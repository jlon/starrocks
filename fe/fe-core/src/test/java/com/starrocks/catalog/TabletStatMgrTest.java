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

package com.starrocks.catalog;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.common.Config;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReportException;
import com.starrocks.common.jmockit.Deencapsulation;
import com.starrocks.lake.LakeTable;
import com.starrocks.lake.LakeTablet;
import com.starrocks.lake.Utils;
import com.starrocks.proto.TabletStatRequest;
import com.starrocks.proto.TabletStatResponse;
import com.starrocks.proto.TabletStatResponse.TabletStat;
import com.starrocks.rpc.BrpcProxy;
import com.starrocks.rpc.LakeService;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.server.WarehouseManager;
import com.starrocks.sql.ast.AggregateType;
import com.starrocks.sql.ast.KeysType;
import com.starrocks.system.ComputeNode;
import com.starrocks.system.SystemInfoService;
import com.starrocks.thrift.TNetworkAddress;
import com.starrocks.thrift.TStorageMedium;
import com.starrocks.thrift.TStorageType;
import com.starrocks.thrift.TTabletStat;
import com.starrocks.thrift.TTabletStatResult;
import com.starrocks.type.IntegerType;
import com.starrocks.utframe.UtFrameUtils;
import com.starrocks.warehouse.cngroup.ComputeResource;
import mockit.Delegate;
import mockit.Expectations;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

public class TabletStatMgrTest {
    private static final long DB_ID = 1;
    private static final long TABLE_ID = 2;
    private static final long PARTITION_ID = 3;
    private static final long INDEX_ID = 4;
    private static final long PH_PARTITION_ID = 5;
    private final List<TabletStatMgr> tabletStatMgrsToStop = Lists.newArrayList();

    @BeforeEach
    public void before() {
        UtFrameUtils.mockInitWarehouseEnv();
    }

    @AfterEach
    public void after() {
        for (TabletStatMgr tabletStatMgr : tabletStatMgrsToStop) {
            tabletStatMgr.setStop();
        }
        tabletStatMgrsToStop.clear();
    }

    private TabletStatMgr createTabletStatMgrForTest() {
        TabletStatMgr tabletStatMgr = new TabletStatMgr();
        tabletStatMgrsToStop.add(tabletStatMgr);
        return tabletStatMgr;
    }

    private static boolean waitUntil(BooleanSupplier condition, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static class CapturingLogAppender extends AbstractAppender {
        private final List<String> messages = new CopyOnWriteArrayList<>();

        private CapturingLogAppender(String name) {
            super(name, null, PatternLayout.createDefaultLayout(), false, null);
        }

        @Override
        public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }

        private boolean contains(String expected) {
            return messages.stream().anyMatch(message -> message.contains(expected));
        }

        private boolean containsAll(String... expectedParts) {
            return messages.stream().anyMatch(message -> {
                for (String expectedPart : expectedParts) {
                    if (!message.contains(expectedPart)) {
                        return false;
                    }
                }
                return true;
            });
        }
    }

    private static class TabletStatMgrLogCapture implements AutoCloseable {
        private final org.apache.logging.log4j.core.Logger logger;
        private final Level oldLevel;
        private final CapturingLogAppender appender;

        private TabletStatMgrLogCapture() {
            logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(TabletStatMgr.class);
            oldLevel = logger.getLevel();
            appender = new CapturingLogAppender("tablet-stat-mgr-test-appender");
            appender.start();
            logger.addAppender(appender);
            logger.setLevel(Level.INFO);
        }

        private boolean contains(String expected) {
            return appender.contains(expected);
        }

        private boolean containsAll(String... expectedParts) {
            return appender.containsAll(expectedParts);
        }

        @Override
        public void close() {
            logger.removeAppender(appender);
            logger.setLevel(oldLevel);
            appender.stop();
        }
    }

    @Test
    public void testUpdateLocalTabletStat(@Mocked GlobalStateMgr globalStateMgr, @Mocked Utils utils,
                                          @Mocked SystemInfoService systemInfoService) {
        long tablet2Id = 11L;
        long backendId = 20L;
        TabletInvertedIndex invertedIndex = new TabletInvertedIndex();

        // Columns
        List<Column> columns = new ArrayList<Column>();
        Column k1 = new Column("k1", IntegerType.INT, true, null, "", "");
        columns.add(k1);
        columns.add(new Column("k2", IntegerType.BIGINT, true, null, "", ""));
        columns.add(new Column("v", IntegerType.BIGINT, false, AggregateType.SUM, "0", ""));

        // Tablet2 is LocalTablet
        TabletMeta tabletMeta2 = new TabletMeta(DB_ID, TABLE_ID, PARTITION_ID, INDEX_ID, TStorageMedium.HDD);
        invertedIndex.addTablet(tablet2Id, tabletMeta2);
        Replica replica = new Replica(tablet2Id + 1, backendId, 0, Replica.ReplicaState.NORMAL);
        invertedIndex.addReplica(tablet2Id, replica);

        // Partition info and distribution info
        DistributionInfo distributionInfo = new HashDistributionInfo(10, Lists.newArrayList(k1));
        PartitionInfo partitionInfo = new SinglePartitionInfo();
        partitionInfo.setDataProperty(PARTITION_ID, new DataProperty(TStorageMedium.HDD));
        partitionInfo.setReplicationNum(PARTITION_ID, (short) 3);

        // Table
        MaterializedIndex index = new MaterializedIndex(INDEX_ID, MaterializedIndex.IndexState.NORMAL);
        Partition partition = new Partition(PARTITION_ID, PH_PARTITION_ID, "p1", index, distributionInfo);
        OlapTable table = new OlapTable(TABLE_ID, "t1", columns, KeysType.AGG_KEYS, partitionInfo, distributionInfo);
        Deencapsulation.setField(table, "baseIndexMetaId", INDEX_ID);
        table.addPartition(partition);
        table.setIndexMeta(INDEX_ID, "t1", columns, 0, 0, (short) 3, TStorageType.COLUMN, KeysType.AGG_KEYS);

        // Db
        Database db = new Database();
        db.registerTableUnlocked(table);

        TTabletStatResult result = new TTabletStatResult();
        Map<Long, TTabletStat> tabletsStats = Maps.newHashMap();
        result.setTablets_stats(tabletsStats);
        TTabletStat tablet2Stat = new TTabletStat(tablet2Id);
        tablet2Stat.setData_size(200L);
        tablet2Stat.setRow_num(201L);
        tabletsStats.put(tablet2Id, tablet2Stat);

        new Expectations() {{
                GlobalStateMgr.getCurrentState().getTabletInvertedIndex();
                result = invertedIndex;
            }};

        // Check
        TabletStatMgr tabletStatMgr = createTabletStatMgrForTest();
        Deencapsulation.invoke(tabletStatMgr, "updateLocalTabletStat", backendId, result);

        Assertions.assertEquals(200L, replica.getDataSize());
        Assertions.assertEquals(201L, replica.getRowCount());
    }

    private LakeTable createLakeTableForTest() {
        long tablet1Id = 10L;
        long tablet2Id = 11L;
        long tablet3Id = 12L;

        // Schema
        List<Column> columns = Lists.newArrayList();
        Column k1 = new Column("k1", IntegerType.INT, true, null, "", "");
        columns.add(k1);
        columns.add(new Column("k2", IntegerType.BIGINT, true, null, "", ""));
        columns.add(new Column("v", IntegerType.BIGINT, false, AggregateType.SUM, "0", ""));

        long visibleVersionTime = System.currentTimeMillis();

        // Tablet
        LakeTablet tablet1 = new LakeTablet(tablet1Id);
        LakeTablet tablet2 = new LakeTablet(tablet2Id);
        LakeTablet tablet3 = new LakeTablet(tablet3Id);
        tablet1.setDataSizeUpdateTime(0);
        tablet2.setDataSizeUpdateTime(0);
        tablet3.setDataSizeUpdateTime(visibleVersionTime);

        // Index
        MaterializedIndex index = new MaterializedIndex(INDEX_ID, MaterializedIndex.IndexState.NORMAL);
        TabletMeta tabletMeta = new TabletMeta(DB_ID, TABLE_ID, PARTITION_ID, INDEX_ID, TStorageMedium.HDD, true);
        index.addTablet(tablet1, tabletMeta);
        index.addTablet(tablet2, tabletMeta);

        // Partition
        DistributionInfo distributionInfo = new HashDistributionInfo(10, Lists.newArrayList(k1));
        PartitionInfo partitionInfo = new SinglePartitionInfo();
        partitionInfo.setReplicationNum(PARTITION_ID, (short) 3);
        Partition partition = new Partition(PARTITION_ID, PH_PARTITION_ID, "p1", index, distributionInfo);
        partition.getDefaultPhysicalPartition().setVisibleVersion(2L, visibleVersionTime);

        // Lake table
        LakeTable table = new LakeTable(TABLE_ID, "t1", columns, KeysType.AGG_KEYS, partitionInfo, distributionInfo);
        Deencapsulation.setField(table, "baseIndexMetaId", INDEX_ID);
        table.addPartition(partition);
        table.setIndexMeta(INDEX_ID, "t1", columns, 0, 0, (short) 3, TStorageType.COLUMN, KeysType.AGG_KEYS);

        return table;
    }

    private LakeTable createLakeTableWithPartitionsForTest(int partitionCount) {
        return createLakeTableWithPartitionsAndTabletsForTest(partitionCount, 1);
    }

    private LakeTable createLakeTableWithPartitionsAndTabletsForTest(int partitionCount, int tabletsPerPartition) {
        List<Column> columns = Lists.newArrayList();
        Column k1 = new Column("k1", IntegerType.INT, true, null, "", "");
        columns.add(k1);
        columns.add(new Column("k2", IntegerType.BIGINT, true, null, "", ""));
        columns.add(new Column("v", IntegerType.BIGINT, false, AggregateType.SUM, "0", ""));

        DistributionInfo distributionInfo = new HashDistributionInfo(10, Lists.newArrayList(k1));
        PartitionInfo partitionInfo = new SinglePartitionInfo();
        LakeTable table = new LakeTable(TABLE_ID, "multi_partition_table", columns, KeysType.AGG_KEYS,
                partitionInfo, distributionInfo);
        Deencapsulation.setField(table, "baseIndexMetaId", INDEX_ID);
        table.setIndexMeta(INDEX_ID, "multi_partition_table", columns, 0, 0, (short) 3, TStorageType.COLUMN,
                KeysType.AGG_KEYS);

        long visibleVersionTime = System.currentTimeMillis();
        for (int i = 0; i < partitionCount; i++) {
            long partitionId = PARTITION_ID + i;
            long physicalPartitionId = PH_PARTITION_ID + i;
            partitionInfo.setReplicationNum(partitionId, (short) 3);

            MaterializedIndex index = new MaterializedIndex(INDEX_ID, MaterializedIndex.IndexState.NORMAL);
            TabletMeta tabletMeta = new TabletMeta(DB_ID, TABLE_ID, partitionId, INDEX_ID, TStorageMedium.HDD, true);
            for (int j = 0; j < tabletsPerPartition; j++) {
                long tabletId = 10L + (long) i * tabletsPerPartition + j;
                LakeTablet tablet = new LakeTablet(tabletId);
                tablet.setDataSizeUpdateTime(0);
                index.addTablet(tablet, tabletMeta);
            }

            Partition partition = new Partition(partitionId, physicalPartitionId, "p" + i, index, distributionInfo);
            partition.getDefaultPhysicalPartition().setVisibleVersion(2L, visibleVersionTime);
            table.addPartition(partition);
        }
        return table;
    }

    private Map<Long, Long> collectLakeTabletRowCounts(LakeTable table) {
        Map<Long, Long> rowCounts = Maps.newHashMap();
        for (Partition partition : table.getAllPartitions()) {
            LakeTablet tablet = (LakeTablet) partition.getDefaultPhysicalPartition()
                    .getLatestBaseIndex().getTablets().get(0);
            rowCounts.put(tablet.getId(), tablet.getRowCount(-1));
        }
        return rowCounts;
    }

    private Map<Long, Long> collectLakeTabletDataSizes(LakeTable table) {
        Map<Long, Long> dataSizes = Maps.newHashMap();
        for (Partition partition : table.getAllPartitions()) {
            LakeTablet tablet = (LakeTablet) partition.getDefaultPhysicalPartition()
                    .getLatestBaseIndex().getTablets().get(0);
            dataSizes.put(tablet.getId(), tablet.getDataSize(true));
        }
        return dataSizes;
    }

