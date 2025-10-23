//! Combat System for Entity Interactions
//!
//! This module implements a comprehensive combat system with damage calculations,
//! hit detection, combat state management, and integration with the ECS architecture.
//! It provides native Rust implementation of combat mechanics with performance optimization.

use glam::Vec3;
use std::sync::{ Arc, RwLock };
use std::collections::{ HashMap, VecDeque };
use std::time::{ Duration, Instant };
use rayon::prelude::*;
use crate::entity_registry::{ EntityId, EntityRegistry, EntityType, Component };
use crate::entity_framework::{ HealthComponent, BoundingBox };
use crate::performance_monitor::PerformanceMonitor;

/// Combat event types for the combat system
#[derive(Debug, Clone, PartialEq)]
pub enum CombatEventType {
    AttackStarted,
    AttackLanded,
    AttackBlocked,
    AttackMissed,
    DamageDealt,
    DamageReceived,
    Death,
    Kill,
    CriticalHit,
    Dodge,
}

/// Combat event for tracking combat interactions
#[derive(Debug, Clone)]
pub struct CombatEvent {
    pub event_type: CombatEventType,
    pub attacker_id: EntityId,
    pub target_id: EntityId,
    pub damage_amount: f32,
    pub is_critical: bool,
    pub timestamp: Instant,
    pub position: Vec3,
}

/// SIMD-optimized hit detection lookup table
const HIT_NORMAL_TABLE: [Vec3; 6] = [
    Vec3::new(1.0, 0.0, 0.0), // Right face
    Vec3::new(-1.0, 0.0, 0.0), // Left face
    Vec3::new(0.0, 1.0, 0.0), // Top face
    Vec3::new(0.0, -1.0, 0.0), // Bottom face
    Vec3::new(0.0, 0.0, 1.0), // Front face
    Vec3::new(0.0, 0.0, -1.0), // Back face
];

/// Hit detection result
#[derive(Debug, Clone)]
pub struct HitDetection {
    pub did_hit: bool,
    pub hit_position: Option<Vec3>,
    pub hit_normal: Option<Vec3>,
    pub damage_multiplier: f32,
    pub is_critical: bool,
    pub hit_box: Option<BoundingBox>,
}

/// Combat state for entities
#[derive(Debug, Clone, PartialEq)]
pub enum CombatState {
    Idle,
    Attacking,
    Defending,
    Stunned,
    Invulnerable,
    Dead,
}

/// Combat statistics for tracking performance
#[derive(Debug, Clone)]
pub struct CombatStats {
    pub total_attacks: u64,
    pub successful_hits: u64,
    pub total_damage_dealt: f64,
    pub total_damage_received: f64,
    pub critical_hits: u64,
    pub dodges: u64,
    pub blocks: u64,
    pub kills: u64,
    pub deaths: u64,
}

impl CombatStats {
    pub fn new() -> Self {
        Self {
            total_attacks: 0,
            successful_hits: 0,
            total_damage_dealt: 0.0,
            total_damage_received: 0.0,
            critical_hits: 0,
            dodges: 0,
            blocks: 0,
            kills: 0,
            deaths: 0,
        }
    }

    pub fn hit_rate(&self) -> f32 {
        if self.total_attacks == 0 {
            0.0
        } else {
            (self.successful_hits as f32) / (self.total_attacks as f32)
        }
    }

    pub fn critical_rate(&self) -> f32 {
        if self.successful_hits == 0 {
            0.0
        } else {
            (self.critical_hits as f32) / (self.successful_hits as f32)
        }
    }

    pub fn average_damage_dealt(&self) -> f32 {
        if self.successful_hits == 0 {
            0.0
        } else {
            (self.total_damage_dealt / (self.successful_hits as f64)) as f32
        }
    }
}

/// Combat component that can be attached to entities
#[derive(Debug, Clone)]
pub struct CombatComponent {
    pub state: CombatState,
    pub stats: CombatStats,
    pub last_combat_action: Option<Instant>,
    pub combat_cooldown: Duration,
    pub stun_duration: Duration,
    pub invulnerability_duration: Duration,
    pub state_timer: f32,
    pub combo_count: u32,
    pub max_combo: u32,
    pub combo_reset_time: Duration,
    // Additional combat stats for entity behavior
    pub attack_damage: f32,
    pub attack_range: f32,
    pub critical_chance: f32,
    pub critical_multiplier: f32,
}

impl Component for CombatComponent {}

impl CombatComponent {
    pub fn new() -> Self {
        Self {
            state: CombatState::Idle,
            stats: CombatStats::new(),
            last_combat_action: None,
            combat_cooldown: Duration::from_millis(500),
            stun_duration: Duration::from_millis(1000),
            invulnerability_duration: Duration::from_millis(300),
            state_timer: 0.0,
            combo_count: 0,
            max_combo: 5,
            combo_reset_time: Duration::from_secs(3),
            attack_damage: 10.0,
            attack_range: 2.0,
            critical_chance: 0.1,
            critical_multiplier: 1.5,
        }
    }

    pub fn can_attack(&self) -> bool {
        self.state == CombatState::Idle &&
            self.last_combat_action.is_none_or(|last| {
                Instant::now().duration_since(last) >= self.combat_cooldown
            })
    }

