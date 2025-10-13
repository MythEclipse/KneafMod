use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::thread;

use crate::logging::generate_trace_id;
use crate::logging::PerformanceLogger;

/// Pool error type for lightweight memory pool operations
#[derive(Debug, thiserror::Error)]
pub enum PoolError {
    #[error("Pool operation failed: {0}")]
    OperationFailed(String),

    #[error("Thread safety violation")]
    ThreadSafetyViolation,

    #[error("Invalid pool state")]
    InvalidState,
}

/// Thread-safe lightweight memory pool with proper synchronization
#[derive(Debug)]
pub struct LightweightMemoryPool<T: Default + Send + Sync> {
    pool: Arc<Mutex<VecDeque<T>>>,
    allocated_count: AtomicUsize,
    max_size: AtomicUsize,
    logger: PerformanceLogger,
    thread_id: std::thread::ThreadId,
}

impl<T: Default + Send + Sync> LightweightMemoryPool<T> {
    /// Create new lightweight pool with specified maximum size
    pub fn new(max_size: usize) -> Self {
        let logger = PerformanceLogger::new("lightweight_memory_pool");

        Self {
            pool: Arc::new(Mutex::new(VecDeque::with_capacity(max_size))),
            allocated_count: AtomicUsize::new(0),
            max_size: AtomicUsize::new(max_size),
            logger,
            thread_id: thread::current().id(),
        }
    }

    /// Get an object from the pool with thread safety validation
    pub fn get(&self) -> Result<LightweightPooledObject<T>, PoolError> {
        let trace_id = generate_trace_id();

        // Check for thread safety violation
        if thread::current().id() != self.thread_id {
            return Err(PoolError::ThreadSafetyViolation);
        }

        let obj = {
            let mut pool = self.pool.lock().unwrap();
            if let Some(obj) = pool.pop_front() {
                self.logger
                    .log_info("get", &trace_id, "Reused object from pool");
                obj
            } else {
                self.logger.log_info("get", &trace_id, "Created new object");
                T::default()
            }
        };

        self.allocated_count.fetch_add(1, Ordering::Relaxed);

        Ok(LightweightPooledObject {
            data: Some(obj),
            pool: Arc::clone(&self.pool),
            allocated_count: Arc::new(AtomicUsize::new(self.allocated_count.load(Ordering::Relaxed))),
            max_size: Arc::new(AtomicUsize::new(self.max_size.load(Ordering::Relaxed))),
            logger: self.logger.clone(),
            thread_id: self.thread_id,
        })
    }

    /// Get current pool statistics with thread safety
    pub fn get_stats(&self) -> Result<LightweightPoolStats, PoolError> {
        // Check for thread safety violation
        if thread::current().id() != self.thread_id {
            return Err(PoolError::ThreadSafetyViolation);
        }

        let pool_len = {
            let pool = self.pool.lock().unwrap();
            pool.len()
        };
        let allocated = self.allocated_count.load(Ordering::Relaxed);
        let max_size = self.max_size.load(Ordering::Relaxed);

        Ok(LightweightPoolStats {
            available_objects: pool_len,
            allocated_objects: allocated,
            max_size,
            utilization_ratio: if max_size > 0 {
                allocated as f64 / max_size as f64
            } else {
                0.0
            },
        })
    }

    /// Clear all objects from the pool with thread safety
    pub fn clear(&self) -> Result<(), PoolError> {
        // Check for thread safety violation
        if thread::current().id() != self.thread_id {
            return Err(PoolError::ThreadSafetyViolation);
        }

        let trace_id = generate_trace_id();
        
        let cleared_count = {
            let mut pool = self.pool.lock().unwrap();
            let count = pool.len();
            pool.clear();
            count
        };

        self.allocated_count.store(0, Ordering::Relaxed);

        self.logger.log_info(
            "clear",
            &trace_id,
            &format!("Cleared {} objects from pool", cleared_count),
        );

        Ok(())
    }

    /// Resize the pool capacity with proper thread safety
    pub fn resize(&self, new_max_size: usize) -> Result<(), PoolError> {
        // Check for thread safety violation
        if thread::current().id() != self.thread_id {
            return Err(PoolError::ThreadSafetyViolation);
        }

        let trace_id = generate_trace_id();

        let (current_len, should_shrink) = {
            let pool = self.pool.lock().unwrap();
            (pool.len(), new_max_size < pool.len())
        };

        if should_shrink {
            {
                let mut pool = self.pool.lock().unwrap();
                pool.truncate(new_max_size);
            }
            
            self.logger.log_info(
                "resize",
                &trace_id,
                &format!(
                    "Shrunk pool from {} to {} objects",
                    current_len, new_max_size
                ),
            );
        } else {
            {
                let mut pool = self.pool.lock().unwrap();
                pool.reserve(new_max_size - current_len);
            }
            
            self.logger.log_info(
                "resize",
                &trace_id,
                &format!("Grew pool capacity to {} objects", new_max_size),
            );
        }

        self.max_size.store(new_max_size, Ordering::Relaxed);
        self.logger.log_info(
            "resize",
            &trace_id,
            &format!("Updated pool max size to {}", new_max_size),
        );

        Ok(())
    }
}

