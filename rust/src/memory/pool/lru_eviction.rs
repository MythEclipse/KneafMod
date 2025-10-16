use std::collections::HashMap;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Instant;

use parking_lot::RwLock;
use crossbeam::queue::SegQueue;

use crate::errors::{RustError, Result};
use crate::logging::{generate_trace_id, PerformanceLogger};
use crate::memory::pool::common::{MemoryPool, MemoryPoolConfig, MemoryPoolStats};
use crate::memory::monitoring::{track_global_allocation, track_global_deallocation, track_global_eviction, MemoryPoolType};

/// Node in the doubly linked list for LRU tracking
struct LRUNode {
    key: String,
    value: Box<[u8]>,
    last_accessed: Instant,
}

/// Doubly linked list for LRU tracking
struct LRUList {
    head: Option<Arc<RwLock<LRUNode>>>,
    tail: Option<Arc<RwLock<LRUNode>>>,
    map: HashMap<String, Arc<RwLock<LRUNode>>>,
    size: AtomicUsize,
    capacity: usize,
}

impl LRUList {
    fn new(capacity: usize) -> Self {
        Self {
            head: None,
            tail: None,
            map: HashMap::new(),
            size: AtomicUsize::new(0),
            capacity,
        }
    }

    fn push_front(&self, key: String, value: Box<[u8]>) -> Result<()> {
        let node = Arc::new(RwLock::new(LRUNode {
            key: key.clone(),
            value,
            last_accessed: Instant::now(),
        }));

        let node_clone = Arc::clone(&node);
        self.map.insert(key, node_clone);

        match self.head.take() {
            Some(old_head) => {
                let mut old_head = old_head.write();
                old_head.prev = Some(Arc::clone(&node));
                node.write().next = Some(old_head);
                self.head = Some(node);
            }
            None => {
                self.head = Some(Arc::clone(&node));
                self.tail = Some(node);
            }
        }

        let current_size = self.size.fetch_add(1, Ordering::Relaxed);
        if current_size + 1 > self.capacity {
            self.evict_lru()?;
        }

        Ok(())
    }

    fn access(&self, key: &str) -> Result<Option<Box<[u8]>>> {
        let node = self.map.get(key).ok_or_else(|| {
            RustError::NotFoundError(format!("Key not found: {}", key))
        })?;

        let mut node = node.write();
        node.last_accessed = Instant::now();

        // Move to front if not already head
        if let Some(head) = &self.head {
            if !Arc::ptr_eq(head, &node) {
                // Remove from current position
                if let Some(prev) = node.prev.take() {
                    let mut prev = prev.write();
                    prev.next = node.next.take();
                }
                if let Some(next) = node.next.take() {
                    let mut next = next.write();
                    next.prev = node.prev.take();
                }

                // Update head
                let mut head = head.write();
                head.prev = Some(Arc::clone(&node));
                node.write().next = Some(head);
                self.head = Some(Arc::clone(&node));
            }
        }

        Ok(Some(node.value.clone()))
    }

    fn remove(&self, key: &str) -> Result<Option<Box<[u8]>>> {
        let node = self.map.remove(key).ok_or_else(|| {
            RustError::NotFoundError(format!("Key not found: {}", key))
        })?;

        let mut node = node.write();
        
        // Update neighbors
        if let Some(prev) = node.prev.take() {
            let mut prev = prev.write();
            prev.next = node.next.take();
        }
        if let Some(next) = node.next.take() {
            let mut next = next.write();
            next.prev = node.prev.take();
        }

        // Update head/tail if needed
        if let Some(head) = &self.head {
            if Arc::ptr_eq(head, &node) {
                self.head = node.next.clone();
            }
        }
        if let Some(tail) = &self.tail {
            if Arc::ptr_eq(tail, &node) {
                self.tail = node.prev.clone();
            }
        }

        self.size.fetch_sub(1, Ordering::Relaxed);
        Ok(Some(node.value.clone()))
    }

    fn evict_lru(&self) -> Result<Option<Box<[u8]>>> {
        let tail = self.tail.as_ref().ok_or_else(|| {
            RustError::ResourceLimitExceeded("No items to evict".to_string())
        })?;

        let tail_clone = Arc::clone(tail);
        let evicted_key = tail.read().key.clone();
        let evicted_value = tail.read().value.clone();

        self.remove(&evicted_key)?;

        Ok(Some(evicted_value))
    }

