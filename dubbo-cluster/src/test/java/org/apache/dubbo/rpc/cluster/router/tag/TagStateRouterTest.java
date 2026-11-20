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
package org.apache.dubbo.rpc.cluster.router.tag;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.beans.factory.ScopeBeanFactory;
import org.apache.dubbo.common.utils.Holder;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.cluster.router.MockInvoker;
import org.apache.dubbo.rpc.cluster.router.state.BitList;
import org.apache.dubbo.rpc.cluster.router.state.StateRouter;
import org.apache.dubbo.rpc.cluster.router.tag.model.TagRouterRule;
import org.apache.dubbo.rpc.cluster.router.tag.model.TagRuleParser;
import org.apache.dubbo.rpc.cluster.support.merger.DefaultProviderURLMergeProcessor;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.ModuleModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.Sets;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.BACKUP_TAG_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TAG_KEY;
import static org.apache.dubbo.rpc.Constants.FORCE_USE_TAG;
import static org.mockito.Mockito.when;

class TagStateRouterTest {
    private URL url;
    private ModuleModel originModel;
    private ModuleModel moduleModel;

    @BeforeEach
    public void setup() {
        originModel = ApplicationModel.defaultModel().getDefaultModule();
        moduleModel = Mockito.spy(originModel);

        ScopeBeanFactory originBeanFactory = originModel.getBeanFactory();
        ScopeBeanFactory beanFactory = Mockito.spy(originBeanFactory);
        when(moduleModel.getBeanFactory()).thenReturn(beanFactory);

        url = URL.valueOf("test://localhost/DemoInterface").setScopeModel(moduleModel);
    }

    @Test
    void testTagRoutePickInvokers() {
        StateRouter router = new TagStateRouterFactory().getRouter(TagRouterRule.class, url);

        List<Invoker<String>> originInvokers = new ArrayList<>();

        URL url1 = URL.valueOf("test://127.0.0.1:7777/DemoInterface?dubbo.tag=tag2")
                .setScopeModel(moduleModel);
        URL url2 = URL.valueOf("test://127.0.0.1:7778/DemoInterface").setScopeModel(moduleModel);
        URL url3 = URL.valueOf("test://127.0.0.1:7779/DemoInterface").setScopeModel(moduleModel);
        Invoker<String> invoker1 = new MockInvoker<>(url1, true);
        Invoker<String> invoker2 = new MockInvoker<>(url2, true);
        Invoker<String> invoker3 = new MockInvoker<>(url3, true);
        originInvokers.add(invoker1);
        originInvokers.add(invoker2);
        originInvokers.add(invoker3);
        BitList<Invoker<String>> invokers = new BitList<>(originInvokers);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setAttachment(TAG_KEY, "tag2");
        List<Invoker<String>> filteredInvokers =
                router.route(invokers.clone(), invokers.get(0).getUrl(), invocation, false, new Holder<>());
        Assertions.assertEquals(1, filteredInvokers.size());
        Assertions.assertEquals(invoker1, filteredInvokers.get(0));
    }

    @Test
    void testTagRouteWithDynamicRuleV3() {
        TagStateRouter router = (TagStateRouter) new TagStateRouterFactory().getRouter(TagRouterRule.class, url);
        router = Mockito.spy(router);

        List<Invoker<String>> originInvokers = new ArrayList<>();

        URL url1 = URL.valueOf("test://127.0.0.1:7777/DemoInterface?application=foo&dubbo.tag=tag2&match_key=value")
                .setScopeModel(moduleModel);
        URL url2 = URL.valueOf("test://127.0.0.1:7778/DemoInterface?application=foo&match_key=value")
                .setScopeModel(moduleModel);
        URL url3 = URL.valueOf("test://127.0.0.1:7779/DemoInterface?application=foo")
                .setScopeModel(moduleModel);
        Invoker<String> invoker1 = new MockInvoker<>(url1, true);
        Invoker<String> invoker2 = new MockInvoker<>(url2, true);
        Invoker<String> invoker3 = new MockInvoker<>(url3, true);
        originInvokers.add(invoker1);
        originInvokers.add(invoker2);
        originInvokers.add(invoker3);
        BitList<Invoker<String>> invokers = new BitList<>(originInvokers);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setAttachment(TAG_KEY, "tag2");
        TagRouterRule rule = getTagRule();
        Mockito.when(router.getInvokers()).thenReturn(invokers);
        rule.init(router);
        router.setTagRouterRule(rule);
        List<Invoker<String>> filteredInvokers =
                router.route(invokers, invokers.get(0).getUrl(), invocation, false, new Holder<>());
        Assertions.assertEquals(2, filteredInvokers.size());
        //        Assertions.(invoker1, filteredInvokers.get(0));
    }

