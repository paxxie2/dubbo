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
package org.apache.dubbo.common.extension;

import org.apache.dubbo.common.extension.ext1.SimpleExt;
import org.apache.dubbo.common.extension.ext6_wrap.WrappedExt;
import org.apache.dubbo.common.logger.Level;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.rpc.model.ApplicationModel;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ExtensionLoaderDebugLogTest {

    @Activate(onClass = {" ", "com.example.MissingDepA", "com.example.MissingDepB"})
    static class InactiveMissingDepExtension {}

    @Test
    void testDebugLoggingDoesNotBreakExtensionAndWrapperLoading() {
        Level previousLevel = LoggerFactory.getLevel();
        try {
            LoggerFactory.setLevel(Level.DEBUG);
            ApplicationModel model = ApplicationModel.ofNullable(null);
            ExtensionLoader<SimpleExt> simpleLoader = model.getExtensionLoader(SimpleExt.class);
            assertNotNull(simpleLoader.getExtension("impl1"));

            ExtensionLoader<WrappedExt> wrappedLoader = model.getExtensionLoader(WrappedExt.class);
            assertNotNull(wrappedLoader.getExtension("impl1"));
        } finally {
            LoggerFactory.setLevel(previousLevel);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFindMissingOnClassSkipsBlankEntriesAndReportsAllMissingClasses() throws Exception {
        ApplicationModel model = ApplicationModel.ofNullable(null);
        ExtensionLoader<SimpleExt> loader = model.getExtensionLoader(SimpleExt.class);
        Method findMissing =
                ExtensionLoader.class.getDeclaredMethod("findMissingOnClass", ClassLoader.class, Class.class);
        findMissing.setAccessible(true);
        List<String> missing = (List<String>) findMissing.invoke(
                loader, ExtensionLoaderDebugLogTest.class.getClassLoader(), InactiveMissingDepExtension.class);
        assertEquals(Arrays.asList("com.example.MissingDepA", "com.example.MissingDepB"), missing);
    }
}
