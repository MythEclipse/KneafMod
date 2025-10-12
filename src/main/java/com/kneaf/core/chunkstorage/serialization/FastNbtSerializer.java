package com.kneaf.core.chunkstorage.serialization;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;

/**
 * High-performance NBT serializer implementation. This class provides a faster alternative to the
 * standard NBT serializer when available.
 */
public class FastNbtSerializer implements ChunkSerializer {
  private static final Logger LOGGER = Logger.getLogger(FastNbtSerializer.class.getName());

  // Native bridge for FastNBT operations using Rust native library
  private static class NativeFastNbt {
    // Native method declarations for FastNBT serialization (matches Rust JNI bindings)
    public static native byte[] nativeSerialize(Object chunk);
    public static native Object nativeDeserialize(byte[] data);
    public static native ByteBuffer nativeSerializeDirect(Object chunk);
    public static native Object nativeDeserializeDirect(ByteBuffer data);
    
    // Track native library loading status
    private static boolean isLibraryLoaded = false;
    
    // Load native library with status tracking
    static {
      try {
        if (com.kneaf.core.performance.bridge.NativeLibraryLoader.loadNativeLibrary()) {
          isLibraryLoaded = true;
          LOGGER.info("FastNBT native library (rustperf) loaded successfully via NativeLibraryLoader");
        } else {
          isLibraryLoaded = false;
          LOGGER.log(Level.WARNING, "FastNBT native library not available via NativeLibraryLoader");
        }
      } catch (Throwable t) {
        isLibraryLoaded = false;
        LOGGER.log(Level.WARNING, "Failed to load FastNBT native library via NativeLibraryLoader: " + t.getMessage(), t);
      }
    }
    
    /** Check if native library was loaded successfully */
    public static boolean isLibraryLoaded() {
      return isLibraryLoaded;
    }
  }

  private static final String FORMAT_NAME = "FAST_NBT";
  private static final int FORMAT_VERSION = 1;

  // Static flags to track availability
  private static final boolean FAST_NBT_AVAILABLE;
  private static final boolean FAST_IMPL_PRESENT;
  private static final Class<?> FAST_IMPL_CLASS;
  private static final Method FAST_IMPL_SERIALIZE;
  private static final Method FAST_IMPL_DESERIALIZE;

  static {
    boolean available = NativeFastNbt.isLibraryLoaded();
    boolean fastImpl = false;
    Class<?> implClass = null;
    Method implSerialize = null;
    Method implDeserialize = null;

    FAST_NBT_AVAILABLE = available;
    FAST_IMPL_PRESENT = fastImpl;
    FAST_IMPL_CLASS = implClass;
    FAST_IMPL_SERIALIZE = implSerialize;
    FAST_IMPL_DESERIALIZE = implDeserialize;

    if (available) {
      LOGGER.info("FastNBT is available via native Rust implementation (rustperf library)");
    } else {
      LOGGER.warning("FastNBT is NOT available - native Rust library failed to load");
    }
  }

  /**
   * Check if native FastNBT implementation is available.
   *
   * @return true if native Rust library is loaded and ready for use
   */
  public static boolean isFastNbtAvailable() {
    return FAST_NBT_AVAILABLE;
  }

  @Override
  public byte[] serialize(Object chunk) throws IOException {
    if (!FAST_NBT_AVAILABLE) {
      throw new UnsupportedOperationException("FastNBT is not available - native Rust library not loaded");
    }

    if (chunk == null) {
      throw new IllegalArgumentException("Chunk cannot be null for FastNBT serialization");
    }

    return serializeChunk(chunk);
  }

