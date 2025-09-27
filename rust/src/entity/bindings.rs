use super::types::*;
use super::processing::*;
use jni::{JNIEnv, objects::{JClass, JString}, sys::jstring};

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustPerformance_processEntitiesNative(mut env: JNIEnv, _class: JClass, json_input: JString) -> jstring {
    let input: String = env.get_string(&json_input).expect("Couldn't get java string!").into();
    let input: Input = serde_json::from_str(&input).unwrap_or(Input { tick_count: 0, entities: vec![] });
    let result = process_entities(input);
    let output = serde_json::to_string(&result).unwrap();
    env.new_string(&output).expect("Couldn't create java string!").into_raw()
}