//! Generic Entity Framework for KneafCore
//!
//! This module provides a flexible, extensible framework for creating and managing
//! game entities with various components and behaviors. It replaces the specific
//! ShadowZombieNinja implementation with a generic system that can handle any
//! entity type through composition and traits.

use crate::entity_registry::{Component, EntityId};
use glam::Vec3;
use std::any::{Any, TypeId};
use std::collections::HashMap;
use std::sync::{Arc, RwLock};

/// Trait that defines the basic interface for any entity in the framework
pub trait Entity: Send + Sync {
    /// Get the unique identifier for this entity
    fn get_id(&self) -> EntityId;

    /// Update the entity state
    fn update(&mut self, delta_time: f32);

    /// Check if the entity is still active/alive
    fn is_active(&self) -> bool;

    /// Get the entity's current position
    fn get_position(&self) -> Vec3;

    /// Set the entity's position
    fn set_position(&mut self, position: Vec3);

    /// Get debug information about the entity
    fn get_debug_info(&self) -> String;
}

/// Trait for entities that support component operations
pub trait ComponentEntity: Entity {
    /// Get a reference to a component by type
    fn get_component<T: Component + 'static>(&self) -> Option<&T>;

    /// Get a mutable reference to a component by type
    fn get_component_mut<T: Component + 'static>(&mut self) -> Option<&mut T>;

    /// Add or replace a component
    fn add_component<T: Component + 'static>(&mut self, component: T);

    /// Remove a component by type
    fn remove_component<T: Component + 'static>(&mut self) -> Option<T>;
}

/// Generic component container that can hold any component type
#[derive(Debug)]
pub struct ComponentContainer {
    components: HashMap<TypeId, Box<dyn Any + Send + Sync>>,
}

impl Default for ComponentContainer {
    fn default() -> Self {
        Self::new()
    }
}

impl ComponentContainer {
    pub fn new() -> Self {
        Self {
            components: HashMap::new(),
        }
    }

    pub fn get<T: Component + 'static>(&self) -> Option<&T> {
        self.components
            .get(&TypeId::of::<T>())
            .and_then(|boxed| boxed.downcast_ref::<T>())
    }

    pub fn get_mut<T: Component + 'static>(&mut self) -> Option<&mut T> {
        self.components
            .get_mut(&TypeId::of::<T>())
            .and_then(|boxed| boxed.downcast_mut::<T>())
    }

    pub fn insert<T: Component + 'static>(&mut self, component: T) {
        self.components
            .insert(TypeId::of::<T>(), Box::new(component));
    }

    pub fn remove<T: Component + 'static>(&mut self) -> Option<T> {
        self.components
            .remove(&TypeId::of::<T>())
            .and_then(|boxed| boxed.downcast::<T>().ok())
            .map(|boxed| *boxed)
    }

    pub fn has<T: Component + 'static>(&self) -> bool {
        self.components.contains_key(&TypeId::of::<T>())
    }
}

/// Generic entity implementation that can be configured with different components
pub struct GenericEntity {
    pub entity_id: EntityId,
    pub position: Vec3,
    pub rotation: glam::Quat,
    pub scale: Vec3,
    pub velocity: Vec3,
    pub components: ComponentContainer,
    pub active: bool,
    pub creation_time: std::time::Instant,
    pub last_update: std::time::Instant,
}

impl GenericEntity {
    pub fn new(entity_id: EntityId, position: Vec3) -> Self {
        Self {
            entity_id,
            position,
            rotation: glam::Quat::IDENTITY,
            scale: Vec3::ONE,
            velocity: Vec3::ZERO,
            components: ComponentContainer::new(),
            active: true,
            creation_time: std::time::Instant::now(),
            last_update: std::time::Instant::now(),
        }
    }

    pub fn with_component<T: Component + 'static>(mut self, component: T) -> Self {
        self.components.insert(component);
        self
    }
}

impl Entity for GenericEntity {
    fn get_id(&self) -> EntityId {
        self.entity_id
    }

    fn update(&mut self, delta_time: f32) {
        // Update position based on velocity
        self.position += self.velocity * delta_time;
        self.last_update = std::time::Instant::now();
    }

    fn is_active(&self) -> bool {
        self.active
    }

    fn get_position(&self) -> Vec3 {
        self.position
    }

    fn set_position(&mut self, position: Vec3) {
        self.position = position;
    }

    fn get_debug_info(&self) -> String {
        format!(
            "GenericEntity[{}] - Position: {:.2}, {:.2}, {:.2}, Active: {}, Components: {}",
            self.entity_id.0,
            self.position.x,
            self.position.y,
            self.position.z,
            self.active,
            self.components.components.len()
        )
    }
}

