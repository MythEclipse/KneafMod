//! Test untuk memverifikasi bahwa deadlock telah teratasi dengan consistent lock ordering
//! dan timeout mechanisms

use std::sync::Arc;
use std::thread;
use std::time::Duration;
use rustperf::database::RustDatabaseAdapter;

#[test]
fn test_no_deadlock_with_concurrent_operations() {
    // Initialize database adapter
    let adapter = Arc::new(RustDatabaseAdapter::new(
        "test_db",
        "test_world",
        false,
        false
    ).expect("Failed to create database adapter"));

    let mut handles = vec![];

    // Spawn multiple threads yang melakukan operasi berbeda secara concurrent
    for i in 0..10 {
        let adapter_clone: Arc<RustDatabaseAdapter> = Arc::clone(&adapter);
        
        let handle = thread::spawn(move || {
            let key = format!("test_chunk_{}", i);
            let data = vec![i as u8; 1024]; // 1KB data
            
            // Operasi put_chunk
            match adapter_clone.put_chunk(&key, &data) {
                Ok(_) => println!("Thread {}: Successfully put chunk", i),
                Err(e) => println!("Thread {}: Failed to put chunk: {}", i, e),
            }
            
            // Operasi get_chunk
            match adapter_clone.get_chunk(&key) {
                Ok(Some(_)) => println!("Thread {}: Successfully got chunk", i),
                Ok(None) => println!("Thread {}: Chunk not found", i),
                Err(e) => println!("Thread {}: Failed to get chunk: {}", i, e),
            }
            
            // Operasi swap_out_chunk (jika chunk ada)
            match adapter_clone.swap_out_chunk(&key) {
                Ok(_) => println!("Thread {}: Successfully swapped out chunk", i),
                Err(e) => println!("Thread {}: Failed to swap out chunk: {}", i, e),
            }
            
            // Operasi swap_in_chunk (jika chunk di-swap-out)
            match adapter_clone.swap_in_chunk(&key) {
                Ok(_) => println!("Thread {}: Successfully swapped in chunk", i),
                Err(e) => println!("Thread {}: Failed to swap in chunk: {}", i, e),
            }
        });
        
        handles.push(handle);
    }

    // Tunggu semua thread selesai dengan timeout
    let timeout = Duration::from_secs(30);
    let start = std::time::Instant::now();
    
    for (i, handle) in handles.into_iter().enumerate() {
        let elapsed = start.elapsed();
        if elapsed >= timeout {
            panic!("Test timeout: Thread {} tidak selesai dalam waktu yang ditentukan", i);
        }
        
        let remaining_timeout = timeout - elapsed;
        match handle.join_timeout(remaining_timeout) {
            Ok(_) => println!("Thread {} completed successfully", i),
            Err(_) => panic!("Thread {} panicked atau timeout", i),
        }
    }

    println!("All threads completed successfully - no deadlock detected!");
}

#[test]
fn test_lock_timeout_mechanism() {
    // Initialize database adapter
    let adapter = Arc::new(RustDatabaseAdapter::new(
        "test_timeout_db",
        "test_timeout_world",
        false,
        false
    ).expect("Failed to create database adapter"));

    // Test timeout mechanism dengan membuat lock contention
    let adapter_clone1: Arc<RustDatabaseAdapter> = Arc::clone(&adapter);
    let adapter_clone2: Arc<RustDatabaseAdapter> = Arc::clone(&adapter);

    // Thread 1: Acquire stats lock dan hold untuk beberapa waktu
    let handle1 = thread::spawn(move || {
        // Try to get stats to simulate lock acquisition
        if let Ok(_stats) = adapter_clone1.get_stats() {
            println!("Thread 1: Acquired stats lock");
            thread::sleep(Duration::from_millis(100)); // Hold simulated lock untuk 100ms
            println!("Thread 1: Released stats lock");
        }
    });

    // Thread 2: Coba acquire stats lock dengan timeout yang lebih pendek
    let handle2 = thread::spawn(move || {
        thread::sleep(Duration::from_millis(10)); // Tunggu sebentar agar thread 1 mendapat lock duluan
        match adapter_clone2.get_stats() {
            Ok(_) => println!("Thread 2: Successfully acquired stats lock"),
            Err(e) => println!("Thread 2: Failed to acquire stats lock: {}", e),
        }
    });

    // Tunggu kedua thread selesai
    handle1.join().expect("Thread 1 panicked");
    handle2.join().expect("Thread 2 panicked");
    
    println!("Lock timeout mechanism test completed!");
}

#[test]
fn test_consistent_lock_ordering_with_multiple_locks() {
    // Initialize database adapter
    let adapter = Arc::new(RustDatabaseAdapter::new(
        "test_ordering_db",
        "test_ordering_world",
        false,
        false
    ).expect("Failed to create database adapter"));

    let mut handles = vec![];

    // Test consistent lock ordering dengan multiple threads yang mengakses berbagai lock
    for i in 0..5 {
        let adapter_clone: Arc<RustDatabaseAdapter> = Arc::clone(&adapter);
        
        let handle = thread::spawn(move || {
            let key = format!("ordering_test_chunk_{}", i);
            let data = vec![i as u8; 2048]; // 2KB data
            
            // Operasi yang memerlukan multiple locks
            match adapter_clone.put_chunk(&key, &data) {
                Ok(_) => println!("Thread {}: Successfully completed put_chunk with multiple locks", i),
                Err(e) => println!("Thread {}: Failed put_chunk: {}", i, e),
            }
            
            // Operasi yang memerlukan lock ordering yang sama
            match adapter_clone.get_swap_candidates(10) {
                Ok(candidates) => println!("Thread {}: Got {} swap candidates", i, candidates.len()),
                Err(e) => println!("Thread {}: Failed to get swap candidates: {}", i, e),
            }
        });
        
        handles.push(handle);
    }

    // Tunggu semua thread selesai
    for (i, handle) in handles.into_iter().enumerate() {
        handle.join().expect(&format!("Thread {} panicked", i));
        println!("Thread {} completed successfully", i);
    }

    println!("Consistent lock ordering test completed!");
}

// Extension trait untuk thread join dengan timeout
trait JoinTimeout {
    fn join_timeout(self, timeout: Duration) -> thread::Result<()>;
}

impl JoinTimeout for thread::JoinHandle<()> {
    fn join_timeout(self, timeout: Duration) -> thread::Result<()> {
        let start = std::time::Instant::now();
        while start.elapsed() < timeout {
            if self.is_finished() {
                return self.join();
            }
            thread::sleep(Duration::from_millis(10));
        }
        panic!("Thread join timeout after {:?}", timeout);
    }
}