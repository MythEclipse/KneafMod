use crate::parallelism::{get_adaptive_pool, get_rayon_thread_pool};
use crate::parallelism::work_stealing::WorkStealingScheduler;
use std::time::Instant;

#[test]
fn test_parallelism_functionality() {
    println!("Testing real parallelism implementation...");
    
    // Test 1: Basic thread pool functionality
    let pool = get_adaptive_pool();
    println!("Thread pool created with {} threads", pool.current_thread_count());
    
    // Test 2: Execute simple task
    let result = pool.execute(|| {
        println!("Task executed on thread: {:?}", std::thread::current().id());
        42
    });
    assert_eq!(result, 42);
    println!("Simple task result: {}", result);
    
    // Test 3: Parallel execution with WorkStealingScheduler
    let tasks: Vec<i32> = (0..100).collect();
    let scheduler = WorkStealingScheduler::new(tasks);
    
    let start = Instant::now();
    let results = scheduler.execute(|x| {
        // Simulate some CPU-intensive work
        let mut sum = 0;
        for i in 0..100 {
            sum += x * i;
        }
        sum
    });
    let duration = start.elapsed();
    
    println!("Parallel execution completed in {:?}", duration);
    println!("Processed {} tasks", results.len());
    assert_eq!(results.len(), 100);
    
    // Test 4: Test spawn functionality
    let pool_clone = get_rayon_thread_pool();
    let start = Instant::now();
    
    for i in 0..5 {
        pool_clone.spawn(move || {
            println!("Spawned task {} on thread: {:?}", i, std::thread::current().id());
            std::thread::sleep(std::time::Duration::from_millis(5));
        });
    }
    
    // Wait a bit for spawned tasks to complete
    std::thread::sleep(std::time::Duration::from_millis(50));
    let spawn_duration = start.elapsed();
    println!("Spawn tasks completed in {:?}", spawn_duration);
    
    println!("All parallelism tests completed successfully!");
}

#[test]
fn test_load_balancing() {
    let tasks: Vec<i32> = (0..50).collect();
    let scheduler = WorkStealingScheduler::new(tasks);
    
    let start = Instant::now();
    let results = scheduler.execute(|x| {
        // Simulate variable workload
        let workload = if x % 2 == 0 { 100 } else { 200 };
        let mut sum = 0;
        for i in 0..workload {
            sum += x * i;
        }
        sum
    });
    let duration = start.elapsed();
    
    println!("Load balancing test completed in {:?}", duration);
    assert_eq!(results.len(), 50);
    println!("Load balancing test passed!");
}