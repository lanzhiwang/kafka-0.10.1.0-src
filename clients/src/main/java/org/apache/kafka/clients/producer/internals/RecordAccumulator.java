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

import java.util.Iterator;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.metrics.Measurable;
import org.apache.kafka.common.metrics.MetricConfig;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.stats.Rate;
import org.apache.kafka.common.record.CompressionType;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.Record;
import org.apache.kafka.common.record.Records;
import org.apache.kafka.common.utils.CopyOnWriteMap;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class acts as a queue that accumulates records into {@link org.apache.kafka.common.record.MemoryRecords}
 * instances to be sent to the server.
 * <p>
 * The accumulator uses a bounded amount of memory and append calls will block when that memory is exhausted, unless
 * this behavior is explicitly disabled.
 */
public final class RecordAccumulator {

    private static final Logger log = LoggerFactory.getLogger(RecordAccumulator.class);

    private volatile boolean closed;
    private final AtomicInteger flushesInProgress;
    private final AtomicInteger appendsInProgress;
    private final int batchSize;
    private final CompressionType compression;
    private final long lingerMs;
    private final long retryBackoffMs;
    private final BufferPool free;
    private final Time time;
    private final ConcurrentMap<TopicPartition, Deque<RecordBatch>> batches;
    private final IncompleteRecordBatches incomplete;
    // The following variables are only accessed by the sender thread, so we don't need to protect them.
    private final Set<TopicPartition> muted;
    private int drainIndex;

    /**
     * Create a new record accumulator
     * 
     * @param batchSize The size to use when allocating {@link org.apache.kafka.common.record.MemoryRecords} instances
     * @param totalSize The maximum memory the record accumulator can use.
     * @param compression The compression codec for the records
     * @param lingerMs An artificial delay time to add before declaring a records instance that isn't full ready for
     *        sending. This allows time for more records to arrive. Setting a non-zero lingerMs will trade off some
     *        latency for potentially better throughput due to more batching (and hence fewer, larger requests).
     * @param retryBackoffMs An artificial delay time to retry the produce request upon receiving an error. This avoids
     *        exhausting all retries in a short period of time.
     * @param metrics The metrics
     * @param time The time instance to use
     */
    /**
     * TODO 生产者客户端缓存组件
     *
     * BATCH_SIZE_CONFIG
     * batch.size
     * 默认值：16384 = 16 KB
     *
     * totalMemorySize
     * buffer.memory
     * 生产者 RecordAccumulator 缓存的大小
     * 默认值是：32 * 1024 * 1024L
     *
     * compressionType
     * compression.type
     * 压缩类型
     * 默认不压缩
     *
     * LINGER_MS_CONFIG
     * linger.ms
     * 默认值：0
     *
     * retryBackoffMs
     * retry.backoff.ms
     * 生产者生产消息的重试时间间隔
     * 默认值是 100L
     */
    public RecordAccumulator(int batchSize,
                             long totalSize,
                             CompressionType compression,
                             long lingerMs,
                             long retryBackoffMs,
                             Metrics metrics,
                             Time time) {
        this.drainIndex = 0;
        this.closed = false;
        this.flushesInProgress = new AtomicInteger(0);
        this.appendsInProgress = new AtomicInteger(0);
        this.batchSize = batchSize;
        this.compression = compression;
        this.lingerMs = lingerMs;
        this.retryBackoffMs = retryBackoffMs;
        this.batches = new CopyOnWriteMap<>();
        String metricGrpName = "producer-metrics";
        /**
         * 初始化内存池
         * totalSize 内存池的大小，默认是 32 MB
         * batchSize 批次大小，默认是 16 KB
         */
        this.free = new BufferPool(totalSize, batchSize, metrics, time, metricGrpName);
        this.incomplete = new IncompleteRecordBatches();
        this.muted = new HashSet<>();
        this.time = time;
        registerMetrics(metrics, metricGrpName);
    }

