use std::alloc::{alloc, dealloc, Layout};
use std::cell::UnsafeCell;
use std::ptr::NonNull;
use std::sync::Mutex;
use std::sync::atomic::{AtomicUsize, Ordering};
use log::debug;

/// A fast bump allocator for temporary allocations
pub struct BumpArena {
    /// The current chunk of memory we're allocating from
    current: UnsafeCell<Chunk>,
    /// Size of chunks to allocate
    chunk_size: usize,
    /// Total bytes allocated
    total_allocated: AtomicUsize,
    /// Total bytes used
    total_used: AtomicUsize,
}

struct Chunk {
    /// Pointer to the start of the chunk
    ptr: NonNull<u8>,
    /// Current position in the chunk
    current: *mut u8,
    /// End of the chunk
    end: *mut u8,
}

unsafe impl Send for Chunk {}
unsafe impl Sync for Chunk {}

impl BumpArena {
    /// Create a new bump arena with the specified chunk size
    pub fn new(chunk_size: usize) -> Self {
        let chunk_size = chunk_size.max(1024); // Minimum 1KB chunks
        let mut chunk = Chunk {
            ptr: NonNull::dangling(),
            current: std::ptr::null_mut(),
            end: std::ptr::null_mut(),
        };
        
        // Allocate initial chunk
        Self::allocate_chunk(&mut chunk, chunk_size);
        
        Self {
            current: UnsafeCell::new(chunk),
            chunk_size,
            total_allocated: AtomicUsize::new(chunk_size),
            total_used: AtomicUsize::new(0),
        }
    }

    fn allocate_chunk(chunk: &mut Chunk, size: usize) {
        let layout = Layout::from_size_align(size, 8).unwrap();
        unsafe {
            let ptr = alloc(layout);
            if ptr.is_null() {
                panic!("Failed to allocate memory for bump arena");
            }
            chunk.ptr = NonNull::new(ptr).unwrap();
            chunk.current = ptr;
            chunk.end = ptr.add(size);
        }
    }

    /// Allocate memory from the arena
    pub fn alloc(&self, size: usize, align: usize) -> *mut u8 {
        let chunk = unsafe { &mut *self.current.get() };
        
        // Align the current pointer
        let align_mask = align - 1;
        let aligned = ((chunk.current as usize) + align_mask) & !align_mask;
        
        let new_current = aligned + size;
        
        if new_current <= chunk.end as usize {
            chunk.current = new_current as *mut u8;
            self.total_used.fetch_add(size, Ordering::Relaxed);
            aligned as *mut u8
        } else {
            // Need a new chunk
            self.allocate_new_chunk(size, align)
        }
    }

    fn allocate_new_chunk(&self, min_size: usize, align: usize) -> *mut u8 {
        let chunk_size = min_size.max(self.chunk_size);
        let mut new_chunk = Chunk {
            ptr: NonNull::dangling(),
            current: std::ptr::null_mut(),
            end: std::ptr::null_mut(),
        };
        
        Self::allocate_chunk(&mut new_chunk, chunk_size);
        
        // Align the current pointer
        let align_mask = align - 1;
        let aligned = ((new_chunk.current as usize) + align_mask) & !align_mask;
        let new_current = aligned + min_size;
        
        new_chunk.current = new_current as *mut u8;
        
        // Replace the current chunk
        let old_chunk = unsafe { &mut *self.current.get() };
        *old_chunk = new_chunk;
        
        self.total_allocated.fetch_add(chunk_size, Ordering::Relaxed);
        self.total_used.fetch_add(min_size, Ordering::Relaxed);
        
        aligned as *mut u8
    }

    /// Get usage statistics
    pub fn stats(&self) -> ArenaStats {
        let allocated = self.total_allocated.load(Ordering::Relaxed);
        let used = self.total_used.load(Ordering::Relaxed);
        
        ArenaStats {
            total_allocated: allocated,
            total_used: used,
            utilization: if allocated > 0 {
                used as f64 / allocated as f64
            } else {
                0.0
            },
        }
    }
}

