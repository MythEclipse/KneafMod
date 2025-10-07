use std::collections::{BinaryHeap, HashMap};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant};

use lazy_static::lazy_static;

// Cache eviction policy types
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EvictionPolicy {
    LRU,      // Least Recently Used
    LFU,      // Least Frequently Used  
    FIFO,     // First-In-First-Out
    Priority, // Custom priority-based
}

// Cache entry structure
#[derive(Debug, Clone, Eq, Ord, PartialEq, PartialOrd)]
pub struct CacheEntry<T> {
    pub key: String,
    pub value: T,
    pub access_count: u64,
    pub last_access: Instant,
    pub priority: u32,
    pub insertion_time: Instant,
}

// Priority queue implementation for cache eviction
#[derive(Debug)]
pub struct PriorityCache<T> {
    capacity: usize,
    policy: EvictionPolicy,
    entries: RwLock<HashMap<String, CacheEntry<T>>>,
    access_queue: Mutex<BinaryHeap<CacheEntry<T>>>,
    stats: Mutex<CacheStats>,
    feature_enabled: bool,
}

// Cache statistics structure
#[derive(Debug, Default, Clone)]
pub struct CacheStats {
    pub hits: u64,
    pub misses: u64,
    pub evictions: u64,
    pub insertions: u64,
    pub current_size: usize,
    pub max_size: usize,
}

impl<T: Clone + std::fmt::Debug + Ord> PriorityCache<T> {
    // Create a new priority cache with specified capacity and eviction policy
    pub fn new(capacity: usize, policy: EvictionPolicy, feature_enabled: bool) -> Self {
        PriorityCache {
            capacity,
            policy,
            entries: RwLock::new(HashMap::new()),
            access_queue: Mutex::new(BinaryHeap::new()),
            stats: Mutex::new(CacheStats {
                max_size: capacity,
                ..Default::default()
            }),
            feature_enabled,
        }
    }

    // Get a value from the cache
    pub fn get(&self, key: &str) -> Option<T> {
        if !self.feature_enabled {
            return None;
        }

        let mut entries = self.entries.write().unwrap();
        let mut stats = self.stats.lock().unwrap();

        if let Some(entry) = entries.get_mut(key) {
            // Update access statistics based on policy
            stats.hits += 1;
            
            match self.policy {
                EvictionPolicy::LRU => {
                    entry.last_access = Instant::now();
                }
                EvictionPolicy::LFU => {
                    entry.access_count += 1;
                }
                EvictionPolicy::Priority => {
                    entry.last_access = Instant::now();
                }
                _ => {} // FIFO doesn't need per-access updates
            }

            Some(entry.value.clone())
        } else {
            stats.misses += 1;
            None
        }
    }

    // Insert a value into the cache
    pub fn insert(&self, key: String, value: T, priority: u32) -> Option<T> {
        if !self.feature_enabled {
            return Some(value);
        }

        let mut entries = self.entries.write().unwrap();
        let mut stats = self.stats.lock().unwrap();
        let mut access_queue = self.access_queue.lock().unwrap();

        // Check if we need to evict before inserting
        let evicted_value = if entries.len() >= self.capacity {
            self.evict_one()
        } else {
            None
        };

        // Insert new entry
        let entry = CacheEntry {
            key: key.clone(),
            value: value.clone(),
            access_count: 1,
            last_access: Instant::now(),
            priority,
            insertion_time: Instant::now(),
        };

        entries.insert(key.clone(), entry.clone());
        access_queue.push(entry);
        
        stats.insertions += 1;
        stats.current_size = entries.len();

        evicted_value
    }

    // Remove a specific entry from the cache
    pub fn remove(&self, key: &str) -> Option<T> {
        if !self.feature_enabled {
            return None;
        }

        let mut entries = self.entries.write().unwrap();
        let mut stats = self.stats.lock().unwrap();

        if let Some(entry) = entries.remove(key) {
            stats.current_size = entries.len();
            Some(entry.value)
        } else {
            None
        }
    }

    // Clear all entries from the cache
    pub fn clear(&self) {
        if !self.feature_enabled {
            return;
        }

        let mut entries = self.entries.write().unwrap();
        let mut stats = self.stats.lock().unwrap();

        entries.clear();
        stats.current_size = 0;
    }

    // Get cache statistics
    pub fn get_stats(&self) -> CacheStats {
        let stats = self.stats.lock().unwrap();
        stats.clone()
    }

    // Enable/disable the cache feature
    pub fn set_feature_enabled(&mut self, enabled: bool) {
        self.feature_enabled = enabled;
    }

    // Check if feature is enabled
    pub fn is_feature_enabled(&self) -> bool {
        self.feature_enabled
    }

