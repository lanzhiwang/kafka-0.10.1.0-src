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
package org.apache.kafka.clients;

import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.network.NetworkReceive;
import org.apache.kafka.common.network.Selectable;
import org.apache.kafka.common.network.Send;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.protocol.ProtoUtils;
import org.apache.kafka.common.protocol.types.Struct;
import org.apache.kafka.common.requests.MetadataRequest;
import org.apache.kafka.common.requests.MetadataResponse;
import org.apache.kafka.common.requests.RequestHeader;
import org.apache.kafka.common.requests.RequestSend;
import org.apache.kafka.common.requests.ResponseHeader;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A network client for asynchronous request/response network i/o. This is an internal class used to implement the
 * user-facing producer and consumer clients.
 * <p>
 * This class is not thread-safe!
 */
public class NetworkClient implements KafkaClient {

    /**
     * NetworkClient 组件里面有 selector
     * selector 组件里面有 nioSelector 和 Map<String, KafkaChannel> channels，channels 也就是有 KafkaChannel
     * KafkaChannel 组件里面有 transportLayer
     * transportLayer 组件里面有 socketChannel
     *
     * NetworkClient.initiateConnect() -> selector.connect()
     * selector.connect() {
     *      SocketChannel socketChannel = SocketChannel.open();
     *      SelectionKey key = socketChannel.register(nioSelector, SelectionKey.OP_CONNECT);
     *      KafkaChannel channel = channelBuilder.buildChannel(id, key, maxReceiveSize);
     *      key.attach(channel);
     *      this.channels.put(id, channel);
     * }
     *
     * nioSelector.poll() -> selector.poll() 在 selector.poll() 中处理各种事件，包括 OP_CONNECT、read、write 事件
     * 在处理 OP_CONNECT 事件时建立真正的网络连接
     *
     */


    private static final Logger log = LoggerFactory.getLogger(NetworkClient.class);

    /**
     *
     */
    /* the selector used to perform network i/o */
    private final Selectable selector;

    /**
     *
     */
    private final MetadataUpdater metadataUpdater;

    /**
     * this.randOffset = new Random();
     */
    private final Random randOffset;

    /**
     * this.connectionStates = new ClusterConnectionStates(reconnectBackoffMs);
     */
    /* the state of each node's connection */
    private final ClusterConnectionStates connectionStates;

    /**
     * this.inFlightRequests = new InFlightRequests(maxInFlightRequestsPerConnection);
     */
    /* the set of requests currently being sent or awaiting a response */
    private final InFlightRequests inFlightRequests;

    /**
     *
     */
    /* the socket send buffer size in bytes */
    private final int socketSendBuffer;

    /**
     *
     */
    /* the socket receive size buffer in bytes */
    private final int socketReceiveBuffer;

    /**
     *
     */
    /* the client id used to identify this client in requests to the server */
    private final String clientId;

    /**
     * this.correlation = 0;
     */
    /* the current correlation id to use when sending requests to servers */
    private int correlation;

    /**
     *
     */
    /* max time in ms for the producer to wait for acknowledgement from server*/
    private final int requestTimeoutMs;

    /**
     *
     */
    private final Time time;

