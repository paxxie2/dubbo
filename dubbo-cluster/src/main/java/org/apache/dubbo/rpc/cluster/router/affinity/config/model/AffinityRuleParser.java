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
package org.apache.dubbo.rpc.cluster.router.affinity.config.model;

import org.apache.dubbo.common.utils.StringUtils;

import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import static org.apache.dubbo.rpc.cluster.Constants.CONFIG_VERSION_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.RULE_VERSION_V31;

/**
 * # dubbo/config/group/{$name}.affinity-router
 * configVersion: v3.1
 * scope: service # Or application
 * key: service.apache.com
 * enabled: true
 * runtime: true
 * affinityAware:
 *   key: region
 *   ratio: 20
 */
public class AffinityRuleParser {

    @SuppressWarnings("unchecked")
    public static AffinityRouterRule parse(String rawRule) {
        if (rawRule == null) {
            return invalidRule(null);
        }
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object loaded = yaml.load(rawRule);
        if (!(loaded instanceof Map<?, ?>)) {
            return invalidRule(rawRule);
        }

        Map<?, ?> rawMap = (Map<?, ?>) loaded;
        Object confVersion = rawMap.get(CONFIG_VERSION_KEY);
        if (!(confVersion instanceof String) || !((String) confVersion).startsWith(RULE_VERSION_V31)) {
            return invalidRule(rawRule);
        }

        AffinityRouterRule rule = AffinityRouterRule.parseFromMap((Map<String, Object>) rawMap);
        if (StringUtils.isEmpty(rule.getAffinityKey())) {
            rule.setValid(false);
        }
        rule.setRawRule(rawRule);

        return rule;
    }

    private static AffinityRouterRule invalidRule(String rawRule) {
        AffinityRouterRule rule = new AffinityRouterRule();
        rule.setValid(false);
        rule.setRawRule(rawRule);
        return rule;
    }
}
