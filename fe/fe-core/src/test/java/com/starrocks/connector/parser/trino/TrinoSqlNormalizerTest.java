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

import org.junit.Assert;
import org.junit.Test;

public class TrinoSqlNormalizerTest {
    @Test
    public void testConvertBacktickQuotedIdentifiers() {
        Assert.assertEquals("select nvl(\"ota_version\", 'ALL')",
                TrinoSqlNormalizer.convertBacktickQuotedIdentifiers(
                        "select nvl(`ota_version`, 'ALL')"));

        Assert.assertEquals("select \"a\".\"b\" from \"db\".\"tbl\"",
                TrinoSqlNormalizer.convertBacktickQuotedIdentifiers(
                        "select `a`.`b` from `db`.`tbl`"));

        Assert.assertEquals("select 'a`b', \"col\"",
                TrinoSqlNormalizer.convertBacktickQuotedIdentifiers(
                        "select 'a`b', `col`"));

        Assert.assertEquals("select \"id\"\"x\" from t",
                TrinoSqlNormalizer.convertBacktickQuotedIdentifiers(
                        "select `id\"x` from t"));

        Assert.assertEquals("select 1 -- `comment`",
                TrinoSqlNormalizer.convertBacktickQuotedIdentifiers(
                        "select 1 -- `comment`"));

        Assert.assertEquals("select 1 /* `comment` */",
                TrinoSqlNormalizer.convertBacktickQuotedIdentifiers(
                        "select 1 /* `comment` */"));
    }

    @Test
    public void testRewriteRlike() {
        Assert.assertEquals("select regexp_like(labid, 'STD|SAD')",
                TrinoSqlNormalizer.rewriteRlike("select labid RLIKE 'STD|SAD'"));

        Assert.assertEquals("select NOT regexp_like(labid, 'STD')",
                TrinoSqlNormalizer.rewriteRlike("select labid NOT RLIKE 'STD'"));

        Assert.assertEquals("select regexp_like(a, b)",
                TrinoSqlNormalizer.rewriteRlike("select RLIKE(a, b)"));

        Assert.assertEquals(
                "CASE WHEN regexp_like(labid, 'STD|STDMIXT') THEN 'toutiao' END",
                TrinoSqlNormalizer.rewriteRlike(
                        "CASE WHEN labid RLIKE 'STD|STDMIXT' THEN 'toutiao' END"));

        // Do not rewrite inside string literals / comments
        Assert.assertEquals("select 'labid RLIKE x'",
                TrinoSqlNormalizer.rewriteRlike("select 'labid RLIKE x'"));
        Assert.assertEquals("select 1 -- labid RLIKE x\n",
                TrinoSqlNormalizer.rewriteRlike("select 1 -- labid RLIKE x\n"));
    }

    @Test
    public void testRewriteArrayConstructor() {
        Assert.assertEquals("select ARRAY['42260']",
                TrinoSqlNormalizer.rewriteArrayConstructor("select ARRAY('42260')"));

        Assert.assertEquals("select array[1, 2, 3]",
                TrinoSqlNormalizer.rewriteArrayConstructor("select array(1, 2, 3)"));

        Assert.assertEquals("select ARRAY[]",
                TrinoSqlNormalizer.rewriteArrayConstructor("select ARRAY()"));

        // Keep type specs
        Assert.assertEquals("select cast(x as ARRAY(INTEGER))",
                TrinoSqlNormalizer.rewriteArrayConstructor("select cast(x as ARRAY(INTEGER))"));
        Assert.assertEquals("select cast(x as array(varchar(10)))",
                TrinoSqlNormalizer.rewriteArrayConstructor("select cast(x as array(varchar(10)))"));

        // Do not touch array_intersect / already-bracket constructors
        Assert.assertEquals("select array_intersect(a, ARRAY['42260'])",
                TrinoSqlNormalizer.rewriteArrayConstructor(
                        "select array_intersect(a, ARRAY('42260'))"));
        Assert.assertEquals("select ARRAY[1,2]",
                TrinoSqlNormalizer.rewriteArrayConstructor("select ARRAY[1,2]"));

        Assert.assertEquals("select 'ARRAY(1)'",
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
        Assert.assertTrue(normalized.contains("regexp_like(labid, 'STD|STDMIXT')"));
        Assert.assertTrue(normalized.contains("ARRAY['42260']"));
        Assert.assertFalse(normalized.contains("RLIKE"));
        Assert.assertFalse(normalized.contains("ARRAY('42260')"));
    }
}
