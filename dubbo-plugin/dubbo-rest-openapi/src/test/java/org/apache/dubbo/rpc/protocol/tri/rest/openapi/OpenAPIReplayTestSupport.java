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
package org.apache.dubbo.rpc.protocol.tri.rest.openapi;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.http12.HttpRequest;
import org.apache.dubbo.remoting.http12.HttpResponse;
import org.apache.dubbo.remoting.http12.rest.OpenAPIRequest;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.ReflectionMethodDescriptor;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.Registration;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.RequestMapping;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.HandlerMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.MethodMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.NamedValueMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.ParameterMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.ServiceMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.openapi.model.OpenAPI;
import org.apache.dubbo.rpc.protocol.tri.rest.util.RestToolKit;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Builds a merged OpenAPI document from a service method without starting a server, so that schema
 * resolution can be verified end to end.
 */
final class OpenAPIReplayTestSupport {

    private OpenAPIReplayTestSupport() {}

    static OpenAPI buildDocument(FrameworkModel frameworkModel, Class<?> serviceType, String methodName, String path) {
        try {
            return doBuildDocument(frameworkModel, serviceType, methodName, path);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OpenAPI doBuildDocument(
            FrameworkModel frameworkModel, Class<?> serviceType, String methodName, String path)
            throws ReflectiveOperationException {
        Method method = serviceType.getMethod(methodName);
        ReflectionMethodDescriptor methodDescriptor = new ReflectionMethodDescriptor(method);
        ServiceMeta serviceMeta = new ServiceMeta(
                Collections.singletonList(serviceType),
                null,
                null,
                URL.valueOf("tri://127.0.0.1:8080/" + serviceType.getName()),
                new TestRestToolKit());
        MethodMeta methodMeta = new MethodMeta(Collections.singletonList(method), methodDescriptor, serviceMeta);
        methodMeta.initParameters();
        HandlerMeta handlerMeta = new HandlerMeta(null, methodMeta, null, methodDescriptor, null);
        RequestMapping mapping =
                RequestMapping.builder().path(path).method("GET").build();
        Registration registration = new Registration(mapping, handlerMeta);
        List<Registration> registrations = Collections.singletonList(registration);

        DefinitionResolver definitionResolver = new DefinitionResolver(frameworkModel);
        OpenAPI openAPI = definitionResolver.resolve(serviceMeta, Collections.singletonList(registrations));
        if (openAPI == null) {
            throw new IllegalStateException("Failed to resolve OpenAPI definition for " + serviceType.getName());
        }

        DefinitionMerger definitionMerger = new DefinitionMerger(frameworkModel);
        return definitionMerger.merge(Collections.singletonList(openAPI), new OpenAPIRequest());
    }

    interface AccessLogService {

        ApiResponse<PageResponse<AccessLogRes>> getAccessLogs();
    }

    public static class ApiResponse<T> {

        @Schema(description = "result code", example = "0")
        private int code;

        @Schema(description = "result message")
        private String message;

        @Schema(description = "result data")
        private T data;

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public T getData() {
            return data;
        }

        public void setData(T data) {
            this.data = data;
        }
    }

    public static class PageResponse<T> {

        @Schema(description = "total records")
        private long total;

        @Schema(description = "record list")
        private List<T> records;

        public long getTotal() {
            return total;
        }

        public void setTotal(long total) {
            this.total = total;
        }

        public List<T> getRecords() {
            return records;
        }

        public void setRecords(List<T> records) {
            this.records = records;
        }
    }

    public static class AccessLogRes {

        @Schema(description = "operator user name")
        private String username;

        @Schema(description = "request path")
        private String path;

        @Schema(description = "elapsed milliseconds")
        private long cost;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public long getCost() {
            return cost;
        }

        public void setCost(long cost) {
            this.cost = cost;
        }
    }

    private static final class TestRestToolKit implements RestToolKit {

        @Override
        public int getDialect() {
            return 0;
        }

        @Override
        public String resolvePlaceholders(String text) {
            return text;
        }

        @Override
        public Object convert(Object value, ParameterMeta parameter) {
            return value;
        }

        @Override
        public Object bind(ParameterMeta parameter, HttpRequest request, HttpResponse response) {
            return null;
        }

        @Override
        public NamedValueMeta getNamedValueMeta(ParameterMeta parameter) {
            return null;
        }

        @Override
        public String[] getParameterNames(Method method) {
            return null;
        }

        @Override
        public String[] getParameterNames(Constructor<?> ctor) {
            return null;
        }

        @Override
        public Map<String, Object> getAttributes(AnnotatedElement element, Annotation annotation) {
            return null;
        }
    }
}
