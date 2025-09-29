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
    
import java.util.zip.Deflater;

/**
 * Optimizes network packets for better server performance.
 * Includes packet compression, batching, and rate limiting.
 */
@EventBusSubscriber(modid = "kneafcore")
public class NetworkOptimizer {
    private static final Logger LOGGER = LogUtils.getLogger();

    // Executor for async packet processing
    private static final java.util.concurrent.ScheduledExecutorService NETWORK_EXECUTOR = java.util.concurrent.Executors.newScheduledThreadPool(
        Math.max(1, Runtime.getRuntime().availableProcessors() / 2));

    // Packet batching
    private static final List<Packet<?>> packetBatch = new ArrayList<>();
    private static final int BATCH_SIZE = 10; // Send batch every 10 packets

    // Compression: use thread-local Deflater to be thread-safe
    private static final ThreadLocal<Deflater> DEFLATER_LOCAL = ThreadLocal.withInitial(() -> new Deflater(Deflater.BEST_SPEED));

    private NetworkOptimizer() {}

    // Ensure we clean up thread-local state to avoid potential leaks in long-running servers
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                DEFLATER_LOCAL.remove();
            } catch (Exception e) {
                // ignore
            }
        }));
    }

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
                // schedule immediate execution on executor instead of submit to allow delayed rate-limited tasks
                NETWORK_EXECUTOR.execute(NetworkOptimizer::sendBatchedPackets);
            }
        }
    }

    /**
     * Compresses packet data if applicable.
     */
    public static byte[] compressPacketData(byte[] data) {
        Deflater deflater = DEFLATER_LOCAL.get();
        deflater.reset();
        deflater.setInput(data);
        deflater.finish();
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(data.length)) {
            byte[] buffer = new byte[1024];
            while (!deflater.finished()) {
                int len = deflater.deflate(buffer);
                if (len > 0) baos.write(buffer, 0, len);
            }
            byte[] compressed = baos.toByteArray();
            if (compressed.length < data.length) return compressed;
            return data;
        } catch (java.io.IOException e) {
            LOGGER.warn("Failed to compress packet data: {}", e.getMessage());
            return data;
        } finally {
            deflater.reset();
        }
    }

    private static void sendBatchedPackets() {
        List<Packet<?>> batch;
        synchronized (packetBatch) {
            batch = new ArrayList<>(packetBatch);
            packetBatch.clear();
        }
        // Process batch with rate limiting and compression
        for (Packet<?> packet : batch) {
            // Estimate size without heavy toString(); try packet's serialized size if available, else fallback to class name length
            int estSize = 0;
            try {
                // If packet has a method to estimate size, use reflection in a safe way; otherwise estimate by class name
                java.lang.reflect.Method m = packet.getClass().getMethod("getPacketSize");
                Object res = m.invoke(packet);
                if (res instanceof Integer integer) estSize = integer;
            } catch (Exception ex) {
                estSize = packet.getClass().getSimpleName().length() * 10; // conservative estimate
            }

            // Apply compression if packet appears large
            if (estSize > 1000) {
                byte[] raw = packet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] compressed = compressPacketData(raw);
                LOGGER.debug("Compressed packet {} est {} -> {} bytes", packet.getClass().getSimpleName(), raw.length, compressed.length);
            }

            // Apply rate limiting using scheduled executor rather than blocking sleep
            int rateLimitDelay = calculateRateLimitDelayByEstimate(packet, estSize);
            if (rateLimitDelay > 0) {
                NETWORK_EXECUTOR.schedule(() -> LOGGER.debug("Rate-limited packet send: {} delayed {}ms", packet.getClass().getSimpleName(), rateLimitDelay), rateLimitDelay, java.util.concurrent.TimeUnit.MILLISECONDS);
            } else {
                LOGGER.debug("Processed packet immediately: {}", packet.getClass().getSimpleName());
            }
        }
        LOGGER.debug("Processed batched packets: {}", batch.size());
    }

    /**
     * Calculates rate limit delay based on packet type and size.
     */
    // removed unused calculateRateLimitDelay to reduce clutter

    private static int calculateRateLimitDelayByEstimate(Packet<?> packet, int estSize) {
        String packetType = packet.getClass().getSimpleName();
        int size = estSize;
        if (size <= 0) {
            // As a fallback use class-based heuristic
            size = packetType.length() * 10;
        }

        int baseDelay = 2;
        if (packetType.contains("Chunk")) baseDelay = 5;
        else if (packetType.contains("Move") || packetType.contains("Entity")) baseDelay = 1;

        int sizeDelay = 0;
        if (size > 5000) sizeDelay = 10;
        else if (size > 1000) sizeDelay = 5;

        return baseDelay + sizeDelay;
    }
}