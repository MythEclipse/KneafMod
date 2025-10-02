package com.kneaf.core.chunkstorage;

import java.io.IOException;

/**
 * Abstract serializer interface for converting chunks to/from binary format.
 * Supports NBT serialization format for flexibility.
 * This interface uses Object types to avoid Minecraft class dependencies in test environments.
 */
public interface ChunkSerializer {
    
    /**
     * Serialize a chunk to binary format.
     * 
     * @param chunk The chunk to serialize (typically LevelChunk in production)
     * @return Binary data representing the chunk
     * @throws IOException if serialization fails
     */
    byte[] serialize(Object chunk) throws IOException;
    
    /**
     * Deserialize binary data back into format that can be used to recreate a chunk.
     * 
     * @param data Binary data to deserialize
     * @return Deserialized data (typically CompoundTag in production)
     * @throws IOException if deserialization fails
     */
    Object deserialize(byte[] data) throws IOException;
    
    /**
     * Get the format identifier for this serializer.
     * Used for versioning and format detection.
     * 
     * @return Format identifier string
     */
    String getFormat();
    
    /**
     * Get the version of this serializer format.
     * Used for compatibility checking and data migration.
     * 
     * @return Version number
     */
    int getVersion();
    
    /**
     * Check if this serializer supports the given format and version.
     * 
     * @param format The format identifier
     * @param version The version number
     * @return true if supported, false otherwise
     */
    boolean supports(String format, int version);
}