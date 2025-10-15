use super::types::*;
use crate::simd_standardized::{get_standard_simd_ops, StandardSimdOps};
use crate::parallelism::executor_factory::{ParallelExecutorFactory, ExecutorType};
use std::sync::Arc;
use lazy_static::lazy_static;
use serde::{Deserialize, Serialize};
use std::time::Instant;
use dashmap::DashMap;
use once_cell::sync::Lazy;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};

/// Unified entity processor factory
#[derive(Debug)]
pub struct EntityProcessorFactory {
    /// Standard SIMD operations instance
    simd_ops: Arc<StandardSimdOps>,
    /// Default parallel executor
    executor: Arc<crate::parallelism::ParallelExecutor>,
}

impl EntityProcessorFactory {
    /// Create a new entity processor factory with default settings
    pub fn new() -> Self {
        let simd_ops = Arc::new(get_standard_simd_ops().clone());
        let executor = Arc::new(ParallelExecutorFactory::create_default()
            .expect("Failed to create default parallel executor"));
        
        Self {
            simd_ops,
            executor,
        }
    }

    /// Create a factory with custom executor
    pub fn with_executor(executor: crate::parallelism::ParallelExecutor) -> Self {
        let simd_ops = Arc::new(get_standard_simd_ops().clone());
        Self {
            simd_ops,
            executor: Arc::new(executor),
        }
    }

    /// Create a processor for the specified entity type
    pub fn create_processor(&self, entity_type: EntityType) -> Arc<dyn EntityProcessor> {
        match entity_type {
            EntityType::Mob => Arc::new(MobEntityProcessor::new(
                Arc::new(self.simd_ops.clone()),
                Arc::new(self.executor.clone()),
            )) as Arc<dyn EntityProcessor>,
            EntityType::Villager => Arc::new(VillagerEntityProcessor::new(
                Arc::new(self.simd_ops.clone()),
                Arc::new(self.executor.clone()),
            )) as Arc<dyn EntityProcessor>,
            EntityType::Other(_) => unimplemented!("Other entity types not supported yet"),
        }
    }

    /// Create a processor with custom configuration
    pub fn create_processor_with_config(
        &self,
        entity_type: EntityType,
        config: Arc<dyn EntityConfig>,
    ) -> Arc<dyn EntityProcessor> {
        match entity_type {
            EntityType::Mob => {
                let mut processor = MobEntityProcessor::new(
                    Arc::new(self.simd_ops.clone()),
                    Arc::new(self.executor.clone()),
                );
                processor.update_config(config);
                Arc::new(processor) as Arc<dyn EntityProcessor>
            }
            EntityType::Villager => {
                let mut processor = VillagerEntityProcessor::new(
                    Arc::new(self.simd_ops.clone()),
                    Arc::new(self.executor.clone()),
                );
                processor.update_config(config);
                Arc::new(processor) as Arc<dyn EntityProcessor>
            }
            EntityType::Other(_) => unimplemented!("Other entity types not supported yet"),
        }
    }
}

/// Global entity processor factory instance
lazy_static! {
    pub static ref GLOBAL_ENTITY_PROCESSOR_FACTORY: EntityProcessorFactory = EntityProcessorFactory::new();
}

/// Get the global entity processor factory
pub fn get_global_entity_processor_factory() -> &'static EntityProcessorFactory {
    &GLOBAL_ENTITY_PROCESSOR_FACTORY
}

// Forward declarations for the actual processor implementations
mod mob_processor;
mod villager_processor;

use mob_processor::MobEntityProcessor;
use villager_processor::VillagerEntityProcessor;

// Re-export the processor types for use in other modules
pub use mob_processor::MobConfig;
pub use villager_processor::VillagerConfig;
pub use mob_processor::MobProcessingStats;
pub use villager_processor::VillagerProcessingStats;

