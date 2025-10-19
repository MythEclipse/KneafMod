package com.kneaf.core.performance;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;
import java.time.Instant;
import java.time.Duration;

/**
 * Distributed tracing support for Java and Rust components.
 * Provides trace correlation, context propagation, and cross-component trace analysis.
 */
public final class DistributedTracer {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Trace storage
    private final ConcurrentHashMap<String, DistributedTrace> activeTraces = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DistributedTrace> completedTraces = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<TraceSpan> recentSpans = new ConcurrentLinkedQueue<>();
    
    // Trace configuration
    private final AtomicInteger maxActiveTraces = new AtomicInteger(1000);
    private final AtomicInteger maxCompletedTraces = new AtomicInteger(10000);
    private final AtomicInteger maxRecentSpans = new AtomicInteger(5000);
    private final AtomicBoolean isEnabled = new AtomicBoolean(false); // Disabled by default
    private final AtomicDouble samplingRate = new AtomicDouble(0.1); // 10% sampling by default
    
    // Trace context propagation
    private final ThreadLocal<TraceContext> currentTraceContext = new ThreadLocal<>();
    
    // Statistics
    private final AtomicLong tracesStarted = new AtomicLong(0);
    private final AtomicLong tracesCompleted = new AtomicLong(0);
    private final AtomicLong spansCreated = new AtomicLong(0);
    private final AtomicLong traceErrors = new AtomicLong(0);
    
    // Health monitoring
    private final AtomicBoolean isHealthy = new AtomicBoolean(true);
    private final AtomicLong lastSuccessfulTrace = new AtomicLong(System.currentTimeMillis());
    
    // Trace correlation with Rust components
    private final ConcurrentHashMap<String, String> rustTraceCorrelation = new ConcurrentHashMap<>();
    
    public DistributedTracer() {
        LOGGER.info("Initializing DistributedTracer");
    }
    
    /**
     * Start a new trace
     */
    public String startTrace(String component, String operation, Map<String, Object> context) {
        if (!isEnabled.get() || !shouldSample()) {
            return null;
        }
        
        try {
            String traceId = generateTraceId();
            DistributedTrace trace = new DistributedTrace(traceId, component, operation, 
                                                          Instant.now(), new HashMap<>(context));
            
            // Store trace
            activeTraces.put(traceId, trace);
            
            // Set current trace context
            TraceContext traceContext = new TraceContext(traceId, trace.getRootSpan().getSpanId());
            currentTraceContext.set(traceContext);
            
            tracesStarted.incrementAndGet();
            lastSuccessfulTrace.set(System.currentTimeMillis());
            
            LOGGER.debug("Started trace: {} for component '{}' operation '{}'", 
                        traceId, component, operation);
            
            updateHealthStatus();
            
            return traceId;
            
        } catch (Exception e) {
            LOGGER.error("Error starting trace for component '{}' operation '{}'", component, operation, e);
            traceErrors.incrementAndGet();
            isHealthy.set(false);
            return null;
        }
    }
    
    /**
     * End a trace
     */
    public void endTrace(String traceId, String component, String operation) {
        if (!isEnabled.get() || traceId == null) {
            return;
        }
        
        try {
            DistributedTrace trace = activeTraces.remove(traceId);
            if (trace == null) {
                LOGGER.warn("Trace '{}' not found when trying to end", traceId);
                return;
            }
            
            // End root span
            trace.getRootSpan().end();
            
            // Complete trace
            trace.complete();
            
            // Store completed trace
            storeCompletedTrace(trace);
            
            // Clear current trace context
            TraceContext currentContext = currentTraceContext.get();
            if (currentContext != null && currentContext.getTraceId().equals(traceId)) {
                currentTraceContext.remove();
            }
            
            tracesCompleted.incrementAndGet();
            lastSuccessfulTrace.set(System.currentTimeMillis());
            
            LOGGER.debug("Completed trace: {} for component '{}' operation '{}'", 
                        traceId, component, operation);
            
            updateHealthStatus();
            
        } catch (Exception e) {
            LOGGER.error("Error ending trace '{}'", traceId, e);
            traceErrors.incrementAndGet();
            isHealthy.set(false);
        }
    }
    
