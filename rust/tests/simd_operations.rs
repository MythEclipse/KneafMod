use rustperf::{calculate_distances_simd, calculate_chunk_distances_simd};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_calculate_distances_simd_basic() {
        let positions = vec![
            (0.0, 0.0, 0.0),
            (3.0, 4.0, 0.0),
            (6.0, 8.0, 0.0),
        ];
        let center = (0.0, 0.0, 0.0);

        let distances = calculate_distances_simd(&positions, center);

        assert_eq!(distances.len(), 3);
        assert!((distances[0] - 0.0).abs() < 1e-6); // Distance from (0,0,0) to (0,0,0)
        assert!((distances[1] - 5.0).abs() < 1e-6); // Distance from (0,0,0) to (3,4,0)

    #[test]
    fn test_calculate_distances_simd_basic() {
        let positions = vec![
            (0.0, 0.0, 0.0),
            (3.0, 4.0, 0.0),
            (6.0, 8.0, 0.0),
        ];
        let center = (0.0, 0.0, 0.0);

        let distances = calculate_distances_simd(&positions, center);

        assert_eq!(distances.len(), 3);
        assert!((distances[0] - 0.0).abs() < 1e-6); // Distance from (0,0,0) to (0,0,0)
        assert!((distances[1] - 5.0).abs() < 1e-6); // Distance from (0,0,0) to (3,4,0)
        assert!((distances[2] - 10.0).abs() < 1e-6); // Distance from (0,0,0) to (6,8,0)
    }

    #[test]
    fn test_calculate_distances_simd_3d() {
        let positions = vec![
            (1.0, 2.0, 3.0),
            (4.0, 5.0, 6.0),
        ];
        let center = (1.0, 2.0, 3.0);

        let distances = calculate_distances_simd(&positions, center);

        assert_eq!(distances.len(), 2);
        assert!((distances[0] - 0.0).abs() < 1e-6); // Same point
        // Distance from (1,2,3) to (4,5,6) = sqrt((3)^2 + (3)^2 + (3)^2) = sqrt(27) ≈ 5.196
        assert!((distances[1] - (27.0_f32).sqrt()).abs() < 1e-6);
    }

    #[test]
    fn test_calculate_distances_simd_empty() {
        let positions: Vec<(f32, f32, f32)> = vec![];
        let center = (0.0, 0.0, 0.0);

        let distances = calculate_distances_simd(&positions, center);

        assert_eq!(distances.len(), 0);
    }

    #[test]
    fn test_calculate_distances_simd_large_batch() {
        let positions: Vec<(f32, f32, f32)> = (0..100).map(|i| (i as f32, 0.0, 0.0)).collect();
        let center = (0.0, 0.0, 0.0);

        let distances = calculate_distances_simd(&positions, center);

        assert_eq!(distances.len(), 100);
        for (i, &dist) in distances.iter().enumerate() {
            assert!((dist - i as f32).abs() < 1e-6);
        }
    }

    #[test]
    fn test_calculate_chunk_distances_simd_basic() {
        let chunk_coords = vec![
            (0, 0),
            (3, 4),
            (6, 8),
        ];
        let center_chunk = (0, 0);

        let distances = calculate_chunk_distances_simd(&chunk_coords, center_chunk);

        assert_eq!(distances.len(), 3);
        assert!((distances[0] - 0.0).abs() < 1e-6); // Distance from (0,0) to (0,0)
        assert!((distances[1] - 5.0).abs() < 1e-6); // Distance from (0,0) to (3,4)
        assert!((distances[2] - 10.0).abs() < 1e-6); // Distance from (0,0) to (6,8)
    }

    #[test]
    fn test_calculate_chunk_distances_simd_empty() {
        let chunk_coords: Vec<(i32, i32)> = vec![];
        let center_chunk = (0, 0);

        let distances = calculate_chunk_distances_simd(&chunk_coords, center_chunk);

        assert_eq!(distances.len(), 0);
    }

    #[test]
    fn test_calculate_chunk_distances_simd_negative_coords() {
        let chunk_coords = vec![
            (-3, -4),
            (3, 4),
        ];
        let center_chunk = (0, 0);

        let distances = calculate_chunk_distances_simd(&chunk_coords, center_chunk);

        assert_eq!(distances.len(), 2);
        assert!((distances[0] - 5.0).abs() < 1e-6); // Distance from (0,0) to (-3,-4)
        assert!((distances[1] - 5.0).abs() < 1e-6); // Distance from (0,0) to (3,4)
    }

    #[test]
    fn test_calculate_chunk_distances_simd_large_batch() {
        let chunk_coords: Vec<(i32, i32)> = (0..50).map(|i| (i, i)).collect();
        let center_chunk = (0, 0);

        let distances = calculate_chunk_distances_simd(&chunk_coords, center_chunk);

        assert_eq!(distances.len(), 50);
        for (i, &dist) in distances.iter().enumerate() {
            let expected = ((i * i + i * i) as f32).sqrt();
            assert!((dist - expected).abs() < 1e-6);
        }
    }

    #[test]
    fn test_simd_fallback_behavior() {
        // Test that the functions work regardless of SIMD availability
        // This test ensures the fallback implementation works correctly
        let positions = vec![(1.0, 2.0, 3.0), (4.0, 5.0, 6.0)];
        let center = (0.0, 0.0, 0.0);

        let distances = calculate_distances_simd(&positions, center);

        assert_eq!(distances.len(), 2);
        // Verify distances are calculated correctly
        assert!(distances[0] > 0.0);
        assert!(distances[1] > distances[0]);
    }
}