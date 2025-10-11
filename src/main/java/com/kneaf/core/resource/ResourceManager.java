package com.kneaf.core.resource;

import com.kneaf.core.exceptions.core.KneafCoreException;
import com.kneaf.core.utils.ValidationUtils;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unified resource management abstraction that provides consistent lifecycle management for all
 * resources throughout the codebase.
 *
 * <p>This class eliminates duplicate resource management patterns by providing a centralized
 * mechanism for resource registration, lifecycle management, and cleanup.
 */
public class ResourceManager implements Closeable {

  private static final Logger LOGGER = LoggerFactory.getLogger(ResourceManager.class);

  /** Resource lifecycle states. */
  public enum ResourceState {
    INITIALIZED("Initialized"),
    STARTED("Started"),
    STOPPING("Stopping"),
    STOPPED("Stopped"),
    FAILED("Failed");

    private final String description;

    ResourceState(String description) {
      this.description = description;
    }

    public String getDescription() {
      return description;
    }
  }

  /** Resource interface for managed resources. */
  public interface ManagedResource {
    /** Initialize the resource. */
    void initialize() throws Exception;

    /** Start the resource. */
    default void start() throws Exception {
      // Default implementation does nothing
    }

    /** Stop the resource. */
    void stop() throws Exception;

    /** Check if the resource is healthy. */
    default boolean isHealthy() {
      return true;
    }

    /** Get the resource name. */
    String getResourceName();

    /** Get the resource type. */
    default String getResourceType() {
      return getClass().getSimpleName();
    }

    /** Get resource-specific metadata. */
    default Object getMetadata() {
      return null;
    }
  }

  /** Resource wrapper that tracks state and provides additional management capabilities. */
  private static class ResourceWrapper {
    private final ManagedResource resource;
    private final AtomicReference<ResourceState> state =
        new AtomicReference<>(ResourceState.INITIALIZED);
    private final AtomicBoolean healthCheckEnabled = new AtomicBoolean(true);
    private final long initializationTime = System.currentTimeMillis();
    private volatile long lastHealthCheckTime = initializationTime;

    ResourceWrapper(ManagedResource resource) {
      this.resource = resource;
    }

    void initialize() throws Exception {
      if (!state.compareAndSet(ResourceState.INITIALIZED, ResourceState.STARTED)) {
        throw new IllegalStateException(
            "Resource " + resource.getResourceName() + " is already initialized");
      }
      resource.initialize();
    }

    void start() throws Exception {
      if (state.get() != ResourceState.STARTED) {
        throw new IllegalStateException(
            "Resource " + resource.getResourceName() + " is not in STARTED state");
      }
      resource.start();
    }

    void stop() throws Exception {
      if (!state.compareAndSet(ResourceState.STARTED, ResourceState.STOPPING)) {
        return; // Already stopping or stopped
      }
      try {
        resource.stop();
        state.set(ResourceState.STOPPED);
      } catch (Exception e) {
        state.set(ResourceState.FAILED);
        throw e;
      }
    }

    boolean isHealthy() {
      if (!healthCheckEnabled.get()) {
        return true;
      }
      try {
        boolean healthy = resource.isHealthy();
        lastHealthCheckTime = System.currentTimeMillis();
        return healthy;
      } catch (Exception e) {
        LOGGER.warn(
            "Health check failed for resource { }: { }",
            resource.getResourceName(),
            e.getMessage());
        return false;
      }
    }

    ResourceState getState() {
      return state.get();
    }

    ManagedResource getResource() {
      return resource;
    }

    long getInitializationTime() {
      return initializationTime;
    }

    long getLastHealthCheckTime() {
      return lastHealthCheckTime;
    }
  }

  private final ConcurrentMap<String, ResourceWrapper> resources = new ConcurrentHashMap<>();
  private final AtomicBoolean shutdown = new AtomicBoolean(false);
  private final String managerName;
  private final List<ResourceLifecycleListener> listeners = new CopyOnWriteArrayList<>();

  public ResourceManager(String managerName) {
    this.managerName = managerName;
  }

  /** Register a managed resource. */
  public void registerResource(ManagedResource resource) throws Exception {
    ValidationUtils.notNull(resource, "ManagedResource cannot be null");
    
    if (shutdown.get()) {
      throw new IllegalStateException("ResourceManager " + managerName + " is shutdown");
    }

    String resourceName = resource.getResourceName();
    ValidationUtils.notEmptyString(resourceName, "Resource name cannot be null or empty");
    
    ResourceWrapper existing = resources.putIfAbsent(resourceName, new ResourceWrapper(resource));
    if (existing != null) {
      throw new IllegalArgumentException(
          "Resource with name '" + resourceName + "' already registered");
    }

    try {
      ResourceWrapper wrapper = resources.get(resourceName);
      wrapper.initialize();
      notifyListeners(ResourceLifecycleEvent.Type.REGISTERED, resource);
      LOGGER.info("Registered resource: { } (type: { })", resourceName, resource.getResourceType());
    } catch (Exception e) {
      resources.remove(resourceName);
      notifyListeners(ResourceLifecycleEvent.Type.REGISTRATION_FAILED, resource);
      throw KneafCoreException.builder()
          .category(KneafCoreException.ErrorCategory.RESOURCE_MANAGEMENT)
          .operation("registerResource")
          .message("Failed to register resource: " + resourceName)
          .cause(e)
          .build();
    }
  }

