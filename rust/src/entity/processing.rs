use super::types::*;
use super::config::*;
use crate::EXCEPTIONS_CONFIG;

pub fn process_entities(input: Input) -> ProcessResult {
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
    ProcessResult { entities_to_tick }
}