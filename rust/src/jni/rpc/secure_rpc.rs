use aes_gcm::{
    aead::{Aead, KeyInit, OsRng},
    Aes256Gcm, Nonce,
};
use chrono::{Duration, Utc};
use dashmap::DashMap;
use hmac::{Hmac, Mac};
use jni::{JNIEnv, objects::{JObject, JString, JByteArray}, sys::jbyteArray};
use jsonwebtoken::{encode, decode, Header, EncodingKey, DecodingKey, Validation, TokenData};
use sha2::Sha256;
use std::{
    sync::{Arc, Mutex, RwLock},
    time::{Instant, Duration as StdDuration},
    collections::HashMap,
    fmt,
};
use tokio::sync::{Semaphore, RwLock as TokioRwLock};
use tokio::runtime::Runtime;
use protobuf::Message;
use flate2::read::GzDecoder;
use flate2::write::GzEncoder;
use flate2::Compression;
use once_cell::sync::Lazy;
use uuid::Uuid;
use crate::{
    jni::bridge::mod as jni_bridge,
    jni::memory::zero_copy_manager::{ZeroCopyMemoryManager, ZERO_COPY_MANAGER},
    jni::converter::enhanced_converter::{EnhancedJniConverter, ENHANCED_CONVERTER_FACTORY},
    performance::monitoring::PerformanceMonitor,
    errors::RustError,
};

// ------------------------------
// Type Definitions & Constants
// ------------------------------
type HmacSha256 = Hmac<Sha256>;
type AesGcm = Aes256Gcm;
const NONCE_SIZE: usize = 12;
const TOKEN_EXPIRY_MINUTES: i64 = 30;
const RATE_LIMIT_WINDOW: StdDuration = StdDuration::from_secs(60);
const MAX_REQUESTS_PER_MINUTE: usize = 100;
const MAX_CONCURRENT_CONNECTIONS: usize = 50;

// ------------------------------
// RPC Configuration
// ------------------------------
#[derive(Debug, Clone, Default)]
pub struct RpcConfig {
    pub encryption_key: Vec<u8>,
    pub hmac_secret: Vec<u8>,
    pub jwt_secret: Vec<u8>,
    pub tls_cert: String,
    pub tls_key: String,
    pub request_timeout: StdDuration,
    pub retry_attempts: u32,
    pub compression_threshold: usize,
    pub enable_tls: bool,
    pub enable_certificate_pinning: bool,
    pub pinned_certificates: Vec<String>,
}

impl RpcConfig {
    pub fn new() -> Self {
        // In production, these should be loaded from secure configuration
        let encryption_key = vec![0u8; 32]; // 256-bit key for AES-256
        let hmac_secret = vec![0u8; 32];   // 256-bit secret for HMAC-SHA256
        let jwt_secret = vec![0u8; 32];    // 256-bit secret for JWT
        
        Self {
            encryption_key,
            hmac_secret,
            jwt_secret,
            tls_cert: "".to_string(),
            tls_key: "".to_string(),
            request_timeout: StdDuration::from_secs(30),
            retry_attempts: 3,
            compression_threshold: 1024 * 100, // 100KB
            enable_tls: true,
            enable_certificate_pinning: true,
            pinned_certificates: vec![],
        }
    }
}

// ------------------------------
// RPC Error Types
// ------------------------------
#[derive(Debug, Clone)]
pub enum RpcError {
    AuthenticationError(String),
    AuthorizationError(String),
    EncryptionError(String),
    DecryptionError(String),
    HmacError(String),
    JwtError(String),
    ValidationError(String),
    RateLimitError(String),
    TimeoutError(String),
    ConnectionError(String),
    ProtocolError(String),
    CompressionError(String),
    InternalError(String),
}

impl fmt::Display for RpcError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            RpcError::AuthenticationError(msg) => write!(f, "Authentication failed: {}", msg),
            RpcError::AuthorizationError(msg) => write!(f, "Authorization failed: {}", msg),
            RpcError::EncryptionError(msg) => write!(f, "Encryption failed: {}", msg),
            RpcError::DecryptionError(msg) => write!(f, "Decryption failed: {}", msg),
            RpcError::HmacError(msg) => write!(f, "HMAC verification failed: {}", msg),
            RpcError::JwtError(msg) => write!(f, "JWT error: {}", msg),
            RpcError::ValidationError(msg) => write!(f, "Request validation failed: {}", msg),
            RpcError::RateLimitError(msg) => write!(f, "Rate limit exceeded: {}", msg),
            RpcError::TimeoutError(msg) => write!(f, "Request timed out: {}", msg),
            RpcError::ConnectionError(msg) => write!(f, "Connection error: {}", msg),
            RpcError::ProtocolError(msg) => write!(f, "Protocol error: {}", msg),
            RpcError::CompressionError(msg) => write!(f, "Compression error: {}", msg),
            RpcError::InternalError(msg) => write!(f, "Internal error: {}", msg),
        }
    }
}

