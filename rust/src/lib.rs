extern crate lazy_static;

mod shared;
mod entity;
mod item;
mod mob;
mod block;
mod spatial;
mod chunk;

pub use shared::*;
pub use entity::*;
pub use item::*;
pub use mob::*;
pub use block::*;
pub use spatial::*;
pub use chunk::*;

use jni::{JNIEnv, objects::JClass, sys::jstring};
use sysinfo::System;

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_getMemoryStatsNative(env: JNIEnv, _class: JClass) -> jstring {
    let mut sys = System::new_all();
    sys.refresh_memory();

    let total_memory = sys.total_memory();
    let used_memory = sys.used_memory();
    let available_memory = sys.available_memory();

    let stats = serde_json::json!({
        "total_memory_kb": total_memory,
        "used_memory_kb": used_memory,
        "available_memory_kb": available_memory,
        "memory_usage_percent": (used_memory as f64 / total_memory as f64 * 100.0)
    });

    let output = serde_json::to_string(&stats).unwrap();
    env.new_string(&output).expect("Couldn't create java string!").into_raw()
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_getCpuStatsNative(env: JNIEnv, _class: JClass) -> jstring {
    let mut sys = System::new_all();
    sys.refresh_cpu();

    let cpu_usage = sys.global_cpu_info().cpu_usage();
    let cpu_count = sys.cpus().len();

    let stats = serde_json::json!({
        "cpu_usage_percent": cpu_usage,
        "cpu_count": cpu_count
    });

    let output = serde_json::to_string(&stats).unwrap();
    env.new_string(&output).expect("Couldn't create java string!").into_raw()
}