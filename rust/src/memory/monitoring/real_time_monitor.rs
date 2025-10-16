use std::sync::{Arc, RwLock};
use std::time::{Instant, Duration};
use std::collections::VecDeque;
use std::thread;
use std::sync::atomic::{AtomicBool, Ordering};
use serde::Serialize;
use crate::errors::{Result, RustError};
use crate::logging::{generate_trace_id, PerformanceLogger};
use crate::memory::pool::common::{MemoryPoolStats, MemoryPoolConfig};
use crate::memory::pool::enhanced_manager::EnhancedMemoryPoolManager;
use crate::memory::pool::lru_eviction::LRUEvictionMemoryPool;
use crate::performance::monitoring::{PerformanceMonitor, PERFORMANCE_MONITOR};

/// Configuration for RealTimeMemoryMonitor
#[derive(Debug, Clone)]
pub struct RealTimeMonitorConfig {
    pub sampling_rate_ms: u64,
    pub allocation_threshold: u64,
    pub deallocation_threshold: u64,
    pub memory_pressure_threshold: f64,
    pub logger_name: String,
    pub buffer_size: usize,
}

impl Default for RealTimeMonitorConfig {
    fn default() -> Self {
        Self {
            sampling_rate_ms: 1000,
            allocation_threshold: 100 * 1024 * 1024, // 100MB
            deallocation_threshold: 50 * 1024 * 1024,  // 50MB
            memory_pressure_threshold: 0.9,           // 90%
            logger_name: "real_time_memory_monitor".to_string(),
            buffer_size: 60,                         // 60 seconds of history
        }
    }
}

/// Memory pool type enum for tracking
#[derive(Debug, Clone, PartialEq)]
pub enum MemoryPoolType {
    LRU,
    Hierarchical,
    Lightweight,
    Slab,
    Object,
    Buffer,
    Swap,
    Custom(String),
}

/// Detailed memory metrics structure
#[derive(Debug, Clone, Serialize)]
pub struct MemoryMetrics {
    pub total_memory_usage: u64,
    pub memory_by_pool_type: Vec<(MemoryPoolType, u64)>,
    pub allocation_rate_bps: f64,
    pub deallocation_rate_bps: f64,
    pub memory_pressure_level: MemoryPressureLevel,
    pub fragmentation_percent: f64,
    pub peak_memory_usage: u64,
    pub active_allocations: u64,
    pub evictions: u64,
    pub timestamp: u64,
}

/// Memory pressure levels
#[derive(Debug, Clone, Serialize, PartialEq)]
pub enum MemoryPressureLevel {
    Normal,
    Warning,
    Critical,
    Emergency,
}

/// Circular buffer for rate calculation
struct CircularBuffer<T> {
    buffer: VecDeque<T>,
    capacity: usize,
}

impl<T: Clone> CircularBuffer<T> {
    fn new(capacity: usize) -> Self {
        Self {
            buffer: VecDeque::with_capacity(capacity),
            capacity,
        }
    }

    fn push(&mut self, item: T) {
        if self.buffer.len() >= self.capacity {
            self.buffer.pop_front();
        }
        self.buffer.push_back(item);
    }

    fn get_all(&self) -> Vec<T> {
        self.buffer.iter().cloned().collect()
    }

    fn is_empty(&self) -> bool {
        self.buffer.is_empty()
    }

    fn len(&self) -> usize {
        self.buffer.len()
    }
}

/// Thread-safe real-time memory monitoring system
pub struct RealTimeMemoryMonitor {
    config: RealTimeMonitorConfig,
    logger: PerformanceLogger,
    is_running: Arc<AtomicBool>,
    monitor_thread: Option<thread::JoinHandle<()>>,
    
    // Core metrics
    total_allocated: Arc<RwLock<u64>>,
    total_deallocated: Arc<RwLock<u64>>,
    peak_usage: Arc<RwLock<u64>>,
    active_allocations: Arc<RwLock<u64>>,
    evictions: Arc<RwLock<u64>>,
    
