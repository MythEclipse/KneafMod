use rand::Rng;
use std::alloc::{alloc, Layout, dealloc};
use std::ptr::NonNull;
use std::sync::{Arc, Mutex, RwLock};
use std::sync::atomic::{AtomicUsize, AtomicBool, Ordering};
use std::slice;
use std::time::{SystemTime, UNIX_EPOCH, Duration};
use std::thread;

/// Safe wrapper for memory chunk operations
#[derive(Debug)]
struct SafeChunk {
    /// Pointer to the start of the chunk
    ptr: NonNull<u8>,
    /// Current position in the chunk (offset from start)
    current_offset: usize,
    /// Total size of the chunk
    size: usize,
    /// Layout for proper deallocation
    layout: Layout,
}

#[allow(dead_code)]
impl SafeChunk {
    /// Create a new safe chunk from raw allocation
    fn new(size: usize, align: usize) -> Self {
        let layout = Layout::from_size_align(size, align).unwrap();
        let ptr = unsafe {
            let raw_ptr = alloc(layout);
            if raw_ptr.is_null() {
                std::alloc::handle_alloc_error(layout);
            }
            NonNull::new(raw_ptr).unwrap()
        };
        
        Self {
            ptr,
            current_offset: 0,
            size,
            layout,
        }
    }

    /// Deallocate the chunk
    fn deallocate(self) {
        unsafe {
            dealloc(self.ptr.as_ptr(), self.layout);
        }
    }

    /// Attempt to allocate `size` bytes with `align` alignment from this chunk.
    /// Returns a raw pointer to the allocated memory on success, or None if
    /// there isn't enough space.
    fn allocate(&mut self, size: usize, align: usize) -> Option<*mut u8> {
        // compute aligned offset
        let align = if align == 0 { 1 } else { align };
        let mask = align - 1;
        let mut offset = self.current_offset;
        if offset & mask != 0 {
            offset = (offset + align) & !mask;
        }

        if offset + size <= self.size {
            let ptr = unsafe { self.ptr.as_ptr().add(offset) };
            self.current_offset = offset + size;
            Some(ptr)
        } else {
            None
        }
    }
}
// Duplicate implementation removed

/// A fast bump allocator for temporary allocations with memory pressure awareness
pub struct BumpArena {
    /// The current chunk of memory we're allocating from
    current: Mutex<SafeChunk>,
    /// Size of chunks to allocate
    chunk_size: usize,
    /// Total bytes allocated
    pub total_allocated: AtomicUsize,
    /// Total bytes used
    total_used: AtomicUsize,
    /// Memory pressure monitor
    pressure_monitor: Arc<RwLock<ArenaPressureMonitor>>,
    /// High water mark for allocations
    high_water_mark: AtomicUsize,
    /// Last cleanup time
    last_cleanup_time: AtomicUsize,
    /// Is the arena being monitored?
    is_monitoring: AtomicBool,
    /// Is a critical operation in progress?
    #[allow(dead_code)]
    is_critical_operation: AtomicBool,
    /// Cleanup thresholds
    #[allow(dead_code)]
    lazy_cleanup_threshold: f64,
    #[allow(dead_code)]
    aggressive_cleanup_threshold: f64,
    #[allow(dead_code)]
    critical_cleanup_threshold: f64,
    /// Minimum allocation guard to prevent thrashing
    #[allow(dead_code)]
    min_allocation_guard: f64,
    /// Shutdown flag for monitoring thread
    shutdown_flag: Arc<AtomicBool>,
}

unsafe impl Send for SafeChunk {}
unsafe impl Sync for SafeChunk {}

impl BumpArena {
    /// Create a new bump arena with the specified chunk size
    pub fn new(chunk_size: usize) -> Self {
        let chunk_size = chunk_size.max(1024); // Minimum 1KB chunks
        let chunk = SafeChunk::new(chunk_size, 8);
        let pressure_monitor = Arc::new(RwLock::new(ArenaPressureMonitor::new()));
        
        Self {
            current: Mutex::new(chunk),
            chunk_size,
            total_allocated: AtomicUsize::new(chunk_size),
            total_used: AtomicUsize::new(0),
            pressure_monitor,
            high_water_mark: AtomicUsize::new(0),
            last_cleanup_time: AtomicUsize::new(SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs() as usize),
            is_monitoring: AtomicBool::new(false),
            is_critical_operation: AtomicBool::new(false),
            lazy_cleanup_threshold: 0.9, // Start lazy cleanup when 90% used
            aggressive_cleanup_threshold: 0.95, // Aggressive cleanup at 95%
            critical_cleanup_threshold: 0.98, // Critical threshold at 98%
            min_allocation_guard: 0.05, // Minimum allocation guard to prevent thrashing
            shutdown_flag: Arc::new(AtomicBool::new(false)),
        }
    }

    /// Safely allocate a new chunk with bounds checking
    fn allocate_safe_chunk(size: usize, align: usize) -> SafeChunk {
        SafeChunk::new(size, align)
    }

    /// Allocate memory from the arena with bounds checking and pressure awareness
    pub fn alloc(&self, size: usize, align: usize) -> *mut u8 {
        if size == 0 {
            return std::ptr::null_mut();
        }

        // Fast path: try allocation without locking first
        let result = self.try_fast_allocate(size, align);
        
        // If fast path failed, use slow path with locking
        if let Some(ptr) = result {
            ptr
        } else {
            self.slow_allocate(size, align)
        }
    }

    /// Fast allocation path without locking (tries current chunk first)
    fn try_fast_allocate(&self, size: usize, align: usize) -> Option<*mut u8> {
        // Skip locking for small allocations if we're under pressure
        if self.should_check_pressure_lightweight() {
            return None;
        }

        let mut chunk = match self.current.try_lock() {
            Ok(chunk) => chunk,
            Err(_) => return None, // Another thread is allocating, fall back to slow path
        };

        if let Some(ptr) = chunk.allocate(size, align) {
            self.total_used.fetch_add(size, Ordering::Relaxed);
            self.record_allocation(size);
            Some(ptr)
        } else {
            None
        }
    }

