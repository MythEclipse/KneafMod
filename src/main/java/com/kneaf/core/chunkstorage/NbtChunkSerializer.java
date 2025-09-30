package com.kneaf.core.chunkstorage;

import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * NBT-based implementation of ChunkSerializer.
 * Uses Minecraft's built-in NBT format for chunk serialization.
 */
public class NbtChunkSerializer implements ChunkSerializer {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    private static final String FORMAT_NAME = "NBT";
    private static final int FORMAT_VERSION = 1;
    
    // NBT tag names for chunk data
    private static final String TAG_VERSION = "DataVersion";
    private static final String TAG_CHUNK_DATA = "ChunkData";
    private static final String TAG_CHECKSUM = "Checksum";
    private static final String TAG_TIMESTAMP = "Timestamp";
    
    @Override
    public byte[] serialize(LevelChunk chunk) throws IOException {
        if (chunk == null) {
            throw new IllegalArgumentException("Chunk cannot be null");
        }
        
        long startTime = System.nanoTime();
        
        try {
            // Create main compound tag
            CompoundTag rootTag = new CompoundTag();
            
            // Add metadata
            rootTag.putInt(TAG_VERSION, FORMAT_VERSION);
            rootTag.putLong(TAG_TIMESTAMP, System.currentTimeMillis());
            
            // Serialize chunk data to NBT using basic chunk information
            CompoundTag chunkData = new CompoundTag();
            
            // Save block entities - simplified approach without external dependencies
            serializeBlockEntities(chunk, chunkData);
            
            // Store basic chunk information
            chunkData.putInt("xPos", chunk.getPos().x);
            chunkData.putInt("zPos", chunk.getPos().z);
            chunkData.putString("status", chunk.getFullStatus().name());
            
            // Save section data for non-empty sections
            for (int y = chunk.getMinSection(); y < chunk.getMaxSection(); y++) {
                if (!chunk.isSectionEmpty(y)) {
                    String sectionKey = "section_" + y;
                    CompoundTag sectionTag = new CompoundTag();
                    sectionTag.putInt("y", y);
                    // Note: Full section serialization would require more complex handling
                    chunkData.put(sectionKey, sectionTag);
                }
            }
            
            rootTag.put(TAG_CHUNK_DATA, chunkData);
            
            // Calculate and add checksum for data integrity
            byte[] chunkBytes = writeCompoundTagToBytes(chunkData);
            long checksum = calculateChecksum(chunkBytes);
            rootTag.putLong(TAG_CHECKSUM, checksum);
            
            // Write complete NBT to bytes
            byte[] serializedData = writeCompoundTagToBytes(rootTag);
            
            if (LOGGER.isDebugEnabled()) {
                long duration = System.nanoTime() - startTime;
                LOGGER.debug("Serialized chunk at ({}, {}) to {} bytes in {} ms", 
                    chunk.getPos().x, chunk.getPos().z, 
                    serializedData.length, duration / 1_000_000);
            }
            
            return serializedData;
            
        } catch (IOException e) {
            LOGGER.error("Failed to serialize chunk at ({}, {}) due to I/O error: {}",
                chunk.getPos().x, chunk.getPos().z, e.getMessage(), e);
            throw new IOException(String.format("Failed to serialize chunk at (%d, %d): %s",
                chunk.getPos().x, chunk.getPos().z, e.getMessage()), e);
        } catch (IllegalArgumentException e) {
            LOGGER.error("Invalid argument while serializing chunk at ({}, {}): {}",
                chunk.getPos().x, chunk.getPos().z, e.getMessage(), e);
            throw new IOException(String.format("Invalid chunk data at (%d, %d): %s",
                chunk.getPos().x, chunk.getPos().z, e.getMessage()), e);
        } catch (Exception e) {
            LOGGER.error("Unexpected error while serializing chunk at ({}, {}): {} - {}",
                chunk.getPos().x, chunk.getPos().z, e.getClass().getSimpleName(), e.getMessage(), e);
            throw new IOException(String.format("Unexpected error serializing chunk at (%d, %d): %s",
                chunk.getPos().x, chunk.getPos().z, e.getMessage()), e);
        }
    }
    
    @Override
    public CompoundTag deserialize(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Data cannot be null or empty");
        }
        
        long startTime = System.nanoTime();
        
