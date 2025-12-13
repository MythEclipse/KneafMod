//! Entity Modulation System - Generic Framework Implementation
//!
//! This module provides the main integration point for the Entity Processing System,
//! now using the generic entity framework instead of specific entity types.

use glam::Vec3;
use rayon::prelude::*;
use std::collections::{HashMap, VecDeque};
use std::sync::Arc;
use std::time::{Duration, Instant};

use crate::combat_system::{CombatEvent, CombatSystem};
use crate::entity_framework::CombatComponent;
use crate::entity_framework::{
    AIComponent, AIType, AnimationComponent, BoundingBox, HealthComponent, MovementComponent,
    SkillComponent, TransformComponent,
};
use crate::entity_framework::{
    ComponentEntity, Entity, EntityBuilder, EntityFactory, EntityManager, GenericEntity,
};
use crate::entity_registry::{EntityId, EntityRegistry, EntitySystem, EntityType};
use crate::performance_monitor::PerformanceMonitor;

/// Entity priority levels for CPU resource allocation
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum EntityPriority {
    Critical = 0,
    High = 1,
    Medium = 2,
    Low = 3,
}

/// Object pool for entity components to reduce allocation overhead
pub struct EntityObjectPool {
    health_pool: VecDeque<HealthComponent>,
    transform_pool: VecDeque<TransformComponent>,
    combat_pool: VecDeque<CombatComponent>,
    movement_pool: VecDeque<MovementComponent>,
    animation_pool: VecDeque<AnimationComponent>,
    ai_pool: VecDeque<AIComponent>,
    max_pool_size: usize,
}

impl EntityObjectPool {
    pub fn new(max_pool_size: usize) -> Self {
        Self {
            health_pool: VecDeque::new(),
            transform_pool: VecDeque::new(),
            combat_pool: VecDeque::new(),
            movement_pool: VecDeque::new(),
            animation_pool: VecDeque::new(),
            ai_pool: VecDeque::new(),
            max_pool_size,
        }
    }

    pub fn get_health_component(&mut self) -> Option<HealthComponent> {
        self.health_pool.pop_front()
    }

    pub fn return_health_component(&mut self, component: HealthComponent) {
        if self.health_pool.len() < self.max_pool_size {
            self.health_pool.push_back(component);
        }
    }

    pub fn get_transform_component(&mut self) -> Option<TransformComponent> {
        self.transform_pool.pop_front()
    }

    pub fn return_transform_component(&mut self, component: TransformComponent) {
        if self.transform_pool.len() < self.max_pool_size {
            self.transform_pool.push_back(component);
        }
    }

    pub fn get_combat_component(&mut self) -> Option<CombatComponent> {
        self.combat_pool.pop_front()
    }

    pub fn return_combat_component(&mut self, component: CombatComponent) {
        if self.combat_pool.len() < self.max_pool_size {
            self.combat_pool.push_back(component);
        }
    }

    pub fn get_movement_component(&mut self) -> Option<MovementComponent> {
        self.movement_pool.pop_front()
    }

    pub fn return_movement_component(&mut self, component: MovementComponent) {
        if self.movement_pool.len() < self.max_pool_size {
            self.movement_pool.push_back(component);
        }
    }

    pub fn get_animation_component(&mut self) -> Option<AnimationComponent> {
        self.animation_pool.pop_front()
    }

    pub fn return_animation_component(&mut self, component: AnimationComponent) {
        if self.animation_pool.len() < self.max_pool_size {
            self.animation_pool.push_back(component);
        }
    }

    pub fn get_ai_component(&mut self) -> Option<AIComponent> {
        self.ai_pool.pop_front()
    }

    pub fn return_ai_component(&mut self, component: AIComponent) {
        if self.ai_pool.len() < self.max_pool_size {
            self.ai_pool.push_back(component);
        }
    }
}

/// Spatial partitioning grid for efficient collision detection
pub struct SpatialGrid {
    cell_size: f32,
    grid: HashMap<(i32, i32, i32), Vec<EntityId>>,
    entities: HashMap<EntityId, (i32, i32, i32)>,
}

