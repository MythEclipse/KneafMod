package com.kneaf.core.performance.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Utility methods for performance processing operations. */
public final class PerformanceUtils {

  private static final Gson GSON = new Gson();

  private PerformanceUtils() {} // Prevent instantiation

  /** Convert byte array to hex string for debugging. */
  public static String bytesToHex(byte[] data, int maxBytes) {
    if (data == null) return "";
    int len = Math.min(data.length, Math.max(0, maxBytes));
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < len; i++) {
      sb.append(String.format("%02x", data[i] & 0xff));
      if (i < len - 1) sb.append(',');
    }
    if (data.length > len) sb.append("...");
    return sb.toString();
  }

  /** Parse entity result from byte array response. */
  public static List<Long> parseEntityResultFromBytes(byte[] resultBytes) {
    // Use binary parser directly
    return parseEntityResultWithFallback(resultBytes);
  }
  
  /** Parse entity result from JSON response (deprecated - use byte array version). */
  public static List<Long> parseEntityResultFromJson(String jsonResult) {
    // Convert string to bytes and use binary parser
    if (jsonResult == null) return new ArrayList<>();
    return parseEntityResultWithFallback(jsonResult.getBytes());
  }

  /** Parse item result from JSON response. */
  public static ItemParseResult parseItemResultFromJson(String jsonResult) {
    try {
      JsonObject result = GSON.fromJson(jsonResult, JsonObject.class);
      JsonArray itemsToRemove = result.getAsJsonArray("items_to_remove");
      List<Long> removeList = new ArrayList<>();
      for (JsonElement e : itemsToRemove) {
        removeList.add(e.getAsLong());
      }
      long merged = result.get("merged_count").getAsLong();
      long despawned = result.get("despawned_count").getAsLong();
      JsonArray itemUpdatesArray = result.getAsJsonArray("item_updates");
      List<ItemUpdateParseResult> updates = new ArrayList<>();
      for (JsonElement e : itemUpdatesArray) {
        JsonObject obj = e.getAsJsonObject();
        long id = obj.get("id").getAsLong();
        int newCount = obj.get("new_count").getAsInt();
        updates.add(new ItemUpdateParseResult(id, newCount));
      }
      return new ItemParseResult(removeList, merged, despawned, updates);
    } catch (Exception e) {
      return new ItemParseResult(new ArrayList<>(), 0, 0, new ArrayList<>());
    }
  }

  /** Parse mob result from byte array response. */
  public static MobParseResult parseMobResultFromBytes(byte[] resultBytes) {
    // Parse binary format: [disable_count:i32][disable_ids...][simplify_count:i32][simplify_ids...]
    if (resultBytes == null) return new MobParseResult(new ArrayList<>(), new ArrayList<>());
    
    try {
      ByteBuffer buffer = ByteBuffer.wrap(resultBytes).order(ByteOrder.LITTLE_ENDIAN);
      
      if (buffer.remaining() < 8) { // At least 2 counts (4 bytes each)
        return new MobParseResult(new ArrayList<>(), new ArrayList<>());
      }
      
      // Read disable count and IDs
      int disableCount = buffer.getInt();
      List<Long> disableList = new ArrayList<>();
      if (disableCount >= 0 && disableCount <= 10000 && buffer.remaining() >= disableCount * 8) {
        for (int i = 0; i < disableCount; i++) {
          disableList.add(buffer.getLong());
        }
      }
      
      // Read simplify count and IDs
      int simplifyCount = buffer.getInt();
      List<Long> simplifyList = new ArrayList<>();
      if (simplifyCount >= 0 && simplifyCount <= 10000 && buffer.remaining() >= simplifyCount * 8) {
        for (int i = 0; i < simplifyCount; i++) {
          simplifyList.add(buffer.getLong());
        }
      }
      
      return new MobParseResult(disableList, simplifyList);
    } catch (Exception e) {
      return new MobParseResult(new ArrayList<>(), new ArrayList<>());
    }
  }
  
  /** Parse mob result from JSON response (deprecated - use byte array version). */
  public static MobParseResult parseMobResultFromJson(String jsonResult) {
    // Convert string to bytes and use binary parser
    if (jsonResult == null) return new MobParseResult(new ArrayList<>(), new ArrayList<>());
    return parseMobResultFromBytes(jsonResult.getBytes());
  }

  /** Parse block result from byte array response. */
  public static List<Long> parseBlockResultFromBytes(byte[] resultBytes) {
    // Parse binary format: [count:i32][ids...]
    if (resultBytes == null) return new ArrayList<>();
    
    try {
      ByteBuffer buffer = ByteBuffer.wrap(resultBytes).order(ByteOrder.LITTLE_ENDIAN);
      
      if (buffer.remaining() < 4) { // At least count field
        return new ArrayList<>();
      }
      
      int count = buffer.getInt();
      if (count < 0 || count > 10000 || buffer.remaining() < count * 8) {
        return new ArrayList<>();
      }
      
      List<Long> resultList = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        resultList.add(buffer.getLong());
      }
      return resultList;
    } catch (Exception e) {
      return new ArrayList<>();
    }
  }
  
  /** Parse block result from JSON response (deprecated - use byte array version). */
  public static List<Long> parseBlockResultFromJson(String jsonResult) {
    // Convert string to bytes and use binary parser
    if (jsonResult == null) return new ArrayList<>();
    return parseBlockResultFromBytes(jsonResult.getBytes());
  }

  /** Try to parse entity result with fallback formats. */
  public static List<Long> parseEntityResultWithFallback(byte[] resultBytes) {
    if (resultBytes == null) return new ArrayList<>();

    try {
      // Try the preferred entity-format first: [len:i32][ids...]
      ByteBuffer resultBuffer = ByteBuffer.wrap(resultBytes).order(ByteOrder.LITTLE_ENDIAN);
      if (resultBuffer.remaining() >= 4) {
        int numItems = resultBuffer.getInt();
        if (numItems >= 0 && numItems <= 1_000_000 && resultBuffer.remaining() >= numItems * 8) {
          List<Long> resultList = new ArrayList<>(numItems);
          for (int i = 0; i < numItems; i++) {
            resultList.add(resultBuffer.getLong());
          }
          return resultList;
        }
      }
    } catch (Exception primaryEx) {
      // Try fallback format: [tickCount:u64][num:i32][ids...]
      try {
        ByteBuffer altBuf = ByteBuffer.wrap(resultBytes).order(ByteOrder.LITTLE_ENDIAN);
        int len = resultBytes.length;
        if (len >= 12) {
          altBuf.getLong(0);
          int numItems = altBuf.getInt(8);
          // Sanity checks
          if (numItems >= 0 && numItems <= 1_000_000 && 12 + numItems * 8 <= len) {
            List<Long> altList = new ArrayList<>(numItems);
            for (int i = 0; i < numItems; i++) {
              long id = altBuf.getLong(12 + i * 8);
              altList.add(id);
            }
            return altList;
          }
        }
      } catch (Exception altEx) {
        // Both formats failed
      }
    }

    return new ArrayList<>();
  }

  /** Result class for item parsing. */
  public static class ItemParseResult {
    private final List<Long> itemsToRemove;
    private final long mergedCount;
    private final long despawnedCount;
    private final List<ItemUpdateParseResult> itemUpdates;

    public ItemParseResult(
        List<Long> itemsToRemove,
        long mergedCount,
        long despawnedCount,
        List<ItemUpdateParseResult> itemUpdates) {
      this.itemsToRemove = itemsToRemove;
      this.mergedCount = mergedCount;
      this.despawnedCount = despawnedCount;
      this.itemUpdates = itemUpdates;
    }

    public List<Long> getItemsToRemove() {
      return itemsToRemove;
    }

    public long getMergedCount() {
      return mergedCount;
    }

    public long getDespawnedCount() {
      return despawnedCount;
    }

    public List<ItemUpdateParseResult> getItemUpdates() {
      return itemUpdates;
    }
  }

  /** Result class for item update parsing. */
  public static class ItemUpdateParseResult {
    private final long id;
    private final int newCount;

    public ItemUpdateParseResult(long id, int newCount) {
      this.id = id;
      this.newCount = newCount;
    }

    public long getId() {
      return id;
    }

    public int getNewCount() {
      return newCount;
    }
  }

  /** Result class for mob parsing. */
  public static class MobParseResult {
    private final List<Long> disableList;
    private final List<Long> simplifyList;

    public MobParseResult(List<Long> disableList, List<Long> simplifyList) {
      this.disableList = disableList;
      this.simplifyList = simplifyList;
    }

    public List<Long> getDisableList() {
      return disableList;
    }

    public List<Long> getSimplifyList() {
      return simplifyList;
    }
  }
}
