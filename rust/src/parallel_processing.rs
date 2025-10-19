//! Parallel processing and batch operations for KneafCore vector library
//! Provides safe memory management, zero-copy operations, and batch processing

use jni::JNIEnv;
use jni::objects::{JClass, JFloatArray, JObject, JObjectArray};
use jni::sys::{jlong, jint};
use std::sync::{Arc, Mutex};
use std::collections::HashMap;
use rayon::prelude::*;
use nalgebra as na;
use glam::Vec3;
use crate::performance::{faer_matrix_mul, glam_matrix_mul};

/// Memory manager for safe native memory allocation and deallocation
pub struct SafeMemoryManager {
    allocations: Arc<Mutex<HashMap<jlong, Vec<f32>>>>,
    next_id: Arc<Mutex<jlong>>,
}

lazy_static::lazy_static! {
    pub static ref MEMORY_MANAGER: SafeMemoryManager = SafeMemoryManager::new();
}

impl SafeMemoryManager {
    pub fn new() -> Self {
        Self {
            allocations: Arc::new(Mutex::new(HashMap::new())),
            next_id: Arc::new(Mutex::new(1)),
        }
    }

    pub fn allocate(&self, size: usize) -> jlong {
        let id = {
            let mut next_id = self.next_id.lock().unwrap();
            let current = *next_id;
            *next_id += 1;
            current
        };

        let mut allocations = self.allocations.lock().unwrap();
        allocations.insert(id, Vec::with_capacity(size));
        id
    }

    pub fn store_data(&self, id: jlong, data: Vec<f32>) -> bool {
        let mut allocations = self.allocations.lock().unwrap();
        if let Some(vec) = allocations.get_mut(&id) {
            *vec = data;
            true
        } else {
            false
        }
    }

    pub fn retrieve_data(&self, id: jlong) -> Option<Vec<f32>> {
        let mut allocations = self.allocations.lock().unwrap();
        allocations.remove(&id)
    }

