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
package org.apache.pinot.core.data.manager;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.apache.pinot.core.data.manager.offline.ImmutableSegmentDataManager;
import org.apache.pinot.segment.local.data.manager.StaleSegment;
import org.apache.pinot.segment.local.indexsegment.immutable.ImmutableSegmentLoader;
import org.apache.pinot.segment.local.segment.creator.impl.SegmentIndexCreationDriverImpl;
import org.apache.pinot.segment.local.segment.index.loader.IndexLoadingConfig;
import org.apache.pinot.segment.local.segment.readers.GenericRowRecordReader;
import org.apache.pinot.segment.spi.ImmutableSegment;
import org.apache.pinot.segment.spi.creator.SegmentGeneratorConfig;
import org.apache.pinot.segment.spi.creator.SegmentVersion;
import org.apache.pinot.segment.spi.index.startree.AggregationFunctionColumnPair;
import org.apache.pinot.spi.config.table.ColumnPartitionConfig;
import org.apache.pinot.spi.config.table.FieldConfig;
import org.apache.pinot.spi.config.table.MultiColumnTextIndexConfig;
import org.apache.pinot.spi.config.table.SegmentPartitionConfig;
import org.apache.pinot.spi.config.table.StarTreeAggregationConfig;
import org.apache.pinot.spi.config.table.StarTreeIndexConfig;
import org.apache.pinot.spi.config.table.TableConfig;
import org.apache.pinot.spi.config.table.TableType;
import org.apache.pinot.spi.data.DimensionFieldSpec;
import org.apache.pinot.spi.data.FieldSpec;
import org.apache.pinot.spi.data.MetricFieldSpec;
import org.apache.pinot.spi.data.Schema;
import org.apache.pinot.spi.data.readers.GenericRow;
import org.apache.pinot.spi.utils.builder.TableConfigBuilder;
import org.apache.pinot.spi.utils.builder.TableNameBuilder;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;


@Test
public class BaseTableDataManagerNeedRefreshTest {
  private static final File TEMP_DIR = new File(FileUtils.getTempDirectory(), "BaseTableDataManagerNeedRefreshTest");
  private static final String DEFAULT_TABLE_NAME = "mytable";
  private static final String OFFLINE_TABLE_NAME = TableNameBuilder.OFFLINE.tableNameWithType(DEFAULT_TABLE_NAME);
  private static final File TABLE_DATA_DIR = new File(TEMP_DIR, OFFLINE_TABLE_NAME);

  private static final String DEFAULT_TIME_COLUMN_NAME = "DaysSinceEpoch";
  private static final String MS_SINCE_EPOCH_COLUMN_NAME = "MilliSecondsSinceEpoch";
  //multi-column text index
  private static final String MC_TEXT_INDEX_COLUMN_1 = "MCtextColumn1";
  private static final String MC_TEXT_INDEX_COLUMN_2 = "MCtextColumn2";
  private static final String TEXT_INDEX_COLUMN = "textColumn";
  private static final String TEXT_INDEX_COLUMN_MV = "textColumnMV";
  private static final String PARTITIONED_COLUMN_NAME = "partitionedColumn";
  private static final String DISTANCE_COLUMN_NAME = "Distance";
  private static final String CARRIER_COLUMN_NAME = "Carrier";
  private static final int NUM_PARTITIONS = 20; // For modulo function
  private static final String PARTITION_FUNCTION_NAME = "MoDuLo";

  private static final String JSON_INDEX_COLUMN = "jsonField";
  private static final String FST_TEST_COLUMN = "DestCityName";
  private static final String NULL_VALUE_COLUMN = "NullValueColumn";

  private static final TableConfig TABLE_CONFIG;
  private static final Schema SCHEMA;
  private static final IndexLoadingConfig INDEX_LOADING_CONFIG;
  private static final ImmutableSegmentDataManager IMMUTABLE_SEGMENT_DATA_MANAGER;
  private static final BaseTableDataManager BASE_TABLE_DATA_MANAGER;

  private String _testName = "defaultTestName";