impl SpatialGrid {
    pub fn new(cell_size: f32) -> Self {
        Self {
            cell_size,
            grid: HashMap::new(),
            entities: HashMap::new(),
        }
    }

    pub fn update_entity(&mut self, entity_id: EntityId, position: Vec3) {
        let new_cell = (
            (position.x / self.cell_size) as i32,
            (position.y / self.cell_size) as i32,
            (position.z / self.cell_size) as i32,
        );

        // Remove from old cell if exists
        if let Some(old_cell) = self.entities.get(&entity_id) {
            if let Some(entities) = self.grid.get_mut(old_cell) {
                entities.retain(|&id| id != entity_id);
                if entities.is_empty() {
                    self.grid.remove(old_cell);
                }
            }
        }

        // Add to new cell
        self.entities.insert(entity_id, new_cell);
        self.grid.entry(new_cell).or_default().push(entity_id);
    }

    pub fn remove_entity(&mut self, entity_id: EntityId) {
        if let Some(cell) = self.entities.remove(&entity_id) {
            if let Some(entities) = self.grid.get_mut(&cell) {
                entities.retain(|&id| id != entity_id);
                if entities.is_empty() {
                    self.grid.remove(&cell);
                }
            }
        }
    }

    pub fn get_nearby_entities(&self, position: Vec3, radius: f32) -> Vec<EntityId> {
        let center_cell = (
            (position.x / self.cell_size) as i32,
            (position.y / self.cell_size) as i32,
            (position.z / self.cell_size) as i32,
        );

        let radius_cells = (radius / self.cell_size) as i32 + 1;
        let mut nearby_entities = Vec::new();

        for dx in -radius_cells..=radius_cells {
            for dy in -radius_cells..=radius_cells {
                for dz in -radius_cells..=radius_cells {
                    let cell = (center_cell.0 + dx, center_cell.1 + dy, center_cell.2 + dz);
                    if let Some(entities) = self.grid.get(&cell) {
                        nearby_entities.extend(entities.iter().copied());
                    }
                }
            }
        }

        nearby_entities
    }

    pub fn clear(&mut self) {
        self.grid.clear();
        self.entities.clear();
    }
}

/// Entity batch for efficient bulk operations
pub struct EntityBatch {
    entities: Vec<EntityId>,
    priority: EntityPriority,
    update_type: BatchUpdateType,
}

#[derive(Debug, Clone, Copy)]
pub enum BatchUpdateType {
    Movement,
    Combat,
    Animation,
    Ai,
}

/// Main entity processing system that coordinates all ECS components
pub struct EntityModulationSystem {
    entity_registry: Arc<EntityRegistry>,
    entity_factory: Arc<EntityFactory>,
    combat_system: Arc<CombatSystem>,
    performance_monitor: Arc<PerformanceMonitor>,
    object_pool: Arc<parking_lot::Mutex<EntityObjectPool>>,
    spatial_grid: Arc<parking_lot::Mutex<SpatialGrid>>,
    entity_batches: Arc<parking_lot::Mutex<HashMap<EntityPriority, Vec<EntityBatch>>>>,
    entity_priorities: Arc<parking_lot::Mutex<HashMap<EntityId, EntityPriority>>>,
    entity_manager: Arc<parking_lot::Mutex<EntityManager>>,
    update_interval: Duration,
    last_update: Instant,
    is_initialized: bool,
    max_entities: usize,
    entity_lifetime: Duration,
}

