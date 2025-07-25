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
package org.apache.pinot.controller.helix.core.rebalance;

import com.google.common.collect.Lists;
import it.unimi.dsi.fastutil.ints.IntIntPair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.apache.pinot.common.assignment.InstancePartitions;
import org.apache.pinot.common.assignment.InstancePartitionsUtils;
import org.apache.pinot.common.restlet.resources.DiskUsageInfo;
import org.apache.pinot.common.tier.TierFactory;
import org.apache.pinot.common.utils.config.TagNameUtils;
import org.apache.pinot.controller.ControllerConf;
import org.apache.pinot.controller.helix.ControllerTest;
import org.apache.pinot.controller.helix.core.assignment.segment.SegmentAssignmentUtils;
import org.apache.pinot.controller.util.ConsumingSegmentInfoReader;
import org.apache.pinot.controller.utils.SegmentMetadataMockUtils;
import org.apache.pinot.controller.validation.ResourceUtilizationInfo;
import org.apache.pinot.core.realtime.impl.fakestream.FakeStreamConfigUtils;
import org.apache.pinot.spi.config.table.RoutingConfig;
import org.apache.pinot.spi.config.table.TableConfig;
import org.apache.pinot.spi.config.table.TableType;
import org.apache.pinot.spi.config.table.TierConfig;
import org.apache.pinot.spi.config.table.assignment.InstanceAssignmentConfig;
import org.apache.pinot.spi.config.table.assignment.InstancePartitionsType;
import org.apache.pinot.spi.config.table.assignment.InstanceReplicaGroupPartitionConfig;
import org.apache.pinot.spi.config.table.assignment.InstanceTagPoolConfig;
import org.apache.pinot.spi.config.table.ingestion.IngestionConfig;
import org.apache.pinot.spi.config.table.ingestion.StreamIngestionConfig;
import org.apache.pinot.spi.config.tenant.Tenant;
import org.apache.pinot.spi.config.tenant.TenantRole;
import org.apache.pinot.spi.stream.LongMsgOffset;
import org.apache.pinot.spi.stream.StreamPartitionMsgOffset;
import org.apache.pinot.spi.utils.Enablement;
import org.apache.pinot.spi.utils.builder.TableConfigBuilder;
import org.apache.pinot.spi.utils.builder.TableNameBuilder;
import org.mockito.Mockito;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.apache.pinot.spi.utils.CommonConstants.Helix.StateModel.SegmentStateModel.ONLINE;
import static org.testng.Assert.*;


@Test(groups = "stateless")
public class TableRebalancerClusterStatelessTest extends ControllerTest {
  private static final String RAW_TABLE_NAME = "testTable";
  private static final String OFFLINE_TABLE_NAME = TableNameBuilder.OFFLINE.tableNameWithType(RAW_TABLE_NAME);
  private static final String REALTIME_TABLE_NAME = TableNameBuilder.REALTIME.tableNameWithType(RAW_TABLE_NAME);
  private static final int NUM_REPLICAS = 3;
  private static final String SEGMENT_NAME_PREFIX = "segment_";
  private static final String PARTITION_COLUMN = "partitionColumn";

  private static final String TIERED_TABLE_NAME = "testTable";
  private static final String OFFLINE_TIERED_TABLE_NAME = TableNameBuilder.OFFLINE.tableNameWithType(TIERED_TABLE_NAME);
  private static final String NO_TIER_NAME = "noTier";
  private static final String TIER_A_NAME = "tierA";
  private static final String TIER_B_NAME = "tierB";
  private static final String TIER_FIXED_NAME = "tierFixed";

  @BeforeClass
  public void setUp()
      throws Exception {
    startZk();
    Map<String, Object> config = getDefaultControllerConfiguration();
    // Disable resource util check in test so that each test can set the ResourceUtilizationInfo manually
    config.put(ControllerConf.RESOURCE_UTILIZATION_CHECKER_INITIAL_DELAY, 30_000);
    startController(config);
    addFakeBrokerInstancesToAutoJoinHelixCluster(1, true);
  }

  /// Dropping instance from cluster requires waiting for live instance gone and removing instance related ZNodes, which
  /// are not the purpose of the test, so combine different rebalance scenarios into one test:
  /// 1. NO_OP rebalance
  /// 2. Add servers and rebalance
  /// 3. Migrate to replica-group based segment assignment and rebalance
  /// 4. Migrate back to non-replica-group based segment assignment and rebalance
  /// 5. Remove (disable) servers and rebalance
  @Test
  public void testRebalance()
      throws Exception {
    for (int batchSizePerServer : Arrays.asList(RebalanceConfig.DISABLE_BATCH_SIZE_PER_SERVER, 1, 2)) {
      int numServers = 3;
      // Mock disk usage
      Map<String, DiskUsageInfo> diskUsageInfoMap = new HashMap<>();

      for (int i = 0; i < numServers; i++) {
        String instanceId = SERVER_INSTANCE_ID_PREFIX + i;
        addFakeServerInstanceToAutoJoinHelixCluster(instanceId, true);
        DiskUsageInfo diskUsageInfo = new DiskUsageInfo(instanceId, "", 1000L, 500L, System.currentTimeMillis());
        diskUsageInfoMap.put(instanceId, diskUsageInfo);
      }

      ExecutorService executorService = Executors.newFixedThreadPool(10);
      DefaultRebalancePreChecker preChecker = new DefaultRebalancePreChecker();
      preChecker.init(_helixResourceManager, executorService, 1);
      TableRebalancer tableRebalancer =
          new TableRebalancer(_helixManager, null, null, preChecker, _tableSizeReader, null);
      TableConfig tableConfig =
          new TableConfigBuilder(TableType.OFFLINE).setTableName(RAW_TABLE_NAME).setNumReplicas(NUM_REPLICAS).build();

      // Rebalance should fail without creating the table
      RebalanceConfig rebalanceConfig = new RebalanceConfig();
      rebalanceConfig.setBatchSizePerServer(batchSizePerServer);
      RebalanceResult rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
      assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.FAILED);
      assertNull(rebalanceResult.getRebalanceSummaryResult());

      // Rebalance with dry-run summary should fail without creating the table
      rebalanceConfig = new RebalanceConfig();
      rebalanceConfig.setDryRun(true);
      rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
      assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.FAILED);
      assertNull(rebalanceResult.getRebalanceSummaryResult());

      // Create the table
      addDummySchema(RAW_TABLE_NAME);
      _helixResourceManager.addTable(tableConfig);

      // Add the segments
      int numSegments = 10;
      for (int i = 0; i < numSegments; i++) {
        _helixResourceManager.addNewSegment(OFFLINE_TABLE_NAME,
            SegmentMetadataMockUtils.mockSegmentMetadata(RAW_TABLE_NAME, SEGMENT_NAME_PREFIX + i), null);
      }
      Map<String, Map<String, String>> oldSegmentAssignment =
          _helixResourceManager.getTableIdealState(OFFLINE_TABLE_NAME).getRecord().getMapFields();

