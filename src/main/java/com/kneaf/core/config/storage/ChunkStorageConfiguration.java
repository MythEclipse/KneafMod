package com.kneaf.core.config.storage;

import com.kneaf.core.config.core.ConfigurationConstants;
import com.kneaf.core.config.core.ConfigurationProvider;
import com.kneaf.core.config.core.ConfigurationUtils;

/** Unified chunk storage configuration that consolidates chunk storage settings. */
public class ChunkStorageConfiguration implements ConfigurationProvider {
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

  @Override
  public void validate() {
    ConfigurationUtils.validatePositive(cacheCapacity, "Cache capacity");
    ConfigurationUtils.validateNotEmpty(evictionPolicy, "Eviction policy");
    ConfigurationUtils.validatePositive(asyncThreadpoolSize, "Async thread pool size");
    ConfigurationUtils.validatePositive(maintenanceIntervalMinutes, "Maintenance interval");
    ConfigurationUtils.validateNotEmpty(backupPath, "Backup path");
    ConfigurationUtils.validatePositive(maxBackupFiles, "Max backup files");
    ConfigurationUtils.validatePositive(backupRetentionDays, "Backup retention days");
    ConfigurationUtils.validateNotEmpty(databaseType, "Database type");
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  // Getters
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
    return new Builder()
        .enabled(ConfigurationConstants.DEFAULT_ENABLED)
        .cacheCapacity(ConfigurationConstants.DEFAULT_CACHE_CAPACITY)
        .evictionPolicy(ConfigurationConstants.DEFAULT_EVICTION_POLICY)
        .build();
  }

  /** Create a development configuration with smaller cache. */
  public static ChunkStorageConfiguration createDevelopment() {
    return builder()
        .enabled(ConfigurationConstants.DEFAULT_ENABLED)
        .cacheCapacity(100)
        .evictionPolicy(ConfigurationConstants.DEFAULT_EVICTION_POLICY)
        .asyncThreadpoolSize(2)
        .maintenanceIntervalMinutes(30)
        .build();
  }

  /** Create a production configuration with optimized settings. */
  public static ChunkStorageConfiguration createProduction() {
    return builder()
        .enabled(ConfigurationConstants.DEFAULT_ENABLED)
        .cacheCapacity(5000)
        .evictionPolicy(ConfigurationConstants.EVICTION_POLICY_HYBRID)
        .asyncThreadpoolSize(8)
        .maintenanceIntervalMinutes(ConfigurationConstants.DEFAULT_MAINTENANCE_INTERVAL_MINUTES)
        .enableCompression(ConfigurationConstants.DEFAULT_ENABLE_COMPRESSION)
        .build();
  }

  public static class Builder {
    private boolean enabled = ConfigurationConstants.DEFAULT_ENABLED;
    private int cacheCapacity = ConfigurationConstants.DEFAULT_CACHE_CAPACITY;
    private String evictionPolicy = ConfigurationConstants.DEFAULT_EVICTION_POLICY;
    private int asyncThreadpoolSize = ConfigurationConstants.DEFAULT_ASYNC_THREAD_POOL_SIZE;
    private boolean enableAsyncOperations = ConfigurationConstants.DEFAULT_ENABLE_ASYNC_OPERATIONS;
    private long maintenanceIntervalMinutes =
        ConfigurationConstants.DEFAULT_MAINTENANCE_INTERVAL_MINUTES;
    private boolean enableBackups = ConfigurationConstants.DEFAULT_ENABLE_BACKUPS;
    private String backupPath = ConfigurationConstants.DEFAULT_BACKUP_PATH;
    private boolean enableChecksums = ConfigurationConstants.DEFAULT_ENABLE_CHECKSUMS;
    private boolean enableCompression = ConfigurationConstants.DEFAULT_ENABLE_COMPRESSION;
    private int maxBackupFiles = ConfigurationConstants.DEFAULT_MAX_BACKUP_FILES;
    private long backupRetentionDays = ConfigurationConstants.DEFAULT_BACKUP_RETENTION_DAYS;
    private String databaseType = ConfigurationConstants.DEFAULT_DATABASE_TYPE;
    private boolean useRustDatabase = ConfigurationConstants.DEFAULT_USE_RUST_DATABASE;

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
