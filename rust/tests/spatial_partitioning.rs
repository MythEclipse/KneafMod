use rustperf::{Aabb, ChunkCoord, QuadTree};
use valence::math::Vec3;

#[cfg(test)]
mod tests {
    use super::*;
    use bevy_ecs::entity::Entity;

    #[test]
    fn test_aabb_creation() {
        let center = Vec3::new(0.0, 0.0, 0.0);
        let size = Vec3::new(10.0, 10.0, 10.0);
        let aabb = Aabb::from_center_size(center, size);

        assert_eq!(aabb.min, Vec3::new(-5.0, -5.0, -5.0));
        assert_eq!(aabb.max, Vec3::new(5.0, 5.0, 5.0));
    }

    #[test]
    fn test_aabb_contains() {
        let aabb = Aabb::from_center_size(Vec3::ZERO, Vec3::splat(10.0));

        assert!(aabb.contains(Vec3::ZERO));
        assert!(aabb.contains(Vec3::new(4.9, 4.9, 4.9)));
        assert!(!aabb.contains(Vec3::new(5.1, 0.0, 0.0)));
        assert!(!aabb.contains(Vec3::new(0.0, 5.1, 0.0)));
        assert!(!aabb.contains(Vec3::new(0.0, 0.0, 5.1)));
    }

    #[test]
    fn test_aabb_intersects() {
        let aabb1 = Aabb::from_center_size(Vec3::ZERO, Vec3::splat(10.0));
        let aabb2 = Aabb::from_center_size(Vec3::new(5.0, 5.0, 5.0), Vec3::splat(10.0));
        let aabb3 = Aabb::from_center_size(Vec3::new(20.0, 20.0, 20.0), Vec3::splat(10.0));

        assert!(aabb1.intersects(&aabb2));
        assert!(!aabb1.intersects(&aabb3));
    }

    #[test]
    fn test_chunk_coord_from_world_pos() {
        let coord = ChunkCoord::from_world_pos(Vec3::new(16.0, 64.0, -8.0));
        assert_eq!(coord.x, 1);
        assert_eq!(coord.z, -1);

        let coord2 = ChunkCoord::from_world_pos(Vec3::new(-0.5, 0.0, 15.9));
        assert_eq!(coord2.x, -1);
        assert_eq!(coord2.z, 0);
    }

    #[test]
    fn test_chunk_coord_distance() {
        let coord1 = ChunkCoord { x: 0, z: 0 };
        let coord2 = ChunkCoord { x: 3, z: 4 };

        assert_eq!(coord1.distance_squared(&coord2), 25.0);
    }

    #[test]
    fn test_quadtree_creation() {
        let bounds = Aabb::from_center_size(Vec3::ZERO, Vec3::splat(100.0));
        let quadtree = QuadTree::new(bounds, 4, 8);

        assert_eq!(quadtree.entities.len(), 0);
        assert!(quadtree.children.is_none());
        assert_eq!(quadtree.max_entities, 4);
        assert_eq!(quadtree.max_depth, 8);
    }

    #[test]
    fn test_quadtree_insert_and_query() {
        let bounds = Aabb::from_center_size(Vec3::ZERO, Vec3::splat(100.0));
        let mut quadtree = QuadTree::new(bounds, 2, 4);

        // Insert some entities
        quadtree.insert(Entity::from_raw(1), Vec3::new(10.0, 0.0, 10.0), 0);
        quadtree.insert(Entity::from_raw(2), Vec3::new(-10.0, 0.0, -10.0), 0);
        quadtree.insert(Entity::from_raw(3), Vec3::new(20.0, 0.0, 20.0), 0);

        // Query a region that should contain entity 1
        let query_bounds = Aabb::from_center_size(Vec3::new(10.0, 0.0, 10.0), Vec3::splat(5.0));
        let results = quadtree.query(query_bounds);

        assert!(results.contains(&Entity::from_raw(1)));
        assert!(!results.contains(&Entity::from_raw(2)));
        assert!(!results.contains(&Entity::from_raw(3)));
    }

    #[test]
    fn test_quadtree_subdivision() {
        let bounds = Aabb::from_center_size(Vec3::ZERO, Vec3::splat(100.0));
        let mut quadtree = QuadTree::new(bounds, 1, 4);

        // Insert more entities than max_entities to trigger subdivision
        quadtree.insert(Entity::from_raw(1), Vec3::new(10.0, 0.0, 10.0), 0);
        quadtree.insert(Entity::from_raw(2), Vec3::new(-10.0, 0.0, -10.0), 0);

        // Should have subdivided
        assert!(quadtree.children.is_some());
        assert_eq!(quadtree.entities.len(), 0); // Entities moved to children
    }

    #[test]
    fn test_quadtree_out_of_bounds() {
        let bounds = Aabb::from_center_size(Vec3::ZERO, Vec3::splat(10.0));
        let mut quadtree = QuadTree::new(bounds, 4, 8);

        // Try to insert entity outside bounds
        quadtree.insert(Entity::from_raw(1), Vec3::new(100.0, 0.0, 100.0), 0);

        // Should not be inserted
        let query_bounds = Aabb::from_center_size(Vec3::new(100.0, 0.0, 100.0), Vec3::splat(1.0));
        let results = quadtree.query(query_bounds);
        assert!(!results.contains(&Entity::from_raw(1)));
    }

    #[test]
    fn test_quadtree_max_depth() {
        let bounds = Aabb::from_center_size(Vec3::ZERO, Vec3::splat(100.0));
        let mut quadtree = QuadTree::new(bounds, 1, 1); // Very low max_depth

        // Insert many entities to force depth limit
        for i in 0..10 {
            quadtree.insert(Entity::from_raw(i as u32), Vec3::new(i as f32 * 2.0, 0.0, i as f32 * 2.0), 0);
        }

        // Should still work despite depth limit
        let query_bounds = Aabb::from_center_size(Vec3::new(10.0, 0.0, 10.0), Vec3::splat(5.0));
        let results = quadtree.query(query_bounds);
        assert!(!results.is_empty());
    }

    #[test]
    fn test_quadtree_empty_query() {
        let bounds = Aabb::from_center_size(Vec3::ZERO, Vec3::splat(100.0));
        let quadtree = QuadTree::new(bounds, 4, 8);

        let query_bounds = Aabb::from_center_size(Vec3::new(1000.0, 0.0, 1000.0), Vec3::splat(1.0));
        let results = quadtree.query(query_bounds);

        assert!(results.is_empty());
    }

    #[test]
    fn test_quadtree_large_query() {
        let bounds = Aabb::from_center_size(Vec3::ZERO, Vec3::splat(100.0));
        let mut quadtree = QuadTree::new(bounds, 4, 8);

        // Insert several entities
        for i in 0..5 {
            quadtree.insert(Entity::from_raw(i as u32), Vec3::new(i as f32 * 10.0, 0.0, i as f32 * 10.0), 0);
        }

        // Query entire bounds
        let results = quadtree.query(bounds);
        assert_eq!(results.len(), 5);
    }
}