    /// Slow allocation path with full locking and chunk creation
    fn slow_allocate(&self, size: usize, align: usize) -> *mut u8 {
        let mut chunk = self.current.lock().expect("Arena mutex poisoned");
        
        // Try allocation again now that we have the lock
        if let Some(ptr) = chunk.allocate(size, align) {
            self.total_used.fetch_add(size, Ordering::Relaxed);
            self.record_allocation(size);
            return ptr;
        }

        // Need a new chunk
        let chunk_size = size.max(self.chunk_size);
        let mut new_chunk = Self::allocate_safe_chunk(chunk_size, align);
        
        // Allocate from new chunk
        let result = new_chunk.allocate(size, align)
            .expect("New chunk should have enough space");

        // Replace the current chunk
        let old_chunk = std::mem::replace(&mut *chunk, new_chunk);
        
        // Deallocate the old chunk to free memory
        old_chunk.deallocate();

        self.total_allocated.fetch_add(chunk_size, Ordering::Relaxed);
        self.total_used.fetch_add(size, Ordering::Relaxed);
        self.record_allocation(size);

        result
    }

    /// Simple memory pressure check hook with reduced frequency
    fn should_check_pressure_lightweight(&self) -> bool {
        // Only perform pressure checks 1/10 of the time to reduce overhead (from 1/5)
        if rand::thread_rng().gen::<f64>() < 0.1 {
            self.check_memory_pressure();
            true
        } else {
            false
        }
    }
    
        #[allow(dead_code)]
        fn allocate_new_chunk(&self, min_size: usize, align: usize) -> *mut u8 {
            let chunk_size = min_size.max(self.chunk_size);
                let mut new_chunk = Self::allocate_safe_chunk(chunk_size, align);
            
                // Try to allocate from the new chunk
                let result = new_chunk.allocate(min_size, align)
                    .expect("New chunk should have enough space");
            
            // Replace the current chunk
            let mut old_chunk = self.current.lock().expect("Arena mutex poisoned");
            let old_chunk = std::mem::replace(&mut *old_chunk, new_chunk);
            
            // Deallocate the old chunk to free memory
            old_chunk.deallocate();
            
            self.total_allocated.fetch_add(chunk_size, Ordering::Relaxed);
            self.total_used.fetch_add(min_size, Ordering::Relaxed);
            self.record_allocation(min_size);
            
            result
        }
    
        /// Start background monitoring
        pub fn start_monitoring(&self, check_interval_ms: u64) {
            if self.is_monitoring.load(Ordering::Relaxed) {
                return;
            }

            self.is_monitoring.store(true, Ordering::Relaxed);
            self.shutdown_flag.store(false, Ordering::Relaxed);
            
            let pressure_monitor = Arc::clone(&self.pressure_monitor);
            let shutdown_flag = Arc::clone(&self.shutdown_flag);

            thread::spawn(move || {
                let mut consecutive_errors = 0;
                const MAX_CONSECUTIVE_ERRORS: u32 = 5;
                
                while !shutdown_flag.load(Ordering::Relaxed) {
                    // Perform a lightweight pressure check using the monitor only
                    match std::panic::catch_unwind(|| {
                        if let Ok(monitor) = pressure_monitor.write() {
                            monitor.record_pressure_check(MemoryPressureLevel::Normal);
                        }
                    }) {
                        Ok(_) => {
                            consecutive_errors = 0; // Reset error counter on success
                        },
                        Err(e) => {
                            log::error!("Panic occurred in monitoring thread: {:?}", e);
                            consecutive_errors += 1;
                            if consecutive_errors >= MAX_CONSECUTIVE_ERRORS {
                                log::error!("Too many consecutive errors, shutting down monitoring thread");
                                return;
                            }
                        }
                    }

                    // Wait for next check with periodic shutdown check
                    let sleep_duration = Duration::from_millis(check_interval_ms);
                    let start_time = SystemTime::now();
                    
                    while SystemTime::now().duration_since(start_time).unwrap() < sleep_duration {
                        if shutdown_flag.load(Ordering::Relaxed) {
                            log::info!("Monitoring thread shutting down gracefully");
                            return;
                        }
                        // Check every 50ms for shutdown signal
                        thread::sleep(Duration::from_millis(50));
                    }
                }
                log::info!("Monitoring thread shut down gracefully");
            });
        }
    
        /// Stop background monitoring with timeout
        pub fn stop_monitoring(&self, timeout_ms: Option<u64>) -> bool {
            self.is_monitoring.store(false, Ordering::Relaxed);
            self.shutdown_flag.store(true, Ordering::Relaxed);
            
            // If timeout is specified, wait for thread to stop
            if let Some(timeout) = timeout_ms {
                let start_time = SystemTime::now();
                let timeout_duration = Duration::from_millis(timeout);
                
                while self.is_monitoring.load(Ordering::Relaxed) {
                    if SystemTime::now().duration_since(start_time).unwrap() > timeout_duration {
                        log::warn!("Monitoring thread did not stop within timeout period");
                        return false;
                    }
                    thread::sleep(Duration::from_millis(10));
                }
            }
            
            true
        }
        
        /// Force stop monitoring thread (use as last resort)
        pub fn force_stop_monitoring(&self) {
            self.is_monitoring.store(false, Ordering::Relaxed);
            self.shutdown_flag.store(true, Ordering::Relaxed);
            log::warn!("Force stopping monitoring thread");
        }
        
        /// Check if monitoring is active
        pub fn is_monitoring_active(&self) -> bool {
            self.is_monitoring.load(Ordering::Relaxed)
        }
        
        /// Check if shutdown flag is set
        pub fn is_shutdown_requested(&self) -> bool {
            self.shutdown_flag.load(Ordering::Relaxed)
        }
    
        /// Record allocation for monitoring
        fn record_allocation(&self, size: usize) {
            let current_used = self.total_used.load(Ordering::Relaxed);
            self.high_water_mark.fetch_max(current_used, Ordering::Relaxed);

            if let Ok(monitor) = self.pressure_monitor.write() {
                monitor.record_allocation(size);
            }
        }
    
