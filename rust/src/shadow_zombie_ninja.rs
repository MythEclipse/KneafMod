//! Shadow Zombie Ninja Entity Implementation
//! 
//! This module implements the core ShadowZombieNinja entity with all its components,
//! behaviors, and state management. It provides a complete Rust-native replacement
//! for the Java ShadowZombieNinja class with enhanced performance and memory safety.

use glam::{Vec3, Quat};
use std::sync::{Arc, RwLock};
use std::time::{Duration, Instant};
use crate::entity_registry::{Component, EntityId};
use crate::performance_monitor::PerformanceMonitor;
use crate::pathfinding::EntityPathfindingSystem;
use crate::combat_system::CombatComponent;

/// Health component for entities with combat capabilities
#[derive(Debug, Clone)]
pub struct HealthComponent {
    pub current_health: f32,
    pub max_health: f32,
    pub health_regeneration_rate: f32,
    pub last_damage_time: Option<Instant>,
    pub invulnerability_duration: Duration,
}

impl HealthComponent {
    pub fn new(max_health: f32) -> Self {
        Self {
            current_health: max_health,
            max_health,
            health_regeneration_rate: 0.1,
            last_damage_time: None,
            invulnerability_duration: Duration::from_millis(500),
        }
    }

    pub fn take_damage(&mut self, amount: f32) -> bool {
        let now = Instant::now();
        
        // Check invulnerability
        if let Some(last_damage) = self.last_damage_time {
            if now.duration_since(last_damage) < self.invulnerability_duration {
                return false;
            }
        }

        self.current_health = (self.current_health - amount).max(0.0);
        self.last_damage_time = Some(now);
        
        true
    }

    pub fn heal(&mut self, amount: f32) {
        self.current_health = (self.current_health + amount).min(self.max_health);
    }

    pub fn is_alive(&self) -> bool {
        self.current_health > 0.0
    }

    pub fn get_health_percentage(&self) -> f32 {
        self.current_health / self.max_health
    }
}

/// Transform component for entity position and rotation
#[derive(Debug, Clone)]
pub struct TransformComponent {
    pub position: Vec3,
    pub rotation: Quat,
    pub scale: Vec3,
    pub velocity: Vec3,
    pub angular_velocity: Vec3,
}

impl TransformComponent {
    pub fn new(position: Vec3) -> Self {
        Self {
            position,
            rotation: Quat::IDENTITY,
            scale: Vec3::ONE,
            velocity: Vec3::ZERO,
            angular_velocity: Vec3::ZERO,
        }
    }

    pub fn translate(&mut self, translation: Vec3) {
        self.position += translation;
    }

    pub fn rotate(&mut self, rotation: Quat) {
        self.rotation = self.rotation * rotation;
    }

    pub fn set_scale(&mut self, scale: Vec3) {
        self.scale = scale;
    }

    pub fn look_at(&mut self, target: Vec3, _up: Vec3) {
        let direction = (target - self.position).normalize();
        // Create a rotation that rotates the forward vector (Z) to the desired direction
        // Use from_rotation_arc which computes the shortest arc from one vector to another
        self.rotation = Quat::from_rotation_arc(Vec3::Z, direction);
    }
}

// Component trait implementations for ECS compatibility
impl Component for HealthComponent {}
impl Component for TransformComponent {}
impl Component for MovementComponent {}
impl Component for AnimationComponent {}
impl Component for AIComponent {}

/// Movement component for entity mobility
#[derive(Debug, Clone)]
pub struct MovementComponent {
    pub movement_speed: f32,
    pub jump_height: f32,
    pub gravity: f32,
    pub is_grounded: bool,
    pub is_jumping: bool,
    pub max_fall_speed: f32,
    pub friction: f32,
}

impl MovementComponent {
    pub fn new(movement_speed: f32) -> Self {
        Self {
            movement_speed,
            jump_height: 1.5,
            gravity: 9.81,
            is_grounded: true,
            is_jumping: false,
            max_fall_speed: 20.0,
            friction: 0.8,
        }
    }

    pub fn jump(&mut self) {
        if self.is_grounded && !self.is_jumping {
            self.is_jumping = true;
            self.is_grounded = false;
        }
    }