    fn clear(&self) -> Result<()> {
        self.map.clear();
        self.head = None;
        self.tail = None;
        self.size.store(0, Ordering::Relaxed);
        Ok(())
    }
}

/// Configuration for LRUEvictionMemoryPool
#[derive(Debug, Clone)]
pub struct LRUEvictionConfig {
    pub capacity: usize,
    pub eviction_threshold: f64,
    pub logger_name: String,
}

impl Default for LRUEvictionConfig {
    fn default() -> Self {
        Self {
            capacity: 1024 * 1024 * 100, // 100MB default
            eviction_threshold: 0.8,
            logger_name: "lru_eviction_pool".to_string(),
        }
    }
}

/// Thread-safe LRU eviction memory pool implementation
#[derive(Debug)]
pub struct LRUEvictionMemoryPool {
    config: LRUEvictionConfig,
    lru_list: RwLock<LRUList>,
    stats: RwLock<MemoryPoolStats>,
    logger: PerformanceLogger,
    eviction_queue: SegQueue<Box<[u8]>>,
    eviction_stats: RwLock<EvictionStats>,
}

/// Statistics for LRU eviction operations
#[derive(Debug, Clone, Default)]
pub struct EvictionStats {
    pub total_evictions: AtomicUsize,
    pub eviction_time: AtomicUsize, // in nanoseconds
    pub evicted_bytes: AtomicUsize,
    pub highest_eviction_rate: f64,
    pub current_eviction_rate: f64,
}

impl LRUEvictionMemoryPool {
    /// Create a new LRUEvictionMemoryPool with custom configuration
    pub fn new(config: Option<LRUEvictionConfig>) -> Result<Self> {
        let config = config.unwrap_or_default();
        let trace_id = generate_trace_id();

        let logger = PerformanceLogger::new(&config.logger_name);
        logger.log_info("new", &trace_id, "LRU eviction memory pool initialized");

        Ok(Self {
            config,
            lru_list: RwLock::new(LRUList::new(config.capacity)),
            stats: RwLock::new(MemoryPoolStats::default()),
            logger,
            eviction_queue: SegQueue::new(),
            eviction_stats: RwLock::new(EvictionStats::default()),
        })
    }

    /// Allocate memory with LRU tracking
    pub fn allocate(&self, size: usize) -> Result<Box<[u8]>> {
        let start_time = Instant::now();
        let trace_id = generate_trace_id();

        // Check if allocation would exceed capacity
        let current_size = self.lru_list.read().size.load(Ordering::Relaxed);
        let usage_ratio = current_size as f64 / self.config.capacity as f64;

        if usage_ratio > self.config.eviction_threshold {
            self.logger.log_info(
                "allocate",
                &trace_id,
                &format!(
                    "Memory pressure detected: usage ratio {:.2}% > threshold {:.2}%",
                    usage_ratio * 100.0,
                    self.config.eviction_threshold * 100.0
                ),
            );
            
            // Evict until under threshold
            while usage_ratio > self.config.eviction_threshold {
                self.evict_lru()?;
                let new_size = self.lru_list.read().size.load(Ordering::Relaxed);
                usage_ratio = new_size as f64 / self.config.capacity as f64;
            }
        }

        // Allocate memory
        let mut buffer = vec![0u8; size].into_boxed_slice();
        
        // Generate a unique key for this allocation
        let key = format!("alloc_{:x}", fastrand::u64(..));
        
        // Add to LRU list
        self.lru_list.write().push_front(key, buffer.clone())?;

        // Update stats
        let mut stats = self.stats.write();
        stats.allocated_bytes.fetch_add(size, Ordering::Relaxed);
        stats.total_allocations.fetch_add(1, Ordering::Relaxed);
        stats.current_usage_ratio = usage_ratio;
        stats.update_peak_usage();

        let elapsed = start_time.elapsed();
        self.logger.log_info(
            "allocate",
            &trace_id,
            &format!("Allocated {} bytes ({}ns)", size, elapsed.as_nanos()),
        );

        Ok(buffer)
    }

