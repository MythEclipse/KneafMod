use crate::errors::Result;
use crate::types::{EntityConfigTrait as EntityConfig, EntityDataTrait as EntityData, EntityTypeTrait as EntityType};
use std::sync::OnceLock;
use std::collections::HashMap;
use std::sync::{Arc, RwLock};
use std::fmt::Debug;
use std::time::{SystemTime, Instant};

/// Entity state representation
#[derive(Debug, Clone, PartialEq)]
pub enum EntityState {
    Idle,
    Active,
    Processing,
    Error(String),
    Disabled,
    Destroyed,
}

/// Entity operation result
#[derive(Debug, Clone)]
pub struct EntityOperationResult {
    pub success: bool,
    pub message: String,
    pub state: EntityState,
    pub data: Option<EntityData>,
    pub timestamp: SystemTime,
}

/// State manager trait for entity state management
pub trait StateManager: Send + Sync {
    /// Get current state
    fn get_state(&self, entity_id: &str) -> Result<EntityState>;
    
    /// Set state
    fn set_state(&self, entity_id: &str, state: EntityState) -> Result<()>;
    
    /// Check if entity exists
    fn exists(&self, entity_id: &str) -> bool;
    
    /// Register entity
    fn register_entity(&self, entity_id: &str, initial_state: EntityState) -> Result<()>;
    
    /// Unregister entity
    fn unregister_entity(&self, entity_id: &str) -> Result<()>;
    
    /// Get all entity states
    fn get_all_states(&self) -> Result<HashMap<String, EntityState>>;
    
    /// Clear all states
    fn clear(&self) -> Result<()>;
}

/// Thread-safe entity state manager implementation
pub struct EntityStateManager {
    states: Arc<RwLock<HashMap<String, EntityState>>>,
    metadata: Arc<RwLock<HashMap<String, EntityMetadata>>>,
}

/// Entity metadata
#[derive(Debug, Clone)]
pub struct EntityMetadata {
    pub entity_type: EntityType,
    pub created_at: SystemTime,
    pub last_updated: SystemTime,
    pub update_count: u64,
}

impl EntityStateManager {
    pub fn new() -> Self {
        Self {
            states: Arc::new(RwLock::new(HashMap::new())),
            metadata: Arc::new(RwLock::new(HashMap::new())),
        }
    }
    
    /// Create a new state manager with shared ownership
    pub fn new_shared() -> Arc<Self> {
        Arc::new(Self::new())
    }
}

impl StateManager for EntityStateManager {
    fn get_state(&self, entity_id: &str) -> Result<EntityState> {
        let states = self.states.read().unwrap();
        match states.get(entity_id) {
            Some(state) => Ok(state.clone()),
            None => Err(crate::errors::RustError::NotFound(format!("Entity {} not found", entity_id))),
        }
    }
    
    fn set_state(&self, entity_id: &str, state: EntityState) -> Result<()> {
        let mut states = self.states.write().unwrap();
        if !states.contains_key(entity_id) {
            return Err(crate::errors::RustError::NotFound(format!("Entity {} not found", entity_id)));
        }
        
        states.insert(entity_id.to_string(), state);
        
        // Update metadata
        let mut metadata = self.metadata.write().unwrap();
        if let Some(meta) = metadata.get_mut(entity_id) {
            meta.last_updated = SystemTime::now();
            meta.update_count += 1;
        }
        
        Ok(())
    }
    
    fn exists(&self, entity_id: &str) -> bool {
        let states = self.states.read().unwrap();
        states.contains_key(entity_id)
    }
    
    fn register_entity(&self, entity_id: &str, initial_state: EntityState) -> Result<()> {
        let mut states = self.states.write().unwrap();
        let mut metadata = self.metadata.write().unwrap();
        
        if states.contains_key(entity_id) {
            return Err(crate::errors::RustError::AlreadyExists(format!("Entity {} already exists", entity_id)));
        }
        
        states.insert(entity_id.to_string(), initial_state);
        metadata.insert(entity_id.to_string(), EntityMetadata {
            entity_type: EntityType::Unknown, // Default type
            created_at: SystemTime::now(),
            last_updated: SystemTime::now(),
            update_count: 0,
        });
        
        Ok(())
    }
    
    fn unregister_entity(&self, entity_id: &str) -> Result<()> {
        let mut states = self.states.write().unwrap();
        let mut metadata = self.metadata.write().unwrap();
        
        if !states.contains_key(entity_id) {
            return Err(crate::errors::RustError::NotFound(format!("Entity {} not found", entity_id)));
        }
        
        states.remove(entity_id);
        metadata.remove(entity_id);
        
        Ok(())
    }
    
    fn get_all_states(&self) -> Result<HashMap<String, EntityState>> {
        let states = self.states.read().unwrap();
        Ok(states.clone())
    }
    
    fn clear(&self) -> Result<()> {
        let mut states = self.states.write().unwrap();
        let mut metadata = self.metadata.write().unwrap();
        
        states.clear();
        metadata.clear();
        
        Ok(())
    }
}

/// Default state manager instance
static DEFAULT_STATE_MANAGER: OnceLock<Arc<EntityStateManager>> = OnceLock::new();

/// Get the default state manager
pub fn get_default_state_manager() -> Arc<EntityStateManager> {
    DEFAULT_STATE_MANAGER.get_or_init(|| EntityStateManager::new_shared()).clone()
}

/// Helper function to create an operation result
pub fn create_operation_result(
    success: bool,
    message: String,
    state: EntityState,
    data: Option<EntityData>,
) -> EntityOperationResult {
    EntityOperationResult {
        success,
        message,
        state,
        data,
        timestamp: SystemTime::now(),
    }
}

/// State transition helper
pub fn transition_state(
    current_state: &EntityState,
    target_state: EntityState,
) -> Result<EntityState> {
    // Validate state transitions
    match (current_state, &target_state) {
        (EntityState::Destroyed, _) => Err(crate::errors::RustError::InvalidState(
            "Cannot transition from Destroyed state".to_string()
        )),
        (EntityState::Error(_), EntityState::Processing) => Ok(target_state),
        (EntityState::Processing, EntityState::Error(_)) => Ok(target_state),
        (EntityState::Processing, EntityState::Active) => Ok(target_state),
        (EntityState::Active, EntityState::Processing) => Ok(target_state),
        (EntityState::Active, EntityState::Idle) => Ok(target_state),
        (EntityState::Idle, EntityState::Active) => Ok(target_state),
        (EntityState::Idle, EntityState::Processing) => Ok(target_state),
        (EntityState::Disabled, EntityState::Idle) => Ok(target_state),
        (EntityState::Idle, EntityState::Disabled) => Ok(target_state),
        _ => Ok(target_state), // Allow most transitions by default
    }
}