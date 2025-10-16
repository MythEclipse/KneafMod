use std::sync::atomic::{AtomicU8, Ordering};

/// Memory pressure levels
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MemoryPressureLevel {
    Low = 0,
    Medium = 1,
    High = 2,
    Critical = 3,
}

impl Default for MemoryPressureLevel {
    fn default() -> Self {
        MemoryPressureLevel::Low
    }
}

/// Global memory pressure configuration
pub struct MemoryPressureConfig {
    current_level: AtomicU8,
    thresholds: MemoryPressureThresholds,
}

#[derive(Clone)]
pub struct MemoryPressureThresholds {
    pub low_threshold: f64,
    pub medium_threshold: f64,
    pub high_threshold: f64,
}

impl Default for MemoryPressureThresholds {
    fn default() -> Self {
        Self {
            low_threshold: 0.5,
            medium_threshold: 0.7,
            high_threshold: 0.9,
        }
    }
}

impl MemoryPressureConfig {
    pub fn new() -> Self {
        Self {
            current_level: AtomicU8::new(MemoryPressureLevel::Low as u8),
            thresholds: MemoryPressureThresholds::default(),
        }
    }

    pub fn get_current_level(&self) -> MemoryPressureLevel {
        match self.current_level.load(Ordering::Relaxed) {
            0 => MemoryPressureLevel::Low,
            1 => MemoryPressureLevel::Medium,
            2 => MemoryPressureLevel::High,
            3 => MemoryPressureLevel::Critical,
            _ => MemoryPressureLevel::Low,
        }
    }

    pub fn set_level(&self, level: MemoryPressureLevel) {
        self.current_level.store(level as u8, Ordering::Relaxed);
    }
}

lazy_static::lazy_static! {
    pub static ref GLOBAL_MEMORY_PRESSURE_CONFIG: MemoryPressureConfig = MemoryPressureConfig::new();
}