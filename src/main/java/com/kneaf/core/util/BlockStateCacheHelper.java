/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.util;

import java.util.Arrays;

/**
 * Helper class for BlockStateCacheMixin.
 * Extracted to avoid IllegalClassLoadError when referenced from Mixin.
 */
public class BlockStateCacheHelper {
    // Use parallel arrays for value and presence
    public boolean[] isAirValues = new boolean[4096];
    public boolean[] isAirPresent = new boolean[4096];

    public void ensureCapacity(int id) {
        if (id >= isAirValues.length) {
            int newSize = Math.max(id + 1024, isAirValues.length * 2);
            isAirValues = Arrays.copyOf(isAirValues, newSize);
            isAirPresent = Arrays.copyOf(isAirPresent, newSize);
        }
    }

    public void clear() {
        Arrays.fill(isAirPresent, false);
    }
}