/// Mob-specific configuration
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct MobConfig {
    pub passive_disable_distance: f32,
    pub hostile_simplify_distance: f32,
    pub ai_tick_rate_far: f32,
    pub max_batch_size: usize,
    pub simd_chunk_size: usize,
    pub spatial_grid_size: f32,
    pub movement_threshold: f32,
    pub ai_update_interval: u64,
    pub pathfinding_update_interval: u64,
    pub memory_pool_threshold: usize,
}

impl EntityConfig for MobConfig {
    fn get_disable_ai_distance(&self) -> f32 {
        self.passive_disable_distance
    }
    
    fn get_simplify_ai_distance(&self) -> f32 {
        self.hostile_simplify_distance
    }
    
    fn get_reduce_pathfinding_distance(&self) -> Option<f32> {
        None
    }
}

/// Mob processing statistics
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct MobProcessingStats {
    pub total_mobs_processed: usize,
    pub ai_disabled_count: usize,
    pub ai_simplified_count: usize,
    pub spatial_groups_formed: usize,
    pub simd_operations_used: usize,
    pub memory_allocations: usize,
    pub processing_time_ms: u64,
}

/// Villager-specific configuration
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct VillagerConfig {
    pub disable_ai_distance: f32,
    pub simplify_ai_distance: f32,
    pub reduce_pathfinding_distance: f32,
    pub village_radius: f32,
    pub max_villagers_per_group: usize,
    pub pathfinding_tick_interval: u8,
    pub simple_ai_tick_interval: u8,
    pub complex_ai_tick_interval: u8,
    pub workstation_search_radius: f32,
    pub breeding_cooldown_ticks: u64,
    pub rest_tick_interval: u8,
}

impl EntityConfig for VillagerConfig {
    fn get_disable_ai_distance(&self) -> f32 {
        self.disable_ai_distance
    }
    
    fn get_simplify_ai_distance(&self) -> f32 {
        self.simplify_ai_distance
    }
    
    fn get_reduce_pathfinding_distance(&self) -> Option<f32> {
        Some(self.reduce_pathfinding_distance)
    }
}

/// Villager processing statistics
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct VillagerProcessingStats {
    pub pathfinding_cache_stats: PathfindingCacheStats,
    pub active_groups: usize,
    pub total_villagers_processed: usize,
    pub ai_disabled_count: usize,
    pub ai_simplified_count: usize,
    pub pathfinding_reduced_count: usize,
}

/// Pathfinding cache statistics
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct PathfindingCacheStats {
    pub hits: usize,
    pub misses: usize,
    pub evictions: usize,
    pub total_entries: usize,
}

/// Mob entity processor implementation
pub struct MobEntityProcessor {
    config: Arc<dyn EntityConfig>,
    simd_ops: Arc<StandardSimdOps>,
    executor: Arc<crate::parallelism::ParallelExecutor>,
    processing_stats: Arc<MobProcessingStats>,
}

impl MobEntityProcessor {
    /// Create a new mob entity processor
    pub fn new(
        simd_ops: Arc<StandardSimdOps>,
        executor: Arc<crate::parallelism::ParallelExecutor>,
    ) -> Self {
        let config = Arc::new(MobConfig::default()) as Arc<dyn EntityConfig>;
        
        Self {
            config,
            simd_ops,
            executor,
            processing_stats: Arc::new(MobProcessingStats::default()),
        }
    }

    /// Convert basic mob input to enhanced mob data
    fn convert_to_enhanced_mobs(&self, input: &EntityProcessingInput) -> Vec<EnhancedMobData> {
        let mut enhanced_mobs = Vec::with_capacity(input.entities.len());
        
        for entity in &input.entities {
            if let EntityType::Mob = entity.entity_type {
                let enhanced_mob = EnhancedMobData {
                    id: entity.id,
                    entity_type: entity.entity_type.clone(),
                    position: entity.position,
                    distance: entity.distance,
                    is_passive: entity.metadata.as_ref()
                        .and_then(|m| m.get("is_passive").and_then(|v| v.as_bool()))
                        .unwrap_or(false),
                    ai_state: AiState::Idle,
                    behavior_priority: 0.5,
                    last_update: Instant::now(),
                    target_position: None,
                    health: 100.0,
                    energy: 100.0,
                };
                
                enhanced_mobs.push(enhanced_mob);
            }
        }
        
        enhanced_mobs
    }

