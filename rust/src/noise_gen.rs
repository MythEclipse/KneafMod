use rayon::prelude::*;

pub fn batch_noise_generate_3d(seed: i64, coords: &[f32], count: i32) -> Vec<f32> {
    let count = count as usize;
    // We expect coords to have 3 * count elements
    if coords.len() < count * 3 {
        return vec![0.0; count];
    }

    // Use parallel iterator for large batches
    (0..count)
        .into_par_iter()
        .map(|i| {
            let x = coords[i * 3];
            let y = coords[i * 3 + 1];
            let z = coords[i * 3 + 2];

            // Match the logic in Java benchmark: sin(x*0.1 + seed) * cos(y*0.1) * sin(z*0.1)
            // Using f32 trig functions
            let s = seed as f32;
            (x * 0.1 + s).sin() * (y * 0.1).cos() * (z * 0.1).sin()
        })
        .collect()
}
