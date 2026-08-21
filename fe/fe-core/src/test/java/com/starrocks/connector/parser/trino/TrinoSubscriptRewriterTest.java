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
import org.junit.Assert;
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
    public void testArrayColumnSubscriptRewritesWithZeroBasedFlag() throws Exception {
        connectContext.getSessionVariable().setTrinoZeroBasedSubscript(true);
        String sql = "select c1[0] from test_array";
        assertPlanContains(sql, "2: c1[1]");

        sql = "select c1[1] from test_array";
        assertPlanContains(sql, "2: c1[2]");
    }

    @Test
    public void testNestedArraySubscriptRewritesWithZeroBasedFlag() throws Exception {
        connectContext.getSessionVariable().setTrinoZeroBasedSubscript(true);
        String sql = "select split(ta, ',')[32] from tall";
        assertPlanContains(sql, "split(1: ta, ',')[33]");

        sql = "select * from (select split(ta, ',') as arr from tall) t where arr[32] = 'x'";
        analyzeSuccess(sql);
    }

    @Test
    public void testNoRewriteForMapSubscript() throws Exception {
        connectContext.getSessionVariable().setTrinoZeroBasedSubscript(true);
        String sql = "select c1[1] from test_map";
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

    @Test
    public void testGroupBySplitSubscriptWithZeroBasedFlag() throws Exception {
        connectContext.getSessionVariable().setTrinoZeroBasedSubscript(true);
        // SELECT and GROUP BY both use zero-based index; rewrite must stay consistent.
        String sql = "select split(ta, '-')[1], count(*) from tall group by split(ta, '-')[1]";
        assertPlanContains(sql, "split(1: ta, '-')[2]");
        analyzeSuccess(sql);
    }

    @Test
    public void testAggregateArgSubscriptShiftedOnlyOnce() throws Exception {
        connectContext.getSessionVariable().setTrinoZeroBasedSubscript(true);
        // SelectAnalyzer analyzes aggregations a second time; the shift must stay idempotent.
        String sql = "select sum(cast(split(ta, '-')[7] as double)) from tall";
        assertSubscriptShiftedOnlyOnce(sql);

        // The same subscript used bare and inside an aggregate must resolve to the same index.
        sql = "select split(ta, '-')[7], sum(cast(split(ta, '-')[7] as double)) from tall group by 1";
        assertSubscriptShiftedOnlyOnce(sql);
    }

    @Test
    public void testMultipleAggregatesShareSameSubscript() throws Exception {
        connectContext.getSessionVariable().setTrinoZeroBasedSubscript(true);
        String sql = "select sum(cast(split(ta, '-')[7] as double)), max(split(ta, '-')[7]), "
                + "min(split(ta, '-')[7]) from tall";
        assertSubscriptShiftedOnlyOnce(sql);
        analyzeSuccess(sql);
    }

    private void assertSubscriptShiftedOnlyOnce(String sql) throws Exception {
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan, plan.contains("split(1: ta, '-')[8]") || plan.contains("split[8]"));
        Assert.assertFalse(plan.contains("split[9]"));
    }

    @Test
    public void testArrayColumnInAggregateShiftedOnlyOnce() throws Exception {
        connectContext.getSessionVariable().setTrinoZeroBasedSubscript(true);
        String sql = "select max(c1[3]) from test_array";
        assertPlanContains(sql, "2: c1[4]");
        analyzeSuccess(sql);
    }

    @Test
    public void testVariableSubscriptInWhereAndAggregate() throws Exception {
        connectContext.getSessionVariable().setTrinoZeroBasedSubscript(true);
        // Non-literal index must still type-check after the +1 rewrite.
        String sql = "select count(*) from tall where split(ta, '-')[td] is not null";
        analyzeSuccess(sql);

        sql = "select sum(cast(split(ta, '-')[td] as double)) from tall";
        analyzeSuccess(sql);
    }

    @Test
    public void testGroupByOrderByOrdinalWithAggregateSubscript() throws Exception {
        connectContext.getSessionVariable().setTrinoZeroBasedSubscript(true);
        String sql = "select tb, cast(sum(coalesce(split(ta, '-')[7], 0)) as double) "
                + "from tall group by 1 order by 2 desc";
        assertPlanContains(sql, "order by:");
        assertPlanContains(sql, "DESC");
    }

    @Test
    public void testGroupByOrderByOrdinalWithMapAggregate() throws Exception {
        connectContext.getSessionVariable().setTrinoZeroBasedSubscript(true);
        String sql = "select c0, cast(sum(coalesce(c1[150], 0)) as double) "
                + "from test_map group by 1 order by 2 desc";
        assertPlanContains(sql, "order by:");
        assertPlanContains(sql, "DESC");
    }

    @Test
    public void testHavingAndOrderByAggregateSubscript() throws Exception {
        connectContext.getSessionVariable().setTrinoZeroBasedSubscript(true);
        String sql = "select tb from tall group by tb "
                + "having sum(cast(split(ta, '-')[7] as double)) > 0 "
                + "order by sum(cast(split(ta, '-')[7] as double))";
        assertPlanContains(sql, "split(1: ta, '-')[8]");
        analyzeSuccess(sql);
    }

    @Test
    public void testCteAggregateSubscriptShiftedOnlyOnce() throws Exception {
        connectContext.getSessionVariable().setTrinoZeroBasedSubscript(true);
        String sql = "with c as (select sum(cast(split(ta, '-')[7] as double)) s from tall) select s from c";
        assertPlanContains(sql, "split(1: ta, '-')[8]");
        analyzeSuccess(sql);
    }

    @Test
    public void testMapSubscriptUnchangedInAggregate() throws Exception {
        connectContext.getSessionVariable().setTrinoZeroBasedSubscript(true);
        String sql = "select max(c1[1]) from test_map";
        assertPlanContains(sql, "2: c1[1]");
        analyzeSuccess(sql);
    }

    @Test
    public void testOrderByBareSubscriptUsesRewrittenExpr() throws Exception {
        connectContext.getSessionVariable().setTrinoZeroBasedSubscript(true);
        // ORDER BY root CollectionElementExpr must keep the rewritten node, otherwise type is invalid.
        String sql = "select split(ta, '-')[7] from tall order by split(ta, '-')[7]";
        assertPlanContains(sql, "split(1: ta, '-')[8]");
        analyzeSuccess(sql);

        sql = "select distinct split(ta, '-')[7] from tall order by split(ta, '-')[7]";
        assertPlanContains(sql, "split(1: ta, '-')[8]");
        analyzeSuccess(sql);
    }

    @Test
    public void testGroupBySplitSubscriptWithoutZeroBasedFlag() throws Exception {
        connectContext.getSessionVariable().setTrinoZeroBasedSubscript(false);
        // Original one-based flow unchanged when flag is off.
        String sql = "select split(ta, '-')[1], count(*) from tall group by split(ta, '-')[1]";
        assertPlanContains(sql, "split(1: ta, '-')[1]");
        analyzeSuccess(sql);
    }
}