    private void assertLakeTabletDataSizeUpdateTimeSet(LakeTable table) {
        for (Partition partition : table.getAllPartitions()) {
            LakeTablet tablet = (LakeTablet) partition.getDefaultPhysicalPartition()
                    .getLatestBaseIndex().getTablets().get(0);
            Assertions.assertTrue(tablet.getDataSizeUpdateTime() > 0);
        }
    }

    // Force the physical partitions owning the given tablets back to the initial version so they look like
    // empty just-created partitions that never had a load committed.
    private void markPartitionsInitialVersion(LakeTable table, long... initialTabletIds) {
        long now = System.currentTimeMillis();
        for (Partition partition : table.getAllPartitions()) {
            LakeTablet tablet = (LakeTablet) partition.getDefaultPhysicalPartition()
                    .getLatestBaseIndex().getTablets().get(0);
            for (long id : initialTabletIds) {
                if (tablet.getId() == id) {
                    partition.getDefaultPhysicalPartition()
                            .setVisibleVersion(PhysicalPartition.PARTITION_INIT_VERSION, now);
                    break;
                }
            }
        }
    }

    @Test
    public void testParallelLakeTabletStatProgressLogDisabledByDefault() {
        Assertions.assertEquals(-1, Config.lake_tablet_stat_progress_log_interval_ms);
    }

    @Test
    public void testParallelLakeTabletStatProgressLogReportsActiveCollection(@Mocked LakeService lakeService)
            throws Exception {
        boolean oldEnabled = Config.enable_parallel_lake_tablet_stat_collection;
        boolean oldCnBatch = Config.enable_lake_tablet_stat_cn_batch_collection;
        int oldParallelism = Config.lake_tablet_stat_collect_parallelism;
        int oldMaxInflight = Config.lake_tablet_stat_max_inflight_tasks;
        long oldProgressLogIntervalMs = Config.lake_tablet_stat_progress_log_interval_ms;
        CountDownLatch releaseResponse = new CountDownLatch(1);
        Thread updateThread = null;
        try (TabletStatMgrLogCapture logCapture = new TabletStatMgrLogCapture()) {
            Config.enable_parallel_lake_tablet_stat_collection = true;
            Config.enable_lake_tablet_stat_cn_batch_collection = false;
            Config.lake_tablet_stat_collect_parallelism = 1;
            Config.lake_tablet_stat_max_inflight_tasks = 1;
            Config.lake_tablet_stat_progress_log_interval_ms = 10;

            LakeTable table = createLakeTableWithPartitionsForTest(1);
            Database db = new Database(DB_ID, "db");
            db.registerTableUnlocked(table);

            new MockUp<BrpcProxy>() {
                @Mock
                public LakeService getLakeService(String host, int port) {
                    return lakeService;
                }
            };

            CountDownLatch requestStarted = new CountDownLatch(1);
            AtomicBoolean responseReleased = new AtomicBoolean(false);
            new Expectations() {
                {
                    lakeService.getTabletStats((TabletStatRequest) any);
                    minTimes = 1;
                    result = new Delegate() {
                        Future<TabletStatResponse> getTabletStats(TabletStatRequest request) {
                            requestStarted.countDown();
                            return new Future<TabletStatResponse>() {
                                @Override
                                public boolean cancel(boolean mayInterruptIfRunning) {
                                    return false;
                                }

                                @Override
                                public boolean isCancelled() {
                                    return false;
                                }

                                @Override
                                public boolean isDone() {
                                    return responseReleased.get();
                                }

                                @Override
                                public TabletStatResponse get() throws InterruptedException {
                                    Assertions.assertTrue(releaseResponse.await(10, TimeUnit.SECONDS));
                                    responseReleased.set(true);
                                    TabletStatResponse response = new TabletStatResponse();
                                    List<TabletStat> stats = Lists.newArrayList();
                                    for (TabletStatRequest.TabletInfo tabletInfo : request.tabletInfos) {
                                        TabletStat stat = new TabletStat();
                                        stat.tabletId = tabletInfo.tabletId;
                                        stat.numRows = 1L;
                                        stat.dataSize = 10L;
                                        stats.add(stat);
                                    }
                                    response.tabletStats = stats;
                                    return response;
                                }

                                @Override
                                public TabletStatResponse get(long timeout, @NotNull TimeUnit unit)
                                        throws InterruptedException {
                                    return get();
                                }
                            };
                        }
                    };
                }
            };

            TabletStatMgr tabletStatMgr = createTabletStatMgrForTest();
            AtomicReference<Throwable> updateFailure = new AtomicReference<>();
            updateThread = new Thread(() -> {
                try {
                    Deencapsulation.invoke(tabletStatMgr, "updateLakeTableTabletStat", db, table);
                } catch (Throwable t) {
                    updateFailure.set(t);
                }
            });
            updateThread.setDaemon(true);
            updateThread.start();

            Assertions.assertTrue(requestStarted.await(5, TimeUnit.SECONDS));
            Assertions.assertTrue(waitUntil(() -> logCapture.containsAll(
                    "lake tablet stat collection progress for db.multi_partition_table",
                    "progress: 0.00% (0/1 seen partitions)",
                    "in-flight: 1",
                    "submitted: 1",
                    "completed: 0",
                    "failed: 0",
                    "elapsed: "), 5, TimeUnit.SECONDS));

            releaseResponse.countDown();
            updateThread.join(5000);
            Assertions.assertFalse(updateThread.isAlive());
            Assertions.assertNull(updateFailure.get());
            Assertions.assertTrue(
                    logCapture.contains("finished to collect lake tablet stat for db.multi_partition_table in parallel"));
        } finally {
            releaseResponse.countDown();
            if (updateThread != null && updateThread.isAlive()) {
                updateThread.interrupt();
                updateThread.join(5000);
            }
            Config.enable_parallel_lake_tablet_stat_collection = oldEnabled;
            Config.enable_lake_tablet_stat_cn_batch_collection = oldCnBatch;
            Config.lake_tablet_stat_collect_parallelism = oldParallelism;
            Config.lake_tablet_stat_max_inflight_tasks = oldMaxInflight;
            Config.lake_tablet_stat_progress_log_interval_ms = oldProgressLogIntervalMs;
        }
    }

    @Test
    public void testParallelLakeTabletStatMatchesSerialResult(@Mocked LakeService lakeService) {
        boolean oldEnabled = Config.enable_parallel_lake_tablet_stat_collection;
        boolean oldCnBatch = Config.enable_lake_tablet_stat_cn_batch_collection;
        int oldParallelism = Config.lake_tablet_stat_collect_parallelism;
        int oldMaxInflight = Config.lake_tablet_stat_max_inflight_tasks;
        try {
            Config.enable_lake_tablet_stat_cn_batch_collection = false;
            LakeTable serialTable = createLakeTableWithPartitionsForTest(4);
            LakeTable parallelTable = createLakeTableWithPartitionsForTest(4);
            Database serialDb = new Database(DB_ID, "db");
            serialDb.registerTableUnlocked(serialTable);
            Database parallelDb = new Database(DB_ID, "db");
            parallelDb.registerTableUnlocked(parallelTable);

            new MockUp<BrpcProxy>() {
                @Mock
                public LakeService getLakeService(String host, int port) {
                    return lakeService;
                }
            };

            new Expectations() {
                {
                    lakeService.getTabletStats((TabletStatRequest) any);
                    minTimes = 8;
                    result = new Delegate() {
                        Future<TabletStatResponse> getTabletStats(TabletStatRequest request) {
                            TabletStatResponse response = new TabletStatResponse();
                            List<TabletStat> stats = Lists.newArrayList();
                            for (TabletStatRequest.TabletInfo tabletInfo : request.tabletInfos) {
                                TabletStat stat = new TabletStat();
                                stat.tabletId = tabletInfo.tabletId;
                                stat.numRows = tabletInfo.tabletId * 10;
                                stat.dataSize = tabletInfo.tabletId * 100;
                                stats.add(stat);
                            }
                            response.tabletStats = stats;
                            return CompletableFuture.completedFuture(response);
                        }
                    };
                }
            };

            Config.enable_parallel_lake_tablet_stat_collection = false;
            TabletStatMgr serialTabletStatMgr = createTabletStatMgrForTest();
            Deencapsulation.invoke(serialTabletStatMgr, "updateLakeTableTabletStat", serialDb, serialTable);

            Config.enable_parallel_lake_tablet_stat_collection = true;
            Config.lake_tablet_stat_collect_parallelism = 2;
            Config.lake_tablet_stat_max_inflight_tasks = 2;
            TabletStatMgr parallelTabletStatMgr = createTabletStatMgrForTest();
            Deencapsulation.invoke(parallelTabletStatMgr, "updateLakeTableTabletStat", parallelDb, parallelTable);

            Assertions.assertEquals(collectLakeTabletRowCounts(serialTable), collectLakeTabletRowCounts(parallelTable));
            Assertions.assertEquals(collectLakeTabletDataSizes(serialTable), collectLakeTabletDataSizes(parallelTable));
            assertLakeTabletDataSizeUpdateTimeSet(serialTable);
            assertLakeTabletDataSizeUpdateTimeSet(parallelTable);
        } finally {
            Config.enable_parallel_lake_tablet_stat_collection = oldEnabled;
            Config.enable_lake_tablet_stat_cn_batch_collection = oldCnBatch;
            Config.lake_tablet_stat_collect_parallelism = oldParallelism;
            Config.lake_tablet_stat_max_inflight_tasks = oldMaxInflight;
        }
    }

    @Test
    public void testParallelLakeTabletStatReusesExecutorBetweenRounds(@Mocked LakeService lakeService) {
        boolean oldEnabled = Config.enable_parallel_lake_tablet_stat_collection;
        boolean oldCnBatch = Config.enable_lake_tablet_stat_cn_batch_collection;
        int oldParallelism = Config.lake_tablet_stat_collect_parallelism;
        int oldMaxInflight = Config.lake_tablet_stat_max_inflight_tasks;
        try {
            Config.enable_parallel_lake_tablet_stat_collection = true;
            Config.enable_lake_tablet_stat_cn_batch_collection = false;
            Config.lake_tablet_stat_collect_parallelism = 1;
            Config.lake_tablet_stat_max_inflight_tasks = 1;

            LakeTable firstTable = createLakeTableWithPartitionsForTest(1);
            LakeTable secondTable = createLakeTableWithPartitionsForTest(1);
            Database firstDb = new Database(DB_ID, "db");
            firstDb.registerTableUnlocked(firstTable);
            Database secondDb = new Database(DB_ID, "db");
            secondDb.registerTableUnlocked(secondTable);

            new MockUp<BrpcProxy>() {
                @Mock
                public LakeService getLakeService(String host, int port) {
                    return lakeService;
                }
            };

            Set<Long> workerThreadIds = ConcurrentHashMap.newKeySet();
            new Expectations() {
                {
                    lakeService.getTabletStats((TabletStatRequest) any);
                    minTimes = 2;
                    result = new Delegate() {
                        Future<TabletStatResponse> getTabletStats(TabletStatRequest request) {
                            workerThreadIds.add(Thread.currentThread().getId());
                            TabletStatResponse response = new TabletStatResponse();
                            List<TabletStat> stats = Lists.newArrayList();
                            TabletStat stat = new TabletStat();
                            stat.tabletId = request.tabletInfos.get(0).tabletId;
                            stat.numRows = 10L;
                            stat.dataSize = 100L;
                            stats.add(stat);
                            response.tabletStats = stats;
                            return CompletableFuture.completedFuture(response);
                        }
                    };
                }
            };

            TabletStatMgr tabletStatMgr = createTabletStatMgrForTest();
            Deencapsulation.invoke(tabletStatMgr, "updateLakeTableTabletStat", firstDb, firstTable);
            Deencapsulation.invoke(tabletStatMgr, "updateLakeTableTabletStat", secondDb, secondTable);

            Assertions.assertEquals(1, workerThreadIds.size(),
                    "parallel lake tablet stat collection should reuse the same executor across rounds");
        } finally {
            Config.enable_parallel_lake_tablet_stat_collection = oldEnabled;
            Config.enable_lake_tablet_stat_cn_batch_collection = oldCnBatch;
            Config.lake_tablet_stat_collect_parallelism = oldParallelism;
            Config.lake_tablet_stat_max_inflight_tasks = oldMaxInflight;
        }
    }

