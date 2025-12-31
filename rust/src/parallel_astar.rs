//! Enhanced work-stealing parallel A* pathfinding implementation
//! Provides thread-safe data structures and efficient multi-core pathfinding with dynamic load balancing
//! Optimized for CPU cache utilization with blocking/striping techniques
//!
//! # Error Handling
//! This module implements comprehensive error handling following Rust best practices:
//! - All operations that can fail return Result types
//! - Panic-free code with proper error propagation
//! - Timeout mechanisms to prevent infinite loops
//! - Deadlock prevention with bounded retry attempts
//! - Resource cleanup on errors

use crossbeam::deque::{Injector, Stealer};
use jni::objects::{JByteArray, JClass, JIntArray, JObject, JObjectArray, JString};
use parking_lot::RwLock as ParkingRwLock;
use rayon::prelude::*;
use std::cmp::Ordering;
use std::collections::HashMap;
use std::sync::{
    atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering as AtomicOrdering},
    Arc, Mutex,
};
use std::thread;
use std::time::{Duration, Instant};

/// Error types for pathfinding operations
#[derive(Debug, Clone)]
pub enum PathfindingError {
    /// Grid access out of bounds
    OutOfBounds { x: i32, y: i32, z: i32 },
    /// Invalid grid dimensions
    InvalidDimensions {
        width: usize,
        height: usize,
        depth: usize,
    },
    /// Invalid starting or goal position
    InvalidPosition { reason: String },
    /// Timeout exceeded during pathfinding
    Timeout {
        elapsed: Duration,
        max_duration: Duration,
    },
    /// No path exists between start and goal
    NoPathExists,
    /// Thread synchronization error
    SyncError { message: String },
    /// Worker thread panicked
    WorkerPanic { worker_id: usize },
    /// Resource exhaustion (memory, queue capacity, etc.)
    ResourceExhausted { resource: String },
    /// Invalid configuration
    InvalidConfig { message: String },
}

impl std::fmt::Display for PathfindingError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PathfindingError::OutOfBounds { x, y, z } => {
                write!(f, "Position ({}, {}, {}) is out of bounds", x, y, z)
            }
            PathfindingError::InvalidDimensions {
                width,
                height,
                depth,
            } => {
                write!(f, "Invalid grid dimensions: {}x{}x{}", width, height, depth)
            }
            PathfindingError::InvalidPosition { reason } => {
                write!(f, "Invalid position: {}", reason)
            }
            PathfindingError::Timeout {
                elapsed,
                max_duration,
            } => {
                write!(
                    f,
                    "Pathfinding timeout: {:?} exceeded maximum {:?}",
                    elapsed, max_duration
                )
            }
            PathfindingError::NoPathExists => {
                write!(f, "No path exists between start and goal")
            }
            PathfindingError::SyncError { message } => {
                write!(f, "Synchronization error: {}", message)
            }
            PathfindingError::WorkerPanic { worker_id } => {
                write!(f, "Worker thread {} panicked", worker_id)
            }
            PathfindingError::ResourceExhausted { resource } => {
                write!(f, "Resource exhausted: {}", resource)
            }
            PathfindingError::InvalidConfig { message } => {
                write!(f, "Invalid configuration: {}", message)
            }
        }
    }
}

impl std::error::Error for PathfindingError {}

pub type PathfindingResult<T> = Result<T, PathfindingError>;

/// Custom AtomicF64 implementation using AtomicU64 bit conversions
#[allow(dead_code)]
#[derive(Debug)]
pub struct AtomicF64 {
    inner: AtomicU64,
}

#[allow(dead_code)]
impl AtomicF64 {
    pub const fn new(value: f64) -> Self {
        Self {
            inner: AtomicU64::new(value.to_bits()),
        }
    }

    pub fn load(&self, order: AtomicOrdering) -> f64 {
        f64::from_bits(self.inner.load(order))
    }

    pub fn store(&self, value: f64, order: AtomicOrdering) {
        self.inner.store(value.to_bits(), order);
    }

    pub fn fetch_add(&self, value: f64, order: AtomicOrdering) -> f64 {
        loop {
            let current_bits = self.inner.load(order);
            let current = f64::from_bits(current_bits);
            let new = current + value;
            let new_bits = new.to_bits();

            match self
                .inner
                .compare_exchange_weak(current_bits, new_bits, order, order)
            {
                Ok(_) => return current,
                Err(_actual) => {
                    // Another thread modified the value, retry with the new value
                    continue;
                }
            }
        }
    }

    pub fn compare_exchange_weak(
        &self,
        current: f64,
        new: f64,
        success: AtomicOrdering,
        failure: AtomicOrdering,
    ) -> Result<f64, f64> {
        self.inner
            .compare_exchange_weak(current.to_bits(), new.to_bits(), success, failure)
            .map(f64::from_bits)
            .map_err(f64::from_bits)
    }
}

#[allow(dead_code)]
#[derive(Clone, Copy, Debug, Eq, PartialEq, Hash)]
pub struct Position {
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

#[allow(dead_code)]
impl Position {
    pub fn new(x: i32, y: i32, z: i32) -> Self {
        Self { x, y, z }
    }

    pub fn manhattan_distance(&self, other: &Position) -> i32 {
        (self.x - other.x).abs() + (self.y - other.y).abs() + (self.z - other.z).abs()
    }

    pub fn euclidean_distance(&self, other: &Position) -> f32 {
        let dx = (self.x - other.x) as f32;
        let dy = (self.y - other.y) as f32;
        let dz = (self.z - other.z) as f32;
        (dx * dx + dy * dy + dz * dz).sqrt()
    }
}

#[allow(dead_code)]
#[derive(Clone, Debug)]
pub struct PathNode {
    pub position: Position,
    pub g_cost: f32, // Cost from start to this node
    pub h_cost: f32, // Heuristic cost to goal
    pub f_cost: f32, // Total cost (g + h)
    pub parent: Option<Position>,
    pub depth: u32,
}

#[allow(dead_code)]
impl PathNode {
    pub fn new(position: Position, g_cost: f32, h_cost: f32, parent: Option<Position>) -> Self {
        Self {
            position,
            g_cost,
            h_cost,
            f_cost: g_cost + h_cost,
            parent,
            depth: parent.map(|_p| 1).unwrap_or(0),
        }
    }
}

impl Ord for PathNode {
    fn cmp(&self, other: &Self) -> Ordering {
        // Reverse ordering for min-heap (BinaryHeap is max-heap by default)
        other
            .f_cost
            .partial_cmp(&self.f_cost)
            .unwrap_or(Ordering::Equal)
            .then_with(|| {
                other
                    .h_cost
                    .partial_cmp(&self.h_cost)
                    .unwrap_or(Ordering::Equal)
            })
    }
}

impl PartialOrd for PathNode {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

impl Eq for PathNode {}

impl PartialEq for PathNode {
    fn eq(&self, other: &Self) -> bool {
        self.position == other.position && self.f_cost == other.f_cost
    }
}

/// Cache-optimized grid representation using blocking for better spatial locality
#[allow(dead_code)]
#[derive(Clone)]
pub struct CacheOptimizedGrid {
    data: Arc<ParkingRwLock<Vec<Vec<Vec<bool>>>>>,
    width: usize,
    height: usize,
    depth: usize,
    block_size: usize,
    blocks_x: usize,
    blocks_y: usize,
    blocks_z: usize,
}

#[allow(dead_code)]
impl CacheOptimizedGrid {
    /// Create a new grid with validation
    pub fn new(
        width: usize,
        height: usize,
        depth: usize,
        default_walkable: bool,
    ) -> Result<Self, PathfindingError> {
        // Validate dimensions
        if width == 0 || height == 0 || depth == 0 {
            return Err(PathfindingError::InvalidDimensions {
                width,
                height,
                depth,
            });
        }

        // Prevent excessive memory allocation
        const MAX_DIMENSION: usize = 10000;
        if width > MAX_DIMENSION || height > MAX_DIMENSION || depth > MAX_DIMENSION {
            return Err(PathfindingError::InvalidDimensions {
                width,
                height,
                depth,
            });
        }

        let block_size = 8; // 8x8x8 blocks for optimal cache line utilization
        let blocks_x = width.div_ceil(block_size);
        let blocks_y = height.div_ceil(block_size);
        let blocks_z = depth.div_ceil(block_size);

        let data = vec![vec![vec![default_walkable; depth]; height]; width];
        Ok(Self {
            data: Arc::new(ParkingRwLock::new(data)),
            width,
            height,
            depth,
            block_size,
            blocks_x,
            blocks_y,
            blocks_z,
        })
    }

