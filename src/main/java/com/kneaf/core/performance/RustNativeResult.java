package com.kneaf.core.performance;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class RustNativeResult {
  private RustNativeResult() {}

  public static JsonObject parseOrThrow(String json) {
    if (json == null) throw new RustPerformanceException("native returned null");
    JsonElement el = JsonParser.parseString(json);
    if (!el.isJsonObject()) return el.getAsJsonObject();
    JsonObject obj = el.getAsJsonObject();
    if (obj.has("error")) {
      String err = obj.get("error").getAsString();
      throw new RustPerformanceException(err);
    }
    return obj;
  }
}
