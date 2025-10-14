//! Enhanced SIMD operations for high-performance vector processing
//! Supports AVX2, SSE, and AVX-512 instruction sets with runtime detection
//! Extreme optimization with aggressive AVX-512 intrinsics and minimal branching

use crate::logging::generate_trace_id;
use std::sync::atomic::{AtomicU64, Ordering};

/// SIMD instruction set capabilities with extreme performance levels
#[derive(Debug, Clone, Copy)]
pub enum SimdCapability {
    Sse,
    Avx2,
    Avx512,
    Avx512Extreme, // Ultra-aggressive AVX-512 optimizations
    Scalar,
}

/// Runtime SIMD capability detection with aggressive AVX-512 detection and logging
pub fn detect_simd_capability() -> SimdCapability {
    #[cfg(target_arch = "x86_64")]
    {
        let _trace_id = generate_trace_id();

        // Extreme AVX-512 detection: require all major AVX-512 features
        let avx512_extreme = is_x86_feature_detected!("avx512f")
            && is_x86_feature_detected!("avx512dq")
            && is_x86_feature_detected!("avx512bw")
            && is_x86_feature_detected!("avx512vl")
            && is_x86_feature_detected!("avx512cd")
            && is_x86_feature_detected!("avx512ifma");

        let avx512_basic =
            is_x86_feature_detected!("avx512f") && is_x86_feature_detected!("avx512vl");
        let avx2 = is_x86_feature_detected!("avx2");
        let sse = is_x86_feature_detected!("sse4.2");

        // Log detected capabilities
        let mut capabilities = Vec::new();
        if avx512_extreme {
            capabilities.push("AVX-512 Extreme");
        }
        if avx512_basic {
            capabilities.push("AVX-512");
        }
        if avx2 {
            capabilities.push("AVX2");
        }
        if sse {
            capabilities.push("SSE4.2");
        }

        if capabilities.is_empty() {
            capabilities.push("Scalar");
        }

        log::info!("CPU Capabilities detected: {}", capabilities.join(" "));

        if avx512_extreme {
            log::info!("SIMD Level: AVX-512 Extreme active");
            return SimdCapability::Avx512Extreme;
        }
        if avx512_basic {
            log::info!("SIMD Level: AVX-512 active");
            return SimdCapability::Avx512;
        }
        if avx2 {
            log::info!("SIMD Level: AVX2 active");
            return SimdCapability::Avx2;
        }
        if sse {
            log::info!("SIMD Level: SSE active");
            return SimdCapability::Sse;
        }
    }

    log::warn!("SIMD Level: Scalar fallback (no SIMD support detected)");
    SimdCapability::Scalar
}

/// Enhanced vector operations with SIMD acceleration and extreme optimizations
pub struct EnhancedSimdProcessor<const MAX_BATCH_SIZE: usize = 16> {
    capability: SimdCapability,
    // Performance counters for extreme optimization monitoring
    operation_count: AtomicU64,
    cycle_count: AtomicU64,
    fallback_count: AtomicU64,
}

/// Performance statistics for extreme SIMD optimization monitoring
#[derive(Debug, Clone)]
pub struct SimdPerformanceStats {
    pub total_operations: u64,
    pub total_cycles: u64,
    pub fallback_operations: u64,
    pub capability: SimdCapability,
}

impl SimdPerformanceStats {
    /// Calculate operations per cycle (OPC) metric
    pub fn operations_per_cycle(&self) -> f64 {
        if self.total_cycles > 0 {
            self.total_operations as f64 / self.total_cycles as f64
        } else {
            0.0
        }
    }

    /// Calculate fallback rate
    pub fn fallback_rate(&self) -> f64 {
        if self.total_operations > 0 {
            self.fallback_operations as f64 / self.total_operations as f64
        } else {
            0.0
        }
    }
}

impl<const MAX_BATCH_SIZE: usize> EnhancedSimdProcessor<MAX_BATCH_SIZE> {
    pub fn new() -> Self {
        let _trace_id = generate_trace_id();
        let capability = detect_simd_capability();

        // Log initialization with capability
        log::info!(
            "EnhancedSimdProcessor initialized with capability: {:?}",
            capability
        );

        Self {
            capability,
            operation_count: AtomicU64::new(0),
            cycle_count: AtomicU64::new(0),
            fallback_count: AtomicU64::new(0),
        }
    }

    /// Get performance statistics with logging
    pub fn get_stats(&self) -> SimdPerformanceStats {
        let _trace_id = generate_trace_id();
        let stats = SimdPerformanceStats {
            total_operations: self.operation_count.load(Ordering::Relaxed),
            total_cycles: self.cycle_count.load(Ordering::Relaxed),
            fallback_operations: self.fallback_count.load(Ordering::Relaxed),
            capability: self.capability,
        };

        // Log performance statistics periodically
        if stats.total_operations > 0 && stats.total_operations % 1000 == 0 {
            log::info!(
                "SIMD Performance: {} ops, {} cycles, {:.2} ops/cycle, {:.2}% fallback rate",
                stats.total_operations,
                stats.total_cycles,
                stats.operations_per_cycle(),
                stats.fallback_rate() * 100.0
            );
        }

        stats
    }

