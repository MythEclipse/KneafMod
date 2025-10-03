# KneafMod API Documentation

## Table of Contents

1. [Exception Hierarchy](#exception-hierarchy)
2. [Async Processing Framework](#async-processing-framework)
3. [Protocol Processing](#protocol-processing)
4. [Resource Management](#resource-management)
5. [Performance Components](#performance-components)

## Exception Hierarchy

### KneafCoreException

The base exception class for all KneafCore operations, providing a unified exception hierarchy for better error handling and logging.

```java
public class KneafCoreException extends RuntimeException
```

**Key Features:**
- **Error Categories**: Categorizes errors into DATABASE_OPERATION, NATIVE_LIBRARY, ASYNC_PROCESSING, CONFIGURATION, RESOURCE_MANAGEMENT, PROTOCOL_ERROR, VALIDATION_ERROR, and SYSTEM_ERROR
- **Context Information**: Includes operation name and context data for better debugging
- **Structured Messages**: Formatted error messages with category and operation information

**Error Categories:**
```java
public enum ErrorCategory {
    DATABASE_OPERATION("Database operation failed"),
    NATIVE_LIBRARY("Native library error"),
    ASYNC_PROCESSING("Async processing error"),
    CONFIGURATION("Configuration error"),
    RESOURCE_MANAGEMENT("Resource management error"),
    PROTOCOL_ERROR("Protocol error"),
    VALIDATION_ERROR("Validation error"),
    SYSTEM_ERROR("System error");
}
```

**Usage Examples:**
```java
// Basic exception
throw new KneafCoreException(ErrorCategory.DATABASE_OPERATION, "Connection failed");

// Exception with operation context
throw new KneafCoreException(ErrorCategory.NATIVE_LIBRARY, "processEntities", 
                           "Native library call failed", cause);

// Exception with detailed context
throw new KneafCoreException(ErrorCategory.ASYNC_PROCESSING, "batchOperation", 
                           "Batch processing failed", contextData, cause);
```

### DatabaseException

Specialized exception for database operation failures, replacing duplicate DatabaseOperationException and RustDatabaseException classes.

```java
public class DatabaseException extends KneafCoreException
```

**Database Error Types:**
```java
public enum DatabaseErrorType {
    CONNECTION_FAILED("Database connection failed"),
    OPERATION_FAILED("Database operation failed"),
    TRANSACTION_FAILED("Database transaction failed"),
    VALIDATION_FAILED("Database validation failed"),
    BACKUP_FAILED("Database backup failed"),
    MAINTENANCE_FAILED("Database maintenance failed"),
    NATIVE_LIBRARY_ERROR("Native database library error"),
    ASYNC_OPERATION_FAILED("Async database operation failed");
}
```

**Factory Methods:**
```java
// Async operation failure
DatabaseException.asyncOperationFailed("backup", "rustdb", "chunk_123", timeoutException);

// Native library error
DatabaseException.nativeLibraryError("rustdb", "Memory allocation failed", nativeException);
```

### NativeLibraryException

Exception for native library operation failures, replacing RustPerformanceException and handling native library specific errors.

```java
public class NativeLibraryException extends KneafCoreException
```

**Native Error Types:**
```java
public enum NativeErrorType {
    LIBRARY_NOT_AVAILABLE("Native library is not available"),
    LIBRARY_LOAD_FAILED("Failed to load native library"),
    NATIVE_CALL_FAILED("Native method call failed"),
    BINARY_PROTOCOL_ERROR("Binary protocol error"),
    JNI_ERROR("JNI error"),
    MEMORY_ALLOCATION_FAILED("Native memory allocation failed");
}
```

**Factory Methods:**
```java
// Library not available
NativeLibraryException.libraryNotAvailable("rustperf");

// Binary protocol error
NativeLibraryException.binaryProtocolError("deserialize", "Invalid data format", protocolException);
```

### AsyncProcessingException

Exception for async processing operation failures, replacing BatchRequestInterruptedException and handling async processing specific errors.

```java
public class AsyncProcessingException extends KneafCoreException
```

**Async Error Types:**
```java
public enum AsyncErrorType {
    BATCH_REQUEST_INTERRUPTED("Batch request interrupted"),
    TIMEOUT_EXCEEDED("Async operation timeout exceeded"),
    EXECUTOR_SHUTDOWN("Async executor shutdown"),
    TASK_REJECTED("Async task rejected"),
    COMPLETION_EXCEPTION("Async completion exception"),
    SUPPLY_ASYNC_FAILED("Supply async operation failed");
}
```

**Factory Methods:**
```java
// Batch request interrupted
AsyncProcessingException.batchRequestInterrupted("entityProcessing", interruptedException);

// Timeout exceeded
AsyncProcessingException.timeoutExceeded("databaseQuery", 30000, timeoutException);

// Supply async failed
AsyncProcessingException.supplyAsyncFailed("chunkLoading", "chunk_123", executionException);
```

## Async Processing Framework

### AsyncProcessor

Generic async processor that consolidates CompletableFuture.supplyAsync patterns with consistent error handling, logging, and timeout management.

```java
public class AsyncProcessor
```

**Key Features:**
- **Unified Async Operations**: Single entry point for all async operations
- **Timeout Management**: Configurable timeouts with automatic cancellation
- **Error Handling**: Consistent error handling with AsyncProcessingException
- **Metrics Collection**: Performance metrics for monitoring
- **Retry Logic**: Built-in retry mechanisms for transient failures
- **Resource Management**: Proper resource cleanup and shutdown

**Configuration:**
```java
AsyncProcessor.AsyncConfig config = new AsyncProcessor.AsyncConfig()
    .executor(customExecutor)
    .defaultTimeoutMs(30000)
    .enableLogging(true)
    .enableMetrics(true)
    .processorName("EntityProcessor");
```

**Core Methods:**

```java
// Basic async execution
CompletableFuture<Result> future = processor.supplyAsync(() -> {
    return performExpensiveOperation();
});

// Async execution with timeout
CompletableFuture<Result> future = processor.supplyAsync(() -> {
    return performExpensiveOperation();
}, 5000); // 5 second timeout

// Async execution with retry
CompletableFuture<Result> future = processor.supplyWithRetry(() -> {
    return performUnreliableOperation();
}, 3, 1000); // 3 retries with 1 second delay

// Batch async execution
List<CompletableFuture<Result>> futures = processor.supplyAllAsync(Arrays.asList(
    () -> operation1(),
    () -> operation2(),
    () -> operation3()
));
```

**Metrics and Monitoring:**
```java
// Get operation-specific metrics
AsyncProcessor.AsyncMetrics metrics = processor.getMetrics("entityProcessing");

// Get processor statistics
AsyncProcessor.ProcessorStats stats = processor.getProcessorStats();

// Metrics include:
// - Operation count and failure rate
// - Duration statistics (min, max, average)
// - Thread pool statistics
```

## Protocol Processing

### ProtocolProcessor

Protocol abstraction layer that provides unified binary/JSON dual protocol processing, eliminating duplicate "try binary, fallback to JSON" patterns.

```java
public class ProtocolProcessor
```

**Protocol Types:**
```java
public enum ProtocolType {
    BINARY("Binary"),
    JSON("JSON"),
    AUTO("Auto"); // Try binary first, fallback to JSON
}
```

**Key Features:**
- **Unified Protocol Handling**: Single interface for both binary and JSON protocols
- **Automatic Fallback**: AUTO mode tries binary first, falls back to JSON on failure
- **Type Safety**: Generic types ensure compile-time safety
- **Error Handling**: Comprehensive error handling with detailed failure information
- **Performance Monitoring**: Built-in logging and debugging capabilities

**Usage Example:**
```java
ProtocolProcessor processor = ProtocolProcessor.createAuto(nativeLibraryAvailable);

ProtocolProcessor.ProtocolResult<Result> result = processor.processWithFallback(
    inputData,
    "entityProcessing",
    
    // Binary protocol handlers
    binarySerializer,
    binaryNativeCaller,
    binaryDeserializer,
    
    // JSON protocol handlers
    jsonInputPreparer,
    jsonNativeCaller,
    jsonResultParser,
    
    // Fallback result
    defaultResult
);

if (result.isSuccess()) {
    Result processedData = result.getData();
    ProtocolProcessor.ProtocolType protocolUsed = result.getProtocolUsed();
    logger.info("Processing successful using {} protocol", protocolUsed);
} else {
    logger.error("Processing failed: {}", result.getErrorMessage());
}
```

**Protocol Result:**
```java
public static class ProtocolResult<T> {
    public T getData();                    // Processed data
    public ProtocolType getProtocolUsed(); // Protocol used for processing
    public boolean isSuccess();            // Success status
    public String getErrorMessage();       // Error message if failed
    public T getDataOrThrow();             // Get data or throw exception
}
```

## Resource Management

### ResourceManager

Unified resource management abstraction that provides consistent lifecycle management for all resources throughout the codebase.

```java
public class ResourceManager implements Closeable
```

**Key Features:**
- **Centralized Resource Management**: Single point for all resource lifecycle operations
- **Lifecycle Management**: Initialize, start, stop, and health check capabilities
- **Event Notifications**: Lifecycle event listeners for resource state changes
- **Health Monitoring**: Comprehensive health checking and reporting
- **Graceful Shutdown**: Proper resource cleanup with timeout handling
- **Statistics Collection**: Resource usage and performance statistics

**Managed Resource Interface:**
```java
public interface ManagedResource {
    void initialize() throws Exception;
    default void start() throws Exception;
    void stop() throws Exception;
    default boolean isHealthy();
    String getResourceName();
    default String getResourceType();
    default Object getMetadata();
}
```

**Usage Example:**
```java
ResourceManager manager = ResourceManager.create("EntityManager");

// Register resources
manager.registerResource(new DatabaseConnection("main-db"));
manager.registerResource(new NativeLibraryHandle("rustperf"));
manager.registerResource(new CacheManager("entity-cache"));

// Start all resources
manager.startAll();

// Monitor health
ResourceManager.ResourceHealthReport health = manager.checkHealth();
if (health.isHealthy()) {
    logger.info("All resources are healthy");
}

// Get statistics
ResourceManager.ResourceStatistics stats = manager.getStatistics();
logger.info("Resource health: {}%", stats.getHealthPercentage());

// Graceful shutdown
manager.close();
```

**Lifecycle Events:**
```java
// Add lifecycle listener
manager.addLifecycleListener(event -> {
    switch (event.getType()) {
        case REGISTERED:
            logger.info("Resource registered: {}", event.getResource().getResourceName());
            break;
        case STARTED:
            logger.info("Resource started: {}", event.getResource().getResourceName());
            break;
        case STOPPED:
            logger.info("Resource stopped: {}", event.getResource().getResourceName());
            break;
    }
});
```

## Performance Components

### Enhanced Native Bridge

Enhanced batch operations with result callbacks and improved error handling.

```java
public class EnhancedNativeBridge
```

**Key Features:**
- **Batch Processing**: Efficient batch operations for bulk data processing
- **Result Callbacks**: Asynchronous result handling with callbacks
- **Error Recovery**: Comprehensive error handling and recovery mechanisms
- **Performance Monitoring**: Built-in performance metrics and logging

**Usage Example:**
```java
EnhancedNativeBridge bridge = new EnhancedNativeBridge();

// Process batch with callback
bridge.processBatchAsync(entityData, result -> {
    if (result.isSuccess()) {
        logger.info("Processed {} entities successfully", result.getProcessedCount());
        processResults(result.getData());
    } else {
        logger.error("Batch processing failed: {}", result.getErrorMessage());
        handleError(result.getError());
    }
});

// Process with timeout
bridge.processBatchAsync(entityData, 30000, result -> {
    // Handle results with 30-second timeout
});
```

### Native Float Buffer Pool

Memory pooling system for efficient native operations with reduced garbage collection pressure.

```java
public class NativeFloatBufferPool
```

**Key Features:**
- **Memory Pooling**: Reusable float buffers for native operations
- **Automatic Sizing**: Dynamic buffer sizing based on usage patterns
- **Thread Safety**: Concurrent access with minimal locking
- **Resource Management**: Proper cleanup and lifecycle management

**Usage Example:**
```java
NativeFloatBufferPool pool = NativeFloatBufferPool.create();

// Acquire buffer
try (NativeFloatBuffer buffer = pool.acquire(size)) {
    // Use buffer for native operations
    buffer.put(data);
    processNative(buffer);
} // Automatically released back to pool

// Get pool statistics
NativeFloatBufferPool.PoolStats stats = pool.getStatistics();
logger.info("Pool utilization: {}%", stats.getUtilizationPercentage());
```

### Performance Manager

Centralized performance management with multithreaded optimization processing.

```java
public class PerformanceManager
```

**Key Features:**
- **Multithreaded Processing**: Asynchronous optimization processing
- **Configurable Intervals**: Adjustable processing frequency (default: every 3 ticks)
- **Spatial Optimization**: Advanced spatial partitioning and entity grouping
- **Memory Management**: Intelligent memory pooling and garbage collection optimization
- **Performance Monitoring**: Comprehensive metrics and logging

**Configuration:**
```java
// Configure performance manager
PerformanceConfig config = new PerformanceConfig()
    .enableRustOptimizations(true)
    .spatialMaxDepth(8)
    .entityCloseRadius(16.0)
    .entityMediumRadius(32.0)
    .processingInterval(3) // Process every 3 ticks
    .enableMetrics(true)
    .enableLogging(true);
```

**Usage Example:**
```java
PerformanceManager manager = new PerformanceManager(config);

// Process optimizations asynchronously
manager.processOptimizationsAsync(server, entityData);

// Monitor performance
PerformanceMetrics metrics = manager.getMetrics();
if (metrics.getTps() < config.getTpsAlertThreshold()) {
    logger.warn("TPS below threshold: {}", metrics.getTps());
}

// Get optimization results
OptimizationResults results = manager.getLastOptimizationResults();
logger.info("Optimized {} entities, {} mobs, {} blocks",
           results.getOptimizedEntities(),
           results.getOptimizedMobs(),
           results.getOptimizedBlocks());
```

## Integration Examples

### Complete Integration Example

```java
public class EntityOptimizationService {
    private final AsyncProcessor asyncProcessor;
    private final ProtocolProcessor protocolProcessor;
    private final ResourceManager resourceManager;
    private final PerformanceManager performanceManager;
    
    public EntityOptimizationService() {
        // Initialize components
        this.asyncProcessor = AsyncProcessor.create();
        this.protocolProcessor = ProtocolProcessor.createAuto(true);
        this.resourceManager = ResourceManager.create("EntityService");
        this.performanceManager = new PerformanceManager(new PerformanceConfig());
        
        // Register resources
        initializeResources();
    }
    
    private void initializeResources() {
        try {
            resourceManager.registerResource(new NativeLibraryHandle("rustperf"));
            resourceManager.registerResource(new DatabaseConnection("entity-db"));
            resourceManager.startAll();
        } catch (Exception e) {
            throw new KneafCoreException(KneafCoreException.ErrorCategory.RESOURCE_MANAGEMENT,
                                       "initializeResources", "Failed to initialize resources", e);
        }
    }
    
    public CompletableFuture<OptimizationResult> optimizeEntitiesAsync(List<Entity> entities) {
        return asyncProcessor.supplyAsync(() -> {
            try {
                // Use protocol processor for flexible data processing
                ProtocolProcessor.ProtocolResult<ProcessedEntities> result = 
                    protocolProcessor.processWithFallback(
                        entities,
                        "entityOptimization",
                        
                        // Binary processing
                        this::serializeEntitiesBinary,
                        this::callNativeEntityProcessing,
                        this::deserializeEntitiesBinary,
                        
                        // JSON processing
                        this::prepareEntitiesJson,
                        this::callNativeEntityProcessingJson,
                        this::parseEntitiesJson,
                        
                        // Fallback
                        new ProcessedEntities(entities)
                    );
                
                return new OptimizationResult(result.getDataOrThrow(), result.getProtocolUsed());
                
            } catch (Exception e) {
                throw new KneafCoreException(KneafCoreException.ErrorCategory.ASYNC_PROCESSING,
                                           "optimizeEntities", "Entity optimization failed", e);
            }
        }, 30000, "entity-optimization");
    }
    
    public void shutdown() {
        try {
            asyncProcessor.shutdown();
            resourceManager.close();
            performanceManager.shutdown();
        } catch (Exception e) {
            logger.error("Error during shutdown", e);
        }
    }
}
```

## Best Practices

### Error Handling
- Always use the appropriate exception type from the hierarchy
- Include meaningful context information in exceptions
- Use factory methods for common exception scenarios
- Chain exceptions properly to preserve stack traces

### Async Processing
- Always specify timeouts for async operations
- Use retry mechanisms for transient failures
- Monitor async operation metrics for performance tuning
- Implement proper error handling with AsyncProcessingException

### Protocol Processing
- Use AUTO protocol type for maximum compatibility
- Always provide fallback results for critical operations
- Monitor protocol usage statistics for optimization
- Handle both binary and JSON protocol failures gracefully

### Resource Management
- Register all resources with proper lifecycle management
- Implement health checks for critical resources
- Use lifecycle listeners for dependent resource coordination
- Always close ResourceManager to ensure proper cleanup

### Performance Optimization
- Configure processing intervals based on server load
- Monitor TPS and adjust optimization thresholds accordingly
- Use spatial partitioning for entity-heavy operations
- Leverage memory pooling to reduce garbage collection pressure

## Thread Safety

All components are designed with thread safety in mind:
- **Immutable Data Structures**: Where possible, use immutable objects
- **Concurrent Collections**: Use ConcurrentHashMap and CopyOnWriteArrayList
- **Atomic Operations**: Use AtomicLong and AtomicReference for counters
- **Proper Synchronization**: Use synchronized blocks only when necessary
- **Thread-Local Storage**: Use ThreadLocal for per-thread state

## Performance Considerations

### Memory Management
- Use object pooling for frequently created/destroyed objects
- Implement proper cleanup in finally blocks or try-with-resources
- Monitor memory usage and adjust pool sizes accordingly
- Use weak references for cache implementations where appropriate

### CPU Optimization
- Use parallel processing for CPU-intensive operations
- Implement proper thread pool sizing based on available processors
- Use non-blocking algorithms where possible
- Minimize lock contention with fine-grained locking

### I/O Optimization
- Use asynchronous I/O for network and file operations
- Implement proper buffering for stream operations
- Use memory-mapped files for large data processing
- Batch I/O operations to reduce system calls

## Monitoring and Debugging

### Logging
- Use structured logging with JSON format for machine parsing
- Include trace IDs for request correlation
- Log performance metrics at appropriate intervals
- Use appropriate log levels (DEBUG, INFO, WARN, ERROR)

### Metrics
- Monitor operation counts and failure rates
- Track duration statistics for performance analysis
- Collect resource usage statistics
- Implement health check endpoints for monitoring systems

### Debugging
- Include detailed error messages with context
- Provide stack traces for exceptions
- Log protocol-specific debugging information
- Use debug logging for development and troubleshooting