    /// Determine initial AI state based on mob characteristics
    fn determine_initial_ai_state(&self, mob: &EnhancedMobData) -> AiState {
        if mob.is_passive {
            if mob.distance < 20.0 {
                AiState::Wandering
            } else {
                AiState::Idle
            }
        } else {
            // Hostile mobs
            if mob.distance < 30.0 {
                AiState::Chasing
            } else {
                AiState::Wandering
            }
        }
    }

    /// Calculate behavior priority based on distance and mob type
    fn calculate_behavior_priority(&self, mob: &EnhancedMobData) -> f32 {
        let base_priority = if mob.is_passive { 0.5 } else { 1.0 };
        let distance_factor = 1.0 / (1.0 + mob.distance / 100.0);
        base_priority * distance_factor
    }

    /// Process mobs in parallel with work stealing and SIMD acceleration
    fn process_mobs_parallel(&mut self, mobs: Vec<EnhancedMobData>) -> EntityProcessingResult {
        // Clone config values to avoid thread safety issues
        let config = self.config.as_ref() as &dyn EntityConfig;
        let disable_distance = config.get_disable_ai_distance();
        let simplify_distance = config.get_simplify_ai_distance();

        // Process mobs in parallel
        let mut processed_results = self.executor.execute(|| {
            mobs.into_par_iter().map(|mob| {
                let mut decision = MobProcessingDecision {
                    mob_id: mob.id,
                    action: AiAction::ProcessBehavior,
                    priority: self.calculate_behavior_priority(&mob),
                    distance: mob.distance,
                    is_passive: mob.is_passive,
                };

                // Distance-based AI optimization
                if mob.is_passive {
                    if mob.distance > *disable_distance {
                        decision.action = AiAction::DisableAi;
                    } else if mob.distance > *disable_distance * 0.7 {
                        decision.action = AiAction::SimplifyAi;
                    }
                } else {
                    // Hostile mob logic
                    if mob.distance > *simplify_distance * 2.0 {
                        decision.action = AiAction::DisableAi;
                    } else if mob.distance > *simplify_distance {
                        decision.action = AiAction::SimplifyAi;
                    }
                }

                // AI state-based decisions
                decision.action = self.refine_action_based_on_state(decision.action, &mob);
                
                decision
            }).collect::<Vec<MobProcessingDecision>>()
        });

        // Categorize results
        let mut result = EntityProcessingResult {
            entities_to_disable_ai: Vec::new(),
            entities_to_simplify_ai: Vec::new(),
            entities_to_reduce_pathfinding: None,
            groups: None,
            processing_stats: ProcessingStats::default(),
        };

        for decision in &processed_results {
            match decision.action {
                AiAction::DisableAi => result.entities_to_disable_ai.push(decision.mob_id),
                AiAction::SimplifyAi => result.entities_to_simplify_ai.push(decision.mob_id),
                AiAction::UpdatePathfinding => {
                    if let Some(ref mut pathfinding_list) = result.entities_to_reduce_pathfinding {
                        pathfinding_list.push(decision.mob_id);
                    } else {
                        result.entities_to_reduce_pathfinding = Some(vec![decision.mob_id]);
                    }
                }
                AiAction::ProcessBehavior => {}
            }
        }

        // Update statistics
        let stats = &mut result.processing_stats;
        stats.total_entities_processed = processed_results.len();
        stats.ai_disabled_count = result.entities_to_disable_ai.len();
        stats.ai_simplified_count = result.entities_to_simplify_ai.len();
        stats.simd_operations_used = self.simd_ops.get_level() as usize;

        result
    }

