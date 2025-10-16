use super::types::{MobData, MobInput, MobProcessResult};
use crate::entities::entity::processing::{process_entities, Input as EntityProcessingInput};
use crate::entities::entity::types::{EntityData, EntityType};
use crate::logging::{generate_trace_id, PerformanceLogger};
use crate::memory::pool::{
    get_global_enhanced_pool, EnhancedMemoryPoolManager, MemoryPoolConfig,
};
use crate::parallelism::base::work_stealing::WorkStealingScheduler;
use crate::parallelism::base::executor_factory::executor_factory::ExecutorType;
use crossbeam_channel::{bounded, unbounded, Receiver, Sender};
use rayon::prelude::*;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::{
    Arc, RwLock, RwLockReadGuard, RwLockWriteGuard,
};
use std::time::{Duration, Instant};

static MOB_PROCESSOR_LOGGER: once_cell::sync::Lazy<PerformanceLogger> =
    once_cell::sync::Lazy::new(|| PerformanceLogger::new("mob_processor"));

/// Configuration for mob processing
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MobProcessingConfig {
    /// Number of worker threads to use for parallel processing
    pub worker_threads: usize,
    /// Maximum batch size for mob processing
    pub batch_size: usize,
    /// Maximum distance for mob simulation (beyond this, mobs are culled)
    pub simulation_distance: f32,
    /// Minimum distance for full LOD (beyond this, LOD is simplified)
    pub lod_simplification_distance: f32,
    /// Distance threshold for AI culling (beyond this, AI is disabled)
    pub ai_culling_distance: f32,
    /// Enable spatial partitioning for better cache locality
    pub enable_spatial_partitioning: bool,
    /// Enable parallel collision detection
    pub enable_parallel_collision: bool,
}

impl Default for MobProcessingConfig {
    fn default() -> Self {
        Self {
            worker_threads: num_cpus::get().max(4),
            batch_size: 64,
            simulation_distance: 128.0,
            lod_simplification_distance: 96.0,
            ai_culling_distance: 64.0,
            enable_spatial_partitioning: true,
            enable_parallel_collision: true,
        }
    }
}

/// Thread-safe state manager for mob data
pub struct MobStateManager {
    /// Thread-safe storage for all mob data
    inner: Arc<RwLock<HashMap<u64, MobData>>>,
    /// Configuration for mob processing
    config: MobProcessingConfig,
    /// Memory pool for temporary allocations
    memory_pool: Arc<EnhancedMemoryPoolManager>,
}

impl MobStateManager {
    /// Create a new MobStateManager with default configuration
    pub fn new() -> Self {
        Self::with_config(MobProcessingConfig::default())
    }

    /// Create a new MobStateManager with custom configuration
    pub fn with_config(config: MobProcessingConfig) -> Self {
        let memory_pool = Arc::new(get_global_enhanced_pool().clone());
        Self {
            inner: Arc::new(RwLock::new(HashMap::new())),
            config,
            memory_pool,
        }
    }

    /// Get read access to the mob data
    pub fn read(&self) -> RwLockReadGuard<HashMap<u64, MobData>> {
        self.inner.read().expect("Failed to acquire read lock")
    }

    /// Get write access to the mob data
    pub fn write(&self) -> RwLockWriteGuard<HashMap<u64, MobData>> {
        self.inner.write().expect("Failed to acquire write lock")
    }

    /// Add or update a mob in the state manager
    pub fn update_mob(&self, mob_id: u64, mob_data: MobData) {
        let mut mobs = self.write();
        mobs.insert(mob_id, mob_data);
    }

    /// Remove a mob from the state manager
    pub fn remove_mob(&self, mob_id: u64) -> Option<MobData> {
        let mut mobs = self.write();
        mobs.remove(&mob_id)
    }

    /// Get a mob by ID
    pub fn get_mob(&self, mob_id: u64) -> Option<MobData> {
        let mobs = self.read();
        mobs.get(&mob_id).cloned()
    }