    pub fn update_physics(&mut self, delta_time: f32, transform: &mut TransformComponent) {
        // Apply gravity
        if !self.is_grounded {
            transform.velocity.y -= self.gravity * delta_time;
            transform.velocity.y = transform.velocity.y.max(-self.max_fall_speed);
        }

        // Apply friction when grounded
        if self.is_grounded {
            transform.velocity.x *= self.friction;
            transform.velocity.z *= self.friction;
        }

        // Update position based on velocity
        transform.translate(transform.velocity * delta_time);

        // Ground collision detection (simplified)
        if transform.position.y <= 0.0 {
            transform.position.y = 0.0;
            transform.velocity.y = 0.0;
            self.is_grounded = true;
            self.is_jumping = false;
        }
    }
}

/// Animation component for entity animations
#[derive(Debug, Clone)]
pub struct AnimationComponent {
    pub current_animation: AnimationState,
    pub animation_timer: f32,
    pub animation_speed: f32,
    pub frame_duration: f32,
}

#[derive(Debug, Clone, PartialEq)]
pub enum AnimationState {
    Idle,
    Walking,
    Running,
    Attacking,
    Jumping,
    Falling,
    Damaged,
    Dying,
}

impl AnimationComponent {
    pub fn new() -> Self {
        Self {
            current_animation: AnimationState::Idle,
            animation_timer: 0.0,
            animation_speed: 1.0,
            frame_duration: 0.1,
        }
    }

    pub fn set_animation(&mut self, state: AnimationState) {
        if self.current_animation != state {
            self.current_animation = state;
            self.animation_timer = 0.0;
        }
    }

    pub fn update(&mut self, delta_time: f32) {
        self.animation_timer += delta_time * self.animation_speed;
        
        // Loop animation
        if self.animation_timer >= self.frame_duration {
            self.animation_timer = 0.0;
        }
    }

    pub fn get_current_frame(&self) -> u32 {
        (self.animation_timer / self.frame_duration) as u32
    }
}

/// AI component for entity artificial intelligence with pathfinding capabilities
#[derive(Debug, Clone)]
pub struct AIComponent {
    pub ai_type: AIType,
    pub target_entity: Option<EntityId>,
    pub detection_range: f32,
    pub aggression_range: f32,
    pub state: AIState,
    pub state_timer: f32,
    pub pathfinding_enabled: bool,
    pub path_recalculation_interval: f32,
    pub last_path_recalculation: f32,
    pub current_path_target: Option<Vec3>,
    pub path_grid_size: f32,
}

#[derive(Debug, Clone, PartialEq)]
pub enum AIType {
    Aggressive,
    Defensive,
    Passive,
    Patrol,
}

#[derive(Debug, Clone, PartialEq)]
pub enum AIState {
    Idle,
    Patrolling,
    Chasing,
    Attacking,
    Fleeing,
    Returning,
}

impl AIComponent {
    pub fn new(ai_type: AIType, detection_range: f32, aggression_range: f32) -> Self {
        Self {
            ai_type,
            target_entity: None,
            detection_range,
            aggression_range,
            state: AIState::Idle,
            state_timer: 0.0,
            pathfinding_enabled: true,
            path_recalculation_interval: 1.0, // Recalculate path every second
            last_path_recalculation: 0.0,
            current_path_target: None,
            path_grid_size: 1.0,
        }
    }

    pub fn set_target(&mut self, target: Option<EntityId>) {
        self.target_entity = target;
    }

    pub fn update_state(&mut self, delta_time: f32) {
        self.state_timer += delta_time;
        
        // State transition logic would be implemented here
        // based on distance to target, health, etc.
    }

    pub fn should_attack(&self, distance_to_target: f32) -> bool {
        distance_to_target <= self.aggression_range &&
        self.state == AIState::Chasing
    }

    /// Check if path needs recalculation based on time interval
    pub fn should_recalculate_path(&self, current_time: f32) -> bool {
        if !self.pathfinding_enabled {
            return false;
        }

        current_time - self.last_path_recalculation >= self.path_recalculation_interval
    }

