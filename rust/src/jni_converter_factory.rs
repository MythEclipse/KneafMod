use std::sync::Arc;

/// JNI converter trait
pub trait JniConverter {
    fn convert_to_java(&self, data: &[u8]) -> Result<Vec<u8>, String>;
    fn convert_from_java(&self, data: &[u8]) -> Result<Vec<u8>, String>;
}

/// JNI converter factory for creating converters
pub struct JniConverterFactory {
    converters: std::collections::HashMap<String, Box<dyn JniConverter + Send + Sync>>,
}

impl JniConverterFactory {
    pub fn new() -> Self {
        Self {
            converters: std::collections::HashMap::new(),
        }
    }

    pub fn register_converter(&mut self, name: String, converter: Box<dyn JniConverter + Send + Sync>) {
        self.converters.insert(name, converter);
    }

    pub fn get_converter(&self, name: &str) -> Option<&Box<dyn JniConverter + Send + Sync>> {
        self.converters.get(name)
    }

    pub fn create_default_converter(&self) -> Result<Box<dyn JniConverter + Send + Sync>, String> {
        Ok(Box::new(DefaultJniConverter))
    }
}

impl Default for JniConverterFactory {
    fn default() -> Self {
        Self::new()
    }
}

/// Default JNI converter implementation
pub struct DefaultJniConverter;

impl JniConverter for DefaultJniConverter {
    fn convert_to_java(&self, data: &[u8]) -> Result<Vec<u8>, String> {
        Ok(data.to_vec())
    }

    fn convert_from_java(&self, data: &[u8]) -> Result<Vec<u8>, String> {
        Ok(data.to_vec())
    }
}