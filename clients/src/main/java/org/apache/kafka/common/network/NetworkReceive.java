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
package org.apache.kafka.common.network;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.ScatteringByteChannel;

/**
 * A size delimited Receive that consists of a 4 byte network-ordered size N followed by N bytes of content
 */
public class NetworkReceive implements Receive {

    public final static String UNKNOWN_SOURCE = "";
    public final static int UNLIMITED = -1;

    private final String source;
    private final ByteBuffer size;
    private final int maxSize;
    private ByteBuffer buffer;


    public NetworkReceive(String source, ByteBuffer buffer) {
        this.source = source;
        this.buffer = buffer;
        this.size = null;
        this.maxSize = UNLIMITED;
    }

    public NetworkReceive(String source) {
        this.source = source;
        this.size = ByteBuffer.allocate(4);
        this.buffer = null;
        this.maxSize = UNLIMITED;
    }

    public NetworkReceive(int maxSize, String source) {
        this.source = source;
        this.size = ByteBuffer.allocate(4);
        this.buffer = null;
        this.maxSize = maxSize;
    }

    public NetworkReceive() {
        this(UNKNOWN_SOURCE);
    }

    @Override
    public String source() {
        return source;
    }

    @Override
    public boolean complete() {
        return !size.hasRemaining() && !buffer.hasRemaining();
    }

    public long readFrom(ScatteringByteChannel channel) throws IOException {
        return readFromReadableChannel(channel);
    }

    // Need a method to read from ReadableByteChannel because BlockingChannel requires read with timeout
    // See: http://stackoverflow.com/questions/2866557/timeout-for-socketchannel-doesnt-work
    // This can go away after we get rid of BlockingChannel
    @Deprecated
    public long readFromReadableChannel(ReadableByteChannel channel) throws IOException {
        /**
         * 处理粘包和拆包问题
         *
         * 粘包问题解决方法
         * [50][body1][60][body2]
         * 4 + 50 + 4 + 60
         *
         * 拆包问题解决方法
         * 4 字节的 50 不是从同一个网络包中返回的，类似 5 0
         * 同理 50 字节的 body1 也不是从同一个网络包中返回的，类型 bo dy1
         */
        int read = 0;
        /**
         * this.size = ByteBuffer.allocate(4); size 是大小 4 byte，也就是一个 int 类型的大小
         *
         * size.hasRemaining() 返回 size 是否还有剩余空间，用于处理拆包问题
         * size.hasRemaining() 返回 true 表示还没有读满 4 个字节
         */
        if (size.hasRemaining()) {
            /**
             * 读取 4 字节的数据到 size 里面
             */
            int bytesRead = channel.read(size);
            if (bytesRead < 0)
                throw new EOFException();
            read += bytesRead;
            if (!size.hasRemaining()) {
                size.rewind();
                int receiveSize = size.getInt();
                if (receiveSize < 0)
                    throw new InvalidReceiveException("Invalid receive (size = " + receiveSize + ")");
                if (maxSize != UNLIMITED && receiveSize > maxSize)
                    throw new InvalidReceiveException("Invalid receive (size = " + receiveSize + " larger than " + maxSize + ")");

                /**
                 * 分配 size 值大小的内存空间
                 */
                this.buffer = ByteBuffer.allocate(receiveSize);
            }
        }
        if (buffer != null) {
            int bytesRead = channel.read(buffer);
            if (bytesRead < 0)
                throw new EOFException();
            read += bytesRead;
        }

        return read;
    }

    public ByteBuffer payload() {
        return this.buffer;
    }

}
