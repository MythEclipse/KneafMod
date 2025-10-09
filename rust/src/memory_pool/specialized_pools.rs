use std::sync::RwLock;
use std::fmt::Debug;

use crate::memory_pool::object_pool::{ObjectPool, PooledObject};

/// Specialized pool for vectors
#[derive(Debug)]
pub struct VecPool<T>
where
    T: Debug,
{
    pub pool: ObjectPool<Vec<T>>,
}

impl<T> Clone for VecPool<T>
where
    T: Debug + Default + Clone + Send + 'static,
{
    fn clone(&self) -> Self {
        VecPool { pool: self.pool.clone_shallow() }
    }
}

impl<T> VecPool<T>
where
    T: Debug + Default + Clone + Send + 'static,
{
    pub fn new(max_size: usize) -> Self {
        Self {
            pool: ObjectPool::new(max_size),
        }
    }

    /// Perform cleanup and return true if any resources were freed
    pub fn cleanup(&self) -> bool {
        // Implement efficient cleanup that trims unused objects
        let mut cleaned = false;
        
        // In a real implementation, we would:
        // 1. Check pool usage statistics
        // 2. Trim excess capacity based on current load
        // 3. Free unused memory buffers
        // 4. Return true if any resources were freed
        
        // For now, maintain the no-op behavior but preserve the API contract
        cleaned
    }

    pub fn get_vec(&self, capacity: usize) -> PooledVec<T> {
        let mut pooled = self.pool.get();
        let vec = pooled.as_mut();
        
        // Fast path: reuse existing vector if it has sufficient capacity
        if vec.capacity() >= capacity {
            vec.clear();
            return pooled;
        }
        
        // Slow path: create new vector with exact capacity
        *vec = Vec::with_capacity(capacity);
        pooled
    }

    /// Get a vector with swap tracking
    pub fn get_vec_with_tracking(
        &self,
        capacity: usize,
        size_bytes: u64,
        allocation_tracker: std::sync::Arc<RwLock<crate::memory_pool::object_pool::SwapAllocationMetrics>>,
        allocation_type: &str,
    ) -> PooledVec<T> {
        let mut pooled = self.pool.get_with_tracking(size_bytes, allocation_tracker, allocation_type);
        let vec = pooled.as_mut();
        vec.clear();
        vec.reserve(capacity);
        // Ensure the vector actually has the requested capacity
        if vec.capacity() < capacity {
            *vec = Vec::with_capacity(capacity);
        }
        pooled
    }
}

pub type PooledVec<T> = PooledObject<Vec<T>>;

/// Specialized pool for strings
#[derive(Debug)]
pub struct StringPool {
    pub pool: ObjectPool<String>,
}

impl Clone for StringPool {
    fn clone(&self) -> Self {
        StringPool { pool: self.pool.clone_shallow() }
    }
}

impl StringPool {
    pub fn new(max_size: usize) -> Self {
        Self {
            pool: ObjectPool::new(max_size),
        }
    }

    /// Perform cleanup and return true if any resources were freed
    pub fn cleanup(&self) -> bool {
        // Implement efficient cleanup that trims unused objects
        let mut cleaned = false;
        
        // In a real implementation, we would:
        // 1. Check pool usage statistics
        // 2. Trim excess capacity based on current load
        // 3. Free unused memory buffers
        // 4. Return true if any resources were freed
        
        // For now, maintain the no-op behavior but preserve the API contract
        cleaned
    }

    pub fn get_string(&self, capacity: usize) -> PooledString {
        let mut pooled = self.pool.get();
        
        // Fast path: reuse existing string if it has sufficient capacity
        if pooled.capacity() >= capacity {
            pooled.clear();
            return pooled;
        }
        
        // Slow path: create new string with exact capacity
        *pooled = String::with_capacity(capacity);
        pooled
    }

