use super::base::{SimdF32, SimdI32, SimdU64, SimdOps};

/// Vector operations trait
pub trait VectorOps<T> {
    /// Add two vectors
    fn add(&self, other: &T) -> T;
    
    /// Subtract two vectors
    fn sub(&self, other: &T) -> T;
    
    /// Multiply two vectors
    fn mul(&self, other: &T) -> T;
    
    /// Divide two vectors
    fn div(&self, other: &T) -> T;
    
    /// Dot product
    fn dot(&self, other: &T) -> f32;
    
    /// Magnitude (length) of vector
    fn magnitude(&self) -> f32;
    
    /// Normalize vector
    fn normalize(&self) -> T;
    
    /// Check if vector is zero
    fn is_zero(&self) -> bool;
}

/// Implement VectorOps for SimdF32
impl VectorOps<SimdF32> for SimdF32 {
    fn add(&self, other: &SimdF32) -> SimdF32 {
        let mut result = [0.0f32; 8];
        for i in 0..8 {
            result[i] = self.0[i] + other.0[i];
        }
        SimdF32(result)
    }
    
    fn sub(&self, other: &SimdF32) -> SimdF32 {
        let mut result = [0.0f32; 8];
        for i in 0..8 {
            result[i] = self.0[i] - other.0[i];
        }
        SimdF32(result)
    }
    
    fn mul(&self, other: &SimdF32) -> SimdF32 {
        let mut result = [0.0f32; 8];
        for i in 0..8 {
            result[i] = self.0[i] * other.0[i];
        }
        SimdF32(result)
    }
    
    fn div(&self, other: &SimdF32) -> SimdF32 {
        let mut result = [0.0f32; 8];
        for i in 0..8 {
            result[i] = self.0[i] / other.0[i];
        }
        SimdF32(result)
    }
    
    fn dot(&self, other: &SimdF32) -> f32 {
        let mut sum = 0.0f32;
        for i in 0..8 {
            sum += self.0[i] * other.0[i];
        }
        sum
    }
    
    fn magnitude(&self) -> f32 {
        let mut sum = 0.0f32;
        for i in 0..8 {
            sum += self.0[i] * self.0[i];
        }
        sum.sqrt()
    }
    
    fn normalize(&self) -> SimdF32 {
        let mag = self.magnitude();
        if mag == 0.0 {
            return SimdF32([0.0f32; 8]);
        }
        
        let mut result = [0.0f32; 8];
        for i in 0..8 {
            result[i] = self.0[i] / mag;
        }
        SimdF32(result)
    }
    
    fn is_zero(&self) -> bool {
        self.0.iter().all(|&x| x == 0.0)
    }
}

/// Implement VectorOps for SimdI32
impl VectorOps<SimdI32> for SimdI32 {
    fn add(&self, other: &SimdI32) -> SimdI32 {
        let mut result = [0i32; 8];
        for i in 0..8 {
            result[i] = self.0[i] + other.0[i];
        }
        SimdI32(result)
    }
    
    fn sub(&self, other: &SimdI32) -> SimdI32 {
        let mut result = [0i32; 8];
        for i in 0..8 {
            result[i] = self.0[i] - other.0[i];
        }
        SimdI32(result)
    }
    
    fn mul(&self, other: &SimdI32) -> SimdI32 {
        let mut result = [0i32; 8];
        for i in 0..8 {
            result[i] = self.0[i] * other.0[i];
        }
        SimdI32(result)
    }
    
    fn div(&self, other: &SimdI32) -> SimdI32 {
        let mut result = [0i32; 8];
        for i in 0..8 {
            result[i] = self.0[i] / other.0[i];
        }
        SimdI32(result)
    }
    
    fn dot(&self, other: &SimdI32) -> f32 {
        let mut sum = 0i32;
        for i in 0..8 {
            sum += self.0[i] * other.0[i];
        }
        sum as f32
    }
    
    fn magnitude(&self) -> f32 {
        let mut sum = 0i32;
        for i in 0..8 {
            sum += self.0[i] * self.0[i];
        }
        (sum as f32).sqrt()
    }
    
    fn normalize(&self) -> SimdI32 {
        let mag = self.magnitude();
        if mag == 0.0 {
            return SimdI32([0i32; 8]);
        }
        
        let mut result = [0i32; 8];
        for i in 0..8 {
            result[i] = (self.0[i] as f32 / mag) as i32;
        }
        SimdI32(result)
    }
    
    fn is_zero(&self) -> bool {
        self.0.iter().all(|&x| x == 0)
    }
}

