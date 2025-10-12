use log::{debug, error, info};
use std::fs;
use std::path::Path;
use std::sync::{Arc, RwLock};
use std::time::{Duration, Instant};

use crate::database::RustDatabaseAdapter;

/// Checksum monitoring configuration
#[derive(Debug, Clone)]
pub struct ChecksumMonitorConfig {
    pub verification_interval: Duration,
    pub reliability_threshold: f64,
    pub auto_repair: bool,
    pub max_concurrent_verifications: usize,
}

impl Default for ChecksumMonitorConfig {
    fn default() -> Self {
        Self {
            verification_interval: Duration::from_secs(300), // 5 minutes
            reliability_threshold: 95.0,                     // 95% reliability threshold
            auto_repair: false,                              // Don't auto-repair by default
            max_concurrent_verifications: 4,                 // Limit concurrent operations
        }
    }
}

/// Checksum verification result
#[derive(Debug, Clone)]
pub enum ChecksumVerificationResult {
    Pass,
    Fail(String),
    Skip(String),
}

/// Checksum monitoring service for database integrity
pub struct ChecksumMonitor {
    adapter: Arc<RustDatabaseAdapter>,
    config: ChecksumMonitorConfig,
    stats: Arc<RwLock<ChecksumMonitorStats>>,
    is_running: Arc<RwLock<bool>>,
}

/// Checksum monitor statistics
#[derive(Debug, Clone, Default)]
pub struct ChecksumMonitorStats {
    pub total_verifications: u64,
    pub total_failures: u64,
    pub last_verification_time: u64,
    pub current_reliability: f64,
    pub health_status: ChecksumHealthStatus,
    pub verification_queue_size: usize,
}

/// Checksum health status enum
#[derive(Debug, Clone, PartialEq)]
pub enum ChecksumHealthStatus {
    Healthy,
    Warning,
    Critical,
    Unknown,
}

impl Default for ChecksumHealthStatus {
    fn default() -> Self {
        ChecksumHealthStatus::Unknown
    }
}

impl ChecksumMonitor {
    /// Create a new checksum monitor
    pub fn new(adapter: Arc<RustDatabaseAdapter>, config: Option<ChecksumMonitorConfig>) -> Self {
        let config = config.unwrap_or_default();
        let stats = Arc::new(RwLock::new(ChecksumMonitorStats::default()));
        let is_running = Arc::new(RwLock::new(false));

        Self {
            adapter,
            config,
            stats,
            is_running,
        }
    }

    /// Start the checksum monitoring service
    pub fn start(&self) -> Result<(), String> {
        let is_running = self.is_running.clone();
        let adapter = self.adapter.clone();
        let config = self.config.clone();
        let stats = self.stats.clone();

        if *is_running.read().unwrap() {
            return Ok(());
        }

        *is_running.write().unwrap() = true;

        std::thread::spawn(move || {
            info!(
                "Checksum monitor started with interval: {:?}",
                config.verification_interval
            );

            while *is_running.read().unwrap() {
                let start_time = Instant::now();

                match Self::perform_full_verification_static(&adapter, &config, &stats) {
                    Ok(verified_count) => {
                        let elapsed = start_time.elapsed();

                        // Update statistics
                        let mut stats_write = stats.write().unwrap();
                        stats_write.total_verifications += verified_count;
                        stats_write.last_verification_time = start_time.elapsed().as_secs();

                        // Calculate current reliability
                        let total = stats_write.total_verifications + stats_write.total_failures;
                        if total > 0 {
                            let reliability =
                                100.0 - (stats_write.total_failures as f64 / total as f64) * 100.0;
                            stats_write.current_reliability = reliability;

                            // Update health status
                            stats_write.health_status =
                                Self::calculate_health_status(reliability, &config);
                        }

                        debug!(
                            "Checksum verification completed in {} ms",
                            elapsed.as_millis()
                        );
                    }
                    Err(e) => {
                        error!("Checksum verification failed: {}", e);

                        let mut stats_write = stats.write().unwrap();
                        stats_write.total_failures += 1;
                        stats_write.health_status = ChecksumHealthStatus::Critical;
                    }
                }

                // Wait for next interval
                std::thread::sleep(config.verification_interval);
            }

            info!("Checksum monitor stopped");
        });

        Ok(())
    }

    /// Stop the checksum monitoring service
    pub fn stop(&self) -> Result<(), String> {
        *self.is_running.write().unwrap() = false;
        Ok(())
    }

    /// Perform a full verification of all swapped chunks
    pub fn perform_full_verification(&self) -> Result<u64, String> {
        Self::perform_full_verification_static(&self.adapter, &self.config, &self.stats)
    }

