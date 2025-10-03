package com.kneaf.core.performance;

/**
 * Compatibility shim for legacy tests that import com.kneaf.core.performance.PerformanceConfig.
 * Delegates to the real implementation in com.kneaf.core.performance.monitoring.PerformanceConfig.
 */
public final class PerformanceConfig {
    private final com.kneaf.core.performance.monitoring.PerformanceConfig delegate;

    private PerformanceConfig(com.kneaf.core.performance.monitoring.PerformanceConfig d) {
        this.delegate = d;
    }

    public static PerformanceConfig load() {
        return new PerformanceConfig(com.kneaf.core.performance.monitoring.PerformanceConfig.load());
    }

    // Delegate commonly used getters used by tests
    public int getNetworkExecutorPoolSize() { return delegate.getNetworkExecutorPoolSize(); }
    public boolean isDynamicThreadScaling() { return delegate.isDynamicThreadScaling(); }
    public boolean isWorkStealingEnabled() { return delegate.isWorkStealingEnabled(); }
    public boolean isCpuAwareThreadSizing() { return delegate.isCpuAwareThreadSizing(); }
    public int getMinThreadPoolSize() { return delegate.getMinThreadPoolSize(); }
    public int getMaxThreadPoolSize() { return delegate.getMaxThreadPoolSize(); }
    public double getThreadScaleUpThreshold() { return delegate.getThreadScaleUpThreshold(); }
    public double getThreadScaleDownThreshold() { return delegate.getThreadScaleDownThreshold(); }
    public int getThreadScaleUpDelayTicks() { return delegate.getThreadScaleUpDelayTicks(); }
    public int getThreadScaleDownDelayTicks() { return delegate.getThreadScaleDownDelayTicks(); }
    public int getThreadPoolKeepAliveSeconds() { return delegate.getThreadPoolKeepAliveSeconds(); }
}
