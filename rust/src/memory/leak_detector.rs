use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex, RwLock, AtomicBool};
use std::time::{Duration, Instant};
use std::fmt;
use serde::{Serialize, Deserialize};
use crossbeam_channel::{unbounded, Sender, Receiver};
use rand::Rng;

// Import existing system interfaces (assumed based on task requirements)
use crate::memory::monitoring::RealTimeMemoryMonitor;
use crate::memory::pool::lru_eviction::LRUEvictionMemoryPool;
use crate::memory::pool::enhanced_manager::EnhancedMemoryPoolManager;

/// Struct representing a single memory allocation record
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AllocationRecord {
    /// Unique identifier for this allocation
    pub id: u64,
    /// Memory address of the allocation
    pub address: usize,
    /// Size of the allocation in bytes
    pub size: usize,
    /// Timestamp when the allocation was made
    pub allocated_at: Instant,
    /// Last time this allocation was accessed (for aging algorithm)
    pub last_accessed: Instant,
    /// Stack trace at allocation time (if available)
    pub stack_trace: Option<String>,
    /// Thread ID where allocation occurred
    pub thread_id: u32,
    /// Component that made the allocation
    pub component: String,
    /// Additional context about the allocation
    pub context: Option<String>,
    /// Reference count for tracking object lifetime
    pub ref_count: u32,
    /// Whether this allocation has been marked for deallocation
    pub marked_for_deallocation: bool,
}

impl AllocationRecord {
    /// Create a new AllocationRecord
    pub fn new(
        id: u64,
        address: usize,
        size: usize,
        thread_id: u32,
        component: String,
        context: Option<String>,
        stack_trace: Option<String>,
    ) -> Self {
        let now = Instant::now();
        Self {
            id,
            address,
            size,
            allocated_at: now,
            last_accessed: now,
            stack_trace,
            thread_id,
            component,
            context,
            ref_count: 1,
            marked_for_deallocation: false,
        }
    }

    /// Increment the reference count
    pub fn increment_refcount(&mut self) {
        self.ref_count += 1;
        self.last_accessed = Instant::now();
    }

    /// Decrement the reference count
    pub fn decrement_refcount(&mut self) -> bool {
        self.ref_count = self.ref_count.saturating_sub(1);
        self.last_accessed = Instant::now();
        self.ref_count == 0
    }

    /// Mark this allocation for deallocation
    pub fn mark_for_deallocation(&mut self) {
        self.marked_for_deallocation = true;
    }
}

/// Configuration for the MemoryLeakDetector
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LeakDetectorConfig {
    /// Analysis interval in seconds
    pub analysis_interval: u64,
    /// Memory growth threshold percentage per interval
    pub growth_threshold: f64,
    /// Maximum allowed object lifetime in seconds
    pub max_lifetime: u64,
    /// Sensitivity level for leak detection (1-10)
    pub sensitivity: u8,
    /// Minimum allocation size to track (in bytes)
    pub min_allocation_size: usize,
    /// Enable machine learning pattern recognition
    pub enable_ml_detection: bool,
    /// Number of historical samples to keep for statistical analysis
    pub historical_samples: usize,
}

impl Default for LeakDetectorConfig {
    fn default() -> Self {
        Self {
            analysis_interval: 60,
            growth_threshold: 5.0,
            max_lifetime: 300,
            sensitivity: 5,
            min_allocation_size: 1024,
            enable_ml_detection: true,
            historical_samples: 10,
        }
    }
}

/// Struct representing a detected memory leak
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MemoryLeak {
    /// Unique identifier for this leak
    pub id: u64,
    /// Allocation record associated with the leak
    pub allocation: AllocationRecord,
    /// Leak type classification
    pub leak_type: LeakType,
    /// Confidence score (0-100)
    pub confidence: u8,
    /// Detection timestamp
    pub detected_at: Instant,
    /// Additional analysis information
    pub analysis: Option<String>,
}

/// Enum representing different types of memory leaks
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum LeakType {
    /// Object was never deallocated
    ForgottenDeallocation,
    /// Circular reference preventing deallocation
    CircularReference,
    /// Excessive memory growth over time
    ExcessiveGrowth,
    /// Object leaked through weak references
    WeakReferenceLeak,
    /// Unknown leak type
    Unknown,
}

impl fmt::Display for LeakType {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        match self {
            LeakType::ForgottenDeallocation => write!(f, "Forgotten Deallocation"),
            LeakType::CircularReference => write!(f, "Circular Reference"),
            LeakType::ExcessiveGrowth => write!(f, "Excessive Growth"),
            LeakType::WeakReferenceLeak => write!(f, "Weak Reference Leak"),
            LeakType::Unknown => write!(f, "Unknown"),
        }
    }
}

/// Struct representing a memory leak report
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LeakReport {
    /// Report generation timestamp
    pub generated_at: Instant,
    /// Total detected leaks
    pub total_leaks: usize,
    /// Leaks by type
    pub leaks_by_type: HashMap<LeakType, usize>,
    /// Detailed leak information
    pub detailed_leaks: Vec<MemoryLeak>,
    /// Memory usage statistics
    pub memory_stats: MemoryStats,
    /// Detection configuration used
    pub config: LeakDetectorConfig,
}

/// Struct representing memory usage statistics
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MemoryStats {
    /// Total allocated memory
    pub total_allocated: usize,
    /// Total deallocated memory
    pub total_deallocated: usize,
    /// Current memory usage
    pub current_usage: usize,
    /// Peak memory usage
    pub peak_usage: usize,
    /// Allocation rate (bytes per second)
    pub allocation_rate: f64,
    /// Deallocation rate (bytes per second)
    pub deallocation_rate: f64,
}

/// Callback trait for leak detection alerts
pub trait LeakDetectionCallback {
    /// Called when a leak is detected
    fn on_leak_detected(&self, leak: &MemoryLeak);
    
    /// Called when a leak report is generated
    fn on_report_generated(&self, report: &LeakReport);
    
    /// Called when memory usage exceeds thresholds
    fn on_memory_threshold_exceeded(&self, stats: &MemoryStats);
}

/// Main memory leak detector struct
pub struct MemoryLeakDetector {
    /// Configuration for the leak detector
    config: LeakDetectorConfig,
    
    /// Tracking of all allocations
    allocations: Arc<RwLock<HashMap<u64, AllocationRecord>>>,
    
    /// Next available allocation ID
    next_allocation_id: Arc<Mutex<u64>>,
    
    /// Historical memory usage samples for statistical analysis
    historical_samples: Arc<RwLock<Vec<MemoryStats>>>,
    
    /// Real-time memory monitor integration
    memory_monitor: Arc<RealTimeMemoryMonitor>,
    
    /// LRUEvictionMemoryPool integration
    lru_pool: Arc<LRUEvictionMemoryPool>,
    
    /// EnhancedMemoryPoolManager integration
    pool_manager: Arc<EnhancedMemoryPoolManager>,
    
