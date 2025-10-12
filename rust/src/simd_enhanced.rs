//! Enhanced SIMD operations for high-performance vector processing
//! Supports AVX2, SSE, and AVX-512 instruction sets with runtime detection
//! Extreme optimization with aggressive AVX-512 intrinsics and minimal branching

use std::arch::x86_64::*;
use rayon::prelude::*;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Instant;
use crate::logging::{generate_trace_id};

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
        let avx512_extreme = is_x86_feature_detected!("avx512f") &&
           is_x86_feature_detected!("avx512dq") &&
           is_x86_feature_detected!("avx512bw") &&
           is_x86_feature_detected!("avx512vl") &&
           is_x86_feature_detected!("avx512cd") &&
           is_x86_feature_detected!("avx512ifma");
        
        let avx512_basic = is_x86_feature_detected!("avx512f") && is_x86_feature_detected!("avx512vl");
        let avx2 = is_x86_feature_detected!("avx2");
        let sse = is_x86_feature_detected!("sse4.2");
        
        // Log detected capabilities
        let mut capabilities = Vec::new();
        if avx512_extreme { capabilities.push("AVX-512 Extreme"); }
        if avx512_basic { capabilities.push("AVX-512"); }
        if avx2 { capabilities.push("AVX2"); }
        if sse { capabilities.push("SSE4.2"); }
        
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
        log::info!("EnhancedSimdProcessor initialized with capability: {:?}", capability);
        
        Self {
            capability,
            operation_count: AtomicU64::new(0),
            cycle_count: AtomicU64::new(0),
            fallback_count: AtomicU64::new(0),
        }
    }
    
    /// Record operation metrics for extreme performance monitoring with logging
    #[inline(always)]
    fn record_operation(&self, cycles: u64, is_fallback: bool) {
        self.operation_count.fetch_add(1, Ordering::Relaxed);
        self.cycle_count.fetch_add(cycles, Ordering::Relaxed);
        if is_fallback {
            self.fallback_count.fetch_add(1, Ordering::Relaxed);
            
            // Log fallback events for debugging
            log::warn!("SIMD fallback detected: {} cycles", cycles);
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
            log::info!("SIMD Performance: {} ops, {} cycles, {:.2} ops/cycle, {:.2}% fallback rate",
                stats.total_operations,
                stats.total_cycles,
                stats.operations_per_cycle(),
                stats.fallback_rate() * 100.0);
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
        matches!(self.capability, SimdCapability::Avx512 | SimdCapability::Avx512Extreme)
    }
    
    /// Check if AVX2 is supported
    #[inline(always)]
    pub fn has_avx2(&self) -> bool {
        matches!(self.capability, SimdCapability::Avx2 | SimdCapability::Avx512 | SimdCapability::Avx512Extreme)
    }
    
    /// Check if SSE is supported
    #[inline(always)]
    pub fn has_sse(&self) -> bool {
        matches!(self.capability, SimdCapability::Sse | SimdCapability::Avx2 | SimdCapability::Avx512 | SimdCapability::Avx512Extreme)
    }
     
    /// Optimized dot product with SIMD acceleration - extreme optimization with minimal branching
    #[inline(always)]
    pub fn dot_product(&self, a: &[f32], b: &[f32]) -> f32 {
        debug_assert_eq!(a.len(), b.len(), "Vectors must have equal length for dot product");

        let start_time = Instant::now();

        // Extreme optimization: direct SIMD dispatch with minimal conditionals
        let result = match self.capability {
            SimdCapability::Avx512Extreme => unsafe { self.dot_product_avx512(a, b) },
            SimdCapability::Avx512 => unsafe { self.dot_product_avx512(a, b) },
            SimdCapability::Avx2 => unsafe { self.dot_product_avx2(a, b) },
            SimdCapability::Sse => unsafe { self.dot_product_sse(a, b) },
            SimdCapability::Scalar => self.dot_product_scalar(a, b),
        };

        let cycles = start_time.elapsed().as_nanos() as u64;
        self.record_operation(cycles, matches!(self.capability, SimdCapability::Scalar));
        result
    }

    /// Specialized dot product for small batches (2-7 elements) - no overhead, direct SIMD
    #[inline(always)]
    #[allow(dead_code)]
    fn dot_product_small_batch(&self, a: &[f32], b: &[f32]) -> f32 {
        let len = a.len();

        match len {
            2 => {
                // Direct scalar for 2 elements - minimal overhead
                a[0] * b[0] + a[1] * b[1]
            },
            3 => {
                // Direct scalar for 3 elements
                a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
            },
            4 => {
                // SSE for exactly 4 elements - no padding needed
                if matches!(self.capability, SimdCapability::Sse | SimdCapability::Avx2 | SimdCapability::Avx512) {
                    unsafe { self.dot_product_sse_exact4(a, b) }
                } else {
                    self.dot_product_scalar(a, b)
                }
            },
            5..=7 => {
                // SSE for 5-7 elements with minimal padding
                if matches!(self.capability, SimdCapability::Sse | SimdCapability::Avx2 | SimdCapability::Avx512) {
                    unsafe { self.dot_product_sse_small(a, b) }
                } else {
                    self.dot_product_scalar(a, b)
                }
            },
            _ => unreachable!("Should not reach here for small batches"),
        }
    }

    /// SSE optimized dot product for exactly 4 elements (no padding)
    #[target_feature(enable = "sse4.2")]
    #[allow(dead_code)]
    unsafe fn dot_product_sse_exact4(&self, a: &[f32], b: &[f32]) -> f32 {
        let va = _mm_loadu_ps(a.as_ptr());
        let vb = _mm_loadu_ps(b.as_ptr());
        let prod = _mm_mul_ps(va, vb);
        Self::hsum128_ps(prod)
    }

    /// SSE optimized dot product for 5-7 elements with minimal overhead
    #[target_feature(enable = "sse4.2")]
    #[allow(dead_code)]
    unsafe fn dot_product_sse_small(&self, a: &[f32], b: &[f32]) -> f32 {
        let len = a.len();
        let mut sum = 0.0f32;

        // Process first 4 elements with SSE
        if len >= 4 {
            let va = _mm_loadu_ps(a.as_ptr());
            let vb = _mm_loadu_ps(b.as_ptr());
            let prod = _mm_mul_ps(va, vb);
            sum += Self::hsum128_ps(prod);
        }

        // Handle remaining elements scalar
        for i in 4..len {
            sum += a[i] * b[i];
        }

        sum
    }
    
    /// AVX-512 optimized dot product with const batch size
        #[target_feature(enable = "avx512f,avx512vl")]
        unsafe fn dot_product_avx512(&self, a: &[f32], b: &[f32]) -> f32 {
            let avx512_width: usize = MAX_BATCH_SIZE.min(16);
            let len = a.len();
            let mut sum = _mm512_setzero_ps();

            let mut i = 0;
            while i + avx512_width <= len {
                // Prefetch next chunk to reduce cache misses
                if i + avx512_width * 2 <= len {
                    _mm_prefetch(a.as_ptr().add(i + avx512_width) as *const i8, _MM_HINT_T0);
                    _mm_prefetch(b.as_ptr().add(i + avx512_width) as *const i8, _MM_HINT_T0);
                }

                let va = _mm512_loadu_ps(a.as_ptr().add(i));
                let vb = _mm512_loadu_ps(b.as_ptr().add(i));
                sum = _mm512_fmadd_ps(va, vb, sum);
                i += avx512_width;
            }
             
            // Reduce sum
            let mut result = _mm512_reduce_add_ps(sum);
             
            // Handle remaining elements with likely hint for branch prediction
            while i < len {
                result += a[i] * b[i];
                i += 1;
            }
             
            result
        }
    
    /// AVX2 optimized dot product with const batch size
        #[target_feature(enable = "avx2")]
        unsafe fn dot_product_avx2(&self, a: &[f32], b: &[f32]) -> f32 {
            let avx2_width: usize = MAX_BATCH_SIZE.min(8);
            let double_width: usize = avx2_width * 2;
            let len = a.len();
            let mut sum0 = _mm256_setzero_ps();
            let mut sum1 = _mm256_setzero_ps();
            
            let mut i = 0;
            while i + double_width <= len {
                // Prefetch next chunk to reduce cache misses
                if i + double_width * 2 <= len {
                    _mm_prefetch(a.as_ptr().add(i + double_width) as *const i8, _MM_HINT_T0);
                    _mm_prefetch(b.as_ptr().add(i + double_width) as *const i8, _MM_HINT_T0);
                }
                
                let va0 = _mm256_loadu_ps(a.as_ptr().add(i));
                let vb0 = _mm256_loadu_ps(b.as_ptr().add(i));
                sum0 = _mm256_fmadd_ps(va0, vb0, sum0);
                 
                let va1 = _mm256_loadu_ps(a.as_ptr().add(i + avx2_width));
                let vb1 = _mm256_loadu_ps(b.as_ptr().add(i + avx2_width));
                sum1 = _mm256_fmadd_ps(va1, vb1, sum1);
                 
                i += double_width;
            }
             
            // Reduce sums
            let sum = _mm256_add_ps(sum0, sum1);
            let mut result = Self::hsum256_ps(sum);
             
            // Handle remaining elements with likely hint for branch prediction
            while i < len {
                result += a[i] * b[i];
                i += 1;
            }
             
            result
        }
    
    /// SSE optimized dot product with const batch size
        #[target_feature(enable = "sse4.2")]
        unsafe fn dot_product_sse(&self, a: &[f32], b: &[f32]) -> f32 {
            let sse_width: usize = MAX_BATCH_SIZE.min(4);
            let quad_width: usize = sse_width * 4;
            let len = a.len();
            let mut sum0 = _mm_setzero_ps();
            let mut sum1 = _mm_setzero_ps();
            let mut sum2 = _mm_setzero_ps();
            let mut sum3 = _mm_setzero_ps();
            
            let mut i = 0;
            while i + quad_width <= len {
                // Prefetch next chunk to reduce cache misses
                if i + quad_width * 2 <= len {
                    _mm_prefetch(a.as_ptr().add(i + quad_width) as *const i8, _MM_HINT_T0);
                    _mm_prefetch(b.as_ptr().add(i + quad_width) as *const i8, _MM_HINT_T0);
                }
                
                let va0 = _mm_loadu_ps(a.as_ptr().add(i));
                let vb0 = _mm_loadu_ps(b.as_ptr().add(i));
                sum0 = _mm_add_ps(_mm_mul_ps(va0, vb0), sum0);
                 
                let va1 = _mm_loadu_ps(a.as_ptr().add(i + sse_width));
                let vb1 = _mm_loadu_ps(b.as_ptr().add(i + sse_width));
                sum1 = _mm_add_ps(_mm_mul_ps(va1, vb1), sum1);
                 
                let va2 = _mm_loadu_ps(a.as_ptr().add(i + sse_width * 2));
                let vb2 = _mm_loadu_ps(b.as_ptr().add(i + sse_width * 2));
                sum2 = _mm_add_ps(_mm_mul_ps(va2, vb2), sum2);
                 
                let va3 = _mm_loadu_ps(a.as_ptr().add(i + sse_width * 3));
                let vb3 = _mm_loadu_ps(b.as_ptr().add(i + sse_width * 3));
                sum3 = _mm_add_ps(_mm_mul_ps(va3, vb3), sum3);
                 
                i += quad_width;
            }
             
            // Reduce sums
            let sum01 = _mm_add_ps(sum0, sum1);
            let sum23 = _mm_add_ps(sum2, sum3);
            let sum = _mm_add_ps(sum01, sum23);
            let mut result = Self::hsum128_ps(sum);
             
            // Handle remaining elements with likely hint for branch prediction
            while i < len {
                result += a[i] * b[i];
                i += 1;
            }
             
            result
        }
    
    /// Scalar fallback dot product
    fn dot_product_scalar(&self, a: &[f32], b: &[f32]) -> f32 {
        a.iter().zip(b.iter()).map(|(x, y)| x * y).sum()
    }
    
    /// Optimized vector addition with SIMD acceleration - aggressive optimization for small batches
    #[inline(always)]
    pub fn vector_add(&self, a: &mut [f32], b: &[f32]) {
        debug_assert_eq!(a.len(), b.len(), "Vectors must have equal length for addition");

        let len = a.len();

        // Aggressive optimization for small batches (2-7 elements) with branch prediction
        if len <= 7 {
            return self.vector_add_small_batch(a, b);
        }

        match self.capability {
            SimdCapability::Avx512Extreme => unsafe { self.vector_add_avx512(a, b) },
            SimdCapability::Avx512 => unsafe { self.vector_add_avx512(a, b) },
            SimdCapability::Avx2 => unsafe { self.vector_add_avx2(a, b) },
            SimdCapability::Sse => unsafe { self.vector_add_sse(a, b) },
            SimdCapability::Scalar => self.vector_add_scalar(a, b),
        }
    }

    /// Specialized vector addition for small batches (2-7 elements) - no overhead
    #[inline(always)]
    fn vector_add_small_batch(&self, a: &mut [f32], b: &[f32]) {
        let len = a.len();

        match len {
            2 => {
                a[0] += b[0];
                a[1] += b[1];
            },
            3 => {
                a[0] += b[0];
                a[1] += b[1];
                a[2] += b[2];
            },
            4 => {
                if matches!(self.capability, SimdCapability::Sse | SimdCapability::Avx2 | SimdCapability::Avx512) {
                    unsafe { self.vector_add_sse_exact4(a, b) }
                } else {
                    self.vector_add_scalar(a, b);
                }
            },
            5..=7 => {
                if matches!(self.capability, SimdCapability::Sse | SimdCapability::Avx2 | SimdCapability::Avx512) {
                    unsafe { self.vector_add_sse_small(a, b) }
                } else {
                    self.vector_add_scalar(a, b);
                }
            },
            _ => unreachable!("Should not reach here for small batches"),
        }
    }

    /// SSE optimized vector addition for exactly 4 elements
    #[target_feature(enable = "sse4.2")]
    unsafe fn vector_add_sse_exact4(&self, a: &mut [f32], b: &[f32]) {
        let va = _mm_loadu_ps(a.as_ptr());
        let vb = _mm_loadu_ps(b.as_ptr());
        let result = _mm_add_ps(va, vb);
        _mm_storeu_ps(a.as_mut_ptr(), result);
    }

    /// SSE optimized vector addition for 5-7 elements
    #[target_feature(enable = "sse4.2")]
    unsafe fn vector_add_sse_small(&self, a: &mut [f32], b: &[f32]) {
        let len = a.len();

        // Process first 4 elements with SSE
        if len >= 4 {
            let va = _mm_loadu_ps(a.as_ptr());
            let vb = _mm_loadu_ps(b.as_ptr());
            let result = _mm_add_ps(va, vb);
            _mm_storeu_ps(a.as_mut_ptr(), result);
        }

        // Handle remaining elements scalar
        for i in 4..len {
            a[i] += b[i];
        }
    }
    
    /// AVX-512 optimized vector addition with const generic batch size and prefetching
    #[target_feature(enable = "avx512f,avx512vl")]
    unsafe fn vector_add_avx512(&self, a: &mut [f32], b: &[f32]) {
        let avx512_width: usize = MAX_BATCH_SIZE.min(16);
        let len = a.len();

        let mut i = 0;
        while i + avx512_width <= len {
            // Prefetch next chunk to reduce cache misses
            if i + avx512_width * 2 <= len {
                _mm_prefetch(a.as_ptr().add(i + avx512_width) as *const i8, _MM_HINT_T0);
                _mm_prefetch(b.as_ptr().add(i + avx512_width) as *const i8, _MM_HINT_T0);
            }

            let va = _mm512_loadu_ps(a.as_ptr().add(i));
            let vb = _mm512_loadu_ps(b.as_ptr().add(i));
            let result = _mm512_add_ps(va, vb);
            _mm512_storeu_ps(a.as_mut_ptr().add(i), result);

            if i + avx512_width <= len {
                i += avx512_width;
            } else {
                break;
            }
        }
        
        // Handle remaining elements with unrolled loop for better ILP
        while i < len {
            a[i] += b[i];
            i += 1;
        }
    }
    
    /// AVX2 optimized vector addition with const generic batch size and prefetching
    #[target_feature(enable = "avx2")]
    unsafe fn vector_add_avx2(&self, a: &mut [f32], b: &[f32]) {
        let avx2_width: usize = MAX_BATCH_SIZE.min(8);
        let double_width: usize = avx2_width * 2;
        let len = a.len();

        let mut i = 0;
        while i + double_width <= len {
            // Prefetch next chunk to reduce cache misses
            if i + double_width * 2 <= len {
                _mm_prefetch(a.as_ptr().add(i + double_width) as *const i8, _MM_HINT_T0);
                _mm_prefetch(b.as_ptr().add(i + double_width) as *const i8, _MM_HINT_T0);
            }
            
            // Process two chunks simultaneously for better ILP
            let va0 = _mm256_loadu_ps(a.as_ptr().add(i));
            let vb0 = _mm256_loadu_ps(b.as_ptr().add(i));
            let result0 = _mm256_add_ps(va0, vb0);
            _mm256_storeu_ps(a.as_mut_ptr().add(i), result0);
            
            let va1 = _mm256_loadu_ps(a.as_ptr().add(i + avx2_width));
            let vb1 = _mm256_loadu_ps(b.as_ptr().add(i + avx2_width));
            let result1 = _mm256_add_ps(va1, vb1);
            _mm256_storeu_ps(a.as_mut_ptr().add(i + avx2_width), result1);
            
            i += double_width;
        }
        
        // Handle remaining elements with unrolled loop
        while i < len {
            a[i] += b[i];
            i += 1;
        }
    }
    
    /// SSE optimized vector addition with const generic batch size and prefetching
    #[target_feature(enable = "sse4.2")]
    unsafe fn vector_add_sse(&self, a: &mut [f32], b: &[f32]) {
        let sse_width: usize = MAX_BATCH_SIZE.min(4);
        let quad_width: usize = sse_width * 4;
        let len = a.len();

        let mut i = 0;
        while i + quad_width <= len {
            // Prefetch next chunk to reduce cache misses
            if i + quad_width * 2 <= len {
                _mm_prefetch(a.as_ptr().add(i + quad_width) as *const i8, _MM_HINT_T0);
                _mm_prefetch(b.as_ptr().add(i + quad_width) as *const i8, _MM_HINT_T0);
            }
            
            // Process four chunks simultaneously for better ILP
            let va0 = _mm_loadu_ps(a.as_ptr().add(i));
            let vb0 = _mm_loadu_ps(b.as_ptr().add(i));
            let result0 = _mm_add_ps(va0, vb0);
            _mm_storeu_ps(a.as_mut_ptr().add(i), result0);
            
            let va1 = _mm_loadu_ps(a.as_ptr().add(i + sse_width));
            let vb1 = _mm_loadu_ps(b.as_ptr().add(i + sse_width));
            let result1 = _mm_add_ps(va1, vb1);
            _mm_storeu_ps(a.as_mut_ptr().add(i + sse_width), result1);
            
            let va2 = _mm_loadu_ps(a.as_ptr().add(i + sse_width * 2));
            let vb2 = _mm_loadu_ps(b.as_ptr().add(i + sse_width * 2));
            let result2 = _mm_add_ps(va2, vb2);
            _mm_storeu_ps(a.as_mut_ptr().add(i + sse_width * 2), result2);
            
            let va3 = _mm_loadu_ps(a.as_ptr().add(i + sse_width * 3));
            let vb3 = _mm_loadu_ps(b.as_ptr().add(i + sse_width * 3));
            let result3 = _mm_add_ps(va3, vb3);
            _mm_storeu_ps(a.as_mut_ptr().add(i + sse_width * 3), result3);

            i += quad_width;
        }
        
        // Handle remaining elements with unrolled loop
        while i < len {
            a[i] += b[i];
            i += 1;
        }
    }
    
    /// Scalar fallback vector addition
    fn vector_add_scalar(&self, a: &mut [f32], b: &[f32]) {
        for (x, y) in a.iter_mut().zip(b.iter()) {
            *x += y;
        }
    }
    
    /// Parallel batch processing with SIMD acceleration
    pub fn process_batch_parallel<F>(&self, data: &mut [f32], batch_size: usize, processor: F)
    where
        F: Fn(&mut [f32]) + Send + Sync,
    {
        data.par_chunks_mut(batch_size)
            .for_each(|chunk| {
                processor(chunk);
            });
    }
    
    /// Optimized AABB intersection for spatial queries
    #[inline(always)]
    pub fn batch_aabb_intersect(&self, aabbs: &[(f32, f32, f32, f32, f32, f32)], query: (f32, f32, f32, f32, f32, f32)) -> Vec<bool> {
        match self.capability {
            SimdCapability::Avx512Extreme => unsafe { self.batch_aabb_intersect_avx512(aabbs, query) },
            SimdCapability::Avx512 => unsafe { self.batch_aabb_intersect_avx512(aabbs, query) },
            SimdCapability::Avx2 => unsafe { self.batch_aabb_intersect_avx2(aabbs, query) },
            SimdCapability::Sse => unsafe { self.batch_aabb_intersect_sse(aabbs, query) },
            SimdCapability::Scalar => self.batch_aabb_intersect_scalar(aabbs, query),
        }
    }
    
    /// AVX-512 optimized batch AABB intersection
    #[target_feature(enable = "avx512f,avx512vl")]
    unsafe fn batch_aabb_intersect_avx512(&self, aabbs: &[(f32, f32, f32, f32, f32, f32)], query: (f32, f32, f32, f32, f32, f32)) -> Vec<bool> {
        let query_vec = _mm512_set_ps(
            query.2, query.3, query.0, query.1, query.5, query.4, query.5, query.4,
            query.2, query.3, query.0, query.1, query.5, query.4, query.5, query.4,
        );
        
        let mut results = Vec::with_capacity(aabbs.len());
        
        for aabb in aabbs {
            let aabb_vec = _mm512_set_ps(
                aabb.2, aabb.3, aabb.0, aabb.1, aabb.5, aabb.4, aabb.5, aabb.4,
                aabb.2, aabb.3, aabb.0, aabb.1, aabb.5, aabb.4, aabb.5, aabb.4,
            );
            
            // Test: query.min <= aabb.max && query.max >= aabb.min
            let min_test = _mm512_cmp_ps_mask(query_vec, aabb_vec, _CMP_LE_OS);
            let max_test = _mm512_cmp_ps_mask(query_vec, aabb_vec, _CMP_GE_OS);
            
            // Extract relevant comparisons for each axis
            let x_intersect = (min_test & 0x0003) != 0 && (max_test & 0x000C) != 0;
            let y_intersect = (min_test & 0x0030) != 0 && (max_test & 0x00C0) != 0;
            let z_intersect = (min_test & 0x0300) != 0 && (max_test & 0x0C00) != 0;
            
            results.push(x_intersect && y_intersect && z_intersect);
        }
        
        results
    }
    
    /// AVX2 optimized batch AABB intersection
    #[target_feature(enable = "avx2")]
    unsafe fn batch_aabb_intersect_avx2(&self, aabbs: &[(f32, f32, f32, f32, f32, f32)], query: (f32, f32, f32, f32, f32, f32)) -> Vec<bool> {
        let query_min = _mm256_set_ps(query.0, query.1, query.2, query.2, query.4, query.5, 0.0, 0.0);
        let query_max = _mm256_set_ps(query.1, query.0, query.3, query.3, query.5, query.4, 0.0, 0.0);
        
        let mut results = Vec::with_capacity(aabbs.len());
        
        for aabb in aabbs {
            let aabb_min = _mm256_set_ps(aabb.0, aabb.1, aabb.2, aabb.2, aabb.4, aabb.5, 0.0, 0.0);
            let aabb_max = _mm256_set_ps(aabb.1, aabb.0, aabb.3, aabb.3, aabb.5, aabb.4, 0.0, 0.0);
            
            // Test: query.min <= aabb.max && query.max >= aabb.min
            let min_test = _mm256_cmp_ps(query_min, aabb_max, _CMP_LE_OS);
            let max_test = _mm256_cmp_ps(query_max, aabb_min, _CMP_GE_OS);
            
            // Combine results
            let intersect = _mm256_and_ps(min_test, max_test);
            let mask = _mm256_movemask_ps(intersect);
            
            // Check if all relevant components intersect
            results.push((mask & 0x3F) == 0x3F); // Check first 6 components
        }
        
        results
    }
    
    /// SSE optimized batch AABB intersection
    #[target_feature(enable = "sse4.2")]
    unsafe fn batch_aabb_intersect_sse(&self, aabbs: &[(f32, f32, f32, f32, f32, f32)], query: (f32, f32, f32, f32, f32, f32)) -> Vec<bool> {
        let mut results = Vec::with_capacity(aabbs.len());
        
        for aabb in aabbs {
            // Process X axis
            let query_x_min = _mm_set1_ps(query.0);
            let query_x_max = _mm_set1_ps(query.1);
            let aabb_x_min = _mm_set1_ps(aabb.0);
            let aabb_x_max = _mm_set1_ps(aabb.1);
            
            let x_min_test = _mm_cmple_ps(query_x_min, aabb_x_max);
            let x_max_test = _mm_cmpge_ps(query_x_max, aabb_x_min);
            let x_intersect = _mm_movemask_ps(_mm_and_ps(x_min_test, x_max_test)) != 0;
            
            // Process Y axis
            let query_y_min = _mm_set1_ps(query.2);
            let query_y_max = _mm_set1_ps(query.3);
            let aabb_y_min = _mm_set1_ps(aabb.2);
            let aabb_y_max = _mm_set1_ps(aabb.3);
            
            let y_min_test = _mm_cmple_ps(query_y_min, aabb_y_max);
            let y_max_test = _mm_cmpge_ps(query_y_max, aabb_y_min);
            let y_intersect = _mm_movemask_ps(_mm_and_ps(y_min_test, y_max_test)) != 0;
            
            // Process Z axis
            let query_z_min = _mm_set1_ps(query.4);
            let query_z_max = _mm_set1_ps(query.5);
            let aabb_z_min = _mm_set1_ps(aabb.4);
            let aabb_z_max = _mm_set1_ps(aabb.5);
            
            let z_min_test = _mm_cmple_ps(query_z_min, aabb_z_max);
            let z_max_test = _mm_cmpge_ps(query_z_max, aabb_z_min);
            let z_intersect = _mm_movemask_ps(_mm_and_ps(z_min_test, z_max_test)) != 0;
            
            results.push(x_intersect && y_intersect && z_intersect);
        }
        
        results
    }
    
    /// Scalar fallback batch AABB intersection
    fn batch_aabb_intersect_scalar(&self, aabbs: &[(f32, f32, f32, f32, f32, f32)], query: (f32, f32, f32, f32, f32, f32)) -> Vec<bool> {
        aabbs.iter()
            .map(|aabb| {
                // Test: query.min <= aabb.max && query.max >= aabb.min
                query.0 <= aabb.1 && query.1 >= aabb.0 && // X axis
                query.2 <= aabb.3 && query.3 >= aabb.2 && // Y axis
                query.4 <= aabb.5 && query.5 >= aabb.4    // Z axis
            })
            .collect()
    }
    
    /// Helper function to horizontally sum AVX2 register
    #[target_feature(enable = "avx2")]
    unsafe fn hsum256_ps(v: __m256) -> f32 {
        let hi = _mm256_extractf128_ps(v, 1);
        let lo = _mm256_castps256_ps128(v);
        let sum128 = _mm_add_ps(lo, hi);
        Self::hsum128_ps(sum128)
    }
    
    /// Helper function to horizontally sum SSE register
    #[target_feature(enable = "sse4.2")]
    unsafe fn hsum128_ps(v: __m128) -> f32 {
        let shuf = _mm_movehl_ps(v, v);
        let sums = _mm_add_ps(v, shuf);
        let shuf2 = _mm_shuffle_ps(sums, sums, 1);
        let sum = _mm_add_ss(sums, shuf2);
        _mm_cvtss_f32(sum)
    }
}

