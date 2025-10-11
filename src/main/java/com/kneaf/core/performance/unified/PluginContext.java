package com.kneaf.core.performance.unified;

import com.kneaf.core.performance.monitoring.PerformanceConfig;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Context provided to plugins during initialization, containing configuration
 * and services needed by the plugin.
 */
public final class PluginContext {
    private final PerformanceConfig performanceConfig;
    private final PerformanceManager performanceManager;
    private final Map<String, Object> services;
    private final String pluginId;

    /**
     * Create a new plugin context.
     *
     * @param builder the builder to create context from
     */
    private PluginContext(Builder builder) {
        this.performanceConfig = Objects.requireNonNull(builder.performanceConfig, "Performance config must not be null");
        this.performanceManager = Objects.requireNonNull(builder.performanceManager, "Performance manager must not be null");
        this.services = Map.copyOf(Objects.requireNonNull(builder.services, "Services map must not be null"));
        this.pluginId = Objects.requireNonNull(builder.pluginId, "Plugin ID must not be null");
    }

    /**
     * Get the performance configuration.
     *
     * @return performance configuration
     */
    public PerformanceConfig getPerformanceConfig() {
        return performanceConfig;
    }

    /**
     * Get the performance manager.
     *
     * @return performance manager
     */
    public PerformanceManager getPerformanceManager() {
        return performanceManager;
    }

    /**
     * Get a service by type.
     *
     * @param serviceType the service type class
     * @param <T>         the service type
     * @return optional service instance
     */
    public <T> Optional<T> getService(Class<T> serviceType) {
        Objects.requireNonNull(serviceType, "Service type must not be null");
        
        for (Map.Entry<String, Object> entry : services.entrySet()) {
            if (serviceType.isAssignableFrom(entry.getValue().getClass())) {
                return Optional.of((T) entry.getValue());
            }
        }
        
        return Optional.empty();
    }

    /**
     * Get a service by name.
     *
     * @param serviceName the service name
     * @return optional service instance
     */
    public Optional<Object> getService(String serviceName) {
        Objects.requireNonNull(serviceName, "Service name must not be null");
        return Optional.ofNullable(services.get(serviceName));
    }

    /**
     * Get all available services.
     *
     * @return unmodifiable map of all services
     */
    public Map<String, Object> getServices() {
        return services;
    }

    /**
     * Get the plugin ID for which this context is intended.
     *
     * @return plugin ID
     */
    public String getPluginId() {
        return pluginId;
    }

    /**
     * Builder for PluginContext.
     */
    public static class Builder {
        private PerformanceConfig performanceConfig;
        private PerformanceManager performanceManager;
        private Map<String, Object> services = Map.of();
        private String pluginId;

        /**
         * Set the performance configuration.
         *
         * @param performanceConfig the performance configuration
         * @return builder
         */
        public Builder performanceConfig(PerformanceConfig performanceConfig) {
            this.performanceConfig = performanceConfig;
            return this;
        }

        /**
         * Set the performance manager.
         *
         * @param performanceManager the performance manager
         * @return builder
         */
        public Builder performanceManager(PerformanceManager performanceManager) {
            this.performanceManager = performanceManager;
            return this;
        }

        /**
         * Set services map.
         *
         * @param services the services map
         * @return builder
         */
        public Builder services(Map<String, Object> services) {
            this.services = services;
            return this;
        }

        /**
         * Add a service.
         *
         * @param name   the service name
         * @param service the service instance
         * @return builder
         */
        public Builder addService(String name, Object service) {
            Objects.requireNonNull(name, "Service name must not be null");
            Objects.requireNonNull(service, "Service instance must not be null");
            
            this.services.put(name, service);
            return this;
        }

        /**
         * Set the plugin ID.
         *
         * @param pluginId the plugin ID
         * @return builder
         */
        public Builder pluginId(String pluginId) {
            this.pluginId = pluginId;
            return this;
        }

        /**
         * Build the PluginContext instance.
         *
         * @return plugin context
         */
        public PluginContext build() {
            return new PluginContext(this);
        }
    }
}