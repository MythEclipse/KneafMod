use valence::prelude::*;
use crate::spatial::{Aabb, QuadTree, ChunkCoord, calculate_chunk_distances_simd};
use crate::resources::{SpatialPartition, PerformanceConfig, ChunkManager, TickCounter, Weather, GameTime};
use crate::spatial::ChunkData;
use crate::components::{Player, Block as OurBlock, Mob, EntityType, BlockType, ShouldTick, NoAi, ShouldTickBlock, ShouldTickAi, Velocity, Gravity, OnGround, Health, ItemDrop, Inventory as OurInventory, BlockState as OurBlockState};
use crate::events::*;


// Systems for performance optimizations
pub fn update_spatial_partition(
    mut spatial_partition: ResMut<SpatialPartition>,
    player_query: Query<(Entity, &Position), With<Player>>,
    entity_query: Query<(Entity, &Position), Without<Player>>,
) {
    // Rebuild quadtrees each tick - in production, optimize with incremental updates
    let world_bounds = Aabb::from_center_size(Vec3::ZERO, Vec3::splat(1000.0));
    spatial_partition.player_quadtree = QuadTree::new(world_bounds, 16, 8);
    spatial_partition.entity_quadtree = QuadTree::new(world_bounds, 16, 8);

    for (entity, pos) in player_query.iter() {
        spatial_partition.player_quadtree.insert(entity, pos.0.as_vec3(), 0);
    }

    for (entity, pos) in entity_query.iter() {
        spatial_partition.entity_quadtree.insert(entity, pos.0.as_vec3(), 0);
    }
}

pub fn distance_based_entity_throttling(
    mut commands: Commands,
    config: Res<PerformanceConfig>,
    tick_counter: Res<TickCounter>,
    player_query: Query<&Position, With<Player>>,
    entity_query: Query<(Entity, &Position, Option<&EntityType>), Without<Player>>,
) {
    let entity_config = config.entity_config.read();
    let exceptions = config.exceptions.read();

    // Collect all player positions
    let player_positions: Vec<Vec3> = player_query.iter().map(|pos| pos.0.as_vec3()).collect();

    if player_positions.is_empty() {
        return;
    }

    for (entity, pos, entity_type) in entity_query.iter() {
        // Skip critical entities - always tick block entities and entities in exception list
        if let Some(entity_type) = entity_type {
            if entity_type.is_block_entity ||
               exceptions.critical_entity_types.contains(&entity_type.entity_type) {
                commands.entity(entity).insert(ShouldTick);
                continue;
            }
        }

        let entity_pos = pos.0.as_vec3();

        // Find min distance to any player
        let min_distance = player_positions.iter()
            .map(|p| (p - entity_pos).length())
            .fold(f32::INFINITY, f32::min);

        let rate = if min_distance <= entity_config.close_radius {
            entity_config.close_rate
        } else if min_distance <= entity_config.medium_radius {
            entity_config.medium_rate
        } else {
            entity_config.far_rate
        };

        let period = (1.0 / rate) as u64;
        if period == 0 || (tick_counter.0 + entity.index() as u64) % period == 0 {
            commands.entity(entity).insert(ShouldTick);
        } else {
            commands.entity(entity).remove::<ShouldTick>();
        }
    }
}

pub fn mob_ai_optimization(
    mut commands: Commands,
    config: Res<PerformanceConfig>,
    tick_counter: Res<TickCounter>,
    player_query: Query<&Position, With<Player>>,
    mob_query: Query<(Entity, &Position), With<Mob>>,
) {
    let mob_config = config.mob_config.read();

    // Collect all player positions
    let player_positions: Vec<Vec3> = player_query.iter().map(|pos| pos.0.as_vec3()).collect();

    if player_positions.is_empty() {
        return;
    }

    for (entity, pos) in mob_query.iter() {
        let mob_pos = pos.0.as_vec3();

        // Find min distance to any player
        let min_distance = player_positions.iter()
            .map(|p| (p - mob_pos).length())
            .fold(f32::INFINITY, f32::min);

        // Disable AI for passive mobs beyond passive_disable_distance
        if min_distance > mob_config.passive_disable_distance {
            commands.entity(entity).insert(NoAi);
        } else {
            commands.entity(entity).remove::<NoAi>();
        }

        // Simplify AI for hostile mobs beyond hostile_simplify_distance
        if min_distance > mob_config.hostile_simplify_distance {
            // Throttle AI ticks
            let period = (1.0 / mob_config.ai_tick_rate_far) as u64;
            if period == 0 || (tick_counter.0 + entity.index() as u64) % period == 0 {
                commands.entity(entity).insert(ShouldTickAi);
            } else {
                commands.entity(entity).remove::<ShouldTickAi>();
            }
        } else {
            commands.entity(entity).insert(ShouldTickAi);
        }
    }
}

