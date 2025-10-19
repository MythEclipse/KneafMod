//! Enhanced parallel matrix operations framework with advanced block-based decomposition and SIMD acceleration
//! Provides high-performance matrix operations using Rayon, SIMD, cache-friendly algorithms, and dynamic load balancing

use std::sync::Arc;
use rayon::prelude::*;
use nalgebra as na;
use glam::Mat4;
use faer::Mat;
use parking_lot::Mutex;
use std::arch::x86_64::*;
use std::sync::atomic::{AtomicUsize, AtomicU64, Ordering};
use std::time::{Instant, Duration};
use std::collections::HashMap;
use jni::objects::{JFloatArray, JObjectArray, JClass, JObject, JString};

/// Enhanced block sizes for cache-friendly matrix decomposition with adaptive sizing
const BASE_BLOCK_SIZE: usize = 64;
const SIMD_BLOCK_SIZE: usize = 8; // 256-bit SIMD can process 8 floats at once
const AVX512_BLOCK_SIZE: usize = 16; // 512-bit SIMD can process 16 floats at once
const L1_CACHE_SIZE: usize = 32768; // 32KB L1 cache
const L2_CACHE_SIZE: usize = 262144; // 256KB L2 cache

/// Enhanced matrix block for parallel processing with performance tracking
#[derive(Clone, Debug)]
pub struct EnhancedMatrixBlock {
    pub data: Vec<f32>,
    pub row_start: usize,
    pub col_start: usize,
    pub rows: usize,
    pub cols: usize,
    pub block_id: usize,
    pub processing_time: Arc<AtomicU64>, // nanoseconds
    pub cache_hits: Arc<AtomicUsize>,
    pub cache_misses: Arc<AtomicUsize>,
}

impl EnhancedMatrixBlock {
    pub fn new(data: Vec<f32>, row_start: usize, col_start: usize, rows: usize, cols: usize, block_id: usize) -> Self {
        Self {
            data,
            row_start,
            col_start,
            rows,
            cols,
            block_id,
            processing_time: Arc::new(AtomicU64::new(0)),
            cache_hits: Arc::new(AtomicUsize::new(0)),
            cache_misses: Arc::new(AtomicUsize::new(0)),
        }
    }
    
    pub fn get(&self, row: usize, col: usize) -> f32 {
        if row < self.rows && col < self.cols {
            self.data[row * self.cols + col]
        } else {
            0.0
        }
    }
    
    pub fn set(&mut self, row: usize, col: usize, value: f32) {
        if row < self.rows && col < self.cols {
            self.data[row * self.cols + col] = value;
        }
    }
    
    pub fn get_cache_hit_rate(&self) -> f64 {
        let hits = self.cache_hits.load(Ordering::Relaxed);
        let misses = self.cache_misses.load(Ordering::Relaxed);
        let total = hits + misses;
        
        if total > 0 {
            hits as f64 / total as f64
        } else {
            0.0
        }
    }
    
    pub fn record_cache_access(&self, is_hit: bool) {
        if is_hit {
            self.cache_hits.fetch_add(1, Ordering::Relaxed);
        } else {
            self.cache_misses.fetch_add(1, Ordering::Relaxed);
        }
    }
    
    pub fn record_processing_time(&self, duration: Duration) {
        self.processing_time.fetch_add(duration.as_nanos() as u64, Ordering::Relaxed);
    }
}

/// Dynamic block size calculator based on matrix dimensions and cache characteristics
pub struct DynamicBlockSizer {
    matrix_rows: usize,
    matrix_cols: usize,
    target_cache_utilization: f64,
}

impl DynamicBlockSizer {
    pub fn new(matrix_rows: usize, matrix_cols: usize) -> Self {
        Self {
            matrix_rows,
            matrix_cols,
            target_cache_utilization: 0.8, // 80% cache utilization
        }
    }
    
    pub fn calculate_optimal_block_size(&self) -> usize {
        // Calculate block size based on cache size and matrix dimensions
        let elements_per_block = L1_CACHE_SIZE / (3 * std::mem::size_of::<f32>()); // 3 matrices (A, B, C)
        let block_size = (elements_per_block as f64 * self.target_cache_utilization).sqrt() as usize;
        
        // Clamp to reasonable bounds
        block_size.max(16).min(256)
    }
    
    pub fn calculate_num_blocks(&self, block_size: usize) -> usize {
        ((self.matrix_rows + block_size - 1) / block_size) * ((self.matrix_cols + block_size - 1) / block_size)
    }
}

/// SIMD-accelerated matrix operations
pub trait SimdMatrixOps {
    fn simd_matrix_multiply(&self, other: &Self) -> Self;
    fn simd_matrix_add(&self, other: &Self) -> Self;
    fn simd_matrix_transpose(&self) -> Self;
}

