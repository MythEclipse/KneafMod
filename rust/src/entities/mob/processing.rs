use super::types::{MobAiComplexity, MobState, MobType};
use crate::entities::common::{
    AiOptimizationType, EntityProcessingExt, EntityProcessor, ProcessingResult, ProcessingStatus,
    ThreadSafeEntityProcessor,
};
use crate::errors::{RustError, Result};
use crate::jni::bridge::JniBridge;
use crate::parallelism::work_stealing::WorkStealingExecutor;
use crate::simd_enhanced::SimdFloat;
use crate::state_manager::{EntityState, StateManager};
use rand::Rng;
use std::sync::{Arc, RwLock};
use std::time::Instant;

/// Thread-safe mob processing system with AI optimization and parallel execution
pub struct MobProcessor {
    base_processor: ThreadSafeEntityProcessor,
    mob_config: MobProcessingConfig,
}

#[derive(Debug, Clone)]
pub struct MobProcessingConfig {
    pub base_movement_speed: f32,
    pub max_ai_complexity: f32,
    pub idle_ai_threshold: f32, // Distance from player to enter idle state
    pub aggressive_ai_threshold: f32, // Distance from player to become aggressive
    pub pathfinding_grid_size: usize,
    pub max_follow_distance: f32,
}

impl Default for MobProcessingConfig {
    fn default() -> Self {
        Self {
            base_movement_speed: 0.5,
            max_ai_complexity: 1.0,
            idle_ai_threshold: 150.0,
            aggressive_ai_threshold: 20.0,
            pathfinding_grid_size: 32,
            max_follow_distance: 50.0,
        }
    }
}

impl MobProcessor {
    /// Create a new thread-safe mob processor
    pub fn new(
        state_manager: Arc<RwLock<StateManager>>,
        jni_bridge: Arc<JniBridge>,
        config: Option<MobProcessingConfig>,
    ) -> Self {
        let mob_config = config.unwrap_or_default();
        let base_config = mob_config.to_base_processing_config();
        let base_processor = ThreadSafeEntityProcessor::new(
            state_manager.clone(),
            jni_bridge,
            Some(base_config),
            "MobProcessor".to_string(),
        );

        Self {
            base_processor,
            mob_config,
        }
    }

    /// Convert MobProcessingConfig to base ProcessingConfig
    fn to_base_processing_config(&self) -> super::common::processing::ProcessingConfig {
        super::common::processing::ProcessingConfig {
            parallelism_factor: 4,
            ai_optimization_threshold: self.mob_config.idle_ai_threshold,
            pathfinding_max_steps: 1000,
            state_snapshot_interval: 100,
            jni_fallback_enabled: true,
        }
    }

    /// Get all mobs from state manager
    pub fn get_all_mobs(&self) -> Result<Vec<EntityState>> {
        let state_manager = self.base_processor.state_manager.read().map_err(|e| {
            RustError::StateError(format!("Failed to read state manager: {}", e))
        })?;
        
        let mobs = state_manager.get_entities_by_type("Mob");
        Ok(mobs)
    }

    /// Get mobs of specific type
    pub fn get_mobs_by_type(&self, mob_type: MobType) -> Result<Vec<EntityState>> {
        let state_manager = self.base_processor.state_manager.read().map_err(|e| {
            RustError::StateError(format!("Failed to read state manager: {}", e))
        })?;
        
        let mobs = state_manager.get_entities_by_type(&mob_type.to_string());
        Ok(mobs)
    }

    /// Process mobs with mob-specific logic
    pub fn process_mobs(&self, mob_ids: &[u64]) -> Result<Vec<ProcessingResult>> {
        let trace_id = "mob_processing_batch".to_string();
        let start_time = Instant::now();

        // Use base processor for parallel execution but with mob-specific logic
        let results = self.base_processor.executor.execute(mob_ids.len(), |chunk| {
            let mut chunk_results = Vec::with_capacity(chunk.len());
            
            for &mob_id in chunk {
                let result = self.process_mob_internal(mob_id);
                chunk_results.push(result);
            }
            
            chunk_results
        });

        let mut all_results = Vec::with_capacity(mob_ids.len());
        for result in results {
            all_results.extend(result);
        }

        let duration = start_time.elapsed().as_millis() as f64;
        self.log_mob_processing_batch(mob_ids.len(), &all_results, duration, &trace_id);

        Ok(all_results)
    }

