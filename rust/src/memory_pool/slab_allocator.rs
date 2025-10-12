use std::collections::HashMap;
use std::fmt::Debug;
use std::marker::PhantomData;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::RwLock;

use crate::logging::PerformanceLogger;

/// Size class definitions for hierarchical memory pooling with better fragmentation reduction
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Ord, PartialOrd)]
pub enum SizeClass {
    Tiny64B,     // 64 bytes
    Small256B,   // 256 bytes
    Medium1KB,   // 1KB
    Medium2KB,   // 2KB
    Medium4KB,   // 4KB
    Medium8KB,   // 8KB
    Large16KB,   // 16KB
    Large32KB,   // 32KB
    Large64KB,   // 64KB
    Huge128KB,   // 128KB
    Huge256KB,   // 256KB
    Huge512KB,   // 512KB
    Huge1MBPlus, // 1MB+ (dynamic)
}

impl SizeClass {
    /// Get the maximum size for this size class
    pub fn max_size(&self) -> usize {
        match self {
            SizeClass::Tiny64B => 64,
            SizeClass::Small256B => 256,
            SizeClass::Medium1KB => 1024,
            SizeClass::Medium2KB => 2048,
            SizeClass::Medium4KB => 4096,
            SizeClass::Medium8KB => 8192,
            SizeClass::Large16KB => 16384,
            SizeClass::Large32KB => 32768,
            SizeClass::Large64KB => 65536,
            SizeClass::Huge128KB => 131072,
            SizeClass::Huge256KB => 262144,
            SizeClass::Huge512KB => 524288,
            SizeClass::Huge1MBPlus => usize::MAX, // Unlimited for this class
        }
    }

    /// Get the typical allocation size for this class (for preallocation)
    pub fn typical_size(&self) -> usize {
        match self {
            SizeClass::Tiny64B => 64,
            SizeClass::Small256B => 256,
            SizeClass::Medium1KB => 1024,
            SizeClass::Medium2KB => 2048,
            SizeClass::Medium4KB => 4096,
            SizeClass::Medium8KB => 8192,
            SizeClass::Large16KB => 16384,
            SizeClass::Large32KB => 32768,
            SizeClass::Large64KB => 65536,
            SizeClass::Huge128KB => 131072,
            SizeClass::Huge256KB => 262144,
            SizeClass::Huge512KB => 524288,
            SizeClass::Huge1MBPlus => 1048576, // 1MB typical size
        }
    }

    /// Get the exact size for this size class (for best-fit allocation)
    pub fn exact_size(&self) -> usize {
        match self {
            SizeClass::Tiny64B => 64,
            SizeClass::Small256B => 256,
            SizeClass::Medium1KB => 1024,
            SizeClass::Medium2KB => 2048,
            SizeClass::Medium4KB => 4096,
            SizeClass::Medium8KB => 8192,
            SizeClass::Large16KB => 16384,
            SizeClass::Large32KB => 32768,
            SizeClass::Large64KB => 65536,
            SizeClass::Huge128KB => 131072,
            SizeClass::Huge256KB => 262144,
            SizeClass::Huge512KB => 524288,
            SizeClass::Huge1MBPlus => 1048576, // Base size for dynamic allocations
        }
    }

    /// Get size class from allocation size (exact match only)
    pub fn from_exact_size(size: usize) -> Option<SizeClass> {
        match size {
            64 => Some(SizeClass::Tiny64B),
            256 => Some(SizeClass::Small256B),
            1024 => Some(SizeClass::Medium1KB),
            2048 => Some(SizeClass::Medium2KB),
            4096 => Some(SizeClass::Medium4KB),
            8192 => Some(SizeClass::Medium8KB),
            16384 => Some(SizeClass::Large16KB),
            32768 => Some(SizeClass::Large32KB),
            65536 => Some(SizeClass::Large64KB),
            131072 => Some(SizeClass::Huge128KB),
            262144 => Some(SizeClass::Huge256KB),
            524288 => Some(SizeClass::Huge512KB),
            _ if size >= 1048576 => Some(SizeClass::Huge1MBPlus),
            _ => None,
        }
    }