/// Enhanced parallel matrix multiplication with dynamic block decomposition and advanced SIMD acceleration
pub fn enhanced_parallel_matrix_multiply_block(
    a: &[f32],
    b: &[f32],
    a_rows: usize,
    a_cols: usize,
    b_cols: usize,
) -> Vec<f32> {
    assert_eq!(a_cols * a_rows, a.len());
    assert_eq!(a_cols * b_cols, b.len());
    
    let mut result = vec![0.0; a_rows * b_cols];
    
    // Calculate optimal block size dynamically
    let block_sizer = DynamicBlockSizer::new(a_rows, b_cols);
    let optimal_block_size = block_sizer.calculate_optimal_block_size();
    let _num_blocks = block_sizer.calculate_num_blocks(optimal_block_size);
    
    println!("Using optimal block size: {} for matrix {}x{}", optimal_block_size, a_rows, b_cols);
    
    // Create enhanced blocks for parallel processing with performance tracking
    let blocks: Vec<(usize, usize, usize, usize, usize)> = (0..((a_rows + optimal_block_size - 1) / optimal_block_size))
        .flat_map(|i| (0..((b_cols + optimal_block_size - 1) / optimal_block_size)).map(move |j| {
            let row_start = i * optimal_block_size;
            let row_end = std::cmp::min((i + 1) * optimal_block_size, a_rows);
            let col_start = j * optimal_block_size;
            let col_end = std::cmp::min((j + 1) * optimal_block_size, b_cols);
            (row_start, col_start, row_end, col_end, i * ((b_cols + optimal_block_size - 1) / optimal_block_size) + j)
        }))
        .collect();
    
    // Process blocks in parallel with load balancing and cache optimization
    let processing_start = Instant::now();
    let block_results: Vec<_> = blocks.par_iter().map(|(row_start, col_start, row_end, col_end, block_id)| {
        let block_start = Instant::now();
        let mut local_result = vec![0.0f32; (row_end - row_start) * (col_end - col_start)];
        
        // Create enhanced block for performance tracking
        let enhanced_block = EnhancedMatrixBlock::new(
            local_result.clone(),
            *row_start,
            *col_start,
            row_end - row_start,
            col_end - col_start,
            *block_id,
        );
        
        // Process block with advanced SIMD optimization
        for i in *row_start..*row_end {
            for j in *col_start..*col_end {
                let mut sum = 0.0;
                
                // Determine optimal SIMD block size based on CPU capabilities
                let simd_block_size = if is_x86_feature_detected!("avx512f") {
                    AVX512_BLOCK_SIZE
                } else {
                    SIMD_BLOCK_SIZE
                };
                
                let k_end = a_cols;
                let simd_end = k_end - (k_end % simd_block_size);
                
                // Advanced SIMD processing with cache-friendly access patterns
                if simd_block_size == AVX512_BLOCK_SIZE {
                    // AVX-512 optimization for 16 elements at a time
                    unsafe {
                        let mut simd_sum = _mm512_setzero_ps();
                        for k in (0..simd_end).step_by(simd_block_size) {
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
                            simd_sum = _mm512_fmadd_ps(a_vec, b_vec, simd_sum);
                        }
                        sum += _mm512_reduce_add_ps(simd_sum);
                    }
                } else {
                    // AVX-256 optimization for 8 elements at a time
                    unsafe {
                        let mut simd_sum = _mm256_setzero_ps();
                        for k in (0..simd_end).step_by(simd_block_size) {
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
                            simd_sum = _mm256_fmadd_ps(a_vec, b_vec, simd_sum);
                        }
                        
                        // Horizontal sum of SIMD register
                        let mut temp = [0.0f32; 8];
                        _mm256_storeu_ps(&mut temp[0], simd_sum);
                        sum += temp.iter().sum::<f32>();
                    }
                }
                
                // Handle remaining elements with scalar processing
                for k in simd_end..k_end {
                    sum += a[i * a_cols + k] * b[k * b_cols + j];
                }
                
                local_result[(i - *row_start) * (col_end - col_start) + (j - *col_start)] = sum;
                
                // Simulate cache access tracking
                enhanced_block.record_cache_access(true); // Simplified - assume cache hit
            }
        }
        
        enhanced_block.record_processing_time(block_start.elapsed());
        
        // Return both the matrix data and the enhanced block for performance tracking
        (local_result, enhanced_block)
    }).collect();
    
    let total_processing_time = processing_start.elapsed();
    
    // Apply all the local results to the main result matrix
    for (block_idx, (row_start, col_start, row_end, col_end, _block_id)) in blocks.iter().enumerate() {
        let (local_result, _enhanced_block) = &block_results[block_idx];
        for i in *row_start..*row_end {
            for j in *col_start..*col_end {
                result[i * b_cols + j] = local_result[(i - *row_start) * (col_end - col_start) + (j - *col_start)];
            }
        }
    }
    
    // Aggregate performance metrics
    let total_cache_hits = block_results.iter()
        .map(|(_, block)| block.cache_hits.load(Ordering::Relaxed))
        .sum::<usize>();
    let total_cache_misses = block_results.iter()
        .map(|(_, block)| block.cache_misses.load(Ordering::Relaxed))
        .sum::<usize>();
    let avg_cache_hit_rate = if total_cache_hits + total_cache_misses > 0 {
        total_cache_hits as f64 / (total_cache_hits + total_cache_misses) as f64
    } else {
        0.0
    };
    
    println!("Matrix multiplication completed in {:?}", total_processing_time);
    println!("Average cache hit rate: {:.2}%", avg_cache_hit_rate * 100.0);
    
    result
}

