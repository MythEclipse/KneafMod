//! Arena-based memory management for hot loops with zero-copy operations
//! Provides high-performance memory allocation with minimal overhead and cache-friendly access patterns

use parking_lot::Mutex as ParkingMutex;
use std::arch::x86_64::*;
use std::collections::VecDeque;
use std::mem;
use std::ptr::NonNull;
use std::slice;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

/// Memory arena for fast allocation in hot loops
#[allow(dead_code)]
pub struct MemoryArena {
    chunks: Arc<Mutex<Vec<Vec<u8>>>>,
    current_chunk: AtomicUsize,
    current_offset: AtomicUsize,
    chunk_size: usize,
    alignment: usize,
    /// Memory pool for pre-allocated chunks
    chunk_pool: Arc<Mutex<VecDeque<Vec<u8>>>>,
    /// Memory compaction threshold
    compaction_threshold: usize,
    /// Memory-mapped file support
    mmap_file: Option<memmap2::Mmap>,
    mmap_path: Option<String>,
}

#[allow(dead_code)]
impl MemoryArena {
    pub fn new(chunk_size: usize, alignment: usize) -> Self {
        let mut arena = Self {
            chunks: Arc::new(Mutex::new(Vec::new())),
            current_chunk: AtomicUsize::new(0),
            current_offset: AtomicUsize::new(0),
            chunk_size,
            alignment,
            chunk_pool: Arc::new(Mutex::new(VecDeque::new())),
            compaction_threshold: chunk_size / 4, // 25% fragmentation threshold
            mmap_file: None,
            mmap_path: None,
        };

        // Pre-allocate chunks for better performance
        arena.preallocate_chunks(4);
        arena
    }

    /// Pre-allocate memory chunks to reduce allocation overhead
    fn preallocate_chunks(&mut self, count: usize) {
        let mut pool = self.chunk_pool.lock().unwrap();
        for _ in 0..count {
            pool.push_back(vec![0u8; self.chunk_size]);
        }
    }

    /// Get a chunk from the pool or allocate new one
    fn get_chunk_from_pool(&self) -> Vec<u8> {
        let mut pool = self.chunk_pool.lock().unwrap();
        if let Some(chunk) = pool.pop_front() {
            chunk
        } else {
            vec![0u8; self.chunk_size]
        }
    }

    /// Return chunk to pool for reuse
    fn return_chunk_to_pool(&self, chunk: Vec<u8>) {
        let mut pool = self.chunk_pool.lock().unwrap();
        if pool.len() < 10 {
            // Limit pool size
            pool.push_back(chunk);
        }
    }

    /// Perform memory compaction to reduce fragmentation
    pub fn compact_memory(&mut self) -> usize {
        let mut chunks = self.chunks.lock().unwrap();
        let mut freed_chunks = 0;

        // Find fragmented chunks (less than 25% used)
        let mut fragmented_indices: Vec<usize> = Vec::new();
        for (i, chunk) in chunks.iter().enumerate() {
            if chunk.len() < self.compaction_threshold {
                fragmented_indices.push(i);
            }
        }

        // Compact fragmented chunks
        if fragmented_indices.len() > 1 {
            // Create new consolidated chunk
            let mut consolidated = Vec::with_capacity(self.chunk_size);

            for &index in fragmented_indices.iter().rev() {
                let chunk = chunks.remove(index);
                consolidated.extend_from_slice(&chunk);
                freed_chunks += 1;
            }

            // Add consolidated chunk back
            chunks.push(consolidated);
        }

        freed_chunks
    }

    /// Enable memory-mapped file support
    pub fn enable_memory_mapped_file(&mut self, path: String, size: usize) -> std::io::Result<()> {
        use std::fs::File;
        use std::io::Write;

        // Create file if it doesn't exist
        let mut file = File::create(&path)?;
        file.write_all(&vec![0u8; size])?;

        // Memory map the file
        let mmap = unsafe { memmap2::Mmap::map(&file)? };
        self.mmap_file = Some(mmap);
        self.mmap_path = Some(path);

        Ok(())
    }