/// Statistics for the arena allocator
#[derive(Debug, Clone)]
pub struct ArenaStats {
    pub total_allocated: usize,
    pub total_used: usize,
    pub utilization: f64,
}

/// A thread-safe arena allocator pool
pub struct ArenaPool {
    arenas: Mutex<Vec<Arc<BumpArena>>>,
    default_chunk_size: usize,
    max_arenas: usize,
}

impl ArenaPool {
    /// Create a new arena pool
    pub fn new(default_chunk_size: usize, max_arenas: usize) -> Self {
        Self {
            arenas: Mutex::new(Vec::new()),
            default_chunk_size,
            max_arenas,
        }
    }

    /// Get an arena from the pool (create if necessary)
    pub fn get_arena(&self) -> Arc<BumpArena> {
        let mut arenas = self.arenas.lock().unwrap();
        
        // Try to reuse an existing arena
        if let Some(arena) = arenas.pop() {
            return arena;
        }
        
        // Create a new arena if we haven't reached the limit
        if arenas.len() < self.max_arenas {
            Arc::new(BumpArena::new(self.default_chunk_size))
        } else {
            // Reuse the least recently used arena
            arenas.remove(0)
        }
    }

    /// Return an arena to the pool
    pub fn return_arena(&self, arena: Arc<BumpArena>) {
        let mut arenas = self.arenas.lock().unwrap();
        if arenas.len() < self.max_arenas {
            arenas.push(arena);
        }
    }

    /// Get combined statistics for all arenas
    pub fn stats(&self) -> ArenaPoolStats {
        let arenas = self.arenas.lock().unwrap();
        let mut total_allocated = 0;
        let mut total_used = 0;
        let arena_count = arenas.len();

        for arena in arenas.iter() {
            let stats = arena.stats();
            total_allocated += stats.total_allocated;
            total_used += stats.total_used;
        }

        ArenaPoolStats {
            arena_count,
            total_allocated,
            total_used,
            utilization: if total_allocated > 0 {
                total_used as f64 / total_allocated as f64
            } else {
                0.0
            },
        }
    }
}

/// Statistics for the arena pool
#[derive(Debug, Clone)]
pub struct ArenaPoolStats {
    pub arena_count: usize,
    pub total_allocated: usize,
    pub total_used: usize,
    pub utilization: f64,
}

/// A scoped arena that automatically returns to pool when dropped
pub struct ScopedArena {
    arena: Arc<BumpArena>,
    pool: Arc<ArenaPool>,
}

impl ScopedArena {
    pub fn new(pool: Arc<ArenaPool>) -> Self {
        let arena = pool.get_arena();
        Self { arena, pool }
    }

    pub fn alloc(&self, size: usize, align: usize) -> *mut u8 {
        self.arena.alloc(size, align)
    }

    pub fn stats(&self) -> ArenaStats {
        self.arena.stats()
    }
}

impl Drop for ScopedArena {
    fn drop(&mut self) {
        self.pool.return_arena(Arc::clone(&self.arena));
    }
}

/// A vector that allocates from an arena
pub struct ArenaVec<T> {
    ptr: *mut T,
    len: usize,
    capacity: usize,
    arena: Arc<BumpArena>,
}

impl<T> ArenaVec<T> {
    pub fn new(arena: Arc<BumpArena>) -> Self {
        Self {
            ptr: std::ptr::null_mut(),
            len: 0,
            capacity: 0,
            arena,
        }
    }

    pub fn with_capacity(arena: Arc<BumpArena>, capacity: usize) -> Self {
        if capacity == 0 {
            return Self::new(arena);
        }

        let size = capacity * std::mem::size_of::<T>();
        let align = std::mem::align_of::<T>();
        let ptr = arena.alloc(size, align) as *mut T;

        Self {
            ptr,
            len: 0,
            capacity,
            arena,
        }
    }

