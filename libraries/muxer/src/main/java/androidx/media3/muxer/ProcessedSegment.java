package androidx.media3.muxer;

import java.nio.ByteBuffer;

public class ProcessedSegment {
  public final boolean isInitSegment;

  public final int segmentNr;

  public final long durationMs;
  public final ByteBuffer payload;

  public ProcessedSegment(boolean isInitSegment, int segmentNr, long durationMs, ByteBuffer payload) {
    this.isInitSegment = isInitSegment;
    this.segmentNr = segmentNr;
    this.durationMs = durationMs;
    this.payload = payload;
  }
}