use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::fmt::Debug;
use crate::logging::{PerformanceLogger, generate_trace_id, ProcessingError};
use lazy_static::lazy_static;

/// Generic object pool for memory reuse
pub struct ObjectPool<T> {
    pool: Arc<Mutex<VecDeque<T>>>,
    max_size: usize,
    logger: PerformanceLogger,
}

impl<T> ObjectPool<T>
where
    T: Default + Debug,
{
    pub fn new(max_size: usize) -> Self {
        Self {
            pool: Arc::new(Mutex::new(VecDeque::with_capacity(max_size))),
            max_size,
            logger: PerformanceLogger::new("memory_pool"),
        }
    }

    /// Get an object from the pool, creating a new one if none available
    pub fn get(&self) -> PooledObject<T> {
        let trace_id = generate_trace_id();
        let mut guard = self.pool.lock().unwrap();
        let obj = guard.pop_front().unwrap_or_else(|| {
            self.logger.log_operation("pool_miss", &trace_id, || {
                log::debug!("Pool miss for type {}, creating new object", std::any::type_name::<T>());
                T::default()
            })
        });

        // Clone the Arc to the pool so the PooledObject can return the object on drop
        let pool_arc = Arc::clone(&self.pool);

        PooledObject {
            object: Some(obj),
            pool: pool_arc,
            max_size: self.max_size,
        }
    }

    /// Return an object to the pool
    fn return_object(&self, obj: T) {
        let trace_id = generate_trace_id();
        let mut pool = self.pool.lock().unwrap();

        if pool.len() < self.max_size {
            pool.push_front(obj);
            self.logger.log_operation("pool_return", &trace_id, || {
                log::debug!("Returned object to pool, current size: {}", pool.len());
            });
        } else {
            self.logger.log_operation("pool_discard", &trace_id, || {
                log::debug!("Pool full, discarding object");
            });
        }
    }
}

/// Wrapper for pooled objects that automatically returns to pool when dropped
pub struct PooledObject<T> {
    object: Option<T>,
    pool: Arc<Mutex<VecDeque<T>>>,
    max_size: usize,
}

impl<T> PooledObject<T> {
    /// Get mutable reference to the pooled object
    pub fn as_mut(&mut self) -> &mut T {
        self.object.as_mut().unwrap()
    }

    /// Get reference to the pooled object
    pub fn as_ref(&self) -> &T {
        self.object.as_ref().unwrap()
    }

    /// Take ownership of the object, preventing return to pool
    pub fn take(mut self) -> T {
        self.object.take().unwrap()
    }
}

impl<T> Drop for PooledObject<T> {
    fn drop(&mut self) {
        if let Some(obj) = self.object.take() {
            let mut pool = self.pool.lock().unwrap();
            if pool.len() < self.max_size {
                pool.push_front(obj);
            }
        }
    }
}

impl<T> std::ops::Deref for PooledObject<T> {
    type Target = T;

    fn deref(&self) -> &Self::Target {
        self.as_ref()
    }
}

impl<T> std::ops::DerefMut for PooledObject<T> {
    fn deref_mut(&mut self) -> &mut Self::Target {
        self.as_mut()
    }
}

/// Specialized pool for vectors
pub struct VecPool<T> {
    pool: ObjectPool<Vec<T>>,
}

impl<T: Debug> VecPool<T> {
    pub fn new(max_size: usize) -> Self {
        Self {
            pool: ObjectPool::new(max_size),
        }
    }

    pub fn get_vec(&self, capacity: usize) -> PooledVec<T> {
        let mut pooled = self.pool.get();
        pooled.clear();
        pooled.reserve(capacity);
        pooled
    }
}

pub type PooledVec<T> = PooledObject<Vec<T>>;

/// Specialized pool for strings
pub struct StringPool {
    pool: ObjectPool<String>,
}

impl StringPool {
    pub fn new(max_size: usize) -> Self {
        Self {
            pool: ObjectPool::new(max_size),
        }
    }

    pub fn get_string(&self, capacity: usize) -> PooledString {
        let mut pooled = self.pool.get();
        pooled.clear();
        pooled.reserve(capacity);
        pooled
    }
}

pub type PooledString = PooledObject<String>;

