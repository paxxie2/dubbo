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
import org.apache.dubbo.common.config.configcenter.ConfigChangeType;
import org.apache.dubbo.common.config.configcenter.ConfigChangedEvent;
import org.apache.dubbo.common.config.configcenter.ConfigurationListener;
import org.apache.dubbo.common.config.configcenter.DynamicConfiguration;
import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.Holder;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.Invocation;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.cluster.router.RouterSnapshotNode;
import org.apache.dubbo.rpc.cluster.router.state.AbstractStateRouter;
import org.apache.dubbo.rpc.cluster.router.state.BitList;
import org.apache.dubbo.rpc.cluster.router.tag.model.TagRouterRule;
import org.apache.dubbo.rpc.cluster.router.tag.model.TagRuleParser;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.apache.dubbo.common.constants.CommonConstants.ANYHOST_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.ANY_VALUE;
import static org.apache.dubbo.common.constants.CommonConstants.BACKUP_TAG_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.TAG_KEY;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CLUSTER_TAG_ROUTE_EMPTY;
import static org.apache.dubbo.common.constants.LoggerCodeConstants.CLUSTER_TAG_ROUTE_INVALID;
import static org.apache.dubbo.rpc.Constants.FORCE_USE_TAG;

/**
 * TagRouter, "application.tag-router"
 */
public class TagStateRouter<T> extends AbstractStateRouter<T> implements ConfigurationListener {
    public static final String NAME = "TAG_ROUTER";
    private static final ErrorTypeAwareLogger logger = LoggerFactory.getErrorTypeAwareLogger(TagStateRouter.class);
    private static final String RULE_SUFFIX = ".tag-router";
    public static final char TAG_SEPERATOR = '|';

    private volatile TagRouterRule tagRouterRule;
    private String application;
    private volatile BitList<Invoker<T>> invokers = BitList.emptyList();

    public TagStateRouter(URL url) {
        super(url);
    }

    @Override
    public synchronized void process(ConfigChangedEvent event) {
        if (logger.isInfoEnabled()) {
            logger.info("Notification of tag rule, change type is: " + event.getChangeType() + ", raw rule is:\n "
                    + event.getContent());
        }

        try {
            if (event.getChangeType().equals(ConfigChangeType.DELETED)) {
                this.tagRouterRule = null;
            } else {
                TagRouterRule rule = TagRuleParser.parse(event.getContent());
                rule.init(this);
                this.tagRouterRule = rule;
            }
        } catch (Exception e) {
            logger.error(
                    CLUSTER_TAG_ROUTE_INVALID,
                    "Failed to parse the raw tag router rule",
                    "",
                    "Failed to parse the raw tag router rule and it will not take effect, please check if the "
                            + "rule matches with the template, the raw rule is:\n ",
                    e);
        }
    }