    /// Allocate memory from the arena with memory pooling
    pub fn allocate<T>(&mut self, count: usize) -> NonNull<T> {
        let size = count * mem::size_of::<T>();
        let aligned_size = (size + self.alignment - 1) & !(self.alignment - 1);

        let mut chunks = self.chunks.lock().unwrap();

        // Ensure we have at least one chunk
        if chunks.is_empty() {
            let new_chunk = self.get_chunk_from_pool();
            chunks.push(new_chunk);
            self.current_chunk.store(0, Ordering::Release);
            self.current_offset.store(0, Ordering::Relaxed);
        }

        let mut chunk_idx = self.current_chunk.load(Ordering::Relaxed);
        let offset = self
            .current_offset
            .fetch_add(aligned_size, Ordering::Relaxed);

        if offset + aligned_size > self.chunk_size {
            // Need new chunk - use pooled allocation
            let new_chunk = self.get_chunk_from_pool();
            chunks.push(new_chunk);
            let new_chunk_idx = chunks.len() - 1;
            self.current_chunk.store(new_chunk_idx, Ordering::Release);
            self.current_offset.store(0, Ordering::Release);

            chunk_idx = new_chunk_idx;
        }

        let chunk = &chunks[chunk_idx];
        let ptr = unsafe { NonNull::new(chunk.as_ptr().add(offset) as *mut T).unwrap() };

        // Record allocation statistics
        MEMORY_STATS.record_allocation(aligned_size, true);

        ptr
    }

    /// Allocate a slice from the arena
    pub fn allocate_slice<T>(&mut self, count: usize) -> NonNull<[T]> {
        let ptr = self.allocate::<T>(count);
        let slice_ptr = NonNull::new(ptr.as_ptr() as *mut [T; 0]).unwrap();
        NonNull::slice_from_raw_parts(slice_ptr.cast::<T>(), count)
    }

    /// Reset the arena with memory pooling support
    pub fn reset(&mut self) {
        let mut chunks = self.chunks.lock().unwrap();

        // Reset offsets without returning chunks to pool - maintain same memory blocks
        self.current_chunk.store(0, Ordering::Release);
        self.current_offset.store(0, Ordering::Release);
    }

    /// Clear the arena with memory pooling
    pub fn clear(&mut self) {
        let mut chunks = self.chunks.lock().unwrap();

        // Return all full-sized chunks to pool
        for chunk in chunks.iter() {
            if chunk.len() == self.chunk_size {
                self.return_chunk_to_pool(chunk.clone());
            }
        }

        chunks.clear();
        self.current_chunk.store(0, Ordering::Release);
        self.current_offset.store(0, Ordering::Release);
    }
}

/// Thread-local arena for each worker thread with memory pooling
#[allow(dead_code)]
pub struct ThreadLocalArena {
    arenas: Vec<Arc<Mutex<MemoryArena>>>,
    thread_count: usize,
    /// Memory compaction threshold
    compaction_threshold: usize,
}

#[allow(dead_code)]
impl ThreadLocalArena {
    pub fn new(thread_count: usize, chunk_size: usize, alignment: usize) -> Self {
        let arenas = (0..thread_count)
            .map(|_| Arc::new(Mutex::new(MemoryArena::new(chunk_size, alignment))))
            .collect();

        Self {
            arenas,
            thread_count,
            compaction_threshold: 10, // Compact after 10 allocations
        }
    }

    pub fn get_arena(&self, thread_id: usize) -> Arc<Mutex<MemoryArena>> {
        self.arenas[thread_id % self.thread_count].clone()
    }

    pub fn reset_all(&self) {
        for arena in &self.arenas {
            if let Ok(mut arena_guard) = arena.lock() {
                arena_guard.reset();
            }
        }
    }

    /// Perform memory compaction on all arenas
    pub fn compact_all(&self) -> usize {
        let mut total_freed = 0;
        for arena in &self.arenas {
            if let Ok(mut arena_guard) = arena.lock() {
                total_freed += arena_guard.compact_memory();
            }
        }
        total_freed
    }

    /// Get memory usage statistics
    pub fn get_memory_stats(&self) -> ThreadLocalArenaStats {
        let mut total_chunks = 0;
        let mut total_used_memory = 0;

        for arena in &self.arenas {
            if let Ok(arena_guard) = arena.lock() {
                let chunks = arena_guard.chunks.lock().unwrap();
                total_chunks += chunks.len();
                total_used_memory += chunks.iter().map(|c| c.len()).sum::<usize>();
            }
        }

        ThreadLocalArenaStats {
            total_chunks,
            total_used_memory,
            thread_count: self.thread_count,
        }
    }
}

/// Statistics for thread-local arena
#[allow(dead_code)]
pub struct ThreadLocalArenaStats {
    pub total_chunks: usize,
    pub total_used_memory: usize,
    pub thread_count: usize,
}

/// Zero-copy buffer pool for JNI operations
#[allow(dead_code)]
pub struct ZeroCopyBufferPool {
    buffers: Arc<ParkingMutex<VecDeque<Vec<u8>>>>,
    buffer_size: usize,
    max_buffers: usize,
}

