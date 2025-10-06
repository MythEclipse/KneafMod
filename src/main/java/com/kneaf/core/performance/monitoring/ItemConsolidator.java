package com.kneaf.core.performance.monitoring;

import com.kneaf.core.data.item.ItemEntityData;
import java.util.*;

/**
 * Handles item entity consolidation and optimization logic.
 * Separated from main PerformanceManager for better modularity.
 */
public class ItemConsolidator {
  public ItemConsolidator() {}

  /**
   * Consolidate collected item entity data by chunk X/Z and item type to reduce the number of items
   * we hand to the downstream Rust processing. This only aggregates the collected snapshot and does
   * not modify world state directly, so it is safe and purely an optimization.
   */
  public List<ItemEntityData> consolidateItemEntities(List<ItemEntityData> items) {
    if (items == null || items.isEmpty()) return items;

    int estimatedSize = Math.min(items.size(), items.size() / 2 + 1);
    Map<Long, ItemEntityData> agg = new HashMap<>(estimatedSize);

    // Pre-calculate hash codes to avoid repeated calls
    for (ItemEntityData it : items) {
      long key = packItemKey(it.getChunkX(), it.getChunkZ(), it.getItemType());

      ItemEntityData cur = agg.get(key);
      if (cur == null) {
        agg.put(key, it);
      } else {
        // Sum counts and keep smallest age to represent the merged group
        int newCount = cur.getCount() + it.getCount();
        int newAge = Math.min(cur.getAgeSeconds(), it.getAgeSeconds());
        // Preserve a valid entity id from one of the merged items (use the existing entry's id)
        long preservedId = cur.getId();
        agg.put(
            key,
            new ItemEntityData(
                preservedId, it.getChunkX(), it.getChunkZ(), it.getItemType(), newCount, newAge));
      }
    }

    // Pre-size the result list to avoid resizing
    return new ArrayList<>(agg.values());
  }

  /**
   * Pack chunk coordinates and item type hash into a composite long key. Format: [chunkX (21
   * bits)][chunkZ (21 bits)][itemTypeHash (22 bits)] This provides efficient HashMap operations
   * without string allocations.
   */
  private long packItemKey(int chunkX, int chunkZ, String itemType) {
    // Use 21 bits for each coordinate (covers ±1 million chunks) and 22 bits for hash
    long packedChunkX = ((long) chunkX) & 0x1FFFFF; // 21 bits
    long packedChunkZ = ((long) chunkZ) & 0x1FFFFF; // 21 bits
    long itemHash = itemType == null ? 0 : ((long) itemType.hashCode()) & 0x3FFFFF; // 22 bits

    return (packedChunkX << 43) | (packedChunkZ << 22) | itemHash;
  }
}