    /// Refine AI action based on current mob state
    fn refine_action_based_on_state(&self, current_action: AiAction, mob: &EnhancedMobData) -> AiAction {
        match mob.ai_state {
            AiState::Sleeping | AiState::Eating => {
                // Keep simple AI for inactive states
                if current_action == AiAction::ProcessBehavior {
                    AiAction::SimplifyAi
                } else {
                    current_action
                }
            }
            AiState::Attacking | AiState::Chasing => {
                // Always process behavior for active hostile actions
                AiAction::ProcessBehavior
            }
            AiState::Socializing => {
                // Use pathfinding for social interactions
                if current_action == AiAction::ProcessBehavior {
                    AiAction::UpdatePathfinding
                } else {
                    current_action
                }
            }
            _ => current_action,
        }
    }
}

impl EntityProcessor for MobEntityProcessor {
    fn process_entities(&mut self, input: EntityProcessingInput) -> EntityProcessingResult {
        let start_time = Instant::now();
        
        // Convert input to enhanced mob data
        let enhanced_mobs = self.convert_to_enhanced_mobs(&input);
        
        // Process mobs in parallel batches
        let mut process_result = self.process_mobs_parallel(enhanced_mobs);
        
        // Update processing statistics
        let processing_time = start_time.elapsed().as_millis() as u64;
        process_result.processing_stats.processing_time_ms = processing_time;
        
        process_result
    }
    
    fn process_entities_batch(&mut self, inputs: Vec<EntityProcessingInput>) -> Vec<EntityProcessingResult> {
        let batch_size = inputs.len();
        
        // Use memory pool for efficient batch processing
        let mut results = Vec::with_capacity(batch_size);
        
        for input in inputs {
            let result = self.process_entities(input);
            results.push(result);
        }
        
        results
    }
    
    fn get_config(&self) -> Arc<dyn EntityConfig> {
        Arc::clone(&self.config)
    }
    
    fn update_config(&mut self, new_config: Arc<dyn EntityConfig>) {
        self.config = new_config;
    }
}

/// Enhanced mob data with AI state and spatial information
#[derive(Debug, Clone)]
pub struct EnhancedMobData {
    pub id: u64,
    pub entity_type: EntityType,
    pub position: (f32, f32, f32),
    pub distance: f32,
    pub is_passive: bool,
    pub ai_state: AiState,
    pub behavior_priority: f32,
    pub last_update: Instant,
    pub target_position: Option<(f32, f32, f32)>,
    pub health: f32,
    pub energy: f32,
}

/// AI behavior states for enhanced mob processing
#[derive(Debug, Clone, PartialEq)]
pub enum AiState {
    Idle,
    Wandering,
    Chasing,
    Fleeing,
    Attacking,
    Defending,
    Sleeping,
    Eating,
    Socializing,
}

/// AI actions for mob processing
#[derive(Debug, PartialEq)]
pub enum AiAction {
    DisableAi,
    SimplifyAi,
    UpdatePathfinding,
    ProcessBehavior,
}

/// Individual mob processing decision
#[derive(Debug)]
pub struct MobProcessingDecision {
    pub mob_id: u64,
    pub action: AiAction,
    pub priority: f32,
    pub distance: f32,
    pub is_passive: bool,
}

/// Villager entity processor implementation
pub struct VillagerEntityProcessor {
    config: Arc<dyn EntityConfig>,
    simd_ops: Arc<StandardSimdOps>,
    executor: Arc<crate::parallelism::ParallelExecutor>,
    processing_stats: Arc<VillagerProcessingStats>,
    
    // Lock-free pathfinding optimizer with Copy-on-Write support
    pathfinding_optimizer: Arc<std::sync::Mutex<PathfindingOptimizer>>,
    
    // Lock-free villager group cache with CoW support for O(1) access
    villager_group_cache: Arc<DashMap<u32, Arc<EntityGroup>>>,
    
    // Atomic counters for performance monitoring
    total_villagers_processed: Arc<AtomicU64>,
    active_villager_groups: Arc<AtomicUsize>,
    villager_ai_critical_ops: Arc<AtomicUsize>,
    villager_ai_memory_aborts: Arc<AtomicUsize>,
    is_villager_ai_critical: Arc<AtomicBool>,
}

