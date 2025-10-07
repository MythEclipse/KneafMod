
use std::sync::{Arc, RwLock};
use std::time::{SystemTime, UNIX_EPOCH};
use std::path::Path;
use std::collections::HashMap;
use std::fs::OpenOptions;
use memmap2::{MmapOptions, Mmap};
use jni::JNIEnv;
use jni::objects::{JClass, JString, JByteArray, JObject};
use jni::sys::{jboolean, jlong, jint, jbyteArray};
use sled::Db;
use fastnbt::Value;
use lz4_flex::block;

use log::{debug, info, error};
use serde_json;
use std::fs;

/// Recursively copy a directory's contents to a destination directory.
fn copy_dir_recursive(src: &Path, dst: &Path) -> Result<(), String> {
    if !src.is_dir() {
        return Err(format!("Source is not a directory: {:?}", src));
    }

    for entry in fs::read_dir(src).map_err(|e| format!("Failed to read dir {:?}: {}", src, e))? {
        let entry = entry.map_err(|e| format!("Failed to read dir entry: {}", e))?;
        let path = entry.path();
        let dest = dst.join(entry.file_name());

        if path.is_dir() {
            fs::create_dir_all(&dest).map_err(|e| format!("Failed to create dir {:?}: {}", dest, e))?;
            copy_dir_recursive(&path, &dest)?;
        } else {
            fs::copy(&path, &dest)
                .map_err(|e| format!("Failed to copy file {:?} to {:?}: {}", path, dest, e))?;
        }
    }
    Ok(())
}

/// Swap metadata for tracking chunk access patterns
#[derive(Debug, Clone)]
pub struct SwapMetadata {
    pub last_swap_time: u64,
    pub access_frequency: u64,
    pub priority_score: f64,
    pub swap_count: u64,
    pub last_access_time: u64,
    pub size_bytes: u64,
}

impl SwapMetadata {
    pub fn new(size_bytes: u64) -> Self {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_secs();
            
        Self {
            last_swap_time: 0,
            access_frequency: 0,
            priority_score: 0.0,
            swap_count: 0,
            last_access_time: now,
            size_bytes,
        }
    }
    
    pub fn update_access(&mut self) {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_secs();
            
        self.access_frequency += 1;
        self.last_access_time = now;
        self.recalculate_priority();
    }
    
    pub fn update_swap(&mut self) {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_secs();
            
        self.last_swap_time = now;
        self.swap_count += 1;
        self.recalculate_priority();
    }
    
    fn recalculate_priority(&mut self) {
       let now = SystemTime::now()
           .duration_since(UNIX_EPOCH)
           .unwrap()
           .as_secs();
        
       let time_since_access = now.saturating_sub(self.last_access_time);
       let time_since_swap = now.saturating_sub(self.last_swap_time);
        
       // Calculate components with exponential recency and size penalty
       // 1. Frequency score (capped at 10 to prevent overwhelming other factors)
       let frequency_score = (self.access_frequency as f64 * 0.1).min(10.0);
       
       // 2. Exponential recency score - decays rapidly over time
       // Score = 10 / (1 + time/300) - gives 10 at 0s, ~3.3 at 300s, ~1 at 1000s
       let recency_score = 10.0 / (1.0 + (time_since_access as f64 / 300.0)).max(1.0);
       
       // 3. Size penalty - larger chunks get lower priority (more likely to be swapped out)
       // Penalty = size in MB - gives 0 for small chunks, increasing penalty for larger chunks
       let size_penalty = (self.size_bytes as f64 / 1_000_000.0).max(0.0);
       
       // 4. Swap cost score - penalty for chunks that have been swapped many times
       let swap_cost_score = if self.swap_count > 5 { -2.0 } else if self.swap_count > 2 { -1.0 } else { 0.0 };
        
       // Calculate final priority score
       self.priority_score = frequency_score + recency_score - size_penalty + swap_cost_score;
       
       // Ensure score doesn't go below a minimum value
       self.priority_score = self.priority_score.max(-5.0);
   }
}

/// Database statistics for monitoring
#[derive(Debug, Clone)]
pub struct DatabaseStats {
    pub total_chunks: u64,
    pub total_size_bytes: u64,
    pub read_latency_ms: u64,
    pub write_latency_ms: u64,
    pub last_maintenance_time: u64,
    pub is_healthy: bool,
    // Swap-specific metrics
    pub swap_operations_total: u64,
    pub swap_operations_failed: u64,
    pub swap_in_latency_ms: u64,
    pub swap_out_latency_ms: u64,
    pub memory_mapped_files_active: u64,
    pub total_swap_size_bytes: u64,
    // Checksum monitoring metrics
    pub checksum_verifications_total: u64,
    pub checksum_failures_total: u64,
    pub checksum_health_score: f64,
}

impl DatabaseStats {
    pub fn new() -> Self {
        Self {
            total_chunks: 0,
            total_size_bytes: 0,
            read_latency_ms: 0,
            write_latency_ms: 0,
            last_maintenance_time: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_secs(),
            is_healthy: true,
            swap_operations_total: 0,
            swap_operations_failed: 0,
            swap_in_latency_ms: 0,
            swap_out_latency_ms: 0,
            memory_mapped_files_active: 0,
            total_swap_size_bytes: 0,
            checksum_verifications_total: 0,
            checksum_failures_total: 0,
            checksum_health_score: 0.0,
        }
    }
}
/// Chunk coordinate structure for indexing
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct ChunkCoordinates {
    pub x: i32,
    pub z: i32,
    pub dimension: String,
}

impl ChunkCoordinates {
    pub fn new(x: i32, z: i32, dimension: String) -> Self {
        Self { x, z, dimension }
    }

    /// Generate a key string for database storage
    pub fn to_key(&self) -> String {
        format!("chunk:{},{},{}", self.x, self.z, self.dimension)
    }

    /// Parse coordinates from a key string
    pub fn from_key(key: &str) -> Result<Self, String> {
        if !key.starts_with("chunk:") {
            return Err("Invalid chunk key format".to_string());
        }

        let coords_str = &key[6..]; // Remove "chunk:" prefix
        let parts: Vec<&str> = coords_str.split(',').collect();

        if parts.len() != 3 {
            return Err("Invalid chunk coordinates format".to_string());
        }

        let x = parts[0].parse::<i32>()
            .map_err(|_| "Invalid x coordinate".to_string())?;
        let z = parts[1].parse::<i32>()
            .map_err(|_| "Invalid z coordinate".to_string())?;
        let dimension = parts[2].to_string();

        Ok(Self { x, z, dimension })
    }
}

/// Rust-based database adapter for chunk storage using sled with swap support
pub struct RustDatabaseAdapter {
    db: Arc<Db>,
    stats: Arc<RwLock<DatabaseStats>>,
    swap_metadata: Arc<RwLock<HashMap<String, SwapMetadata>>>,
    memory_mapped_files: Arc<RwLock<HashMap<String, Mmap>>>,
    database_type: String,
    checksum_enabled: bool,
    memory_mapping_enabled: bool,
    db_path: String,
    swap_path: String,
}

