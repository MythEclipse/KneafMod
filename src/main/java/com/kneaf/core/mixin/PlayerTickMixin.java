/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 */
package com.kneaf.core.mixin;

import com.kneaf.core.io.AsyncChunkPrefetcher;
import com.kneaf.core.io.PlayerMovementTracker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * PlayerTickMixin - Trigger chunk prefetching based on player movement.
 * 
 * Predicts player movement every 0.5 seconds and schedules async chunk
 * prefetching.
 */
@Mixin(Player.class)
public abstract class PlayerTickMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/PlayerTickMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    /**
     * Trigger prefetching every 10 ticks (0.5 seconds).
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void kneaf$onPlayerTick(CallbackInfo ci) {
        Player player = (Player) (Object) this;

        // Only for server-side players
        if (player.level().isClientSide()) {
            return;
        }

        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ PlayerTickMixin applied - Chunk prefetching active!");
            kneaf$loggedFirstApply = true;
        }

        // Predict and prefetch every 10 ticks (0.5 seconds)
        if (player.tickCount % 10 == 0) {
            try {
                // Predict which chunks player will visit
                List<ChunkPos> predicted = PlayerMovementTracker.predict(player);

                // Schedule prefetch
                if (!predicted.isEmpty()) {
                    AsyncChunkPrefetcher.schedulePrefetch(predicted);
                }

                // Update performance stats
                com.kneaf.core.PerformanceStats.prefetchActive = AsyncChunkPrefetcher.getActiveOperations();
                com.kneaf.core.PerformanceStats.prefetchQueueSize = AsyncChunkPrefetcher.getQueueSize();

            } catch (Exception e) {
                // Silently fail - prefetching is best-effort
                kneaf$LOGGER.debug("Prefetch prediction failed: {}", e.getMessage());
            }
        }

        // Update configuration every 5 seconds based on TPS
        if (player.tickCount % 100 == 0) {
            double currentTPS = com.kneaf.core.util.TPSTracker.getCurrentTPS();
            AsyncChunkPrefetcher.updateConfiguration(currentTPS);
        }
    }
}
