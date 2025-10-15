//! Memory pool factory with builder pattern for unified pool creation

use std::sync::Arc;

use crate::errors::{RustError, Result};
use crate::memory::pool::buffer_pool::{BufferPool, BufferPoolConfig as BufferPoolConfigImpl};
use crate::memory::pool::object_pool::{ObjectPool, ObjectPoolConfig as ObjectPoolConfigImpl};
use crate::memory::pool::slab_allocator::{SlabAllocator, SlabAllocatorConfig as SlabAllocatorConfigImpl};
use crate::memory::pool::common::{MemoryPool, MemoryPoolConfig, MemoryPoolBuilder};

/// Enum representing different types of memory pools
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MemoryPoolType {
    /// Zero-copy buffer pool for high-performance data transfer
    BufferPool,
    
    /// Generic object pooling with LRU eviction
    ObjectPool,
    
    /// Fixed-size block allocation with size classes
    SlabAllocator,
    
    /// Lightweight single-threaded pool
    LightweightPool,
    
    /// Multi-level memory allocation with size classes
    HierarchicalPool,
    
    /// Memory swapping with compression
    SwapPool,
}

/// Memory pool factory for creating different types of memory pools
#[derive(Debug)]
pub struct MemoryPoolFactory {
    /// Common configuration for all pools created by this factory
    common_config: MemoryPoolConfig,
}

impl MemoryPoolFactory {
    /// Create a new memory pool factory with default configuration
    pub fn new() -> Self {
        Self {
            common_config: MemoryPoolConfig::default(),
        }
    }
    
    /// Create a factory with custom configuration
    pub fn with_config(config: MemoryPoolConfig) -> Self {
        Self { common_config: config }
    }
    
    /// Create a builder for memory pool configuration
    pub fn builder() -> MemoryPoolBuilder {
        MemoryPoolBuilder::new()
    }
    
    /// Create a memory pool of the specified type with default configuration
    pub fn create_pool(&self, pool_type: MemoryPoolType) -> Result<Box<dyn MemoryPool>> {
        self.create_pool_with_config(pool_type, None)
    }
    
    /// Create a memory pool of the specified type with custom configuration
    pub fn create_pool_with_config(
        &self,
        pool_type: MemoryPoolType,
        custom_config: Option<&MemoryPoolConfig>,
    ) -> Result<Box<dyn MemoryPool>> {
        let config = custom_config.unwrap_or(&self.common_config);
        
        match pool_type {
            MemoryPoolType::BufferPool => {
                let buffer_config = BufferPoolConfig {
                    max_buffers: config.max_size / config.max_size.min(64 * 1024), // Default to 64KB buffers
                    buffer_size: config.max_size.min(64 * 1024),
                    shard_count: 8,
                    max_buffers_per_shard: config.max_size / (8 * 64 * 1024),
                };
                
                let pool = BufferPool::new(buffer_config);
                Ok(Box::new(pool))
            }
            
            MemoryPoolType::ObjectPool => {
                let object_config = ObjectPoolConfig {
                    max_size: config.max_size,
                    pre_allocate: config.pre_allocate,
                    high_water_mark_ratio: config.high_water_mark_ratio,
                    cleanup_threshold: config.cleanup_threshold,
                    logger_name: config.logger_name.clone(),
                };
                
                let pool = ObjectPool::new(object_config);
                Ok(Box::new(pool))
            }
            
            MemoryPoolType::SlabAllocator => {
                let slab_config = SlabAllocatorConfig {
                    slab_size: 1024, // 1024 objects per slab
                    max_slabs_per_class: 8,
                    pre_allocate: config.pre_allocate,
                    allow_overcommit: false,
                };
                
                let pool = SlabAllocator::new(slab_config);
                Ok(Box::new(pool))
            }
            
            MemoryPoolType::LightweightPool => {
                // Implementation would go here
                unimplemented!("LightweightPool not yet implemented")
            }
            
            MemoryPoolType::HierarchicalPool => {
                // Implementation would go here
                unimplemented!("HierarchicalPool not yet implemented")
            }
            
            MemoryPoolType::SwapPool => {
                // Implementation would go here
                unimplemented!("SwapPool not yet implemented")
            }
        }
    }
    
    /// Create a memory pool using a builder pattern
    pub fn create_pool_with_builder<F>(&self, pool_type: MemoryPoolType, builder: F) -> Result<Box<dyn MemoryPool>>
    where
        F: FnOnce(MemoryPoolBuilder) -> MemoryPoolBuilder,
    {
        let config = builder(MemoryPoolBuilder::new()).build();
        self.create_pool_with_config(pool_type, Some(&config))
    }
}

/// Trait for memory pool specific configurations
pub trait PoolSpecificConfig {
    /// Convert to common memory pool config
    fn to_common_config(&self) -> MemoryPoolConfig;
}

/// Buffer pool specific configuration
#[derive(Debug, Clone)]
pub struct BufferPoolConfig {
    pub max_buffers: usize,
    pub buffer_size: usize,
    pub shard_count: usize,
    pub max_buffers_per_shard: usize,
}

impl Default for BufferPoolConfig {
    fn default() -> Self {
        Self {
            max_buffers: 1024,
            buffer_size: 64 * 1024,
            shard_count: 8,
            max_buffers_per_shard: 128,
        }
    }
}

impl PoolSpecificConfig for BufferPoolConfig {
    fn to_common_config(&self) -> MemoryPoolConfig {
        MemoryPoolConfig {
            max_size: self.max_buffers * self.buffer_size,
            pre_allocate: true,
            high_water_mark_ratio: 0.9,
            cleanup_threshold: 0.8,
            logger_name: "buffer_pool".to_string(),
        }
    }
}

/// Object pool specific configuration
#[derive(Debug, Clone)]
pub struct ObjectPoolConfig {
    pub max_size: usize,
    pub pre_allocate: bool,
    pub high_water_mark_ratio: f64,
    pub cleanup_threshold: f64,
    pub logger_name: String,
}

impl Default for ObjectPoolConfig {
    fn default() -> Self {
        Self {
            max_size: 1024 * 1024 * 50, // 50MB default
            pre_allocate: true,
            high_water_mark_ratio: 0.9,
            cleanup_threshold: 0.8,
            logger_name: "object_pool".to_string(),
        }
    }
}

impl PoolSpecificConfig for ObjectPoolConfig {
    fn to_common_config(&self) -> MemoryPoolConfig {
        MemoryPoolConfig {
            max_size: self.max_size,
            pre_allocate: self.pre_allocate,
            high_water_mark_ratio: self.high_water_mark_ratio,
            cleanup_threshold: self.cleanup_threshold,
            logger_name: self.logger_name.clone(),
        }
    }
}

/// Slab allocator specific configuration
#[derive(Debug, Clone)]
pub struct SlabAllocatorConfig {
    pub slab_size: usize,
    pub max_slabs_per_class: usize,
    pub pre_allocate: bool,
    pub allow_overcommit: bool,
}

impl Default for SlabAllocatorConfig {
    fn default() -> Self {
        Self {
            slab_size: 1024,
            max_slabs_per_class: 8,
            pre_allocate: true,
            allow_overcommit: false,
        }
    }
}

impl PoolSpecificConfig for SlabAllocatorConfig {
    fn to_common_config(&self) -> MemoryPoolConfig {
        MemoryPoolConfig {
            max_size: 1024 * 1024 * 100, // 100MB default
            pre_allocate: self.pre_allocate,
            high_water_mark_ratio: 0.9,
            cleanup_threshold: 0.8,
            logger_name: "slab_allocator".to_string(),
        }
    }
}