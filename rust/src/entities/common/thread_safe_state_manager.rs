use super::state_manager::{EntityState, EntityOperationResult, StateManager, EntityStateManager};
use crate::errors::Result;
use crate::types::{EntityConfigTrait as EntityConfig, EntityDataTrait as EntityData, EntityTypeTrait as EntityType};
use std::sync::{Arc, RwLock, Mutex, OnceLock};
use std::collections::HashMap;
use std::time::SystemTime;

/// Thread-safe entity operation result
#[derive(Debug, Clone)]
pub struct ThreadSafeEntityOperationResult {
    pub success: bool,
    pub message: String,
    pub state: EntityState,
    pub data: Option<EntityData>,
    pub timestamp: SystemTime,
    pub thread_id: std::thread::ThreadId,
}

/// Thread-safe entity state
#[derive(Debug, Clone)]
pub struct ThreadSafeEntityState {
    pub state: EntityState,
    pub owner_thread: std::thread::ThreadId,
    pub last_accessed: SystemTime,
    pub access_count: u64,
}

/// Thread-safe state manager trait
pub trait ThreadSafeEntityStateManager: Send + Sync {
    /// Get current state with thread safety
    fn get_thread_safe_state(&self, entity_id: &str) -> Result<ThreadSafeEntityState>;
    
    /// Set state with thread safety
    fn set_thread_safe_state(&self, entity_id: &str, state: ThreadSafeEntityState) -> Result<()>;
    
    /// Acquire exclusive lock on entity
    fn acquire_entity_lock(&self, entity_id: &str, timeout_ms: u64) -> Result<ThreadSafeEntityLock>;
    
    /// Release entity lock
    fn release_entity_lock(&self, entity_id: &str) -> Result<()>;
    
    /// Check if entity is locked
    fn is_entity_locked(&self, entity_id: &str) -> bool;
    
    /// Get lock owner thread
    fn get_lock_owner(&self, entity_id: &str) -> Option<std::thread::ThreadId>;
}

/// Thread-safe entity lock
pub struct ThreadSafeEntityLock {
    entity_id: String,
    owner_thread: std::thread::ThreadId,
    acquired_at: SystemTime,
}

impl ThreadSafeEntityLock {
    pub fn new(entity_id: String) -> Self {
        Self {
            entity_id,
            owner_thread: std::thread::current().id(),
            acquired_at: SystemTime::now(),
        }
    }
    
    pub fn get_entity_id(&self) -> &str {
        &self.entity_id
    }
    
    pub fn get_owner_thread(&self) -> std::thread::ThreadId {
        self.owner_thread
    }
    
    pub fn get_acquired_at(&self) -> SystemTime {
        self.acquired_at
    }
}

/// Thread-safe entity processor
pub trait ThreadSafeEntityProcessor: Send + Sync {
    /// Process entity with thread safety
    fn process_thread_safe(
        &self,
        entity_id: &str,
        entity_data: &EntityData,
        config: &ThreadSafeProcessingConfig,
    ) -> Result<ThreadSafeEntityOperationResult>;
    
    /// Process multiple entities concurrently
    fn process_entities_concurrent(
        &self,
        entities: Vec<(String, EntityData)>,
        config: &ThreadSafeProcessingConfig,
    ) -> Result<Vec<ThreadSafeEntityOperationResult>>;
}

/// Thread-safe processing configuration
#[derive(Debug, Clone)]
pub struct ThreadSafeProcessingConfig {
    pub max_concurrent_threads: usize,
    pub lock_timeout_ms: u64,
    pub retry_attempts: u32,
    pub enable_deadlock_detection: bool,
    pub max_lock_hold_time_ms: u64,
}

impl Default for ThreadSafeProcessingConfig {
    fn default() -> Self {
        Self {
            max_concurrent_threads: 4,
            lock_timeout_ms: 5000,
            retry_attempts: 3,
            enable_deadlock_detection: true,
            max_lock_hold_time_ms: 30000,
        }
    }
}

/// Thread-safe state manager implementation
pub struct ThreadSafeEntityStateManagerImpl {
    inner: Arc<EntityStateManager>,
    entity_locks: Arc<RwLock<HashMap<String, ThreadSafeEntityLock>>>,
    lock_timeouts: Arc<RwLock<HashMap<String, SystemTime>>>,
}

impl ThreadSafeEntityStateManagerImpl {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(EntityStateManager::new()),
            entity_locks: Arc::new(RwLock::new(HashMap::new())),
            lock_timeouts: Arc::new(RwLock::new(HashMap::new())),
        }
    }
    
    pub fn new_shared() -> Arc<Self> {
        Arc::new(Self::new())
    }
}

impl ThreadSafeEntityStateManager for ThreadSafeEntityStateManagerImpl {
    fn get_thread_safe_state(&self, entity_id: &str) -> Result<ThreadSafeEntityState> {
        let state = self.inner.get_state(entity_id)?;
        let current_thread = std::thread::current().id();
        
        Ok(ThreadSafeEntityState {
            state,
            owner_thread: current_thread,
            last_accessed: SystemTime::now(),
            access_count: 1,
        })
    }
    