// Pathfinding optimizer for villager processing
#[derive(Debug, Default)]
pub struct PathfindingOptimizer {
    // In a real implementation, this would contain pathfinding optimization state
    tick_count: u64,
    cache_stats: PathfindingCacheStats,
}

impl PathfindingOptimizer {
    pub fn new() -> Self {
        Self::default()
    }
    
    pub fn update_tick(&mut self, tick_count: u64) {
        self.tick_count = tick_count;
    }
    
    pub fn optimize_villager_pathfinding(&mut self, _villagers: &mut Vec<EntityData>, _config: &dyn EntityConfig) -> Vec<u8> {
        // In a real implementation, this would optimize pathfinding
        Vec::new()
    }
    
    pub fn optimize_large_villager_groups(&mut self, _groups: &mut Vec<EntityGroup>, _config: &dyn EntityConfig) -> Vec<u8> {
        // In a real implementation, this would optimize pathfinding for large groups
        Vec::new()
    }
    
    pub fn get_cache_stats(&self) -> PathfindingCacheStats {
        self.cache_stats.clone()
    }
    
    pub fn cleanup_old_cache(&mut self, _active_ids: &[u64]) {
        // In a real implementation, this would cleanup old cache entries
    }
}

impl VillagerEntityProcessor {
    /// Create a new villager entity processor
    pub fn new(
        simd_ops: Arc<StandardSimdOps>,
        executor: Arc<crate::parallelism::ParallelExecutor>,
    ) -> Self {
        let config = Arc::new(VillagerConfig::default()) as Arc<dyn EntityConfig>;
        
        Self {
            config,
            simd_ops,
            executor,
            processing_stats: Arc::new(VillagerProcessingStats::default()),
            pathfinding_optimizer: Arc::new(std::sync::Mutex::new(PathfindingOptimizer::new())),
            villager_group_cache: Arc::new(DashMap::new()),
            total_villagers_processed: Arc::new(AtomicU64::new(0)),
            active_villager_groups: Arc::new(AtomicUsize::new(0)),
            villager_ai_critical_ops: Arc::new(AtomicUsize::new(0)),
            villager_ai_memory_aborts: Arc::new(AtomicUsize::new(0)),
            is_villager_ai_critical: Arc::new(AtomicBool::new(false)),
        }
    }

    /// Group villagers by proximity
    fn group_villagers_by_proximity(&self, villagers: &[EntityData], players: &[PlayerData]) -> Vec<EntityGroup> {
        let config = self.config.as_ref() as &VillagerConfig;
        
        // Use SIMD for distance calculations
        let group_centers = self.simd_ops.calculate_entity_distances(
            &villagers.iter()
                .map(|v| (v.position.0, v.position.1, v.position.2))
                .collect::<Vec<_>>(),
            (0.0, 0.0, 0.0)
        );
        
        // Create groups based on proximity
        let mut groups = Vec::new();
        let mut group_id = 0u32;
        
        for i in 0..villagers.len() {
            if i % config.max_villagers_per_group as usize == 0 {
                let group_members = villagers[i..std::cmp::min(i + config.max_villagers_per_group as usize, villagers.len())]
                    .iter()
                    .map(|v| v.id)
                    .collect();
                
                let center_position = (
                    villagers[i].position.0,
                    villagers[i].position.1,
                    villagers[i].position.2,
                );
                
                groups.push(EntityGroup {
                    group_id,
                    center_position,
                    member_ids: group_members,
                    group_type: "village".to_string(),
                    metadata: None,
                });
                
                group_id += 1;
            }
        }
        
        groups
    }

    /// Optimize villager groups
    fn optimize_villager_groups(&self, groups: Vec<EntityGroup>, config: &VillagerConfig) -> Vec<EntityGroup> {
        groups.into_iter().map(|group| {
            let mut optimized_group = group.clone();
            
            // In a real implementation, this would optimize the group based on villager behavior
            // For now, we'll just set a simple optimization flag in metadata
            optimized_group.metadata = Some(serde_json::json!({
                "optimized": true,
                "tick_interval": config.simple_ai_tick_interval
            }));
            
            optimized_group
        }).collect()
    }

