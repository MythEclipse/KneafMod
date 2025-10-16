use std::collections::HashMap;
use std::sync::{Arc, Mutex, RwLock};
use std::time::Instant;

use crate::state_manager::entity_state::{EntityState, StateManager};

#[derive(Debug, Clone)]
pub struct ThreadSafeEntityState {
    pub entity_id: u64,
    pub position: (f64, f64, f64),
    pub velocity: (f64, f64, f64),
    pub health: f64,
    pub last_updated: Instant,
    pub metadata: HashMap<String, String>,
}

impl ThreadSafeEntityState {
    pub fn new(entity_id: u64, position: (f64, f64, f64)) -> Self {
        Self {
            entity_id,
            position,
            velocity: (0.0, 0.0, 0.0),
            health: 100.0,
            last_updated: Instant::now(),
            metadata: HashMap::new(),
        }
    }

    pub fn update_position(&mut self, new_position: (f64, f64, f64)) {
        self.position = new_position;
        self.last_updated = Instant::now();
    }

    pub fn update_velocity(&mut self, new_velocity: (f64, f64, f64)) {
        self.velocity = new_velocity;
        self.last_updated = Instant::now();
    }

    pub fn update_health(&mut self, new_health: f64) {
        self.health = new_health;
        self.last_updated = Instant::now();
    }

    pub fn set_metadata(&mut self, key: String, value: String) {
        self.metadata.insert(key, value);
        self.last_updated = Instant::now();
    }

    pub fn get_metadata(&self, key: &str) -> Option<&String> {
        self.metadata.get(key)
    }
}

pub struct ThreadSafeStateManager {
    entities: Arc<RwLock<HashMap<u64, ThreadSafeEntityState>>>,
}

impl ThreadSafeStateManager {
    pub fn new() -> Self {
        Self {
            entities: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    pub fn register_entity(&self, entity_id: u64, position: (f64, f64, f64)) {
        let entity_state = ThreadSafeEntityState::new(entity_id, position);
        let mut entities = self.entities.write().unwrap();
        entities.insert(entity_id, entity_state);
    }

    pub fn unregister_entity(&self, entity_id: u64) {
        let mut entities = self.entities.write().unwrap();
        entities.remove(&entity_id);
    }

    pub fn update_entity_position(&self, entity_id: u64, position: (f64, f64, f64)) -> bool {
        let mut entities = self.entities.write().unwrap();
        if let Some(entity) = entities.get_mut(&entity_id) {
            entity.update_position(position);
            true
        } else {
            false
        }
    }

    pub fn update_entity_velocity(&self, entity_id: u64, velocity: (f64, f64, f64)) -> bool {
        let mut entities = self.entities.write().unwrap();
        if let Some(entity) = entities.get_mut(&entity_id) {
            entity.update_velocity(velocity);
            true
        } else {
            false
        }
    }

    pub fn update_entity_health(&self, entity_id: u64, health: f64) -> bool {
        let mut entities = self.entities.write().unwrap();
        if let Some(entity) = entities.get_mut(&entity_id) {
            entity.update_health(health);
            true
        } else {
            false
        }
    }

    pub fn set_entity_metadata(&self, entity_id: u64, key: String, value: String) -> bool {
        let mut entities = self.entities.write().unwrap();
        if let Some(entity) = entities.get_mut(&entity_id) {
            entity.set_metadata(key, value);
            true
        } else {
            false
        }
    }

    pub fn get_entity_metadata(&self, entity_id: u64, key: &str) -> Option<String> {
        let entities = self.entities.read().unwrap();
        entities
            .get(&entity_id)
            .and_then(|entity| entity.get_metadata(key).cloned())
    }

    pub fn get_entity_state(&self, entity_id: u64) -> Option<ThreadSafeEntityState> {
        let entities = self.entities.read().unwrap();
        entities.get(&entity_id).cloned()
    }

    pub fn get_all_entities(&self) -> Vec<ThreadSafeEntityState> {
        let entities = self.entities.read().unwrap();
        entities.values().cloned().collect()
    }

    pub fn entity_count(&self) -> usize {
        let entities = self.entities.read().unwrap();
        entities.len()
    }

    pub fn get_entities_arc(&self) -> Arc<RwLock<HashMap<u64, ThreadSafeEntityState>>> {
        self.entities.clone()
    }
}

// Conversion functions between thread-safe and non-thread-safe versions
impl From<EntityState> for ThreadSafeEntityState {
    fn from(entity: EntityState) -> Self {
        Self {
            entity_id: entity.entity_id,
            position: entity.position,
            velocity: entity.velocity,
            health: entity.health,
            last_updated: entity.last_updated,
            metadata: entity.metadata,
        }
    }
}

impl From<ThreadSafeEntityState> for EntityState {
    fn from(entity: ThreadSafeEntityState) -> Self {
        Self {
            entity_id: entity.entity_id,
            position: entity.position,
            velocity: entity.velocity,
            health: entity.health,
            last_updated: entity.last_updated,
            metadata: entity.metadata,
        }
    }
}