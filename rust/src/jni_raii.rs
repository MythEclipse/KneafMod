use jni::{JNIEnv, objects::JObject};
use std::ops::Deref;

/// RAII wrapper for JNI global references to prevent memory leaks
pub struct JniGlobalRef<'a> {
    env: &'a JNIEnv<'a>,
    global_ref: Option<JObject<'a>>,
}

impl<'a> JniGlobalRef<'a> {
    /// Create a new global reference from a local reference
    pub fn new(env: &'a JNIEnv<'a>, local_ref: JObject<'a>) -> Result<Self, String> {
        match env.new_global_ref(local_ref) {
            Ok(global_ref) => Ok(JniGlobalRef {
                env,
                global_ref: Some(global_ref),
            }),
            Err(e) => Err(format!("Failed to create global ref: {}", e)),
        }
    }

    /// Get the underlying global reference
    pub fn as_obj(&self) -> &JObject<'a> {
        self.global_ref.as_ref().unwrap()
    }
}

impl<'a> Deref for JniGlobalRef<'a> {
    type Target = JObject<'a>;

    fn deref(&self) -> &Self::Target {
        self.as_obj()
    }
}

impl<'a> Drop for JniGlobalRef<'a> {
    fn drop(&mut self) {
        if let Some(global_ref) = self.global_ref.take() {
            // Safely delete the global reference
            unsafe {
                if let Err(e) = self.env.delete_global_ref(global_ref) {
                    eprintln!("Warning: Failed to delete global ref: {}", e);
                }
            }
        }
    }
}

/// RAII wrapper for JNI local references to ensure proper cleanup
pub struct JniLocalRef<'a> {
    env: &'a JNIEnv<'a>,
    local_ref: Option<JObject<'a>>,
}

impl<'a> JniLocalRef<'a> {
    /// Create a new local reference wrapper
    pub fn new(env: &'a JNIEnv<'a>, local_ref: JObject<'a>) -> Self {
        JniLocalRef {
            env,
            local_ref: Some(local_ref),
        }
    }

    /// Get the underlying local reference
    pub fn as_obj(&self) -> &JObject<'a> {
        self.local_ref.as_ref().unwrap()
    }

    /// Manually delete the local reference (useful for early cleanup)
    pub fn delete(mut self) {
        if let Some(local_ref) = self.local_ref.take() {
            unsafe {
                if let Err(e) = self.env.delete_local_ref(local_ref) {
                    eprintln!("Warning: Failed to delete local ref: {}", e);
                }
            }
        }
    }
}

impl<'a> Deref for JniLocalRef<'a> {
    type Target = JObject<'a>;

    fn deref(&self) -> &Self::Target {
        self.as_obj()
    }
}

impl<'a> Drop for JniLocalRef<'a> {
    fn drop(&mut self) {
        if let Some(local_ref) = self.local_ref.take() {
            unsafe {
                if let Err(e) = self.env.delete_local_ref(local_ref) {
                    eprintln!("Warning: Failed to delete local ref: {}", e);
                }
            }
        }
    }
}