/// Global memory pool manager
pub struct MemoryPoolManager {
    vec_u64_pool: VecPool<u64>,
    vec_u32_pool: VecPool<u32>,
    vec_f32_pool: VecPool<f32>,
    string_pool: StringPool,
    logger: PerformanceLogger,
}

impl MemoryPoolManager {
    pub fn new() -> Self {
        Self {
            vec_u64_pool: VecPool::new(1000),
            vec_u32_pool: VecPool::new(1000),
            vec_f32_pool: VecPool::new(1000),
            string_pool: StringPool::new(500),
            logger: PerformanceLogger::new("memory_pool_manager"),
        }
    }

    pub fn get_vec_u64(&self, capacity: usize) -> PooledVec<u64> {
        self.vec_u64_pool.get_vec(capacity)
    }

    pub fn get_vec_u32(&self, capacity: usize) -> PooledVec<u32> {
        self.vec_u32_pool.get_vec(capacity)
    }

    pub fn get_vec_f32(&self, capacity: usize) -> PooledVec<f32> {
        self.vec_f32_pool.get_vec(capacity)
    }

    pub fn get_string(&self, capacity: usize) -> PooledString {
        self.string_pool.get_string(capacity)
    }

    /// Get pool statistics
    pub fn get_stats(&self) -> MemoryPoolStats {
        let trace_id = generate_trace_id();
        self.logger.log_operation("get_stats", &trace_id, || {
            MemoryPoolStats {
                vec_u64_available: self.vec_u64_pool.pool.pool.lock().unwrap().len(),
                vec_u32_available: self.vec_u32_pool.pool.pool.lock().unwrap().len(),
                vec_f32_available: self.vec_f32_pool.pool.pool.lock().unwrap().len(),
                strings_available: self.string_pool.pool.pool.lock().unwrap().len(),
            }
        })
    }
}

#[derive(Debug, Clone)]
pub struct MemoryPoolStats {
    pub vec_u64_available: usize,
    pub vec_u32_available: usize,
    pub vec_f32_available: usize,
    pub strings_available: usize,
}

/// Thread-local memory pool for better performance
pub struct ThreadLocalMemoryPool {
    manager: Arc<MemoryPoolManager>,
}

impl ThreadLocalMemoryPool {
    pub fn new(manager: Arc<MemoryPoolManager>) -> Self {
        Self { manager }
    }

    pub fn get_vec_u64(&self, capacity: usize) -> PooledVec<u64> {
        self.manager.get_vec_u64(capacity)
    }

    pub fn get_vec_u32(&self, capacity: usize) -> PooledVec<u32> {
        self.manager.get_vec_u32(capacity)
    }

    pub fn get_vec_f32(&self, capacity: usize) -> PooledVec<f32> {
        self.manager.get_vec_f32(capacity)
    }

    pub fn get_string(&self, capacity: usize) -> PooledString {
        self.manager.get_string(capacity)
    }
}

thread_local! {
    static THREAD_LOCAL_POOL: std::cell::RefCell<Option<Arc<MemoryPoolManager>>> = std::cell::RefCell::new(None);
}

/// Initialize thread-local memory pool
pub fn init_thread_local_pool(manager: Arc<MemoryPoolManager>) {
    THREAD_LOCAL_POOL.with(|pool| {
        *pool.borrow_mut() = Some(manager);
    });
}

/// Get thread-local memory pool instance
pub fn get_thread_local_pool() -> Option<ThreadLocalMemoryPool> {
    THREAD_LOCAL_POOL.with(|pool| {
        pool.borrow().as_ref().map(|manager| ThreadLocalMemoryPool::new(Arc::clone(manager)))
    })
}

/// Global memory pool instance
lazy_static::lazy_static! {
    pub static ref GLOBAL_MEMORY_POOL: Arc<MemoryPoolManager> = Arc::new(MemoryPoolManager::new());
}

/// Initialize global memory pools
pub fn init_memory_pools() {
    init_thread_local_pool(Arc::clone(&GLOBAL_MEMORY_POOL));
    log::info!("Memory pools initialized");
}

/// Get global memory pool instance
pub fn get_global_pool() -> &'static MemoryPoolManager {
    &GLOBAL_MEMORY_POOL
}