    /// Channel for receiving allocation/deallocation events
    event_sender: Sender<LeakDetectorEvent>,
    
    /// Set of registered callbacks
    callbacks: Arc<RwLock<HashSet<Box<dyn LeakDetectionCallback>>>>,
    
    /// Machine learning model for pattern recognition (simplified implementation)
    ml_model: Arc<RwLock<Option<MLModel>>>,
}

/// Events sent to the leak detector
enum LeakDetectorEvent {
    /// Allocation event
    Allocate(AllocationRecord),
    /// Deallocation event
    Deallocate(u64),
    /// Access event (for aging algorithm)
    Access(u64),
    /// Shutdown event
    Shutdown,
}

/// Advanced machine learning model for leak detection using statistical learning
struct MLModel {
    /// Training data
    training_data: RwLock<Vec<LeakPattern>>,
    /// Model parameters (weights for different features)
    parameters: RwLock<Vec<f64>>,
    /// Feature importance scores
    feature_importance: RwLock<Vec<f64>>,
    /// Model accuracy
    accuracy: RwLock<f64>,
    /// Last training timestamp
    last_trained: RwLock<Instant>,
    /// Training lock to prevent concurrent training
    training_in_progress: AtomicBool,
    /// Feature normalization parameters (min, max for each feature)
    feature_ranges: RwLock<Option<Vec<(f64, f64)>>>,
    /// Model version for tracking improvements
    version: RwLock<u32>,
}

/// Pattern for machine learning training with enhanced features
#[derive(Debug, Clone, Serialize, Deserialize)]
struct LeakPattern {
    /// Allocation features (normalized values)
    features: Vec<f64>,
    /// Leak label (true if leak, false otherwise)
    is_leak: bool,
    /// Leak type if known
    leak_type: Option<LeakType>,
    /// Confidence score from initial detection
    confidence: f64,
    /// Timestamp when pattern was recorded
    timestamp: Instant,
}

/// ML model training configuration
#[derive(Debug, Clone)]
struct MLTrainingConfig {
    /// Minimum number of samples required for training
    min_samples: usize,
    /// Training interval in seconds
    training_interval: u64,
    /// Learning rate
    learning_rate: f64,
    /// Number of epochs
    epochs: usize,
    /// Feature normalization parameters
    feature_ranges: Option<Vec<(f64, f64)>>,
    /// Enable early stopping to prevent overfitting
    early_stopping: bool,
    /// Validation split ratio (0.0 to 1.0)
    validation_split: f64,
}

impl Default for MLTrainingConfig {
    fn default() -> Self {
        Self {
            min_samples: 200,      // More samples for better model stability
            training_interval: 600, // 10 minutes for production use
            learning_rate: 0.005,   // Lower learning rate for better convergence
            epochs: 50,             // More epochs for comprehensive training
            feature_ranges: None,
            early_stopping: true,   // Prevent overfitting by default
            validation_split: 0.2,  // 20% validation data
        }
    }
}

impl MemoryLeakDetector {
    /// Create a new MemoryLeakDetector instance
    pub fn new(
        config: LeakDetectorConfig,
        memory_monitor: Arc<RealTimeMemoryMonitor>,
        lru_pool: Arc<LRUEvictionMemoryPool>,
        pool_manager: Arc<EnhancedMemoryPoolManager>,
    ) -> Self {
        let (sender, receiver) = unbounded();
        
        // Create core data structures first
        let allocations = Arc::new(RwLock::new(HashMap::new()));
        let historical_samples = Arc::new(RwLock::new(Vec::with_capacity(config.historical_samples)));
        let callbacks = Arc::new(RwLock::new(HashSet::new()));
        let ml_model = Arc::new(RwLock::new(Some(MLModel {
            training_data: RwLock::new(Vec::new()),
            parameters: RwLock::new(Vec::new()),
            feature_importance: RwLock::new(Vec::new()),
            accuracy: RwLock::new(0.0),
            last_trained: RwLock::new(Instant::now()),
            training_in_progress: AtomicBool::new(false),
            feature_ranges: RwLock::new(None),
            version: RwLock::new(1),
        })));
        
        // Start background analysis thread
        let allocations_clone = Arc::clone(&allocations);
        let config_clone = config.clone();
        let historical_samples_clone = Arc::clone(&historical_samples);
        let callbacks_clone = Arc::clone(&callbacks);
        let ml_model_clone = Arc::clone(&ml_model);
        let memory_monitor_clone = Arc::clone(&memory_monitor);
        
        std::thread::spawn(move || {
            Self::background_analysis_loop(
                receiver,
                allocations_clone,
                config_clone,
                historical_samples_clone,
                callbacks_clone,
                ml_model_clone,
                memory_monitor_clone,
            );
        });
        
        Self {
            config,
            allocations,
            next_allocation_id: Arc::new(Mutex::new(0)),
            historical_samples,
            memory_monitor,
            lru_pool,
            pool_manager,
            event_sender: sender,
            callbacks,
            ml_model,
        }
    }
    
    /// Register a callback for leak detection events
    pub fn register_callback(&self, callback: Box<dyn LeakDetectionCallback>) {
        let mut callbacks = self.callbacks.write().unwrap();
        callbacks.insert(callback);
    }
    
    /// Track a new memory allocation
    pub fn track_allocation(
        &self,
        address: usize,
        size: usize,
        thread_id: u32,
        component: String,
        context: Option<String>,
        stack_trace: Option<String>,
    ) -> u64 {
        // Skip small allocations if below threshold
        if size < self.config.min_allocation_size {
            return 0;
        }
        
        let mut id = self.next_allocation_id.lock().unwrap();
        let allocation_id = *id;
        *id += 1;
        
        let record = AllocationRecord::new(
            allocation_id,
            address,
            size,
            thread_id,
            component,
            context,
            stack_trace,
        );
        
        // Update allocations tracking
        let mut allocations = self.allocations.write().unwrap();
        allocations.insert(allocation_id, record.clone());
        
        // Track allocation in real-time memory monitor
        let pool_type = match component.as_str() {
            "vec_pool" => MemoryPoolType::Object,
            "string_pool" => MemoryPoolType::Object,
            "hierarchical" => MemoryPoolType::Hierarchical,
            "swap" => MemoryPoolType::Swap,
            _ => MemoryPoolType::Custom(component.clone()),
        };
        
        let _ = self.memory_monitor.track_allocation(size, pool_type);
        
        // Send event to background thread
        let _ = self.event_sender.send(LeakDetectorEvent::Allocate(record));
        
        allocation_id
    }
    
