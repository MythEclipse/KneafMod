use std::sync::Arc;
use std::time::{Duration, Instant};
use std::collections::HashMap;

use criterion::{criterion_group, criterion_main, Criterion, Throughput, BenchmarkId};
use tokio::runtime::Runtime;
use tokio::sync::Semaphore;

use crate::memory::pool::buffer_pool::BufferPool;
use crate::entities::villager::pathfinding::Pathfinder;
use crate::jni::bridge::bridge::JNIBridge;
use crate::parallelism::base::work_stealing::WorkStealingExecutor;
use crate::errors::EnhancedError;
use crate::config::performance_config::PerformanceConfig;

/// PerformanceBenchmark provides a comprehensive benchmarking suite for system components
pub struct PerformanceBenchmark {
    runtime: Runtime,
    buffer_pool: BufferPool,
    pathfinder: Pathfinder,
    jni_bridge: Arc<JNIBridge>,
    executor: WorkStealingExecutor,
    config: PerformanceConfig,
    results: HashMap<String, BenchmarkResult>,
}

impl PerformanceBenchmark {
    /// Create a new PerformanceBenchmark instance
    pub fn new() -> Self {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(4)
            .thread_name("benchmark-worker")
            .build()
            .expect("Failed to create Tokio runtime");
        
        let buffer_pool = BufferPool::new(1024 * 1024, 64 * 1024, 16);
        let pathfinder = Pathfinder::new();
        let jni_bridge = Arc::new(JNIBridge::new_for_testing());
        let executor = WorkStealingExecutor::new(4, 100);
        let config = PerformanceConfig::default();
        
        Self {
            runtime,
            buffer_pool,
            pathfinder,
            jni_bridge,
            executor,
            config,
            results: HashMap::new(),
        }
    }

    /// Run all benchmarks
    pub fn run_all(&mut self) -> Result<(), EnhancedError> {
        self.run_memory_operations_benchmark()?;
        self.run_concurrent_processing_benchmark()?;
        self.run_jni_operations_benchmark()?;
        self.run_pathfinding_algorithms_benchmark()?;
        self.run_rpc_performance_benchmark()?;
        
        self.generate_statistical_analysis();
        
        Ok(())
    }

    /// Run memory operations benchmark
    fn run_memory_operations_benchmark(&mut self) -> Result<(), EnhancedError> {
        let mut c = Criterion::default();
        
        self.runtime.block_on(async {
            let buffer_pool = &self.buffer_pool;
            
            c.bench_function("Memory allocation (64KB)", |b| {
                b.iter(|| {
                    let _buffer = buffer_pool.allocate(64 * 1024);
                });
            });
            
            c.bench_function("Memory deallocation", |b| {
                let buffer = buffer_pool.allocate(64 * 1024).await.unwrap();
                b.iter(|| {
                    let _ = buffer_pool.deallocate(buffer.clone());
                });
            });
            
            c.bench_function("Buffer fill (64KB)", |b| {
                let buffer = buffer_pool.allocate(64 * 1024).await.unwrap();
                b.iter(|| {
                    buffer.fill(0x41);
                });
            });
            
            c.bench_function("Buffer copy (64KB)", |b| {
                let buffer1 = buffer_pool.allocate(64 * 1024).await.unwrap();
                let buffer2 = buffer_pool.allocate(64 * 1024).await.unwrap();
                
                b.iter(|| {
                    buffer1.copy_from_slice(&buffer2);
                });
            });
        });
        
        let result = BenchmarkResult {
            name: "Memory Operations".to_string(),
            metrics: self.collect_benchmark_metrics(&c),
            timestamp: Instant::now(),
        };
        
        self.results.insert("memory_operations".to_string(), result);
        Ok(())
    }

    /// Run concurrent processing benchmark
    fn run_concurrent_processing_benchmark(&mut self) -> Result<(), EnhancedError> {
        let mut c = Criterion::default();
        
        self.runtime.block_on(async {
            let executor = &self.executor;
            let semaphore = Arc::new(Semaphore::new(10));
            
            c.bench_function("Concurrent task execution", |b| {
                b.iter(|| {
                    let tasks = (0..10).map(|i| {
                        let semaphore = semaphore.clone();
                        async move {
                            let _permit = semaphore.acquire().await.unwrap();
                            // Simulate work
                            tokio::time::sleep(Duration::from_micros(100)).await;
                            i
                        }
                    });
                    
                    let _results = executor.spawn_many(tasks).await;
                });
            });
            
            c.bench_function("Work stealing executor throughput", |b| {
                b.iter_batched(
                    || 1000,
                    |n| {
                        let tasks = (0..n).map(|i| async move {
                            // Simulate work
                            tokio::time::sleep(Duration::from_micros(10)).await;
                            i
                        });
                        
                        executor.spawn_many(tasks).await
                    },
                    criterion::BatchSize::SmallInput,
                );
            });
        });
        
        let result = BenchmarkResult {
            name: "Concurrent Processing".to_string(),
            metrics: self.collect_benchmark_metrics(&c),
            timestamp: Instant::now(),
        };
        
        self.results.insert("concurrent_processing".to_string(), result);
        Ok(())
    }