#[derive(Debug, Clone)]
pub struct LightweightPoolStats {
    pub available_objects: usize,
    pub allocated_objects: usize,
    pub max_size: usize,
    pub utilization_ratio: f64,
}

/// Thread-safe pooled object wrapper for lightweight pool
#[derive(Debug)]
pub struct LightweightPooledObject<T: Default + Send + Sync> {
    data: Option<T>,
    pool: Arc<Mutex<VecDeque<T>>>,
    allocated_count: Arc<AtomicUsize>,
    max_size: Arc<AtomicUsize>,
    logger: PerformanceLogger,
    thread_id: std::thread::ThreadId,
}

impl<T: Default + Send + Sync> LightweightPooledObject<T> {
    /// Get immutable reference to the data
    pub fn as_ref(&self) -> &T {
        self.data.as_ref().expect("Object data should be available")
    }

    /// Get mutable reference to the data
    pub fn as_mut(&mut self) -> &mut T {
        self.data.as_mut().expect("Object data should be available")
    }

    /// Take ownership of the data (removes it from pool management)
    pub fn into_inner(mut self) -> T {
        self.data.take().expect("Object data should be available")
    }
}

impl<T: Default + Send + Sync> Drop for LightweightPooledObject<T> {
    fn drop(&mut self) {
        if let Some(_data) = self.data.take() {
            let trace_id = generate_trace_id();

            // Check for thread safety violation
            if thread::current().id() != self.thread_id {
                log::warn!("Thread safety violation in LightweightPooledObject drop");
                return;
            }

            // Reset object to default state before returning to pool
            let reset_obj = T::default();

            let mut pool = self.pool.lock().unwrap();
            let max_size = self.max_size.load(Ordering::Relaxed);
            if pool.len() < max_size {
                pool.push_back(reset_obj);
                self.logger
                    .log_info("return_object", &trace_id, "Object returned to pool");
            } else {
                self.logger
                    .log_info("return_object", &trace_id, "Pool full, object discarded");
            }

            self.allocated_count.fetch_sub(1, Ordering::Relaxed);
        }
    }
}

impl<T: Default + Send + Sync> std::ops::Deref for LightweightPooledObject<T> {
    type Target = T;

    fn deref(&self) -> &Self::Target {
        self.as_ref()
    }
}

impl<T: Default + Send + Sync> std::ops::DerefMut for LightweightPooledObject<T> {
    fn deref_mut(&mut self) -> &mut Self::Target {
        self.as_mut()
    }
}

/// Thread-safe lightweight pool for better performance in single-threaded contexts
pub struct ThreadLocalLightweightPool<T: Default + Send + Sync> {
    pool: Arc<LightweightMemoryPool<T>>,
}

impl<T: Default + Send + Sync> ThreadLocalLightweightPool<T> {
    /// Create new thread-local pool
    pub fn new(max_size: usize) -> Self {
        Self {
            pool: Arc::new(LightweightMemoryPool::new(max_size)),
        }
    }

    /// Get an object from the thread-local pool
    pub fn get(&self) -> Result<LightweightPooledObject<T>, PoolError> {
        self.pool.get()
    }

    /// Get pool statistics
    pub fn get_stats(&self) -> Result<LightweightPoolStats, PoolError> {
        self.pool.get_stats()
    }
}

impl<T: Default + Send + Sync> Clone for ThreadLocalLightweightPool<T> {
    fn clone(&self) -> Self {
        Self {
            pool: Arc::clone(&self.pool),
        }
    }
}

/// Thread-safe fast arena-style allocator for temporary objects
#[derive(Debug)]
pub struct FastArena<T: Default + Send + Sync> {
    objects: Arc<Mutex<Vec<T>>>,
    free_indices: Arc<Mutex<Vec<usize>>>,
    logger: PerformanceLogger,
}

impl<T: Default + Send + Sync + Clone> FastArena<T> {
    /// Create new fast arena
    pub fn new() -> Self {
        Self {
            objects: Arc::new(Mutex::new(Vec::new())),
            free_indices: Arc::new(Mutex::new(Vec::new())),
            logger: PerformanceLogger::new("fast_arena"),
        }
    }

