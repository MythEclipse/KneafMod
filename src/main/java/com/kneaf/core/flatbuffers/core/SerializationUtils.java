package com.kneaf.core.flatbuffers.core;

import com.kneaf.core.flatbuffers.utils.SerializationException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Utility methods for flatbuffer serialization operations.
 * Provides helper methods for buffer management, data conversion, and validation.
 */
public final class SerializationUtils {
    
    private SerializationUtils() {
        // Private constructor to prevent instantiation
    }
    
    /**
     * Create a new ByteBuffer with the specified initial capacity.
     * The buffer will be configured for little-endian byte order.
     * 
     * @param initialCapacity the initial capacity of the buffer
     * @return a new ByteBuffer
     */
    public static ByteBuffer createBuffer(int initialCapacity) {
        ByteBuffer buffer = ByteBuffer.allocate(initialCapacity);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        return buffer;
    }
    
    /**
     * Create a new ByteBuffer from existing byte array.
     * The buffer will be configured for little-endian byte order.
     * 
     * @param data the byte array to wrap
     * @return a new ByteBuffer wrapping the data
     */
    public static ByteBuffer wrapBuffer(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("Data cannot be null");
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        return buffer;
    }
    
    /**
     * Ensure buffer has enough remaining capacity for the required bytes.
     * If not enough space, creates a new larger buffer and copies existing data.
     * 
     * @param buffer the current buffer
     * @param requiredBytes the number of bytes needed
     * @return the buffer (possibly a new one with more capacity)
     */
    public static ByteBuffer ensureCapacity(ByteBuffer buffer, int requiredBytes) {
        if (buffer == null) {
            throw new IllegalArgumentException("Buffer cannot be null");
        }
        
        if (buffer.remaining() >= requiredBytes) {
            return buffer;
        }
        
        // Calculate new capacity
        int currentCapacity = buffer.capacity();
        int currentPosition = buffer.position();
        int newCapacity = Math.max(currentCapacity * SerializationConstants.BUFFER_GROWTH_FACTOR, 
                                   currentPosition + requiredBytes);
        
        // Ensure we don't exceed maximum buffer size
        if (newCapacity > SerializationConstants.MAX_BUFFER_SIZE) {
            throw new SerializationException("Buffer size exceeds maximum allowed size: " + newCapacity);
        }
        
        // Create new buffer and copy data
        ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
        newBuffer.order(buffer.order());
        
        // Copy existing data
        buffer.flip(); // Switch to read mode
        newBuffer.put(buffer);
        
        return newBuffer;
    }
    
    /**
     * Write a string to the buffer with length prefix.
     * Format: [length:4 bytes][string bytes]
     * 
     * @param buffer the buffer to write to
     * @param str the string to write
     * @param charset the charset to use
     */
    public static void writeString(ByteBuffer buffer, String str, Charset charset) {
        if (buffer == null) {
            throw new IllegalArgumentException("Buffer cannot be null");
        }
        if (str == null) {
            buffer.putInt(0);
            return;
        }
        
        byte[] bytes = str.getBytes(charset);
        
        // Validate string length
        if (bytes.length > SerializationConstants.MAX_STRING_LENGTH) {
            throw new SerializationException("String length exceeds maximum allowed: " + bytes.length);
        }
        
        buffer.putInt(bytes.length);
        buffer.put(bytes);
    }
    
    /**
     * Write a string to the buffer with length prefix using UTF-8 encoding.
     * 
     * @param buffer the buffer to write to
     * @param str the string to write
     */
    public static void writeString(ByteBuffer buffer, String str) {
        writeString(buffer, str, StandardCharsets.UTF_8);
    }
    
    /**
     * Read a string from the buffer with length prefix.
     * Format: [length:4 bytes][string bytes]
     * 
     * @param buffer the buffer to read from
     * @param charset the charset to use
     * @return the read string
     */
    public static String readString(ByteBuffer buffer, Charset charset) {
        if (buffer == null) {
            throw new IllegalArgumentException("Buffer cannot be null");
        }
        
        int length = buffer.getInt();
        if (length == 0) {
            return "";
        }
        
        if (length < 0 || length > SerializationConstants.MAX_STRING_LENGTH) {
            throw new SerializationException("Invalid string length: " + length);
        }
        
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        
        return new String(bytes, charset);
    }
    
    /**
     * Read a string from the buffer with length prefix using UTF-8 encoding.
     * 
     * @param buffer the buffer to read from
     * @return the read string
     */
    public static String readString(ByteBuffer buffer) {
        return readString(buffer, StandardCharsets.UTF_8);
    }
    
    /**
     * Write a boolean value as a single byte.
     * 
     * @param buffer the buffer to write to
     * @param value the boolean value
     */
    public static void writeBoolean(ByteBuffer buffer, boolean value) {
        buffer.put(value ? (byte) 1 : (byte) 0);
    }
    
    /**
     * Read a boolean value from a single byte.
     * 
     * @param buffer the buffer to read from
     * @return the boolean value
     */
    public static boolean readBoolean(ByteBuffer buffer) {
        return buffer.get() != 0;
    }
    
    /**
     * Calculate the size needed to serialize a string.
     * 
     * @param str the string
     * @param charset the charset to use
     * @return the number of bytes needed
     */
    public static int calculateStringSize(String str, Charset charset) {
        if (str == null || str.isEmpty()) {
            return SerializationConstants.SIZE_OF_INT;
        }
        return SerializationConstants.SIZE_OF_INT + str.getBytes(charset).length;
    }
    
    /**
     * Validate that a buffer contains valid flatbuffer data.
     * 
     * @param data the data to validate
     * @return true if valid, false otherwise
     */
    public static boolean validateBuffer(byte[] data) {
        if (data == null || data.length < SerializationConstants.HEADER_SIZE) {
            return false;
        }
        
        // Check magic header
        for (int i = 0; i < SerializationConstants.MAGIC_HEADER.length; i++) {
            if (data[i] != SerializationConstants.MAGIC_HEADER[i]) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Add flatbuffer header to the buffer.
     * 
     * @param buffer the buffer to add header to
     */
    public static void addHeader(ByteBuffer buffer) {
        if (buffer.position() < SerializationConstants.HEADER_SIZE) {
            throw new SerializationException("Buffer too small for header");
        }
        
        int originalPosition = buffer.position();
        buffer.position(0);
        
        // Write magic header
        buffer.put(SerializationConstants.MAGIC_HEADER);
        
        // Write version
        buffer.putShort((short) 1);
        
        // Write flags
        buffer.putShort((short) 0);
        
        buffer.position(originalPosition);
    }
    
    /**
     * Get the remaining bytes in the buffer.
     * 
     * @param buffer the buffer
     * @return number of remaining bytes
     */
    public static int getRemainingBytes(ByteBuffer buffer) {
        return buffer.remaining();
    }
    
    /**
     * Flip the buffer for reading.
     * 
     * @param buffer the buffer to flip
     * @return the flipped buffer
     */
    public static ByteBuffer flipForReading(ByteBuffer buffer) {
        return buffer.flip();
    }
    
    /**
     * Compact the buffer for writing.
     * 
     * @param buffer the buffer to compact
     * @return the compacted buffer
     */
    public static ByteBuffer compactForWriting(ByteBuffer buffer) {
        return buffer.compact();
    }
}