package com.kneaf.core.protocol.core;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;

/**
 * Base interface for command executors with standardized execution pattern.
 */
public interface CommandExecutor {
    
    /**
     * Execute the command with the given context.
     * 
     * @param context the command context
     * @return command result (typically 1 for success, 0 for failure)
     */
    int execute(CommandContext<CommandSourceStack> context);
    
    /**
     * Get the name of this command.
     * 
     * @return command name
     */
    String getCommandName();
    
    /**
     * Get the description of this command.
     * 
     * @return command description
     */
    String getDescription();
    
    /**
     * Check if the executor requires specific permissions.
     * 
     * @return required permission level
     */
    int getRequiredPermissionLevel();
    
    /**
     * Get the usage string for this command.
     * 
     * @return usage string
     */
    String getUsage();
    
    /**
     * Validate command arguments before execution.
     * 
     * @param context the command context
     * @return true if arguments are valid
     */
    boolean validateArguments(CommandContext<CommandSourceStack> context);
    
    /**
     * Handle command execution errors in a standardized way.
     * 
     * @param context the command context
     * @param error the error that occurred
     * @return error result code
     */
    int handleError(CommandContext<CommandSourceStack> context, Exception error);
}