use std::sync::{Arc, RwLock, Mutex, atomic::{AtomicBool, AtomicU64, Ordering}};
use std::collections::HashMap;
use std::time::{SystemTime, Duration};
use std::thread;

/// Thread-safe synchronization primitive
#[derive(Debug)]
pub struct ThreadSafeSync {
    inner: Arc<ThreadSafeSyncInner>,
}

#[derive(Debug)]
struct ThreadSafeSyncInner {
    /// Global lock for critical sections
    global_lock: Mutex<()>,
    
    /// Read-write lock for shared data
    data_lock: RwLock<HashMap<String, SyncData>>,
    
    /// Atomic flag for shutdown
    shutdown_flag: AtomicBool,
    
    /// Atomic counter for operations
    operation_counter: AtomicU64,
    
    /// Thread-safe condition variable
    condition: std::sync::Condvar,
}

/// Synchronization data
#[derive(Debug, Clone)]
pub struct SyncData {
    pub value: String,
    pub timestamp: SystemTime,
    pub owner_thread: thread::ThreadId,
    pub access_count: u64,
}

impl ThreadSafeSync {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(ThreadSafeSyncInner {
                global_lock: Mutex::new(()),
                data_lock: RwLock::new(HashMap::new()),
                shutdown_flag: AtomicBool::new(false),
                operation_counter: AtomicU64::new(0),
                condition: std::sync::Condvar::new(),
            }),
        }
    }
    
    /// Create a new thread-safe sync instance
    pub fn new_shared() -> Arc<Self> {
        Arc::new(Self::new())
    }
    
    /// Acquire global lock
    pub fn acquire_global_lock(&self) -> ThreadSafeGlobalLock {
        ThreadSafeGlobalLock {
            guard: self.inner.global_lock.lock().unwrap(),
        }
    }
    
    /// Check if shutdown is requested
    pub fn is_shutdown_requested(&self) -> bool {
        self.inner.shutdown_flag.load(Ordering::Relaxed)
    }
    
    /// Request shutdown
    pub fn request_shutdown(&self) {
        self.inner.shutdown_flag.store(true, Ordering::Relaxed);
        self.inner.condition.notify_all();
    }
    
    /// Get operation count
    pub fn get_operation_count(&self) -> u64 {
        self.inner.operation_counter.load(Ordering::Relaxed)
    }
    
    /// Increment operation count
    pub fn increment_operation_count(&self) {
        self.inner.operation_counter.fetch_add(1, Ordering::Relaxed);
    }
    
    /// Store data thread-safely
    pub fn store_data(&self, key: String, value: String) -> Result<(), SyncError> {
        let mut data = self.inner.data_lock.write().unwrap();
        
        data.insert(key, SyncData {
            value,
            timestamp: SystemTime::now(),
            owner_thread: thread::current().id(),
            access_count: 0,
        });
        
        self.inner.operation_counter.fetch_add(1, Ordering::Relaxed);
        Ok(())
    }
    
    /// Retrieve data thread-safely
    pub fn retrieve_data(&self, key: &str) -> Result<Option<SyncData>, SyncError> {
        let mut data = self.inner.data_lock.write().unwrap();
        
        if let Some(sync_data) = data.get_mut(key) {
            sync_data.access_count += 1;
            sync_data.timestamp = SystemTime::now();
            Ok(Some(sync_data.clone()))
        } else {
            Ok(None)
        }
    }
    
    /// Remove data thread-safely
    pub fn remove_data(&self, key: &str) -> Result<bool, SyncError> {
        let mut data = self.inner.data_lock.write().unwrap();
        
        let existed = data.remove(key).is_some();
        if existed {
            self.inner.operation_counter.fetch_add(1, Ordering::Relaxed);
        }
        
        Ok(existed)
    }
    
    /// Clear all data
    pub fn clear_all_data(&self) -> Result<(), SyncError> {
        let mut data = self.inner.data_lock.write().unwrap();
        data.clear();
        self.inner.operation_counter.fetch_add(1, Ordering::Relaxed);
        Ok(())
    }
    
    /// Wait for condition with timeout
    pub fn wait_for_condition(&self, timeout: Duration) -> Result<bool, SyncError> {
        let lock = self.inner.global_lock.lock().unwrap();
        let result = self.inner.condition.wait_timeout(lock, timeout).unwrap();
        Ok(!result.timed_out())
    }
    
    /// Notify all waiting threads
    pub fn notify_all(&self) {
        self.inner.condition.notify_all();
    }
    
    /// Notify one waiting thread
    pub fn notify_one(&self) {
        self.inner.condition.notify_one();
    }
}

