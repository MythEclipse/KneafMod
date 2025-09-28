use valence::prelude::*;

// Events
#[derive(Event)]
pub struct AttackEvent {
    pub attacker: Entity,
    pub target: Entity,
    pub damage: f32,
}

#[derive(Event)]
pub struct BlockBreakEvent {
    pub player: Entity,
    pub position: glam::IVec3,
    pub block_type: String,
}

#[derive(Event)]
pub struct BlockPlaceEvent {
    pub player: Entity,
    pub position: glam::IVec3,
    pub block_type: String,
}