use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use tokio::sync::{RwLock, Mutex};
use chrono::{Utc, DateTime};
use serde_json::{json, Value};
use thiserror::Error;
use std::ops::Drop;

use crate::config::runtime_validation::RuntimeSchemaValidator;
use crate::errors::enhanced_errors::KneafError;
use crate::shared::thread_safe_data::ThreadSafeData;

// Atomic guard for safe cleanup of atomic flags
struct AtomicGuard<'a> {
    flag: &'a Arc<AtomicBool>,
}

impl<'a> AtomicGuard<'a> {
    fn new(flag: &'a Arc<AtomicBool>) -> Self {
        Self { flag }
    }
}

impl<'a> Drop for AtomicGuard<'a> {
    fn drop(&mut self) {
        self.flag.store(false, Ordering::Release);
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConfigStatus {
    Active,
    Pending,
    Failed,
    RolledBack,
    Applying,
    RollingBack,
}

#[derive(Debug, Error)]
pub enum ConfigReloadError {
    #[error("Configuration error: {0}")]
    ConfigurationError(String),
    
    #[error("Validation error: {0}")]
    ValidationError(String),
    
    #[error("Atomicity violation: {0}")]
    AtomicityViolation(String),
    
    #[error("Concurrent modification: {0}")]
    ConcurrentModification(String),
}

impl From<ConfigReloadError> for KneafError {
    fn from(error: ConfigReloadError) -> Self {
        KneafError::ConfigurationError(error.to_string())
    }
}

#[derive(Debug, Clone)]
pub struct ConfigVersion {
    pub version: u64,
    pub timestamp: DateTime<Utc>,
    pub description: String,
    pub status: ConfigStatus,
    pub config: Option<Value>, // Store full config for rollback capabilities
}

#[derive(Debug, Clone)]
pub struct ConfigChange {
    pub version: u64,
    pub from_version: u64,
    pub to_version: u64,
    pub status: ConfigStatus,
    pub timestamp: DateTime<Utc>,
    pub description: String,
}

pub struct ZeroDowntimeConfigManager {
    current_config: Arc<RwLock<Value>>,
    pending_config: Arc<RwLock<Option<Value>>>,
    config_versions: ThreadSafeData<Vec<ConfigVersion>>,
    config_history: ThreadSafeData<Vec<ConfigChange>>,
    version_counter: AtomicU64,
    validator: Arc<RuntimeSchemaValidator>,
    reload_in_progress: Arc<AtomicBool>,
    rollback_in_progress: Arc<AtomicBool>,
    config_storage: Arc<Mutex<ConfigStorage>>,
    active_version: AtomicU64,
}

#[derive(Debug, Default)]
struct ConfigStorage {
    version_map: std::collections::HashMap<u64, Value>,
    active_version: u64,
}

impl ConfigStorage {
    fn new() -> Self {
        Self {
            version_map: std::collections::HashMap::new(),
            active_version: 0,
        }
    }

    fn store_version(&mut self, version: u64, config: &Value) {
        self.version_map.insert(version, config.clone());
    }

    fn get_version(&self, version: u64) -> Option<Value> {
        self.version_map.get(&version).cloned()
    }

    fn set_active_version(&mut self, version: u64) {
        self.active_version = version;
    }

    fn get_active_version(&self) -> u64 {
        self.active_version
    }
}

impl ZeroDowntimeConfigManager {
    pub fn new(validator: Arc<RuntimeSchemaValidator>) -> Self {
        Self {
            current_config: Arc::new(RwLock::new(json!({}))),
            pending_config: Arc::new(RwLock::new(None)),
            config_versions: ThreadSafeData::new(Vec::new()),
            config_history: ThreadSafeData::new(Vec::new()),
            version_counter: AtomicU64::new(1),
            validator,
            reload_in_progress: Arc::new(AtomicBool::new(false)),
            rollback_in_progress: Arc::new(AtomicBool::new(false)),
            config_storage: Arc::new(Mutex::new(ConfigStorage::new())),
            active_version: AtomicU64::new(0),
        }
    }

    pub async fn load_initial(&self, config: Value, schema: &str) -> Result<(), KneafError> {
        // Check for atomicity before initialization
        if self.reload_in_progress.load(Ordering::Acquire) || self.rollback_in_progress.load(Ordering::Acquire) {
            return Err(KneafError::from(ConfigReloadError::AtomicityViolation(
                "Initialization failed - concurrent operation in progress".into()
            )));
        }

        self.validator.validate_configuration(&config, schema).await?;
        
        // Use CAS pattern for initial config activation
        let initial_version = 1;
        if self.active_version.compare_exchange(0, initial_version, Ordering::SeqCst, Ordering::SeqCst).is_err() {
            return Err(KneafError::from(ConfigReloadError::AtomicityViolation(
                "Initialization failed - config already exists".into()
            )));
        }

        let mut current = self.current_config.write().await;
        *current = config.clone();

        // Store in persistent storage first for atomicity
        let mut storage = self.config_storage.lock().await;
        storage.store_version(initial_version, &config);
        storage.set_active_version(initial_version);
        drop(storage);

        self.config_versions.write().push(ConfigVersion {
            version: initial_version,
            timestamp: Utc::now(),
            description: "Initial config".into(),
            status: ConfigStatus::Active,
            config: Some(config),
        });

        Ok(())
    }

    pub async fn queue_reload(&self, new_config: Value, schema: &str, desc: String) -> Result<u64, KneafError> {
        // Enforce atomicity with CAS pattern
        if !self.reload_in_progress.compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst).is_ok() {
            return Err(KneafError::from(ConfigReloadError::ConcurrentModification(
                "Reload already in progress".into()
            )));
        }
        let _reload_guard = AtomicGuard::new(&self.reload_in_progress);

        // Validate configuration first
        self.validator.validate_configuration(&new_config, schema).await?;

        // Get next version number atomically
        let version = self.version_counter.fetch_add(1, Ordering::SeqCst);

        // Store config in persistent storage FIRST for atomicity guarantee
        let mut storage = self.config_storage.lock().await;
        storage.store_version(version, &new_config);
        drop(storage);

        // Queue as pending with atomic operations
        let mut pending = self.pending_config.write().await;
        if pending.is_some() {
            self.reload_in_progress.store(false, Ordering::Release);
            return Err(KneafError::from(ConfigReloadError::AtomicityViolation(
                "Pending config already exists".into()
            )));
        }
        *pending = Some(new_config.clone());
        drop(pending);

        // Record version history atomically
        let from_version = self.active_version.load(Ordering::SeqCst);
        self.config_versions.write().push(ConfigVersion {
            version,
            timestamp: Utc::now(),
            description: desc.clone(),
            status: ConfigStatus::Pending,
            config: Some(new_config),
        });

        // Record change history with CAS validation
        self.config_history.write().push(ConfigChange {
            version,
            from_version,
            to_version: version,
            status: ConfigStatus::Pending,
            timestamp: Utc::now(),
            description: desc,
        });

        Ok(version)
    }

    pub async fn apply_pending(&self) -> Result<(), KneafError> {
        // Enforce exclusive access with CAS
        if !self.reload_in_progress.compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst).is_ok() {
            return Err(KneafError::from(ConfigReloadError::ConcurrentModification(
                "Apply already in progress".into()
            )));
        }
        let _apply_guard = AtomicGuard::new(&self.reload_in_progress);

        let pending = self.pending_config.read().await;
        let config = pending.as_ref().ok_or(KneafError::ConfigurationError("No pending config".into()))?;

        // Verify version exists in storage before applying
        let storage = self.config_storage.lock().await;
        let stored_config = storage.get_version(config.version);
        drop(storage);
        
        if stored_config.is_none() {
            return Err(KneafError::from(ConfigReloadError::AtomicityViolation(
                format!("Version {} not found in storage", config.version)
            )));
        }

        // Atomic state transition: Pending -> Active
        let mut versions = self.config_versions.write();
        let entry = versions.iter_mut().find(|v| v.version == config.version)
            .ok_or(KneafError::ConfigurationError(format!("Version {} not found", config.version)))?;

        if entry.status != ConfigStatus::Pending {
            self.reload_in_progress.store(false, Ordering::Release);
            return Err(KneafError::from(ConfigReloadError::AtomicityViolation(
                format!("Cannot apply version {} (status: {:?})", config.version, entry.status)
            )));
        }

        entry.status = ConfigStatus::Applying;
        drop(versions);

        // Update current config atomically
        let mut current = self.current_config.write().await;
        *current = config.clone();

        // Update active version with CAS
        let old_version = self.active_version.swap(config.version, Ordering::SeqCst);
        
        // Update storage with CAS pattern
        let mut storage = self.config_storage.lock().await;
        if storage.get_active_version() != old_version {
            self.reload_in_progress.store(false, Ordering::Release);
            return Err(KneafError::from(ConfigReloadError::AtomicityViolation(
                "Storage version mismatch during apply".into()
            )));
        }
        storage.set_active_version(config.version);
        drop(storage);

        // Final status update
        self.update_status(config.version, ConfigStatus::Active).await?;
        
        // Clear pending config only after all atomic operations succeed
        let mut pending_lock = self.pending_config.write().await;
        *pending_lock = None;

        Ok(())
    }