        /// Perform lazy cleanup (free small amounts periodically)
        pub fn perform_lazy_cleanup(&self) {
            let current_time = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_secs() as usize;
            let last_cleanup = self.last_cleanup_time.load(Ordering::Relaxed);
            
            // Only cleanup if enough time has passed (5 seconds)
            if current_time - last_cleanup < 5 {
                return;
            }
    
            let mut chunk = self.current.lock().expect("Arena mutex poisoned");
            
            // Free a small amount (1KB) if possible
            const SMALL_FREE_AMOUNT: usize = 1024;
            if chunk.current_offset > SMALL_FREE_AMOUNT {
                chunk.current_offset -= SMALL_FREE_AMOUNT;
                self.total_used.fetch_sub(SMALL_FREE_AMOUNT, Ordering::Relaxed);
                
                self.last_cleanup_time.store(current_time, Ordering::Relaxed);
                
                let monitor = self.pressure_monitor.write().unwrap();
                monitor.record_cleanup_event(ArenaCleanupType::Lazy);
                
                log::debug!("Freed {} bytes in lazy cleanup", SMALL_FREE_AMOUNT);
            }
        }
    
        /// Get memory pressure monitoring statistics
        pub fn get_pressure_stats(&self) -> ArenaPressureStats {
            self.pressure_monitor.read().unwrap().get_stats()
        }

    /// Get usage statistics
    pub fn stats(&self) -> ArenaStats {
        let allocated = self.total_allocated.load(Ordering::Relaxed);
        let used = self.total_used.load(Ordering::Relaxed);
        
        ArenaStats {
            total_allocated: allocated,
            total_used: used,
            utilization: if allocated > 0 {
                used as f64 / allocated as f64
            } else {
                0.0
            },
        }
    }

    /// Simple memory pressure check hook with reduced frequency
    fn check_memory_pressure(&self) {
        // Only perform pressure checks 1/5 of the time to reduce overhead
        if rand::thread_rng().gen::<f64>() < 0.2 {
            if let Ok(monitor) = self.pressure_monitor.write() {
                let stats = self.stats();
                let usage_ratio = if stats.total_allocated > 0 {
                    stats.total_used as f64 / stats.total_allocated as f64
                } else { 0.0 };

                let level = MemoryPressureLevel::from_usage_ratio(usage_ratio);
                monitor.record_pressure_check(level);
            }
        }
    }
}

/// Memory pressure levels
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MemoryPressureLevel {
    Normal,
    Moderate,
    High,
    Critical,
}

impl MemoryPressureLevel {
    pub fn from_usage_ratio(usage_ratio: f64) -> Self {
        match usage_ratio {
            r if r < 0.7 => MemoryPressureLevel::Normal,
            r if r < 0.85 => MemoryPressureLevel::Moderate,
            r if r < 0.95 => MemoryPressureLevel::High,
            _ => MemoryPressureLevel::Critical,
        }
    }
}

/// Statistics for the arena allocator
#[derive(Debug, Clone)]
pub struct ArenaStats {
    pub total_allocated: usize,
    pub total_used: usize,
    pub utilization: f64,
}

/// Arena pressure monitor
#[derive(Debug)]
pub struct ArenaPressureMonitor {
    allocation_history: Mutex<Vec<(SystemTime, usize)>>,
    pressure_checks: Mutex<Vec<(SystemTime, MemoryPressureLevel)>>,
    cleanup_events: Mutex<Vec<(SystemTime, ArenaCleanupType)>>,
    total_allocations: AtomicUsize,
    total_bytes_allocated: AtomicUsize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ArenaCleanupType {
    Light,
    Aggressive,
    Lazy,
}

impl ArenaPressureMonitor {
    pub fn new() -> Self {
        Self {
            allocation_history: Mutex::new(Vec::new()),
            pressure_checks: Mutex::new(Vec::new()),
            cleanup_events: Mutex::new(Vec::new()),
            total_allocations: AtomicUsize::new(0),
            total_bytes_allocated: AtomicUsize::new(0),
        }
    }

    pub fn record_allocation(&self, size: usize) {
        let now = SystemTime::now();
        let mut history = self.allocation_history.lock().unwrap();
        history.push((now, size));
        
        // Keep only last 5 minutes of data
        while let Some(&(_, _)) = history.first() {
            if now.duration_since(history[0].0).unwrap_or(Duration::from_secs(301)).as_secs() > 300 {
                history.remove(0);
            } else {
                break;
            }
        }
        
        self.total_allocations.fetch_add(1, Ordering::Relaxed);
        self.total_bytes_allocated.fetch_add(size, Ordering::Relaxed);
    }

    pub fn record_pressure_check(&self, pressure: MemoryPressureLevel) {
        let now = SystemTime::now();
        let mut checks = self.pressure_checks.lock().unwrap();
        checks.push((now, pressure));
        
        // Keep only last 60 seconds of data
        while let Some(&(_, _)) = checks.first() {
            if now.duration_since(checks[0].0).unwrap_or(Duration::from_secs(61)).as_secs() > 60 {
                checks.remove(0);
            } else {
                break;
            }
        }
    }

    pub fn record_cleanup_event(&self, cleanup_type: ArenaCleanupType) {
        let now = SystemTime::now();
        let mut events = self.cleanup_events.lock().unwrap();
        events.push((now, cleanup_type));
        
        // Keep only last 60 seconds of data
        while let Some(&(_, _)) = events.first() {
            if now.duration_since(events[0].0).unwrap_or(Duration::from_secs(61)).as_secs() > 60 {
                events.remove(0);
            } else {
                break;
            }
        }
    }

