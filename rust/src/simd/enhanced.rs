use crate::simd::base::SimdLevel;
use std::sync::atomic::{AtomicU32, Ordering};

/// Enhanced SIMD capability detection and reporting
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SimdCapability {
    None,
    Basic,
    Advanced,
    Full,
    Avx512Extreme,
}

impl SimdCapability {
    /// Convert capability to SIMD level
    pub fn to_level(&self) -> SimdLevel {
        match self {
            SimdCapability::None => SimdLevel::Scalar,
            SimdCapability::Basic => SimdLevel::Sse41,
            SimdCapability::Advanced => SimdLevel::Avx2,
            SimdCapability::Full => SimdLevel::Avx512,
            SimdCapability::Avx512Extreme => SimdLevel::Avx512, // Fallback to highest supported level
        }
    }
}

/// Detect SIMD capabilities for the current system
pub fn detect_simd_capability() -> SimdCapability {
    #[cfg(target_arch = "x86_64")]
    {
        use std::arch::is_x86_feature_detected;
        
        if is_x86_feature_detected!("avx512f") && 
           is_x86_feature_detected!("avx512dq") && 
           is_x86_feature_detected!("avx512bw") && 
           is_x86_feature_detected!("avx512vl") {
            return SimdCapability::Full;
        } else if is_x86_feature_detected!("avx2") {
            return SimdCapability::Advanced;
        } else if is_x86_feature_detected!("sse4.1") {
            return SimdCapability::Basic;
        }
    }
    
    SimdCapability::None
}

lazy_static::lazy_static! {
    static ref GLOBAL_SIMD_CAPABILITY: AtomicU32 = AtomicU32::new(SimdCapability::None as u32);
}

/// Set global SIMD capability
pub fn set_simd_capability(capability: SimdCapability) {
    GLOBAL_SIMD_CAPABILITY.store(capability as u32, Ordering::Relaxed);
}

/// Get global SIMD capability
pub fn get_simd_capability() -> SimdCapability {
    match GLOBAL_SIMD_CAPABILITY.load(Ordering::Relaxed) {
        0 => SimdCapability::None,
        1 => SimdCapability::Basic,
        2 => SimdCapability::Advanced,
        3 => SimdCapability::Full,
        _ => SimdCapability::None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_simd_capability_conversion() {
        let none = SimdCapability::None;
        let basic = SimdCapability::Basic;
        let advanced = SimdCapability::Advanced;
        let full = SimdCapability::Full;

        assert_eq!(none.to_level(), SimdLevel::Scalar);
        assert_eq!(basic.to_level(), SimdLevel::Sse41);
        assert_eq!(advanced.to_level(), SimdLevel::Avx2);
        assert_eq!(full.to_level(), SimdLevel::Avx512);
    }

    #[test]
    fn test_global_simd_capability() {
        set_simd_capability(SimdCapability::Advanced);
        assert_eq!(get_simd_capability(), SimdCapability::Advanced);
        
        set_simd_capability(SimdCapability::Full);
        assert_eq!(get_simd_capability(), SimdCapability::Full);
        
        set_simd_capability(SimdCapability::None);
        assert_eq!(get_simd_capability(), SimdCapability::None);
    }
}