use std::collections::HashMap;

use crate::logging::generate_trace_id;
use crate::logging::PerformanceLogger;
use crate::memory_pool::object_pool::{MemoryPressureLevel, ObjectPool, PooledObject};
use crate::memory_pool::slab_allocator::{SizeClass, SlabAllocator, SlabAllocatorConfig};

/// Size class definitions for hierarchical memory pooling with better fragmentation reduction
#[derive(Debug, Clone)]
pub struct HierarchicalPoolConfig {
    pub max_size_per_class: HashMap<SizeClass, usize>,
    pub high_water_mark_ratio: f64,
    pub cleanup_threshold: f64,
    pub adaptive_config: Option<AdaptivePoolConfig>,
}

impl Default for HierarchicalPoolConfig {
    fn default() -> Self {
        let mut max_sizes = HashMap::new();
        max_sizes.insert(SizeClass::Tiny64B, 10000); // 10k tiny objects
        max_sizes.insert(SizeClass::Small256B, 5000); // 5k small objects
        max_sizes.insert(SizeClass::Medium1KB, 2000); // 2k medium objects
        max_sizes.insert(SizeClass::Medium2KB, 1500); // 1.5k medium objects
        max_sizes.insert(SizeClass::Medium4KB, 1000); // 1k medium objects
        max_sizes.insert(SizeClass::Medium8KB, 750); // 750 medium objects
        max_sizes.insert(SizeClass::Large16KB, 500); // 500 large objects
        max_sizes.insert(SizeClass::Large32KB, 300); // 300 large objects
        max_sizes.insert(SizeClass::Large64KB, 200); // 200 large objects
        max_sizes.insert(SizeClass::Huge128KB, 100); // 100 huge objects
        max_sizes.insert(SizeClass::Huge256KB, 50); // 50 huge objects
        max_sizes.insert(SizeClass::Huge512KB, 25); // 25 huge objects
        max_sizes.insert(SizeClass::Huge1MBPlus, 10); // 10 huge+ objects

        Self {
            max_size_per_class: max_sizes,
            high_water_mark_ratio: 0.8,
            cleanup_threshold: 0.9,
            adaptive_config: Some(AdaptivePoolConfig::default()),
        }
    }
}

#[derive(Debug, Clone)]
pub struct AdaptivePoolConfig {
    pub growth_factor: f64,
    pub shrink_factor: f64,
    pub high_usage_threshold: f64,
    pub low_usage_threshold: f64,
    pub min_pool_size: usize,
}

impl Default for AdaptivePoolConfig {
    fn default() -> Self {
        Self {
            growth_factor: 1.5,
            shrink_factor: 0.7,
            high_usage_threshold: 0.85,
            low_usage_threshold: 0.3,
            min_pool_size: 50,
        }
    }
}

/// Fast object pool for frequent allocations
#[derive(Debug)]
pub struct FastObjectPool<T>
where
    T: Default + Clone,
{
    pool: Vec<T>,
    index: std::sync::atomic::AtomicUsize,
}

impl<T: Default + Clone> FastObjectPool<T> {
    /// Create new fast object pool for frequent allocations
    pub fn new(size: usize) -> Self {
        Self {
            pool: vec![T::default(); size],
            index: std::sync::atomic::AtomicUsize::new(0),
        }
    }

    /// Get object from pool using lock-free round-robin
    pub fn get(&self) -> T {
        let index = self
            .index
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed)
            % self.pool.len();
        self.pool[index].clone()
    }

    /// Return object to pool (overwrites oldest entry)
    pub fn return_object(&mut self, obj: T) {
        let index = self
            .index
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed)
            % self.pool.len();
        self.pool[index] = obj;
    }
}

/// Hierarchical memory pool combining object pools and slab allocation
#[derive(Debug)]
pub struct HierarchicalMemoryPool {
    pools: HashMap<SizeClass, ObjectPool<Vec<u8>>>,
    logger: PerformanceLogger,
    config: HierarchicalPoolConfig,
    size_class_order: Vec<SizeClass>, // Ordered by size for efficient best-fit lookup
    slab_allocator: Option<SlabAllocator<u8>>,
}

