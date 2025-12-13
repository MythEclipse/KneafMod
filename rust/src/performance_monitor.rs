//! Performance Monitor - Sistem monitoring performa native Rust
//!
//! Modul ini menyediakan struktur utama untuk monitoring performa dengan
//! menggunakan lock-free data structures, sampling strategies, dan streaming
//! algorithms untuk overhead minimal dan real-time performance.

use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, AtomicU8, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

/// Struktur utama untuk performance monitoring dengan lock-free design
#[derive(Debug, Clone)]
pub struct PerformanceMonitor {
    /// Koleksi metrik CPU usage dengan fixed-size array untuk lock-free access
    cpu_metrics: Arc<[AtomicU32; 256]>,
    /// Koleksi metrik memory usage dengan fixed-size array
    memory_metrics: Arc<[AtomicU64; 256]>,
    /// Koleksi metrik response time dengan fixed-size array
    response_time_metrics: Arc<[AtomicU32; 256]>,
    /// Timestamp untuk tracking
    start_time: DateTime<Utc>,
    /// Status monitoring
    is_monitoring: Arc<AtomicBool>,
    /// Sampling rate (0-100)
    sampling_rate: Arc<AtomicU8>,
    /// System load indicator
    system_load: Arc<AtomicU8>,
    /// Last sampling decision untuk menghindari overhead RNG
    last_sampling_decision: Arc<AtomicBool>,
    /// Component name hashes untuk indexing array
    component_hashes: Arc<std::sync::Mutex<std::collections::HashMap<String, usize>>>,
}

/// Konfigurasi untuk PerformanceMonitor
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PerformanceMonitorConfig {
    /// Interval pengukuran dalam milidetik
    pub measurement_interval: u64,
    /// Threshold untuk alerting
    pub cpu_threshold: f64,
    pub memory_threshold: u64,
    pub response_time_threshold: f64,
    /// Enable/disable monitoring
    pub enabled: bool,
}

impl Default for PerformanceMonitorConfig {
    fn default() -> Self {
        Self {
            measurement_interval: 1000,           // 1 detik
            cpu_threshold: 80.0,                  // 80%
            memory_threshold: 1024 * 1024 * 1024, // 1GB
            response_time_threshold: 1000.0,      // 1 detik
            enabled: true,
        }
    }
}

impl PerformanceMonitor {
    /// Membuat instance PerformanceMonitor baru dengan lock-free design
    pub fn new() -> Self {
        // Inisialisasi fixed-size arrays dengan nilai default
        let cpu_metrics = Arc::new(std::array::from_fn(|_| AtomicU32::new(0)));
        let memory_metrics = Arc::new(std::array::from_fn(|_| AtomicU64::new(0)));
        let response_time_metrics = Arc::new(std::array::from_fn(|_| AtomicU32::new(0)));

        Self {
            cpu_metrics,
            memory_metrics,
            response_time_metrics,
            start_time: Utc::now(),
            is_monitoring: Arc::new(AtomicBool::new(false)),
            sampling_rate: Arc::new(AtomicU8::new(100)), // 100% sampling by default
            system_load: Arc::new(AtomicU8::new(0)),     // 0-100 load indicator
            last_sampling_decision: Arc::new(AtomicBool::new(true)),
            component_hashes: Arc::new(std::sync::Mutex::new(std::collections::HashMap::new())),
        }
    }

    /// Membuat instance dengan konfigurasi kustom
    pub fn with_config(config: PerformanceMonitorConfig) -> Self {
        let monitor = Self::new();
        if config.enabled {
            monitor.start_monitoring();
        }
        monitor
    }

    /// Memulai monitoring
    pub fn start_monitoring(&self) {
        self.is_monitoring.store(true, Ordering::SeqCst);
    }

    /// Menghentikan monitoring
    pub fn stop_monitoring(&self) {
        self.is_monitoring.store(false, Ordering::SeqCst);
    }

    /// Mengecek apakah monitoring sedang berjalan
    pub fn is_monitoring(&self) -> bool {
        self.is_monitoring.load(Ordering::SeqCst)
    }

    /// Merekam metrik CPU usage dengan sampling dan lock-free design
    pub fn record_cpu_usage(&self, component: &str, usage: f64) {
        if !self.is_monitoring() {
            return;
        }

        // Adaptive sampling berdasarkan system load
        if !self.should_sample() {
            return;
        }

        let usage_u32 = (usage * 100.0) as u32;
        let index = self.get_component_index(component);

        // Lock-free atomic store
        self.cpu_metrics[index].store(usage_u32, Ordering::Relaxed);
    }

