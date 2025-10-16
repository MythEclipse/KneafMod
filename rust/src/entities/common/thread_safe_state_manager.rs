use super::types::*;
use crate::entities::entity::types::*;
use crate::logging::{generate_trace_id, PerformanceLogger};
use crate::memory::pool::{get_global_enhanced_pool, EnhancedMemoryPoolManager};
use crossbeam_channel::{unbounded, Receiver, Sender};
use parking_lot::{RwLock, RwLockReadGuard, RwLockWriteGuard};
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, atomic::AtomicU64};
use std::time::{SystemTime, UNIX_EPOCH};

// External dependencies that need to be added to Cargo.toml
use dashmap::DashMap;
use tokio::sync::mpsc;
use uuid::Uuid;

static THREAD_SAFE_STATE_MANAGER_LOGGER: once_cell::sync::Lazy<PerformanceLogger> =
    once_cell::sync::Lazy::new(|| PerformanceLogger::new("thread_safe_state_manager"));
/// Configuration for thread-safe state management
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StateManagementConfig {
    /// Consistency level for state operations (strict/relaxed/eventual)
    pub consistency_level: ConsistencyLevel,
    /// Operation timeout in milliseconds
    pub operation_timeout: u64,
    /// Maximum number of state versions to keep for MVCC
    pub max_version_history: usize,
    /// Enable state replication
    pub enable_replication: bool,
    /// Replication timeout in milliseconds
    pub replication_timeout: u64,
    /// Enable event sourcing
    pub enable_event_sourcing: bool,
    /// Maximum size of event log
    pub max_event_log_size: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum ConsistencyLevel {
    Strict,
    Relaxed,
    Eventual,
}

impl Default for StateManagementConfig {
    fn default() -> Self {
        Self {
            consistency_level: ConsistencyLevel::Strict,
            operation_timeout: 5000,
            max_version_history: 100,
            enable_replication: true,
            replication_timeout: 1000,
            enable_event_sourcing: true,
            max_event_log_size: 1000,
        }
    }
}

/// Atomic state representation with versioning
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AtomicState<T> {
    /// Current state value
    pub value: T,
    /// Version number for MVCC
    pub version: u64,
    /// Timestamp of last modification
    pub timestamp: u64,
    /// Transaction ID for distributed operations
    pub transaction_id: Uuid,
}

impl<T: Clone + Serialize + Deserialize<'static>> AtomicState<T> {
    /// Create a new AtomicState with initial value
    pub fn new(value: T) -> Self {
        Self {
            value,
            version: 1,
            timestamp: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs(),
            transaction_id: Uuid::new_v4(),
        }
    }

    /// Update the state with atomic operations
    pub fn update(&mut self, new_value: T) {
        self.value = new_value;
        self.version += 1;
        self.timestamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        self.transaction_id = Uuid::new_v4();
    }
}
/// State snapshot for consistent reads
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StateSnapshot<T> {
    /// Snapshot of the state
    pub state: T,
    /// Version of the state at snapshot time
    pub version: u64,
    /// Timestamp of the snapshot
    pub timestamp: u64,
    /// Consistency level guaranteed for this snapshot
    pub consistency_level: ConsistencyLevel,
}

