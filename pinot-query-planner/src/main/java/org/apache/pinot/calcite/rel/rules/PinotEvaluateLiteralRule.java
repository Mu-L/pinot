/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.calcite.rel.rules;

import com.google.common.base.Preconditions;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import org.apache.calcite.avatica.util.ByteString;
import org.apache.calcite.avatica.util.TimeUnitRange;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.ArraySqlType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.tools.RelBuilderFactory;
import org.apache.calcite.util.NlsString;
import org.apache.pinot.common.function.FunctionInfo;
import org.apache.pinot.common.function.FunctionRegistry;
import org.apache.pinot.common.function.QueryFunctionInvoker;
import org.apache.pinot.common.utils.DataSchema.ColumnDataType;
import org.apache.pinot.query.planner.logical.RelToPlanNodeConverter;
import org.apache.pinot.spi.utils.TimestampUtils;
import org.apache.pinot.sql.parsers.SqlCompilationException;


/**
 * PinotEvaluateLiteralRule that matches the literal only function calls and evaluates them.
 */
public class PinotEvaluateLiteralRule {

  public static class Project extends RelOptRule {
    public static final Project INSTANCE = new Project(PinotRuleUtils.PINOT_REL_FACTORY, null);

    public static Project instanceWithDescription(String description) {
      return new Project(PinotRuleUtils.PINOT_REL_FACTORY, description);
    }

    private Project(RelBuilderFactory factory, @Nullable String description) {
      super(operand(LogicalProject.class, any()), factory, description);
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
      LogicalProject oldProject = call.rel(0);
      RexBuilder rexBuilder = oldProject.getCluster().getRexBuilder();
      LogicalProject newProject = (LogicalProject) oldProject.accept(new EvaluateLiteralShuttle(rexBuilder));
      if (newProject != oldProject) {
        call.transformTo(constructNewProject(oldProject, newProject, rexBuilder));
      }
    }
  }

  /**
   * Constructs a new LogicalProject that matches the type of the old LogicalProject.
   */
  private static LogicalProject constructNewProject(LogicalProject oldProject, LogicalProject newProject,
      RexBuilder rexBuilder) {
    List<RexNode> oldProjects = oldProject.getProjects();
    List<RexNode> newProjects = newProject.getProjects();
    int numProjects = oldProjects.size();
    assert newProjects.size() == numProjects;
    List<RexNode> castedNewProjects = new ArrayList<>(numProjects);
    boolean needCast = false;
    for (int i = 0; i < numProjects; i++) {
      RexNode oldNode = oldProjects.get(i);
      RexNode newNode = newProjects.get(i);
      // Need to cast the result to the original type if the literal type is changed, e.g. VARCHAR literal is typed as
      // CHAR(STRING_LENGTH) in Calcite, but we need to cast it back to VARCHAR.
      if (!oldNode.getType().equals(newNode.getType())) {
        needCast = true;
        newNode = rexBuilder.makeCast(oldNode.getType(), newNode, true);
      }
      castedNewProjects.add(newNode);
    }
    return needCast ? oldProject.copy(oldProject.getTraitSet(), oldProject.getInput(), castedNewProjects,
        oldProject.getRowType()) : newProject;
  }

  public static class Filter extends RelOptRule {
    public static final Filter INSTANCE = new Filter(PinotRuleUtils.PINOT_REL_FACTORY, null);

    public static Filter instanceWithDescription(String description) {
      return new Filter(PinotRuleUtils.PINOT_REL_FACTORY, description);
    }

    private Filter(RelBuilderFactory factory, @Nullable String description) {
      super(operand(LogicalFilter.class, any()), factory, description);
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
      LogicalFilter oldFilter = call.rel(0);
      RexBuilder rexBuilder = oldFilter.getCluster().getRexBuilder();
      LogicalFilter newFilter = (LogicalFilter) oldFilter.accept(new EvaluateLiteralShuttle(rexBuilder));
      if (newFilter != oldFilter) {
        call.transformTo(newFilter);
      }
    }
  }