    private void registerMetrics(Metrics metrics, String metricGrpName) {
        MetricName metricName = metrics.metricName("waiting-threads", metricGrpName, "The number of user threads blocked waiting for buffer memory to enqueue their records");
        Measurable waitingThreads = new Measurable() {
            public double measure(MetricConfig config, long now) {
                return free.queued();
            }
        };
        metrics.addMetric(metricName, waitingThreads);

        metricName = metrics.metricName("buffer-total-bytes", metricGrpName, "The maximum amount of buffer memory the client can use (whether or not it is currently used).");
        Measurable totalBytes = new Measurable() {
            public double measure(MetricConfig config, long now) {
                return free.totalMemory();
            }
        };
        metrics.addMetric(metricName, totalBytes);

        metricName = metrics.metricName("buffer-available-bytes", metricGrpName, "The total amount of buffer memory that is not being used (either unallocated or in the free list).");
        Measurable availableBytes = new Measurable() {
            public double measure(MetricConfig config, long now) {
                return free.availableMemory();
            }
        };
        metrics.addMetric(metricName, availableBytes);

        Sensor bufferExhaustedRecordSensor = metrics.sensor("buffer-exhausted-records");
        metricName = metrics.metricName("buffer-exhausted-rate", metricGrpName, "The average per-second number of record sends that are dropped due to buffer exhaustion");
        bufferExhaustedRecordSensor.add(metricName, new Rate());
    }

    /**
     * Add a record to the accumulator, return the append result
     * <p>
     * The append result will contain the future metadata, and flag for whether the appended batch is full or a new batch is created
     * <p>
     *
     * @param tp The topic/partition to which this record is being sent
     * @param timestamp The timestamp of the record
     * @param key The key for the record
     * @param value The value for the record
     * @param callback The user-supplied callback to execute when the request is complete
     * @param maxTimeToBlock The maximum time in milliseconds to block for buffer memory to be available
     */
    // RecordAccumulator.RecordAppendResult result = accumulator.append(
    //     tp,
    //     timestamp,
    //     serializedKey,
    //     serializedValue,
    //     interceptCallback,
    //     remainingWaitMs
    // );
    // TopicPartition
    //     private final int partition;
    //     private final String topic;
    public RecordAppendResult append(TopicPartition tp,
                                     long timestamp,
                                     byte[] key,
                                     byte[] value,
                                     Callback callback,
                                     long maxTimeToBlock) throws InterruptedException {
        // We keep track of the number of appending thread to make sure we do not miss batches in
        // abortIncompleteBatches().
        appendsInProgress.incrementAndGet();
        try {
            /**
             * 步骤一：
             *      根据分区信息找到应该插入到哪个队列
             */
            // check if we have an in-progress batch
            Deque<RecordBatch> dq = getOrCreateDeque(tp);
            /**
             * 对象锁
             * 假设有 3 个线程，线程1，线程2，线程3 同时执行 append 操作，并且是往同一个 topic 的同一个 partition 内发送消息
             * 线程1 通过 getOrCreateDeque 创建 Deque
             * 线程2 通过 getOrCreateDeque 直接获取 Deque
             * 线程3 通过 getOrCreateDeque 直接获取 Deque
             * 因为是同一个 Deque 对象，并且有对象锁，所以下面的代码变成串行的
             */
            synchronized (dq) {
                // 首先是线程1 执行，获取锁
                if (closed)
                    throw new IllegalStateException("Cannot send after the producer is closed.");
                /**
                 * 步骤二：
                 *      尝试往队列里面的 batch 添加数据
                 *
                 * 一开始添加数据肯定是失败的，因为目前我们现在只有队列和消息
                 * 消息是需要封装在 batch 对象里面的，这个 batch 对象是需要分配内存的，目前还没有分配内存
                 */
                RecordAppendResult appendResult = tryAppend(timestamp, key, value, callback, dq);
                if (appendResult != null)
                    return appendResult;
            }
            // 线程1 释放锁，接着线程2 开始执行，获取锁，以此类推

            /**
             * 步骤三：
             *      计算批次的大小
             * 在消息的大小和批次大小之间选择一个最大值作为最终批次的大小
             * 因为可能一条消息的大小比默认的批次的大小 16 KB 还要大
             *
             * 如果消息大小总是比批次大小还要大，那么每个批次里面只有一条消息
             * 也就是消息的一条一条发送的，那么批次的设计就没有意义了，因此要合理设计批次的大小
             */
            // we don't have an in-progress record batch try to allocate a new batch
            int size = Math.max(this.batchSize, Records.LOG_OVERHEAD + Record.recordSize(key, value));
            log.trace("Allocating a new {} byte message buffer for topic {} partition {}", size, tp.topic(), tp.partition());
            /**
             * 步骤四：
             *      从 BufferPool 申请一个批次大小的内存
             * 线程1，线程2，线程3 都会申请内存，假设线程1，线程2，线程3 都申请了 16KB 的内存
             */
            ByteBuffer buffer = free.allocate(size, maxTimeToBlock);
            synchronized (dq) {
                // 还是有个线程会获取锁，执行下面的内容，假设线程1 获取到锁
                // Need to check if producer is closed again after grabbing the dequeue lock.
                if (closed)
                    throw new IllegalStateException("Cannot send after the producer is closed.");

                /**
                 * 步骤五：
                 *      再次尝试将消息写入 batch 里面
                 * 此时写入 batch 操作还是失败的，因为此时只有分配的内存，但是还没有实例化 batch 对象
                 *
                 * 线程2 往 batch 里面写入消息的操作是成功的
                 * 之前线程2 也申请了内存，但是这是线程2 是往线程1 申请的内存里面写入数据，因此线程2 自己申请的内存就需要释放回收
                 */
                RecordAppendResult appendResult = tryAppend(timestamp, key, value, callback, dq);
                if (appendResult != null) {
                    // 释放内存
                    // Somebody else found us a batch, return the one we waited for! Hopefully this doesn't happen often...
                    free.deallocate(buffer);
                    return appendResult;
                }
                /**
                 * 步骤六：
                 *      根据内存大小实例化 batch
                 *
                 * 线程1 到这里之后会根据 buffer 封装出一个 batch
                 */
                MemoryRecords records = MemoryRecords.emptyRecords(buffer, compression, this.batchSize);
                RecordBatch batch = new RecordBatch(tp, records, time.milliseconds());
                /**
                 * 第三次尝试往 batch 里面写入消息，这是写入操作会成功
                 * 线程1 写入操作会成功
                 */
                FutureRecordMetadata future = Utils.notNull(batch.tryAppend(timestamp, key, value, callback, time.milliseconds()));

                /**
                 * 步骤七：
                 *      把这个 batch 放入到队列的队尾
                 */
                dq.addLast(batch);
                incomplete.add(batch);
                return new RecordAppendResult(future, dq.size() > 1 || batch.records.isFull(), true);
            }
            // 线程1 释放锁，线程2 获取锁
        } finally {
            appendsInProgress.decrementAndGet();
        }
    }

