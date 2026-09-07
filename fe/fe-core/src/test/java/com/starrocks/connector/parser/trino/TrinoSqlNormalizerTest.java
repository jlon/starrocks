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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Locale;

public class TrinoSqlNormalizerTest {
    @Test
    public void testConvertBacktickQuotedIdentifiers() {
        Assertions.assertEquals("select nvl(\"ota_version\", 'ALL')",
                TrinoSqlNormalizer.convertBacktickQuotedIdentifiers(
                        "select nvl(`ota_version`, 'ALL')"));

        Assertions.assertEquals("select \"a\".\"b\" from \"db\".\"tbl\"",
                TrinoSqlNormalizer.convertBacktickQuotedIdentifiers(
                        "select `a`.`b` from `db`.`tbl`"));

        Assertions.assertEquals("select 'a`b', \"col\"",
                TrinoSqlNormalizer.convertBacktickQuotedIdentifiers(
                        "select 'a`b', `col`"));

        Assertions.assertEquals("select \"id\"\"x\" from t",
                TrinoSqlNormalizer.convertBacktickQuotedIdentifiers(
                        "select `id\"x` from t"));

        Assertions.assertEquals("select 1 -- `comment`",
                TrinoSqlNormalizer.convertBacktickQuotedIdentifiers(
                        "select 1 -- `comment`"));

        Assertions.assertEquals("select 1 /* `comment` */",
                TrinoSqlNormalizer.convertBacktickQuotedIdentifiers(
                        "select 1 /* `comment` */"));
    }

    @Test
    public void testRewriteRlike() {
        Assertions.assertEquals("select regexp_like(labid, 'STD|SAD')",
                TrinoSqlNormalizer.rewriteRlike("select labid RLIKE 'STD|SAD'"));

        Assertions.assertEquals("select NOT regexp_like(labid, 'STD')",
                TrinoSqlNormalizer.rewriteRlike("select labid NOT RLIKE 'STD'"));

        Assertions.assertEquals("select regexp_like(a, b)",
                TrinoSqlNormalizer.rewriteRlike("select RLIKE(a, b)"));

        Assertions.assertEquals(
                "CASE WHEN regexp_like(labid, 'STD|STDMIXT') THEN 'toutiao' END",
                TrinoSqlNormalizer.rewriteRlike(
                        "CASE WHEN labid RLIKE 'STD|STDMIXT' THEN 'toutiao' END"));

        // Do not rewrite inside string literals / comments
        Assertions.assertEquals("select 'labid RLIKE x'",
                TrinoSqlNormalizer.rewriteRlike("select 'labid RLIKE x'"));
        Assertions.assertEquals("select 1 -- labid RLIKE x\n",
                TrinoSqlNormalizer.rewriteRlike("select 1 -- labid RLIKE x\n"));
    }

    @Test
    public void testRewriteArrayConstructor() {
        Assertions.assertEquals("select ARRAY['42260']",
                TrinoSqlNormalizer.rewriteArrayConstructor("select ARRAY('42260')"));

        Assertions.assertEquals("select array[1, 2, 3]",
                TrinoSqlNormalizer.rewriteArrayConstructor("select array(1, 2, 3)"));

        Assertions.assertEquals("select ARRAY[]",
                TrinoSqlNormalizer.rewriteArrayConstructor("select ARRAY()"));

        // Keep type specs
        Assertions.assertEquals("select cast(x as ARRAY(INTEGER))",
                TrinoSqlNormalizer.rewriteArrayConstructor("select cast(x as ARRAY(INTEGER))"));
        Assertions.assertEquals("select cast(x as array(varchar(10)))",
                TrinoSqlNormalizer.rewriteArrayConstructor("select cast(x as array(varchar(10)))"));

        // Do not touch array_intersect / already-bracket constructors
        Assertions.assertEquals("select array_intersect(a, ARRAY['42260'])",
                TrinoSqlNormalizer.rewriteArrayConstructor(
                        "select array_intersect(a, ARRAY('42260'))"));
        Assertions.assertEquals("select ARRAY[1,2]",
                TrinoSqlNormalizer.rewriteArrayConstructor("select ARRAY[1,2]"));

        Assertions.assertEquals("select 'ARRAY(1)'",
                TrinoSqlNormalizer.rewriteArrayConstructor("select 'ARRAY(1)'"));
    }

