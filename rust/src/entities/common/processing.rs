use super::state_manager::{EntityState, StateManager};
use crate::errors::{RustError, Result};
use crate::jni::bridge::JniBridge;
use crate::parallelism::work_stealing::{WorkStealingExecutor, WorkStealingTask};
use crate::simd_enhanced::SimdFloat;
use rayon::prelude::*;
use std::sync::{Arc, RwLock};
use std::time::Instant;

/// Base trait for entity processing
pub trait EntityProcessor: Send + Sync {
    /// Process a single entity
    fn process_entity(&self, entity: &mut EntityState) -> Result<()>;
    
    /// Process multiple entities in parallel
    fn process_entities(&self, entities: &mut [EntityState]) -> Result<Vec<ProcessingResult>>;
    
    /// Get processor name for metrics
    fn name(&self) -> &str;
}

/// Result of entity processing
#[derive(Debug, Clone)]
pub struct ProcessingResult {
    pub entity_id: u64,
    pub status: ProcessingStatus,
    pub duration_ms: f64,
    pub version: u64,
}

/// Status of entity processing
#[derive(Debug, Clone, PartialEq)]
pub enum ProcessingStatus {
    Success,
    Failed(RustError),
    Skipped,
    Optimized, // Processed with optimizations (e.g., simplified AI)
}

/// Thread-safe entity processing system with work-stealing
pub struct ThreadSafeEntityProcessor {
    state_manager: Arc<RwLock<StateManager>>,
    jni_bridge: Arc<JniBridge>,
    executor: WorkStealingExecutor,
    config: ProcessingConfig,
    processor_name: String,
}

#[derive(Debug, Clone)]
pub struct ProcessingConfig {
    pub parallelism_factor: usize,
    pub ai_optimization_threshold: f32, // Distance threshold for AI optimization (blocks)
    pub pathfinding_max_steps: usize,
    pub state_snapshot_interval: u64, // Milliseconds between state snapshots
    pub jni_fallback_enabled: bool,   // Fallback to JNI for complex processing
}

impl Default for ProcessingConfig {
    fn default() -> Self {
        Self {
            parallelism_factor: 4,
            ai_optimization_threshold: 100.0, // 100 blocks away = optimize AI
            pathfinding_max_steps: 500,
            state_snapshot_interval: 100,
            jni_fallback_enabled: true,
        }
    }
}

impl ThreadSafeEntityProcessor {
    /// Create a new thread-safe entity processor
    pub fn new(
        state_manager: Arc<RwLock<StateManager>>,
        jni_bridge: Arc<JniBridge>,
        config: Option<ProcessingConfig>,
        processor_name: String,
    ) -> Self {
        let config = config.unwrap_or_default();
        let executor = WorkStealingExecutor::new(config.parallelism_factor);
        
        Self {
            state_manager,
            jni_bridge,
            executor,
            config,
            processor_name,
        }
    }

    /// Get entities in a specific area using spatial partitioning
    pub fn get_entities_in_area(&self, center: (f32, f32, f32), radius: f32) -> Result<Vec<EntityState>> {
        let state_manager = self.state_manager.read().map_err(|e| {
            RustError::StateError(format!("Failed to read state manager: {}", e))
        })?;
        
        let entities = state_manager.get_entities_in_area(center, radius);
        Ok(entities)
    }

    /// Get all active entities
    pub fn get_active_entities(&self) -> Result<Vec<EntityState>> {
        let state_manager = self.state_manager.read().map_err(|e| {
            RustError::StateError(format!("Failed to read state manager: {}", e))
        })?;
        
        let entities = state_manager.get_active_entities();
        Ok(entities)
    }

