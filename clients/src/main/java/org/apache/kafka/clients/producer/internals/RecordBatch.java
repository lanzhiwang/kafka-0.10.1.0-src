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

import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A batch of records that is or will be sent.
 * 
 * This class is not thread safe and external synchronization must be used when modifying it
 */
public final class RecordBatch {

    private static final Logger log = LoggerFactory.getLogger(RecordBatch.class);

    public int recordCount = 0;
    public int maxRecordSize = 0;
    public volatile int attempts = 0;
    public final long createdMs;
    public long drainedMs;
    public long lastAttemptMs;
    public final MemoryRecords records;
    public final TopicPartition topicPartition;
    public final ProduceRequestResult produceFuture;
    public long lastAppendTime;
    private final List<Thunk> thunks;
    private long offsetCounter = 0L;
    private boolean retry;

    public RecordBatch(TopicPartition tp, MemoryRecords records, long now) {
        this.createdMs = now;
        this.lastAttemptMs = now;
        this.records = records;
        this.topicPartition = tp;
        this.produceFuture = new ProduceRequestResult();
        this.thunks = new ArrayList<Thunk>();
        this.lastAppendTime = createdMs;
        this.retry = false;
    }

    /**
     * Append the record to the current record set and return the relative offset within that record set
     * 
     * @return The RecordSend corresponding to this record or null if there isn't sufficient room.
     */
    // batch.tryAppend(timestamp, key, value, callback, time.milliseconds())
    public FutureRecordMetadata tryAppend(long timestamp, byte[] key, byte[] value, Callback callback, long now) {
        if (!this.records.hasRoomFor(key, value)) {
            return null;
        } else {
            long checksum = this.records.append(offsetCounter++, timestamp, key, value);
            this.maxRecordSize = Math.max(this.maxRecordSize, Record.recordSize(key, value));
            this.lastAppendTime = now;
            FutureRecordMetadata future = new FutureRecordMetadata(this.produceFuture, this.recordCount,
                                                                   timestamp, checksum,
                                                                   key == null ? -1 : key.length,
                                                                   value == null ? -1 : value.length);
            if (callback != null)
                thunks.add(new Thunk(callback, future));
            this.recordCount++;
            return future;
        }
    }

    /**
     * Complete the request
     * 
     * @param baseOffset The base offset of the messages assigned by the server
     * @param timestamp The timestamp returned by the broker.
     * @param exception The exception that occurred (or null if the request was successful)
     */
    public void done(long baseOffset, long timestamp, RuntimeException exception) {
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
         * 9. 执行用户定义的回调函数
         */
        log.trace("Produced messages to topic-partition {} with base offset offset {} and error: {}.",
                  topicPartition,
                  baseOffset,
                  exception);
        /**
         * final private static class Thunk {}
         * this.thunks = new ArrayList<Thunk>();
         * Thunk 表示一条用于封装的消息
         */
        // execute callbacks
        for (int i = 0; i < this.thunks.size(); i++) {
            try {
                Thunk thunk = this.thunks.get(i);
                if (exception == null) {
                    // If the timestamp returned by server is NoTimestamp, that means CreateTime is used. Otherwise LogAppendTime is used.
                    RecordMetadata metadata = new RecordMetadata(this.topicPartition,  baseOffset, thunk.future.relativeOffset(),
                                                                 timestamp == Record.NO_TIMESTAMP ? thunk.future.timestamp() : timestamp,
                                                                 thunk.future.checksum(),
                                                                 thunk.future.serializedKeySize(),
                                                                 thunk.future.serializedValueSize());
                    thunk.callback.onCompletion(metadata, null);
                } else {
                    thunk.callback.onCompletion(null, exception);
                }
            } catch (Exception e) {
                log.error("Error executing user-provided callback on message for topic-partition {}:", topicPartition, e);
            }
        }
        this.produceFuture.done(topicPartition, baseOffset, exception);
    }

    /**
     * A callback and the associated FutureRecordMetadata argument to pass to it.
     */
    final private static class Thunk {
        final Callback callback;
        final FutureRecordMetadata future;

        public Thunk(Callback callback, FutureRecordMetadata future) {
            this.callback = callback;
            this.future = future;
        }
    }

    @Override
    public String toString() {
        return "RecordBatch(topicPartition=" + topicPartition + ", recordCount=" + recordCount + ")";
    }

    /**
     * A batch whose metadata is not available should be expired if one of the following is true:
     * <ol>
     *     <li> the batch is not in retry AND request timeout has elapsed after it is ready (full or linger.ms has reached).
     *     <li> the batch is in retry AND request timeout has elapsed after the backoff period ended.
     * </ol>
     */
    public boolean maybeExpire(int requestTimeoutMs, long retryBackoffMs, long now, long lingerMs, boolean isFull) {
        boolean expire = false;
        String errorMessage = null;

        /**
         * this.inRetry() 返回当前 batch 是否正在重试
         *
         * requestTimeoutMs
         * timeout.ms 默认值：30 * 1000
         * request.timeout.ms 默认值：30 * 1000
         *
         * now - this.lastAppendTime 当前时间 - 最后入队时间
         */
        if (!this.inRetry() && isFull && requestTimeoutMs < (now - this.lastAppendTime)) {
            expire = true;
            errorMessage = (now - this.lastAppendTime) + " ms has passed since last append";
        /**
         * this.createdMs 当前 batch 的初始化时间，也就是创建时间
         * lingerMs 消息发送的时候，如果一直凑不齐一个 batch，也会限定一个时间，
         *          要求在这个限定时间之内即使不满足一个 batch，也要把这个 batch 发送出去
         */
        } else if (!this.inRetry() && requestTimeoutMs < (now - (this.createdMs + lingerMs))) {
            expire = true;
            errorMessage = (now - (this.createdMs + lingerMs)) + " ms has passed since batch creation plus linger time";
        /**
         * this.lastAttemptMs 上一次重试的时间
         * retryBackoffMs 表示生产者生产消息的重试时间间隔
         */
        } else if (this.inRetry() && requestTimeoutMs < (now - (this.lastAttemptMs + retryBackoffMs))) {
            expire = true;
            errorMessage = (now - (this.lastAttemptMs + retryBackoffMs)) + " ms has passed since last attempt plus backoff time";
        }

        if (expire) {
            // 如果超时
            this.records.close();
            /**
             * TimeoutException 异常
             */
            this.done(-1L, Record.NO_TIMESTAMP,
                      new TimeoutException("Expiring " + recordCount + " record(s) for " + topicPartition + " due to " + errorMessage));
        }

        return expire;
    }

    /**
     * Returns if the batch is been retried for sending to kafka
     */
    public boolean inRetry() {
        return this.retry;
    }

    /**
     * Set retry to true if the batch is being retried (for send)
     */
    public void setRetry() {
        this.retry = true;
    }
}