    pub fn deallocate(&self, id: jlong) -> bool {
        let mut allocations = self.allocations.lock().unwrap();
        allocations.remove(&id).is_some()
    }
    pub fn cleanup_all(&self) {
        let mut allocations = self.allocations.lock().unwrap();
        allocations.clear();
    }

}
    
    pub fn safe_glam_matrix_mul(a: [f32; 16], b: [f32; 16]) -> Vec<f32> {
        let result = glam_matrix_mul(a, b);
        (&result[..]).to_vec()
    }

    #[allow(dead_code)]
    pub fn safe_faer_matrix_mul(a: [f32; 16], b: [f32; 16]) -> Vec<f32> {
        let result = faer_matrix_mul(a, b);
        (&result[..]).to_vec()
    }

    /// JNI helper wrappers (kept as plain Rust functions; actual extern exports live in lib.rs)
    #[allow(non_snake_case)]
    pub fn Java_com_kneaf_core_ParallelRustVectorProcessor_nalgebraMatrixMulDirect<'a>(
        mut env: JNIEnv<'a>,
        _class: JClass,
        a_buffer: JObject<'a>,
        b_buffer: JObject<'a>,
        result_buffer: JObject<'a>,
    ) -> JObject<'a> {
        let a_data = get_direct_buffer_data(&mut env, a_buffer);
        let b_data = get_direct_buffer_data(&mut env, b_buffer);

        if a_data.len() != 16 || b_data.len() != 16 {
            env.throw_new("java/lang/IllegalArgumentException", "Buffers must contain 16 floats each").unwrap();
            return result_buffer;
        }

        let a_array: [f32; 16] = a_data.try_into().unwrap();
        let b_array: [f32; 16] = b_data.try_into().unwrap();

        let result = nalgebra_matrix_mul(a_array, b_array);

        set_direct_buffer_data(&mut env, &result_buffer, &result);

        env.new_local_ref(&result_buffer).unwrap()
    }

    #[allow(non_snake_case)]
    pub fn Java_com_kneaf_core_ParallelRustVectorProcessor_nalgebraVectorAddDirect<'a>(
        mut env: JNIEnv<'a>,
        _class: JClass,
        a_buffer: JObject<'a>,
        b_buffer: JObject<'a>,
        result_buffer: JObject<'a>,
    ) -> JObject<'a> {
        let a_data = get_direct_buffer_data(&mut env, a_buffer);
        let b_data = get_direct_buffer_data(&mut env, b_buffer);

        if a_data.len() != 3 || b_data.len() != 3 {
            env.throw_new("java/lang/IllegalArgumentException", "Buffers must contain 3 floats each").unwrap();
            return result_buffer;
        }

        let a_array: [f32; 3] = a_data.try_into().unwrap();
        let b_array: [f32; 3] = b_data.try_into().unwrap();

        let result = nalgebra_vector_add(a_array, b_array);

        set_direct_buffer_data(&mut env, &result_buffer, &result);

        env.new_local_ref(&result_buffer).unwrap()
    }

    #[allow(non_snake_case, dead_code)]
    pub fn Java_com_kneaf_core_ParallelRustVectorProcessor_glamVectorDotDirect<'a>(
        mut env: JNIEnv<'a>,
        _class: JClass,
        a_buffer: JObject<'a>,
        b_buffer: JObject<'a>,
    ) -> f32 {
        let a_data = get_direct_buffer_data(&mut env, a_buffer);
        let b_data = get_direct_buffer_data(&mut env, b_buffer);

        if a_data.len() != 3 || b_data.len() != 3 {
            env.throw_new("java/lang/IllegalArgumentException", "Buffers must contain 3 floats each").unwrap();
            return 0.0;
        }

        let a_array: [f32; 3] = a_data.try_into().unwrap();
        let b_array: [f32; 3] = b_data.try_into().unwrap();

        glam_vector_dot(a_array, b_array)
    }

    /// Safe memory management functions
    pub fn release_native_buffer(pointer: jlong) {
        MEMORY_MANAGER.deallocate(pointer);
    }

    pub fn allocate_native_buffer(size: jint) -> jlong {
        MEMORY_MANAGER.allocate(size as usize)
    }

    pub fn copy_to_native_buffer(env: &mut JNIEnv, data: &JFloatArray, offset: jint, length: jint) -> Vec<f32> {
        let mut buffer = vec![0.0f32; length as usize];
        env.get_float_array_region(data, offset, &mut buffer).unwrap();
        buffer
    }

    pub fn copy_from_native_buffer(env: &JNIEnv, data: &[f32], result: &JFloatArray, offset: jint) {
        env.set_float_array_region(result, offset, data).unwrap();
    }

    /// Helper functions for JNI conversions

    pub fn convert_jfloat_array_2d(env: &mut JNIEnv, arrays: &JObjectArray, count: i32) -> Vec<[f32; 16]> {
        let mut result = Vec::with_capacity(count as usize);

        for i in 0..count {
            let array_obj: jni::objects::JObject = env.get_object_array_element(arrays, i).unwrap();
            let float_array = JFloatArray::from(array_obj);

            let mut buffer = [0.0f32; 16];
            env.get_float_array_region(&float_array, 0, &mut buffer).unwrap();

            result.push(buffer);
        }

        result
    }

    fn convert_jfloat_vector_2d(env: &mut JNIEnv, vectors: &JObjectArray, count: i32) -> Vec<[f32; 3]> {
        let mut result = Vec::with_capacity(count as usize);

        for i in 0..count {
            let vector_obj: jni::objects::JObject = env.get_object_array_element(vectors, i).unwrap();
            let float_array = JFloatArray::from(vector_obj);

            let mut buffer = [0.0f32; 3];
            env.get_float_array_region(&float_array, 0, &mut buffer).unwrap();

            result.push(buffer);
        }

        result
    }

    #[allow(dead_code)]
    pub fn create_jfloat_array_2d<'a>(env: &mut JNIEnv<'a>, data: Vec<[f32; 16]>) -> JObjectArray<'a> {
        let array_class = env.find_class("[F").unwrap();
        let result_array = env.new_object_array(data.len() as i32, array_class, JObject::null()).unwrap();

        for (i, matrix) in data.iter().enumerate() {
            let java_array = env.new_float_array(16).unwrap();
            env.set_float_array_region(&java_array, 0, matrix).unwrap();
            env.set_object_array_element(&result_array, i as i32, java_array).unwrap();
        }

        result_array
    }

    fn create_jfloat_vector_2d<'a>(env: &mut JNIEnv<'a>, data: Vec<[f32; 3]>) -> JObjectArray<'a> {
        let array_class = env.find_class("[F").unwrap();
        let result_array = env.new_object_array(data.len() as i32, array_class, JObject::null()).unwrap();

        for (i, vector) in data.iter().enumerate() {
            let java_array = env.new_float_array(3).unwrap();
            env.set_float_array_region(&java_array, 0, vector).unwrap();
            env.set_object_array_element(&result_array, i as i32, java_array).unwrap();
        }

        result_array
    }

    #[allow(dead_code)]
    fn create_jfloat_array_1d<'a>(env: &mut JNIEnv<'a>, data: Vec<f32>) -> JFloatArray<'a> {
        let array = env.new_float_array(data.len() as i32).unwrap();
        env.set_float_array_region(&array, 0, &data).unwrap();
        array
    }

    fn get_direct_buffer_data(env: &mut JNIEnv, buffer: JObject) -> Vec<f32> {
        let capacity = env.call_method(&buffer, "capacity", "()I", &[]).unwrap().i().unwrap() as usize;
        let address = env.call_method(&buffer, "address", "()J", &[]).unwrap().j().unwrap();

        // Safety: This assumes the buffer is a direct ByteBuffer containing floats
        unsafe {
            let ptr = address as *const f32;
            std::slice::from_raw_parts(ptr, capacity / 4).to_vec()
        }
    }

    fn set_direct_buffer_data(env: &mut JNIEnv, buffer: &JObject, data: &[f32]) {
        let address = env.call_method(&buffer, "address", "()J", &[]).unwrap().j().unwrap();

        // Safety: This assumes the buffer is a direct ByteBuffer with enough capacity
        unsafe {
            let ptr = address as *mut f32;
            std::ptr::copy_nonoverlapping(data.as_ptr(), ptr, data.len());
        }
    }

    /// Original performance functions (unchanged)
    // Internal helpers — use Rust ABI to avoid improper_ctypes warnings.
    pub fn nalgebra_matrix_mul(a: [f32; 16], b: [f32; 16]) -> [f32; 16] {
        let ma = na::Matrix4::<f32>::from_row_slice(&a);
        let mb = na::Matrix4::<f32>::from_row_slice(&b);
        let res = ma * mb;
        res.as_slice().try_into().unwrap()
    }

    pub fn nalgebra_vector_add(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
        let va = na::Vector3::<f32>::from_row_slice(&a);
        let vb = na::Vector3::<f32>::from_row_slice(&b);
        let res = va + vb;
        res.as_slice().try_into().unwrap()
    }

    pub fn glam_vector_dot(a: [f32; 3], b: [f32; 3]) -> f32 {
        let va = Vec3::from(a);
        let vb = Vec3::from(b);
        va.dot(vb)
    }

    pub fn glam_vector_cross(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
        let va = Vec3::from(a);
        let vb = Vec3::from(b);
        let res = va.cross(vb);
        res.to_array()
    }

