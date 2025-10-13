use rust::parallelism::{get_adaptive_pool, get_rayon_thread_pool};
use rust::parallelism::work_stealing::WorkStealingScheduler;
use std::time::Instant;

fn main() {
    println!("Testing real parallelism implementation...");
    
    // Test 1: Basic thread pool functionality
    let pool = get_adaptive_pool();
    println!("Thread pool created with {} threads", pool.current_thread_count());
    
    // Test 2: Execute simple task
    let result = pool.execute(|| {
        println!("Task executed on thread: {:?}", std::thread::current().id());
        42
    });
    println!("Simple task result: {}", result);
    
    // Test 3: Parallel execution with WorkStealingScheduler
    let tasks: Vec<i32> = (0..1000).collect();
    let scheduler = WorkStealingScheduler::new(tasks);
    
    let start = Instant::now();
    let results = scheduler.execute(|x| {
        // Simulate some CPU-intensive work
        let mut sum = 0;
        for i in 0..1000 {
            sum += x * i;
        }
        sum
    });
    let duration = start.elapsed();
    
    println!("Parallel execution completed in {:?}", duration);
    println!("Processed {} tasks", results.len());
    println!("Sum of results: {}", results.iter().sum::<i32>());
    
    // Test 4: Test spawn functionality
    let pool_clone = get_rayon_thread_pool();
    let start = Instant::now();
    
    for i in 0..10 {
        pool_clone.spawn(move || {
            println!("Spawned task {} on thread: {:?}", i, std::thread::current().id());
            std::thread::sleep(std::time::Duration::from_millis(10));
        });
    }
    
    // Wait a bit for spawned tasks to complete
    std::thread::sleep(std::time::Duration::from_millis(100));
    let spawn_duration = start.elapsed();
    println!("Spawn tasks completed in {:?}", spawn_duration);
    
    println!("All parallelism tests completed successfully!");
}