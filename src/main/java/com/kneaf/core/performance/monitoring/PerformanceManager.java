package com.kneaf.core.performance.monitoring;

import com.kneaf.core.config.ConfigurationManager;
import com.kneaf.core.config.exception.ConfigurationException;
import com.kneaf.core.data.block.BlockEntityData;
import com.kneaf.core.data.entity.EntityData;
import com.kneaf.core.data.entity.MobData;
import com.kneaf.core.data.entity.PlayerData;
import com.kneaf.core.performance.RustPerformance;
import com.kneaf.core.performance.core.MobProcessResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Manages performance monitoring and optimization for the Kneaf mod.
 * Uses unified configuration system while maintaining compatibility with existing code.
 */
public class PerformanceManager {
  // Singleton instance - lazy loaded with double-checked locking
  private static volatile PerformanceManager INSTANCE = null;
  private static final Object INSTANCE_LOCK = new Object();
  
  // Configuration (loaded from unified configuration system)
  protected PerformanceConfig config;
  private static final Object CONFIG_LOCK = new Object();
  private static volatile ConfigurationManager CONFIGURATION_MANAGER = null;
  
  // State for backward compatibility - static for thread safety
  private static volatile boolean isEnabled = true;
  private static volatile double averageTPS = 20.0;
  private static volatile long lastTickDurationMs = 0;

  // Enhanced tick monitoring state - static for backward compatibility
  private static volatile long lastTickTimeNs = 0;
  private static final java.util.Deque<Long> tickDurationsMs = new java.util.LinkedList<>();
  private static final int MAX_TICK_HISTORY = 100;
  private static volatile double currentTPS = 20.0;
  private static volatile long tickCount = 0;
  private static volatile long totalTickTimeMs = 0;
  private static final Object tickMonitorLock = new Object();
  
  // Imported types for compatibility
  public interface EntityDataCollection {
      List<EntityData> entities();
      List<PlayerData> players();
      List<BlockEntityData> blockEntities();
      List<MobData> mobs();
  }
  
  // Private constructor for singleton
  private PerformanceManager() {
    this.config = createSafeConfiguration();
  }

  /** Get singleton instance with lazy initialization and failure resilience */
  public static PerformanceManager getInstance() {
      if (INSTANCE == null) {
          synchronized (INSTANCE_LOCK) {
              if (INSTANCE == null) {
                  try {
                      INSTANCE = new PerformanceManager();
                  } catch (Throwable t) {
                      System.err.println("Failed to create PerformanceManager instance: " + t.getMessage());
                      // Create a fallback instance instead of failing
                      INSTANCE = new PerformanceManager();
                  }
              }
          }
      }
      return INSTANCE;
  }
  
  /** Create a safe configuration that will never fail */
  private PerformanceConfig createSafeConfiguration() {
    try {
      return createMinimalConfiguration();
    } catch (Exception e) {
      System.err.println("Failed to create minimal configuration: " + e.getMessage());
      // Return a configuration object that implements all required methods
      return new PerformanceConfig.Builder()
          .enabled(false)
          .threadpoolSize(1)
          .tpsThresholdForAsync(15.0)
          .maxEntitiesToCollect(100)
          .profilingEnabled(false)
          .build();
    }
  }

  /** Create minimal configuration without depending on builder */
  private static PerformanceConfig createMinimalConfiguration() {
    try {
      return new PerformanceConfig.Builder()
              .enabled(false)
              .threadpoolSize(1)
              .tpsThresholdForAsync(15.0)
              .maxEntitiesToCollect(100)
              .profilingEnabled(false)
              .build();
    } catch (Exception e) {
      throw new RuntimeException("Failed to create minimal configuration", e);
    }
  }

  /** Get configuration manager instance with lazy initialization */
  private static synchronized ConfigurationManager getConfigurationManager() {
      if (CONFIGURATION_MANAGER == null) {
          CONFIGURATION_MANAGER = ConfigurationManager.getInstance();
      }
      return CONFIGURATION_MANAGER;
  }
  
