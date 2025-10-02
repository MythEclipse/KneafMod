//! Memory allocator configuration
//!
//! This module provides conditional compilation for memory allocators.
//! On Windows with MinGW, we avoid mimalloc due to compatibility issues.
//! On other platforms, mimalloc is used for better performance.

#[cfg(not(target_os = "windows"))]
use mimalloc::MiMalloc;

#[cfg(not(target_os = "windows"))]
#[global_allocator]
static GLOBAL: MiMalloc = MiMalloc;

#[cfg(target_os = "windows")]
pub fn init_allocator() {
    // On Windows, we use the default system allocator
    // to avoid libmimalloc-sys compilation issues with MinGW
    println!("Using system default allocator on Windows");
}

#[cfg(not(target_os = "windows"))]
pub fn init_allocator() {
    println!("Using mimalloc allocator for better performance");
}