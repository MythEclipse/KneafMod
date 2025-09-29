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
pub mod chunk;
pub mod parallelism;
pub mod types;

fn ensure_logging() {
    INIT.call_once(|| {
        let _ = crate::logging::init_logging();
        crate::memory_pool::init_memory_pools();
        crate::performance_monitoring::init_performance_monitoring();
    });
}