impl ComponentEntity for GenericEntity {
    fn get_component<T: Component + 'static>(&self) -> Option<&T> {
        self.components.get::<T>()
    }

    fn get_component_mut<T: Component + 'static>(&mut self) -> Option<&mut T> {
        self.components.get_mut::<T>()
    }

    fn add_component<T: Component + 'static>(&mut self, component: T) {
        self.components.insert(component);
    }

    fn remove_component<T: Component + 'static>(&mut self) -> Option<T> {
        self.components.remove::<T>()
    }
}

/// Entity builder pattern for creating entities with specific configurations
pub struct EntityBuilder {
    entity_id: EntityId,
    position: Vec3,
    components: Vec<Box<dyn FnOnce(&mut GenericEntity) + Send + Sync>>,
}

impl EntityBuilder {
    pub fn new(entity_id: EntityId, position: Vec3) -> Self {
        Self {
            entity_id,
            position,
            components: Vec::new(),
        }
    }

    pub fn with_health(mut self, max_health: f32) -> Self {
        self.components.push(Box::new(move |entity| {
            entity.add_component(HealthComponent::new(max_health));
        }));
        self
    }

    pub fn with_movement(mut self, speed: f32) -> Self {
        self.components.push(Box::new(move |entity| {
            entity.add_component(MovementComponent::new(speed));
        }));
        self
    }

    pub fn with_combat(mut self, attack_damage: f32, attack_range: f32) -> Self {
        self.components.push(Box::new(move |entity| {
            entity.add_component(CombatComponent::new(attack_damage, attack_range));
        }));
        self
    }

    pub fn with_ai(mut self, ai_type: AIType, detection_range: f32, aggression_range: f32) -> Self {
        self.components.push(Box::new(move |entity| {
            entity.add_component(AIComponent::new(ai_type, detection_range, aggression_range));
        }));
        self
    }

    pub fn with_animation(mut self) -> Self {
        self.components.push(Box::new(move |entity| {
            entity.add_component(AnimationComponent::new());
        }));
        self
    }

    pub fn with_skills(mut self) -> Self {
        self.components.push(Box::new(move |entity| {
            entity.add_component(SkillComponent::new());
        }));
        self
    }

    pub fn build(self) -> GenericEntity {
        let mut entity = GenericEntity::new(self.entity_id, self.position);

        // Apply all component builders
        for component_builder in self.components {
            component_builder(&mut entity);
        }

        entity
    }
}

/// Generic factory for creating different types of entities
pub struct EntityFactory {
    performance_monitor: Arc<crate::performance_monitor::PerformanceMonitor>,
}

impl EntityFactory {
    pub fn new(performance_monitor: Arc<crate::performance_monitor::PerformanceMonitor>) -> Self {
        Self {
            performance_monitor,
        }
    }

    /// Create a basic entity with minimal components
    pub fn create_basic_entity(&self, entity_id: EntityId, position: Vec3) -> GenericEntity {
        let start_time = std::time::Instant::now();

        let entity = GenericEntity::new(entity_id, position);

        // Record performance metrics
        let creation_duration = start_time.elapsed();
        self.performance_monitor.record_metric(
            "basic_entity_creation_time",
            creation_duration.as_secs_f64(),
        );

        entity
    }

    /// Create a combat-ready entity
    pub fn create_combat_entity(
        &self,
        entity_id: EntityId,
        position: Vec3,
        health: f32,
        attack_damage: f32,
        movement_speed: f32,
    ) -> GenericEntity {
        let start_time = std::time::Instant::now();

        let entity = EntityBuilder::new(entity_id, position)
            .with_health(health)
            .with_combat(attack_damage, 2.0)
            .with_movement(movement_speed)
            .with_animation()
            .build();

        // Record performance metrics
        let creation_duration = start_time.elapsed();
        self.performance_monitor.record_metric(
            "combat_entity_creation_time",
            creation_duration.as_secs_f64(),
        );

        entity
    }

    /// Create an AI-controlled entity
    pub fn create_ai_entity(
        &self,
        entity_id: EntityId,
        position: Vec3,
        ai_type: AIType,
        detection_range: f32,
        aggression_range: f32,
    ) -> GenericEntity {
        let start_time = std::time::Instant::now();

        let entity = EntityBuilder::new(entity_id, position)
            .with_ai(ai_type, detection_range, aggression_range)
            .with_animation()
            .build();

        // Record performance metrics
        let creation_duration = start_time.elapsed();
        self.performance_monitor
            .record_metric("ai_entity_creation_time", creation_duration.as_secs_f64());

        entity
    }

