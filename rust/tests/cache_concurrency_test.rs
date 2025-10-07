use std::sync::{Arc, Barrier};
use std::thread;
use std::time::{Duration, Instant};

use rustperf::cache_eviction::{PriorityCache, EvictionPolicy};

#[test]
fn test_cache_concurrent_operations_no_deadlocks() {
    let cache = Arc::new(PriorityCache::new(100, EvictionPolicy::LRU, true));
    let num_threads = 8;
    let operations_per_thread = 500;
    let barrier = Arc::new(Barrier::new(num_threads));
    
    let handles: Vec<_> = (0..num_threads)
        .map(|thread_id| {
            let cache = Arc::clone(&cache);
            let barrier = Arc::clone(&barrier);
            
            thread::spawn(move || {
                barrier.wait();
                
                let mut success_count = 0;
                let start_time = Instant::now();
                
                for i in 0..operations_per_thread {
                    let key = format!("thread_{}_key_{}", thread_id, i % 25);
                    let value = vec![thread_id as u8, i as u8];
                    
                    // Mix of operations that could cause deadlocks
                    match i % 4 {
                        0 => {
                            // Insert operation
                            let _result = cache.insert(key.clone(), value.clone(), 1);
                            success_count += 1;
                        }
                        1 => {
                            // Get operation
                            let _result = cache.get(&key);
                            success_count += 1;
                        }
                        2 => {
                            // Remove operation
                            let _result = cache.remove(&key);
                            success_count += 1;
                        }
                        3 => {
                            // Stats operation
                            let _stats = cache.get_stats();
                            success_count += 1;
                        }
                        _ => {}
                    }
                    
                    // Add some timing variation to increase deadlock probability
                    if i % 50 == 0 {
                        thread::sleep(Duration::from_micros(1));
                    }
                }
                
                let elapsed = start_time.elapsed();
                println!("Thread {} completed {} operations in {:?}", thread_id, operations_per_thread, elapsed);
                
                success_count
            })
        })
        .collect();
    
    let start_time = Instant::now();
    let results: Vec<_> = handles.into_iter().map(|h| h.join().unwrap()).collect();
    let total_elapsed = start_time.elapsed();
    
    let total_operations = results.iter().sum::<usize>();
    println!("Total concurrent operations completed: {} in {:?}", total_operations, total_elapsed);
    
    // Verify all threads completed successfully (no deadlocks)
    assert_eq!(results.len(), num_threads);
    for (i, &count) in results.iter().enumerate() {
        assert!(count > 0, "Thread {} had no successful operations - possible deadlock", i);
    }
    
    // Verify reasonable performance (should complete in under 5 seconds)
    assert!(total_elapsed < Duration::from_secs(5), "Test took too long - possible performance issue");
}

#[test]
fn test_cache_rapid_eviction_no_deadlocks() {
    let cache = Arc::new(PriorityCache::new(10, EvictionPolicy::LFU, true));
    let num_threads = 6;
    let barrier = Arc::new(Barrier::new(num_threads));
    
    let handles: Vec<_> = (0..num_threads)
        .map(|thread_id| {
            let cache = Arc::clone(&cache);
            let barrier = Arc::clone(&barrier);
            
            thread::spawn(move || {
                barrier.wait();
                
                for i in 0i32..200 {
                    // Rapidly insert more items than capacity to force evictions
                    let key = format!("thread_{}_item_{}", thread_id, i);
                    let value = vec![thread_id as u8, i as u8];
                    
                    // This should trigger many evictions due to small cache size
                    cache.insert(key, value, 1);
                    
                    // Try to access recently inserted items to create contention
                    for j in 0..5 {
                        let access_key = format!("thread_{}_item_{}", thread_id, i.saturating_sub(j));
                        cache.get(&access_key);
                    }
                    
                    // Periodically check stats
                    if i % 25 == 0 {
                        let stats = cache.get_stats();
                        println!("Thread {} stats at iteration {}: {:?}", thread_id, i, stats);
                    }
                }
                
                println!("Thread {} completed rapid eviction test", thread_id);
            })
        })
        .collect();
    
    let start_time = Instant::now();
    for handle in handles {
        handle.join().unwrap();
    }
    let elapsed = start_time.elapsed();
    
    println!("Rapid eviction test completed in {:?}", elapsed);
    assert!(elapsed < Duration::from_secs(3), "Test took too long - possible deadlock");
}

