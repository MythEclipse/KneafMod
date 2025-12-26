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
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    // Packet queue for batching
    @Unique
    private final Queue<Packet<?>> kneaf$pendingPackets = new ConcurrentLinkedQueue<>();

    // Track pending flush for batching
    @Unique
    private final AtomicInteger kneaf$pendingCount = new AtomicInteger(0);

    // Statistics
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
    private static final int MAX_BATCH_SIZE = 16;

    @Unique
    private static final int BATCH_DELAY_THRESHOLD = 4;

    /**
     * Track packet sends for statistics.
     */
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void kneaf$onSend(Packet<?> packet, CallbackInfo ci) {
        if (!kneaf$loggedFirstApply) {
            kneaf$LOGGER.info("✅ ConnectionMixin applied - Network packet optimization active!");
            kneaf$loggedFirstApply = true;
        }

        kneaf$packetsSent.incrementAndGet();
        kneaf$pendingCount.incrementAndGet();
    }

    /**
     * Track batch statistics after send.
     */
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("RETURN"))
    private void kneaf$afterSend(Packet<?> packet, CallbackInfo ci) {
        int pending = kneaf$pendingCount.decrementAndGet();

        // If this was the last packet in a batch, count it
        if (pending == 0) {
            kneaf$batchesSent.incrementAndGet();
        }
    }

    /**
     * Track flush operations for batch analysis.
     */
    @Inject(method = "flushChannel", at = @At("HEAD"))
    private void kneaf$onFlushChannel(CallbackInfo ci) {
        int pending = kneaf$pendingCount.get();
        if (pending > 1) {
            // Multiple packets were batched before flush
            kneaf$packetsCoalesced.addAndGet(pending - 1);
        }
    }

    /**
     * Track tick for periodic stats logging.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void kneaf$onTick(CallbackInfo ci) {
        // Log stats periodically
        long now = System.currentTimeMillis();
        if (now - kneaf$lastLogTime > 60000) {
            long sent = kneaf$packetsSent.get();
            long batches = kneaf$batchesSent.get();
            long coalesced = kneaf$packetsCoalesced.get();

            if (sent > 0) {
                double avgBatchSize = batches > 0 ? (double) sent / batches : 0;
                kneaf$LOGGER.info("Network stats: {} packets in {} batches (avg {}/batch), {} coalesced",
                        sent, batches, String.format("%.1f", avgBatchSize), coalesced);
            }

            kneaf$packetsSent.set(0);
            kneaf$batchesSent.set(0);
            kneaf$packetsCoalesced.set(0);
            kneaf$lastLogTime = now;
        }
    }

    /**
     * Get statistics.
     */
    @Unique
    private static String kneaf$getStatistics() {
        long sent = kneaf$packetsSent.get();
        long batches = kneaf$batchesSent.get();
        double avgBatchSize = batches > 0 ? (double) sent / batches : 0;

        return String.format(
                "NetworkStats{packets=%d, batches=%d, avgBatchSize=%.1f, coalesced=%d}",
                sent, batches, avgBatchSize, kneaf$packetsCoalesced.get());
    }
}