  static {
    try {
      TABLE_CONFIG = getTableConfigBuilder().build();
      SCHEMA = getSchema();
      INDEX_LOADING_CONFIG = new IndexLoadingConfig(TABLE_CONFIG, SCHEMA);
      IMMUTABLE_SEGMENT_DATA_MANAGER =
          createImmutableSegmentDataManager(INDEX_LOADING_CONFIG, "basicSegment", generateRows());
      BASE_TABLE_DATA_MANAGER = BaseTableDataManagerTest.createTableManager();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  protected static TableConfigBuilder getTableConfigBuilder() {
    return new TableConfigBuilder(TableType.OFFLINE).setTableName(DEFAULT_TABLE_NAME)
        .setTimeColumnName(DEFAULT_TIME_COLUMN_NAME)
        .setNullHandlingEnabled(true)
        .setNoDictionaryColumns(List.of(TEXT_INDEX_COLUMN, MC_TEXT_INDEX_COLUMN_1, MC_TEXT_INDEX_COLUMN_1));
  }

  protected static Schema getSchema()
      throws IOException {
    return new Schema.SchemaBuilder().addDateTime(DEFAULT_TIME_COLUMN_NAME, FieldSpec.DataType.INT, "1:DAYS:EPOCH",
            "1:DAYS")
        .addDateTime(MS_SINCE_EPOCH_COLUMN_NAME, FieldSpec.DataType.LONG, "1:MILLISECONDS:EPOCH", "1:MILLISECONDS")
        .addSingleValueDimension(PARTITIONED_COLUMN_NAME, FieldSpec.DataType.INT)
        .addSingleValueDimension(MC_TEXT_INDEX_COLUMN_1, FieldSpec.DataType.STRING)
        .addSingleValueDimension(MC_TEXT_INDEX_COLUMN_2, FieldSpec.DataType.STRING)
        .addSingleValueDimension(TEXT_INDEX_COLUMN, FieldSpec.DataType.STRING)
        .addMultiValueDimension(TEXT_INDEX_COLUMN_MV, FieldSpec.DataType.STRING)
        .addSingleValueDimension(JSON_INDEX_COLUMN, FieldSpec.DataType.JSON)
        .addSingleValueDimension(FST_TEST_COLUMN, FieldSpec.DataType.STRING)
        .addSingleValueDimension(NULL_VALUE_COLUMN, FieldSpec.DataType.STRING)
        .addSingleValueDimension(DISTANCE_COLUMN_NAME, FieldSpec.DataType.INT)
        .addSingleValueDimension(CARRIER_COLUMN_NAME, FieldSpec.DataType.STRING)
        .build();
  }

  protected static List<GenericRow> generateRows() {
    GenericRow row0 = new GenericRow();
    row0.putValue(DEFAULT_TIME_COLUMN_NAME, 20000);
    row0.putValue(MS_SINCE_EPOCH_COLUMN_NAME, 20000L * 86400 * 1000);
    row0.putValue(MC_TEXT_INDEX_COLUMN_1, "mc_text_index_column_1");
    row0.putValue(MC_TEXT_INDEX_COLUMN_2, "mc_text_index_column_2");
    row0.putValue(TEXT_INDEX_COLUMN, "text_index_column_0");
    row0.putValue(TEXT_INDEX_COLUMN_MV, "text_index_column_0");
    row0.putValue(JSON_INDEX_COLUMN, "{\"a\":\"b\"}");
    row0.putValue(FST_TEST_COLUMN, "fst_test_column_0");
    row0.putValue(PARTITIONED_COLUMN_NAME, 0);
    row0.putValue(DISTANCE_COLUMN_NAME, 1000);
    row0.putValue(CARRIER_COLUMN_NAME, "c0");

    GenericRow row1 = new GenericRow();
    row1.putValue(DEFAULT_TIME_COLUMN_NAME, 20001);
    row1.putValue(MS_SINCE_EPOCH_COLUMN_NAME, 20001L * 86400 * 1000);
    row1.putValue(MC_TEXT_INDEX_COLUMN_1, "mc_text_index_column_1");
    row1.putValue(MC_TEXT_INDEX_COLUMN_2, "mc_text_index_column_2");
    row1.putValue(TEXT_INDEX_COLUMN, "text_index_column_0");
    row1.putValue(TEXT_INDEX_COLUMN_MV, "text_index_column_1");
    row1.putValue(JSON_INDEX_COLUMN, "{\"a\":\"b\"}");
    row1.putValue(FST_TEST_COLUMN, "fst_test_column_1");
    row1.putValue(PARTITIONED_COLUMN_NAME, 1);
    row1.putValue(DISTANCE_COLUMN_NAME, 1000);
    row1.putValue(CARRIER_COLUMN_NAME, "c1");

    GenericRow row2 = new GenericRow();
    row2.putValue(DEFAULT_TIME_COLUMN_NAME, 20002);
    row2.putValue(MS_SINCE_EPOCH_COLUMN_NAME, 20002L * 86400 * 1000);
    row2.putValue(MC_TEXT_INDEX_COLUMN_1, "mc_text_index_column_1");
    row2.putValue(MC_TEXT_INDEX_COLUMN_2, "mc_text_index_column_2");
    row2.putValue(TEXT_INDEX_COLUMN, "text_index_column_0");
    row2.putValue(TEXT_INDEX_COLUMN_MV, "text_index_column_2");
    row2.putValue(JSON_INDEX_COLUMN, "{\"a\":\"b\"}");
    row2.putValue(FST_TEST_COLUMN, "fst_test_column_2");
    row2.putValue(PARTITIONED_COLUMN_NAME, 2);
    row2.putValue(DISTANCE_COLUMN_NAME, 2000);
    row2.putValue(CARRIER_COLUMN_NAME, "c0");

    return List.of(row0, row2, row1);
  }

  private static File createSegment(IndexLoadingConfig indexLoadingConfig, String segmentName, List<GenericRow> rows)
      throws Exception {
    SegmentGeneratorConfig config =
        new SegmentGeneratorConfig(indexLoadingConfig.getTableConfig(), indexLoadingConfig.getSchema());
    config.setOutDir(TABLE_DATA_DIR.getAbsolutePath());
    config.setSegmentName(segmentName);
    config.setSegmentVersion(SegmentVersion.v3);

    //Create ONE row
    SegmentIndexCreationDriverImpl driver = new SegmentIndexCreationDriverImpl();
    driver.init(config, new GenericRowRecordReader(rows));
    driver.build();
    return new File(TABLE_DATA_DIR, segmentName);
  }

  private static ImmutableSegmentDataManager createImmutableSegmentDataManager(IndexLoadingConfig indexLoadingConfig,
      String segmentName, List<GenericRow> rows)
      throws Exception {
    ImmutableSegmentDataManager segmentDataManager = mock(ImmutableSegmentDataManager.class);
    when(segmentDataManager.getSegmentName()).thenReturn(segmentName);
    File indexDir = createSegment(indexLoadingConfig, segmentName, rows);
    ImmutableSegment immutableSegment = ImmutableSegmentLoader.load(indexDir, indexLoadingConfig,
        BaseTableDataManagerTest.SEGMENT_OPERATIONS_THROTTLER);
    when(segmentDataManager.getSegment()).thenReturn(immutableSegment);
    return segmentDataManager;
  }

  @BeforeMethod
  void setTestName(Method method) {
    _testName = method.getName();
  }

  @Test
  void testAddTimeColumn()
      throws Exception {
    TableConfig tableConfig = new TableConfigBuilder(TableType.OFFLINE).setTableName(DEFAULT_TABLE_NAME)
        .setNullHandlingEnabled(true)
        .setNoDictionaryColumns(List.of(TEXT_INDEX_COLUMN, MC_TEXT_INDEX_COLUMN_1, MC_TEXT_INDEX_COLUMN_2))
        .build();
    Schema schema = new Schema.SchemaBuilder()
        .addSingleValueDimension(MC_TEXT_INDEX_COLUMN_1, FieldSpec.DataType.STRING)
        .addSingleValueDimension(MC_TEXT_INDEX_COLUMN_2, FieldSpec.DataType.STRING)
        .addSingleValueDimension(TEXT_INDEX_COLUMN, FieldSpec.DataType.STRING)
        .addSingleValueDimension(JSON_INDEX_COLUMN, FieldSpec.DataType.JSON)
        .addSingleValueDimension(FST_TEST_COLUMN, FieldSpec.DataType.STRING)
        .build();
    IndexLoadingConfig indexLoadingConfig = new IndexLoadingConfig(tableConfig, schema);

    GenericRow row = new GenericRow();
    row.putValue(MC_TEXT_INDEX_COLUMN_1, "mc_text_index_column_1");
    row.putValue(MC_TEXT_INDEX_COLUMN_2, "mc_text_index_column_2");
    row.putValue(TEXT_INDEX_COLUMN, "text_index_column");
    row.putValue(JSON_INDEX_COLUMN, "{\"a\":\"b\"}");
    row.putValue(FST_TEST_COLUMN, "fst_test_column");

    ImmutableSegmentDataManager segmentDataManager =
        createImmutableSegmentDataManager(indexLoadingConfig, "noChanges", List.of(row));
    BaseTableDataManager tableDataManager = BaseTableDataManagerTest.createTableManager();

    StaleSegment response = tableDataManager.isSegmentStale(indexLoadingConfig, segmentDataManager);
    assertFalse(response.isStale());

    // Test new time column
    response = tableDataManager.isSegmentStale(INDEX_LOADING_CONFIG, segmentDataManager);
    assertTrue(response.isStale());
    assertEquals(response.getReason(), "time column");
  }

  @Test
  void testChangeTimeColumn() {
    TableConfig tableConfig = getTableConfigBuilder().setTimeColumnName(MS_SINCE_EPOCH_COLUMN_NAME).build();
    StaleSegment response = BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(tableConfig, SCHEMA),
        IMMUTABLE_SEGMENT_DATA_MANAGER);
    assertTrue(response.isStale());
    assertEquals(response.getReason(), "time column");
  }

  @Test
  void testRemoveColumn()
      throws Exception {
    Schema schema = getSchema();
    schema.removeField(TEXT_INDEX_COLUMN);
    StaleSegment response = BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(TABLE_CONFIG, schema),
        IMMUTABLE_SEGMENT_DATA_MANAGER);
    assertTrue(response.isStale());
    assertEquals(response.getReason(), "column deleted: textColumn");
  }

