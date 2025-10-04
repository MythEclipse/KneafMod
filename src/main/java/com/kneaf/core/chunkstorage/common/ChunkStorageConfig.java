package com.kneaf.core.chunkstorage.common;

/** Configuration for chunk storage operations. */
public class ChunkStorageConfig {
  private boolean enabled = true;
  private int cacheCapacity = 1000;
  private String evictionPolicy = "LRU";
  private int asyncThreadpoolSize = 4;
  private boolean enableAsyncOperations = true;
  private long maintenanceIntervalMinutes = 60;
  private boolean enableBackups = true;
  private String backupPath = "backups/chunkstorage";
  private boolean enableChecksums = true;
  private boolean enableCompression = false;
  private int maxBackupFiles = 10;
  private long backupRetentionDays = 7;
  private String databaseType = "rust"; // "rust", "inmemory", "rocksdb", "sled", "lmdb"
  private boolean useRustDatabase = true;

  // Swap configuration
  private boolean enableSwapManager = true;
  private long swapMemoryCheckIntervalMs = 5000;
  private int maxConcurrentSwaps = 10;
  private int swapBatchSize = 50;
  private long swapTimeoutMs = 30000;
  private boolean enableAutomaticSwapping = true;
  private double criticalMemoryThreshold = 0.95;
  private double highMemoryThreshold = 0.85;
  private double elevatedMemoryThreshold = 0.75;
  private int minSwapChunkAgeMs = 60000;
  private boolean enableSwapStatistics = true;

  public ChunkStorageConfig() {}

  public ChunkStorageConfig(boolean enabled, int cacheCapacity, String evictionPolicy) {
    this.enabled = enabled;
    this.cacheCapacity = cacheCapacity;
    this.evictionPolicy = evictionPolicy;
  }

  // Getters and setters
  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public int getCacheCapacity() {
    return cacheCapacity;
  }

  public void setCacheCapacity(int cacheCapacity) {
    if (cacheCapacity <= 0) {
      throw new IllegalArgumentException("Cache capacity must be positive");
    }
    this.cacheCapacity = cacheCapacity;
  }

  public String getEvictionPolicy() {
    return evictionPolicy;
  }

  public void setEvictionPolicy(String evictionPolicy) {
    if (evictionPolicy == null || evictionPolicy.isEmpty()) {
      throw new IllegalArgumentException("Eviction policy cannot be null or empty");
    }
    this.evictionPolicy = evictionPolicy;
  }

  public int getAsyncThreadpoolSize() {
    return asyncThreadpoolSize;
  }

  public void setAsyncThreadpoolSize(int asyncThreadpoolSize) {
    if (asyncThreadpoolSize <= 0) {
      throw new IllegalArgumentException("Async thread pool size must be positive");
    }
    this.asyncThreadpoolSize = asyncThreadpoolSize;
  }

  public boolean isEnableAsyncOperations() {
    return enableAsyncOperations;
  }

  public void setEnableAsyncOperations(boolean enableAsyncOperations) {
    this.enableAsyncOperations = enableAsyncOperations;
  }

  public long getMaintenanceIntervalMinutes() {
    return maintenanceIntervalMinutes;
  }

  public void setMaintenanceIntervalMinutes(long maintenanceIntervalMinutes) {
    if (maintenanceIntervalMinutes <= 0) {
      throw new IllegalArgumentException("Maintenance interval must be positive");
    }
    this.maintenanceIntervalMinutes = maintenanceIntervalMinutes;
  }

  public boolean isEnableBackups() {
    return enableBackups;
  }

  public void setEnableBackups(boolean enableBackups) {
    this.enableBackups = enableBackups;
  }

  public String getBackupPath() {
    return backupPath;
  }

  public void setBackupPath(String backupPath) {
    if (backupPath == null || backupPath.isEmpty()) {
      throw new IllegalArgumentException("Backup path cannot be null or empty");
    }
    this.backupPath = backupPath;
  }

  public boolean isEnableChecksums() {
    return enableChecksums;
  }

  public void setEnableChecksums(boolean enableChecksums) {
    this.enableChecksums = enableChecksums;
  }

  public boolean isEnableCompression() {
    return enableCompression;
  }

  public void setEnableCompression(boolean enableCompression) {
    this.enableCompression = enableCompression;
  }

  public int getMaxBackupFiles() {
    return maxBackupFiles;
  }

  public void setMaxBackupFiles(int maxBackupFiles) {
    if (maxBackupFiles <= 0) {
      throw new IllegalArgumentException("Max backup files must be positive");
    }
    this.maxBackupFiles = maxBackupFiles;
  }

  public long getBackupRetentionDays() {
    return backupRetentionDays;
  }

  public void setBackupRetentionDays(long backupRetentionDays) {
    if (backupRetentionDays <= 0) {
      throw new IllegalArgumentException("Backup retention days must be positive");
    }
    this.backupRetentionDays = backupRetentionDays;
  }

  public String getDatabaseType() {
    return databaseType;
  }

  public void setDatabaseType(String databaseType) {
    if (databaseType == null || databaseType.isEmpty()) {
      throw new IllegalArgumentException("Database type cannot be null or empty");
    }
    this.databaseType = databaseType;
  }

  public boolean isUseRustDatabase() {
    return useRustDatabase;
  }

  public void setUseRustDatabase(boolean useRustDatabase) {
    this.useRustDatabase = useRustDatabase;
  }