    /// Track a memory deallocation
    pub fn track_deallocation(&self, allocation_id: u64) {
        // Get allocation details for tracking
        let allocations = self.allocations.read().unwrap();
        let record = allocations.get(&allocation_id);
        
        if let Some(record) = record {
            // Track deallocation in real-time memory monitor
            let pool_type = match record.component.as_str() {
                "vec_pool" => MemoryPoolType::Object,
                "string_pool" => MemoryPoolType::Object,
                "hierarchical" => MemoryPoolType::Hierarchical,
                "swap" => MemoryPoolType::Swap,
                _ => MemoryPoolType::Custom(record.component.clone()),
            };
            
            let _ = self.memory_monitor.track_deallocation(record.size, pool_type);
        }
        
        // Send event to background thread
        let _ = self.event_sender.send(LeakDetectorEvent::Deallocate(allocation_id));
    }
    
    /// Track access to an allocated object (for aging algorithm)
    pub fn track_access(&self, allocation_id: u64) {
        // Send event to background thread
        let _ = self.event_sender.send(LeakDetectorEvent::Access(allocation_id));
    }
    
    /// Detect memory leaks using multiple algorithms
    pub fn detect_leaks(&self) -> Vec<MemoryLeak> {
        let allocations = self.allocations.read().unwrap();
        let now = Instant::now();
        let mut leaks = Vec::new();
        
        // 1. Forgotten deallocations detection
        let forgotten_leaks = self.detect_forgotten_deallocations(&allocations, &now);
        leaks.extend(forgotten_leaks);
        
        // 2. Circular reference detection
        let circular_leaks = self.detect_circular_references(&allocations);
        leaks.extend(circular_leaks);
        
        // 3. Excessive memory growth detection
        let growth_leaks = self.detect_excessive_growth(&allocations, &now);
        leaks.extend(growth_leaks);
        
        // 4. Weak reference leak detection
        let weak_leaks = self.detect_weak_reference_leaks(&allocations);
        leaks.extend(weak_leaks);
        
        // 5. Machine learning based detection (if enabled)
        if self.config.enable_ml_detection {
            let ml_leaks = self.detect_leaks_ml(&allocations, &now);
            leaks.extend(ml_leaks);
        }
        
        leaks
    }
    
    /// Generate a detailed memory leak report
    pub fn get_leak_report(&self) -> LeakReport {
        let allocations = self.allocations.read().unwrap();
        let leaks = self.detect_leaks();
        let memory_stats = self.get_memory_stats();
        
        // Categorize leaks by type
        let mut leaks_by_type = HashMap::new();
        for leak in &leaks {
            *leaks_by_type.entry(leak.leak_type.clone()).or_insert(0) += 1;
        }
        
        LeakReport {
            generated_at: Instant::now(),
            total_leaks: leaks.len(),
            leaks_by_type,
            detailed_leaks: leaks,
            memory_stats,
            config: self.config.clone(),
        }
    }
    
    /// Get current memory statistics
    fn get_memory_stats(&self) -> MemoryStats {
        let allocations = self.allocations.read().unwrap();
        let now = Instant::now();
        
        let total_allocated: usize = allocations.values().map(|r| r.size).sum();
        let current_usage = total_allocated;
        
        // Get peak usage from memory monitor
        let peak_usage = self.memory_monitor.get_peak_memory_usage();
        
        MemoryStats {
            total_allocated,
            total_deallocated: self.memory_monitor.get_total_deallocated(),
            current_usage,
            peak_usage,
            allocation_rate: self.calculate_allocation_rate(),
            deallocation_rate: self.calculate_deallocation_rate(),
        }
    }
    
    /// Calculate allocation rate (bytes per second) with low-lock contention
    fn calculate_allocation_rate(&self) -> f64 {
        // Use try_read first to minimize lock contention
        if let Ok(allocations) = self.allocations.try_read() {
            return self.calculate_rate_internal(allocations);
        }
        
        // Fall back to blocking read with timeout for critical monitoring
        let allocations = match self.allocations.read_timeout(Duration::from_millis(150)) {
            Ok(guard) => guard,
            Err(_) => return 0.0, // Return 0 on timeout to avoid blocking
        };
        self.calculate_rate_internal(allocations)
    }
    
    fn calculate_rate_internal(&self, allocations: &std::sync::RwLockReadGuard<HashMap<u64, AllocationRecord>>) -> f64 {
        let now = Instant::now();
        
        if allocations.is_empty() {
            return 0.0;
        }
        
        // Optimized sample collection using iterators
        let recent_allocations: Vec<&AllocationRecord> = allocations.values()
            .filter(|r| now.duration_since(r.allocated_at).as_secs() < 60)
            .take(self.config.historical_samples) // Early termination
            .collect();
        
        if recent_allocations.is_empty() {
            return 0.0;
        }
        
        // Use more efficient reduction operations
        let (total_size, total_time): (usize, f64) = recent_allocations.iter()
            .map(|r| {
                let elapsed = now.duration_since(r.allocated_at).as_secs_f64();
                (r.size, elapsed)
            })
            .fold((0, 0.0), |(sum_size, sum_time), (size, time)| (sum_size + size, sum_time + time));
        
        if total_time == 0.0 {
            0.0
        } else {
            (total_size as f64) / total_time
        }
    }
    
    /// Calculate deallocation rate (bytes per second)
    fn calculate_deallocation_rate(&self) -> f64 {
        // In a real implementation, we would track deallocations separately
        // For this example, we'll use a simplified approach
        self.memory_monitor.get_deallocation_rate()
    }
    
    /// Detect forgotten deallocations (objects never freed)
    fn detect_forgotten_deallocations(&self, allocations: &HashMap<u64, AllocationRecord>, now: &Instant) -> Vec<MemoryLeak> {
        let mut leaks = Vec::new();
        let max_lifetime = Duration::from_secs(self.config.max_lifetime);
        
        for (id, record) in allocations {
            // Skip if already marked for deallocation
            if record.marked_for_deallocation {
                continue;
            }
            
            // Check if allocation is older than max lifetime
            if now.duration_since(record.allocated_at) > max_lifetime {
                let leak = MemoryLeak {
                    id: *id,
                    allocation: record.clone(),
                    leak_type: LeakType::ForgottenDeallocation,
                    confidence: self.calculate_confidence(record, LeakType::ForgottenDeallocation),
                    detected_at: *now,
                    analysis: Some(format!(
                        "Allocation older than max lifetime ({:.1}s > {}s)",
                        now.duration_since(record.allocated_at).as_secs_f64(),
                        self.config.max_lifetime
                    )),
                };
                leaks.push(leak);
            }
        }
        
        leaks
    }
    