    @Test
    public void testNormalizeUserSqlFragments() {
        String sql = "CASE\n"
                + "        WHEN labid RLIKE 'STD|STDMIXT' THEN 'toutiao'\n"
                + "        WHEN ARRAY_INTERSECT(SPLIT(exp_group_ids, '_'), ARRAY('42260')) [ 0 ] "
                + "IS NOT NULL THEN '42260-guanxing'\n"
                + "    END";
        String normalized = TrinoSqlNormalizer.normalize(sql);
        Assertions.assertTrue(normalized.contains("regexp_like(labid, 'STD|STDMIXT')"));
        Assertions.assertTrue(normalized.contains("ARRAY['42260']"));
        Assertions.assertFalse(normalized.contains("RLIKE"));
        Assertions.assertFalse(normalized.contains("ARRAY('42260')"));
    }

    @Test
    public void testRewriteLateralViewExplode() {
        Assertions.assertEquals(
                "SELECT recall_name, COUNT(*) AS pv\n"
                        + "FROM t\n"
                        + "CROSS JOIN UNNEST(recall_names) AS t(recall_name)\n"
                        + "GROUP BY 1",
                TrinoSqlNormalizer.rewriteLateralViewExplode(
                        "SELECT recall_name, COUNT(*) AS pv\n"
                                + "FROM t\n"
                                + "LATERAL VIEW explode(recall_names) t AS recall_name\n"
                                + "GROUP BY 1"));

        Assertions.assertEquals(
                "select * from db.tbl cross join unnest(arr) as u(col)",
                TrinoSqlNormalizer.rewriteLateralViewExplode(
                        "select * from db.tbl lateral view explode(arr) u as col"));

        Assertions.assertEquals(
                "select * from db.tbl cross join unnest(split(a, ',')) as t(x)",
                TrinoSqlNormalizer.rewriteLateralViewExplode(
                        "select * from db.tbl lateral view explode(split(a, ',')) t as x"));

        // Do not rewrite map explode (multi-column)
        Assertions.assertEquals(
                "select * from t lateral view explode(m) t as k, v",
                TrinoSqlNormalizer.rewriteLateralViewExplode(
                        "select * from t lateral view explode(m) t as k, v"));

        // Do not rewrite OUTER explode in this phase
        Assertions.assertEquals(
                "select * from t lateral view outer explode(arr) t as col",
                TrinoSqlNormalizer.rewriteLateralViewExplode(
                        "select * from t lateral view outer explode(arr) t as col"));

        // Do not rewrite inside string literals / comments
        Assertions.assertEquals("select 'lateral view explode(x) t as y'",
                TrinoSqlNormalizer.rewriteLateralViewExplode("select 'lateral view explode(x) t as y'"));
        Assertions.assertEquals("select 1 -- lateral view explode(x) t as y\n",
                TrinoSqlNormalizer.rewriteLateralViewExplode("select 1 -- lateral view explode(x) t as y\n"));
    }

    @Test
    public void testNormalizeLateralViewExplode() {
        String sql = "WITH t AS (SELECT recall_names FROM db.tbl)\n"
                + "SELECT recall_name FROM t\n"
                + "LATERAL VIEW explode(recall_names) t AS recall_name";
        String normalized = TrinoSqlNormalizer.normalize(sql);
        Assertions.assertTrue(normalized.contains("CROSS JOIN UNNEST(recall_names) AS t(recall_name)"));
        Assertions.assertFalse(normalized.toLowerCase(Locale.ROOT).contains("lateral view"));
    }
}
