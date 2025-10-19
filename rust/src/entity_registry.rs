//! Entity Registry System for KneafMod ECS
//! 
//! This module provides a comprehensive entity registration and management system
//! that replaces the Java-based entity system with a native Rust implementation.
//! Features include component-based architecture, efficient entity lookup,
//! and integration with the performance monitoring system.

use std::collections::HashMap;
use std::sync::{Arc, RwLock};
use std::any::{Any, TypeId};
use rayon::prelude::*;
use crate::performance_monitor::PerformanceMonitor;

/// Unique identifier for entities in the ECS
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct EntityId(pub u64);

/// Component trait that all ECS components must implement
pub trait Component: Send + Sync + Any + 'static {
    /// Get the component type identifier
    fn component_type(&self) -> TypeId {
        TypeId::of::<Self>()
    }
}

/// Base entity structure containing unique identifier and metadata
#[derive(Debug, Clone)]
pub struct Entity {
    pub id: EntityId,
    pub entity_type: EntityType,
    pub created_at: std::time::Instant,
    pub active: bool,
}

/// Enumeration of supported entity types
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum EntityType {
    ShadowZombieNinja,
    Player,
    Monster,
    Item,
    Projectile,
    Environmental,
}

/// Component storage using type-erased containers
#[derive(Debug)]
pub struct ComponentStorage {
    components: HashMap<TypeId, Box<dyn Any + Send + Sync>>,
}

impl ComponentStorage {
    pub fn new() -> Self {
        Self {
            components: HashMap::new(),
        }
    }

    /// Insert a component into storage
    pub fn insert<T: Component>(&mut self, component: T) {
        self.components.insert(TypeId::of::<T>(), Box::new(component));
    }

    /// Get a component by type
    pub fn get<T: Component>(&self) -> Option<&T> {
        self.components
            .get(&TypeId::of::<T>())
            .and_then(|boxed| boxed.downcast_ref::<T>())
    }

    /// Remove a component by type
    pub fn remove<T: Component>(&mut self) -> Option<T> {
        self.components
            .remove(&TypeId::of::<T>())
            .and_then(|boxed| boxed.downcast::<T>().ok())
            .map(|boxed| *boxed)
    }

    /// Check if component exists
    pub fn contains<T: Component>(&self) -> bool {
        self.components.contains_key(&TypeId::of::<T>())
    }
}

/// Main entity registry managing all entities and their components
pub struct EntityRegistry {
    entities: Arc<RwLock<HashMap<EntityId, Entity>>>,
    component_storages: Arc<RwLock<HashMap<EntityId, ComponentStorage>>>,
    entity_type_map: Arc<RwLock<HashMap<EntityType, Vec<EntityId>>>>,
    performance_monitor: Arc<PerformanceMonitor>,
    next_entity_id: Arc<RwLock<u64>>,
}

impl EntityRegistry {
    /// Create a new entity registry instance
    pub fn new(performance_monitor: Arc<PerformanceMonitor>) -> Self {
        Self {
            entities: Arc::new(RwLock::new(HashMap::new())),
            component_storages: Arc::new(RwLock::new(HashMap::new())),
            entity_type_map: Arc::new(RwLock::new(HashMap::new())),
            performance_monitor,
            next_entity_id: Arc::new(RwLock::new(1)),
        }
    }

    /// Register a new entity with the given type
    pub fn create_entity(&self, entity_type: EntityType) -> EntityId {
        let start_time = std::time::Instant::now();
        
        let entity_id = self.generate_entity_id();
        // clone the entity_type for insertion into the Entity struct so we can still use
        // the original parameter below when updating the entity_type_map
        let entity = Entity {
            id: entity_id,
            entity_type: entity_type.clone(),
            created_at: std::time::Instant::now(),
            active: true,
        };

        // Add to main entity storage
        self.entities.write().unwrap().insert(entity_id, entity.clone());
        
        // Add to type-specific storage
        self.entity_type_map.write().unwrap()
            .entry(entity_type.clone())
            .or_insert_with(Vec::new)
            .push(entity_id);

        // Create component storage for this entity
        self.component_storages.write().unwrap()
            .insert(entity_id, ComponentStorage::new());

        // Record performance metrics
        let duration = start_time.elapsed();
        self.performance_monitor.record_metric(
            "entity_creation_time",
            duration.as_secs_f64(),
        );

        entity_id
    }

