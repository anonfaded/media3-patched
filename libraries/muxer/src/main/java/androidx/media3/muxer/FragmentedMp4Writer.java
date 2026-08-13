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

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.muxer.AnnexBUtils.doesSampleContainAnnexBNalUnits;
import static androidx.media3.muxer.Av1ConfigUtil.createAv1CodecConfigurationRecord;
import static androidx.media3.muxer.Boxes.BOX_HEADER_SIZE;
import static androidx.media3.muxer.Boxes.MFHD_BOX_CONTENT_SIZE;
import static androidx.media3.muxer.Boxes.TFDT_BOX_CONTENT_SIZE;
import static androidx.media3.muxer.Boxes.TFHD_BOX_CONTENT_SIZE;
import static androidx.media3.muxer.Boxes.getTrunBoxContentSize;
import static androidx.media3.muxer.Mp4Muxer.LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS;
import static androidx.media3.muxer.MuxerUtil.UNSIGNED_INT_MAX_VALUE;
import static java.lang.Math.max;
import static java.lang.Math.min;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.Util;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Writes media samples into multiple fragments as per the fragmented MP4 (ISO/IEC 14496-12)
 * standard.
 */
/* package */ final class FragmentedMp4Writer {
  /** Provides a limited set of sample metadata. */
  public static class SampleMetadata {
    public final int durationVu;
    public final int size;
    public final int flags;
    public final int compositionTimeOffsetVu;

    public SampleMetadata(int durationsVu, int size, int flags, int compositionTimeOffsetVu) {
      this.durationVu = durationsVu;
      this.size = size;
      this.flags = flags;
      this.compositionTimeOffsetVu = compositionTimeOffsetVu;
    }
  }

  /** An {@link OutputStream} that tracks the number of bytes written to the stream. */
  private static class PositionTrackingOutputStream extends OutputStream {
    private final OutputStream outputStream;
    private long position;

    public PositionTrackingOutputStream(OutputStream outputStream) {
      this.outputStream = outputStream;
      this.position = 0;
    }

    @Override
    public void write(int b) throws IOException {
      position++;
      outputStream.write(b);
    }

    @Override
    public void write(byte[] b) throws IOException {
      position += b.length;
      outputStream.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      position += len;
      outputStream.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
      outputStream.flush();
    }

    @Override
    public void close() throws IOException {
      outputStream.close();
    }

    /** Returns the number of bytes written to the stream. */
    public long getPosition() {
      return position;
    }
  }

  private final Consumer<ProcessedSegment> segmentConsumer;

  private final MetadataCollector metadataCollector;
  private final AnnexBToAvccConverter annexBToAvccConverter;
  private final long fragmentDurationUs;
  private final boolean sampleCopyEnabled;
  private final @Mp4Muxer.LastSampleDurationBehavior int lastSampleDurationBehavior;
  private final List<Track> tracks;
  private final LinearByteBufferAllocator linearByteBufferAllocator;

  /** Dedicated writer thread that calls segmentConsumer.accept() asynchronously.
   *  This decouples disk/network I/O from the encoder drain thread, eliminating
   *  the periodic ~2-3s stutter caused by fragment finalization + fsync blocking
   *  the encoder pipeline.
   *
   *  The queue holds ProcessedSegment (init header + final combined media
   *  fragments). Box construction happens on the drain thread — the writer
   *  thread only performs the final segment I/O. */
  private final BlockingQueue<Object> segmentQueue;
  private final Thread writerThread;
  private volatile IOException writerError;

  /** Poison pill — signals the writer thread to exit after draining the queue. */
  private static final Object WRITER_POISON = new Object();

  private @MonotonicNonNull Track videoTrack;
  private int currentFragmentSequenceNumber;
  private boolean headerCreated;
  private long minInputPresentationTimeUs;
  private long maxTrackDurationUs;
  private int nextTrackId;
  /** AVC corruption tracing: bounded counter for converted-sample diagnostics. */
  private int sampleConvertDiagCount = 0;

  /**
   * Creates an instance.
   *
   * @param segmentConsumer Consumer to generate actual m4s segments and HLS manifest.
   * @param metadataCollector A {@link MetadataCollector}.
   * @param annexBToAvccConverter The {@link AnnexBToAvccConverter} to be used to convert H.264 and
   *     H.265 NAL units from the Annex-B format (using start codes to delineate NAL units) to the
   *     AVCC format (which uses length prefixes).
   * @param fragmentDurationMs The fragment duration (in milliseconds).
   * @param sampleCopyEnabled Whether sample copying is enabled.
   */
  public FragmentedMp4Writer(
      Consumer<ProcessedSegment> segmentConsumer,
      MetadataCollector metadataCollector,
      AnnexBToAvccConverter annexBToAvccConverter,
      long fragmentDurationMs,
      boolean sampleCopyEnabled) {

    this.segmentConsumer = segmentConsumer;

    this.metadataCollector = metadataCollector;
    this.annexBToAvccConverter = annexBToAvccConverter;
    this.fragmentDurationUs = fragmentDurationMs * 1_000;
    this.sampleCopyEnabled = sampleCopyEnabled;
    this.segmentQueue = new LinkedBlockingQueue<>(4);
    this.writerError = null;
    lastSampleDurationBehavior =
        LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS;
    tracks = new ArrayList<>();
    minInputPresentationTimeUs = Long.MAX_VALUE;
    currentFragmentSequenceNumber = 1;
    linearByteBufferAllocator = new LinearByteBufferAllocator(/* initialCapacity= */ 0);

    // Start the dedicated writer thread that performs the final segment I/O
    // (segmentConsumer.accept) asynchronously. This prevents disk/network I/O
    // from blocking the encoder drain thread.
    //
    // NOTE: moof/mdat BOX BUILDING deliberately stays on the drain thread
    // (inside createFragment). The AnnexB→AVCC converter writes into the shared
    // linearByteBufferAllocator pool; if the pool were read on this writer
    // thread after the drain thread reset/reused it for the next fragment, the
    // sample data would be silently corrupted (misaligned NAL length prefixes —
    // "Invalid NAL unit size" in ffprobe). Only the FINAL combined segment
    // buffer (an independent allocation) crosses the thread boundary.
    writerThread = new Thread(() -> {
      while (true) {
        try {
          Object item = segmentQueue.take();
          if (item == WRITER_POISON) {
            break;
          }
          if (item instanceof ProcessedSegment) {
            segmentConsumer.accept((ProcessedSegment) item);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        } catch (Exception e) {
          writerError = new IOException("Writer thread error: " + e.getMessage(), e);
          break; // STOP — further processing would produce corrupted fragments
        }
      }
      // Drain remaining items so writing threads don't block forever
      segmentQueue.drainTo(new java.util.ArrayList<>());
    }, "FragmentedMp4Writer-SegmentWriter");
    writerThread.start();
  }

  public Track addTrack(int sortKey, Format format) {
    Track track = new Track(nextTrackId++, format, sampleCopyEnabled);
    tracks.add(track);
    if (MimeTypes.isVideo(format.sampleMimeType)) {
      videoTrack = track;
    }
    return track;
  }


  /**
   * Return the duration of all written samples so far of track in timebase units.
   *
   * @param track The track to calculate duration from.
   * @return The sum of duration from all written samples.
   */
  private long getTrackDuration(Track track) {
    List<Integer> durations = Boxes.convertPresentationTimestampsToDurationsVu(
        track.writtenSamples,
        track.videoUnitTimebase(),
        LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS,
        track.endOfStreamTimestampUs
    );
    long duration  = 0;

    for (int i = 0 ; i < durations.size() ; i ++) {
      duration += durations.get(i);
    }

    return duration;
  }
  public void writeSampleData(Track track, ByteBuffer byteBuffer, BufferInfo bufferInfo)
      throws IOException {
    // Fast-fail if the writer thread has already errored.
    if (writerError != null) {
      throw new IOException("Segment writer thread previously failed", writerError);
    }
    if (Objects.equals(track.format.sampleMimeType, MimeTypes.VIDEO_AV1)
        && track.format.initializationData.isEmpty()
        && track.parsedCsd == null) {
      track.parsedCsd = createAv1CodecConfigurationRecord(byteBuffer.duplicate());
    }
    if (!headerCreated) {
      createHeader();
      headerCreated = true;
    }
    if (shouldFlushPendingSamples(track, bufferInfo)) {
      createFragment();
    }
    track.writeSampleData(byteBuffer, bufferInfo);
    BufferInfo firstPendingSample = track.pendingSamplesBufferInfo.peekFirst();
    BufferInfo lastPendingSample = track.pendingSamplesBufferInfo.peekLast();
    // Null check added: queue might be empty in rare race conditions during flushing
    if (firstPendingSample != null && lastPendingSample != null) {
      minInputPresentationTimeUs =
          min(minInputPresentationTimeUs, firstPendingSample.presentationTimeUs);
      maxTrackDurationUs =
          max(
              maxTrackDurationUs,
              lastPendingSample.presentationTimeUs - firstPendingSample.presentationTimeUs);
    }
  }

  /**
   * Builds a complete non-fragmented MP4 moov box from all accumulated
   * sample data, to be appended at the end of the file for Hybrid MP4
   * finalization.  The caller is responsible for appending this moov
   * and overwriting the free placeholder with an mdat header.
   */
  public ByteBuffer buildFinalMoov(
      List<Long> audioOffsets, List<Integer> audioCounts,
      List<Long> videoOffsets, List<Integer> videoCounts) {

    // Populate per-track chunk metadata from the arguments.
    for (int i = 0; i < tracks.size(); i++) {
      Track t = tracks.get(i);
      boolean isAudio = !MimeTypes.isVideo(t.format.sampleMimeType);
      List<Long> offsets = isAudio ? audioOffsets : videoOffsets;
      List<Integer> counts = isAudio ? audioCounts : videoCounts;
      if (offsets != null && counts != null) {
        t.writtenChunkOffsets.clear();
        t.writtenChunkOffsets.addAll(offsets);
        t.writtenChunkSampleCounts.clear();
        t.writtenChunkSampleCounts.addAll(counts);
      }
    }

    return Boxes.moov(tracks, metadataCollector, /* isFragmentedMp4= */ false,
        lastSampleDurationBehavior);
  }

  public void close() throws IOException {
    // Flush any remaining buffered samples as a final fragment, then
    // signal the writer thread to exit and wait for it to finish all
    // pending I/O before this method returns.
    try {
      createFragment();
    } finally {
      // Always shut down the writer thread, even if createFragment() threw.
      try {
        segmentQueue.put(WRITER_POISON);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      try {
        writerThread.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (writerError != null) {
      throw new IOException("Segment writer thread failed", writerError);
    }
  }

  private static ImmutableList<ByteBuffer> createTrafBoxes(
      List<ProcessedTrackInfo> trackInfos) {
    ImmutableList.Builder<ByteBuffer> trafBoxes = new ImmutableList.Builder<>();
    int moofBoxSize = calculateMoofBoxSize(trackInfos);
    int mdatBoxHeaderSize = BOX_HEADER_SIZE;
    // dataOffset denotes the relative position of the first sample of the track from the
    // moofBoxStartPosition.
    int dataOffset = moofBoxSize + mdatBoxHeaderSize;
    for (int i = 0; i < trackInfos.size(); i++) {
      ProcessedTrackInfo currentTrackInfo = trackInfos.get(i);
      trafBoxes.add(
          Boxes.traf(
              Boxes.tfhd(currentTrackInfo.trackId),
              Boxes.tfdt(currentTrackInfo.fragmentPts),
              Boxes.trun(
                  currentTrackInfo.trackFormat,
                  currentTrackInfo.pendingSamplesMetadata,
                  dataOffset,
                  currentTrackInfo.hasBFrame)));
      dataOffset += currentTrackInfo.totalSamplesSize;
    }
    return trafBoxes.build();
  }

  private static int calculateMoofBoxSize(List<ProcessedTrackInfo> trackInfos) {
    /* moof box looks like:
    moof
        mfhd
        traf
           tfhd
           tfdt
           trun
        traf
           tfhd
           tfdt
           trun
     */
    int moofBoxHeaderSize = BOX_HEADER_SIZE;
    int mfhdBoxSize = BOX_HEADER_SIZE + MFHD_BOX_CONTENT_SIZE;
    int trafBoxHeaderSize = BOX_HEADER_SIZE;
    int tfhdBoxSize = BOX_HEADER_SIZE + TFHD_BOX_CONTENT_SIZE;
    int tfdtBoxSize = BOX_HEADER_SIZE + TFDT_BOX_CONTENT_SIZE;
    int trunBoxHeaderFixedSize = BOX_HEADER_SIZE;
    int trafBoxesSize = 0;
    for (int i = 0; i < trackInfos.size(); i++) {
      ProcessedTrackInfo trackInfo = trackInfos.get(i);
      int trunBoxSize =
          trunBoxHeaderFixedSize
              + getTrunBoxContentSize(trackInfo.pendingSamplesMetadata.size(), trackInfo.hasBFrame);
      trafBoxesSize += trafBoxHeaderSize + tfhdBoxSize + tfdtBoxSize + trunBoxSize;
    }

    return moofBoxHeaderSize + mfhdBoxSize + trafBoxesSize;
  }


  private ByteBuffer combine(ByteBuffer a, ByteBuffer b) {
    return (ByteBuffer) ByteBuffer.allocate(a.remaining() + b.remaining())
        .put(a)
        .put(b)
        .flip();
  }
  private void createHeader() throws IOException {

    ByteBuffer ftyp = Boxes.ftyp();
    // Hybrid MP4: insert a 16-byte free box between ftyp and moov.
    // On clean stop this placeholder is overwritten with an mdat header
    // that turns the entire file body into one Media Data box, making
    // the fragmented file appear as a standard MP4.  On crash, the
    // free box is harmless — the file remains a valid fMP4.
    ByteBuffer freePlaceholder = ByteBuffer.allocate(16);
    freePlaceholder.putInt(16);                          // box size = 16
    freePlaceholder.put(new byte[]{'f','r','e','e'});    // type = 'free'
    freePlaceholder.putLong(0);                          // padding; becomes mdat extended size
    freePlaceholder.flip();

    ByteBuffer moov = Boxes.moov(
        tracks, metadataCollector, /* isFragmentedMp4= */ true, lastSampleDurationBehavior);
    ProcessedSegment segment = new ProcessedSegment(true, -1, -1,
        combine(combine(ftyp, freePlaceholder), moov));
    try {
      segmentQueue.put(segment);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while enqueueing init segment", e);
    }
  }

  private boolean shouldFlushPendingSamples(Track track, BufferInfo nextSampleBufferInfo) {
    // If video track is present then fragment will be created based on group of pictures and
    // track's duration so far.
    if (videoTrack != null) {
      // Video samples can be written only when complete group of pictures are present.
      if (track.equals(videoTrack)
          && track.hadKeyframe
          && ((nextSampleBufferInfo.flags & C.BUFFER_FLAG_KEY_FRAME) > 0)) {
        BufferInfo firstPendingSample = track.pendingSamplesBufferInfo.peekFirst();
        BufferInfo lastPendingSample = track.pendingSamplesBufferInfo.peekLast();
        if (firstPendingSample != null && lastPendingSample != null) {
          return lastPendingSample.presentationTimeUs - firstPendingSample.presentationTimeUs
              >= fragmentDurationUs;
        }
      }

      // Allow audio to flush independently if its duration >= fragmentDurationUs
      // This prevents audio samples from being dropped when they haven't caught up with video keyframes
      if (!track.equals(videoTrack) && !track.pendingSamplesBufferInfo.isEmpty()) {
        BufferInfo firstPendingSample = track.pendingSamplesBufferInfo.peekFirst();
        BufferInfo lastPendingSample = track.pendingSamplesBufferInfo.peekLast();
        // Safety check: ensure we actually got valid samples before calculating duration
        if (firstPendingSample != null && lastPendingSample != null) {
          return lastPendingSample.presentationTimeUs - firstPendingSample.presentationTimeUs
              >= fragmentDurationUs;
        }
      }
      return false;
    } else {
      return maxTrackDurationUs >= fragmentDurationUs;
    }
  }

  /**
   * Calculate the tracks duration including last frame duration.
   *
   * @param processedTrackInfos - ProcessedTrackInfos for this segment includes the first samples timestamp
   * @param tracks - After tracks been processed tracks should include all samples that will be written in this segment.
   * @return The duration in micro seconds.
   */
  private long getMaxTrackDurationUs(List<ProcessedTrackInfo> processedTrackInfos, List<Track> tracks) {
    long maxDuration = 0;

    for (int i = 0 ; i < processedTrackInfos.size() ; i++) {
      maxDuration = max(maxDuration, ((getTrackDuration(tracks.get(i)) - processedTrackInfos.get(i).fragmentPts) * 1_000_000)/tracks.get(i).videoUnitTimebase());
    }
    return maxDuration;
  }
  private void createFragment() throws IOException {
    /* All work that touches the shared linearByteBufferAllocator pool (AnnexB→AVCC
       conversion in processAllTracks, plus the moof/mdat box building that reads the
       converted buffers) runs HERE on the drain thread. The writer thread only
       receives the final combined segment buffer — an independent allocation — and
       performs the actual I/O (segmentConsumer.accept). This is what makes
       allocator.reset() below safe: no other thread can be reading pool memory. */

    ImmutableList<ProcessedTrackInfo> trackInfos = processAllTracks();
    if (trackInfos.isEmpty()) {
      linearByteBufferAllocator.reset();
      return;
    }

    int fragNum = currentFragmentSequenceNumber;
    currentFragmentSequenceNumber++;
    long fragMaxDurationUs = getMaxTrackDurationUs(trackInfos, tracks);
    maxTrackDurationUs = 0;

    try {
      // Build moof+mdat now, while the pool is exclusively ours. combine() copies
      // everything into a fresh independent buffer before crossing threads.
      ImmutableList<ByteBuffer> trafBoxes = createTrafBoxes(trackInfos);
      ByteBuffer moof = Boxes.moof(Boxes.mfhd(fragNum), trafBoxes);
      ByteBuffer mdat = getMdatBox(trackInfos);
      ProcessedSegment seg = new ProcessedSegment(false, fragNum,
          fragMaxDurationUs / 1_000, combine(moof, mdat));
      segmentQueue.put(seg);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while enqueueing fragment segment", e);
    } finally {
      // Safe: the combined segment buffer is independent of the pool, and the
      // writer thread never reads pool memory.
      linearByteBufferAllocator.reset();
    }
  }

  private ByteBuffer getMdatBox(List<ProcessedTrackInfo> trackInfos) throws IOException {
    long totalNumBytesSamples = 0;
    for (int trackInfoIndex = 0; trackInfoIndex < trackInfos.size(); trackInfoIndex++) {
      ProcessedTrackInfo currentTrackInfo = trackInfos.get(trackInfoIndex);
      for (int sampleIndex = 0;
          sampleIndex < currentTrackInfo.pendingSamplesByteBuffer.size();
          sampleIndex++) {
        totalNumBytesSamples +=
            currentTrackInfo.pendingSamplesByteBuffer.get(sampleIndex).remaining();
      }
    }

    int mdatHeaderSize = 8; // 4 bytes (box size) + 4 bytes (box name)
    ByteBuffer header = ByteBuffer.allocate(mdatHeaderSize);
    long totalMdatSize = mdatHeaderSize + totalNumBytesSamples;

    checkArgument(
        totalMdatSize <= UNSIGNED_INT_MAX_VALUE,
        "Only 32-bit long mdat size supported in the fragmented MP4");
    header.putInt((int) totalMdatSize);
    header.put(Util.getUtf8Bytes("mdat"));
    header.flip();

    int outputBufferSize = header.remaining();
    for (int trackInfoIndex = 0; trackInfoIndex < trackInfos.size(); trackInfoIndex++) {
      ProcessedTrackInfo currentTrackInfo = trackInfos.get(trackInfoIndex);
      for (int sampleIndex = 0;
          sampleIndex < currentTrackInfo.pendingSamplesByteBuffer.size();
          sampleIndex++) {
        outputBufferSize += currentTrackInfo.pendingSamplesByteBuffer.get(sampleIndex).remaining();
      }
    }

    ByteBuffer outputBuffer = ByteBuffer.allocate(outputBufferSize);
    outputBuffer.put(header);
    for (int trackInfoIndex = 0; trackInfoIndex < trackInfos.size(); trackInfoIndex++) {
      ProcessedTrackInfo currentTrackInfo = trackInfos.get(trackInfoIndex);
      for (int sampleIndex = 0;
          sampleIndex < currentTrackInfo.pendingSamplesByteBuffer.size();
          sampleIndex++) {
          outputBuffer.put(currentTrackInfo.pendingSamplesByteBuffer.get(sampleIndex));
      }
    }

    // NOTE: linearByteBufferAllocator.reset() was here but removed — it now
    // runs on the writer thread, while processTrack() (which allocates from it)
    // runs on the drain thread.  Resetting concurrently would be a data race.
    outputBuffer.flip();
    return outputBuffer;
  }

  private ImmutableList<ProcessedTrackInfo> processAllTracks() {
    ImmutableList.Builder<ProcessedTrackInfo> trackInfos = new ImmutableList.Builder<>();
    for (int i = 0; i < tracks.size(); i++) {
      if (!tracks.get(i).pendingSamplesBufferInfo.isEmpty()) {
        trackInfos.add(processTrack(/* trackId= */ i + 1, tracks.get(i)));
      }
    }
    return trackInfos.build();
  }

  private ProcessedTrackInfo processTrack(int trackId, Track track) {
    checkState(track.pendingSamplesByteBuffer.size() == track.pendingSamplesBufferInfo.size());

    ImmutableList.Builder<ByteBuffer> pendingSamplesByteBuffer = new ImmutableList.Builder<>();
    ImmutableList.Builder<BufferInfo> pendingSamplesBufferInfoBuilder =
        new ImmutableList.Builder<>();

    long fragmentStartPts = getTrackDuration(track);
    track.writtenSamples.addAll(track.pendingSamplesBufferInfo);

    if (doesSampleContainAnnexBNalUnits(track.format)) {
      while (!track.pendingSamplesByteBuffer.isEmpty()) {
        ByteBuffer currentSampleByteBuffer = track.pendingSamplesByteBuffer.removeFirst();
        currentSampleByteBuffer =
            annexBToAvccConverter.process(currentSampleByteBuffer, linearByteBufferAllocator);
        // AVC corruption tracing (first 2 converted video samples only):
        // valid AVCC starts with a 4-byte NAL length, e.g. 00 00 00 16 67...
        if (sampleConvertDiagCount < 2 && MimeTypes.isVideo(track.format.sampleMimeType)) {
          sampleConvertDiagCount++;
          StringBuilder hex = new StringBuilder();
          int shown = Math.min(16, currentSampleByteBuffer.remaining());
          for (int i = 0; i < shown; i++) {
            hex.append(String.format("%02X ", currentSampleByteBuffer.get(currentSampleByteBuffer.position() + i)));
          }
          android.util.Log.i(
              "FragmentedMp4Writer",
              "[AVC-DIAG] converted video sample #" + sampleConvertDiagCount
                  + " size=" + currentSampleByteBuffer.remaining()
                  + " head=" + hex.toString().trim());
        }
        pendingSamplesByteBuffer.add(currentSampleByteBuffer);
        BufferInfo currentSampleBufferInfo = track.pendingSamplesBufferInfo.removeFirst();
        currentSampleBufferInfo =
            new BufferInfo(
                currentSampleBufferInfo.presentationTimeUs,
                currentSampleByteBuffer.remaining(),
                currentSampleBufferInfo.flags);
        pendingSamplesBufferInfoBuilder.add(currentSampleBufferInfo);
      }
    } else {
      pendingSamplesByteBuffer.addAll(track.pendingSamplesByteBuffer);
      track.pendingSamplesByteBuffer.clear();
      pendingSamplesBufferInfoBuilder.addAll(track.pendingSamplesBufferInfo);
      track.pendingSamplesBufferInfo.clear();
    }

    boolean hasBFrame = false;
    ImmutableList<BufferInfo> pendingSamplesBufferInfo = pendingSamplesBufferInfoBuilder.build();
    List<Integer> sampleDurations =
        Boxes.convertPresentationTimestampsToDurationsVu(
            pendingSamplesBufferInfo,
            track.videoUnitTimebase(),
            LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS,
            track.endOfStreamTimestampUs);

    List<Integer> sampleCompositionTimeOffsets =
        Boxes.calculateSampleCompositionTimeOffsets(
            pendingSamplesBufferInfo, sampleDurations, track.videoUnitTimebase());
    if (!sampleCompositionTimeOffsets.isEmpty()) {
      hasBFrame = true;
    }

    ImmutableList.Builder<SampleMetadata> pendingSamplesMetadata = new ImmutableList.Builder<>();
    int totalSamplesSize = 0;
    for (int i = 0; i < pendingSamplesBufferInfo.size(); i++) {
      totalSamplesSize += pendingSamplesBufferInfo.get(i).size;
      pendingSamplesMetadata.add(
          new SampleMetadata(
              sampleDurations.get(i),
              pendingSamplesBufferInfo.get(i).size,
              pendingSamplesBufferInfo.get(i).flags,
              hasBFrame ? sampleCompositionTimeOffsets.get(i) : 0));
    }
    return new ProcessedTrackInfo(
        trackId,
        track.format,
        totalSamplesSize,
        hasBFrame,
        pendingSamplesByteBuffer.build(),
        pendingSamplesMetadata.build(),
        fragmentStartPts);
  }

  private static class ProcessedTrackInfo {
    public final int trackId;
    public final Format trackFormat;
    public final int totalSamplesSize;
    public final boolean hasBFrame;
    public final ImmutableList<ByteBuffer> pendingSamplesByteBuffer;
    public final ImmutableList<SampleMetadata> pendingSamplesMetadata;
    public final long fragmentPts;

    public ProcessedTrackInfo(
        int trackId,
        Format trackFormat,
        int totalSamplesSize,
        boolean hasBFrame,
        ImmutableList<ByteBuffer> pendingSamplesByteBuffer,
        ImmutableList<SampleMetadata> pendingSamplesMetadata,
        long fragmentPts
) {
      this.trackId = trackId;
      this.trackFormat = trackFormat;
      this.totalSamplesSize = totalSamplesSize;
      this.hasBFrame = hasBFrame;
      this.pendingSamplesByteBuffer = pendingSamplesByteBuffer;
      this.pendingSamplesMetadata = pendingSamplesMetadata;
      this.fragmentPts = fragmentPts;
    }
  }
}
