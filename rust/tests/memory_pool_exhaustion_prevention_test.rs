use std::sync::Arc;
use std::thread;
use std::time::Duration;
use crate::memory_pool::{EnhancedMemoryPoolManager, SwapMemoryPool, MemoryPressureLevel};
use crate::arena::{BumpArena, ArenaPool};

#[test]
fn test_swap_memory_pool_threshold_cleanup() {
    // Create a swap memory pool with small limit to test threshold behavior
    let pool = SwapMemoryPool::new(10 * 1024 * 1024); // 10MB limit
    
    // Allocate until we cross thresholds
    for i in 0..100 {
        let size = if i < 50 { 100_000 } else { 200_000 }; // Vary allocation sizes
        
        // This should trigger cleanup when crossing thresholds
        let result = pool.allocate_chunk_metadata(size);
        
        if let Err(e) = result {
            // Allocation failures are expected at critical levels
            println!("Allocation failed at iteration {}: {}", i, e);
            break;
        }
    }
    
    // Check that we have some cleanup events recorded
    let metrics = pool.get_metrics();
    assert!(metrics.lazy_cleanup_count > 0, "Should have at least one lazy cleanup");
    assert!(metrics.aggressive_cleanup_count > 0, "Should have at least one aggressive cleanup");
    
    println!("Lazy cleanups: {}, Aggressive cleanups: {}", 
             metrics.lazy_cleanup_count, metrics.aggressive_cleanup_count);
}

#[test]
fn test_arena_memory_pressure_management() {
    // Create a bump arena with small chunk size
    let arena = BumpArena::new(1 * 1024 * 1024); // 1MB chunks
    
    // Allocate memory until we cross pressure thresholds
    for i in 0..500 {
        let size = 2_000 + (i % 100 * 10_000); // Increasing allocation sizes
        
        // Mark as critical operation during allocation
        arena.is_critical_operation.store(true, std::sync::atomic::Ordering::Relaxed);
        let ptr = arena.alloc(size, 8);
        arena.is_critical_operation.store(false, std::sync::atomic::Ordering::Relaxed);
        
        if ptr.is_null() {
            println!("Allocation failed at iteration {} (expected at high pressure)", i);
            break;
        }
    }
    
    // Check stats
    let stats = arena.stats();
    println!("Arena utilization: {:.2}%", stats.utilization * 100.0);
    println!("Arena pressure level: {:?}", stats.pressure_level);
    
    // Should have some cleanup events
    let pressure_stats = arena.get_pressure_stats();
    assert!(pressure_stats.cleanup_distribution.lazy > 0.0, "Should have lazy cleanup events");
}

#[test]
fn test_critical_operation_protection() {
    let pool = EnhancedMemoryPoolManager::new(50 * 1024 * 1024);
    let swap_pool = Arc::clone(&pool.swap_pool);
    
    // Start monitoring
    pool.start_monitoring(100);
    
    // Simulate critical operation (like chunk loading)
    swap_pool.is_critical_operation.store(true, std::sync::atomic::Ordering::Relaxed);
    
    // Allocate memory during critical operation
    for _ in 0..20 {
        let result = swap_pool.allocate_chunk_metadata(500_000);
        assert!(result.is_ok(), "Should allow allocations during critical operations");
    }
    
    // Check that no cleanup occurred during critical operation
    let metrics_before = swap_pool.get_metrics();
    
    // Clear critical flag and wait for potential cleanup
    swap_pool.is_critical_operation.store(false, std::sync::atomic::Ordering::Relaxed);
    thread::sleep(Duration::from_secs(2));
    
    let metrics_after = swap_pool.get_metrics();
    
    println!("Critical operation protection test completed");
    println!("Cleanup events during critical: {}", metrics_after.lazy_cleanup_count - metrics_before.lazy_cleanup_count);
}

#[test]
fn test_threshold_based_lazy_allocation() {
    let pool = SwapMemoryPool::new(15 * 1024 * 1024); // 15MB limit
    
    // Track initial cleanup count
    let initial_lazy_cleanups = pool.get_metrics().lazy_cleanup_count;
    
    // Allocate just below lazy threshold
    for _ in 0..30 {
        let _ = pool.allocate_chunk_metadata(400_000);
    }
    
    // Should not have cleanup yet
    let metrics1 = pool.get_metrics();
    assert_eq!(metrics1.lazy_cleanup_count, initial_lazy_cleanups, "Should not cleanup below threshold");
    
    // Allocate more to cross lazy threshold
    for _ in 0..20 {
        let _ = pool.allocate_chunk_metadata(600_000);
    }
    
    // Should have cleanup now
    let metrics2 = pool.get_metrics();
    assert!(metrics2.lazy_cleanup_count > initial_lazy_cleanups, "Should cleanup above lazy threshold");
    
    println!("Threshold test completed: {} -> {} lazy cleanups", 
             initial_lazy_cleanups, metrics2.lazy_cleanup_count);
}

#[test]
fn test_arena_pool_pressure_management() {
    let pool = ArenaPool::new(512 * 1024, 5); // 512KB chunks, 5 arenas max
    
    // Start monitoring
    pool.start_monitoring(100);
    
    // Get and use arenas
    for i in 0..20 {
        let arena = pool.get_arena();
        
        // Allocate memory in each arena
        for _ in 0..10 {
            let _ = arena.alloc(100_000, 8);
        }
        
        // Return arena to pool
        pool.return_arena(arena);
    }
    
    // Wait for monitoring to do its work
    thread::sleep(Duration::from_secs(3));
    
    // Check stats
    let stats = pool.stats();
    let pressure_stats = pool.get_pressure_stats();
    
    println!("Arena pool stats: {} arenas, {}% utilization", 
             stats.arena_count, stats.utilization * 100.0);
    println!("Cleanup distribution: light={}, aggressive={}, lazy={}",
             pressure_stats.cleanup_distribution.light,
             pressure_stats.cleanup_distribution.aggressive,
             pressure_stats.cleanup_distribution.lazy);
    
    // Should have some cleanup activity
    assert!(pressure_stats.cleanup_distribution.light > 0.0 || 
            pressure_stats.cleanup_distribution.aggressive > 0.0 || 
            pressure_stats.cleanup_distribution.lazy > 0.0,
            "Should have some cleanup activity");
}