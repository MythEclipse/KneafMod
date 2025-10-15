//! Factory and builder patterns for batch processing with unified interface

use std::sync::Arc;
use crate::batch_processing::common::{BatchConfig, BatchOperation, BatchResult, BatchMetrics};
use crate::errors::{RustError, Result};

/// Enum representing different types of batch processors
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BatchProcessorType {
    /// Standard batch processor for general use
    Standard,
    
    /// High-performance processor with SIMD optimizations
    SimdOptimized,
    
    /// Low-latency processor for time-sensitive operations
    LowLatency,
    
    /// High-throughput processor for bulk operations
    HighThroughput,
}

/// Trait defining the common interface for all batch processors
pub trait BatchProcessor: Send + Sync {
    /// Process a batch of operations
    fn process_batch(&self, operations: &[BatchOperation]) -> Result<BatchResult>;
    
    /// Get current metrics for the processor
    fn get_metrics(&self) -> Arc<BatchMetrics>;
    
    /// Get configuration for the processor
    fn get_config(&self) -> Arc<BatchConfig>;
    
    /// Submit a single operation to be batched
    fn submit_operation(&self, operation: BatchOperation) -> Result<()>;
}

/// Factory for creating different types of batch processors
#[derive(Debug)]
pub struct BatchProcessorFactory {
    /// Common configuration for all processors created by this factory
    common_config: Arc<BatchConfig>,
}

impl BatchProcessorFactory {
    /// Create a new batch processor factory with default configuration
    pub fn new() -> Self {
        Self {
            common_config: Arc::new(BatchConfig::default()),
        }
    }
    
    /// Create a factory with custom base configuration
    pub fn with_config(config: BatchConfig) -> Self {
        Self {
            common_config: Arc::new(config),
        }
    }
    
    /// Create a batch processor of the specified type with default configuration
    pub fn create_processor(&self, processor_type: BatchProcessorType) -> Result<Arc<dyn BatchProcessor>> {
        self.create_processor_with_config(processor_type, None)
    }
    
    /// Create a batch processor of the specified type with custom configuration
    pub fn create_processor_with_config(
        &self,
        processor_type: BatchProcessorType,
        custom_config: Option<&BatchConfig>,
    ) -> Result<Arc<dyn BatchProcessor>> {
        let config = custom_config.map_or_else(
            || Arc::clone(&self.common_config),
            |custom| Arc::new(custom.clone())
        );
        
        match processor_type {
            BatchProcessorType::Standard => {
                let processor = StandardBatchProcessor::new(config.clone());
                Ok(Arc::new(processor) as Arc<dyn BatchProcessor>)
            }
            BatchProcessorType::SimdOptimized => {
                let processor = SimdBatchProcessor::new(config.clone());
                Ok(Arc::new(processor) as Arc<dyn BatchProcessor>)
            }
            BatchProcessorType::LowLatency => {
                let processor = LowLatencyBatchProcessor::new(config.clone());
                Ok(Arc::new(processor) as Arc<dyn BatchProcessor>)
            }
            BatchProcessorType::HighThroughput => {
                let processor = HighThroughputBatchProcessor::new(config.clone());
                Ok(Arc::new(processor) as Arc<dyn BatchProcessor>)
            }
        }
    }
    
    /// Create a batch processor using the builder pattern
    pub fn create_processor_with_builder<F>(&self, processor_type: BatchProcessorType, builder: F) -> Result<Arc<dyn BatchProcessor>>
    where
        F: FnOnce(BatchConfigBuilder) -> BatchConfigBuilder,
    {
        let config = builder(BatchConfigBuilder::new()).build();
        self.create_processor_with_config(processor_type, Some(&config))
    }
}

/// Builder pattern for batch configuration
#[derive(Debug, Clone)]
pub struct BatchConfigBuilder {
    config: BatchConfig,
}

impl BatchConfigBuilder {
    /// Create a new batch configuration builder with default values
    pub fn new() -> Self {
        Self {
            config: BatchConfig::default(),
        }
    }
    
