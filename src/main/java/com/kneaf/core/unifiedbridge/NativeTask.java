package com.kneaf.core.unifiedbridge;

/**
 * Represents a native task that can be executed by workers.
 */
public class NativeTask {
    private final String taskName;
    private final Object[] parameters;
    private final long taskId;
    private final long timestamp;

    public NativeTask(String taskName, Object... parameters) {
        this.taskName = taskName;
        this.parameters = parameters;
        this.taskId = System.nanoTime();
        this.timestamp = System.currentTimeMillis();
    }

    public NativeTask(long taskId, String taskName, Object... parameters) {
        this.taskId = taskId;
        this.taskName = taskName;
        this.parameters = parameters;
        this.timestamp = System.currentTimeMillis();
    }

    public String getTaskName() {
        return taskName;
    }

    public Object[] getParameters() {
        return parameters;
    }

    public long getTaskId() {
        return taskId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getOperationName() {
        return taskName;
    }

    public BridgeResult execute(UnifiedBridge bridge) {
        try {
            return bridge.executeSync(taskName, parameters);
        } catch (BridgeException e) {
            return BridgeResultFactory.createFailure(taskName, e);
        }
    }
}