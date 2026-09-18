/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.muxer;

import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;

/**
 * Converts a buffer containing H.264/H.265 NAL units from the Annex-B format (ISO/IEC 14496-14
 * Annex B, which uses start codes to delineate NAL units) to the avcC format (ISO/IEC 14496-15,
 * which uses length prefixes).
 */
@UnstableApi
public interface AnnexBToAvccConverter {
  /** One-shot diagnostics counter for the default converter (first 3 conversions). */
  java.util.concurrent.atomic.AtomicInteger DIAG_COUNT =
      new java.util.concurrent.atomic.AtomicInteger(0);

  /** Profiling: nanos spent locating NAL units, nanos spent writing AVCC output, call count. */
  java.util.concurrent.atomic.AtomicLong PROF_SCAN_NANOS = new java.util.concurrent.atomic.AtomicLong(0);
  java.util.concurrent.atomic.AtomicLong PROF_WRITE_NANOS = new java.util.concurrent.atomic.AtomicLong(0);
  java.util.concurrent.atomic.AtomicLong PROF_CALLS = new java.util.concurrent.atomic.AtomicLong(0);
  java.util.concurrent.atomic.AtomicLong PROF_BYTES = new java.util.concurrent.atomic.AtomicLong(0);

  /** Default implementation for {@link AnnexBToAvccConverter}. */
  AnnexBToAvccConverter DEFAULT =
      new AnnexBToAvccConverter() {
        @Override
        public ByteBuffer process(ByteBuffer inputBuffer) {
          return process(inputBuffer, ByteBufferAllocator.DEFAULT);
        }

        /** Reusable scratch for samples that are not backed by a byte[]. */
        private final ThreadLocal<byte[]> scratch = new ThreadLocal<>();

        @Override
        public ByteBuffer process(ByteBuffer inputBuffer, ByteBufferAllocator byteBufferAllocator) {
          if (!inputBuffer.hasRemaining()) {
            return inputBuffer;
          }
          int size = inputBuffer.remaining();
          int basePosition = inputBuffer.position();

          // Get the sample bytes as a plain array: zero-copy when the buffer is array-backed,
          // otherwise one bulk copy into a reused scratch buffer. Scanning the array is what turns
          // this conversion from ~400 ms per fragment into a few ms.
          byte[] data;
          int dataOffset;
          if (inputBuffer.hasArray()) {
            data = inputBuffer.array();
            dataOffset = inputBuffer.arrayOffset() + basePosition;
          } else {
            byte[] scratchBuffer = scratch.get();
            if (scratchBuffer == null || scratchBuffer.length < size) {
              scratchBuffer = new byte[size];
              scratch.set(scratchBuffer);
            }
            ByteBuffer copy = inputBuffer.duplicate();
            copy.position(basePosition);
            copy.limit(basePosition + size);
            copy.get(scratchBuffer, 0, size);
            data = scratchBuffer;
            dataOffset = 0;
          }

          long scanStartNs = System.nanoTime();
          int[] nalRanges = AnnexBUtils.findNalUnitRanges(data, dataOffset, size);
          PROF_SCAN_NANOS.addAndGet(System.nanoTime() - scanStartNs);

          // ── Defensive fix: a sample with NO start codes is already
          // length-prefixed (AVCC) or otherwise un-splittable. Converting it
          // would produce an EMPTY buffer and silently destroy the sample —
          // pass it through unchanged instead. (The writer only converts when
          // the format is H264/H265, so an AVCC sample here means a mixed
          // Annex-B/AVCC stream from the encoder.)
          if (nalRanges.length == 0) {
            if (DIAG_COUNT.get() < 3) {
              DIAG_COUNT.incrementAndGet();
              android.util.Log.w(
                  "AnnexBToAvccConverter",
                  "[AVCC-CONV] sample#" + DIAG_COUNT.get()
                      + " no NAL units found (already AVCC?) — passing through unchanged,"
                      + " inputSize=" + size);
            }
            return inputBuffer;
          }

          if (DIAG_COUNT.get() < 3) {
            DIAG_COUNT.incrementAndGet();
            android.util.Log.i(
                "AnnexBToAvccConverter",
                "[AVCC-CONV] sample#" + DIAG_COUNT.get()
                    + " inputSize=" + size
                    + " nalCount=" + (nalRanges.length / 2));
          }

          int totalBytesNeeded = 0;
          for (int i = 0; i < nalRanges.length; i += 2) {
            // 4 bytes to store NAL unit length. Zero-length NAL units
            // (adjacent start codes in a broken encoder stream) are skipped —
            // a 00 00 00 00 length prefix would corrupt the sample for strict
            // extractors.
            int nalLength = nalRanges[i + 1] - nalRanges[i];
            if (nalLength > 0) {
              totalBytesNeeded += 4 + nalLength;
            }
          }

          long writeStartNs = System.nanoTime();
          ByteBuffer outputBuffer = byteBufferAllocator.allocate(totalBytesNeeded);

          for (int i = 0; i < nalRanges.length; i += 2) {
            int nalLength = nalRanges[i + 1] - nalRanges[i];
            if (nalLength <= 0) {
              continue;
            }
            // Rewrite NAL units with NAL unit length in place of start code.
            outputBuffer.putInt(nalLength);
            outputBuffer.put(data, nalRanges[i], nalLength);
          }
          outputBuffer.rewind();
          PROF_WRITE_NANOS.addAndGet(System.nanoTime() - writeStartNs);
          PROF_BYTES.addAndGet(totalBytesNeeded);
          long profCalls = PROF_CALLS.incrementAndGet();
          if (profCalls % 500 == 0) {
            android.util.Log.i("AnnexBToAvccConverter",
                "[AVCC-PROF] calls=" + profCalls
                    + " scanMs=" + (PROF_SCAN_NANOS.get() / 1_000_000L)
                    + " writeMs=" + (PROF_WRITE_NANOS.get() / 1_000_000L)
                    + " bytes=" + PROF_BYTES.get());
          }
          return outputBuffer;
        }
      };

  /**
   * Returns the processed {@link ByteBuffer}.
   *
   * <p>Expects a {@link ByteBuffer} input with a zero offset.
   *
   * @param inputBuffer The buffer to be converted.
   */
  ByteBuffer process(ByteBuffer inputBuffer);

  /**
   * Returns the processed {@link ByteBuffer}.
   *
   * <p>Expects a {@link ByteBuffer} input with a zero offset.
   *
   * @param inputBuffer The buffer to be converted.
   * @param allocator An allocator for {@link ByteBuffer} instances that enables memory reuse.
   */
  default ByteBuffer process(ByteBuffer inputBuffer, ByteBufferAllocator allocator) {
    return process(inputBuffer);
  }
}