    @Override
    public BitList<Invoker<T>> doRoute(
            BitList<Invoker<T>> invokers,
            URL url,
            Invocation invocation,
            boolean needToPrintMessage,
            Holder<RouterSnapshotNode<T>> nodeHolder,
            Holder<String> messageHolder)
            throws RpcException {
        if (CollectionUtils.isEmpty(invokers)) {
            if (needToPrintMessage && messageHolder != null) {
                messageHolder.set("Directly Return. Reason: Invokers from previous router is empty.");
            }
            return invokers;
        }

        String tag = StringUtils.isEmpty(invocation.getAttachment(TAG_KEY))
                ? url.getParameter(TAG_KEY)
                : invocation.getAttachment(TAG_KEY);
        if (ANY_VALUE.equals(tag)) {
            if (needToPrintMessage && messageHolder != null) {
                messageHolder.set("Skip tag routing. Reason: wildcard tag request");
            }
            return invokers;
        }

        // since the rule can be changed by config center, we should copy one to use.
        final TagRouterRule tagRouterRuleCopy = tagRouterRule;
        if (tagRouterRuleCopy == null || !tagRouterRuleCopy.isValid() || !tagRouterRuleCopy.isEnabled()) {
            return filterUsingStaticTag(invokers, url, invocation, needToPrintMessage, messageHolder);
        }

        BitList<Invoker<T>> result = invokers;

        // if we are requesting for a Provider with a specific tag
        if (StringUtils.isNotEmpty(tag)) {
            Map<String, Set<String>> tagnameToAddresses = tagRouterRuleCopy.getTagnameToAddresses();
            Holder<String> matchedTagHolder = needToPrintMessage ? new Holder<>() : null;
            Set<String> addresses =
                    selectAddressByTagLevel(tagnameToAddresses, tag, isForceUseTag(invocation), matchedTagHolder);
            // Tier 1 (Primary): filter by dynamic tag group first, otherwise check static tag
            if (addresses != null) { // null means tag not set
                result = filterInvoker(invokers, invoker -> addressMatches(invoker.getUrl(), addresses));
            } else {
                // dynamic tag group doesn't have any item about the requested app. check static tag
                result = filterInvoker(
                        invokers, invoker -> tag.equals(invoker.getUrl().getParameter(TAG_KEY)));
            }
            if (CollectionUtils.isNotEmpty(result)) {
                if (needToPrintMessage && messageHolder != null) {
                    if (addresses != null) {
                        String matchedTag = matchedTagHolder != null && matchedTagHolder.get() != null
                                ? matchedTagHolder.get()
                                : tag;
                        messageHolder.set("Use primary tag " + matchedTag
                                + " (dynamic rule) to route. Reason: primary tag matched");
                    } else {
                        messageHolder.set(
                                "Use primary tag " + tag + " (static tag) to route. Reason: primary tag matched");
                    }
                }
                return result;
            }

            // Tier 2 (Backup): try provider-side backup tag match, then consumer-side backup tag
            Holder<Boolean> backupMatchedDynamicRule = new Holder<>();
            result = filterUsingBackupTag(
                    invokers,
                    url,
                    invocation,
                    tag,
                    tagnameToAddresses,
                    backupMatchedDynamicRule,
                    needToPrintMessage,
                    messageHolder);
            if (CollectionUtils.isNotEmpty(result)) {
                return result;
            }

            // Tier 3 (Force & untagged failover): only when both Tier 1 and Tier 2 are empty.
            // force.tag is set by default to false, which means it will invoke any providers without a tag
            // unless it's explicitly disallowed.
            boolean ruleForce = (addresses != null || Boolean.TRUE.equals(backupMatchedDynamicRule.get()))
                    && tagRouterRuleCopy.isForce();
            if (ruleForce || isForceUseTag(invocation)) {
                if (needToPrintMessage && messageHolder != null) {
                    String forceSource;
                    if (ruleForce && isForceUseTag(invocation)) {
                        forceSource = "dynamic rule and invocation force";
                    } else if (ruleForce) {
                        forceSource = "dynamic rule force";
                    } else {
                        forceSource = "invocation force";
                    }
                    messageHolder.set("Return empty result for tag " + tag
                            + ". Reason: no available matching invokers and force is true (" + forceSource + ")");
                }
                return BitList.emptyList();
            }
            // FAILOVER: return all Providers without any tags.
            else {
                BitList<Invoker<T>> tmp = filterInvoker(
                        invokers, invoker -> addressNotMatches(invoker.getUrl(), tagRouterRuleCopy.getAddresses()));
                if (needToPrintMessage && messageHolder != null) {
                    messageHolder.set("Fallback to untagged providers for tag " + tag
                            + " (dynamic rule). Reason: no primary or backup tag matched and no force policy applied");
                }
                return filterInvoker(
                        tmp, invoker -> StringUtils.isEmpty(invoker.getUrl().getParameter(TAG_KEY)));
            }
        } else {
            // List<String> addresses = tagRouterRule.filter(providerApp);
            // return all addresses in dynamic tag group.
            Set<String> addresses = tagRouterRuleCopy.getAddresses();
            if (CollectionUtils.isNotEmpty(addresses)) {
                result = filterInvoker(invokers, invoker -> addressNotMatches(invoker.getUrl(), addresses));
                // 1. all addresses are in dynamic tag group, return empty list.
                if (CollectionUtils.isEmpty(result)) {
                    if (needToPrintMessage && messageHolder != null) {
                        messageHolder.set(
                                "Untagged request returns empty result (dynamic rule). Reason: all providers are in dynamic tag groups");
                    }
                    return result;
                }
                // 2. if there are some addresses that are not in any dynamic tag group, continue to filter using the
                // static tag group.
            }
            if (needToPrintMessage && messageHolder != null) {
                messageHolder.set(
                        "Untagged request routes to untagged providers (dynamic rule). Reason: no tag requested, excluding dynamic tag groups and static tagged providers");
            }
            return filterInvoker(result, invoker -> {
                String localTag = invoker.getUrl().getParameter(TAG_KEY);
                return StringUtils.isEmpty(localTag);
            });
        }
    }

