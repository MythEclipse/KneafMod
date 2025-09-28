// Integration tests for ECS systems - testing the interaction between different systems

#[cfg(test)]
mod tests {
    use valence::prelude::*;
    use valence::math::{DVec3, Vec3};
    use std::sync::Arc;
    use parking_lot::RwLock;

    // Test that entity throttling works with spatial partitioning
    #[test]
    fn test_entity_throttling_integration() {
        // Create a test app
        let mut app = App::new();
        app.add_plugins(DefaultPlugins);

        // Add our resources
        app.insert_resource(rustperf::PerformanceConfig {
            entity_config: Arc::new(RwLock::new(rustperf::Config {
                close_radius: 32.0,
                medium_radius: 64.0,
                close_rate: 1.0,
                medium_rate: 0.5,
                far_rate: 0.1,
            })),
            block_config: Arc::new(RwLock::new(rustperf::BlockConfig {
                close_radius: 32.0,
                medium_radius: 64.0,
                close_rate: 1.0,
                medium_rate: 0.5,
                far_rate: 0.1,
            })),
            mob_config: Arc::new(RwLock::new(rustperf::AiConfig {
                passive_disable_distance: 64.0,
                hostile_simplify_distance: 32.0,
                ai_tick_rate_far: 0.2,
            })),
            exceptions: Arc::new(RwLock::new(rustperf::ExceptionsConfig {
                critical_entity_types: vec!["minecraft:villager".to_string()],
                critical_block_types: vec!["minecraft:furnace".to_string()],
            })),
        });

        app.insert_resource(rustperf::TickCounter(0));

        // Register components
        app.register_type::<rustperf::Player>();
        app.register_type::<rustperf::EntityType>();
        app.register_type::<rustperf::ShouldTick>();

        // Add systems
        app.add_systems(Update, rustperf::distance_based_entity_throttling);

        // Create test entities
        let player_entity = app.world_mut().spawn((
            rustperf::Player,
            Position(DVec3::new(0.0, 0.0, 0.0)),
        )).id();

        let close_entity = app.world_mut().spawn((
            Position(DVec3::new(16.0, 0.0, 0.0)), // Within close radius
        )).id();

        let medium_entity = app.world_mut().spawn((
            Position(DVec3::new(48.0, 0.0, 0.0)), // Within medium radius
        )).id();

        let far_entity = app.world_mut().spawn((
            Position(DVec3::new(100.0, 0.0, 0.0)), // Beyond medium radius
        )).id();

        // Run systems
        app.update();

        // Check that entities have appropriate tick components
        // Close entity should always tick
        assert!(app.world().get::<rustperf::ShouldTick>(close_entity).is_some());

        // Medium entity should have 50% chance to tick (we can't test randomness deterministically)
        // Far entity should have 10% chance to tick

        // Test critical entity handling
        let critical_entity = app.world_mut().spawn((
            Position(DVec3::new(200.0, 0.0, 0.0)), // Very far away
            rustperf::EntityType {
                entity_type: "minecraft:villager".to_string(),
                is_block_entity: false,
            },
        )).id();

        app.update();

        // Critical entity should always tick regardless of distance
        assert!(app.world().get::<rustperf::ShouldTick>(critical_entity).is_some());
    }

    #[test]
    fn test_block_throttling_integration() {
        let mut app = App::new();
        app.add_plugins(DefaultPlugins);

        // Add resources
        app.insert_resource(rustperf::PerformanceConfig {
            entity_config: Arc::new(RwLock::new(rustperf::Config {
                close_radius: 32.0,
                medium_radius: 64.0,
                close_rate: 1.0,
                medium_rate: 0.5,
                far_rate: 0.1,
            })),
            block_config: Arc::new(RwLock::new(rustperf::BlockConfig {
                close_radius: 32.0,
                medium_radius: 64.0,
                close_rate: 1.0,
                medium_rate: 0.5,
                far_rate: 0.1,
            })),
            mob_config: Arc::new(RwLock::new(rustperf::AiConfig {
                passive_disable_distance: 64.0,
                hostile_simplify_distance: 32.0,
                ai_tick_rate_far: 0.2,
            })),
            exceptions: Arc::new(RwLock::new(rustperf::ExceptionsConfig {
                critical_entity_types: vec![],
                critical_block_types: vec!["minecraft:furnace".to_string()],
            })),
        });

        app.insert_resource(rustperf::TickCounter(0));

        // Register components
        app.register_type::<rustperf::Player>();
        app.register_type::<rustperf::Block>();
        app.register_type::<rustperf::BlockType>();
        app.register_type::<rustperf::ShouldTickBlock>();

        // Add systems
        app.add_systems(Update, rustperf::distance_based_block_throttling);

        // Create test entities
        let player_entity = app.world_mut().spawn((
            rustperf::Player,
            Position(DVec3::new(0.0, 0.0, 0.0)),
        )).id();

        let furnace_block = app.world_mut().spawn((
            rustperf::Block,
            Position(DVec3::new(100.0, 0.0, 0.0)), // Far away
            rustperf::BlockType {
                block_type: "minecraft:furnace".to_string(),
            },
        )).id();

        let regular_block = app.world_mut().spawn((
            rustperf::Block,
            Position(DVec3::new(100.0, 0.0, 0.0)), // Same distance
        )).id();

        // Run systems
        app.update();

        // Critical block should always tick
        assert!(app.world().get::<rustperf::ShouldTickBlock>(furnace_block).is_some());

        // Regular block should be throttled based on distance
        // (we can't test the exact throttling without controlling randomness)
    }

