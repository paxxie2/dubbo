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

/**
 * Resolves the load-balance priority tier of a provider from its {@link URL}.
 *
 * <p>Contract:
 * <ul>
 *   <li>Reads {@value #PRIORITY_KEY} using string-valued method-parameter lookup
 *       ({@link URL#getMethodParameter(String, String)}) when {@code methodName} is non-empty
 *       (method-specific override takes precedence over the provider-wide parameter), or
 *       {@link URL#getParameter(String)} when {@code methodName} is {@code null} or empty.</li>
 *   <li>Parses the effective string value after {@link String#trim()} as a signed decimal Java
 *       {@code int} ({@link Integer#parseInt(String)}).</li>
 *   <li>If {@code provider} is {@code null}, or if the effective value is absent, blank,
 *       malformed, or overflows 32-bit signed {@code int}, resolves to {@value #DEFAULT_PRIORITY}
 *       ({@code 0}).</li>
 *   <li>An invalid (blank, malformed, or overflowing) method-specific override resolves to
 *       {@value #DEFAULT_PRIORITY} ({@code 0}) and does not fall back to the provider-wide
 *       value.</li>
 * </ul>
 */
public final class ProviderPriority {

    /**
     * URL parameter key for provider load-balance priority.
     */
    public static final String PRIORITY_KEY = "loadbalance.priority";

    /**
     * Default priority when absent, blank, malformed, or out of {@code int} range.
     */
    public static final int DEFAULT_PRIORITY = 0;

    private ProviderPriority() {}

    /**
     * Resolves the priority tier for the given provider {@link URL} and method name.
     *
     * <p>Looks up {@value #PRIORITY_KEY} via string-valued method-parameter lookup (method
     * override, then provider-wide value) and parses the trimmed value as a signed decimal
     * Java {@code int}. Absent, blank, malformed, or overflowing effective values resolve to
     * {@value #DEFAULT_PRIORITY}. An invalid method override resolves to
     * {@value #DEFAULT_PRIORITY}, not to the provider-wide number.
     *
     * @param provider   the provider URL (may be {@code null})
     * @param methodName the invoked method name (may be {@code null} or empty)
     * @return the resolved signed 32-bit priority, or {@value #DEFAULT_PRIORITY} when absent or invalid
     */
    public static int resolve(URL provider, String methodName) {
        if (provider == null) {
            return DEFAULT_PRIORITY;
        }
        String raw;
        if (StringUtils.isEmpty(methodName)) {
            raw = provider.getParameter(PRIORITY_KEY);
        } else {
            // Check the method-scoped key directly first so dotted method keys added via
            // URL#addParameter (which URLParam does not index into METHOD_PARAMETERS) and
            // blank/empty method overrides take precedence instead of falling back to the
            // provider-wide value.
            raw = provider.getParameter(methodName + "." + PRIORITY_KEY);
            if (raw == null) {
                raw = provider.getMethodParameter(methodName, PRIORITY_KEY);
            }
        }
        if (raw == null) {
            return DEFAULT_PRIORITY;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return DEFAULT_PRIORITY;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException ignored) {
            return DEFAULT_PRIORITY;
        }
    }
}
