use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};

/// Atomic state for thread-safe pool operations
#[derive(Debug)]
pub struct AtomicPoolState {
    pub current_size: AtomicUsize,
    max_size: usize,
    usage_ratio: AtomicU64, // Stored as u64 to represent f64 bits
}

impl AtomicPoolState {
    pub fn new(max_size: usize) -> Self {
        Self {
            current_size: AtomicUsize::new(0),
            max_size,
            usage_ratio: AtomicU64::new(0),
        }
    }

    pub fn get_usage_ratio(&self) -> f64 {
        let bits = self.usage_ratio.load(Ordering::Relaxed);
        f64::from_bits(bits)
    }

    pub fn update_usage_ratio(&self, current_size: usize) {
        let ratio = if self.max_size > 0 {
            current_size as f64 / self.max_size as f64
        } else {
            0.0
        };
        // Use Relaxed ordering for better performance since this is just for monitoring
        self.usage_ratio.store(ratio.to_bits(), Ordering::Relaxed);
    }

    pub fn try_increment_size(&self) -> Option<usize> {
        let mut current = self.current_size.load(Ordering::Relaxed);
        loop {
            if current >= self.max_size {
                return None;
            }
            match self.current_size.compare_exchange_weak(
                current,
                current + 1,
                Ordering::SeqCst,
                Ordering::Relaxed,
            ) {
                Ok(_) => {
                    self.update_usage_ratio(current + 1);
                    return Some(current + 1);
                }
                Err(actual) => current = actual,
            }
        }
    }

    pub fn decrement_size(&self) {
        let previous = self.current_size.fetch_sub(1, Ordering::SeqCst);
        if previous > 0 {
            self.update_usage_ratio(previous - 1);
        }
    }

    pub fn get_current_size(&self) -> usize {
        self.current_size.load(Ordering::Relaxed)
    }

    pub fn get_max_size(&self) -> usize {
        self.max_size
    }
}

/// Simple atomic counter for reference counting
#[derive(Debug)]
pub struct AtomicCounter {
    count: AtomicUsize,
}

impl AtomicCounter {
    pub fn new(initial: usize) -> Self {
        Self {
            count: AtomicUsize::new(initial),
        }
    }

    pub fn increment(&self) -> usize {
        self.count.fetch_add(1, Ordering::SeqCst) + 1
    }

    pub fn decrement(&self) -> usize {
        self.count.fetch_sub(1, Ordering::SeqCst) - 1
    }

    pub fn get(&self) -> usize {
        self.count.load(Ordering::SeqCst)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::thread;

    #[test]
    fn test_atomic_increment_decrement() {
        let state = Arc::new(AtomicPoolState::new(10));

        // Test concurrent increment operations
        let mut handles = vec![];

        for _ in 0..5 {
            let state_clone = Arc::clone(&state);
            let handle = thread::spawn(move || {
                for _ in 0..10 {
                    if let Some(_) = state_clone.try_increment_size() {
                        // Simulate some work
                        std::thread::sleep(std::time::Duration::from_micros(10));
                        state_clone.decrement_size();
                    }
                }
            });
            handles.push(handle);
        }

        // Wait for all threads to complete
        for handle in handles {
            handle.join().expect("Thread panicked");
        }

        // Verify final state is consistent
        let final_size = state.get_current_size();
        assert_eq!(
            final_size, 0,
            "Final pool size should be 0 after all operations"
        );
    }

    #[test]
    fn test_max_size_boundary() {
        let state = AtomicPoolState::new(3);

        // Fill to capacity
        assert!(state.try_increment_size().is_some());
        assert!(state.try_increment_size().is_some());
        assert!(state.try_increment_size().is_some());

        // Should not allow increment beyond max
        assert!(state.try_increment_size().is_none());
        assert_eq!(state.get_current_size(), 3);

        // After decrement, should allow increment again
        state.decrement_size();
        assert!(state.try_increment_size().is_some());
        assert_eq!(state.get_current_size(), 3);
    }

    #[test]
    fn test_race_condition_prevention() {
        let state = Arc::new(AtomicPoolState::new(100));
        let mut handles = vec![];

        // Create many threads that try to increment concurrently
        for _ in 0..10 {
            let state_clone = Arc::clone(&state);
            let handle = thread::spawn(move || {
                let mut success_count = 0;
                for i in 0..20 {
                    if let Some(_) = state_clone.try_increment_size() {
                        success_count += 1;
                        // Simulate work
                        std::thread::sleep(std::time::Duration::from_micros(1));

                        // Decrement with some probability
                        if i % 3 == 0 {
                            state_clone.decrement_size();
                        }
                    }
                }
                success_count
            });
            handles.push(handle);
        }

        // Collect results
        let mut _total_success = 0;
        for handle in handles {
            let success = handle.join().expect("Thread panicked");
            _total_success += success;
        }

        // The final size should be consistent with the operations
        let final_size = state.get_current_size();
        assert!(final_size <= 100, "Final size should not exceed max_size");
    }

    #[test]
    fn test_atomic_counter() {
        let counter = AtomicCounter::new(0);

        assert_eq!(counter.get(), 0);
        assert_eq!(counter.increment(), 1);
        assert_eq!(counter.get(), 1);
        assert_eq!(counter.increment(), 2);
        assert_eq!(counter.get(), 2);
        assert_eq!(counter.decrement(), 1);
        assert_eq!(counter.get(), 1);
        assert_eq!(counter.decrement(), 0);
        assert_eq!(counter.get(), 0);
    }

    #[test]
    fn test_atomic_counter_concurrent() {
        let counter = Arc::new(AtomicCounter::new(0));
        let mut handles = vec![];

        // Spawn threads that increment
        for _ in 0..10 {
            let counter_clone = Arc::clone(&counter);
            let handle = thread::spawn(move || {
                for _ in 0..100 {
                    counter_clone.increment();
                }
            });
            handles.push(handle);
        }

        // Wait for all threads
        for handle in handles {
            handle.join().expect("Thread panicked");
        }

        // Should have 1000 increments
        assert_eq!(counter.get(), 1000);
    }
}