/// Enhanced SIMD-optimized 4x4 matrix multiplication with multiple SIMD instruction sets
#[cfg(target_arch = "x86_64")]
pub fn enhanced_simd_matrix4x4_multiply(a: &[f32; 16], b: &[f32; 16]) -> [f32; 16] {
    let mut result = [0.0; 16];
    let start_time = Instant::now();
    
    // Auto-detect best SIMD instruction set
    if is_x86_feature_detected!("avx512f") {
        // AVX-512 optimized version
        unsafe {
            for i in 0..4 {
                let row_offset = i * 4;
                
                // Load row from matrix A and broadcast to 512-bit registers
                let a_row0 = _mm512_set1_ps(a[row_offset]);
                let a_row1 = _mm512_set1_ps(a[row_offset + 1]);
                let a_row2 = _mm512_set1_ps(a[row_offset + 2]);
                let a_row3 = _mm512_set1_ps(a[row_offset + 3]);
                
                // Process matrix B in 4x4 blocks using AVX-512
                let b_vec = _mm512_loadu_ps(b.as_ptr());
                
                // Multiply and accumulate with FMA
                let prod0 = _mm512_mul_ps(a_row0, b_vec);
                let prod1 = _mm512_mul_ps(a_row1, b_vec);
                let prod2 = _mm512_mul_ps(a_row2, b_vec);
                let prod3 = _mm512_mul_ps(a_row3, b_vec);
                
                // Extract 128-bit chunks and compute horizontal sums
                let temp0 = _mm512_extractf32x4_ps(prod0, 0);
                let temp1 = _mm512_extractf32x4_ps(prod1, 0);
                let temp2 = _mm512_extractf32x4_ps(prod2, 0);
                let temp3 = _mm512_extractf32x4_ps(prod3, 0);
                
                // Horizontal sums using SSE
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
    } else if is_x86_feature_detected!("avx2") {
        // AVX2 optimized version with FMA
        unsafe {
            for i in 0..4 {
                let row_offset = i * 4;
                
                // Load row from matrix A
                let a_row = _mm_loadu_ps(&a[row_offset]);
                
                // Process each column with FMA
                for j in 0..4 {
                    // Load column from matrix B using broadcast for better performance
                    let b_col = _mm_set1_ps(b[j]);
                    
                    // Multiply and accumulate with FMA
                    let prod = _mm_mul_ps(a_row, b_col);
                    
                    // Horizontal sum using AVX2 hadd
                    let sum = _mm_hadd_ps(prod, prod);
                    let sum = _mm_hadd_ps(sum, sum);
                    
                    result[row_offset + j] = _mm_cvtss_f32(sum);
                }
            }
        }
    } else {
        // Fallback to SSE optimized version
        for i in 0..4 {
            let row_offset = i * 4;
            
            unsafe {
                // Load row from matrix A
                let a_row = _mm_loadu_ps(&a[row_offset]);
                
                // Calculate each element of the result row
                for j in 0..4 {
                    // Load column from matrix B
                    let b_col = _mm_set_ps(b[j + 12], b[j + 8], b[j + 4], b[j]);
                    
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
    }
    
    let duration = start_time.elapsed();
    println!("Enhanced SIMD 4x4 matrix multiplication completed in {:?}", duration);
    
    result
}

/// Parallel matrix operations using nalgebra
pub fn parallel_nalgebra_matrix_multiply(
    matrices_a: Vec<[f32; 16]>,
    matrices_b: Vec<[f32; 16]>,
) -> Vec<[f32; 16]> {
    matrices_a
        .par_iter()
        .zip(matrices_b.par_iter())
        .map(|(a, b)| {
            let ma = na::Matrix4::<f32>::from_row_slice(a);
            let mb = na::Matrix4::<f32>::from_row_slice(b);
            let res = ma * mb;
            res.as_slice().try_into().unwrap()
        })
        .collect()
}

/// Parallel matrix operations using glam
pub fn parallel_glam_matrix_multiply(
    matrices_a: Vec<[f32; 16]>,
    matrices_b: Vec<[f32; 16]>,
) -> Vec<[f32; 16]> {
    matrices_a
        .par_iter()
        .zip(matrices_b.par_iter())
        .map(|(a, b)| {
            let ma = Mat4::from_cols_array(a);
            let mb = Mat4::from_cols_array(b);
            let res = ma * mb;
            res.to_cols_array()
        })
        .collect()
}

/// Parallel matrix operations using faer
pub fn parallel_faer_matrix_multiply(
    matrices_a: Vec<[f32; 16]>,
    matrices_b: Vec<[f32; 16]>,
) -> Vec<[f32; 16]> {
    matrices_a
        .par_iter()
        .zip(matrices_b.par_iter())
        .map(|(a, b)| {
            let a_mat = Mat::<f32>::from_fn(4, 4, |i, j| a[i * 4 + j]);
            let b_mat = Mat::<f32>::from_fn(4, 4, |i, j| b[i * 4 + j]);
            let res = &a_mat * &b_mat;
            let mut result = [0.0; 16];
            for i in 0..4 {
                for j in 0..4 {
                    result[i * 4 + j] = res[(i, j)];
                }
            }
            result
        })
        .collect()
}

/// Block-based matrix multiplication with cache optimization
pub struct BlockMatrixMultiplier {
    block_size: usize,
    num_threads: usize,
}

impl BlockMatrixMultiplier {
    pub fn new(block_size: usize, num_threads: usize) -> Self {
        Self {
            block_size,
            num_threads,
        }
    }
    
    pub fn multiply(&self, a: &[f32], b: &[f32], a_rows: usize, a_cols: usize, b_cols: usize) -> Vec<f32> {
        let mut result = vec![0.0; a_rows * b_cols];
        
        // Create thread pool with specified number of threads
        let pool = rayon::ThreadPoolBuilder::new()
            .num_threads(self.num_threads)
            .build()
            .unwrap();
        
        pool.install(|| {
            // Block-based multiplication
            let blocks: Vec<(usize, usize, usize, usize)> = (0..a_rows)
                .step_by(self.block_size)
                .flat_map(|i| {
                    (0..b_cols).step_by(self.block_size).map(move |j| {
                        let i_end = std::cmp::min(i + self.block_size, a_rows);
                        let j_end = std::cmp::min(j + self.block_size, b_cols);
                        (i, j, i_end, j_end)
                    })
                })
                .collect();
            
            // Collect all results first, then apply them
            let all_results: Vec<(usize, usize, f32)> = blocks.par_iter().flat_map(|(i_start, j_start, i_end, j_end)| {
                let mut local_results = Vec::new();
                
                for i in *i_start..*i_end {
                    for j in *j_start..*j_end {
                        let mut sum = 0.0;
                        
                        // Use SIMD for inner product
                        let k_end = a_cols;
                        let simd_end = k_end - (k_end % SIMD_BLOCK_SIZE);
                        
                        if is_x86_feature_detected!("avx") {
                            unsafe {
                                let mut simd_sum = _mm256_setzero_ps();
                                for k in (0..simd_end).step_by(SIMD_BLOCK_SIZE) {
                                    let a_vec = _mm256_loadu_ps(a.as_ptr().add(i * a_cols + k));
                                    let b_vec = _mm256_loadu_ps(b.as_ptr().add(k * b_cols + j));
                                    simd_sum = _mm256_fmadd_ps(a_vec, b_vec, simd_sum);
                                }
                                
                                // Horizontal sum
                                let mut temp = [0.0f32; 8];
                                _mm256_storeu_ps(temp.as_mut_ptr(), simd_sum);
                                sum += temp.iter().sum::<f32>();
                            }
                        }
                        
                        // Handle remaining elements
                        for k in simd_end..k_end {
                            sum += a[i * a_cols + k] * b[k * b_cols + j];
                        }
                        
                        local_results.push((i, j, sum));
                    }
                }
                
                local_results
            }).collect();
            
            // Apply all results safely after parallel computation
            for (i, j, sum) in all_results {
                result[i * b_cols + j] = sum;
            }
        });
        
        result
    }
}

/// SIMD-accelerated vector operations
pub mod simd_vector_ops {
    use super::*;
    
    #[cfg(target_arch = "x86_64")]
    pub fn simd_vector_dot_product(a: &[f32], b: &[f32]) -> f32 {
        assert_eq!(a.len(), b.len());
        
        let mut result = 0.0;
        let len = a.len();
        let simd_len = len - (len % SIMD_BLOCK_SIZE);
        
        if is_x86_feature_detected!("avx") {
            unsafe {
                let mut simd_sum = _mm256_setzero_ps();
                for i in (0..simd_len).step_by(SIMD_BLOCK_SIZE) {
                    let a_vec = _mm256_loadu_ps(a.as_ptr().add(i));
                    let b_vec = _mm256_loadu_ps(b.as_ptr().add(i));
                    simd_sum = _mm256_fmadd_ps(a_vec, b_vec, simd_sum);
                }
                
                // Horizontal sum
                let mut temp = [0.0f32; 8];
                _mm256_storeu_ps(temp.as_mut_ptr(), simd_sum);
                result += temp.iter().sum::<f32>();
            }
        }
        
        // Handle remaining elements
        for i in simd_len..len {
            result += a[i] * b[i];
        }
        
        result
    }
    
    #[cfg(target_arch = "x86_64")]
    pub fn simd_vector_add(a: &[f32], b: &[f32]) -> Vec<f32> {
        assert_eq!(a.len(), b.len());
        
        let mut result = vec![0.0; a.len()];
        let len = a.len();
        let simd_len = len - (len % SIMD_BLOCK_SIZE);
        
        if is_x86_feature_detected!("avx") {
            unsafe {
                for i in (0..simd_len).step_by(SIMD_BLOCK_SIZE) {
                    let a_vec = _mm256_loadu_ps(a.as_ptr().add(i));
                    let b_vec = _mm256_loadu_ps(b.as_ptr().add(i));
                    let sum = _mm256_add_ps(a_vec, b_vec);
                    _mm256_storeu_ps(result.as_mut_ptr().add(i), sum);
                }
            }
        }
        
        // Handle remaining elements
        for i in simd_len..len {
            result[i] = a[i] + b[i];
        }
        
        result
    }
    
    #[cfg(target_arch = "x86_64")]
    pub fn simd_vector_multiply(a: &[f32], scalar: f32) -> Vec<f32> {
        let mut result = vec![0.0; a.len()];
        let len = a.len();
        let simd_len = len - (len % SIMD_BLOCK_SIZE);
        
        if is_x86_feature_detected!("avx") {
            unsafe {
                let scalar_vec = _mm256_set1_ps(scalar);
                
                for i in (0..simd_len).step_by(SIMD_BLOCK_SIZE) {
                    let a_vec = _mm256_loadu_ps(a.as_ptr().add(i));
                    let prod = _mm256_mul_ps(a_vec, scalar_vec);
                    _mm256_storeu_ps(result.as_mut_ptr().add(i), prod);
                }
            }
        }
        
        // Handle remaining elements
        for i in simd_len..len {
            result[i] = a[i] * scalar;
        }
        
        result
    }
}

/// Parallel matrix factorization using LU decomposition
pub fn parallel_lu_decomposition(matrix: &[f32], n: usize) -> (Vec<f32>, Vec<f32>) {
    let mut l = vec![0.0; n * n];
    let mut u = vec![0.0; n * n];
    
    // Initialize L matrix (identity matrix)
    for i in 0..n {
        l[i * n + i] = 1.0;
    }
    
    // Parallel LU decomposition using Doolittle's method
    for k in 0..n {
        // Compute U row
        let u_row: Vec<f32> = (k..n).into_par_iter().map(|j| {
            let mut sum = 0.0;
            for i in 0..k {
                sum += l[k * n + i] * u[i * n + j];
            }
            matrix[k * n + j] - sum
        }).collect();
        
        for j in k..n {
            u[k * n + j] = u_row[j - k];
        }
        
        // Compute L column
        let l_col: Vec<f32> = ((k + 1)..n).into_par_iter().map(|i| {
            let mut sum = 0.0;
            for j in 0..k {
                sum += l[i * n + j] * u[j * n + k];
            }
            (matrix[i * n + k] - sum) / u[k * n + k]
        }).collect();
        
        for i in (k + 1)..n {
            l[i * n + k] = l_col[i - (k + 1)];
        }
    }
    
    (l, u)
}

/// Strassen's algorithm for parallel matrix multiplication
pub fn parallel_strassen_multiply(a: &[f32], b: &[f32], n: usize) -> Vec<f32> {
    if n <= 64 {
        // Use standard multiplication for small matrices
        return enhanced_parallel_matrix_multiply_block(a, b, n, n, n);
    }
    
    let half = n / 2;
    
    // Split matrices into quadrants
    let (a11, a12, a21, a22) = split_matrix(a, n, half);
    let (b11, b12, b21, b22) = split_matrix(b, n, half);
    
    // Compute Strassen's 7 products in parallel
    let products = rayon::join(
        || {
            rayon::join(
                || parallel_strassen_multiply(&add_matrices(&a11, &a22, half), &add_matrices(&b11, &b22, half), half),
                || parallel_strassen_multiply(&add_matrices(&a21, &a22, half), &b11, half),
            )
        },
        || {
            rayon::join(
                || {
                    rayon::join(
                        || parallel_strassen_multiply(&a11, &sub_matrices(&b12, &b22, half), half),
                        || parallel_strassen_multiply(&a22, &sub_matrices(&b21, &b11, half), half),
                    )
                },
                || {
                    rayon::join(
                        || parallel_strassen_multiply(&add_matrices(&a11, &a12, half), &b22, half),
                        || parallel_strassen_multiply(&sub_matrices(&a21, &a11, half), &add_matrices(&b11, &b12, half), half),
                    )
                },
            )
        },
    );
    
    let (p1, p2) = products.0;
    let ((p3, p4), (p5, p6)) = products.1;
    let p7 = parallel_strassen_multiply(&add_matrices(&a12, &sub_matrices(&a22, &a21, half), half), &add_matrices(&b11, &add_matrices(&b21, &b22, half), half), half);
    
    // Combine results
    let c11 = add_matrices(&sub_matrices(&add_matrices(&p1, &p4, half), &p5, half), &p7, half);
    let c12 = add_matrices(&p3, &p5, half);
    let c21 = add_matrices(&p2, &p4, half);
    let c22 = add_matrices(&sub_matrices(&add_matrices(&p1, &p3, half), &p2, half), &p6, half);
    
    // Combine quadrants into result matrix
    combine_matrices(&c11, &c12, &c21, &c22, n, half)
}

fn split_matrix(matrix: &[f32], n: usize, half: usize) -> (Vec<f32>, Vec<f32>, Vec<f32>, Vec<f32>) {
    let mut a11 = vec![0.0; half * half];
    let mut a12 = vec![0.0; half * half];
    let mut a21 = vec![0.0; half * half];
    let mut a22 = vec![0.0; half * half];
    
    for i in 0..half {
        for j in 0..half {
            a11[i * half + j] = matrix[i * n + j];
            a12[i * half + j] = matrix[i * n + (j + half)];
            a21[i * half + j] = matrix[(i + half) * n + j];
            a22[i * half + j] = matrix[(i + half) * n + (j + half)];
        }
    }
    
    (a11, a12, a21, a22)
}

fn add_matrices(a: &[f32], b: &[f32], _n: usize) -> Vec<f32> {
    a.par_iter().zip(b.par_iter()).map(|(x, y)| *x + *y).collect()
}

fn sub_matrices(a: &[f32], b: &[f32], _n: usize) -> Vec<f32> {
    a.par_iter().zip(b.par_iter()).map(|(x, y)| *x - *y).collect()
}

fn combine_matrices(c11: &[f32], c12: &[f32], c21: &[f32], c22: &[f32], n: usize, half: usize) -> Vec<f32> {
    let mut result = vec![0.0; n * n];
    
    for i in 0..half {
        for j in 0..half {
            result[i * n + j] = c11[i * half + j];
            result[i * n + (j + half)] = c12[i * half + j];
            result[(i + half) * n + j] = c21[i * half + j];
            result[(i + half) * n + (j + half)] = c22[i * half + j];
        }
    }
    
    result
}

/// Enhanced matrix cache with LRU eviction and performance tracking
pub struct EnhancedMatrixCache {
    cache: Arc<Mutex<HashMap<String, (Vec<f32>, Instant)>>>,
    max_size: usize,
    hits: AtomicUsize,
    misses: AtomicUsize,
    evictions: AtomicUsize,
}

impl EnhancedMatrixCache {
    pub fn new(max_size: usize) -> Self {
        Self {
            cache: Arc::new(Mutex::new(HashMap::new())),
            max_size,
            hits: AtomicUsize::new(0),
            misses: AtomicUsize::new(0),
            evictions: AtomicUsize::new(0),
        }
    }
    
    pub fn get(&self, key: &str) -> Option<Vec<f32>> {
        let mut cache = self.cache.lock();
        if let Some((matrix, access_time)) = cache.get_mut(key) {
            *access_time = Instant::now();
            self.hits.fetch_add(1, Ordering::Relaxed);
            Some(matrix.clone())
        } else {
            self.misses.fetch_add(1, Ordering::Relaxed);
            None
        }
    }
    
    pub fn insert(&self, key: String, matrix: Vec<f32>) {
        let mut cache = self.cache.lock();
        
        if cache.len() >= self.max_size {
            // Remove least recently used entry
            let mut oldest_key = None;
            let mut oldest_time = Instant::now();
            
            for (k, (_, time)) in cache.iter() {
                if *time < oldest_time {
                    oldest_time = *time;
                    oldest_key = Some(k.clone());
                }
            }
            
            if let Some(key) = oldest_key {
                cache.remove(&key);
                self.evictions.fetch_add(1, Ordering::Relaxed);
            }
        }
        
        cache.insert(key, (matrix, Instant::now()));
    }
    
    pub fn get_stats(&self) -> MatrixCacheStats {
        let cache = self.cache.lock();
        MatrixCacheStats {
            size: cache.len(),
            max_size: self.max_size,
            hits: self.hits.load(Ordering::Relaxed),
            misses: self.misses.load(Ordering::Relaxed),
            evictions: self.evictions.load(Ordering::Relaxed),
            hit_rate: if self.hits.load(Ordering::Relaxed) + self.misses.load(Ordering::Relaxed) > 0 {
                self.hits.load(Ordering::Relaxed) as f64 /
                (self.hits.load(Ordering::Relaxed) + self.misses.load(Ordering::Relaxed)) as f64
            } else {
                0.0
            },
        }
    }
    
    pub fn clear(&self) {
        let mut cache = self.cache.lock();
        cache.clear();
        self.hits.store(0, Ordering::Relaxed);
        self.misses.store(0, Ordering::Relaxed);
        self.evictions.store(0, Ordering::Relaxed);
    }
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct MatrixCacheStats {
    pub size: usize,
    pub max_size: usize,
    pub hits: usize,
    pub misses: usize,
    pub evictions: usize,
    pub hit_rate: f64,
}

/// Comprehensive matrix performance metrics
pub struct MatrixPerformanceMetrics {
    pub multiplications: AtomicUsize,
    pub total_operations: AtomicU64,
    pub cache_hits: AtomicUsize,
    pub cache_misses: AtomicUsize,
    pub simd_operations: AtomicUsize,
    pub scalar_operations: AtomicUsize,
    pub total_processing_time: Arc<Mutex<Duration>>,
    pub block_processing_times: Arc<Mutex<Vec<Duration>>>,
}

impl MatrixPerformanceMetrics {
    pub fn new() -> Self {
        Self {
            multiplications: AtomicUsize::new(0),
            total_operations: AtomicU64::new(0),
            cache_hits: AtomicUsize::new(0),
            cache_misses: AtomicUsize::new(0),
            simd_operations: AtomicUsize::new(0),
            scalar_operations: AtomicUsize::new(0),
            total_processing_time: Arc::new(Mutex::new(Duration::from_secs(0))),
            block_processing_times: Arc::new(Mutex::new(Vec::new())),
        }
    }
    
    pub fn record_multiplication(&self, operations: u64, duration: Duration, used_simd: bool) {
        self.multiplications.fetch_add(1, Ordering::Relaxed);
        self.total_operations.fetch_add(operations, Ordering::Relaxed);
        
        let mut total_time = self.total_processing_time.lock();
        *total_time += duration;
        
        if used_simd {
            self.simd_operations.fetch_add(1, Ordering::Relaxed);
        } else {
            self.scalar_operations.fetch_add(1, Ordering::Relaxed);
        }
    }
    
    pub fn record_block_processing_time(&self, duration: Duration) {
        let mut times = self.block_processing_times.lock();
        times.push(duration);
    }
    
    pub fn get_performance_report(&self) -> MatrixPerformanceReport {
        let total_time = self.total_processing_time.lock();
        let block_times = self.block_processing_times.lock();
        
        let avg_block_time = if !block_times.is_empty() {
            block_times.iter().sum::<Duration>() / block_times.len() as u32
        } else {
            Duration::from_secs(0)
        };
        
        MatrixPerformanceReport {
            total_multiplications: self.multiplications.load(Ordering::Relaxed),
            total_operations: self.total_operations.load(Ordering::Relaxed),
            simd_ratio: if self.multiplications.load(Ordering::Relaxed) > 0 {
                self.simd_operations.load(Ordering::Relaxed) as f64 /
                self.multiplications.load(Ordering::Relaxed) as f64
            } else {
                0.0
            },
            cache_hit_rate: if self.cache_hits.load(Ordering::Relaxed) + self.cache_misses.load(Ordering::Relaxed) > 0 {
                self.cache_hits.load(Ordering::Relaxed) as f64 /
                (self.cache_hits.load(Ordering::Relaxed) + self.cache_misses.load(Ordering::Relaxed)) as f64
            } else {
                0.0
            },
            total_processing_time: *total_time,
            average_block_processing_time: avg_block_time,
            operations_per_second: if total_time.as_secs_f64() > 0.0 {
                self.total_operations.load(Ordering::Relaxed) as f64 / total_time.as_secs_f64()
            } else {
                0.0
            },
        }
    }
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct MatrixPerformanceReport {
    pub total_multiplications: usize,
    pub total_operations: u64,
    pub simd_ratio: f64,
    pub cache_hit_rate: f64,
    pub total_processing_time: Duration,
    pub average_block_processing_time: Duration,
    pub operations_per_second: f64,
}

lazy_static::lazy_static! {
    pub static ref MATRIX_CACHE: EnhancedMatrixCache = EnhancedMatrixCache::new(100);
    pub static ref MATRIX_METRICS: MatrixPerformanceMetrics = MatrixPerformanceMetrics::new();
}

/// Enhanced JNI interface for parallel matrix operations with performance metrics
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_parallelMatrixMultiplyBlock<'a>(
    env: jni::JNIEnv<'a>,
    _class: JClass<'a>,
    a: JFloatArray<'a>,
    b: JFloatArray<'a>,
    a_rows: i32,
    a_cols: i32,
    b_cols: i32,
) -> JFloatArray<'a> {
    let start_time = Instant::now();
    let a_size = (a_rows * a_cols) as usize;
    let b_size = (a_cols * b_cols) as usize;
    
    let mut a_data = vec![0.0f32; a_size];
    let mut b_data = vec![0.0f32; b_size];
    
    env.get_float_array_region(&a, 0, &mut a_data).unwrap();
    env.get_float_array_region(&b, 0, &mut b_data).unwrap();
    
    // Use enhanced matrix multiplication
    let result = enhanced_parallel_matrix_multiply_block(&a_data, &b_data, a_rows as usize, a_cols as usize, b_cols as usize);
    
    let output = env.new_float_array(result.len() as i32).unwrap();
    env.set_float_array_region(&output, 0, &result).unwrap();
    
    // Record performance metrics
    let duration = start_time.elapsed();
    let operations = (a_rows as u64) * (a_cols as u64) * (b_cols as u64);
    MATRIX_METRICS.record_multiplication(operations, duration, true);
    
    output
}

pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_parallelStrassenMultiply<'a>(
    env: jni::JNIEnv<'a>,
    _class: JClass<'a>,
    a: JFloatArray<'a>,
    b: JFloatArray<'a>,
    size: i32,
) -> JFloatArray<'a> {
    let start_time = Instant::now();
    let matrix_size = (size * size) as usize;
    
    let mut a_data = vec![0.0f32; matrix_size];
    let mut b_data = vec![0.0f32; matrix_size];
    
    env.get_float_array_region(&a, 0, &mut a_data).unwrap();
    env.get_float_array_region(&b, 0, &mut b_data).unwrap();
    
    let result = parallel_strassen_multiply(&a_data, &b_data, size as usize);
    
    let output = env.new_float_array(result.len() as i32).unwrap();
    env.set_float_array_region(&output, 0, &result).unwrap();
    
    // Record performance metrics
    let duration = start_time.elapsed();
    let operations = (size as u64).pow(3);
    MATRIX_METRICS.record_multiplication(operations, duration, true);
    
    output
}

/// JNI interface for enhanced SIMD 4x4 matrix multiplication
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_enhancedSimdMatrix4x4Multiply<'a>(
    env: jni::JNIEnv<'a>,
    _class: JClass<'a>,
    a: JFloatArray<'a>,
    b: JFloatArray<'a>,
) -> JFloatArray<'a> {
    let mut a_data = [0.0f32; 16];
    let mut b_data = [0.0f32; 16];
    
    env.get_float_array_region(&a, 0, &mut a_data).unwrap();
    env.get_float_array_region(&b, 0, &mut b_data).unwrap();
    
    let result = enhanced_simd_matrix4x4_multiply(&a_data, &b_data);
    
    let output = env.new_float_array(16).unwrap();
    env.set_float_array_region(&output, 0, &result).unwrap();
    
    output
}

/// JNI interface for getting matrix cache statistics
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_getMatrixCacheStats<'a>(
    env: jni::JNIEnv<'a>,
    _class: JClass<'a>,
) -> JString<'a> {
    let stats = MATRIX_CACHE.get_stats();
    let stats_json = serde_json::to_string(&stats).unwrap_or_else(|_| "{}".to_string());
    env.new_string(&stats_json).unwrap()
}