/// Enhanced SIMD operations for batch processing
pub struct BatchSimdProcessor {
    simd: EnhancedSimdProcessor,
    #[allow(dead_code)]
    chunk_size: usize,
}

impl BatchSimdProcessor {
    pub fn new(chunk_size: usize) -> Self {
        Self {
            simd: EnhancedSimdProcessor::<16>::new(),
            chunk_size,
        }
    }
    
    /// Process multiple vector operations in parallel
    pub fn process_vectors_parallel(&self, operations: &[VectorOperation]) -> Vec<f32> {
        operations.par_iter()
            .map(|op| match op {
                VectorOperation::DotProduct(a, b) => self.simd.dot_product(a, b),
                VectorOperation::Magnitude(v) => {
                    let dot = self.simd.dot_product(v, v);
                    dot.sqrt()
                }
            })
            .collect()
    }
}

/// Vector operation types for batch processing
#[derive(Debug, Clone)]
pub enum VectorOperation {
    DotProduct(Vec<f32>, Vec<f32>),
    Magnitude(Vec<f32>),
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
            assert!((result - expect).abs() < 1e-6, "Mismatch at index {}: {} != {}", i, result, expect);
        }
    }
    
    #[test]
    fn test_aabb_intersection() {
        let simd = EnhancedSimdProcessor::<16>::new();
        let aabbs = vec![
            (0.0, 10.0, 0.0, 10.0, 0.0, 10.0), // AABB 1
            (5.0, 15.0, 5.0, 15.0, 5.0, 15.0), // AABB 2
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
            VectorOperation::Magnitude(vec![3.0, 4.0]), // sqrt(9 + 16) = 5
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
            (vec![1.0, 2.0], vec![3.0, 4.0], 11.0), // 2 elements
            (vec![1.0, 2.0, 3.0], vec![4.0, 5.0, 6.0], 32.0), // 3 elements
            (vec![1.0, 2.0, 3.0, 4.0], vec![4.0, 3.0, 2.0, 1.0], 20.0), // 4 elements
            (vec![1.0, 2.0, 3.0, 4.0, 5.0], vec![5.0, 4.0, 3.0, 2.0, 1.0], 35.0), // 5 elements
            (vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0], vec![6.0, 5.0, 4.0, 3.0, 2.0, 1.0], 56.0), // 6 elements
            (vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0], vec![7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0], 84.0), // 7 elements
        ];

        for (a, b, expected) in test_cases {
            let result = simd.dot_product(&a, &b);
            assert!((result - expected).abs() < 1e-6, "Failed for {} elements: expected {}, got {}", a.len(), expected, result);
        }

        // Test vector addition for small batches
        let mut test_a = vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0];
        let test_b = vec![7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0];
        let expected = vec![8.0, 8.0, 8.0, 8.0, 8.0, 8.0, 8.0];

        simd.vector_add(&mut test_a, &test_b);

        for (i, (&result, &expect)) in test_a.iter().zip(expected.iter()).enumerate() {
            assert!((result - expect).abs() < 1e-6, "Vector add failed at index {}: expected {}, got {}", i, expect, result);
        }
    }
}