// Zero-copy buffer pool for unified memory management
// Provides thread-safe, sharded buffer allocation with zero-copy semantics

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use crate::errors::{RustError, Result};
use crate::memory::pool::common::{MemoryPool, MemoryPoolConfig, MemoryPoolStats};

/// Configuration for the zero-copy buffer pool
#[derive(Debug, Clone)]
pub struct BufferPoolConfig {
    pub max_buffers: usize,
    pub buffer_size: usize,
    pub shard_count: usize,
    pub max_buffers_per_shard: usize,
    pub common_config: MemoryPoolConfig,
}

impl Default for BufferPoolConfig {
    fn default() -> Self {
        Self {
            max_buffers: 1024,
            buffer_size: 64 * 1024, // 64KB default buffer size
            shard_count: 8,
            max_buffers_per_shard: 128,
            common_config: MemoryPoolConfig::default(),
        }
    }
}

impl From<MemoryPoolConfig> for BufferPoolConfig {
    fn from(config: MemoryPoolConfig) -> Self {
        let buffer_size = config.max_size.min(64 * 1024);
        let max_buffers = config.max_size / buffer_size;
        let max_buffers_per_shard = max_buffers / 8;
        
        Self {
            max_buffers,
            buffer_size,
            shard_count: 8,
            max_buffers_per_shard,
            common_config: config,
        }
    }
}

/// Zero-copy buffer with reference counting
#[derive(Debug)]
pub struct ZeroCopyBuffer {
    data: Arc<Vec<u8>>,
    size: usize,
    pool: Arc<BufferPool>,
}

impl ZeroCopyBuffer {
    /// Get immutable reference to buffer data
    pub fn data(&self) -> &[u8] {
        &self.data[..self.size]
    }

    /// Get mutable reference to buffer data (for writing)
    pub fn data_mut(&mut self) -> &mut [u8] {
        let slice = Arc::get_mut(&mut self.data).expect("Buffer is shared, cannot get mutable reference");
        &mut slice[..self.size]
    }

    /// Get buffer capacity
    pub fn capacity(&self) -> usize {
        self.data.len()
    }

    /// Get current buffer size
    pub fn size(&self) -> usize {
        self.size
    }

    /// Resize buffer (within capacity limits)
    pub fn resize(&mut self, new_size: usize) -> Result<()> {
        if new_size > self.capacity() {
            return Err(RustError::BufferError("Resize exceeds buffer capacity".to_string()));
        }
        self.size = new_size;
        Ok(())
    }

    /// Clear buffer contents
    pub fn clear(&mut self) {
        self.size = 0;
    }

    /// Clone buffer data into a new owned Vec
    pub fn to_vec(&self) -> Vec<u8> {
        self.data()[..self.size].to_vec()
    }
}

impl Drop for ZeroCopyBuffer {
    fn drop(&mut self) {
        // Return buffer to pool when dropped
        let _ = self.pool.return_buffer(Arc::clone(&self.data));
    }
}

/// Sharded buffer pool for reduced lock contention
#[derive(Debug)]
struct BufferShard {
    buffers: Mutex<Vec<Arc<Vec<u8>>>>,
    max_buffers: usize,
    buffer_size: usize,
}

impl BufferShard {
    fn new(max_buffers: usize, buffer_size: usize) -> Self {
        let mut buffers = Vec::with_capacity(max_buffers);
        for _ in 0..(max_buffers / 4) { // Pre-allocate 25% of buffers
            buffers.push(Arc::new(vec![0u8; buffer_size]));
        }

        Self {
            buffers: Mutex::new(buffers),
            max_buffers,
            buffer_size,
        }
    }

    fn acquire_buffer(&self) -> Option<Arc<Vec<u8>>> {
        let mut buffers = self.buffers.lock().unwrap();
        buffers.pop()
    }

    fn return_buffer(&self, buffer: Arc<Vec<u8>>) -> bool {
        let mut buffers = self.buffers.lock().unwrap();
        if buffers.len() < self.max_buffers {
            buffers.push(buffer);
            true
        } else {
            false // Pool is full, buffer will be dropped
        }
    }

    fn len(&self) -> usize {
        self.buffers.lock().unwrap().len()
    }

    fn create_new_buffer(&self) -> Arc<Vec<u8>> {
        Arc::new(vec![0u8; self.buffer_size])
    }
}

/// Unified zero-copy buffer pool
#[derive(Debug)]
pub struct BufferPool {
    shards: Vec<Arc<BufferShard>>,
    shard_count: usize,
    config: BufferPoolConfig,
    stats: BufferPoolStats,
    common_stats: MemoryPoolStats,
    logger: PerformanceLogger,
}

