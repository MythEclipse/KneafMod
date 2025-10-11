package com.kneaf.core.performance.monitoring;

import com.kneaf.core.config.ConfigurationManager;
import com.kneaf.core.config.performance.PerformanceConfig;
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
public final class PerformanceManager {
  // Singleton instance
  private static final PerformanceManager INSTANCE = new PerformanceManager();
  
  // Configuration (loaded from unified configuration system)
  private static final ConfigurationManager CONFIGURATION_MANAGER = ConfigurationManager.getInstance();
  private PerformanceConfig config;
  
  // State for backward compatibility
  private boolean isEnabled = true;
  private double averageTPS = 20.0;
  private long lastTickDurationMs = 0;
  
  // Imported types for compatibility
  public interface EntityDataCollection {
      List<EntityData> entities();
      List<PlayerData> players();
      List<BlockEntityData> blockEntities();
      List<MobData> mobs();
  }
  
  // Private constructor for singleton
  private PerformanceManager() {
    try {
      loadConfiguration();
    } catch (Exception e) {
      System.err.println("Failed to initialize PerformanceManager: " + e.getMessage());
      // Ensure we have at least a minimal working configuration
      this.config = getFallbackConfiguration();
    }
  }
  
  /** Get singleton instance */
  public static PerformanceManager getInstance() {
    return INSTANCE;
  }
  
  /** Load configuration with proper error handling */
  private void loadConfiguration() throws ConfigurationException {
    this.config = CONFIGURATION_MANAGER.getConfiguration(PerformanceConfig.class);
  }
  
  /** Get fallback configuration when loading fails */
  private PerformanceConfig getFallbackConfiguration() {
    try {
      return PerformanceConfig.builder()
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
    if (INSTANCE.config.isProfilingEnabled() && durationMs > 50) {
      System.out.println("JNI Call: " + callType + " took " + durationMs + "ms (above threshold)");
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
  
  // Backward compatibility methods - static getters and setters
  public static boolean isEnabled() { return INSTANCE.isEnabled; }
  public static void setEnabled(boolean enabled) { INSTANCE.isEnabled = enabled; }
  public static double getAverageTPS() { return INSTANCE.averageTPS; }
  public static long getLastTickDurationMs() { return INSTANCE.lastTickDurationMs; }
  
  // Essential metric methods for backward compatibility
  public static Map<String, Object> getJniCallMetrics() { return Map.of(); }
  public static Map<String, Object> getLockWaitMetrics() { return Map.of(); }
  public static Map<String, Object> getMemoryMetrics() { return Map.of(); }
  public static Map<String, Object> getAllocationMetrics() { return Map.of(); }
  public static Map<String, Object> getLockContentionMetrics() { return Map.of(); }
  public static List<String> getThresholdAlerts() { return List.of(); }
  
  // Unused metric methods - minimal implementation
  public static void clearThresholdAlerts() { /* Not implemented */ }
  public static Map<String, Object> getLockWaitTypeMetrics() { return Map.of(); }
  public static void recordLockWait(String lockName, long durationMs) { /* Not implemented */ }
  public static void addThresholdAlert(String alert) { /* Not implemented */ }
  
  // Server integration methods - not implemented
  public static void onServerTick(Object server) { /* Not implemented */ }
  public static void broadcastPerformanceLine(Object server, String line) { /* Not implemented */ }
  
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
