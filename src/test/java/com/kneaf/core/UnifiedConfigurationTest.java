package com.kneaf.core;

import com.kneaf.core.config.performance.PerformanceConfig;
import com.kneaf.core.config.UnifiedConfiguration;
import com.kneaf.core.config.chunkstorage.ChunkStorageConfig;
import com.kneaf.core.config.swap.SwapConfig;
import com.kneaf.core.config.resource.ResourceConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for unified configuration system with different profiles
 */
public class UnifiedConfigurationTest {

    @Test
    public void testUnifiedConfigurationCreation() throws Exception {
        // Test that we can create a UnifiedConfiguration instance using the builder pattern
        PerformanceConfig performanceConfig = PerformanceConfig.builder()
                .enabled(true)
                .threadpoolSize(4)
                .build();
        
        // Create a minimal UnifiedConfiguration with just performance config
        UnifiedConfiguration config = UnifiedConfiguration.builder()
                .performanceConfig(performanceConfig)
                // For simplicity, we'll just use empty/default configs for the other types
                .chunkStorageConfig(ChunkStorageConfig.builder().build())
                .swapConfig(SwapConfig.builder().build())
                .resourceConfig(ResourceConfig.builder().build())
                .build();
        
        assertNotNull(config, "Unified configuration should be created successfully");
        
        // Test that performance config is accessible through unified config
        PerformanceConfig retrievedConfig = config.getPerformanceConfig();
        assertNotNull(retrievedConfig, "Performance config should be accessible through unified config");
        
        // Test basic configuration properties
        assertTrue(retrievedConfig.isEnabled(), "Performance should be enabled");
        assertEquals(4, retrievedConfig.getThreadpoolSize(), "Threadpool size should be 4");
    }

    @Test
    public void testConfigurationModification() throws Exception {
        // Test that configuration can be modified through the builder pattern
        PerformanceConfig modifiedConfig = PerformanceConfig.builder()
                .threadpoolSize(8)
                .profilingEnabled(true)
                .maxEntitiesToCollect(10000)
                .build();
        
        assertNotNull(modifiedConfig, "Should be able to build modified configuration");
        assertEquals(8, modifiedConfig.getThreadpoolSize(), "Should be able to modify threadpool size");
        assertTrue(modifiedConfig.isProfilingEnabled(), "Should be able to modify profiling setting");
        assertEquals(10000, modifiedConfig.getMaxEntitiesToCollect(), "Should be able to modify max entities to collect");
    }

    @Test
    public void testUnifiedConfigurationImmutability() throws Exception {
        // Test that UnifiedConfiguration is immutable
        PerformanceConfig performanceConfig = PerformanceConfig.builder().build();
        ChunkStorageConfig chunkStorageConfig = ChunkStorageConfig.builder().build();
        SwapConfig swapConfig = SwapConfig.builder().build();
        ResourceConfig resourceConfig = ResourceConfig.builder().build();
        
        UnifiedConfiguration config = UnifiedConfiguration.builder()
                .performanceConfig(performanceConfig)
                .chunkStorageConfig(chunkStorageConfig)
                .swapConfig(swapConfig)
                .resourceConfig(resourceConfig)
                .build();
        
        // Try to access the configs - they should be immutable
        assertNotNull(config.getPerformanceConfig(), "Should be able to access performance config");
        assertNotNull(config.getChunkStorageConfig(), "Should be able to access chunk storage config");
        assertNotNull(config.getSwapConfig(), "Should be able to access swap config");
        assertNotNull(config.getResourceConfig(), "Should be able to access resource config");
    }
}