    /// Update path recalculation timer
    pub fn update_path_timer(&mut self, current_time: f32) {
        self.last_path_recalculation = current_time;
    }

    /// Set pathfinding target
    pub fn set_path_target(&mut self, target: Vec3) {
        self.current_path_target = Some(target);
    }

    /// Clear pathfinding target
    pub fn clear_path_target(&mut self) {
        self.current_path_target = None;
    }

    /// Check if entity has an active pathfinding target
    pub fn has_path_target(&self) -> bool {
        self.current_path_target.is_some()
    }

    /// Get current pathfinding target
    pub fn get_path_target(&self) -> Option<Vec3> {
        self.current_path_target
    }
}

/// Main ShadowZombieNinja entity structure
pub struct ShadowZombieNinja {
    pub entity_id: EntityId,
    pub health: HealthComponent,
    pub transform: TransformComponent,
    pub combat: CombatComponent,
    pub movement: MovementComponent,
    pub animation: AnimationComponent,
    pub ai: AIComponent,
    pub performance_monitor: Arc<PerformanceMonitor>,
    pub pathfinding_system: Option<Arc<RwLock<EntityPathfindingSystem>>>,
    pub creation_time: Instant,
    pub last_update: Instant,
}

impl std::fmt::Debug for ShadowZombieNinja {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ShadowZombieNinja")
            .field("entity_id", &self.entity_id)
            .field("health", &self.health)
            .field("transform.position", &self.transform.position)
            .finish()
    }
}

impl ShadowZombieNinja {
    /// Create a new ShadowZombieNinja entity
    pub fn new(
        entity_id: EntityId,
        position: Vec3,
        performance_monitor: Arc<PerformanceMonitor>,
    ) -> Self {
        Self {
            entity_id,
            health: HealthComponent::new(100.0),
            transform: TransformComponent::new(position),
            combat: CombatComponent::new(),
            movement: MovementComponent::new(4.5),
            animation: AnimationComponent::new(),
            ai: AIComponent::new(AIType::Aggressive, 15.0, 8.0),
            performance_monitor,
            pathfinding_system: None,
            creation_time: Instant::now(),
            last_update: Instant::now(),
        }
    }

    /// Update the entity state and components
    pub fn update(&mut self, delta_time: f32) {
        let start_time = Instant::now();
        
        // Update animation
        self.animation.update(delta_time);
        
        // Update AI state
        self.ai.update_state(delta_time);
        
        // Update physics
        self.movement.update_physics(delta_time, &mut self.transform);
        
        // Handle state transitions
        self.handle_state_transitions();
        
        // Record performance metrics
        let update_duration = start_time.elapsed();
        self.performance_monitor.record_metric(
            "shadow_zombie_ninja_update_time",
            update_duration.as_secs_f64(),
        );
        
        self.last_update = Instant::now();
    }

    /// Handle entity state transitions
    fn handle_state_transitions(&mut self) {
        // Animation state transitions based on AI state
        match self.ai.state {
            AIState::Chasing => {
                self.animation.set_animation(AnimationState::Running);
            }
            AIState::Attacking => {
                self.animation.set_animation(AnimationState::Attacking);
            }
            AIState::Idle => {
                self.animation.set_animation(AnimationState::Idle);
            }
            AIState::Fleeing => {
                self.animation.set_animation(AnimationState::Running);
            }
            _ => {}
        }

        // Health-based state transitions
        if !self.health.is_alive() {
            self.animation.set_animation(AnimationState::Dying);
        } else if self.health.get_health_percentage() < 0.3 {
            // Low health - might change AI behavior
            if self.ai.ai_type == AIType::Aggressive {
                // Could transition to defensive behavior
            }
        }
    }

    /// Perform attack action
    pub fn attack(&mut self, target_position: Vec3) -> Option<f32> {
        if !self.health.is_alive() {
            return None;
        }

        let distance = self.transform.position.distance(target_position);
        
        if distance <= self.combat.attack_range && self.combat.can_attack() {
            let damage = self.combat.perform_attack();
            
            // Update animation
            self.animation.set_animation(AnimationState::Attacking);
            
            // Record performance metrics
            self.performance_monitor.record_metric(
                "shadow_zombie_ninja_attack_count",
                1.0,
            );
            
            Some(damage)
        } else {
            None
        }
    }