    /// Process a single mob with mob-specific logic
    fn process_mob_internal(&self, mob_id: u64) -> ProcessingResult {
        let trace_id = format!("mob_processing_{}", mob_id);
        let start_time = Instant::now();

        let mut state_manager = self.base_processor.state_manager.write().map_err(|e| {
            RustError::StateError(format!("Failed to write state manager: {}", e))
        }).unwrap_or_else(|e| {
            return ProcessingResult {
                entity_id: mob_id,
                status: ProcessingStatus::Failed(e),
                duration_ms: 0.0,
                version: 0,
            }
        });

        let mob = match state_manager.get_entity_mut(mob_id) {
            Some(entity) => entity,
            None => {
                let result = ProcessingResult {
                    entity_id: mob_id,
                    status: ProcessingStatus::Skipped,
                    duration_ms: 0.0,
                    version: 0,
                };
                return result;
            }
        };

        let version = mob.version();
        let result = self.process_mob_with_mob_logic(mob);

        // Update entity version if processing succeeded
        if let ProcessingStatus::Success = result.status {
            mob.increment_version();
        }

        let duration = start_time.elapsed().as_millis() as f64;
        let result = ProcessingResult {
            entity_id: mob_id,
            status: result.status,
            duration_ms: duration,
            version,
        };

        drop(state_manager);

        self.log_mob_processing(mob_id, &result, duration, &trace_id);

        result
    }

    /// Process mob with mob-specific AI and behavior logic
    fn process_mob_with_mob_logic(&self, mob: &mut EntityState) -> ProcessingResult {
        let mob_id = mob.id();
        
        // First check if mob is eligible for optimization (from base processor)
        let optimization_status = self.base_processor.check_ai_optimization(mob);
        if let Some(optimization) = optimization_status {
            self.apply_mob_optimization(mob, optimization);
            return ProcessingResult {
                entity_id: mob_id,
                status: ProcessingStatus::Optimized,
                duration_ms: 0.0,
                version: mob.version(),
            };
        }

        // Get mob-specific state
        let mob_state = mob.get_mob_state().ok_or_else(|| {
            RustError::EntityError(format!("Entity {} is not a valid mob", mob_id))
        })?;

        // Determine mob behavior based on player distance
        let player_distance = self.get_nearest_player_distance(mob.position());
        let behavior = self.determine_mob_behavior(player_distance, &mob_state);

        // Execute mob behavior
        match self.execute_mob_behavior(mob, behavior, &mob_state) {
            Ok(_) => ProcessingResult {
                entity_id: mob_id,
                status: ProcessingStatus::Success,
                duration_ms: 0.0,
                version: mob.version(),
            },
            Err(e) => {
                // Fallback to JNI if enabled
                if self.base_processor.config.jni_fallback_enabled {
                    match self.base_processor.process_entity_via_jni(mob) {
                        Ok(_) => ProcessingResult {
                            entity_id: mob_id,
                            status: ProcessingStatus::Success,
                            duration_ms: 0.0,
                            version: mob.version(),
                        },
                        Err(jni_err) => ProcessingResult {
                            entity_id: mob_id,
                            status: ProcessingStatus::Failed(RustError::from(e).chain(jni_err)),
                            duration_ms: 0.0,
                            version: mob.version(),
                        },
                    }
                } else {
                    ProcessingResult {
                        entity_id: mob_id,
                        status: ProcessingStatus::Failed(e),
                        duration_ms: 0.0,
                        version: mob.version(),
                    }
                }
            }
        }
    }

