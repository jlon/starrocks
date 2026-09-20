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
}
