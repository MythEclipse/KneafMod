use std::alloc::{alloc, Layout};
use std::ptr::NonNull;
use std::sync::{Arc, Mutex};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::slice;

/// Safe wrapper for memory chunk operations
#[derive(Debug)]
struct SafeChunk {
    /// Pointer to the start of the chunk
    ptr: NonNull<u8>,
    /// Current position in the chunk (offset from start)
    current_offset: usize,
    /// Total size of the chunk
    size: usize,
}

impl SafeChunk {
    /// Create a new safe chunk from raw allocation
    fn new(ptr: NonNull<u8>, size: usize) -> Self {
        Self {
            ptr,
            current_offset: 0,
            size,
        }
    }

    /// Get the current pointer position
    fn current_ptr(&self) -> *mut u8 {
        unsafe { self.ptr.as_ptr().add(self.current_offset) }
    }

    /// Get the end pointer of the chunk
    fn end_ptr(&self) -> *mut u8 {
        unsafe { self.ptr.as_ptr().add(self.size) }
    }

    /// Check if we have enough space for allocation
    fn has_space(&self, size: usize, align: usize) -> bool {
        let align_mask = align - 1;
        let aligned_offset = (self.current_offset + align_mask) & !align_mask;
        let new_offset = aligned_offset + size;
        new_offset <= self.size
    }

    /// Allocate memory from this chunk
    fn allocate(&mut self, size: usize, align: usize) -> Option<*mut u8> {
        let align_mask = align - 1;
        let aligned_offset = (self.current_offset + align_mask) & !align_mask;
        let new_offset = aligned_offset + size;

        if new_offset <= self.size {
            self.current_offset = new_offset;
            Some(unsafe { self.ptr.as_ptr().add(aligned_offset) })
        } else {
            None
        }
    }
}

/// A fast bump allocator for temporary allocations
pub struct BumpArena {
    /// The current chunk of memory we're allocating from
    current: Mutex<SafeChunk>,
    /// Size of chunks to allocate
    chunk_size: usize,
    /// Total bytes allocated
    total_allocated: AtomicUsize,
    /// Total bytes used
    total_used: AtomicUsize,
}

unsafe impl Send for SafeChunk {}
unsafe impl Sync for SafeChunk {}

impl BumpArena {
    /// Create a new bump arena with the specified chunk size
    pub fn new(chunk_size: usize) -> Self {
        let chunk_size = chunk_size.max(1024); // Minimum 1KB chunks
        let chunk = Self::allocate_safe_chunk(chunk_size);
        
        Self {
            current: Mutex::new(chunk),
            chunk_size,
            total_allocated: AtomicUsize::new(chunk_size),
            total_used: AtomicUsize::new(0),
        }
    }

    /// Safely allocate a new chunk with bounds checking
    fn allocate_safe_chunk(size: usize) -> SafeChunk {
        let layout = Layout::from_size_align(size, 8).unwrap();
        let ptr = unsafe {
            let raw_ptr = alloc(layout);
            if raw_ptr.is_null() {
                std::alloc::handle_alloc_error(layout);
            }
            NonNull::new(raw_ptr).unwrap()
        };
        
        SafeChunk::new(ptr, size)
    }

    /// Allocate memory from the arena with bounds checking
    pub fn alloc(&self, size: usize, align: usize) -> *mut u8 {
        let mut chunk = self.current.lock().expect("Arena mutex poisoned");
        
        if let Some(ptr) = chunk.allocate(size, align) {
            self.total_used.fetch_add(size, Ordering::Relaxed);
            ptr
        } else {
            // Need a new chunk
            self.allocate_new_chunk(size, align)
        }
    }

    fn allocate_new_chunk(&self, min_size: usize, align: usize) -> *mut u8 {
        let chunk_size = min_size.max(self.chunk_size);
        let mut new_chunk = Self::allocate_safe_chunk(chunk_size);
        
        // Try to allocate from the new chunk
        let result = new_chunk.allocate(min_size, align)
            .expect("New chunk should have enough space");
        
        // Replace the current chunk
        let mut old_chunk = self.current.lock().expect("Arena mutex poisoned");
        *old_chunk = new_chunk;
        
        self.total_allocated.fetch_add(chunk_size, Ordering::Relaxed);
        self.total_used.fetch_add(min_size, Ordering::Relaxed);
        
        result
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
    let mut arenas = self.arenas.lock().expect("ArenaPool arenas mutex poisoned");
        
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
    let mut arenas = self.arenas.lock().expect("ArenaPool arenas mutex poisoned");
        if arenas.len() < self.max_arenas {
            arenas.push(arena);
        }
    }

