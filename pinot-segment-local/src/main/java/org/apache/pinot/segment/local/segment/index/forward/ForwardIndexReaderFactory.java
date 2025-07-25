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

package org.apache.pinot.segment.local.segment.index.forward;

import java.util.Arrays;
import org.apache.pinot.segment.local.io.writer.impl.VarByteChunkForwardIndexWriterV4;
import org.apache.pinot.segment.local.io.writer.impl.VarByteChunkForwardIndexWriterV5;
import org.apache.pinot.segment.local.segment.creator.impl.fwd.CLPForwardIndexCreatorV1;
import org.apache.pinot.segment.local.segment.creator.impl.fwd.CLPForwardIndexCreatorV2;
import org.apache.pinot.segment.local.segment.index.readers.forward.CLPForwardIndexReaderV1;
import org.apache.pinot.segment.local.segment.index.readers.forward.CLPForwardIndexReaderV2;
import org.apache.pinot.segment.local.segment.index.readers.forward.FixedBitMVEntryDictForwardIndexReader;
import org.apache.pinot.segment.local.segment.index.readers.forward.FixedBitMVForwardIndexReader;
import org.apache.pinot.segment.local.segment.index.readers.forward.FixedBitSVForwardIndexReaderV2;
import org.apache.pinot.segment.local.segment.index.readers.forward.FixedByteChunkMVForwardIndexReader;
import org.apache.pinot.segment.local.segment.index.readers.forward.FixedByteChunkSVForwardIndexReader;
import org.apache.pinot.segment.local.segment.index.readers.forward.FixedBytePower2ChunkSVForwardIndexReader;
import org.apache.pinot.segment.local.segment.index.readers.forward.VarByteChunkForwardIndexReaderV4;
import org.apache.pinot.segment.local.segment.index.readers.forward.VarByteChunkForwardIndexReaderV5;
import org.apache.pinot.segment.local.segment.index.readers.forward.VarByteChunkMVForwardIndexReader;
import org.apache.pinot.segment.local.segment.index.readers.forward.VarByteChunkSVForwardIndexReader;
import org.apache.pinot.segment.local.segment.index.readers.sorted.SortedIndexReaderImpl;
import org.apache.pinot.segment.spi.ColumnMetadata;
import org.apache.pinot.segment.spi.index.ForwardIndexConfig;
import org.apache.pinot.segment.spi.index.IndexReaderConstraintException;
import org.apache.pinot.segment.spi.index.IndexReaderFactory;
import org.apache.pinot.segment.spi.index.IndexType;
import org.apache.pinot.segment.spi.index.StandardIndexes;
import org.apache.pinot.segment.spi.index.reader.ForwardIndexReader;
import org.apache.pinot.segment.spi.memory.PinotDataBuffer;
import org.apache.pinot.spi.data.FieldSpec.DataType;


public class ForwardIndexReaderFactory extends IndexReaderFactory.Default<ForwardIndexConfig, ForwardIndexReader> {
  private static volatile ForwardIndexReaderFactory _instance = new ForwardIndexReaderFactory();

  public static void setInstance(ForwardIndexReaderFactory factory) {
    _instance = factory;
  }

  public static ForwardIndexReaderFactory getInstance() {
    return _instance;
  }

  @Override
  protected IndexType<ForwardIndexConfig, ForwardIndexReader, ?> getIndexType() {
    return StandardIndexes.forward();
  }

  @Override
  protected ForwardIndexReader createIndexReader(PinotDataBuffer dataBuffer, ColumnMetadata metadata,
      ForwardIndexConfig indexConfig)
      throws IndexReaderConstraintException {
    return createIndexReader(dataBuffer, metadata);
  }

  public ForwardIndexReader createIndexReader(PinotDataBuffer dataBuffer, ColumnMetadata metadata) {
    if (metadata.hasDictionary()) {
      if (metadata.isSingleValue()) {
        if (metadata.isSorted()) {
          return new SortedIndexReaderImpl(dataBuffer, metadata.getCardinality());
        } else {
          return new FixedBitSVForwardIndexReaderV2(dataBuffer, metadata.getTotalDocs(), metadata.getBitsPerElement());
        }
      } else {
        if (dataBuffer.size() > Integer.BYTES
            && dataBuffer.getInt(0) == FixedBitMVEntryDictForwardIndexReader.MAGIC_MARKER) {
          return new FixedBitMVEntryDictForwardIndexReader(dataBuffer, metadata.getTotalDocs(),
              metadata.getBitsPerElement());
        } else {
          return new FixedBitMVForwardIndexReader(dataBuffer, metadata.getTotalDocs(),
              metadata.getTotalNumberOfEntries(), metadata.getBitsPerElement());
        }
      }
    } else {
      if (dataBuffer.size() >= CLPForwardIndexCreatorV1.MAGIC_BYTES.length) {
        byte[] magicBytes = new byte[CLPForwardIndexCreatorV1.MAGIC_BYTES.length];
        dataBuffer.copyTo(0, magicBytes);
        if (Arrays.equals(magicBytes, CLPForwardIndexCreatorV1.MAGIC_BYTES)) {
          return new CLPForwardIndexReaderV1(dataBuffer, metadata.getTotalDocs());
        }
      }
      if (dataBuffer.size() >= CLPForwardIndexCreatorV2.MAGIC_BYTES.length) {
        byte[] magicBytes = new byte[CLPForwardIndexCreatorV2.MAGIC_BYTES.length];
        dataBuffer.copyTo(0, magicBytes);
        if (Arrays.equals(magicBytes, CLPForwardIndexCreatorV2.MAGIC_BYTES)) {
          return new CLPForwardIndexReaderV2(dataBuffer, metadata.getTotalDocs());
        }
      }
      return createRawIndexReader(dataBuffer, metadata.getDataType().getStoredType(), metadata.isSingleValue());
    }
  }

  public ForwardIndexReader createRawIndexReader(PinotDataBuffer dataBuffer, DataType storedType,
      boolean isSingleValue) {
    int version = dataBuffer.getInt(0);
    if (isSingleValue && storedType.isFixedWidth()) {
      return version == FixedBytePower2ChunkSVForwardIndexReader.VERSION
          ? new FixedBytePower2ChunkSVForwardIndexReader(dataBuffer, storedType)
          : new FixedByteChunkSVForwardIndexReader(dataBuffer, storedType);
    }

    if (version == VarByteChunkForwardIndexWriterV5.VERSION) {
      // V5 is the same as V4 except the multi-value docs have implicit value count rather than explicit
      return new VarByteChunkForwardIndexReaderV5(dataBuffer, storedType, isSingleValue);
    } else if (version == VarByteChunkForwardIndexWriterV4.VERSION) {
      // V4 reader is common for sv var byte, mv fixed byte and mv var byte
      return new VarByteChunkForwardIndexReaderV4(dataBuffer, storedType, isSingleValue);
    } else {
      return createNonV4RawIndexReader(dataBuffer, storedType, isSingleValue);
    }
  }

  private ForwardIndexReader createNonV4RawIndexReader(PinotDataBuffer dataBuffer, DataType storedType,
      boolean isSingleValue) {
    // Only reach here if SV + raw + var byte + non v4 or MV + non v4
    if (isSingleValue) {
      return new VarByteChunkSVForwardIndexReader(dataBuffer, storedType);
    } else {
      if (storedType.isFixedWidth()) {
        return new FixedByteChunkMVForwardIndexReader(dataBuffer, storedType);
      } else {
        return new VarByteChunkMVForwardIndexReader(dataBuffer, storedType);
      }
    }
  }
}
