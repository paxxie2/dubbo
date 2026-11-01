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
package org.apache.dubbo.rpc.protocol.tri.rest.openapi

import org.apache.dubbo.common.URL
import org.apache.dubbo.common.constants.CommonConstants
import org.apache.dubbo.common.utils.ClassUtils
import org.apache.dubbo.remoting.http12.rest.OpenAPIRequest
import org.apache.dubbo.remoting.http12.rest.OpenAPIService
import org.apache.dubbo.rpc.ProxyFactory
import org.apache.dubbo.rpc.model.ApplicationModel
import org.apache.dubbo.rpc.model.FrameworkModel
import org.apache.dubbo.rpc.model.ProviderModel
import org.apache.dubbo.rpc.model.ServiceMetadata
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.DefaultRequestMappingRegistry
import org.apache.dubbo.rpc.protocol.tri.rest.openapi.service.AccessLogService
import org.apache.dubbo.rpc.protocol.tri.rest.openapi.service.AccessLogServiceImpl

import spock.lang.Shared
import spock.lang.Specification

class OpenApiDocumentTest extends Specification {

    @Shared
    FrameworkModel frameworkModel

    @Shared
    ApplicationModel applicationModel

    @Shared
    OpenAPIService openAPIService

    def setupSpec() {
        frameworkModel = FrameworkModel.defaultModel()
        applicationModel = frameworkModel.newApplication()

        DefaultRequestMappingRegistry registry =
                frameworkModel.getBeanFactory().getOrRegisterBean(DefaultRequestMappingRegistry.class)
        ProxyFactory proxyFactory =
                applicationModel.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension()

        Class<?> type = AccessLogService.class
        Map<String, String> parameters = new LinkedHashMap<>()
        parameters.put(CommonConstants.INTERFACE_KEY, type.getName())
        URL url = new URL("test", "127.0.0.1", 8081, type.getName(), parameters)

        Object service = new AccessLogServiceImpl()
        def serviceRepository = applicationModel.getDefaultModule().getServiceRepository()
        def serviceDescriptor = serviceRepository.registerService(type)
        ProviderModel providerModel = new ProviderModel(
                url.getServiceKey(),
                service,
                serviceDescriptor,
                new ServiceMetadata(),
                ClassUtils.getClassLoader(type))
        serviceRepository.registerProvider(providerModel)
        url = url.setServiceModel(providerModel)
        registry.register(proxyFactory.getInvoker(service, (Class) type, url))

        openAPIService = frameworkModel.getBean(OpenAPIService.class)
    }

    def cleanupSpec() {
        if (applicationModel != null) {
            applicationModel.destroy()
        }
    }

    def "nested generic response models are resolved into component schemas"() {
        given:
        def openAPI = openAPIService.getOpenAPI(new OpenAPIRequest())
        def schemas = openAPI.getComponents().getSchemas()

        expect:
        schemas != null
        schemas.keySet().containsAll(["ApiResponse", "PageResponse", "AccessLogRes"])
        schemas["ApiResponse"].getProperties()["data"].getRef() == "#/components/schemas/PageResponse"
        schemas["PageResponse"].getProperties()["records"].getItems().getRef() ==
                "#/components/schemas/AccessLogRes"
    }

    def "swagger Schema annotations are applied to generated schemas"() {
        given:
        def openAPI = openAPIService.getOpenAPI(new OpenAPIRequest())
        def schemas = openAPI.getComponents().getSchemas()

        expect:
        schemas["ApiResponse"].getProperties()["message"].getDescription() == "Human readable message"
        schemas["PageResponse"].getProperties()["total"].getDescription() == "Total record count"
        schemas["AccessLogRes"].getProperties()["operator"].getDescription() == "Operator name"
        schemas["AccessLogRes"].getProperties()["operator"].getExample() == "alice"
        !schemas["AccessLogRes"].getProperties().containsKey("internalToken")
    }
}