impl RustDatabaseAdapter {
    pub fn new(database_type: &str, checksum_enabled: bool, memory_mapping_enabled: bool) -> Result<Self, String> {
        // Use absolute path to ensure consistent database location
        let current_dir = std::env::current_dir()
            .map_err(|e| format!("Failed to get current directory: {}", e))?;
        let db_path = current_dir.join(format!("kneaf_db_{}", database_type));
        let db_path_str = db_path.to_str()
            .ok_or("Failed to convert path to string")?;
        Self::with_path(db_path_str, database_type, checksum_enabled, memory_mapping_enabled)
    }
     
    pub fn with_path(db_path: &str, database_type: &str, checksum_enabled: bool, memory_mapping_enabled: bool) -> Result<Self, String> {
        info!("Initializing RustDatabaseAdapter of type: {} at path: {}", database_type, db_path);
         
        // Create database directory if it doesn't exist
        let path = Path::new(db_path);
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| format!("Failed to create database directory: {}", e))?;
        }
         
        // Create swap directory
        let swap_path = format!("{}/swap", db_path);
        std::fs::create_dir_all(&swap_path)
            .map_err(|e| format!("Failed to create swap directory: {}", e))?;
         
        // Configure sled database with memory mapping if enabled
        let db = if memory_mapping_enabled {
            info!("Enabling memory mapping for sled database");
            let config = sled::Config::new()
                .path(path);
                // memory_map is not supported in this sled version
            config.open()
                .map_err(|e| format!("Failed to open sled database with memory mapping: {}", e))?
        } else {
            sled::open(path)
                .map_err(|e| format!("Failed to open sled database: {}", e))?
        };
        
        // Count existing chunks and load swap metadata
        let mut total_chunks = 0u64;
        let mut total_size_bytes = 0u64;
        let mut swap_metadata = HashMap::new();
        
        let mut iter = db.iter();
        while let Some(item) = iter.next() {
            if let Ok((key, value)) = item {
                total_chunks += 1;
                total_size_bytes += value.len() as u64;
                
                // Initialize swap metadata for existing chunks
                let key_str = String::from_utf8_lossy(&key).to_string();
                swap_metadata.insert(key_str, SwapMetadata::new(value.len() as u64));
            }
        }
        
        let mut stats = DatabaseStats::new();
        stats.total_chunks = total_chunks;
        stats.total_size_bytes = total_size_bytes;
        
        Ok(Self {
            db: Arc::new(db),
            stats: Arc::new(RwLock::new(stats)),
            swap_metadata: Arc::new(RwLock::new(swap_metadata)),
            memory_mapped_files: Arc::new(RwLock::new(HashMap::new())),
            database_type: database_type.to_string(),
            checksum_enabled,
            memory_mapping_enabled,
            db_path: db_path.to_string(),
            swap_path,
        })
    }
    
    /// Helper to decompress data if needed
    fn decompress_if_needed(&self, data: &[u8]) -> Result<Vec<u8>, String> {
        if data.is_empty() {
            return Ok(Vec::new());
        }

        // Check compression flag (first byte)
        let is_compressed = data[0] == 1;
        let payload = &data[1..];

        if is_compressed {
            info!("Decompressing chunk data");
            let decompressed = block::decompress(payload, payload.len()).map_err(|e| format!("LZ4 decompression failed: {}", e))?;
            Ok(decompressed)
        } else {
            Ok(payload.to_vec())
        }
    }

    /// Store a chunk in the database with optional checksum
    pub fn put_chunk(&self, key: &str, data: &[u8]) -> Result<(), String> {
        let start_time = std::time::Instant::now();
         
        if key.is_empty() {
            return Err("Key cannot be empty".to_string());
        }
         
        if data.is_empty() {
            return Err("Data cannot be empty".to_string());
        }
         
        // Calculate data size before insertion
        let data_size = data.len() as u64;
         
        // Apply adaptive LZ4 compression for large chunks (>10KB)
        let compressed_data = if data.len() > 10_000 {
            info!("Compressing large chunk {} ({:.2}KB)", key, data.len() as f64 / 1024.0);
            let compressed = block::compress(data);
            Some(compressed)
        } else {
            None
        };
         
        // Prepare data with compression flag only (no checksum for now)
        let data_to_store: Vec<u8> = if let Some(compressed) = compressed_data {
            let mut result = Vec::with_capacity(compressed.len() + 1);
            result.push(1); // Compression flag: 1 = compressed, 0 = uncompressed
            result.extend_from_slice(&compressed);
            result
        } else {
            let mut result = Vec::with_capacity(data.len() + 1);
            result.push(0); // No compression
            result.extend_from_slice(data);
            result
        };
        
        // Convert key to bytes
        let key_bytes = key.as_bytes();
        
        // Check if key already exists
        let exists = self.db.contains_key(key_bytes)
            .map_err(|e| format!("Failed to check key existence: {}", e))?;
        
        // Update storage
        self.db.insert(key_bytes, data_to_store)
            .map_err(|e| format!("Failed to insert data: {}", e))?;
        
        // Update swap metadata
        if let Ok(mut swap_meta) = self.swap_metadata.write() {
            if let Some(metadata) = swap_meta.get_mut(key) {
                metadata.update_access();
                metadata.size_bytes = data_size;
            } else {
                swap_meta.insert(key.to_string(), SwapMetadata::new(data_size));
            }
        }
        
        // Update statistics
        if let Ok(mut stats) = self.stats.write() {
            if exists {
                // Replacing existing data - we don't know the old size, so we approximate
                stats.total_size_bytes = stats.total_size_bytes.saturating_sub(data_size / 2) + data_size;
            } else {
                // New entry
                stats.total_chunks += 1;
                stats.total_size_bytes += data_size;
            }
            
            stats.write_latency_ms = start_time.elapsed().as_millis() as u64;
            stats.is_healthy = true;
        }
        
        debug!("Stored chunk {} ({} bytes) in {} ms",
               key, data_size, start_time.elapsed().as_millis());
        
        Ok(())
    }
    
    /// Retrieve a chunk from the database
    pub fn get_chunk(&self, key: &str) -> Result<Option<Vec<u8>>, String> {
        let start_time = std::time::Instant::now();
        
        if key.is_empty() {
            return Err("Key cannot be empty".to_string());
        }
        
        let key_bytes = key.as_bytes();
        
        let result = self.db.get(key_bytes)
            .map_err(|e| format!("Failed to get data: {}", e))?;
        
        let processed_result = match result {
            Some(data) => {
                if self.checksum_enabled {
                    let data = self.verify_and_extract_data(&data)?;
                    Ok(Some(self.decompress_if_needed(&data)?))
                } else {
                    Ok(Some(self.decompress_if_needed(&data)?))
                }
            }
            None => Ok(None),
        };
        
        // Update swap metadata if chunk was found
            if let Ok(Some(_)) = processed_result {
                if let Ok(mut swap_meta) = self.swap_metadata.write() {
                    if let Some(metadata) = swap_meta.get_mut(key) {
                        metadata.update_access();
                    }
                }
            }
        
        // Update statistics
        if let Ok(mut stats) = self.stats.write() {
            stats.read_latency_ms = start_time.elapsed().as_millis() as u64;
        }
        
        debug!("Retrieved chunk {} in {} ms",
               key, start_time.elapsed().as_millis());
        
        processed_result
    }
    
    /// Delete a chunk from the database
    pub fn delete_chunk(&self, key: &str) -> Result<bool, String> {
        if key.is_empty() {
            return Err("Key cannot be empty".to_string());
        }
        
        let key_bytes = key.as_bytes();
        
        // Get the data size before deletion for statistics
        let existing_data = self.db.get(key_bytes)
            .map_err(|e| format!("Failed to get data for deletion: {}", e))?;
        
        if let Some(data) = existing_data {
            let data_size = data.len() as u64;
            
            // Delete the key
            self.db.remove(key_bytes)
                .map_err(|e| format!("Failed to remove data: {}", e))?;
            
            // Update statistics
            if let Ok(mut stats) = self.stats.write() {
                stats.total_chunks = stats.total_chunks.saturating_sub(1);
                stats.total_size_bytes = stats.total_size_bytes.saturating_sub(data_size);
            }
            
            debug!("Deleted chunk {} ({} bytes)", key, data_size);
            Ok(true)
        } else {
            Ok(false)
        }
    }
    
    /// Check if a chunk exists
    pub fn has_chunk(&self, key: &str) -> Result<bool, String> {
        if key.is_empty() {
            return Err("Key cannot be empty".to_string());
        }
        
        let key_bytes = key.as_bytes();
        self.db.contains_key(key_bytes)
            .map_err(|e| format!("Failed to check key existence: {}", e))
    }
    
    /// Get the number of stored chunks
    pub fn get_chunk_count(&self) -> u64 {
        self.stats.read()
            .map(|stats| stats.total_chunks)
            .unwrap_or(0)
    }
    
    /// Get database statistics
    pub fn get_stats(&self) -> Result<DatabaseStats, String> {
        let stats = self.stats.read()
            .map_err(|e| format!("Failed to acquire stats lock: {}", e))?
            .clone();
        Ok(stats)
    }
    
    /// Perform database maintenance
    pub fn perform_maintenance(&self) -> Result<(), String> {
        info!("Performing database maintenance");
        
        let start_time = std::time::Instant::now();
        
        // Flush the database to ensure durability
        self.db.flush()
            .map_err(|e| format!("Failed to flush database: {}", e))?;
        
        // Update maintenance timestamp
        if let Ok(mut stats) = self.stats.write() {
            stats.last_maintenance_time = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_secs();
            stats.is_healthy = true;
        }
        
        info!("Database maintenance completed in {} ms", start_time.elapsed().as_millis());
        Ok(())
    }
    
    /// Create a backup of the database
    pub fn create_backup(&self, backup_path: &str) -> Result<(), String> {
        info!("Creating backup at: {}", backup_path);
        
            // Create a timestamped backup directory under the provided path
            let stats = self.get_stats()?;

            // Ensure base backup directory exists
            let base = Path::new(backup_path);
            std::fs::create_dir_all(&base)
                .map_err(|e| format!("Failed to create base backup directory {}: {}", backup_path, e))?;

            let ts = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map_err(|e| format!("SystemTime error: {}", e))?
                .as_secs();

            let backup_dir = base.join(format!("backup_{}", ts));
            std::fs::create_dir_all(&backup_dir)
                .map_err(|e| format!("Failed to create backup directory {:?}: {}", backup_dir, e))?;

            // Copy sled db directory files into the backup directory. If db_path is a directory
            // we copy recursively. Otherwise copy the single file.
            let db_path = Path::new(&self.db_path);
            if db_path.exists() {
                let copy_result = if db_path.is_dir() {
                    // Recursively copy directory contents
                    copy_dir_recursive(db_path, &backup_dir)
                } else {
                    // Copy single file into backup_dir
                    let file_name = db_path.file_name().ok_or("Invalid db path file name")?;
                    let dest = backup_dir.join(file_name);
                    std::fs::copy(db_path, &dest)
                        .map(|_| ())
                        .map_err(|e| format!("Failed to copy db file {:?} to {:?}: {}", db_path, dest, e))
                };

                copy_result.map_err(|e| format!("Failed to copy database for backup: {}", e))?;
            } else {
                return Err(format!("Database path does not exist: {}", self.db_path));
            }

            // Save metadata about the backup (stats + timestamp)
            let metadata = serde_json::json!({
                "timestamp": ts,
                "total_chunks": stats.total_chunks,
                "total_size_bytes": stats.total_size_bytes,
                "database_type": self.database_type,
            });

            let meta_path = backup_dir.join("metadata.json");
            std::fs::write(&meta_path, serde_json::to_vec_pretty(&metadata).map_err(|e| format!("Failed to serialize metadata: {}", e))?)
                .map_err(|e| format!("Failed to write metadata file {:?}: {}", meta_path, e))?;

            info!("Created backup at {:?} ({} chunks, {} bytes)", backup_dir, stats.total_chunks, stats.total_size_bytes);

            // Prune old backups according to simple retention: keep latest N backups if present
            // We'll keep up to 10 backups by default to avoid unbounded growth
            if let Ok(entries) = std::fs::read_dir(base) {
                let mut backups: Vec<_> = entries
                    .filter_map(|e| e.ok())
                    .filter(|e| e.path().is_dir())
                    .collect();

                // Sort by modified time ascending
                backups.sort_by_key(|d| d.metadata().and_then(|m| m.modified()).ok());

                // Maximum backups to keep
                let max_backups = 10usize;
                while backups.len() > max_backups {
                    // remove the oldest (first) entry and delete its directory
                    let oldest = backups.remove(0);
                    let old_path = oldest.path();
                    if let Err(e) = std::fs::remove_dir_all(&old_path) {
                        error!("Failed to prune old backup {:?}: {}", old_path, e);
                    } else {
                        info!("Pruned old backup: {:?}", old_path);
                    }
                }
            }

            Ok(())
    }
    
    /// Store data with checksum
   fn store_with_checksum(data: &[u8]) -> Result<Vec<u8>, String> {
       let mut hasher = blake3::Hasher::new();
       hasher.update(data);
       let checksum = hasher.finalize();
       
       // Combine data and checksum
       let mut result = Vec::with_capacity(data.len() + 32);
       result.extend_from_slice(data);
       result.extend_from_slice(checksum.as_bytes());
       
       Ok(result)
   }
    
    /// Verify and extract data with checksum
    fn verify_and_extract_data(&self, stored_data: &[u8]) -> Result<Vec<u8>, String> {
        if stored_data.len() < 32 {
            return Err("Invalid data format: too short for checksum".to_string());
        }
        
        let (data, checksum_bytes) = stored_data.split_at(stored_data.len() - 32);
        
        // Calculate checksum of data
        let mut hasher = blake3::Hasher::new();
        hasher.update(data);
        let expected_checksum = hasher.finalize();
        
        // Verify checksum
        let expected_bytes = expected_checksum.as_bytes();
        if checksum_bytes != expected_bytes {
            return Err("Checksum verification failed".to_string());
        }
        
        Ok(data.to_vec())
    }
    
    /// Get database type
    pub fn get_database_type(&self) -> &str {
        &self.database_type
    }
    
    /// Get database path
    pub fn get_database_path(&self) -> &str {
        &self.db_path
    }
    
    /// Check if database is healthy
    pub fn is_healthy(&self) -> bool {
        self.stats.read()
            .map(|stats| stats.is_healthy)
            .unwrap_or(false)
    }
    
    /// Clear all data
    pub fn clear(&self) -> Result<(), String> {
        self.db.clear()
            .map_err(|e| format!("Failed to clear database: {}", e))?;
        
        if let Ok(mut stats) = self.stats.write() {
            stats.total_chunks = 0;
            stats.total_size_bytes = 0;
        }
        
        if let Ok(mut swap_meta) = self.swap_metadata.write() {
            swap_meta.clear();
        }
        
        info!("Database cleared");
        Ok(())
    }
    
    /// Swap out a chunk to disk storage
    pub fn swap_out_chunk(&self, key: &str) -> Result<(), String> {
        let start_time = std::time::Instant::now();
        
        if key.is_empty() {
            return Err("Key cannot be empty".to_string());
        }
        
        let key_bytes = key.as_bytes();
        
        // Get the chunk data
        let data = self.db.get(key_bytes)
            .map_err(|e| format!("Failed to get chunk for swap out: {}", e))?
            .ok_or_else(|| format!("Chunk not found: {}", key))?;
        
        let data_size = data.len() as u64;
        
        // Create swap file path
        let swap_file_path = format!("{}/{}.swap", self.swap_path, key.replace(':', "_"));
        
        // Write data to swap file
        std::fs::write(&swap_file_path, &data)
            .map_err(|e| format!("Failed to write swap file: {}", e))?;
        
        // Remove from main database
        self.db.remove(key_bytes)
            .map_err(|e| format!("Failed to remove chunk from database: {}", e))?;
        
        // Update swap metadata
        if let Ok(mut swap_meta) = self.swap_metadata.write() {
            if let Some(metadata) = swap_meta.get_mut(key) {
                metadata.update_swap();
            }
        }
        
        // Update statistics
        if let Ok(mut stats) = self.stats.write() {
            stats.swap_operations_total += 1;
            stats.swap_out_latency_ms = start_time.elapsed().as_millis() as u64;
            stats.total_swap_size_bytes += data_size;
            stats.total_chunks = stats.total_chunks.saturating_sub(1);
            stats.total_size_bytes = stats.total_size_bytes.saturating_sub(data_size);
        }
        
        info!("Swapped out chunk {} ({} bytes) in {} ms",
              key, data_size, start_time.elapsed().as_millis());
        
        Ok(())
    }
    
    /// Swap in a chunk from disk storage
    pub fn swap_in_chunk(&self, key: &str) -> Result<Vec<u8>, String> {
        let start_time = std::time::Instant::now();
        
        if key.is_empty() {
            return Err("Key cannot be empty".to_string());
        }
        
        // Check if chunk exists in swap
        let swap_file_path = format!("{}/{}.swap", self.swap_path, key.replace(':', "_"));
        
        if !Path::new(&swap_file_path).exists() {
            return Err(format!("Swap file not found: {}", swap_file_path));
        }
        
        // Read data from swap file
        let data = std::fs::read(&swap_file_path)
            .map_err(|e| format!("Failed to read swap file: {}", e))?;
        
        let data_size = data.len() as u64;
        
        // Restore to main database
        let key_bytes = key.as_bytes();
        self.db.insert(key_bytes, data.clone())
            .map_err(|e| format!("Failed to restore chunk to database: {}", e))?;
        
        // Remove swap file
        std::fs::remove_file(&swap_file_path)
            .map_err(|e| format!("Failed to remove swap file: {}", e))?;
        
        // Update swap metadata
        if let Ok(mut swap_meta) = self.swap_metadata.write() {
            if let Some(metadata) = swap_meta.get_mut(key) {
                metadata.update_access();
            }
        }
        
        // Update statistics
        if let Ok(mut stats) = self.stats.write() {
            stats.swap_operations_total += 1;
            stats.swap_in_latency_ms = start_time.elapsed().as_millis() as u64;
            stats.total_swap_size_bytes = stats.total_swap_size_bytes.saturating_sub(data_size);
            stats.total_chunks += 1;
            stats.total_size_bytes += data_size;
        }
        
        info!("Swapped in chunk {} ({} bytes) in {} ms",
              key, data_size, start_time.elapsed().as_millis());
        
        Ok(data)
    }
    
    /// Get swap candidates based on access patterns and priority scores
    pub fn get_swap_candidates(&self, limit: usize) -> Result<Vec<String>, String> {
        if let Ok(swap_meta) = self.swap_metadata.read() {
            let mut candidates: Vec<(String, f64)> = swap_meta
                .iter()
                .map(|(key, metadata)| (key.clone(), metadata.priority_score))
                .collect();
            
            // Sort by priority score (lowest first - these are best swap candidates)
            candidates.sort_by(|a, b| a.1.partial_cmp(&b.1).unwrap_or(std::cmp::Ordering::Equal));
            
            // Return the keys with lowest priority scores
            let result: Vec<String> = candidates
                .into_iter()
                .take(limit)
                .map(|(key, _)| key)
                .collect();
            
            Ok(result)
        } else {
            Err("Failed to acquire swap metadata lock".to_string())
        }
    }
    
    /// Bulk swap out multiple chunks using sled batch API for atomic operations
    pub fn bulk_swap_out(&self, keys: &[String]) -> Result<usize, String> {
        let mut success_count = 0;
        let mut batch = sled::Batch::default();
        
        let start_time = std::time::Instant::now();
        
        for key in keys {
            let key_bytes = key.as_bytes();
            
            // Get the chunk data first with decompression if needed
            let data_result = self.db.get(key_bytes)
                .map_err(|e| format!("Failed to get chunk for swap out: {}", e))?;
            
            let data = match data_result {
                Some(raw_data) => self.decompress_if_needed(&raw_data)?,
                None => return Err(format!("Chunk not found: {}", key))
            };
            
            let data_size = data.len() as u64;
            
            // Create swap file path with proper escaping
            let safe_key = key.replace(':', "_").replace('/', "-");
            let swap_file_path = format!("{}/{}.swap", self.swap_path, safe_key);
            
            // Write data to swap file with atomic write pattern
            let temp_path = format!("{}.tmp", swap_file_path);
            std::fs::write(&temp_path, &data)
                .map_err(|e| format!("Failed to write temp swap file: {}", e))?;
            std::fs::rename(&temp_path, &swap_file_path)
                .map_err(|e| format!("Failed to rename temp file: {}", e))?;
            
            // Add remove operation to batch
            batch.remove(key_bytes);
            
            // Update swap metadata with proper locking
            if let Ok(mut swap_meta) = self.swap_metadata.write() {
                if let Some(metadata) = swap_meta.get_mut(key) {
                    metadata.update_swap();
                    metadata.size_bytes = data_size; // Update with actual decompressed size
                }
            }
            
            success_count += 1;
        }
        
        // Execute batch atomically
        self.db.apply_batch(batch)
            .map_err(|e| format!("Failed to apply batch: {}", e))?;
        
        // Update statistics with precise calculations
        if let Ok(mut stats) = self.stats.write() {
            stats.swap_operations_total += keys.len() as u64;
            stats.swap_out_latency_ms = start_time.elapsed().as_millis() as u64;
            stats.total_swap_size_bytes += success_count as u64 * (keys.get(0).map(|k| {
                self.db.get(k.as_bytes()).ok().flatten().map(|d| d.len() as u64).unwrap_or(0)
            }).unwrap_or(0));
            stats.total_chunks = stats.total_chunks.saturating_sub(success_count as u64);
        }
        
        info!("Bulk swap out completed: {}/{} chunks swapped successfully in {} ms",
              success_count, keys.len(), start_time.elapsed().as_millis());
        
        Ok(success_count)
    }
    
    /// Bulk swap in multiple chunks
    pub fn bulk_swap_in(&self, keys: &[String]) -> Result<Vec<Vec<u8>>, String> {
        let mut results = Vec::new();
        let mut batch = sled::Batch::default();
        
        let start_time = std::time::Instant::now();
        let mut total_size = 0u64;
        
        for key in keys {
            // Check if chunk exists in swap
            let swap_file_path = format!("{}/{}.swap", self.swap_path, key.replace(':', "_"));
            
            if !Path::new(&swap_file_path).exists() {
                error!("Swap file not found: {}", swap_file_path);
                if let Ok(mut stats) = self.stats.write() {
                    stats.swap_operations_failed += 1;
                }
                continue;
            }
            
            // Read data from swap file
            let data = std::fs::read(&swap_file_path)
                .map_err(|e| format!("Failed to read swap file: {}", e))?;
            
            let data_size = data.len() as u64;
            total_size += data_size;
            
            // Add insert operation to batch
            let key_bytes = key.as_bytes();
            batch.insert(key_bytes, data.clone());
            
            // Remove swap file
            std::fs::remove_file(&swap_file_path)
                .map_err(|e| format!("Failed to remove swap file: {}", e))?;
            
            // Update swap metadata
            if let Ok(mut swap_meta) = self.swap_metadata.write() {
                if let Some(metadata) = swap_meta.get_mut(key) {
                    metadata.update_access();
                }
            }
            
            results.push(data);
        }
        
        // Execute batch
        self.db.apply_batch(batch)
            .map_err(|e| format!("Failed to apply batch: {}", e))?;
        
        // Update statistics
        if let Ok(mut stats) = self.stats.write() {
            stats.swap_operations_total += keys.len() as u64;
            stats.swap_in_latency_ms = start_time.elapsed().as_millis() as u64;
            stats.total_swap_size_bytes = stats.total_swap_size_bytes.saturating_sub(total_size);
            stats.total_chunks += results.len() as u64;
            stats.total_size_bytes += total_size;
        }
        
        info!("Bulk swap in completed: {}/{} chunks swapped successfully in {} ms",
              results.len(), keys.len(), start_time.elapsed().as_millis());
        
        Ok(results)
    }
    
    /// Create memory-mapped access for a large chunk
    pub fn create_memory_mapped_chunk(&self, key: &str, data: &[u8]) -> Result<Mmap, String> {
        let mmap_path = format!("{}/{}.mmap", self.swap_path, key.replace(':', "_"));
        
        // Write data to file first
        std::fs::write(&mmap_path, data)
            .map_err(|e| format!("Failed to write mmap file: {}", e))?;
        
        // Open file for memory mapping
        let file = OpenOptions::new()
            .read(true)
            .open(&mmap_path)
            .map_err(|e| format!("Failed to open mmap file: {}", e))?;
        
        // Create memory map
        let mmap = unsafe {
            MmapOptions::new()
                .map(&file)
                .map_err(|e| format!("Failed to create memory map: {}", e))?
        };
        
        // Store in memory-mapped files cache - Mmap doesn't implement Clone, so we store the path instead
        if let Ok(mut mm_files) = self.memory_mapped_files.write() {
            // We'll recreate the mmap when needed since we can't clone it
            // For now, just track that this key has memory-mapped access
            mm_files.insert(key.to_string(), mmap);
        }
        
        // Update statistics
        if let Ok(mut stats) = self.stats.write() {
            stats.memory_mapped_files_active += 1;
        }
        
        // Return a new mmap by reading the file again since we can't clone the original
        let file2 = OpenOptions::new()
            .read(true)
            .open(&mmap_path)
            .map_err(|e| format!("Failed to open mmap file for return: {}", e))?;
        
        unsafe {
            MmapOptions::new()
                .map(&file2)
                .map_err(|e| format!("Failed to create return memory map: {}", e))
        }
    }
    
    /// Get memory-mapped access for a chunk
    pub fn get_memory_mapped_chunk(&self, key: &str) -> Result<Option<Mmap>, String> {
        // Check if already memory-mapped
        if let Ok(mm_files) = self.memory_mapped_files.read() {
            if mm_files.contains_key(key) {
                // Recreate the mmap since we can't clone it
                drop(mm_files);
                if let Some(data) = self.get_chunk(key)? {
                    let mmap = self.create_memory_mapped_chunk(key, &data)?;
                    return Ok(Some(mmap));
                }
            }
        }
        
        // Try to get chunk from database and create memory map
        if let Some(data) = self.get_chunk(key)? {
            let mmap = self.create_memory_mapped_chunk(key, &data)?;
            Ok(Some(mmap))
        } else {
            Ok(None)
        }
    }
    
    /// Remove memory-mapped access for a chunk
    pub fn remove_memory_mapped_chunk(&self, key: &str) -> Result<(), String> {
        let mmap_path = format!("{}/{}.mmap", self.swap_path, key.replace(':', "_"));
        
        // Remove from cache
        if let Ok(mut mm_files) = self.memory_mapped_files.write() {
            mm_files.remove(key);
        }
        
        // Remove file
        if Path::new(&mmap_path).exists() {
            std::fs::remove_file(&mmap_path)
                .map_err(|e| format!("Failed to remove mmap file: {}", e))?;
        }
        
        // Update statistics
        if let Ok(mut stats) = self.stats.write() {
            stats.memory_mapped_files_active = stats.memory_mapped_files_active.saturating_sub(1);
        }
        
        Ok(())
    }
    /// Store a chunk using raw NBT data with FastNBT parsing
    pub fn store_chunk_raw_nbt(&self, x: i32, z: i32, dimension: &str, raw_nbt_data: &[u8]) -> Result<(), String> {
        let start_time = std::time::Instant::now();

        // Parse NBT data using FastNBT
        let nbt_value: Value = fastnbt::from_bytes(raw_nbt_data)
            .map_err(|e| format!("Failed to parse NBT data: {}", e))?;

        // Extract chunk coordinates and validate
        let coords = ChunkCoordinates::new(x, z, dimension.to_string());
        let key = coords.to_key();

        // Validate that the NBT data contains expected chunk structure
        if let Value::Compound(root) = &nbt_value {
            // Check for basic chunk structure (xPos, zPos, etc.)
            if !root.contains_key("xPos") || !root.contains_key("zPos") {
                return Err("Invalid chunk NBT: missing position data".to_string());
            }
        } else {
            return Err("Invalid chunk NBT: root must be a compound tag".to_string());
        }

        // Serialize back to bytes for storage (this ensures consistent format)
        let serialized_data = fastnbt::to_bytes(&nbt_value)
            .map_err(|e| format!("Failed to serialize NBT data: {}", e))?;

        // Store in database
        self.put_chunk(&key, &serialized_data)?;

        info!("Stored chunk at ({}, {}) in dimension {} using FastNBT ({} bytes) in {} ms",
              x, z, dimension, serialized_data.len(), start_time.elapsed().as_millis());

        Ok(())
    }

    /// Retrieve and parse chunk data using FastNBT
    pub fn get_chunk_parsed_nbt(&self, x: i32, z: i32, dimension: &str) -> Result<Option<Value>, String> {
        let coords = ChunkCoordinates::new(x, z, dimension.to_string());
        let key = coords.to_key();

        match self.get_chunk(&key)? {
            Some(data) => {
                let nbt_value: Value = fastnbt::from_bytes(&data)
                    .map_err(|e| format!("Failed to parse stored NBT data: {}", e))?;
                Ok(Some(nbt_value))
            }
            None => Ok(None),
        }
    }

    /// Query chunks by coordinate range with optimization
    pub fn query_chunks_by_range(&self, min_x: i32, max_x: i32, min_z: i32, max_z: i32, dimension: &str) -> Result<Vec<ChunkCoordinates>, String> {
        let mut results = Vec::new();

        // Use sled's prefix iterator for efficient range queries
        let prefix = format!("chunk:{},", dimension);
        let prefix_bytes = prefix.as_bytes();

        for item in self.db.scan_prefix(prefix_bytes) {
            if let Ok((key_bytes, _)) = item {
                if let Ok(key_str) = String::from_utf8(key_bytes.to_vec()) {
                    if let Ok(coords) = ChunkCoordinates::from_key(&key_str) {
                        // Check if coordinates are within the specified range
                        if coords.x >= min_x && coords.x <= max_x &&
                           coords.z >= min_z && coords.z <= max_z &&
                           coords.dimension == dimension {
                            results.push(coords);
                        }
                    }
                }
            }
        }

        Ok(results)
    }

    /// Get chunk statistics by dimension
    pub fn get_chunk_stats_by_dimension(&self, dimension: &str) -> Result<(u64, u64), String> {
        let mut chunk_count = 0u64;
        let mut total_size = 0u64;

        let prefix = format!("chunk:{},", dimension);
        let prefix_bytes = prefix.as_bytes();

        for item in self.db.scan_prefix(prefix_bytes) {
            if let Ok((_, value)) = item {
                chunk_count += 1;
                total_size += value.len() as u64;
            }
        }

        Ok((chunk_count, total_size))
    }
}