    fn set_thread_safe_state(&self, entity_id: &str, thread_safe_state: ThreadSafeEntityState) -> Result<()> {
        self.inner.set_state(entity_id, thread_safe_state.state)?;
        Ok(())
    }
    
    fn acquire_entity_lock(&self, entity_id: &str, timeout_ms: u64) -> Result<ThreadSafeEntityLock> {
        let start_time = SystemTime::now();
        let timeout_duration = std::time::Duration::from_millis(timeout_ms);
        
        loop {
            let mut locks = self.entity_locks.write().unwrap();
            
            if !locks.contains_key(entity_id) {
                let lock = ThreadSafeEntityLock::new(entity_id.to_string());
                locks.insert(entity_id.to_string(), lock.clone());
                
                // Set lock timeout
                let mut timeouts = self.lock_timeouts.write().unwrap();
                timeouts.insert(entity_id.to_string(), SystemTime::now() + timeout_duration);
                
                return Ok(lock);
            }
            
            // Check timeout
            if SystemTime::now().duration_since(start_time).unwrap() > timeout_duration {
                return Err(crate::errors::RustError::Timeout(format!(
                    "Failed to acquire lock for entity {} within {}ms", entity_id, timeout_ms
                )));
            }
            
            // Small sleep to avoid busy waiting
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
    }
    
    fn release_entity_lock(&self, entity_id: &str) -> Result<()> {
        let mut locks = self.entity_locks.write().unwrap();
        let mut timeouts = self.lock_timeouts.write().unwrap();
        
        if !locks.contains_key(entity_id) {
            return Err(crate::errors::RustError::NotFound(format!("No lock found for entity {}", entity_id)));
        }
        
        locks.remove(entity_id);
        timeouts.remove(entity_id);
        
        Ok(())
    }
    
    fn is_entity_locked(&self, entity_id: &str) -> bool {
        let locks = self.entity_locks.read().unwrap();
        locks.contains_key(entity_id)
    }
    
    fn get_lock_owner(&self, entity_id: &str) -> Option<std::thread::ThreadId> {
        let locks = self.entity_locks.read().unwrap();
        locks.get(entity_id).map(|lock| lock.get_owner_thread())
    }
}

/// Default thread-safe state manager instance
static DEFAULT_THREAD_SAFE_STATE_MANAGER: OnceLock<Arc<ThreadSafeEntityStateManagerImpl>> = OnceLock::new();

/// Get the default thread-safe state manager
pub fn get_default_thread_safe_state_manager() -> Arc<ThreadSafeEntityStateManagerImpl> {
    DEFAULT_THREAD_SAFE_STATE_MANAGER.get_or_init(|| ThreadSafeEntityStateManagerImpl::new_shared()).clone()
}

/// Thread-safe entity processor implementation
pub struct ThreadSafeEntityProcessorImpl {
    state_manager: Arc<ThreadSafeEntityStateManagerImpl>,
}

impl ThreadSafeEntityProcessorImpl {
    pub fn new(state_manager: Arc<ThreadSafeEntityStateManagerImpl>) -> Self {
        Self { state_manager }
    }
}

impl ThreadSafeEntityProcessor for ThreadSafeEntityProcessorImpl {
    fn process_thread_safe(
        &self,
        entity_id: &str,
        entity_data: &EntityData,
        config: &ThreadSafeProcessingConfig,
    ) -> Result<ThreadSafeEntityOperationResult> {
        // Acquire lock
        let lock = self.state_manager.acquire_entity_lock(entity_id, config.lock_timeout_ms)?;
        
        // Process entity
        let result = ThreadSafeEntityOperationResult {
            success: true,
            message: "Entity processed successfully".to_string(),
            state: EntityState::Active,
            data: Some(entity_data.clone()),
            timestamp: SystemTime::now(),
            thread_id: std::thread::current().id(),
        };
        
        // Release lock
        self.state_manager.release_entity_lock(entity_id)?;
        
        Ok(result)
    }
    
    fn process_entities_concurrent(
        &self,
        entities: Vec<(String, EntityData)>,
        config: &ThreadSafeProcessingConfig,
    ) -> Result<Vec<ThreadSafeEntityOperationResult>> {
        use std::thread;
        
        let mut handles = Vec::new();
        let state_manager = self.state_manager.clone();
        
        for (entity_id, entity_data) in entities {
            let state_manager_clone = state_manager.clone();
            let config_clone = config.clone();
            
            let handle = thread::spawn(move || {
                let processor = ThreadSafeEntityProcessorImpl::new(state_manager_clone);
                processor.process_thread_safe(&entity_id, &entity_data, &config_clone)
            });
            
            handles.push(handle);
        }
        
        let mut results = Vec::new();
        for handle in handles {
            match handle.join() {
                Ok(result) => results.push(result?),
                Err(e) => return Err(crate::errors::RustError::ThreadError(format!("Thread join error: {:?}", e))),
            }
        }
        
        Ok(results)
    }
}

/// Helper function to create a thread-safe operation result
pub fn create_thread_safe_operation_result(
    success: bool,
    message: String,
    state: EntityState,
    data: Option<EntityData>,
) -> ThreadSafeEntityOperationResult {
    ThreadSafeEntityOperationResult {
        success,
        message,
        state,
        data,
        timestamp: SystemTime::now(),
        thread_id: std::thread::current().id(),
    }
}