    /// Set minimum batch size
    pub fn min_batch_size(mut self, size: usize) -> Self {
        self.config.min_batch_size = size;
        self
    }
    
    /// Set maximum batch size
    pub fn max_batch_size(mut self, size: usize) -> Self {
        self.config.max_batch_size = size;
        self
    }
    
    /// Set adaptive batch timeout in milliseconds
    pub fn adaptive_batch_timeout_ms(mut self, timeout: u64) -> Self {
        self.config.adaptive_batch_timeout_ms = timeout;
        self
    }
    
    /// Set maximum pending batches
    pub fn max_pending_batches(mut self, count: usize) -> Self {
        self.config.max_pending_batches = count;
        self
    }
    
    /// Set number of worker threads
    pub fn worker_threads(mut self, threads: usize) -> Self {
        self.config.worker_threads = threads;
        self
    }
    
    /// Enable or disable adaptive sizing
    pub fn enable_adaptive_sizing(mut self, enable: bool) -> Self {
        self.config.enable_adaptive_sizing = enable;
        self
    }
    
    /// Build the final configuration
    pub fn build(self) -> BatchConfig {
        self.config
    }
}

/// Standard implementation of batch processor
#[derive(Debug)]
pub struct StandardBatchProcessor {
    config: Arc<BatchConfig>,
    metrics: Arc<BatchMetrics>,
    // Additional internal state would go here
}

impl StandardBatchProcessor {
    /// Create a new standard batch processor
    pub fn new(config: Arc<BatchConfig>) -> Self {
        Self {
            config,
            metrics: Arc::new(BatchMetrics::new()),
        }
    }
}

impl BatchProcessor for StandardBatchProcessor {
    fn process_batch(&self, operations: &[BatchOperation]) -> Result<BatchResult> {
        // Standard batch processing implementation
        let batch_size = operations.len();
        
        // Update metrics
        let metrics = Arc::clone(&self.metrics);
        metrics.total_batches_processed.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        metrics.total_operations_batched.fetch_add(batch_size as u64, std::sync::atomic::Ordering::Relaxed);
        
        // Simulate processing
        let processing_time = std::time::Instant::now().elapsed().as_nanos() as u64;
        
        Ok(BatchResult {
            operation_type: 0, // Default for standard processor
            results: operations.iter().map(|op| op.input_data.clone()).collect(),
            processing_time_ns: processing_time,
            batch_size,
            success_count: batch_size,
        })
    }
    
    fn get_metrics(&self) -> Arc<BatchMetrics> {
        Arc::clone(&self.metrics)
    }
    
    fn get_config(&self) -> Arc<BatchConfig> {
        Arc::clone(&self.config)
    }
    
    fn submit_operation(&self, operation: BatchOperation) -> Result<()> {
        // In a real implementation, this would add to a queue
        Ok(())
    }
}

/// SIMD-optimized batch processor implementation
#[derive(Debug)]
pub struct SimdBatchProcessor {
    config: Arc<BatchConfig>,
    metrics: Arc<BatchMetrics>,
    // SIMD-specific state would go here
}

impl SimdBatchProcessor {
    /// Create a new SIMD-optimized batch processor
    pub fn new(config: Arc<BatchConfig>) -> Self {
        Self {
            config,
            metrics: Arc::new(BatchMetrics::new()),
        }
    }
    
    /// Process operations using SIMD optimizations
    fn process_with_simd(&self, operations: &[BatchOperation]) -> Vec<Vec<u8>> {
        // This would contain actual SIMD-accelerated processing logic
        // For example, using std::simd or vendor-specific intrinsics
        operations.iter().map(|op| op.input_data.clone()).collect()
    }
}

