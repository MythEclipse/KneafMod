# KneafMod Optimization Library

This is a Rust-based optimization library for Minecraft mods, providing high-performance parallel processing for entity, mob, block, and spatial operations.

## Features

- **Parallel Processing**: Utilizes Rayon for parallel computation of entity, mob, and block processing
- **SIMD Operations**: Optimized distance calculations using AVX2 SIMD instructions
- **Spatial Partitioning**: Quadtree-based spatial partitioning for efficient entity queries
- **JNI Integration**: Seamless integration with Java-based Minecraft mods
- **Batch Processing**: Support for processing entities in batches for better performance
- **FastNBT Mode**: Optional high-performance NBT serialization using Rust's fastnbt library for improved chunk storage performance

## New Features & Optimizations (Latest Update)

### Enhanced Performance Manager

- **Multithreaded Optimization Processing**: Now uses asynchronous multithreading for better server performance
- **Reduced Processing Frequency**: Optimized to process every 3 ticks instead of every tick, reducing CPU load
- **Enhanced Logging**: Structured JSON logging with periodic spatial grid statistics and meaningful optimization summaries

### Network Optimizer Improvements

- **Increased Batch Size**: Enhanced throughput with larger batch processing (increased from 10)
- **Reduced Code Clutter**: Removed unused methods like `calculateRateLimitDelay` for cleaner codebase

### Rust Core Optimizations

- **SIMD-Accelerated Entity Filtering**: Enhanced performance with vectorized entity filtering operations
- **Proximity-Based Optimization**: Reduced processing overhead with intelligent proximity calculations
- **Manhattan Distance Enhancement**: Improved spatial calculations for better performance
- **Reduced Debug Noise**: Removed excessive debug logging to improve performance and reduce log clutter

### Memory Management

- **Native Float Buffer Pool**: Enhanced memory pooling for native operations
- **Optimized Memory Allocation**: Improved allocator performance for better memory efficiency

### Enhanced Native Bridge

- **Improved Batch Operations**: Enhanced batch processing with result callbacks
- **Better Error Handling**: Comprehensive error handling for native operations
- **Heavy Job Processing**: Optimized processing for resource-intensive operations

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
10. **FastNBT Serialization**: High-performance NBT processing for chunk data when enabled

## Commands

- `/rustperf status`: Shows current TPS, CPU usage, and memory statistics

## Architecture

- `src/lib.rs`: JNI bindings and main library interface
- `src/entity/`: Entity processing with parallel batch operations
- `src/mob/`: Mob processing without throttling
- `src/block/`: Block entity processing
- `src/spatial.rs`: Quadtree spatial partitioning
- `src/shared/`: Shared utilities and SIMD operations
- `src/performance_monitoring.rs`: Performance monitoring and metrics
- `src/memory_pool.rs`: Memory pooling for efficient allocation
- `src/allocator.rs`: Optimized memory allocation
- `src/jni_batch_processor.rs`: JNI batch processing operations
