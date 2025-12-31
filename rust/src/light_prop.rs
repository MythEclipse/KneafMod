use rayon::prelude::*;

pub fn batch_light_propagate(levels: &[i32], opacity: &[i8], count: i32) -> Vec<i32> {
    let count = count as usize;
    if levels.len() < count || opacity.len() < count {
        return vec![0; count];
    }

    (0..count)
        .into_par_iter()
        .map(|i| {
            let level = levels[i];
            let op = opacity[i] as i32;

            if level > op {
                level - op
            } else {
                0
            }
        })
        .collect()
}