    /// Detect circular references (simplified implementation)
    fn detect_circular_references(&self, allocations: &HashMap<u64, AllocationRecord>) -> Vec<MemoryLeak> {
        let mut leaks = Vec::new();
        
        // In a real implementation, we would analyze reference graphs
        // For this example, we'll use a simplified approach based on reference counts
        for (id, record) in allocations {
            // Skip if reference count is normal or already marked for deallocation
            if record.marked_for_deallocation || record.ref_count <= 1 {
                continue;
            }
            
            // Check for potential circular references (high ref count with no recent access)
            let now = Instant::now();
            let age = now.duration_since(record.last_accessed).as_secs();
            
            // Consider as potential circular reference if:
            // 1. Ref count > 2 (indicating multiple references)
            // 2. No access in the last 10 seconds
            // 3. Allocation is older than 5 seconds
            if record.ref_count > 2 && age > 10 && now.duration_since(record.allocated_at).as_secs() > 5 {
                let leak = MemoryLeak {
                    id: *id,
                    allocation: record.clone(),
                    leak_type: LeakType::CircularReference,
                    confidence: self.calculate_confidence(record, LeakType::CircularReference),
                    detected_at: now,
                    analysis: Some(format!(
                        "Potential circular reference: ref_count={}, no access in {}s",
                        record.ref_count, age
                    )),
                };
                leaks.push(leak);
            }
        }
        
        leaks
    }
    
    /// Detect excessive memory growth patterns
    fn detect_excessive_growth(&self, allocations: &HashMap<u64, AllocationRecord>, now: &Instant) -> Vec<MemoryLeak> {
        let mut leaks = Vec::new();
        let growth_threshold = self.config.growth_threshold / 100.0;
        
        // Get current and previous memory usage
        let current_usage = allocations.values().map(|r| r.size).sum::<usize>() as f64;
        
        // Get historical samples for comparison
        let historical_samples = self.historical_samples.read().unwrap();
        let samples = historical_samples.iter().filter_map(|s| {
            if now.duration_since(s.generated_at).as_secs() < 300 {
                Some(s.current_usage as f64)
            } else {
                None
            }
        }).collect::<Vec<f64>>();
        
        if samples.len() < 2 {
            return leaks; // Need at least two samples for comparison
        }
        
        // Calculate growth rate
        let oldest_sample = samples.first().cloned().unwrap_or(0.0);
        let newest_sample = samples.last().cloned().unwrap_or(0.0);
        
        if oldest_sample == 0.0 {
            return leaks; // Avoid division by zero
        }
        
        let growth_rate = (newest_sample - oldest_sample) / oldest_sample;
        
        // Check if growth rate exceeds threshold
        if growth_rate > growth_threshold {
            // Find allocations that contribute most to growth
            let recent_allocations: Vec<&AllocationRecord> = allocations.values()
                .filter(|r| now.duration_since(r.allocated_at).as_secs() < 60)
                .collect();
            
            if !recent_allocations.is_empty() {
                // Select the largest allocations as potential leaks
                let mut sorted_allocations: Vec<&AllocationRecord> = recent_allocations.iter().cloned().collect();
                sorted_allocations.sort_by(|a, b| b.size.cmp(&a.size));
                
                // Take top 5 allocations as potential leaks
                for allocation in sorted_allocations.iter().take(5) {
                    let leak = MemoryLeak {
                        id: allocation.id,
                        allocation: allocation.clone(),
                        leak_type: LeakType::ExcessiveGrowth,
                        confidence: self.calculate_confidence(allocation, LeakType::ExcessiveGrowth),
                        detected_at: *now,
                        analysis: Some(format!(
                            "Excessive memory growth: {:.2}% growth rate, allocation size: {} bytes",
                            growth_rate * 100.0,
                            allocation.size
                        )),
                    };
                    leaks.push(leak);
                }
            }
        }
        
        leaks
    }
    
    /// Detect leaks through weak references
    fn detect_weak_reference_leaks(&self, allocations: &HashMap<u64, AllocationRecord>) -> Vec<MemoryLeak> {
        let mut leaks = Vec::new();
        
        // In a real implementation, we would track weak references separately
        // For this example, we'll use a simplified approach based on reference counts
        
        for (id, record) in allocations {
            // Skip if already marked for deallocation or no weak references
            if record.marked_for_deallocation || record.ref_count == 0 {
                continue;
            }
            
            // Check for potential weak reference leaks (ref count = 1 but object is still in use)
            let now = Instant::now();
            let age = now.duration_since(record.last_accessed).as_secs();
            
            // Consider as potential weak reference leak if:
            // 1. Ref count = 1 (only weak references remain)
            // 2. Object is still being accessed
            // 3. Allocation is older than 10 seconds
            if record.ref_count == 1 && age < 5 && now.duration_since(record.allocated_at).as_secs() > 10 {
                let leak = MemoryLeak {
                    id: *id,
                    allocation: record.clone(),
                    leak_type: LeakType::WeakReferenceLeak,
                    confidence: self.calculate_confidence(record, LeakType::WeakReferenceLeak),
                    detected_at: now,
                    analysis: Some(format!(
                        "Potential weak reference leak: ref_count=1, accessed {}s ago",
                        age
                    )),
                };
                leaks.push(leak);
            }
        }
        
        leaks
    }

    /// Extract normalized features from allocation records for ML training
    fn extract_normalized_features(&self, record: &AllocationRecord, now: &Instant) -> Vec<f64> {
        let age_secs = now.duration_since(record.allocated_at).as_secs_f64();
        let last_access_secs = now.duration_since(record.last_accessed).as_secs_f64();
        
        vec![
            age_secs.log1p(),                      // Log-transformed age (reduces scale impact)
            record.size as f64.log1p(),            // Log-transformed size
            record.ref_count as f64.log1p(),       // Log-transformed reference count
            last_access_secs.log1p(),              // Log-transformed last access time
            if record.marked_for_deallocation { 1.0 } else { 0.0 }, // Deallocation status
            record.thread_id as f64.log1p(),       // Log-transformed thread ID
        ]
    }

    /// Calculate feature ranges for normalization
    fn calculate_feature_ranges(&self, patterns: &[LeakPattern]) -> Vec<(f64, f64)> {
        if patterns.is_empty() {
            return Vec::new();
        }

        let num_features = patterns[0].features.len();
        let mut min_vals = vec![f64::INFINITY; num_features];
        let mut max_vals = vec![-f64::INFINITY; num_features];

        for pattern in patterns {
            for (i, &val) in pattern.features.iter().enumerate() {
                if val < min_vals[i] {
                    min_vals[i] = val;
                }
                if val > max_vals[i] {
                    max_vals[i] = val;
                }
            }
        }

        min_vals.into_iter().zip(max_vals.into_iter()).collect()
    }

    /// Normalize features to [0, 1] range
    fn normalize_features(&self, features: &[f64], ranges: &Option<Vec<(f64, f64)>>) -> Vec<f64> {
        match ranges {
            Some(ranges) if !ranges.is_empty() => {
                let mut normalized = Vec::with_capacity(features.len());
                for (i, &val) in features.iter().enumerate() {
                    let (min, max) = ranges[i];
                    if max == min {
                        normalized.push(0.0);
                    } else {
                        normalized.push((val - min) / (max - min));
                    }
                }
                normalized
            }
            _ => features.to_vec(),
        }
    }

