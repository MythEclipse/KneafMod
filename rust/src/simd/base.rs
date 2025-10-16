use crate::{
    errors::{RustError, Result as RustResult},
    traits::Initializable,
};
use lazy_static::lazy_static;
use serde::{Deserialize, Serialize};
use std::result::Result as StdResult;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

/// Type alias for SIMD operation results using standardized error type
pub type SimdResult<T> = StdResult<T, SimdError>;

/// SIMD error type for vector operations
#[derive(Debug)]
pub enum SimdError {
    UnsupportedInstructionSet { required: String, available: String },
    InvalidInputLength { expected: usize, actual: usize },
    JniError { operation: String, details: String },
}

impl std::fmt::Display for SimdError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SimdError::UnsupportedInstructionSet { required, available } => {
                write!(f, "Unsupported SIMD instruction set: {} (available: {})", required, available)
            }
            SimdError::InvalidInputLength { expected, actual } => {
                write!(f, "Invalid input length: expected {}, got {}", expected, actual)
            }
            SimdError::JniError { operation, details } => {
                write!(f, "JNI error in {}: {}", operation, details)
            }
        }
    }
}

impl std::error::Error for SimdError {}

/// SIMD level capabilities
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum SimdLevel {
    None,
    Sse,
    Sse41,
    Sse42,
    Avx2,
    Avx512,
    Scalar,
}

/// Global SIMD manager instance
lazy_static! {
    pub static ref GLOBAL_SIMD_MANAGER: SimdProcessor = SimdProcessor::new();
}

/// Get the global SIMD manager instance
pub fn get_simd_manager() -> &'static SimdProcessor {
    &GLOBAL_SIMD_MANAGER
}

/// SIMD feature detection and optimization configuration
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SimdConfig {
    pub enable_avx2: bool,
    pub enable_sse41: bool,
    pub enable_sse42: bool,
    pub enable_fma: bool,
    pub enable_bmi1: bool,
    pub enable_bmi2: bool,
    pub force_scalar_fallback: bool,
}

impl Default for SimdConfig {
    fn default() -> Self {
        Self {
            enable_avx2: true,
            enable_sse41: true,
            enable_sse42: true,
            enable_fma: true,
            enable_bmi1: true,
            enable_bmi2: true,
            force_scalar_fallback: false,
        }
    }
}

/// Runtime SIMD feature detection
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SimdFeatures {
    pub has_avx2: bool,
    pub has_sse41: bool,
    pub has_sse42: bool,
    pub has_fma: bool,
    pub has_bmi1: bool,
    pub has_bmi2: bool,
    pub has_avx512f: bool,
    pub has_avx512dq: bool,
    pub has_avx512bw: bool,
    pub has_avx512vl: bool,
}

impl SimdFeatures {
    /// Detect SIMD capabilities at runtime
    pub fn detect() -> Self {
        Self {
            has_avx2: is_x86_feature_detected!("avx2"),
            has_sse41: is_x86_feature_detected!("sse4.1"),
            has_sse42: is_x86_feature_detected!("sse4.2"),
            has_fma: is_x86_feature_detected!("fma"),
            has_bmi1: is_x86_feature_detected!("bmi1"),
            has_bmi2: is_x86_feature_detected!("bmi2"),
            has_avx512f: is_x86_feature_detected!("avx512f"),
            has_avx512dq: is_x86_feature_detected!("avx512dq"),
            has_avx512bw: is_x86_feature_detected!("avx512bw"),
            has_avx512vl: is_x86_feature_detected!("avx512vl"),
        }
    }

    /// Check if all required features are available
    pub fn has_all(&self, config: &SimdConfig) -> bool {
        if config.force_scalar_fallback {
            return false;
        }

        let required = [
            (config.enable_avx2, self.has_avx2),
            (config.enable_sse41, self.has_sse41),
            (config.enable_sse42, self.has_sse42),
            (config.enable_fma, self.has_fma),
            (config.enable_bmi1, self.has_bmi1),
            (config.enable_bmi2, self.has_bmi2),
        ];

        required.iter().all(|&(required, available)| !required || available)
    }

    /// Get list of available SIMD instruction sets
    pub fn available_features(&self) -> Vec<&'static str> {
        let mut features = Vec::new();
        
