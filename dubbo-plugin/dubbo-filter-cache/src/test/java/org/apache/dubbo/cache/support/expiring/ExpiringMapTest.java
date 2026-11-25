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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpiringMapTest {

    private static final long TIMEOUT_SECONDS = 10;

    @Test
    void getRemovesExpiredEntry() throws Exception {
        ExpiringMap<Object, Object> map = new ExpiringMap<>(180, 1);
        BlockingKey key = new BlockingKey("ordinary-get-expiry");
        map.put(key, "value");
        expireTimestamp(map, key);

        assertNull(map.get(key));
        assertFalse(map.containsKey(key));
        assertEquals(0, map.size());
    }

    @Test
    void getReturnsValueThatHasNotExpired() {
        ExpiringMap<Object, Object> map = new ExpiringMap<>(180, 1);
        map.put("key", "value");

        assertEquals("value", map.get("key"));
        assertEquals(1, map.size());
    }

    @Test
    void backgroundExpiryRemovesExpiredEntry() throws Exception {
        ExpiringMap<Object, Object> map = new ExpiringMap<>(180, 1);
        try {
            map.put("key", "value");
            expireTimestamp(map, "key");
            map.getExpireThread().startExpiryIfNotStarted();

            assertTrue(waitUntil(() -> map.size() == 0), "the background thread did not remove the expired entry");
            assertFalse(map.containsKey("key"));
        } finally {
            map.getExpireThread().stopExpiring();
        }
    }

    @Test
    void getShouldNotRemoveAConcurrentReplacement() throws Exception {
        ExpiringMap<Object, Object> map = new ExpiringMap<>(180, 1);
        BlockingKey key = new BlockingKey("get-race");
        map.put(key, "stale");
        expireTimestamp(map, key);

        // get() hashes the key once to read the entry and once more to remove it. Pausing on the
        // second call stops get() after it observed the stale entry but before it removes anything.
        key.pauseOnHashCode(2);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> racingGet = executor.submit(() -> map.get(key));
            key.awaitPaused();
            assertEquals("stale", map.put(key, "fresh"));
            key.resume();
            assertNull(racingGet.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            key.disarm();
            executor.shutdownNow();
        }

        assertEquals("fresh", map.get(key));
        assertEquals(1, map.size());
    }

    @Test
    void backgroundExpiryShouldNotRemoveAConcurrentReplacement() throws Exception {
        ExpiringMap<Object, Object> map = new ExpiringMap<>(180, 1);
        BlockingKey key = new BlockingKey("background-race");
        map.put(key, "stale");
        expireTimestamp(map, key);

        // processExpires() hashes the key only when it removes the entry, so the first call is the
        // point between observing the stale entry and removing it.
        key.pauseOnHashCode(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> expirePass = executor.submit(() -> processExpires(map));
            key.awaitPaused();
            assertEquals("stale", map.put(key, "fresh"));
            key.resume();
            expirePass.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            key.disarm();
            executor.shutdownNow();
        }

        assertEquals("fresh", map.get(key));
        assertEquals(1, map.size());
    }

    private static void processExpires(ExpiringMap<Object, Object> map) {
        try {
            Method processExpires = map.getExpireThread().getClass().getDeclaredMethod("processExpires");
            processExpires.setAccessible(true);
            processExpires.invoke(map.getExpireThread());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("unable to run the background expiration pass", e);
        }
    }

    /**
     * Moves the timestamp of the mapping stored under the given key into the past so that the
     * mapping is expired without waiting for the time to live.
     */
    @SuppressWarnings("unchecked")
    private static void expireTimestamp(ExpiringMap<Object, Object> map, Object key)
            throws ReflectiveOperationException {
        Field delegateField = ExpiringMap.class.getDeclaredField("delegateMap");
        delegateField.setAccessible(true);
        ConcurrentHashMap<Object, Object> delegate = (ConcurrentHashMap<Object, Object>) delegateField.get(map);
        Object expiryObject = delegate.get(key);
        assertNotNull(expiryObject, "the mapping must be present before its timestamp is expired");
        Field lastAccessTimeField = expiryObject.getClass().getDeclaredField("lastAccessTime");
        lastAccessTimeField.setAccessible(true);
        ((AtomicLong) lastAccessTimeField.get(expiryObject)).set(0L);
    }

    private static boolean waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    /**
     * Key whose {@code hashCode()} can be paused on a chosen call so that a replacement can be
     * injected between observing an expired mapping and removing it.
     */
    private static final class BlockingKey {
        private final String id;
        private final CountDownLatch paused = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);
        private final AtomicInteger hashCodeCalls = new AtomicInteger();
        private volatile int pauseOnCall = -1;

        private BlockingKey(String id) {
            this.id = id;
        }

        void pauseOnHashCode(int call) {
            hashCodeCalls.set(0);
            pauseOnCall = call;
        }

        void awaitPaused() throws InterruptedException {
            assertTrue(paused.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "hashCode() was not paused in time");
        }

        void resume() {
            resume.countDown();
        }

        void disarm() {
            pauseOnCall = -1;
            resume.countDown();
        }

        @Override
        public int hashCode() {
            if (hashCodeCalls.incrementAndGet() == pauseOnCall) {
                paused.countDown();
                try {
                    if (!resume.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new AssertionError("hashCode() was not resumed in time");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("interrupted while pausing hashCode()", e);
                }
            }
            return id.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj || (obj instanceof BlockingKey && id.equals(((BlockingKey) obj).id));
        }

        @Override
        public String toString() {
            return id;
        }
    }
}
