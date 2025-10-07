use std::io::{Read, Write};
use lz4_flex::frame::{compress, decompress, BlockSize, CompressionLevel};
use lz4_flex::block::{compress_prepend_size, decompress_size_prefixed};

/// Adaptive compression configuration
#[derive(Debug, Clone)]
pub struct CompressionConfig {
    pub min_size_for_compression: usize,
    pub compression_level: CompressionLevel,
    pub block_size: BlockSize,
    pub checksum: bool,
}

impl Default for CompressionConfig {
    fn default() -> Self {
        Self {
            min_size_for_compression: 10_000, // 10KB - compress chunks larger than this
            compression_level: CompressionLevel::Default,
            block_size: BlockSize::Max,
            checksum: true,
        }
    }
}

/// Compression utilities for chunk storage
pub struct ChunkCompressor {
    config: CompressionConfig,
}

impl ChunkCompressor {
    /// Create a new chunk compressor with default configuration
    pub fn new() -> Self {
        Self::with_config(CompressionConfig::default())
    }

    /// Create a new chunk compressor with custom configuration
    pub fn with_config(config: CompressionConfig) -> Self {
        Self { config }
    }

    /// Compress chunk data if it meets the size threshold
    pub fn compress(&self, data: &[u8]) -> Result<Vec<u8>, String> {
        if data.len() < self.config.min_size_for_compression {
            return Ok(data.to_vec()); // Return uncompressed if below threshold
        }

        let compressed = compress(data, self.config.compression_level)
            .map_err(|e| format!("LZ4 compression failed: {}", e))?;

        Ok(compressed)
    }

    /// Decompress chunk data (automatically detects if compressed)
    pub fn decompress(&self, data: &[u8]) -> Result<Vec<u8>, String> {
        decompress(data)
            .map_err(|e| format!("LZ4 decompression failed: {}", e))
    }

    /// Check if data is likely compressed (simple heuristic)
    pub fn is_compressed(&self, data: &[u8]) -> bool {
        // Simple heuristic: check for LZ4 frame magic number (0x04 0x22 0x4D 0x18)
        data.len() >= 4 && 
        data[0] == 0x04 && 
        data[1] == 0x22 && 
        data[2] == 0x4D && 
        data[3] == 0x18
    }

    /// Get compression ratio (original_size / compressed_size)
    pub fn calculate_compression_ratio(&self, original_size: usize, compressed_size: usize) -> f64 {
        if compressed_size == 0 {
            return 1.0;
        }
        (original_size as f64 / compressed_size as f64).max(1.0)
    }
}

/// Block compression utilities for smaller chunks
pub struct BlockCompressor {
    config: CompressionConfig,
}

impl BlockCompressor {
    /// Create a new block compressor with default configuration
    pub fn new() -> Self {
        Self::with_config(CompressionConfig::default())
    }

    /// Create a new block compressor with custom configuration
    pub fn with_config(config: CompressionConfig) -> Self {
        Self { config }
    }

    /// Compress data using LZ4 block compression with size prefix
    pub fn compress(&self, data: &[u8]) -> Result<Vec<u8>, String> {
        compress_prepend_size(data)
            .map_err(|e| format!("LZ4 block compression failed: {}", e))
    }

    /// Decompress data compressed with LZ4 block compression
    pub fn decompress(&self, data: &[u8]) -> Result<Vec<u8>, String> {
        decompress_size_prefixed(data)
            .map_err(|e| format!("LZ4 block decompression failed: {}", e))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_compression_roundtrip() {
        let compressor = ChunkCompressor::new();
        let original_data = vec![1, 2, 3, 4, 5];
        
        // Test with small data (should not compress)
        let compressed = compressor.compress(&original_data).unwrap();
        assert_eq!(compressed, original_data);
        
        let decompressed = compressor.decompress(&compressed).unwrap();
        assert_eq!(decompressed, original_data);
        
        // Test with larger data
        let large_data: Vec<u8> = (0..20_000).map(|i| i as u8).collect();
        let compressed = compressor.compress(&large_data).unwrap();
        assert!(compressed.len() < large_data.len());
        
        let decompressed = compressor.decompress(&compressed).unwrap();
        assert_eq!(decompressed, large_data);
    }

    #[test]
    fn test_block_compression_roundtrip() {
        let compressor = BlockCompressor::new();
        let data = vec![1, 2, 3, 4, 5];
        
        let compressed = compressor.compress(&data).unwrap();
        assert!(compressed.len() > data.len()); // Should always add size prefix
        
        let decompressed = compressor.decompress(&compressed).unwrap();
        assert_eq!(decompressed, data);
    }

    #[test]
    fn test_is_compressed_detection() {
        let compressor = ChunkCompressor::new();
        let original_data = vec![1, 2, 3, 4, 5];
        let compressed_data = compressor.compress(&vec![0; 20_000]).unwrap();
        
        assert!(!compressor.is_compressed(&original_data));
        assert!(compressor.is_compressed(&compressed_data));
    }
}