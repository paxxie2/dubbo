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
    void testSkipLongMaxValueStopsAtSliceEnd() {
        byte[] data = {10, 20, 30, 40};
        UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream(data, 1, 2);

        assertThat(stream.read(), is(20));
        assertThat(stream.skip(Long.MAX_VALUE), is(1L));
        assertThat(stream.position(), is(3));
        assertThat(stream.available(), is(0));
        assertThat(stream.read(), is(-1));
    }

    @Test
    void testSkipLongMaxValueOnFullStream() {
        byte[] data = {10, 20, 30, 40};
        UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream(data);

        assertThat(stream.skip(Long.MAX_VALUE), is(4L));
        assertThat(stream.position(), is(4));
        assertThat(stream.available(), is(0));
        assertThat(stream.read(), is(-1));
    }

    @Test
    void testSkipAtEndOfStream() {
        UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream(new byte[] {1, 2});

        assertThat(stream.skip(2), is(2L));
        assertThat(stream.skip(Long.MAX_VALUE), is(0L));
        assertThat(stream.position(), is(2));
        assertThat(stream.read(), is(-1));
    }

    @Test
    void testSkipNegativeAndZeroDoNotMove() {
        UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream("abc".getBytes());

        assertThat(stream.skip(-1), is(0L));
        assertThat(stream.skip(0), is(0L));
        assertThat(stream.position(), is(0));
        assertThat(stream.available(), is(3));
        assertThat(stream.read(), is((int) 'a'));
    }

    @Test
    void testSkipOrdinaryWithinSlice() {
        UnsafeByteArrayInputStream stream = new UnsafeByteArrayInputStream("abcd".getBytes(), 1, 2);

        assertThat(stream.skip(1), is(1L));
        assertThat(stream.position(), is(2));
        assertThat(stream.available(), is(1));
        assertThat(stream.read(), is((int) 'c'));
        assertThat(stream.read(), is(-1));
        assertThat(stream.skip(1), is(0L));
    }
}
