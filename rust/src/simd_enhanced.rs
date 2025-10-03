//! Enhanced SIMD operations for high-performance vector processing
//! Supports AVX2, SSE, and AVX-512 instruction sets with runtime detection

use std::arch::x86_64::*;
use std::mem;
use std::simd::prelude::*;
use rayon::prelude::*;

/// SIMD instruction set capabilities
#[derive(Debug, Clone, Copy)]
pub enum SimdCapability {
    Sse,
    Avx2,
    Avx512,
    Scalar,
}

/// Runtime SIMD capability detection
pub fn detect_simd_capability() -> SimdCapability {
    #[cfg(target_arch = "x86_64")]
    {
        if is_x86_feature_detected!("avx512f") && is_x86_feature_detected!("avx512vl") {
            return SimdCapability::Avx512;
        }
        if is_x86_feature_detected!("avx2") {
            return SimdCapability::Avx2;
        }
        if is_x86_feature_detected!("sse4.2") {
            return SimdCapability::Sse;
        }
    }
    SimdCapability::Scalar
}

/// Enhanced vector operations with SIMD acceleration
pub struct EnhancedSimdProcessor {
    capability: SimdCapability,
}

impl EnhancedSimdProcessor {
    pub fn new() -> Self {
        Self {
            capability: detect_simd_capability(),
        }
    }
    
    /// Optimized dot product with SIMD acceleration
    #[inline(always)]
    pub fn dot_product(&self, a: &[f32], b: &[f32]) -> f32 {
        if a.len() != b.len() {
            panic!("Vectors must have equal length for dot product");
        }
        
        match self.capability {
            SimdCapability::Avx512 => unsafe { self.dot_product_avx512(a, b) },
            SimdCapability::Avx2 => unsafe { self.dot_product_avx2(a, b) },
            SimdCapability::Sse => unsafe { self.dot_product_sse(a, b) },
            SimdCapability::Scalar => self.dot_product_scalar(a, b),
        }
    }
    
    /// AVX-512 optimized dot product
    #[target_feature(enable = "avx512f,avx512vl")]
    unsafe fn dot_product_avx512(&self, a: &[f32], b: &[f32]) -> f32 {
        let len = a.len();
        let mut sum = _mm512_setzero_ps();
        let chunk_size = 16; // AVX-512 processes 16 floats at once
        
        let mut i = 0;
        while i + chunk_size <= len {
            let va = _mm512_loadu_ps(a.as_ptr().add(i));
            let vb = _mm512_loadu_ps(b.as_ptr().add(i));
            sum = _mm512_fmadd_ps(va, vb, sum);
            i += chunk_size;
        }
        
        // Reduce sum
        let mut result = _mm512_reduce_add_ps(sum);
        
        // Handle remaining elements
        while i < len {
            result += a[i] * b[i];
            i += 1;
        }
        
        result
    }
    
    /// AVX2 optimized dot product
    #[target_feature(enable = "avx2")]
    unsafe fn dot_product_avx2(&self, a: &[f32], b: &[f32]) -> f32 {
        let len = a.len();
        let mut sum0 = _mm256_setzero_ps();
        let mut sum1 = _mm256_setzero_ps();
        let chunk_size = 16; // Process 16 floats (2 AVX2 registers) per iteration
        
        let mut i = 0;
        while i + chunk_size <= len {
            let va0 = _mm256_loadu_ps(a.as_ptr().add(i));
            let vb0 = _mm256_loadu_ps(b.as_ptr().add(i));
            sum0 = _mm256_fmadd_ps(va0, vb0, sum0);
            
            let va1 = _mm256_loadu_ps(a.as_ptr().add(i + 8));
            let vb1 = _mm256_loadu_ps(b.as_ptr().add(i + 8));
            sum1 = _mm256_fmadd_ps(va1, vb1, sum1);
            
            i += chunk_size;
        }
        
        // Reduce sums
        let sum = _mm256_add_ps(sum0, sum1);
        let mut result = hsum256_ps(sum);
        
        // Handle remaining elements
        while i < len {
            result += a[i] * b[i];
            i += 1;
        }
        
        result
    }
    