    /**
     * Create a new span within the current trace
     */
    public TraceSpan createSpan(String component, String operation, Map<String, Object> tags) {
        if (!isEnabled.get()) {
            return null;
        }
        
        try {
            TraceContext context = currentTraceContext.get();
            if (context == null) {
                // No active trace, create a new one
                String traceId = startTrace(component, operation, tags);
                if (traceId != null) {
                    context = currentTraceContext.get();
                } else {
                    return null;
                }
            }
            
            // Create span
            String spanId = generateSpanId();
            TraceSpan span = new TraceSpan(
                context.getTraceId(),
                spanId,
                context.getParentSpanId(),
                component,
                operation,
                Instant.now(),
                new HashMap<>(tags != null ? tags : new HashMap<>())
            );
            
            // Add to current trace
            DistributedTrace trace = activeTraces.get(context.getTraceId());
            if (trace != null) {
                trace.addSpan(span);
            }
            
            // Store recent span
            recentSpans.offer(span);
            
            // Trim recent spans
            while (recentSpans.size() > maxRecentSpans.get()) {
                recentSpans.poll();
            }
            
            spansCreated.incrementAndGet();
            lastSuccessfulTrace.set(System.currentTimeMillis());
            
            LOGGER.debug("Created span: {} in trace: {} for component '{}' operation '{}'", 
                        spanId, context.getTraceId(), component, operation);
            
            updateHealthStatus();
            
            return span;
            
        } catch (Exception e) {
            LOGGER.error("Error creating span for component '{}' operation '{}'", component, operation, e);
            traceErrors.incrementAndGet();
            isHealthy.set(false);
            return null;
        }
    }
    
    /**
     * End a span
     */
    public void endSpan(TraceSpan span, Map<String, Object> tags) {
        if (!isEnabled.get() || span == null) {
            return;
        }
        
        try {
            span.end();
            
            if (tags != null) {
                span.getTags().putAll(tags);
            }
            
            LOGGER.debug("Ended span: {} in trace: {} for component '{}' operation '{}'", 
                        span.getSpanId(), span.getTraceId(), span.getComponent(), span.getOperation());
            
            updateHealthStatus();
            
        } catch (Exception e) {
            LOGGER.error("Error ending span '{}'", span.getSpanId(), e);
            traceErrors.incrementAndGet();
            isHealthy.set(false);
        }
    }
    
    /**
     * Correlate with Rust trace
     */
    public void correlateWithRustTrace(String javaTraceId, String rustTraceId) {
        if (!isEnabled.get()) {
            return;
        }
        
        try {
            rustTraceCorrelation.put(javaTraceId, rustTraceId);
            LOGGER.debug("Correlated Java trace '{}' with Rust trace '{}'", javaTraceId, rustTraceId);
            
        } catch (Exception e) {
            LOGGER.error("Error correlating traces", e);
            traceErrors.incrementAndGet();
            isHealthy.set(false);
        }
    }
    
    /**
     * Get Rust trace ID for Java trace
     */
    public String getRustTraceId(String javaTraceId) {
        return rustTraceCorrelation.get(javaTraceId);
    }
    
    /**
     * Get trace by ID
     */
    public DistributedTrace getTrace(String traceId) {
        DistributedTrace trace = activeTraces.get(traceId);
        if (trace == null) {
            trace = completedTraces.get(traceId);
        }
        return trace;
    }
    
    /**
     * Get active traces
     */
    public List<DistributedTrace> getActiveTraces() {
        return new ArrayList<>(activeTraces.values());
    }
    
    /**
     * Get completed traces
     */
    public List<DistributedTrace> getCompletedTraces(int maxCount) {
        List<DistributedTrace> traces = new ArrayList<>(completedTraces.values());
        traces.sort((a, b) -> b.getEndTime().compareTo(a.getEndTime()));
        
        if (traces.size() > maxCount) {
            traces = traces.subList(0, maxCount);
        }
        
        return traces;
    }
    