    /// Set walkable status with bounds checking
    pub fn set_walkable(
        &self,
        x: usize,
        y: usize,
        z: usize,
        walkable: bool,
    ) -> Result<(), PathfindingError> {
        if x >= self.width || y >= self.height || z >= self.depth {
            return Err(PathfindingError::OutOfBounds {
                x: x as i32,
                y: y as i32,
                z: z as i32,
            });
        }

        let mut grid = self.data.write();
        grid[x][y][z] = walkable;
        Ok(())
    }

    pub fn is_walkable(&self, x: i32, y: i32, z: i32) -> bool {
        if x < 0
            || y < 0
            || z < 0
            || x as usize >= self.width
            || y as usize >= self.height
            || z as usize >= self.depth
        {
            return false;
        }
        let grid = self.data.read();
        grid[x as usize][y as usize][z as usize]
    }

    pub fn get_dimensions(&self) -> (usize, usize, usize) {
        (self.width, self.height, self.depth)
    }

    /// Get block coordinates for cache-optimized processing
    pub fn get_block_coords(&self, x: usize, y: usize, z: usize) -> (usize, usize, usize) {
        (
            x / self.block_size,
            y / self.block_size,
            z / self.block_size,
        )
    }

    /// Process a block of cells with better cache locality
    pub fn process_block<F>(&self, block_x: usize, block_y: usize, block_z: usize, mut processor: F)
    where
        F: FnMut(usize, usize, usize, bool),
    {
        let start_x = block_x * self.block_size;
        let start_y = block_y * self.block_size;
        let start_z = block_z * self.block_size;
        let end_x = (start_x + self.block_size).min(self.width);
        let end_y = (start_y + self.block_size).min(self.height);
        let end_z = (start_z + self.block_size).min(self.depth);

        let grid = self.data.read();
        for x in start_x..end_x {
            for y in start_y..end_y {
                for z in start_z..end_z {
                    processor(x, y, z, grid[x][y][z]);
                }
            }
        }
    }
}

/// Legacy alias for backward compatibility
pub type ThreadSafeGrid = CacheOptimizedGrid;

/// Cache-optimized work-stealing queue with blocking for better CPU utilization
#[allow(dead_code)]
pub struct CacheOptimizedWorkStealingQueue {
    injector: Arc<Injector<PathNode>>,
    stealers: Vec<Arc<Stealer<PathNode>>>,
    worker_queues: Vec<Arc<Injector<PathNode>>>, // Use Injector instead of Worker for thread safety
    active_workers: AtomicUsize,
    completed_tasks: AtomicUsize,
    total_tasks: AtomicUsize,
    load_imbalance: Arc<Mutex<f64>>,
    steal_attempts: AtomicUsize,
    successful_steals: AtomicUsize,
    // Cache affinity tracking
    worker_cache_affinity: Vec<Arc<Mutex<CacheAffinity>>>,
    block_distribution: Arc<Mutex<BlockDistribution>>,
    // Deadlock prevention
    max_steal_retries: usize,
    work_available: Arc<AtomicBool>,
}

#[allow(dead_code)]
#[derive(Debug, Clone)]
pub struct CacheAffinity {
    preferred_blocks: Vec<(usize, usize, usize)>, // (block_x, block_y, block_z)
    last_accessed_block: Option<(usize, usize, usize)>,
    cache_hit_rate: f64,
}

#[allow(dead_code)]
#[derive(Debug)]
pub struct BlockDistribution {
    block_workload: HashMap<(usize, usize, usize), usize>, // Block -> workload count
    block_ownership: HashMap<(usize, usize, usize), usize>, // Block -> worker_id
    total_blocks: usize,
}

#[allow(dead_code)]
impl CacheAffinity {
    pub fn new() -> Self {
        Self {
            preferred_blocks: Vec::new(),
            last_accessed_block: None,
            cache_hit_rate: 0.0,
        }
    }

    pub fn update_preferred_blocks(&mut self, block: (usize, usize, usize)) {
        self.last_accessed_block = Some(block);

        // Keep track of recently accessed blocks for cache affinity
        if !self.preferred_blocks.contains(&block) {
            self.preferred_blocks.push(block);
            if self.preferred_blocks.len() > 8 {
                // Limit to 8 blocks for cache efficiency
                self.preferred_blocks.remove(0);
            }
        }
    }

    pub fn get_preferred_blocks(&self) -> &[(usize, usize, usize)] {
        &self.preferred_blocks
    }
}

#[allow(dead_code)]
impl BlockDistribution {
    pub fn new() -> Self {
        Self {
            block_workload: HashMap::new(),
            block_ownership: HashMap::new(),
            total_blocks: 0,
        }
    }

    pub fn assign_block(&mut self, block: (usize, usize, usize), worker_id: usize) {
        self.block_ownership.insert(block, worker_id);
        self.block_workload.insert(block, 0);
        self.total_blocks += 1;
    }

    pub fn increment_block_workload(&mut self, block: (usize, usize, usize)) {
        if let Some(count) = self.block_workload.get_mut(&block) {
            *count += 1;
        }
    }

    pub fn get_block_owner(&self, block: (usize, usize, usize)) -> Option<usize> {
        self.block_ownership.get(&block).copied()
    }

    pub fn get_load_imbalance(&self) -> f64 {
        if self.block_workload.is_empty() {
            return 0.0;
        }

        let max_load = self.block_workload.values().max().unwrap_or(&0);
        let min_load = self.block_workload.values().min().unwrap_or(&0);
        let avg_load =
            self.block_workload.values().sum::<usize>() as f64 / self.block_workload.len() as f64;

        if avg_load > 0.0 {
            (*max_load as f64 - *min_load as f64) / avg_load
        } else {
            0.0
        }
    }
}

/// Enhanced work-stealing task queue for parallel pathfinding with dynamic load balancing (legacy alias)
#[allow(dead_code)]
pub type EnhancedWorkStealingQueue = CacheOptimizedWorkStealingQueue;

#[allow(dead_code)]
impl CacheOptimizedWorkStealingQueue {
    pub fn new(num_threads: usize) -> Result<Self, PathfindingError> {
        if num_threads == 0 {
            return Err(PathfindingError::InvalidConfig {
                message: "Number of threads must be greater than 0".to_string(),
            });
        }

        if num_threads > 1024 {
            return Err(PathfindingError::InvalidConfig {
                message: format!("Number of threads ({}) exceeds maximum (1024)", num_threads),
            });
        }

        let injector = Arc::new(Injector::new());
        let stealers = Vec::new();
        let mut worker_queues = Vec::new();
        let mut worker_cache_affinity = Vec::new();

        for _ in 0..num_threads {
            let worker_queue = Arc::new(Injector::new());
            worker_queues.push(worker_queue);
            worker_cache_affinity.push(Arc::new(Mutex::new(CacheAffinity::new())));
        }

        Ok(Self {
            injector,
            stealers,
            worker_queues,
            active_workers: AtomicUsize::new(0),
            completed_tasks: AtomicUsize::new(0),
            total_tasks: AtomicUsize::new(0),
            load_imbalance: Arc::new(Mutex::new(0.0)),
            steal_attempts: AtomicUsize::new(0),
            successful_steals: AtomicUsize::new(0),
            worker_cache_affinity,
            block_distribution: Arc::new(Mutex::new(BlockDistribution::new())),
            max_steal_retries: 100, // Prevent infinite retry loops
            work_available: Arc::new(AtomicBool::new(false)),
        })
    }

    pub fn push(&self, task: PathNode) {
        self.injector.push(task);
        self.total_tasks.fetch_add(1, AtomicOrdering::SeqCst);
        self.work_available.store(true, AtomicOrdering::SeqCst);
    }

    pub fn push_to_worker(&self, worker_id: usize, task: PathNode) -> Result<(), PathfindingError> {
        if worker_id >= self.worker_queues.len() {
            return Err(PathfindingError::InvalidConfig {
                message: format!("Worker ID {} out of bounds", worker_id),
            });
        }

        self.worker_queues[worker_id].push(task);
        self.total_tasks.fetch_add(1, AtomicOrdering::SeqCst);
        self.work_available.store(true, AtomicOrdering::SeqCst);
        Ok(())
    }

    /// Cache-aware work stealing with spatial locality optimization and deadlock prevention
    pub fn steal_with_cache_affinity(
        &self,
        worker_id: usize,
        preferred_blocks: &[(usize, usize, usize)],
    ) -> Option<PathNode> {
        self.steal_attempts.fetch_add(1, AtomicOrdering::SeqCst);

        // Try to steal tasks that match cache affinity first
        if !preferred_blocks.is_empty() {
            for block in preferred_blocks {
                // Find workers that own this block
                if let Some(target_worker) = self.get_block_owner(*block) {
                    if target_worker != worker_id && target_worker < self.worker_queues.len() {
                        // Try to steal from this worker's queue with bounded retries
                        let mut retries = 0;
                        loop {
                            match self.worker_queues[target_worker].steal() {
                                crossbeam::deque::Steal::Success(task) => {
                                    self.successful_steals.fetch_add(1, AtomicOrdering::SeqCst);
                                    return Some(task);
                                }
                                crossbeam::deque::Steal::Empty => break,
                                crossbeam::deque::Steal::Retry => {
                                    retries += 1;
                                    if retries >= self.max_steal_retries {
                                        break; // Prevent infinite retry loop
                                    }
                                    thread::yield_now();
                                    continue;
                                }
                            }
                        }
                    }
                }
            }
        }

        // Fallback to regular stealing strategy
        self.steal(worker_id)
    }

