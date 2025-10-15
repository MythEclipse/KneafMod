//! Tests for the batch processing module

use std::sync::Arc;
use crate::batch_processing::common::{BatchConfig, BatchOperation, BatchResult, BatchMetrics};
use crate::batch_processing::factory::{BatchProcessorFactory, BatchProcessorType, BatchConfigBuilder};
use crate::errors::Result;

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn test_batch_config_builder() {
        let config = BatchConfigBuilder::new()
            .min_batch_size(100)
            .max_batch_size(1000)
            .adaptive_batch_timeout_ms(5)
            .max_pending_batches(100)
            .worker_threads(4)
            .enable_adaptive_sizing(false)
            .build();

        assert_eq!(config.min_batch_size, 100);
        assert_eq!(config.max_batch_size, 1000);
        assert_eq!(config.adaptive_batch_timeout_ms, 5);
        assert_eq!(config.max_pending_batches, 100);
        assert_eq!(config.worker_threads, 4);
        assert!(!config.enable_adaptive_sizing);
    }

    #[test]
    fn test_batch_processor_factory_standard() {
        let factory = BatchProcessorFactory::new();
        let processor = factory.create_processor(BatchProcessorType::Standard).unwrap();
        
        let operation = BatchOperation::new(0, vec![1, 2, 3], 1);
        let operations = vec![operation.clone(), operation.clone()];
        
        let result = processor.process_batch(&operations);
        assert!(result.is_ok());
        
        let batch_result = result.unwrap();
        assert_eq!(batch_result.batch_size, 2);
        assert_eq!(batch_result.success_count, 2);
        assert_eq!(batch_result.operation_type, 0);
    }

    #[test]
    fn test_batch_processor_factory_simd_optimized() {
        let factory = BatchProcessorFactory::new();
        let processor = factory.create_processor(BatchProcessorType::SimdOptimized).unwrap();
        
        let operation = BatchOperation::new(0, vec![1, 2, 3], 1);
        let operations = vec![operation.clone(), operation.clone()];
        
        let result = processor.process_batch(&operations);
        assert!(result.is_ok());
        
        let batch_result = result.unwrap();
        assert_eq!(batch_result.batch_size, 2);
        assert_eq!(batch_result.success_count, 2);
        assert_eq!(batch_result.operation_type, 1); // SIMD processor uses operation type 1
    }

    #[test]
    fn test_batch_processor_factory_low_latency() {
        let factory = BatchProcessorFactory::new();
        let processor = factory.create_processor(BatchProcessorType::LowLatency).unwrap();
        
        let operation = BatchOperation::new(0, vec![1, 2, 3], 1);
        let operations = vec![operation.clone(), operation.clone()];
        
        let result = processor.process_batch(&operations);
        assert!(result.is_ok());
        
        let batch_result = result.unwrap();
        assert_eq!(batch_result.batch_size, 2);
        assert_eq!(batch_result.success_count, 2);
        assert_eq!(batch_result.operation_type, 2); // Low latency processor uses operation type 2
    }

    #[test]
    fn test_batch_processor_factory_high_throughput() {
        let factory = BatchProcessorFactory::new();
        let processor = factory.create_processor(BatchProcessorType::HighThroughput).unwrap();
        
        let operation = BatchOperation::new(0, vec![1, 2, 3], 1);
        let operations = vec![operation.clone(), operation.clone()];
        
        let result = processor.process_batch(&operations);
        assert!(result.is_ok());
        
        let batch_result = result.unwrap();
        assert_eq!(batch_result.batch_size, 2);
        assert_eq!(batch_result.success_count, 2);
        assert_eq!(batch_result.operation_type, 3); // High throughput processor uses operation type 3
    }

    #[test]
    fn test_batch_processor_factory_with_custom_config() {
        let custom_config = BatchConfig {
            min_batch_size: 200,
            max_batch_size: 2000,
            adaptive_batch_timeout_ms: 10,
            max_pending_batches: 50,
            worker_threads: 2,
            enable_adaptive_sizing: true,
        };
        
        let factory = BatchProcessorFactory::with_config(custom_config.clone());
        let processor = factory.create_processor(BatchProcessorType::Standard).unwrap();
        
        let config = processor.get_config();
        assert_eq!(config.min_batch_size, 200);
        assert_eq!(config.max_batch_size, 2000);
        assert_eq!(config.adaptive_batch_timeout_ms, 10);
        assert_eq!(config.max_pending_batches, 50);
        assert_eq!(config.worker_threads, 2);
        assert!(config.enable_adaptive_sizing);
    }

    #[test]
    fn test_batch_processor_factory_with_builder() {
        let factory = BatchProcessorFactory::new();
        let processor = factory.create_processor_with_builder(BatchProcessorType::Standard, |builder| {
            builder
                .min_batch_size(300)
                .max_batch_size(3000)
                .adaptive_batch_timeout_ms(15)
                .max_pending_batches(75)
                .worker_threads(6)
                .enable_adaptive_sizing(false)
        }).unwrap();
        
        let config = processor.get_config();
        assert_eq!(config.min_batch_size, 300);
        assert_eq!(config.max_batch_size, 3000);
        assert_eq!(config.adaptive_batch_timeout_ms, 15);
        assert_eq!(config.max_pending_batches, 75);
        assert_eq!(config.worker_threads, 6);
        assert!(!config.enable_adaptive_sizing);
    }

    #[test]
    fn test_batch_metrics() {
        let metrics = BatchMetrics::new();
        assert_eq!(metrics.total_batches_processed.load(std::sync::atomic::Ordering::Relaxed), 0);
        assert_eq!(metrics.total_operations_batched.load(std::sync::atomic::Ordering::Relaxed), 0);
        assert_eq!(metrics.average_batch_size.load(std::sync::atomic::Ordering::Relaxed), 50);
        assert_eq!(metrics.current_queue_depth.load(std::sync::atomic::Ordering::Relaxed), 0);
        assert_eq!(metrics.failed_operations.load(std::sync::atomic::Ordering::Relaxed), 0);
        assert_eq!(metrics.total_processing_time_ns.load(std::sync::atomic::Ordering::Relaxed), 0);
        assert_eq!(metrics.adaptive_batch_size.load(std::sync::atomic::Ordering::Relaxed), 50);
        assert_eq!(metrics.get_pressure_level(), 0);
        
        metrics.update_average_batch_size(100);
        assert_eq!(metrics.average_batch_size.load(std::sync::atomic::Ordering::Relaxed), (50 * 9 + 100) / 10);
        assert_eq!(metrics.adaptive_batch_size.load(std::sync::atomic::Ordering::Relaxed), (50 * 9 + 100) / 10 as usize);
        
        metrics.set_pressure_level(75);
        assert_eq!(metrics.get_pressure_level(), 75);
    }

    #[test]
    fn test_batch_operation() {
        let input_data = vec![1, 2, 3, 4, 5];
        let operation = BatchOperation::new(42, input_data.clone(), 5);
        
        assert_eq!(operation.operation_type, 42);
        assert_eq!(operation.input_data, input_data);
        assert_eq!(operation.estimated_size, input_data.len());
        assert!(operation.timestamp.elapsed().as_nanos() >= 0);
        assert_eq!(operation.priority, 5);
    }

    #[test]
    fn test_simd_utilities() {
        use crate::batch_processing::common::simd;
        
        let positions = vec![
            (1.0, 2.0, 3.0),
            (4.0, 5.0, 6.0),
            (7.0, 8.0, 9.0),
            (10.0, 11.0, 12.0),
            (13.0, 14.0, 15.0),
        ];
        
        let center = (5.0, 6.0, 7.0);
        let distances = simd::calculate_entity_distances_simd(&positions, center);
        
        assert_eq!(distances.len(), 5);
        
        // Test vector operations
        let a = vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0];
        let b = vec![8.0, 7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0];
        
        let sum = simd::vector_add_simd(&a, &b);
        assert_eq!(sum, vec![9.0, 9.0, 9.0, 9.0, 9.0, 9.0, 9.0, 9.0]);
        
        let product = simd::vector_mul_simd(&a, &b);
        assert_eq!(product, vec![8.0, 14.0, 18.0, 20.0, 20.0, 18.0, 14.0, 8.0]);
    }

    #[test]
    fn test_error_handling_utilities() {
        use crate::batch_processing::common::error_handling;
        
        // Test successful case
        let result = error_handling::handle_batch_error("test_operation", || {
            Ok("success".to_string())
        });
        assert!(result.is_ok());
        assert_eq!(result.unwrap(), "success");
        
        // Test error case
        let result = error_handling::handle_batch_error("test_operation", || {
            Err(crate::errors::RustError::OperationFailed("test error".to_string()))
        });
        assert!(result.is_err());
    }
}