    #[test]
    fn test_mob_ai_integration() {
        let mut app = App::new();
        app.add_plugins(DefaultPlugins);

        // Add resources
        app.insert_resource(rustperf::PerformanceConfig {
            entity_config: Arc::new(RwLock::new(rustperf::Config::default())),
            block_config: Arc::new(RwLock::new(rustperf::BlockConfig::default())),
            mob_config: Arc::new(RwLock::new(rustperf::AiConfig {
                passive_disable_distance: 64.0,
                hostile_simplify_distance: 32.0,
                ai_tick_rate_far: 0.2,
            })),
            exceptions: Arc::new(RwLock::new(rustperf::ExceptionsConfig::default())),
        });

        app.insert_resource(rustperf::TickCounter(0));

        // Register components
        app.register_type::<rustperf::Player>();
        app.register_type::<rustperf::Mob>();
        app.register_type::<rustperf::NoAi>();
        app.register_type::<rustperf::ShouldTickAi>();

        // Add systems
        app.add_systems(Update, rustperf::mob_ai_optimization);

        // Create test entities
        let player_entity = app.world_mut().spawn((
            rustperf::Player,
            Position(DVec3::new(0.0, 0.0, 0.0)),
        )).id();

        let close_mob = app.world_mut().spawn((
            rustperf::Mob,
            Position(DVec3::new(16.0, 0.0, 0.0)), // Within hostile_simplify_distance
        )).id();

        let far_mob = app.world_mut().spawn((
            rustperf::Mob,
            Position(DVec3::new(100.0, 0.0, 0.0)), // Beyond passive_disable_distance
        )).id();

        // Run systems
        app.update();

        // Close mob should have AI enabled
        assert!(app.world().get::<rustperf::ShouldTickAi>(close_mob).is_some());
        assert!(!app.world().get::<rustperf::NoAi>(close_mob).is_some());

        // Far mob should have AI disabled
        assert!(app.world().get::<rustperf::NoAi>(far_mob).is_some());
    }

    #[test]
    fn test_chunk_management_integration() {
        let mut app = App::new();
        app.add_plugins(DefaultPlugins);

        // Add resources
        app.insert_resource(rustperf::ChunkManager {
            loaded_chunks: std::collections::HashMap::new(),
            view_distance: 2,
            unload_distance: 3,
        });

        app.insert_resource(rustperf::TickCounter(0));

        // Register components
        app.register_type::<rustperf::Player>();

        // Add systems
        app.add_systems(Update, rustperf::update_chunk_manager);

        // Create player
        let player_entity = app.world_mut().spawn((
            rustperf::Player,
            Position(DVec3::new(0.0, 0.0, 0.0)), // Chunk (0,0)
        )).id();

        // Run systems
        app.update();

        // Check that chunks are loaded
        let chunk_manager = app.world().resource::<rustperf::ChunkManager>();
        assert!(!chunk_manager.loaded_chunks.is_empty());

        // Should have chunks within view distance of (0,0)
        let center_chunk = rustperf::ChunkCoord { x: 0, z: 0 };
        assert!(chunk_manager.loaded_chunks.contains_key(&center_chunk));
    }

    #[test]
    fn test_config_loading_integration() {
        let mut app = App::new();
        app.add_plugins(DefaultPlugins);

        // Add resources
        app.insert_resource(rustperf::PerformanceConfig {
            entity_config: Arc::new(RwLock::new(rustperf::Config::default())),
            block_config: Arc::new(RwLock::new(rustperf::BlockConfig::default())),
            mob_config: Arc::new(RwLock::new(rustperf::AiConfig::default())),
            exceptions: Arc::new(RwLock::new(rustperf::ExceptionsConfig::default())),
        });

        // Add config loading system
        app.add_systems(Update, rustperf::load_config_from_toml);

        // Run systems (config loading should work without panicking)
        app.update();

        // Verify resources still exist
        assert!(app.world().get_resource::<rustperf::PerformanceConfig>().is_some());
    }
}