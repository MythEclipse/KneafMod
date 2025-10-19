//! Entity Modulation System - Main ECS Integration Module
//! 
//! This module provides the main integration point for the Entity Processing System,
//! combining all ECS components into a cohesive system with performance monitoring
//! and native GPU acceleration.

use std::sync::Arc;
use std::time::{Duration, Instant};
use glam::Vec3;
use rayon::prelude::*;

use crate::entity_registry::{EntityRegistry, EntityId, EntityType, EntitySystem};
use crate::shadow_zombie_ninja::ShadowZombieNinjaFactory;
use crate::entity_renderer::{EntityRenderer, EntityRendererFactory};
use crate::combat_system::{CombatSystem, CombatEvent};
use crate::performance_monitor::PerformanceMonitor;
use crate::zero_copy_buffer::ZeroCopyBufferPool;

/// Main entity processing system that coordinates all ECS components
pub struct EntityModulationSystem {
    entity_registry: Arc<EntityRegistry>,
    ninja_factory: Arc<ShadowZombieNinjaFactory>,
    entity_renderer: Arc<EntityRenderer>,
    combat_system: Arc<CombatSystem>,
    performance_monitor: Arc<PerformanceMonitor>,
    zero_copy_buffer: Arc<ZeroCopyBufferPool>,
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
        
        Self {
            entity_registry,
            ninja_factory,
            entity_renderer,
            combat_system,
            performance_monitor,
            zero_copy_buffer,
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
        
        // Create ShadowZombieNinja instance
        let ninja = self.ninja_factory.create_entity(entity_id, position);
        
        // Add components to entity
        self.entity_registry.add_component(entity_id, ninja.health.clone());
        self.entity_registry.add_component(entity_id, ninja.transform.clone());
        self.entity_registry.add_component(entity_id, ninja.combat.clone());
        self.entity_registry.add_component(entity_id, ninja.movement.clone());
        self.entity_registry.add_component(entity_id, ninja.animation.clone());
        self.entity_registry.add_component(entity_id, ninja.ai.clone());
        
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

    /// Update individual entities
    fn update_entities(&self, delta_time: f32) -> Result<(), String> {
        let entities = self.entity_registry.get_entities_by_type(EntityType::ShadowZombieNinja);
        
        // Process entities in parallel
        entities.par_iter().for_each(|&entity_id| {
            // Get entity components
            if let Some(health) = self.entity_registry.get_component::<crate::shadow_zombie_ninja::HealthComponent>(entity_id) {
                if let Some(transform) = self.entity_registry.get_component::<crate::shadow_zombie_ninja::TransformComponent>(entity_id) {
                    if let Some(combat) = self.entity_registry.get_component::<crate::combat_system::CombatComponent>(entity_id) {
                        if let Some(movement) = self.entity_registry.get_component::<crate::shadow_zombie_ninja::MovementComponent>(entity_id) {
                            if let Some(animation) = self.entity_registry.get_component::<crate::shadow_zombie_ninja::AnimationComponent>(entity_id) {
                                if let Some(ai) = self.entity_registry.get_component::<crate::shadow_zombie_ninja::AIComponent>(entity_id) {
                                    // Update entity components
                                    // This would update health, transform, combat, etc.
                                    // For now, animation updates are handled in the main entity update loop
                                    // TODO: Implement proper component updating through the registry
                                }
                            }
                        }
                    }
                }
            }
        });
        
        Ok(())
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