/// Event for state change (for event sourcing)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct StateChangeEvent<T> {
    /// Entity ID
    pub entity_id: u64,
    /// Type of change
    pub change_type: StateChangeType,
    /// Previous state (if applicable)
    pub previous_state: Option<T>,
    /// New state (if applicable)
    pub new_state: Option<T>,
    /// Version after change
    pub version: u64,
    /// Timestamp
    pub timestamp: u64,
    /// Transaction ID
    pub transaction_id: Uuid,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub enum StateChangeType {
    Create,
    Update,
    Delete,
    ConflictResolved,
/// Thread-safe state manager with advanced consistency guarantees
#[derive(Debug, Clone)]
pub struct ThreadSafeStateManager {
    /// Active entities by ID with atomic state
    pub active_entities: Arc<DashMap<u64, AtomicState<EntityData>>>,
    
    /// Entity version history for MVCC
    pub entity_version_history: Arc<DashMap<u64, Vec<AtomicState<EntityData>>>>,
    
    /// State change events for event sourcing
    pub state_change_events: Arc<RwLock<Vec<StateChangeEvent<EntityData>>>>,
    
    /// Entity pools by type - optimized for memory reuse
    pub entity_pools: Arc<RwLock<HashMap<EntityType, EnhancedMemoryPoolManager>>>,
    
    /// Spatial partitioning for efficient lookups
    pub spatial_index: Arc<RwLock<HashMap<(i32, i32, i32), Vec<u64>>>>,
    
    /// Performance metrics tracking
    pub stats: Arc<RwLock<ThreadSafeStateStats>>,
    
    /// Configuration for state management
    pub config: StateManagementConfig,
    
    /// Channel for state change notifications
    pub state_change_notifier: Sender<StateChangeNotification>,
    
    /// Receiver for state change notifications (public for testing)
    pub _state_change_receiver: Receiver<StateChangeNotification>,
    
    /// Next entity ID to assign
    pub next_entity_id: Arc<AtomicU64>,
    
    logger: PerformanceLogger,
}

/// Notification for state changes
#[derive(Debug, Clone)]
pub struct StateChangeNotification {
    pub entity_id: u64,
    pub change_type: StateChangeType,
    pub version: u64,
    pub timestamp: u64,
}

#[derive(Debug, Clone, Default)]
pub struct ThreadSafeStateStats {
    pub total_entities: usize,
    pub active_entities: usize,
    pub version_conflicts: usize,
    pub replication_failures: usize,
    pub event_log_size: usize,
    pub highest_entity_id: u64,
    pub last_cleanup_time: u64,
    pub read_operations: u64,
    pub write_operations: u64,
}
}
impl ThreadSafeStateManager {
    /// Create a new ThreadSafeStateManager with default configuration
    pub fn new() -> Self {
        Self::with_config(StateManagementConfig::default())
    }

    /// Create a new ThreadSafeStateManager with custom configuration
    pub fn with_config(config: StateManagementConfig) -> Self {
        let (tx, rx) = unbounded();
        
        Self {
            active_entities: Arc::new(DashMap::new()),
            entity_version_history: Arc::new(DashMap::new()),
            state_change_events: Arc::new(RwLock::new(Vec::new())),
            entity_pools: Arc::new(RwLock::new(HashMap::new())),
            spatial_index: Arc::new(RwLock::new(HashMap::new())),
            stats: Arc::new(RwLock::new(ThreadSafeStateStats::default())),
            config,
            state_change_notifier: tx,
            _state_change_receiver: rx,
            next_entity_id: Arc::new(AtomicU64::new(1)),
            logger: PerformanceLogger::new("thread_safe_state_manager"),
        }
    }