pub fn distance_based_block_throttling(
    mut commands: Commands,
    config: Res<PerformanceConfig>,
    tick_counter: Res<TickCounter>,
    player_query: Query<&Position, With<Player>>,
    block_query: Query<(Entity, &Position, Option<&BlockType>), With<OurBlock>>,
) {
    let block_config = config.block_config.read();
    let exceptions = config.exceptions.read();

    // Collect all player positions
    let player_positions: Vec<Vec3> = player_query.iter().map(|pos| pos.0.as_vec3()).collect();

    if player_positions.is_empty() {
        return;
    }

    for (entity, pos, block_type) in block_query.iter() {
        // Skip critical blocks - always tick blocks in exception list
        if let Some(block_type) = block_type {
            if exceptions.critical_block_types.contains(&block_type.block_type) {
                commands.entity(entity).insert(ShouldTickBlock);
                continue;
            }
        }

        let block_pos = pos.0.as_vec3();

        // Find min distance to any player
        let min_distance = player_positions.iter()
            .map(|p| (p - block_pos).length())
            .fold(f32::INFINITY, f32::min);

        let rate = if min_distance <= block_config.close_radius {
            block_config.close_rate
        } else if min_distance <= block_config.medium_radius {
            block_config.medium_rate
        } else {
            block_config.far_rate
        };

        let period = (1.0 / rate) as u64;
        if period == 0 || (tick_counter.0 + entity.index() as u64) % period == 0 {
            commands.entity(entity).insert(ShouldTickBlock);
        } else {
            commands.entity(entity).remove::<ShouldTickBlock>();
        }
    }
}

// Chunk management systems
pub fn update_chunk_manager(
    mut chunk_manager: ResMut<ChunkManager>,
    tick_counter: Res<TickCounter>,
    player_query: Query<&Position, With<Player>>,
    _entity_query: Query<(Entity, &Position), Without<Player>>,
    _block_query: Query<(Entity, &Position), With<OurBlock>>,
    _mob_query: Query<(Entity, &Position), With<Mob>>,
) {
    // Collect all player chunk coordinates
    let player_chunks: std::collections::HashSet<ChunkCoord> = player_query
        .iter()
        .map(|pos| ChunkCoord::from_world_pos(pos.0.as_vec3()))
        .collect();

    if player_chunks.is_empty() {
        return;
    }

    // Determine chunks to load (within view_distance of any player)
    let mut chunks_to_load = std::collections::HashSet::new();
    for &player_chunk in &player_chunks {
        for dx in -(chunk_manager.view_distance as i32)..=(chunk_manager.view_distance as i32) {
            for dz in -(chunk_manager.view_distance as i32)..=(chunk_manager.view_distance as i32) {
                if dx * dx + dz * dz <= (chunk_manager.view_distance * chunk_manager.view_distance) as i32 {
                    chunks_to_load.insert(ChunkCoord {
                        x: player_chunk.x + dx,
                        z: player_chunk.z + dz,
                    });
                }
            }
        }
    }

    // Load new chunks
    for &coord in &chunks_to_load {
        chunk_manager.loaded_chunks.entry(coord).or_insert_with(|| ChunkData {
            coord,
            entities: Vec::new(),
            blocks: Vec::new(),
            mobs: Vec::new(),
            last_accessed: tick_counter.0,
            is_loaded: true,
        });
    }

    // Update last_accessed for loaded chunks
    for (&coord, chunk) in &mut chunk_manager.loaded_chunks {
        if chunks_to_load.contains(&coord) {
            chunk.last_accessed = tick_counter.0;
        }
    }

    // Unload chunks beyond unload_distance
    let max_distance_sq = (chunk_manager.unload_distance * chunk_manager.unload_distance) as f32;
    chunk_manager.loaded_chunks.retain(|coord, chunk| {
        let min_distance_sq = player_chunks
            .iter()
            .map(|pc| coord.distance_squared(pc))
            .fold(f32::INFINITY, f32::min);

        if min_distance_sq > max_distance_sq {
            chunk.is_loaded = false;
            // Keep in map but mark as unloaded for potential future reuse
            false
        } else {
            true
        }
    });
}