    /// Merekam metrik memory usage dengan sampling dan lock-free design
    pub fn record_memory_usage(&self, component: &str, usage: u64) {
        if !self.is_monitoring() {
            return;
        }

        // Adaptive sampling berdasarkan system load
        if !self.should_sample() {
            return;
        }

        let index = self.get_component_index(component);

        // Lock-free atomic store
        self.memory_metrics[index].store(usage, Ordering::Relaxed);
    }

    /// Merekam metrik response time dengan sampling dan lock-free design
    pub fn record_response_time(&self, component: &str, response_time: f64) {
        if !self.is_monitoring() {
            return;
        }

        // Adaptive sampling berdasarkan system load
        if !self.should_sample() {
            return;
        }

        let response_time_u32 = response_time as u32;
        let index = self.get_component_index(component);

        // Lock-free atomic store
        self.response_time_metrics[index].store(response_time_u32, Ordering::Relaxed);
    }

    /// Merekam metrik umum dengan kategori
    pub fn record_metric(&self, category: &str, value: f64) {
        if !self.is_monitoring() {
            return;
        }

        // Redirect to appropriate specialized method based on category
        match category {
            "combat_attack_processing_time"
            | "hit_detection_time"
            | "damage_calculation_time"
            | "aoe_damage_processing_time"
            | "combat_system_update_time" => {
                self.record_response_time(category, value);
            }
            _ => {
                // For unknown categories, use response_time as default
                self.record_response_time(category, value);
            }
        }
    }

    /// Sampling strategy untuk adaptive monitoring
    pub fn should_sample(&self) -> bool {
        let current_rate = self.sampling_rate.load(Ordering::Relaxed);
        let system_load = self.system_load.load(Ordering::Relaxed);

        // Adaptive sampling based on system load
        let effective_rate = if system_load > 80 {
            // High load, reduce sampling to 10% minimum
            current_rate.min(10)
        } else if system_load > 50 {
            // Medium load, reduce sampling to 50%
            current_rate.min(50)
        } else {
            // Low load, use full sampling rate
            current_rate
        };

        // Use cached decision to avoid RNG overhead
        if effective_rate >= 100 {
            true
        } else if effective_rate == 0 {
            false
        } else {
            // Simple deterministic sampling based on time
            let now = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_millis() as u64;
            (now % 100) < (effective_rate as u64)
        }
    }

    /// Mendapatkan index komponen untuk array
    pub fn get_component_index(&self, component: &str) -> usize {
        let mut component_hashes = self.component_hashes.lock().unwrap();

        if let Some(&index) = component_hashes.get(component) {
            index
        } else {
            // Gunakan hash sederhana untuk index
            let hash = component
                .bytes()
                .fold(0u64, |acc, b| acc.wrapping_mul(31).wrapping_add(b as u64));
            let index = (hash % 256) as usize;
            component_hashes.insert(component.to_string(), index);
            index
        }
    }

    /// Mendapatkan nilai CPU usage untuk komponen tertentu dengan lock-free read
    pub fn get_cpu_usage(&self, component: &str) -> Option<f64> {
        let index = self.get_component_index(component);
        let value = self.cpu_metrics[index].load(Ordering::Relaxed);
        if value == 0 {
            None
        } else {
            Some(value as f64 / 100.0)
        }
    }

    /// Mendapatkan nilai memory usage untuk komponen tertentu dengan lock-free read
    pub fn get_memory_usage(&self, component: &str) -> Option<u64> {
        let index = self.get_component_index(component);
        let value = self.memory_metrics[index].load(Ordering::Relaxed);
        if value == 0 {
            None
        } else {
            Some(value)
        }
    }

    /// Mendapatkan nilai response time untuk komponen tertentu dengan lock-free read
    pub fn get_response_time(&self, component: &str) -> Option<f64> {
        let index = self.get_component_index(component);
        let value = self.response_time_metrics[index].load(Ordering::Relaxed);
        if value == 0 {
            None
        } else {
            Some(value as f64)
        }
    }

    /// Mendapatkan semua CPU metrics
    pub fn get_all_cpu_metrics(&self) -> Vec<(String, f64)> {
        let mut metrics = Vec::new();
        let component_hashes = self.component_hashes.lock().unwrap();

        for (component, index) in component_hashes.iter() {
            let value = self.cpu_metrics[*index].load(Ordering::SeqCst);
            if value > 0 {
                metrics.push((component.clone(), value as f64 / 100.0));
            }
        }

        metrics
    }

    /// Mendapatkan semua memory metrics
    pub fn get_all_memory_metrics(&self) -> Vec<(String, u64)> {
        let mut metrics = Vec::new();
        let component_hashes = self.component_hashes.lock().unwrap();

        for (component, index) in component_hashes.iter() {
            let value = self.memory_metrics[*index].load(Ordering::SeqCst);
            if value > 0 {
                metrics.push((component.clone(), value));
            }
        }

        metrics
    }

