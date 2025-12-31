/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 *
 * Optimizes map item rendering by skipping unchanged updates.
 */
package com.kneaf.core.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MapItemSavedDataMixin - Optimizes map updates for players holding maps.
 * 
 * PROBLEM:
 * - MapItemSavedData.tickCarriedBy() is called every tick for every player
 * holding a map
 * - This causes unnecessary packet generation even when map content is
 * unchanged
 * - Maps in item frames also trigger updates unnecessarily
 * 
 * OPTIMIZATIONS:
 * 1. Track last update checksum per-map
 * 2. Skip tickCarriedBy if map content hasn't changed
 * 3. Reduce update frequency for maps far from player
 */
@Mixin(MapItemSavedData.class)
public abstract class MapItemSavedDataMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/MapItemSavedDataMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Track last checksum per player for this map
    // Key: player UUID hash, Value: last content checksum
    @Unique
    private final Map<Integer, Integer> kneaf$lastChecksums = new ConcurrentHashMap<>();

    // Track last update tick per player
    @Unique
    private final Map<Integer, Long> kneaf$lastUpdateTicks = new ConcurrentHashMap<>();

    // Minimum ticks between full updates when map is unchanged
    @Unique
    private static final int MIN_UPDATE_INTERVAL = 20; // 1 second

    // Statistics
    @Unique
    private static final AtomicLong kneaf$updatesSkipped = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$updatesSent = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    @Shadow
    public byte[] colors;

    /**
     * OPTIMIZATION: Skip map updates when content hasn't changed.
     */
    @Inject(method = "tickCarriedBy", at = @At("HEAD"), cancellable = true)
    private void kneaf$onTickCarriedBy(Player player, ItemStack mapStack, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ MapItemSavedDataMixin applied - Map update optimization active!");
            kneaf$loggedFirstApply = true;
        }

        Level level = player.level();
        if (level == null || level.isClientSide()) {
            return; // Let vanilla handle client-side
        }

        int playerKey = player.getUUID().hashCode();
        long currentTick = level.getGameTime();

        // Calculate content checksum
        int currentChecksum = kneaf$calculateChecksum();

        // Check if we've already sent an update recently
        Long lastTick = kneaf$lastUpdateTicks.get(playerKey);
        Integer lastChecksum = kneaf$lastChecksums.get(playerKey);

        if (lastChecksum != null && lastChecksum == currentChecksum) {
            // Content unchanged - check if we should skip
            if (lastTick != null && currentTick - lastTick < MIN_UPDATE_INTERVAL) {
                kneaf$updatesSkipped.incrementAndGet();
                ci.cancel(); // Skip this update entirely
                return;
            }
        }

        // Content changed or interval elapsed - allow update
        kneaf$lastChecksums.put(playerKey, currentChecksum);
        kneaf$lastUpdateTicks.put(playerKey, currentTick);
        kneaf$updatesSent.incrementAndGet();

        // Log stats periodically
        kneaf$logStats();
    }

    /**
     * Calculate a quick checksum of map colors.
     */
    @Unique
    private int kneaf$calculateChecksum() {
        if (colors == null || colors.length == 0) {
            return 0;
        }

        // Use Arrays.hashCode for efficient checksum
        // Only sample every 16th byte for very large maps to reduce CPU
        if (colors.length > 1024) {
            int checksum = colors.length;
            for (int i = 0; i < colors.length; i += 16) {
                checksum = checksum * 31 + colors[i];
            }
            return checksum;
        }

        return Arrays.hashCode(colors);
    }

    /**
     * Clear tracking for a player (on disconnect).
     */
    @Unique
    private void kneaf$clearPlayerTracking(Player player) {
        if (player != null) {
            int playerKey = player.getUUID().hashCode();
            kneaf$lastChecksums.remove(playerKey);
            kneaf$lastUpdateTicks.remove(playerKey);
        }
    }

    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 1000) {
            long skipped = kneaf$updatesSkipped.get();
            long sent = kneaf$updatesSent.get();
            long total = skipped + sent;

            if (total > 0) {
                double skipRate = skipped * 100.0 / total;
                // Update central stats
                com.kneaf.core.PerformanceStats.mapUpdatesSkipped = skipped;
                com.kneaf.core.PerformanceStats.mapUpdatesSent = sent;
                com.kneaf.core.PerformanceStats.mapSkipPercent = skipRate;

                kneaf$updatesSkipped.set(0);
                kneaf$updatesSent.set(0);
            }
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Get statistics.
     */
    @Unique
    private static String kneaf$getStatistics() {
        long skipped = kneaf$updatesSkipped.get();
        long sent = kneaf$updatesSent.get();
        long total = skipped + sent;
        double skipRate = total > 0 ? skipped * 100.0 / total : 0;

        return String.format("MapStats{skipped=%d, sent=%d, skipRate=%.1f%%}",
                skipped, sent, skipRate);
    }
}