    /**
     * If `RecordBatch.tryAppend` fails (i.e. the record batch is full), close its memory records to release temporary
     * resources (like compression streams buffers).
     */
    // tryAppend(timestamp, key, value, callback, dq)
    private RecordAppendResult tryAppend(long timestamp, byte[] key, byte[] value, Callback callback, Deque<RecordBatch> deque) {
        RecordBatch last = deque.peekLast();
        if (last != null) {
            FutureRecordMetadata future = last.tryAppend(timestamp, key, value, callback, time.milliseconds());
            if (future == null)
                last.records.close();
            else
                return new RecordAppendResult(future, deque.size() > 1 || last.records.isFull(), false);
        }
        return null;
    }

    /**
     * Abort the batches that have been sitting in RecordAccumulator for more than the configured requestTimeout
     * due to metadata being unavailable
     */
    public List<RecordBatch> abortExpiredBatches(int requestTimeout, long now) {
        List<RecordBatch> expiredBatches = new ArrayList<>();
        int count = 0;
        for (Map.Entry<TopicPartition, Deque<RecordBatch>> entry : this.batches.entrySet()) {
            Deque<RecordBatch> dq = entry.getValue();
            TopicPartition tp = entry.getKey();
            // We only check if the batch should be expired if the partition does not have a batch in flight.
            // This is to prevent later batches from being expired while an earlier batch is still in progress.
            // Note that `muted` is only ever populated if `max.in.flight.request.per.connection=1` so this protection
            // is only active in this case. Otherwise the expiration order is not guaranteed.
            if (!muted.contains(tp)) {
                synchronized (dq) {
                    // iterate over the batches and expire them if they have been in the accumulator for more than requestTimeOut
                    RecordBatch lastBatch = dq.peekLast();
                    Iterator<RecordBatch> batchIterator = dq.iterator();
                    while (batchIterator.hasNext()) {
                        RecordBatch batch = batchIterator.next();
                        boolean isFull = batch != lastBatch || batch.records.isFull();
                        /**
                         * 判断是否超时
                         */
                        // check if the batch is expired
                        if (batch.maybeExpire(requestTimeout, retryBackoffMs, now, this.lingerMs, isFull)) {
                            expiredBatches.add(batch);
                            count++;
                            batchIterator.remove();
                            /**
                             * 超时之后释放内存池中的内存
                             */
                            deallocate(batch);
                        } else {
                            // Stop at the first batch that has not expired.
                            break;
                        }
                    }
                }
            }
        }
        if (!expiredBatches.isEmpty())
            log.trace("Expired {} batches in accumulator", count);

        return expiredBatches;
    }