    /// Get all mobs within a certain distance from a center point
    pub fn get_mobs_within_distance(&self, center: (f32, f32, f32), max_distance: f32) -> Vec<MobData> {
        let mobs = self.read();
        mobs.values()
            .filter(|mob| {
                let dx = mob.position.0 - center.0;
                let dy = mob.position.1 - center.1;
                let dz = mob.position.2 - center.2;
                let distance_sq = dx * dx + dy * dy + dz * dz;
                distance_sq <= max_distance * max_distance
            })
            .cloned()
            .collect()
    }

    /// Get the processing configuration
    pub fn config(&self) -> &MobProcessingConfig {
        &self.config
    }

    /// Get the memory pool for temporary allocations
    pub fn memory_pool(&self) -> &Arc<EnhancedMemoryPoolManager> {
        &self.memory_pool
    }
}

/// A single task for processing a mob
#[derive(Debug, Clone)]
pub struct MobProcessingTask {
    /// Unique ID for the task
    pub task_id: u64,
    /// Mob ID to process
    pub mob_id: u64,
    /// Mob data to process
    pub mob_data: MobData,
    /// Delta time for this processing step
    pub delta_time: f32,
    /// Center point for distance calculations
    pub center: (f32, f32, f32),
}

impl MobProcessingTask {
    /// Create a new MobProcessingTask
    pub fn new(task_id: u64, mob_id: u64, mob_data: MobData, delta_time: f32, center: (f32, f32, f32)) -> Self {
        Self {
            task_id,
            mob_id,
            mob_data,
            delta_time,
            center,
        }
    }

    /// Process the mob task and return the result
    pub fn process(&self, state_manager: &MobStateManager) -> Result<MobProcessResult, String> {
        let trace_id = generate_trace_id();
        let start_time = Instant::now();

        MOB_PROCESSOR_LOGGER.log_debug(
            "task_start",
            &trace_id,
            &format!("Processing task {} for mob {}", self.task_id, self.mob_id),
        );

        // Calculate distance from center for LOD and culling decisions
        let dx = self.mob_data.position.0 - self.center.0;
        let dy = self.mob_data.position.1 - self.center.1;
        let dz = self.mob_data.position.2 - self.center.2;
        let distance = (dx * dx + dy * dy + dz * dz).sqrt();

        // Apply distance-based culling and LOD simplification
        let config = state_manager.config();
        
        if distance > config.simulation_distance {
            MOB_PROCESSOR_LOGGER.log_debug(
                "mob_culled",
                &trace_id,
                &format!("Mob {} is beyond simulation distance ({:.2} > {:.2}), skipping processing",
                    self.mob_id, distance, config.simulation_distance),
            );
            return Ok(MobProcessResult {
                mobs_to_disable_ai: vec![self.mob_id],
                mobs_to_simplify_ai: vec![self.mob_id],
            });
        }

        // Create entity processing input from mob data
        let entity_data: EntityData = self.mob_data.clone().into();
        let entity_input = EntityProcessingInput {
            entity_id: self.mob_id.to_string(),
            entity_type: EntityType::Mob,
            data: entity_data,
            delta_time: self.delta_time,
            simulation_distance: config.simulation_distance as i32,
        };

        // Process the entity using the common entity processor
        let process_result = process_entities(entity_input.into());

        // Convert to mob-specific result
        let mob_result: MobProcessResult = process_result.into();

        // Apply LOD simplification based on distance
        let mut final_result = mob_result;
        if distance > config.lod_simplification_distance {
            final_result.mobs_to_simplify_ai.push(self.mob_id);
        }

        // Apply AI culling based on distance
        if distance > config.ai_culling_distance {
            final_result.mobs_to_disable_ai.push(self.mob_id);
        }

        let elapsed = start_time.elapsed();
        MOB_PROCESSOR_LOGGER.log_info(
            "task_complete",
            &trace_id,
            &format!(
                "Task {} for mob {} completed in {:?}, result: {:?}",
                self.task_id, self.mob_id, elapsed, final_result
            ),
        );

        Ok(final_result)
    }
}