  @Test
  void testFieldType()
      throws Exception {
    Schema schema = getSchema();
    schema.removeField(TEXT_INDEX_COLUMN);
    schema.addField(new MetricFieldSpec(TEXT_INDEX_COLUMN, FieldSpec.DataType.STRING, true));
    StaleSegment response = BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(TABLE_CONFIG, schema),
        IMMUTABLE_SEGMENT_DATA_MANAGER);
    assertTrue(response.isStale());
    assertEquals(response.getReason(), "field type changed: textColumn");
  }

  @Test
  void testChangeDataType()
      throws Exception {
    Schema schema = getSchema();
    schema.removeField(TEXT_INDEX_COLUMN);
    schema.addField(new DimensionFieldSpec(TEXT_INDEX_COLUMN, FieldSpec.DataType.INT, true));
    StaleSegment response = BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(TABLE_CONFIG, schema),
        IMMUTABLE_SEGMENT_DATA_MANAGER);
    assertTrue(response.isStale());
    assertEquals(response.getReason(), "data type changed: textColumn");
  }

  @Test
  void testChangeToMV()
      throws Exception {
    Schema schema = getSchema();
    schema.removeField(TEXT_INDEX_COLUMN);
    schema.addField(new DimensionFieldSpec(TEXT_INDEX_COLUMN, FieldSpec.DataType.STRING, false));
    StaleSegment response = BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(TABLE_CONFIG, schema),
        IMMUTABLE_SEGMENT_DATA_MANAGER);
    assertTrue(response.isStale());
    assertEquals(response.getReason(), "single / multi value changed: textColumn");
  }

  @Test
  void testChangeToSV()
      throws Exception {
    Schema schema = getSchema();
    schema.removeField(TEXT_INDEX_COLUMN_MV);
    schema.addField(new DimensionFieldSpec(TEXT_INDEX_COLUMN_MV, FieldSpec.DataType.STRING, true));
    StaleSegment response = BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(TABLE_CONFIG, schema),
        IMMUTABLE_SEGMENT_DATA_MANAGER);
    assertTrue(response.isStale());
    assertEquals(response.getReason(), "single / multi value changed: textColumnMV");
  }

  @Test
  void testSortColumnMismatch() {
    // Check with a column that is not sorted
    TableConfig tableConfig = getTableConfigBuilder().setSortedColumn(MS_SINCE_EPOCH_COLUMN_NAME).build();
    IndexLoadingConfig indexLoadingConfig = new IndexLoadingConfig(tableConfig, SCHEMA);
    StaleSegment response = BASE_TABLE_DATA_MANAGER.isSegmentStale(indexLoadingConfig, IMMUTABLE_SEGMENT_DATA_MANAGER);
    assertTrue(response.isStale());
    assertEquals(response.getReason(), "sort column changed: MilliSecondsSinceEpoch");
    // Check with a column that is sorted
    tableConfig.getIndexingConfig().setSortedColumn(List.of(TEXT_INDEX_COLUMN));
    assertFalse(BASE_TABLE_DATA_MANAGER.isSegmentStale(indexLoadingConfig, IMMUTABLE_SEGMENT_DATA_MANAGER).isStale());
  }

  @DataProvider(name = "testFilterArgs")
  private Object[][] testFilterArgs() {
    return new Object[][]{
        {
            "withBloomFilter", getTableConfigBuilder().setBloomFilterColumns(
            List.of(TEXT_INDEX_COLUMN)).build(), "bloom filter changed: textColumn"
        }, {
        "withJsonIndex", getTableConfigBuilder().setJsonIndexColumns(
        List.of(JSON_INDEX_COLUMN)).build(), "json index changed: jsonField"
    }, {
        "withTextIndex", getTableConfigBuilder().setFieldConfigList(List.of(
        new FieldConfig(TEXT_INDEX_COLUMN, FieldConfig.EncodingType.DICTIONARY, List.of(FieldConfig.IndexType.TEXT),
            null, null))).build(), "text index changed: textColumn"
    }, {
        "withFstIndex", getTableConfigBuilder().setFieldConfigList(List.of(
        new FieldConfig(FST_TEST_COLUMN, FieldConfig.EncodingType.DICTIONARY, List.of(FieldConfig.IndexType.FST), null,
            Map.of(FieldConfig.TEXT_FST_TYPE,
                FieldConfig.TEXT_NATIVE_FST_LITERAL)))).build(), "fst index changed: DestCityName"
    }, {
        "withRangeFilter", getTableConfigBuilder().setRangeIndexColumns(
        List.of(MS_SINCE_EPOCH_COLUMN_NAME)).build(), "range index changed: MilliSecondsSinceEpoch"
    }
    };
  }

  @Test(dataProvider = "testFilterArgs")
  void testFilter(String segmentName, TableConfig tableConfigWithFilter, String expectedReason)
      throws Exception {
    IndexLoadingConfig indexLoadingConfig = new IndexLoadingConfig(tableConfigWithFilter, SCHEMA);
    ImmutableSegmentDataManager segmentWithFilter =
        createImmutableSegmentDataManager(indexLoadingConfig, segmentName, generateRows());

    // When TableConfig has a filter but segment does not have, needRefresh is true.
    StaleSegment response = BASE_TABLE_DATA_MANAGER.isSegmentStale(indexLoadingConfig, IMMUTABLE_SEGMENT_DATA_MANAGER);
    assertTrue(response.isStale());
    assertEquals(response.getReason(), expectedReason);

    // When TableConfig does not have a filter but segment has, needRefresh is true
    response = BASE_TABLE_DATA_MANAGER.isSegmentStale(INDEX_LOADING_CONFIG, segmentWithFilter);
    assertTrue(response.isStale());
    assertEquals(response.getReason(), expectedReason);

    // When TableConfig has a filter AND segment also has a filter, needRefresh is false
    assertFalse(BASE_TABLE_DATA_MANAGER.isSegmentStale(indexLoadingConfig, segmentWithFilter).isStale());
  }

  @Test
  void testPartition()
      throws Exception {
    TableConfig partitionedTableConfig = getTableConfigBuilder().setSegmentPartitionConfig(new SegmentPartitionConfig(
        Map.of(PARTITIONED_COLUMN_NAME, new ColumnPartitionConfig(PARTITION_FUNCTION_NAME, NUM_PARTITIONS)))).build();
    IndexLoadingConfig indexLoadingConfig = new IndexLoadingConfig(partitionedTableConfig, SCHEMA);
    ImmutableSegmentDataManager segmentWithPartition =
        createImmutableSegmentDataManager(indexLoadingConfig, "partitionWithModulo", generateRows());

    // when segment has no partition AND tableConfig has partitions then needRefresh = true
    StaleSegment response = BASE_TABLE_DATA_MANAGER.isSegmentStale(indexLoadingConfig, IMMUTABLE_SEGMENT_DATA_MANAGER);
    assertTrue(response.isStale());
    assertEquals(response.getReason(), "partition function added: partitionedColumn");

    // when segment has partitions AND tableConfig has no partitions, then needRefresh = false
    assertFalse(BASE_TABLE_DATA_MANAGER.isSegmentStale(INDEX_LOADING_CONFIG, segmentWithPartition).isStale());

    // when # of partitions is different, then needRefresh = true
    TableConfig partitionedTableConfig40 = getTableConfigBuilder().setSegmentPartitionConfig(new SegmentPartitionConfig(
        Map.of(PARTITIONED_COLUMN_NAME, new ColumnPartitionConfig(PARTITION_FUNCTION_NAME, 40)))).build();
    response = BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(partitionedTableConfig40, SCHEMA),
        segmentWithPartition);
    assertTrue(response.isStale());
    assertEquals(response.getReason(), "num partitions changed: partitionedColumn");

    // when partition function is different, then needRefresh = true
    TableConfig partitionedTableConfigMurmur = getTableConfigBuilder().setSegmentPartitionConfig(
        new SegmentPartitionConfig(
            Map.of(PARTITIONED_COLUMN_NAME, new ColumnPartitionConfig("murmur", NUM_PARTITIONS)))).build();
    response = BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(partitionedTableConfigMurmur, SCHEMA),
        segmentWithPartition);
    assertTrue(response.isStale());
    assertEquals(response.getReason(), "partition function name changed: partitionedColumn");
  }

  @Test
  void testNullValueVector()
      throws Exception {
    TableConfig withoutNullHandling = getTableConfigBuilder().setNullHandlingEnabled(false).build();
    IndexLoadingConfig indexLoadingConfig = new IndexLoadingConfig(withoutNullHandling, SCHEMA);
    ImmutableSegmentDataManager segmentWithoutNullHandling =
        createImmutableSegmentDataManager(indexLoadingConfig, "withoutNullHandling", generateRows());

    // If null handling is removed from table config AND segment has NVV, then NVV can be removed. needRefresh = true
    StaleSegment response = BASE_TABLE_DATA_MANAGER.isSegmentStale(indexLoadingConfig, IMMUTABLE_SEGMENT_DATA_MANAGER);
    assertTrue(response.isStale());
    assertEquals(response.getReason(), "null value vector index removed from column: NullValueColumn");

    // if NVV is added to table config AND segment does not have NVV, then it cannot be added. needRefresh = false
    assertFalse(BASE_TABLE_DATA_MANAGER.isSegmentStale(INDEX_LOADING_CONFIG, segmentWithoutNullHandling).isStale());
  }

  @Test
  public void testAddMultiColumnTextIndex() {
    MultiColumnTextIndexConfig newIndex =
        new MultiColumnTextIndexConfig(List.of(MC_TEXT_INDEX_COLUMN_1, MC_TEXT_INDEX_COLUMN_2));
    TableConfig tableConfig =
        getTableConfigBuilder().setMultiColumnTextIndexConfig(newIndex).build();
    assertTrue(BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(tableConfig, SCHEMA),
        IMMUTABLE_SEGMENT_DATA_MANAGER).isStale());
  }

  @Test
  public void testMultiColumnTextIndexWithDifferentColumns()
      throws Exception {
    TableConfig tableConfig = getTableConfigBuilder()
        .setMultiColumnTextIndexConfig(
            new MultiColumnTextIndexConfig(List.of(MC_TEXT_INDEX_COLUMN_1))).build();

    ImmutableSegmentDataManager segmentDataManager =
        createImmutableSegmentDataManager(new IndexLoadingConfig(tableConfig, SCHEMA), _testName, generateRows());

    TableConfig newTableConfig = getTableConfigBuilder()
        .setMultiColumnTextIndexConfig(
            new MultiColumnTextIndexConfig(List.of(MC_TEXT_INDEX_COLUMN_1, MC_TEXT_INDEX_COLUMN_2)))
        .build();

    assertTrue(
        BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(newTableConfig, SCHEMA), segmentDataManager)
            .isStale());
  }

  @Test
  public void testMultiColumnTextIndexWithDifferentSharedProperties()
      throws Exception {
    TableConfig tableConfig = getTableConfigBuilder()
        .setMultiColumnTextIndexConfig(
            new MultiColumnTextIndexConfig(List.of(MC_TEXT_INDEX_COLUMN_1, MC_TEXT_INDEX_COLUMN_2), null, null))
        .build();

    ImmutableSegmentDataManager segmentDataManager =
        createImmutableSegmentDataManager(new IndexLoadingConfig(tableConfig, SCHEMA), _testName, generateRows());

    TableConfig newTableConfig = getTableConfigBuilder()
        .setMultiColumnTextIndexConfig(
            new MultiColumnTextIndexConfig(List.of(MC_TEXT_INDEX_COLUMN_1, MC_TEXT_INDEX_COLUMN_2),
                Map.of(FieldConfig.TEXT_INDEX_CASE_SENSITIVE_KEY, "true"), null))
        .build();

    assertTrue(
        BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(newTableConfig, SCHEMA), segmentDataManager)
            .isStale());
  }

  @Test
  public void testMultiColumnTextIndexWithDifferentColumnProperties()
      throws Exception {
    TableConfig tableConfig = getTableConfigBuilder()
        .setMultiColumnTextIndexConfig(
            new MultiColumnTextIndexConfig(List.of(MC_TEXT_INDEX_COLUMN_1, MC_TEXT_INDEX_COLUMN_2), null, null))
        .build();

    ImmutableSegmentDataManager segmentDataManager =
        createImmutableSegmentDataManager(new IndexLoadingConfig(tableConfig, SCHEMA), _testName, generateRows());

    TableConfig newTableConfig = getTableConfigBuilder()
        .setMultiColumnTextIndexConfig(
            new MultiColumnTextIndexConfig(List.of(MC_TEXT_INDEX_COLUMN_1, MC_TEXT_INDEX_COLUMN_2),
                null, Map.of(MC_TEXT_INDEX_COLUMN_1, Map.of(FieldConfig.TEXT_INDEX_CASE_SENSITIVE_KEY, "true"))))
        .build();

    assertTrue(
        BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(newTableConfig, SCHEMA), segmentDataManager)
            .isStale());
  }

  @Test
  public void addStartreeIndex() {
    // Test 1 : Adding a StarTree index should trigger segment refresh.

    StarTreeIndexConfig starTreeIndexConfig =
        new StarTreeIndexConfig(List.of("Carrier"), null, List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), null,
            100);
    TableConfig tableConfig = getTableConfigBuilder().setStarTreeIndexConfigs(List.of(starTreeIndexConfig)).build();
    assertTrue(BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(tableConfig, SCHEMA),
        IMMUTABLE_SEGMENT_DATA_MANAGER).isStale());
  }

  @Test
  public void testStarTreeIndexWithDifferentColumn()
      throws Exception {
    // Test 2: Adding a new StarTree index with split dimension column of same size but with different element should
    // trigger segment refresh.

    // Create a segment with StarTree index on Carrier.
    StarTreeIndexConfig starTreeIndexConfig =
        new StarTreeIndexConfig(List.of("Carrier"), null, List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), null,
            100);
    TableConfig tableConfig = getTableConfigBuilder().setStarTreeIndexConfigs(List.of(starTreeIndexConfig)).build();
    ImmutableSegmentDataManager segmentDataManager =
        createImmutableSegmentDataManager(new IndexLoadingConfig(tableConfig, SCHEMA), _testName, generateRows());

    // Create a StarTree index on Distance.
    StarTreeIndexConfig newStarTreeIndexConfig =
        new StarTreeIndexConfig(List.of("Distance"), null, List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), null,
            100);
    TableConfig newTableConfig =
        getTableConfigBuilder().setStarTreeIndexConfigs(List.of(newStarTreeIndexConfig)).build();
    assertTrue(
        BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(newTableConfig, SCHEMA), segmentDataManager)
            .isStale());
  }

  @Test
  public void testStarTreeIndexWithManyColumns()
      throws Exception {
    // Test 3: Adding a new StarTree index with split dimension columns of different size should trigger segment
    // refresh.

    // Create a segment with StarTree index on Carrier.
    StarTreeIndexConfig starTreeIndexConfig =
        new StarTreeIndexConfig(List.of("Carrier"), null, List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), null,
            100);
    TableConfig tableConfig = getTableConfigBuilder().setStarTreeIndexConfigs(List.of(starTreeIndexConfig)).build();
    ImmutableSegmentDataManager segmentDataManager =
        createImmutableSegmentDataManager(new IndexLoadingConfig(tableConfig, SCHEMA), _testName, generateRows());

    StarTreeIndexConfig newStarTreeIndexConfig = new StarTreeIndexConfig(Arrays.asList("Carrier", "Distance"), null,
        List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), null, 100);
    TableConfig newTableConfig =
        getTableConfigBuilder().setStarTreeIndexConfigs(List.of(newStarTreeIndexConfig)).build();
    assertTrue(
        BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(newTableConfig, SCHEMA), segmentDataManager)
            .isStale());
  }

  @Test
  public void testStartIndexWithDifferentOrder()
      throws Exception {
    // Test 4: Adding a new StarTree index with the differently ordered split dimension columns should trigger
    // segment refresh.

    // Create a segment with StarTree index on Carrier, Distance.
    StarTreeIndexConfig starTreeIndexConfig = new StarTreeIndexConfig(Arrays.asList("Carrier", "Distance"), null,
        List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), null, 100);
    TableConfig tableConfig = getTableConfigBuilder().setStarTreeIndexConfigs(List.of(starTreeIndexConfig)).build();
    ImmutableSegmentDataManager segmentDataManager =
        createImmutableSegmentDataManager(new IndexLoadingConfig(tableConfig, SCHEMA), _testName, generateRows());

    // Create a StarTree index.
    StarTreeIndexConfig newStarTreeIndexConfig = new StarTreeIndexConfig(Arrays.asList("Distance", "Carrier"), null,
        List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), null, 100);
    TableConfig newTableConfig =
        getTableConfigBuilder().setStarTreeIndexConfigs(List.of(newStarTreeIndexConfig)).build();
    assertTrue(
        BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(newTableConfig, SCHEMA), segmentDataManager)
            .isStale());
  }

  @Test
  void testStarTreeIndexWithSkipDimCols()
      throws Exception {
    // Test 5: Adding a new StarTree index with skipped dimension columns should trigger segment refresh.
    // Create a segment with StarTree index on Carrier, Distance.

    StarTreeIndexConfig starTreeIndexConfig = new StarTreeIndexConfig(Arrays.asList("Carrier", "Distance"), null,
        List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), null, 100);
    TableConfig tableConfig = getTableConfigBuilder().setStarTreeIndexConfigs(List.of(starTreeIndexConfig)).build();
    ImmutableSegmentDataManager segmentDataManager =
        createImmutableSegmentDataManager(new IndexLoadingConfig(tableConfig, SCHEMA), _testName, generateRows());

    // Create a StarTree index.
    StarTreeIndexConfig newStarTreeIndexConfig =
        new StarTreeIndexConfig(Arrays.asList("Carrier", "Distance"), Arrays.asList("Carrier", "Distance"),
            List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), null, 100);
    TableConfig newTableConfig =
        getTableConfigBuilder().setStarTreeIndexConfigs(List.of(newStarTreeIndexConfig)).build();
    assertTrue(
        BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(newTableConfig, SCHEMA), segmentDataManager)
            .isStale());
  }

  @Test
  void testStarTreeIndexWithDiffOrderSkipDimCols()
      throws Exception {
    // Test 6: Adding a new StarTree index with skipped dimension columns in different order should not trigger
    // segment refresh.

    StarTreeIndexConfig starTreeIndexConfig =
        new StarTreeIndexConfig(Arrays.asList("Carrier", "Distance"), Arrays.asList("Carrier", "Distance"),
            List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), null, 100);
    TableConfig tableConfig = getTableConfigBuilder().setStarTreeIndexConfigs(List.of(starTreeIndexConfig)).build();
    ImmutableSegmentDataManager segmentDataManager =
        createImmutableSegmentDataManager(new IndexLoadingConfig(tableConfig, SCHEMA), _testName, generateRows());

    StarTreeIndexConfig newStarTreeIndexConfig =
        new StarTreeIndexConfig(Arrays.asList("Carrier", "Distance"), Arrays.asList("Distance", "Carrier"),
            List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), null, 100);
    TableConfig newTableConfig =
        getTableConfigBuilder().setStarTreeIndexConfigs(List.of(newStarTreeIndexConfig)).build();
    assertFalse(
        BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(newTableConfig, SCHEMA), segmentDataManager)
            .isStale());
  }

  @Test
  void testStarTreeIndexRemoveSkipDimCols()
      throws Exception {
    // Test 7: Adding a new StarTree index with removed skipped-dimension column should trigger segment refresh.

    StarTreeIndexConfig starTreeIndexConfig =
        new StarTreeIndexConfig(Arrays.asList("Carrier", "Distance"), Arrays.asList("Carrier", "Distance"),
            List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), null, 100);
    TableConfig tableConfig = getTableConfigBuilder().setStarTreeIndexConfigs(List.of(starTreeIndexConfig)).build();
    ImmutableSegmentDataManager segmentDataManager =
        createImmutableSegmentDataManager(new IndexLoadingConfig(tableConfig, SCHEMA), _testName, generateRows());

    StarTreeIndexConfig newStarTreeIndexConfig = new StarTreeIndexConfig(Arrays.asList("Carrier", "Distance"), null,
        List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), null, 100);
    TableConfig newTableConfig =
        getTableConfigBuilder().setStarTreeIndexConfigs(List.of(newStarTreeIndexConfig)).build();
    assertTrue(
        BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(newTableConfig, SCHEMA), segmentDataManager)
            .isStale());
  }

  @Test
  void testStarTreeIndexAddAggFn()
      throws Exception {
    // Test 8: Adding a new StarTree index with an added metrics aggregation function should trigger segment refresh.

    StarTreeIndexConfig starTreeIndex = new StarTreeIndexConfig(Arrays.asList("Carrier", "Distance"), null,
        List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), null, 100);
    TableConfig tableConfig = getTableConfigBuilder().setStarTreeIndexConfigs(List.of(starTreeIndex)).build();
    ImmutableSegmentDataManager segmentDataManager =
        createImmutableSegmentDataManager(new IndexLoadingConfig(tableConfig, SCHEMA), _testName, generateRows());

    StarTreeIndexConfig newStarTreeIndexConfig = new StarTreeIndexConfig(Arrays.asList("Carrier", "Distance"), null,
        Arrays.asList(AggregationFunctionColumnPair.COUNT_STAR_NAME, "MAX__Distance"), null, 100);
    TableConfig newTableConfig =
        getTableConfigBuilder().setStarTreeIndexConfigs(List.of(newStarTreeIndexConfig)).build();
    assertTrue(
        BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(newTableConfig, SCHEMA), segmentDataManager)
            .isStale());
  }

  @Test
  void testStarTreeIndexDiffOrderAggFn()
      throws Exception {
    // Test 9: Adding a new StarTree index with the same aggregation functions but in different order should not
    // trigger segment refresh.

    StarTreeIndexConfig starTreeIndexConfig = new StarTreeIndexConfig(Arrays.asList("Carrier", "Distance"), null,
        Arrays.asList(AggregationFunctionColumnPair.COUNT_STAR_NAME, "MAX__Distance"), null, 100);
    TableConfig tableConfig = getTableConfigBuilder().setStarTreeIndexConfigs(List.of(starTreeIndexConfig)).build();
    ImmutableSegmentDataManager segmentDataManager =
        createImmutableSegmentDataManager(new IndexLoadingConfig(tableConfig, SCHEMA), _testName, generateRows());

    StarTreeIndexConfig newStarTreeIndexConfig = new StarTreeIndexConfig(Arrays.asList("Carrier", "Distance"), null,
        Arrays.asList("MAX__Distance", AggregationFunctionColumnPair.COUNT_STAR_NAME), null, 100);
    TableConfig newTableConfig =
        getTableConfigBuilder().setStarTreeIndexConfigs(List.of(newStarTreeIndexConfig)).build();
    assertFalse(
        BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(newTableConfig, SCHEMA), segmentDataManager)
            .isStale());
  }

  @Test
  void testStarTreeIndexRemoveAggFn()
      throws Exception {
    // Test 10: removing an aggregation function through aggregation config should trigger segment refresh.

    StarTreeIndexConfig starTreeIndexConfig = new StarTreeIndexConfig(Arrays.asList("Carrier", "Distance"), null,
        Arrays.asList("MAX__Distance", AggregationFunctionColumnPair.COUNT_STAR_NAME), null, 100);

    TableConfig tableConfig = getTableConfigBuilder().setStarTreeIndexConfigs(List.of(starTreeIndexConfig)).build();
    ImmutableSegmentDataManager segmentDataManager =
        createImmutableSegmentDataManager(new IndexLoadingConfig(tableConfig, SCHEMA), _testName, generateRows());

    StarTreeIndexConfig newStarTreeIndexConfig =
        new StarTreeIndexConfig(Arrays.asList("Carrier", "Distance"), null, null,
            List.of(new StarTreeAggregationConfig("Distance", "MAX")), 100);
    TableConfig newTableConfig =
        getTableConfigBuilder().setStarTreeIndexConfigs(List.of(newStarTreeIndexConfig)).build();
    assertTrue(
        BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(newTableConfig, SCHEMA), segmentDataManager)
            .isStale());
  }

  @Test
  void testStarTreeIndexNewMetricAgg()
      throws Exception {
    // Test 11 : Adding a new metric aggregation function through functionColumnPairs should trigger segment refresh.

    StarTreeAggregationConfig aggregationConfig = new StarTreeAggregationConfig("Distance", "MAX");
    StarTreeIndexConfig starTreeIndexConfig =
        new StarTreeIndexConfig(Arrays.asList("Carrier", "Distance"), null, null, List.of(aggregationConfig), 100);
    TableConfig tableConfig = getTableConfigBuilder().setStarTreeIndexConfigs(List.of(starTreeIndexConfig)).build();
    ImmutableSegmentDataManager segmentDataManager =
        createImmutableSegmentDataManager(new IndexLoadingConfig(tableConfig, SCHEMA), _testName, generateRows());

    // Create a StarTree index.
    StarTreeIndexConfig newStarTreeIndexConfig = new StarTreeIndexConfig(Arrays.asList("Carrier", "Distance"), null,
        List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), List.of(aggregationConfig), 100);
    TableConfig newTableConfig =
        getTableConfigBuilder().setStarTreeIndexConfigs(List.of(newStarTreeIndexConfig)).build();
    assertTrue(
        BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(newTableConfig, SCHEMA), segmentDataManager)
            .isStale());
  }

  @Test
  void testStarTreeIndexDiffOrderAggFn2()
      throws Exception {
    // Test 12: Adding a new StarTree index with different ordered aggregation functions through aggregation config
    // should not trigger segment refresh.

    StarTreeAggregationConfig aggregationConfig = new StarTreeAggregationConfig("Distance", "MAX");
    StarTreeIndexConfig starTreeIndexConfig = new StarTreeIndexConfig(Arrays.asList("Carrier", "Distance"), null,
        List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), List.of(aggregationConfig), 100);
    TableConfig tableConfig = getTableConfigBuilder().setStarTreeIndexConfigs(List.of(starTreeIndexConfig)).build();
    ImmutableSegmentDataManager segmentDataManager =
        createImmutableSegmentDataManager(new IndexLoadingConfig(tableConfig, SCHEMA), _testName, generateRows());

    StarTreeAggregationConfig starTreeAggregationConfig2 = new StarTreeAggregationConfig("*", "count");
    StarTreeIndexConfig newStarTreeIndexConfig =
        new StarTreeIndexConfig(Arrays.asList("Carrier", "Distance"), null, null,
            Arrays.asList(starTreeAggregationConfig2, aggregationConfig), 100);
    TableConfig newTableConfig =
        getTableConfigBuilder().setStarTreeIndexConfigs(List.of(newStarTreeIndexConfig)).build();
    assertFalse(
        BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(newTableConfig, SCHEMA), segmentDataManager)
            .isStale());
  }

  @Test
  void testStarTreeIndexMaxLeafNode()
      throws Exception {
    StarTreeIndexConfig starTreeIndexConfig =
        new StarTreeIndexConfig(List.of("Carrier"), null, List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), null,
            100);
    TableConfig tableConfig = getTableConfigBuilder().setStarTreeIndexConfigs(List.of(starTreeIndexConfig)).build();
    ImmutableSegmentDataManager segmentDataManager =
        createImmutableSegmentDataManager(new IndexLoadingConfig(tableConfig, SCHEMA), _testName, generateRows());

    StarTreeIndexConfig newStarTreeIndexConfig =
        new StarTreeIndexConfig(List.of("Carrier"), null, List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), null,
            10);
    TableConfig newTableConfig =
        getTableConfigBuilder().setStarTreeIndexConfigs(List.of(newStarTreeIndexConfig)).build();
    assertTrue(
        BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(newTableConfig, SCHEMA), segmentDataManager)
            .isStale());
  }

  @Test
  void testStarTreeIndexRemove()
      throws Exception {
    StarTreeIndexConfig starTreeIndexConfig =
        new StarTreeIndexConfig(List.of("Carrier"), null, List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), null,
            100);
    TableConfig tableConfig = getTableConfigBuilder().setStarTreeIndexConfigs(List.of(starTreeIndexConfig)).build();
    ImmutableSegmentDataManager segmentDataManager =
        createImmutableSegmentDataManager(new IndexLoadingConfig(tableConfig, SCHEMA), _testName, generateRows());
    assertTrue(BASE_TABLE_DATA_MANAGER.isSegmentStale(INDEX_LOADING_CONFIG, segmentDataManager).isStale());
  }

  @Test
  void testStarTreeIndexAddMultiple()
      throws Exception {
    // Test 15: Add multiple StarTree Indexes should trigger segment refresh.

    StarTreeIndexConfig starTreeIndexConfig =
        new StarTreeIndexConfig(List.of("Carrier"), null, List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), null,
            100);
    TableConfig tableConfig = getTableConfigBuilder().setStarTreeIndexConfigs(List.of(starTreeIndexConfig)).build();
    ImmutableSegmentDataManager segmentDataManager =
        createImmutableSegmentDataManager(new IndexLoadingConfig(tableConfig, SCHEMA), _testName, generateRows());

    StarTreeIndexConfig newStarTreeIndexConfig =
        new StarTreeIndexConfig(List.of("Distance"), null, List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), null,
            100);
    TableConfig newTableConfig =
        getTableConfigBuilder().setStarTreeIndexConfigs(List.of(starTreeIndexConfig, newStarTreeIndexConfig)).build();
    assertTrue(
        BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(newTableConfig, SCHEMA), segmentDataManager)
            .isStale());
  }

  @Test
  void testStarTreeIndexEnableDefault()
      throws Exception {
    // Test 16: Enabling default StarTree index should trigger a segment refresh.

    StarTreeIndexConfig starTreeIndexConfig =
        new StarTreeIndexConfig(List.of("Carrier"), null, List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), null,
            100);
    TableConfig tableConfig = getTableConfigBuilder().setStarTreeIndexConfigs(List.of(starTreeIndexConfig)).build();
    ImmutableSegmentDataManager segmentDataManager =
        createImmutableSegmentDataManager(new IndexLoadingConfig(tableConfig, SCHEMA), _testName, generateRows());

    TableConfig newTableConfig = getTableConfigBuilder().setStarTreeIndexConfigs(List.of(starTreeIndexConfig)).build();
    newTableConfig.getIndexingConfig().setEnableDefaultStarTree(true);
    assertTrue(
        BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(newTableConfig, SCHEMA), segmentDataManager)
            .isStale());
  }

  @Test
  void testStarTreeIndexNoChanges()
      throws Exception {
    // Test 17: Attempting to trigger segment refresh again should not be successful.

    StarTreeIndexConfig starTreeIndexConfig =
        new StarTreeIndexConfig(List.of("Carrier"), null, List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), null,
            100);
    TableConfig tableConfig = getTableConfigBuilder().setStarTreeIndexConfigs(List.of(starTreeIndexConfig)).build();
    IndexLoadingConfig indexLoadingConfig = new IndexLoadingConfig(tableConfig, SCHEMA);
    ImmutableSegmentDataManager segmentDataManager =
        createImmutableSegmentDataManager(indexLoadingConfig, _testName, generateRows());
    assertFalse(BASE_TABLE_DATA_MANAGER.isSegmentStale(indexLoadingConfig, segmentDataManager).isStale());
  }

  @Test
  void testStarTreeIndexDisableDefault()
      throws Exception {
    // Test 18: Disabling default StarTree index should trigger a segment refresh.

    StarTreeIndexConfig starTreeIndexConfig =
        new StarTreeIndexConfig(List.of("Carrier"), null, List.of(AggregationFunctionColumnPair.COUNT_STAR_NAME), null,
            100);
    TableConfig tableConfig = getTableConfigBuilder().setStarTreeIndexConfigs(List.of(starTreeIndexConfig)).build();
    tableConfig.getIndexingConfig().setEnableDefaultStarTree(true);
    ImmutableSegmentDataManager segmentDataManager =
        createImmutableSegmentDataManager(new IndexLoadingConfig(tableConfig, SCHEMA), _testName, generateRows());

    TableConfig newTableConfig = getTableConfigBuilder().setStarTreeIndexConfigs(List.of(starTreeIndexConfig)).build();
    newTableConfig.getIndexingConfig().setEnableDefaultStarTree(false);
    assertTrue(
        BASE_TABLE_DATA_MANAGER.isSegmentStale(new IndexLoadingConfig(newTableConfig, SCHEMA), segmentDataManager)
            .isStale());
  }
}