    /**
     * Get recent spans
     */
    public List<TraceSpan> getRecentSpans(int maxCount) {
        List<TraceSpan> spans = new ArrayList<>(recentSpans);
        spans.sort((a, b) -> b.getStartTime().compareTo(a.getStartTime()));
        
        if (spans.size() > maxCount) {
            spans = spans.subList(0, maxCount);
        }
        
        return spans;
    }
    
    /**
     * Get trace statistics
     */
    public TraceStatistics getStatistics() {
        return new TraceStatistics(
            tracesStarted.get(),
            tracesCompleted.get(),
            activeTraces.size(),
            completedTraces.size(),
            spansCreated.get(),
            traceErrors.get()
        );
    }
    
    /**
     * Analyze trace patterns
     */
    public TraceAnalysis analyzeTraces() {
        TraceAnalysis analysis = new TraceAnalysis();
        
        // Analyze completed traces
        for (DistributedTrace trace : completedTraces.values()) {
            analysis.addTrace(trace);
        }
        
        // Analyze active traces
        for (DistributedTrace trace : activeTraces.values()) {
            analysis.addActiveTrace(trace);
        }
        
        return analysis;
    }
    
    /**
     * Clear all traces
     */
    public void clearAllTraces() {
        activeTraces.clear();
        completedTraces.clear();
        recentSpans.clear();
        rustTraceCorrelation.clear();
        tracesStarted.set(0);
        tracesCompleted.set(0);
        spansCreated.set(0);
        traceErrors.set(0);
        LOGGER.info("All traces cleared");
    }
    
    /**
     * Enable/disable tracing
     */
    public void setEnabled(boolean enabled) {
        this.isEnabled.set(enabled);
        if (!enabled) {
            clearAllTraces();
        }
        LOGGER.info("DistributedTracer {}", enabled ? "enabled" : "disabled");
    }
    
    /**
     * Set sampling rate (0.0 - 1.0)
     */
    public void setSamplingRate(double rate) {
        this.samplingRate.set(Math.max(0.0, Math.min(1.0, rate)));
        LOGGER.info("Sampling rate set to {}%", (int)(rate * 100));
    }
    
    /**
     * Set maximum active traces
     */
    public void setMaxActiveTraces(int maxTraces) {
        this.maxActiveTraces.set(Math.max(10, maxTraces)); // Minimum 10
        LOGGER.info("Max active traces set to {}", maxTraces);
    }
    
    /**
     * Set maximum completed traces
     */
    public void setMaxCompletedTraces(int maxTraces) {
        this.maxCompletedTraces.set(Math.max(100, maxTraces)); // Minimum 100
        LOGGER.info("Max completed traces set to {}", maxTraces);
    }
    
    /**
     * Set maximum recent spans
     */
    public void setMaxRecentSpans(int maxSpans) {
        this.maxRecentSpans.set(Math.max(100, maxSpans)); // Minimum 100
        LOGGER.info("Max recent spans set to {}", maxSpans);
    }
    
    /**
     * Check if tracer is healthy
     */
    public boolean isHealthy() {
        return isHealthy.get() && 
               (System.currentTimeMillis() - lastSuccessfulTrace.get()) < 30000; // 30 second timeout
    }
    
    /**
     * Shutdown the tracer
     */
    public void shutdown() {
        LOGGER.info("Shutting down DistributedTracer");
        setEnabled(false);
        clearAllTraces();
        currentTraceContext.remove();
    }
    
    /**
     * Update health status
     */
    private void updateHealthStatus() {
        boolean healthy = traceErrors.get() < 100 && // Less than 100 trace errors
                         (System.currentTimeMillis() - lastSuccessfulTrace.get()) < 60000; // Within 1 minute
        isHealthy.set(healthy);
    }
    
