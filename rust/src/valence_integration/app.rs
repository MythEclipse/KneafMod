use valence::prelude::*;
use crate::{Aabb, QuadTree};
use crate::resources::{ValenceSpatialPartition, PerformanceConfig, ChunkManager, TickCounter, Weather, GameTime};
use crate::systems::*;
use crate::valence_integration::config::load_config_from_toml;
use crate::events::*;
use std::sync::Arc;
use parking_lot::RwLock;
use crate::{EntityConfig, BlockConfig, AiConfig, ExceptionsConfig};

// Basic Valence server setup
pub fn create_valence_app() -> App {
    let mut app = App::new();

    // Add Valence plugins
    app.add_plugins(DefaultPlugins);

    // Add our performance optimization systems
    app.insert_resource(TickCounter(0));
    app.insert_resource(ValenceSpatialPartition {
        player_quadtree: QuadTree::new(
            Aabb::from_center_size(Vec3::ZERO, Vec3::splat(1000.0)),
            16, 8,
        ),
        entity_quadtree: QuadTree::new(
            Aabb::from_center_size(Vec3::ZERO, Vec3::splat(1000.0)),
            16, 8,
        ),
    });
    app.insert_resource(PerformanceConfig {
        entity_config: Arc::new(RwLock::new(EntityConfig {
            close_radius: 96.0,
            medium_radius: 192.0,
            close_rate: 1.0,
            medium_rate: 0.5,
            far_rate: 0.1,
            world_bounds: Aabb::from_center_size(Vec3::ZERO, Vec3::splat(1000.0)),
            quadtree_max_entities: 16,
            quadtree_max_depth: 8,
            use_spatial_partitioning: true,
        })),
        block_config: Arc::new(RwLock::new(BlockConfig {
            close_radius: 96.0,
            medium_radius: 192.0,
            close_rate: 1.0,
            medium_rate: 0.5,
            far_rate: 0.1,
        })),
        mob_config: Arc::new(RwLock::new(AiConfig {
            passive_disable_distance: 128.0,
            hostile_simplify_distance: 64.0,
            ai_tick_rate_far: 0.2,
        })),
        exceptions: Arc::new(RwLock::new(ExceptionsConfig {
            critical_entity_types: vec!["minecraft:villager".to_string(), "minecraft:wandering_trader".to_string()],
            critical_block_types: vec!["minecraft:furnace".to_string(), "minecraft:chest".to_string(), "minecraft:brewing_stand".to_string()],
        })),
    });
    app.insert_resource(ChunkManager {
        loaded_chunks: std::collections::HashMap::new(),
        view_distance: 8, // 8 chunks view distance
        unload_distance: 12, // Unload at 12 chunks
    });

    app.insert_resource(Weather { raining: false, thunder: false, time: 0 });
    app.insert_resource(GameTime { time: 0, day_length: 24000 });

    app.add_event::<AttackEvent>();
    app.add_event::<BlockBreakEvent>();
    app.add_event::<BlockPlaceEvent>();

    app.add_systems(Update, (
        update_chunk_manager,
        update_chunk_entity_grouping,
        chunk_based_entity_throttling,
        chunk_based_mob_ai_optimization,
    ));

    app.add_systems(Update, (
        apply_gravity,
        collision_detection,
        block_update_system,
        item_pickup_system,
        combat_system,
        weather_update,
        time_update,
        mob_spawning,
        pathfinding_ai,
        redstone_update,
        crafting_system,
        particle_effects,
        sound_effects,
        multiplayer_interactions,
    ));

    app.add_systems(Update, (
        chunk_based_block_throttling,
        load_config_from_toml,
    ));

    app.add_systems(PreUpdate, update_tick_counter);

    app
}

// JNI-compatible function to initialize Valence integration
#[no_mangle]
pub extern "C" fn Java_com_kneaf_core_RustPerformance_initializeValenceNative() {
    // Initialize the Valence app in a separate thread to avoid blocking JNI
    std::thread::spawn(|| {
        let mut app = create_valence_app();
        app.run();
    });
}