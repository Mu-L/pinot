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
package org.apache.pinot.broker.routing.instanceselector;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.helix.store.zk.ZkHelixPropertyStore;
import org.apache.helix.zookeeper.datamodel.ZNRecord;
import org.apache.pinot.broker.routing.adaptiveserverselector.AdaptiveServerSelector;
import org.apache.pinot.broker.routing.adaptiveserverselector.ServerSelectionContext;
import org.apache.pinot.common.metrics.BrokerMeter;
import org.apache.pinot.common.metrics.BrokerMetrics;
import org.apache.pinot.common.utils.HashUtil;
import org.apache.pinot.common.utils.config.QueryOptionsUtils;


/**
 * Instance selector for replica-group routing strategy.
 * <p>The selection algorithm will always evenly distribute the traffic to all replicas of each segment, and will select
 * the same index of the enabled instances for all segments with the same number of replicas. The algorithm is very
 * light-weight and will do best effort to select the least servers for the request.
 * <p>The algorithm relies on the mirror segment assignment from replica-group segment assignment strategy. With mirror
 * segment assignment, any server in one replica-group will always have a corresponding server in other replica-groups
 * that have the same segments assigned. For an example, if S1 is a server in replica-group 1, and it has mirror server
 * S2 in replica-group 2 and S3 in replica-group 3. All segments assigned to S1 will also be assigned to S2 and S3. In
 * stable scenario (external view matches ideal state), all segments assigned to S1 will have the same enabled instances
 * of [S1, S2, S3] sorted (in alphabetical order). If we always pick the same index of enabled instances for all
 * segments, only one of S1, S2, S3 will be picked, so it is guaranteed that we pick the least server instances for the
 * request (there is no guarantee on choosing servers from the same replica-group though). In transitioning/error
 * scenario (external view does not match ideal state), there is no guarantee on picking the least server instances, but
 * the traffic is guaranteed to be evenly distributed to all available instances to avoid overwhelming hotspot servers.
 *<p> If the query option NUM_REPLICA_GROUPS_TO_QUERY is provided, the servers to be picked will be from different
 * replica groups such that segments are evenly distributed amongst the provided value of NUM_REPLICA_GROUPS_TO_QUERY.
 * Thus in case of [S1, S2, S3] if NUM_REPLICA_GROUPS_TO_QUERY = 2, the ReplicaGroup S1 and ReplicaGroup S2 will be
 * selected such that half the segments will come from S1 and other half from S2. If NUM_REPLICA_GROUPS_TO_QUERY value
 * is much greater than available servers, then ReplicaGroupInstanceSelector will behave similar to
 * BalancedInstanceSelector.
 * <p>If AdaptiveServerSelection is enabled, a single snapshot of the server ranking is fetched. This ranking is
 * referenced to pick the best available server for each segment. The algorithm ends up picking the minimum number of
 * servers required to process a query because it references a single snapshot of the server rankings. Currently,
 * NUM_REPLICA_GROUPS_TO_QUERY is not supported is AdaptiveServerSelection is enabled.
 */
public class ReplicaGroupInstanceSelector extends BaseInstanceSelector {

  public ReplicaGroupInstanceSelector(String tableNameWithType, ZkHelixPropertyStore<ZNRecord> propertyStore,
      BrokerMetrics brokerMetrics, @Nullable AdaptiveServerSelector adaptiveServerSelector, Clock clock,
      boolean useFixedReplica, long newSegmentExpirationTimeInSeconds) {
    super(tableNameWithType, propertyStore, brokerMetrics, adaptiveServerSelector, clock, useFixedReplica,
        newSegmentExpirationTimeInSeconds);
  }

  @Override
  Pair<Map<String, String>, Map<String, String>> select(List<String> segments, int requestId,
      SegmentStates segmentStates, Map<String, String> queryOptions) {
    ServerSelectionContext ctx = new ServerSelectionContext(queryOptions);
    if (_adaptiveServerSelector != null) {
      // Adaptive Server Selection is enabled.
      List<SegmentInstanceCandidate> candidateServers = fetchCandidateServersForQuery(segments, segmentStates);

      // Fetch serverRankList before looping through all the segments. This is important to make sure that we pick
      // the least amount of instances for a query by referring to a single snapshot of the rankings.
      List<String> serverRankList = _priorityPoolInstanceSelector.rank(ctx, candidateServers);
      Map<String, Integer> serverRankMap = new HashMap<>();
      for (int idx = 0; idx < serverRankList.size(); idx++) {
        serverRankMap.put(serverRankList.get(idx), idx);
      }
      return selectServersUsingAdaptiveServerSelector(segments, requestId, segmentStates, serverRankMap, ctx);
    } else {
      // Adaptive Server Selection is NOT enabled.
      return selectServersUsingRoundRobin(segments, requestId, segmentStates, ctx);
    }
  }

