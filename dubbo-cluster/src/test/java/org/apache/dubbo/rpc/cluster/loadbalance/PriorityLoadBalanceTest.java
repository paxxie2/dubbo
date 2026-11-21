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
package org.apache.dubbo.rpc.cluster.loadbalance;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.LoadbalanceRules;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.cluster.LoadBalance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class PriorityLoadBalanceTest {

    private static final URL CONSUMER_URL =
            URL.valueOf("consumer://127.0.0.1/org.apache.dubbo.demo.DemoService?loadbalance=priority");

    @Test
    void nameMatchesLoadbalanceRulesConstant() {
        assertEquals(LoadbalanceRules.PRIORITY, PriorityLoadBalance.NAME);
    }

    @Test
    void handlesEmptyAndSingleCandidateListsNormally() {
        RecordingLoadBalance delegate = new RecordingLoadBalance();
        PriorityLoadBalance lb = new PriorityLoadBalance(delegate);
        Invocation invocation = invocation("sayHello");

        assertNull(lb.select(null, CONSUMER_URL, invocation));
        assertNull(lb.select(Collections.emptyList(), CONSUMER_URL, invocation));
        assertEquals(0, delegate.callCount);

        Invoker<Object> single = invoker("127.0.0.1", 20880, "loadbalance.priority=-5");
        assertSame(single, lb.select(Collections.singletonList(single), CONSUMER_URL, invocation));
        assertEquals(0, delegate.callCount);
    }

    @Test
    void selectsHighestPriorityRegardlessOfCandidateOrderWithoutDelegating() {
        RecordingLoadBalance delegate = new RecordingLoadBalance();
        PriorityLoadBalance lb = new PriorityLoadBalance(delegate);
        Invocation invocation = invocation("sayHello");

        Invoker<Object> low = invoker("127.0.0.1", 20881, "loadbalance.priority=1");
        Invoker<Object> mid = invoker("127.0.0.1", 20882, "loadbalance.priority=5");
        Invoker<Object> high = invoker("127.0.0.1", 20883, "loadbalance.priority=10");

        URL consumerWithConflictingPriority = CONSUMER_URL.addParameter("loadbalance.priority", "999");

        assertSame(high, lb.select(Arrays.asList(high, mid, low), consumerWithConflictingPriority, invocation));
        assertSame(high, lb.select(Arrays.asList(low, high, mid), consumerWithConflictingPriority, invocation));
        assertSame(high, lb.select(Arrays.asList(low, mid, high), consumerWithConflictingPriority, invocation));
        assertSame(high, lb.select(Arrays.asList(mid, low, high), consumerWithConflictingPriority, invocation));
        assertEquals(0, delegate.callCount);
    }

    @Test
    void methodOverrideChangesWinnerIncludingGenericInvocation() {
        RecordingLoadBalance delegate = new RecordingLoadBalance();
        PriorityLoadBalance lb = new PriorityLoadBalance(delegate);

        Invoker<Object> providerA = invoker(
                "127.0.0.1",
                20881,
                "loadbalance.priority=10&special.loadbalance.priority=1&broken.loadbalance.priority=bad");
        Invoker<Object> providerB = invoker(
                "127.0.0.1",
                20882,
                "loadbalance.priority=5&special.loadbalance.priority=20&broken.loadbalance.priority=2");
        List<Invoker<Object>> candidates = Arrays.asList(providerA, providerB);

        assertSame(providerA, lb.select(candidates, CONSUMER_URL, invocation("sayHello")));
        assertSame(providerB, lb.select(candidates, CONSUMER_URL, invocation("special")));
        assertSame(providerB, lb.select(candidates, CONSUMER_URL, genericInvocation("special")));
        // Invalid method override on providerA resolves to 0 (not 10), so providerB (2) wins.
        assertSame(providerB, lb.select(candidates, CONSUMER_URL, invocation("broken")));
        assertEquals(0, delegate.callCount);
    }

    @Test
    void supportsAllNegativeTiersAndSignedIntegerExtremesWithoutOverflowOrZeroFloor() {
        RecordingLoadBalance delegate = new RecordingLoadBalance();
        PriorityLoadBalance lb = new PriorityLoadBalance(delegate);
        Invocation invocation = invocation("sayHello");

        Invoker<Object> neg20 = invoker("127.0.0.1", 20881, "loadbalance.priority=-20");
        Invoker<Object> neg5 = invoker("127.0.0.1", 20882, "loadbalance.priority=-5");
        Invoker<Object> neg10 = invoker("127.0.0.1", 20883, "loadbalance.priority=-10");
        assertSame(neg5, lb.select(Arrays.asList(neg20, neg5, neg10), CONSUMER_URL, invocation));
        assertSame(neg5, lb.select(Arrays.asList(neg10, neg20, neg5), CONSUMER_URL, invocation));

        Invoker<Object> minInt = invoker("127.0.0.1", 20884, "loadbalance.priority=" + Integer.MIN_VALUE);
        Invoker<Object> maxInt = invoker("127.0.0.1", 20885, "loadbalance.priority=" + Integer.MAX_VALUE);
        Invoker<Object> zero = invoker("127.0.0.1", 20886, "");

        assertSame(maxInt, lb.select(Arrays.asList(minInt, maxInt), CONSUMER_URL, invocation));
        assertSame(maxInt, lb.select(Arrays.asList(maxInt, minInt), CONSUMER_URL, invocation));
        assertSame(zero, lb.select(Arrays.asList(minInt, zero), CONSUMER_URL, invocation));
        assertSame(neg5, lb.select(Arrays.asList(minInt, neg5), CONSUMER_URL, invocation));
        assertEquals(0, delegate.callCount);
    }

    @Test
    void removingTopTierImmediatelyPromotesNextTierWithoutCaching() {
        RecordingLoadBalance delegate = new RecordingLoadBalance();
        PriorityLoadBalance lb = new PriorityLoadBalance(delegate);
        Invocation invocation = invocation("sayHello");

        Invoker<Object> primary = invoker("127.0.0.1", 20881, "loadbalance.priority=100");
        Invoker<Object> standbyA = invoker("127.0.0.1", 20882, "loadbalance.priority=50");
        Invoker<Object> standbyB = invoker("127.0.0.1", 20883, "loadbalance.priority=50");
        Invoker<Object> tertiary = invoker("127.0.0.1", 20884, "loadbalance.priority=10");

        List<Invoker<Object>> active = new ArrayList<>(Arrays.asList(tertiary, standbyA, primary, standbyB));
        assertSame(primary, lb.select(active, CONSUMER_URL, invocation));
        assertEquals(0, delegate.callCount);

        // Remove the sole primary: tied standbys become eligible immediately in input order.
        active.remove(primary);
        delegate.returnIndex = 1;
        assertSame(standbyB, lb.select(active, CONSUMER_URL, invocation));
        assertEquals(1, delegate.callCount);
        assertEquals(Arrays.asList(standbyA, standbyB), delegate.lastInvokers);

        // Remove both standbys: tertiary is selected directly without delegation.
        active.remove(standbyA);
        active.remove(standbyB);
        assertSame(tertiary, lb.select(active, CONSUMER_URL, invocation));
        assertEquals(1, delegate.callCount);
    }

    @Test
    void delegatesOnlyTiedTopTierInvokersInInputOrder() {
        RecordingLoadBalance delegate = new RecordingLoadBalance();
        delegate.returnIndex = 1;
        PriorityLoadBalance lb = new PriorityLoadBalance(delegate);
        Invocation invocation = invocation("sayHello");

        Invoker<Object> lowTie1 = invoker("127.0.0.1", 20881, "loadbalance.priority=5");
        Invoker<Object> lowTie2 = invoker("127.0.0.1", 20882, "loadbalance.priority=5");
        Invoker<Object> top1 = invoker("127.0.0.1", 20883, "loadbalance.priority=10");
        Invoker<Object> low = invoker("127.0.0.1", 20884, "loadbalance.priority=-1");
        Invoker<Object> top2 = invoker("127.0.0.1", 20885, "loadbalance.priority=10");
        Invoker<Object> top3 = invoker("127.0.0.1", 20886, "loadbalance.priority=10");

        List<Invoker<Object>> candidates = Arrays.asList(lowTie1, lowTie2, top1, low, top2, top3);
        Invoker<Object> selected = lb.select(candidates, CONSUMER_URL, invocation);

        assertSame(top2, selected);
        assertEquals(1, delegate.callCount);
        assertEquals(Arrays.asList(top1, top2, top3), delegate.lastInvokers);
        assertSame(CONSUMER_URL, delegate.lastUrl);
        assertSame(invocation, delegate.lastInvocation);

        // Reordered tied candidates preserve their new input order.
        delegate.returnIndex = 0;
        List<Invoker<Object>> reordered = Arrays.asList(top3, low, top1, lowTie1, top2);
        Invoker<Object> reorderedSelected = lb.select(reordered, CONSUMER_URL, invocation);

        assertSame(top3, reorderedSelected);
        assertEquals(2, delegate.callCount);
        assertEquals(Arrays.asList(top3, top1, top2), delegate.lastInvokers);
    }

    @Test
    void doesNotDelegateWhenHigherTierOverridesEarlierLowerTie() {
        RecordingLoadBalance delegate = new RecordingLoadBalance();
        PriorityLoadBalance lb = new PriorityLoadBalance(delegate);
        Invocation invocation = invocation("sayHello");

        Invoker<Object> lowerTie1 = invoker("127.0.0.1", 20881, "loadbalance.priority=-10");
        Invoker<Object> lowerTie2 = invoker("127.0.0.1", 20882, "loadbalance.priority=-10");
        Invoker<Object> soleTop = invoker("127.0.0.1", 20883, "loadbalance.priority=-2");

        assertSame(soleTop, lb.select(Arrays.asList(lowerTie1, lowerTie2, soleTop), CONSUMER_URL, invocation));
        assertEquals(0, delegate.callCount);
    }

    @Test
    void doesNotMutateSuppliedCandidateList() {
        RecordingLoadBalance delegate = new RecordingLoadBalance();
        PriorityLoadBalance lb = new PriorityLoadBalance(delegate);
        Invocation invocation = invocation("sayHello");

        Invoker<Object> low = invoker("127.0.0.1", 20881, "loadbalance.priority=1");
        Invoker<Object> high1 = invoker("127.0.0.1", 20882, "loadbalance.priority=10");
        Invoker<Object> high2 = invoker("127.0.0.1", 20883, "loadbalance.priority=10");

        List<Invoker<Object>> backing = new ArrayList<>(Arrays.asList(low, high1, high2));
        List<Invoker<Object>> unmodifiable = Collections.unmodifiableList(backing);

        assertSame(high1, lb.select(unmodifiable, CONSUMER_URL, invocation));
        assertEquals(Arrays.asList(low, high1, high2), backing);
    }

    @Test
    void defaultRandomTieBreakerPreservesWeightBehaviorAmongTiedTopTier() {
        PriorityLoadBalance lb = new PriorityLoadBalance();
        Invocation invocation = invocation("sayHello");

        Invoker<Object> lowerHigherWeight = invoker("127.0.0.1", 20881, "loadbalance.priority=5&weight=1000");
        Invoker<Object> tiedZeroWeight = invoker("127.0.0.1", 20882, "loadbalance.priority=10&weight=0");
        Invoker<Object> tiedPositiveWeight = invoker("127.0.0.1", 20883, "loadbalance.priority=10&weight=100");

        // In RandomLoadBalance, when tied weights are [0, 100], totalWeight=100 and
        // offset in [0, 100) deterministically selects the weight=100 candidate.
        List<Invoker<Object>> candidates = Arrays.asList(lowerHigherWeight, tiedZeroWeight, tiedPositiveWeight);
        for (int i = 0; i < 20; i++) {
            assertSame(tiedPositiveWeight, lb.select(candidates, CONSUMER_URL, invocation));
        }
    }

    @SuppressWarnings("unchecked")
    private static Invoker<Object> invoker(String host, int port, String query) {
        Invoker<Object> invoker = mock(Invoker.class, Mockito.withSettings().stubOnly());
        String rawUrl = "dubbo://" + host + ":" + port + "/org.apache.dubbo.demo.DemoService"
                + (query == null || query.isEmpty() ? "" : "?" + query);
        URL url = URL.valueOf(rawUrl);
        given(invoker.getUrl()).willReturn(url);
        given(invoker.isAvailable()).willReturn(true);
        given(invoker.getInterface()).willReturn(Object.class);
        return invoker;
    }

    private static Invocation invocation(String methodName) {
        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName(methodName);
        return invocation;
    }

    private static Invocation genericInvocation(String targetMethodName) {
        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("$invoke");
        invocation.setArguments(new Object[] {targetMethodName, new String[0], new Object[0]});
        return invocation;
    }

    private static final class RecordingLoadBalance implements LoadBalance {
        int callCount;
        int returnIndex;
        List<Invoker<?>> lastInvokers;
        URL lastUrl;
        Invocation lastInvocation;

        @Override
        public <T> Invoker<T> select(List<Invoker<T>> invokers, URL url, Invocation invocation) {
            this.callCount++;
            this.lastInvokers = new ArrayList<>(invokers);
            this.lastUrl = url;
            this.lastInvocation = invocation;
            return invokers.get(returnIndex);
        }
    }
}