    /**
     *
        NetworkClient client = new NetworkClient(
            new Selector(
                config.getLong(ProducerConfig.CONNECTIONS_MAX_IDLE_MS_CONFIG),
                this.metrics,
                time,
                "producer",
                channelBuilder
            ),
            this.metadata,
            clientId,
            config.getInt(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION),
            config.getLong(ProducerConfig.RECONNECT_BACKOFF_MS_CONFIG),
            config.getInt(ProducerConfig.SEND_BUFFER_CONFIG),
            config.getInt(ProducerConfig.RECEIVE_BUFFER_CONFIG),
            this.requestTimeoutMs,
            time
        );
     *
     * CONNECTIONS_MAX_IDLE_MS_CONFIG
     * connections.max.idle.ms
     * Close idle connections after the number of milliseconds specified by this config. 网络连接的空闲时间，超过空闲时间，该连接会被关闭
     * 默认值：9 * 60 * 1000
     *
     * channelBuilder
     * ChannelBuilder channelBuilder = ClientUtils.createChannelBuilder(config.values());
     *
     * MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION
     * max.in.flight.requests.per.connection
     * 默认值是 5
     * The maximum number of unacknowledged requests the client will send on a single connection before blocking.
     * Note that if this setting is set to be greater than 1 and there are failed sends, there is a risk of message re-ordering due to retries (i.e., if retries are enabled).
     * 1 2 3 4 5
     *
     * RECONNECT_BACKOFF_MS_CONFIG
     * reconnect.backoff.ms
     * The amount of time to wait before attempting to reconnect to a given host. This avoids repeatedly connecting to a host in a tight loop. This backoff applies to all requests sent by the consumer to the broker.
     * 尝试重新连接到给定主机之前等待的时间。 这避免了在紧密循环中重复连接到主机。 此退避适用于消费者向代理发送的所有请求。
     *
     * SEND_BUFFER_CONFIG
     * send.buffer.bytes
     * The size of the TCP send buffer (SO_SNDBUF) to use when sending data. If the value is -1, the OS default will be used.
     * 默认值是：128 * 1024
     *
     * RECEIVE_BUFFER_CONFIG
     * receive.buffer.bytes
     * The size of the TCP receive buffer (SO_RCVBUF) to use when reading data. If the value is -1, the OS default will be used.
     * 默认值是：32 * 1024
     *
     * requestTimeoutMs
     * timeout.ms 默认值：30 * 1000
     * request.timeout.ms 默认值：30 * 1000
     */
    public NetworkClient(Selectable selector,
                         Metadata metadata,
                         String clientId,
                         int maxInFlightRequestsPerConnection,
                         long reconnectBackoffMs,
                         int socketSendBuffer,
                         int socketReceiveBuffer,
                         int requestTimeoutMs,
                         Time time) {
        this(null, metadata, selector, clientId, maxInFlightRequestsPerConnection,
                reconnectBackoffMs, socketSendBuffer, socketReceiveBuffer, requestTimeoutMs, time);
    }

    public NetworkClient(Selectable selector,
                         MetadataUpdater metadataUpdater,
                         String clientId,
                         int maxInFlightRequestsPerConnection,
                         long reconnectBackoffMs,
                         int socketSendBuffer,
                         int socketReceiveBuffer,
                         int requestTimeoutMs,
                         Time time) {
        this(metadataUpdater, null, selector, clientId, maxInFlightRequestsPerConnection, reconnectBackoffMs,
                socketSendBuffer, socketReceiveBuffer, requestTimeoutMs, time);
    }

    // this(
    //     null,
    //     metadata,
    //     selector,
    //     clientId,
    //     maxInFlightRequestsPerConnection,
    //     reconnectBackoffMs,
    //     socketSendBuffer,
    //     socketReceiveBuffer,
    //     requestTimeoutMs,
    //     time
    // );
    private NetworkClient(MetadataUpdater metadataUpdater,
                          Metadata metadata,
                          Selectable selector,
                          String clientId,
                          int maxInFlightRequestsPerConnection,
                          long reconnectBackoffMs,
                          int socketSendBuffer,
                          int socketReceiveBuffer,
                          int requestTimeoutMs,
                          Time time) {

        /* It would be better if we could pass `DefaultMetadataUpdater` from the public constructor, but it's not
         * possible because `DefaultMetadataUpdater` is an inner class and it can only be instantiated after the
         * super constructor is invoked.
         */
        if (metadataUpdater == null) {
            if (metadata == null)
                throw new IllegalArgumentException("`metadata` must not be null");
            this.metadataUpdater = new DefaultMetadataUpdater(metadata);
        } else {
            this.metadataUpdater = metadataUpdater;
        }
        this.selector = selector;
        this.clientId = clientId;
        this.inFlightRequests = new InFlightRequests(maxInFlightRequestsPerConnection);
        this.connectionStates = new ClusterConnectionStates(reconnectBackoffMs);
        this.socketSendBuffer = socketSendBuffer;
        this.socketReceiveBuffer = socketReceiveBuffer;
        this.correlation = 0;
        this.randOffset = new Random();
        this.requestTimeoutMs = requestTimeoutMs;
        this.time = time;
    }

