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

import com.google.common.collect.Lists;
import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.ast.expression.CastExpr;
import com.starrocks.sql.ast.expression.Expr;
import com.starrocks.sql.ast.expression.FunctionCallExpr;
import com.starrocks.type.Type;

public class TrinoCastRewriter {
    private TrinoCastRewriter() {
    }

    public static Expr rewriteCastToJson(Expr expr, ConnectContext session) {
        if (expr == null || session == null) {
            return expr;
        }
        if (!"trino".equalsIgnoreCase(session.getSessionVariable().getSqlDialect())) {
            return expr;
        }
        if (!(expr instanceof CastExpr)) {
            return expr;
        }
        CastExpr cast = (CastExpr) expr;
        if (cast.isImplicit() || cast.getTargetTypeDef() == null) {
            return expr;
        }
        Type castType = cast.getTargetTypeDef().getType();
        if (!castType.isJsonType()) {
            return expr;
        }
        Type fromType = cast.getChild(0).getType();
        if (fromType == null || fromType.isInvalid()) {
            return expr;
        }
        if (!fromType.isMapType() && !fromType.isStructType()) {
            return expr;
        }
        return new FunctionCallExpr("to_json", Lists.newArrayList(cast.getChild(0)), cast.getPos());
    }
}
