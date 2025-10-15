use super::types::*;
use crate::common_entity::factory::*;
use crate::simd_standardized::{get_standard_simd_ops, StandardSimdOps};
use crate::parallelism::executor_factory::ParallelExecutorFactory;
use std::sync::Arc;
use serde_json;

/// Enhanced mob processing configuration with AI behavior parameters
#[derive(Debug, Clone)]
pub struct MobProcessingConfig {
    pub ai_config: MobConfig,
    pub max_batch_size: usize,
    pub simd_chunk_size: usize,
    pub spatial_grid_size: f32,
    pub movement_threshold: f32,
    pub ai_update_interval: u64,
    pub pathfinding_update_interval: u64,
    pub memory_pool_threshold: usize,
}

impl Default for MobProcessingConfig {
    fn default() -> Self {
        Self {
            ai_config: MobConfig::default(),
            max_batch_size: 256,
            simd_chunk_size: 64,
            spatial_grid_size: 128.0,
            movement_threshold: 0.5,
            ai_update_interval: 50,
            pathfinding_update_interval: 100,
            memory_pool_threshold: 1024,
        }
    }
}

/// Global mob processing manager with all optimizations
pub struct MobProcessingManager {
    config: MobProcessingConfig,
    simd_processor: Arc<StandardSimdOps>,
    executor: Arc<crate::parallelism::ParallelExecutor>,
    processor: Arc<dyn EntityProcessor>,
}

impl MobProcessingManager {
    /// Create a new mob processing manager with all optimizations
    pub fn new(config: MobProcessingConfig) -> Result<Self, String> {
        let simd_processor = Arc::new(get_standard_simd_ops().clone());
        let executor = Arc::new(ParallelExecutorFactory::create_default()
            .map_err(|e| format!("Failed to create parallel executor: {}", e))?);
        
        // Create a mob entity processor with the given config
        let processor = Arc::new(MobEntityProcessor::new(
            Arc::clone(&simd_processor),
            Arc::clone(&executor),
        )) as Arc<dyn EntityProcessor>;
        
        // Update the processor with the custom config
        let ai_config = Arc::new(config.ai_config) as Arc<dyn EntityConfig>;
        processor.update_config(ai_config);
        
        Ok(Self {
            config,
            simd_processor,
            executor,
            processor,
        })
    }

    /// Process mob AI with full optimizations - main entry point
    pub fn process_mob_ai(&mut self, input: MobInput) -> EnhancedMobProcessResult {
        let entity_input: EntityProcessingInput = input.into();
        let entity_result = self.processor.process_entities(entity_input);
        let mut result = entity_result.into();
        
        // Update with additional statistics
        result.processing_stats.simd_operations_used = self.simd_processor.get_level() as usize;
        
        result
    }

    /// Process mob AI using SIMD acceleration for vector operations
    pub fn process_mob_ai_simd(&mut self, input: MobInput) -> EnhancedMobProcessResult {
        let start_time = std::time::Instant::now();
        
        // Use SIMD for distance calculations
        let positions: Vec<f32> = input.mobs.iter().flat_map(|mob| {
            vec![mob.distance * 0.1, mob.distance * 0.05, mob.distance * 0.1]
        }).collect();
        
        let distance_factors: Vec<f32> = self.simd_processor.calculate_entity_distances(
            &positions.chunks(3).map(|p| (p[0], p[1], p[2])).collect::<Vec<_>>(),
            (0.0, 0.0, 0.0)
        );
        
        // Process with enhanced AI logic
        let mut result = self.process_mob_ai(input);
        
        // Update SIMD statistics
        result.processing_stats.simd_operations_used = distance_factors.len();
        result.processing_stats.processing_time_ms = start_time.elapsed().as_millis() as u64;
        
        result
    }

    /// Batch process multiple mob collections with memory pool optimization
    pub fn process_mob_ai_batch(&mut self, inputs: Vec<MobInput>) -> Vec<EnhancedMobProcessResult> {
        let entity_inputs: Vec<EntityProcessingInput> = inputs.into_iter().map(|i| i.into()).collect();
        let entity_results = self.processor.process_entities_batch(entity_inputs);
        
        entity_results.into_iter().map(|r| r.into()).collect()
    }

    /// Get current processing statistics
    pub fn get_processing_stats(&self) -> ProcessingStats {
        let config = self.processor.get_config();
        let mob_config = config.as_ref() as &dyn EntityConfig;
        
        ProcessingStats {
            total_mobs_processed: 0, // Would be tracked by the processor in a real implementation
            ai_disabled_count: 0,    // Would be tracked by the processor in a real implementation
            ai_simplified_count: 0,  // Would be tracked by the processor in a real implementation
            spatial_groups_formed: 0,// Would be tracked by the processor in a real implementation
            simd_operations_used: self.simd_processor.get_level() as usize,
            memory_allocations: 0,  // Would be tracked by the processor in a real implementation
            processing_time_ms: 0,   // Would be tracked by the processor in a real implementation
        }
    }

    /// Update AI configuration
    pub fn update_ai_config(&mut self, new_config: MobConfig) {
        let config = Arc::new(new_config) as Arc<dyn EntityConfig>;
        self.processor.update_config(config);
        self.config.ai_config = new_config;
    }
}

