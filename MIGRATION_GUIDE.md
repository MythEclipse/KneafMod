# KneafMod Migration Guide

## Overview

This guide provides detailed instructions for migrating from previous versions of KneafMod to version 1.0.0, which includes significant architectural improvements, new features, and breaking changes.

## Table of Contents

1. [Breaking Changes](#breaking-changes)
2. [Configuration Migration](#configuration-migration)
3. [API Changes](#api-changes)
4. [Code Migration Examples](#code-migration-examples)
5. [Dependency Updates](#dependency-updates)
6. [Testing Migration](#testing-migration)
7. [Performance Considerations](#performance-considerations)
8. [Troubleshooting](#troubleshooting)

## Breaking Changes

### Version 1.0.0 Breaking Changes

1. **Configuration Format**: Updated configuration structure in `kneafcore-common.toml`
2. **API Changes**: Enhanced NativeBridge interface with callback support
3. **Exception Hierarchy**: New exception classes replace legacy exceptions
4. **Logging Format**: Changed to structured JSON logging format
5. **Async Processing**: New AsyncProcessor replaces manual CompletableFuture handling
6. **Protocol Processing**: Unified binary/JSON protocol processing

### Deprecated Components

- `DatabaseOperationException` → Use `DatabaseException`
- `RustPerformanceException` → Use `NativeLibraryException`
- `BatchRequestInterruptedException` → Use `AsyncProcessingException`
- Manual CompletableFuture handling → Use `AsyncProcessor`
- Direct JNI calls → Use `EnhancedNativeBridge`

## Configuration Migration

### Old Configuration (v0.9.0)

```toml
[kneafcore]
enableOptimizations = true
spatialDepth = 6
entityRadius = 16.0
itemMergeDistance = 1.0
itemDespawnTime = 6000
```

### New Configuration (v1.0.0)

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

[kneafcore.async]
defaultTimeoutMs = 30000
maxRetries = 3
retryDelayMs = 1000
enableMetrics = true

[kneafcore.memory]
poolSize = 1000
enablePooling = true
cleanupInterval = 300

[kneafcore.protocol]
defaultProtocol = "AUTO"
enableBinaryProtocol = true
enableJsonProtocol = true
```

### Migration Steps

1. **Backup existing configuration** before making changes
2. **Update section headers** to new format
3. **Add new configuration options** with appropriate values
4. **Remove deprecated options**
5. **Validate configuration** using built-in validation

## API Changes

### Exception Handling Migration

#### Old Exception Handling
```java
// v0.9.0
try {
    RustPerformance.processEntities(entities);
} catch (RustPerformanceException e) {
    logger.error("Native processing failed: " + e.getMessage());
} catch (DatabaseOperationException e) {
    logger.error("Database operation failed: " + e.getMessage());
}
```

#### New Exception Handling
```java
// v1.0.0
try {
    EnhancedNativeBridge.processBatchAsync(entities, result -> {
        if (result.isSuccess()) {
            processResults(result.getData());
        } else {
            handleError(result.getError());
        }
    });
} catch (NativeLibraryException e) {
    logger.error("Native library error [{}]: {}", 
                 e.getErrorType(), e.getMessage(), e);
} catch (DatabaseException e) {
    logger.error("Database error [{}] for {}: {}", 
                 e.getErrorType(), e.getDatabaseType(), e.getMessage(), e);
} catch (KneafCoreException e) {
    logger.error("KneafCore error [{}] in operation '{}': {}", 
                 e.getCategory(), e.getOperation(), e.getMessage(), e);
}
```

### Async Processing Migration

#### Old Async Processing
```java
// v0.9.0
CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> {
    try {
        return RustPerformance.preGenerateNearbyChunks(x, z, radius);
    } catch (Exception e) {
        throw new RuntimeException("Chunk generation failed", e);
    }
});

future.thenAccept(chunksGenerated -> {
    logger.info("Generated {} chunks", chunksGenerated);
}).exceptionally(throwable -> {
    logger.error("Chunk generation failed", throwable);
    return null;
});
```

#### New Async Processing
```java
// v1.0.0
AsyncProcessor processor = AsyncProcessor.create();

processor.supplyAsync(() -> {
    return RustPerformance.preGenerateNearbyChunks(x, z, radius);
}, 30000, "chunk-generation") // 30-second timeout
.thenAccept(chunksGenerated -> {
    logger.info("Generated {} chunks", chunksGenerated);
})
.exceptionally(throwable -> {
    logger.error("Chunk generation failed", throwable);
    return null;
});
```

### Protocol Processing Migration

#### Old Protocol Handling
```java
// v0.9.0 - Manual protocol handling
public Result processData(InputData input) {
    try {
        // Try binary protocol first
        ByteBuffer buffer = serializeToBinary(input);
        byte[] result = nativeCallBinary(buffer);
        if (result != null) {
            return deserializeBinary(result);
        }
    } catch (Exception e) {
        logger.warn("Binary protocol failed, falling back to JSON");
    }
    
    // Fallback to JSON
    try {
        String jsonInput = serializeToJson(input);
        String jsonResult = nativeCallJson(jsonInput);
        return deserializeJson(jsonResult);
    } catch (Exception e) {
        logger.error("Both protocols failed", e);
        throw new RuntimeException("Processing failed", e);
    }
}
```

#### New Protocol Processing
```java
// v1.0.0 - Unified protocol processing
ProtocolProcessor processor = ProtocolProcessor.createAuto(nativeAvailable);

ProtocolResult<Result> result = processor.processWithFallback(
    input,
    "dataProcessing",
    
    // Binary protocol handlers
    this::serializeToBinary,
    this::nativeCallBinary,
    this::deserializeBinary,
    
    // JSON protocol handlers
    this::prepareJsonInput,
    this::nativeCallJson,
    this::parseJsonResult,
    
    // Fallback result
    defaultResult
);

return result.getDataOrThrow(); // Throws exception if both protocols fail
```

## Code Migration Examples

### Complete Service Migration

#### Old Service Implementation
```java
@Service
public class EntityService {
    private static final Logger logger = LoggerFactory.getLogger(EntityService.class);
    
    public void processEntities(List<Entity> entities) {
        try {
            RustPerformance.processEntities(entities);
        } catch (Exception e) {
            logger.error("Entity processing failed", e);
            throw new RuntimeException("Processing failed", e);
        }
    }
    
    public CompletableFuture<Integer> countEntitiesAsync(List<Entity> entities) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return entities.size();
            } catch (Exception e) {
                throw new RuntimeException("Counting failed", e);
            }
        });
    }
}
```

#### New Service Implementation
```java
@Service
public class EntityService {
    private static final Logger logger = LoggerFactory.getLogger(EntityService.class);
    private final AsyncProcessor asyncProcessor;
    private final ProtocolProcessor protocolProcessor;
    
    public EntityService() {
        this.asyncProcessor = AsyncProcessor.create();
        this.protocolProcessor = ProtocolProcessor.createAuto(true);
    }
    
    public void processEntities(List<Entity> entities) {
        try {
            ProtocolResult<Void> result = protocolProcessor.processWithFallback(
                entities,
                "entityProcessing",
                
                // Binary processing
                this::serializeEntitiesBinary,
                this::callNativeEntityProcessing,
                bytes -> null, // No return value needed
                
                // JSON processing
                this::prepareEntitiesJson,
                this::callNativeEntityProcessingJson,
                json -> null, // No return value needed
                
                // Fallback
                null
            );
            
            result.getDataOrThrow(); // Validate success
            
        } catch (NativeLibraryException e) {
            logger.error("Native library error during entity processing", e);
            throw new KneafCoreException(KneafCoreException.ErrorCategory.NATIVE_LIBRARY, 
                                       "processEntities", "Entity processing failed", e);
        } catch (KneafCoreException e) {
            logger.error("KneafCore error during entity processing", e);
            throw e;
        }
    }
    
    public CompletableFuture<Integer> countEntitiesAsync(List<Entity> entities) {
        return asyncProcessor.supplyAsync(() -> entities.size(), 
                                        5000, // 5-second timeout
                                        "count-entities");
    }
    
    // Helper methods for protocol processing
    private ByteBuffer serializeEntitiesBinary(List<Entity> entities) {
        // Implementation for binary serialization
        return ByteBuffer.allocate(1024);
    }
    
    private byte[] callNativeEntityProcessing(ByteBuffer buffer) {
        // Implementation for native binary call
        return new byte[0];
    }
    
    private Map<String, Object> prepareEntitiesJson(List<Entity> entities) {
        // Implementation for JSON preparation
        return Map.of("entities", entities.size());
    }
    
    private String callNativeEntityProcessingJson(String json) {
        // Implementation for native JSON call
        return "{}";
    }
}
```

### Resource Management Migration

#### Old Resource Management
```java
public class ResourceManagerOld {
    private final List<Closeable> resources = new ArrayList<>();
    
    public void registerResource(Closeable resource) {
        resources.add(resource);
    }
    
    public void cleanup() {
        for (Closeable resource : resources) {
            try {
                resource.close();
            } catch (Exception e) {
                logger.error("Failed to close resource", e);
            }
        }
    }
}
```

#### New Resource Management
```java
public class ResourceManagerNew {
    private final ResourceManager resourceManager;
    
    public ResourceManagerNew() {
        this.resourceManager = ResourceManager.create("EntityResources");
    }
    
    public void registerResource(ManagedResource resource) {
        try {
            resourceManager.registerResource(resource);
        } catch (Exception e) {
            throw new KneafCoreException(KneafCoreException.ErrorCategory.RESOURCE_MANAGEMENT,
                                       "registerResource", "Failed to register resource", e);
        }
    }
    
    public void cleanup() {
        try {
            resourceManager.close(); // Proper lifecycle management
        } catch (IOException e) {
            throw new KneafCoreException(KneafCoreException.ErrorCategory.RESOURCE_MANAGEMENT,
                                       "cleanup", "Failed to cleanup resources", e);
        }
    }
    
    public ResourceHealthReport getHealth() {
        return resourceManager.checkHealth();
    }
}
```

## Dependency Updates

### Gradle Dependencies

#### Old Dependencies
```gradle
dependencies {
    implementation 'io.github.astonbitecode:j4rs:0.17.0'
    // Other dependencies
}
```

#### New Dependencies
```gradle
dependencies {
    implementation 'io.github.astonbitecode:j4rs:0.17.0'
    implementation 'com.google.code.gson:gson:2.10.1'
    implementation 'org.slf4j:slf4j-api:2.0.7'
    
    testImplementation 'org.junit.jupiter:junit-jupiter-api:5.10.0'
    testImplementation 'org.junit.jupiter:junit-jupiter-engine:5.10.0'
}
```

### Rust Dependencies

#### Updated Cargo.toml
```toml
[dependencies]
j4rs = "0.17"
serde = { version = "1.0", features = ["derive"] }
serde_json = "1.0"
jni = "0.21"
lazy_static = "1.4"
sysinfo = "0.30"
rayon = "1.8"
bevy_reflect = "0.14"
bevy_ecs = "0.14"
glam = "0.27"
nalgebra = "0.32"
ndarray = "0.15"
memmap2 = "0.5"
crossbeam = "0.8"
crossbeam-channel = "0.5"
dashmap = "5.5"
blake3 = "1.3"
log = "0.4"
env_logger = "0.10"
sled = "0.34"
chrono = { version = "0.4", features = ["serde"] }
num_cpus = "1.16"
fastrand = "2.0"
byteorder = "1.4"

[target.'cfg(not(target_os = "windows"))'.dependencies]
mimalloc = "0.1"

[dev-dependencies]
criterion = "0.5"
rand = "0.8"
```

## Testing Migration

### Test Framework Updates

#### Old Test Structure
```java
@Test
void testEntityProcessing() {
    try {
        RustPerformance.processEntities(entities);
        assertTrue(true);
    } catch (Exception e) {
        fail("Entity processing failed: " + e.getMessage());
    }
}
```

#### New Test Structure
```java
@Test
void testEntityProcessing() {
    // Use assumptions for native library availability
    try {
        Class.forName("com.kneaf.core.performance.RustPerformance");
    } catch (ClassNotFoundException e) {
        Assumptions.assumeTrue(false, "RustPerformance class not available");
        return;
    } catch (UnsatisfiedLinkError e) {
        Assumptions.assumeTrue(false, "Native library not available");
        return;
    }
    
    // Test with new API
    assertDoesNotThrow(() -> {
        ProtocolResult<Void> result = protocolProcessor.processWithFallback(
            entities,
            "testProcessing",
            this::serializeTestData,
            this::callNativeTest,
            bytes -> null,
            this::prepareTestJson,
            this::callNativeTestJson,
            json -> null,
            null
        );
        
        assertTrue(result.isSuccess());
    });
}
```

### Performance Test Migration

#### Old Performance Tests
```java
@Test
void testPerformance() {
    long start = System.currentTimeMillis();
    RustPerformance.processEntities(entities);
    long duration = System.currentTimeMillis() - start;
    
    assertTrue(duration < 1000, "Processing should complete within 1 second");
}
```

#### New Performance Tests
```java
@Test
void testPerformance() {
    AsyncProcessor processor = AsyncProcessor.create();
    
    CompletableFuture<Long> future = processor.supplyAsync(() -> {
        long start = System.nanoTime();
        ProtocolResult<Void> result = protocolProcessor.processWithFallback(
            entities,
            "performanceTest",
            this::serializeTestData,
            this::callNativeTest,
            bytes -> null,
            this::prepareTestJson,
            this::callNativeTestJson,
            json -> null,
            null
        );
        result.getDataOrThrow(); // Ensure success
        return System.nanoTime() - start;
    }, 5000, "performance-test");
    
    long durationNanos = future.get(5, TimeUnit.SECONDS);
    long durationMillis = durationNanos / 1_000_000;
    
    assertTrue(durationMillis < 1000, "Processing should complete within 1 second");
    
    // Verify metrics
    AsyncProcessor.AsyncMetrics metrics = processor.getMetrics("performance-test");
    assertTrue(metrics.getOperations() > 0);
    assertEquals(0.0, metrics.getFailureRate(), 0.001);
}
```

## Performance Considerations

### Memory Usage

#### Old Memory Management
- Manual memory allocation and cleanup
- Potential memory leaks in error scenarios
- No pooling for frequently allocated objects

#### New Memory Management
- Automatic resource management with ResourceManager
- Memory pooling for native operations
- Proper cleanup in all error scenarios
- Configurable pool sizes based on usage patterns

### CPU Utilization

#### Old CPU Usage
- Single-threaded processing for most operations
- Limited SIMD utilization
- Inefficient data structures

#### New CPU Usage
- Parallel processing with Rayon
- SIMD acceleration for vector operations
- Optimized data structures with cache locality
- Configurable thread pool sizes

### I/O Performance

#### Old I/O Patterns
- Synchronous I/O operations
- No batching for multiple operations
- Inefficient protocol handling

#### New I/O Patterns
- Asynchronous operations with AsyncProcessor
- Batch processing for better throughput
- Unified protocol processing with automatic fallback
- Configurable timeouts and retry mechanisms

## Troubleshooting

### Common Migration Issues

#### 1. Native Library Loading Issues

**Problem**: `UnsatisfiedLinkError` when calling native methods

**Solution**:
```java
// Check native library availability
public static boolean isNativeLibraryAvailable() {
    try {
        System.loadLibrary("rustperf");
        return true;
    } catch (UnsatisfiedLinkError e) {
        return false;
    }
}

// Use assumptions in tests
@Test
void testNativeOperations() {
    Assumptions.assumeTrue(isNativeLibraryAvailable(), 
                          "Native library not available");
    // Test code here
}
```

#### 2. Configuration Validation Issues

**Problem**: Invalid configuration causes startup failures

**Solution**:
```java
// Validate configuration on startup
public void validateConfiguration() {
    try {
        ConfigurationManager.validate(config);
    } catch (KneafCoreException e) {
        if (e.getCategory() == KneafCoreException.ErrorCategory.CONFIGURATION) {
            logger.error("Invalid configuration: {}", e.getMessage());
            // Use default configuration or fail gracefully
        }
    }
}
```

#### 3. Async Processing Timeouts

**Problem**: Operations timing out in async processing

**Solution**:
```java
// Configure appropriate timeouts
AsyncProcessor processor = AsyncProcessor.create(
    new AsyncProcessor.AsyncConfig()
        .defaultTimeoutMs(60000) // 60 seconds
        .maxRetries(3)
        .retryDelayMs(2000)
);

// Handle timeout exceptions
processor.supplyAsync(operation, timeout, operationName)
    .exceptionally(throwable -> {
        if (throwable instanceof AsyncProcessingException) {
            AsyncProcessingException ape = (AsyncProcessingException) throwable;
            if (ape.getErrorType() == AsyncProcessingException.AsyncErrorType.TIMEOUT_EXCEEDED) {
                logger.error("Operation timed out after {}ms", ape.getTimeoutMs());
                // Handle timeout appropriately
            }
        }
        return null;
    });
```

#### 4. Protocol Processing Failures

**Problem**: Both binary and JSON protocols fail

**Solution**:
```java
// Always provide fallback results
ProtocolResult<Result> result = processor.processWithFallback(
    input,
    "operation",
    binarySerializer, binaryCaller, binaryDeserializer,
    jsonPreparer, jsonCaller, jsonParser,
    defaultResult // Always provide this
);

// Handle failure gracefully
if (!result.isSuccess()) {
    logger.warn("Protocol processing failed: {}", result.getErrorMessage());
    // Use fallback or default behavior
    return defaultResult;
}
```

### Migration Checklist

- [ ] Update configuration files to new format
- [ ] Replace deprecated exception classes
- [ ] Migrate to new AsyncProcessor API
- [ ] Update protocol processing to use ProtocolProcessor
- [ ] Implement ResourceManager for resource lifecycle
- [ ] Update logging to structured format
- [ ] Add proper error handling for new exception hierarchy
- [ ] Update test cases with new assumptions
- [ ] Validate native library loading
- [ ] Configure appropriate timeouts for async operations
- [ ] Test fallback mechanisms
- [ ] Monitor performance metrics after migration
- [ ] Update documentation and comments

### Performance Monitoring After Migration

```java
// Monitor async processor performance
AsyncProcessor processor = AsyncProcessor.create();
// ... use processor ...

// Get performance metrics
AsyncProcessor.ProcessorStats stats = processor.getProcessorStats();
logger.info("Processor stats: {}", stats);

// Monitor specific operations
AsyncProcessor.AsyncMetrics metrics = processor.getMetrics("entityProcessing");
if (metrics.getFailureRate() > 0.05) { // 5% failure rate
    logger.warn("High failure rate detected: {}%", metrics.getFailureRate() * 100);
}
```

### Rollback Strategy

If migration issues occur:

1. **Keep old code commented** during migration for reference
2. **Implement feature flags** to toggle between old and new implementations
3. **Gradual migration** - migrate one component at a time
4. **Comprehensive testing** before full deployment
5. **Backup configurations** and code before migration
6. **Monitor performance** and error rates after migration

## Support and Resources

### Documentation
- [API Documentation](API_DOCUMENTATION.md)
- [Performance Benchmarks](PERFORMANCE_BENCHMARKS.md)
- [Changelog](CHANGELOG.md)

### Migration Support
- Create issues in the project repository for migration questions
- Provide detailed error logs and configuration when reporting issues
- Include system information and environment details

### Best Practices
1. **Test thoroughly** in development environment before production
2. **Monitor performance** after migration to ensure improvements
3. **Keep backups** of working configurations
4. **Gradual rollout** for large-scale deployments
5. **Document customizations** made during migration