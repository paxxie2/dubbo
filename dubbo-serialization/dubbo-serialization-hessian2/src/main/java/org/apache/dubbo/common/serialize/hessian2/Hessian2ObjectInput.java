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
package org.apache.dubbo.common.serialize.hessian2;

import org.apache.dubbo.common.serialize.Cleanable;
import org.apache.dubbo.common.serialize.ObjectInput;
import org.apache.dubbo.rpc.model.FrameworkModel;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.alibaba.com.caucho.hessian.io.Hessian2Input;

/**
 * Hessian2 object input implementation
 */
public class Hessian2ObjectInput implements ObjectInput, Cleanable {
    private final Hessian2Input mH2i;
    private final Hessian2FactoryManager hessian2FactoryManager;

    @Deprecated
    public Hessian2ObjectInput(InputStream is) {
        mH2i = new Hessian2Input(is);
        this.hessian2FactoryManager =
                FrameworkModel.defaultModel().getBeanFactory().getOrRegisterBean(Hessian2FactoryManager.class);
        mH2i.setSerializerFactory(hessian2FactoryManager.getSerializerFactory(
                Thread.currentThread().getContextClassLoader()));
    }

    public Hessian2ObjectInput(InputStream is, Hessian2FactoryManager hessian2FactoryManager) {
        mH2i = new Hessian2Input(is);
        this.hessian2FactoryManager = hessian2FactoryManager;
        mH2i.setSerializerFactory(hessian2FactoryManager.getSerializerFactory(
                Thread.currentThread().getContextClassLoader()));
    }

    @Override
    public boolean readBool() throws IOException {
        return mH2i.readBoolean();
    }

    @Override
    public byte readByte() throws IOException {
        return (byte) mH2i.readInt();
    }

    @Override
    public short readShort() throws IOException {
        return (short) mH2i.readInt();
    }

    @Override
    public int readInt() throws IOException {
        return mH2i.readInt();
    }

    @Override
    public long readLong() throws IOException {
        return mH2i.readLong();
    }

    @Override
    public float readFloat() throws IOException {
        return (float) mH2i.readDouble();
    }

    @Override
    public double readDouble() throws IOException {
        return mH2i.readDouble();
    }

    @Override
    public byte[] readBytes() throws IOException {
        return mH2i.readBytes();
    }

    @Override
    public String readUTF() throws IOException {
        return mH2i.readString();
    }