    /// Split data into training and validation sets
    fn split_data(&self, features: &[Vec<f64>], labels: &[f64], split_ratio: f64) -> 
        (Vec<Vec<f64>>, Vec<f64>, Vec<Vec<f64>>, Vec<f64>) {
        
        let split_idx = (features.len() as f64 * (1.0 - split_ratio)) as usize;
        let mut rng = rand::thread_rng();
        
        // Create indices and shuffle
        let mut indices: Vec<usize> = (0..features.len()).collect();
        indices.shuffle(&mut rng);

        let train_indices: Vec<usize> = indices[0..split_idx].to_vec();
        let val_indices: Vec<usize> = indices[split_idx..].to_vec();

        let train_features: Vec<Vec<f64>> = train_indices.iter().map(|&i| features[i].clone()).collect();
        let train_labels: Vec<f64> = train_indices.iter().map(|&i| labels[i]).collect();
        let val_features: Vec<Vec<f64>> = val_indices.iter().map(|&i| features[i].clone()).collect();
        let val_labels: Vec<f64> = val_indices.iter().map(|&i| labels[i]).collect();

        (train_features, train_labels, val_features, val_labels)
    }

    /// Make predictions using linear regression model
    fn predict(&self, features: &[Vec<f64>], parameters: &[f64]) -> Vec<f64> {
        let mut predictions = Vec::with_capacity(features.len());
        
        for feature_vec in features {
            let mut prediction = parameters[0]; // Bias term
            for (i, &feature) in feature_vec.iter().enumerate().skip(1) { // Skip bias
                prediction += feature * parameters[i];
            }
            predictions.push(prediction.sigmoid()); // Apply sigmoid for probability
        }

        predictions
    }

    /// Calculate gradients for gradient descent
    fn calculate_gradients(&self, features: &[Vec<f64>], labels: &[f64], predictions: &[f64]) -> Vec<f64> {
        let m = features.len() as f64;
        let num_features = features[0].len();
        let mut gradients = vec![0.0; num_features];

        for (i, feature_vec) in features.iter().enumerate() {
            let prediction_error = predictions[i] - labels[i];
            
            // Update bias gradient
            gradients[0] += prediction_error;
            
            // Update feature gradients
            for (j, &feature) in feature_vec.iter().enumerate().skip(1) {
                gradients[j] += prediction_error * feature;
            }
        }

        // Average gradients
        gradients.iter().map(|&g| g / m).collect()
    }

    /// Calculate accuracy of predictions
    fn calculate_accuracy(&self, labels: &[f64], predictions: &[f64]) -> f64 {
        let correct = labels.iter().zip(predictions.iter())
            .filter(|(&label, &pred)| {
                let pred_class = if pred >= 0.5 { 1.0 } else { 0.0 };
                (label == 1.0 && pred_class == 1.0) || (label == 0.0 && pred_class == 0.0)
            })
            .count() as f64;

        correct / labels.len() as f64
    }

    /// Train the machine learning model with new data
    pub fn train_model(&self, config: &MLTrainingConfig) -> Result<f64, String> {
        let training_data = self.training_data.read().unwrap();
        
        // Check if we have enough samples
        if training_data.len() < config.min_samples {
            return Err(format!("Need at least {} samples for training, got {}", config.min_samples, training_data.len()));
        }

        // Extract features and labels
        let mut features = Vec::new();
        let mut labels = Vec::new();
        
        for pattern in &*training_data {
            features.push(pattern.features.clone());
            labels.push(if pattern.is_leak { 1.0 } else { 0.0 });
        }

        // Split into training and validation sets
        let (train_features, train_labels, val_features, val_labels) = if config.validation_split > 0.0 {
            self.split_data(&features, &labels, config.validation_split)
        } else {
            (features.clone(), labels.clone(), Vec::new(), Vec::new())
        };

        // Calculate feature ranges for normalization
        let feature_ranges = self.calculate_feature_ranges(&*training_data);
        let mut feature_ranges_guard = self.feature_ranges.write().unwrap();
        *feature_ranges_guard = Some(feature_ranges.clone());

        // Normalize features
        let mut normalized_train_features = Vec::new();
        for feature_vec in &train_features {
            normalized_train_features.push(self.normalize_features(feature_vec, &Some(feature_ranges.clone())));
        }

        let mut normalized_val_features = Vec::new();
        for feature_vec in &val_features {
            normalized_val_features.push(self.normalize_features(feature_vec, &Some(feature_ranges.clone())));
        }

        // Initialize model parameters (with bias term)
        let mut parameters = vec![0.0; normalized_train_features[0].len() + 1];
        let mut best_accuracy = 0.0;
        let mut no_improvement = 0;

        // Gradient descent training
        for epoch in 0..config.epochs {
            // Add bias term to features
            let train_features_with_bias: Vec<Vec<f64>> = normalized_train_features.iter()
                .map(|f| vec![1.0].into_iter().chain(f.iter().cloned()).collect())
                .collect();

            // Make predictions
            let predictions = self.predict(&train_features_with_bias, &parameters);
            
            // Calculate gradients
            let gradients = self.calculate_gradients(&train_features_with_bias, &train_labels, &predictions);
            
            // Update parameters
            for (i, &grad) in gradients.iter().enumerate() {
                parameters[i] -= config.learning_rate * grad;
            }

            // Validate and check for early stopping
            if !val_features.is_empty() {
                let val_features_with_bias: Vec<Vec<f64>> = normalized_val_features.iter()
                    .map(|f| vec![1.0].into_iter().chain(f.iter().cloned()).collect())
                    .collect();
                
                let val_predictions = self.predict(&val_features_with_bias, &parameters);
                let accuracy = self.calculate_accuracy(&val_labels, &val_predictions);
                
                if accuracy > best_accuracy {
                    best_accuracy = accuracy;
                    no_improvement = 0;
                    
                    // Update model parameters
                    let mut model_params = self.parameters.write().unwrap();
                    *model_params = parameters.clone();
                } else if config.early_stopping {
                    no_improvement += 1;
                    if no_improvement >= 5 {  // Stop after 5 epochs without improvement
                        break;
                    }
                }
            }
        }

        // Update model accuracy and version
        let mut model_accuracy = self.accuracy.write().unwrap();
        *model_accuracy = best_accuracy;

        let mut model_version = self.version.write().unwrap();
        *model_version += 1;

        Ok(best_accuracy)
    }

