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

import java.util.Locale;
import java.util.Set;

/**
 * Normalize Hive/Spark/MySQL flavored SQL fragments into Trino-compatible SQL
 * before handing them to the Trino parser.
 */
public final class TrinoSqlNormalizer {
    private static final Set<String> ARRAY_TYPE_NAMES = Set.of(
            "boolean", "tinyint", "smallint", "int", "integer", "bigint", "largeint",
            "float", "double", "real", "decimal", "numeric", "char", "varchar", "string",
            "binary", "varbinary", "date", "datetime", "timestamp", "time", "json",
            "array", "map", "struct", "row", "hll", "bitmap", "percentile", "uuid", "ip"
    );

    private static final Set<String> EXPR_BOUNDARY_KEYWORDS = Set.of(
            "and", "or", "xor", "when", "then", "else", "end", "where", "having", "on",
            "from", "join", "left", "right", "full", "inner", "outer", "cross", "lateral",
            "limit", "group", "order", "union", "intersect", "except", "select", "with",
            "as", "case", "between", "in", "is", "not", "like", "ilike", "rlike", "regexp",
            "by", "asc", "desc", "nulls", "first", "last", "returning", "set", "into",
            "values", "over", "partition", "window", "qualify", "using", "natural"
    );

    private TrinoSqlNormalizer() {
    }