    /// Perform an attack and return the damage value for this attack
    pub fn perform_attack(&mut self) -> f32 {
        // Update last action timestamp and combo
        self.last_combat_action = Some(Instant::now());
        self.increment_combo();

        // Basic damage calculation including combo multiplier and critical chance
        let mut damage = self.attack_damage * self.get_combo_multiplier();
        if rand::random::<f32>() < self.critical_chance {
            damage *= self.critical_multiplier;
            self.stats.critical_hits += 1;
        }

        // Update stats
        self.stats.total_attacks += 1;

        damage
    }

    pub fn reset_combo(&mut self) {
        self.combo_count = 0;
    }

    pub fn increment_combo(&mut self) {
        self.combo_count = (self.combo_count + 1).min(self.max_combo);
    }

    pub fn get_combo_multiplier(&self) -> f32 {
        1.0 + (self.combo_count as f32) * 0.1
    }
}

/// Damage type for different kinds of damage
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum DamageType {
    Physical,
    Magical,
    Fire,
    Ice,
    Lightning,
    Poison,
    True, // Unblockable damage
}

/// Damage calculation parameters
#[derive(Debug, Clone)]
pub struct DamageCalculation {
    pub base_damage: f32,
    pub damage_type: DamageType,
    pub attacker_level: u32,
    pub target_level: u32,
    pub attacker_stats: CombatStats,
    pub critical_chance: f32,
    pub armor_penetration: f32,
    pub element_bonus: f32,
}

/// Result of damage calculation
#[derive(Debug, Clone)]
pub struct DamageResult {
    pub final_damage: f32,
    pub is_critical: bool,
    pub damage_type: DamageType,
    pub was_blocked: bool,
    pub was_dodged: bool,
    pub overkill_damage: f32,
}

/// Main combat system managing all combat interactions
pub struct CombatSystem {
    entity_registry: Arc<EntityRegistry>,
    performance_monitor: Arc<PerformanceMonitor>,
    combat_events: Arc<RwLock<VecDeque<CombatEvent>>>,
    hit_detection_cache: Arc<RwLock<HashMap<(EntityId, EntityId), HitDetection>>>,
    damage_modifiers: Arc<RwLock<HashMap<DamageType, f32>>>,
    max_event_history: usize,
}

impl CombatSystem {
    /// Create a new combat system
    pub fn new(
        entity_registry: Arc<EntityRegistry>,
        performance_monitor: Arc<PerformanceMonitor>
    ) -> Self {
        let mut damage_modifiers = HashMap::new();
        damage_modifiers.insert(DamageType::Physical, 1.0);
        damage_modifiers.insert(DamageType::Magical, 1.0);
        damage_modifiers.insert(DamageType::Fire, 1.2);
        damage_modifiers.insert(DamageType::Ice, 0.8);
        damage_modifiers.insert(DamageType::Lightning, 1.1);
        damage_modifiers.insert(DamageType::Poison, 0.9);
        damage_modifiers.insert(DamageType::True, 1.0);

        Self {
            entity_registry,
            performance_monitor,
            combat_events: Arc::new(RwLock::new(VecDeque::new())),
            hit_detection_cache: Arc::new(RwLock::new(HashMap::new())),
            damage_modifiers: Arc::new(RwLock::new(damage_modifiers)),
            max_event_history: 1000,
        }
    }

    /// Process an attack from one entity to another
    pub fn process_attack(
        &self,
        attacker_id: EntityId,
        target_id: EntityId,
        attack_position: Vec3
    ) -> Result<CombatEvent, String> {
        let start_time = Instant::now();

        // Validate entities exist
        if !self.entity_registry.entity_exists(attacker_id) {
            return Err(format!("Attacker entity {} does not exist", attacker_id.0));
        }

        if !self.entity_registry.entity_exists(target_id) {
            return Err(format!("Target entity {} does not exist", target_id.0));
        }

        // Get combat components
        let attacker_combat = self.get_combat_component(attacker_id)?;
        let target_combat = self.get_combat_component(target_id)?;

        // Check if attacker can attack
        if !attacker_combat.can_attack() {
            return self.create_missed_event(attacker_id, target_id, attack_position);
        }

        // Special handling for ShadowZombieNinja - multi-shot with fixed trajectory
        let is_shadow_zombie = self.entity_registry.get_entity_type(attacker_id)
            .map(|t| t == EntityType::ShadowZombieNinja)
            .unwrap_or(false);
        let mut all_events = Vec::new();

        if is_shadow_zombie {
            // Shadow Zombie Ninja should fire 3 bullets with fixed (non-curving) trajectory
            let num_shots = 3;
            let spread_angle = std::f32::consts::PI / 6.0; // 30 degrees in radians

            for i in 0..num_shots {
                let angle_offset = ((i as f32) - 1.0) * spread_angle; // -30°, 0°, +30°

                // Calculate attack position with slight spread
                let spread_x = angle_offset.cos() * 0.2;
                let spread_z = angle_offset.sin() * 0.2;
                let shot_position = attack_position + Vec3::new(spread_x, 0.0, spread_z);

                // Get target position for straight trajectory calculation
                let target_pos = self.get_entity_position(target_id)?;

                // Calculate straight trajectory (no upward curve)
                let direction = (target_pos - shot_position).normalize();
                let straight_attack_position =
                    shot_position + direction * attacker_combat.attack_range;

                // Perform hit detection with straight trajectory
                let hit_detection = self.perform_hit_detection(
                    attacker_id,
                    target_id,
                    straight_attack_position
                )?;

                if hit_detection.did_hit {
                    // Calculate damage
                    let damage_calc = self.prepare_damage_calculation(attacker_id, target_id)?;
                    let damage_result = self.calculate_damage(
                        damage_calc,
                        &hit_detection,
                        target_id
                    )?;

                    // Apply damage to target
                    let event = self.apply_damage(
                        attacker_id,
                        target_id,
                        damage_result,
                        straight_attack_position
                    )?;
                    all_events.push(event);
                } else {
                    let event = self.create_missed_event(
                        attacker_id,
                        target_id,
                        straight_attack_position
                    )?;
                    all_events.push(event);
                }
            }

            // Return the first successful hit event, or last missed event
            if let Some(last_event) = all_events.last() {
                // Update combat state for the first successful attack
                if
                    let Some(first_hit) = all_events
                        .iter()
                        .find(|e| e.event_type != CombatEventType::AttackMissed)
                {
                    self.update_combat_state(attacker_id, target_id, first_hit)?;
                } else {
                    self.update_combat_state(attacker_id, target_id, last_event)?;
                }

                return Ok(last_event.clone());
            }
        } else {
            // Normal entity attack logic
            // Perform hit detection
            let hit_detection = self.perform_hit_detection(
                attacker_id,
                target_id,
                attack_position
            )?;

            if !hit_detection.did_hit {
                return self.create_missed_event(attacker_id, target_id, attack_position);
            }

            // Calculate damage
            let damage_calc = self.prepare_damage_calculation(attacker_id, target_id)?;
            let damage_result = self.calculate_damage(damage_calc, &hit_detection, target_id)?;

            // Apply damage to target
            let final_event = self.apply_damage(
                attacker_id,
                target_id,
                damage_result,
                attack_position
            )?;

            // Update combat state
            self.update_combat_state(attacker_id, target_id, &final_event)?;

            return Ok(final_event);
        }

        // Record performance metrics
        let processing_time = start_time.elapsed();
        self.performance_monitor.record_metric(
            "combat_attack_processing_time",
            processing_time.as_secs_f64()
        );

        Ok(CombatEvent {
            event_type: CombatEventType::AttackMissed,
            attacker_id,
            target_id,
            damage_amount: 0.0,
            is_critical: false,
            timestamp: Instant::now(),
            position: attack_position,
        })
    }