    /**
     * Re-enqueue the given record batch in the accumulator to retry
     */
    public void reenqueue(RecordBatch batch, long now) {
        /**
         * batch 发送到服务端后，服务端返回异常，该 batch 进行重试
         * batch.attempts 表示已经重试的次数，加一
         */
        batch.attempts++;
        /**
         * batch 上一次重试的时间
         */
        batch.lastAttemptMs = now;
        /**
         * batch 上一次入队的时间
         */
        batch.lastAppendTime = now;
        batch.setRetry();
        Deque<RecordBatch> deque = getOrCreateDeque(batch.topicPartition);
        synchronized (deque) {
            /**
             * 重新入队，并且放入对头，优先处理
             */
            deque.addFirst(batch);
        }
    }

    /**
     * Get a list of nodes whose partitions are ready to be sent, and the earliest time at which any non-sendable
     * partition will be ready; Also return the flag for whether there are any unknown leaders for the accumulated
     * partition batches.
     * <p>
     * A destination node is ready to send data if:
     * <ol>
     * <li>There is at least one partition that is not backing off its send
     * <li><b>and</b> those partitions are not muted (to prevent reordering if
     *   {@value org.apache.kafka.clients.producer.ProducerConfig#MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION}
     *   is set to one)</li>
     * <li><b>and <i>any</i></b> of the following are true</li>
     * <ul>
     *     <li>The record set is full</li>
     *     <li>The record set has sat in the accumulator for at least lingerMs milliseconds</li>
     *     <li>The accumulator is out of memory and threads are blocking waiting for data (in this case all partitions
     *     are immediately considered ready).</li>
     *     <li>The accumulator has been closed</li>
     * </ul>
     * </ol>
     */
    public ReadyCheckResult ready(Cluster cluster, long nowMs) {
        Set<Node> readyNodes = new HashSet<>();
        long nextReadyCheckDelayMs = Long.MAX_VALUE;
        Set<String> unknownLeaderTopics = new HashSet<>();

        /**
         * this.free.queued() 返回等待队列中线程的数量(The number of threads blocked waiting on memory)
         * 如果数量大于 0，说明内存池中的内存不足
         * exhausted = true 表示内存池中的内存不足，队列中的 batch 要发送出去回收内存，无论是否超时，无论 batch 是否写满
         */
        boolean exhausted = this.free.queued() > 0;
        /**
         * 遍历所有的分区
         */
        for (Map.Entry<TopicPartition, Deque<RecordBatch>> entry : this.batches.entrySet()) {
            TopicPartition part = entry.getKey();
            Deque<RecordBatch> deque = entry.getValue();

            /**
             * 通过元数据信息和分区信息找到 leader partition 所在的 Node 节点
             */
            Node leader = cluster.leaderFor(part);
            synchronized (deque) {
                if (leader == null && !deque.isEmpty()) {
                    /**
                     * 将找不到 leader partition 的 topic 统一记录下来，下次拉取元数据时要拉取相应 topic 的元数据
                     */
                    // This is a partition for which leader is not known, but messages are available to send.
                    // Note that entries are currently not removed from batches when deque is empty.
                    unknownLeaderTopics.add(part.topic());
                } else if (!readyNodes.contains(leader) && !muted.contains(part)) {
                    RecordBatch batch = deque.peekFirst();
                    if (batch != null) {
                        /**
                         * batch.attempts 表示已经重试的次数，如果是第一次发送，那么已经重试的次数就是 0
                         * batch.lastAttemptMs 表示上一次重试的时间
                         * retryBackoffMs 表示生产者生产消息的重试时间间隔
                         *
                         * backingOff = true 表示重新发送 batch 的时间到了，也就是可以重新发送 batch 了
                         */
                        boolean backingOff = batch.attempts > 0 && batch.lastAttemptMs + retryBackoffMs > nowMs;
                        /**
                         * nowMs 表示当前时间
                         * batch.lastAttemptMs 表示上一次重试的时间
                         *
                         * waitedTimeMs 表示这个 batch 自从上次发送到现在已经等待多久了
                         */
                        long waitedTimeMs = nowMs - batch.lastAttemptMs;
                        /**
                         * retryBackoffMs 表示生产者生产消息的重试时间间隔
                         * lingerMs 消息发送的时候，如果一直凑不齐一个 batch，也会限定一个时间，
                         *          要求在这个限定时间之内即使不满足一个 batch，也要把这个 batch 发送出去
                         *
                         * timeToWaitMs 表示消息最多等待多久之后就一定要发送出去了
                         */
                        long timeToWaitMs = backingOff ? retryBackoffMs : lingerMs;
                        /**
                         * timeToWaitMs 表示消息最多等待多久之后就一定要发送出去了
                         * waitedTimeMs 表示这个 batch 自从上次发送到现在已经等待多久了
                         *
                         * timeLeftMs 表示还可以等待多久
                         */
                        long timeLeftMs = Math.max(timeToWaitMs - waitedTimeMs, 0);
                        /**
                         * deque.size() 表示队列中的 batch 数量
                         * deque.size() > 1 表示队列中至少有两个 batch，并且第一个 batch 已经写满了，不然不会创建第二个 batch
                         *                  如果 batch 写满了，就一定可以被发送了
                         *
                         * batch.records.isFull() 表示队列中只有一个 batch，并且这个 batch 刚好也写满了，也是可以发送的了
                         *
                         * full 表示队列中有准备好的 batch 可以发送了
                         */
                        boolean full = deque.size() > 1 || batch.records.isFull();
                        /**
                         * waitedTimeMs 表示这个 batch 自从上次发送到现在已经等待多久了
                         * timeToWaitMs 表示消息最多等待多久之后就一定要发送出去了
                         * expired = waitedTimeMs >= timeToWaitMs 说明已经等待超时了，batch 可以发送了
                         */
                        boolean expired = waitedTimeMs >= timeToWaitMs;
                        /**
                         * full 表示队列中有准备好的 batch 可以发送了，无论是否超时
                         * expired 表示已经等待超时了，batch 可以发送了，无论是否写满
                         * exhausted = true 表示内存池中的内存不足，队列中的 batch 要发送出去回收内存，无论是否超时，无论 batch 是否写满
                         * closed 和 flushInProgress() 表示停止 sender 线程之前，必须把缓存在队列中的 batch 发送出去
                         */
                        boolean sendable = full || expired || exhausted || closed || flushInProgress();
                        if (sendable && !backingOff) {
                            /**
                             * 将可以发送的 partition 的 leader partition 所在的 Node 加入到 readyNodes 里面
                             */
                            readyNodes.add(leader);
                        } else {
                            // Note that this results in a conservative estimate since an un-sendable partition may have
                            // a leader that will later be found to have sendable data. However, this is good enough
                            // since we'll just wake up and then sleep again for the remaining time.
                            nextReadyCheckDelayMs = Math.min(timeLeftMs, nextReadyCheckDelayMs);
                        }
                    }
                }
            }
        }

        return new ReadyCheckResult(readyNodes, nextReadyCheckDelayMs, unknownLeaderTopics);
    }