  /** Start a specific resource. */
  public void startResource(String resourceName) throws Exception {
    ValidationUtils.notEmptyString(resourceName, "Resource name cannot be null or empty");
    
    ResourceWrapper wrapper = resources.get(resourceName);
    if (wrapper == null) {
      throw new IllegalArgumentException("Resource not found: " + resourceName);
    }

    try {
      wrapper.start();
      notifyListeners(ResourceLifecycleEvent.Type.STARTED, wrapper.getResource());
      LOGGER.info("Started resource: { }", resourceName);
    } catch (Exception e) {
      notifyListeners(ResourceLifecycleEvent.Type.START_FAILED, wrapper.getResource());
      throw KneafCoreException.builder()
          .category(KneafCoreException.ErrorCategory.RESOURCE_MANAGEMENT)
          .operation("startResource")
          .message("Failed to start resource: " + resourceName)
          .cause(e)
          .build();
    }
  }

  /** Stop a specific resource. */
  public void stopResource(String resourceName) throws Exception {
    ValidationUtils.notEmptyString(resourceName, "Resource name cannot be null or empty");
    
    ResourceWrapper wrapper = resources.get(resourceName);
    if (wrapper == null) {
      return; // Resource not found, already stopped or never registered
    }

    try {
      wrapper.stop();
      notifyListeners(ResourceLifecycleEvent.Type.STOPPED, wrapper.getResource());
      LOGGER.info("Stopped resource: { }", resourceName);
    } catch (Exception e) {
      notifyListeners(ResourceLifecycleEvent.Type.STOP_FAILED, wrapper.getResource());
      throw KneafCoreException.builder()
          .category(KneafCoreException.ErrorCategory.RESOURCE_MANAGEMENT)
          .operation("stopResource")
          .message("Failed to stop resource: " + resourceName)
          .cause(e)
          .build();
    }
  }

  /** Start all registered resources. */
  public void startAll() throws Exception {
    List<Exception> exceptions = new ArrayList<>();

    for (ResourceWrapper wrapper : resources.values()) {
      try {
        startResource(wrapper.getResource().getResourceName());
      } catch (Exception e) {
        exceptions.add(e);
      }
    }

    if (!exceptions.isEmpty()) {
      KneafCoreException aggregated =
          KneafCoreException.builder()
              .category(KneafCoreException.ErrorCategory.RESOURCE_MANAGEMENT)
              .operation("startAll")
              .message("Failed to start " + exceptions.size() + " resources")
              .cause(exceptions.get(0))
              .build();
      exceptions.subList(1, exceptions.size()).forEach(aggregated::addSuppressed);
      throw aggregated;
    }
  }

  /** Stop all registered resources in reverse order of registration. */
  public void stopAll() throws Exception {
    shutdown.set(true);

    // Get resources in reverse order
    List<ResourceWrapper> resourceList = new ArrayList<>(resources.values());
    Collections.reverse(resourceList);

    List<Exception> exceptions = new ArrayList<>();

    for (ResourceWrapper wrapper : resourceList) {
      try {
        stopResource(wrapper.getResource().getResourceName());
      } catch (Exception e) {
        exceptions.add(e);
      }
    }

    if (!exceptions.isEmpty()) {
      KneafCoreException aggregated =
          KneafCoreException.builder()
              .category(KneafCoreException.ErrorCategory.RESOURCE_MANAGEMENT)
              .operation("stopAll")
              .message("Failed to stop " + exceptions.size() + " resources")
              .cause(exceptions.get(0))
              .build();
      exceptions.subList(1, exceptions.size()).forEach(aggregated::addSuppressed);
      throw aggregated;
    }
  }

  /** Perform health check on all resources. */
  public ResourceHealthReport checkHealth() {
    if (shutdown.get()) {
      return new ResourceHealthReport(Collections.emptyMap(), Collections.emptyMap());
    }

    Map<String, Boolean> healthStatus = new HashMap<>();
    Map<String, ResourceState> stateStatus = new HashMap<>();

    for (Map.Entry<String, ResourceWrapper> entry : resources.entrySet()) {
      String name = entry.getKey();
      ResourceWrapper wrapper = entry.getValue();

      healthStatus.put(name, wrapper.isHealthy());
      stateStatus.put(name, wrapper.getState());
    }

    return new ResourceHealthReport(healthStatus, stateStatus);
  }

  /** Get resource by name. */
  public ManagedResource getResource(String resourceName) {
    ValidationUtils.notEmptyString(resourceName, "Resource name cannot be null or empty");
    
    ResourceWrapper wrapper = resources.get(resourceName);
    return wrapper != null ? wrapper.getResource() : null;
  }