    /// Process entities with automatic parallelization and optimization
    pub fn process_entities_batch(&self, entity_ids: &[u64]) -> Result<Vec<ProcessingResult>> {
        let trace_id = "entity_batch_processing".to_string();
        let start_time = Instant::now();
        let mut results = Vec::with_capacity(entity_ids.len());

        // Get entities from state manager
        let state_manager = self.state_manager.read().map_err(|e| {
            RustError::StateError(format!("Failed to read state manager: {}", e))
        })?;

        let entities: Vec<EntityState> = entity_ids
            .iter()
            .filter_map(|&id| state_manager.get_entity(id).ok())
            .collect();

        drop(state_manager); // Release read lock early

        // Process entities in parallel using work-stealing
        let processing_results = self.executor.execute(entity_ids.len(), |chunk| {
            let mut chunk_results = Vec::with_capacity(chunk.len());
            
            for &entity_id in chunk {
                let result = self.process_entity_internal(entity_id);
                chunk_results.push(result);
            }
            
            chunk_results
        });

        // Collect and aggregate results
        for result in processing_results {
            results.extend(result);
        }

        let duration = start_time.elapsed().as_millis() as f64;
        self.log_processing_batch(entity_ids.len(), &results, duration, &trace_id);

        Ok(results)
    }

    /// Process a single entity with internal logic
    fn process_entity_internal(&self, entity_id: u64) -> ProcessingResult {
        let trace_id = format!("entity_processing_{}", entity_id);
        let start_time = Instant::now();

        let mut state_manager = self.state_manager.write().map_err(|e| {
            RustError::StateError(format!("Failed to write state manager: {}", e))
        }).unwrap_or_else(|e| {
            return ProcessingResult {
                entity_id,
                status: ProcessingStatus::Failed(e),
                duration_ms: 0.0,
                version: 0,
            }
        });

        let entity = match state_manager.get_entity_mut(entity_id) {
            Some(entity) => entity,
            None => {
                let result = ProcessingResult {
                    entity_id,
                    status: ProcessingStatus::Skipped,
                    duration_ms: 0.0,
                    version: 0,
                };
                return result;
            }
        };

        let version = entity.version();
        let result = self.process_entity_with_optimization(entity);

        // Update entity version if processing succeeded
        if let ProcessingStatus::Success = result.status {
            entity.increment_version();
        }

        let duration = start_time.elapsed().as_millis() as f64;
        let result = ProcessingResult {
            entity_id,
            status: result.status,
            duration_ms: duration,
            version,
        };

        drop(state_manager); // Release write lock

        self.log_processing_entity(entity_id, &result, duration, &trace_id);

        result
    }

    /// Process entity with AI optimization and JNI fallback
    fn process_entity_with_optimization(&self, entity: &mut EntityState) -> ProcessingResult {
        let entity_id = entity.id();
        
        // Check if entity is eligible for AI optimization
        let optimization_status = self.check_ai_optimization(entity);
        
        // Apply optimization if needed
        if let Some(optimization) = optimization_status {
            self.apply_ai_optimization(entity, optimization);
            return ProcessingResult {
                entity_id,
                status: ProcessingStatus::Optimized,
                duration_ms: 0.0,
                version: entity.version(),
            };
        }

        // Try native processing first
        match self.process_entity_natively(entity) {
            Ok(_) => ProcessingResult {
                entity_id,
                status: ProcessingStatus::Success,
                duration_ms: 0.0,
                version: entity.version(),
            },
            Err(e) => {
                // Fallback to JNI if enabled
                if self.config.jni_fallback_enabled {
                    match self.process_entity_via_jni(entity) {
                        Ok(_) => ProcessingResult {
                            entity_id,
                            status: ProcessingStatus::Success,
                            duration_ms: 0.0,
                            version: entity.version(),
                        },
                        Err(jni_err) => ProcessingResult {
                            entity_id,
                            status: ProcessingStatus::Failed(RustError::from(e).chain(jni_err)),
                            duration_ms: 0.0,
                            version: entity.version(),
                        },
                    }
                } else {
                    ProcessingResult {
                        entity_id,
                        status: ProcessingStatus::Failed(e),
                        duration_ms: 0.0,
                        version: entity.version(),
                    }
                }
            }
        }
    }

    /// Check if entity is eligible for AI optimization
    fn check_ai_optimization(&self, entity: &EntityState) -> Option<AiOptimizationType> {
        // For mobs: check distance from nearest player
        // For villagers: check if no active tasks
        if entity.is_mob() {
            let player_distance = self.get_nearest_player_distance(entity.position());
            if player_distance > self.config.ai_optimization_threshold {
                return Some(AiOptimizationType::SimplifiedAi);
            }
        } else if entity.is_villager() {
            if !entity.has_active_task() {
                return Some(AiOptimizationType::PausedAi);
            }
        }

        None
    }

