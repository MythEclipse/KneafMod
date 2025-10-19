use std::collections::BinaryHeap;
use std::cmp::Ordering;
use lazy_static::lazy_static;
use std::sync::Mutex;
use rayon::prelude::*;
use serde::{Deserialize, Serialize};
use glam::Mat4;
use faer::Mat;
use libc::c_double;

#[allow(dead_code)]
const GRAVITY: f64 = 0.01; // Matches vanilla Minecraft gravity strength
#[allow(dead_code)]
const AIR_DAMPING: f64 = 0.98;

lazy_static! {
    pub static ref ENTITY_PROCESSING_STATS: Mutex<EntityProcessingStats> = Mutex::new(EntityProcessingStats::default());
}

#[allow(dead_code)]
#[derive(Default)]
pub struct EntityProcessingStats {
    pub total_entities_processed: u64,
    pub native_optimizations_applied: u64,
    pub total_calculation_time_ns: u64,
    pub dimension_stats: std::collections::HashMap<String, u64>,
}

#[allow(dead_code)]
impl EntityProcessingStats {
    pub fn record_processing(&mut self, dimension: &str, entities_processed: u64, optimizations_applied: u64, calculation_time_ns: u64) {
        self.total_entities_processed += entities_processed;
        self.native_optimizations_applied += optimizations_applied;
        self.total_calculation_time_ns += calculation_time_ns;
        
        let dim_entry = self.dimension_stats.entry(dimension.to_string()).or_insert(0);
        *dim_entry += entities_processed;
    }
    
    pub fn get_summary(&self) -> String {
        let total_time_ms = (self.total_calculation_time_ns as f64 / 1_000_000.0).round() as u64;
        let avg_time_per_entity = if self.native_optimizations_applied > 0 {
            (self.total_calculation_time_ns as f64 / self.native_optimizations_applied as f64).round() as u64
        } else {
            0
        };
        
        let mut dimension_summary = String::new();
        for (dim, count) in &self.dimension_stats {
            dimension_summary.push_str(&format!("{}:{} ", dim, count));
        }
        
        format!("NativeEntityStats{{totalProcessed:{}, optimized:{}, totalTimeMs:{}, avgTimePerEntityNs:{}, dimensions:{}}}",
                self.total_entities_processed,
                self.native_optimizations_applied,
                total_time_ms,
                avg_time_per_entity,
                dimension_summary.trim_end_matches(&[' ', ','][..]))
    }
}

#[allow(dead_code)]
pub fn tick_entity_physics(data: &[f64; 6], on_ground: bool) -> [f64; 6] {
    let mut pos = [data[0], data[1], data[2]];
    let mut vel = [data[3], data[4], data[5]];

    // Apply gravity if not on ground
    if !on_ground {
        vel[1] -= GRAVITY;
    }

    // Apply air damping
    vel[0] *= AIR_DAMPING;
    vel[1] *= AIR_DAMPING;
    vel[2] *= AIR_DAMPING;

    // Update position
    pos[0] += vel[0];
    pos[1] += vel[1];
    pos[2] += vel[2];

    [pos[0], pos[1], pos[2], vel[0], vel[1], vel[2]]
}


#[allow(dead_code)]
#[derive(Clone, Copy, Debug)]
pub struct Matrix4(pub [f32; 16]);

#[allow(dead_code)]
impl Matrix4 {
    pub fn mul(&self, other: &Matrix4) -> Matrix4 {
        let mut result = [0.0; 16];
        for i in 0..4 {
            for j in 0..4 {
                for k in 0..4 {
                    result[i * 4 + j] += self.0[i * 4 + k] * other.0[k * 4 + j];
                }
            }
        }
        Matrix4(result)
    }
}

#[no_mangle]
pub extern "C" fn rustperf_vector_multiply(x: f64, y: f64, z: f64, scalar: f64) -> *mut c_double {
    // General vector multiplication (x * scalar, y * scalar, z * scalar)
    // Pure mathematical operation - no game-specific logic
    let vec_result = vec![x * scalar, y * scalar, z * scalar];
    let ptr = vec_result.as_ptr();
    std::mem::forget(vec_result); // Prevent premature cleanup
    ptr as *mut c_double
}

