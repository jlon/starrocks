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

package com.starrocks.sql.analyzer;

import com.starrocks.analysis.Expr;
import com.starrocks.analysis.FunctionCallExpr;
import com.starrocks.analysis.FunctionName;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.Function;
import com.starrocks.catalog.ScalarFunction;
import com.starrocks.catalog.Type;
import com.starrocks.common.Config;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.QueryRelation;
import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.sql.ast.SelectRelation;
import com.starrocks.thrift.TFunctionBinaryType;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class QualifiedUdfResolutionTest {
    private static boolean originEnableUdf;

    @BeforeClass
    public static void beforeClass() throws Exception {
        AnalyzeTestUtil.init();
        originEnableUdf = Config.enable_udf;
        Config.enable_udf = true;

        AnalyzeTestUtil.getStarRocksAssert().withDatabase("dc_udf").useDatabase("test");
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb("dc_udf");
        Function udf = ScalarFunction.createUdf(
                new FunctionName("dc_udf", "date_add"),
                new Type[] {Type.VARCHAR, Type.INT},
                Type.INT,
                false,
                TFunctionBinaryType.SRJAR,
                "file:///tmp/oppo-hive-udf-1.0.0.jar",
                "com.oppo.starrocks.udfs.dcfunctions.DateAddStringInt",
                "",
                "",
                false);
        udf.setChecksum("dummy");
        db.addFunction(udf);
    }

    @AfterClass
    public static void afterClass() {
        Config.enable_udf = originEnableUdf;
    }

    @Test
    public void testUnqualifiedNameStillUsesBuiltin() {
        QueryStatement stmt = (QueryStatement) AnalyzeTestUtil.analyzeSuccess(
                "select date_add('2024-01-01', INTERVAL 1 DAY)");
        QueryRelation relation = stmt.getQueryRelation();
        Assert.assertTrue(relation instanceof SelectRelation);
        Expr expr = ((SelectRelation) relation).getOutputExpression().get(0);
        // Builtin date_add returns DATETIME/DATE; the dc_udf.date_add UDF returns INT.
        Assert.assertFalse(expr.getType().isInt());
        Assert.assertTrue(expr.getType().isDatetime() || expr.getType().isDate());
    }

    @Test
    public void testTrinoDialectQualifiedNamePrefersUdf() {
        // Default dialect rewrites date_add (even with db prefix) to TimestampArithmeticExpr.
        // Qualified UDF resolution is supported under Trino dialect.
        ConnectContext ctx = AnalyzeTestUtil.getConnectContext();
        String originDialect = ctx.getSessionVariable().getSqlDialect();
        try {
            ctx.getSessionVariable().setSqlDialect("trino");
            QueryStatement stmt = (QueryStatement) AnalyzeTestUtil.analyzeSuccess(
                    "select dc_udf.date_add('20240101', 1)");
            FunctionCallExpr call = extractFirstFunctionCall(stmt);
            Assert.assertEquals("dc_udf", call.getFnName().getDb());
            Assert.assertEquals("date_add", call.getFnName().getFunction());
            Assert.assertEquals(TFunctionBinaryType.SRJAR, call.getFn().getBinaryType());
            Assert.assertTrue(call.getType().isInt());
        } finally {
            ctx.getSessionVariable().setSqlDialect(originDialect);
        }
    }

    private static FunctionCallExpr extractFirstFunctionCall(QueryStatement stmt) {
        QueryRelation relation = stmt.getQueryRelation();
        Assert.assertTrue(relation instanceof SelectRelation);
        SelectRelation select = (SelectRelation) relation;
        Expr expr = select.getOutputExpression().get(0);
        Assert.assertTrue(expr instanceof FunctionCallExpr);
        return (FunctionCallExpr) expr;
    }
}