impl HierarchicalMemoryPool {
    pub fn new(config: Option<HierarchicalPoolConfig>) -> Self {
        let config = config.unwrap_or_default();
        let mut pools = HashMap::new();

        // Create ordered list of size classes by increasing size for efficient best-fit lookup
        let mut size_class_order = Vec::new();
        size_class_order.push(SizeClass::Tiny64B);
        size_class_order.push(SizeClass::Small256B);
        size_class_order.push(SizeClass::Medium1KB);
        size_class_order.push(SizeClass::Medium2KB);
        size_class_order.push(SizeClass::Medium4KB);
        size_class_order.push(SizeClass::Medium8KB);
        size_class_order.push(SizeClass::Large16KB);
        size_class_order.push(SizeClass::Large32KB);
        size_class_order.push(SizeClass::Large64KB);
        size_class_order.push(SizeClass::Huge128KB);
        size_class_order.push(SizeClass::Huge256KB);
        size_class_order.push(SizeClass::Huge512KB);
        size_class_order.push(SizeClass::Huge1MBPlus);

        for size_class in &size_class_order {
            let max_size = config
                .max_size_per_class
                .get(size_class)
                .copied()
                .unwrap_or(1000); // Default to 1000 if not specified

            pools.insert(*size_class, ObjectPool::new(max_size));
        }

        Self {
            pools,
            logger: PerformanceLogger::new("hierarchical_memory_pool"),
            config,
            size_class_order,
            slab_allocator: Some(SlabAllocator::new(SlabAllocatorConfig::default())),
        }
    }

    /// Allocate memory with size-based bucket selection and best-fit strategy
    pub fn allocate(&self, size: usize) -> PooledVec<u8> {
        let trace_id = generate_trace_id();

        // Find the best-fit size class using our ordered list
        let size_class = self.find_best_fit_size_class(size);
        let pool = self
            .pools
            .get(&size_class)
            .expect("Failed to find pool for size class");

        self.logger.log_info(
            "allocate",
            &trace_id,
            &format!(
                "Allocating {} bytes from {} pool (best-fit)",
                size, size_class
            ),
        );

        let mut pooled = pool.get();
        let vec = pooled.as_mut();
        vec.clear();
        vec.resize(size, 0u8);

        pooled
    }

    /// Find the best-fit size class for a given allocation size
    fn find_best_fit_size_class(&self, size: usize) -> SizeClass {
        // 1. First try exact size match (0% fragmentation)
        if let Some(size_class) = SizeClass::from_exact_size(size) {
            return size_class;
        }

        // 2. Binary search for smallest fit (O(log n) vs O(n) linear search)
        let index = self
            .size_class_order
            .binary_search_by(|&sc| {
                if sc.max_size() < size {
                    std::cmp::Ordering::Less
                } else {
                    std::cmp::Ordering::Greater
                }
            })
            .unwrap_or_else(|i| i);

        if index < self.size_class_order.len() {
            self.size_class_order[index]
        } else {
            SizeClass::Huge1MBPlus
        }
    }

    /// Get memory pressure level
    pub fn get_memory_pressure(&self) -> MemoryPressureLevel {
        // Calculate overall memory pressure based on pool usage
        let mut total_usage = 0;
        let mut total_capacity = 0;

        for (_, pool) in &self.pools {
            let stats = pool.get_monitoring_stats();
            total_usage += stats.available_objects;
            total_capacity += stats.max_size;
        }

        let usage_ratio = if total_capacity > 0 {
            total_usage as f64 / total_capacity as f64
        } else {
            0.0
        };

        MemoryPressureLevel::from_usage_ratio(usage_ratio)
    }

    /// Perform memory defragmentation during low-pressure periods
    pub fn defragment(&mut self) -> bool {
        let trace_id = generate_trace_id();
        let mut defragmented = false;

        // Only defragment during low memory pressure
        let pressure = self.get_memory_pressure();
        if pressure != MemoryPressureLevel::Normal {
            self.logger.log_info(
                "defragment",
                &trace_id,
                "Skipping defragmentation - not in low pressure mode",
            );
            return false;
        }

        self.logger
            .log_info("defragment", &trace_id, "Starting memory defragmentation");

        for (size_class, pool) in &self.pools {
            let stats = pool.get_monitoring_stats();
            let usage_ratio = stats.current_usage_ratio;

            // Only defragment pools with moderate usage to avoid unnecessary work
            if usage_ratio > 0.2 && usage_ratio < 0.7 {
                let result = self.defragment_pool(pool);
                if result {
                    defragmented = true;
                    self.logger.log_info(
                        "defragment",
                        &trace_id,
                        &format!("Defragmented {} pool", size_class),
                    );
                }
            }
        }

        if defragmented {
            self.logger.log_info(
                "defragment",
                &trace_id,
                "Memory defragmentation completed successfully",
            );
        } else {
            self.logger
                .log_info("defragment", &trace_id, "No defragmentation needed");
        }

        defragmented
    }

