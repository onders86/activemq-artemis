/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.tests.integration.cluster.failover;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import org.apache.activemq.artemis.api.config.ActiveMQDefaultConfiguration;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.api.core.client.ActiveMQClient;
import org.apache.activemq.artemis.api.core.client.ClientConsumer;
import org.apache.activemq.artemis.api.core.client.ClientProducer;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.client.ServerLocator;
import org.apache.activemq.artemis.core.client.impl.ClientSessionFactoryInternal;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.remoting.impl.netty.TransportConstants;
import org.apache.activemq.artemis.core.server.NodeManager;
import org.apache.activemq.artemis.core.server.impl.InVMNodeManager;
import org.apache.activemq.artemis.core.server.impl.jdbc.JdbcNodeManager;
import org.apache.activemq.artemis.jdbc.store.sql.PropertySQLProvider;
import org.apache.activemq.artemis.jdbc.store.sql.SQLProvider;
import org.apache.activemq.artemis.utils.ExecutorFactory;
import org.apache.activemq.artemis.utils.actors.OrderedExecutorFactory;
import org.hamcrest.core.Is;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import static org.apache.activemq.artemis.jdbc.store.sql.PropertySQLProvider.Factory.SQLDialect.DERBY;

@RunWith(Parameterized.class)
public class NettyFailoverTest extends FailoverTest {

   private static final long JDBC_LOCK_EXPIRATION_MILLIS = ActiveMQDefaultConfiguration.getDefaultJdbcLockExpirationMillis();
   private static final long JDBC_LOCK_RENEW_PERIOD_MILLIS = ActiveMQDefaultConfiguration.getDefaultJdbcLockRenewPeriodMillis();
   private static final long JDBC_LOCK_ACQUISITION_TIMEOUT_MILLIS = ActiveMQDefaultConfiguration.getDefaultJdbcLockAcquisitionTimeoutMillis();
   private static final SQLProvider SQL_PROVIDER = new PropertySQLProvider.Factory(DERBY).create(ActiveMQDefaultConfiguration.getDefaultNodeManagerStoreTableName(), SQLProvider.DatabaseStoreType.NODE_MANAGER);
   private static final String JDBC_URL = "jdbc:derby:memory:server_lock_db;create=true";
   private static final String DRIVER_CLASS_NAME = "org.apache.derby.jdbc.EmbeddedDriver";

   public enum NodeManagerType {
      InVM, Jdbc
   }

   @Parameterized.Parameters(name = "{0} Node Manager")
   public static Iterable<? extends Object> nodeManagerTypes() {
      return Arrays.asList(new Object[][]{{NodeManagerType.Jdbc}, {NodeManagerType.InVM}});
   }

   @Parameterized.Parameter
   public NodeManagerType nodeManagerType;

   @Override
   protected TransportConfiguration getAcceptorTransportConfiguration(final boolean live) {
      return getNettyAcceptorTransportConfiguration(live);
   }

   @Override
   protected TransportConfiguration getConnectorTransportConfiguration(final boolean live) {
      return getNettyConnectorTransportConfiguration(live);
   }

   private ScheduledExecutorService scheduledExecutorService;
   private ExecutorService executor;

   @Override
   protected NodeManager createReplicatedBackupNodeManager(Configuration backupConfig) {
      Assume.assumeThat("Replicated backup is supported only by " + NodeManagerType.InVM + " Node Manager", nodeManagerType, Is.is(NodeManagerType.InVM));
      return super.createReplicatedBackupNodeManager(backupConfig);
   }

   @Override
   protected NodeManager createNodeManager() {

      switch (nodeManagerType) {

         case InVM:
            return new InVMNodeManager(false);
         case Jdbc:
            //It can uses an in memory JavaDB: the failover tests are in process
            final ThreadFactory daemonThreadFactory = t -> {
               final Thread th = new Thread(t);
               th.setDaemon(true);
               return th;
            };
            scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory);
            executor = Executors.newFixedThreadPool(2, daemonThreadFactory);
            final ExecutorFactory executorFactory = new OrderedExecutorFactory(executor);
            return JdbcNodeManager.usingConnectionUrl(UUID.randomUUID().toString(), JDBC_LOCK_EXPIRATION_MILLIS, JDBC_LOCK_RENEW_PERIOD_MILLIS, JDBC_LOCK_ACQUISITION_TIMEOUT_MILLIS, JDBC_URL, DRIVER_CLASS_NAME, SQL_PROVIDER, scheduledExecutorService, executorFactory, (code, message, file) -> {
               code.printStackTrace();
               Assert.fail(message);
            });
         default:
            throw new AssertionError("enum type not supported!");
      }
   }

   @After
   public void shutDownExecutors() {
      if (scheduledExecutorService != null) {
         executor.shutdown();
         scheduledExecutorService.shutdown();
         this.executor = null;
         this.scheduledExecutorService = null;
      }
   }

   @Test(timeout = 120000)
   public void testFailoverWithHostAlias() throws Exception {
      Map<String, Object> params = new HashMap<>();
      params.put(TransportConstants.HOST_PROP_NAME, "127.0.0.1");
      TransportConfiguration tc = createTransportConfiguration(true, false, params);

      ServerLocator locator = addServerLocator(ActiveMQClient.createServerLocatorWithHA(tc)).setBlockOnNonDurableSend(true).setBlockOnDurableSend(true).setBlockOnAcknowledge(true).setReconnectAttempts(15);

      ClientSessionFactoryInternal sf = createSessionFactoryAndWaitForTopology(locator, 2);

      ClientSession session = createSession(sf, true, true, 0);

      session.createQueue(ADDRESS, ADDRESS, null, true);

      ClientProducer producer = session.createProducer(ADDRESS);

      final int numMessages = 10;

      ClientConsumer consumer = session.createConsumer(ADDRESS);

      session.start();

      crash(session);

      sendMessages(session, producer, numMessages);
      receiveMessages(consumer, 0, numMessages, true);

      session.close();

      sf.close();

      Assert.assertEquals(0, sf.numSessions());

      Assert.assertEquals(0, sf.numConnections());
   }

}