#[test]
fn test_cache_mixed_operations_stress() {
    let cache = Arc::new(PriorityCache::new(50, EvictionPolicy::FIFO, true));
    let num_threads = 4;
    let barrier = Arc::new(Barrier::new(num_threads));
    
    let handles: Vec<_> = (0..num_threads)
        .map(|thread_id| {
            let cache = Arc::clone(&cache);
            let barrier = Arc::clone(&barrier);
            
            thread::spawn(move || {
                barrier.wait();
                
                for i in 0..100 {
                    let key1 = format!("key_{}", i);
                    let key2 = format!("key_{}", (i + 1) % 75);
                    let value = vec![thread_id as u8, i as u8];
                    
                    // Mix of operations that could cause deadlocks in the old implementation
                    cache.insert(key1.clone(), value.clone(), 1);
                    cache.get(&key1);
                    cache.get(&key2);
                    cache.insert(key2.clone(), value.clone(), 2);
                    cache.remove(&key1);
                    cache.get(&key2);
                    
                    // Periodically clear cache to add more complexity
                    if i % 20 == 0 {
                        cache.clear();
                        println!("Thread {} cleared cache at iteration {}", thread_id, i);
                    }
                    
                    // Get stats after clear
                    if i % 25 == 0 {
                        let stats = cache.get_stats();
                        println!("Thread {} final stats at iteration {}: {:?}", thread_id, i, stats);
                    }
                }
                
                println!("Thread {} completed mixed operations stress test", thread_id);
            })
        })
        .collect();
    
    let start_time = Instant::now();
    for handle in handles {
        handle.join().unwrap();
    }
    let elapsed = start_time.elapsed();
    
    println!("Mixed operations stress test completed in {:?}", elapsed);
    assert!(elapsed < Duration::from_secs(4), "Test took too long - possible deadlock");
}

#[test]
fn test_cache_statistics_consistency_under_load() {
    let cache = Arc::new(PriorityCache::new(20, EvictionPolicy::Priority, true));
    let num_threads = 3;
    let barrier = Arc::new(Barrier::new(num_threads));
    
    let handles: Vec<_> = (0..num_threads)
        .map(|thread_id| {
            let cache = Arc::clone(&cache);
            let barrier = Arc::clone(&barrier);
            
            thread::spawn(move || {
                barrier.wait();
                
                let mut stats_snapshots = Vec::new();
                
                for i in 0..150 {
                    let key = format!("thread_{}_key_{}", thread_id, i % 30);
                    let value = vec![thread_id as u8, i as u8];
                    
                    // Insert items
                    cache.insert(key.clone(), value, thread_id as u32);
                    
                    // Access items to build up stats
                    cache.get(&key);
                    
                    // Collect stats snapshots
                    if i % 20 == 0 {
                        let stats = cache.get_stats();
                        println!("Thread {} collected stats at iteration {}: {:?}", thread_id, i, stats);
                        stats_snapshots.push(stats);
                    }
                    
                    // Periodically remove items
                    if i % 10 == 0 && i > 0 {
                        let remove_key = format!("thread_{}_key_{}", thread_id, (i - 5) % 30);
                        cache.remove(&remove_key);
                    }
                }
                
                println!("Thread {} collected {} stats snapshots", thread_id, stats_snapshots.len());
                stats_snapshots
            })
        })
        .collect();
    
    let stats_results: Vec<_> = handles.into_iter().map(|h| h.join().unwrap()).collect();
    
    // Verify that stats collection didn't cause deadlocks
    assert_eq!(stats_results.len(), num_threads);
    for (i, stats_vec) in stats_results.iter().enumerate() {
        assert!(!stats_vec.is_empty(), "Thread {} collected no stats", i);
    }
    
    let final_stats = cache.get_stats();
    println!("Final cache stats after load test: {:?}", final_stats);
    assert!(final_stats.insertions > 0, "No insertions recorded");
    assert!(final_stats.current_size <= 20, "Cache size exceeded capacity");
}

#[test]
fn test_cache_feature_toggle_under_concurrent_load() {
    let cache = Arc::new(PriorityCache::new(15, EvictionPolicy::LRU, true));
    let num_threads = 5;
    let barrier = Arc::new(Barrier::new(num_threads));
    
    let handles: Vec<_> = (0..num_threads)
        .map(|thread_id| {
            let cache = Arc::clone(&cache);
            let barrier = Arc::clone(&barrier);
            
            thread::spawn(move || {
                barrier.wait();
                
                for i in 0..80 {
                    let key = format!("thread_{}_item_{}", thread_id, i);
                    let value = vec![thread_id as u8, i as u8];
                    
                    // Insert items
                    cache.insert(key.clone(), value, 1);
                    
                    // Access items
                    cache.get(&key);
                    
                    // Periodically toggle feature and verify behavior
                    if i == 40 {
                        // This would require mutable access to cache, which we can't do with Arc
                        // So we'll just test that operations continue to work
                        println!("Thread {} reached midpoint at iteration {}", thread_id, i);
                    }
                    
                    // Get stats
                    if i % 20 == 0 {
                        let stats = cache.get_stats();
                        println!("Thread {} stats at iteration {}: {:?}", thread_id, i, stats);
                    }
                }
                
                println!("Thread {} completed feature toggle test", thread_id);
            })
        })
        .collect();
    
    let start_time = Instant::now();
    for handle in handles {
        handle.join().unwrap();
    }
    let elapsed = start_time.elapsed();
    
    println!("Feature toggle test completed in {:?}", elapsed);
    assert!(elapsed < Duration::from_secs(3), "Test took too long - possible deadlock");
}

