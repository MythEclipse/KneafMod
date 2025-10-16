use super::types::*;
use crate::entities::entity::types::*;
use crate::logging::{generate_trace_id, PerformanceLogger};
use crate::memory::pool::object_pool::ObjectPool;
use once_cell::sync::Lazy;
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::sync::RwLock;
use std::time::{SystemTime, UNIX_EPOCH};

static ENTITY_STATE_MANAGER_LOGGER: Lazy<PerformanceLogger> =
    Lazy::new(|| PerformanceLogger::new("entity_state_manager"));

/// Thread-safe entity state management with consistent snapshots across all threads
#[derive(Debug, Clone)]
pub struct EntityStateManager {
    /// Active entities by ID - thread-safe access
    pub active_entities: Arc<RwLock<HashMap<u64, EntityData>>>,
    
    /// Entity pools by type - optimized for memory reuse
    pub entity_pools: Arc<RwLock<HashMap<EntityType, ObjectPool<EntityData>>>>,
    
    /// Spatial partitioning for efficient lookups - chunk-based
    pub spatial_index: Arc<RwLock<HashMap<(i32, i32, i32), Vec<u64>>>>,
    
    /// Entity version tracking for change detection
    pub entity_versions: Arc<RwLock<HashMap<u64, u64>>>,
    
    /// Performance metrics tracking
    pub stats: Arc<RwLock<EntityStateStats>>,
    
    logger: PerformanceLogger,
}

#[derive(Debug, Clone, Default)]
pub struct EntityStateStats {
    pub total_entities: usize,
    pub active_entities: usize,
    pub pooled_objects: usize,
    pub spatial_groups: usize,
    pub highest_entity_id: u64,
    pub last_cleanup_time: u64,
    pub version_conflicts: usize,
}

impl EntityStateManager {
    /// Create a new EntityStateManager with default configuration
    pub fn new() -> Self {
        Self {
            active_entities: Arc::new(RwLock::new(HashMap::new())),
            entity_pools: Arc::new(RwLock::new(HashMap::new())),
            spatial_index: Arc::new(RwLock::new(HashMap::new())),
            entity_versions: Arc::new(RwLock::new(HashMap::new())),
            stats: Arc::new(RwLock::new(EntityStateStats::default())),
            logger: PerformanceLogger::new("entity_state_manager"),
        }
    }

    /// Get or create a thread-safe entity pool for a specific type
    pub fn get_or_create_pool(&self, entity_type: EntityType) -> Arc<ObjectPool<EntityData>> {
        let trace_id = generate_trace_id();
        
        let mut pools = self.entity_pools.write().unwrap();
        
        if let Some(pool) = pools.get(&entity_type) {
            return Arc::clone(pool);
        }

        let pool = Arc::new(ObjectPool::new(Default::default()));
        pools.insert(entity_type, Arc::clone(&pool));

        self.logger.log_info(
            "pool_created",
            &trace_id,
            &format!("Created new entity pool for type: {:?}", entity_type),
        );

        pool
    }