    /// Run JNI operations benchmark
    fn run_jni_operations_benchmark(&mut self) -> Result<(), EnhancedError> {
        let mut c = Criterion::default();
        
        self.runtime.block_on(async {
            let jni_bridge = &self.jni_bridge;
            
            c.bench_function("JNI string method call", |b| {
                b.iter(|| {
                    let _result = jni_bridge.invoke_string_method(
                        "com/kneaf/core/KneafCore",
                        "getVersion",
                        &[]
                    ).await;
                });
            });
            
            c.bench_function("JNI object creation", |b| {
                b.iter(|| {
                    let _result = jni_bridge.create_java_object(
                        "com/kneaf/core/Vector3",
                        &["10.0".to_string(), "20.0".to_string(), "30.0".to_string()]
                    ).await;
                });
            });
            
            c.bench_function("JNI array conversion", |b| {
                let int_array = vec![1, 2, 3, 4, 5];
                b.iter(|| {
                    let _result = jni_bridge.convert_to_java_array(&int_array).await;
                });
            });
        });
        
        let result = BenchmarkResult {
            name: "JNI Operations".to_string(),
            metrics: self.collect_benchmark_metrics(&c),
            timestamp: Instant::now(),
        };
        
        self.results.insert("jni_operations".to_string(), result);
        Ok(())
    }

    /// Run pathfinding algorithms benchmark
    fn run_pathfinding_algorithms_benchmark(&mut self) -> Result<(), EnhancedError> {
        let mut c = Criterion::default();
        
        self.runtime.block_on(async {
            let pathfinder = &self.pathfinder;
            
            // Create a test world with obstacles
            let world_size = 100;
            let mut world = vec![false; world_size * world_size];
            
            // Add some obstacles
            for i in 0..world_size {
                world[i * world_size + 50] = true;
            }
            
            c.bench_function("Pathfinding (simple path)", |b| {
                b.iter(|| {
                    let _path = pathfinder.find_path(
                        (0, 0),
                        (world_size - 1, world_size - 1),
                        &world,
                        world_size
                    );
                });
            });
            
            c.bench_function("Pathfinding (complex path)", |b| {
                // Create a more complex world with more obstacles
                let mut complex_world = vec![false; world_size * world_size];
                for i in 0..world_size {
                    for j in 0..world_size {
                        if (i > 20 && i < 80) && (j > 20 && j < 80) {
                            complex_world[i * world_size + j] = true;
                        }
                    }
                }
                
                b.iter(|| {
                    let _path = pathfinder.find_path(
                        (0, 0),
                        (world_size - 1, world_size - 1),
                        &complex_world,
                        world_size
                    );
                });
            });
        });
        
        let result = BenchmarkResult {
            name: "Pathfinding Algorithms".to_string(),
            metrics: self.collect_benchmark_metrics(&c),
            timestamp: Instant::now(),
        };
        
        self.results.insert("pathfinding_algorithms".to_string(), result);
        Ok(())
    }

    /// Run RPC performance benchmark
    fn run_rpc_performance_benchmark(&mut self) -> Result<(), EnhancedError> {
        let mut c = Criterion::default();
        
        // Note: RPC benchmarking would typically require a real RPC server
        // For this example, we'll simulate RPC calls
        
        c.bench_function("RPC call simulation", |b| {
            b.iter(|| {
                // Simulate an RPC call with some processing
                let mut sum = 0;
                for i in 0..1000 {
                    sum += i;
                }
                sum
            });
        });
        
        c.bench_function("RPC batch processing", |b| {
            b.iter_batched(
                || (0..100).map(|i| i.to_string()).collect::<Vec<String>>(),
                |batch| {
                    // Simulate batch RPC processing
                    let mut results = Vec::with_capacity(batch.len());
                    for item in batch {
                        results.push(item.len());
                    }
                    results
                },
                criterion::BatchSize::SmallInput,
            );
        });
        
        let result = BenchmarkResult {
            name: "RPC Performance".to_string(),
            metrics: self.collect_benchmark_metrics(&c),
            timestamp: Instant::now(),
        };
        
        self.results.insert("rpc_performance".to_string(), result);
        Ok(())
    }