/// Buffer pool specific statistics
#[derive(Debug, Default)]
struct BufferPoolStats {
    total_acquired: AtomicUsize,
    total_returned: AtomicUsize,
    total_created: AtomicUsize,
    current_allocated: AtomicUsize,
}

/// Combined statistics for BufferPool (implements MemoryPoolStats)
#[derive(Debug, Clone)]
pub struct CombinedBufferPoolStats {
    pub common_stats: MemoryPoolStats,
    pub buffer_stats: BufferPoolStats,
}

impl MemoryPoolStats for CombinedBufferPoolStats {
    fn allocated_bytes(&self) -> usize {
        self.common_stats.allocated_bytes.load(Ordering::Relaxed)
    }
    
    fn total_allocations(&self) -> usize {
        self.common_stats.total_allocations.load(Ordering::Relaxed)
    }
    
    fn total_deallocations(&self) -> usize {
        self.common_stats.total_deallocations.load(Ordering::Relaxed)
    }
    
    fn peak_allocated_bytes(&self) -> usize {
        self.common_stats.peak_allocated_bytes.load(Ordering::Relaxed)
    }
    
    fn current_usage_ratio(&self) -> f64 {
        self.common_stats.current_usage_ratio
    }
}

impl BufferPool {
    /// Create new buffer pool with configuration
    pub fn new(config: BufferPoolConfig) -> Arc<Self> {
        // Validate configuration
        if config.buffer_size == 0 {
            panic!("Buffer size cannot be zero");
        }
        if config.shard_count == 0 {
            panic!("Shard count cannot be zero");
        }
        
        let mut shards = Vec::with_capacity(config.shard_count);
        let max_per_shard = config.max_buffers / config.shard_count;

        for _ in 0..config.shard_count {
            shards.push(Arc::new(BufferShard::new(max_per_shard, config.buffer_size)));
        }

        Arc::new(Self {
            shards,
            shard_count: config.shard_count,
            config,
            stats: BufferPoolStats::default(),
            common_stats: MemoryPoolStats::default(),
            logger: PerformanceLogger::new(&config.common_config.logger_name),
        })
    }

    /// Get buffer pool statistics
    pub fn get_stats(&self) -> CombinedBufferPoolStats {
        CombinedBufferPoolStats {
            common_stats: self.common_stats.clone(),
            buffer_stats: self.stats.clone(),
        }
    }

    /// Get current memory pressure level (0.0 to 1.0)
    pub fn get_memory_pressure(&self) -> f64 {
        let current_allocated = self.stats.current_allocated.load(Ordering::Relaxed);
        let max_possible = self.config.max_buffers * self.config.buffer_size;
        
        if max_possible == 0 {
            0.0
        } else {
            current_allocated as f64 / max_possible as f64
        }
    }

    /// Get shard index for current thread
    fn get_shard_index(&self) -> usize {
        // Use thread ID hash for shard selection
        let thread_id = std::thread::current().id();
        let hash = format!("{:?}", thread_id).as_bytes().iter().fold(0usize, |acc, &b| acc.wrapping_add(b as usize));
        hash % self.shard_count
    }

    /// Acquire a zero-copy buffer
    pub fn acquire_buffer(self: &Arc<Self>) -> Result<ZeroCopyBuffer> {
        let shard_idx = self.get_shard_index();

        // Try to get buffer from preferred shard first
        if let Some(buffer) = self.shards[shard_idx].acquire_buffer() {
            self.stats.total_acquired.fetch_add(1, Ordering::Relaxed);
            return Ok(ZeroCopyBuffer {
                data: buffer,
                size: 0,
                pool: Arc::clone(self),
            });
        }

        // Try other shards with round-robin
        for i in 1..self.shard_count {
            let idx = (shard_idx + i) % self.shard_count;
            if let Some(buffer) = self.shards[idx].acquire_buffer() {
                self.stats.total_acquired.fetch_add(1, Ordering::Relaxed);
                return Ok(ZeroCopyBuffer {
                    data: buffer,
                    size: 0,
                    pool: Arc::clone(self),
                });
            }
        }

        // Create new buffer if pool is empty
        let new_buffer = self.shards[shard_idx].create_new_buffer();
        self.stats.total_created.fetch_add(1, Ordering::Relaxed);
        self.stats.current_allocated.fetch_add(1, Ordering::Relaxed);

        Ok(ZeroCopyBuffer {
            data: new_buffer,
            size: 0,
            pool: Arc::clone(self),
        })
    }