    /// Remove an entity and all its components
    pub fn remove_entity(&self, entity_id: EntityId) -> bool {
        let start_time = std::time::Instant::now();
        
        // Remove from main storage
        if let Some(entity) = self.entities.write().unwrap().remove(&entity_id) {
            // Remove from type-specific storage
            self.entity_type_map.write().unwrap()
                .get_mut(&entity.entity_type)
                .map(|vec| vec.retain(|&id| id != entity_id));

            // Remove component storage
            self.component_storages.write().unwrap().remove(&entity_id);

            // Record performance metrics
            let duration = start_time.elapsed();
            self.performance_monitor.record_metric(
                "entity_removal_time",
                duration.as_secs_f64(),
            );

            true
        } else {
            false
        }
    }

    /// Get entity by ID
    pub fn get_entity(&self, entity_id: EntityId) -> Option<Entity> {
        self.entities.read().unwrap().get(&entity_id).cloned()
    }

    /// Add component to an entity
    pub fn add_component<T: Component>(&self, entity_id: EntityId, component: T) -> bool {
        if let Some(storage) = self.component_storages.write().unwrap().get_mut(&entity_id) {
            storage.insert(component);
            
            self.performance_monitor.record_metric(
                "component_add_count",
                1.0,
            );
            
            true
        } else {
            false
        }
    }

    /// Get component from an entity
    pub fn get_component<T: Component + Clone>(&self, entity_id: EntityId) -> Option<Arc<T>> {
        self.component_storages.read().unwrap()
            .get(&entity_id)?
            .get::<T>()
            .map(|component| Arc::new(component.clone()))
    }

    /// Remove component from an entity
    pub fn remove_component<T: Component>(&self, entity_id: EntityId) -> bool {
        if let Some(storage) = self.component_storages.write().unwrap().get_mut(&entity_id) {
            let removed = storage.remove::<T>().is_some();
            
            if removed {
                self.performance_monitor.record_metric(
                    "component_remove_count",
                    1.0,
                );
            }
            
            removed
        } else {
            false
        }
    }

    /// Get all entities of a specific type
    pub fn get_entities_by_type(&self, entity_type: EntityType) -> Vec<EntityId> {
        self.entity_type_map.read().unwrap()
            .get(&entity_type)
            .cloned()
            .unwrap_or_default()
    }

    /// Process entities in parallel using Rayon
    pub fn process_entities_parallel<F>(&self, processor: F) 
    where 
        F: Fn(EntityId, &Entity, &ComponentStorage) + Send + Sync
    {
        let start_time = std::time::Instant::now();
        
        let entities = self.entities.read().unwrap();
        let storages = self.component_storages.read().unwrap();
        
        let entity_data: Vec<_> = entities
            .iter()
            .filter_map(|(id, entity)| {
                storages.get(id).map(|storage| (*id, entity.clone(), storage))
            })
            .collect();

        entity_data.par_iter().for_each(|(id, entity, storage)| {
            processor(*id, entity, storage);
        });

        let duration = start_time.elapsed();
        self.performance_monitor.record_metric(
            "parallel_entity_processing_time",
            duration.as_secs_f64(),
        );
    }

    /// Get entity count by type
    pub fn get_entity_count(&self, entity_type: Option<EntityType>) -> usize {
        match entity_type {
            Some(entity_type) => {
                self.entity_type_map.read().unwrap()
                    .get(&entity_type)
                    .map(|vec| vec.len())
                    .unwrap_or(0)
            }
            None => {
                self.entities.read().unwrap().len()
            }
        }
    }