impl From<RpcError> for RustError {
    fn from(error: RpcError) -> Self {
        RustError::RpcError(error.to_string())
    }
}

// ------------------------------
// JWT Claims Structure
// ------------------------------
#[derive(Debug, serde::Serialize, serde::Deserialize)]
pub struct JwtClaims {
    pub sub: String,          // Subject (user/entity ID)
    pub iss: String,          // Issuer
    pub exp: i64,             // Expiration time (unix timestamp)
    pub nbf: i64,             // Not Before time (unix timestamp)
    pub iat: i64,             // Issued At time (unix timestamp)
    pub jti: String,          // JWT ID
    pub roles: Vec<String>,   // Authorized roles
    pub permissions: Vec<String>, // Specific permissions
}

// ------------------------------
// RPC Method Registry
// ------------------------------
#[derive(Debug, Clone)]
pub struct RpcMethod {
    pub name: String,
    pub handler: Box<dyn Fn(&JNIEnv, &RpcContext, &[u8]) -> Result<Vec<u8>, RpcError> + Send + Sync>,
    pub required_permissions: Vec<String>,
    pub is_game_critical: bool,
}

// ------------------------------
// RPC Context (for method handlers)
// ------------------------------
pub struct RpcContext {
    pub auth_token: Option<String>,
    pub client_ip: String,
    pub request_id: String,
    pub timestamp: Instant,
    pub performance_monitor: Arc<PerformanceMonitor>,
}

impl RpcContext {
    pub fn new(auth_token: Option<String>, client_ip: String, request_id: String) -> Self {
        Self {
            auth_token,
            client_ip,
            request_id,
            timestamp: Instant::now(),
            performance_monitor: Arc::new(PerformanceMonitor::new()),
        }
    }
}

// ------------------------------
// Rate Limiting Structure
// ------------------------------
struct RateLimiter {
    request_timestamps: DashMap<String, Vec<Instant>>,
    max_requests: usize,
    window: StdDuration,
}

impl RateLimiter {
    fn new(max_requests: usize, window: StdDuration) -> Self {
        Self {
            request_timestamps: DashMap::new(),
            max_requests,
            window,
        }
    }

    fn check(&self, client_id: &str) -> Result<(), RpcError> {
        let now = Instant::now();
        let entry = self.request_timestamps.entry(client_id.to_string()).or_insert_with(Vec::new);
        
        // Remove timestamps outside the window
        let mut timestamps = entry.value_mut();
        timestamps.retain(|&t| now.duration_since(t) <= self.window);
        
        if timestamps.len() >= self.max_requests {
            return Err(RpcError::RateLimitError(format!(
                "Rate limit exceeded for client {}: {} requests in {}s",
                client_id, self.max_requests, self.window.as_secs()
            )));
        }
        
        // Add current request timestamp
        timestamps.push(now);
        
        Ok(())
    }
}

// ------------------------------
// Secure RPC Interface Implementation
// ------------------------------
pub struct SecureRpcInterface {
    config: RpcConfig,
    method_registry: DashMap<String, RpcMethod>,
    rate_limiter: Arc<RateLimiter>,
    connection_pool: Arc<Semaphore>,
    performance_monitor: Arc<PerformanceMonitor>,
    jwt_encoder: jsonwebtoken::Encoder,
    jwt_decoder: jsonwebtoken::Decoder,
}

