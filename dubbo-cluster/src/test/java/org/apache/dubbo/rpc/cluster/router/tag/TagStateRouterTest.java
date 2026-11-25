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
import org.apache.dubbo.rpc.cluster.router.RouterSnapshotNode;
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

    @Test
    void testWildcardConsumerBackupTagRespectsDynamicRule() {
        Invoker<String> unrelatedTagged = new MockInvoker<>(
                URL.valueOf("test://10.20.3.4:7774/DemoInterface?application=foo&dubbo.tag=other")
                        .setScopeModel(moduleModel),
                true);
        Invoker<String> untagged = new MockInvoker<>(
                URL.valueOf("test://10.20.3.3:7775/DemoInterface?application=foo")
                        .setScopeModel(moduleModel),
                true);
        BitList<Invoker<String>> invokers = new BitList<>(Arrays.asList(unrelatedTagged, untagged));

        // Primary tag B is mapped to an offline address by the dynamic rule; consumer specifies dubbo.backup.tag=*
        RpcInvocation wildcardAttachmentInvocation = new RpcInvocation();
        wildcardAttachmentInvocation.setAttachment(TAG_KEY, "B");
        wildcardAttachmentInvocation.setAttachment(BACKUP_TAG_KEY, ANY_VALUE);

        URL consumerUrlWithWildcardBackup = URL.valueOf("consumer://127.0.0.1/DemoInterface?dubbo.backup.tag=*")
                .setScopeModel(moduleModel);
        RpcInvocation wildcardUrlInvocation = new RpcInvocation();
        wildcardUrlInvocation.setAttachment(TAG_KEY, "B");

        // Forced dynamic rule -> must return EMPTY (must NOT leak to unrelated tagged or untagged providers)
        TagStateRouter<String> forceTrueRouter =
                Mockito.spy((TagStateRouter<String>) new TagStateRouterFactory().getRouter(String.class, url));
        TagRouterRule forceTrueRule = buildDynamicTagRule(true);
        Mockito.when(forceTrueRouter.getInvokers()).thenReturn(invokers);
        forceTrueRule.init(forceTrueRouter);
        forceTrueRouter.setTagRouterRule(forceTrueRule);

        List<Invoker<String>> forceTrueAttachmentResult =
                forceTrueRouter.route(invokers.clone(), url, wildcardAttachmentInvocation, false, new Holder<>());
        Assertions.assertEquals(Collections.emptyList(), new ArrayList<>(forceTrueAttachmentResult));

        List<Invoker<String>> forceTrueUrlResult = forceTrueRouter.route(
                invokers.clone(), consumerUrlWithWildcardBackup, wildcardUrlInvocation, false, new Holder<>());
        Assertions.assertEquals(Collections.emptyList(), new ArrayList<>(forceTrueUrlResult));

        // Non-forced dynamic rule -> falls back only to untagged provider (must NOT leak to unrelated tagged provider)
        TagStateRouter<String> forceFalseRouter =
                Mockito.spy((TagStateRouter<String>) new TagStateRouterFactory().getRouter(String.class, url));
        TagRouterRule forceFalseRule = buildDynamicTagRule(false);
        Mockito.when(forceFalseRouter.getInvokers()).thenReturn(invokers);
        forceFalseRule.init(forceFalseRouter);
        forceFalseRouter.setTagRouterRule(forceFalseRule);

        List<Invoker<String>> forceFalseResult =
                forceFalseRouter.route(invokers.clone(), url, wildcardAttachmentInvocation, false, new Holder<>());
        Assertions.assertEquals(Collections.singletonList(untagged), new ArrayList<>(forceFalseResult));

        // Static mode (no dynamic rule) -> wildcard backup matches all invokers like static primary wildcard
        StateRouter<String> staticRouter = new TagStateRouterFactory().getRouter(String.class, url);
        List<Invoker<String>> staticResult =
                staticRouter.route(invokers.clone(), url, wildcardAttachmentInvocation, false, new Holder<>());
        Assertions.assertEquals(Arrays.asList(unrelatedTagged, untagged), new ArrayList<>(staticResult));
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

    @Test
    void testSnapshotStaticTagRoutingBranches() {
        Invoker<String> blue1 = new MockInvoker<>(
                URL.valueOf("test://127.0.0.1:7801/DemoInterface?dubbo.tag=blue")
                        .setScopeModel(moduleModel),
                true);
        Invoker<String> blue2 = new MockInvoker<>(
                URL.valueOf("test://127.0.0.1:7802/DemoInterface?dubbo.tag=blue")
                        .setScopeModel(moduleModel),
                true);
        Invoker<String> providerBackupForBlue = new MockInvoker<>(
                URL.valueOf("test://127.0.0.1:7803/DemoInterface?dubbo.tag=alpha&dubbo.backup.tag=blue")
                        .setScopeModel(moduleModel),
                true);
        Invoker<String> green1 = new MockInvoker<>(
                URL.valueOf("test://127.0.0.1:7804/DemoInterface?dubbo.tag=green")
                        .setScopeModel(moduleModel),
                true);
        Invoker<String> green2 = new MockInvoker<>(
                URL.valueOf("test://127.0.0.1:7805/DemoInterface?dubbo.tag=green")
                        .setScopeModel(moduleModel),
                true);
        Invoker<String> untagged1 = new MockInvoker<>(
                URL.valueOf("test://127.0.0.1:7806/DemoInterface").setScopeModel(moduleModel), true);
        Invoker<String> untagged2 = new MockInvoker<>(
                URL.valueOf("test://127.0.0.1:7807/DemoInterface").setScopeModel(moduleModel), true);

        BitList<Invoker<String>> allInvokers =
                new BitList<>(Arrays.asList(blue1, blue2, providerBackupForBlue, green1, green2, untagged1, untagged2));
        BitList<Invoker<String>> noBlueWithProviderBackup =
                new BitList<>(Arrays.asList(providerBackupForBlue, green1, green2, untagged1, untagged2));
        BitList<Invoker<String>> noBlueNoProviderBackup =
                new BitList<>(Arrays.asList(green1, green2, untagged1, untagged2));
        BitList<Invoker<String>> onlyUntagged = new BitList<>(Arrays.asList(untagged1, untagged2));

        // Verify both no-rule static mode and disabled/invalid dynamic rule fallback to static mode
        TagStateRouter<String> noRuleRouter =
                (TagStateRouter<String>) new TagStateRouterFactory().getRouter(String.class, url);

        TagStateRouter<String> disabledRuleRouter =
                Mockito.spy((TagStateRouter<String>) new TagStateRouterFactory().getRouter(String.class, url));
        TagRouterRule disabledRule = TagRuleParser.parse("---\n"
                + "force: true\n"
                + "runtime: true\n"
                + "enabled: false\n"
                + "priority: 1\n"
                + "key: demo-provider\n"
                + "tags:\n"
                + "  - name: blue\n"
                + "    addresses: [\"127.0.0.1:7801\"]\n"
                + "...");
        Mockito.when(disabledRuleRouter.getInvokers()).thenReturn(allInvokers);
        disabledRule.init(disabledRuleRouter);
        disabledRuleRouter.setTagRouterRule(disabledRule);

        TagStateRouter<String> invalidRuleRouter =
                (TagStateRouter<String>) new TagStateRouterFactory().getRouter(String.class, url);
        TagRouterRule invalidRule = new TagRouterRule();
        invalidRule.setValid(false);
        invalidRule.setEnabled(true);
        invalidRuleRouter.setTagRouterRule(invalidRule);

        for (TagStateRouter<String> router : Arrays.asList(noRuleRouter, disabledRuleRouter, invalidRuleRouter)) {
            // 1. Upstream candidates empty
            Holder<String> emptyMsg = new Holder<>();
            List<Invoker<String>> emptyResult =
                    routeWithSnapshot(router, BitList.emptyList(), url, new RpcInvocation(), emptyMsg);
            Assertions.assertEquals(Collections.emptyList(), emptyResult);
            Assertions.assertEquals("Directly Return. Reason: Invokers from previous router is empty.", emptyMsg.get());

            // 2. Wildcard tag request skips tag routing (distinct from upstream empty)
            RpcInvocation wildcardInvocation = new RpcInvocation();
            wildcardInvocation.setAttachment(TAG_KEY, ANY_VALUE);
            Holder<String> wildcardMsg = new Holder<>();
            List<Invoker<String>> wildcardResult =
                    routeWithSnapshot(router, allInvokers, url, wildcardInvocation, wildcardMsg);
            Assertions.assertEquals(new ArrayList<>(allInvokers), wildcardResult);
            Assertions.assertEquals("Skip tag routing. Reason: wildcard tag request", wildcardMsg.get());
            Assertions.assertNotEquals(emptyMsg.get(), wildcardMsg.get());

            // 3. Static primary tag hit preserves order and reports actual matched tag
            RpcInvocation primaryBlue = new RpcInvocation();
            primaryBlue.setAttachment(TAG_KEY, "blue");
            Holder<String> primaryMsg = new Holder<>();
            List<Invoker<String>> primaryResult = routeWithSnapshot(router, allInvokers, url, primaryBlue, primaryMsg);
            Assertions.assertEquals(Arrays.asList(blue1, blue2), primaryResult);
            Assertions.assertEquals(
                    "Use primary tag blue (static tag) to route. Reason: primary tag matched", primaryMsg.get());

            // 4. Provider-side backup tag hit when primary is empty
            Holder<String> providerBackupMsg = new Holder<>();
            List<Invoker<String>> providerBackupResult =
                    routeWithSnapshot(router, noBlueWithProviderBackup, url, primaryBlue, providerBackupMsg);
            Assertions.assertEquals(Collections.singletonList(providerBackupForBlue), providerBackupResult);
            Assertions.assertEquals(
                    "Use provider backup tag blue to route. Reason: primary tag result is empty",
                    providerBackupMsg.get());

            // 5. Consumer-side backup tag green hit when primary blue has no candidates
            RpcInvocation blueBackupGreen = new RpcInvocation();
            blueBackupGreen.setAttachment(TAG_KEY, "blue");
            blueBackupGreen.setAttachment(BACKUP_TAG_KEY, "green");
            Holder<String> consumerBackupMsg = new Holder<>();
            List<Invoker<String>> consumerBackupResult =
                    routeWithSnapshot(router, noBlueNoProviderBackup, url, blueBackupGreen, consumerBackupMsg);
            Assertions.assertEquals(Arrays.asList(green1, green2), consumerBackupResult);
            Assertions.assertEquals(
                    "Use consumer backup tag green (static tag) to route. Reason: primary tag result is empty",
                    consumerBackupMsg.get());
            Assertions.assertFalse(consumerBackupMsg.get().contains("invalid or disabled"));

            // 6. Consumer wildcard backup tag hit in static mode
            RpcInvocation blueBackupWildcard = new RpcInvocation();
            blueBackupWildcard.setAttachment(TAG_KEY, "blue");
            blueBackupWildcard.setAttachment(BACKUP_TAG_KEY, ANY_VALUE);
            Holder<String> wildcardBackupMsg = new Holder<>();
            List<Invoker<String>> wildcardBackupResult =
                    routeWithSnapshot(router, noBlueNoProviderBackup, url, blueBackupWildcard, wildcardBackupMsg);
            Assertions.assertEquals(new ArrayList<>(noBlueNoProviderBackup), wildcardBackupResult);
            Assertions.assertEquals(
                    "Use consumer wildcard backup tag * to route. Reason: primary tag result is empty",
                    wildcardBackupMsg.get());

            // 7. Invocation force=true with no matching primary or backup -> empty with explanation
            RpcInvocation forceBlue = new RpcInvocation();
            forceBlue.setAttachment(TAG_KEY, "blue");
            forceBlue.setAttachment(FORCE_USE_TAG, "true");
            Holder<String> forceEmptyMsg = new Holder<>();
            List<Invoker<String>> forceEmptyResult =
                    routeWithSnapshot(router, onlyUntagged, url, forceBlue, forceEmptyMsg);
            Assertions.assertEquals(Collections.emptyList(), forceEmptyResult);
            Assertions.assertEquals(
                    "Return empty result for tag blue. Reason: no available matching invokers and force is true (invocation force)",
                    forceEmptyMsg.get());

            // 8. Non-force fallback to untagged providers preserves order
            Holder<String> failoverMsg = new Holder<>();
            List<Invoker<String>> failoverResult =
                    routeWithSnapshot(router, noBlueNoProviderBackup, url, primaryBlue, failoverMsg);
            Assertions.assertEquals(Arrays.asList(untagged1, untagged2), failoverResult);
            Assertions.assertEquals(
                    "Fallback to untagged providers for tag blue (static tag). Reason: no primary or backup tag matched and force is false",
                    failoverMsg.get());

            // 9. Untagged request routes to untagged providers (distinct from tagged fallback)
            Holder<String> untaggedReqMsg = new Holder<>();
            List<Invoker<String>> untaggedReqResult =
                    routeWithSnapshot(router, allInvokers, url, new RpcInvocation(), untaggedReqMsg);
            Assertions.assertEquals(Arrays.asList(untagged1, untagged2), untaggedReqResult);
            Assertions.assertEquals(
                    "Untagged request routes to untagged providers (static tag). Reason: no tag requested",
                    untaggedReqMsg.get());
            Assertions.assertNotEquals(failoverMsg.get(), untaggedReqMsg.get());
        }
    }

    @Test
    void testSnapshotDynamicRuleRoutingBranches() {
        Invoker<String> dynBlue1 = new MockInvoker<>(
                URL.valueOf("test://10.20.4.1:7801/DemoInterface?application=foo")
                        .setScopeModel(moduleModel),
                true);
        Invoker<String> dynBlue2 = new MockInvoker<>(
                URL.valueOf("test://10.20.4.2:7802/DemoInterface?application=foo")
                        .setScopeModel(moduleModel),
                true);
        Invoker<String> dynBetaTeam1 = new MockInvoker<>(
                URL.valueOf("test://10.20.4.3:7803/DemoInterface?application=foo")
                        .setScopeModel(moduleModel),
                true);
        Invoker<String> dynGreenZone1 = new MockInvoker<>(
                URL.valueOf("test://10.20.4.4:7804/DemoInterface?application=foo")
                        .setScopeModel(moduleModel),
                true);
        Invoker<String> staticCanary = new MockInvoker<>(
                URL.valueOf("test://10.20.4.5:7805/DemoInterface?application=foo&dubbo.tag=canary")
                        .setScopeModel(moduleModel),
                true);
        Invoker<String> providerBackupBlue = new MockInvoker<>(
                URL.valueOf("test://10.20.4.6:7806/DemoInterface?application=foo&dubbo.tag=other&dubbo.backup.tag=blue")
                        .setScopeModel(moduleModel),
                true);
        Invoker<String> staticYellow = new MockInvoker<>(
                URL.valueOf("test://10.20.4.7:7807/DemoInterface?application=foo&dubbo.tag=yellow")
                        .setScopeModel(moduleModel),
                true);
        Invoker<String> untagged1 = new MockInvoker<>(
                URL.valueOf("test://10.20.4.8:7808/DemoInterface?application=foo")
                        .setScopeModel(moduleModel),
                true);
        Invoker<String> untagged2 = new MockInvoker<>(
                URL.valueOf("test://10.20.4.9:7809/DemoInterface?application=foo")
                        .setScopeModel(moduleModel),
                true);

        BitList<Invoker<String>> allInvokers = new BitList<>(Arrays.asList(
                dynBlue1,
                dynBlue2,
                dynBetaTeam1,
                dynGreenZone1,
                staticCanary,
                providerBackupBlue,
                staticYellow,
                untagged1,
                untagged2));

        TagStateRouter<String> nonForceRouter = buildDynamicRouter(false, allInvokers);
        TagStateRouter<String> forceRuleRouter = buildDynamicRouter(true, allInvokers);

        // 1. Dynamic primary tag hit (exact single-level)
        RpcInvocation blueInvocation = new RpcInvocation();
        blueInvocation.setAttachment(TAG_KEY, "blue");
        Holder<String> dynPrimaryMsg = new Holder<>();
        List<Invoker<String>> dynPrimaryResult =
                routeWithSnapshot(nonForceRouter, allInvokers, url, blueInvocation, dynPrimaryMsg);
        Assertions.assertEquals(Arrays.asList(dynBlue1, dynBlue2), dynPrimaryResult);
        Assertions.assertEquals(
                "Use primary tag blue (dynamic rule) to route. Reason: primary tag matched", dynPrimaryMsg.get());

        // 2. Dynamic multi-level primary tag hit reports actual matched level (beta|team1, not beta|team1|partner1)
        RpcInvocation multiLevelPrimary = new RpcInvocation();
        multiLevelPrimary.setAttachment(TAG_KEY, "beta|team1|partner1");
        Holder<String> multiLevelPrimaryMsg = new Holder<>();
        List<Invoker<String>> multiLevelPrimaryResult =
                routeWithSnapshot(nonForceRouter, allInvokers, url, multiLevelPrimary, multiLevelPrimaryMsg);
        Assertions.assertEquals(Collections.singletonList(dynBetaTeam1), multiLevelPrimaryResult);
        Assertions.assertEquals(
                "Use primary tag beta|team1 (dynamic rule) to route. Reason: primary tag matched",
                multiLevelPrimaryMsg.get());
        Assertions.assertFalse(multiLevelPrimaryMsg.get().contains("beta|team1|partner1"));

        // 3. Dynamic rule enabled, tag not in dynamic rule -> static tag primary hit
        RpcInvocation canaryInvocation = new RpcInvocation();
        canaryInvocation.setAttachment(TAG_KEY, "canary");
        Holder<String> staticPrimaryInDynMsg = new Holder<>();
        List<Invoker<String>> staticPrimaryInDynResult =
                routeWithSnapshot(nonForceRouter, allInvokers, url, canaryInvocation, staticPrimaryInDynMsg);
        Assertions.assertEquals(Collections.singletonList(staticCanary), staticPrimaryInDynResult);
        Assertions.assertEquals(
                "Use primary tag canary (static tag) to route. Reason: primary tag matched",
                staticPrimaryInDynMsg.get());

        // 4. Provider-side backup tag hit when dynamic primary blue is offline
        BitList<Invoker<String>> blueOfflineWithProviderBackup =
                new BitList<>(Arrays.asList(dynBetaTeam1, dynGreenZone1, providerBackupBlue, staticYellow, untagged1));
        Holder<String> providerBackupMsg = new Holder<>();
        List<Invoker<String>> providerBackupResult = routeWithSnapshot(
                nonForceRouter, blueOfflineWithProviderBackup, url, blueInvocation, providerBackupMsg);
        Assertions.assertEquals(Collections.singletonList(providerBackupBlue), providerBackupResult);
        Assertions.assertEquals(
                "Use provider backup tag blue to route. Reason: primary tag result is empty", providerBackupMsg.get());

        // 5. Consumer-side multi-level backup tag hit in dynamic rule reports actual matched level green|zone1
        BitList<Invoker<String>> blueOfflineNoProviderBackup =
                new BitList<>(Arrays.asList(dynBetaTeam1, dynGreenZone1, staticYellow, untagged1, untagged2));
        RpcInvocation blueBackupMultiLevelGreen = new RpcInvocation();
        blueBackupMultiLevelGreen.setAttachment(TAG_KEY, "blue");
        blueBackupMultiLevelGreen.setAttachment(BACKUP_TAG_KEY, "green|zone1|rack1");
        Holder<String> dynBackupMsg = new Holder<>();
        List<Invoker<String>> dynBackupResult = routeWithSnapshot(
                nonForceRouter, blueOfflineNoProviderBackup, url, blueBackupMultiLevelGreen, dynBackupMsg);
        Assertions.assertEquals(Collections.singletonList(dynGreenZone1), dynBackupResult);
        Assertions.assertEquals(
                "Use consumer backup tag green|zone1 (dynamic rule) to route. Reason: primary tag result is empty",
                dynBackupMsg.get());
        Assertions.assertFalse(dynBackupMsg.get().contains("green|zone1|rack1"));

        // 6. Consumer-side backup tag hit via static tag when dynamic rule does not define backup tag
        RpcInvocation blueBackupStaticYellow = new RpcInvocation();
        blueBackupStaticYellow.setAttachment(TAG_KEY, "blue");
        blueBackupStaticYellow.setAttachment(BACKUP_TAG_KEY, "yellow");
        Holder<String> staticBackupInDynMsg = new Holder<>();
        List<Invoker<String>> staticBackupInDynResult = routeWithSnapshot(
                nonForceRouter, blueOfflineNoProviderBackup, url, blueBackupStaticYellow, staticBackupInDynMsg);
        Assertions.assertEquals(Collections.singletonList(staticYellow), staticBackupInDynResult);
        Assertions.assertEquals(
                "Use consumer backup tag yellow (static tag) to route. Reason: primary tag result is empty",
                staticBackupInDynMsg.get());

        // 7. Dynamic rule force=true empty result (primary tag in dynamic rule is offline)
        Holder<String> ruleForceEmptyMsg = new Holder<>();
        List<Invoker<String>> ruleForceEmptyResult =
                routeWithSnapshot(forceRuleRouter, blueOfflineNoProviderBackup, url, blueInvocation, ruleForceEmptyMsg);
        Assertions.assertEquals(Collections.emptyList(), ruleForceEmptyResult);
        Assertions.assertEquals(
                "Return empty result for tag blue. Reason: no available matching invokers and force is true (dynamic rule force)",
                ruleForceEmptyMsg.get());

        // 8. Dynamic rule force=true triggered via consumer backup tag matching offline dynamic rule
        RpcInvocation missingPrimaryWithOfflineDynBackup = new RpcInvocation();
        missingPrimaryWithOfflineDynBackup.setAttachment(TAG_KEY, "missing");
        missingPrimaryWithOfflineDynBackup.setAttachment(BACKUP_TAG_KEY, "blue");
        Holder<String> backupRuleForceMsg = new Holder<>();
        List<Invoker<String>> backupRuleForceResult = routeWithSnapshot(
                forceRuleRouter,
                blueOfflineNoProviderBackup,
                url,
                missingPrimaryWithOfflineDynBackup,
                backupRuleForceMsg);
        Assertions.assertEquals(Collections.emptyList(), backupRuleForceResult);
        Assertions.assertEquals(
                "Return empty result for tag missing. Reason: no available matching invokers and force is true (dynamic rule force)",
                backupRuleForceMsg.get());

        // 9. Invocation force=true with dynamic rule force=false
        RpcInvocation invocationForceBlue = new RpcInvocation();
        invocationForceBlue.setAttachment(TAG_KEY, "blue");
        invocationForceBlue.setAttachment(FORCE_USE_TAG, "true");
        Holder<String> invocationForceMsg = new Holder<>();
        List<Invoker<String>> invocationForceResult = routeWithSnapshot(
                nonForceRouter, blueOfflineNoProviderBackup, url, invocationForceBlue, invocationForceMsg);
        Assertions.assertEquals(Collections.emptyList(), invocationForceResult);
        Assertions.assertEquals(
                "Return empty result for tag blue. Reason: no available matching invokers and force is true (invocation force)",
                invocationForceMsg.get());

        // 10. Both dynamic rule force=true and invocation force=true
        Holder<String> bothForceMsg = new Holder<>();
        List<Invoker<String>> bothForceResult =
                routeWithSnapshot(forceRuleRouter, blueOfflineNoProviderBackup, url, invocationForceBlue, bothForceMsg);
        Assertions.assertEquals(Collections.emptyList(), bothForceResult);
        Assertions.assertEquals(
                "Return empty result for tag blue. Reason: no available matching invokers and force is true (dynamic rule and invocation force)",
                bothForceMsg.get());

        // 11. Dynamic rule force=false fallback to untagged providers preserves order
        Holder<String> dynFailoverMsg = new Holder<>();
        List<Invoker<String>> dynFailoverResult =
                routeWithSnapshot(nonForceRouter, blueOfflineNoProviderBackup, url, blueInvocation, dynFailoverMsg);
        Assertions.assertEquals(Arrays.asList(untagged1, untagged2), dynFailoverResult);
        Assertions.assertEquals(
                "Fallback to untagged providers for tag blue (dynamic rule). Reason: no primary or backup tag matched and force is false",
                dynFailoverMsg.get());

        // 12. Untagged request when all providers are in dynamic tag groups -> empty
        BitList<Invoker<String>> onlyDynamicTagged = new BitList<>(Arrays.asList(dynBlue1, dynBetaTeam1));
        Holder<String> allDynamicUntaggedMsg = new Holder<>();
        List<Invoker<String>> allDynamicUntaggedResult =
                routeWithSnapshot(nonForceRouter, onlyDynamicTagged, url, new RpcInvocation(), allDynamicUntaggedMsg);
        Assertions.assertEquals(Collections.emptyList(), allDynamicUntaggedResult);
        Assertions.assertEquals(
                "Untagged request returns empty result (dynamic rule). Reason: all providers are in dynamic tag groups",
                allDynamicUntaggedMsg.get());

        // 13. Untagged request excludes both dynamic tag groups and static tagged providers
        // (predicate requires StringUtils.isEmpty(localTag)), preserving untagged provider order
        Holder<String> untaggedReqMsg = new Holder<>();
        List<Invoker<String>> untaggedReqResult =
                routeWithSnapshot(nonForceRouter, allInvokers, url, new RpcInvocation(), untaggedReqMsg);
        Assertions.assertEquals(Arrays.asList(untagged1, untagged2), untaggedReqResult);
        Assertions.assertEquals(
                "Untagged request routes to untagged providers (dynamic rule). Reason: no tag requested, excluding dynamic tag groups and static tagged providers",
                untaggedReqMsg.get());
        Assertions.assertNotEquals(dynFailoverMsg.get(), untaggedReqMsg.get());

        // 14. Wildcard tag under dynamic rule skips tag routing
        RpcInvocation wildcardInvocation = new RpcInvocation();
        wildcardInvocation.setAttachment(TAG_KEY, ANY_VALUE);
        Holder<String> wildcardMsg = new Holder<>();
        List<Invoker<String>> wildcardResult =
                routeWithSnapshot(nonForceRouter, allInvokers, url, wildcardInvocation, wildcardMsg);
        Assertions.assertEquals(new ArrayList<>(allInvokers), wildcardResult);
        Assertions.assertEquals("Skip tag routing. Reason: wildcard tag request", wildcardMsg.get());
    }

    @Test
    void testSnapshotDisabledGeneratesNoMessage() {
        Invoker<String> blue = new MockInvoker<>(
                URL.valueOf("test://10.20.4.1:7801/DemoInterface?application=foo&dubbo.tag=blue")
                        .setScopeModel(moduleModel),
                true);
        Invoker<String> green = new MockInvoker<>(
                URL.valueOf("test://10.20.4.4:7804/DemoInterface?application=foo&dubbo.tag=green")
                        .setScopeModel(moduleModel),
                true);
        Invoker<String> untagged = new MockInvoker<>(
                URL.valueOf("test://10.20.4.8:7808/DemoInterface?application=foo")
                        .setScopeModel(moduleModel),
                true);
        BitList<Invoker<String>> invokers = new BitList<>(Arrays.asList(blue, green, untagged));
        BitList<Invoker<String>> noBlue = new BitList<>(Arrays.asList(green, untagged));

        TagStateRouter<String> staticRouter =
                (TagStateRouter<String>) new TagStateRouterFactory().getRouter(String.class, url);
        TagStateRouter<String> dynamicRouter = buildDynamicRouter(false, invokers);

        RpcInvocation blueOnly = new RpcInvocation();
        blueOnly.setAttachment(TAG_KEY, "blue");

        RpcInvocation blueBackupGreen = new RpcInvocation();
        blueBackupGreen.setAttachment(TAG_KEY, "blue");
        blueBackupGreen.setAttachment(BACKUP_TAG_KEY, "green");

        RpcInvocation forceBlue = new RpcInvocation();
        forceBlue.setAttachment(TAG_KEY, "blue");
        forceBlue.setAttachment(FORCE_USE_TAG, "true");

        RpcInvocation wildcard = new RpcInvocation();
        wildcard.setAttachment(TAG_KEY, ANY_VALUE);

        for (TagStateRouter<String> router : Arrays.asList(staticRouter, dynamicRouter)) {
            // Via route() with needToPrintMessage=false: no snapshot child node created
            RouterSnapshotNode<String> rootNode = new RouterSnapshotNode<>("Parent", noBlue.clone());
            Holder<RouterSnapshotNode<String>> nodeHolder = new Holder<>();
            nodeHolder.set(rootNode);
            List<Invoker<String>> routed = router.route(noBlue.clone(), url, blueBackupGreen, false, nodeHolder);
            Assertions.assertEquals(Collections.singletonList(green), new ArrayList<>(routed));
            Assertions.assertTrue(rootNode.getNextNode().isEmpty());

            // Via doRoute() with needToPrintMessage=false: messageHolder stays null across all branches
            assertNoMessageWhenDisabled(router, BitList.emptyList(), new RpcInvocation(), Collections.emptyList());
            assertNoMessageWhenDisabled(router, invokers, wildcard, Arrays.asList(blue, green, untagged));
            assertNoMessageWhenDisabled(router, invokers, blueBackupGreen, Collections.singletonList(blue));
            assertNoMessageWhenDisabled(router, noBlue, blueBackupGreen, Collections.singletonList(green));
            assertNoMessageWhenDisabled(router, noBlue, forceBlue, Collections.emptyList());
            assertNoMessageWhenDisabled(router, noBlue, blueOnly, Collections.singletonList(untagged));
            assertNoMessageWhenDisabled(router, invokers, new RpcInvocation(), Collections.singletonList(untagged));
        }
    }

    private TagStateRouter<String> buildDynamicRouter(boolean force, BitList<Invoker<String>> invokers) {
        TagStateRouter<String> router =
                Mockito.spy((TagStateRouter<String>) new TagStateRouterFactory().getRouter(String.class, url));
        String ruleConfig = "---\n"
                + "force: " + force + "\n"
                + "runtime: true\n"
                + "enabled: true\n"
                + "priority: 1\n"
                + "key: foo\n"
                + "tags:\n"
                + "  - name: blue\n"
                + "    addresses: [\"10.20.4.1:7801\", \"10.20.4.2:7802\"]\n"
                + "  - name: beta|team1\n"
                + "    addresses: [\"10.20.4.3:7803\"]\n"
                + "  - name: green|zone1\n"
                + "    addresses: [\"10.20.4.4:7804\"]\n"
                + "  - name: green\n"
                + "    addresses: [\"10.20.4.4:7804\"]\n"
                + "...";
        TagRouterRule rule = TagRuleParser.parse(ruleConfig);
        Mockito.when(router.getInvokers()).thenReturn(invokers);
        rule.init(router);
        router.setTagRouterRule(rule);
        return router;
    }

    private List<Invoker<String>> routeWithSnapshot(
            StateRouter<String> router,
            BitList<Invoker<String>> invokers,
            URL consumerUrl,
            RpcInvocation invocation,
            Holder<String> messageOut) {
        RouterSnapshotNode<String> rootNode = new RouterSnapshotNode<>("Parent", invokers.clone());
        Holder<RouterSnapshotNode<String>> nodeHolder = new Holder<>();
        nodeHolder.set(rootNode);
        List<Invoker<String>> result = router.route(invokers.clone(), consumerUrl, invocation, true, nodeHolder);
        Assertions.assertEquals(1, rootNode.getNextNode().size());
        messageOut.set(rootNode.getNextNode().get(0).getRouterMessage());
        return new ArrayList<>(result);
    }

    private void assertNoMessageWhenDisabled(
            TagStateRouter<String> router,
            BitList<Invoker<String>> invokers,
            RpcInvocation invocation,
            List<Invoker<String>> expected) {
        Holder<String> messageHolder = new Holder<>();
        BitList<Invoker<String>> result = router.doRoute(invokers.clone(), url, invocation, false, null, messageHolder);
        Assertions.assertEquals(expected, new ArrayList<>(result));
        Assertions.assertNull(messageHolder.get());
    }
}