    /// Defragment a specific pool by removing fragmented objects
    fn defragment_pool(&self, pool: &ObjectPool<Vec<u8>>) -> bool {
        let trace_id = generate_trace_id();
        let changed = false;

        let mut pool_guard = pool.pool.lock().unwrap();
        let mut access_order_guard = pool.access_order.lock().unwrap();
        let current_size = pool_guard.len();

        if current_size <= 10 {
            return false; // Skip very small pools
        }

        // Calculate fragmentation by checking for unused space patterns
        let mut fragmented_objects = Vec::new();
        let mut total_fragmentation = 0;

        for (&id, (obj, _)) in pool_guard.iter() {
            let usage_ratio = obj.len() as f64 / obj.capacity() as f64;
            if usage_ratio < 0.5 {
                // Object is significantly underutilized - mark for defragmentation
                fragmented_objects.push(id);
                total_fragmentation += obj.capacity() - obj.len();
            }
        }

        if fragmented_objects.is_empty() {
            return false;
        }

        self.logger.log_info(
            "defragment_pool",
            &trace_id,
            &format!(
                "Found {} fragmented objects ({:.1}KB total fragmentation)",
                fragmented_objects.len(),
                total_fragmentation as f64 / 1024.0
            ),
        );

        // Remove fragmented objects
        for id in &fragmented_objects {
            pool_guard.remove(&id);

            // Also remove from access order
            if let Some((&time, &_obj_id)) = access_order_guard
                .iter()
                .find(|&(_, obj_id)| *obj_id == *id)
            {
                access_order_guard.remove(&time);
            }
        }

        // Reallocate surviving objects to consolidate memory
        let surviving_objects: Vec<_> = pool_guard
            .drain()
            .map(|(id, (obj, time))| (id, obj, time))
            .collect();
        access_order_guard.clear();

        for (id, obj, _time) in surviving_objects {
            let now = std::time::SystemTime::now();
            pool_guard.insert(id, (obj, now));
            access_order_guard.insert(now, id);
        }

        self.logger.log_info(
            "defragment_pool",
            &trace_id,
            &format!(
                "Defragmented pool: removed {} fragmented objects, recovered {} KB",
                fragmented_objects.len(),
                total_fragmentation as f64 / 1024.0
            ),
        );

        changed
    }

    /// Allocate memory with SIMD alignment requirements (for AVX-512 compatible buffers)
    pub fn allocate_simd_aligned(
        &self,
        size: usize,
        alignment: usize,
    ) -> Result<(PooledVec<u8>, usize), String> {
        let trace_id = generate_trace_id();

        // Ensure alignment is power of 2 (required for SIMD operations)
        if alignment == 0 || (alignment & (alignment - 1)) != 0 {
            return Err(format!(
                "Invalid alignment: {} (must be power of 2)",
                alignment
            ));
        }

        let size_class = self.find_best_fit_size_class(size);
        let pool = self
            .pools
            .get(&size_class)
            .ok_or_else(|| format!("Failed to find pool for size class: {:?}", size_class))?;

        self.logger.log_info(
            "allocate_simd_aligned",
            &trace_id,
            &format!(
                "Allocating {} bytes (aligned to {}) from {} pool (best-fit)",
                size, alignment, size_class
            ),
        );

        // Get a pooled vector object. We'll reserve additional bytes so the caller
        // can create an aligned view within the returned Vec without taking an
        // interior pointer ownership. We allocate size + alignment bytes and leave
        // the Vec owning the full buffer.
        let mut pooled = pool.get();
        let vec = pooled.as_mut();

        vec.clear();
        // Ensure the underlying Vec length is at least `size + alignment` so an
        // aligned subslice (starting at some offset < alignment) can be taken
        // safely. We'll compute and return the aligned byte offset to the
        // caller so it doesn't need to recompute raw pointer math.
        vec.reserve(size + alignment);
        vec.resize(size + alignment, 0u8);

        // Compute an aligned offset within the returned buffer
        let raw_ptr = vec.as_ptr() as usize;
        let aligned_ptr = (raw_ptr + alignment - 1) & !(alignment - 1);
        let offset = aligned_ptr - raw_ptr;

        Ok((pooled, offset))
    }

