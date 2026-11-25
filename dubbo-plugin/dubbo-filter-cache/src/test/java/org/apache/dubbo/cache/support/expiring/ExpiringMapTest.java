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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpiringMapTest {

    private static final int TIME_TO_LIVE = 1;

    private static final long PAUSE_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10);

    @Test
    void getDoesNotRemoveConcurrentReplacement() throws Exception {
        ExpiringMap<LatchKey, String> map = new ExpiringMap<>(TIME_TO_LIVE);
        LatchKey key = new LatchKey("get-race");
        map.put(key, "stale");
        awaitExpired();

        // put() hashed the key once; the get-time expiration path hashes it for get() and for remove().
        key.resetHashCalls();
        key.pauseOnHashCall(2);

        Thread expiringGetter = new Thread(() -> map.get(key), "expiring-getter");
        expiringGetter.start();
        try {
            assertTrue(key.awaitPaused(), "get() should observe the stale entry and pause before removing it");

            map.put(key, "fresh");
            key.resume();

            expiringGetter.join(PAUSE_TIMEOUT_MILLIS);
            assertEquals("fresh", map.get(key), "the replacement must survive the get-time expiration");
        } finally {
            key.resume();
        }
    }

    @Test
    void backgroundExpiryDoesNotRemoveConcurrentReplacement() throws Exception {
        ExpiringMap<LatchKey, String> map = new ExpiringMap<>(TIME_TO_LIVE, TIME_TO_LIVE);
        LatchKey key = new LatchKey("background-race");
        map.put(key, "stale");
        // put() hashed the key once; the first background removal hashes it again.
        key.pauseOnHashCall(2);

        map.getExpireThread().startExpiring();
        try {
            assertTrue(
                    key.awaitPaused(), "background expiry should observe the stale entry and pause before removing it");

            map.put(key, "fresh");
            key.resume();

            Thread.sleep(300);
            assertEquals("fresh", map.get(key), "the replacement must survive the background expiration");
        } finally {
            map.getExpireThread().stopExpiring();
            key.resume();
        }
    }

    @Test
    void expiredEntryIsRemoved() throws Exception {
        ExpiringMap<String, String> map = new ExpiringMap<>(TIME_TO_LIVE);
        map.put("key", "value");
        awaitExpired();

        assertNull(map.get("key"));
        assertEquals(0, map.size());
    }

    private static void awaitExpired() throws InterruptedException {
        Thread.sleep(TimeUnit.SECONDS.toMillis(TIME_TO_LIVE) + 200);
    }

    /**
     * Key that can pause the calling thread inside {@link #hashCode()} once it has been called a configured
     * number of times, which widens the window between observing a stale entry and removing it.
     */
    private static final class LatchKey {
        private final String name;
        private final AtomicInteger hashCalls = new AtomicInteger();
        private final CountDownLatch paused = new CountDownLatch(1);
        private final CountDownLatch resumed = new CountDownLatch(1);
        private volatile int pauseOnHashCall = -1;

        LatchKey(String name) {
            this.name = name;
        }

        void resetHashCalls() {
            hashCalls.set(0);
        }

        void pauseOnHashCall(int call) {
            pauseOnHashCall = call;
        }

        boolean awaitPaused() throws InterruptedException {
            return paused.await(PAUSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }

        void resume() {
            resumed.countDown();
        }

        @Override
        public int hashCode() {
            int call = hashCalls.incrementAndGet();
            if (call == pauseOnHashCall) {
                paused.countDown();
                try {
                    if (!resumed.await(PAUSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException("timed out waiting to resume hashCode");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while paused in hashCode", e);
                }
            }
            return name.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof LatchKey && name.equals(((LatchKey) obj).name);
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
