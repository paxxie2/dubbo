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

import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.RadixTree;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.RadixTree.Match;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.AnnotationMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.BeanMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.BeanMeta.PropertyMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.ParameterMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.meta.TypeParameterMeta;
import org.apache.dubbo.rpc.protocol.tri.rest.openapi.OpenAPISchemaResolver.SchemaChain;
import org.apache.dubbo.rpc.protocol.tri.rest.openapi.OpenAPISchemaResolver.SchemaContext;
import org.apache.dubbo.rpc.protocol.tri.rest.openapi.model.Schema;
import org.apache.dubbo.rpc.protocol.tri.rest.support.basic.Annotations;
import org.apache.dubbo.rpc.protocol.tri.rest.util.RestToolKit;
import org.apache.dubbo.rpc.protocol.tri.rest.util.TypeUtils;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.apache.dubbo.rpc.protocol.tri.rest.openapi.PrimitiveSchema.ARRAY;
import static org.apache.dubbo.rpc.protocol.tri.rest.openapi.PrimitiveSchema.OBJECT;

public final class SchemaResolver {

    private final ConfigFactory configFactory;
    private final OpenAPISchemaResolver[] resolvers;
    private final OpenAPISchemaPredicate[] predicates;
    private final Map<Class<?>, Schema> schemaMap = CollectionUtils.newConcurrentHashMap();
    private final Map<String, Schema> parameterizedSchemaMap = CollectionUtils.newConcurrentHashMap();

    private volatile RadixTree<Boolean> classFilter;

    public SchemaResolver(FrameworkModel frameworkModel) {
        configFactory = frameworkModel.getOrRegisterBean(ConfigFactory.class);
        ExtensionFactory extensionFactory = frameworkModel.getOrRegisterBean(ExtensionFactory.class);
        resolvers = extensionFactory.getExtensions(OpenAPISchemaResolver.class);
        predicates = extensionFactory.getExtensions(OpenAPISchemaPredicate.class);
    }

    public Schema resolve(Type type) {
        return resolve(new TypeParameterMeta(type));
    }

    public Schema resolve(ParameterMeta parameter) {
        return new SchemaChainImpl(resolvers, this::fallbackResolve).resolve(parameter, new SchemaContextImpl());
    }

    public Schema resolve(List<ParameterMeta> parameters) {
        Schema schema = OBJECT.newSchema();
        for (ParameterMeta parameter : parameters) {
            String name = parameter.getName();
            if (name == null) {
                return ARRAY.newSchema();
            }
            schema.addProperty(name, resolve(parameter));
        }
        return schema;
    }

    private Schema fallbackResolve(ParameterMeta parameter) {
        return doResolveType(parameter.getActualGenericType(), parameter);
    }

    private Schema doResolveNestedType(Type nestedType, ParameterMeta parameter) {
        return doResolveType(nestedType, new TypeParameterMeta(parameter.getToolKit(), nestedType));
    }

    private Schema doResolveType(Type type, ParameterMeta parameter) {
        if (type instanceof Class) {
            return doResolveClass((Class<?>) type, parameter);
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType pType = (ParameterizedType) type;
            Type rawType = pType.getRawType();
            if (rawType instanceof Class) {
                Class<?> clazz = (Class<?>) rawType;
                Type[] argTypes = pType.getActualTypeArguments();
                if (Iterable.class.isAssignableFrom(clazz)) {
                    Type itemType = TypeUtils.getActualGenericType(argTypes[0]);
                    return ARRAY.newSchema()
                            .addExtension(Constants.X_JAVA_CLASS, TypeUtils.toTypeString(type))
                            .setItems(doResolveNestedType(itemType, parameter));
                }

                if (Map.class.isAssignableFrom(clazz)) {
                    return OBJECT.newSchema()
                            .addExtension(Constants.X_JAVA_CLASS, TypeUtils.toTypeString(type))
                            .setAdditionalPropertiesSchema(doResolveNestedType(argTypes[1], parameter));
                }

                return doResolveClass(clazz, parameter, pType);
            }
        }
        if (type instanceof TypeVariable) {
            return doResolveNestedType(((TypeVariable<?>) type).getBounds()[0], parameter);
        }
        if (type instanceof WildcardType) {
            return doResolveNestedType(((WildcardType) type).getUpperBounds()[0], parameter);
        }
        if (type instanceof GenericArrayType) {
            return ARRAY.newSchema()
                    .addExtension(Constants.X_JAVA_CLASS, TypeUtils.toTypeString(type))
                    .setItems(doResolveNestedType(((GenericArrayType) type).getGenericComponentType(), parameter));
        }
        return OBJECT.newSchema();
    }

