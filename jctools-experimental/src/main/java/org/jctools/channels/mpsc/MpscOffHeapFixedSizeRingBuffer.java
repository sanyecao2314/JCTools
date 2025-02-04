/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jctools.channels.mpsc;

import static org.jctools.util.UnsafeAccess.UNSAFE;
import static org.jctools.util.UnsafeDirectByteBuffer.allocateAlignedByteBuffer;

import java.nio.ByteBuffer;

import org.jctools.channels.OffHeapFixedMessageSizeRingBuffer;
import org.jctools.util.JvmInfo;
import org.jctools.util.Pow2;

/**
 * Channel protocol: - Fixed message size - 'null' indicator in message preceding byte (potentially use same
 * for type mapping in future) - Use FF algorithm relying on indicator to support in place detection of next
 * element existence
 */
public class MpscOffHeapFixedSizeRingBuffer extends OffHeapFixedMessageSizeRingBuffer {


    public MpscOffHeapFixedSizeRingBuffer(final int capacity, final int messageSize) {
        this(allocateAlignedByteBuffer(getRequiredBufferSize(capacity, messageSize), JvmInfo.CACHE_LINE_SIZE), Pow2
                .roundToPowerOfTwo(capacity), true, true, true, messageSize);
    }

    /**
     * This is to be used for an IPC queue with the direct buffer used being a memory mapped file.
     *
     * @param buff
     * @param capacity
     */
    protected MpscOffHeapFixedSizeRingBuffer(final ByteBuffer buff, final int capacity,
            final boolean isProducer, final boolean isConsumer, final boolean initialize,
            final int messageSize) {
        super(buff,capacity,isProducer,isConsumer,initialize,messageSize);
    }

    @Override
    protected final long writeAcquire() {
        long producerIndex;
        long offset;
        do {
            producerIndex = lvProducerIndex(); // LoadLoad
            offset = offsetForIndex(producerIndex);
            // if the message is not ready for writing the queue is full
            if (!this.isReadReleased(offset)) {
                // It is possible that due to another producer passing us we are seeing that producer completed message,
                // if that is the case then we must retry.
                if (producerIndex != lvProducerIndex()) {
                    continue;// go around again
                }
                return EOF;
            }
        } while (!casProducerIndex(producerIndex, producerIndex + 1));
        //writeAcquireState(offset);
        // return offset for current producer index
        return offset;
    }

    @Override
    protected final void writeRelease(long offset) {
        writeReleaseState(offset);
    }

    @Override
    protected final long readAcquire() {
        final long currentHead = lpConsumerIndex();
        final long offset = offsetForIndex(currentHead);
        if (isReadReleased(offset)) {
            return EOF;
        }
        soConsumerIndex(currentHead + 1); // StoreStore
        //readAcquireState(offset);
        return offset;
    }

    @Override
    protected final void readRelease(long offset) {
        readReleaseState(offset);
    }

    private boolean casProducerIndex(final long expected, long update) {
        return UNSAFE.compareAndSwapLong(null, producerIndexAddress, expected, update);
    }
}