  /** Load configuration with proper error handling */
  private void loadConfiguration() throws ConfigurationException {
      if (CONFIGURATION_MANAGER == null) {
          synchronized (CONFIG_LOCK) {
              if (CONFIGURATION_MANAGER == null) {
                  try {
                      CONFIGURATION_MANAGER = ConfigurationManager.getInstance();
                  } catch (Throwable t) {
                      System.err.println("Failed to get ConfigurationManager: " + t.getMessage());
                      // Use fallback if we can't get config manager
                      this.config = createMinimalConfiguration();
                      return;
                  }
              }
          }
      }
      
      try {
          this.config = CONFIGURATION_MANAGER.getConfiguration(PerformanceConfig.class);
      } catch (Throwable t) {
          System.err.println("Failed to load PerformanceConfig: " + t.getMessage());
          // Fall back to minimal configuration
          this.config = createMinimalConfiguration();
      }
  }
  
  /** Get fallback configuration when loading fails */
  private PerformanceConfig getFallbackConfiguration() {
    try {
      return new PerformanceConfig.Builder()
              .enabled(true)
              .threadpoolSize(4)
              .tpsThresholdForAsync(19.0)
              .maxEntitiesToCollect(1000)
              .profilingEnabled(false)
              .build();
    } catch (Exception e) {
      // Last resort - use minimal configuration
      System.err.println("Failed to create default config: " + e.getMessage());
      throw new IllegalStateException("Cannot create PerformanceConfig instance", e);
    }
  }

  /** Record JNI call performance metrics (static for backward compatibility) */
  public static void recordJniCall(String callType, long durationMs) {
    // Simple threshold check - only log if profiling enabled and duration significant
    try {
      PerformanceManager instance = getInstance();
      if (instance.config.isProfilingEnabled() && durationMs > 50) {
        System.out.println("JNI Call: " + callType + " took " + durationMs + "ms (above threshold)");
      }
    } catch (Throwable t) {
      System.err.println("Failed to record JNI call: " + t.getMessage());
    }
  }

  /** Simple data class to hold optimization results */
  public static class OptimizationResults {
    private final List<Long> entityIdsToTick;
    private final List<Long> blockEntityIdsToTick;
    private final MobProcessResult mobProcessResult;

    public OptimizationResults(List<Long> entityIdsToTick, List<Long> blockEntityIdsToTick, MobProcessResult mobProcessResult) {
      this.entityIdsToTick = entityIdsToTick;
      this.blockEntityIdsToTick = blockEntityIdsToTick;
      this.mobProcessResult = mobProcessResult;
    }

    public List<Long> entityIdsToTick() { return entityIdsToTick; }
    public List<Long> blockEntityIdsToTick() { return blockEntityIdsToTick; }
    public MobProcessResult mobResult() { return mobProcessResult; }
  }
  
  // Unused metric methods - minimal implementation
  public static void clearThresholdAlerts() { /* Not implemented */ }
  public static Map<String, Object> getLockWaitTypeMetrics() { return Map.of(); }
  public static void recordLockWait(String lockName, long durationMs) { /* Not implemented */ }
  public static void addThresholdAlert(String alert) { /* Not implemented */ }
  
