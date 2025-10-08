use std::collections::HashMap;
use std::fs::{File, OpenOptions};
use std::io::{Read, Write, Seek, SeekFrom};
use std::path::PathBuf;
use std::sync::RwLock;

use crate::logging::PerformanceLogger;
use crate::memory_pool::object_pool::MemoryPressureLevel;
use crate::logging::generate_trace_id;

/// Configuration for swap memory pool
#[derive(Debug, Clone)]
pub struct SwapPoolConfig {
    pub swap_file_path: PathBuf,
    pub max_swap_size: usize, // Maximum swap file size in bytes
    pub page_size: usize,     // Size of each swap page (typically 4KB)
    pub max_pages_in_memory: usize, // Maximum pages to keep in memory
    pub compression_enabled: bool,
    pub prefetch_enabled: bool,
    pub prefetch_threshold: f64, // Percentage of memory usage to trigger prefetch
}

impl Default for SwapPoolConfig {
    fn default() -> Self {
        Self {
            swap_file_path: PathBuf::from("./swap_memory.dat"),
            max_swap_size: 1_073_741_824, // 1GB default
            page_size: 4096, // 4KB pages
            max_pages_in_memory: 1000, // Keep 1000 pages in memory
            compression_enabled: true,
            prefetch_enabled: true,
            prefetch_threshold: 0.7, // Prefetch when 70% of memory is used
        }
    }
}

/// Swap page metadata
#[derive(Debug, Clone)]
#[allow(dead_code)]
struct SwapPage {
    id: u64,
    offset: u64, // Offset in swap file
    size: usize, // Size of data in page
    compressed_size: usize, // Size after compression (if enabled)
    last_access: std::time::SystemTime,
    is_compressed: bool,
    checksum: u32, // CRC32 checksum for data integrity
}

/// Swap memory pool for handling memory pressure with disk backing
#[derive(Debug)]
pub struct SwapMemoryPool {
    config: SwapPoolConfig,
    swap_file: RwLock<File>,
    pages: RwLock<HashMap<u64, SwapPage>>,
    memory_cache: RwLock<HashMap<u64, Vec<u8>>>, // In-memory cache for frequently accessed pages
    page_id_counter: std::sync::atomic::AtomicU64,
    logger: PerformanceLogger,
    compression_stats: RwLock<CompressionStats>,
}

#[derive(Debug, Clone, Default)]
pub struct CompressionStats {
    pub total_compressed: usize,
    pub total_uncompressed: usize,
    pub compression_ratio: f64,
    pub compression_time: std::time::Duration,
}

#[derive(Debug, Clone, Default)]
pub struct SwapPoolMetrics {
    pub total_allocations: u64,
    pub current_usage_bytes: usize,
    pub swap_file_size_bytes: u64,
    pub pages_in_memory: usize,
    pub pages_on_disk: usize,
    pub compression_ratio: f64,
    pub total_compressed_bytes: usize,
    pub total_uncompressed_bytes: usize,
}