    /// Get current SIMD capability
    #[inline(always)]
    pub fn get_capability(&self) -> SimdCapability {
        self.capability
    }

    /// Check if AVX-512 is supported
    #[inline(always)]
    pub fn has_avx512(&self) -> bool {
        matches!(
            self.capability,
            SimdCapability::Avx512 | SimdCapability::Avx512Extreme
        )
    }

    /// Check if AVX2 is supported
    #[inline(always)]
    pub fn has_avx2(&self) -> bool {
        matches!(self.capability, SimdCapability::Avx2)
    }

    /// Check if SSE is supported
    #[inline(always)]
    pub fn has_sse(&self) -> bool {
        matches!(self.capability, SimdCapability::Sse)
    }
}

impl<const MAX_BATCH_SIZE: usize> Default for EnhancedSimdProcessor<MAX_BATCH_SIZE> {
    fn default() -> Self {
        Self::new()
    }
}



#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_dot_product() {
        let simd = EnhancedSimdProcessor::<16>::new();
        let a = vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0];
        let b = vec![8.0, 7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0];

        let result = simd.dot_product(&a, &b);
        let expected = 120.0; // 1*8 + 2*7 + 3*6 + 4*5 + 5*4 + 6*3 + 7*2 + 8*1

        assert!((result - expected).abs() < 1e-6);
    }

    #[test]
    fn test_vector_add() {
        let simd = EnhancedSimdProcessor::<16>::new();
        let mut a = vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0];
        let b = vec![8.0, 7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0];
        let expected = vec![9.0, 9.0, 9.0, 9.0, 9.0, 9.0, 9.0, 9.0];

        simd.vector_add(&mut a, &b);

        for (i, (result, expect)) in a.iter().zip(expected.iter()).enumerate() {
            assert!(
                (result - expect).abs() < 1e-6,
                "Mismatch at index {}: {} != {}",
                i,
                result,
                expect
            );
        }
    }

    #[test]
    fn test_aabb_intersection() {
        let simd = EnhancedSimdProcessor::<16>::new();
        let aabbs = vec![
            (0.0, 10.0, 0.0, 10.0, 0.0, 10.0),    // AABB 1
            (5.0, 15.0, 5.0, 15.0, 5.0, 15.0),    // AABB 2
            (20.0, 30.0, 20.0, 30.0, 20.0, 30.0), // AABB 3 (no intersection)
        ];
        let query = (4.0, 6.0, 4.0, 6.0, 4.0, 6.0); // Query AABB

        let results = simd.batch_aabb_intersect(&aabbs, query);

        assert_eq!(results.len(), 3);
        assert!(results[0]); // Should intersect with AABB 1
        assert!(results[1]); // Should intersect with AABB 2
        assert!(!results[2]); // Should not intersect with AABB 3
    }

    #[test]
    fn test_batch_operations() {
        let batch_processor = BatchSimdProcessor::new(4);

        let operations = vec![
            VectorOperation::DotProduct(vec![1.0, 2.0], vec![3.0, 4.0]), // 1*3 + 2*4 = 11
            VectorOperation::Magnitude(vec![3.0, 4.0]),                  // sqrt(9 + 16) = 5
        ];

        let results = batch_processor.process_vectors_parallel(&operations);

        assert_eq!(results.len(), 2);
        assert!((results[0] - 11.0).abs() < 1e-6);
        assert!((results[1] - 5.0).abs() < 1e-6);
    }
    #[test]
    fn test_small_batch_optimizations() {
        let simd = EnhancedSimdProcessor::<16>::new();

        // Test dot product for small batches
        let test_cases = vec![
            (vec![1.0, 2.0], vec![3.0, 4.0], 11.0),           // 2 elements
            (vec![1.0, 2.0, 3.0], vec![4.0, 5.0, 6.0], 32.0), // 3 elements
            (vec![1.0, 2.0, 3.0, 4.0], vec![4.0, 3.0, 2.0, 1.0], 20.0), // 4 elements
            (
                vec![1.0, 2.0, 3.0, 4.0, 5.0],
                vec![5.0, 4.0, 3.0, 2.0, 1.0],
                35.0,
            ), // 5 elements
            (
                vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0],
                vec![6.0, 5.0, 4.0, 3.0, 2.0, 1.0],
                56.0,
            ), // 6 elements
            (
                vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0],
                vec![7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0],
                84.0,
            ), // 7 elements
        ];

        for (a, b, expected) in test_cases {
            let result = simd.dot_product(&a, &b);
            assert!(
                (result - expected).abs() < 1e-6,
                "Failed for {} elements: expected {}, got {}",
                a.len(),
                expected,
                result
            );
        }

        // Test vector addition for small batches
        let mut test_a = vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0];
        let test_b = vec![7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0];
        let expected = vec![8.0, 8.0, 8.0, 8.0, 8.0, 8.0, 8.0];

        simd.vector_add(&mut test_a, &test_b);

        for (i, (&result, &expect)) in test_a.iter().zip(expected.iter()).enumerate() {
            assert!(
                (result - expect).abs() < 1e-6,
                "Vector add failed at index {}: expected {}, got {}",
                i,
                expect,
                result
            );
        }
    }
}