    /// Get a string with swap tracking
    pub fn get_string_with_tracking(
        &self,
        capacity: usize,
        size_bytes: u64,
        allocation_tracker: std::sync::Arc<RwLock<crate::memory_pool::object_pool::SwapAllocationMetrics>>,
        allocation_type: &str,
    ) -> PooledString {
        let mut pooled = self.pool.get_with_tracking(size_bytes, allocation_tracker, allocation_type);
        pooled.clear();
        pooled.reserve(capacity);
        pooled
    }
}

pub type PooledString = PooledObject<String>;

// Provide a small shim to satisfy calls to return_to_pool from SmartPooledVec drop
impl<T> PooledObject<Vec<T>> {
    pub fn return_to_pool(&mut self) {
        // Return is handled by Drop in PooledObject; this is a no-op shim to satisfy callers
        // Actual pool return logic is implemented in Drop for PooledObject
    }
}

impl PooledObject<String> {
    pub fn return_to_pool(&mut self) {
        // No-op shim for String pooled objects
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::thread;

    #[test]
    fn test_vec_pool_basic() {
        let pool = VecPool::<u32>::new(10);
        let mut vec = pool.get_vec(100);
        assert_eq!(vec.len(), 0);
        assert!(vec.capacity() >= 100);

        vec.push(42);
        assert_eq!(vec.len(), 1);
        assert_eq!(vec[0], 42);
    }

    #[test]
    fn test_string_pool_basic() {
        let pool = StringPool::new(10);
        let mut string = pool.get_string(100);
        assert_eq!(string.len(), 0);
        assert!(string.capacity() >= 100);

        string.push_str("hello");
        assert_eq!(string.as_str(), "hello");
    }

    #[test]
    fn test_vec_pool_reuse() {
        let pool = VecPool::<u32>::new(10);

        // First allocation
        {
            let mut vec = pool.get_vec(50);
            vec.push(1);
            vec.push(2);
            vec.push(3);
            assert_eq!(vec.as_ref(), &[1, 2, 3]);
        } // vec goes out of scope, should return to pool

        // Second allocation should reuse
        let vec = pool.get_vec(50);
        assert_eq!(vec.len(), 0); // Should be cleared
        assert!(vec.capacity() >= 50); // Should maintain capacity
    }

    #[test]
    fn test_string_pool_reuse() {
        let pool = StringPool::new(10);

        // First allocation
        {
            let mut string = pool.get_string(50);
            string.push_str("test");
            assert_eq!(string.as_str(), "test");
        } // string goes out of scope, should return to pool

        // Second allocation should reuse
        let string = pool.get_string(50);
        assert_eq!(string.len(), 0); // Should be cleared
        assert!(string.capacity() >= 50); // Should maintain capacity
    }

    #[test]
    fn test_concurrent_vec_pool() {
        let pool = Arc::new(VecPool::<u32>::new(100));
        let mut handles = vec![];

        for i in 0..5 {
            let pool_clone = Arc::clone(&pool);
            let handle = thread::spawn(move || {
                for j in 0..20 {
                    let mut vec = pool_clone.get_vec(10);
                    vec.push(i * 100 + j as u32);
                    assert_eq!(vec.len(), 1);
                    assert_eq!(vec[0], i * 100 + j as u32);
                }
            });
            handles.push(handle);
        }

        for handle in handles {
            handle.join().expect("Thread panicked");
        }
    }

    #[test]
    fn test_concurrent_string_pool() {
        let pool = Arc::new(StringPool::new(100));
        let mut handles = vec![];

        for i in 0..5 {
            let pool_clone = Arc::clone(&pool);
            let handle = thread::spawn(move || {
                for j in 0..20 {
                    let mut string = pool_clone.get_string(10);
                    string.push_str(&format!("thread_{}_{}", i, j));
                    assert!(string.starts_with(&format!("thread_{}_{}", i, j)));
                }
            });
            handles.push(handle);
        }

        for handle in handles {
            handle.join().expect("Thread panicked");
        }
    }
}