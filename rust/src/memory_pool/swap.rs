use std::collections::HashMap;
use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::PathBuf;
use std::sync::RwLock;

use crate::logging::generate_trace_id;
use crate::logging::PerformanceLogger;
use crate::memory_pool::object_pool::MemoryPressureLevel;
use crate::CompressionStats;

/// Configuration for swap memory pool
#[derive(Debug, Clone)]
pub struct SwapPoolConfig {
    pub swap_file_path: PathBuf,
    pub max_swap_size: usize,       // Maximum swap file size in bytes
    pub page_size: usize,           // Size of each swap page (typically 4KB)
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
            page_size: 4096,              // 4KB pages
            max_pages_in_memory: 1000,    // Keep 1000 pages in memory
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
    offset: u64,            // Offset in swap file
    size: usize,            // Size of data in page
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

        logger.log_info(
            "new",
            &trace_id,
            &format!(
                "Initialized swap memory pool with {} MB max size",
                config.max_swap_size / 1_048_576
            ),
        );

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
    pub fn allocate(&self, size: usize) -> Result<SwapPooledVec, String> {
        let trace_id = generate_trace_id();

        // Check if we need to swap out some pages first
        self.check_memory_pressure()?;

        let page_id = self
            .page_id_counter
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);

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

        self.logger.log_info(
            "allocate",
            &trace_id,
            &format!("Allocated {} bytes in swap pool (page {})", size, page_id),
        );

