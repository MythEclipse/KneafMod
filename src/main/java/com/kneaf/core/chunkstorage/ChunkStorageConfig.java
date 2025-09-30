package com.kneaf.core.chunkstorage;

/**
 * Configuration for chunk storage operations.
 */
public class ChunkStorageConfig {
    private boolean enabled = true;
    private int cacheCapacity = 1000;
    private String evictionPolicy = "LRU";
    private int asyncThreadPoolSize = 4;
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
    
    public int getAsyncThreadPoolSize() {
        return asyncThreadPoolSize;
    }
    
    public void setAsyncThreadPoolSize(int asyncThreadPoolSize) {
        if (asyncThreadPoolSize <= 0) {
            throw new IllegalArgumentException("Async thread pool size must be positive");
        }
        this.asyncThreadPoolSize = asyncThreadPoolSize;
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
    
    @Override
    public String toString() {
        return String.format("ChunkStorageConfig{enabled=%s, cacheCapacity=%d, evictionPolicy='%s', " +
                           "asyncThreadPoolSize=%d, enableAsyncOperations=%s, maintenanceIntervalMinutes=%d, " +
                           "enableBackups=%s, backupPath='%s', enableChecksums=%s, enableCompression=%s, " +
                           "maxBackupFiles=%d, backupRetentionDays=%d, databaseType='%s', useRustDatabase=%s}",
                           enabled, cacheCapacity, evictionPolicy, asyncThreadPoolSize,
                           enableAsyncOperations, maintenanceIntervalMinutes, enableBackups,
                           backupPath, enableChecksums, enableCompression, maxBackupFiles,
                           backupRetentionDays, databaseType, useRustDatabase);
    }
    
    /**
     * Create a default configuration.
     */
    public static ChunkStorageConfig createDefault() {
        return new ChunkStorageConfig(true, 1000, "LRU");
    }
    
    /**
     * Create a development configuration with smaller cache.
     */
    public static ChunkStorageConfig createDevelopment() {
        ChunkStorageConfig config = new ChunkStorageConfig(true, 100, "LRU");
        config.setAsyncThreadPoolSize(2);
        config.setMaintenanceIntervalMinutes(30);
        return config;
    }
    
    /**
     * Create a production configuration with optimized settings.
     */
    public static ChunkStorageConfig createProduction() {
        ChunkStorageConfig config = new ChunkStorageConfig(true, 5000, "Hybrid");
        config.setAsyncThreadPoolSize(8);
        config.setMaintenanceIntervalMinutes(60);
        config.setEnableCompression(true);
        return config;
    }
}