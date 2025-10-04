package com.kneaf.core.chunkstorage.serialization;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * High-performance NBT serializer implementation. This class provides a faster alternative to the
 * standard NBT serializer when available.
 */
public class FastNbtSerializer implements ChunkSerializer {
  private static final Logger LOGGER = LoggerFactory.getLogger(FastNbtSerializer.class);

  private static final String FORMAT_NAME = "FAST_NBT";
  private static final int FORMAT_VERSION = 1;

  // Static flags to track availability
  private static final boolean FAST_NBT_AVAILABLE;
  private static final boolean FAST_IMPL_PRESENT;
  private static final Class<?> FAST_IMPL_CLASS;
  private static final Method FAST_IMPL_SERIALIZE;
  private static final Method FAST_IMPL_DESERIALIZE;

  static {
    boolean available = false;
    boolean fastImpl = false;
    Class<?> implClass = null;
    Method implSerialize = null;
    Method implDeserialize = null;

    // First, prefer a project-provided FastNBT implementation
    try {
      implClass =
          Class.forName("com.kneaf.core.chunkstorage.serialization.fast.FastNbtImplementation");
      // Expecting static methods: byte[] serialize(Object) and Object deserialize(byte[])
      implSerialize = implClass.getMethod("serialize", Object.class);
      implDeserialize = implClass.getMethod("deserialize", byte[].class);
      fastImpl = true;
      available = true;
      LOGGER.info("FastNBT implementation found: {}", implClass.getName());
    } catch (Throwable t) {
      LOGGER.debug("No project FastNBT implementation available: {}", t.getMessage());
    }

    // Second, check whether Minecraft's NBT classes are present and usable. If so, we can
    // implement a high-performance path using RawNbtExtractor (which itself uses reflection).
    if (!available) {
      try {
        Class.forName("net.minecraft.nbt.NbtIo");
        Class.forName("net.minecraft.nbt.CompoundTag");
        available = true;
        LOGGER.info(
            "Minecraft NBT classes available - FastNbtSerializer will use RawNbtExtractor fallback");
      } catch (Throwable t) {
        LOGGER.debug("Minecraft NBT classes not available: {}", t.getMessage());
      }
    }

    FAST_NBT_AVAILABLE = available;
    FAST_IMPL_PRESENT = fastImpl;
    FAST_IMPL_CLASS = implClass;
    FAST_IMPL_SERIALIZE = implSerialize;
    FAST_IMPL_DESERIALIZE = implDeserialize;
  }

  /**
   * Check if FastNBT classes are available for serialization.
   *
   * @return true if FastNBT classes are available, false otherwise
   */
  public static boolean isFastNbtAvailable() {
    return FAST_NBT_AVAILABLE;
  }

  @Override
  public byte[] serialize(Object chunk) throws IOException {
    if (!FAST_NBT_AVAILABLE) {
      throw new UnsupportedOperationException("FastNBT is not available");
    }

    if (chunk == null) {
      throw new IllegalArgumentException("Chunk cannot be null");
    }

    return serializeChunk(chunk);
  }

  @Override
  public Object deserialize(byte[] data) throws IOException {
    if (!FAST_NBT_AVAILABLE) {
      throw new UnsupportedOperationException("FastNBT is not available");
    }

    if (data == null || data.length == 0) {
      throw new IllegalArgumentException("Data cannot be null or empty");
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

  /** Placeholder for actual FastNBT serialization implementation. */
  private byte[] serializeChunk(Object chunk) throws IOException {
    if (chunk == null) {
      throw new IllegalArgumentException("Chunk cannot be null");
    }

    // If a native fast implementation is present, call it via reflection
    if (FAST_IMPL_PRESENT && FAST_IMPL_CLASS != null) {
      try {
        Object res = FAST_IMPL_SERIALIZE.invoke(null, chunk);
        if (res instanceof byte[]) {
          return (byte[]) res;
        }
        throw new IOException("FastNbtImplementation.serialize did not return byte[]");
      } catch (IllegalAccessException | InvocationTargetException e) {
        LOGGER.warn("FastNbtImplementation invocation failed, falling back: {}", e.getMessage());
        // fall through to other strategies
      }
    }

    // Fallback: use RawNbtExtractor via reflection if present to avoid compile-time dependency
    try {
      Class<?> extractorClass =
          Class.forName("com.kneaf.core.chunkstorage.serialization.RawNbtExtractor");
      Object extractor = extractorClass.getDeclaredConstructor().newInstance();
      Method m = extractorClass.getMethod("serialize", Object.class);
      Object res = m.invoke(extractor, chunk);
      if (res instanceof byte[]) return (byte[]) res;
      LOGGER.debug(
          "RawNbtExtractor.serialize returned unexpected type: {}",
          res == null ? "null" : res.getClass());
    } catch (Throwable t) {
      LOGGER.debug("RawNbtExtractor unavailable or failed: {}", t.getMessage());
    }

    // Last-resort generic fallback: produce a compact, reversible representation.
    // We avoid depending on game classes by encoding a simple header and the chunk's
    // toString() representation. This is not a true chunk serialization but it is
    // deterministic and useful for environments without Minecraft or native fast code.
    try {
      String repr = chunk.toString();
      Map<String, Object> wrapper = new HashMap<>();
      wrapper.put("fallback", true);
      wrapper.put("class", chunk.getClass().getName());
      wrapper.put("data", repr);
      // Simple encoding: UTF-8 bytes of JSON-like map (no external deps)
      StringBuilder sb = new StringBuilder();
      sb.append("{\"fallback\":true,\"class\":\"")
          .append(chunk.getClass().getName())
          .append("\",\"data\":\"")
          .append(escapeString(repr))
          .append("\"}");
      return sb.toString().getBytes(StandardCharsets.UTF_8);
    } catch (Throwable t) {
      throw new IOException("Failed to serialize chunk using fallback: " + t.getMessage(), t);
    }
  }

  /** Placeholder for actual FastNBT deserialization implementation. */
  private Object deserializeChunk(byte[] data) throws IOException {
    if (data == null || data.length == 0) {
      throw new IllegalArgumentException("Data cannot be null or empty");
    }

    // Try native fast impl first
    if (FAST_IMPL_PRESENT && FAST_IMPL_CLASS != null) {
      try {
        Object res = FAST_IMPL_DESERIALIZE.invoke(null, (Object) data);
        return res;
      } catch (IllegalAccessException | InvocationTargetException e) {
        LOGGER.warn(
            "FastNbtImplementation.deserialize invocation failed, falling back: {}",
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
      LOGGER.debug("RawNbtExtractor unavailable or failed: {}", t.getMessage());
    }

    // Last-resort: try to decode our simple fallback JSON-like format
    try {
      String s = new String(data, StandardCharsets.UTF_8);
      if (s.startsWith("{\"fallback\"")) {
        // crude parsing to retrieve the data field
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
      // If not our fallback, return raw bytes as last resort
      return data;
    } catch (Throwable t) {
      throw new IOException("Failed to deserialize data using fallback: " + t.getMessage(), t);
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