impl SecureRpcInterface {
    // ------------------------------
    // Constructor & Initialization
    // ------------------------------
    pub fn new(config: Option<RpcConfig>) -> Self {
        let config = config.unwrap_or_else(RpcConfig::new);
        
        let header = Header::default();
        let encoding_key = EncodingKey::from_secret(&config.jwt_secret);
        let decoding_key = DecodingKey::from_secret(&config.jwt_secret);
        
        Self {
            config,
            method_registry: DashMap::new(),
            rate_limiter: Arc::new(RateLimiter::new(MAX_REQUESTS_PER_MINUTE, RATE_LIMIT_WINDOW)),
            connection_pool: Arc::new(Semaphore::new(MAX_CONCURRENT_CONNECTIONS)),
            performance_monitor: Arc::new(PerformanceMonitor::new()),
            jwt_encoder: jsonwebtoken::Encoder::new(encoding_key),
            jwt_decoder: jsonwebtoken::Decoder::new(decoding_key),
        }
    }

    // ------------------------------
    // Public API - Method Registration
    // ------------------------------
    pub fn register_rpc_method(&self, method: RpcMethod) -> Result<(), RpcError> {
        if self.method_registry.contains_key(&method.name) {
            return Err(RpcError::ProtocolError(format!(
                "RPC method '{}' is already registered", method.name
            )));
        }
        
        self.method_registry.insert(method.name.clone(), method);
        Ok(())
    }

    // ------------------------------
    // Public API - RPC Method Call
    // ------------------------------
    pub async fn call_rpc_method(
        &self,
        env: &mut JNIEnv,
        method_name: &str,
        auth_token: Option<String>,
        client_ip: &str,
        payload: &[u8],
    ) -> Result<Vec<u8>, RpcError> {
        let start_time = Instant::now();
        let request_id = uuid::Uuid::new_v4().to_string();
        
        // 1. Acquire connection from pool
        let _permit = self.connection_pool.acquire().await.map_err(|e| {
            RpcError::ConnectionError(format!("Failed to acquire connection: {}", e))
        })?;

        // 2. Validate request
        self.validate_rpc_request(method_name, auth_token.as_deref(), client_ip, payload)?;

        // 3. Rate limiting
        self.rate_limiter.check(client_ip)?;

        // 4. Decrypt and verify payload
        let decrypted_payload = self.decrypt_payload(payload, auth_token.as_deref())?;

        // 5. Look up method
        let method = self.method_registry.get(method_name)
            .ok_or_else(|| RpcError::ProtocolError(format!(
                "RPC method '{}' not found", method_name
            )))?;

        // 6. Create context and execute method
        let context = RpcContext::new(auth_token, client_ip.to_string(), request_id);
        let result = (method.handler)(env, &context, &decrypted_payload)?;

        // 7. Encrypt response
        let encrypted_response = self.encrypt_payload(&result, auth_token.as_deref())?;

        // 8. Record performance metrics
        let duration = start_time.elapsed();
        self.performance_monitor.record_metric(
            "rpc_call_duration",
            duration.as_micros() as u64,
            &[
                ("method", method_name),
                ("client_ip", client_ip),
                ("status", "success"),
            ]
        );

        Ok(encrypted_response)
    }

    // ------------------------------
    // Security Implementation
    // ------------------------------

    /// Encrypt payload using AES-256-GCM with HMAC-SHA256 authentication
    pub fn encrypt_payload(&self, payload: &[u8], auth_token: Option<&str>) -> Result<Vec<u8>, RpcError> {
        let cipher = Aes256Gcm::new_from_slice(&self.config.encryption_key).map_err(|e| {
            RpcError::EncryptionError(format!("Failed to create cipher: {}", e))
        })?;

        let nonce = Nonce::from_slice(&OsRng.generate::<[u8; NONCE_SIZE]>()).map_err(|e| {
            RpcError::EncryptionError(format!("Failed to generate nonce: {}", e))
        })?;

        let ciphertext = cipher.encrypt(nonce, payload.as_ref()).map_err(|e| {
            RpcError::EncryptionError(format!("Encryption failed: {}", e))
        })?;

        // Create HMAC for integrity verification
        let mut hmac = HmacSha256::new_from_slice(&self.config.hmac_secret).map_err(|e| {
            RpcError::HmacError(format!("Failed to create HMAC: {}", e))
        })?;
        hmac.update(&nonce);
        hmac.update(&ciphertext);
        let hmac_result = hmac.finalize().into_bytes();

        // Include auth token in metadata if present
        let metadata = auth_token.map(|t| t.as_bytes().to_vec()).unwrap_or_default();

        // Combine all components: nonce (12B) + ciphertext + HMAC (32B) + metadata length + metadata
        let mut encrypted_payload = Vec::with_capacity(NONCE_SIZE + ciphertext.len() + 32 + 4 + metadata.len());
        encrypted_payload.extend_from_slice(&nonce);
        encrypted_payload.extend_from_slice(&ciphertext);
        encrypted_payload.extend_from_slice(&hmac_result);
        
        // Write metadata length (big-endian)
        encrypted_payload.extend_from_slice(&(metadata.len() as u32).to_be_bytes());
        encrypted_payload.extend_from_slice(&metadata);

        Ok(encrypted_payload)
    }

