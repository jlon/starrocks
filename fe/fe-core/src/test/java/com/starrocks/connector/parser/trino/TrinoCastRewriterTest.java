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

import org.junit.BeforeClass;
import org.junit.Test;

public class TrinoCastRewriterTest extends TrinoTestBase {
    @BeforeClass
    public static void beforeClass() throws Exception {
        TrinoTestBase.beforeClass();
    }

    @Test
    public void testRewriteMapCastToJson() {
        String sql = "SELECT CAST(MAP(ARRAY['k1'], ARRAY['v1']) AS JSON)";
        analyzeSuccess(sql);

        sql = "select cast(c3 as json) from test_map";
        analyzeSuccess(sql);
    }

    @Test
    public void testKeepVarcharCastToJson() {
        String sql = "SELECT CAST('{\"k\":\"v\"}' AS JSON)";
        analyzeSuccess(sql);
    }

    @Test
    public void testNoRewriteWithoutTrinoDialect() {
        String originDialect = connectContext.getSessionVariable().getSqlDialect();
        try {
            connectContext.getSessionVariable().setSqlDialect("starrocks");
            String sql = "SELECT CAST(MAP(ARRAY['k1'], ARRAY['v1']) AS JSON)";
            analyzeFail(sql, "Invalid type cast");
        } finally {
            connectContext.getSessionVariable().setSqlDialect(originDialect);
        }
    }

    @Test
    public void testRewritePlanUsesToJson() throws Exception {
        String sql = "select cast(c3 as json) from test_map";
        assertPlanContains(sql, "to_json");
    }
}