    /// Determine which size class a given allocation should use (best-fit)
    pub fn from_allocation_size(size: usize) -> Self {
        // Use exact size matching first for best-fit allocation
        match size {
            64 => SizeClass::Tiny64B,
            256 => SizeClass::Small256B,
            1024 => SizeClass::Medium1KB,
            2048 => SizeClass::Medium2KB,
            4096 => SizeClass::Medium4KB,
            8192 => SizeClass::Medium8KB,
            16384 => SizeClass::Large16KB,
            32768 => SizeClass::Large32KB,
            65536 => SizeClass::Large64KB,
            131072 => SizeClass::Huge128KB,
            262144 => SizeClass::Huge256KB,
            524288 => SizeClass::Huge512KB,
            _ if size <= 1048576 => SizeClass::Huge1MBPlus,
            _ => SizeClass::Huge1MBPlus,
        }
    }

    /// Find the smallest size class that can accommodate the given allocation
    pub fn find_smallest_fit(size: usize) -> Self {
        if size <= SizeClass::Tiny64B.max_size() {
            SizeClass::Tiny64B
        } else if size <= SizeClass::Small256B.max_size() {
            SizeClass::Small256B
        } else if size <= SizeClass::Medium1KB.max_size() {
            SizeClass::Medium1KB
        } else if size <= SizeClass::Medium2KB.max_size() {
            SizeClass::Medium2KB
        } else if size <= SizeClass::Medium4KB.max_size() {
            SizeClass::Medium4KB
        } else if size <= SizeClass::Medium8KB.max_size() {
            SizeClass::Medium8KB
        } else if size <= SizeClass::Large16KB.max_size() {
            SizeClass::Large16KB
        } else if size <= SizeClass::Large32KB.max_size() {
            SizeClass::Large32KB
        } else if size <= SizeClass::Large64KB.max_size() {
            SizeClass::Large64KB
        } else if size <= SizeClass::Huge128KB.max_size() {
            SizeClass::Huge128KB
        } else if size <= SizeClass::Huge256KB.max_size() {
            SizeClass::Huge256KB
        } else if size <= SizeClass::Huge512KB.max_size() {
            SizeClass::Huge512KB
        } else {
            SizeClass::Huge1MBPlus
        }
    }

    /// Get the next larger size class
    pub fn next_larger(&self) -> Option<SizeClass> {
        match self {
            SizeClass::Tiny64B => Some(SizeClass::Small256B),
            SizeClass::Small256B => Some(SizeClass::Medium1KB),
            SizeClass::Medium1KB => Some(SizeClass::Medium2KB),
            SizeClass::Medium2KB => Some(SizeClass::Medium4KB),
            SizeClass::Medium4KB => Some(SizeClass::Medium8KB),
            SizeClass::Medium8KB => Some(SizeClass::Large16KB),
            SizeClass::Large16KB => Some(SizeClass::Large32KB),
            SizeClass::Large32KB => Some(SizeClass::Large64KB),
            SizeClass::Large64KB => Some(SizeClass::Huge128KB),
            SizeClass::Huge128KB => Some(SizeClass::Huge256KB),
            SizeClass::Huge256KB => Some(SizeClass::Huge512KB),
            SizeClass::Huge512KB => Some(SizeClass::Huge1MBPlus),
            SizeClass::Huge1MBPlus => None,
        }
    }

    /// Get the previous smaller size class
    pub fn next_smaller(&self) -> Option<SizeClass> {
        match self {
            SizeClass::Tiny64B => None,
            SizeClass::Small256B => Some(SizeClass::Tiny64B),
            SizeClass::Medium1KB => Some(SizeClass::Small256B),
            SizeClass::Medium2KB => Some(SizeClass::Medium1KB),
            SizeClass::Medium4KB => Some(SizeClass::Medium2KB),
            SizeClass::Medium8KB => Some(SizeClass::Medium4KB),
            SizeClass::Large16KB => Some(SizeClass::Medium8KB),
            SizeClass::Large32KB => Some(SizeClass::Large16KB),
            SizeClass::Large64KB => Some(SizeClass::Large32KB),
            SizeClass::Huge128KB => Some(SizeClass::Large64KB),
            SizeClass::Huge256KB => Some(SizeClass::Huge128KB),
            SizeClass::Huge512KB => Some(SizeClass::Huge256KB),
            SizeClass::Huge1MBPlus => Some(SizeClass::Huge512KB),
        }
    }
}