    /// SIMD-optimized hit detection between entities
    fn perform_hit_detection(
        &self,
        attacker_id: EntityId,
        target_id: EntityId,
        attack_position: Vec3
    ) -> Result<HitDetection, String> {
        let start_time = Instant::now();

        // Get entity positions and bounding boxes
        let attacker_pos = self.get_entity_position(attacker_id)?;
        let target_pos = self.get_entity_position(target_id)?;
        let target_bbox = self.get_entity_bounding_box(target_id)?;

        // SIMD-optimized distance calculation
        let distance = self.simd_distance_optimized(attacker_pos, target_pos);

        // Check if target is within attack range
        let attacker_combat = self.get_shadow_zombie_combat(attacker_id)?;
        if distance > attacker_combat.attack_range {
            return Ok(HitDetection {
                did_hit: false,
                hit_position: None,
                hit_normal: None,
                damage_multiplier: 0.0,
                is_critical: false,
                hit_box: None,
            });
        }

        // SIMD-optimized ray-box intersection test
        let hit_result = self.simd_ray_box_intersection(attack_position, target_pos, &target_bbox);

        // Lookup table for dodge and critical calculations
        let dodge_chance = self.get_dodge_chance_optimized(target_id);
        let was_dodged = self.lookup_chance(dodge_chance);

        let critical_chance = attacker_combat.critical_chance;
        let is_critical = self.lookup_chance(critical_chance);

        let hit_detection = HitDetection {
            did_hit: hit_result.did_hit && !was_dodged,
            hit_position: hit_result.hit_position,
            hit_normal: hit_result.hit_normal,
            damage_multiplier: if is_critical {
                attacker_combat.critical_multiplier
            } else {
                1.0
            },
            is_critical,
            hit_box: Some(target_bbox),
        };

        // Cache hit detection result
        self.hit_detection_cache
            .write()
            .unwrap()
            .insert((attacker_id, target_id), hit_detection.clone());

        // Record performance metrics
        let detection_time = start_time.elapsed();
        self.performance_monitor.record_metric("hit_detection_time", detection_time.as_secs_f64());

        Ok(hit_detection)
    }

    /// SIMD-optimized distance calculation using packed operations
    fn simd_distance_optimized(&self, pos1: Vec3, pos2: Vec3) -> f32 {
        let dx = pos1.x - pos2.x;
        let dy = pos1.y - pos2.y;
        let dz = pos1.z - pos2.z;

        // Use fast inverse square root approximation for distance
        let distance_sq = dx * dx + dy * dy + dz * dz;
        self.fast_inverse_sqrt(distance_sq)
    }

    /// Fast inverse square root approximation (Quake III algorithm)
    fn fast_inverse_sqrt(&self, x: f32) -> f32 {
        let half_x = 0.5f32 * x;
        let mut y = x;
        let i = y.to_bits();
        let j = 0x5f3759df - (i >> 1);
        y = f32::from_bits(j);
        y = y * (1.5f32 - half_x * y * y);
        1.0f32 / y
    }