    pub fn get_stats(&self) -> ArenaPressureStats {
        let allocation_history = self.allocation_history.lock().unwrap();
        let pressure_checks = self.pressure_checks.lock().unwrap();
        let cleanup_events = self.cleanup_events.lock().unwrap();
        
        let total_allocations = self.total_allocations.load(Ordering::Relaxed);
        let total_bytes_allocated = self.total_bytes_allocated.load(Ordering::Relaxed);
        
        let recent_allocations: Vec<&(SystemTime, usize)> = allocation_history
            .iter()
            .filter(|&&(time, _)| {
                let now = SystemTime::now();
                now.duration_since(time).unwrap_or(Duration::from_secs(0)).as_secs() <= 60
            })
            .collect();
        
        let recent_pressure_checks: Vec<&(SystemTime, MemoryPressureLevel)> = pressure_checks
            .iter()
            .filter(|&&(time, _)| {
                let now = SystemTime::now();
                now.duration_since(time).unwrap_or(Duration::from_secs(0)).as_secs() <= 60
            })
            .collect();
        
        let recent_cleanup_events: Vec<&(SystemTime, ArenaCleanupType)> = cleanup_events
            .iter()
            .filter(|&&(time, _)| {
                let now = SystemTime::now();
                now.duration_since(time).unwrap_or(Duration::from_secs(0)).as_secs() <= 60
            })
            .collect();
        
        let avg_allocation_size = if !recent_allocations.is_empty() {
            recent_allocations.iter().map(|&&(_, size)| size).sum::<usize>() as f64 / recent_allocations.len() as f64
        } else {
            0.0
        };
        
        let pressure_distribution = self.calculate_pressure_distribution(recent_pressure_checks);
        let cleanup_distribution = self.calculate_cleanup_distribution(recent_cleanup_events);
        
        ArenaPressureStats {
            total_allocations,
            total_bytes_allocated,
            recent_allocations_count: recent_allocations.len(),
            avg_allocation_size,
            pressure_distribution,
            cleanup_distribution,
            last_allocation_time: allocation_history.last().map(|&(t, _)| t).unwrap_or(SystemTime::now()),
            last_pressure_check_time: pressure_checks.last().map(|&(t, _)| t).unwrap_or(SystemTime::now()),
            last_cleanup_time: cleanup_events.last().map(|&(t, _)| t).unwrap_or(SystemTime::now()),
        }
    }

    fn calculate_pressure_distribution(&self, checks: Vec<&(SystemTime, MemoryPressureLevel)>) -> PressureDistribution {
        let total = checks.len();
        if total == 0 {
            return PressureDistribution::default();
        }
        
    let normal = checks.iter().filter(|&&(_, p)| *p == MemoryPressureLevel::Normal).count();
    let moderate = checks.iter().filter(|&&(_, p)| *p == MemoryPressureLevel::Moderate).count();
    let high = checks.iter().filter(|&&(_, p)| *p == MemoryPressureLevel::High).count();
    let critical = checks.iter().filter(|&&(_, p)| *p == MemoryPressureLevel::Critical).count();
        
        PressureDistribution {
            normal: normal as f64 / total as f64,
            moderate: moderate as f64 / total as f64,
            high: high as f64 / total as f64,
            critical: critical as f64 / total as f64,
        }
    }

    fn calculate_cleanup_distribution(&self, events: Vec<&(SystemTime, ArenaCleanupType)>) -> CleanupDistribution {
        let total = events.len();
        if total == 0 {
            return CleanupDistribution::default();
        }
        
    let light = events.iter().filter(|&&(_, t)| *t == ArenaCleanupType::Light).count();
    let aggressive = events.iter().filter(|&&(_, t)| *t == ArenaCleanupType::Aggressive).count();
    let lazy = events.iter().filter(|&&(_, t)| *t == ArenaCleanupType::Lazy).count();
        
        CleanupDistribution {
            light: light as f64 / total as f64,
            aggressive: aggressive as f64 / total as f64,
            lazy: lazy as f64 / total as f64,
        }
    }
}

#[derive(Debug, Clone)]
pub struct ArenaPressureStats {
    pub total_allocations: usize,
    pub total_bytes_allocated: usize,
    pub recent_allocations_count: usize,
    pub avg_allocation_size: f64,
    pub pressure_distribution: PressureDistribution,
    pub cleanup_distribution: CleanupDistribution,
    pub last_allocation_time: SystemTime,
    pub last_pressure_check_time: SystemTime,
    pub last_cleanup_time: SystemTime,
}

#[derive(Debug, Clone, Default)]
pub struct PressureDistribution {
    pub normal: f64,
    pub moderate: f64,
    pub high: f64,
    pub critical: f64,
}

#[derive(Debug, Clone, Default)]
pub struct CleanupDistribution {
    pub light: f64,
    pub aggressive: f64,
    pub lazy: f64,
}

/// A thread-safe arena allocator pool with monitoring
pub struct ArenaPool {
    arenas: Mutex<Vec<Arc<BumpArena>>>,
    default_chunk_size: usize,
    max_arenas: usize,
    pressure_monitor: Arc<RwLock<ArenaPoolPressureMonitor>>,
    is_monitoring: AtomicBool,
    shutdown_flag: Arc<AtomicBool>,
}

impl ArenaPool {
    /// Create a new arena pool
    pub fn new(default_chunk_size: usize, max_arenas: usize) -> Self {
        let pressure_monitor = Arc::new(RwLock::new(ArenaPoolPressureMonitor::new()));
        
        Self {
            arenas: Mutex::new(Vec::new()),
            default_chunk_size,
            max_arenas,
            pressure_monitor,
            is_monitoring: AtomicBool::new(false),
            shutdown_flag: Arc::new(AtomicBool::new(false)),
        }
    }

