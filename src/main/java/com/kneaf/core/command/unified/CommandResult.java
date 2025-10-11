package com.kneaf.core.command.unified;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Immutable result object for command execution containing success status and messages.
 * Thread-safe and suitable for concurrent use.
 */
public final class CommandResult {

    private final boolean success;
    private final int returnCode;
    private final List<String> messages;
    private final Map<String, Object> data;
    private final long executionTime;

    private CommandResult(Builder builder) {
        this.success = builder.success;
        this.returnCode = builder.returnCode;
        this.messages = Collections.unmodifiableList(builder.messages);
        this.data = Collections.unmodifiableMap(builder.data);
        this.executionTime = builder.executionTime;
    }

    /**
     * Check if command execution was successful.
     *
     * @return true if successful
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Get the command return code.
     *
     * @return return code (typically 0 for success, non-0 for failure)
     */
    public int getReturnCode() {
        return returnCode;
    }

    /**
     * Get the result messages.
     *
     * @return immutable list of messages
     */
    public List<String> getMessages() {
        return messages;
    }

    /**
     * Get additional result data.
     *
     * @return immutable map of result data
     */
    public Map<String, Object> getData() {
        return data;
    }

    /**
     * Get the execution time in milliseconds.
     *
     * @return execution time
     */
    public long getExecutionTime() {
        return executionTime;
    }

    /**
     * Create a successful command result.
     *
     * @param returnCode success return code
     * @param messages result messages
     * @return CommandResult instance
     */
    public static CommandResult success(int returnCode, List<String> messages) {
        return new Builder()
                .success(true)
                .returnCode(returnCode)
                .messages(messages)
                .build();
    }

    /**
     * Create a failed command result.
     *
     * @param returnCode failure return code
     * @param messages error messages
     * @return CommandResult instance
     */
    public static CommandResult failure(int returnCode, List<String> messages) {
        return new Builder()
                .success(false)
                .returnCode(returnCode)
                .messages(messages)
                .build();
    }

    /**
     * Create a builder for CommandResult.
     *
     * @return new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for CommandResult to support fluent construction.
     */
    public static class Builder {
        private boolean success = false;
        private int returnCode = 0;
        private List<String> messages = List.of();
        private Map<String, Object> data = Map.of();
        private long executionTime = System.currentTimeMillis();

        /**
         * Set success status.
         *
         * @param success success status
         * @return builder instance
         */
        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        /**
         * Set return code.
         *
         * @param returnCode return code
         * @return builder instance
         */
        public Builder returnCode(int returnCode) {
            this.returnCode = returnCode;
            return this;
        }

        /**
         * Set result messages.
         *
         * @param messages list of messages
         * @return builder instance
         */
        public Builder messages(List<String> messages) {
            this.messages = List.copyOf(messages);
            return this;
        }

        /**
         * Add a single message.
         *
         * @param message message to add
         * @return builder instance
         */
        public Builder addMessage(String message) {
            this.messages = List.copyOf(Stream.concat(this.messages.stream(), Stream.of(message)).toList());
            return this;
        }

        /**
         * Set additional result data.
         *
         * @param data map of data
         * @return builder instance
         */
        public Builder data(Map<String, Object> data) {
            this.data = Map.copyOf(data);
            return this;
        }

        /**
         * Add a single data entry.
         *
         * @param key data key
         * @param value data value
         * @return builder instance
         */
        public Builder addData(String key, Object value) {
            Map<String, Object> newData = new HashMap<>(this.data);
            newData.put(key, value);
            this.data = Map.copyOf(newData);
            return this;
        }

        /**
         * Set execution time.
         *
         * @param executionTime execution time in milliseconds
         * @return builder instance
         */
        public Builder executionTime(long executionTime) {
            this.executionTime = executionTime;
            return this;
        }

        /**
         * Build the CommandResult.
         *
         * @return new CommandResult instance
         */
        public CommandResult build() {
            return new CommandResult(this);
        }
    }
}