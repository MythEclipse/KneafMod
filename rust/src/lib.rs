#![allow(
    unused_imports,
    dead_code,
    unused_variables,
    unused_mut,
    unused_doc_comments,
    mismatched_lifetime_syntaxes
)]

use std::sync::Once;

static INIT: Once = Once::new();

// Module declarations
pub mod entity;
pub mod item;
pub mod mob;
pub mod block;
pub mod flatbuffers;
pub mod logging;
pub mod memory_pool;
pub mod performance_monitoring;
pub mod simd;
pub mod chunk;
pub mod spatial;
pub mod parallelism;
pub mod types;

fn ensure_logging() {
    INIT.call_once(|| {
        let _ = crate::logging::init_logging();
        crate::memory_pool::init_memory_pools();
        crate::performance_monitoring::init_performance_monitoring();
    });
}

// Re-export commonly used performance helpers at crate root so tests and Java JNI
// bindings can import them as `rustperf::calculate_distances_simd` etc.
pub use crate::spatial::{calculate_distances_simd, calculate_chunk_distances_simd};