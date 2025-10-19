//! Runtime-detected SIMD optimizations for numerical computations
//! Provides automatic SIMD detection and optimization selection based on CPU capabilities

use std::arch::x86_64::*;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Once;

/// SIMD instruction set detection
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum SimdLevel {
    None,
    SSE2,
    SSE3,
    SSSE3,
    SSE41,
    SSE42,
    AVX,
    AVX2,
    AVX512F,
    AVX512BW,
    AVX512DQ,
}

/// Runtime SIMD capability detection
pub struct SimdDetector {
    detected_level: AtomicUsize,
    detection_done: Once,
}

impl SimdDetector {
    pub fn new() -> Self {
        Self {
            detected_level: AtomicUsize::new(0),
            detection_done: Once::new(),
        }
    }
    
    /// Detect available SIMD instructions
    pub fn detect_capabilities(&self) -> SimdLevel {
        self.detection_done.call_once(|| {
            let level = self.perform_detection();
            self.detected_level.store(level as usize, Ordering::Relaxed);
        });
        
        match self.detected_level.load(Ordering::Relaxed) {
            0 => SimdLevel::None,
            1 => SimdLevel::SSE2,
            2 => SimdLevel::SSE3,
            3 => SimdLevel::SSSE3,
            4 => SimdLevel::SSE41,
            5 => SimdLevel::SSE42,
            6 => SimdLevel::AVX,
            7 => SimdLevel::AVX2,
            8 => SimdLevel::AVX512F,
            9 => SimdLevel::AVX512BW,
            10 => SimdLevel::AVX512DQ,
            _ => SimdLevel::None,
        }
    }
    
    fn perform_detection(&self) -> usize {
        #[cfg(target_arch = "x86_64")]
        {
            // Check for AVX512 support
            if is_x86_feature_detected!("avx512f") && is_x86_feature_detected!("avx512bw") && is_x86_feature_detected!("avx512dq") {
                return 10;
            }
            if is_x86_feature_detected!("avx512f") {
                return 8;
            }
            
            // Check for AVX2 support
            if is_x86_feature_detected!("avx2") {
                return 7;
            }
            
            // Check for AVX support
            if is_x86_feature_detected!("avx") {
                return 6;
            }
            
            // Check for SSE4.2 support
            if is_x86_feature_detected!("sse4.2") {
                return 5;
            }
            
            // Check for SSE4.1 support
            if is_x86_feature_detected!("sse4.1") {
                return 4;
            }
            
            // Check for SSSE3 support
            if is_x86_feature_detected!("ssse3") {
                return 3;
            }
            
            // Check for SSE3 support
            if is_x86_feature_detected!("sse3") {
                return 2;
            }
            
            // Check for SSE2 support (minimum requirement)
            if is_x86_feature_detected!("sse2") {
                return 1;
            }
        }
        
        0 // No SIMD support
    }
}

lazy_static::lazy_static! {
    pub static ref SIMD_DETECTOR: SimdDetector = SimdDetector::new();
}

/// Runtime-selected SIMD matrix multiplication
pub fn runtime_matrix_multiply(a: &[f32], b: &[f32], a_rows: usize, a_cols: usize, b_cols: usize) -> Vec<f32> {
    let simd_level = SIMD_DETECTOR.detect_capabilities();
    
    match simd_level {
        SimdLevel::AVX512F | SimdLevel::AVX512BW | SimdLevel::AVX512DQ => {
            avx512_matrix_multiply(a, b, a_rows, a_cols, b_cols)
        }
        SimdLevel::AVX2 => {
            avx2_matrix_multiply(a, b, a_rows, a_cols, b_cols)
        }
        SimdLevel::AVX => {
            avx_matrix_multiply(a, b, a_rows, a_cols, b_cols)
        }
        SimdLevel::SSE41 | SimdLevel::SSE42 => {
            sse41_matrix_multiply(a, b, a_rows, a_cols, b_cols)
        }
        _ => {
            // Fallback to scalar implementation
            scalar_matrix_multiply(a, b, a_rows, a_cols, b_cols)
        }
    }
}

/// Scalar fallback implementation
fn scalar_matrix_multiply(a: &[f32], b: &[f32], a_rows: usize, a_cols: usize, b_cols: usize) -> Vec<f32> {
    let mut result = vec![0.0; a_rows * b_cols];
    
    for i in 0..a_rows {
        for j in 0..b_cols {
            let mut sum = 0.0;
            for k in 0..a_cols {
                sum += a[i * a_cols + k] * b[k * b_cols + j];
            }
            result[i * b_cols + j] = sum;
        }
    }
    
    result
}

