package com.kneaf.core.unifiedbridge;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.WARNING;

/**
 * Centralized native resource lifecycle management with leak detection.
 * Manages native resources efficiently with proper lifecycle tracking and leak detection.
 */
public final class ResourceManager {
    private static final Logger LOGGER = Logger.getLogger(ResourceManager.class.getName());
    private static final ResourceManager INSTANCE = new ResourceManager();
    
    private volatile BridgeConfiguration config;
    private final ConcurrentMap<Long, Resource> activeResources = new ConcurrentHashMap<>();
    private final ConcurrentMap<ResourceType, Set<Long>> resourceTypeRegistry = new ConcurrentHashMap<>();
    private final AtomicLong nextResourceHandle = new AtomicLong(1);
    private final AtomicLong totalResourcesAllocated = new AtomicLong(0);
    private final AtomicLong totalResourcesReleased = new AtomicLong(0);
    private final AtomicLong detectedLeaks = new AtomicLong(0);
    
    private volatile boolean leakDetectionEnabled = true;
    private volatile long lastLeakCheckTime = System.currentTimeMillis();

    private ResourceManager() {
        this.config = BridgeConfiguration.getDefault();
        LOGGER.info("ResourceManager initialized with default configuration");
        startLeakDetectionTimer();
    }

    /**
     * Get the singleton instance of ResourceManager.
     * @return ResourceManager instance
     */
    public static ResourceManager getInstance() {
        return INSTANCE;
    }

    /**
     * Get the singleton instance with custom configuration.
     * @param config Custom configuration
     * @return ResourceManager instance
     */
    public static ResourceManager getInstance(BridgeConfiguration config) {
        INSTANCE.config = Objects.requireNonNull(config);
        LOGGER.info("ResourceManager reconfigured with custom settings");
        return INSTANCE;
    }

    /**
     * Allocate a native resource.
     * @param resourceType Type of resource to allocate
     * @param resourceData Resource-specific data
     * @return Unique resource handle (non-zero if successful)
     * @throws BridgeException If resource allocation fails
     */
    public long allocateResource(ResourceType resourceType, Object resourceData) throws BridgeException {
        Objects.requireNonNull(resourceType, "Resource type cannot be null");
        
        long handle = nextResourceHandle.getAndIncrement();
        Resource resource = new Resource(handle, resourceType, resourceData);
        
        activeResources.put(handle, resource);
        registerResourceType(resourceType, handle);
        
        totalResourcesAllocated.incrementAndGet();
        
        LOGGER.log(FINE, "Allocated resource {0} of type {1}", 
                new Object[]{handle, resourceType.name()});
        
        return handle;
    }

    /**
     * Release a native resource.
     * @param resourceHandle Handle of resource to release
     * @return true if resource was found and released, false otherwise
     */
    public boolean releaseResource(long resourceHandle) {
        Resource resource = activeResources.remove(resourceHandle);
        if (resource != null) {
            try {
                resource.release();
                
                unregisterResourceType(resource.getResourceType(), resourceHandle);
                totalResourcesReleased.incrementAndGet();
                
                LOGGER.log(FINE, "Released resource {0} of type {1}", 
                        new Object[]{resourceHandle, resource.getResourceType().name()});
                
                return true;
            } catch (Exception e) {
                LOGGER.log(WARNING, "Failed to release resource " + resourceHandle, e);
                // Don't rethrow - we want to fail softly for cleanup operations
                activeResources.put(resourceHandle, resource); // Put back to avoid leaks
                return false;
            }
        } else {
            LOGGER.warning(() -> "Attempted to release non-existent resource " + resourceHandle);
            return false;
        }
    }

    /**
     * Get resource information by handle.
     * @param resourceHandle Handle of resource to query
     * @return Resource information or null if not found
     */
    public ResourceInfo getResourceInfo(long resourceHandle) {
        Resource resource = activeResources.get(resourceHandle);
        return resource != null ? new ResourceInfo(resource) : null;
    }

    /**
     * Get statistics about resource management.
     * @return Map containing resource statistics
     */
    public Map<String, Object> getResourceStats() {
        return Map.of(
                "activeResources", activeResources.size(),
                "totalResourcesAllocated", totalResourcesAllocated.get(),
                "totalResourcesReleased", totalResourcesReleased.get(),
                "detectedLeaks", detectedLeaks.get(),
                "leakDetectionEnabled", leakDetectionEnabled,
                "registeredResourceTypes", resourceTypeRegistry.size()
        );
    }

    /**
     * Get all active resources of a specific type.
     * @param resourceType Type of resources to retrieve
     * @return Set of resource handles
     */
    public Set<Long> getResourcesByType(ResourceType resourceType) {
        Objects.requireNonNull(resourceType, "Resource type cannot be null");
        
        Set<Long> handles = resourceTypeRegistry.get(resourceType);
        return handles != null ? Set.copyOf(handles) : Set.of();
    }

    /**
     * Enable or disable leak detection.
     * @param enabled true to enable, false to disable
     */
    public void setLeakDetectionEnabled(boolean enabled) {
        this.leakDetectionEnabled = enabled;
        if (enabled) {
            LOGGER.info("Resource leak detection enabled");
        } else {
            LOGGER.info("Resource leak detection disabled");
        }
    }