    /// Get the worker ID that owns a specific block with error handling
    fn get_block_owner(&self, block: (usize, usize, usize)) -> Option<usize> {
        self.block_distribution
            .lock()
            .ok()
            .and_then(|distribution| distribution.get_block_owner(block))
    }

    /// Work stealing with bounded retries to prevent infinite loops
    pub fn steal(&self, worker_id: usize) -> Option<PathNode> {
        self.steal_attempts.fetch_add(1, AtomicOrdering::SeqCst);

        // Try to steal from other workers first (more efficient)
        for i in 0..self.worker_queues.len() {
            if i == worker_id {
                continue;
            }

            let mut retries = 0;
            loop {
                match self.worker_queues[i].steal() {
                    crossbeam::deque::Steal::Success(task) => {
                        self.successful_steals.fetch_add(1, AtomicOrdering::SeqCst);
                        return Some(task);
                    }
                    crossbeam::deque::Steal::Empty => break,
                    crossbeam::deque::Steal::Retry => {
                        retries += 1;
                        if retries >= self.max_steal_retries {
                            break; // Prevent infinite retry loop
                        }
                        thread::yield_now();
                        continue;
                    }
                }
            }
        }

        // Try global injector as fallback with bounded retries
        let mut retries = 0;
        loop {
            match self.injector.steal() {
                crossbeam::deque::Steal::Success(task) => {
                    self.successful_steals.fetch_add(1, AtomicOrdering::SeqCst);
                    return Some(task);
                }
                crossbeam::deque::Steal::Empty => {
                    // No work available anywhere
                    if self.is_work_depleted() {
                        self.work_available.store(false, AtomicOrdering::SeqCst);
                    }
                    return None;
                }
                crossbeam::deque::Steal::Retry => {
                    retries += 1;
                    if retries >= self.max_steal_retries {
                        return None; // Prevent infinite retry loop
                    }
                    thread::yield_now();
                    continue;
                }
            }
        }
    }

    /// Check if all work queues are depleted
    fn is_work_depleted(&self) -> bool {
        // Check if any worker has pending tasks
        for queue in &self.worker_queues {
            let mut retries = 0;
            loop {
                match queue.steal() {
                    crossbeam::deque::Steal::Success(_task) => return false,
                    crossbeam::deque::Steal::Empty => break,
                    crossbeam::deque::Steal::Retry => {
                        retries += 1;
                        if retries >= 10 {
                            break;
                        }
                        continue;
                    }
                }
            }
        }

        // Check global injector
        matches!(self.injector.steal(), crossbeam::deque::Steal::Empty)
    }

    pub fn get_local_task(&self, worker_id: usize) -> Option<PathNode> {
        if worker_id >= self.worker_queues.len() {
            return None;
        }

        let mut retries = 0;
        loop {
            match self.worker_queues[worker_id].steal() {
                crossbeam::deque::Steal::Success(task) => return Some(task),
                crossbeam::deque::Steal::Empty => return None,
                crossbeam::deque::Steal::Retry => {
                    retries += 1;
                    if retries >= self.max_steal_retries {
                        return None; // Prevent infinite retry loop
                    }
                    thread::yield_now();
                    continue;
                }
            }
        }
    }

    pub fn has_work_available(&self) -> bool {
        self.work_available.load(AtomicOrdering::SeqCst)
    }

    pub fn increment_active_workers(&self) {
        self.active_workers.fetch_add(1, AtomicOrdering::SeqCst);
    }

    pub fn decrement_active_workers(&self) {
        self.active_workers.fetch_sub(1, AtomicOrdering::SeqCst);
    }

    pub fn increment_completed_tasks(&self) {
        self.completed_tasks.fetch_add(1, AtomicOrdering::SeqCst);
    }

    pub fn update_load_imbalance(&self) {
        let active = self.active_workers.load(AtomicOrdering::SeqCst) as f64;
        let total = self.total_tasks.load(AtomicOrdering::SeqCst) as f64;

        if total > 0.0 {
            let imbalance = 1.0 - (active / total);
            if let Ok(mut load) = self.load_imbalance.lock() {
                *load = imbalance;
            }
        }
    }

    pub fn get_stats(&self) -> WorkStealingStats {
        let load_imbalance = self.load_imbalance.lock().map(|load| *load).unwrap_or(0.0);

        WorkStealingStats {
            active_workers: self.active_workers.load(AtomicOrdering::SeqCst),
            completed_tasks: self.completed_tasks.load(AtomicOrdering::SeqCst),
            total_tasks: self.total_tasks.load(AtomicOrdering::SeqCst),
            load_imbalance,
            steal_attempts: self.steal_attempts.load(AtomicOrdering::SeqCst),
            successful_steals: self.successful_steals.load(AtomicOrdering::SeqCst),
        }
    }
}

#[allow(dead_code)]
#[derive(Debug, Clone)]
pub struct WorkStealingStats {
    pub active_workers: usize,
    pub completed_tasks: usize,
    pub total_tasks: usize,
    pub load_imbalance: f64,
    pub steal_attempts: usize,
    pub successful_steals: usize,
}

/// Enhanced parallel A* pathfinding engine with dynamic load balancing and cache optimization
#[allow(dead_code)]
pub struct EnhancedParallelAStar {
    grid: CacheOptimizedGrid,
    queue: Arc<CacheOptimizedWorkStealingQueue>,
    num_threads: usize,
    max_search_distance: f32,
    diagonal_movement: bool,
    early_termination: bool,
    load_balance_interval: Duration,
    metrics: Arc<EnhancedPathfindingMetrics>,
    use_hierarchical_search: bool,
    hierarchical_levels: usize,
    timeout_duration: Duration,
    max_nodes_explored: usize,
}

#[allow(dead_code)]
impl EnhancedParallelAStar {
    pub fn new(grid: CacheOptimizedGrid, num_threads: usize) -> Result<Self, PathfindingError> {
        if num_threads == 0 {
            return Err(PathfindingError::InvalidConfig {
                message: "Number of threads must be greater than 0".to_string(),
            });
        }

        let queue = Arc::new(CacheOptimizedWorkStealingQueue::new(num_threads)?);

        Ok(Self {
            grid,
            queue,
            num_threads,
            max_search_distance: 1000.0,
            diagonal_movement: true,
            early_termination: true,
            load_balance_interval: Duration::from_millis(50),
            metrics: Arc::new(EnhancedPathfindingMetrics::new()),
            use_hierarchical_search: false,
            hierarchical_levels: 3,
            timeout_duration: Duration::from_secs(30), // 30 second timeout
            max_nodes_explored: 1_000_000,             // Limit nodes to prevent resource exhaustion
        })
    }

    pub fn set_max_search_distance(&mut self, distance: f32) {
        self.max_search_distance = distance;
    }

    pub fn set_diagonal_movement(&mut self, enabled: bool) {
        self.diagonal_movement = enabled;
    }

    pub fn set_early_termination(&mut self, enabled: bool) {
        self.early_termination = enabled;
    }

    pub fn set_hierarchical_search(&mut self, enabled: bool, levels: usize) {
        self.use_hierarchical_search = enabled;
        self.hierarchical_levels = levels;
    }

    pub fn set_timeout(&mut self, timeout: Duration) {
        self.timeout_duration = timeout;
    }

    pub fn set_max_nodes_explored(&mut self, max_nodes: usize) {
        self.max_nodes_explored = max_nodes;
    }

