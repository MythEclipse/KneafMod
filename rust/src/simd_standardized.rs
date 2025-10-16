use std::sync::Arc;

/// Standard SIMD operations interface
pub trait StandardSimdOps: Send + Sync {
    fn vector_add(&self, a: &[f32], b: &[f32]) -> Vec<f32>;
    fn vector_mul(&self, a: &[f32], b: &[f32]) -> Vec<f32>;
    fn vector_dot(&self, a: &[f32], b: &[f32]) -> f32;
}

/// SIMD processor with standardized operations
pub struct StandardizedSimdProcessor {
    capabilities: crate::simd_enhanced::SimdCapability,
}

impl StandardizedSimdProcessor {
    pub fn new() -> Self {
        Self {
            capabilities: crate::simd_enhanced::detect_simd_capability(),
        }
    }
}

impl StandardSimdOps for StandardizedSimdProcessor {
    fn vector_add(&self, a: &[f32], b: &[f32]) -> Vec<f32> {
        if a.len() != b.len() {
            return Vec::new();
        }

        a.iter().zip(b.iter()).map(|(x, y)| x + y).collect()
    }

    fn vector_mul(&self, a: &[f32], b: &[f32]) -> Vec<f32> {
        if a.len() != b.len() {
            return Vec::new();
        }

        a.iter().zip(b.iter()).map(|(x, y)| x * y).collect()
    }

    fn vector_dot(&self, a: &[f32], b: &[f32]) -> f32 {
        if a.len() != b.len() {
            return 0.0;
        }

        a.iter().zip(b.iter()).map(|(x, y)| x * y).sum()
    }
}

/// Get standard SIMD operations instance
pub fn get_standard_simd_ops() -> Arc<dyn StandardSimdOps + Send + Sync> {
    Arc::new(StandardizedSimdProcessor::new())
}