    /// Return buffer to pool (called automatically by ZeroCopyBuffer drop)
    fn return_buffer(&self, buffer: Arc<Vec<u8>>) -> Result<()> {
        let shard_idx = self.get_shard_index();

        // Try to return to preferred shard first
        if self.shards[shard_idx].return_buffer(Arc::clone(&buffer)) {
            self.stats.total_returned.fetch_add(1, Ordering::Relaxed);
            return Ok(());
        }

        // Try other shards
        for i in 1..self.shard_count {
            let idx = (shard_idx + i) % self.shard_count;
            if self.shards[idx].return_buffer(Arc::clone(&buffer)) {
                self.stats.total_returned.fetch_add(1, Ordering::Relaxed);
                return Ok(());
            }
        }

        // If all shards are full, buffer will be dropped
        self.stats.current_allocated.fetch_sub(1, Ordering::Relaxed);
        Ok(())
    }

    /// Get legacy buffer pool statistics (for backward compatibility)
    pub fn get_legacy_stats(&self) -> BufferPoolStatsSnapshot {
        BufferPoolStatsSnapshot {
            total_acquired: self.stats.total_acquired.load(Ordering::Relaxed),
            total_returned: self.stats.total_returned.load(Ordering::Relaxed),
            total_created: self.stats.total_created.load(Ordering::Relaxed),
            current_allocated: self.stats.current_allocated.load(Ordering::Relaxed),
            total_available: self.shards.iter().map(|s| s.len()).sum(),
        }
    }

    /// Pre-allocate buffers for better performance
    pub fn preallocate_buffers(&self, count: usize) {
        let buffers_per_shard = count / self.shard_count;
        for shard in &self.shards {
            let mut buffers = shard.buffers.lock().unwrap();
            let current_count = buffers.len();
            let needed = buffers_per_shard.saturating_sub(current_count);

            for _ in 0..needed {
                if buffers.len() < shard.max_buffers {
                    buffers.push(Arc::new(vec![0u8; self.config.buffer_size]));
                }
            }
        }
    }
}

/// Statistics snapshot for buffer pool
#[derive(Debug, Clone)]
pub struct BufferPoolStatsSnapshot {
    pub total_acquired: usize,
    pub total_returned: usize,
    pub total_created: usize,
    pub current_allocated: usize,
    pub total_available: usize,
}

// Global buffer pool instance
lazy_static::lazy_static! {
    static ref GLOBAL_BUFFER_POOL: Arc<BufferPool> = BufferPool::new(BufferPoolConfig::default());
}

/// Get global buffer pool instance
pub fn get_global_buffer_pool() -> &'static Arc<BufferPool> {
    &GLOBAL_BUFFER_POOL
}

/// Acquire buffer from global pool
pub fn acquire_buffer() -> Result<ZeroCopyBuffer> {
    get_global_buffer_pool().acquire_buffer()
}

/// Initialize global buffer pool with custom configuration
pub fn init_global_buffer_pool(config: BufferPoolConfig) -> Result<()> {
    // Pre-allocate some buffers for immediate use
    get_global_buffer_pool().preallocate_buffers(config.max_buffers / 4);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_buffer_acquire_release() {
        let pool = BufferPool::new(BufferPoolConfig {
            max_buffers: 10,
            buffer_size: 1024,
            shard_count: 2,
            max_buffers_per_shard: 5,
        });

        // Acquire buffer
        let mut buffer = pool.acquire_buffer().unwrap();
        assert_eq!(buffer.capacity(), 1024);
        assert_eq!(buffer.size(), 0);

        // Write some data
        let data = b"Hello, zero-copy!";
        buffer.data_mut()[..data.len()].copy_from_slice(data);
        buffer.resize(data.len()).unwrap();

        assert_eq!(buffer.size(), data.len());
        assert_eq!(buffer.data(), data);

        // Buffer is automatically returned when dropped
        drop(buffer);

        // Check stats
        let stats = pool.get_stats();
        assert_eq!(stats.total_acquired, 1);
        assert_eq!(stats.total_returned, 1);
    }

    #[test]
    fn test_buffer_pool_stats() {
        let pool = BufferPool::new(BufferPoolConfig::default());

        // Acquire multiple buffers
        let buffers: Vec<_> = (0..5).map(|_| pool.acquire_buffer().unwrap()).collect();

        let stats = pool.get_stats();
        assert_eq!(stats.total_acquired, 5);

        // Drop buffers
        drop(buffers);

        let stats = pool.get_stats();
        assert_eq!(stats.total_returned, 5);
    }
}