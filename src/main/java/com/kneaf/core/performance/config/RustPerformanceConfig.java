package com.kneaf.core.performance.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.Objects;

/**
 * Configuration for Rust performance monitoring system.
 * Loads settings from configuration files and provides access to performance parameters.
 */
public final class RustPerformanceConfig {
    private static final String DEFAULT_CONFIG_PATH = "/config/rustperf.toml";
    private static final String DEFAULT_PROPERTIES_PATH = "/config/kneaf-performance.properties";
    
    // Performance configuration
    private final boolean enableAggressiveOptimizations;
    private final int maxConcurrentOperations;
    private final int batchSize;
    private final int prefetchDistance;
    private final int cacheSizeMb;
    
    // Ultra optimization settings
    private final boolean enableSafetyChecks;
    private final boolean enableBoundsChecking;
    private final boolean enableNullChecks;
    private final boolean enableAggressiveInlining;
    private final boolean enableLoopUnrolling;
    private final boolean enableVectorization;
    private final boolean enableMemoryPooling;
    private final int memoryPoolSizeMb;
    private final boolean enableSimd;
    private final int simdBatchSize;
    private final boolean enableBranchHints;
    private final boolean enableCachePrefetching;
    
    // Batch processing configuration
    private final int minBatchSize;
    private final int maxBatchSize;
    private final long adaptiveTimeoutMs;
    private final int maxPendingBatches;
    private final int workerThreads;
    private final boolean enableAdaptiveSizing;
    
    // Singleton instance
    private static volatile RustPerformanceConfig instance;

    /**
     * Get the singleton instance of RustPerformanceConfig.
     *
     * @return The singleton instance
     */
    public static RustPerformanceConfig getInstance() {
        if (instance == null) {
            synchronized (RustPerformanceConfig.class) {
                if (instance == null) {
                    instance = loadConfiguration();
                }
            }
        }
        return instance;
    }

    /**
     * Load configuration from properties files.
     */
    private static RustPerformanceConfig loadConfiguration() {
        // Start with default values
        var builder = new Builder()
                // Performance configuration
                .enableAggressiveOptimizations(true)
                .maxConcurrentOperations(32)
                .batchSize(64)
                .prefetchDistance(8)
                .cacheSizeMb(512)
                
                // Ultra optimization settings
                .enableSafetyChecks(true)
                .enableBoundsChecking(true)
                .enableNullChecks(true)
                .enableAggressiveInlining(false)
                .enableLoopUnrolling(false)
                .enableVectorization(false)
                .enableMemoryPooling(false)
                .memoryPoolSizeMb(256)
                .enableSimd(false)
                .simdBatchSize(16)
                .enableBranchHints(false)
                .enableCachePrefetching(false)
                
                // Batch processing configuration
                .minBatchSize(50)
                .maxBatchSize(500)
                .adaptiveTimeoutMs(1)
                .maxPendingBatches(10)
                .workerThreads(8)
                .enableAdaptiveSizing(false);

        // Try to load from TOML configuration first
        try {
            loadFromTomlConfig(builder);
        } catch (Exception e) {
            // Fall back to properties if TOML loading fails
            loadFromProperties(builder);
        }

        return builder.build();
    }

    /**
     * Load configuration from TOML file.
     * Note: In a real implementation, you would use a TOML parser like Jackson or SnakeYAML.
     * For this example, we'll simulate loading from a properties file.
     */
    private static void loadFromTomlConfig(Builder builder) {
        // For demonstration purposes, we'll just log that we're loading from TOML
        // In a real implementation, you would parse the TOML file here
        System.out.println("Loading Rust performance configuration from TOML file");
    }

