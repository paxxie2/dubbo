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
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.Result;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.cluster.LoadBalance;
import org.apache.dubbo.rpc.model.ApplicationModel;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class PriorityLoadBalanceSpiTest {

    private static final URL CONSUMER_URL =
            URL.valueOf("consumer://127.0.0.1/org.apache.dubbo.demo.DemoService?loadbalance=priority");

    @Test
    void loadsPriorityLoadBalanceViaExtensionLoaderAndSelectsHigherPriorityWhenListedLast() {
        ExtensionLoader<LoadBalance> loader = ApplicationModel.defaultModel().getExtensionLoader(LoadBalance.class);

        LoadBalance loadBalance = loader.getExtension(LoadbalanceRules.PRIORITY);
        assertInstanceOf(PriorityLoadBalance.class, loadBalance);

        Invoker<Object> standby = new LocalTestInvoker(
                URL.valueOf("dubbo://127.0.0.1:20881/org.apache.dubbo.demo.DemoService?loadbalance.priority=1"));
        Invoker<Object> primary = new LocalTestInvoker(
                URL.valueOf("dubbo://127.0.0.1:20882/org.apache.dubbo.demo.DemoService?loadbalance.priority=10"));

        List<Invoker<Object>> invokers = Arrays.asList(standby, primary);
        RpcInvocation invocation = new RpcInvocation();
        invocation.setMethodName("sayHello");

        assertSame(primary, loadBalance.select(invokers, CONSUMER_URL, invocation));
    }

    @Test
    void existingRandomExtensionAndDefaultRemainIntact() {
        ExtensionLoader<LoadBalance> loader = ApplicationModel.defaultModel().getExtensionLoader(LoadBalance.class);

        LoadBalance random = loader.getExtension(LoadbalanceRules.RANDOM);
        assertInstanceOf(RandomLoadBalance.class, random);
        assertEquals(LoadbalanceRules.RANDOM, loader.getDefaultExtensionName());
        assertInstanceOf(RandomLoadBalance.class, loader.getDefaultExtension());
    }

    private static final class LocalTestInvoker implements Invoker<Object> {

        private final URL url;

        private LocalTestInvoker(URL url) {
            this.url = url;
        }

        @Override
        public Class<Object> getInterface() {
            return Object.class;
        }

        @Override
        public Result invoke(Invocation invocation) throws RpcException {
            throw new UnsupportedOperationException("Selection test should not invoke the provider");
        }

        @Override
        public URL getUrl() {
            return url;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public void destroy() {}
    }
}