    /**
     * If there's no dynamic tag rule being set, use static tag in URL.
     * <p>
     * A typical scenario is a Consumer using version 2.7.x calls Providers using version 2.6.x or lower,
     * the Consumer should always respect the tag in provider URL regardless of whether a dynamic tag rule has been set to it or not.
     * <p>
     * TODO, to guarantee consistent behavior of interoperability between 2.6- and 2.7+, this method should has the same logic with the TagRouter in 2.6.x.
     *
     * @param invokers
     * @param url
     * @param invocation
     * @param <T>
     * @return
     */
    private <T> BitList<Invoker<T>> filterUsingStaticTag(
            BitList<Invoker<T>> invokers,
            URL url,
            Invocation invocation,
            boolean needToPrintMessage,
            Holder<String> messageHolder) {
        BitList<Invoker<T>> result;
        // Dynamic param
        String tag = StringUtils.isEmpty(invocation.getAttachment(TAG_KEY))
                ? url.getParameter(TAG_KEY)
                : invocation.getAttachment(TAG_KEY);
        // Tag request
        if (!StringUtils.isEmpty(tag)) {
            // Tier 1 (Primary)
            result = filterInvoker(
                    invokers,
                    invoker ->
                            ANY_VALUE.equals(tag) || tag.equals(invoker.getUrl().getParameter(TAG_KEY)));
            if (CollectionUtils.isNotEmpty(result)) {
                if (needToPrintMessage && messageHolder != null) {
                    messageHolder.set("Use primary tag " + tag + " (static tag) to route. Reason: primary tag matched");
                }
                return result;
            }
            // Tier 2 (Backup)
            result =
                    filterUsingBackupTag(invokers, url, invocation, tag, null, null, needToPrintMessage, messageHolder);
            if (CollectionUtils.isNotEmpty(result)) {
                return result;
            }
            // Tier 3 (Force & untagged failover)
            if (isForceUseTag(invocation)) {
                if (needToPrintMessage && messageHolder != null) {
                    messageHolder.set("Return empty result for tag " + tag
                            + ". Reason: no available matching invokers and force is true (invocation force)");
                }
                return BitList.emptyList();
            }
            if (needToPrintMessage && messageHolder != null) {
                messageHolder.set("Fallback to untagged providers for tag " + tag
                        + " (static tag). Reason: no primary or backup tag matched and force is false");
            }
            result = filterInvoker(
                    invokers, invoker -> StringUtils.isEmpty(invoker.getUrl().getParameter(TAG_KEY)));
        } else {
            if (needToPrintMessage && messageHolder != null) {
                messageHolder.set(
                        "Untagged request routes to untagged providers (static tag). Reason: no tag requested");
            }
            result = filterInvoker(
                    invokers, invoker -> StringUtils.isEmpty(invoker.getUrl().getParameter(TAG_KEY)));
        }
        return result;
    }