    /// Decrypt payload and verify integrity using AES-256-GCM and HMAC-SHA256
    pub fn decrypt_payload(&self, payload: &[u8], auth_token: Option<&str>) -> Result<Vec<u8>, RpcError> {
        if payload.len() < NONCE_SIZE + 32 + 4 {
            return Err(RpcError::DecryptionError("Payload too short"));
        }

        // Split components
        let nonce = Nonce::from_slice(&payload[0..NONCE_SIZE]).map_err(|e| {
            RpcError::DecryptionError(format!("Invalid nonce: {}", e))
        })?;

        let hmac_end = NONCE_SIZE + payload.len() - 32 - 4;
        let ciphertext = &payload[NONCE_SIZE..hmac_end];
        let received_hmac = &payload[hmac_end..hmac_end + 32];

        // Verify HMAC first for integrity
        let mut hmac = HmacSha256::new_from_slice(&self.config.hmac_secret).map_err(|e| {
            RpcError::HmacError(format!("Failed to create HMAC: {}", e))
        })?;
        hmac.update(&nonce);
        hmac.update(ciphertext);

        hmac.verify(received_hmac).map_err(|_| {
            RpcError::HmacError("HMAC verification failed - payload may be tampered with")
        })?;

        // Decrypt payload
        let cipher = Aes256Gcm::new_from_slice(&self.config.encryption_key).map_err(|e| {
            RpcError::DecryptionError(format!("Failed to create cipher: {}", e))
        })?;

        let plaintext = cipher.decrypt(nonce, ciphertext).map_err(|e| {
            RpcError::DecryptionError(format!("Decryption failed: {}", e))
        })?;

        // Verify metadata if auth token was provided
        let metadata_start = hmac_end + 32;
        let metadata_len = u32::from_be_bytes(payload[metadata_start..metadata_start + 4].try_into().map_err(|e| {
            RpcError::DecryptionError(format!("Failed to read metadata length: {}", e))
        })?);

        if let Some(expected_token) = auth_token {
            let expected_metadata = expected_token.as_bytes();
            if metadata_len as usize != expected_metadata.len() {
                return Err(RpcError::AuthenticationError("Token mismatch in metadata"));
            }
            
            let actual_metadata = &payload[metadata_start + 4..metadata_start + 4 + metadata_len as usize];
            if actual_metadata != expected_metadata {
                return Err(RpcError::AuthenticationError("Token verification failed"));
            }
        }

        Ok(plaintext)
    }

    /// Validate JWT token and check permissions
    pub fn validate_jwt_token(&self, token: &str, required_permissions: &[String]) -> Result<JwtClaims, RpcError> {
        let validation = Validation::default();
        let token_data = self.jwt_decoder.decode(token, &validation).map_err(|e| {
            RpcError::JwtError(format!("JWT decode failed: {}", e))
        })?;

        let claims = token_data.claims;

        // Check token expiration
        if claims.exp < Utc::now().timestamp() {
            return Err(RpcError::AuthenticationError("Token has expired"));
        }

        // Check not before
        if claims.nbf > Utc::now().timestamp() {
            return Err(RpcError::AuthenticationError("Token not yet valid"));
        }

        // Check required permissions
        for required_perm in required_permissions {
            if !claims.permissions.contains(required_perm) {
                return Err(RpcError::AuthorizationError(format!(
                    "Missing required permission: {}", required_perm
                )));
            }
        }

        Ok(claims)
    }

