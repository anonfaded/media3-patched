/*
 * Copyright 2022 The Android Open Source Project
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

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.muxer.Boxes.getDolbyVisionProfileAndLevel;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** NAL unit utilities for start codes and emulation prevention. */
/* package */ final class AnnexBUtils {
  private static final int THREE_BYTE_NAL_START_CODE_SIZE = 3;

  private AnnexBUtils() {}

  /**
   * Splits a {@link ByteBuffer} into individual NAL units (0x000001 or 0x00000001 start code).
   *
   * <p>An {@link IllegalStateException} is thrown if the NAL units are invalid. The NAL units are
   * identified as per ITU-T H264 spec:Annex B.2.
   *
   * <p>The input buffer must have position set to 0 and the position remains unchanged after
   * calling this method.
   */
  public static ImmutableList<ByteBuffer> findNalUnits(ByteBuffer input) {
    if (input.remaining() == 0) {
      return ImmutableList.of();
    }
    input = input.asReadOnlyBuffer();
    input.order(ByteOrder.BIG_ENDIAN);

    // The algorithm always searches for 0x000001 start code but it will work for 0x00000001 start
    // code as well because the first 0 will be considered as a leading 0 and will be skipped.

    int nalStartCodeIndex = skipLeadingZerosAndFindNalStartCodeIndex(input, input.position());

    int nalStartIndex = nalStartCodeIndex + THREE_BYTE_NAL_START_CODE_SIZE;
    boolean readingNalUnit = true;

    ImmutableList.Builder<ByteBuffer> nalUnits = new ImmutableList.Builder<>();
    int i = nalStartIndex;
    while (i < input.limit()) {
      if (readingNalUnit) {
        int nalEndIndex = findNalEndIndex(input, i);
        nalUnits.add(getBytes(input, nalStartIndex, nalEndIndex - nalStartIndex));
        i = nalEndIndex;
        readingNalUnit = false;
      } else {
        int nextNalStartCodeIndex = skipLeadingZerosAndFindNalStartCodeIndex(input, i);
        if (nextNalStartCodeIndex != input.limit()) {
          nalStartIndex = nextNalStartCodeIndex + THREE_BYTE_NAL_START_CODE_SIZE;
          i = nalStartIndex;
          readingNalUnit = true;
        } else {
          break;
        }
      }
    }

    return nalUnits.build();
  }

  /** Removes Annex-B emulation prevention bytes from a buffer. */
  public static ByteBuffer stripEmulationPrevention(ByteBuffer input) {
    // For simplicity, we allocate the same number of bytes (although the eventual number might be
    // smaller).
    ByteBuffer output = ByteBuffer.allocate(input.limit());
    int zerosSeen = 0;
    for (int i = 0; i < input.limit(); i++) {
      boolean lookingAtEmulationPreventionByte = input.get(i) == 0x03 && zerosSeen >= 2;

      // Only copy bytes if they aren't emulation prevention bytes.
      if (!lookingAtEmulationPreventionByte) {
        output.put(input.get(i));
      }

      if (input.get(i) == 0) {
        zerosSeen++;
      } else {
        zerosSeen = 0;
      }
    }

    output.flip();

    return output;
  }

  /**
   * Returns whether the sample of the given MIME type will contain NAL units in Annex-B format
   * (ISO/IEC 14496-10 Annex B, which uses start codes to delineate NAL units).
   */
  public static boolean doesSampleContainAnnexBNalUnits(Format format) {
    String sampleMimeType = format.sampleMimeType;
    checkNotNull(sampleMimeType);
    if (sampleMimeType.equals(MimeTypes.VIDEO_DOLBY_VISION)) {
      // Dolby vision with AV1 profile does not contain Nal units.
      int profile = checkNotNull(getDolbyVisionProfileAndLevel(format)).first;
      // Dolby vision with Profile 10 is equivalent to DolbyVisionProfileDvav110 of framework
      // media codec constants.
      return profile != 10;
    }
    return sampleMimeType.equals(MimeTypes.VIDEO_H264)
        || sampleMimeType.equals(MimeTypes.VIDEO_H265);
  }

  /**
   * Returns the end position (exclusive) of the current NAL unit within the input.
   *
   * <p>A NAL unit is terminated by one of the following sequences:
   *
   * <ul>
   *   <li>0x000000
   *   <li>0x000001
   *   <li>The end of the input data.
   * </ul>
   *
   * @param input The {@link ByteBuffer} containing NAL units.
   * @param currentIndex The starting position for the search.
   * @return The NAL unit end index (exclusive).
   */
  private static int findNalEndIndex(ByteBuffer input, int currentIndex) {
    while (currentIndex <= input.limit() - 4) {
      int fourBytes = input.getInt(currentIndex);
      // Check if the first 3 bytes are 0x000000 or 0x000001.
      if ((fourBytes & 0xFFFFFF00) == 0 || (fourBytes & 0xFFFFFF00) == 0x00000100) {
        return currentIndex;
      }

      // Check if the last 3 bytes are 0x000000 or 0x000001.
      if ((fourBytes & 0x00FFFFFF) == 0 || (fourBytes & 0x00FFFFFF) == 0x00000001) {
        return currentIndex + 1;
      }

      // Check if the last 2 bytes are prefix of 0x000000 or 0x000001.
      if ((fourBytes & 0x0000FFFF) == 0) {
        currentIndex = currentIndex + 2;
      } else if ((fourBytes & 0x000000FF)
          == 0) { // Check if the last byte is prefix of 0x000000 or 0x000001.
        currentIndex = currentIndex + 3;
      } else {
        currentIndex = currentIndex + 4;
      }
    }

    // Handle remaining bytes if any (less than 4).
    // Last 3 bytes could be 0x000000 or 0x000001.
    if (currentIndex == input.limit() - THREE_BYTE_NAL_START_CODE_SIZE) {
      short firstTwoBytes = input.getShort(currentIndex);
      byte lastByte = input.get(currentIndex + 2);
      if (firstTwoBytes == 0 && (lastByte == 0 || lastByte == 1)) {
        return currentIndex;
      }
    }
    return input.limit();
  }

  /**
   * Skips leading zeros and locates the start of the next NAL unit (0x000001).
   *
   * @param input The {@link ByteBuffer} containing NAL units.
   * @param currentIndex The starting position for the search.
   * @return The index of the NAL start code, or the end of the input if NAL start code is not
   *     found.
   */
  private static int skipLeadingZerosAndFindNalStartCodeIndex(ByteBuffer input, int currentIndex) {
    while (currentIndex <= input.limit() - 4) {
      int fourBytes = input.getInt(currentIndex);

      // Check if the first 3 bytes is 0x000001.
      if ((fourBytes & 0xFFFFFF00) == 0x00000100) {
        return currentIndex;
      }

      // Otherwise the first 3 bytes must be 0.
      checkState((fourBytes & 0xFFFFFF00) == 0, "Invalid Nal units");

      // Check if the last byte is 1. It then makes last three bytes 0x000001.
      if ((fourBytes & 0x000000FF) == 1) {
        return currentIndex + 1;
      }

      // Otherwise the last byte must be 0;
      checkState((fourBytes & 0x000000FF) == 0, "Invalid Nal units");

      // Last three zeroes can be a prefix of the NAL start code 0x000001.
      currentIndex = currentIndex + 1;
    }

    // Handle remaining bytes if any (less than 4).
    // Last 3 bytes could be 0x000001.
    if (currentIndex <= input.limit() - THREE_BYTE_NAL_START_CODE_SIZE) {
      short firstTwoBytes = input.getShort(currentIndex);
      checkState(firstTwoBytes == 0, "Invalid NAL units");
      byte lastByte = input.get(currentIndex + 2);
      if (lastByte == 1) {
        return currentIndex;
      }
      checkState(lastByte == 0, "Invalid NAL units");
    } else {
      // Remaining bytes must be 0.
      while (currentIndex < input.limit()) {
        checkState(input.get(currentIndex) == 0, "Invalid NAL units");
        currentIndex++;
      }
    }
    return input.limit();
  }

  /**
   * Byte-array NAL range finder — identical rules to {@link #findNalUnits(ByteBuffer)}, but the
   * scan runs on a plain {@code byte[]} instead of absolute reads on a (direct) {@link ByteBuffer}.
   *
   * <p>The buffer-based scan measured ~35 MB/s on device (per-4-byte {@code getInt(index)} calls),
   * which made Annex-B → AVCC conversion of a 4K60 fragment (~15 MB) take ~400 ms on the encoder
   * drain thread — long enough that the drain could not release MediaCodec output buffers, the
   * encoder stalled, and every fragment boundary showed up as a frame freeze. Array indexing runs
   * at memory bandwidth instead.
   *
   * <p>Returns pairs of {@code (start, end)} absolute indices into {@code data}; an empty array
   * means "no Annex-B start code found" (the caller passes such samples through unchanged).
   */
  public static int[] findNalUnitRanges(byte[] data, int offset, int length) {
    int end = offset + length;
    int firstStartCode = skipLeadingZerosArray(data, offset, end);
    if (firstStartCode < 0) {
      return EMPTY_RANGES;
    }
    int[] ranges = new int[16];
    int count = 0;
    int cursor = firstStartCode + THREE_BYTE_NAL_START_CODE_SIZE;
    while (cursor < end) {
      int nalEnd = findNalEndArray(data, cursor, end);
      if (count + 2 > ranges.length) {
        ranges = java.util.Arrays.copyOf(ranges, ranges.length * 2);
      }
      ranges[count++] = cursor;
      ranges[count++] = nalEnd;
      int nextStartCode = skipLeadingZerosArray(data, nalEnd, end);
      if (nextStartCode < 0) {
        break;
      }
      cursor = nextStartCode + THREE_BYTE_NAL_START_CODE_SIZE;
    }
    return java.util.Arrays.copyOf(ranges, count);
  }

  private static final int[] EMPTY_RANGES = new int[0];

  /**
   * Array port of {@link #skipLeadingZerosAndFindNalStartCodeIndex(ByteBuffer, int)}. Returns the
   * index where a {@code 0x000001} start code begins, or {@code -1} when the remaining bytes are
   * not a valid start-code prefix (caller treats it as "not Annex-B").
   */
  private static int skipLeadingZerosArray(byte[] d, int index, int end) {
    while (index <= end - 4) {
      int b0 = d[index] & 0xFF;
      int b1 = d[index + 1] & 0xFF;
      int b2 = d[index + 2] & 0xFF;
      int b3 = d[index + 3] & 0xFF;
      if (b0 == 0 && b1 == 0 && b2 == 1) {
        return index;
      }
      if (b0 != 0 || b1 != 0 || b2 != 0) {
        return -1;
      }
      if (b3 == 1) {
        return index + 1;
      }
      if (b3 != 0) {
        return -1;
      }
      index++;
    }
    if (index <= end - THREE_BYTE_NAL_START_CODE_SIZE) {
      if ((d[index] & 0xFF) != 0 || (d[index + 1] & 0xFF) != 0) {
        return -1;
      }
      // A start code needs the trailing 0x01; anything else is not Annex-B.
      return (d[index + 2] & 0xFF) == 1 ? index : -1;
    }
    while (index < end) {
      if ((d[index] & 0xFF) != 0) {
        return -1;
      }
      index++;
    }
    return -1;
  }

  /**
   * Array port of {@link #findNalEndIndex(ByteBuffer, int)}: returns the index at which the current
   * NAL unit ends (exclusive), i.e. where the next start code's zero run begins.
   */
  private static int findNalEndArray(byte[] d, int index, int end) {
    while (index <= end - 4) {
      int b0 = d[index] & 0xFF;
      int b1 = d[index + 1] & 0xFF;
      int b2 = d[index + 2] & 0xFF;
      int b3 = d[index + 3] & 0xFF;
      if (b0 == 0 && b1 == 0 && (b2 == 0 || b2 == 1)) {
        return index;
      }
      if (b1 == 0 && b2 == 0 && (b3 == 0 || b3 == 1)) {
        return index + 1;
      }
      if (b2 == 0 && b3 == 0) {
        index += 2;
      } else if (b3 == 0) {
        index += 3;
      } else {
        index += 4;
      }
    }
    if (index == end - THREE_BYTE_NAL_START_CODE_SIZE) {
      if ((d[index] & 0xFF) == 0
          && (d[index + 1] & 0xFF) == 0
          && ((d[index + 2] & 0xFF) == 0 || (d[index + 2] & 0xFF) == 1)) {
        return index;
      }
    }
    return end;
  }

  private static ByteBuffer getBytes(ByteBuffer buf, int offset, int length) {
    ByteBuffer result = buf.duplicate();
    result.position(offset);
    result.limit(offset + length);
    return result.slice();
  }
}
