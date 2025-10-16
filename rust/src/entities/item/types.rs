use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// Item types
#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
pub enum ItemType {
    Sword,
    Pickaxe,
    Axe,
    Shovel,
    Hoe,
    Helmet,
    Chestplate,
    Leggings,
    Boots,
    Bow,
    Arrow,
    Shield,
    Food,
    Tool,
    Block,
    Potion,
    Book,
    Map,
    Compass,
    Clock,
    FishingRod,
    Shears,
    FlintAndSteel,
    Bucket,
    Lead,
    NameTag,
    Saddle,
    Armor,
    Weapon,
    Other(String),
}

/// Item data
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct ItemData {
    pub id: u64,
    pub item_type: ItemType,
    pub name: String,
    pub durability: u32,
    pub max_durability: u32,
    pub enchantments: HashMap<String, u8>,
    pub properties: HashMap<String, String>,
}

/// Item stack data
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct ItemStack {
    pub item: ItemData,
    pub count: u8,
    pub slot: i8,
}

/// Item input for processing
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct ItemInput {
    pub tick_count: u64,
    pub items: Vec<ItemStack>,
    pub players: Vec<PlayerData>,
}

/// Item processing result
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct ItemProcessResult {
    pub items_to_despawn: Vec<u64>,
    pub items_to_merge: Vec<ItemMergeGroup>,
}

/// Item merge group
#[derive(Serialize, Deserialize, Debug, Clone)]
pub struct ItemMergeGroup {
    pub group_id: u32,
    pub item_ids: Vec<u64>,
    pub merge_position: (f32, f32, f32),
}

/// Player data for item processing
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct PlayerData {
    pub id: u64,
    pub x: f64,
    pub y: f64,
    pub z: f64,
}