    /// Get combined statistics for all arenas
    pub fn stats(&self) -> ArenaPoolStats {
    let arenas = self.arenas.lock().expect("ArenaPool arenas mutex poisoned");
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

/// Safe memory region that can be used for arena allocations
#[derive(Debug)]
struct SafeMemoryRegion<T> {
    ptr: NonNull<T>,
    len: usize,
    capacity: usize,
}

impl<T> SafeMemoryRegion<T> {
    fn new(ptr: *mut T, capacity: usize) -> Self {
        Self {
            ptr: NonNull::new(ptr).expect("Null pointer passed to SafeMemoryRegion"),
            len: 0,
            capacity,
        }
    }

    fn is_valid_index(&self, index: usize) -> bool {
        index < self.len
    }

    fn get(&self, index: usize) -> Option<&T> {
        if self.is_valid_index(index) {
            unsafe { Some(&*self.ptr.as_ptr().add(index)) }
        } else {
            None
        }
    }

    fn get_mut(&mut self, index: usize) -> Option<&mut T> {
        if self.is_valid_index(index) {
            unsafe { Some(&mut *self.ptr.as_ptr().add(index)) }
        } else {
            None
        }
    }

    fn push(&mut self, value: T) -> Result<(), T> {
        if self.len < self.capacity {
            unsafe {
                self.ptr.as_ptr().add(self.len).write(value);
                self.len += 1;
                Ok(())
            }
        } else {
            Err(value)
        }
    }

    fn as_slice(&self) -> &[T] {
        if self.len == 0 {
            &[]
        } else {
            unsafe { slice::from_raw_parts(self.ptr.as_ptr(), self.len) }
        }
    }

    fn as_mut_slice(&mut self) -> &mut [T] {
        if self.len == 0 {
            &mut []
        } else {
            unsafe { slice::from_raw_parts_mut(self.ptr.as_ptr(), self.len) }
        }
    }

    fn clear(&mut self) {
        unsafe {
            for i in 0..self.len {
                self.ptr.as_ptr().add(i).drop_in_place();
            }
        }
        self.len = 0;
    }
}

/// A vector that allocates from an arena with safe operations
pub struct ArenaVec<T> {
    memory: Option<SafeMemoryRegion<T>>,
    arena: Arc<BumpArena>,
}

impl<T> ArenaVec<T> {
    pub fn new(arena: Arc<BumpArena>) -> Self {
        Self {
            memory: None,
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
            memory: Some(SafeMemoryRegion::new(ptr, capacity)),
            arena,
        }
    }

    pub fn push(&mut self, value: T) {
        // Check if we need to grow first
        let needs_grow = self.memory.as_ref().map(|m| m.len >= m.capacity).unwrap_or(true);
        
        if needs_grow {
            self.grow_and_repush();
        }
        
        // Now we should have space
        if let Some(ref mut memory) = self.memory {
            if memory.push(value).is_err() {
                panic!("Should have space after growing or initial allocation");
            }
        } else {
            panic!("Memory should be initialized after grow_and_repush");
        }
    }

    fn grow_and_repush(&mut self) {
        let current_capacity = self.memory.as_ref().map(|m| m.capacity).unwrap_or(0);
        let new_capacity = if current_capacity == 0 { 4 } else { current_capacity * 2 };
        
        let new_size = new_capacity * std::mem::size_of::<T>();
        let align = std::mem::align_of::<T>();
        let new_ptr = self.arena.alloc(new_size, align) as *mut T;
        
        let mut new_memory = SafeMemoryRegion::new(new_ptr, new_capacity);
        
        // Copy existing elements
        if let Some(ref mut old_memory) = self.memory {
            for i in 0..old_memory.len {
                unsafe {
                    let val = std::ptr::read(old_memory.ptr.as_ptr().add(i));
                    if new_memory.push(val).is_err() {
                        panic!("New memory should have space for existing elements");
                    }
                }
            }
            old_memory.len = 0; // Prevent double drop
        }
        
        self.memory = Some(new_memory);
    }

    pub fn len(&self) -> usize {
        self.memory.as_ref().map(|m| m.len).unwrap_or(0)
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    pub fn as_slice(&self) -> &[T] {
        self.memory.as_ref().map(|m| m.as_slice()).unwrap_or(&[])
    }

    pub fn as_mut_slice(&mut self) -> &mut [T] {
        self.memory.as_mut().map(|m| m.as_mut_slice()).unwrap_or(&mut [])
    }
}

impl<T> Drop for ArenaVec<T> {
    fn drop(&mut self) {
        if let Some(ref mut memory) = self.memory {
            memory.clear();
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
#[cfg(feature = "jni")]
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_getArenaPoolStats(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
) -> jni::sys::jstring {
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