use std::sync::Arc;
use std::time::Duration;

use crate::memory::cleanup::background_cleanup::{
    BackgroundCleanupManager, CleanupConfig, CleanupPriority, CleanupTaskType,
};
use crate::memory::monitoring::real_time_monitor::RealTimeMemoryMonitor;
use crate::memory::leak_detector::MemoryLeakDetector;
use crate::memory::pool::lru_eviction::LRUEvictionMemoryPool;
use crate::memory::pool::enhanced_manager::EnhancedMemoryPoolManager;

/// Example demonstrating how to set up and use the BackgroundCleanupManager
pub fn setup_background_cleanup() -> Result<BackgroundCleanupManager, String> {
    // 1. Create necessary dependencies
    let monitor = Arc::new(RealTimeMemoryMonitor::new(None)?);
    let leak_detector = Arc::new(MemoryLeakDetector::new(None, Arc::clone(&monitor))?);
    let lru_pool = Arc::new(LRUEvictionMemoryPool::new(None)?);
    let pool_manager = Arc::new(EnhancedMemoryPoolManager::new(None)?);

    // 2. Create and configure the cleanup manager
    let mut cleanup_manager = BackgroundCleanupManager::new(
        Arc::clone(&monitor),
        Arc::clone(&leak_detector),
        Arc::clone(&lru_pool),
        Arc::clone(&pool_manager),
    );

    // 3. Customize cleanup configuration
    let mut config = cleanup_manager.get_config();
    
    // Adjust thresholds based on your application's needs
    config.memory_pressure_threshold = 0.90; // Trigger cleanup at 90% memory usage
    config.leak_detection_threshold = 200;   // Trigger cleanup for 200+ leaked objects
    config.fragmentation_threshold = 0.75;   // Trigger defragmentation at 75% fragmentation
    config.periodic_cleanup_interval = Duration::from_secs(600); // Cleanup every 10 minutes
    
    cleanup_manager.update_config(config)?;

    // 4. Start the background cleanup service
    cleanup_manager.start_cleanup_service()?;

    // 5. Register some initial cleanup tasks (optional)
    cleanup_manager.register_cleanup_task(CleanupTask {
        task_type: CleanupTaskType::Periodic,
        priority: CleanupPriority::Low,
        description: "Initial periodic cleanup task".to_string(),
        timestamp: std::time::Instant::now(),
    })?;

    Ok(cleanup_manager)
}

/// Example demonstrating how to manually trigger cleanup operations
pub fn manual_cleanup_demo(cleanup_manager: &BackgroundCleanupManager) -> Result<(), String> {
    // Trigger memory pressure cleanup
    let memory_cleanup_result = cleanup_manager.trigger_cleanup(CleanupTaskType::MemoryPressure)?;
    println!("Memory pressure cleanup result: {:?}", memory_cleanup_result);

    // Trigger leak detection cleanup
    let leak_cleanup_result = cleanup_manager.trigger_cleanup(CleanupTaskType::LeakDetection)?;
    println!("Leak detection cleanup result: {:?}", leak_cleanup_result);

    // Trigger fragmentation cleanup
    let fragmentation_cleanup_result = cleanup_manager.trigger_cleanup(CleanupTaskType::Fragmentation)?;
    println!("Fragmentation cleanup result: {:?}", fragmentation_cleanup_result);

    Ok(())
}

/// Example demonstrating how to integrate with application lifecycle
pub fn integrate_with_lifecycle() {
    // In a real application, you would initialize this during application startup
    let cleanup_manager = match setup_background_cleanup() {
        Ok(manager) => manager,
        Err(e) => {
            eprintln!("Failed to setup background cleanup: {}", e);
            return;
        }
    };

    // In a real application, you would run your main application logic here
    println!("Application is running with background cleanup active...");
    std::thread::sleep(Duration::from_secs(5));

    // Perform manual cleanup demonstration
    if let Err(e) = manual_cleanup_demo(&cleanup_manager) {
        eprintln!("Failed to perform manual cleanup: {}", e);
    }

    // In a real application, you would stop the cleanup service during application shutdown
    if let Err(e) = cleanup_manager.stop_cleanup_service() {
        eprintln!("Failed to stop background cleanup: {}", e);
    } else {
        println!("Background cleanup service stopped gracefully");
    }
}

/// Example CleanupTask struct for demonstration purposes
#[derive(Debug, Clone)]
pub struct CleanupTask {
    pub task_type: CleanupTaskType,
    pub priority: CleanupPriority,
    pub description: String,
    pub timestamp: std::time::Instant,
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    #[test]
    fn test_example_setup() {
        // This is a basic test to ensure the example code compiles and runs
        let result = setup_background_cleanup();
        
        // We don't expect this to succeed in a test environment without proper dependencies
        // but we want to ensure it doesn't panic
        assert!(result.is_err());
    }
}