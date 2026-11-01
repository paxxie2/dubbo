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

import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.RequestMappingResolver;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.BeanMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.ParameterMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.TypeParameterMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.openapi.model.Schema;
import org.apache.dubbo.rpc.protocol.tri.rest.util.RestToolKit;

import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces <a href="https://github.com/apache/dubbo/issues/57">issue #57</a> for the
 * {@code dubbo-rest-openapi} module:
 *
 * <ol>
 *   <li>a nested generic response type such as {@code ApiResponse<PageResponse<AccessLogRes>>} loses the
 *       type arguments, so {@code data} resolves to {@code Object} and the {@code PageResponse} /
 *       {@code AccessLogRes} schemas are never produced;</li>
 *   <li>{@code io.swagger.v3.oas.annotations.media.Schema} metadata (description/example) on fields is
 *       dropped, and properties annotated with Dubbo's own
 *       {@code org.apache.dubbo.remoting.http12.rest.Schema} are dropped from the bean schema entirely.</li>
 * </ol>
 *
 * <p>All assertions go through the production resolution path
 * ({@link SchemaResolver#resolve(ParameterMeta)} with a {@link TypeParameterMeta} that carries the
 * {@link RestToolKit}) so that annotation attributes can be read.</p>
 */
class NestedGenericSchemaTest {

    private static final String SWAGGER_DESCRIPTION = "unique identifier of the access log";
    private static final String SWAGGER_EXAMPLE = "10086";
    private static final String DUBBO_DESCRIPTION = "identifier of the user who created the access log";

    private static final List<String> ACCESS_LOG_PROPERTIES = Arrays.asList("id", "userId", "action", "createTime");

    private FrameworkModel frameworkModel;
    private SchemaResolver schemaResolver;
    private RestToolKit toolKit;

    @BeforeEach
    void setUp() {
        frameworkModel = new FrameworkModel();
        schemaResolver = new SchemaResolver(frameworkModel);
        // Production always resolves through a ParameterMeta that carries the RestToolKit; annotation
        // attributes (description/example/hidden) can only be read with a non-null tool kit, so obtain the
        // basic one through the public RequestMappingResolver SPI.
        toolKit = frameworkModel
                .getExtensionLoader(RequestMappingResolver.class)
                .getExtension("basic")
                .getRestToolKit();
    }

    @AfterEach
    void tearDown() {
        frameworkModel.destroy();
    }

    /**
     * Bug 1: {@code ApiResponse<PageResponse<AccessLogRes>>} must resolve {@code data} to the
     * {@code PageResponse} bean and {@code records} to an array of {@code AccessLogRes} beans.
     */
    @Test
    void nestedGenericReturnTypeResolvesNestedBeanSchemas() throws NoSuchMethodException {
        Schema resolved = resolveSchema(logApiReturnType());
        assertNotNull(resolved, "resolve() returned null for the nested generic return type");

        Schema apiResponse = unwrapTarget(resolved);
        assertNotNull(
                apiResponse.getProperties(),
                "ApiResponse schema should expose bean properties, actual: " + describe(resolved));

        Schema data = requiredProperty(apiResponse, "data");
        Schema pageResponse = unwrapTarget(data);
        assertNotNull(
                pageResponse.getProperties(),
                "ApiResponse.data should resolve to the PageResponse bean schema, actual: " + describe(data));
        assertEquals(
                PageResponse.class,
                pageResponse.getJavaType(),
                "ApiResponse.data should resolve to PageResponse (generic argument of ApiResponse was ignored), actual: "
                        + describe(pageResponse));

        requiredProperty(pageResponse, "total");

        Schema records = unwrapTarget(requiredProperty(pageResponse, "records"));
        assertEquals(
                Schema.Type.ARRAY,
                records.getType(),
                "PageResponse.records should resolve to an array schema, actual: " + describe(records));
        assertNotNull(
                records.getItems(), "PageResponse.records should declare array items, actual: " + describe(records));

        Schema accessLog = unwrapTarget(records.getItems());
        assertNotNull(
                accessLog.getProperties(),
                "PageResponse.records items should resolve to the AccessLogRes bean schema, actual: "
                        + describe(records.getItems()));
        assertEquals(
                AccessLogRes.class,
                accessLog.getJavaType(),
                "PageResponse.records items should resolve to AccessLogRes (nested generic argument was ignored), actual: "
                        + describe(accessLog));
        assertTrue(
                accessLog.getProperties().keySet().containsAll(ACCESS_LOG_PROPERTIES),
                "AccessLogRes should expose properties " + ACCESS_LOG_PROPERTIES + ", actual: "
                        + accessLog.getProperties().keySet());
    }

    /**
     * Bug 2a: field level {@code io.swagger.v3.oas.annotations.media.Schema} description and example must be
     * carried over to the resolved property schema.
     */
    @Test
    void swaggerSchemaMetadataIsPreservedOnProperties() {
        Schema accessLog = unwrapTarget(resolveSchema(AccessLogRes.class));
        assertNotNull(
                accessLog.getProperties(),
                "AccessLogRes schema should expose bean properties, actual: " + describe(accessLog));

        Schema id = unwrapTarget(requiredProperty(accessLog, "id"));
        assertEquals(
                SWAGGER_DESCRIPTION,
                id.getDescription(),
                "io.swagger.v3.oas.annotations.media.Schema#description on AccessLogRes.id was lost, actual: "
                        + describe(id));
        assertEquals(
                SWAGGER_EXAMPLE,
                id.getExample(),
                "io.swagger.v3.oas.annotations.media.Schema#example on AccessLogRes.id was lost, actual: "
                        + describe(id));
    }

    /**
     * Bug 2b: a property annotated with Dubbo's own {@code org.apache.dubbo.remoting.http12.rest.Schema}
     * must not be dropped from the bean schema, and its description must be applied.
     */
    @Test
    void dubboSchemaAnnotatedPropertyIsNotDropped() {
        Schema accessLog = unwrapTarget(resolveSchema(AccessLogRes.class));
        assertNotNull(
                accessLog.getProperties(),
                "AccessLogRes schema should expose bean properties, actual: " + describe(accessLog));
        assertTrue(
                accessLog.getProperties().containsKey("userId"),
                "Property 'userId' annotated with org.apache.dubbo.remoting.http12.rest.Schema was dropped, actual properties: "
                        + accessLog.getProperties().keySet());

        Schema userId = unwrapTarget(accessLog.getProperty("userId"));
        assertEquals(
                DUBBO_DESCRIPTION,
                userId.getDescription(),
                "org.apache.dubbo.remoting.http12.rest.Schema#description on AccessLogRes.userId was lost, actual: "
                        + describe(userId));
    }

    /**
     * Bug 2c: properties flagged with {@code hidden = true} by either annotation must stay out of the
     * resolved schema.
     */
    @Test
    void hiddenPropertiesAreExcludedFromResolvedSchema() {
        Schema accessLog = unwrapTarget(resolveSchema(AccessLogRes.class));
        assertNotNull(
                accessLog.getProperties(),
                "AccessLogRes schema should expose bean properties, actual: " + describe(accessLog));
        // Control assertion: regular properties must be present, otherwise the negative assertions below
        // would also pass when the whole bean schema had silently become empty.
        assertTrue(
                accessLog.getProperties().keySet().containsAll(Arrays.asList("action", "createTime")),
                "Hidden-property fixture is ineffective: regular AccessLogRes properties are missing, actual properties: "
                        + accessLog.getProperties().keySet());

        assertFalse(
                accessLog.getProperties().containsKey("swaggerHidden"),
                "Property with io.swagger.v3.oas.annotations.media.Schema(hidden = true) must not be emitted, actual properties: "
                        + accessLog.getProperties().keySet());
        assertFalse(
                accessLog.getProperties().containsKey("dubboHidden"),
                "Property with org.apache.dubbo.remoting.http12.rest.Schema(hidden = true) must not be emitted, actual properties: "
                        + accessLog.getProperties().keySet());

        // Guard against a vacuously passing assertion: make sure the fixture really is visible to BeanMeta,
        // otherwise the absence above would not prove that the hidden flag was honoured.
        BeanMeta beanMeta = new BeanMeta(toolKit, AccessLogRes.class);
        assertNotNull(
                beanMeta.getProperty("swaggerHidden"),
                "Test fixture is ineffective: BeanMeta does not expose AccessLogRes.swaggerHidden");
        assertNotNull(
                beanMeta.getProperty("dubboHidden"),
                "Test fixture is ineffective: BeanMeta does not expose AccessLogRes.dubboHidden");
    }

    /**
     * The resolved schema graph must reach the {@code PageResponse} and {@code AccessLogRes} java types so
     * that they can be emitted into {@code components.schemas}.
     */
    @Test
    void resolvedSchemaGraphReachesNestedBeanJavaTypes() throws NoSuchMethodException {
        Schema resolved = resolveSchema(logApiReturnType());
        Set<Class<?>> javaTypes = collectJavaTypes(resolved);

        assertTrue(
                javaTypes.contains(ApiResponse.class),
                "ApiResponse should be reachable from the resolved schema graph, actual java types: " + javaTypes);
        assertTrue(
                javaTypes.contains(PageResponse.class),
                "PageResponse should be reachable from the resolved schema graph, actual java types: " + javaTypes);
        assertTrue(
                javaTypes.contains(AccessLogRes.class),
                "AccessLogRes should be reachable from the resolved schema graph, actual java types: " + javaTypes);
    }

    private static Type logApiReturnType() throws NoSuchMethodException {
        return LogApi.class.getDeclaredMethod("list").getGenericReturnType();
    }

    /**
     * Resolves a {@link Type} the way production does: through a {@link ParameterMeta} that carries the
     * {@link RestToolKit}. {@link SchemaResolver#resolve(Type)} creates a tool kit-less meta, which is fine
     * for annotation-free types (e.g. {@code ErrorResponse}) but cannot read annotation attributes.
     */
    private Schema resolveSchema(Type type) {
        return schemaResolver.resolve(new TypeParameterMeta(toolKit, type));
    }

    /**
     * Resolves the wrapper chain used by {@link SchemaResolver} for bean classes, i.e. a schema whose
     * {@link Schema#getTargetSchema()} points at the actual bean schema holding {@link Schema#getProperties()}.
     */
    private static Schema unwrapTarget(Schema schema) {
        Schema target = schema;
        for (int depth = 0; target != null && target.getTargetSchema() != null; depth++) {
            assertTrue(depth < 64, "targetSchema chain is unexpectedly deep");
            target = target.getTargetSchema();
        }
        return target;
    }

    private static Schema requiredProperty(Schema owner, String name) {
        assertNotNull(owner, "Cannot look up property '" + name + "' on a null schema");
        assertNotNull(
                owner.getProperties(),
                "Schema does not expose any properties, cannot look up '" + name + "', actual: " + describe(owner));
        Schema property = owner.getProperty(name);
        assertNotNull(
                property,
                "Missing property '" + name + "', actual properties: "
                        + owner.getProperties().keySet());
        return property;
    }

    private static Set<Class<?>> collectJavaTypes(Schema schema) {
        Set<Class<?>> javaTypes = new LinkedHashSet<>();
        collectJavaTypes(schema, javaTypes, Collections.newSetFromMap(new IdentityHashMap<>()));
        return javaTypes;
    }

    private static void collectJavaTypes(Schema schema, Set<Class<?>> javaTypes, Set<Schema> visited) {
        if (schema == null || !visited.add(schema)) {
            return;
        }
        if (schema.getJavaType() != null) {
            javaTypes.add(schema.getJavaType());
        }
        collectJavaTypes(schema.getTargetSchema(), javaTypes, visited);
        collectJavaTypes(schema.getItems(), javaTypes, visited);
        collectJavaTypes(schema.getAdditionalPropertiesSchema(), javaTypes, visited);
        collectJavaTypes(schema.getNot(), javaTypes, visited);
        if (schema.getProperties() != null) {
            for (Schema property : schema.getProperties().values()) {
                collectJavaTypes(property, javaTypes, visited);
            }
        }
        collectJavaTypes(schema.getAllOf(), javaTypes, visited);
        collectJavaTypes(schema.getOneOf(), javaTypes, visited);
        collectJavaTypes(schema.getAnyOf(), javaTypes, visited);
    }

    private static void collectJavaTypes(List<Schema> schemas, Set<Class<?>> javaTypes, Set<Schema> visited) {
        if (schemas == null) {
            return;
        }
        for (Schema schema : schemas) {
            collectJavaTypes(schema, javaTypes, visited);
        }
    }

    private static String describe(Schema schema) {
        if (schema == null) {
            return "null";
        }
        return "Schema{type=" + schema.getType()
                + ", javaType="
                + (schema.getJavaType() == null ? null : schema.getJavaType().getName())
                + ", properties="
                + (schema.getProperties() == null
                        ? null
                        : schema.getProperties().keySet())
                + ", hasItems=" + (schema.getItems() != null)
                + ", hasTargetSchema=" + (schema.getTargetSchema() != null)
                + '}';
    }

    interface LogApi {
        ApiResponse<PageResponse<AccessLogRes>> list();
    }

    public static class ApiResponse<T> {

        private int code;
        private String message;
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

        private List<T> records;
        private long total;

        public List<T> getRecords() {
            return records;
        }

        public void setRecords(List<T> records) {
            this.records = records;
        }

        public long getTotal() {
            return total;
        }

        public void setTotal(long total) {
            this.total = total;
        }
    }

    public static class AccessLogRes {

        @io.swagger.v3.oas.annotations.media.Schema(description = SWAGGER_DESCRIPTION, example = SWAGGER_EXAMPLE)
        private String id;

        @org.apache.dubbo.remoting.http12.rest.Schema(description = DUBBO_DESCRIPTION)
        private String userId;

        private String action;

        private Long createTime;

        @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
        private String swaggerHidden;

        @org.apache.dubbo.remoting.http12.rest.Schema(hidden = true)
        private String dubboHidden;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public Long getCreateTime() {
            return createTime;
        }

        public void setCreateTime(Long createTime) {
            this.createTime = createTime;
        }

        public String getSwaggerHidden() {
            return swaggerHidden;
        }

        public void setSwaggerHidden(String swaggerHidden) {
            this.swaggerHidden = swaggerHidden;
        }

        public String getDubboHidden() {
            return dubboHidden;
        }

        public void setDubboHidden(String dubboHidden) {
            this.dubboHidden = dubboHidden;
        }
    }
}