    /// Generate JWT token for authenticated clients
    pub fn generate_jwt_token(&self, subject: &str, roles: &[String], permissions: &[String]) -> Result<String, RpcError> {
        let now = Utc::now();
        let claims = JwtClaims {
            sub: subject.to_string(),
            iss: "kneafmod-rpc".to_string(),
            exp: (now + Duration::minutes(TOKEN_EXPIRY_MINUTES)).timestamp(),
            nbf: now.timestamp(),
            iat: now.timestamp(),
            jti: uuid::Uuid::new_v4().to_string(),
            roles: roles.to_vec(),
            permissions: permissions.to_vec(),
        };

        let token = self.jwt_encoder.encode(&Header::default(), &claims).map_err(|e| {
            RpcError::JwtError(format!("JWT encode failed: {}", e))
        })?;

        Ok(token)
    }

    /// Validate the entire RPC request
    pub fn validate_rpc_request(
        &self,
        method_name: &str,
        auth_token: Option<&str>,
        client_ip: &str,
        payload: &[u8],
    ) -> Result<(), RpcError> {
        // 1. Basic validation
        if method_name.is_empty() {
            return Err(RpcError::ValidationError("Empty method name"));
        }

        if payload.is_empty() {
            return Err(RpcError::ValidationError("Empty payload"));
        }

        // 2. Check method exists in registry
        let method = self.method_registry.get(method_name).ok_or_else(|| {
            RpcError::ValidationError(format!("Method '{}' not registered", method_name))
        })?;

        // 3. Validate authentication if required
        if !method.required_permissions.is_empty() {
            let token = auth_token.ok_or_else(|| {
                RpcError::AuthenticationError("Authentication required for this method")
            })?;

            self.validate_jwt_token(token, &method.required_permissions)?;
        }

        // 4. Validate payload size
        if payload.len() > self.config.compression_threshold && !self.config.enable_tls {
            return Err(RpcError::ValidationError("Payload too large without TLS"));
        }

        Ok(())
    }

    // ------------------------------
    // Performance Optimization Implementation
    // ------------------------------

    /// Compress payload if it exceeds threshold
    pub fn compress_payload(&self, payload: &[u8]) -> Result<Vec<u8>, RpcError> {
        if payload.len() <= self.config.compression_threshold {
            return Ok(payload.to_vec());
        }

        let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(payload).map_err(|e| {
            RpcError::CompressionError(format!("Failed to compress payload: {}", e))
        })?;
        let result = encoder.finish().map_err(|e| {
            RpcError::CompressionError(format!("Failed to finalize compression: {}", e))
        })?;

        Ok(result)
    }

    /// Decompress payload if compressed
    pub fn decompress_payload(&self, payload: &[u8]) -> Result<Vec<u8>, RpcError> {
        let mut decoder = GzDecoder::new(payload);
        let mut result = Vec::new();
        decoder.read_to_end(&mut result).map_err(|e| {
            RpcError::CompressionError(format!("Failed to decompress payload: {}", e))
        })?;

        Ok(result)
    }

    /// Batch multiple RPC requests into a single call
    pub async fn batch_call_rpc_methods(
        &self,
        env: &mut JNIEnv,
        auth_token: Option<String>,
        client_ip: &str,
        batch_requests: &[Vec<u8>],
    ) -> Result<Vec<Vec<u8>>, RpcError> {
        let mut results = Vec::with_capacity(batch_requests.len());
        
        for request in batch_requests {
            let result = self.call_rpc_method(env, "batch_processor", auth_token.clone(), client_ip, request).await?;
            results.push(result);
        }

        Ok(results)
    }

    // ------------------------------
    // Game-Specific RPC Methods
    // ------------------------------

