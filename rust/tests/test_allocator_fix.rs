//! Test for memory leak fixes in the allocator
//! This test verifies that the RAII wrapper, scope guards, and memory tracking work correctly

#[cfg(test)]
mod tests {
    use crate::allocator::{UnifiedMemoryArena, MemoryArenaConfig, AllocationError};
    use std::sync::Arc;

    #[test]
    fn test_tracked_allocation_no_leak() {
        let config = MemoryArenaConfig::default();
        let arena = Arc::new(UnifiedMemoryArena::new(config));
        
        // Test successful allocation and deallocation
        let allocation = arena.allocate_tracked(1024).expect("Allocation should succeed");
        assert!(!allocation.as_ptr().is_null());
        assert_eq!(allocation.size(), 1024);
        
        // Deallocate should succeed
        arena.deallocate_tracked(allocation).expect("Deallocation should succeed");
        
        // Check that there are no leaks
        assert!(!arena.has_memory_leaks(), "Should have no memory leaks after proper deallocation");
        
        let (allocated, deallocated) = arena.get_allocation_stats();
        assert_eq!(allocated, deallocated, "Allocation and deallocation counts should match");
    }

    #[test]
    fn test_allocation_failure_handling() {
        let config = MemoryArenaConfig::default();
        let arena = Arc::new(UnifiedMemoryArena::new(config));
        
        // Test invalid size
        let result = arena.allocate_tracked(0);
        assert!(matches!(result, Err(AllocationError::InvalidSize)));
        
        // Test that no leaks occur from failed allocations
        assert!(!arena.has_memory_leaks(), "Should have no memory leaks from failed allocations");
    }

    #[test]
    fn test_memory_tracking_accuracy() {
        let config = MemoryArenaConfig::default();
        let arena = Arc::new(UnifiedMemoryArena::new(config));
        
        let initial_stats = arena.get_allocation_stats();
        
        // Allocate multiple objects
        let allocations: Vec<_> = (0..5).map(|i| {
            arena.allocate_tracked(256 * (i + 1)).expect("Allocation should succeed")
        }).collect();
        
        let after_alloc_stats = arena.get_allocation_stats();
        assert_eq!(after_alloc_stats.0 - initial_stats.0, 5, "Should have 5 new allocations");
        
        // Deallocate all objects
        for allocation in allocations {
            arena.deallocate_tracked(allocation).expect("Deallocation should succeed");
        }
        
        let final_stats = arena.get_allocation_stats();
        assert_eq!(final_stats.0, final_stats.1, "All allocations should be deallocated");
        assert!(!arena.has_memory_leaks(), "Should have no memory leaks");
    }

    #[test]
    fn test_error_propagation() {
        let config = MemoryArenaConfig::default();
        let arena = Arc::new(UnifiedMemoryArena::new(config));
        
        // Test that allocation errors are properly propagated
        let result = arena.allocate_tracked(usize::MAX);
        assert!(result.is_err(), "Allocation of usize::MAX should fail");
        
        // Verify no leaks from failed large allocation
        assert!(!arena.has_memory_leaks(), "Should have no memory leaks from failed large allocation");
    }

    #[test]
    fn test_legacy_compatibility() {
        let config = MemoryArenaConfig::default();
        let arena = Arc::new(UnifiedMemoryArena::new(config));
        
        // Test that legacy allocate/deallocate still works
        let ptr = arena.allocate(1024);
        assert!(!ptr.is_null(), "Legacy allocation should succeed");
        
        arena.deallocate(ptr, 1024);
        
        // Note: Legacy methods don't participate in leak tracking, so we can't verify
        // that they don't leak without additional instrumentation
    }
}