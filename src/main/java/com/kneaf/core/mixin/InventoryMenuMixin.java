/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Optimizes inventory/container sync with skip optimization.
 */
package com.kneaf.core.mixin;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * InventoryMenuMixin - REAL optimization for container sync.
 * 
 * ACTUAL OPTIMIZATIONS:
 * 1. Skip broadcastChanges when inventory hasn't changed (checksum match)
 * 2. Reduce sync frequency for unchanged containers
 */
@Mixin(AbstractContainerMenu.class)
public abstract class InventoryMenuMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/InventoryMenuMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    // Track last sync checksum for change detection
    @Unique
    private int kneaf$lastSyncChecksum = 0;

    // Track consecutive unchanged syncs
    @Unique
    private int kneaf$unchangedCount = 0;

    // Skip every N syncs when container is stable
    @Unique
    private static final int SKIP_THRESHOLD = 3;

    // Statistics
    @Unique
    private static final AtomicLong kneaf$syncAttempts = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$syncsSkipped = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    @Shadow
    public net.minecraft.core.NonNullList<net.minecraft.world.inventory.Slot> slots;

    /**
     * OPTIMIZATION: Skip sync when inventory hasn't changed.
     */
    @Inject(method = "broadcastChanges", at = @At("HEAD"), cancellable = true)
    private void kneaf$onBroadcastChanges(CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ InventoryMenuMixin applied - Container sync skip optimization active!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$syncAttempts.incrementAndGet();

        // Calculate checksum of current inventory state
        int currentChecksum = kneaf$calculateChecksum();

        // === OPTIMIZATION: Skip sync if checksum matches ===
        if (currentChecksum == kneaf$lastSyncChecksum && kneaf$lastSyncChecksum != 0) {
            kneaf$unchangedCount++;

            // Skip syncs for stable containers (unchanged for multiple cycles)
            if (kneaf$unchangedCount >= SKIP_THRESHOLD) {
                kneaf$syncsSkipped.incrementAndGet();
                ci.cancel(); // Actually skip the sync!
                return;
            }
        } else {
            // Container changed - reset counter
            kneaf$unchangedCount = 0;
        }

        kneaf$lastSyncChecksum = currentChecksum;

        // Log stats periodically
        kneaf$logStats();
    }

    /**
     * Calculate a quick checksum of inventory contents.
     */
    @Unique
    private int kneaf$calculateChecksum() {
        if (slots == null || slots.isEmpty()) {
            return 0;
        }

        int checksum = slots.size();
        for (int i = 0; i < slots.size(); i++) {
            net.minecraft.world.inventory.Slot slot = slots.get(i);
            if (slot.hasItem()) {
                ItemStack stack = slot.getItem();
                checksum = checksum * 31 + stack.getItem().hashCode();
                checksum = checksum * 31 + stack.getCount();
                // Include components hash for items with data
                checksum = checksum * 31 + stack.getComponents().hashCode();
            }
        }
        return checksum;
    }

    @Unique
    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long attempts = kneaf$syncAttempts.get();
            long skipped = kneaf$syncsSkipped.get();

            if (attempts > 0) {
                double skipRate = skipped * 100.0 / attempts;
                kneaf$LOGGER.info("Container sync optimization: {} attempts, {} skipped ({}% saved)",
                        attempts, skipped, String.format("%.1f", skipRate));
            }

            kneaf$syncAttempts.set(0);
            kneaf$syncsSkipped.set(0);
            kneaf$lastLogTime = now;
        }
    }

    @Unique
    private static String kneaf$getStatistics() {
        return String.format("InventoryMenuStats{ticks=%d, rateLimited=%d, broadcastSkipped=%d}",
                kneaf$syncAttempts.get(), kneaf$syncsSkipped.get());
    }
}