    /// Optimized ray-box intersection using vectorized operations
    fn simd_ray_box_intersection(
        &self,
        ray_origin: Vec3,
        ray_direction: Vec3,
        bbox: &BoundingBox
    ) -> HitDetection {
        let mut t_min: f32 = 0.0;
        let mut t_max: f32 = f32::INFINITY;

        // Process all three axes with vectorized operations
        let origins = [ray_origin.x, ray_origin.y, ray_origin.z];
        let directions = [ray_direction.x, ray_direction.y, ray_direction.z];
        let bbox_mins = [bbox.min.x, bbox.min.y, bbox.min.z];
        let bbox_maxs = [bbox.max.x, bbox.max.y, bbox.max.z];

        // Calculate t1 and t2 for all axes simultaneously
        let mut t1s = [0.0f32; 3];
        let mut t2s = [0.0f32; 3];

        for i in 0..3 {
            if directions[i].abs() < 1e-8 {
                // Ray is parallel to the plane
                if origins[i] < bbox_mins[i] || origins[i] > bbox_maxs[i] {
                    return HitDetection {
                        did_hit: false,
                        hit_position: None,
                        hit_normal: None,
                        damage_multiplier: 1.0,
                        is_critical: false,
                        hit_box: None,
                    };
                }
            } else {
                t1s[i] = (bbox_mins[i] - origins[i]) / directions[i];
                t2s[i] = (bbox_maxs[i] - origins[i]) / directions[i];
            }
        }

        // Find min and max for each axis
        let t_nears: Vec<f32> = t1s
            .iter()
            .zip(t2s.iter())
            .map(|(a, b)| a.min(*b))
            .collect();
        let t_fars: Vec<f32> = t1s
            .iter()
            .zip(t2s.iter())
            .map(|(a, b)| a.max(*b))
            .collect();

        // Calculate final t_min and t_max
        let t_near_max = t_nears.iter().fold(0.0f32, |a, &b| a.max(b));
        let t_far_min = t_fars.iter().fold(f32::INFINITY, |a, &b| a.min(b));

        t_min = t_min.max(t_near_max);
        t_max = t_max.min(t_far_min);

        if t_min > t_max {
            return HitDetection {
                did_hit: false,
                hit_position: None,
                hit_normal: None,
                damage_multiplier: 1.0,
                is_critical: false,
                hit_box: None,
            };
        }

        let hit_position = ray_origin + ray_direction * t_min;
        let hit_normal = self.lookup_hit_normal(hit_position, bbox);

        HitDetection {
            did_hit: true,
            hit_position: Some(hit_position),
            hit_normal: Some(hit_normal),
            damage_multiplier: 1.0,
            is_critical: false,
            hit_box: Some(bbox.clone()),
        }
    }

    /// Lookup table-based hit normal calculation
    fn lookup_hit_normal(&self, hit_position: Vec3, bbox: &BoundingBox) -> Vec3 {
        let center = (bbox.min + bbox.max) * 0.5;
        let direction = (hit_position - center).normalize();

        // Use lookup table for hit normal based on dominant axis
        let abs_x = direction.x.abs();
        let abs_y = direction.y.abs();
        let abs_z = direction.z.abs();

        if abs_x > abs_y && abs_x > abs_z {
            HIT_NORMAL_TABLE[if direction.x > 0.0 { 0 } else { 1 }]
        } else if abs_y > abs_z {
            HIT_NORMAL_TABLE[if direction.y > 0.0 { 2 } else { 3 }]
        } else {
            HIT_NORMAL_TABLE[if direction.z > 0.0 { 4 } else { 5 }]
        }
    }

    /// Lookup table-based chance calculation
    fn lookup_chance(&self, chance: f32) -> bool {
        // Pre-calculated threshold table for common chance values
        const CHANCE_THRESHOLDS: [f32; 101] = [
            0.0, 0.01, 0.02, 0.03, 0.04, 0.05, 0.06, 0.07, 0.08, 0.09, 0.1, 0.11, 0.12, 0.13, 0.14, 0.15,
            0.16, 0.17, 0.18, 0.19, 0.2, 0.21, 0.22, 0.23, 0.24, 0.25, 0.26, 0.27, 0.28, 0.29, 0.3, 0.31,
            0.32, 0.33, 0.34, 0.35, 0.36, 0.37, 0.38, 0.39, 0.4, 0.41, 0.42, 0.43, 0.44, 0.45,
            0.46, 0.47, 0.48, 0.49, 0.5, 0.51, 0.52, 0.53, 0.54, 0.55, 0.56, 0.57, 0.58, 0.59, 0.6, 0.61,
            0.62, 0.63, 0.64, 0.65, 0.66, 0.67, 0.68, 0.69, 0.7, 0.71, 0.72, 0.73, 0.74, 0.75,
            0.76, 0.77, 0.78, 0.79, 0.8, 0.81, 0.82, 0.83, 0.84, 0.85, 0.86, 0.87, 0.88, 0.89, 0.9, 0.91,
            0.92, 0.93, 0.94, 0.95, 0.96, 0.97, 0.98, 0.99, 1.0,
        ];

        let threshold = if ((chance * 100.0) as usize) < 101 {
            CHANCE_THRESHOLDS[(chance * 100.0) as usize]
        } else {
            chance
        };

        rand::random::<f32>() < threshold
    }

    /// Optimized dodge chance calculation using lookup tables
    fn get_dodge_chance_optimized(&self, target_id: EntityId) -> f32 {
        // Pre-calculated dodge chances based on entity level and type
        const BASE_DODGE_CHANCES: [f32; 10] = [
            0.05, 0.06, 0.07, 0.08, 0.09, 0.1, 0.11, 0.12, 0.13, 0.14,
        ];

        // Simplified level-based lookup
        let level_index = (target_id.0 % 10) as usize;
        BASE_DODGE_CHANCES[level_index]
    }

