package com.kneaf.core.protocol;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Legacy protocol processor for unified binary and JSON processing with fallback mechanisms.
 *
 * @deprecated Use EnhancedProtocolProcessor instead
 */
@Deprecated
public class ProtocolProcessor {

  /** Result wrapper for protocol operations. */
  public static class ProtocolResult<T> {
    private final T data;
    private final Exception error;
    private final boolean success;

    public ProtocolResult(T data, Exception error, boolean success) {
      this.data = data;
      this.error = error;
      this.success = success;
    }

    public T getDataOrThrow() throws Exception {
      if (!success && error != null) {
        throw error;
      }
      return data;
    }

    public T getData() {
      return data;
    }

    public Exception getError() {
      return error;
    }

    public boolean isSuccess() {
      return success;
    }
  }

  /** Interface for binary serialization. */
  public interface BinarySerializer<T> {
    ByteBuffer serialize(T input) throws Exception;
  }

  /** Interface for binary native calling. */
  public interface BinaryNativeCaller<R> {
    R callNative(ByteBuffer input) throws Exception;
  }

  /** Interface for binary deserialization. */
  public interface BinaryDeserializer<T> {
    T deserialize(byte[] resultBytes) throws Exception;
  }

  /** Interface for JSON input preparation. */
  public interface JsonInputPreparer<T> {
    Map<String, Object> prepareInput(T input);
  }

  /** Interface for JSON native calling. */
  public interface JsonNativeCaller<R> {
    R callNative(String jsonInput) throws Exception;
  }

  /** Interface for JSON result parsing. */
  public interface JsonResultParser<T> {
    T parseResult(String jsonResult) throws Exception;

    /** Create auto-configured protocol processor. */
    public static ProtocolProcessor createAuto(boolean nativeAvailable) {
      return new ProtocolProcessor();
    }
  }

  /** Process data with unified binary/JSON fallback mechanism. */
  public <T, R> ProtocolResult<R> processWithFallback(
      T input,
      String operationName,
      BinarySerializer<T> binarySerializer,
      BinaryNativeCaller<byte[]> binaryCaller,
      BinaryDeserializer<R> binaryDeserializer,
      JsonInputPreparer<T> jsonInputPreparer,
      JsonNativeCaller<String> jsonCaller,
      JsonResultParser<R> jsonResultParser,
      R fallbackResult) {

    try {
      // Try binary protocol first
      ByteBuffer binaryInput = binarySerializer.serialize(input);
      byte[] binaryResult = binaryCaller.callNative(binaryInput);

      if (binaryResult != null && binaryResult.length > 0) {
        R result = binaryDeserializer.deserialize(binaryResult);
        return new ProtocolResult<>(result, null, true);
      }
    } catch (Exception e) {
      // Binary failed, try JSON fallback
    }

    try {
      // Try JSON protocol as fallback
      Map<String, Object> jsonInput = jsonInputPreparer.prepareInput(input);
      String jsonInputStr = new com.google.gson.Gson().toJson(jsonInput);
      String jsonResult = jsonCaller.callNative(jsonInputStr);

      if (jsonResult != null && !jsonResult.isEmpty()) {
        R result = jsonResultParser.parseResult(jsonResult);
        return new ProtocolResult<>(result, null, true);
      }
    } catch (Exception e) {
      // JSON also failed, return fallback
    }

    // Return fallback result
    return new ProtocolResult<>(fallbackResult, null, true);
  }
}
