package com.kneaf.core.performance;

import java.nio.ByteBuffer;

/**
 * Java facade for zero-copy binary serialization/deserialization
 * Provides high-performance binary operations with Rust backend
 */
public final class BinaryZeroCopyFacade {
    static {
        System.loadLibrary("rustperf");
    }

    /**
     * Deserialize mob input using zero-copy operations
     * @param buffer Direct ByteBuffer containing mob input data
     * @return Byte array with deserialized mob input (compatible with EntitySerializer)
     */
    public native byte[] nativeDeserializeMobInput(ByteBuffer buffer);

    /**
     * Serialize mob result using zero-copy operations
     * @param resultBuffer Direct ByteBuffer containing MobProcessResult
     * @param outputBuffer Pre-allocated direct ByteBuffer for output
     * @return Number of bytes written, or -1 on error
     */
    public native int nativeSerializeMobResult(ByteBuffer resultBuffer, ByteBuffer outputBuffer);

    /**
     * Calculate required buffer size for mob result serialization
     * @param resultBuffer Direct ByteBuffer containing MobProcessResult
     * @return Required buffer size in bytes, or -1 on error
     */
    public native int nativeCalculateMobResultSize(ByteBuffer resultBuffer);

    /**
     * Java wrapper for zero-copy binary operations
     */
    public static class ZeroCopyWrapper {
        private final BinaryZeroCopyFacade facade = new BinaryZeroCopyFacade();

        /**
         * Deserialize mob input with zero-copy
         * @param buffer Direct ByteBuffer containing input data
         * @return Deserialized mob input data
         * @throws IllegalArgumentException If buffer is not direct
         */
        public byte[] deserializeMobInput(ByteBuffer buffer) {
            if (!buffer.isDirect()) {
                throw new IllegalArgumentException("Buffer must be direct ByteBuffer for zero-copy operations");
            }
            return facade.nativeDeserializeMobInput(buffer);
        }

        /**
         * Serialize mob result with zero-copy
         * @param resultBuffer Direct ByteBuffer containing MobProcessResult
         * @return Serialized result as byte array
         */
        public byte[] serializeMobResult(ByteBuffer resultBuffer) {
            if (!resultBuffer.isDirect()) {
                throw new IllegalArgumentException("Buffer must be direct ByteBuffer for zero-copy operations");
            }
    
            // Calculate required buffer size first
            int requiredSize = facade.nativeCalculateMobResultSize(resultBuffer);
            if (requiredSize <= 0) {
                return new byte[0];
            }
    
            // Allocate direct buffer and serialize
            ByteBuffer outputBuffer = ByteBuffer.allocateDirect(requiredSize);
            try {
                int bytesWritten = facade.nativeSerializeMobResult(resultBuffer, outputBuffer);
                if (bytesWritten > 0) {
                    outputBuffer.flip();
                    byte[] result = new byte[bytesWritten];
                    outputBuffer.get(result);
                    return result;
                }
                return new byte[0];
            } finally {
                // ByteBuffer doesn't need closing but we can clear it
                outputBuffer.clear();
            }
        }

        /**
         * Create a direct ByteBuffer for zero-copy operations
         * @param capacity Buffer capacity in bytes
         * @return Direct ByteBuffer
         */
        public static ByteBuffer createDirectBuffer(int capacity) {
            return ByteBuffer.allocateDirect(capacity);
        }

        /**
         * Get native library version information
         * @return Version string or "unknown" if not available
         */
        public String getNativeVersion() {
            // In a real implementation, this would call a native method to get version info
            return "1.0.0";
        }
    }
}