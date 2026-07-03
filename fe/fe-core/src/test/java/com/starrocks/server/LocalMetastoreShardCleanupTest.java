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

package com.starrocks.server;

import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.TabletInvertedIndex;
import com.starrocks.common.DdlException;
import com.starrocks.common.jmockit.Deencapsulation;
import com.starrocks.lake.StarOSAgent;
import mockit.Mock;
import mockit.MockUp;
import mockit.Mocked;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies the fix for the "ShardDeleter" ghost-promise defect in LocalMetastore.
 *
 * FACT established by source inspection (grep across the whole /mnt/data/starrocks/fe tree
 * found zero occurrences of a class named ShardDeleter): LocalMetastore#cleanTabletIdSetForAll()
 * and LocalMetastore#deleteUselessTablets() previously carried comments claiming
 * "Cleanup of shards for LakeTable is taken care by ShardDeleter" / "shards cleanup is taken
 * care in ShardDeleter", but their entire implementation was a single call to
 * TabletInvertedIndex#deleteTablets(), which only removes the FE-local in-memory tablet index
 * entry. No StarOSAgent#deleteShards() (or any other shard-deletion call) was ever made. Since
 * LakeTable/LakeMaterializedView tablet ids are StarOS shard ids (see LakeTablet's class
 * comment), and those shards are created eagerly in LocalMetastore#createLakeTablets() via
 * StarOSAgent#createShards() BEFORE any CreateReplicaTask is ever sent, a failure anywhere in
 * the subsequent tablet/replica-creation step (CN non-OK response, CN crash + FE-side timeout,
 * etc.) left those already-created shards — and their backing remote directories — completely
 * unreferenced by FE, with no proactive cleanup path. This test proves the fix: for a
 * CloudNativeTable, the rollback path now calls StarOSAgent#deleteShards() with exactly the
 * tablet ids being rolled back.
 */
public class LocalMetastoreShardCleanupTest {

    @Test
    public void testCleanTabletIdSetForAllDeletesShardsForCloudNativeTable(
            @Mocked GlobalStateMgr globalStateMgr) throws Exception {
        AtomicReference<Set<Long>> deletedShards = new AtomicReference<>();
        mockGlobalStateMgrWithStarOSAgent(globalStateMgr, deletedShards, null);
        LocalMetastore localMetastore = new LocalMetastore(globalStateMgr, null, null);

        OlapTable cloudNativeTable = new FakeTable(true);

        Set<Long> tabletIds = new HashSet<>(java.util.Arrays.asList(101L, 102L, 103L));
        Deencapsulation.invoke(localMetastore, "cleanTabletIdSetForAll", cloudNativeTable, tabletIds);

        Assertions.assertNotNull(deletedShards.get(),
                "FACT (fixed behavior): StarOSAgent#deleteShards() must be called when rolling "
                        + "back tablet creation for a CloudNativeTable, so the shards created "
                        + "eagerly in createLakeTablets() do not become permanently orphaned.");
        Assertions.assertEquals(tabletIds, deletedShards.get(),
                "FACT: the exact set of tablet ids being rolled back must be passed to "
                        + "deleteShards(), since for LakeTable tablet id == StarOS shard id.");
    }

    @Test
    public void testCleanTabletIdSetForAllSkipsShardDeletionForNonCloudNativeTable(
            @Mocked GlobalStateMgr globalStateMgr) throws Exception {
        AtomicReference<Set<Long>> deletedShards = new AtomicReference<>();
        mockGlobalStateMgrWithStarOSAgent(globalStateMgr, deletedShards, null);
        LocalMetastore localMetastore = new LocalMetastore(globalStateMgr, null, null);

        OlapTable localTable = new FakeTable(false);

        Set<Long> tabletIds = new HashSet<>(java.util.Arrays.asList(201L, 202L));
        Deencapsulation.invoke(localMetastore, "cleanTabletIdSetForAll", localTable, tabletIds);

        Assertions.assertNull(deletedShards.get(),
                "FACT: shared-nothing (non-cloud-native) tablet ids are not StarOS shard ids, "
                        + "so no deleteShards() call must be issued for them.");
    }

    @Test
    public void testCleanTabletIdSetForAllToleratesShardDeletionFailure(
            @Mocked GlobalStateMgr globalStateMgr) throws Exception {
        mockGlobalStateMgrWithStarOSAgent(
                globalStateMgr, new AtomicReference<>(), new DdlException("simulated StarMgr failure"));
        LocalMetastore localMetastore = new LocalMetastore(globalStateMgr, null, null);

        OlapTable cloudNativeTable = new FakeTable(true);

        Set<Long> tabletIds = new HashSet<>(java.util.Arrays.asList(301L));
        // Must not propagate the shard-deletion failure: the caller is already in a
        // DdlException rollback path and a secondary cleanup failure must not mask or replace
        // the original error, nor prevent the FE-local tablet index from being cleaned up.
        Assertions.assertDoesNotThrow(() ->
                Deencapsulation.invoke(localMetastore, "cleanTabletIdSetForAll", cloudNativeTable, tabletIds));
    }

    /** Named subclass to avoid JMockit cascading-mock interference with anonymous classes. */
    private static final class FakeTable extends OlapTable {
        private final boolean cloudNative;

        FakeTable(boolean cloudNative) {
            this.cloudNative = cloudNative;
        }

        @Override
        public boolean isCloudNativeTableOrMaterializedView() {
            return cloudNative;
        }
    }

    private void mockGlobalStateMgrWithStarOSAgent(
            GlobalStateMgr globalStateMgr, AtomicReference<Set<Long>> deletedShards, DdlException throwOnDelete) {
        StarOSAgent starOSAgent = new StarOSAgent();
        new MockUp<StarOSAgent>() {
            @Mock
            public void deleteShards(Set<Long> shardIds) throws DdlException {
                if (throwOnDelete != null) {
                    throw throwOnDelete;
                }
                deletedShards.set(shardIds);
            }
        };

        TabletInvertedIndex tabletInvertedIndex = new TabletInvertedIndex();
        new MockUp<GlobalStateMgr>() {
            @Mock
            public GlobalStateMgr getCurrentState() {
                return globalStateMgr;
            }

            @Mock
            public StarOSAgent getStarOSAgent() {
                return starOSAgent;
            }

            @Mock
            public TabletInvertedIndex getTabletInvertedIndex() {
                return tabletInvertedIndex;
            }
        };
    }
}
