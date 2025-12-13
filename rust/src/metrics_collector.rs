//! Metrics Collector - Struktur data untuk koleksi metrik performa
//!
//! Modul ini menyediakan struktur data untuk mengumpulkan dan menyimpan
//! berbagai jenis metrik performa dengan dukungan untuk time-series data
//! dan operasi thread-safe.

use chrono::{DateTime, Duration, Utc};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::sync::Arc;

/// Tipe metrik yang dikumpulkan
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum MetricType {
    CpuUsage,
    MemoryUsage,
    ResponseTime,
    Throughput,
    ErrorRate,
    Custom,
}

/// Satuan metrik
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum MetricUnit {
    Percent,
    Bytes,
    Milliseconds,
    RequestsPerSecond,
    Count,
    Custom,
}

/// Data point untuk time-series metrik
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricDataPoint {
    /// Timestamp data point
    pub timestamp: DateTime<Utc>,
    /// Nilai metrik
    pub value: f64,
    /// Label tambahan
    pub label: Option<String>,
}

/// Metadata untuk metrik
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricMetadata {
    /// Nama metrik
    pub name: String,
    /// Tipe metrik
    pub metric_type: MetricType,
    /// Satuan metrik
    pub unit: MetricUnit,
    /// Deskripsi metrik
    pub description: Option<String>,
    /// Tags untuk kategorisasi
    pub tags: Vec<String>,
}

/// Struktur untuk menyimpan time-series data metrik
#[derive(Debug, Clone)]
pub struct TimeSeriesMetric {
    /// Metadata metrik
    pub metadata: MetricMetadata,
    /// Data points dalam bentuk deque untuk efisiensi
    pub data_points: Arc<std::sync::Mutex<VecDeque<MetricDataPoint>>>,
    /// Kapasitas maksimum data points (untuk menghindari memory leak)
    pub max_capacity: usize,
}

/// Kolektor metrik utama
#[derive(Debug, Clone)]
pub struct MetricsCollector {
    /// Koleksi time-series metrik
    metrics: Arc<DashMap<String, TimeSeriesMetric>>,
    /// Default max capacity untuk time-series
    default_max_capacity: usize,
}

impl TimeSeriesMetric {
    /// Membuat time-series metrik baru
    pub fn new(metadata: MetricMetadata, max_capacity: usize) -> Self {
        Self {
            metadata,
            data_points: Arc::new(std::sync::Mutex::new(VecDeque::with_capacity(max_capacity))),
            max_capacity,
        }
    }

    /// Menambahkan data point baru
    pub fn add_data_point(&self, value: f64, label: Option<String>) {
        let data_point = MetricDataPoint {
            timestamp: Utc::now(),
            value,
            label,
        };

        let mut data_points = self.data_points.lock().unwrap();

        // Hapus data points yang lebih lama dari 24 jam jika sudah mencapai kapasitas
        if data_points.len() >= self.max_capacity {
            data_points.pop_front();
        }

        data_points.push_back(data_point);
    }

    /// Mendapatkan data points dalam rentang waktu tertentu
    pub fn get_data_points_in_range(
        &self,
        start_time: DateTime<Utc>,
        end_time: DateTime<Utc>,
    ) -> Vec<MetricDataPoint> {
        let data_points = self.data_points.lock().unwrap();
        data_points
            .iter()
            .filter(|point| point.timestamp >= start_time && point.timestamp <= end_time)
            .cloned()
            .collect()
    }

    /// Mendapatkan latest data point
    pub fn get_latest_data_point(&self) -> Option<MetricDataPoint> {
        let data_points = self.data_points.lock().unwrap();
        data_points.back().cloned()
    }

    /// Mendapatkan data points dalam durasi terakhir
    pub fn get_recent_data_points(&self, duration: Duration) -> Vec<MetricDataPoint> {
        let end_time = Utc::now();
        let start_time = end_time - duration;
        self.get_data_points_in_range(start_time, end_time)
    }

