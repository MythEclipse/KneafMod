use criterion::{black_box, criterion_group, criterion_main, Criterion};
use rustperf::spatial::{QuadTree, Aabb};
use bevy_ecs::prelude::Entity;
use rand::prelude::*;

/// Generate realistic test data for spatial benchmarks
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

fn bench_quadtree_insertion(c: &mut Criterion) {
    let mut group = c.benchmark_group("quadtree_insertion");

    for size in [100, 500, 1000].iter() {
        group.bench_with_input(format!("insert_{}", size), size, |b, &size| {
            let entities = generate_test_entities(size);
            let bounds = Aabb::new(-200.0, -200.0, -200.0, 200.0, 200.0, 200.0);

            b.iter(|| {
                let mut quadtree = QuadTree::new(bounds.clone(), 10, 5);
                for (entity, pos) in &entities {
                    quadtree.insert(black_box(*entity), black_box(*pos), 0);
                }
                black_box(quadtree);
            });
        });
    }

    group.finish();
}

fn bench_quadtree_query(c: &mut Criterion) {
    let mut group = c.benchmark_group("quadtree_query");

    for size in [100, 500, 1000].iter() {
        group.bench_with_input(format!("query_{}", size), size, |b, &size| {
            let entities = generate_test_entities(size);
            let bounds = Aabb::new(-200.0, -200.0, -200.0, 200.0, 200.0, 200.0);
            let mut quadtree = QuadTree::new(bounds.clone(), 10, 5);

            // Pre-populate the quadtree
            for (entity, pos) in &entities {
                quadtree.insert(*entity, *pos, 0);
            }

            // Query bounds that should capture roughly half the entities
            let query_bounds = Aabb::new(-50.0, -50.0, -50.0, 50.0, 50.0, 50.0);

            b.iter(|| {
                let results = quadtree.query_simd(black_box(&query_bounds));
                black_box(results);
            });
        });
    }

    group.finish();
}

fn bench_quadtree_batch_queries(c: &mut Criterion) {
    let mut group = c.benchmark_group("quadtree_batch_queries");

    let entities = generate_test_entities(1000);
    let bounds = Aabb::new(-200.0, -200.0, -200.0, 200.0, 200.0, 200.0);
    let mut quadtree = QuadTree::new(bounds.clone(), 10, 5);

    // Pre-populate the quadtree
    for (entity, pos) in &entities {
        quadtree.insert(*entity, *pos, 0);
    }

    // Generate multiple query bounds
    let query_bounds: Vec<Aabb> = (0..10)
        .map(|i| {
            let offset = i as f64 * 20.0 - 100.0;
            Aabb::new(offset, offset, offset, offset + 50.0, offset + 50.0, offset + 50.0)
        })
        .collect();

    group.bench_function("batch_10_queries", |b| {
        b.iter(|| {
            let results = rustperf::spatial::batch_quadtree_queries(
                black_box(&quadtree),
                black_box(&query_bounds)
            );
            black_box(results);
        });
    });

    group.finish();
}

criterion_group!(
    spatial_benches,
    bench_quadtree_insertion,
    bench_quadtree_query,
    bench_quadtree_batch_queries
);
criterion_main!(spatial_benches);