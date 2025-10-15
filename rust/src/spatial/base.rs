use glam::Vec3A;
use serde::{Deserialize, Serialize};

/// Axis-Aligned Bounding Box for spatial operations
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
pub struct Aabb {
    pub min: Vec3A,
    pub max: Vec3A,
}

impl Aabb {
    /// Create a new AABB from minimum and maximum coordinates
    pub fn new(min: Vec3A, max: Vec3A) -> Self {
        Self { min, max }
    }

    /// Check if this AABB intersects with another AABB
    pub fn intersects(&self, other: &Aabb) -> bool {
        // Separating Axis Theorem for AABBs
        self.min.x <= other.max.x &&
        self.max.x >= other.min.x &&
        self.min.y <= other.max.y &&
        self.max.y >= other.min.y &&
        self.min.z <= other.max.z &&
        self.max.z >= other.min.z
    }

    /// Expand the AABB to include a point
    pub fn expand(&mut self, point: Vec3A) {
        self.min = self.min.min(point);
        self.max = self.max.max(point);
    }

    /// Get the center of the AABB
    pub fn center(&self) -> Vec3A {
        (self.min + self.max) / 2.0
    }

    /// Get the dimensions of the AABB
    pub fn dimensions(&self) -> Vec3A {
        self.max - self.min
    }

    /// Check if a point is inside the AABB
    pub fn contains(&self, point: Vec3A) -> bool {
        point.x >= self.min.x && point.x <= self.max.x &&
        point.y >= self.min.y && point.y <= self.max.y &&
        point.z >= self.min.z && point.z <= self.max.z
    }
}

/// Ray-AABB intersection test
/// Returns the distance to the intersection point if the ray intersects the AABB, None otherwise
pub fn ray_aabb_intersect(ray_origin: glam::Vec3, ray_dir: glam::Vec3, aabb: &Aabb) -> Option<f32> {
    // Avoid division by zero
    let inv_dir_x = if ray_dir.x != 0.0 { 1.0 / ray_dir.x } else { f32::INFINITY };
    let inv_dir_y = if ray_dir.y != 0.0 { 1.0 / ray_dir.y } else { f32::INFINITY };
    let inv_dir_z = if ray_dir.z != 0.0 { 1.0 / ray_dir.z } else { f32::INFINITY };

    let t1 = (aabb.min.x - ray_origin.x) * inv_dir_x;
    let t2 = (aabb.max.x - ray_origin.x) * inv_dir_x;
    let mut tmin = t1.min(t2);
    let mut tmax = t1.max(t2);

    let t1 = (aabb.min.y - ray_origin.y) * inv_dir_y;
    let t2 = (aabb.max.y - ray_origin.y) * inv_dir_y;
    tmin = tmin.max(t1.min(t2));
    tmax = tmax.min(t1.max(t2));

    let t1 = (aabb.min.z - ray_origin.z) * inv_dir_z;
    let t2 = (aabb.max.z - ray_origin.z) * inv_dir_z;
    tmin = tmin.max(t1.min(t2));
    tmax = tmax.min(t1.max(t2));

    if tmax >= tmin && tmax >= 0.0 {
        Some(tmin.max(0.0))
    } else {
        None
    }
}

/// SIMD-accelerated version (currently falls back to scalar)
pub fn ray_aabb_intersect_simd(ray_origin: glam::Vec3, ray_dir: glam::Vec3, aabb: &Aabb) -> Option<f32> {
    ray_aabb_intersect(ray_origin, ray_dir, aabb)
}

impl Default for Aabb {
    fn default() -> Self {
        Self {
            min: Vec3A::ZERO,
            max: Vec3A::ZERO,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use glam::Vec3A;

    #[test]
    fn test_aabb_intersection() {
        let a = Aabb::new(Vec3A::new(0.0, 0.0, 0.0), Vec3A::new(2.0, 2.0, 2.0));
        let b = Aabb::new(Vec3A::new(1.0, 1.0, 1.0), Vec3A::new(3.0, 3.0, 3.0));
        let c = Aabb::new(Vec3A::new(3.0, 3.0, 3.0), Vec3A::new(4.0, 4.0, 4.0));
        
        assert!(a.intersects(&b));
        assert!(!a.intersects(&c));
        assert!(b.intersects(&a));
        assert!(!b.intersects(&c));
    }

    #[test]
    fn test_aabb_contains() {
        let aabb = Aabb::new(Vec3A::new(0.0, 0.0, 0.0), Vec3A::new(2.0, 2.0, 2.0));
        
        assert!(aabb.contains(Vec3A::new(1.0, 1.0, 1.0)));
        assert!(aabb.contains(Vec3A::new(0.0, 0.0, 0.0)));
        assert!(aabb.contains(Vec3A::new(2.0, 2.0, 2.0)));
        assert!(!aabb.contains(Vec3A::new(2.1, 1.0, 1.0)));
    }

    #[test]
    fn test_aabb_expand() {
        let mut aabb = Aabb::new(Vec3A::new(0.0, 0.0, 0.0), Vec3A::new(1.0, 1.0, 1.0));
        aabb.expand(Vec3A::new(2.0, 2.0, 2.0));
        
        assert_eq!(aabb.min, Vec3A::new(0.0, 0.0, 0.0));
        assert_eq!(aabb.max, Vec3A::new(2.0, 2.0, 2.0));
    }
}