    /// Menghitung statistik dasar
    pub fn calculate_statistics(&self) -> MetricStatistics {
        let data_points = self.data_points.lock().unwrap();

        if data_points.is_empty() {
            return MetricStatistics::empty();
        }

        let values: Vec<f64> = data_points.iter().map(|point| point.value).collect();
        let count = values.len();
        let sum = values.iter().sum::<f64>();
        let avg = sum / count as f64;

        let min = values.iter().fold(f64::INFINITY, |a, &b| a.min(b));
        let max = values.iter().fold(f64::NEG_INFINITY, |a, &b| a.max(b));

        // Hitung standard deviation
        let variance = values
            .iter()
            .map(|value| (value - avg).powi(2))
            .sum::<f64>()
            / count as f64;
        let std_dev = variance.sqrt();

        MetricStatistics {
            count,
            min,
            max,
            average: avg,
            std_deviation: std_dev,
            latest_value: *values.last().unwrap_or(&0.0),
        }
    }

    /// Membersihkan data points yang lebih lama dari durasi tertentu
    pub fn cleanup_old_data(&self, max_age: Duration) {
        let mut data_points = self.data_points.lock().unwrap();
        let cutoff_time = Utc::now() - max_age;

        // Hapus data points yang lebih lama dari cutoff time
        while let Some(front) = data_points.front() {
            if front.timestamp < cutoff_time {
                data_points.pop_front();
            } else {
                break;
            }
        }
    }
}

/// Statistik metrik
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricStatistics {
    pub count: usize,
    pub min: f64,
    pub max: f64,
    pub average: f64,
    pub std_deviation: f64,
    pub latest_value: f64,
}

impl MetricStatistics {
    /// Membuat statistik kosong
    pub fn empty() -> Self {
        Self {
            count: 0,
            min: 0.0,
            max: 0.0,
            average: 0.0,
            std_deviation: 0.0,
            latest_value: 0.0,
        }
    }
}

impl MetricsCollector {
    /// Membuat kolektor metrik baru
    pub fn new() -> Self {
        Self {
            metrics: Arc::new(DashMap::new()),
            default_max_capacity: 10000, // 10k data points default
        }
    }

    /// Membuat kolektor dengan kapasitas custom
    pub fn with_capacity(max_capacity: usize) -> Self {
        Self {
            metrics: Arc::new(DashMap::new()),
            default_max_capacity: max_capacity,
        }
    }

    /// Mendaftarkan metrik baru
    pub fn register_metric(
        &self,
        name: String,
        metric_type: MetricType,
        unit: MetricUnit,
        description: Option<String>,
        tags: Vec<String>,
    ) -> Result<(), String> {
        if self.metrics.contains_key(&name) {
            return Err(format!("Metric '{}' already exists", name));
        }

        let metadata = MetricMetadata {
            name: name.clone(),
            metric_type,
            unit,
            description,
            tags,
        };

        let time_series = TimeSeriesMetric::new(metadata, self.default_max_capacity);
        self.metrics.insert(name.clone(), time_series);
        Ok(())
    }

    /// Merekam nilai metrik
    pub fn record_metric(
        &self,
        name: &str,
        value: f64,
        label: Option<String>,
    ) -> Result<(), String> {
        match self.metrics.get(name) {
            Some(metric) => {
                metric.add_data_point(value, label);
                Ok(())
            }
            None => Err(format!("Metric '{}' not found", name)),
        }
    }

    /// Merekam multiple nilai metrik sekaligus (batch)
    pub fn record_metrics_batch(
        &self,
        records: Vec<(String, f64, Option<String>)>,
    ) -> Vec<Result<(), String>> {
        records
            .into_iter()
            .map(|(name, value, label)| self.record_metric(&name, value, label))
            .collect()
    }

    /// Mendapatkan time-series metrik
    pub fn get_metric(&self, name: &str) -> Option<TimeSeriesMetric> {
        self.metrics.get(name).map(|metric| metric.clone())
    }

    /// Mendapatkan semua nama metrik
    pub fn get_all_metric_names(&self) -> Vec<String> {
        self.metrics
            .iter()
            .map(|entry| entry.key().clone())
            .collect()
    }

    /// Mendapatkan metrik berdasarkan tipe
    pub fn get_metrics_by_type(&self, metric_type: MetricType) -> Vec<String> {
        self.metrics
            .iter()
            .filter(|entry| entry.value().metadata.metric_type == metric_type)
            .map(|entry| entry.key().clone())
            .collect()
    }

    /// Mendapatkan metrik berdasarkan tag
    pub fn get_metrics_by_tag(&self, tag: &str) -> Vec<String> {
        self.metrics
            .iter()
            .filter(|entry| entry.value().metadata.tags.contains(&tag.to_string()))
            .map(|entry| entry.key().clone())
            .collect()
    }