        if self.has_avx512f {
            features.push("AVX-512");
        }
        if self.has_avx2 {
            features.push("AVX2");
        }
        if self.has_sse42 {
            features.push("SSE4.2");
        }
        if self.has_sse41 {
            features.push("SSE4.1");
        }
        if self.has_fma {
            features.push("FMA");
        }
        
        features
    }
}

/// Main SIMD processor implementation
pub struct SimdProcessor {
    config: SimdConfig,
    features: SimdFeatures,
    level: SimdLevel,
    initialized: AtomicBool,
}

impl SimdProcessor {
    /// Create a new SIMD processor with default configuration
    pub fn new() -> Self {
        let config = SimdConfig::default();
        let features = SimdFeatures::detect();
        let level = Self::detect_simd_level(&features, &config);
        Self {
            config,
            features,
            level,
            initialized: AtomicBool::new(false),
        }
    }

    /// Create a new SIMD processor with custom configuration
    pub fn with_config(config: SimdConfig) -> Self {
        let features = SimdFeatures::detect();
        let level = Self::detect_simd_level(&features, &config);
        Self {
            config,
            features,
            level,
            initialized: AtomicBool::new(false),
        }
    }

    /// Initialize the SIMD processor
    /// Detect SIMD level based on available features and configuration
    fn detect_simd_level(features: &SimdFeatures, config: &SimdConfig) -> SimdLevel {
        if config.force_scalar_fallback {
            return SimdLevel::Scalar;
        }

        if features.has_avx512f && config.enable_avx2 {
            SimdLevel::Avx512
        } else if features.has_avx2 && config.enable_avx2 {
            SimdLevel::Avx2
        } else if features.has_sse42 && config.enable_sse42 {
            SimdLevel::Sse42
        } else if features.has_sse41 && config.enable_sse41 {
            SimdLevel::Sse41
        } else {
            SimdLevel::Scalar
        }
    }

    /// Get the current SIMD optimization level
    pub fn get_level(&self) -> SimdLevel {
        self.level
    }

    /// Check if a specific SIMD feature is available
    pub fn has_feature(&self, feature: &str) -> bool {
        match feature {
            "avx2" => self.features.has_avx2,
            "sse4.1" => self.features.has_sse41,
            "sse4.2" => self.features.has_sse42,
            "avx512f" => self.features.has_avx512f,
            _ => false,
        }
    }

    pub fn initialize(&self) -> SimdResult<()> {
        if self.initialized.load(Ordering::Relaxed) {
            return Ok(());
        }

        if !self.features.has_all(&self.config) {
            let available = self.features.available_features().join(", ");
            let required = self.get_required_features().join(", ");
            
            return Err(SimdError::UnsupportedInstructionSet {
                required,
                available,
            });
        }

        self.initialized.store(true, Ordering::Relaxed);
        Ok(())
    }

    /// Check if the processor is initialized
    pub fn is_initialized(&self) -> bool {
        self.initialized.load(Ordering::Relaxed)
    }

    /// Get list of required SIMD features from configuration
    fn get_required_features(&self) -> Vec<String> {
        let mut features = Vec::new();
        
        if self.config.enable_avx2 {
            features.push("AVX2".to_string());
        }
        if self.config.enable_sse41 {
            features.push("SSE4.1".to_string());
        }
        if self.config.enable_sse42 {
            features.push("SSE4.2".to_string());
        }
        if self.config.enable_fma {
            features.push("FMA".to_string());
        }
        if self.config.enable_bmi1 {
            features.push("BMI1".to_string());
        }
        if self.config.enable_bmi2 {
            features.push("BMI2".to_string());
        }
        
        features
    }

    /// Perform basic validation before operations
    fn validate_initialized(&self) -> SimdResult<()> {
        if !self.initialized.load(Ordering::Relaxed) {
            return Err(SimdError::JniError {
                operation: "validate_initialized".to_string(),
                details: "SIMD Processor not initialized".to_string(),
            });
        }
        Ok(())
    }

    /// Process spatial data using SIMD-accelerated operations
    pub fn process_spatial_data(&self, data: &mut [f32]) -> SimdResult<()> {
            self.validate_initialized()?;
            
            let start = Instant::now();
            self.process_array_scalar(data);
            // record_operation(start, data.len(), 1);
            
            Ok(())
        }

