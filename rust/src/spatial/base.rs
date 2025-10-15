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