    /**
     * TagRouterRule parse test when the tags addresses is null
     *
     * <pre>
     *     ~ -> null
     *     null -> null
     * </pre>
     */
    @Test
    void tagRouterRuleParseTest() {
        String tagRouterRuleConfig = "---\n" + "force: false\n"
                + "runtime: true\n"
                + "enabled: false\n"
                + "priority: 1\n"
                + "key: demo-provider\n"
                + "tags:\n"
                + "  - name: tag1\n"
                + "    addresses: null\n"
                + "  - name: tag2\n"
                + "    addresses: [\"30.5.120.37:20880\"]\n"
                + "  - name: tag3\n"
                + "    addresses: []\n"
                + "  - name: tag4\n"
                + "    addresses: ~\n"
                + "...";

        TagRouterRule tagRouterRule = TagRuleParser.parse(tagRouterRuleConfig);
        TagStateRouter<?> router = Mockito.mock(TagStateRouter.class);
        Mockito.when(router.getInvokers()).thenReturn(BitList.emptyList());
        tagRouterRule.init(router);

        // assert tags
        assert tagRouterRule.getKey().equals("demo-provider");
        assert tagRouterRule.getPriority() == 1;
        assert tagRouterRule.getTagNames().contains("tag1");
        assert tagRouterRule.getTagNames().contains("tag2");
        assert tagRouterRule.getTagNames().contains("tag3");
        assert tagRouterRule.getTagNames().contains("tag4");
        // assert addresses
        assert tagRouterRule.getAddresses().contains("30.5.120.37:20880");
        assert tagRouterRule.getTagnameToAddresses().get("tag1") == null;
        assert tagRouterRule.getTagnameToAddresses().get("tag2").size() == 1;
        assert tagRouterRule.getTagnameToAddresses().get("tag3") == null;
        assert tagRouterRule.getTagnameToAddresses().get("tag4") == null;
        assert tagRouterRule.getAddresses().size() == 1;
    }

    @Test
    void tagRouterRuleParseTestV3() {
        String tagRouterRuleConfig = "---\n" + "configVersion: v3.0\n"
                + "force: false\n"
                + "runtime: true\n"
                + "enabled: true\n"
                + "priority: 1\n"
                + "key: demo-provider\n"
                + "tags:\n"
                + "  - name: tag1\n"
                + "    match:\n"
                + "    - key: match_key1\n"
                + "      value:\n"
                + "       exact: value1\n"
                + "  - name: tag2\n"
                + "    addresses:\n"
                + "     - \"10.20.3.3:20880\"\n"
                + "     - \"10.20.3.4:20880\"\n"
                + "    match:\n"
                + "    - key: match_key2\n"
                + "      value:\n"
                + "       exact: value2\n"
                + "  - name: tag3\n"
                + "    match:\n"
                + "    - key: match_key2\n"
                + "      value:\n"
                + "       exact: value2\n"
                + "  - name: tag4\n"
                + "    match:\n"
                + "    - key: not_exist\n"
                + "      value:\n"
                + "       exact: not_exist\n"
                + "  - name: tag5\n"
                + "    match:\n"
                + "    - key: match_key2\n"
                + "      value:\n"
                + "       wildcard: \"*\"\n"
                + "...";

        TagRouterRule tagRouterRule = TagRuleParser.parse(tagRouterRuleConfig);
        TagStateRouter<String> router = Mockito.mock(TagStateRouter.class);
        Mockito.when(router.getInvokers()).thenReturn(getInvokers());
        tagRouterRule.init(router);

        // assert tags
        assert tagRouterRule.getKey().equals("demo-provider");
        assert tagRouterRule.getPriority() == 1;
        assert tagRouterRule.getTagNames().contains("tag1");
        assert tagRouterRule.getTagNames().contains("tag2");
        assert tagRouterRule.getTagNames().contains("tag3");
        assert tagRouterRule.getTagNames().contains("tag4");
        // assert addresses
        assert tagRouterRule.getAddresses().size() == 2;
        assert tagRouterRule.getAddresses().contains("10.20.3.3:20880");
        assert tagRouterRule.getTagnameToAddresses().get("tag1").size() == 2;
        assert tagRouterRule.getTagnameToAddresses().get("tag2").size() == 2;
        assert tagRouterRule.getTagnameToAddresses().get("tag3").size() == 1;
        assert tagRouterRule.getTagnameToAddresses().get("tag5").size() == 1;
        assert tagRouterRule.getTagnameToAddresses().get("tag4") == null;
    }