  /**
   * A RexShuttle that recursively evaluates all the calls with literal only operands.
   */
  private static class EvaluateLiteralShuttle extends RexShuttle {
    final RexBuilder _rexBuilder;

    EvaluateLiteralShuttle(RexBuilder rexBuilder) {
      _rexBuilder = rexBuilder;
    }

    @Override
    public RexNode visitCall(RexCall call) {
      RexCall visitedCall = (RexCall) super.visitCall(call);
      // Check if all operands are RexLiteral
      if (visitedCall.operands.stream().allMatch(
          operand -> operand instanceof RexLiteral || (operand instanceof RexCall && ((RexCall) operand).getOperands()
              .stream().allMatch(op -> op instanceof RexLiteral)))) {
        return evaluateLiteralOnlyFunction(visitedCall, _rexBuilder);
      } else {
        return visitedCall;
      }
    }
  }

  /**
   * Evaluates the literal only function and returns the result as a RexLiteral if it can be evaluated, or the function
   * itself (RexCall) if it cannot be evaluated.
   */
  private static RexNode evaluateLiteralOnlyFunction(RexCall rexCall, RexBuilder rexBuilder) {
    List<RexNode> operands = rexCall.getOperands();
    assert operands.stream().allMatch(
        operand -> operand instanceof RexLiteral || (operand instanceof RexCall && ((RexCall) operand).getOperands()
            .stream().allMatch(op -> op instanceof RexLiteral)));

    int numArguments = operands.size();
    ColumnDataType[] argumentTypes = new ColumnDataType[numArguments];
    Object[] arguments = new Object[numArguments];
    for (int i = 0; i < numArguments; i++) {
      RexNode rexNode = operands.get(i);
      RexLiteral rexLiteral;
      if (rexNode instanceof RexLiteral) {
        rexLiteral = (RexLiteral) rexNode;
      } else {
        // Function operands cannot be evaluated, skip
        return rexCall;
      }
      argumentTypes[i] = RelToPlanNodeConverter.convertToColumnDataType(rexLiteral.getType());
      arguments[i] = getLiteralValue(rexLiteral);
    }

    if (rexCall.getKind() == SqlKind.CAST) {
      // Handle separately because the CAST operator only has one operand (the value to be cast) and the type to be cast
      // to is determined by the operator's return type. Pinot's CAST function implementation requires two arguments:
      // the value to be cast and the target type.
      argumentTypes = new ColumnDataType[]{argumentTypes[0], ColumnDataType.STRING};
      arguments = new Object[]{arguments[0], RelToPlanNodeConverter.convertToColumnDataType(rexCall.getType()).name()};
    }
    String canonicalName = FunctionRegistry.canonicalize(PinotRuleUtils.extractFunctionName(rexCall));
    FunctionInfo functionInfo = FunctionRegistry.lookupFunctionInfo(canonicalName, argumentTypes);
    if (functionInfo == null) {
      // Function cannot be evaluated
      return rexCall;
    }
    RelDataType rexNodeType = rexCall.getType();
    if (rexNodeType.getSqlTypeName() == SqlTypeName.DECIMAL) {
      rexNodeType = convertDecimalType(rexNodeType, rexBuilder);
    }
    Object resultValue;
    try {
      QueryFunctionInvoker invoker = new QueryFunctionInvoker(functionInfo);
      if (functionInfo.getMethod().isVarArgs()) {
        resultValue = invoker.invoke(new Object[]{arguments});
      } else {
        invoker.convertTypes(arguments);
        resultValue = invoker.invoke(arguments);
      }
      if (rexNodeType.getSqlTypeName() == SqlTypeName.ARRAY) {
        RelDataType componentType = rexNodeType.getComponentType();
        if (componentType != null) {
          if (Objects.requireNonNull(componentType.getSqlTypeName()) == SqlTypeName.CHAR) {
            // Calcite uses CHAR for STRING, but we need to use VARCHAR for STRING
            rexNodeType = rexBuilder.getTypeFactory()
                .createArrayType(rexBuilder.getTypeFactory().createSqlType(SqlTypeName.VARCHAR), -1);
          }
        }
      }
    } catch (Exception e) {
      throw new SqlCompilationException(
          "Caught exception while invoking method: " + functionInfo.getMethod().getName() + " with arguments: "
              + Arrays.toString(arguments) + ": " + e.getMessage(), e);
    }
    try {
      resultValue = convertResultValue(resultValue, rexNodeType);
    } catch (Exception e) {
      throw new SqlCompilationException(
          "Caught exception while converting result value: " + resultValue + " to type: " + rexNodeType, e);
    }
    if (resultValue == null) {
      return rexBuilder.makeNullLiteral(rexNodeType);
    }
    try {
      if (rexNodeType instanceof ArraySqlType) {
        List<Object> resultValues = new ArrayList<>();

        // SQL FLOAT and DOUBLE literals are represented as Java double
        if (resultValue instanceof double[]) {
          for (double value: (double[]) resultValue) {
            resultValues.add(convertResultValue(value, rexNodeType.getComponentType()));
          }
        } else {
          for (Object value : (Object[]) resultValue) {
            resultValues.add(convertResultValue(value, rexNodeType.getComponentType()));
          }
        }
        return rexBuilder.makeLiteral(resultValues, rexNodeType, false);
      }
      return rexBuilder.makeLiteral(resultValue, rexNodeType, false);
    } catch (Exception e) {
      throw new SqlCompilationException(
          "Caught exception while making literal with value: " + resultValue + " and type: " + rexNodeType, e);
    }
  }