  // Server integration methods - fully implemented
  public static void onServerTick(Object server) {
    if (!isEnabled()) {
      return;
    }

    // Validate server parameter
    if (server == null) {
      System.err.println("PerformanceManager.onServerTick: server parameter cannot be null");
      return;
    }

    if (!(server instanceof net.minecraft.server.MinecraftServer)) {
      System.err.println("PerformanceManager.onServerTick: server parameter must be MinecraftServer instance, got: " +
          (server.getClass() != null ? server.getClass().getName() : "null"));
      return;
    }

    net.minecraft.server.MinecraftServer minecraftServer = (net.minecraft.server.MinecraftServer) server;

    try {
      synchronized (tickMonitorLock) {
        long currentTimeNs = System.nanoTime();
        long tickDurationNs = 0;

        if (lastTickTimeNs > 0) {
          tickDurationNs = currentTimeNs - lastTickTimeNs;
        }
        lastTickTimeNs = currentTimeNs;

        long tickDurationMs = TimeUnit.NANOSECONDS.toMillis(tickDurationNs);
        lastTickDurationMs = tickDurationMs;

        // Update tick history for TPS calculation
        tickDurationsMs.addLast(tickDurationMs);
        if (tickDurationsMs.size() > MAX_TICK_HISTORY) {
          tickDurationsMs.removeFirst();
        }

        tickCount++;
        totalTickTimeMs += tickDurationMs;

        // Calculate current TPS based on recent tick durations
        if (!tickDurationsMs.isEmpty()) {
          double averageTickDurationMs = tickDurationsMs.stream().mapToLong(Long::longValue).average().orElse(50.0);
          currentTPS = 1000.0 / averageTickDurationMs;
          // Clamp TPS to reasonable bounds
          currentTPS = Math.max(0.0, Math.min(100.0, currentTPS));
        }

        // Update average TPS with exponential moving average
        double alpha = 0.1; // Smoothing factor
        averageTPS = alpha * currentTPS + (1 - alpha) * averageTPS;

        // Record tick performance metrics
        recordJniCall("server_tick", tickDurationMs);

        // Check for performance thresholds and broadcast alerts if needed
        checkPerformanceThresholds(minecraftServer, tickDurationMs, currentTPS);

        // Integrate with RustPerformance for native metrics if available
        try {
          if (RustPerformance.isNativeAvailable()) {
            RustPerformance.setCurrentTPS(currentTPS);
            // Get additional native metrics
            String memoryStats = RustPerformance.getMemoryStats();
            String cpuStats = RustPerformance.getCpuStats();

            // Log comprehensive performance data periodically
            if (tickCount % 100 == 0) { // Every 100 ticks (~5 seconds at 20 TPS)
              logPerformanceMetrics(minecraftServer, tickDurationMs, currentTPS, memoryStats, cpuStats);
            }
          }
        } catch (Exception e) {
          // Don't let Rust integration failures affect server tick
          System.err.println("Failed to integrate with RustPerformance: " + e.getMessage());
        }
      }
    } catch (Exception e) {
      System.err.println("PerformanceManager.onServerTick: Unexpected error during tick monitoring: " + e.getMessage());
      e.printStackTrace();
    }
  }

  public static void broadcastPerformanceLine(Object server, String line) {
    // Validate parameters
    if (server == null) {
      System.err.println("PerformanceManager.broadcastPerformanceLine: server parameter cannot be null");
      return;
    }

    if (line == null || line.trim().isEmpty()) {
      System.err.println("PerformanceManager.broadcastPerformanceLine: line parameter cannot be null or empty");
      return;
    }

    if (!(server instanceof net.minecraft.server.MinecraftServer)) {
      System.err.println("PerformanceManager.broadcastPerformanceLine: server parameter must be MinecraftServer instance, got: " +
          (server.getClass() != null ? server.getClass().getName() : "null"));
      return;
    }

    net.minecraft.server.MinecraftServer minecraftServer = (net.minecraft.server.MinecraftServer) server;

    try {
      // Use NetworkHandler for broadcasting to all players
      com.kneaf.core.network.NetworkHandler.broadcastPerformanceLine(minecraftServer, line.trim());

      // Also log to server console for debugging
      System.out.println("[Performance] " + line.trim());
    } catch (Exception e) {
      System.err.println("PerformanceManager.broadcastPerformanceLine: Failed to broadcast performance line: " + e.getMessage());
      e.printStackTrace();

      // Fallback: try to log to console at least
      try {
        System.err.println("Performance broadcast failed, logging to console: " + line.trim());
      } catch (Exception fallbackError) {
        // Last resort - do nothing if even console logging fails
      }
    }
  }

  // Performance threshold checking and alerting
  private static void checkPerformanceThresholds(net.minecraft.server.MinecraftServer server, long tickDurationMs, double currentTPS) {
    try {
      PerformanceManager instance = getInstance();
      if (instance.config == null) {
        return; // No config available
      }

      double tpsThreshold = instance.config.getTpsThresholdForAsync();
      if (currentTPS < tpsThreshold) {
        String alertMessage = String.format("Performance Alert: TPS dropped below threshold (%.1f < %.1f), Tick Duration: %dms",
            currentTPS, tpsThreshold, tickDurationMs);
        broadcastPerformanceLine(server, alertMessage);
        addThresholdAlert(alertMessage);
      }

      if (tickDurationMs > 100) { // Hard-coded threshold for very slow ticks
        String slowTickMessage = String.format("Performance Warning: Slow tick detected (%dms)", tickDurationMs);
        broadcastPerformanceLine(server, slowTickMessage);
      }
    } catch (Exception e) {
      System.err.println("Failed to check performance thresholds: " + e.getMessage());
    }
  }