    // Evict one entry based on the configured policy
    fn evict_one(&self) -> Option<T> {
        let mut entries = self.entries.write().unwrap();
        let mut access_queue = self.access_queue.lock().unwrap();
        let mut stats = self.stats.lock().unwrap();

        // Find and remove the entry to evict based on policy
        let evict_key = match self.policy {
            EvictionPolicy::LRU => {
                // Find least recently used entry
                let mut lru_entry = None;
                let mut lru_instant = Instant::now() + Duration::from_secs(1_000_000);

                for (key, entry) in entries.iter() {
                    if entry.last_access < lru_instant {
                        lru_instant = entry.last_access;
                        lru_entry = Some(key.clone());
                    }
                }

                lru_entry
            }
            EvictionPolicy::LFU => {
                // Find least frequently used entry
                let mut lfu_entry = None;
                let mut lfu_count = u64::MAX;

                for (key, entry) in entries.iter() {
                    if entry.access_count < lfu_count {
                        lfu_count = entry.access_count;
                        lfu_entry = Some(key.clone());
                    }
                }

                lfu_entry
            }
            EvictionPolicy::FIFO => {
                // Find oldest entry by insertion time
                let mut fifo_entry = None;
                let mut oldest_instant = Instant::now() + Duration::from_secs(1_000_000);

                for (key, entry) in entries.iter() {
                    if entry.insertion_time < oldest_instant {
                        oldest_instant = entry.insertion_time;
                        fifo_entry = Some(key.clone());
                    }
                }

                fifo_entry
            }
            EvictionPolicy::Priority => {
                // Find lowest priority entry (with LRU tiebreaker)
                let mut lowest_priority = u32::MAX;
                let mut lowest_priority_entries = Vec::new();

                // First find all entries with the lowest priority
                for (key, entry) in entries.iter() {
                    if entry.priority < lowest_priority {
                        lowest_priority = entry.priority;
                        lowest_priority_entries.clear();
                        lowest_priority_entries.push(key.clone());
                    } else if entry.priority == lowest_priority {
                        lowest_priority_entries.push(key.clone());
                    }
                }

                // From lowest priority entries, find the least recently used
                if let Some(lowest_entry) = lowest_priority_entries.iter().min_by_key(|&key| {
                    entries.get(key).map(|e| e.last_access).unwrap_or(Instant::now())
                }) {
                    Some(lowest_entry.clone())
                } else {
                    None
                }
            }
        };

        // Remove the evicted entry if found
        if let Some(key) = evict_key {
            if let Some(entry) = entries.remove(&key) {
                stats.evictions += 1;
                stats.current_size = entries.len();
                return Some(entry.value);
            }
        }

        None
    }
}

// Global cache instance - can be configured via JNI
lazy_static! {
    pub static ref GLOBAL_CACHE: Arc<PriorityCache<Vec<u8>>> = Arc::new(PriorityCache::new(
        1000,                      // Default capacity
        EvictionPolicy::LRU,       // Default policy
        false                      // Feature disabled by default
    ));
}

// Feature flag configuration
#[derive(Debug, Clone)]
pub struct CacheFeatureFlags {
    pub cache_enabled: bool,
    pub eviction_policy: EvictionPolicy,
    pub max_capacity: usize,
    pub stats_collection: bool,
}

impl Default for CacheFeatureFlags {
    fn default() -> Self {
        CacheFeatureFlags {
            cache_enabled: false,
            eviction_policy: EvictionPolicy::LRU,
            max_capacity: 1000,
            stats_collection: true,
        }
    }
}

// Initialize cache with feature flags
pub fn initialize_cache(flags: CacheFeatureFlags) {
    // For testing purposes, we'll just log the configuration
    // In a real implementation, we would properly handle cache reconfiguration
    eprintln!("Cache configuration received: enabled={}, policy={:?}, capacity={}, stats={}",
        flags.cache_enabled,
        flags.eviction_policy,
        flags.max_capacity,
        flags.stats_collection);
    
    // For now, we'll just update the feature flag through the global cache API
    // This is a simplification that works for our testing needs
    let mut cache = GLOBAL_CACHE.clone();
    let mut cache = Arc::get_mut(&mut cache).expect("Failed to get mutable reference to cache");
    cache.set_feature_enabled(flags.cache_enabled);
}

// JNI-compatible functions
#[cfg(feature = "jni")]
pub mod jni_bindings {
    use super::*;
    use jni::JNIEnv;
    use jni::objects::{JClass, JByteArray};
    use jni::sys::{jbyteArray, jstring, jlong, jint, jboolean};

