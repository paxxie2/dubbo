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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpiringMapTest {

    private static final long AWAIT_TIMEOUT_SECONDS = 30;

    @Test
    void getExpiryKeepsReplacementPutAfterStaleObservation() throws Exception {
        ExpiringMap<PausingKey, String> map = new ExpiringMap<>(1, 1);
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        PausingKey key = new PausingKey("get-path", paused, resume);
        map.put(key, "stale");
        makeExpired(map, key);
        // The expiring get looks the key up once before it tries to remove the observed entry.
        key.armPauseOnCall(2);

        AtomicReference<String> observed = new AtomicReference<>();
        Thread expiration = new Thread(() -> observed.set(map.get(key)), "get-expiry");
        expiration.start();
        await(paused);

        map.put(key, "fresh");
        resume.countDown();
        expiration.join(TimeUnit.SECONDS.toMillis(AWAIT_TIMEOUT_SECONDS));
        assertFalse(expiration.isAlive(), "the expiring get did not finish");

        assertNull(observed.get(), "the overlapping get must not return the expired value");
        assertEquals("fresh", map.get(key), "get expiry must not remove a concurrent replacement");
        assertEquals(1, map.size());
    }

    @Test
    void backgroundExpiryKeepsReplacementPutAfterStaleObservation() throws Exception {
        ExpiringMap<PausingKey, String> map = new ExpiringMap<>(1, 1);
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        PausingKey key = new PausingKey("background-path", paused, resume);
        map.put(key, "stale");
        makeExpired(map, key);
        // The background pass observes the expired entry first and only then removes it.
        key.armPauseOnCall(1);

        Thread expiration = new Thread(() -> invokeProcessExpires(map), "background-expiry");
        expiration.start();
        await(paused);

        map.put(key, "fresh");
        resume.countDown();
        expiration.join(TimeUnit.SECONDS.toMillis(AWAIT_TIMEOUT_SECONDS));
        assertFalse(expiration.isAlive(), "the background expiry pass did not finish");

        assertEquals("fresh", map.get(key), "background expiry must not remove a concurrent replacement");
        assertEquals(1, map.size());
    }

    @Test
    void getRemovesExpiredEntry() throws Exception {
        ExpiringMap<String, String> map = new ExpiringMap<>(1, 1);
        map.put("key", "value");
        assertNotNull(map.get("key"));

        makeExpired(map, "key");
        assertNull(map.get("key"));
        assertTrue(map.isEmpty());
    }

    @Test
    void backgroundExpiryRemovesExpiredEntry() throws Exception {
        ExpiringMap<String, String> map = new ExpiringMap<>(1, 1);
        map.put("key", "value");

        makeExpired(map, "key");
        invokeProcessExpires(map);
        assertTrue(map.isEmpty());
    }

    @Test
    void getKeepsUnexpiredEntry() {
        ExpiringMap<String, String> map = new ExpiringMap<>(1, 1);
        map.put("key", "value");

        assertEquals("value", map.get("key"));
        assertEquals(1, map.size());
    }

    @Test
    void backgroundExpiryKeepsUnexpiredEntry() {
        ExpiringMap<String, String> map = new ExpiringMap<>(1, 1);
        map.put("key", "value");

        invokeProcessExpires(map);
        assertEquals("value", map.get("key"));
        assertEquals(1, map.size());
    }

    @SuppressWarnings("unchecked")
    private static void makeExpired(ExpiringMap<?, ?> map, Object key) throws Exception {
        Field delegateField = ExpiringMap.class.getDeclaredField("delegateMap");
        delegateField.setAccessible(true);
        ConcurrentHashMap<Object, Object> delegate = (ConcurrentHashMap<Object, Object>) delegateField.get(map);
        Object expiryObject = null;
        for (Map.Entry<Object, Object> entry : delegate.entrySet()) {
            if (entry.getKey() == key) {
                expiryObject = entry.getValue();
                break;
            }
        }
        assertNotNull(expiryObject, "expected the key to be cached");

        Field lastAccessTimeField = expiryObject.getClass().getDeclaredField("lastAccessTime");
        lastAccessTimeField.setAccessible(true);
        ((AtomicLong) lastAccessTimeField.get(expiryObject)).set(0L);
    }

    private static void invokeProcessExpires(ExpiringMap<?, ?> map) {
        try {
            Object expireThread = map.getExpireThread();
            Method processExpires = expireThread.getClass().getDeclaredMethod("processExpires");
            processExpires.setAccessible(true);
            processExpires.invoke(expireThread);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to run the background expiry pass", e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for the concurrent expiration step");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the concurrent expiration step", e);
        }
    }

    /**
     * Key that blocks a chosen {@link #hashCode()} call so a test can freeze an expiration check
     * after it observed an expired entry and before it removes that entry.
     */
    private static final class PausingKey {

        private final String name;
        private final CountDownLatch paused;
        private final CountDownLatch resume;
        private final AtomicInteger hashCodeCalls = new AtomicInteger();
        private volatile int pauseOnCall;

        private PausingKey(String name, CountDownLatch paused, CountDownLatch resume) {
            this.name = name;
            this.paused = paused;
            this.resume = resume;
        }

        private void armPauseOnCall(int call) {
            hashCodeCalls.set(0);
            pauseOnCall = call;
        }

        @Override
        public int hashCode() {
            int call = hashCodeCalls.incrementAndGet();
            if (call == pauseOnCall) {
                paused.countDown();
                await(resume);
            }
            return name.hashCode();
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
