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
package org.apache.pinot.core.query.scheduler.fcfs;

import java.util.concurrent.atomic.LongAccumulator;
import org.apache.pinot.common.metrics.ServerMetrics;
import org.apache.pinot.core.query.executor.QueryExecutor;
import org.apache.pinot.core.query.scheduler.MultiLevelPriorityQueue;
import org.apache.pinot.core.query.scheduler.PriorityScheduler;
import org.apache.pinot.core.query.scheduler.SchedulerGroup;
import org.apache.pinot.core.query.scheduler.SchedulerGroupFactory;
import org.apache.pinot.core.query.scheduler.SchedulerPriorityQueue;
import org.apache.pinot.core.query.scheduler.TableBasedGroupMapper;
import org.apache.pinot.core.query.scheduler.resources.PolicyBasedResourceManager;
import org.apache.pinot.core.query.scheduler.resources.ResourceManager;
import org.apache.pinot.spi.accounting.ThreadResourceUsageAccountant;
import org.apache.pinot.spi.env.PinotConfiguration;


/**
 * Per group FCFS algorithm with bounded resource management per {@link SchedulerGroup}
 * This class is a thin wrapper factory that configures {@link PriorityScheduler} with right
 * concrete classes. All the scheduling logic resides in {@link PriorityScheduler}
 */
public class BoundedFCFSScheduler extends PriorityScheduler {
  public static BoundedFCFSScheduler create(PinotConfiguration config, QueryExecutor queryExecutor,
      ServerMetrics serverMetrics, LongAccumulator latestQueryTime,
      ThreadResourceUsageAccountant resourceUsageAccountant) {
    final ResourceManager rm = new PolicyBasedResourceManager(config, resourceUsageAccountant);
    final SchedulerGroupFactory groupFactory = new SchedulerGroupFactory() {
      @Override
      public SchedulerGroup create(PinotConfiguration config, String groupName) {
        return new FCFSSchedulerGroup(groupName);
      }
    };
    MultiLevelPriorityQueue queue = new MultiLevelPriorityQueue(config, rm, groupFactory, new TableBasedGroupMapper());
    return new BoundedFCFSScheduler(config, rm, queryExecutor, queue, serverMetrics, latestQueryTime);
  }

  private BoundedFCFSScheduler(PinotConfiguration config, ResourceManager resourceManager, QueryExecutor queryExecutor,
      SchedulerPriorityQueue queue, ServerMetrics metrics, LongAccumulator latestQueryTime) {
    super(config, resourceManager, queryExecutor, queue, metrics, latestQueryTime);
  }

  @Override
  public String name() {
    return "BoundedFCFS";
  }
}