impl std::fmt::Display for SizeClass {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SizeClass::Tiny64B => write!(f, "Tiny64B"),
            SizeClass::Small256B => write!(f, "Small256B"),
            SizeClass::Medium1KB => write!(f, "Medium1KB"),
            SizeClass::Medium2KB => write!(f, "Medium2KB"),
            SizeClass::Medium4KB => write!(f, "Medium4KB"),
            SizeClass::Medium8KB => write!(f, "Medium8KB"),
            SizeClass::Large16KB => write!(f, "Large16KB"),
            SizeClass::Large32KB => write!(f, "Large32KB"),
            SizeClass::Large64KB => write!(f, "Large64KB"),
            SizeClass::Huge128KB => write!(f, "Huge128KB"),
            SizeClass::Huge256KB => write!(f, "Huge256KB"),
            SizeClass::Huge512KB => write!(f, "Huge512KB"),
            SizeClass::Huge1MBPlus => write!(f, "Huge1MBPlus"),
        }
    }
}

/// Slab allocation structure for fixed-size memory blocks
#[derive(Debug)]
pub struct Slab {
    /// Memory block containing fixed-size objects
    memory: Vec<u8>,
    /// Bitmask tracking which slots are allocated (1 = allocated, 0 = free)
    allocation_mask: Vec<bool>,
    /// Current number of allocated objects
    allocated_count: AtomicUsize,
    /// Total number of slots in this slab
    total_slots: usize,
    /// Size of each object slot
    slot_size: usize,
    /// Lock for thread-safe operations
    lock: std::sync::Mutex<()>,
}

impl Slab {
    pub fn new(slot_size: usize, num_slots: usize) -> Self {
        Self {
            memory: vec![0u8; slot_size * num_slots],
            allocation_mask: vec![false; num_slots],
            allocated_count: AtomicUsize::new(0),
            total_slots: num_slots,
            slot_size,
            lock: std::sync::Mutex::new(()),
        }
    }

    /// Allocate a slot from this slab
    pub fn allocate(&mut self) -> Option<usize> {
        let _guard = self.lock.lock().unwrap();

        for i in 0..self.total_slots {
            if !self.allocation_mask[i] {
                self.allocation_mask[i] = true;
                self.allocated_count.fetch_add(1, Ordering::Relaxed);
                return Some(i);
            }
        }
        None
    }

    /// Deallocate a slot
    pub fn deallocate(&mut self, slot_index: usize) {
        let _guard = self.lock.lock().unwrap();

        if slot_index < self.total_slots && self.allocation_mask[slot_index] {
            self.allocation_mask[slot_index] = false;
            self.allocated_count.fetch_sub(1, Ordering::Relaxed);
        }
    }

    /// Get pointer to slot data
    pub fn get_slot_ptr(&self, slot_index: usize) -> Option<*mut u8> {
        if slot_index < self.total_slots {
            Some(unsafe { self.memory.as_ptr().add(slot_index * self.slot_size) as *mut u8 })
        } else {
            None
        }
    }

    /// Check if slab is full
    pub fn is_full(&self) -> bool {
        self.allocated_count.load(Ordering::Relaxed) >= self.total_slots
    }

    /// Check if slab is empty
    pub fn is_empty(&self) -> bool {
        self.allocated_count.load(Ordering::Relaxed) == 0
    }

    /// Get allocation statistics
    pub fn stats(&self) -> SlabStats {
        SlabStats {
            total_slots: self.total_slots,
            allocated_slots: self.allocated_count.load(Ordering::Relaxed),
            slot_size: self.slot_size,
            memory_usage: self.memory.len(),
        }
    }
}

/// Statistics for slab allocation
#[derive(Debug, Clone)]
pub struct SlabStats {
    pub total_slots: usize,
    pub allocated_slots: usize,
    pub slot_size: usize,
    pub memory_usage: usize,
}

/// Slab allocator for efficient fixed-size memory management
#[derive(Debug)]
pub struct SlabAllocator<T> {
    /// Map of size classes to their respective slabs
    slabs: HashMap<SizeClass, Vec<Slab>>,
    _phantom: PhantomData<T>,
    /// Current slab for each size class (for round-robin allocation)
    current_slab: HashMap<SizeClass, AtomicUsize>,
    /// Configuration for slab allocation
    config: SlabAllocatorConfig,
    /// Logger for performance monitoring
    #[allow(dead_code)]
    logger: PerformanceLogger,
    /// Global lock for thread-safe access to allocator state
    lock: RwLock<()>,
}

