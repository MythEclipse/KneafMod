//! Comprehensive testing for extreme performance optimizations
//! Tests for AVX-512 intrinsics, lock-free memory pooling, and extreme configuration

use crate::simd_enhanced::{EnhancedSimdProcessor, SimdPerformanceStats};
use crate::memory_pool::ObjectPool;
use std::time::Instant;

/// Test extreme AVX-512 optimizations
pub fn test_extreme_avx512() -> bool {
    println!("Testing Extreme AVX-512 Optimizations...");
    
    let simd: EnhancedSimdProcessor<16> = EnhancedSimdProcessor::new();
    println!("SIMD Capability: {:?}", simd.get_capability());
    
    // Test dot product with large vectors
    let a = vec![1.0f32; 1024];
    let b = vec![2.0f32; 1024];
    
    let start = Instant::now();
    let result = simd.dot_product(&a, &b);
    let duration = start.elapsed();
    
    let expected = 1.0 * 2.0 * 1024.0;
    let correct = (result - expected).abs() < 1e-6;
    
    println!("Dot Product Result: {} (Expected: {})", result, expected);
    println!("Correct: {}", correct);
    println!("Duration: {:?}", duration);
    
    // Test vector addition
    let mut a_add = vec![1.0f32; 1024];
    let b_add = vec![3.0f32; 1024];
    
    let start_add = Instant::now();
    simd.vector_add(&mut a_add, &b_add);
    let duration_add = start_add.elapsed();
    
    let correct_add = a_add.iter().all(|&x| x == 4.0);
    println!("Vector Addition Correct: {}", correct_add);
    println!("Vector Addition Duration: {:?}", duration_add);
    
    // Get performance statistics
    let stats = simd.get_stats();
    println!("Performance Stats: {:?}", stats);
    println!("Operations per Cycle: {:.2}", stats.operations_per_cycle());
    println!("Fallback Rate: {:.2}%", stats.fallback_rate() * 100.0);
    
    correct && correct_add
}

/// Test lock-free memory pooling
pub fn test_lock_free_pooling() -> bool {
    println!("Testing Lock-Free Memory Pooling...");
    
    let pool = ObjectPool::<Vec<f32>>::new(100);
    
    // Test basic allocation and deallocation
    let start = Instant::now();
    let mut objects = Vec::new();
    
    for i in 0..10 {
        let obj = pool.get();
        let mut vec = obj.take();
        vec.push(i as f32);
        objects.push(vec);
    }
    
    let duration = start.elapsed();
    println!("Allocation Duration: {:?}", duration);
    
    // Verify objects were created correctly
    let correct = objects.iter().enumerate().all(|(i, vec)| {
        vec.len() == 1 && vec[0] == i as f32
    });
    
    println!("Objects Correct: {}", correct);
    
    // Test pool statistics
    let stats = pool.get_monitoring_stats();
    println!("Pool Stats: {:?}", stats);
    
    correct
}

/// Test extreme configuration integration
pub fn test_extreme_configuration() -> bool {
    println!("Testing Extreme Configuration Integration...");
    
    // Test that all components work together
    let avx512_test = test_extreme_avx512();
    let pooling_test = test_lock_free_pooling();
    
    println!("AVX-512 Test: {}", avx512_test);
    println!("Pooling Test: {}", pooling_test);
    
    avx512_test && pooling_test
}

/// Performance benchmark comparison
pub fn benchmark_comparison() {
    println!("Performance Benchmark Comparison...");
    
    let simd: EnhancedSimdProcessor<16> = EnhancedSimdProcessor::new();
    let a = vec![1.0f32; 4096];
    let b = vec![2.0f32; 4096];
    let mut a_add = vec![1.0f32; 4096];
    let b_add = vec![3.0f32; 4096];
    
    // Benchmark dot product
    let start_dot = Instant::now();
    for _ in 0..1000 {
        let _ = simd.dot_product(&a, &b);
    }
    let duration_dot = start_dot.elapsed();
    
    // Benchmark vector addition
    let start_add = Instant::now();
    for _ in 0..1000 {
        simd.vector_add(&mut a_add, &b_add);
    }
    let duration_add = start_add.elapsed();
    
    println!("Dot Product Benchmark (1000 iterations): {:?}", duration_dot);
    println!("Vector Addition Benchmark (1000 iterations): {:?}", duration_add);
    println!("Dot Product ops/ms: {:.2}", 1000.0 / duration_dot.as_millis() as f64);
    println!("Vector Addition ops/ms: {:.2}", 1000.0 / duration_add.as_millis() as f64);
}

/// Main test function
pub fn run_all_tests() -> bool {
    println!("=== Extreme Performance Optimization Tests ===");
    println!();
    
    let test1 = test_extreme_avx512();
    println!();
    
    let test2 = test_lock_free_pooling();
    println!();
    
    let test3 = test_extreme_configuration();
    println!();
    
    benchmark_comparison();
    println!();
    
    let overall_success = test1 && test2 && test3;
    
    println!("=== Test Results ===");
    println!("AVX-512 Test: {}", if test1 { "PASS" } else { "FAIL" });
    println!("Lock-Free Pooling Test: {}", if test2 { "PASS" } else { "FAIL" });
    println!("Integration Test: {}", if test3 { "PASS" } else { "FAIL" });
    println!("Overall: {}", if overall_success { "ALL TESTS PASSED" } else { "SOME TESTS FAILED" });
    
    overall_success
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_extreme_optimizations() {
        assert!(run_all_tests());
    }
}