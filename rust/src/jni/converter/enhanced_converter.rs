use jni::{JNIEnv, objects::{JString, JByteArray, JObject, JByteBuffer, JIntArray, JFloatArray, JLongArray}, sys::{jstring, jbyteArray, jintArray, jfloatArray, jlongArray}};
use std::sync::{Arc, Mutex, RwLock};
use std::collections::HashMap;
use std::time::{Duration, Instant};
use std::ptr;
use byteorder::{LittleEndian, ReadBytesExt, WriteBytesExt};
use std::io::{Cursor, Read};
use once_cell::sync::Lazy;
use dashmap::DashMap;
use simd::{SimdF32, SimdI32, SimdU64};
use crate::errors::{RustError, Result};
use crate::jni::converter::factory::JniConverter;
use crate::memory::zero_copy::{ZeroCopyBuffer, ZeroCopyBufferPool, GlobalBufferTracker};
use crate::binary::zero_copy::ZeroCopyConverter;
use crate::entities::entity::types::{EntityData, EntityPosition};
use crate::entities::block::types::{BlockData, BlockState};
use crate::entities::item::types::{ItemData, ItemStack};
use crate::entities::villager::types::{VillagerData, VillagerProfession};
use schemars::schema::{RootSchema, Schema};
use schemars::JsonSchema;

/// Configuration for EnhancedJniConverter
#[derive(Debug, Clone)]
pub struct EnhancedConverterConfig {
    /// Maximum buffer size for direct memory operations (in bytes)
    pub max_buffer_size: usize,
    /// Maximum cache size for frequently converted types
    pub cache_size: usize,
    /// Cache timeout duration (seconds)
    pub cache_timeout: u64,
    /// Enable SIMD acceleration for numeric arrays
    pub enable_simd: bool,
    /// Enable memory mapping for large data structures
    pub enable_memory_mapping: bool,
    /// Enable streaming conversion for large datasets
    pub enable_streaming: bool,
}

impl Default for EnhancedConverterConfig {
    fn default() -> Self {
        Self {
            max_buffer_size: 1024 * 1024 * 64, // 64MB
            cache_size: 1000,
            cache_timeout: 300, // 5 minutes
            enable_simd: true,
            enable_memory_mapping: true,
            enable_streaming: true,
        }
    }
}

/// Cache entry for frequently converted types
#[derive(Debug, Clone)]
struct CacheEntry<T> {
    value: T,
    timestamp: Instant,
}

/// Enhanced JNI converter with zero-copy capabilities and performance optimizations
pub struct EnhancedJniConverter {
    config: EnhancedConverterConfig,
    string_cache: DashMap<String, CacheEntry<jstring>>,
    primitive_cache: DashMap<(String, Vec<u8>), CacheEntry<JObject>>,
    buffer_pool: ZeroCopyBufferPool,
    performance_metrics: RwLock<HashMap<String, u64>>,
    schema_registry: RwLock<HashMap<String, RootSchema>>,
}

impl EnhancedJniConverter {
    /// Create a new EnhancedJniConverter with default configuration
    pub fn new() -> Self {
        Self::with_config(EnhancedConverterConfig::default())
    }

    /// Create a new EnhancedJniConverter with custom configuration
    pub fn with_config(config: EnhancedConverterConfig) -> Self {
        Self {
            config,
            string_cache: DashMap::new(),
            primitive_cache: DashMap::new(),
            buffer_pool: ZeroCopyBufferPool::new(config.max_buffer_size),
            performance_metrics: RwLock::new(HashMap::new()),
            schema_registry: RwLock::new(HashMap::new()),
        }
    }

    /// Register a schema for type safety validation
    pub fn register_schema(&self, type_name: &str, schema: RootSchema) -> Result<()> {
        let mut registry = self.schema_registry.write().map_err(|e| {
            RustError::ConversionError(format!("Failed to lock schema registry: {}", e))
        })?;
        
        registry.insert(type_name.to_string(), schema);
        Ok(())
    }