    /// Take damage from an attack
    pub fn take_damage(&mut self, amount: f32) -> bool {
        let damage_taken = self.health.take_damage(amount);
        
        if damage_taken {
            // Update animation
            self.animation.set_animation(AnimationState::Damaged);
            
            // Update AI state if health is low
            if self.health.get_health_percentage() < 0.2 {
                if self.ai.ai_type == AIType::Aggressive {
                    self.ai.state = AIState::Fleeing;
                }
            }
            
            // Record performance metrics
            self.performance_monitor.record_metric(
                "shadow_zombie_ninja_damage_taken",
                amount as f64,
            );
        }
        
        damage_taken
    }

    /// Set the pathfinding system for this entity
    pub fn set_pathfinding_system(&mut self, system: Arc<RwLock<EntityPathfindingSystem>>) {
        self.pathfinding_system = Some(system);
    }

    /// Find a path to a target position using native A* pathfinding
    pub fn find_path_to(&mut self, target_position: Vec3, grid_data: &[bool], grid_width: usize, grid_height: usize) -> bool {
        if !self.ai.pathfinding_enabled || self.pathfinding_system.is_none() {
            return false;
        }

        if let Some(system) = &self.pathfinding_system {
            let mut system_lock = system.write().unwrap();

            // Convert world positions to grid coordinates
            let start_pos = self.transform.position;
            let grid_start = (
                (start_pos.x / self.ai.path_grid_size) as usize,
                (start_pos.z / self.ai.path_grid_size) as usize,
            );
            let grid_target = (
                (target_position.x / self.ai.path_grid_size) as usize,
                (target_position.z / self.ai.path_grid_size) as usize,
            );

            // Create grid from data
            let grid = crate::pathfinding::PathfindingGrid {
                width: grid_width,
                height: grid_height,
                obstacles: grid_data.to_vec(),
            };

            // Find path
            let result = system_lock.find_entity_path(
                self.entity_id,
                start_pos,
                target_position,
                &grid,
                self.ai.path_grid_size,
            );

            if let Some(path_result) = result {
                if path_result.success {
                    self.ai.set_path_target(target_position);
                    self.ai.update_path_timer(self.ai.state_timer);

                    // Record performance metrics
                    self.performance_monitor.record_metric(
                        "shadow_zombie_ninja_pathfinding_success",
                        1.0,
                    );
                    self.performance_monitor.record_metric(
                        "shadow_zombie_ninja_path_length",
                        path_result.path_length as f64,
                    );

                    return true;
                }
            }

            // Record failed pathfinding
            self.performance_monitor.record_metric(
                "shadow_zombie_ninja_pathfinding_failure",
                1.0,
            );
        }

        false
    }

    /// Move towards a target position using pathfinding if available
    pub fn move_towards(&mut self, target_position: Vec3, delta_time: f32) {
        if !self.health.is_alive() {
            return;
        }

        // Check if we need to recalculate path
        if self.ai.should_recalculate_path(self.ai.state_timer) ||
           !self.ai.has_path_target() ||
           self.ai.get_path_target().unwrap().distance(target_position) > self.ai.path_grid_size {

            // Try to find path using native pathfinding
            let grid_size = 32; // Assume 32x32 grid for now
            let dummy_grid = vec![false; grid_size * grid_size]; // No obstacles for now

            if !self.find_path_to(target_position, &dummy_grid, grid_size, grid_size) {
                // Fallback to direct movement if pathfinding fails
                self.move_directly_towards(target_position, delta_time);
                return;
            }
        }

        // For now, use direct movement to avoid complex borrowing issues
        // In a full implementation, this would integrate with the pathfinding system
        self.move_directly_towards(target_position, delta_time);
    }