    /// Apply mob-specific AI optimization
    fn apply_mob_optimization(&self, mob: &mut EntityState, optimization: AiOptimizationType) {
        match optimization {
            AiOptimizationType::SimplifiedAi => {
                let mut mob_state = mob.get_mob_state_mut().unwrap();
                mob_state.set_ai_complexity(MobAiComplexity::Low);
                mob_state.disable_advanced_ai_features();
                mob_state.set_movement_speed(self.mob_config.base_movement_speed * 0.5);
            }
            AiOptimizationType::PausedAi => {
                let mut mob_state = mob.get_mob_state_mut().unwrap();
                mob_state.set_ai_complexity(MobAiComplexity::None);
                mob_state.pause_ai();
                mob_state.clear_active_goals();
            }
            AiOptimizationType::LimitedMovement => {
                let mut mob_state = mob.get_mob_state_mut().unwrap();
                mob_state.set_movement_speed(0.0);
                mob_state.limit_movement_to_current_area();
            }
        }
    }

    /// Determine mob behavior based on player distance and mob type
    fn determine_mob_behavior(&self, player_distance: f32, mob_state: &MobState) -> MobBehavior {
        let mob_type = mob_state.get_mob_type();

        match mob_type {
            MobType::Passive => {
                if player_distance < self.mob_config.aggressive_ai_threshold {
                    MobBehavior::Flee
                } else if player_distance < self.mob_config.idle_ai_threshold {
                    MobBehavior::Idle
                } else {
                    MobBehavior::Wander
                }
            }
            MobType::Neutral => {
                if player_distance < self.mob_config.aggressive_ai_threshold {
                    MobBehavior::Attack
                } else if player_distance < self.mob_config.idle_ai_threshold {
                    MobBehavior::Investigate
                } else {
                    MobBehavior::Wander
                }
            }
            MobType::Hostile => {
                if player_distance < self.mob_config.max_follow_distance {
                    MobBehavior::Attack
                } else {
                    MobBehavior::Patrol
                }
            }
            MobType::Boss => MobBehavior::EliteCombat,
        }
    }

    /// Execute mob behavior logic
    fn execute_mob_behavior(&self, mob: &mut EntityState, behavior: MobBehavior, mob_state: &MobState) -> Result<()> {
        match behavior {
            MobBehavior::Wander => self.execute_wander_behavior(mob, mob_state),
            MobBehavior::Idle => self.execute_idle_behavior(mob, mob_state),
            MobBehavior::Attack => self.execute_attack_behavior(mob, mob_state),
            MobBehavior::Flee => self.execute_flee_behavior(mob, mob_state),
            MobBehavior::Investigate => self.execute_investigate_behavior(mob, mob_state),
            MobBehavior::Patrol => self.execute_patrol_behavior(mob, mob_state),
            MobBehavior::EliteCombat => self.execute_elite_combat_behavior(mob, mob_state),
        }
    }

    /// Execute wander behavior (random movement)
    fn execute_wander_behavior(&self, mob: &mut EntityState, _mob_state: &MobState) -> Result<()> {
        let mut rng = rand::thread_rng();
        let (x, y, z) = mob.position();
        
        // Generate random wander direction within a reasonable radius
        let wander_radius = 5.0;
        let new_x = x + rng.gen_range(-wander_radius..wander_radius);
        let new_z = z + rng.gen_range(-wander_radius..wander_radius);
        let new_y = y; // Keep y consistent for now
        
        // Update mob position
        mob.set_position((new_x, new_y, new_z));
        
        // Update movement state
        let mut mob_state = mob.get_mob_state_mut().unwrap();
        mob_state.set_moving(true);
        mob_state.set_destination((new_x, new_y, new_z));
        
        Ok(())
    }

    /// Execute idle behavior (stand still)
    fn execute_idle_behavior(&self, mob: &mut EntityState, _mob_state: &MobState) -> Result<()> {
        let mut mob_state = mob.get_mob_state_mut().unwrap();
        mob_state.set_moving(false);
        mob_state.clear_destination();
        
        Ok(())
    }