        try {
            // Read NBT from bytes
            CompoundTag rootTag = readCompoundTagFromBytes(data);
            
            // Validate format version
            int version = rootTag.getInt(TAG_VERSION);
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported format version: " + version);
            }
            
            // Verify checksum
            if (rootTag.contains(TAG_CHECKSUM, 4)) { // 4 = Long tag type
                CompoundTag chunkData = rootTag.getCompound(TAG_CHUNK_DATA);
                byte[] chunkBytes = writeCompoundTagToBytes(chunkData);
                long expectedChecksum = rootTag.getLong(TAG_CHECKSUM);
                long actualChecksum = calculateChecksum(chunkBytes);
                
                if (expectedChecksum != actualChecksum) {
                    throw new IOException("Checksum mismatch: expected " + expectedChecksum + 
                                        ", got " + actualChecksum);
                }
            }
            
            // Return the chunk data
            CompoundTag result = rootTag.getCompound(TAG_CHUNK_DATA);
            
            if (LOGGER.isDebugEnabled()) {
                long duration = System.nanoTime() - startTime;
                LOGGER.debug("Deserialized chunk data from {} bytes in {} ms", 
                    data.length, duration / 1_000_000);
            }
            
            return result;
            
        } catch (IOException e) {
            LOGGER.error("Failed to deserialize chunk data due to I/O error: {}", e.getMessage(), e);
            throw new IOException("Failed to deserialize chunk data: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            LOGGER.error("Invalid data format while deserializing chunk: {}", e.getMessage(), e);
            throw new IOException("Invalid chunk data format: " + e.getMessage(), e);
        } catch (Exception e) {
            LOGGER.error("Unexpected error while deserializing chunk data: {} - {}",
                e.getClass().getSimpleName(), e.getMessage(), e);
            throw new IOException("Unexpected error deserializing chunk data: " + e.getMessage(), e);
        }
    }
    
    @Override
    public String getFormat() {
        return FORMAT_NAME;
    }
    
    @Override
    public int getVersion() {
        return FORMAT_VERSION;
    }
    
    @Override
    public boolean supports(String format, int version) {
        return FORMAT_NAME.equals(format) && version == FORMAT_VERSION;
    }
    
    /**
     * Write a CompoundTag to byte array using Minecraft's NBT IO.
     */
    private byte[] writeCompoundTagToBytes(CompoundTag tag) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        
        try {
            NbtIo.write(tag, dos);
            return baos.toByteArray();
        } finally {
            dos.close();
        }
    }
    
    /**
     * Read a CompoundTag from byte array using Minecraft's NBT IO.
     */
    private CompoundTag readCompoundTagFromBytes(byte[] data) throws IOException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        DataInputStream dis = new DataInputStream(bais);
        
        try {
            return NbtIo.read(dis);
        } finally {
            dis.close();
        }
    }
    
    /**
     * Serialize block entities from a chunk into the provided compound tag.
     * This method extracts the nested try block to improve code readability.
     *
     * @param chunk The chunk containing block entities
     * @param chunkData The compound tag to store block entity data in
     */
    private void serializeBlockEntities(LevelChunk chunk, CompoundTag chunkData) {
        for (BlockPos pos : chunk.getBlockEntitiesPos()) {
            BlockEntity blockEntity = chunk.getBlockEntity(pos);
            if (blockEntity != null) {
                try {
                    // Create a simple tag for the block entity
                    CompoundTag beTag = new CompoundTag();
                    beTag.putInt("x", pos.getX());
                    beTag.putInt("y", pos.getY());
                    beTag.putInt("z", pos.getZ());
                    beTag.putString("id", blockEntity.getType().toString());
                    chunkData.put("block_entity_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ(), beTag);
                } catch (Exception e) {
                    LOGGER.debug("Could not serialize block entity at {}", pos, e);
                }
            }
        }
    }
    
    /**
     * Calculate checksum for data integrity verification.
     * Uses a simple but effective checksum algorithm.
     */
    private long calculateChecksum(byte[] data) {
        if (data == null || data.length == 0) {
            return 0L;
        }
        
        long checksum = 0;
        for (int i = 0; i < data.length; i++) {
            checksum = (checksum << 1) ^ (data[i] & 0xFF);
            // Rotate bits to improve distribution
            checksum = (checksum << 7) | (checksum >>> (64 - 7));
        }
        return checksum;
    }
}