    /**
     * Determine if current operation should be sampled
     */
    private boolean shouldSample() {
        if (samplingRate.get() >= 1.0) {
            return true;
        }
        if (samplingRate.get() <= 0.0) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble() < samplingRate.get();
    }
    
    /**
     * Generate unique trace ID
     */
    private String generateTraceId() {
        return "trace-" + System.currentTimeMillis() + "-" + ThreadLocalRandom.current().nextInt(1000000);
    }
    
    /**
     * Generate unique span ID
     */
    private String generateSpanId() {
        return "span-" + System.currentTimeMillis() + "-" + ThreadLocalRandom.current().nextInt(1000000);
    }
    
    /**
     * Store completed trace
     */
    private void storeCompletedTrace(DistributedTrace trace) {
        completedTraces.put(trace.getTraceId(), trace);
        
        // Remove old traces if limit exceeded
        while (completedTraces.size() > maxCompletedTraces.get()) {
            // Remove oldest trace
            String oldestTraceId = null;
            Instant oldestTime = Instant.now();
            
            for (Map.Entry<String, DistributedTrace> entry : completedTraces.entrySet()) {
                if (entry.getValue().getEndTime().isBefore(oldestTime)) {
                    oldestTime = entry.getValue().getEndTime();
                    oldestTraceId = entry.getKey();
                }
            }
            
            if (oldestTraceId != null) {
                completedTraces.remove(oldestTraceId);
            }
        }
    }
    
    /**
     * Trace context for current thread
     */
    public static class TraceContext {
        private final String traceId;
        private final String parentSpanId;
        
        public TraceContext(String traceId, String parentSpanId) {
            this.traceId = traceId;
            this.parentSpanId = parentSpanId;
        }
        
        public String getTraceId() { return traceId; }
        public String getParentSpanId() { return parentSpanId; }
    }
    
    /**
     * Distributed trace information
     */
    public static class DistributedTrace {
        private final String traceId;
        private final String component;
        private final String operation;
        private final Instant startTime;
        private volatile Instant endTime;
        private final Map<String, Object> context;
        private final TraceSpan rootSpan;
        private final List<TraceSpan> spans = new ArrayList<>();
        private final AtomicBoolean completed = new AtomicBoolean(false);
        
        public DistributedTrace(String traceId, String component, String operation, 
                               Instant startTime, Map<String, Object> context) {
            this.traceId = traceId;
            this.component = component;
            this.operation = operation;
            this.startTime = startTime;
            this.endTime = null;
            this.context = new HashMap<>(context);
            this.rootSpan = new TraceSpan(traceId, "root", null, component, operation, startTime, new HashMap<>());
        }
        
        public void complete() {
            if (completed.compareAndSet(false, true)) {
                this.endTime = Instant.now();
            }
        }
        
        public void addSpan(TraceSpan span) {
            spans.add(span);
        }
        
        public String getTraceId() { return traceId; }
        public String getComponent() { return component; }
        public String getOperation() { return operation; }
        public Instant getStartTime() { return startTime; }
        public Instant getEndTime() { return endTime; }
        public Map<String, Object> getContext() { return new HashMap<>(context); }
        public TraceSpan getRootSpan() { return rootSpan; }
        public List<TraceSpan> getSpans() { return new ArrayList<>(spans); }
        public boolean isCompleted() { return completed.get(); }
        
        public long getDurationMs() {
            if (endTime == null) {
                return java.time.Duration.between(startTime, Instant.now()).toMillis();
            }
            return java.time.Duration.between(startTime, endTime).toMillis();
        }
        
        public double getDurationSeconds() {
            return getDurationMs() / 1000.0;
        }
        
        @Override
        public String toString() {
            return String.format("DistributedTrace{traceId='%s', component='%s', operation='%s', startTime=%s, endTime=%s, completed=%b}",
                    traceId, component, operation, startTime, endTime, completed.get());
        }
    }
    
    /**
     * Trace span information
     */
    public static class TraceSpan {
        private final String traceId;
        private final String spanId;
        private final String parentSpanId;
        private final String component;
        private final String operation;
        private final Instant startTime;
        private volatile Instant endTime;
        private final Map<String, Object> tags;
        private final AtomicBoolean ended = new AtomicBoolean(false);
        
