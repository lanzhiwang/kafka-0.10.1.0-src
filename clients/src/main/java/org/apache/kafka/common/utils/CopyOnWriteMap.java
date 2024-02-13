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
package org.apache.kafka.common.utils;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

/**
 * A simple read-optimized map implementation that synchronizes only writes and does a full copy on each modification
 * CopyOnWriteMap 数据结构的特点
 * 1. 这个数据结构在高并发场景下是线程安全的
 * 2. 采用读写分离的方法，每次插入数据都开辟新的内存空间
 *    这样在写数据的时候会比较耗费内存
 * 3. 这个数据结构适合 写少读多 的场景
 *    读数据性能很高
 *
 */
public class CopyOnWriteMap<K, V> implements ConcurrentMap<K, V> {

    /**
     * 核心变量就是这个 map
     * 这个 map 的修饰符是 volatile，这说明在多线程环境中，如果这个 map 里面的值发生变化，其他线程也是可见的
     */
    private volatile Map<K, V> map;

    // private final ConcurrentMap<TopicPartition, Deque<RecordBatch>> batches;
    // this.batches = new CopyOnWriteMap<>();
    public CopyOnWriteMap() {
        this.map = Collections.emptyMap();
    }

    public CopyOnWriteMap(Map<K, V> map) {
        this.map = Collections.unmodifiableMap(map);
    }

    @Override
    public boolean containsKey(Object k) {
        return map.containsKey(k);
    }

    @Override
    public boolean containsValue(Object v) {
        return map.containsValue(v);
    }

    // for (Map.Entry<TopicPartition, Deque<RecordBatch>> entry : this.batches.entrySet()) {
    @Override
    public Set<java.util.Map.Entry<K, V>> entrySet() {
        return map.entrySet();
    }

    /**
     * get 操作没有加锁，并且是线程安全的
     * 因为采用了读写分离的方法
     * 在高并发场景下，性能很好
     */
    // Deque<RecordBatch> d = this.batches.get(tp);
    @Override
    public V get(Object k) {
        return map.get(k);
    }

    @Override
    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public Set<K> keySet() {
        return map.keySet();
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public Collection<V> values() {
        return map.values();
    }

    @Override
    public synchronized void clear() {
        this.map = Collections.emptyMap();
    }

    /**
     * 1. put 使用 synchronized 修饰，说明 put 方法是线程安全的
     *    即使加了锁，put 操作依然很快，因为 put 里面都是纯内存操作
     * 2. 采用读写分离的方法
     *    也就是读操作和写操作是互相不影响的
     *    也就是读操作天然就是线程安全的
     * 3. 最后赋值给 map，map 是 volatile 修饰的
     *    说明 map 具有可见性，也就是 get 数据时，如果此时 map 值发生变化，get 可以获取到最新的值
     */
    @Override
    public synchronized V put(K k, V v) {
        // 开辟新的内存空间，相当于 读 操作
        Map<K, V> copy = new HashMap<K, V>(this.map);
        // 插入数据，相当于 写 操作
        V prev = copy.put(k, v);
        // 赋值给 map
        this.map = Collections.unmodifiableMap(copy);
        return prev;
    }

    @Override
    public synchronized void putAll(Map<? extends K, ? extends V> entries) {
        Map<K, V> copy = new HashMap<K, V>(this.map);
        copy.putAll(entries);
        this.map = Collections.unmodifiableMap(copy);
    }

    @Override
    public synchronized V remove(Object key) {
        Map<K, V> copy = new HashMap<K, V>(this.map);
        V prev = copy.remove(key);
        this.map = Collections.unmodifiableMap(copy);
        return prev;
    }

    // Deque<RecordBatch> previous = this.batches.putIfAbsent(tp, d);
    @Override
    public synchronized V putIfAbsent(K k, V v) {
        if (!containsKey(k))
            return put(k, v);
        else
            return get(k);
    }

    @Override
    public synchronized boolean remove(Object k, Object v) {
        if (containsKey(k) && get(k).equals(v)) {
            remove(k);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public synchronized boolean replace(K k, V original, V replacement) {
        if (containsKey(k) && get(k).equals(original)) {
            put(k, replacement);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public synchronized V replace(K k, V v) {
        if (containsKey(k)) {
            return put(k, v);
        } else {
            return null;
        }
    }

}
