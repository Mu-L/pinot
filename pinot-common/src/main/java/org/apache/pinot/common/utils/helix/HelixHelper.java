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
package org.apache.pinot.common.utils.helix;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.helix.HelixAdmin;
import org.apache.helix.HelixDataAccessor;
import org.apache.helix.HelixManager;
import org.apache.helix.PropertyKey.Builder;
import org.apache.helix.model.ExternalView;
import org.apache.helix.model.HelixConfigScope;
import org.apache.helix.model.HelixConfigScope.ConfigScopeProperty;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.InstanceConfig;
import org.apache.helix.model.builder.HelixConfigScopeBuilder;
import org.apache.helix.zookeeper.datamodel.ZNRecord;
import org.apache.helix.zookeeper.datamodel.serializer.ZNRecordSerializer;
import org.apache.pinot.common.helix.ExtraInstanceConfig;
import org.apache.pinot.common.metadata.ZKMetadataProvider;
import org.apache.pinot.common.utils.config.TagNameUtils;
import org.apache.pinot.common.version.PinotVersion;
import org.apache.pinot.spi.config.table.TableConfig;
import org.apache.pinot.spi.config.table.TableType;
import org.apache.pinot.spi.utils.CommonConstants;
import org.apache.pinot.spi.utils.CommonConstants.Helix.StateModel.BrokerResourceStateModel;
import org.apache.pinot.spi.utils.InstanceTypeUtils;
import org.apache.pinot.spi.utils.retry.RetryPolicies;
import org.apache.pinot.spi.utils.retry.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class HelixHelper {
  private HelixHelper() {
  }

  private static final RetryPolicy DEFAULT_RETRY_POLICY = RetryPolicies.exponentialBackoffRetryPolicy(5, 1000L, 2.0f);
  private static final RetryPolicy DEFAULT_TABLE_IDEALSTATES_UPDATE_RETRY_POLICY =
      RetryPolicies.randomDelayRetryPolicy(20, 100L, 200L);

  private static final Logger LOGGER = LoggerFactory.getLogger(HelixHelper.class);
  private static final ZNRecordSerializer ZN_RECORD_SERIALIZER = new ZNRecordSerializer();
  private static final IdealStateGroupCommit IDEAL_STATE_GROUP_COMMIT = new IdealStateGroupCommit();

  private static final String ONLINE = "ONLINE";
  private static final String OFFLINE = "OFFLINE";

  public static final String BROKER_RESOURCE = CommonConstants.Helix.BROKER_RESOURCE_INSTANCE;

  public static IdealState cloneIdealState(IdealState idealState) {
    return new IdealState(
        (ZNRecord) ZN_RECORD_SERIALIZER.deserialize(ZN_RECORD_SERIALIZER.serialize(idealState.getRecord())));
  }

  public static IdealState updateIdealState(HelixManager helixManager, String resourceName,
      Function<IdealState, IdealState> updater) {
    return IDEAL_STATE_GROUP_COMMIT.commit(helixManager, resourceName, updater,
        DEFAULT_TABLE_IDEALSTATES_UPDATE_RETRY_POLICY, false);
  }

  public static IdealState updateIdealState(HelixManager helixManager, String resourceName,
      Function<IdealState, IdealState> updater, RetryPolicy retryPolicy) {
    return IDEAL_STATE_GROUP_COMMIT.commit(helixManager, resourceName, updater, retryPolicy, false);
  }

  public static IdealState updateIdealState(HelixManager helixManager, String resourceName,
      Function<IdealState, IdealState> updater, RetryPolicy retryPolicy, boolean noChangeOk) {
    return IDEAL_STATE_GROUP_COMMIT.commit(helixManager, resourceName, updater, retryPolicy, noChangeOk);
  }

  /**
   * Exception to be thrown by updater function to exit from retry in {@link HelixHelper::updatedIdealState}
   */
  public static class PermanentUpdaterException extends RuntimeException {

    public PermanentUpdaterException(String message) {
      super(message);
    }

    public PermanentUpdaterException(Throwable cause) {
      super(cause);
    }
  }

  /**
   * Updates broker resource ideal state for the given broker with the given broker tags. Optional {@code tablesAdded}
   * and {@code tablesRemoved} can be provided to track the tables added/removed during the update.
   */
  public static void updateBrokerResource(HelixManager helixManager, String brokerId, List<String> brokerTags,
      @Nullable List<String> tablesAdded, @Nullable List<String> tablesRemoved) {
    Preconditions.checkArgument(InstanceTypeUtils.isBroker(brokerId), "Invalid broker id: %s", brokerId);
    for (String brokerTag : brokerTags) {
      Preconditions.checkArgument(TagNameUtils.isBrokerTag(brokerTag), "Invalid broker tag: %s", brokerTag);
    }

    Set<String> tablesForBrokerTag;
    int numBrokerTags = brokerTags.size();
    if (numBrokerTags == 0) {
      tablesForBrokerTag = Collections.emptySet();
    } else if (numBrokerTags == 1) {
      tablesForBrokerTag = getTablesForBrokerTag(helixManager, brokerTags.get(0));
    } else {
      tablesForBrokerTag = getTablesForBrokerTags(helixManager, brokerTags);
    }

    updateIdealState(helixManager, BROKER_RESOURCE, idealState -> {
      if (tablesAdded != null) {
        tablesAdded.clear();
      }
      if (tablesRemoved != null) {
        tablesRemoved.clear();
      }
      for (Map.Entry<String, Map<String, String>> entry : idealState.getRecord().getMapFields().entrySet()) {
        String tableNameWithType = entry.getKey();
        Map<String, String> brokerAssignment = entry.getValue();
        if (tablesForBrokerTag.contains(tableNameWithType)) {
          if (brokerAssignment.put(brokerId, BrokerResourceStateModel.ONLINE) == null && tablesAdded != null) {
            tablesAdded.add(tableNameWithType);
          }
        } else {
          if (brokerAssignment.remove(brokerId) != null && tablesRemoved != null) {
            tablesRemoved.add(tableNameWithType);
          }
        }
      }
      return idealState;
    });
  }

  /**
   * Returns all instances for the given cluster.
   *
   * @param helixAdmin The HelixAdmin object used to interact with the Helix cluster
   * @param clusterName Name of the cluster for which to get all the instances for.
   * @return Returns a List of strings containing the instance names for the given cluster.
   */
  public static List<String> getAllInstances(HelixAdmin helixAdmin, String clusterName) {
    return helixAdmin.getInstancesInCluster(clusterName);
  }

  /**
   * Returns all instances for the given resource.
   *
   * @param idealState IdealState of the resource for which to return the instances of.
   * @return Returns a Set of strings containing the instance names for the given cluster.
   */
  public static Set<String> getAllInstancesForResource(IdealState idealState) {
    final Set<String> instances = new HashSet<String>();

    for (final String partition : idealState.getPartitionSet()) {
      for (final String instance : idealState.getInstanceSet(partition)) {
        instances.add(instance);
      }
    }
    return instances;
  }

  /**
   * Toggle the state of the instance between OFFLINE and ONLINE.
   *
   * @param instanceName Name of the instance for which to toggle the state.
   * @param clusterName Name of the cluster to which the instance belongs.
   * @param admin HelixAdmin to access the cluster.
   * @param enable Set enable to true for ONLINE and FALSE for OFFLINE.
   */
  public static void setInstanceState(String instanceName, String clusterName, HelixAdmin admin, boolean enable) {
    admin.enableInstance(clusterName, instanceName, enable);
  }

  public static void setStateForInstanceList(List<String> instances, String clusterName, HelixAdmin admin,
      boolean enable) {
    for (final String instance : instances) {
      setInstanceState(instance, clusterName, admin, enable);
    }
  }

  public static void setStateForInstanceSet(Set<String> instances, String clusterName, HelixAdmin admin,
      boolean enable) {
    for (final String instanceName : instances) {
      setInstanceState(instanceName, clusterName, admin, enable);
    }
  }

  public static Map<String, String> getInstanceConfigsMapFor(String instanceName, String clusterName,
      HelixAdmin admin) {
    final HelixConfigScope scope = getInstanceScopefor(clusterName, instanceName);
    final List<String> keys = admin.getConfigKeys(scope);
    return admin.getConfig(scope, keys);
  }

  public static HelixConfigScope getInstanceScopefor(String clusterName, String instanceName) {
    return new HelixConfigScopeBuilder(ConfigScopeProperty.PARTICIPANT, clusterName).forParticipant(instanceName)
        .build();
  }

  public static HelixConfigScope getResourceScopeFor(String clusterName, String resourceName) {
    return new HelixConfigScopeBuilder(ConfigScopeProperty.RESOURCE, clusterName).forResource(resourceName).build();
  }

  public static Map<String, String> getResourceConfigsFor(String clusterName, String resourceName, HelixAdmin admin) {
    final HelixConfigScope scope = getResourceScopeFor(clusterName, resourceName);
    final List<String> keys = admin.getConfigKeys(scope);
    return admin.getConfig(scope, keys);
  }

  public static void updateResourceConfigsFor(Map<String, String> newConfigs, String resourceName, String clusterName,
      HelixAdmin admin) {
    final HelixConfigScope scope = getResourceScopeFor(clusterName, resourceName);
    admin.setConfig(scope, newConfigs);
  }

  public static void deleteResourcePropertyFromHelix(HelixAdmin admin, String clusterName, String resourceName,
      String configKey) {
    final List<String> keys = new ArrayList<String>();
    keys.add(configKey);

    final HelixConfigScope scope = getResourceScopeFor(clusterName, resourceName);
    admin.removeConfig(scope, keys);
  }

  public static IdealState getTableIdealState(HelixManager manager, String resourceName) {
    final HelixDataAccessor accessor = manager.getHelixDataAccessor();
    final Builder builder = accessor.keyBuilder();
    return accessor.getProperty(builder.idealStates(resourceName));
  }

  public static ExternalView getExternalViewForResource(HelixAdmin admin, String clusterName, String resourceName) {
    return admin.getResourceExternalView(clusterName, resourceName);
  }

  public static Map<String, String> getBrokerResourceConfig(HelixAdmin admin, String clusterName) {
    return HelixHelper.getResourceConfigsFor(clusterName, BROKER_RESOURCE, admin);
  }

  public static void updateBrokerConfig(Map<String, String> brokerResourceConfig, HelixAdmin admin,
      String clusterName) {
    HelixHelper.updateResourceConfigsFor(brokerResourceConfig, BROKER_RESOURCE, clusterName, admin);
  }

  public static IdealState getBrokerIdealStates(HelixAdmin admin, String clusterName) {
    return admin.getResourceIdealState(clusterName, BROKER_RESOURCE);
  }

  /**
   * Remove a resource (offline/realtime table) from the Broker's ideal state.
   *
   * @param helixManager The HelixManager object for accessing helix cluster.
   * @param resourceTag Name of the resource that needs to be removed from Broker ideal state.
   */
  public static void removeResourceFromBrokerIdealState(HelixManager helixManager, final String resourceTag) {
    Function<IdealState, IdealState> updater = new Function<IdealState, IdealState>() {
      @Override
      public IdealState apply(IdealState idealState) {
        if (idealState.getPartitionSet().contains(resourceTag)) {
          idealState.getPartitionSet().remove(resourceTag);
          return idealState;
        } else {
          return null;
        }
      }
    };

    // Removing partitions from ideal state
    LOGGER.info("Trying to remove resource {} from idealstate", resourceTag);
    HelixHelper.updateIdealState(helixManager, CommonConstants.Helix.BROKER_RESOURCE_INSTANCE, updater,
        DEFAULT_RETRY_POLICY);
  }

  /**
   * Returns the set of online instances from external view.
   *
   * @param resourceExternalView External view for the resource.
   * @return Set&lt;String&gt; of online instances in the external view for the resource.
   */
  public static Set<String> getOnlineInstanceFromExternalView(ExternalView resourceExternalView) {
    Set<String> instanceSet = new HashSet<String>();
    if (resourceExternalView != null) {
      for (String partition : resourceExternalView.getPartitionSet()) {
        Map<String, String> stateMap = resourceExternalView.getStateMap(partition);
        for (String instance : stateMap.keySet()) {
          if (stateMap.get(instance).equalsIgnoreCase(ONLINE)) {
            instanceSet.add(instance);
          }
        }
      }
    }
    return instanceSet;
  }

  /**
   * Get a set of offline instance from the external view of the resource.
   *
   * @param resourceExternalView External view of the resource
   * @return Set of string instance names of the offline instances in the external view.
   */
  public static Set<String> getOfflineInstanceFromExternalView(ExternalView resourceExternalView) {
    Set<String> instanceSet = new HashSet<String>();
    for (String partition : resourceExternalView.getPartitionSet()) {
      Map<String, String> stateMap = resourceExternalView.getStateMap(partition);
      for (String instance : stateMap.keySet()) {
        if (stateMap.get(instance).equalsIgnoreCase(OFFLINE)) {
          instanceSet.add(instance);
        }
      }
    }
    return instanceSet;
  }

  /**
   * Remove the segment from the cluster.
   *
   * @param helixManager The HelixManager object to access the helix cluster.
   * @param tableName Name of the table to which the new segment is to be added.
   * @param segmentName Name of the new segment to be added
   */
  public static void removeSegmentFromIdealState(HelixManager helixManager, String tableName,
      final String segmentName) {
    Function<IdealState, IdealState> updater = new Function<IdealState, IdealState>() {
      @Override
      public IdealState apply(IdealState idealState) {
        if (idealState == null) {
          return idealState;
        }
        // partitionSet is never null but let's be defensive anyway
        Set<String> partitionSet = idealState.getPartitionSet();
        if (partitionSet != null) {
          partitionSet.remove(segmentName);
        }
        return idealState;
      }
    };

    updateIdealState(helixManager, tableName, updater, DEFAULT_RETRY_POLICY);
  }

  public static void removeSegmentsFromIdealState(HelixManager helixManager, String tableName,
      final List<String> segments) {
    Function<IdealState, IdealState> updater = new Function<IdealState, IdealState>() {
      @Nullable
      @Override
      public IdealState apply(@Nullable IdealState idealState) {
        if (idealState == null) {
          return idealState;
        }
        // partitionSet is never null but let's be defensive anyway
        Set<String> partitionSet = idealState.getPartitionSet();
        if (partitionSet != null) {
          partitionSet.removeAll(segments);
        }
        return idealState;
      }
    };
    updateIdealState(helixManager, tableName, updater, DEFAULT_RETRY_POLICY);
  }

  /**
   * Returns the config for all the instances in the cluster.
   */
  public static List<InstanceConfig> getInstanceConfigs(HelixManager helixManager) {
    HelixDataAccessor helixDataAccessor = helixManager.getHelixDataAccessor();
    return helixDataAccessor.getChildValues(helixDataAccessor.keyBuilder().instanceConfigs(), true);
  }

  /**
   * Returns the instances in the cluster with the given tag.
   */
  public static List<String> getInstancesWithTag(HelixManager helixManager, String tag) {
    return getInstancesWithTag(getInstanceConfigs(helixManager), tag);
  }

  /**
   *  Returns the instances in the cluster without any tag.
   */
  public static List<String> getInstancesWithoutTag(HelixManager helixManager, String defaultTag) {
    return getInstancesWithoutTag(getInstanceConfigs(helixManager), defaultTag);
  }

  /**
   * Returns the instances in the cluster with the given tag.
   *
   * TODO: refactor code to use this method over {@link #getInstancesWithTag(HelixManager, String)} if applicable to
   * reuse instance configs in order to reduce ZK accesses
   */
  public static List<String> getInstancesWithTag(List<InstanceConfig> instanceConfigs, String tag) {
    List<InstanceConfig> instancesWithTag = getInstancesConfigsWithTag(instanceConfigs, tag);
    return instancesWithTag.stream().map(InstanceConfig::getInstanceName).collect(Collectors.toList());
  }

  /**
   * Retrieves the list of instance names for instances that do not have a specific tag associated with them.
   * This method filters through the provided list of {@link InstanceConfig} objects and identifies those
   * that are associated with the provided {@code defaultTag}, which indicates the absence of a specific tag.
   *
   * @param instanceConfigs the list of {@link InstanceConfig} objects to be checked for instances without tags.
   * @param defaultTag the default tag that represents instances without an associated tag.
   * @return a list of instance names for instances that do not have a specific tag.
   */
  public static List<String> getInstancesWithoutTag(List<InstanceConfig> instanceConfigs, String defaultTag) {
    List<InstanceConfig> instancesWithoutTag = getInstancesConfigsWithoutTag(instanceConfigs, defaultTag);
    return instancesWithoutTag.stream().map(InstanceConfig::getInstanceName).collect(Collectors.toList());
  }

  public static List<InstanceConfig> getInstancesConfigsWithTag(List<InstanceConfig> instanceConfigs, String tag) {
    List<InstanceConfig> instancesWithTag = new ArrayList<>();
    for (InstanceConfig instanceConfig : instanceConfigs) {
      if (instanceConfig.containsTag(tag)) {
        instancesWithTag.add(instanceConfig);
      }
    }
    return instancesWithTag;
  }


  /**
   * Retrieves a list of {@link InstanceConfig} objects that either do not have any tags
   * or are associated with the provided tag, which represents the absence of a specific tag.
   * This method iterates through the provided list of {@link InstanceConfig} objects, checks
   * whether their tag list is empty or if they contain the specified tag, and collects those
   * instances that match the criteria.
   *
   * @param instanceConfigs the list of {@link InstanceConfig} objects to be checked.
   * @param defaultTag the tag used to identify instances that are either untagged or have the specified tag.
   * @return a list of {@link InstanceConfig} objects that are untagged or have the specified tag.
   */
  public static List<InstanceConfig> getInstancesConfigsWithoutTag(
      List<InstanceConfig> instanceConfigs, String defaultTag) {
    List<InstanceConfig> instancesWithoutTag = new ArrayList<>();
    for (InstanceConfig instanceConfig : instanceConfigs) {
      // instanceConfig.getTags() never returns null
      if (instanceConfig.getTags().isEmpty() || instanceConfig.containsTag(defaultTag)) {
        instancesWithoutTag.add(instanceConfig);
      }
    }
    return instancesWithoutTag;
  }

  /**
   * Returns the enabled instances in the cluster with the given tag.
   */
  public static List<String> getEnabledInstancesWithTag(HelixManager helixManager, String tag) {
    return getEnabledInstancesWithTag(getInstanceConfigs(helixManager), tag);
  }

  /**
   * Returns the enabled instances in the cluster with the given tag.
   *
   * TODO: refactor code to use this method over {@link #getEnabledInstancesWithTag(HelixManager, String)} if applicable
   * to reuse instance configs in order to reduce ZK accesses
   */
  public static List<String> getEnabledInstancesWithTag(List<InstanceConfig> instanceConfigs, String tag) {
    List<String> enabledInstancesWithTag = new ArrayList<>();
    for (InstanceConfig instanceConfig : instanceConfigs) {
      if (instanceConfig.getInstanceEnabled() && instanceConfig.containsTag(tag)) {
        enabledInstancesWithTag.add(instanceConfig.getInstanceName());
      }
    }
    return enabledInstancesWithTag;
  }

  /**
   * Returns the server instances in the cluster for the given tenant.
   */
  public static Set<String> getServerInstancesForTenant(HelixManager helixManager, String tenant) {
    return getServerInstancesForTenant(getInstanceConfigs(helixManager), tenant);
  }

  /**
   * Returns the server instances in the cluster for the given tenant.
   *
   * TODO: refactor code to use this method if applicable to reuse instance configs in order to reduce ZK accesses
   */
  public static Set<String> getServerInstancesForTenant(List<InstanceConfig> instanceConfigs, String tenant) {
    return getServerInstancesForTenantWithType(instanceConfigs, tenant, null);
  }

  /**
   * Returns the server instances in the cluster for the given tenant name and tenant type.
   *
   * TODO: refactor code to use this method if applicable to reuse instance configs in order to reduce ZK accesses
   */
  public static Set<String> getServerInstancesForTenantWithType(List<InstanceConfig> instanceConfigs, String tenant,
      TableType tableType) {
    Set<String> serverInstancesWithType = new HashSet<>();
    if (tableType == null || tableType == TableType.OFFLINE) {
      serverInstancesWithType.addAll(
          HelixHelper.getInstancesWithTag(instanceConfigs, TagNameUtils.getOfflineTagForTenant(tenant)));
    }
    if (tableType == null || tableType == TableType.REALTIME) {
      serverInstancesWithType.addAll(
          HelixHelper.getInstancesWithTag(instanceConfigs, TagNameUtils.getRealtimeTagForTenant(tenant)));
    }
    return serverInstancesWithType;
  }

  /**
   * Returns the broker instances in the cluster for the given tenant.
   *
   * TODO: refactor code to use this method if applicable to reuse instance configs in order to reduce ZK accesses
   */
  public static Set<String> getBrokerInstancesForTenant(List<InstanceConfig> instanceConfigs, String tenant) {
    return new HashSet<>(getInstancesWithTag(instanceConfigs, TagNameUtils.getBrokerTagForTenant(tenant)));
  }

  public static Set<InstanceConfig> getBrokerInstanceConfigsForTenant(List<InstanceConfig> instanceConfigs,
      String tenant) {
    return new HashSet<>(getInstancesConfigsWithTag(instanceConfigs, TagNameUtils.getBrokerTagForTenant(tenant)));
  }

  public static Set<String> getTablesForBrokerTag(HelixManager helixManager, String brokerTag) {
    Set<String> tablesForBrokerTag = new HashSet<>();
    List<TableConfig> tableConfigs = ZKMetadataProvider.getAllTableConfigs(helixManager.getHelixPropertyStore());
    for (TableConfig tableConfig : tableConfigs) {
      if (TagNameUtils.getBrokerTagForTenant(tableConfig.getTenantConfig().getBroker()).equals(brokerTag)) {
        tablesForBrokerTag.add(tableConfig.getTableName());
      }
    }
    return tablesForBrokerTag;
  }

  public static Set<String> getTablesForBrokerTags(HelixManager helixManager, List<String> brokerTags) {
    Set<String> tablesForBrokerTags = new HashSet<>();
    List<TableConfig> tableConfigs = ZKMetadataProvider.getAllTableConfigs(helixManager.getHelixPropertyStore());
    for (TableConfig tableConfig : tableConfigs) {
      if (brokerTags.contains(TagNameUtils.getBrokerTagForTenant(tableConfig.getTenantConfig().getBroker()))) {
        tablesForBrokerTags.add(tableConfig.getTableName());
      }
    }
    return tablesForBrokerTags;
  }

  /**
   * Returns the instance config for a specific instance.
   */
  public static InstanceConfig getInstanceConfig(HelixManager helixManager, String instanceId) {
    HelixAdmin admin = helixManager.getClusterManagmentTool();
    String clusterName = helixManager.getClusterName();
    return admin.getInstanceConfig(clusterName, instanceId);
  }

  /**
   * Updates instance config to the Helix property store.
   */
  public static void updateInstanceConfig(HelixManager helixManager, InstanceConfig instanceConfig) {
    // NOTE: Use HelixDataAccessor.setProperty() instead of HelixAdmin.setInstanceConfig() because the latter explicitly
    // forbids instance host/port modification
    HelixDataAccessor helixDataAccessor = helixManager.getHelixDataAccessor();
    Preconditions.checkState(
        helixDataAccessor.setProperty(helixDataAccessor.keyBuilder().instanceConfig(instanceConfig.getId()),
            instanceConfig), "Failed to update instance config for instance: " + instanceConfig.getId());
  }

  /**
   * Updates hostname and port in the instance config, returns {@code true} if the value is updated, {@code false}
   * otherwise.
   */
  public static boolean updateHostnamePort(InstanceConfig instanceConfig, String hostname, int port) {
    boolean updated = false;
    String existingHostname = instanceConfig.getHostName();
    if (!hostname.equals(existingHostname)) {
      LOGGER.info("Updating instance: {} with hostname: {}", instanceConfig.getId(), hostname);
      instanceConfig.setHostName(hostname);
      updated = true;
    }
    String portStr = Integer.toString(port);
    String existingPortStr = instanceConfig.getPort();
    if (!portStr.equals(existingPortStr)) {
      LOGGER.info("Updating instance: {} with port: {}", instanceConfig.getId(), port);
      instanceConfig.setPort(portStr);
      updated = true;
    }
    return updated;
  }

  /**
   * Updates a tlsPort value into Pinot instance config so it can be retrieved later
   * @param instanceConfig the instance config to update
   * @param tlsPort the tlsPort number
   * @return true if updated
   */
  public static boolean updateTlsPort(InstanceConfig instanceConfig, int tlsPort) {
    ExtraInstanceConfig pinotInstanceConfig = new ExtraInstanceConfig(instanceConfig);
    pinotInstanceConfig.setTlsPort(String.valueOf(tlsPort));
    return true;
  }

  /**
   * Return the grpcPort for a given Pinot instance config
   * @param instanceConfig the instance config to fetch
   * @return the grpc port, -1 if not found
   */
  public static String getGrpcPort(InstanceConfig instanceConfig) {
    return instanceConfig.getRecord().getStringField(CommonConstants.Helix.Instance.GRPC_PORT_KEY, "-1");
  }

  /**
   * Adds default tags to the instance config if no tag exists, returns {@code true} if the default tags are added,
   * {@code false} otherwise.
   * <p>The {@code defaultTagsSupplier} is a function which is only invoked when the instance does not have any tag.
   * E.g. () -> Collections.singletonList("DefaultTenant_BROKER").
   */
  public static boolean addDefaultTags(InstanceConfig instanceConfig, Supplier<List<String>> defaultTagsSupplier) {
    List<String> instanceTags = instanceConfig.getTags();
    if (instanceTags.isEmpty()) {
      List<String> defaultTags = defaultTagsSupplier.get();
      if (!CollectionUtils.isEmpty(defaultTags)) {
        LOGGER.info("Updating instance: {} with default tags: {}", instanceConfig.getId(), instanceTags);
        for (String defaultTag : defaultTags) {
          instanceConfig.addTag(defaultTag);
        }
        return true;
      }
    }
    return false;
  }

  /**
   * Removes the disabled partitions from the instance config. Sometimes a partition can be accidentally disabled, and
   * not re-enabled for some reason. When an instance is restarted, we should remove these disabled partitions so that
   * they can be processed.
   */
  public static boolean removeDisabledPartitions(InstanceConfig instanceConfig) {
    ZNRecord record = instanceConfig.getRecord();
    String disabledPartitionsKey = InstanceConfig.InstanceConfigProperty.HELIX_DISABLED_PARTITION.name();
    boolean listUpdated = record.getListFields().remove(disabledPartitionsKey) != null;
    boolean mapUpdated = record.getMapFields().remove(disabledPartitionsKey) != null;
    return listUpdated | mapUpdated;
  }

  public static boolean updatePinotVersion(InstanceConfig instanceConfig) {
    ZNRecord record = instanceConfig.getRecord();
    String currentVer = PinotVersion.VERSION;
    String oldVer = record.getSimpleField(CommonConstants.Helix.Instance.PINOT_VERSION_KEY);
    if (!currentVer.equals(oldVer)) {
      record.setSimpleField(CommonConstants.Helix.Instance.PINOT_VERSION_KEY, currentVer);
      return true;
    }
    return false;
  }
}
