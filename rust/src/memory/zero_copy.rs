use std::sync::atomic::AtomicU64;
use std::sync::Mutex;
use std::sync::atomic::Ordering;

/// Minimal ZeroCopyBuffer type used by multiple modules as a lightweight placeholder.
#[derive(Clone)]
pub struct ZeroCopyBuffer {
    pub address: u64,
    pub size: usize,
    pub operation_type: u8,
}

impl ZeroCopyBuffer {
    pub fn new(address: u64, size: usize, operation_type: u8) -> Self {
        Self { address, size, operation_type }
    }
}

/// Minimal buffer reference used by async bridge
#[derive(Debug)]
pub struct ZeroCopyBufferRef {
    pub buffer_id: u64,
    pub address: u64,
    pub size: usize,
    pub operation_type: u8,
    pub java_buffer: Option<jni::objects::JByteBuffer<'static>>,
    pub creation_time: std::time::SystemTime,
    pub ref_count: std::sync::atomic::AtomicUsize,
    // other fields are intentionally omitted for the stub
}

impl ZeroCopyBufferRef {
    pub fn new(address: u64, size: usize, operation_type: u8) -> Self {
        static NEXT_ID: AtomicU64 = AtomicU64::new(1);
        Self {
            buffer_id: NEXT_ID.fetch_add(1, Ordering::SeqCst),
            address,
            size,
            operation_type,
            java_buffer: None,
            creation_time: std::time::SystemTime::now(),
            ref_count: std::sync::atomic::AtomicUsize::new(1),
        }
    }
}

/// Minimal pool with acquire/release semantics for compilation
pub struct ZeroCopyBufferPool {
}

impl ZeroCopyBufferPool {
    pub fn new(_capacity: usize) -> Self {
        Self { }
    }

    pub fn acquire(&self, address: u64, size: usize, operation_type: u8) -> ZeroCopyBuffer {
        ZeroCopyBuffer::new(address, size, operation_type)
    }

    pub fn release(&self, _buf: ZeroCopyBuffer) {
        // no-op for stub
    }
}

/// Very small global tracker used by some modules; just keeps a list of buffer ids for sync
pub struct GlobalBufferTracker {
    list: Mutex<Vec<u64>>,
}

impl GlobalBufferTracker {
    pub fn new() -> Self { Self { list: Mutex::new(Vec::new()) } }
    pub fn register_buffer(&self, id: u64) -> Result<(), String> {
        self.list.lock().unwrap().push(id);
        Ok(())
    }
    pub fn unregister_buffer(&self, id: u64) -> Result<(), String> {
        let mut l = self.list.lock().unwrap();
        if let Some(pos) = l.iter().position(|x| *x == id) {
            l.remove(pos);
            Ok(())
        } else {
            Err(format!("Buffer id {} not found", id))
        }
    }
}

use once_cell::sync::OnceCell;
static GLOBAL_TRACKER: OnceCell<GlobalBufferTracker> = OnceCell::new();

pub fn get_global_buffer_tracker() -> &'static GlobalBufferTracker {
    GLOBAL_TRACKER.get_or_init(|| GlobalBufferTracker::new())
}