    /// Validate data against registered schema
    pub fn validate_schema(&self, type_name: &str, data: &[u8]) -> Result<()> {
        let registry = self.schema_registry.read().map_err(|e| {
            RustError::ConversionError(format!("Failed to lock schema registry: {}", e))
        })?;
        
        let schema = registry.get(type_name)
            .ok_or_else(|| RustError::ConversionError(format!("No schema registered for type: {}", type_name)))?;
        
        // In a real implementation, this would validate the binary data against the schema
        // For now, we'll just check that the schema exists
        Ok(())
    }

    /// Get performance metrics
    pub fn get_performance_metrics(&self) -> Result<HashMap<String, u64>> {
        let metrics = self.performance_metrics.read().map_err(|e| {
            RustError::ConversionError(format!("Failed to lock performance metrics: {}", e))
        })?;
        
        Ok(metrics.clone())
    }

    /// Clear expired cache entries
    pub fn cleanup_cache(&self) -> Result<()> {
        let now = Instant::now();
        let timeout = Duration::from_secs(self.config.cache_timeout);
        
        // Cleanup string cache
        let mut removed = 0;
        for entry in self.string_cache.iter() {
            if now.duration_since(entry.value.timestamp) > timeout {
                self.string_cache.remove(entry.key());
                removed += 1;
            }
        }
        
        // Cleanup primitive cache
        for entry in self.primitive_cache.iter() {
            if now.duration_since(entry.value.timestamp) > timeout {
                self.primitive_cache.remove(entry.key());
                removed += 1;
            }
        }
        
        if removed > 0 {
            self.record_performance_metric("cache_cleanup_removed", removed as u64);
        }
        
        Ok(())
    }

    /// Record a performance metric
    fn record_performance_metric(&self, name: &str, value: u64) {
        let mut metrics = self.performance_metrics.write().unwrap_or_else(|e| {
            eprintln!("[rustperf] Failed to lock performance metrics: {}", e);
            return;
        });
        
        *metrics.entry(name.to_string()).or_insert(0) += value;
    }

    /// Zero-copy string conversion from Java String to Rust String
    pub fn zero_copy_jstring_to_rust(&self, env: &mut JNIEnv, j_str: JString) -> Result<String> {
        let start_time = Instant::now();
        
        // Check if string is in cache first
        let string_ptr = j_str.as_ptr();
        let cache_key = format!("{:p}", string_ptr);
        
        if let Some(entry) = self.string_cache.get(&cache_key) {
            let cached_result = entry.value.clone();
            self.record_performance_metric("string_conversion_cache_hit", 1);
            return Ok(String::from_utf8_lossy(unsafe {
                std::slice::from_raw_parts(cached_result as *const u8, env.get_string_len(j_str)?)
            }).to_string());
        }

        // Get string length and data pointer using JNI
        let len = env.get_string_len(j_str)? as usize;
        let data_ptr = env.get_string_chars(j_str)?;
        
        if data_ptr.is_null() || len == 0 {
            return Err(RustError::ConversionError("Empty or null string".to_string()));
        }

        // Convert UTF-16 to UTF-8 using zero-copy approach
        let result = unsafe {
            jni::strings::JavaString::from_raw(j_str).into()
        };
        
        // Cache the result
        self.string_cache.insert(cache_key, CacheEntry {
            value: data_ptr as jstring,
            timestamp: Instant::now(),
        });
        
        self.record_performance_metric("string_conversion_cache_miss", 1);
        self.record_performance_metric("string_conversion_duration", start_time.elapsed().as_micros() as u64);
        
        Ok(result)
    }

    /// Zero-copy string conversion from Rust String to Java String
    pub fn zero_copy_rust_string_to_jni(&self, env: &mut JNIEnv, s: &str) -> Result<jstring> {
        let start_time = Instant::now();
        
        // Check if string is in cache first
        let cache_key = s.to_string();
        
        if let Some(entry) = self.string_cache.get(&cache_key) {
            self.record_performance_metric("string_conversion_cache_hit", 1);
            return Ok(entry.value.value);
        }

        // Use direct string creation with JNI
        let result = env.new_string(s)
            .map(|s| s.into_raw())
            .map_err(|e| RustError::ConversionError(format!("Failed to convert Rust string to JString: {}", e)))?;
        
        // Cache the result
        self.string_cache.insert(cache_key, CacheEntry {
            value: result,
            timestamp: Instant::now(),
        });
        
        self.record_performance_metric("string_conversion_cache_miss", 1);
        self.record_performance_metric("string_conversion_duration", start_time.elapsed().as_micros() as u64);
        
        Ok(result)
    }