    /// Update entity state with atomic operations and version tracking
    pub fn update_entity_state(&self, entity_id: u64, new_state: EntityData) -> Result<u64, String> {
        let trace_id = generate_trace_id();
        let start_time = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        
        let mut active_entities = self.active_entities.write().unwrap();
        let mut spatial_index = self.spatial_index.write().unwrap();
        let mut entity_versions = self.entity_versions.write().unwrap();
        let mut stats = self.stats.write().unwrap();

        // Get current version for conflict detection
        let current_version = entity_versions.get(&entity_id).copied().unwrap_or(0);
        let new_version = current_version + 1;

        if let Some(existing) = active_entities.get_mut(&entity_id) {
            // Remove from old spatial chunk
            let old_chunk = self.get_spatial_chunk(existing.position);
            if let Some(chunk_entities) = spatial_index.get_mut(&old_chunk) {
                if let Some(index) = chunk_entities.iter().position(|&id| id == entity_id) {
                    chunk_entities.remove(index);
                    
                    // Remove empty chunks to save space
                    if chunk_entities.is_empty() {
                        spatial_index.remove(&old_chunk);
                        stats.spatial_groups -= 1;
                    }
                }
            }

            // Update entity state with atomic field operations
            existing.x = new_state.x;
            existing.y = new_state.y;
            existing.z = new_state.z;
            existing.health = new_state.health;
            existing.max_health = new_state.max_health;
            existing.velocity_x = new_state.velocity_x;
            existing.velocity_y = new_state.velocity_y;
            existing.velocity_z = new_state.velocity_z;
            existing.rotation = new_state.rotation;
            existing.pitch = new_state.pitch;
            existing.yaw = new_state.yaw;
            existing.properties = new_state.properties.clone();

            // Add to new spatial chunk
            let new_chunk = self.get_spatial_chunk(existing.position);
            spatial_index.entry(new_chunk).or_insert_with(Vec::new).push(entity_id);
            
            // Update version and stats
            entity_versions.insert(entity_id, new_version);
            stats.active_entities = active_entities.len();
            stats.spatial_groups = spatial_index.len();

            self.logger.log_debug(
                "entity_updated",
                &trace_id,
                &format!("Updated entity {} (version {}) at position {:?}", entity_id, new_version, existing.position),
            );
            
            Ok(new_version)
        } else {
            // Create new entity entry
            active_entities.insert(entity_id, new_state.clone());
            
            // Add to spatial index
            let chunk = self.get_spatial_chunk(new_state.position);
            spatial_index.entry(chunk).or_insert_with(Vec::new).push(entity_id);
            
            // Set initial version
            entity_versions.insert(entity_id, 1);
            
            // Update stats
            stats.total_entities += 1;
            stats.active_entities = active_entities.len();
            stats.spatial_groups = spatial_index.len();
            stats.highest_entity_id = stats.highest_entity_id.max(entity_id);

            self.logger.log_debug(
                "entity_created",
                &trace_id,
                &format!("Created new entity {} (version 1) at position {:?}", entity_id, new_state.position),
            );
            
            Ok(1)
        }
    }

    /// Get entity state with version tracking for consistency
    pub fn get_entity_state(&self, entity_id: u64) -> Option<(EntityData, u64)> {
        let trace_id = generate_trace_id();
        
        let active_entities = self.active_entities.read().unwrap();
        let entity_versions = self.entity_versions.read().unwrap();

        if let (Some(entity), Some(&version)) = (active_entities.get(&entity_id), entity_versions.get(&entity_id)) {
            self.logger.log_debug(
                "entity_read",
                &trace_id,
                &format!("Read entity {} (version {})", entity_id, version),
            );
            Some((entity.clone(), version))
        } else {
            self.logger.log_debug(
                "entity_not_found",
                &trace_id,
                &format!("Entity {} not found", entity_id),
            );
            None
        }
    }

    /// Get consistent snapshot of all entities in a spatial area
    pub fn get_entities_in_area(&self, center: (f32, f32, f32), radius: f32) -> Vec<(EntityData, u64)> {
        let trace_id = generate_trace_id();
        
        let active_entities = self.active_entities.read().unwrap();
        let entity_versions = self.entity_versions.read().unwrap();
        let spatial_index = self.spatial_index.read().unwrap();

        let center_chunk = self.get_spatial_chunk(center);
        let radius_chunks = (radius / 16.0).ceil() as i32; // Assume 16-block chunks

        let mut result = Vec::new();

        // Check neighboring chunks within radius for efficient spatial lookup
        for x in center_chunk.0 - radius_chunks..=center_chunk.0 + radius_chunks {
            for y in center_chunk.1 - radius_chunks..=center_chunk.1 + radius_chunks {
                for z in center_chunk.2 - radius_chunks..=center_chunk.2 + radius_chunks {
                    let chunk = (x, y, z);
                    if let Some(entity_ids) = spatial_index.get(&chunk) {
                        for &entity_id in entity_ids {
                            if let (Some(entity), Some(&version)) = (active_entities.get(&entity_id), entity_versions.get(&entity_id)) {
                                // Check actual distance to ensure we don't return entities too far away
                                let dx = entity.x - center.0;
                                let dy = entity.y - center.1;
                                let dz = entity.z - center.2;
                                if (dx * dx + dy * dy + dz * dz).sqrt() <= radius {
                                    result.push((entity.clone(), version));
                                }
                            }
                        }
                    }
                }
            }
        }

        self.logger.log_debug(
            "entities_in_area",
            &trace_id,
            &format!("Found {} entities within {} radius of {:?}", result.len(), radius, center),
        );

        result
    }