/// AVX-optimized matrix multiplication (256-bit SIMD)
fn avx_matrix_multiply(a: &[f32], b: &[f32], a_rows: usize, a_cols: usize, b_cols: usize) -> Vec<f32> {
    let mut result = vec![0.0; a_rows * b_cols];
    
    unsafe {
        for i in 0..a_rows {
            for j in 0..b_cols {
                let mut sum = _mm256_setzero_ps();
                
                // Process 8 elements at a time
                let k_end = a_cols - (a_cols % 8);
                for k in (0..k_end).step_by(8) {
                    let a_vec = _mm256_loadu_ps(&a[i * a_cols + k]);
                    let b_vec = _mm256_set_ps(
                        b[(k + 7) * b_cols + j],
                        b[(k + 6) * b_cols + j],
                        b[(k + 5) * b_cols + j],
                        b[(k + 4) * b_cols + j],
                        b[(k + 3) * b_cols + j],
                        b[(k + 2) * b_cols + j],
                        b[(k + 1) * b_cols + j],
                        b[k * b_cols + j],
                    );
                    sum = _mm256_fmadd_ps(a_vec, b_vec, sum);
                }
                
                // Horizontal sum
                let mut temp = [0.0f32; 8];
                _mm256_storeu_ps(&mut temp[0], sum);
                let mut scalar_sum = temp.iter().sum::<f32>();
                
                // Handle remaining elements
                for k in k_end..a_cols {
                    scalar_sum += a[i * a_cols + k] * b[k * b_cols + j];
                }
                
                result[i * b_cols + j] = scalar_sum;
            }
        }
    }
    
    result
}

/// AVX2-optimized matrix multiplication with FMA
fn avx2_matrix_multiply(a: &[f32], b: &[f32], a_rows: usize, a_cols: usize, b_cols: usize) -> Vec<f32> {
    let mut result = vec![0.0; a_rows * b_cols];
    
    unsafe {
        for i in 0..a_rows {
            for j in 0..b_cols {
                let mut sum = _mm256_setzero_ps();
                
                // Process 8 elements at a time using FMA
                let k_end = a_cols - (a_cols % 8);
                for k in (0..k_end).step_by(8) {
                    let a_vec = _mm256_loadu_ps(&a[i * a_cols + k]);
                    let b_vec = _mm256_set_ps(
                        b[(k + 7) * b_cols + j],
                        b[(k + 6) * b_cols + j],
                        b[(k + 5) * b_cols + j],
                        b[(k + 4) * b_cols + j],
                        b[(k + 3) * b_cols + j],
                        b[(k + 2) * b_cols + j],
                        b[(k + 1) * b_cols + j],
                        b[k * b_cols + j],
                    );
                    sum = _mm256_fmadd_ps(a_vec, b_vec, sum);
                }
                
                // Horizontal sum
                let mut temp = [0.0f32; 8];
                _mm256_storeu_ps(&mut temp[0], sum);
                let mut scalar_sum = temp.iter().sum::<f32>();
                
                // Handle remaining elements
                for k in k_end..a_cols {
                    scalar_sum += a[i * a_cols + k] * b[k * b_cols + j];
                }
                
                result[i * b_cols + j] = scalar_sum;
            }
        }
    }
    
    result
}

