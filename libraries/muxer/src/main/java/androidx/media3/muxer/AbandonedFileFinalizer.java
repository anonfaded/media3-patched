/*
 * Copyright 2026 The FadCam Project
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

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Self-healing hybrid-MP4 finalization for recordings abandoned by a killed
 * process (aggressive OEM background management — Xiaomi HyperOS etc.).
 *
 * <p>A recording whose process died mid-session is left as a valid fragmented MP4
 * (init segment + moof/mdat fragments) but never gets the hybrid moov appended,
 * so consumer apps can't play it. All state needed for finalization still exists
 * IN THE FILE: the init segment (ftyp+free+moov) is the file's first bytes and
 * every fragment's moof contains its sample tables. This class walks the file,
 * rebuilds the in-memory {@link Track} state, and reuses the exact same
 * {@link Boxes#moov(List, MetadataCollector, boolean, int)} path as the live
 * finalization — producing byte-identical output to a normal clean stop.
 */
/* package */ final class AbandonedFileFinalizer {

  private static final String TAG = "AbandonedFileFinalizer";
  private static final int BOX_HEADER_SIZE = 8;
  private static final int LAST_SAMPLE_DURATION_BEHAVIOR =
      Mp4Muxer.LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS;

  private AbandonedFileFinalizer() {}

  /** Container-level info gathered while walking the file's top-level boxes. */
  private static final class FileInfo {
    byte[] initSegment = new byte[0];
    final List<Long> moofPositions = new ArrayList<>();
  }

  /** Per-track info parsed from the init segment's moov. */
  private static final class TrackInfo {
    int trackId;
    boolean isVideo;
    boolean isAudio;
    boolean isHevc;
    int width;
    int height;
    byte[] csd0;
    byte[] csd1;
    int sampleRate = 48_000;
    int channelCount = 2;
    // Per-fragment sample metadata (indexed like moofPositions).
    final List<Integer> fragmentSampleCounts = new ArrayList<>();
    final List<Long> fragmentDataOffsets = new ArrayList<>();
    final List<Integer> sampleSizes = new ArrayList<>();
    final List<Long> sampleDurationsUs = new ArrayList<>();
  }

  /**
   * Finalizes an abandoned fragmented MP4 in place: appends the hybrid moov and
   * patches the free placeholder into an mdat header.
   *
   * <p>Safety guarantees: the moov is only written if it is non-empty and the
   * free placeholder is verified before patching — a file that cannot be
   * converted cleanly is left untouched (return -1).
   *
   * @param ch An open, positionable channel to the file ("rw").
   * @return 1 if the file was converted, 0 if it was not a fragmented MP4 (already
   *     hybrid/plain — nothing to do), -1 if it is fragmented but conversion failed
   *     (file left untouched).
   */
  public static int finalize(FileChannel ch) {
    return finalize(ch, ch);
  }

  /**
   * Two-channel variant for SAF fds: {@code readChannel} must be readable (e.g.
   * FileInputStream(fd).getChannel()), {@code writeChannel} writable (e.g.
   * FileOutputStream(fd).getChannel()). Both must wrap the SAME file descriptor —
   * the kernel file offset is shared, so explicit positioning keeps reads and
   * writes coherent. A FileOutputStream channel alone throws
   * NonReadableChannelException on read, which is why this split is needed.
   */
  public static int finalize(FileChannel readChannel, FileChannel writeChannel) {
    try {
      FileInfo info = walkTopLevelBoxes(readChannel);
      if (info.moofPositions.isEmpty()) {
        // Not a fragmented MP4 (already hybrid, plain MP4, or not video) — nothing to do.
        return 0;
      }
      if (info.initSegment.length == 0) return -1;

      List<TrackInfo> tracks = parseMoov(info.initSegment);
      if (tracks.isEmpty()) return -1;

      byte[] firstVideoSample = parseMoofs(readChannel, info, tracks);
      if (firstVideoSample == null) return -1;

      List<Track> media3Tracks = buildMedia3Tracks(tracks, firstVideoSample);
      if (media3Tracks.isEmpty()) return -1;
      // Every track must actually carry samples, or Boxes.moov() emits an EMPTY moov
      // which would corrupt the file (moov=0B). Never proceed without them.
      for (Track t : media3Tracks) {
        if (t.writtenSamples.isEmpty() || t.writtenChunkOffsets.isEmpty()) return -1;
      }

      MetadataCollector metadataCollector = new MetadataCollector();
      ByteBuffer moov =
          Boxes.moov(media3Tracks, metadataCollector, /* isFragmentedMp4= */ false,
              LAST_SAMPLE_DURATION_BEHAVIOR);
      if (moov.remaining() == 0) return -1; // NEVER append an empty moov

      long moovPos = writeChannel.size();
      int moovSize = moov.remaining();
      writeChannel.position(moovPos);
      writeChannel.write(moov);

      // Verify the placeholder we are about to overwrite really is the 'free' box
      // (init segment: [ftyp][free][moov]...), OR the damaged-layout equivalent
      // ([ftyp][mdat-header][zeros][moov]...). Patching anything else would corrupt
      // the file, so only proceed when one of these matches.
      int ftypSize = readIntBE(info.initSegment, 0);
      if (ftypSize + 8 > info.initSegment.length) return -1;
      int placeholderType = readIntBE(info.initSegment, ftypSize + 4);
      boolean validFree = placeholderType == 0x66726565; // 'free'
      boolean damagedFree = placeholderType == 0x6D646174 // 'mdat' from old buggy build
          && ftypSize + 16 <= info.initSegment.length
          && readIntBE(info.initSegment, ftypSize + 12) == 0;
      if (!validFree && !damagedFree) return -1;
      long freePos = ftypSize; // init segment starts at file offset 0
      long mdatSize = moovPos - freePos;
      if (mdatSize <= Integer.MAX_VALUE) {
        ByteBuffer hdr = ByteBuffer.allocate(8);
        hdr.putInt((int) mdatSize);
        hdr.putInt(0x6D646174); // 'mdat'
        hdr.flip();
        writeChannel.position(freePos);
        writeChannel.write(hdr);
      } else {
        ByteBuffer hdr = ByteBuffer.allocate(16);
        hdr.putInt(1);
        hdr.putInt(0x6D646174);
        hdr.putLong(mdatSize - 16 + 16);
        hdr.flip();
        writeChannel.position(freePos);
        writeChannel.write(hdr);
      }

      // Patch mvhd.duration (offset = ftypSize + 8 + 24, timescale 10000).
      long lastPtsUs = 0;
      for (TrackInfo t : tracks) {
        long trackLast = 0;
        for (int i = 0; i < t.sampleDurationsUs.size(); i++) {
          trackLast += t.sampleDurationsUs.get(i);
        }
        lastPtsUs = Math.max(lastPtsUs, trackLast);
      }
      int durationVu = (int) (lastPtsUs * 10_000L / 1_000_000L);
      ByteBuffer patch = ByteBuffer.allocate(4);
      patch.putInt(durationVu);
      patch.flip();
      writeChannel.position(ftypSize + 8 + 24);
      writeChannel.write(patch);

      android.util.Log.i(TAG, "Finalized abandoned recording: " + info.moofPositions.size()
          + " fragments, moov=" + moovSize + "B, duration=" + lastPtsUs + "us");
      return 1;
    } catch (Exception e) {
      android.util.Log.w(TAG, "Failed to finalize abandoned recording", e);
      return -1;
    }
  }

  private static FileInfo walkTopLevelBoxes(FileChannel ch) throws Exception {
    FileInfo info = new FileInfo();
    long fileSize = ch.size();
    long pos = 0;
    long firstMoof = -1;
    int ftypSize = -1;
    while (pos + 8 <= fileSize) {
      ByteBuffer hdr = ByteBuffer.allocate(8);
      ch.position(pos);
      readFully(ch, hdr);
      hdr.flip();
      int size = hdr.getInt();
      int type = hdr.getInt();
      if (size < 8 || pos + size > fileSize) break;
      if (type == 0x6D6F6F66) { // 'moof'
        if (firstMoof < 0) firstMoof = pos;
        info.moofPositions.add(pos);
      } else if (type == 0x66747970 && ftypSize < 0) { // 'ftyp'
        ftypSize = size;
      } else if (type == 0x6D646174 && ftypSize >= 0 && pos == ftypSize) {
        // DAMAGED-LAYOUT RECOVERY (previous buggy build): the 16-byte free
        // placeholder right after ftyp was overwritten with an mdat header
        // claiming the whole file, hiding the init moov inside. If the bytes
        // after that header really are a 'moov' box, continue the walk from
        // ftypSize + 16 as if the placeholder were still the free box.
        byte[] probe = readBytes(ch, ftypSize + 8, Math.min(16, (int) (fileSize - ftypSize - 8)));
        if (probe.length >= 12 && readIntBE(probe, 8) == 0x6D6F6F76) { // 'moov'
          pos = ftypSize + 16;
          continue;
        }
      }
      pos += size;
    }
    if (firstMoof > 0) {
      info.initSegment = readBytes(ch, 0, (int) firstMoof);
    }
    return info;
  }

  /** Parses moov → trak* → (tkhd, mdia→hdlr, mdhd, stbl→stsd) for each track. */
  private static List<TrackInfo> parseMoov(byte[] initSegment) {
    List<TrackInfo> tracks = new ArrayList<>();
    try {
    int pos = 0;
    int moovPos = findBox(initSegment, 0, 0x6D6F6F76); // 'moov'
    if (moovPos < 0) return tracks;
    int moovEnd = moovPos + readIntBE(initSegment, moovPos);
    pos = moovPos + 8;
    int nextTrackId = 1;
      while (pos + 8 <= moovEnd) {
        int size = readIntBE(initSegment, pos);
        int type = readIntBE(initSegment, pos + 4);
        if (size < 8 || pos + size > moovEnd) break;
        if (type == 0x7472616B) { // 'trak'
          TrackInfo t = parseTrak(initSegment, pos, size, nextTrackId++);
          if (t != null) tracks.add(t);
        }
        pos += size;
      }
    } catch (Exception e) {
      android.util.Log.w(TAG, "Failed to parse moov", e);
    }
    return tracks;
  }

  private static TrackInfo parseTrak(byte[] b, int trakStart, int trakSize, int trackId) {
    TrackInfo t = new TrackInfo();
    // media3 writes trak boxes in track-registration order and assigns
    // moof/tfhd track_IDs in the same 1-based order — so the trak ORDER is the
    // authoritative id. (Parsing tkhd's track_ID is version-dependent and was
    // the source of the moov=0B corruption.)
    t.trackId = trackId;
    try {
      int pos = trakStart + 8;
      int trakEnd = trakStart + trakSize;
      while (pos + 8 <= trakEnd) {
        int size = readIntBE(b, pos);
        int type = readIntBE(b, pos + 4);
        if (size < 8 || pos + size > trakEnd) break;
        if (type == 0x746B6864) { // 'tkhd' — width/height are the last 8 bytes (v0 and v1)
          int wOffset = pos + size - 8;
          t.width = readIntBE(b, wOffset) >>> 16;
          t.height = readIntBE(b, wOffset + 4) >>> 16;
        } else if (type == 0x6D646961) { // 'mdia'
          parseMdia(b, pos, size, t);
        }
        pos += size;
      }
    } catch (Exception e) {
      android.util.Log.w(TAG, "Failed to parse trak", e);
      return null;
    }
    return t;
  }

  private static void parseMdia(byte[] b, int mdiaStart, int mdiaSize, TrackInfo t) {
    int pos = mdiaStart + 8;
    int mdiaEnd = mdiaStart + mdiaSize;
    while (pos + 8 <= mdiaEnd) {
      int size = readIntBE(b, pos);
      int type = readIntBE(b, pos + 4);
      if (size < 8 || pos + size > mdiaEnd) break;
      if (type == 0x68646C72) { // 'hdlr'
        int handler = readIntBE(b, pos + 16);
        t.isVideo = (handler == 0x76696465); // 'vide'
        t.isAudio = (handler == 0x736F756E); // 'soun'
      } else if (type == 0x6D646864) { // 'mdhd'
        int verFlags = readIntBE(b, pos + 8);
        int version = verFlags >> 24;
        int rateOffset = pos + 12 + (version == 1 ? 20 : 12);
        int timescale = readIntBE(b, rateOffset);
        if (timescale > 0) t.sampleRate = timescale;
      } else if (type == 0x6D696E66) { // 'minf'
        parseStbl(b, pos, size, t);
      }
      pos += size;
    }
  }

  private static void parseStbl(byte[] b, int minfStart, int minfSize, TrackInfo t) {
    int pos = minfStart + 8;
    int minfEnd = minfStart + minfSize;
    while (pos + 8 <= minfEnd) {
      int size = readIntBE(b, pos);
      int type = readIntBE(b, pos + 4);
      if (size < 8 || pos + size > minfEnd) break;
      if (type == 0x7374626C) { // 'stbl'
        // stsd lives INSIDE stbl: minf → stbl → stsd (not directly under minf).
        int p = pos + 8;
        int stblEnd = pos + size;
        while (p + 8 <= stblEnd) {
          int s2 = readIntBE(b, p);
          int t2 = readIntBE(b, p + 4);
          if (s2 < 8 || p + s2 > stblEnd) break;
          if (t2 == 0x73747364) { // 'stsd'
            parseStsd(b, p + 8 + 8, s2 - 16, t); // skip fullbox header + entry count
            return;
          }
          p += s2;
        }
        return;
      }
      pos += size;
    }
  }

  private static void parseStsd(byte[] b, int start, int len, TrackInfo t) {
    int pos = start;
    int end = start + len;
    while (pos + 8 <= end) {
      int size = readIntBE(b, pos);
      int type = readIntBE(b, pos + 4);
      if (size < 8 || pos + size > end) break;
      if (type == 0x61766331) { // 'avc1'
        parseAvcC(b, pos, size, t);
        break;
      } else if (type == 0x68766331) { // 'hvc1'
        t.isHevc = true;
        parseHvcC(b, pos, size, t);
        break;
      } else if (type == 0x6D703461) { // 'mp4a'
        t.channelCount = (b[pos + 24] & 0xFF) << 8 | (b[pos + 25] & 0xFF);
        break;
      }
      pos += size;
    }
  }

  /** Extracts SPS/PPS from the avcC box inside stsd and wraps them as Annex-B csd. */
  private static void parseAvcC(byte[] b, int entryStart, int entrySize, TrackInfo t) {
    try {
      // avc1/hvc1 sample entry: 8 (box hdr) + 6 (reserved) + 2 (data_ref_index)
      // + 16 + 12 + 4 + 4 + 2 + 32 (compressorname) + 2 + 2 = 78 fixed bytes,
      // THEN the child boxes (avcC etc.) start. Scanning from +8 hits reserved
      // zeros (size < 8) and never reaches avcC.
      int entryEnd = entryStart + entrySize;
      int avcCPos = findChildBox(b, entryStart + 86, entryEnd, 0x61766343); // 'avcC'
      if (avcCPos < 0) {
        // Defensive: some builds may use a different entry layout — scan the
        // whole entry for an avcC box.
        avcCPos = findChildBox(b, entryStart + 8, entryEnd, 0x61766343);
      }
      if (avcCPos < 0) return;

      int p = avcCPos + 8;
      int spsCount = b[p + 5] & 0x1F;
      int q = p + 6;
      for (int i = 0; i < spsCount && q + 2 <= entryEnd; i++) {
        int len = ((b[q] & 0xFF) << 8) | (b[q + 1] & 0xFF);
        q += 2;
        if (q + len > entryEnd) return;
        byte[] sps = new byte[len];
        System.arraycopy(b, q, sps, 0, len);
        t.csd0 = new byte[len + 4];
        t.csd0[0] = 0; t.csd0[1] = 0; t.csd0[2] = 0; t.csd0[3] = 1;
        System.arraycopy(sps, 0, t.csd0, 4, len);
        q += len;
      }
      if (q + 1 <= entryEnd) {
        int ppsCount = b[q] & 0xFF;
        q += 1;
        for (int i = 0; i < ppsCount && q + 2 <= entryEnd; i++) {
          int len = ((b[q] & 0xFF) << 8) | (b[q + 1] & 0xFF);
          q += 2;
          if (q + len > entryEnd) return;
          byte[] pps = new byte[len];
          System.arraycopy(b, q, pps, 0, len);
          t.csd1 = new byte[len + 4];
          t.csd1[0] = 0; t.csd1[1] = 0; t.csd1[2] = 0; t.csd1[3] = 1;
          System.arraycopy(pps, 0, t.csd1, 4, len);
          q += len;
        }
      }
    } catch (Exception e) {
      android.util.Log.w(TAG, "Failed to parse avcC", e);
    }
  }

  /**
   * Parses the hvcC box (HEVC) inside the stsd sample entry and packs the
   * VPS/SPS/PPS arrays into the single Annex-B csd-0 that media3's hvcCBox
   * expects (all three NAL units concatenated with start codes).
   */
  private static void parseHvcC(byte[] b, int entryStart, int entrySize, TrackInfo t) {
    try {
      int entryEnd = entryStart + entrySize;
      int hvcCPos = findChildBox(b, entryStart + 86, entryEnd, 0x68766343); // 'hvcC'
      if (hvcCPos < 0) {
        hvcCPos = findChildBox(b, entryStart + 8, entryEnd, 0x68766343);
      }
      if (hvcCPos < 0) return;

      int p = hvcCPos + 8;
      // hvcC: configVersion(1) + profile(1) + compat(4) + constraint(6) +
      // level(1) + reserved(2) + parallelism(1) + chroma(1) + bitdepth(2) +
      // avgFrameRate(2) + constantFrameRate(1) = 22 bytes, then numOfArrays(1).
      int numArrays = b[p + 22] & 0xFF;
      int q = p + 23;
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      for (int i = 0; i < numArrays && q + 3 <= entryEnd; i++) {
        q += 1; // array_completeness + NAL_unit_type
        int numNalus = ((b[q] & 0xFF) << 8) | (b[q + 1] & 0xFF);
        q += 2;
        for (int j = 0; j < numNalus && q + 2 <= entryEnd; j++) {
          int len = ((b[q] & 0xFF) << 8) | (b[q + 1] & 0xFF);
          q += 2;
          if (q + len > entryEnd) return;
          out.write(0); out.write(0); out.write(0); out.write(1); // start code
          out.write(b, q, len);
          q += len;
        }
      }
      if (out.size() > 0) {
        t.csd0 = out.toByteArray();
      }
    } catch (Exception e) {
      android.util.Log.w(TAG, "Failed to parse hvcC", e);
    }
  }

  /** Finds a child box of the given type within [start, end). Returns -1 if absent. */
  private static int findChildBox(byte[] b, int start, int end, int type) {
    int pos = start;
    while (pos + 8 <= end) {
      int size = readIntBE(b, pos);
      int t = readIntBE(b, pos + 4);
      if (size < 8 || pos + size > end) break;
      if (t == type) return pos;
      pos += size;
    }
    return -1;
  }

  /** Walks every moof: tfhd→track id, trun→sample sizes/durations/data offset. */
  private static byte[] parseMoofs(FileChannel readChannel, FileInfo info, List<TrackInfo> tracks)
      throws Exception {
    boolean any = false;
    byte[] firstVideoSample = null;
    for (long moofPos : info.moofPositions) {
      int moofSize = (int) Math.min(Integer.MAX_VALUE, readIntAt(readChannel, moofPos));
      byte[] moof = readBytes(readChannel, moofPos, moofSize);
      int pos = 8;
      int moofEnd = moofSize;
      while (pos + 8 <= moofEnd) {
        int s = readIntBE(moof, pos);
        int t = readIntBE(moof, pos + 4);
        if (s < 8 || pos + s > moofEnd) break;
        if (t == 0x74726166) { // 'traf'
          parseTraf(moof, pos, s, moofPos, tracks);
          any = true;
        }
        pos += s;
      }
      // Capture the first video sample's head (for SPS/PPS extraction) once.
      if (firstVideoSample == null) {
        for (TrackInfo tr : tracks) {
          if (tr.isVideo && !tr.fragmentDataOffsets.isEmpty()) {
            long off = tr.fragmentDataOffsets.get(0);
            int len = (int) Math.min(65_536, readChannel.size() - off);
            if (len > 8) {
              firstVideoSample = readBytes(readChannel, off, len);
            }
            break;
          }
        }
      }
    }
    if (!any) return null;
    return firstVideoSample != null ? firstVideoSample : new byte[0];
  }

  private static void parseTraf(byte[] moof, int trafStart, int trafSize, long moofPos,
      List<TrackInfo> tracks) {
    int trackId = -1;
    int pos = trafStart + 8;
    int trafEnd = trafStart + trafSize;
    while (pos + 8 <= trafEnd) {
      int s = readIntBE(moof, pos);
      int t = readIntBE(moof, pos + 4);
      if (s < 8 || pos + s > trafEnd) break;
      if (t == 0x74666864) { // 'tfhd'
        int verFlags = readIntBE(moof, pos + 8);
        int flags = verFlags & 0xFFFFFF;
        trackId = readIntBE(moof, pos + 12);
        // (default sample duration/size would be here; trun usually carries them)
      } else if (t == 0x7472756E && trackId != -1) { // 'trun'
        int verFlags = readIntBE(moof, pos + 8);
        int flags = verFlags & 0xFFFFFF;
        int sampleCount = readIntBE(moof, pos + 12);
        int q = pos + 16;
        boolean hasDataOffset = (flags & 0x1) != 0;
        int dataOffset = hasDataOffset ? readIntBE(moof, q) : 0;
        if (hasDataOffset) q += 4;
        boolean hasDur = (flags & 0x100) != 0;
        boolean hasSize = (flags & 0x200) != 0;
        boolean hasFlags = (flags & 0x400) != 0;

        TrackInfo track = findTrack(tracks, trackId);
        if (track == null) return;
        track.fragmentSampleCounts.add(sampleCount);
        track.fragmentDataOffsets.add(moofPos + dataOffset);
        for (int i = 0; i < sampleCount; i++) {
          if (hasDur) {
            track.sampleDurationsUs.add(readIntBE(moof, q) * 1_000_000L / track.sampleRate);
            q += 4;
          } else {
            track.sampleDurationsUs.add(33_333L);
          }
          if (hasSize) {
            track.sampleSizes.add(readIntBE(moof, q));
            q += 4;
          } else {
            track.sampleSizes.add(0);
          }
          if (hasFlags) q += 4;
        }
        return;
      }
      pos += s;
    }
  }

  private static TrackInfo findTrack(List<TrackInfo> tracks, int trackId) {
    for (TrackInfo t : tracks) {
      if (t.trackId == trackId) return t;
    }
    return null;
  }

  /** Rebuilds media3 Track objects from the parsed file state. */
  private static List<Track> buildMedia3Tracks(List<TrackInfo> trackInfos,
      byte[] firstVideoSample) {
    List<Track> tracks = new ArrayList<>();
    for (TrackInfo t : trackInfos) {
      // Skip unknown tracks (metadata/text etc.) — only avc1/hvc1 video and
      // mp4a audio can be rebuilt into a playable hybrid moov.
      if (!t.isVideo && !t.isAudio) continue;
      Format.Builder fb = new Format.Builder();
      if (t.isVideo) {
        if (t.isHevc) {
          // HEVC: hvcCBox needs all VPS/SPS/PPS packed into csd-0 (Annex-B).
          fb.setSampleMimeType(MimeTypes.VIDEO_H265);
          fb.setWidth(t.width);
          fb.setHeight(t.height);
          fb.setCodecs("hev1.1.6.L150.B0");
          List<byte[]> init = new ArrayList<>();
          if (t.csd0 != null) init.add(t.csd0);
          if (init.isEmpty() && firstVideoSample != null) {
            byte[] hvc = extractHevcCsdFromBitstream(firstVideoSample);
            if (hvc != null) init.add(hvc);
          }
          if (!init.isEmpty()) fb.setInitializationData(init);
        } else {
          fb.setSampleMimeType(MimeTypes.VIDEO_H264);
          fb.setWidth(t.width);
          fb.setHeight(t.height);
          fb.setCodecs("avc1.42001E");
          List<byte[]> init = new ArrayList<>();
          if (t.csd0 != null) init.add(t.csd0);
          if (t.csd1 != null) init.add(t.csd1);
          if (init.size() != 2) {
            // avcCBox requires BOTH csd-0 and csd-1. If either is missing, discard
            // partial data and rely on the sample-bitstream fallback instead.
            init.clear();
            if (firstVideoSample != null) {
              // mdat holds AVCC samples (length-prefixed), with Annex-B handled
              // defensively. This is independent of the moov's stsd layout.
              byte[][] spsPps = extractAvcSpsPpsFromBitstream(firstVideoSample);
              if (spsPps != null) {
                init.add(spsPps[0]);
                init.add(spsPps[1]);
              }
            }
          }
          if (init.size() == 2) fb.setInitializationData(init);
        }
      } else {
        fb.setSampleMimeType(MimeTypes.AUDIO_AAC);
        fb.setSampleRate(t.sampleRate);
        fb.setChannelCount(t.channelCount);
        fb.setCodecs("mp4a.40.2");
        List<byte[]> init = new ArrayList<>();
        init.add(buildAacConfig(t.sampleRate, t.channelCount));
        fb.setInitializationData(init);
      }
      Track track = new Track(t.trackId, fb.build(), /* sortKey= */ 1, /* sampleCopyEnabled= */ true);
      track.writtenChunkOffsets.addAll(t.fragmentDataOffsets);
      track.writtenChunkSampleCounts.addAll(t.fragmentSampleCounts);
      // Reconstruct PTS from cumulative durations.
      long ptsUs = 0;
      for (int i = 0; i < t.sampleSizes.size(); i++) {
        long dur = i < t.sampleDurationsUs.size() ? t.sampleDurationsUs.get(i) : 33_333L;
        track.writtenSamples.add(new BufferInfo(ptsUs, t.sampleSizes.get(i), 0));
        ptsUs += dur;
      }
      if (!track.writtenSamples.isEmpty()) {
        track.endOfStreamTimestampUs = ptsUs;
      }
      tracks.add(track);
    }
    return tracks;
  }

  /**
   * Scans a sample head for SPS (type 7) and PPS (type 8) NAL units.
   *
   * <p>The mdat contains AVCC samples (4-byte length-prefixed NALs, written by
   * FragmentedMp4Writer's AnnexBToAvccConverter), so AVCC is tried first. Annex-B
   * (start-code delimited) is kept as a defensive fallback for files written by
   * older builds. Returned NALs are wrapped WITH start codes because media3's
   * avcCBox requires Annex-B input.
   */
  private static byte[][] extractAvcSpsPpsFromBitstream(byte[] data) {
    if (data == null || data.length < 8) return null;
    byte[] sps = null;
    byte[] pps = null;

    // Try AVCC first: [4-byte big-endian length][NAL]...
    if (looksLikeAvcc(data)) {
      int pos = 0;
      int end = data.length;
      while (pos + 4 <= end && (sps == null || pps == null)) {
        int len = readIntBE(data, pos);
        if (len <= 0 || pos + 4 + len > end) break;
        int nalType = data[pos + 4] & 0x1F;
        if (nalType == 7 && sps == null) sps = wrapWithStartCode(data, pos + 4, len);
        else if (nalType == 8 && pps == null) pps = wrapWithStartCode(data, pos + 4, len);
        pos += 4 + len;
      }
      if (sps != null && pps != null) return new byte[][] {sps, pps};
      // Fall through to Annex-B scan if AVCC yielded incomplete results.
    }

    // Annex-B: [start code][NAL]...
    int pos = 0;
    int end = data.length;
    while (pos < end - 3 && (sps == null || pps == null)) {
      int startCodeLen;
      if (data[pos] == 0 && data[pos + 1] == 0 && data[pos + 2] == 0 && data[pos + 3] == 1) {
        startCodeLen = 4;
      } else if (data[pos] == 0 && data[pos + 1] == 0 && data[pos + 2] == 1) {
        startCodeLen = 3;
      } else {
        pos++;
        continue;
      }
      int nalStart = pos + startCodeLen;
      int nalEnd = nalStart;
      while (nalEnd < end - 2) {
        if (data[nalEnd] == 0 && data[nalEnd + 1] == 0) {
          if (nalEnd + 2 < end && data[nalEnd + 2] == 1) break;
          if (nalEnd + 3 < end && data[nalEnd + 2] == 0 && data[nalEnd + 3] == 1) break;
        }
        nalEnd++;
      }
      if (nalEnd >= end - 2) nalEnd = end;
      if (nalStart < nalEnd) {
        int nalType = data[nalStart] & 0x1F;
        // Keep the Annex-B start code — media3's avcCBox requires it.
        byte[] nalu = new byte[nalEnd - pos];
        System.arraycopy(data, pos, nalu, 0, nalu.length);
        if (nalType == 7 && sps == null) sps = nalu;
        else if (nalType == 8 && pps == null) pps = nalu;
      }
      pos = nalEnd;
    }
    if (sps == null || pps == null) return null;
    return new byte[][]{sps, pps};
  }

  /**
   * Extracts HEVC codec data (VPS+SPS+PPS) from the first video sample and packs
   * them into the single Annex-B csd-0 that media3's hvcCBox requires.
   *
   * <p>The mdat holds length-prefixed (AVCC-style) samples; the first keyframe
   * carries VPS (type 32), SPS (type 33) and PPS (type 34) NAL units. Annex-B
   * input is handled defensively as well. HEVC NAL type is encoded in bits
   * 1..6 of the first byte (not 0..4 like AVC).
   */
  private static byte[] extractHevcCsdFromBitstream(byte[] data) {
    if (data == null || data.length < 8) return null;
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    boolean foundVps = false;
    boolean foundSps = false;
    boolean foundPps = false;

    // Try AVCC-style first: [4-byte length][NAL]...
    if (looksLikeAvcc(data)) {
      int pos = 0;
      int end = data.length;
      while (pos + 4 <= end && !(foundVps && foundSps && foundPps)) {
        int len = readIntBE(data, pos);
        if (len <= 0 || pos + 4 + len > end) break;
        int nalType = (data[pos + 4] >> 1) & 0x3F; // HEVC type in bits 1..6
        if (nalType == 32 || nalType == 33 || nalType == 34) {
          byte[] nalu = wrapWithStartCode(data, pos + 4, len);
          out.write(nalu, 0, nalu.length);
          if (nalType == 32) foundVps = true;
          else if (nalType == 33) foundSps = true;
          else if (nalType == 34) foundPps = true;
        }
        pos += 4 + len;
      }
      if (foundVps && foundSps && foundPps) return out.toByteArray();
    }

    // Annex-B fallback: [start code][NAL]...
    out.reset();
    foundVps = foundSps = foundPps = false;
    int pos = 0;
    int end = data.length;
    while (pos < end - 3 && !(foundVps && foundSps && foundPps)) {
      int startCodeLen;
      if (data[pos] == 0 && data[pos + 1] == 0 && data[pos + 2] == 0 && data[pos + 3] == 1) {
        startCodeLen = 4;
      } else if (data[pos] == 0 && data[pos + 1] == 0 && data[pos + 2] == 1) {
        startCodeLen = 3;
      } else {
        pos++;
        continue;
      }
      int nalStart = pos + startCodeLen;
      int nalEnd = nalStart;
      while (nalEnd < end - 2) {
        if (data[nalEnd] == 0 && data[nalEnd + 1] == 0) {
          if (nalEnd + 2 < end && data[nalEnd + 2] == 1) break;
          if (nalEnd + 3 < end && data[nalEnd + 2] == 0 && data[nalEnd + 3] == 1) break;
        }
        nalEnd++;
      }
      if (nalEnd >= end - 2) nalEnd = end;
      if (nalStart < nalEnd) {
        int nalType = (data[nalStart] >> 1) & 0x3F; // HEVC type in bits 1..6
        if (nalType == 32 || nalType == 33 || nalType == 34) {
          byte[] nalu = new byte[nalEnd - pos];
          System.arraycopy(data, pos, nalu, 0, nalu.length);
          out.write(nalu, 0, nalu.length);
          if (nalType == 32) foundVps = true;
          else if (nalType == 33) foundSps = true;
          else if (nalType == 34) foundPps = true;
        }
      }
      pos = nalEnd;
    }
    if (foundVps && foundSps && foundPps) return out.toByteArray();
    return null;
  }

  /** True when the data looks like AVCC: a plausible 4-byte NAL length header. */
  private static boolean looksLikeAvcc(byte[] data) {
    if (data.length < 8) return false;
    // Annex-B start codes are 00 00 00 01 / 00 00 01 — those are NOT AVCC lengths.
    if (data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) return false;
    if (data[0] == 0 && data[1] == 0 && data[2] == 1) return false;
    int len = readIntBE(data, 0);
    return len > 0 && len <= data.length - 4;
  }

  /** Wraps a raw NAL payload with a 4-byte Annex-B start code. */
  private static byte[] wrapWithStartCode(byte[] data, int off, int len) {
    byte[] out = new byte[len + 4];
    out[0] = 0;
    out[1] = 0;
    out[2] = 0;
    out[3] = 1;
    System.arraycopy(data, off, out, 4, len);
    return out;
  }

  /** 2-byte AAC AudioSpecificConfig (AAC-LC). */
  private static byte[] buildAacConfig(int sampleRate, int channels) {
    int samplingFrequencyIndex;
    switch (sampleRate) {
      case 96000: samplingFrequencyIndex = 0; break;
      case 88200: samplingFrequencyIndex = 1; break;
      case 64000: samplingFrequencyIndex = 2; break;
      case 44100: samplingFrequencyIndex = 4; break;
      case 32000: samplingFrequencyIndex = 5; break;
      case 24000: samplingFrequencyIndex = 6; break;
      case 22050: samplingFrequencyIndex = 7; break;
      case 16000: samplingFrequencyIndex = 8; break;
      default: samplingFrequencyIndex = 3; break; // 48000
    }
    int channelConfig = Math.min(Math.max(channels, 1), 2);
    int byte1 = (2 << 3) | (samplingFrequencyIndex >> 1);
    int byte2 = ((samplingFrequencyIndex & 0x1) << 7) | (channelConfig << 3);
    return new byte[] {(byte) byte1, (byte) byte2};
  }

  // ---- low-level helpers ----

  private static int findBox(byte[] b, int start, int type) {
    int pos = start;
    while (pos + 8 <= b.length) {
      int size = readIntBE(b, pos);
      int t = readIntBE(b, pos + 4);
      if (size < 8) break;
      if (t == type) return pos;
      pos += size;
    }
    return -1;
  }

  private static long readIntAt(FileChannel ch, long pos) throws Exception {
    ByteBuffer b = ByteBuffer.allocate(4);
    ch.position(pos);
    readFully(ch, b);
    b.flip();
    return b.getInt() & 0xFFFFFFFFL;
  }

  private static byte[] readBytes(FileChannel ch, long pos, int len) throws Exception {
    ByteBuffer b = ByteBuffer.allocate(len);
    ch.position(pos);
    readFully(ch, b);
    b.flip();
    byte[] out = new byte[len];
    b.get(out);
    return out;
  }

  private static void readFully(FileChannel ch, ByteBuffer b) throws Exception {
    while (b.hasRemaining()) {
      if (ch.read(b) < 0) break;
    }
  }

  private static int readIntBE(byte[] b, int off) {
    return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
        | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
  }
}