    /**
     * @return Whether there is any unsent record in the accumulator.
     */
    public boolean hasUnsent() {
        for (Map.Entry<TopicPartition, Deque<RecordBatch>> entry : this.batches.entrySet()) {
            Deque<RecordBatch> deque = entry.getValue();
            synchronized (deque) {
                if (!deque.isEmpty())
                    return true;
            }
        }
        return false;
    }

    /**
     * Drain all the data for the given nodes and collate them into a list of batches that will fit within the specified
     * size on a per-node basis. This method attempts to avoid choosing the same topic-node over and over.
     * 
     * @param cluster The current cluster metadata
     * @param nodes The list of node to drain
     * @param maxSize The maximum number of bytes to drain
     * @param now The current unix time in milliseconds
     * @return A list of {@link RecordBatch} for each node specified with total size less than the requested maxSize.
     */
    public Map<Integer, List<RecordBatch>> drain(Cluster cluster,
                                                 Set<Node> nodes,
                                                 int maxSize,
                                                 long now) {
        if (nodes.isEmpty())
            return Collections.emptyMap();

        Map<Integer, List<RecordBatch>> batches = new HashMap<>();
        for (Node node : nodes) {
            int size = 0;
            List<PartitionInfo> parts = cluster.partitionsForNode(node.id());
            List<RecordBatch> ready = new ArrayList<>();
            /* to make starvation less likely this loop doesn't start at 0 */
            int start = drainIndex = drainIndex % parts.size();
            do {
                PartitionInfo part = parts.get(drainIndex);
                TopicPartition tp = new TopicPartition(part.topic(), part.partition());
                // Only proceed if the partition has no in-flight batches.
                if (!muted.contains(tp)) {
                    Deque<RecordBatch> deque = getDeque(new TopicPartition(part.topic(), part.partition()));
                    if (deque != null) {
                        synchronized (deque) {
                            RecordBatch first = deque.peekFirst();
                            if (first != null) {
                                boolean backoff = first.attempts > 0 && first.lastAttemptMs + retryBackoffMs > now;
                                // Only drain the batch if it is not during backoff period.
                                if (!backoff) {
                                    if (size + first.records.sizeInBytes() > maxSize && !ready.isEmpty()) {
                                        // there is a rare case that a single batch size is larger than the request size due
                                        // to compression; in this case we will still eventually send this batch in a single
                                        // request
                                        break;
                                    } else {
                                        RecordBatch batch = deque.pollFirst();
                                        batch.records.close();
                                        size += batch.records.sizeInBytes();
                                        ready.add(batch);
                                        batch.drainedMs = now;
                                    }
                                }
                            }
                        }
                    }
                }
                this.drainIndex = (this.drainIndex + 1) % parts.size();
            } while (start != drainIndex);
            batches.put(node.id(), ready);
        }
        return batches;
    }

