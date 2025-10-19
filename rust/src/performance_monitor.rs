//! Performance Monitor - Sistem monitoring performa native Rust
//! 
//! Modul ini menyediakan struktur utama untuk monitoring performa dengan
//! menggunakan DashMap untuk thread-safe data collection dan atomic types
//! untuk operasi yang efisien.

use dashmap::DashMap;
use std::sync::atomic::{AtomicU64, AtomicU32, Ordering};
use std::sync::Arc;
use chrono::{DateTime, Utc};
use serde::{Serialize, Deserialize};

/// Struktur utama untuk performance monitoring
#[derive(Debug, Clone)]
pub struct PerformanceMonitor {
    /// Koleksi metrik CPU usage
    cpu_metrics: Arc<DashMap<String, AtomicU32>>,
    /// Koleksi metrik memory usage
    memory_metrics: Arc<DashMap<String, AtomicU64>>,
    /// Koleksi metrik response time
    response_time_metrics: Arc<DashMap<String, AtomicU32>>,
    /// Timestamp untuk tracking
    start_time: DateTime<Utc>,
    /// Status monitoring
    is_monitoring: Arc<std::sync::atomic::AtomicBool>,
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
            measurement_interval: 1000, // 1 detik
            cpu_threshold: 80.0, // 80%
            memory_threshold: 1024 * 1024 * 1024, // 1GB
            response_time_threshold: 1000.0, // 1 detik
            enabled: true,
        }
    }
}

impl PerformanceMonitor {
    /// Membuat instance PerformanceMonitor baru
    pub fn new() -> Self {
        Self {
            cpu_metrics: Arc::new(DashMap::new()),
            memory_metrics: Arc::new(DashMap::new()),
            response_time_metrics: Arc::new(DashMap::new()),
            start_time: Utc::now(),
            is_monitoring: Arc::new(std::sync::atomic::AtomicBool::new(false)),
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

    /// Merekam metrik CPU usage
    pub fn record_cpu_usage(&self, component: &str, usage: f64) {
        if !self.is_monitoring() {
            return;
        }

        let usage_u32 = (usage * 100.0) as u32; // Convert to integer for atomic storage
        self.cpu_metrics
            .entry(component.to_string())
            .or_insert_with(|| AtomicU32::new(0))
            .store(usage_u32, Ordering::SeqCst);
    }

    /// Merekam metrik memory usage
    pub fn record_memory_usage(&self, component: &str, usage: u64) {
        if !self.is_monitoring() {
            return;
        }

        self.memory_metrics
            .entry(component.to_string())
            .or_insert_with(|| AtomicU64::new(0))
            .store(usage, Ordering::SeqCst);
    }

    /// Merekam metrik response time
    pub fn record_response_time(&self, component: &str, response_time: f64) {
        if !self.is_monitoring() {
            return;
        }

        let response_time_u32 = response_time as u32; // Convert to integer for atomic storage
        self.response_time_metrics
            .entry(component.to_string())
            .or_insert_with(|| AtomicU32::new(0))
            .store(response_time_u32, Ordering::SeqCst);
    }

    /// Merekam metrik umum dengan kategori
    pub fn record_metric(&self, category: &str, value: f64) {
        if !self.is_monitoring() {
            return;
        }
        
        // Redirect to appropriate specialized method based on category
        match category {
            "combat_attack_processing_time" | "hit_detection_time" | "damage_calculation_time" | "aoe_damage_processing_time" | "combat_system_update_time" => {
                self.record_response_time(category, value);
            }
            _ => {
                // For unknown categories, use response_time as default
                self.record_response_time(category, value);
            }
        }
    }

    /// Mendapatkan nilai CPU usage untuk komponen tertentu
    pub fn get_cpu_usage(&self, component: &str) -> Option<f64> {
        self.cpu_metrics
            .get(component)
            .map(|metric| metric.load(Ordering::SeqCst) as f64 / 100.0)
    }

    /// Mendapatkan nilai memory usage untuk komponen tertentu
    pub fn get_memory_usage(&self, component: &str) -> Option<u64> {
        self.memory_metrics
            .get(component)
            .map(|metric| metric.load(Ordering::SeqCst))
    }

    /// Mendapatkan nilai response time untuk komponen tertentu
    pub fn get_response_time(&self, component: &str) -> Option<f64> {
        self.response_time_metrics
            .get(component)
            .map(|metric| metric.load(Ordering::SeqCst) as f64)
    }

    /// Mendapatkan semua CPU metrics
    pub fn get_all_cpu_metrics(&self) -> Vec<(String, f64)> {
        self.cpu_metrics
            .iter()
            .map(|entry| (entry.key().clone(), entry.value().load(Ordering::SeqCst) as f64 / 100.0))
            .collect()
    }

    /// Mendapatkan semua memory metrics
    pub fn get_all_memory_metrics(&self) -> Vec<(String, u64)> {
        self.memory_metrics
            .iter()
            .map(|entry| (entry.key().clone(), entry.value().load(Ordering::SeqCst)))
            .collect()
    }

    /// Mendapatkan semua response time metrics
    pub fn get_all_response_time_metrics(&self) -> Vec<(String, f64)> {
        self.response_time_metrics
            .iter()
            .map(|entry| (entry.key().clone(), entry.value().load(Ordering::SeqCst) as f64))
            .collect()
    }

    /// Membersihkan semua metrik
    pub fn clear_all_metrics(&self) {
        self.cpu_metrics.clear();
        self.memory_metrics.clear();
        self.response_time_metrics.clear();
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
            cpu_metrics.iter().map(|(_, v)| v).sum::<f64>() / cpu_metrics.len() as f64
        };

        let total_memory = memory_metrics.iter().map(|(_, v)| v).sum::<u64>();
        let avg_response_time = if response_metrics.is_empty() {
            0.0
        } else {
            response_metrics.iter().map(|(_, v)| v).sum::<f64>() / response_metrics.len() as f64
        };

        PerformanceSummary {
            total_components: cpu_metrics.len() + memory_metrics.len() + response_metrics.len(),
            avg_cpu_usage: avg_cpu,
            total_memory_usage: total_memory,
            avg_response_time: avg_response_time,
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
        assert_eq!(monitor.get_memory_usage("test_component"), Some(1024 * 1024));
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
        assert_eq!(summary.total_components, 3);
        assert_eq!(summary.avg_cpu_usage, 60.0);
        assert_eq!(summary.total_memory_usage, 1024);
        assert_eq!(summary.avg_response_time, 100.0);
    }
}