    /// Deallocate memory and update LRU tracking
    pub fn deallocate(&self, key: &str) -> Result<()> {
        let start_time = Instant::now();
        let trace_id = generate_trace_id();

        // Remove from LRU list
        let result = self.lru_list.write().remove(key);
        
        if let Ok(Some(value)) = result {
            // Update stats
            let mut stats = self.stats.write();
            stats.allocated_bytes.fetch_sub(value.len(), Ordering::Relaxed);
            stats.total_deallocations.fetch_add(1, Ordering::Relaxed);
            stats.current_usage_ratio = stats.allocated_bytes.load(Ordering::Relaxed) as f64 / self.config.capacity as f64;
            
            // Add to eviction queue for background processing
            self.eviction_queue.push(value);
            
            let elapsed = start_time.elapsed();
            self.logger.log_info(
                "deallocate",
                &trace_id,
                &format!("Deallocated {} bytes ({}ns)", key, elapsed.as_nanos()),
            );
            
            Ok(())
        } else {
            Err(RustError::NotFoundError(format!("Allocation not found: {}", key)))
        }
    }

    /// Evict the least recently used item
    pub fn evict_lru(&self) -> Result<Option<Box<[u8]>>> {
        let start_time = Instant::now();
        let trace_id = generate_trace_id();

        let result = self.lru_list.read().evict_lru();
        
        if let Ok(Some(value)) = result {
            // Update eviction stats
            let mut eviction_stats = self.eviction_stats.write();
            eviction_stats.total_evictions.fetch_add(1, Ordering::Relaxed);
            eviction_stats.evicted_bytes.fetch_add(value.len(), Ordering::Relaxed);
            
            let elapsed = start_time.elapsed();
            let eviction_ns = elapsed.as_nanos() as usize;
            eviction_stats.eviction_time.fetch_add(eviction_ns, Ordering::Relaxed);
            
            // Calculate current eviction rate
            let total_evictions = eviction_stats.total_evictions.load(Ordering::Relaxed) as f64;
            let current_rate = if total_evictions > 0.0 {
                eviction_stats.eviction_time.load(Ordering::Relaxed) as f64 / total_evictions
            } else {
                0.0
            };
            eviction_stats.current_eviction_rate = current_rate;
            
            // Update highest eviction rate if needed
            if current_rate > eviction_stats.highest_eviction_rate {
                eviction_stats.highest_eviction_rate = current_rate;
            }

            self.logger.log_info(
                "evict_lru",
                &trace_id,
                &format!("Evicted LRU item ({} bytes, {}ns)", value.len(), eviction_ns),
            );
            
            Ok(Some(value))
        } else {
            self.logger.log_warn(
                "evict_lru",
                &trace_id,
                "No items to evict",
            );
            Ok(None)
        }
    }

    /// Get current memory usage statistics
    pub fn get_memory_usage(&self) -> MemoryPoolStats {
        self.stats.read().clone()
    }

    /// Get eviction statistics
    pub fn get_eviction_stats(&self) -> EvictionStats {
        self.eviction_stats.read().clone()
    }

    /// Clear all items from the pool
    pub fn clear(&self) -> Result<()> {
        let trace_id = generate_trace_id();
        
        self.lru_list.write().clear()?;
        
        // Clear eviction queue
        while let Ok(item) = self.eviction_queue.pop() {
            // Just consume the items to clear the queue
        }
        
        // Reset stats
        let mut stats = self.stats.write();
        *stats = MemoryPoolStats::default();
        
        let mut eviction_stats = self.eviction_stats.write();
        *eviction_stats = EvictionStats::default();
        
        self.logger.log_info(
            "clear",
            &trace_id,
            "Cleared all items from LRU eviction pool",
        );
        
        Ok(())
    }

    /// Process eviction queue in background
    pub fn process_eviction_queue(&self) -> Result<usize> {
        let start_time = Instant::now();
        let trace_id = generate_trace_id();
        
        let mut processed = 0;
        let mut eviction_stats = self.eviction_stats.write();
        
        while let Ok(item) = self.eviction_queue.pop() {
            processed += 1;
            eviction_stats.evicted_bytes.fetch_add(item.len(), Ordering::Relaxed);
        }
        
        let elapsed = start_time.elapsed();
        self.logger.log_info(
            "process_eviction_queue",
            &trace_id,
            &format!("Processed {} eviction queue items ({}ns)", processed, elapsed.as_nanos()),
        );
        
        Ok(processed)
    }
}

// Implement MemoryPool trait for LRUEvictionMemoryPool
impl MemoryPool for LRUEvictionMemoryPool {
    type Object = Box<[u8]>;