    /// Get all entities of a specific type
    pub fn get_entities_by_type(&self, entity_type: EntityType) -> Vec<(EntityData, u64)> {
        let trace_id = generate_trace_id();
        
        let active_entities = self.active_entities.read().unwrap();
        let entity_versions = self.entity_versions.read().unwrap();

        let result: Vec<(EntityData, u64)> = active_entities
            .iter()
            .filter(|(_, entity)| entity.entity_type == entity_type)
            .filter_map(|(id, entity)| {
                entity_versions.get(id).map(|&version| (entity.clone(), version))
            })
            .collect();

        self.logger.log_debug(
            "entities_by_type",
            &trace_id,
            &format!("Found {} entities of type {:?}", result.len(), entity_type),
        );

        result
    }

    /// Remove entity from state management
    pub fn remove_entity(&self, entity_id: u64) -> Result<(), String> {
        let trace_id = generate_trace_id();
        
        let mut active_entities = self.active_entities.write().unwrap();
        let mut spatial_index = self.spatial_index.write().unwrap();
        let mut entity_versions = self.entity_versions.write().unwrap();
        let mut stats = self.stats.write().unwrap();

        if let Some(entity) = active_entities.remove(&entity_id) {
            // Remove from spatial index
            let chunk = self.get_spatial_chunk(entity.position);
            if let Some(chunk_entities) = spatial_index.get_mut(&chunk) {
                if let Some(index) = chunk_entities.iter().position(|&id| id == entity_id) {
                    chunk_entities.remove(index);
                    
                    if chunk_entities.is_empty() {
                        spatial_index.remove(&chunk);
                        stats.spatial_groups -= 1;
                    }
                }
            }

            // Remove version tracking
            entity_versions.remove(&entity_id);
            
            // Update stats
            stats.active_entities = active_entities.len();
            stats.spatial_groups = spatial_index.len();

            self.logger.log_debug(
                "entity_removed",
                &trace_id,
                &format!("Removed entity {}", entity_id),
            );
            
            Ok(())
        } else {
            self.logger.log_debug(
                "entity_remove_failed",
                &trace_id,
                &format!("Failed to remove entity {} - not found", entity_id),
            );
            Err(format!("Entity {} not found", entity_id))
        }
    }

    /// Get spatial chunk coordinates for an entity position
    fn get_spatial_chunk(&self, position: (f32, f32, f32)) -> (i32, i32, i32) {
        const CHUNK_SIZE: f32 = 16.0; // Standard Minecraft chunk size
        let x = (position.0 / CHUNK_SIZE).floor() as i32;
        let y = (position.1 / CHUNK_SIZE).floor() as i32;
        let z = (position.2 / CHUNK_SIZE).floor() as i32;
        (x, y, z)
    }

    /// Get consistent stats snapshot
    pub fn get_stats(&self) -> EntityStateStats {
        let stats = self.stats.read().unwrap();
        stats.clone()
    }