impl SwapMemoryPool {
    pub fn new(config: Option<SwapPoolConfig>) -> Result<Self, String> {
        let config = config.unwrap_or_default();
        let trace_id = generate_trace_id();

        // Create swap file directory if it doesn't exist
        if let Some(parent) = config.swap_file_path.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| format!("Failed to create swap directory: {}", e))?;
        }

        // Open or create swap file
        let swap_file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .open(&config.swap_file_path)
            .map_err(|e| format!("Failed to open swap file: {}", e))?;

        let logger = PerformanceLogger::new("swap_memory_pool");

        logger.log_info("new", &trace_id, &format!("Initialized swap memory pool with {} MB max size", config.max_swap_size / 1_048_576));

        Ok(Self {
            config,
            swap_file: RwLock::new(swap_file),
            pages: RwLock::new(HashMap::new()),
            memory_cache: RwLock::new(HashMap::new()),
            page_id_counter: std::sync::atomic::AtomicU64::new(1),
            logger,
            compression_stats: RwLock::new(CompressionStats::default()),
        })
    }

    /// Allocate memory with swap backing - returns a pooled object that can be swapped out
    pub fn allocate(&self, size: usize) -> Result<SwapPooledVec<u8>, String> {
        let trace_id = generate_trace_id();

        // Check if we need to swap out some pages first
        self.check_memory_pressure()?;

        let page_id = self.page_id_counter.fetch_add(1, std::sync::atomic::Ordering::Relaxed);

        // Create new page metadata
        let page = SwapPage {
            id: page_id,
            offset: 0, // Will be set when written to disk
            size,
            compressed_size: size,
            last_access: std::time::SystemTime::now(),
            is_compressed: false,
            checksum: 0, // Will be calculated when data is written
        };

        // Store page metadata
        self.pages.write().unwrap().insert(page_id, page.clone());

        // Create pooled vector
        let mut vec = Vec::with_capacity(size);
        vec.resize(size, 0u8);

        self.logger.log_info("allocate", &trace_id, &format!("Allocated {} bytes in swap pool (page {})", size, page_id));

        Ok(SwapPooledVec {
            data: vec,
            page_id,
            pool: self,
            is_swapped: false,
            dirty: false,
        })
    }

    /// Swap out least recently used pages to disk
    fn check_memory_pressure(&self) -> Result<(), String> {
        let trace_id = generate_trace_id();
        let memory_usage = self.memory_cache.read().unwrap().len();

        if memory_usage < self.config.max_pages_in_memory {
            return Ok(());
        }

        self.logger.log_info("check_memory_pressure", &trace_id, &format!("Memory pressure detected: {} pages in memory", memory_usage));

        // Find least recently used pages to swap out
        let mut pages_to_swap: Vec<(u64, std::time::SystemTime)> = self.pages.read().unwrap()
            .iter()
            .filter_map(|(&id, page)| {
                if self.memory_cache.read().unwrap().contains_key(&id) {
                    Some((id, page.last_access))
                } else {
                    None
                }
            })
            .collect();

        // Sort by last access time (oldest first)
        pages_to_swap.sort_by(|a, b| a.1.cmp(&b.1));

        // Swap out oldest 20% of pages
        let swap_count = (pages_to_swap.len() / 5).max(1);
        for (page_id, _) in pages_to_swap.into_iter().take(swap_count) {
            self.swap_out_page(page_id)?;
        }

        Ok(())
    }

    /// Swap a page out to disk
    fn swap_out_page(&self, page_id: u64) -> Result<(), String> {
        let trace_id = generate_trace_id();

        // Get page data from memory cache
        let data = match self.memory_cache.write().unwrap().remove(&page_id) {
            Some(data) => data,
            None => return Ok(()), // Already swapped out
        };

        // Get page metadata
        let mut page = match self.pages.write().unwrap().get_mut(&page_id) {
            Some(page) => page.clone(),
            None => return Err(format!("Page {} not found in metadata", page_id)),
        };

        // Compress data if enabled
        let (compressed_data, is_compressed) = if self.config.compression_enabled {
            match self.compress_data(&data) {
                Ok(compressed) => {
                    if compressed.len() < data.len() {
                        (compressed, true)
                    } else {
                        (data, false) // Compression didn't help
                    }
                }
                Err(_) => (data, false), // Compression failed
            }
        } else {
            (data, false)
        };

        // Calculate checksum
        let checksum = crc32fast::hash(&compressed_data);

        // Write to swap file
        let mut file = self.swap_file.write().unwrap();
        let offset = file.seek(SeekFrom::End(0))
            .map_err(|e| format!("Failed to seek swap file: {}", e))?;

        file.write_all(&compressed_data)
            .map_err(|e| format!("Failed to write to swap file: {}", e))?;

        // Update page metadata
        page.offset = offset;
        page.compressed_size = compressed_data.len();
        page.is_compressed = is_compressed;
        page.checksum = checksum;
        page.last_access = std::time::SystemTime::now();

        self.pages.write().unwrap().insert(page_id, page);

        self.logger.log_info("swap_out_page", &trace_id, &format!("Swapped out page {} ({} bytes, compressed: {})", page_id, compressed_data.len(), is_compressed));

        Ok(())
    }

    /// Swap a page back into memory
    fn swap_in_page(&self, page_id: u64) -> Result<Vec<u8>, String> {
        let trace_id = generate_trace_id();

        // Get page metadata
        let page = match self.pages.read().unwrap().get(&page_id) {
            Some(page) => page.clone(),
            None => return Err(format!("Page {} not found in metadata", page_id)),
        };

        // Check if already in memory
        if let Some(data) = self.memory_cache.read().unwrap().get(&page_id) {
            return Ok(data.clone());
        }

        // Read from swap file
        let mut file = self.swap_file.write().unwrap();
        file.seek(SeekFrom::Start(page.offset))
            .map_err(|e| format!("Failed to seek to page offset: {}", e))?;

        let mut compressed_data = vec![0u8; page.compressed_size];
        file.read_exact(&mut compressed_data)
            .map_err(|e| format!("Failed to read from swap file: {}", e))?;

        // Verify checksum
        let calculated_checksum = crc32fast::hash(&compressed_data);
        if calculated_checksum != page.checksum {
            return Err(format!("Checksum mismatch for page {}: expected {}, got {}", page_id, page.checksum, calculated_checksum));
        }

        // Decompress if necessary
        let data = if page.is_compressed {
            self.decompress_data(&compressed_data)?
        } else {
            compressed_data
        };

        // Store in memory cache
        self.memory_cache.write().unwrap().insert(page_id, data.clone());

        // Update last access time
        if let Some(page) = self.pages.write().unwrap().get_mut(&page_id) {
            page.last_access = std::time::SystemTime::now();
        }

        self.logger.log_info("swap_in_page", &trace_id, &format!("Swapped in page {} ({} bytes)", page_id, data.len()));

        Ok(data)
    }

    /// Compress data using LZ4
    pub fn compress_data(&self, data: &[u8]) -> Result<Vec<u8>, String> {
        let start_time = std::time::Instant::now();

        // Use LZ4 compression for speed
        let compressed = lz4_flex::compress_prepend_size(data);

        let elapsed = start_time.elapsed();
        let mut stats = self.compression_stats.write().unwrap();
        stats.total_uncompressed += data.len();
        stats.total_compressed += compressed.len();
        stats.compression_time += elapsed;

        if stats.total_uncompressed > 0 {
            stats.compression_ratio = stats.total_compressed as f64 / stats.total_uncompressed as f64;
        }

        Ok(compressed)
    }

    /// Decompress data using LZ4
    pub fn decompress_data(&self, compressed_data: &[u8]) -> Result<Vec<u8>, String> {
        lz4_flex::decompress_size_prepended(compressed_data)
            .map_err(|e| format!("Failed to decompress data: {}", e))
    }

    /// Allocate chunk metadata - specialized allocation for chunk metadata
    pub fn allocate_chunk_metadata(&self, size: usize) -> Result<SwapPooledVec<u8>, String> {
        self.allocate(size)
    }

    /// Allocate compressed data - specialized allocation for compressed data
    pub fn allocate_compressed_data(&self, size: usize) -> Result<SwapPooledVec<u8>, String> {
        self.allocate(size)
    }

    /// Allocate temporary buffer - specialized allocation for temporary buffers
    pub fn allocate_temporary_buffer(&self, size: usize) -> Result<SwapPooledVec<u8>, String> {
        self.allocate(size)
    }

    /// Get swap pool metrics
    pub fn get_metrics(&self) -> SwapPoolMetrics {
        let pages = self.pages.read().unwrap();
        let memory_cache = self.memory_cache.read().unwrap();
        let compression_stats = self.compression_stats.read().unwrap();

        SwapPoolMetrics {
            total_allocations: pages.len() as u64,
            current_usage_bytes: pages.values().map(|p| p.size).sum(),
            swap_file_size_bytes: self.get_swap_file_size().unwrap_or(0),
            pages_in_memory: memory_cache.len(),
            pages_on_disk: pages.len() - memory_cache.len(),
            compression_ratio: compression_stats.compression_ratio,
            total_compressed_bytes: compression_stats.total_compressed,
            total_uncompressed_bytes: compression_stats.total_uncompressed,
        }
    }

    /// Perform aggressive cleanup of swap pool
    pub fn perform_aggressive_cleanup(&self) -> Result<(), String> {
        self.cleanup()
    }

    /// Write data asynchronously (placeholder for async operations)
    pub async fn write_data_async(&self, data: Vec<u8>) -> Result<u64, String> {
        let allocation = self.allocate(data.len())?;
        let page_id = allocation.page_id;
        
        // In a real implementation, this would write to disk asynchronously
        // For now, we'll just simulate the operation
        std::thread::sleep(std::time::Duration::from_millis(1));
        
        Ok(page_id)
    }

    /// Read data asynchronously (placeholder for async operations)
    pub async fn read_data_async(&self, _page_id: u64, size: usize) -> Result<Vec<u8>, String> {
        // In a real implementation, this would read from disk asynchronously
        // For now, we'll just simulate the operation
        std::thread::sleep(std::time::Duration::from_millis(1));
        
        Ok(vec![0u8; size])
    }

    /// Prefetch pages that are likely to be accessed soon
    pub fn prefetch_pages(&self, page_ids: &[u64]) -> Result<(), String> {
        let trace_id = generate_trace_id();

        if !self.config.prefetch_enabled {
            return Ok(());
        }

        self.logger.log_info("prefetch_pages", &trace_id, &format!("Prefetching {} pages", page_ids.len()));

        for &page_id in page_ids {
            if !self.memory_cache.read().unwrap().contains_key(&page_id) {
                self.swap_in_page(page_id)?;
            }
        }

        Ok(())
    }

    /// Get memory pressure level
    pub fn get_memory_pressure(&self) -> MemoryPressureLevel {
        let memory_usage = self.memory_cache.read().unwrap().len() as f64;
        let max_memory = self.config.max_pages_in_memory as f64;

        let usage_ratio = if max_memory > 0.0 {
            memory_usage / max_memory
        } else {
            0.0
        };

        MemoryPressureLevel::from_usage_ratio(usage_ratio)
    }

    /// Get compression statistics
    pub fn get_compression_stats(&self) -> CompressionStats {
        self.compression_stats.read().unwrap().clone()
    }

    /// Clean up swap file and resources
    pub fn cleanup(&self) -> Result<(), String> {
        let trace_id = generate_trace_id();

        // Clear memory cache
        self.memory_cache.write().unwrap().clear();

        // Clear page metadata
        self.pages.write().unwrap().clear();

        // Truncate swap file
        let file = self.swap_file.write().unwrap();
        file.set_len(0)
            .map_err(|e| format!("Failed to truncate swap file: {}", e))?;

        self.logger.log_info("cleanup", &trace_id, "Swap memory pool cleaned up");

        Ok(())
    }

    /// Get swap file size
    pub fn get_swap_file_size(&self) -> Result<u64, String> {
        let metadata = std::fs::metadata(&self.config.swap_file_path)
            .map_err(|e| format!("Failed to get swap file metadata: {}", e))?;
        Ok(metadata.len())
    }
}