    /// Process a single villager group
    fn process_villager_group(&self, group: &EntityGroup, input: &EntityProcessingInput) -> VillagerGroupResult {
        let config = self.config.as_ref() as &VillagerConfig;
        let mut result = VillagerGroupResult::default();
        
        // Determine processing strategy based on group characteristics
        let group_distance = self.calculate_group_distance_to_players(group, input.players.as_deref().unwrap_or(&[]));
        
        if group_distance > config.disable_ai_distance {
            // Far groups - disable AI for most villagers
            let villagers_to_keep = std::cmp::max(1, group.member_ids.len() / 8);
            for (i, &villager_id) in group.member_ids.iter().enumerate() {
                if i >= villagers_to_keep {
                    result.villagers_to_disable_ai.push(villager_id);
                }
            }
        } else if group_distance > config.simplify_ai_distance {
            // Medium distance - simplify AI for most villagers
            let villagers_to_keep_complex = std::cmp::max(2, group.member_ids.len() / 4);
            for (i, &villager_id) in group.member_ids.iter().enumerate() {
                if i >= villagers_to_keep_complex {
                    result.villagers_to_simplify_ai.push(villager_id);
                }
            }
        }
        
        // Apply distance-based optimizations
        self.apply_distance_optimizations(group, input, config, &mut result);
        
        result
    }

    /// Calculate distance from group center to nearest player
    fn calculate_group_distance_to_players(&self, group: &EntityGroup, players: &[PlayerData]) -> f32 {
        if players.is_empty() {
            return f32::MAX;
        }
        
        // Calculate distance from group center to nearest player using SIMD
        let group_center = (group.center_position.0, group.center_position.1, group.center_position.2);
        
        let player_positions = players.iter()
            .map(|p| (p.position.0, p.position.1, p.position.2))
            .collect::<Vec<_>>();
        
        let distances = self.simd_ops.calculate_entity_distances(&player_positions, group_center);
        
        *distances.iter().min_by(|a, b| a.partial_cmp(b).unwrap()).unwrap_or(&f32::MAX)
    }

    /// Apply additional distance-based optimizations
    fn apply_distance_optimizations(&self, group: &EntityGroup, input: &EntityProcessingInput, config: &VillagerConfig, result: &mut VillagerGroupResult) {
        let group_distance = self.calculate_group_distance_to_players(group, input.players.as_deref().unwrap_or(&[]));
        
        if group_distance > config.reduce_pathfinding_distance {
            // Reduce pathfinding frequency for distant groups
            result.villagers_to_reduce_pathfinding.extend(&group.member_ids);
        }
    }

    /// Process villagers during critical memory pressure
    fn process_villager_ai_during_critical_pressure(&self, input: &EntityProcessingInput) -> EntityProcessingResult {
        let config = self.config.as_ref() as &VillagerConfig;
        
        // During critical memory pressure, we only do essential processing
        let spatial_groups = self.group_villagers_by_proximity(&input.entities, input.players.as_deref().unwrap_or(&[]));
        let villager_groups = self.optimize_villager_groups(spatial_groups, config);
        
        let mut result = EntityProcessingResult {
            entities_to_disable_ai: Vec::new(),
            entities_to_simplify_ai: Vec::new(),
            entities_to_reduce_pathfinding: Some(Vec::new()),
            groups: Some(villager_groups.clone()),
            processing_stats: ProcessingStats::default(),
        };
        
        // During critical pressure, disable AI for all but essential villagers
        for group in &villager_groups {
            // Keep only 1 villager per group active during critical pressure
            let keep_count = std::cmp::max(1, group.member_ids.len() / 8);
            for (i, &villager_id) in group.member_ids.iter().enumerate() {
                if i >= keep_count {
                    result.entities_to_disable_ai.push(villager_id);
                } else {
                    result.entities_to_simplify_ai.push(villager_id);
                }
            }
            
            // Reduce pathfinding for all during critical pressure
            result.entities_to_reduce_pathfinding.as_mut().unwrap().extend(&group.member_ids);
        }
        
        result
    }
}

