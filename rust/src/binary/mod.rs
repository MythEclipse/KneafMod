//! Binary conversion and zero-copy operations module

pub mod conversions;
pub mod zero_copy;

// Re-export key types and traits for easier access
pub use conversions::{BinaryConversionError, BinaryConverter, BinaryConverterFactory};
pub use zero_copy::{ZeroCopyConverter, ZeroCopyConverterFactory};
