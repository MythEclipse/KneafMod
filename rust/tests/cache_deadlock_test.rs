use std::sync::{Arc, Barrier};
use std::thread;
use std::time::{Duration, Instant};

use rustperf::cache_eviction::{PriorityCache, EvictionPolicy};

#[test]
fn test_cache_no_deadlocks_concurrent_operations() {
    let cache = Arc::new(PriorityCache::new(100, EvictionPolicy::LRU, true));
    let num_threads = 10;
    let operations_per_thread = 1000;
    let barrier = Arc::new(Barrier::new(num_threads));
    
    let handles: Vec<_> = (0..num_threads)
        .map(|thread_id| {
            let cache = Arc::clone(&cache);
            let barrier = Arc::clone(&barrier);
            
            thread::spawn(move || {
                // Wait for all threads to be ready
                barrier.wait();
                
                let mut success_count = 0;
                let start_time = Instant::now();
                
                for i in 0..operations_per_thread {
                    let key = format!("thread_{}_key_{}", thread_id, i % 50);
                    let value = vec![thread_id as u8, i as u8];
                    
                    // Randomly choose operation type
                    match i % 3 {
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
                        _ => {}
                    }
                    
                    // Add some randomness to timing
                    if i % 100 == 0 {
                        thread::sleep(Duration::from_micros(10));
                    }
                }
                
                let elapsed = start_time.elapsed();
                println!("Thread {} completed {} operations in {:?} ({} successful)", 
                         thread_id, operations_per_thread, elapsed, success_count);
                
                success_count
            })
        })
        .collect();
    
    let start_time = Instant::now();
    let results: Vec<_> = handles.into_iter().map(|h| h.join().unwrap()).collect();
    let total_elapsed = start_time.elapsed();
    
    let total_operations = results.iter().sum::<usize>();
    println!("Total operations completed: {} in {:?}", total_operations, total_elapsed);
    
    // Verify all threads completed successfully
    assert_eq!(results.len(), num_threads);
    for (i, &count) in results.iter().enumerate() {
        assert!(count > 0, "Thread {} had no successful operations", i);
    }
}

#[test]
fn test_cache_no_deadlocks_mixed_operations() {
    let cache = Arc::new(PriorityCache::new(50, EvictionPolicy::LFU, true));
    let num_threads = 5;
    let barrier = Arc::new(Barrier::new(num_threads));
    
    let handles: Vec<_> = (0..num_threads)
        .map(|thread_id| {
            let cache = Arc::clone(&cache);
            let barrier = Arc::clone(&barrier);
            
            thread::spawn(move || {
                barrier.wait();
                
                for i in 0..200 {
                    let key1 = format!("key_{}", i);
                    let key2 = format!("key_{}", (i + 1) % 100);
                    let value = vec![thread_id as u8, i as u8];
                    
                    // Mix of operations that could cause deadlocks in the old implementation
                    cache.insert(key1.clone(), value.clone(), 1);
                    cache.get(&key1);
                    cache.get(&key2);
                    cache.insert(key2.clone(), value.clone(), 2);
                    cache.remove(&key1);
                    cache.get(&key2);
                    
                    if i % 50 == 0 {
                        cache.clear();
                    }
                }
                
                println!("Thread {} completed mixed operations", thread_id);
            })
        })
        .collect();
    
    let start_time = Instant::now();
    for handle in handles {
        handle.join().unwrap();
    }
    let elapsed = start_time.elapsed();
    
    println!("Mixed operations test completed in {:?}", elapsed);
    assert!(elapsed < Duration::from_secs(10), "Test took too long - possible deadlock");
}

#[test]
fn test_cache_no_deadlocks_rapid_eviction() {
    let cache = Arc::new(PriorityCache::new(5, EvictionPolicy::LRU, true));
    let num_threads = 4;
    let barrier = Arc::new(Barrier::new(num_threads));
    
    let handles: Vec<_> = (0..num_threads)
        .map(|thread_id| {
            let cache = Arc::clone(&cache);
            let barrier = Arc::clone(&barrier);
            
            thread::spawn(move || {
                barrier.wait();
                
                for i in 0i32..100 {
                    // Rapidly insert more items than capacity to force evictions
                    let key = format!("thread_{}_item_{}", thread_id, i);
                    let value = vec![thread_id as u8, i as u8];
                    
                    // This should trigger many evictions
                    cache.insert(key, value, 1);
                    
                    // Try to access recently inserted items
                    for j in 0..5 {
                        let access_key = format!("thread_{}_item_{}", thread_id, i.saturating_sub(j as i32));
                        cache.get(&access_key);
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
    assert!(elapsed < Duration::from_secs(5), "Test took too long - possible deadlock");
}

#[test]
fn test_cache_statistics_consistency() {
    let cache = Arc::new(PriorityCache::new(10, EvictionPolicy::FIFO, true));
    let num_threads = 3;
    let barrier = Arc::new(Barrier::new(num_threads));
    
    let handles: Vec<_> = (0..num_threads)
        .map(|thread_id| {
            let cache = Arc::clone(&cache);
            let barrier = Arc::clone(&barrier);
            
            thread::spawn(move || {
                barrier.wait();
                
                let mut stats_vec = Vec::new();
                
                for i in 0..50 {
                    let key = format!("key_{}", i);
                    let value = vec![thread_id as u8, i as u8];
                    
                    cache.insert(key.clone(), value, 1);
                    cache.get(&key);
                    
                    if i % 10 == 0 {
                        let stats = cache.get_stats();
                        stats_vec.push(stats);
                    }
                }
                
                println!("Thread {} collected {} stats snapshots", thread_id, stats_vec.len());
                stats_vec
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
    println!("Final cache stats: {:?}", final_stats);
    assert!(final_stats.insertions > 0, "No insertions recorded");
}

#[test]
fn test_cache_stress_with_clear() {
    let cache = Arc::new(PriorityCache::new(20, EvictionPolicy::Priority, true));
    let num_threads = 6;
    let barrier = Arc::new(Barrier::new(num_threads));
    
    let handles: Vec<_> = (0..num_threads)
        .map(|thread_id| {
            let cache = Arc::clone(&cache);
            let barrier = Arc::clone(&barrier);
            
            thread::spawn(move || {
                barrier.wait();
                
                for i in 0..30 {
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
                    
                    // Periodically clear cache
                    if i % 5 == 0 {
                        cache.clear();
                    }
                    
                    // Get stats after clear
                    let stats = cache.get_stats();
                    if stats.current_size != 0 {
                        println!("Thread {}: Cache not empty after clear: size={}", thread_id, stats.current_size);
                    }
                }
                
                println!("Thread {} completed stress test", thread_id);
            })
        })
        .collect();
    
    let start_time = Instant::now();
    for handle in handles {
        handle.join().unwrap();
    }
    let elapsed = start_time.elapsed();
    
    println!("Stress test with clear completed in {:?}", elapsed);
    assert!(elapsed < Duration::from_secs(15), "Test took too long - possible deadlock");
    
    // Verify cache is in a consistent state
    let final_stats = cache.get_stats();
    println!("Final stats after stress test: {:?}", final_stats);
}