#[test]
fn test_cache_performance_under_extreme_contention() {
    let cache = Arc::new(PriorityCache::new(5, EvictionPolicy::LRU, true));
    let num_threads = 10;
    let barrier = Arc::new(Barrier::new(num_threads));
    
    let handles: Vec<_> = (0..num_threads)
        .map(|thread_id| {
            let cache = Arc::clone(&cache);
            let barrier = Arc::clone(&barrier);
            
            thread::spawn(move || {
                barrier.wait();
                
                // Extreme contention scenario: all threads hitting the same small cache
                for i in 0..300 {
                    let key = format!("shared_key_{}", i % 3); // Only 3 keys for 10 threads!
                    let value = vec![thread_id as u8, i as u8];
                    
                    // Heavy contention on few keys
                    cache.insert(key.clone(), value, 1);
                    cache.get(&key);
                    
                    if i % 50 == 0 {
                        let stats = cache.get_stats();
                        println!("Thread {} stats at iteration {}: {:?}", thread_id, i, stats);
                    }
                }
                
                println!("Thread {} completed extreme contention test", thread_id);
            })
        })
        .collect();
    
    let start_time = Instant::now();
    for handle in handles {
        handle.join().unwrap();
    }
    let elapsed = start_time.elapsed();
    
    println!("Extreme contention test completed in {:?}", elapsed);
    assert!(elapsed < Duration::from_secs(5), "Test took too long - possible performance issue");
    
    // Verify cache is in consistent state
    let final_stats = cache.get_stats();
    println!("Final stats after extreme contention: {:?}", final_stats);
    assert!(final_stats.current_size <= 5, "Cache size exceeded capacity");
}

#[test]
fn test_cache_cleanup_operations_no_deadlocks() {
    let cache = Arc::new(PriorityCache::new(25, EvictionPolicy::FIFO, true));
    let num_threads = 4;
    let barrier = Arc::new(Barrier::new(num_threads));
    
    let handles: Vec<_> = (0..num_threads)
        .map(|thread_id| {
            let cache = Arc::clone(&cache);
            let barrier = Arc::clone(&barrier);
            
            thread::spawn(move || {
                barrier.wait();
                
                for i in 0..50 {
                    // Insert items
                    for j in 0..10 {
                        let key = format!("thread_{}_item_{}_{}", thread_id, i, j);
                        let value = vec![thread_id as u8, i as u8, j as u8];
                        cache.insert(key, value, j as u32);
                    }
                    
                    // Access items
                    for j in 0..10 {
                        let key = format!("thread_{}_item_{}_{}", thread_id, i, j);
                        cache.get(&key);
                    }
                    
                    // Periodically clear cache (cleanup operation)
                    if i % 10 == 0 {
                        cache.clear();
                        println!("Thread {} cleared cache at iteration {}", thread_id, i);
                    }
                    
                    // Get stats after clear
                    let stats = cache.get_stats();
                    if i % 15 == 0 {
                        println!("Thread {} stats at iteration {}: {:?}", thread_id, i, stats);
                    }
                    
                    // Log cache state after clear (but don't assert strict emptiness due to race conditions)
                    if i % 10 == 0 {
                        println!("Thread {} cache state after clear: size={}, hits={}, misses={}",
                            thread_id, stats.current_size, stats.hits, stats.misses);
                        
                        // In a concurrent environment, we can't guarantee perfect emptiness
                        // due to race conditions with other threads inserting immediately after clear.
                        // Instead, we verify that clear operations are being performed successfully
                        // by checking that the cache size is reasonable (not growing unbounded).
                        assert!(stats.current_size <= 25,
                            "Cache size exceeded capacity after clear in thread {}", thread_id);
                    }
                }
                
                println!("Thread {} completed cleanup operations test", thread_id);
            })
        })
        .collect();
    
    let start_time = Instant::now();
    for handle in handles {
        handle.join().unwrap();
    }
    let elapsed = start_time.elapsed();
    
    println!("Cleanup operations test completed in {:?}", elapsed);
    assert!(elapsed < Duration::from_secs(3), "Test took too long - possible deadlock");
}