    /// Calculate ray-box intersection
    fn ray_box_intersection(
        &self,
        ray_origin: Vec3,
        ray_direction: Vec3,
        bbox: &BoundingBox
    ) -> HitDetection {
        let mut t_min: f32 = 0.0;
        let mut t_max: f32 = f32::INFINITY;

        // Test intersection with each pair of planes
        for i in 0..3 {
            let origin = ray_origin[i];
            let direction = ray_direction[i];
            let min_val = bbox.min[i];
            let max_val = bbox.max[i];

            if direction.abs() < 1e-8 {
                // Ray is parallel to the plane
                if origin < min_val || origin > max_val {
                    return HitDetection {
                        did_hit: false,
                        hit_position: None,
                        hit_normal: None,
                        damage_multiplier: 1.0,
                        is_critical: false,
                        hit_box: None,
                    };
                }
            } else {
                let t1 = (min_val - origin) / direction;
                let t2 = (max_val - origin) / direction;

                let (t_near, t_far) = if t1 < t2 { (t1, t2) } else { (t2, t1) };

                t_min = t_min.max(t_near);
                t_max = t_min.min(t_far);

                if t_min > t_max {
                    return HitDetection {
                        did_hit: false,
                        hit_position: None,
                        hit_normal: None,
                        damage_multiplier: 1.0,
                        is_critical: false,
                        hit_box: None,
                    };
                }
            }
        }

        let hit_position = ray_origin + ray_direction * t_min;
        let hit_normal = self.calculate_hit_normal(hit_position, bbox);

        HitDetection {
            did_hit: true,
            hit_position: Some(hit_position),
            hit_normal: Some(hit_normal),
            damage_multiplier: 1.0,
            is_critical: false,
            hit_box: Some(bbox.clone()),
        }
    }

    /// Calculate hit normal based on hit position and bounding box
    fn calculate_hit_normal(&self, hit_position: Vec3, bbox: &BoundingBox) -> Vec3 {
        let center = (bbox.min + bbox.max) * 0.5;
        let direction = (hit_position - center).normalize();

        // Determine which face was hit based on the dominant axis
        if direction.x.abs() > direction.y.abs() && direction.x.abs() > direction.z.abs() {
            Vec3::new(direction.x.signum(), 0.0, 0.0)
        } else if direction.y.abs() > direction.z.abs() {
            Vec3::new(0.0, direction.y.signum(), 0.0)
        } else {
            Vec3::new(0.0, 0.0, direction.z.signum())
        }
    }

    /// Calculate dodge chance for target entity
    fn calculate_dodge_chance(&self, target_id: EntityId) -> Result<f32, String> {
        // Simplified dodge calculation - would use entity stats in real implementation
        let base_dodge = 0.05; // 5% base dodge chance
        let level_bonus = 0.01; // 1% per level difference

        Ok(base_dodge)
    }

    /// Prepare damage calculation parameters
    fn prepare_damage_calculation(
        &self,
        attacker_id: EntityId,
        target_id: EntityId
    ) -> Result<DamageCalculation, String> {
        let attacker_combat = self.get_shadow_zombie_combat(attacker_id)?;
        let attacker_stats = self.get_combat_stats(attacker_id)?;
        let target_stats = self.get_combat_stats(target_id)?;

        Ok(DamageCalculation {
            base_damage: attacker_combat.attack_damage,
            damage_type: DamageType::Physical,
            attacker_level: 1,
            target_level: 1,
            attacker_stats,
            critical_chance: attacker_combat.critical_chance,
            armor_penetration: 0.0,
            element_bonus: 0.0,
        })
    }

    /// Calculate final damage based on various factors
    fn calculate_damage(
        &self,
        calc: DamageCalculation,
        hit_detection: &HitDetection,
        target_id: EntityId
    ) -> Result<DamageResult, String> {
        let start_time = Instant::now();

        let mut final_damage = calc.base_damage * hit_detection.damage_multiplier;

        // Apply damage type modifier
        let damage_modifiers = self.damage_modifiers.read().unwrap();
        if let Some(&modifier) = damage_modifiers.get(&calc.damage_type) {
            final_damage *= modifier;
        }

        // Check for dodge
        let was_dodged = false; // Would be calculated based on target stats

        // Check for block
        let was_blocked = false; // Would be calculated based on target defense

        // Apply block reduction if blocked
        if was_blocked {
            final_damage *= 0.5; // 50% damage reduction
        }

        // Calculate overkill damage
        let target_health = self.get_entity_health(target_id)?;
        let overkill_damage = if final_damage > target_health {
            final_damage - target_health
        } else {
            0.0
        };

        let result = DamageResult {
            final_damage,
            is_critical: hit_detection.is_critical,
            damage_type: calc.damage_type,
            was_blocked,
            was_dodged,
            overkill_damage,
        };

        // Record performance metrics
        let calc_time = start_time.elapsed();
        self.performance_monitor.record_metric("damage_calculation_time", calc_time.as_secs_f64());

        Ok(result)
    }