    /// Get statistics for all size classes
    pub fn get_size_class_stats(
        &self,
    ) -> HashMap<SizeClass, crate::memory_pool::object_pool::ObjectPoolMonitoringStats> {
        let trace_id = generate_trace_id();
        self.logger
            .log_operation("get_size_class_stats", &trace_id, || {
                let mut stats = HashMap::new();
                for (size_class, pool) in &self.pools {
                    stats.insert(*size_class, pool.get_monitoring_stats());
                }
                stats
            })
    }

    /// Perform cleanup across all size classes
    pub fn cleanup_all(&self) -> bool {
        let trace_id = generate_trace_id();
        let mut cleaned_up = false;

        for (size_class, pool) in &self.pools {
            if pool.lazy_cleanup(self.config.cleanup_threshold) {
                cleaned_up = true;
                self.logger.log_info(
                    "cleanup",
                    &trace_id,
                    &format!("Cleaned up {} pool", size_class),
                );
            }
        }

        cleaned_up
    }
}

impl Clone for HierarchicalMemoryPool {
    fn clone(&self) -> Self {
        let mut pools = HashMap::new();
        for (size_class, pool) in &self.pools {
            pools.insert(*size_class, pool.clone_shallow());
        }

        Self {
            pools,
            logger: self.logger.clone(),
            config: self.config.clone(),
            size_class_order: self.size_class_order.clone(),
            slab_allocator: self
                .slab_allocator
                .as_ref()
                .map(|_| SlabAllocator::new(SlabAllocatorConfig::default())),
        }
    }
}

pub type PooledVec<T> = PooledObject<Vec<T>>;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_hierarchical_pool_allocation() {
        let pool = HierarchicalMemoryPool::new(None);

        // Test small allocation
        let small_vec = pool.allocate(64);
        assert_eq!(small_vec.len(), 64);

        // Test medium allocation
        let medium_vec = pool.allocate(1024);
        assert_eq!(medium_vec.len(), 1024);

        // Test large allocation
        let large_vec = pool.allocate(10000);
        assert_eq!(large_vec.len(), 10000);
    }

    #[test]
    fn test_best_fit_size_class() {
        let pool = HierarchicalMemoryPool::new(None);

        assert_eq!(pool.find_best_fit_size_class(64), SizeClass::Tiny64B);
        assert_eq!(pool.find_best_fit_size_class(200), SizeClass::Small256B);
        assert_eq!(pool.find_best_fit_size_class(1500), SizeClass::Medium2KB);
        assert_eq!(pool.find_best_fit_size_class(50000), SizeClass::Large64KB);
    }

    #[test]
    fn test_simd_aligned_allocation() {
        let pool = HierarchicalMemoryPool::new(None);

        // Test SIMD-aligned allocation
        // The pool returns a pooled Vec with extra padding. We verify that we
        // can compute an aligned subslice of length `size` within the returned
        // buffer.
    let (pooled, offset) = pool.allocate_simd_aligned(1024, 32).unwrap();
    let vec_ref = pooled.as_ref();
    // Ensure the aligned subslice (at provided offset) fits within the padded buffer
    assert!(offset + 1024 <= vec_ref.len(), "Aligned subslice must fit");
    }

    #[test]
    fn test_memory_pressure_calculation() {
        let pool = HierarchicalMemoryPool::new(None);

        // Initially should be normal pressure
        let pressure = pool.get_memory_pressure();
        assert_eq!(pressure, MemoryPressureLevel::Normal);
    }

    #[test]
    fn test_fast_object_pool() {
        let pool = FastObjectPool::<String>::new(10);

        // Get objects
        let obj1 = pool.get();
        let obj2 = pool.get();

        assert_eq!(obj1, "");
        assert_eq!(obj2, "");

        // Test round-robin behavior (implementation detail)
        let obj3 = pool.get();
        assert_eq!(obj3, "");
    }
}