    /**
     * Perform an immediate leak check.
     * @return Set of resource handles that are potentially leaked
     */
    public Set<Long> checkForLeaks() {
        if (!leakDetectionEnabled) {
            return Set.of();
        }

        Set<Long> leakedResources = activeResources.keySet().stream()
                .filter(handle -> isPotentiallyLeaked(handle))
                .collect(Collectors.toSet());

        if (!leakedResources.isEmpty()) {
            detectedLeaks.addAndGet(leakedResources.size());
            LOGGER.warning(() -> "Detected " + leakedResources.size() + " potentially leaked resources");
            
            for (long handle : leakedResources) {
                LOGGER.log(WARNING, "Potentially leaked resource: {0}", handle);
            }
        }

        lastLeakCheckTime = System.currentTimeMillis();
        return leakedResources;
    }

    /**
     * Shutdown the resource manager and release all resources.
     */
    public void shutdown() {
        LOGGER.info("ResourceManager shutting down - releasing all resources");
        
        checkForLeaks(); // Final leak check before shutdown
        
        for (long resourceHandle : new java.util.ArrayList<>(activeResources.keySet())) {
            releaseResource(resourceHandle);
        }
        
        activeResources.clear();
        resourceTypeRegistry.clear();
        
        LOGGER.info("ResourceManager shutdown complete");
    }

    /**
     * Register a resource type for tracking.
     * @param resourceType Type of resource
     * @param resourceHandle Resource handle
     */
    private void registerResourceType(ResourceType resourceType, long resourceHandle) {
        resourceTypeRegistry.computeIfAbsent(resourceType, k -> ConcurrentHashMap.newKeySet())
                .add(resourceHandle);
    }

    /**
     * Unregister a resource type from tracking.
     * @param resourceType Type of resource
     * @param resourceHandle Resource handle
     */
    private void unregisterResourceType(ResourceType resourceType, long resourceHandle) {
        Set<Long> handles = resourceTypeRegistry.get(resourceType);
        if (handles != null) {
            handles.remove(resourceHandle);
            if (handles.isEmpty()) {
                resourceTypeRegistry.remove(resourceType);
            }
        }
    }

    /**
     * Check if a resource is potentially leaked.
     * @param resourceHandle Resource handle to check
     * @return true if potentially leaked, false otherwise
     */
    private boolean isPotentiallyLeaked(long resourceHandle) {
        Resource resource = activeResources.get(resourceHandle);
        if (resource == null) {
            return false;
        }

        // In real implementation, this would check for native memory leaks
        // For simulation, we'll use a simple heuristic based on resource age
        long resourceAgeMs = System.currentTimeMillis() - resource.getAllocationTime();
        return resourceAgeMs > config.getResourceLeakDetectionThresholdMs();
    }

    /**
     * Start background leak detection timer.
     */
    private void startLeakDetectionTimer() {
        if (config.isEnableResourceLeakDetection()) {
            new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(config.getResourceLeakDetectionIntervalMs());
                        checkForLeaks();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOGGER.info("Resource leak detection timer interrupted");
                }
            }, "ResourceLeakDetection").start();
            
            LOGGER.info("Resource leak detection timer started");
        }
    }

    /**
     * Enum representing different types of native resources.
     */
    public enum ResourceType {
        /** Native memory buffer */
        MEMORY_BUFFER,
        
        /** Native thread */
        NATIVE_THREAD,
        
        /** Native file handle */
        FILE_HANDLE,
        
        /** Native socket */
        SOCKET_HANDLE,
        
        /** Native database connection */
        DATABASE_CONNECTION,
        
        /** Native graphics resource */
        GRAPHICS_RESOURCE,
        
        /** Other native resource */
        OTHER
    }

    /**
     * Internal representation of a managed resource.
     */
    static final class Resource {
        private final long handle;
        private final ResourceType resourceType;
        private final Object resourceData;
        private final long allocationTime;
        private final WeakReference<Object> ownerReference;
        private volatile boolean isReleased = false;

        Resource(long handle, ResourceType resourceType, Object resourceData) {
            this.handle = handle;
            this.resourceType = resourceType;
            this.resourceData = resourceData;
            this.allocationTime = System.currentTimeMillis();
            
            // For leak detection, track potential owners if available
            this.ownerReference = resourceData instanceof ResourceOwner ?
                    new WeakReference<>(((ResourceOwner) resourceData).getOwner()) : null;
        }

        void release() {
            if (isReleased) {
                return; // Already released, avoid double-release
            }
            
            isReleased = true;
            
            // In real implementation, this would call native free() or similar
            LOGGER.log(FINE, "Released native resource {0}", handle);
        }

        // Getters
        public long getHandle() { return handle; }
        public ResourceType getResourceType() { return resourceType; }
        public Object getResourceData() { return resourceData; }
        public long getAllocationTime() { return allocationTime; }
        public WeakReference<Object> getOwnerReference() { return ownerReference; }
        public boolean isReleased() { return isReleased; }
    }

    /**
     * Interface for objects that own resources (for leak detection).
     */
    public interface ResourceOwner {
        /**
         * Get the owner object of this resource.
         * @return Owner object
         */
        Object getOwner();
    }

    /**
     * Public resource information class (immutable).
     */
    public static final class ResourceInfo {
        private final long handle;
        private final ResourceType resourceType;
        private final long allocationTime;
        private final long ageMs;

        ResourceInfo(Resource resource) {
            this.handle = resource.getHandle();
            this.resourceType = resource.getResourceType();
            this.allocationTime = resource.getAllocationTime();
            this.ageMs = System.currentTimeMillis() - resource.getAllocationTime();
        }

        // Getters
        public long getHandle() { return handle; }
        public ResourceType getResourceType() { return resourceType; }
        public long getAllocationTime() { return allocationTime; }
        public long getAgeMs() { return ageMs; }
    }
}