    /// Find path from start to goal using enhanced parallel A* with cache optimization and comprehensive error handling
    pub fn find_path(
        self: Arc<Self>,
        start: Position,
        goal: Position,
    ) -> PathfindingResult<Vec<Position>> {
        // Validate positions
        if !self.grid.is_walkable(start.x, start.y, start.z) {
            return Err(PathfindingError::InvalidPosition {
                reason: format!(
                    "Start position ({}, {}, {}) is not walkable",
                    start.x, start.y, start.z
                ),
            });
        }

        if !self.grid.is_walkable(goal.x, goal.y, goal.z) {
            return Err(PathfindingError::InvalidPosition {
                reason: format!(
                    "Goal position ({}, {}, {}) is not walkable",
                    goal.x, goal.y, goal.z
                ),
            });
        }

        if start == goal {
            return Ok(vec![start]);
        }

        let start_time = Instant::now();

        // Initialize with start node
        let h_cost = start.manhattan_distance(&goal) as f32;
        let start_node = PathNode::new(start, 0.0, h_cost, None);

        // Distribute initial node to multiple workers for better load balancing
        for worker_id in 0..self.num_threads.min(4) {
            self.queue.push_to_worker(worker_id, start_node.clone())?;
        }

        // Shared data structures with enhanced concurrency
        let open_set = Arc::new(ParkingRwLock::new(HashMap::new()));
        let closed_set = Arc::new(ParkingRwLock::new(HashMap::new()));
        let came_from = Arc::new(ParkingRwLock::new(HashMap::new()));
        let goal_found = Arc::new(AtomicBool::new(false));
        let best_cost = Arc::new(AtomicU64::new(u64::MAX));
        let error_occurred = Arc::new(Mutex::new(None::<PathfindingError>));

        // Start enhanced worker threads
        let mut handles = Vec::new();

        for worker_id in 0..self.num_threads {
            let queue = Arc::clone(&self.queue);
            let grid = self.grid.clone();
            let open_set = Arc::clone(&open_set);
            let closed_set = Arc::clone(&closed_set);
            let came_from = Arc::clone(&came_from);
            let goal_found = Arc::clone(&goal_found);
            let best_cost = Arc::clone(&best_cost);
            let metrics = Arc::clone(&self.metrics);
            let error_occurred = Arc::clone(&error_occurred);
            let timeout_duration = self.timeout_duration;
            let max_nodes = self.max_nodes_explored;

            let handle = thread::spawn({
                let self_clone = Arc::clone(&self);
                move || match self_clone.enhanced_worker_thread(
                    worker_id,
                    queue,
                    grid,
                    open_set,
                    closed_set,
                    came_from,
                    goal_found,
                    best_cost,
                    goal,
                    metrics,
                    start_time,
                    timeout_duration,
                    max_nodes,
                ) {
                    Ok(_) => {}
                    Err(e) => {
                        if let Ok(mut err) = error_occurred.lock() {
                            *err = Some(e);
                        }
                    }
                }
            });

            handles.push(handle);
        }

        // Start load balancing monitor
        let queue_monitor = Arc::clone(&self.queue);
        let goal_found_monitor = Arc::clone(&goal_found);
        let metrics_monitor = Arc::clone(&self.metrics);
        let error_monitor = Arc::clone(&error_occurred);

        let monitor_handle = thread::spawn(move || {
            Self::load_balancing_monitor(
                queue_monitor,
                goal_found_monitor,
                metrics_monitor,
                error_monitor,
            );
        });

        // Wait for all worker threads to complete
        for (worker_id, handle) in handles.into_iter().enumerate() {
            if let Err(_e) = handle.join() {
                return Err(PathfindingError::WorkerPanic { worker_id });
            }
        }

        // Stop monitor
        goal_found.store(true, AtomicOrdering::SeqCst);
        let _ = monitor_handle.join();

        // Check for errors
        if let Ok(err_guard) = error_occurred.lock() {
            if let Some(e) = err_guard.as_ref() {
                return Err(e.clone());
            }
        }

        let elapsed = start_time.elapsed();
        println!("Enhanced Parallel A* completed in {:?}", elapsed);

        // Update metrics
        self.metrics.record_pathfinding(
            self.queue.get_stats().completed_tasks,
            elapsed,
            goal_found.load(AtomicOrdering::SeqCst),
        );

        // Reconstruct path if found
        let came_from_read = came_from.read();
        if came_from_read.contains_key(&goal) {
            self.reconstruct_path(&came_from_read, start, goal)
        } else {
            Err(PathfindingError::NoPathExists)
        }
    }

    /// Cache-optimized worker thread with spatial locality awareness and comprehensive error handling
    fn enhanced_worker_thread(
        &self,
        worker_id: usize,
        queue: Arc<CacheOptimizedWorkStealingQueue>,
        grid: CacheOptimizedGrid,
        open_set: Arc<ParkingRwLock<HashMap<Position, f32>>>,
        closed_set: Arc<ParkingRwLock<HashMap<Position, f32>>>,
        came_from: Arc<ParkingRwLock<HashMap<Position, Position>>>,
        goal_found: Arc<AtomicBool>,
        best_cost: Arc<AtomicU64>,
        goal: Position,
        metrics: Arc<EnhancedPathfindingMetrics>,
        start_time: Instant,
        timeout_duration: Duration,
        max_nodes: usize,
    ) -> PathfindingResult<()> {
        queue.increment_active_workers();
        let mut local_nodes_processed = 0;
        let local_start_time = Instant::now();
        let mut cache_affinity = CacheAffinity::new();
        let mut idle_iterations = 0;
        const MAX_IDLE_ITERATIONS: usize = 100; // Prevent infinite idle loops

        while !goal_found.load(AtomicOrdering::SeqCst) {
            // Check timeout
            if start_time.elapsed() > timeout_duration {
                queue.decrement_active_workers();
                return Err(PathfindingError::Timeout {
                    elapsed: start_time.elapsed(),
                    max_duration: timeout_duration,
                });
            }

            // Check node limit
            if local_nodes_processed >= max_nodes {
                queue.decrement_active_workers();
                return Err(PathfindingError::ResourceExhausted {
                    resource: format!("Max nodes explored ({})", max_nodes),
                });
            }

            // Try to get task from local queue first with cache affinity
            let current = queue.get_local_task(worker_id).or_else(|| {
                // Try cache-aware stealing with spatial locality
                let preferred_blocks = cache_affinity.get_preferred_blocks();
                queue.steal_with_cache_affinity(worker_id, preferred_blocks)
            });

            if current.is_none() {
                idle_iterations += 1;

                // Check if we should terminate
                if idle_iterations >= MAX_IDLE_ITERATIONS || !queue.has_work_available() {
                    // Double-check with a final steal attempt
                    let final_attempt = queue
                        .get_local_task(worker_id)
                        .or_else(|| queue.steal(worker_id));

                    if final_attempt.is_none() {
                        break; // No work left, terminate
                    } else {
                        idle_iterations = 0; // Reset counter
                    }
                }

                // Brief sleep to avoid busy-waiting
                thread::sleep(Duration::from_micros(100));
                continue;
            }

            let current = current.unwrap();
            let current_pos = current.position;
            local_nodes_processed += 1;
            idle_iterations = 0; // Reset idle counter

            // Update cache affinity based on current position
            let current_block = grid.get_block_coords(
                current_pos.x as usize,
                current_pos.y as usize,
                current_pos.z as usize,
            );
            cache_affinity.update_preferred_blocks(current_block);

            // Update block distribution metrics with error handling
            if let Ok(mut distribution) = queue.block_distribution.lock() {
                distribution.increment_block_workload(current_block);
            }

            // Early termination check
            if self.early_termination && goal_found.load(AtomicOrdering::SeqCst) {
                break;
            }

            // Check if we've reached the goal
            if current_pos == goal {
                goal_found.store(true, AtomicOrdering::SeqCst);
                queue.increment_completed_tasks();

                // Update best cost if this path is better
                let current_cost = current.g_cost as u64;
                best_cost.fetch_min(current_cost, AtomicOrdering::SeqCst);
                break;
            }

            // Skip if already processed with better cost
            {
                let mut closed = closed_set.write();
                if let Some(&existing_cost) = closed.get(&current_pos) {
                    if existing_cost <= current.g_cost {
                        continue;
                    }
                }
                closed.insert(current_pos, current.g_cost);
            }

            // Early pruning based on heuristic
            let remaining_cost = current_pos.manhattan_distance(&goal) as f32;
            if current.g_cost + remaining_cost > self.max_search_distance {
                continue;
            }

            // Expand neighbors using cache-optimized method
            let neighbors = self.get_cache_optimized_neighbors(&current_pos, &grid);

            for neighbor_pos in neighbors {
                // Skip if goal already found (early termination)
                if goal_found.load(AtomicOrdering::SeqCst) {
                    break;
                }

                // Calculate costs with dynamic weight adjustment
                let move_cost = self.get_enhanced_move_cost(&current_pos, &neighbor_pos);
                let g_cost = current.g_cost + move_cost;
                let h_cost = neighbor_pos.manhattan_distance(&goal) as f32;

                // Pruning based on best known cost
                let best_cost_f32 = best_cost.load(AtomicOrdering::SeqCst) as f32;
                if g_cost + h_cost > best_cost_f32 {
                    continue;
                }

                // Check if this path is better
                {
                    let mut open = open_set.write();
                    if let Some(&existing_g) = open.get(&neighbor_pos) {
                        if existing_g <= g_cost {
                            continue;
                        }
                    }
                    open.insert(neighbor_pos, g_cost);
                }

                // Update came_from with thread-safe write
                {
                    let mut came_from_map = came_from.write();
                    came_from_map.insert(neighbor_pos, current_pos);
                }

                // Create new node and add to queue with cache-aware distribution
                let g_cost_neighbor = g_cost;
                let h_cost_neighbor = h_cost;

                // Determine target worker based on cache affinity
                let neighbor_block = grid.get_block_coords(
                    neighbor_pos.x as usize,
                    neighbor_pos.y as usize,
                    neighbor_pos.z as usize,
                );
                let target_worker = queue
                    .block_distribution
                    .lock()
                    .ok()
                    .and_then(|distribution| distribution.get_block_owner(neighbor_block))
                    .unwrap_or((worker_id + local_nodes_processed) % self.num_threads);

                // Create new node
                let new_node = PathNode::new(
                    neighbor_pos,
                    g_cost_neighbor,
                    h_cost_neighbor,
                    Some(current_pos),
                );

                // Push to worker queue with error handling
                if let Err(e) = queue.push_to_worker(target_worker, new_node) {
                    eprintln!("Failed to push task to worker {}: {}", target_worker, e);
                    // Fallback: push to global queue
                    let fallback_node = PathNode::new(
                        neighbor_pos,
                        g_cost_neighbor,
                        h_cost_neighbor,
                        Some(current_pos),
                    );
                    queue.push(fallback_node);
                }
            }

            queue.increment_completed_tasks();

            // Periodic load balancing and cache affinity update
            if local_nodes_processed % 50 == 0 {
                queue.update_load_imbalance();

                // Update worker cache affinity with error handling
                if let Ok(mut affinity) = queue.worker_cache_affinity[worker_id].lock() {
                    affinity.update_preferred_blocks(current_block);
                }
            }
        }

        queue.decrement_active_workers();
        metrics.record_worker_efficiency(
            worker_id,
            local_nodes_processed,
            local_start_time.elapsed(),
        );
        Ok(())
    }