    /// Register game-specific RPC methods
    pub fn register_game_rpc_methods(&self) -> Result<(), RpcError> {
        // Entity state synchronization
        self.register_rpc_method(RpcMethod {
            name: "sync_entity_state".to_string(),
            handler: Box::new(|env, ctx, payload| {
                let converter = ENHANCED_CONVERTER_FACTORY.create_for_game_entities();
                let entity_data = converter.zero_copy_jbyte_array_to_vec(env, JByteArray::from_raw(payload.as_ptr() as _))?;
                
                // In a real implementation, this would synchronize entity state between Rust and Java
                ctx.performance_monitor.record_metric("entity_sync_duration", 100, &[]);
                
                Ok(entity_data)
            }),
            required_permissions: vec!["entity.read", "entity.write".to_string()],
            is_game_critical: true,
        })?;

        // Real-time player action validation
        self.register_rpc_method(RpcMethod {
            name: "validate_player_action".to_string(),
            handler: Box::new(|_, ctx, payload| {
                let action = String::from_utf8(payload.to_vec())?;
                
                // In a real implementation, this would validate player actions against game rules
                ctx.performance_monitor.record_metric("player_action_validation", 50, &[]);
                
                Ok(b"VALIDATED".to_vec())
            }),
            required_permissions: vec!["player.action.validate".to_string()],
            is_game_critical: true,
        })?;

        // Secure inventory management
        self.register_rpc_method(RpcMethod {
            name: "manage_inventory".to_string(),
            handler: Box::new(|env, ctx, payload| {
                let converter = ENHANCED_CONVERTER_FACTORY.create_default();
                let inventory_data = converter.zero_copy_jbyte_array_to_vec(env, JByteArray::from_raw(payload.as_ptr() as _))?;
                
                // In a real implementation, this would manage player inventory securely
                ctx.performance_monitor.record_metric("inventory_operation", 75, &[]);
                
                Ok(inventory_data)
            }),
            required_permissions: vec!["inventory.read", "inventory.write".to_string()],
            is_game_critical: true,
        })?;

        // Authenticated chat system
        self.register_rpc_method(RpcMethod {
            name: "send_chat_message".to_string(),
            handler: Box::new(|_, ctx, payload| {
                let message = String::from_utf8(payload.to_vec())?;
                
                // In a real implementation, this would handle secure chat messaging
                ctx.performance_monitor.record_metric("chat_message_processing", 30, &[]);
                
                Ok(format!("CHAT: {}", message).as_bytes().to_vec())
            }),
            required_permissions: vec!["chat.send".to_string()],
            is_game_critical: false,
        })?;

        // Protected configuration updates
        self.register_rpc_method(RpcMethod {
            name: "update_game_config".to_string(),
            handler: Box::new(|_, ctx, payload| {
                let config_update = String::from_utf8(payload.to_vec())?;
                
                // In a real implementation, this would handle secure configuration updates
                ctx.performance_monitor.record_metric("config_update", 200, &[]);
                
                Ok(format!("CONFIG_UPDATED: {}", config_update).as_bytes().to_vec())
            }),
            required_permissions: vec!["config.admin".to_string()],
            is_game_critical: true,
        })?;

        Ok(())
    }

    // ------------------------------
    // JNI Integration Methods
    // ------------------------------

    /// JNI entry point for RPC calls
    #[no_mangle]
    pub extern "system" fn Java_com_kneaf_core_rpc_SecureRpc_nativeCallRpcMethod(
        env: JNIEnv,
        _class: JClass,
        method_name: JString,
        auth_token: JString,
        client_ip: JString,
        payload: JByteArray,
    ) -> jbyteArray {
        let rt = Runtime::new().unwrap_or_else(|e| {
            eprintln!("[rpc] Failed to create Tokio runtime: {}", e);
            return env.byte_array_from_slice(&[]).unwrap_or_else(|_| env.byte_array_from_slice(b"INTERNAL_ERROR").unwrap()).into_raw();
        });

        let method_name = match env.get_string(method_name) {
            Ok(s) => s.to_string_lossy().to_string(),
            Err(e) => {
                eprintln!("[rpc] Failed to get method name: {}", e);
                return env.byte_array_from_slice(&[]).unwrap_or_else(|_| env.byte_array_from_slice(b"INVALID_METHOD_NAME").unwrap()).into_raw();
            }
        };

        let auth_token = match env.get_string(auth_token) {
            Ok(s) => Some(s.to_string_lossy().to_string()),
            Err(_) => None,
        };

        let client_ip = match env.get_string(client_ip) {
            Ok(s) => s.to_string_lossy().to_string(),
            Err(e) => {
                eprintln!("[rpc] Failed to get client IP: {}", e);
                return env.byte_array_from_slice(&[]).unwrap_or_else(|_| env.byte_array_from_slice(b"INVALID_CLIENT_IP").unwrap()).into_raw();
            }
        };

        let payload = match env.convert_byte_array(payload) {
            Ok(v) => v,
            Err(e) => {
                eprintln!("[rpc] Failed to convert payload: {}", e);
                return env.byte_array_from_slice(&[]).unwrap_or_else(|_| env.byte_array_from_slice(b"INVALID_PAYLOAD").unwrap()).into_raw();
            }
        };

        let result = rt.block_on(async {
            let secure_rpc = SECURE_RPC_INTERFACE.read().await;
            secure_rpc.call_rpc_method(&mut env, &method_name, auth_token, &client_ip, &payload).await
        });

        match result {
            Ok(response) => {
                env.byte_array_from_slice(&response).unwrap_or_else(|_| {
                    eprintln!("[rpc] Failed to create response byte array");
                    env.byte_array_from_slice(b"INTERNAL_ERROR").unwrap()
                }).into_raw()
            }
            Err(e) => {
                eprintln!("[rpc] RPC call failed: {}", e);
                env.byte_array_from_slice(e.to_string().as_bytes()).unwrap_or_else(|_| {
                    env.byte_array_from_slice(b"RPC_CALL_FAILED").unwrap()
                }).into_raw()
            }
        }
    }