  private Pair<Map<String, String>, Map<String, String>> selectServersUsingRoundRobin(List<String> segments,
      int requestId, SegmentStates segmentStates, ServerSelectionContext ctx) {
    Map<String, String> segmentToSelectedInstanceMap = new HashMap<>(HashUtil.getHashMapCapacity(segments.size()));
    // No need to adjust this map per total segment numbers, as optional segments should be empty most of the time.
    Map<String, String> optionalSegmentToInstanceMap = new HashMap<>();
    Map<Integer, Integer> poolToSegmentCount = new HashMap<>();
    boolean useFixedReplica = isUseFixedReplica(ctx.getQueryOptions());
    Integer numReplicaGroupsToQuery = QueryOptionsUtils.getNumReplicaGroupsToQuery(ctx.getQueryOptions());
    int numReplicaGroups = numReplicaGroupsToQuery != null ? numReplicaGroupsToQuery : 1;
    int replicaOffset = 0;
    for (String segment : segments) {
      List<SegmentInstanceCandidate> candidates = segmentStates.getCandidates(segment);
      // NOTE: candidates can be null when there is no enabled instances for the segment, or the instance selector has
      // not been updated (we update all components for routing in sequence)
      if (candidates == null) {
        continue;
      }
      // Round robin selection.
      int numCandidates = candidates.size();
      int instanceIdx;

      if (useFixedReplica) {
        // candidates array is always sorted
        instanceIdx = (_tableNameHashForFixedReplicaRouting + replicaOffset) % numCandidates;
      } else {
        instanceIdx = (requestId + replicaOffset) % numCandidates;
      }

      SegmentInstanceCandidate selectedInstance = candidates.get(instanceIdx);
      poolToSegmentCount.merge(selectedInstance.getPool(), 1, Integer::sum);
      // This can only be offline when it is a new segment. And such segment is marked as optional segment so that
      // broker or server can skip it upon any issue to process it.
      if (selectedInstance.isOnline()) {
        segmentToSelectedInstanceMap.put(segment, selectedInstance.getInstance());
      } else {
        optionalSegmentToInstanceMap.put(segment, selectedInstance.getInstance());
      }
      if (numReplicaGroups > numCandidates) {
        numReplicaGroups = numCandidates;
      }
      replicaOffset = (replicaOffset + 1) % numReplicaGroups;
    }
    for (Map.Entry<Integer, Integer> entry : poolToSegmentCount.entrySet()) {
      _brokerMetrics.addMeteredValue(BrokerMeter.POOL_SEG_QUERIES, entry.getValue(),
          BrokerMetrics.getTagForPreferredPool(ctx.getQueryOptions()), String.valueOf(entry.getKey()));
    }
    return Pair.of(segmentToSelectedInstanceMap, optionalSegmentToInstanceMap);
  }

  private Pair<Map<String, String>, Map<String, String>> selectServersUsingAdaptiveServerSelector(List<String> segments,
      int requestId, SegmentStates segmentStates, Map<String, Integer> serverRankMap,
      ServerSelectionContext ctx) {
    Map<String, String> segmentToSelectedInstanceMap = new HashMap<>(HashUtil.getHashMapCapacity(segments.size()));
    // No need to adjust this map per total segment numbers, as optional segments should be empty most of the time.
    Map<String, String> optionalSegmentToInstanceMap = new HashMap<>();
    Map<Integer, Integer> poolToSegmentCount = new HashMap<>();
    for (String segment : segments) {
      // NOTE: candidates can be null when there is no enabled instances for the segment, or the instance selector has
      // not been updated (we update all components for routing in sequence)
      List<SegmentInstanceCandidate> candidates = segmentStates.getCandidates(segment);
      if (candidates == null) {
        continue;
      }

      // Round Robin selection
      int roundRobinInstanceIdx = requestId % candidates.size();
      SegmentInstanceCandidate selectedInstance = candidates.get(roundRobinInstanceIdx);

      // Adaptive Server Selection logic
      if (!serverRankMap.isEmpty()) {
        // Use instance with the best rank if all servers have stats populated, if not use round-robin selected instance
        selectedInstance = candidates.stream()
            .anyMatch(candidate -> !serverRankMap.containsKey(candidate.getInstance()))
            ? candidates.get(roundRobinInstanceIdx)
            : candidates.stream()
                .min(Comparator.comparingInt(candidate -> serverRankMap.get(candidate.getInstance())))
                .orElse(candidates.get(roundRobinInstanceIdx));
      }
      poolToSegmentCount.merge(selectedInstance.getPool(), 1, Integer::sum);
      // This can only be offline when it is a new segment. And such segment is marked as optional segment so that
      // broker or server can skip it upon any issue to process it.
      if (selectedInstance.isOnline()) {
        segmentToSelectedInstanceMap.put(segment, selectedInstance.getInstance());
      } else {
        optionalSegmentToInstanceMap.put(segment, selectedInstance.getInstance());
      }
    }
    for (Map.Entry<Integer, Integer> entry : poolToSegmentCount.entrySet()) {
      _brokerMetrics.addMeteredValue(BrokerMeter.POOL_SEG_QUERIES, entry.getValue(),
          BrokerMetrics.getTagForPreferredPool(ctx.getQueryOptions()), String.valueOf(entry.getKey()));
    }
    return Pair.of(segmentToSelectedInstanceMap, optionalSegmentToInstanceMap);
  }

  private List<SegmentInstanceCandidate> fetchCandidateServersForQuery(List<String> segments,
      SegmentStates segmentStates) {
    Map<String, SegmentInstanceCandidate> candidateServers = new HashMap<>();
    for (String segment : segments) {
      List<SegmentInstanceCandidate> candidates = segmentStates.getCandidates(segment);
      if (candidates == null) {
        continue;
      }
      for (SegmentInstanceCandidate candidate : candidates) {
        candidateServers.put(candidate.getInstance(), candidate);
      }
    }
    return new ArrayList<>(candidateServers.values());
  }
}