impl EntityModulationSystem {
    /// Create a new entity modulation system
    pub fn new(max_entities: usize) -> Self {
        let performance_monitor = Arc::new(PerformanceMonitor::new());

        let entity_registry = Arc::new(EntityRegistry::new(performance_monitor.clone()));
        let entity_factory = Arc::new(EntityFactory::new(performance_monitor.clone()));

        let combat_system = Arc::new(CombatSystem::new(
            entity_registry.clone(),
            performance_monitor.clone(),
        ));

        let object_pool = Arc::new(parking_lot::Mutex::new(EntityObjectPool::new(
            max_entities / 4,
        )));
        let spatial_grid = Arc::new(parking_lot::Mutex::new(SpatialGrid::new(16.0))); // 16x16x16 cells
        let entity_batches = Arc::new(parking_lot::Mutex::new(HashMap::new()));
        let entity_priorities = Arc::new(parking_lot::Mutex::new(HashMap::new()));
        let entity_manager = Arc::new(parking_lot::Mutex::new(EntityManager::new(
            performance_monitor.clone(),
        )));

        Self {
            entity_registry,
            entity_factory,
            combat_system,
            performance_monitor,
            object_pool,
            spatial_grid,
            entity_batches,
            entity_priorities,
            entity_manager,
            update_interval: Duration::from_millis(16), // ~60 FPS
            last_update: Instant::now(),
            is_initialized: false,
            max_entities,
            entity_lifetime: Duration::from_secs(300), // 5 minutes
        }
    }

    /// Initialize the entity modulation system
    pub fn initialize(&mut self) -> Result<(), String> {
        let start_time = Instant::now();

        // Set up default resources
        self.setup_default_resources()?;

        self.is_initialized = true;

        // Record initialization metrics
        let init_time = start_time.elapsed();
        self.performance_monitor
            .record_metric("entity_modulation_init_time", init_time.as_secs_f64());

        Ok(())
    }

    /// Set up default resources (materials, shaders, meshes)
    fn setup_default_resources(&self) -> Result<(), String> {
        // This would load default textures, materials, and shader programs
        // For now, we'll use the built-in defaults

        Ok(())
    }

    /// Set entity priority for CPU resource allocation
    pub fn set_entity_priority(&self, entity_id: EntityId, priority: EntityPriority) {
        self.entity_priorities.lock().insert(entity_id, priority);
    }

    /// Get entity priority
    pub fn get_entity_priority(&self, entity_id: EntityId) -> EntityPriority {
        self.entity_priorities
            .lock()
            .get(&entity_id)
            .copied()
            .unwrap_or(EntityPriority::Medium)
    }

    /// Create a generic entity with specified components
    pub fn create_entity(
        &self,
        entity_type: &str,
        position: Vec3,
        config: EntityConfig,
    ) -> Result<EntityId, String> {
        let start_time = Instant::now();

        // Check entity limit
        let current_count = self.entity_manager.lock().count();
        if current_count >= self.max_entities {
            return Err("Maximum entity limit reached".to_string());
        }

        // Create entity in registry
        let entity_id = self
            .entity_registry
            .create_entity(EntityType::ShadowZombieNinja); // Use existing type for compatibility

        // Build entity with specified components
        let mut builder = EntityBuilder::new(entity_id, position);

        if config.has_health {
            builder = builder.with_health(config.max_health);
        }

        if config.has_movement {
            builder = builder.with_movement(config.movement_speed);
        }

        if config.has_combat {
            builder = builder.with_combat(config.attack_damage, config.attack_range);
        }

        if config.has_ai {
            builder = builder.with_ai(
                config.ai_type,
                config.detection_range,
                config.aggression_range,
            );
        }

        if config.has_animation {
            builder = builder.with_animation();
        }

        if config.has_skills {
            builder = builder.with_skills();
        }

        let entity = builder.build();

        // Add entity to manager
        self.entity_manager.lock().add_entity(entity);

        // Update spatial grid
        self.spatial_grid.lock().update_entity(entity_id, position);

        // Set default priority
        self.set_entity_priority(entity_id, EntityPriority::Medium);

        // Record creation metrics
        let creation_time = start_time.elapsed();
        self.performance_monitor
            .record_metric("entity_creation_time", creation_time.as_secs_f64());

        Ok(entity_id)
    }

    /// Create a combat-ready entity
    pub fn create_combat_entity(
        &self,
        position: Vec3,
        health: f32,
        attack_damage: f32,
        movement_speed: f32,
    ) -> Result<EntityId, String> {
        let config = EntityConfig {
            has_health: true,
            max_health: health,
            has_movement: true,
            movement_speed,
            has_combat: true,
            attack_damage,
            attack_range: 2.0,
            has_ai: true,
            ai_type: AIType::Aggressive,
            detection_range: 10.0,
            aggression_range: 5.0,
            has_animation: true,
            has_skills: false,
        };

        self.create_entity("combat_entity", position, config)
    }