#[allow(dead_code)]
impl ZeroCopyBufferPool {
    pub fn new(buffer_size: usize, max_buffers: usize) -> Self {
        let mut buffers = VecDeque::new();

        // Pre-allocate buffers
        for _ in 0..max_buffers / 2 {
            buffers.push_back(vec![0u8; buffer_size]);
        }

        Self {
            buffers: Arc::new(ParkingMutex::new(buffers)),
            buffer_size,
            max_buffers,
        }
    }

    /// Get a buffer from the pool (zero-copy operation)
    pub fn acquire_buffer(&self) -> Option<Vec<u8>> {
        let mut buffers = self.buffers.lock();
        buffers.pop_front()
    }

    /// Return a buffer to the pool
    pub fn release_buffer(&self, buffer: Vec<u8>) {
        let mut buffers = self.buffers.lock();
        if buffers.len() < self.max_buffers {
            buffers.push_back(buffer);
        }
    }

    /// Get buffer with automatic cleanup
    pub fn get_buffer<F>(&self, f: F)
    where
        F: FnOnce(&mut [u8]),
    {
        let mut buffer = self
            .acquire_buffer()
            .unwrap_or_else(|| vec![0u8; self.buffer_size]);
        f(&mut buffer);
        self.release_buffer(buffer);
    }
}

/// Memory pool for frequently allocated objects
#[allow(dead_code)]
pub struct ObjectPool<T> {
    objects: Arc<ParkingMutex<VecDeque<T>>>,
    max_objects: usize,
    factory: Box<dyn Fn() -> T + Send + Sync>,
}

#[allow(dead_code)]
impl<T> ObjectPool<T> {
    pub fn new(max_objects: usize, factory: Box<dyn Fn() -> T + Send + Sync>) -> Self {
        let mut objects = VecDeque::new();

        // Pre-allocate objects
        for _ in 0..max_objects / 2 {
            objects.push_back(factory());
        }

        Self {
            objects: Arc::new(ParkingMutex::new(objects)),
            max_objects,
            factory,
        }
    }

    pub fn acquire(&self) -> T {
        let mut objects = self.objects.lock();
        objects.pop_front().unwrap_or_else(|| (self.factory)())
    }

    pub fn release(&self, object: T) {
        let mut objects = self.objects.lock();
        if objects.len() < self.max_objects {
            objects.push_back(object);
        }
    }
}

/// Cache-friendly memory layout for matrix operations
#[allow(dead_code)]
pub struct CacheFriendlyMatrix {
    data: Vec<f32>,
    rows: usize,
    cols: usize,
    row_major: bool,
}

#[allow(dead_code)]
impl CacheFriendlyMatrix {
    pub fn new(rows: usize, cols: usize, row_major: bool) -> Self {
        Self {
            data: vec![0.0; rows * cols],
            rows,
            cols,
            row_major,
        }
    }

    pub fn from_data(data: Vec<f32>, rows: usize, cols: usize, row_major: bool) -> Self {
        Self {
            data,
            rows,
            cols,
            row_major,
        }
    }

    pub fn get(&self, row: usize, col: usize) -> f32 {
        if self.row_major {
            self.data[row * self.cols + col]
        } else {
            self.data[col * self.rows + row]
        }
    }

    pub fn set(&mut self, row: usize, col: usize, value: f32) {
        if self.row_major {
            self.data[row * self.cols + col] = value;
        } else {
            self.data[col * self.rows + row] = value;
        }
    }

    pub fn get_row(&self, row: usize) -> &[f32] {
        if self.row_major {
            &self.data[row * self.cols..(row + 1) * self.cols]
        } else {
            // For column-major, we need to collect scattered elements
            unsafe { slice::from_raw_parts(self.data.as_ptr().add(row), self.cols) }
        }
    }

    pub fn get_col(&self, col: usize) -> Vec<f32> {
        if self.row_major {
            // For row-major, we need to collect scattered elements
            (0..self.rows).map(|row| self.get(row, col)).collect()
        } else {
            self.data[col * self.rows..(col + 1) * self.rows].to_vec()
        }
    }

    pub fn transpose(&self) -> Self {
        let mut result = Self::new(self.cols, self.rows, !self.row_major);

        for i in 0..self.rows {
            for j in 0..self.cols {
                result.set(j, i, self.get(i, j));
            }
        }

        result
    }
}

/// Memory-mapped matrix for large datasets
#[allow(dead_code)]
pub struct MemoryMappedMatrix {
    path: String,
    rows: usize,
    cols: usize,
    mmap: Option<memmap2::Mmap>,
}