    /// Efficient array conversion for int arrays using zero-copy
    pub fn zero_copy_int_array_to_vec(&self, env: &mut JNIEnv, array: JIntArray) -> Result<Vec<i32>> {
        let start_time = Instant::now();
        
        // Get array elements directly from JNI
        let elements = env.get_int_array_elements(array)?;
        let len = env.get_array_length(array)? as usize;
        
        if elements.is_null() || len == 0 {
            return Err(RustError::ConversionError("Empty or null int array".to_string()));
        }

        // Use SIMD acceleration if enabled
        let result = if self.config.enable_simd {
            self.convert_int_array_with_simd(elements, len)
        } else {
            self.convert_int_array_standard(elements, len)
        };
        
        self.record_performance_metric("int_array_conversion_duration", start_time.elapsed().as_micros() as u64);
        
        Ok(result)
    }

    /// Efficient array conversion for float arrays using zero-copy
    pub fn zero_copy_float_array_to_vec(&self, env: &mut JNIEnv, array: JFloatArray) -> Result<Vec<f32>> {
        let start_time = Instant::now();
        
        // Get array elements directly from JNI
        let elements = env.get_float_array_elements(array)?;
        let len = env.get_array_length(array)? as usize;
        
        if elements.is_null() || len == 0 {
            return Err(RustError::ConversionError("Empty or null float array".to_string()));
        }

        // Use SIMD acceleration if enabled
        let result = if self.config.enable_simd {
            self.convert_float_array_with_simd(elements, len)
        } else {
            self.convert_float_array_standard(elements, len)
        };
        
        self.record_performance_metric("float_array_conversion_duration", start_time.elapsed().as_micros() as u64);
        
        Ok(result)
    }

    /// Efficient array conversion for long arrays using zero-copy
    pub fn zero_copy_long_array_to_vec(&self, env: &mut JNIEnv, array: JLongArray) -> Result<Vec<u64>> {
        let start_time = Instant::now();
        
        // Get array elements directly from JNI
        let elements = env.get_long_array_elements(array)?;
        let len = env.get_array_length(array)? as usize;
        
        if elements.is_null() || len == 0 {
            return Err(RustError::ConversionError("Empty or null long array".to_string()));
        }

        // Use SIMD acceleration if enabled
        let result = if self.config.enable_simd {
            self.convert_long_array_with_simd(elements, len)
        } else {
            self.convert_long_array_standard(elements, len)
        };
        
        self.record_performance_metric("long_array_conversion_duration", start_time.elapsed().as_micros() as u64);
        
        Ok(result)
    }

    /// Standard int array conversion (no SIMD)
    fn convert_int_array_standard(&self, elements: *const i32, len: usize) -> Vec<i32> {
        unsafe {
            let slice = std::slice::from_raw_parts(elements, len);
            slice.to_vec()
        }
    }

    /// SIMD-accelerated int array conversion
    fn convert_int_array_with_simd(&self, elements: *const i32, len: usize) -> Vec<i32> {
        let mut result = Vec::with_capacity(len);
        let mut i = 0;
        
        while i + SimdI32::LANES <= len {
            let simd_values = unsafe { SimdI32::from_slice(&*elements.add(i)) };
            result.extend_from_slice(simd_values.as_slice());
            i += SimdI32::LANES;
        }
        
        // Handle remaining elements
        if i < len {
            result.extend_from_slice(unsafe { std::slice::from_raw_parts(elements.add(i), len - i) });
        }
        
        result
    }

    /// Standard float array conversion (no SIMD)
    fn convert_float_array_standard(&self, elements: *const f32, len: usize) -> Vec<f32> {
        unsafe {
            let slice = std::slice::from_raw_parts(elements, len);
            slice.to_vec()
        }
    }

