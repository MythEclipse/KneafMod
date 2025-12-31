/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.util;

import com.kneaf.core.util.PrimitiveMaps.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;

/**
 * SlotCache - Caches the first non-empty slot for inventories to speed up
 * hopper extraction.
 */
public final class SlotCache {
    private static final Long2IntOpenHashMap CACHE = new Long2IntOpenHashMap(1024);

    public static int getFirstNonEmptySlot(BlockPos pos) {
        return CACHE.get(pos.asLong());
    }

    public static void setFirstNonEmptySlot(BlockPos pos, int slot) {
        CACHE.put(pos.asLong(), slot);
    }

    public static void invalidate(BlockPos pos) {
        CACHE.remove(pos.asLong());
    }
}