        public TraceSpan(String traceId, String spanId, String parentSpanId, 
                        String component, String operation, Instant startTime, 
                        Map<String, Object> tags) {
            this.traceId = traceId;
            this.spanId = spanId;
            this.parentSpanId = parentSpanId;
            this.component = component;
            this.operation = operation;
            this.startTime = startTime;
            this.endTime = null;
            this.tags = new HashMap<>(tags);
        }
        
        public void end() {
            if (ended.compareAndSet(false, true)) {
                this.endTime = Instant.now();
            }
        }
        
        public String getTraceId() { return traceId; }
        public String getSpanId() { return spanId; }
        public String getParentSpanId() { return parentSpanId; }
        public String getComponent() { return component; }
        public String getOperation() { return operation; }
        public Instant getStartTime() { return startTime; }
        public Instant getEndTime() { return endTime; }
        public Map<String, Object> getTags() { return new HashMap<>(tags); }
        public boolean isEnded() { return ended.get(); }
        
        public long getDurationMs() {
            if (endTime == null) {
                return java.time.Duration.between(startTime, Instant.now()).toMillis();
            }
            return java.time.Duration.between(startTime, endTime).toMillis();
        }
        
        public double getDurationSeconds() {
            return getDurationMs() / 1000.0;
        }
        
        @Override
        public String toString() {
            return String.format("TraceSpan{traceId='%s', spanId='%s', component='%s', operation='%s', startTime=%s, endTime=%s, ended=%b}",
                    traceId, spanId, component, operation, startTime, endTime, ended.get());
        }
    }
    
    /**
     * Trace statistics
     */
    public static class TraceStatistics {
        private final long tracesStarted;
        private final long tracesCompleted;
        private final int activeTraces;
        private final int completedTraces;
        private final long spansCreated;
        private final long traceErrors;
        
        public TraceStatistics(long tracesStarted, long tracesCompleted, int activeTraces, 
                              int completedTraces, long spansCreated, long traceErrors) {
            this.tracesStarted = tracesStarted;
            this.tracesCompleted = tracesCompleted;
            this.activeTraces = activeTraces;
            this.completedTraces = completedTraces;
            this.spansCreated = spansCreated;
            this.traceErrors = traceErrors;
        }
        
        public long getTracesStarted() { return tracesStarted; }
        public long getTracesCompleted() { return tracesCompleted; }
        public int getActiveTraces() { return activeTraces; }
        public int getCompletedTraces() { return completedTraces; }
        public long getSpansCreated() { return spansCreated; }
        public long getTraceErrors() { return traceErrors; }
        public double getCompletionRate() { 
            return tracesStarted > 0 ? (double) tracesCompleted / tracesStarted : 0.0; 
        }
        public double getErrorRate() { 
            return tracesStarted > 0 ? (double) traceErrors / tracesStarted : 0.0; 
        }
    }
    
    /**
     * Trace analysis results
     */
    public static class TraceAnalysis {
        private final Map<String, ComponentAnalysis> componentAnalysis = new HashMap<>();
        private final Map<String, OperationAnalysis> operationAnalysis = new HashMap<>();
        private final List<DistributedTrace> slowTraces = new ArrayList<>();
        private final List<DistributedTrace> errorTraces = new ArrayList<>();
        private final AtomicLong totalTraces = new AtomicLong(0);
        private final AtomicLong totalDuration = new AtomicLong(0);
        
        public void addTrace(DistributedTrace trace) {
            totalTraces.incrementAndGet();
            totalDuration.addAndGet(trace.getDurationMs());
            
            // Component analysis
            ComponentAnalysis component = componentAnalysis.computeIfAbsent(trace.getComponent(), 
                k -> new ComponentAnalysis(trace.getComponent()));
            component.addTrace(trace);
            
            // Operation analysis
            OperationAnalysis operation = operationAnalysis.computeIfAbsent(trace.getOperation(), 
                k -> new OperationAnalysis(trace.getOperation()));
            operation.addTrace(trace);
            
            // Check for slow traces
            if (trace.getDurationMs() > 1000) { // > 1 second
                slowTraces.add(trace);
            }
            
            // Check for error traces (based on tags)
            if (trace.getContext().containsKey("error")) {
                errorTraces.add(trace);
            }
        }
        
