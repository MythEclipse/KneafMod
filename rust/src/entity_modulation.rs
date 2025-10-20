//! Entity Modulation System - Main ECS Integration Module
//! 
//! This module provides the main integration point for the Entity Processing System,
//! combining all ECS components into a cohesive system with performance monitoring
//! and native GPU acceleration.

use std::sync::Arc;
use std::time::{Duration, Instant};
use std::collections::{HashMap, VecDeque};
use glam::Vec3;
use rayon::prelude::*;

use crate::entity_registry::{EntityRegistry, EntityId, EntityType, EntitySystem};
use crate::shadow_zombie_ninja::ShadowZombieNinjaFactory;
use crate::entity_renderer::{EntityRenderer, EntityRendererFactory};
use crate::combat_system::{CombatSystem, CombatEvent};
use crate::performance_monitor::PerformanceMonitor;
use crate::zero_copy_buffer::ZeroCopyBufferPool;

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
    health_pool: VecDeque<crate::shadow_zombie_ninja::HealthComponent>,
    transform_pool: VecDeque<crate::shadow_zombie_ninja::TransformComponent>,
    combat_pool: VecDeque<crate::combat_system::CombatComponent>,
    movement_pool: VecDeque<crate::shadow_zombie_ninja::MovementComponent>,
    animation_pool: VecDeque<crate::shadow_zombie_ninja::AnimationComponent>,
    ai_pool: VecDeque<crate::shadow_zombie_ninja::AIComponent>,
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

    pub fn get_health_component(&mut self) -> Option<crate::shadow_zombie_ninja::HealthComponent> {
        self.health_pool.pop_front()
    }

    pub fn return_health_component(&mut self, component: crate::shadow_zombie_ninja::HealthComponent) {
        if self.health_pool.len() < self.max_pool_size {
            self.health_pool.push_back(component);
        }
    }

    pub fn get_transform_component(&mut self) -> Option<crate::shadow_zombie_ninja::TransformComponent> {
        self.transform_pool.pop_front()
    }

    pub fn return_transform_component(&mut self, component: crate::shadow_zombie_ninja::TransformComponent) {
        if self.transform_pool.len() < self.max_pool_size {
            self.transform_pool.push_back(component);
        }
    }

    pub fn get_combat_component(&mut self) -> Option<crate::combat_system::CombatComponent> {
        self.combat_pool.pop_front()
    }

    pub fn return_combat_component(&mut self, component: crate::combat_system::CombatComponent) {
        if self.combat_pool.len() < self.max_pool_size {
            self.combat_pool.push_back(component);
        }
    }

    pub fn get_movement_component(&mut self) -> Option<crate::shadow_zombie_ninja::MovementComponent> {
        self.movement_pool.pop_front()
    }

    pub fn return_movement_component(&mut self, component: crate::shadow_zombie_ninja::MovementComponent) {
        if self.movement_pool.len() < self.max_pool_size {
            self.movement_pool.push_back(component);
        }
    }

    pub fn get_animation_component(&mut self) -> Option<crate::shadow_zombie_ninja::AnimationComponent> {
        self.animation_pool.pop_front()
    }

    pub fn return_animation_component(&mut self, component: crate::shadow_zombie_ninja::AnimationComponent) {
        if self.animation_pool.len() < self.max_pool_size {
            self.animation_pool.push_back(component);
        }
    }

    pub fn get_ai_component(&mut self) -> Option<crate::shadow_zombie_ninja::AIComponent> {
        self.ai_pool.pop_front()
    }

    pub fn return_ai_component(&mut self, component: crate::shadow_zombie_ninja::AIComponent) {
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
        self.grid.entry(new_cell).or_insert_with(Vec::new).push(entity_id);
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
    ninja_factory: Arc<ShadowZombieNinjaFactory>,
    entity_renderer: Arc<EntityRenderer>,
    combat_system: Arc<CombatSystem>,
    performance_monitor: Arc<PerformanceMonitor>,
    zero_copy_buffer: Arc<ZeroCopyBufferPool>,
    object_pool: Arc<parking_lot::Mutex<EntityObjectPool>>,
    spatial_grid: Arc<parking_lot::Mutex<SpatialGrid>>,
    entity_batches: Arc<parking_lot::Mutex<HashMap<EntityPriority, Vec<EntityBatch>>>>,
    entity_priorities: Arc<parking_lot::Mutex<HashMap<EntityId, EntityPriority>>>,
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
        let zero_copy_buffer = Arc::new(ZeroCopyBufferPool::new(1024 * 1024, 10));
        
        let entity_registry = Arc::new(EntityRegistry::new(performance_monitor.clone()));
        let ninja_factory = Arc::new(ShadowZombieNinjaFactory::new(performance_monitor.clone()));
        
        let entity_renderer_factory = EntityRendererFactory::new(
            performance_monitor.clone(),
            zero_copy_buffer.clone(),
        );
        let entity_renderer = Arc::new(entity_renderer_factory.create_renderer(entity_registry.clone()));
        
        let combat_system = Arc::new(CombatSystem::new(
            entity_registry.clone(),
            performance_monitor.clone(),
        ));
        
        let object_pool = Arc::new(parking_lot::Mutex::new(EntityObjectPool::new(max_entities / 4)));
        let spatial_grid = Arc::new(parking_lot::Mutex::new(SpatialGrid::new(16.0))); // 16x16x16 cells
        let entity_batches = Arc::new(parking_lot::Mutex::new(HashMap::new()));
        let entity_priorities = Arc::new(parking_lot::Mutex::new(HashMap::new()));
        
        Self {
            entity_registry,
            ninja_factory,
            entity_renderer,
            combat_system,
            performance_monitor,
            zero_copy_buffer,
            object_pool,
            spatial_grid,
            entity_batches,
            entity_priorities,
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
        
        // Initialize entity renderer
        self.entity_renderer.initialize()?;
        
        // Set up default materials and shaders
        self.setup_default_resources()?;
        
        self.is_initialized = true;
        
        // Record initialization metrics
        let init_time = start_time.elapsed();
        self.performance_monitor.record_cpu_usage(
            "entity_modulation_init_time",
            init_time.as_secs_f64(),
        );
        
        Ok(())
    }

    /// Set up default resources (materials, shaders, meshes)
    fn setup_default_resources(&self) -> Result<(), String> {
        // This would load default textures, materials, and shader programs
        // For now, we'll use the built-in defaults from the renderer
        
        Ok(())
    }

    /// Set entity priority for CPU resource allocation
    pub fn set_entity_priority(&self, entity_id: EntityId, priority: EntityPriority) {
        self.entity_priorities.lock().insert(entity_id, priority);
    }

    /// Get entity priority
    pub fn get_entity_priority(&self, entity_id: EntityId) -> EntityPriority {
        self.entity_priorities.lock().get(&entity_id).copied().unwrap_or(EntityPriority::Medium)
    }

    /// Spawn a ShadowZombieNinja entity at the specified position
    pub fn spawn_shadow_zombie_ninja(&self, position: Vec3) -> Result<EntityId, String> {
        let start_time = Instant::now();
        
        // Check entity limit
        let current_count = self.entity_registry.get_entity_count(Some(EntityType::ShadowZombieNinja));
        if current_count >= self.max_entities {
            return Err("Maximum entity limit reached".to_string());
        }
        
        // Create entity in registry
        let entity_id = self.entity_registry.create_entity(EntityType::ShadowZombieNinja);
        
        // Get components from object pool or create new ones
        let mut pool = self.object_pool.lock();
        let health = pool.get_health_component()
            .unwrap_or_else(|| crate::shadow_zombie_ninja::HealthComponent::new(100.0));
        let transform = pool.get_transform_component()
            .unwrap_or_else(|| crate::shadow_zombie_ninja::TransformComponent::new(position));
        let combat = pool.get_combat_component()
            .unwrap_or_else(|| crate::combat_system::CombatComponent::new());
        let movement = pool.get_movement_component()
            .unwrap_or_else(|| crate::shadow_zombie_ninja::MovementComponent::new(1.0)); // Default movement speed
        let animation = pool.get_animation_component()
            .unwrap_or_else(|| crate::shadow_zombie_ninja::AnimationComponent::new());
        let ai = pool.get_ai_component()
            .unwrap_or_else(|| crate::shadow_zombie_ninja::AIComponent::new(
                crate::shadow_zombie_ninja::AIType::Aggressive, 5.0, 10.0
            ));
        
        // Create ShadowZombieNinja instance with pooled components
        let ninja = self.ninja_factory.create_entity_with_stats(
            entity_id,
            position,
            100.0, // health
            10.0,  // damage
            1.0,   // movement speed
        );
        
        // Add components to entity
        self.entity_registry.add_component(entity_id, ninja.health.clone());
        self.entity_registry.add_component(entity_id, ninja.transform.clone());
        self.entity_registry.add_component(entity_id, ninja.combat.clone());
        self.entity_registry.add_component(entity_id, ninja.movement.clone());
        self.entity_registry.add_component(entity_id, ninja.animation.clone());
        self.entity_registry.add_component(entity_id, ninja.ai.clone());
        
        // Update spatial grid
        self.spatial_grid.lock().update_entity(entity_id, position);
        
        // Set default priority
        self.set_entity_priority(entity_id, EntityPriority::Medium);
        
        // Record spawn metrics
        let spawn_time = start_time.elapsed();
        self.performance_monitor.record_cpu_usage(
            "shadow_zombie_ninja_spawn_time",
            spawn_time.as_secs_f64(),
        );
        
        Ok(entity_id)
    }

    /// Spawn multiple ShadowZombieNinja entities in a batch
    pub fn spawn_shadow_zombie_ninja_batch(&self, positions: Vec<Vec3>) -> Result<Vec<EntityId>, String> {
        let start_time = Instant::now();
        
        let entity_ids: Vec<EntityId> = positions
            .par_iter()
            .filter_map(|&position| {
                match self.spawn_shadow_zombie_ninja(position) {
                    Ok(entity_id) => Some(entity_id),
                    Err(_) => None,
                }
            })
            .collect();
        
        // Record batch spawn metrics
        let batch_time = start_time.elapsed();
        self.performance_monitor.record_cpu_usage(
            "shadow_zombie_ninja_batch_spawn_time",
            batch_time.as_secs_f64(),
        );
        
        Ok(entity_ids)
    }

    /// Remove an entity from the system
    pub fn remove_entity(&self, entity_id: EntityId) -> Result<bool, String> {
        let start_time = Instant::now();
        
        // Return components to object pool
        if let Some(health) = self.entity_registry.get_component::<crate::shadow_zombie_ninja::HealthComponent>(entity_id) {
            // Clone the Arc and return the inner component to pool
            let health_component = (*health).clone();
            self.object_pool.lock().return_health_component(health_component);
        }
        if let Some(transform) = self.entity_registry.get_component::<crate::shadow_zombie_ninja::TransformComponent>(entity_id) {
            let transform_component = (*transform).clone();
            self.object_pool.lock().return_transform_component(transform_component);
        }
        if let Some(combat) = self.entity_registry.get_component::<crate::combat_system::CombatComponent>(entity_id) {
            let combat_component = (*combat).clone();
            self.object_pool.lock().return_combat_component(combat_component);
        }
        if let Some(movement) = self.entity_registry.get_component::<crate::shadow_zombie_ninja::MovementComponent>(entity_id) {
            let movement_component = (*movement).clone();
            self.object_pool.lock().return_movement_component(movement_component);
        }
        if let Some(animation) = self.entity_registry.get_component::<crate::shadow_zombie_ninja::AnimationComponent>(entity_id) {
            let animation_component = (*animation).clone();
            self.object_pool.lock().return_animation_component(animation_component);
        }
        if let Some(ai) = self.entity_registry.get_component::<crate::shadow_zombie_ninja::AIComponent>(entity_id) {
            let ai_component = (*ai).clone();
            self.object_pool.lock().return_ai_component(ai_component);
        }
        
        // Remove from spatial grid
        self.spatial_grid.lock().remove_entity(entity_id);
        
        // Remove priority
        self.entity_priorities.lock().remove(&entity_id);
        
        let removed = self.entity_registry.remove_entity(entity_id);
        
        // Record removal metrics
        let removal_time = start_time.elapsed();
        self.performance_monitor.record_cpu_usage(
            "entity_removal_time",
            removal_time.as_secs_f64(),
        );
        
        Ok(removed)
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
        
        // Update entity registry
        self.update_entities(delta_time)?;
        
        // Clean up expired entities
        self.cleanup_expired_entities()?;
        
        // Update renderer
        self.entity_renderer.render(delta_time)?;
        
        self.last_update = now;
        
        // Record update metrics
        let update_time = start_time.elapsed();
        self.performance_monitor.record_cpu_usage(
            "entity_modulation_update_time",
            update_time.as_secs_f64(),
        );
        
        Ok(())
    }

    /// Update individual entities with priority-based batching
    fn update_entities(&self, delta_time: f32) -> Result<(), String> {
        let entities = self.entity_registry.get_entities_by_type(EntityType::ShadowZombieNinja);
        
        // Group entities by priority
        let mut priority_groups: HashMap<EntityPriority, Vec<EntityId>> = HashMap::new();
        let priorities = self.entity_priorities.lock();
        
        for &entity_id in &entities {
            let priority = priorities.get(&entity_id).copied().unwrap_or(EntityPriority::Medium);
            priority_groups.entry(priority).or_insert_with(Vec::new).push(entity_id);
        }
        
        // Process entities by priority order (Critical first, Low last)
        let mut batches = Vec::new();
        for priority in [EntityPriority::Critical, EntityPriority::High, EntityPriority::Medium, EntityPriority::Low] {
            if let Some(entity_ids) = priority_groups.get(&priority) {
                // Create batches of 32 entities for efficient processing
                for chunk in entity_ids.chunks(32) {
                    batches.push(EntityBatch {
                        entities: chunk.to_vec(),
                        priority,
                        update_type: BatchUpdateType::Movement,
                    });
                }
            }
        }
        
        // Process batches in parallel
        batches.par_iter().for_each(|batch| {
            self.process_entity_batch(batch, delta_time);
        });
        
        Ok(())
    }

    /// Process a batch of entities efficiently
    fn process_entity_batch(&self, batch: &EntityBatch, delta_time: f32) {
        match batch.update_type {
            BatchUpdateType::Movement => {
                for &entity_id in &batch.entities {
                    if let Some(transform) = self.entity_registry.get_component::<crate::shadow_zombie_ninja::TransformComponent>(entity_id) {
                        // Update position in spatial grid
                        self.spatial_grid.lock().update_entity(entity_id, transform.position);
                    }
                }
            }
            BatchUpdateType::Combat => {
                // Process combat interactions using spatial grid
                let spatial_grid = self.spatial_grid.lock();
                for &entity_id in &batch.entities {
                    if let Some(transform) = self.entity_registry.get_component::<crate::shadow_zombie_ninja::TransformComponent>(entity_id) {
                        let nearby_entities = spatial_grid.get_nearby_entities(transform.position, 10.0);
                        // Process combat with nearby entities
                        for &target_id in &nearby_entities {
                            if target_id != entity_id {
                                // Combat processing logic here
                            }
                        }
                    }
                }
            }
            BatchUpdateType::Animation => {
                // Batch animation updates
                for &entity_id in &batch.entities {
                    if let Some(animation) = self.entity_registry.get_component::<crate::shadow_zombie_ninja::AnimationComponent>(entity_id) {
                        // Update animation state
                    }
                }
            }
            BatchUpdateType::Ai => {
                // Batch AI updates
                for &entity_id in &batch.entities {
                    if let Some(ai) = self.entity_registry.get_component::<crate::shadow_zombie_ninja::AIComponent>(entity_id) {
                        // Update AI state
                    }
                }
            }
        }
    }

    /// Clean up entities that have exceeded their lifetime
    fn cleanup_expired_entities(&self) -> Result<(), String> {
        let entities = self.entity_registry.get_entities_by_type(EntityType::ShadowZombieNinja);
        let now = Instant::now();
        
        for entity_id in entities {
            if let Some(entity) = self.entity_registry.get_entity(entity_id) {
                if now.duration_since(entity.created_at) > self.entity_lifetime {
                    self.entity_registry.remove_entity(entity_id);
                }
            }
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
        self.combat_system.process_attack(attacker_id, target_id, attack_position)
    }

    /// Process area-of-effect combat
    pub fn process_aoe_combat(
        &self,
        center: Vec3,
        radius: f32,
        damage: f32,
        exclude_entity: Option<EntityId>,
    ) -> Result<Vec<CombatEvent>, String> {
        self.combat_system.process_aoe_damage(center, radius, damage, crate::combat_system::DamageType::Physical, exclude_entity)
    }

    /// Set camera position for rendering
    pub fn set_camera_position(&self, position: Vec3, target: Vec3, up: Vec3) {
        self.entity_renderer.update_camera(position, target, up);
    }

    /// Get system statistics
    pub fn get_statistics(&self) -> EntityModulationStats {
        let registry_stats = self.entity_registry.get_statistics();
        let render_stats = self.entity_renderer.get_render_stats();
        
        EntityModulationStats {
            total_entities: registry_stats.total_entities,
            active_entities: registry_stats.active_entities,
            shadow_zombie_ninjas: *registry_stats.entities_by_type.get(&EntityType::ShadowZombieNinja).unwrap_or(&0),
            entities_rendered: render_stats.entities_rendered,
            triangles_rendered: render_stats.triangles_rendered,
            draw_calls: render_stats.draw_calls,
            render_time_ms: render_stats.render_time_ms,
            total_frame_time_ms: render_stats.total_frame_time_ms,
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
        let entities = self.entity_registry.get_entities_by_type(EntityType::ShadowZombieNinja);
        for entity_id in entities {
            self.entity_registry.remove_entity(entity_id);
        }
        
        self.is_initialized = false;
        
        Ok(())
    }
}

/// Statistics for the entity modulation system
#[derive(Debug, Clone)]
pub struct EntityModulationStats {
    pub total_entities: usize,
    pub active_entities: usize,
    pub shadow_zombie_ninjas: usize,
    pub entities_rendered: u32,
    pub triangles_rendered: u32,
    pub draw_calls: u32,
    pub render_time_ms: f32,
    pub total_frame_time_ms: f32,
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
    fn test_entity_spawn() {
        let mut system = EntityModulationSystem::new(10);
        system.initialize().unwrap();
        
        let position = Vec3::new(0.0, 0.0, 0.0);
        let entity_id = system.spawn_shadow_zombie_ninja(position).unwrap();
        
        assert!(system.entity_registry.entity_exists(entity_id));
        
        let stats = system.get_statistics();
        assert_eq!(stats.shadow_zombie_ninjas, 1);
    }

    #[test]
    fn test_entity_removal() {
        let mut system = EntityModulationSystem::new(10);
        system.initialize().unwrap();
        
        let position = Vec3::new(1.0, 0.0, 1.0);
        let entity_id = system.spawn_shadow_zombie_ninja(position).unwrap();
        
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
        
        assert!(system.spawn_shadow_zombie_ninja(position1).is_ok());
        assert!(system.spawn_shadow_zombie_ninja(position2).is_ok());
        
        // Third entity should fail
        let position3 = Vec3::new(2.0, 0.0, 0.0);
        assert!(system.spawn_shadow_zombie_ninja(position3).is_err());
    }

    #[test]
    fn test_batch_spawn() {
        let mut system = EntityModulationSystem::new(10);
        system.initialize().unwrap();
        
        let positions = vec![
            Vec3::new(0.0, 0.0, 0.0),
            Vec3::new(1.0, 0.0, 0.0),
            Vec3::new(2.0, 0.0, 0.0),
        ];
        
        let entity_ids = system.spawn_shadow_zombie_ninja_batch(positions).unwrap();
        assert_eq!(entity_ids.len(), 3);
        
        let stats = system.get_statistics();
        assert_eq!(stats.shadow_zombie_ninjas, 3);
    }
}