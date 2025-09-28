use valence::prelude::*;
use bevy_reflect::Reflect;

// Core components
#[derive(Component, Reflect)]
pub struct Player;

#[derive(Component, Reflect)]
pub struct Block;

#[derive(Component, Reflect)]
pub struct Mob;

#[derive(Component, Reflect)]
pub struct EntityType {
    pub entity_type: String,
    pub is_block_entity: bool,
}

#[derive(Component, Reflect)]
pub struct BlockType {
    pub block_type: String,
}

#[derive(Component, Reflect)]
#[component(storage = "Table")]
pub struct DistanceCache {
    pub distances: Vec<f32>,
}

// Performance optimization components
#[derive(Component, Reflect)]
#[component(storage = "Table")]
pub struct ShouldTick;

#[derive(Component, Reflect)]
pub struct NoAi;

#[derive(Component, Reflect)]
pub struct ShouldTickBlock;

#[derive(Component, Reflect)]
pub struct ShouldTickAi;

// New components for complex gameplay
#[derive(Component, Reflect)]
pub struct Velocity {
    pub x: f32,
    pub y: f32,
    pub z: f32,
}

#[derive(Component, Reflect)]
pub struct Gravity;

#[derive(Component, Reflect)]
pub struct OnGround;

#[derive(Component, Reflect)]
pub struct Health {
    pub current: f32,
    pub max: f32,
}

#[derive(Component, Reflect)]
pub struct AttackDamage {
    pub damage: f32,
}

#[derive(Component, Reflect)]
pub struct ItemDrop {
    pub item_kind: String,
    pub count: i32,
}

#[derive(Component, Reflect)]
pub struct Inventory {
    pub items: Vec<(String, i32)>,
}

#[derive(Component, Reflect)]
pub struct BlockState {
    pub block_type: String,
    pub powered: bool,
    pub power_level: i32,
}