    /// Execute attack behavior (move towards and attack nearest player)
    fn execute_attack_behavior(&self, mob: &mut EntityState, mob_state: &MobState) -> Result<()> {
        let (mob_x, mob_y, mob_z) = mob.position();
        let target_position = self.get_nearest_player_position()?;
        let (target_x, target_y, target_z) = target_position;
        
        // Calculate movement towards target
        let dx = target_x - mob_x;
        let dy = target_y - mob_y;
        let dz = target_z - mob_z;
        let distance = SimdFloat::sqrt(dx * dx + dy * dy + dz * dz).into();
        
        // Move towards target if not already close enough
        if distance > 1.0 {
            let move_x = mob_x + dx * 0.1; // 10% speed towards target
            let move_y = mob_y + dy * 0.1;
            let move_z = mob_z + dz * 0.1;
            
            mob.set_position((move_x, move_y, move_z));
            
            let mut mob_state_mut = mob.get_mob_state_mut().unwrap();
            mob_state_mut.set_moving(true);
            mob_state_mut.set_destination(target_position);
        } else {
            // Perform attack when close enough
            self.perform_attack(mob, mob_state)?;
        }
        
        Ok(())
    }

    /// Execute flee behavior (run away from nearest player)
    fn execute_flee_behavior(&self, mob: &mut EntityState, _mob_state: &MobState) -> Result<()> {
        let (mob_x, mob_y, mob_z) = mob.position();
        let target_position = self.get_nearest_player_position()?;
        let (target_x, target_y, target_z) = target_position;
        
        // Calculate movement away from target
        let dx = mob_x - target_x; // Reverse direction from attack
        let dy = mob_y - target_y;
        let dz = mob_z - target_z;
        let distance = SimdFloat::sqrt(dx * dx + dy * dy + dz * dz).into();
        
        // Move away from target
        if distance < self.mob_config.idle_ai_threshold {
            let flee_speed = self.mob_config.base_movement_speed * 1.5; // Faster than normal
            let move_x = mob_x + dx * flee_speed * 0.1;
            let move_y = mob_y + dy * flee_speed * 0.1;
            let move_z = mob_z + dz * flee_speed * 0.1;
            
            mob.set_position((move_x, move_y, move_z));
            
            let mut mob_state = mob.get_mob_state_mut().unwrap();
            mob_state.set_moving(true);
        } else {
            let mut mob_state = mob.get_mob_state_mut().unwrap();
            mob_state.set_moving(false);
        }
        
        Ok(())
    }

    /// Execute investigate behavior (move towards last seen player)
    fn execute_investigate_behavior(&self, mob: &mut EntityState, _mob_state: &MobState) -> Result<()> {
        // In a real implementation, this would use last known player position
        // For now, use wander behavior as a placeholder
        self.execute_wander_behavior(mob, _mob_state)
    }

    /// Execute patrol behavior (move between waypoints)
    fn execute_patrol_behavior(&self, mob: &mut EntityState, mob_state: &MobState) -> Result<()> {
        let has_waypoints = mob_state.has_patrol_waypoints();
        
        if has_waypoints {
            // Get next patrol waypoint
            let next_waypoint = mob_state.get_next_patrol_waypoint().unwrap();
            
            // Move towards waypoint
            let (mob_x, mob_y, mob_z) = mob.position();
            let (wp_x, wp_y, wp_z) = next_waypoint;
            
            let dx = wp_x - mob_x;
            let dy = wp_y - mob_y;
            let dz = wp_z - mob_z;
            let distance = SimdFloat::sqrt(dx * dx + dy * dy + dz * dz).into();
            
            if distance > 1.0 {
                let move_x = mob_x + dx * 0.1;
                let move_y = mob_y + dy * 0.1;
                let move_z = mob_z + dz * 0.1;
                
                mob.set_position((move_x, move_y, move_z));
                
                let mut mob_state_mut = mob.get_mob_state_mut().unwrap();
                mob_state_mut.set_moving(true);
                mob_state_mut.set_destination(next_waypoint);
            } else {
                // Reached waypoint, get next one
                mob_state.complete_patrol_waypoint();
                let mut mob_state_mut = mob.get_mob_state_mut().unwrap();
                mob_state_mut.set_moving(false);
            }
        } else {
            // No waypoints, wander instead
            self.execute_wander_behavior(mob, mob_state)
        }
        
        Ok(())
    }

