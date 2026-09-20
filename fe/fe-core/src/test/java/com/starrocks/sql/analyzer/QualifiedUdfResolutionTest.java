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

import com.starrocks.catalog.Database;
import com.starrocks.catalog.Function;
import com.starrocks.catalog.FunctionName;
import com.starrocks.catalog.ScalarFunction;
import com.starrocks.common.Config;
import com.starrocks.qe.ConnectContext;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.ast.QueryRelation;
import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.sql.ast.SelectRelation;
import com.starrocks.sql.ast.expression.Expr;
import com.starrocks.sql.ast.expression.FunctionCallExpr;
import com.starrocks.thrift.TFunctionBinaryType;
import com.starrocks.type.IntegerType;
import com.starrocks.type.Type;
import com.starrocks.type.VarcharType;
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
                new Type[] {VarcharType.VARCHAR, IntegerType.INT},
                IntegerType.INT,
                false,
                TFunctionBinaryType.SRJAR,
                "file:///tmp/oppo-hive-udf-1.0.0.jar",
                "com.oppo.starrocks.udfs.dcfunctions.DateAddStringInt",
                "",
                "",
                null);
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
        Assert.assertFalse(expr.getType().isInt());
        Assert.assertTrue(expr.getType().isDatetime() || expr.getType().isDate());
    }

    @Test
    public void testTrinoDialectQualifiedNamePrefersUdf() {
        ConnectContext context = AnalyzeTestUtil.getConnectContext();
        String originalDialect = context.getSessionVariable().getSqlDialect();
        try {
            context.getSessionVariable().setSqlDialect("trino");
            QueryStatement stmt = (QueryStatement) AnalyzeTestUtil.analyzeSuccess(
                    "select dc_udf.date_add('20240101', 1)");
            FunctionCallExpr call = extractFirstFunctionCall(stmt);
            Assert.assertEquals("dc_udf", call.getDbName());
            Assert.assertEquals("date_add", call.getFunctionName());
            Assert.assertEquals(TFunctionBinaryType.SRJAR, call.getFn().getBinaryType());
            Assert.assertTrue(call.getType().isInt());
        } finally {
            context.getSessionVariable().setSqlDialect(originalDialect);
        }
    }

    private static FunctionCallExpr extractFirstFunctionCall(QueryStatement stmt) {
        QueryRelation relation = stmt.getQueryRelation();
        Assert.assertTrue(relation instanceof SelectRelation);
        Expr expr = ((SelectRelation) relation).getOutputExpression().get(0);
        Assert.assertTrue(expr instanceof FunctionCallExpr);
        return (FunctionCallExpr) expr;
    }
}