    /// Apply AI optimization to entity
    fn apply_ai_optimization(&self, entity: &mut EntityState, optimization: AiOptimizationType) {
        match optimization {
            AiOptimizationType::SimplifiedAi => {
                entity.set_ai_complexity(0.1); // Reduce AI complexity to 10%
                entity.disable_advanced_ai();
            }
            AiOptimizationType::PausedAi => {
                entity.pause_ai();
                entity.clear_active_tasks();
            }
        }
    }

    /// Get nearest player distance using spatial partitioning
    fn get_nearest_player_distance(&self, entity_position: (f32, f32, f32)) -> f32 {
        let state_manager = self.state_manager.read().unwrap();
        let players = state_manager.get_entities_by_type("Player");
        
        players.iter()
            .map(|player| {
                let dx = player.position().0 - entity_position.0;
                let dy = player.position().1 - entity_position.1;
                let dz = player.position().2 - entity_position.2;
                SimdFloat::sqrt(dx * dx + dy * dy + dz * dz).into()
            })
            .min_by(|a, b| a.partial_cmp(b).unwrap())
            .unwrap_or(self.config.ai_optimization_threshold * 2.0)
    }

    /// Native entity processing (implement in specific processors)
    fn process_entity_natively(&self, _entity: &mut EntityState) -> Result<()> {
        Ok(())
    }

    /// Process entity via JNI bridge (fallback)
    fn process_entity_via_jni(&self, entity: &mut EntityState) -> Result<()> {
        let entity_data = entity.serialize()?;
        let result = self.jni_bridge.process_entity(entity.id(), &entity_data)?;
        entity.deserialize(&result)?;
        Ok(())
    }

    /// Log processing results
    fn log_processing_batch(&self, count: usize, results: &[ProcessingResult], duration: f64, trace_id: &str) {
        let success_count = results.iter().filter(|r| matches!(r.status, ProcessingStatus::Success)).count();
        let optimized_count = results.iter().filter(|r| matches!(r.status, ProcessingStatus::Optimized)).count();
        let failed_count = results.iter().filter(|r| matches!(r.status, ProcessingStatus::Failed(_))).count();
        let skipped_count = results.iter().filter(|r| matches!(r.status, ProcessingStatus::Skipped)).count();

        let avg_duration = if count > 0 { duration / count as f64 } else { 0.0 };

        // In a real implementation, use the actual logging system
        println!("[{}] Processed {} entities in {}ms - Success: {}, Optimized: {}, Failed: {}, Skipped: {}, Avg: {:.2}ms",
            self.processor_name, count, duration, success_count, optimized_count, failed_count, skipped_count, avg_duration);
    }

    /// Log individual entity processing
    fn log_processing_entity(&self, entity_id: u64, result: &ProcessingResult, duration: f64, trace_id: &str) {
        // In a real implementation, use the actual logging system
        println!("[{}] Entity {} processed in {:.2}ms - Status: {:?}, Version: {}",
            self.processor_name, entity_id, duration, result.status, result.version);
    }
}

/// AI optimization types
#[derive(Debug, Clone, PartialEq)]
pub enum AiOptimizationType {
    SimplifiedAi,    // Reduce AI complexity but keep basic behavior
    PausedAi,        // Pause all AI processing
    LimitedMovement, // Restrict movement to current area
}

/// Extension trait for EntityState to add processing-related methods
pub trait EntityProcessingExt {
    /// Check if entity is a mob
    fn is_mob(&self) -> bool;
    
    /// Check if entity is a villager
    fn is_villager(&self) -> bool;
    
    /// Check if entity has active tasks
    fn has_active_task(&self) -> bool;
    
    /// Set AI complexity (0.0 to 1.0)
    fn set_ai_complexity(&mut self, complexity: f32);
    
    /// Disable advanced AI features
    fn disable_advanced_ai(&mut self);
    
    /// Pause AI processing
    fn pause_ai(&mut self);
    
    /// Clear all active tasks
    fn clear_active_tasks(&mut self);
    
    /// Serialize entity state to bytes
    fn serialize(&self) -> Result<Vec<u8>>;
    
    /// Deserialize entity state from bytes
    fn deserialize(&mut self, data: &[u8]) -> Result<()>;
}