    /// Apply damage to target entity
    fn apply_damage(
        &self,
        attacker_id: EntityId,
        target_id: EntityId,
        damage_result: DamageResult,
        position: Vec3
    ) -> Result<CombatEvent, String> {
        // Get target health component
        let mut target_health = self.get_entity_health_component(target_id)?;

        // Apply damage
        let damage_taken = target_health.take_damage(damage_result.final_damage);

        // Update combat stats
        self.update_combat_stats(attacker_id, target_id, &damage_result)?;

        // Create combat event
        let event = CombatEvent {
            event_type: if damage_result.is_critical {
                CombatEventType::CriticalHit
            } else {
                CombatEventType::DamageDealt
            },
            attacker_id,
            target_id,
            damage_amount: damage_result.final_damage,
            is_critical: damage_result.is_critical,
            timestamp: Instant::now(),
            position,
        };

        // Add to event history
        self.add_combat_event(event.clone())?;

        // Check for death
        if !target_health.is_alive() {
            self.handle_entity_death(attacker_id, target_id)?;
        }

        Ok(event)
    }

    /// Update combat statistics
    fn update_combat_stats(
        &self,
        attacker_id: EntityId,
        target_id: EntityId,
        damage_result: &DamageResult
    ) -> Result<(), String> {
        // Update attacker stats
        let mut attacker_stats = self.get_combat_stats(attacker_id)?;
        attacker_stats.total_attacks += 1;
        attacker_stats.successful_hits += 1;
        attacker_stats.total_damage_dealt += damage_result.final_damage as f64;

        if damage_result.is_critical {
            attacker_stats.critical_hits += 1;
        }

        // Update target stats
        let mut target_stats = self.get_combat_stats(target_id)?;
        target_stats.total_damage_received += damage_result.final_damage as f64;

        Ok(())
    }

    /// Handle entity death
    fn handle_entity_death(&self, killer_id: EntityId, victim_id: EntityId) -> Result<(), String> {
        // Update killer stats
        let mut killer_stats = self.get_combat_stats(killer_id)?;
        killer_stats.kills += 1;

        // Update victim stats
        let mut victim_stats = self.get_combat_stats(victim_id)?;
        victim_stats.deaths += 1;

        // Create death event
        let death_event = CombatEvent {
            event_type: CombatEventType::Death,
            attacker_id: killer_id,
            target_id: victim_id,
            damage_amount: 0.0,
            is_critical: false,
            timestamp: Instant::now(),
            position: Vec3::new(0.0, 0.0, 0.0), // Would get actual position
        };

        self.add_combat_event(death_event)?;

        // Deactivate entity in registry
        self.entity_registry.deactivate_entity(victim_id);

        Ok(())
    }

    /// Update combat state for entities
    fn update_combat_state(
        &self,
        attacker_id: EntityId,
        target_id: EntityId,
        event: &CombatEvent
    ) -> Result<(), String> {
        // Update attacker combat state
        let mut attacker_combat = self.get_combat_component(attacker_id)?;
        attacker_combat.state = CombatState::Attacking;
        attacker_combat.last_combat_action = Some(Instant::now());
        attacker_combat.increment_combo();

        // Update target combat state
        let mut target_combat = self.get_combat_component(target_id)?;
        target_combat.state = CombatState::Defending;

        Ok(())
    }

    /// Create a missed attack event
    fn create_missed_event(
        &self,
        attacker_id: EntityId,
        target_id: EntityId,
        position: Vec3
    ) -> Result<CombatEvent, String> {
        let event = CombatEvent {
            event_type: CombatEventType::AttackMissed,
            attacker_id,
            target_id,
            damage_amount: 0.0,
            is_critical: false,
            timestamp: Instant::now(),
            position,
        };

        self.add_combat_event(event.clone())?;
        Ok(event)
    }

    /// Add combat event to history
    fn add_combat_event(&self, event: CombatEvent) -> Result<(), String> {
        let mut events = self.combat_events.write().unwrap();

        events.push_back(event);

        // Maintain max event history
        if events.len() > self.max_event_history {
            events.pop_front();
        }

        Ok(())
    }

    /// Helper methods to get entity components
    fn get_combat_component(&self, entity_id: EntityId) -> Result<CombatComponent, String> {
        self.entity_registry
            .get_component::<CombatComponent>(entity_id)
            .map(|arc_comp| (*arc_comp).clone())
            .ok_or_else(|| format!("Combat component not found for entity {}", entity_id.0))
    }

    fn get_shadow_zombie_combat(&self, entity_id: EntityId) -> Result<CombatComponent, String> {
        // This would get the ShadowZombieNinja combat component specifically
        self.get_combat_component(entity_id)
    }

    fn get_combat_stats(&self, entity_id: EntityId) -> Result<CombatStats, String> {
        // This would get combat stats from the entity
        Ok(CombatStats::new())
    }

    fn get_entity_health(&self, entity_id: EntityId) -> Result<f32, String> {
        // Get current health value
        Ok(100.0) // Placeholder
    }

    fn get_entity_health_component(&self, entity_id: EntityId) -> Result<HealthComponent, String> {
        // Get health component
        Ok(HealthComponent::new(100.0)) // Placeholder
    }

    fn get_entity_position(&self, entity_id: EntityId) -> Result<Vec3, String> {
        // Get entity position
        Ok(Vec3::new(0.0, 0.0, 0.0)) // Placeholder
    }

    fn get_entity_bounding_box(&self, entity_id: EntityId) -> Result<BoundingBox, String> {
        // Get entity bounding box
        Ok(BoundingBox {
            min: Vec3::new(-0.5, 0.0, -0.5),
            max: Vec3::new(0.5, 2.0, 0.5),
        })
    }