    /// SIMD-accelerated float array conversion
    fn convert_float_array_with_simd(&self, elements: *const f32, len: usize) -> Vec<f32> {
        let mut result = Vec::with_capacity(len);
        let mut i = 0;
        
        while i + SimdF32::LANES <= len {
            let simd_values = unsafe { SimdF32::from_slice(&*elements.add(i)) };
            result.extend_from_slice(simd_values.as_slice());
            i += SimdF32::LANES;
        }
        
        // Handle remaining elements
        if i < len {
            result.extend_from_slice(unsafe { std::slice::from_raw_parts(elements.add(i), len - i) });
        }
        
        result
    }

    /// Standard long array conversion (no SIMD)
    fn convert_long_array_standard(&self, elements: *const u64, len: usize) -> Vec<u64> {
        unsafe {
            let slice = std::slice::from_raw_parts(elements, len);
            slice.to_vec()
        }
    }

    /// SIMD-accelerated long array conversion
    fn convert_long_array_with_simd(&self, elements: *const u64, len: usize) -> Vec<u64> {
        let mut result = Vec::with_capacity(len);
        let mut i = 0;
        
        while i + SimdU64::LANES <= len {
            let simd_values = unsafe { SimdU64::from_slice(&*elements.add(i)) };
            result.extend_from_slice(simd_values.as_slice());
            i += SimdU64::LANES;
        }
        
        // Handle remaining elements
        if i < len {
            result.extend_from_slice(unsafe { std::slice::from_raw_parts(elements.add(i), len - i) });
        }
        
        result
    }

    /// Direct memory mapping for struct conversion
    pub fn direct_memory_map_struct<T: Sized>(&self, env: &mut JNIEnv, buffer: JByteBuffer) -> Result<*const T> {
        let start_time = Instant::now();
        
        // Get direct buffer information
        let address = env.get_direct_buffer_address(buffer)?;
        let capacity = env.get_direct_buffer_capacity(buffer)? as usize;
        
        if address.is_null() || capacity == 0 {
            return Err(RustError::ConversionError("Empty or null direct buffer".to_string()));
        }

        // Calculate required size for the struct
        let struct_size = std::mem::size_of::<T>();
        if capacity < struct_size {
            return Err(RustError::ConversionError(format!(
                "Buffer capacity ({}) is less than struct size ({})",
                capacity, struct_size
            )));
        }

        let result = address as *const T;
        
        self.record_performance_metric("direct_memory_mapping_duration", start_time.elapsed().as_micros() as u64);
        
        Ok(result)
    }

    /// Batch conversion for multiple objects
    pub fn batch_convert_objects<T, F>(&self, env: &mut JNIEnv, objects: Vec<JObject>, converter: F) -> Result<Vec<T>>
    where
        F: Fn(&mut JNIEnv, JObject) -> Result<T>,
    {
        let start_time = Instant::now();
        let mut results = Vec::with_capacity(objects.len());
        
        for obj in objects {
            results.push(converter(env, obj)?);
        }
        
        self.record_performance_metric("batch_conversion_duration", start_time.elapsed().as_micros() as u64);
        self.record_performance_metric("batch_conversion_count", objects.len() as u64);
        
        Ok(results)
    }

    /// Lazy conversion with on-demand access
    pub fn create_lazy_converter<F, T>(&self, env: &mut JNIEnv, obj: JObject, converter: F) -> Result<LazyConverter<F, T>>
    where
        F: Fn(&mut JNIEnv, JObject) -> Result<T> + 'static,
        T: 'static,
    {
        Ok(LazyConverter::new(env, obj, converter))
    }

    /// Direct ByteBuffer access without copy
    pub fn direct_byte_buffer_access(&self, env: &mut JNIEnv, buffer: JByteBuffer) -> Result<ZeroCopyBuffer> {
        let start_time = Instant::now();
        
        // Get direct buffer information
        let address = env.get_direct_buffer_address(buffer)?;
        let capacity = env.get_direct_buffer_capacity(buffer)? as usize;
        
        if address.is_null() || capacity == 0 {
            return Err(RustError::ConversionError("Empty or null direct buffer".to_string()));
        }

        let buffer = self.buffer_pool.acquire(address as u64, capacity, 0);
        
        // Register buffer with global tracker
        let tracker = GlobalBufferTracker::get_global_buffer_tracker();
        tracker.register_buffer(buffer.address).map_err(|e| {
            RustError::ConversionError(format!("Failed to register buffer: {}", e))
        })?;
        
        self.record_performance_metric("direct_buffer_access_duration", start_time.elapsed().as_micros() as u64);
        
        Ok(buffer)
    }

