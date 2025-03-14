/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.uniffle.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.apache.uniffle.common.RemoteStorageInfo;
import org.apache.uniffle.common.metrics.TestUtils;
import org.apache.uniffle.storage.common.LocalStorage;
import org.apache.uniffle.storage.util.StorageType;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ShuffleServerMetricsTest {

  private static final String SERVER_METRICS_URL = "http://127.0.0.1:12345/metrics/server";
  private static final String SERVER_JVM_URL = "http://127.0.0.1:12345/metrics/jvm";
  private static final String SERVER_GRPC_URL = "http://127.0.0.1:12345/metrics/grpc";
  private static final String SERVER_NETTY_URL = "http://127.0.0.1:12345/metrics/netty";
  private static final String REMOTE_STORAGE_PATH = "hdfs://hdfs1:9000/rss";
  private static final String STORAGE_HOST = "hdfs1";
  private static ShuffleServer shuffleServer;

  private static String encodedTagsForMetricLabel;

  @BeforeAll
  public static void setUp() throws Exception {
    ShuffleServerMetrics.clear();
    ShuffleServerConf ssc = new ShuffleServerConf();
    ssc.set(ShuffleServerConf.JETTY_HTTP_PORT, 12345);
    ssc.set(ShuffleServerConf.JETTY_CORE_POOL_SIZE, 128);
    ssc.set(ShuffleServerConf.RPC_SERVER_PORT, 12346);
    ssc.set(ShuffleServerConf.RSS_STORAGE_BASE_PATH, Arrays.asList("tmp"));
    ssc.set(ShuffleServerConf.DISK_CAPACITY, 1024L * 1024L * 1024L);
    ssc.setString(ShuffleServerConf.RSS_STORAGE_TYPE.key(), StorageType.MEMORY_LOCALFILE.name());
    ssc.set(ShuffleServerConf.RSS_COORDINATOR_QUORUM, "fake.coordinator:123");
    ssc.set(ShuffleServerConf.SERVER_BUFFER_CAPACITY, 1000L);
    shuffleServer = new ShuffleServer(ssc);
    shuffleServer
        .getStorageManager()
        .registerRemoteStorage("metricsTest", new RemoteStorageInfo(REMOTE_STORAGE_PATH));
    shuffleServer.start();
    encodedTagsForMetricLabel = shuffleServer.getEncodedTags();
  }

  @AfterAll
  public static void tearDown() throws Exception {
    shuffleServer.stopServer();
    ShuffleServerMetrics.clear();
  }

  @Test
  public void testJvmMetrics() throws Exception {
    String content = TestUtils.httpGet(SERVER_JVM_URL);
    ObjectMapper mapper = new ObjectMapper();
    JsonNode actualObj = mapper.readTree(content);
    assertEquals(2, actualObj.size());
  }

  @Test
  public void testServerMetrics() throws Exception {
    ShuffleServerMetrics.counterRemoteStorageFailedWrite
        .labels(encodedTagsForMetricLabel, STORAGE_HOST)
        .inc(0);
    ShuffleServerMetrics.counterRemoteStorageSuccessWrite
        .labels(encodedTagsForMetricLabel, STORAGE_HOST)
        .inc(0);
    ShuffleServerMetrics.counterRemoteStorageTotalWrite
        .labels(encodedTagsForMetricLabel, STORAGE_HOST)
        .inc(0);
    ShuffleServerMetrics.counterRemoteStorageRetryWrite
        .labels(encodedTagsForMetricLabel, STORAGE_HOST)
        .inc(0);
    String content = TestUtils.httpGet(SERVER_METRICS_URL);
    ObjectMapper mapper = new ObjectMapper();
    JsonNode actualObj = mapper.readTree(content);
    assertEquals(2, actualObj.size());
    JsonNode metricsNode = actualObj.get("metrics");
    List<String> expectedMetricNames =
        Lists.newArrayList(
            ShuffleServerMetrics.STORAGE_TOTAL_WRITE_REMOTE,
            ShuffleServerMetrics.STORAGE_SUCCESS_WRITE_REMOTE,
            ShuffleServerMetrics.STORAGE_FAILED_WRITE_REMOTE,
            ShuffleServerMetrics.STORAGE_RETRY_WRITE_REMOTE);
    for (String expectMetricName : expectedMetricNames) {
      validateMetrics(
          mapper, metricsNode, expectMetricName, encodedTagsForMetricLabel, STORAGE_HOST);
    }
  }

  private void validateMetrics(
      ObjectMapper mapper, JsonNode metricsNode, String expectedMetricName, String... labels) {
    boolean bingo = false;
    for (int i = 0; i < metricsNode.size(); i++) {
      JsonNode metricsName = metricsNode.get(i).get("name");
      if (expectedMetricName.equals(metricsName.textValue())) {
        List<String> labelValues =
            mapper.convertValue(metricsNode.get(i).get("labelValues"), ArrayList.class);
        assertArrayEquals(labels, labelValues.toArray(new String[0]));
        bingo = true;
        break;
      }
    }
    assertTrue(bingo);
  }

  @Test
  public void testHadoopStorageWriteDataSize() {
    // case1
    String host1 = "hadoop-cluster01";
    ShuffleServerMetrics.incHadoopStorageWriteDataSize(host1, 1000);
    assertEquals(
        1000.0,
        ShuffleServerMetrics.counterTotalHadoopWriteDataSize
            .labels(encodedTagsForMetricLabel, host1)
            .get());

    // case2
    ShuffleServerMetrics.incHadoopStorageWriteDataSize(host1, 500);
    assertEquals(
        1500.0,
        ShuffleServerMetrics.counterTotalHadoopWriteDataSize
            .labels(encodedTagsForMetricLabel, host1)
            .get());

    // case3
    String host2 = "hadoop-cluster2";
    ShuffleServerMetrics.incHadoopStorageWriteDataSize(host2, 2000);
    assertEquals(
        2000.0,
        ShuffleServerMetrics.counterTotalHadoopWriteDataSize
            .labels(encodedTagsForMetricLabel, host2)
            .get());

    // case4
    assertEquals(
        3500.0,
        ShuffleServerMetrics.counterTotalHadoopWriteDataSize
            .labels(encodedTagsForMetricLabel, ShuffleServerMetrics.STORAGE_HOST_LABEL_ALL)
            .get());
  }

  @Test
  public void testStorageCounter() {
    // test for local storage
    ShuffleServerMetrics.incStorageRetryCounter(LocalStorage.STORAGE_HOST);
    assertEquals(1.0, ShuffleServerMetrics.counterLocalStorageTotalWrite.get(), 0.5);
    assertEquals(1.0, ShuffleServerMetrics.counterLocalStorageRetryWrite.get(), 0.5);
    ShuffleServerMetrics.incStorageSuccessCounter(LocalStorage.STORAGE_HOST);
    assertEquals(2.0, ShuffleServerMetrics.counterLocalStorageTotalWrite.get(), 0.5);
    assertEquals(1.0, ShuffleServerMetrics.counterLocalStorageSuccessWrite.get(), 0.5);
    ShuffleServerMetrics.incStorageFailedCounter(LocalStorage.STORAGE_HOST);
    assertEquals(3.0, ShuffleServerMetrics.counterLocalStorageTotalWrite.get(), 0.5);
    assertEquals(1.0, ShuffleServerMetrics.counterLocalStorageFailedWrite.get(), 0.5);

    // test for remote storage
    ShuffleServerMetrics.incStorageRetryCounter(STORAGE_HOST);
    assertEquals(
        1.0,
        ShuffleServerMetrics.counterRemoteStorageTotalWrite
            .labels(encodedTagsForMetricLabel, STORAGE_HOST)
            .get(),
        0.5);
    assertEquals(
        1.0,
        ShuffleServerMetrics.counterRemoteStorageRetryWrite
            .labels(encodedTagsForMetricLabel, STORAGE_HOST)
            .get(),
        0.5);
    ShuffleServerMetrics.incStorageSuccessCounter(STORAGE_HOST);
    assertEquals(
        2.0,
        ShuffleServerMetrics.counterRemoteStorageTotalWrite
            .labels(encodedTagsForMetricLabel, STORAGE_HOST)
            .get(),
        0.5);
    assertEquals(
        1.0,
        ShuffleServerMetrics.counterRemoteStorageSuccessWrite
            .labels(encodedTagsForMetricLabel, STORAGE_HOST)
            .get(),
        0.5);
    ShuffleServerMetrics.incStorageFailedCounter(STORAGE_HOST);
    assertEquals(
        3.0,
        ShuffleServerMetrics.counterRemoteStorageTotalWrite
            .labels(encodedTagsForMetricLabel, STORAGE_HOST)
            .get(),
        0.5);
    assertEquals(
        1.0,
        ShuffleServerMetrics.counterRemoteStorageFailedWrite
            .labels(encodedTagsForMetricLabel, STORAGE_HOST)
            .get(),
        0.5);
  }

  @Test
  public void testGrpcMetrics() throws Exception {
    String content = TestUtils.httpGet(SERVER_GRPC_URL);
    ObjectMapper mapper = new ObjectMapper();
    JsonNode actualObj = mapper.readTree(content);
    assertEquals(2, actualObj.size());
    assertEquals(84, actualObj.get("metrics").size());
  }

  @Test
  public void testNettyMetrics() throws Exception {
    String content = TestUtils.httpGet(SERVER_NETTY_URL);
    ObjectMapper mapper = new ObjectMapper();
    JsonNode actualObj = mapper.readTree(content);
    assertEquals(2, actualObj.size());
    assertEquals(68, actualObj.get("metrics").size());
  }

  @Test
  public void testServerMetricsConcurrently() throws Exception {
    ExecutorService executorService = Executors.newFixedThreadPool(3);
    List<Callable<Void>> calls = new ArrayList<>();
    ShuffleServerMetrics.gaugeInFlushBufferSize.set(0);

    long expectedNum = 0;
    for (int i = 1; i < 5; ++i) {
      int cur = i * i;
      if (i % 2 == 0) {
        calls.add(
            new Callable<Void>() {
              @Override
              public Void call() throws Exception {
                ShuffleServerMetrics.gaugeInFlushBufferSize.inc(cur);
                return null;
              }
            });
        expectedNum += cur;
      } else {
        calls.add(
            new Callable<Void>() {
              @Override
              public Void call() throws Exception {
                ShuffleServerMetrics.gaugeInFlushBufferSize.dec(cur);
                return null;
              }
            });
        expectedNum -= cur;
      }
    }

    List<Future<Void>> results = executorService.invokeAll(calls);
    for (Future<Void> f : results) {
      f.get();
    }

    String content = TestUtils.httpGet(SERVER_METRICS_URL);
    ObjectMapper mapper = new ObjectMapper();
    JsonNode actualObj = mapper.readTree(content);

    final long tmp = expectedNum;
    actualObj
        .get("metrics")
        .iterator()
        .forEachRemaining(
            jsonNode -> {
              String name = jsonNode.get("name").textValue();
              if (name.equals("buffered_data_size")) {
                assertEquals(tmp, jsonNode.get("value").asLong());
              }
            });
  }
}