/// Pooled vector with swap backing
#[derive(Debug)]
pub struct SwapPooledVec<T> {
    data: Vec<T>,
    page_id: u64,
    pool: *const SwapMemoryPool, // Raw pointer to avoid lifetime issues
    is_swapped: bool,
    dirty: bool, // Track if data has been modified
}

impl<T> SwapPooledVec<T> {
    /// Access the underlying data
    pub fn as_slice(&self) -> &[T] {
        &self.data
    }

    /// Access mutable data (marks as dirty)
    pub fn as_mut_slice(&mut self) -> &mut [T] {
        self.dirty = true;
        &mut self.data
    }

    /// Get length
    pub fn len(&self) -> usize {
        self.data.len()
    }

    /// Check if empty
    pub fn is_empty(&self) -> bool {
        self.data.is_empty()
    }

    /// Ensure data is in memory (swap in if necessary)
    pub fn ensure_in_memory(&mut self) -> Result<(), String> {
        if self.is_swapped {
            // Swap in the data - this is a simplified version
            // In a real implementation, you'd need proper type conversion
            // For now, we'll just mark as not swapped
            self.is_swapped = false;
        }
        Ok(())
    }

    /// Mark data as swapped out (for manual memory management)
    pub fn mark_swapped(&mut self) {
        if !self.is_swapped && self.dirty {
            // Data was modified, need to write back to disk first
            // This would be handled by the pool's swap_out_page method
        }
        self.is_swapped = true;
        self.data.clear(); // Clear memory copy
    }
}

