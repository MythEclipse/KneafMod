use crate::types::{EntityConfigTrait as EntityConfig, EntityDataTrait as EntityData, EntityTypeTrait as EntityType, PlayerDataTrait as PlayerData};
use std::sync::Arc;
use std::time::Instant;

/// Entity processing status enum
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum ProcessingStatus {
    /// Entity is ready to be processed
    Ready,
    /// Entity is currently being processed
    Processing,
    /// Entity processing completed successfully
    Completed,
    /// Entity processing failed
    Failed,
    /// Entity processing was skipped
    Skipped,
    /// Entity needs to be retried
    Retry,
}

/// Result type for entity processing operations
pub type ProcessingResult<T> = Result<T, Box<dyn std::error::Error + Send + Sync>>;

/// AI optimization types for entity processing
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AiOptimizationType {
    /// No optimization - process normally
    None,
    /// Basic optimization - skip non-critical updates
    Basic,
    /// Advanced optimization - use predictive processing
    Advanced,
    /// Extreme optimization - minimal processing only
    Extreme,
}

/// Trait for entity processing extensions
pub trait EntityProcessingExt: Send + Sync {
    /// Get current processing status
    fn processing_status(&self) -> ProcessingStatus;
    
    /// Set processing status
    fn set_processing_status(&mut self, status: ProcessingStatus);
    
    /// Get last processing time
    fn last_processed(&self) -> Instant;
    
    /// Set last processing time
    fn set_last_processed(&mut self, time: Instant);
    
    /// Get AI optimization level
    fn ai_optimization(&self) -> AiOptimizationType;
    
    /// Set AI optimization level
    fn set_ai_optimization(&mut self, optimization: AiOptimizationType);
}

/// Base entity processor trait
pub trait EntityProcessor: Send + Sync {
    /// Process a single entity
    fn process_entity(&self, entity: &mut EntityData) -> ProcessingResult<()>;
    
    /// Process multiple entities in batch
    fn process_entities(&self, entities: &mut Vec<EntityData>) -> ProcessingResult<()>;
    
    /// Get processor name/identifier
    fn processor_name(&self) -> &str;
}

/// Thread-safe entity processor wrapper
pub struct ThreadSafeEntityProcessor<T>
where
    T: EntityProcessor,
{
    processor: Arc<T>,
}

impl<T> ThreadSafeEntityProcessor<T>
where
    T: EntityProcessor,
{
    /// Create new thread-safe entity processor
    pub fn new(processor: T) -> Self {
        Self {
            processor: Arc::new(processor),
        }
    }
    
    /// Get underlying processor reference
    pub fn get_processor(&self) -> Arc<T> {
        self.processor.clone()
    }
}

impl<T> EntityProcessor for ThreadSafeEntityProcessor<T>
where
    T: EntityProcessor,
{
    fn process_entity(&self, entity: &mut EntityData) -> ProcessingResult<()> {
        self.processor.process_entity(entity)
    }
    
    fn process_entities(&self, entities: &mut Vec<EntityData>) -> ProcessingResult<()> {
        self.processor.process_entities(entities)
    }
    
    fn processor_name(&self) -> &str {
        self.processor.processor_name()
    }
}

impl<T> Clone for ThreadSafeEntityProcessor<T>
where
    T: EntityProcessor,
{
    fn clone(&self) -> Self {
        Self {
            processor: self.processor.clone(),
        }
    }
}

/// Entity processing input structure
#[derive(Debug, Clone)]
pub struct ProcessingInput<T: EntityConfig> {
    /// Entity to process
    pub entity: EntityData,
    /// Processing configuration
    pub config: T,
    /// Current timestamp
    pub timestamp: Instant,
    /// AI optimization level
    pub ai_optimization: AiOptimizationType,
}

/// Entity processing output structure
#[derive(Debug, Clone)]
pub struct ProcessingOutput {
    /// Processed entity
    pub entity: EntityData,
    /// Processing status
    pub status: ProcessingStatus,
    /// Processing duration
    pub duration: std::time::Duration,
    /// Error message (if any)
    pub error: Option<String>,
}