/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.snapshots;

import com.google.common.collect.ImmutableMap;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.cluster.*;
import org.elasticsearch.cluster.metadata.SnapshotId;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.snapshots.IndexShardSnapshotAndRestoreService;
import org.elasticsearch.index.snapshots.IndexShardSnapshotStatus;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;
import static org.elasticsearch.cluster.SnapshotsInProgress.completed;

/**
 * This service runs on data and master nodes and controls currently snapshotted shards on these nodes. It is responsible for
 * starting and stopping shard level snapshots
 */
public class SnapshotShardsService extends AbstractLifecycleComponent<SnapshotShardsService> implements ClusterStateListener {

    public static final String UPDATE_SNAPSHOT_ACTION_NAME = "internal:cluster/snapshot/update_snapshot";

    private final ClusterService clusterService;

    private final IndicesService indicesService;

    private final SnapshotsService snapshotsService;

    private final TransportService transportService;

    private final ThreadPool threadPool;

    private final Lock shutdownLock = new ReentrantLock();

    private final Condition shutdownCondition = shutdownLock.newCondition();

    private volatile ImmutableMap<SnapshotId, SnapshotShards> shardSnapshots = ImmutableMap.of();

    private final BlockingQueue<UpdateIndexShardSnapshotStatusRequest> updatedSnapshotStateQueue = ConcurrentCollections.newBlockingQueue();


    @Inject
    public SnapshotShardsService(Settings settings, ClusterService clusterService, SnapshotsService snapshotsService, ThreadPool threadPool,
                                 TransportService transportService, IndicesService indicesService) {
        super(settings);
        this.indicesService = indicesService;
        this.snapshotsService = snapshotsService;
        this.transportService = transportService;
        this.clusterService = clusterService;
        this.threadPool = threadPool;
        if (DiscoveryNode.dataNode(settings)) {
            // this is only useful on the nodes that can hold data
            // addLast to make sure that Repository will be created before snapshot
            clusterService.addLast(this);
        }

        if (DiscoveryNode.masterNode(settings)) {
            // This needs to run only on nodes that can become masters
            transportService.registerRequestHandler(UPDATE_SNAPSHOT_ACTION_NAME, UpdateIndexShardSnapshotStatusRequest.class, ThreadPool.Names.SAME, new UpdateSnapshotStateRequestHandler());
        }

    }

    @Override
    protected void doStart() {

    }

