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