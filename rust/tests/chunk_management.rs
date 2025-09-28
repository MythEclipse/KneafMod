use std::collections::HashMap;
use valence::math::Vec3;

#[cfg(test)]
mod tests {
    use super::*;
    use rustperf::{ChunkCoord, ChunkData, ChunkManager};

    #[test]
    fn test_chunk_coord_from_world_pos() {
        let coord = ChunkCoord::from_world_pos(Vec3::new(16.0, 64.0, -8.0));
        assert_eq!(coord.x, 1);
        assert_eq!(coord.z, -1);

        let coord2 = ChunkCoord::from_world_pos(Vec3::new(-0.5, 0.0, 15.9));
        assert_eq!(coord2.x, -1);
        assert_eq!(coord2.z, 0);

        let coord3 = ChunkCoord::from_world_pos(Vec3::new(-16.0, 100.0, -32.0));
        assert_eq!(coord3.x, -1);
        assert_eq!(coord3.z, -2);
    }

    #[test]
    fn test_chunk_coord_center() {
        let coord = ChunkCoord { x: 1, z: -1 };
        let center = coord.center();
        assert_eq!(center.x, 24.0); // 1 * 16 + 8
        assert_eq!(center.y, 0.0);
        assert_eq!(center.z, -8.0); // -1 * 16 + 8
    }

    #[test]
    fn test_chunk_coord_distance_squared() {
        let coord1 = ChunkCoord { x: 0, z: 0 };
        let coord2 = ChunkCoord { x: 3, z: 4 };

        assert_eq!(coord1.distance_squared(&coord2), 25.0);

        let coord3 = ChunkCoord { x: 1, z: 1 };
        assert_eq!(coord1.distance_squared(&coord3), 2.0);
    }

    #[test]
    fn test_chunk_data_creation() {
        let coord = ChunkCoord { x: 5, z: 10 };
        let chunk_data = ChunkData {
            coord,
            entities: vec![],
            blocks: vec![],
            mobs: vec![],
            last_accessed: 1000,
            is_loaded: true,
        };

        assert_eq!(chunk_data.coord.x, 5);
        assert_eq!(chunk_data.coord.z, 10);
        assert!(chunk_data.entities.is_empty());
        assert!(chunk_data.blocks.is_empty());
        assert!(chunk_data.mobs.is_empty());
        assert_eq!(chunk_data.last_accessed, 1000);
        assert!(chunk_data.is_loaded);
    }

    #[test]
    fn test_chunk_manager_creation() {
        let mut chunk_manager = ChunkManager {
            loaded_chunks: HashMap::new(),
            view_distance: 8,
            unload_distance: 12,
        };

        assert!(chunk_manager.loaded_chunks.is_empty());
        assert_eq!(chunk_manager.view_distance, 8);
        assert_eq!(chunk_manager.unload_distance, 12);
    }

    #[test]
    fn test_chunk_manager_chunk_operations() {
        let mut chunk_manager = ChunkManager {
            loaded_chunks: HashMap::new(),
            view_distance: 8,
            unload_distance: 12,
        };

        let coord = ChunkCoord { x: 1, z: 2 };

        // Test inserting chunk
        chunk_manager.loaded_chunks.insert(coord, ChunkData {
            coord,
            entities: vec![],
            blocks: vec![],
            mobs: vec![],
            last_accessed: 0,
            is_loaded: true,
        });

        assert!(chunk_manager.loaded_chunks.contains_key(&coord));
        assert_eq!(chunk_manager.loaded_chunks.len(), 1);

        // Test accessing chunk
        if let Some(chunk) = chunk_manager.loaded_chunks.get_mut(&coord) {
            chunk.last_accessed = 100;
            assert_eq!(chunk.last_accessed, 100);
        }

        // Test removing chunk
        chunk_manager.loaded_chunks.remove(&coord);
        assert!(!chunk_manager.loaded_chunks.contains_key(&coord));
    }

