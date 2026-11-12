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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Stream utils.
 */
public final class StreamUtils {

    public static final ByteArrayInputStream EMPTY = new ByteArrayInputStream(new byte[0]);

    private StreamUtils() {}

    public static InputStream limitedInputStream(final InputStream is, final int limit) throws IOException {
        return new InputStream() {
            private int mPosition = 0;
            private int mMark = 0;
            private final int mLimit = limit;
            private boolean mEof = false;

            @Override
            public int read() throws IOException {
                if (mEof || mPosition >= mLimit) {
                    return -1;
                }

                int b = is.read();
                if (b == -1) {
                    mEof = true;
                    return -1;
                }

                mPosition++;
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (b == null) {
                    throw new NullPointerException();
                }

                if (off < 0 || len < 0 || len > b.length - off) {
                    throw new IndexOutOfBoundsException();
                }

                if (len == 0) {
                    return 0;
                }

                if (mEof || mPosition >= mLimit) {
                    return -1;
                }

                int read = is.read(b, off, Math.min(len, mLimit - mPosition));
                if (read == -1) {
                    mEof = true;
                    return -1;
                }

                mPosition += read;
                return read;
            }

            @Override
            public long skip(long len) throws IOException {
                if (len <= 0 || mEof || mPosition >= mLimit) {
                    return 0;
                }

                long remaining = mLimit - mPosition;
                long skipped = Math.min(is.skip(Math.min(len, remaining)), remaining);
                if (skipped <= 0) {
                    return 0;
                }

                mPosition += (int) skipped;
                return skipped;
            }

            @Override
            public int available() throws IOException {
                if (mEof || mPosition >= mLimit) {
                    return 0;
                }

                return Math.min(mLimit - mPosition, is.available());
            }

            @Override
            public boolean markSupported() {
                return is.markSupported();
            }

            @Override
            public synchronized void mark(int readlimit) {
                is.mark(readlimit);
                mMark = mPosition;
            }

            @Override
            public synchronized void reset() throws IOException {
                is.reset();
                mPosition = mMark;
                mEof = false;
            }

            @Override
            public void close() throws IOException {
                is.close();
            }
        };
    }

    public static InputStream markSupportedInputStream(final InputStream is, final int markBufferSize) {
        if (is.markSupported()) {
            return is;
        }

        return new InputStream() {
            byte[] mMarkBuffer;

            boolean mInMarked = false;
            boolean mInReset = false;
            boolean mDry = false;
            private int mPosition = 0;
            private int mCount = 0;

            @Override
            public int read() throws IOException {
                if (!mInMarked) {
                    return is.read();
                } else {
                    if (mPosition < mCount) {
                        byte b = mMarkBuffer[mPosition++];
                        return b & 0xFF;
                    }

                    if (!mInReset) {
                        if (mDry) {
                            return -1;
                        }

                        if (null == mMarkBuffer) {
                            mMarkBuffer = new byte[markBufferSize];
                        }
                        if (mPosition >= markBufferSize) {
                            throw new IOException("Mark buffer is full!");
                        }

                        int read = is.read();
                        if (-1 == read) {
                            mDry = true;
                            return -1;
                        }

                        mMarkBuffer[mPosition++] = (byte) read;
                        mCount++;

                        return read;
                    } else {
                        // mark buffer is used, exit mark status!
                        mInMarked = false;
                        mInReset = false;
                        mPosition = 0;
                        mCount = 0;

                        return is.read();
                    }
                }
            }

            /**
             * NOTE: the <code>readlimit</code> argument for this class
             *  has no meaning.
             */
            @Override
            public synchronized void mark(int readlimit) {
                mInMarked = true;
                mInReset = false;

                // mark buffer is not empty
                int count = mCount - mPosition;
                if (count > 0) {
                    System.arraycopy(mMarkBuffer, mPosition, mMarkBuffer, 0, count);
                    mCount = count;
                    mPosition = 0;
                }
            }

            @Override
            public synchronized void reset() throws IOException {
                if (!mInMarked) {
                    throw new IOException("should mark before reset!");
                }

                mInReset = true;
                mPosition = 0;
            }

            @Override
            public boolean markSupported() {
                return true;
            }

            @Override
            public int available() throws IOException {
                int available = is.available();

                if (mInMarked && mInReset) {
                    available += mCount - mPosition;
                }

                return available;
            }

            @Override
            public void close() throws IOException {
                is.close();
            }
        };
    }

    public static InputStream markSupportedInputStream(final InputStream is) {
        return markSupportedInputStream(is, 1024);
    }

    public static void skipUnusedStream(InputStream is) throws IOException {
        // available() only estimates the bytes readable without blocking and skip() may
        // consume fewer bytes than requested, so retry while skip() keeps progressing.
        long available;
        while ((available = is.available()) > 0) {
            if (is.skip(available) <= 0) {
                break;
            }
        }

        // A stream may report zero availability (or stop skipping early) while bytes remain,
        // so drain the remainder through read() until EOF.
        byte[] buffer = new byte[1024];
        int read;
        while ((read = is.read(buffer)) != -1) {
            if (read == 0) {
                // A bulk read that returns 0 makes no progress; a single-byte read either
                // consumes data or reports EOF, so the loop cannot spin forever.
                if (is.read() == -1) {
                    return;
                }
            }
        }
    }

    public static void copy(InputStream in, OutputStream out) throws IOException {
        if (in.getClass() == ByteArrayInputStream.class) {
            copy((ByteArrayInputStream) in, out);
            return;
        }
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
        }
    }

    public static void copy(ByteArrayInputStream in, OutputStream out) throws IOException {
        int len = in.available();
        byte[] buffer = new byte[len];
        in.read(buffer, 0, len);
        out.write(buffer, 0, len);
    }

    public static byte[] readBytes(InputStream in) throws IOException {
        if (in.getClass() == ByteArrayInputStream.class) {
            return readBytes((ByteArrayInputStream) in);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
        }
        return out.toByteArray();
    }

    public static byte[] readBytes(ByteArrayInputStream in) throws IOException {
        int len = in.available();
        byte[] bytes = new byte[len];
        in.read(bytes, 0, len);
        return bytes;
    }

    public static String toString(InputStream in) throws IOException {
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }

    public static String toString(InputStream in, Charset charset) throws IOException {
        return new String(readBytes(in), charset);
    }
}
