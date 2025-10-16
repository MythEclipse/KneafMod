use std::sync::Arc;

/// Zero-copy buffer for efficient data transfer
#[derive(Clone)]
pub struct ZeroCopyBuffer {
    data: Arc<Vec<u8>>,
}

impl ZeroCopyBuffer {
    pub fn new(data: Vec<u8>) -> Self {
        Self {
            data: Arc::new(data),
        }
    }

    pub fn as_slice(&self) -> &[u8] {
        &self.data
    }

    pub fn len(&self) -> usize {
        self.data.len()
    }

    pub fn is_empty(&self) -> bool {
        self.data.is_empty()
    }
}