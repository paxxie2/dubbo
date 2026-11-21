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
import org.apache.dubbo.common.utils.ProviderPriority;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Priority-tier load balancer that selects from the highest priority tier represented in the
 * supplied candidate list.
 *
 * <p>Each candidate's priority is resolved from its own provider {@link URL} via
 * {@link ProviderPriority#resolve(URL, String)}. Priorities are compared as signed 32-bit integers
 * with no zero floor. A unique highest-priority candidate is returned directly; ties within the
 * highest tier are delegated in input order to {@link RandomLoadBalance} (preserving its weight
 * and warmup behavior).
 */
public class PriorityLoadBalance extends AbstractLoadBalance {

    public static final String NAME = LoadbalanceRules.PRIORITY;

    private final LoadBalance tieBreaker;

    public PriorityLoadBalance() {
        this(new RandomLoadBalance());
    }

    PriorityLoadBalance(LoadBalance tieBreaker) {
        this.tieBreaker = Objects.requireNonNull(tieBreaker, "tieBreaker must not be null");
    }

    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        String methodName = invocation == null ? null : RpcUtils.getMethodName(invocation);

        Invoker<T> soleWinner = invokers.get(0);
        int highestPriority = ProviderPriority.resolve(soleWinner.getUrl(), methodName);
        List<Invoker<T>> tied = null;

        int size = invokers.size();
        for (int i = 1; i < size; i++) {
            Invoker<T> candidate = invokers.get(i);
            int priority = ProviderPriority.resolve(candidate.getUrl(), methodName);
            if (priority > highestPriority) {
                highestPriority = priority;
                soleWinner = candidate;
                if (tied != null) {
                    tied.clear();
                }
            } else if (priority == highestPriority) {
                if (tied == null) {
                    tied = new ArrayList<>(size - i + 1);
                }
                if (tied.isEmpty()) {
                    tied.add(soleWinner);
                }
                tied.add(candidate);
            }
        }

        if (tied == null || tied.isEmpty()) {
            return soleWinner;
        }
        return tieBreaker.select(tied, url, invocation);
    }
}