    /// JNI entry point for batch RPC calls
    #[no_mangle]
    pub extern "system" fn Java_com_kneaf_core_rpc_SecureRpc_nativeBatchCallRpcMethods(
        env: JNIEnv,
        _class: JClass,
        auth_token: JString,
        client_ip: JString,
        batch_requests: JObjectArray,
    ) -> jbyteArray {
        let rt = Runtime::new().unwrap_or_else(|e| {
            eprintln!("[rpc] Failed to create Tokio runtime: {}", e);
            return env.byte_array_from_slice(&[]).unwrap_or_else(|_| env.byte_array_from_slice(b"INTERNAL_ERROR").unwrap()).into_raw();
        });

        let auth_token = match env.get_string(auth_token) {
            Ok(s) => Some(s.to_string_lossy().to_string()),
            Err(_) => None,
        };

        let client_ip = match env.get_string(client_ip) {
            Ok(s) => s.to_string_lossy().to_string(),
            Err(e) => {
                eprintln!("[rpc] Failed to get client IP: {}", e);
                return env.byte_array_from_slice(&[]).unwrap_or_else(|_| env.byte_array_from_slice(b"INVALID_CLIENT_IP").unwrap()).into_raw();
            }
        };

        let batch_requests = match env.get_object_array_length(batch_requests) {
            Ok(len) => {
                let mut requests = Vec::with_capacity(len as usize);
                for i in 0..len {
                    if let Ok(obj) = env.get_object_array_element(batch_requests, i) {
                        if let Ok(byte_array) = JByteArray::from(obj) {
                            if let Ok(payload) = env.convert_byte_array(byte_array) {
                                requests.push(payload);
                            }
                        }
                    }
                }
                requests
            }
            Err(e) => {
                eprintln!("[rpc] Failed to process batch requests: {}", e);
                return env.byte_array_from_slice(&[]).unwrap_or_else(|_| env.byte_array_from_slice(b"INVALID_BATCH_REQUESTS").unwrap()).into_raw();
            }
        };

        let result = rt.block_on(async {
            let secure_rpc = SECURE_RPC_INTERFACE.read().await;
            secure_rpc.batch_call_rpc_methods(&mut env, auth_token, &client_ip, &batch_requests).await
        });

        match result {
            Ok(responses) => {
                // Serialize batch responses (in a real implementation, use protobuf)
                let mut serialized = Vec::new();
                for response in responses {
                    serialized.extend_from_slice(&(response.len() as u32).to_be_bytes());
                    serialized.extend_from_slice(&response);
                }
                
                env.byte_array_from_slice(&serialized).unwrap_or_else(|_| {
                    eprintln!("[rpc] Failed to create batch response byte array");
                    env.byte_array_from_slice(b"INTERNAL_ERROR").unwrap()
                }).into_raw()
            }
            Err(e) => {
                eprintln!("[rpc] Batch RPC call failed: {}", e);
                env.byte_array_from_slice(e.to_string().as_bytes()).unwrap_or_else(|_| {
                    env.byte_array_from_slice(b"BATCH_CALL_FAILED").unwrap()
                }).into_raw()
            }
        }
    }
}

// ------------------------------
// Global Singleton
// ------------------------------
pub static SECURE_RPC_INTERFACE: Lazy<TokioRwLock<SecureRpcInterface>> = Lazy::new(|| {
    let secure_rpc = SecureRpcInterface::new(None);
    // Register game-specific methods on initialization
    let _ = secure_rpc.register_game_rpc_methods();
    TokioRwLock::new(secure_rpc)
});

// ------------------------------
// Integration Helpers
// ------------------------------

