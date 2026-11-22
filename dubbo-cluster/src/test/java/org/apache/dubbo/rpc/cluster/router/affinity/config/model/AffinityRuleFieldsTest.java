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

import org.apache.dubbo.rpc.cluster.Constants;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AffinityRuleFieldsTest {

    @Test
    void testInvalidBlocksAndKeys() {
        assertFalse(parse(envelope()).isValid());
        for (Object block : new Object[] {null, "region", 123, true, Collections.singletonList("region")}) {
            assertFalse(parse(withAffinity(block)).isValid());
        }
        assertFalse(parse(withAffinity(Collections.emptyMap())).isValid());
        for (Object key : new Object[] {null, "", "   \t ", 42, false, Collections.singletonList("region")}) {
            assertFalse(parse(ruleWith(key, 20)).isValid());
        }
    }

    @Test
    void testDefaultAndValidRatios() {
        AffinityRouterRule defaulted = parse(withAffinity(Collections.singletonMap(Constants.KEY_KEY, "region")));
        assertTrue(defaulted.isValid());
        assertEquals("region", defaulted.getAffinityKey());
        assertEquals(Constants.DefaultAffinityRatio, defaulted.getRatio());

        Object[][] valid = {{0, 0.0}, {100, 100.0}, {"0", 0.0}, {"100", 100.0}, {"12.5", 12.5}, {37.25, 37.25}};
        for (Object[] c : valid) {
            AffinityRouterRule rule = parse(ruleWith("region", c[0]));
            assertTrue(rule.isValid());
            assertEquals("region", rule.getAffinityKey());
            assertEquals((Double) c[1], rule.getRatio());
        }
    }

    @Test
    void testInvalidRatiosAndPreservedInvalidFlag() {
        for (Object r : new Object[] {null, "", "abc", true, Collections.singletonList(10), Double.NaN, "NaN"}) {
            assertFalse(parse(ruleWith("region", r)).isValid());
        }
        for (Object r : new Object[] {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, "Infinity", "-Infinity"}) {
            assertFalse(parse(ruleWith("region", r)).isValid());
        }
        for (Object r : new Object[] {-0.1, 100.1, "-1", "101"}) {
            assertFalse(parse(ruleWith("region", r)).isValid());
        }
        Map<String, Object> preInvalid = ruleWith("region", 20);
        preInvalid.put(Constants.VALID_KEY, false);
        assertFalse(parse(preInvalid).isValid());
    }

    private static AffinityRouterRule parse(Map<String, Object> map) {
        return AffinityRouterRule.parseFromMap(map);
    }

    private static Map<String, Object> envelope() {
        Map<String, Object> map = new HashMap<>();
        map.put(Constants.CONFIG_VERSION_KEY, Constants.RULE_VERSION_V31);
        map.put(Constants.SCOPE_KEY, "service");
        map.put(Constants.KEY_KEY, "org.apache.dubbo.demo.DemoService");
        return map;
    }

    private static Map<String, Object> withAffinity(Object affinity) {
        Map<String, Object> map = envelope();
        map.put(Constants.AFFINITY_KEY, affinity);
        return map;
    }

    private static Map<String, Object> ruleWith(Object key, Object ratio) {
        Map<String, Object> affinity = new HashMap<>();
        affinity.put(Constants.KEY_KEY, key);
        affinity.put(Constants.RATIO_KEY, ratio);
        return withAffinity(affinity);
    }
}