#[no_mangle]
pub extern "C" fn rustperf_vector_add(x1: f64, y1: f64, z1: f64, x2: f64, y2: f64, z2: f64) -> *mut c_double {
    // General vector addition (x1+x2, y1+y2, z1+z2)
    // Pure mathematical operation - no game-specific logic
    let vec_result = vec![x1 + x2, y1 + y2, z1 + z2];
    let ptr = vec_result.as_ptr();
    std::mem::forget(vec_result); // Prevent premature cleanup
    ptr as *mut c_double
}

#[no_mangle]
pub extern "C" fn rustperf_vector_damp(x: f64, y: f64, z: f64, damping: f64) -> *mut c_double {
    // General vector damping (x * damping, y * damping, z * damping)
    // Pure mathematical operation - no game-specific logic
    let vec_result = vec![x * damping, y * damping, z * damping];
    let ptr = vec_result.as_ptr();
    std::mem::forget(vec_result); // Prevent premature cleanup
    ptr as *mut c_double
}

#[allow(dead_code)]
#[derive(Clone, Copy, Debug)]
pub struct Vector3(pub [f32; 3]);

#[allow(dead_code)]
impl Vector3 {
    pub fn dot(&self, other: &Vector3) -> f32 {
        self.0[0] * other.0[0] + self.0[1] * other.0[1] + self.0[2] * other.0[2]
    }

    pub fn cross(&self, other: &Vector3) -> Vector3 {
        Vector3([
            self.0[1] * other.0[2] - self.0[2] * other.0[1],
            self.0[2] * other.0[0] - self.0[0] * other.0[2],
            self.0[0] * other.0[1] - self.0[1] * other.0[0],
        ])
    }

    pub fn normalize(&self) -> Vector3 {
        let len = (self.0[0] * self.0[0] + self.0[1] * self.0[1] + self.0[2] * self.0[2]).sqrt();
        if len > 0.0 {
            Vector3([self.0[0] / len, self.0[1] / len, self.0[2] / len])
        } else {
            *self
        }
    }

    #[cfg(target_arch = "x86_64")]
    pub fn dot_simd(&self, other: &Vector3) -> f32 {
        use std::arch::x86_64::*;
        unsafe {
            let a = _mm_loadu_ps(self.0.as_ptr());
            let b = _mm_loadu_ps(other.0.as_ptr());
            let mul = _mm_mul_ps(a, b);
            let shuf = _mm_shuffle_ps(mul, mul, 0b00011011);
            let sums = _mm_add_ps(mul, shuf);
            let shuf2 = _mm_shuffle_ps(sums, sums, 0b00000001);
            _mm_cvtss_f32(_mm_add_ss(sums, shuf2))
        }
    }

    #[cfg(not(target_arch = "x86_64"))]
    pub fn dot_simd(&self, other: &Vector3) -> f32 {
        self.dot(other)
    }
}

#[allow(dead_code)]
#[derive(Clone, Copy, Debug)]
pub struct Quaternion(pub [f32; 4]);

#[allow(dead_code)]
impl Quaternion {
    pub fn rotate_vector(&self, v: &Vector3) -> Vector3 {
        let q = self.0;
        let vq = [0.0, v.0[0], v.0[1], v.0[2]];
        let conj = [q[0], -q[1], -q[2], -q[3]];
        let temp = mul_quaternion(q, vq);
        let result = mul_quaternion(temp, conj);
        Vector3([result[1], result[2], result[3]])
    }
}

#[allow(dead_code)]
fn mul_quaternion(a: [f32; 4], b: [f32; 4]) -> [f32; 4] {
    [
        a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
        a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
        a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
        a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0],
    ]
}

#[allow(dead_code)]
#[derive(Clone, Eq, PartialEq)]
struct Node {
    position: (i32, i32),
    cost: i32,
    heuristic: i32,
}

impl Ord for Node {
    fn cmp(&self, other: &Self) -> Ordering {
        (other.cost + other.heuristic).cmp(&(self.cost + self.heuristic))
    }
}