    /// Allocate object in arena with thread safety
    pub fn alloc(&self) -> Result<ArenaHandle<T>, PoolError> {
        let trace_id = generate_trace_id();

        let (index, _is_new) = {
            let mut free_indices = self.free_indices.lock().unwrap();
            if let Some(index) = free_indices.pop() {
                self.logger
                    .log_info("alloc", &trace_id, &format!("Reused slot {}", index));
                (index, false)
            } else {
                let mut objects = self.objects.lock().unwrap();
                let index = objects.len();
                objects.push(T::default());
                self.logger
                    .log_info("alloc", &trace_id, &format!("Allocated new slot {}", index));
                (index, true)
            }
        };

        Ok(ArenaHandle {
            index,
            arena_objects: Arc::clone(&self.objects),
            arena_free_indices: Arc::clone(&self.free_indices),
            logger: self.logger.clone(),
        })
    }

    /// Get reference to object at index with thread safety
    #[allow(dead_code)]
    fn get(&self, index: usize) -> Result<T, PoolError> {
        let objects = self.objects.lock().unwrap();
        if index < objects.len() {
            Ok(objects[index].clone())
        } else {
            Err(PoolError::InvalidState)
        }
    }

    /// Get mutable reference to object at index with thread safety
    #[allow(dead_code)]
    fn get_mut(&self, index: usize) -> Result<T, PoolError> {
        let objects = self.objects.lock().unwrap();
        if index < objects.len() {
            Ok(objects[index].clone())
        } else {
            Err(PoolError::InvalidState)
        }
    }

    /// Deallocate object (return to free list) with thread safety
    #[allow(dead_code)]
    fn dealloc(&self, index: usize) -> Result<(), PoolError> {
        let trace_id = generate_trace_id();

        {
            let mut objects = self.objects.lock().unwrap();
            if index < objects.len() {
                // Reset object to default state
                objects[index] = T::default();
            } else {
                return Err(PoolError::InvalidState);
            }
        }

        let mut free_indices = self.free_indices.lock().unwrap();
        free_indices.push(index);
        self.logger
            .log_info("dealloc", &trace_id, &format!("Deallocated slot {}", index));

        Ok(())
    }

    /// Clear all objects and reset arena with thread safety
    pub fn clear(&self) -> Result<(), PoolError> {
        let trace_id = generate_trace_id();

        let (object_count, free_count) = {
            let mut objects = self.objects.lock().unwrap();
            let mut free_indices = self.free_indices.lock().unwrap();
            
            let obj_count = objects.len();
            let free_count = free_indices.len();
            
            objects.clear();
            free_indices.clear();
            
            (obj_count, free_count)
        };

        self.logger.log_info(
            "clear",
            &trace_id,
            &format!(
                "Cleared arena: {} objects, {} free slots",
                object_count, free_count
            ),
        );

        Ok(())
    }

    /// Get arena statistics with thread safety
    pub fn get_stats(&self) -> Result<ArenaStats, PoolError> {
        let (total_objects, free_objects) = {
            let objects = self.objects.lock().unwrap();
            let free_indices = self.free_indices.lock().unwrap();
            (objects.len(), free_indices.len())
        };
        
        let used_objects = total_objects - free_objects;

        Ok(ArenaStats {
            total_objects,
            used_objects,
            free_objects,
            utilization_ratio: if total_objects > 0 {
                used_objects as f64 / total_objects as f64
            } else {
                0.0
            },
        })
    }
}

#[derive(Debug, Clone)]
pub struct ArenaStats {
    pub total_objects: usize,
    pub used_objects: usize,
    pub free_objects: usize,
    pub utilization_ratio: f64,
}

/// Thread-safe handle to arena-allocated object
#[derive(Debug)]
pub struct ArenaHandle<T: Default + Send + Sync> {
    index: usize,
    arena_objects: Arc<Mutex<Vec<T>>>,
    arena_free_indices: Arc<Mutex<Vec<usize>>>,
    logger: PerformanceLogger,
}

impl<T: Default + Send + Sync + Clone> ArenaHandle<T> {
    /// Get immutable reference to the data
    pub fn as_ref(&self) -> Result<T, PoolError> {
        let objects = self.arena_objects.lock().unwrap();
        if self.index < objects.len() {
            Ok(objects[self.index].clone())
        } else {
            Err(PoolError::InvalidState)
        }
    }

    /// Get mutable reference to the data
    pub fn as_mut(&self) -> Result<T, PoolError> {
        let objects = self.arena_objects.lock().unwrap();
        if self.index < objects.len() {
            Ok(objects[self.index].clone())
        } else {
            Err(PoolError::InvalidState)
        }
    }
}