    public BitList<Invoker<String>> getInvokers() {
        List<Invoker<String>> originInvokers = new ArrayList<Invoker<String>>();
        Invoker<String> invoker1 = new MockInvoker<String>(
                URL.valueOf("dubbo://10.20.3.3:20880/com.foo.BarService?match_key1=value1&match_key2=value2"));
        Invoker<String> invoker2 =
                new MockInvoker<String>(URL.valueOf("dubbo://10.20.3.4:20880/com.foo.BarService?match_key1=value1"));
        originInvokers.add(invoker1);
        originInvokers.add(invoker2);
        BitList<Invoker<String>> invokers = new BitList<>(originInvokers);
        return invokers;
    }

    private TagRouterRule getTagRule() {
        String tagRouterRuleConfig = "---\n" + "configVersion: v3.0\n"
                + "force: false\n"
                + "runtime: true\n"
                + "enabled: true\n"
                + "priority: 1\n"
                + "key: demo-provider\n"
                + "tags:\n"
                + "  - name: tag2\n"
                + "    match:\n"
                + "    - key: match_key\n"
                + "      value:\n"
                + "       exact: value\n"
                + "...";

        TagRouterRule tagRouterRule = TagRuleParser.parse(tagRouterRuleConfig);
        return tagRouterRule;
    }

    @Test
    public void tagMultiLevelTest() {
        String tagSelector = "beta|team1|partner1";
        Set<String> address1 = Sets.newHashSet("192.168.5.1:20880");
        Map<String, Set<String>> tagAddresses = new HashMap<>();
        tagAddresses.put("beta", address1);
        Assertions.assertEquals(address1, TagStateRouter.selectAddressByTagLevel(tagAddresses, tagSelector, false));

        Set<String> address2 = Sets.newHashSet("192.168.5.2:20880");
        tagAddresses.put("beta|team1", address2);
        Assertions.assertEquals(address2, TagStateRouter.selectAddressByTagLevel(tagAddresses, tagSelector, false));

        Set<String> address3 = Sets.newHashSet("192.168.5.3:20880");
        tagAddresses.put("beta|team1|partner1", address3);
        Assertions.assertEquals(address3, TagStateRouter.selectAddressByTagLevel(tagAddresses, tagSelector, false));

        tagSelector = "beta";
        Assertions.assertEquals(address1, TagStateRouter.selectAddressByTagLevel(tagAddresses, tagSelector, false));
        tagSelector = "beta|team1";
        Assertions.assertEquals(address2, TagStateRouter.selectAddressByTagLevel(tagAddresses, tagSelector, false));
        tagSelector = "beta|team1|partner1";
        Assertions.assertEquals(address3, TagStateRouter.selectAddressByTagLevel(tagAddresses, tagSelector, false));

        tagSelector = "beta2";
        Assertions.assertNull(TagStateRouter.selectAddressByTagLevel(tagAddresses, tagSelector, false));
        tagSelector = "beta|team2";
        Assertions.assertEquals(address1, TagStateRouter.selectAddressByTagLevel(tagAddresses, tagSelector, false));
        tagSelector = "beta|team1|partner2";
        Assertions.assertEquals(address2, TagStateRouter.selectAddressByTagLevel(tagAddresses, tagSelector, false));
    }

    @Test
    public void tagLevelForceTest() {
        Set<String> addresses = Sets.newHashSet("192.168.1.223:20880");
        Map<String, Set<String>> tagAddresses = new HashMap<>();
        tagAddresses.put("beta", addresses);
        Set<String> selectedAddresses = TagStateRouter.selectAddressByTagLevel(tagAddresses, "beta", true);
        Assertions.assertEquals(addresses, selectedAddresses);
    }