pub fn update_chunk_entity_grouping(
    mut chunk_manager: ResMut<ChunkManager>,
    entity_query: Query<(Entity, &Position), Without<Player>>,
    block_query: Query<(Entity, &Position), With<OurBlock>>,
    mob_query: Query<(Entity, &Position), With<Mob>>,
) {
    // Clear existing groupings
    for chunk in chunk_manager.loaded_chunks.values_mut() {
        chunk.entities.clear();
        chunk.blocks.clear();
        chunk.mobs.clear();
    }

    // Group entities by chunk
    for (entity, pos) in entity_query.iter() {
        let coord = ChunkCoord::from_world_pos(pos.0.as_vec3());
        if let Some(chunk) = chunk_manager.loaded_chunks.get_mut(&coord) {
            chunk.entities.push((entity, pos.0.as_vec3()));
        }
    }

    // Group blocks by chunk
    for (entity, pos) in block_query.iter() {
        let coord = ChunkCoord::from_world_pos(pos.0.as_vec3());
        if let Some(chunk) = chunk_manager.loaded_chunks.get_mut(&coord) {
            chunk.blocks.push(entity);
        }
    }

    // Group mobs by chunk
    for (entity, pos) in mob_query.iter() {
        let coord = ChunkCoord::from_world_pos(pos.0.as_vec3());
        if let Some(chunk) = chunk_manager.loaded_chunks.get_mut(&coord) {
            chunk.mobs.push(entity);
        }
    }
}

pub fn chunk_based_entity_throttling(
    mut commands: Commands,
    config: Res<PerformanceConfig>,
    tick_counter: Res<TickCounter>,
    chunk_manager: Res<ChunkManager>,
    player_query: Query<&Position, With<Player>>,
) {
    let entity_config = config.entity_config.read();
    let _exceptions = config.exceptions.read();

    // Collect all player chunk coordinates
    let player_chunks: Vec<ChunkCoord> = player_query
        .iter()
        .map(|pos| ChunkCoord::from_world_pos(pos.0.as_vec3()))
        .collect();

    if player_chunks.is_empty() {
        return;
    }

    // Use SIMD to calculate distances for all loaded chunks to nearest player chunk
    let chunk_coords: Vec<(i32, i32)> = chunk_manager.loaded_chunks.keys()
        .map(|coord| (coord.x, coord.z))
        .collect();

    for (chunk_coord, _chunk_distances) in chunk_manager.loaded_chunks.iter()
        .zip(calculate_chunk_distances_simd(&chunk_coords, (0, 0)).into_iter()) {

        // Find actual min distance to any player chunk
        let min_distance = player_chunks.iter()
            .map(|pc| chunk_coord.0.distance_squared(pc))
            .fold(f32::INFINITY, f32::min);

        let chunk_distance = min_distance.sqrt() * 16.0; // Convert chunk distance to block distance

        let rate = if chunk_distance <= entity_config.close_radius {
            entity_config.close_rate
        } else if chunk_distance <= entity_config.medium_radius {
            entity_config.medium_rate
        } else {
            entity_config.far_rate
        };

        // Apply throttling to all entities in this chunk
        for &(entity, _pos) in &chunk_coord.1.entities {
            let period = (1.0 / rate) as u64;
            if period == 0 || (tick_counter.0 + entity.index() as u64) % period == 0 {
                commands.entity(entity).insert(ShouldTick);
            } else {
                commands.entity(entity).remove::<ShouldTick>();
            }
        }
    }
}

pub fn chunk_based_block_throttling(
    mut commands: Commands,
    config: Res<PerformanceConfig>,
    tick_counter: Res<TickCounter>,
    chunk_manager: Res<ChunkManager>,
    player_query: Query<&Position, With<Player>>,
) {
    let block_config = config.block_config.read();
    let _exceptions = config.exceptions.read();

    // Collect all player chunk coordinates
    let player_chunks: Vec<ChunkCoord> = player_query
        .iter()
        .map(|pos| ChunkCoord::from_world_pos(pos.0.as_vec3()))
        .collect();

    if player_chunks.is_empty() {
        return;
    }

    for (chunk_coord, chunk) in &chunk_manager.loaded_chunks {
        // Find min distance to any player chunk
        let min_distance = player_chunks.iter()
            .map(|pc| chunk_coord.distance_squared(pc))
            .fold(f32::INFINITY, f32::min);

        let chunk_distance = min_distance.sqrt() * 16.0; // Convert chunk distance to block distance

        let rate = if chunk_distance <= block_config.close_radius {
            block_config.close_rate
        } else if chunk_distance <= block_config.medium_radius {
            block_config.medium_rate
        } else {
            block_config.far_rate
        };

        // Apply throttling to all blocks in this chunk
        for &entity in &chunk.blocks {
            let period = (1.0 / rate) as u64;
            if period == 0 || (tick_counter.0 + entity.index() as u64) % period == 0 {
                commands.entity(entity).insert(ShouldTickBlock);
            } else {
                commands.entity(entity).remove::<ShouldTickBlock>();
            }
        }
    }
}

