//! Entities module containing all entity-related functionality

pub mod common;
pub mod block;
pub mod entity;
pub mod mob;
pub mod villager;
pub mod item;

// Re-export commonly used types with specific names to avoid conflicts
pub use self::common::factory::{EntityProcessorFactory, EntityConfig};
pub use self::common::processing::{EntityProcessor, ProcessingResult, ProcessingStatus, ProcessingConfig};
pub use self::common::state_manager::{EntityStateManager, EntityOperationResult, EntityState};
pub use self::common::thread_safe_state_manager::{ThreadSafeEntityStateManager, ThreadSafeEntityOperationResult, ThreadSafeEntityState, ThreadSafeEntityProcessor};
pub use self::common::types::{ProcessingInput, ProcessingOutput};

pub use self::block::types::{BlockData, BlockState, BlockPosition};

pub use self::entity::types::{EntityPosition, EntityData as EntityEntityData};
pub use self::entity::types::ProcessResult as EntityProcessResult;
pub use crate::traits::EntityConfigTrait as EntityEntityConfig;

pub use self::mob::types::{MobAiComplexity, MobState, MobType, MobData};
pub use crate::entities::entity::processing::process_entities as process_mob_entities;
pub use self::mob::processing::process_mob_ai_json;
pub use self::common::processing::ProcessingResult as MobProcessingResult;
pub use self::mob::config::MobConfig;

pub use self::villager::types::{VillagerData, VillagerProfession};
pub use crate::entities::entity::processing::process_entities as process_villager_entities;
pub use self::villager::processing::process_villager_ai_json;
pub use self::common::processing::ProcessingResult as VillagerProcessingResult;
pub use self::villager::types::VillagerConfig;

pub use self::item::types::{ItemData, ItemStack, ItemInput, ItemProcessResult};
pub use self::item::processing::process_items;
pub use self::item::types::ItemProcessResult as ItemProcessingResult;
pub use self::item::config::ItemConfig;