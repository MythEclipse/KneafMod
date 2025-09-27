use super::types::*;
use super::config::*;
use crate::EXCEPTIONS_CONFIG;

pub fn process_mob_ai(input: MobInput) -> MobProcessResult {
    let config = AI_CONFIG.read().unwrap();
    let exceptions = EXCEPTIONS_CONFIG.read().unwrap();
    let mut mobs_to_disable_ai = Vec::new();
    let mut mobs_to_simplify_ai = Vec::new();
    for mob in input.mobs {
        if exceptions.critical_entity_types.contains(&mob.entity_type) {
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
    MobProcessResult { mobs_to_disable_ai, mobs_to_simplify_ai }
}