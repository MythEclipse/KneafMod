package com.kneaf.core.command.unified;

import java.util.List;

/**
 * Interface for commands that can be executed through the unified command system.
 * Extends the base UnifiedCommand interface with execution capabilities.
 */
public interface ExecutableCommand extends UnifiedCommand {

    /**
     * Execute the command with the given context.
     *
     * @param context the command execution context
     * @return the command result
     * @throws Exception if command execution fails
     */
    CommandResult execute(CommandContext context) throws Exception;

    /**
     * Validate command arguments before execution.
     *
     * @param context the command execution context
     * @return true if arguments are valid, false otherwise
     */
    default boolean validateArguments(CommandContext context) {
        return true;
    }

    /**
     * Handle command execution errors.
     *
     * @param context the command execution context
     * @param error the exception that occurred
     * @return the error result
     */
    default CommandResult handleError(CommandContext context, Exception error) {
        return CommandResult.failure(
                1,
                List.of("Command failed: " + error.getMessage())
        );
    }
}