package com.kneaf.core.command.unified;

import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Streamlined command execution with validation and error handling.
 * Provides a simplified interface for command execution while maintaining all system capabilities.
 */
public class CommandExecutor {

    private final UnifiedCommandSystem commandSystem;
    private final ExecutorService executorService;
    private final boolean asyncByDefault;

    /**
     * Create a new CommandExecutor with default settings.
     *
     * @param commandSystem the unified command system
     */
    public CommandExecutor(UnifiedCommandSystem commandSystem) {
        this(commandSystem, true, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Create a new CommandExecutor with custom settings.
     *
     * @param commandSystem the unified command system
     * @param asyncByDefault whether to execute commands asynchronously by default
     * @param threadPoolSize thread pool size for async execution
     */
    public CommandExecutor(UnifiedCommandSystem commandSystem, boolean asyncByDefault, int threadPoolSize) {
        this.commandSystem = commandSystem;
        this.asyncByDefault = asyncByDefault;
        this.executorService = Executors.newFixedThreadPool(threadPoolSize);
    }

    /**
     * Execute a command with default execution mode.
     *
     * @param commandName the command name
     * @param context the command context
     * @return the command result (CompletableFuture for async, immediate for sync)
     */
    public CompletableFuture<CommandResult> execute(String commandName, CommandContext context) {
        return execute(commandName, context, asyncByDefault);
    }

    /**
     * Execute a command with specified execution mode.
     *
     * @param commandName the command name
     * @param context the command context
     * @param async whether to execute asynchronously
     * @return the command result future
     */
    public CompletableFuture<CommandResult> execute(String commandName, CommandContext context, boolean async) {
        if (async) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return commandSystem.executeCommand(commandName, context, false);
                } catch (Exception e) {
                    return CommandResult.failure(
                        1,
                        List.of("Command execution failed: " + e.getMessage())
                    );
                }
            }, executorService);
        } else {
            try {
                return CompletableFuture.completedFuture(
                    commandSystem.executeCommand(commandName, context, true)
                );
            } catch (Exception e) {
                return CompletableFuture.completedFuture(
                    CommandResult.failure(1, List.of("Command execution failed: " + e.getMessage()))
                );
            }
        }
    }

    /**
     * Execute a command with validation only (no actual execution).
     *
     * @param commandName the command name
     * @param context the command context
     * @return true if command would execute successfully (validation passes)
     */
    public boolean validateOnly(String commandName, CommandContext context) {
        ExecutableCommand command = commandSystem.getCommandRegistry().getCommand(commandName);
        
        if (command == null) {
            return false;
        }

        // Check permissions
        if (!commandSystem.getPermissionManager().hasPermission(context, command)) {
            return false;
        }

        // Validate arguments
        return command.validateArguments(context);
    }

    /**
     * Get execution statistics.
     *
     * @return map containing execution statistics
     */
    public Map<String, Object> getExecutionStatistics() {
        return Map.of(
                "async_by_default", asyncByDefault,
                "thread_pool_size", executorService.toString(),
                "command_system_stats", commandSystem.getSystemStatistics()
        );
    }

    /**
     * Shutdown the command executor gracefully.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return true if all tasks completed before the timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean shutdown(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
        executorService.shutdown();
        return executorService.awaitTermination(timeout, unit);
    }
}