    /// Create multiple entities in a batch
    pub fn create_entity_batch(
        &self,
        requests: Vec<EntityRequest>,
    ) -> Result<Vec<EntityId>, String> {
        let start_time = Instant::now();

        let entity_ids: Vec<EntityId> = requests
            .par_iter()
            .filter_map(|request| {
                self.create_entity(
                    &request.entity_type,
                    request.position,
                    request.config.clone(),
                )
                .ok()
            })
            .collect();

        // Record batch creation metrics
        let batch_time = start_time.elapsed();
        self.performance_monitor
            .record_metric("entity_batch_creation_time", batch_time.as_secs_f64());

        Ok(entity_ids)
    }

    /// Remove an entity from the system
    pub fn remove_entity(&self, entity_id: EntityId) -> Result<bool, String> {
        let start_time = Instant::now();

        // Return components to object pool
        if let Some(entity) = self.entity_manager.lock().get_entity_mut(entity_id) {
            if let Some(health) = entity.get_component::<HealthComponent>() {
                let health_component = health.clone();
                self.object_pool
                    .lock()
                    .return_health_component(health_component);
            }
            if let Some(transform) = entity.get_component::<TransformComponent>() {
                let transform_component = transform.clone();
                self.object_pool
                    .lock()
                    .return_transform_component(transform_component);
            }
            if let Some(combat) = entity.get_component::<CombatComponent>() {
                let combat_component = combat.clone();
                self.object_pool
                    .lock()
                    .return_combat_component(combat_component);
            }
            if let Some(movement) = entity.get_component::<MovementComponent>() {
                let movement_component = movement.clone();
                self.object_pool
                    .lock()
                    .return_movement_component(movement_component);
            }
            if let Some(animation) = entity.get_component::<AnimationComponent>() {
                let animation_component = animation.clone();
                self.object_pool
                    .lock()
                    .return_animation_component(animation_component);
            }
            if let Some(ai) = entity.get_component::<AIComponent>() {
                let ai_component = ai.clone();
                self.object_pool.lock().return_ai_component(ai_component);
            }
        }

        // Remove from spatial grid
        self.spatial_grid.lock().remove_entity(entity_id);

        // Remove priority
        self.entity_priorities.lock().remove(&entity_id);

        // Remove from manager and registry
        let removed_from_manager = self
            .entity_manager
            .lock()
            .remove_entity(entity_id)
            .is_some();
        let removed_from_registry = self.entity_registry.remove_entity(entity_id);

        // Record removal metrics
        let removal_time = start_time.elapsed();
        self.performance_monitor
            .record_metric("entity_removal_time", removal_time.as_secs_f64());

        Ok(removed_from_manager && removed_from_registry)
    }

    /// Update all entities in the system
    pub fn update(&mut self, delta_time: f32) -> Result<(), String> {
        let start_time = Instant::now();

        if !self.is_initialized {
            return Err("Entity modulation system not initialized".to_string());
        }

        // Check update interval
        let now = Instant::now();
        if now.duration_since(self.last_update) < self.update_interval {
            return Ok(());
        }

        // Update combat system
        self.combat_system.update(delta_time)?;

        // Update entity manager (all entities)
        self.entity_manager.lock().update_all(delta_time);

        // Update spatial grid for all entities
        self.update_spatial_grid()?;

        // Clean up expired entities
        self.cleanup_expired_entities()?;

        self.last_update = now;

        // Record update metrics
        let update_time = start_time.elapsed();
        self.performance_monitor
            .record_metric("entity_modulation_update_time", update_time.as_secs_f64());

        Ok(())
    }

    /// Update spatial grid with current entity positions
    fn update_spatial_grid(&self) -> Result<(), String> {
        let manager = self.entity_manager.lock();
        let mut grid = self.spatial_grid.lock();

        // Clear and rebuild spatial grid
        grid.clear();

        // Get all entities and update their positions in the grid
        for (entity_id, entity) in manager.entities.iter() {
            grid.update_entity(*entity_id, entity.get_position());
        }

        Ok(())
    }

