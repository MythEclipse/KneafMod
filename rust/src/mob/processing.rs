use super::types::*;
use super::config::*;
use crate::EXCEPTIONS_CONFIG;
use rayon::prelude::*;

pub fn process_mob_ai(input: MobInput) -> MobProcessResult {
    let config = AI_CONFIG.read().unwrap();
    let exceptions = EXCEPTIONS_CONFIG.read().unwrap();
    let (mobs_to_disable_ai, mobs_to_simplify_ai): (Vec<u64>, Vec<u64>) = input.mobs.par_iter().fold(|| (Vec::new(), Vec::new()), |(mut disable, mut simplify), mob| {
        if exceptions.critical_entity_types.contains(&mob.entity_type) {
            return (disable, simplify);
        }
        if mob.is_passive {
            if mob.distance > config.passive_disable_distance {
                disable.push(mob.id);
            }
        } else {
            if mob.distance > config.hostile_simplify_distance {
                let period = (1.0 / config.ai_tick_rate_far) as u64;
                if period == 0 || (input.tick_count + mob.id as u64) % period == 0 {
                    simplify.push(mob.id);
                }
            }
        }
        (disable, simplify)
    }).reduce(|| (Vec::new(), Vec::new()), |(mut acc_disable, mut acc_simplify), (disable, simplify)| {
        acc_disable.extend(disable);
        acc_simplify.extend(simplify);
        (acc_disable, acc_simplify)
    });
    MobProcessResult { mobs_to_disable_ai, mobs_to_simplify_ai }
}