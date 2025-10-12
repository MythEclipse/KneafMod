package com.kneaf.core.chunkstorage;

import com.kneaf.core.data.core.Serializable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Implementation of a Minecraft LevelChunk that can be used for testing and simulation.
 * This class provides the core functionality expected from a Minecraft LevelChunk
 * while being compatible with the existing serialization framework.
 *
 * <p>Key features:
 * - Implements Serializable interface for consistent serialization
 * - Thread-safe operations using ReadWriteLock
 * - Stores chunk coordinates (x, z) and block data
 * - Supports proper serialization/deserialization
 * - Compatible with existing swap testing framework
 */
public class LevelChunk implements Serializable {
    // Chunk coordinates - package-private for internal use
    int x;
    int z;
    
    // Block data storage - simulates Minecraft's block storage
    private Map<Integer, byte[]> blockSections;
    
    // Additional metadata
    private String worldName;
    private long lastModified;
    private boolean isModified;
    
    // Thread safety
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Create a new LevelChunk instance.
     *
     * @param worldName Name of the world this chunk belongs to
     * @param x         X coordinate of the chunk
     * @param z         Z coordinate of the chunk
     */
    public LevelChunk(String worldName, int x, int z) {
        this.worldName = worldName;
        this.x = x;
        this.z = z;
        this.blockSections = new HashMap<>();
        this.lastModified = System.currentTimeMillis();
        this.isModified = false;
    }

    /**
     * Package-private constructor for deserialization and testing.
     */
    LevelChunk() {
        this.blockSections = new HashMap<>();
    }

    /**
     * Get the X coordinate of this chunk.
     *
     * @return X coordinate
     */
    public int getX() {
        lock.readLock().lock();
        try {
            return x;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get the Z coordinate of this chunk.
     *
     * @return Z coordinate
     */
    public int getZ() {
        lock.readLock().lock();
        try {
            return z;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get the world name this chunk belongs to.
     *
     * @return World name
     */
    public String getWorldName() {
        lock.readLock().lock();
        try {
            return worldName;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get the last modified timestamp.
     *
     * @return Last modified time in milliseconds
     */
    public long getLastModified() {
        lock.readLock().lock();
        try {
            return lastModified;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Check if this chunk has been modified since last save.
     *
     * @return true if modified, false otherwise
     */
    public boolean isModified() {
        lock.readLock().lock();
        try {
            return isModified;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Set block data at the specified section index.
     *
     * @param sectionIndex Section index (0-15 for main sections, 16+ for extended)
     * @param blockData    Raw block data
     */
    public void setBlockSection(int sectionIndex, byte[] blockData) {
        lock.writeLock().lock();
        try {
            blockSections.put(sectionIndex, blockData != null ? blockData.clone() : null);
            lastModified = System.currentTimeMillis();
            isModified = true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get block data from the specified section index.
     *
     * @param sectionIndex Section index
     * @return Block data or null if not found
     */
    public byte[] getBlockSection(int sectionIndex) {
        lock.readLock().lock();
        try {
            byte[] data = blockSections.get(sectionIndex);
            return data != null ? data.clone() : null;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get all block sections in this chunk.
     *
     * @return Map of section indices to block data
     */
    public Map<Integer, byte[]> getBlockSections() {
        lock.readLock().lock();
        try {
            Map<Integer, byte[]> copy = new HashMap<>();
            for (Map.Entry<Integer, byte[]> entry : blockSections.entrySet()) {
                copy.put(entry.getKey(), entry.getValue() != null ? entry.getValue().clone() : null);
            }
            return copy;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Mark this chunk as saved (clear modified flag).
     */
    public void markAsSaved() {
        lock.writeLock().lock();
        try {
            isModified = false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Serialize this LevelChunk to a byte array using the existing framework format.
     *
     * @return Serialized byte array
     */
    @Override
    public byte[] serialize() {
        lock.readLock().lock();
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            // Write header
            dos.writeUTF("LEVEL_CHUNK_V1");
            dos.writeLong(lastModified);
            
            // Write chunk coordinates
            dos.writeInt(x);
            dos.writeInt(z);
            
            // Write world name
            dos.writeUTF(worldName);
            
            // Write metadata flags
            dos.writeBoolean(isModified);
            
            // Write block sections count and data
            dos.writeInt(blockSections.size());
            for (Map.Entry<Integer, byte[]> entry : blockSections.entrySet()) {
                int sectionIndex = entry.getKey();
                byte[] sectionData = entry.getValue();
                
                dos.writeInt(sectionIndex);
                dos.writeInt(sectionData != null ? sectionData.length : 0);
                if (sectionData != null) {
                    dos.write(sectionData);
                }
            }
            
            // Write footer
            dos.writeUTF("CHUNK_END");
            
            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize LevelChunk", e);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Deserialize a byte array into this LevelChunk instance.
     *
     * @param data Serialized byte array
     * @return true if deserialization was successful, false otherwise
     */
    @Override
    public boolean deserialize(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }

        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            DataInputStream dis = new DataInputStream(bais);

            // Read header
            String header = dis.readUTF();
            if (!header.equals("LEVEL_CHUNK_V1")) {
                return false;
            }

            long chunkLastModified = dis.readLong();
            
            // Read chunk coordinates
            int chunkX = dis.readInt();
            int chunkZ = dis.readInt();
            
            // Read world name
            String chunkWorldName = dis.readUTF();
            
            // Read metadata flags
            boolean chunkModified = dis.readBoolean();
            
            // Read block sections
            int sectionCount = dis.readInt();
            Map<Integer, byte[]> chunkBlockSections = new HashMap<>(sectionCount);
            
            for (int i = 0; i < sectionCount; i++) {
                int sectionIndex = dis.readInt();
                int sectionLength = dis.readInt();
                
                byte[] sectionData = null;
                if (sectionLength > 0) {
                    sectionData = new byte[sectionLength];
                    dis.readFully(sectionData);
                }
                
                chunkBlockSections.put(sectionIndex, sectionData);
            }
            
            // Read footer
            String footer = dis.readUTF();
            if (!footer.equals("CHUNK_END")) {
                return false;
            }

            // Populate this instance with deserialized data
            this.worldName = chunkWorldName;
            this.x = chunkX;
            this.z = chunkZ;
            this.lastModified = chunkLastModified;
            this.isModified = chunkModified;
            this.blockSections = chunkBlockSections;
            
            return true;
        } catch (IOException e) {
            return false;
        }
    }
    
    /**
     * Deserialize a byte array into a new LevelChunk instance.
     *
     * @param data Serialized byte array
     * @return New LevelChunk instance if deserialization was successful, null otherwise
     */
    public static LevelChunk deserializeStatic(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }

        LevelChunk chunk = new LevelChunk();
        if (chunk.deserialize(data)) {
            return chunk;
        }
        return null;
    }

    /**
     * Create a chunk key in the format used by the swap system.
     *
     * @return Chunk key string
     */
    public String createChunkKey() {
        return worldName + ":" + x + ":" + z;
    }

    @Override
    public String toString() {
        lock.readLock().lock();
        try {
            return String.format("LevelChunk{world='%s', x=%d, z=%d, sections=%d, modified=%b}",
                    worldName, x, z, blockSections.size(), isModified);
        } finally {
            lock.readLock().unlock();
        }
    }
}