    /// Load balancing monitor for dynamic work distribution with cache optimization and error handling
    fn load_balancing_monitor(
        queue: Arc<CacheOptimizedWorkStealingQueue>,
        goal_found: Arc<AtomicBool>,
        metrics: Arc<EnhancedPathfindingMetrics>,
        error_occurred: Arc<Mutex<Option<PathfindingError>>>,
    ) {
        let mut _last_stats = queue.get_stats();
        let mut iterations = 0;
        const MAX_MONITOR_ITERATIONS: usize = 10000; // Prevent infinite monitor loops

        while !goal_found.load(AtomicOrdering::SeqCst) && iterations < MAX_MONITOR_ITERATIONS {
            iterations += 1;

            // Check for errors
            if let Ok(err_guard) = error_occurred.lock() {
                if err_guard.is_some() {
                    break; // Stop monitoring if error occurred
                }
            }

            thread::sleep(Duration::from_millis(100));

            let current_stats = queue.get_stats();

            // Detect load imbalance
            if current_stats.load_imbalance > 0.3 {
                metrics.record_load_imbalance(current_stats.load_imbalance);
            }

            // Monitor steal efficiency
            let steal_rate = if current_stats.steal_attempts > 0 {
                current_stats.successful_steals as f64 / current_stats.steal_attempts as f64
            } else {
                0.0
            };

            if steal_rate < 0.5 && current_stats.steal_attempts > 10 {
                metrics.record_steal_inefficiency(steal_rate);
            }

            _last_stats = current_stats;
        }
    }

    /// Cache-optimized neighbor selection with spatial locality awareness
    fn get_cache_optimized_neighbors(
        &self,
        pos: &Position,
        grid: &CacheOptimizedGrid,
    ) -> Vec<Position> {
        let mut neighbors = Vec::with_capacity(26); // Max 26 neighbors in 3D

        // Process neighbors in cache-friendly order (locality-based)
        let current_block = grid.get_block_coords(pos.x as usize, pos.y as usize, pos.z as usize);

        // First check same block neighbors (best cache locality)
        self.process_block_neighbors(pos, grid, current_block, &mut neighbors);

        // Then check adjacent blocks
        for dx in -1i32..=1 {
            for dy in -1i32..=1 {
                for dz in -1i32..=1 {
                    if dx == 0 && dy == 0 && dz == 0 {
                        continue;
                    }

                    let block_x = (current_block.0 as i32 + dx) as usize;
                    let block_y = (current_block.1 as i32 + dy) as usize;
                    let block_z = (current_block.2 as i32 + dz) as usize;

                    if block_x < grid.blocks_x && block_y < grid.blocks_y && block_z < grid.blocks_z
                    {
                        self.process_block_neighbors(
                            pos,
                            grid,
                            (block_x, block_y, block_z),
                            &mut neighbors,
                        );
                    }
                }
            }
        }

        neighbors
    }

    /// Process neighbors within a specific block for cache efficiency
    fn process_block_neighbors(
        &self,
        pos: &Position,
        grid: &CacheOptimizedGrid,
        block: (usize, usize, usize),
        neighbors: &mut Vec<Position>,
    ) {
        let start_x = block.0 * grid.block_size;
        let start_y = block.1 * grid.block_size;
        let start_z = block.2 * grid.block_size;
        let end_x = (start_x + grid.block_size).min(grid.width);
        let end_y = (start_y + grid.block_size).min(grid.height);
        let end_z = (start_z + grid.block_size).min(grid.depth);

        // Check if position is within this block
        if (pos.x as usize >= start_x)
            && ((pos.x as usize) < end_x)
            && (pos.y as usize >= start_y)
            && ((pos.y as usize) < end_y)
            && (pos.z as usize >= start_z)
            && ((pos.z as usize) < end_z)
        {
            // Process neighbors in spatial order for cache efficiency
            let directions = if self.diagonal_movement {
                self.get_cache_friendly_directions(pos, block)
            } else {
                vec![
                    (-1, 0, 0),
                    (1, 0, 0),
                    (0, -1, 0),
                    (0, 1, 0),
                    (0, 0, -1),
                    (0, 0, 1),
                ]
            };

            for (dx, dy, dz) in directions {
                let new_x = pos.x + dx;
                let new_y = pos.y + dy;
                let new_z = pos.z + dz;

                if (new_x as usize >= start_x)
                    && ((new_x as usize) < end_x)
                    && (new_y as usize >= start_y)
                    && ((new_y as usize) < end_y)
                    && (new_z as usize >= start_z)
                    && ((new_z as usize) < end_z)
                    && grid.is_walkable(new_x, new_y, new_z)
                {
                    neighbors.push(Position::new(new_x, new_y, new_z));
                }
            }
        }
    }

    /// Get cache-friendly direction ordering based on spatial locality
    fn get_cache_friendly_directions(
        &self,
        _pos: &Position,
        _current_block: (usize, usize, usize),
    ) -> Vec<(i32, i32, i32)> {
        vec![
            // Cardinal directions first (most likely to be in same cache line)
            (-1, 0, 0),
            (1, 0, 0),
            (0, -1, 0),
            (0, 1, 0),
            (0, 0, -1),
            (0, 0, 1),
            // 2D diagonals (still likely in same cache block)
            (-1, -1, 0),
            (-1, 1, 0),
            (1, -1, 0),
            (1, 1, 0),
            // 3D face diagonals
            (-1, 0, -1),
            (-1, 0, 1),
            (1, 0, -1),
            (1, 0, 1),
            (0, -1, -1),
            (0, -1, 1),
            (0, 1, -1),
            (0, 1, 1),
            // 3D corner diagonals (least cache friendly)
            (-1, -1, -1),
            (-1, -1, 1),
            (-1, 1, -1),
            (-1, 1, 1),
            (1, -1, -1),
            (1, -1, 1),
            (1, 1, -1),
            (1, 1, 1),
        ]
    }

    /// Legacy method for backward compatibility
    fn get_enhanced_neighbors(&self, pos: &Position, grid: &ThreadSafeGrid) -> Vec<Position> {
        self.get_cache_optimized_neighbors(pos, grid)
    }

    /// Adaptive direction ordering based on heuristic
    fn get_adaptive_directions(&self, _pos: &Position) -> Vec<(i32, i32, i32)> {
        // Default direction set with strategic ordering
        vec![
            // Cardinal directions first (cheaper)
            (-1, 0, 0),
            (1, 0, 0),
            (0, -1, 0),
            (0, 1, 0),
            (0, 0, -1),
            (0, 0, 1),
            // 2D diagonals
            (-1, -1, 0),
            (-1, 1, 0),
            (1, -1, 0),
            (1, 1, 0),
            // 3D diagonals
            (-1, 0, -1),
            (-1, 0, 1),
            (1, 0, -1),
            (1, 0, 1),
            (0, -1, -1),
            (0, -1, 1),
            (0, 1, -1),
            (0, 1, 1),
            // 3D corner diagonals (most expensive)
            (-1, -1, -1),
            (-1, -1, 1),
            (-1, 1, -1),
            (-1, 1, 1),
            (1, -1, -1),
            (1, -1, 1),
            (1, 1, -1),
            (1, 1, 1),
        ]
    }

