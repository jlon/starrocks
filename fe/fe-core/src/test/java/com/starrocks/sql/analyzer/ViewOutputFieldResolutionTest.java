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

import com.starrocks.sql.ast.QueryStatement;
import com.starrocks.sql.ast.StatementBase;
import com.starrocks.sql.ast.ViewRelation;
import com.starrocks.sql.ast.expression.SlotRef;
import com.starrocks.sql.plan.ConnectorPlanTestBase;
import com.starrocks.sql.plan.PlanTestBase;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

public class ViewOutputFieldResolutionTest extends PlanTestBase {
    @BeforeAll
    public static void beforeClass() throws Exception {
        PlanTestBase.beforeClass();
        ConnectorPlanTestBase.mockHiveCatalog(connectContext);
    }

    private static QueryStatement analyzeQuery(String sql) {
        try {
            StatementBase statement = com.starrocks.sql.parser.SqlParser.parse(sql, connectContext.getSessionVariable())
                    .get(0);
            Analyzer.analyze(statement, connectContext);
            return (QueryStatement) statement;
        } catch (Exception ex) {
            ex.printStackTrace();
            Assertions.fail(ex.getMessage());
            return null;
        }
    }

    @Test
    public void testNativeViewWithRenamedColumnsUsesPositionalFallback() throws Exception {
        starRocksAssert.withView("CREATE VIEW v_col_alias (col_a, col_b) AS SELECT v1, v2 FROM t0");
        QueryStatement stmt = analyzeQuery("SELECT * FROM v_col_alias");
        ViewRelation viewRelation = AnalyzerUtils.collectViewRelations(stmt).get(0);

        Field colA = viewRelation.getScope().getRelationFields().resolveFields(new SlotRef(null, "col_a")).get(0);
        Field colB = viewRelation.getScope().getRelationFields().resolveFields(new SlotRef(null, "col_b")).get(0);
        Assertions.assertEquals("v1", ((SlotRef) colA.getOriginExpression()).getColumnName());
        Assertions.assertEquals("v2", ((SlotRef) colB.getOriginExpression()).getColumnName());
    }

    @Test
    public void testTrinoViewWithReorderedMetadataColumns() throws Exception {
        QueryStatement stmt = analyzeQuery("SELECT * FROM hive0.tpch.trino_reordered_columns_view");
        List<ViewRelation> viewRelations = AnalyzerUtils.collectViewRelations(stmt);
        Assertions.assertEquals(1, viewRelations.size());

        ViewRelation viewRelation = viewRelations.get(0);
        Field custkeyField = viewRelation.getScope().getRelationFields()
                .resolveFields(new SlotRef(null, "c_custkey")).get(0);
        Field nameField = viewRelation.getScope().getRelationFields()
                .resolveFields(new SlotRef(null, "c_name")).get(0);
        Assertions.assertEquals("c_custkey", ((SlotRef) custkeyField.getOriginExpression()).getColumnName());
        Assertions.assertEquals("c_name", ((SlotRef) nameField.getOriginExpression()).getColumnName());
    }
}