    /**
     * Load configuration from properties file.
     */
    private static void loadFromProperties(Builder builder) {
        try (InputStream input = RustPerformanceConfig.class.getResourceAsStream(DEFAULT_PROPERTIES_PATH)) {
            if (input == null) {
                System.out.println("Unable to find " + DEFAULT_PROPERTIES_PATH);
                return;
            }
            
            Properties properties = new Properties();
            properties.load(input);
            
            // Performance configuration
            builder.enableAggressiveOptimizations(Boolean.parseBoolean(properties.getProperty("performance.enable_aggressive_optimizations",
                    String.valueOf(builder.enableAggressiveOptimizations))));
            builder.maxConcurrentOperations(Integer.parseInt(properties.getProperty("performance.max_concurrent_operations",
                    String.valueOf(builder.maxConcurrentOperations))));
            builder.batchSize(Integer.parseInt(properties.getProperty("performance.batch_size",
                    String.valueOf(builder.batchSize))));
            builder.prefetchDistance(Integer.parseInt(properties.getProperty("performance.prefetch_distance",
                    String.valueOf(builder.prefetchDistance))));
            builder.cacheSizeMb(Integer.parseInt(properties.getProperty("performance.cache_size_mb",
                    String.valueOf(builder.cacheSizeMb))));
            
            // Ultra optimization settings
            builder.enableSafetyChecks(Boolean.parseBoolean(properties.getProperty("ultra_optimizations.enable_safety_checks",
                    String.valueOf(builder.enableSafetyChecks))));
            builder.enableBoundsChecking(Boolean.parseBoolean(properties.getProperty("ultra_optimizations.enable_bounds_checking",
                    String.valueOf(builder.enableBoundsChecking))));
            builder.enableNullChecks(Boolean.parseBoolean(properties.getProperty("ultra_optimizations.enable_null_checks",
                    String.valueOf(builder.enableNullChecks))));
            builder.enableAggressiveInlining(Boolean.parseBoolean(properties.getProperty("ultra_optimizations.enable_aggressive_inlining",
                    String.valueOf(builder.enableAggressiveInlining))));
            builder.enableLoopUnrolling(Boolean.parseBoolean(properties.getProperty("ultra_optimizations.enable_loop_unrolling",
                    String.valueOf(builder.enableLoopUnrolling))));
            builder.enableVectorization(Boolean.parseBoolean(properties.getProperty("ultra_optimizations.enable_vectorization",
                    String.valueOf(builder.enableVectorization))));
            builder.enableMemoryPooling(Boolean.parseBoolean(properties.getProperty("ultra_optimizations.enable_memory_pooling",
                    String.valueOf(builder.enableMemoryPooling))));
            builder.memoryPoolSizeMb(Integer.parseInt(properties.getProperty("ultra_optimizations.pool_size_mb",
                    String.valueOf(builder.memoryPoolSizeMb))));
            builder.enableSimd(Boolean.parseBoolean(properties.getProperty("ultra_optimizations.enable_simd",
                    String.valueOf(builder.enableSimd))));
            builder.simdBatchSize(Integer.parseInt(properties.getProperty("ultra_optimizations.simd_batch_size",
                    String.valueOf(builder.simdBatchSize))));
            builder.enableBranchHints(Boolean.parseBoolean(properties.getProperty("ultra_optimizations.enable_branch_hints",
                    String.valueOf(builder.enableBranchHints))));
            builder.enableCachePrefetching(Boolean.parseBoolean(properties.getProperty("ultra_optimizations.enable_cache_prefetching",
                    String.valueOf(builder.enableCachePrefetching))));
            
            // Batch processing configuration
            builder.minBatchSize(Integer.parseInt(properties.getProperty("batch.min_batch_size",
                    String.valueOf(builder.minBatchSize))));
            builder.maxBatchSize(Integer.parseInt(properties.getProperty("batch.max_batch_size",
                    String.valueOf(builder.maxBatchSize))));
            builder.adaptiveTimeoutMs(Long.parseLong(properties.getProperty("batch.adaptive_timeout_ms",
                    String.valueOf(builder.adaptiveTimeoutMs))));
            builder.maxPendingBatches(Integer.parseInt(properties.getProperty("batch.max_pending_batches",
                    String.valueOf(builder.maxPendingBatches))));
            builder.workerThreads(Integer.parseInt(properties.getProperty("batch.worker_threads",
                    String.valueOf(builder.workerThreads))));
            builder.enableAdaptiveSizing(Boolean.parseBoolean(properties.getProperty("batch.enable_adaptive_sizing",
                    String.valueOf(builder.enableAdaptiveSizing))));
            
            System.out.println("Successfully loaded Rust performance configuration from properties");
        } catch (IOException ex) {
            System.err.println("Error loading Rust performance configuration: " + ex.getMessage());
        }
    }