pub fn chunk_based_mob_ai_optimization(
    mut commands: Commands,
    config: Res<PerformanceConfig>,
    tick_counter: Res<TickCounter>,
    chunk_manager: Res<ChunkManager>,
    player_query: Query<&Position, With<Player>>,
) {
    let mob_config = config.mob_config.read();

    // Collect all player chunk coordinates
    let player_chunks: Vec<ChunkCoord> = player_query
        .iter()
        .map(|pos| ChunkCoord::from_world_pos(pos.0.as_vec3()))
        .collect();

    if player_chunks.is_empty() {
        return;
    }

    for (chunk_coord, chunk) in &chunk_manager.loaded_chunks {
        // Find min distance to any player chunk
        let min_distance = player_chunks.iter()
            .map(|pc| chunk_coord.distance_squared(pc))
            .fold(f32::INFINITY, f32::min);

        let chunk_distance = min_distance.sqrt() * 16.0; // Convert chunk distance to block distance

        // Apply AI optimization to all mobs in this chunk
        for &entity in &chunk.mobs {
            // Disable AI for passive mobs beyond passive_disable_distance
            if chunk_distance > mob_config.passive_disable_distance {
                commands.entity(entity).insert(NoAi);
            } else {
                commands.entity(entity).remove::<NoAi>();
            }

            // Simplify AI for hostile mobs beyond hostile_simplify_distance
            if chunk_distance > mob_config.hostile_simplify_distance {
                // Throttle AI ticks
                let period = (1.0 / mob_config.ai_tick_rate_far) as u64;
                if period == 0 || (tick_counter.0 + entity.index() as u64) % period == 0 {
                    commands.entity(entity).insert(ShouldTickAi);
                } else {
                    commands.entity(entity).remove::<ShouldTickAi>();
                }
            } else {
                commands.entity(entity).insert(ShouldTickAi);
            }
        }
    }
}

// Tick counter update system
pub fn update_tick_counter(mut counter: ResMut<TickCounter>) {
    counter.0 += 1;
}

// Complex gameplay systems
pub fn apply_gravity(
    mut query: Query<(&mut Velocity, &mut Position), With<Gravity>>,
) {
    for (mut vel, mut pos) in query.iter_mut() {
        vel.y -= 0.08; // Minecraft gravity acceleration
        pos.0.y += vel.y as f64;
    }
}

pub fn collision_detection(
    mut query: Query<(&mut Position, &mut Velocity, Option<&mut OnGround>), With<Gravity>>,
) {
    for (mut pos, mut vel, on_ground) in query.iter_mut() {
        // Simple ground collision at y=0
        if pos.0.y <= 0.0 {
            pos.0.y = 0.0;
            vel.y = 0.0;
            if let Some(_on_ground) = on_ground {
                // Already on ground
            } else {
                // Add OnGround component if not present
            }
        } else {
            // Remove OnGround if in air
        }
    }
}

pub fn block_update_system(
    mut events: EventReader<BlockBreakEvent>,
    mut commands: Commands,
) {
    for event in events.read() {
        // Spawn item drop at block position
        commands.spawn((
            Position(DVec3::new(event.position.x as f64, event.position.y as f64, event.position.z as f64)),
            Velocity { x: 0.0, y: 0.0, z: 0.0 },
            Gravity,
            ItemDrop { item_kind: event.block_type.clone(), count: 1 },
        ));
    }
}

pub fn item_pickup_system(
    mut commands: Commands,
    mut player_query: Query<(&Position, &mut OurInventory), With<Player>>,
    item_query: Query<(Entity, &Position, &ItemDrop), Without<Player>>,
) {
    for (player_pos, mut inventory) in player_query.iter_mut() {
        for (entity, item_pos, item_drop) in item_query.iter() {
            let distance = (player_pos.0.as_vec3() - item_pos.0.as_vec3()).length();
            if distance < 1.0 {
                // Add item to inventory
                inventory.items.push((item_drop.item_kind.clone(), item_drop.count));
                commands.entity(entity).despawn();
            }
        }
    }
}

