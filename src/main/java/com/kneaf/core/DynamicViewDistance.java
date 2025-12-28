/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Dynamic View Distance - Automatically adjusts view distance based on TPS.
 */
package com.kneaf.core;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kneaf.core.util.TPSTracker;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * DynamicViewDistance - Automatically adjusts server view distance based on
 * TPS.
 * 
 * When TPS drops below threshold, view distance is reduced to improve
 * performance.
 * When TPS recovers, view distance is gradually restored.
 */
@EventBusSubscriber(modid = "kneafcore")
public class DynamicViewDistance {

    private static final Logger LOGGER = LoggerFactory.getLogger("KneafMod/DynamicViewDistance");

    // Configuration
    private static boolean enabled = true;
    private static int minViewDistance = 6;
    private static int maxViewDistance = 16;
    private static double tpsThresholdLow = 15.0;
    private static double tpsThresholdRecover = 18.0;
    private static int adjustmentCooldownTicks = 200; // 10 seconds

    // State
    private static final AtomicInteger currentViewDistance = new AtomicInteger(-1);
    private static int cooldownTicks = 0;
    private static boolean initialized = false;
    private static int tickCounter = 0;

    /**
     * Handle server tick for dynamic adjustment.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!enabled)
            return;

        tickCounter++;

        // Only check every 20 ticks (1 second)
        if (tickCounter % 20 != 0)
            return;

        // Cooldown check
        if (cooldownTicks > 0) {
            cooldownTicks -= 20;
            return;
        }

        MinecraftServer server = event.getServer();
        if (server == null)
            return;

        // Initialize on first tick
        if (!initialized) {
            ServerLevel level = server.overworld();
            if (level != null) {
                currentViewDistance.set(server.getPlayerList().getViewDistance());
                LOGGER.info("✅ DynamicViewDistance initialized - current: {}, range: {}-{}",
                        currentViewDistance.get(), minViewDistance, maxViewDistance);
                initialized = true;
            }
            return;
        }

        double currentTPS = TPSTracker.getCurrentTPS();
        int currentDist = currentViewDistance.get();

        // Reduce view distance if TPS is low
        if (currentTPS < tpsThresholdLow && currentDist > minViewDistance) {
            int newDistance = Math.max(minViewDistance, currentDist - 1);
            setViewDistance(server, newDistance);
            LOGGER.info("DynamicViewDistance: Reduced {} -> {} (TPS: {:.1f})",
                    currentDist, newDistance, currentTPS);
            cooldownTicks = adjustmentCooldownTicks;
        }
        // Increase view distance if TPS has recovered
        else if (currentTPS > tpsThresholdRecover && currentDist < maxViewDistance) {
            int newDistance = Math.min(maxViewDistance, currentDist + 1);
            setViewDistance(server, newDistance);
            LOGGER.info("DynamicViewDistance: Increased {} -> {} (TPS: {:.1f})",
                    currentDist, newDistance, currentTPS);
            cooldownTicks = adjustmentCooldownTicks;
        }
    }

    /**
     * Set server view distance.
     */
    private static void setViewDistance(MinecraftServer server, int distance) {
        try {
            server.getPlayerList().setViewDistance(distance);
            currentViewDistance.set(distance);
        } catch (Exception e) {
            LOGGER.error("Failed to set view distance: {}", e.getMessage());
        }
    }

    // Configuration methods
    public static void setEnabled(boolean value) {
        enabled = value;
        LOGGER.info("DynamicViewDistance enabled: {}", enabled);
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setMinViewDistance(int value) {
        minViewDistance = Math.max(2, Math.min(value, 32));
    }

    public static void setMaxViewDistance(int value) {
        maxViewDistance = Math.max(minViewDistance, Math.min(value, 32));
    }

    public static void setTpsThresholdLow(double value) {
        tpsThresholdLow = Math.max(5.0, Math.min(value, 20.0));
    }

    public static void setTpsThresholdRecover(double value) {
        tpsThresholdRecover = Math.max(tpsThresholdLow, Math.min(value, 20.0));
    }

    public static int getCurrentViewDistance() {
        return currentViewDistance.get();
    }

    public static String getStatistics() {
        return String.format("DynamicViewDistance{enabled=%b, current=%d, range=%d-%d, tps=%.1f}",
                enabled, currentViewDistance.get(), minViewDistance, maxViewDistance,
                TPSTracker.getCurrentTPS());
    }
}