/// Concurrent mob processor with thread-safe processing capabilities
pub struct ConcurrentMobProcessor {
    /// State manager for thread-safe mob data access
    state_manager: Arc<MobStateManager>,
    /// Work-stealing scheduler for task distribution
    scheduler: WorkStealingScheduler,
    /// Channel for sending tasks to worker threads
    task_sender: Sender<MobProcessingTask>,
    /// Channel for receiving results from worker threads
    result_receiver: Receiver<Result<(u64, MobProcessResult), String>>,
    /// Channel for worker threads to signal completion
    worker_completion: Receiver<()>,
    /// Configuration for the concurrent processor
    config: MobProcessingConfig,
    /// Next task ID to assign
    next_task_id: Arc<std::sync::atomic::AtomicU64>,
}

impl ConcurrentMobProcessor {
    /// Create a new ConcurrentMobProcessor with default configuration
    pub fn new() -> Self {
        Self::with_config(MobProcessingConfig::default())
    }

    /// Create a new ConcurrentMobProcessor with custom configuration
    pub fn with_config(config: MobProcessingConfig) -> Self {
        let state_manager = Arc::new(MobStateManager::with_config(config.clone()));
        let scheduler = WorkStealingScheduler::new(ExecutorType::WorkStealing);
        
        // Create channels for task distribution
        let (task_sender, task_receiver) = bounded(config.worker_threads * 2);
        let (result_sender, result_receiver) = unbounded();
        let (worker_sender, worker_completion) = unbounded();

        // Start worker threads
        let num_workers = config.worker_threads;
        let state_manager_clone = Arc::clone(&state_manager);
        
        for worker_id in 0..num_workers {
            let task_receiver = task_receiver.clone();
            let result_sender = result_sender.clone();
            let worker_sender = worker_sender.clone();
            let state_manager = Arc::clone(&state_manager_clone);
            
            std::thread::spawn(move || {
                let trace_id = generate_trace_id();
                MOB_PROCESSOR_LOGGER.log_info(
                    "worker_start",
                    &trace_id,
                    &format!("Worker thread {} started", worker_id),
                );

                while let Ok(task) = task_receiver.recv() {
                    let task_result = task.process(&state_manager);
                    let _ = result_sender.send(Ok((task.mob_id, task_result?)));
                }

                MOB_PROCESSOR_LOGGER.log_info(
                    "worker_stop",
                    &trace_id,
                    &format!("Worker thread {} stopped", worker_id),
                );
                let _ = worker_sender.send(());
            });
        }

        Self {
            state_manager,
            scheduler,
            task_sender,
            result_receiver,
            worker_completion,
            config,
            next_task_id: Arc::new(std::sync::atomic::AtomicU64::new(0)),
        }
    }

    /// Get the state manager for accessing mob data
    pub fn state_manager(&self) -> &Arc<MobStateManager> {
        &self.state_manager
    }

    /// Process all mobs in parallel using work-stealing
    pub fn process_mobs_parallel(&self, center: (f32, f32, f32), delta_time: f32) -> Result<Vec<MobProcessResult>, String> {
        let trace_id = generate_trace_id();
        let start_time = Instant::now();

        MOB_PROCESSOR_LOGGER.log_info(
            "parallel_process_start",
            &trace_id,
            &format!("Starting parallel processing of all mobs"),
        );

        // Get all mobs from state manager
        let mobs = self.state_manager.read();
        let mob_count = mobs.len();
        
        MOB_PROCESSOR_LOGGER.log_debug(
            "mob_count",
            &trace_id,
            &format!("Found {} mobs for parallel processing", mob_count),
        );

        if mob_count == 0 {
            MOB_PROCESSOR_LOGGER.log_debug(
                "no_mobs",
                &trace_id,
                "No mobs to process",
            );
            return Ok(Vec::new());
        }

        // Create tasks for all mobs
        let tasks: Vec<MobProcessingTask> = mobs.iter()
            .map(|(mob_id, mob_data)| {
                let task_id = self.next_task_id.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                MobProcessingTask::new(task_id, *mob_id, mob_data.clone(), delta_time, center)
            })
            .collect();

        // Process tasks in parallel using work-stealing
        let results = self.scheduler.execute(move || {
            tasks.into_par_iter()
                .map(|task| {
                    let result = task.process(&self.state_manager);
                    (task.mob_id, result)
                })
                .filter_map(|(mob_id, result)| result.ok().map(|r| (mob_id, r)))
                .collect::<Vec<_>>()
        });

        let elapsed = start_time.elapsed();
        MOB_PROCESSOR_LOGGER.log_info(
            "parallel_process_complete",
            &trace_id,
            &format!(
                "Parallel processing completed in {:?}, processed {} mobs",
                elapsed, results.len()
            ),
        );

        Ok(results.into_iter().map(|(_, r)| r).collect())
    }

