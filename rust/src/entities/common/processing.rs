use crate::errors::Result;
use crate::types::{EntityConfigTrait as EntityConfig, EntityDataTrait as EntityData, EntityTypeTrait as EntityType};
use std::fmt::Debug;
use std::time::{SystemTime, Instant};

/// Processing result for entity operations
#[derive(Debug, Clone, PartialEq)]
pub struct ProcessingResult {
    pub success: bool,
    pub message: String,
    pub data: Option<EntityData>,
    pub timestamp: SystemTime,
}

/// Processing status for entity operations
#[derive(Debug, Clone, PartialEq)]
pub enum ProcessingStatus {
    Pending,
    InProgress,
    Completed,
    Failed(String),
    Cancelled,
}

/// Processing configuration for entity operations
#[derive(Debug, Clone)]
pub struct ProcessingConfig {
    pub max_concurrent_operations: usize,
    pub timeout_seconds: u64,
    pub retry_attempts: u32,
    pub enable_caching: bool,
}

impl Default for ProcessingConfig {
    fn default() -> Self {
        Self {
            max_concurrent_operations: 10,
            timeout_seconds: 30,
            retry_attempts: 3,
            enable_caching: true,
        }
    }
}

/// Extension trait for entity processing
pub trait EntityProcessingExt {
    /// Process entity with given configuration
    fn process_with_config(&self, config: &ProcessingConfig) -> Result<ProcessingResult>;
    
    /// Get processing status
    fn get_processing_status(&self) -> ProcessingStatus;
    
    /// Update processing status
    fn update_processing_status(&mut self, status: ProcessingStatus);
    
    /// Get processing start time
    fn get_processing_start_time(&self) -> Option<Instant>;
    
    /// Set processing start time
    fn set_processing_start_time(&mut self, time: Instant);
}

/// Common entity processor
pub struct EntityProcessor {
    config: ProcessingConfig,
    status: ProcessingStatus,
    start_time: Option<Instant>,
}

impl EntityProcessor {
    pub fn new(config: ProcessingConfig) -> Self {
        Self {
            config,
            status: ProcessingStatus::Pending,
            start_time: None,
        }
    }
    
    pub fn process_entity(&mut self, entity_data: &EntityData) -> Result<ProcessingResult> {
        self.status = ProcessingStatus::InProgress;
        self.start_time = Some(Instant::now());
        
        // Simulate processing logic
        let result = ProcessingResult {
            success: true,
            message: "Entity processed successfully".to_string(),
            data: Some(entity_data.clone()),
            timestamp: SystemTime::now(),
        };
        
        self.status = ProcessingStatus::Completed;
        Ok(result)
    }
    
    pub fn get_status(&self) -> &ProcessingStatus {
        &self.status
    }
    
    pub fn get_config(&self) -> &ProcessingConfig {
        &self.config
    }
}

/// Process multiple entities
pub fn process_entities_batch(
    entities: Vec<EntityData>,
    config: &ProcessingConfig,
) -> Result<Vec<ProcessingResult>> {
    let mut results = Vec::new();
    let mut processor = EntityProcessor::new(config.clone());
    
    for entity in entities {
        let result = processor.process_entity(&entity)?;
        results.push(result);
    }
    
    Ok(results)
}