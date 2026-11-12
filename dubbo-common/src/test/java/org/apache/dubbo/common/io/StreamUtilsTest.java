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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StreamUtilsTest {

    @Test
    void testMarkSupportedInputStream() throws Exception {
        InputStream is = StreamUtilsTest.class.getResourceAsStream("/StreamUtilsTest.txt");
        assertEquals(10, is.available());

        is = new PushbackInputStream(is);
        assertEquals(10, is.available());
        assertFalse(is.markSupported());

        is = StreamUtils.markSupportedInputStream(is);
        assertEquals(10, is.available());

        is.mark(0);
        assertEquals((int) '0', is.read());
        assertEquals((int) '1', is.read());

        is.reset();
        assertEquals((int) '0', is.read());
        assertEquals((int) '1', is.read());
        assertEquals((int) '2', is.read());

        is.mark(0);
        assertEquals((int) '3', is.read());
        assertEquals((int) '4', is.read());
        assertEquals((int) '5', is.read());

        is.reset();
        assertEquals((int) '3', is.read());
        assertEquals((int) '4', is.read());

        is.mark(0);
        assertEquals((int) '5', is.read());
        assertEquals((int) '6', is.read());

        is.reset();
        assertEquals((int) '5', is.read());
        assertEquals((int) '6', is.read());
        assertEquals((int) '7', is.read());
        assertEquals((int) '8', is.read());
        assertEquals((int) '9', is.read());
        assertEquals(-1, is.read());
        assertEquals(-1, is.read());

        is.mark(0);
        assertEquals(-1, is.read());
        assertEquals(-1, is.read());

        is.reset();
        assertEquals(-1, is.read());
        assertEquals(-1, is.read());

        is.close();
    }

    @Test
    void testLimitedInputStream() throws Exception {
        InputStream is = StreamUtilsTest.class.getResourceAsStream("/StreamUtilsTest.txt");
        assertThat(10, is(is.available()));

        is = StreamUtils.limitedInputStream(is, 2);
        assertThat(2, is(is.available()));
        assertThat(is.markSupported(), is(true));

        is.mark(0);
        assertEquals((int) '0', is.read());
        assertEquals((int) '1', is.read());
        assertEquals(-1, is.read());

        is.reset();
        is.skip(1);
        assertEquals((int) '1', is.read());

        is.reset();
        is.skip(-1);
        assertEquals((int) '0', is.read());

        is.reset();
        byte[] bytes = new byte[2];
        int read = is.read(bytes, 1, 1);
        assertThat(read, is(1));

        is.reset();
        StreamUtils.skipUnusedStream(is);
        assertEquals(-1, is.read());

        is.close();
    }

    @Test
    void testMarkInputSupport() {
        Assertions.assertThrows(IOException.class, () -> {
            InputStream is = StreamUtilsTest.class.getResourceAsStream("/StreamUtilsTest.txt");
            try {
                is = StreamUtils.markSupportedInputStream(new PushbackInputStream(is), 1);

                is.mark(1);
                int read = is.read();
                assertThat(read, is((int) '0'));

                is.skip(1);
                is.read();
            } finally {
                if (is != null) {
                    is.close();
                }
            }
        });
    }

    @Test
    void testSkipForOriginMarkSupportInput() throws IOException {
        InputStream is = StreamUtilsTest.class.getResourceAsStream("/StreamUtilsTest.txt");
        InputStream newIs = StreamUtils.markSupportedInputStream(is, 1);

        assertThat(newIs, is(is));
        is.close();
    }

    @Test
    void testReadEmptyByteArray() {
        Assertions.assertThrows(NullPointerException.class, () -> {
            InputStream is = StreamUtilsTest.class.getResourceAsStream("/StreamUtilsTest.txt");
            try {
                is = StreamUtils.limitedInputStream(is, 2);
                is.read(null, 0, 1);
            } finally {
                if (is != null) {
                    is.close();
                }
            }
        });
    }

    @Test
    void testReadWithWrongOffset() {
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> {
            InputStream is = StreamUtilsTest.class.getResourceAsStream("/StreamUtilsTest.txt");
            try {
                is = StreamUtils.limitedInputStream(is, 2);
                is.read(new byte[1], -1, 1);
            } finally {
                if (is != null) {
                    is.close();
                }
            }
        });
    }

    @Test
    void testSkipUnusedStreamDrainsStreamWhenAvailableIsZero() throws Exception {
        InputStream is = new ZeroAvailableInputStream("0123456789");
        StreamUtils.skipUnusedStream(is);
        assertEquals(-1, is.read());
    }

    @Test
    void testSkipUnusedStreamRetriesPartialSkip() throws Exception {
        InputStream is = new InputStream() {
            private int remaining = 5;

            @Override
            public int available() {
                return remaining;
            }

            @Override
            public int read() {
                if (remaining == 0) {
                    return -1;
                }
                remaining--;
                return 'x';
            }

            @Override
            public long skip(long n) {
                if (remaining == 0) {
                    return 0;
                }
                remaining--;
                return 1;
            }
        };

        StreamUtils.skipUnusedStream(is);

        assertEquals(-1, is.read());
    }

    @Test
    void testSkipUnusedStreamOnStreamAlreadyAtEof() throws Exception {
        InputStream is = new ByteArrayInputStream(new byte[0]);
        StreamUtils.skipUnusedStream(is);
        assertEquals(-1, is.read());
    }

    @Test
    void testSkipUnusedStreamTerminatesWhenSkipCannotProgress() {
        InputStream is = new InputStream() {
            @Override
            public int available() {
                return 1;
            }

            @Override
            public int read() {
                return -1;
            }

            @Override
            public long skip(long n) {
                return 0;
            }
        };

        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> StreamUtils.skipUnusedStream(is));
    }

    @Test
    void testLimitedInputStreamExposesDataWhenAvailableIsZero() throws Exception {
        InputStream is = StreamUtils.limitedInputStream(new ZeroAvailableInputStream("0123456789"), 4);

        assertEquals((int) '0', is.read());
        assertEquals((int) '1', is.read());

        byte[] bytes = new byte[4];
        assertEquals(2, is.read(bytes));
        assertEquals((int) '2', bytes[0]);
        assertEquals((int) '3', bytes[1]);
        assertEquals(-1, is.read(bytes));
        assertEquals(-1, is.read());
    }

    @Test
    void testLimitedInputStreamUsesCallerLimitWhenAvailableIsSmaller() throws Exception {
        InputStream is = StreamUtils.limitedInputStream(new PartialAvailableInputStream("0123456789", 1), 4);

        assertEquals(1, is.available());

        byte[] bytes = new byte[10];
        assertEquals(4, is.read(bytes));
        assertEquals("0123", new String(bytes, 0, 4, StandardCharsets.UTF_8));
        assertEquals(-1, is.read());
        assertEquals(0, is.available());
    }

    @Test
    void testLimitedInputStreamHonorsCallerLimit() throws Exception {
        InputStream source = new ByteArrayInputStream("0123456789".getBytes(StandardCharsets.UTF_8));
        InputStream is = StreamUtils.limitedInputStream(source, 3);

        byte[] bytes = new byte[8];
        int offset = 0;
        int read;
        while (offset < bytes.length && (read = is.read(bytes, offset, bytes.length - offset)) != -1) {
            offset += read;
        }

        assertEquals(3, offset);
        assertEquals("012", new String(bytes, 0, offset, StandardCharsets.UTF_8));
        assertEquals((int) '3', source.read());
    }

    @Test
    void testLimitedInputStreamReturnsNumberOfBytesActuallyRead() throws Exception {
        InputStream is = StreamUtils.limitedInputStream(new ShortReadInputStream("01234"), 5);

        byte[] bytes = new byte[8];
        assertEquals(1, is.read(bytes, 0, 5));

        int offset = 1;
        int read;
        while (offset < 5 && (read = is.read(bytes, offset, 5 - offset)) != -1) {
            offset += read;
        }

        assertEquals(5, offset);
        assertEquals("01234", new String(bytes, 0, offset, StandardCharsets.UTF_8));
        assertEquals(-1, is.read());
    }

    @Test
    void testLimitedInputStreamReturnsEofWhenSourceEndsBeforeLimit() throws Exception {
        InputStream is =
                StreamUtils.limitedInputStream(new ByteArrayInputStream("01".getBytes(StandardCharsets.UTF_8)), 10);

        byte[] bytes = new byte[10];
        assertEquals(2, is.read(bytes));
        assertEquals(-1, is.read(bytes));
        assertEquals(-1, is.read());
        assertEquals(0, is.available());
    }

    @Test
    void testLimitedInputStreamSkipReportsBytesActuallySkipped() throws Exception {
        InputStream is = StreamUtils.limitedInputStream(new PartialSkipInputStream(10), 3);

        assertEquals(1, is.skip(3));

        long total = 1;
        long skipped;
        while ((skipped = is.skip(10)) > 0) {
            total += skipped;
        }

        assertEquals(3, total);
        assertEquals(-1, is.read());
        assertEquals(0, is.available());
    }

    /**
     * A stream whose real contents are invisible to {@code available()} and whose
     * {@code skip} cannot make progress, so only {@code read} reveals the bytes.
     */
    private static final class ZeroAvailableInputStream extends InputStream {
        private final byte[] data;
        private int position;

        private ZeroAvailableInputStream(String content) {
            this.data = content.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int available() {
            return 0;
        }

        @Override
        public int read() {
            return position < data.length ? data[position++] & 0xFF : -1;
        }

        @Override
        public long skip(long n) {
            return 0;
        }
    }

    /**
     * A stream that under-reports its remaining length through {@code available()}.
     */
    private static final class PartialAvailableInputStream extends InputStream {
        private final byte[] data;
        private final int reportedAvailable;
        private int position;

        private PartialAvailableInputStream(String content, int reportedAvailable) {
            this.data = content.getBytes(StandardCharsets.UTF_8);
            this.reportedAvailable = reportedAvailable;
        }

        @Override
        public int available() {
            return reportedAvailable;
        }

        @Override
        public int read() {
            return position < data.length ? data[position++] & 0xFF : -1;
        }
    }

    /**
     * A stream that always returns at most one byte from a bulk {@code read}.
     */
    private static final class ShortReadInputStream extends InputStream {
        private final byte[] data;
        private int position;

        private ShortReadInputStream(String content) {
            this.data = content.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int available() {
            return data.length - position;
        }

        @Override
        public int read() {
            return position < data.length ? data[position++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (position >= data.length) {
                return -1;
            }
            if (len == 0) {
                return 0;
            }
            b[off] = data[position++];
            return 1;
        }
    }

    /**
     * A stream whose {@code skip} advances by one byte at most.
     */
    private static final class PartialSkipInputStream extends InputStream {
        private int remaining;

        private PartialSkipInputStream(int remaining) {
            this.remaining = remaining;
        }

        @Override
        public int available() {
            return remaining;
        }

        @Override
        public int read() {
            if (remaining == 0) {
                return -1;
            }
            remaining--;
            return 'x';
        }

        @Override
        public long skip(long n) {
            if (remaining == 0) {
                return 0;
            }
            remaining--;
            return 1;
        }
    }
}
