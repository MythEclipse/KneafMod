package com.kneaf.core.performance;

import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/**
 * Represents a cross-component performance event.
 * Contains information about the event source, type, timing, and contextual data.
 */
public final class CrossComponentEvent {
    private final String id;
    private final String component;
    private final String eventType;
    private final Instant timestamp;
    private final long durationNs;
    private final Map<String, Object> context;
    private final String traceId;
    
    public CrossComponentEvent(String component, String eventType, Instant timestamp, 
                              long durationNs, Map<String, Object> context) {
        this.id = UUID.randomUUID().toString();
        this.component = component;
        this.eventType = eventType;
        this.timestamp = timestamp;
        this.durationNs = durationNs;
        this.context = new HashMap<>(context != null ? context : new HashMap<>());
        this.traceId = this.context.getOrDefault("traceId", generateTraceId()).toString();
        
        // Add event ID to context
        this.context.put("eventId", id);
        this.context.put("traceId", traceId);
    }
    
    public String getId() {
        return id;
    }
    
    public String getComponent() {
        return component;
    }
    
    public String getEventType() {
        return eventType;
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    public long getDurationNs() {
        return durationNs;
    }
    
    public double getDurationMs() {
        return durationNs / 1_000_000.0;
    }
    
    public Map<String, Object> getContext() {
        return new HashMap<>(context);
    }
    
    public String getTraceId() {
        return traceId;
    }
    
    public Object getContextValue(String key) {
        return context.get(key);
    }
    
    public String getContextString(String key) {
        Object value = context.get(key);
        return value != null ? value.toString() : null;
    }
    
    public Long getContextLong(String key) {
        Object value = context.get(key);
        if (value instanceof Long) {
            return (Long) value;
        } else if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return null;
    }
    
    public Double getContextDouble(String key) {
        Object value = context.get(key);
        if (value instanceof Double) {
            return (Double) value;
        } else if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return null;
    }
    
    public Boolean getContextBoolean(String key) {
        Object value = context.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        } else if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return null;
    }
    
    public boolean hasContext(String key) {
        return context.containsKey(key);
    }
    
    public void addContext(String key, Object value) {
        context.put(key, value);
    }
    
    public void removeContext(String key) {
        context.remove(key);
    }
    
    public long getAgeMs() {
        return java.time.Duration.between(timestamp, Instant.now()).toMillis();
    }
    
    public boolean isOlderThan(long ageMs) {
        return getAgeMs() > ageMs;
    }
    
    public boolean isRecent(long withinMs) {
        return getAgeMs() <= withinMs;
    }
    
    @Override
    public String toString() {
        return String.format("CrossComponentEvent{id='%s', component='%s', eventType='%s', timestamp=%s, durationNs=%d, context=%s, traceId='%s'}",
                id, component, eventType, timestamp, durationNs, context, traceId);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        CrossComponentEvent that = (CrossComponentEvent) obj;
        return id.equals(that.id);
    }
    
    @Override
    public int hashCode() {
        return id.hashCode();
    }
    
    private String generateTraceId() {
        return component + "-" + eventType + "-" + timestamp.toEpochMilli();
    }
    
    /**
     * Builder for CrossComponentEvent
     */
    public static class Builder {
        private String component;
        private String eventType;
        private Instant timestamp;
        private long durationNs;
        private Map<String, Object> context;
        
        public Builder() {
            this.timestamp = Instant.now();
            this.context = new HashMap<>();
        }
        
        public Builder component(String component) {
            this.component = component;
            return this;
        }
        
        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }
        
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        
        public Builder durationNs(long durationNs) {
            this.durationNs = durationNs;
            return this;
        }
        
        public Builder durationMs(double durationMs) {
            this.durationNs = (long)(durationMs * 1_000_000);
            return this;
        }
        
        public Builder context(Map<String, Object> context) {
            if (context != null) {
                this.context.putAll(context);
            }
            return this;
        }
        
        public Builder addContext(String key, Object value) {
            this.context.put(key, value);
            return this;
        }
        
        public Builder traceId(String traceId) {
            this.context.put("traceId", traceId);
            return this;
        }
        
        public Builder operationId(String operationId) {
            this.context.put("operationId", operationId);
            return this;
        }
        
        public Builder entityId(String entityId) {
            this.context.put("entityId", entityId);
            return this;
        }
        
        public Builder threadId(long threadId) {
            this.context.put("threadId", threadId);
            return this;
        }
        
        public Builder threadName(String threadName) {
            this.context.put("threadName", threadName);
            return this;
        }
        
        public Builder success(boolean success) {
            this.context.put("success", success);
            return this;
        }
        
        public Builder error(String error) {
            this.context.put("error", error);
            return this;
        }
        
        public Builder error(Throwable error) {
            this.context.put("error", error.getMessage());
            this.context.put("errorType", error.getClass().getSimpleName());
            return this;
        }
        
        public CrossComponentEvent build() {
            if (component == null) {
                throw new IllegalStateException("Component is required");
            }
            if (eventType == null) {
                throw new IllegalStateException("EventType is required");
            }
            return new CrossComponentEvent(component, eventType, timestamp, durationNs, context);
        }
    }
}