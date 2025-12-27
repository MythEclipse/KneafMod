/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Removed - tracking-only mixin with no real optimization.
 * ChunkDataPacket creation is already optimized by Minecraft itself.
 * This mixin is kept minimal to avoid overhead.
 */
package com.kneaf.core.mixin;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ChunkDataPacketMixin - Minimal tracking for chunk packets.
 * 
 * Note: Most chunk packet optimizations require deep changes to the
 * serialization format which could break client compatibility.
 * This mixin only provides metrics tracking.
 */
@Mixin(ClientboundLevelChunkWithLightPacket.class)
public abstract class ChunkDataPacketMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/ChunkDataPacketMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    @Unique
    private static final AtomicLong kneaf$packetsCreated = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    @Inject(method = "<init>(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/lighting/LevelLightEngine;Ljava/util/BitSet;Ljava/util/BitSet;)V", at = @At("RETURN"))
    private void kneaf$onPacketCreate(LevelChunk chunk, LevelLightEngine lightEngine,
            BitSet skyLight, BitSet blockLight, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ ChunkDataPacketMixin applied - Packet tracking active");
            kneaf$loggedFirstApply = true;
        }

        kneaf$packetsCreated.incrementAndGet();

        // Log stats periodically
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            kneaf$LOGGER.info("ChunkPackets created: {}", kneaf$packetsCreated.get());
            kneaf$packetsCreated.set(0);
            kneaf$lastLogTime = now;
        }
    }

    @Unique
    public static String kneaf$getStatistics() {
        return String.format("ChunkPacketStats{created=%d}", kneaf$packetsCreated.get());
    }
}
