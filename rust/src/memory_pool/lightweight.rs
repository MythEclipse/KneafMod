use std::cell::RefCell;
use std::collections::VecDeque;
use std::rc::Rc;
use std::sync::atomic::{AtomicUsize, Ordering};

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

/// Lightweight memory pool for single-threaded scenarios with minimal overhead
#[derive(Debug)]
pub struct LightweightMemoryPool<T: Default> {
    pool: RefCell<VecDeque<T>>,
    allocated_count: AtomicUsize,
    max_size: AtomicUsize,
    logger: PerformanceLogger,
}

impl<T: Default> LightweightMemoryPool<T> {
    /// Create new lightweight pool with specified maximum size
    pub fn new(max_size: usize) -> Self {
        let logger = PerformanceLogger::new("lightweight_memory_pool");

        Self {
            pool: RefCell::new(VecDeque::with_capacity(max_size)),
            allocated_count: AtomicUsize::new(0),
            max_size: AtomicUsize::new(max_size),
            logger,
        }
    }

    /// Get an object from the pool (single-threaded)
    pub fn get(&self) -> LightweightPooledObject<'_, T> {
        let trace_id = generate_trace_id();

        let obj = if let Some(obj) = self.pool.borrow_mut().pop_front() {
            self.logger
                .log_info("get", &trace_id, "Reused object from pool");
            obj
        } else {
            self.logger.log_info("get", &trace_id, "Created new object");
            T::default()
        };

        self.allocated_count.fetch_add(1, Ordering::Relaxed);

