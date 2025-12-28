/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.util;

import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ExplosionControl - Dynamic budget manager for explosions to prevent TPS lag.
 * Automatically adjusts budget based on current server TPS.
 */
public final class ExplosionControl {
    private static final Logger LOGGER = LoggerFactory.getLogger("KneafMod/ExplosionControl");

    // Dynamic budget range
    private static final int MIN_EXPLOSIONS_PER_TICK = 4;
    private static final int MAX_EXPLOSIONS_PER_TICK = 32;
    private static final int DEFAULT_EXPLOSIONS_PER_TICK = 8;

    // Current dynamic budget
    private static volatile int currentBudget = DEFAULT_EXPLOSIONS_PER_TICK;

    private static final AtomicInteger explosionCount = new AtomicInteger(0);
    private static long lastTickTime = -1;
    private static boolean loggedThrottle = false;

    // Budget adjustment tracking
    private static long lastBudgetAdjustTime = 0;
    private static final long BUDGET_ADJUST_INTERVAL = 8;

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

            // Dynamically adjust budget based on TPS
            adjustBudget(gameTime);
        }

        if (explosionCount.get() >= currentBudget) {
            if (!loggedThrottle) {
                LOGGER.debug("Explosion budget full ({}/{}) - Queueing remaining TNT clusters...",
                        explosionCount.get(), currentBudget);
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
            adjustBudget(gameTime);
        }
        explosionCount.incrementAndGet();
    }

    /**
     * Dynamically adjust the explosion budget based on current TPS.
     */
    private static void adjustBudget(long gameTime) {
        // Only adjust every BUDGET_ADJUST_INTERVAL ticks
        if (gameTime - lastBudgetAdjustTime < BUDGET_ADJUST_INTERVAL) {
            return;
        }
        lastBudgetAdjustTime = gameTime;

        double tps = TPSTracker.getCurrentTPS();
        int oldBudget = currentBudget;

        // Adaptive budget based on TPS
        if (tps >= 19.5) {
            // Excellent TPS - increase budget
            currentBudget = Math.min(MAX_EXPLOSIONS_PER_TICK, currentBudget + 2);
        } else if (tps >= 18.0) {
            // Good TPS - slight increase
            currentBudget = Math.min(MAX_EXPLOSIONS_PER_TICK, currentBudget + 1);
        } else if (tps < 15.0) {
            // Poor TPS - decrease budget aggressively
            currentBudget = Math.max(MIN_EXPLOSIONS_PER_TICK, currentBudget - 2);
        } else if (tps < 17.0) {
            // Below target - slight decrease
            currentBudget = Math.max(MIN_EXPLOSIONS_PER_TICK, currentBudget - 1);
        }
        // TPS 17-18: maintain current budget

        if (currentBudget != oldBudget) {
            LOGGER.info("Dynamic explosion budget adjusted: {} → {} (TPS: {:.1f})",
                    oldBudget, currentBudget, tps);
        }
    }

    /**
     * Get the current explosion count for the current tick.
     */
    public static int getExplosionCount() {
        return explosionCount.get();
    }

    /**
     * Get the current dynamic budget.
     */
    public static int getCurrentBudget() {
        return currentBudget;
    }
}