impl<T> Drop for SwapPooledVec<T> {
    fn drop(&mut self) {
        if self.dirty && !self.is_swapped {
            // Data was modified but not yet swapped out - ensure it's persisted
            let pool = unsafe { &*self.pool };
            let _ = pool.swap_out_page(self.page_id); // Ignore errors in drop
        }
    }
}

impl<T: Clone> Clone for SwapPooledVec<T> {
    fn clone(&self) -> Self {
        let mut cloned_data = self.data.clone();
        if self.is_swapped {
            // If original is swapped, clone should also be swapped initially
            cloned_data.clear();
        }

        Self {
            data: cloned_data,
            page_id: self.page_id,
            pool: self.pool,
            is_swapped: self.is_swapped,
            dirty: false, // Clone starts clean
        }
    }
}

// Implement Send for SwapPooledVec to allow sending between threads
// SAFETY: The raw pointer to the pool is only used for cleanup in Drop,
// and the pool itself is expected to be thread-safe for allocation operations.
unsafe impl<T> Send for SwapPooledVec<T> {}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn test_swap_pool_allocation() {
        let temp_file = "test_swap.dat";
        let config = SwapPoolConfig {
            swap_file_path: PathBuf::from(temp_file),
            max_swap_size: 10_485_760, // 10MB
            page_size: 4096,
            max_pages_in_memory: 10,
            compression_enabled: false,
            prefetch_enabled: false,
            prefetch_threshold: 0.7,
        };