    private Schema doResolveClass(Class<?> clazz, ParameterMeta parameter) {
        return doResolveClass(clazz, parameter, null);
    }

    private Schema doResolveClass(Class<?> clazz, ParameterMeta parameter, ParameterizedType pType) {
        Schema schema = PrimitiveSchema.newSchemaOf(clazz);
        if (schema != null) {
            return schema;
        }

        if (clazz.isArray()) {
            schema = ARRAY.newSchema();
            if (!PrimitiveSchema.isPrimitive(clazz.getComponentType())) {
                schema.addExtension(Constants.X_JAVA_CLASS, TypeUtils.toTypeString(clazz));
            }
            return schema.setItems(doResolveNestedType(clazz.getComponentType(), parameter));
        }

        Map<TypeVariable<?>, Type> typeVariables = pType == null ? null : getTypeVariables(pType);
        boolean generic = typeVariables != null && !typeVariables.isEmpty();
        String genericKey = generic ? TypeUtils.toTypeString(pType) : null;

        Schema existingSchema = generic ? parameterizedSchemaMap.get(genericKey) : schemaMap.get(clazz);
        if (existingSchema != null) {
            return new Schema().setTargetSchema(existingSchema);
        }

        String javaClass = generic ? genericKey : TypeUtils.toTypeString(clazz);
        if (isClassExcluded(clazz)) {
            schema = OBJECT.newSchema().addExtension(Constants.X_JAVA_CLASS, javaClass);
            putSchema(clazz, genericKey, schema);
            return schema;
        }

        TypeParameterMeta typeParameter = new TypeParameterMeta(clazz);
        for (OpenAPISchemaPredicate predicate : predicates) {
            Boolean accepted = predicate.acceptClass(clazz, typeParameter);
            if (accepted == null) {
                continue;
            }
            if (accepted) {
                break;
            } else {
                schema = OBJECT.newSchema().addExtension(Constants.X_JAVA_CLASS, javaClass);
                putSchema(clazz, genericKey, schema);
                return schema;
            }
        }

        if (clazz.isEnum()) {
            schema = PrimitiveSchema.STRING.newSchema().setJavaType(clazz);
            for (Object value : clazz.getEnumConstants()) {
                schema.addEnumeration(value);
            }
            putSchema(clazz, genericKey, schema);
            return schema.clone();
        }

        Boolean flatten = configFactory.getGlobalConfig().getSchemaFlatten();
        if (flatten == null) {
            AnnotationMeta<?> anno = typeParameter.getAnnotation(Annotations.Schema);
            flatten = anno != null && anno.getBoolean("flatten");
        }

        Schema beanSchema = OBJECT.newSchema().setJavaType(clazz);
        putSchema(clazz, genericKey, beanSchema);
        resolveBeanProperties(parameter.getToolKit(), clazz, flatten, beanSchema, typeVariables);
        return new Schema().setTargetSchema(beanSchema);
    }

    private void putSchema(Class<?> clazz, String genericKey, Schema schema) {
        if (genericKey == null) {
            schemaMap.put(clazz, schema);
        } else {
            parameterizedSchemaMap.put(genericKey, schema);
        }
    }

    private void resolveBeanProperties(
            RestToolKit toolKit,
            Class<?> clazz,
            boolean flatten,
            Schema beanSchema,
            Map<TypeVariable<?>, Type> typeVariables) {
        BeanMeta beanMeta = new BeanMeta(toolKit, clazz, flatten);
        out:
        for (PropertyMeta property : beanMeta.getProperties()) {
            boolean fallback = true;
            for (OpenAPISchemaPredicate predicate : predicates) {
                Boolean accepted = predicate.acceptProperty(beanMeta, property);
                if (accepted == null) {
                    continue;
                }
                if (accepted) {
                    fallback = false;
                    break;
                } else {
                    continue out;
                }
            }

            if (fallback) {
                int visibility = property.getVisibility();
                if ((visibility & 0b001) == 0 || (visibility & 0b110) == 0) {
                    continue;
                }
            }

            ParameterMeta propertyMeta = property;
            if (typeVariables != null) {
                Type genericType = property.getGenericType();
                Type resolvedType = resolveTypeVariables(genericType, typeVariables);
                if (resolvedType != genericType) {
                    propertyMeta = new GenericParameterMeta(property, resolvedType);
                }
            }
            beanSchema.addProperty(property.getName(), resolve(propertyMeta));
        }

        if (flatten) {
            return;
        }
        Class<?> superClass = clazz.getSuperclass();
        if (superClass == null || superClass == Object.class || TypeUtils.isSystemType(superClass)) {
            return;
        }

        beanSchema.addAllOf(resolve(superClass));
    }