    /// Check if entity exists
    pub fn entity_exists(&self, entity_id: EntityId) -> bool {
        self.entities.read().unwrap().contains_key(&entity_id)
    }

    /// Deactivate an entity (soft delete)
    pub fn deactivate_entity(&self, entity_id: EntityId) -> bool {
        if let Some(entity) = self.entities.write().unwrap().get_mut(&entity_id) {
            entity.active = false;
            
            self.performance_monitor.record_metric(
                "entity_deactivation_count",
                1.0,
            );
            
            true
        } else {
            false
        }
    }

    /// Reactivate a deactivated entity
    pub fn reactivate_entity(&self, entity_id: EntityId) -> bool {
        if let Some(entity) = self.entities.write().unwrap().get_mut(&entity_id) {
            entity.active = true;
            
            self.performance_monitor.record_metric(
                "entity_reactivation_count",
                1.0,
            );
            
            true
        } else {
            false
        }
    }

    /// Generate a unique entity ID
    fn generate_entity_id(&self) -> EntityId {
        let mut next_id = self.next_entity_id.write().unwrap();
        let current_id = *next_id;
        *next_id += 1;
        EntityId(current_id)
    }

    /// Get registry statistics
    pub fn get_statistics(&self) -> EntityRegistryStats {
        let entities = self.entities.read().unwrap();
        let type_map = self.entity_type_map.read().unwrap();
        
        EntityRegistryStats {
            total_entities: entities.len(),
            active_entities: entities.values().filter(|e| e.active).count(),
            entities_by_type: type_map.iter()
                .map(|(entity_type, ids)| (entity_type.clone(), ids.len()))
                .collect(),
        }
    }
}

/// Statistics about the entity registry
#[derive(Debug, Clone)]
pub struct EntityRegistryStats {
    pub total_entities: usize,
    pub active_entities: usize,
    pub entities_by_type: HashMap<EntityType, usize>,
}

/// System trait for ECS systems that operate on entities
pub trait EntitySystem: Send + Sync {
    /// Update the system for a single frame/tick
    fn update(&mut self, registry: &EntityRegistry, delta_time: f64);
    
    /// Get the system name for debugging and monitoring
    fn name(&self) -> &str;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Debug, Clone)]
    struct TestComponent {
        value: i32,
    }

    impl Component for TestComponent {}

    #[test]
    fn test_entity_creation() {
        let monitor = Arc::new(PerformanceMonitor::new());
        let registry = EntityRegistry::new(monitor);
        
        let entity_id = registry.create_entity(EntityType::ShadowZombieNinja);
        assert!(registry.entity_exists(entity_id));
        
        let entity = registry.get_entity(entity_id).unwrap();
        assert_eq!(entity.entity_type, EntityType::ShadowZombieNinja);
        assert!(entity.active);
    }

    #[test]
    fn test_component_management() {
        let monitor = Arc::new(PerformanceMonitor::new());
        let registry = EntityRegistry::new(monitor);
        
        let entity_id = registry.create_entity(EntityType::ShadowZombieNinja);
        let component = TestComponent { value: 42 };
        
        assert!(registry.add_component(entity_id, component));
        
        let retrieved = registry.get_component::<TestComponent>(entity_id);
        assert!(retrieved.is_some());
        assert_eq!(retrieved.unwrap().value, 42);
        
        assert!(registry.remove_component::<TestComponent>(entity_id));
        assert!(registry.get_component::<TestComponent>(entity_id).is_none());
    }

    #[test]
    fn test_entity_removal() {
        let monitor = Arc::new(PerformanceMonitor::new());
        let registry = EntityRegistry::new(monitor);
        
        let entity_id = registry.create_entity(EntityType::ShadowZombieNinja);
        assert!(registry.remove_entity(entity_id));
        assert!(!registry.entity_exists(entity_id));
    }
}