  // Comprehensive performance metrics logging
  private static void logPerformanceMetrics(net.minecraft.server.MinecraftServer server, long tickDurationMs, double currentTPS, String memoryStats, String cpuStats) {
    try {
      StringBuilder metrics = new StringBuilder();
      metrics.append(String.format("Performance Metrics - TPS: %.2f, Tick Duration: %dms", currentTPS, tickDurationMs));

      if (memoryStats != null && !memoryStats.isEmpty()) {
        metrics.append(", Memory: ").append(memoryStats);
      }

      if (cpuStats != null && !cpuStats.isEmpty()) {
        metrics.append(", CPU: ").append(cpuStats);
      }

      // Log to server console
      System.out.println("[PerformanceManager] " + metrics.toString());

      // Broadcast to players if TPS is concerning
      if (currentTPS < 18.0) {
        broadcastPerformanceLine(server, metrics.toString());
      }
    } catch (Exception e) {
      System.err.println("Failed to log performance metrics: " + e.getMessage());
    }
  }

  // Backward compatibility methods - return empty collections for missing metrics
  public static Map<String, Object> getJniCallMetrics() {
      try {
          getInstance(); // Ensure instance is created
          return Map.of(); // Return empty map for now
      } catch (Throwable t) {
          return Map.of();
      }
  }
  
  public static Map<String, Object> getLockWaitMetrics() {
      try {
          getInstance(); // Ensure instance is created
          return Map.of(); // Return empty map for now
      } catch (Throwable t) {
          return Map.of();
      }
  }
  
  public static Map<String, Object> getMemoryMetrics() {
      try {
          getInstance(); // Ensure instance is created
          return Map.of(); // Return empty map for now
      } catch (Throwable t) {
          return Map.of();
      }
  }
  
  public static Map<String, Object> getAllocationMetrics() {
      try {
          getInstance(); // Ensure instance is created
          return Map.of(); // Return empty map for now
      } catch (Throwable t) {
          return Map.of();
      }
  }
  
  public static Map<String, Object> getLockContentionMetrics() {
      try {
          getInstance(); // Ensure instance is created
          return Map.of(); // Return empty map for now
      } catch (Throwable t) {
          return Map.of();
      }
  }
  
  public static List<String> getThresholdAlerts() {
      try {
          getInstance(); // Ensure instance is created
          return List.of(); // Return empty list for now
      } catch (Throwable t) {
          return List.of();
      }
  }

  // Backward compatibility methods - static getters and setters with null safety
  public static boolean isEnabled() {
    return isEnabled;
  }
  public static void setEnabled(boolean enabled) {
    isEnabled = enabled;
  }
  public static double getAverageTPS() {
    return averageTPS;
  }
  public static long getLastTickDurationMs() {
    return lastTickDurationMs;
  }

  /** Process optimization data using RustPerformance with unified bridge integration */
  public static OptimizationResults processOptimizations(EntityDataCollection data) {
    // Use RustPerformance directly for native operations
    long getEntitiesToTickStart = System.nanoTime();
    List<Long> toTick = RustPerformance.getEntitiesToTick(data.entities(), data.players());
    long getEntitiesToTickNanos = System.nanoTime() - getEntitiesToTickStart;
    recordJniCall("getEntitiesToTick", TimeUnit.NANOSECONDS.toMillis(getEntitiesToTickNanos));
     
    long getBlockEntitiesStart = System.nanoTime();
    List<Long> blockResult = RustPerformance.getBlockEntitiesToTick(data.blockEntities());
    long getBlockEntitiesNanos = System.nanoTime() - getBlockEntitiesStart;
    recordJniCall("getBlockEntitiesToTick", TimeUnit.NANOSECONDS.toMillis(getBlockEntitiesNanos));
     
    long processMobAIStart = System.nanoTime();
    MobProcessResult mobResult = RustPerformance.processMobAI(data.mobs());
    long processMobAINanos = System.nanoTime() - processMobAIStart;
    recordJniCall("processMobAI", TimeUnit.NANOSECONDS.toMillis(processMobAINanos));
     
    return new OptimizationResults(toTick, blockResult, mobResult);
  }
  
}