/// AVX512-optimized matrix multiplication (512-bit SIMD)
#[cfg(target_arch = "x86_64")]
fn avx512_matrix_multiply(a: &[f32], b: &[f32], a_rows: usize, a_cols: usize, b_cols: usize) -> Vec<f32> {
    let mut result = vec![0.0; a_rows * b_cols];
    
    unsafe {
        for i in 0..a_rows {
            for j in 0..b_cols {
                let mut sum = _mm512_setzero_ps();
                
                // Process 16 elements at a time
                let k_end = a_cols - (a_cols % 16);
                for k in (0..k_end).step_by(16) {
                    let a_vec = _mm512_loadu_ps(&a[i * a_cols + k]);
                    let b_vec = _mm512_set_ps(
                        b[(k + 15) * b_cols + j],
                        b[(k + 14) * b_cols + j],
                        b[(k + 13) * b_cols + j],
                        b[(k + 12) * b_cols + j],
                        b[(k + 11) * b_cols + j],
                        b[(k + 10) * b_cols + j],
                        b[(k + 9) * b_cols + j],
                        b[(k + 8) * b_cols + j],
                        b[(k + 7) * b_cols + j],
                        b[(k + 6) * b_cols + j],
                        b[(k + 5) * b_cols + j],
                        b[(k + 4) * b_cols + j],
                        b[(k + 3) * b_cols + j],
                        b[(k + 2) * b_cols + j],
                        b[(k + 1) * b_cols + j],
                        b[k * b_cols + j],
                    );
                    sum = _mm512_fmadd_ps(a_vec, b_vec, sum);
                }
                
                // Horizontal sum
                result[i * b_cols + j] = _mm512_reduce_add_ps(sum);
                
                // Handle remaining elements
                for k in k_end..a_cols {
                    result[i * b_cols + j] += a[i * a_cols + k] * b[k * b_cols + j];
                }
            }
        }
    }
    
    result
}

/// SSE4.1 optimized matrix multiplication
fn sse41_matrix_multiply(a: &[f32], b: &[f32], a_rows: usize, a_cols: usize, b_cols: usize) -> Vec<f32> {
    let mut result = vec![0.0; a_rows * b_cols];
    
    unsafe {
        for i in 0..a_rows {
            for j in 0..b_cols {
                let mut sum = _mm_setzero_ps();
                
                // Process 4 elements at a time
                let k_end = a_cols - (a_cols % 4);
                for k in (0..k_end).step_by(4) {
                    let a_vec = _mm_loadu_ps(&a[i * a_cols + k]);
                    let b_vec = _mm_set_ps(
                        b[(k + 3) * b_cols + j],
                        b[(k + 2) * b_cols + j],
                        b[(k + 1) * b_cols + j],
                        b[k * b_cols + j],
                    );
                    sum = _mm_add_ps(_mm_mul_ps(a_vec, b_vec), sum);
                }
                
                // Horizontal sum
                let mut temp = [0.0f32; 4];
                _mm_storeu_ps(&mut temp[0], sum);
                let mut scalar_sum = temp.iter().sum::<f32>();
                
                // Handle remaining elements
                for k in k_end..a_cols {
                    scalar_sum += a[i * a_cols + k] * b[k * b_cols + j];
                }
                
                result[i * b_cols + j] = scalar_sum;
            }
        }
    }
    
    result
}

/// Runtime-selected SIMD vector operations
pub fn runtime_vector_dot_product(a: &[f32], b: &[f32]) -> f32 {
    let simd_level = SIMD_DETECTOR.detect_capabilities();
    
    match simd_level {
        SimdLevel::AVX512F | SimdLevel::AVX512BW | SimdLevel::AVX512DQ => {
            avx512_vector_dot_product(a, b)
        }
        SimdLevel::AVX2 | SimdLevel::AVX => {
            avx_vector_dot_product(a, b)
        }
        SimdLevel::SSE41 | SimdLevel::SSE42 => {
            sse41_vector_dot_product(a, b)
        }
        _ => {
            scalar_vector_dot_product(a, b)
        }
    }
}

/// Scalar vector dot product
fn scalar_vector_dot_product(a: &[f32], b: &[f32]) -> f32 {
    assert_eq!(a.len(), b.len());
    a.iter().zip(b.iter()).map(|(x, y)| *x * *y).sum()
}

/// AVX vector dot product
fn avx_vector_dot_product(a: &[f32], b: &[f32]) -> f32 {
    assert_eq!(a.len(), b.len());
    
    unsafe {
        let mut sum = _mm256_setzero_ps();
        let len = a.len();
        let simd_len = len - (len % 8);
        
        // Process 8 elements at a time
        for i in (0..simd_len).step_by(8) {
            let a_vec = _mm256_loadu_ps(&a[i]);
            let b_vec = _mm256_loadu_ps(&b[i]);
            sum = _mm256_fmadd_ps(a_vec, b_vec, sum);
        }
        
        // Horizontal sum
        let mut temp = [0.0f32; 8];
        _mm256_storeu_ps(&mut temp[0], sum);
        let mut result = temp.iter().sum::<f32>();
        
        // Handle remaining elements
        for i in simd_len..len {
            result += a[i] * b[i];
        }
        
        result
    }
}

