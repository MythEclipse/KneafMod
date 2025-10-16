use lz4_flex::block::{compress_prepend_size, decompress_size_prepended, CompressError, DecompressError};
use std::time::Instant;
use thiserror::Error;

/// Compression error type
#[derive(Debug, Error)]
pub enum CompressionError {
    #[error("LZ4 compression failed: {0}")]
    Lz4(#[from] CompressError),
    
    #[error("LZ4 decompression failed: {0}")]
    Lz4Decompress(#[from] DecompressError),
    
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
    
    #[error("Invalid data: {0}")]
    InvalidData(String),
    
    #[error("Buffer too small: {0}")]
    BufferTooSmall(String),
}

/// Compression algorithm types
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CompressionAlgorithm {
    /// LZ4 block compression (default)
    Lz4,
    /// No compression
    None,
}

/// Compression level for LZ4
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CompressionLevel {
    /// Fastest compression (lowest ratio)
    Fastest,
    /// Fast compression
    Fast,
    /// Default compression
    Default,
    /// High compression (best ratio)
    High,
    /// Ultra compression (highest ratio, slowest)
    Ultra,
}

/// Compression statistics
#[derive(Debug, Clone, Default)]
pub struct CompressionStats {
    pub original_size: usize,
    pub compressed_size: usize,
    pub total_uncompressed: usize,
    pub total_compressed: usize,
    pub compression_ratio: f64,
    pub compression_time: std::time::Duration,
    pub decompression_time: std::time::Duration,
}

/// Builder for creating compression configurations
#[derive(Debug, Clone)]
pub struct CompressionBuilder {
    min_size_for_compression: usize,
    checksum: bool,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
    stats: bool,
}

impl Default for CompressionBuilder {
    fn default() -> Self {
        Self {
            min_size_for_compression: 10_000, // 10KB - compress chunks larger than this
            checksum: true,
            algorithm: CompressionAlgorithm::Lz4,
            level: CompressionLevel::Default,
            stats: false,
        }
    }
}

impl CompressionBuilder {
    /// Create a new compression builder with default settings
    pub fn new() -> Self {
        Self::default()
    }
    
    /// Set minimum size threshold for compression
    pub fn min_size(mut self, size: usize) -> Self {
        self.min_size_for_compression = size;
        self
    }
    
    /// Enable/disable checksum verification
    pub fn checksum(mut self, enabled: bool) -> Self {
        self.checksum = enabled;
        self
    }
    
    /// Set compression algorithm
    pub fn algorithm(mut self, algorithm: CompressionAlgorithm) -> Self {
        self.algorithm = algorithm;
        self
    }
    
    /// Set compression level
    pub fn level(mut self, level: CompressionLevel) -> Self {
        self.level = level;
        self
    }
    
    /// Enable/disable statistics collection
    pub fn stats(mut self, enabled: bool) -> Self {
        self.stats = enabled;
        self
    }
    
    /// Build a compression engine with the configured settings
    pub fn build(self) -> CompressionEngine {
        CompressionEngine {
            config: CompressionConfig {
                min_size_for_compression: self.min_size_for_compression,
                checksum: self.checksum,
                algorithm: self.algorithm,
                level: self.level,
            },
            stats: if self.stats { Some(CompressionStats::default()) } else { None },
        }
    }
    
    /// Build a compression engine with statistics collection enabled
    pub fn build_with_stats(self) -> CompressionEngine {
        self.stats(true).build()
    }
}

/// Compression configuration
#[derive(Debug, Clone)]
pub struct CompressionConfig {
    min_size_for_compression: usize,
    checksum: bool,
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
}

/// Main compression engine
pub struct CompressionEngine {
    config: CompressionConfig,
    stats: Option<CompressionStats>,
}

impl CompressionEngine {
    /// Create a new compression engine with default configuration
    pub fn new() -> Self {
        CompressionBuilder::new().build()
    }
    
    /// Create a new compression engine with statistics collection
    pub fn with_stats() -> Self {
        CompressionBuilder::new().build_with_stats()
    }
    
    /// Compress data according to the configured settings
    pub fn compress(&mut self, data: &[u8]) -> Result<Vec<u8>, CompressionError> {
        let start_time = Instant::now();
        
        // Skip compression for small data
        if data.len() < self.config.min_size_for_compression {
            self.update_stats(data.len(), data.len(), start_time);
            return Ok(data.to_vec());
        }
        
        let result = match self.config.algorithm {
            CompressionAlgorithm::Lz4 => {
                // lz4_flex block API used here with default compression parameters.
                // The crate's block API in this workspace exposes `compress_prepend_size` which
                // takes only the input slice and returns the compressed bytes.
                compress_prepend_size(data)
            }
            CompressionAlgorithm::None => data.to_vec(),
        };
        
        self.update_stats(data.len(), result.len(), start_time);
        Ok(result)
    }
    
    /// Decompress data (automatically detects compression format)
    pub fn decompress(&mut self, data: &[u8]) -> Result<Vec<u8>, CompressionError> {
        let start_time = Instant::now();
        
        // Handle uncompressed data or data too small to be compressed
        if data.len() < self.config.min_size_for_compression || !self.is_compressed(data) {
            self.update_stats(data.len(), data.len(), start_time);
            return Ok(data.to_vec());
        }
        
        let result = decompress_size_prepended(data)?;
        self.update_stats(data.len(), result.len(), start_time);
        Ok(result)
    }
    
    /// Check if data is likely compressed
    pub fn is_compressed(&self, data: &[u8]) -> bool {
        // For block compression with size prepend, check if data is smaller than uncompressed threshold
        // or has reasonable size prefix
        if data.len() < 8 { // Need at least 4 bytes for size + 4 bytes for data
            return false;
        }
        
        // Check if the size prefix makes sense (not too large)
        let size = u32::from_le_bytes([data[0], data[1], data[2], data[3]]) as usize;
        
        // For compressed data, the size should be reasonable and the total data length should be size + 4
        size > 0 && size < 100_000_000 && data.len() == size + 4
    }
    
    /// Calculate compression ratio (original_size / compressed_size)
    pub fn calculate_compression_ratio(original_size: usize, compressed_size: usize) -> f64 {
        if compressed_size == 0 {
            return 1.0;
        }
        (original_size as f64 / compressed_size as f64).max(1.0)
    }
    
    /// Get compression statistics (if enabled)
    pub fn stats(&self) -> Option<&CompressionStats> {
        self.stats.as_ref()
    }
    
    /// Reset compression statistics (if enabled)
    pub fn reset_stats(&mut self) {
        if let Some(stats) = &mut self.stats {
            *stats = CompressionStats::default();
        }
    }
    
    /// Update compression statistics
    fn update_stats(&mut self, original_size: usize, compressed_size: usize, start_time: Instant) {
        if let Some(stats) = &mut self.stats {
            stats.original_size = original_size;
            stats.compressed_size = compressed_size;
            stats.total_uncompressed += original_size;
            stats.total_compressed += compressed_size;
            stats.compression_ratio = Self::calculate_compression_ratio(original_size, compressed_size);
            stats.compression_time = start_time.elapsed();
            stats.compression_time = start_time.elapsed();
        }
    }
}

/// Chunk compressor for compressing Minecraft world chunks
pub struct ChunkCompressor {
    algorithm: CompressionAlgorithm,
    level: CompressionLevel,
}

impl ChunkCompressor {
    pub fn new() -> Self {
        Self {
            algorithm: CompressionAlgorithm::Lz4,
            level: CompressionLevel::Default,
        }
    }

    pub fn with_algorithm(mut self, algorithm: CompressionAlgorithm) -> Self {
        self.algorithm = algorithm;
        self
    }

    pub fn with_level(mut self, level: CompressionLevel) -> Self {
        self.level = level;
        self
    }

    pub fn compress(&self, data: &[u8]) -> Result<Vec<u8>, CompressionError> {
        match self.algorithm {
            CompressionAlgorithm::Lz4 => {
                compress_prepend_size(data).map_err(CompressionError::Lz4)
            }
            CompressionAlgorithm::None => Ok(data.to_vec()),
        }
    }

    pub fn decompress(&self, data: &[u8]) -> Result<Vec<u8>, CompressionError> {
        match self.algorithm {
            CompressionAlgorithm::Lz4 => {
                decompress_size_prepended(data).map_err(CompressionError::Lz4Decompress)
            }
            CompressionAlgorithm::None => Ok(data.to_vec()),
        }
    }
}

impl Default for ChunkCompressor {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_compression_builder_defaults() {
        let builder = CompressionBuilder::new();
        assert_eq!(builder.min_size_for_compression, 10_000);
        assert!(builder.checksum);
        assert_eq!(builder.algorithm, CompressionAlgorithm::Lz4);
        assert_eq!(builder.level, CompressionLevel::Default);
        assert!(!builder.stats);
    }

    #[test]
    fn test_compression_builder_configuration() {
        let builder = CompressionBuilder::new()
            .min_size(5_000)
            .checksum(false)
            .algorithm(CompressionAlgorithm::None)
            .level(CompressionLevel::Ultra)
            .stats(true);
        
        assert_eq!(builder.min_size_for_compression, 5_000);
        assert!(!builder.checksum);
        assert_eq!(builder.algorithm, CompressionAlgorithm::None);
        assert_eq!(builder.level, CompressionLevel::Ultra);
        assert!(builder.stats);
    }

    #[test]
    fn test_compression_engine_default() {
        let engine = CompressionEngine::new();
        assert_eq!(engine.config.min_size_for_compression, 10_000);
        assert!(engine.config.checksum);
        assert_eq!(engine.config.algorithm, CompressionAlgorithm::Lz4);
        assert_eq!(engine.config.level, CompressionLevel::Default);
    }

    #[test]
    fn test_compression_roundtrip() {
        let mut engine = CompressionEngine::new();
        let original_data = vec![1, 2, 3, 4, 5];

        // Test with small data (should not compress)
        let compressed = engine.compress(&original_data).unwrap();
        assert_eq!(compressed, original_data);

        let decompressed = engine.decompress(&compressed).unwrap();
        assert_eq!(decompressed, original_data);

        // Test with larger data pattern that should compress
        let large_data: Vec<u8> = (0..20_000).map(|i| (i % 100) as u8).collect();
        let compressed = engine.compress(&large_data).unwrap();
        
        // Should be different from original (compressed)
        assert_ne!(compressed, large_data);

        let decompressed = engine.decompress(&compressed).unwrap();
        assert_eq!(decompressed, large_data);
    }

    #[test]
    fn test_is_compressed_detection() {
        let engine = CompressionEngine::new();
        let original_data = vec![1, 2, 3, 4, 5];
        
        // Create data that will be compressed (large enough)
        let large_data = vec![0; 20_000];
        let compressed_data = engine.compress(&large_data).unwrap();

        // Small data should not be considered compressed
        assert!(!engine.is_compressed(&original_data));
        
        // Compressed data should be detected as compressed
        assert!(engine.is_compressed(&compressed_data));
    }

    #[test]
    fn test_compression_stats() {
        let mut engine = CompressionEngine::with_stats();
        let original_data = vec![1, 2, 3, 4, 5];

        // Compress and decompress
        let compressed = engine.compress(&original_data).unwrap();
        let _ = engine.decompress(&compressed).unwrap();

        // Check stats
        let stats = engine.stats().unwrap();
        assert_eq!(stats.original_size, 5);
        assert_eq!(stats.compressed_size, 5); // Should be same as original for small data
        assert_eq!(stats.total_uncompressed, 5);
        assert_eq!(stats.total_compressed, 5);
        assert!(stats.compression_ratio >= 1.0);
        assert!(stats.compression_time_ms > 0);
    }
}