    // Pool-specific metrics
    memory_by_pool: Arc<RwLock<Vec<(MemoryPoolType, u64)>>>,
    
    // Rate calculation buffers
    allocation_history: Arc<RwLock<CircularBuffer<(Instant, u64)>>>,
    deallocation_history: Arc<RwLock<CircularBuffer<(Instant, u64)>>>,
    
    // Memory pressure tracking
    memory_pressure_events: Arc<RwLock<u64>>,
    last_pressure_check: Arc<RwLock<Instant>>,
    
    // Integrated systems
    enhanced_pool_manager: Arc<RwLock<Option<EnhancedMemoryPoolManager>>>,
}

impl RealTimeMemoryMonitor {
    /// Create a new RealTimeMemoryMonitor with custom configuration
    pub fn new(config: Option<RealTimeMonitorConfig>) -> Result<Self> {
        let config = config.unwrap_or_default();
        let trace_id = generate_trace_id();
        
        let logger = PerformanceLogger::new(&config.logger_name);
        logger.log_info("new", &trace_id, "Real-time memory monitor initialized");
        
        Ok(Self {
            config,
            logger,
            is_running: Arc::new(AtomicBool::new(false)),
            monitor_thread: None,
            
            total_allocated: Arc::new(RwLock::new(0)),
            total_deallocated: Arc::new(RwLock::new(0)),
            peak_usage: Arc::new(RwLock::new(0)),
            active_allocations: Arc::new(RwLock::new(0)),
            evictions: Arc::new(RwLock::new(0)),
            
            memory_by_pool: Arc::new(RwLock::new(Vec::new())),
            
            allocation_history: Arc::new(RwLock::new(CircularBuffer::new(config.buffer_size))),
            deallocation_history: Arc::new(RwLock::new(CircularBuffer::new(config.buffer_size))),
            
            memory_pressure_events: Arc::new(RwLock::new(0)),
            last_pressure_check: Arc::new(RwLock::new(Instant::now())),
            
            enhanced_pool_manager: Arc::new(RwLock::new(None)),
        })
    }
    
