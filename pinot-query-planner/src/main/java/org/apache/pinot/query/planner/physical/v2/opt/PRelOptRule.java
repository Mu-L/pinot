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
package org.apache.pinot.query.planner.physical.v2.opt;

import javax.annotation.Nullable;
import org.apache.pinot.query.planner.physical.v2.PRelNode;


/**
 * Optimization rule for a {@link PRelNode}.
 */
public abstract class PRelOptRule {
  /**
   * Whether an optimization rule should be called for the given {@link PRelNode}.
   */
  public boolean matches(PRelOptRuleCall call) {
    return true;
  }

  /**
   * Allows transforming a {@link PRelNode} into another {@link PRelNode}.
   */
  public abstract PRelNode onMatch(PRelOptRuleCall call);

  /**
   * Called after the subtree rooted at the given {@link PRelNode} is processed completely.
   */
  public PRelNode onDone(PRelNode currentNode) {
    return currentNode;
  }

  @Nullable
  public PRelNode getParentNode(PRelOptRuleCall call) {
    return call._parents.isEmpty() ? null : call._parents.getLast();
  }

  public boolean isLeafBoundary(PRelOptRuleCall call) {
    PRelNode parentNode = getParentNode(call);
    return call._currentNode.isLeafStage() && (parentNode == null || !parentNode.isLeafStage());
  }
}
