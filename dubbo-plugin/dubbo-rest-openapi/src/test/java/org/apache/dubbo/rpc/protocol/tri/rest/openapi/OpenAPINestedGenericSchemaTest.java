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
import org.apache.dubbo.rpc.protocol.tri.rest.openapi.model.OpenAPI;
import org.apache.dubbo.rpc.protocol.tri.rest.openapi.model.Schema;

import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAPINestedGenericSchemaTest {

    private static OpenAPI document;

    @BeforeAll
    static void setUp() {
        FrameworkModel frameworkModel = FrameworkModel.defaultModel();
        document = OpenAPIReplayTestSupport.buildDocument(
                frameworkModel, OpenAPIReplayTestSupport.AccessLogService.class, "getAccessLogs", "/access-logs");
    }

    @Test
    void nestedGenericResponseSchemasAreRegistered() {
        Map<String, Schema> schemas = document.getComponents().getSchemas();
        assertNotNull(schemas);
        assertTrue(schemas.containsKey("ApiResponse"), () -> "schemas: " + schemas.keySet());
        assertTrue(schemas.containsKey("PageResponse"), () -> "schemas: " + schemas.keySet());
        assertTrue(schemas.containsKey("AccessLogRes"), () -> "schemas: " + schemas.keySet());

        Schema apiResponse = schemas.get("ApiResponse");
        Schema data = apiResponse.getProperties().get("data");
        assertNotNull(data);
        assertEquals("#/components/schemas/PageResponse", data.getRef());

        Schema pageResponse = schemas.get("PageResponse");
        Schema records = pageResponse.getProperties().get("records");
        assertNotNull(
                records,
                () -> "PageResponse properties: " + pageResponse.getProperties().keySet());
        assertEquals("#/components/schemas/AccessLogRes", records.getItems().getRef());
    }
}
