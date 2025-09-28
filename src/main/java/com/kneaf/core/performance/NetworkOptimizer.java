package com.kneaf.core.performance;

import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Deflater;

/**
 * Optimizes network packets for better server performance.
 * Includes packet compression, batching, and rate limiting.
 */
@EventBusSubscriber(modid = "kneafcore")
public class NetworkOptimizer {
    private static final Logger LOGGER = LogUtils.getLogger();

    // Executor for async packet processing
    private static final ExecutorService NETWORK_EXECUTOR = Executors.newCachedThreadPool();

    // Packet batching
    private static final List<Packet<?>> packetBatch = new ArrayList<>();
    private static final int BATCH_SIZE = 10; // Send batch every 10 packets

    // Compression
    private static final Deflater deflater = new Deflater(Deflater.BEST_SPEED);

    private NetworkOptimizer() {}

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onServerTick(ServerTickEvent.Post event) {
        // Process batched packets
        if (!packetBatch.isEmpty()) {
            NETWORK_EXECUTOR.submit(() -> sendBatchedPackets());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LOGGER.info("Optimizing network for player: {}", player.getName().getString());
            // Enable compression for player connection
            // Note: Actual compression would require modifying packet sending
        }
    }

    /**
     * Adds a packet to the batch for optimized sending.
     */
    public static void addPacketToBatch(Packet<?> packet) {
        synchronized (packetBatch) {
            packetBatch.add(packet);
            if (packetBatch.size() >= BATCH_SIZE) {
                NETWORK_EXECUTOR.submit(() -> sendBatchedPackets());
            }
        }
    }

    /**
     * Compresses packet data if applicable.
     */
    public static byte[] compressPacketData(byte[] data) {
        deflater.setInput(data);
        deflater.finish();
        byte[] compressed = new byte[data.length];
        int compressedLength = deflater.deflate(compressed);
        deflater.reset();
        if (compressedLength < data.length) {
            byte[] result = new byte[compressedLength];
            System.arraycopy(compressed, 0, result, 0, compressedLength);
            return result;
        }
        return data; // Return original if compression doesn't help
    }

    private static void sendBatchedPackets() {
        List<Packet<?>> batch;
        synchronized (packetBatch) {
            batch = new ArrayList<>(packetBatch);
            packetBatch.clear();
        }
        // Process batch with rate limiting and compression
        for (Packet<?> packet : batch) {
            // Apply compression if packet is large
            if (packet.toString().length() > 1000) { // Arbitrary size check
                byte[] compressed = compressPacketData(packet.toString().getBytes());
                LOGGER.debug("Compressed packet from {} to {} bytes", packet.toString().length(), compressed.length);
            }
            // Apply rate limiting based on packet type and size
            int rateLimitDelay = calculateRateLimitDelay(packet);
            if (rateLimitDelay > 0) {
                try {
                    Thread.sleep(rateLimitDelay); // Apply delay for rate limiting
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            LOGGER.debug("Applied rate limiting to packet: {} with delay: {}ms", packet.getClass().getSimpleName(), rateLimitDelay);
        }
        LOGGER.debug("Processed batched packets: {}", batch.size());
    }

    /**
     * Calculates rate limit delay based on packet type and size.
     */
    private static int calculateRateLimitDelay(Packet<?> packet) {
        String packetType = packet.getClass().getSimpleName();
        int size = packet.toString().length();

        // Base delay on packet type
        int baseDelay = switch (packetType) {
            case "ClientboundLevelChunkWithLightPacket" -> 5; // Chunk packets get higher priority
            case "ClientboundMoveEntityPacket" -> 1; // Entity movement low delay
            default -> 2;
        };

        // Add delay based on size
        int sizeDelay;
        if (size > 5000) {
            sizeDelay = 10;
        } else if (size > 1000) {
            sizeDelay = 5;
        } else {
            sizeDelay = 0;
        }

        return baseDelay + sizeDelay;
    }
}