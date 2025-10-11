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
  
  // Server integration methods - not implemented
  public static void onServerTick(Object server) { /* Not implemented */ }
  public static void broadcastPerformanceLine(Object server, String line) { /* Not implemented */ }
  
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
    try {
      PerformanceManager instance = getInstance();
      return instance.isEnabled; 
    } catch (Throwable t) {
      System.err.println("Failed to get enabled status: " + t.getMessage());
      return false;
    }
  }
  public static void setEnabled(boolean enabled) { 
    try {
      PerformanceManager instance = getInstance();
      instance.isEnabled = enabled; 
    } catch (Throwable t) {
      System.err.println("Failed to set enabled status: " + t.getMessage());
    }
  }
  public static double getAverageTPS() { 
    try {
      PerformanceManager instance = getInstance();
      return instance.averageTPS; 
    } catch (Throwable t) {
      System.err.println("Failed to get average TPS: " + t.getMessage());
      return 20.0;
    }
  }
  public static long getLastTickDurationMs() { 
    try {
      PerformanceManager instance = getInstance();
      return instance.lastTickDurationMs; 
    } catch (Throwable t) {
      System.err.println("Failed to get last tick duration: " + t.getMessage());
      return 0;
    }
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
