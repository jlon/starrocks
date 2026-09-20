// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/catalog/TabletStatMgr.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.catalog;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.alter.reshard.TabletReshardUtils;
import com.starrocks.catalog.MaterializedIndex.IndexExtState;
import com.starrocks.common.Config;
import com.starrocks.common.ErrorReportException;
import com.starrocks.common.Pair;
import com.starrocks.common.ThreadPoolManager;
import com.starrocks.common.util.FrontendDaemon;
import com.starrocks.common.util.concurrent.lock.LockType;
import com.starrocks.common.util.concurrent.lock.Locker;
import com.starrocks.lake.LakeTablet;
import com.starrocks.proto.TabletStatRequest;
import com.starrocks.proto.TabletStatRequest.TabletInfo;
import com.starrocks.proto.TabletStatResponse;
import com.starrocks.proto.TabletStatResponse.TabletStat;
import com.starrocks.rpc.BrpcProxy;
import com.starrocks.rpc.LakeService;
import com.starrocks.rpc.ThriftConnectionPool;
import com.starrocks.rpc.ThriftRPCRequestExecutor;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.RunMode;
import com.starrocks.server.WarehouseManager;
import com.starrocks.statistic.BasicStatsMeta;
import com.starrocks.system.Backend;
import com.starrocks.system.ComputeNode;
import com.starrocks.thrift.BackendService;
import com.starrocks.thrift.TNetworkAddress;
import com.starrocks.thrift.TTabletStat;
import com.starrocks.thrift.TTabletStatResult;
import com.starrocks.warehouse.cngroup.ComputeResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;

/*
 * TabletStatMgr is for collecting tablet(replica) statistics from backends.
 * Each FE will collect by itself.
 */
public class TabletStatMgr extends FrontendDaemon {
    private static final Logger LOG = LogManager.getLogger(TabletStatMgr.class);
    private static final String LAKE_TABLET_STAT_COLLECTOR_POOL_NAME = "lake-tablet-stat-collector";

    private LocalDateTime lastWorkTimestamp = LocalDateTime.MIN;
    private final Object lakeTabletStatExecutorLock = new Object();
    private ThreadPoolExecutor lakeTabletStatExecutor;
    private int lakeTabletStatExecutorParallelism = 0;
    private int lakeTabletStatExecutorMaxInflightTasks = 0;
    private final List<LakeTabletStatCollector> activeLakeTabletStatCollectors = Lists.newArrayList();
    private boolean lakeTabletStatStopRequested = false;
    private boolean lakeTabletStatExecutorShutdownRequested = false;

    public TabletStatMgr() {
        super("tablet-stat-mgr", Config.tablet_stat_update_interval_second * 1000L);
    }

    public LocalDateTime getLastWorkTimestamp() {
        return lastWorkTimestamp;
    }

    @Override
    public void setStop() {
        super.setStop();
        List<LakeTabletStatCollector> activeCollectors;
        synchronized (lakeTabletStatExecutorLock) {
            lakeTabletStatStopRequested = true;
            activeCollectors = Lists.newArrayList(activeLakeTabletStatCollectors);
        }
        for (LakeTabletStatCollector collector : activeCollectors) {
            collector.requestStop();
        }
        interrupt();
        shutdownLakeTabletStatExecutorIfIdle();
    }

    private void shutdownLakeTabletStatExecutorIfIdle() {
        synchronized (lakeTabletStatExecutorLock) {
            if (!activeLakeTabletStatCollectors.isEmpty()) {
                return;
            }
            shutdownLakeTabletStatExecutorLocked();
        }
    }

    private void shutdownLakeTabletStatExecutorLocked() {
        if (lakeTabletStatExecutor == null) {
            return;
        }
        lakeTabletStatExecutor.shutdownNow();
        lakeTabletStatExecutor = null;
        lakeTabletStatExecutorParallelism = 0;
        lakeTabletStatExecutorMaxInflightTasks = 0;
        lakeTabletStatExecutorShutdownRequested = false;
    }

    private void registerLakeTabletStatCollector(LakeTabletStatCollector collector) {
        boolean shouldStop;
        synchronized (lakeTabletStatExecutorLock) {
            activeLakeTabletStatCollectors.add(collector);
            shouldStop = lakeTabletStatStopRequested;
        }
        if (shouldStop) {
            collector.requestStop();
        }
    }

    private void finishLakeTabletStatCollector(LakeTabletStatCollector collector, boolean shutdownExecutor) {
        synchronized (lakeTabletStatExecutorLock) {
            activeLakeTabletStatCollectors.remove(collector);
            lakeTabletStatExecutorShutdownRequested |= shutdownExecutor;
            if ((lakeTabletStatStopRequested || lakeTabletStatExecutorShutdownRequested)
                    && activeLakeTabletStatCollectors.isEmpty()) {
                shutdownLakeTabletStatExecutorLocked();
            }
        }
    }

    public boolean workTimeIsMustAfter(LocalDateTime time) {
        if (lastWorkTimestamp.isEqual(LocalDateTime.MIN)) {
            return false;
        }
        return lastWorkTimestamp.minusSeconds(Config.tablet_stat_update_interval_second * 2).isAfter(time);
    }