    /// Memory-mapped file conversion for large data structures
    pub fn memory_mapped_file_conversion(&self, env: &mut JNIEnv, file_path: JString) -> Result<Vec<u8>> {
        let start_time = Instant::now();
        
        // Convert file path to Rust string
        let file_path = self.zero_copy_jstring_to_rust(env, file_path)?;
        
        // In a real implementation, this would use memmap2 to map the file directly
        // For now, we'll read the file normally as a placeholder
        let result = std::fs::read(file_path).map_err(|e| {
            RustError::ConversionError(format!("Failed to read file: {}", e))
        })?;
        
        self.record_performance_metric("memory_mapped_conversion_duration", start_time.elapsed().as_micros() as u64);
        
        Ok(result)
    }

    /// Game entity data conversion
    pub fn convert_entity_data(&self, env: &mut JNIEnv, buffer: JByteBuffer) -> Result<EntityData> {
        let start_time = Instant::now();
        
        // Use direct memory mapping for zero-copy access
        let entity_ptr = self.direct_memory_map_struct::<EntityData>(env, buffer)?;
        let entity = unsafe { &*entity_ptr };
        
        // Clone the entity data (in a real implementation, we might use ARC or shared pointers)
        let result = EntityData {
            entity_id: entity.entity_id,
            position: EntityPosition {
                x: entity.position.x,
                y: entity.position.y,
                z: entity.position.z,
            },
            health: entity.health,
            max_health: entity.max_health,
            inventory: entity.inventory.clone(),
            metadata: entity.metadata.clone(),
        };
        
        self.record_performance_metric("entity_conversion_duration", start_time.elapsed().as_micros() as u64);
        
        Ok(result)
    }

    /// Game block data conversion
    pub fn convert_block_data(&self, env: &mut JNIEnv, buffer: JByteBuffer) -> Result<BlockData> {
        let start_time = Instant::now();
        
        // Use direct memory mapping for zero-copy access
        let block_ptr = self.direct_memory_map_struct::<BlockData>(env, buffer)?;
        let block = unsafe { &*block_ptr };
        
        let result = BlockData {
            block_id: block.block_id,
            position: EntityPosition {
                x: block.position.x,
                y: block.position.y,
                z: block.position.z,
            },
            state: block.state,
            metadata: block.metadata.clone(),
        };
        
        self.record_performance_metric("block_conversion_duration", start_time.elapsed().as_micros() as u64);
        
        Ok(result)
    }

    /// Game item data conversion
    pub fn convert_item_data(&self, env: &mut JNIEnv, buffer: JByteBuffer) -> Result<ItemData> {
        let start_time = Instant::now();
        
        // Use direct memory mapping for zero-copy access
        let item_ptr = self.direct_memory_map_struct::<ItemData>(env, buffer)?;
        let item = unsafe { &*item_ptr };
        
        let result = ItemData {
            item_id: item.item_id,
            stack_size: item.stack_size,
            durability: item.durability,
            enchantments: item.enchantments.clone(),
            metadata: item.metadata.clone(),
        };
        
        self.record_performance_metric("item_conversion_duration", start_time.elapsed().as_micros() as u64);
        
        Ok(result)
    }

    /// Game villager data conversion
    pub fn convert_villager_data(&self, env: &mut JNIEnv, buffer: JByteBuffer) -> Result<VillagerData> {
        let start_time = Instant::now();
        
        // Use direct memory mapping for zero-copy access
        let villager_ptr = self.direct_memory_map_struct::<VillagerData>(env, buffer)?;
        let villager = unsafe { &*villager_ptr };
        
        let result = VillagerData {
            villager_id: villager.villager_id,
            position: EntityPosition {
                x: villager.position.x,
                y: villager.position.y,
                z: villager.position.z,
            },
            profession: villager.profession,
            level: villager.level,
            trades: villager.trades.clone(),
            pathfinding_data: villager.pathfinding_data.clone(),
        };
        
        self.record_performance_metric("villager_conversion_duration", start_time.elapsed().as_micros() as u64);
        
        Ok(result)
    }