    /// SSE optimized dot product
    #[target_feature(enable = "sse4.2")]
    unsafe fn dot_product_sse(&self, a: &[f32], b: &[f32]) -> f32 {
        let len = a.len();
        let mut sum0 = _mm_setzero_ps();
        let mut sum1 = _mm_setzero_ps();
        let mut sum2 = _mm_setzero_ps();
        let mut sum3 = _mm_setzero_ps();
        let chunk_size = 16; // Process 16 floats (4 SSE registers) per iteration
        
        let mut i = 0;
        while i + chunk_size <= len {
            let va0 = _mm_loadu_ps(a.as_ptr().add(i));
            let vb0 = _mm_loadu_ps(b.as_ptr().add(i));
            sum0 = _mm_add_ps(_mm_mul_ps(va0, vb0), sum0);
            
            let va1 = _mm_loadu_ps(a.as_ptr().add(i + 4));
            let vb1 = _mm_loadu_ps(b.as_ptr().add(i + 4));
            sum1 = _mm_add_ps(_mm_mul_ps(va1, vb1), sum1);
            
            let va2 = _mm_loadu_ps(a.as_ptr().add(i + 8));
            let vb2 = _mm_loadu_ps(b.as_ptr().add(i + 8));
            sum2 = _mm_add_ps(_mm_mul_ps(va2, vb2), sum2);
            
            let va3 = _mm_loadu_ps(a.as_ptr().add(i + 12));
            let vb3 = _mm_loadu_ps(b.as_ptr().add(i + 12));
            sum3 = _mm_add_ps(_mm_mul_ps(va3, vb3), sum3);
            
            i += chunk_size;
        }
        
        // Reduce sums
        let sum01 = _mm_add_ps(sum0, sum1);
        let sum23 = _mm_add_ps(sum2, sum3);
        let sum = _mm_add_ps(sum01, sum23);
        let mut result = hsum128_ps(sum);
        
        // Handle remaining elements
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
    
    /// Optimized vector addition with SIMD acceleration
    #[inline(always)]
    pub fn vector_add(&self, a: &mut [f32], b: &[f32]) {
        if a.len() != b.len() {
            panic!("Vectors must have equal length for addition");
        }
        
        match self.capability {
            SimdCapability::Avx512 => unsafe { self.vector_add_avx512(a, b) },
            SimdCapability::Avx2 => unsafe { self.vector_add_avx2(a, b) },
            SimdCapability::Sse => unsafe { self.vector_add_sse(a, b) },
            SimdCapability::Scalar => self.vector_add_scalar(a, b),
        }
    }
    
    /// AVX-512 optimized vector addition
    #[target_feature(enable = "avx512f,avx512vl")]
    unsafe fn vector_add_avx512(&self, a: &mut [f32], b: &[f32]) {
        let len = a.len();
        let chunk_size = 16;
        
        let mut i = 0;
        while i + chunk_size <= len {
            let va = _mm512_loadu_ps(a.as_ptr().add(i));
            let vb = _mm512_loadu_ps(b.as_ptr().add(i));
            let result = _mm512_add_ps(va, vb);
            _mm512_storeu_ps(a.as_mut_ptr().add(i), result);
            i += chunk_size;
        }
        
        // Handle remaining elements
        while i < len {
            a[i] += b[i];
            i += 1;
        }
    }
    
    /// AVX2 optimized vector addition
    #[target_feature(enable = "avx2")]
    unsafe fn vector_add_avx2(&self, a: &mut [f32], b: &[f32]) {
        let len = a.len();
        let chunk_size = 8;
        
        let mut i = 0;
        while i + chunk_size <= len {
            let va = _mm256_loadu_ps(a.as_ptr().add(i));
            let vb = _mm256_loadu_ps(b.as_ptr().add(i));
            let result = _mm256_add_ps(va, vb);
            _mm256_storeu_ps(a.as_mut_ptr().add(i), result);
            i += chunk_size;
        }
        
        // Handle remaining elements
        while i < len {
            a[i] += b[i];
            i += 1;
        }
    }
    
    /// SSE optimized vector addition
    #[target_feature(enable = "sse4.2")]
    unsafe fn vector_add_sse(&self, a: &mut [f32], b: &[f32]) {
        let len = a.len();
        let chunk_size = 4;
        
        let mut i = 0;
        while i + chunk_size <= len {
            let va = _mm_loadu_ps(a.as_ptr().add(i));
            let vb = _mm_loadu_ps(b.as_ptr().add(i));
            let result = _mm_add_ps(va, vb);
            _mm_storeu_ps(a.as_mut_ptr().add(i), result);
            i += chunk_size;
        }
        
        // Handle remaining elements
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
        hsum128_ps(sum128)
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
    chunk_size: usize,
}

impl BatchSimdProcessor {
    pub fn new(chunk_size: usize) -> Self {
        Self {
            simd: EnhancedSimdProcessor::new(),
            chunk_size,
        }
    }
    
    /// Process multiple vector operations in parallel
    pub fn process_vectors_parallel(&self, operations: &[VectorOperation]) -> Vec<f32> {
        operations.par_chunks(self.chunk_size)
            .flat_map(|chunk| {
                chunk.iter().map(|op| match op {
                    VectorOperation::DotProduct(a, b) => self.simd.dot_product(a, b),
                    VectorOperation::Magnitude(v) => {
                        let dot = self.simd.dot_product(v, v);
                        dot.sqrt()
                    }
                })
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
        let simd = EnhancedSimdProcessor::new();
        let a = vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0];
        let b = vec![8.0, 7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0];
        
        let result = simd.dot_product(&a, &b);
        let expected = 120.0; // 1*8 + 2*7 + 3*6 + 4*5 + 5*4 + 6*3 + 7*2 + 8*1
        
        assert!((result - expected).abs() < 1e-6);
    }
    
    #[test]
    fn test_vector_add() {
        let simd = EnhancedSimdProcessor::new();
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
        let simd = EnhancedSimdProcessor::new();
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
}