use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Instant;

#[derive(Debug, Clone)]
pub struct EntityState {
    pub entity_id: u64,
    pub position: (f64, f64, f64),
    pub velocity: (f64, f64, f64),
    pub health: f64,
    pub last_updated: Instant,
    pub metadata: HashMap<String, String>,
}

impl EntityState {
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

pub struct StateManager {
    entities: HashMap<u64, EntityState>,
}

impl StateManager {
    pub fn new() -> Self {
        Self {
            entities: HashMap::new(),
        }
    }

    pub fn register_entity(&mut self, entity_id: u64, position: (f64, f64, f64)) {
        let entity_state = EntityState::new(entity_id, position);
        self.entities.insert(entity_id, entity_state);
    }

    pub fn unregister_entity(&mut self, entity_id: u64) {
        self.entities.remove(&entity_id);
    }

    pub fn update_entity_position(&mut self, entity_id: u64, position: (f64, f64, f64)) -> bool {
        if let Some(entity) = self.entities.get_mut(&entity_id) {
            entity.update_position(position);
            true
        } else {
            false
        }
    }

    pub fn update_entity_velocity(&mut self, entity_id: u64, velocity: (f64, f64, f64)) -> bool {
        if let Some(entity) = self.entities.get_mut(&entity_id) {
            entity.update_velocity(velocity);
            true
        } else {
            false
        }
    }

    pub fn update_entity_health(&mut self, entity_id: u64, health: f64) -> bool {
        if let Some(entity) = self.entities.get_mut(&entity_id) {
            entity.update_health(health);
            true
        } else {
            false
        }
    }

    pub fn set_entity_metadata(&mut self, entity_id: u64, key: String, value: String) -> bool {
        if let Some(entity) = self.entities.get_mut(&entity_id) {
            entity.set_metadata(key, value);
            true
        } else {
            false
        }
    }

    pub fn get_entity_metadata(&self, entity_id: u64, key: &str) -> Option<String> {
        self.entities
            .get(&entity_id)
            .and_then(|entity| entity.get_metadata(key).cloned())
    }

    pub fn get_entity_state(&self, entity_id: u64) -> Option<&EntityState> {
        self.entities.get(&entity_id)
    }

    pub fn get_all_entities(&self) -> Vec<&EntityState> {
        self.entities.values().collect()
    }

    pub fn entity_count(&self) -> usize {
        self.entities.len()
    }
}