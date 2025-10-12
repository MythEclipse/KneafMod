use glam::Vec3A;
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct Aabb {
    pub min: Vec3A,
    pub max: Vec3A,
}

impl Aabb {
    pub fn new(min_x: f32, min_y: f32, min_z: f32, max_x: f32, max_y: f32, max_z: f32) -> Self {
        Self {
            min: Vec3A::new(min_x, min_y, min_z),
            max: Vec3A::new(max_x, max_y, max_z),
        }
    }

    pub fn contains_point(&self, point: &Vec3A) -> bool {
        point.x >= self.min.x
            && point.x <= self.max.x
            && point.y >= self.min.y
            && point.y <= self.max.y
            && point.z >= self.min.z
            && point.z <= self.max.z
    }

    pub fn intersects(&self, other: &Aabb) -> bool {
        self.min.x <= other.max.x
            && self.max.x >= other.min.x
            && self.min.y <= other.max.y
            && self.max.y >= other.min.y
            && self.min.z <= other.max.z
            && self.max.z >= other.min.z
    }
}

/// Custom error type for Rust performance operations
#[derive(Debug, Clone)]
pub struct RustPerformanceError {
    pub message: String,
    pub code: Option<i32>,
}

impl RustPerformanceError {
    pub fn new(message: String) -> Self {
        Self {
            message,
            code: None,
        }
    }

    pub fn with_code(message: String, code: i32) -> Self {
        Self {
            message,
            code: Some(code),
        }
    }
}

impl std::fmt::Display for RustPerformanceError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        if let Some(code) = self.code {
            write!(f, "[{}] {}", code, self.message)
        } else {
            write!(f, "{}", self.message)
        }
    }
}

impl std::error::Error for RustPerformanceError {}

/// Result type alias for Rust performance operations
pub type Result<T> = std::result::Result<T, RustPerformanceError>;
