// Memory Pool Factory Comprehensive Test Suite
// This test suite verifies all factory methods, builder patterns, and DRY compliance
// for the MemoryPoolFactory implementation

use super::super::common::{MemoryPool, MemoryPoolBuilder, MemoryPoolConfig, MemoryPoolStats};
use super::super::factory::{
    BufferPoolConfig, MemoryPoolFactory, MemoryPoolType, ObjectPoolConfig, SlabAllocatorConfig,
};
use crate::errors::{RustError, Result};
use std::sync::atomic::Ordering;

#[cfg(test)]
mod tests {
    use super::*;

    ///////////////////////////////////////////////////////////////////////////
    // Basic Factory Functionality Tests
    ///////////////////////////////////////////////////////////////////////////

    #[test]
    fn test_factory_creation() {
        let factory = MemoryPoolFactory::new();
        assert!(factory.common_config.max_size > 0);
        assert!(factory.common_config.pre_allocate);
    }

    #[test]
    fn test_factory_with_config() {
        let custom_config = MemoryPoolConfig {
            max_size: 1024 * 1024 * 50, // 50MB
            pre_allocate: false,
            high_water_mark_ratio: 0.8,
            cleanup_threshold: 0.7,
            logger_name: "test_pool".to_string(),
        };

        let factory = MemoryPoolFactory::with_config(custom_config.clone());
        assert_eq!(factory.common_config.max_size, custom_config.max_size);
        assert_eq!(factory.common_config.pre_allocate, custom_config.pre_allocate);
        assert_eq!(
            factory.common_config.high_water_mark_ratio,
            custom_config.high_water_mark_ratio
        );
        assert_eq!(
            factory.common_config.cleanup_threshold,
            custom_config.cleanup_threshold
        );
        assert_eq!(
            factory.common_config.logger_name,
            custom_config.logger_name
        );
    }

    ///////////////////////////////////////////////////////////////////////////
    // Pool Creation Tests
    ///////////////////////////////////////////////////////////////////////////

    #[test]
    fn test_buffer_pool_creation() {
        let factory = MemoryPoolFactory::new();
        let pool_result = factory.create_pool(MemoryPoolType::BufferPool);
        
        assert!(pool_result.is_ok());
        let pool = pool_result.unwrap();
        
        // Test basic memory operations
        let allocation_result = pool.allocate(1024);
        assert!(allocation_result.is_ok());
        let buffer = allocation_result.unwrap();
        assert_eq!(buffer.len(), 1024);
        
        // Test stats collection
        let stats = pool.get_stats();
        assert!(stats.allocated_bytes.load(Ordering::SeqCst) > 0);
        assert!(stats.total_allocations.load(Ordering::SeqCst) > 0);
    }

    #[test]
    fn test_object_pool_creation() {
        let factory = MemoryPoolFactory::new();
        let pool_result = factory.create_pool(MemoryPoolType::ObjectPool);
        
        assert!(pool_result.is_ok());
        let pool = pool_result.unwrap();
        
        // Test basic memory operations
        let allocation_result = pool.allocate(512);
        assert!(allocation_result.is_ok());
        let buffer = allocation_result.unwrap();
        assert_eq!(buffer.len(), 512);
        
        // Test stats collection
        let stats = pool.get_stats();
        assert!(stats.allocated_bytes.load(Ordering::SeqCst) > 0);
    }

