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
package org.apache.dubbo.rpc.protocol.dubbo;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.serialize.ObjectOutput;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.common.serialize.support.DefaultSerializationSelector;
import org.apache.dubbo.common.url.component.ServiceConfigURL;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;
import org.apache.dubbo.remoting.buffer.ChannelBufferInputStream;
import org.apache.dubbo.remoting.buffer.ChannelBufferOutputStream;
import org.apache.dubbo.remoting.buffer.ChannelBuffers;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.transport.CodecSupport;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.protocol.PermittedSerializationKeeper;
import org.apache.dubbo.rpc.protocol.dubbo.decode.MockChannel;
import org.apache.dubbo.rpc.protocol.dubbo.support.DemoService;
import org.apache.dubbo.rpc.protocol.dubbo.support.GenericCollectionService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_VERSION_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.PATH_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.VERSION_KEY;
import static org.apache.dubbo.rpc.protocol.dubbo.DubboCodec.DUBBO_VERSION;

/**
 * {@link DecodeableRpcInvocation}
 */
class DecodeableRpcInvocationTest {

    @Test
    void test() throws Exception {
        // Simulate the data called by the client(The called data is stored in invocation and written to the buffer)
        URL url = new ServiceConfigURL("dubbo", "127.0.0.1", 9103, DemoService.class.getName(), VERSION_KEY, "1.0.0");
        RpcInvocation inv = new RpcInvocation(
                null, "sayHello", DemoService.class.getName(), "", new Class<?>[] {String.class}, new String[] {"yug"});
        inv.setObjectAttachment(PATH_KEY, url.getPath());
        inv.setObjectAttachment(VERSION_KEY, url.getVersion());
        inv.setObjectAttachment(DUBBO_VERSION_KEY, DUBBO_VERSION);
        inv.setObjectAttachment("k1", "v1");
        inv.setObjectAttachment("k2", "v2");
        inv.setTargetServiceUniqueName(url.getServiceKey());
        // Write the data of inv to the buffer
        Byte proto = CodecSupport.getIDByName(DefaultSerializationSelector.getDefaultRemotingSerialization());
        ChannelBuffer buffer = writeBuffer(url, inv, proto);

        FrameworkModel frameworkModel = new FrameworkModel();
        ApplicationModel applicationModel = frameworkModel.newApplication();
        applicationModel
                .getDefaultModule()
                .getServiceRepository()
                .registerService(DemoService.class.getName(), DemoService.class);
        frameworkModel
                .getBeanFactory()
                .getBean(PermittedSerializationKeeper.class)
                .registerService(url);

        // Simulate the server to decode
        Channel channel = new MockChannel();
        Request request = new Request(1);
        ChannelBufferInputStream is = new ChannelBufferInputStream(buffer, buffer.readableBytes());
        DecodeableRpcInvocation decodeableRpcInvocation =
                new DecodeableRpcInvocation(frameworkModel, channel, request, is, proto);
        decodeableRpcInvocation.decode();

        // Verify that the decodeableRpcInvocation data decoded by the server is consistent with the invocation data of
        // the client
        Assertions.assertEquals(request.getVersion(), DUBBO_VERSION);
        Assertions.assertEquals(decodeableRpcInvocation.getObjectAttachment(DUBBO_VERSION_KEY), DUBBO_VERSION);
        Assertions.assertEquals(
                decodeableRpcInvocation.getObjectAttachment(VERSION_KEY), inv.getObjectAttachment(VERSION_KEY));
        Assertions.assertEquals(
                decodeableRpcInvocation.getObjectAttachment(PATH_KEY), inv.getObjectAttachment(PATH_KEY));
        Assertions.assertEquals(decodeableRpcInvocation.getMethodName(), inv.getMethodName());
        Assertions.assertEquals(decodeableRpcInvocation.getParameterTypesDesc(), inv.getParameterTypesDesc());
        Assertions.assertArrayEquals(decodeableRpcInvocation.getParameterTypes(), inv.getParameterTypes());
        Assertions.assertArrayEquals(decodeableRpcInvocation.getArguments(), inv.getArguments());
        Assertions.assertTrue(
                CollectionUtils.mapEquals(decodeableRpcInvocation.getObjectAttachments(), inv.getObjectAttachments()));
        Assertions.assertEquals(decodeableRpcInvocation.getTargetServiceUniqueName(), inv.getTargetServiceUniqueName());

        frameworkModel.destroy();
    }