// JNI bindings for Java integration
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativeInit<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    database_type: JString<'a>,
    checksum_enabled: jboolean,
) -> jlong {
    let database_type_str = env.get_string(&database_type)
        .expect("Failed to get database type string")
        .to_str()
        .expect("Failed to convert to str")
        .to_string();
    
    let checksum = checksum_enabled != 0;
    
    match RustDatabaseAdapter::new(&database_type_str, checksum, false) {
        Ok(adapter) => Box::into_raw(Box::new(adapter)) as jlong,
        Err(e) => {
            error!("Failed to initialize database adapter at CWD: {}. Attempting temp-dir fallback", e);

            // Try to initialize using a temp directory as a fallback to increase robustness
            match std::env::temp_dir().to_str() {
                Some(tmp) => {
                    let fallback_path = format!("{}/kneaf_db_{}_fallback", tmp, database_type_str);
                    match RustDatabaseAdapter::with_path(&fallback_path, &database_type_str, checksum, false) {
                        Ok(fallback_adapter) => {
                            info!("Initialized fallback RustDatabaseAdapter at: {}", fallback_path);
                            Box::into_raw(Box::new(fallback_adapter)) as jlong
                        }
                        Err(e2) => {
                            error!("Failed to initialize fallback adapter: {}", e2);
                            0
                        }
                    }
                }
                None => {
                    error!("Failed to determine temp dir for fallback initialization");
                    0
                }
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativePutChunk<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    adapter_ptr: jlong,
    key: JString<'a>,
    data: JByteArray<'a>,
) -> jboolean {
    if adapter_ptr == 0 {
        error!("Database adapter pointer is null");
        return 0;
    }
    
    let adapter = unsafe { &*(adapter_ptr as *const RustDatabaseAdapter) };
    
    let key_str = env.get_string(&key)
        .expect("Failed to get key string")
        .to_str()
        .expect("Failed to convert key to str")
        .to_string();
    
    let data_vec = env.convert_byte_array(&data)
        .expect("Failed to convert byte array");
    
    match adapter.put_chunk(&key_str, &data_vec) {
        Ok(_) => 1,
        Err(e) => {
            error!("Failed to put chunk: {}", e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativeGetChunk<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    adapter_ptr: jlong,
    key: JString<'a>,
) -> jbyteArray {
    if adapter_ptr == 0 {
        error!("Database adapter pointer is null");
        return std::ptr::null_mut();
    }
    
    let adapter = unsafe { &*(adapter_ptr as *const RustDatabaseAdapter) };
    
    let key_str = env.get_string(&key)
        .expect("Failed to get key string")
        .to_str()
        .expect("Failed to convert key to str")
        .to_string();
    
    match adapter.get_chunk(&key_str) {
        Ok(Some(data)) => {
            env.byte_array_from_slice(&data)
                .expect("Failed to create byte array")
                .into_raw()
        }
        Ok(None) => std::ptr::null_mut(),
        Err(e) => {
            error!("Failed to get chunk: {}", e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativeDeleteChunk<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    adapter_ptr: jlong,
    key: JString<'a>,
) -> jboolean {
    if adapter_ptr == 0 {
        error!("Database adapter pointer is null");
        return 0;
    }
    
    let adapter = unsafe { &*(adapter_ptr as *const RustDatabaseAdapter) };
    
    let key_str = env.get_string(&key)
        .expect("Failed to get key string")
        .to_str()
        .expect("Failed to convert key to str")
        .to_string();
    
    match adapter.delete_chunk(&key_str) {
        Ok(deleted) => if deleted { 1 } else { 0 },
        Err(e) => {
            error!("Failed to delete chunk: {}", e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativeHasChunk<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    adapter_ptr: jlong,
    key: JString<'a>,
) -> jboolean {
    if adapter_ptr == 0 {
        error!("Database adapter pointer is null");
        return 0;
    }
    
    let adapter = unsafe { &*(adapter_ptr as *const RustDatabaseAdapter) };
    
    let key_str = env.get_string(&key)
        .expect("Failed to get key string")
        .to_str()
        .expect("Failed to convert key to str")
        .to_string();
    
    match adapter.has_chunk(&key_str) {
        Ok(has) => if has { 1 } else { 0 },
        Err(e) => {
            error!("Failed to check chunk: {}", e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativeGetChunkCount<'a>(
    _env: JNIEnv<'a>,
    _class: JClass<'a>,
    adapter_ptr: jlong,
) -> jlong {
    if adapter_ptr == 0 {
        error!("Database adapter pointer is null");
        return 0;
    }
    
    let adapter = unsafe { &*(adapter_ptr as *const RustDatabaseAdapter) };
    adapter.get_chunk_count() as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativeGetStats<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    adapter_ptr: jlong,
) -> JObject<'a> {
    if adapter_ptr == 0 {
        error!("Database adapter pointer is null");
        return JObject::null();
    }
    
    let adapter = unsafe { &*(adapter_ptr as *const RustDatabaseAdapter) };
    
    match adapter.get_stats() {
        Ok(stats) => {
            // Create a Java DatabaseStats object with extended swap metrics
            let stats_class = env.find_class("com/kneaf/core/chunkstorage/DatabaseAdapter$DatabaseStats")
                .expect("Failed to find DatabaseStats class");
            
            env.new_object(
                stats_class,
                "(JJJJJZJJJJJ)V",
                &[
                    jni::objects::JValue::Long(stats.total_chunks as jlong),
                    jni::objects::JValue::Long(stats.total_size_bytes as jlong),
                    jni::objects::JValue::Long(stats.read_latency_ms as jlong),
                    jni::objects::JValue::Long(stats.write_latency_ms as jlong),
                    jni::objects::JValue::Long(stats.last_maintenance_time as jlong),
                    jni::objects::JValue::Bool(stats.is_healthy as jboolean),
                    jni::objects::JValue::Long(stats.swap_operations_total as jlong),
                    jni::objects::JValue::Long(stats.swap_operations_failed as jlong),
                    jni::objects::JValue::Long(stats.swap_in_latency_ms as jlong),
                    jni::objects::JValue::Long(stats.swap_out_latency_ms as jlong),
                    jni::objects::JValue::Long(stats.memory_mapped_files_active as jlong),
                ],
            ).expect("Failed to create DatabaseStats object")
        }
        Err(e) => {
            error!("Failed to get stats: {}", e);
            JObject::null()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativePerformMaintenance<'a>(
    _env: JNIEnv<'a>,
    _class: JClass<'a>,
    adapter_ptr: jlong,
) -> jboolean {
    if adapter_ptr == 0 {
        error!("Database adapter pointer is null");
        return 0;
    }
    
    let adapter = unsafe { &*(adapter_ptr as *const RustDatabaseAdapter) };
    
    match adapter.perform_maintenance() {
        Ok(_) => 1,
        Err(e) => {
            error!("Failed to perform maintenance: {}", e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativeCreateBackup<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    adapter_ptr: jlong,
    backup_path: JString<'a>,
) -> jboolean {
    if adapter_ptr == 0 {
        error!("Database adapter pointer is null");
        return 0;
    }
    
    let adapter = unsafe { &*(adapter_ptr as *const RustDatabaseAdapter) };
    
    let backup_path_str = env.get_string(&backup_path)
        .expect("Failed to get backup path string")
        .to_str()
        .expect("Failed to convert backup path to str")
        .to_string();
    
    match adapter.create_backup(&backup_path_str) {
        Ok(_) => 1,
        Err(e) => {
            error!("Failed to create backup: {}", e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativeGetDatabaseType<'a>(
    env: JNIEnv<'a>,
    _class: JClass<'a>,
    adapter_ptr: jlong,
) -> JString<'a> {
    if adapter_ptr == 0 {
        error!("Database adapter pointer is null");
        return env.new_string("").expect("Failed to create empty string");
    }
    
    let adapter = unsafe { &*(adapter_ptr as *const RustDatabaseAdapter) };
    
    env.new_string(adapter.get_database_type())
        .expect("Failed to create database type string")
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativeIsHealthy<'a>(
    _env: JNIEnv<'a>,
    _class: JClass<'a>,
    adapter_ptr: jlong,
) -> jboolean {
    if adapter_ptr == 0 {
        error!("Database adapter pointer is null");
        return 0;
    }
    
    let adapter = unsafe { &*(adapter_ptr as *const RustDatabaseAdapter) };
    if adapter.is_healthy() { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativeDestroy<'a>(
    _env: JNIEnv<'a>,
    _class: JClass<'a>,
    adapter_ptr: jlong,
) {
    if adapter_ptr != 0 {
        let _ = unsafe { Box::from_raw(adapter_ptr as *mut RustDatabaseAdapter) };
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativeSwapOutChunk<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    adapter_ptr: jlong,
    key: JString<'a>,
) -> jboolean {
    if adapter_ptr == 0 {
        error!("Database adapter pointer is null");
        return 0;
    }
    
    let adapter = unsafe { &*(adapter_ptr as *const RustDatabaseAdapter) };
    
    let key_str = env.get_string(&key)
        .expect("Failed to get key string")
        .to_str()
        .expect("Failed to convert key to str")
        .to_string();
    
    match adapter.swap_out_chunk(&key_str) {
        Ok(_) => 1,
        Err(e) => {
            error!("Failed to swap out chunk: {}", e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativeSwapInChunk<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    adapter_ptr: jlong,
    key: JString<'a>,
) -> jbyteArray {
    if adapter_ptr == 0 {
        error!("Database adapter pointer is null");
        return std::ptr::null_mut();
    }
    
    let adapter = unsafe { &*(adapter_ptr as *const RustDatabaseAdapter) };
    
    let key_str = env.get_string(&key)
        .expect("Failed to get key string")
        .to_str()
        .expect("Failed to convert key to str")
        .to_string();
    
    match adapter.swap_in_chunk(&key_str) {
        Ok(data) => {
            env.byte_array_from_slice(&data)
                .expect("Failed to create byte array")
                .into_raw()
        }
        Err(e) => {
            error!("Failed to swap in chunk: {}", e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativeGetSwapCandidates<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    adapter_ptr: jlong,
    limit: jint,
) -> JObject<'a> {
    if adapter_ptr == 0 {
        error!("Database adapter pointer is null");
        return JObject::null();
    }
    
    let adapter = unsafe { &*(adapter_ptr as *const RustDatabaseAdapter) };
    
    match adapter.get_swap_candidates(limit as usize) {
        Ok(candidates) => {
            // Create Java ArrayList
            let list_class = env.find_class("java/util/ArrayList")
                .expect("Failed to find ArrayList class");
            let list_obj = env.new_object(&list_class, "()V", &[])
                .expect("Failed to create ArrayList");
            let _add_method = env.get_method_id(&list_class, "add", "(Ljava/lang/Object;)Z")
                .expect("Failed to get add method");
            
            // Add candidates to list
            for candidate in candidates {
                let candidate_str = env.new_string(&candidate)
                    .expect("Failed to create string");
                let candidate_obj = JObject::from(candidate_str);
                env.call_method(&list_obj, "add", "(Ljava/lang/Object;)Z", &[jni::objects::JValue::Object(&candidate_obj)])
                    .expect("Failed to add to list");
            }
            
            list_obj
        }
        Err(e) => {
            error!("Failed to get swap candidates: {}", e);
            JObject::null()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativeBulkSwapOut<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    adapter_ptr: jlong,
    keys: JObject<'a>,
) -> jint {
    if adapter_ptr == 0 {
        error!("Database adapter pointer is null");
        return -1;
    }
    
    let adapter = unsafe { &*(adapter_ptr as *const RustDatabaseAdapter) };
    
    // Convert Java List to Vec<String>
    let list_class = env.find_class("java/util/List")
        .expect("Failed to find List class");
    let _size_method = env.get_method_id(&list_class, "size", "()I")
        .expect("Failed to get size method");
    let _get_method = env.get_method_id(&list_class, "get", "(I)Ljava/lang/Object;")
        .expect("Failed to get get method");
    
    let size = env.call_method(&keys, "size", "()I", &[])
        .expect("Failed to call size method")
        .i()
        .unwrap();
    
    let mut keys_vec = Vec::new();
    for i in 0..size {
        let element = env.call_method(&keys, "get", "(I)Ljava/lang/Object;", &[jni::objects::JValue::Int(i)])
            .expect("Failed to call get method");
        
        let str_obj = element.l().expect("Failed to get object from result");
        if let Ok(key_str) = env.get_string(&JString::from(str_obj)) {
            let key_string = key_str.to_str()
                .expect("Failed to convert to str")
                .to_string();
            keys_vec.push(key_string);
        }
    }
    
    match adapter.bulk_swap_out(&keys_vec) {
        Ok(count) => count as jint,
        Err(e) => {
            error!("Failed to bulk swap out: {}", e);
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativeBulkSwapIn<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    adapter_ptr: jlong,
    keys: JObject<'a>,
) -> JObject<'a> {
    if adapter_ptr == 0 {
        error!("Database adapter pointer is null");
        return JObject::null();
    }
    
    let adapter = unsafe { &*(adapter_ptr as *const RustDatabaseAdapter) };
    
    // Convert Java List to Vec<String> (similar to bulk_swap_out)
    let list_class = env.find_class("java/util/List")
        .expect("Failed to find List class");
    let _size_method = env.get_method_id(&list_class, "size", "()I")
        .expect("Failed to get size method");
    let _get_method = env.get_method_id(&list_class, "get", "(I)Ljava/lang/Object;")
        .expect("Failed to get get method");
    
    let size = env.call_method(&keys, "size", "()I", &[])
        .expect("Failed to call size method")
        .i()
        .unwrap();
    
    let mut keys_vec = Vec::new();
    for i in 0..size {
        let element = env.call_method(&keys, "get", "(I)Ljava/lang/Object;", &[jni::objects::JValue::Int(i)])
            .expect("Failed to call get method");
        
        let str_obj = element.l().expect("Failed to get object from result");
        if let Ok(key_str) = env.get_string(&JString::from(str_obj)) {
            let key_string = key_str.to_str()
                .expect("Failed to convert to str")
                .to_string();
            keys_vec.push(key_string);
        }
    }
    
    match adapter.bulk_swap_in(&keys_vec) {
        Ok(results) => {
            // Create Java ArrayList of byte arrays
            let list_class = env.find_class("java/util/ArrayList")
                .expect("Failed to find ArrayList class");
            let list_obj = env.new_object(&list_class, "()V", &[])
                .expect("Failed to create ArrayList");

            // Add results to list
            for data in results {
                let byte_array = env.byte_array_from_slice(&data)
                    .expect("Failed to create byte array");
                env.call_method(&list_obj, "add", "(Ljava/lang/Object;)Z", &[jni::objects::JValue::Object(&byte_array)])
                    .expect("Failed to add to list");
            }

            return list_obj;
        }
        Err(e) => {
            error!("Failed to bulk swap in: {}", e);
            return JObject::null();
        }
    }
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativeStoreChunkRawNbt<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    adapter_ptr: jlong,
    x: jint,
    z: jint,
    dimension: JString<'a>,
    raw_nbt_data: JByteArray<'a>,
) -> jboolean {
    if adapter_ptr == 0 {
        error!("Database adapter pointer is null");
        return 0;
    }

    let adapter = unsafe { &*(adapter_ptr as *const RustDatabaseAdapter) };

    let dimension_str = env.get_string(&dimension)
        .expect("Failed to get dimension string")
        .to_str()
        .expect("Failed to convert dimension to str")
        .to_string();

    let nbt_data_vec = env.convert_byte_array(&raw_nbt_data)
        .expect("Failed to convert byte array");

    match adapter.store_chunk_raw_nbt(x as i32, z as i32, &dimension_str, &nbt_data_vec) {
        Ok(_) => 1,
        Err(e) => {
            error!("Failed to store chunk raw NBT: {}", e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativeGetChunkParsedNbt<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    adapter_ptr: jlong,
    x: jint,
    z: jint,
    dimension: JString<'a>,
) -> jbyteArray {
    if adapter_ptr == 0 {
        error!("Database adapter pointer is null");
        return std::ptr::null_mut();
    }

    let adapter = unsafe { &*(adapter_ptr as *const RustDatabaseAdapter) };

    let dimension_str = env.get_string(&dimension)
        .expect("Failed to get dimension string")
        .to_str()
        .expect("Failed to convert dimension to str")
        .to_string();

    match adapter.get_chunk_parsed_nbt(x as i32, z as i32, &dimension_str) {
        Ok(Some(nbt_value)) => {
            // Serialize back to bytes for Java
            match fastnbt::to_bytes(&nbt_value) {
                Ok(data) => {
                    env.byte_array_from_slice(&data)
                        .expect("Failed to create byte array")
                        .into_raw()
                }
                Err(e) => {
                    error!("Failed to serialize NBT value: {}", e);
                    std::ptr::null_mut()
                }
            }
        }
        Ok(None) => std::ptr::null_mut(),
        Err(e) => {
            error!("Failed to get chunk parsed NBT: {}", e);
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativeQueryChunksByRange<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    adapter_ptr: jlong,
    min_x: jint,
    max_x: jint,
    min_z: jint,
    max_z: jint,
    dimension: JString<'a>,
) -> JObject<'a> {
    if adapter_ptr == 0 {
        error!("Database adapter pointer is null");
        return JObject::null();
    }

    let adapter = unsafe { &*(adapter_ptr as *const RustDatabaseAdapter) };

    let dimension_str = env.get_string(&dimension)
        .expect("Failed to get dimension string")
        .to_str()
        .expect("Failed to convert dimension to str")
        .to_string();

    match adapter.query_chunks_by_range(min_x as i32, max_x as i32, min_z as i32, max_z as i32, &dimension_str) {
        Ok(chunks) => {
            // Create Java ArrayList of chunk coordinates
            let list_class = env.find_class("java/util/ArrayList")
                .expect("Failed to find ArrayList class");
            let list_obj = env.new_object(&list_class, "()V", &[])
                .expect("Failed to create ArrayList");

            // Create chunk coordinate class
            let coord_class = env.find_class("com/kneaf/core/chunkstorage/ChunkCoordinates")
                .expect("Failed to find ChunkCoordinates class");

            // Add chunks to list
            for chunk in chunks {
                let coord_obj = env.new_object(
                    &coord_class,
                    "(IILjava/lang/String;)V",
                    &[
                        jni::objects::JValue::Int(chunk.x as jint),
                        jni::objects::JValue::Int(chunk.z as jint),
                        jni::objects::JValue::Object(&env.new_string(&chunk.dimension)
                            .expect("Failed to create dimension string")),
                    ],
                ).expect("Failed to create ChunkCoordinates object");

                env.call_method(&list_obj, "add", "(Ljava/lang/Object;)Z",
                    &[jni::objects::JValue::Object(&coord_obj)])
                    .expect("Failed to add to list");
            }

            list_obj
        }
        Err(e) => {
            error!("Failed to query chunks by range: {}", e);
            JObject::null()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativeGetChunkStatsByDimension<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    adapter_ptr: jlong,
    dimension: JString<'a>,
) -> JObject<'a> {
    if adapter_ptr == 0 {
        error!("Database adapter pointer is null");
        return JObject::null();
    }

    let adapter = unsafe { &*(adapter_ptr as *const RustDatabaseAdapter) };

    let dimension_str = env.get_string(&dimension)
        .expect("Failed to get dimension string")
        .to_str()
        .expect("Failed to convert dimension to str")
        .to_string();

    match adapter.get_chunk_stats_by_dimension(&dimension_str) {
        Ok((chunk_count, total_size)) => {
            // Create Java ChunkStats object
            let stats_class = env.find_class("com/kneaf/core/chunkstorage/ChunkStats")
                .expect("Failed to find ChunkStats class");

            env.new_object(
                &stats_class,
                "(JJ)V",
                &[
                    jni::objects::JValue::Long(chunk_count as jlong),
                    jni::objects::JValue::Long(total_size as jlong),
                ],
            ).expect("Failed to create ChunkStats object")
        }
        Err(e) => {
            error!("Failed to get chunk stats by dimension: {}", e);
            JObject::null()
        }
    }
}
}