    private Deque<RecordBatch> getDeque(TopicPartition tp) {
        return batches.get(tp);
    }

    /**
     * Get the deque for the given topic-partition, creating it if necessary.
     */
    private Deque<RecordBatch> getOrCreateDeque(TopicPartition tp) {
        /**
         * this.batches = new CopyOnWriteMap<>();
         * 从 batches 这个 map 里面获取队列
         */
        Deque<RecordBatch> d = this.batches.get(tp);
        if (d != null)
            return d;
        /**
         * 如果从 map 里面没有获取到队列，那么初始化一个队列
         */
        d = new ArrayDeque<>();
        /**
         * 将初始化的队列放入 map 里面
         */
        Deque<RecordBatch> previous = this.batches.putIfAbsent(tp, d);
        if (previous == null)
            return d;
        else
            return previous;
    }

    /**
     * Deallocate the record batch
     */
    public void deallocate(RecordBatch batch) {
        incomplete.remove(batch);
        free.deallocate(batch.records.buffer(), batch.records.initialCapacity());
    }
    
    /**
     * Are there any threads currently waiting on a flush?
     *
     * package private for test
     */
    boolean flushInProgress() {
        return flushesInProgress.get() > 0;
    }

    /* Visible for testing */
    Map<TopicPartition, Deque<RecordBatch>> batches() {
        return Collections.unmodifiableMap(batches);
    }
    