    @Test
    void testTagRouteWithWildcardShouldReturnAllProviders() {
        StateRouter<TagRouterRule> router = new TagStateRouterFactory().getRouter(TagRouterRule.class, url);

        List<Invoker<TagRouterRule>> originInvokers = new ArrayList<>();

        URL url1 = URL.valueOf("test://127.0.0.1:7777/DemoInterface?dubbo.tag=t-01")
                .setScopeModel(moduleModel);
        URL url2 = URL.valueOf("test://127.0.0.1:7778/DemoInterface?dubbo.tag=t-02")
                .setScopeModel(moduleModel);
        URL url3 = URL.valueOf("test://127.0.0.1:7779/DemoInterface?dubbo.tag=t-03")
                .setScopeModel(moduleModel);
        URL url4 = URL.valueOf("test://127.0.0.1:7780/DemoInterface?dubbo.tag=t-04")
                .setScopeModel(moduleModel);

        Invoker<TagRouterRule> invoker1 = new MockInvoker<>(url1, true);
        Invoker<TagRouterRule> invoker2 = new MockInvoker<>(url2, true);
        Invoker<TagRouterRule> invoker3 = new MockInvoker<>(url3, true);
        Invoker<TagRouterRule> invoker4 = new MockInvoker<>(url4, true);
        originInvokers.add(invoker1);
        originInvokers.add(invoker2);
        originInvokers.add(invoker3);
        originInvokers.add(invoker4);
        BitList<Invoker<TagRouterRule>> invokers = new BitList<>(originInvokers);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setAttachment(TAG_KEY, ANY_VALUE);

        List<Invoker<TagRouterRule>> filteredInvokers =
                router.route(invokers.clone(), invokers.get(0).getUrl(), invocation, false, new Holder<>());

        Assertions.assertEquals(4, filteredInvokers.size());
        Assertions.assertEquals(invoker1, filteredInvokers.get(0));
        Assertions.assertEquals(invoker2, filteredInvokers.get(1));
        Assertions.assertEquals(invoker3, filteredInvokers.get(2));
        Assertions.assertEquals(invoker4, filteredInvokers.get(3));
    }

    @Test
    void testTagRouteWithDynamicRuleWildcardShouldReturnAllProviders() {
        TagStateRouter router = (TagStateRouter) new TagStateRouterFactory().getRouter(TagRouterRule.class, url);
        router = Mockito.spy(router);

        List<Invoker<String>> originInvokers = new ArrayList<>();

        URL url1 = URL.valueOf("test://127.0.0.1:7777/DemoInterface?application=foo&dubbo.tag=tag2&match_key=value")
                .setScopeModel(moduleModel);
        URL url2 = URL.valueOf("test://127.0.0.1:7778/DemoInterface?application=foo&match_key=value")
                .setScopeModel(moduleModel);
        URL url3 = URL.valueOf("test://127.0.0.1:7779/DemoInterface?application=foo")
                .setScopeModel(moduleModel);
        Invoker<String> invoker1 = new MockInvoker<>(url1, true);
        Invoker<String> invoker2 = new MockInvoker<>(url2, true);
        Invoker<String> invoker3 = new MockInvoker<>(url3, true);
        originInvokers.add(invoker1);
        originInvokers.add(invoker2);
        originInvokers.add(invoker3);
        BitList<Invoker<String>> invokers = new BitList<>(originInvokers);

        RpcInvocation invocation = new RpcInvocation();
        invocation.setAttachment(TAG_KEY, ANY_VALUE);
        TagRouterRule rule = getTagRule();
        Mockito.when(router.getInvokers()).thenReturn(invokers);
        rule.init(router);
        router.setTagRouterRule(rule);
        List<Invoker<String>> filteredInvokers = router.route(invokers.clone(), url, invocation, false, new Holder<>());

        Assertions.assertEquals(3, filteredInvokers.size());
        Assertions.assertEquals(invoker1, filteredInvokers.get(0));
        Assertions.assertEquals(invoker2, filteredInvokers.get(1));
        Assertions.assertEquals(invoker3, filteredInvokers.get(2));
    }