    /// Clean up entities that have exceeded their lifetime
    fn cleanup_expired_entities(&self) -> Result<(), String> {
        let now = Instant::now();
        let mut manager = self.entity_manager.lock();

        let mut expired_entities = Vec::new();

        // Check each entity's lifetime
        for entity in manager.get_all_entities() {
            let entity_id = entity.get_id();
            // Simple lifetime check - in a real implementation, you'd track creation time per entity
            // For now, we'll use a simple heuristic
            if !entity.is_active() {
                expired_entities.push(entity_id);
            }
        }

        // Remove expired entities
        for entity_id in expired_entities {
            self.remove_entity(entity_id)?;
        }

        Ok(())
    }

    /// Process combat between two entities
    pub fn process_combat(
        &self,
        attacker_id: EntityId,
        target_id: EntityId,
        attack_position: Vec3,
    ) -> Result<CombatEvent, String> {
        self.combat_system
            .process_attack(attacker_id, target_id, attack_position)
    }

    /// Process area-of-effect combat
    pub fn process_aoe_combat(
        &self,
        center: Vec3,
        radius: f32,
        damage: f32,
        exclude_entity: Option<EntityId>,
    ) -> Result<Vec<CombatEvent>, String> {
        self.combat_system.process_aoe_damage(
            center,
            radius,
            damage,
            crate::combat_system::DamageType::Physical,
            exclude_entity,
        )
    }

    /// Get system statistics
    pub fn get_statistics(&self) -> EntityModulationStats {
        let registry_stats = self.entity_registry.get_statistics();
        let manager_count = self.entity_manager.lock().count();

        EntityModulationStats {
            total_entities: registry_stats.total_entities,
            active_entities: manager_count,
            is_initialized: self.is_initialized,
        }
    }

    /// Get performance metrics
    pub fn get_performance_metrics(&self) -> Vec<(String, f64)> {
        self.performance_monitor.get_all_cpu_metrics()
    }

    /// Set maximum number of entities
    pub fn set_max_entities(&mut self, max_entities: usize) {
        self.max_entities = max_entities;
    }

    /// Set entity lifetime
    pub fn set_entity_lifetime(&mut self, lifetime: Duration) {
        self.entity_lifetime = lifetime;
    }

    /// Set update interval
    pub fn set_update_interval(&mut self, interval: Duration) {
        self.update_interval = interval;
    }

    /// Shutdown the system and clean up resources
    pub fn shutdown(&mut self) -> Result<(), String> {
        // Clean up all entities
        let entities: Vec<EntityId> = self
            .entity_manager
            .lock()
            .entities
            .keys()
            .cloned()
            .collect();
        for entity_id in entities {
            self.remove_entity(entity_id)?;
        }

        self.is_initialized = false;

        Ok(())
    }
}

/// Configuration for entity creation
#[derive(Debug, Clone)]
pub struct EntityConfig {
    pub has_health: bool,
    pub max_health: f32,
    pub has_movement: bool,
    pub movement_speed: f32,
    pub has_combat: bool,
    pub attack_damage: f32,
    pub attack_range: f32,
    pub has_ai: bool,
    pub ai_type: AIType,
    pub detection_range: f32,
    pub aggression_range: f32,
    pub has_animation: bool,
    pub has_skills: bool,
}

impl Default for EntityConfig {
    fn default() -> Self {
        Self {
            has_health: true,
            max_health: 100.0,
            has_movement: true,
            movement_speed: 1.0,
            has_combat: false,
            attack_damage: 10.0,
            attack_range: 2.0,
            has_ai: false,
            ai_type: AIType::Passive,
            detection_range: 5.0,
            aggression_range: 2.0,
            has_animation: false,
            has_skills: false,
        }
    }
}

/// Request for creating an entity
#[derive(Debug, Clone)]
pub struct EntityRequest {
    pub entity_type: String,
    pub position: Vec3,
    pub config: EntityConfig,
}

