/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.cache.support.expiring;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regressions for an expiration check racing a concurrent replacement of the same key.
 *
 * <p>The checks are paused deterministically: the test key blocks inside {@link Object#hashCode()} at the
 * call the removal makes, which happens after the expired entry has been observed.
 */
class ExpiringMapTest {

    private static final long TIMEOUT_SECONDS = 10;

    @Test
    void getShouldKeepReplacementPutAfterExpiredEntryWasObserved() throws Exception {
        ExpiringMap<Object, Object> map = new ExpiringMap<>(60, 60);
        // put hashes once, get hashes once, and the removal of the observed entry is the third call
        BlockingKey key = new BlockingKey(3);
        map.put(key, "expired");
        expireEntry(map, key);

        AtomicReference<Object> observedValue = new AtomicReference<>();
        Thread getter = new Thread(() -> observedValue.set(map.get(key)), "expiring-map-get");
        getter.start();
        try {
            key.awaitBlocked();
            // the expiration check has observed the expired entry but has not removed it yet
            map.put(key, "replacement");
        } finally {
            key.release();
        }
        getter.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        assertFalse(getter.isAlive(), "get did not finish");

        assertNull(observedValue.get(), "the overlapping get must not return the expired value");
        assertEquals("replacement", map.get(key), "the expiration check removed the concurrent replacement");
    }

    @Test
    void expireThreadShouldKeepReplacementPutAfterExpiredEntryWasObserved() throws Exception {
        ExpiringMap<Object, Object> map = new ExpiringMap<>(60, 60);
        BlockingKey key = new BlockingKey(2);
        map.put(key, "expired");
        expireEntry(map, key);

        Thread expirer = new Thread(
                () -> {
                    try {
                        processExpires(map);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                },
                "expiring-map-processor");
        expirer.start();
        try {
            key.awaitBlocked();
            // the expiration pass has observed the expired entry but has not removed it yet
            map.put(key, "replacement");
        } finally {
            key.release();
        }
        expirer.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        assertFalse(expirer.isAlive(), "expiration pass did not finish");

        assertEquals("replacement", map.get(key), "the expiration pass removed the concurrent replacement");
    }

    @Test
    void getShouldRemoveExpiredEntryWithoutReplacement() throws Exception {
        ExpiringMap<Object, Object> map = new ExpiringMap<>(60, 60);
        BlockingKey key = new BlockingKey(0);
        map.put(key, "expired");
        expireEntry(map, key);

        assertNull(map.get(key), "an expired entry must not be returned");
        assertTrue(map.isEmpty(), "an expired entry must be removed when it is not replaced");
    }

    @Test
    void expireThreadShouldRemoveExpiredEntryWithoutReplacement() throws Exception {
        ExpiringMap<Object, Object> map = new ExpiringMap<>(60, 60);
        BlockingKey key = new BlockingKey(0);
        map.put(key, "expired");
        expireEntry(map, key);

        processExpires(map);

        assertTrue(map.isEmpty(), "an expired entry must be removed when it is not replaced");
    }

    private static void processExpires(ExpiringMap<Object, Object> map) throws ReflectiveOperationException {
        Object expireThread = map.getExpireThread();
        Method method = expireThread.getClass().getDeclaredMethod("processExpires");
        method.setAccessible(true);
        method.invoke(expireThread);
    }

    @SuppressWarnings("unchecked")
    private static void expireEntry(ExpiringMap<Object, Object> map, Object key) throws ReflectiveOperationException {
        Field delegateField = ExpiringMap.class.getDeclaredField("delegateMap");
        delegateField.setAccessible(true);
        ConcurrentHashMap<Object, Object> delegate = (ConcurrentHashMap<Object, Object>) delegateField.get(map);
        for (Map.Entry<Object, Object> entry : delegate.entrySet()) {
            if (entry.getKey() == key) {
                Field lastAccessTimeField = entry.getValue().getClass().getDeclaredField("lastAccessTime");
                lastAccessTimeField.setAccessible(true);
                ((AtomicLong) lastAccessTimeField.get(entry.getValue())).set(0L);
                return;
            }
        }
        fail("the entry for the given key was not found");
    }

    /**
     * A key whose {@code hashCode()} can block at a chosen invocation, which pauses an expiration check between
     * observing an expired entry and removing it.
     */
    private static final class BlockingKey {
        private final int hash;
        private final int blockingCall;
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch blocked = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        BlockingKey(int blockingCall) {
            this.hash = 17;
            this.blockingCall = blockingCall;
        }

        void awaitBlocked() throws InterruptedException {
            assertTrue(
                    blocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the expiration check never reached the removal step");
        }

        void release() {
            release.countDown();
        }

        @Override
        public int hashCode() {
            if (calls.incrementAndGet() == blockingCall) {
                blocked.countDown();
                try {
                    release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj;
        }

        @Override
        public String toString() {
            return "blocking-key";
        }
    }
}
