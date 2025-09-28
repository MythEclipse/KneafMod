use criterion::{black_box, criterion_group, criterion_main, Criterion};
use valence::prelude::*;
use valence::math::Vec3;
use std::sync::Arc;
use parking_lot::RwLock;

// Benchmark comparing old JNI-style processing vs new ECS processing

fn bench_entity_processing_old_style(c: &mut Criterion) {
    // Simulate old-style entity processing (similar to what was in entity/processing.rs)
    let entities = create_test_entities(1000);
    let player_pos = Vec3::new(0.0, 0.0, 0.0);

    c.bench_function("entity_processing_old_style", |b| {
        b.iter(|| {
            let mut processed = 0;
            let mut throttled = 0;

            for entity in &entities {
                let distance = (entity.position - player_pos).length();

                // Simple distance-based throttling logic
                let should_tick = if distance < 32.0 {
                    true
                } else if distance < 64.0 {
                    processed % 2 == 0 // 50% throttling
                } else {
                    processed % 10 == 0 // 10% throttling
                };

                if should_tick {
                    processed += 1;
                } else {
                    throttled += 1;
                }
            }

            black_box((processed, throttled))
        })
    });
}

fn bench_entity_processing_ecs(c: &mut Criterion) {
    // Benchmark ECS-style entity processing
    let mut app = App::new();
    app.add_plugins(MinimalPlugins);

    // Add resources
    app.insert_resource(crate::valence_integration::PerformanceConfig {
        entity_config: Arc::new(RwLock::new(crate::entity::Config {
            close_radius: 32.0,
            medium_radius: 64.0,
            close_rate: 1.0,
            medium_rate: 0.5,
            far_rate: 0.1,
        })),
        block_config: Arc::new(RwLock::new(crate::block::BlockConfig::default())),
        mob_config: Arc::new(RwLock::new(crate::mob::AiConfig::default())),
        exceptions: Arc::new(RwLock::new(crate::shared::ExceptionsConfig::default())),
    });

    app.insert_resource(crate::valence_integration::TickCounter(0));

    // Register components
    app.register_component::<crate::valence_integration::Player>();
    app.register_component::<crate::valence_integration::ShouldTick>();

    // Add systems
    app.add_systems(Update, crate::valence_integration::distance_based_entity_throttling);

    // Create test entities
    let player_entity = app.world_mut().spawn((
        crate::valence_integration::Player,
        Position(Vec3::new(0.0, 0.0, 0.0)),
    )).id();

    // Create many entities at different distances
    for i in 0..1000 {
        let distance = (i % 100) as f32 * 2.0; // 0, 2, 4, ..., 198
        let angle = (i as f32 * 0.1).to_radians();
        let x = distance * angle.cos();
        let z = distance * angle.sin();

        app.world_mut().spawn(Position(Vec3::new(x, 0.0, z)));
    }

    c.bench_function("entity_processing_ecs", |b| {
        b.iter(|| {
            app.update();
            black_box(())
        })
    });
}

fn bench_spatial_partitioning(c: &mut Criterion) {
    let bounds = crate::valence_integration::Aabb::from_center_size(Vec3::ZERO, Vec3::splat(1000.0));
    let mut quadtree = crate::valence_integration::QuadTree::new(bounds, 16, 8);

    // Insert many entities
    let mut entities = Vec::new();
    for i in 0..10000 {
        let x = (i % 100) as f32 * 10.0 - 500.0;
        let z = (i / 100) as f32 * 10.0 - 500.0;
        let pos = Vec3::new(x, 0.0, z);
        let entity = valence::entity::Entity::from_raw(i as u32);
        quadtree.insert(entity, pos, 0);
        entities.push((entity, pos));
    }

    c.bench_function("spatial_partitioning_quadtree", |b| {
        b.iter(|| {
            let query_bounds = crate::valence_integration::Aabb::from_center_size(Vec3::new(100.0, 0.0, 100.0), Vec3::splat(50.0));
            let results = quadtree.query(query_bounds);
            black_box(results.len())
        })
    });
}

fn bench_simd_distance_calculation(c: &mut Criterion) {
    // Create test positions
    let positions: Vec<(f32, f32, f32)> = (0..10000).map(|i| {
        let x = (i % 100) as f32;
        let y = ((i / 100) % 100) as f32;
        let z = (i / 10000) as f32;
        (x, y, z)
    }).collect();

    let center = (500.0, 500.0, 500.0);

    c.bench_function("simd_distance_calculation", |b| {
        b.iter(|| {
            let distances = crate::valence_integration::calculate_distances_simd(&positions, center);
            black_box(distances)
        })
    });
}

fn bench_chunk_distance_calculation(c: &mut Criterion) {
    // Create test chunk coordinates
    let chunk_coords: Vec<(i32, i32)> = (0..1000).map(|i| {
        let x = (i % 32) - 16; // -16 to 15
        let z = (i / 32) - 16; // -16 to 15
        (x, z)
    }).collect();

    let center_chunk = (0, 0);

    c.bench_function("chunk_distance_calculation_simd", |b| {
        b.iter(|| {
            let distances = crate::valence_integration::calculate_chunk_distances_simd(&chunk_coords, center_chunk);
            black_box(distances)
        })
    });
}

fn bench_chunk_management(c: &mut Criterion) {
    let mut chunk_manager = crate::valence_integration::ChunkManager {
        loaded_chunks: std::collections::HashMap::new(),
        view_distance: 8,
        unload_distance: 12,
    };

    // Pre-populate with chunks
    for x in -10..=10 {
        for z in -10..=10 {
            let coord = crate::valence_integration::ChunkCoord { x, z };
            chunk_manager.loaded_chunks.insert(coord, crate::valence_integration::ChunkData {
                coord,
                entities: vec![],
                blocks: vec![],
                mobs: vec![],
                last_accessed: 0,
                is_loaded: true,
            });
        }
    }

    c.bench_function("chunk_management_update", |b| {
        b.iter(|| {
            // Simulate chunk management update with player movement
            let player_chunks = vec![
                crate::valence_integration::ChunkCoord { x: 5, z: 5 },
                crate::valence_integration::ChunkCoord { x: -3, z: 2 },
            ];

            // This would normally be done in the update_chunk_manager system
            let mut chunks_to_load = std::collections::HashSet::new();
            for &player_chunk in &player_chunks {
                for dx in -8..=8 {
                    for dz in -8..=8 {
                        if dx * dx + dz * dz <= 64 { // view_distance squared
                            chunks_to_load.insert(crate::valence_integration::ChunkCoord {
                                x: player_chunk.x + dx,
                                z: player_chunk.z + dz,
                            });
                        }
                    }
                }
            }

            black_box(chunks_to_load.len())
        })
    });
}

fn create_test_entities(count: usize) -> Vec<TestEntity> {
    (0..count).map(|i| {
        let distance = (i % 100) as f32 * 2.0;
        let angle = (i as f32 * 0.1).to_radians();
        TestEntity {
            position: Vec3::new(
                distance * angle.cos(),
                0.0,
                distance * angle.sin(),
            ),
        }
    }).collect()
}

struct TestEntity {
    position: Vec3,
}

criterion_group!(
    benches,
    bench_entity_processing_old_style,
    bench_entity_processing_ecs,
    bench_spatial_partitioning,
    bench_simd_distance_calculation,
    bench_chunk_distance_calculation,
    bench_chunk_management
);
criterion_main!(benches);