  // Swap configuration getters and setters
  public boolean isEnableSwapManager() {
    return enableSwapManager;
  }

  public void setEnableSwapManager(boolean enableSwapManager) {
    this.enableSwapManager = enableSwapManager;
  }

  public long getSwapMemoryCheckIntervalMs() {
    return swapMemoryCheckIntervalMs;
  }

  public void setSwapMemoryCheckIntervalMs(long swapMemoryCheckIntervalMs) {
    this.swapMemoryCheckIntervalMs = swapMemoryCheckIntervalMs;
  }

  public int getMaxConcurrentSwaps() {
    return maxConcurrentSwaps;
  }

  public void setMaxConcurrentSwaps(int maxConcurrentSwaps) {
    this.maxConcurrentSwaps = maxConcurrentSwaps;
  }

  public int getSwapBatchSize() {
    return swapBatchSize;
  }

  public void setSwapBatchSize(int swapBatchSize) {
    this.swapBatchSize = swapBatchSize;
  }

  public long getSwapTimeoutMs() {
    return swapTimeoutMs;
  }

  public void setSwapTimeoutMs(long swapTimeoutMs) {
    this.swapTimeoutMs = swapTimeoutMs;
  }

  public boolean isEnableAutomaticSwapping() {
    return enableAutomaticSwapping;
  }

  public void setEnableAutomaticSwapping(boolean enableAutomaticSwapping) {
    this.enableAutomaticSwapping = enableAutomaticSwapping;
  }

  public double getCriticalMemoryThreshold() {
    return criticalMemoryThreshold;
  }

  public void setCriticalMemoryThreshold(double criticalMemoryThreshold) {
    this.criticalMemoryThreshold = criticalMemoryThreshold;
  }

  public double getHighMemoryThreshold() {
    return highMemoryThreshold;
  }

  public void setHighMemoryThreshold(double highMemoryThreshold) {
    this.highMemoryThreshold = highMemoryThreshold;
  }

  public double getElevatedMemoryThreshold() {
    return elevatedMemoryThreshold;
  }

  public void setElevatedMemoryThreshold(double elevatedMemoryThreshold) {
    this.elevatedMemoryThreshold = elevatedMemoryThreshold;
  }

  public int getMinSwapChunkAgeMs() {
    return minSwapChunkAgeMs;
  }

  public void setMinSwapChunkAgeMs(int minSwapChunkAgeMs) {
    this.minSwapChunkAgeMs = minSwapChunkAgeMs;
  }

  public boolean isEnableSwapStatistics() {
    return enableSwapStatistics;
  }

  public void setEnableSwapStatistics(boolean enableSwapStatistics) {
    this.enableSwapStatistics = enableSwapStatistics;
  }

  @Override
  public String toString() {
    return String.format(
        "ChunkStorageConfig{enabled=%s, cacheCapacity=%d, evictionPolicy='%s', "
            + "asyncThreadpoolSize=%d, enableAsyncOperations=%s, maintenanceIntervalMinutes=%d, "
            + "enableBackups=%s, backupPath='%s', enableChecksums=%s, enableCompression=%s, "
            + "maxBackupFiles=%d, backupRetentionDays=%d, databaseType='%s', useRustDatabase=%s, "
            + "enableSwapManager=%s, swapMemoryCheckIntervalMs=%d, maxConcurrentSwaps=%d, "
            + "swapBatchSize=%d, swapTimeoutMs=%d, enableAutomaticSwapping=%s, "
            + "criticalMemoryThreshold=%.2f, highMemoryThreshold=%.2f, elevatedMemoryThreshold=%.2f, "
            + "minSwapChunkAgeMs=%d, enableSwapStatistics=%s}",
        enabled,
        cacheCapacity,
        evictionPolicy,
        asyncThreadpoolSize,
        enableAsyncOperations,
        maintenanceIntervalMinutes,
        enableBackups,
        backupPath,
        enableChecksums,
        enableCompression,
        maxBackupFiles,
        backupRetentionDays,
        databaseType,
        useRustDatabase,
        enableSwapManager,
        swapMemoryCheckIntervalMs,
        maxConcurrentSwaps,
        swapBatchSize,
        swapTimeoutMs,
        enableAutomaticSwapping,
        criticalMemoryThreshold,
        highMemoryThreshold,
        elevatedMemoryThreshold,
        minSwapChunkAgeMs,
        enableSwapStatistics);
  }

  /** Create a default configuration. */
  public static ChunkStorageConfig createDefault() {
    return new ChunkStorageConfig(true, 1000, "LRU");
  }

  /** Create a development configuration with smaller cache. */
  public static ChunkStorageConfig createDevelopment() {
    ChunkStorageConfig config = new ChunkStorageConfig(true, 100, "LRU");
    config.setAsyncThreadpoolSize(2);
    config.setMaintenanceIntervalMinutes(30);
    return config;
  }

  /** Create a production configuration with optimized settings. */
  public static ChunkStorageConfig createProduction() {
    ChunkStorageConfig config = new ChunkStorageConfig(true, 5000, "Hybrid");
    config.setAsyncThreadpoolSize(8);
    config.setMaintenanceIntervalMinutes(60);
    config.setEnableCompression(true);
    config.setEnableSwapManager(true);
    config.setMaxConcurrentSwaps(20);
    config.setSwapBatchSize(100);
    return config;
  }
}
