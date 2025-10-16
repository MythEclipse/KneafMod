use crate::errors::EnhancedError;
use crate::errors::ToEnhancedError;
use crate::logging::{generate_trace_id, LogSeverity};
use crossbeam_epoch::pin;
use crossbeam_epoch::Guard;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::{RwLock, RwLockReadGuard, RwLockWriteGuard};

/// Thread-safe data synchronizer with lock-free operations where possible
#[derive(Debug, Clone)]
pub struct ThreadSafeSynchronizer<T> {
    data: Arc<RwLock<T>>,
    last_modified: Arc<AtomicU64>,
    is_modifying: Arc<AtomicBool>,
    component_name: String,
}

impl<T: Clone + Send + Sync + 'static> ThreadSafeSynchronizer<T> {
    /// Create a new ThreadSafeSynchronizer
    pub fn new(initial_data: T, component_name: &str) -> Self {
        Self {
            data: Arc::new(RwLock::new(initial_data)),
            last_modified: Arc::new(AtomicU64::new(std::time::SystemTime::now()
                .duration_since(std::time::SystemTime::UNIX_EPOCH)
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0))),
            is_modifying: Arc::new(AtomicBool::new(false)),
            component_name: component_name.to_string(),
        }
    }

    /// Get read access to the data
    pub async fn read(&self) -> Result<RwLockReadGuard<'_, T>, EnhancedError> {
        let trace_id = generate_trace_id();
        
        // Log read operation
        slog!(
            &self.component_name,
            LogSeverity::Debug,
            "data_read_attempt",
            "Attempting to read data",
            "trace_id" => trace_id.clone()
        );

        let guard = self.data.read().await;
        
        // Update last accessed time
        let now = std::time::SystemTime::now()
            .duration_since(std::time::SystemTime::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);
        self.last_modified.store(now, Ordering::Relaxed);

        slog!(
            &self.component_name,
            LogSeverity::Info,
            "data_read_success",
            "Successfully read data",
            "trace_id" => trace_id,
            "last_modified" => now.to_string()
        );

        Ok(guard)
    }

    /// Get write access to the data
    pub async fn write(&self) -> Result<RwLockWriteGuard<'_, T>, EnhancedError> {
        let trace_id = generate_trace_id();
        
        // Use compare-and-swap to ensure only one writer at a time
        if !self.is_modifying.compare_and_swap(false, true, Ordering::Acquire) {
            let guard = self.data.write().await;
            
            // Update last modified time
            let now = std::time::SystemTime::now()
                .duration_since(std::time::SystemTime::UNIX_EPOCH)
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0);
            self.last_modified.store(now, Ordering::Release);

            slog!(
                &self.component_name,
                LogSeverity::Info,
                "data_write_success",
                "Successfully acquired write lock",
                "trace_id" => trace_id.clone(),
                "last_modified" => now.to_string()
            );

            Ok(guard)
        } else {
            let error = crate::errors::RustError::new_thread_safe("Data is currently being modified by another thread")
                .to_enhanced()
                .with_trace_id(trace_id);
            
            slog!(
                &self.component_name,
                LogSeverity::Warn,
                "data_write_failed",
                "Write attempt failed - data is being modified",
                "trace_id" => trace_id
            );

            Err(error)
        }
    }

    /// Try to get write access with timeout
    pub async fn try_write_with_timeout(&self, timeout_ms: u64) -> Result<RwLockWriteGuard<'_, T>, EnhancedError> {
        let trace_id = generate_trace_id();
        let start_time = std::time::Instant::now();
        let timeout_duration = Duration::from_millis(timeout_ms);

        loop {
            // Check if we've exceeded timeout
            if start_time.elapsed() > timeout_duration {
                let error = crate::errors::RustError::new_thread_safe(&format!(
                    "Write timeout after {}ms - data is being modified",
                    timeout_ms
                ))
                .to_enhanced()
                .with_trace_id(trace_id.clone());

                slog!(
                    &self.component_name,
                    LogSeverity::Error,
                    "data_write_timeout",
                    &format!("Write timeout after {}ms", timeout_ms),
                    "trace_id" => trace_id
                );

                return Err(error);
            }

            // Try to acquire write lock
            if let Ok(guard) = self.try_write() {
                return Ok(guard);
            }

            // Wait a bit before retrying
            tokio::time::sleep(Duration::from_millis(1)).await;
        }
    }

    /// Try to get write access without blocking
    pub async fn try_write(&self) -> Result<RwLockWriteGuard<'_, T>, EnhancedError> {
        let trace_id = generate_trace_id();

        // Quick check if data is being modified
        if self.is_modifying.load(Ordering::Acquire) {
            let error = crate::errors::RustError::new_thread_safe("Data is currently being modified by another thread")
                .to_enhanced()
                .with_trace_id(trace_id);
            
            return Err(error);
        }

        // Try to acquire write lock without blocking
        match self.data.try_write() {
            Some(guard) => {
                // Update last modified time
                let now = std::time::SystemTime::now()
                    .duration_since(std::time::SystemTime::UNIX_EPOCH)
                    .map(|d| d.as_millis() as u64)
                    .unwrap_or(0);
                self.last_modified.store(now, Ordering::Release);

                // Mark as modifying
                self.is_modifying.store(true, Ordering::Release);

                slog!(
                    &self.component_name,
                    LogSeverity::Info,
                    "data_write_success",
                    "Successfully acquired write lock without blocking",
                    "trace_id" => trace_id,
                    "last_modified" => now.to_string()
                );

                Ok(guard)
            }
            None => {
                let error = crate::errors::RustError::new_thread_safe("Failed to acquire write lock - data is locked")
                    .to_enhanced()
                    .with_trace_id(trace_id);
                
                slog!(
                    &self.component_name,
                    LogSeverity::Warn,
                    "data_write_failed",
                    "Failed to acquire write lock",
                    "trace_id" => trace_id
                );

                Err(error)
            }
        }
    }

    /// Update the data with synchronization
    pub async fn update<F>(&self, update_fn: F) -> Result<(), EnhancedError>
    where
        F: FnOnce(&mut T) -> Result<(), EnhancedError>,
    {
        let trace_id = generate_trace_id();
        
        slog!(
            &self.component_name,
            LogSeverity::Debug,
            "data_update_attempt",
            "Attempting to update data",
            "trace_id" => trace_id.clone()
        );

        let mut guard = self.write().await?;
        
        match update_fn(&mut *guard) {
            Ok(_) => {
                // Update last modified time
                let now = std::time::SystemTime::now()
                    .duration_since(std::time::SystemTime::UNIX_EPOCH)
                    .map(|d| d.as_millis() as u64)
                    .unwrap_or(0);
                self.last_modified.store(now, Ordering::Release);

                // Clear modifying flag
                self.is_modifying.store(false, Ordering::Release);

                slog!(
                    &self.component_name,
                    LogSeverity::Info,
                    "data_update_success",
                    "Successfully updated data",
                    "trace_id" => trace_id,
                    "last_modified" => now.to_string()
                );

                Ok(())
            }
            Err(e) => {
                // Clear modifying flag even if update failed
                self.is_modifying.store(false, Ordering::Release);

                slog!(
                    &self.component_name,
                    LogSeverity::Error,
                    "data_update_failed",
                    &format!("Data update failed: {}", e),
                    "trace_id" => trace_id
                );

                Err(e)
            }
        }
    }

    /// Get the last modified timestamp
    pub fn last_modified(&self) -> u64 {
        self.last_modified.load(Ordering::Relaxed)
    }

    /// Check if data is currently being modified
    pub fn is_modifying(&self) -> bool {
        self.is_modifying.load(Ordering::Relaxed)
    }

    /// Get a clone of the current data (for non-critical operations)
    pub async fn get_snapshot(&self) -> Result<T, EnhancedError> {
        let trace_id = generate_trace_id();
        
        let guard = self.read().await;
        let snapshot = guard.clone();

        slog!(
            &self.component_name,
            LogSeverity::Debug,
            "data_snapshot",
            "Created data snapshot",
            "trace_id" => trace_id,
            "last_modified" => self.last_modified().to_string()
        );

        Ok(snapshot)
    }
}

