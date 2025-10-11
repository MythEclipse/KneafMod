package com.kneaf.core.command.unified;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable execution context containing all necessary information for command execution.
 * Provides thread-safe access to command execution state.
 */
public class CommandContext {

    private final CommandSourceStack source;
    private final Map<String, Object> arguments;
    private final ServerPlayer player;
    private final Object level;
    private final long executionTimestamp;
    private final String traceId;

    /**
     * Create a new CommandContext.
     *
     * @param source the command source stack
     * @param arguments parsed command arguments
     * @param player optional player executing the command
     * @param level optional server level where command is executed
     * @param traceId unique trace ID for logging
     */
    public CommandContext(CommandSourceStack source, Map<String, Object> arguments,
                         Optional<ServerPlayer> player, Optional<Object> level,
                         String traceId) {
        this.source = source;
        this.arguments = Map.copyOf(arguments);
        this.player = player.orElse(null);
        this.level = level.orElse(null);
        this.executionTimestamp = System.currentTimeMillis();
        this.traceId = traceId != null ? traceId : java.util.UUID.randomUUID().toString();
    }

    /**
     * Get the command source stack.
     *
     * @return command source stack
     */
    public CommandSourceStack getSource() {
        return source;
    }

    /**
     * Get parsed command arguments.
     *
     * @return immutable map of arguments
     */
    public Map<String, Object> getArguments() {
        return arguments;
    }

    /**
     * Get the player executing the command (if available).
     *
     * @return optional player
     */
    public Optional<ServerPlayer> getPlayer() {
        return Optional.ofNullable(player);
    }

    /**
     * Get the server level where command is executed (if available).
     *
     * @return optional server level
     */
    public Optional<Object> getLevel() {
        return Optional.ofNullable(level);
    }

    /**
     * Get the execution timestamp.
     *
     * @return timestamp in milliseconds
     */
    public long getExecutionTimestamp() {
        return executionTimestamp;
    }

    /**
     * Get the unique trace ID for logging and tracing.
     *
     * @return trace ID
     */
    public String getTraceId() {
        return traceId;
    }

    /**
     * Create a builder for CommandContext.
     *
     * @return new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for CommandContext to support fluent construction.
     */
    public static class Builder {
        private CommandSourceStack source;
        private Map<String, Object> arguments = Map.of();
        private Optional<ServerPlayer> player = Optional.empty();
        private Optional<Object> level = Optional.empty();
        private String traceId;

        /**
         * Set the command source stack.
         *
         * @param source command source stack
         * @return builder instance
         */
        public Builder source(CommandSourceStack source) {
            this.source = source;
            return this;
        }

        /**
         * Set command arguments.
         *
         * @param arguments parsed arguments
         * @return builder instance
         */
        public Builder arguments(Map<String, Object> arguments) {
            this.arguments = Map.copyOf(arguments);
            return this;
        }

        /**
         * Set the player executing the command.
         *
         * @param player server player
         * @return builder instance
         */
        public Builder player(ServerPlayer player) {
            this.player = Optional.ofNullable(player);
            return this;
        }

        /**
         * Set the server level.
         *
         * @param level server level
         * @return builder instance
         */
        public Builder level(Object level) {
            this.level = Optional.ofNullable(level);
            return this;
        }

        /**
         * Set the trace ID.
         *
         * @param traceId unique trace ID
         * @return builder instance
         */
        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        /**
         * Build the CommandContext.
         *
         * @return new CommandContext instance
         * @throws IllegalStateException if required fields are missing
         */
        public CommandContext build() {
            if (source == null) {
                throw new IllegalStateException("CommandSourceStack is required");
            }
            return new CommandContext(source, arguments, player, level, traceId);
        }
    }
}