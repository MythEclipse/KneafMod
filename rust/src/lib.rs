use std::sync::RwLock;
use std::fs;
use std::fs::OpenOptions;
use std::io::Write;
use std::time::{Duration, Instant};
use serde::{Deserialize, Serialize};
use jni::{JNIEnv, objects::{JClass, JString}, sys::jstring};

#[derive(Serialize, Deserialize)]
pub struct EntityData {
    pub id: u64,
    pub entity_type: String,
    pub distance: f32,
    pub is_block_entity: bool,
}

#[derive(Serialize, Deserialize)]
pub struct Input {
    pub tick_count: u64,
    pub entities: Vec<EntityData>,
}

#[derive(Serialize, Deserialize)]
pub struct ProcessResult {
    pub entities_to_tick: Vec<u64>,
}

#[derive(Serialize, Deserialize)]
pub struct ItemEntityData {
    pub id: u64,
    pub item_type: String,
    pub count: u32,
    pub age_seconds: u32,
    pub chunk_x: i32,
    pub chunk_z: i32,
}

#[derive(Serialize, Deserialize)]
pub struct ItemInput {
    pub items: Vec<ItemEntityData>,
}

#[derive(Serialize, Deserialize)]
pub struct ItemUpdate {
    pub id: u64,
    pub new_count: u32,
}

#[derive(Serialize, Deserialize)]
pub struct ItemProcessResult {
    pub items_to_remove: Vec<u64>,
    pub merged_count: u64,
    pub despawned_count: u64,
    pub item_updates: Vec<ItemUpdate>,
}

#[derive(Serialize, Deserialize)]
pub struct MobData {
    pub id: u64,
    pub entity_type: String,
    pub distance: f32,
    pub is_passive: bool,
}

#[derive(Serialize, Deserialize)]
pub struct MobInput {
    pub tick_count: u64,
    pub mobs: Vec<MobData>,
}

#[derive(Serialize, Deserialize)]
pub struct MobProcessResult {
    pub mobs_to_disable_ai: Vec<u64>,
    pub mobs_to_simplify_ai: Vec<u64>,
}

#[derive(Serialize, Deserialize)]
pub struct Config {
    pub close_radius: f32,
    pub medium_radius: f32,
    pub close_rate: f32,
    pub medium_rate: f32,
    pub far_rate: f32,
}

#[derive(Serialize, Deserialize)]
pub struct ItemConfig {
    pub merge_enabled: bool,
    pub max_items_per_chunk: usize,
    pub despawn_time_seconds: u32,
}

#[derive(Serialize, Deserialize)]
pub struct AiConfig {
    pub passive_disable_distance: f32,
    pub hostile_simplify_distance: f32,
    pub ai_tick_rate_far: f32,
}

#[derive(Serialize, Deserialize)]
pub struct ExceptionsConfig {
    pub critical_entity_types: Vec<String>,
}

