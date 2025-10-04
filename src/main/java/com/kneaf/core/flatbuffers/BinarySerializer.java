package com.kneaf.core.flatbuffers;

/**
 * Deprecated stub. The previous BinarySerializer implementation was removed as manual ByteBuffer
 * serializers were introduced and inlined in com.kneaf.core.performance.RustPerformance.
 *
 * <p>Keeping a small stub prevents NoClassDefFoundError during the transition.
 */
public final class BinarySerializer {
  private BinarySerializer() {
    throw new UnsupportedOperationException(
        "BinarySerializer removed; use RustPerformance manual serializers");
  }
}
