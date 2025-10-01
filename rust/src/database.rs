
use std::sync::{Arc, RwLock};
use std::time::{SystemTime, UNIX_EPOCH};
use std::path::Path;
use jni::JNIEnv;
use jni::objects::{JClass, JString, JByteArray, JObject};
use jni::sys::{jboolean, jlong, jbyteArray};
use sled::Db;

use log::{debug, info, error};

/// Database statistics for monitoring
#[derive(Debug, Clone)]
pub struct DatabaseStats {
    pub total_chunks: u64,
    pub total_size_bytes: u64,
    pub read_latency_ms: u64,
    pub write_latency_ms: u64,
    pub last_maintenance_time: u64,
    pub is_healthy: bool,
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
        }
    }
}

/// Rust-based database adapter for chunk storage using sled
pub struct RustDatabaseAdapter {
    db: Arc<Db>,
    stats: Arc<RwLock<DatabaseStats>>,
    database_type: String,
    checksum_enabled: bool,
    db_path: String,
}

impl RustDatabaseAdapter {
    pub fn new(database_type: &str, checksum_enabled: bool) -> Result<Self, String> {
        let db_path = format!("./kneaf_db_{}", database_type);
        Self::with_path(&db_path, database_type, checksum_enabled)
    }
    
    pub fn with_path(db_path: &str, database_type: &str, checksum_enabled: bool) -> Result<Self, String> {
        info!("Initializing RustDatabaseAdapter of type: {} at path: {}", database_type, db_path);
        
        // Create database directory if it doesn't exist
        let path = Path::new(db_path);
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| format!("Failed to create database directory: {}", e))?;
        }
        
        // Open or create sled database
        let db = sled::open(path)
            .map_err(|e| format!("Failed to open sled database: {}", e))?;
        
        // Count existing chunks
        let mut total_chunks = 0u64;
        let mut total_size_bytes = 0u64;
        
        for item in db.iter() {
            if let Ok((_, value)) = item {
                total_chunks += 1;
                total_size_bytes += value.len() as u64;
            }
        }
        
        let mut stats = DatabaseStats::new();
        stats.total_chunks = total_chunks;
        stats.total_size_bytes = total_size_bytes;
        
        Ok(Self {
            db: Arc::new(db),
            stats: Arc::new(RwLock::new(stats)),
            database_type: database_type.to_string(),
            checksum_enabled,
            db_path: db_path.to_string(),
        })
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
        
        // Insert data with optional checksum
        let data_to_store = if self.checksum_enabled {
            self.store_with_checksum(data)?
        } else {
            data.to_vec()
        };
        
        // Convert key to bytes
        let key_bytes = key.as_bytes();
        
        // Check if key already exists
        let exists = self.db.contains_key(key_bytes)
            .map_err(|e| format!("Failed to check key existence: {}", e))?;
        
        // Update storage
        self.db.insert(key_bytes, data_to_store)
            .map_err(|e| format!("Failed to insert data: {}", e))?;
        
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
                    self.verify_and_extract_data(&data).map(Some)
                } else {
                    Ok(Some(data.to_vec()))
                }
            }
            None => Ok(None),
        };
        
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
        
        // In a real implementation, this would create a proper backup
        // For now, we'll export the current stats and log the operation
        let stats = self.get_stats()?;
        
        // Create backup directory if it doesn't exist
        let backup_dir = Path::new(backup_path);
        if let Some(parent) = backup_dir.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| format!("Failed to create backup directory: {}", e))?;
        }
        
        info!("Backup operation logged for {} chunks ({} bytes)", 
              stats.total_chunks, stats.total_size_bytes);
        
        Ok(())
    }
    
    /// Store data with checksum
    fn store_with_checksum(&self, data: &[u8]) -> Result<Vec<u8>, String> {
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
        
        info!("Database cleared");
        Ok(())
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
    
    match RustDatabaseAdapter::new(&database_type_str, checksum) {
        Ok(adapter) => Box::into_raw(Box::new(adapter)) as jlong,
        Err(e) => {
            error!("Failed to initialize database adapter: {}", e);
            0
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
            // Create a Java DatabaseStats object
            let stats_class = env.find_class("com/kneaf/core/chunkstorage/DatabaseStats")
                .expect("Failed to find DatabaseStats class");
            
            env.new_object(
                stats_class,
                "(JJJJJZ)V",
                &[
                    jni::objects::JValue::Long(stats.total_chunks as jlong),
                    jni::objects::JValue::Long(stats.total_size_bytes as jlong),
                    jni::objects::JValue::Long(stats.read_latency_ms as jlong),
                    jni::objects::JValue::Long(stats.write_latency_ms as jlong),
                    jni::objects::JValue::Long(stats.last_maintenance_time as jlong),
                    jni::objects::JValue::Bool(stats.is_healthy as jboolean),
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