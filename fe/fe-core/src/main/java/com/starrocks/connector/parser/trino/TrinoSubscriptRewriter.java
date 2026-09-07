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

import com.starrocks.catalog.FunctionSet;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.ast.expression.ArithmeticExpr;
import com.starrocks.sql.ast.expression.CollectionElementExpr;
import com.starrocks.sql.ast.expression.Expr;
import com.starrocks.sql.ast.expression.FunctionCallExpr;
import com.starrocks.sql.ast.expression.IntLiteral;
import com.starrocks.type.Type;

public class TrinoSubscriptRewriter {
    private TrinoSubscriptRewriter() {
    }

    public static Expr rewrite(Expr expr, ConnectContext session) {
        if (expr == null || session == null || !isZeroBasedSubscriptEnabled(session)) {
            return expr;
        }
        if (expr instanceof CollectionElementExpr) {
            return rewriteArraySubscript((CollectionElementExpr) expr);
        }
        return expr;
    }

    private static Expr toOneBasedIndex(Expr zeroBasedIndex) {
        if (zeroBasedIndex instanceof IntLiteral) {
            return new IntLiteral(((IntLiteral) zeroBasedIndex).getValue() + 1);
        }
        return new ArithmeticExpr(ArithmeticExpr.Operator.ADD, zeroBasedIndex, new IntLiteral(1));
    }

    private static boolean isZeroBasedSubscriptEnabled(ConnectContext session) {
        return "trino".equalsIgnoreCase(session.getSessionVariable().getSqlDialect())
                && session.getSessionVariable().isTrinoZeroBasedSubscript();
    }

    private static CollectionElementExpr rewriteArraySubscript(CollectionElementExpr node) {
        if (node.isZeroBasedSubscriptRewritten()) {
            return node;
        }
        Expr base = node.getChild(0);
        Type baseType = base.getType();
        if (baseType != null) {
            if (!baseType.isArrayType()) {
                return node;
            }
        } else if (!isSplitCall(base)) {
            return node;
        }
        CollectionElementExpr rewritten =
                new CollectionElementExpr(base, toOneBasedIndex(node.getChild(1)), node.isCheckIsOutOfBounds());
        rewritten.setZeroBasedSubscriptRewritten(true);
        return rewritten;
    }

    private static boolean isSplitCall(Expr expr) {
        if (!(expr instanceof FunctionCallExpr)) {
            return false;
        }
        FunctionCallExpr functionCall = (FunctionCallExpr) expr;
        return FunctionSet.SPLIT.equalsIgnoreCase(functionCall.getFnName().toString());
    }
}
