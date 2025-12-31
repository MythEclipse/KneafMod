/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.util;

import net.minecraft.world.phys.Vec3;
import java.util.HashMap;
import java.util.Map;

public class ExplosionControl {

    private static final ThreadLocal<Map<ExposureKey, Float>> EXPOSURE_CACHE = ThreadLocal.withInitial(HashMap::new);
    private static long lastExplosionTime = 0;

    public static void notifyExploded(long time) {
        lastExplosionTime = time;
    }

    public static Float getCachedExposure(Vec3 source, int entityId, int bbHash) {
        return EXPOSURE_CACHE.get().get(new ExposureKey(source, entityId, bbHash));
    }

    public static void cacheExposure(Vec3 source, int entityId, int bbHash, float value) {
        EXPOSURE_CACHE.get().put(new ExposureKey(source, entityId, bbHash), value);
    }

    public static void clearExposureCache() {
        EXPOSURE_CACHE.get().clear();
    }

    /**
     * Budget system for explosions.
     * Prevents more than 100 explosions per tick.
     */
    private static long currentTick = -1;
    private static int explosionsThisTick = 0;

    public static boolean tryExplode(long time) {
        if (time != currentTick) {
            currentTick = time;
            explosionsThisTick = 0;
        }
        if (explosionsThisTick >= 100) {
            return false;
        }
        explosionsThisTick++;
        return true;
    }

    private record ExposureKey(Vec3 source, int entityId, int bbHash) {
    }
}