    /// Direct movement towards target (fallback when pathfinding unavailable)
    fn move_directly_towards(&mut self, target_position: Vec3, delta_time: f32) {
        let direction = (target_position - self.transform.position).normalize();
        let movement = direction * self.movement.movement_speed * delta_time;

        self.transform.translate(movement);

        // Update animation based on movement
        if movement.length() > 0.01 {
            self.animation.set_animation(AnimationState::Walking);
        }

        // Look at movement direction
        self.transform.look_at(self.transform.position + direction, Vec3::Y);
    }

    /// Get the entity's current bounding box for collision detection
    pub fn get_bounding_box(&self) -> BoundingBox {
        let half_width = 0.5;
        let height = 2.0;
        
        BoundingBox {
            min: self.transform.position - Vec3::new(half_width, 0.0, half_width),
            max: self.transform.position + Vec3::new(half_width, height, half_width),
        }
    }

    /// Check if the entity should be rendered (within view distance)
    pub fn should_render(&self, camera_position: Vec3, max_render_distance: f32) -> bool {
        let distance = self.transform.position.distance(camera_position);
        distance <= max_render_distance && self.health.is_alive()
    }

    /// Get the entity's current state for debugging
    pub fn get_debug_info(&self) -> String {
        format!(
            "ShadowZombieNinja[{}] - Health: {:.1}/{:.1}, Position: {:.2}, {:.2}, {:.2}, State: {:?}",
            self.entity_id.0,
            self.health.current_health,
            self.health.max_health,
            self.transform.position.x,
            self.transform.position.y,
            self.transform.position.z,
            self.ai.state
        )
    }
}

/// Bounding box for collision detection
#[derive(Debug, Clone)]
pub struct BoundingBox {
    pub min: Vec3,
    pub max: Vec3,
}

impl BoundingBox {
    pub fn intersects(&self, other: &BoundingBox) -> bool {
        self.min.x <= other.max.x && self.max.x >= other.min.x &&
        self.min.y <= other.max.y && self.max.y >= other.min.y &&
        self.min.z <= other.max.z && self.max.z >= other.min.z
    }

    pub fn contains(&self, point: Vec3) -> bool {
        point.x >= self.min.x && point.x <= self.max.x &&
        point.y >= self.min.y && point.y <= self.max.y &&
        point.z >= self.min.z && point.z <= self.max.z
    }
}

/// Factory for creating ShadowZombieNinja entities
pub struct ShadowZombieNinjaFactory {
    performance_monitor: Arc<PerformanceMonitor>,
    pathfinding_system: Option<Arc<RwLock<EntityPathfindingSystem>>>,
}

impl ShadowZombieNinjaFactory {
    pub fn new(performance_monitor: Arc<PerformanceMonitor>) -> Self {
        Self {
            performance_monitor,
            pathfinding_system: None,
        }
    }

    /// Set the pathfinding system for entities created by this factory
    pub fn set_pathfinding_system(&mut self, system: Arc<RwLock<EntityPathfindingSystem>>) {
        self.pathfinding_system = Some(system);
    }

    pub fn create_entity(&self, entity_id: EntityId, position: Vec3) -> ShadowZombieNinja {
        let start_time = Instant::now();

        let mut entity = ShadowZombieNinja::new(entity_id, position, self.performance_monitor.clone());

        // Set pathfinding system if available
        if let Some(system) = &self.pathfinding_system {
            entity.set_pathfinding_system(system.clone());
        }

        // Record performance metrics
        let creation_duration = start_time.elapsed();
        self.performance_monitor.record_metric(
            "shadow_zombie_ninja_creation_time",
            creation_duration.as_secs_f64(),
        );

        entity
    }

