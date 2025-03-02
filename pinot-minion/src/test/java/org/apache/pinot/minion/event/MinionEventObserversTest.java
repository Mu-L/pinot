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
package org.apache.pinot.minion.event;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.pinot.minion.MinionConf;
import org.apache.pinot.minion.MinionTestUtils;
import org.apache.pinot.spi.utils.CommonConstants;
import org.apache.pinot.util.TestUtils;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;


public class MinionEventObserversTest {
  @Test
  public void testCleanupImmediately() {
    MinionConf config = new MinionConf();
    MinionEventObservers.init(config, null);
    for (String taskId : new String[]{"t01", "t02", "t03"}) {
      MinionEventObserver observer = MinionTestUtils.getMinionProgressObserver();
      MinionEventObservers.getInstance().addMinionEventObserver(taskId, observer);
      assertSame(MinionEventObservers.getInstance().getMinionEventObserver(taskId), observer);
      MinionEventObservers.getInstance().removeMinionEventObserver(taskId);
      assertNull(MinionEventObservers.getInstance().getMinionEventObserver(taskId));
    }
  }

  @Test
  public void testCleanupWithDelay() {
    ExecutorService executor = Executors.newCachedThreadPool();
    MinionConf config = new MinionConf();
    config.setProperty(CommonConstants.Minion.CONFIG_OF_EVENT_OBSERVER_CLEANUP_DELAY_IN_SEC, 2);
    MinionEventObservers.init(config, executor);
    String[] taskIds = new String[]{"t01", "t02", "t03"};
    for (String taskId : taskIds) {
      MinionEventObserver observer = MinionTestUtils.getMinionProgressObserver();
      MinionEventObservers.getInstance().addMinionEventObserver(taskId, observer);
      assertSame(MinionEventObservers.getInstance().getMinionEventObserver(taskId), observer);
      MinionEventObservers.getInstance().removeMinionEventObserver(taskId);
      assertNotNull(MinionEventObservers.getInstance().getMinionEventObserver(taskId));
    }
    for (String taskId : taskIds) {
      TestUtils
          .waitForCondition(aVoid -> MinionEventObservers.getInstance().getMinionEventObserver(taskId) == null, 5000,
              "Failed to clean up observer");
    }
  }

  @Test
  public void testGetMinionEventObserverWithGivenState() {
    MinionEventObserver observer1 = MinionTestUtils.getMinionProgressObserver();
    observer1.notifyTaskStart(MinionTestUtils.getPinotTaskConfig("t01"));
    MinionEventObservers.getInstance().addMinionEventObserver("t01", observer1);

    MinionEventObserver observer2 = MinionTestUtils.getMinionProgressObserver();
    observer2.notifyTaskStart(MinionTestUtils.getPinotTaskConfig("t02"));
    observer2.notifyProgress(MinionTestUtils.getPinotTaskConfig("t02"), "");
    MinionEventObservers.getInstance().addMinionEventObserver("t02", observer2);

    MinionEventObserver observer3 = MinionTestUtils.getMinionProgressObserver();
    observer3.notifyTaskStart(MinionTestUtils.getPinotTaskConfig("t03"));
    observer3.notifyTaskSuccess(MinionTestUtils.getPinotTaskConfig("t03"), "");
    MinionEventObservers.getInstance().addMinionEventObserver("t03", observer3);

    Map<String, MinionEventObserver> minionEventObserverWithGivenState =
        MinionEventObservers.getInstance().getMinionEventObserverWithGivenState(MinionTaskState.IN_PROGRESS);
    assertEquals(minionEventObserverWithGivenState.size(), 2);
    assertSame(minionEventObserverWithGivenState.get("t01"), observer1);
    assertSame(minionEventObserverWithGivenState.get("t02"), observer2);
  }
}