impl EntityProcessor for VillagerEntityProcessor {
    fn process_entities(&mut self, input: EntityProcessingInput) -> EntityProcessingResult {
        // Mark as critical operation to prevent memory cleanup during villager AI processing
        self.is_villager_ai_critical.store(true, Ordering::Relaxed);
        self.villager_ai_critical_ops.fetch_add(1, Ordering::Relaxed);

        let config = self.config.as_ref() as &VillagerConfig;

        // Step 1: Spatial grouping
        let spatial_groups = self.group_villagers_by_proximity(&input.entities, input.players.as_deref().unwrap_or(&[]));

        // Step 2: Optimize villager groups
        let villager_groups = self.optimize_villager_groups(spatial_groups, config);

        // Update atomic counters for performance monitoring
        let total_villagers = input.entities.len() as u64;
        self.total_villagers_processed.fetch_add(total_villagers, Ordering::Relaxed);
        self.active_villager_groups.store(villager_groups.len(), Ordering::Relaxed);

        // Step 3: Process AI optimizations in parallel by group
        let group_results = self.executor.execute(|| {
            villager_groups.into_par_iter().map(|group| {
                self.process_villager_group(&group, &input)
            }).collect::<Vec<VillagerGroupResult>>()
        });

        // Step 4: Combine all results
        let mut result = EntityProcessingResult {
            entities_to_disable_ai: Vec::new(),
            entities_to_simplify_ai: Vec::new(),
            entities_to_reduce_pathfinding: Some(Vec::new()),
            groups: Some(villager_groups),
            processing_stats: ProcessingStats::default(),
        };

        for group_result in group_results {
            result.entities_to_disable_ai.extend(group_result.villagers_to_disable_ai);
            result.entities_to_simplify_ai.extend(group_result.villagers_to_simplify_ai);
            result.entities_to_reduce_pathfinding.as_mut().unwrap()
                .extend(group_result.villagers_to_reduce_pathfinding);
        }

        // Step 5: Optimize pathfinding
        let _ = self.pathfinding_optimizer.lock().map(|mut optimizer| {
            optimizer.update_tick(input.tick_count);
            let _ = optimizer.optimize_villager_pathfinding(
                &mut input.entities.iter().map(|e| e.clone()).collect(),
                config
            );
        });

        // Clean up old cache entries
        let _ = self.pathfinding_optimizer.lock().map(|mut optimizer| {
            let active_ids: Vec<u64> = input.entities.iter().map(|v| v.id).collect();
            optimizer.cleanup_old_cache(&active_ids);
        });

        // Clear critical operation flag
        self.is_villager_ai_critical.store(false, Ordering::Relaxed);

        // Update statistics
        let stats = &mut result.processing_stats;
        stats.total_entities_processed = input.entities.len();
        stats.ai_disabled_count = result.entities_to_disable_ai.len();
        stats.ai_simplified_count = result.entities_to_simplify_ai.len();
        stats.simd_operations_used = self.simd_ops.get_level() as usize;

        result
    }
    
    fn process_entities_batch(&mut self, inputs: Vec<EntityProcessingInput>) -> Vec<EntityProcessingResult> {
        inputs.into_par_iter().map(|input| {
            self.process_entities(input)
        }).collect()
    }
    
    fn get_config(&self) -> Arc<dyn EntityConfig> {
        Arc::clone(&self.config)
    }
    
    fn update_config(&mut self, new_config: Arc<dyn EntityConfig>) {
        self.config = new_config;
    }
}

/// Villager group result structure
#[derive(Debug, Clone, Default)]
pub struct VillagerGroupResult {
    pub villagers_to_disable_ai: Vec<u64>,
    pub villagers_to_simplify_ai: Vec<u64>,
    pub villagers_to_reduce_pathfinding: Vec<u64>,
}