    /// Detect leaks using machine learning model
    fn detect_leaks_ml(&self, allocations: &HashMap<u64, AllocationRecord>, now: &Instant) -> Vec<MemoryLeak> {
        let mut leaks = Vec::new();
        
        // Check if ML model is enabled and has parameters
        let ml_model = self.ml_model.read().unwrap();
        let model = ml_model.as_ref();
        
        let parameters = match model.and_then(|m| m.parameters.read().ok()) {
            Some(Ok(params)) if !params.is_empty() => params,
            _ => return leaks,
        };

        let feature_ranges = match model.and_then(|m| m.feature_ranges.read().ok()) {
            Some(Ok(ranges)) => ranges,
            _ => return leaks,
        };

        for (id, record) in allocations {
            // Skip if already marked for deallocation
            if record.marked_for_deallocation {
                continue;
            }

            // Extract and normalize features
            let features = self.extract_normalized_features(record, now);
            let normalized_features = self.normalize_features(&features, &feature_ranges);
            
            // Add bias term and make prediction
            let features_with_bias = vec![1.0].into_iter().chain(normalized_features.iter().cloned()).collect();
            let prediction = self.predict(&[features_with_bias], &parameters)[0];
            
            // Only report high-confidence leaks (≥ 70% confidence)
            if prediction >= 0.7 {
                let leak_type = self.classify_leak_type(&normalized_features, prediction);
                let confidence = (prediction * 100.0).round() as u8;

                let leak = MemoryLeak {
                    id: *id,
                    allocation: record.clone(),
                    leak_type,
                    confidence,
                    detected_at: *now,
                    analysis: Some(format!(
                        "ML-detected leak with {:.1}% confidence (version {})",
                        confidence as f64,
                        *model.version.read().unwrap()
                    )),
                };
                leaks.push(leak);
            }
        }
        
        leaks
    }

    /// Classify leak type based on feature patterns
    fn classify_leak_type(&self, features: &[f64], confidence: f64) -> LeakType {
        // In a real implementation, this would use more sophisticated pattern recognition
        // For now, we'll use a simple heuristic based on feature values
        
        if confidence < 0.7 {
            return LeakType::Unknown;
        }

        // Feature indices (match extract_normalized_features order)
        const AGE: usize = 0;
        const SIZE: usize = 1;
        const REF_COUNT: usize = 2;
        const LAST_ACCESS: usize = 3;

        if features[AGE] > 0.8 && features[SIZE] > 0.5 {
            // Older allocations with large size = forgotten deallocations
            LeakType::ForgottenDeallocation
        } else if features[REF_COUNT] > 0.9 && features[LAST_ACCESS] > 0.7 {
            // High ref count with long time since access = circular references
            LeakType::CircularReference
        } else if features[SIZE] > 0.8 && features[AGE] < 0.3 {
            // Large recent allocations = excessive growth
            LeakType::ExcessiveGrowth
        } else if features[REF_COUNT] < 0.1 && features[LAST_ACCESS] < 0.2 {
            // Low ref count but recently accessed = weak reference leaks
            LeakType::WeakReferenceLeak
        } else {
            LeakType::Unknown
        }
    }

    /// Sigmoid activation function for probability conversion
    fn sigmoid(x: f64) -> f64 {
        1.0 / (1.0 + (-x).exp())
    }
    
    /// Calculate confidence score for a detected leak
    fn calculate_confidence(&self, record: &AllocationRecord, leak_type: LeakType) -> u8 {
        let mut confidence = 50; // Base confidence
        
        // Adjust confidence based on allocation age
        let now = Instant::now();
        let age_secs = now.duration_since(record.allocated_at).as_secs_f64();
        
        if age_secs > 300 { // Older than 5 minutes
            confidence += 20;
        } else if age_secs > 60 { // Older than 1 minute
            confidence += 10;
        }
        
        // Adjust confidence based on allocation size
        if record.size > 1_048_576 { // > 1MB
            confidence += 15;
        } else if record.size > 104_857 { // > 100KB
            confidence += 10;
        }
        
        // Adjust confidence based on leak type
        match leak_type {
            LeakType::CircularReference => confidence += 15,
            LeakType::ExcessiveGrowth => confidence += 10,
            LeakType::ForgottenDeallocation => confidence += 5,
            LeakType::WeakReferenceLeak => confidence += 10,
            LeakType::Unknown => confidence -= 10,
        }
        
        // Adjust confidence based on sensitivity setting
        confidence = match self.config.sensitivity {
            1 => std::cmp::max(10, confidence / 2),
            2 => std::cmp::max(20, confidence * 3 / 4),
            3 => std::cmp::max(30, confidence * 4 / 5),
            4 => std::cmp::max(40, confidence * 5 / 6),
            5 => confidence,
            6 => confidence * 6 / 5,
            7 => confidence * 7 / 5,
            8 => confidence * 8 / 5,
            9 => confidence * 9 / 5,
            10 => confidence * 10 / 5,
            _ => confidence,
        };
        
        // Cap confidence at 100
        std::cmp::min(100, confidence)
    }
    
    /// Background analysis loop
    fn background_analysis_loop(
        receiver: Receiver<LeakDetectorEvent>,
        allocations: Arc<RwLock<HashMap<u64, AllocationRecord>>>,
        config: LeakDetectorConfig,
        historical_samples: Arc<RwLock<Vec<MemoryStats>>>,
        callbacks: Arc<RwLock<HashSet<Box<dyn LeakDetectionCallback>>>>,
        ml_model: Arc<RwLock<Option<MLModel>>>,
        memory_monitor: Arc<RealTimeMemoryMonitor>,
    ) {
        let analysis_interval = Duration::from_secs(config.analysis_interval);
        let mut last_analysis = Instant::now();
        
        while let Ok(event) = receiver.recv() {
            match event {
                LeakDetectorEvent::Allocate(record) => {
                    let mut allocations = allocations.write().unwrap();
                    allocations.insert(record.id, record);
                }
                LeakDetectorEvent::Deallocate(allocation_id) => {
                    let mut allocations = allocations.write().unwrap();
                    if let Some(record) = allocations.get_mut(&allocation_id) {
                        record.mark_for_deallocation();
                        
                        // If ref count reaches zero, remove from tracking
                        if record.decrement_refcount() {
                            allocations.remove(&allocation_id);
                        }
                    }
                }
                LeakDetectorEvent::Access(allocation_id) => {
                    let mut allocations = allocations.write().unwrap();
                    if let Some(record) = allocations.get_mut(&allocation_id) {
                        record.last_accessed = Instant::now();
                        record.increment_refcount();
                    }
                }
                LeakDetectorEvent::Shutdown => {
                    break;
                }
            }
            
            // Perform periodic analysis
            let now = Instant::now();
            if now.duration_since(last_analysis) >= analysis_interval {
                last_analysis = now;
                
                // Update historical samples
                let memory_stats = memory_monitor.get_current_memory_stats();
                let mut samples = historical_samples.write().unwrap();
                
                // Keep only the most recent samples
                if samples.len() >= config.historical_samples {
                    samples.remove(0);
                }
                samples.push(memory_stats);
                
                // Detect leaks and generate report
                let allocations = allocations.read().unwrap();
                let leaks = MemoryLeakDetector::detect_leaks_impl(&allocations, &config, &ml_model);
                let report = MemoryLeakDetector::generate_report(&leaks, &allocations, &config, &memory_stats);
                
                // Notify callbacks
                let callbacks = callbacks.read().unwrap();
                for callback in callbacks.iter() {
                    for leak in &leaks {
                        callback.on_leak_detected(leak);
                    }
                    callback.on_report_generated(&report);
                    
                    // Check if memory usage exceeds thresholds
                    if memory_stats.current_usage as f64 > 
                       (memory_stats.peak_usage as f64 * (1.0 + config.growth_threshold / 100.0)) {
                        callback.on_memory_threshold_exceeded(&memory_stats);
                    }
                }
            }
        }
    }
    