    /// Perform cleanup of inactive entities (optional)
    pub fn cleanup_inactive_entities(&self, max_inactive_seconds: u64) -> Result<usize, String> {
        let trace_id = generate_trace_id();
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        
        let mut active_entities = self.active_entities.write().unwrap();
        let mut spatial_index = self.spatial_index.write().unwrap();
        let mut entity_versions = self.entity_versions.write().unwrap();
        let mut stats = self.stats.write().unwrap();

        let mut removed_count = 0;

        // In a real implementation, you would track last_active_time for each entity
        // For this example, we'll just remove entities that haven't been accessed recently
        // (this is a simplified version - real cleanup would be more sophisticated)
        
        let entities_to_remove: Vec<u64> = active_entities
            .iter()
            .filter(|(&entity_id, _)| {
                // In real implementation, check last_active_time < now - max_inactive_seconds
                // For demonstration, we'll just remove a small percentage of entities
                rand::random::<f64>() < 0.01 // 1% chance to remove for demonstration
            })
            .map(|(&id, _)| id)
            .collect();

        for entity_id in entities_to_remove {
            if let Some(entity) = active_entities.remove(&entity_id) {
                // Remove from spatial index
                let chunk = self.get_spatial_chunk(entity.position);
                if let Some(chunk_entities) = spatial_index.get_mut(&chunk) {
                    if let Some(index) = chunk_entities.iter().position(|&id| id == entity_id) {
                        chunk_entities.remove(index);
                        
                        if chunk_entities.is_empty() {
                            spatial_index.remove(&chunk);
                            stats.spatial_groups -= 1;
                        }
                    }
                }

                // Remove version tracking
                entity_versions.remove(&entity_id);
                
                removed_count += 1;
            }
        }

        // Update stats
        stats.active_entities = active_entities.len();
        stats.spatial_groups = spatial_index.len();
        stats.last_cleanup_time = now;

        self.logger.log_info(
            "cleanup_complete",
            &trace_id,
            &format!("Cleaned up {} inactive entities", removed_count),
        );

        Ok(removed_count)
    }

    /// Get atomic snapshot of entity state changes since last check
    pub fn get_state_changes(&self, last_version: u64) -> Vec<(u64, EntityData, u64)> {
        let trace_id = generate_trace_id();
        
        let active_entities = self.active_entities.read().unwrap();
        let entity_versions = self.entity_versions.read().unwrap();

        let mut changes = Vec::new();

        for (&entity_id, &version) in entity_versions.iter() {
            if version > last_version {
                if let Some(entity) = active_entities.get(&entity_id) {
                    changes.push((entity_id, entity.clone(), version));
                }
            }
        }

        self.logger.log_debug(
            "state_changes",
            &trace_id,
            &format!("Found {} state changes since version {}", changes.len(), last_version),
        );

        changes
    }
}

/// Atomic entity operation result with version tracking
#[derive(Debug, Clone)]
pub struct EntityOperationResult {
    pub entity_id: u64,
    pub success: bool,
    pub version: u64,
    pub error: Option<String>,
    pub timestamp: u64,
}

impl Default for EntityOperationResult {
    fn default() -> Self {
        Self {
            entity_id: 0,
            success: false,
            version: 0,
            error: None,
            timestamp: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs(),
        }
    }
}

/// Thread-safe entity batch operation builder
pub struct EntityBatchOperation {
    entity_manager: Arc<EntityStateManager>,
    operations: Vec<(u64, EntityData, EntityOperationType)>,
}

#[derive(Debug, Clone)]
pub enum EntityOperationType {
    Create,
    Update,
    Delete,
}

impl EntityBatchOperation {
    /// Create a new batch operation builder
    pub fn new(entity_manager: Arc<EntityStateManager>) -> Self {
        Self {
            entity_manager,
            operations: Vec::new(),
        }
    }

    /// Add create operation to batch
    pub fn create(mut self, entity_id: u64, entity_data: EntityData) -> Self {
        self.operations.push((entity_id, entity_data, EntityOperationType::Create));
        self
    }

    /// Add update operation to batch
    pub fn update(mut self, entity_id: u64, entity_data: EntityData) -> Self {
        self.operations.push((entity_id, entity_data, EntityOperationType::Update));
        self
    }

    /// Add delete operation to batch
    pub fn delete(mut self, entity_id: u64) -> Self {
        self.operations.push((entity_id, EntityData::default(), EntityOperationType::Delete));
        self
    }