impl<T> SlabAllocator<T> {
    pub fn new(config: SlabAllocatorConfig) -> Self {
        Self {
            slabs: HashMap::new(),
            _phantom: PhantomData,
            current_slab: HashMap::new(),
            config,
            logger: PerformanceLogger::new("slab_allocator"),
            lock: RwLock::new(()),
        }
    }

    /// Allocate memory of the specified size
    pub fn allocate(&mut self, size: usize) -> Option<SlabAllocation> {
        let _global_guard = self.lock.write().unwrap();
        let size_class = SizeClass::find_smallest_fit(size);

        // Ensure we have slabs for this size class (thread-safe now)
        Self::ensure_slabs_for_size_class(
            &mut self.slabs,
            &mut self.current_slab,
            &self.config,
            &size_class,
        );

        let slabs = self.slabs.get_mut(&size_class)?;
        let current_slab_idx = self
            .current_slab
            .get(&size_class)?
            .fetch_add(1, Ordering::Relaxed)
            % slabs.len();

        // Try to allocate from current slab
        if let Some(slot_idx) = slabs[current_slab_idx].allocate() {
            return Some(SlabAllocation {
                slab_index: current_slab_idx,
                slot_index: slot_idx,
                size_class,
                ptr: slabs[current_slab_idx].get_slot_ptr(slot_idx).unwrap(),
            });
        }

        // Try other slabs in this size class
        for (i, slab) in slabs.iter_mut().enumerate() {
            if i == current_slab_idx {
                continue; // Already tried this one
            }
            if let Some(slot_idx) = slab.allocate() {
                return Some(SlabAllocation {
                    slab_index: i,
                    slot_index: slot_idx,
                    size_class,
                    ptr: slab.get_slot_ptr(slot_idx).unwrap(),
                });
            }
        }

        // All slabs are full, need to allocate new slab
        Self::allocate_new_slab(&mut self.slabs, &self.config, &size_class);
        if let Some(slot_idx) = self.slabs.get_mut(&size_class)?.last_mut()?.allocate() {
            let slab_idx = self.slabs.get(&size_class)?.len() - 1;
            return Some(SlabAllocation {
                slab_index: slab_idx,
                slot_index: slot_idx,
                size_class,
                ptr: self
                    .slabs
                    .get(&size_class)?
                    .last()?
                    .get_slot_ptr(slot_idx)
                    .unwrap(),
            });
        }

        None
    }

    /// Deallocate memory
    pub fn deallocate(&mut self, allocation: SlabAllocation) {
        let _global_guard = self.lock.write().unwrap();

        if let Some(slabs) = self.slabs.get_mut(&allocation.size_class) {
            if let Some(slab) = slabs.get_mut(allocation.slab_index) {
                slab.deallocate(allocation.slot_index);
            }
        }
    }

    /// Ensure slabs exist for a size class
    fn ensure_slabs_for_size_class(
        slabs_map: &mut HashMap<SizeClass, Vec<Slab>>,
        current_slab_map: &mut HashMap<SizeClass, AtomicUsize>,
        config: &SlabAllocatorConfig,
        size_class: &SizeClass,
    ) {
        if !slabs_map.contains_key(size_class) {
            let mut slabs = Vec::new();

            // Pre-allocate initial slabs if configured
            if config.pre_allocate {
                for _ in 0..config.max_slabs_per_class.min(1) {
                    slabs.push(Slab::new(size_class.exact_size(), config.slab_size));
                }
            }

            slabs_map.insert(*size_class, slabs);
            current_slab_map.insert(*size_class, AtomicUsize::new(0));
        }
    }

    /// Allocate a new slab for a size class
    fn allocate_new_slab(
        slabs_map: &mut HashMap<SizeClass, Vec<Slab>>,
        config: &SlabAllocatorConfig,
        size_class: &SizeClass,
    ) {
        if let Some(slabs) = slabs_map.get_mut(size_class) {
            if slabs.len() < config.max_slabs_per_class {
                slabs.push(Slab::new(size_class.exact_size(), config.slab_size));
            }
        }
    }