    pub fn push(&mut self, value: T) {
        if self.len == self.capacity {
            self.grow();
        }

        unsafe {
            self.ptr.add(self.len).write(value);
            self.len += 1;
        }
    }

    fn grow(&mut self) {
        let new_capacity = if self.capacity == 0 { 4 } else { self.capacity * 2 };
        let new_size = new_capacity * std::mem::size_of::<T>();
        let align = std::mem::align_of::<T>();
        
        let new_ptr = self.arena.alloc(new_size, align) as *mut T;
        
        if !self.ptr.is_null() {
            unsafe {
                std::ptr::copy_nonoverlapping(self.ptr, new_ptr, self.len);
            }
        }

        self.ptr = new_ptr;
        self.capacity = new_capacity;
    }

    pub fn len(&self) -> usize {
        self.len
    }

    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    pub fn as_slice(&self) -> &[T] {
        if self.ptr.is_null() {
            &[]
        } else {
            unsafe {
                std::slice::from_raw_parts(self.ptr, self.len)
            }
        }
    }

    pub fn as_mut_slice(&mut self) -> &mut [T] {
        if self.ptr.is_null() {
            &mut []
        } else {
            unsafe {
                std::slice::from_raw_parts_mut(self.ptr, self.len)
            }
        }
    }
}

impl<T> Drop for ArenaVec<T> {
    fn drop(&mut self) {
        // Arena doesn't support deallocation, so we just drop the values
        unsafe {
            for i in 0..self.len {
                self.ptr.add(i).drop_in_place();
            }
        }
    }
}

// Global arena pool for the application
lazy_static::lazy_static! {
    static ref GLOBAL_ARENA_POOL: Arc<ArenaPool> = Arc::new(ArenaPool::new(
        64 * 1024, // 64KB default chunk size
        16         // Max 16 arenas
    ));
}

/// Get the global arena pool
pub fn get_global_arena_pool() -> Arc<ArenaPool> {
    Arc::clone(&GLOBAL_ARENA_POOL)
}

/// Create a scoped arena from the global pool
pub fn create_scoped_arena() -> ScopedArena {
    ScopedArena::new(get_global_arena_pool())
}

/// JNI function to get arena pool statistics
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_getArenaPoolStats(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let stats = GLOBAL_ARENA_POOL.stats();
    let stats_json = serde_json::json!({
        "arenaCount": stats.arena_count,
        "totalAllocated": stats.total_allocated,
        "totalUsed": stats.total_used,
        "utilization": stats.utilization,
    });

    match env.new_string(&serde_json::to_string(&stats_json).unwrap_or_default()) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_bump_arena_allocation() {
        let arena = BumpArena::new(1024);
        
        let ptr1 = arena.alloc(10, 8);
        assert!(!ptr1.is_null());
        
        let ptr2 = arena.alloc(20, 8);
        assert!(!ptr2.is_null());
        assert_ne!(ptr1, ptr2);
        
        let stats = arena.stats();
        assert!(stats.total_used >= 30);
        assert!(stats.utilization > 0.0);
    }

    #[test]
    fn test_arena_vec() {
        let arena = Arc::new(BumpArena::new(1024));
        let mut vec = ArenaVec::with_capacity(arena, 4);
        
        vec.push(1);
        vec.push(2);
        vec.push(3);
        
        assert_eq!(vec.len(), 3);
        assert_eq!(vec.as_slice(), &[1, 2, 3]);
    }

    #[test]
    fn test_arena_pool() {
        let pool = Arc::new(ArenaPool::new(1024, 2));
        
        let arena1 = pool.get_arena();
        let arena2 = pool.get_arena();
        
        // Third arena should reuse one of the first two
        let arena3 = pool.get_arena();
        
        pool.return_arena(arena1);
        pool.return_arena(arena2);
        pool.return_arena(arena3);
        
        let stats = pool.stats();
        assert_eq!(stats.arena_count, 2);
    }
}