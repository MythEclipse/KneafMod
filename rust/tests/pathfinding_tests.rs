//! Tests for Pathfinding System

use std::sync::Arc;
use glam::Vec3;
use rustperf::pathfinding::{PathfindingSystem, PathfindingConfig, PathfindingGrid};
use rustperf::performance_monitor::PerformanceMonitor;

fn create_test_monitor() -> Arc<PerformanceMonitor> {
    Arc::new(PerformanceMonitor::new())
}

fn create_test_grid() -> PathfindingGrid {
    let mut grid = PathfindingGrid::new(10, 10);
    
    // Add some obstacles
    grid.set_obstacle(3, 3, true);
    grid.set_obstacle(3, 4, true);
    grid.set_obstacle(3, 5, true);
    grid.set_obstacle(4, 3, true);
    grid.set_obstacle(5, 3, true);
    
    grid
}

#[test]
fn test_simple_pathfinding() {
    let monitor = create_test_monitor();
    let system = PathfindingSystem::new(PathfindingConfig::default(), monitor);
    let grid = create_test_grid();
    
    let result = system.find_path(&grid, (0, 0), (9, 9));
    
    assert!(result.success);
    assert!(!result.path.is_empty());
    assert_eq!(result.path[0], (0, 0));
    assert_eq!(result.path[result.path.len() - 1], (9, 9));
}

#[test]
fn test_no_path_found() {
    let monitor = create_test_monitor();
    let system = PathfindingSystem::new(PathfindingConfig::default(), monitor);
    let mut grid = PathfindingGrid::new(5, 5);
    
    // Block the path completely
    for i in 0..5 {
        grid.set_obstacle(2, i, true);
    }
    
    let result = system.find_path(&grid, (0, 0), (4, 4));
    
    assert!(!result.success);
    assert!(result.path.is_empty());
}

#[test]
fn test_world_grid_conversion() {
    let grid_size = 1.0;
    let world_pos = Vec3::new(5.5, 0.0, 3.2);
    let grid_pos = PathfindingSystem::world_to_grid(world_pos, grid_size);
    
    assert_eq!(grid_pos, (5, 3));
    
    let converted_back = PathfindingSystem::grid_to_world(grid_pos, grid_size);
    assert_eq!(converted_back.x, 5.0);
    assert_eq!(converted_back.z, 3.0);
}

#[tokio::test]
async fn test_async_pathfinding() {
    let monitor = create_test_monitor();
    let system = PathfindingSystem::new(PathfindingConfig::default(), monitor);
    let grid = create_test_grid();
    
    let result = system.find_path_async(grid, (0, 0), (9, 9)).await;
    
    assert!(result.success);
    assert!(!result.path.is_empty());
}