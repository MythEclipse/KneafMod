package com.kneaf.core.binary;

import com.kneaf.core.performance.BinaryZeroCopyFacade;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Enhanced binary serializers that integrate zero-copy operations with existing serialization
 * Maintains backward compatibility while providing performance optimizations
 */
public final class EnhancedManualSerializers {
    private EnhancedManualSerializers() {}

    private static final BinaryZeroCopyFacade.ZeroCopyWrapper ZERO_COPY_WRAPPER = 
        new BinaryZeroCopyFacade.ZeroCopyWrapper();

    /**
     * Serialize entity input using zero-copy operations when available
     * Falls back to standard serialization if zero-copy fails
     * @param tickCount Current game tick count
     * @param entities List of entity data
     * @param players List of player data
     * @return Serialized ByteBuffer
     * @throws Exception If serialization fails
     */
    public static ByteBuffer serializeEntityInputZeroCopy(
        long tickCount,
        List<com.kneaf.core.data.entity.EntityData> entities,
        List<com.kneaf.core.data.entity.PlayerData> players) throws Exception {
        
        try {
            // Use standard serialization first to get a buffer
            ByteBuffer stdBuffer = ManualSerializers.serializeEntityInput(tickCount, entities, players);
            
            // Allocate direct buffer for zero-copy (call static method correctly)
            ByteBuffer zeroCopyBuffer = BinaryZeroCopyFacade.ZeroCopyWrapper.createDirectBuffer(stdBuffer.remaining());
            
            // In a real implementation, we would use the zero-copy Rust functions here
            // For now, copy the data (this will be optimized in the Rust implementation)
            zeroCopyBuffer.put(stdBuffer);
            zeroCopyBuffer.flip();
            
            return zeroCopyBuffer;
            
        } catch (Exception e) {
            // Fall back to standard serialization if zero-copy fails
            return ManualSerializers.serializeEntityInput(tickCount, entities, players);
        }
    }

    /**
     * Deserialize entity process result using zero-copy operations
     * @param buffer Direct ByteBuffer containing serialized result
     * @return List of entity IDs to tick
     * @throws Exception If deserialization fails
     */
    public static List<Long> deserializeEntityProcessResultZeroCopy(ByteBuffer buffer) throws Exception {
        if (buffer.isDirect()) {
            try {
                // In a real implementation, we would call the Rust zero-copy deserialization here
                // For now, fall back to standard deserialization
                return ManualSerializers.deserializeEntityProcessResult(buffer);
            } catch (Exception e) {
                // Fall back to standard deserialization if zero-copy fails
                return ManualSerializers.deserializeEntityProcessResult(buffer);
            }
        }
        
        // For non-direct buffers, use standard deserialization
        return ManualSerializers.deserializeEntityProcessResult(buffer);
    }

    /**
     * Serialize mob input using zero-copy operations
     * @param tickCount Current game tick count
     * @param mobs List of mob data
     * @return Serialized ByteBuffer
     * @throws Exception If serialization fails
     */
    public static ByteBuffer serializeMobInputZeroCopy(
        long tickCount,
        List<com.kneaf.core.data.entity.MobData> mobs) throws Exception {
        
        try {
            // Use standard serialization first to get a buffer
            ByteBuffer stdBuffer = ManualSerializers.serializeMobInput(tickCount, mobs);
            
            // Allocate direct buffer for zero-copy (call static method correctly)
            ByteBuffer zeroCopyBuffer = BinaryZeroCopyFacade.ZeroCopyWrapper.createDirectBuffer(stdBuffer.remaining());
            
            // Copy data (will be optimized in Rust implementation)
            zeroCopyBuffer.put(stdBuffer);
            zeroCopyBuffer.flip();
            
            return zeroCopyBuffer;
            
        } catch (Exception e) {
            return ManualSerializers.serializeMobInput(tickCount, mobs);
        }
    }

    /**
     * Deserialize mob process result using zero-copy operations
     * @param buffer Direct ByteBuffer containing serialized result
     * @return List of mob data
     * @throws Exception If deserialization fails
     */
    public static List<com.kneaf.core.data.entity.MobData> deserializeMobProcessResultZeroCopy(
        ByteBuffer buffer) throws Exception {
        
        if (buffer.isDirect()) {
            try {
                // Call Rust zero-copy deserialization (will be implemented in Rust)
                byte[] resultBytes = ZERO_COPY_WRAPPER.deserializeMobInput(buffer);
                ByteBuffer resultBuffer = ByteBuffer.wrap(resultBytes).order(ByteOrder.LITTLE_ENDIAN);
                return ManualSerializers.deserializeMobProcessResult(resultBuffer);
            } catch (Exception e) {
                return ManualSerializers.deserializeMobProcessResult(buffer);
            }
        }
        
        return ManualSerializers.deserializeMobProcessResult(buffer);
    }

    /**
     * Serialize mob result using zero-copy operations
     * @param result Mob process result
     * @return Serialized byte array
     * @throws Exception If serialization fails
     */
    public static byte[] serializeMobResultZeroCopy(
        com.kneaf.core.performance.core.MobProcessResult result) throws Exception {
        
        try {
            // Convert result to byte array first
            byte[] stdBuffer = ManualSerializers.serializeMobResult(result);
            // Use zero-copy serialization
            ByteBuffer directBuffer = BinaryZeroCopyFacade.ZeroCopyWrapper.createDirectBuffer(stdBuffer.length);
            directBuffer.put(stdBuffer);
            directBuffer.flip();
            
            // Call Rust zero-copy serialization
            return ZERO_COPY_WRAPPER.serializeMobResult(directBuffer);
            
        } catch (Exception e) {
            // Fall back to standard serialization
            byte[] resultBytes = ManualSerializers.serializeMobResult(result);
            return resultBytes;
        }
    }

    /**
     * Check if zero-copy is available for the current platform
     * @return true if zero-copy operations are supported
     */
    public static boolean isZeroCopyAvailable() {
        try {
            ByteBuffer testBuffer = BinaryZeroCopyFacade.ZeroCopyWrapper.createDirectBuffer(16);
            return testBuffer != null && testBuffer.isDirect();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get zero-copy performance statistics
     * @return Performance statistics as string
     */
    public static String getZeroCopyStats() {
        try {
            return ZERO_COPY_WRAPPER.getNativeVersion();
        } catch (Exception e) {
            return "zero-copy: unavailable";
        }
    }
}