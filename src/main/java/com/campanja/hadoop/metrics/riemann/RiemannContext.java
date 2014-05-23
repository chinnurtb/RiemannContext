/*
 * GraphiteContext.java
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.campanja.hadoop.metrics.riemann;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;

import org.apache.hadoop.metrics.ContextFactory;
import org.apache.hadoop.metrics.spi.AbstractMetricsContext;
import org.apache.hadoop.metrics.spi.OutputRecord;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.aphyr.riemann.client.RiemannClient;
import com.aphyr.riemann.client.SynchronizeTransport;
import com.aphyr.riemann.client.TcpTransport;
import com.aphyr.riemann.client.EventDSL;
import com.aphyr.riemann.Proto.Event;
import com.aphyr.riemann.Proto.Msg;

import java.util.concurrent.TimeUnit;

import java.net.InetSocketAddress;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Metrics context for writing metrics to Riemann.<p/>
 *
 * This class is configured by setting ContextFactory attributes which in turn
 * are usually configured through a properties file.  All the attributes are
 * prefixed by the contextName. For example, the properties file might contain:
 * <pre>
 * mapred.class=org.apache.hadoop.metrics.graphite.GraphiteContext
 * mapred.period=60
 * mapred.serverName=graphite.foo.bar
 * mapred.port=2013
 * </pre>
 */
@SuppressWarnings("deprecation")
public class RiemannContext extends AbstractMetricsContext {
  /* Configuration attribute names */
  private RiemannClient client;

  private Map<String, String> attributes;
  private List<String> tags;

  private final Log LOG = LogFactory.getLog(this.getClass());

  /** Creates a new instance of GraphiteContext */
  public RiemannContext() {}

  public void init(String contextName, ContextFactory factory) {
    super.init(contextName, factory);

    String server = getAttribute("server");
    Integer port = Integer.parseInt(getAttribute("port"));
    attributes = getAttributeTable("attributes");
    String taglist = getAttribute("tags");
    if (taglist != null) {
      tags = new ArrayList<String>(Arrays.asList(taglist.split(",")));
    }

    try {
      client = RiemannClient.tcp(server, port);
      SynchronizeTransport st = (SynchronizeTransport)client.transport;
      TcpTransport tt = (TcpTransport)st.transport;
      tt.cacheDns.set(false);
      client.connect();
    } catch (Exception e) {
      LOG.error(e);
    }
    parseAndSetPeriod("period");
  }

  /**
   * Emits a metrics record to Graphite.
   */
  public void emitRecord(String contextName, String recordName, OutputRecord outRec) throws IOException {
    String hostname = outRec.getTag("hostName").toString(); // Only want to send first part of hostname.  
    String split[] = hostname.split("\\.");

    List<Event> events = new ArrayList<Event>();

    long tm = System.currentTimeMillis() ; // Graphite doesn't handle milliseconds
    for (String metricName : outRec.getMetricNames()) {
      StringBuilder service = new StringBuilder();
      service.append("hadoop " + contextName + " ");
      if (contextName.equals("jvm") && outRec.getTag("processName") != null) {
        service.append(outRec.getTag("processName") + " ");
      }
      final String mName = metricName.replaceAll("\\.{2,}", ".");
      service.append(mName);
      EventDSL ev = client.event().
        host(split[0]).
        service(service.toString()).
        metric(outRec.getMetric(metricName).doubleValue()).
        tags("hadoop").
        attribute("contextName", contextName).
        ttl(getPeriod()*2);

      if (attributes != null) {
        ev.attributes(attributes);
      }

      if (tags != null) {
        ev.tags(tags);
      }
      events.add(ev.build());
    }
    final Msg msg = Msg.newBuilder().
      addAllEvents(events).
      build();
    try {
      client.sendRecvMessage(msg);
    } catch (Exception e) {
      LOG.info("Exception: " + e);
    }
    LOG.info("emitted metrics in " + Long.toString(System.currentTimeMillis()-tm));
  }
}