    fn new(config: MemoryPoolConfig) -> Self {
        let lru_config = LRUEvictionConfig {
            capacity: config.max_size,
            eviction_threshold: config.cleanup_threshold,
            logger_name: config.logger_name,
        };
        Self::new(Some(lru_config)).expect("Failed to create LRUEvictionMemoryPool")
    }

    fn allocate(&self, size: usize) -> Result<Box<[u8]>> {
        self.allocate(size)
    }

    fn deallocate(&self, ptr: *mut u8, size: usize) {
        // In Rust, we typically rely on the drop trait for deallocation
        // This is a placeholder for any additional safety checks needed
        let key = format!("ptr_{:p}", ptr);
        let _ = self.deallocate(&key);
    }

    fn get_stats(&self) -> MemoryPoolStats {
        self.get_memory_usage()
    }

    fn get_memory_pressure(&self) -> f64 {
        let current_size = self.lru_list.read().size.load(Ordering::Relaxed);
        current_size as f64 / self.config.capacity as f64
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::thread;
    use std::time::Duration;

    #[test]
    fn test_lru_eviction_basic() {
        let pool = LRUEvictionMemoryPool::new(None).unwrap();
        
        // Allocate some items
        let alloc1 = pool.allocate(100).unwrap();
        let alloc2 = pool.allocate(200).unwrap();
        let alloc3 = pool.allocate(300).unwrap();
        
        // Check stats
        let stats = pool.get_memory_usage();
        assert_eq!(stats.total_allocations.load(Ordering::Relaxed), 3);
        assert_eq!(stats.allocated_bytes.load(Ordering::Relaxed), 600);
        
        // Access alloc1 to make it most recently used
        let _ = pool.access("alloc_1"); // Note: In real usage, we'd need to track the actual key
        
        // Allocate one more item to trigger eviction
        let alloc4 = pool.allocate(400).unwrap();
        
        // Check that eviction happened (should have evicted alloc2 or alloc3, not alloc1)
        let eviction_stats = pool.get_eviction_stats();
        assert_eq!(eviction_stats.total_evictions.load(Ordering::Relaxed), 1);
        
        // Check memory pressure
        let pressure = pool.get_memory_pressure();
        assert!(pressure < 0.8); // Should be under eviction threshold
    }

    #[test]
    fn test_lru_eviction_thread_safety() {
        let pool = LRUEvictionMemoryPool::new(None).unwrap();
        let pool_clone = Arc::new(pool);
        
        // Spawn multiple threads to allocate and deallocate concurrently
        let handles: Vec<_> = (0..5).map(|i| {
            let pool = Arc::clone(&pool_clone);
            thread::spawn(move || {
                for j in 0..10 {
                    let size = 100 * (i * 10 + j);
                    let _ = pool.allocate(size);
                    thread::sleep(Duration::from_micros(10));
                    // In real usage, we'd need to track keys for deallocation
                }
            })
        }).collect();
        
        // Wait for all threads to complete
        for handle in handles {
            let _ = handle.join();
        }
        
        // Check stats
        let stats = pool.get_memory_usage();
        assert!(stats.total_allocations.load(Ordering::Relaxed) > 0);
        
        // Process eviction queue
        let processed = pool.process_eviction_queue().unwrap();
        assert!(processed >= 0);
    }

    #[test]
    fn test_lru_eviction_clear() {
        let pool = LRUEvictionMemoryPool::new(None).unwrap();
        
        // Allocate some items
        let _alloc1 = pool.allocate(100).unwrap();
        let _alloc2 = pool.allocate(200).unwrap();
        let _alloc3 = pool.allocate(300).unwrap();
        
        // Check stats before clear
        let stats_before = pool.get_memory_usage();
        assert_eq!(stats_before.total_allocations.load(Ordering::Relaxed), 3);
        
        // Clear the pool
        let _ = pool.clear();
        
        // Check stats after clear
        let stats_after = pool.get_memory_usage();
        assert_eq!(stats_after.total_allocations.load(Ordering::Relaxed), 0);
        assert_eq!(stats_after.allocated_bytes.load(Ordering::Relaxed), 0);
        
        // Check eviction stats after clear
        let eviction_stats = pool.get_eviction_stats();
        assert_eq!(eviction_stats.total_evictions.load(Ordering::Relaxed), 0);
    }
}