/// Implement VectorOps for SimdU64
impl VectorOps<SimdU64> for SimdU64 {
    fn add(&self, other: &SimdU64) -> SimdU64 {
        let mut result = [0u64; 4];
        for i in 0..4 {
            result[i] = self.0[i] + other.0[i];
        }
        SimdU64(result)
    }
    
    fn sub(&self, other: &SimdU64) -> SimdU64 {
        let mut result = [0u64; 4];
        for i in 0..4 {
            result[i] = self.0[i] - other.0[i];
        }
        SimdU64(result)
    }
    
    fn mul(&self, other: &SimdU64) -> SimdU64 {
        let mut result = [0u64; 4];
        for i in 0..4 {
            result[i] = self.0[i] * other.0[i];
        }
        SimdU64(result)
    }
    
    fn div(&self, other: &SimdU64) -> SimdU64 {
        let mut result = [0u64; 4];
        for i in 0..4 {
            result[i] = self.0[i] / other.0[i];
        }
        SimdU64(result)
    }
    
    fn dot(&self, other: &SimdU64) -> f32 {
        let mut sum = 0u64;
        for i in 0..4 {
            sum += self.0[i] * other.0[i];
        }
        sum as f32
    }
    
    fn magnitude(&self) -> f32 {
        let mut sum = 0u64;
        for i in 0..4 {
            sum += self.0[i] * self.0[i];
        }
        (sum as f32).sqrt()
    }
    
    fn normalize(&self) -> SimdU64 {
        let mag = self.magnitude();
        if mag == 0.0 {
            return SimdU64([0u64; 4]);
        }
        
        let mut result = [0u64; 4];
        for i in 0..4 {
            result[i] = (self.0[i] as f32 / mag) as u64;
        }
        SimdU64(result)
    }
    
    fn is_zero(&self) -> bool {
        self.0.iter().all(|&x| x == 0)
    }
}

/// Cross-type vector operations
pub fn simd_f32_from_array(arr: [f32; 8]) -> SimdF32 {
    SimdF32(arr)
}

pub fn simd_i32_from_array(arr: [i32; 8]) -> SimdI32 {
    SimdI32(arr)
}

pub fn simd_u64_from_array(arr: [u64; 4]) -> SimdU64 {
    SimdU64(arr)
}

/// Vector math utilities
pub fn lerp(a: &SimdF32, b: &SimdF32, t: f32) -> SimdF32 {
    let mut result = [0.0f32; 8];
    for i in 0..8 {
        result[i] = a.0[i] + (b.0[i] - a.0[i]) * t;
    }
    SimdF32(result)
}

pub fn clamp(vec: &SimdF32, min: f32, max: f32) -> SimdF32 {
    let mut result = [0.0f32; 8];
    for i in 0..8 {
        result[i] = vec.0[i].clamp(min, max);
    }
    SimdF32(result)
}

pub fn distance(a: &SimdF32, b: &SimdF32) -> f32 {
    let diff = a.sub(b);
    diff.magnitude()
}

/// Global vector operations instance
pub struct GlobalVectorOps;

impl GlobalVectorOps {
    pub fn new() -> Self {
        Self
    }
    
    pub fn process_f32_vectors(&self, a: SimdF32, b: SimdF32, operation: VectorOperation) -> SimdF32 {
        match operation {
            VectorOperation::Add => a.add(&b),
            VectorOperation::Sub => a.sub(&b),
            VectorOperation::Mul => a.mul(&b),
            VectorOperation::Div => a.div(&b),
        }
    }
    
    pub fn process_i32_vectors(&self, a: SimdI32, b: SimdI32, operation: VectorOperation) -> SimdI32 {
        match operation {
            VectorOperation::Add => a.add(&b),
            VectorOperation::Sub => a.sub(&b),
            VectorOperation::Mul => a.mul(&b),
            VectorOperation::Div => a.div(&b),
        }
    }
    
    pub fn process_u64_vectors(&self, a: SimdU64, b: SimdU64, operation: VectorOperation) -> SimdU64 {
        match operation {
            VectorOperation::Add => a.add(&b),
            VectorOperation::Sub => a.sub(&b),
            VectorOperation::Mul => a.mul(&b),
            VectorOperation::Div => a.div(&b),
        }
    }
}

/// Vector operation types
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum VectorOperation {
    Add,
    Sub,
    Mul,
    Div,
}

/// Global instance
static GLOBAL_VECTOR_OPS: std::sync::OnceLock<GlobalVectorOps> = std::sync::OnceLock::new();

/// Get global vector operations instance
pub fn get_global_vector_ops() -> &'static GlobalVectorOps {
    GLOBAL_VECTOR_OPS.get_or_init(|| GlobalVectorOps::new())
}