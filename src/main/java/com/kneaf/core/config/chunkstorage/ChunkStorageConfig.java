package com.kneaf.core.config.chunkstorage;

import com.kneaf.core.config.core.ConfigurationConstants;
import com.kneaf.core.config.core.ConfigurationProvider;
import com.kneaf.core.config.exception.ConfigurationException;

import java.util.Objects;

/**
 * Immutable configuration class for chunk storage settings.
 */
public final class ChunkStorageConfig implements ConfigurationProvider {

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

    /**
     * Returns a new Builder for constructing ChunkStorageConfig instances.
     *
     * @return a new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Private constructor to enforce immutability (use Builder instead).
     *
     * @param builder the builder to construct from
     * @throws ConfigurationException if validation fails
     */
    private ChunkStorageConfig(Builder builder) throws ConfigurationException {
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

        // Perform validation during construction
        validate();
    }

    @Override
    public void validate() throws ConfigurationException {
        if (cacheCapacity <= 0) {
            throw new ConfigurationException("cacheCapacity must be greater than 0");
        }
        if (asyncThreadpoolSize <= 0) {
            throw new ConfigurationException("asyncThreadpoolSize must be greater than 0");
        }
        if (maintenanceIntervalMinutes <= 0) {
            throw new ConfigurationException("maintenanceIntervalMinutes must be greater than 0");
        }
        if (maxBackupFiles <= 0) {
            throw new ConfigurationException("maxBackupFiles must be greater than 0");
        }
        if (backupRetentionDays <= 0) {
            throw new ConfigurationException("backupRetentionDays must be greater than 0");
        }
        if (evictionPolicy == null || evictionPolicy.trim().isEmpty()) {
            throw new ConfigurationException("evictionPolicy must be specified");
        }
        if (backupPath == null || backupPath.trim().isEmpty()) {
            throw new ConfigurationException("backupPath must be specified");
        }
        if (databaseType == null || databaseType.trim().isEmpty()) {
            throw new ConfigurationException("databaseType must be specified");
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the cache capacity.
     *
     * @return the cache capacity
     */
    public int getCacheCapacity() {
        return cacheCapacity;
    }

    /**
     * Returns the eviction policy.
     *
     * @return the eviction policy
     */
    public String getEvictionPolicy() {
        return evictionPolicy;
    }

    /**
     * Returns the async thread pool size.
     *
     * @return the async thread pool size
     */
    public int getAsyncThreadpoolSize() {
        return asyncThreadpoolSize;
    }

    /**
     * Returns whether async operations are enabled.
     *
     * @return true if async operations are enabled, false otherwise
     */
    public boolean isEnableAsyncOperations() {
        return enableAsyncOperations;
    }

    /**
     * Returns the maintenance interval in minutes.
     *
     * @return the maintenance interval in minutes
     */
    public long getMaintenanceIntervalMinutes() {
        return maintenanceIntervalMinutes;
    }

    /**
     * Returns whether backups are enabled.
     *
     * @return true if backups are enabled, false otherwise
     */
    public boolean isEnableBackups() {
        return enableBackups;
    }

    /**
     * Returns the backup path.
     *
     * @return the backup path
     */
    public String getBackupPath() {
        return backupPath;
    }

    /**
     * Returns whether checksums are enabled.
     *
     * @return true if checksums are enabled, false otherwise
     */
    public boolean isEnableChecksums() {
        return enableChecksums;
    }

    /**
     * Returns whether compression is enabled.
     *
     * @return true if compression is enabled, false otherwise
     */
    public boolean isEnableCompression() {
        return enableCompression;
    }

    /**
     * Returns the maximum number of backup files.
     *
     * @return the maximum number of backup files
     */
    public int getMaxBackupFiles() {
        return maxBackupFiles;
    }

    /**
     * Returns the backup retention days.
     *
     * @return the backup retention days
     */
    public long getBackupRetentionDays() {
        return backupRetentionDays;
    }

    /**
     * Returns the database type.
     *
     * @return the database type
     */
    public String getDatabaseType() {
        return databaseType;
    }

    /**
     * Returns whether to use the Rust database.
     *
     * @return true if using Rust database, false otherwise
     */
    public boolean isUseRustDatabase() {
        return useRustDatabase;
    }

    /**
     * Builder class for constructing immutable ChunkStorageConfig instances.
     */
    public static final class Builder {

        private boolean enabled = ConfigurationConstants.DEFAULT_ENABLED;
        private int cacheCapacity = ConfigurationConstants.DEFAULT_CACHE_CAPACITY;
        private String evictionPolicy = ConfigurationConstants.DEFAULT_EVICTION_POLICY;
        private int asyncThreadpoolSize = ConfigurationConstants.DEFAULT_ASYNC_THREAD_POOL_SIZE;
        private boolean enableAsyncOperations = ConfigurationConstants.DEFAULT_ENABLE_ASYNC_OPERATIONS;
        private long maintenanceIntervalMinutes = ConfigurationConstants.DEFAULT_MAINTENANCE_INTERVAL_MINUTES;
        private boolean enableBackups = ConfigurationConstants.DEFAULT_ENABLE_BACKUPS;
        private String backupPath = ConfigurationConstants.DEFAULT_BACKUP_PATH;
        private boolean enableChecksums = ConfigurationConstants.DEFAULT_ENABLE_CHECKSUMS;
        private boolean enableCompression = ConfigurationConstants.DEFAULT_ENABLE_COMPRESSION;
        private int maxBackupFiles = ConfigurationConstants.DEFAULT_MAX_BACKUP_FILES;
        private long backupRetentionDays = ConfigurationConstants.DEFAULT_BACKUP_RETENTION_DAYS;
        private String databaseType = ConfigurationConstants.DEFAULT_DATABASE_TYPE;
        private boolean useRustDatabase = ConfigurationConstants.DEFAULT_USE_RUST_DATABASE;

        /**
         * Sets whether the configuration is enabled.
         *
         * @param enabled whether the configuration is enabled
         * @return this Builder for method chaining
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * Sets the cache capacity.
         *
         * @param cacheCapacity the cache capacity
         * @return this Builder for method chaining
         */
        public Builder cacheCapacity(int cacheCapacity) {
            this.cacheCapacity = cacheCapacity;
            return this;
        }

        /**
         * Sets the eviction policy.
         *
         * @param evictionPolicy the eviction policy
         * @return this Builder for method chaining
         */
        public Builder evictionPolicy(String evictionPolicy) {
            this.evictionPolicy = evictionPolicy;
            return this;
        }

        /**
         * Sets the async thread pool size.
         *
         * @param asyncThreadpoolSize the async thread pool size
         * @return this Builder for method chaining
         */
        public Builder asyncThreadpoolSize(int asyncThreadpoolSize) {
            this.asyncThreadpoolSize = asyncThreadpoolSize;
            return this;
        }

        /**
         * Sets whether async operations are enabled.
         *
         * @param enableAsyncOperations whether async operations are enabled
         * @return this Builder for method chaining
         */
        public Builder enableAsyncOperations(boolean enableAsyncOperations) {
            this.enableAsyncOperations = enableAsyncOperations;
            return this;
        }

        /**
         * Sets the maintenance interval in minutes.
         *
         * @param maintenanceIntervalMinutes the maintenance interval in minutes
         * @return this Builder for method chaining
         */
        public Builder maintenanceIntervalMinutes(long maintenanceIntervalMinutes) {
            this.maintenanceIntervalMinutes = maintenanceIntervalMinutes;
            return this;
        }

        /**
         * Sets whether backups are enabled.
         *
         * @param enableBackups whether backups are enabled
         * @return this Builder for method chaining
         */
        public Builder enableBackups(boolean enableBackups) {
            this.enableBackups = enableBackups;
            return this;
        }

        /**
         * Sets the backup path.
         *
         * @param backupPath the backup path
         * @return this Builder for method chaining
         */
        public Builder backupPath(String backupPath) {
            this.backupPath = backupPath;
            return this;
        }

        /**
         * Sets whether checksums are enabled.
         *
         * @param enableChecksums whether checksums are enabled
         * @return this Builder for method chaining
         */
        public Builder enableChecksums(boolean enableChecksums) {
            this.enableChecksums = enableChecksums;
            return this;
        }

        /**
         * Sets whether compression is enabled.
         *
         * @param enableCompression whether compression is enabled
         * @return this Builder for method chaining
         */
        public Builder enableCompression(boolean enableCompression) {
            this.enableCompression = enableCompression;
            return this;
        }

        /**
         * Sets the maximum number of backup files.
         *
         * @param maxBackupFiles the maximum number of backup files
         * @return this Builder for method chaining
         */
        public Builder maxBackupFiles(int maxBackupFiles) {
            this.maxBackupFiles = maxBackupFiles;
            return this;
        }

        /**
         * Sets the backup retention days.
         *
         * @param backupRetentionDays the backup retention days
         * @return this Builder for method chaining
         */
        public Builder backupRetentionDays(long backupRetentionDays) {
            this.backupRetentionDays = backupRetentionDays;
            return this;
        }

        /**
         * Sets the database type.
         *
         * @param databaseType the database type
         * @return this Builder for method chaining
         */
        public Builder databaseType(String databaseType) {
            this.databaseType = databaseType;
            return this;
        }

        /**
         * Sets whether to use the Rust database.
         *
         * @param useRustDatabase whether to use the Rust database
         * @return this Builder for method chaining
         */
        public Builder useRustDatabase(boolean useRustDatabase) {
            this.useRustDatabase = useRustDatabase;
            return this;
        }

        /**
         * Builds and returns a new ChunkStorageConfig instance.
         *
         * @return the constructed ChunkStorageConfig
         * @throws ConfigurationException if validation fails
         */
        public ChunkStorageConfig build() throws ConfigurationException {
            return new ChunkStorageConfig(this);
        }
    }

    /**
     * Returns a string representation of this ChunkStorageConfig.
     *
     * @return a string representation
     */
    @Override
    public String toString() {
        return "ChunkStorageConfig{" +
                "enabled=" + enabled +
                ", cacheCapacity=" + cacheCapacity +
                ", evictionPolicy='" + evictionPolicy + '\'' +
                ", asyncThreadpoolSize=" + asyncThreadpoolSize +
                ", enableAsyncOperations=" + enableAsyncOperations +
                ", maintenanceIntervalMinutes=" + maintenanceIntervalMinutes +
                ", enableBackups=" + enableBackups +
                ", backupPath='" + backupPath + '\'' +
                ", enableChecksums=" + enableChecksums +
                ", enableCompression=" + enableCompression +
                ", maxBackupFiles=" + maxBackupFiles +
                ", backupRetentionDays=" + backupRetentionDays +
                ", databaseType='" + databaseType + '\'' +
                ", useRustDatabase=" + useRustDatabase +
                '}';
    }

    /**
     * Indicates whether some other object is "equal to" this one.
     *
     * @param o the reference object with which to compare
     * @return true if this object is the same as the o argument; false otherwise
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChunkStorageConfig that = (ChunkStorageConfig) o;
        return enabled == that.enabled &&
                cacheCapacity == that.cacheCapacity &&
                asyncThreadpoolSize == that.asyncThreadpoolSize &&
                enableAsyncOperations == that.enableAsyncOperations &&
                maintenanceIntervalMinutes == that.maintenanceIntervalMinutes &&
                enableBackups == that.enableBackups &&
                enableChecksums == that.enableChecksums &&
                enableCompression == that.enableCompression &&
                maxBackupFiles == that.maxBackupFiles &&
                backupRetentionDays == that.backupRetentionDays &&
                useRustDatabase == that.useRustDatabase &&
                evictionPolicy.equals(that.evictionPolicy) &&
                backupPath.equals(that.backupPath) &&
                databaseType.equals(that.databaseType);
    }

    /**
     * Returns a hash code value for the object.
     *
     * @return a hash code value for this object
     */
    @Override
    public int hashCode() {
        return Objects.hash(enabled, cacheCapacity, evictionPolicy, asyncThreadpoolSize, enableAsyncOperations,
                maintenanceIntervalMinutes, enableBackups, backupPath, enableChecksums, enableCompression,
                maxBackupFiles, backupRetentionDays, databaseType, useRustDatabase);
    }
}