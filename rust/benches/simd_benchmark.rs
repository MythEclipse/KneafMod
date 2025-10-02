use criterion::{black_box, criterion_group, criterion_main, Criterion};
use rustperf::spatial::{calculate_distances_simd, calculate_distances_simd_portable, filter_entities_by_distance_simd, batch_aabb_intersections};
use rustperf::spatial::{Aabb};
use bevy_ecs::prelude::Entity;
use rand::prelude::*;

/// Generate realistic test positions for SIMD benchmarks
fn generate_test_positions(count: usize) -> Vec<(f32, f32, f32)> {
    let mut rng = rand::thread_rng();
    (0..count)
        .map(|_| {
            (
                rng.gen_range(-100.0..100.0) as f32,
                rng.gen_range(-100.0..100.0) as f32,
                rng.gen_range(-100.0..100.0) as f32,
            )
        })
        .collect()
}

/// Generate test entities with positions
fn generate_test_entities(count: usize) -> Vec<(Entity, [f64; 3])> {
    let mut rng = rand::thread_rng();
    (0..count)
        .map(|i| {
            let x = rng.gen_range(-100.0..100.0);
            let y = rng.gen_range(-100.0..100.0);
            let z = rng.gen_range(-100.0..100.0);
            (Entity::from_raw(i as u32), [x, y, z])
        })
        .collect()
}

/// Generate test AABBs for batch intersection tests
fn generate_test_aabbs(count: usize) -> Vec<Aabb> {
    let mut rng = rand::thread_rng();
    (0..count)
        .map(|_| {
            let x = rng.gen_range(-50.0..50.0);
            let y = rng.gen_range(-50.0..50.0);
            let z = rng.gen_range(-50.0..50.0);
            let size = rng.gen_range(5.0..20.0);
            Aabb::new(x, y, z, x + size, y + size, z + size)
        })
        .collect()
}

fn bench_simd_distance_calculation(c: &mut Criterion) {
    let mut group = c.benchmark_group("simd_distance_calculation");

    for &batch_size in &[8, 32, 64, 100] {
        let positions = generate_test_positions(batch_size);
        let center = (0.0, 0.0, 0.0);

        group.bench_with_input(format!("simd_distances_{}", batch_size), &batch_size, |b, _| {
            b.iter(|| {
                let distances = calculate_distances_simd(black_box(&positions), black_box(center));
                black_box(distances);
            });
        });

        group.bench_with_input(format!("simd_portable_distances_{}", batch_size), &batch_size, |b, _| {
            b.iter(|| {
                let distances = calculate_distances_simd_portable(black_box(&positions), black_box(center));
                black_box(distances);
            });
        });
    }

    group.finish();
}

fn bench_simd_entity_filtering(c: &mut Criterion) {
    let mut group = c.benchmark_group("simd_entity_filtering");

    for &entity_count in &[100, 500, 1000] {
        let entities = generate_test_entities(entity_count);
        let center = [0.0, 0.0, 0.0];
        let max_distance = 50.0;

        group.bench_with_input(format!("filter_entities_{}", entity_count), &entity_count, |b, _| {
            b.iter(|| {
                let filtered = filter_entities_by_distance_simd(
                    black_box(&entities),
                    black_box(center),
                    black_box(max_distance)
                );
                black_box(filtered);
            });
        });
    }

    group.finish();
}

fn bench_simd_batch_aabb_intersections(c: &mut Criterion) {
    let mut group = c.benchmark_group("simd_batch_aabb_intersections");

    let aabbs = generate_test_aabbs(100);
    let queries = generate_test_aabbs(50);

    group.bench_function("batch_aabb_100x50", |b| {
        b.iter(|| {
            let results = batch_aabb_intersections(black_box(&aabbs), black_box(&queries));
            black_box(results);
        });
    });

    // Smaller batch for comparison
    let small_aabbs = generate_test_aabbs(20);
    let small_queries = generate_test_aabbs(10);

    group.bench_function("batch_aabb_20x10", |b| {
        b.iter(|| {
            let results = batch_aabb_intersections(black_box(&small_aabbs), black_box(&small_queries));
            black_box(results);
        });
    });

    group.finish();
}

fn bench_simd_chunk_distances(c: &mut Criterion) {
    let mut group = c.benchmark_group("simd_chunk_distances");

    for &chunk_count in &[8, 32, 64, 100] {
        let chunk_coords: Vec<(i32, i32)> = (0..chunk_count)
            .map(|i| (i as i32 * 16, (i % 10) as i32 * 16))
            .collect();
        let center_chunk = (0, 0);

        group.bench_with_input(format!("chunk_distances_{}", chunk_count), &chunk_count, |b, _| {
            b.iter(|| {
                let distances = rustperf::spatial::calculate_chunk_distances_simd(
                    black_box(&chunk_coords),
                    black_box(center_chunk)
                );
                black_box(distances);
            });
        });
    }

    group.finish();
}

criterion_group!(
    simd_benches,
    bench_simd_distance_calculation,
    bench_simd_entity_filtering,
    bench_simd_batch_aabb_intersections,
    bench_simd_chunk_distances
);
criterion_main!(simd_benches);