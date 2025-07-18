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
package org.apache.pinot.query.runtime.queries;

import java.util.HashMap;
import java.util.Map;
import org.apache.pinot.query.QueryEnvironmentTestBase;
import org.apache.pinot.query.QueryServerEnclosure;
import org.apache.pinot.query.mailbox.MailboxService;
import org.apache.pinot.query.routing.QueryServerInstance;
import org.apache.pinot.query.testutils.MockInstanceDataManagerFactory;
import org.apache.pinot.query.testutils.QueryTestUtils;
import org.apache.pinot.spi.accounting.ThreadResourceUsageAccountant;
import org.apache.pinot.spi.env.PinotConfiguration;
import org.apache.pinot.spi.utils.CommonConstants;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;


public abstract class QueryRunnerAccountingTest extends QueryRunnerTestBase {

  protected ThreadResourceUsageAccountant _accountant;

  @BeforeClass
  public void setUp()
      throws Exception {
    MockInstanceDataManagerFactory factory1 = new MockInstanceDataManagerFactory("server1");
    factory1.registerTable(QueryRunnerTest.SCHEMA_BUILDER.setSchemaName("a").build(), "a_REALTIME");
    factory1.addSegment("a_REALTIME", QueryRunnerTest.buildRows("a_REALTIME"));

    MockInstanceDataManagerFactory factory2 = new MockInstanceDataManagerFactory("server2");
    factory2.registerTable(QueryRunnerTest.SCHEMA_BUILDER.setSchemaName("b").build(), "b_REALTIME");
    factory2.addSegment("b_REALTIME", QueryRunnerTest.buildRows("b_REALTIME"));

    _reducerHostname = "localhost";
    _reducerPort = QueryTestUtils.getAvailablePort();
    Map<String, Object> reducerConfig = new HashMap<>();
    reducerConfig.put(CommonConstants.MultiStageQueryRunner.KEY_OF_QUERY_RUNNER_HOSTNAME, _reducerHostname);
    reducerConfig.put(CommonConstants.MultiStageQueryRunner.KEY_OF_QUERY_RUNNER_PORT, _reducerPort);
    _mailboxService = new MailboxService(_reducerHostname, _reducerPort, new PinotConfiguration(reducerConfig));
    _mailboxService.start();

    _accountant = getThreadResourceUsageAccountant();

    QueryServerEnclosure server1 = new QueryServerEnclosure(factory1, Map.of(), _accountant);
    server1.start();
    // Start server1 to ensure the next server will have a different port.
    QueryServerEnclosure server2 = new QueryServerEnclosure(factory2, Map.of(), _accountant);
    server2.start();
    // this doesn't test the QueryServer functionality so the server port can be the same as the mailbox port.
    // this is only use for test identifier purpose.
    int port1 = server1.getPort();
    int port2 = server2.getPort();
    _servers.put(new QueryServerInstance("Server_localhost_" + port1, "localhost", port1, port1), server1);
    _servers.put(new QueryServerInstance("Server_localhost_" + port2, "localhost", port2, port2), server2);

    _queryEnvironment = QueryEnvironmentTestBase.getQueryEnvironment(_reducerPort, server1.getPort(), server2.getPort(),
        factory1.getRegisteredSchemaMap(), factory1.buildTableSegmentNameMap(), factory2.buildTableSegmentNameMap(),
        null);
  }

  @AfterClass
  public void tearDown() {
    for (QueryServerEnclosure server : _servers.values()) {
      server.shutDown();
    }
    _mailboxService.shutdown();
  }

  protected abstract ThreadResourceUsageAccountant getThreadResourceUsageAccountant();
}
