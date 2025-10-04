package com.kneaf.core.config;

/** Unified chunk storage configuration that consolidates chunk storage settings. */
public class ChunkStorageConfiguration {
  private final boolean enabled;
  private final int cacheCapacity;
  private final String evictionPolicy;
  private final int asyncThreadpoolSize;
  private final boolean enableAsyncOperations;
  private final long maintenanceIntervalMinutes;
  private final boolean enableBackups;
  private final String backupPath;
  private final boolean enableChecksums;
  private final boolean enableCompression;
  private final int maxBackupFiles;
  private final long backupRetentionDays;
  private final String databaseType;
  private final boolean useRustDatabase;

  private ChunkStorageConfiguration(Builder builder) {
    this.enabled = builder.enabled;
    this.cacheCapacity = builder.cacheCapacity;
    this.evictionPolicy = builder.evictionPolicy;
    this.asyncThreadpoolSize = builder.asyncThreadpoolSize;
    this.enableAsyncOperations = builder.enableAsyncOperations;
    this.maintenanceIntervalMinutes = builder.maintenanceIntervalMinutes;
    this.enableBackups = builder.enableBackups;
    this.backupPath = builder.backupPath;
    this.enableChecksums = builder.enableChecksums;
    this.enableCompression = builder.enableCompression;
    this.maxBackupFiles = builder.maxBackupFiles;
    this.backupRetentionDays = builder.backupRetentionDays;
    this.databaseType = builder.databaseType;
    this.useRustDatabase = builder.useRustDatabase;

    validate();
  }

  private void validate() {
    if (cacheCapacity <= 0) {
      throw new IllegalArgumentException("Cache capacity must be positive");
    }
    if (evictionPolicy == null || evictionPolicy.isEmpty()) {
      throw new IllegalArgumentException("Eviction policy cannot be null or empty");
    }
    if (asyncThreadpoolSize <= 0) {
      throw new IllegalArgumentException("Async thread pool size must be positive");
    }
    if (maintenanceIntervalMinutes <= 0) {
      throw new IllegalArgumentException("Maintenance interval must be positive");
    }
    if (backupPath == null || backupPath.isEmpty()) {
      throw new IllegalArgumentException("Backup path cannot be null or empty");
    }
    if (maxBackupFiles <= 0) {
      throw new IllegalArgumentException("Max backup files must be positive");
    }
    if (backupRetentionDays <= 0) {
      throw new IllegalArgumentException("Backup retention days must be positive");
    }
    if (databaseType == null || databaseType.isEmpty()) {
      throw new IllegalArgumentException("Database type cannot be null or empty");
    }
  }

  // Getters
  public boolean isEnabled() {
    return enabled;
  }

  public int getCacheCapacity() {
    return cacheCapacity;
  }

  public String getEvictionPolicy() {
    return evictionPolicy;
  }

  public int getAsyncThreadpoolSize() {
    return asyncThreadpoolSize;
  }

  public boolean isEnableAsyncOperations() {
    return enableAsyncOperations;
  }

  public long getMaintenanceIntervalMinutes() {
    return maintenanceIntervalMinutes;
  }

  public boolean isEnableBackups() {
    return enableBackups;
  }

  public String getBackupPath() {
    return backupPath;
  }

  public boolean isEnableChecksums() {
    return enableChecksums;
  }

  public boolean isEnableCompression() {
    return enableCompression;
  }

  public int getMaxBackupFiles() {
    return maxBackupFiles;
  }

  public long getBackupRetentionDays() {
    return backupRetentionDays;
  }

  public String getDatabaseType() {
    return databaseType;
  }

  public boolean isUseRustDatabase() {
    return useRustDatabase;
  }

  @Override
  public String toString() {
    return String.format(
        "ChunkStorageConfiguration{enabled=%s, cacheCapacity=%d, evictionPolicy='%s', "
            + "asyncThreadpoolSize=%d, enableAsyncOperations=%s, maintenanceIntervalMinutes=%d, "
            + "enableBackups=%s, backupPath='%s', enableChecksums=%s, enableCompression=%s, "
            + "maxBackupFiles=%d, backupRetentionDays=%d, databaseType='%s', useRustDatabase=%s}",
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
        useRustDatabase);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Create a default configuration. */
  public static ChunkStorageConfiguration createDefault() {
    return new Builder().enabled(true).cacheCapacity(1000).evictionPolicy("LRU").build();
  }

  /** Create a development configuration with smaller cache. */
  public static ChunkStorageConfiguration createDevelopment() {
    return builder()
        .enabled(true)
        .cacheCapacity(100)
        .evictionPolicy("LRU")
        .asyncThreadpoolSize(2)
        .maintenanceIntervalMinutes(30)
        .build();
  }

  /** Create a production configuration with optimized settings. */
  public static ChunkStorageConfiguration createProduction() {
    return builder()
        .enabled(true)
        .cacheCapacity(5000)
        .evictionPolicy("Hybrid")
        .asyncThreadpoolSize(8)
        .maintenanceIntervalMinutes(60)
        .enableCompression(true)
        .build();
  }

  public static class Builder {
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
    private String databaseType = "rust";
    private boolean useRustDatabase = true;

    public Builder enabled(boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    public Builder cacheCapacity(int cacheCapacity) {
      this.cacheCapacity = cacheCapacity;
      return this;
    }

    public Builder evictionPolicy(String evictionPolicy) {
      this.evictionPolicy = evictionPolicy;
      return this;
    }

    public Builder asyncThreadpoolSize(int asyncThreadpoolSize) {
      this.asyncThreadpoolSize = asyncThreadpoolSize;
      return this;
    }

    public Builder enableAsyncOperations(boolean enableAsyncOperations) {
      this.enableAsyncOperations = enableAsyncOperations;
      return this;
    }

    public Builder maintenanceIntervalMinutes(long maintenanceIntervalMinutes) {
      this.maintenanceIntervalMinutes = maintenanceIntervalMinutes;
      return this;
    }

    public Builder enableBackups(boolean enableBackups) {
      this.enableBackups = enableBackups;
      return this;
    }

    public Builder backupPath(String backupPath) {
      this.backupPath = backupPath;
      return this;
    }

    public Builder enableChecksums(boolean enableChecksums) {
      this.enableChecksums = enableChecksums;
      return this;
    }

    public Builder enableCompression(boolean enableCompression) {
      this.enableCompression = enableCompression;
      return this;
    }

    public Builder maxBackupFiles(int maxBackupFiles) {
      this.maxBackupFiles = maxBackupFiles;
      return this;
    }

    public Builder backupRetentionDays(long backupRetentionDays) {
      this.backupRetentionDays = backupRetentionDays;
      return this;
    }

    public Builder databaseType(String databaseType) {
      this.databaseType = databaseType;
      return this;
    }

    public Builder useRustDatabase(boolean useRustDatabase) {
      this.useRustDatabase = useRustDatabase;
      return this;
    }

    public ChunkStorageConfiguration build() {
      return new ChunkStorageConfiguration(this);
    }
  }
}
