use super::types::*;
use super::config::*;
use crate::EXCEPTIONS_CONFIG;
use rayon::prelude::*;

pub fn process_entities(input: Input) -> ProcessResult {
    let config = CONFIG.read().unwrap();
    let exceptions = EXCEPTIONS_CONFIG.read().unwrap();
    let entities_to_tick: Vec<u64> = input.entities.par_iter().filter_map(|entity| {
        if entity.is_block_entity || exceptions.critical_entity_types.contains(&entity.entity_type) {
            Some(entity.id)
        } else {
            let rate = if entity.distance <= config.close_radius {
                config.close_rate
            } else if entity.distance <= config.medium_radius {
                config.medium_rate
            } else {
                config.far_rate
            };
            let period = (1.0 / rate) as u64;
            if period == 0 || (input.tick_count + entity.id as u64) % period == 0 {
                Some(entity.id)
            } else {
                None
            }
        }
    }).collect();
    ProcessResult { entities_to_tick }
}