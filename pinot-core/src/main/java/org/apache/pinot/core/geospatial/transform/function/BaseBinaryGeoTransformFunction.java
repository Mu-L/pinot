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
package org.apache.pinot.core.geospatial.transform.function;

import com.google.common.base.Preconditions;
import java.util.List;
import java.util.Map;
import org.apache.pinot.core.operator.ColumnContext;
import org.apache.pinot.core.operator.blocks.ValueBlock;
import org.apache.pinot.core.operator.transform.function.BaseTransformFunction;
import org.apache.pinot.core.operator.transform.function.LiteralTransformFunction;
import org.apache.pinot.core.operator.transform.function.TransformFunction;
import org.apache.pinot.segment.local.utils.GeometrySerializer;
import org.apache.pinot.spi.data.FieldSpec;
import org.locationtech.jts.geom.Geometry;


/**
 * Base Binary geo transform functions that can take either one of the arguments as literal.
 */
public abstract class BaseBinaryGeoTransformFunction extends BaseTransformFunction {
  private TransformFunction _firstArgument;
  private TransformFunction _secondArgument;
  private Geometry _firstLiteral;
  private Geometry _secondLiteral;

  @Override
  public void init(List<TransformFunction> arguments, Map<String, ColumnContext> columnContextMap) {
    super.init(arguments, columnContextMap);
    Preconditions.checkArgument(arguments.size() == 2, "2 arguments are required for transform function: %s",
        getName());
    TransformFunction transformFunction = arguments.get(0);
    Preconditions.checkArgument(transformFunction.getResultMetadata().isSingleValue(),
        "First argument must be single-valued for transform function: %s", getName());
    Preconditions.checkArgument(transformFunction.getResultMetadata().getDataType() == FieldSpec.DataType.BYTES
            || transformFunction instanceof LiteralTransformFunction,
        "The first argument must be of type BYTES , but was %s", transformFunction.getResultMetadata().getDataType());
    if (transformFunction instanceof LiteralTransformFunction) {
      _firstLiteral = GeometrySerializer.deserialize(((LiteralTransformFunction) transformFunction).getBytesLiteral());
    } else {
      _firstArgument = transformFunction;
    }
    transformFunction = arguments.get(1);
    Preconditions.checkArgument(transformFunction.getResultMetadata().isSingleValue(),
        "Second argument must be single-valued for transform function: %s", getName());
    Preconditions.checkArgument(transformFunction.getResultMetadata().getDataType() == FieldSpec.DataType.BYTES
            || transformFunction instanceof LiteralTransformFunction,
        "The second argument must be of type BYTES , but was %s", transformFunction.getResultMetadata().getDataType());
    if (transformFunction instanceof LiteralTransformFunction) {
      _secondLiteral = GeometrySerializer.deserialize(((LiteralTransformFunction) transformFunction).getBytesLiteral());
    } else {
      _secondArgument = transformFunction;
    }
  }

  protected int[] transformGeometryToIntValuesSV(ValueBlock valueBlock) {
    int numDocs = valueBlock.getNumDocs();
    initIntValuesSV(numDocs);
    byte[][] firstValues;
    byte[][] secondValues;

    if (_firstArgument == null && _secondArgument == null) {
      int value = transformGeometryToInt(_firstLiteral, _secondLiteral);
      for (int i = 0; i < numDocs; i++) {
        _intValuesSV[i] = value;
      }
    } else if (_firstArgument == null) {
      secondValues = _secondArgument.transformToBytesValuesSV(valueBlock);
      for (int i = 0; i < numDocs; i++) {
        _intValuesSV[i] = transformGeometryToInt(_firstLiteral, GeometrySerializer.deserialize(secondValues[i]));
      }
    } else if (_secondArgument == null) {
      firstValues = _firstArgument.transformToBytesValuesSV(valueBlock);
      for (int i = 0; i < numDocs; i++) {
        _intValuesSV[i] = transformGeometryToInt(GeometrySerializer.deserialize(firstValues[i]), _secondLiteral);
      }
    } else {
      firstValues = _firstArgument.transformToBytesValuesSV(valueBlock);
      secondValues = _secondArgument.transformToBytesValuesSV(valueBlock);
      for (int i = 0; i < numDocs; i++) {
        _intValuesSV[i] = transformGeometryToInt(GeometrySerializer.deserialize(firstValues[i]),
            GeometrySerializer.deserialize(secondValues[i]));
      }
    }
    return _intValuesSV;
  }

  protected double[] transformGeometryToDoubleValuesSV(ValueBlock valueBlock) {
    int numDocs = valueBlock.getNumDocs();
    initDoubleValuesSV(numDocs);

    byte[][] firstValues;
    byte[][] secondValues;

    if (_firstArgument == null && _secondArgument == null) {
      double value = transformGeometryToDouble(_firstLiteral, _secondLiteral);
      for (int i = 0; i < numDocs; i++) {
        _doubleValuesSV[i] = value;
      }
    } else if (_firstArgument == null) {
      secondValues = _secondArgument.transformToBytesValuesSV(valueBlock);
      for (int i = 0; i < numDocs; i++) {
        _doubleValuesSV[i] = transformGeometryToDouble(_firstLiteral, GeometrySerializer.deserialize(secondValues[i]));
      }
    } else if (_secondArgument == null) {
      firstValues = _firstArgument.transformToBytesValuesSV(valueBlock);
      for (int i = 0; i < numDocs; i++) {
        _doubleValuesSV[i] = transformGeometryToDouble(GeometrySerializer.deserialize(firstValues[i]), _secondLiteral);
      }
    } else {
      firstValues = _firstArgument.transformToBytesValuesSV(valueBlock);
      secondValues = _secondArgument.transformToBytesValuesSV(valueBlock);
      for (int i = 0; i < numDocs; i++) {
        _doubleValuesSV[i] = transformGeometryToDouble(GeometrySerializer.deserialize(firstValues[i]),
            GeometrySerializer.deserialize(secondValues[i]));
      }
    }
    return _doubleValuesSV;
  }

  public int transformGeometryToInt(Geometry firstGeometry, Geometry secondGeometry) {
    throw new UnsupportedOperationException();
  }

  public double transformGeometryToDouble(Geometry firstGeometry, Geometry secondGeometry) {
    throw new UnsupportedOperationException();
  }
}
