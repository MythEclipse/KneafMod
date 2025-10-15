use crate::errors::{Result, RustError};
use crate::types::BinaryConverterConfig;

#[derive(Debug, Clone)]
pub struct BinaryConverterFactory;

impl BinaryConverterFactory {
    pub fn new() -> Self {
        BinaryConverterFactory
    }

    pub fn create_converter(&self, config: BinaryConverterConfig) -> Result<Box<dyn BinaryConverter>> {
        Ok(Box::new(BasicBinaryConverter::new(config)))
    }
}

#[derive(Debug, Clone)]
pub struct BasicBinaryConverter {
    config: BinaryConverterConfig,
}

impl BasicBinaryConverter {
    pub fn new(config: BinaryConverterConfig) -> Self {
        BasicBinaryConverter { config }
    }
}

pub trait BinaryConverter {
    fn convert(&self, input: &[u8]) -> Result<Vec<u8>>;
}

impl BinaryConverter for BasicBinaryConverter {
    fn convert(&self, input: &[u8]) -> Result<Vec<u8>> {
        Ok(input.to_vec()) // Simple identity conversion for now
    }
}

pub use crate::types::BinaryConverterConfig;