use std::collections::BinaryHeap;
use std::cmp::Ordering;

#[derive(Clone, Copy, Debug)]
pub struct Matrix4(pub [f32; 16]);

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

#[derive(Clone, Copy, Debug)]
pub struct Vector3(pub [f32; 3]);

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

#[derive(Clone, Copy, Debug)]
pub struct Quaternion(pub [f32; 4]);

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

fn mul_quaternion(a: [f32; 4], b: [f32; 4]) -> [f32; 4] {
    [
        a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
        a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
        a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
        a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0],
    ]
}

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

fn manhattan(a: (i32, i32), b: (i32, i32)) -> i32 {
    (a.0 - b.0).abs() + (a.1 - b.1).abs()
}

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