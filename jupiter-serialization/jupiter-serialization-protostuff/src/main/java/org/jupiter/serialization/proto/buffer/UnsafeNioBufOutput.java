/*
 * Copyright (c) 2015 The Jupiter Project
 *
 * Licensed under the Apache License, version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jupiter.serialization.proto.buffer;

import org.jupiter.common.util.internal.UnsafeDirectBufferUtil;
import org.jupiter.common.util.internal.UnsafeUtf8Util;
import org.jupiter.common.util.internal.UnsafeUtil;
import org.jupiter.serialization.OutputBuf;

import java.io.IOException;

import static io.protostuff.WireFormat.WIRETYPE_LENGTH_DELIMITED;
import static io.protostuff.WireFormat.makeTag;

/**
 * jupiter
 * org.jupiter.serialization.proto.buffer
 *
 * @author jiachun.fjc
 */
class UnsafeNioBufOutput extends NioBufOutput {

    /**
     * Start address of the memory buffer The memory buffer should be non-movable, which normally means that is is allocated
     * off-heap
     */
    private long memoryAddress;

    UnsafeNioBufOutput(OutputBuf outputBuf, int minWritableBytes) {
        super(outputBuf, minWritableBytes);
        updateBufferAddress();
    }

    @Override
    public void writeString(int fieldNumber, CharSequence value, boolean repeated) throws IOException {
        writeVarInt32(makeTag(fieldNumber, WIRETYPE_LENGTH_DELIMITED));

        // UTF-8 byte length of the string is at least its UTF-16 code unit length (value.length()),
        // and at most 3 times of it. We take advantage of this in both branches below.
        int minLength = value.length();
        int maxLength = minLength * UnsafeUtf8Util.MAX_BYTES_PER_CHAR;
        int minLengthVarIntSize = computeVarInt32Size(minLength);
        int maxLengthVarIntSize = computeVarInt32Size(maxLength);
        if (minLengthVarIntSize == maxLengthVarIntSize) {
            int position = nioBuffer.position();

            ensureCapacity(maxLengthVarIntSize + maxLength);

            // Save the current position and increment past the length field. We'll come back
            // and write the length field after the encoding is complete.
            int stringStartPos = position + maxLengthVarIntSize;
            nioBuffer.position(stringStartPos);

            // Encode the string.
            UnsafeUtf8Util.encodeUtf8Direct(value, nioBuffer);

            // Write the length and advance the position.
            int length = nioBuffer.position() - stringStartPos;
            nioBuffer.position(position);
            writeVarInt32(length);
            nioBuffer.position(stringStartPos + length);
        } else {
            // Calculate and write the encoded length.
            int length = UnsafeUtf8Util.encodedLength(value);
            writeVarInt32(length);

            ensureCapacity(length);

            // Write the string and advance the position.
            UnsafeUtf8Util.encodeUtf8Direct(value, nioBuffer);
        }
    }

    // VarInt是一种变长的的数字编码方式, 用字节表示数字, 值越小的数字, 占用的字节越少
    // 通过减少表示数字的字节数, 从而进行数据压缩
    //
    // 每个字节的最高位都是一个标志:
    // 如果是1: 表示后续的字节也是该数字的一部分
    // 如果是0: 表示这是最后一个字节, 剩余7位都是用来表示数字
    @Override
    protected void writeVarInt32(int value) throws IOException {
        byte[] buf = new byte[5];
        int locPtr = 0;
        int position = nioBuffer.position();
        while (true) {
            if ((value & ~0x7F) == 0) {
                // 3. 这是最后一次取出, 最高位是0, 构成一个字节
                buf[locPtr++] = (byte) value;
                ensureCapacity(locPtr);
                // 此时的字节串就是VarInt编码后的字节
                UnsafeDirectBufferUtil.setBytes(address(position), buf, 0, locPtr);
                nioBuffer.position(position + locPtr);
                return;
            } else {
                // 1. 取出字节串末尾7位, 并将最高位设置为1(与0x80按位或), 构成一个字节
                buf[locPtr++] = (byte) ((value & 0x7F) | 0x80);
                // 2. 将字节串整体右移7位, 继续从字节串末尾取7位, 取完为止
                value >>>= 7;
            }
        }
    }

    @Override
    protected void writeVarInt64(long value) throws IOException {
        byte[] buf = new byte[10];
        int locPtr = 0;
        int position = nioBuffer.position();
        while (true) {
            if ((value & ~0x7FL) == 0) {
                buf[locPtr++] = (byte) value;
                ensureCapacity(locPtr);
                UnsafeDirectBufferUtil.setBytes(address(position), buf, 0, locPtr);
                nioBuffer.position(position + locPtr);
                return;
            } else {
                buf[locPtr++] = (byte) (((int) value & 0x7F) | 0x80);
                value >>>= 7;
            }
        }
    }

    @Override
    protected void writeInt32LE(int value) throws IOException {
        ensureCapacity(4);
        int position = nioBuffer.position();
        UnsafeDirectBufferUtil.setIntLE(address(position), value);
        nioBuffer.position(position + 4);
    }

    @Override
    protected void writeInt64LE(long value) throws IOException {
        ensureCapacity(8);
        int position = nioBuffer.position();
        UnsafeDirectBufferUtil.setLongLE(address(position), value);
        nioBuffer.position(position + 8);
    }

    @Override
    protected void writeByte(byte value) throws IOException {
        ensureCapacity(1);
        int position = nioBuffer.position();
        UnsafeDirectBufferUtil.setByte(address(position), value);
        nioBuffer.position(position + 1);
    }

    @Override
    protected void writeByteArray(byte[] value, int offset, int length) throws IOException {
        ensureCapacity(length);
        int position = nioBuffer.position();
        UnsafeDirectBufferUtil.setBytes(address(position), value, offset, length);
        nioBuffer.position(position + length);
    }

    @Override
    protected void ensureCapacity(int required) {
        if (nioBuffer.remaining() < required) {
            int position = nioBuffer.position();

            while (capacity - position < required) {
                capacity = capacity << 1;
                if (capacity < 0) {
                    capacity = Integer.MAX_VALUE;
                }
            }

            nioBuffer = outputBuf.nioByteBuffer(capacity - position);
            capacity = nioBuffer.limit();
            // need to update the direct buffer's memory address
            updateBufferAddress();
        }
    }

    private void updateBufferAddress() {
        memoryAddress = UnsafeUtil.addressOffset(nioBuffer);
    }

    private long address(int position) {
        return memoryAddress + position;
    }

    /**
     * Compute the number of bytes that would be needed to encode a {@code uint32} field.
     */
    private static int computeVarInt32Size(final int value) {
        if ((value & (~0 <<  7)) == 0) {
            return 1;
        }
        if ((value & (~0 << 14)) == 0) {
            return 2;
        }
        if ((value & (~0 << 21)) == 0) {
            return 3;
        }
        if ((value & (~0 << 28)) == 0) {
            return 4;
        }
        return 5;
    }
}