/// AVX512 vector dot product
#[cfg(target_arch = "x86_64")]
fn avx512_vector_dot_product(a: &[f32], b: &[f32]) -> f32 {
    assert_eq!(a.len(), b.len());
    
    unsafe {
        let mut sum = _mm512_setzero_ps();
        let len = a.len();
        let simd_len = len - (len % 16);
        
        // Process 16 elements at a time
        for i in (0..simd_len).step_by(16) {
            let a_vec = _mm512_loadu_ps(&a[i]);
            let b_vec = _mm512_loadu_ps(&b[i]);
            sum = _mm512_fmadd_ps(a_vec, b_vec, sum);
        }
        
        // Horizontal sum
        let mut result = _mm512_reduce_add_ps(sum);
        
        // Handle remaining elements
        for i in simd_len..len {
            result += a[i] * b[i];
        }
        
        result
    }
}

/// SSE4.1 vector dot product
fn sse41_vector_dot_product(a: &[f32], b: &[f32]) -> f32 {
    assert_eq!(a.len(), b.len());
    
    unsafe {
        let mut sum = _mm_setzero_ps();
        let len = a.len();
        let simd_len = len - (len % 4);
        
        // Process 4 elements at a time
        for i in (0..simd_len).step_by(4) {
            let a_vec = _mm_loadu_ps(&a[i]);
            let b_vec = _mm_loadu_ps(&b[i]);
            sum = _mm_add_ps(_mm_mul_ps(a_vec, b_vec), sum);
        }
        
        // Horizontal sum
        let mut temp = [0.0f32; 4];
        _mm_storeu_ps(&mut temp[0], sum);
        let mut result = temp.iter().sum::<f32>();
        
        // Handle remaining elements
        for i in simd_len..len {
            result += a[i] * b[i];
        }
        
        result
    }
}

/// Runtime-selected SIMD vector addition
pub fn runtime_vector_add(a: &[f32], b: &[f32]) -> Vec<f32> {
    let simd_level = SIMD_DETECTOR.detect_capabilities();
    
    match simd_level {
        SimdLevel::AVX512F | SimdLevel::AVX512BW | SimdLevel::AVX512DQ => {
            avx512_vector_add(a, b)
        }
        SimdLevel::AVX2 | SimdLevel::AVX => {
            avx_vector_add(a, b)
        }
        SimdLevel::SSE41 | SimdLevel::SSE42 => {
            sse41_vector_add(a, b)
        }
        _ => {
            scalar_vector_add(a, b)
        }
    }
}

/// Scalar vector addition
fn scalar_vector_add(a: &[f32], b: &[f32]) -> Vec<f32> {
    assert_eq!(a.len(), b.len());
    a.iter().zip(b.iter()).map(|(x, y)| *x + *y).collect()
}

/// AVX vector addition
fn avx_vector_add(a: &[f32], b: &[f32]) -> Vec<f32> {
    assert_eq!(a.len(), b.len());
    
    let mut result = vec![0.0; a.len()];
    
    unsafe {
        let len = a.len();
        let simd_len = len - (len % 8);
        
        // Process 8 elements at a time
        for i in (0..simd_len).step_by(8) {
            let a_vec = _mm256_loadu_ps(&a[i]);
            let b_vec = _mm256_loadu_ps(&b[i]);
            let sum = _mm256_add_ps(a_vec, b_vec);
            _mm256_storeu_ps(&mut result[i], sum);
        }
        
        // Handle remaining elements
        for i in simd_len..len {
            result[i] = a[i] + b[i];
        }
    }
    
    result
}

/// AVX512 vector addition
#[cfg(target_arch = "x86_64")]
fn avx512_vector_add(a: &[f32], b: &[f32]) -> Vec<f32> {
    assert_eq!(a.len(), b.len());
    
    let mut result = vec![0.0; a.len()];
    
    unsafe {
        let len = a.len();
        let simd_len = len - (len % 16);
        
        // Process 16 elements at a time
        for i in (0..simd_len).step_by(16) {
            let a_vec = _mm512_loadu_ps(&a[i]);
            let b_vec = _mm512_loadu_ps(&b[i]);
            let sum = _mm512_add_ps(a_vec, b_vec);
            _mm512_storeu_ps(&mut result[i], sum);
        }
        
        // Handle remaining elements
        for i in simd_len..len {
            result[i] = a[i] + b[i];
        }
    }
    
    result
}

