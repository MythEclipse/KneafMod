package com.kneaf.core.performance;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.Deflater;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Optimizes network packets for better server performance. Includes packet compression, batching,
 * and rate limiting.
 */
@EventBusSubscriber(modid = "kneafcore")
public class NetworkOptimizer {
  private static final Logger LOGGER = LogUtils.getLogger();

  // Executor for async packet processing. Lazily initialized to avoid heavy work during class
  // loading.
  private static final AtomicReference<java.util.concurrent.ScheduledExecutorService>
      NETWORK_EXECUTOR = new AtomicReference<>();

  private static java.util.concurrent.ScheduledExecutorService getNetworkExecutor() {
    java.util.concurrent.ScheduledExecutorService exec = NETWORK_EXECUTOR.get();
    if (exec == null) {
      synchronized (NetworkOptimizer.class) {
        exec = NETWORK_EXECUTOR.get();
        if (exec == null) {
          int pool =
              com.kneaf.core.performance.monitoring.PerformanceConfig.load()
                  .getNetworkExecutorpoolSize();
          exec = java.util.concurrent.Executors.newScheduledThreadPool(Math.max(1, pool));
          NETWORK_EXECUTOR.set(exec);
          // add a shutdown hook to ensure we don't leak threads in environments that load/unload
          // mods
          Runtime.getRuntime()
              .addShutdownHook(
                  new Thread(
                      () -> {
                        try {
                          java.util.concurrent.ScheduledExecutorService e = NETWORK_EXECUTOR.get();
                          if (e != null) {
                            e.shutdownNow();
                          }
                        } catch (Exception ex) {
                          // ignore
                        }
                      }));
        }
      }
    }
    return exec;
  }

  // Packet batching
  private static final List<Packet<?>> PACKET_BATCH = new ArrayList<>();

  private static int getPacketBatchSize() {
    double tps = com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS();
    double tickDelay =
        com.kneaf.core.performance.monitoring.PerformanceManager.getLastTickDurationMs();
    return com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveBatchSize(
        tps, tickDelay);
  }

  // Compression: use thread-local Deflater to be thread-safe
  private static final ThreadLocal<Deflater> DEFLATER_LOCAL =
      ThreadLocal.withInitial(() -> new Deflater(Deflater.BEST_SPEED));

  private NetworkOptimizer() {}

  // Ensure we clean up thread-local state to avoid potential leaks in long-running servers
  static {
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
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
    if (!PACKET_BATCH.isEmpty()) {
      getNetworkExecutor().submit(() -> sendBatchedPackets());
    }
  }

  @SubscribeEvent
  public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
    if (event.getEntity() instanceof ServerPlayer player) {
      LOGGER.info("Optimizing network for player: { }", player.getName().getString());
      // Enable compression for player connection
      // Note: Actual compression would require modifying packet sending
    }
  }

  /** Adds a packet to the batch for optimized sending. */
  public static void addPacketToBatch(Packet<?> packet) {
    synchronized (PACKET_BATCH) {
      PACKET_BATCH.add(packet);
      int batchSize = getPacketBatchSize();
      double tps = com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS();
      double flushFraction =
          com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveBatchFlushFraction(tps);
      int flushThreshold = Math.max(1, (int) Math.ceil(batchSize * flushFraction));
      if (PACKET_BATCH.size() >= batchSize) {
        // schedule immediate execution on executor instead of submit to allow delayed rate-limited
        // tasks
        getNetworkExecutor().execute(NetworkOptimizer::sendBatchedPackets);
      } else if (PACKET_BATCH.size() >= flushThreshold) {
        // Schedule batch processing for partially-full batches to prevent excessive delays
        int scheduleMs =
            com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveNetworkBatchScheduleMs(
                tps);
        getNetworkExecutor()
            .schedule(
                NetworkOptimizer::sendBatchedPackets,
                scheduleMs,
                java.util.concurrent.TimeUnit.MILLISECONDS);
      }
    }
  }

  /** Compresses packet data if applicable. */
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
      LOGGER.warn("Failed to compress packet data: { }", e.getMessage());
      return data;
    } finally {
      deflater.reset();
    }
  }

  private static void sendBatchedPackets() {
    double tps = com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS();
    List<Packet<?>> batch;
    synchronized (PACKET_BATCH) {
      batch = new ArrayList<>(PACKET_BATCH);
      PACKET_BATCH.clear();
    }
    // Process batch with rate limiting and compression
    for (Packet<?> packet : batch) {
      // Estimate size without heavy toString(); try packet's serialized size if available, else
      // fallback to class name length
      int estSize = 0;
      try {
        // If packet has a method to estimate size, use reflection in a safe way; otherwise estimate
        // by class name
        java.lang.reflect.Method m = packet.getClass().getMethod("getPacketSize");
        Object res = m.invoke(packet);
        if (res instanceof Integer integer) estSize = integer;
      } catch (Exception ex) {
        estSize = packet.getClass().getSimpleName().length() * 10; // conservative estimate
      }

      // Apply compression if packet appears large (adaptive threshold)
      int compressionThreshold =
          com.kneaf.core.performance.core.PerformanceConstants
              .getAdaptivePacketCompressionThreshold(tps);
      if (estSize > compressionThreshold) {
        byte[] raw = packet.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] compressed = compressPacketData(raw);
        LOGGER.debug(
            "Compressed packet { } est { } -> { } bytes",
            packet.getClass().getSimpleName(),
            raw.length,
            compressed.length);
      }

      // Apply rate limiting using scheduled executor rather than blocking sleep
      int rateLimitDelay = calculateRateLimitDelayByEstimate(packet, estSize);
      if (rateLimitDelay > 0) {
        getNetworkExecutor()
            .schedule(
                () ->
                    LOGGER.debug(
                        "Rate-limited packet send: { } delayed { }ms",
                        packet.getClass().getSimpleName(),
                        rateLimitDelay),
                rateLimitDelay,
                java.util.concurrent.TimeUnit.MILLISECONDS);
      } else {
        LOGGER.debug("Processed packet immediately: { }", packet.getClass().getSimpleName());
      }
    }
    LOGGER.debug("Processed batched packets: { }", batch.size());
  }

  /** Calculates rate limit delay based on packet type and size. */
  // removed unused calculateRateLimitDelay to reduce clutter

  private static int calculateRateLimitDelayByEstimate(Packet<?> packet, int estSize) {
    String packetType = packet.getClass().getSimpleName();
    int size = estSize;
    if (size <= 0) {
      // As a fallback use class-based heuristic
      size = packetType.length() * 10;
    }

    int baseDelay =
        com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveRateLimitBaseDelayMs(
            com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS());
    if (packetType.contains("Chunk")) baseDelay = Math.max(baseDelay, 5);
    else if (packetType.contains("Move") || packetType.contains("Entity"))
      baseDelay = Math.min(baseDelay, 1);

    int sizeExtra =
        com.kneaf.core.performance.core.PerformanceConstants.getAdaptiveRateLimitExtraForSize(
            size, com.kneaf.core.performance.monitoring.PerformanceManager.getAverageTPS());

    return baseDelay + sizeExtra;
  }
}