    /// Track memory allocation
    pub fn track_allocation(&self, size: usize, pool_type: MemoryPoolType) -> Result<()> {
        let trace_id = generate_trace_id();
        
        // Update core metrics
        let mut total_allocated = self.total_allocated.write().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock total_allocated: {}", e))
        })?;
        *total_allocated += size as u64;
        
        let mut active_allocations = self.active_allocations.write().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock active_allocations: {}", e))
        })?;
        *active_allocations += 1;
        
        // Update pool-specific metrics
        let mut memory_by_pool = self.memory_by_pool.write().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock memory_by_pool: {}", e))
        })?;
        
        let entry_index = memory_by_pool.iter().position(|(pt, _)| *pt == pool_type);
        if let Some(idx) = entry_index {
            memory_by_pool[idx].1 += size as u64;
        } else {
            memory_by_pool.push((pool_type.clone(), size as u64));
        }
        
        // Update rate calculation history
        let mut allocation_history = self.allocation_history.write().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock allocation_history: {}", e))
        })?;
        allocation_history.push((Instant::now(), size as u64));
        
        // Check allocation threshold
        if *total_allocated > self.config.allocation_threshold {
            self.trigger_threshold_alert(
                "ALLOCATION_THRESHOLD",
                &format!(
                    "High allocation rate: {} bytes > threshold {}",
                    *total_allocated, self.config.allocation_threshold
                ),
            );
        }
        
        // Update peak usage
        let mut peak_usage = self.peak_usage.write().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock peak_usage: {}", e))
        })?;
        if *total_allocated > *peak_usage {
            *peak_usage = *total_allocated;
        }
        
        self.logger.log_info(
            "allocation",
            &trace_id,
            &format!(
                "Allocated {} bytes (total: {}, active: {}, pool: {:?})",
                size, *total_allocated, *active_allocations, pool_type
            ),
        );
        
        Ok(())
    }
    
    /// Track memory deallocation
    pub fn track_deallocation(&self, size: usize, pool_type: MemoryPoolType) -> Result<()> {
        let trace_id = generate_trace_id();
        
        // Update core metrics
        let mut total_deallocated = self.total_deallocated.write().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock total_deallocated: {}", e))
        })?;
        *total_deallocated += size as u64;
        
        let mut active_allocations = self.active_allocations.write().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock active_allocations: {}", e))
        })?;
        if *active_allocations > 0 {
            *active_allocations -= 1;
        }
        
        // Update pool-specific metrics
        let mut memory_by_pool = self.memory_by_pool.write().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock memory_by_pool: {}", e))
        })?;
        
        if let Some(entry) = memory_by_pool.iter_mut().find(|(pt, _)| *pt == pool_type) {
            if entry.1 >= size as u64 {
                entry.1 -= size as u64;
                
                // Remove entry if pool usage is zero
                if entry.1 == 0 {
                    let pos = memory_by_pool.iter().position(|(pt, _)| *pt == pool_type).unwrap();
                    memory_by_pool.remove(pos);
                }
            }
        }
        
        // Update rate calculation history
        let mut deallocation_history = self.deallocation_history.write().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock deallocation_history: {}", e))
        })?;
        deallocation_history.push((Instant::now(), size as u64));
        
        // Check deallocation threshold
        if *total_deallocated > self.config.deallocation_threshold {
            self.trigger_threshold_alert(
                "DEALLOCATION_THRESHOLD",
                &format!(
                    "High deallocation rate: {} bytes > threshold {}",
                    *total_deallocated, self.config.deallocation_threshold
                ),
            );
        }
        
        self.logger.log_info(
            "deallocation",
            &trace_id,
            &format!(
                "Deallocated {} bytes (total: {}, active: {}, pool: {:?})",
                size, *total_deallocated, *active_allocations, pool_type
            ),
        );
        
        Ok(())
    }
    
    /// Track memory eviction
    pub fn track_eviction(&self, size: usize, pool_type: MemoryPoolType) -> Result<()> {
        let trace_id = generate_trace_id();
        
        // Update eviction metrics
        let mut evictions = self.evictions.write().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock evictions: {}", e))
        })?;
        *evictions += 1;
        
        // Update pool-specific metrics for evictions
        let mut memory_by_pool = self.memory_by_pool.write().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock memory_by_pool: {}", e))
        })?;
        
        if let Some(entry) = memory_by_pool.iter_mut().find(|(pt, _)| *pt == pool_type) {
            if entry.1 >= size as u64 {
                entry.1 -= size as u64;
                
                // Remove entry if pool usage is zero
                if entry.1 == 0 {
                    let pos = memory_by_pool.iter().position(|(pt, _)| *pt == pool_type).unwrap();
                    memory_by_pool.remove(pos);
                }
            }
        }
        
        self.logger.log_info(
            "eviction",
            &trace_id,
            &format!(
                "Evicted {} bytes (total evictions: {}, pool: {:?})",
                size, *evictions, pool_type
            ),
        );
        
        Ok(())
    }
    
    /// Get current memory metrics
    pub fn get_memory_metrics(&self) -> Result<MemoryMetrics> {
        let now = Instant::now();
        let duration = now - self.last_pressure_check.read().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock last_pressure_check: {}", e))
        })?;
        
        // Check memory pressure if enough time has passed
        if duration.as_secs() >= 1 {
            let _ = self.check_memory_pressure();
            let mut last_check = self.last_pressure_check.write().map_err(|e| {
                RustError::OperationFailed(format!("Failed to lock last_pressure_check: {}", e))
            })?;
            *last_check = now;
        }
        
        // Calculate rates using circular buffer
        let allocation_rate = self.calculate_rate(&self.allocation_history)?;
        let deallocation_rate = self.calculate_rate(&self.deallocation_history)?;
        
        // Get current metrics
        let total_allocated = self.total_allocated.read().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock total_allocated: {}", e))
        })?;
        
        let total_deallocated = self.total_deallocated.read().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock total_deallocated: {}", e))
        })?;
        
        let peak_usage = self.peak_usage.read().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock peak_usage: {}", e))
        })?;
        
        let active_allocations = self.active_allocations.read().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock active_allocations: {}", e))
        })?;
        
        let evictions = self.evictions.read().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock evictions: {}", e))
        })?;
        
        let memory_by_pool = self.memory_by_pool.read().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock memory_by_pool: {}", e))
        })?;
        
        let total_usage = *total_allocated - *total_deallocated;
        let fragmentation = self.calculate_fragmentation()?;
        
        // Determine memory pressure level
        let pressure_level = self.determine_memory_pressure(total_usage)?;
        
        Ok(MemoryMetrics {
            total_memory_usage: total_usage,
            memory_by_pool_type: memory_by_pool.clone(),
            allocation_rate_bps: allocation_rate,
            deallocation_rate_bps: deallocation_rate,
            memory_pressure_level: pressure_level.clone(),
            fragmentation_percent: fragmentation,
            peak_memory_usage: *peak_usage,
            active_allocations: *active_allocations,
            evictions: *evictions,
            timestamp: now.elapsed().as_millis() as u64,
        })
    }
    
    /// Calculate allocation/deallocation rate in bytes per second
    fn calculate_rate(&self, history: &Arc<RwLock<CircularBuffer<(Instant, u64)>>>) -> Result<f64> {
        let history = history.read().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock history: {}", e))
        })?;
        
        if history.is_empty() {
            return Ok(0.0);
        }
        
        let entries = history.get_all();
        let oldest = entries.first().unwrap().0;
        let newest = entries.last().unwrap().0;
        
        let time_diff = newest.duration_since(oldest).as_secs_f64();
        if time_diff == 0.0 {
            return Ok(0.0);
        }
        
        let total_bytes: u64 = entries.iter().map(|(_, bytes)| *bytes).sum();
        Ok(total_bytes as f64 / time_diff)
    }
    
    /// Calculate memory fragmentation percentage
    fn calculate_fragmentation(&self) -> Result<f64> {
        let memory_by_pool = self.memory_by_pool.read().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock memory_by_pool: {}", e))
        })?;
        
        let total_usage = self.total_allocated.read().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock total_allocated: {}", e))
        })? - self.total_deallocated.read().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock total_deallocated: {}", e))
        })?;
        
        if total_usage == 0 {
            return Ok(0.0);
        }
        
        // For simplicity, we'll use a basic fragmentation calculation
        // In a real system, this would be much more complex and depend on the specific allocator
        let allocated_blocks = self.active_allocations.read().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock active_allocations: {}", e))
        })?;
        
        // This is a simplified calculation - real fragmentation would require more detailed info
        let fragmentation = if allocated_blocks == 0 {
            0.0
        } else {
            (allocated_blocks as f64 / total_usage as f64).min(1.0) * 100.0
        };
        
        Ok(fragmentation)
    }
    
    /// Check memory pressure and return current level
    fn check_memory_pressure(&self) -> Result<MemoryPressureLevel> {
        let trace_id = generate_trace_id();
        
        // Get enhanced pool manager if available
        let enhanced_pool = self.enhanced_pool_manager.read().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock enhanced_pool_manager: {}", e))
        })?;
        
        let pressure_level = if let Some(pool) = &*enhanced_pool {
            let pressure = pool.get_memory_pressure();
            
            if pressure > self.config.memory_pressure_threshold + 0.1 {
                MemoryPressureLevel::Emergency
            } else if pressure > self.config.memory_pressure_threshold + 0.05 {
                MemoryPressureLevel::Critical
            } else if pressure > self.config.memory_pressure_threshold {
                MemoryPressureLevel::Warning
            } else {
                MemoryPressureLevel::Normal
            }
        } else {
            // Fallback to basic calculation if no enhanced pool manager
            let total_usage = self.total_allocated.read().map_err(|e| {
                RustError::OperationFailed(format!("Failed to lock total_allocated: {}", e))
            })? - self.total_deallocated.read().map_err(|e| {
                RustError::OperationFailed(format!("Failed to lock total_deallocated: {}", e))
            })?;
            
            let peak_usage = self.peak_usage.read().map_err(|e| {
                RustError::OperationFailed(format!("Failed to lock peak_usage: {}", e))
            })?;
            
            if peak_usage == 0 {
                MemoryPressureLevel::Normal
            } else {
                let pressure = (total_usage as f64 / peak_usage as f64).min(1.0);
                
                if pressure > self.config.memory_pressure_threshold + 0.1 {
                    MemoryPressureLevel::Emergency
                } else if pressure > self.config.memory_pressure_threshold + 0.05 {
                    MemoryPressureLevel::Critical
                } else if pressure > self.config.memory_pressure_threshold {
                    MemoryPressureLevel::Warning
                } else {
                    MemoryPressureLevel::Normal
                }
            }
        };
        
        // Log pressure events
        if pressure_level != MemoryPressureLevel::Normal {
            let mut pressure_events = self.memory_pressure_events.write().map_err(|e| {
                RustError::OperationFailed(format!("Failed to lock memory_pressure_events: {}", e))
            })?;
            *pressure_events += 1;
            
            self.logger.log_warning(
                "memory_pressure",
                &trace_id,
                &format!("Memory pressure detected: {:?}", pressure_level),
            );
        }
        
        Ok(pressure_level)
    }
    
    /// Determine memory pressure level based on current usage
    fn determine_memory_pressure(&self, current_usage: u64) -> Result<MemoryPressureLevel> {
        let peak_usage = self.peak_usage.read().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock peak_usage: {}", e))
        })?;
        
        if *peak_usage == 0 {
            return Ok(MemoryPressureLevel::Normal);
        }
        
        let usage_ratio = (current_usage as f64 / *peak_usage as f64).min(1.0);
        
        if usage_ratio > self.config.memory_pressure_threshold + 0.1 {
            Ok(MemoryPressureLevel::Emergency)
        } else if usage_ratio > self.config.memory_pressure_threshold + 0.05 {
            Ok(MemoryPressureLevel::Critical)
        } else if usage_ratio > self.config.memory_pressure_threshold {
            Ok(MemoryPressureLevel::Warning)
        } else {
            Ok(MemoryPressureLevel::Normal)
        }
    }
    
    /// Trigger threshold alert
    fn trigger_threshold_alert(&self, alert_type: &str, message: &str) {
        let now = Instant::now();
        let last_alert_time = PERFORMANCE_MONITOR.last_alert_time_ns.load(Ordering::Relaxed);
        let cooldown_ms = PERFORMANCE_MONITOR.alert_cooldown.load(Ordering::Relaxed);
        
        // Convert cooldown to nanoseconds
        let cooldown_ns = cooldown_ms * 1_000_000;
        
        // Check cooldown to prevent alert spam
        if now.elapsed().as_nanos() < last_alert_time + cooldown_ns {
            return;
        }
        
        // Log the alert
        let trace_id = generate_trace_id();
        self.logger.log_error(
            "threshold_alert",
            &trace_id,
            &format!("[ALERT] {}: {}", alert_type, message),
            "MEMORY_ALERT",
        );
        
        // Update last alert time
        let _ = PERFORMANCE_MONITOR.last_alert_time_ns.compare_exchange(
            last_alert_time,
            now.elapsed().as_nanos() as u64,
            Ordering::Relaxed,
            Ordering::Relaxed,
        );
    }
    
    /// Start background monitoring thread
    pub fn start_monitoring(&mut self) -> Result<()> {
        if self.is_running.load(Ordering::Relaxed) {
            return Ok(());
        }
        
        let is_running = Arc::clone(&self.is_running);
        let config = self.config.clone();
        let logger = self.logger.clone();
        let monitor_clone = Arc::new(self.clone());
        
        self.is_running.store(true, Ordering::Relaxed);
        
        let handle = thread::spawn(move || {
            let sampling_duration = Duration::from_millis(config.sampling_rate_ms);
            
            while is_running.load(Ordering::Relaxed) {
                let start = Instant::now();
                
                // Get metrics and log them
                if let Ok(metrics) = monitor_clone.get_memory_metrics() {
                    let json = serde_json::to_string(&metrics).unwrap_or_else(|e| {
                        logger.log_error(
                            "json_serialization",
                            &generate_trace_id(),
                            &format!("Failed to serialize metrics: {}", e),
                            "SERIALIZATION_ERROR",
                        );
                        return;
                    });
                    
                    logger.log_info(
                        "memory_metrics",
                        &generate_trace_id(),
                        &format!("Real-time memory metrics: {}", json),
                    );
                }
                
                // Sleep for remaining time in sampling interval
                let elapsed = start.elapsed();
                if elapsed < sampling_duration {
                    thread::sleep(sampling_duration - elapsed);
                }
            }
            
            logger.log_info(
                "monitoring",
                &generate_trace_id(),
                "Real-time memory monitoring stopped",
            );
        });
        
        self.monitor_thread = Some(handle);
        Ok(())
    }
    
    /// Stop background monitoring thread
    pub fn stop_monitoring(&mut self) -> Result<()> {
        if !self.is_running.load(Ordering::Relaxed) {
            return Ok(());
        }
        
        self.is_running.store(false, Ordering::Relaxed);
        
        if let Some(handle) = self.monitor_thread.take() {
            let _ = handle.join();
        }
        
        let trace_id = generate_trace_id();
        self.logger.log_info(
            "monitoring",
            &trace_id,
            "Real-time memory monitoring stopped",
        );
        
        Ok(())
    }
    
    /// Integrate with EnhancedMemoryPoolManager
    pub fn integrate_with_enhanced_manager(&mut self, manager: EnhancedMemoryPoolManager) -> Result<()> {
        let mut enhanced_pool = self.enhanced_pool_manager.write().map_err(|e| {
            RustError::OperationFailed(format!("Failed to lock enhanced_pool_manager: {}", e))
        })?;
        
        *enhanced_pool = Some(manager);
        
        let trace_id = generate_trace_id();
        self.logger.log_info(
            "integration",
            &trace_id,
            "Integrated with EnhancedMemoryPoolManager",
        );
        
        Ok(())
    }
    
    /// Register callback for memory pressure events
    pub fn register_pressure_callback<F>(&self, callback: F) -> Result<()> 
    where
        F: Fn(MemoryPressureLevel) + Send + 'static,
    {
        // In a real implementation, we would store and manage these callbacks
        // For simplicity, we'll just log that a callback was registered
        let trace_id = generate_trace_id();
        self.logger.log_info(
            "callback",
            &trace_id,
            "Memory pressure callback registered",
        );
        
        Ok(())
    }
    
    /// Export metrics to JSON string
    pub fn export_metrics_to_json(&self) -> Result<String> {
        let metrics = self.get_memory_metrics()?;
        let json = serde_json::to_string_pretty(&metrics).map_err(|e| {
            RustError::OperationFailed(format!("Failed to serialize metrics to JSON: {}", e))
        })?;
        
        Ok(json)
    }
}