    /// Execute elite combat behavior (complex attack patterns for bosses)
    fn execute_elite_combat_behavior(&self, mob: &mut EntityState, mob_state: &MobState) -> Result<()> {
        // In a real implementation, this would have complex attack patterns,
        // cooldowns, special abilities, etc.
        // For now, use attack behavior as a placeholder
        self.execute_attack_behavior(mob, mob_state)
    }

    /// Perform attack on target
    fn perform_attack(&self, mob: &mut EntityState, mob_state: &MobState) -> Result<()> {
        // In a real implementation, this would calculate attack damage,
        // apply effects, play animations, etc.
        let mob_id = mob.id();
        let target_position = self.get_nearest_player_position()?;
        
        // Log attack (in real implementation, use proper logging system)
        println!("[MobProcessor] Mob {} attacking at position {:?}", mob_id, target_position);
        
        // Update mob state to show attacking
        let mut mob_state_mut = mob.get_mob_state_mut().unwrap();
        mob_state_mut.set_attacking(true);
        
        Ok(())
    }

    /// Get nearest player position
    fn get_nearest_player_position(&self) -> Result<(f32, f32, f32)> {
        let state_manager = self.base_processor.state_manager.read().unwrap();
        let players = state_manager.get_entities_by_type("Player");
        
        players.iter()
            .min_by(|a, b| {
                let dist_a = a.distance_to_player().unwrap_or(f32::MAX);
                let dist_b = b.distance_to_player().unwrap_or(f32::MAX);
                dist_a.partial_cmp(&dist_b).unwrap()
            })
            .map(|player| player.position())
            .ok_or_else(|| {
                RustError::EntityError("No players found for mob targeting".to_string())
            })
    }

    /// Log mob processing results
    fn log_mob_processing_batch(&self, count: usize, results: &[ProcessingResult], duration: f64, trace_id: &str) {
        let success_count = results.iter().filter(|r| matches!(r.status, ProcessingStatus::Success)).count();
        let optimized_count = results.iter().filter(|r| matches!(r.status, ProcessingStatus::Optimized)).count();
        let failed_count = results.iter().filter(|r| matches!(r.status, ProcessingStatus::Failed(_))).count();
        let skipped_count = results.iter().filter(|r| matches!(r.status, ProcessingStatus::Skipped)).count();

        let avg_duration = if count > 0 { duration / count as f64 } else { 0.0 };

        println!("[MobProcessor] Processed {} mobs in {}ms - Success: {}, Optimized: {}, Failed: {}, Skipped: {}, Avg: {:.2}ms",
            count, duration, success_count, optimized_count, failed_count, skipped_count, avg_duration);
    }

    /// Log individual mob processing
    fn log_mob_processing(&self, mob_id: u64, result: &ProcessingResult, duration: f64, trace_id: &str) {
        println!("[MobProcessor] Mob {} processed in {:.2}ms - Status: {:?}, Version: {}",
            mob_id, duration, result.status, result.version);
    }
}

/// Mob behavior types
#[derive(Debug, Clone, PartialEq)]
pub enum MobBehavior {
    Wander,      // Random movement
    Idle,        // Stand still
    Attack,      // Attack nearest player
    Flee,        // Run from nearest player
    Investigate, // Investigate last player position
    Patrol,      // Patrol between waypoints
    EliteCombat, // Complex combat for bosses
}

/// Extension trait for EntityState to add mob-specific methods
pub trait MobEntityExt: EntityProcessingExt {
    /// Get mob state
    fn get_mob_state(&self) -> Result<MobState>;
    
    /// Get mutable mob state
    fn get_mob_state_mut(&mut self) -> Result<&mut MobState>;
    
    /// Set mob state
    fn set_mob_state(&mut self, state: MobState) -> Result<()>;
    
    /// Distance to nearest player
    fn distance_to_player(&self) -> Result<f32>;
}