  /** Get all registered resource names. */
  public List<String> getResourceNames() {
    return new ArrayList<>(resources.keySet());
  }

  /** Get resource statistics. */
  public ResourceStatistics getStatistics() {
    Map<String, Object> Stats = new HashMap<>();
    long totalUptime = 0;
    int healthyCount = 0;

    for (Map.Entry<String, ResourceWrapper> entry : resources.entrySet()) {
      String name = entry.getKey();
      ResourceWrapper wrapper = entry.getValue();

      Map<String, Object> resourceStats = new HashMap<>();
      resourceStats.put("state", wrapper.getState().name());
      resourceStats.put("healthy", wrapper.isHealthy());
      resourceStats.put("initializationTime", wrapper.getInitializationTime());
      resourceStats.put("lastHealthCheckTime", wrapper.getLastHealthCheckTime());

      if (wrapper.isHealthy()) {
        healthyCount++;
      }

      Stats.put(name, resourceStats);
      totalUptime += (System.currentTimeMillis() - wrapper.getInitializationTime());
    }

    return new ResourceStatistics(resources.size(), healthyCount, totalUptime, Stats);
  }

  /** Add a lifecycle listener. */
  public void addLifecycleListener(ResourceLifecycleListener listener) {
    listeners.add(listener);
  }

  /** Remove a lifecycle listener. */
  public void removeLifecycleListener(ResourceLifecycleListener listener) {
    listeners.remove(listener);
  }

  private void notifyListeners(ResourceLifecycleEvent.Type type, ManagedResource resource) {
    ResourceLifecycleEvent event = new ResourceLifecycleEvent(type, resource, this);
    for (ResourceLifecycleListener listener : listeners) {
      try {
        listener.onResourceLifecycleEvent(event);
      } catch (Exception e) {
        LOGGER.warn("Lifecycle listener threw exception", e);
      }
    }
  }

  @Override
  public void close() throws IOException {
    try {
      stopAll();
    } catch (Exception e) {
      throw new IOException("Failed to close ResourceManager", e);
    }
  }

  public String getManagerName() {
    return managerName;
  }

  public boolean isShutdown() {
    return shutdown.get();
  }

  /** Resource lifecycle event. */
  public static class ResourceLifecycleEvent {
    public enum Type {
      REGISTERED,
      REGISTRATION_FAILED,
      STARTED,
      START_FAILED,
      STOPPED,
      STOP_FAILED
    }

    private final Type type;
    private final ManagedResource resource;
    private final ResourceManager source;

    public ResourceLifecycleEvent(Type type, ManagedResource resource, ResourceManager source) {
      this.type = type;
      this.resource = resource;
      this.source = source;
    }

    public Type getType() {
      return type;
    }

    public ManagedResource getResource() {
      return resource;
    }

    public ResourceManager getSource() {
      return source;
    }
  }

  /** Resource lifecycle listener. */
  public interface ResourceLifecycleListener {
    void onResourceLifecycleEvent(ResourceLifecycleEvent event);
  }

  /** Resource health report. */
  public static class ResourceHealthReport {
    private final Map<String, Boolean> healthStatus;
    private final Map<String, ResourceState> stateStatus;

    public ResourceHealthReport(
        Map<String, Boolean> healthStatus, Map<String, ResourceState> stateStatus) {
      this.healthStatus = Collections.unmodifiableMap(healthStatus);
      this.stateStatus = Collections.unmodifiableMap(stateStatus);
    }

    public Map<String, Boolean> getHealthStatus() {
      return healthStatus;
    }

    public Map<String, ResourceState> getStateStatus() {
      return stateStatus;
    }

    public boolean isHealthy() {
      return healthStatus.values().stream().allMatch(Boolean::booleanValue);
    }

    public boolean isHealthy(String resourceName) {
      return Boolean.TRUE.equals(healthStatus.get(resourceName));
    }
  }

  /** Resource statistics. */
  public static class ResourceStatistics {
    private final int totalResources;
    private final int healthyResources;
    private final long totalUptimeMs;
    private final Map<String, Object> detailedStats;

    public ResourceStatistics(
        int totalResources,
        int healthyResources,
        long totalUptimeMs,
        Map<String, Object> detailedStats) {
      this.totalResources = totalResources;
      this.healthyResources = healthyResources;
      this.totalUptimeMs = totalUptimeMs;
      this.detailedStats = Collections.unmodifiableMap(detailedStats);
    }

    public int getTotalResources() {
      return totalResources;
    }

    public int getHealthyResources() {
      return healthyResources;
    }

    public long getTotalUptimeMs() {
      return totalUptimeMs;
    }

    public Map<String, Object> getDetailedStats() {
      return detailedStats;
    }

    public double getHealthPercentage() {
      return totalResources > 0 ? (healthyResources * 100.0 / totalResources) : 0.0;
    }
  }

  /** Create a new ResourceManager. */
  public static ResourceManager create(String managerName) {
    return new ResourceManager(managerName);
  }
}
