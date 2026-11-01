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

class OpenAPISchemaAnnotationTest {

    private static OpenAPI document;

    @BeforeAll
    static void setUp() {
        FrameworkModel frameworkModel = FrameworkModel.defaultModel();
        document = OpenAPIReplayTestSupport.buildDocument(
                frameworkModel, OpenAPIReplayTestSupport.AccessLogService.class, "getAccessLogs", "/access-logs");
    }

    @Test
    void modelPropertySchemaAnnotationsAreApplied() {
        Map<String, Schema> schemas = document.getComponents().getSchemas();
        assertNotNull(schemas);
        Schema apiResponse = schemas.get("ApiResponse");
        assertNotNull(apiResponse, () -> "Missing ApiResponse schema, schemas: " + schemas.keySet());

        Map<String, Schema> properties = apiResponse.getProperties();
        assertNotNull(properties);
        assertEquals("result code", properties.get("code").getDescription());
        assertEquals("0", properties.get("code").getExample());
        assertEquals("result message", properties.get("message").getDescription());

        Schema pageResponse = schemas.get("PageResponse");
        assertNotNull(pageResponse, () -> "Missing PageResponse schema, schemas: " + schemas.keySet());
        assertEquals("total records", pageResponse.getProperties().get("total").getDescription());
        assertEquals("record list", pageResponse.getProperties().get("records").getDescription());

        Schema accessLogRes = schemas.get("AccessLogRes");
        assertNotNull(accessLogRes, () -> "Missing AccessLogRes schema, schemas: " + schemas.keySet());
        assertEquals(
                "operator user name",
                accessLogRes.getProperties().get("username").getDescription());
    }
}
