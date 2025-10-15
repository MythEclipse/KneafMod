//! Comprehensive tests for the refactored binary and compression modules

use crate::binary::conversions::{BinaryConverterFactory, BinaryConversionError};
use crate::binary::zero_copy::{ZeroCopyConverterFactory, ZeroCopyConverter};
use crate::compression::{CompressionBuilder, CompressionEngine, CompressionError};
use crate::mob::types::{MobInput, MobProcessResult};

#[test]
fn test_binary_converter_factory() -> Result<(), BinaryConversionError> {
    // Test mob converter
    let mob_data = vec![
        12345u64.to_le_bytes().as_slice(),
        &[3i32.to_le_bytes().as_slice(), &[1u64.to_le_bytes().as_slice(), &[1.0f32.to_le_bytes().as_slice(), &[1u8], &[4i32], b"mob1".as_slice()]]].concat(),
    ].concat();
    
    let mob_converter = BinaryConverterFactory::mob_converter();
    let mob_input = mob_converter.deserialize(&mob_data)?;
    assert_eq!(mob_input.tick_count, 12345);
    assert_eq!(mob_input.mobs.len(), 3);
    
    let mob_result = MobProcessResult {
        mobs_to_disable_ai: vec![1, 2, 3],
        mobs_to_simplify_ai: vec![4, 5, 6],
    };
    
    let serialized = mob_converter.serialize(&mob_result)?;
    assert!(serialized.len() > 0);
    
    Ok(())
}

#[test]
fn test_zero_copy_converter_factory() -> Result<(), Box<dyn std::error::Error>> {
    let test_result = MobProcessResult {
        mobs_to_disable_ai: vec![1, 2, 3],
        mobs_to_simplify_ai: vec![4, 5, 6],
    };
    
    let converter = ZeroCopyConverterFactory::mob_converter();
    let required_size = converter.calculate_serialized_size(&test_result);
    
    let mut buffer = vec![0u8; required_size];
    let test_tick_count = 12345u64;
    let bytes_written = unsafe {
        converter.serialize(&test_result, test_tick_count, buffer.as_mut_ptr(), buffer.len())
    }?;
    
    assert_eq!(bytes_written, required_size);
    
    // Test deserialization
    let deserialized = unsafe {
        converter.deserialize(buffer.as_ptr(), buffer.len())
    }?;
    
    assert_eq!(deserialized.tick_count, test_tick_count);
    
    Ok(())
}

#[test]
fn test_compression_builder() -> Result<(), CompressionError> {
    // Test default builder
    let default_builder = CompressionBuilder::new();
    assert_eq!(default_builder.min_size_for_compression, 10_000);
    assert!(default_builder.checksum);
    
    // Test custom builder
    let custom_builder = CompressionBuilder::new()
        .min_size(5_000)
        .checksum(false)
        .build();
    
    assert_eq!(custom_builder.config.min_size_for_compression, 5_000);
    assert!(!custom_builder.config.checksum);
    
    // Test compression engine
    let mut engine = CompressionEngine::new();
    let original_data = vec![1, 2, 3, 4, 5];
    
    // Small data should not be compressed
    let compressed = engine.compress(&original_data)?;
    assert_eq!(compressed, original_data);
    
    let decompressed = engine.decompress(&compressed)?;
    assert_eq!(decompressed, original_data);
    
    Ok(())
}

#[test]
fn test_compression_stats() -> Result<(), CompressionError> {
    let mut engine = CompressionEngine::with_stats();
    let original_data = vec![1, 2, 3, 4, 5];
    
    // Compress and decompress
    let compressed = engine.compress(&original_data)?;
    let _ = engine.decompress(&compressed)?;
    
    // Check stats
    let stats = engine.stats().unwrap();
    assert_eq!(stats.original_size, 5);
    assert_eq!(stats.compressed_size, 5);
    assert_eq!(stats.total_uncompressed, 5);
    assert_eq!(stats.total_compressed, 5);
    assert!(stats.compression_ratio >= 1.0);
    assert!(stats.compression_time_ms > 0);
    
    Ok(())
}

#[test]
fn test_error_handling() {
    // Test BinaryConversionError
    let result: Result<(), BinaryConversionError> = Err(BinaryConversionError::InvalidData("Test error".to_string()));
    assert!(result.is_err());
    
    // Test CompressionError
    let compression_result: Result<(), CompressionError> = Err(CompressionError::InvalidData("Test error".to_string()));
    assert!(compression_result.is_err());
}