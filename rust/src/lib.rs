extern crate lazy_static;

mod shared;
mod entity;
mod item;
mod mob;

pub use shared::*;
pub use entity::*;
pub use item::*;
pub use mob::*;

use jni::{JNIEnv, objects::JClass, sys::jstring};

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustPerformance_freeStringNative(_env: JNIEnv, _class: JClass, _s: jstring) {
    // jstring is managed by JVM, no need to free
}