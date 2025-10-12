package com.kneaf.core.command.unified;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unified logging for all command operations with consistent formatting and structure.
 * Thread-safe and provides detailed command execution logging.
 */
public class CommandLogger {

    private final AtomicLong commandExecutionCount = new AtomicLong(0);
    private final AtomicLong commandErrorCount = new AtomicLong(0);
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(CommandLogger.class);

    /**
     * Log command registration.
     *
     * @param commandName the command name
     */
    public void logCommandRegistered(String commandName) {
        logInfo("Command registered: " + commandName);
    }

    /**
     * Log command unregistration.
     *
     * @param commandName the command name
     */
    public void logCommandUnregistered(String commandName) {
        logInfo("Command unregistered: " + commandName);
    }

    /**
     * Log command execution.
     *
     * @param commandName the command name
     * @param success whether execution was successful
     * @param executionTime execution time in milliseconds
     */
    public void logCommandExecution(String commandName, boolean success, long executionTime) {
        commandExecutionCount.incrementAndGet();
        if (success) {
            logInfo(String.format("Command executed successfully: %s (time: %dms)", commandName, executionTime));
        } else {
            commandErrorCount.incrementAndGet();
            logWarn(String.format("Command execution failed: %s (time: %dms)", commandName, executionTime));
        }
    }

    /**
     * Log command error.
     *
     * @param commandName the command name
     * @param error the error that occurred
     */
    public void logCommandError(String commandName, Exception error) {
        commandErrorCount.incrementAndGet();
        logError(String.format("Command error: %s - %s", commandName, error.getMessage()), error);
    }

    /**
     * Log command permission check.
     *
     * @param commandName the command name
     * @param granted whether permission was granted
     */
    public void logCommandPermissionCheck(String commandName, boolean granted) {
        if (granted) {
            logInfo(String.format("Permission granted for command: %s", commandName));
        } else {
            logWarn(String.format("Permission denied for command: %s", commandName));
        }
    }

    /**
     * Log command argument validation.
     *
     * @param commandName the command name
     * @param valid whether arguments are valid
     */
    public void logCommandArgumentValidation(String commandName, boolean valid) {
        if (valid) {
            logInfo(String.format("Command arguments validated: %s", commandName));
        } else {
            logWarn(String.format("Command arguments invalid: %s", commandName));
        }
    }

    /**
     * Log general information.
     *
     * @param message the message to log
     */
    protected void logInfo(String message) {
        LOGGER.info(message);
    }

    /**
     * Log a warning.
     *
     * @param message the message to log
     */
    protected void logWarn(String message) {
        LOGGER.warn(message);
    }

    /**
     * Log an error.
     *
     * @param message the message to log
     * @param error the error
     */
    protected void logError(String message, Exception error) {
        LOGGER.error(message, error);
    }

    /**
     * Get command logging statistics.
     *
     * @return map containing logging statistics
     */
    public Map<String, Object> getStatistics() {
        return Map.of(
                "total_commands_executed", commandExecutionCount.get(),
                "total_command_errors", commandErrorCount.get(),
                "success_rate", commandExecutionCount.get() > 0 
                        ? (double) (commandExecutionCount.get() - commandErrorCount.get()) / commandExecutionCount.get()
                        : 0.0
        );
    }

    /**
     * Reset statistics counters.
     */
    public void resetStatistics() {
        commandExecutionCount.set(0);
        commandErrorCount.set(0);
    }
}