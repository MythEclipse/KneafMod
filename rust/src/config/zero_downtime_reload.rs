use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use tokio::sync::{RwLock};
use chrono::{Utc, DateTime};
use serde_json::{json, Value};

use crate::config::runtime_validation::RuntimeSchemaValidator;
use crate::errors::enhanced_errors::KneafError;
use crate::shared::thread_safe_data::ThreadSafeData;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ConfigStatus {
    Active,
    Pending,
    Failed,
    RolledBack,
}

#[derive(Debug, Clone)]
pub struct ConfigVersion {
    pub version: u64,
    pub timestamp: DateTime<Utc>,
    pub description: String,
    pub status: ConfigStatus,
}

pub struct ZeroDowntimeConfigManager {
    current_config: Arc<RwLock<Value>>,
    pending_config: Arc<RwLock<Option<Value>>>,
    config_versions: ThreadSafeData<Vec<ConfigVersion>>,
    version_counter: AtomicU64,
    validator: Arc<RuntimeSchemaValidator>,
}

impl ZeroDowntimeConfigManager {
    pub fn new(validator: Arc<RuntimeSchemaValidator>) -> Self {
        Self {
            current_config: Arc::new(RwLock::new(json!({}))),
            pending_config: Arc::new(RwLock::new(None)),
            config_versions: ThreadSafeData::new(Vec::new()),
            version_counter: AtomicU64::new(1),
            validator,
        }
    }

    pub async fn load_initial(&self, config: Value, schema: &str) -> Result<(), KneafError> {
        self.validator.validate_configuration(&config, schema).await?;
        let mut current = self.current_config.write().await;
        *current = config.clone();
        
        self.config_versions.write().push(ConfigVersion {
            version: 1,
            timestamp: Utc::now(),
            description: "Initial config".into(),
            status: ConfigStatus::Active,
        });
        Ok(())
    }

    pub async fn queue_reload(&self, new_config: Value, schema: &str, desc: String) -> Result<u64, KneafError> {
        self.validator.validate_configuration(&new_config, schema).await?;
        let version = self.version_counter.fetch_add(1, Ordering::SeqCst);
        
        let mut pending = self.pending_config.write().await;
        *pending = Some(new_config.clone());
        
        self.config_versions.write().push(ConfigVersion {
            version,
            timestamp: Utc::now(),
            description: desc,
            status: ConfigStatus::Pending,
        });
        
        Ok(version)
    }

    pub async fn apply_pending(&self) -> Result<(), KneafError> {
        let pending = self.pending_config.read().await;
        let config = pending.as_ref().ok_or(KneafError::ConfigurationError("No pending config".into()))?;
        
        let mut current = self.current_config.write().await;
        *current = config.clone();
        
        self.update_status(config.version, ConfigStatus::Active).await?;
        let mut pending_lock = self.pending_config.write().await;
        *pending_lock = None;
        
        Ok(())
    }

    pub async fn rollback(&self, version: u64) -> Result<(), KneafError> {
        let versions = self.config_versions.read();
        let entry = versions.iter().find(|v| v.version == version)
            .ok_or(KneafError::ConfigurationError(format!("Version {} not found", version)))?;
        
        if entry.status != ConfigStatus::Active {
            return Err(KneafError::ConfigurationError(format!(
                "Cannot rollback to non-active version (status: {:?})", entry.status
            )));
        }

        let mut current = self.current_config.write().await;
        *current = entry.config.clone(); // Simplified - in real code, fetch from storage
        
        self.config_versions.write().push(ConfigVersion {
            version: self.version_counter.fetch_add(1, Ordering::SeqCst),
            timestamp: Utc::now(),
            description: format!("Rollback to v{}", version),
            status: ConfigStatus::RolledBack,
        });
        
        Ok(())
    }

    async fn update_status(&self, version: u64, status: ConfigStatus) -> Result<(), KneafError> {
        let mut versions = self.config_versions.write();
        let entry = versions.iter_mut().find(|v| v.version == version)
            .ok_or(KneafError::ConfigurationError(format!("Version {} not found", version)))?;
        
        entry.status = status;
        Ok(())
    }

    pub async fn get_current(&self) -> Value {
        self.current_config.read().await.clone()
    }

    pub fn get_history(&self) -> Vec<ConfigVersion> {
        self.config_versions.read().clone()
    }

    pub async fn get_pending(&self) -> Option<Value> {
        self.pending_config.read().await.clone()
    }
}