    // Getters for all configuration properties
    
    public boolean isEnableAggressiveOptimizations() {
        return enableAggressiveOptimizations;
    }

    public int getMaxConcurrentOperations() {
        return maxConcurrentOperations;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int getPrefetchDistance() {
        return prefetchDistance;
    }

    public int getCacheSizeMb() {
        return cacheSizeMb;
    }

    public boolean isEnableSafetyChecks() {
        return enableSafetyChecks;
    }

    public boolean isEnableBoundsChecking() {
        return enableBoundsChecking;
    }

    public boolean isEnableNullChecks() {
        return enableNullChecks;
    }

    public boolean isEnableAggressiveInlining() {
        return enableAggressiveInlining;
    }

    public boolean isEnableLoopUnrolling() {
        return enableLoopUnrolling;
    }

    public boolean isEnableVectorization() {
        return enableVectorization;
    }

    public boolean isEnableMemoryPooling() {
        return enableMemoryPooling;
    }

    public int getMemoryPoolSizeMb() {
        return memoryPoolSizeMb;
    }

    public boolean isEnableSimd() {
        return enableSimd;
    }

    public int getSimdBatchSize() {
        return simdBatchSize;
    }

    public boolean isEnableBranchHints() {
        return enableBranchHints;
    }

    public boolean isEnableCachePrefetching() {
        return enableCachePrefetching;
    }

    public int getMinBatchSize() {
        return minBatchSize;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public long getAdaptiveTimeoutMs() {
        return adaptiveTimeoutMs;
    }

    public int getMaxPendingBatches() {
        return maxPendingBatches;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public boolean isEnableAdaptiveSizing() {
        return enableAdaptiveSizing;
    }

    /**
     * Builder for RustPerformanceConfig.
     */
    public static final class Builder {
        // Performance configuration
        private boolean enableAggressiveOptimizations;
        private int maxConcurrentOperations;
        private int batchSize;
        private int prefetchDistance;
        private int cacheSizeMb;
        
        // Ultra optimization settings
        private boolean enableSafetyChecks;
        private boolean enableBoundsChecking;
        private boolean enableNullChecks;
        private boolean enableAggressiveInlining;
        private boolean enableLoopUnrolling;
        private boolean enableVectorization;
        private boolean enableMemoryPooling;
        private int memoryPoolSizeMb;
        private boolean enableSimd;
        private int simdBatchSize;
        private boolean enableBranchHints;
        private boolean enableCachePrefetching;
        
        // Batch processing configuration
        private int minBatchSize;
        private int maxBatchSize;
        private long adaptiveTimeoutMs;
        private int maxPendingBatches;
        private int workerThreads;
        private boolean enableAdaptiveSizing;

        /**
         * Build a RustPerformanceConfig instance.
         *
         * @return A new RustPerformanceConfig instance
         */
        public RustPerformanceConfig build() {
            return new RustPerformanceConfig(this);
        }

        // Performance configuration setters
        
        public Builder enableAggressiveOptimizations(boolean enableAggressiveOptimizations) {
            this.enableAggressiveOptimizations = enableAggressiveOptimizations;
            return this;
        }

        public Builder maxConcurrentOperations(int maxConcurrentOperations) {
            this.maxConcurrentOperations = maxConcurrentOperations;
            return this;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder prefetchDistance(int prefetchDistance) {
            this.prefetchDistance = prefetchDistance;
            return this;
        }

        public Builder cacheSizeMb(int cacheSizeMb) {
            this.cacheSizeMb = cacheSizeMb;
            return this;
        }

        // Ultra optimization settings setters
        
        public Builder enableSafetyChecks(boolean enableSafetyChecks) {
            this.enableSafetyChecks = enableSafetyChecks;
            return this;
        }

        public Builder enableBoundsChecking(boolean enableBoundsChecking) {
            this.enableBoundsChecking = enableBoundsChecking;
            return this;
        }

        public Builder enableNullChecks(boolean enableNullChecks) {
            this.enableNullChecks = enableNullChecks;
            return this;
        }

        public Builder enableAggressiveInlining(boolean enableAggressiveInlining) {
            this.enableAggressiveInlining = enableAggressiveInlining;
            return this;
        }

        public Builder enableLoopUnrolling(boolean enableLoopUnrolling) {
            this.enableLoopUnrolling = enableLoopUnrolling;
            return this;
        }

        public Builder enableVectorization(boolean enableVectorization) {
            this.enableVectorization = enableVectorization;
            return this;
        }

        public Builder enableMemoryPooling(boolean enableMemoryPooling) {
            this.enableMemoryPooling = enableMemoryPooling;
            return this;
        }

        public Builder memoryPoolSizeMb(int memoryPoolSizeMb) {
            this.memoryPoolSizeMb = memoryPoolSizeMb;
            return this;
        }

        public Builder enableSimd(boolean enableSimd) {
            this.enableSimd = enableSimd;
            return this;
        }

        public Builder simdBatchSize(int simdBatchSize) {
            this.simdBatchSize = simdBatchSize;
            return this;
        }

        public Builder enableBranchHints(boolean enableBranchHints) {
            this.enableBranchHints = enableBranchHints;
            return this;
        }

        public Builder enableCachePrefetching(boolean enableCachePrefetching) {
            this.enableCachePrefetching = enableCachePrefetching;
            return this;
        }

        // Batch processing configuration setters
        
        public Builder minBatchSize(int minBatchSize) {
            this.minBatchSize = minBatchSize;
            return this;
        }

        public Builder maxBatchSize(int maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
            return this;
        }

        public Builder adaptiveTimeoutMs(long adaptiveTimeoutMs) {
            this.adaptiveTimeoutMs = adaptiveTimeoutMs;
            return this;
        }

        public Builder maxPendingBatches(int maxPendingBatches) {
            this.maxPendingBatches = maxPendingBatches;
            return this;
        }

        public Builder workerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
            return this;
        }

        public Builder enableAdaptiveSizing(boolean enableAdaptiveSizing) {
            this.enableAdaptiveSizing = enableAdaptiveSizing;
            return this;
        }
    }

    /**
     * Private constructor to enforce builder pattern.
     *
     * @param builder The builder containing configuration values
     */
    private RustPerformanceConfig(Builder builder) {
        this.enableAggressiveOptimizations = builder.enableAggressiveOptimizations;
        this.maxConcurrentOperations = builder.maxConcurrentOperations;
        this.batchSize = builder.batchSize;
        this.prefetchDistance = builder.prefetchDistance;
        this.cacheSizeMb = builder.cacheSizeMb;
        this.enableSafetyChecks = builder.enableSafetyChecks;
        this.enableBoundsChecking = builder.enableBoundsChecking;
        this.enableNullChecks = builder.enableNullChecks;
        this.enableAggressiveInlining = builder.enableAggressiveInlining;
        this.enableLoopUnrolling = builder.enableLoopUnrolling;
        this.enableVectorization = builder.enableVectorization;
        this.enableMemoryPooling = builder.enableMemoryPooling;
        this.memoryPoolSizeMb = builder.memoryPoolSizeMb;
        this.enableSimd = builder.enableSimd;
        this.simdBatchSize = builder.simdBatchSize;
        this.enableBranchHints = builder.enableBranchHints;
        this.enableCachePrefetching = builder.enableCachePrefetching;
        this.minBatchSize = builder.minBatchSize;
        this.maxBatchSize = builder.maxBatchSize;
        this.adaptiveTimeoutMs = builder.adaptiveTimeoutMs;
        this.maxPendingBatches = builder.maxPendingBatches;
        this.workerThreads = builder.workerThreads;
        this.enableAdaptiveSizing = builder.enableAdaptiveSizing;
    }

    /**
     * Create a new builder for RustPerformanceConfig.
     *
     * @return A new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RustPerformanceConfig that = (RustPerformanceConfig) o;
        return enableAggressiveOptimizations == that.enableAggressiveOptimizations &&
                maxConcurrentOperations == that.maxConcurrentOperations &&
                batchSize == that.batchSize &&
                prefetchDistance == that.prefetchDistance &&
                cacheSizeMb == that.cacheSizeMb &&
                enableSafetyChecks == that.enableSafetyChecks &&
                enableBoundsChecking == that.enableBoundsChecking &&
                enableNullChecks == that.enableNullChecks &&
                enableAggressiveInlining == that.enableAggressiveInlining &&
                enableLoopUnrolling == that.enableLoopUnrolling &&
                enableVectorization == that.enableVectorization &&
                enableMemoryPooling == that.enableMemoryPooling &&
                memoryPoolSizeMb == that.memoryPoolSizeMb &&
                enableSimd == that.enableSimd &&
                simdBatchSize == that.simdBatchSize &&
                enableBranchHints == that.enableBranchHints &&
                enableCachePrefetching == that.enableCachePrefetching &&
                minBatchSize == that.minBatchSize &&
                maxBatchSize == that.maxBatchSize &&
                adaptiveTimeoutMs == that.adaptiveTimeoutMs &&
                maxPendingBatches == that.maxPendingBatches &&
                workerThreads == that.workerThreads &&
                enableAdaptiveSizing == that.enableAdaptiveSizing;
    }

    @Override
    public int hashCode() {
        return Objects.hash(enableAggressiveOptimizations, maxConcurrentOperations, batchSize, prefetchDistance, cacheSizeMb,
                enableSafetyChecks, enableBoundsChecking, enableNullChecks, enableAggressiveInlining, enableLoopUnrolling,
                enableVectorization, enableMemoryPooling, memoryPoolSizeMb, enableSimd, simdBatchSize, enableBranchHints,
                enableCachePrefetching, minBatchSize, maxBatchSize, adaptiveTimeoutMs, maxPendingBatches, workerThreads,
                enableAdaptiveSizing);
    }

    @Override
    public String toString() {
        return "RustPerformanceConfig{" +
                "enableAggressiveOptimizations=" + enableAggressiveOptimizations +
                ", maxConcurrentOperations=" + maxConcurrentOperations +
                ", batchSize=" + batchSize +
                ", prefetchDistance=" + prefetchDistance +
                ", cacheSizeMb=" + cacheSizeMb +
                ", enableSafetyChecks=" + enableSafetyChecks +
                ", enableBoundsChecking=" + enableBoundsChecking +
                ", enableNullChecks=" + enableNullChecks +
                ", enableAggressiveInlining=" + enableAggressiveInlining +
                ", enableLoopUnrolling=" + enableLoopUnrolling +
                ", enableVectorization=" + enableVectorization +
                ", enableMemoryPooling=" + enableMemoryPooling +
                ", memoryPoolSizeMb=" + memoryPoolSizeMb +
                ", enableSimd=" + enableSimd +
                ", simdBatchSize=" + simdBatchSize +
                ", enableBranchHints=" + enableBranchHints +
                ", enableCachePrefetching=" + enableCachePrefetching +
                ", minBatchSize=" + minBatchSize +
                ", maxBatchSize=" + maxBatchSize +
                ", adaptiveTimeoutMs=" + adaptiveTimeoutMs +
                ", maxPendingBatches=" + maxPendingBatches +
                ", workerThreads=" + workerThreads +
                ", enableAdaptiveSizing=" + enableAdaptiveSizing +
                '}';
    }
}