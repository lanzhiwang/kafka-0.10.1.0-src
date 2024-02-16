# Producer

1. 生产者源码 Producer 核心流程说明
2. 生产者源码 Producer 初始化
3. 生产者源码 Producer 元数据管理
4. 生产者源码 Producer 核心流程代码
5. 生产者源码 Producer 加载元数据
6. 生产者源码 Producer 分区选择
7. 生产者源码 Producer RecordAccumulator 封装消息
8. 生产者源码 Producer CopyOnWriteMap 数据结构的使用
9. 生产者源码 Producer 把数据写入到对应的 batch
10. 生产者源码 Producer 内存池的设计
11. 生产者源码 Producer sender 线程运行流程初探
12. 生产者源码 Producer 一个 batch 什么条件下可以发送
13. 生产者源码 Producer 筛选可以发送消息的 broker
14. 生产者源码 Producer kafka 网络设计 NetworkClient
15. 生产者源码 Producer 网络没有建立会发送消息吗
16. 生产者源码 Producer 与 broker 建立连接完成
17. 生产者源码 Producer 真正发送消息
18. 生产者源码 Producer kafka 是如何处理粘包问题的
19. 生产者源码 Producer kafka 是如何处理拆包问题的
20. 生产者源码 Producer 如何处理暂存状态下的消息
21. 生产者源码 Producer 如何处理响应消息
22. 生产者源码 Producer 响应处理完成之后内存如何处理
23. 生产者源码 Producer 响应有异常是如何处理的
24. 生产者源码 Producer 如何处理超时的 batch
25. 生产者源码 Producer 如何长时间没有接收到响应的 batch

总结：
1. Kafka 的源码阅读起来，思路很清晰，命名也是很规范。
2. Kafka 的网络部分的设计绝对是一个亮点，Kafka 自己基于 NIO 封装了一套自己的网络框架，支持一个客户端与多个 Broker 建立连接。
3. 处理`拆包`和`粘包`的的思路和代码，绝对是教科书级别的，大家可以把代码复制粘贴下来直接用到自己的线上的项目去。
4. RecordAccumulator 封装消息的 batchs，使用的自己封装的数据结构 CopyOnWriteMap，采用读写分离的思想，提提供了性能。
5. 同时封装消息的时候设计的内存缓冲池，这极大的减少了 GC 的次数。
6. RecordAccumulator 封装批次时候的代码的话，里面封装批次的时候代码采用的是分段加锁的思想，极大的提高了性能。
7. Kafka 的异常体系也是设计得比较清晰的，在核心流程捕获异常底层抛异常。

```bash
$ git status
On branch main
Changes to be committed:
  (use "git restore --staged <file>..." to unstage)
	modified:   clients/src/main/java/org/apache/kafka/clients/ClusterConnectionStates.java
	modified:   clients/src/main/java/org/apache/kafka/clients/InFlightRequests.java
	modified:   clients/src/main/java/org/apache/kafka/clients/Metadata.java
	modified:   clients/src/main/java/org/apache/kafka/clients/NetworkClient.java
	modified:   clients/src/main/java/org/apache/kafka/clients/producer/KafkaProducer.java
	modified:   clients/src/main/java/org/apache/kafka/clients/producer/internals/BufferPool.java
	modified:   clients/src/main/java/org/apache/kafka/clients/producer/internals/DefaultPartitioner.java
	modified:   clients/src/main/java/org/apache/kafka/clients/producer/internals/RecordAccumulator.java
	modified:   clients/src/main/java/org/apache/kafka/clients/producer/internals/RecordBatch.java
	modified:   clients/src/main/java/org/apache/kafka/clients/producer/internals/Sender.java
	modified:   clients/src/main/java/org/apache/kafka/common/Cluster.java
	modified:   clients/src/main/java/org/apache/kafka/common/Node.java
	modified:   clients/src/main/java/org/apache/kafka/common/PartitionInfo.java
	modified:   clients/src/main/java/org/apache/kafka/common/network/KafkaChannel.java
	modified:   clients/src/main/java/org/apache/kafka/common/network/NetworkReceive.java
	modified:   clients/src/main/java/org/apache/kafka/common/network/PlaintextTransportLayer.java
	modified:   clients/src/main/java/org/apache/kafka/common/network/Selector.java
	modified:   clients/src/main/java/org/apache/kafka/common/utils/CopyOnWriteMap.java
	modified:   examples/src/main/java/kafka/examples/Producer.java

$
```

# 服务端

1. kafka 服务端源码包结构
2. kafka 服务端 Acceptor 线程是如何启动的
3. kafka 服务端 Processor 线程是如何启动的
4. kafka 服务端 Processor 线程是如何接受请求的并处理请求数据的
5. kafka 服务端是如何处理 requestQueue 队列中中的请求的
6. 
