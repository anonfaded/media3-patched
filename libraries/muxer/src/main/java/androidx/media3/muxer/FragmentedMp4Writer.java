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

  /**
   * Pool of per-fragment linear allocators.
   *
   * <p>Each fragment owns exactly one allocator for its lifetime. Converted (Annex-B → AVCC)
   * sample data is written into that allocator on the drain thread and then read by the writer
   * thread when it builds the moof/mdat boxes. Because no two fragments ever share an allocator,
   * the previous data race (writer thread reading pool memory that the drain thread had already
   * reset and reused for the next fragment, producing misaligned NAL length prefixes) is
   * impossible by construction — while still reusing buffers instead of allocating ~15 MB per
   * fragment.
   */
  private final java.util.ArrayDeque<LinearByteBufferAllocator> fragmentAllocatorPool =
      new java.util.ArrayDeque<>();
  private static final int MAX_POOLED_ALLOCATORS = 6;

  /** Dedicated writer thread that calls segmentConsumer.accept() asynchronously.
   *  This decouples fragment box construction (moof/mdat assembly, multi-MB buffer
   *  allocations) and disk/network I/O from the encoder drain thread. Keeping it off
   *  the drain thread is what prevents the periodic ~2-3 s encoder stutter: the drain
   *  thread is the only thread that releases MediaCodec output buffers, so any time it
   *  spends inside fragment finalization starves the encoder and stalls the camera.
   *
   *  The queue holds either a ProcessedSegment (init header) or a FragmentTask whose
   *  sample buffers are owned by that fragment (per-fragment allocator), so the writer
   *  thread can safely build the boxes. */
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
  private int emptyConvertDiagCount = 0;
  private int fragmentDiagCount = 0;

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

    // Start the dedicated writer thread that performs the final segment I/O
    // (segmentConsumer.accept) asynchronously. This prevents disk/network I/O
    // from blocking the encoder drain thread.
    //
    // NOTE: moof/mdat BOX BUILDING deliberately stays on the drain thread
    // (inside createFragment). The AnnexB→AVCC converter writes into the shared
    // fragment's own allocator; if that memory were shared with a later fragment, the
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
          } else if (item instanceof FragmentTask) {
            buildAndEmitFragment((FragmentTask) item);
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
  /**
   * Maximum fragment duration (µs) across tracks — computed from THIS fragment's samples only.
   *
   * <p>Previously this walked every sample written so far (cumulative {@code writtenSamples}) and
   * allocated boxed duration lists on each call, which made fragment finalization cost grow
   * linearly with recording length. The per-fragment duration is now carried in
   * {@link ProcessedTrackInfo#fragmentDurationUs}.
   */
  private long getMaxTrackDurationUs(List<ProcessedTrackInfo> processedTrackInfos) {
    long maxDuration = 0;
    for (int i = 0; i < processedTrackInfos.size(); i++) {
      maxDuration = max(maxDuration, processedTrackInfos.get(i).fragmentDurationUs);
    }
    return maxDuration;
  }
  private void createFragment() throws IOException {
    /* Phase 1 (drain thread): Annex-B → AVCC conversion + per-sample metadata.
       Everything written here lands in THIS fragment's own allocator, which is handed
       to the writer thread with the task. Phase 2 (writer thread, buildAndEmitFragment):
       traf/moof/mdat box construction, the multi-MB allocations that go with it, and the
       consumer call. Fragment finalization therefore no longer blocks the encoder drain. */
    LinearByteBufferAllocator fragmentAllocator = acquireFragmentAllocator();
    ImmutableList<ProcessedTrackInfo> trackInfos;
    try {
      trackInfos = processAllTracks(fragmentAllocator);
    } catch (RuntimeException e) {
      releaseFragmentAllocator(fragmentAllocator);
      throw e;
    }
    if (trackInfos.isEmpty()) {
      releaseFragmentAllocator(fragmentAllocator);
      return;
    }

    // ── AVC diagnostics (first 3 fragments): per-track sample counts and total
    // sample bytes inside each fragment — confirms fragments actually carry the
    // encoded data (conversion emptying samples would show as tiny totals).
    if (fragmentDiagCount < 3) {
      fragmentDiagCount++;
      StringBuilder perTrack = new StringBuilder();
      for (int i = 0; i < trackInfos.size(); i++) {
        ProcessedTrackInfo ti = trackInfos.get(i);
        perTrack.append("track").append(ti.trackId).append("=")
            .append(ti.pendingSamplesMetadata.size()).append("samples/")
            .append(ti.totalSamplesSize).append("B ");
      }
      android.util.Log.i(
          "FragmentedMp4Writer",
          "[AVCC-CONV] fragment#" + fragmentDiagCount + " " + perTrack.toString().trim());
    }

    int fragNum = currentFragmentSequenceNumber++;
    long fragMaxDurationUs = getMaxTrackDurationUs(trackInfos);
    maxTrackDurationUs = 0;

    {
      StringBuilder prof = new StringBuilder("[FRAG-PROF] drain frag=").append(fragNum);
      for (ProcessedTrackInfo ti : trackInfos) {
        for (Track t : tracks) {
          if (t.id == ti.trackId) {
            prof.append(" t").append(ti.trackId)
                .append("(copy=").append(t.sampleCopyNanos / 1_000_000L)
                .append("ms conv=").append(t.convertNanos / 1_000_000L).append("ms)");
          }
        }
      }
      android.util.Log.i("FragmentedMp4Writer", prof.toString());
      for (Track t : tracks) { t.sampleCopyNanos = 0; t.convertNanos = 0; }
    }

    FragmentTask task = new FragmentTask(fragNum, fragMaxDurationUs, trackInfos, fragmentAllocator);
    try {
      segmentQueue.put(task);
    } catch (InterruptedException e) {
      releaseFragmentAllocator(fragmentAllocator);
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while enqueueing fragment task", e);
    }
  }

  /** Builds the fragment boxes off the drain thread and hands the segment to the consumer. */
  private void buildAndEmitFragment(FragmentTask task) throws IOException {
    try {
      long buildStartNs = System.nanoTime();
      ImmutableList<ByteBuffer> trafBoxes = createTrafBoxes(task.trackInfos);
      ByteBuffer moof = Boxes.moof(Boxes.mfhd(task.fragmentSequenceNumber), trafBoxes);
      ByteBuffer mdat = getMdatBox(task.trackInfos);
      ProcessedSegment seg =
          new ProcessedSegment(
              false,
              task.fragmentSequenceNumber,
              task.fragmentMaxDurationUs / 1_000,
              combine(moof, mdat));
      long buildMs = (System.nanoTime() - buildStartNs) / 1_000_000L;
      StringBuilder sb = new StringBuilder();
      for (ProcessedTrackInfo ti : task.trackInfos) {
        sb.append(" [t").append(ti.trackId).append("=")
            .append(ti.pendingSamplesMetadata.size()).append("]");
      }
      android.util.Log.i(
          "FragmentedMp4Writer",
          "[FRAG-WRITE] frag=" + task.fragmentSequenceNumber + " buildMs=" + buildMs
              + " maxDurMs=" + (task.fragmentMaxDurationUs / 1000) + " tracks:" + sb);
      segmentConsumer.accept(seg);
    } finally {
      // Fragment finished with its allocator — return it for reuse by a later fragment.
      releaseFragmentAllocator(task.allocator);
    }
  }

  private LinearByteBufferAllocator acquireFragmentAllocator() {
    synchronized (fragmentAllocatorPool) {
      LinearByteBufferAllocator pooled = fragmentAllocatorPool.pollFirst();
      if (pooled != null) {
        pooled.reset();
        return pooled;
      }
    }
    return new LinearByteBufferAllocator(/* initialCapacity= */ 0);
  }

  private void releaseFragmentAllocator(LinearByteBufferAllocator allocator) {
    if (allocator == null) {
      return;
    }
    synchronized (fragmentAllocatorPool) {
      if (fragmentAllocatorPool.size() < MAX_POOLED_ALLOCATORS) {
        fragmentAllocatorPool.addLast(allocator);
      }
    }
  }

  /** One fragment's worth of work handed from the drain thread to the writer thread. */
  private static final class FragmentTask {
    public final int fragmentSequenceNumber;
    public final long fragmentMaxDurationUs;
    public final ImmutableList<ProcessedTrackInfo> trackInfos;
    /** Owned by this fragment until the writer thread returns it to the pool. */
    public final LinearByteBufferAllocator allocator;

    public FragmentTask(
        int fragmentSequenceNumber,
        long fragmentMaxDurationUs,
        ImmutableList<ProcessedTrackInfo> trackInfos,
        LinearByteBufferAllocator allocator) {
      this.fragmentSequenceNumber = fragmentSequenceNumber;
      this.fragmentMaxDurationUs = fragmentMaxDurationUs;
      this.trackInfos = trackInfos;
      this.allocator = allocator;
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

    // The converted sample slices belong to THIS fragment's allocator; the writer
    // thread reads them when building the boxes, and the allocator is returned to
    // the pool once the fragment is emitted. No cross-fragment sharing exists.
    outputBuffer.flip();
    return outputBuffer;
  }

  private ImmutableList<ProcessedTrackInfo> processAllTracks(
      LinearByteBufferAllocator fragmentAllocator) {
    ImmutableList.Builder<ProcessedTrackInfo> trackInfos = new ImmutableList.Builder<>();
    for (int i = 0; i < tracks.size(); i++) {
      if (!tracks.get(i).pendingSamplesBufferInfo.isEmpty()) {
        trackInfos.add(processTrack(/* trackId= */ i + 1, tracks.get(i), fragmentAllocator));
      }
    }
    return trackInfos.build();
  }

  private ProcessedTrackInfo processTrack(
      int trackId, Track track, LinearByteBufferAllocator fragmentAllocator) {
    checkState(track.pendingSamplesByteBuffer.size() == track.pendingSamplesBufferInfo.size());

    ImmutableList.Builder<ByteBuffer> pendingSamplesByteBuffer = new ImmutableList.Builder<>();
    ImmutableList.Builder<BufferInfo> pendingSamplesBufferInfoBuilder =
        new ImmutableList.Builder<>();

    // O(1): running counter maintained per track (was a full-history walk per fragment).
    long fragmentStartPts = track.completedDurationVu;
    int trackPreConversionBytes = 0;
    int trackConvertedSamples = 0;
    int trackDroppedSamples = 0;

    
    if (doesSampleContainAnnexBNalUnits(track.format)) {
      long convertStartNs = System.nanoTime();
      while (!track.pendingSamplesByteBuffer.isEmpty()) {
        ByteBuffer currentSampleByteBuffer = track.pendingSamplesByteBuffer.removeFirst();
        trackPreConversionBytes += currentSampleByteBuffer.remaining();
        currentSampleByteBuffer =
            annexBToAvccConverter.process(currentSampleByteBuffer, fragmentAllocator);
        // ── AVC diagnostics: a conversion that yields ZERO bytes means the
        // converter found no NAL units (already-AVCC sample or un-splittable
        // stream) — the sample is silently lost from the file.
        if (emptyConvertDiagCount < 3 && !currentSampleByteBuffer.hasRemaining()) {
          emptyConvertDiagCount++;
          android.util.Log.w(
              "FragmentedMp4Writer",
              "[AVCC-CONV] WARNING: conversion produced EMPTY sample #"
                  + emptyConvertDiagCount + " (track=" + trackId + ") — sample dropped");
        }
        if (!currentSampleByteBuffer.hasRemaining()) {
          // Drop the empty sample instead of writing a zero-size trun entry —
          // a 0-byte sample can trip strict extractors.
          track.pendingSamplesBufferInfo.removeFirst();
          trackDroppedSamples++;
          continue;
        }
        trackConvertedSamples++;
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
      track.convertNanos += System.nanoTime() - convertStartNs;
    } else {
      pendingSamplesByteBuffer.addAll(track.pendingSamplesByteBuffer);
      track.pendingSamplesByteBuffer.clear();
      pendingSamplesBufferInfoBuilder.addAll(track.pendingSamplesBufferInfo);
      track.pendingSamplesBufferInfo.clear();
    }

    boolean hasBFrame = false;
    ImmutableList<BufferInfo> pendingSamplesBufferInfo = pendingSamplesBufferInfoBuilder.build();
    // Record samples with their FINAL sizes: Annex-B to AVCC conversion can
    // shrink samples (e.g. QCOM multi-NAL samples lose trailing zero-length
    // start codes), so the appended moov's stsz/chunk offsets must reflect
    // what was actually written into the mdat, not the raw encoder sizes.
    track.writtenSamples.addAll(pendingSamplesBufferInfo);
    List<Integer> sampleDurations =
        Boxes.convertPresentationTimestampsToDurationsVu(
            pendingSamplesBufferInfo,
            track.videoUnitTimebase(),
            LAST_SAMPLE_DURATION_BEHAVIOR_SET_FROM_END_OF_STREAM_BUFFER_OR_DUPLICATE_PREVIOUS,
            track.endOfStreamTimestampUs);

    // Advance the running duration counter and derive this fragment's duration (µs) —
    // both O(samples in this fragment), no walk over the whole recording.
    long fragmentDurationVu = 0;
    for (int i = 0; i < sampleDurations.size(); i++) {
      fragmentDurationVu += sampleDurations.get(i);
    }
    track.completedDurationVu = fragmentStartPts + fragmentDurationVu;
    long fragmentDurationUs = (fragmentDurationVu * 1_000_000L) / track.videoUnitTimebase();

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
    // Per-fragment conversion telemetry: shows exactly what survived the
    // Annex-B -> AVCC conversion (pre vs post bytes, dropped count).
    if (!pendingSamplesBufferInfo.isEmpty() && trackConvertedSamples > 0) {
      android.util.Log.i(
          "FragmentedMp4Writer",
          "[FRAG-TRACK] track="
              + trackId
              + " samples="
              + pendingSamplesBufferInfo.size()
              + " converted="
              + trackConvertedSamples
              + " dropped="
              + trackDroppedSamples
              + " preBytes="
              + trackPreConversionBytes
              + " postBytes="
              + totalSamplesSize);
    }

    return new ProcessedTrackInfo(
        trackId,
        track.format,
        totalSamplesSize,
        hasBFrame,
        pendingSamplesByteBuffer.build(),
        pendingSamplesMetadata.build(),
        fragmentStartPts,
        fragmentDurationUs);
  }

  private static class ProcessedTrackInfo {
    public final int trackId;
    public final Format trackFormat;
    public final int totalSamplesSize;
    public final boolean hasBFrame;
    public final ImmutableList<ByteBuffer> pendingSamplesByteBuffer;
    public final ImmutableList<SampleMetadata> pendingSamplesMetadata;
    public final long fragmentPts;
    /** Duration (µs) of the samples contained in this fragment. */
    public final long fragmentDurationUs;

    public ProcessedTrackInfo(
        int trackId,
        Format trackFormat,
        int totalSamplesSize,
        boolean hasBFrame,
        ImmutableList<ByteBuffer> pendingSamplesByteBuffer,
        ImmutableList<SampleMetadata> pendingSamplesMetadata,
        long fragmentPts,
        long fragmentDurationUs
) {
      this.trackId = trackId;
      this.trackFormat = trackFormat;
      this.totalSamplesSize = totalSamplesSize;
      this.hasBFrame = hasBFrame;
      this.pendingSamplesByteBuffer = pendingSamplesByteBuffer;
      this.pendingSamplesMetadata = pendingSamplesMetadata;
      this.fragmentPts = fragmentPts;
      this.fragmentDurationUs = fragmentDurationUs;
    }
  }
}
