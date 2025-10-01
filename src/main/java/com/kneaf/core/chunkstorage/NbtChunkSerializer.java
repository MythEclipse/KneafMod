package com.kneaf.core.chunkstorage;

import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ListTag;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
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
            
            // Save section data for all sections
            serializeChunkSections(chunk, chunkData);
            
            // Save heightmaps
            serializeHeightmaps(chunk, chunkData);
            
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
     * Serialize all chunk sections including block states and data.
     * This method serializes each non-empty section with complete block data.
     *
     * @param chunk The chunk containing sections
     * @param chunkData The compound tag to store section data in
     */
    private void serializeChunkSections(LevelChunk chunk, CompoundTag chunkData) {
        ListTag sectionsList = new ListTag();
        
        for (int y = chunk.getMinSection(); y < chunk.getMaxSection(); y++) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(y));
            if (section != null && !section.hasOnlyAir()) {
                CompoundTag sectionTag = new CompoundTag();
                sectionTag.putInt("y", y);
                
                // Serialize block states
                serializeBlockStates(section, sectionTag);
                
                // Serialize block light data if present
                // Note: Light data is typically handled by the server's light engine
                // and may not be directly accessible from the section
                
                sectionsList.add(sectionTag);
            }
        }
        
        chunkData.put("sections", sectionsList);
    }
    
    /**
     * Serialize block states for a chunk section.
     * This method serializes all block states in the section using a compact format.
     *
     * @param section The chunk section containing block states
     * @param sectionTag The compound tag to store block state data in
     */
    private void serializeBlockStates(LevelChunkSection section, CompoundTag sectionTag) {
        ListTag blockStatesList = new ListTag();
        
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockState blockState = section.getBlockState(x, y, z);
                    if (!blockState.isAir()) {
                        CompoundTag blockTag = new CompoundTag();
                        blockTag.putInt("x", x);
                        blockTag.putInt("y", y);
                        blockTag.putInt("z", z);
                        blockTag.putString("block", blockState.toString());
                        blockStatesList.add(blockTag);
                    }
                }
            }
        }
        
        sectionTag.put("block_states", blockStatesList);
    }
    
    /**
     * Serialize heightmaps for the chunk.
     * This method saves heightmap data for terrain generation.
     *
     * @param chunk The chunk containing heightmap data
     * @param chunkData The compound tag to store heightmap data in
     */
    private void serializeHeightmaps(LevelChunk chunk, CompoundTag chunkData) {
        CompoundTag heightmapsTag = new CompoundTag();
        
        // Serialize basic heightmap information
        // Note: Heightmap data is typically stored within the chunk's internal structure
        // and accessed during chunk serialization through Minecraft's built-in mechanisms
        try {
            // Store basic height information for the chunk
            int minHeight = chunk.getMinBuildHeight();
            int maxHeight = chunk.getMaxBuildHeight();
            heightmapsTag.putInt("minHeight", minHeight);
            heightmapsTag.putInt("maxHeight", maxHeight);
            
            // Store surface height information at key positions
            // This provides basic terrain height data for reconstruction
            for (int x = 0; x < 16; x += 4) {
                for (int z = 0; z < 16; z += 4) {
                    // Find surface height at this position
                    int surfaceY = findSurfaceHeight(chunk, x, z, minHeight, maxHeight);
                    String key = "height_" + x + "_" + z;
                    heightmapsTag.putInt(key, surfaceY);
                }
            }
        } catch (Exception e) {
            // If heightmap access fails, log and continue
            LOGGER.debug("Could not serialize heightmaps: {}", e.getMessage());
        }
        
        chunkData.put("heightmaps", heightmapsTag);
    }
    
    /**
     * Find the surface height at a given position within the chunk.
     * This method scans from top to bottom to find the first non-air block.
     *
     * @param chunk The chunk to search
     * @param x Local x coordinate within chunk (0-15)
     * @param z Local z coordinate within chunk (0-15)
     * @param minHeight Minimum build height
     * @param maxHeight Maximum build height
     * @return The y-coordinate of the surface, or minHeight if no surface found
     */
    private int findSurfaceHeight(LevelChunk chunk, int x, int z, int minHeight, int maxHeight) {
        for (int y = maxHeight - 1; y >= minHeight; y--) {
            BlockPos pos = new BlockPos(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
            if (!chunk.getBlockState(pos).isAir()) {
                return y;
            }
        }
        return minHeight;
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
                    // Log the exception with contextual information and continue serializing other
                    // block entities. We intentionally handle the exception (don't rethrow) to
                    // avoid failing the whole chunk serialization due to a single bad block
                    // entity. Use WARN level because this indicates potentially corrupt data.
                    String beType = "<unknown>";
                    try {
                        beType = blockEntity.getType().toString();
                    } catch (Exception ex) {
                        // ignore - we still want to log the original exception
                    }
                    LOGGER.warn("Could not serialize block entity at {} (type: {}): {}",
                        pos, beType, e.getMessage(), e);
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