    /// Process mobs in batches for better cache utilization
    pub fn process_mob_batch(&self, center: (f32, f32, f32), delta_time: f32) -> Result<Vec<MobProcessResult>, String> {
        let trace_id = generate_trace_id();
        let start_time = Instant::now();

        MOB_PROCESSOR_LOGGER.log_info(
            "batch_process_start",
            &trace_id,
            &format!("Starting batch processing of mobs"),
        );

        // Get all mobs from state manager
        let mobs = self.state_manager.read();
        let mob_count = mobs.len();
        
        MOB_PROCESSOR_LOGGER.log_debug(
            "batch_mob_count",
            &trace_id,
            &format!("Found {} mobs for batch processing", mob_count),
        );

        if mob_count == 0 {
            MOB_PROCESSOR_LOGGER.log_debug(
                "batch_no_mobs",
                &trace_id,
                "No mobs to process in batch",
            );
            return Ok(Vec::new());
        }

        let batch_size = self.config.batch_size;
        let num_batches = (mob_count + batch_size - 1) / batch_size;
        
        MOB_PROCESSOR_LOGGER.log_debug(
            "batch_config",
            &trace_id,
            &format!("Processing in {} batches of size {}", num_batches, batch_size),
        );

        // Process mobs in batches
        let results = (0..num_batches)
            .into_par_iter()
            .flat_map(|batch_idx| {
                let start_idx = batch_idx * batch_size;
                let end_idx = std::cmp::min(start_idx + batch_size, mob_count);
                
                let batch_mobs: Vec<&MobData> = mobs.values()
                    .skip(start_idx)
                    .take(end_idx - start_idx)
                    .collect();

                self.process_batch_internal(batch_mobs, center, delta_time)
            })
            .collect();

        let elapsed = start_time.elapsed();
        MOB_PROCESSOR_LOGGER.log_info(
            "batch_process_complete",
            &trace_id,
            &format!(
                "Batch processing completed in {:?}, processed {} mobs",
                elapsed, results.len()
            ),
        );

        Ok(results)
    }