        public void addActiveTrace(DistributedTrace trace) {
            // Component analysis for active traces
            ComponentAnalysis component = componentAnalysis.computeIfAbsent(trace.getComponent(), 
                k -> new ComponentAnalysis(trace.getComponent()));
            component.addActiveTrace(trace);
        }
        
        public Map<String, ComponentAnalysis> getComponentAnalysis() { return new HashMap<>(componentAnalysis); }
        public Map<String, OperationAnalysis> getOperationAnalysis() { return new HashMap<>(operationAnalysis); }
        public List<DistributedTrace> getSlowTraces() { return new ArrayList<>(slowTraces); }
        public List<DistributedTrace> getErrorTraces() { return new ArrayList<>(errorTraces); }
        public long getTotalTraces() { return totalTraces.get(); }
        public long getTotalDuration() { return totalDuration.get(); }
        public double getAverageDuration() { 
            return totalTraces.get() > 0 ? (double) totalDuration.get() / totalTraces.get() : 0.0; 
        }
    }
    
    /**
     * Component analysis
     */
    public static class ComponentAnalysis {
        private final String component;
        private final AtomicLong traceCount = new AtomicLong(0);
        private final AtomicLong totalDuration = new AtomicLong(0);
        private final AtomicLong activeTraceCount = new AtomicLong(0);
        private final AtomicLong errorCount = new AtomicLong(0);
        
        public ComponentAnalysis(String component) {
            this.component = component;
        }
        
        public void addTrace(DistributedTrace trace) {
            traceCount.incrementAndGet();
            totalDuration.addAndGet(trace.getDurationMs());
            
            if (trace.getContext().containsKey("error")) {
                errorCount.incrementAndGet();
            }
        }
        
        public void addActiveTrace(DistributedTrace trace) {
            activeTraceCount.incrementAndGet();
        }
        
        public String getComponent() { return component; }
        public long getTraceCount() { return traceCount.get(); }
        public long getTotalDuration() { return totalDuration.get(); }
        public long getActiveTraceCount() { return activeTraceCount.get(); }
        public long getErrorCount() { return errorCount.get(); }
        public double getAverageDuration() { 
            return traceCount.get() > 0 ? (double) totalDuration.get() / traceCount.get() : 0.0; 
        }
        public double getErrorRate() { 
            return traceCount.get() > 0 ? (double) errorCount.get() / traceCount.get() : 0.0; 
        }
    }
    
    /**
     * Operation analysis
     */
    public static class OperationAnalysis {
        private final String operation;
        private final AtomicLong traceCount = new AtomicLong(0);
        private final AtomicLong totalDuration = new AtomicLong(0);
        private final AtomicLong errorCount = new AtomicLong(0);
        
        public OperationAnalysis(String operation) {
            this.operation = operation;
        }
        
        public void addTrace(DistributedTrace trace) {
            traceCount.incrementAndGet();
            totalDuration.addAndGet(trace.getDurationMs());
            
            if (trace.getContext().containsKey("error")) {
                errorCount.incrementAndGet();
            }
        }
        
        public String getOperation() { return operation; }
        public long getTraceCount() { return traceCount.get(); }
        public long getTotalDuration() { return totalDuration.get(); }
        public long getErrorCount() { return errorCount.get(); }
        public double getAverageDuration() { 
            return traceCount.get() > 0 ? (double) totalDuration.get() / traceCount.get() : 0.0; 
        }
        public double getErrorRate() { 
            return traceCount.get() > 0 ? (double) errorCount.get() / traceCount.get() : 0.0; 
        }
    }
}