    /**
     * Initiate the flushing of data from the accumulator...this makes all requests immediately ready
     */
    public void beginFlush() {
        this.flushesInProgress.getAndIncrement();
    }

    /**
     * Are there any threads currently appending messages?
     */
    private boolean appendsInProgress() {
        return appendsInProgress.get() > 0;
    }

    /**
     * Mark all partitions as ready to send and block until the send is complete
     */
    public void awaitFlushCompletion() throws InterruptedException {
        try {
            for (RecordBatch batch : this.incomplete.all())
                batch.produceFuture.await();
        } finally {
            this.flushesInProgress.decrementAndGet();
        }
    }

    /**
     * This function is only called when sender is closed forcefully. It will fail all the
     * incomplete batches and return.
     */
    public void abortIncompleteBatches() {
        // We need to keep aborting the incomplete batch until no thread is trying to append to
        // 1. Avoid losing batches.
        // 2. Free up memory in case appending threads are blocked on buffer full.
        // This is a tight loop but should be able to get through very quickly.
        do {
            abortBatches();
        } while (appendsInProgress());
        // After this point, no thread will append any messages because they will see the close
        // flag set. We need to do the last abort after no thread was appending in case there was a new
        // batch appended by the last appending thread.
        abortBatches();
        this.batches.clear();
    }

    /**
     * Go through incomplete batches and abort them.
     */
    private void abortBatches() {
        for (RecordBatch batch : incomplete.all()) {
            Deque<RecordBatch> dq = getDeque(batch.topicPartition);
            // Close the batch before aborting
            synchronized (dq) {
                batch.records.close();
                dq.remove(batch);
            }
            batch.done(-1L, Record.NO_TIMESTAMP, new IllegalStateException("Producer is closed forcefully."));
            deallocate(batch);
        }
    }

    public void mutePartition(TopicPartition tp) {
        muted.add(tp);
    }

    public void unmutePartition(TopicPartition tp) {
        muted.remove(tp);
    }

    /**
     * Close this accumulator and force all the record buffers to be drained
     */
    public void close() {
        this.closed = true;
    }

    /*
     * Metadata about a record just appended to the record accumulator
     */
    public final static class RecordAppendResult {
        public final FutureRecordMetadata future;
        public final boolean batchIsFull;
        public final boolean newBatchCreated;

        public RecordAppendResult(FutureRecordMetadata future, boolean batchIsFull, boolean newBatchCreated) {
            this.future = future;
            this.batchIsFull = batchIsFull;
            this.newBatchCreated = newBatchCreated;
        }
    }

    /*
     * The set of nodes that have at least one complete record batch in the accumulator
     */
    public final static class ReadyCheckResult {
        public final Set<Node> readyNodes;
        public final long nextReadyCheckDelayMs;
        public final Set<String> unknownLeaderTopics;

        public ReadyCheckResult(Set<Node> readyNodes, long nextReadyCheckDelayMs, Set<String> unknownLeaderTopics) {
            this.readyNodes = readyNodes;
            this.nextReadyCheckDelayMs = nextReadyCheckDelayMs;
            this.unknownLeaderTopics = unknownLeaderTopics;
        }
    }
    
    /*
     * A threadsafe helper class to hold RecordBatches that haven't been ack'd yet
     */
    private final static class IncompleteRecordBatches {
        private final Set<RecordBatch> incomplete;

        public IncompleteRecordBatches() {
            this.incomplete = new HashSet<RecordBatch>();
        }
        
        public void add(RecordBatch batch) {
            synchronized (incomplete) {
                this.incomplete.add(batch);
            }
        }
        
        public void remove(RecordBatch batch) {
            synchronized (incomplete) {
                boolean removed = this.incomplete.remove(batch);
                if (!removed)
                    throw new IllegalStateException("Remove from the incomplete set failed. This should be impossible.");
            }
        }
        
        public Iterable<RecordBatch> all() {
            synchronized (incomplete) {
                return new ArrayList<>(this.incomplete);
            }
        }
    }

}