    @Test
    void testBackupTagFailoverAndRecovery() {
        StateRouter<String> router = new TagStateRouterFactory().getRouter(String.class, url);

        Invoker<String> serviceB = new MockInvoker<>(
                URL.valueOf("test://127.0.0.1:7771/DemoInterface?dubbo.tag=B").setScopeModel(moduleModel), true);
        Invoker<String> serviceANormal = new MockInvoker<>(
                URL.valueOf("test://127.0.0.1:7772/DemoInterface?dubbo.tag=A").setScopeModel(moduleModel), true);
        Invoker<String> serviceABackup1 = new MockInvoker<>(
                URL.valueOf("test://127.0.0.1:7773/DemoInterface?dubbo.tag=A&dubbo.backup.tag=B")
                        .setScopeModel(moduleModel),
                true);
        Invoker<String> serviceABackup2 = new MockInvoker<>(
                URL.valueOf("test://127.0.0.1:7774/DemoInterface?dubbo.tag=A&dubbo.backup.tag=B")
                        .setScopeModel(moduleModel),
                true);
        Invoker<String> untagged = new MockInvoker<>(
                URL.valueOf("test://127.0.0.1:7775/DemoInterface").setScopeModel(moduleModel), true);

        BitList<Invoker<String>> allOnline =
                new BitList<>(Arrays.asList(serviceB, serviceANormal, serviceABackup1, serviceABackup2, untagged));

        // 1. All online: request dubbo.tag=B -> selects ONLY Service B (backup NOT used)
        RpcInvocation tagBInvocation = new RpcInvocation();
        tagBInvocation.setAttachment(TAG_KEY, "B");
        List<Invoker<String>> routedAllOnline =
                router.route(allOnline.clone(), url, tagBInvocation, false, new Holder<>());
        Assertions.assertEquals(Collections.singletonList(serviceB), new ArrayList<>(routedAllOnline));

        // 2. Service B offline: request dubbo.tag=B -> selects ONLY Service A backup machines
        BitList<Invoker<String>> serviceBOffline =
                new BitList<>(Arrays.asList(serviceANormal, serviceABackup1, serviceABackup2, untagged));
        List<Invoker<String>> routedBackup =
                router.route(serviceBOffline.clone(), url, tagBInvocation, false, new Holder<>());
        Assertions.assertEquals(Arrays.asList(serviceABackup1, serviceABackup2), new ArrayList<>(routedBackup));

        // 3. Service B recovers: request dubbo.tag=B -> switches strictly back to Service B
        BitList<Invoker<String>> serviceBRecovered =
                new BitList<>(Arrays.asList(serviceB, serviceANormal, serviceABackup1, serviceABackup2, untagged));
        List<Invoker<String>> routedRecovered =
                router.route(serviceBRecovered.clone(), url, tagBInvocation, false, new Holder<>());
        Assertions.assertEquals(Collections.singletonList(serviceB), new ArrayList<>(routedRecovered));

        // 4. Service A traffic still routes to all Service A machines (including backup ones)
        RpcInvocation tagAInvocation = new RpcInvocation();
        tagAInvocation.setAttachment(TAG_KEY, "A");
        List<Invoker<String>> routedTagA = router.route(allOnline.clone(), url, tagAInvocation, false, new Holder<>());
        Assertions.assertEquals(
                Arrays.asList(serviceANormal, serviceABackup1, serviceABackup2), new ArrayList<>(routedTagA));

        // 5. Untagged request routes to untagged providers even if they carry dubbo.backup.tag=B
        Invoker<String> untaggedWithBackup = new MockInvoker<>(
                URL.valueOf("test://127.0.0.1:7776/DemoInterface?dubbo.backup.tag=B")
                        .setScopeModel(moduleModel),
                true);
        BitList<Invoker<String>> withUntaggedBackup = new BitList<>(Arrays.asList(
                serviceB, serviceANormal, serviceABackup1, serviceABackup2, untagged, untaggedWithBackup));
        RpcInvocation untaggedInvocation = new RpcInvocation();
        List<Invoker<String>> routedUntagged =
                router.route(withUntaggedBackup.clone(), url, untaggedInvocation, false, new Holder<>());
        Assertions.assertEquals(Arrays.asList(untagged, untaggedWithBackup), new ArrayList<>(routedUntagged));
    }