/// SSE4.1 vector addition
fn sse41_vector_add(a: &[f32], b: &[f32]) -> Vec<f32> {
    assert_eq!(a.len(), b.len());
    
    let mut result = vec![0.0; a.len()];
    
    unsafe {
        let len = a.len();
        let simd_len = len - (len % 4);
        
        // Process 4 elements at a time
        for i in (0..simd_len).step_by(4) {
            let a_vec = _mm_loadu_ps(&a[i]);
            let b_vec = _mm_loadu_ps(&b[i]);
            let sum = _mm_add_ps(a_vec, b_vec);
            _mm_storeu_ps(&mut result[i], sum);
        }
        
        // Handle remaining elements
        for i in simd_len..len {
            result[i] = a[i] + b[i];
        }
    }
    
    result
}

/// Runtime-selected SIMD 4x4 matrix operations
pub fn runtime_matrix4x4_multiply(a: &[f32; 16], b: &[f32; 16]) -> [f32; 16] {
    let simd_level = SIMD_DETECTOR.detect_capabilities();
    
    match simd_level {
        SimdLevel::AVX512F | SimdLevel::AVX512BW | SimdLevel::AVX512DQ => {
            avx512_matrix4x4_multiply(a, b)
        }
        SimdLevel::AVX2 | SimdLevel::AVX => {
            avx_matrix4x4_multiply(a, b)
        }
        SimdLevel::SSE41 | SimdLevel::SSE42 => {
            sse41_matrix4x4_multiply(a, b)
        }
        _ => {
            scalar_matrix4x4_multiply(a, b)
        }
    }
}

/// Scalar 4x4 matrix multiplication
fn scalar_matrix4x4_multiply(a: &[f32; 16], b: &[f32; 16]) -> [f32; 16] {
    let mut result = [0.0; 16];
    
    for i in 0..4 {
        for j in 0..4 {
            let mut sum = 0.0;
            for k in 0..4 {
                sum += a[i * 4 + k] * b[k * 4 + j];
            }
            result[i * 4 + j] = sum;
        }
    }
    
    result
}

/// AVX 4x4 matrix multiplication
fn avx_matrix4x4_multiply(a: &[f32; 16], b: &[f32; 16]) -> [f32; 16] {
    let mut result = [0.0; 16];
    
    unsafe {
        for i in 0..4 {
            let row_offset = i * 4;
            
            // Load row from matrix A
            let a_row = _mm_loadu_ps(&a[row_offset]);
            
            // Calculate each element of the result row
            for j in 0..4 {
                // Load column from matrix B using broadcast
                let b_col = _mm_set1_ps(b[j]);
                
                // Multiply and accumulate
                let prod = _mm_mul_ps(a_row, b_col);
                
                // Horizontal sum
                let sum = _mm_hadd_ps(prod, prod);
                let sum = _mm_hadd_ps(sum, sum);
                
                // Store result
                result[row_offset + j] = _mm_cvtss_f32(sum);
            }
        }
    }
    
    result
}

/// AVX512 4x4 matrix multiplication
#[cfg(target_arch = "x86_64")]
fn avx512_matrix4x4_multiply(a: &[f32; 16], b: &[f32; 16]) -> [f32; 16] {
    let mut result = [0.0; 16];
    
    unsafe {
        for i in 0..4 {
            let row_offset = i * 4;
            
            // Load row from matrix A and broadcast
            let a_row0 = _mm512_set1_ps(a[row_offset]);
            let a_row1 = _mm512_set1_ps(a[row_offset + 1]);
            let a_row2 = _mm512_set1_ps(a[row_offset + 2]);
            let a_row3 = _mm512_set1_ps(a[row_offset + 3]);
            
            // Process matrix B in 4x4 blocks
            let b_vec = _mm512_loadu_ps(b.as_ptr());
            
            // Multiply and accumulate
            let prod0 = _mm512_mul_ps(a_row0, b_vec);
            let prod1 = _mm512_mul_ps(a_row1, b_vec);
            let prod2 = _mm512_mul_ps(a_row2, b_vec);
            let prod3 = _mm512_mul_ps(a_row3, b_vec);
            
            // Extract results
            let temp0 = _mm512_extractf32x4_ps(prod0, 0);
            let temp1 = _mm512_extractf32x4_ps(prod1, 0);
            let temp2 = _mm512_extractf32x4_ps(prod2, 0);
            let temp3 = _mm512_extractf32x4_ps(prod3, 0);
            
            // Horizontal sums
            let sum0 = _mm_hadd_ps(temp0, temp0);
            let sum0 = _mm_hadd_ps(sum0, sum0);
            let sum1 = _mm_hadd_ps(temp1, temp1);
            let sum1 = _mm_hadd_ps(sum1, sum1);
            let sum2 = _mm_hadd_ps(temp2, temp2);
            let sum2 = _mm_hadd_ps(sum2, sum2);
            let sum3 = _mm_hadd_ps(temp3, temp3);
            let sum3 = _mm_hadd_ps(sum3, sum3);
            
            result[row_offset] = _mm_cvtss_f32(sum0);
            result[row_offset + 1] = _mm_cvtss_f32(sum1);
            result[row_offset + 2] = _mm_cvtss_f32(sum2);
            result[row_offset + 3] = _mm_cvtss_f32(sum3);
        }
    }
    
    result
}

