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
    void testZeroLengthReadOnEmptyStream() {
        UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream(new byte[0]);
        byte[] bytes = new byte[2];

        assertThat(stream.read(bytes, 0, 0), is(0));
        assertThat(stream.position(), is(0));
        assertThat(stream.available(), is(0));
        assertThat(stream.read(), is(-1));
    }

    @Test
    void testZeroLengthReadOnExhaustedStream() {
        UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream("abc".getBytes());
        assertThat(stream.read(new byte[3], 0, 3), is(3));

        assertThat(stream.read(new byte[3], 0, 0), is(0));
        assertThat(stream.position(), is(3));
        assertThat(stream.available(), is(0));
        assertThat(stream.read(new byte[1], 0, 1), is(-1));
    }

    @Test
    void testZeroLengthReadOnBoundedSlice() {
        UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream("abcdef".getBytes(), 2, 2);
        assertThat(stream.read(new byte[2], 0, 2), is(2));
        assertThat(stream.position(), is(4));

        assertThat(stream.read(new byte[2], 1, 0), is(0));
        assertThat(stream.position(), is(4));
        assertThat(stream.available(), is(0));
        assertThat(stream.read(new byte[1], 0, 1), is(-1));
    }

    @Test
    void testZeroLengthReadAtValidOffsets() {
        UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream("abc".getBytes());
        byte[] bytes = new byte[2];

        assertThat(stream.read(bytes, 0, 0), is(0));
        assertThat(stream.read(bytes, 1, 0), is(0));
        assertThat(stream.read(bytes, bytes.length, 0), is(0));
        assertThat(stream.position(), is(0));

        assertThat(stream.read(), is((int) 'a'));
        assertThat(stream.read(bytes, 0, 0), is(0));
        assertThat(stream.position(), is(1));
        assertThat(stream.read(bytes, 0, 1), is(1));
        assertThat(bytes[0], is((byte) 'b'));
    }

    @Test
    void testInheritedZeroLengthRead() throws IOException {
        UnsafeByteArrayInputStream empty = new UnsafeByteArrayInputStream(new byte[0]);
        assertThat(empty.read(new byte[0]), is(0));
        assertThat(empty.position(), is(0));

        UnsafeByteArrayInputStream exhausted = new UnsafeByteArrayInputStream("abc".getBytes());
        assertThat(exhausted.read(new byte[3]), is(3));
        assertThat(exhausted.read(new byte[0]), is(0));
        assertThat(exhausted.position(), is(3));
    }

    @Test
    void testZeroLengthReadLeavesDestinationUnchanged() {
        UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream("abc".getBytes());
        byte[] bytes = new byte[] {1, 2, 3};

        assertThat(stream.read(bytes, 1, 0), is(0));
        assertThat(bytes, is(new byte[] {1, 2, 3}));
        assertThat(stream.position(), is(0));
        assertThat(stream.available(), is(3));

        assertThat(stream.read(new byte[3], 0, 3), is(3));
        assertThat(stream.read(bytes, 0, 0), is(0));
        assertThat(bytes, is(new byte[] {1, 2, 3}));
        assertThat(stream.position(), is(3));
    }

    @Test
    void testZeroLengthReadValidatesArgumentsFirst() {
        UnsafeByteArrayInputStream empty = new UnsafeByteArrayInputStream(new byte[0]);
        Assertions.assertThrows(NullPointerException.class, () -> empty.read(null, 0, 0));
        Assertions.assertThrows(NullPointerException.class, () -> empty.read(null, 0, 1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> empty.read(new byte[1], -1, 0));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> empty.read(new byte[1], 0, -1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> empty.read(new byte[1], 0, 2));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> empty.read(new byte[1], 2, 0));

        UnsafeByteArrayInputStream exhausted = new UnsafeByteArrayInputStream("abc".getBytes());
        assertThat(exhausted.read(new byte[3], 0, 3), is(3));
        Assertions.assertThrows(NullPointerException.class, () -> exhausted.read(null, 0, 0));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> exhausted.read(new byte[1], -1, 0));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> exhausted.read(new byte[1], 0, -1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> exhausted.read(new byte[1], 1, 1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> exhausted.read(new byte[1], 2, 0));
    }

    @Test
    void testPositiveLengthReadAtEofReturnsMinusOne() {
        UnsafeByteArrayInputStream empty = new UnsafeByteArrayInputStream(new byte[0]);
        assertThat(empty.read(new byte[1], 0, 1), is(-1));

        UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream("abc".getBytes());
        assertThat(stream.read(new byte[3], 0, 3), is(3));
        assertThat(stream.read(new byte[1], 0, 1), is(-1));
        assertThat(stream.read(new byte[2], 0, 2), is(-1));
    }
}