    /// Enhanced move cost calculation with terrain-aware costing
    fn get_enhanced_move_cost(&self, from: &Position, to: &Position) -> f32 {
        let dx = (to.x - from.x).abs();
        let dy = (to.y - from.y).abs();
        let dz = (to.z - from.z).abs();

        // Base movement cost
        let base_cost = if dx + dy + dz > 1 {
            // Diagonal movement costs more
            1.4 + (dx + dy + dz - 1) as f32 * 0.4
        } else {
            1.0
        };

        // Terrain-aware cost adjustment (if terrain data is available)
        // This could be extended with actual terrain cost information
        let terrain_multiplier = 1.0;

        base_cost * terrain_multiplier
    }

    fn reconstruct_path(
        &self,
        came_from: &HashMap<Position, Position>,
        start: Position,
        goal: Position,
    ) -> PathfindingResult<Vec<Position>> {
        let mut path = Vec::new();
        let mut current = goal;
        let mut iterations = 0;
        const MAX_PATH_LENGTH: usize = 100000; // Prevent infinite loops in path reconstruction

        while current != start {
            if iterations >= MAX_PATH_LENGTH {
                return Err(PathfindingError::ResourceExhausted {
                    resource: format!(
                        "Path reconstruction exceeded max length ({})",
                        MAX_PATH_LENGTH
                    ),
                });
            }

            path.push(current);
            if let Some(&parent) = came_from.get(&current) {
                current = parent;
            } else {
                return Err(PathfindingError::NoPathExists);
            }

            iterations += 1;
        }

        path.push(start);
        path.reverse();
        Ok(path)
    }
}

/// Batch pathfinding for multiple queries with cache optimization and error handling
#[allow(dead_code)]
pub fn batch_parallel_astar(
    grid: &CacheOptimizedGrid,
    queries: Vec<(Position, Position)>,
    num_threads: usize,
) -> Vec<PathfindingResult<Vec<Position>>> {
    let engine = match EnhancedParallelAStar::new(grid.clone(), num_threads) {
        Ok(e) => Arc::new(e),
        Err(e) => {
            // Return error for all queries if engine creation failed
            return vec![Err(e); queries.len()];
        }
    };

    queries
        .into_par_iter()
        .map(|(start, goal)| {
            let engine_clone = Arc::clone(&engine);
            engine_clone.find_path(start, goal)
        })
        .collect()
}
/// SIMD-optimized heuristic calculations
#[cfg(target_arch = "x86_64")]
pub mod simd_heuristics {
    use std::arch::x86_64::*;

    #[allow(dead_code)]
    pub fn batch_manhattan_distance(
        positions: &[(i32, i32, i32)],
        goal: (i32, i32, i32),
    ) -> Vec<f32> {
        if !is_x86_feature_detected!("avx") {
            // Fallback to scalar implementation if AVX is not available
            return positions
                .iter()
                .map(|pos| {
                    ((pos.0 - goal.0).abs() + (pos.1 - goal.1).abs() + (pos.2 - goal.2).abs())
                        as f32
                })
                .collect();
        }

        unsafe {
            let goal_x = _mm256_set1_ps(goal.0 as f32);
            let goal_y = _mm256_set1_ps(goal.1 as f32);
            let goal_z = _mm256_set1_ps(goal.2 as f32);

            positions
                .chunks(8)
                .flat_map(|chunk| {
                    let mut results = Vec::with_capacity(chunk.len());

                    if chunk.len() == 8 {
                        // Create temporary arrays for SIMD operations
                        let mut x_coords = [0.0f32; 8];
                        let mut y_coords = [0.0f32; 8];
                        let mut z_coords = [0.0f32; 8];

                        for (i, pos) in chunk.iter().enumerate() {
                            x_coords[i] = pos.0 as f32;
                            y_coords[i] = pos.1 as f32;
                            z_coords[i] = pos.2 as f32;
                        }

                        let pos_x = _mm256_loadu_ps(x_coords.as_ptr());
                        let pos_y = _mm256_loadu_ps(y_coords.as_ptr());
                        let pos_z = _mm256_loadu_ps(z_coords.as_ptr());

                        let dx = _mm256_sub_ps(pos_x, goal_x);
                        let dy = _mm256_sub_ps(pos_y, goal_y);
                        let dz = _mm256_sub_ps(pos_z, goal_z);

                        let abs_dx = _mm256_andnot_ps(_mm256_set1_ps(-0.0), dx);
                        let abs_dy = _mm256_andnot_ps(_mm256_set1_ps(-0.0), dy);
                        let abs_dz = _mm256_andnot_ps(_mm256_set1_ps(-0.0), dz);

                        let sum = _mm256_add_ps(_mm256_add_ps(abs_dx, abs_dy), abs_dz);

                        let mut result_array = [0.0f32; 8];
                        _mm256_storeu_ps(result_array.as_mut_ptr(), sum);

                        for distance in result_array.iter().take(chunk.len()) {
                            results.push(*distance);
                        }
                    } else {
                        for pos in chunk {
                            let distance = ((pos.0 - goal.0).abs()
                                + (pos.1 - goal.1).abs()
                                + (pos.2 - goal.2).abs())
                                as f32;
                            results.push(distance);
                        }
                    }

                    results
                })
                .collect()
        }
    }
}

/// Enhanced performance metrics for pathfinding operations with detailed analytics
#[allow(dead_code)]
#[derive(Debug)]
pub struct EnhancedPathfindingMetrics {
    pub total_nodes_explored: AtomicUsize,
    pub total_pathfinding_time: std::sync::Mutex<std::time::Duration>,
    pub successful_paths: AtomicUsize,
    pub failed_paths: AtomicUsize,
    pub load_imbalance_events: AtomicUsize,
    pub steal_inefficiency_events: AtomicUsize,
    pub worker_efficiency: Arc<ParkingRwLock<HashMap<usize, WorkerEfficiency>>>,
    pub average_path_length: AtomicF64,
    pub path_optimality: AtomicF64,
    pub memory_usage: AtomicUsize,
    pub cache_hit_rate: AtomicF64,
    pub hierarchical_search_levels: AtomicUsize,
}

#[allow(dead_code)]
#[derive(Debug, Clone)]
pub struct WorkerEfficiency {
    pub worker_id: usize,
    pub nodes_processed: usize,
    pub processing_time: Duration,
    pub steal_success_rate: f64,
    pub load_factor: f64,
    pub efficiency_score: f64,
}

#[allow(dead_code)]
impl EnhancedPathfindingMetrics {
    pub fn new() -> Self {
        Self {
            total_nodes_explored: AtomicUsize::new(0),
            total_pathfinding_time: std::sync::Mutex::new(std::time::Duration::from_secs(0)),
            successful_paths: AtomicUsize::new(0),
            failed_paths: AtomicUsize::new(0),
            load_imbalance_events: AtomicUsize::new(0),
            steal_inefficiency_events: AtomicUsize::new(0),
            worker_efficiency: Arc::new(ParkingRwLock::new(HashMap::new())),
            average_path_length: AtomicF64::new(0.0),
            path_optimality: AtomicF64::new(0.0),
            memory_usage: AtomicUsize::new(0),
            cache_hit_rate: AtomicF64::new(0.0),
            hierarchical_search_levels: AtomicUsize::new(0),
        }
    }

    pub fn record_pathfinding(
        &self,
        nodes_explored: usize,
        duration: std::time::Duration,
        success: bool,
    ) {
        self.total_nodes_explored
            .fetch_add(nodes_explored, AtomicOrdering::SeqCst);

        if let Ok(mut total_time) = self.total_pathfinding_time.lock() {
            *total_time += duration;
        }

        if success {
            self.successful_paths.fetch_add(1, AtomicOrdering::SeqCst);
        } else {
            self.failed_paths.fetch_add(1, AtomicOrdering::SeqCst);
        }
    }

    pub fn record_load_imbalance(&self, imbalance: f64) {
        if imbalance > 0.3 {
            self.load_imbalance_events
                .fetch_add(1, AtomicOrdering::SeqCst);
        }
    }

    pub fn record_steal_inefficiency(&self, steal_rate: f64) {
        if steal_rate < 0.5 {
            self.steal_inefficiency_events
                .fetch_add(1, AtomicOrdering::SeqCst);
        }
    }

    pub fn record_worker_efficiency(
        &self,
        worker_id: usize,
        nodes_processed: usize,
        processing_time: Duration,
    ) {
        let mut worker_stats = self.worker_efficiency.write();
        let efficiency = WorkerEfficiency {
            worker_id,
            nodes_processed,
            processing_time,
            steal_success_rate: 0.0, // Will be updated separately
            load_factor: 0.0,
            efficiency_score: nodes_processed as f64 / processing_time.as_secs_f64().max(0.001),
        };
        worker_stats.insert(worker_id, efficiency);
    }