    /// Get allocation statistics
    pub fn stats(&self) -> SlabAllocatorStats {
        let _global_guard = self.lock.read().unwrap();
        let mut total_memory = 0;
        let mut total_allocated = 0;
        let mut size_class_stats = HashMap::new();

        for (size_class, slabs) in &self.slabs {
            let mut class_memory = 0;
            let mut class_allocated = 0;

            for slab in slabs {
                let stats = slab.stats();
                class_memory += stats.memory_usage;
                class_allocated += stats.allocated_slots;
            }

            total_memory += class_memory;
            total_allocated += class_allocated;

            size_class_stats.insert(
                *size_class,
                SizeClassStats {
                    memory_usage: class_memory,
                    allocated_slots: class_allocated,
                    total_slots: slabs.len() * self.config.slab_size,
                },
            );
        }

        SlabAllocatorStats {
            total_memory_usage: total_memory,
            total_allocated_slots: total_allocated,
            size_class_stats,
        }
    }
}

/// Configuration for slab allocator
#[derive(Debug, Clone)]
pub struct SlabAllocatorConfig {
    /// Number of objects per slab
    pub slab_size: usize,
    /// Maximum number of slabs per size class
    pub max_slabs_per_class: usize,
    /// Pre-allocate initial slabs
    pub pre_allocate: bool,
    /// Enable overcommitment
    pub allow_overcommit: bool,
}

impl Default for SlabAllocatorConfig {
    fn default() -> Self {
        Self {
            slab_size: 1024,        // 1024 objects per slab by default
            max_slabs_per_class: 8, // Max 8 slabs per size class
            pre_allocate: true,
            allow_overcommit: false,
        }
    }
}

/// Represents an allocation from the slab allocator
#[derive(Debug, Clone)]
pub struct SlabAllocation {
    pub slab_index: usize,
    pub slot_index: usize,
    pub size_class: SizeClass,
    pub ptr: *mut u8,
}

/// Statistics for slab allocator
#[derive(Debug, Clone)]
pub struct SlabAllocatorStats {
    pub total_memory_usage: usize,
    pub total_allocated_slots: usize,
    pub size_class_stats: HashMap<SizeClass, SizeClassStats>,
}

/// Statistics for a size class
#[derive(Debug, Clone)]
pub struct SizeClassStats {
    pub memory_usage: usize,
    pub allocated_slots: usize,
    pub total_slots: usize,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_size_class_from_allocation_size() {
        assert_eq!(SizeClass::from_allocation_size(64), SizeClass::Tiny64B);
        assert_eq!(SizeClass::from_allocation_size(256), SizeClass::Small256B);
        assert_eq!(SizeClass::from_allocation_size(1024), SizeClass::Medium1KB);
        assert_eq!(
            SizeClass::from_allocation_size(2000000),
            SizeClass::Huge1MBPlus
        );
    }

    #[test]
    fn test_size_class_find_smallest_fit() {
        assert_eq!(SizeClass::find_smallest_fit(32), SizeClass::Tiny64B);
        assert_eq!(SizeClass::find_smallest_fit(128), SizeClass::Small256B);
        assert_eq!(SizeClass::find_smallest_fit(2000), SizeClass::Medium4KB);
    }

    #[test]
    fn test_slab_basic_allocation() {
        let mut slab = Slab::new(64, 10);

        // Allocate slots
        let slot1 = slab.allocate().unwrap();
        let slot2 = slab.allocate().unwrap();

        assert_ne!(slot1, slot2);
        assert!(slot1 < 10);
        assert!(slot2 < 10);

        // Check stats
        let stats = slab.stats();
        assert_eq!(stats.allocated_slots, 2);
        assert_eq!(stats.total_slots, 10);

        // Deallocate
        slab.deallocate(slot1);
        let stats_after = slab.stats();
        assert_eq!(stats_after.allocated_slots, 1);
    }

    #[test]
    fn test_slab_allocator() {
        let config = SlabAllocatorConfig {
            slab_size: 10,
            max_slabs_per_class: 2,
            pre_allocate: true,
            allow_overcommit: false,
        };

        let mut allocator = SlabAllocator::<u8>::new(config);

        // Allocate some memory
        let alloc1 = allocator.allocate(64).unwrap();
        let alloc2 = allocator.allocate(64).unwrap();

        assert_eq!(alloc1.size_class, SizeClass::Tiny64B);
        assert_eq!(alloc2.size_class, SizeClass::Tiny64B);

        // Deallocate
        allocator.deallocate(alloc1);
        allocator.deallocate(alloc2);

        // Check stats
        let stats = allocator.stats();
        assert!(stats.total_memory_usage > 0);
    }
}