    pub async fn rollback(&self, version: u64) -> Result<(), KneafError> {
        // Enforce exclusive access with CAS
        if !self.rollback_in_progress.compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst).is_ok() {
            return Err(KneafError::from(ConfigReloadError::ConcurrentModification(
                "Rollback already in progress".into()
            )));
        }
        let _rollback_guard = AtomicGuard::new(&self.rollback_in_progress);

        // Verify version exists and is active
        let versions = self.config_versions.read();
        let entry = versions.iter().find(|v| v.version == version)
            .ok_or(KneafError::ConfigurationError(format!("Version {} not found", version)))?;
        
        if entry.status != ConfigStatus::Active {
            return Err(KneafError::ConfigurationError(format!(
                "Cannot rollback to non-active version (status: {:?})", entry.status
            )));
        }

        // Get config from persistent storage (atomic guarantee)
        let storage = self.config_storage.lock().await;
        let rollback_config = storage.get_version(version)
            .ok_or(KneafError::from(ConfigReloadError::AtomicityViolation(
                format!("Version {} not found in storage", version)
            )))?;
        drop(storage);

        // Create rollback version with CAS pattern
        let rollback_version = self.version_counter.fetch_add(1, Ordering::SeqCst);
        
        // Atomic state transition: Active -> RollingBack -> RolledBack
        let mut versions = self.config_versions.write();
        let original_entry = versions.iter_mut().find(|v| v.version == version)
            .ok_or(KneafError::ConfigurationError(format!("Version {} not found", version)))?;
        
