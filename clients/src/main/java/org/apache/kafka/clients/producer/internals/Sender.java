/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.kafka.clients.producer.internals;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.ClientRequest;
import org.apache.kafka.clients.ClientResponse;
import org.apache.kafka.clients.KafkaClient;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.RequestCompletionHandler;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InvalidMetadataException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.metrics.Measurable;
import org.apache.kafka.common.metrics.MetricConfig;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.stats.Avg;
import org.apache.kafka.common.metrics.stats.Max;
import org.apache.kafka.common.metrics.stats.Rate;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.requests.ProduceRequest;
import org.apache.kafka.common.requests.ProduceResponse;
import org.apache.kafka.common.requests.RequestSend;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The background thread that handles the sending of produce requests to the Kafka cluster. This thread makes metadata
 * requests to renew its view of the cluster and then sends produce requests to the appropriate nodes.
 */
public class Sender implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(Sender.class);

    /* the state of each nodes connection */
    private final KafkaClient client;

    /* the record accumulator that batches records */
    private final RecordAccumulator accumulator;

    /* the metadata for the client */
    private final Metadata metadata;

    /* the flag indicating whether the producer should guarantee the message order on the broker or not. */
    private final boolean guaranteeMessageOrder;

    /* the maximum request size to attempt to send to the server */
    private final int maxRequestSize;

    /* the number of acknowledgements to request from the server */
    private final short acks;

    /* the number of times to retry a failed request before giving up */
    private final int retries;

    /* the clock instance used for getting the time */
    private final Time time;

    /* true while the sender thread is still running */
    private volatile boolean running;

    /* true when the caller wants to ignore all unsent/inflight messages and force close.  */
    private volatile boolean forceClose;

    /* metrics */
    private final SenderMetrics sensors;

    /* param clientId of the client */
    private String clientId;

    /* the max time to wait for the server to respond to the request*/
    private final int requestTimeout;

    public Sender(KafkaClient client,
                  Metadata metadata,
                  RecordAccumulator accumulator,
                  boolean guaranteeMessageOrder,
                  int maxRequestSize,
                  short acks,
                  int retries,
                  Metrics metrics,
                  Time time,
                  String clientId,
                  int requestTimeout) {
        this.client = client;
        this.accumulator = accumulator;
        this.metadata = metadata;
        this.guaranteeMessageOrder = guaranteeMessageOrder;
        this.maxRequestSize = maxRequestSize;
        this.running = true;
        this.acks = acks;
        this.retries = retries;
        this.time = time;
        this.clientId = clientId;
        this.sensors = new SenderMetrics(metrics);
        this.requestTimeout = requestTimeout;
    }

    /**
     * The main run loop for the sender thread
     */
    public void run() {
        log.debug("Starting Kafka producer I/O thread.");

        /**
         * sender 线程启动之后会一直运行
         */
        // main loop, runs until close is called
        while (running) {
            try {
                /**
                 * TODO
                 * 核心处理逻辑
                 */
                run(time.milliseconds());
            } catch (Exception e) {
                log.error("Uncaught error in kafka producer I/O thread: ", e);
            }
        }

        log.debug("Beginning shutdown of Kafka producer I/O thread, sending remaining records.");

        // okay we stopped accepting requests but there may still be
        // requests in the accumulator or waiting for acknowledgment,
        // wait until these are completed.
        while (!forceClose && (this.accumulator.hasUnsent() || this.client.inFlightRequestCount() > 0)) {
            try {
                run(time.milliseconds());
            } catch (Exception e) {
                log.error("Uncaught error in kafka producer I/O thread: ", e);
            }
        }
        if (forceClose) {
            // We need to fail all the incomplete batches and wake up the threads waiting on
            // the futures.
            this.accumulator.abortIncompleteBatches();
        }
        try {
            this.client.close();
        } catch (Exception e) {
            log.error("Failed to close network client", e);
        }

        log.debug("Shutdown of Kafka producer I/O thread has completed.");
    }

    /**
     * Run a single iteration of sending
     * 
     * @param now
     *            The current POSIX time in milliseconds
     */
    void run(long now) {
        /**
         * 获取生产者本地的元数据信息
         * 此时本地的元数据中只有 bootstrap.servers 信息
         *
         * 因为 cluster 中还没有任何元数据，所以下面的代码都不能被执行
         * 因为下面的代码逻辑都需要有元数据进行驱动
         * 因此直接跳转到最后的 this.client.poll(pollTimeout, now); 去发送网络请求更新元数据
         *
         * 如果已经有元数据以后，就会尝试发送消息到 kafka 服务端
         * 发送流程如下：
         * 步骤一：
         *      获取元数据
         */
        Cluster cluster = metadata.fetch();
        /**
         * 步骤二：
         *      从 RecordAccumulator 获取已经准备好的 batch
         * 获取对应 partition 对应的 leader partition 对应的 broker 地址
         *
         * 生产者消息重试机制：
         * 1. 重试次数
         * 2. 重试的时间间隔
         *
         * 消息发送的时候，如果一直凑不齐一个 batch，也会限定一个时间，要求在这个限定时间之内即使不满足一个 batch，也要把这个 batch 发送出去
         *
         * result = ReadyCheckResult(readyNodes, nextReadyCheckDelayMs, unknownLeaderTopics)
         */
        // get the list of partitions with data ready to send
        RecordAccumulator.ReadyCheckResult result = this.accumulator.ready(cluster, now);

        /**
         * 步骤三：
         *      标识还没有拉取到元数据的 topic，继续发送拉取元数据的请求
         */
        // if there are any partitions whose leaders are not known yet, force metadata update
        if (!result.unknownLeaderTopics.isEmpty()) {
            // The set of topics with unknown leader contains topics with leader election pending as well as
            // topics which may have expired. Add the topic again to metadata to ensure it is included
            // and request metadata update, since there are messages to send to the topic.
            for (String topic : result.unknownLeaderTopics)
                this.metadata.add(topic);
            this.metadata.requestUpdate();
        }

        // remove any nodes we aren't ready to send to
        Iterator<Node> iter = result.readyNodes.iterator();
        long notReadyTimeout = Long.MAX_VALUE;
        while (iter.hasNext()) {
            Node node = iter.next();
            /**
             * 步骤四：
             *      检查要与之通信的 node 主机是否建立好连接
             *
             * this.client = new NetworkClient()
             */
            if (!this.client.ready(node, now)) {
                /**
                 * 如果网络连接没有建立，那么就移除 result 里面的 Node
                 *
                 * 如果是初次发送消息，那么所有到 Node 的连接都没有建立
                 * 因此后续步骤中的 result.readyNodes 就为空，不会发送消息，
                 * 最终在 this.client.poll(pollTimeout, now); 中建立网络连接
                 */
                iter.remove();
                notReadyTimeout = Math.min(notReadyTimeout, this.client.connectionDelay(node, now));
            }
        }

        /**
         * 步骤五：
         *      我们可能有多个 partition 分区的数据需要发送
         * 很有可能有些 partition 的 leader 在同一个 node 主机上
         * p0:leader:0
         * p1:leader:0
         * p2:leader:1
         * p3:leader:2
         *
         * 因此根据 broker 进行分组，同一个 broker 的 partition 为同一组
         * 这样做可以提高网络发送的效率，提高性能
         * 0:(p0, p1)
         * 1:(p2)
         * 2:(p3)
         */
        // create produce requests
        Map<Integer, List<RecordBatch>> batches = this.accumulator.drain(cluster,
                                                                         result.readyNodes,
                                                                         this.maxRequestSize,
                                                                         now);
        if (guaranteeMessageOrder) {
            // Mute all the partitions drained
            for (List<RecordBatch> batchList : batches.values()) {
                for (RecordBatch batch : batchList)
                    this.accumulator.mutePartition(batch.topicPartition);
            }
        }

        /**
         * 步骤六：
         *      放弃超时的 batch
         */
        List<RecordBatch> expiredBatches = this.accumulator.abortExpiredBatches(this.requestTimeout, now);
        // update sensors
        for (RecordBatch expiredBatch : expiredBatches)
            this.sensors.recordErrors(expiredBatch.topicPartition.topic(), expiredBatch.recordCount);

        sensors.updateProduceRequestMetrics(batches);
        /**
         * 步骤七：
         *      创建发送消息的请求
         */
        List<ClientRequest> requests = createProduceRequests(batches, now);
        // If we have any nodes that are ready to send + have sendable data, poll with 0 timeout so this can immediately
        // loop and try sending more data. Otherwise, the timeout is determined by nodes that have partitions with data
        // that isn't yet sendable (e.g. lingering, backing off). Note that this specifically does not include nodes
        // with sendable data that aren't ready to send since they would cause busy looping.
        long pollTimeout = Math.min(result.nextReadyCheckDelayMs, notReadyTimeout);
        if (result.readyNodes.size() > 0) {
            log.trace("Nodes with data ready to send: {}", result.readyNodes);
            log.trace("Created {} produce requests: {}", requests.size(), requests);
            pollTimeout = 0;
        }
        /**
         * 遍历每个请求进行发送
         */
        for (ClientRequest request : requests)
            client.send(request, now);

        /**
         * 步骤八：
         *      真正执行网络操作的都是这个 NetworkClient 组件
         *
         */
        // if some partitions are already ready to be sent, the select time would be 0;
        // otherwise if some partition already has some data accumulated but not ready yet,
        // the select time will be the time difference between now and its linger expiry time;
        // otherwise the select time will be the time difference between now and the metadata expiry time;
        this.client.poll(pollTimeout, now);
    }

    /**
     * Start closing the sender (won't actually complete until all data is sent out)
     */
    public void initiateClose() {
        // Ensure accumulator is closed first to guarantee that no more appends are accepted after
        // breaking from the sender loop. Otherwise, we may miss some callbacks when shutting down.
        this.accumulator.close();
        this.running = false;
        this.wakeup();
    }

    /**
     * Closes the sender without sending out any pending messages.
     */
    public void forceClose() {
        this.forceClose = true;
        initiateClose();
    }

    /**
     * Handle a produce response
     */
    private void handleProduceResponse(ClientResponse response, Map<TopicPartition, RecordBatch> batches, long now) {
        /**
         *
         * 响应消息的流转流程：
         * 1. 将响应消息从服务端接收，并存储在 networkReceive 里面
         * 2. 将 networkReceive 缓存到 stagedReceives 里面
         * 3. 将 networkReceive 从 stagedReceives 取出，存入 completedReceives 中
         * 4. 遍历 completedReceives 中的 networkReceive，
         *    根据 networkReceive 初始化 ClientResponse，将 ClientResponse 存储到 responses 中
         * 5. 调用请求中的回调函数处理响应
         * 6. handleProduceResponse 真正处理网络响应
         * 7. completeBatch 处理网络响应
         */
        int correlationId = response.request().request().header().correlationId();
        if (response.wasDisconnected()) {
            /**
             * 在发送消息之前已经建立连接，但是等到真正发送消息的时候连接已经断开
             */
            log.trace("Cancelled request {} due to node {} being disconnected", response, response.request()
                                                                                                  .request()
                                                                                                  .destination());
            for (RecordBatch batch : batches.values())
                completeBatch(batch, Errors.NETWORK_EXCEPTION, -1L, Record.NO_TIMESTAMP, correlationId, now);
        } else {
            log.trace("Received produce response from node {} with correlation id {}",
                      response.request().request().destination(),
                      correlationId);
            // if we have a response, parse it
            if (response.hasResponse()) {
                /**
                 * acks != 0
                 */
                ProduceResponse produceResponse = new ProduceResponse(response.responseBody());
                /**
                 * 响应中可能有多个分区的多个 batch 的响应
                 *
                 * partition -> batch, batch
                 * partition -> broker id
                 *
                 * broker id -> batch, batch
                 */
                for (Map.Entry<TopicPartition, ProduceResponse.PartitionResponse> entry : produceResponse.responses().entrySet()) {
                    TopicPartition tp = entry.getKey();
                    ProduceResponse.PartitionResponse partResp = entry.getValue();
                    /**
                     * 生产者发送消息到服务端之后，服务端接收消息后进行处理
                     * 处理可能成功，也可能失败
                     * error 保存的就是服务端处理失败之后的异常码
                     */
                    Errors error = Errors.forCode(partResp.errorCode);
                    RecordBatch batch = batches.get(tp);
                    completeBatch(batch, error, partResp.baseOffset, partResp.timestamp, correlationId, now);
                }
                this.sensors.recordLatency(response.request().request().destination(), response.requestLatencyMs());
                this.sensors.recordThrottleTime(response.request().request().destination(),
                                                produceResponse.getThrottleTime());
            } else {
                /**
                 * acks == 0
                 */
                // this is the acks = 0 case, just complete all requests
                for (RecordBatch batch : batches.values())
                    completeBatch(batch, Errors.NONE, -1L, Record.NO_TIMESTAMP, correlationId, now);
            }
        }
    }

    /**
     * Complete or retry the given batch of records.
     * 
     * @param batch The record batch
     * @param error The error (or null if none)
     * @param baseOffset The base offset assigned to the records if successful
     * @param timestamp The timestamp returned by the broker for this batch
     * @param correlationId The correlation id for the request
     * @param now The current POSIX time stamp in milliseconds
     */
    // completeBatch(batch, error, partResp.baseOffset, partResp.timestamp, correlationId, now);
    private void completeBatch(RecordBatch batch, Errors error, long baseOffset, long timestamp, long correlationId, long now) {
        /**
         *
         * 响应消息的流转流程：
         * 1. 将响应消息从服务端接收，并存储在 networkReceive 里面
         * 2. 将 networkReceive 缓存到 stagedReceives 里面
         * 3. 将 networkReceive 从 stagedReceives 取出，存入 completedReceives 中
         * 4. 遍历 completedReceives 中的 networkReceive，
         *    根据 networkReceive 初始化 ClientResponse，将 ClientResponse 存储到 responses 中
         * 5. 调用请求中的回调函数处理响应
         * 6. handleProduceResponse 真正处理网络响应
         * 7. completeBatch 处理网络响应
         * 8. batch.done() 处理网络响应
         */

        /**
         * error 不为空，说明服务端响应出现异常，需要进行重试
         * canRetry(batch, error) 返回这个 batch 请求是否可以重试
         */
        if (error != Errors.NONE && canRetry(batch, error)) {
            /**
             * 有异常，并且可重试
             */
            // retry
            log.warn("Got error produce response with correlation id {} on topic-partition {}, retrying ({} attempts left). Error: {}",
                     correlationId,
                     batch.topicPartition,
                     this.retries - batch.attempts - 1,
                     error);
            /**
             * 将有异常的 batch 重新入队
             */
            this.accumulator.reenqueue(batch, now);
            this.sensors.recordRetries(batch.topicPartition.topic(), batch.recordCount);
        } else {
            /**
             * 有异常，但不可以重试
             */
            RuntimeException exception;
            /**
             * 生产者对要发送消息的 topic 没有权限
             */
            if (error == Errors.TOPIC_AUTHORIZATION_FAILED)
                exception = new TopicAuthorizationException(batch.topicPartition.topic());
            else
                exception = error.exception();
            // tell the user the result of their request
            batch.done(baseOffset, timestamp, exception);
            /**
             * 回收 accumulator 缓存中的内存
             */
            this.accumulator.deallocate(batch);
            if (error != Errors.NONE)
                this.sensors.recordErrors(batch.topicPartition.topic(), batch.recordCount);
        }
        if (error.exception() instanceof InvalidMetadataException) {
            if (error.exception() instanceof UnknownTopicOrPartitionException)
                log.warn("Received unknown topic or partition error in produce request on partition {}. The " +
                        "topic/partition may not exist or the user may not have Describe access to it", batch.topicPartition);
            metadata.requestUpdate();
        }

        // Unmute the completed partition.
        if (guaranteeMessageOrder)
            this.accumulator.unmutePartition(batch.topicPartition);
    }

    /**
     * We can retry a send if the error is transient and the number of attempts taken is fewer than the maximum allowed
     */
    private boolean canRetry(RecordBatch batch, Errors error) {
        /**
         * 可重试的条件是：已经重试的次数小于 retries 并且异常属于可重试异常
         * 有些异常是不可以重试的，比如对 topic 没有权限
         */
        return batch.attempts < this.retries && error.exception() instanceof RetriableException;
    }

    /**
     * Transfer the record batches into a list of produce requests on a per-node basis
     */
    private List<ClientRequest> createProduceRequests(Map<Integer, List<RecordBatch>> collated, long now) {
        List<ClientRequest> requests = new ArrayList<ClientRequest>(collated.size());
        for (Map.Entry<Integer, List<RecordBatch>> entry : collated.entrySet())
            requests.add(produceRequest(now, entry.getKey(), acks, requestTimeout, entry.getValue()));
        return requests;
    }

    /**
     * Create a produce request from the given record batches
     */
    private ClientRequest produceRequest(long now, int destination, short acks, int timeout, List<RecordBatch> batches) {
        Map<TopicPartition, ByteBuffer> produceRecordsByPartition = new HashMap<TopicPartition, ByteBuffer>(batches.size());
        final Map<TopicPartition, RecordBatch> recordsByPartition = new HashMap<TopicPartition, RecordBatch>(batches.size());
        for (RecordBatch batch : batches) {
            TopicPartition tp = batch.topicPartition;
            produceRecordsByPartition.put(tp, batch.records.buffer());
            recordsByPartition.put(tp, batch);
        }
        ProduceRequest request = new ProduceRequest(acks, timeout, produceRecordsByPartition);
        RequestSend send = new RequestSend(Integer.toString(destination),
                                           this.client.nextRequestHeader(ApiKeys.PRODUCE),
                                           request.toStruct());
        RequestCompletionHandler callback = new RequestCompletionHandler() {
            public void onComplete(ClientResponse response) {
                /**
                 *
                 * 响应消息的流转流程：
                 * 1. 将响应消息从服务端接收，并存储在 networkReceive 里面
                 * 2. 将 networkReceive 缓存到 stagedReceives 里面
                 * 3. 将 networkReceive 从 stagedReceives 取出，存入 completedReceives 中
                 * 4. 遍历 completedReceives 中的 networkReceive，
                 *    根据 networkReceive 初始化 ClientResponse，将 ClientResponse 存储到 responses 中
                 * 5. 调用请求中的回调函数处理响应
                 * 6. handleProduceResponse 真正处理网络响应
                 */
                handleProduceResponse(response, recordsByPartition, time.milliseconds());
            }
        };

        return new ClientRequest(now, acks != 0, send, callback);
    }

    /**
     * Wake up the selector associated with this send thread
     */
    public void wakeup() {
        this.client.wakeup();
    }

    /**
     * A collection of sensors for the sender
     */
    private class SenderMetrics {

        private final Metrics metrics;
        public final Sensor retrySensor;
        public final Sensor errorSensor;
        public final Sensor queueTimeSensor;
        public final Sensor requestTimeSensor;
        public final Sensor recordsPerRequestSensor;
        public final Sensor batchSizeSensor;
        public final Sensor compressionRateSensor;
        public final Sensor maxRecordSizeSensor;
        public final Sensor produceThrottleTimeSensor;

        public SenderMetrics(Metrics metrics) {
            this.metrics = metrics;
            String metricGrpName = "producer-metrics";

            this.batchSizeSensor = metrics.sensor("batch-size");
            MetricName m = metrics.metricName("batch-size-avg", metricGrpName, "The average number of bytes sent per partition per-request.");
            this.batchSizeSensor.add(m, new Avg());
            m = metrics.metricName("batch-size-max", metricGrpName, "The max number of bytes sent per partition per-request.");
            this.batchSizeSensor.add(m, new Max());

            this.compressionRateSensor = metrics.sensor("compression-rate");
            m = metrics.metricName("compression-rate-avg", metricGrpName, "The average compression rate of record batches.");
            this.compressionRateSensor.add(m, new Avg());

            this.queueTimeSensor = metrics.sensor("queue-time");
            m = metrics.metricName("record-queue-time-avg", metricGrpName, "The average time in ms record batches spent in the record accumulator.");
            this.queueTimeSensor.add(m, new Avg());
            m = metrics.metricName("record-queue-time-max", metricGrpName, "The maximum time in ms record batches spent in the record accumulator.");
            this.queueTimeSensor.add(m, new Max());

            this.requestTimeSensor = metrics.sensor("request-time");
            m = metrics.metricName("request-latency-avg", metricGrpName, "The average request latency in ms");
            this.requestTimeSensor.add(m, new Avg());
            m = metrics.metricName("request-latency-max", metricGrpName, "The maximum request latency in ms");
            this.requestTimeSensor.add(m, new Max());

            this.produceThrottleTimeSensor = metrics.sensor("produce-throttle-time");
            m = metrics.metricName("produce-throttle-time-avg", metricGrpName, "The average throttle time in ms");
            this.produceThrottleTimeSensor.add(m, new Avg());
            m = metrics.metricName("produce-throttle-time-max", metricGrpName, "The maximum throttle time in ms");
            this.produceThrottleTimeSensor.add(m, new Max());

            this.recordsPerRequestSensor = metrics.sensor("records-per-request");
            m = metrics.metricName("record-send-rate", metricGrpName, "The average number of records sent per second.");
            this.recordsPerRequestSensor.add(m, new Rate());
            m = metrics.metricName("records-per-request-avg", metricGrpName, "The average number of records per request.");
            this.recordsPerRequestSensor.add(m, new Avg());

            this.retrySensor = metrics.sensor("record-retries");
            m = metrics.metricName("record-retry-rate", metricGrpName, "The average per-second number of retried record sends");
            this.retrySensor.add(m, new Rate());

            this.errorSensor = metrics.sensor("errors");
            m = metrics.metricName("record-error-rate", metricGrpName, "The average per-second number of record sends that resulted in errors");
            this.errorSensor.add(m, new Rate());

            this.maxRecordSizeSensor = metrics.sensor("record-size-max");
            m = metrics.metricName("record-size-max", metricGrpName, "The maximum record size");
            this.maxRecordSizeSensor.add(m, new Max());
            m = metrics.metricName("record-size-avg", metricGrpName, "The average record size");
            this.maxRecordSizeSensor.add(m, new Avg());

            m = metrics.metricName("requests-in-flight", metricGrpName, "The current number of in-flight requests awaiting a response.");
            this.metrics.addMetric(m, new Measurable() {
                public double measure(MetricConfig config, long now) {
                    return client.inFlightRequestCount();
                }
            });
            m = metrics.metricName("metadata-age", metricGrpName, "The age in seconds of the current producer metadata being used.");
            metrics.addMetric(m, new Measurable() {
                public double measure(MetricConfig config, long now) {
                    return (now - metadata.lastSuccessfulUpdate()) / 1000.0;
                }
            });
        }

        private void maybeRegisterTopicMetrics(String topic) {
            // if one sensor of the metrics has been registered for the topic,
            // then all other sensors should have been registered; and vice versa
            String topicRecordsCountName = "topic." + topic + ".records-per-batch";
            Sensor topicRecordCount = this.metrics.getSensor(topicRecordsCountName);
            if (topicRecordCount == null) {
                Map<String, String> metricTags = Collections.singletonMap("topic", topic);
                String metricGrpName = "producer-topic-metrics";

                topicRecordCount = this.metrics.sensor(topicRecordsCountName);
                MetricName m = this.metrics.metricName("record-send-rate", metricGrpName, metricTags);
                topicRecordCount.add(m, new Rate());

                String topicByteRateName = "topic." + topic + ".bytes";
                Sensor topicByteRate = this.metrics.sensor(topicByteRateName);
                m = this.metrics.metricName("byte-rate", metricGrpName, metricTags);
                topicByteRate.add(m, new Rate());

                String topicCompressionRateName = "topic." + topic + ".compression-rate";
                Sensor topicCompressionRate = this.metrics.sensor(topicCompressionRateName);
                m = this.metrics.metricName("compression-rate", metricGrpName, metricTags);
                topicCompressionRate.add(m, new Avg());

                String topicRetryName = "topic." + topic + ".record-retries";
                Sensor topicRetrySensor = this.metrics.sensor(topicRetryName);
                m = this.metrics.metricName("record-retry-rate", metricGrpName, metricTags);
                topicRetrySensor.add(m, new Rate());

                String topicErrorName = "topic." + topic + ".record-errors";
                Sensor topicErrorSensor = this.metrics.sensor(topicErrorName);
                m = this.metrics.metricName("record-error-rate", metricGrpName, metricTags);
                topicErrorSensor.add(m, new Rate());
            }
        }

        public void updateProduceRequestMetrics(Map<Integer, List<RecordBatch>> batches) {
            long now = time.milliseconds();
            for (List<RecordBatch> nodeBatch : batches.values()) {
                int records = 0;
                for (RecordBatch batch : nodeBatch) {
                    // register all per-topic metrics at once
                    String topic = batch.topicPartition.topic();
                    maybeRegisterTopicMetrics(topic);

                    // per-topic record send rate
                    String topicRecordsCountName = "topic." + topic + ".records-per-batch";
                    Sensor topicRecordCount = Utils.notNull(this.metrics.getSensor(topicRecordsCountName));
                    topicRecordCount.record(batch.recordCount);

                    // per-topic bytes send rate
                    String topicByteRateName = "topic." + topic + ".bytes";
                    Sensor topicByteRate = Utils.notNull(this.metrics.getSensor(topicByteRateName));
                    topicByteRate.record(batch.records.sizeInBytes());

                    // per-topic compression rate
                    String topicCompressionRateName = "topic." + topic + ".compression-rate";
                    Sensor topicCompressionRate = Utils.notNull(this.metrics.getSensor(topicCompressionRateName));
                    topicCompressionRate.record(batch.records.compressionRate());

                    // global metrics
                    this.batchSizeSensor.record(batch.records.sizeInBytes(), now);
                    this.queueTimeSensor.record(batch.drainedMs - batch.createdMs, now);
                    this.compressionRateSensor.record(batch.records.compressionRate());
                    this.maxRecordSizeSensor.record(batch.maxRecordSize, now);
                    records += batch.recordCount;
                }
                this.recordsPerRequestSensor.record(records, now);
            }
        }

        public void recordRetries(String topic, int count) {
            long now = time.milliseconds();
            this.retrySensor.record(count, now);
            String topicRetryName = "topic." + topic + ".record-retries";
            Sensor topicRetrySensor = this.metrics.getSensor(topicRetryName);
            if (topicRetrySensor != null)
                topicRetrySensor.record(count, now);
        }

        public void recordErrors(String topic, int count) {
            long now = time.milliseconds();
            this.errorSensor.record(count, now);
            String topicErrorName = "topic." + topic + ".record-errors";
            Sensor topicErrorSensor = this.metrics.getSensor(topicErrorName);
            if (topicErrorSensor != null)
                topicErrorSensor.record(count, now);
        }

        public void recordLatency(String node, long latency) {
            long now = time.milliseconds();
            this.requestTimeSensor.record(latency, now);
            if (!node.isEmpty()) {
                String nodeTimeName = "node-" + node + ".latency";
                Sensor nodeRequestTime = this.metrics.getSensor(nodeTimeName);
                if (nodeRequestTime != null)
                    nodeRequestTime.record(latency, now);
            }
        }

        public void recordThrottleTime(String node, long throttleTimeMs) {
            this.produceThrottleTimeSensor.record(throttleTimeMs, time.milliseconds());
        }

    }

}