    /**
     * Apply all Trino dialect normalizations.
     */
    public static String normalize(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        String normalized = convertBacktickQuotedIdentifiers(sql);
        normalized = rewriteRlike(normalized);
        normalized = rewriteArrayConstructor(normalized);
        return normalized;
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

    /**
     * Rewrite Hive/Spark {@code expr RLIKE pattern} / {@code RLIKE(expr, pattern)}
     * into Trino {@code regexp_like(expr, pattern)}.
     */
    public static String rewriteRlike(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        String lower = sql.toLowerCase(Locale.ROOT);
        if (!lower.contains("rlike")) {
            return sql;
        }

        StringBuilder out = new StringBuilder(sql.length() + 32);
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
                default:
                    if (isIdentStart(c) && isKeywordAt(sql, i, "rlike")) {
                        i = rewriteOneRlike(sql, i, out);
                    } else {
                        out.append(c);
                        i++;
                    }
                    break;
            }
        }
        return out.toString();
    }

    /**
     * Rewrite Hive/Spark {@code ARRAY(...)} value constructors into Trino {@code ARRAY[...]}.
     * Type specs such as {@code CAST(x AS ARRAY(INTEGER))} are preserved.
     */
    public static String rewriteArrayConstructor(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        String lower = sql.toLowerCase(Locale.ROOT);
        if (!lower.contains("array")) {
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
                default:
                    if (isIdentStart(c) && isKeywordAt(sql, i, "array")) {
                        i = rewriteOneArray(sql, i, out);
                    } else {
                        out.append(c);
                        i++;
                    }
                    break;
            }
        }
        return out.toString();
    }

    private static int rewriteOneRlike(String sql, int rlikeStart, StringBuilder out) {
        int n = sql.length();
        int afterRlike = rlikeStart + 5;
        int j = skipWhitespace(sql, afterRlike);

        // Function form: RLIKE(expr, pattern)
        if (j < n && sql.charAt(j) == '(') {
            out.append("regexp_like");
            return afterRlike;
        }

        // Infix form: expr [NOT] RLIKE pattern
        boolean negated = false;
        int notStartInOut = findTrailingNotKeyword(out);
        if (notStartInOut >= 0) {
            negated = true;
            out.setLength(notStartInOut);
            trimTrailingWhitespace(out);
        }

        int leftStartInOut = findLeftExprStart(out);
        String left = out.substring(leftStartInOut).trim();
        if (left.isEmpty()) {
            out.append(sql, rlikeStart, afterRlike);
            return afterRlike;
        }
        out.setLength(leftStartInOut);
        trimTrailingWhitespace(out);

        int rightStart = skipWhitespace(sql, afterRlike);
        int rightEnd = scanValueExpressionEnd(sql, rightStart);
        if (rightEnd <= rightStart) {
            out.append(left);
            if (negated) {
                out.append(" NOT");
            }
            out.append(" RLIKE");
            return afterRlike;
        }
        String right = sql.substring(rightStart, rightEnd).trim();

        if (out.length() > 0 && needsSpaceBeforeExpr(out)) {
            out.append(' ');
        }
        if (negated) {
            out.append("NOT ");
        }
        out.append("regexp_like(").append(left).append(", ").append(right).append(')');
        return rightEnd;
    }

    private static int rewriteOneArray(String sql, int arrayStart, StringBuilder out) {
        int afterArray = arrayStart + 5;
        int j = skipWhitespace(sql, afterArray);
        if (j >= sql.length() || sql.charAt(j) != '(') {
            out.append(sql, arrayStart, afterArray);
            return afterArray;
        }

        int close = findMatchingParen(sql, j);
        if (close < 0) {
            out.append(sql, arrayStart, afterArray);
            return afterArray;
        }

        String content = sql.substring(j + 1, close);
        if (isArrayTypeSpec(content)) {
            out.append(sql, arrayStart, close + 1);
            return close + 1;
        }

        // Preserve original ARRAY keyword casing style loosely as ARRAY[...]
        out.append(sql, arrayStart, afterArray);
        out.append('[').append(content).append(']');
        return close + 1;
    }

    private static boolean isArrayTypeSpec(String content) {
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            // ARRAY() is an empty constructor in Hive.
            return false;
        }
        if (startsWithStringOrNumberLiteral(trimmed)) {
            return false;
        }
        // Multi-arg value constructors: ARRAY(a, b)
        if (hasTopLevelComma(trimmed)) {
            // ROW(a type, b type) also has commas — treat as type only if it starts with row/struct/map
            // and every segment looks type-like. For safety, if first token is row/struct/map keep;
            // otherwise treat as constructor.
            String first = firstIdent(trimmed);
            return first != null && (first.equals("row") || first.equals("struct") || first.equals("map"));
        }

        String first = firstIdent(trimmed);
        if (first == null) {
            return false;
        }
        return ARRAY_TYPE_NAMES.contains(first);
    }

    private static boolean startsWithStringOrNumberLiteral(String s) {
        int start = skipWhitespace(s, 0);
        if (start == s.length()) {
            return false;
        }
        char c = s.charAt(start);
        if (c == '\'' || c == '"') {
            return true;
        }
        if (c == '-' || c == '+') {
            int numberStart = skipWhitespace(s, start + 1);
            return numberStart < s.length() && Character.isDigit(s.charAt(numberStart));
        }
        return Character.isDigit(c);
    }

    private static boolean hasTopLevelComma(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(s, i, c) - 1;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                return true;
            }
        }
        return false;
    }

    private static String firstIdent(String s) {
        int i = skipWhitespace(s, 0);
        if (i >= s.length() || !isIdentStart(s.charAt(i))) {
            return null;
        }
        int start = i;
        i++;
        while (i < s.length() && isIdentPart(s.charAt(i))) {
            i++;
        }
        return s.substring(start, i).toLowerCase(Locale.ROOT);
    }

    private static int findTrailingNotKeyword(StringBuilder out) {
        int i = out.length();
        while (i > 0 && Character.isWhitespace(out.charAt(i - 1))) {
            i--;
        }
        if (i < 3) {
            return -1;
        }
        int start = i - 3;
        if (!out.substring(start, i).equalsIgnoreCase("not")) {
            return -1;
        }
        if (start > 0 && isIdentPart(out.charAt(start - 1))) {
            return -1;
        }
        return start;
    }

    private static int findLeftExprStart(StringBuilder out) {
        int i = out.length();
        while (i > 0 && Character.isWhitespace(out.charAt(i - 1))) {
            i--;
        }
        int depth = 0;
        while (i > 0) {
            char c = out.charAt(i - 1);
            if (c == '\'' || c == '"') {
                i = skipQuotedBackward(out, i - 1, c);
                continue;
            }
            if (c == ')') {
                depth++;
                i--;
                continue;
            }
            if (c == '(') {
                if (depth == 0) {
                    return i;
                }
                depth--;
                i--;
                continue;
            }
            if (c == ']' || c == '}') {
                depth++;
                i--;
                continue;
            }
            if (c == '[' || c == '{') {
                if (depth == 0) {
                    return i;
                }
                depth--;
                i--;
                continue;
            }
            if (depth == 0) {
                if (c == ',' || c == '=' || c == '<' || c == '>' || c == '!' || c == '+'
                        || c == '*' || c == '/' || c == '%' || c == '|' || c == '&' || c == '^') {
                    // binary ops / separators: expression starts after them
                    // but '||' concat should be part of left expr. Handle below via keyword/op scan.
                    if (c == '|' && i >= 2 && out.charAt(i - 2) == '|') {
                        i -= 2;
                        continue;
                    }
                    if (c == '&' && i >= 2 && out.charAt(i - 2) == '&') {
                        i -= 2;
                        continue;
                    }
                    if (c == '=' || c == '<' || c == '>' || c == '!') {
                        return i;
                    }
                    if (c == ',') {
                        return i;
                    }
                    // +, *, /, % at depth 0 typically continue expr; keep scanning
                }
                if (Character.isWhitespace(c)) {
                    // Check whether previous token is a boundary keyword
                    int keyEnd = i - 1;
                    while (keyEnd > 0 && Character.isWhitespace(out.charAt(keyEnd - 1))) {
                        keyEnd--;
                    }
                    int keyStart = keyEnd;
                    while (keyStart > 0 && isIdentPart(out.charAt(keyStart - 1))) {
                        keyStart--;
                    }
                    if (keyStart < keyEnd) {
                        String word = out.substring(keyStart, keyEnd).toLowerCase(Locale.ROOT);
                        if (EXPR_BOUNDARY_KEYWORDS.contains(word)) {
                            return i;
                        }
                    }
                }
            }
            i--;
        }
        return 0;
    }

    private static int scanValueExpressionEnd(String sql, int start) {
        int n = sql.length();
        int i = skipWhitespace(sql, start);
        if (i >= n) {
            return start;
        }

        // Scan one value expression: primary with optional trailing [..] / .ident / (args)
        // Stop at boundary keywords or top-level separators.
        int depthParen = 0;
        int depthBracket = 0;
        boolean started = false;

        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(sql, i, c);
                started = true;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                break;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                break;
            }
            if (c == '(') {
                depthParen++;
                started = true;
                i++;
                continue;
            }
            if (c == ')') {
                if (depthParen == 0) {
                    break;
                }
                depthParen--;
                i++;
                continue;
            }
            if (c == '[') {
                depthBracket++;
                started = true;
                i++;
                continue;
            }
            if (c == ']') {
                if (depthBracket == 0) {
                    break;
                }
                depthBracket--;
                i++;
                continue;
            }
            if (depthParen == 0 && depthBracket == 0) {
                if (c == ',' || c == ';' || c == '}') {
                    break;
                }
                if (Character.isWhitespace(c)) {
                    int k = skipWhitespace(sql, i);
                    if (k < n && isIdentStart(sql.charAt(k))) {
                        int wordEnd = scanIdentEnd(sql, k);
                        String word = sql.substring(k, wordEnd).toLowerCase(Locale.ROOT);
                        if (EXPR_BOUNDARY_KEYWORDS.contains(word)) {
                            break;
                        }
                        // function call after space is uncommon for RLIKE RHS; treat as boundary
                        // unless it's a known continuation — keep simple: stop at keywords only
                    }
                    // allow whitespace inside already-started expression (e.g. "cast ( x as y )")
                    if (!started) {
                        i = k;
                        continue;
                    }
                    // peek if next is operator continuation
                    if (k < n) {
                        char nc = sql.charAt(k);
                        if (nc == '(' || nc == '[' || nc == '.' || nc == '|') {
                            i = k;
                            continue;
                        }
                        if (isIdentStart(nc) || Character.isDigit(nc) || nc == '\'' || nc == '"') {
                            // another primary without operator — stop
                            break;
                        }
                    }
                    i++;
                    continue;
                }
            }
            started = true;
            i++;
        }
        return i;
    }

    private static int skipQuoted(String sql, int start, char quote) {
        int i = start + 1;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            i++;
            if (c == quote) {
                if (i < n && sql.charAt(i) == quote) {
                    i++;
                } else {
                    break;
                }
            } else if (c == '\\' && quote == '\'' && i < n) {
                i++;
            }
        }
        return i;
    }

    private static int skipQuotedBackward(StringBuilder out, int quotePos, char quote) {
        // quotePos points at closing quote; walk left to opening quote
        int i = quotePos - 1;
        while (i >= 0) {
            char c = out.charAt(i);
            if (c == quote) {
                if (i > 0 && out.charAt(i - 1) == quote) {
                    i -= 2;
                    continue;
                }
                return i;
            }
            i--;
        }
        return 0;
    }

    private static int findMatchingParen(String sql, int openPos) {
        int depth = 0;
        int i = openPos;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(sql, i, c);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    private static boolean isKeywordAt(String sql, int start, String keyword) {
        int n = keyword.length();
        if (start + n > sql.length()) {
            return false;
        }
        if (start > 0 && isIdentPart(sql.charAt(start - 1))) {
            return false;
        }
        if (!sql.regionMatches(true, start, keyword, 0, n)) {
            return false;
        }
        if (start + n < sql.length() && isIdentPart(sql.charAt(start + n))) {
            return false;
        }
        return true;
    }

    private static int skipWhitespace(String sql, int i) {
        int n = sql.length();
        while (i < n && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int scanIdentEnd(String sql, int start) {
        int i = start + 1;
        int n = sql.length();
        while (i < n && isIdentPart(sql.charAt(i))) {
            i++;
        }
        return i;
    }

    private static void trimTrailingWhitespace(StringBuilder out) {
        int i = out.length();
        while (i > 0 && Character.isWhitespace(out.charAt(i - 1))) {
            i--;
        }
        out.setLength(i);
    }

    private static boolean needsSpaceBeforeExpr(StringBuilder out) {
        char c = out.charAt(out.length() - 1);
        return isIdentPart(c) || c == ')' || c == ']' || c == '\'' || c == '"';
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
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
