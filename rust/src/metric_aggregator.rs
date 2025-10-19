//! Metric Aggregator - Sistem agregasi metrik async menggunakan tokio
//! 
//! Modul ini menyediakan sistem agregasi metrik yang efisien dengan
//! support untuk async processing, threshold-based alerting, dan
//! parallel processing menggunakan rayon.

use dashmap::DashMap;
use std::sync::Arc;
use tokio::sync::{mpsc, RwLock};
use tokio::time::{interval, Duration};
use rayon::prelude::*;
use chrono::{DateTime, Utc};
use serde::{Serialize, Deserialize};
use std::collections::HashMap;

/// Tipe agregasi yang didukung
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub enum AggregationType {
    Average,
    Sum,
    Min,
    Max,
    Count,
    StdDeviation,
    Percentile(f64), // 0.0 - 100.0
}

/// Rule untuk alerting berbasis threshold
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AlertRule {
    /// Nama rule
    pub name: String,
    /// Nama metrik yang dimonitor
    pub metric_name: String,
    /// Tipe agregasi untuk evaluasi
    pub aggregation_type: AggregationType,
    /// Threshold value
    pub threshold: f64,
    /// Operator perbandingan (>, <, >=, <=, ==, !=)
    pub operator: String,
    /// Durasi window untuk evaluasi
    pub window_duration: Duration,
    /// Aksi yang diambil saat trigger
    pub action: AlertAction,
    /// Enable/disable rule
    pub enabled: bool,
}

/// Aksi alerting yang bisa diambil
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum AlertAction {
    Log,
    Notify,
    Escalate,
    Custom(String),
}

/// Hasil agregasi metrik
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AggregationResult {
    pub metric_name: String,
    pub aggregation_type: AggregationType,
    pub value: f64,
    pub timestamp: DateTime<Utc>,
    pub window_start: DateTime<Utc>,
    pub window_end: DateTime<Utc>,
    pub sample_count: usize,
}

/// Alert yang di-trigger
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Alert {
    pub rule_name: String,
    pub metric_name: String,
    pub triggered_value: f64,
    pub threshold: f64,
    pub operator: String,
    pub timestamp: DateTime<Utc>,
    pub severity: AlertSeverity,
}

/// Tingkat severity alert
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum AlertSeverity {
    Info,
    Warning,
    Critical,
    Emergency,
}

/// Konfigurasi untuk MetricAggregator
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MetricAggregatorConfig {
    /// Interval agregasi
    pub aggregation_interval: Duration,
    /// Jumlah worker threads untuk parallel processing
    pub worker_threads: usize,
    /// Max buffer size untuk metric queue
    pub max_buffer_size: usize,
    /// Enable alerting
    pub alerting_enabled: bool,
    /// Cleanup interval untuk data lama
    pub cleanup_interval: Duration,
}

impl Default for MetricAggregatorConfig {
    fn default() -> Self {
        Self {
            aggregation_interval: Duration::from_secs(60), // 1 menit
            worker_threads: 4,
            max_buffer_size: 100000,
            alerting_enabled: true,
            cleanup_interval: Duration::from_secs(3600), // 1 jam
        }
    }
}

/// Input data untuk agregasi
#[derive(Debug, Clone)]
pub struct MetricData {
    pub metric_name: String,
    pub value: f64,
    pub timestamp: DateTime<Utc>,
    pub tags: HashMap<String, String>,
}

/// Metric Aggregator utama
pub struct MetricAggregator {
    /// Queue untuk menerima data metrik
    data_queue: Arc<RwLock<mpsc::UnboundedReceiver<MetricData>>>,
    /// Sender untuk mengirim data ke queue
    data_sender: mpsc::UnboundedSender<MetricData>,
    /// Buffer untuk menyimpan data sementara
    data_buffer: Arc<RwLock<Vec<MetricData>>>,
    /// Alert rules
    alert_rules: Arc<DashMap<String, AlertRule>>,
    /// Hasil agregasi
    aggregation_results: Arc<DashMap<String, AggregationResult>>,
    /// Alerts yang di-trigger
    alerts: Arc<RwLock<Vec<Alert>>>,
    /// Konfigurasi
    config: MetricAggregatorConfig,
    /// Status running
    is_running: Arc<RwLock<bool>>,
}

