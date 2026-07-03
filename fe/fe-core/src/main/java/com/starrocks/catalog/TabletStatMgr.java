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
import com.starrocks.sql.ast.MergeTabletClause;
import com.starrocks.sql.ast.SplitTabletClause;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;

/*
 * TabletStatMgr is for collecting tablet(replica) statistics from backends.
 * Each FE will collect by itself.
 */
public class TabletStatMgr extends FrontendDaemon {
    private static final Logger LOG = LogManager.getLogger(TabletStatMgr.class);

    private LocalDateTime lastWorkTimestamp = LocalDateTime.MIN;

    public TabletStatMgr() {
        super("tablet-stat-mgr", Config.tablet_stat_update_interval_second * 1000L);
    }

    public LocalDateTime getLastWorkTimestamp() {
        return lastWorkTimestamp;
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
                // NOTE: calculate the row first with read lock, then update the stats with write lock
                OlapTable olapTable = (OlapTable) table;
                locker.lockTableWithIntensiveDbLock(db.getId(), table.getId(), LockType.READ);
                try {
                    for (Partition partition : olapTable.getAllPartitions()) {
                        for (PhysicalPartition physicalPartition : partition.getSubPartitions()) {
                            long version = physicalPartition.getVisibleVersion();
                            long visibleVersionTime = physicalPartition.getVisibleVersionTime();
                            for (MaterializedIndex index : physicalPartition.getLatestMaterializedIndices(
                                    IndexExtState.VISIBLE)) {
                                long indexRowCount = 0L;
                                long prevFreshTabletSize = -1L;
                                // NOTE: can take a rather long time to iterate lots of tablets
                                for (Tablet tablet : index.getTablets()) {
                                    indexRowCount += tablet.getRowCount(version);
                                    long dataSize = tablet.getDataSize(true);
                                    maxTabletSize = Math.max(maxTabletSize, dataSize);
                                    if (!(tablet instanceof LakeTablet)
                                            || ((LakeTablet) tablet).getDataSizeUpdateTime() < visibleVersionTime) {
                                        prevFreshTabletSize = -1L;
                                        continue;
                                    }
                                    if (prevFreshTabletSize >= 0) {
                                        minAdjacentTabletPairSize = Math.min(minAdjacentTabletPairSize,
                                                prevFreshTabletSize + dataSize);
                                    }
                                    prevFreshTabletSize = dataSize;
                                } // end for tablets
                                indexRowCountMap.put(Pair.create(physicalPartition.getId(), index.getId()),
                                        indexRowCount);
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
                            }
                        }
                    }
                    adjustStatUpdateRows(table.getId(), totalRowCount);
                } finally {
                    locker.unLockTableWithIntensiveDbLock(db.getId(), table.getId(), LockType.WRITE);
                }

                // Trigger tablet reshard
                if (GlobalStateMgr.getCurrentState().isLeader()) {
                    triggerTabletReshard(db, olapTable, maxTabletSize, minAdjacentTabletPairSize);
                }
            }
        }
        LOG.info("finished to update index row num of all databases. cost: {} ms",
                (System.currentTimeMillis() - start));
        lastWorkTimestamp = LocalDateTime.now();
    }

    private static void triggerTabletReshard(Database db, OlapTable table,
                                             long maxTabletSize, long minAdjacentTabletPairSize) {
        if (!table.isCloudNativeTableOrMaterializedView() || !table.isRangeDistribution()) {
            return;
        }

        try {
            if (TabletReshardUtils.needSplit(maxTabletSize)) {
                GlobalStateMgr.getCurrentState().getTabletReshardJobMgr().createTabletReshardJob(
                        db, table, new SplitTabletClause());
                LOG.info("Auto triggered split tablet job for table {}.{}, maxTabletSize {}",
                        db.getFullName(), table.getName(), maxTabletSize);
                return;
            }

            if (TabletReshardUtils.needMerge(minAdjacentTabletPairSize)) {
                GlobalStateMgr.getCurrentState().getTabletReshardJobMgr().createTabletReshardJob(
                        db, table, new MergeTabletClause());
                LOG.info("Auto triggered merge tablet job for table {}.{}, minAdjacentTabletPairSize {}",
                        db.getFullName(), table.getName(), minAdjacentTabletPairSize);
            }
        } catch (Exception e) {
            LOG.warn("Failed to create tablet reshard job for table {}.{}.",
                    db.getFullName(), table.getName(), e);
        }
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
        LakeTabletStatCollector collector = new LakeTabletStatCollector("all databases");
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
        long visibleVersionTime = snapshot.visibleVersionTime;
        snapshot.tablets.removeIf(t -> ((LakeTablet) t).getDataSizeUpdateTime() >= visibleVersionTime);
        if (snapshot.tablets.isEmpty()) {
            LOG.debug("Skipped tablet stat collection of partition {}", snapshot.debugName());
            return null;
        }
        return new CollectTabletStatJob(snapshot, computeResource);
    }

    private void updateLakeTableTabletStat(@NotNull Database db, @NotNull OlapTable table) {
        if (Config.enable_parallel_lake_tablet_stat_collection) {
            long start = System.currentTimeMillis();
            LakeTabletStatCollector collector = new LakeTabletStatCollector(db.getFullName() + "." + table.getName());
            try {
                submitLakeTableTabletStatJobs(collector, db, table);
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

    private void submitLakeTableTabletStatJobs(@NotNull LakeTabletStatCollector collector,
                                               @NotNull Database db,
                                               @NotNull OlapTable table) {
        Collection<PhysicalPartition> partitions = getPartitions(db, table);
        for (PhysicalPartition partition : partitions) {
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

    private static class LakeTabletStatCollector implements AutoCloseable {
        private final String name;
        private final int parallelism;
        private final int maxInflightTasks;
        private final ThreadPoolExecutor executor;
        private final CompletionService<CollectTabletStatJobResult> completionService;
        private int inFlightJobs = 0;
        private int maxObservedInFlightJobs = 0;
        private long submittedJobs = 0;
        private long skippedJobs = 0;
        private long completedJobs = 0;
        private long failedJobs = 0;
        private long requestedTablets = 0;
        private long updatedTablets = 0;
        private long slowJobs = 0;
        private boolean interrupted = false;

        LakeTabletStatCollector(String name) {
            this.name = name;
            this.parallelism = lakeTabletStatCollectParallelism();
            this.maxInflightTasks = lakeTabletStatMaxInflightTasks(parallelism);
            this.executor = ThreadPoolManager.newDaemonFixedThreadPool(parallelism, maxInflightTasks,
                    "lake-tablet-stat-collector", false);
            this.completionService = new ExecutorCompletionService<>(executor);
        }

        void submit(@Nullable CollectTabletStatJob job) {
            if (job == null) {
                skippedJobs++;
                return;
            }
            if (interrupted) {
                skippedJobs++;
                return;
            }

            completionService.submit(job::execute);
            submittedJobs++;
            requestedTablets += job.getTabletCount();
            inFlightJobs++;
            maxObservedInFlightJobs = Math.max(maxObservedInFlightJobs, inFlightJobs);
            if (inFlightJobs >= maxInflightTasks) {
                waitOne();
            }
        }

        void waitAll() {
            while (inFlightJobs > 0 && !interrupted) {
                waitOne();
            }
        }

        private void waitOne() {
            try {
                CollectTabletStatJobResult result = completionService.take().get();
                completedJobs++;
                updatedTablets += result.updatedTabletCount;
                if (result.failed()) {
                    failedJobs++;
                }
                if (result.slow()) {
                    slowJobs++;
                }
                inFlightJobs--;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted = true;
                LOG.warn("Interrupted while collecting lake tablet stat for {}", name);
            } catch (ExecutionException e) {
                failedJobs++;
                inFlightJobs--;
                LOG.warn("Failed to collect lake tablet stat for {}: {}", name, e.getMessage());
            }
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
            if (interrupted) {
                executor.shutdownNow();
            } else {
                executor.shutdown();
            }
        }
    }

    private static class CollectTabletStatJobResult {
        private final int updatedTabletCount;
        private final int failedResponseCount;
        private final long costMs;

        CollectTabletStatJobResult(int updatedTabletCount, int failedResponseCount, long costMs) {
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

    private static class CollectTabletStatJob {
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

        CollectTabletStatJobResult execute() {
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
            return new CollectTabletStatJobResult(waitResult.updatedTabletCount,
                    waitResult.failedResponseCount, costMs);
        }

        private String debugName() {
            return String.format("%s.%s.%d", dbName, tableName, partitionId);
        }

        private int getTabletCount() {
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
                            tablet.setDataSize(stat.dataSize);
                            tablet.setRowCount(stat.numRows);
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
}