        LightweightPooledObject {
            data: Some(obj),
            pool: self,
        }
    }

    /// Return object to pool (called by PooledObject drop)
    fn return_object(&self, obj: T) {
        let trace_id = generate_trace_id();

        let mut pool = self.pool.borrow_mut();
        let max_size = self.max_size.load(Ordering::Relaxed);
        if pool.len() < max_size {
            pool.push_back(obj);
            self.logger
                .log_info("return_object", &trace_id, "Object returned to pool");
        } else {
            self.logger
                .log_info("return_object", &trace_id, "Pool full, object discarded");
        }

        self.allocated_count.fetch_sub(1, Ordering::Relaxed);
    }

    /// Get current pool statistics
    pub fn get_stats(&self) -> LightweightPoolStats {
        let pool_len = self.pool.borrow().len();
        let allocated = self.allocated_count.load(Ordering::Relaxed);
        let max_size = self.max_size.load(Ordering::Relaxed);

        LightweightPoolStats {
            available_objects: pool_len,
            allocated_objects: allocated,
            max_size,
            utilization_ratio: if max_size > 0 {
                allocated as f64 / max_size as f64
            } else {
                0.0
            },
        }
    }

    /// Clear all objects from the pool
    pub fn clear(&self) {
        let trace_id = generate_trace_id();
        
        // Get current size for logging (separate borrow scope)
        let cleared_count = {
            let pool = self.pool.borrow();
            pool.len()
        }; // pool borrow ends here

        // Clear the pool (separate borrow scope)
        {
            let mut pool = self.pool.borrow_mut();
            pool.clear();
        } // pool borrow_mut ends here
        
        // Reset allocated count (atomic operation, no borrow needed)
        self.allocated_count.store(0, Ordering::Relaxed);

        self.logger.log_info(
            "clear",
            &trace_id,
            &format!("Cleared {} objects from pool", cleared_count),
        );
    }

    /// Resize the pool capacity with proper memory management
    pub fn resize(&mut self, new_max_size: usize) -> Result<(), PoolError> {
        let trace_id = generate_trace_id();

        // Get current pool state (separate borrow scope)
        let (current_len, should_shrink) = {
            let pool = self.pool.borrow();
            (pool.len(), new_max_size < pool.len())
        }; // pool borrow ends here

        if should_shrink {
            // Shrink pool by removing excess objects (separate borrow scope)
            {
                let mut pool = self.pool.borrow_mut();
                pool.truncate(new_max_size);
            } // pool borrow_mut ends here
            
            self.logger.log_info(
                "resize",
                &trace_id,
                &format!(
                    "Shrunk pool from {} to {} objects",
                    current_len, new_max_size
                ),
            );
        } else {
            // Grow pool capacity (separate borrow scope)
            {
                let mut pool = self.pool.borrow_mut();
                pool.reserve(new_max_size - current_len);
            } // pool borrow_mut ends here
            
            self.logger.log_info(
                "resize",
                &trace_id,
                &format!("Grew pool capacity to {} objects", new_max_size),
            );
        }

        // Update max_size with atomic operation for thread safety
        self.max_size.store(new_max_size, Ordering::Relaxed);
        self.logger.log_info(
            "resize",
            &trace_id,
            &format!("Updated pool max size to {}", new_max_size),
        );

        Ok(())
    }

    /// Recreate the pool with new capacity while preserving allocated objects
    pub fn recreate(&self, new_max_size: usize) -> Result<(), PoolError> {
        let trace_id = generate_trace_id();

        // Get current allocated objects count first (atomic operation, no borrow needed)
        let allocated_objects = self.allocated_count.load(Ordering::Relaxed);
        
        // Get current pool size for logging (separate borrow scope)
        let current_pool_size = {
            let pool = self.pool.borrow();
            pool.len()
        }; // pool borrow ends here

        // Clear the existing pool (separate borrow scope)
        {
            let mut pool = self.pool.borrow_mut();
            pool.clear();
        } // pool borrow_mut ends here

        // Update max_size with atomic operation to avoid borrow conflicts
        self.max_size.store(new_max_size, Ordering::Relaxed);

        self.logger.log_info(
            "recreate",
            &trace_id,
            &format!("Recreated pool from {} to {} objects, preserving {} allocated objects",
                     current_pool_size, new_max_size, allocated_objects),
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

/// Pooled object wrapper for lightweight pool
#[derive(Debug)]
pub struct LightweightPooledObject<'a, T: Default> {
    data: Option<T>,
    pool: &'a LightweightMemoryPool<T>,
}

impl<'a, T: Default> LightweightPooledObject<'a, T> {
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

impl<'a, T: Default> Drop for LightweightPooledObject<'a, T> {
    fn drop(&mut self) {
        if let Some(data) = self.data.take() {
            self.pool.return_object(data);
        }
    }
}

impl<'a, T: Default> std::ops::Deref for LightweightPooledObject<'a, T> {
    type Target = T;

    fn deref(&self) -> &Self::Target {
        self.as_ref()
    }
}

impl<'a, T: Default> std::ops::DerefMut for LightweightPooledObject<'a, T> {
    fn deref_mut(&mut self) -> &mut Self::Target {
        self.as_mut()
    }
}

/// Thread-local lightweight pool for better performance in single-threaded contexts
pub struct ThreadLocalLightweightPool<T: Default> {
    pool: Rc<LightweightMemoryPool<T>>,
}

impl<T: Default> ThreadLocalLightweightPool<T> {
    /// Create new thread-local pool
    pub fn new(max_size: usize) -> Self {
        Self {
            pool: Rc::new(LightweightMemoryPool::new(max_size)),
        }
    }

    /// Get an object from the thread-local pool
    pub fn get(&self) -> LightweightPooledObject<'_, T> {
        self.pool.get()
    }

    /// Get pool statistics
    pub fn get_stats(&self) -> LightweightPoolStats {
        self.pool.get_stats()
    }
}

impl<T: Default> Clone for ThreadLocalLightweightPool<T> {
    fn clone(&self) -> Self {
        Self {
            pool: Rc::clone(&self.pool),
        }
    }
}

/// Fast arena-style allocator for temporary objects
#[derive(Debug)]
pub struct FastArena<T: Default> {
    objects: RefCell<Vec<T>>,
    free_indices: RefCell<Vec<usize>>,
    logger: PerformanceLogger,
}

impl<T: Default> FastArena<T> {
    /// Create new fast arena
    pub fn new() -> Self {
        Self {
            objects: RefCell::new(Vec::new()),
            free_indices: RefCell::new(Vec::new()),
            logger: PerformanceLogger::new("fast_arena"),
        }
    }

    /// Allocate object in arena
    pub fn alloc(&self) -> ArenaHandle<'_, T> {
        let trace_id = generate_trace_id();

        let index = if let Some(index) = self.free_indices.borrow_mut().pop() {
            self.logger
                .log_info("alloc", &trace_id, &format!("Reused slot {}", index));
            index
        } else {
            let index = self.objects.borrow().len();
            self.objects.borrow_mut().push(T::default());
            self.logger
                .log_info("alloc", &trace_id, &format!("Allocated new slot {}", index));
            index
        };

        ArenaHandle { index, arena: self }
    }

    /// Get reference to object at index
    fn get(&self, index: usize) -> std::cell::Ref<'_, T> {
        std::cell::Ref::map(self.objects.borrow(), |objs| &objs[index])
    }

    /// Get mutable reference to object at index
    fn get_mut(&self, index: usize) -> std::cell::RefMut<'_, T> {
        std::cell::RefMut::map(self.objects.borrow_mut(), |objs| &mut objs[index])
    }

    /// Deallocate object (return to free list)
    fn dealloc(&self, index: usize) {
        let trace_id = generate_trace_id();

        // Reset object to default state
        *self.get_mut(index) = T::default();

        self.free_indices.borrow_mut().push(index);
        self.logger
            .log_info("dealloc", &trace_id, &format!("Deallocated slot {}", index));
    }

    /// Clear all objects and reset arena
    pub fn clear(&self) {
        let trace_id = generate_trace_id();

        let object_count = self.objects.borrow().len();
        let free_count = self.free_indices.borrow().len();

        self.objects.borrow_mut().clear();
        self.free_indices.borrow_mut().clear();

        self.logger.log_info(
            "clear",
            &trace_id,
            &format!(
                "Cleared arena: {} objects, {} free slots",
                object_count, free_count
            ),
        );
    }

    /// Get arena statistics
    pub fn get_stats(&self) -> ArenaStats {
        let total_objects = self.objects.borrow().len();
        let free_objects = self.free_indices.borrow().len();
        let used_objects = total_objects - free_objects;

        ArenaStats {
            total_objects,
            used_objects,
            free_objects,
            utilization_ratio: if total_objects > 0 {
                used_objects as f64 / total_objects as f64
            } else {
                0.0
            },
        }
    }
}

