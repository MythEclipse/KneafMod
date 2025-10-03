package com.kneaf.core.flatbuffers.core;

/**
 * Constants used throughout the flatbuffers serialization system.
 * These constants define buffer sizes, schema versions, and other configuration values.
 */
public final class SerializationConstants {
    
    private SerializationConstants() {
        // Private constructor to prevent instantiation
    }
    
    // Buffer sizes
    public static final int DEFAULT_BUFFER_SIZE = 1024;
    public static final int MAX_BUFFER_SIZE = 16 * 1024 * 1024; // 16MB
    public static final int BUFFER_GROWTH_FACTOR = 2;
    
    // Schema versions
    public static final String SCHEMA_VERSION_1_0 = "1.0.0";
    public static final String CURRENT_SCHEMA_VERSION = SCHEMA_VERSION_1_0;
    
    // Binary format constants (matching Rust implementation)
    public static final byte LITTLE_ENDIAN = 0;
    public static final byte BIG_ENDIAN = 1;
    public static final byte CURRENT_ENDIANNESS = LITTLE_ENDIAN;
    
    // Data type sizes
    public static final int SIZE_OF_BYTE = 1;
    public static final int SIZE_OF_SHORT = 2;
    public static final int SIZE_OF_INT = 4;
    public static final int SIZE_OF_LONG = 8;
    public static final int SIZE_OF_FLOAT = 4;
    public static final int SIZE_OF_DOUBLE = 8;
    
    // String encoding
    public static final String DEFAULT_CHARSET = "UTF-8";
    
    // Serialization headers
    public static final byte[] MAGIC_HEADER = {0x4B, 0x46, 0x42, 0x53}; // "KFBS" (Kneaf FlatBuffer Serialization)
    public static final int HEADER_SIZE = 8; // Magic (4) + Version (2) + Flags (2)
    
    // Error codes
    public static final int ERROR_INVALID_FORMAT = 1001;
    public static final int ERROR_BUFFER_OVERFLOW = 1002;
    public static final int ERROR_SCHEMA_MISMATCH = 1003;
    public static final int ERROR_DESERIALIZATION_FAILED = 1004;
    public static final int ERROR_SERIALIZATION_FAILED = 1005;
    
    // Performance tuning
    public static final int DEFAULT_POOL_SIZE = 16;
    public static final int MAX_POOL_SIZE = 64;
    public static final long BUFFER_TIMEOUT_MS = 30000; // 30 seconds
    
    // Native integration constants
    public static final int NATIVE_BUFFER_ALIGNMENT = 8;
    public static final int NATIVE_CACHE_LINE_SIZE = 64;
    
    // Entity type identifiers
    public static final String ENTITY_TYPE_MOB = "mob";
    public static final String ENTITY_TYPE_ITEM = "item";
    public static final String ENTITY_TYPE_BLOCK = "block";
    public static final String ENTITY_TYPE_VILLAGER = "villager";
    public static final String ENTITY_TYPE_PLAYER = "player";
    
    // Processing constants
    public static final int BATCH_SIZE_THRESHOLD = 25;
    public static final int MAX_STRING_LENGTH = 65536;
    
    // Validation constants
    public static final int MIN_ENTITY_COUNT = 0;
    public static final int MAX_ENTITY_COUNT = 100000;
    public static final float MIN_DISTANCE = 0.0f;
    public static final float MAX_DISTANCE = 10000.0f;
}