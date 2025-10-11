package com.kneaf.core.command.unified;

import java.util.Map;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Main coordinator for the unified command system.
 * Manages command registration, execution, and provides system-wide command operations.
 */
public class UnifiedCommandSystem {

    private final CommandRegistry commandRegistry;
    private final CommandPermissionManager permissionManager;
    private final CommandLogger commandLogger;
    private final ExecutorService executionService;
    private final boolean asyncExecutionEnabled;

    /**
     * Create a new UnifiedCommandSystem with default settings.
     */
    public UnifiedCommandSystem() {
        this(true, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Create a new UnifiedCommandSystem with custom settings.
     *
     * @param asyncExecutionEnabled whether to enable async command execution
     * @param threadPoolSize thread pool size for async execution
     */
    public UnifiedCommandSystem(boolean asyncExecutionEnabled, int threadPoolSize) {
        this.commandRegistry = new CommandRegistry();
        this.permissionManager = new CommandPermissionManager();
        this.commandLogger = new CommandLogger();
        this.asyncExecutionEnabled = asyncExecutionEnabled;
        
        this.executionService = asyncExecutionEnabled 
                ? Executors.newFixedThreadPool(threadPoolSize)
                : Executors.newSingleThreadExecutor();
    }

    /**
     * Register a command with the system.
     *
     * @param command the command to register
     */
    public void registerCommand(ExecutableCommand command) {
        commandRegistry.registerCommand(command);
        commandLogger.logCommandRegistered(command.getName());
    }

    /**
     * Unregister a command from the system.
     *
     * @param commandName the name of the command to unregister
     * @return true if command was found and unregistered
     */
    public boolean unregisterCommand(String commandName) {
        boolean result = commandRegistry.unregisterCommand(commandName);
        if (result) {
            commandLogger.logCommandUnregistered(commandName);
        }
        return result;
    }

    /**
     * Execute a command synchronously.
     *
     * @param commandName the name of the command to execute
     * @param context the command execution context
     * @return the command result
     * @throws Exception if command execution fails
     */
    public CommandResult executeCommand(String commandName, CommandContext context) throws Exception {
        return executeCommand(commandName, context, false);
    }

    /**
     * Execute a command with specified execution mode.
     *
     * @param commandName the name of the command to execute
     * @param context the command execution context
     * @param forceSync whether to force synchronous execution
     * @return the command result (immediate for sync, completed future for async)
     * @throws Exception if command execution fails
     */
    public CommandResult executeCommand(String commandName, CommandContext context, boolean forceSync) throws Exception {
        ExecutableCommand command = commandRegistry.getCommand(commandName);
        
        if (command == null) {
            throw new IllegalArgumentException("Command '" + commandName + "' is not registered");
        }

        // Check permissions first
        if (!permissionManager.hasPermission(context, command)) {
            return CommandResult.failure(
                1,
                List.of("Permission denied for command: " + commandName)
            );
        }

        // Validate arguments
        if (!command.validateArguments(context)) {
            return CommandResult.failure(
                1,
                List.of("Invalid arguments for command: " + commandName)
            );
        }

        try {
            long startTime = System.currentTimeMillis();
            CommandResult result;

            if (asyncExecutionEnabled && !forceSync) {
                // For async execution, we'd return a CompletableFuture here
                // For simplicity in this example, we'll execute synchronously
                result = command.execute(context);
            } else {
                result = command.execute(context);
            }

            long executionTime = System.currentTimeMillis() - startTime;
            commandLogger.logCommandExecution(commandName, result.isSuccess(), executionTime);
            
            return result;

        } catch (Exception e) {
            commandLogger.logCommandError(commandName, e);
            return command.handleError(context, e);
        }
    }

    /**
     * Get the command registry.
     *
     * @return the command registry
     */
    public CommandRegistry getCommandRegistry() {
        return commandRegistry;
    }

    /**
     * Get the permission manager.
     *
     * @return the permission manager
     */
    public CommandPermissionManager getPermissionManager() {
        return permissionManager;
    }

    /**
     * Get the command logger.
     *
     * @return the command logger
     */
    public CommandLogger getCommandLogger() {
        return commandLogger;
    }

    /**
     * Shutdown the command system gracefully.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return true if all tasks completed before the timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean shutdown(long timeout, TimeUnit unit) throws InterruptedException {
        executionService.shutdown();
        return executionService.awaitTermination(timeout, unit);
    }

    /**
     * Get system statistics.
     *
     * @return map containing system statistics
     */
    public Map<String, Object> getSystemStatistics() {
        return Map.of(
                "async_execution_enabled", asyncExecutionEnabled,
                "thread_pool_size", executionService.toString(),
                "command_registry", commandRegistry.getStatistics(),
                "permission_manager", permissionManager.getStatistics(),
                "command_logger", commandLogger.getStatistics()
        );
    }
}