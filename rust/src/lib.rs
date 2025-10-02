#![allow(
    unused_doc_comments,
    mismatched_lifetime_syntaxes
)]





// Module declarations
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


// Re-export commonly used performance helpers at crate root so tests and Java JNI
// bindings can import them as `rustperf::calculate_distances_simd` etc.
pub use crate::spatial::{calculate_distances_simd, calculate_chunk_distances_simd};

// Re-export swap performance monitoring functions and types
pub use crate::performance_monitoring::{
    report_swap_operation, report_memory_pressure, report_swap_cache_statistics,
    report_swap_io_performance, report_swap_pool_metrics, report_swap_component_health,
    get_swap_performance_summary, SwapHealthStatus
};