#[derive(Debug, Clone)]
pub struct ArenaStats {
    pub total_objects: usize,
    pub used_objects: usize,
    pub free_objects: usize,
    pub utilization_ratio: f64,
}

/// Handle to arena-allocated object
#[derive(Debug)]
pub struct ArenaHandle<'a, T: Default> {
    index: usize,
    arena: &'a FastArena<T>,
}

impl<'a, T: Default> ArenaHandle<'a, T> {
    /// Get immutable reference to the data
    pub fn as_ref(&self) -> std::cell::Ref<'_, T> {
        self.arena.get(self.index)
    }

    /// Get mutable reference to the data
    pub fn as_mut(&mut self) -> std::cell::RefMut<'_, T> {
        self.arena.get_mut(self.index)
    }
}

impl<'a, T: Default> Drop for ArenaHandle<'a, T> {
    fn drop(&mut self) {
        self.arena.dealloc(self.index);
    }
}

impl<'a, T: Default> std::ops::Deref for ArenaHandle<'a, T> {
    type Target = std::cell::Ref<'a, T>;

    fn deref(&self) -> &Self::Target {
        // This is not ideal but necessary due to lifetime constraints
        // In practice, users should use as_ref() method directly
        panic!("Use as_ref() method instead of deref for ArenaHandle")
    }
}

impl<'a, T: Default> std::ops::DerefMut for ArenaHandle<'a, T> {
    fn deref_mut(&mut self) -> &mut Self::Target {
        // This is not ideal but necessary due to lifetime constraints
        // In practice, users should use as_mut() method directly
        panic!("Use as_mut() method instead of deref for ArenaHandle")
    }
}

/// Scoped arena that automatically clears when dropped
#[derive(Debug)]
pub struct ScopedArena<T: Default> {
    arena: FastArena<T>,
}

impl<T: Default> ScopedArena<T> {
    /// Create new scoped arena
    pub fn new() -> Self {
        Self {
            arena: FastArena::new(),
        }
    }

    /// Allocate object in scoped arena
    pub fn alloc(&self) -> ArenaHandle<'_, T> {
        self.arena.alloc()
    }

    /// Get arena statistics
    pub fn get_stats(&self) -> ArenaStats {
        self.arena.get_stats()
    }
}

impl<T: Default> Drop for ScopedArena<T> {
    fn drop(&mut self) {
        self.arena.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_lightweight_pool_basic() {
        let pool = LightweightMemoryPool::<Vec<u8>>::new(10);

        // Get object
        let mut obj = pool.get();
        obj.push(42);
        assert_eq!(obj[0], 42);

        // Object should be returned to pool on drop
        drop(obj);

        // Get another object (should reuse)
        let obj2 = pool.get();
        assert_eq!(obj2.len(), 0); // Should be reset to default
    }

    #[test]
    fn test_lightweight_pool_stats() {
        let pool = LightweightMemoryPool::<String>::new(5);

        let stats = pool.get_stats();
        assert_eq!(stats.available_objects, 0);
        assert_eq!(stats.allocated_objects, 0);
        assert_eq!(stats.max_size, 5);

        let _obj = pool.get();
        let stats = pool.get_stats();
        assert_eq!(stats.allocated_objects, 1);
    }

    #[test]
    fn test_thread_local_pool() {
        let pool = ThreadLocalLightweightPool::<Vec<i32>>::new(10);

        let mut obj = pool.get();
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
        let mut handle1 = arena.alloc();
        *handle1.as_mut() = "Hello".to_string();

        let mut handle2 = arena.alloc();
        *handle2.as_mut() = "World".to_string();

        assert_eq!(&**handle1.as_ref(), "Hello");
        assert_eq!(&**handle2.as_ref(), "World");

        // Objects should be deallocated on drop
        drop(handle1);
        drop(handle2);

        // Check stats
        let stats = arena.get_stats();
        assert_eq!(stats.total_objects, 2);
        assert_eq!(stats.free_objects, 2);
        assert_eq!(stats.used_objects, 0);
    }

    #[test]
    fn test_scoped_arena() {
        {
            let arena = ScopedArena::<Vec<u8>>::new();

            let mut handle = arena.alloc();
            handle.as_mut().push(1);
            handle.as_mut().push(2);

            assert_eq!(handle.as_ref().len(), 2);

            // Arena will be cleared when it goes out of scope
        }

        // Arena should be cleared automatically
    }

    #[test]
    fn test_pool_resize() {
        let mut pool = LightweightMemoryPool::<i32>::new(10);

        // Add some objects to pool
        {
            let _obj1 = pool.get();
            let _obj2 = pool.get();
        } // Objects returned to pool

        let stats = pool.get_stats();
        assert_eq!(stats.available_objects, 2);

        // Resize down
        pool.resize(1);

        let stats = pool.get_stats();
        assert_eq!(stats.available_objects, 1); // Should be truncated
    }
}