    // Convert JString to Rust String
    fn jstring_to_string(env: &JNIEnv, jstr: jstring) -> Result<String, String> {
        let jstr = JClass::from_raw(jstr);
        let result: String = env.get_string(jstr).map_err(|e| e.to_string())?.into();
        Ok(result)
    }

    // Convert Rust String to JString
    fn string_to_jstring(env: &JNIEnv, s: &str) -> Result<jstring, String> {
        env.new_string(s).map_err(|e| e.to_string()).map(|s| s.into_raw())
    }

    // Convert JByteArray to Vec<u8>
    fn jbyte_array_to_vec(env: &JNIEnv, array: jbyteArray) -> Result<Vec<u8>, String> {
        let array = JByteArray::from_raw(array);
        env.convert_byte_array(array).map_err(|e| e.to_string())
    }

    // Convert Vec<u8> to JByteArray
    fn vec_to_jbyte_array(env: &JNIEnv, vec: &[u8]) -> Result<jbyteArray, String> {
        env.byte_array_from_slice(vec).map_err(|e| e.to_string()).map(|a| a.into_raw())
    }

    // Convert CacheStats to JSON string
    fn cache_stats_to_json(stats: &CacheStats) -> String {
        format!(
            r#"{{"hits":{},"misses":{},"evictions":{},"insertions":{},"currentSize":{},"maxSize":{}}}"#,
            stats.hits,
            stats.misses,
            stats.evictions,
            stats.insertions,
            stats.current_size,
            stats.max_size
        )
    }

    // Initialize cache with feature flags
    #[no_mangle]
    pub extern "system" fn Java_com_kneaf_core_performance_CacheNativeBridge_nativeInitializeCache(
        env: JNIEnv,
        _class: JClass,
        enabled: jboolean,
        policy: jint,
        capacity: jint,
        collect_stats: jboolean,
    ) -> jboolean {
        let policy = match policy {
            0 => EvictionPolicy::LRU,
            1 => EvictionPolicy::LFU,
            2 => EvictionPolicy::FIFO,
            3 => EvictionPolicy::Priority,
            _ => return 0, // Invalid policy
        };

        let flags = CacheFeatureFlags {
            cache_enabled: enabled != 0,
            eviction_policy: policy,
            max_capacity: capacity as usize,
            stats_collection: collect_stats != 0,
        };

        initialize_cache(flags);
        1 // Success
    }

    // Insert value into cache
    #[no_mangle]
    pub extern "system" fn Java_com_kneaf_core_performance_CacheNativeBridge_nativeCachePut(
        env: JNIEnv,
        _class: JClass,
        key: jstring,
        value: jbyteArray,
        priority: jint,
    ) -> jbyteArray {
        let key_str = match jstring_to_string(env, key) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };

        let value_vec = match jbyte_array_to_vec(env, value) {
            Ok(v) => v,
            Err(_) => return std::ptr::null_mut(),
        };

        let result = GLOBAL_CACHE.insert(key_str, value_vec, priority as u32);
        
