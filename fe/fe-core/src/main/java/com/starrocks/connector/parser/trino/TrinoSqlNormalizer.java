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

public final class TrinoSqlNormalizer {
    private TrinoSqlNormalizer() {
    }

    /**
     * Convert MySQL/StarRocks backtick-quoted identifiers to Trino double-quoted identifiers.
     */
    public static String convertBacktickQuotedIdentifiers(String sql) {
        if (sql == null || sql.indexOf('`') < 0) {
            return sql;
        }
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            switch (c) {
                case '\'':
                    i = appendQuotedContent(sql, i, out, '\'');
                    break;
                case '"':
                    i = appendQuotedContent(sql, i, out, '"');
                    break;
                case '-':
                    if (i + 1 < n && sql.charAt(i + 1) == '-') {
                        i = appendLineComment(sql, i, out);
                    } else {
                        out.append(c);
                        i++;
                    }
                    break;
                case '/':
                    if (i + 1 < n && sql.charAt(i + 1) == '*') {
                        i = appendBlockComment(sql, i, out);
                    } else {
                        out.append(c);
                        i++;
                    }
                    break;
                case '`':
                    i = appendBacktickIdentifierAsDoubleQuoted(sql, i + 1, out);
                    break;
                default:
                    out.append(c);
                    i++;
                    break;
            }
        }
        return out.toString();
    }

    private static int appendQuotedContent(String sql, int start, StringBuilder out, char quote) {
        out.append(quote);
        int i = start + 1;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            out.append(c);
            i++;
            if (c == quote) {
                if (i < n && sql.charAt(i) == quote) {
                    out.append(sql.charAt(i));
                    i++;
                } else {
                    break;
                }
            } else if (c == '\\' && quote == '\'') {
                if (i < n) {
                    out.append(sql.charAt(i));
                    i++;
                }
            }
        }
        return i;
    }

    private static int appendBacktickIdentifierAsDoubleQuoted(String sql, int start, StringBuilder out) {
        out.append('"');
        int i = start;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '`') {
                if (i + 1 < n && sql.charAt(i + 1) == '`') {
                    out.append('`');
                    i += 2;
                } else {
                    out.append('"');
                    return i + 1;
                }
            } else if (c == '"') {
                out.append("\"\"");
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        out.append('"');
        return i;
    }

    private static int appendLineComment(String sql, int start, StringBuilder out) {
        int i = start;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            out.append(c);
            i++;
            if (c == '\n') {
                break;
            }
        }
        return i;
    }

    private static int appendBlockComment(String sql, int start, StringBuilder out) {
        int i = start;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            out.append(c);
            i++;
            if (c == '*' && i < n && sql.charAt(i) == '/') {
                out.append('/');
                return i + 1;
            }
        }
        return i;
    }
}