    @Override
    public Object readObject() throws IOException {
        if (!Objects.equals(
                mH2i.getSerializerFactory().getClassLoader(),
                Thread.currentThread().getContextClassLoader())) {
            mH2i.setSerializerFactory(hessian2FactoryManager.getSerializerFactory(
                    Thread.currentThread().getContextClassLoader()));
        }
        return mH2i.readObject();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T readObject(Class<T> cls) throws IOException, ClassNotFoundException {
        if (!Objects.equals(
                mH2i.getSerializerFactory().getClassLoader(),
                Thread.currentThread().getContextClassLoader())) {
            mH2i.setSerializerFactory(hessian2FactoryManager.getSerializerFactory(
                    Thread.currentThread().getContextClassLoader()));
        }
        return (T) mH2i.readObject(cls);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T readObject(Class<T> cls, Type type) throws IOException, ClassNotFoundException {
        if (!Objects.equals(
                mH2i.getSerializerFactory().getClassLoader(),
                Thread.currentThread().getContextClassLoader())) {
            mH2i.setSerializerFactory(hessian2FactoryManager.getSerializerFactory(
                    Thread.currentThread().getContextClassLoader()));
        }
        if (containsNarrowableElement(type)) {
            // hessian2 encodes Byte/Short/Integer all as int and Float/Double as double on the wire,
            // so narrow element types can only be restored from the declared generic type. hessian's
            // expectedTypes mechanism only covers one level, so read the value with the erased class
            // and then narrow its elements to the declared generic type recursively.
            return (T) narrowByType(mH2i.readObject(cls), type);
        }
        return (T) mH2i.readObject(cls);
    }

    /**
     * Returns whether {@code type} is a generic collection or map whose declared type arguments
     * contain a narrow primitive-wrapper type (Byte/Short/Float) anywhere in the nesting
     * hierarchy. Only those types need the recursive narrowing pass; for instance {@code List<String>}
     * and {@code List<Integer>} keep the plain deserialization path.
     */
    private static boolean containsNarrowableElement(Type type) {
        if (!(type instanceof ParameterizedType)) {
            return false;
        }
        ParameterizedType parameterizedType = (ParameterizedType) type;
        Type rawType = parameterizedType.getRawType();
        if (!(rawType instanceof Class)) {
            return false;
        }
        Class<?> rawClass = (Class<?>) rawType;
        Type[] typeArguments = parameterizedType.getActualTypeArguments();
        if (Map.class.isAssignableFrom(rawClass)) {
            return typeArguments.length == 2
                    && (containsNarrowableType(typeArguments[0]) || containsNarrowableType(typeArguments[1]));
        }
        if (Collection.class.isAssignableFrom(rawClass)) {
            return typeArguments.length == 1 && containsNarrowableType(typeArguments[0]);
        }
        return false;
    }

    private static boolean containsNarrowableType(Type type) {
        if (type instanceof ParameterizedType) {
            for (Type typeArgument : ((ParameterizedType) type).getActualTypeArguments()) {
                if (containsNarrowableType(typeArgument)) {
                    return true;
                }
            }
            return false;
        }
        return type == Byte.class || type == Short.class || type == Float.class;
    }

    /**
     * Recursively narrows widened numeric elements (Integer/Double) to the element types declared by
     * {@code type}. Collections and maps are narrowed in place so that the concrete classes produced
     * by hessian deserialization are preserved.
     */
    private static Object narrowByType(Object value, Type type) {
        if (value == null) {
            return null;
        }
        if (type instanceof ParameterizedType) {
            return narrowContainer(value, (ParameterizedType) type);
        }
        if (type instanceof Class) {
            return narrowScalar(value, (Class<?>) type);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Object narrowContainer(Object value, ParameterizedType type) {
        Type rawType = type.getRawType();
        if (!(rawType instanceof Class)) {
            return value;
        }
        Class<?> rawClass = (Class<?>) rawType;
        Type[] typeArguments = type.getActualTypeArguments();
        if (Collection.class.isAssignableFrom(rawClass) && typeArguments.length == 1) {
            if (value instanceof List) {
                return narrowList((List<Object>) value, typeArguments[0]);
            }
            if (value instanceof Collection) {
                return narrowCollection((Collection<Object>) value, typeArguments[0]);
            }
        } else if (Map.class.isAssignableFrom(rawClass) && typeArguments.length == 2 && value instanceof Map) {
            return narrowMap((Map<Object, Object>) value, typeArguments[0], typeArguments[1]);
        }
        return value;
    }

    private static Object narrowScalar(Object value, Class<?> declaredClass) {
        if (declaredClass.isInstance(value)) {
            return value;
        }
        if (declaredClass == Byte.class && value instanceof Number) {
            return ((Number) value).byteValue();
        }
        if (declaredClass == Short.class && value instanceof Number) {
            return ((Number) value).shortValue();
        }
        if (declaredClass == Float.class && value instanceof Number) {
            return ((Number) value).floatValue();
        }
        return value;
    }

    private static Object narrowList(List<Object> list, Type elementType) {
        for (int i = 0; i < list.size(); i++) {
            Object element = list.get(i);
            Object narrowed = narrowByType(element, elementType);
            if (narrowed != element) {
                list.set(i, narrowed);
            }
        }
        return list;
    }

    private static Object narrowCollection(Collection<Object> collection, Type elementType) {
        Object[] elements = collection.toArray();
        boolean narrowed = false;
        for (int i = 0; i < elements.length; i++) {
            Object element = narrowByType(elements[i], elementType);
            if (element != elements[i]) {
                elements[i] = element;
                narrowed = true;
            }
        }
        if (narrowed) {
            collection.clear();
            for (Object element : elements) {
                collection.add(element);
            }
        }
        return collection;
    }

    private static Object narrowMap(Map<Object, Object> map, Type keyType, Type valueType) {
        if (map.isEmpty()) {
            return map;
        }
        List<Object> keys = new ArrayList<>(map.size());
        List<Object> values = new ArrayList<>(map.size());
        boolean narrowed = false;
        for (Map.Entry<Object, Object> entry : map.entrySet()) {
            Object key = narrowByType(entry.getKey(), keyType);
            Object value = narrowByType(entry.getValue(), valueType);
            keys.add(key);
            values.add(value);
            if (key != entry.getKey() || value != entry.getValue()) {
                narrowed = true;
            }
        }
        if (narrowed) {
            map.clear();
            for (int i = 0; i < keys.size(); i++) {
                map.put(keys.get(i), values.get(i));
            }
        }
        return map;
    }

    public InputStream readInputStream() throws IOException {
        return mH2i.readInputStream();
    }

    @Override
    public void cleanup() {
        if (mH2i != null) {
            mH2i.reset();
        }
    }
}
