package com.kneaf.core.chunkstorage.serialization;

import com.kneaf.core.chunkstorage.common.ChunkStorageConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * NBT-based implementation of ChunkSerializer.
 * Uses Minecraft's built-in NBT format for chunk serialization.
 * This implementation includes proper error handling for test environments.
 */
public class NbtChunkSerializer implements ChunkSerializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(NbtChunkSerializer.class);
    
    private static final String FORMAT_NAME = "NBT";
    private static final int FORMAT_VERSION = 1;
    
    // NBT tag names for chunk data
    private static final String TAG_VERSION = "DataVersion";
    private static final String TAG_CHUNK_DATA = "ChunkData";
    private static final String TAG_CHECKSUM = "Checksum";
    private static final String TAG_TIMESTAMP = "Timestamp";
    
    // Static flag to track if Minecraft classes are available
    private static final boolean MINECRAFT_CLASSES_AVAILABLE;
    
    static {
        boolean minecraftAvailable = false;
        try {
            // Try to load a key Minecraft class
            Class.forName("net.minecraft.nbt.CompoundTag");
            minecraftAvailable = true;
            LOGGER.info("Minecraft classes available - NbtChunkSerializer fully functional");
        } catch (ClassNotFoundException e) {
            LOGGER.warn("Minecraft classes not available - NbtChunkSerializer will be non-functional");
        }
        MINECRAFT_CLASSES_AVAILABLE = minecraftAvailable;
    }
    
    /**
     * Check if Minecraft classes are available for serialization.
     * @return true if Minecraft classes are available, false otherwise
     */
    public static boolean isMinecraftAvailable() {
        return MINECRAFT_CLASSES_AVAILABLE;
    }
    
    @Override
    public byte[] serialize(Object chunk) throws IOException {
        if (!MINECRAFT_CLASSES_AVAILABLE) {
            throw new IOException("Minecraft classes not available - cannot serialize chunks");
        }
        
        if (chunk == null) {
            throw new IllegalArgumentException("Chunk cannot be null");
        }
        
        // Delegate to the actual implementation
        return serializeChunk(chunk);
    }
    
    @Override
    public Object deserialize(byte[] data) throws IOException {
        if (!MINECRAFT_CLASSES_AVAILABLE) {
            throw new IOException("Minecraft classes not available - cannot deserialize chunks");
        }
        
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Data cannot be null or empty");
        }
        
        // Delegate to the actual implementation
        return deserializeChunk(data);
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
     * Actual serialization implementation that uses Minecraft classes.
     * This method is only called if Minecraft classes are available.
     */
    private byte[] serializeChunk(Object chunk) throws IOException {
        try {
            // Load Minecraft classes dynamically to avoid ClassNotFoundException during class loading
            Class<?> levelChunkClass = Class.forName("net.minecraft.world.level.chunk.LevelChunk");
            Class<?> compoundTagClass = Class.forName("net.minecraft.nbt.CompoundTag");
            Class<?> listTagClass = Class.forName("net.minecraft.nbt.ListTag");
            Class<?> nbtIoClass = Class.forName("net.minecraft.nbt.NbtIo");
            Class<?> blockPosClass = Class.forName("net.minecraft.core.BlockPos");
            Class<?> blockStateClass = Class.forName("net.minecraft.world.level.block.state.BlockState");
            Class<?> blockEntityClass = Class.forName("net.minecraft.world.level.block.entity.BlockEntity");
            
            long startTime = System.nanoTime();
            
            // Create main compound tag
            Object rootTag = compoundTagClass.getDeclaredConstructor().newInstance();
            
            // Add metadata using reflection
            compoundTagClass.getMethod("putInt", String.class, int.class).invoke(rootTag, TAG_VERSION, FORMAT_VERSION);
            compoundTagClass.getMethod("putLong", String.class, long.class).invoke(rootTag, TAG_TIMESTAMP, System.currentTimeMillis());
            
            // Serialize chunk data to NBT using basic chunk information
            Object chunkData = compoundTagClass.getDeclaredConstructor().newInstance();
            
            // Get chunk position and basic info using reflection
            Object chunkPos = levelChunkClass.getMethod("getPos").invoke(chunk);
            int chunkX = (int) chunkPos.getClass().getMethod("x").invoke(chunkPos);
            int chunkZ = (int) chunkPos.getClass().getMethod("z").invoke(chunkPos);
            
            // Store basic chunk information
            compoundTagClass.getMethod("putInt", String.class, int.class).invoke(chunkData, "xPos", chunkX);
            compoundTagClass.getMethod("putInt", String.class, int.class).invoke(chunkData, "zPos", chunkZ);
            
            try {
                Object fullStatus = levelChunkClass.getMethod("getFullStatus").invoke(chunk);
                String statusName = fullStatus.toString();
                compoundTagClass.getMethod("putString", String.class, String.class).invoke(chunkData, "status", statusName);
            } catch (Exception e) {
                LOGGER.debug("Could not get chunk status, using default");
                compoundTagClass.getMethod("putString", String.class, String.class).invoke(chunkData, "status", "FULL");
            }
            
            // Save section data for all sections
            serializeChunkSections(chunk, chunkData, levelChunkClass, compoundTagClass, listTagClass, blockStateClass);
            
            // Save heightmaps
            serializeHeightmaps(chunk, chunkData, levelChunkClass, compoundTagClass, blockPosClass, blockStateClass);
            
            // Save block entities
            serializeBlockEntities(chunk, chunkData, levelChunkClass, compoundTagClass, blockPosClass, blockEntityClass);
            
            compoundTagClass.getMethod("put", String.class, compoundTagClass).invoke(rootTag, TAG_CHUNK_DATA, chunkData);
            
            // Calculate and add checksum for data integrity
            byte[] chunkBytes = writeCompoundTagToBytes(chunkData, nbtIoClass);
            long checksum = calculateChecksum(chunkBytes);
            compoundTagClass.getMethod("putLong", String.class, long.class).invoke(rootTag, TAG_CHECKSUM, checksum);
            
            // Write complete NBT to bytes
            byte[] serializedData = writeCompoundTagToBytes(rootTag, nbtIoClass);
            
            if (LOGGER.isDebugEnabled()) {
                long duration = System.nanoTime() - startTime;
                LOGGER.debug("Serialized chunk at ({}, {}) to {} bytes in {} ms", 
                    chunkX, chunkZ, serializedData.length, duration / 1_000_000);
            }
            
            return serializedData;
            
        } catch (Exception e) {
            LOGGER.error("Failed to serialize chunk due to error: {}", e.getMessage(), e);
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Failed to serialize chunk: " + e.getMessage(), e);
        }
    }
    
    /**
     * Actual deserialization implementation that uses Minecraft classes.
     * This method is only called if Minecraft classes are available.
     */
    private Object deserializeChunk(byte[] data) throws IOException {
        try {
            // Load Minecraft classes dynamically
            Class<?> compoundTagClass = Class.forName("net.minecraft.nbt.CompoundTag");
            Class<?> nbtIoClass = Class.forName("net.minecraft.nbt.NbtIo");
            
            long startTime = System.nanoTime();
            
            // Read NBT from bytes
            Object rootTag = readCompoundTagFromBytes(data, nbtIoClass);
            
            // Validate format version
            int version = (int) compoundTagClass.getMethod("getInt", String.class).invoke(rootTag, TAG_VERSION);
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported format version: " + version);
            }
            
            // Verify checksum
            if ((boolean) compoundTagClass.getMethod("contains", String.class, int.class).invoke(rootTag, TAG_CHECKSUM, 4)) { // 4 = Long tag type
                Object chunkData = compoundTagClass.getMethod("getCompound", String.class).invoke(rootTag, TAG_CHUNK_DATA);
                byte[] chunkBytes = writeCompoundTagToBytes(chunkData, nbtIoClass);
                long expectedChecksum = (long) compoundTagClass.getMethod("getLong", String.class).invoke(rootTag, TAG_CHECKSUM);
                long actualChecksum = calculateChecksum(chunkBytes);
                
                if (expectedChecksum != actualChecksum) {
                    throw new IOException("Checksum mismatch: expected " + expectedChecksum + 
                                        ", got " + actualChecksum);
                }
            }
            
            // Return the chunk data
            Object result = compoundTagClass.getMethod("getCompound", String.class).invoke(rootTag, TAG_CHUNK_DATA);
            
            if (LOGGER.isDebugEnabled()) {
                long duration = System.nanoTime() - startTime;
                LOGGER.debug("Deserialized chunk data from {} bytes in {} ms", 
                    data.length, duration / 1_000_000);
            }
            
            return result;
            
        } catch (Exception e) {
            LOGGER.error("Failed to deserialize chunk data due to error: {}", e.getMessage(), e);
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException("Failed to deserialize chunk data: " + e.getMessage(), e);
        }
    }
    
    /**
     * Serialize all chunk sections including block states and data.
     */
    private void serializeChunkSections(Object chunk, Object chunkData, Class<?> levelChunkClass, 
                                      Class<?> compoundTagClass, Class<?> listTagClass, Class<?> blockStateClass) throws Exception {
        Object sectionsList = listTagClass.getDeclaredConstructor().newInstance();
        
        // Get min and max sections
        int minSection = (int) levelChunkClass.getMethod("getMinSection").invoke(chunk);
        int maxSection = (int) levelChunkClass.getMethod("getMaxSection").invoke(chunk);
        
        for (int y = minSection; y < maxSection; y++) {
            Object section = levelChunkClass.getMethod("getSection", int.class).invoke(chunk, 
                levelChunkClass.getMethod("getSectionIndexFromSectionY", int.class).invoke(chunk, y));
            
            if (section != null && !(boolean) section.getClass().getMethod("hasOnlyAir").invoke(section)) {
                Object sectionTag = compoundTagClass.getDeclaredConstructor().newInstance();
                compoundTagClass.getMethod("putInt", String.class, int.class).invoke(sectionTag, "y", y);
                
                // Serialize block states
                serializeBlockStates(section, sectionTag, compoundTagClass, listTagClass, blockStateClass);
                
                listTagClass.getMethod("add", compoundTagClass).invoke(sectionsList, sectionTag);
            }
        }
        
        compoundTagClass.getMethod("put", String.class, listTagClass).invoke(chunkData, "sections", sectionsList);
    }
    
    /**
     * Serialize block states for a chunk section.
     */
    private void serializeBlockStates(Object section, Object sectionTag, Class<?> compoundTagClass, 
                                    Class<?> listTagClass, Class<?> blockStateClass) throws Exception {
        Object blockStatesList = listTagClass.getDeclaredConstructor().newInstance();
        
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    Object blockState = section.getClass().getMethod("getBlockState", int.class, int.class, int.class)
                        .invoke(section, x, y, z);
                    
                    if (!(boolean) blockStateClass.getMethod("isAir").invoke(blockState)) {
                        Object blockTag = compoundTagClass.getDeclaredConstructor().newInstance();
                        compoundTagClass.getMethod("putInt", String.class, int.class).invoke(blockTag, "x", x);
                        compoundTagClass.getMethod("putInt", String.class, int.class).invoke(blockTag, "y", y);
                        compoundTagClass.getMethod("putInt", String.class, int.class).invoke(blockTag, "z", z);
                        compoundTagClass.getMethod("putString", String.class, String.class).invoke(blockTag, "block", blockState.toString());
                        listTagClass.getMethod("add", compoundTagClass).invoke(blockStatesList, blockTag);
                    }
                }
            }
        }
        
        compoundTagClass.getMethod("put", String.class, listTagClass).invoke(sectionTag, "block_states", blockStatesList);
    }
    
    /**
     * Serialize heightmaps for the chunk.
     */
    private void serializeHeightmaps(Object chunk, Object chunkData, Class<?> levelChunkClass, 
                                   Class<?> compoundTagClass, Class<?> blockPosClass, Class<?> blockStateClass) throws Exception {
        Object heightmapsTag = compoundTagClass.getDeclaredConstructor().newInstance();
        
        try {
            // Store basic height information for the chunk
            int minHeight = (int) levelChunkClass.getMethod("getMinBuildHeight").invoke(chunk);
            int maxHeight = (int) levelChunkClass.getMethod("getMaxBuildHeight").invoke(chunk);
            compoundTagClass.getMethod("putInt", String.class, int.class).invoke(heightmapsTag, "minHeight", minHeight);
            compoundTagClass.getMethod("putInt", String.class, int.class).invoke(heightmapsTag, "maxHeight", maxHeight);
            
            // Get chunk position
            Object chunkPos = levelChunkClass.getMethod("getPos").invoke(chunk);
            int minBlockX = (int) chunkPos.getClass().getMethod("getMinBlockX").invoke(chunkPos);
            int minBlockZ = (int) chunkPos.getClass().getMethod("getMinBlockZ").invoke(chunkPos);
            
            // Store surface height information at key positions
            for (int x = 0; x < 16; x += 4) {
                for (int z = 0; z < 16; z += 4) {
                    // Find surface height at this position
                    int surfaceY = findSurfaceHeight(chunk, x, z, minHeight, maxHeight, 
                        levelChunkClass, blockPosClass, blockStateClass, minBlockX, minBlockZ);
                    String key = "height_" + x + "_" + z;
                    compoundTagClass.getMethod("putInt", String.class, int.class).invoke(heightmapsTag, key, surfaceY);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Error while serializing heightmaps: {}", e.getMessage(), e);
            // Continue with partial heightmap data
        }
        
        compoundTagClass.getMethod("put", String.class, compoundTagClass).invoke(chunkData, "heightmaps", heightmapsTag);
    }
    
    /**
     * Serialize block entities from a chunk.
     */
    private void serializeBlockEntities(Object chunk, Object chunkData, Class<?> levelChunkClass, 
                                      Class<?> compoundTagClass, Class<?> blockPosClass, Class<?> blockEntityClass) throws Exception {
        Object blockEntitiesPos = levelChunkClass.getMethod("getBlockEntitiesPos").invoke(chunk);
        
        // Iterate over block entity positions
        if (blockEntitiesPos instanceof Iterable) {
            for (Object pos : (Iterable<?>) blockEntitiesPos) {
                Object blockEntity = levelChunkClass.getMethod("getBlockEntity", blockPosClass).invoke(chunk, pos);
                if (blockEntity != null) {
                    try {
                        // Create a simple tag for the block entity
                        Object beTag = compoundTagClass.getDeclaredConstructor().newInstance();
                        
                        int x = (int) blockPosClass.getMethod("getX").invoke(pos);
                        int y = (int) blockPosClass.getMethod("getY").invoke(pos);
                        int z = (int) blockPosClass.getMethod("getZ").invoke(pos);
                        
                        compoundTagClass.getMethod("putInt", String.class, int.class).invoke(beTag, "x", x);
                        compoundTagClass.getMethod("putInt", String.class, int.class).invoke(beTag, "y", y);
                        compoundTagClass.getMethod("putInt", String.class, int.class).invoke(beTag, "z", z);
                        
                        try {
                            Object blockEntityType = blockEntityClass.getMethod("getType").invoke(blockEntity);
                            String typeString = blockEntityType.toString();
                            compoundTagClass.getMethod("putString", String.class, String.class).invoke(beTag, "id", typeString);
                        } catch (Exception e) {
                            compoundTagClass.getMethod("putString", String.class, String.class).invoke(beTag, "id", "unknown");
                        }
                        
                        String key = "block_entity_" + x + "_" + y + "_" + z;
                        compoundTagClass.getMethod("put", String.class, compoundTagClass).invoke(chunkData, key, beTag);
                    } catch (Exception e) {
                        // Log the exception and continue serializing other block entities
                        LOGGER.warn("Could not serialize block entity at {}: {}", pos, e.getMessage(), e);
                    }
                }
            }
        }
    }
    
    /**
     * Find the surface height at a given position within the chunk.
     */
    private int findSurfaceHeight(Object chunk, int x, int z, int minHeight, int maxHeight,
                                Class<?> levelChunkClass, Class<?> blockPosClass, Class<?> blockStateClass,
                                int minBlockX, int minBlockZ) throws Exception {
        for (int y = maxHeight - 1; y >= minHeight; y--) {
            Object pos = blockPosClass.getDeclaredConstructor(int.class, int.class, int.class)
                .newInstance(minBlockX + x, y, minBlockZ + z);
            Object blockState = levelChunkClass.getMethod("getBlockState", blockPosClass).invoke(chunk, pos);
            if (!(boolean) blockStateClass.getMethod("isAir").invoke(blockState)) {
                return y;
            }
        }
        return minHeight;
    }
    
    /**
     * Write a CompoundTag to byte array using Minecraft's NBT IO.
     */
    private byte[] writeCompoundTagToBytes(Object tag, Class<?> nbtIoClass) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream dos = new java.io.DataOutputStream(baos);
        
        try {
            nbtIoClass.getMethod("write", Class.forName("net.minecraft.nbt.CompoundTag"), java.io.DataOutput.class).invoke(null, tag, dos);
            return baos.toByteArray();
        } finally {
            dos.close();
        }
    }
    
    /**
     * Read a CompoundTag from byte array using Minecraft's NBT IO.
     */
    private Object readCompoundTagFromBytes(byte[] data, Class<?> nbtIoClass) throws Exception {
        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(data);
        java.io.DataInputStream dis = new java.io.DataInputStream(bais);
        
        try {
            return nbtIoClass.getMethod("read", java.io.DataInput.class).invoke(null, dis);
        } finally {
            dis.close();
        }
    }
    
    /**
     * Calculate checksum for data integrity verification.
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