    @Test
    public void testParallelLakeTabletStatWaitsForCanceledJobsBeforeReturning(@Mocked LakeService lakeService)
            throws Exception {
        boolean oldEnabled = Config.enable_parallel_lake_tablet_stat_collection;
        boolean oldCnBatch = Config.enable_lake_tablet_stat_cn_batch_collection;
        int oldParallelism = Config.lake_tablet_stat_collect_parallelism;
        int oldMaxInflight = Config.lake_tablet_stat_max_inflight_tasks;
        try {
            Config.enable_parallel_lake_tablet_stat_collection = true;
            Config.enable_lake_tablet_stat_cn_batch_collection = false;
            Config.lake_tablet_stat_collect_parallelism = 2;
            Config.lake_tablet_stat_max_inflight_tasks = 2;

            LakeTable table = createLakeTableWithPartitionsForTest(2);
            Database db = new Database(DB_ID, "db");
            db.registerTableUnlocked(table);

            new MockUp<BrpcProxy>() {
                @Mock
                public LakeService getLakeService(String host, int port) {
                    return lakeService;
                }
            };

            CountDownLatch slowRequestStarted = new CountDownLatch(1);
            CountDownLatch slowRequestCancelObserved = new CountDownLatch(1);
            CountDownLatch allowSlowRequestExit = new CountDownLatch(1);
            new Expectations() {
                {
                    lakeService.getTabletStats((TabletStatRequest) any);
                    minTimes = 2;
                    result = new Delegate() {
                        Future<TabletStatResponse> getTabletStats(TabletStatRequest request) throws Exception {
                            long tabletId = request.tabletInfos.get(0).tabletId;
                            if (tabletId == 10L) {
                                Assertions.assertTrue(slowRequestStarted.await(5, TimeUnit.SECONDS));
                                TabletStatResponse response = new TabletStatResponse();
                                TabletStat stat = new TabletStat();
                                stat.tabletId = -1L;
                                stat.numRows = 1L;
                                stat.dataSize = 1L;
                                response.tabletStats = Lists.newArrayList(stat);
                                return CompletableFuture.completedFuture(response);
                            }

                            slowRequestStarted.countDown();
                            return new Future<TabletStatResponse>() {
                                @Override
                                public boolean cancel(boolean mayInterruptIfRunning) {
                                    return false;
                                }

                                @Override
                                public boolean isCancelled() {
                                    return false;
                                }

                                @Override
                                public boolean isDone() {
                                    return false;
                                }

                                @Override
                                public TabletStatResponse get() throws InterruptedException {
                                    try {
                                        allowSlowRequestExit.await(30, TimeUnit.SECONDS);
                                        return new TabletStatResponse();
                                    } catch (InterruptedException e) {
                                        slowRequestCancelObserved.countDown();
                                        Assertions.assertTrue(allowSlowRequestExit.await(5, TimeUnit.SECONDS));
                                        throw e;
                                    }
                                }

                                @Override
                                public TabletStatResponse get(long timeout, @NotNull TimeUnit unit)
                                        throws InterruptedException {
                                    return get();
                                }
                            };
                        }
                    };
                }
            };

            CountDownLatch updateReturned = new CountDownLatch(1);
            AtomicReference<Throwable> updateFailure = new AtomicReference<>();
            Thread updateThread = new Thread(() -> {
                try {
                    TabletStatMgr tabletStatMgr = createTabletStatMgrForTest();
                    Deencapsulation.invoke(tabletStatMgr, "updateLakeTableTabletStat", db, table);
                } catch (Throwable t) {
                    updateFailure.set(t);
                } finally {
                    updateReturned.countDown();
                }
            });
            updateThread.setDaemon(true);
            updateThread.start();

            Assertions.assertTrue(slowRequestCancelObserved.await(5, TimeUnit.SECONDS));
            boolean returnedBeforeSlowRequestExit = updateReturned.await(100, TimeUnit.MILLISECONDS);
            allowSlowRequestExit.countDown();
            updateThread.join(5000);

            Assertions.assertFalse(returnedBeforeSlowRequestExit,
                    "parallel collector should wait for canceled in-flight jobs to exit before returning");
            Assertions.assertFalse(updateThread.isAlive());
            Assertions.assertTrue(updateFailure.get() instanceof NullPointerException);
        } finally {
            Config.enable_parallel_lake_tablet_stat_collection = oldEnabled;
            Config.enable_lake_tablet_stat_cn_batch_collection = oldCnBatch;
            Config.lake_tablet_stat_collect_parallelism = oldParallelism;
            Config.lake_tablet_stat_max_inflight_tasks = oldMaxInflight;
        }
    }

    @Test
    public void testParallelLakeTabletStatExecutorShutsDownWhenMgrStops() {
        boolean oldEnabled = Config.enable_parallel_lake_tablet_stat_collection;
        boolean oldCnBatch = Config.enable_lake_tablet_stat_cn_batch_collection;
        int oldParallelism = Config.lake_tablet_stat_collect_parallelism;
        int oldMaxInflight = Config.lake_tablet_stat_max_inflight_tasks;
        try {
            Config.enable_parallel_lake_tablet_stat_collection = true;
            Config.enable_lake_tablet_stat_cn_batch_collection = false;
            Config.lake_tablet_stat_collect_parallelism = 1;
            Config.lake_tablet_stat_max_inflight_tasks = 1;

            TabletStatMgr tabletStatMgr = createTabletStatMgrForTest();
            ThreadPoolExecutor executor = Deencapsulation.invoke(tabletStatMgr,
                    "getLakeTabletStatExecutor", 1, 1);
            Assertions.assertFalse(executor.isShutdown());

            tabletStatMgr.setStop();

            Assertions.assertTrue(executor.isShutdown(),
                    "TabletStatMgr should shut down its long-lived lake tablet stat executor when stopped");
        } finally {
            Config.enable_parallel_lake_tablet_stat_collection = oldEnabled;
            Config.enable_lake_tablet_stat_cn_batch_collection = oldCnBatch;
            Config.lake_tablet_stat_collect_parallelism = oldParallelism;
            Config.lake_tablet_stat_max_inflight_tasks = oldMaxInflight;
        }
    }