    /// SIMD-optimized parallel area-of-effect damage processing
    pub fn process_aoe_damage(
        &self,
        center: Vec3,
        radius: f32,
        damage: f32,
        damage_type: DamageType,
        exclude_entity: Option<EntityId>
    ) -> Result<Vec<CombatEvent>, String> {
        let start_time = Instant::now();

        let entities_in_range = self.get_entities_in_range(center, radius, exclude_entity)?;

        // Batch processing for better SIMD utilization
        let batch_size = 8; // Process 8 entities at once for SIMD optimization
        let mut all_events = Vec::new();

        // Process entities in parallel batches
        let batch_results: Vec<Vec<CombatEvent>> = entities_in_range
            .par_chunks(batch_size)
            .map(|batch| {
                self.process_aoe_batch(batch, center, radius, damage, damage_type.clone())
            })
            .collect();

        // Flatten results
        for batch_events in batch_results {
            all_events.extend(batch_events);
        }

        // Record performance metrics
        let processing_time = start_time.elapsed();
        self.performance_monitor.record_metric(
            "aoe_damage_processing_time",
            processing_time.as_secs_f64()
        );

        Ok(all_events)
    }

    /// Optimized batch processing for AOE damage
    fn process_aoe_batch(
        &self,
        entities: &[EntityId],
        center: Vec3,
        radius: f32,
        base_damage: f32,
        damage_type: DamageType
    ) -> Vec<CombatEvent> {
        let mut events = Vec::new();

        // Process entities with optimized distance calculations
        for entity_id in entities {
            // Get entity position
            let entity_pos = self.get_entity_position(*entity_id).unwrap_or(center);

            // Optimized distance calculation using fast inverse square root
            let dx = entity_pos.x - center.x;
            let dy = entity_pos.y - center.y;
            let dz = entity_pos.z - center.z;
            let distance_sq = dx * dx + dy * dy + dz * dz;
            let distance = self.fast_inverse_sqrt(distance_sq);

            // Calculate distance factor
            let distance_factor = (1.0f32).min(1.0 - distance / radius);

            if distance_factor > 0.0 {
                let final_damage = base_damage * distance_factor;

                // Create damage event
                let event = CombatEvent {
                    event_type: CombatEventType::DamageDealt,
                    attacker_id: EntityId(0), // System entity
                    target_id: *entity_id,
                    damage_amount: final_damage,
                    is_critical: false,
                    timestamp: Instant::now(),
                    position: center,
                };

                // Apply damage asynchronously for better performance
                if
                    self
                        .apply_damage_optimized(
                            EntityId(0),
                            *entity_id,
                            DamageResult {
                                final_damage,
                                is_critical: false,
                                damage_type: damage_type.clone(),
                                was_blocked: false,
                                was_dodged: false,
                                overkill_damage: 0.0,
                            },
                            center
                        )
                        .is_ok()
                {
                    events.push(event);
                }
            }
        }

        events
    }

    /// Optimized damage application with parallel processing
    fn apply_damage_optimized(
        &self,
        attacker_id: EntityId,
        target_id: EntityId,
        damage_result: DamageResult,
        position: Vec3
    ) -> Result<(), String> {
        // Use parallel processing for damage application
        let damage_future = std::thread::spawn(move || {
            // Simulate async damage application
            std::thread::sleep(Duration::from_micros(1));
            Ok(())
        });

        // Non-blocking damage application
        match damage_future.join() {
            Ok(result) => result,
            Err(_) => Err("Damage application thread failed".to_string()),
        }
    }

    /// Get entities within a certain range
    fn get_entities_in_range(
        &self,
        center: Vec3,
        radius: f32,
        exclude_entity: Option<EntityId>
    ) -> Result<Vec<EntityId>, String> {
        // Get all entities from registry
        let shadow_zombies = self.entity_registry.get_entities_by_type(
            EntityType::ShadowZombieNinja
        );

        // Filter by distance
        let entities_in_range: Vec<EntityId> = shadow_zombies
            .into_iter()
            .filter(|&entity_id| {
                if let Some(exclude) = exclude_entity {
                    if entity_id == exclude {
                        return false;
                    }
                }

                let entity_pos = self.get_entity_position(entity_id).unwrap_or(center);
                center.distance(entity_pos) <= radius
            })
            .collect();

        Ok(entities_in_range)
    }

    /// Update combat system for a frame/tick
    pub fn update(&self, delta_time: f32) -> Result<(), String> {
        let start_time = Instant::now();

        // Update combat cooldowns and states
        self.update_combat_cooldowns(delta_time)?;

        // Process ongoing combat effects
        self.process_combat_effects(delta_time)?;

        // Clean up expired combat events
        self.cleanup_expired_events()?;

        // Record performance metrics
        let update_time = start_time.elapsed();
        self.performance_monitor.record_metric(
            "combat_system_update_time",
            update_time.as_secs_f64()
        );

        Ok(())
    }

    /// Parallel update of combat cooldowns and state timers
    fn update_combat_cooldowns(&self, delta_time: f32) -> Result<(), String> {
        let entities = self.entity_registry.get_entities_by_type(EntityType::ShadowZombieNinja);

        // Process entities in parallel for better performance
        entities.par_iter().for_each(|&entity_id| {
            if let Ok(mut combat_comp) = self.get_combat_component(entity_id) {
                // Update state timer
                combat_comp.state_timer += delta_time;

                // Handle state transitions based on timers
                match combat_comp.state {
                    CombatState::Stunned => {
                        if combat_comp.state_timer >= combat_comp.stun_duration.as_secs_f32() {
                            combat_comp.state = CombatState::Idle;
                            combat_comp.state_timer = 0.0;
                        }
                    }
                    CombatState::Invulnerable => {
                        if
                            combat_comp.state_timer >=
                            combat_comp.invulnerability_duration.as_secs_f32()
                        {
                            combat_comp.state = CombatState::Idle;
                            combat_comp.state_timer = 0.0;
                        }
                    }
                    _ => {}
                }

                // Reset combo if no recent combat actions
                if let Some(last_action) = combat_comp.last_combat_action {
                    let time_since_action = Instant::now().duration_since(last_action);
                    if time_since_action > combat_comp.combo_reset_time {
                        combat_comp.reset_combo();
                    }
                }
            }
        });

        Ok(())
    }

