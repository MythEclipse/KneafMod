package com.kneaf.core.performance;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kneaf.core.exceptions.nativelib.NativeLibraryException;

public final class RustNativeResult {
  private RustNativeResult() {}

  public static JsonObject parseOrThrow(String json) {
    if (json == null) throw NativeLibraryException.builder()
        .message("native returned null")
        .errorType(NativeLibraryException.NativeErrorType.NATIVE_CALL_FAILED)
        .libraryName("rustperf")
        .nativeMethod("parseOrThrow")
        .build();
    JsonElement el = JsonParser.parseString(json);
    if (!el.isJsonObject()) return el.getAsJsonObject();
    JsonObject obj = el.getAsJsonObject();
    if (obj.has("error")) {
      String err = obj.get("error").getAsString();
      throw NativeLibraryException.builder()
          .message(err)
          .errorType(NativeLibraryException.NativeErrorType.NATIVE_CALL_FAILED)
          .libraryName("rustperf")
          .nativeMethod("parseOrThrow")
          .build();
    }
    return obj;
  }
}