  @Override
  public Object deserialize(byte[] data) throws IOException {
    if (!FAST_NBT_AVAILABLE) {
      throw new UnsupportedOperationException("FastNBT is not available - native Rust library not loaded");
    }

    if (data == null || data.length == 0) {
      throw new IllegalArgumentException("Data cannot be null or empty for FastNBT deserialization");
    }

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
   * Actual FastNBT serialization implementation using native Rust calls.
   * Prioritizes zero-copy direct buffer path, then buffered path, then last-resort fallback.
   */
  private byte[] serializeChunk(Object chunk) throws IOException {
    if (chunk == null) {
      throw new IllegalArgumentException("Chunk cannot be null for FastNBT serialization");
    }

    // Primary: Direct native serialization (zero-copy path)
    try {
      LOGGER.log(FINE, "Using direct native serialization for chunk: {0}", chunk.getClass().getName());
      ByteBuffer directBuffer = NativeFastNbt.nativeSerializeDirect(chunk);
      if (directBuffer == null) {
        throw new IOException("Native direct serialization returned null buffer");
      }
      return directBuffer.array();
    } catch (Throwable t) {
      LOGGER.log(Level.FINE, "Direct native serialization failed - falling back to buffered path: {0}", t.getMessage());
    }

    // Secondary: Buffered native serialization
    try {
      LOGGER.log(FINE, "Using buffered native serialization for chunk: {0}", chunk.getClass().getName());
      byte[] bufferedResult = NativeFastNbt.nativeSerialize(chunk);
      if (bufferedResult == null) {
        throw new IOException("Native buffered serialization returned null result");
      }
      return bufferedResult;
    } catch (Throwable t) {
      LOGGER.log(Level.WARNING, "All native serialization paths failed - using last-resort fallback: {0}", t.getMessage());
      
      // Last-resort: Generic text-based fallback (only for critical cases)
      return createGenericFallbackInternal(chunk);
    }
  }

  /**
   * Actual FastNBT deserialization implementation using native Rust calls.
   * Prioritizes zero-copy direct buffer path, then buffered path, then last-resort fallback.
   */
  private byte[] createGenericFallbackInternal(Object chunk) throws IOException {
    String repr = chunk.toString();
    Map<String, Object> wrapper = new HashMap<>();
    wrapper.put("fallback", true);
    wrapper.put("class", chunk.getClass().getName());
    wrapper.put("data", repr);
    
    StringBuilder sb = new StringBuilder();
    sb.append("{\"fallback\":true,\"class\":\"")
        .append(chunk.getClass().getName())
        .append("\",\"data\":\"")
        .append(escapeString(repr))
        .append("\"}");
    
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  /** Internal helper for generic fallback deserialization */
  private Object decodeGenericFallbackInternal(byte[] data) throws IOException {
    try {
      String s = new String(data, StandardCharsets.UTF_8);
      if (s.startsWith("{\"fallback\"")) {
        int idx = s.indexOf("\",\"data\":\"");
        if (idx >= 0) {
          int start = idx + "\",\"data\":\"".length();
          int end = s.lastIndexOf('\"');
          if (end > start) {
            String payload = unescapeString(s.substring(start, end));
            Map<String, String> map = new HashMap<>();
            map.put("payload", payload);
            return map;
          }
        }
      }
      // If not our fallback format, return raw bytes
      return data;
    } catch (Throwable t) {
      throw new IOException("Failed to decode generic fallback data: " + t.getMessage(), t);
    }
  }

  private Object deserializeChunk(byte[] data) throws IOException {
    if (data == null || data.length == 0) {
      throw new IllegalArgumentException("Data cannot be null or empty for FastNBT deserialization");
    }

    // Try native fast impl first
    if (FAST_IMPL_PRESENT && FAST_IMPL_CLASS != null) {
      try {
        Object res = FAST_IMPL_DESERIALIZE.invoke(null, (Object) data);
        return res;
      } catch (IllegalAccessException | InvocationTargetException e) {
        LOGGER.log(Level.WARNING,
            "FastNbtImplementation.deserialize invocation failed, falling back: {0}",
            e.getMessage());
        // fall through
      }
    }

    // Fallback to RawNbtExtractor via reflection if available
    try {
      Class<?> extractorClass =
          Class.forName("com.kneaf.core.chunkstorage.serialization.RawNbtExtractor");
      Object extractor = extractorClass.getDeclaredConstructor().newInstance();
      Method m = extractorClass.getMethod("deserialize", byte[].class);
      return m.invoke(extractor, (Object) data);
    } catch (Throwable t) {
      LOGGER.log(Level.FINE, "RawNbtExtractor unavailable or failed: {0}", t.getMessage());
    }

    // Try direct native deserialization first (zero-copy path)
    try {
      LOGGER.log(FINE, "Using direct native deserialization for data of length: {0}", data.length);
      ByteBuffer buffer = ByteBuffer.wrap(data);
      return NativeFastNbt.nativeDeserializeDirect(buffer);
    } catch (Throwable t) {
      LOGGER.log(Level.FINE, "Direct native deserialization failed, falling back to buffered path: {0}", t.getMessage());
    }

    // Try buffered native deserialization
    try {
      LOGGER.log(FINE, "Using buffered native deserialization");
      return NativeFastNbt.nativeDeserialize(data);
    } catch (Throwable t) {
      LOGGER.log(Level.WARNING, "Native deserialization failed, falling back to generic path: {0}", t.getMessage());
      
      // Last-resort: Try to decode our simple fallback JSON-like format
      return decodeGenericFallbackInternal(data);
    }
  }

  private static String escapeString(String s) {
    if (s == null) return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }

  private static String unescapeString(String s) {
    if (s == null) return "";
    return s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
  }
}