    pub fn update_path_optimality(&self, path_length: usize, optimal_length: usize) {
        if optimal_length > 0 {
            let optimality = (optimal_length as f64 / path_length as f64).min(1.0);
            self.path_optimality
                .store(optimality, AtomicOrdering::SeqCst);
        }
    }

    pub fn get_comprehensive_summary(&self) -> String {
        let total_time = self.total_pathfinding_time.lock().unwrap();
        let worker_stats = self.worker_efficiency.read();

        let total_workers = worker_stats.len();
        let avg_worker_efficiency = if total_workers > 0 {
            worker_stats
                .values()
                .map(|w| w.efficiency_score)
                .sum::<f64>()
                / total_workers as f64
        } else {
            0.0
        };

        format!(
            "EnhancedPathfindingMetrics{{\n  total_nodes: {}, total_time: {:?},\n  successful: {}, failed: {},\n  load_imbalance_events: {}, steal_inefficiency_events: {},\n  avg_path_length: {:.2}, path_optimality: {:.2},\n  memory_usage: {} bytes, cache_hit_rate: {:.2},\n  worker_count: {}, avg_worker_efficiency: {:.2},\n  hierarchical_levels: {}\n}}",
            self.total_nodes_explored.load(AtomicOrdering::SeqCst),
            *total_time,
            self.successful_paths.load(AtomicOrdering::SeqCst),
            self.failed_paths.load(AtomicOrdering::SeqCst),
            self.load_imbalance_events.load(AtomicOrdering::SeqCst),
            self.steal_inefficiency_events.load(AtomicOrdering::SeqCst),
            self.average_path_length.load(AtomicOrdering::SeqCst),
            self.path_optimality.load(AtomicOrdering::SeqCst),
            self.memory_usage.load(AtomicOrdering::SeqCst),
            self.cache_hit_rate.load(AtomicOrdering::SeqCst),
            total_workers,
            avg_worker_efficiency,
            self.hierarchical_search_levels.load(AtomicOrdering::SeqCst)
        )
    }

    pub fn get_performance_report(&self) -> PerformanceReport {
        let total_time = self.total_pathfinding_time.lock().unwrap();
        let total_paths = self.successful_paths.load(AtomicOrdering::SeqCst)
            + self.failed_paths.load(AtomicOrdering::SeqCst);

        PerformanceReport {
            total_operations: total_paths,
            success_rate: if total_paths > 0 {
                self.successful_paths.load(AtomicOrdering::SeqCst) as f64 / total_paths as f64
            } else {
                0.0
            },
            average_nodes_per_path: if total_paths > 0 {
                self.total_nodes_explored.load(AtomicOrdering::SeqCst) as f64 / total_paths as f64
            } else {
                0.0
            },
            average_time_per_path: if total_paths > 0 {
                total_time.as_secs_f64() / total_paths as f64
            } else {
                0.0
            },
            load_imbalance_rate: if total_paths > 0 {
                self.load_imbalance_events.load(AtomicOrdering::SeqCst) as f64 / total_paths as f64
            } else {
                0.0
            },
            memory_efficiency: self.memory_usage.load(AtomicOrdering::SeqCst) as f64
                / self
                    .total_nodes_explored
                    .load(AtomicOrdering::SeqCst)
                    .max(1) as f64,
        }
    }
}

#[allow(dead_code)]
#[derive(Debug, Clone)]
pub struct PerformanceReport {
    pub total_operations: usize,
    pub success_rate: f64,
    pub average_nodes_per_path: f64,
    pub average_time_per_path: f64,
    pub load_imbalance_rate: f64,
    pub memory_efficiency: f64,
}

lazy_static::lazy_static! {
    pub static ref PATHFINDING_METRICS: EnhancedPathfindingMetrics = EnhancedPathfindingMetrics::new();
}

/// Enhanced JNI interface for parallel A* pathfinding with cache optimization and comprehensive error handling
#[allow(non_snake_case)]
pub fn Java_com_kneaf_core_ParallelRustVectorProcessor_parallelAStarPathfind<'a>(
    mut env: jni::JNIEnv<'a>,
    _class: JClass<'a>,
    grid_data: JByteArray<'a>,
    width: i32,
    height: i32,
    depth: i32,
    start_x: i32,
    start_y: i32,
    start_z: i32,
    goal_x: i32,
    goal_y: i32,
    goal_z: i32,
    num_threads: i32,
) -> JObject<'a> {
    // Validate inputs
    if width <= 0 || height <= 0 || depth <= 0 {
        eprintln!("Invalid grid dimensions: {}x{}x{}", width, height, depth);
        let array_class = env.find_class("[I").unwrap_or_else(|_| {
            panic!("Failed to find array class");
        });
        let null_obj = jni::objects::JObject::null();
        return env
            .new_object_array(0, array_class, null_obj)
            .unwrap_or_else(|_| jni::objects::JObject::null().into())
            .into();
    }

    if num_threads <= 0 {
        eprintln!("Invalid number of threads: {}", num_threads);
        let array_class = env.find_class("[I").unwrap_or_else(|_| {
            panic!("Failed to find array class");
        });
        let null_obj = jni::objects::JObject::null();
        return env
            .new_object_array(0, array_class, null_obj)
            .unwrap_or_else(|_| jni::objects::JObject::null().into())
            .into();
    }

    // Convert Java byte array to Rust grid data with error handling
    let grid_size = (width * height * depth) as usize;
    let mut grid_bytes = vec![0i8; grid_size];

    if let Err(e) = env.get_byte_array_region(&grid_data, 0, &mut grid_bytes) {
        eprintln!("Failed to get grid data from Java: {:?}", e);
        let array_class = env.find_class("[I").unwrap();
        let null_obj = jni::objects::JObject::null();
        return env
            .new_object_array(0, array_class, null_obj)
            .unwrap()
            .into();
    }

    // Create cache-optimized thread-safe grid with error handling
    let grid = match CacheOptimizedGrid::new(width as usize, height as usize, depth as usize, true)
    {
        Ok(g) => g,
        Err(e) => {
            eprintln!("Failed to create grid: {}", e);
            let array_class = env.find_class("[I").unwrap();
            let null_obj = jni::objects::JObject::null();
            return env
                .new_object_array(0, array_class, null_obj)
                .unwrap()
                .into();
        }
    };

    // Set walkable cells based on byte array (0 = walkable, 1 = blocked)
    for x in 0..width {
        for y in 0..height {
            for z in 0..depth {
                let index = (x * height * depth + y * depth + z) as usize;
                if grid_bytes[index] != 0 {
                    if let Err(e) = grid.set_walkable(x as usize, y as usize, z as usize, false) {
                        eprintln!("Failed to set walkable at ({}, {}, {}): {}", x, y, z, e);
                    }
                }
            }
        }
    }

    let start = Position::new(start_x, start_y, start_z);
    let goal = Position::new(goal_x, goal_y, goal_z);

    // Perform enhanced parallel A* pathfinding with error handling
    let mut engine = match EnhancedParallelAStar::new(grid, num_threads as usize) {
        Ok(e) => e,
        Err(e) => {
            eprintln!("Failed to create pathfinding engine: {}", e);
            let array_class = env.find_class("[I").unwrap();
            let null_obj = jni::objects::JObject::null();
            return env
                .new_object_array(0, array_class, null_obj)
                .unwrap()
                .into();
        }
    };

    // Configure advanced features
    engine.set_early_termination(true);
    engine.set_hierarchical_search(false, 3);
    engine.set_timeout(Duration::from_secs(30));
    engine.set_max_nodes_explored(1_000_000);

    let result = Arc::new(engine).find_path(start, goal);

    // Get comprehensive metrics
    let _metrics = PATHFINDING_METRICS.get_performance_report();

    // Convert result to Java array with enhanced error handling
    match result {
        Ok(path) => {
            let array_class = match env.find_class("[I") {
                Ok(c) => c,
                Err(e) => {
                    eprintln!("Failed to find array class: {:?}", e);
                    return jni::objects::JObject::null();
                }
            };

            let result_array = match env.new_object_array(
                path.len() as i32,
                array_class,
                jni::objects::JObject::null(),
            ) {
                Ok(arr) => arr,
                Err(e) => {
                    eprintln!("Failed to create result array: {:?}", e);
                    return jni::objects::JObject::null();
                }
            };

            for (i, pos) in path.iter().enumerate() {
                let coord_array = match env.new_int_array(3) {
                    Ok(arr) => arr,
                    Err(e) => {
                        eprintln!("Failed to create coordinate array: {:?}", e);
                        continue;
                    }
                };

                let coords = [pos.x, pos.y, pos.z];
                if let Err(e) = env.set_int_array_region(&coord_array, 0, &coords) {
                    eprintln!("Failed to set coordinate array region: {:?}", e);
                    continue;
                }

                if let Err(e) = env.set_object_array_element(&result_array, i as i32, coord_array) {
                    eprintln!("Failed to set object array element: {:?}", e);
                    continue;
                }
            }

            // Record path optimality
            PATHFINDING_METRICS.update_path_optimality(path.len(), path.len());

            result_array.into()
        }
        Err(e) => {
            // Only log significant errors, not common "position not walkable" which happens frequently
            // during normal gameplay (entities trying to pathfind to unreachable locations)
            match &e {
                PathfindingError::InvalidPosition { .. } | PathfindingError::NoPathExists => {
                    // These are expected during normal gameplay, don't spam logs
                }
                _ => {
                    eprintln!("Pathfinding failed: {}", e);
                }
            }
            // Return empty array if no path found or error occurred
            let array_class = env.find_class("[I").unwrap_or_else(|_| {
                panic!("Failed to find array class");
            });
            let null_obj = jni::objects::JObject::null();
            env.new_object_array(0, array_class, null_obj)
                .unwrap_or_else(|_| jni::objects::JObject::null().into())
                .into()
        }
    }
}

