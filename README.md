# KneafMod Optimization Library

This is a Rust-based optimization library for Minecraft mods, providing high-performance parallel processing for entity, mob, block, and spatial operations.

## Features

- **Parallel Processing**: Utilizes Rayon for parallel computation of entity, mob, and block processing
- **SIMD Operations**: Optimized distance calculations using AVX2 SIMD instructions
- **Spatial Partitioning**: Quadtree-based spatial partitioning for efficient entity queries
- **JNI Integration**: Seamless integration with Java-based Minecraft mods
- **Batch Processing**: Support for processing entities in batches for better performance

## Performance Optimizations

The library achieves performance gains through:

1. **Parallel Entity Processing**: Entities are processed in parallel using Rayon's parallel iterators
2. **Parallel Mob Processing**: Mob AI and behavior calculations run concurrently
3. **Parallel Block Processing**: Block entity updates are parallelized
4. **SIMD Distance Calculations**: Vectorized operations for distance computations
5. **Spatial Indexing**: Quadtree structure for fast spatial queries
6. **Entity Grouping**: Entities grouped by type for optimized processing
7. **Async Chunk Loading**: Pre-load chunks asynchronously to reduce lag
8. **Connection Pooling**: Framework ready for future database integrations
9. **Memory Pooling**: Object reuse to minimize garbage collection pressure

## Usage

Add this to your `Cargo.toml`:

```toml
[dependencies]
rustperf = { path = "../rust" }
```

Then use the JNI bindings from your Java mod:

```java
// Example usage from Java
RustPerformance.processEntities(entities);
RustPerformance.processMobs(mobs);
RustPerformance.processBlocks(blocks);
```

## Configuration

The mod can be configured via `config/kneafcore-common.toml`:

```toml
[kneafcore.performance]
enableRustOptimizations = true
spatialMaxDepth = 8
entityCloseRadius = 16.0
entityMediumRadius = 32.0
entityCloseRate = 1.0
entityMediumRate = 0.5
entityFarRate = 0.1
useSpatialPartitioning = true
itemMergeRadius = 1.0
itemDespawnTime = 6000
verboseLogging = false
tpsAlertThreshold = 15.0
```

## Commands

- `/rustperf status`: Shows current TPS, CPU usage, and memory statistics

## Async Operations

The mod supports asynchronous operations for better performance:

```java
// Async chunk pre-generation
CompletableFuture<Integer> future = RustPerformance.preGenerateNearbyChunksAsync(x, z, radius);
future.thenAccept(chunksGenerated -> {
    // Handle result
});
```

## Connection Pooling

Framework for database connections (ready for future use):

```java
try (Connection conn = RustPerformance.acquireConnection()) {
    // Use connection
} catch (SQLException e) {
    // Handle error
}
```

## Building

```bash
cd rust
cargo build --release
```

## Testing

```bash
cd rust
cargo test
```

## Benchmarks

Run performance benchmarks:

```bash
cd rust
cargo bench
```

## Architecture

- `src/lib.rs`: JNI bindings and main library interface
- `src/entity/`: Entity processing with parallel batch operations
- `src/mob/`: Mob processing without throttling
- `src/block/`: Block entity processing
- `src/spatial.rs`: Quadtree spatial partitioning
- `src/shared/`: Shared utilities and SIMD operations