    @Test
    void testBackupTagWithForceSemantics() {
        StateRouter<String> router = new TagStateRouterFactory().getRouter(String.class, url);

        Invoker<String> serviceANormal = new MockInvoker<>(
                URL.valueOf("test://127.0.0.1:7772/DemoInterface?dubbo.tag=A").setScopeModel(moduleModel), true);
        Invoker<String> serviceABackup = new MockInvoker<>(
                URL.valueOf("test://127.0.0.1:7773/DemoInterface?dubbo.tag=A&dubbo.backup.tag=B")
                        .setScopeModel(moduleModel),
                true);
        Invoker<String> untagged = new MockInvoker<>(
                URL.valueOf("test://127.0.0.1:7775/DemoInterface").setScopeModel(moduleModel), true);

        // force=true, primary offline, backup online -> routes strictly to backup
        BitList<Invoker<String>> primaryOfflineWithBackup =
                new BitList<>(Arrays.asList(serviceANormal, serviceABackup, untagged));
        RpcInvocation forceInvocation = new RpcInvocation();
        forceInvocation.setAttachment(TAG_KEY, "B");
        forceInvocation.setAttachment(FORCE_USE_TAG, "true");
        List<Invoker<String>> forceWithBackupResult =
                router.route(primaryOfflineWithBackup.clone(), url, forceInvocation, false, new Holder<>());
        Assertions.assertEquals(Collections.singletonList(serviceABackup), new ArrayList<>(forceWithBackupResult));

        // force=true, BOTH primary and backup offline -> returns empty list (no leak to untagged)
        BitList<Invoker<String>> primaryAndBackupOffline = new BitList<>(Arrays.asList(serviceANormal, untagged));
        List<Invoker<String>> forceWithoutBackupResult =
                router.route(primaryAndBackupOffline.clone(), url, forceInvocation, false, new Holder<>());
        Assertions.assertEquals(Collections.emptyList(), new ArrayList<>(forceWithoutBackupResult));

        // force=false, BOTH primary and backup offline -> falls back to untagged providers
        RpcInvocation nonForceInvocation = new RpcInvocation();
        nonForceInvocation.setAttachment(TAG_KEY, "B");
        nonForceInvocation.setAttachment(FORCE_USE_TAG, "false");
        List<Invoker<String>> nonForceResult =
                router.route(primaryAndBackupOffline.clone(), url, nonForceInvocation, false, new Holder<>());
        Assertions.assertEquals(Collections.singletonList(untagged), new ArrayList<>(nonForceResult));
    }

    @Test
    void testBackupTagWithDynamicRuleForceTrueAndFalse() {
        Invoker<String> serviceANormal = new MockInvoker<>(
                URL.valueOf("test://10.20.3.1:7772/DemoInterface?application=foo&dubbo.tag=A")
                        .setScopeModel(moduleModel),
                true);
        Invoker<String> serviceABackup = new MockInvoker<>(
                URL.valueOf("test://10.20.3.2:7773/DemoInterface?application=foo&dubbo.tag=A&dubbo.backup.tag=B")
                        .setScopeModel(moduleModel),
                true);
        Invoker<String> untagged = new MockInvoker<>(
                URL.valueOf("test://10.20.3.3:7775/DemoInterface?application=foo")
                        .setScopeModel(moduleModel),
                true);

        BitList<Invoker<String>> withBackup = new BitList<>(Arrays.asList(serviceANormal, serviceABackup, untagged));
        BitList<Invoker<String>> withoutBackup = new BitList<>(Arrays.asList(serviceANormal, untagged));

        RpcInvocation tagBInvocation = new RpcInvocation();
        tagBInvocation.setAttachment(TAG_KEY, "B");

        // Dynamic rule with force=true where tag B's dynamic address is offline
        TagStateRouter<String> forceTrueRouter =
                Mockito.spy((TagStateRouter<String>) new TagStateRouterFactory().getRouter(String.class, url));
        TagRouterRule forceTrueRule = buildDynamicTagRule(true);
        Mockito.when(forceTrueRouter.getInvokers()).thenReturn(withBackup);
        forceTrueRule.init(forceTrueRouter);
        forceTrueRouter.setTagRouterRule(forceTrueRule);

        // Primary dynamic tag B offline, backup present -> routes strictly to backup
        List<Invoker<String>> forceTrueBackupResult =
                forceTrueRouter.route(withBackup.clone(), url, tagBInvocation, false, new Holder<>());
        Assertions.assertEquals(Collections.singletonList(serviceABackup), new ArrayList<>(forceTrueBackupResult));

        // Primary dynamic tag B offline, backup absent, force=true -> empty list
        List<Invoker<String>> forceTrueEmptyResult =
                forceTrueRouter.route(withoutBackup.clone(), url, tagBInvocation, false, new Holder<>());
        Assertions.assertEquals(Collections.emptyList(), new ArrayList<>(forceTrueEmptyResult));

        // Dynamic rule with force=false where tag B's dynamic address is offline
        TagStateRouter<String> forceFalseRouter =
                Mockito.spy((TagStateRouter<String>) new TagStateRouterFactory().getRouter(String.class, url));
        TagRouterRule forceFalseRule = buildDynamicTagRule(false);
        Mockito.when(forceFalseRouter.getInvokers()).thenReturn(withBackup);
        forceFalseRule.init(forceFalseRouter);
        forceFalseRouter.setTagRouterRule(forceFalseRule);

        // Primary dynamic tag B offline, backup present -> routes strictly to backup
        List<Invoker<String>> forceFalseBackupResult =
                forceFalseRouter.route(withBackup.clone(), url, tagBInvocation, false, new Holder<>());
        Assertions.assertEquals(Collections.singletonList(serviceABackup), new ArrayList<>(forceFalseBackupResult));

        // Primary dynamic tag B offline, backup absent, force=false -> falls back to untagged
        List<Invoker<String>> forceFalseFallbackResult =
                forceFalseRouter.route(withoutBackup.clone(), url, tagBInvocation, false, new Holder<>());
        Assertions.assertEquals(Collections.singletonList(untagged), new ArrayList<>(forceFalseFallbackResult));
    }

