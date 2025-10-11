package com.kneaf.core.config.core;

import com.kneaf.core.config.exception.ConfigurationException;
import com.kneaf.core.config.performance.PerformanceConfig;

/**
 * Validator for PerformanceConfig instances.
 */
public class PerformanceConfigValidator extends BaseConfigValidator {

    @Override
    public void validate(Object configuration) throws ConfigurationException {
        if (!(configuration instanceof PerformanceConfig)) {
            throw new ConfigurationException("Invalid configuration type. Expected PerformanceConfig");
        }

        PerformanceConfig config = (PerformanceConfig) configuration;

        // Validate basic settings
        validateMin(config.getThreadpoolSize(), 1, "threadpoolSize");
        validateMin(config.getLogIntervalTicks(), 1, "logIntervalTicks");
        validateRange(config.getScanIntervalTicks(), 1, 100, "scanIntervalTicks");
        validateRange(config.getTpsThresholdForAsync(), 0.0, 20.0, "tpsThresholdForAsync");
        validateMin(config.getMaxEntitiesToCollect(), 1, "maxEntitiesToCollect");
        validateMin(config.getMaxLogBytes(), 1, "maxLogBytes");
        validateMin(config.getNetworkExecutorpoolSize(), 1, "networkExecutorpoolSize");
        validateMin(config.getProfilingSampleRate(), 1, "profilingSampleRate");

        // Validate advanced parallelism settings
        validateMin(config.getMinThreadpoolSize(), 1, "minThreadpoolSize");
        validateMin(config.getMaxThreadpoolSize(), config.getMinThreadpoolSize(), "maxThreadpoolSize");
        validateRange(config.getThreadScaleUpThreshold(), 0.0, 1.0, "threadScaleUpThreshold");
        validateRange(config.getThreadScaleDownThreshold(), 0.0, 1.0, "threadScaleDownThreshold");
        validateMin(config.getThreadScaleUpDelayTicks(), 1, "threadScaleUpDelayTicks");
        validateMin(config.getThreadScaleDownDelayTicks(), 1, "threadScaleDownDelayTicks");
        validateMin(config.getWorkStealingQueueSize(), 1, "workStealingQueueSize");
        validateRange(config.getCpuLoadThreshold(), 0.0, 1.0, "cpuLoadThreshold");
        validateMin(config.getThreadPoolKeepAliveSeconds(), 1, "threadPoolKeepAliveSeconds");

        // Validate distance & processing optimizations
        validateMin(config.getDistanceCalculationInterval(), 1, "distanceCalculationInterval");
        validateMin(config.getDistanceCacheSize(), 1, "distanceCacheSize");
        validateMin(config.getItemProcessingIntervalMultiplier(), 1, "itemProcessingIntervalMultiplier");
        validateMin(config.getSpatialGridUpdateInterval(), 1, "spatialGridUpdateInterval");
    }

    @Override
    public boolean supports(Class<?> configurationType) {
        return PerformanceConfig.class.equals(configurationType);
    }
}