/// SSE4.1 4x4 matrix multiplication
fn sse41_matrix4x4_multiply(a: &[f32; 16], b: &[f32; 16]) -> [f32; 16] {
    let mut result = [0.0; 16];
    
    unsafe {
        for i in 0..4 {
            let row_offset = i * 4;
            
            // Load row from matrix A
            let a_row = _mm_loadu_ps(&a[row_offset]);
            
            // Calculate each element of the result row
            for j in 0..4 {
                // Load column from matrix B using broadcast
                let b_col = _mm_set1_ps(b[j]);
                
                // Multiply and accumulate
                let prod = _mm_mul_ps(a_row, b_col);
                
                // Horizontal sum
                let sum = _mm_hadd_ps(prod, prod);
                let sum = _mm_hadd_ps(sum, sum);
                
                // Store result
                result[row_offset + j] = _mm_cvtss_f32(sum);
            }
        }
    }
    
    result
}

/// SIMD optimization statistics
pub struct SimdStats {
    pub avx512_operations: AtomicUsize,
    pub avx2_operations: AtomicUsize,
    pub avx_operations: AtomicUsize,
    pub sse_operations: AtomicUsize,
    pub scalar_operations: AtomicUsize,
    pub total_operations: AtomicUsize,
}

impl SimdStats {
    pub fn new() -> Self {
        Self {
            avx512_operations: AtomicUsize::new(0),
            avx2_operations: AtomicUsize::new(0),
            avx_operations: AtomicUsize::new(0),
            sse_operations: AtomicUsize::new(0),
            scalar_operations: AtomicUsize::new(0),
            total_operations: AtomicUsize::new(0),
        }
    }
    
    pub fn record_operation(&self, simd_level: SimdLevel) {
        self.total_operations.fetch_add(1, Ordering::Relaxed);
        
        match simd_level {
            SimdLevel::AVX512F | SimdLevel::AVX512BW | SimdLevel::AVX512DQ => {
                self.avx512_operations.fetch_add(1, Ordering::Relaxed);
            }
            SimdLevel::AVX2 => {
                self.avx2_operations.fetch_add(1, Ordering::Relaxed);
            }
            SimdLevel::AVX => {
                self.avx_operations.fetch_add(1, Ordering::Relaxed);
            }
            SimdLevel::SSE41 | SimdLevel::SSE42 | SimdLevel::SSSE3 | SimdLevel::SSE3 | SimdLevel::SSE2 => {
                self.sse_operations.fetch_add(1, Ordering::Relaxed);
            }
            _ => {
                self.scalar_operations.fetch_add(1, Ordering::Relaxed);
            }
        }
    }
    
    pub fn get_summary(&self) -> String {
        format!(
            "SimdStats{{total:{}, avx512:{}, avx2:{}, avx:{}, sse:{}, scalar:{}}}",
            self.total_operations.load(Ordering::Relaxed),
            self.avx512_operations.load(Ordering::Relaxed),
            self.avx2_operations.load(Ordering::Relaxed),
            self.avx_operations.load(Ordering::Relaxed),
            self.sse_operations.load(Ordering::Relaxed),
            self.scalar_operations.load(Ordering::Relaxed)
        )
    }
}

lazy_static::lazy_static! {
    pub static ref SIMD_STATS: SimdStats = SimdStats::new();
}