    /**
     * Begin connecting to the given node, return true if we are already connected and ready to send to that node.
     *
     * @param node The node to check
     * @param now The current timestamp
     * @return True if we are ready to send to the given node
     */
    @Override
    public boolean ready(Node node, long now) {
        if (node.isEmpty())
            throw new IllegalArgumentException("Cannot connect to empty node " + node);

        /**
         * 检查将要与之通信的 node 是否建立好网络连接
         */
        if (isReady(node, now))
            return true;

        /**
         * 如果与 node 的网络连接没有建立好
         * connectionStates.canConnect() 表示是否可以建立网络连接
         * initiateConnect() 初始化网络连接
         */
        if (connectionStates.canConnect(node.idString(), now))
            // if we are interested in sending to a node and we don't have a connection to it, initiate one
            initiateConnect(node, now);

        return false;
    }

    /**
     * Closes the connection to a particular node (if there is one).
     *
     * @param nodeId The id of the node
     */
    @Override
    public void close(String nodeId) {
        selector.close(nodeId);
        for (ClientRequest request : inFlightRequests.clearAll(nodeId))
            metadataUpdater.maybeHandleDisconnection(request);
        connectionStates.remove(nodeId);
    }

    /**
     * Returns the number of milliseconds to wait, based on the connection state, before attempting to send data. When
     * disconnected, this respects the reconnect backoff time. When connecting or connected, this handles slow/stalled
     * connections.
     *
     * @param node The node to check
     * @param now The current timestamp
     * @return The number of milliseconds to wait.
     */
    @Override
    public long connectionDelay(Node node, long now) {
        return connectionStates.connectionDelay(node.idString(), now);
    }

    /**
     * Check if the connection of the node has failed, based on the connection state. Such connection failure are
     * usually transient and can be resumed in the next {@link #ready(org.apache.kafka.common.Node, long)} }
     * call, but there are cases where transient failures needs to be caught and re-acted upon.
     *
     * @param node the node to check
     * @return true iff the connection has failed and the node is disconnected
     */
    @Override
    public boolean connectionFailed(Node node) {
        return connectionStates.connectionState(node.idString()).equals(ConnectionState.DISCONNECTED);
    }

    /**
     * Check if the node with the given id is ready to send more requests.
     *
     * @param node The node
     * @param now The current time in ms
     * @return true if the node is ready
     */
    @Override
    public boolean isReady(Node node, long now) {
        // if we need to update our metadata now declare all requests unready to make metadata requests first
        // priority
        /**
         * metadataUpdater.isUpdateDue() 表示是否正在更新元数据
         *                               如果正在更新元数据，那么就不能发送消息，如果没有更新元数据，那么久可以发送消息
         * canSendRequest() 检查是否有可用的网络连接
         */
        return !metadataUpdater.isUpdateDue(now) && canSendRequest(node.idString());
    }

    /**
     * Are we connected and ready and able to send more requests to the given connection?
     *
     * @param node The node
     */
    private boolean canSendRequest(String node) {
        /**
         * connectionStates.isConnected()
         * connectionStates 中缓存了到每个 broker 的网络连接
         * isConnected() 表示缓存中是否有相应的 broker 的网络连接，并且网络连接处于连接状态
         *
         * selector.isChannelReady(node) 表示操作系统层面的网络连接是否建立好
         *
         * 对于生产者往 broker 发送消息，默认配置 5 个发送消息的请求可以没有接收到响应
         * inFlightRequests.canSendMore(node) 判断是否超过 5 个请求没有接收到响应
         */
        return connectionStates.isConnected(node) && selector.isChannelReady(node) && inFlightRequests.canSendMore(node);
    }

    /**
     * Queue up the given request for sending. Requests can only be sent out to ready nodes.
     *
     * @param request The request
     * @param now The current timestamp
     */
    @Override
    public void send(ClientRequest request, long now) {
        String nodeId = request.request().destination();
        if (!canSendRequest(nodeId))
            throw new IllegalStateException("Attempt to send a request to node " + nodeId + " which is not ready.");
        doSend(request, now);
    }

    private void doSend(ClientRequest request, long now) {
        request.setSendTimeMs(now);
        /**
         * 将 request 存入 inFlightRequests
         * inFlightRequests 默认最多存储 5 个 request
         * 这些 request 都是没有接收到服务器响应的请求
         *
         * 如果这些 request 接收到响应，那么一定会把 request 从 inFlightRequests 移除
         */
        this.inFlightRequests.add(request);
        selector.send(request.request());
    }