#[allow(dead_code)]
impl MemoryMappedMatrix {
    pub fn new(path: String, rows: usize, cols: usize) -> Self {
        Self {
            path,
            rows,
            cols,
            mmap: None,
        }
    }

    pub fn create(&mut self, data: &[f32]) -> std::io::Result<()> {
        use std::fs::File;
        use std::io::Write;

        let mut file = File::create(&self.path)?;
        let bytes = unsafe { slice::from_raw_parts(data.as_ptr() as *const u8, data.len() * 4) };
        file.write_all(bytes)?;

        Ok(())
    }

    pub fn load(&mut self) -> std::io::Result<()> {
        use std::fs::File;

        let file = File::open(&self.path)?;
        let mmap = unsafe { memmap2::Mmap::map(&file)? };
        self.mmap = Some(mmap);

        Ok(())
    }

    pub fn get(&self, row: usize, col: usize) -> Option<f32> {
        if let Some(mmap) = &self.mmap {
            let index = row * self.cols + col;
            if index < self.rows * self.cols {
                let bytes = &mmap[index * 4..(index + 1) * 4];
                let value = unsafe { *(bytes.as_ptr() as *const f32) };
                Some(value)
            } else {
                None
            }
        } else {
            None
        }
    }
}

/// Hot loop memory allocator with zero-copy semantics
#[allow(dead_code)]
pub struct HotLoopAllocator {
    arenas: Vec<Arc<Mutex<MemoryArena>>>,
    current_arena: AtomicUsize,
    thread_local_arenas: Arc<ThreadLocalArena>,
}

#[allow(dead_code)]
impl HotLoopAllocator {
    pub fn new(num_threads: usize, chunk_size: usize) -> Self {
        let arenas = (0..num_threads)
            .map(|_| Arc::new(Mutex::new(MemoryArena::new(chunk_size, 64))))
            .collect();

        let thread_local_arenas = Arc::new(ThreadLocalArena::new(num_threads, chunk_size * 4, 64));

        Self {
            arenas,
            current_arena: AtomicUsize::new(0),
            thread_local_arenas,
        }
    }

    /// Allocate memory with zero-copy semantics
    pub fn allocate<T>(&self, thread_id: usize, count: usize) -> NonNull<T> {
        let arena_idx = thread_id % self.arenas.len();
        let mut arena = self.arenas[arena_idx].lock().unwrap();
        arena.allocate::<T>(count)
    }

    /// Allocate from thread-local arena
    pub fn allocate_thread_local<T>(&self, thread_id: usize, count: usize) -> NonNull<T> {
        let arena = self.thread_local_arenas.get_arena(thread_id);
        let mut arena_guard = arena.lock().unwrap();
        arena_guard.allocate::<T>(count)
    }

    /// Batch allocate multiple objects
    pub fn batch_allocate<T>(&self, thread_id: usize, counts: &[usize]) -> Vec<NonNull<T>> {
        let arena_idx = thread_id % self.arenas.len();
        let mut arena = self.arenas[arena_idx].lock().unwrap();

        counts
            .iter()
            .map(|&count| arena.allocate::<T>(count))
            .collect()
    }

    /// Reset all arenas
    pub fn reset_all(&self) {
        for _arena in &self.arenas {
            // Skip reset for now - need to redesign thread-safe arena
            // Skip reset for now - need to redesign thread-safe arena
        }
        self.thread_local_arenas.reset_all();
    }
}

/// Memory statistics for hot loops
#[allow(dead_code)]
pub struct MemoryStats {
    pub total_allocations: AtomicU64,
    pub total_bytes_allocated: AtomicU64,
    pub arena_hits: AtomicU64,
    pub arena_misses: AtomicU64,
    pub cache_hits: AtomicU64,
    pub cache_misses: AtomicU64,
}

#[allow(dead_code)]
impl MemoryStats {
    pub fn new() -> Self {
        Self {
            total_allocations: AtomicU64::new(0),
            total_bytes_allocated: AtomicU64::new(0),
            arena_hits: AtomicU64::new(0),
            arena_misses: AtomicU64::new(0),
            cache_hits: AtomicU64::new(0),
            cache_misses: AtomicU64::new(0),
        }
    }

    pub fn record_allocation(&self, bytes: usize, hit: bool) {
        self.total_allocations.fetch_add(1, Ordering::Relaxed);
        self.total_bytes_allocated
            .fetch_add(bytes as u64, Ordering::Relaxed);

        if hit {
            self.arena_hits.fetch_add(1, Ordering::Relaxed);
        } else {
            self.arena_misses.fetch_add(1, Ordering::Relaxed);
        }
    }