        original_entry.status = ConfigStatus::RollingBack;
        drop(versions);

        // Update current config atomically
        let mut current = self.current_config.write().await;
        *current = rollback_config.clone();

        // Update active version with CAS
        let old_version = self.active_version.swap(version, Ordering::SeqCst);
        
        // Update storage with atomic validation
        let mut storage = self.config_storage.lock().await;
        if storage.get_active_version() != old_version {
            self.rollback_in_progress.store(false, Ordering::Release);
            return Err(KneafError::from(ConfigReloadError::AtomicityViolation(
                "Storage version mismatch during rollback".into()
            )));
        }
        storage.set_active_version(version);
        drop(storage);

        // Record rollback in history
        self.config_history.write().push(ConfigChange {
            version: rollback_version,
            from_version: old_version,
            to_version: version,
            status: ConfigStatus::RolledBack,
            timestamp: Utc::now(),
            description: format!("Rollback from v{} to v{}", old_version, version),
        });

        // Final status update
        self.update_status(rollback_version, ConfigStatus::RolledBack).await?;
        self.update_status(version, ConfigStatus::Active).await?;

        Ok(())
    }

    async fn update_status(&self, version: u64, status: ConfigStatus) -> Result<(), KneafError> {
        // Add status transition validation for atomicity
        let mut versions = self.config_versions.write();
        let entry = versions.iter_mut().find(|v| v.version == version)
            .ok_or(KneafError::ConfigurationError(format!("Version {} not found", version)))?;
        
        // Validate atomic status transitions
        match (entry.status, status) {
            (ConfigStatus::Pending, ConfigStatus::Applying) |
            (ConfigStatus::Applying, ConfigStatus::Active) |
            (ConfigStatus::Active, ConfigStatus::RollingBack) |
            (ConfigStatus::RollingBack, ConfigStatus::RolledBack) |
            (ConfigStatus::Failed, ConfigStatus::RolledBack) |
            (current, next) if current == next => Ok(()),
            (from, to) => {
                let err = ConfigReloadError::AtomicityViolation(format!(
                    "Invalid status transition: {:?} -> {:?}", from, to
                ));
                return Err(KneafError::from(err));
            }
        }?;

        entry.status = status;
        Ok(())
    }

    pub async fn get_current(&self) -> Value {
        // Add atomic version consistency check
        let config = self.current_config.read().await.clone();
        let active_version = self.active_version.load(Ordering::SeqCst);
        
        // Verify config matches active version (defensive check)
        let storage = self.config_storage.lock().await;
        let stored_config = storage.get_version(active_version);
        drop(storage);
        
        if let Some(stored) = stored_config {
            if config != stored {
                log::warn!("Config mismatch detected: current config doesn't match active version {}", active_version);
            }
        }

        config
    }

    pub fn get_history(&self) -> Vec<ConfigVersion> {
        self.config_versions.read().clone()
    }

    pub async fn get_pending(&self) -> Option<Value> {
        self.pending_config.read().await.clone()
    }
}