    /**
     * Do actual reads and writes to sockets.
     *
     * @param timeout The maximum amount of time to wait (in ms) for responses if there are none immediately,
     *                must be non-negative. The actual timeout will be the minimum of timeout, request timeout and
     *                metadata timeout
     * @param now The current time in milliseconds
     * @return The list of responses received
     */
    // this.client.poll(pollTimeout, now);
    @Override
    public List<ClientResponse> poll(long timeout, long now) {
        /**
         * 步骤一：
         *      封装拉取元数据的网络请求
         */
        long metadataTimeout = metadataUpdater.maybeUpdate(now);
        try {
            /**
             * 步骤二：
             *      执行网络 IO 操作
             */
            this.selector.poll(Utils.min(timeout, metadataTimeout, requestTimeoutMs));
        } catch (IOException e) {
            log.error("Unexpected error during I/O", e);
        }

        // process completed actions
        long updatedNow = this.time.milliseconds();
        List<ClientResponse> responses = new ArrayList<>();
        handleCompletedSends(responses, updatedNow);
        /**
         * 步骤三：
         *      处理网络响应，响应中带有元数据信息
         * 发送消息之后，处理服务端的响应也是同样的流程
         *
         * 响应消息的流转流程：
         * 1. 将响应消息从服务端接收，并存储在 networkReceive 里面
         * 2. 将 networkReceive 缓存到 stagedReceives 里面
         * 3. 将 networkReceive 从 stagedReceives 取出，存入 completedReceives 中
         * 4. 遍历 completedReceives 中的 networkReceive，
         *    根据 networkReceive 初始化 ClientResponse，将 ClientResponse 存储到 responses 中
         *
         */
        handleCompletedReceives(responses, updatedNow);
        handleDisconnections(responses, updatedNow);
        handleConnections();
        /**
         * 处理超时的请求
         */
        handleTimedOutRequests(responses, updatedNow);

        /**
         *
         * 响应消息的流转流程：
         * 1. 将响应消息从服务端接收，并存储在 networkReceive 里面
         * 2. 将 networkReceive 缓存到 stagedReceives 里面
         * 3. 将 networkReceive 从 stagedReceives 取出，存入 completedReceives 中
         * 4. 遍历 completedReceives 中的 networkReceive，
         *    根据 networkReceive 初始化 ClientResponse，将 ClientResponse 存储到 responses 中
         * 5. 调用请求中的回调函数处理响应
         */
        // invoke callbacks
        for (ClientResponse response : responses) {
            if (response.request().hasCallback()) {
                try {
                    /**
                     * request().callback() 从请求中获取回调函数
                     * 说明创建请求的时候将回调函数加进去了
                     */
                    response.request().callback().onComplete(response);
                } catch (Exception e) {
                    log.error("Uncaught error in request completion:", e);
                }
            }
        }

        return responses;
    }

    /**
     * Get the number of in-flight requests
     */
    @Override
    public int inFlightRequestCount() {
        return this.inFlightRequests.inFlightRequestCount();
    }

    /**
     * Get the number of in-flight requests for a given node
     */
    @Override
    public int inFlightRequestCount(String node) {
        return this.inFlightRequests.inFlightRequestCount(node);
    }

    /**
     * Generate a request header for the given API key
     *
     * @param key The api key
     * @return A request header with the appropriate client id and correlation id
     */
    @Override
    public RequestHeader nextRequestHeader(ApiKeys key) {
        return new RequestHeader(key.id, clientId, correlation++);
    }

    /**
     * Generate a request header for the given API key and version
     *
     * @param key The api key
     * @param version The api version
     * @return A request header with the appropriate client id and correlation id
     */
    @Override
    public RequestHeader nextRequestHeader(ApiKeys key, short version) {
        return new RequestHeader(key.id, version, clientId, correlation++);
    }

    /**
     * Interrupt the client if it is blocked waiting on I/O.
     */
    @Override
    public void wakeup() {
        this.selector.wakeup();
    }

    /**
     * Close the network client
     */
    @Override
    public void close() {
        this.selector.close();
    }

