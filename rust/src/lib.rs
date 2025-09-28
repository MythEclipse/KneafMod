extern crate lazy_static;

mod shared;
mod entity;
mod item;
mod mob;
mod block;
mod spatial;

pub use shared::*;
pub use entity::*;
pub use item::*;
pub use mob::*;
pub use block::*;
pub use spatial::*;

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
pub extern "C" fn Java_com_kneaf_core_performance_RustPerformance_freeStringNative(_env: JNIEnv, _class: JClass, _s: jstring) {
    // jstring is managed by JVM, no need to free
}