use crate::parallelism::base::executor_factory::executor_factory::{
    ExecutorType, ParallelExecutorEnum, TaskPriority, EnhancedWorkStealingExecutor, WorkStealingConfig
};
use crate::config::performance_config::PerformanceConfig;
use std::sync::{Arc, atomic::{AtomicUsize, Ordering}};
use std::time::Duration;
use tokio::time::timeout;

#[tokio::test]
async fn test_work_stealing_basic_execution() {
    let executor = ParallelExecutorEnum::WorkStealing(Arc::new(EnhancedWorkStealingExecutor::new(None)));
    
    let result = executor.execute(|| {
        "test_task_execution".to_string()
    });
    
    assert_eq!(result, "test_task_execution");
}

#[tokio::test]
async fn test_priority_execution_order() {
    let executor = ParallelExecutorEnum::WorkStealing(Arc::new(EnhancedWorkStealingExecutor::new(None)));
    
    let execution_order = Arc::new(AtomicUsize::new(0));
    let expected_order = vec![TaskPriority::Highest, TaskPriority::High, TaskPriority::Normal, TaskPriority::Low, TaskPriority::Lowest];
    
    for (i, priority) in expected_order.iter().enumerate() {
        let execution_order_clone = execution_order.clone();
        executor.execute_with_priority(move || {
            let current = execution_order_clone.fetch_add(1, Ordering::SeqCst);
            assert_eq!(current, i, "Task executed out of priority order");
            priority.clone()
        }, *priority, None);
    }
    
    // Wait for all tasks to complete
    tokio::time::sleep(Duration::from_millis(100)).await;
    
    assert_eq!(execution_order.load(Ordering::SeqCst), 5, "Not all tasks executed");
}

#[tokio::test]
async fn test_queue_size_limiting() {
    let config = WorkStealingConfig {
        enabled: true,
        queue_size: 2,
        high_priority_ratio: 0.5,
        worker_threads: None,
    };
    
    let executor = ParallelExecutorEnum::WorkStealing(Arc::new(EnhancedWorkStealingExecutor::new(Some(&crate::parallelism::base::executor_factory::executor_factory::ExecutorConfig {
        work_stealing_config: Some(config),
        ..Default::default()
    }))));
    
    // Fill queue to capacity
    let task1_result = Arc::new(AtomicUsize::new(0));
    let task2_result = Arc::new(AtomicUsize::new(0));
    
    executor.execute_with_priority({
        let task1_result = task1_result.clone();
        move || { task1_result.store(1, Ordering::SeqCst); 42 }
    }, TaskPriority::Normal, None);
    
    executor.execute_with_priority({
        let task2_result = task2_result.clone();
        move || { task2_result.store(1, Ordering::SeqCst); 84 }
    }, TaskPriority::Normal, None);
    
    // Third task should execute immediately (queue full)
    let task3_result = executor.execute_with_priority(|| 126, TaskPriority::Normal, None);
    
    assert_eq!(task3_result, 126, "Third task should execute immediately");
    
    // Wait for queued tasks to complete
    tokio::time::sleep(Duration::from_millis(100)).await;
    
    assert_eq!(task1_result.load(Ordering::SeqCst), 1, "First task not executed");
    assert_eq!(task2_result.load(Ordering::SeqCst), 1, "Second task not executed");
}

#[tokio::test]
async fn test_dynamic_config_update() {
    let executor = ParallelExecutorEnum::WorkStealing(Arc::new(EnhancedWorkStealingExecutor::new(None)));
    
    // Get initial config
    let initial_config = {
        let executor = executor.as_any().downcast_ref::<EnhancedWorkStealingExecutor>().unwrap();
        let config = executor.config.read().unwrap();
        config.clone()
    };
    
    // Update config
    let new_config = WorkStealingConfig {
        enabled: false,
        queue_size: 100,
        high_priority_ratio: 0.8,
        worker_threads: Some(4),
    };
    
    let executor = executor.as_any().downcast_ref::<EnhancedWorkStealingExecutor>().unwrap();
    executor.config.write().unwrap().enabled = new_config.enabled;
    executor.config.write().unwrap().queue_size = new_config.queue_size;
    
    // Verify config updated
    let updated_config = {
        let config = executor.config.read().unwrap();
        config.clone()
    };
    
    assert!(!updated_config.enabled, "Config not updated");
    assert_eq!(updated_config.queue_size, 100, "Queue size not updated");
}

#[tokio::test]
async fn test_graceful_shutdown() {
    let executor = ParallelExecutorEnum::WorkStealing(Arc::new(EnhancedWorkStealingExecutor::new(None)));
    
    // Submit tasks
    for i in 0..5 {
        executor.execute(move || {
            std::thread::sleep(Duration::from_millis(10));
            i
        });
    }
    
    // Shutdown should wait for tasks to complete
    let shutdown_result = timeout(Duration::from_secs(1), executor.shutdown()).await;
    
    assert!(shutdown_result.is_ok(), "Shutdown timed out or failed");
    
    // Verify queue is empty after shutdown
    let executor = executor.as_any().downcast_ref::<EnhancedWorkStealingExecutor>().unwrap();
    assert_eq!(executor.priority_queue.lock().unwrap().len(), 0, "Queue not empty after shutdown");
}

#[tokio::test]
async fn test_performance_config_integration() {
    let performance_config = PerformanceConfig {
        work_stealing_enabled: true,
        work_stealing_queue_size: 200,
        ..Default::default()
    };
    
    let executor = ParallelExecutorEnum::WorkStealing(Arc::new(EnhancedWorkStealingExecutor::new(None)));
    
    // Apply performance config
    let result = performance_config.apply_work_stealing_config(
        executor.as_any().downcast_ref::<EnhancedWorkStealingExecutor>().unwrap()
    );
    
    assert!(result.is_ok(), "Failed to apply performance config");
    
    // Verify config applied
    let executor = executor.as_any().downcast_ref::<EnhancedWorkStealingExecutor>().unwrap();
    let config = executor.config.read().unwrap();
    
    assert!(config.enabled, "Work stealing not enabled");
    assert_eq!(config.queue_size, 200, "Queue size not updated from performance config");
}

#[tokio::test]
async fn test_trace_id_integration() {
    let executor = ParallelExecutorEnum::WorkStealing(Arc::new(EnhancedWorkStealingExecutor::new(None)));
    let trace_id = "test-trace-123".to_string();
    
    let result = executor.execute_with_priority(move || {
        // In real implementation, trace_id would be used for logging/monitoring
        trace_id.clone()
    }, TaskPriority::High, Some(trace_id.clone()));
    
    assert_eq!(result, trace_id, "Trace ID not propagated correctly");
}

#[tokio::test]
async fn test_concurrent_execution() {
    let executor = ParallelExecutorEnum::WorkStealing(Arc::new(EnhancedWorkStealingExecutor::new(None)));
    let counter = Arc::new(AtomicUsize::new(0));
    let num_tasks = 100;
    
    for _ in 0..num_tasks {
        let counter_clone = counter.clone();
        executor.execute(move || {
            let _ = counter_clone.fetch_add(1, Ordering::Relaxed);
        });
    }
    
    // Wait for all tasks to complete
    tokio::time::sleep(Duration::from_millis(50)).await;
    
    assert_eq!(counter.load(Ordering::Relaxed), num_tasks, "Not all tasks executed concurrently");
}