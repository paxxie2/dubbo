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
    void testZeroLengthReadAtEndOfStreamReturnsZero() throws IOException {
        byte[] destination = {1, 2, 3};

        UnsafeByteArrayInputStream empty = new UnsafeByteArrayInputStream(new byte[0]);
        assertThat(empty.read(destination, 0, 0), is(0));
        assertThat(empty.read(destination, destination.length, 0), is(0));
        assertThat(empty.read(new byte[0]), is(0));
        assertThat(empty.position(), is(0));
        Assertions.assertArrayEquals(new byte[] {1, 2, 3}, destination);
        assertThat(empty.read(destination, 0, 1), is(-1));

        UnsafeByteArrayInputStream exhausted = new UnsafeByteArrayInputStream(new byte[] {10});
        assertThat(exhausted.read(), is(10));
        assertThat(exhausted.read(destination, 0, 0), is(0));
        assertThat(exhausted.read(destination, destination.length, 0), is(0));
        assertThat(exhausted.position(), is(1));
        assertThat(exhausted.read(destination, 0, 1), is(-1));

        UnsafeByteArrayInputStream slice = new UnsafeByteArrayInputStream(new byte[] {10, 20}, 1, 1);
        assertThat(slice.read(), is(20));
        assertThat(slice.read(destination, 0, 0), is(0));
        assertThat(slice.position(), is(2));
        assertThat(slice.read(destination, 0, 1), is(-1));
    }

    @Test
    void testZeroLengthReadKeepsStreamState() {
        UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream("abc".getBytes());
        byte[] destination = {1, 2, 3};

        stream.mark(1);
        assertThat(stream.read(destination, 1, 0), is(0));
        assertThat(stream.position(), is(0));
        assertThat(stream.available(), is(3));
        Assertions.assertArrayEquals(new byte[] {1, 2, 3}, destination);
        assertThat(stream.read(), is((int) 'a'));
        stream.reset();
        assertThat(stream.position(), is(0));
    }

    @Test
    void testZeroLengthReadStillValidatesArguments() {
        UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream(new byte[0]);
        Assertions.assertThrows(NullPointerException.class, () -> stream.read(null, 0, 0));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> stream.read(new byte[1], -1, 0));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> stream.read(new byte[1], 2, 0));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> stream.read(new byte[1], 0, -1));
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
}