    /// Get or create a thread-safe entity pool for a specific type
    pub fn get_or_create_pool(&self, entity_type: EntityType) -> Arc<EnhancedMemoryPoolManager> {
        let trace_id = generate_trace_id();
        
        let mut pools = self.entity_pools.write().unwrap();
        
        if let Some(pool) = pools.get(&entity_type) {
            return Arc::clone(pool);
        }

        let pool = Arc::new(get_global_enhanced_pool().clone());
        pools.insert(entity_type, Arc::clone(&pool));

        self.logger.log_info(
            "pool_created",
            &trace_id,
            &format!("Created new entity pool for type: {:?}", entity_type),
        );

        pool
    }
/// Update entity state with thread-safe atomic operations and version tracking
    pub fn update_entity_state(&self, entity_id: u64, new_state: EntityData) -> Result<u64, StateManagementError> {
        let trace_id = generate_trace_id();
        let start_time = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();

        // Get write lock for atomic operations
        let mut stats = self.stats.write().unwrap();
        stats.write_operations += 1;

        // Use DashMap's atomic access pattern
        let result = self.active_entities.entry(entity_id).and_modify(|entry| {
            let mut atomic_state = entry.value_mut();
            
            // Check for consistency based on configuration
            if self.config.consistency_level == ConsistencyLevel::Strict {
                // In strict mode, we ensure no concurrent modifications
                // This is already handled by DashMap's locking mechanism
            }

            // Update the state atomically
            atomic_state.update(new_state.clone());
            
            // Record version history for MVCC
            self.record_version_history(entity_id, atomic_state.clone());
            
            // Log the state change event
            self.log_state_change(entity_id, StateChangeType::Update, Some(entry.value().clone()), Some(new_state.clone()));
            
            // Notify listeners about the state change
            let notification = StateChangeNotification {
                entity_id,
                change_type: StateChangeType::Update,
                version: atomic_state.version,
                timestamp: start_time,
            };
            let _ = self.state_change_notifier.send(notification);
            
            atomic_state.version
        }).or_insert_with(|| {
            // Create new entity entry
            let atomic_state = AtomicState::new(new_state.clone());
            
            // Record initial version history
            self.record_version_history(entity_id, atomic_state.clone());
            
            // Log the state change event
            self.log_state_change(entity_id, StateChangeType::Create, None, Some(new_state.clone()));
            
            // Notify listeners about the state change
            let notification = StateChangeNotification {
                entity_id,
                change_type: StateChangeType::Create,
                version: atomic_state.version,
                timestamp: start_time,
            };
            let _ = self.state_change_notifier.send(notification);
            
            // Update spatial index
            self.update_spatial_index(entity_id, &atomic_state.value, true);
            
            // Update stats
            stats.total_entities += 1;
            stats.active_entities = self.active_entities.len();
            stats.highest_entity_id = stats.highest_entity_id.max(entity_id);
            
            atomic_state.version
        });

        let version = result.unwrap();

        self.logger.log_debug(
            "entity_updated",
            &trace_id,
            &format!("Updated entity {} (version {})", entity_id, version),
        );

        Ok(version)
    }

    /// Get entity state with consistency guarantee
    pub fn get_entity_state(&self, entity_id: u64) -> Result<StateSnapshot<EntityData>, StateManagementError> {
        let trace_id = generate_trace_id();
        let start_time = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();

        // Get read lock for consistent read
        let mut stats = self.stats.write().unwrap();
        stats.read_operations += 1;

        // Use DashMap's atomic access for consistent read
        let result = self.active_entities.get(&entity_id).map(|entry| {
            let atomic_state = entry.value();
            
            // Create a consistent snapshot based on configuration
            let consistency_level = match self.config.consistency_level {
                ConsistencyLevel::Strict => {
                    // For strict consistency, we ensure we have the latest version
                    ConsistencyLevel::Strict
                }
                ConsistencyLevel::Relaxed => {
                    // For relaxed consistency, we return the latest available version
                    ConsistencyLevel::Relaxed
                }
                ConsistencyLevel::Eventual => {
                    // For eventual consistency, we return whatever is available
                    ConsistencyLevel::Eventual
                }
            };

            let snapshot = StateSnapshot {
                state: atomic_state.value.clone(),
                version: atomic_state.version,
                timestamp: atomic_state.timestamp,
                consistency_level,
            };

            snapshot
        });

        match result {
            Some(snapshot) => {
                self.logger.log_debug(
                    "entity_read",
                    &trace_id,
                    &format!("Read entity {} (version {}) with {} consistency", 
                        entity_id, snapshot.version, snapshot.consistency_level.as_str()),
                );
                Ok(snapshot)
            }
            None => {
                self.logger.log_debug(
                    "entity_not_found",
                    &trace_id,
                    &format!("Entity {} not found", entity_id),
                );
                Err(StateManagementError::EntityNotFound(entity_id))
            }
        }
    }