/// Lock-free data synchronizer for high-performance scenarios
#[derive(Debug, Clone)]
pub struct LockFreeSynchronizer<T> {
    data: Arc<std::sync::atomic::AtomicPtr<T>>,
    component_name: String,
}

impl<T: Clone + Send + Sync + 'static> LockFreeSynchronizer<T> {
    /// Create a new LockFreeSynchronizer
    pub fn new(initial_data: T, component_name: &str) -> Self {
        let boxed_data = Box::new(initial_data);
        Self {
            data: Arc::new(std::sync::atomic::AtomicPtr::new(Box::into_raw(boxed_data))),
            component_name: component_name.to_string(),
        }
    }

    /// Get a snapshot of the data using lock-free operations
    pub fn get_snapshot(&self) -> Result<T, EnhancedError> {
        let trace_id = generate_trace_id();
        
        // Use load with acquire ordering to ensure we see the most recent write
        let ptr = self.data.load(Ordering::Acquire);
        
        // Safety: We know the pointer is valid because we only replace it with Box::into_raw
        // and never delete it without replacing it first
        let data = unsafe { &*ptr };
        let snapshot = data.clone();

        slog!(
            &self.component_name,
            LogSeverity::Debug,
            "lock_free_snapshot",
            "Created lock-free data snapshot",
            "trace_id" => trace_id
        );

        Ok(snapshot)
    }

    /// Update the data using lock-free operations
    pub fn update<F>(&self, update_fn: F) -> Result<(), EnhancedError>
    where
        F: FnOnce(&mut T) -> Result<(), EnhancedError>,
    {
        let trace_id = generate_trace_id();
        
        slog!(
            &self.component_name,
            LogSeverity::Debug,
            "lock_free_update_attempt",
            "Attempting lock-free data update",
            "trace_id" => trace_id.clone()
        );

        loop {
            // Load current pointer
            let old_ptr = self.data.load(Ordering::Acquire);
            
            // Safety: We know the pointer is valid
            let old_data = unsafe { &*old_ptr };
            let mut new_data = old_data.clone();

            match update_fn(&mut new_data) {
                Ok(_) => {
                    // Create new boxed data
                    let new_ptr = Box::into_raw(Box::new(new_data));
                    
                    // Compare and swap - if the pointer is still the same, we succeeded
                    if self.data.compare_exchange_weak(
                        old_ptr,
                        new_ptr,
                        Ordering::Release,
                        Ordering::Acquire
                    ).is_ok() {
                        // Safety: We can drop the old pointer because we successfully swapped
                        unsafe { Box::from_raw(old_ptr); }

                        slog!(
                            &self.component_name,
                            LogSeverity::Info,
                            "lock_free_update_success",
                            "Successfully updated data lock-free",
                            "trace_id" => trace_id
                        );

                        return Ok(());
                    } else {
                        // Another thread updated the data, try again
                        continue;
                    }
                }
                Err(e) => {
                    slog!(
                        &self.component_name,
                        LogSeverity::Error,
                        "lock_free_update_failed",
                        &format!("Lock-free data update failed: {}", e),
                        "trace_id" => trace_id
                    );

                    return Err(e);
                }
            }
        }
    }
}

/// Synchronize data between multiple threads safely
pub async fn synchronize_data<T, F>(
    data: &ThreadSafeSynchronizer<T>,
    sync_fn: F
) -> Result<(), EnhancedError>
where
    T: Clone + Send + Sync + 'static,
    F: FnOnce(&mut T) -> Result<(), EnhancedError>,
{
    let trace_id = generate_trace_id();
    
    slog!(
        &data.component_name,
        LogSeverity::Info,
        "data_synchronization",
        "Starting data synchronization",
        "trace_id" => trace_id.clone()
    );

    let result = data.update(sync_fn).await;

    match &result {
        Ok(_) => {
            slog!(
                &data.component_name,
                LogSeverity::Info,
                "data_synchronization_complete",
                "Data synchronization completed successfully",
                "trace_id" => trace_id
            );
        }
        Err(e) => {
            slog!(
                &data.component_name,
                LogSeverity::Error,
                "data_synchronization_failed",
                &format!("Data synchronization failed: {}", e),
                "trace_id" => trace_id
            );
        }
    }

    result
}