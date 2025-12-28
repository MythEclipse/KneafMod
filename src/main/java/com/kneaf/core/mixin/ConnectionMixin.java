/*
 * Copyright (c) 2025 MYTHECLIPSE. All rights reserved.
 * Licensed under the MIT License.
 * 
 * Optimizes network packet sending with batching and coalescing.
 */
package com.kneaf.core.mixin;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ConnectionMixin - Network packet batching optimization.
 * 
 * Optimizations:
 * 1. Batch small packets before sending
 * 2. Track packet sending patterns
 * 3. Coalesce sequential entity updates (tracking only, no modification)
 * 
 * This improves network efficiency by batching packets where possible.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {

    @Unique
    private static final Logger kneaf$LOGGER = LoggerFactory.getLogger("KneafMod/ConnectionMixin");

    @Unique
    private static boolean kneaf$loggedFirstApply = false;

    @Shadow
    private Channel channel;

    @Unique
    private final AtomicInteger kneaf$pendingCount = new AtomicInteger(0);

    @Unique
    private static final AtomicLong kneaf$packetsSent = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$batchesSent = new AtomicLong(0);

    @Unique
    private static final AtomicLong kneaf$packetsCoalesced = new AtomicLong(0);

    @Unique
    private static long kneaf$lastLogTime = 0;

    // Configuration
    @Unique
    private static final int MAX_BATCH_SIZE = 16; /* Batch size limit */

    @Unique
    private static final int BATCH_DELAY_THRESHOLD = 4;

    /**
     * Replacing writeAndFlush with logic to batch.
     */
    @Redirect(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At(value = "INVOKE", target = "Lio/netty/channel/Channel;writeAndFlush(Ljava/lang/Object;)Lio/netty/channel/ChannelFuture;"))
    private ChannelFuture kneaf$onSendWrite(Channel channel, Object msg) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ ConnectionMixin applied - Network packet optimization active!");
            kneaf$loggedFirstApply = true;
        }

        int pending = kneaf$pendingCount.incrementAndGet();
        kneaf$packetsSent.incrementAndGet();

        // If batch is full, flush
        if (pending >= MAX_BATCH_SIZE) {
            kneaf$pendingCount.set(0);
            kneaf$batchesSent.incrementAndGet();
            if (pending > 1) {
                kneaf$packetsCoalesced.addAndGet(pending - 1);
            }
            return channel.writeAndFlush(msg);
        }

        // Otherwise buffer
        return channel.write(msg);
    }

    /**
     * Flush logic on tick.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kneaf$onTick(CallbackInfo ci) {
        // Flush any pending packets every tick
        int pending = kneaf$pendingCount.get();
        if (pending > 0) {
            this.channel.flush();
            kneaf$pendingCount.set(0);
            kneaf$batchesSent.incrementAndGet();
            if (pending > 1) {
                kneaf$packetsCoalesced.addAndGet(pending - 1);
            }
        }
        kneaf$logStats();
    }

    private static void kneaf$logStats() {
        long now = System.currentTimeMillis();
        // Update stats every 1s
        if (now - kneaf$lastLogTime > 1000) {
            long sent = kneaf$packetsSent.get();
            long batches = kneaf$batchesSent.get();
            long coalesced = kneaf$packetsCoalesced.get();
            double timeDiff = (now - kneaf$lastLogTime) / 1000.0;

            if (sent > 0) {
                // Update central stats
                com.kneaf.core.PerformanceStats.netPackets = sent / timeDiff;
                com.kneaf.core.PerformanceStats.netBatches = batches / timeDiff;
                com.kneaf.core.PerformanceStats.netCoalesced = coalesced / timeDiff;

                kneaf$packetsSent.set(0);
                kneaf$batchesSent.set(0);
                kneaf$packetsCoalesced.set(0);
            } else {
                com.kneaf.core.PerformanceStats.netPackets = 0;
            }
            kneaf$lastLogTime = now;
        }
    }
}