    private static Map<TypeVariable<?>, Type> getTypeVariables(ParameterizedType pType) {
        Type rawType = pType.getRawType();
        if (!(rawType instanceof Class)) {
            return null;
        }
        TypeVariable<?>[] typeParameters = ((Class<?>) rawType).getTypeParameters();
        Type[] typeArguments = pType.getActualTypeArguments();
        int len = Math.min(typeParameters.length, typeArguments.length);
        if (len == 0) {
            return null;
        }
        Map<TypeVariable<?>, Type> typeVariables = new HashMap<>(len);
        for (int i = 0; i < len; i++) {
            typeVariables.put(typeParameters[i], resolveTypeVariables(typeArguments[i], typeVariables));
        }
        return typeVariables;
    }

    private static Type resolveTypeVariables(Type type, Map<TypeVariable<?>, Type> typeVariables) {
        if (type instanceof TypeVariable) {
            Type resolvedType = typeVariables.get(type);
            return resolvedType == null ? type : resolvedType;
        }
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            Type[] typeArguments = parameterizedType.getActualTypeArguments();
            Type[] resolvedTypeArguments = resolveTypeVariables(typeArguments, typeVariables);
            if (resolvedTypeArguments == null) {
                return type;
            }
            return new ParameterizedTypeImpl(
                    parameterizedType.getOwnerType(), parameterizedType.getRawType(), resolvedTypeArguments);
        }
        if (type instanceof GenericArrayType) {
            GenericArrayType genericArrayType = (GenericArrayType) type;
            Type componentType = genericArrayType.getGenericComponentType();
            Type resolvedComponentType = resolveTypeVariables(componentType, typeVariables);
            return resolvedComponentType == componentType ? type : new GenericArrayTypeImpl(resolvedComponentType);
        }
        if (type instanceof WildcardType) {
            WildcardType wildcardType = (WildcardType) type;
            Type[] upperBounds = resolveTypeVariables(wildcardType.getUpperBounds(), typeVariables);
            Type[] lowerBounds = resolveTypeVariables(wildcardType.getLowerBounds(), typeVariables);
            if (upperBounds == null && lowerBounds == null) {
                return type;
            }
            return new WildcardTypeImpl(
                    upperBounds == null ? wildcardType.getUpperBounds() : upperBounds,
                    lowerBounds == null ? wildcardType.getLowerBounds() : lowerBounds);
        }
        return type;
    }

    private static Type[] resolveTypeVariables(Type[] types, Map<TypeVariable<?>, Type> typeVariables) {
        Type[] resolvedTypes = null;
        for (int i = 0; i < types.length; i++) {
            Type type = types[i];
            Type resolvedType = resolveTypeVariables(type, typeVariables);
            if (resolvedType != type) {
                if (resolvedTypes == null) {
                    resolvedTypes = types.clone();
                }
                resolvedTypes[i] = resolvedType;
            }
        }
        return resolvedTypes;
    }

    private boolean isClassExcluded(Class<?> clazz) {
        RadixTree<Boolean> classFilter = this.classFilter;
        if (classFilter == null) {
            synchronized (this) {
                classFilter = this.classFilter;
                if (classFilter == null) {
                    classFilter = new RadixTree<>('.');
                    for (String prefix : TypeUtils.getSystemPrefixes()) {
                        addPath(classFilter, prefix);
                    }
                    String[] excludes = configFactory.getGlobalConfig().getSchemaClassExcludes();
                    if (excludes != null) {
                        for (String exclude : excludes) {
                            addPath(classFilter, exclude);
                        }
                    }
                    this.classFilter = classFilter;
                }
            }
        }

        List<Match<Boolean>> matches = classFilter.match('.' + clazz.getName());
        int size = matches.size();
        if (size == 0) {
            return false;
        } else if (size > 1) {
            Collections.sort(matches);
        }
        return matches.get(0).getValue();
    }

    public static void addPath(RadixTree<Boolean> tree, String path) {
        if (path == null) {
            return;
        }
        int size = path.length();
        if (size == 0) {
            return;
        }
        boolean value = true;
        if (path.charAt(0) == '!') {
            path = path.substring(1);
            size--;
            value = false;
        }
        if (path.charAt(size - 1) == '.') {
            path += "**";
        }
        tree.addPath(path, value);
    }

    private static final class GenericParameterMeta extends ParameterMeta {

        private final ParameterMeta delegate;
        private final Type genericType;

        GenericParameterMeta(ParameterMeta delegate, Type genericType) {
            super(delegate.getToolKit(), delegate.getPrefix(), null);
            this.delegate = delegate;
            this.genericType = genericType;
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        @Override
        public Class<?> getType() {
            return TypeUtils.getActualType(genericType);
        }

        @Override
        public Type getGenericType() {
            return genericType;
        }

        @Override
        protected AnnotatedElement getAnnotatedElement() {
            List<? extends AnnotatedElement> elements = delegate.getAnnotatedElements();
            return elements.isEmpty() ? null : elements.get(0);
        }

        @Override
        public List<? extends AnnotatedElement> getAnnotatedElements() {
            return delegate.getAnnotatedElements();
        }

        @Override
        public String getDescription() {
            return delegate.getDescription();
        }
    }

    private static final class ParameterizedTypeImpl implements ParameterizedType {

        private final Type ownerType;
        private final Type rawType;
        private final Type[] actualTypeArguments;

        ParameterizedTypeImpl(Type ownerType, Type rawType, Type[] actualTypeArguments) {
            this.ownerType = ownerType;
            this.rawType = rawType;
            this.actualTypeArguments = actualTypeArguments;
        }

        @Override
        public Type[] getActualTypeArguments() {
            return actualTypeArguments;
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public Type getOwnerType() {
            return ownerType;
        }

        @Override
        public String toString() {
            return TypeUtils.toTypeString(this);
        }
    }

    private static final class GenericArrayTypeImpl implements GenericArrayType {

        private final Type genericComponentType;

        GenericArrayTypeImpl(Type genericComponentType) {
            this.genericComponentType = genericComponentType;
        }

        @Override
        public Type getGenericComponentType() {
            return genericComponentType;
        }

        @Override
        public String toString() {
            return TypeUtils.toTypeString(this);
        }
    }

    private static final class WildcardTypeImpl implements WildcardType {

        private final Type[] upperBounds;
        private final Type[] lowerBounds;

        WildcardTypeImpl(Type[] upperBounds, Type[] lowerBounds) {
            this.upperBounds = upperBounds;
            this.lowerBounds = lowerBounds;
        }

        @Override
        public Type[] getUpperBounds() {
            return upperBounds;
        }

        @Override
        public Type[] getLowerBounds() {
            return lowerBounds;
        }

        @Override
        public String toString() {
            return TypeUtils.toTypeString(this);
        }
    }

    private static final class SchemaChainImpl implements SchemaChain {

        private final OpenAPISchemaResolver[] resolvers;
        private final Function<ParameterMeta, Schema> fallback;
        private int cursor;

        SchemaChainImpl(OpenAPISchemaResolver[] resolvers, Function<ParameterMeta, Schema> fallback) {
            this.resolvers = resolvers;
            this.fallback = fallback;
        }

        @Override
        public Schema resolve(ParameterMeta parameter, SchemaContext context) {
            if (cursor < resolvers.length) {
                return resolvers[cursor++].resolve(parameter, context, this);
            }
            return fallback.apply(parameter);
        }
    }

    private final class SchemaContextImpl implements SchemaContext {

        @Override
        public void defineSchema(Class<?> type, Schema schema) {
            schemaMap.putIfAbsent(type, schema);
        }

        @Override
        public Schema resolve(ParameterMeta parameter) {
            return SchemaResolver.this.resolve(parameter);
        }

        @Override
        public Schema resolve(Type type) {
            return SchemaResolver.this.resolve(type);
        }
    }
}