impl MetricAggregator {
    /// Membuat aggregator baru dengan konfigurasi default
    pub fn new() -> Self {
        let config = MetricAggregatorConfig::default();
        Self::with_config(config)
    }

    /// Membuat aggregator dengan konfigurasi kustom
    pub fn with_config(config: MetricAggregatorConfig) -> Self {
        let (sender, receiver) = mpsc::unbounded_channel();
        
        Self {
            data_queue: Arc::new(RwLock::new(receiver)),
            data_sender: sender,
            data_buffer: Arc::new(RwLock::new(Vec::with_capacity(config.max_buffer_size))),
            alert_rules: Arc::new(DashMap::new()),
            aggregation_results: Arc::new(DashMap::new()),
            alerts: Arc::new(RwLock::new(Vec::new())),
            config,
            is_running: Arc::new(RwLock::new(false)),
        }
    }

    /// Menambahkan data metrik ke queue
    pub fn add_metric_data(&self, data: MetricData) -> Result<(), String> {
        match self.data_sender.send(data) {
            Ok(_) => Ok(()),
            Err(e) => Err(format!("Failed to send metric data: {}", e)),
        }
    }

    /// Menambahkan alert rule
    pub fn add_alert_rule(&self, rule: AlertRule) -> Result<(), String> {
        if !rule.enabled {
            return Ok(()); // Skip disabled rules
        }

        self.alert_rules.insert(rule.name.clone(), rule);
        Ok(())
    }

    /// Menghapus alert rule
    pub fn remove_alert_rule(&self, name: &str) -> bool {
        self.alert_rules.remove(name).is_some()
    }

    /// Memulai agregasi
    pub async fn start_aggregation(&self) -> Result<(), String> {
        let mut is_running = self.is_running.write().await;
        if *is_running {
            return Err("Aggregator is already running".to_string());
        }
        *is_running = true;

        // Clone untuk digunakan dalam task
        let data_queue = self.data_queue.clone();
        let data_buffer_clone1 = self.data_buffer.clone();
        let data_buffer_clone2 = self.data_buffer.clone();
        let data_buffer_clone3 = self.data_buffer.clone();
        let alert_rules = self.alert_rules.clone();
        let aggregation_results = self.aggregation_results.clone();
        let alerts = self.alerts.clone();
        let config = self.config.clone();
        let config_clone = config.clone();
        let is_running_clone = self.is_running.clone();

        // Task untuk mengumpulkan data dari queue
        let collect_task = tokio::spawn(async move {
            Self::run_data_collection(data_queue, data_buffer_clone1, is_running_clone).await;
        });

        // Task untuk agregasi berkala
        let aggregation_task = tokio::spawn(async move {
            Self::run_aggregation(
                data_buffer_clone2,
                alert_rules,
                aggregation_results,
                alerts,
                config,
            ).await;
        });

        // Task untuk cleanup
        let cleanup_task = tokio::spawn(async move {
            Self::run_cleanup(data_buffer_clone3, config_clone.cleanup_interval).await;
        });

        // Wait untuk tasks (dalam implementasi nyata, ini akan berjalan terus)
        tokio::select! {
            _ = collect_task => {},
            _ = aggregation_task => {},
            _ = cleanup_task => {},
        }

        Ok(())
    }