    pub fn get_summary(&self) -> String {
        format!(
            "MemoryStats{{allocations:{}, bytes:{}, arena_hits:{}, arena_misses:{}, cache_hits:{}, cache_misses:{}}}",
            self.total_allocations.load(Ordering::Relaxed),
            self.total_bytes_allocated.load(Ordering::Relaxed),
            self.arena_hits.load(Ordering::Relaxed),
            self.arena_misses.load(Ordering::Relaxed),
            self.cache_hits.load(Ordering::Relaxed),
            self.cache_misses.load(Ordering::Relaxed)
        )
    }
}

lazy_static::lazy_static! {
    pub static ref MEMORY_STATS: MemoryStats = MemoryStats::new();
}

// Global hot loop allocator
lazy_static::lazy_static! {
    pub static ref HOT_LOOP_ALLOCATOR: HotLoopAllocator = HotLoopAllocator::new(
        num_cpus::get(),
        1024 * 1024, // 1MB chunks
    );
}

/// Zero-copy matrix multiplication using arena allocation
#[allow(dead_code)]
pub fn arena_matrix_multiply(
    a: &[f32],
    b: &[f32],
    a_rows: usize,
    a_cols: usize,
    b_cols: usize,
    thread_id: usize,
) -> Vec<f32> {
    let result_size = a_rows * b_cols;

    // Allocate result matrix from arena
    let result_ptr = HOT_LOOP_ALLOCATOR.allocate_thread_local::<f32>(thread_id, result_size);
    let result_slice = unsafe { slice::from_raw_parts_mut(result_ptr.as_ptr(), result_size) };

    // Perform multiplication with cache-friendly access
    for i in 0..a_rows {
        for j in 0..b_cols {
            let mut sum = 0.0;

            // Use SIMD for inner product
            let simd_end = a_cols - (a_cols % 8);

            if is_x86_feature_detected!("avx") {
                unsafe {
                    let mut simd_sum = _mm256_setzero_ps();

                    for k in (0..simd_end).step_by(8) {
                        let a_vec = _mm256_loadu_ps(a.as_ptr().add(i * a_cols + k));
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

                    // Horizontal sum
                    let mut temp = [0.0f32; 8];
                    _mm256_storeu_ps(temp.as_mut_ptr(), simd_sum);
                    sum += temp.iter().sum::<f32>();
                }
            }

            // Handle remaining elements
            for k in simd_end..a_cols {
                sum += a[i * a_cols + k] * b[k * b_cols + j];
            }

            result_slice[i * b_cols + j] = sum;
        }
    }

    // Copy result to owned vector (this is the only copy operation)
    result_slice.to_vec()
}

/// JNI interface for zero-copy operations
#[allow(dead_code)]
pub fn arena_matrix_multiply_jni<'a>(
    env: &mut jni::JNIEnv<'a>,
    a: &jni::objects::JFloatArray<'a>,
    b: &jni::objects::JFloatArray<'a>,
    a_rows: i32,
    a_cols: i32,
    b_cols: i32,
    thread_id: i32,
) -> jni::objects::JFloatArray<'a> {
    let a_size = (a_rows * a_cols) as usize;
    let b_size = (a_cols * b_cols) as usize;

    let mut a_data = vec![0.0f32; a_size];
    let mut b_data = vec![0.0f32; b_size];

    env.get_float_array_region(a, 0, &mut a_data).unwrap();
    env.get_float_array_region(b, 0, &mut b_data).unwrap();

    let result = arena_matrix_multiply(
        &a_data,
        &b_data,
        a_rows as usize,
        a_cols as usize,
        b_cols as usize,
        thread_id as usize,
    );

    let output = env.new_float_array(result.len() as i32).unwrap();
    env.set_float_array_region(&output, 0, &result).unwrap();

    output
}

#[allow(non_snake_case)]
pub fn Java_com_kneaf_core_ParallelRustVectorProcessor_getMemoryStats<'a>(
    env: jni::JNIEnv<'a>,
    _class: jni::objects::JClass<'a>,
) -> jni::objects::JString<'a> {
    let stats = MEMORY_STATS.get_summary();
    env.new_string(&stats).unwrap()
}

#[allow(non_snake_case)]
pub fn Java_com_kneaf_core_ParallelRustVectorProcessor_resetMemoryArena(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
) {
    HOT_LOOP_ALLOCATOR.reset_all();
    MEMORY_STATS.record_allocation(0, true);
}