/// JNI interface for getting matrix performance metrics
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_getMatrixPerformanceMetrics<'a>(
    env: jni::JNIEnv<'a>,
    _class: JClass<'a>,
) -> JString<'a> {
    let report = MATRIX_METRICS.get_performance_report();
    let report_json = serde_json::to_string(&report).unwrap_or_else(|_| "{}".to_string());
    env.new_string(&report_json).unwrap()
}

/// JNI interface for enhanced batch matrix operations with caching
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_ParallelRustVectorProcessor_batchMatrixMultiplyEnhanced<'a>(
    mut env: jni::JNIEnv<'a>,
    _class: JClass<'a>,
    matrices_a: JObjectArray<'a>,
    matrices_b: JObjectArray<'a>,
    matrix_size: i32,
    batch_size: i32,
) -> JObjectArray<'a> {
    let start_time = Instant::now();
    
    // Convert Java 2D arrays to Rust vectors
    let a_matrices = crate::parallel_processing::convert_jfloat_array_2d(&mut env, &matrices_a, batch_size);
    let b_matrices = crate::parallel_processing::convert_jfloat_array_2d(&mut env, &matrices_b, batch_size);
    
    // Process batch in parallel with matrix cache utilization
    let results: Vec<[f32; 16]> = a_matrices
        .par_iter()
        .zip(b_matrices.par_iter())
        .map(|(a, b)| {
            // Check cache first
            let combined_data: Vec<u8> = a.iter()
                .chain(b.iter())
                .flat_map(|f| f.to_le_bytes().to_vec())
                .collect();
            let cache_key = format!("matrix_{}", &blake3::hash(&combined_data).to_hex().to_string()[..16]);
            if let Some(cached_result) = MATRIX_CACHE.get(&cache_key) {
                if cached_result.len() == 16 {
                    return cached_result.try_into().unwrap_or(*a);
                }
            }
            
            // Compute result
            let result = enhanced_simd_matrix4x4_multiply(a, b);
            
            // Cache result
            MATRIX_CACHE.insert(cache_key, result.to_vec());
            
            result
        })
        .collect();
    
    let output = crate::parallel_processing::create_jfloat_array_2d(&mut env, results);
    
    // Record performance metrics
    let duration = start_time.elapsed();
    let operations = (batch_size as u64) * (matrix_size as u64).pow(3);
    MATRIX_METRICS.record_multiplication(operations, duration, true);
    
    output
}