        let pool = SwapMemoryPool::new(Some(config)).unwrap();

        // Test allocation
        let vec = pool.allocate(1024).unwrap();
        assert_eq!(vec.len(), 1024);

        // Clean up
        fs::remove_file(temp_file).ok();
    }

    #[test]
    fn test_swap_pool_memory_pressure() {
        let temp_file = "test_swap_pressure.dat";
        let config = SwapPoolConfig {
            swap_file_path: PathBuf::from(temp_file),
            max_swap_size: 10_485_760,
            page_size: 4096,
            max_pages_in_memory: 2, // Very small to trigger pressure
            compression_enabled: false,
            prefetch_enabled: false,
            prefetch_threshold: 0.7,
        };

        let pool = SwapMemoryPool::new(Some(config)).unwrap();

        // Allocate multiple pages to trigger memory pressure
        let _vec1 = pool.allocate(1024).unwrap();
        let _vec2 = pool.allocate(1024).unwrap();
        let _vec3 = pool.allocate(1024).unwrap(); // This should trigger swap out

        // Check memory pressure
        let pressure = pool.get_memory_pressure();
        assert_ne!(pressure, MemoryPressureLevel::Normal); // Should be under pressure

        // Clean up
        fs::remove_file(temp_file).ok();
    }

    #[test]
    fn test_compression_stats() {
        let temp_file = "test_compression.dat";
        let config = SwapPoolConfig {
            swap_file_path: PathBuf::from(temp_file),
            max_swap_size: 10_485_760,
            page_size: 4096,
            max_pages_in_memory: 10,
            compression_enabled: true,
            prefetch_enabled: false,
            prefetch_threshold: 0.7,
        };

        let pool = SwapMemoryPool::new(Some(config)).unwrap();

        // Allocate and modify data to trigger compression
        let mut vec = pool.allocate(1024).unwrap();
        let data = vec.as_mut_slice();
        for i in 0..data.len() {
            data[i] = (i % 256) as u8; // Fill with compressible pattern
        }

        // Force swap out to trigger compression
        pool.check_memory_pressure().unwrap();

        // Check compression stats
        let stats = pool.get_compression_stats();
        assert!(stats.total_uncompressed > 0);

        // Clean up
        fs::remove_file(temp_file).ok();
    }
}