impl Clone for RealTimeMemoryMonitor {
    fn clone(&self) -> Self {
        Self {
            config: self.config.clone(),
            logger: self.logger.clone(),
            is_running: Arc::clone(&self.is_running),
            monitor_thread: None, // Can't clone thread handles
            
            total_allocated: Arc::clone(&self.total_allocated),
            total_deallocated: Arc::clone(&self.total_deallocated),
            peak_usage: Arc::clone(&self.peak_usage),
            active_allocations: Arc::clone(&self.active_allocations),
            evictions: Arc::clone(&self.evictions),
            
            memory_by_pool: Arc::clone(&self.memory_by_pool),
            
            allocation_history: Arc::clone(&self.allocation_history),
            deallocation_history: Arc::clone(&self.deallocation_history),
            
            memory_pressure_events: Arc::clone(&self.memory_pressure_events),
            last_pressure_check: Arc::clone(&self.last_pressure_check),
            
            enhanced_pool_manager: Arc::clone(&self.enhanced_pool_manager),
        }
    }
}

/// Global real-time memory monitor instance
pub static REAL_TIME_MEMORY_MONITOR: once_cell::sync::Lazy<RealTimeMemoryMonitor> = 
    once_cell::sync::Lazy::new(|| {
        RealTimeMemoryMonitor::new(None).expect("Failed to create global RealTimeMemoryMonitor")
    });

