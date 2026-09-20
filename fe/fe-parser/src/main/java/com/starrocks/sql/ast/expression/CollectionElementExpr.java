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

package com.starrocks.sql.ast.expression;

import com.starrocks.sql.ast.AstVisitor;
import com.starrocks.sql.parser.NodePosition;
import com.starrocks.type.Type;

public class CollectionElementExpr extends Expr {

    // When true, missing map keys or out-of-range array indexes return an error at runtime.
    // Trino/Presto return NULL for these cases, so the Trino parser sets this to false.
    private final boolean checkIsOutOfBounds;

    // Set once trino_zero_based_subscript has shifted the index. Expressions may be analyzed more
    // than once (SelectAnalyzer re-analyzes aggregations), and the shift must not be applied twice.
    private boolean zeroBasedSubscriptRewritten;

    public CollectionElementExpr(Expr expr, Expr subscript, boolean checkIsOutOfBounds) {
        super(NodePosition.ZERO);
        this.children.add(expr);
        this.children.add(subscript);
        this.checkIsOutOfBounds = checkIsOutOfBounds;
    }

    public CollectionElementExpr(Type type, Expr expr, Expr subscript, boolean checkIsOutOfBounds) {
        this(type, expr, subscript, checkIsOutOfBounds, NodePosition.ZERO);
    }

    public CollectionElementExpr(Type type, Expr expr, Expr subscript, boolean checkIsOutOfBounds, NodePosition pos) {
        super(pos);
        this.type = type;
        this.children.add(expr);
        this.children.add(subscript);
        this.checkIsOutOfBounds = checkIsOutOfBounds;
    }

    public CollectionElementExpr(CollectionElementExpr other) {
        super(other);
        this.checkIsOutOfBounds = other.checkIsOutOfBounds;
        this.zeroBasedSubscriptRewritten = other.zeroBasedSubscriptRewritten;
    }


    @Override
    public Expr clone() {
        return new CollectionElementExpr(this);
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitCollectionElementExpr(this, context);
    }

    @Override
    public boolean isSelfMonotonic() {
        boolean ret = true;
        for (Expr child : children) {
            ret &= child.isSelfMonotonic();
        }
        return ret;
    }

    public boolean isCheckIsOutOfBounds() {
        return checkIsOutOfBounds;
    }

    public boolean isZeroBasedSubscriptRewritten() {
        return zeroBasedSubscriptRewritten;
    }

    public void setZeroBasedSubscriptRewritten(boolean zeroBasedSubscriptRewritten) {
        this.zeroBasedSubscriptRewritten = zeroBasedSubscriptRewritten;
    }
}
