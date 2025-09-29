pub mod entity {
    include!("entity/entity_generated.rs");
}

pub mod item {
    include!("item/item_generated.rs");
}

pub mod mob {
    include!("mob/mob_generated.rs");
}

pub mod block {
    include!("block/block_generated.rs");
}

pub mod conversions;

// Re-export the FlatBuffers generated types for convenience
pub use self::entity::kneaf::*;
pub use self::item::kneaf::*;
pub use self::mob::kneaf::*;
pub use self::block::kneaf::*;