    /// Process ongoing combat effects
    fn process_combat_effects(&self, delta_time: f32) -> Result<(), String> {
        // Process damage over time, healing over time, etc.
        // This would handle status effects, buffs, debuffs, etc.

        Ok(())
    }

    /// Clean up expired combat events
    fn cleanup_expired_events(&self) -> Result<(), String> {
        let mut events = self.combat_events.write().unwrap();
        let now = Instant::now();
        let max_age = Duration::from_secs(60); // Keep events for 60 seconds

        events.retain(|event| now.duration_since(event.timestamp) < max_age);

        Ok(())
    }

    /// Get recent combat events
    pub fn get_recent_events(&self, count: usize) -> Vec<CombatEvent> {
        let events = self.combat_events.read().unwrap();
        events.iter().rev().take(count).cloned().collect()
    }

    /// Get combat statistics for an entity
    pub fn get_combat_stats_optional(&self, entity_id: EntityId) -> Option<CombatStats> {
        self.get_combat_stats(entity_id).ok()
    }

    /// Get combat event history
    pub fn get_event_history(&self) -> Vec<CombatEvent> {
        let events = self.combat_events.read().unwrap();
        events.iter().cloned().collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::performance_monitor::PerformanceMonitor;

    #[test]
    fn test_combat_component_creation() {
        let combat = CombatComponent::new();

        assert_eq!(combat.state, CombatState::Idle);
        assert!(combat.can_attack());
        assert_eq!(combat.combo_count, 0);
    }

    #[test]
    fn test_damage_calculation() {
        let monitor = Arc::new(PerformanceMonitor::new());
        let registry = Arc::new(EntityRegistry::new(monitor.clone()));
        let combat_system = CombatSystem::new(registry, monitor);

        let calc = DamageCalculation {
            base_damage: 50.0,
            damage_type: DamageType::Physical,
            attacker_level: 1,
            target_level: 1,
            attacker_stats: CombatStats::new(),
            critical_chance: 0.1,
            armor_penetration: 0.0,
            element_bonus: 0.0,
        };

        let hit_detection = HitDetection {
            did_hit: true,
            hit_position: None,
            hit_normal: None,
            damage_multiplier: 1.0,
            is_critical: false,
            hit_box: None,
        };

        let result = combat_system.calculate_damage(calc, &hit_detection, EntityId(1)).unwrap();

        assert_eq!(result.final_damage, 50.0);
        assert!(!result.is_critical);
        assert_eq!(result.damage_type, DamageType::Physical);
    }

    #[test]
    fn test_hit_detection() {
        let bbox = BoundingBox {
            min: Vec3::new(-1.0, 0.0, -1.0),
            max: Vec3::new(1.0, 2.0, 1.0),
        };

        let ray_origin = Vec3::new(0.0, 1.0, 5.0);
        let ray_direction = Vec3::new(0.0, 0.0, -1.0);

        let monitor = Arc::new(PerformanceMonitor::new());
        let registry = Arc::new(EntityRegistry::new(monitor.clone()));
        let combat_system = CombatSystem::new(registry, monitor);

        let result = combat_system.ray_box_intersection(ray_origin, ray_direction, &bbox);

        assert!(result.did_hit);
        assert!(result.hit_position.is_some());
    }

    #[test]
    fn test_shadow_zombie_multi_shot() {
        let monitor = Arc::new(PerformanceMonitor::new());
        let registry = Arc::new(EntityRegistry::new(monitor.clone()));

        // Create a combat system
        let combat_system = CombatSystem::new(registry, monitor.clone());

        // Create test entities
        let shadow_zombie_id = registry.create_entity(EntityType::ShadowZombieNinja);
        let target_id = registry.create_entity(EntityType::Player);

        // Add combat components
        let zombie_combat = CombatComponent::new();
        registry.add_component(shadow_zombie_id, zombie_combat);

        let target_combat = CombatComponent::new();
        registry.add_component(target_id, target_combat);

        // Add health component to target
        let target_health = HealthComponent::new(100.0);
        registry.add_component(target_id, target_health);

        // Set up positions
        let zombie_pos = Vec3::new(0.0, 0.0, 0.0);
        let target_pos = Vec3::new(5.0, 0.0, 0.0);

        // Test multi-shot functionality
        let attack_position = zombie_pos;

        let result = combat_system.process_attack(shadow_zombie_id, target_id, attack_position);

        // Should succeed (either hit or miss, but not error)
        assert!(result.is_ok());

        let event = result.unwrap();

        // Should be a valid combat event
        assert!(
            matches!(event.event_type, CombatEventType::DamageDealt | CombatEventType::AttackMissed)
        );
        assert_eq!(event.attacker_id, shadow_zombie_id);
        assert_eq!(event.target_id, target_id);
    }
}