/// JNI interface for runtime SIMD operations
pub fn Java_com_kneaf_core_ParallelRustVectorProcessor_runtimeMatrixMultiply<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    a: jni::objects::JFloatArray<'a>,
    b: jni::objects::JFloatArray<'a>,
    a_rows: i32,
    a_cols: i32,
    b_cols: i32,
) -> jni::objects::JFloatArray<'a> {
    let a_size = (a_rows * a_cols) as usize;
    let b_size = (a_cols * b_cols) as usize;
    
    let mut a_data = vec![0.0f32; a_size];
    let mut b_data = vec![0.0f32; b_size];
    
    env.get_float_array_region(&a, 0, &mut a_data).unwrap();
    env.get_float_array_region(&b, 0, &mut b_data).unwrap();
    
    let result = runtime_matrix_multiply(&a_data, &b_data, a_rows as usize, a_cols as usize, b_cols as usize);
    
    let output = env.new_float_array(result.len() as i32).unwrap();
    env.set_float_array_region(&output, 0, &result).unwrap();
    
    output
}

pub fn Java_com_kneaf_core_ParallelRustVectorProcessor_runtimeVectorDotProduct(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
    a: jni::objects::JFloatArray,
    b: jni::objects::JFloatArray,
) -> f32 {
    let len = env.get_array_length(&a).unwrap() as usize;
    
    let mut a_data = vec![0.0f32; len];
    let mut b_data = vec![0.0f32; len];
    
    env.get_float_array_region(&a, 0, &mut a_data).unwrap();
    env.get_float_array_region(&b, 0, &mut b_data).unwrap();
    
    runtime_vector_dot_product(&a_data, &b_data)
}

pub fn Java_com_kneaf_core_ParallelRustVectorProcessor_runtimeVectorAdd<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    a: jni::objects::JFloatArray<'a>,
    b: jni::objects::JFloatArray<'a>,
) -> jni::objects::JFloatArray<'a> {
    let len = env.get_array_length(&a).unwrap() as usize;
    
    let mut a_data = vec![0.0f32; len];
    let mut b_data = vec![0.0f32; len];
    
    env.get_float_array_region(&a, 0, &mut a_data).unwrap();
    env.get_float_array_region(&b, 0, &mut b_data).unwrap();
    
    let result = runtime_vector_add(&a_data, &b_data);
    
    let output = env.new_float_array(result.len() as i32).unwrap();
    env.set_float_array_region(&output, 0, &result).unwrap();
    
    output
}

pub fn Java_com_kneaf_core_ParallelRustVectorProcessor_runtimeMatrix4x4Multiply<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
    a: jni::objects::JFloatArray<'a>,
    b: jni::objects::JFloatArray<'a>,
) -> jni::objects::JFloatArray<'a> {
    let mut a_data = [0.0f32; 16];
    let mut b_data = [0.0f32; 16];
    
    env.get_float_array_region(&a, 0, &mut a_data).unwrap();
    env.get_float_array_region(&b, 0, &mut b_data).unwrap();
    
    let result = runtime_matrix4x4_multiply(&a_data, &b_data);
    
    let output = env.new_float_array(16).unwrap();
    env.set_float_array_region(&output, 0, &result).unwrap();
    
    output
}

pub fn Java_com_kneaf_core_ParallelRustVectorProcessor_getSimdCapabilities<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
) -> jni::objects::JString<'a> {
    let simd_level = SIMD_DETECTOR.detect_capabilities();
    let level_str = match simd_level {
        SimdLevel::None => "None",
        SimdLevel::SSE2 => "SSE2",
        SimdLevel::SSE3 => "SSE3",
        SimdLevel::SSSE3 => "SSSE3",
        SimdLevel::SSE41 => "SSE4.1",
        SimdLevel::SSE42 => "SSE4.2",
        SimdLevel::AVX => "AVX",
        SimdLevel::AVX2 => "AVX2",
        SimdLevel::AVX512F => "AVX512F",
        SimdLevel::AVX512BW => "AVX512BW",
        SimdLevel::AVX512DQ => "AVX512DQ",
    };
    
    env.new_string(level_str).unwrap()
}

pub fn Java_com_kneaf_core_ParallelRustVectorProcessor_getSimdStats<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
) -> jni::objects::JString<'a> {
    let stats = SIMD_STATS.get_summary();
    env.new_string(&stats).unwrap()
}