/// Statistics for the entity modulation system
#[derive(Debug, Clone)]
pub struct EntityModulationStats {
    pub total_entities: usize,
    pub active_entities: usize,
    pub is_initialized: bool,
}

/// Factory for creating entity modulation systems
pub struct EntityModulationFactory {
    performance_monitor: Arc<PerformanceMonitor>,
}

impl EntityModulationFactory {
    pub fn new(performance_monitor: Arc<PerformanceMonitor>) -> Self {
        Self {
            performance_monitor,
        }
    }

    pub fn create_system(&self, max_entities: usize) -> EntityModulationSystem {
        EntityModulationSystem::new(max_entities)
    }

    pub fn create_system_with_config(
        &self,
        max_entities: usize,
        update_interval: Duration,
        entity_lifetime: Duration,
    ) -> EntityModulationSystem {
        let mut system = EntityModulationSystem::new(max_entities);
        system.set_update_interval(update_interval);
        system.set_entity_lifetime(entity_lifetime);
        system
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_entity_modulation_system_creation() {
        let system = EntityModulationSystem::new(100);

        assert!(!system.is_initialized);
        assert_eq!(system.max_entities, 100);
    }

    #[test]
    fn test_entity_creation() {
        let mut system = EntityModulationSystem::new(10);
        system.initialize().unwrap();

        let position = Vec3::new(0.0, 0.0, 0.0);
        let config = EntityConfig::default();
        let entity_id = system
            .create_entity("test_entity", position, config)
            .unwrap();

        assert!(system.entity_registry.entity_exists(entity_id));

        let stats = system.get_statistics();
        assert_eq!(stats.active_entities, 1);
    }

    #[test]
    fn test_combat_entity_creation() {
        let mut system = EntityModulationSystem::new(10);
        system.initialize().unwrap();

        let position = Vec3::new(0.0, 0.0, 0.0);
        let entity_id = system
            .create_combat_entity(position, 150.0, 20.0, 5.0)
            .unwrap();

        assert!(system.entity_registry.entity_exists(entity_id));

        let stats = system.get_statistics();
        assert_eq!(stats.active_entities, 1);
    }

    #[test]
    fn test_entity_removal() {
        let mut system = EntityModulationSystem::new(10);
        system.initialize().unwrap();

        let position = Vec3::new(1.0, 0.0, 1.0);
        let config = EntityConfig::default();
        let entity_id = system
            .create_entity("test_entity", position, config)
            .unwrap();

        assert!(system.remove_entity(entity_id).unwrap());
        assert!(!system.entity_registry.entity_exists(entity_id));
    }

    #[test]
    fn test_max_entity_limit() {
        let mut system = EntityModulationSystem::new(2);
        system.initialize().unwrap();

        // Spawn first two entities
        let position1 = Vec3::new(0.0, 0.0, 0.0);
        let position2 = Vec3::new(1.0, 0.0, 0.0);
        let config = EntityConfig::default();

        assert!(system
            .create_entity("test1", position1, config.clone())
            .is_ok());
        assert!(system
            .create_entity("test2", position2, config.clone())
            .is_ok());

        // Third entity should fail
        let position3 = Vec3::new(2.0, 0.0, 0.0);
        assert!(system.create_entity("test3", position3, config).is_err());
    }

    #[test]
    fn test_batch_creation() {
        let mut system = EntityModulationSystem::new(10);
        system.initialize().unwrap();

        let requests = vec![
            EntityRequest {
                entity_type: "combat_entity".to_string(),
                position: Vec3::new(0.0, 0.0, 0.0),
                config: EntityConfig::default(),
            },
            EntityRequest {
                entity_type: "combat_entity".to_string(),
                position: Vec3::new(1.0, 0.0, 0.0),
                config: EntityConfig::default(),
            },
            EntityRequest {
                entity_type: "combat_entity".to_string(),
                position: Vec3::new(2.0, 0.0, 0.0),
                config: EntityConfig::default(),
            },
        ];

        let entity_ids = system.create_entity_batch(requests).unwrap();
        assert_eq!(entity_ids.len(), 3);

        let stats = system.get_statistics();
        assert_eq!(stats.active_entities, 3);
    }
}