impl BatchProcessor for SimdBatchProcessor {
    fn process_batch(&self, operations: &[BatchOperation]) -> Result<BatchResult> {
        let batch_size = operations.len();
        
        // Update metrics
        let metrics = Arc::clone(&self.metrics);
        metrics.total_batches_processed.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        metrics.total_operations_batched.fetch_add(batch_size as u64, std::sync::atomic::Ordering::Relaxed);
        
        // Use SIMD-optimized processing
        let results = self.process_with_simd(operations);
        
        let processing_time = std::time::Instant::now().elapsed().as_nanos() as u64;
        
        Ok(BatchResult {
            operation_type: 1, // SIMD-specific operation type
            results,
            processing_time_ns: processing_time,
            batch_size,
            success_count: batch_size,
        })
    }
    
    fn get_metrics(&self) -> Arc<BatchMetrics> {
        Arc::clone(&self.metrics)
    }
    
    fn get_config(&self) -> Arc<BatchConfig> {
        Arc::clone(&self.config)
    }
    
    fn submit_operation(&self, operation: BatchOperation) -> Result<()> {
        Ok(())
    }
}

/// Low-latency batch processor implementation
#[derive(Debug)]
pub struct LowLatencyBatchProcessor {
    config: Arc<BatchConfig>,
    metrics: Arc<BatchMetrics>,
    // Low-latency specific state would go here
}

impl LowLatencyBatchProcessor {
    /// Create a new low-latency batch processor
    pub fn new(config: Arc<BatchConfig>) -> Self {
        Self {
            config,
            metrics: Arc::new(BatchMetrics::new()),
        }
    }
}

impl BatchProcessor for LowLatencyBatchProcessor {
    fn process_batch(&self, operations: &[BatchOperation]) -> Result<BatchResult> {
        let batch_size = operations.len();
        
        // Update metrics
        let metrics = Arc::clone(&self.metrics);
        metrics.total_batches_processed.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        metrics.total_operations_batched.fetch_add(batch_size as u64, std::sync::atomic::Ordering::Relaxed);
        
        // Low-latency processing (simplified)
        let processing_time = std::time::Instant::now().elapsed().as_nanos() as u64;
        
        Ok(BatchResult {
            operation_type: 2, // Low-latency operation type
            results: operations.iter().map(|op| op.input_data.clone()).collect(),
            processing_time_ns: processing_time,
            batch_size,
            success_count: batch_size,
        })
    }
    
    fn get_metrics(&self) -> Arc<BatchMetrics> {
        Arc::clone(&self.metrics)
    }
    
    fn get_config(&self) -> Arc<BatchConfig> {
        Arc::clone(&self.config)
    }
    
    fn submit_operation(&self, operation: BatchOperation) -> Result<()> {
        Ok(())
    }
}

/// High-throughput batch processor implementation
#[derive(Debug)]
pub struct HighThroughputBatchProcessor {
    config: Arc<BatchConfig>,
    metrics: Arc<BatchMetrics>,
    // High-throughput specific state would go here
}

impl HighThroughputBatchProcessor {
    /// Create a new high-throughput batch processor
    pub fn new(config: Arc<BatchConfig>) -> Self {
        Self {
            config,
            metrics: Arc::new(BatchMetrics::new()),
        }
    }
}

impl BatchProcessor for HighThroughputBatchProcessor {
    fn process_batch(&self, operations: &[BatchOperation]) -> Result<BatchResult> {
        let batch_size = operations.len();
        
        // Update metrics
        let metrics = Arc::clone(&self.metrics);
        metrics.total_batches_processed.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        metrics.total_operations_batched.fetch_add(batch_size as u64, std::sync::atomic::Ordering::Relaxed);
        
        // High-throughput processing (simplified)
        let processing_time = std::time::Instant::now().elapsed().as_nanos() as u64;
        
        Ok(BatchResult {
            operation_type: 3, // High-throughput operation type
            results: operations.iter().map(|op| op.input_data.clone()).collect(),
            processing_time_ns: processing_time,
            batch_size,
            success_count: batch_size,
        })
    }
    
    fn get_metrics(&self) -> Arc<BatchMetrics> {
        Arc::clone(&self.metrics)
    }
    
    fn get_config(&self) -> Arc<BatchConfig> {
        Arc::clone(&self.config)
    }
    
    fn submit_operation(&self, operation: BatchOperation) -> Result<()> {
        Ok(())
    }
}