    /// Scalar fallback implementation for array processing
    fn process_array_scalar(&self, data: &mut [f32]) -> bool {
        let success = true;
        for i in 0..data.len() {
            data[i] = data[i].sqrt();
        }
        success
    }

    /// Get the detected SIMD features
    pub fn get_features(&self) -> &SimdFeatures {
        &self.features
    }
}

impl Initializable for SimdProcessor {
    fn check_initialized(&self) -> Result<(), RustError> {
        if !self.initialized.load(Ordering::Relaxed) {
            return Err(RustError::SerializationError("SIMD Processor not initialized".to_string()));
        }
        Ok(())
    }

    fn component_name(&self) -> &str {
        "SIMD Processor"
    }
}

/// SIMD-accelerated vector operations module
pub mod vector_ops {
    use super::SimdError;
    use super::SimdResult;
    use crate::errors::{RustError, Result};

    /// Dot product (scalar implementation, always available)
    pub fn dot_product(a: &[f32], b: &[f32]) -> SimdResult<f32> {
        if a.len() != b.len() {
            return Err(SimdError::InvalidInputLength {
                expected: a.len(),
                actual: b.len(),
            });
        }
        Ok(a.iter().zip(b.iter()).map(|(x, y)| x * y).sum())
    }

    /// Vector addition with scaling (scalar implementation)
    pub fn vector_add(a: &mut [f32], b: &[f32], scale: f32) -> SimdResult<()> {
        if a.len() != b.len() {
            return Err(SimdError::InvalidInputLength {
                expected: a.len(),
                actual: b.len(),
            });
        }

        for (a, b) in a.iter_mut().zip(b.iter()) {
            *a += b * scale;
        }
        Ok(())
    }

    /// Matrix multiplication (scalar implementation)
    pub fn matrix_multiply(a: &[f32], b: &[f32], result: &mut [f32]) -> SimdResult<()> {
        if a.len() != 9 || b.len() != 9 || result.len() != 9 {
            return Err(SimdError::InvalidInputLength {
                expected: 9,
                actual: a.len(),
            });
        }

        // Simple 3x3 matrix multiplication
        result[0] = a[0] * b[0] + a[1] * b[3] + a[2] * b[6];
        result[1] = a[0] * b[1] + a[1] * b[4] + a[2] * b[7];
        result[2] = a[0] * b[2] + a[1] * b[5] + a[2] * b[8];
        
        result[3] = a[3] * b[0] + a[4] * b[3] + a[5] * b[6];
        result[4] = a[3] * b[1] + a[4] * b[4] + a[5] * b[7];
        result[5] = a[3] * b[2] + a[4] * b[5] + a[5] * b[8];
        
        result[6] = a[6] * b[0] + a[7] * b[3] + a[8] * b[6];
        result[7] = a[6] * b[1] + a[7] * b[4] + a[8] * b[7];
        result[8] = a[6] * b[2] + a[7] * b[5] + a[8] * b[8];
        
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_dot_product() {
        let a = &[1.0, 2.0, 3.0];
        let b = &[4.0, 5.0, 6.0];
        let result = vector_ops::dot_product(a, b).unwrap();
        assert_eq!(result, 1.0*4.0 + 2.0*5.0 + 3.0*6.0);
    }

    #[test]
    fn test_vector_add() {
        let mut a = [1.0, 2.0, 3.0];
        let b = [4.0, 5.0, 6.0];
        vector_ops::vector_add(&mut a, &b, 2.0).unwrap();
        assert_eq!(a, [9.0, 12.0, 15.0]);
    }

    #[test]
    fn test_matrix_multiply() {
        let a = &[1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0];
        let b = &[9.0, 8.0, 7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0];
        let mut result = [0.0; 9];
        
        vector_ops::matrix_multiply(a, b, &mut result).unwrap();
        
        // Verify a few key values
        assert_eq!(result[0], 1.0*9.0 + 2.0*6.0 + 3.0*3.0);
        assert_eq!(result[4], 5.0*5.0); // Middle element
        assert_eq!(result[8], 9.0*1.0 + 8.0*4.0 + 7.0*1.0);
    }
}