    /**
     * Choose the node with the fewest outstanding requests which is at least eligible for connection. This method will
     * prefer a node with an existing connection, but will potentially choose a node for which we don't yet have a
     * connection if all existing connections are in use. This method will never choose a node for which there is no
     * existing connection and from which we have disconnected within the reconnect backoff period.
     *
     * @return The node with the fewest in-flight requests.
     */
    @Override
    public Node leastLoadedNode(long now) {
        List<Node> nodes = this.metadataUpdater.fetchNodes();
        int inflight = Integer.MAX_VALUE;
        Node found = null;

        int offset = this.randOffset.nextInt(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            int idx = (offset + i) % nodes.size();
            Node node = nodes.get(idx);
            int currInflight = this.inFlightRequests.inFlightRequestCount(node.idString());
            if (currInflight == 0 && this.connectionStates.isConnected(node.idString())) {
                // if we find an established connection with no in-flight requests we can stop right away
                return node;
            } else if (!this.connectionStates.isBlackedOut(node.idString(), now) && currInflight < inflight) {
                // otherwise if this is the best we have found so far, record that
                inflight = currInflight;
                found = node;
            }
        }

        return found;
    }

    public static Struct parseResponse(ByteBuffer responseBuffer, RequestHeader requestHeader) {
        ResponseHeader responseHeader = ResponseHeader.parse(responseBuffer);
        // Always expect the response version id to be the same as the request version id
        short apiKey = requestHeader.apiKey();
        short apiVer = requestHeader.apiVersion();
        Struct responseBody = ProtoUtils.responseSchema(apiKey, apiVer).read(responseBuffer);
        correlate(requestHeader, responseHeader);
        return responseBody;
    }

    /**
     * Post process disconnection of a node
     *
     * @param responses The list of responses to update
     * @param nodeId Id of the node to be disconnected
     * @param now The current time
     */
    private void processDisconnection(List<ClientResponse> responses, String nodeId, long now) {
        /**
         * 修改缓存在 connectionStates 中的连接的连接状态
         */
        connectionStates.disconnected(nodeId, now);
        for (ClientRequest request : this.inFlightRequests.clearAll(nodeId)) {
            log.trace("Cancelled request {} due to node {} being disconnected", request, nodeId);
            if (!metadataUpdater.maybeHandleDisconnection(request))
                /**
                 * 向生产者也返回连接断开的响应
                 */
                responses.add(new ClientResponse(request, now, true, null));
        }
    }

    /**
     * Iterate over all the inflight requests and expire any requests that have exceeded the configured requestTimeout.
     * The connection to the node associated with the request will be terminated and will be treated as a disconnection.
     *
     * @param responses The list of responses to update
     * @param now The current time
     */
    private void handleTimedOutRequests(List<ClientResponse> responses, long now) {
        /**
         * requestTimeoutMs
         * timeout.ms 默认值：30 * 1000
         * request.timeout.ms 默认值：30 * 1000
         *
         */
        List<String> nodeIds = this.inFlightRequests.getNodesWithTimedOutRequests(now, this.requestTimeoutMs);
        for (String nodeId : nodeIds) {
            // close connection to the node
            this.selector.close(nodeId);
            log.debug("Disconnecting from node {} due to request timeout.", nodeId);
            /**
             * 修改换成的连接的连接状态
             * 向生产者返回连接断开的响应
             */
            processDisconnection(responses, nodeId, now);
        }

        // we disconnected, so we should probably refresh our metadata
        if (nodeIds.size() > 0)
            metadataUpdater.requestUpdate();
    }

    /**
     * Handle any completed request send. In particular if no response is expected consider the request complete.
     *
     * @param responses The list of responses to update
     * @param now The current time
     */
    private void handleCompletedSends(List<ClientResponse> responses, long now) {
        // if no response is expected then when the send is completed, return it
        for (Send send : this.selector.completedSends()) {
            ClientRequest request = this.inFlightRequests.lastSent(send.destination());
            if (!request.expectResponse()) {
                this.inFlightRequests.completeLastSent(send.destination());
                responses.add(new ClientResponse(request, now, false, null));
            }
        }
    }