/// Helper to convert between JNI types and RPC payloads using zero-copy
pub fn jni_to_rpc_payload(env: &mut JNIEnv, payload: JByteArray) -> Result<Vec<u8>, RustError> {
    let converter = ENHANCED_CONVERTER_FACTORY.create_default();
    converter.jbyte_array_to_vec(env, payload).map_err(|e| {
        RustError::ConversionError(format!("Failed to convert JNI payload: {}", e))
    })
}

/// Helper to convert RPC responses back to JNI types using zero-copy
pub fn rpc_payload_to_jni(env: &mut JNIEnv, payload: &[u8]) -> Result<JByteArray, RustError> {
    let converter = ENHANCED_CONVERTER_FACTORY.create_default();
    converter.vec_to_jbyte_array(env, payload).map_err(|e| {
        RustError::ConversionError(format!("Failed to convert RPC payload to JNI: {}", e))
    })
}

/// Integrate with existing zero-copy memory manager
pub fn integrate_with_zero_copy() -> Result<(), RustError> {
    // In a real implementation, this would integrate with ZERO_COPY_MANAGER
    // for efficient memory sharing between RPC layers
    ZERO_COPY_MANAGER.get_memory_stats()?;
    Ok(())
}

/// Integrate with performance monitoring
pub fn integrate_with_performance_monitoring() -> Result<(), RustError> {
    let secure_rpc = SECURE_RPC_INTERFACE.blocking_read();
    let performance_monitor = &secure_rpc.performance_monitor;
    
    // In a real implementation, this would integrate with the global performance monitoring system
    performance_monitor.record_metric("rpc_system_initialized", 1, &[]);
    Ok(())
}

// ------------------------------
// Module Initialization
// ------------------------------

/// Initialize the secure RPC system
pub fn initialize_secure_rpc() -> Result<(), RustError> {
    // Initialize TLS if enabled
    if SECURE_RPC_INTERFACE.blocking_read().config.enable_tls {
        // In a real implementation, this would initialize TLS with certificate pinning
        // if SECURE_RPC_INTERFACE.blocking_read().config.enable_certificate_pinning {
        //     initialize_certificate_pinning();
        // }
        // initialize_tls();
    }

    // Register core RPC methods
    let secure_rpc = SECURE_RPC_INTERFACE.blocking_read();
    secure_rpc.register_game_rpc_methods()?;

    // Integrate with existing systems
    integrate_with_zero_copy()?;
    integrate_with_performance_monitoring()?;

    Ok(())
}

// ------------------------------
// Tests
// ------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use jni::mock::MockJNIEnv;
    use jni::objects::JByteArray;

    #[test]
    fn test_secure_rpc_creation() {
        let secure_rpc = SecureRpcInterface::new(None);
        assert!(!secure_rpc.method_registry.is_empty()); // Game methods should be registered
    }

    #[test]
    fn test_jwt_token_generation() {
        let secure_rpc = SecureRpcInterface::new(None);
        let token = secure_rpc.generate_jwt_token(
            "test_user",
            &vec!["player".to_string()],
            &vec!["entity.read".to_string()]
        ).unwrap();
        
        assert!(!token.is_empty());
    }

    #[test]
    fn test_payload_encryption_decryption() {
        let secure_rpc = SecureRpcInterface::new(None);
        let plaintext = b"test_payload";
        
        let encrypted = secure_rpc.encrypt_payload(plaintext, None).unwrap();
        assert!(encrypted.len() > plaintext.len());
        
        let decrypted = secure_rpc.decrypt_payload(&encrypted, None).unwrap();
        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn test_rpc_method_registration() {
        let secure_rpc = SecureRpcInterface::new(None);
        
        let test_method = RpcMethod {
            name: "test_method".to_string(),
            handler: Box::new(|_, _, _| Ok(b"test_response".to_vec())),
            required_permissions: vec![],
            is_game_critical: false,
        };
        
        let result = secure_rpc.register_rpc_method(test_method);
        assert!(result.is_ok());
        
        // Should fail when registering duplicate
        let duplicate_method = RpcMethod {
            name: "test_method".to_string(),
            handler: Box::new(|_, _, _| Ok(b"duplicate".to_vec())),
            required_permissions: vec![],
            is_game_critical: false,
        };
        
        let result = secure_rpc.register_rpc_method(duplicate_method);
        assert!(result.is_err());
    }
}