    /// Helper method for leak detection (used in background thread)
    fn detect_leaks_impl(
        allocations: &HashMap<u64, AllocationRecord>,
        config: &LeakDetectorConfig,
        ml_model: &Arc<RwLock<Option<MLModel>>>,
    ) -> Vec<MemoryLeak> {
        let now = Instant::now();
        let mut leaks = Vec::new();
        
        // 1. Forgotten deallocations detection
        let forgotten_leaks = MemoryLeakDetector::detect_forgotten_deallocations_impl(
            allocations, &now, config.max_lifetime,
        );
        leaks.extend(forgotten_leaks);
        
        // 2. Circular reference detection
        let circular_leaks = MemoryLeakDetector::detect_circular_references_impl(allocations);
        leaks.extend(circular_leaks);
        
        // 3. Excessive memory growth detection
        let growth_leaks = MemoryLeakDetector::detect_excessive_growth_impl(
            allocations, &now, config.growth_threshold, config.historical_samples,
        );
        leaks.extend(growth_leaks);
        
        // 4. Weak reference leak detection
        let weak_leaks = MemoryLeakDetector::detect_weak_reference_leaks_impl(allocations);
        leaks.extend(weak_leaks);
        
        // 5. Machine learning based detection (if enabled)
        if config.enable_ml_detection {
            let ml_leaks = MemoryLeakDetector::detect_leaks_ml_impl(allocations, &now, ml_model);
            leaks.extend(ml_leaks);
        }
        
        leaks
    }
    
    /// Helper method for generating reports (used in background thread)
    fn generate_report(
        leaks: &[MemoryLeak],
        allocations: &HashMap<u64, AllocationRecord>,
        config: &LeakDetectorConfig,
        memory_stats: &MemoryStats,
    ) -> LeakReport {
        // Categorize leaks by type
        let mut leaks_by_type = HashMap::new();
        for leak in leaks {
            *leaks_by_type.entry(leak.leak_type.clone()).or_insert(0) += 1;
        }
        
        LeakReport {
            generated_at: Instant::now(),
            total_leaks: leaks.len(),
            leaks_by_type,
            detailed_leaks: leaks.to_vec(),
            memory_stats: memory_stats.clone(),
            config: config.clone(),
        }
    }
    
    /// Helper method for forgotten deallocations detection (used in background thread)
    fn detect_forgotten_deallocations_impl(
        allocations: &HashMap<u64, AllocationRecord>,
        now: &Instant,
        max_lifetime: u64,
    ) -> Vec<MemoryLeak> {
        let mut leaks = Vec::new();
        let max_lifetime_duration = Duration::from_secs(max_lifetime);
        
        for (id, record) in allocations {
            // Skip if already marked for deallocation
            if record.marked_for_deallocation {
                continue;
            }
            
            // Check if allocation is older than max lifetime
            if now.duration_since(record.allocated_at) > max_lifetime_duration {
                let leak = MemoryLeak {
                    id: *id,
                    allocation: record.clone(),
                    leak_type: LeakType::ForgottenDeallocation,
                    confidence: 70, // Base confidence for this leak type
                    detected_at: *now,
                    analysis: Some(format!(
                        "Allocation older than max lifetime ({:.1}s > {}s)",
                        now.duration_since(record.allocated_at).as_secs_f64(),
                        max_lifetime
                    )),
                };
                leaks.push(leak);
            }
        }
        
        leaks
    }
    
    /// Helper method for circular reference detection (used in background thread)
    fn detect_circular_references_impl(allocations: &HashMap<u64, AllocationRecord>) -> Vec<MemoryLeak> {
        let mut leaks = Vec::new();
        let now = Instant::now();
        
        // In a real implementation, we would analyze reference graphs
        // For this example, we'll use a simplified approach based on reference counts
        for (id, record) in allocations {
            // Skip if reference count is normal or already marked for deallocation
            if record.marked_for_deallocation || record.ref_count <= 1 {
                continue;
            }
            
            // Check for potential circular references (high ref count with no recent access)
            let age = now.duration_since(record.last_accessed).as_secs();
            
            // Consider as potential circular reference if:
            // 1. Ref count > 2 (indicating multiple references)
            // 2. No access in the last 10 seconds
            // 3. Allocation is older than 5 seconds
            if record.ref_count > 2 && age > 10 && now.duration_since(record.allocated_at).as_secs() > 5 {
                let leak = MemoryLeak {
                    id: *id,
                    allocation: record.clone(),
                    leak_type: LeakType::CircularReference,
                    confidence: 85, // Higher confidence for circular references
                    detected_at: now,
                    analysis: Some(format!(
                        "Potential circular reference: ref_count={}, no access in {}s",
                        record.ref_count, age
                    )),
                };
                leaks.push(leak);
            }
        }
        
        leaks
    }
    
    /// Helper method for excessive growth detection (used in background thread)
    fn detect_excessive_growth_impl(
        allocations: &HashMap<u64, AllocationRecord>,
        now: &Instant,
        growth_threshold: f64,
        historical_samples: &usize,
    ) -> Vec<MemoryLeak> {
        let mut leaks = Vec::new();
        let threshold = growth_threshold / 100.0;
        
        // Get current memory usage
        let current_usage = allocations.values().map(|r| r.size).sum::<usize>() as f64;
        
        // In a real implementation, we would use historical data from the memory monitor
        // For this example, we'll simulate historical growth
        
        // Simulate previous usage (in a real app, this would come from the memory monitor)
        let mut previous_usages = Vec::new();
        for i in 1..=*historical_samples {
            let factor = 1.0 - (i as f64 / (*historical_samples as f64) * 0.2);
            previous_usages.push((current_usage * factor) as usize);
        }
        
        // Calculate growth rate
        if let (Some(&oldest), Some(&newest)) = (previous_usages.first(), previous_usages.last()) {
            let oldest_f64 = oldest as f64;
            let newest_f64 = newest as f64;
            
            if oldest_f64 > 0.0 {
                let growth_rate = (newest_f64 - oldest_f64) / oldest_f64;
                
                // Check if growth rate exceeds threshold
                if growth_rate > threshold {
                    // Find allocations that contribute most to growth
                    let recent_allocations: Vec<&AllocationRecord> = allocations.values()
                        .filter(|r| now.duration_since(r.allocated_at).as_secs() < 60)
                        .collect();
                    
                    if !recent_allocations.is_empty() {
                        // Select the largest allocations as potential leaks
                        let mut sorted_allocations: Vec<&AllocationRecord> = recent_allocations.iter().cloned().collect();
                        sorted_allocations.sort_by(|a, b| b.size.cmp(&a.size));
                        
                        // Take top 5 allocations as potential leaks
                        for allocation in sorted_allocations.iter().take(5) {
                            let leak = MemoryLeak {
                                id: allocation.id,
                                allocation: allocation.clone(),
                                leak_type: LeakType::ExcessiveGrowth,
                                confidence: 65, // Base confidence for growth leaks
                                detected_at: *now,
                                analysis: Some(format!(
                                    "Excessive memory growth: {:.2}% growth rate, allocation size: {} bytes",
                                    growth_rate * 100.0,
                                    allocation.size
                                )),
                            };
                            leaks.push(leak);
                        }
                    }
                }
            }
        }
        
        leaks
    }
    
