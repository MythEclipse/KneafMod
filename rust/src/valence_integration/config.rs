use crate::PerformanceConfig;
use bevy_ecs::prelude::ResMut;
use serde::Deserialize;
use std::fs;




// TOML configuration structure
#[derive(Deserialize)]
struct TomlConfig {
    throttling: ThrottlingConfig,
    ai_optimization: AiOptimizationConfig,
    exceptions: TomlExceptionsConfig,
}

#[derive(Deserialize)]
struct ThrottlingConfig {
    close_radius: f32,
    medium_radius: f32,
    close_rate: f32,
    medium_rate: f32,
    far_rate: f32,
}

#[derive(Deserialize)]
struct AiOptimizationConfig {
    passive_disable_distance: f32,
    hostile_simplify_distance: f32,
    ai_tick_rate_far: f32,
}

#[derive(Deserialize)]
struct TomlExceptionsConfig {
    critical_entity_types: Vec<String>,
}

// Config loading system
pub fn load_config_from_toml(
    config: ResMut<PerformanceConfig>,
) {
    // Load config from TOML file
    if let Ok(contents) = fs::read_to_string("config/rustperf.toml") {
        if let Ok(toml_config) = toml::from_str::<TomlConfig>(&contents) {
            // Update entity config
            {
                let mut entity_config = config.entity_config.write();
                entity_config.close_radius = toml_config.throttling.close_radius;
                entity_config.medium_radius = toml_config.throttling.medium_radius;
                entity_config.close_rate = toml_config.throttling.close_rate;
                entity_config.medium_rate = toml_config.throttling.medium_rate;
                entity_config.far_rate = toml_config.throttling.far_rate as f64;
            }

            // Update block config (assuming same as entity for now)
            {
                let mut block_config = config.block_config.write();
                block_config.close_radius = toml_config.throttling.close_radius;
                block_config.medium_radius = toml_config.throttling.medium_radius;
                block_config.close_rate = toml_config.throttling.close_rate;
                block_config.medium_rate = toml_config.throttling.medium_rate;
                block_config.far_rate = toml_config.throttling.far_rate;
            }

            // Update mob config
            {
                let mut mob_config = config.mob_config.write();
                mob_config.passive_disable_distance = toml_config.ai_optimization.passive_disable_distance;
                mob_config.hostile_simplify_distance = toml_config.ai_optimization.hostile_simplify_distance;
                mob_config.ai_tick_rate_far = toml_config.ai_optimization.ai_tick_rate_far;
            }

            // Update exceptions
            {
                let mut exceptions = config.exceptions.write();
                exceptions.critical_entity_types = toml_config.exceptions.critical_entity_types.clone();
                // For blocks, use same as entities for now
                // exceptions.critical_block_types = toml_config.exceptions.critical_entity_types.clone();
            }
        }
    }
    // If loading fails, keep default values
}