    /// Internal method to process a batch of mobs
    fn process_batch_internal(&self, mobs: Vec<&MobData>, center: (f32, f32, f32), delta_time: f32) -> Vec<MobProcessResult> {
        let trace_id = generate_trace_id();
        let batch_size = mobs.len();

        MOB_PROCESSOR_LOGGER.log_debug(
            "processing_batch",
            &trace_id,
            &format!("Processing batch of {} mobs", batch_size),
        );

        // Use memory pool for temporary allocations
        let mut results = Vec::with_capacity(batch_size);
        
        for mob_data in mobs {
            let mob_id = mob_data.id;
            
            // Calculate distance for LOD and culling
            let dx = mob_data.position.0 - center.0;
            let dy = mob_data.position.1 - center.1;
            let dz = mob_data.position.2 - center.2;
            let distance = (dx * dx + dy * dy + dz * dz).sqrt();

            let config = self.state_manager.config();
            
            // Skip processing if mob is too far away
            if distance > config.simulation_distance {
                MOB_PROCESSOR_LOGGER.log_debug(
                    "batch_mob_culled",
                    &trace_id,
                    &format!("Mob {} culled (distance: {:.2})", mob_id, distance),
                );
                results.push(MobProcessResult {
                    mobs_to_disable_ai: vec![mob_id],
                    mobs_to_simplify_ai: vec![mob_id],
                });
                continue;
            }

            // Create entity processing input
            let entity_data: EntityData = mob_data.clone().into();
            let entity_input = EntityProcessingInput {
                entity_id: mob_id.to_string(),
                entity_type: EntityType::Mob,
                data: entity_data,
                delta_time,
                simulation_distance: config.simulation_distance as i32,
            };

            // Process the entity
            let process_result = process_entities(entity_input.into());
            
            // Convert to mob result
            let mut mob_result: MobProcessResult = process_result.into();
            
            // Apply LOD and AI culling based on distance
            if distance > config.lod_simplification_distance {
                mob_result.mobs_to_simplify_ai.push(mob_id);
            }
            if distance > config.ai_culling_distance {
                mob_result.mobs_to_disable_ai.push(mob_id);
            }

            results.push(mob_result);
        }

        results
    }

    /// Update the state of a specific mob
    pub fn update_mob_state(&self, mob_id: u64, new_data: MobData) -> Result<(), String> {
        let trace_id = generate_trace_id();
        let start_time = Instant::now();

        MOB_PROCESSOR_LOGGER.log_info(
            "update_mob_start",
            &trace_id,
            &format!("Updating state for mob {}", mob_id),
        );

        // Update the mob in the state manager
        self.state_manager.update_mob(mob_id, new_data);

        let elapsed = start_time.elapsed();
        MOB_PROCESSOR_LOGGER.log_info(
            "update_mob_complete",
            &trace_id,
            &format!("Updated state for mob {} in {:?}", mob_id, elapsed),
        );

        Ok(())
    }

    /// Process mobs with spatial partitioning for better performance
    pub fn process_with_spatial_partitioning(&self, center: (f32, f32, f32), delta_time: f32, cell_size: f32) -> Result<Vec<MobProcessResult>, String> {
        let trace_id = generate_trace_id();
        let start_time = Instant::now();

        MOB_PROCESSOR_LOGGER.log_info(
            "spatial_process_start",
            &trace_id,
            &format!("Starting spatial partitioning processing with cell size {}", cell_size),
        );

        // Get all mobs from state manager
        let mobs = self.state_manager.read();
        let mob_count = mobs.len();
        
        MOB_PROCESSOR_LOGGER.log_debug(
            "spatial_mob_count",
            &trace_id,
            &format!("Found {} mobs for spatial processing", mob_count),
        );

        if mob_count == 0 {
            return Ok(Vec::new());
        }

        // Create spatial partitions
        let mut partitions: HashMap<(i32, i32, i32), Vec<&MobData>> = HashMap::new();
        
        for mob_data in mobs.values() {
            // Calculate which cell this mob is in
            let cell_x = (mob_data.position.0 / cell_size) as i32;
            let cell_y = (mob_data.position.1 / cell_size) as i32;
            let cell_z = (mob_data.position.2 / cell_size) as i32;
            
            let cell_key = (cell_x, cell_y, cell_z);
            partitions.entry(cell_key).or_insert_with(Vec::new).push(mob_data);
        }

        MOB_PROCESSOR_LOGGER.log_debug(
            "spatial_partitions",
            &trace_id,
            &format!("Created {} spatial partitions", partitions.len()),
        );

        // Determine which cells are near the center
        let center_cell_x = (center.0 / cell_size) as i32;
        let center_cell_y = (center.1 / cell_size) as i32;
        let center_cell_z = (center.2 / cell_size) as i32;

        let relevant_cells = self.find_relevant_cells(center_cell_x, center_cell_y, center_cell_z, 2);
        
        MOB_PROCESSOR_LOGGER.log_debug(
            "spatial_relevant_cells",
            &trace_id,
            &format!("Found {} relevant cells for processing", relevant_cells.len()),
        );

        // Process only relevant cells in parallel
        let results = relevant_cells.into_par_iter()
            .flat_map(|cell_key| {
                partitions.get(&cell_key).map(|cell_mobs| {
                    self.process_batch_internal(cell_mobs.to_vec(), center, delta_time)
                }).unwrap_or_default()
            })
            .collect();

        let elapsed = start_time.elapsed();
        MOB_PROCESSOR_LOGGER.log_info(
            "spatial_process_complete",
            &trace_id,
            &format!(
                "Spatial partitioning processing completed in {:?}, processed {} mobs",
                elapsed, results.len()
            ),
        );

        Ok(results)
    }