/// Global lock guard
pub struct ThreadSafeGlobalLock<'a> {
    guard: std::sync::MutexGuard<'a, ()>,
}

impl<'a> Drop for ThreadSafeGlobalLock<'a> {
    fn drop(&mut self) {
        // Lock is automatically released when guard is dropped
    }
}

/// Synchronization error
#[derive(Debug, Clone)]
pub enum SyncError {
    LockTimeout,
    DataCorrupted,
    ThreadError(String),
    ShutdownInProgress,
}

impl std::fmt::Display for SyncError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SyncError::LockTimeout => write!(f, "Lock timeout"),
            SyncError::DataCorrupted => write!(f, "Data corrupted"),
            SyncError::ThreadError(msg) => write!(f, "Thread error: {}", msg),
            SyncError::ShutdownInProgress => write!(f, "Shutdown in progress"),
        }
    }
}

impl std::error::Error for SyncError {}

/// Thread-safe counter
#[derive(Debug)]
pub struct ThreadSafeCounter {
    value: AtomicU64,
}

impl ThreadSafeCounter {
    pub fn new(initial: u64) -> Self {
        Self {
            value: AtomicU64::new(initial),
        }
    }
    
    pub fn increment(&self) -> u64 {
        self.value.fetch_add(1, Ordering::Relaxed)
    }
    
    pub fn decrement(&self) -> u64 {
        self.value.fetch_sub(1, Ordering::Relaxed)
    }
    
    pub fn get(&self) -> u64 {
        self.value.load(Ordering::Relaxed)
    }
    
    pub fn set(&self, value: u64) {
        self.value.store(value, Ordering::Relaxed);
    }
    
    pub fn compare_and_swap(&self, current: u64, new: u64) -> Result<u64, u64> {
        let result = self.value.compare_exchange(current, new, Ordering::Relaxed, Ordering::Relaxed);
        match result {
            Ok(old) => Ok(old),
            Err(old) => Err(old),
        }
    }
}

/// Thread-safe flag
#[derive(Debug)]
pub struct ThreadSafeFlag {
    flag: AtomicBool,
}

impl ThreadSafeFlag {
    pub fn new(initial: bool) -> Self {
        Self {
            flag: AtomicBool::new(initial),
        }
    }
    
    pub fn set(&self, value: bool) {
        self.flag.store(value, Ordering::Relaxed);
    }
    
    pub fn get(&self) -> bool {
        self.flag.load(Ordering::Relaxed)
    }
    
    pub fn set_true(&self) {
        self.flag.store(true, Ordering::Relaxed);
    }
    
    pub fn set_false(&self) {
        self.flag.store(false, Ordering::Relaxed);
    }
    
    pub fn toggle(&self) {
        self.flag.fetch_xor(true, Ordering::Relaxed);
    }
}

/// Global thread-safe sync instance
static GLOBAL_THREAD_SAFE_SYNC: std::sync::OnceLock<Arc<ThreadSafeSync>> = std::sync::OnceLock::new();

/// Get global thread-safe sync instance
pub fn get_global_thread_safe_sync() -> Arc<ThreadSafeSync> {
    GLOBAL_THREAD_SAFE_SYNC.get_or_init(|| ThreadSafeSync::new_shared()).clone()
}

/// Helper function to create thread-safe data
pub fn create_thread_safe_data(value: String) -> SyncData {
    SyncData {
        value,
        timestamp: SystemTime::now(),
        owner_thread: thread::current().id(),
        access_count: 0,
    }
}

/// Test helper for thread-safe operations
pub fn test_thread_safe_operation<F>(operation: F) -> Result<(), SyncError>
where
    F: FnOnce(&ThreadSafeSync) -> Result<(), SyncError>,
{
    let sync = ThreadSafeSync::new();
    operation(&sync)?;
    Ok(())
}