    /// Start background monitoring
    pub fn start_monitoring(&self, check_interval_ms: u64) {
        if self.is_monitoring.load(Ordering::Relaxed) {
            return;
        }

        self.is_monitoring.store(true, Ordering::Relaxed);
        self.shutdown_flag.store(false, Ordering::Relaxed);
        
        let pressure_monitor = Arc::clone(&self.pressure_monitor);
        // Clone only the data we need into the thread: a handle to the pool (Arc)
        let arenas_handle = Arc::new(Mutex::new(self.arenas.lock().unwrap().clone()));
        let shutdown_flag = Arc::clone(&self.shutdown_flag);
        thread::spawn(move || {
            let mut consecutive_errors = 0;
            const MAX_CONSECUTIVE_ERRORS: u32 = 5;
            
            while !shutdown_flag.load(Ordering::Relaxed) {
                // Check pool pressure
                match std::panic::catch_unwind(|| {
                    // Reconstruct a temporary ArenaPoolStats-like struct by peeking at arenas_handle
                    let arenas_guard = arenas_handle.lock().unwrap();
                    let mut total_allocated = 0usize;
                    let mut total_used = 0usize;
                    for a in arenas_guard.iter() {
                        let s = a.stats();
                        total_allocated += s.total_allocated;
                        total_used += s.total_used;
                    }
                    let usage_ratio = if total_allocated > 0 { total_used as f64 / total_allocated as f64 } else { 0.0 };

                    let pressure_level = MemoryPressureLevel::from_usage_ratio(usage_ratio);

                    // Build a basic ArenaPoolStats snapshot to pass to the monitor
                    let stats_snapshot = ArenaPoolStats {
                        arena_count: arenas_guard.len(),
                        total_allocated,
                        total_used,
                        utilization: if total_allocated > 0 { total_used as f64 / total_allocated as f64 } else { 0.0 },
                    };

                    // Update monitoring stats
                    let monitor = pressure_monitor.write().unwrap();
                    monitor.record_pressure_check(pressure_level, stats_snapshot);
                    
                    // Perform cleanup if needed (no direct calls into pool to avoid borrowing self)
                    match pressure_level {
                        MemoryPressureLevel::High | MemoryPressureLevel::Critical => {
                            log::warn!("High memory pressure in arena pool - performing cleanup (deferred)");
                            // In this simplified background thread we avoid mutating the real pool; production code would signal the pool to cleanup
                        },
                        MemoryPressureLevel::Moderate => {
                            log::info!("Moderate memory pressure in arena pool");
                        },
                        _ => {}
                    }
                }) {
                    Ok(_) => {
                        consecutive_errors = 0; // Reset error counter on success
                    },
                    Err(e) => {
                        log::error!("Panic occurred in arena pool monitoring thread: {:?}", e);
                        consecutive_errors += 1;
                        if consecutive_errors >= MAX_CONSECUTIVE_ERRORS {
                            log::error!("Too many consecutive errors, shutting down arena pool monitoring thread");
                            return;
                        }
                    }
                }

                // Wait for next check with periodic shutdown check
                let sleep_duration = Duration::from_millis(check_interval_ms);
                let start_time = SystemTime::now();
                
                while SystemTime::now().duration_since(start_time).unwrap() < sleep_duration {
                    if shutdown_flag.load(Ordering::Relaxed) {
                        log::info!("Arena pool monitoring thread shutting down gracefully");
                        return;
                    }
                    // Check every 50ms for shutdown signal
                    thread::sleep(Duration::from_millis(50));
                }
            }
            log::info!("Arena pool monitoring thread shut down gracefully");
        });
    }

    /// Stop background monitoring with timeout
    pub fn stop_monitoring(&self, timeout_ms: Option<u64>) -> bool {
        self.is_monitoring.store(false, Ordering::Relaxed);
        self.shutdown_flag.store(true, Ordering::Relaxed);
        
        // If timeout is specified, wait for thread to stop
        if let Some(timeout) = timeout_ms {
            let start_time = SystemTime::now();
            let timeout_duration = Duration::from_millis(timeout);
            
            while self.is_monitoring.load(Ordering::Relaxed) {
                if SystemTime::now().duration_since(start_time).unwrap() > timeout_duration {
                    log::warn!("Monitoring thread did not stop within timeout period");
                    return false;
                }
                thread::sleep(Duration::from_millis(10));
            }
        }
        
        true
    }
    
    /// Force stop monitoring thread (use as last resort)
    pub fn force_stop_monitoring(&self) {
        self.is_monitoring.store(false, Ordering::Relaxed);
        self.shutdown_flag.store(true, Ordering::Relaxed);
        log::warn!("Force stopping monitoring thread");
    }
    
    /// Check if monitoring is active
    pub fn is_monitoring_active(&self) -> bool {
        self.is_monitoring.load(Ordering::Relaxed)
    }
    
    /// Check if shutdown flag is set
    pub fn is_shutdown_requested(&self) -> bool {
        self.shutdown_flag.load(Ordering::Relaxed)
    }

    /// Get an arena from the pool (create if necessary)
    pub fn get_arena(&self) -> Arc<BumpArena> {
        let mut arenas = self.arenas.lock().expect("ArenaPool arenas mutex poisoned");
        
        // Try to reuse an existing arena
        if let Some(arena) = arenas.pop() {
            self.record_arena_event(ArenaPoolEvent::Reuse);
            return arena;
        }
        
        // Create a new arena if we haven't reached the limit
        if arenas.len() < self.max_arenas {
            let arena = Arc::new(BumpArena::new(self.default_chunk_size));
            self.record_arena_event(ArenaPoolEvent::Creation);
            arena
        } else {
            // Reuse the least recently used arena
            let arena = arenas.remove(0);
            self.record_arena_event(ArenaPoolEvent::Reuse);
            arena
        }
    }

    /// Return an arena to the pool
    pub fn return_arena(&self, arena: Arc<BumpArena>) {
        let mut arenas = self.arenas.lock().expect("ArenaPool arenas mutex poisoned");
        if arenas.len() < self.max_arenas {
            arenas.push(arena);
            self.record_arena_event(ArenaPoolEvent::Return);
        } else {
            // If pool is full, perform cleanup on the returned arena
            log::info!("Arena pool full - cleaning up returned arena");
            // BumpArena exposes a lazy cleanup method; call it here to reduce memory usage
            arena.perform_lazy_cleanup();
        }
    }

    /// Get combined statistics for all arenas
    pub fn stats(&self) -> ArenaPoolStats {
        let arenas = self.arenas.lock().expect("ArenaPool arenas mutex poisoned");
        let mut total_allocated = 0;
        let mut total_used = 0;
        let arena_count = arenas.len();

        for arena in arenas.iter() {
            let stats = arena.stats();
            total_allocated += stats.total_allocated;
            total_used += stats.total_used;
        }

        ArenaPoolStats {
            arena_count,
            total_allocated,
            total_used,
            utilization: if total_allocated > 0 {
                total_used as f64 / total_allocated as f64
            } else {
                0.0
            },
        }
    }

