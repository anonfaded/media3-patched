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

  /** Default implementation for {@link AnnexBToAvccConverter}. */
  AnnexBToAvccConverter DEFAULT =
      new AnnexBToAvccConverter() {
        @Override
        public ByteBuffer process(ByteBuffer inputBuffer) {
          return process(inputBuffer, ByteBufferAllocator.DEFAULT);
        }

        @Override
        public ByteBuffer process(ByteBuffer inputBuffer, ByteBufferAllocator byteBufferAllocator) {
          if (!inputBuffer.hasRemaining()) {
            return inputBuffer;
          }

          ImmutableList<ByteBuffer> nalUnitList = AnnexBUtils.findNalUnits(inputBuffer);

          // ── Defensive fix: a sample with NO start codes is already
          // length-prefixed (AVCC) or otherwise un-splittable. Converting it
          // would produce an EMPTY buffer and silently destroy the sample —
          // pass it through unchanged instead. (The writer only converts when
          // the format is H264/H265, so an AVCC sample here means a mixed
          // Annex-B/AVCC stream from the encoder.)
          if (nalUnitList.isEmpty()) {
            if (DIAG_COUNT.get() < 3) {
              DIAG_COUNT.incrementAndGet();
              StringBuilder hex = new StringBuilder();
              int shown = Math.min(16, inputBuffer.remaining());
              for (int i = 0; i < shown; i++) {
                hex.append(String.format("%02X ", inputBuffer.get(inputBuffer.position() + i)));
              }
              android.util.Log.w(
                  "AnnexBToAvccConverter",
                  "[AVCC-CONV] sample#" + DIAG_COUNT.get()
                      + " no NAL units found (already AVCC?) — passing through unchanged,"
                      + " inputSize=" + inputBuffer.remaining()
                      + " head=" + hex.toString().trim());
            }
            return inputBuffer;
          }

          // ── AVC diagnostics (first 3 samples per process): observe exactly what
          // the Annex-B→AVCC conversion does with each encoder's stream.
          if (DIAG_COUNT.get() < 3) {
            DIAG_COUNT.incrementAndGet();
            int diagN = DIAG_COUNT.get();
            StringBuilder hex = new StringBuilder();
            int shown = Math.min(16, inputBuffer.remaining());
            for (int i = 0; i < shown; i++) {
              hex.append(String.format("%02X ", inputBuffer.get(inputBuffer.position() + i)));
            }
            android.util.Log.i(
                "AnnexBToAvccConverter",
                "[AVCC-CONV] sample#" + diagN
                    + " inputSize=" + inputBuffer.remaining()
                    + " nalCount=" + nalUnitList.size()
                    + " inputHead=" + hex.toString().trim());
          }

          int totalBytesNeeded = 0;

          for (int i = 0; i < nalUnitList.size(); i++) {
            // 4 bytes to store NAL unit length.
            totalBytesNeeded += 4 + nalUnitList.get(i).remaining();
          }

          ByteBuffer outputBuffer = byteBufferAllocator.allocate(totalBytesNeeded);

          for (int i = 0; i < nalUnitList.size(); i++) {
            ByteBuffer currentNalUnit = nalUnitList.get(i);
            int currentNalUnitLength = currentNalUnit.remaining();

            // Rewrite NAL units with NAL unit length in place of start code.
            outputBuffer.putInt(currentNalUnitLength);
            outputBuffer.put(currentNalUnit);
          }
          outputBuffer.rewind();
          // ── AVC diagnostics: post-conversion head (must be a 4-byte NAL length,
          // e.g. 00 00 00 13 67… — a 00 00 00 01 head means the conversion output
          // is not a valid length-prefixed stream).
          int diagN = DIAG_COUNT.get();
          if (diagN <= 2 && nalUnitList.size() > 0) {
            StringBuilder outHex = new StringBuilder();
            int shown = Math.min(16, outputBuffer.remaining());
            for (int i = 0; i < shown; i++) {
              outHex.append(String.format("%02X ", outputBuffer.get(i)));
            }
            android.util.Log.i(
                "AnnexBToAvccConverter",
                "[AVCC-CONV] output#" + diagN
                    + " size=" + outputBuffer.remaining()
                    + " head=" + outHex.toString().trim());
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