    @Test
    void testDecodeNestedGenericCollectionArguments() throws Exception {
        // hessian2 widens Byte/Short/Float elements inside generic collections to Integer/Double,
        // so the declared generic parameter types must be used to restore them on the provider side.

        // Map<String, List<Byte>>
        Map<String, List<Byte>> byteChunks = new LinkedHashMap<>();
        byteChunks.put("c1", new ArrayList<>(Arrays.asList((byte) 1, (byte) 127, (byte) -1)));
        byteChunks.put("c2", new ArrayList<>(Arrays.asList((byte) 5, (byte) -8)));
        Object decodedChunks = decodeArgument("uploadChunks", Map.class, byteChunks);
        for (Object value : ((Map<?, ?>) decodedChunks).values()) {
            assertNoWidenedElement(value, Byte.class);
        }
        Assertions.assertEquals(byteChunks, decodedChunks);

        // List<List<Byte>>
        List<List<Byte>> nestedBytes = new ArrayList<>();
        nestedBytes.add(new ArrayList<>(Arrays.asList((byte) 1, (byte) 2, (byte) -3)));
        nestedBytes.add(new ArrayList<>(Arrays.asList((byte) 9, (byte) 10)));
        Object decodedNestedBytes = decodeArgument("uploadNestedBytes", List.class, nestedBytes);
        assertNoWidenedElement(decodedNestedBytes, Byte.class);
        Assertions.assertEquals(nestedBytes, decodedNestedBytes);

        // Map<String, List<Float>>
        Map<String, List<Float>> floatMetrics = new LinkedHashMap<>();
        floatMetrics.put("m1", new ArrayList<>(Arrays.asList(1.5f, -2.25f, 3.0f)));
        Object decodedFloatMetrics = decodeArgument("uploadMetrics", Map.class, floatMetrics);
        for (Object value : ((Map<?, ?>) decodedFloatMetrics).values()) {
            assertNoWidenedElement(value, Float.class);
        }
        Assertions.assertEquals(floatMetrics, decodedFloatMetrics);

        // List<List<Short>>
        List<List<Short>> nestedShorts = new ArrayList<>();
        nestedShorts.add(new ArrayList<>(Arrays.asList((short) 1, (short) 32767, (short) -1)));
        Object decodedNestedShorts = decodeArgument("uploadNestedShorts", List.class, nestedShorts);
        assertNoWidenedElement(decodedNestedShorts, Short.class);
        Assertions.assertEquals(nestedShorts, decodedNestedShorts);
    }

    private Object decodeArgument(String methodName, Class<?> parameterClass, Object argument) throws Exception {
        URL url = new ServiceConfigURL(
                "dubbo", "127.0.0.1", 9103, GenericCollectionService.class.getName(), VERSION_KEY, "1.0.0");
        RpcInvocation inv = new RpcInvocation(
                null,
                methodName,
                GenericCollectionService.class.getName(),
                "",
                new Class<?>[] {parameterClass},
                new Object[] {argument});
        inv.setObjectAttachment(PATH_KEY, url.getPath());
        inv.setObjectAttachment(VERSION_KEY, url.getVersion());
        inv.setObjectAttachment(DUBBO_VERSION_KEY, DUBBO_VERSION);
        inv.setTargetServiceUniqueName(url.getServiceKey());

        Byte proto = CodecSupport.getIDByName(DefaultSerializationSelector.getDefaultRemotingSerialization());
        ChannelBuffer buffer = writeBuffer(url, inv, proto);

        FrameworkModel frameworkModel = new FrameworkModel();
        try {
            ApplicationModel applicationModel = frameworkModel.newApplication();
            applicationModel
                    .getDefaultModule()
                    .getServiceRepository()
                    .registerService(GenericCollectionService.class.getName(), GenericCollectionService.class);
            frameworkModel
                    .getBeanFactory()
                    .getBean(PermittedSerializationKeeper.class)
                    .registerService(url);

            Channel channel = new MockChannel();
            Request request = new Request(1);
            ChannelBufferInputStream is = new ChannelBufferInputStream(buffer, buffer.readableBytes());
            DecodeableRpcInvocation decodeableRpcInvocation =
                    new DecodeableRpcInvocation(frameworkModel, channel, request, is, proto);
            decodeableRpcInvocation.decode();

            Object[] arguments = decodeableRpcInvocation.getArguments();
            Assertions.assertNotNull(arguments);
            Assertions.assertEquals(1, arguments.length);
            return arguments[0];
        } finally {
            frameworkModel.destroy();
        }
    }

    private static void assertNoWidenedElement(Object value, Class<?> expectedElementClass) {
        Iterable<?> elements = value instanceof Map ? ((Map<?, ?>) value).values() : (Iterable<?>) value;
        for (Object element : elements) {
            if (element instanceof Map || element instanceof Iterable) {
                assertNoWidenedElement(element, expectedElementClass);
            } else {
                Assertions.assertInstanceOf(
                        expectedElementClass,
                        element,
                        "generic collection element must not be widened during deserialization");
            }
        }
    }

    private ChannelBuffer writeBuffer(URL url, RpcInvocation inv, Byte proto) throws IOException {
        Serialization serialization = CodecSupport.getSerializationById(proto);
        ChannelBuffer buffer = ChannelBuffers.buffer(1024);
        ChannelBufferOutputStream outputStream = new ChannelBufferOutputStream(buffer);
        ObjectOutput out = serialization.serialize(url, outputStream);
        out.writeUTF(inv.getAttachment(DUBBO_VERSION_KEY)); // dubbo version
        out.writeUTF(inv.getAttachment(PATH_KEY)); // path
        out.writeUTF(inv.getAttachment(VERSION_KEY)); // version
        out.writeUTF(inv.getMethodName()); // methodName
        out.writeUTF(inv.getParameterTypesDesc()); // parameterTypesDesc
        Object[] args = inv.getArguments();
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                out.writeObject(args[i]); // args
            }
        }
        out.writeAttachments(inv.getObjectAttachments()); // attachments
        out.flushBuffer();
        outputStream.close();
        return buffer;
    }
}