    @Test
    public void testParallelLakeTabletStatStopDoesNotHangWithQueuedJobs(@Mocked LakeService lakeService)
            throws Exception {
        boolean oldEnabled = Config.enable_parallel_lake_tablet_stat_collection;
        boolean oldCnBatch = Config.enable_lake_tablet_stat_cn_batch_collection;
        int oldParallelism = Config.lake_tablet_stat_collect_parallelism;
        int oldMaxInflight = Config.lake_tablet_stat_max_inflight_tasks;
        try {
            Config.enable_parallel_lake_tablet_stat_collection = true;
            Config.enable_lake_tablet_stat_cn_batch_collection = false;
            Config.lake_tablet_stat_collect_parallelism = 1;
            Config.lake_tablet_stat_max_inflight_tasks = 2;

            LakeTable table = createLakeTableWithPartitionsForTest(2);
            Database db = new Database(DB_ID, "db");
            db.registerTableUnlocked(table);

            new MockUp<BrpcProxy>() {
                @Mock
                public LakeService getLakeService(String host, int port) {
                    return lakeService;
                }
            };

            CountDownLatch firstRequestStarted = new CountDownLatch(1);
            CountDownLatch firstRequestInterrupted = new CountDownLatch(1);
            CountDownLatch allowFirstRequestExit = new CountDownLatch(1);
            new Expectations() {
                {
                    lakeService.getTabletStats((TabletStatRequest) any);
                    minTimes = 1;
                    result = new Delegate() {
                        Future<TabletStatResponse> getTabletStats(TabletStatRequest request) {
                            firstRequestStarted.countDown();
                            return new Future<TabletStatResponse>() {
                                @Override
                                public boolean cancel(boolean mayInterruptIfRunning) {
                                    return false;
                                }

                                @Override
                                public boolean isCancelled() {
                                    return false;
                                }

                                @Override
                                public boolean isDone() {
                                    return false;
                                }

                                @Override
                                public TabletStatResponse get() throws InterruptedException {
                                    try {
                                        allowFirstRequestExit.await(30, TimeUnit.SECONDS);
                                        return new TabletStatResponse();
                                    } catch (InterruptedException e) {
                                        firstRequestInterrupted.countDown();
                                        Assertions.assertTrue(allowFirstRequestExit.await(5, TimeUnit.SECONDS));
                                        throw e;
                                    }
                                }

                                @Override
                                public TabletStatResponse get(long timeout, @NotNull TimeUnit unit)
                                        throws InterruptedException {
                                    return get();
                                }
                            };
                        }
                    };
                }
            };

            TabletStatMgr tabletStatMgr = createTabletStatMgrForTest();
            ThreadPoolExecutor executor = Deencapsulation.invoke(tabletStatMgr,
                    "getLakeTabletStatExecutor", 1, 2);
            AtomicReference<Throwable> updateFailure = new AtomicReference<>();
            Thread updateThread = new Thread(() -> {
                try {
                    Deencapsulation.invoke(tabletStatMgr, "updateLakeTableTabletStat", db, table);
                } catch (Throwable t) {
                    updateFailure.set(t);
                }
            });
            updateThread.setDaemon(true);
            updateThread.start();

            Assertions.assertTrue(firstRequestStarted.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (executor.getQueue().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            Assertions.assertFalse(executor.getQueue().isEmpty(),
                    "second partition job should be queued before stopping the manager");

            tabletStatMgr.setStop();
            Assertions.assertTrue(firstRequestInterrupted.await(5, TimeUnit.SECONDS));
            allowFirstRequestExit.countDown();
            updateThread.join(1000);
            boolean stoppedWithoutHang = !updateThread.isAlive();
            if (!stoppedWithoutHang) {
                updateThread.interrupt();
                updateThread.join(6000);
            }

            Assertions.assertTrue(stoppedWithoutHang,
                    "stopping TabletStatMgr should not leave active collection waiting on queued jobs");
            Assertions.assertNotNull(updateFailure.get());
        } finally {
            Config.enable_parallel_lake_tablet_stat_collection = oldEnabled;
            Config.enable_lake_tablet_stat_cn_batch_collection = oldCnBatch;
            Config.lake_tablet_stat_collect_parallelism = oldParallelism;
            Config.lake_tablet_stat_max_inflight_tasks = oldMaxInflight;
        }
    }

    @Test
    public void testParallelLakeTabletStatShutsDownExecutorWhenCancelDrainTimesOut(@Mocked LakeService lakeService)
            throws Exception {
        boolean oldEnabled = Config.enable_parallel_lake_tablet_stat_collection;
        boolean oldCnBatch = Config.enable_lake_tablet_stat_cn_batch_collection;
        int oldParallelism = Config.lake_tablet_stat_collect_parallelism;
        int oldMaxInflight = Config.lake_tablet_stat_max_inflight_tasks;
        long oldCancelWaitMs = Config.lake_tablet_stat_cancel_wait_ms;
        try {
            Config.enable_parallel_lake_tablet_stat_collection = true;
            Config.enable_lake_tablet_stat_cn_batch_collection = false;
            Config.lake_tablet_stat_collect_parallelism = 2;
            Config.lake_tablet_stat_max_inflight_tasks = 2;
            Config.lake_tablet_stat_cancel_wait_ms = 100;

            LakeTable table = createLakeTableWithPartitionsForTest(2);
            Database db = new Database(DB_ID, "db");
            db.registerTableUnlocked(table);

            new MockUp<BrpcProxy>() {
                @Mock
                public LakeService getLakeService(String host, int port) {
                    return lakeService;
                }
            };

            CountDownLatch slowRequestStarted = new CountDownLatch(1);
            CountDownLatch allowSlowRequestExit = new CountDownLatch(1);
            new Expectations() {
                {
                    lakeService.getTabletStats((TabletStatRequest) any);
                    minTimes = 2;
                    result = new Delegate() {
                        Future<TabletStatResponse> getTabletStats(TabletStatRequest request) throws Exception {
                            long tabletId = request.tabletInfos.get(0).tabletId;
                            if (tabletId == 10L) {
                                Assertions.assertTrue(slowRequestStarted.await(5, TimeUnit.SECONDS));
                                TabletStatResponse response = new TabletStatResponse();
                                TabletStat stat = new TabletStat();
                                stat.tabletId = -1L;
                                stat.numRows = 1L;
                                stat.dataSize = 1L;
                                response.tabletStats = Lists.newArrayList(stat);
                                return CompletableFuture.completedFuture(response);
                            }

                            slowRequestStarted.countDown();
                            return new Future<TabletStatResponse>() {
                                @Override
                                public boolean cancel(boolean mayInterruptIfRunning) {
                                    return false;
                                }

                                @Override
                                public boolean isCancelled() {
                                    return false;
                                }

                                @Override
                                public boolean isDone() {
                                    return false;
                                }

                                @Override
                                public TabletStatResponse get() throws InterruptedException {
                                    try {
                                        allowSlowRequestExit.await(30, TimeUnit.SECONDS);
                                        return new TabletStatResponse();
                                    } catch (InterruptedException e) {
                                        allowSlowRequestExit.await(30, TimeUnit.SECONDS);
                                        throw e;
                                    }
                                }

                                @Override
                                public TabletStatResponse get(long timeout, @NotNull TimeUnit unit)
                                        throws InterruptedException {
                                    return get();
                                }
                            };
                        }
                    };
                }
            };

            TabletStatMgr tabletStatMgr = createTabletStatMgrForTest();
            ThreadPoolExecutor executor = Deencapsulation.invoke(tabletStatMgr,
                    "getLakeTabletStatExecutor", 2, 2);
            boolean executorShutdown;
            try {
                Assertions.assertThrows(NullPointerException.class,
                        () -> Deencapsulation.invoke(tabletStatMgr, "updateLakeTableTabletStat", db, table));
                executorShutdown = executor.isShutdown();
            } finally {
                allowSlowRequestExit.countDown();
                executor.shutdownNow();
            }

            Assertions.assertTrue(executorShutdown,
                    "executor must be shut down when canceled jobs do not drain before timeout");
        } finally {
            Config.enable_parallel_lake_tablet_stat_collection = oldEnabled;
            Config.enable_lake_tablet_stat_cn_batch_collection = oldCnBatch;
            Config.lake_tablet_stat_collect_parallelism = oldParallelism;
            Config.lake_tablet_stat_max_inflight_tasks = oldMaxInflight;
            Config.lake_tablet_stat_cancel_wait_ms = oldCancelWaitMs;
        }
    }

    @Test
    public void testParallelLakeTabletStatPropagatesUncheckedJobFailure(@Mocked LakeService lakeService) {
        boolean oldEnabled = Config.enable_parallel_lake_tablet_stat_collection;
        boolean oldCnBatch = Config.enable_lake_tablet_stat_cn_batch_collection;
        int oldParallelism = Config.lake_tablet_stat_collect_parallelism;
        int oldMaxInflight = Config.lake_tablet_stat_max_inflight_tasks;
        try {
            Config.enable_parallel_lake_tablet_stat_collection = true;
            Config.enable_lake_tablet_stat_cn_batch_collection = false;
            Config.lake_tablet_stat_collect_parallelism = 2;
            Config.lake_tablet_stat_max_inflight_tasks = 2;

            LakeTable table = createLakeTableWithPartitionsForTest(2);
            Database db = new Database(DB_ID, "db");
            db.registerTableUnlocked(table);

            new MockUp<BrpcProxy>() {
                @Mock
                public LakeService getLakeService(String host, int port) {
                    return lakeService;
                }
            };

            new Expectations() {
                {
                    lakeService.getTabletStats((TabletStatRequest) any);
                    minTimes = 1;
                    result = new Delegate() {
                        Future<TabletStatResponse> getTabletStats(TabletStatRequest request) {
                            TabletStatResponse response = new TabletStatResponse();
                            TabletStat stat = new TabletStat();
                            stat.tabletId = -1L;
                            stat.numRows = 1L;
                            stat.dataSize = 1L;
                            response.tabletStats = Lists.newArrayList(stat);
                            return CompletableFuture.completedFuture(response);
                        }
                    };
                }
            };

            TabletStatMgr tabletStatMgr = createTabletStatMgrForTest();
            Assertions.assertThrows(NullPointerException.class,
                    () -> Deencapsulation.invoke(tabletStatMgr, "updateLakeTableTabletStat", db, table));
        } finally {
            Config.enable_parallel_lake_tablet_stat_collection = oldEnabled;
            Config.enable_lake_tablet_stat_cn_batch_collection = oldCnBatch;
            Config.lake_tablet_stat_collect_parallelism = oldParallelism;
            Config.lake_tablet_stat_max_inflight_tasks = oldMaxInflight;
        }
    }

    @Test
    public void testParallelLakeTabletStatKeepsStatsWhenSendFails(@Mocked LakeService lakeService) {
        boolean oldEnabled = Config.enable_parallel_lake_tablet_stat_collection;
        boolean oldCnBatch = Config.enable_lake_tablet_stat_cn_batch_collection;
        try {
            Config.enable_parallel_lake_tablet_stat_collection = true;
            Config.enable_lake_tablet_stat_cn_batch_collection = false;

            LakeTable table = createLakeTableForTest();
            Database db = new Database(DB_ID, "db");
            db.registerTableUnlocked(table);

            new MockUp<BrpcProxy>() {
                @Mock
                public LakeService getLakeService(String host, int port) {
                    throw new RuntimeException("injected exception");
                }
            };

            TabletStatMgr tabletStatMgr = createTabletStatMgrForTest();
            Deencapsulation.invoke(tabletStatMgr, "updateLakeTableTabletStat", db, table);

            LakeTablet tablet1 = (LakeTablet) table.getPartition(PARTITION_ID).getDefaultPhysicalPartition()
                    .getLatestBaseIndex().getTablets().get(0);
            LakeTablet tablet2 = (LakeTablet) table.getPartition(PARTITION_ID).getDefaultPhysicalPartition()
                    .getLatestBaseIndex().getTablets().get(1);

            Assertions.assertEquals(0, tablet1.getRowCount(-1));
            Assertions.assertEquals(0, tablet1.getDataSize(true));
            Assertions.assertEquals(0, tablet2.getRowCount(-1));
            Assertions.assertEquals(0, tablet2.getDataSize(true));
            Assertions.assertEquals(0L, tablet1.getDataSizeUpdateTime());
            Assertions.assertEquals(0L, tablet2.getDataSizeUpdateTime());
        } finally {
            Config.enable_parallel_lake_tablet_stat_collection = oldEnabled;
            Config.enable_lake_tablet_stat_cn_batch_collection = oldCnBatch;
        }
    }

    @Test
    public void testParallelLakeTabletStatKeepsStatsWhenResponseFutureFails(@Mocked LakeService lakeService) {
        boolean oldEnabled = Config.enable_parallel_lake_tablet_stat_collection;
        boolean oldCnBatch = Config.enable_lake_tablet_stat_cn_batch_collection;
        try {
            Config.enable_parallel_lake_tablet_stat_collection = true;
            Config.enable_lake_tablet_stat_cn_batch_collection = false;

            LakeTable table = createLakeTableForTest();
            Database db = new Database(DB_ID, "db");
            db.registerTableUnlocked(table);

            new MockUp<BrpcProxy>() {
                @Mock
                public LakeService getLakeService(String host, int port) {
                    return lakeService;
                }
            };

            new Expectations() {
                {
                    lakeService.getTabletStats((TabletStatRequest) any);
                    minTimes = 1;
                    result = new Delegate() {
                        Future<TabletStatResponse> getTabletStats(TabletStatRequest request) {
                            CompletableFuture<TabletStatResponse> future = new CompletableFuture<>();
                            future.completeExceptionally(new RuntimeException("injected"));
                            return future;
                        }
                    };
                }
            };

            TabletStatMgr tabletStatMgr = createTabletStatMgrForTest();
            Deencapsulation.invoke(tabletStatMgr, "updateLakeTableTabletStat", db, table);

            LakeTablet tablet1 = (LakeTablet) table.getPartition(PARTITION_ID).getDefaultPhysicalPartition()
                    .getLatestBaseIndex().getTablets().get(0);
            LakeTablet tablet2 = (LakeTablet) table.getPartition(PARTITION_ID).getDefaultPhysicalPartition()
                    .getLatestBaseIndex().getTablets().get(1);

            Assertions.assertEquals(0, tablet1.getRowCount(-1));
            Assertions.assertEquals(0, tablet1.getDataSize(true));
            Assertions.assertEquals(0, tablet2.getRowCount(-1));
            Assertions.assertEquals(0, tablet2.getDataSize(true));
            Assertions.assertEquals(0L, tablet1.getDataSizeUpdateTime());
            Assertions.assertEquals(0L, tablet2.getDataSizeUpdateTime());
        } finally {
            Config.enable_parallel_lake_tablet_stat_collection = oldEnabled;
            Config.enable_lake_tablet_stat_cn_batch_collection = oldCnBatch;
        }
    }

    @Test
    public void testParallelLakeTabletStatCollectsMultiplePartitionsConcurrently(@Mocked LakeService lakeService)
            throws Exception {
        boolean oldEnabled = Config.enable_parallel_lake_tablet_stat_collection;
        boolean oldCnBatch = Config.enable_lake_tablet_stat_cn_batch_collection;
        int oldParallelism = Config.lake_tablet_stat_collect_parallelism;
        int oldMaxInflight = Config.lake_tablet_stat_max_inflight_tasks;
        try {
            Config.enable_parallel_lake_tablet_stat_collection = true;
            Config.enable_lake_tablet_stat_cn_batch_collection = false;
            Config.lake_tablet_stat_collect_parallelism = 2;
            Config.lake_tablet_stat_max_inflight_tasks = 2;

            LakeTable table = createLakeTableWithPartitionsForTest(3);
            Database db = new Database(DB_ID, "db");
            db.registerTableUnlocked(table);

            new MockUp<BrpcProxy>() {
                @Mock
                public LakeService getLakeService(String host, int port) {
                    return lakeService;
                }
            };

            CountDownLatch firstTwoRequestsSent = new CountDownLatch(2);
            CountDownLatch releaseResponses = new CountDownLatch(1);
            AtomicInteger requestCount = new AtomicInteger();
            new Expectations() {
                {
                    lakeService.getTabletStats((TabletStatRequest) any);
                    minTimes = 3;
                    result = new Delegate() {
                        Future<TabletStatResponse> getTabletStats(TabletStatRequest request) {
                            int requestIndex = requestCount.incrementAndGet();
                            firstTwoRequestsSent.countDown();
                            CompletableFuture<TabletStatResponse> future = new CompletableFuture<>();
                            Thread responder = new Thread(() -> {
                                try {
                                    releaseResponses.await(5, TimeUnit.SECONDS);
                                    TabletStatResponse response = new TabletStatResponse();
                                    List<TabletStat> stats = Lists.newArrayList();
                                    TabletStat stat = new TabletStat();
                                    stat.tabletId = request.tabletInfos.get(0).tabletId;
                                    stat.numRows = 100L + requestIndex;
                                    stat.dataSize = 1000L + requestIndex;
                                    stats.add(stat);
                                    response.tabletStats = stats;
                                    future.complete(response);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    future.completeExceptionally(e);
                                }
                            });
                            responder.setDaemon(true);
                            responder.start();
                            return future;
                        }
                    };
                }
            };

            Thread updateThread = new Thread(() -> {
                TabletStatMgr tabletStatMgr = createTabletStatMgrForTest();
                Deencapsulation.invoke(tabletStatMgr, "updateLakeTableTabletStat", db, table);
            });
            updateThread.setDaemon(true);
            updateThread.start();

            Assertions.assertTrue(firstTwoRequestsSent.await(1, TimeUnit.SECONDS),
                    "parallel collection should send two partition RPCs before waiting for the first response");
            Assertions.assertEquals(2, requestCount.get());

            releaseResponses.countDown();
            updateThread.join(5000);
            Assertions.assertFalse(updateThread.isAlive());
            Assertions.assertEquals(3, requestCount.get());
            for (Partition partition : table.getAllPartitions()) {
                LakeTablet tablet = (LakeTablet) partition.getDefaultPhysicalPartition()
                        .getLatestBaseIndex().getTablets().get(0);
                Assertions.assertTrue(tablet.getRowCount(-1) > 0);
                Assertions.assertTrue(tablet.getDataSize(true) > 0);
                Assertions.assertTrue(tablet.getDataSizeUpdateTime() > 0);
            }
        } finally {
            Config.enable_parallel_lake_tablet_stat_collection = oldEnabled;
            Config.enable_lake_tablet_stat_cn_batch_collection = oldCnBatch;
            Config.lake_tablet_stat_collect_parallelism = oldParallelism;
            Config.lake_tablet_stat_max_inflight_tasks = oldMaxInflight;
        }
    }

    @Test
    public void testLakeTabletStatKeepsSerialBehaviorWhenParallelDisabled(@Mocked LakeService lakeService)
            throws Exception {
        boolean oldEnabled = Config.enable_parallel_lake_tablet_stat_collection;
        boolean oldCnBatch = Config.enable_lake_tablet_stat_cn_batch_collection;
        try {
            Config.enable_parallel_lake_tablet_stat_collection = false;
            Config.enable_lake_tablet_stat_cn_batch_collection = false;

            LakeTable table = createLakeTableWithPartitionsForTest(2);
            Database db = new Database(DB_ID, "db");
            db.registerTableUnlocked(table);

            new MockUp<BrpcProxy>() {
                @Mock
                public LakeService getLakeService(String host, int port) {
                    return lakeService;
                }
            };

            CountDownLatch firstRequestSent = new CountDownLatch(1);
            CountDownLatch secondRequestSent = new CountDownLatch(1);
            CountDownLatch releaseFirstResponse = new CountDownLatch(1);
            AtomicInteger requestCount = new AtomicInteger();
            new Expectations() {
                {
                    lakeService.getTabletStats((TabletStatRequest) any);
                    minTimes = 2;
                    result = new Delegate() {
                        Future<TabletStatResponse> getTabletStats(TabletStatRequest request) {
                            int requestIndex = requestCount.incrementAndGet();
                            firstRequestSent.countDown();
                            if (requestIndex == 2) {
                                secondRequestSent.countDown();
                            }
                            CompletableFuture<TabletStatResponse> future = new CompletableFuture<>();
                            Thread responder = new Thread(() -> {
                                try {
                                    if (requestIndex == 1) {
                                        releaseFirstResponse.await(5, TimeUnit.SECONDS);
                                    }
                                    TabletStatResponse response = new TabletStatResponse();
                                    List<TabletStat> stats = Lists.newArrayList();
                                    TabletStat stat = new TabletStat();
                                    stat.tabletId = request.tabletInfos.get(0).tabletId;
                                    stat.numRows = 10L + requestIndex;
                                    stat.dataSize = 20L + requestIndex;
                                    stats.add(stat);
                                    response.tabletStats = stats;
                                    future.complete(response);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    future.completeExceptionally(e);
                                }
                            });
                            responder.setDaemon(true);
                            responder.start();
                            return future;
                        }
                    };
                }
            };

            Thread updateThread = new Thread(() -> {
                TabletStatMgr tabletStatMgr = createTabletStatMgrForTest();
                Deencapsulation.invoke(tabletStatMgr, "updateLakeTableTabletStat", db, table);
            });
            updateThread.setDaemon(true);
            updateThread.start();

            Assertions.assertTrue(firstRequestSent.await(1, TimeUnit.SECONDS));
            Assertions.assertFalse(secondRequestSent.await(100, TimeUnit.MILLISECONDS),
                    "serial collection should not send the second partition RPC before the first response returns");
            Assertions.assertEquals(1, requestCount.get(),
                    "serial collection should not send the second partition RPC before the first response returns");
            releaseFirstResponse.countDown();
            updateThread.join(5000);
            Assertions.assertFalse(updateThread.isAlive());
            Assertions.assertEquals(2, requestCount.get());
        } finally {
            Config.enable_parallel_lake_tablet_stat_collection = oldEnabled;
            Config.enable_lake_tablet_stat_cn_batch_collection = oldCnBatch;
        }
    }

    @Test
    public void testUpdateLakeTabletStat(@Mocked SystemInfoService systemInfoService,
                                         @Mocked LakeService lakeService) {

        LakeTable table = createLakeTableForTest();

        long tablet1Id =
                table.getPartition(PARTITION_ID).getDefaultPhysicalPartition().getLatestBaseIndex().getTablets().get(0).getId();
        long tablet2Id =
                table.getPartition(PARTITION_ID).getDefaultPhysicalPartition().getLatestBaseIndex().getTablets().get(1).getId();

        // db
        Database db = new Database(DB_ID, "db");
        db.registerTableUnlocked(table);

        new MockUp<BrpcProxy>() {
            @Mock
            public LakeService getLakeService(TNetworkAddress addr) {
                return lakeService;
            }

            @Mock
            public LakeService getLakeService(String host, int port) {
                return lakeService;
            }
        };
        new MockUp<Utils>() {
            @Mock
            public Long chooseNodeId(LakeTablet tablet) {
                return 1000L;
            }

            @Mock
            public ComputeNode chooseNode(LakeTablet tablet) {
                return new ComputeNode();
            }
        };

        long tablet1NumRows = 20L;
        long tablet2NumRows = 21L;
        long tablet1DataSize = 30L;
        long tablet2DataSize = 31L;

        new Expectations() {
            {
                lakeService.getTabletStats((TabletStatRequest) any);
                minTimes = 1;
                maxTimes = 1;
                result = new Delegate() {
                    Future<TabletStatResponse> getTabletStats(TabletStatRequest request) {
                        Assertions.assertEquals(LakeService.TIMEOUT_GET_TABLET_STATS, (long) request.timeoutMs);
                        Assertions.assertEquals(2, request.tabletInfos.size());
                        Assertions.assertEquals(tablet1Id, (long) request.tabletInfos.get(0).tabletId);
                        Assertions.assertEquals(tablet2Id, (long) request.tabletInfos.get(1).tabletId);

                        return new Future<TabletStatResponse>() {
                            @Override
                            public boolean cancel(boolean mayInterruptIfRunning) {
                                return false;
                            }

                            @Override
                            public boolean isCancelled() {
                                return false;
                            }

                            @Override
                            public boolean isDone() {
                                return true;
                            }

                            @Override
                            public TabletStatResponse get() {
                                List<TabletStat> stats = Lists.newArrayList();
                                TabletStat stat1 = new TabletStat();
                                stat1.tabletId = tablet1Id;
                                stat1.numRows = tablet1NumRows;
                                stat1.dataSize = tablet1DataSize;
                                stats.add(stat1);
                                TabletStat stat2 = new TabletStat();
                                stat2.tabletId = tablet2Id;
                                stat2.numRows = tablet2NumRows;
                                stat2.dataSize = tablet2DataSize;
                                stats.add(stat2);

                                TabletStatResponse response = new TabletStatResponse();
                                response.tabletStats = stats;
                                return response;
                            }

                            @Override
                            public TabletStatResponse get(long timeout, @NotNull TimeUnit unit) {
                                return null;
                            }
                        };
                    }
                };
            }
        };

        long t1 = System.currentTimeMillis();
        TabletStatMgr tabletStatMgr = createTabletStatMgrForTest();
        Deencapsulation.invoke(tabletStatMgr, "updateLakeTableTabletStat", db, table);
        long t2 = System.currentTimeMillis();

        LakeTablet tablet1 = (LakeTablet) table.getPartition(PARTITION_ID).getDefaultPhysicalPartition()
                .getLatestBaseIndex().getTablets().get(0);
        LakeTablet tablet2 = (LakeTablet) table.getPartition(PARTITION_ID).getDefaultPhysicalPartition()
                .getLatestBaseIndex().getTablets().get(1);

        Assertions.assertEquals(tablet1.getRowCount(-1), tablet1NumRows);
        Assertions.assertEquals(tablet1.getDataSize(true), tablet1DataSize);
        Assertions.assertEquals(tablet2.getRowCount(-1), tablet2NumRows);
        Assertions.assertEquals(tablet2.getDataSize(true), tablet2DataSize);
        Assertions.assertTrue(tablet1.getDataSizeUpdateTime() >= t1 && tablet1.getDataSizeUpdateTime() <= t2);
        Assertions.assertTrue(tablet2.getDataSizeUpdateTime() >= t1 && tablet2.getDataSizeUpdateTime() <= t2);
    }

    @Test
    public void testUpdateLakeTabletStat2(@Mocked SystemInfoService systemInfoService,
                                          @Mocked LakeService lakeService) {
        LakeTable table = createLakeTableForTest();

        long tablet1Id = table.getPartition(PARTITION_ID).getDefaultPhysicalPartition()
                .getLatestBaseIndex().getTablets().get(0).getId();
        long tablet2Id = table.getPartition(PARTITION_ID).getDefaultPhysicalPartition()
                .getLatestBaseIndex().getTablets().get(1).getId();

        // db
        Database db = new Database(DB_ID, "db");
        db.registerTableUnlocked(table);

        new MockUp<BrpcProxy>() {
            @Mock
            public LakeService getLakeService(TNetworkAddress addr) {
                throw new RuntimeException("injected exception");
            }

            @Mock
            public LakeService getLakeService(String host, int port) {
                throw new RuntimeException("injected exception");
            }
        };
        new MockUp<Utils>() {
            @Mock
            public Long chooseNodeId(LakeTablet tablet) {
                return 1000L;
            }

            @Mock
            public ComputeNode chooseNode(LakeTablet tablet) {
                return new ComputeNode();
            }
        };

        TabletStatMgr tabletStatMgr = createTabletStatMgrForTest();
        Deencapsulation.invoke(tabletStatMgr, "updateLakeTableTabletStat", db, table);

        LakeTablet tablet1 = (LakeTablet) table.getPartition(PARTITION_ID).getDefaultPhysicalPartition()
                .getLatestBaseIndex().getTablets().get(0);
        LakeTablet tablet2 = (LakeTablet) table.getPartition(PARTITION_ID).getDefaultPhysicalPartition()
                .getLatestBaseIndex().getTablets().get(1);

        Assertions.assertEquals(0, tablet1.getRowCount(-1));
        Assertions.assertEquals(0, tablet1.getDataSize(true));
        Assertions.assertEquals(0, tablet2.getRowCount(-1));
        Assertions.assertEquals(0, tablet2.getDataSize(true));
        Assertions.assertEquals(0L, tablet1.getDataSizeUpdateTime());
        Assertions.assertEquals(0L, tablet2.getDataSizeUpdateTime());
    }

    @Test
    public void testUpdateLakeTabletStat3(@Mocked SystemInfoService systemInfoService,
                                          @Mocked LakeService lakeService) {
        LakeTable table = createLakeTableForTest();

        long tablet1Id = table.getPartition(PARTITION_ID).getDefaultPhysicalPartition()
                .getLatestBaseIndex().getTablets().get(0).getId();
        long tablet2Id = table.getPartition(PARTITION_ID).getDefaultPhysicalPartition()
                .getLatestBaseIndex().getTablets().get(1).getId();

        // db
        Database db = new Database(DB_ID, "db");
        db.registerTableUnlocked(table);

        new MockUp<BrpcProxy>() {
            @Mock
            public LakeService getLakeService(TNetworkAddress addr) {
                return lakeService;
            }

            @Mock
            public LakeService getLakeService(String host, int port) {
                return lakeService;
            }
        };
        new MockUp<Utils>() {
            @Mock
            public Long chooseNodeId(LakeTablet tablet) {
                return 1000L;
            }

            @Mock
            public ComputeNode chooseNode(LakeTablet tablet) {
                return new ComputeNode();
            }
        };

        long tablet1NumRows = 20L;
        long tablet2NumRows = 21L;
        long tablet1DataSize = 30L;
        long tablet2DataSize = 31L;

        new Expectations() {
            {
                lakeService.getTabletStats((TabletStatRequest) any);
                minTimes = 1;
                maxTimes = 1;
                result = new Delegate() {
                    Future<TabletStatResponse> getTabletStats(TabletStatRequest request) {
                        return new Future<TabletStatResponse>() {
                            @Override
                            public boolean cancel(boolean mayInterruptIfRunning) {
                                return false;
                            }

                            @Override
                            public boolean isCancelled() {
                                return false;
                            }

                            @Override
                            public boolean isDone() {
                                return true;
                            }

                            @Override
                            public TabletStatResponse get() throws ExecutionException {
                                throw new ExecutionException(new RuntimeException("injected"));
                            }

                            @Override
                            public TabletStatResponse get(long timeout, @NotNull TimeUnit unit) {
                                return null;
                            }
                        };
                    }
                };
            }
        };

        TabletStatMgr tabletStatMgr = createTabletStatMgrForTest();
        Deencapsulation.invoke(tabletStatMgr, "updateLakeTableTabletStat", db, table);

        LakeTablet tablet1 = (LakeTablet) table.getPartition(PARTITION_ID).getDefaultPhysicalPartition()
                .getLatestBaseIndex().getTablets().get(0);
        LakeTablet tablet2 = (LakeTablet) table.getPartition(PARTITION_ID).getDefaultPhysicalPartition()
                .getLatestBaseIndex().getTablets().get(1);

        Assertions.assertEquals(0, tablet1.getRowCount(-1));
        Assertions.assertEquals(0, tablet1.getDataSize(true));
        Assertions.assertEquals(0, tablet2.getRowCount(-1));
        Assertions.assertEquals(0, tablet2.getDataSize(true));
        Assertions.assertEquals(0L, tablet1.getDataSizeUpdateTime());
        Assertions.assertEquals(0L, tablet2.getDataSizeUpdateTime());
    }

    @Test
    public void testNoAliveNode(@Mocked SystemInfoService systemInfoService, @Mocked LakeService lakeService) {
        LakeTable table = createLakeTableForTest();

        // db
        Database db = new Database(DB_ID, "db");
        db.registerTableUnlocked(table);

        new MockUp<BrpcProxy>() {
            @Mock
            public LakeService getLakeService(TNetworkAddress addr) {
                return lakeService;
            }

            @Mock
            public LakeService getLakeService(String host, int port) {
                return lakeService;
            }
        };
        new MockUp<Utils>() {
            @Mock
            public Long chooseNodeId(LakeTablet tablet) {
                return 1000L;
            }

            @Mock
            public ComputeNode chooseNode(LakeTablet tablet) {
                return null;
            }
        };

        TabletStatMgr tabletStatMgr = createTabletStatMgrForTest();
        assertDoesNotThrow(() -> {
            Deencapsulation.invoke(tabletStatMgr, "updateLakeTableTabletStat", db, table);
        });
    }


    @Test
    public void testExceptionAliveNode(@Mocked WarehouseManager warehouseManager, @Mocked LakeService lakeService,
                                       @Mocked GlobalStateMgr globalStateMgr) {
        LakeTable table = createLakeTableForTest();

        // db
        Database db = new Database(DB_ID, "db");
        db.registerTableUnlocked(table);

        new MockUp<GlobalStateMgr>() {
            @Mock
            public GlobalStateMgr getCurrentState() {
                return globalStateMgr;
            }
        };

        new MockUp<WarehouseManager>() {
            @Mock
            public WarehouseManager getWarehouseMgr() {
                return warehouseManager;
            }
        };

        new MockUp<Utils>() {
            @Mock
            public Long chooseNodeId(LakeTablet tablet) {
                return 1000L;
            }

            @Mock
            public ComputeNode chooseNode(LakeTablet tablet) {
                return null;
            }
        };

        new Expectations() {
            {
                warehouseManager.getComputeNodeAssignedToTablet((ComputeResource) any, anyLong);
                result = new Delegate() {
                    ComputeNode getComputeNodeAssignedToTablet(ComputeResource computeResource, long tabletId) {
                        throw ErrorReportException.report(ErrorCode.ERR_NO_NODES_IN_WAREHOUSE, tabletId);
                    }
                };
            }
        };

        new Expectations() {
            {
                lakeService.getTabletStats((TabletStatRequest) any);
                times = 0;
            }
        };

        TabletStatMgr tabletStatMgr = createTabletStatMgrForTest();
        Deencapsulation.invoke(tabletStatMgr, "updateLakeTableTabletStat", db, table);

    }

    @Test
    public void testNullAliveNode(@Mocked WarehouseManager warehouseManager, @Mocked LakeService lakeService,
                                  @Mocked GlobalStateMgr globalStateMgr) {
        LakeTable table = createLakeTableForTest();

        // db
        Database db = new Database(DB_ID, "db");
        db.registerTableUnlocked(table);

        new MockUp<GlobalStateMgr>() {
            @Mock
            public GlobalStateMgr getCurrentState() {
                return globalStateMgr;
            }
        };

        new MockUp<WarehouseManager>() {
            @Mock
            public WarehouseManager getWarehouseMgr() {
                return warehouseManager;
            }
        };


        new MockUp<BrpcProxy>() {
            @Mock
            public LakeService getLakeService(TNetworkAddress addr) {
                return lakeService;
            }

            @Mock
            public LakeService getLakeService(String host, int port) {
                return lakeService;
            }
        };
        new MockUp<Utils>() {
            @Mock
            public Long chooseNodeId(LakeTablet tablet) {
                return 1000L;
            }

            @Mock
            public ComputeNode chooseNode(LakeTablet tablet) {
                return null;
            }
        };

        new Expectations() {
            {
                warehouseManager.getComputeNodeAssignedToTablet((ComputeResource) any, anyLong);
                result = new Delegate() {
                    ComputeNode getComputeNodeAssignedToTablet(ComputeResource computeResource, long tabletId) {
                        if (tabletId == 10L) {
                            return null;
                        }
                        return new ComputeNode(1000L, "127.0.0.1", 9030);
                    }
                };
            }
        };

        new Expectations() {
            {
                lakeService.getTabletStats((TabletStatRequest) any);
                times = 1;
            }
        };

        TabletStatMgr tabletStatMgr = createTabletStatMgrForTest();
        Deencapsulation.invoke(tabletStatMgr, "updateLakeTableTabletStat", db, table);

    }

    // ---- Per-CN batch collection (enable_lake_tablet_stat_cn_batch_collection) tests ----

    private void runCnBatch(TabletStatMgr mgr, Database db, LakeTable table) {
        Deencapsulation.invoke(mgr, "updateLakeTableTabletStat", db, table);
    }

    @Test
    public void testCnBatchDisabledByDefault() {
        Assertions.assertFalse(Config.enable_lake_tablet_stat_cn_batch_collection);
    }

    @Test
    public void testCnBatchUpdatesStatsAndRespectsBatchSize(@Mocked WarehouseManager warehouseManager,
            @Mocked LakeService lakeService, @Mocked GlobalStateMgr globalStateMgr) {
        boolean oldEnabled = Config.enable_parallel_lake_tablet_stat_collection;
        boolean oldCnBatch = Config.enable_lake_tablet_stat_cn_batch_collection;
        int oldBatchSize = Config.lake_tablet_stat_batch_size;
        int oldParallelism = Config.lake_tablet_stat_collect_parallelism;
        int oldMaxInflight = Config.lake_tablet_stat_max_inflight_tasks;
        try {
            Config.enable_parallel_lake_tablet_stat_collection = false;
            Config.enable_lake_tablet_stat_cn_batch_collection = true;
            Config.lake_tablet_stat_batch_size = 2;
            Config.lake_tablet_stat_collect_parallelism = 2;
            Config.lake_tablet_stat_max_inflight_tasks = 4;

            LakeTable table = createLakeTableWithPartitionsForTest(4);
            Database db = new Database(DB_ID, "db");
            db.registerTableUnlocked(table);

            new MockUp<GlobalStateMgr>() {
                @Mock
                public GlobalStateMgr getCurrentState() {
                    return globalStateMgr;
                }
            };
            new MockUp<WarehouseManager>() {
                @Mock
                public WarehouseManager getWarehouseMgr() {
                    return warehouseManager;
                }
            };
            new MockUp<BrpcProxy>() {
                @Mock
                public LakeService getLakeService(String host, int port) {
                    return lakeService;
                }
            };

            List<Integer> requestSizes = new CopyOnWriteArrayList<>();
            new Expectations() {
                {
                    warehouseManager.getComputeNodeAssignedToTablet((ComputeResource) any, anyLong);
                    result = new Delegate() {
                        ComputeNode getComputeNodeAssignedToTablet(ComputeResource cr, long tabletId) {
                            return new ComputeNode(1000L, "127.0.0.1", 9030);
                        }
                    };
                }
            };
            new Expectations() {
                {
                    lakeService.getTabletStats((TabletStatRequest) any);
                    result = new Delegate() {
                        Future<TabletStatResponse> getTabletStats(TabletStatRequest request) {
                            requestSizes.add(request.tabletInfos.size());
                            TabletStatResponse response = new TabletStatResponse();
                            List<TabletStat> stats = Lists.newArrayList();
                            for (TabletStatRequest.TabletInfo tabletInfo : request.tabletInfos) {
                                TabletStat stat = new TabletStat();
                                stat.tabletId = tabletInfo.tabletId;
                                stat.numRows = tabletInfo.tabletId * 10;
                                stat.dataSize = tabletInfo.tabletId * 100;
                                stats.add(stat);
                            }
                            response.tabletStats = stats;
                            return CompletableFuture.completedFuture(response);
                        }
                    };
                }
            };

            runCnBatch(createTabletStatMgrForTest(), db, table);

            for (Partition partition : table.getAllPartitions()) {
                LakeTablet tablet = (LakeTablet) partition.getDefaultPhysicalPartition()
                        .getLatestBaseIndex().getTablets().get(0);
                Assertions.assertEquals(tablet.getId() * 10, tablet.getRowCount(-1));
                Assertions.assertEquals(tablet.getId() * 100, tablet.getDataSize(true));
                Assertions.assertTrue(tablet.getDataSizeUpdateTime() > 0);
            }
            Assertions.assertFalse(requestSizes.isEmpty());
            int total = 0;
            for (int size : requestSizes) {
                Assertions.assertTrue(size <= 2, "batch size must cap at 2 but was " + size);
                total += size;
            }
            Assertions.assertEquals(4, total);
        } finally {
            Config.enable_parallel_lake_tablet_stat_collection = oldEnabled;
            Config.enable_lake_tablet_stat_cn_batch_collection = oldCnBatch;
            Config.lake_tablet_stat_batch_size = oldBatchSize;
            Config.lake_tablet_stat_collect_parallelism = oldParallelism;
            Config.lake_tablet_stat_max_inflight_tasks = oldMaxInflight;
        }
    }

    @Test
    public void testParallelCollectionSkipsInitialVersionPartitions(@Mocked WarehouseManager warehouseManager,
            @Mocked LakeService lakeService, @Mocked GlobalStateMgr globalStateMgr) {
        boolean oldEnabled = Config.enable_parallel_lake_tablet_stat_collection;
        boolean oldCnBatch = Config.enable_lake_tablet_stat_cn_batch_collection;
        boolean oldSkip = Config.enable_lake_tablet_stat_skip_initial_version;
        try {
            Config.enable_parallel_lake_tablet_stat_collection = true;
            Config.enable_lake_tablet_stat_cn_batch_collection = false;
            Config.enable_lake_tablet_stat_skip_initial_version = true;

            LakeTable table = createLakeTableWithPartitionsForTest(4); // tablets 10,11,12,13
            Database db = new Database(DB_ID, "db");
            db.registerTableUnlocked(table);
            markPartitionsInitialVersion(table, 10L, 12L);

            List<Long> requestedTabletIds = new CopyOnWriteArrayList<>();
            mockComputeNodeAndTabletStats(warehouseManager, lakeService, globalStateMgr, requestedTabletIds);

            Deencapsulation.invoke(createTabletStatMgrForTest(), "updateLakeTableTabletStat", db, table);

            Assertions.assertEquals(2, requestedTabletIds.size());
            Assertions.assertTrue(requestedTabletIds.contains(11L));
            Assertions.assertTrue(requestedTabletIds.contains(13L));
            Assertions.assertFalse(requestedTabletIds.contains(10L));
            Assertions.assertFalse(requestedTabletIds.contains(12L));
        } finally {
            Config.enable_parallel_lake_tablet_stat_collection = oldEnabled;
            Config.enable_lake_tablet_stat_cn_batch_collection = oldCnBatch;
            Config.enable_lake_tablet_stat_skip_initial_version = oldSkip;
        }
    }

    @Test
    public void testCnBatchCollectionSkipsInitialVersionPartitions(@Mocked WarehouseManager warehouseManager,
            @Mocked LakeService lakeService, @Mocked GlobalStateMgr globalStateMgr) {
        boolean oldEnabled = Config.enable_parallel_lake_tablet_stat_collection;
        boolean oldCnBatch = Config.enable_lake_tablet_stat_cn_batch_collection;
        boolean oldSkip = Config.enable_lake_tablet_stat_skip_initial_version;
        int oldBatchSize = Config.lake_tablet_stat_batch_size;
        try {
            Config.enable_parallel_lake_tablet_stat_collection = false;
            Config.enable_lake_tablet_stat_cn_batch_collection = true;
            Config.enable_lake_tablet_stat_skip_initial_version = true;
            Config.lake_tablet_stat_batch_size = 100;

            LakeTable table = createLakeTableWithPartitionsForTest(4); // tablets 10,11,12,13
            Database db = new Database(DB_ID, "db");
            db.registerTableUnlocked(table);
            markPartitionsInitialVersion(table, 10L, 12L);

            List<Long> requestedTabletIds = new CopyOnWriteArrayList<>();
            mockComputeNodeAndTabletStats(warehouseManager, lakeService, globalStateMgr, requestedTabletIds);

            runCnBatch(createTabletStatMgrForTest(), db, table);

            Assertions.assertEquals(2, requestedTabletIds.size());
            Assertions.assertTrue(requestedTabletIds.contains(11L));
            Assertions.assertTrue(requestedTabletIds.contains(13L));
            Assertions.assertFalse(requestedTabletIds.contains(10L));
            Assertions.assertFalse(requestedTabletIds.contains(12L));
        } finally {
            Config.enable_parallel_lake_tablet_stat_collection = oldEnabled;
            Config.enable_lake_tablet_stat_cn_batch_collection = oldCnBatch;
            Config.enable_lake_tablet_stat_skip_initial_version = oldSkip;
            Config.lake_tablet_stat_batch_size = oldBatchSize;
        }
    }

    @Test
    public void testInitialVersionSkipCanBeDisabled(@Mocked WarehouseManager warehouseManager,
            @Mocked LakeService lakeService, @Mocked GlobalStateMgr globalStateMgr) {
        boolean oldEnabled = Config.enable_parallel_lake_tablet_stat_collection;
        boolean oldCnBatch = Config.enable_lake_tablet_stat_cn_batch_collection;
        boolean oldSkip = Config.enable_lake_tablet_stat_skip_initial_version;
        try {
            Config.enable_parallel_lake_tablet_stat_collection = true;
            Config.enable_lake_tablet_stat_cn_batch_collection = false;
            Config.enable_lake_tablet_stat_skip_initial_version = false;

            LakeTable table = createLakeTableWithPartitionsForTest(4); // tablets 10,11,12,13
            Database db = new Database(DB_ID, "db");
            db.registerTableUnlocked(table);
            markPartitionsInitialVersion(table, 10L, 12L);

            List<Long> requestedTabletIds = new CopyOnWriteArrayList<>();
            mockComputeNodeAndTabletStats(warehouseManager, lakeService, globalStateMgr, requestedTabletIds);

            Deencapsulation.invoke(createTabletStatMgrForTest(), "updateLakeTableTabletStat", db, table);

            // With the skip disabled, every stale tablet is still requested, including initial-version ones.
            Assertions.assertEquals(4, requestedTabletIds.size());
            Assertions.assertTrue(requestedTabletIds.contains(10L));
            Assertions.assertTrue(requestedTabletIds.contains(12L));
        } finally {
            Config.enable_parallel_lake_tablet_stat_collection = oldEnabled;
            Config.enable_lake_tablet_stat_cn_batch_collection = oldCnBatch;
            Config.enable_lake_tablet_stat_skip_initial_version = oldSkip;
        }
    }

    private void mockComputeNodeAndTabletStats(WarehouseManager warehouseManager, LakeService lakeService,
            GlobalStateMgr globalStateMgr, List<Long> requestedTabletIds) {
        new MockUp<GlobalStateMgr>() {
            @Mock
            public GlobalStateMgr getCurrentState() {
                return globalStateMgr;
            }
        };
        new MockUp<WarehouseManager>() {
            @Mock
            public WarehouseManager getWarehouseMgr() {
                return warehouseManager;
            }
        };
        new MockUp<BrpcProxy>() {
            @Mock
            public LakeService getLakeService(String host, int port) {
                return lakeService;
            }
        };
        new Expectations() {
            {
                warehouseManager.getComputeNodeAssignedToTablet((ComputeResource) any, anyLong);
                result = new Delegate() {
                    ComputeNode getComputeNodeAssignedToTablet(ComputeResource cr, long tabletId) {
                        return new ComputeNode(1000L, "127.0.0.1", 9030);
                    }
                };
            }
        };
        new Expectations() {
            {
                lakeService.getTabletStats((TabletStatRequest) any);
                result = new Delegate() {
                    Future<TabletStatResponse> getTabletStats(TabletStatRequest request) {
                        TabletStatResponse response = new TabletStatResponse();
                        List<TabletStat> stats = Lists.newArrayList();
                        for (TabletStatRequest.TabletInfo tabletInfo : request.tabletInfos) {
                            requestedTabletIds.add(tabletInfo.tabletId);
                            TabletStat stat = new TabletStat();
                            stat.tabletId = tabletInfo.tabletId;
                            stat.numRows = 1L;
                            stat.dataSize = 10L;
                            stats.add(stat);
                        }
                        response.tabletStats = stats;
                        return CompletableFuture.completedFuture(response);
                    }
                };
            }
        };
    }

    @Test
    public void testCnBatchLimitsOneInFlightBatchPerComputeNode(@Mocked WarehouseManager warehouseManager,
            @Mocked LakeService lakeService, @Mocked GlobalStateMgr globalStateMgr) throws Exception {
        boolean oldEnabled = Config.enable_parallel_lake_tablet_stat_collection;
        boolean oldCnBatch = Config.enable_lake_tablet_stat_cn_batch_collection;
        int oldBatchSize = Config.lake_tablet_stat_batch_size;
        int oldParallelism = Config.lake_tablet_stat_collect_parallelism;
        int oldMaxInflight = Config.lake_tablet_stat_max_inflight_tasks;
        CountDownLatch allowResponses = new CountDownLatch(1);
        Thread updateThread = null;
        try {
            Config.enable_parallel_lake_tablet_stat_collection = false;
            Config.enable_lake_tablet_stat_cn_batch_collection = true;
            Config.lake_tablet_stat_batch_size = 1;
            Config.lake_tablet_stat_collect_parallelism = 2;
            Config.lake_tablet_stat_max_inflight_tasks = 4;

            LakeTable table = createLakeTableWithPartitionsForTest(3);
            Database db = new Database(DB_ID, "db");
            db.registerTableUnlocked(table);

            new MockUp<GlobalStateMgr>() {
                @Mock
                public GlobalStateMgr getCurrentState() {
                    return globalStateMgr;
                }
            };
            new MockUp<WarehouseManager>() {
                @Mock
                public WarehouseManager getWarehouseMgr() {
                    return warehouseManager;
                }
            };
            new MockUp<BrpcProxy>() {
                @Mock
                public LakeService getLakeService(String host, int port) {
                    return lakeService;
                }
            };

            new Expectations() {
                {
                    warehouseManager.getComputeNodeAssignedToTablet((ComputeResource) any, anyLong);
                    result = new Delegate() {
                        ComputeNode getComputeNodeAssignedToTablet(ComputeResource cr, long tabletId) {
                            return new ComputeNode(1000L, "127.0.0.1", 9030);
                        }
                    };
                }
            };

            CountDownLatch firstRequestStarted = new CountDownLatch(1);
            AtomicInteger activeRequests = new AtomicInteger();
            AtomicInteger maxActiveRequests = new AtomicInteger();
            AtomicInteger requestCount = new AtomicInteger();
            new Expectations() {
                {
                    lakeService.getTabletStats((TabletStatRequest) any);
                    minTimes = 1;
                    result = new Delegate() {
                        Future<TabletStatResponse> getTabletStats(TabletStatRequest request) {
                            int active = activeRequests.incrementAndGet();
                            maxActiveRequests.accumulateAndGet(active, Math::max);
                            requestCount.incrementAndGet();
                            firstRequestStarted.countDown();
                            return new Future<TabletStatResponse>() {
                                @Override
                                public boolean cancel(boolean mayInterruptIfRunning) {
                                    return false;
                                }

                                @Override
                                public boolean isCancelled() {
                                    return false;
                                }

                                @Override
                                public boolean isDone() {
                                    return false;
                                }

                                @Override
                                public TabletStatResponse get() throws InterruptedException {
                                    Assertions.assertTrue(allowResponses.await(10, TimeUnit.SECONDS));
                                    activeRequests.decrementAndGet();
                                    TabletStatResponse response = new TabletStatResponse();
                                    List<TabletStat> stats = Lists.newArrayList();
                                    for (TabletStatRequest.TabletInfo tabletInfo : request.tabletInfos) {
                                        TabletStat stat = new TabletStat();
                                        stat.tabletId = tabletInfo.tabletId;
                                        stat.numRows = tabletInfo.tabletId;
                                        stat.dataSize = tabletInfo.tabletId;
                                        stats.add(stat);
                                    }
                                    response.tabletStats = stats;
                                    return response;
                                }

                                @Override
                                public TabletStatResponse get(long timeout, @NotNull TimeUnit unit)
                                        throws InterruptedException {
                                    return get();
                                }
                            };
                        }
                    };
                }
            };

            TabletStatMgr tabletStatMgr = createTabletStatMgrForTest();
            AtomicReference<Throwable> updateFailure = new AtomicReference<>();
            updateThread = new Thread(() -> {
                try {
                    runCnBatch(tabletStatMgr, db, table);
                } catch (Throwable t) {
                    updateFailure.set(t);
                }
            });
            updateThread.setDaemon(true);
            updateThread.start();

            Assertions.assertTrue(firstRequestStarted.await(5, TimeUnit.SECONDS));
            Thread.sleep(200);
            Assertions.assertEquals(1, requestCount.get(),
                    "CN-batch must not send a second batch to the same CN while one is in-flight");
            Assertions.assertEquals(1, maxActiveRequests.get(),
                    "CN-batch must keep at most one in-flight request per CN");

            allowResponses.countDown();
            updateThread.join(5000);
            Assertions.assertFalse(updateThread.isAlive());
            Assertions.assertNull(updateFailure.get());
            Assertions.assertEquals(3, requestCount.get());
        } finally {
            allowResponses.countDown();
            if (updateThread != null && updateThread.isAlive()) {
                updateThread.interrupt();
                updateThread.join(5000);
            }
            Config.enable_parallel_lake_tablet_stat_collection = oldEnabled;
            Config.enable_lake_tablet_stat_cn_batch_collection = oldCnBatch;
            Config.lake_tablet_stat_batch_size = oldBatchSize;
            Config.lake_tablet_stat_collect_parallelism = oldParallelism;
            Config.lake_tablet_stat_max_inflight_tasks = oldMaxInflight;
        }
    }

    @Test
    public void testCnBatchKeepsPartitionTabletsTogetherWhenPossible(@Mocked WarehouseManager warehouseManager,
            @Mocked LakeService lakeService, @Mocked GlobalStateMgr globalStateMgr) {
        boolean oldEnabled = Config.enable_parallel_lake_tablet_stat_collection;
        boolean oldCnBatch = Config.enable_lake_tablet_stat_cn_batch_collection;
        int oldBatchSize = Config.lake_tablet_stat_batch_size;
        int oldParallelism = Config.lake_tablet_stat_collect_parallelism;
        int oldMaxInflight = Config.lake_tablet_stat_max_inflight_tasks;
        try {
            Config.enable_parallel_lake_tablet_stat_collection = false;
            Config.enable_lake_tablet_stat_cn_batch_collection = true;
            Config.lake_tablet_stat_batch_size = 3;
            Config.lake_tablet_stat_collect_parallelism = 1;
            Config.lake_tablet_stat_max_inflight_tasks = 4;

            LakeTable table = createLakeTableWithPartitionsAndTabletsForTest(2, 2);
            Database db = new Database(DB_ID, "db");
            db.registerTableUnlocked(table);

            new MockUp<GlobalStateMgr>() {
                @Mock
                public GlobalStateMgr getCurrentState() {
                    return globalStateMgr;
                }
            };
            new MockUp<WarehouseManager>() {
                @Mock
                public WarehouseManager getWarehouseMgr() {
                    return warehouseManager;
                }
            };
            new MockUp<BrpcProxy>() {
                @Mock
                public LakeService getLakeService(String host, int port) {
                    return lakeService;
                }
            };

            List<List<Long>> requestTabletIds = new CopyOnWriteArrayList<>();
            new Expectations() {
                {
                    warehouseManager.getComputeNodeAssignedToTablet((ComputeResource) any, anyLong);
                    result = new Delegate() {
                        ComputeNode getComputeNodeAssignedToTablet(ComputeResource cr, long tabletId) {
                            return new ComputeNode(1000L, "127.0.0.1", 9030);
                        }
                    };
                }
            };
            new Expectations() {
                {
                    lakeService.getTabletStats((TabletStatRequest) any);
                    result = new Delegate() {
                        Future<TabletStatResponse> getTabletStats(TabletStatRequest request) {
                            List<Long> ids = Lists.newArrayList();
                            TabletStatResponse response = new TabletStatResponse();
                            List<TabletStat> stats = Lists.newArrayList();
                            for (TabletStatRequest.TabletInfo tabletInfo : request.tabletInfos) {
                                ids.add(tabletInfo.tabletId);
                                TabletStat stat = new TabletStat();
                                stat.tabletId = tabletInfo.tabletId;
                                stat.numRows = 1L;
                                stat.dataSize = 1L;
                                stats.add(stat);
                            }
                            requestTabletIds.add(ids);
                            response.tabletStats = stats;
                            return CompletableFuture.completedFuture(response);
                        }
                    };
                }
            };

            runCnBatch(createTabletStatMgrForTest(), db, table);

            Assertions.assertEquals(2, requestTabletIds.size());
            Assertions.assertTrue(requestTabletIds.contains(Lists.newArrayList(10L, 11L)),
                    "first partition tablets must stay in one batch: " + requestTabletIds);
            Assertions.assertTrue(requestTabletIds.contains(Lists.newArrayList(12L, 13L)),
                    "second partition tablets must stay in one batch: " + requestTabletIds);
        } finally {
            Config.enable_parallel_lake_tablet_stat_collection = oldEnabled;
            Config.enable_lake_tablet_stat_cn_batch_collection = oldCnBatch;
            Config.lake_tablet_stat_batch_size = oldBatchSize;
            Config.lake_tablet_stat_collect_parallelism = oldParallelism;
            Config.lake_tablet_stat_max_inflight_tasks = oldMaxInflight;
        }
    }

    @Test
    public void testCnBatchGroupsByComputeNode(@Mocked WarehouseManager warehouseManager,
            @Mocked LakeService lakeService, @Mocked GlobalStateMgr globalStateMgr) {
        boolean oldEnabled = Config.enable_parallel_lake_tablet_stat_collection;
        boolean oldCnBatch = Config.enable_lake_tablet_stat_cn_batch_collection;
        int oldBatchSize = Config.lake_tablet_stat_batch_size;
        try {
            Config.enable_parallel_lake_tablet_stat_collection = false;
            Config.enable_lake_tablet_stat_cn_batch_collection = true;
            Config.lake_tablet_stat_batch_size = 512;

            LakeTable table = createLakeTableWithPartitionsForTest(4); // tablets 10,11,12,13
            Database db = new Database(DB_ID, "db");
            db.registerTableUnlocked(table);

            new MockUp<GlobalStateMgr>() {
                @Mock
                public GlobalStateMgr getCurrentState() {
                    return globalStateMgr;
                }
            };
            new MockUp<WarehouseManager>() {
                @Mock
                public WarehouseManager getWarehouseMgr() {
                    return warehouseManager;
                }
            };
            new MockUp<BrpcProxy>() {
                @Mock
                public LakeService getLakeService(String host, int port) {
                    return lakeService;
                }
            };

            List<List<Long>> requestTabletIds = new CopyOnWriteArrayList<>();
            new Expectations() {
                {
                    warehouseManager.getComputeNodeAssignedToTablet((ComputeResource) any, anyLong);
                    result = new Delegate() {
                        ComputeNode getComputeNodeAssignedToTablet(ComputeResource cr, long tabletId) {
                            // tablets 10,11 -> node 1000; 12,13 -> node 1001
                            long nodeId = (tabletId <= 11L) ? 1000L : 1001L;
                            return new ComputeNode(nodeId, "127.0.0.1", 9030);
                        }
                    };
                }
            };
            new Expectations() {
                {
                    lakeService.getTabletStats((TabletStatRequest) any);
                    result = new Delegate() {
                        Future<TabletStatResponse> getTabletStats(TabletStatRequest request) {
                            List<Long> ids = Lists.newArrayList();
                            TabletStatResponse response = new TabletStatResponse();
                            List<TabletStat> stats = Lists.newArrayList();
                            for (TabletStatRequest.TabletInfo tabletInfo : request.tabletInfos) {
                                ids.add(tabletInfo.tabletId);
                                TabletStat stat = new TabletStat();
                                stat.tabletId = tabletInfo.tabletId;
                                stat.numRows = 1L;
                                stat.dataSize = 1L;
                                stats.add(stat);
                            }
                            requestTabletIds.add(ids);
                            response.tabletStats = stats;
                            return CompletableFuture.completedFuture(response);
                        }
                    };
                }
            };

            runCnBatch(createTabletStatMgrForTest(), db, table);

            // No request should mix tablets from different compute nodes.
            Assertions.assertEquals(2, requestTabletIds.size());
            for (List<Long> ids : requestTabletIds) {
                boolean allNode1000 = ids.stream().allMatch(id -> id <= 11L);
                boolean allNode1001 = ids.stream().allMatch(id -> id >= 12L);
                Assertions.assertTrue(allNode1000 || allNode1001,
                        "a batch mixed tablets from different compute nodes: " + ids);
            }
        } finally {
            Config.enable_parallel_lake_tablet_stat_collection = oldEnabled;
            Config.enable_lake_tablet_stat_cn_batch_collection = oldCnBatch;
            Config.lake_tablet_stat_batch_size = oldBatchSize;
        }
    }

    @Test
    public void testCnBatchSkipsFreshTablets(@Mocked WarehouseManager warehouseManager,
            @Mocked LakeService lakeService, @Mocked GlobalStateMgr globalStateMgr) {
        boolean oldEnabled = Config.enable_parallel_lake_tablet_stat_collection;
        boolean oldCnBatch = Config.enable_lake_tablet_stat_cn_batch_collection;
        try {
            Config.enable_parallel_lake_tablet_stat_collection = false;
            Config.enable_lake_tablet_stat_cn_batch_collection = true;

            LakeTable table = createLakeTableWithPartitionsForTest(3); // tablets 10,11,12
            Database db = new Database(DB_ID, "db");
            db.registerTableUnlocked(table);

            // Mark tablet 10 as fresh so it must be skipped.
            long freshTime = System.currentTimeMillis() + 3600_000L;
            for (Partition partition : table.getAllPartitions()) {
                LakeTablet tablet = (LakeTablet) partition.getDefaultPhysicalPartition()
                        .getLatestBaseIndex().getTablets().get(0);
                if (tablet.getId() == 10L) {
                    tablet.setDataSizeUpdateTime(freshTime);
                }
            }

            new MockUp<GlobalStateMgr>() {
                @Mock
                public GlobalStateMgr getCurrentState() {
                    return globalStateMgr;
                }
            };
            new MockUp<WarehouseManager>() {
                @Mock
                public WarehouseManager getWarehouseMgr() {
                    return warehouseManager;
                }
            };
            new MockUp<BrpcProxy>() {
                @Mock
                public LakeService getLakeService(String host, int port) {
                    return lakeService;
                }
            };

            List<Long> requestedTabletIds = new CopyOnWriteArrayList<>();
            new Expectations() {
                {
                    warehouseManager.getComputeNodeAssignedToTablet((ComputeResource) any, anyLong);
                    result = new Delegate() {
                        ComputeNode getComputeNodeAssignedToTablet(ComputeResource cr, long tabletId) {
                            return new ComputeNode(1000L, "127.0.0.1", 9030);
                        }
                    };
                }
            };
            new Expectations() {
                {
                    lakeService.getTabletStats((TabletStatRequest) any);
                    result = new Delegate() {
                        Future<TabletStatResponse> getTabletStats(TabletStatRequest request) {
                            TabletStatResponse response = new TabletStatResponse();
                            List<TabletStat> stats = Lists.newArrayList();
                            for (TabletStatRequest.TabletInfo tabletInfo : request.tabletInfos) {
                                requestedTabletIds.add(tabletInfo.tabletId);
                                TabletStat stat = new TabletStat();
                                stat.tabletId = tabletInfo.tabletId;
                                stat.numRows = 1L;
                                stat.dataSize = 1L;
                                stats.add(stat);
                            }
                            response.tabletStats = stats;
                            return CompletableFuture.completedFuture(response);
                        }
                    };
                }
            };

            runCnBatch(createTabletStatMgrForTest(), db, table);

            Assertions.assertFalse(requestedTabletIds.contains(10L),
                    "fresh tablet 10 must not be requested");
            Assertions.assertTrue(requestedTabletIds.contains(11L));
            Assertions.assertTrue(requestedTabletIds.contains(12L));
        } finally {
            Config.enable_parallel_lake_tablet_stat_collection = oldEnabled;
            Config.enable_lake_tablet_stat_cn_batch_collection = oldCnBatch;
        }
    }

    @Test
    public void testCnBatchFailureDoesNotAbortOtherBatches(@Mocked WarehouseManager warehouseManager,
            @Mocked LakeService lakeService, @Mocked GlobalStateMgr globalStateMgr) {
        boolean oldEnabled = Config.enable_parallel_lake_tablet_stat_collection;
        boolean oldCnBatch = Config.enable_lake_tablet_stat_cn_batch_collection;
        int oldBatchSize = Config.lake_tablet_stat_batch_size;
        try {
            Config.enable_parallel_lake_tablet_stat_collection = false;
            Config.enable_lake_tablet_stat_cn_batch_collection = true;
            Config.lake_tablet_stat_batch_size = 512;

            LakeTable table = createLakeTableWithPartitionsForTest(4); // tablets 10,11,12,13
            Database db = new Database(DB_ID, "db");
            db.registerTableUnlocked(table);

            new MockUp<GlobalStateMgr>() {
                @Mock
                public GlobalStateMgr getCurrentState() {
                    return globalStateMgr;
                }
            };
            new MockUp<WarehouseManager>() {
                @Mock
                public WarehouseManager getWarehouseMgr() {
                    return warehouseManager;
                }
            };
            new MockUp<BrpcProxy>() {
                @Mock
                public LakeService getLakeService(String host, int port) {
                    return lakeService;
                }
            };

            new Expectations() {
                {
                    warehouseManager.getComputeNodeAssignedToTablet((ComputeResource) any, anyLong);
                    result = new Delegate() {
                        ComputeNode getComputeNodeAssignedToTablet(ComputeResource cr, long tabletId) {
                            // tablet 10 -> failing node 1000; others -> node 1001
                            long nodeId = (tabletId == 10L) ? 1000L : 1001L;
                            return new ComputeNode(nodeId, "127.0.0.1", 9030);
                        }
                    };
                }
            };
            new Expectations() {
                {
                    lakeService.getTabletStats((TabletStatRequest) any);
                    result = new Delegate() {
                        Future<TabletStatResponse> getTabletStats(TabletStatRequest request) {
                            boolean shouldFail = request.tabletInfos.stream().anyMatch(t -> t.tabletId == 10L);
                            if (shouldFail) {
                                CompletableFuture<TabletStatResponse> failed = new CompletableFuture<>();
                                failed.completeExceptionally(new RuntimeException("injected batch failure"));
                                return failed;
                            }
                            TabletStatResponse response = new TabletStatResponse();
                            List<TabletStat> stats = Lists.newArrayList();
                            for (TabletStatRequest.TabletInfo tabletInfo : request.tabletInfos) {
                                TabletStat stat = new TabletStat();
                                stat.tabletId = tabletInfo.tabletId;
                                stat.numRows = tabletInfo.tabletId * 10;
                                stat.dataSize = tabletInfo.tabletId * 100;
                                stats.add(stat);
                            }
                            response.tabletStats = stats;
                            return CompletableFuture.completedFuture(response);
                        }
                    };
                }
            };

            runCnBatch(createTabletStatMgrForTest(), db, table);

            for (Partition partition : table.getAllPartitions()) {
                LakeTablet tablet = (LakeTablet) partition.getDefaultPhysicalPartition()
                        .getLatestBaseIndex().getTablets().get(0);
                if (tablet.getId() == 10L) {
                    // failing batch: not updated, so it will be retried next round
                    Assertions.assertEquals(0, tablet.getDataSizeUpdateTime());
                } else {
                    Assertions.assertEquals(tablet.getId() * 10, tablet.getRowCount(-1));
                    Assertions.assertTrue(tablet.getDataSizeUpdateTime() > 0);
                }
            }
        } finally {
            Config.enable_parallel_lake_tablet_stat_collection = oldEnabled;
            Config.enable_lake_tablet_stat_cn_batch_collection = oldCnBatch;
            Config.lake_tablet_stat_batch_size = oldBatchSize;
        }
    }
}