    @Test
    void testConsumerBackupTagDynamicRuleForcePreserved() {
        Invoker<String> serviceANormal = new MockInvoker<>(
                URL.valueOf("test://10.20.3.1:7772/DemoInterface?application=foo&dubbo.tag=A")
                        .setScopeModel(moduleModel),
                true);
        Invoker<String> untagged = new MockInvoker<>(
                URL.valueOf("test://10.20.3.3:7775/DemoInterface?application=foo")
                        .setScopeModel(moduleModel),
                true);
        BitList<Invoker<String>> invokers = new BitList<>(Arrays.asList(serviceANormal, untagged));

        // Primary tag X has no rule and no provider; consumer backup tag B has a dynamic rule whose address is offline.
        // No dubbo.force.tag attachment is set.
        RpcInvocation invocationWithBackupAttachment = new RpcInvocation();
        invocationWithBackupAttachment.setAttachment(TAG_KEY, "X");
        invocationWithBackupAttachment.setAttachment(BACKUP_TAG_KEY, "B");

        URL consumerUrlWithBackup = URL.valueOf("consumer://127.0.0.1/DemoInterface?dubbo.backup.tag=B")
                .setScopeModel(moduleModel);
        RpcInvocation invocationWithUrlBackup = new RpcInvocation();
        invocationWithUrlBackup.setAttachment(TAG_KEY, "X");

        // Dynamic rule with force=true -> must NOT fall back to untagged provider
        TagStateRouter<String> forceTrueRouter =
                Mockito.spy((TagStateRouter<String>) new TagStateRouterFactory().getRouter(String.class, url));
        TagRouterRule forceTrueRule = buildDynamicTagRule(true);
        Mockito.when(forceTrueRouter.getInvokers()).thenReturn(invokers);
        forceTrueRule.init(forceTrueRouter);
        forceTrueRouter.setTagRouterRule(forceTrueRule);

        List<Invoker<String>> forceTrueAttachmentResult =
                forceTrueRouter.route(invokers.clone(), url, invocationWithBackupAttachment, false, new Holder<>());
        Assertions.assertEquals(Collections.emptyList(), new ArrayList<>(forceTrueAttachmentResult));

        List<Invoker<String>> forceTrueUrlResult = forceTrueRouter.route(
                invokers.clone(), consumerUrlWithBackup, invocationWithUrlBackup, false, new Holder<>());
        Assertions.assertEquals(Collections.emptyList(), new ArrayList<>(forceTrueUrlResult));

        // Dynamic rule with force=false -> falls back to online untagged provider
        TagStateRouter<String> forceFalseRouter =
                Mockito.spy((TagStateRouter<String>) new TagStateRouterFactory().getRouter(String.class, url));
        TagRouterRule forceFalseRule = buildDynamicTagRule(false);
        Mockito.when(forceFalseRouter.getInvokers()).thenReturn(invokers);
        forceFalseRule.init(forceFalseRouter);
        forceFalseRouter.setTagRouterRule(forceFalseRule);

        List<Invoker<String>> forceFalseAttachmentResult =
                forceFalseRouter.route(invokers.clone(), url, invocationWithBackupAttachment, false, new Holder<>());
        Assertions.assertEquals(Collections.singletonList(untagged), new ArrayList<>(forceFalseAttachmentResult));

        List<Invoker<String>> forceFalseUrlResult = forceFalseRouter.route(
                invokers.clone(), consumerUrlWithBackup, invocationWithUrlBackup, false, new Holder<>());
        Assertions.assertEquals(Collections.singletonList(untagged), new ArrayList<>(forceFalseUrlResult));
    }

    private TagRouterRule buildDynamicTagRule(boolean force) {
        String tagRouterRuleConfig = "---\n"
                + "force: " + force + "\n"
                + "runtime: true\n"
                + "enabled: true\n"
                + "priority: 1\n"
                + "key: foo\n"
                + "tags:\n"
                + "  - name: B\n"
                + "    addresses: [\"10.20.3.99:20880\"]\n"
                + "  - name: A\n"
                + "    addresses: [\"10.20.3.1:7772\"]\n"
                + "...";
        return TagRuleParser.parse(tagRouterRuleConfig);
    }

