/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.websocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CoderResult;

import org.apache.catalina.util.Conversions;
import org.apache.coyote.http11.upgrade.UpgradeProcessor;
import org.apache.tomcat.util.res.StringManager;

/**
 * Represents a complete WebSocket frame with the exception of the payload for
 * non-control frames.
 */
public class WsFrame {

    private static final StringManager sm =
            StringManager.getManager(Constants.Package);


    private final boolean fin;
    private final int rsv;
    private final byte opCode;
    private byte[] mask = new byte[4];
    private long payloadLength;
    private ByteBuffer payload;

    /**
     * Create the new WebSocket frame, reading data from the processor as
     * necessary.
     *
     * @param processor Processor associated with the WebSocket connection on
     *                  which the frame has been sent
     *
     * @throws IOException  If a problem occurs processing the frame. Any
     *                      exception will trigger the closing of the WebSocket
     *                      connection.
     */
    public WsFrame(UpgradeProcessor<?> processor) throws IOException {

        int b = blockingRead(processor);
        fin = (b & 0x80) > 0;
        rsv = (b & 0x70) >>> 4;
        opCode = (byte) (b & 0x0F);

        b = blockingRead(processor);
        // Client data must be masked
        if ((b & 0x80) == 0) {
            throw new IOException(sm.getString("frame.notMasked"));
        }

        payloadLength = b & 0x7F;
        if (payloadLength == 126) {
            byte[] extended = new byte[2];
            blockingRead(processor, extended);
            payloadLength = Conversions.byteArrayToLong(extended);
        } else if (payloadLength == 127) {
            byte[] extended = new byte[8];
            blockingRead(processor, extended);
            payloadLength = Conversions.byteArrayToLong(extended);
        }

        if (isControl()) {
            if (payloadLength > 125) {
                throw new IOException();
            }
            if (!fin) {
                throw new IOException();
            }
        }

        blockingRead(processor, mask);

        if (isControl()) {
            // Note: Payload limited to <= 125 bytes by test above
            payload = ByteBuffer.allocate((int) payloadLength);
            blockingRead(processor, payload);

            if (opCode == Constants.OPCODE_CLOSE && payloadLength > 2) {
                // Check close payload - if present - is valid UTF-8
                CharBuffer cb = CharBuffer.allocate((int) payloadLength);
                Utf8Decoder decoder = new Utf8Decoder();
                payload.position(2);
                CoderResult cr = decoder.decode(payload, cb, true);
                payload.position(0);
                if (cr.isError()) {
                    throw new IOException(sm.getString("frame.invalidUtf8"));
                }
            }
        } else {
            payload = null;
        }
    }

    public boolean getFin() {
        return fin;
    }

    public int getRsv() {
        return rsv;
    }

    public byte getOpCode() {
        return opCode;
    }

    public boolean isControl() {
        return (opCode & 0x08) > 0;
    }

    public byte[] getMask() {
        return mask;
    }

    public long getPayLoadLength() {
        return payloadLength;
    }

    public ByteBuffer getPayLoad() {
        return payload;
    }


    /*
     * Blocks until a aingle byte has been read
     */
    private int blockingRead(UpgradeProcessor<?> processor)
            throws IOException {
        int result = processor.read();
        if (result == -1) {
            throw new IOException(sm.getString("frame.eos"));
        }
        return result;
    }


    /*
     * Blocks until the byte array has been filled.
     */
    private void blockingRead(UpgradeProcessor<?> processor, byte[] bytes)
            throws IOException {
        int read = 0;
        int last = 0;
        while (read < bytes.length) {
            last = processor.read(true, bytes, read, bytes.length - read);
            if (last == -1) {
                throw new IOException(sm.getString("frame.eos"));
            }
            read += last;
        }
    }


    /*
     * Intended to read whole payload and blocks until it has. Therefore able to
     * unmask the payload data.
     */
    private void blockingRead(UpgradeProcessor<?> processor, ByteBuffer bb)
            throws IOException {
        int last = 0;
        while (bb.hasRemaining()) {
            last = processor.read();
            if (last == -1) {
                throw new IOException(sm.getString("frame.eos"));
            }
            bb.put((byte) (last ^ mask[bb.position() % 4]));
        }
        bb.flip();
    }
}