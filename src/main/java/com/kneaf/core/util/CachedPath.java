/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.util;

import net.minecraft.world.level.pathfinder.Path;

/**
 * Cache entry for pathfinding optimization.
 */
public record CachedPath(Path path, long timestamp) {
}