    /**
     * Handle any completed receives and update the response list with the responses received.
     *
     * @param responses The list of responses to update
     * @param now The current time
     */
    private void handleCompletedReceives(List<ClientResponse> responses, long now) {
        /**
         * 响应消息的流转流程：
         * 1. 将响应消息从服务端接收，并存储在 networkReceive 里面
         * 2. 将 networkReceive 缓存到 stagedReceives 里面
         * 3. 将 networkReceive 从 stagedReceives 取出，存入 completedReceives 中
         * 4. 遍历 completedReceives 中的 networkReceive，根据 networkReceive 初始化 ClientResponse，将 ClientResponse 存储到 responses 中
         */
        for (NetworkReceive receive : this.selector.completedReceives()) {
            /**
             * source 表示 broker id
             */
            String source = receive.source();
            /**
             * 从 inFlightRequests 获取请求，并且把请求从 inFlightRequests 移除
             */
            ClientRequest req = inFlightRequests.completeNext(source);
            Struct body = parseResponse(receive.payload(), req.request().header());
            /**
             * 处理 body 中的元数据
             *
             * metadataUpdater.maybeHandleCompletedReceive()
             * If `request` is a metadata request, handles it and returns `true`. Otherwise, returns `false`.
             */
            if (!metadataUpdater.maybeHandleCompletedReceive(req, now, body))
                /**
                 * List<ClientResponse> responses = new ArrayList<>();
                 */
                responses.add(new ClientResponse(req, now, false, body));
        }
    }

    /**
     * Handle any disconnected connections
     *
     * @param responses The list of responses that completed with the disconnection
     * @param now The current time
     */
    private void handleDisconnections(List<ClientResponse> responses, long now) {
        for (String node : this.selector.disconnected()) {
            log.debug("Node {} disconnected.", node);
            processDisconnection(responses, node, now);
        }
        // we got a disconnect so we should probably refresh our metadata and see if that broker is dead
        if (this.selector.disconnected().size() > 0)
            metadataUpdater.requestUpdate();
    }

    /**
     * Record any newly completed connections
     */
    private void handleConnections() {
        for (String node : this.selector.connected()) {
            log.debug("Completed connection to node {}", node);
            this.connectionStates.connected(node);
        }
    }

    /**
     * Validate that the response corresponds to the request we expect or else explode
     */
    private static void correlate(RequestHeader requestHeader, ResponseHeader responseHeader) {
        if (requestHeader.correlationId() != responseHeader.correlationId())
            throw new IllegalStateException("Correlation id for response (" + responseHeader.correlationId()
                    + ") does not match request (" + requestHeader.correlationId() + "), request header: " + requestHeader);
    }

    /**
     * Initiate a connection to the given node
     */
    private void initiateConnect(Node node, long now) {
        String nodeConnectionId = node.idString();
        try {
            log.debug("Initiating connection to node {} at {}:{}.", node.id(), node.host(), node.port());
            this.connectionStates.connecting(nodeConnectionId, now);
            /**
             * TODO 尝试建立连接
             */
            selector.connect(nodeConnectionId,
                             new InetSocketAddress(node.host(), node.port()),
                             this.socketSendBuffer,
                             this.socketReceiveBuffer);
        } catch (IOException e) {
            /* attempt failed, we'll try again after the backoff */
            connectionStates.disconnected(nodeConnectionId, now);
            /* maybe the problem is our metadata, update it */
            metadataUpdater.requestUpdate();
            log.debug("Error connecting to node {} at {}:{}:", node.id(), node.host(), node.port(), e);
        }
    }

    class DefaultMetadataUpdater implements MetadataUpdater {

        /* the current cluster metadata */
        private final Metadata metadata;

        /* true iff there is a metadata request that has been sent and for which we have not yet received a response */
        private boolean metadataFetchInProgress;

        /* the last timestamp when no broker node is available to connect */
        private long lastNoNodeAvailableMs;

        DefaultMetadataUpdater(Metadata metadata) {
            this.metadata = metadata;
            this.metadataFetchInProgress = false;
            this.lastNoNodeAvailableMs = 0;
        }

        @Override
        public List<Node> fetchNodes() {
            return metadata.fetch().nodes();
        }

        @Override
        public boolean isUpdateDue(long now) {
            return !this.metadataFetchInProgress && this.metadata.timeToNextUpdate(now) == 0;
        }

        @Override
        public long maybeUpdate(long now) {
            // should we update our metadata?
            long timeToNextMetadataUpdate = metadata.timeToNextUpdate(now);
            long timeToNextReconnectAttempt = Math.max(this.lastNoNodeAvailableMs + metadata.refreshBackoff() - now, 0);
            long waitForMetadataFetch = this.metadataFetchInProgress ? Integer.MAX_VALUE : 0;
            // if there is no node available to connect, back off refreshing metadata
            long metadataTimeout = Math.max(Math.max(timeToNextMetadataUpdate, timeToNextReconnectAttempt),
                    waitForMetadataFetch);

            if (metadataTimeout == 0) {
                // Beware that the behavior of this method and the computation of timeouts for poll() are
                // highly dependent on the behavior of leastLoadedNode.
                Node node = leastLoadedNode(now);
                /**
                 * TODO
                 * 封装网络请求
                 */
                maybeUpdate(now, node);
            }

            return metadataTimeout;
        }

