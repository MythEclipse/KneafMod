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