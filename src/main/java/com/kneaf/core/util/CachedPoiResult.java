/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.util;

import net.minecraft.core.BlockPos;
import java.util.Optional;

/**
 * Helper class for PoiManagerMixin result caching.
 * Extracted to avoid IllegalClassLoadError in Mixins.
 */
public class CachedPoiResult {
    public final Optional<BlockPos> result;
    public final long timestamp;

    public CachedPoiResult(Optional<BlockPos> result, long timestamp) {
        this.result = result;
        this.timestamp = timestamp;
    }
}
