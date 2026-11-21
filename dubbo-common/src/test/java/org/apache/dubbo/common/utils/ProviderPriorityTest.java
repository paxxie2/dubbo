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
package org.apache.dubbo.common.utils;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.constants.LoadbalanceRules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderPriorityTest {

    private static final String BASE_URL = "dubbo://127.0.0.1:20880/org.apache.dubbo.demo.DemoService";

    @Test
    void priorityLoadbalanceRuleConstant() {
        assertEquals("priority", LoadbalanceRules.PRIORITY);
    }

    @Test
    void defaultsToZeroWhenProviderOrParameterAbsent() {
        assertEquals(0, ProviderPriority.resolve(null, "sayHello"));
        assertEquals(0, ProviderPriority.resolve(null, null));

        URL url = URL.valueOf(BASE_URL);
        assertEquals(0, ProviderPriority.resolve(url, "sayHello"));
        assertEquals(0, ProviderPriority.resolve(url, null));
        assertEquals(0, ProviderPriority.resolve(url, ""));
    }

    @Test
    void resolvesProviderWidePriority() {
        URL url = URL.valueOf(BASE_URL + "?loadbalance.priority=10");
        assertEquals(10, ProviderPriority.resolve(url, "sayHello"));
        assertEquals(10, ProviderPriority.resolve(url, "sayBye"));
        assertEquals(10, ProviderPriority.resolve(url, null));
        assertEquals(10, ProviderPriority.resolve(url, ""));

        URL withMethods = URL.valueOf(BASE_URL + "?methods=sayHello,sayBye&loadbalance.priority=15");
        assertEquals(15, ProviderPriority.resolve(withMethods, null));
        assertEquals(15, ProviderPriority.resolve(withMethods, ""));
        assertEquals(15, ProviderPriority.resolve(withMethods, "sayHello"));
    }

    @Test
    void methodOverrideTakesPrecedenceOverProviderWideValue() {
        URL url = URL.valueOf(BASE_URL + "?loadbalance.priority=10&sayHello.loadbalance.priority=25");
        assertEquals(25, ProviderPriority.resolve(url, "sayHello"));
        assertEquals(10, ProviderPriority.resolve(url, "sayBye"));
        assertEquals(10, ProviderPriority.resolve(url, null));

        URL withMethods = URL.valueOf(
                BASE_URL + "?methods=sayHello,sayBye&loadbalance.priority=10&sayHello.loadbalance.priority=0");
        assertEquals(0, ProviderPriority.resolve(withMethods, "sayHello"));
        assertEquals(10, ProviderPriority.resolve(withMethods, "sayBye"));

        URL lowerOverride = URL.valueOf(BASE_URL + "?loadbalance.priority=10&sayHello.loadbalance.priority=-3");
        assertEquals(-3, ProviderPriority.resolve(lowerOverride, "sayHello"));
        assertEquals(10, ProviderPriority.resolve(lowerOverride, "sayBye"));

        URL addedOverride =
                URL.valueOf(BASE_URL + "?loadbalance.priority=10").addParameter("sayHello.loadbalance.priority", "42");
        assertEquals(42, ProviderPriority.resolve(addedOverride, "sayHello"));
        assertEquals(10, ProviderPriority.resolve(addedOverride, "sayBye"));

        URL updatedOverride = URL.valueOf(BASE_URL + "?loadbalance.priority=10&sayHello.loadbalance.priority=5")
                .addParameter("sayHello.loadbalance.priority", "25");
        assertEquals(25, ProviderPriority.resolve(updatedOverride, "sayHello"));
        assertEquals(10, ProviderPriority.resolve(updatedOverride, "sayBye"));
    }

    @Test
    void invalidMethodOverrideResolvesToZeroInsteadOfProviderWideValue() {
        URL malformedOverride =
                URL.valueOf(BASE_URL + "?loadbalance.priority=10&sayHello.loadbalance.priority=notAnInt");
        assertEquals(0, ProviderPriority.resolve(malformedOverride, "sayHello"));
        assertEquals(10, ProviderPriority.resolve(malformedOverride, "sayBye"));

        URL blankOverride =
                URL.valueOf(BASE_URL + "?loadbalance.priority=10").addParameter("sayHello.loadbalance.priority", "   ");
        assertEquals(0, ProviderPriority.resolve(blankOverride, "sayHello"));
        assertEquals(10, ProviderPriority.resolve(blankOverride, "sayBye"));

        URL overflowOverride =
                URL.valueOf(BASE_URL + "?loadbalance.priority=10&sayHello.loadbalance.priority=2147483648");
        assertEquals(0, ProviderPriority.resolve(overflowOverride, "sayHello"));
        assertEquals(10, ProviderPriority.resolve(overflowOverride, "sayBye"));

        URL underflowOverride =
                URL.valueOf(BASE_URL + "?loadbalance.priority=10&sayHello.loadbalance.priority=-2147483649");
        assertEquals(0, ProviderPriority.resolve(underflowOverride, "sayHello"));
        assertEquals(10, ProviderPriority.resolve(underflowOverride, "sayBye"));
    }

    @Test
    void parsesTrimmedSignedDecimalValuesAndNegativePriorities() {
        assertEquals(-1, ProviderPriority.resolve(URL.valueOf(BASE_URL + "?loadbalance.priority=-1"), "sayHello"));
        assertEquals(-42, ProviderPriority.resolve(URL.valueOf(BASE_URL + "?loadbalance.priority=-42"), "sayHello"));
        assertEquals(0, ProviderPriority.resolve(URL.valueOf(BASE_URL + "?loadbalance.priority=-0"), "sayHello"));
        assertEquals(12, ProviderPriority.resolve(URL.valueOf(BASE_URL + "?loadbalance.priority=+12"), "sayHello"));

        URL trimmedProvider = URL.valueOf(BASE_URL).addParameter("loadbalance.priority", "  -7\t\n");
        assertEquals(-7, ProviderPriority.resolve(trimmedProvider, "sayHello"));

        URL trimmedMethod = URL.valueOf(BASE_URL + "?loadbalance.priority=1")
                .addParameter("sayHello.loadbalance.priority", "  +99  ");
        assertEquals(99, ProviderPriority.resolve(trimmedMethod, "sayHello"));
    }

    @Test
    void supportsExactIntegerLimits() {
        URL maxUrl = URL.valueOf(BASE_URL + "?loadbalance.priority=" + Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, ProviderPriority.resolve(maxUrl, "sayHello"));

        URL minUrl = URL.valueOf(BASE_URL + "?loadbalance.priority=" + Integer.MIN_VALUE);
        assertEquals(Integer.MIN_VALUE, ProviderPriority.resolve(minUrl, "sayHello"));

        URL methodLimits = URL.valueOf(BASE_URL
                + "?loadbalance.priority=5"
                + "&high.loadbalance.priority="
                + Integer.MAX_VALUE
                + "&low.loadbalance.priority="
                + Integer.MIN_VALUE);
        assertEquals(Integer.MAX_VALUE, ProviderPriority.resolve(methodLimits, "high"));
        assertEquals(Integer.MIN_VALUE, ProviderPriority.resolve(methodLimits, "low"));
        assertEquals(5, ProviderPriority.resolve(methodLimits, "other"));
    }

    @Test
    void resolvesBlankMalformedAndOverflowingValuesToZero() {
        URL blankSpaces = URL.valueOf(BASE_URL).addParameter("loadbalance.priority", "   ");
        assertEquals(0, ProviderPriority.resolve(blankSpaces, "sayHello"));

        URL blankTabsAndNewlines = URL.valueOf(BASE_URL).addParameter("loadbalance.priority", "\t\r\n ");
        assertEquals(0, ProviderPriority.resolve(blankTabsAndNewlines, "sayHello"));

        String[] invalidValues = {
            "2147483648",
            "-2147483649",
            "99999999999999999999",
            "-99999999999999999999",
            "abc",
            "1.5",
            "0x10",
            "1_000",
            "10a",
            "+",
            "-"
        };
        for (String invalid : invalidValues) {
            URL url = URL.valueOf(BASE_URL).addParameter("loadbalance.priority", invalid);
            assertEquals(0, ProviderPriority.resolve(url, "sayHello"), "Expected 0 for value: " + invalid);
        }
    }
}