pub fn combat_system(
    mut events: EventReader<AttackEvent>,
    mut query: Query<&mut Health>,
    mut commands: Commands,
) {
    for event in events.read() {
        if let Ok(mut health) = query.get_mut(event.target) {
            health.current -= event.damage;
            if health.current <= 0.0 {
                commands.entity(event.target).despawn();
            }
        }
    }
}

pub fn weather_update(
    mut weather: ResMut<Weather>,
    tick: Res<TickCounter>,
) {
    // Change weather every 12000 ticks (10 minutes)
    if tick.0 % 12000 == 0 {
        weather.raining = rand::random::<bool>();
        weather.thunder = weather.raining && rand::random::<f32>() < 0.1;
    }
}

pub fn time_update(mut time: ResMut<GameTime>) {
    time.time += 1;
    if time.time >= time.day_length {
        time.time = 0;
    }
}

pub fn mob_spawning(
    mut commands: Commands,
    weather: Res<Weather>,
    player_query: Query<&Position, With<Player>>,
) {
    if weather.raining {
        for player_pos in player_query.iter() {
            // Spawn zombie near player
            let offset = Vec3::new(
                rand::random::<f32>() * 20.0 - 10.0,
                0.0,
                rand::random::<f32>() * 20.0 - 10.0,
            );
            let spawn_pos = player_pos.0.as_vec3() + offset;
            commands.spawn((
                Position(DVec3::new(spawn_pos.x as f64, spawn_pos.y as f64, spawn_pos.z as f64)),
                Velocity { x: 0.0, y: 0.0, z: 0.0 },
                Gravity,
                Health { current: 20.0, max: 20.0 },
                Mob,
                EntityType { entity_type: "minecraft:zombie".to_string(), is_block_entity: false },
            ));
        }
    }
}

pub fn pathfinding_ai(
    mut query: Query<(&Position, &mut Velocity), With<Mob>>,
    player_query: Query<&Position, With<Player>>,
) {
    if let Some(player_pos) = player_query.iter().next() {
        for (mob_pos, mut vel) in query.iter_mut() {
            let direction = (player_pos.0.as_vec3() - mob_pos.0.as_vec3()).normalize();
            vel.x = direction.x * 0.1;
            vel.z = direction.z * 0.1;
        }
    }
}

pub fn redstone_update(
    mut query: Query<&mut OurBlockState>,
) {
    // Simple redstone propagation
    for mut state in query.iter_mut() {
        if state.block_type == "minecraft:redstone_wire" {
            // Propagate power
            if state.powered {
                state.power_level = 15;
            } else {
                state.power_level = 0;
            }
        }
    }
}

pub fn crafting_system(
    mut player_query: Query<&mut OurInventory, With<Player>>,
) {
    for mut inventory in player_query.iter_mut() {
        // Simple crafting: 1 log -> 4 planks
        let log_count = inventory.items.iter().find(|(item, _)| item == "minecraft:oak_log").map(|(_, c)| *c).unwrap_or(0);
        if log_count >= 1 {
            // Remove log
            if let Some(index) = inventory.items.iter().position(|(item, _)| item == "minecraft:oak_log") {
                inventory.items[index].1 -= 1;
                if inventory.items[index].1 <= 0 {
                    inventory.items.remove(index);
                }
            }
            // Add planks
            inventory.items.push(("minecraft:oak_planks".to_string(), 4));
        }
    }
}

pub fn particle_effects(
    query: Query<&Position, With<Mob>>,
) {
    for _pos in query.iter() {
        // Spawn particles (logging for now)
    }
}

pub fn sound_effects(
    mut events: EventReader<AttackEvent>,
) {
    for _event in events.read() {
        // Play attack sound
    }
}

pub fn multiplayer_interactions(
    mut events: EventWriter<AttackEvent>,
    player_query: Query<(Entity, &Position), With<Player>>,
) {
    let players: Vec<_> = player_query.iter().collect();
    for i in 0..players.len() {
        for j in (i + 1)..players.len() {
            let (e1, p1) = players[i];
            let (e2, p2) = players[j];
            let distance = (p1.0.as_vec3() - p2.0.as_vec3()).length();
            if distance < 2.0 {
                events.send(AttackEvent { attacker: e1, target: e2, damage: 5.0 });
            }
        }
    }
}