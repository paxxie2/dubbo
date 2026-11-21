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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.apache.dubbo.rpc.cluster.Constants.AFFINITY_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.CONFIG_VERSION_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.DefaultAffinityRatio;
import static org.apache.dubbo.rpc.cluster.Constants.KEY_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.RATIO_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.RULE_VERSION_V31;
import static org.apache.dubbo.rpc.cluster.Constants.SCOPE_KEY;
import static org.apache.dubbo.rpc.cluster.Constants.VALID_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AffinityRuleFieldsTest {

    @Test
    void testMissingAndNonMapAffinityAwareBlock() {
        Map<String, Object> missingBlock = baseEnvelope();
        assertFalse(AffinityRouterRule.parseFromMap(missingBlock).isValid());

        Object[] invalidBlocks = new Object[] {null, "region", 123, Boolean.TRUE, Collections.singletonList("region")};
        for (Object invalidBlock : invalidBlocks) {
            Map<String, Object> map = baseEnvelope();
            map.put(AFFINITY_KEY, invalidBlock);
            assertFalse(AffinityRouterRule.parseFromMap(map).isValid(), "Expected invalid for block: " + invalidBlock);
        }
    }

    @Test
    void testMissingNonStringAndBlankKeys() {
        Map<String, Object> missingKeyMap = baseEnvelope();
        missingKeyMap.put(AFFINITY_KEY, new HashMap<>());
        assertFalse(AffinityRouterRule.parseFromMap(missingKeyMap).isValid());

        Object[] invalidKeys =
                new Object[] {null, "", "   \t ", 42, Boolean.FALSE, Collections.singletonList("region")};
        for (Object invalidKey : invalidKeys) {
            Map<String, Object> affinity = new HashMap<>();
            affinity.put(KEY_KEY, invalidKey);
            affinity.put(RATIO_KEY, 20);
            Map<String, Object> map = baseEnvelope();
            map.put(AFFINITY_KEY, affinity);
            assertFalse(AffinityRouterRule.parseFromMap(map).isValid(), "Expected invalid for key: " + invalidKey);
        }
    }

    @Test
    void testDefaultRatioAndValidRatioInputs() {
        Map<String, Object> defaultMap = baseEnvelope();
        Map<String, Object> defaultAffinity = new HashMap<>();
        defaultAffinity.put(KEY_KEY, "region");
        defaultMap.put(AFFINITY_KEY, defaultAffinity);

        AffinityRouterRule defaultRule = AffinityRouterRule.parseFromMap(defaultMap);
        assertTrue(defaultRule.isValid());
        assertEquals("region", defaultRule.getAffinityKey());
        assertEquals(DefaultAffinityRatio, defaultRule.getRatio());

        assertValidRatio(0, 0.0);
        assertValidRatio(100, 100.0);
        assertValidRatio("0", 0.0);
        assertValidRatio("100", 100.0);
        assertValidRatio("12.5", 12.5);
        assertValidRatio(37.25, 37.25);
    }

    @Test
    void testInvalidRatios() {
        Object[] invalidRatios = new Object[] {
            null,
            "",
            "   ",
            "abc",
            Boolean.TRUE,
            Collections.singletonList(10),
            Double.NaN,
            "NaN",
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            "Infinity",
            "-Infinity",
            -0.1,
            -1,
            100.1,
            101,
            "-1",
            "101"
        };
        for (Object invalidRatio : invalidRatios) {
            Map<String, Object> affinity = new HashMap<>();
            affinity.put(KEY_KEY, "region");
            affinity.put(RATIO_KEY, invalidRatio);
            Map<String, Object> map = baseEnvelope();
            map.put(AFFINITY_KEY, affinity);
            assertFalse(AffinityRouterRule.parseFromMap(map).isValid(), "Expected invalid for ratio: " + invalidRatio);
        }
    }

    @Test
    void testPreviouslyInvalidRuleStaysInvalid() {
        Map<String, Object> affinity = new HashMap<>();
        affinity.put(KEY_KEY, "region");
        affinity.put(RATIO_KEY, 20);
        Map<String, Object> map = baseEnvelope();
        map.put(VALID_KEY, false);
        map.put(AFFINITY_KEY, affinity);

        AffinityRouterRule rule = AffinityRouterRule.parseFromMap(map);
        assertFalse(rule.isValid());
    }

    private static void assertValidRatio(Object ratioInput, double expected) {
        Map<String, Object> affinity = new HashMap<>();
        affinity.put(KEY_KEY, "region");
        affinity.put(RATIO_KEY, ratioInput);
        Map<String, Object> map = baseEnvelope();
        map.put(AFFINITY_KEY, affinity);

        AffinityRouterRule rule = AffinityRouterRule.parseFromMap(map);
        assertTrue(rule.isValid(), "Expected valid for ratio: " + ratioInput);
        assertEquals("region", rule.getAffinityKey());
        assertEquals(expected, rule.getRatio());
    }

    private static Map<String, Object> baseEnvelope() {
        Map<String, Object> map = new HashMap<>();
        map.put(CONFIG_VERSION_KEY, RULE_VERSION_V31);
        map.put(SCOPE_KEY, "service");
        map.put(KEY_KEY, "org.apache.dubbo.demo.DemoService");
        return map;
    }
}