    @Test
    void testBackupTagRoutingWithMergedProviderUrls() {
        DefaultProviderURLMergeProcessor mergeProcessor = new DefaultProviderURLMergeProcessor();

        URL consumerUrl = URL.valueOf("consumer://127.0.0.1/DemoInterface?dubbo.backup.tag=C")
                .setScopeModel(moduleModel);
        Map<String, String> consumerParams = consumerUrl.getParameters();

        URL mergedUrl1 = mergeProcessor
                .mergeUrl(
                        URL.valueOf("test://127.0.0.1:7781/DemoInterface?dubbo.tag=A&dubbo.backup.tag=B"),
                        consumerParams)
                .setScopeModel(moduleModel);
        URL mergedUrl2 = mergeProcessor
                .mergeUrl(URL.valueOf("test://127.0.0.1:7782/DemoInterface?dubbo.tag=A"), consumerParams)
                .setScopeModel(moduleModel);
        URL mergedUrl3 = mergeProcessor
                .mergeUrl(URL.valueOf("test://127.0.0.1:7783/DemoInterface?dubbo.tag=C"), consumerParams)
                .setScopeModel(moduleModel);
        URL mergedUrl4 = mergeProcessor
                .mergeUrl(URL.valueOf("test://127.0.0.1:7784/DemoInterface?dubbo.tag=D"), consumerParams)
                .setScopeModel(moduleModel);

        // Verify merge processor preserves provider backup tag and does NOT leak consumer backup tag
        Assertions.assertEquals("B", mergedUrl1.getParameter(BACKUP_TAG_KEY));
        Assertions.assertNull(mergedUrl2.getParameter(BACKUP_TAG_KEY));
        Assertions.assertNull(mergedUrl3.getParameter(BACKUP_TAG_KEY));
        Assertions.assertNull(mergedUrl4.getParameter(BACKUP_TAG_KEY));

        Invoker<String> invoker1 = new MockInvoker<>(mergedUrl1, true);
        Invoker<String> invoker2 = new MockInvoker<>(mergedUrl2, true);
        Invoker<String> invoker3 = new MockInvoker<>(mergedUrl3, true);
        Invoker<String> invoker4 = new MockInvoker<>(mergedUrl4, true);
        BitList<Invoker<String>> invokers = new BitList<>(Arrays.asList(invoker1, invoker2, invoker3, invoker4));

        StateRouter<String> router = new TagStateRouterFactory().getRouter(String.class, consumerUrl);

        // 1. Request for primary tag B selects ONLY Invoker 1 (and NOT Invoker 2)
        RpcInvocation primaryBInvocation = new RpcInvocation();
        primaryBInvocation.setAttachment(TAG_KEY, "B");
        List<Invoker<String>> routedB =
                router.route(invokers.clone(), consumerUrl, primaryBInvocation, false, new Holder<>());
        Assertions.assertEquals(Collections.singletonList(invoker1), new ArrayList<>(routedB));

        // 2. Request for missing primary tag X with consumer URL dubbo.backup.tag=C selects ONLY Invoker 3
        // (and NOT Invoker 2, proving consumer C did not leak into Invoker 2)
        RpcInvocation missingXInvocation = new RpcInvocation();
        missingXInvocation.setAttachment(TAG_KEY, "X");
        List<Invoker<String>> routedConsumerBackup =
                router.route(invokers.clone(), consumerUrl, missingXInvocation, false, new Holder<>());
        Assertions.assertEquals(Collections.singletonList(invoker3), new ArrayList<>(routedConsumerBackup));

        // 3. Request for missing primary tag X with consumer URL dubbo.backup.tag=C AND invocation attachment
        // dubbo.backup.tag=D selects ONLY Invoker 4 (proving attachment precedence over consumer URL)
        RpcInvocation attachmentOverrideInvocation = new RpcInvocation();
        attachmentOverrideInvocation.setAttachment(TAG_KEY, "X");
        attachmentOverrideInvocation.setAttachment(BACKUP_TAG_KEY, "D");
        List<Invoker<String>> routedAttachmentBackup =
                router.route(invokers.clone(), consumerUrl, attachmentOverrideInvocation, false, new Holder<>());
        Assertions.assertEquals(Collections.singletonList(invoker4), new ArrayList<>(routedAttachmentBackup));
    }
}
