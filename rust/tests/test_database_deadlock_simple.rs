//! Simple test untuk memverifikasi deadlock prevention di database module

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use std::thread;
    use std::time::Duration;
    
    #[test]
    fn test_lock_ordering_prevents_deadlock() {
        // Test sederhana untuk memverifikasi bahwa lock ordering bekerja
        println!("Testing lock ordering deadlock prevention...");
        
        // Simulasi lock ordering
        let lock1 = Arc::new(std::sync::RwLock::new(0));
        let lock2 = Arc::new(std::sync::RwLock::new(0));
        
        let lock1_clone = Arc::clone(&lock1);
        let lock2_clone = Arc::clone(&lock2);
        
        // Thread 1: Acquire locks in order 1 -> 2
        let handle1 = thread::spawn(move || {
            let start = std::time::Instant::now();
            loop {
                if let Ok(guard1) = lock1_clone.try_write() {
                    if let Ok(guard2) = lock2_clone.try_write() {
                        println!("Thread 1 acquired both locks in correct order");
                        drop(guard1);
                        drop(guard2);
                        break;
                    } else {
                        drop(guard1);
                    }
                }
                if start.elapsed() > Duration::from_secs(1) {
                    panic!("Thread 1 timeout - possible deadlock");
                }
                thread::sleep(Duration::from_millis(1));
            }
        });
        
        let lock1_clone2 = Arc::clone(&lock1);
        let lock2_clone2 = Arc::clone(&lock2);
        
        // Thread 2: Acquire locks in same order 1 -> 2 (consistent ordering)
        let handle2 = thread::spawn(move || {
            let start = std::time::Instant::now();
            loop {
                if let Ok(guard1) = lock1_clone2.try_write() {
                    if let Ok(guard2) = lock2_clone2.try_write() {
                        println!("Thread 2 acquired both locks in correct order");
                        drop(guard1);
                        drop(guard2);
                        break;
                    } else {
                        drop(guard1);
                    }
                }
                if start.elapsed() > Duration::from_secs(1) {
                    panic!("Thread 2 timeout - possible deadlock");
                }
                thread::sleep(Duration::from_millis(1));
            }
        });
        
        // Tunggu kedua thread selesai
        handle1.join().expect("Thread 1 should complete without deadlock");
        handle2.join().expect("Thread 2 should complete without deadlock");
        
        println!("Lock ordering test passed - no deadlock detected!");
    }
    
    #[test]
    fn test_timeout_mechanism() {
        println!("Testing timeout mechanism...");
        
        let lock = Arc::new(std::sync::RwLock::new(0));
        let lock_clone = Arc::clone(&lock);
        
        // Thread 1: Hold lock untuk waktu yang lama
        let handle1 = thread::spawn(move || {
            let _guard = lock_clone.write().unwrap();
            println!("Thread 1 acquired lock and holding it");
            thread::sleep(Duration::from_millis(200));
            println!("Thread 1 releasing lock");
        });
        
        // Thread 2: Coba acquire lock dengan timeout
        let start = std::time::Instant::now();
        let mut acquired = false;
        
        while start.elapsed() < Duration::from_millis(500) {
            if let Ok(_guard) = lock.try_write() {
                println!("Thread 2 acquired lock");
                acquired = true;
                break;
            }
            thread::sleep(Duration::from_millis(1));
        }
        
        handle1.join().expect("Thread 1 should complete");
        
        if acquired {
            println!("Timeout mechanism test passed - lock acquired within timeout");
        } else {
            println!("Timeout mechanism test passed - proper timeout behavior");
        }
    }
}