        @Override
        public boolean maybeHandleDisconnection(ClientRequest request) {
            ApiKeys requestKey = ApiKeys.forId(request.request().header().apiKey());

            if (requestKey == ApiKeys.METADATA && request.isInitiatedByNetworkClient()) {
                Cluster cluster = metadata.fetch();
                if (cluster.isBootstrapConfigured()) {
                    int nodeId = Integer.parseInt(request.request().destination());
                    Node node = cluster.nodeById(nodeId);
                    if (node != null)
                        log.warn("Bootstrap broker {}:{} disconnected", node.host(), node.port());
                }

                metadataFetchInProgress = false;
                return true;
            }

            return false;
        }

        @Override
        public boolean maybeHandleCompletedReceive(ClientRequest req, long now, Struct body) {
            short apiKey = req.request().header().apiKey();
            if (apiKey == ApiKeys.METADATA.id && req.isInitiatedByNetworkClient()) {
                /**
                 * TODO
                 */
                handleResponse(req.request().header(), body, now);
                return true;
            }
            return false;
        }

        @Override
        public void requestUpdate() {
            this.metadata.requestUpdate();
        }

        private void handleResponse(RequestHeader header, Struct body, long now) {
            this.metadataFetchInProgress = false;
            /**
             * 将二进制的数据结构进行解析
             */
            MetadataResponse response = new MetadataResponse(body);
            /**
             * TODO
             */
            Cluster cluster = response.cluster();
            // check if any topics metadata failed to get updated
            Map<String, Errors> errors = response.errors();
            if (!errors.isEmpty())
                log.warn("Error while fetching metadata with correlation id {} : {}", header.correlationId(), errors);

            // don't update the cluster if there are no valid nodes...the topic we want may still be in the process of being
            // created which means we will get errors and no nodes until it exists
            if (cluster.nodes().size() > 0) {
                /**
                 * TODO
                 */
                this.metadata.update(cluster, now);
            } else {
                log.trace("Ignoring empty metadata response with correlation id {}.", header.correlationId());
                this.metadata.failedUpdate(now);
            }
        }

        /**
         * Create a metadata request for the given topics
         */
        private ClientRequest request(long now, String node, MetadataRequest metadata) {
            RequestSend send = new RequestSend(node, nextRequestHeader(ApiKeys.METADATA), metadata.toStruct());
            return new ClientRequest(now, true, send, null, true);
        }

        /**
         * Add a metadata request to the list of sends if we can make one
         */
        private void maybeUpdate(long now, Node node) {
            if (node == null) {
                log.debug("Give up sending metadata request since no node is available");
                // mark the timestamp for no node available to connect
                this.lastNoNodeAvailableMs = now;
                return;
            }
            String nodeConnectionId = node.idString();

            /**
             * 判断网络连接是否已经建立好
             * 假设网络连接已经建立
             */
            if (canSendRequest(nodeConnectionId)) {
                this.metadataFetchInProgress = true;
                MetadataRequest metadataRequest;
                if (metadata.needMetadataForAllTopics())
                    metadataRequest = MetadataRequest.allTopics();
                else
                    metadataRequest = new MetadataRequest(new ArrayList<>(metadata.topics()));
                ClientRequest clientRequest = request(now, nodeConnectionId, metadataRequest);
                log.debug("Sending metadata request {} to node {}", metadataRequest, node.id());
                /**
                 * 本地缓存网络请求
                 */
                doSend(clientRequest, now);
            } else if (connectionStates.canConnect(nodeConnectionId, now)) {
                // we don't have a connection to this node right now, make one
                log.debug("Initialize connection to node {} for sending metadata request", node.id());
                initiateConnect(node, now);
                // If initiateConnect failed immediately, this node will be put into blackout and we
                // should allow immediately retrying in case there is another candidate node. If it
                // is still connecting, the worst case is that we end up setting a longer timeout
                // on the next round and then wait for the response.
            } else { // connected, but can't send more OR connecting
                // In either case, we just need to wait for a network event to let us know the selected
                // connection might be usable again.
                this.lastNoNodeAvailableMs = now;
            }
        }

    }

}
