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

        Assertions.assertNotNull(deletedShards.get());
        Assertions.assertEquals(tabletIds, deletedShards.get());
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

        Assertions.assertNull(deletedShards.get());
    }

    @Test
    public void testCleanTabletIdSetForAllToleratesShardDeletionFailure(
            @Mocked GlobalStateMgr globalStateMgr) throws Exception {
        mockGlobalStateMgrWithStarOSAgent(
                globalStateMgr, new AtomicReference<>(), new DdlException("simulated StarMgr failure"));
        LocalMetastore localMetastore = new LocalMetastore(globalStateMgr, null, null);

        OlapTable cloudNativeTable = new FakeTable(true);

        Set<Long> tabletIds = new HashSet<>(java.util.Arrays.asList(301L));
        Assertions.assertDoesNotThrow(() ->
                Deencapsulation.invoke(localMetastore, "cleanTabletIdSetForAll", cloudNativeTable, tabletIds));
    }

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