    /// Static version of perform_full_verification for use in threads
    fn perform_full_verification_static(
        adapter: &Arc<RustDatabaseAdapter>,
        config: &ChecksumMonitorConfig,
        _stats: &Arc<RwLock<ChecksumMonitorStats>>,
    ) -> Result<u64, String> {
        let start_time = Instant::now();
        let mut verified_count = 0;
        let mut failed_count = 0;

        info!("Starting full checksum verification");

        // Scan swap directory for all .swap files
        let swap_dir = Path::new(&adapter.swap_path);
        if !swap_dir.exists() {
            info!("No swap directory found, skipping verification");
            return Ok(0);
        }

        let mut verification_tasks = Vec::with_capacity(config.max_concurrent_verifications);

        for entry in
            fs::read_dir(swap_dir).map_err(|e| format!("Failed to read swap directory: {}", e))?
        {
            let entry = entry.map_err(|e| format!("Failed to read swap directory entry: {}", e))?;
            let path = entry.path();

            if path.is_file() && path.extension().and_then(|s| s.to_str()) == Some("swap") {
                let file_name = path
                    .file_name()
                    .and_then(|s| s.to_str())
                    .unwrap_or("unknown");
                let key = file_name.replace("_", ":"); // Convert back from swap file naming
                let adapter_clone = adapter.clone();

                // Spawn verification task
                let handle =
                    std::thread::spawn(move || adapter_clone.verify_swap_chunk_checksum(&key));

                verification_tasks.push(handle);

                // Respect concurrency limit
                if verification_tasks.len() >= config.max_concurrent_verifications {
                    Self::process_verification_results_static(
                        &mut verification_tasks,
                        &mut verified_count,
                        &mut failed_count,
                    );
                }
            }
        }

        // Process any remaining tasks
        Self::process_verification_results_static(
            &mut verification_tasks,
            &mut verified_count,
            &mut failed_count,
        );

        // Update database statistics
        let mut db_stats = adapter.stats.write().unwrap();
        db_stats.checksum_verifications_total += verified_count;
        db_stats.checksum_failures_total += failed_count;

        // Calculate and update health score
        let total = verified_count + failed_count;
        if total > 0 {
            let reliability = 100.0 - (failed_count as f64 / total as f64) * 100.0;
            db_stats.checksum_health_score = reliability;

            // Update overall system health if checksum reliability is too low
            if reliability < config.reliability_threshold {
                db_stats.is_healthy = false;
                error!("Checksum reliability below threshold: {:.2}%", reliability);
            } else {
                db_stats.is_healthy = true;
            }
        }

        info!(
            "Full checksum verification completed: {} verified, {} failed in {} ms",
            verified_count,
            failed_count,
            start_time.elapsed().as_millis()
        );

        Ok(verified_count)
    }

    /// Process verification results from completed tasks
    #[allow(dead_code)]
    fn process_verification_results(
        &self,
        tasks: &mut Vec<std::thread::JoinHandle<Result<(), String>>>,
        verified_count: &mut u64,
        failed_count: &mut u64,
    ) {
        Self::process_verification_results_static(tasks, verified_count, failed_count);
    }

    /// Static version of process_verification_results for use in threads
    fn process_verification_results_static(
        tasks: &mut Vec<std::thread::JoinHandle<Result<(), String>>>,
        verified_count: &mut u64,
        failed_count: &mut u64,
    ) {
        for task in tasks.drain(..) {
            match task.join() {
                Ok(Ok(_)) => *verified_count += 1,
                Ok(Err(e)) => {
                    *failed_count += 1;
                    error!("Checksum verification failed: {}", e);
                }
                Err(e) => {
                    *failed_count += 1;
                    error!("Verification task panicked: {:?}", e);
                }
            }
        }
    }

    /// Calculate health status based on reliability score
    fn calculate_health_status(
        reliability: f64,
        config: &ChecksumMonitorConfig,
    ) -> ChecksumHealthStatus {
        if reliability >= config.reliability_threshold {
            ChecksumHealthStatus::Healthy
        } else if reliability >= 90.0 {
            ChecksumHealthStatus::Warning
        } else {
            ChecksumHealthStatus::Critical
        }
    }

    /// Get current monitor statistics
    pub fn get_stats(&self) -> Result<ChecksumMonitorStats, String> {
        Ok(self.stats.read().unwrap().clone())
    }

    /// Verify checksum for a specific chunk
    pub fn verify_chunk_checksum(&self, key: &str) -> Result<ChecksumVerificationResult, String> {
        if !self.adapter.checksum_enabled {
            return Ok(ChecksumVerificationResult::Skip(
                "Checksum verification is disabled".to_string(),
            ));
        }

        match self.adapter.verify_swap_chunk_checksum(key) {
            Ok(_) => Ok(ChecksumVerificationResult::Pass),
            Err(e) => Ok(ChecksumVerificationResult::Fail(e)),
        }
    }
}

impl ChecksumHealthStatus {
    /// Convert health status to string for display
    pub fn to_string(&self) -> &'static str {
        match self {
            ChecksumHealthStatus::Healthy => "HEALTHY",
            ChecksumHealthStatus::Warning => "WARNING",
            ChecksumHealthStatus::Critical => "CRITICAL",
            ChecksumHealthStatus::Unknown => "UNKNOWN",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_checksum_health_status() {
        assert_eq!(ChecksumHealthStatus::Healthy.to_string(), "HEALTHY");
        assert_eq!(ChecksumHealthStatus::Warning.to_string(), "WARNING");
        assert_eq!(ChecksumHealthStatus::Critical.to_string(), "CRITICAL");
        assert_eq!(ChecksumHealthStatus::Unknown.to_string(), "UNKNOWN");
    }

    #[test]
    fn test_calculate_health_status() {
        let config = ChecksumMonitorConfig::default();

        assert_eq!(
            ChecksumMonitor::calculate_health_status(98.0, &config),
            ChecksumHealthStatus::Healthy
        );
        assert_eq!(
            ChecksumMonitor::calculate_health_status(92.0, &config),
            ChecksumHealthStatus::Warning
        );
        assert_eq!(
            ChecksumMonitor::calculate_health_status(85.0, &config),
            ChecksumHealthStatus::Critical
        );
    }

    #[test]
    fn test_checksum_monitor_stats_default() {
        let stats = ChecksumMonitorStats::default();
        assert_eq!(stats.total_verifications, 0);
        assert_eq!(stats.total_failures, 0);
        assert_eq!(stats.current_reliability, 0.0);
        assert_eq!(stats.health_status, ChecksumHealthStatus::Unknown);
    }
}
