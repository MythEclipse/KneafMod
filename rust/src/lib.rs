extern crate lazy_static;

mod shared;
mod entity;
mod item;
mod mob;
mod block;
mod valence_integration;

pub use shared::*;
pub use entity::*;
pub use item::*;
pub use mob::*;
pub use block::*;
pub use valence_integration::*;

use jni::{JNIEnv, objects::{JClass, JString}, sys::jstring};
use sysinfo::System;

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustPerformance_getMemoryStatsNative(env: JNIEnv, _class: JClass) -> jstring {
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
pub extern "C" fn Java_com_kneaf_core_RustPerformance_freeStringNative(_env: JNIEnv, _class: JClass, _s: jstring) {
    // jstring is managed by JVM, no need to free
}
// Compatibility layer for gradual migration
static USE_VALENCE_ECS: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustPerformance_setUseValenceEcsNative(_env: JNIEnv, _class: JClass, use_valence: bool) {
    USE_VALENCE_ECS.store(use_valence, std::sync::atomic::Ordering::Relaxed);
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustPerformance_processEntitiesCompatNative(env: JNIEnv, _class: JClass, json_input: JString) -> jstring {
    if USE_VALENCE_ECS.load(std::sync::atomic::Ordering::Relaxed) {
        // For now, return empty result when using Valence ECS
        // In a full implementation, this would interface with the running Valence app
        let result = serde_json::json!({
            "processed_entities": 0,
            "throttled_entities": 0,
            "status": "using_valence_ecs"
        });
        let output = serde_json::to_string(&result).unwrap();
        env.new_string(&output).expect("Couldn't create java string!").into_raw()
    } else {
        // Fall back to original JNI implementation
        entity::Java_com_kneaf_core_RustPerformance_processEntitiesNative(env, _class, json_input)
    }
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustPerformance_processBlockEntitiesCompatNative(env: JNIEnv, _class: JClass, json_input: JString) -> jstring {
    if USE_VALENCE_ECS.load(std::sync::atomic::Ordering::Relaxed) {
        // For now, return empty result when using Valence ECS
        let result = serde_json::json!({
            "processed_blocks": 0,
            "throttled_blocks": 0,
            "status": "using_valence_ecs"
        });
        let output = serde_json::to_string(&result).unwrap();
        env.new_string(&output).expect("Couldn't create java string!").into_raw()
    } else {
        // Fall back to original JNI implementation
        block::Java_com_kneaf_core_RustPerformance_processBlockEntitiesNative(env, _class, json_input)
    }
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustPerformance_processMobAiCompatNative(env: JNIEnv, _class: JClass, json_input: JString) -> jstring {
    if USE_VALENCE_ECS.load(std::sync::atomic::Ordering::Relaxed) {
        // For now, return empty result when using Valence ECS
        let result = serde_json::json!({
            "processed_mobs": 0,
            "ai_disabled_mobs": 0,
            "status": "using_valence_ecs"
        });
        let output = serde_json::to_string(&result).unwrap();
        env.new_string(&output).expect("Couldn't create java string!").into_raw()
    } else {
        // Fall back to original JNI implementation
        mob::Java_com_kneaf_core_RustPerformance_processMobAiNative(env, _class, json_input)
    }
}