    /// Menghapus metrik
    pub fn remove_metric(&self, name: &str) -> bool {
        self.metrics.remove(name).is_some()
    }

    /// Membersihkan metrik yang tidak aktif (opsional)
    pub fn cleanup_inactive_metrics(&self, max_age: Duration) {
        let metric_names = self.get_all_metric_names();

        for name in metric_names {
            if let Some(metric) = self.metrics.get(&name) {
                let latest_point = metric.get_latest_data_point();

                // Hapus jika tidak ada data dalam waktu tertentu
                if let Some(point) = latest_point {
                    if Utc::now() - point.timestamp > max_age {
                        self.metrics.remove(&name);
                    }
                } else {
                    // Hapus jika tidak ada data points sama sekali
                    self.metrics.remove(&name);
                }
            }
        }
    }

    /// Mendapatkan statistik untuk semua metrik
    pub fn get_all_statistics(&self) -> DashMap<String, MetricStatistics> {
        let stats = DashMap::new();

        for entry in self.metrics.iter() {
            let name = entry.key().clone();
            let metric = entry.value();
            let statistics = metric.calculate_statistics();
            stats.insert(name, statistics);
        }

        stats
    }

    /// Membersihkan semua metrik
    pub fn clear_all(&self) {
        self.metrics.clear();
    }

    /// Mendapatkan jumlah total metrik
    pub fn get_metric_count(&self) -> usize {
        self.metrics.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_time_series_metric_creation() {
        let metadata = MetricMetadata {
            name: "test_metric".to_string(),
            metric_type: MetricType::CpuUsage,
            unit: MetricUnit::Percent,
            description: Some("Test metric".to_string()),
            tags: vec!["test".to_string()],
        };

        let metric = TimeSeriesMetric::new(metadata, 100);
        assert_eq!(metric.metadata.name, "test_metric");
        assert_eq!(metric.max_capacity, 100);
    }

    #[test]
    fn test_metrics_collector_registration() {
        let collector = MetricsCollector::new();

        let result = collector.register_metric(
            "cpu_usage".to_string(),
            MetricType::CpuUsage,
            MetricUnit::Percent,
            Some("CPU usage metric".to_string()),
            vec!["system".to_string()],
        );

        assert!(result.is_ok());
        assert!(collector.get_metric("cpu_usage").is_some());
    }

    #[test]
    fn test_metric_recording() {
        let collector = MetricsCollector::new();

        collector
            .register_metric(
                "response_time".to_string(),
                MetricType::ResponseTime,
                MetricUnit::Milliseconds,
                None,
                vec![],
            )
            .unwrap();

        let result =
            collector.record_metric("response_time", 100.0, Some("test_label".to_string()));
        assert!(result.is_ok());

        let metric = collector.get_metric("response_time").unwrap();
        let latest = metric.get_latest_data_point();
        assert!(latest.is_some());
        assert_eq!(latest.unwrap().value, 100.0);
    }

    #[test]
    fn test_statistics_calculation() {
        let metadata = MetricMetadata {
            name: "test_metric".to_string(),
            metric_type: MetricType::CpuUsage,
            unit: MetricUnit::Percent,
            description: None,
            tags: vec![],
        };

        let metric = TimeSeriesMetric::new(metadata, 100);

        // Tambahkan beberapa data points
        for i in 1..=5 {
            metric.add_data_point(i as f64 * 10.0, None);
        }

        let stats = metric.calculate_statistics();
        assert_eq!(stats.count, 5);
        assert_eq!(stats.min, 10.0);
        assert_eq!(stats.max, 50.0);
        assert_eq!(stats.average, 30.0);
    }

    #[test]
    fn test_batch_recording() {
        let collector = MetricsCollector::new();

        collector
            .register_metric(
                "metric1".to_string(),
                MetricType::CpuUsage,
                MetricUnit::Percent,
                None,
                vec![],
            )
            .unwrap();
        collector
            .register_metric(
                "metric2".to_string(),
                MetricType::MemoryUsage,
                MetricUnit::Bytes,
                None,
                vec![],
            )
            .unwrap();

        let records = vec![
            ("metric1".to_string(), 50.0, None),
            ("metric2".to_string(), 1024.0, None),
        ];

        let results = collector.record_metrics_batch(records);
        assert_eq!(results.len(), 2);
        assert!(results.iter().all(|r| r.is_ok()));
    }
}
