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

import org.apache.dubbo.common.logger.ErrorTypeAwareLogger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.rpc.cluster.router.AbstractRouterRule;

import java.util.Map;

import static org.apache.dubbo.common.constants.LoggerCodeConstants.CLUSTER_FAILED_RULE_PARSING;
import static org.apache.dubbo.rpc.cluster.Constants.AFFINITY_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.DefaultAffinityRatio;
import static org.apache.dubbo.rpc.cluster.Constants.KEY_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.RATIO_KEY;

public class AffinityRouterRule extends AbstractRouterRule {

    private static final ErrorTypeAwareLogger logger = LoggerFactory.getErrorTypeAwareLogger(AffinityRouterRule.class);
    private String affinityKey;
    private Double ratio;

    public static AffinityRouterRule parseFromMap(Map<String, Object> map) {
        AffinityRouterRule affinityRouterRule = new AffinityRouterRule();
        if (map == null) {
            affinityRouterRule.setValid(false);
            return affinityRouterRule;
        }
        affinityRouterRule.parseFromMap0(map);

        Object conditions = map.get(AFFINITY_KEY);
        if (!(conditions instanceof Map<?, ?>)) {
            logger.error(
                    CLUSTER_FAILED_RULE_PARSING,
                    "Invalid affinity router config.",
                    "",
                    "The affinityAware section must be a mapping.");
            affinityRouterRule.setValid(false);
            return affinityRouterRule;
        }

        Map<?, ?> conditionMap = (Map<?, ?>) conditions;
        Object keyObj = conditionMap.get(KEY_KEY);
        if (!(keyObj instanceof String) || StringUtils.isBlank((String) keyObj)) {
            logger.error(
                    CLUSTER_FAILED_RULE_PARSING,
                    "Invalid affinity router config.",
                    "",
                    "The affinityAware key must be a non-blank string.");
            affinityRouterRule.setValid(false);
            return affinityRouterRule;
        }
        affinityRouterRule.setAffinityKey((String) keyObj);

        if (!conditionMap.containsKey(RATIO_KEY)) {
            affinityRouterRule.setRatio(DefaultAffinityRatio);
            return affinityRouterRule;
        }

        Double parsedRatio = parseRatio(conditionMap.get(RATIO_KEY));
        if (parsedRatio == null || !Double.isFinite(parsedRatio) || parsedRatio < 0 || parsedRatio > 100) {
            logger.error(
                    CLUSTER_FAILED_RULE_PARSING,
                    "Invalid affinity router config.",
                    "",
                    "The ratio value must be a finite number ranging from 0 to 100");
            affinityRouterRule.setValid(false);
            return affinityRouterRule;
        }
        affinityRouterRule.setRatio(parsedRatio);
        return affinityRouterRule;
    }

    private static Double parseRatio(Object ratioObj) {
        if (ratioObj instanceof Number) {
            return ((Number) ratioObj).doubleValue();
        }
        if (ratioObj instanceof String) {
            try {
                return Double.valueOf((String) ratioObj);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public AffinityRouterRule() {}

    public String getAffinityKey() {
        return affinityKey;
    }

    public void setAffinityKey(String affinityKey) {
        this.affinityKey = affinityKey;
    }

    public Double getRatio() {
        return ratio;
    }

    public void setRatio(Double ratio) {
        this.ratio = ratio;
    }
}