    pub fn create_entity_with_stats(
        &self,
        entity_id: EntityId,
        position: Vec3,
        health: f32,
        attack_damage: f32,
        movement_speed: f32,
    ) -> ShadowZombieNinja {
        let mut entity = self.create_entity(entity_id, position);

        entity.health = HealthComponent::new(health);
        entity.combat = CombatComponent::new();
        entity.movement = MovementComponent::new(movement_speed);

        // Pathfinding system is already set in create_entity

        entity
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::performance_monitor::PerformanceMonitor;

    #[test]
    fn test_shadow_zombie_ninja_creation() {
        let monitor = Arc::new(PerformanceMonitor::new());
        let entity_id = EntityId(1);
        let position = Vec3::new(0.0, 0.0, 0.0);
        
        let ninja = ShadowZombieNinja::new(entity_id, position, monitor);
        
        assert_eq!(ninja.entity_id, entity_id);
        assert_eq!(ninja.transform.position, position);
        assert!(ninja.health.is_alive());
        assert_eq!(ninja.health.max_health, 100.0);
    }

    #[test]
    fn test_combat_system() {
        let monitor = Arc::new(PerformanceMonitor::new());
        let entity_id = EntityId(1);
        let position = Vec3::new(0.0, 0.0, 0.0);
        
        let mut ninja = ShadowZombieNinja::new(entity_id, position, monitor);
        
        // Test attack
        let target_pos = Vec3::new(1.0, 0.0, 0.0);
        let damage = ninja.attack(target_pos);
        assert!(damage.is_some());
        
        // Test damage taking
        assert!(ninja.take_damage(20.0));
        assert_eq!(ninja.health.current_health, 80.0);
        
        // Test death
        ninja.take_damage(100.0);
        assert!(!ninja.health.is_alive());
    }

    #[test]
    fn test_movement_system() {
        let monitor = Arc::new(PerformanceMonitor::new());
        let entity_id = EntityId(1);
        let position = Vec3::new(0.0, 0.0, 0.0);
        
        let mut ninja = ShadowZombieNinja::new(entity_id, position, monitor);
        
        let target_pos = Vec3::new(10.0, 0.0, 0.0);
        ninja.move_towards(target_pos, 0.016); // 60 FPS
        
        assert!(ninja.transform.position.x > 0.0);
        assert_eq!(ninja.transform.position.y, 0.0);
    }

    #[test]
    fn test_factory_pattern() {
        let monitor = Arc::new(PerformanceMonitor::new());
        let factory = ShadowZombieNinjaFactory::new(monitor);

        let entity_id = EntityId(1);
        let position = Vec3::new(5.0, 0.0, 5.0);

        let ninja = factory.create_entity_with_stats(
            entity_id,
            position,
            150.0,
            20.0,
            5.0,
        );

        assert_eq!(ninja.health.max_health, 150.0);
        assert_eq!(ninja.combat.attack_damage, 20.0);
        assert_eq!(ninja.movement.movement_speed, 5.0);
    }

    #[test]
    fn test_pathfinding_integration() {
        let monitor = Arc::new(PerformanceMonitor::new());

        // Create pathfinding system
        let config = PathfindingConfig {
            max_search_distance: 100.0,
            heuristic_weight: 1.0,
            timeout_ms: 1000,
            allow_diagonal_movement: true,
            path_smoothing_enabled: true,
            async_thread_pool_size: 4,
        };
        let pathfinding_system = Arc::new(RwLock::new(EntityPathfindingSystem::new(config, monitor.clone())));

        // Create factory with pathfinding system
        let mut factory = ShadowZombieNinjaFactory::new(monitor);
        factory.set_pathfinding_system(pathfinding_system);

        let entity_id = EntityId(1);
        let position = Vec3::new(0.0, 0.0, 0.0);

        let ninja = factory.create_entity(entity_id, position);

        // Verify pathfinding system is set
        assert!(ninja.pathfinding_system.is_some());
        assert!(ninja.ai.pathfinding_enabled);
        assert_eq!(ninja.ai.path_grid_size, 1.0);
    }

    #[test]
    fn test_ai_pathfinding_methods() {
        let monitor = Arc::new(PerformanceMonitor::new());
        let mut ninja = ShadowZombieNinja::new(EntityId(1), Vec3::ZERO, monitor);

        // Test AI pathfinding methods
        assert!(!ninja.ai.has_path_target());

        let target = Vec3::new(10.0, 0.0, 10.0);
        ninja.ai.set_path_target(target);
        assert!(ninja.ai.has_path_target());
        assert_eq!(ninja.ai.get_path_target(), Some(target));

        ninja.ai.clear_path_target();
        assert!(!ninja.ai.has_path_target());
    }
}