impl PartialOrd for Node {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

#[allow(dead_code)]
pub fn a_star_pathfind(grid: &Vec<Vec<bool>>, start: (i32, i32), goal: (i32, i32)) -> Option<Vec<(i32, i32)>> {
    let rows = grid.len() as i32;
    let cols = grid[0].len() as i32;
    let mut open_set = BinaryHeap::new();
    let mut came_from = std::collections::HashMap::new();
    let mut g_score = std::collections::HashMap::new();
    g_score.insert(start, 0);
    open_set.push(Node {
        position: start,
        cost: 0,
        heuristic: manhattan(start, goal),
    });

    while let Some(current) = open_set.pop() {
        if current.position == goal {
            return Some(reconstruct_path(&came_from, current.position));
        }

        for neighbor in get_neighbors(current.position, rows, cols) {
            if grid[neighbor.0 as usize][neighbor.1 as usize] {
                continue;
            }
            let tentative_g = g_score[&current.position] + 1;
            if tentative_g < *g_score.get(&neighbor).unwrap_or(&i32::MAX) {
                came_from.insert(neighbor, current.position);
                g_score.insert(neighbor, tentative_g);
                open_set.push(Node {
                    position: neighbor,
                    cost: tentative_g,
                    heuristic: manhattan(neighbor, goal),
                });
            }
        }
    }
    None
}

#[allow(dead_code)]
fn manhattan(a: (i32, i32), b: (i32, i32)) -> i32 {
    (a.0 - b.0).abs() + (a.1 - b.1).abs()
}

#[allow(dead_code)]
fn get_neighbors(pos: (i32, i32), rows: i32, cols: i32) -> Vec<(i32, i32)> {
    let mut neighbors = Vec::new();
    let dirs = [(-1, 0), (1, 0), (0, -1), (0, 1)];
    for (dx, dy) in dirs.iter() {
        let nx = pos.0 + dx;
        let ny = pos.1 + dy;
        if nx >= 0 && nx < rows && ny >= 0 && ny < cols {
            neighbors.push((nx, ny));
        }
    }
    neighbors
}

#[allow(dead_code)]
fn reconstruct_path(came_from: &std::collections::HashMap<(i32, i32), (i32, i32)>, current: (i32, i32)) -> Vec<(i32, i32)> {
    let mut path = vec![current];
    let mut current = current;
    while let Some(&prev) = came_from.get(&current) {
        path.push(prev);
        current = prev;
    }
    path.reverse();
    path
}

#[allow(dead_code)]
#[derive(Serialize, Deserialize, Clone)]
pub struct PathQuery {
    pub start: (i32, i32),
    pub goal: (i32, i32),
}

#[allow(dead_code)]
pub fn batch_tick_entities(entities: &mut Vec<[f64;6]>, on_grounds: &[bool], dimension: &str) {
    let start_time = std::time::Instant::now();
    entities.par_iter_mut().zip(on_grounds.par_iter()).for_each(|(entity, &on_ground)| {
        *entity = tick_entity_physics(entity, on_ground);
    });
    let elapsed = start_time.elapsed().as_nanos() as u64;
    let entity_count = entities.len() as u64;
    if let Ok(mut stats) = ENTITY_PROCESSING_STATS.lock() {
        stats.record_processing(dimension, entity_count, entity_count, elapsed);
    }
}





// Internal helper - not an FFI entrypoint. Use Rust ABI for fixed-size arrays.
pub fn glam_matrix_mul(a: [f32; 16], b: [f32; 16]) -> [f32; 16] {
    let ma = Mat4::from_cols_array(&a);
    let mb = Mat4::from_cols_array(&b);
    let res = ma * mb;
    res.to_cols_array()
}

pub fn faer_matrix_mul(a: [f32; 16], b: [f32; 16]) -> [f32; 16] {
    let a_mat = Mat::<f32>::from_fn(4, 4, |i, j| a[i * 4 + j]);
    let b_mat = Mat::<f32>::from_fn(4, 4, |i, j| b[i * 4 + j]);
    let res = &a_mat * &b_mat;
    let mut result = [0.0; 16];
    for i in 0..4 {
        for j in 0..4 {
            result[i * 4 + j] = res[(i, j)];
        }
    }
    result
}


#[allow(dead_code)]
pub fn parallel_a_star(grid: &Vec<Vec<bool>>, queries: &[PathQuery]) -> Vec<Option<Vec<(i32,i32)>>> {
    queries.par_iter().map(|query| a_star_pathfind(grid, query.start, query.goal)).collect()
}