#![allow(
    unused_doc_comments,
    mismatched_lifetime_syntaxes
)]

// Module declarations
pub mod arena;
pub mod entity;
pub mod item;
pub mod mob;
pub mod block;
pub mod binary;
pub mod logging;
pub mod memory_pool;
pub mod performance_monitoring;
pub mod simd;
pub mod chunk;
pub mod spatial;
pub mod parallelism;
pub mod types;
pub mod database;
pub mod allocator;

// Re-export commonly used performance helpers at crate root so tests and Java JNI
// bindings can import them as `rustperf::calculate_distances_simd` etc.
pub use crate::spatial::{calculate_distances_simd, calculate_chunk_distances_simd};

// Re-export swap performance monitoring functions and types
pub use crate::performance_monitoring::{
    report_swap_operation, report_memory_pressure, report_swap_cache_statistics,
    report_swap_io_performance, report_swap_pool_metrics, report_swap_component_health,
    get_swap_performance_summary, SwapHealthStatus
};

// Initialize the allocator - should be called once at startup
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_NativeBridge_initRustAllocator() {
    crate::allocator::init_allocator();
}

// Generate a float buffer for NativeFloatBuffer - allocate native memory for float array
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_generateFloatBufferNative(
    mut env: jni::JNIEnv,
    _class: jni::objects::JClass,
    rows: jni::sys::jlong,
    cols: jni::sys::jlong,
) -> jni::sys::jobject {
    let total_elements = rows * cols;
    let total_bytes = total_elements * 4; // 4 bytes per float
    
    // Allocate memory for the buffer
    let mut buffer = vec![0u8; total_bytes as usize];
    let buffer_ptr = buffer.as_mut_ptr();
    
    // Forget the vector so Rust doesn't free the memory
    std::mem::forget(buffer);
    
    // Create direct ByteBuffer from the allocated memory
    match unsafe { env.new_direct_byte_buffer(buffer_ptr, total_bytes as usize) } {
        Ok(buffer) => buffer.into_raw(),
        Err(e) => {
            eprintln!("Failed to allocate direct ByteBuffer: {:?}", e);
            // Free the memory if we failed to create the ByteBuffer
            unsafe { 
                std::alloc::dealloc(buffer_ptr, std::alloc::Layout::from_size_align_unchecked(total_bytes as usize, 1));
            }
            std::ptr::null_mut()
        }
    }
}

// Free a float buffer allocated by generateFloatBufferNative
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_performance_RustPerformance_freeFloatBufferNative(
    env: jni::JNIEnv,
    _class: jni::objects::JClass,
    buffer: jni::sys::jobject,
) {
    if buffer.is_null() {
        return;
    }
    
    // Get the direct ByteBuffer
    let byte_buffer = unsafe { jni::objects::JByteBuffer::from_raw(buffer) };
    match env.get_direct_buffer_address(&byte_buffer) {
        Ok(address) => {
            if !address.is_null() {
                // Get the capacity of the buffer
                match env.get_direct_buffer_capacity(&byte_buffer) {
                    Ok(capacity) if capacity > 0 => {
                        // Free the native memory
                        unsafe {
                            std::alloc::dealloc(
                                address as *mut u8,
                                std::alloc::Layout::from_size_align_unchecked(capacity, 1)
                            );
                        }
                    }
                    _ => {
                        eprintln!("Failed to get buffer capacity or invalid capacity");
                    }
                }
            }
        }
        Err(e) => {
            eprintln!("Failed to get direct buffer address: {:?}", e);
        }
    }
}