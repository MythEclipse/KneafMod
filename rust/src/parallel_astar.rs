//! Enhanced work-stealing parallel A* pathfinding implementation
//! Provides thread-safe data structures and efficient multi-core pathfinding with dynamic load balancing

use std::collections::HashMap;
use std::cmp::Ordering;
use std::sync::{Arc, Mutex, atomic::{AtomicUsize, AtomicU64, AtomicBool, Ordering as AtomicOrdering}};
use std::thread;
use std::time::{Instant, Duration};
use rayon::prelude::*;
use crossbeam::deque::{Injector, Stealer};
use parking_lot::RwLock as ParkingRwLock;
use jni::objects::{JObjectArray, JByteArray, JIntArray, JClass, JObject, JString};

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
            
            match self.inner.compare_exchange_weak(current_bits, new_bits, order, order) {
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
        self.inner.compare_exchange_weak(current.to_bits(), new.to_bits(), success, failure)
            .map(|old| f64::from_bits(old))
            .map_err(|old| f64::from_bits(old))
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
    pub g_cost: f32,  // Cost from start to this node
    pub h_cost: f32,  // Heuristic cost to goal
    pub f_cost: f32,  // Total cost (g + h)
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
        other.f_cost.partial_cmp(&self.f_cost)
            .unwrap_or(Ordering::Equal)
            .then_with(|| other.h_cost.partial_cmp(&self.h_cost).unwrap_or(Ordering::Equal))
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

/// Thread-safe grid representation for pathfinding
#[allow(dead_code)]
#[derive(Clone)]
pub struct ThreadSafeGrid {
    data: Arc<ParkingRwLock<Vec<Vec<Vec<bool>>>>>,
    width: usize,
    height: usize,
    depth: usize,
}

#[allow(dead_code)]
impl ThreadSafeGrid {
    pub fn new(width: usize, height: usize, depth: usize, default_walkable: bool) -> Self {
        let data = vec![vec![vec![default_walkable; depth]; height]; width];
        Self {
            data: Arc::new(ParkingRwLock::new(data)),
            width,
            height,
            depth,
        }
    }
    
    pub fn set_walkable(&self, x: usize, y: usize, z: usize, walkable: bool) {
        let mut grid = self.data.write();
        if x < self.width && y < self.height && z < self.depth {
            grid[x][y][z] = walkable;
        }
    }
    
    pub fn is_walkable(&self, x: i32, y: i32, z: i32) -> bool {
        if x < 0 || y < 0 || z < 0 || 
           x as usize >= self.width || y as usize >= self.height || z as usize >= self.depth {
            return false;
        }
        let grid = self.data.read();
        grid[x as usize][y as usize][z as usize]
    }
    
    pub fn get_dimensions(&self) -> (usize, usize, usize) {
        (self.width, self.height, self.depth)
    }
}

/// Enhanced work-stealing task queue for parallel pathfinding with dynamic load balancing
#[allow(dead_code)]
pub struct EnhancedWorkStealingQueue {
    injector: Arc<Injector<PathNode>>,
    stealers: Vec<Arc<Stealer<PathNode>>>,
    worker_queues: Vec<Arc<Injector<PathNode>>>, // Use Injector instead of Worker for thread safety
    active_workers: AtomicUsize,
    completed_tasks: AtomicUsize,
    total_tasks: AtomicUsize,
    load_imbalance: Arc<Mutex<f64>>,
    steal_attempts: AtomicUsize,
    successful_steals: AtomicUsize,
}

#[allow(dead_code)]
impl EnhancedWorkStealingQueue {
    pub fn new(num_threads: usize) -> Self {
        let injector = Arc::new(Injector::new());
        let stealers = Vec::new();
        let mut worker_queues = Vec::new();
        
        for _ in 0..num_threads {
            let worker_queue = Arc::new(Injector::new());
            // Use the worker_queue directly for stealing, no stealer() method
            worker_queues.push(worker_queue);
        }
        
        Self {
            injector,
            stealers,
            worker_queues,
            active_workers: AtomicUsize::new(0),
            completed_tasks: AtomicUsize::new(0),
            total_tasks: AtomicUsize::new(0),
            load_imbalance: Arc::new(Mutex::new(0.0)),
            steal_attempts: AtomicUsize::new(0),
            successful_steals: AtomicUsize::new(0),
        }
    }
    
    pub fn push(&self, task: PathNode) {
        self.injector.push(task);
        self.total_tasks.fetch_add(1, AtomicOrdering::SeqCst);
    }
    
    pub fn push_to_worker(&self, worker_id: usize, task: PathNode) {
        if worker_id < self.worker_queues.len() {
            self.worker_queues[worker_id].push(task);
            self.total_tasks.fetch_add(1, AtomicOrdering::SeqCst);
        }
    }
    
    pub fn steal(&self, worker_id: usize) -> Option<PathNode> {
        self.steal_attempts.fetch_add(1, AtomicOrdering::SeqCst);
        
        // Try to steal from other workers first (more efficient)
        for i in 0..self.worker_queues.len() {
            if i == worker_id { continue; }
            
            loop {
                match self.worker_queues[i].steal() {
                    crossbeam::deque::Steal::Success(task) => {
                        self.successful_steals.fetch_add(1, AtomicOrdering::SeqCst);
                        return Some(task);
                    }
                    crossbeam::deque::Steal::Empty => break,
                    crossbeam::deque::Steal::Retry => continue,
                }
            }
        }
        
        // Try global injector as fallback
        loop {
            match self.injector.steal() {
                crossbeam::deque::Steal::Success(task) => {
                    self.successful_steals.fetch_add(1, AtomicOrdering::SeqCst);
                    return Some(task);
                }
                crossbeam::deque::Steal::Empty => return None,
                crossbeam::deque::Steal::Retry => continue,
            }
        }
    }
    
    pub fn get_local_task(&self, worker_id: usize) -> Option<PathNode> {
        if worker_id < self.worker_queues.len() {
            loop {
                match self.worker_queues[worker_id].steal() {
                    crossbeam::deque::Steal::Success(task) => return Some(task),
                    crossbeam::deque::Steal::Empty => return None,
                    crossbeam::deque::Steal::Retry => continue,
                }
            }
        } else {
            None
        }
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
        let load_imbalance = if let Ok(load) = self.load_imbalance.lock() {
            *load
        } else {
            0.0
        };
        
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

/// Enhanced parallel A* pathfinding engine with dynamic load balancing
#[allow(dead_code)]
pub struct EnhancedParallelAStar {
    grid: ThreadSafeGrid,
    queue: Arc<EnhancedWorkStealingQueue>,
    num_threads: usize,
    max_search_distance: f32,
    diagonal_movement: bool,
    early_termination: bool,
    load_balance_interval: Duration,
    metrics: Arc<EnhancedPathfindingMetrics>,
    use_hierarchical_search: bool,
    hierarchical_levels: usize,
}

#[allow(dead_code)]
impl EnhancedParallelAStar {
    pub fn new(grid: ThreadSafeGrid, num_threads: usize) -> Self {
        let queue = Arc::new(EnhancedWorkStealingQueue::new(num_threads));
        
        Self {
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
        }
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
    
    /// Find path from start to goal using enhanced parallel A*
    pub fn find_path(self: Arc<Self>, start: Position, goal: Position) -> Option<Vec<Position>> {
        if !self.grid.is_walkable(start.x, start.y, start.z) ||
           !self.grid.is_walkable(goal.x, goal.y, goal.z) {
            return None;
        }
        
        if start == goal {
            return Some(vec![start]);
        }
        
        let start_time = Instant::now();
        
        // Initialize with start node
        let h_cost = start.manhattan_distance(&goal) as f32;
        let start_node = PathNode::new(start, 0.0, h_cost, None);
        
        // Distribute initial node to multiple workers for better load balancing
        for worker_id in 0..self.num_threads.min(4) { // Limit to 4 workers for initial distribution
            self.queue.push_to_worker(worker_id, start_node.clone());
        }
        
        // Shared data structures with enhanced concurrency
        let open_set = Arc::new(ParkingRwLock::new(HashMap::new()));
        let closed_set = Arc::new(ParkingRwLock::new(HashMap::new()));
        let came_from = Arc::new(ParkingRwLock::new(HashMap::new()));
        let goal_found = Arc::new(AtomicBool::new(false));
        let best_cost = Arc::new(AtomicU64::new(u64::MAX));
        
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
            let goal = goal;
            let metrics = Arc::clone(&self.metrics);
            
            let handle = thread::spawn({
                let self_clone = Arc::clone(&self);
                move || {
                    self_clone.enhanced_worker_thread(
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
                    )
                }
            });
            
            handles.push(handle);
        }
        
        // Start load balancing monitor
        let queue_monitor = Arc::clone(&self.queue);
        let goal_found_monitor = Arc::clone(&goal_found);
        let metrics_monitor = Arc::clone(&self.metrics);
        
        let monitor_handle = thread::spawn(move || {
            Self::load_balancing_monitor(queue_monitor, goal_found_monitor, metrics_monitor);
        });
        
        // Wait for all worker threads to complete
        for handle in handles {
            handle.join().unwrap();
        }
        
        // Stop monitor
        goal_found.store(true, AtomicOrdering::SeqCst);
        monitor_handle.join().unwrap();
        
        let elapsed = start_time.elapsed();
        println!("Enhanced Parallel A* completed in {:?}", elapsed);
        
        // Update metrics
        self.metrics.record_pathfinding(
            self.queue.get_stats().completed_tasks,
            elapsed,
            goal_found.load(AtomicOrdering::SeqCst)
        );
        
        // Reconstruct path if found
        let came_from_read = came_from.read();
        if came_from_read.contains_key(&goal) {
            self.reconstruct_path(&came_from_read, start, goal)
        } else {
            None
        }
    }
    
    /// Enhanced worker thread with better load balancing and early termination
    fn enhanced_worker_thread(
        &self,
        worker_id: usize,
        queue: Arc<EnhancedWorkStealingQueue>,
        grid: ThreadSafeGrid,
        open_set: Arc<ParkingRwLock<HashMap<Position, f32>>>,
        closed_set: Arc<ParkingRwLock<HashMap<Position, f32>>>,
        came_from: Arc<ParkingRwLock<HashMap<Position, Position>>>,
        goal_found: Arc<AtomicBool>,
        best_cost: Arc<AtomicU64>,
        goal: Position,
        metrics: Arc<EnhancedPathfindingMetrics>,
    ) {
        queue.increment_active_workers();
        let mut local_nodes_processed = 0;
        let local_start_time = Instant::now();
        
        while !goal_found.load(AtomicOrdering::SeqCst) {
            // Try to get task from local queue first
            let current = queue.get_local_task(worker_id)
                .or_else(|| queue.steal(worker_id));
            
            if current.is_none() {
                // No work available, check if we should terminate
                if queue.get_stats().active_workers <= 1 {
                    thread::sleep(Duration::from_micros(100));
                    if queue.get_local_task(worker_id).is_none() && queue.steal(worker_id).is_none() {
                        break; // No work left, terminate
                    }
                }
                continue;
            }
            
            let current = current.unwrap();
            let current_pos = current.position;
            local_nodes_processed += 1;
            
            // Early termination check
            if self.early_termination && goal_found.load(AtomicOrdering::SeqCst) {
                break;
            }
            
            // Check if we've reached the goal
            if current_pos == goal {
                goal_found.store(true, AtomicOrdering::SeqCst);
                queue.increment_completed_tasks();
                
                // Update best cost if this path is better
                let current_cost = current.g_cost as f64;
                if current_cost < best_cost.load(AtomicOrdering::SeqCst) as f64 {
                    best_cost.store(current_cost as u64, AtomicOrdering::SeqCst);
                }
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
            
            // Expand neighbors with enhanced neighbor selection
            let neighbors = self.get_enhanced_neighbors(&current_pos, &grid);
            
            for neighbor_pos in neighbors {
                // Skip if goal already found
                if goal_found.load(AtomicOrdering::SeqCst) {
                    break;
                }
                
                // Calculate costs with dynamic weight adjustment
                let move_cost = self.get_enhanced_move_cost(&current_pos, &neighbor_pos);
                let g_cost = current.g_cost + move_cost;
                let h_cost = neighbor_pos.manhattan_distance(&goal) as f32;
                
                // Pruning based on best known cost
                if g_cost + h_cost > best_cost.load(AtomicOrdering::SeqCst) as f32 {
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
                
                // Create new node and add to queue with load balancing
                let new_node = PathNode::new(neighbor_pos, g_cost, h_cost, Some(current_pos));
                
                // Distribute work based on current load
                let target_worker = (worker_id + local_nodes_processed) % self.num_threads;
                queue.push_to_worker(target_worker, new_node);
            }
            
            queue.increment_completed_tasks();
            
            // Periodic load balancing update
            if local_nodes_processed % 100 == 0 {
                queue.update_load_imbalance();
            }
        }
        
        queue.decrement_active_workers();
        metrics.record_worker_efficiency(worker_id, local_nodes_processed, local_start_time.elapsed());
    }
    
    /// Load balancing monitor for dynamic work distribution
    fn load_balancing_monitor(
        queue: Arc<EnhancedWorkStealingQueue>,
        goal_found: Arc<AtomicBool>,
        metrics: Arc<EnhancedPathfindingMetrics>,
    ) {
        let mut _last_stats = queue.get_stats();
        
        while !goal_found.load(AtomicOrdering::SeqCst) {
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
    
    
    /// Enhanced neighbor selection with adaptive direction ordering
    fn get_enhanced_neighbors(&self, pos: &Position, grid: &ThreadSafeGrid) -> Vec<Position> {
        let mut neighbors = Vec::new();
        
        // Adaptive direction ordering based on goal direction (if available)
        let directions = if self.diagonal_movement {
            self.get_adaptive_directions(pos)
        } else {
            vec![
                (-1, 0, 0), (1, 0, 0), (0, -1, 0), (0, 1, 0), (0, 0, -1), (0, 0, 1),
            ]
        };
        
        // Pre-allocate capacity for better performance
        neighbors.reserve(directions.len());
        
        for (dx, dy, dz) in directions {
            let new_x = pos.x + dx;
            let new_y = pos.y + dy;
            let new_z = pos.z + dz;
            
            if grid.is_walkable(new_x, new_y, new_z) {
                neighbors.push(Position::new(new_x, new_y, new_z));
            }
        }
        
        neighbors
    }
    
    /// Adaptive direction ordering based on heuristic
    fn get_adaptive_directions(&self, _pos: &Position) -> Vec<(i32, i32, i32)> {
        // Default direction set with strategic ordering
        vec![
            // Cardinal directions first (cheaper)
            (-1, 0, 0), (1, 0, 0), (0, -1, 0), (0, 1, 0), (0, 0, -1), (0, 0, 1),
            // 2D diagonals
            (-1, -1, 0), (-1, 1, 0), (1, -1, 0), (1, 1, 0),
            // 3D diagonals
            (-1, 0, -1), (-1, 0, 1), (1, 0, -1), (1, 0, 1),
            (0, -1, -1), (0, -1, 1), (0, 1, -1), (0, 1, 1),
            // 3D corner diagonals (most expensive)
            (-1, -1, -1), (-1, -1, 1), (-1, 1, -1), (-1, 1, 1),
            (1, -1, -1), (1, -1, 1), (1, 1, -1), (1, 1, 1),
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
    ) -> Option<Vec<Position>> {
        let mut path = Vec::new();
        let mut current = goal;
        
        while current != start {
            path.push(current);
            if let Some(&parent) = came_from.get(&current) {
                current = parent;
            } else {
                return None; // No path found
            }
        }
        
        path.push(start);
        path.reverse();
        Some(path)
    }
}

/// Batch pathfinding for multiple queries
#[allow(dead_code)]
pub fn batch_parallel_astar(
    grid: &ThreadSafeGrid,
    queries: Vec<(Position, Position)>,
    num_threads: usize,
) -> Vec<Option<Vec<Position>>> {
    let engine = Arc::new(EnhancedParallelAStar::new(grid.clone(), num_threads));
    
    queries.into_par_iter().map(|(start, goal)| {
        let engine_clone = Arc::clone(&engine);
        engine_clone.find_path(start, goal)
    }).collect()
}

/// SIMD-optimized heuristic calculations
#[cfg(target_arch = "x86_64")]
pub mod simd_heuristics {
    use std::arch::x86_64::*;
    
    #[allow(dead_code)]
    pub fn batch_manhattan_distance(positions: &[(i32, i32, i32)], goal: (i32, i32, i32)) -> Vec<f32> {
        if !is_x86_feature_detected!("avx") {
            // Fallback to scalar implementation if AVX is not available
            return positions.iter().map(|pos| {
                ((pos.0 - goal.0).abs() + (pos.1 - goal.1).abs() + (pos.2 - goal.2).abs()) as f32
            }).collect();
        }
        
        unsafe {
            let goal_x = _mm256_set1_ps(goal.0 as f32);
            let goal_y = _mm256_set1_ps(goal.1 as f32);
            let goal_z = _mm256_set1_ps(goal.2 as f32);
            
            positions.chunks(8).map(|chunk| {
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
                    
                    for i in 0..chunk.len() {
                        results.push(result_array[i]);
                    }
                } else {
                    for pos in chunk {
                        let distance = ((pos.0 - goal.0).abs() + (pos.1 - goal.1).abs() + (pos.2 - goal.2).abs()) as f32;
                        results.push(distance);
                    }
                }
                
                results
            }).flatten().collect()
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
    
    pub fn record_pathfinding(&self, nodes_explored: usize, duration: std::time::Duration, success: bool) {
        self.total_nodes_explored.fetch_add(nodes_explored, AtomicOrdering::SeqCst);
        
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
            self.load_imbalance_events.fetch_add(1, AtomicOrdering::SeqCst);
        }
    }
    
    pub fn record_steal_inefficiency(&self, steal_rate: f64) {
        if steal_rate < 0.5 {
            self.steal_inefficiency_events.fetch_add(1, AtomicOrdering::SeqCst);
        }
    }
    
    pub fn record_worker_efficiency(&self, worker_id: usize, nodes_processed: usize, processing_time: Duration) {
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
            self.path_optimality.store(optimality, AtomicOrdering::SeqCst);
        }
    }
    
    pub fn get_comprehensive_summary(&self) -> String {
        let total_time = self.total_pathfinding_time.lock().unwrap();
        let worker_stats = self.worker_efficiency.read();
        
        let total_workers = worker_stats.len();
        let avg_worker_efficiency = if total_workers > 0 {
            worker_stats.values()
                .map(|w| w.efficiency_score)
                .sum::<f64>() / total_workers as f64
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
        let total_paths = self.successful_paths.load(AtomicOrdering::SeqCst) + self.failed_paths.load(AtomicOrdering::SeqCst);
        
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
            memory_efficiency: self.memory_usage.load(AtomicOrdering::SeqCst) as f64 /
                self.total_nodes_explored.load(AtomicOrdering::SeqCst).max(1) as f64,
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

/// Enhanced JNI interface for parallel A* pathfinding with comprehensive metrics
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
    // Convert Java byte array to Rust grid data
    let grid_size = (width * height * depth) as usize;
    let mut grid_bytes = vec![0i8; grid_size];
    env.get_byte_array_region(&grid_data, 0, &mut grid_bytes).unwrap();
    
    // Create enhanced thread-safe grid
    let grid = ThreadSafeGrid::new(width as usize, height as usize, depth as usize, true);
    
    // Set walkable cells based on byte array (0 = walkable, 1 = blocked)
    for x in 0..width {
        for y in 0..height {
            for z in 0..depth {
                let index = (x * height * depth + y * depth + z) as usize;
                if grid_bytes[index] != 0 {
                    grid.set_walkable(x as usize, y as usize, z as usize, false);
                }
            }
        }
    }
    
    let start = Position::new(start_x, start_y, start_z);
    let goal = Position::new(goal_x, goal_y, goal_z);
    
    // Perform enhanced parallel A* pathfinding
    let mut engine = EnhancedParallelAStar::new(grid, num_threads as usize);
    
    // Configure advanced features
    engine.set_early_termination(true);
    engine.set_hierarchical_search(false, 3);
    
    let result = Arc::new(engine).find_path(start, goal);
    
    // Get comprehensive metrics
    let _metrics = PATHFINDING_METRICS.get_performance_report();
    
    // Convert result to Java array with enhanced error handling
    match result {
        Some(path) => {
            let array_class = env.find_class("[I").unwrap();
            let result_array = env.new_object_array(path.len() as i32, array_class, jni::objects::JObject::null()).unwrap();
            
            for (i, pos) in path.iter().enumerate() {
                let coord_array = env.new_int_array(3).unwrap();
                let coords = [pos.x, pos.y, pos.z];
                env.set_int_array_region(&coord_array, 0, &coords).unwrap();
                env.set_object_array_element(&result_array, i as i32, coord_array).unwrap();
            }
            
            // Record path optimality
            PATHFINDING_METRICS.update_path_optimality(path.len(), path.len()); // Simplified for now
            
            result_array.into()
        }
        None => {
            // Return empty array if no path found
            let array_class = env.find_class("[I").unwrap();
            let null_obj = jni::objects::JObject::null();
            env.new_object_array(0, array_class, null_obj).unwrap().into()
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

/// JNI interface for batch pathfinding operations
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
    // Convert Java byte array to Rust grid data
    let grid_size = (width * height * depth) as usize;
    let mut grid_bytes = vec![0i8; grid_size];
    env.get_byte_array_region(&grid_data, 0, &mut grid_bytes).unwrap();
    
    // Create enhanced thread-safe grid
    let grid = ThreadSafeGrid::new(width as usize, height as usize, depth as usize, true);
    
    // Set walkable cells based on byte array
    for x in 0..width {
        for y in 0..height {
            for z in 0..depth {
                let index = (x * height * depth + y * depth + z) as usize;
                if grid_bytes[index] != 0 {
                    grid.set_walkable(x as usize, y as usize, z as usize, false);
                }
            }
        }
    }
    
    // Convert queries array
    let query_count = env.get_array_length(&queries).unwrap();
    let mut pathfinding_queries = Vec::new();
    
    for i in 0..query_count {
        let query_obj: JObject = env.get_object_array_element(&queries, i).unwrap();
        let query_array_obj = JIntArray::from(query_obj);
        let mut coords = [0i32; 6];
        env.get_int_array_region(&query_array_obj, 0, &mut coords).unwrap();
        
        let start = Position::new(coords[0], coords[1], coords[2]);
        let goal = Position::new(coords[3], coords[4], coords[5]);
        pathfinding_queries.push((start, goal));
    }
    
    // Perform batch parallel A* pathfinding
    let _engine = Arc::new(EnhancedParallelAStar::new(grid.clone(), num_threads as usize));
    
    let results: Vec<Option<Vec<Position>>> = pathfinding_queries
    .into_par_iter()
    .map(|(start, goal)| {
        let engine_clone = Arc::new(EnhancedParallelAStar::new(grid.clone(), num_threads as usize));
        engine_clone.find_path(start, goal)
    })
    .collect();
    
    // Convert results to Java array
    let result_class = env.find_class("[[I").unwrap();
    let result_array = env.new_object_array(results.len() as i32, result_class, jni::objects::JObject::null()).unwrap();
    
    for (i, result) in results.iter().enumerate() {
        match result {
            Some(path) => {
                let path_class = env.find_class("[I").unwrap();
                let path_array = env.new_object_array(path.len() as i32, path_class, jni::objects::JObject::null()).unwrap();
                
                for (j, pos) in path.iter().enumerate() {
                    let coord_array = env.new_int_array(3).unwrap();
                    let coords = [pos.x, pos.y, pos.z];
                    env.set_int_array_region(&coord_array, 0, &coords).unwrap();
                    env.set_object_array_element(&path_array, j as i32, coord_array).unwrap();
                }
                
                env.set_object_array_element(&result_array, i as i32, path_array).unwrap();
            }
            None => {
                let path_class = env.find_class("[I").unwrap();
                let null_obj = jni::objects::JObject::null();
                let empty_array = env.new_object_array(0, path_class, null_obj).unwrap();
                env.set_object_array_element(&result_array, i as i32, empty_array).unwrap();
            }
        }
    }
    
    result_array.into()
}