    /// Create a Hayabusa-style ninja entity (preserving original functionality)
    pub fn create_hayabusa_ninja(&self, entity_id: EntityId, position: Vec3) -> GenericEntity {
        let start_time = std::time::Instant::now();

        let entity = EntityBuilder::new(entity_id, position)
            .with_health(200.0)
            .with_combat(15.0, 2.0)
            .with_movement(6.5)
            .with_ai(AIType::Aggressive, 25.0, 12.0)
            .with_animation()
            .with_skills()
            .build();

        // Record performance metrics
        let creation_duration = start_time.elapsed();
        self.performance_monitor.record_metric(
            "hayabusa_ninja_creation_time",
            creation_duration.as_secs_f64(),
        );

        entity
    }
}

/// Generic Health Component
#[derive(Debug, Clone)]
pub struct HealthComponent {
    pub current_health: f32,
    pub max_health: f32,
    pub health_regeneration_rate: f32,
    pub last_damage_time: Option<std::time::Instant>,
    pub invulnerability_duration: std::time::Duration,
}

impl HealthComponent {
    pub fn new(max_health: f32) -> Self {
        Self {
            current_health: max_health,
            max_health,
            health_regeneration_rate: 0.1,
            last_damage_time: None,
            invulnerability_duration: std::time::Duration::from_millis(500),
        }
    }

    pub fn take_damage(&mut self, amount: f32) -> bool {
        let now = std::time::Instant::now();

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

impl Component for HealthComponent {}

/// Generic Transform Component
#[derive(Debug, Clone)]
pub struct TransformComponent {
    pub position: Vec3,
    pub rotation: glam::Quat,
    pub scale: Vec3,
    pub velocity: Vec3,
    pub angular_velocity: Vec3,
}

impl TransformComponent {
    pub fn new(position: Vec3) -> Self {
        Self {
            position,
            rotation: glam::Quat::IDENTITY,
            scale: Vec3::ONE,
            velocity: Vec3::ZERO,
            angular_velocity: Vec3::ZERO,
        }
    }

    pub fn translate(&mut self, translation: Vec3) {
        self.position += translation;
    }

    pub fn rotate(&mut self, rotation: glam::Quat) {
        self.rotation *= rotation;
    }

    pub fn set_scale(&mut self, scale: Vec3) {
        self.scale = scale;
    }

    pub fn look_at(&mut self, target: Vec3, _up: Vec3) {
        let direction = (target - self.position).normalize();
        self.rotation = glam::Quat::from_rotation_arc(Vec3::Z, direction);
    }
}

impl Component for TransformComponent {}

/// Generic Movement Component
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

impl Component for MovementComponent {}

/// Generic Animation Component
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

impl Default for AnimationComponent {
    fn default() -> Self {
        Self::new()
    }
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

impl Component for AnimationComponent {}

/// Generic Skill Component
#[derive(Debug, Clone)]
pub struct SkillComponent {
    pub skill_cooldowns: HashMap<String, f32>,
    pub skill_data: HashMap<String, f32>,
}

impl Default for SkillComponent {
    fn default() -> Self {
        Self::new()
    }
}

impl SkillComponent {
    pub fn new() -> Self {
        Self {
            skill_cooldowns: HashMap::new(),
            skill_data: HashMap::new(),
        }
    }

    pub fn update_cooldowns(&mut self, delta_time: f32) {
        for cooldown in self.skill_cooldowns.values_mut() {
            if *cooldown > 0.0 {
                *cooldown -= delta_time;
            }
        }
    }

    pub fn set_cooldown(&mut self, skill_name: &str, cooldown: f32) {
        self.skill_cooldowns
            .insert(skill_name.to_string(), cooldown);
    }

    pub fn get_cooldown(&self, skill_name: &str) -> f32 {
        *self.skill_cooldowns.get(skill_name).unwrap_or(&0.0)
    }

    pub fn is_ready(&self, skill_name: &str) -> bool {
        self.get_cooldown(skill_name) <= 0.0
    }
}

impl Component for SkillComponent {}

/// Generic AI Component
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
            path_recalculation_interval: 1.0,
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
    }

    pub fn should_attack(&self, distance_to_target: f32) -> bool {
        distance_to_target <= self.aggression_range && self.state == AIState::Chasing
    }

    pub fn should_recalculate_path(&self, current_time: f32) -> bool {
        if !self.pathfinding_enabled {
            return false;
        }
        current_time - self.last_path_recalculation >= self.path_recalculation_interval
    }

    pub fn update_path_timer(&mut self, current_time: f32) {
        self.last_path_recalculation = current_time;
    }

    pub fn set_path_target(&mut self, target: Vec3) {
        self.current_path_target = Some(target);
    }

    pub fn clear_path_target(&mut self) {
        self.current_path_target = None;
    }

    pub fn has_path_target(&self) -> bool {
        self.current_path_target.is_some()
    }

    pub fn get_path_target(&self) -> Option<Vec3> {
        self.current_path_target
    }
}

impl Component for AIComponent {}

/// Bounding box for collision detection
#[derive(Debug, Clone)]
pub struct BoundingBox {
    pub min: Vec3,
    pub max: Vec3,
}

impl BoundingBox {
    pub fn new(center: Vec3, width: f32, height: f32, depth: f32) -> Self {
        let half_width = width / 2.0;
        let half_height = height / 2.0;
        let half_depth = depth / 2.0;

        Self {
            min: center - Vec3::new(half_width, half_height, half_depth),
            max: center + Vec3::new(half_width, half_height, half_depth),
        }
    }

