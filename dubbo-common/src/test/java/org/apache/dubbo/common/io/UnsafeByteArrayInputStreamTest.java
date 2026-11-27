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
package org.apache.dubbo.common.io;

import java.io.IOException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class UnsafeByteArrayInputStreamTest {
    @Test
    void testMark() {
        UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream("abc".getBytes(), 1);
        assertThat(stream.markSupported(), is(true));

        stream.mark(2);
        stream.read();
        assertThat(stream.position(), is(2));
        stream.reset();
        assertThat(stream.position(), is(1));
    }

    @Test
    void testRead() throws IOException {
        UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream("abc".getBytes());
        assertThat(stream.read(), is((int) 'a'));
        assertThat(stream.available(), is(2));

        stream.skip(1);
        assertThat(stream.available(), is(1));

        byte[] bytes = new byte[1];
        int read = stream.read(bytes);
        assertThat(read, is(1));
        assertThat(bytes, is("c".getBytes()));

        stream.reset();
        assertThat(stream.position(), is(0));
        assertThat(stream.size(), is(3));

        stream.position(1);
        assertThat(stream.read(), is((int) 'b'));
    }

    @Test
    void testWrongLength() {
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> {
            UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream("abc".getBytes());
            stream.read(new byte[1], 0, 100);
        });
    }

    @Test
    void testWrongOffset() {
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> {
            UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream("abc".getBytes());
            stream.read(new byte[1], -1, 1);
        });
    }

    @Test
    void testReadEmptyByteArray() {
        Assertions.assertThrows(NullPointerException.class, () -> {
            UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream("abc".getBytes());
            stream.read(null, 0, 1);
        });
    }

    @Test
    void testSkipZero() {
        UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream("abc".getBytes());
        long skip = stream.skip(-1);

        assertThat(skip, is(0L));
        assertThat(stream.position(), is(0));
    }

    @Test
    void testZeroLengthReadOnEmptyStream() throws IOException {
        UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream(new byte[0]);
        byte[] dest = new byte[] {1, 2, 3};

        assertThat(stream.read(dest, 1, 0), is(0));
        assertThat(stream.read(new byte[0]), is(0));
        assertThat(stream.position(), is(0));
        assertThat(stream.available(), is(0));
        assertThat(dest, is(new byte[] {1, 2, 3}));
    }

    @Test
    void testZeroLengthReadOnExhaustedStream() throws IOException {
        UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream("abc".getBytes());
        byte[] drain = new byte[3];
        assertThat(stream.read(drain), is(3));
        assertThat(stream.available(), is(0));
        byte[] dest = new byte[] {1, 2, 3, 4};

        assertThat(stream.read(dest, 1, 0), is(0));
        assertThat(stream.read(new byte[0]), is(0));
        assertThat(stream.position(), is(3));
        assertThat(stream.available(), is(0));
        assertThat(dest, is(new byte[] {1, 2, 3, 4}));
    }

    @Test
    void testZeroLengthReadOnBoundedSlice() throws IOException {
        byte[] data = "abcdef".getBytes();
        UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream(data, 1, 2);
        byte[] dest = new byte[] {1, 2, 3};

        assertThat(stream.read(dest, 1, 0), is(0));
        assertThat(stream.position(), is(1));
        assertThat(dest, is(new byte[] {1, 2, 3}));

        byte[] slice = new byte[2];
        assertThat(stream.read(slice), is(2));
        assertThat(new String(slice), is("bc"));
        assertThat(stream.position(), is(3));

        assertThat(stream.read(dest, 0, 0), is(0));
        assertThat(stream.position(), is(3));
        assertThat(dest, is(new byte[] {1, 2, 3}));
    }

    @Test
    void testZeroLengthReadOnClampedBoundedSlice() throws IOException {
        byte[] data = "abcdef".getBytes();
        UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream(data, 4, 100);
        byte[] dest = new byte[] {1, 2, 3};

        assertThat(stream.available(), is(2));
        assertThat(stream.read(dest, 0, 0), is(0));
        assertThat(stream.position(), is(4));
        assertThat(dest, is(new byte[] {1, 2, 3}));

        byte[] slice = new byte[2];
        assertThat(stream.read(slice), is(2));
        assertThat(new String(slice), is("ef"));
        assertThat(stream.position(), is(6));

        assertThat(stream.read(dest, 2, 0), is(0));
        assertThat(stream.position(), is(6));
        assertThat(dest, is(new byte[] {1, 2, 3}));
    }

    @Test
    void testZeroLengthReadAtValidBufferBoundary() {
        UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream(new byte[3]);
        byte[] dest = new byte[3];

        assertThat(stream.read(dest, dest.length, 0), is(0));
        assertThat(stream.position(), is(0));
    }

    @Test
    void testZeroLengthReadStillValidatesArguments() {
        UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream(new byte[0]);
        byte[] dest = new byte[0];

        Assertions.assertThrows(NullPointerException.class, () -> stream.read(null, 0, 0));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> stream.read(dest, -1, 0));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> stream.read(dest, 0, -1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> stream.read(dest, 1, 0));
    }

    @Test
    void testPositiveLengthReadAtEndOfStream() throws IOException {
        UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream("abc".getBytes());
        byte[] drain = new byte[3];
        assertThat(stream.read(drain), is(3));
        byte[] dest = new byte[4];

        assertThat(stream.read(dest, 1, 2), is(-1));
        assertThat(stream.read(dest), is(-1));
        assertThat(stream.read(new byte[2]), is(-1));
        assertThat(stream.read(), is(-1));
        assertThat(stream.position(), is(3));
    }
}