      // Rebalance with dry-run summary should return NO_OP status
      rebalanceConfig = new RebalanceConfig();
      rebalanceConfig.setDryRun(true);
      rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
      assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.NO_OP);
      RebalanceSummaryResult rebalanceSummaryResult = rebalanceResult.getRebalanceSummaryResult();
      assertNotNull(rebalanceSummaryResult);
      assertNotNull(rebalanceSummaryResult.getServerInfo());
      assertNotNull(rebalanceSummaryResult.getSegmentInfo());
      assertEquals(rebalanceSummaryResult.getSegmentInfo().getTotalSegmentsToBeMoved(), 0);
      assertEquals(rebalanceSummaryResult.getSegmentInfo().getTotalSegmentsToBeDeleted(), 0);
      assertNull(rebalanceSummaryResult.getSegmentInfo().getConsumingSegmentToBeMovedSummary());
      assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getValueBeforeRebalance(), 3);
      assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getExpectedValueAfterRebalance(), 3);
      assertNotNull(rebalanceSummaryResult.getTagsInfo());
      assertEquals(rebalanceSummaryResult.getTagsInfo().size(), 1);
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getTagName(), TagNameUtils.getOfflineTagForTenant(null));
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumSegmentsToDownload(), 0);
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumSegmentsUnchanged(), numSegments * NUM_REPLICAS);
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumServerParticipants(), numServers);
      assertNotNull(rebalanceResult.getInstanceAssignment());
      assertNotNull(rebalanceResult.getSegmentAssignment());

      // Dry-run mode should not change the IdealState
      assertEquals(_helixResourceManager.getTableIdealState(OFFLINE_TABLE_NAME).getRecord().getMapFields(),
          oldSegmentAssignment);

      // Rebalance should return NO_OP status
      rebalanceConfig = new RebalanceConfig();
      rebalanceConfig.setBatchSizePerServer(batchSizePerServer);
      rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
      assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.NO_OP);

      // All servers should be assigned to the table
      Map<InstancePartitionsType, InstancePartitions> instanceAssignment = rebalanceResult.getInstanceAssignment();
      assertEquals(instanceAssignment.size(), 1);
      InstancePartitions instancePartitions = instanceAssignment.get(InstancePartitionsType.OFFLINE);
      assertEquals(instancePartitions.getNumReplicaGroups(), 1);
      assertEquals(instancePartitions.getNumPartitions(), 1);
      // Math.abs("testTable_OFFLINE".hashCode()) % 3 = 2
      assertEquals(instancePartitions.getInstances(0, 0),
          Arrays.asList(SERVER_INSTANCE_ID_PREFIX + 2, SERVER_INSTANCE_ID_PREFIX + 0, SERVER_INSTANCE_ID_PREFIX + 1));

      // Segment assignment should not change
      assertEquals(rebalanceResult.getSegmentAssignment(), oldSegmentAssignment);

      // Add 3 more servers
      int numServersToAdd = 3;
      for (int i = 0; i < numServersToAdd; i++) {
        String instanceId = SERVER_INSTANCE_ID_PREFIX + (numServers + i);
        addFakeServerInstanceToAutoJoinHelixCluster(instanceId, true);
        DiskUsageInfo diskUsageInfo = new DiskUsageInfo(instanceId, "", 1000L, 500L, System.currentTimeMillis());
        diskUsageInfoMap.put(instanceId, diskUsageInfo);
      }

      ResourceUtilizationInfo.setDiskUsageInfo(diskUsageInfoMap);

      // Rebalance in dry-run summary mode with added servers
      rebalanceConfig = new RebalanceConfig();
      rebalanceConfig.setDryRun(true);
      rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
      assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);
      rebalanceSummaryResult = rebalanceResult.getRebalanceSummaryResult();
      assertNotNull(rebalanceSummaryResult);
      assertNotNull(rebalanceSummaryResult.getServerInfo());
      assertNotNull(rebalanceSummaryResult.getSegmentInfo());
      assertEquals(rebalanceSummaryResult.getSegmentInfo().getTotalSegmentsToBeMoved(), 15);
      assertEquals(rebalanceSummaryResult.getSegmentInfo().getTotalSegmentsToBeDeleted(), 15);
      assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getValueBeforeRebalance(), 3);
      assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getExpectedValueAfterRebalance(), 6);
      assertEquals(rebalanceSummaryResult.getServerInfo().getNumServersGettingNewSegments(), 3);
      assertNotNull(rebalanceSummaryResult.getTagsInfo());
      assertEquals(rebalanceSummaryResult.getTagsInfo().size(), 1);
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getTagName(), TagNameUtils.getOfflineTagForTenant(null));
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumSegmentsToDownload(), 15);
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumSegmentsUnchanged(),
          numSegments * NUM_REPLICAS - 15);
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumServerParticipants(),
          numServers + numServersToAdd);
      assertNotNull(rebalanceResult.getInstanceAssignment());
      assertNotNull(rebalanceResult.getSegmentAssignment());

      Map<String, RebalanceSummaryResult.ServerSegmentChangeInfo> serverSegmentChangeInfoMap =
          rebalanceSummaryResult.getServerInfo().getServerSegmentChangeInfo();
      assertNotNull(serverSegmentChangeInfoMap);
      for (int i = 0; i < numServers; i++) {
        // Original servers should be losing some segments
        String newServer = SERVER_INSTANCE_ID_PREFIX + i;
        RebalanceSummaryResult.ServerSegmentChangeInfo serverSegmentChange = serverSegmentChangeInfoMap.get(newServer);
        assertEquals(serverSegmentChange.getTotalSegmentsBeforeRebalance(), 10);
        assertEquals(serverSegmentChange.getTotalSegmentsAfterRebalance(), 5);
        assertEquals(serverSegmentChange.getSegmentsAdded(), 0);
        assertEquals(serverSegmentChange.getSegmentsDeleted(), 5);
        assertEquals(serverSegmentChange.getSegmentsUnchanged(), 5);
      }
      for (int i = 0; i < numServersToAdd; i++) {
        // New servers should only get new segments
        String newServer = SERVER_INSTANCE_ID_PREFIX + (numServers + i);
        RebalanceSummaryResult.ServerSegmentChangeInfo serverSegmentChange = serverSegmentChangeInfoMap.get(newServer);
        assertEquals(serverSegmentChange.getTotalSegmentsBeforeRebalance(), 0);
        assertEquals(serverSegmentChange.getTotalSegmentsAfterRebalance(), 5);
        assertEquals(serverSegmentChange.getSegmentsAdded(), 5);
        assertEquals(serverSegmentChange.getSegmentsDeleted(), 0);
        assertEquals(serverSegmentChange.getSegmentsUnchanged(), 0);
      }

      // Dry-run mode should not change the IdealState
      assertEquals(_helixResourceManager.getTableIdealState(OFFLINE_TABLE_NAME).getRecord().getMapFields(),
          oldSegmentAssignment);

      // Rebalance in dry-run mode
      rebalanceConfig = new RebalanceConfig();
      rebalanceConfig.setDryRun(true);
      rebalanceConfig.setPreChecks(true);
      rebalanceConfig.setReassignInstances(true);

      rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
      assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);
      Map<String, RebalancePreCheckerResult> preCheckResult = rebalanceResult.getPreChecksResult();
      assertNotNull(preCheckResult);
      assertEquals(preCheckResult.size(), 6);
      assertTrue(preCheckResult.containsKey(DefaultRebalancePreChecker.NEEDS_RELOAD_STATUS));
      assertTrue(preCheckResult.containsKey(DefaultRebalancePreChecker.IS_MINIMIZE_DATA_MOVEMENT));
      assertTrue(preCheckResult.containsKey(DefaultRebalancePreChecker.DISK_UTILIZATION_DURING_REBALANCE));
      assertTrue(preCheckResult.containsKey(DefaultRebalancePreChecker.DISK_UTILIZATION_AFTER_REBALANCE));
      assertTrue(preCheckResult.containsKey(DefaultRebalancePreChecker.REBALANCE_CONFIG_OPTIONS));
      assertTrue(preCheckResult.containsKey(DefaultRebalancePreChecker.REPLICA_GROUPS_INFO));
      // Sending request to servers should fail for all, so needsPreprocess should be set to "error" to indicate that a
      // manual check is needed
      assertEquals(preCheckResult.get(DefaultRebalancePreChecker.NEEDS_RELOAD_STATUS).getPreCheckStatus(),
          RebalancePreCheckerResult.PreCheckStatus.ERROR);
      assertEquals(preCheckResult.get(DefaultRebalancePreChecker.NEEDS_RELOAD_STATUS).getMessage(),
          "Could not determine needReload status, run needReload API manually");
      assertEquals(preCheckResult.get(DefaultRebalancePreChecker.IS_MINIMIZE_DATA_MOVEMENT).getPreCheckStatus(),
          RebalancePreCheckerResult.PreCheckStatus.PASS);
      assertEquals(preCheckResult.get(DefaultRebalancePreChecker.IS_MINIMIZE_DATA_MOVEMENT).getMessage(),
          "Instance assignment not allowed, no need for minimizeDataMovement");
      assertEquals(preCheckResult.get(DefaultRebalancePreChecker.DISK_UTILIZATION_DURING_REBALANCE).getPreCheckStatus(),
          RebalancePreCheckerResult.PreCheckStatus.PASS);
      assertTrue(preCheckResult.get(DefaultRebalancePreChecker.DISK_UTILIZATION_DURING_REBALANCE)
          .getMessage()
          .startsWith("Within threshold"));
      assertEquals(preCheckResult.get(DefaultRebalancePreChecker.DISK_UTILIZATION_AFTER_REBALANCE).getPreCheckStatus(),
          RebalancePreCheckerResult.PreCheckStatus.PASS);
      assertTrue(preCheckResult.get(DefaultRebalancePreChecker.DISK_UTILIZATION_AFTER_REBALANCE)
          .getMessage()
          .startsWith("Within threshold"));
      assertEquals(preCheckResult.get(DefaultRebalancePreChecker.REBALANCE_CONFIG_OPTIONS).getPreCheckStatus(),
          RebalancePreCheckerResult.PreCheckStatus.PASS);
      assertEquals(preCheckResult.get(DefaultRebalancePreChecker.REBALANCE_CONFIG_OPTIONS).getMessage(),
          "All rebalance parameters look good");
      assertEquals(preCheckResult.get(DefaultRebalancePreChecker.REPLICA_GROUPS_INFO).getPreCheckStatus(),
          RebalancePreCheckerResult.PreCheckStatus.PASS);
      assertEquals(preCheckResult.get(DefaultRebalancePreChecker.REPLICA_GROUPS_INFO).getMessage(),
          "OFFLINE segments - Replica Groups are not enabled, replication: " + NUM_REPLICAS);

      // All servers should be assigned to the table
      instanceAssignment = rebalanceResult.getInstanceAssignment();
      assertEquals(instanceAssignment.size(), 1);
      instancePartitions = instanceAssignment.get(InstancePartitionsType.OFFLINE);
      assertEquals(instancePartitions.getNumReplicaGroups(), 1);
      assertEquals(instancePartitions.getNumPartitions(), 1);
      // Math.abs("testTable_OFFLINE".hashCode()) % 6 = 2
      assertEquals(instancePartitions.getInstances(0, 0),
          Arrays.asList(SERVER_INSTANCE_ID_PREFIX + 2, SERVER_INSTANCE_ID_PREFIX + 3, SERVER_INSTANCE_ID_PREFIX + 4,
              SERVER_INSTANCE_ID_PREFIX + 5, SERVER_INSTANCE_ID_PREFIX + 0, SERVER_INSTANCE_ID_PREFIX + 1));

      // Segments should be moved to the new added servers
      Map<String, Map<String, String>> newSegmentAssignment = rebalanceResult.getSegmentAssignment();
      Map<String, IntIntPair> instanceToNumSegmentsToMoveMap =
          SegmentAssignmentUtils.getNumSegmentsToMovePerInstance(oldSegmentAssignment, newSegmentAssignment);
      assertEquals(instanceToNumSegmentsToMoveMap.size(), numServers + numServersToAdd);
      for (int i = 0; i < numServers; i++) {
        IntIntPair numSegmentsToMove = instanceToNumSegmentsToMoveMap.get(SERVER_INSTANCE_ID_PREFIX + i);
        assertNotNull(numSegmentsToMove);
        assertEquals(numSegmentsToMove.leftInt(), 0);
        assertEquals(numSegmentsToMove.rightInt(), 5);
      }
      for (int i = 0; i < numServersToAdd; i++) {
        IntIntPair numSegmentsToMove = instanceToNumSegmentsToMoveMap.get(SERVER_INSTANCE_ID_PREFIX + (numServers + i));
        assertNotNull(numSegmentsToMove);
        assertEquals(numSegmentsToMove.leftInt(), 5);
        assertEquals(numSegmentsToMove.rightInt(), 0);
      }

      // Dry-run mode should not change the IdealState
      assertEquals(_helixResourceManager.getTableIdealState(OFFLINE_TABLE_NAME).getRecord().getMapFields(),
          oldSegmentAssignment);

      // Rebalance dry-run summary with 3 min available replicas should not be impacted since actual rebalance does not
      // occur
      rebalanceConfig = new RebalanceConfig();
      rebalanceConfig.setDryRun(true);
      rebalanceConfig.setPreChecks(true);
      rebalanceConfig.setMinAvailableReplicas(3);
      rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
      assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);
      rebalanceSummaryResult = rebalanceResult.getRebalanceSummaryResult();
      assertNotNull(rebalanceSummaryResult);
      assertNotNull(rebalanceSummaryResult.getServerInfo());
      assertNotNull(rebalanceSummaryResult.getSegmentInfo());
      assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getValueBeforeRebalance(), 3);
      assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getExpectedValueAfterRebalance(), 6);
      assertNotNull(rebalanceResult.getPreChecksResult());
      assertNotNull(rebalanceResult.getInstanceAssignment());
      assertNotNull(rebalanceResult.getSegmentAssignment());

      // Rebalance with 3 min available replicas should fail as the table only have 3 replicas
      rebalanceConfig = new RebalanceConfig();
      rebalanceConfig.setMinAvailableReplicas(3);
      rebalanceConfig.setBatchSizePerServer(batchSizePerServer);
      rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
      assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.FAILED);

      // IdealState should not change for FAILED rebalance
      assertEquals(_helixResourceManager.getTableIdealState(OFFLINE_TABLE_NAME).getRecord().getMapFields(),
          oldSegmentAssignment);

      // Rebalance with 2 min available replicas should succeed
      rebalanceConfig = new RebalanceConfig();
      rebalanceConfig.setMinAvailableReplicas(2);
      rebalanceConfig.setBatchSizePerServer(batchSizePerServer);
      rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
      assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);

      // Result should be the same as the result in dry-run mode
      instanceAssignment = rebalanceResult.getInstanceAssignment();
      assertEquals(instanceAssignment.size(), 1);
      assertEquals(instanceAssignment.get(InstancePartitionsType.OFFLINE).getPartitionToInstancesMap(),
          instancePartitions.getPartitionToInstancesMap());
      assertEquals(rebalanceResult.getSegmentAssignment(), newSegmentAssignment);

      // Update the table config to use replica-group based assignment
      InstanceTagPoolConfig tagPoolConfig =
          new InstanceTagPoolConfig(TagNameUtils.getOfflineTagForTenant(null), false, 0, null);
      InstanceReplicaGroupPartitionConfig replicaGroupPartitionConfig =
          new InstanceReplicaGroupPartitionConfig(true, 0, NUM_REPLICAS, 0, 0, 0, false, null);
      tableConfig.setInstanceAssignmentConfigMap(Collections.singletonMap(InstancePartitionsType.OFFLINE.toString(),
          new InstanceAssignmentConfig(tagPoolConfig, null, replicaGroupPartitionConfig, null, false)));
      _helixResourceManager.updateTableConfig(tableConfig);

      // Try dry-run summary mode
      // No need to reassign instances because instances should be automatically assigned when updating the table config
      rebalanceConfig = new RebalanceConfig();
      rebalanceConfig.setDryRun(true);
      rebalanceConfig.setPreChecks(true);
      rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
      assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);
      // Though instance partition map is set in ZK, the pre-checker is unaware of that, a warning will be thrown
      assertEquals(
          rebalanceResult.getPreChecksResult().get(DefaultRebalancePreChecker.REPLICA_GROUPS_INFO).getPreCheckStatus(),
          RebalancePreCheckerResult.PreCheckStatus.WARN);
      assertEquals(
          rebalanceResult.getPreChecksResult().get(DefaultRebalancePreChecker.REPLICA_GROUPS_INFO).getMessage(),
          "reassignInstances is disabled, replica groups may not be updated.\nOFFLINE segments "
              + "- numReplicaGroups: " + NUM_REPLICAS + ", numInstancesPerReplicaGroup: 0 (using as many instances as "
              + "possible)");
      rebalanceSummaryResult = rebalanceResult.getRebalanceSummaryResult();
      assertNotNull(rebalanceSummaryResult);
      assertNotNull(rebalanceSummaryResult.getServerInfo());
      assertNotNull(rebalanceSummaryResult.getSegmentInfo());
      assertEquals(rebalanceSummaryResult.getSegmentInfo().getTotalSegmentsToBeMoved(), 20);
      assertEquals(rebalanceSummaryResult.getSegmentInfo().getTotalSegmentsToBeDeleted(), 20);
      assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getValueBeforeRebalance(), 6);
      assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getExpectedValueAfterRebalance(), 6);
      assertNotNull(rebalanceSummaryResult.getTagsInfo());
      assertEquals(rebalanceSummaryResult.getTagsInfo().size(), 1);
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getTagName(), TagNameUtils.getOfflineTagForTenant(null));
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumSegmentsToDownload(), 20);
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumSegmentsUnchanged(),
          numSegments * NUM_REPLICAS - 20);
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumServerParticipants(),
          numServers + numServersToAdd);
      assertNotNull(rebalanceResult.getInstanceAssignment());
      assertNotNull(rebalanceResult.getSegmentAssignment());

      serverSegmentChangeInfoMap = rebalanceSummaryResult.getServerInfo().getServerSegmentChangeInfo();
      assertNotNull(serverSegmentChangeInfoMap);
      for (int i = 0; i < numServers + numServersToAdd; i++) {
        String newServer = SERVER_INSTANCE_ID_PREFIX + i;
        RebalanceSummaryResult.ServerSegmentChangeInfo serverSegmentChange = serverSegmentChangeInfoMap.get(newServer);
        assertEquals(serverSegmentChange.getTotalSegmentsBeforeRebalance(), 5);
        assertEquals(serverSegmentChange.getTotalSegmentsAfterRebalance(), 5);
      }

      // Dry-run mode should not change the IdealState
      assertEquals(_helixResourceManager.getTableIdealState(OFFLINE_TABLE_NAME).getRecord().getMapFields(),
          newSegmentAssignment);

      // Try actual rebalance
      // No need to reassign instances because instances should be automatically assigned when updating the table config
      rebalanceConfig = new RebalanceConfig();
      rebalanceConfig.setBatchSizePerServer(batchSizePerServer);
      rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
      assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);

      // There should be 3 replica-groups, each with 2 servers
      instanceAssignment = rebalanceResult.getInstanceAssignment();
      assertEquals(instanceAssignment.size(), 1);
      instancePartitions = instanceAssignment.get(InstancePartitionsType.OFFLINE);
      assertEquals(instancePartitions.getNumReplicaGroups(), NUM_REPLICAS);
      assertEquals(instancePartitions.getNumPartitions(), 1);
      // Math.abs("testTable_OFFLINE".hashCode()) % 6 = 2
      // [i2, i3, i4, i5, i0, i1]
      //  r0  r1  r2  r0  r1  r2
      assertEquals(instancePartitions.getInstances(0, 0),
          Arrays.asList(SERVER_INSTANCE_ID_PREFIX + 2, SERVER_INSTANCE_ID_PREFIX + 5));
      assertEquals(instancePartitions.getInstances(0, 1),
          Arrays.asList(SERVER_INSTANCE_ID_PREFIX + 3, SERVER_INSTANCE_ID_PREFIX + 0));
      assertEquals(instancePartitions.getInstances(0, 2),
          Arrays.asList(SERVER_INSTANCE_ID_PREFIX + 4, SERVER_INSTANCE_ID_PREFIX + 1));

      // The assignment are based on replica-group 0 and mirrored to all the replica-groups, so server of index 0, 1, 5
      // should have the same segments assigned, and server of index 2, 3, 4 should have the same segments assigned,
      // each with 5 segments
      newSegmentAssignment = rebalanceResult.getSegmentAssignment();
      int numSegmentsOnServer0 = 0;
      for (int i = 0; i < numSegments; i++) {
        String segmentName = SEGMENT_NAME_PREFIX + i;
        Map<String, String> instanceStateMap = newSegmentAssignment.get(segmentName);
        assertEquals(instanceStateMap.size(), NUM_REPLICAS);
        if (instanceStateMap.containsKey(SERVER_INSTANCE_ID_PREFIX + 0)) {
          numSegmentsOnServer0++;
          assertEquals(instanceStateMap.get(SERVER_INSTANCE_ID_PREFIX + 0), ONLINE);
          assertEquals(instanceStateMap.get(SERVER_INSTANCE_ID_PREFIX + 1), ONLINE);
          assertEquals(instanceStateMap.get(SERVER_INSTANCE_ID_PREFIX + 5), ONLINE);
        } else {
          assertEquals(instanceStateMap.get(SERVER_INSTANCE_ID_PREFIX + 2), ONLINE);
          assertEquals(instanceStateMap.get(SERVER_INSTANCE_ID_PREFIX + 3), ONLINE);
          assertEquals(instanceStateMap.get(SERVER_INSTANCE_ID_PREFIX + 4), ONLINE);
        }
      }
      assertEquals(numSegmentsOnServer0, numSegments / 2);

      // Update the table config to use non-replica-group based assignment
      tableConfig.setInstanceAssignmentConfigMap(null);
      _helixResourceManager.updateTableConfig(tableConfig);

      // Try dry-run summary mode without reassignment to ensure that existing instance partitions are used
      // no movement should occur
      rebalanceConfig = new RebalanceConfig();
      rebalanceConfig.setDryRun(true);
      rebalanceConfig.setPreChecks(true);
      rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
      assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.NO_OP);
      assertEquals(
          rebalanceResult.getPreChecksResult().get(DefaultRebalancePreChecker.REPLICA_GROUPS_INFO).getPreCheckStatus(),
          RebalancePreCheckerResult.PreCheckStatus.PASS);
      assertEquals(
          rebalanceResult.getPreChecksResult().get(DefaultRebalancePreChecker.REPLICA_GROUPS_INFO).getMessage(),
          "OFFLINE segments - Replica Groups are not enabled, replication: " + NUM_REPLICAS);
      rebalanceSummaryResult = rebalanceResult.getRebalanceSummaryResult();
      assertNotNull(rebalanceSummaryResult);
      assertNotNull(rebalanceSummaryResult.getServerInfo());
      assertNotNull(rebalanceSummaryResult.getSegmentInfo());
      assertEquals(rebalanceSummaryResult.getSegmentInfo().getTotalSegmentsToBeMoved(), 0);
      assertEquals(rebalanceSummaryResult.getSegmentInfo().getTotalSegmentsToBeDeleted(), 0);
      assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getValueBeforeRebalance(), 6);
      assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getExpectedValueAfterRebalance(), 6);
      assertEquals(rebalanceSummaryResult.getServerInfo().getNumServersGettingNewSegments(), 0);
      assertNotNull(rebalanceSummaryResult.getTagsInfo());
      assertEquals(rebalanceSummaryResult.getTagsInfo().size(), 1);
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getTagName(), TagNameUtils.getOfflineTagForTenant(null));
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumSegmentsToDownload(), 0);
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumSegmentsUnchanged(), numSegments * NUM_REPLICAS);
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumServerParticipants(),
          numServers + numServersToAdd);
      assertNotNull(rebalanceResult.getInstanceAssignment());
      assertNotNull(rebalanceResult.getSegmentAssignment());

      // Without instances reassignment, the rebalance should return status NO_OP, and the existing instance partitions
      // should be used
      rebalanceConfig = new RebalanceConfig();
      rebalanceConfig.setBatchSizePerServer(batchSizePerServer);
      rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
      assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.NO_OP);
      assertEquals(rebalanceResult.getInstanceAssignment(), instanceAssignment);
      assertEquals(rebalanceResult.getSegmentAssignment(), newSegmentAssignment);

      // Try dry-run summary mode with reassignment
      rebalanceConfig = new RebalanceConfig();
      rebalanceConfig.setDryRun(true);
      rebalanceConfig.setPreChecks(true);
      rebalanceConfig.setReassignInstances(true);
      rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
      assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);
      assertEquals(
          rebalanceResult.getPreChecksResult().get(DefaultRebalancePreChecker.REPLICA_GROUPS_INFO).getPreCheckStatus(),
          RebalancePreCheckerResult.PreCheckStatus.PASS);
      assertEquals(
          rebalanceResult.getPreChecksResult().get(DefaultRebalancePreChecker.REPLICA_GROUPS_INFO).getMessage(),
          "OFFLINE segments - Replica Groups are not enabled, replication: " + NUM_REPLICAS);
      rebalanceSummaryResult = rebalanceResult.getRebalanceSummaryResult();
      assertNotNull(rebalanceSummaryResult);
      assertNotNull(rebalanceSummaryResult.getServerInfo());
      assertNotNull(rebalanceSummaryResult.getSegmentInfo());
      // No move expected since already balanced
      assertEquals(rebalanceSummaryResult.getSegmentInfo().getTotalSegmentsToBeMoved(), 0);
      assertEquals(rebalanceSummaryResult.getSegmentInfo().getTotalSegmentsToBeDeleted(), 0);
      assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getValueBeforeRebalance(), 6);
      assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getExpectedValueAfterRebalance(), 6);
      assertEquals(rebalanceSummaryResult.getServerInfo().getNumServersGettingNewSegments(), 0);
      assertNotNull(rebalanceSummaryResult.getTagsInfo());
      assertEquals(rebalanceSummaryResult.getTagsInfo().size(), 1);
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getTagName(), TagNameUtils.getOfflineTagForTenant(null));
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumSegmentsToDownload(), 0);
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumSegmentsUnchanged(), numSegments * NUM_REPLICAS);
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumServerParticipants(),
          numServers + numServersToAdd);
      assertNotNull(rebalanceResult.getInstanceAssignment());
      assertNotNull(rebalanceResult.getSegmentAssignment());

      // With instances reassignment, the rebalance should return status DONE, the existing instance partitions should
      // be removed, and the default instance partitions should be used
      rebalanceConfig = new RebalanceConfig();
      rebalanceConfig.setReassignInstances(true);
      rebalanceConfig.setBatchSizePerServer(batchSizePerServer);
      rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
      assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);
      assertNull(InstancePartitionsUtils.fetchInstancePartitions(_propertyStore,
          InstancePartitionsType.OFFLINE.getInstancePartitionsName(RAW_TABLE_NAME)));

      // All servers should be assigned to the table
      instanceAssignment = rebalanceResult.getInstanceAssignment();
      assertEquals(instanceAssignment.size(), 1);
      instancePartitions = instanceAssignment.get(InstancePartitionsType.OFFLINE);
      assertEquals(instancePartitions.getNumReplicaGroups(), 1);
      assertEquals(instancePartitions.getNumPartitions(), 1);
      // Math.abs("testTable_OFFLINE".hashCode()) % 6 = 2
      assertEquals(instancePartitions.getInstances(0, 0),
          Arrays.asList(SERVER_INSTANCE_ID_PREFIX + 2, SERVER_INSTANCE_ID_PREFIX + 3, SERVER_INSTANCE_ID_PREFIX + 4,
              SERVER_INSTANCE_ID_PREFIX + 5, SERVER_INSTANCE_ID_PREFIX + 0, SERVER_INSTANCE_ID_PREFIX + 1));

      // Segment assignment should not change as it is already balanced
      assertEquals(rebalanceResult.getSegmentAssignment(), newSegmentAssignment);

      // Remove the tag from the added servers
      for (int i = 0; i < numServersToAdd; i++) {
        _helixAdmin.removeInstanceTag(getHelixClusterName(), SERVER_INSTANCE_ID_PREFIX + (numServers + i),
            TagNameUtils.getOfflineTagForTenant(null));
      }

      // Try dry-run summary mode
      rebalanceConfig = new RebalanceConfig();
      rebalanceConfig.setDryRun(true);
      rebalanceConfig.setDowntime(true);
      rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
      assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);
      rebalanceSummaryResult = rebalanceResult.getRebalanceSummaryResult();
      assertNotNull(rebalanceSummaryResult);
      assertNotNull(rebalanceSummaryResult.getServerInfo());
      assertNotNull(rebalanceSummaryResult.getSegmentInfo());
      assertEquals(rebalanceSummaryResult.getSegmentInfo().getTotalSegmentsToBeMoved(), 15);
      assertEquals(rebalanceSummaryResult.getSegmentInfo().getTotalSegmentsToBeDeleted(), 15);
      assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getValueBeforeRebalance(), 6);
      assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getExpectedValueAfterRebalance(), 3);
      assertEquals(rebalanceSummaryResult.getServerInfo().getNumServersGettingNewSegments(), 3);
      assertNotNull(rebalanceSummaryResult.getTagsInfo());
      assertEquals(rebalanceSummaryResult.getTagsInfo().size(), 1);
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getTagName(), TagNameUtils.getOfflineTagForTenant(null));
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumSegmentsToDownload(), 15);
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumSegmentsUnchanged(),
          numSegments * NUM_REPLICAS - 15);
      assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumServerParticipants(), numServers);
      assertNotNull(rebalanceResult.getInstanceAssignment());
      assertNotNull(rebalanceResult.getSegmentAssignment());

      // Rebalance with downtime should succeed
      rebalanceConfig = new RebalanceConfig();
      rebalanceConfig.setDowntime(true);
      rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
      assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);

      // All servers with tag should be assigned to the table
      instanceAssignment = rebalanceResult.getInstanceAssignment();
      assertEquals(instanceAssignment.size(), 1);
      instancePartitions = instanceAssignment.get(InstancePartitionsType.OFFLINE);
      assertEquals(instancePartitions.getNumReplicaGroups(), 1);
      assertEquals(instancePartitions.getNumPartitions(), 1);
      // Math.abs("testTable_OFFLINE".hashCode()) % 3 = 2
      assertEquals(instancePartitions.getInstances(0, 0),
          Arrays.asList(SERVER_INSTANCE_ID_PREFIX + 2, SERVER_INSTANCE_ID_PREFIX + 0, SERVER_INSTANCE_ID_PREFIX + 1));

      // New segment assignment should not contain servers without tag
      newSegmentAssignment = rebalanceResult.getSegmentAssignment();
      for (int i = 0; i < numSegments; i++) {
        String segmentName = SEGMENT_NAME_PREFIX + i;
        Map<String, String> instanceStateMap = newSegmentAssignment.get(segmentName);
        assertEquals(instanceStateMap.size(), NUM_REPLICAS);
        for (int j = 0; j < numServersToAdd; j++) {
          assertFalse(instanceStateMap.containsKey(SERVER_INSTANCE_ID_PREFIX + (numServers + j)));
        }
      }

      // Try pre-checks mode without dry-run set
      rebalanceConfig = new RebalanceConfig();
      rebalanceConfig.setPreChecks(true);
      rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
      assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.FAILED);
      assertNull(rebalanceResult.getRebalanceSummaryResult());
      assertNull(rebalanceResult.getPreChecksResult());

      _helixResourceManager.deleteOfflineTable(RAW_TABLE_NAME);

      for (int i = 0; i < numServers; i++) {
        stopAndDropFakeInstance(SERVER_INSTANCE_ID_PREFIX + i);
      }
      for (int i = 0; i < numServersToAdd; i++) {
        stopAndDropFakeInstance(SERVER_INSTANCE_ID_PREFIX + (numServers + i));
      }
      executorService.shutdown();
    }
  }

  @Test(timeOut = 60000)
  public void testRebalanceStrictReplicaGroup()
      throws Exception {
    for (int batchSizePerServer : Arrays.asList(RebalanceConfig.DISABLE_BATCH_SIZE_PER_SERVER, 3, 1)) {
      int numServers = 3;

      for (int i = 0; i < numServers; i++) {
        String instanceId = SERVER_INSTANCE_ID_PREFIX + i;
        addFakeServerInstanceToAutoJoinHelixCluster(instanceId, true);
      }

      ExecutorService executorService = Executors.newFixedThreadPool(10);
      DefaultRebalancePreChecker preChecker = new DefaultRebalancePreChecker();
      preChecker.init(_helixResourceManager, executorService, 1);
      TableRebalancer tableRebalancer = new TableRebalancer(_helixManager, null, null, preChecker,
          _tableSizeReader, null);
      // Set up the table with 1 replication factor and strict replica group enabled
      TableConfig tableConfig = new TableConfigBuilder(TableType.OFFLINE).setTableName(RAW_TABLE_NAME)
          .setNumReplicas(1)
          .setRoutingConfig(
              new RoutingConfig(null, null, RoutingConfig.STRICT_REPLICA_GROUP_INSTANCE_SELECTOR_TYPE, false))
          .build();

      // Create the table
      addDummySchema(RAW_TABLE_NAME);
      _helixResourceManager.addTable(tableConfig);

      // Add the segments
      int numSegments = 10;
      for (int i = 0; i < numSegments; i++) {
        _helixResourceManager.addNewSegment(OFFLINE_TABLE_NAME,
            SegmentMetadataMockUtils.mockSegmentMetadata(RAW_TABLE_NAME, SEGMENT_NAME_PREFIX + i), null);
      }
      Map<String, Map<String, String>> oldSegmentAssignment =
          _helixResourceManager.getTableIdealState(OFFLINE_TABLE_NAME).getRecord().getMapFields();
      for (Map.Entry<String, Map<String, String>> entry : oldSegmentAssignment.entrySet()) {
        assertEquals(entry.getValue().size(), 1);
      }

      // Rebalance should return NO_OP status since there has been no change
      RebalanceConfig rebalanceConfig = new RebalanceConfig();
      rebalanceConfig.setBatchSizePerServer(batchSizePerServer);
      RebalanceResult rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
      assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.NO_OP);

      // All servers should be assigned to the table
      Map<InstancePartitionsType, InstancePartitions> instanceAssignment = rebalanceResult.getInstanceAssignment();
      assertEquals(instanceAssignment.size(), 1);
      InstancePartitions instancePartitions = instanceAssignment.get(InstancePartitionsType.OFFLINE);
      assertEquals(instancePartitions.getNumReplicaGroups(), 1);
      assertEquals(instancePartitions.getNumPartitions(), 1);
      // Math.abs("testTable_OFFLINE".hashCode()) % 3 = 2
      assertEquals(instancePartitions.getInstances(0, 0),
          Arrays.asList(SERVER_INSTANCE_ID_PREFIX + 2, SERVER_INSTANCE_ID_PREFIX + 0, SERVER_INSTANCE_ID_PREFIX + 1));

      // Segment assignment should not change
      assertEquals(rebalanceResult.getSegmentAssignment(), oldSegmentAssignment);

      // Increase the replication factor to 3
      tableConfig.getValidationConfig().setReplication("3");
      rebalanceConfig = new RebalanceConfig();
      rebalanceConfig.setDryRun(false);
      rebalanceConfig.setPreChecks(false);
      rebalanceConfig.setReassignInstances(true);
      rebalanceConfig.setBatchSizePerServer(batchSizePerServer);
      // minAvailableReplicas = -1 results in minAvailableReplicas = target replication - 1 = 2 in this case
      rebalanceConfig.setMinAvailableReplicas(-1);
      rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
      assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);

      Map<String, Map<String, String>> newSegmentAssignment = rebalanceResult.getSegmentAssignment();
      assertNotEquals(oldSegmentAssignment, newSegmentAssignment);
      for (Map.Entry<String, Map<String, String>> entry : newSegmentAssignment.entrySet()) {
        assertTrue(oldSegmentAssignment.containsKey(entry.getKey()));
        assertEquals(entry.getValue().size(), 3);
      }

      _helixResourceManager.deleteOfflineTable(RAW_TABLE_NAME);

      for (int i = 0; i < numServers; i++) {
        stopAndDropFakeInstance(SERVER_INSTANCE_ID_PREFIX + i);
      }
      executorService.shutdown();
    }
  }

  @Test
  public void testRebalanceWithImplicitRealtimeTablePartitionSelectorAndMinimizeDataMovement()
      throws Exception {
    int numServers = 6;
    int numPartitions = 18;
    int numReplicas = 2;

    for (int i = 0; i < numServers; i++) {
      String instanceId = SERVER_INSTANCE_ID_PREFIX + i;
      addFakeServerInstanceToAutoJoinHelixCluster(instanceId, true);
    }

    InstanceReplicaGroupPartitionConfig replicaGroupPartitionConfig =
        new InstanceReplicaGroupPartitionConfig(true, 0, numReplicas, 0, 0, 1, false, null);
    InstanceAssignmentConfig instanceAssignmentConfig = new InstanceAssignmentConfig(
        new InstanceTagPoolConfig(TagNameUtils.getRealtimeTagForTenant(null), false, 0, null), null,
        replicaGroupPartitionConfig,
        InstanceAssignmentConfig.PartitionSelector.IMPLICIT_REALTIME_TABLE_PARTITION_SELECTOR.name(), true);
    TableConfig tableConfig = new TableConfigBuilder(TableType.REALTIME).setTableName(RAW_TABLE_NAME)
        .setNumReplicas(numReplicas)
        .setRoutingConfig(
            new RoutingConfig(null, null, RoutingConfig.STRICT_REPLICA_GROUP_INSTANCE_SELECTOR_TYPE, false))
        .setStreamConfigs(FakeStreamConfigUtils.getDefaultLowLevelStreamConfigs(numPartitions).getStreamConfigsMap())
        .setInstanceAssignmentConfigMap(Map.of(InstancePartitionsType.CONSUMING.name(), instanceAssignmentConfig))
        .build();

    // Create the table
    addDummySchema(RAW_TABLE_NAME);
    _helixResourceManager.addTable(tableConfig);

    // Add the segments
    int numSegmentsPerPartition = 4;
    for (int i = 0; i < numPartitions; i++) {
      for (int j = 0; j < numSegmentsPerPartition; j++) {
        _helixResourceManager.addNewSegment(REALTIME_TABLE_NAME,
            SegmentMetadataMockUtils.mockSegmentMetadataWithPartitionInfo(RAW_TABLE_NAME,
                SEGMENT_NAME_PREFIX + (i * numSegmentsPerPartition + j), PARTITION_COLUMN, i), null);
      }
    }

    Map<String, Map<String, String>> oldSegmentAssignment =
        _helixResourceManager.getTableIdealState(REALTIME_TABLE_NAME).getRecord().getMapFields();
    for (Map.Entry<String, Map<String, String>> entry : oldSegmentAssignment.entrySet()) {
      assertEquals(entry.getValue().size(), numReplicas);
    }

    // Verify that segments are distributed equally across servers
    Map<String, Integer> numSegmentsPerServer = getNumSegmentsPerServer(oldSegmentAssignment);
    for (int i = 0; i < numServers; i++) {
      String instanceId = SERVER_INSTANCE_ID_PREFIX + i;
      assertTrue(numSegmentsPerServer.containsKey(instanceId));
      // Total number of segments is numReplicas * numPartitions * (numSegmentsPerPartition + 1) because of
      // CONSUMING segments
      assertEquals(numSegmentsPerServer.get(instanceId),
          (numReplicas * numPartitions * (numSegmentsPerPartition + 1)) / numServers);
    }

    TableRebalancer tableRebalancer = new TableRebalancer(_helixManager, null, null, null, null, null);
    // Rebalance should return NO_OP status since there has been no change
    RebalanceConfig rebalanceConfig = new RebalanceConfig();
    RebalanceResult rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.NO_OP);

    // All servers should be assigned to the table
    Map<InstancePartitionsType, InstancePartitions> instanceAssignment = rebalanceResult.getInstanceAssignment();
    assertEquals(instanceAssignment.size(), 1);
    InstancePartitions instancePartitions = instanceAssignment.get(InstancePartitionsType.CONSUMING);
    assertEquals(instancePartitions.getNumReplicaGroups(), numReplicas);
    assertEquals(instancePartitions.getNumPartitions(), numPartitions);

    // Verify that replica partitions are distributed equally across servers
    Map<String, Integer> numReplicaPartitionsPerServer = getNumReplicaPartitionsPerServer(instancePartitions);
    for (int i = 0; i < numServers; i++) {
      String instanceId = SERVER_INSTANCE_ID_PREFIX + i;
      assertTrue(numReplicaPartitionsPerServer.containsKey(instanceId));
      // Total number of partitions is numReplicas * numPartitions
      assertEquals(numReplicaPartitionsPerServer.get(instanceId), (numReplicas * numPartitions) / numServers);
    }

    // Segment assignment should not change
    assertEquals(rebalanceResult.getSegmentAssignment(), oldSegmentAssignment);

    // Add two new servers
    int numServersToAdd = 2;
    Set<String> newServers = new HashSet<>();
    for (int i = 0; i < numServersToAdd; i++) {
      String instanceId = SERVER_INSTANCE_ID_PREFIX + (numServers + i);
      addFakeServerInstanceToAutoJoinHelixCluster(instanceId, true);
      newServers.add(instanceId);
    }

    // Check number of segments moved when minimizeDataMovement is not enabled
    rebalanceConfig.setReassignInstances(true);
    rebalanceConfig.setIncludeConsuming(true);
    rebalanceConfig.setDryRun(true);
    rebalanceConfig.setMinimizeDataMovement(Enablement.DISABLE);
    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    // Most of the segments end up being moved when minimizeDataMovement is not enabled due to the round robin way in
    // which partitions are assigned to instances (see InstanceReplicaGroupPartitionSelector)
    assertEquals(rebalanceResult.getRebalanceSummaryResult().getSegmentInfo().getTotalSegmentsToBeMoved(), 130);

    // Rebalance with reassignInstances and minimizeDataMovement enabled
    rebalanceConfig.setMinimizeDataMovement(Enablement.ENABLE);
    rebalanceConfig.setDryRun(false);
    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);
    instanceAssignment = rebalanceResult.getInstanceAssignment();
    assertEquals(instanceAssignment.size(), 1);
    instancePartitions = instanceAssignment.get(InstancePartitionsType.CONSUMING);
    assertEquals(instancePartitions.getNumReplicaGroups(), numReplicas);
    assertEquals(instancePartitions.getNumPartitions(), numPartitions);

    // Get number of segments moved
    int numSegmentsMoved = getNumSegmentsMoved(oldSegmentAssignment, rebalanceResult.getSegmentAssignment());
    // This number is 130 when using the default partition selector in this same scenario since more segment partitions
    // will be moved when the instance partitions don't match the segment partitions (we're setting numPartitions to
    // the default value of 0 in the table's instance assignment config).
    assertEquals(numSegmentsMoved, 30);

    // "Repartition" and add two new partitions
    int newNumPartitions = 20;
    tableConfig.getIndexingConfig()
        .setStreamConfigs(
            FakeStreamConfigUtils.getDefaultLowLevelStreamConfigs(newNumPartitions).getStreamConfigsMap());
    _helixResourceManager.updateTableConfig(tableConfig);

    // Add segments for the new partitions
    for (int i = numPartitions; i < newNumPartitions; i++) {
      for (int j = 0; j < numSegmentsPerPartition; j++) {
        _helixResourceManager.addNewSegment(REALTIME_TABLE_NAME,
            SegmentMetadataMockUtils.mockSegmentMetadataWithPartitionInfo(RAW_TABLE_NAME,
                SEGMENT_NAME_PREFIX + (i * numSegmentsPerPartition + j), PARTITION_COLUMN, i), null);
      }
    }

    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);

    // Verify that the new partitions are assigned to the new servers. Due to the minimizeDataMovement algorithm, the
    // previous rebalance resulted in the older servers having 5 partition replicas each with the newer ones having 3
    // partition replicas each.
    instancePartitions = rebalanceResult.getInstanceAssignment().get(InstancePartitionsType.CONSUMING);
    for (int i = numPartitions; i < newNumPartitions; i++) {
      for (int j = 0; j < numReplicas; j++) {
        for (String instanceId : instancePartitions.getInstances(i, j)) {
          assertTrue(newServers.contains(instanceId),
              "Expected new partition " + i + " to be assigned to a new server, but found it on " + instanceId);
        }
      }
    }

    _helixResourceManager.deleteRealtimeTable(RAW_TABLE_NAME);

    for (int i = 0; i < numServers + numServersToAdd; i++) {
      stopAndDropFakeInstance(SERVER_INSTANCE_ID_PREFIX + i);
    }
  }

  private Map<String, Integer> getNumSegmentsPerServer(Map<String, Map<String, String>> segmentAssignment) {
    Map<String, Integer> numSegmentsPerServer = new HashMap<>();
    for (Map<String, String> instanceStateMap : segmentAssignment.values()) {
      for (String instanceId : instanceStateMap.keySet()) {
        numSegmentsPerServer.merge(instanceId, 1, Integer::sum);
      }
    }
    return numSegmentsPerServer;
  }

  private Map<String, Integer> getNumReplicaPartitionsPerServer(InstancePartitions instancePartitions) {
    Map<String, Integer> numPartitionsPerServer = new HashMap<>();
    for (int i = 0; i < instancePartitions.getNumReplicaGroups(); i++) {
      for (int j = 0; j < instancePartitions.getNumPartitions(); j++) {
        List<String> instances = instancePartitions.getInstances(j, i);
        for (String instanceId : instances) {
          numPartitionsPerServer.merge(instanceId, 1, Integer::sum);
        }
      }
    }
    return numPartitionsPerServer;
  }

  private int getNumSegmentsMoved(Map<String, Map<String, String>> oldSegmentAssignment,
      Map<String, Map<String, String>> newSegmentAssignment) {
    int numSegmentsMoved = 0;
    for (Map.Entry<String, Map<String, String>> entry : newSegmentAssignment.entrySet()) {
      String segmentName = entry.getKey();
      Map<String, String> newInstanceStateMap = entry.getValue();
      Map<String, String> oldInstanceStateMap = oldSegmentAssignment.get(segmentName);
      assertEquals(oldInstanceStateMap.size(), newInstanceStateMap.size());
      Set<String> commonInstances = new HashSet<>(newInstanceStateMap.keySet());
      commonInstances.retainAll(oldInstanceStateMap.keySet());
      numSegmentsMoved += newInstanceStateMap.size() - commonInstances.size();
    }
    return numSegmentsMoved;
  }

  @Test
  public void testRebalanceBatchSizePerServerErrors()
      throws Exception {
    int numServers = 3;

    for (int i = 0; i < numServers; i++) {
      String instanceId = SERVER_INSTANCE_ID_PREFIX + i;
      addFakeServerInstanceToAutoJoinHelixCluster(instanceId, true);
    }

    ExecutorService executorService = Executors.newFixedThreadPool(10);
    DefaultRebalancePreChecker preChecker = new DefaultRebalancePreChecker();
    preChecker.init(_helixResourceManager, executorService, 1);
    TableRebalancer tableRebalancer =
        new TableRebalancer(_helixManager, null, null, preChecker, _tableSizeReader, null);
    // Set up the table with 1 replication factor and strict replica group enabled
    TableConfig tableConfig = new TableConfigBuilder(TableType.OFFLINE).setTableName(RAW_TABLE_NAME)
        .setNumReplicas(1)
        .setRoutingConfig(
            new RoutingConfig(null, null, RoutingConfig.STRICT_REPLICA_GROUP_INSTANCE_SELECTOR_TYPE, false))
        .build();

    // Create the table
    addDummySchema(RAW_TABLE_NAME);
    _helixResourceManager.addTable(tableConfig);

    // Add the segments
    int numSegments = 10;
    for (int i = 0; i < numSegments; i++) {
      _helixResourceManager.addNewSegment(OFFLINE_TABLE_NAME,
          SegmentMetadataMockUtils.mockSegmentMetadata(RAW_TABLE_NAME, SEGMENT_NAME_PREFIX + i), null);
    }
    Map<String, Map<String, String>> oldSegmentAssignment =
        _helixResourceManager.getTableIdealState(OFFLINE_TABLE_NAME).getRecord().getMapFields();
    for (Map.Entry<String, Map<String, String>> entry : oldSegmentAssignment.entrySet()) {
      assertEquals(entry.getValue().size(), 1);
    }

    // Rebalance should throw an exception due to setting an unacceptable value for batchSizePerServer
    final RebalanceConfig rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setBatchSizePerServer(0);
    assertThrows(IllegalStateException.class, () -> tableRebalancer.rebalance(tableConfig, rebalanceConfig, null));

    final RebalanceConfig rebalanceConfig2 = new RebalanceConfig();
    rebalanceConfig2.setBatchSizePerServer(-2);
    assertThrows(IllegalStateException.class, () -> tableRebalancer.rebalance(tableConfig, rebalanceConfig2, null));

    _helixResourceManager.deleteOfflineTable(RAW_TABLE_NAME);

    for (int i = 0; i < numServers; i++) {
      stopAndDropFakeInstance(SERVER_INSTANCE_ID_PREFIX + i);
    }
    executorService.shutdown();
  }

  @Test
  public void testRebalancePreCheckerDiskUtil()
      throws Exception {
    int numServers = 3;
    // Mock disk usage
    Map<String, DiskUsageInfo> diskUsageInfoMap = new HashMap<>();

    for (int i = 0; i < numServers; i++) {
      String instanceId = "preCheckerDiskUtil_" + SERVER_INSTANCE_ID_PREFIX + i;
      addFakeServerInstanceToAutoJoinHelixCluster(instanceId, true);
      DiskUsageInfo diskUsageInfo1 = new DiskUsageInfo(instanceId, "", 1000L, 200L, System.currentTimeMillis());
      diskUsageInfoMap.put(instanceId, diskUsageInfo1);
    }

    ExecutorService executorService = Executors.newFixedThreadPool(10);
    DefaultRebalancePreChecker preChecker = new DefaultRebalancePreChecker();
    preChecker.init(_helixResourceManager, executorService, 0.5);
    TableRebalancer tableRebalancer =
        new TableRebalancer(_helixManager, null, null, preChecker, _tableSizeReader, null);
    TableConfig tableConfig =
        new TableConfigBuilder(TableType.OFFLINE).setTableName(RAW_TABLE_NAME).setNumReplicas(NUM_REPLICAS).build();

    // Create the table
    addDummySchema(RAW_TABLE_NAME);
    _helixResourceManager.addTable(tableConfig);

    // Add the segments
    int numSegments = 10;
    for (int i = 0; i < numSegments; i++) {
      _helixResourceManager.addNewSegment(OFFLINE_TABLE_NAME,
          SegmentMetadataMockUtils.mockSegmentMetadata(RAW_TABLE_NAME, SEGMENT_NAME_PREFIX + i), null);
    }
    Map<String, Map<String, String>> oldSegmentAssignment =
        _helixResourceManager.getTableIdealState(OFFLINE_TABLE_NAME).getRecord().getMapFields();

    // Add 3 more servers
    int numServersToAdd = 3;
    for (int i = 0; i < numServersToAdd; i++) {
      String instanceId = "preCheckerDiskUtil_" + SERVER_INSTANCE_ID_PREFIX + (numServers + i);
      addFakeServerInstanceToAutoJoinHelixCluster(instanceId, true);
      DiskUsageInfo diskUsageInfo = new DiskUsageInfo(instanceId, "", 1000L, 200L, System.currentTimeMillis());
      diskUsageInfoMap.put(instanceId, diskUsageInfo);
    }

    ResourceUtilizationInfo.setDiskUsageInfo(diskUsageInfoMap);

    // Rebalance in dry-run mode
    RebalanceConfig rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setDryRun(true);
    rebalanceConfig.setPreChecks(true);

    RebalanceResult rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);
    Map<String, RebalancePreCheckerResult> preCheckResult = rebalanceResult.getPreChecksResult();
    assertNotNull(preCheckResult);
    assertTrue(preCheckResult.containsKey(DefaultRebalancePreChecker.DISK_UTILIZATION_DURING_REBALANCE));
    assertEquals(preCheckResult.get(DefaultRebalancePreChecker.DISK_UTILIZATION_DURING_REBALANCE).getPreCheckStatus(),
        RebalancePreCheckerResult.PreCheckStatus.PASS);
    assertTrue(preCheckResult.get(DefaultRebalancePreChecker.DISK_UTILIZATION_DURING_REBALANCE)
        .getMessage()
        .startsWith("Within threshold"));
    assertTrue(preCheckResult.containsKey(DefaultRebalancePreChecker.DISK_UTILIZATION_AFTER_REBALANCE));
    assertEquals(preCheckResult.get(DefaultRebalancePreChecker.DISK_UTILIZATION_AFTER_REBALANCE).getPreCheckStatus(),
        RebalancePreCheckerResult.PreCheckStatus.PASS);
    assertTrue(preCheckResult.get(DefaultRebalancePreChecker.DISK_UTILIZATION_AFTER_REBALANCE)
        .getMessage()
        .startsWith("Within threshold"));

    for (int i = 0; i < numServers + numServersToAdd; i++) {
      String instanceId = "preCheckerDiskUtil_" + SERVER_INSTANCE_ID_PREFIX + i;
      DiskUsageInfo diskUsageInfo = new DiskUsageInfo(instanceId, "", 1000L, 755L, System.currentTimeMillis());
      diskUsageInfoMap.put(instanceId, diskUsageInfo);
    }

    rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setDryRun(true);
    rebalanceConfig.setPreChecks(true);

    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);
    preCheckResult = rebalanceResult.getPreChecksResult();
    assertNotNull(preCheckResult);
    assertTrue(preCheckResult.containsKey(DefaultRebalancePreChecker.DISK_UTILIZATION_DURING_REBALANCE));
    assertEquals(preCheckResult.get(DefaultRebalancePreChecker.DISK_UTILIZATION_DURING_REBALANCE).getPreCheckStatus(),
        RebalancePreCheckerResult.PreCheckStatus.ERROR);
    assertTrue(preCheckResult.containsKey(DefaultRebalancePreChecker.DISK_UTILIZATION_AFTER_REBALANCE));
    assertEquals(preCheckResult.get(DefaultRebalancePreChecker.DISK_UTILIZATION_AFTER_REBALANCE).getPreCheckStatus(),
        RebalancePreCheckerResult.PreCheckStatus.ERROR);

    _helixResourceManager.deleteOfflineTable(RAW_TABLE_NAME);

    for (int i = 0; i < numServers; i++) {
      stopAndDropFakeInstance("preCheckerDiskUtil_" + SERVER_INSTANCE_ID_PREFIX + i);
    }
    for (int i = 0; i < numServersToAdd; i++) {
      stopAndDropFakeInstance("preCheckerDiskUtil_" + SERVER_INSTANCE_ID_PREFIX + (numServers + i));
    }
    executorService.shutdown();
  }

  @Test
  public void testRebalancePreCheckerRebalanceConfig()
      throws Exception {
    int numServers = 3;

    for (int i = 0; i < numServers; i++) {
      String instanceId = "preCheckerRebalanceConfig_" + SERVER_INSTANCE_ID_PREFIX + i;
      addFakeServerInstanceToAutoJoinHelixCluster(instanceId, true);
    }

    ExecutorService executorService = Executors.newFixedThreadPool(10);
    DefaultRebalancePreChecker preChecker = new DefaultRebalancePreChecker();
    preChecker.init(_helixResourceManager, executorService, 0.5);
    TableRebalancer tableRebalancer =
        new TableRebalancer(_helixManager, null, null, preChecker, _tableSizeReader, null);
    TableConfig tableConfig =
        new TableConfigBuilder(TableType.REALTIME).setTableName(RAW_TABLE_NAME)
            .setNumReplicas(2)
            .setStreamConfigs(FakeStreamConfigUtils.getDefaultLowLevelStreamConfigs().getStreamConfigsMap())
            .build();

    // Create the table
    addDummySchema(RAW_TABLE_NAME);
    _helixResourceManager.addTable(tableConfig);

    // Add the segments
    int numSegments = 10;
    for (int i = 0; i < numSegments; i++) {
      _helixResourceManager.addNewSegment(REALTIME_TABLE_NAME,
          SegmentMetadataMockUtils.mockSegmentMetadata(RAW_TABLE_NAME, SEGMENT_NAME_PREFIX + i), null);
    }

    RebalanceConfig rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setDryRun(true);
    rebalanceConfig.setPreChecks(true);

    // dry-run with default rebalance config
    RebalanceResult rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    RebalancePreCheckerResult preCheckerResult =
        rebalanceResult.getPreChecksResult().get(DefaultRebalancePreChecker.REBALANCE_CONFIG_OPTIONS);
    assertNotNull(preCheckerResult);
    assertEquals(preCheckerResult.getPreCheckStatus(), RebalancePreCheckerResult.PreCheckStatus.WARN);
    assertEquals(preCheckerResult.getMessage(), "includeConsuming is disabled for a realtime table.");

    // trigger bootstrap and bestEfforts warning
    rebalanceConfig.setIncludeConsuming(true);
    rebalanceConfig.setBootstrap(true);
    rebalanceConfig.setBestEfforts(true);
    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    preCheckerResult = rebalanceResult.getPreChecksResult().get(DefaultRebalancePreChecker.REBALANCE_CONFIG_OPTIONS);
    assertNotNull(preCheckerResult);
    assertEquals(preCheckerResult.getPreCheckStatus(), RebalancePreCheckerResult.PreCheckStatus.WARN);
    assertEquals(preCheckerResult.getMessage(),
        "bestEfforts is enabled, only enable it if you know what you are doing\n"
            + "bootstrap is enabled which can cause a large amount of data movement, double check if this is "
            + "intended");

    // test updateTargetTier warning
    rebalanceConfig.setUpdateTargetTier(false);
    rebalanceConfig.setBootstrap(false);
    rebalanceConfig.setBestEfforts(false);
    tableConfig = new TableConfigBuilder(TableType.REALTIME).setTableName(RAW_TABLE_NAME)
        .setTierConfigList(Collections.singletonList(
            new TierConfig("dummyTier", TierFactory.TIME_SEGMENT_SELECTOR_TYPE, "7d", null,
                TierFactory.PINOT_SERVER_STORAGE_TYPE,
                TagNameUtils.getRealtimeTagForTenant(TagNameUtils.DEFAULT_TENANT_NAME), null, null)))
        .build();

    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    preCheckerResult = rebalanceResult.getPreChecksResult().get(DefaultRebalancePreChecker.REBALANCE_CONFIG_OPTIONS);
    assertNotNull(preCheckerResult);
    assertEquals(preCheckerResult.getPreCheckStatus(), RebalancePreCheckerResult.PreCheckStatus.WARN);
    assertEquals(preCheckerResult.getMessage(), "updateTargetTier should be enabled when tier configs are present");

    // trigger downtime warning
    TableConfig newTableConfig =
        new TableConfigBuilder(TableType.REALTIME).setTableName(RAW_TABLE_NAME).setNumReplicas(3).build();

    rebalanceConfig.setBootstrap(false);
    rebalanceConfig.setBestEfforts(false);
    rebalanceConfig.setDowntime(true);
    // udpateTargetTier is false, but there is no tier config so we should not see a warning
    rebalanceConfig.setUpdateTargetTier(false);
    rebalanceResult = tableRebalancer.rebalance(newTableConfig, rebalanceConfig, null);
    preCheckerResult = rebalanceResult.getPreChecksResult().get(DefaultRebalancePreChecker.REBALANCE_CONFIG_OPTIONS);
    assertNotNull(preCheckerResult);
    assertEquals(preCheckerResult.getPreCheckStatus(), RebalancePreCheckerResult.PreCheckStatus.WARN);
    assertEquals(preCheckerResult.getMessage(),
        "Number of replicas (3) is greater than 1, downtime is not recommended.");

    // no downtime warning with 1 replica
    newTableConfig = new TableConfigBuilder(TableType.REALTIME).setTableName(RAW_TABLE_NAME).setNumReplicas(1).build();

    rebalanceConfig.setDowntime(true);
    rebalanceResult = tableRebalancer.rebalance(newTableConfig, rebalanceConfig, null);
    preCheckerResult = rebalanceResult.getPreChecksResult().get(DefaultRebalancePreChecker.REBALANCE_CONFIG_OPTIONS);
    assertNotNull(preCheckerResult);
    assertEquals(preCheckerResult.getPreCheckStatus(), RebalancePreCheckerResult.PreCheckStatus.PASS);
    assertEquals(preCheckerResult.getMessage(), "All rebalance parameters look good");

    // trigger pauseless table rebalance warning
    IngestionConfig ingestionConfig = new IngestionConfig();
    StreamIngestionConfig streamIngestionConfig = new StreamIngestionConfig(
        Collections.singletonList(FakeStreamConfigUtils.getDefaultLowLevelStreamConfigs().getStreamConfigsMap()));
    streamIngestionConfig.setPauselessConsumptionEnabled(true);
    ingestionConfig.setStreamIngestionConfig(streamIngestionConfig);
    newTableConfig.setIngestionConfig(ingestionConfig);

    rebalanceConfig.setDowntime(true);
    rebalanceResult = tableRebalancer.rebalance(newTableConfig, rebalanceConfig, null);
    preCheckerResult = rebalanceResult.getPreChecksResult().get(DefaultRebalancePreChecker.REBALANCE_CONFIG_OPTIONS);
    assertNotNull(preCheckerResult);
    assertEquals(preCheckerResult.getPreCheckStatus(), RebalancePreCheckerResult.PreCheckStatus.WARN);
    assertEquals(preCheckerResult.getMessage(),
        "Replication of the table is 1, which is not recommended for pauseless tables as it may cause data loss "
            + "during rebalance");

    newTableConfig = new TableConfigBuilder(TableType.REALTIME).setTableName(RAW_TABLE_NAME).setNumReplicas(3).build();
    newTableConfig.setIngestionConfig(ingestionConfig);

    rebalanceResult = tableRebalancer.rebalance(newTableConfig, rebalanceConfig, null);
    preCheckerResult = rebalanceResult.getPreChecksResult().get(DefaultRebalancePreChecker.REBALANCE_CONFIG_OPTIONS);
    assertNotNull(preCheckerResult);
    assertEquals(preCheckerResult.getPreCheckStatus(), RebalancePreCheckerResult.PreCheckStatus.WARN);
    assertEquals(preCheckerResult.getMessage(),
        "Number of replicas (3) is greater than 1, downtime is not recommended.\nDowntime or minAvailableReplicas=0 "
            + "for pauseless tables may cause data loss during rebalance");

    rebalanceConfig.setDowntime(false);
    rebalanceConfig.setMinAvailableReplicas(-3);
    rebalanceResult = tableRebalancer.rebalance(newTableConfig, rebalanceConfig, null);
    preCheckerResult = rebalanceResult.getPreChecksResult().get(DefaultRebalancePreChecker.REBALANCE_CONFIG_OPTIONS);
    assertNotNull(preCheckerResult);
    assertEquals(preCheckerResult.getPreCheckStatus(), RebalancePreCheckerResult.PreCheckStatus.WARN);
    assertEquals(preCheckerResult.getMessage(),
        "Downtime or minAvailableReplicas=0 for pauseless tables may cause data loss during rebalance");

    rebalanceConfig.setDowntime(false);
    rebalanceConfig.setMinAvailableReplicas(0);
    rebalanceResult = tableRebalancer.rebalance(newTableConfig, rebalanceConfig, null);
    preCheckerResult = rebalanceResult.getPreChecksResult().get(DefaultRebalancePreChecker.REBALANCE_CONFIG_OPTIONS);
    assertNotNull(preCheckerResult);
    assertEquals(preCheckerResult.getPreCheckStatus(), RebalancePreCheckerResult.PreCheckStatus.WARN);
    assertEquals(preCheckerResult.getMessage(),
        "Downtime or minAvailableReplicas=0 for pauseless tables may cause data loss during rebalance");

    // test pass
    rebalanceConfig.setMinAvailableReplicas(1);
    rebalanceResult = tableRebalancer.rebalance(newTableConfig, rebalanceConfig, null);
    preCheckerResult = rebalanceResult.getPreChecksResult().get(DefaultRebalancePreChecker.REBALANCE_CONFIG_OPTIONS);
    assertNotNull(preCheckerResult);
    assertEquals(preCheckerResult.getPreCheckStatus(), RebalancePreCheckerResult.PreCheckStatus.PASS);
    assertEquals(preCheckerResult.getMessage(), "All rebalance parameters look good");

    // Add more segments
    int additionalNumSegments = DefaultRebalancePreChecker.SEGMENT_ADD_THRESHOLD + 1;
    for (int i = 0; i < additionalNumSegments; i++) {
      _helixResourceManager.addNewSegment(REALTIME_TABLE_NAME,
          SegmentMetadataMockUtils.mockSegmentMetadata(RAW_TABLE_NAME, SEGMENT_NAME_PREFIX + (numSegments + i)), null);
    }

    // Add one more server instance
    String instanceId = "preCheckerRebalanceConfig_" + SERVER_INSTANCE_ID_PREFIX + numServers;
    addFakeServerInstanceToAutoJoinHelixCluster(instanceId, true);

    // change num replicas from 3 to 4
    newTableConfig = new TableConfigBuilder(TableType.REALTIME).setTableName(RAW_TABLE_NAME).setNumReplicas(4).build();

    // now the new server (the 4th server) should expect to be added all the existing segments (including consuming)
    rebalanceResult = tableRebalancer.rebalance(newTableConfig, rebalanceConfig, null);
    int expectedNumSegmentsToAdd = FakeStreamConfigUtils.DEFAULT_NUM_PARTITIONS + additionalNumSegments + numSegments;
    assertEquals(rebalanceResult.getRebalanceSummaryResult()
        .getServerInfo()
        .getServerSegmentChangeInfo()
        .get(instanceId)
        .getSegmentsAdded(), expectedNumSegmentsToAdd);
    assertEquals(rebalanceResult.getRebalanceSummaryResult().getSegmentInfo().getMaxSegmentsAddedToASingleServer(),
        expectedNumSegmentsToAdd);
    preCheckerResult = rebalanceResult.getPreChecksResult().get(DefaultRebalancePreChecker.REBALANCE_CONFIG_OPTIONS);
    assertNotNull(preCheckerResult);
    assertEquals(preCheckerResult.getPreCheckStatus(), RebalancePreCheckerResult.PreCheckStatus.WARN);
    assertEquals(preCheckerResult.getMessage(),
        "Number of segments to add to a single server (" + expectedNumSegmentsToAdd + ") is high (>"
            + DefaultRebalancePreChecker.SEGMENT_ADD_THRESHOLD + "). It is recommended to set batchSizePerServer to "
            + DefaultRebalancePreChecker.RECOMMENDED_BATCH_SIZE + " or lower to avoid excessive load on servers.");

    rebalanceConfig.setBatchSizePerServer(DefaultRebalancePreChecker.RECOMMENDED_BATCH_SIZE);
    rebalanceResult = tableRebalancer.rebalance(newTableConfig, rebalanceConfig, null);
    preCheckerResult = rebalanceResult.getPreChecksResult().get(DefaultRebalancePreChecker.REBALANCE_CONFIG_OPTIONS);
    assertNotNull(preCheckerResult);
    assertEquals(preCheckerResult.getPreCheckStatus(), RebalancePreCheckerResult.PreCheckStatus.PASS);
    assertEquals(preCheckerResult.getMessage(), "All rebalance parameters look good");

    _helixResourceManager.deleteRealtimeTable(RAW_TABLE_NAME);

    for (int i = 0; i < numServers + 1; i++) {
      stopAndDropFakeInstance("preCheckerRebalanceConfig_" + SERVER_INSTANCE_ID_PREFIX + i);
    }
    executorService.shutdown();
  }

  /// Tests rebalance with tier configs
  /// Add 10 segments, with segment metadata end time 3 days apart starting from now to 30 days ago
  /// 1. run rebalance - should see no change
  /// 2. add nodes for tiers and run rebalance - should see no change
  /// 3. add tier config and run rebalance - should see changed assignment
  @Test
  public void testRebalanceWithTiers()
      throws Exception {
    int numServers = 3;
    for (int i = 0; i < numServers; i++) {
      addFakeServerInstanceToAutoJoinHelixCluster(NO_TIER_NAME + "_" + SERVER_INSTANCE_ID_PREFIX + i, false);
    }
    _helixResourceManager.createServerTenant(new Tenant(TenantRole.SERVER, NO_TIER_NAME, numServers, numServers, 0));

    TableConfig tableConfig = new TableConfigBuilder(TableType.OFFLINE).setTableName(TIERED_TABLE_NAME)
        .setNumReplicas(NUM_REPLICAS)
        .setServerTenant(NO_TIER_NAME)
        .build();
    // Create the table
    addDummySchema(TIERED_TABLE_NAME);
    _helixResourceManager.addTable(tableConfig);

    // Add the segments
    int numSegments = 10;
    long nowInDays = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis());
    // keep decreasing end time from today in steps of 3. 3 segments don't move. 3 segment on tierA. 4 segments on tierB
    for (int i = 0; i < numSegments; i++) {
      _helixResourceManager.addNewSegment(OFFLINE_TIERED_TABLE_NAME,
          SegmentMetadataMockUtils.mockSegmentMetadataWithEndTimeInfo(TIERED_TABLE_NAME, SEGMENT_NAME_PREFIX + i,
              nowInDays), null);
      nowInDays -= 3;
    }
    Map<String, Map<String, String>> oldSegmentAssignment =
        _helixResourceManager.getTableIdealState(OFFLINE_TIERED_TABLE_NAME).getRecord().getMapFields();

    TableRebalancer tableRebalancer = new TableRebalancer(_helixManager);

    // Try dry-run summary mode
    RebalanceConfig rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setDryRun(true);
    RebalanceResult rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.NO_OP);
    RebalanceSummaryResult rebalanceSummaryResult = rebalanceResult.getRebalanceSummaryResult();
    assertNotNull(rebalanceSummaryResult);
    assertNotNull(rebalanceSummaryResult.getServerInfo());
    assertNotNull(rebalanceSummaryResult.getSegmentInfo());
    assertEquals(rebalanceSummaryResult.getSegmentInfo().getTotalSegmentsToBeMoved(), 0);
    assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getValueBeforeRebalance(), 3);
    assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getExpectedValueAfterRebalance(), 3);
    assertEquals(rebalanceSummaryResult.getServerInfo().getNumServersGettingNewSegments(), 0);
    assertNotNull(rebalanceSummaryResult.getTagsInfo());
    assertEquals(rebalanceSummaryResult.getTagsInfo().size(), 1);
    assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getTagName(),
        TagNameUtils.getOfflineTagForTenant(NO_TIER_NAME));
    assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumSegmentsToDownload(), 0);
    assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumSegmentsUnchanged(), numSegments * NUM_REPLICAS);
    assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumServerParticipants(), numServers);
    assertNotNull(rebalanceResult.getInstanceAssignment());
    assertNotNull(rebalanceResult.getSegmentAssignment());

    // Run actual table rebalance
    rebalanceResult = tableRebalancer.rebalance(tableConfig, new RebalanceConfig(), null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.NO_OP);
    // Segment assignment should not change
    assertEquals(rebalanceResult.getSegmentAssignment(), oldSegmentAssignment);

    // add 3 nodes tierA, 3 nodes tierB
    for (int i = 0; i < 3; i++) {
      addFakeServerInstanceToAutoJoinHelixCluster(TIER_A_NAME + "_" + SERVER_INSTANCE_ID_PREFIX + i, false);
    }
    _helixResourceManager.createServerTenant(new Tenant(TenantRole.SERVER, TIER_A_NAME, 3, 3, 0));
    for (int i = 0; i < 3; i++) {
      addFakeServerInstanceToAutoJoinHelixCluster(TIER_B_NAME + "_" + SERVER_INSTANCE_ID_PREFIX + i, false);
    }
    _helixResourceManager.createServerTenant(new Tenant(TenantRole.SERVER, TIER_B_NAME, 3, 3, 0));

    // Try dry-run summary mode, should be no-op
    rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setDryRun(true);
    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.NO_OP);
    rebalanceSummaryResult = rebalanceResult.getRebalanceSummaryResult();
    assertNotNull(rebalanceSummaryResult);
    assertNotNull(rebalanceSummaryResult.getServerInfo());
    assertNotNull(rebalanceSummaryResult.getSegmentInfo());
    assertEquals(rebalanceSummaryResult.getSegmentInfo().getTotalSegmentsToBeMoved(), 0);
    assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getValueBeforeRebalance(), 3);
    assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getExpectedValueAfterRebalance(), 3);
    assertEquals(rebalanceSummaryResult.getServerInfo().getNumServersGettingNewSegments(), 0);
    assertNotNull(rebalanceSummaryResult.getTagsInfo());
    assertEquals(rebalanceSummaryResult.getTagsInfo().size(), 1);
    assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getTagName(),
        TagNameUtils.getOfflineTagForTenant(NO_TIER_NAME));
    assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumSegmentsToDownload(), 0);
    assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumSegmentsUnchanged(), numSegments * NUM_REPLICAS);
    assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumServerParticipants(), numServers);
    assertNotNull(rebalanceResult.getInstanceAssignment());
    assertNotNull(rebalanceResult.getSegmentAssignment());

    // rebalance is NOOP and no change in assignment caused by new instances
    rebalanceResult = tableRebalancer.rebalance(tableConfig, new RebalanceConfig(), null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.NO_OP);
    // Segment assignment should not change
    assertEquals(rebalanceResult.getSegmentAssignment(), oldSegmentAssignment);

    // add tier config
    ArrayList<String> fixedTierSegments =
        Lists.newArrayList(SEGMENT_NAME_PREFIX + 6, SEGMENT_NAME_PREFIX + 3, SEGMENT_NAME_PREFIX + 1);
    tableConfig.setTierConfigsList(Lists.newArrayList(
        new TierConfig(TIER_A_NAME, TierFactory.TIME_SEGMENT_SELECTOR_TYPE, "7d", null,
            TierFactory.PINOT_SERVER_STORAGE_TYPE, TIER_A_NAME + "_OFFLINE", null, null),
        new TierConfig(TIER_B_NAME, TierFactory.TIME_SEGMENT_SELECTOR_TYPE, "15d", null,
            TierFactory.PINOT_SERVER_STORAGE_TYPE, TIER_B_NAME + "_OFFLINE", null, null),
        new TierConfig(TIER_FIXED_NAME, TierFactory.FIXED_SEGMENT_SELECTOR_TYPE, null, fixedTierSegments,
            TierFactory.PINOT_SERVER_STORAGE_TYPE, NO_TIER_NAME + "_OFFLINE", null, null)));
    _helixResourceManager.updateTableConfig(tableConfig);

    // Try dry-run summary mode, some moves should occur
    rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setDryRun(true);
    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);
    rebalanceSummaryResult = rebalanceResult.getRebalanceSummaryResult();
    assertNotNull(rebalanceSummaryResult);
    assertNotNull(rebalanceSummaryResult.getServerInfo());
    assertNotNull(rebalanceSummaryResult.getSegmentInfo());
    assertEquals(rebalanceSummaryResult.getSegmentInfo().getTotalSegmentsToBeMoved(), 15);
    assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getValueBeforeRebalance(), 3);
    assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getExpectedValueAfterRebalance(), 9);
    assertEquals(rebalanceSummaryResult.getServerInfo().getNumServersGettingNewSegments(), 6);
    assertNotNull(rebalanceSummaryResult.getTagsInfo());
    assertEquals(rebalanceSummaryResult.getTagsInfo().size(), 3);
    Map<String, RebalanceSummaryResult.TagInfo> tenantInfoMap = rebalanceSummaryResult.getTagsInfo()
        .stream()
        .collect(Collectors.toMap(RebalanceSummaryResult.TagInfo::getTagName, info -> info));
    assertTrue(tenantInfoMap.containsKey(TagNameUtils.getOfflineTagForTenant(NO_TIER_NAME)));
    assertTrue(tenantInfoMap.containsKey(TagNameUtils.getOfflineTagForTenant(TIER_A_NAME)));
    assertTrue(tenantInfoMap.containsKey(TagNameUtils.getOfflineTagForTenant(TIER_B_NAME)));
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant(NO_TIER_NAME)).getNumSegmentsToDownload(), 0);
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant(NO_TIER_NAME)).getNumSegmentsUnchanged(),
        5 * NUM_REPLICAS);
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant(NO_TIER_NAME)).getNumServerParticipants(),
        numServers);
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant(TIER_A_NAME)).getNumSegmentsToDownload(),
        NUM_REPLICAS);
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant(TIER_A_NAME)).getNumSegmentsUnchanged(), 0);
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant(TIER_A_NAME)).getNumServerParticipants(), 3);
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant(TIER_B_NAME)).getNumSegmentsToDownload(),
        4 * NUM_REPLICAS);
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant(TIER_B_NAME)).getNumSegmentsUnchanged(), 0);
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant(TIER_B_NAME)).getNumServerParticipants(), 3);
    assertNotNull(rebalanceResult.getInstanceAssignment());
    assertNotNull(rebalanceResult.getTierInstanceAssignment());
    assertNotNull(rebalanceResult.getSegmentAssignment());

    // rebalance should change assignment. Enable batching
    rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setBatchSizePerServer(2);
    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);

    // check that segments have moved to tiers
    Map<String, Map<String, String>> tierSegmentAssignment = rebalanceResult.getSegmentAssignment();
    for (Map.Entry<String, Map<String, String>> entry : tierSegmentAssignment.entrySet()) {
      String segment = entry.getKey();
      int segId = Integer.parseInt(segment.split("_")[1]);
      Map<String, String> instanceStateMap = entry.getValue();
      String expectedPrefix;
      if (fixedTierSegments.contains(segment)) {
        expectedPrefix = NO_TIER_NAME + "_" + SERVER_INSTANCE_ID_PREFIX;
      } else if (segId > 4) {
        expectedPrefix = TIER_B_NAME + "_" + SERVER_INSTANCE_ID_PREFIX;
      } else if (segId > 2) {
        expectedPrefix = TIER_A_NAME + "_" + SERVER_INSTANCE_ID_PREFIX;
      } else {
        expectedPrefix = NO_TIER_NAME + "_" + SERVER_INSTANCE_ID_PREFIX;
      }
      for (String instance : instanceStateMap.keySet()) {
        assertTrue(instance.startsWith(expectedPrefix));
      }
    }
    _helixResourceManager.deleteOfflineTable(TIERED_TABLE_NAME);
  }

  @Test
  public void testRebalanceWithTiersAndInstanceAssignments()
      throws Exception {
    int numServers = 3;
    for (int i = 0; i < numServers; i++) {
      addFakeServerInstanceToAutoJoinHelixCluster(
          "replicaAssignment" + NO_TIER_NAME + "_" + SERVER_INSTANCE_ID_PREFIX + i, false);
    }
    _helixResourceManager.createServerTenant(
        new Tenant(TenantRole.SERVER, "replicaAssignment" + NO_TIER_NAME, numServers, numServers, 0));

    TableConfig tableConfig = new TableConfigBuilder(TableType.OFFLINE).setTableName(TIERED_TABLE_NAME)
        .setNumReplicas(NUM_REPLICAS)
        .setServerTenant("replicaAssignment" + NO_TIER_NAME)
        .build();
    // Create the table
    addDummySchema(TIERED_TABLE_NAME);
    _helixResourceManager.addTable(tableConfig);

    // Add the segments
    int numSegments = 10;
    long nowInDays = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis());

    for (int i = 0; i < numSegments; i++) {
      _helixResourceManager.addNewSegment(OFFLINE_TIERED_TABLE_NAME,
          SegmentMetadataMockUtils.mockSegmentMetadataWithEndTimeInfo(TIERED_TABLE_NAME, SEGMENT_NAME_PREFIX + i,
              nowInDays), null);
    }
    Map<String, Map<String, String>> oldSegmentAssignment =
        _helixResourceManager.getTableIdealState(OFFLINE_TIERED_TABLE_NAME).getRecord().getMapFields();

    DefaultRebalancePreChecker preChecker = new DefaultRebalancePreChecker();
    ExecutorService executorService = Executors.newFixedThreadPool(10);
    preChecker.init(_helixResourceManager, executorService, 1);
    TableRebalancer tableRebalancer =
        new TableRebalancer(_helixManager, null, null, preChecker, _tableSizeReader, null);

    // Try dry-run summary mode
    RebalanceConfig rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setDryRun(true);
    RebalanceResult rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.NO_OP);
    RebalanceSummaryResult rebalanceSummaryResult = rebalanceResult.getRebalanceSummaryResult();
    assertNotNull(rebalanceSummaryResult);
    assertNotNull(rebalanceSummaryResult.getServerInfo());
    assertNotNull(rebalanceSummaryResult.getSegmentInfo());
    assertEquals(rebalanceSummaryResult.getSegmentInfo().getTotalSegmentsToBeMoved(), 0);
    assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getValueBeforeRebalance(), 3);
    assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getExpectedValueAfterRebalance(), 3);
    assertEquals(rebalanceSummaryResult.getServerInfo().getNumServersGettingNewSegments(), 0);
    assertNotNull(rebalanceSummaryResult.getTagsInfo());
    assertEquals(rebalanceSummaryResult.getTagsInfo().size(), 1);
    assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getTagName(),
        TagNameUtils.getOfflineTagForTenant("replicaAssignment" + NO_TIER_NAME));
    assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumSegmentsToDownload(), 0);
    assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumSegmentsUnchanged(), numSegments * NUM_REPLICAS);
    assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumServerParticipants(), numServers);
    assertNotNull(rebalanceResult.getInstanceAssignment());
    assertNotNull(rebalanceResult.getSegmentAssignment());

    rebalanceResult = tableRebalancer.rebalance(tableConfig, new RebalanceConfig(), null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.NO_OP);
    // Segment assignment should not change
    assertEquals(rebalanceResult.getSegmentAssignment(), oldSegmentAssignment);

    // add 6 nodes tierA
    for (int i = 0; i < 6; i++) {
      addFakeServerInstanceToAutoJoinHelixCluster(
          "replicaAssignment" + TIER_A_NAME + "_" + SERVER_INSTANCE_ID_PREFIX + i, false);
    }
    _helixResourceManager.createServerTenant(new Tenant(TenantRole.SERVER, "replicaAssignment" + TIER_A_NAME, 6, 6, 0));

    // Try dry-run summary mode
    rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setDryRun(true);
    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.NO_OP);
    rebalanceSummaryResult = rebalanceResult.getRebalanceSummaryResult();
    assertNotNull(rebalanceResult.getRebalanceSummaryResult());
    assertNotNull(rebalanceSummaryResult.getServerInfo());
    assertNotNull(rebalanceSummaryResult.getSegmentInfo());
    assertEquals(rebalanceSummaryResult.getSegmentInfo().getTotalSegmentsToBeMoved(), 0);
    assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getValueBeforeRebalance(), 3);
    assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getExpectedValueAfterRebalance(), 3);
    assertEquals(rebalanceSummaryResult.getServerInfo().getNumServersGettingNewSegments(), 0);
    assertNotNull(rebalanceSummaryResult.getTagsInfo());
    assertEquals(rebalanceSummaryResult.getTagsInfo().size(), 1);
    assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getTagName(),
        TagNameUtils.getOfflineTagForTenant("replicaAssignment" + NO_TIER_NAME));
    assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumSegmentsToDownload(), 0);
    assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumSegmentsUnchanged(), numSegments * NUM_REPLICAS);
    assertEquals(rebalanceSummaryResult.getTagsInfo().get(0).getNumServerParticipants(), numServers);
    assertNotNull(rebalanceResult.getInstanceAssignment());
    assertNotNull(rebalanceResult.getSegmentAssignment());

    // rebalance is NOOP and no change in assignment caused by new instances
    rebalanceResult = tableRebalancer.rebalance(tableConfig, new RebalanceConfig(), null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.NO_OP);
    // Segment assignment should not change
    assertEquals(rebalanceResult.getSegmentAssignment(), oldSegmentAssignment);

    // add tier config
    tableConfig.setTierConfigsList(Lists.newArrayList(
        new TierConfig(TIER_A_NAME, TierFactory.TIME_SEGMENT_SELECTOR_TYPE, "0d", null,
            TierFactory.PINOT_SERVER_STORAGE_TYPE, "replicaAssignment" + TIER_A_NAME + "_OFFLINE", null, null)));
    _helixResourceManager.updateTableConfig(tableConfig);

    // Try dry-run summary mode
    rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setDryRun(true);
    rebalanceConfig.setPreChecks(true);
    rebalanceConfig.setReassignInstances(true);
    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);
    assertEquals(
        rebalanceResult.getPreChecksResult().get(DefaultRebalancePreChecker.REPLICA_GROUPS_INFO).getPreCheckStatus(),
        RebalancePreCheckerResult.PreCheckStatus.PASS);
    assertEquals(rebalanceResult.getPreChecksResult().get(DefaultRebalancePreChecker.REPLICA_GROUPS_INFO).getMessage(),
        "OFFLINE segments - Replica Groups are not enabled, replication: " + NUM_REPLICAS + "\n" + TIER_A_NAME
            + " tier - Replica Groups are not enabled, replication: " + NUM_REPLICAS);
    rebalanceSummaryResult = rebalanceResult.getRebalanceSummaryResult();
    assertNotNull(rebalanceResult.getRebalanceSummaryResult());
    assertNotNull(rebalanceSummaryResult.getServerInfo());
    assertNotNull(rebalanceSummaryResult.getSegmentInfo());
    assertEquals(rebalanceSummaryResult.getSegmentInfo().getTotalSegmentsToBeMoved(), 30);
    assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getValueBeforeRebalance(), 3);
    assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getExpectedValueAfterRebalance(), 6);
    assertEquals(rebalanceSummaryResult.getServerInfo().getNumServersGettingNewSegments(), 6);
    assertNotNull(rebalanceSummaryResult.getTagsInfo());
    assertEquals(rebalanceSummaryResult.getTagsInfo().size(), 2);
    Map<String, RebalanceSummaryResult.TagInfo> tenantInfoMap = rebalanceSummaryResult.getTagsInfo()
        .stream()
        .collect(Collectors.toMap(RebalanceSummaryResult.TagInfo::getTagName, info -> info));
    assertTrue(tenantInfoMap.containsKey(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + NO_TIER_NAME)));
    assertTrue(tenantInfoMap.containsKey(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + TIER_A_NAME)));
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + NO_TIER_NAME))
        .getNumSegmentsToDownload(), 0);
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + NO_TIER_NAME))
        .getNumSegmentsUnchanged(), 0);
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + NO_TIER_NAME))
        .getNumServerParticipants(), 0);
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + TIER_A_NAME))
        .getNumSegmentsToDownload(), numSegments * NUM_REPLICAS);
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + TIER_A_NAME))
        .getNumSegmentsUnchanged(), 0);
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + TIER_A_NAME))
        .getNumServerParticipants(), 6);
    assertNotNull(rebalanceResult.getInstanceAssignment());
    assertNotNull(rebalanceResult.getTierInstanceAssignment());
    assertNotNull(rebalanceResult.getSegmentAssignment());

    // rebalance should change assignment, enable batching
    rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setBatchSizePerServer(2);
    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);

    // check that segments have moved to tier a
    Map<String, Map<String, String>> tierSegmentAssignment = rebalanceResult.getSegmentAssignment();
    for (Map.Entry<String, Map<String, String>> entry : tierSegmentAssignment.entrySet()) {
      Map<String, String> instanceStateMap = entry.getValue();
      for (String instance : instanceStateMap.keySet()) {
        assertTrue(instance.startsWith("replicaAssignment" + TIER_A_NAME + "_" + SERVER_INSTANCE_ID_PREFIX));
      }
    }

    // Test rebalance with tier instance assignment
    InstanceTagPoolConfig tagPoolConfig =
        new InstanceTagPoolConfig(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + TIER_A_NAME), false, 0,
            null);
    InstanceReplicaGroupPartitionConfig replicaGroupPartitionConfig =
        new InstanceReplicaGroupPartitionConfig(true, 0, NUM_REPLICAS, 0, 0, 0, false, null);
    tableConfig.setInstanceAssignmentConfigMap(Collections.singletonMap(TIER_A_NAME,
        new InstanceAssignmentConfig(tagPoolConfig, null, replicaGroupPartitionConfig, null, false)));
    _helixResourceManager.updateTableConfig(tableConfig);

    // Try dry-run summary mode
    rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setDryRun(true);
    rebalanceConfig.setPreChecks(true);
    rebalanceConfig.setReassignInstances(true);
    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.NO_OP);
    assertEquals(
        rebalanceResult.getPreChecksResult().get(DefaultRebalancePreChecker.REPLICA_GROUPS_INFO).getPreCheckStatus(),
        RebalancePreCheckerResult.PreCheckStatus.PASS);
    assertEquals(rebalanceResult.getPreChecksResult().get(DefaultRebalancePreChecker.REPLICA_GROUPS_INFO).getMessage(),
        "OFFLINE segments - Replica Groups are not enabled, replication: " + NUM_REPLICAS + "\n" + TIER_A_NAME
            + " tier - numReplicaGroups: " + NUM_REPLICAS
            + ", numInstancesPerReplicaGroup: 0 (using as many instances as possible)");
    rebalanceSummaryResult = rebalanceResult.getRebalanceSummaryResult();
    assertNotNull(rebalanceSummaryResult);
    assertNotNull(rebalanceSummaryResult.getServerInfo());
    assertNotNull(rebalanceSummaryResult.getSegmentInfo());
    assertEquals(rebalanceSummaryResult.getSegmentInfo().getTotalSegmentsToBeMoved(), 0);
    assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getValueBeforeRebalance(), 6);
    assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getExpectedValueAfterRebalance(), 6);
    assertNotNull(rebalanceSummaryResult.getTagsInfo());
    assertEquals(rebalanceSummaryResult.getTagsInfo().size(), 2);
    tenantInfoMap = rebalanceSummaryResult.getTagsInfo()
        .stream()
        .collect(Collectors.toMap(RebalanceSummaryResult.TagInfo::getTagName, info -> info));
    assertTrue(tenantInfoMap.containsKey(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + NO_TIER_NAME)));
    assertTrue(tenantInfoMap.containsKey(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + TIER_A_NAME)));
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + NO_TIER_NAME))
        .getNumSegmentsToDownload(), 0);
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + NO_TIER_NAME))
        .getNumSegmentsUnchanged(), 0);
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + NO_TIER_NAME))
        .getNumServerParticipants(), 0);
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + TIER_A_NAME))
        .getNumSegmentsToDownload(), 0);
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + TIER_A_NAME))
        .getNumSegmentsUnchanged(), numSegments * NUM_REPLICAS);
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + TIER_A_NAME))
        .getNumServerParticipants(), 6);
    assertNotNull(rebalanceResult.getInstanceAssignment());
    assertNotNull(rebalanceResult.getTierInstanceAssignment());
    assertNotNull(rebalanceResult.getSegmentAssignment());

    rebalanceResult = tableRebalancer.rebalance(tableConfig, new RebalanceConfig(), null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.NO_OP);
    assertTrue(rebalanceResult.getTierInstanceAssignment().containsKey(TIER_A_NAME));

    InstancePartitions instancePartitions = rebalanceResult.getTierInstanceAssignment().get(TIER_A_NAME);

    // Math.abs("testTable_OFFLINE".hashCode()) % 6 = 2
    // [i2, i3, i4, i5, i0, i1]
    //  r0  r1  r2  r0  r1  r2
    assertEquals(instancePartitions.getInstances(0, 0),
        Arrays.asList("replicaAssignment" + TIER_A_NAME + "_" + SERVER_INSTANCE_ID_PREFIX + 2,
            "replicaAssignment" + TIER_A_NAME + "_" + SERVER_INSTANCE_ID_PREFIX + 5));
    assertEquals(instancePartitions.getInstances(0, 1),
        Arrays.asList("replicaAssignment" + TIER_A_NAME + "_" + SERVER_INSTANCE_ID_PREFIX + 3,
            "replicaAssignment" + TIER_A_NAME + "_" + SERVER_INSTANCE_ID_PREFIX + 0));
    assertEquals(instancePartitions.getInstances(0, 2),
        Arrays.asList("replicaAssignment" + TIER_A_NAME + "_" + SERVER_INSTANCE_ID_PREFIX + 4,
            "replicaAssignment" + TIER_A_NAME + "_" + SERVER_INSTANCE_ID_PREFIX + 1));

    // The assignment are based on replica-group 0 and mirrored to all the replica-groups, so server of index 0, 1, 5
    // should have the same segments assigned, and server of index 2, 3, 4 should have the same segments assigned, each
    // with 5 segments
    Map<String, Map<String, String>> newSegmentAssignment = rebalanceResult.getSegmentAssignment();
    int numSegmentsOnServer0 = 0;
    for (int i = 0; i < numSegments; i++) {
      String segmentName = SEGMENT_NAME_PREFIX + i;
      Map<String, String> instanceStateMap = newSegmentAssignment.get(segmentName);
      assertEquals(instanceStateMap.size(), NUM_REPLICAS);
      if (instanceStateMap.containsKey("replicaAssignment" + TIER_A_NAME + "_" + SERVER_INSTANCE_ID_PREFIX + 0)) {
        numSegmentsOnServer0++;
        assertEquals(instanceStateMap.get("replicaAssignment" + TIER_A_NAME + "_" + SERVER_INSTANCE_ID_PREFIX + 0),
            ONLINE);
        assertEquals(instanceStateMap.get("replicaAssignment" + TIER_A_NAME + "_" + SERVER_INSTANCE_ID_PREFIX + 1),
            ONLINE);
        assertEquals(instanceStateMap.get("replicaAssignment" + TIER_A_NAME + "_" + SERVER_INSTANCE_ID_PREFIX + 5),
            ONLINE);
      } else {
        assertEquals(instanceStateMap.get("replicaAssignment" + TIER_A_NAME + "_" + SERVER_INSTANCE_ID_PREFIX + 2),
            ONLINE);
        assertEquals(instanceStateMap.get("replicaAssignment" + TIER_A_NAME + "_" + SERVER_INSTANCE_ID_PREFIX + 3),
            ONLINE);
        assertEquals(instanceStateMap.get("replicaAssignment" + TIER_A_NAME + "_" + SERVER_INSTANCE_ID_PREFIX + 4),
            ONLINE);
      }
    }
    assertEquals(numSegmentsOnServer0, numSegments / 2);

    _helixResourceManager.deleteOfflineServerTenantFor("replicaAssignment" + TIER_A_NAME);
    rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setDryRun(true);
    rebalanceConfig.setReassignInstances(false);

    // if rebalance with reassignInstances=false, servers assigned would not have relevant tags
    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.NO_OP);
    rebalanceSummaryResult = rebalanceResult.getRebalanceSummaryResult();
    assertNotNull(rebalanceSummaryResult);
    assertNotNull(rebalanceSummaryResult.getServerInfo());
    assertNotNull(rebalanceSummaryResult.getSegmentInfo());
    assertEquals(rebalanceSummaryResult.getSegmentInfo().getTotalSegmentsToBeMoved(), 0);
    assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getValueBeforeRebalance(), 6);
    assertEquals(rebalanceSummaryResult.getServerInfo().getNumServers().getExpectedValueAfterRebalance(), 6);
    assertNotNull(rebalanceSummaryResult.getTagsInfo());
    assertEquals(rebalanceSummaryResult.getTagsInfo().size(), 3);
    tenantInfoMap = rebalanceSummaryResult.getTagsInfo()
        .stream()
        .collect(Collectors.toMap(RebalanceSummaryResult.TagInfo::getTagName, info -> info));
    assertTrue(tenantInfoMap.containsKey(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + NO_TIER_NAME)));
    assertTrue(tenantInfoMap.containsKey(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + TIER_A_NAME)));
    assertTrue(tenantInfoMap.containsKey(RebalanceSummaryResult.TagInfo.TAG_FOR_OUTDATED_SERVERS));
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + NO_TIER_NAME))
        .getNumSegmentsToDownload(), 0);
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + NO_TIER_NAME))
        .getNumSegmentsUnchanged(), 0);
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + NO_TIER_NAME))
        .getNumServerParticipants(), 0);
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + TIER_A_NAME))
        .getNumSegmentsToDownload(), 0);
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + TIER_A_NAME))
        .getNumSegmentsUnchanged(), 0);
    assertEquals(tenantInfoMap.get(TagNameUtils.getOfflineTagForTenant("replicaAssignment" + TIER_A_NAME))
        .getNumServerParticipants(), 0);
    assertEquals(tenantInfoMap.get(RebalanceSummaryResult.TagInfo.TAG_FOR_OUTDATED_SERVERS).getNumSegmentsToDownload(),
        0);
    assertEquals(tenantInfoMap.get(RebalanceSummaryResult.TagInfo.TAG_FOR_OUTDATED_SERVERS).getNumSegmentsUnchanged(),
        numSegments * NUM_REPLICAS);
    assertEquals(tenantInfoMap.get(RebalanceSummaryResult.TagInfo.TAG_FOR_OUTDATED_SERVERS).getNumServerParticipants(),
        6);

    _helixResourceManager.deleteOfflineTable(TIERED_TABLE_NAME);
    executorService.shutdown();
  }

  @Test
  public void testRebalanceWithMinimizeDataMovementBalanced()
      throws Exception {
    int numServers = 3;
    for (int i = 0; i < numServers; i++) {
      addFakeServerInstanceToAutoJoinHelixCluster("minimizeDataMovement_balance_" + SERVER_INSTANCE_ID_PREFIX + i,
          true);
    }

    // Create the table with default balanced segment assignment
    TableConfig tableConfig =
        new TableConfigBuilder(TableType.OFFLINE).setTableName(RAW_TABLE_NAME).setNumReplicas(NUM_REPLICAS).build();

    addDummySchema(RAW_TABLE_NAME);
    _helixResourceManager.addTable(tableConfig);

    // Add the segments
    int numSegments = 10;
    long nowInDays = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis());

    for (int i = 0; i < numSegments; i++) {
      _helixResourceManager.addNewSegment(OFFLINE_TABLE_NAME,
          SegmentMetadataMockUtils.mockSegmentMetadataWithEndTimeInfo(RAW_TABLE_NAME, SEGMENT_NAME_PREFIX + i,
              nowInDays), null);
    }

    Map<String, Map<String, String>> oldSegmentAssignment =
        _helixResourceManager.getTableIdealState(OFFLINE_TABLE_NAME).getRecord().getMapFields();

    TableRebalancer tableRebalancer = new TableRebalancer(_helixManager);

    // Try dry-run summary mode
    RebalanceConfig rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setDryRun(true);
    rebalanceConfig.setReassignInstances(true);
    rebalanceConfig.setMinimizeDataMovement(Enablement.ENABLE);
    RebalanceResult rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);

    RebalanceSummaryResult rebalanceSummaryResult = rebalanceResult.getRebalanceSummaryResult();
    assertNotNull(rebalanceSummaryResult);
    assertNotNull(rebalanceSummaryResult.getServerInfo());
    RebalanceSummaryResult.ServerInfo rebalanceServerInfo = rebalanceSummaryResult.getServerInfo();
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.NO_OP);
    assertEquals(rebalanceServerInfo.getNumServers().getExpectedValueAfterRebalance(), numServers);

    rebalanceResult = tableRebalancer.rebalance(tableConfig, new RebalanceConfig(), null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.NO_OP);
    // Segment assignment should not change
    assertEquals(rebalanceResult.getSegmentAssignment(), oldSegmentAssignment);

    // add one server instance
    addFakeServerInstanceToAutoJoinHelixCluster("minimizeDataMovement_balance_" + SERVER_INSTANCE_ID_PREFIX, true);

    // Table without instance assignment config should work fine (ignore) with the minimizeDataMovement flag set
    // Try dry-run summary mode
    rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setDryRun(true);
    rebalanceConfig.setReassignInstances(true);
    rebalanceConfig.setMinimizeDataMovement(Enablement.ENABLE);
    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);

    rebalanceSummaryResult = rebalanceResult.getRebalanceSummaryResult();
    assertNotNull(rebalanceSummaryResult);
    assertNotNull(rebalanceSummaryResult.getServerInfo());
    rebalanceServerInfo = rebalanceSummaryResult.getServerInfo();
    // Should see the added server
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);
    assertEquals(rebalanceServerInfo.getNumServers().getValueBeforeRebalance(), numServers);
    assertEquals(rebalanceServerInfo.getNumServers().getExpectedValueAfterRebalance(), numServers + 1);

    // Check if the instance assignment is the same as the one without minimizeDataMovement flag set
    rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setDryRun(true);
    rebalanceConfig.setReassignInstances(true);
    rebalanceConfig.setMinimizeDataMovement(Enablement.DEFAULT);
    RebalanceResult rebalanceResultWithoutMinimized = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);

    assertEquals(rebalanceResult.getInstanceAssignment(), rebalanceResultWithoutMinimized.getInstanceAssignment());

    // Rebalance
    rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setReassignInstances(true);
    rebalanceConfig.setMinimizeDataMovement(Enablement.ENABLE);
    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    // Should see the added server in the instance assignment
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);
    assertEquals(rebalanceResult.getInstanceAssignment().get(InstancePartitionsType.OFFLINE).getInstances(0, 0).size(),
        numServers + 1);

    _helixResourceManager.deleteOfflineTable(RAW_TABLE_NAME);
    for (int i = 0; i < numServers; i++) {
      stopAndDropFakeInstance("minimizeDataMovement_balance_" + SERVER_INSTANCE_ID_PREFIX + i);
    }
  }

  @Test
  public void testRebalanceWithMinimizeDataMovementInstanceAssignments()
      throws Exception {
    int numServers = 6;
    for (int i = 0; i < numServers; i++) {
      addFakeServerInstanceToAutoJoinHelixCluster("minimizeDataMovement_" + SERVER_INSTANCE_ID_PREFIX + i, true);
    }

    // One instance per replica group, no partition
    InstanceAssignmentConfig instanceAssignmentConfig = new InstanceAssignmentConfig(
        new InstanceTagPoolConfig(TagNameUtils.getOfflineTagForTenant(null), false, 0, null), null,
        new InstanceReplicaGroupPartitionConfig(true, 0, NUM_REPLICAS, 1, 0, 0, false, null), null, false);

    // Create the table
    TableConfig tableConfig = new TableConfigBuilder(TableType.OFFLINE).setTableName(RAW_TABLE_NAME)
        .setNumReplicas(NUM_REPLICAS)
        .setInstanceAssignmentConfigMap(Map.of("OFFLINE", instanceAssignmentConfig))
        .build();

    addDummySchema(RAW_TABLE_NAME);
    _helixResourceManager.addTable(tableConfig);

    // Add the segments
    int numSegments = 10;
    long nowInDays = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis());

    for (int i = 0; i < numSegments; i++) {
      _helixResourceManager.addNewSegment(OFFLINE_TABLE_NAME,
          SegmentMetadataMockUtils.mockSegmentMetadataWithEndTimeInfo(RAW_TABLE_NAME, SEGMENT_NAME_PREFIX + i,
              nowInDays), null);
    }

    Map<String, Map<String, String>> oldSegmentAssignment =
        _helixResourceManager.getTableIdealState(OFFLINE_TABLE_NAME).getRecord().getMapFields();

    TableRebalancer tableRebalancer = new TableRebalancer(_helixManager);

    // Try dry-run summary mode
    RebalanceConfig rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setDryRun(true);
    rebalanceConfig.setReassignInstances(true);
    rebalanceConfig.setMinimizeDataMovement(Enablement.ENABLE);
    RebalanceResult rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);

    RebalanceSummaryResult rebalanceSummaryResult = rebalanceResult.getRebalanceSummaryResult();
    assertNotNull(rebalanceSummaryResult);
    assertNotNull(rebalanceSummaryResult.getServerInfo());
    RebalanceSummaryResult.ServerInfo rebalanceServerInfo = rebalanceSummaryResult.getServerInfo();
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.NO_OP);
    assertEquals(rebalanceServerInfo.getNumServers().getExpectedValueAfterRebalance(), NUM_REPLICAS);

    rebalanceResult = tableRebalancer.rebalance(tableConfig, new RebalanceConfig(), null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.NO_OP);
    // Segment assignment should not change
    assertEquals(rebalanceResult.getSegmentAssignment(), oldSegmentAssignment);

    // add one server instance
    addFakeServerInstanceToAutoJoinHelixCluster("minimizeDataMovement_" + SERVER_INSTANCE_ID_PREFIX + numServers, true);

    // increase replica group size by 1
    instanceAssignmentConfig = new InstanceAssignmentConfig(
        new InstanceTagPoolConfig(TagNameUtils.getOfflineTagForTenant(null), false, 0, null), null,
        new InstanceReplicaGroupPartitionConfig(true, 0, NUM_REPLICAS + 1, 1, 0, 0, false, null), null, false);

    tableConfig.setInstanceAssignmentConfigMap(Map.of("OFFLINE", instanceAssignmentConfig));

    // Try dry-run summary mode

    // without minimize data movement, it's supposed to add more than one server
    rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setDryRun(true);
    rebalanceConfig.setReassignInstances(true);
    rebalanceConfig.setMinimizeDataMovement(Enablement.DISABLE);
    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);
    rebalanceServerInfo = rebalanceResult.getRebalanceSummaryResult().getServerInfo();

    // note: this assertion may fail due to instance assignment algorithm changed in the future.
    // right now, rebalance without minimizing data movement adds more than one server and remove some servers in the
    // testing setup like this.
    assertTrue(rebalanceServerInfo.getServersAdded().size() > 1);
    assertEquals(rebalanceServerInfo.getServersAdded().size() - rebalanceServerInfo.getServersRemoved().size(), 1);

    // use default table config's minimizeDataMovement flag, should be equivalent to without minimize data movement
    rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setDryRun(true);
    rebalanceConfig.setReassignInstances(true);
    rebalanceConfig.setMinimizeDataMovement(Enablement.DEFAULT);
    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);
    rebalanceServerInfo = rebalanceResult.getRebalanceSummaryResult().getServerInfo();

    assertTrue(rebalanceServerInfo.getServersAdded().size() > 1);
    assertEquals(rebalanceServerInfo.getServersAdded().size() - rebalanceServerInfo.getServersRemoved().size(), 1);

    // with minimize data movement, we should add 1 server only
    rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setDryRun(true);
    rebalanceConfig.setReassignInstances(true);
    rebalanceConfig.setMinimizeDataMovement(Enablement.ENABLE);
    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);
    rebalanceServerInfo = rebalanceResult.getRebalanceSummaryResult().getServerInfo();

    assertEquals(rebalanceServerInfo.getServersAdded().size(), 1);
    assertEquals(rebalanceServerInfo.getServersRemoved().size(), 0);

    // rebalance without dry-run
    rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setReassignInstances(true);
    rebalanceConfig.setMinimizeDataMovement(Enablement.ENABLE);
    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);
    assertEquals(rebalanceResult.getInstanceAssignment().get(InstancePartitionsType.OFFLINE).getNumReplicaGroups(),
        NUM_REPLICAS + 1);

    // add one server instance
    addFakeServerInstanceToAutoJoinHelixCluster("minimizeDataMovement_" + SERVER_INSTANCE_ID_PREFIX + (numServers + 1),
        true);

    // decrease replica group size by 1
    instanceAssignmentConfig = new InstanceAssignmentConfig(
        new InstanceTagPoolConfig(TagNameUtils.getOfflineTagForTenant(null), false, 0, null), null,
        new InstanceReplicaGroupPartitionConfig(true, 0, NUM_REPLICAS, 1, 0, 0, false, null), null, false);

    tableConfig.setInstanceAssignmentConfigMap(Map.of("OFFLINE", instanceAssignmentConfig));
    _helixResourceManager.updateTableConfig(tableConfig);

    // Try dry-run summary mode
    rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setDryRun(true);
    rebalanceConfig.setReassignInstances(true);
    rebalanceConfig.setMinimizeDataMovement(Enablement.ENABLE);
    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);
    rebalanceServerInfo = rebalanceResult.getRebalanceSummaryResult().getServerInfo();

    // with minimize data movement, we should remove 1 server only
    assertEquals(rebalanceServerInfo.getServersAdded().size(), 0);
    assertEquals(rebalanceServerInfo.getServersRemoved().size(), 1);

    rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setReassignInstances(true);
    rebalanceConfig.setMinimizeDataMovement(Enablement.ENABLE);
    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    assertEquals(rebalanceResult.getStatus(), RebalanceResult.Status.DONE);
    assertEquals(rebalanceResult.getInstanceAssignment().get(InstancePartitionsType.OFFLINE).getNumReplicaGroups(),
        NUM_REPLICAS);

    _helixResourceManager.deleteOfflineTable(RAW_TABLE_NAME);
    for (int i = 0; i < numServers; i++) {
      stopAndDropFakeInstance("minimizeDataMovement_" + SERVER_INSTANCE_ID_PREFIX + i);
    }
  }

  @Test
  public void testRebalanceConsumingSegmentSummary()
      throws Exception {
    int numServers = 3;
    int numReplica = 3;

    for (int i = 0; i < numServers; i++) {
      String instanceId = "consumingSegmentSummary_" + SERVER_INSTANCE_ID_PREFIX + i;
      addFakeServerInstanceToAutoJoinHelixCluster(instanceId, true);
    }

    ConsumingSegmentInfoReader mockConsumingSegmentInfoReader = Mockito.mock(ConsumingSegmentInfoReader.class);
    TableRebalancer tableRebalancerOriginal =
        new TableRebalancer(_helixManager, null, null, null, _tableSizeReader, null);
    TableConfig tableConfig =
        new TableConfigBuilder(TableType.REALTIME).setTableName(RAW_TABLE_NAME)
            .setNumReplicas(numReplica)
            .setStreamConfigs(FakeStreamConfigUtils.getDefaultLowLevelStreamConfigs().getStreamConfigsMap())
            .build();

    // Create the table
    addDummySchema(RAW_TABLE_NAME);
    _helixResourceManager.addTable(tableConfig);

    // Generate mock ConsumingSegmentsInfoMap for the consuming segments
    int mockOffsetSmall = 1000;
    int mockOffsetBig = 2000;

    TableRebalancer tableRebalancer = Mockito.spy(tableRebalancerOriginal);
    Mockito.doReturn(new LongMsgOffset(mockOffsetBig))
        .when(tableRebalancer)
        .fetchStreamPartitionOffset(Mockito.any(), Mockito.eq(0));
    Mockito.doReturn(new LongMsgOffset(mockOffsetSmall))
        .when(tableRebalancer)
        .fetchStreamPartitionOffset(Mockito.any(), Mockito.intThat(x -> x != 0));

    RebalanceConfig rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setDryRun(true);

    // dry-run with default rebalance config
    RebalanceResult rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    RebalanceSummaryResult summaryResult = rebalanceResult.getRebalanceSummaryResult();
    assertNotNull(summaryResult.getSegmentInfo());
    RebalanceSummaryResult.ConsumingSegmentToBeMovedSummary consumingSegmentToBeMovedSummary =
        summaryResult.getSegmentInfo().getConsumingSegmentToBeMovedSummary();
    assertNotNull(consumingSegmentToBeMovedSummary);
    assertEquals(consumingSegmentToBeMovedSummary.getNumConsumingSegmentsToBeMoved(), 0);
    assertEquals(consumingSegmentToBeMovedSummary.getNumServersGettingConsumingSegmentsAdded(), 0);
    assertEquals(consumingSegmentToBeMovedSummary.getConsumingSegmentsToBeMovedWithMostOffsetsToCatchUp().size(), 0);
    assertEquals(consumingSegmentToBeMovedSummary.getConsumingSegmentsToBeMovedWithOldestAgeInMinutes().size(), 0);
    assertEquals(consumingSegmentToBeMovedSummary.getServerConsumingSegmentSummary().size(), 0);
    assertTrue(consumingSegmentToBeMovedSummary.getServerConsumingSegmentSummary()
        .values()
        .stream()
        .allMatch(x -> x.getTotalOffsetsToCatchUpAcrossAllConsumingSegments() == 0));

    rebalanceConfig.setIncludeConsuming(true);
    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    summaryResult = rebalanceResult.getRebalanceSummaryResult();
    assertNotNull(summaryResult.getSegmentInfo());
    consumingSegmentToBeMovedSummary = summaryResult.getSegmentInfo().getConsumingSegmentToBeMovedSummary();
    assertNotNull(consumingSegmentToBeMovedSummary);
    assertEquals(consumingSegmentToBeMovedSummary.getNumConsumingSegmentsToBeMoved(), 0);
    assertEquals(consumingSegmentToBeMovedSummary.getNumServersGettingConsumingSegmentsAdded(), 0);
    assertEquals(consumingSegmentToBeMovedSummary.getConsumingSegmentsToBeMovedWithMostOffsetsToCatchUp().size(), 0);
    assertEquals(consumingSegmentToBeMovedSummary.getConsumingSegmentsToBeMovedWithOldestAgeInMinutes().size(), 0);
    assertEquals(consumingSegmentToBeMovedSummary.getServerConsumingSegmentSummary().size(), 0);
    assertTrue(consumingSegmentToBeMovedSummary.getServerConsumingSegmentSummary()
        .values()
        .stream()
        .allMatch(x -> x.getTotalOffsetsToCatchUpAcrossAllConsumingSegments() == 0));

    // Create new servers to replace the old servers
    for (int i = numServers; i < numServers * 2; i++) {
      String instanceId = "consumingSegmentSummary_" + SERVER_INSTANCE_ID_PREFIX + i;
      addFakeServerInstanceToAutoJoinHelixCluster(instanceId, true);
    }
    for (int i = 0; i < numServers; i++) {
      _helixAdmin.removeInstanceTag(getHelixClusterName(), "consumingSegmentSummary_" + SERVER_INSTANCE_ID_PREFIX + i,
          TagNameUtils.getRealtimeTagForTenant(null));
    }

    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    summaryResult = rebalanceResult.getRebalanceSummaryResult();
    assertNotNull(summaryResult.getSegmentInfo());
    consumingSegmentToBeMovedSummary = summaryResult.getSegmentInfo().getConsumingSegmentToBeMovedSummary();
    assertNotNull(consumingSegmentToBeMovedSummary);
    assertEquals(consumingSegmentToBeMovedSummary.getNumConsumingSegmentsToBeMoved(),
        FakeStreamConfigUtils.DEFAULT_NUM_PARTITIONS * numReplica);
    assertEquals(consumingSegmentToBeMovedSummary.getNumServersGettingConsumingSegmentsAdded(), numServers);
    Iterator<Integer> offsetToCatchUpIterator =
        consumingSegmentToBeMovedSummary.getConsumingSegmentsToBeMovedWithMostOffsetsToCatchUp().values().iterator();
    assertEquals(offsetToCatchUpIterator.next(), mockOffsetBig);
    if (FakeStreamConfigUtils.DEFAULT_NUM_PARTITIONS > 1) {
      assertEquals(offsetToCatchUpIterator.next(), mockOffsetSmall);
    }
    assertEquals(consumingSegmentToBeMovedSummary.getConsumingSegmentsToBeMovedWithOldestAgeInMinutes().size(),
        FakeStreamConfigUtils.DEFAULT_NUM_PARTITIONS);
    assertEquals(consumingSegmentToBeMovedSummary.getServerConsumingSegmentSummary().size(), numServers);
    assertTrue(consumingSegmentToBeMovedSummary.getServerConsumingSegmentSummary()
        .values()
        .stream()
        .allMatch(x -> x.getTotalOffsetsToCatchUpAcrossAllConsumingSegments()
            == mockOffsetSmall * (FakeStreamConfigUtils.DEFAULT_NUM_PARTITIONS - 1) + mockOffsetBig));

    _helixResourceManager.deleteRealtimeTable(RAW_TABLE_NAME);

    for (int i = 0; i < numServers * 2; i++) {
      stopAndDropFakeInstance("consumingSegmentSummary_" + SERVER_INSTANCE_ID_PREFIX + i);
    }
  }

  @Test
  public void testRebalanceConsumingSegmentSummaryFailure()
      throws Exception {
    int numServers = 3;
    int numReplica = 3;

    for (int i = 0; i < numServers; i++) {
      String instanceId = "consumingSegmentSummaryFailure_" + SERVER_INSTANCE_ID_PREFIX + i;
      addFakeServerInstanceToAutoJoinHelixCluster(instanceId, true);
    }

    TableRebalancer tableRebalancerOriginal =
        new TableRebalancer(_helixManager, null, null, null, _tableSizeReader, null);
    TableConfig tableConfig =
        new TableConfigBuilder(TableType.REALTIME).setTableName(RAW_TABLE_NAME)
            .setNumReplicas(numReplica)
            .setStreamConfigs(FakeStreamConfigUtils.getDefaultLowLevelStreamConfigs().getStreamConfigsMap())
            .build();

    // Create the table
    addDummySchema(RAW_TABLE_NAME);
    _helixResourceManager.addTable(tableConfig);

    // Generate mock ConsumingSegmentsInfoMap for the consuming segments
    int mockOffsetSmall = 1000;
    int mockOffsetBig = 2000;

    TableRebalancer tableRebalancer = Mockito.spy(tableRebalancerOriginal);
    Mockito.doReturn(new LongMsgOffset(mockOffsetBig))
        .when(tableRebalancer)
        .fetchStreamPartitionOffset(Mockito.any(), Mockito.eq(0));
    Mockito.doReturn(new LongMsgOffset(mockOffsetSmall))
        .when(tableRebalancer)
        .fetchStreamPartitionOffset(Mockito.any(), Mockito.intThat(x -> x != 0));

    RebalanceConfig rebalanceConfig = new RebalanceConfig();
    rebalanceConfig.setDryRun(true);
    rebalanceConfig.setIncludeConsuming(true);

    // Create new servers to replace the old servers
    for (int i = numServers; i < numServers * 2; i++) {
      String instanceId = "consumingSegmentSummaryFailure_" + SERVER_INSTANCE_ID_PREFIX + i;
      addFakeServerInstanceToAutoJoinHelixCluster(instanceId, true);
    }
    for (int i = 0; i < numServers; i++) {
      _helixAdmin.removeInstanceTag(getHelixClusterName(),
          "consumingSegmentSummaryFailure_" + SERVER_INSTANCE_ID_PREFIX + i,
          TagNameUtils.getRealtimeTagForTenant(null));
    }

    RebalanceResult rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    RebalanceSummaryResult summaryResult = rebalanceResult.getRebalanceSummaryResult();
    assertNotNull(summaryResult.getSegmentInfo());
    RebalanceSummaryResult.ConsumingSegmentToBeMovedSummary consumingSegmentToBeMovedSummary =
        summaryResult.getSegmentInfo().getConsumingSegmentToBeMovedSummary();
    assertNotNull(consumingSegmentToBeMovedSummary);
    assertEquals(consumingSegmentToBeMovedSummary.getNumServersGettingConsumingSegmentsAdded(), numServers);
    assertEquals(consumingSegmentToBeMovedSummary.getNumConsumingSegmentsToBeMoved(),
        FakeStreamConfigUtils.DEFAULT_NUM_PARTITIONS * numReplica);
    assertNotNull(consumingSegmentToBeMovedSummary.getConsumingSegmentsToBeMovedWithOldestAgeInMinutes());
    assertNotNull(consumingSegmentToBeMovedSummary.getConsumingSegmentsToBeMovedWithMostOffsetsToCatchUp());
    assertNotNull(consumingSegmentToBeMovedSummary.getServerConsumingSegmentSummary());

    // Simulate not supported stream partition message type (e.g. Kinesis)
    Mockito.doReturn((StreamPartitionMsgOffset) o -> 0)
        .when(tableRebalancer)
        .fetchStreamPartitionOffset(Mockito.any(), Mockito.eq(0));
    Mockito.doReturn(new LongMsgOffset(mockOffsetSmall))
        .when(tableRebalancer)
        .fetchStreamPartitionOffset(Mockito.any(), Mockito.intThat(x -> x != 0));

    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    summaryResult = rebalanceResult.getRebalanceSummaryResult();
    assertNotNull(summaryResult.getSegmentInfo());
    consumingSegmentToBeMovedSummary = summaryResult.getSegmentInfo().getConsumingSegmentToBeMovedSummary();
    assertNotNull(consumingSegmentToBeMovedSummary);
    assertEquals(consumingSegmentToBeMovedSummary.getNumServersGettingConsumingSegmentsAdded(), numServers);
    assertEquals(consumingSegmentToBeMovedSummary.getNumConsumingSegmentsToBeMoved(),
        FakeStreamConfigUtils.DEFAULT_NUM_PARTITIONS * numReplica);
    assertNotNull(consumingSegmentToBeMovedSummary.getConsumingSegmentsToBeMovedWithOldestAgeInMinutes());
    assertNull(consumingSegmentToBeMovedSummary.getConsumingSegmentsToBeMovedWithMostOffsetsToCatchUp());
    assertNotNull(consumingSegmentToBeMovedSummary.getServerConsumingSegmentSummary());
    assertTrue(consumingSegmentToBeMovedSummary.getServerConsumingSegmentSummary()
        .values()
        .stream()
        .allMatch(x -> x.getTotalOffsetsToCatchUpAcrossAllConsumingSegments() == -1));

    // Simulate stream partition offset fetch failure
    Mockito.doThrow(new TimeoutException("timeout"))
        .when(tableRebalancer)
        .fetchStreamPartitionOffset(Mockito.any(), Mockito.eq(0));
    Mockito.doReturn(new LongMsgOffset(mockOffsetSmall))
        .when(tableRebalancer)
        .fetchStreamPartitionOffset(Mockito.any(), Mockito.intThat(x -> x != 0));

    rebalanceResult = tableRebalancer.rebalance(tableConfig, rebalanceConfig, null);
    summaryResult = rebalanceResult.getRebalanceSummaryResult();
    assertNotNull(summaryResult.getSegmentInfo());
    consumingSegmentToBeMovedSummary = summaryResult.getSegmentInfo().getConsumingSegmentToBeMovedSummary();
    assertNotNull(consumingSegmentToBeMovedSummary);
    assertEquals(consumingSegmentToBeMovedSummary.getNumServersGettingConsumingSegmentsAdded(), numServers);
    assertEquals(consumingSegmentToBeMovedSummary.getNumConsumingSegmentsToBeMoved(),
        FakeStreamConfigUtils.DEFAULT_NUM_PARTITIONS * numReplica);
    assertNotNull(consumingSegmentToBeMovedSummary.getConsumingSegmentsToBeMovedWithOldestAgeInMinutes());
    assertNull(consumingSegmentToBeMovedSummary.getConsumingSegmentsToBeMovedWithMostOffsetsToCatchUp());
    assertNotNull(consumingSegmentToBeMovedSummary.getServerConsumingSegmentSummary());
    assertTrue(consumingSegmentToBeMovedSummary.getServerConsumingSegmentSummary()
        .values()
        .stream()
        .allMatch(x -> x.getTotalOffsetsToCatchUpAcrossAllConsumingSegments() == -1));

    _helixResourceManager.deleteRealtimeTable(RAW_TABLE_NAME);

    for (int i = 0; i < numServers * 2; i++) {
      stopAndDropFakeInstance("consumingSegmentSummaryFailure_" + SERVER_INSTANCE_ID_PREFIX + i);
    }
  }

  @Test
  public void testGetMovingConsumingSegments() {
    // Setup: segment assignments with consuming segments moving
    Map<String, Map<String, String>> currentAssignment = new HashMap<>();
    Map<String, Map<String, String>> targetAssignment = new HashMap<>();

    // Segment 1: CONSUMING, same instances, should not be considered moving
    Map<String, String> cur1 = new HashMap<>();
    cur1.put("server1", "CONSUMING");
    cur1.put("server2", "CONSUMING");
    currentAssignment.put("segment1", cur1);
    Map<String, String> tgt1 = new HashMap<>();
    tgt1.put("server1", "CONSUMING");
    tgt1.put("server2", "CONSUMING");
    targetAssignment.put("segment1", tgt1);

    // Segment 2: CONSUMING, different instances, should be considered moving
    Map<String, String> cur2 = new HashMap<>();
    cur2.put("server1", "CONSUMING");
    cur2.put("server2", "CONSUMING");
    currentAssignment.put("segment2", cur2);
    Map<String, String> tgt2 = new HashMap<>();
    tgt2.put("server3", "CONSUMING");
    tgt2.put("server4", "CONSUMING");
    targetAssignment.put("segment2", tgt2);

    // Segment 3: ONLINE, should not be considered
    Map<String, String> cur3 = new HashMap<>();
    cur3.put("server1", "ONLINE");
    currentAssignment.put("segment3", cur3);
    Map<String, String> tgt3 = new HashMap<>();
    tgt3.put("server2", "ONLINE");
    targetAssignment.put("segment3", tgt3);

    // Segment 4: one instance is ONLINE, should not be considered
    Map<String, String> cur4 = new HashMap<>();
    cur4.put("server1", "ONLINE");
    cur4.put("server2", "CONSUMING");
    currentAssignment.put("segment4", cur4);
    Map<String, String> tgt4 = new HashMap<>();
    tgt4.put("server1", "ONLINE");
    tgt4.put("server2", "CONSUMING");
    targetAssignment.put("segment4", tgt4);

    // Segment 5: no ONLINE instance, but at least one in CONSUMING, should be considered moving
    Map<String, String> cur5 = new HashMap<>();
    cur5.put("server1", "OFFLINE");
    cur5.put("server2", "CONSUMING");
    currentAssignment.put("segment5", cur5);
    Map<String, String> tgt5 = new HashMap<>();
    tgt5.put("server1", "OFFLINE");
    tgt5.put("server3", "CONSUMING");
    targetAssignment.put("segment5", tgt5);

    Set<String> moving = TableRebalancer.getMovingConsumingSegments(currentAssignment, targetAssignment);
    assertEquals(moving.size(), 2);
    assertTrue(moving.contains("segment2"));
    assertTrue(moving.contains("segment5"));
    assertFalse(moving.contains("segment1"));
    assertFalse(moving.contains("segment3"));
    assertFalse(moving.contains("segment4"));
  }

  @AfterClass
  public void tearDown() {
    stopFakeInstances();
    stopController();
    stopZk();
  }
}