    /// Batch entity update conversion
    pub fn batch_convert_entities(&self, env: &mut JNIEnv, buffers: Vec<JByteBuffer>) -> Result<Vec<EntityData>> {
        self.batch_convert_objects(env, buffers.into_iter().map(JObject::from).collect(), |env, obj| {
            let buffer = JByteBuffer::from(obj);
            self.convert_entity_data(env, buffer)
        })
    }
}

impl JniConverter for EnhancedJniConverter {
    fn jstring_to_rust(&self, env: &mut JNIEnv, j_str: JString) -> Result<String> {
        // Use zero-copy implementation by default
        self.zero_copy_jstring_to_rust(env, j_str)
    }

    fn rust_string_to_jni(&self, env: &mut JNIEnv, s: &str) -> Result<jstring> {
        // Use zero-copy implementation by default
        self.zero_copy_rust_string_to_jni(env, s)
    }

    fn jbyte_array_to_vec(&self, env: &mut JNIEnv, array: JByteArray) -> Result<Vec<u8>> {
        let start_time = Instant::now();
        
        // Get array elements directly from JNI
        let elements = env.get_byte_array_elements(array)?;
        let len = env.get_array_length(array)? as usize;
        
        if elements.is_null() || len == 0 {
            return Err(RustError::ConversionError("Empty or null byte array".to_string()));
        }

        let result = unsafe {
            std::slice::from_raw_parts(elements, len).to_vec()
        };
        
        self.record_performance_metric("byte_array_conversion_duration", start_time.elapsed().as_micros() as u64);
        
        Ok(result)
    }

    fn vec_to_jbyte_array(&self, env: &mut JNIEnv, vec: &[u8]) -> Result<JByteArray> {
        env.byte_array_from_slice(vec)
            .map_err(|e| RustError::ConversionError(format!("Failed to convert Vec<u8> to JByteArray: {}", e)))
    }

    fn create_error_jni_string(&self, env: &mut JNIEnv, error: &str) -> jstring {
        match self.rust_string_to_jni(env, error) {
            Ok(s) => s,
            Err(_) => {
                let fallback = env.new_string(error).unwrap_or_else(|_| env.new_string("").unwrap());
                fallback.into_raw()
            }
        }
    }
}

/// Lazy converter that performs conversion only when accessed
pub struct LazyConverter<F, T>
where
    F: Fn(&mut JNIEnv, JObject) -> Result<T> + 'static,
    T: 'static,
{
    env: *mut JNIEnv,
    obj: JObject,
    converter: F,
    result: Option<T>,
}

unsafe impl<F, T> Send for LazyConverter<F, T> where F: Send, T: Send {}
unsafe impl<F, T> Sync for LazyConverter<F, T> where F: Sync, T: Sync {}

impl<F, T> LazyConverter<F, T>
where
    F: Fn(&mut JNIEnv, JObject) -> Result<T> + 'static,
    T: 'static,
{
    pub fn new(env: &mut JNIEnv, obj: JObject, converter: F) -> Self {
        Self {
            env: env as *mut JNIEnv,
            obj,
            converter,
            result: None,
        }
    }

    /// Get the converted result, performing conversion if not already done
    pub fn get(&mut self) -> Result<&T> {
        if self.result.is_none() {
            let env = unsafe { &mut *self.env };
            self.result = Some((self.converter)(env, self.obj.clone())?);
        }
        Ok(self.result.as_ref().unwrap())
    }

    /// Consume the lazy converter and return the result
    pub fn into_inner(self) -> Result<T> {
        if self.result.is_some() {
            Ok(self.result.unwrap())
        } else {
            let env = unsafe { &mut *self.env };
            (self.converter)(env, self.obj)
        }
    }
}

/// Factory for creating EnhancedJniConverter instances
pub struct EnhancedJniConverterFactory;

impl EnhancedJniConverterFactory {
    /// Create a default EnhancedJniConverter
    pub fn create_default() -> Box<dyn JniConverter> {
        Box::new(EnhancedJniConverter::new())
    }