    @Override
    protected void doStop() {
        shutdownLock.lock();
        try {
            while(!shardSnapshots.isEmpty() && shutdownCondition.await(5, TimeUnit.SECONDS)) {
                // Wait for at most 5 second for locally running snapshots to finish
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            shutdownLock.unlock();
        }

    }

    @Override
    protected void doClose() {
        clusterService.remove(this);
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        try {
            SnapshotsInProgress prev = event.previousState().custom(SnapshotsInProgress.TYPE);
            SnapshotsInProgress curr = event.state().custom(SnapshotsInProgress.TYPE);

            if (prev == null) {
                if (curr != null) {
                    processIndexShardSnapshots(event);
                }
            } else if (prev.equals(curr) == false) {
                    processIndexShardSnapshots(event);
            }
            String masterNodeId = event.state().nodes().masterNodeId();
            if (masterNodeId != null && masterNodeId.equals(event.previousState().nodes().masterNodeId()) == false) {
                syncShardStatsOnNewMaster(event);
            }

        } catch (Throwable t) {
            logger.warn("Failed to update snapshot state ", t);
        }
    }


    /**
     * Returns status of shards that are snapshotted on the node and belong to the given snapshot
     * <p>
     * This method is executed on data node
     * </p>
     *
     * @param snapshotId snapshot id
     * @return map of shard id to snapshot status
     */
    public Map<ShardId, IndexShardSnapshotStatus> currentSnapshotShards(SnapshotId snapshotId) {
        SnapshotShards snapshotShards = shardSnapshots.get(snapshotId);
        if (snapshotShards == null) {
            return null;
        } else {
            return snapshotShards.shards;
        }
    }

    /**
     * Checks if any new shards should be snapshotted on this node
     *
     * @param event cluster state changed event
     */
    private void processIndexShardSnapshots(ClusterChangedEvent event) {
        SnapshotsInProgress snapshotsInProgress = event.state().custom(SnapshotsInProgress.TYPE);
        Map<SnapshotId, SnapshotShards> survivors = newHashMap();
        // First, remove snapshots that are no longer there
        for (Map.Entry<SnapshotId, SnapshotShards> entry : shardSnapshots.entrySet()) {
            if (snapshotsInProgress != null && snapshotsInProgress.snapshot(entry.getKey()) != null) {
                survivors.put(entry.getKey(), entry.getValue());
            }
        }

        // For now we will be mostly dealing with a single snapshot at a time but might have multiple simultaneously running
        // snapshots in the future
        Map<SnapshotId, Map<ShardId, IndexShardSnapshotStatus>> newSnapshots = newHashMap();
        // Now go through all snapshots and update existing or create missing
        final String localNodeId = clusterService.localNode().id();
        if (snapshotsInProgress != null) {
            for (SnapshotsInProgress.Entry entry : snapshotsInProgress.entries()) {
                if (entry.state() == SnapshotsInProgress.State.STARTED) {
                    Map<ShardId, IndexShardSnapshotStatus> startedShards = newHashMap();
                    SnapshotShards snapshotShards = shardSnapshots.get(entry.snapshotId());
                    for (Map.Entry<ShardId, SnapshotsInProgress.ShardSnapshotStatus> shard : entry.shards().entrySet()) {
                        // Add all new shards to start processing on
                        if (localNodeId.equals(shard.getValue().nodeId())) {
                            if (shard.getValue().state() == SnapshotsInProgress.State.INIT && (snapshotShards == null || !snapshotShards.shards.containsKey(shard.getKey()))) {
                                logger.trace("[{}] - Adding shard to the queue", shard.getKey());
                                startedShards.put(shard.getKey(), new IndexShardSnapshotStatus());
                            }
                        }
                    }
                    if (!startedShards.isEmpty()) {
                        newSnapshots.put(entry.snapshotId(), startedShards);
                        if (snapshotShards != null) {
                            // We already saw this snapshot but we need to add more started shards
                            ImmutableMap.Builder<ShardId, IndexShardSnapshotStatus> shards = ImmutableMap.builder();
                            // Put all shards that were already running on this node
                            shards.putAll(snapshotShards.shards);
                            // Put all newly started shards
                            shards.putAll(startedShards);
                            survivors.put(entry.snapshotId(), new SnapshotShards(shards.build()));
                        } else {
                            // Brand new snapshot that we haven't seen before
                            survivors.put(entry.snapshotId(), new SnapshotShards(ImmutableMap.copyOf(startedShards)));
                        }
                    }
                } else if (entry.state() == SnapshotsInProgress.State.ABORTED) {
                    // Abort all running shards for this snapshot
                    SnapshotShards snapshotShards = shardSnapshots.get(entry.snapshotId());
                    if (snapshotShards != null) {
                        for (Map.Entry<ShardId, SnapshotsInProgress.ShardSnapshotStatus> shard : entry.shards().entrySet()) {
                            IndexShardSnapshotStatus snapshotStatus = snapshotShards.shards.get(shard.getKey());
                            if (snapshotStatus != null) {
                                switch (snapshotStatus.stage()) {
                                    case INIT:
                                    case STARTED:
                                        snapshotStatus.abort();
                                        break;
                                    case FINALIZE:
                                        logger.debug("[{}] trying to cancel snapshot on shard [{}] that is finalizing, letting it finish", entry.snapshotId(), shard.getKey());
                                        break;
                                    case DONE:
                                        logger.debug("[{}] trying to cancel snapshot on the shard [{}] that is already done, updating status on the master", entry.snapshotId(), shard.getKey());
                                        updateIndexShardSnapshotStatus(entry.snapshotId(), shard.getKey(),
                                                new SnapshotsInProgress.ShardSnapshotStatus(event.state().nodes().localNodeId(), SnapshotsInProgress.State.SUCCESS));
                                        break;
                                    case FAILURE:
                                        logger.debug("[{}] trying to cancel snapshot on the shard [{}] that has already failed, updating status on the master", entry.snapshotId(), shard.getKey());
                                        updateIndexShardSnapshotStatus(entry.snapshotId(), shard.getKey(),
                                                new SnapshotsInProgress.ShardSnapshotStatus(event.state().nodes().localNodeId(), SnapshotsInProgress.State.FAILED, snapshotStatus.failure()));
                                        break;
                                    default:
                                        throw new IllegalStateException("Unknown snapshot shard stage " + snapshotStatus.stage());
                                }
                            }
                        }
                    }
                }
            }
        }

        // Update the list of snapshots that we saw and tried to started
        // If startup of these shards fails later, we don't want to try starting these shards again
        shutdownLock.lock();
        try {
            shardSnapshots = ImmutableMap.copyOf(survivors);
            if (shardSnapshots.isEmpty()) {
                // Notify all waiting threads that no more snapshots
                shutdownCondition.signalAll();
            }
        } finally {
            shutdownLock.unlock();
        }

        // We have new shards to starts
        if (newSnapshots.isEmpty() == false) {
            Executor executor = threadPool.executor(ThreadPool.Names.SNAPSHOT);
            for (final Map.Entry<SnapshotId, Map<ShardId, IndexShardSnapshotStatus>> entry : newSnapshots.entrySet()) {
                for (final Map.Entry<ShardId, IndexShardSnapshotStatus> shardEntry : entry.getValue().entrySet()) {
                    try {
                        final IndexShardSnapshotAndRestoreService shardSnapshotService = indicesService.indexServiceSafe(shardEntry.getKey().getIndex()).shardInjectorSafe(shardEntry.getKey().id())
                                .getInstance(IndexShardSnapshotAndRestoreService.class);
                        executor.execute(new AbstractRunnable() {
                            @Override
                            public void doRun() {
                                shardSnapshotService.snapshot(entry.getKey(), shardEntry.getValue());
                                updateIndexShardSnapshotStatus(entry.getKey(), shardEntry.getKey(), new SnapshotsInProgress.ShardSnapshotStatus(localNodeId, SnapshotsInProgress.State.SUCCESS));
                            }

                            @Override
                            public void onFailure(Throwable t) {
                                logger.warn("[{}] [{}] failed to create snapshot", t, shardEntry.getKey(), entry.getKey());
                                updateIndexShardSnapshotStatus(entry.getKey(), shardEntry.getKey(), new SnapshotsInProgress.ShardSnapshotStatus(localNodeId, SnapshotsInProgress.State.FAILED, ExceptionsHelper.detailedMessage(t)));
                            }

                        });
                    } catch (Throwable t) {
                        updateIndexShardSnapshotStatus(entry.getKey(), shardEntry.getKey(), new SnapshotsInProgress.ShardSnapshotStatus(localNodeId, SnapshotsInProgress.State.FAILED, ExceptionsHelper.detailedMessage(t)));
                    }
                }
            }
        }
    }

    /**
     * Checks if any shards were processed that the new master doesn't know about
     * @param event
     */
    private void syncShardStatsOnNewMaster(ClusterChangedEvent event) {
        SnapshotsInProgress snapshotsInProgress = event.state().custom(SnapshotsInProgress.TYPE);
        if (snapshotsInProgress == null) {
            return;
        }
        for (SnapshotsInProgress.Entry snapshot : snapshotsInProgress.entries()) {
            if (snapshot.state() == SnapshotsInProgress.State.STARTED || snapshot.state() == SnapshotsInProgress.State.ABORTED) {
                Map<ShardId, IndexShardSnapshotStatus> localShards = currentSnapshotShards(snapshot.snapshotId());
                if (localShards != null) {
                    ImmutableMap<ShardId, SnapshotsInProgress.ShardSnapshotStatus> masterShards = snapshot.shards();
                    for(Map.Entry<ShardId, IndexShardSnapshotStatus> localShard : localShards.entrySet()) {
                        ShardId shardId = localShard.getKey();
                        IndexShardSnapshotStatus localShardStatus = localShard.getValue();
                        SnapshotsInProgress.ShardSnapshotStatus masterShard = masterShards.get(shardId);
                        if (masterShard != null && masterShard.state().completed() == false) {
                            // Master knows about the shard and thinks it has not completed
                            if (localShardStatus.stage() == IndexShardSnapshotStatus.Stage.DONE) {
                                // but we think the shard is done - we need to make new master know that the shard is done
                                logger.debug("[{}] new master thinks the shard [{}] is not completed but the shard is done locally, updating status on the master", snapshot.snapshotId(), shardId);
                                updateIndexShardSnapshotStatus(snapshot.snapshotId(), shardId,
                                        new SnapshotsInProgress.ShardSnapshotStatus(event.state().nodes().localNodeId(), SnapshotsInProgress.State.SUCCESS));
                            } else if (localShard.getValue().stage() == IndexShardSnapshotStatus.Stage.FAILURE) {
                                // but we think the shard failed - we need to make new master know that the shard failed
                                logger.debug("[{}] new master thinks the shard [{}] is not completed but the shard failed locally, updating status on master", snapshot.snapshotId(), shardId);
                                updateIndexShardSnapshotStatus(snapshot.snapshotId(), shardId,
                                        new SnapshotsInProgress.ShardSnapshotStatus(event.state().nodes().localNodeId(), SnapshotsInProgress.State.FAILED, localShardStatus.failure()));

                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Stores the list of shards that has to be snapshotted on this node
     */
    private static class SnapshotShards {
        private final Map<ShardId, IndexShardSnapshotStatus> shards;

        private SnapshotShards(ImmutableMap<ShardId, IndexShardSnapshotStatus> shards) {
            this.shards = shards;
        }
    }


    /**
     * Internal request that is used to send changes in snapshot status to master
     */
    private static class UpdateIndexShardSnapshotStatusRequest extends TransportRequest {
        private SnapshotId snapshotId;
        private ShardId shardId;
        private SnapshotsInProgress.ShardSnapshotStatus status;

        private volatile boolean processed; // state field, no need to serialize

        public UpdateIndexShardSnapshotStatusRequest() {

        }

        public UpdateIndexShardSnapshotStatusRequest(SnapshotId snapshotId, ShardId shardId, SnapshotsInProgress.ShardSnapshotStatus status) {
            this.snapshotId = snapshotId;
            this.shardId = shardId;
            this.status = status;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            snapshotId = SnapshotId.readSnapshotId(in);
            shardId = ShardId.readShardId(in);
            status = SnapshotsInProgress.ShardSnapshotStatus.readShardSnapshotStatus(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            snapshotId.writeTo(out);
            shardId.writeTo(out);
            status.writeTo(out);
        }

        public SnapshotId snapshotId() {
            return snapshotId;
        }

        public ShardId shardId() {
            return shardId;
        }

        public SnapshotsInProgress.ShardSnapshotStatus status() {
            return status;
        }

        @Override
        public String toString() {
            return "" + snapshotId + ", shardId [" + shardId + "], status [" + status.state() + "]";
        }

        public void markAsProcessed() {
            processed = true;
        }

        public boolean isProcessed() {
            return processed;
        }
    }

    /**
     * Updates the shard status
     */
    public void updateIndexShardSnapshotStatus(SnapshotId snapshotId, ShardId shardId, SnapshotsInProgress.ShardSnapshotStatus status) {
        UpdateIndexShardSnapshotStatusRequest request = new UpdateIndexShardSnapshotStatusRequest(snapshotId, shardId, status);
        try {
            if (clusterService.state().nodes().localNodeMaster()) {
                innerUpdateSnapshotState(request);
            } else {
                transportService.sendRequest(clusterService.state().nodes().masterNode(),
                        UPDATE_SNAPSHOT_ACTION_NAME, request, EmptyTransportResponseHandler.INSTANCE_SAME);
            }
        } catch (Throwable t) {
            logger.warn("[{}] [{}] failed to update snapshot state", t, request.snapshotId(), request.status());
        }
    }

    /**
     * Updates the shard status on master node
     *
     * @param request update shard status request
     */
    private void innerUpdateSnapshotState(final UpdateIndexShardSnapshotStatusRequest request) {
        logger.trace("received updated snapshot restore state [{}]", request);
        updatedSnapshotStateQueue.add(request);

        clusterService.submitStateUpdateTask("update snapshot state", new ClusterStateUpdateTask() {
            private final List<UpdateIndexShardSnapshotStatusRequest> drainedRequests = new ArrayList<>();

            @Override
            public ClusterState execute(ClusterState currentState) {
                // The request was already processed as a part of an early batch - skipping
                if (request.isProcessed()) {
                    return currentState;
                }

                updatedSnapshotStateQueue.drainTo(drainedRequests);

                final int batchSize = drainedRequests.size();

                // nothing to process (a previous event has processed it already)
                if (batchSize == 0) {
                    return currentState;
                }

                final SnapshotsInProgress snapshots = currentState.custom(SnapshotsInProgress.TYPE);
                if (snapshots != null) {
                    int changedCount = 0;
                    final List<SnapshotsInProgress.Entry> entries = newArrayList();
                    for (SnapshotsInProgress.Entry entry : snapshots.entries()) {
                        final Map<ShardId, SnapshotsInProgress.ShardSnapshotStatus> shards = newHashMap();
                        boolean updated = false;

                        for (int i = 0; i < batchSize; i++) {
                            final UpdateIndexShardSnapshotStatusRequest updateSnapshotState = drainedRequests.get(i);
                            updateSnapshotState.markAsProcessed();

                            if (entry.snapshotId().equals(updateSnapshotState.snapshotId())) {
                                logger.trace("[{}] Updating shard [{}] with status [{}]", updateSnapshotState.snapshotId(), updateSnapshotState.shardId(), updateSnapshotState.status().state());
                                if (updated == false) {
                                    shards.putAll(entry.shards());
                                    updated = true;
                                }
                                shards.put(updateSnapshotState.shardId(), updateSnapshotState.status());
                                changedCount++;
                            }
                        }

                        if (updated) {
                            if (completed(shards.values()) == false) {
                                entries.add(new SnapshotsInProgress.Entry(entry, ImmutableMap.copyOf(shards)));
                            } else {
                                // Snapshot is finished - mark it as done
                                // TODO: Add PARTIAL_SUCCESS status?
                                SnapshotsInProgress.Entry updatedEntry = new SnapshotsInProgress.Entry(entry, SnapshotsInProgress.State.SUCCESS, ImmutableMap.copyOf(shards));
                                entries.add(updatedEntry);
                                // Finalize snapshot in the repository
                                snapshotsService.endSnapshot(updatedEntry);
                                logger.info("snapshot [{}] is done", updatedEntry.snapshotId());
                            }
                        } else {
                            entries.add(entry);
                        }
                    }
                    if (changedCount > 0) {
                        logger.trace("changed cluster state triggered by {} snapshot state updates", changedCount);

                        final SnapshotsInProgress updatedSnapshots = new SnapshotsInProgress(entries.toArray(new SnapshotsInProgress.Entry[entries.size()]));
                        return ClusterState.builder(currentState).putCustom(SnapshotsInProgress.TYPE, updatedSnapshots).build();
                    }
                }
                return currentState;
            }

            @Override
            public void onFailure(String source, Throwable t) {
                for (UpdateIndexShardSnapshotStatusRequest request : drainedRequests) {
                    logger.warn("[{}][{}] failed to update snapshot status to [{}]", t, request.snapshotId(), request.shardId(), request.status());
                }
            }
        });
    }

    /**
     * Transport request handler that is used to send changes in snapshot status to master
     */
    class UpdateSnapshotStateRequestHandler implements TransportRequestHandler<UpdateIndexShardSnapshotStatusRequest> {
        @Override
        public void messageReceived(UpdateIndexShardSnapshotStatusRequest request, final TransportChannel channel) throws Exception {
            innerUpdateSnapshotState(request);
            channel.sendResponse(TransportResponse.Empty.INSTANCE);
        }
    }

}