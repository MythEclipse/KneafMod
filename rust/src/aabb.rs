use rayon::prelude::*;

pub fn batch_aabb_intersection(boxes: &[f64], test_box: &[f64], count: i32) -> Vec<i32> {
    let count = count as usize;
    if boxes.len() < count * 6 || test_box.len() < 6 {
        return vec![0; count];
    }

    // Unpack test box
    let t_min_x = test_box[0];
    let t_min_y = test_box[1];
    let t_min_z = test_box[2];
    let t_max_x = test_box[3];
    let t_max_y = test_box[4];
    let t_max_z = test_box[5];

    (0..count)
        .into_par_iter()
        .map(|i| {
            let base = i * 6;
            let min_x = boxes[base];
            let min_y = boxes[base + 1];
            let min_z = boxes[base + 2];
            let max_x = boxes[base + 3];
            let max_y = boxes[base + 4];
            let max_z = boxes[base + 5];

            // Java Logic:
            // (minX < t[3] && maxX > t[0] && minY < t[4] && maxY > t[1] && minZ < t[5] && maxZ > t[2]) ? 1 : 0;
            // t[3] is maxX of test box

            if min_x < t_max_x
                && max_x > t_min_x
                && min_y < t_max_y
                && max_y > t_min_y
                && min_z < t_max_z
                && max_z > t_min_z
            {
                1
            } else {
                0
            }
        })
        .collect()
}
