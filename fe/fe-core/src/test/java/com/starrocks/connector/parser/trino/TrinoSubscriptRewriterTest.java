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

package com.starrocks.connector.parser.trino;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class TrinoSubscriptRewriterTest extends TrinoTestBase {
    private boolean originZeroBasedSubscript;

    @BeforeClass
    public static void beforeClass() throws Exception {
        TrinoTestBase.beforeClass();
    }

    @Before
    public void setUp() {
        originZeroBasedSubscript = connectContext.getSessionVariable().isTrinoZeroBasedSubscript();
    }

    @After
    public void tearDown() {
        connectContext.getSessionVariable().setTrinoZeroBasedSubscript(originZeroBasedSubscript);
    }

    @Test
    public void testSplitSubscriptUsesOneBasedIndexByDefault() throws Exception {
        connectContext.getSessionVariable().setTrinoZeroBasedSubscript(false);
        String sql = "select split('a-b-c', '-')[1]";
        assertPlanContains(sql, "split('a-b-c', '-')[1]");
    }

    @Test
    public void testSplitSubscriptRewritesWithZeroBasedFlag() throws Exception {
        connectContext.getSessionVariable().setTrinoZeroBasedSubscript(true);
        String sql = "select split('a-b-c', '-')[0]";
        assertPlanContains(sql, "split('a-b-c', '-')[1]");

        sql = "select split('a-b-c', '-')[1]";
        assertPlanContains(sql, "split('a-b-c', '-')[2]");

        sql = "select split(ta, '-')[34] from tall";
        assertPlanContains(sql, "split(1: ta, '-')[35]");
    }

    @Test
    public void testNoRewriteForNonSplitArraySubscript() throws Exception {
        connectContext.getSessionVariable().setTrinoZeroBasedSubscript(true);
        String sql = "select c1[1] from test_array";
        assertPlanContains(sql, "2: c1[1]");
    }

    @Test
    public void testNoRewriteWithoutTrinoDialect() throws Exception {
        String originDialect = connectContext.getSessionVariable().getSqlDialect();
        try {
            connectContext.getSessionVariable().setSqlDialect("starrocks");
            connectContext.getSessionVariable().setTrinoZeroBasedSubscript(true);
            String sql = "select split('a-b-c', '-')[0]";
            assertPlanContains(sql, "split('a-b-c', '-')[0]");
        } finally {
            connectContext.getSessionVariable().setSqlDialect(originDialect);
        }
    }
}
