use rayon::prelude::*;

pub fn batch_spatial_hash(positions: &[f64], cell_size: f64, count: i32) -> Vec<i64> {
    let count = count as usize;
    if positions.len() < count * 3 {
        return vec![0; count];
    }

    (0..count)
        .into_par_iter()
        .map(|i| {
            let x_pos = positions[i * 3];
            let y_pos = positions[i * 3 + 1];
            let z_pos = positions[i * 3 + 2];

            let x = (x_pos / cell_size).floor() as i64;
            let y = (y_pos / cell_size).floor() as i64;
            let z = (z_pos / cell_size).floor() as i64;

            // Java logic:
            // ((long)x & 0x3FFFFF) | (((long)y & 0xFFF) << 22) | (((long)z & 0x3FFFFF) << 34);

            (x & 0x3FFFFF) | ((y & 0xFFF) << 22) | ((z & 0x3FFFFF) << 34)
        })
        .collect()
}