    #[test]
    fn test_slab_allocator_creation() {
        let factory = MemoryPoolFactory::new();
        let pool_result = factory.create_pool(MemoryPoolType::SlabAllocator);
        
        assert!(pool_result.is_ok());
        let pool = pool_result.unwrap();
        
        // Test basic memory operations
        let allocation_result = pool.allocate(256);
        assert!(allocation_result.is_ok());
        let buffer = allocation_result.unwrap();
        assert_eq!(buffer.len(), 256);
        
        // Test stats collection
        let stats = pool.get_stats();
        assert!(stats.allocated_bytes.load(Ordering::SeqCst) > 0);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Custom Configuration Tests
    ///////////////////////////////////////////////////////////////////////////

    #[test]
    fn test_buffer_pool_with_custom_config() {
        let custom_config = BufferPoolConfig {
            max_buffers: 2048,
            buffer_size: 128 * 1024, // 128KB
            shard_count: 16,
            max_buffers_per_shard: 128,
        };

        let common_config = custom_config.to_common_config();
        let factory = MemoryPoolFactory::with_config(common_config);
        
        let pool_result = factory.create_pool(MemoryPoolType::BufferPool);
        assert!(pool_result.is_ok());
    }

    #[test]
    fn test_object_pool_with_custom_config() {
        let custom_config = ObjectPoolConfig {
            max_size: 1024 * 1024 * 20, // 20MB
            pre_allocate: true,
            high_water_mark_ratio: 0.85,
            cleanup_threshold: 0.75,
            logger_name: "custom_object_pool".to_string(),
        };

        let common_config = custom_config.to_common_config();
        let factory = MemoryPoolFactory::with_config(common_config);
        
        let pool_result = factory.create_pool(MemoryPoolType::ObjectPool);
        assert!(pool_result.is_ok());
    }

    #[test]
    fn test_slab_allocator_with_custom_config() {
        let custom_config = SlabAllocatorConfig {
            slab_size: 2048, // 2048 objects per slab
            max_slabs_per_class: 16,
            pre_allocate: true,
            allow_overcommit: true,
        };

        let common_config = custom_config.to_common_config();
        let factory = MemoryPoolFactory::with_config(common_config);
        
        let pool_result = factory.create_pool(MemoryPoolType::SlabAllocator);
        assert!(pool_result.is_ok());
    }

    ///////////////////////////////////////////////////////////////////////////
    // Builder Pattern Tests
    ///////////////////////////////////////////////////////////////////////////

    #[test]
    fn test_factory_with_builder() {
        let factory = MemoryPoolFactory::new();
        
        let custom_pool = factory.create_pool_with_builder(MemoryPoolType::BufferPool, |builder| {
            builder
                .max_size(1024 * 1024 * 30) // 30MB
                .pre_allocate(false)
                .high_water_mark_ratio(0.85)
                .cleanup_threshold(0.75)
                .logger_name("builder_test_pool".to_string())
        });

        assert!(custom_pool.is_ok());
        let pool = custom_pool.unwrap();
        
        // Test that the pool uses the custom configuration
        let stats = pool.get_stats();
        assert!(stats.allocated_bytes.load(Ordering::SeqCst) == 0);
    }

    #[test]
    fn test_memory_pool_builder_creation() {
        let builder = MemoryPoolFactory::builder();
        assert!(builder.config.max_size > 0);
        
        let config = builder
            .max_size(1024 * 1024 * 10) // 10MB
            .pre_allocate(false)
            .high_water_mark_ratio(0.95)
            .cleanup_threshold(0.85)
            .logger_name("test_builder".to_string())
            .build();
        
        assert_eq!(config.max_size, 1024 * 1024 * 10);
        assert!(!config.pre_allocate);
        assert_eq!(config.high_water_mark_ratio, 0.95);
        assert_eq!(config.cleanup_threshold, 0.85);
        assert_eq!(config.logger_name, "test_builder");
    }

    ///////////////////////////////////////////////////////////////////////////
    // Error Handling Tests
    ///////////////////////////////////////////////////////////////////////////

    #[test]
    fn test_allocation_error_handling() {
        let factory = MemoryPoolFactory::new();
        let pool_result = factory.create_pool(MemoryPoolType::BufferPool);
        
        assert!(pool_result.is_ok());
        let pool = pool_result.unwrap();
        
        // Test allocation of zero size (should fail)
        let zero_allocation = pool.allocate(0);
        assert!(zero_allocation.is_err());
        if let Err(err) = zero_allocation {
            assert!(err.to_string().contains("Allocation size cannot be zero"));
        }
    }

    #[test]
    fn test_exceeded_size_error_handling() {
        let small_config = MemoryPoolConfig {
            max_size: 100, // Very small pool
            pre_allocate: true,
            high_water_mark_ratio: 0.9,
            cleanup_threshold: 0.8,
            logger_name: "small_pool".to_string(),
        };

        let factory = MemoryPoolFactory::with_config(small_config);
        let pool_result = factory.create_pool(MemoryPoolType::BufferPool);
        
        assert!(pool_result.is_ok());
        let pool = pool_result.unwrap();
        
        // Test allocation exceeding max size (should fail)
        let large_allocation = pool.allocate(200);
        assert!(large_allocation.is_err());
        if let Err(err) = large_allocation {
            assert!(err.to_string().contains("exceeds max pool size"));
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // DRY Principle Compliance Tests
    ///////////////////////////////////////////////////////////////////////////

    #[test]
    fn test_config_conversion_consistency() {
        // Test that all pool-specific configs convert to common config consistently
        let buffer_config = BufferPoolConfig::default();
        let object_config = ObjectPoolConfig::default();
        let slab_config = SlabAllocatorConfig::default();
        
        let buffer_common = buffer_config.to_common_config();
        let object_common = object_config.to_common_config();
        let slab_common = slab_config.to_common_config();
        
        // All should have pre_allocate = true by default
        assert!(buffer_common.pre_allocate);
        assert!(object_common.pre_allocate);
        assert!(slab_common.pre_allocate);
        
        // All should have reasonable high_water_mark_ratio
        assert!(buffer_common.high_water_mark_ratio > 0.0);
        assert!(buffer_common.high_water_mark_ratio <= 1.0);
        assert!(object_common.high_water_mark_ratio > 0.0);
        assert!(object_common.high_water_mark_ratio <= 1.0);
        assert!(slab_common.high_water_mark_ratio > 0.0);
        assert!(slab_common.high_water_mark_ratio <= 1.0);
    }

    #[test]
    fn test_trait_implementation_consistency() {
        // Test that all pool types implement the MemoryPool trait consistently
        let factory = MemoryPoolFactory::new();
        
        let buffer_pool = factory.create_pool(MemoryPoolType::BufferPool).unwrap();
        let object_pool = factory.create_pool(MemoryPoolType::ObjectPool).unwrap();
        let slab_pool = factory.create_pool(MemoryPoolType::SlabAllocator).unwrap();
        
        // All should implement allocate/deallocate/get_stats/get_memory_pressure
        let buffer_alloc = buffer_pool.allocate(100);
        let object_alloc = object_pool.allocate(100);
        let slab_alloc = slab_pool.allocate(100);
        
        assert!(buffer_alloc.is_ok());
        assert!(object_alloc.is_ok());
        assert!(slab_alloc.is_ok());
        
        let buffer_stats = buffer_pool.get_stats();
        let object_stats = object_pool.get_stats();
        let slab_stats = slab_pool.get_stats();
        
        assert!(buffer_stats.allocated_bytes.load(Ordering::SeqCst) > 0);
        assert!(object_stats.allocated_bytes.load(Ordering::SeqCst) > 0);
        assert!(slab_stats.allocated_bytes.load(Ordering::SeqCst) > 0);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Backward Compatibility Tests
    ///////////////////////////////////////////////////////////////////////////

    #[test]
    fn test_backward_compatibility() {
        // Test that the factory can create pools that work with existing interfaces
        let factory = MemoryPoolFactory::new();
        
        // Create different pool types
        let buffer_pool = factory.create_pool(MemoryPoolType::BufferPool).unwrap();
        let object_pool = factory.create_pool(MemoryPoolType::ObjectPool).unwrap();
        let slab_pool = factory.create_pool(MemoryPoolType::SlabAllocator).unwrap();
        
        // Test that they all implement the common MemoryPool trait
        let pools: Vec<Box<dyn MemoryPool>> = vec![buffer_pool, object_pool, slab_pool];
        
        for pool in pools {
            // All should support basic operations
            let alloc = pool.allocate(512);
            assert!(alloc.is_ok());
            
            let stats = pool.get_stats();
            assert!(stats.allocated_bytes.load(Ordering::SeqCst) > 0);
            
            let pressure = pool.get_memory_pressure();
            assert!(pressure >= 0.0);
            assert!(pressure <= 1.0);
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Performance Tests
    ///////////////////////////////////////////////////////////////////////////

    #[test]
    fn test_factory_performance_baseline() {
        use std::time::Instant;
        
        let factory = MemoryPoolFactory::new();
        
        // Test creation performance
        let start = Instant::now();
        for _ in 0..10 {
            let _pool = factory.create_pool(MemoryPoolType::BufferPool).unwrap();
        }
        let creation_time = start.elapsed();
        
        // Test allocation performance
        let pool = factory.create_pool(MemoryPoolType::BufferPool).unwrap();
        let start = Instant::now();
        for _ in 0..100 {
            let _alloc = pool.allocate(1024).unwrap();
        }
        let allocation_time = start.elapsed();
        
        // These are just baseline tests - in real scenarios you'd compare against thresholds
        println!("Factory creation time (10 pools): {:?}", creation_time);
        println!("Allocation time (100x 1KB): {:?}", allocation_time);
        
        // Basic sanity check - should complete in reasonable time
        assert!(creation_time.as_millis() < 1000);
        assert!(allocation_time.as_millis() < 500);
    }
}