    private <T> BitList<Invoker<T>> filterUsingBackupTag(
            BitList<Invoker<T>> invokers,
            URL url,
            Invocation invocation,
            String tag,
            Map<String, Set<String>> tagnameToAddresses,
            Holder<Boolean> backupMatchedDynamicRule,
            boolean needToPrintMessage,
            Holder<String> messageHolder) {
        // 1. Provider-side backup match
        BitList<Invoker<T>> result =
                filterInvoker(invokers, invoker -> tag.equals(invoker.getUrl().getParameter(BACKUP_TAG_KEY)));
        if (CollectionUtils.isNotEmpty(result)) {
            if (needToPrintMessage && messageHolder != null) {
                messageHolder.set("Use provider backup tag " + tag + " to route. Reason: primary tag result is empty");
            }
            return result;
        }

        // 2. Consumer-side backup tag match
        String backupTag = StringUtils.isEmpty(invocation.getAttachment(BACKUP_TAG_KEY))
                ? url.getParameter(BACKUP_TAG_KEY)
                : invocation.getAttachment(BACKUP_TAG_KEY);
        if (StringUtils.isNotEmpty(backupTag)) {
            boolean matchedByDynamicRule = false;
            Holder<String> matchedBackupTagHolder = needToPrintMessage ? new Holder<>() : null;
            if (tagnameToAddresses != null) {
                Set<String> backupAddresses = selectAddressByTagLevel(
                        tagnameToAddresses, backupTag, isForceUseTag(invocation), matchedBackupTagHolder);
                if (backupAddresses != null) {
                    matchedByDynamicRule = true;
                    if (backupMatchedDynamicRule != null) {
                        backupMatchedDynamicRule.set(true);
                    }
                    result = filterInvoker(invokers, invoker -> addressMatches(invoker.getUrl(), backupAddresses));
                } else {
                    result = filterInvoker(
                            invokers,
                            invoker -> backupTag.equals(invoker.getUrl().getParameter(TAG_KEY))
                                    || backupTag.equals(invoker.getUrl().getParameter(BACKUP_TAG_KEY)));
                }
            } else {
                if (ANY_VALUE.equals(backupTag)) {
                    if (needToPrintMessage && messageHolder != null) {
                        messageHolder.set("Use consumer wildcard backup tag " + ANY_VALUE
                                + " to route. Reason: primary tag result is empty");
                    }
                    return invokers;
                }
                result = filterInvoker(
                        invokers,
                        invoker -> backupTag.equals(invoker.getUrl().getParameter(TAG_KEY))
                                || backupTag.equals(invoker.getUrl().getParameter(BACKUP_TAG_KEY)));
            }
            if (CollectionUtils.isNotEmpty(result)) {
                if (needToPrintMessage && messageHolder != null) {
                    if (matchedByDynamicRule) {
                        String matchedBackupTag = matchedBackupTagHolder != null && matchedBackupTagHolder.get() != null
                                ? matchedBackupTagHolder.get()
                                : backupTag;
                        messageHolder.set("Use consumer backup tag " + matchedBackupTag
                                + " (dynamic rule) to route. Reason: primary tag result is empty");
                    } else {
                        messageHolder.set("Use consumer backup tag " + backupTag
                                + " (static tag) to route. Reason: primary tag result is empty");
                    }
                }
                return result;
            }
        }

        return BitList.emptyList();
    }

    @Override
    public boolean isRuntime() {
        return tagRouterRule != null && tagRouterRule.isRuntime();
    }

    @Override
    public boolean isForce() {
        // FIXME
        return tagRouterRule != null && tagRouterRule.isForce();
    }

    private boolean isForceUseTag(Invocation invocation) {
        return Boolean.parseBoolean(
                invocation.getAttachment(FORCE_USE_TAG, this.getUrl().getParameter(FORCE_USE_TAG, "false")));
    }

    private <T> BitList<Invoker<T>> filterInvoker(BitList<Invoker<T>> invokers, Predicate<Invoker<T>> predicate) {
        if (invokers.stream().allMatch(predicate)) {
            return invokers;
        }

        BitList<Invoker<T>> newInvokers = invokers.clone();
        newInvokers.removeIf(invoker -> !predicate.test(invoker));

        return newInvokers;
    }

    private boolean addressMatches(URL url, Set<String> addresses) {
        return addresses != null && checkAddressMatch(addresses, url.getHost(), url.getPort());
    }

    private boolean addressNotMatches(URL url, Set<String> addresses) {
        return addresses == null || !checkAddressMatch(addresses, url.getHost(), url.getPort());
    }