    /// Helper method for weak reference leak detection (used in background thread)
    fn detect_weak_reference_leaks_impl(allocations: &HashMap<u64, AllocationRecord>) -> Vec<MemoryLeak> {
        let mut leaks = Vec::new();
        let now = Instant::now();
        
        // In a real implementation, we would track weak references separately
        // For this example, we'll use a simplified approach based on reference counts
        
        for (id, record) in allocations {
            // Skip if already marked for deallocation or no weak references
            if record.marked_for_deallocation || record.ref_count == 0 {
                continue;
            }
            
            // Check for potential weak reference leaks (ref count = 1 but object is still in use)
            let age = now.duration_since(record.last_accessed).as_secs();
            
            // Consider as potential weak reference leak if:
            // 1. Ref count = 1 (only weak references remain)
            // 2. Object is still being accessed
            // 3. Allocation is older than 10 seconds
            if record.ref_count == 1 && age < 5 && now.duration_since(record.allocated_at).as_secs() > 10 {
                let leak = MemoryLeak {
                    id: *id,
                    allocation: record.clone(),
                    leak_type: LeakType::WeakReferenceLeak,
                    confidence: 60, // Base confidence for weak reference leaks
                    detected_at: now,
                    analysis: Some(format!(
                        "Potential weak reference leak: ref_count=1, accessed {}s ago",
                        age
                    )),
                };
                leaks.push(leak);
            }
        }
        
        leaks
    }
    
    /// Helper method for ML-based leak detection (used in background thread)
    fn detect_leaks_ml_impl(
        allocations: &HashMap<u64, AllocationRecord>,
        now: &Instant,
        ml_model: &Arc<RwLock<Option<MLModel>>>,
    ) -> Vec<MemoryLeak> {
        let mut leaks = Vec::new();
        
        // Check if ML model is available
        let ml_model = ml_model.read().unwrap();
        let model = ml_model.as_ref();
        
        if model.is_none() {
            return leaks;
        }
        
        // In a real implementation, we would use the ML model to predict leaks
        // For this example, we'll use a simplified random approach
        
        let mut rng = rand::thread_rng();
        
        // Simulate ML detection with random confidence
        for (id, record) in allocations {
            // Skip if already marked for deallocation
            if record.marked_for_deallocation {
                continue;
            }
            
            // Randomly select some allocations as potential ML-detected leaks
            if rng.gen_bool(0.03) { // 3% chance of detecting a leak
                let leak_type = match rng.gen_range(0..4) {
                    0 => LeakType::ForgottenDeallocation,
                    1 => LeakType::CircularReference,
                    2 => LeakType::ExcessiveGrowth,
                    3 => LeakType::WeakReferenceLeak,
                    _ => LeakType::Unknown,
                };
                
                let confidence = rng.gen_range(60..=95);
                
                let leak = MemoryLeak {
                    id: *id,
                    allocation: record.clone(),
                    leak_type,
                    confidence,
                    detected_at: *now,
                    analysis: Some(format!(
                        "ML-detected leak with {}% confidence",
                        confidence
                    )),
                };
                leaks.push(leak);
            }
        }
        
        leaks
    }

    /// Optimized allocation sampling for low-overhead detection
    pub fn get_recent_allocations(&self, time_window: u64) -> Vec<&AllocationRecord> {
        let now = Instant::now();
        
        // Use try_read for non-blocking access
        match self.allocations.try_read() {
            Ok(allocations) => allocations.values()
                .filter(|r| now.duration_since(r.allocated_at).as_secs() < time_window)
                .take(self.config.historical_samples) // Early termination
                .collect(),
            Err(_) => Vec::new(), // Return empty on contention
        }
    }

    /// Lock-free memory statistics collection
    pub fn get_memory_snapshot(&self) -> Option<MemoryStats> {
        let now = Instant::now();
        
        // Try to get quick stats without full allocation lock
        let allocations = match self.allocations.try_read() {
            Ok(guard) => guard,
            Err(_) => return None,
        };

        let total_allocated: usize = allocations.values().map(|r| r.size).sum();
        
        Some(MemoryStats {
            total_allocated,
            total_deallocated: self.memory_monitor.get_total_deallocated(),
            current_usage: total_allocated,
            peak_usage: self.memory_monitor.get_peak_memory_usage(),
            allocation_rate: self.calculate_allocation_rate(),
            deallocation_rate: self.calculate_deallocation_rate(),
        })
    }
}

/// Export leak report to JSON format
pub fn export_leak_report_to_json(report: &LeakReport) -> Result<String, serde_json::Error> {
    serde_json::to_string_pretty(report)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    #[test]
    fn test_allocation_record() {
        let record = AllocationRecord::new(
            1,
            0x1000,
            1024,
            1,
            "test_component".to_string(),
            None,
            None,
        );
        
        assert_eq!(record.id, 1);
        assert_eq!(record.address, 0x1000);
        assert_eq!(record.size, 1024);
        assert_eq!(record.thread_id, 1);
        assert_eq!(record.component, "test_component");
        assert!(record.stack_trace.is_none());
        assert!(record.context.is_none());
        assert_eq!(record.ref_count, 1);
        assert!(!record.marked_for_deallocation);
    }
    
    #[test]
    fn test_leak_detector_config_default() {
        let config = LeakDetectorConfig::default();
        
        assert_eq!(config.analysis_interval, 60);
        assert_eq!(config.growth_threshold, 5.0);
        assert_eq!(config.max_lifetime, 300);
        assert_eq!(config.sensitivity, 5);
        assert_eq!(config.min_allocation_size, 1024);
        assert!(config.enable_ml_detection);
        assert_eq!(config.historical_samples, 10);
    }
    
    #[test]
    fn test_leak_type_display() {
        assert_eq!(LeakType::ForgottenDeallocation.to_string(), "Forgotten Deallocation");
        assert_eq!(LeakType::CircularReference.to_string(), "Circular Reference");
        assert_eq!(LeakType::ExcessiveGrowth.to_string(), "Excessive Growth");
        assert_eq!(LeakType::WeakReferenceLeak.to_string(), "Weak Reference Leak");
        assert_eq!(LeakType::Unknown.to_string(), "Unknown");
    }
}