    @Override
    protected void runAfterCatalogReady() {
        // update interval
        if (getInterval() != Config.tablet_stat_update_interval_second * 1000) {
            setInterval(Config.tablet_stat_update_interval_second * 1000);
        }

        // for testing statistic behavior
        if (!Config.enable_sync_tablet_stats) {
            return;
        }

        acquireBackgroundComputeResource();
        updateLocalTabletStat();
        updateLakeTabletStat();

        // after update replica in all backends, update index row num
        long start = System.currentTimeMillis();
        for (Long dbId : GlobalStateMgr.getCurrentState().getLocalMetastore().getDbIds()) {
            Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbId);
            if (db == null) {
                continue;
            }
            Locker locker = new Locker();
            for (Table table : GlobalStateMgr.getCurrentState().getLocalMetastore().getTables(db.getId())) {
                if (!table.isNativeTableOrMaterializedView()) {
                    continue;
                }

                long totalRowCount = 0L;
                long maxTabletSize = 0L;
                long minAdjacentTabletPairSize = Long.MAX_VALUE;
                Map<Pair<Long, Long>, Long> indexRowCountMap = Maps.newHashMap();
                Map<Pair<Long, Long>, Boolean> indexCountFastPathSafeMap = Maps.newHashMap();
                // NOTE: calculate the row first with read lock, then update the stats with write lock
                OlapTable olapTable = (OlapTable) table;
                // Reshard is leader-only (TabletStatMgr runs on all FEs), and only for cloud-native
                // range-distribution tables. This gates the parallelism-floor lookup (a StarMgr RPC),
                // the adjacency walk, and the reshard trigger — none of which should run on followers.
                boolean reshardEligible = GlobalStateMgr.getCurrentState().isLeader()
                        && olapTable.isCloudNativeTableOrMaterializedView()
                        && olapTable.isRangeDistribution();
                int parallelismFloor = reshardEligible
                        ? TabletReshardUtils.safeComputeParallelismFloor(table.getId()) : 0;
                locker.lockTableWithIntensiveDbLock(db.getId(), table.getId(), LockType.READ);
                try {
                    for (Partition partition : olapTable.getAllPartitions()) {
                        for (PhysicalPartition physicalPartition : partition.getSubPartitions()) {
                            long version = physicalPartition.getVisibleVersion();
                            long visibleVersionTime = physicalPartition.getVisibleVersionTime();
                            for (MaterializedIndex index : physicalPartition.getLatestMaterializedIndices(
                                    IndexExtState.VISIBLE)) {
                                long indexRowCount = 0L;
                                List<Tablet> tablets = index.getTablets();
                                // Only an index above the parallelism floor contributes the merge signal
                                // (minAdjacentTabletPairSize); otherwise auto-merge could shrink it below the
                                // tablet count pre-split established for parallelism (and would churn empty
                                // merge jobs every cycle). Split detection (maxTabletSize) is never gated.
                                // MergeTabletJobFactory's per-index merge budget re-enforces the same floor
                                // inside an admitted job, so the floor holds even for manual size-based merges.
                                boolean eligibleForMerge = tablets.size() > parallelismFloor;
                                boolean indexCountFastPathSafe = true;
                                long prevFreshTabletSize = -1L;
                                // NOTE: can take a rather long time to iterate lots of tablets
                                for (Tablet tablet : tablets) {
                                    indexRowCount += tablet.getRowCount(version);
                                    indexCountFastPathSafe &= tablet instanceof LakeTablet
                                            && ((LakeTablet) tablet).isCountFastPathSafe(visibleVersionTime);
                                    long dataSize = tablet.getDataSize(true);
                                    maxTabletSize = Math.max(maxTabletSize, dataSize);
                                    if (!(tablet instanceof LakeTablet)
                                            || ((LakeTablet) tablet).getDataSizeUpdateTime() < visibleVersionTime) {
                                        prevFreshTabletSize = -1L;
                                        continue;
                                    }
                                    if (prevFreshTabletSize >= 0 && eligibleForMerge) {
                                        minAdjacentTabletPairSize = Math.min(minAdjacentTabletPairSize,
                                                prevFreshTabletSize + dataSize);
                                    }
                                    prevFreshTabletSize = dataSize;
                                } // end for tablets
                                indexRowCountMap.put(Pair.create(physicalPartition.getId(), index.getId()),
                                        indexRowCount);
                                indexCountFastPathSafeMap.put(Pair.create(physicalPartition.getId(), index.getId()),
                                        indexCountFastPathSafe);
                                if (!olapTable.isTempPartition(partition.getId())) {
                                    totalRowCount += indexRowCount;
                                }
                            } // end for indices
                        } // end for physical partitions
                    } // end for partitions
                    LOG.debug("finished to set row num for table: {} in database: {}",
                            table.getName(), db.getFullName());
                } finally {
                    locker.unLockTableWithIntensiveDbLock(db.getId(), table.getId(), LockType.READ);
                }

                // update
                locker.lockTableWithIntensiveDbLock(db.getId(), table.getId(), LockType.WRITE);
                try {
                    for (Partition partition : olapTable.getAllPartitions()) {
                        for (PhysicalPartition physicalPartition : partition.getSubPartitions()) {
                            for (MaterializedIndex index :
                                    physicalPartition.getLatestMaterializedIndices(IndexExtState.VISIBLE)) {
                                Long indexRowCount =
                                        indexRowCountMap.get(Pair.create(physicalPartition.getId(), index.getId()));
                                if (indexRowCount != null) {
                                    index.setRowCount(indexRowCount);
                                }
                                Boolean countFastPathSafe = indexCountFastPathSafeMap.get(
                                        Pair.create(physicalPartition.getId(), index.getId()));
                                if (countFastPathSafe != null) {
                                    index.setCountFastPathSafe(countFastPathSafe);
                                }
                            }
                        }
                    }
                    adjustStatUpdateRows(table.getId(), totalRowCount);
                } finally {
                    locker.unLockTableWithIntensiveDbLock(db.getId(), table.getId(), LockType.WRITE);
                }

                // Emit a reshard candidate with the signals computed above; addReshardCandidate drops
                // non-actionable signals and the TabletReshardJobMgr drain owns job creation. This
                // periodic scan is the fallback for the publish-driven path, so unlike publish it
                // carries the merge signal too.
                if (reshardEligible) {
                    GlobalStateMgr.getCurrentState().getTabletReshardJobMgr().addReshardCandidate(
                            db.getId(), olapTable.getId(), maxTabletSize, minAdjacentTabletPairSize);
                }
            }
        }
        LOG.info("finished to update index row num of all databases. cost: {} ms",
                (System.currentTimeMillis() - start));
        lastWorkTimestamp = LocalDateTime.now();
    }

    private void updateLocalTabletStat() {
        if (!RunMode.isSharedNothingMode()) {
            return;
        }
        ImmutableMap<Long, Backend> backends =
                GlobalStateMgr.getCurrentState().getNodeMgr().getClusterInfo().getIdToBackend();

        long start = System.currentTimeMillis();
        for (Backend backend : backends.values()) {
            try {
                TTabletStatResult result = ThriftRPCRequestExecutor.callNoRetry(
                        ThriftConnectionPool.backendPool,
                        new TNetworkAddress(backend.getHost(), backend.getBePort()),
                        BackendService.Client::get_tablet_stat);
                LOG.debug("get tablet stat from backend: {}, num: {}", backend.getId(), result.getTablets_statsSize());
                updateLocalTabletStat(backend.getId(), result);

            } catch (Exception e) {
                LOG.warn("task exec error. backend[{}]", backend.getId(), e);
            }
        }
        LOG.info("finished to get local tablet stat of all backends. cost: {} ms",
                (System.currentTimeMillis() - start));
    }

    private void updateLocalTabletStat(Long beId, TTabletStatResult result) {
        TabletInvertedIndex invertedIndex = GlobalStateMgr.getCurrentState().getTabletInvertedIndex();
        for (Map.Entry<Long, TTabletStat> entry : result.getTablets_stats().entrySet()) {
            if (invertedIndex.getTabletMeta(entry.getKey()) == null) {
                // the replica is obsolete, ignore it.
                continue;
            }

            // Currently, only local table will update replica.
            Replica replica = invertedIndex.getReplica(entry.getKey(), beId);
            if (replica == null) {
                // replica may be deleted from catalog, ignore it.
                continue;
            }
            // TODO(cmy) no db lock protected. I think it is ok even we get wrong row num
            replica.updateStat(
                    entry.getValue().getData_size(),
                    entry.getValue().getRow_num(),
                    entry.getValue().getVersion_count()
            );
        }
    }

    private void updateLakeTabletStat() {
        if (!RunMode.isSharedDataMode()) {
            return;
        }

        if (Config.enable_lake_tablet_stat_cn_batch_collection) {
            updateLakeTabletStatCnBatch();
            return;
        }
        if (Config.enable_parallel_lake_tablet_stat_collection) {
            updateLakeTabletStatParallel();
            return;
        }

        List<Long> dbIds = GlobalStateMgr.getCurrentState().getLocalMetastore().getDbIds();
        for (Long dbId : dbIds) {
            Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbId);
            if (db == null) {
                continue;
            }

            List<Table> tables = GlobalStateMgr.getCurrentState().getLocalMetastore().getTables(db.getId());
            for (Table table : tables) {
                if (table.isCloudNativeTableOrMaterializedView()) {
                    updateLakeTableTabletStat(db, (OlapTable) table);
                }
            }
        }
    }

    private void updateLakeTabletStatParallel() {
        long start = System.currentTimeMillis();
        LakeTabletStatCollector collector = newLakeTabletStatCollector("all databases");
        try {
            List<Long> dbIds = GlobalStateMgr.getCurrentState().getLocalMetastore().getDbIds();
            for (Long dbId : dbIds) {
                Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbId);
                if (db == null) {
                    continue;
                }

                List<Table> tables = GlobalStateMgr.getCurrentState().getLocalMetastore().getTables(db.getId());
                for (Table table : tables) {
                    if (table.isCloudNativeTableOrMaterializedView()) {
                        submitLakeTableTabletStatJobs(collector, db, (OlapTable) table);
                    }
                }
            }
            collector.waitAll();
        } finally {
            collector.close();
        }
        collector.logSummary(System.currentTimeMillis() - start);
    }

    // Collect lake tablet stats by aggregating stale tablets per compute node into batched
    // get_tablet_stats requests, instead of one request per physical partition. Batches preserve
    // per-partition (i.e. bundle) locality so a CN can reuse a bundle metadata read across the
    // tablets of the same partition inside one request.
    private void updateLakeTabletStatCnBatch() {
        long start = System.currentTimeMillis();
        LakeTabletStatCollector collector = newLakeTabletStatCollector("all databases (cn-batch)");
        try {
            WarehouseManager warehouseManager = GlobalStateMgr.getCurrentState().getWarehouseMgr();
            CnBatchAccumulator accumulator = new CnBatchAccumulator(collector);

            List<Long> dbIds = GlobalStateMgr.getCurrentState().getLocalMetastore().getDbIds();
            for (Long dbId : dbIds) {
                if (collector.isInterrupted()) {
                    break;
                }
                Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbId);
                if (db == null) {
                    continue;
                }
                for (Table table : GlobalStateMgr.getCurrentState().getLocalMetastore().getTables(db.getId())) {
                    if (collector.isInterrupted()) {
                        break;
                    }
                    if (table.isCloudNativeTableOrMaterializedView()) {
                        collectStaleTabletsAndSubmitBatches(db, (OlapTable) table, warehouseManager, accumulator);
                    }
                }
            }

            accumulator.flushAll();
            collector.waitAll();
        } finally {
            collector.close();
        }
        collector.logSummary(System.currentTimeMillis() - start);
    }

    // Scan one table's physical partitions under a short read lock. Stale tablets are grouped by
    // owning CN per partition before they enter the accumulator, so batch construction preserves
    // bundle locality unless one partition alone exceeds the configured batch size.
    private void collectStaleTabletsAndSubmitBatches(@NotNull Database db, @NotNull OlapTable table,
                                                     @NotNull WarehouseManager warehouseManager,
                                                     @NotNull CnBatchAccumulator accumulator) {
        for (PhysicalPartition partition : getPartitions(db, table)) {
            if (accumulator.isInterrupted()) {
                return;
            }
            PartitionSnapshot snapshot = createPartitionSnapshot(db, table, partition);
            long visibleVersion = snapshot.visibleVersion;
            if (isInitialEmptyPartition(visibleVersion)) {
                continue;
            }
            long visibleVersionTime = snapshot.visibleVersionTime;
            Map<Long, ComputeNode> nodeById = new LinkedHashMap<>();
            Map<Long, List<TabletStatEntry>> partitionTabletsByNode = new LinkedHashMap<>();
            for (Tablet tablet : snapshot.tablets) {
                LakeTablet lakeTablet = (LakeTablet) tablet;
                if (isLakeTabletStatFresh(lakeTablet, visibleVersionTime, table.hasDelete())) {
                    continue;
                }
                ComputeNode node;
                try {
                    node = warehouseManager.getComputeNodeAssignedToTablet(computeResource, tablet.getId());
                } catch (ErrorReportException e) {
                    continue;
                }
                if (node == null) {
                    continue;
                }
                nodeById.putIfAbsent(node.getId(), node);
                partitionTabletsByNode.computeIfAbsent(node.getId(), k -> new ArrayList<>())
                        .add(new TabletStatEntry(tablet.getId(), lakeTablet, visibleVersion));
            }
            for (Map.Entry<Long, List<TabletStatEntry>> entry : partitionTabletsByNode.entrySet()) {
                if (accumulator.isInterrupted()) {
                    return;
                }
                accumulator.addPartitionGroup(nodeById.get(entry.getKey()), entry.getValue());
            }
        }
    }

    private void adjustStatUpdateRows(long tableId, long totalRowCount) {
        BasicStatsMeta meta = GlobalStateMgr.getCurrentState().getAnalyzeMgr().getTableBasicStatsMeta(tableId);
        if (meta != null) {
            meta.setTotalRows(totalRowCount);
            meta.resetDeltaRows();
            meta.updateTabletStatsReportTime();
        }
    }

    @NotNull
    private Collection<PhysicalPartition> getPartitions(@NotNull Database db, @NotNull OlapTable table) {
        Locker locker = new Locker();
        locker.lockTableWithIntensiveDbLock(db.getId(), table.getId(), LockType.READ);
        try {
            return table.getPhysicalPartitions();
        } finally {
            locker.unLockTableWithIntensiveDbLock(db.getId(), table.getId(), LockType.READ);
        }
    }

    @NotNull
    private PartitionSnapshot createPartitionSnapshot(@NotNull Database db,
                                                      @NotNull OlapTable table,
                                                      @NotNull PhysicalPartition partition) {
        String dbName = db.getFullName();
        String tableName = table.getName();
        long partitionId = partition.getId();
        Locker locker = new Locker();
        locker.lockTableWithIntensiveDbLock(db.getId(), table.getId(), LockType.READ);
        try {
            long visibleVersion = partition.getVisibleVersion();
            long visibleVersionTime = partition.getVisibleVersionTime();
            List<Tablet> tablets = new ArrayList<>(partition.getLatestBaseIndex().getTablets());
            return new PartitionSnapshot(dbName, tableName, partitionId, visibleVersion, visibleVersionTime, tablets);
        } finally {
            locker.unLockTableWithIntensiveDbLock(db.getId(), table.getId(), LockType.READ);
        }
    }

    @Nullable
    private CollectTabletStatJob createCollectTabletStatJob(@NotNull Database db, @NotNull OlapTable table,
                                                            @NotNull PhysicalPartition partition) {
        PartitionSnapshot snapshot = createPartitionSnapshot(db, table, partition);
        if (isInitialEmptyPartition(snapshot.visibleVersion)) {
            LOG.debug("Skipped tablet stat collection of initial empty partition {}", snapshot.debugName());
            return null;
        }
        long visibleVersionTime = snapshot.visibleVersionTime;
        snapshot.tablets.removeIf(t -> isLakeTabletStatFresh((LakeTablet) t, visibleVersionTime, table.hasDelete()));
        if (snapshot.tablets.isEmpty()) {
            LOG.debug("Skipped tablet stat collection of partition {}", snapshot.debugName());
            return null;
        }
        return new CollectTabletStatJob(snapshot, computeResource);
    }

    // A physical partition still at the initial version has never had a load committed, so its row count and
    // data size are guaranteed to be 0. Collecting stats for it would only make the CN read remote initial
    // metadata and, for bundle-optimized tablets, trigger an object-store FileNotFound on the per-tablet
    // metadata path. The same "initial version means no data" semantics are used by ConsistencyChecker.
    private static boolean isInitialEmptyPartition(long visibleVersion) {
        return Config.enable_lake_tablet_stat_skip_initial_version
                && visibleVersion <= PhysicalPartition.PARTITION_INIT_VERSION;
    }

    private static boolean isLakeTabletStatFresh(LakeTablet tablet, long visibleVersionTime,
                                                 boolean requireCountFastPathSafety) {
        return tablet.getDataSizeUpdateTime() >= visibleVersionTime
                && (!requireCountFastPathSafety || tablet.hasCountFastPathSafety(visibleVersionTime));
    }

    private void updateLakeTableTabletStat(@NotNull Database db, @NotNull OlapTable table) {
        if (Config.enable_lake_tablet_stat_cn_batch_collection || Config.enable_parallel_lake_tablet_stat_collection) {
            long start = System.currentTimeMillis();
            String collectorName = db.getFullName() + "." + table.getName()
                    + (Config.enable_lake_tablet_stat_cn_batch_collection ? " (cn-batch)" : "");
            LakeTabletStatCollector collector = newLakeTabletStatCollector(collectorName);
            try {
                if (Config.enable_lake_tablet_stat_cn_batch_collection) {
                    WarehouseManager warehouseManager = GlobalStateMgr.getCurrentState().getWarehouseMgr();
                    CnBatchAccumulator accumulator = new CnBatchAccumulator(collector);
                    collectStaleTabletsAndSubmitBatches(db, table, warehouseManager, accumulator);
                    accumulator.flushAll();
                } else {
                    submitLakeTableTabletStatJobs(collector, db, table);
                }
                collector.waitAll();
            } finally {
                collector.close();
            }
            collector.logSummary(System.currentTimeMillis() - start);
            return;
        }

        updateLakeTableTabletStatSerial(db, table);
    }

    private void updateLakeTableTabletStatSerial(@NotNull Database db, @NotNull OlapTable table) {
        Collection<PhysicalPartition> partitions = getPartitions(db, table);
        for (PhysicalPartition partition : partitions) {
            CollectTabletStatJob job = createCollectTabletStatJob(db, table, partition);
            if (job == null) {
                continue;
            }
            job.execute();
        }
    }

    private LakeTabletStatCollector newLakeTabletStatCollector(String name) {
        int parallelism = lakeTabletStatCollectParallelism();
        int maxInflightTasks = lakeTabletStatMaxInflightTasks(parallelism);
        LakeTabletStatCollector collector = new LakeTabletStatCollector(name, parallelism, maxInflightTasks,
                getLakeTabletStatExecutor(parallelism, maxInflightTasks));
        registerLakeTabletStatCollector(collector);
        return collector;
    }

    private ThreadPoolExecutor getLakeTabletStatExecutor(int parallelism, int maxInflightTasks) {
        synchronized (lakeTabletStatExecutorLock) {
            if (lakeTabletStatExecutor == null || lakeTabletStatExecutor.isShutdown()
                    || lakeTabletStatExecutorMaxInflightTasks != maxInflightTasks) {
                if (lakeTabletStatExecutor != null && !lakeTabletStatExecutor.isShutdown()) {
                    lakeTabletStatExecutor.shutdownNow();
                    LOG.info("Recreated lake tablet stat collector executor because max in-flight changed. " +
                                    "old parallelism: {}, old max in-flight: {}, new parallelism: {}, " +
                                    "new max in-flight: {}",
                            lakeTabletStatExecutorParallelism, lakeTabletStatExecutorMaxInflightTasks,
                            parallelism, maxInflightTasks);
                } else {
                    LOG.info("Created lake tablet stat collector executor. parallelism: {}, max in-flight: {}",
                            parallelism, maxInflightTasks);
                }
                lakeTabletStatExecutor = ThreadPoolManager.newDaemonFixedThreadPool(parallelism, maxInflightTasks,
                        LAKE_TABLET_STAT_COLLECTOR_POOL_NAME, false);
                lakeTabletStatExecutorParallelism = parallelism;
                lakeTabletStatExecutorMaxInflightTasks = maxInflightTasks;
                return lakeTabletStatExecutor;
            }

            if (lakeTabletStatExecutorParallelism != parallelism) {
                ThreadPoolManager.setFixedThreadPoolSize(lakeTabletStatExecutor, parallelism);
                LOG.info("Resized lake tablet stat collector executor. old parallelism: {}, new parallelism: {}, " +
                                "max in-flight: {}",
                        lakeTabletStatExecutorParallelism, parallelism, maxInflightTasks);
                lakeTabletStatExecutorParallelism = parallelism;
            }
            return lakeTabletStatExecutor;
        }
    }

    private void submitLakeTableTabletStatJobs(@NotNull LakeTabletStatCollector collector,
                                               @NotNull Database db,
                                               @NotNull OlapTable table) {
        if (collector.isInterrupted()) {
            return;
        }
        Collection<PhysicalPartition> partitions = getPartitions(db, table);
        for (PhysicalPartition partition : partitions) {
            if (collector.isInterrupted()) {
                return;
            }
            CollectTabletStatJob job = createCollectTabletStatJob(db, table, partition);
            collector.submit(job);
        }
    }

    private static int lakeTabletStatCollectParallelism() {
        return Math.max(1, Config.lake_tablet_stat_collect_parallelism);
    }

    private static int lakeTabletStatMaxInflightTasks(int parallelism) {
        return Math.max(parallelism, Config.lake_tablet_stat_max_inflight_tasks);
    }

    private static long lakeTabletStatSlowLogMs() {
        return Math.max(0, Config.lake_tablet_stat_collect_slow_log_ms);
    }

    private static long lakeTabletStatProgressLogIntervalMs() {
        return Config.lake_tablet_stat_progress_log_interval_ms;
    }

    private static long lakeTabletStatCancelWaitMs() {
        return Math.max(0, Config.lake_tablet_stat_cancel_wait_ms);
    }

    private static class PartitionSnapshot {
        private final String dbName;
        private final String tableName;
        private final long partitionId;
        private final long visibleVersion;
        private final long visibleVersionTime;
        private final List<Tablet> tablets;

        PartitionSnapshot(String dbName, String tableName, long partitionId, long visibleVersion,
                          long visibleVersionTime, List<Tablet> tablets) {
            this.dbName = dbName;
            this.tableName = tableName;
            this.partitionId = partitionId;
            this.visibleVersion = visibleVersion;
            this.visibleVersionTime = visibleVersionTime;
            this.tablets = Objects.requireNonNull(tablets);
        }

        private String debugName() {
            return String.format("%s.%s.%d version %d", dbName, tableName, partitionId, visibleVersion);
        }
    }

    private class LakeTabletStatCollector implements AutoCloseable {
        private final String name;
        private final int parallelism;
        private final int maxInflightTasks;
        private final CompletionService<CollectTabletStatJobResult> completionService;
        private final Set<Future<CollectTabletStatJobResult>> inFlightFutures = ConcurrentHashMap.newKeySet();
        private final Map<Future<CollectTabletStatJobResult>, String> inFlightSerialKeys = new ConcurrentHashMap<>();
        private final Set<String> activeSerialKeys = ConcurrentHashMap.newKeySet();
        private final Thread ownerThread;
        private int inFlightJobs = 0;
        private int maxObservedInFlightJobs = 0;
        private long submittedJobs = 0;
        private long skippedJobs = 0;
        private long completedJobs = 0;
        private long failedJobs = 0;
        private long requestedTablets = 0;
        private long updatedTablets = 0;
        private long slowJobs = 0;
        private long startTimeMs = System.currentTimeMillis();
        private long nextProgressLogTimeMs = -1;
        private String lastSubmittedPartition = "";
        private String lastCompletedPartition = "";
        private boolean interrupted = false;
        private volatile boolean stopRequested = false;
        private boolean closed = false;

        LakeTabletStatCollector(String name, int parallelism, int maxInflightTasks, ThreadPoolExecutor executor) {
            this.name = name;
            this.parallelism = parallelism;
            this.maxInflightTasks = maxInflightTasks;
            this.completionService = new ExecutorCompletionService<>(executor);
            this.ownerThread = Thread.currentThread();
        }

        boolean isInterrupted() {
            return interrupted || stopRequested;
        }

        void requestStop() {
            stopRequested = true;
            cancelInFlightJobs();
            ownerThread.interrupt();
        }

        void submit(@Nullable LakeTabletStatJob job) {
            if (job == null) {
                skippedJobs++;
                return;
            }
            if (isInterrupted()) {
                skippedJobs++;
                return;
            }

            String serialKey = job.serialKey();
            if (!serialKey.isEmpty()) {
                waitUntilSerialKeyIdle(serialKey);
                if (isInterrupted()) {
                    skippedJobs++;
                    return;
                }
            }

            Future<CollectTabletStatJobResult> future = completionService.submit(job::execute);
            inFlightFutures.add(future);
            if (!serialKey.isEmpty()) {
                inFlightSerialKeys.put(future, serialKey);
                activeSerialKeys.add(serialKey);
            }
            submittedJobs++;
            requestedTablets += job.getTabletCount();
            lastSubmittedPartition = job.debugName();
            inFlightJobs++;
            maxObservedInFlightJobs = Math.max(maxObservedInFlightJobs, inFlightJobs);
            if (stopRequested) {
                future.cancel(true);
            }
            if (inFlightJobs >= maxInflightTasks) {
                waitOne();
            }
        }

        private void waitUntilSerialKeyIdle(String serialKey) {
            while (activeSerialKeys.contains(serialKey) && !isInterrupted()) {
                waitOne();
            }
        }

        void waitAll() {
            while (inFlightJobs > 0 && !isInterrupted()) {
                waitOne();
            }
        }

        private void waitOne() {
            Future<CollectTabletStatJobResult> future = null;
            try {
                future = waitForOneCompletedJob();
                CollectTabletStatJobResult result = future.get();
                completedJobs++;
                lastCompletedPartition = result.partitionName;
                updatedTablets += result.updatedTabletCount;
                if (result.failed()) {
                    failedJobs++;
                }
                if (result.slow()) {
                    slowJobs++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted = true;
                throw new RuntimeException("Interrupted while collecting lake tablet stat for " + name, e);
            } catch (ExecutionException e) {
                failedJobs++;
                throw toRuntimeException(e);
            } finally {
                if (future != null && inFlightFutures.remove(future)) {
                    clearSerialKey(future);
                    inFlightJobs--;
                }
            }
        }

        private void clearSerialKey(Future<CollectTabletStatJobResult> future) {
            String serialKey = inFlightSerialKeys.remove(future);
            if (serialKey != null) {
                activeSerialKeys.remove(serialKey);
            }
        }

        private Future<CollectTabletStatJobResult> waitForOneCompletedJob() throws InterruptedException {
            while (true) {
                long waitMs = getProgressLogWaitMs();
                if (waitMs < 0) {
                    return completionService.take();
                }
                Future<CollectTabletStatJobResult> future = completionService.poll(waitMs, TimeUnit.MILLISECONDS);
                if (future != null) {
                    return future;
                }
                logProgressIfDue();
            }
        }

        private long getProgressLogWaitMs() {
            long intervalMs = lakeTabletStatProgressLogIntervalMs();
            if (intervalMs <= 0) {
                nextProgressLogTimeMs = -1;
                return -1;
            }
            long nowMs = System.currentTimeMillis();
            if (nextProgressLogTimeMs < 0) {
                nextProgressLogTimeMs = nowMs + intervalMs;
            }
            return Math.max(1, nextProgressLogTimeMs - nowMs);
        }

        private void logProgressIfDue() {
            long intervalMs = lakeTabletStatProgressLogIntervalMs();
            if (intervalMs <= 0) {
                nextProgressLogTimeMs = -1;
                return;
            }
            long nowMs = System.currentTimeMillis();
            if (nextProgressLogTimeMs < 0) {
                nextProgressLogTimeMs = nowMs + intervalMs;
                return;
            }
            if (nowMs < nextProgressLogTimeMs) {
                return;
            }
            logProgress(nowMs);
            do {
                nextProgressLogTimeMs += intervalMs;
            } while (nextProgressLogTimeMs <= nowMs);
        }

        private void logProgress(long nowMs) {
            long finishedPartitions = completedJobs + skippedJobs;
            long seenPartitions = submittedJobs + skippedJobs;
            double progress = seenPartitions == 0 ? 100.0 : finishedPartitions * 100.0 / seenPartitions;
            long elapsedMs = Math.max(0, nowMs - startTimeMs);
            double rate = elapsedMs == 0 ? 0.0 : finishedPartitions * 1000.0 / elapsedMs;
            LOG.info("lake tablet stat collection progress for {}: progress: {}% ({}/{} seen partitions), " +
                            "in-flight: {}, submitted: {}, completed: {}, failed: {}, skipped: {}, " +
                            "requested tablets: {}, updated tablets: {}, " +
                            "elapsed: {} ms, rate: {} partitions/s, max in-flight: {}, parallelism: {}, " +
                            "max in-flight config: {}, last submitted partition: {}, last completed partition: {}",
                    name, formatDouble(progress), finishedPartitions, seenPartitions, inFlightJobs, submittedJobs,
                    completedJobs, failedJobs, skippedJobs, requestedTablets, updatedTablets, elapsedMs,
                    formatDouble(rate), maxObservedInFlightJobs, parallelism, maxInflightTasks,
                    lastSubmittedPartition, lastCompletedPartition);
        }

        private String formatDouble(double value) {
            return String.format(Locale.ROOT, "%.2f", value);
        }

        private RuntimeException toRuntimeException(ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            if (cause instanceof RuntimeException) {
                return (RuntimeException) cause;
            }
            return new RuntimeException(cause == null ? exception : cause);
        }

        void logSummary(long costMs) {
            LOG.info("finished to collect lake tablet stat for {} in parallel. submitted partitions: {}, " +
                            "completed partitions: {}, failed partitions: {}, skipped partitions: {}, " +
                            "requested tablets: {}, updated tablets: {}, max in-flight partitions: {}, " +
                            "parallelism: {}, max in-flight config: {}, slow partitions: {}, cost: {} ms",
                    name, submittedJobs, completedJobs, failedJobs, skippedJobs, requestedTablets, updatedTablets,
                    maxObservedInFlightJobs, parallelism, maxInflightTasks, slowJobs, costMs);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            try {
                if (interrupted || stopRequested || inFlightJobs > 0) {
                    cancelAndDrainInFlightJobs();
                }
            } finally {
                closed = true;
                finishLakeTabletStatCollector(this, !inFlightFutures.isEmpty());
            }
        }

        private void cancelAndDrainInFlightJobs() {
            int canceledJobs = cancelInFlightJobs();
            if (canceledJobs > 0) {
                LOG.warn("Canceled {} unfinished lake tablet stat collection jobs for {}", canceledJobs, name);
            }
            int unfinishedJobs = drainCanceledJobs();
            if (unfinishedJobs > 0) {
                LOG.warn("Lake tablet stat collector for {} still has {} unfinished jobs after cancellation",
                        name, unfinishedJobs);
            }
        }

        private int cancelInFlightJobs() {
            int canceledJobs = 0;
            for (Future<CollectTabletStatJobResult> future : inFlightFutures) {
                if (!future.isDone() && future.cancel(true)) {
                    canceledJobs++;
                }
            }
            return canceledJobs;
        }

        private int drainCanceledJobs() {
            int remainingJobs = inFlightFutures.size();
            boolean wasInterrupted = Thread.interrupted();
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lakeTabletStatCancelWaitMs());
            try {
                while (remainingJobs > 0) {
                    long waitNanos = deadlineNanos - System.nanoTime();
                    if (waitNanos <= 0) {
                        break;
                    }
                    Future<CollectTabletStatJobResult> future =
                            completionService.poll(waitNanos, TimeUnit.NANOSECONDS);
                    if (future == null) {
                        break;
                    }
                    if (inFlightFutures.remove(future)) {
                        clearSerialKey(future);
                        inFlightJobs--;
                        remainingJobs--;
                    }
                }
            } catch (InterruptedException e) {
                wasInterrupted = true;
                LOG.warn("Interrupted while draining canceled lake tablet stat collection jobs for {}", name);
            } finally {
                if (wasInterrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return remainingJobs;
        }
    }

    private interface LakeTabletStatJob {
        CollectTabletStatJobResult execute();

        int getTabletCount();

        String debugName();

        default String serialKey() {
            return "";
        }
    }

    private static class CollectTabletStatJobResult {
        private final String partitionName;
        private final int updatedTabletCount;
        private final int failedResponseCount;
        private final long costMs;

        CollectTabletStatJobResult(String partitionName, int updatedTabletCount, int failedResponseCount, long costMs) {
            this.partitionName = partitionName;
            this.updatedTabletCount = updatedTabletCount;
            this.failedResponseCount = failedResponseCount;
            this.costMs = costMs;
        }

        private boolean failed() {
            return failedResponseCount > 0;
        }

        private boolean slow() {
            long slowLogMs = lakeTabletStatSlowLogMs();
            return slowLogMs > 0 && costMs >= slowLogMs;
        }
    }

    private static class CollectTabletStatWaitResult {
        private final int updatedTabletCount;
        private final int failedResponseCount;

        CollectTabletStatWaitResult(int updatedTabletCount, int failedResponseCount) {
            this.updatedTabletCount = updatedTabletCount;
            this.failedResponseCount = failedResponseCount;
        }
    }

    private static class CollectTabletStatJob implements LakeTabletStatJob {
        private final String dbName;
        private final String tableName;
        private final long partitionId;
        private final long version;
        private final Map<Long, Tablet> tablets;
        private long collectStatTime = 0;
        private List<Future<TabletStatResponse>> responseList;
        private final ComputeResource computeResource;

        CollectTabletStatJob(PartitionSnapshot snapshot, ComputeResource computeResource) {
            this.dbName = Objects.requireNonNull(snapshot.dbName, "dbName is null");
            this.tableName = Objects.requireNonNull(snapshot.tableName, "tableName is null");
            this.partitionId = snapshot.partitionId;
            this.version = snapshot.visibleVersion;
            this.tablets = new HashMap<>();
            for (Tablet tablet : snapshot.tablets) {
                this.tablets.put(tablet.getId(), tablet);
            }
            this.computeResource = computeResource;
        }

        @Override
        public CollectTabletStatJobResult execute() {
            long start = System.currentTimeMillis();
            int requestCount = sendTasks();
            CollectTabletStatWaitResult waitResult = waitResponse();
            long costMs = System.currentTimeMillis() - start;
            if (Config.enable_parallel_lake_tablet_stat_collection
                    && lakeTabletStatSlowLogMs() > 0
                    && costMs >= lakeTabletStatSlowLogMs()) {
                LOG.info("slow lake tablet stat collection. partition: {}, version: {}, tablets: {}, requests: {}, " +
                                "updated tablets: {}, failed responses: {}, cost: {} ms",
                        debugName(), version, tablets.size(), requestCount, waitResult.updatedTabletCount,
                        waitResult.failedResponseCount, costMs);
            }
            return new CollectTabletStatJobResult(debugName(), waitResult.updatedTabletCount,
                    waitResult.failedResponseCount, costMs);
        }

        @Override
        public String debugName() {
            return String.format("%s.%s.%d", dbName, tableName, partitionId);
        }

        @Override
        public int getTabletCount() {
            return tablets.size();
        }

        private int sendTasks() {
            final WarehouseManager warehouseManager = GlobalStateMgr.getCurrentState().getWarehouseMgr();
            Map<ComputeNode, List<TabletInfo>> beToTabletInfos = new HashMap<>();
            for (Tablet tablet : tablets.values()) {
                ComputeNode node;
                try {
                    node = warehouseManager.getComputeNodeAssignedToTablet(computeResource, tablet.getId());
                    if (node == null) {
                        LOG.warn("Skip sending tablet stat task for partition {} because no alive node", debugName());
                        continue;
                    }
                } catch (ErrorReportException e) {
                    LOG.warn("Skip sending tablet stat task for partition {} because exception: {}",
                            debugName(), e.getMessage());
                    continue;
                }
                TabletInfo tabletInfo = new TabletInfo();
                tabletInfo.tabletId = tablet.getId();
                tabletInfo.version = version;
                beToTabletInfos.computeIfAbsent(node, k -> Lists.newArrayList()).add(tabletInfo);
            }

            collectStatTime = System.currentTimeMillis();
            responseList = Lists.newArrayListWithCapacity(beToTabletInfos.size());
            int requestCount = 0;
            for (Map.Entry<ComputeNode, List<TabletInfo>> entry : beToTabletInfos.entrySet()) {
                ComputeNode node = entry.getKey();
                TabletStatRequest request = new TabletStatRequest();
                request.tabletInfos = entry.getValue();
                request.timeoutMs = LakeService.TIMEOUT_GET_TABLET_STATS;
                try {
                    LakeService lakeService = BrpcProxy.getLakeService(node.getHost(), node.getBrpcPort());
                    Future<TabletStatResponse> responseFuture = lakeService.getTabletStats(request);
                    responseList.add(responseFuture);
                    requestCount++;
                    LOG.debug(
                            "Sent tablet stat collection task to node {} for partition {} of version {}. tablet " +
                                    "count={}",
                            node.getHost(), debugName(), version, entry.getValue().size());
                } catch (Throwable e) {
                    LOG.warn("Fail to send tablet stat task to host {} for partition {}: {}", node.getHost(),
                            debugName(),
                            e.getMessage());
                }
            }
            return requestCount;
        }

        private CollectTabletStatWaitResult waitResponse() {
            // responseList may be null if there aren't any alive node.
            if (responseList == null) {
                return new CollectTabletStatWaitResult(0, 0);
            }
            int updatedTabletCount = 0;
            int failedResponseCount = 0;
            for (Future<TabletStatResponse> responseFuture : responseList) {
                try {
                    TabletStatResponse response = responseFuture.get();
                    if (response != null && response.tabletStats != null) {
                        for (TabletStat stat : response.tabletStats) {
                            LakeTablet tablet = (LakeTablet) tablets.get(stat.tabletId);
                            if (tablet == null) {
                                continue;
                            }
                            tablet.setDataSize(stat.dataSize);
                            tablet.setRowCount(stat.numRows);
                            if (stat.version != null && stat.version == version && stat.countFastPathSafe != null) {
                                tablet.setCountFastPathSafety(stat.countFastPathSafe, collectStatTime);
                            }
                            tablet.setDataSizeUpdateTime(collectStatTime);
                            updatedTabletCount++;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failedResponseCount++;
                } catch (ExecutionException e) {
                    failedResponseCount++;
                    LOG.warn("Fail to collect tablet stat for partition {}: {}", debugName(), e.getMessage());
                }
            }
            return new CollectTabletStatWaitResult(updatedTabletCount, failedResponseCount);
        }
    }

    // A stale tablet target to refresh, carrying its owning LakeTablet reference and the visible
    // version captured at scan time. Used by the per-CN batch collection path.
    private static class TabletStatEntry {
        private final long tabletId;
        private final LakeTablet tablet;
        private final long version;

        TabletStatEntry(long tabletId, LakeTablet tablet, long version) {
            this.tabletId = tabletId;
            this.tablet = tablet;
            this.version = version;
        }
    }

    private static class CnBatchAccumulator {
        private final LakeTabletStatCollector collector;
        private final int batchSize;
        private final Map<Long, ComputeNode> nodeById = new LinkedHashMap<>();
        private final Map<Long, List<TabletStatEntry>> pendingByNode = new LinkedHashMap<>();

        CnBatchAccumulator(LakeTabletStatCollector collector) {
            this.collector = Objects.requireNonNull(collector, "collector is null");
            this.batchSize = Math.max(1, Config.lake_tablet_stat_batch_size);
        }

        boolean isInterrupted() {
            return collector.isInterrupted();
        }

        void addPartitionGroup(ComputeNode node, List<TabletStatEntry> entries) {
            if (entries.isEmpty() || collector.isInterrupted()) {
                return;
            }
            long nodeId = node.getId();
            nodeById.putIfAbsent(nodeId, node);

            if (entries.size() > batchSize) {
                flushNode(nodeId);
                for (int offset = 0; offset < entries.size() && !collector.isInterrupted(); offset += batchSize) {
                    int end = Math.min(offset + batchSize, entries.size());
                    submitBatch(node, entries.subList(offset, end));
                }
                return;
            }

            List<TabletStatEntry> pending = pendingByNode.computeIfAbsent(nodeId, ignored -> new ArrayList<>());
            if (!pending.isEmpty() && pending.size() + entries.size() > batchSize) {
                flushNode(nodeId);
                pending = pendingByNode.computeIfAbsent(nodeId, ignored -> new ArrayList<>());
            }
            pending.addAll(entries);
            if (pending.size() >= batchSize) {
                flushNode(nodeId);
            }
        }

        void flushAll() {
            List<Long> nodeIds = new ArrayList<>(pendingByNode.keySet());
            for (long nodeId : nodeIds) {
                if (collector.isInterrupted()) {
                    return;
                }
                flushNode(nodeId);
            }
        }

        private void flushNode(long nodeId) {
            List<TabletStatEntry> pending = pendingByNode.remove(nodeId);
            if (pending == null || pending.isEmpty() || collector.isInterrupted()) {
                return;
            }
            submitBatch(nodeById.get(nodeId), pending);
        }

        private void submitBatch(ComputeNode node, List<TabletStatEntry> entries) {
            collector.submit(new CnBatchTabletStatJob(node, new ArrayList<>(entries)));
        }
    }

    // Collects stats for a batch of tablets that all belong to one compute node in a single
    // get_tablet_stats RPC. Batches are built to keep tablets of the same partition contiguous, so
    // the CN can reuse a bundle metadata read across them within the request.
    private static class CnBatchTabletStatJob implements LakeTabletStatJob {
        private final ComputeNode node;
        private final List<TabletStatEntry> entries;

        CnBatchTabletStatJob(ComputeNode node, List<TabletStatEntry> entries) {
            this.node = Objects.requireNonNull(node, "node is null");
            this.entries = Objects.requireNonNull(entries, "entries is null");
        }

        @Override
        public CollectTabletStatJobResult execute() {
            long start = System.currentTimeMillis();
            Map<Long, TabletStatEntry> entryByTabletId = new HashMap<>();
            TabletStatRequest request = new TabletStatRequest();
            List<TabletInfo> tabletInfos = Lists.newArrayListWithCapacity(entries.size());
            for (TabletStatEntry entry : entries) {
                TabletInfo tabletInfo = new TabletInfo();
                tabletInfo.tabletId = entry.tabletId;
                tabletInfo.version = entry.version;
                tabletInfos.add(tabletInfo);
                entryByTabletId.put(entry.tabletId, entry);
            }
            request.tabletInfos = tabletInfos;
            request.timeoutMs = LakeService.TIMEOUT_GET_TABLET_STATS;

            // Record send time before the RPC. If a partition gets a newer visible version during
            // the RPC, its visibleVersionTime will exceed this time, so the tablet is refreshed
            // again next round (same semantics as the per-partition path).
            long collectStatTime = System.currentTimeMillis();
            int updatedTabletCount = 0;
            int failedResponseCount = 0;
            try {
                LakeService lakeService = BrpcProxy.getLakeService(node.getHost(), node.getBrpcPort());
                Future<TabletStatResponse> responseFuture = lakeService.getTabletStats(request);
                TabletStatResponse response = responseFuture.get();
                if (response != null && response.tabletStats != null) {
                    for (TabletStat stat : response.tabletStats) {
                        TabletStatEntry entry = entryByTabletId.get(stat.tabletId);
                        if (entry != null) {
                            entry.tablet.setDataSize(stat.dataSize);
                            entry.tablet.setRowCount(stat.numRows);
                            if (stat.version != null && stat.version == entry.version && stat.countFastPathSafe != null) {
                                entry.tablet.setCountFastPathSafety(stat.countFastPathSafe, collectStatTime);
                            }
                            entry.tablet.setDataSizeUpdateTime(collectStatTime);
                            updatedTabletCount++;
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failedResponseCount++;
            } catch (Exception e) {
                // A per-CN RPC failure (unreachable node, timeout, remote metadata read failure) is
                // expected and must not abort the whole round; count it and let other batches proceed.
                failedResponseCount++;
                LOG.warn("Fail to collect tablet stat batch on node {}: {}", node.getHost(), e.getMessage());
            }

            long costMs = System.currentTimeMillis() - start;
            if (lakeTabletStatSlowLogMs() > 0 && costMs >= lakeTabletStatSlowLogMs()) {
                LOG.info("slow lake tablet stat batch. node: {}, tablets: {}, updated: {}, failed: {}, cost: {} ms",
                        node.getHost(), entries.size(), updatedTabletCount, failedResponseCount, costMs);
            }
            return new CollectTabletStatJobResult(debugName(), updatedTabletCount, failedResponseCount, costMs);
        }

        @Override
        public int getTabletCount() {
            return entries.size();
        }

        @Override
        public String debugName() {
            return String.format("cn-%d batch(%d)", node.getId(), entries.size());
        }

        @Override
        public String serialKey() {
            return "cn-" + node.getId();
        }
    }
}
