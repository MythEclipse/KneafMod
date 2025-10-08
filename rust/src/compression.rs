use lz4_flex::block::{compress_prepend_size, decompress_size_prepended};

/// Adaptive compression configuration
#[derive(Debug, Clone)]
pub struct CompressionConfig {
    pub min_size_for_compression: usize,
    pub checksum: bool,
}

impl Default for CompressionConfig {
    fn default() -> Self {
        Self {
            min_size_for_compression: 10_000, // 10KB - compress chunks larger than this
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

        let compressed = compress_prepend_size(data);

        Ok(compressed)
    }

    /// Decompress chunk data (automatically detects if compressed)
    pub fn decompress(&self, data: &[u8]) -> Result<Vec<u8>, String> {
        decompress_size_prepended(data)
            .map_err(|e| format!("LZ4 decompression failed: {}", e))
    }

    /// Check if data is likely compressed (simple heuristic for block compression)
    pub fn is_compressed(&self, data: &[u8]) -> bool {
        // For block compression with size prepend, check if data is smaller than uncompressed threshold
        // or has reasonable size prefix
        if data.len() < 4 {
            return false;
        }
        // Check if the size prefix makes sense (not too large)
        let size = u32::from_le_bytes([data[0], data[1], data[2], data[3]]) as usize;
        size > 0 && size < 100_000_000 // reasonable limit
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
    #[allow(dead_code)]
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
        Ok(compress_prepend_size(data))
    }

    /// Decompress data compressed with LZ4 block compression
    pub fn decompress(&self, data: &[u8]) -> Result<Vec<u8>, String> {
        decompress_size_prepended(data)
            .map_err(|e| format!("LZ4 block decompression failed: {}", e))
    }
}

/// Compression statistics and metrics
#[derive(Debug, Clone)]
pub struct CompressionStats {
    pub original_size: usize,
    pub compressed_size: usize,
    pub compression_ratio: f64,
    pub compression_time_ms: u64,
}

/// Compression engine with statistics
pub struct CompressionEngine {
    chunk_compressor: ChunkCompressor,
    block_compressor: BlockCompressor,
}

impl CompressionEngine {
    pub fn new() -> Self {
        Self {
            chunk_compressor: ChunkCompressor::new(),
            block_compressor: BlockCompressor::new(),
        }
    }

    pub fn compress_chunk(&self, data: &[u8]) -> Result<Vec<u8>, String> {
        self.chunk_compressor.compress(data)
    }

    pub fn decompress_chunk(&self, data: &[u8]) -> Result<Vec<u8>, String> {
        self.chunk_compressor.decompress(data)
    }

    pub fn compress_block(&self, data: &[u8]) -> Result<Vec<u8>, String> {
        self.block_compressor.compress(data)
    }

    pub fn decompress_block(&self, data: &[u8]) -> Result<Vec<u8>, String> {
        self.block_compressor.decompress(data)
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