    /// Task untuk mengumpulkan data dari queue
    async fn run_data_collection(
        data_queue: Arc<RwLock<mpsc::UnboundedReceiver<MetricData>>>,
        data_buffer: Arc<RwLock<Vec<MetricData>>>,
        is_running: Arc<RwLock<bool>>,
    ) {
        while *is_running.read().await {
            let mut queue = data_queue.write().await;
            let mut buffer = data_buffer.write().await;
            
            // Collect data dari queue
            while let Ok(data) = queue.try_recv() {
                if buffer.len() < buffer.capacity() {
                    buffer.push(data);
                } else {
                    // Buffer penuh, hapus data lama
                    buffer.remove(0);
                    buffer.push(data);
                }
            }
            
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
    }

    /// Task untuk melakukan agregasi
    async fn run_aggregation(
        data_buffer: Arc<RwLock<Vec<MetricData>>>,
        alert_rules: Arc<DashMap<String, AlertRule>>,
        aggregation_results: Arc<DashMap<String, AggregationResult>>,
        alerts: Arc<RwLock<Vec<Alert>>>,
        config: MetricAggregatorConfig,
    ) {
        let mut interval = interval(config.aggregation_interval);
        
        loop {
            interval.tick().await;
            
            let buffer = data_buffer.read().await;
            if buffer.is_empty() {
                continue;
            }

            // Lakukan agregasi dengan rayon untuk parallel processing
            let results = Self::perform_aggregation(&buffer);
            
            // Simpan hasil agregasi
            for result in &results {
                aggregation_results.insert(result.metric_name.clone(), result.clone());
            }

            // Evaluasi alert rules jika di-enable
            if config.alerting_enabled {
                Self::evaluate_alert_rules(&results, &alert_rules, &alerts).await;
            }
        }
    }

    /// Melakukan agregasi data dengan parallel processing
    fn perform_aggregation(data: &[MetricData]) -> Vec<AggregationResult> {
        // Group data by metric name
        let mut grouped_data: HashMap<String, Vec<&MetricData>> = HashMap::new();
        for data_point in data {
            grouped_data
                .entry(data_point.metric_name.clone())
                .or_insert_with(Vec::new)
                .push(data_point);
        }

        // Lakukan agregasi untuk setiap grup dengan rayon
        let results: Vec<AggregationResult> = grouped_data
            .par_iter()
            .flat_map(|(metric_name, data_points)| {
                let values: Vec<f64> = data_points.iter().map(|d| d.value).collect();
                let timestamps: Vec<DateTime<Utc>> = data_points.iter().map(|d| d.timestamp).collect();
                
                if values.is_empty() {
                    return vec![];
                }

                let window_start = timestamps.iter().min().unwrap();
                let window_end = timestamps.iter().max().unwrap();

                // Hitung berbagai tipe agregasi
                vec![
                    Self::calculate_average(metric_name.clone(), &values, *window_start, *window_end),
                    Self::calculate_sum(metric_name.clone(), &values, *window_start, *window_end),
                    Self::calculate_min(metric_name.clone(), &values, *window_start, *window_end),
                    Self::calculate_max(metric_name.clone(), &values, *window_start, *window_end),
                    Self::calculate_std_deviation(metric_name.clone(), &values, *window_start, *window_end),
                ]
            })
            .collect();

        results
    }

    /// Menghitung rata-rata
    fn calculate_average(
        metric_name: String,
        values: &[f64],
        window_start: DateTime<Utc>,
        window_end: DateTime<Utc>,
    ) -> AggregationResult {
        let sum = values.iter().sum::<f64>();
        let avg = sum / values.len() as f64;

        AggregationResult {
            metric_name,
            aggregation_type: AggregationType::Average,
            value: avg,
            timestamp: Utc::now(),
            window_start,
            window_end,
            sample_count: values.len(),
        }
    }

    /// Menghitung sum
    fn calculate_sum(
        metric_name: String,
        values: &[f64],
        window_start: DateTime<Utc>,
        window_end: DateTime<Utc>,
    ) -> AggregationResult {
        let sum = values.iter().sum::<f64>();

        AggregationResult {
            metric_name,
            aggregation_type: AggregationType::Sum,
            value: sum,
            timestamp: Utc::now(),
            window_start,
            window_end,
            sample_count: values.len(),
        }
    }

    /// Menghitung minimum
    fn calculate_min(
        metric_name: String,
        values: &[f64],
        window_start: DateTime<Utc>,
        window_end: DateTime<Utc>,
    ) -> AggregationResult {
        let min = values.iter().fold(f64::INFINITY, |a, &b| a.min(b));

        AggregationResult {
            metric_name,
            aggregation_type: AggregationType::Min,
            value: min,
            timestamp: Utc::now(),
            window_start,
            window_end,
            sample_count: values.len(),
        }
    }

    /// Menghitung maximum
    fn calculate_max(
        metric_name: String,
        values: &[f64],
        window_start: DateTime<Utc>,
        window_end: DateTime<Utc>,
    ) -> AggregationResult {
        let max = values.iter().fold(f64::NEG_INFINITY, |a, &b| a.max(b));

        AggregationResult {
            metric_name,
            aggregation_type: AggregationType::Max,
            value: max,
            timestamp: Utc::now(),
            window_start,
            window_end,
            sample_count: values.len(),
        }
    }

    /// Menghitung standard deviation
    fn calculate_std_deviation(
        metric_name: String,
        values: &[f64],
        window_start: DateTime<Utc>,
        window_end: DateTime<Utc>,
    ) -> AggregationResult {
        let avg = values.iter().sum::<f64>() / values.len() as f64;
        let variance = values
            .iter()
            .map(|value| (value - avg).powi(2))
            .sum::<f64>() / values.len() as f64;
        let std_dev = variance.sqrt();

        AggregationResult {
            metric_name,
            aggregation_type: AggregationType::StdDeviation,
            value: std_dev,
            timestamp: Utc::now(),
            window_start,
            window_end,
            sample_count: values.len(),
        }
    }

    /// Mengevaluasi alert rules
    async fn evaluate_alert_rules(
        results: &[AggregationResult],
        alert_rules: &Arc<DashMap<String, AlertRule>>,
        alerts: &Arc<RwLock<Vec<Alert>>>,
    ) {
        for result in results {
            for rule_entry in alert_rules.iter() {
                let rule = rule_entry.value();
                
                if rule.metric_name != result.metric_name {
                    continue;
                }

                // Evaluasi threshold
                let should_trigger = match rule.operator.as_str() {
                    ">" => result.value > rule.threshold,
                    "<" => result.value < rule.threshold,
                    ">=" => result.value >= rule.threshold,
                    "<=" => result.value <= rule.threshold,
                    "==" => (result.value - rule.threshold).abs() < f64::EPSILON,
                    "!=" => (result.value - rule.threshold).abs() > f64::EPSILON,
                    _ => false,
                };

                if should_trigger {
                    let alert = Alert {
                        rule_name: rule.name.clone(),
                        metric_name: result.metric_name.clone(),
                        triggered_value: result.value,
                        threshold: rule.threshold,
                        operator: rule.operator.clone(),
                        timestamp: Utc::now(),
                        severity: Self::determine_severity(result.value, rule.threshold, &rule.operator),
                    };

                    let mut alerts_list = alerts.write().await;
                    alerts_list.push(alert);
                }
            }
        }
    }

    /// Menentukan severity alert berdasarkan nilai dan threshold
    fn determine_severity(value: f64, threshold: f64, operator: &str) -> AlertSeverity {
        match operator {
            ">" => {
                if value > threshold * 1.5 {
                    AlertSeverity::Critical
                } else if value > threshold * 1.2 {
                    AlertSeverity::Warning
                } else {
                    AlertSeverity::Info
                }
            }
            "<" => {
                if value < threshold * 0.5 {
                    AlertSeverity::Critical
                } else if value < threshold * 0.8 {
                    AlertSeverity::Warning
                } else {
                    AlertSeverity::Info
                }
            }
            _ => AlertSeverity::Info,
        }
    }

    /// Task untuk cleanup data lama
    async fn run_cleanup(
        data_buffer: Arc<RwLock<Vec<MetricData>>>,
        cleanup_interval: Duration,
    ) {
        let mut interval = interval(cleanup_interval);
        
        loop {
            interval.tick().await;
            
            let mut buffer = data_buffer.write().await;
            let cutoff_time = Utc::now() - chrono::Duration::hours(24);
            
            // Hapus data yang lebih lama dari 24 jam
            buffer.retain(|data| data.timestamp > cutoff_time);
        }
    }

    /// Mendapatkan hasil agregasi terakhir
    pub fn get_latest_aggregation(&self, metric_name: &str) -> Option<AggregationResult> {
        self.aggregation_results.get(metric_name).map(|result| result.clone())
    }

    /// Mendapatkan semua hasil agregasi
    pub fn get_all_aggregations(&self) -> Vec<AggregationResult> {
        self.aggregation_results
            .iter()
            .map(|entry| entry.value().clone())
            .collect()
    }

    /// Mendapatkan alerts yang belum diproses
    pub async fn get_pending_alerts(&self) -> Vec<Alert> {
        let mut alerts = self.alerts.write().await;
        let pending_alerts = alerts.clone();
        alerts.clear();
        pending_alerts
    }

    /// Menghentikan aggregator
    pub async fn stop(&self) {
        let mut is_running = self.is_running.write().await;
        *is_running = false;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_metric_aggregator_creation() {
        let aggregator = MetricAggregator::new();
        assert!(!*aggregator.is_running.read().await);
    }

    #[tokio::test]
    async fn test_add_metric_data() {
        let aggregator = MetricAggregator::new();
        
        let data = MetricData {
            metric_name: "test_metric".to_string(),
            value: 100.0,
            timestamp: Utc::now(),
            tags: HashMap::new(),
        };

        let result = aggregator.add_metric_data(data);
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_alert_rule_evaluation() {
        let aggregator = MetricAggregator::new();
        
        let rule = AlertRule {
            name: "cpu_alert".to_string(),
            metric_name: "cpu_usage".to_string(),
            aggregation_type: AggregationType::Average,
            threshold: 80.0,
            operator: ">".to_string(),
            window_duration: Duration::from_secs(60),
            action: AlertAction::Log,
            enabled: true,
        };

        aggregator.add_alert_rule(rule).unwrap();

        // Tambahkan data metrik
        let data = MetricData {
            metric_name: "cpu_usage".to_string(),
            value: 90.0,
            timestamp: Utc::now(),
            tags: HashMap::new(),
        };

        aggregator.add_metric_data(data).unwrap();

        // Trigger manual aggregation untuk test
        let buffer = vec![MetricData {
            metric_name: "cpu_usage".to_string(),
            value: 90.0,
            timestamp: Utc::now(),
            tags: HashMap::new(),
        }];

        let results = MetricAggregator::perform_aggregation(&buffer);
        assert!(!results.is_empty());
    }

    #[test]
    fn test_aggregation_calculations() {
        let values = vec![10.0, 20.0, 30.0, 40.0, 50.0];
        let window_start = Utc::now();
        let window_end = Utc::now();

        let avg_result = MetricAggregator::calculate_average("test".to_string(), &values, window_start, window_end);
        assert_eq!(avg_result.value, 30.0);

        let sum_result = MetricAggregator::calculate_sum("test".to_string(), &values, window_start, window_end);
        assert_eq!(sum_result.value, 150.0);

        let min_result = MetricAggregator::calculate_min("test".to_string(), &values, window_start, window_end);
        assert_eq!(min_result.value, 10.0);

        let max_result = MetricAggregator::calculate_max("test".to_string(), &values, window_start, window_end);
        assert_eq!(max_result.value, 50.0);
    }
}