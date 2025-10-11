package com.kneaf.core.unifiedbridge;

/**
 * Represents the result of a task execution.
 */
public class TaskResult {
    private final long taskId;
    private final boolean success;
    private final Object result;
    private final String errorMessage;
    private final long executionTimeMs;

    public TaskResult(long taskId, boolean success, Object result, String errorMessage, long executionTimeMs) {
        this.taskId = taskId;
        this.success = success;
        this.result = result;
        this.errorMessage = errorMessage;
        this.executionTimeMs = executionTimeMs;
    }

    public TaskResult(long taskId, Object result, long executionTimeMs) {
        this(taskId, true, result, null, executionTimeMs);
    }

    public static TaskResult success(long taskId, Object result, long executionTimeMs) {
        return new TaskResult(taskId, true, result, null, executionTimeMs);
    }

    public static TaskResult failure(long taskId, String errorMessage, long executionTimeMs) {
        return new TaskResult(taskId, false, null, errorMessage, executionTimeMs);
    }

    public long getTaskId() {
        return taskId;
    }

    public boolean isSuccess() {
        return success;
    }

    public Object getResult() {
        return result;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public long getTaskHandle() {
        return taskId;
    }
}