/// Track allocation in the global monitor
pub fn track_global_allocation(size: usize, pool_type: MemoryPoolType) {
    if let Err(e) = REAL_TIME_MEMORY_MONITOR.track_allocation(size, pool_type) {
        let trace_id = generate_trace_id();
        REAL_TIME_MEMORY_MONITOR.logger.log_error(
            "allocation_error",
            &trace_id,
            &format!("Failed to track allocation: {}", e),
            "MEMORY_ERROR",
        );
    }
}

/// Track deallocation in the global monitor
pub fn track_global_deallocation(size: usize, pool_type: MemoryPoolType) {
    if let Err(e) = REAL_TIME_MEMORY_MONITOR.track_deallocation(size, pool_type) {
        let trace_id = generate_trace_id();
        REAL_TIME_MEMORY_MONITOR.logger.log_error(
            "deallocation_error",
            &trace_id,
            &format!("Failed to track deallocation: {}", e),
            "MEMORY_ERROR",
        );
    }
}

/// Track eviction in the global monitor
pub fn track_global_eviction(size: usize, pool_type: MemoryPoolType) {
    if let Err(e) = REAL_TIME_MEMORY_MONITOR.track_eviction(size, pool_type) {
        let trace_id = generate_trace_id();
        REAL_TIME_MEMORY_MONITOR.logger.log_error(
            "eviction_error",
            &trace_id,
            &format!("Failed to track eviction: {}", e),
            "MEMORY_ERROR",
        );
    }
}

/// Start global monitoring
pub fn start_global_monitoring() {
    if let Err(e) = REAL_TIME_MEMORY_MONITOR.start_monitoring() {
        let trace_id = generate_trace_id();
        REAL_TIME_MEMORY_MONITOR.logger.log_error(
            "monitoring_error",
            &trace_id,
            &format!("Failed to start global monitoring: {}", e),
            "MEMORY_ERROR",
        );
    }
}

/// Stop global monitoring
pub fn stop_global_monitoring() {
    if let Err(e) = REAL_TIME_MEMORY_MONITOR.stop_monitoring() {
        let trace_id = generate_trace_id();
        REAL_TIME_MEMORY_MONITOR.logger.log_error(
            "monitoring_error",
            &trace_id,
            &format!("Failed to stop global monitoring: {}", e),
            "MEMORY_ERROR",
        );
    }
}

/// Get global memory metrics
pub fn get_global_memory_metrics() -> Option<MemoryMetrics> {
    REAL_TIME_MEMORY_MONITOR.get_memory_metrics().ok()
}

/// Export global metrics to JSON
pub fn export_global_metrics_to_json() -> Option<String> {
    REAL_TIME_MEMORY_MONITOR.export_metrics_to_json().ok()
}