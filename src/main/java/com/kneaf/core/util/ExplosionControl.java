/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.util;

import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ExplosionControl - Global budget manager for explosions to prevent TPS lag.
 * Spreads explosion processing over multiple ticks to maintain stable TPS.
 */
public final class ExplosionControl {
    private static final Logger LOGGER = LoggerFactory.getLogger("KneafMod/ExplosionControl");

    // Limits the number of TNT clusters that can trigger per tick.
    // 8 per tick = 160 per second. This provides even more headroom for TPS.
    private static final int MAX_EXPLOSIONS_PER_TICK = 8;

    private static final AtomicInteger explosionCount = new AtomicInteger(0);
    private static long lastTickTime = -1;
    private static boolean loggedThrottle = false;

    /**
     * Try to acquire permission for an explosion to occur this tick.
     * 
     * @param gameTime The current level game time
     * @return true if explosion is allowed, false if budget full
     */
    public static boolean tryExplode(long gameTime) {
        if (gameTime != lastTickTime) {
            explosionCount.set(0);
            lastTickTime = gameTime;
            loggedThrottle = false;
        }

        // We check before incrementing to avoid unnecessary increments when full
        if (explosionCount.get() >= MAX_EXPLOSIONS_PER_TICK) {
            if (!loggedThrottle) {
                LOGGER.debug("Explosion budget full - Queueing remaining TNT clusters...");
                loggedThrottle = true;
            }
            return false;
        }

        explosionCount.incrementAndGet();
        return true;
    }

    /**
     * Notify that an explosion has occurred (e.g. from non-throttleable sources).
     * 
     * @param gameTime Current game time
     */
    public static void notifyExploded(long gameTime) {
        if (gameTime != lastTickTime) {
            explosionCount.set(0);
            lastTickTime = gameTime;
        }
        explosionCount.incrementAndGet();
    }

    /**
     * Get the current explosion count for the current tick.
     */
    public static int getExplosionCount() {
        return explosionCount.get();
    }
}