    /// Find cells relevant to the center cell (for spatial partitioning)
    fn find_relevant_cells(&self, center_x: i32, center_y: i32, center_z: i32, radius: i32) -> Vec<(i32, i32, i32)> {
        let mut cells = Vec::new();
        
        for x in (center_x - radius)..=(center_x + radius) {
            for y in (center_y - radius)..=(center_y + radius) {
                for z in (center_z - radius)..=(center_z + radius) {
                    cells.push((x, y, z));
                }
            }
        }

        cells
    }

    /// Process mobs with parallel collision detection
    pub fn process_with_collision_detection(&self, center: (f32, f32, f32), delta_time: f32) -> Result<Vec<MobProcessResult>, String> {
        let trace_id = generate_trace_id();
        let start_time = Instant::now();

        MOB_PROCESSOR_LOGGER.log_info(
            "collision_process_start",
            &trace_id,
            &format!("Starting processing with parallel collision detection"),
        );

        // Get all mobs from state manager
        let mobs = self.state_manager.read();
        let mob_count = mobs.len();
        
        MOB_PROCESSOR_LOGGER.log_debug(
            "collision_mob_count",
            &trace_id,
            &format!("Found {} mobs for collision processing", mob_count),
        );

        if mob_count == 0 {
            return Ok(Vec::new());
        }

        // First, process all mobs normally
        let mut results = self.process_mob_batch(center, delta_time)?;

        // Then perform parallel collision detection
        if mob_count >= 2 {
            let collision_pairs = self.detect_collisions_parallel(mobs.values().cloned().collect());
            
            MOB_PROCESSOR_LOGGER.log_debug(
                "collision_pairs",
                &trace_id,
                &format!("Detected {} collision pairs", collision_pairs.len()),
            );

            // Handle collisions (simplified - in a real implementation, you would apply physics responses)
            for (mob_id1, mob_id2) in collision_pairs {
                if let Some(result) = results.iter_mut().find(|r| r.mobs_to_disable_ai.contains(&mob_id1)) {
                    result.mobs_to_disable_ai.push(mob_id2);
                }
                if let Some(result) = results.iter_mut().find(|r| r.mobs_to_disable_ai.contains(&mob_id2)) {
                    result.mobs_to_disable_ai.push(mob_id1);
                }
            }
        }

        let elapsed = start_time.elapsed();
        MOB_PROCESSOR_LOGGER.log_info(
            "collision_process_complete",
            &trace_id,
            &format!(
                "Collision detection processing completed in {:?}, processed {} mobs",
                elapsed, results.len()
            ),
        );

        Ok(results)
    }

    /// Detect collisions between mobs in parallel
    fn detect_collisions_parallel(&self, mobs: Vec<MobData>) -> Vec<(u64, u64)> {
        let trace_id = generate_trace_id();

        MOB_PROCESSOR_LOGGER.log_debug(
            "collision_detection_start",
            &trace_id,
            &format!("Starting parallel collision detection for {} mobs", mobs.len()),
        );

        // Use a simple spatial hash for collision detection
        let collision_threshold = 2.0; // 2 blocks distance for collision
        let mut collision_pairs = Vec::new();

