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

import com.google.common.collect.Range;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.lake.DataCacheInfo;
import com.starrocks.thrift.TStorageMedium;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class CatalogRecycleBinErasePartitionLimitTest {
    private int originalMaxNewTasksPerCycle;
    private int originalMaxPendingTasks;
    private CountDownLatch deleteLatch;

    @BeforeEach
    public void setUp() {
        originalMaxNewTasksPerCycle = Config.catalog_recycle_bin_erase_max_new_partition_delete_tasks_per_cycle;
        originalMaxPendingTasks = Config.catalog_recycle_bin_erase_max_pending_partition_delete_tasks;
        deleteLatch = new CountDownLatch(1);
    }

    @AfterEach
    public void tearDown() {
        Config.catalog_recycle_bin_erase_max_new_partition_delete_tasks_per_cycle = originalMaxNewTasksPerCycle;
        Config.catalog_recycle_bin_erase_max_pending_partition_delete_tasks = originalMaxPendingTasks;
        deleteLatch.countDown();
    }

    @Test
    public void testErasePartitionLimitsNewDeleteTasksPerCycle() {
        Config.catalog_recycle_bin_erase_max_new_partition_delete_tasks_per_cycle = 2;
        Config.catalog_recycle_bin_erase_max_pending_partition_delete_tasks = 0;

        CatalogRecycleBin recycleBin = createRecycleBinWithExpiredPartitions(5);
        recycleBin.erasePartition(Long.MAX_VALUE);

        Assertions.assertEquals(2L, recycleBin.estimateCount().get("AsyncDeletePartition"));
    }

    @Test
    public void testErasePartitionLimitsPendingDeleteTasks() {
        Config.catalog_recycle_bin_erase_max_new_partition_delete_tasks_per_cycle = 0;
        Config.catalog_recycle_bin_erase_max_pending_partition_delete_tasks = 3;

        CatalogRecycleBin recycleBin = createRecycleBinWithExpiredPartitions(5);
        recycleBin.erasePartition(Long.MAX_VALUE);

        Assertions.assertEquals(3L, recycleBin.estimateCount().get("AsyncDeletePartition"));
    }

    @Test
    public void testRecycleBinSizeAccessors() {
        CatalogRecycleBin recycleBin = createRecycleBinWithExpiredPartitions(1);

        Assertions.assertEquals(0, recycleBin.getRecycleDatabaseNum());
        Assertions.assertEquals(0, recycleBin.getRecycleTableNum());
        Assertions.assertEquals(1, recycleBin.getRecyclePartitionNum());
    }

    private CatalogRecycleBin createRecycleBinWithExpiredPartitions(int partitionCount) {
        CatalogRecycleBin recycleBin = new CatalogRecycleBin();
        long dbId = 1L;
        long tableId = 2L;
        DataProperty dataProperty = new DataProperty(TStorageMedium.HDD);
        long recycleTime = System.currentTimeMillis() - 10_000L;
        for (int i = 0; i < partitionCount; i++) {
            long partitionId = 10_000L + i;
            Partition partition = new Partition(partitionId, partitionId + 1, "p" + i, new MaterializedIndex(), null);
            RecyclePartitionInfo partitionInfo =
                    new BlockingRecyclePartitionInfo(dbId, tableId, partition, dataProperty, (short) 1, deleteLatch);
            recycleBin.recyclePartition(partitionInfo);
            recycleBin.idToRecycleTime.put(partitionId, recycleTime);
        }
        return recycleBin;
    }

    private static class BlockingRecyclePartitionInfo extends RecyclePartitionInfo {
        private final CountDownLatch deleteLatch;

        private BlockingRecyclePartitionInfo(long dbId, long tableId, Partition partition, DataProperty dataProperty,
                                             short replicationNum, CountDownLatch deleteLatch) {
            super(dbId, tableId, partition, dataProperty, replicationNum);
            this.deleteLatch = deleteLatch;
        }

        @Override
        public boolean delete() {
            try {
                deleteLatch.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            return true;
        }

        @Override
        Range<PartitionKey> getRange() {
            return null;
        }

        @Override
        DataCacheInfo getDataCacheInfo() {
            return null;
        }

        @Override
        void checkRecoverable(OlapTable table) throws DdlException {
        }

        @Override
        void recover(OlapTable table) {
        }
    }
}