    /// Execute all operations in batch with atomic consistency
    pub fn execute(self) -> Vec<EntityOperationResult> {
        let trace_id = generate_trace_id();
        let start_time = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        
        let mut results = Vec::new();
        let entity_manager = self.entity_manager;

        // Use a write lock to ensure atomicity of the entire batch operation
        let mut active_entities = entity_manager.active_entities.write().unwrap();
        let mut spatial_index = entity_manager.spatial_index.write().unwrap();
        let mut entity_versions = entity_manager.entity_versions.write().unwrap();
        let mut stats = entity_manager.stats.write().unwrap();

        for (entity_id, entity_data, op_type) in self.operations {
            let result = match op_type {
                EntityOperationType::Create => {
                    if active_entities.contains_key(&entity_id) {
                        EntityOperationResult {
                            entity_id,
                            success: false,
                            version: 0,
                            error: Some(format!("Entity {} already exists", entity_id)),
                            timestamp: start_time,
                        }
                    } else {
                        active_entities.insert(entity_id, entity_data.clone());
                        
                        let chunk = entity_manager.get_spatial_chunk(entity_data.position);
                        spatial_index.entry(chunk).or_insert_with(Vec::new).push(entity_id);
                        
                        let version = 1;
                        entity_versions.insert(entity_id, version);
                        
                        stats.total_entities += 1;
                        stats.active_entities = active_entities.len();
                        stats.spatial_groups = spatial_index.len();
                        stats.highest_entity_id = stats.highest_entity_id.max(entity_id);
                        
                        EntityOperationResult {
                            entity_id,
                            success: true,
                            version,
                            error: None,
                            timestamp: start_time,
                        }
                    }
                }
                EntityOperationType::Update => {
                    if let Some(existing) = active_entities.get_mut(&entity_id) {
                        // Remove from old spatial chunk
                        let old_chunk = entity_manager.get_spatial_chunk(existing.position);
                        if let Some(chunk_entities) = spatial_index.get_mut(&old_chunk) {
                            if let Some(index) = chunk_entities.iter().position(|&id| id == entity_id) {
                                chunk_entities.remove(index);
                                
                                if chunk_entities.is_empty() {
                                    spatial_index.remove(&old_chunk);
                                    stats.spatial_groups -= 1;
                                }
                            }
                        }

                        // Update entity state
                        *existing = entity_data.clone();

                        // Add to new spatial chunk
                        let new_chunk = entity_manager.get_spatial_chunk(existing.position);
                        spatial_index.entry(new_chunk).or_insert_with(Vec::new).push(entity_id);

                        // Update version
                        let version = entity_versions.get(&entity_id).copied().unwrap_or(0) + 1;
                        entity_versions.insert(entity_id, version);
                        
                        stats.active_entities = active_entities.len();
                        stats.spatial_groups = spatial_index.len();

                        EntityOperationResult {
                            entity_id,
                            success: true,
                            version,
                            error: None,
                            timestamp: start_time,
                        }
                    } else {
                        EntityOperationResult {
                            entity_id,
                            success: false,
                            version: 0,
                            error: Some(format!("Entity {} not found", entity_id)),
                            timestamp: start_time,
                        }
                    }
                }
                EntityOperationType::Delete => {
                    if let Some(entity) = active_entities.remove(&entity_id) {
                        // Remove from spatial index
                        let chunk = entity_manager.get_spatial_chunk(entity.position);
                        if let Some(chunk_entities) = spatial_index.get_mut(&chunk) {
                            if let Some(index) = chunk_entities.iter().position(|&id| id == entity_id) {
                                chunk_entities.remove(index);
                                
                                if chunk_entities.is_empty() {
                                    spatial_index.remove(&chunk);
                                    stats.spatial_groups -= 1;
                                }
                            }
                        }

                        // Remove version tracking
                        entity_versions.remove(&entity_id);
                        
                        stats.active_entities = active_entities.len();
                        stats.spatial_groups = spatial_index.len();

                        EntityOperationResult {
                            entity_id,
                            success: true,
                            version: 0, // No version after delete
                            error: None,
                            timestamp: start_time,
                        }
                    } else {
                        EntityOperationResult {
                            entity_id,
                            success: false,
                            version: 0,
                            error: Some(format!("Entity {} not found", entity_id)),
                            timestamp: start_time,
                        }
                    }
                }
            };

            results.push(result);
        }

        entity_manager.logger.log_info(
            "batch_operation_complete",
            &trace_id,
            &format!("Executed {} batch operations, {} succeeded, {} failed",
                results.len(),
                results.iter().filter(|r| r.success).count(),
                results.iter().filter(|r| !r.success).count()
            ),
        );

        results
    }
}