lazy_static::lazy_static! {
    static ref CONFIG: RwLock<Config> = RwLock::new(Config {
        close_radius: 10.0,
        medium_radius: 50.0,
        close_rate: 1.0,
        medium_rate: 0.5,
        far_rate: 0.1,
    });
    static ref ITEM_CONFIG: RwLock<ItemConfig> = RwLock::new(ItemConfig {
        merge_enabled: true,
        max_items_per_chunk: 100,
        despawn_time_seconds: 300,
    });
    static ref AI_CONFIG: RwLock<AiConfig> = RwLock::new(AiConfig {
        passive_disable_distance: 100.0,
        hostile_simplify_distance: 50.0,
        ai_tick_rate_far: 0.2,
    });
    static ref EXCEPTIONS_CONFIG: RwLock<ExceptionsConfig> = RwLock::new(ExceptionsConfig {
        critical_entity_types: vec!["minecraft:villager".to_string(), "minecraft:wandering_trader".to_string()],
    });
    static ref MERGED_COUNT: RwLock<u64> = RwLock::new(0);
    static ref DESPAWNED_COUNT: RwLock<u64> = RwLock::new(0);
    static ref LAST_LOG_TIME: RwLock<Instant> = RwLock::new(Instant::now());
}
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustPerformance_processEntitiesNative(mut env: JNIEnv, _class: JClass, json_input: JString) -> jstring {
    let input: String = env.get_string(&json_input).expect("Couldn't get java string!").into();
    let input: Input = serde_json::from_str(&input).unwrap_or(Input { tick_count: 0, entities: vec![] });
    let config = CONFIG.read().unwrap();
    let exceptions = EXCEPTIONS_CONFIG.read().unwrap();
    let mut entities_to_tick = Vec::new();
    for entity in input.entities {
        if entity.is_block_entity || exceptions.critical_entity_types.contains(&entity.entity_type) {
            entities_to_tick.push(entity.id);
            continue;
        }
        let rate = if entity.distance <= config.close_radius {
            config.close_rate
        } else if entity.distance <= config.medium_radius {
            config.medium_rate
        } else {
            config.far_rate
        };
        let period = (1.0 / rate) as u64;
        if period == 0 || (input.tick_count + entity.id as u64) % period == 0 {
            entities_to_tick.push(entity.id);
        }
    }
    let result = ProcessResult { entities_to_tick };
    let output = serde_json::to_string(&result).unwrap();
    env.new_string(&output).expect("Couldn't create java string!").into_raw()
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustPerformance_processItemEntitiesNative(mut env: JNIEnv, _class: JClass, json_input: JString) -> jstring {
    let input: String = env.get_string(&json_input).expect("Couldn't get java string!").into();
    let input: ItemInput = serde_json::from_str(&input).unwrap_or(ItemInput { items: vec![] });
    let config = ITEM_CONFIG.read().unwrap();
    let mut items_to_remove = Vec::new();
    let mut item_updates = Vec::new();
    let mut local_merged = 0u64;
    let mut local_despawned = 0u64;

    use std::collections::HashMap;
    let mut chunk_map: HashMap<(i32, i32), Vec<&ItemEntityData>> = HashMap::new();
    for item in &input.items {
        chunk_map.entry((item.chunk_x, item.chunk_z)).or_insert(Vec::new()).push(item);
    }

    for (_chunk, items) in chunk_map {
        // Merge stacks
        if config.merge_enabled {
            let mut type_map: HashMap<&str, Vec<&ItemEntityData>> = HashMap::new();
            for item in &items {
                type_map.entry(&item.item_type).or_insert(Vec::new()).push(item);
            }
            for (_type, type_items) in type_map {
                if type_items.len() > 1 {
                    // Merge stacks: sum counts, update one item, remove others
                    let mut total_count = 0u32;
                    let mut keep_id = None;
                    for item in &type_items {
                        total_count += item.count;
                        if keep_id.is_none() {
                            keep_id = Some(item.id);
                        }
                    }
                    if let Some(keep_id) = keep_id {
                        item_updates.push(ItemUpdate { id: keep_id, new_count: total_count });
                        for item in &type_items {
                            if item.id != keep_id {
                                items_to_remove.push(item.id);
                                local_merged += 1;
                            }
                        }
                    }
                }
            }
        }

        // Enforce max per chunk - DISABLED to not break vanilla gameplay
        /*
        let mut sorted_items: Vec<&ItemEntityData> = items.iter().filter(|i| !items_to_remove.contains(&i.id)).cloned().collect();
        sorted_items.sort_by_key(|i| i.age_seconds);
        if sorted_items.len() > config.max_items_per_chunk {
            let excess = sorted_items.len() - config.max_items_per_chunk;
            for i in 0..excess {
                items_to_remove.push(sorted_items[i].id);
                local_despawned += 1;
            }
        }
        */
        let mut sorted_items: Vec<&ItemEntityData> = items.iter().filter(|i| !items_to_remove.contains(&i.id)).cloned().collect();
        sorted_items.sort_by_key(|i| i.age_seconds);
        if sorted_items.len() > config.max_items_per_chunk {
            let excess = sorted_items.len() - config.max_items_per_chunk;
            for i in 0..excess {
                items_to_remove.push(sorted_items[i].id);
                local_despawned += 1;
            }
        }

        // Despawn old items
        for item in &items {
            if item.age_seconds > config.despawn_time_seconds && !items_to_remove.contains(&item.id) {
                items_to_remove.push(item.id);
                local_despawned += 1;
            }
        }
    }

    // Update global counters
    *MERGED_COUNT.write().unwrap() += local_merged;
    *DESPAWNED_COUNT.write().unwrap() += local_despawned;

    // Log every minute
    let mut last_time = LAST_LOG_TIME.write().unwrap();
    if last_time.elapsed() > Duration::from_secs(60) {
        let merged = *MERGED_COUNT.read().unwrap();
        let despawned = *DESPAWNED_COUNT.read().unwrap();
        let log_msg = format!("Item optimization: {} merged, {} despawned\n", merged, despawned);
        if let Err(e) = fs::create_dir_all("logs") {
            eprintln!("Failed to create logs dir: {}", e);
        } else if let Ok(mut file) = OpenOptions::new().create(true).append(true).open("logs/rustperf.log") {
            if let Err(e) = write!(file, "{}", log_msg) {
                eprintln!("Failed to write log: {}", e);
            }
        }
        *last_time = Instant::now();
        *MERGED_COUNT.write().unwrap() = 0;
        *DESPAWNED_COUNT.write().unwrap() = 0;
    }

    let result = ItemProcessResult { items_to_remove, merged_count: local_merged, despawned_count: local_despawned, item_updates };
    let output = serde_json::to_string(&result).unwrap();
    env.new_string(&output).expect("Couldn't create java string!").into_raw()
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustPerformance_processMobAiNative(mut env: JNIEnv, _class: JClass, json_input: JString) -> jstring {
    let input: String = env.get_string(&json_input).expect("Couldn't get java string!").into();
    let input: MobInput = serde_json::from_str(&input).unwrap_or(MobInput { tick_count: 0, mobs: vec![] });
    let config = AI_CONFIG.read().unwrap();
    let exceptions = EXCEPTIONS_CONFIG.read().unwrap();
    let mut mobs_to_disable_ai = Vec::new();
    let mut mobs_to_simplify_ai = Vec::new();
    for mob in input.mobs {
        if exceptions.critical_entity_types.contains(&mob.entity_type) {
            // Skip optimization for critical entities
            continue;
        }
        if mob.is_passive {
            if mob.distance > config.passive_disable_distance {
                mobs_to_disable_ai.push(mob.id);
            }
        } else {
            if mob.distance > config.hostile_simplify_distance {
                let period = (1.0 / config.ai_tick_rate_far) as u64;
                if period == 0 || (input.tick_count + mob.id as u64) % period == 0 {
                    mobs_to_simplify_ai.push(mob.id);
                }
            }
        }
    }
    let result = MobProcessResult { mobs_to_disable_ai, mobs_to_simplify_ai };
    let output = serde_json::to_string(&result).unwrap();
    env.new_string(&output).expect("Couldn't create java string!").into_raw()
}

#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustPerformance_freeStringNative(_env: JNIEnv, _class: JClass, _s: jstring) {
    // jstring is managed by JVM, no need to free
}