    pub fn intersects(&self, other: &BoundingBox) -> bool {
        self.min.x <= other.max.x
            && self.max.x >= other.min.x
            && self.min.y <= other.max.y
            && self.max.y >= other.min.y
            && self.min.z <= other.max.z
            && self.max.z >= other.min.z
    }

    pub fn contains(&self, point: Vec3) -> bool {
        point.x >= self.min.x
            && point.x <= self.max.x
            && point.y >= self.min.y
            && point.y <= self.max.y
            && point.z >= self.min.z
            && point.z <= self.max.z
    }
}

impl Component for BoundingBox {}

/// Combat Component
#[derive(Debug, Clone)]
pub struct CombatComponent {
    pub attack_damage: f32,
    pub attack_range: f32,
    pub attack_cooldown: f32,
    pub last_attack_time: Option<std::time::Instant>,
    pub is_attacking: bool,
}

impl CombatComponent {
    pub fn new(attack_damage: f32, attack_range: f32) -> Self {
        Self {
            attack_damage,
            attack_range,
            attack_cooldown: 0.5,
            last_attack_time: None,
            is_attacking: false,
        }
    }

    pub fn can_attack(&self) -> bool {
        if let Some(last_attack) = self.last_attack_time {
            std::time::Instant::now()
                .duration_since(last_attack)
                .as_secs_f32()
                >= self.attack_cooldown
        } else {
            true
        }
    }

    pub fn attack(&mut self) -> bool {
        if !self.can_attack() {
            return false;
        }

        self.is_attacking = true;
        self.last_attack_time = Some(std::time::Instant::now());

        true
    }

    pub fn reset_attack(&mut self) {
        self.is_attacking = false;
    }
}

impl Component for CombatComponent {}

/// System that manages multiple entities
pub struct EntityManager {
    pub entities: HashMap<EntityId, GenericEntity>,
    performance_monitor: Arc<crate::performance_monitor::PerformanceMonitor>,
}

impl EntityManager {
    pub fn new(performance_monitor: Arc<crate::performance_monitor::PerformanceMonitor>) -> Self {
        Self {
            entities: HashMap::new(),
            performance_monitor,
        }
    }

    pub fn add_entity(&mut self, entity: GenericEntity) {
        self.entities.insert(entity.get_id(), entity);
    }

    pub fn remove_entity(&mut self, entity_id: EntityId) -> Option<GenericEntity> {
        self.entities.remove(&entity_id)
    }

    pub fn get_entity(&self, entity_id: EntityId) -> Option<&GenericEntity> {
        self.entities.get(&entity_id)
    }

    pub fn get_entity_mut(&mut self, entity_id: EntityId) -> Option<&mut GenericEntity> {
        self.entities.get_mut(&entity_id)
    }

    pub fn update_all(&mut self, delta_time: f32) {
        let start_time = std::time::Instant::now();

        for entity in self.entities.values_mut() {
            entity.update(delta_time);
        }

        // Remove inactive entities
        let mut inactive_entities = Vec::new();
        for (id, entity) in &self.entities {
            if !entity.is_active() {
                inactive_entities.push(*id);
            }
        }

        for id in inactive_entities {
            self.entities.remove(&id);
        }

        // Record performance metrics
        let update_duration = start_time.elapsed();
        self.performance_monitor
            .record_metric("entity_manager_update_time", update_duration.as_secs_f64());
        self.performance_monitor
            .record_metric("active_entities_count", self.entities.len() as f64);
    }

    pub fn get_entities_in_radius(&self, center: Vec3, radius: f32) -> Vec<EntityId> {
        self.entities
            .values()
            .filter(|entity| {
                let distance = entity.get_position().distance(center);
                distance <= radius
            })
            .map(|entity| entity.get_id())
            .collect()
    }

    pub fn count(&self) -> usize {
        self.entities.len()
    }

    // Public method to access entities for testing purposes
    pub fn get_all_entities(&self) -> Vec<&GenericEntity> {
        self.entities.values().collect()
    }
}

// Re-export commonly used components and types
// All components are already defined in this module
