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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link ExpiringMap} expiration interleaved with a concurrent put of the same key.
 */
class ExpiringMapTest {

    @Test
    void expiredEntryIsRemovedOnGet() throws Exception {
        ExpiringMap<Object, String> map = new ExpiringMap<>(3600, 3600);
        Object key = new Object();
        map.put(key, "stale");
        markExpired(map);

        assertNull(map.get(key));
        assertTrue(map.isEmpty());
    }

    @Test
    void expiredEntryIsRemovedByBackgroundExpiration() throws Exception {
        ExpiringMap<Object, String> map = new ExpiringMap<>(3600, 1);
        Object key = new Object();
        map.put(key, "stale");
        markExpired(map);

        map.getExpireThread().startExpiring();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!map.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(map.isEmpty(), "background expiration should remove the expired entry");
        } finally {
            map.getExpireThread().stopExpiring();
        }
    }

    @Test
    void concurrentReplacementSurvivesGetTimeExpiration() throws Exception {
        ExpiringMap<PausingKey, String> map = new ExpiringMap<>(3600, 3600);
        PausingKey key = new PausingKey();
        map.put(key, "stale");
        markExpired(map);
        // get() hashes once to read the stale entry and a second time to remove it.
        key.armForCall(2);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> observed = executor.submit(() -> map.get(key));
            assertTrue(key.awaitPaused(), "get() should reach the removal of the stale entry");
            map.put(key, "fresh");
            key.resume();
            assertNull(observed.get(10, TimeUnit.SECONDS), "get() should report the observed entry as expired");
            assertEquals("fresh", map.get(key), "get-time expiration must not remove the concurrently replaced value");
        } finally {
            key.resume();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentReplacementSurvivesBackgroundExpiration() throws Exception {
        ExpiringMap<PausingKey, String> map = new ExpiringMap<>(3600, 3600);
        PausingKey key = new PausingKey();
        map.put(key, "stale");
        markExpired(map);
        // processExpires() hashes the observed stale entry when it removes it.
        key.armForCall(1);

        Thread expirer = runProcessExpires(map);
        try {
            assertTrue(key.awaitPaused(), "processExpires() should reach the removal of the stale entry");
            map.put(key, "fresh");
            key.resume();
            expirer.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(expirer.isAlive(), "processExpires() should finish");
            assertEquals(
                    "fresh", map.get(key), "background expiration must not remove the concurrently replaced value");
        } finally {
            key.resume();
            expirer.join(TimeUnit.SECONDS.toMillis(10));
        }
    }

    /**
     * Runs the private {@code processExpires()} pass of the background expirer in its own thread so the
     * test can wait for that single pass to complete instead of racing the daemon thread.
     */
    private static Thread runProcessExpires(final ExpiringMap<?, ?> map) throws Exception {
        final Object expireThread = map.getExpireThread();
        final Method processExpires = expireThread.getClass().getDeclaredMethod("processExpires");
        processExpires.setAccessible(true);
        Thread expirer = new Thread(
                () -> {
                    try {
                        processExpires.invoke(expireThread);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                },
                "ExpiringMap-test-expirer");
        expirer.start();
        return expirer;
    }

    /**
     * Moves every entry of the map into the past without waiting for the real clock.
     */
    private static void markExpired(ExpiringMap<?, ?> map) throws Exception {
        Field delegateMapField = ExpiringMap.class.getDeclaredField("delegateMap");
        delegateMapField.setAccessible(true);
        Map<?, ?> delegateMap = (Map<?, ?>) delegateMapField.get(map);
        for (Object expiryObject : delegateMap.values()) {
            Field lastAccessTimeField = expiryObject.getClass().getDeclaredField("lastAccessTime");
            lastAccessTimeField.setAccessible(true);
            ((AtomicLong) lastAccessTimeField.get(expiryObject)).set(0L);
        }
    }

    /**
     * Key that pauses inside its N-th {@code hashCode()} call after arming, which is the point where
     * {@link ExpiringMap} has already observed the stale entry and is about to remove it.
     */
    private static final class PausingKey {
        private final AtomicInteger hashCalls = new AtomicInteger();
        private final CountDownLatch paused = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);
        private volatile int armedAt;
        private volatile int blockedCall;

        void armForCall(int call) {
            this.armedAt = hashCalls.get();
            this.blockedCall = call;
        }

        boolean awaitPaused() throws InterruptedException {
            return paused.await(10, TimeUnit.SECONDS);
        }

        void resume() {
            resume.countDown();
        }

        @Override
        public int hashCode() {
            int call = hashCalls.incrementAndGet();
            if (blockedCall > 0 && call - armedAt == blockedCall) {
                paused.countDown();
                try {
                    resume.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return 1;
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj;
        }

        @Override
        public String toString() {
            return "expiring-map-race-key";
        }
    }
}