  private static RelDataType convertDecimalType(RelDataType relDataType, RexBuilder rexBuilder) {
    Preconditions.checkArgument(relDataType.getSqlTypeName() == SqlTypeName.DECIMAL);
    return RelToPlanNodeConverter.convertToColumnDataType(relDataType).toType(rexBuilder.getTypeFactory());
  }

  @Nullable
  private static Object getLiteralValue(RexLiteral rexLiteral) {
    Object value = rexLiteral.getValue();
    if (value instanceof NlsString) {
      // STRING
      return ((NlsString) value).getValue();
    } else if (value instanceof GregorianCalendar) {
      // TIMESTAMP
      return ((GregorianCalendar) value).getTimeInMillis();
    } else if (value instanceof ByteString) {
      // BYTES
      return ((ByteString) value).getBytes();
    } else if (value instanceof TimeUnitRange) {
      return ((TimeUnitRange) value).name();
    } else {
      return value;
    }
  }

  @Nullable
  private static Object convertResultValue(@Nullable Object resultValue, RelDataType relDataType) {
    if (resultValue == null) {
      return null;
    }
    if (relDataType.getSqlTypeName() == SqlTypeName.TIMESTAMP) {
      // Return millis since epoch for TIMESTAMP
      if (resultValue instanceof Timestamp) {
        return ((Timestamp) resultValue).getTime();
      } else if (resultValue instanceof Number) {
        return ((Number) resultValue).longValue();
      } else {
        return TimestampUtils.toMillisSinceEpoch(resultValue.toString());
      }
    }
    // Use BigDecimal for INTEGER / BIGINT / DECIMAL literals
    if (relDataType.getSqlTypeName() == SqlTypeName.INTEGER || relDataType.getSqlTypeName() == SqlTypeName.BIGINT) {
      return new BigDecimal(((Number) resultValue).longValue());
    }
    if (relDataType.getSqlTypeName() == SqlTypeName.DECIMAL) {
      return new BigDecimal(resultValue.toString());
    }
    // Use double for FLOAT / DOUBLE literals
    if (relDataType.getSqlTypeName() == SqlTypeName.FLOAT || relDataType.getSqlTypeName() == SqlTypeName.DOUBLE
        || relDataType.getSqlTypeName() == SqlTypeName.REAL) {
      return ((Number) resultValue).doubleValue();
    }
    // Return ByteString for byte[]
    if (resultValue instanceof byte[]) {
      return new ByteString((byte[]) resultValue);
    }
    // TODO: Add more type handling
    return resultValue;
  }
}