    #[allow(dead_code)]
    /// Perform pool-level cleanup
    fn perform_pool_cleanup(&self) {
        let mut arenas = self.arenas.lock().expect("ArenaPool arenas mutex poisoned");

        // Sort arenas by utilization (most used first)
        arenas.sort_by(|a, b| {
            let stats_a = a.stats();
            let stats_b = b.stats();
            let util_a = stats_a.utilization;
            let util_b = stats_b.utilization;

            util_b.partial_cmp(&util_a).unwrap_or(std::cmp::Ordering::Equal)
        });

        // Determine arenas to remove, but don't call methods on them while holding the lock
        let target_count = (arenas.len() as f64 * 0.7) as usize; // Keep 70% of arenas
        let mut removed_arenas: Vec<Arc<BumpArena>> = Vec::new();
        while arenas.len() > target_count && arenas.len() > 2 { // Keep at least 2 arenas
            if let Some(arena) = arenas.pop() {
                log::info!("Removed least used arena from pool");
                removed_arenas.push(arena);
            }
        }
        drop(arenas);

        // Perform cleanup on removed arenas outside of the lock
        for arena in removed_arenas {
            arena.perform_lazy_cleanup();
        }
        
        let monitor = self.pressure_monitor.write().unwrap();
        monitor.record_cleanup_event(ArenaPoolCleanupType::PoolLevel);
    }

    /// Record arena event
    fn record_arena_event(&self, event: ArenaPoolEvent) {
        let monitor = self.pressure_monitor.write().unwrap();
        monitor.record_arena_event(event);
    }