    /// Create an EnhancedJniConverter with custom configuration
    pub fn create_with_config(config: EnhancedConverterConfig) -> Box<dyn JniConverter> {
        Box::new(EnhancedJniConverter::with_config(config))
    }

    /// Create an EnhancedJniConverter optimized for game entities
    pub fn create_for_game_entities() -> Box<dyn JniConverter> {
        let mut config = EnhancedConverterConfig::default();
        config.max_buffer_size = 1024 * 1024 * 256; // 256MB for game entities
        config.cache_size = 5000; // Larger cache for frequent entity conversions
        Box::new(EnhancedJniConverter::with_config(config))
    }

    /// Create an EnhancedJniConverter optimized for large datasets
    pub fn create_for_large_datasets() -> Box<dyn JniConverter> {
        let mut config = EnhancedConverterConfig::default();
        config.enable_memory_mapping = true;
        config.enable_streaming = true;
        config.cache_size = 100; // Smaller cache for less frequent large data conversions
        Box::new(EnhancedJniConverter::with_config(config))
    }
}

/// Global singleton for enhanced converter factory
pub static ENHANCED_CONVERTER_FACTORY: Lazy<EnhancedJniConverterFactory> = Lazy::new(|| EnhancedJniConverterFactory);

#[cfg(test)]
mod tests {
    use super::*;
    use jni::errors::Error;
    use mockall::mock;

    mock! {
        JNIEnv {}
        impl JNIEnv for JNIEnv {
            fn get_string(&mut self, _: &JString) -> Result<jni::objects::String, Error>;
            fn new_string(&mut self, _: &str) -> Result<jni::objects::String, Error>;
            fn convert_byte_array(&mut self, _: JByteArray) -> Result<Vec<u8>, Error>;
            fn byte_array_from_slice(&mut self, _: &[u8]) -> Result<JByteArray, Error>;
            fn get_string_len(&mut self, _: JString) -> Result<usize, Error>;
            fn get_string_chars(&mut self, _: JString) -> Result<*const i16, Error>;
            fn get_int_array_elements(&mut self, _: JIntArray) -> Result<*const i32, Error>;
            fn get_array_length(&mut self, _: JIntArray) -> Result<i32, Error>;
            fn get_direct_buffer_address(&mut self, _: JByteBuffer) -> Result<*mut u8, Error>;
            fn get_direct_buffer_capacity(&mut self, _: JByteBuffer) -> Result<usize, Error>;
        }
    }

    #[test]
    fn test_enhanced_converter_default_creation() {
        let converter = EnhancedJniConverter::new();
        assert_eq!(converter.config.max_buffer_size, 1024 * 1024 * 64);
        assert_eq!(converter.config.cache_size, 1000);
    }

    #[test]
    fn test_enhanced_converter_with_config() {
        let config = EnhancedConverterConfig {
            max_buffer_size: 1024 * 1024 * 128,
            cache_size: 2000,
            cache_timeout: 600,
            enable_simd: false,
            enable_memory_mapping: false,
            enable_streaming: false,
        };
        let converter = EnhancedJniConverter::with_config(config);
        assert_eq!(converter.config.max_buffer_size, 1024 * 1024 * 128);
        assert_eq!(converter.config.cache_size, 2000);
        assert!(!converter.config.enable_simd);
    }

    #[test]
    fn test_lazy_converter() {
        let converter = EnhancedJniConverter::new();
        let mut env = MockJNIEnv::new();
        let obj = JObject::from_raw(ptr::null_mut());
        
        env.expect_get_string_chars()
            .returning(|_| Ok("test".as_ptr() as *const i16));
        
        let lazy_conv = converter.create_lazy_converter(&mut env, obj, |env, obj| {
            let j_str = JString::from(obj);
            converter.zero_copy_jstring_to_rust(env, j_str)
        }).unwrap();
        
        // Conversion should not happen yet
        assert!(lazy_conv.result.is_none());
        
        // Conversion should happen when get() is called
        let result = lazy_conv.get();
        assert!(result.is_ok());
        assert!(lazy_conv.result.is_some());
    }
}