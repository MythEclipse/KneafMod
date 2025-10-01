package com.kneaf.core.chunkstorage;

import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.nbt.CompoundTag;
import java.io.IOException;

/**
 * Abstract serializer interface for converting LevelChunk to/from binary format.
 * Supports NBT serialization format for flexibility.
 */
public interface ChunkSerializer {
    
    /**
     * Serialize a LevelChunk to binary format.
     * 
     * @param chunk The LevelChunk to serialize
     * @return Binary data representing the chunk
     * @throws IOException if serialization fails
     */
    byte[] serialize(LevelChunk chunk) throws IOException;
    
    /**
     * Deserialize binary data back into NBT format that can be used to recreate a LevelChunk.
     * 
     * @param data Binary data to deserialize
     * @return CompoundTag containing chunk data
     * @throws IOException if deserialization fails
     */
    CompoundTag deserialize(byte[] data) throws IOException;
    
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