    /// Get pool pressure monitoring statistics
    pub fn get_pressure_stats(&self) -> ArenaPoolPressureStats {
        self.pressure_monitor.read().unwrap().get_stats()
    }
}

/// Arena pool pressure monitor
#[derive(Debug)]
pub struct ArenaPoolPressureMonitor {
    pressure_checks: Mutex<Vec<(SystemTime, MemoryPressureLevel, ArenaPoolStats)>>,
    arena_events: Mutex<Vec<(SystemTime, ArenaPoolEvent)>>,
    cleanup_events: Mutex<Vec<(SystemTime, ArenaPoolCleanupType)>>,
    total_arenas_created: AtomicUsize,
    total_arenas_reused: AtomicUsize,
    total_arenas_returned: AtomicUsize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ArenaPoolEvent {
    Creation,
    Acquisition,
    Reuse,
    Return,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ArenaPoolCleanupType {
    PoolLevel,
    Individual,
}

impl ArenaPoolPressureMonitor {
    pub fn new() -> Self {
        Self {
            pressure_checks: Mutex::new(Vec::new()),
            arena_events: Mutex::new(Vec::new()),
            cleanup_events: Mutex::new(Vec::new()),
            total_arenas_created: AtomicUsize::new(0),
            total_arenas_reused: AtomicUsize::new(0),
            total_arenas_returned: AtomicUsize::new(0),
        }
    }

    pub fn record_pressure_check(&self, pressure: MemoryPressureLevel, stats: ArenaPoolStats) {
        let now = SystemTime::now();
        let mut checks = self.pressure_checks.lock().unwrap();
        checks.push((now, pressure, stats));
        
        // Keep only last 5 minutes of data
        while let Some(&(_, _, _)) = checks.first() {
            if now.duration_since(checks[0].0).unwrap_or(Duration::from_secs(301)).as_secs() > 300 {
                checks.remove(0);
            } else {
                break;
            }
        }
    }

    pub fn record_arena_event(&self, event: ArenaPoolEvent) {
        let now = SystemTime::now();
        let mut events = self.arena_events.lock().unwrap();
        events.push((now, event));
        
        // Keep only last 5 minutes of data
        while let Some(&(_, _)) = events.first() {
            if now.duration_since(events[0].0).unwrap_or(Duration::from_secs(301)).as_secs() > 300 {
                events.remove(0);
            } else {
                break;
            }
        }
        
        match event {
            ArenaPoolEvent::Creation => {
                self.total_arenas_created.fetch_add(1, Ordering::Relaxed);
            },
            ArenaPoolEvent::Reuse => {
                self.total_arenas_reused.fetch_add(1, Ordering::Relaxed);
            },
            ArenaPoolEvent::Return => {
                self.total_arenas_returned.fetch_add(1, Ordering::Relaxed);
            },
            _ => {}
        }
    }

    pub fn record_cleanup_event(&self, cleanup_type: ArenaPoolCleanupType) {
        let now = SystemTime::now();
        let mut events = self.cleanup_events.lock().unwrap();
        events.push((now, cleanup_type));
        
        // Keep only last 5 minutes of data
        while let Some(&(_, _)) = events.first() {
            if now.duration_since(events[0].0).unwrap_or(Duration::from_secs(301)).as_secs() > 300 {
                events.remove(0);
            } else {
                break;
            }
        }
    }

    pub fn get_stats(&self) -> ArenaPoolPressureStats {
        let pressure_checks = self.pressure_checks.lock().unwrap();
        let arena_events = self.arena_events.lock().unwrap();
        let cleanup_events = self.cleanup_events.lock().unwrap();
        
        let total_arenas_created = self.total_arenas_created.load(Ordering::Relaxed);
        let total_arenas_reused = self.total_arenas_reused.load(Ordering::Relaxed);
        let total_arenas_returned = self.total_arenas_returned.load(Ordering::Relaxed);
        
        let recent_events: Vec<&(SystemTime, ArenaPoolEvent)> = arena_events
            .iter()
            .filter(|&&(time, _)| {
                let now = SystemTime::now();
                now.duration_since(time).unwrap_or(Duration::from_secs(0)).as_secs() <= 60
            })
            .collect();
        
        let recent_cleanup_events: Vec<&(SystemTime, ArenaPoolCleanupType)> = cleanup_events
            .iter()
            .filter(|&&(time, _)| {
                let now = SystemTime::now();
                now.duration_since(time).unwrap_or(Duration::from_secs(0)).as_secs() <= 60
            })
            .collect();
        
    let event_distribution = self.calculate_event_distribution(&recent_events);
    let cleanup_distribution = self.calculate_cleanup_distribution(&recent_cleanup_events);
        
        let current_stats = if let Some((_, _, stats)) = pressure_checks.last() {
            Some(stats.clone())
        } else {
            None
        };
        
        ArenaPoolPressureStats {
            total_arenas_created,
            total_arenas_reused,
            total_arenas_returned,
            recent_events_count: recent_events.len(),
            event_distribution,
            cleanup_distribution,
            current_pool_stats: current_stats,
            last_pressure_check_time: pressure_checks.last().map(|&(t, _, _)| t).unwrap_or(SystemTime::now()),
            last_arena_event_time: arena_events.last().map(|&(t, _)| t).unwrap_or(SystemTime::now()),
            last_cleanup_time: cleanup_events.last().map(|&(t, _)| t).unwrap_or(SystemTime::now()),
        }
    }

    fn calculate_event_distribution(&self, events: &Vec<&(SystemTime, ArenaPoolEvent)>) -> ArenaPoolEventDistribution {
        let total = events.len();
        if total == 0 {
            return ArenaPoolEventDistribution::default();
        }
        
    let creation = events.iter().filter(|&&(_, e)| *e == ArenaPoolEvent::Creation).count();
    let acquisition = events.iter().filter(|&&(_, e)| *e == ArenaPoolEvent::Acquisition).count();
    let reuse = events.iter().filter(|&&(_, e)| *e == ArenaPoolEvent::Reuse).count();
    let returned = events.iter().filter(|&&(_, e)| *e == ArenaPoolEvent::Return).count();
        
        ArenaPoolEventDistribution {
            creation: creation as f64 / total as f64,
            acquisition: acquisition as f64 / total as f64,
            reuse: reuse as f64 / total as f64,
            returned: returned as f64 / total as f64,
        }
    }

    fn calculate_cleanup_distribution(&self, events: &Vec<&(SystemTime, ArenaPoolCleanupType)>) -> ArenaPoolCleanupDistribution {
        let total = events.len();
        if total == 0 {
            return ArenaPoolCleanupDistribution::default();
        }
        
    let pool_level = events.iter().filter(|&&(_, t)| *t == ArenaPoolCleanupType::PoolLevel).count();
    let individual = events.iter().filter(|&&(_, t)| *t == ArenaPoolCleanupType::Individual).count();
        
        ArenaPoolCleanupDistribution {
            pool_level: pool_level as f64 / total as f64,
            individual: individual as f64 / total as f64,
        }
    }
}

#[derive(Debug, Clone)]
pub struct ArenaPoolPressureStats {
    pub total_arenas_created: usize,
    pub total_arenas_reused: usize,
    pub total_arenas_returned: usize,
    pub recent_events_count: usize,
    pub event_distribution: ArenaPoolEventDistribution,
    pub cleanup_distribution: ArenaPoolCleanupDistribution,
    pub current_pool_stats: Option<ArenaPoolStats>,
    pub last_pressure_check_time: SystemTime,
    pub last_arena_event_time: SystemTime,
    pub last_cleanup_time: SystemTime,
}

#[derive(Debug, Clone, Default)]
pub struct ArenaPoolEventDistribution {
    pub creation: f64,
    pub acquisition: f64,
    pub reuse: f64,
    pub returned: f64,
}

#[derive(Debug, Clone, Default)]
pub struct ArenaPoolCleanupDistribution {
    pub pool_level: f64,
    pub individual: f64,
}

/// Statistics for the arena pool
#[derive(Debug, Clone)]
pub struct ArenaPoolStats {
    pub arena_count: usize,
    pub total_allocated: usize,
    pub total_used: usize,
    pub utilization: f64,
}

/// A scoped arena that automatically returns to pool when dropped
pub struct ScopedArena {
    arena: Arc<BumpArena>,
    pool: Arc<ArenaPool>,
}

impl ScopedArena {
    pub fn new(pool: Arc<ArenaPool>) -> Self {
        let arena = pool.get_arena();
        Self { arena, pool }
    }

    pub fn alloc(&self, size: usize, align: usize) -> *mut u8 {
        self.arena.alloc(size, align)
    }

    pub fn stats(&self) -> ArenaStats {
        self.arena.stats()
    }

    /// Get the inner bump arena (for advanced use cases)
    pub fn get_inner_arena(&self) -> Arc<BumpArena> {
        Arc::clone(&self.arena)
    }
}

impl Drop for ScopedArena {
    fn drop(&mut self) {
        self.pool.return_arena(Arc::clone(&self.arena));
    }
}

/// Safe memory region that can be used for arena allocations
#[derive(Debug)]
struct SafeMemoryRegion<T> {
    ptr: NonNull<T>,
    len: usize,
    capacity: usize,
}

#[allow(dead_code)]
impl<T> SafeMemoryRegion<T> {
    fn new(ptr: *mut T, capacity: usize) -> Self {
        Self {
            ptr: NonNull::new(ptr).expect("Null pointer passed to SafeMemoryRegion"),
            len: 0,
            capacity,
        }
    }

    fn is_valid_index(&self, index: usize) -> bool {
        index < self.len
    }

    fn get(&self, index: usize) -> Option<&T> {
        if self.is_valid_index(index) {
            unsafe { Some(&*self.ptr.as_ptr().add(index)) }
        } else {
            None
        }
    }

    fn get_mut(&mut self, index: usize) -> Option<&mut T> {
        if self.is_valid_index(index) {
            unsafe { Some(&mut *self.ptr.as_ptr().add(index)) }
        } else {
            None
        }
    }

    fn push(&mut self, value: T) -> Result<(), T> {
        if self.len < self.capacity {
            unsafe {
                self.ptr.as_ptr().add(self.len).write(value);
                self.len += 1;
                Ok(())
            }
        } else {
            Err(value)
        }
    }

    fn as_slice(&self) -> &[T] {
        if self.len == 0 {
            &[]
        } else {
            unsafe { slice::from_raw_parts(self.ptr.as_ptr(), self.len) }
        }
    }

    fn as_mut_slice(&mut self) -> &mut [T] {
        if self.len == 0 {
            &mut []
        } else {
            unsafe { slice::from_raw_parts_mut(self.ptr.as_ptr(), self.len) }
        }
    }

    fn clear(&mut self) {
        unsafe {
            for i in 0..self.len {
                self.ptr.as_ptr().add(i).drop_in_place();
            }
        }
        self.len = 0;
    }
}

/// A vector that allocates from an arena with safe operations
pub struct ArenaVec<T> {
    memory: Option<SafeMemoryRegion<T>>,
    arena: Arc<BumpArena>,
}

impl<T> ArenaVec<T> {
    pub fn new(arena: Arc<BumpArena>) -> Self {
        Self {
            memory: None,
            arena,
        }
    }

    pub fn with_capacity(arena: Arc<BumpArena>, capacity: usize) -> Self {
        if capacity == 0 {
            return Self::new(arena);
        }

        let size = capacity * std::mem::size_of::<T>();
        let align = std::mem::align_of::<T>();
        let ptr = arena.alloc(size, align) as *mut T;

        Self {
            memory: Some(SafeMemoryRegion::new(ptr, capacity)),
            arena,
        }
    }

    pub fn push(&mut self, value: T) {
        // Check if we need to grow first
        let needs_grow = self.memory.as_ref().map(|m| m.len >= m.capacity).unwrap_or(true);
        
        if needs_grow {
            self.grow_and_repush();
        }
        
        // Now we should have space
        if let Some(ref mut memory) = self.memory {
            if memory.push(value).is_err() {
                panic!("Should have space after growing or initial allocation");
            }
        } else {
            panic!("Memory should be initialized after grow_and_repush");
        }
    }

    fn grow_and_repush(&mut self) {
        let current_capacity = self.memory.as_ref().map(|m| m.capacity).unwrap_or(0);
        let new_capacity = if current_capacity == 0 { 4 } else { current_capacity * 2 };
        
        let new_size = new_capacity * std::mem::size_of::<T>();
        let align = std::mem::align_of::<T>();
        let new_ptr = self.arena.alloc(new_size, align) as *mut T;
        
        let mut new_memory = SafeMemoryRegion::new(new_ptr, new_capacity);
        
        // Copy existing elements
        if let Some(ref mut old_memory) = self.memory {
            for i in 0..old_memory.len {
                unsafe {
                    let val = std::ptr::read(old_memory.ptr.as_ptr().add(i));
                    if new_memory.push(val).is_err() {
                        panic!("New memory should have space for existing elements");
                    }
                }
            }
            old_memory.len = 0; // Prevent double drop
        }
        
        self.memory = Some(new_memory);
    }

    pub fn len(&self) -> usize {
        self.memory.as_ref().map(|m| m.len).unwrap_or(0)
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    pub fn as_slice(&self) -> &[T] {
        self.memory.as_ref().map(|m| m.as_slice()).unwrap_or(&[])
    }

    pub fn as_mut_slice(&mut self) -> &mut [T] {
        self.memory.as_mut().map(|m| m.as_mut_slice()).unwrap_or(&mut [])
    }
}

impl<T> Drop for ArenaVec<T> {
    fn drop(&mut self) {
        if let Some(ref mut memory) = self.memory {
            memory.clear();
        }
    }
}

// Global arena pool for the application
lazy_static::lazy_static! {
    static ref GLOBAL_ARENA_POOL: Arc<ArenaPool> = Arc::new(ArenaPool::new(
        64 * 1024, // 64KB default chunk size
        16         // Max 16 arenas
    ));
}

/// Get the global arena pool
pub fn get_global_arena_pool() -> Arc<ArenaPool> {
    Arc::clone(&GLOBAL_ARENA_POOL)
}

/// Create a scoped arena from the global pool
pub fn create_scoped_arena() -> ScopedArena {
    ScopedArena::new(get_global_arena_pool())
}

/// JNI function to get arena pool statistics
#[cfg(feature = "jni")]
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_getArenaPoolStats(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
) -> jni::sys::jstring {
    let stats = GLOBAL_ARENA_POOL.stats();
    let stats_json = serde_json::json!({
        "arenaCount": stats.arena_count,
        "totalAllocated": stats.total_allocated,
        "totalUsed": stats.total_used,
        "utilization": stats.utilization,
    });

    match env.new_string(&serde_json::to_string(&stats_json).unwrap_or_default()) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_bump_arena_allocation() {
        let arena = BumpArena::new(1024);
        
        let ptr1 = arena.alloc(10, 8);
        assert!(!ptr1.is_null());
        
        let ptr2 = arena.alloc(20, 8);
        assert!(!ptr2.is_null());
        assert_ne!(ptr1, ptr2);
        
        let stats = arena.stats();
        assert!(stats.total_used >= 30);
        assert!(stats.utilization > 0.0);
    }

    #[test]
    fn test_arena_vec() {
        let arena = Arc::new(BumpArena::new(1024));
        let mut vec = ArenaVec::with_capacity(arena, 4);
        
        vec.push(1);
        vec.push(2);
        vec.push(3);
        
        assert_eq!(vec.len(), 3);
        assert_eq!(vec.as_slice(), &[1, 2, 3]);
    }

    #[test]
    fn test_arena_pool() {
        let pool = Arc::new(ArenaPool::new(1024, 2));
        
        let arena1 = pool.get_arena();
        let arena2 = pool.get_arena();
        
        // Third arena should reuse one of the first two
        let arena3 = pool.get_arena();
        
        pool.return_arena(arena1);
        pool.return_arena(arena2);
        pool.return_arena(arena3);
        
        let stats = pool.stats();
        assert_eq!(stats.arena_count, 2);
    }
}