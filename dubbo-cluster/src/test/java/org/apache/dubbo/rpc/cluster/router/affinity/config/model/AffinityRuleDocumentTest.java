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

    @Test
    void testEmptyDocumentsReturnInvalidRuleAndRetainRawRule() {
        String[] emptyDocs = new String[] {"", "   \n  ", "# comment only\n", "---\n", "null\n", "~\n"};
        for (String doc : emptyDocs) {
            AffinityRouterRule rule = AffinityRuleParser.parse(doc);
            assertFalse(rule.isValid(), "Expected invalid for empty doc: " + doc);
            assertEquals(doc, rule.getRawRule());
        }
    }

    @Test
    void testScalarAndListRootsReturnInvalidRuleAndRetainRawRule() {
        String[] nonMapRoots = new String[] {"just a scalar\n", "42\n", "true\n", "- first\n- second\n", "[1, 2, 3]\n"};
        for (String doc : nonMapRoots) {
            AffinityRouterRule rule = AffinityRuleParser.parse(doc);
            assertFalse(rule.isValid(), "Expected invalid for non-map root: " + doc);
            assertEquals(doc, rule.getRawRule());
        }
    }

    @Test
    void testMissingNonStringAndUnsupportedConfigVersion() {
        String[] invalidVersionDocs = new String[] {
            "scope: service\nkey: com.foo.BarService\naffinityAware:\n  key: region\n  ratio: 20\n",
            "configVersion: null\naffinityAware:\n  key: region\n  ratio: 20\n",
            "configVersion: 3.1\naffinityAware:\n  key: region\n  ratio: 20\n",
            "configVersion: 3\naffinityAware:\n  key: region\n  ratio: 20\n",
            "configVersion: true\naffinityAware:\n  key: region\n  ratio: 20\n",
            "configVersion:\n  - v3.1\naffinityAware:\n  key: region\n  ratio: 20\n",
            "configVersion:\n  major: 3\naffinityAware:\n  key: region\n  ratio: 20\n",
            "configVersion: v3.0\naffinityAware:\n  key: region\n  ratio: 20\n",
            "configVersion: v2.7\naffinityAware:\n  key: region\n  ratio: 20\n",
            "configVersion: \"3.1\"\naffinityAware:\n  key: region\n  ratio: 20\n",
            "configVersion: \"\"\naffinityAware:\n  key: region\n  ratio: 20\n"
        };
        for (String doc : invalidVersionDocs) {
            AffinityRouterRule rule = AffinityRuleParser.parse(doc);
            assertFalse(rule.isValid(), "Expected invalid for doc: " + doc);
            assertEquals(doc, rule.getRawRule());
        }
    }

    @Test
    void testYamlSyntaxErrorAndUnsafeTagAreNotSuppressed() {
        String malformedYaml = "configVersion: v3.1\naffinityAware: [unclosed\n";
        assertThrows(YAMLException.class, () -> AffinityRuleParser.parse(malformedYaml));

        String unsafeTagYaml = "!!java.lang.Object {}\n";
        assertThrows(YAMLException.class, () -> AffinityRuleParser.parse(unsafeTagYaml));
    }

    @Test
    void testV31DocumentRejectedByFieldValidationRetainsRawRule() {
        String[] rejectedDocs = new String[] {
            "configVersion: v3.1\nscope: service\nkey: com.foo.BarService\n",
            "configVersion: v3.1\naffinityAware: not-a-map\n",
            "configVersion: v3.1\naffinityAware:\n  key: \"   \"\n  ratio: 20\n",
            "configVersion: v3.1\naffinityAware:\n  key: region\n  ratio: 150\n",
            "configVersion: v3.1\naffinityAware:\n  key: region\n  ratio: NaN\n"
        };
        for (String doc : rejectedDocs) {
            AffinityRouterRule rule = AffinityRuleParser.parse(doc);
            assertFalse(rule.isValid(), "Expected invalid for doc: " + doc);
            assertEquals(doc, rule.getRawRule());
        }
    }

    @Test
    void testValidV31DocumentRetainsKeyRatioAndRawRule() {
        String rawRule = "configVersion: v3.1\n"
                + "scope: service\n"
                + "key: service.apache.com\n"
                + "enabled: true\n"
                + "runtime: true\n"
                + "affinityAware:\n"
                + "  key: region\n"
                + "  ratio: 20\n";

        AffinityRouterRule rule = AffinityRuleParser.parse(rawRule);
        assertTrue(rule.isValid());
        assertEquals("region", rule.getAffinityKey());
        assertEquals(20.0, rule.getRatio());
        assertEquals(rawRule, rule.getRawRule());

        String prefixVersionRule = "configVersion: v3.1.2\n"
                + "scope: application\n"
                + "key: demo-app\n"
                + "affinityAware:\n"
                + "  key: zone\n"
                + "  ratio: \"37.5\"\n";

        AffinityRouterRule prefixed = AffinityRuleParser.parse(prefixVersionRule);
        assertTrue(prefixed.isValid());
        assertEquals("zone", prefixed.getAffinityKey());
        assertEquals(37.5, prefixed.getRatio());
        assertEquals(prefixVersionRule, prefixed.getRawRule());
    }
}
