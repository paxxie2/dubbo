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

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for the expiry behavior of {@link ExpiringMap}.
 *
 * <p>Both the get-time expiry and the background expiry must remove only the entry they observed
 * as stale. A value that another thread put in the meantime must survive the expiry pass.
 */
class ExpiringMapTest {

    private static final int TIME_TO_LIVE_SECONDS = 1;

    /**
     * Interval of the background expiry thread used by tests that drive the expiry themselves. It
     * is long enough that the background thread never expires anything on its own.
     */
    private static final int IDLE_EXPIRATION_INTERVAL_SECONDS = 3600;

    private static final long WAIT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(30L);

    @Test
    void testGetTimeExpiryRemovesOnlyObservedStaleEntry() throws Exception {
        ExpiringMap<Object, String> map = new ExpiringMap<>(TIME_TO_LIVE_SECONDS, IDLE_EXPIRATION_INTERVAL_SECONDS);
        BlockingKey key = new BlockingKey("get-time-expiry-race");
        map.put(key, "stale");
        Thread.sleep(TIME_TO_LIVE_SECONDS * 1000L + 500L);

        // Reading the stale entry is the first hashCode() call of this thread, removing the
        // observed entry is the second one. Blocking the second call forces the interleaving in
        // which the value is replaced between observing the stale entry and removing it.
        key.blockAfterHashCodeCalls(Thread.currentThread(), 1);

        CountDownLatch replacementDone = new CountDownLatch(1);
        Thread replacer = new Thread(
                () -> {
                    try {
                        if (key.awaitBlockedCall(WAIT_TIMEOUT_MILLIS)) {
                            map.put(key, "fresh");
                            replacementDone.countDown();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        key.releaseBlockedCall();
                    }
                },
                "expiring-map-test-replacer");
        replacer.start();

        assertNull(map.get(key), "get must still report the observed stale entry as expired");
        assertTrue(replacementDone.await(WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), "replacement must be written");
        replacer.join(WAIT_TIMEOUT_MILLIS);
        assertEquals("fresh", map.get(key), "expiry must keep the value that replaced the observed stale entry");
    }

    @Test
    void testBackgroundExpiryRemovesOnlyObservedStaleEntry() throws Exception {
        ExpiringMap<Object, String> map = new ExpiringMap<>(TIME_TO_LIVE_SECONDS, IDLE_EXPIRATION_INTERVAL_SECONDS);
        BlockingKey key = new BlockingKey("background-expiry-race");
        map.put(key, "stale");
        Thread.sleep(TIME_TO_LIVE_SECONDS * 1000L + 500L);

        Set<Thread> expiryThreadsBefore = expiryThreads();

        // The removal of the observed stale entry is the first hashCode() call of the expiry
        // thread. Blocking it forces the interleaving in which the value is replaced between
        // observing the stale entry and removing it.
        key.blockAfterHashCodeCalls(null, 0);
        map.getExpireThread().startExpiring();
        assertTrue(key.awaitBlockedCall(WAIT_TIMEOUT_MILLIS), "the expiry thread must reach the expiry removal");
        Thread expiryThread = awaitNewExpiryThread(expiryThreadsBefore);

        map.put(key, "fresh");
        key.releaseBlockedCall();
        map.getExpireThread().stopExpiring();
        expiryThread.join(WAIT_TIMEOUT_MILLIS);
        assertFalse(expiryThread.isAlive(), "the expiry thread must finish its expiration pass");
        assertEquals(
                "fresh", map.get(key), "background expiry must keep the value that replaced the observed stale entry");
    }

    @Test
    void testOrdinaryExpiryOnGet() throws Exception {
        ExpiringMap<Object, String> map = new ExpiringMap<>(TIME_TO_LIVE_SECONDS, IDLE_EXPIRATION_INTERVAL_SECONDS);
        map.put("key", "value");
        Thread.sleep(TIME_TO_LIVE_SECONDS * 1000L + 500L);

        assertNull(map.get("key"));
        assertFalse(map.containsKey("key"));
    }

    @Test
    void testOrdinaryBackgroundExpiry() throws Exception {
        ExpiringMap<Object, String> map = new ExpiringMap<>(TIME_TO_LIVE_SECONDS, TIME_TO_LIVE_SECONDS);
        map.put("key", "value");
        map.getExpireThread().startExpiryIfNotStarted();
        try {
            long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MILLIS;
            while (map.containsKey("key") && System.currentTimeMillis() < deadline) {
                Thread.sleep(50L);
            }
            assertFalse(map.containsKey("key"), "the background expiry must remove the expired entry");
        } finally {
            map.getExpireThread().stopExpiring();
        }
    }

    private static Set<Thread> expiryThreads() {
        Set<Thread> threads = new HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("ExpiryMapExpire-")) {
                threads.add(thread);
            }
        }
        return threads;
    }

    private static Thread awaitNewExpiryThread(Set<Thread> before) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            Set<Thread> current = expiryThreads();
            current.removeAll(before);
            if (current.size() == 1) {
                return current.iterator().next();
            }
            if (current.size() > 1) {
                fail("expected exactly one new expiry thread but found " + current.size());
            }
            Thread.sleep(10L);
        }
        return fail("the new expiry thread could not be observed");
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Key whose {@link #hashCode()} can be blocked deterministically.
     *
     * <p>An expiry removal has to hash the key before it can remove the mapping, so blocking the
     * hash call parks the expiring thread exactly between observing a stale entry and removing it.
     * That lets a test replace the value at that point instead of hoping for a scheduling race.
     */
    private static final class BlockingKey {

        private final String name;
        private final AtomicInteger hashCodeCalls = new AtomicInteger();
        private final CountDownLatch blockedCallEntered = new CountDownLatch(1);
        private final CountDownLatch blockedCallReleased = new CountDownLatch(1);

        private volatile Thread countedThread;
        private volatile int callsBeforeBlocking;
        private volatile boolean armed;
        private volatile boolean blockedCallReached;

        BlockingKey(String name) {
            this.name = name;
        }

        /**
         * Arms the key so that the {@code callsToSkip + 1}-th {@link #hashCode()} call made by
         * {@code thread} (or by any thread when {@code thread} is {@code null}) blocks until
         * {@link #releaseBlockedCall()} is invoked.
         */
        void blockAfterHashCodeCalls(Thread thread, int callsToSkip) {
            this.countedThread = thread;
            this.callsBeforeBlocking = callsToSkip;
            this.hashCodeCalls.set(0);
            this.armed = true;
        }

        boolean awaitBlockedCall(long timeoutMillis) throws InterruptedException {
            return blockedCallEntered.await(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        void releaseBlockedCall() {
            blockedCallReleased.countDown();
        }

        @Override
        public int hashCode() {
            if (armed && !blockedCallReached) {
                Thread counted = countedThread;
                if (counted == null || counted == Thread.currentThread()) {
                    if (hashCodeCalls.incrementAndGet() > callsBeforeBlocking) {
                        blockedCallReached = true;
                        blockedCallEntered.countDown();
                        awaitUninterruptibly(blockedCallReleased);
                    }
                }
            }
            return name.hashCode();
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