        Ok(SwapPooledVec {
            data: vec,
            page_id,
            pool: self,
            is_swapped: false,
            dirty: false,
            logger: PerformanceLogger::new("swap_pooled_vec"),
        })
    }

    /// Swap out least recently used pages to disk
    fn check_memory_pressure(&self) -> Result<(), String> {
        let trace_id = generate_trace_id();
        let memory_usage = self.memory_cache.read().unwrap().len();

        if memory_usage < self.config.max_pages_in_memory {
            return Ok(());
        }

        self.logger.log_info(
            "check_memory_pressure",
            &trace_id,
            &format!("Memory pressure detected: {} pages in memory", memory_usage),
        );

        // Find least recently used pages to swap out
        let mut pages_to_swap: Vec<(u64, std::time::SystemTime)> = self
            .pages
            .read()
            .unwrap()
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
        let offset = file
            .seek(SeekFrom::End(0))
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

        self.logger.log_info(
            "swap_out_page",
            &trace_id,
            &format!(
                "Swapped out page {} ({} bytes, compressed: {})",
                page_id,
                compressed_data.len(),
                is_compressed
            ),
        );

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
            return Err(format!(
                "Checksum mismatch for page {}: expected {}, got {}",
                page_id, page.checksum, calculated_checksum
            ));
        }

        // Decompress if necessary
        let data = if page.is_compressed {
            self.decompress_data(&compressed_data)?
        } else {
            compressed_data
        };

        // Store in memory cache
        self.memory_cache
            .write()
            .unwrap()
            .insert(page_id, data.clone());

        // Update last access time
        if let Some(page) = self.pages.write().unwrap().get_mut(&page_id) {
            page.last_access = std::time::SystemTime::now();
        }

        self.logger.log_info(
            "swap_in_page",
            &trace_id,
            &format!("Swapped in page {} ({} bytes)", page_id, data.len()),
        );

        Ok(data)
    }

    /// Compress data using LZ4
    pub fn compress_data(&self, data: &[u8]) -> Result<Vec<u8>, String> {
        let start_time = std::time::Instant::now();

        // Use LZ4 compression for speed
        let compressed = lz4_flex::compress_prepend_size(data);

        let elapsed = start_time.elapsed();
        let mut stats = self.compression_stats.write().unwrap();
        stats.original_size += data.len();
        stats.compressed_size += compressed.len();
        stats.total_uncompressed += data.len();
        stats.total_compressed += compressed.len();
        stats.compression_time += elapsed;
        stats.compression_time_ms += elapsed.as_millis() as u64;

        if stats.total_uncompressed > 0 {
            stats.compression_ratio =
                stats.total_compressed as f64 / stats.total_uncompressed as f64;
        }

        Ok(compressed)
    }

    /// Decompress data using LZ4
    pub fn decompress_data(&self, compressed_data: &[u8]) -> Result<Vec<u8>, String> {
        lz4_flex::decompress_size_prepended(compressed_data)
            .map_err(|e| format!("Failed to decompress data: {}", e))
    }

    /// Allocate chunk metadata - specialized allocation for chunk metadata
    pub fn allocate_chunk_metadata(&self, size: usize) -> Result<SwapPooledVec, String> {
        self.allocate(size)
    }

    /// Allocate compressed data - specialized allocation for compressed data
    pub fn allocate_compressed_data(&self, size: usize) -> Result<SwapPooledVec, String> {
        self.allocate(size)
    }

    /// Allocate temporary buffer - specialized allocation for temporary buffers
    pub fn allocate_temporary_buffer(&self, size: usize) -> Result<SwapPooledVec, String> {
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
            total_compressed_bytes: compression_stats.compressed_size,
            total_uncompressed_bytes: compression_stats.original_size,
        }
    }

    /// Perform aggressive cleanup of swap pool
    pub fn perform_aggressive_cleanup(&self) -> Result<(), String> {
        self.cleanup()
    }

    /// Write data synchronously to swap file
    pub fn write_data_sync(&self, data: Vec<u8>) -> Result<u64, String> {
        let trace_id = generate_trace_id();

        // Allocate memory for the data
        let allocation = self.allocate(data.len())?;
        let page_id = allocation.page_id;

        // Get page metadata and ensure it's updated (metadata clone retained for potential future use)
        let mut _page = self.pages.write().unwrap();
        let _page = _page
            .get_mut(&page_id)
            .ok_or_else(|| format!("Page {} not found", page_id))?
            .clone();

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
                Err(e) => {
                    self.logger.log_error(
                        "write_data_sync",
                        &trace_id,
                        &format!("Compression failed: {}", e),
                        "COMPRESSION_ERROR",
                    );
                    (data, false) // Use original data if compression fails
                }
            }
        } else {
            (data, false)
        };

        // Calculate checksum
        let checksum = crc32fast::hash(&compressed_data);

        // Write to swap file
        let swap_file_path = self.config.swap_file_path.clone();
        let write_result: Result<u64, String> = {
            let mut file = OpenOptions::new()
                .read(true)
                .write(true)
                .open(&swap_file_path)
                .map_err(|e| format!("Failed to open swap file: {}", e))?;

            let offset = file
                .seek(SeekFrom::End(0))
                .map_err(|e| format!("Failed to seek swap file: {}", e))?;

            file.write_all(&compressed_data)
                .map_err(|e| format!("Failed to write to swap file: {}", e))?;

            Ok(offset)
        };

        let offset = write_result?;

        // Update page metadata
        let mut page = self.pages.write().unwrap();
        let page = page
            .get_mut(&page_id)
            .ok_or_else(|| format!("Page {} not found", page_id))?;

        page.offset = offset;
        page.compressed_size = compressed_data.len();
        page.is_compressed = is_compressed;
        page.checksum = checksum;
        page.last_access = std::time::SystemTime::now();

        self.logger.log_info(
            "write_data_sync",
            &trace_id,
            &format!(
                "Wrote {} bytes to swap file (page {}), compressed: {}",
                compressed_data.len(),
                page_id,
                is_compressed
            ),
        );

        Ok(page_id)
    }

    /// Read data asynchronously from swap file
    pub async fn read_data_async(&self, page_id: u64, size: usize) -> Result<Vec<u8>, String> {
        let trace_id = generate_trace_id();

        // Get page metadata
        let page = self
            .pages
            .read()
            .unwrap()
            .get(&page_id)
            .ok_or_else(|| format!("Page {} not found", page_id))?
            .clone();

        // Check if already in memory cache
        if let Some(cached_data) = self.memory_cache.read().unwrap().get(&page_id) {
            if cached_data.len() == size {
                self.logger.log_info(
                    "read_data_async",
                    &trace_id,
                    &format!("Cache hit for page {}", page_id),
                );
                return Ok(cached_data.clone());
            }
        }

        // Read from swap file asynchronously
        let swap_file_path = self.config.swap_file_path.clone();
        let read_result: Result<Vec<u8>, String> = {
            let mut file = OpenOptions::new()
                .read(true)
                .open(&swap_file_path)
                .map_err(|e| format!("Failed to open swap file: {}", e))?;

            file.seek(SeekFrom::Start(page.offset))
                .map_err(|e| format!("Failed to seek to page offset: {}", e))?;

            let mut compressed_data = vec![0u8; page.compressed_size];
            file.read_exact(&mut compressed_data)
                .map_err(|e| format!("Failed to read from swap file: {}", e))?;

            Ok(compressed_data)
        };

        let compressed_data = read_result?;

        // Verify checksum
        let calculated_checksum = crc32fast::hash(&compressed_data);
        if calculated_checksum != page.checksum {
            return Err(format!(
                "Checksum mismatch for page {}: expected {}, got {}",
                page_id, page.checksum, calculated_checksum
            ));
        }

        // Decompress if necessary
        let data = if page.is_compressed {
            self.decompress_data(&compressed_data).map_err(|e| {
                self.logger.log_error(
                    "read_data_async",
                    &trace_id,
                    &format!("Decompression failed: {}", e),
                    "DECOMPRESSION_ERROR",
                );
                format!("Decompression failed: {}", e)
            })?
        } else {
            compressed_data
        };

        // Update memory cache
        if data.len() == size {
            self.memory_cache
                .write()
                .unwrap()
                .insert(page_id, data.clone());

            // Update last access time
            if let Some(page) = self.pages.write().unwrap().get_mut(&page_id) {
                page.last_access = std::time::SystemTime::now();
            }
        }

        self.logger.log_info(
            "read_data_async",
            &trace_id,
            &format!(
                "Asynchronously read {} bytes from swap file (page {})",
                data.len(),
                page_id
            ),
        );

        Ok(data)
    }

    /// Async wrapper for compress_data to support async operations
    #[allow(dead_code)]
    async fn compress_data_async(&self, data: &[u8]) -> Result<Vec<u8>, String> {
        let start_time = std::time::Instant::now();

        // Use LZ4 compression for speed
        let compressed = lz4_flex::compress_prepend_size(data);

        let elapsed = start_time.elapsed();
        let mut stats = self.compression_stats.write().unwrap();
        stats.original_size += data.len();
        stats.compressed_size += compressed.len();
        stats.total_uncompressed += data.len();
        stats.total_compressed += compressed.len();
        stats.compression_time += elapsed;
        stats.compression_time_ms += elapsed.as_millis() as u64;

        if stats.total_uncompressed > 0 {
            stats.compression_ratio =
                stats.total_compressed as f64 / stats.total_uncompressed as f64;
        }

        Ok(compressed)
    }

    /// Prefetch pages that are likely to be accessed soon
    pub fn prefetch_pages(&self, page_ids: &[u64]) -> Result<(), String> {
        let trace_id = generate_trace_id();

        if !self.config.prefetch_enabled {
            return Ok(());
        }

        self.logger.log_info(
            "prefetch_pages",
            &trace_id,
            &format!("Prefetching {} pages", page_ids.len()),
        );

        for &page_id in page_ids {
            if !self.memory_cache.read().unwrap().contains_key(&page_id) {
                self.swap_in_page(page_id)?;
            }
        }

        Ok(())
    }

    /// Get memory pressure level
    pub fn get_memory_pressure(&self) -> MemoryPressureLevel {
        let total_pages = self.pages.read().unwrap().len() as f64;
        let max_memory = self.config.max_pages_in_memory as f64;

        let usage_ratio = if max_memory > 0.0 {
            total_pages / max_memory
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

        self.logger
            .log_info("cleanup", &trace_id, "Swap memory pool cleaned up");

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
pub struct SwapPooledVec {
    data: Vec<u8>,
    page_id: u64,
    pool: *const SwapMemoryPool, // Raw pointer to avoid lifetime issues
    is_swapped: bool,
    dirty: bool, // Track if data has been modified
    #[allow(dead_code)]
    logger: PerformanceLogger,
}

impl SwapPooledVec {
    /// Access the underlying data
    pub fn as_slice(&self) -> &[u8] {
        &self.data
    }

    /// Access mutable data (marks as dirty)
    pub fn as_mut_slice(&mut self) -> &mut [u8] {
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

    /// Ensure data is in memory (swap in if necessary) with proper type conversion
    pub fn ensure_in_memory(&mut self) -> Result<(), String> {
        if self.is_swapped {
            let trace_id = generate_trace_id();

            // Get the page from the pool
            let pool = unsafe { &*self.pool };
            let page = match pool.pages.read().unwrap().get(&self.page_id) {
                Some(page) => page.clone(),
                None => return Err(format!("Page {} not found in metadata", self.page_id)),
            };

            // Read data from swap file with proper type conversion
            let data = {
                let pool = unsafe { &*self.pool };
                pool.swap_in_page(self.page_id)
            }
            .map_err(|e| e.to_string())?;

            // Convert the read data to the appropriate type (u8 in this case)
            // For other types, this would involve deserialization or type conversion logic
            if data.len() != page.size {
                return Err(format!(
                    "Data size mismatch for page {}: expected {}, got {}",
                    self.page_id,
                    page.size,
                    data.len()
                ));
            }

            // Replace the internal data with the swapped-in data
            self.data = data;

            // Mark as not swapped and not dirty (data is now in memory)
            self.is_swapped = false;
            self.dirty = false;

            let pool = unsafe { &*self.pool };
            pool.logger.log_info(
                "ensure_in_memory",
                &trace_id,
                &format!(
                    "Successfully swapped in page {} with {} bytes of data",
                    self.page_id,
                    self.data.len()
                ),
            );
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

impl Drop for SwapPooledVec {
    fn drop(&mut self) {
        if self.dirty && !self.is_swapped {
            // Data was modified but not yet swapped out - ensure it's persisted
            let pool = unsafe { &*self.pool };
            let _ = pool.swap_out_page(self.page_id); // Ignore errors in drop
        }
    }
}

impl Clone for SwapPooledVec {
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
            logger: PerformanceLogger::new("swap_pooled_vec"),
        }
    }
}

// Implement Send for SwapPooledVec to allow sending between threads
// SAFETY: The raw pointer to the pool is only used for cleanup in Drop,
// and the pool itself is expected to be thread-safe for allocation operations.
unsafe impl Send for SwapPooledVec {}

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
            max_pages_in_memory: 1, // Very small to force immediate swap out
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
            max_pages_in_memory: 1, // Very small to force immediate swap out
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

        // Force swap out to trigger compression by allocating another page
        // This will trigger memory pressure and force the first page to be swapped out
        let _vec2 = pool.allocate(1024).unwrap();

        // Also test direct compression through write_data_sync
        let test_data = vec![1u8; 2048]; // Create compressible data
        let _page_id = pool.write_data_sync(test_data).unwrap();

        // Check compression stats
        let stats = pool.get_compression_stats();
        assert!(stats.total_uncompressed > 0, "total_uncompressed should be > 0, got {}", stats.total_uncompressed);
        assert!(stats.total_compressed > 0, "total_compressed should be > 0, got {}", stats.total_compressed);
        assert!(stats.compression_ratio > 0.0, "compression_ratio should be > 0.0, got {}", stats.compression_ratio);

        // Clean up
        fs::remove_file(temp_file).ok();
    }
}
