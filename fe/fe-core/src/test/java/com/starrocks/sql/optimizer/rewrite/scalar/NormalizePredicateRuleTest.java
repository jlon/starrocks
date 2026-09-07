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

package com.starrocks.sql.optimizer.rewrite.scalar;

import com.google.common.collect.Lists;
import com.starrocks.catalog.Function;
import com.starrocks.catalog.FunctionName;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.sql.ast.expression.BinaryType;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.CompoundPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.InPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.rewrite.ScalarOperatorRewriteContext;
import com.starrocks.sql.optimizer.rewrite.ScalarOperatorRewriter;
import com.starrocks.type.IntegerType;
import com.starrocks.type.Type;
import com.starrocks.type.VarcharType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NormalizePredicateRuleTest {
    @Test
    public void testRule() {
        NormalizePredicateRule rule = new NormalizePredicateRule();
        ScalarOperatorRewriteContext context = new ScalarOperatorRewriteContext();

        BinaryPredicateOperator bpo = new BinaryPredicateOperator(BinaryType.EQ,
                new ColumnRefOperator(1, IntegerType.INT, "test", true),
                ConstantOperator.createInt(1));

        ScalarOperator result = rule.apply(bpo, context);

        assertEquals(bpo, result);
        assertEquals(OperatorType.VARIABLE, result.getChild(0).getOpType());
        assertEquals(OperatorType.CONSTANT, result.getChild(1).getOpType());
    }

    @Test
    public void testRule1() {
        NormalizePredicateRule rule = new NormalizePredicateRule();
        ScalarOperatorRewriteContext context = new ScalarOperatorRewriteContext();

        BinaryPredicateOperator bpo = new BinaryPredicateOperator(BinaryType.EQ,
                ConstantOperator.createInt(1),
                new ColumnRefOperator(1, IntegerType.INT, "test", true));

        ScalarOperator result = rule.apply(bpo, context);

        assertNotEquals(bpo, result);
        assertEquals(OperatorType.VARIABLE, result.getChild(0).getOpType());
        assertEquals(OperatorType.CONSTANT, result.getChild(1).getOpType());
    }

    @Test
    public void testRule2() {
        NormalizePredicateRule rule = new NormalizePredicateRule();
        ScalarOperatorRewriteContext context = new ScalarOperatorRewriteContext();

        BinaryPredicateOperator bpo = new BinaryPredicateOperator(BinaryType.EQ,
                ConstantOperator.createInt(1),
                ConstantOperator.createInt(2));

        ScalarOperator result = rule.apply(bpo, context);

        assertEquals(bpo, result);
    }

    @Test
    public void testInPredicate() {
        NormalizePredicateRule rule = new NormalizePredicateRule();
        ScalarOperatorRewriteContext context = new ScalarOperatorRewriteContext();

        InPredicateOperator inOp = new InPredicateOperator(
                ConstantOperator.createInt(1),
                new ColumnRefOperator(0, IntegerType.INT, "col1", true)
        );

        ScalarOperator result = rule.apply(inOp, context);
        BinaryPredicateOperator eqOp = new BinaryPredicateOperator(BinaryType.EQ,
                ConstantOperator.createInt(1),
                new ColumnRefOperator(0, IntegerType.INT, "col1", true)
        );

        assertEquals(eqOp, result);
    }

    // A deterministic function call with all-constant args reports isConstant()==true but,
    // having no FE evaluator (fn == null, like a UDF), can never be folded to a literal. Such a
    // value must be rewritten to an equality predicate instead of staying in the InPredicate,
    // otherwise BE fails with "VectorizedInPredicate value not const".
    private CallOperator nonFoldableConstantCall() {
        return new CallOperator("dc_udf.date_add", VarcharType.VARCHAR,
                Lists.newArrayList(ConstantOperator.createVarchar("20260812"), ConstantOperator.createInt(1)));
    }

    @Test
    public void testInPredicateWithNonFoldableConstant() {
        NormalizePredicateRule rule = new NormalizePredicateRule();
        ScalarOperatorRewriteContext context = new ScalarOperatorRewriteContext();

        ColumnRefOperator col = new ColumnRefOperator(1, VarcharType.VARCHAR, "dayno", true);
        CallOperator udfCall = nonFoldableConstantCall();
        // sanity: it is "constant" (isConstant) but not a true literal (isConstantRef)
        assertTrue(udfCall.isConstant());
        assertFalse(udfCall.isConstantRef());

        InPredicateOperator inOp = new InPredicateOperator(col,
                ConstantOperator.createVarchar("20260813"), udfCall);

        ScalarOperator result = rule.apply(inOp, context);

        // rewritten to OR of equality, not an InPredicate keeping the non-foldable call
        assertTrue(result instanceof CompoundPredicateOperator);
        assertFalse(containsInPredicateWith(result, udfCall));
    }

    @Test
    public void testInPredicateAllLiteralsStaysIn() {
        NormalizePredicateRule rule = new NormalizePredicateRule();
        ScalarOperatorRewriteContext context = new ScalarOperatorRewriteContext();

        ColumnRefOperator col = new ColumnRefOperator(1, IntegerType.INT, "c", true);
        InPredicateOperator inOp = new InPredicateOperator(col,
                ConstantOperator.createInt(1), ConstantOperator.createInt(2));

        ScalarOperator result = rule.apply(inOp, context);

        assertTrue(result instanceof InPredicateOperator);
    }

    @Test
    public void testInPredicateAllNonFoldableRewritesToOr() {
        NormalizePredicateRule rule = new NormalizePredicateRule();
        ScalarOperatorRewriteContext context = new ScalarOperatorRewriteContext();

        ColumnRefOperator col = new ColumnRefOperator(1, VarcharType.VARCHAR, "dayno", true);
        CallOperator udf1 = nonFoldableConstantCall();
        CallOperator udf2 = new CallOperator("dc_udf.date_add", VarcharType.VARCHAR,
                Lists.newArrayList(ConstantOperator.createVarchar("20260812"), ConstantOperator.createInt(3)));

        InPredicateOperator inOp = new InPredicateOperator(col, udf1, udf2);
        ScalarOperator result = rule.apply(inOp, context);

        assertTrue(result instanceof CompoundPredicateOperator);
        assertFalse(containsInPredicateWith(result, udf1));
        assertFalse(containsInPredicateWith(result, udf2));
    }

    // Full pipeline (FoldConstantsRule runs before NormalizePredicateRule): a foldable builtin
    // must be folded to a literal and the InPredicate preserved -- no regression. Use concat_ws,
    // which has a fixed-arity registered signature ({VARCHAR, VARCHAR} -> VARCHAR), so a Function
    // with matching args lets ScalarOperatorEvaluator fold it.
    @Test
    public void testInPredicateFoldableBuiltinStaysInViaPipeline() {
        ColumnRefOperator col = new ColumnRefOperator(1, VarcharType.VARCHAR, "c", true);
        Function fn = new Function(new FunctionName(FunctionSet.CONCAT_WS),
                new Type[] {VarcharType.VARCHAR, VarcharType.VARCHAR}, VarcharType.VARCHAR, false);
        CallOperator concatWsCall = new CallOperator(FunctionSet.CONCAT_WS, VarcharType.VARCHAR,
                Lists.newArrayList(ConstantOperator.createVarchar("a"), ConstantOperator.createVarchar("b")), fn);
        InPredicateOperator inOp = new InPredicateOperator(col, concatWsCall, ConstantOperator.createVarchar("x"));

        ScalarOperatorRewriter rewriter = new ScalarOperatorRewriter();
        ScalarOperator result = rewriter.rewrite(inOp, ScalarOperatorRewriter.DEFAULT_REWRITE_RULES);

        assertTrue(result instanceof InPredicateOperator);
        // the concat_ws call has been folded into a literal, no longer present in the tree
        assertFalse(containsOperator(result, concatWsCall));
    }

    // Full pipeline: a non-foldable constant (UDF-like) in the IN list must be rewritten to OR
    // of equality instead of reaching BE as a non-const InPredicate child.
    @Test
    public void testInPredicateNonFoldableRewritesViaPipeline() {
        ColumnRefOperator col = new ColumnRefOperator(1, VarcharType.VARCHAR, "dayno", true);
        CallOperator udfCall = nonFoldableConstantCall();
        InPredicateOperator inOp = new InPredicateOperator(col,
                ConstantOperator.createVarchar("20260813"), udfCall);

        ScalarOperatorRewriter rewriter = new ScalarOperatorRewriter();
        ScalarOperator result = rewriter.rewrite(inOp, ScalarOperatorRewriter.DEFAULT_REWRITE_RULES);

        assertFalse(containsInPredicateWith(result, udfCall));
    }

    private boolean containsInPredicateWith(ScalarOperator root, ScalarOperator target) {
        if (root instanceof InPredicateOperator) {
            for (ScalarOperator child : root.getChildren()) {
                if (child == target) {
                    return true;
                }
            }
        }
        for (ScalarOperator child : root.getChildren()) {
            if (containsInPredicateWith(child, target)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsOperator(ScalarOperator root, ScalarOperator target) {
        if (root == target) {
            return true;
        }
        for (ScalarOperator child : root.getChildren()) {
            if (containsOperator(child, target)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void testCompound() {
        ScalarOperator root = new CompoundPredicateOperator(CompoundPredicateOperator.CompoundType.OR,
                new CompoundPredicateOperator(CompoundPredicateOperator.CompoundType.OR,
                        new CompoundPredicateOperator(CompoundPredicateOperator.CompoundType.AND,
                                ConstantOperator.createBoolean(true),
                                ConstantOperator.createBoolean(true)),
                        new BinaryPredicateOperator(BinaryType.EQ,
                                ConstantOperator.createInt(1),
                                new ColumnRefOperator(1, IntegerType.INT, "test1", true))),
                new BinaryPredicateOperator(BinaryType.EQ,
                        ConstantOperator.createInt(1),
                        new ColumnRefOperator(1, IntegerType.INT, "test1", true)));

        ScalarOperatorRewriter operatorRewriter = new ScalarOperatorRewriter();
        ScalarOperator result = operatorRewriter
                .rewrite(root, Lists.newArrayList(new NormalizePredicateRule(), new SimplifiedPredicateRule()));

        assertTrue(result.isConstantTrue());
    }

    @Test
    public void testCompound1() {
        NormalizePredicateRule rule = new NormalizePredicateRule();
        ScalarOperatorRewriteContext context = new ScalarOperatorRewriteContext();

        InPredicateOperator inOp = new InPredicateOperator(
                true,
                ConstantOperator.createInt(1),
                new ColumnRefOperator(0, IntegerType.INT, "col1", true),
                new ColumnRefOperator(0, IntegerType.INT, "col1", true)
        );

        CompoundPredicateOperator compoundPredicateOperator =
                new CompoundPredicateOperator(CompoundPredicateOperator.CompoundType.AND, inOp,
                        new BinaryPredicateOperator(BinaryType.GE,
                                ConstantOperator.createInt(1),
                                new ColumnRefOperator(1, IntegerType.INT, "test1", true))
                );

        ScalarOperatorRewriter operatorRewriter = new ScalarOperatorRewriter();
        ScalarOperator res =
                operatorRewriter.rewrite(compoundPredicateOperator, Lists.newArrayList(new NormalizePredicateRule()));
    }

    @Test
    public void testCompound2() {
        InPredicateOperator inOp = new InPredicateOperator(
                false,
                ConstantOperator.createInt(1063),
                new ColumnRefOperator(0, IntegerType.INT, "col1", false),
                new ColumnRefOperator(1, IntegerType.INT, "col2", false),
                new ColumnRefOperator(2, IntegerType.INT, "col3", false),
                new ColumnRefOperator(3, IntegerType.INT, "col4", false)
        );

        ScalarOperatorRewriter operatorRewriter = new ScalarOperatorRewriter();
        ScalarOperator res =
                operatorRewriter.rewrite(inOp, Lists.newArrayList(new NormalizePredicateRule()));
    }
}