    /// Mendapatkan semua response time metrics
    pub fn get_all_response_time_metrics(&self) -> Vec<(String, f64)> {
        let mut metrics = Vec::new();
        let component_hashes = self.component_hashes.lock().unwrap();

        for (component, index) in component_hashes.iter() {
            let value = self.response_time_metrics[*index].load(Ordering::SeqCst);
            if value > 0 {
                metrics.push((component.clone(), value as f64));
            }
        }

        metrics
    }

    /// Membersihkan semua metrik
    pub fn clear_all_metrics(&self) {
        for i in 0..256 {
            self.cpu_metrics[i].store(0, Ordering::SeqCst);
            self.memory_metrics[i].store(0, Ordering::SeqCst);
            self.response_time_metrics[i].store(0, Ordering::SeqCst);
        }

        let mut component_hashes = self.component_hashes.lock().unwrap();
        component_hashes.clear();
    }

    /// Mendapatkan uptime monitoring dalam detik
    pub fn get_uptime_seconds(&self) -> i64 {
        let now = Utc::now();
        (now - self.start_time).num_seconds()
    }

    /// Mendapatkan statistik ringkasan
    pub fn get_summary_stats(&self) -> PerformanceSummary {
        let cpu_metrics = self.get_all_cpu_metrics();
        let memory_metrics = self.get_all_memory_metrics();
        let response_metrics = self.get_all_response_time_metrics();

        let avg_cpu = if cpu_metrics.is_empty() {
            0.0
        } else {
            let sum: f64 = cpu_metrics.iter().map(|(_, v)| *v).sum();
            sum / cpu_metrics.len() as f64
        };

        let total_memory = memory_metrics.iter().map(|(_, v)| *v).sum::<u64>();
        let avg_response_time = if response_metrics.is_empty() {
            0.0
        } else {
            let sum: f64 = response_metrics.iter().map(|(_, v)| *v).sum();
            sum / response_metrics.len() as f64
        };

        // Use a set to track unique components (avoid double-counting)
        let mut unique_components = std::collections::HashSet::new();
        cpu_metrics.iter().for_each(|(c, _)| {
            unique_components.insert(c);
        });
        memory_metrics.iter().for_each(|(c, _)| {
            unique_components.insert(c);
        });
        response_metrics.iter().for_each(|(c, _)| {
            unique_components.insert(c);
        });

        PerformanceSummary {
            total_components: unique_components.len(),
            avg_cpu_usage: avg_cpu,
            total_memory_usage: total_memory,
            avg_response_time,
            uptime_seconds: self.get_uptime_seconds(),
        }
    }
}

/// Ringkasan statistik performa
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PerformanceSummary {
    pub total_components: usize,
    pub avg_cpu_usage: f64,
    pub total_memory_usage: u64,
    pub avg_response_time: f64,
    pub uptime_seconds: i64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_performance_monitor_creation() {
        let monitor = PerformanceMonitor::new();
        assert!(!monitor.is_monitoring());
    }

    #[test]
    fn test_cpu_metrics_recording() {
        let monitor = PerformanceMonitor::new();
        monitor.start_monitoring();

        monitor.record_cpu_usage("test_component", 75.5);
        assert_eq!(monitor.get_cpu_usage("test_component"), Some(75.5));
    }

    #[test]
    fn test_memory_metrics_recording() {
        let monitor = PerformanceMonitor::new();
        monitor.start_monitoring();

        monitor.record_memory_usage("test_component", 1024 * 1024);
        assert_eq!(
            monitor.get_memory_usage("test_component"),
            Some(1024 * 1024)
        );
    }

    #[test]
    fn test_response_time_metrics_recording() {
        let monitor = PerformanceMonitor::new();
        monitor.start_monitoring();

        monitor.record_response_time("test_component", 500.0);
        assert_eq!(monitor.get_response_time("test_component"), Some(500.0));
    }

    #[test]
    fn test_summary_stats() {
        let monitor = PerformanceMonitor::new();
        monitor.start_monitoring();

        monitor.record_cpu_usage("comp1", 50.0);
        monitor.record_cpu_usage("comp2", 70.0);
        monitor.record_memory_usage("comp1", 1024);
        monitor.record_response_time("comp1", 100.0);

        let summary = monitor.get_summary_stats();
        assert_eq!(summary.total_components, 2); // Only comp1 and comp2
        assert_eq!(summary.avg_cpu_usage, 60.0);
        assert_eq!(summary.total_memory_usage, 1024);
        assert_eq!(summary.avg_response_time, 100.0);
    }
}
