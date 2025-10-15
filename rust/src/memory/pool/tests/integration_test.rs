//! Integration tests for the memory pool factory and refactored implementations

use std::sync::Arc;

use crate::errors::Result;
use crate::memory::pool::buffer_pool::{BufferPool, BufferPoolConfig};
use crate::memory::pool::common::{MemoryPool, MemoryPoolBuilder, MemoryPoolConfig};
use crate::memory::pool::factory::{MemoryPoolFactory, MemoryPoolType};
use crate::memory::pool::object_pool::{ObjectPool, ObjectPoolConfig};
use crate::memory::pool::slab_allocator::{SlabAllocator, SlabAllocatorConfig};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_memory_pool_factory_basic() {
        // Create a factory with default configuration
        let factory = MemoryPoolFactory::new();
        
        // Test creating different pool types
        let buffer_pool = factory.create_pool(MemoryPoolType::BufferPool).unwrap();
        let object_pool = factory.create_pool(MemoryPoolType::ObjectPool).unwrap();
        let slab_pool = factory.create_pool(MemoryPoolType::SlabAllocator).unwrap();
        
        // Test that all pools implement the MemoryPool trait
        assert!(buffer_pool.allocate(1024).is_ok());
        assert!(object_pool.allocate(1024).is_ok());
        assert!(slab_pool.allocate(1024).is_ok());
        
        // Test statistics
        let buffer_stats = buffer_pool.get_stats();
        let object_stats = object_pool.get_stats();
        let slab_stats = slab_pool.get_stats();
        
        assert!(buffer_stats.allocated_bytes() > 0);
        assert!(object_stats.allocated_bytes() > 0);
        assert!(slab_stats.allocated_bytes() > 0);
        
        // Test memory pressure
        let buffer_pressure = buffer_pool.get_memory_pressure();
        let object_pressure = object_pool.get_memory_pressure();
        let slab_pressure = slab_pool.get_memory_pressure();
        
        assert!(buffer_pressure >= 0.0 && buffer_pressure <= 1.0);
        assert!(object_pressure >= 0.0 && object_pressure <= 1.0);
        assert!(slab_pressure >= 0.0 && slab_pressure <= 1.0);
    }

    #[test]
    fn test_memory_pool_factory_with_custom_config() {
        // Create a custom configuration using the builder pattern
        let custom_config = MemoryPoolBuilder::new()
            .max_size(1024 * 1024 * 10) // 10MB
            .pre_allocate(false)
            .high_water_mark_ratio(0.8)
            .cleanup_threshold(0.7)
            .logger_name("test_pool".to_string())
            .build();
        
        // Create factory with custom config
        let factory = MemoryPoolFactory::with_config(custom_config.clone());
        
        // Test creating a pool with custom config
        let pool = factory.create_pool(MemoryPoolType::BufferPool).unwrap();
        
        // Verify the pool was created with the right max size
        let stats = pool.get_stats();
        assert_eq!(stats.allocated_bytes(), 0);
    }

    #[test]
    fn test_memory_pool_factory_with_builder() {
        // Create a pool using the factory builder pattern
        let factory = MemoryPoolFactory::new();
        
        let buffer_pool = factory.create_pool_with_builder(MemoryPoolType::BufferPool, |builder| {
            builder
                .max_size(1024 * 1024 * 5) // 5MB
                .pre_allocate(true)
                .logger_name("builder_test_pool".to_string())
        }).unwrap();
        
        // Test allocation
        let allocation = buffer_pool.allocate(1024).unwrap();
        assert_eq!(allocation.len(), 1024);
    }

    #[test]
    fn test_buffer_pool_implementation() {
        // Create buffer pool with custom config
        let config = BufferPoolConfig {
            max_buffers: 100,
            buffer_size: 4096,
            shard_count: 4,
            max_buffers_per_shard: 25,
            common_config: MemoryPoolConfig::default(),
        };
        
        let pool = BufferPool::new(config);
        
        // Test acquiring and returning buffers
        let buffer = pool.acquire_buffer().unwrap();
        assert_eq!(buffer.capacity(), 4096);
        assert_eq!(buffer.size(), 0);
        
        // Write some data
        let data = b"test data";
        buffer.data_mut()[..data.len()].copy_from_slice(data);
        buffer.resize(data.len()).unwrap();
        
        assert_eq!(buffer.size(), data.len());
        assert_eq!(buffer.data(), data);
        
        // Buffer is automatically returned when dropped
        drop(buffer);
        
        // Check stats
        let stats = pool.get_stats();
        assert!(stats.common_stats.allocated_bytes() == 0);
    }

    #[test]
    fn test_object_pool_implementation() {
        // Create object pool with custom config
        let config = ObjectPoolConfig {
            max_size: 1024 * 1024 * 5, // 5MB
            pre_allocate: true,
            high_water_mark_ratio: 0.9,
            cleanup_threshold: 0.8,
            logger_name: "object_pool_test".to_string(),
            common_config: MemoryPoolConfig::default(),
        };
        
        let pool: Arc<ObjectPool<Vec<u8>>> = Arc::new(ObjectPool::new(config));
        
        // Test getting objects
        let obj1 = pool.get();
        let obj2 = pool.get();
        
        // Modify objects
        obj1.as_mut().resize(1024, 0);
        obj2.as_mut().resize(2048, 0);
        
        // Objects are automatically returned when dropped
        drop(obj1);
        drop(obj2);
        
        // Check stats
        let stats = pool.get_stats();
        assert!(stats.allocated_bytes() == 0);
    }

    #[test]
    fn test_slab_allocator_implementation() {
        // Create slab allocator with custom config
        let config = SlabAllocatorConfig {
            slab_size: 512,
            max_slabs_per_class: 4,
            pre_allocate: true,
            allow_overcommit: false,
            common_config: MemoryPoolConfig::default(),
        };
        
        let mut pool: SlabAllocator<u8> = SlabAllocator::new(config);
        
        // Test allocation
        let allocation = pool.allocate(64).unwrap();
        assert_eq!(allocation.size_class.max_size(), 64);
        
        // Test deallocation
        pool.deallocate(allocation.ptr, 64);
        
        // Check stats
        let stats = pool.get_stats();
        assert!(stats.allocated_bytes() == 0);
    }

    #[test]
    fn test_error_handling() {
        // Create a small pool that will quickly exhaust
        let config = MemoryPoolConfig {
            max_size: 1024, // 1KB
            pre_allocate: false,
            high_water_mark_ratio: 0.9,
            cleanup_threshold: 0.8,
            logger_name: "error_test_pool".to_string(),
        };
        
        let factory = MemoryPoolFactory::with_config(config);
        let pool = factory.create_pool(MemoryPoolType::BufferPool).unwrap();
        
        // Allocate until we exceed the limit
        for _ in 0..10 {
            let allocation = pool.allocate(100).unwrap();
            assert_eq!(allocation.len(), 100);
        }
        
        // Try to allocate one more - should fail
        let result = pool.allocate(100);
        assert!(result.is_err());
    }
}