    private boolean checkAddressMatch(Set<String> addresses, String host, int port) {
        for (String address : addresses) {
            try {
                if (NetUtils.matchIpExpression(address, host, port)) {
                    return true;
                }
                if ((ANYHOST_VALUE + ":" + port).equals(address)) {
                    return true;
                }
            } catch (Exception e) {
                logger.error(
                        CLUSTER_TAG_ROUTE_INVALID,
                        "tag route address is invalid",
                        "",
                        "The format of ip address is invalid in tag route. Address :" + address,
                        e);
            }
        }
        return false;
    }

    public void setApplication(String app) {
        this.application = app;
    }

    @Override
    public void notify(BitList<Invoker<T>> invokers) {
        this.invokers = invokers;
        if (CollectionUtils.isEmpty(invokers)) {
            return;
        }

        Invoker<T> invoker = invokers.get(0);
        URL url = invoker.getUrl();
        String providerApplication = url.getRemoteApplication();

        if (StringUtils.isEmpty(providerApplication)) {
            logger.error(
                    CLUSTER_TAG_ROUTE_EMPTY,
                    "tag router get providerApplication is empty",
                    "",
                    "TagRouter must getConfig from or subscribe to a specific application, but the application "
                            + "in this TagRouter is not specified.");
            return;
        }

        synchronized (this) {
            if (!providerApplication.equals(application)) {
                if (StringUtils.isNotEmpty(application)) {
                    this.getRuleRepository().removeListener(application + RULE_SUFFIX, this);
                }
                String key = providerApplication + RULE_SUFFIX;
                this.getRuleRepository().addListener(key, this);
                application = providerApplication;
                String rawRule = this.getRuleRepository().getRule(key, DynamicConfiguration.DEFAULT_GROUP);
                if (StringUtils.isNotEmpty(rawRule)) {
                    this.process(new ConfigChangedEvent(key, DynamicConfiguration.DEFAULT_GROUP, rawRule));
                }
            } else {
                if (this.tagRouterRule != null) {
                    TagRouterRule newRule = TagRuleParser.parse(this.tagRouterRule.getRawRule());
                    newRule.init(this);
                    this.tagRouterRule = newRule;
                }
            }
        }
    }

    public BitList<Invoker<T>> getInvokers() {
        return invokers;
    }

    @Override
    public void stop() {
        if (StringUtils.isNotEmpty(application)) {
            this.getRuleRepository().removeListener(application + RULE_SUFFIX, this);
        }
    }

    // for testing purpose
    public void setTagRouterRule(TagRouterRule tagRouterRule) {
        this.tagRouterRule = tagRouterRule;
    }

    /**
     * select addresses by tag with level
     * <p>
     * example:
     * selector=beta|team1|partner1
     * step1.select tagAddresses with selector=beta|team1|partner1, if result is empty, then run step2
     * step2.select tagAddresses with selector=beta|team1, if result is empty, then run step3
     * step3.select tagAddresses with selector=beta, if result is empty, result is null
     * </p>
     *
     * @param tagAddresses
     * @param tagSelector  eg: beta|team1|partner1
     * @return
     */
    public static Set<String> selectAddressByTagLevel(
            Map<String, Set<String>> tagAddresses, String tagSelector, boolean isForce) {
        return selectAddressByTagLevel(tagAddresses, tagSelector, isForce, null);
    }

    private static Set<String> selectAddressByTagLevel(
            Map<String, Set<String>> tagAddresses,
            String tagSelector,
            boolean isForce,
            Holder<String> matchedTagHolder) {
        if (isForce || StringUtils.isNotContains(tagSelector, TAG_SEPERATOR)) {
            Set<String> addresses = tagAddresses.get(tagSelector);
            if (addresses != null && matchedTagHolder != null) {
                matchedTagHolder.set(tagSelector);
            }
            return addresses;
        }
        String[] selectors = StringUtils.split(tagSelector, TAG_SEPERATOR);
        for (int i = selectors.length; i > 0; i--) {
            String selectorTmp = StringUtils.join(selectors, TAG_SEPERATOR, 0, i);
            Set<String> addresses = tagAddresses.get(selectorTmp);
            if (CollectionUtils.isNotEmpty(addresses)) {
                if (matchedTagHolder != null) {
                    matchedTagHolder.set(selectorTmp);
                }
                return addresses;
            }
        }
        return null;
    }
}