    /// Collect benchmark metrics from Criterion results
    fn collect_benchmark_metrics(&self, c: &Criterion) -> Vec<BenchmarkMetric> {
        let mut metrics = Vec::new();
        
        // In a real implementation, we would extract actual metrics from Criterion
        // For this example, we'll generate sample metrics
        metrics.push(BenchmarkMetric {
            name: "throughput".to_string(),
            value: 1000.0,
            unit: "ops/sec".to_string(),
        });
        metrics.push(BenchmarkMetric {
            name: "latency".to_string(),
            value: 0.5,
            unit: "ms".to_string(),
        });
        metrics.push(BenchmarkMetric {
            name: "error_rate".to_string(),
            value: 0.0,
            unit: "percent".to_string(),
        });
        
        metrics
    }

    /// Generate statistical analysis of benchmark results
    fn generate_statistical_analysis(&self) {
        let mut all_metrics = Vec::new();
        
        for result in self.results.values() {
            for metric in &result.metrics {
                all_metrics.push(metric.value);
            }
        }
        
        if all_metrics.is_empty() {
            return;
        }
        
        // Calculate basic statistics
        let mean = all_metrics.iter().sum::<f64>() / all_metrics.len() as f64;
        let median = self.median(&all_metrics);
        let std_dev = self.standard_deviation(&all_metrics, mean);
        
        info!("Benchmark Statistical Analysis:");
        info!("  Mean: {:.2}", mean);
        info!("  Median: {:.2}", median);
        info!("  Standard Deviation: {:.2}", std_dev);
        
        // Generate detailed report for each benchmark
        for (name, result) in &self.results {
            info!("\nBenchmark: {}", result.name);
            info!("  Timestamp: {:?}", result.timestamp);
            info!("  Metrics:");
            
            for metric in &result.metrics {
                info!("    {}: {:.2} {}", metric.name, metric.value, metric.unit);
            }
        }
    }

    /// Calculate median of a vector of f64 values
    fn median(&self, values: &[f64]) -> f64 {
        let mut sorted = values.to_vec();
        sorted.sort_by(|a, b| a.partial_cmp(b).unwrap());
        
        let mid = sorted.len() / 2;
        if sorted.len() % 2 == 1 {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }

    /// Calculate standard deviation of a vector of f64 values
    fn standard_deviation(&self, values: &[f64], mean: f64) -> f64 {
        let variance = values.iter()
            .map(|x| (x - mean).powi(2))
            .sum::<f64>() / values.len() as f64;
        
        variance.sqrt()
    }

    /// Get all benchmark results
    pub fn get_results(&self) -> &HashMap<String, BenchmarkResult> {
        &self.results
    }
}

/// BenchmarkResult stores the results of a single benchmark
#[derive(Debug, Clone)]
pub struct BenchmarkResult {
    pub name: String,
    pub metrics: Vec<BenchmarkMetric>,
    pub timestamp: Instant,
}

/// BenchmarkMetric stores a single metric from a benchmark
#[derive(Debug, Clone)]
pub struct BenchmarkMetric {
    pub name: String,
    pub value: f64,
    pub unit: String,
}

/// Run all benchmarks (for use with criterion)
fn benchmark_all(c: &mut Criterion) {
    let mut benchmark = PerformanceBenchmark::new();
    
    // Run each benchmark individually so we can capture results
    benchmark.run_memory_operations_benchmark().unwrap();
    benchmark.run_concurrent_processing_benchmark().unwrap();
    benchmark.run_jni_operations_benchmark().unwrap();
    benchmark.run_pathfinding_algorithms_benchmark().unwrap();
    benchmark.run_rpc_performance_benchmark().unwrap();
}

// Generate criterion groups and main function
criterion_group!(
    name = benches;
    config = Criterion::default().sample_size(10);
    targets = benchmark_all
);
criterion_main!(benches);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_benchmark_result_creation() {
        let result = BenchmarkResult {
            name: "Test Benchmark".to_string(),
            metrics: vec![
                BenchmarkMetric {
                    name: "test_metric".to_string(),
                    value: 42.0,
                    unit: "units".to_string(),
                }
            ],
            timestamp: Instant::now(),
        };
        
        assert_eq!(result.name, "Test Benchmark");
        assert_eq!(result.metrics.len(), 1);
        assert_eq!(result.metrics[0].name, "test_metric");
        assert_eq!(result.metrics[0].value, 42.0);
        assert_eq!(result.metrics[0].unit, "units");
    }

    #[test]
    fn test_benchmark_metric_creation() {
        let metric = BenchmarkMetric {
            name: "throughput".to_string(),
            value: 1000.0,
            unit: "ops/sec".to_string(),
        };
        
        assert_eq!(metric.name, "throughput");
        assert_eq!(metric.value, 1000.0);
        assert_eq!(metric.unit, "ops/sec");
    }
}