        match result {
            Some(evicted) => match vec_to_jbyte_array(env, &evicted) {
                Ok(array) => array,
                Err(_) => std::ptr::null_mut(),
            },
            None => std::ptr::null_mut(), // No eviction occurred
        }
    }

    // Get value from cache
    #[no_mangle]
    pub extern "system" fn Java_com_kneaf_core_performance_CacheNativeBridge_nativeCacheGet(
        env: JNIEnv,
        _class: JClass,
        key: jstring,
    ) -> jbyteArray {
        let key_str = match jstring_to_string(env, key) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };

        let result = GLOBAL_CACHE.get(&key_str);
        
        match result {
            Some(value) => match vec_to_jbyte_array(env, &value) {
                Ok(array) => array,
                Err(_) => std::ptr::null_mut(),
            },
            None => std::ptr::null_mut(), // Cache miss or feature disabled
        }
    }

    // Remove value from cache
    #[no_mangle]
    pub extern "system" fn Java_com_kneaf_core_performance_CacheNativeBridge_nativeCacheRemove(
        env: JNIEnv,
        _class: JClass,
        key: jstring,
    ) -> jbyteArray {
        let key_str = match jstring_to_string(env, key) {
            Ok(s) => s,
            Err(_) => return std::ptr::null_mut(),
        };

        let result = GLOBAL_CACHE.remove(&key_str);
        
        match result {
            Some(value) => match vec_to_jbyte_array(env, &value) {
                Ok(array) => array,
                Err(_) => std::ptr::null_mut(),
            },
            None => std::ptr::null_mut(), // Not found or feature disabled
        }
    }

    // Clear cache
    #[no_mangle]
    pub extern "system" fn Java_com_kneaf_core_performance_CacheNativeBridge_nativeCacheClear(
        env: JNIEnv,
        _class: JClass,
    ) -> jboolean {
        GLOBAL_CACHE.clear();
        1 // Success
    }

    // Get cache statistics
    #[no_mangle]
    pub extern "system" fn Java_com_kneaf_core_performance_CacheNativeBridge_nativeGetCacheStats(
        env: JNIEnv,
        _class: JClass,
    ) -> jstring {
        let stats = GLOBAL_CACHE.get_stats();
        let json = cache_stats_to_json(&stats);
        
        match string_to_jstring(env, &json) {
            Ok(jstr) => jstr,
            Err(_) => std::ptr::null_mut(),
        }
    }

    // Check if cache feature is enabled
    #[no_mangle]
    pub extern "system" fn Java_com_kneaf_core_performance_CacheNativeBridge_nativeIsCacheEnabled(
        env: JNIEnv,
        _class: JClass,
    ) -> jboolean {
        let enabled = GLOBAL_CACHE.is_feature_enabled();
        if enabled { 1 } else { 0 }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_cache_basic_operations() {
        let cache = PriorityCache::new(2, EvictionPolicy::LRU, true);
        
        // Test insertions
        assert!(cache.insert("key1".to_string(), vec![1, 2, 3], 1).is_none());
        assert!(cache.insert("key2".to_string(), vec![4, 5, 6], 1).is_none());
        
        // Test get operations
        assert_eq!(cache.get("key1"), Some(vec![1, 2, 3]));
        assert_eq!(cache.get("key2"), Some(vec![4, 5, 6]));
        
        // Test eviction
        let evicted = cache.insert("key3".to_string(), vec![7, 8, 9], 1);
        assert_eq!(evicted, Some(vec![1, 2, 3])); // LRU evicts key1
        
        // Test that evicted key is no longer accessible
        assert!(cache.get("key1").is_none());
        assert_eq!(cache.get("key2"), Some(vec![4, 5, 6]));
        assert_eq!(cache.get("key3"), Some(vec![7, 8, 9]));
    }

    #[test]
    fn test_cache_feature_disabled() {
        let cache = PriorityCache::new(2, EvictionPolicy::LRU, false);
        
        // Insert should return the value (bypass cache)
        let result = cache.insert("key1".to_string(), vec![1, 2, 3], 1);
        assert_eq!(result, Some(vec![1, 2, 3]));
        
        // Get should return None
        assert!(cache.get("key1").is_none());
    }

    #[test]
    fn test_cache_eviction_policies() {
        // Test LFU policy
        let lfu_cache = PriorityCache::new(2, EvictionPolicy::LFU, true);
        
        lfu_cache.insert("key1".to_string(), vec![1], 1);
        lfu_cache.insert("key2".to_string(), vec![2], 1);
        
        // Access key1 multiple times to make it more frequent
        lfu_cache.get("key1");
        lfu_cache.get("key1");
        
        // Key2 should be evicted since it has lower access count
        let evicted = lfu_cache.insert("key3".to_string(), vec![3], 1);
        assert_eq!(evicted, Some(vec![2]));

        // Test FIFO policy
        let fifo_cache = PriorityCache::new(2, EvictionPolicy::FIFO, true);
        
        fifo_cache.insert("key1".to_string(), vec![1], 1);
        fifo_cache.insert("key2".to_string(), vec![2], 1);
        
        // Key1 should be evicted since it was inserted first
        let evicted = fifo_cache.insert("key3".to_string(), vec![3], 1);
        assert_eq!(evicted, Some(vec![1]));
    }

    #[test]
    fn test_cache_stats() {
        let cache = PriorityCache::new(1, EvictionPolicy::LRU, true);
        
        // Initial stats
        let stats = cache.get_stats();
        assert_eq!(stats.hits, 0);
        assert_eq!(stats.misses, 0);
        assert_eq!(stats.evictions, 0);
        assert_eq!(stats.insertions, 0);
        assert_eq!(stats.current_size, 0);
        assert_eq!(stats.max_size, 1);
        
        // Insert (miss -> insertion)
        cache.insert("key1".to_string(), vec![1], 1);
        let stats = cache.get_stats();
        assert_eq!(stats.misses, 1);
        assert_eq!(stats.insertions, 1);
        assert_eq!(stats.current_size, 1);
        
        // Get (hit)
        cache.get("key1");
        let stats = cache.get_stats();
        assert_eq!(stats.hits, 1);
        
        // Insert again (should evict, miss -> insertion -> eviction)
        cache.insert("key2".to_string(), vec![2], 1);
        let stats = cache.get_stats();
        assert_eq!(stats.misses, 2);
        assert_eq!(stats.insertions, 2);
        assert_eq!(stats.evictions, 1);
        assert_eq!(stats.current_size, 1);
    }
}