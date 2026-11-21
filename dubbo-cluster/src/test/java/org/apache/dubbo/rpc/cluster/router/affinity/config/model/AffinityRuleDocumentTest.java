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

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.error.YAMLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AffinityRuleDocumentTest {

    private static final String BODY = "affinityAware:\n  key: region\n  ratio: 20\n";

    @Test
    void testMalformedEnvelopesAndVersionsReturnInvalidRule() {
        assertInvalid("", "   \n  ", "# comment\n", "---\n", "null\n", "~\n");
        assertInvalid("scalar\n", "42\n", "- a\n- b\n", "[1, 2]\n");
        for (String h : new String[] {"scope: service", "configVersion: null", "configVersion: 3.1"}) {
            assertInvalid(h + "\n" + BODY);
        }
        for (String h : new String[] {"configVersion: true", "configVersion:\n  - v3.1", "configVersion: v3.0"}) {
            assertInvalid(h + "\n" + BODY);
        }
        assertInvalid("configVersion: \"\"\n" + BODY);
    }

    @Test
    void testSyntaxErrorsAndUnsafeTagsPropagate() {
        assertThrows(YAMLException.class, () -> AffinityRuleParser.parse("configVersion: v3.1\naffinityAware: [bad\n"));
        assertThrows(YAMLException.class, () -> AffinityRuleParser.parse("!!java.lang.Object {}\n"));
    }

    @Test
    void testV31RejectedByFieldValidationAndValidV31Documents() {
        assertInvalid("configVersion: v3.1\nscope: service\n", "configVersion: v3.1\naffinityAware: not-a-map\n");
        assertInvalid(
                "configVersion: v3.1\naffinityAware:\n  key: \"   \"\n  ratio: 20\n",
                "configVersion: v3.1\naffinityAware:\n  key: region\n  ratio: NaN\n");

        assertValid("configVersion: v3.1\nscope: service\nkey: svc\n" + BODY, "region", 20.0);
        assertValid("configVersion: v3.1.2\naffinityAware:\n  key: zone\n  ratio: \"37.5\"\n", "zone", 37.5);
    }

    private static void assertInvalid(String... docs) {
        for (String doc : docs) {
            AffinityRouterRule rule = AffinityRuleParser.parse(doc);
            assertFalse(rule.isValid(), "Expected invalid for: " + doc);
            assertEquals(doc, rule.getRawRule());
        }
    }

    private static void assertValid(String raw, String key, double ratio) {
        AffinityRouterRule rule = AffinityRuleParser.parse(raw);
        assertTrue(rule.isValid());
        assertEquals(key, rule.getAffinityKey());
        assertEquals(ratio, rule.getRatio());
        assertEquals(raw, rule.getRawRule());
    }
}