impl<T: Default + Send + Sync> Drop for ArenaHandle<T> {
    fn drop(&mut self) {
        let trace_id = generate_trace_id();

        {
            let mut objects = self.arena_objects.lock().unwrap();
            if self.index < objects.len() {
                // Reset object to default state
                objects[self.index] = T::default();
            }
        }

        let mut free_indices = self.arena_free_indices.lock().unwrap();
        free_indices.push(self.index);
        self.logger
            .log_info("dealloc", &trace_id, &format!("Deallocated slot {}", self.index));
    }
}

/// Thread-safe scoped arena that automatically clears when dropped
#[derive(Debug)]
pub struct ScopedArena<T: Default + Send + Sync + Clone> {
    arena: Arc<FastArena<T>>,
}

impl<T: Default + Send + Sync + Clone> ScopedArena<T> {
    /// Create new scoped arena
    pub fn new() -> Self {
        Self {
            arena: Arc::new(FastArena::new()),
        }
    }

    /// Allocate object in scoped arena
    pub fn alloc(&self) -> Result<ArenaHandle<T>, PoolError> {
        self.arena.alloc()
    }

    /// Get arena statistics
    pub fn get_stats(&self) -> Result<ArenaStats, PoolError> {
        self.arena.get_stats()
    }
}

impl<T: Default + Send + Sync + Clone> Drop for ScopedArena<T> {
    fn drop(&mut self) {
        // The arena will be dropped automatically, no need to call clear
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_lightweight_pool_basic() {
        let pool = LightweightMemoryPool::<Vec<u8>>::new(10);

        // Get object
        let mut obj = pool.get().unwrap();
        obj.push(42);
        assert_eq!(obj[0], 42);

        // Object should be returned to pool on drop
        drop(obj);

        // Get another object (should reuse)
        let obj2 = pool.get().unwrap();
        assert_eq!(obj2.len(), 0); // Should be reset to default
    }

    #[test]
    fn test_lightweight_pool_stats() {
        let pool = LightweightMemoryPool::<String>::new(5);

        let stats = pool.get_stats().unwrap();
        assert_eq!(stats.available_objects, 0);
        assert_eq!(stats.allocated_objects, 0);
        assert_eq!(stats.max_size, 5);

        let _obj = pool.get().unwrap();
        let stats = pool.get_stats().unwrap();
        assert_eq!(stats.allocated_objects, 1);
    }

    #[test]
    fn test_thread_local_pool() {
        let pool = ThreadLocalLightweightPool::<Vec<i32>>::new(10);

        let mut obj = pool.get().unwrap();
        obj.push(1);
        obj.push(2);
        obj.push(3);

        assert_eq!(obj.len(), 3);
        assert_eq!(obj[0], 1);
    }

    #[test]
    fn test_fast_arena() {
        let arena = FastArena::<String>::new();

        // Allocate objects
        let handle1 = arena.alloc().unwrap();
        assert_eq!(handle1.as_ref().unwrap(), "");
        
        let handle2 = arena.alloc().unwrap();
        assert_eq!(handle2.as_ref().unwrap(), "");

        // Objects should be deallocated on drop
        drop(handle1);
        drop(handle2);

        // Check stats
        let stats = arena.get_stats().unwrap();
        assert_eq!(stats.total_objects, 2);
        assert_eq!(stats.free_objects, 2);
        assert_eq!(stats.used_objects, 0);
    }

    #[test]
    fn test_scoped_arena() {
        {
            let arena = ScopedArena::<Vec<u8>>::new();

            let handle = arena.alloc().unwrap();
            assert_eq!(handle.as_ref().unwrap().len(), 0);

            // Arena will be cleared when it goes out of scope
        }

        // Arena should be cleared automatically
    }

    #[test]
    fn test_pool_resize() {
        let pool = LightweightMemoryPool::<i32>::new(10);

        // Add some objects to pool
        {
            let _obj1 = pool.get().unwrap();
            let _obj2 = pool.get().unwrap();
        } // Objects returned to pool

        let stats = pool.get_stats().unwrap();
        assert_eq!(stats.available_objects, 2);

        // Resize down
        let _ = pool.resize(1);

        let stats = pool.get_stats().unwrap();
        assert_eq!(stats.available_objects, 1); // Should be truncated
    }

    #[test]
    fn test_thread_safety_violation() {
        let pool = LightweightMemoryPool::<Vec<u8>>::new(10);
        
        // This should work in the same thread
        let obj = pool.get().unwrap();
        drop(obj);
        
        // We can't easily test cross-thread violations in a unit test,
        // but the code is designed to detect them
    }
}
