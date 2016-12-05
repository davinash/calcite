/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.rel.rel2sql;

import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Calc;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Intersect;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.Minus;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rel.core.TableModify.Operation;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.core.Union;
import org.apache.calcite.rel.core.Values;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexLocalRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexProgram;
import org.apache.calcite.sql.JoinConditionType;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDelete;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlSpecialOperator;
import org.apache.calcite.sql.SqlUpdate;
import org.apache.calcite.sql.SqlUtil;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.fun.SqlSingleValueAggFunction;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.InferTypes;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.ReflectUtil;
import org.apache.calcite.util.ReflectiveVisitor;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Utility to convert relational expressions to SQL abstract syntax tree.
 */
public class RelToSqlConverter extends SqlImplementor
    implements ReflectiveVisitor {

  private final ReflectUtil.MethodDispatcher<Result> dispatcher;

  /** Creates a RelToSqlConverter. */
  public RelToSqlConverter(SqlDialect dialect) {
    super(dialect);
    dispatcher = ReflectUtil.createMethodDispatcher(Result.class, this, "visit",
        RelNode.class);
  }

  /** Dispatches a call to the {@code visit(Xxx e)} method where {@code Xxx}
   * most closely matches the runtime type of the argument. */
  protected Result dispatch(RelNode e) {
    return dispatcher.invoke(e);
  }

  public Result visitChild(int i, RelNode e) {
    return dispatch(e);
  }

  /** @see #dispatch */
  public Result visit(RelNode e) {
    throw new AssertionError("Need to implement " + e.getClass().getName());
  }

  /** @see #dispatch */
  public Result visit(Join e) {
    final Result leftResult = visitChild(0, e.getLeft());
    final Result rightResult = visitChild(1, e.getRight());
    final Context leftContext = leftResult.qualifiedContext();
    final Context rightContext =
        rightResult.qualifiedContext();
    SqlNode sqlCondition = convertConditionToSqlNode(e.getCondition(),
        leftContext,
        rightContext,
        e.getLeft().getRowType().getFieldCount());
    SqlNode join =
        new SqlJoin(POS,
            leftResult.asFrom(),
            SqlLiteral.createBoolean(false, POS),
            joinType(e.getJoinType()).symbol(POS),
            rightResult.asFrom(),
            JoinConditionType.ON.symbol(POS),
            sqlCondition);
    return result(join, leftResult, rightResult);
  }

  /** @see #dispatch */
  public Result visit(Filter e) {
    Result x = visitChild(0, e.getInput());
    final Builder builder =
        x.builder(e, Clause.WHERE);
    builder.setWhere(builder.context.toSql(null, e.getCondition()));
    return builder.result();
  }

  /** @see #dispatch */
  public Result visit(Project e) {
    Result x = visitChild(0, e.getInput());
    if (isStar(e.getChildExps(), e.getInput().getRowType())) {
      return x;
    }
    final Builder builder =
        x.builder(e, Clause.SELECT);
    final List<SqlNode> selectList = new ArrayList<>();
    for (RexNode ref : e.getChildExps()) {
      SqlNode sqlExpr = builder.context.toSql(null, ref);
      addSelect(selectList, sqlExpr, e.getRowType());
    }

    builder.setSelect(new SqlNodeList(selectList, POS));
    return builder.result();
  }

  /** @see #dispatch */
  public Result visit(Aggregate e) {
    // "select a, b, sum(x) from ( ... ) group by a, b"
    final Result x = visitChild(0, e.getInput());
    final Builder builder;
    if (e.getInput() instanceof Project) {
      builder = x.builder(e);
      builder.clauses.add(Clause.GROUP_BY);
    } else {
      builder = x.builder(e, Clause.GROUP_BY);
    }
    List<SqlNode> groupByList = Expressions.list();
    final List<SqlNode> selectList = new ArrayList<>();
    for (int group : e.getGroupSet()) {
      final SqlNode field = builder.context.field(group);
      addSelect(selectList, field, e.getRowType());
      groupByList.add(field);
    }
    for (AggregateCall aggCall : e.getAggCallList()) {
      SqlNode aggCallSqlNode = builder.context.toSql(aggCall);
      if (aggCall.getAggregation() instanceof SqlSingleValueAggFunction) {
        aggCallSqlNode =
            rewriteSingleValueExpr(aggCallSqlNode, dialect);
      }
      addSelect(selectList, aggCallSqlNode, e.getRowType());
    }
    builder.setSelect(new SqlNodeList(selectList, POS));
    if (!groupByList.isEmpty() || e.getAggCallList().isEmpty()) {
      // Some databases don't support "GROUP BY ()". We can omit it as long
      // as there is at least one aggregate function.
      builder.setGroupBy(new SqlNodeList(groupByList, POS));
    }
    return builder.result();
  }

  /** @see #dispatch */
  public Result visit(TableScan e) {
    final SqlIdentifier identifier =
        new SqlIdentifier(e.getTable().getQualifiedName(), SqlParserPos.ZERO);
    return result(identifier, ImmutableList.of(Clause.FROM), e, null);
  }

  /** @see #dispatch */
  public Result visit(Union e) {
    return setOpToSql(e.all
        ? SqlStdOperatorTable.UNION_ALL
        : SqlStdOperatorTable.UNION, e);
  }

  /** @see #dispatch */
  public Result visit(Intersect e) {
    return setOpToSql(e.all
        ? SqlStdOperatorTable.INTERSECT_ALL
        : SqlStdOperatorTable.INTERSECT, e);
  }

  /** @see #dispatch */
  public Result visit(Minus e) {
    return setOpToSql(e.all
        ? SqlStdOperatorTable.EXCEPT_ALL
        : SqlStdOperatorTable.EXCEPT, e);
  }

  /** @see #dispatch */
  public Result visit(Calc e) {
    Result x = visitChild(0, e.getInput());
    final RexProgram program = e.getProgram();
    Builder builder =
        program.getCondition() != null
            ? x.builder(e, Clause.WHERE)
            : x.builder(e);
    if (!isStar(program)) {
      final List<SqlNode> selectList = new ArrayList<>();
      for (RexLocalRef ref : program.getProjectList()) {
        SqlNode sqlExpr = builder.context.toSql(program, ref);
        addSelect(selectList, sqlExpr, e.getRowType());
      }
      builder.setSelect(new SqlNodeList(selectList, POS));
    }

    if (program.getCondition() != null) {
      builder.setWhere(
          builder.context.toSql(program, program.getCondition()));
    }
    return builder.result();
  }

  /** @see #dispatch */
  public Result visit(Values e) {
    final List<String> fields = e.getRowType().getFieldNames();
    final List<Clause> clauses = ImmutableList.of(Clause.SELECT);
    final Map<String, RelDataType> pairs = ImmutableMap.of();
    final Context context = aliasContext(pairs, false);
    final List<SqlSelect> selects = new ArrayList<>();
    for (List<RexLiteral> tuple : e.getTuples()) {
      final List<SqlNode> selectList = new ArrayList<>();
      for (Pair<RexLiteral, String> literal : Pair.zip(tuple, fields)) {
        selectList.add(
            SqlStdOperatorTable.AS.createCall(
                POS,
                context.toSql(null, literal.left),
                new SqlIdentifier(literal.right, POS)));
      }
      selects.add(
          new SqlSelect(POS, SqlNodeList.EMPTY,
              new SqlNodeList(selectList, POS), null, null, null,
              null, null, null, null, null));
    }
    SqlNode query = null;
    for (SqlSelect select : selects) {
      if (query == null) {
        query = select;
      } else {
        query = SqlStdOperatorTable.UNION_ALL.createCall(POS, query,
            select);
      }
    }
    return result(query, clauses, e, null);
  }

  /** @see #dispatch */
  public Result visit(Sort e) {
    Result x = visitChild(0, e.getInput());
    Builder builder = x.builder(e, Clause.ORDER_BY);
    List<SqlNode> orderByList = Expressions.list();
    for (RelFieldCollation field : e.getCollation().getFieldCollations()) {
      builder.addOrderItem(orderByList, field);
    }
    if (!orderByList.isEmpty()) {
      builder.setOrderBy(new SqlNodeList(orderByList, POS));
      x = builder.result();
    }
    if (e.fetch != null) {
      builder = x.builder(e, Clause.FETCH);
      builder.setFetch(builder.context.toSql(null, e.fetch));
      x = builder.result();
    }
    if (e.offset != null) {
      builder = x.builder(e, Clause.OFFSET);
      builder.setOffset(builder.context.toSql(null, e.offset));
      x = builder.result();
    }
    return x;
  }

  /** @see #dispatch */
  public Result visit(TableModify modify) {
    final Map<String, RelDataType> pairs = ImmutableMap.of();
    final Context context = aliasContext(pairs, false);

    // Target Table Name
    final SqlIdentifier sqlTargetTable =
      new SqlIdentifier(modify.getTable().getQualifiedName(), POS);

    if (modify.getOperation().equals(Operation.INSERT)) {
      Values values = (Values) modify.getInput();

      // Column Names
      final List<String> fields = values.getRowType().getFieldNames();
      ImmutableList.Builder<SqlNode> columnNamesBuilder =
        ImmutableList.builder();
      for (String field : fields) {
        columnNamesBuilder.add(new SqlIdentifier(field, POS));
      }
      SqlNodeList sqlColumnList =
        new SqlNodeList(columnNamesBuilder.build(), POS);

      // Values
      ImmutableList.Builder<SqlNode> valuesOperandsBuilder =
        ImmutableList.builder();
      for (List<RexLiteral> tuple: values.getTuples()) {
        for (RexLiteral rexLiteral : tuple) {
          valuesOperandsBuilder.add(context.toSql(null, rexLiteral));
        }
      }
      ImmutableList<SqlNode> operandsList = valuesOperandsBuilder.build();
      SqlNode[] operands = operandsList.toArray(
        new SqlNode[operandsList.size()]);

      SqlCall sqlValues =
        new SqlBasicCall(SQL_INSERT_VALUES_OPERATOR, operands, POS);

      // Keywords
      SqlNodeList keywords = new SqlNodeList(ImmutableList.<SqlNode>of(), POS);

      SqlInsert sqlInsert = new SqlInsert(POS, keywords, sqlTargetTable,
        sqlValues, sqlColumnList);

      return result(sqlInsert, ImmutableList.<Clause>of(), modify, null);

    } else if (modify.getOperation().equals(Operation.UPDATE)) {

      // Update Columns
      ImmutableList.Builder<SqlIdentifier> targetUpdateColumnListBuilder =
        ImmutableList.builder();
      for (String uclName: modify.getUpdateColumnList()) {
        targetUpdateColumnListBuilder.add(new SqlIdentifier(uclName, POS));
      }

      // Source Expressions
      ImmutableList.Builder<SqlNode> sourceExpressionListBuilder =
        ImmutableList.builder();
      for (RexNode rexNode : modify.getSourceExpressionList()) {
        sourceExpressionListBuilder.add(context.toSql(null, rexNode));
      }

      Result input = visitChild(0, modify.getInput());

      // Source Select
      SqlSelect sqlSourceSelect = (SqlSelect) input.node;

      // Condition
      SqlNode sqlCondition = sqlSourceSelect.getWhere();

      SqlUpdate sqlUpdate  = new SqlUpdate(POS, sqlTargetTable,
        new SqlNodeList(targetUpdateColumnListBuilder.build(), POS),
        new SqlNodeList(sourceExpressionListBuilder.build(), POS),
        sqlCondition,
        sqlSourceSelect,
        null);

      return result(sqlUpdate, input.clauses, modify, null);

    } else if (modify.getOperation().equals(Operation.DELETE)) {

      Result input = visitChild(0, modify.getInput());

      // Source Select
      SqlSelect sqlSourceSelect = (SqlSelect) visitChild(0,
        modify.getInput()).node;

      // Condition
      SqlNode sqlCondition = sqlSourceSelect.getWhere();

      SqlDelete sqlDelete = new SqlDelete(POS, sqlTargetTable, sqlCondition,
        sqlSourceSelect, null);

      return result(sqlDelete, input.clauses, modify, null);
    }

    throw new AssertionError("not implemented: " + modify);
  }

  private static final SqlSpecialOperator SQL_INSERT_VALUES_OPERATOR =
    new SqlSpecialOperator("VALUES", SqlKind.VALUES,
        SqlOperator.MDX_PRECEDENCE, false, null,
        InferTypes.RETURN_TYPE, OperandTypes.VARIADIC) {
      public void unparse(SqlWriter writer, SqlCall call,
          int leftPrec, int rightPrec) {
        SqlUtil.unparseFunctionSyntax(this, writer, call);
      }
    };

  @Override public void addSelect(List<SqlNode> selectList, SqlNode node,
      RelDataType rowType) {
    String name = rowType.getFieldNames().get(selectList.size());
    String alias = SqlValidatorUtil.getAlias(node, -1);
    if (name.toLowerCase().startsWith("expr$")) {
      //Put it in ordinalMap
      ordinalMap.put(name.toLowerCase(), node);
    } else if (alias == null || !alias.equals(name)) {
      node = SqlStdOperatorTable.AS.createCall(
          POS, node, new SqlIdentifier(name, POS));
    }
    selectList.add(node);
  }
}

// End RelToSqlConverter.java