    #[test]
    fn test_chunk_manager_view_distance_logic() {
        let chunk_manager = ChunkManager {
            loaded_chunks: HashMap::new(),
            view_distance: 2,
            unload_distance: 3,
        };

        let center_chunk = ChunkCoord { x: 0, z: 0 };

        // Test chunks within view distance
        let nearby_chunks = vec![
            ChunkCoord { x: 0, z: 0 }, // distance 0
            ChunkCoord { x: 1, z: 0 }, // distance 1
            ChunkCoord { x: 2, z: 0 }, // distance 2
            ChunkCoord { x: 0, z: 2 }, // distance 2
        ];

        for chunk in nearby_chunks {
            let distance_sq = center_chunk.distance_squared(&chunk);
            assert!(distance_sq <= (chunk_manager.view_distance * chunk_manager.view_distance) as f32);
        }

        // Test chunks outside view distance
        let far_chunks = vec![
            ChunkCoord { x: 3, z: 0 }, // distance 3
            ChunkCoord { x: 0, z: 3 }, // distance 3
            ChunkCoord { x: 3, z: 3 }, // distance ~4.24
        ];

        for chunk in far_chunks {
            let distance_sq = center_chunk.distance_squared(&chunk);
            assert!(distance_sq > (chunk_manager.view_distance * chunk_manager.view_distance) as f32);
        }
    }

    #[test]
    fn test_chunk_manager_unload_logic() {
        let chunk_manager = ChunkManager {
            loaded_chunks: HashMap::new(),
            view_distance: 2,
            unload_distance: 4,
        };

        let center_chunk = ChunkCoord { x: 0, z: 0 };

        // Chunks that should be unloaded (beyond unload_distance)
        let unload_chunks = vec![
            ChunkCoord { x: 5, z: 0 }, // distance 5
            ChunkCoord { x: 0, z: 5 }, // distance 5
        ];

        for chunk in unload_chunks {
            let distance_sq = center_chunk.distance_squared(&chunk);
            assert!(distance_sq > (chunk_manager.unload_distance * chunk_manager.unload_distance) as f32);
        }

        // Chunks that should be kept (within unload_distance)
        let keep_chunks = vec![
            ChunkCoord { x: 3, z: 0 }, // distance 3
            ChunkCoord { x: 4, z: 0 }, // distance 4
        ];

        for chunk in keep_chunks {
            let distance_sq = center_chunk.distance_squared(&chunk);
            assert!(distance_sq <= (chunk_manager.unload_distance * chunk_manager.unload_distance) as f32);
        }
    }

    #[test]
    fn test_chunk_manager_multiple_players() {
        let chunk_manager = ChunkManager {
            loaded_chunks: HashMap::new(),
            view_distance: 2,
            unload_distance: 3,
        };

        let player_chunks = vec![
            ChunkCoord { x: 0, z: 0 },
            ChunkCoord { x: 10, z: 10 },
        ];

        // Test that chunks near multiple players are kept
        let test_chunk = ChunkCoord { x: 5, z: 5 };

        let min_distance_sq = player_chunks.iter()
            .map(|pc| test_chunk.distance_squared(pc))
            .fold(f32::INFINITY, f32::min);

        // Chunk at (5,5) is closer to (0,0) than to (10,10)
        assert_eq!(min_distance_sq, 50.0); // distance squared to (0,0)

        // With view_distance=2, this chunk should be unloaded
        assert!(min_distance_sq > (chunk_manager.view_distance * chunk_manager.view_distance) as f32);
    }

    #[test]
    fn test_chunk_boundary_calculations() {
        // Test edge cases around chunk boundaries
        let test_positions = vec![
            (15.9, 0.0, 15.9),   // Just inside chunk (0,0)
            (16.1, 0.0, 16.1),   // Just inside chunk (1,1)
            (-0.1, 0.0, -0.1),   // Just inside chunk (-1,-1)
            (-16.1, 0.0, -16.1), // Just inside chunk (-2,-2)
        ];

        for (x, y, z) in test_positions {
            let coord = ChunkCoord::from_world_pos(Vec3::new(x, y, z));
            let center = coord.center();

            // Verify the position is within the chunk bounds
            let chunk_min_x = (coord.x * 16) as f32;
            let chunk_max_x = ((coord.x + 1) * 16) as f32;
            let chunk_min_z = (coord.z * 16) as f32;
            let chunk_max_z = ((coord.z + 1) * 16) as f32;

            assert!(x >= chunk_min_x && x < chunk_max_x);
            assert!(z >= chunk_min_z && z < chunk_max_z);
        }
    }
}