/// Batch processing functions (reintroduced)
pub fn batch_nalgebra_matrix_mul(matrices_a: Vec<[f32; 16]>, matrices_b: Vec<[f32; 16]>) -> Vec<[f32; 16]> {
    matrices_a.par_iter().zip(matrices_b.par_iter()).map(|(a, b)| nalgebra_matrix_mul(*a, *b)).collect()
}

pub fn batch_nalgebra_vector_add(vectors_a: Vec<[f32; 3]>, vectors_b: Vec<[f32; 3]>) -> Vec<[f32; 3]> {
    vectors_a.par_iter().zip(vectors_b.par_iter()).map(|(a, b)| nalgebra_vector_add(*a, *b)).collect()
}

    #[allow(non_snake_case)]
    pub fn Java_com_kneaf_core_ParallelRustVectorProcessor_batchNalgebraMatrixMulEnhanced<'a>(
        mut env: JNIEnv<'a>,
        _class: JClass,
        matrices_a: JObjectArray<'a>,
        matrices_b: JObjectArray<'a>,
        count: i32,
    ) -> JObjectArray<'a> {
        let a = convert_jfloat_array_2d(&mut env, &matrices_a, count);
        let b = convert_jfloat_array_2d(&mut env, &matrices_b, count);
        let res = batch_nalgebra_matrix_mul(a, b);
        create_jfloat_array_2d(&mut env, res)
    }

    #[allow(non_snake_case)]
    pub fn Java_com_kneaf_core_ParallelRustVectorProcessor_batchNalgebraVectorAddEnhanced<'a>(
        mut env: JNIEnv<'a>,
        _class: JClass,
        vectors_a: JObjectArray<'a>,
        vectors_b: JObjectArray<'a>,
        count: i32,
    ) -> JObjectArray<'a> {
        let a = convert_jfloat_vector_2d(&mut env, &vectors_a, count);
        let b = convert_jfloat_vector_2d(&mut env, &vectors_b, count);
        let res = batch_nalgebra_vector_add(a, b);
        create_jfloat_vector_2d(&mut env, res)
    }
