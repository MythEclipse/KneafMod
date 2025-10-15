// Parallel Executor Factory Comprehensive Test Suite
// This test suite verifies all factory methods, executor types, and parallel execution capabilities
// for the ParallelExecutorFactory implementation

use super::super::executor_factory::{
    ExecutorType, ParallelExecutor, ParallelExecutorBuilder, ParallelExecutorFactory,
    get_global_executor,
};
use crate::errors::{Result, RustError};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

#[cfg(test)]
mod tests {
    use super::*;

    ///////////////////////////////////////////////////////////////////////////
    // Basic Factory Functionality Tests
    ///////////////////////////////////////////////////////////////////////////

    #[test]
    fn test_factory_creation() {
        // Test all factory methods return valid executors
        let default_executor = ParallelExecutorFactory::create_default();
        assert!(default_executor.is_ok());
        
        let cpu_executor = ParallelExecutorFactory::create_cpu_bound();
        assert!(cpu_executor.is_ok());
        
        let io_executor = ParallelExecutorFactory::create_io_bound();
        assert!(io_executor.is_ok());
        
        let ws_executor = ParallelExecutorFactory::create_work_stealing();
        assert!(ws_executor.is_ok());
        
        let seq_executor = ParallelExecutorFactory::create_sequential();
        assert!(seq_executor.is_ok());
    }

    #[test]
    fn test_executor_types() {
        // Test that each factory method returns the correct executor type
        let default_executor = ParallelExecutorFactory::create_default().unwrap();
        assert_eq!(default_executor.executor_type(), ExecutorType::Default);
        
        let cpu_executor = ParallelExecutorFactory::create_cpu_bound().unwrap();
        assert_eq!(cpu_executor.executor_type(), ExecutorType::CpuBound);
        
        let io_executor = ParallelExecutorFactory::create_io_bound().unwrap();
        assert_eq!(io_executor.executor_type(), ExecutorType::IoBound);
        
        let ws_executor = ParallelExecutorFactory::create_work_stealing().unwrap();
        assert_eq!(ws_executor.executor_type(), ExecutorType::WorkStealing);
        
        let seq_executor = ParallelExecutorFactory::create_sequential().unwrap();
        assert_eq!(seq_executor.executor_type(), ExecutorType::Sequential);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Builder Pattern Tests
    ///////////////////////////////////////////////////////////////////////////

    #[test]
    fn test_builder_creation() {
        let builder = ParallelExecutorBuilder::new();
        assert_eq!(builder.executor_type, ExecutorType::Default);
        assert!(builder.num_threads.is_none());
        assert!(builder.thread_name_prefix.is_none());
    }

    #[test]
    fn test_builder_configuration() {
        let builder = ParallelExecutorBuilder::new()
            .with_type(ExecutorType::CpuBound)
            .with_threads(8)
            .with_thread_name_prefix("test-prefix".to_string());
        
        assert_eq!(builder.executor_type, ExecutorType::CpuBound);
        assert_eq!(builder.num_threads, Some(8));
        assert_eq!(builder.thread_name_prefix, Some("test-prefix".to_string()));
    }

    #[test]
    fn test_builder_build() {
        let executor = ParallelExecutorBuilder::new()
            .with_type(ExecutorType::IoBound)
            .with_threads(4)
            .build();
        
        assert!(executor.is_ok());
        let executor = executor.unwrap();
        assert_eq!(executor.executor_type(), ExecutorType::IoBound);
        assert!(executor.current_thread_count() >= 4); // Should be at least the requested number
    }

    #[test]
    fn test_custom_executor_creation() {
        let custom_executor = ParallelExecutorFactory::create_custom(|builder| {
            builder
                .with_type(ExecutorType::WorkStealing)
                .with_threads(16)
                .with_thread_name_prefix("custom-exec".to_string())
        });
        
        assert!(custom_executor.is_ok());
        let executor = custom_executor.unwrap();
        assert_eq!(executor.executor_type(), ExecutorType::WorkStealing);
        assert!(executor.current_thread_count() >= 16);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Parallel Execution Tests
    ///////////////////////////////////////////////////////////////////////////

    #[test]
    fn test_sync_execution() {
        let executor = ParallelExecutorFactory::create_default().unwrap();
        
        // Test simple synchronous execution
        let result = executor.execute(|| 42);
        assert_eq!(result, 42);
        
        // Test execution with captured variables
        let value = 10;
        let result = executor.execute(move || value * 2);
        assert_eq!(result, 20);
    }

    #[test]
    fn test_async_execution() {
        let executor = ParallelExecutorFactory::create_default().unwrap();
        
        // Test asynchronous execution with thread spawning
        let results = Arc::new(AtomicUsize::new(0));
        let results_clone = Arc::clone(&results);
        
        // Spawn multiple tasks
        for i in 0..10 {
            let results_clone = Arc::clone(&results_clone);
            executor.spawn(move || {
                let thread_id = std::thread::current().id().as_u64() as usize;
                results_clone.fetch_add(thread_id, Ordering::Relaxed);
            });
        }
        
        // Give tasks time to complete
        std::thread::sleep(std::time::Duration::from_millis(100));
        
        let final_count = results.load(Ordering::SeqCst);
        assert!(final_count > 0); // Should have some thread IDs added
    }
}