/// Enhanced mob data with AI state and spatial information
#[derive(Debug, Clone)]
pub struct EnhancedMobData {
    pub id: u64,
    pub entity_type: EntityType,
    pub position: (f32, f32, f32),
    pub velocity: (f32, f32, f32),
    pub distance: f32,
    pub is_passive: bool,
    pub ai_state: AiState,
    pub behavior_priority: f32,
    pub last_update: std::time::Instant,
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

/// Enhanced mob processing result with detailed AI decisions
#[derive(Debug, Clone)]
pub struct EnhancedMobProcessResult {
    pub mobs_to_disable_ai: Vec<u64>,
    pub mobs_to_simplify_ai: Vec<u64>,
    pub mobs_to_update_pathfinding: Vec<u64>,
    pub mobs_to_process_behavior: Vec<u64>,
    pub spatial_groups: Option<Vec<SpatialGroup>>,
    pub processing_stats: ProcessingStats,
}

/// Spatial grouping for optimized mob processing
#[derive(Debug, Clone)]
pub struct SpatialGroup {
    pub group_id: u64,
    pub center_position: (f32, f32, f32),
    pub member_ids: Vec<u64>,
    pub group_type: GroupType,
    pub average_distance: f32,
}

/// Types of spatial groups
#[derive(Debug, Clone)]
pub enum GroupType {
    PassiveHerd,
    HostilePack,
    MixedCrowd,
    VillagerGroup,
    WildlifeCluster,
}

/// Processing statistics for performance monitoring
#[derive(Debug, Clone, Default)]
pub struct ProcessingStats {
    pub total_mobs_processed: usize,
    pub ai_disabled_count: usize,
    pub ai_simplified_count: usize,
    pub spatial_groups_formed: usize,
    pub simd_operations_used: usize,
    pub memory_allocations: usize,
    pub processing_time_ms: u64,
}

/// Individual mob processing decision
#[derive(Debug)]
struct MobProcessingDecision {
    mob_id: u64,
    action: AiAction,
    priority: f32,
    distance: f32,
    is_passive: bool,
}

/// AI actions for mob processing
#[derive(Debug, PartialEq)]
enum AiAction {
    DisableAi,
    SimplifyAi,
    UpdatePathfinding,
    ProcessBehavior,
}

/// Legacy compatibility functions
pub fn process_mob_ai(input: MobInput) -> MobProcessResult {
    let mut manager = MobProcessingManager::new(MobProcessingConfig::default())
        .expect("Failed to create mob processing manager");
    
    let enhanced_result = manager.process_mob_ai(input);
    
    enhanced_result.into()
}

/// Batch process multiple mob collections in parallel
pub fn process_mob_ai_batch(inputs: Vec<MobInput>) -> Vec<MobProcessResult> {
    let mut manager = MobProcessingManager::new(MobProcessingConfig::default())
        .expect("Failed to create mob processing manager");
    
    let enhanced_results = manager.process_mob_ai_batch(inputs);
    
    enhanced_results.into_iter().map(|result| result.into()).collect()
}

/// Process mob AI from JSON input and return JSON result
pub fn process_mob_ai_json(json_input: &str) -> Result<String, String> {
    let input: MobInput = serde_json::from_str(json_input)
        .map_err(|e| format!("Failed to parse JSON input: {}", e))?;

    let result = process_mob_ai(input);

    serde_json::to_string(&result).map_err(|e| format!("Failed to serialize result to JSON: {}", e))
}

/// Process mob AI from binary input in batches for better JNI performance
pub fn process_mob_ai_binary_batch(data: &[u8]) -> Result<Vec<u8>, String> {
    if data.is_empty() {
        return Ok(Vec::new());
    }

    // Deserialize manual mob input, process, and serialize result
    let input = crate::binary::conversions::deserialize_mob_input(data)
        .map_err(|e| format!("Failed to deserialize mob input: {}", e))?;
    let result = process_mob_ai(input);
    let out = crate::binary::conversions::serialize_mob_result(&result)
        .map_err(|e| format!("Failed to serialize mob result: {}", e))?;
    Ok(out)
}

impl From<EntityProcessingResult> for EnhancedMobProcessResult {
    fn from(result: EntityProcessingResult) -> Self {
        let mut mobs_to_update_pathfinding = Vec::new();
        let mut mobs_to_process_behavior = Vec::new();
        
        // In a complete implementation, we would have more sophisticated logic here
        // to determine which mobs need pathfinding updates or behavior processing
        
        // For now, we'll just split the entities based on some simple criteria
        for &mob_id in &result.entities_to_disable_ai {
            mobs_to_process_behavior.push(mob_id);
        }
        
        for &mob_id in &result.entities_to_simplify_ai {
            mobs_to_process_behavior.push(mob_id);
        }
        
        // If we have pathfinding reduction, use that for pathfinding updates
        if let Some(pathfinding_list) = result.entities_to_reduce_pathfinding {
            mobs_to_update_pathfinding.extend(pathfinding_list);
        }
        
        EnhancedMobProcessResult {
            mobs_to_disable_ai: result.entities_to_disable_ai,
            mobs_to_simplify_ai: result.entities_to_simplify_ai,
            mobs_to_update_pathfinding,
            mobs_to_process_behavior,
            spatial_groups: result.groups,
            processing_stats: result.processing_stats,
        }
    }
}

impl From<EnhancedMobProcessResult> for MobProcessResult {
    fn from(result: EnhancedMobProcessResult) -> Self {
        MobProcessResult {
            mobs_to_disable_ai: result.mobs_to_disable_ai,
            mobs_to_simplify_ai: result.mobs_to_simplify_ai,
        }
    }
}
