use std::sync::Arc;
use tokio::sync::oneshot;
use jni::JNIEnv;
use jni::objects::{JClass, JString, JByteArray, JObject};
use jni::sys::{jlong, jint, jboolean};
use rustperf::database::RustDatabaseAdapter;

/// Async operation types supported by the JNI bridge
#[derive(Debug, Clone, Copy)]
pub enum AsyncOperationType {
    PutChunk,
    GetChunk,
    DeleteChunk,
    SwapOut,
    SwapIn,
    BulkSwapOut,
    BulkSwapIn,
}

impl From<jint> for AsyncOperationType {
    fn from(value: jint) -> Self {
        match value {
            0 => AsyncOperationType::PutChunk,
            1 => AsyncOperationType::GetChunk,
            2 => AsyncOperationType::DeleteChunk,
            3 => AsyncOperationType::SwapOut,
            4 => AsyncOperationType::SwapIn,
            5 => AsyncOperationType::BulkSwapOut,
            6 => AsyncOperationType::BulkSwapIn,
            _ => panic!("Invalid async operation type"),
        }
    }
}

/// Submit an async operation to the Rust database
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativeSubmitAsyncOperation<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    adapter_ptr: jlong,
    op_type: jint,
    key: JString<'a>,
    data: JByteArray<'a>,
) -> jlong {
    let (tx, rx) = oneshot::channel();
    
    // Convert JNI parameters to Rust types
    let key_str = env.get_string(&key)
        .expect("Failed to get key string")
        .to_str()
        .expect("Failed to convert key to str")
        .to_string();
    
    let data_vec = env.convert_byte_array(&data)
        .expect("Failed to convert byte array");
    
    // Get adapter reference
    let adapter = unsafe { &*(adapter_ptr as *const RustDatabaseAdapter) };
    
    // Spawn async task
    tokio::spawn(async move {
        let result = match AsyncOperationType::from(op_type) {
            AsyncOperationType::PutChunk => adapter.put_chunk(&key_str, &data_vec).map(|_| None),
            AsyncOperationType::GetChunk => adapter.get_chunk(&key_str).map(|v| v.map(|d| d.into_bytes())),
            AsyncOperationType::DeleteChunk => adapter.delete_chunk(&key_str).map(|_| None),
            AsyncOperationType::SwapOut => adapter.swap_out_chunk(&key_str).map(|_| None),
            AsyncOperationType::SwapIn => adapter.swap_in_chunk(&key_str).map(|d| Some(d.into_bytes())),
            _ => Err("Unsupported operation type".to_string()),
        };
        
        let _ = tx.send(result);
    });
    
    // Return oneshot receiver ID for result retrieval
    rx.as_raw() as jlong
}

/// Get result of async operation
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativeGetAsyncResult<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    op_id: jlong,
) -> JObject<'a> {
    let rx = unsafe { oneshot::Receiver::from_raw(op_id as *mut oneshot::Receiver<Result<Option<Vec<u8>>, String>>) };
    
    match rx.await {
        Ok(Ok(Some(data))) => {
            let byte_array = env.byte_array_from_slice(&data)
                .expect("Failed to create byte array");
            JObject::from(byte_array)
        }
        Ok(Ok(None)) => JObject::null(),
        Ok(Err(e)) => {
            env.throw_new("java/lang/Exception", &e)
                .expect("Failed to throw exception");
            JObject::null()
        }
        Err(_) => {
            env.throw_new("java/lang/Exception", "Async operation cancelled")
                .expect("Failed to throw exception");
            JObject::null()
        }
    }
}

/// Bulk async operation support
#[no_mangle]
pub extern "system" fn Java_com_kneaf_core_chunkstorage_RustDatabaseAdapter_nativeSubmitBulkAsyncOperation<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    adapter_ptr: jlong,
    op_type: jint,
    keys: JObject<'a>,
) -> jlong {
    let (tx, rx) = oneshot::channel();
    
    // Convert JNI list to Rust vector
    let keys_vec = convert_jni_list_to_string_vec(&mut env, keys).expect("Failed to convert list");
    
    // Get adapter reference
    let adapter = unsafe { &*(adapter_ptr as *const RustDatabaseAdapter) };
    
    // Spawn async task
    tokio::spawn(async move {
        let result = match AsyncOperationType::from(op_type) {
            AsyncOperationType::BulkSwapOut => adapter.bulk_swap_out(&keys_vec).map(|count| Some(vec![count as u8])),
            AsyncOperationType::BulkSwapIn => adapter.bulk_swap_in(&keys_vec).map(|results| Some(serialize_results(results))),
            _ => Err("Unsupported bulk operation type".to_string()),
        };
        
        let _ = tx.send(result);
    });
    
    rx.as_raw() as jlong
}

/// Helper to convert JNI List to Vec<String>
fn convert_jni_list_to_string_vec<'a>(env: &mut JNIEnv<'a>, list: JObject<'a>) -> Result<Vec<String>, String> {
    let list_class = env.find_class("java/util/List").map_err(|e| e.to_string())?;
    let size_method = env.get_method_id(&list_class, "size", "()I").map_err(|e| e.to_string())?;
    let get_method = env.get_method_id(&list_class, "get", "(I)Ljava/lang/Object;").map_err(|e| e.to_string())?;
    
    let size = env.call_method(&list, "size", "()I", &[]).map_err(|e| e.to_string())?.i().unwrap();
    let mut keys_vec = Vec::with_capacity(size as usize);
    
    for i in 0..size {
        let element = env.call_method(&list, "get", "(I)Ljava/lang/Object;", &[jni::objects::JValue::Int(i)])
            .map_err(|e| e.to_string())?;
        
        let str_obj = element.l().map_err(|e| e.to_string())?;
        let key_str = env.get_string(&JString::from(str_obj)).map_err(|e| e.to_string())?;
        keys_vec.push(key_str.to_str().map_err(|e| e.to_string())?.to_string());
    }
    
    Ok(keys_vec)
}

/// Helper to serialize results for bulk operations
fn serialize_results(results: Vec<Vec<u8>>) -> Vec<u8> {
    let mut serialized = Vec::new();
    for result in results {
        serialized.extend_from_slice(&(result.len() as u32).to_le_bytes());
        serialized.extend_from_slice(&result);
    }
    serialized
}