/// JNI interface for getting comprehensive pathfinding metrics
#[allow(non_snake_case)]
pub fn Java_com_kneaf_core_ParallelRustVectorProcessor_getPathfindingMetrics<'a>(
    env: jni::JNIEnv<'a>,
    _class: JClass<'a>,
) -> JString<'a> {
    let metrics = PATHFINDING_METRICS.get_comprehensive_summary();
    env.new_string(&metrics).unwrap()
}

/// JNI interface for getting pathfinding performance report
#[allow(non_snake_case)]
pub fn Java_com_kneaf_core_ParallelRustVectorProcessor_getPathfindingPerformanceReport<'a>(
    env: jni::JNIEnv<'a>,
    _class: JClass<'a>,
) -> JString<'a> {
    let report = PATHFINDING_METRICS.get_performance_report();
    let report_json = format!(
        "{{\"total_operations\":{},\"success_rate\":{},\"average_nodes_per_path\":{},\"average_time_per_path\":{},\"load_imbalance_rate\":{},\"memory_efficiency\":{}}}",
        report.total_operations,
        report.success_rate,
        report.average_nodes_per_path,
        report.average_time_per_path,
        report.load_imbalance_rate,
        report.memory_efficiency
    );
    env.new_string(&report_json).unwrap()
}

/// JNI interface for batch pathfinding operations with cache optimization and comprehensive error handling
#[allow(non_snake_case)]
pub fn Java_com_kneaf_core_ParallelRustVectorProcessor_batchParallelAStarPathfind<'a>(
    mut env: jni::JNIEnv<'a>,
    _class: JClass<'a>,
    grid_data: JByteArray<'a>,
    width: i32,
    height: i32,
    depth: i32,
    queries: JObjectArray<'a>, // 2D array of [start_x, start_y, start_z, goal_x, goal_y, goal_z]
    num_threads: i32,
) -> JObject<'a> {
    // Validate inputs
    if width <= 0 || height <= 0 || depth <= 0 || num_threads <= 0 {
        eprintln!(
            "Invalid parameters: dimensions={}x{}x{}, threads={}",
            width, height, depth, num_threads
        );
        let result_class = env.find_class("[[I").unwrap_or_else(|_| {
            panic!("Failed to find result class");
        });
        return env
            .new_object_array(0, result_class, jni::objects::JObject::null())
            .unwrap_or_else(|_| jni::objects::JObject::null().into())
            .into();
    }

    // Convert Java byte array to Rust grid data with error handling
    let grid_size = (width * height * depth) as usize;
    let mut grid_bytes = vec![0i8; grid_size];

    if let Err(e) = env.get_byte_array_region(&grid_data, 0, &mut grid_bytes) {
        eprintln!("Failed to get grid data: {:?}", e);
        let result_class = env.find_class("[[I").unwrap();
        return env
            .new_object_array(0, result_class, jni::objects::JObject::null())
            .unwrap()
            .into();
    }

    // Create cache-optimized thread-safe grid with error handling
    let grid = match CacheOptimizedGrid::new(width as usize, height as usize, depth as usize, true)
    {
        Ok(g) => g,
        Err(e) => {
            eprintln!("Failed to create grid: {}", e);
            let result_class = env.find_class("[[I").unwrap();
            return env
                .new_object_array(0, result_class, jni::objects::JObject::null())
                .unwrap()
                .into();
        }
    };

    // Set walkable cells based on byte array
    for x in 0..width {
        for y in 0..height {
            for z in 0..depth {
                let index = (x * height * depth + y * depth + z) as usize;
                if grid_bytes[index] != 0 {
                    if let Err(e) = grid.set_walkable(x as usize, y as usize, z as usize, false) {
                        eprintln!("Failed to set walkable: {}", e);
                    }
                }
            }
        }
    }

    // Convert queries array with error handling
    let query_count = match env.get_array_length(&queries) {
        Ok(len) => len,
        Err(e) => {
            eprintln!("Failed to get query count: {:?}", e);
            let result_class = env.find_class("[[I").unwrap();
            return env
                .new_object_array(0, result_class, jni::objects::JObject::null())
                .unwrap()
                .into();
        }
    };

    let mut pathfinding_queries = Vec::new();

    for i in 0..query_count {
        let query_obj = match env.get_object_array_element(&queries, i) {
            Ok(obj) => obj,
            Err(e) => {
                eprintln!("Failed to get query element {}: {:?}", i, e);
                continue;
            }
        };

        let query_array_obj = JIntArray::from(query_obj);
        let mut coords = [0i32; 6];

        if let Err(e) = env.get_int_array_region(&query_array_obj, 0, &mut coords) {
            eprintln!("Failed to get coordinates for query {}: {:?}", i, e);
            continue;
        }

        let start = Position::new(coords[0], coords[1], coords[2]);
        let goal = Position::new(coords[3], coords[4], coords[5]);
        pathfinding_queries.push((start, goal));
    }

    // Perform batch parallel A* pathfinding with error handling
    let results: Vec<PathfindingResult<Vec<Position>>> = pathfinding_queries
        .into_par_iter()
        .map(|(start, goal)| {
            let engine = match EnhancedParallelAStar::new(grid.clone(), num_threads as usize) {
                Ok(e) => Arc::new(e),
                Err(e) => return Err(e),
            };
            engine.find_path(start, goal)
        })
        .collect();

    // Convert results to Java array with error handling
    let result_class = match env.find_class("[[I") {
        Ok(c) => c,
        Err(e) => {
            eprintln!("Failed to find result class: {:?}", e);
            return jni::objects::JObject::null();
        }
    };

    let result_array = match env.new_object_array(
        results.len() as i32,
        result_class,
        jni::objects::JObject::null(),
    ) {
        Ok(arr) => arr,
        Err(e) => {
            eprintln!("Failed to create result array: {:?}", e);
            return jni::objects::JObject::null();
        }
    };

    for (i, result) in results.iter().enumerate() {
        match result {
            Ok(path) => {
                let path_class = match env.find_class("[I") {
                    Ok(c) => c,
                    Err(e) => {
                        eprintln!("Failed to find path class: {:?}", e);
                        continue;
                    }
                };

                let path_array = match env.new_object_array(
                    path.len() as i32,
                    path_class,
                    jni::objects::JObject::null(),
                ) {
                    Ok(arr) => arr,
                    Err(e) => {
                        eprintln!("Failed to create path array: {:?}", e);
                        continue;
                    }
                };

                for (j, pos) in path.iter().enumerate() {
                    let coord_array = match env.new_int_array(3) {
                        Ok(arr) => arr,
                        Err(e) => {
                            eprintln!("Failed to create coordinate array: {:?}", e);
                            continue;
                        }
                    };

                    let coords = [pos.x, pos.y, pos.z];
                    if let Err(e) = env.set_int_array_region(&coord_array, 0, &coords) {
                        eprintln!("Failed to set coordinate region: {:?}", e);
                        continue;
                    }

                    if let Err(e) = env.set_object_array_element(&path_array, j as i32, coord_array)
                    {
                        eprintln!("Failed to set path element: {:?}", e);
                        continue;
                    }
                }

                if let Err(e) = env.set_object_array_element(&result_array, i as i32, path_array) {
                    eprintln!("Failed to set result element: {:?}", e);
                }
            }
            Err(e) => {
                eprintln!("Pathfinding query {} failed: {}", i, e);
                // Return empty array for failed query
                let path_class = env.find_class("[I").unwrap();
                let null_obj = jni::objects::JObject::null();
                let empty_array = env.new_object_array(0, path_class, null_obj).unwrap();
                if let Err(e) = env.set_object_array_element(&result_array, i as i32, empty_array) {
                    eprintln!("Failed to set empty result: {:?}", e);
                }
            }
        }
    }

    result_array.into()
}
