package com.kneaf.core.protocol.commands;

import com.kneaf.core.protocol.core.CommandExecutor;
import com.kneaf.core.protocol.core.ProtocolConstants;
import com.kneaf.core.protocol.core.ProtocolException;
import com.kneaf.core.protocol.core.ProtocolLogger;
import com.kneaf.core.protocol.core.ProtocolUtils;
import com.kneaf.core.protocol.utils.ProtocolLoggerImpl;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.Map;

/**
 * Base class for standardized command implementations with consistent error handling and logging.
 */
public abstract class BaseCommand implements CommandExecutor {
    
    protected final ProtocolLogger logger;
    private final String commandName;
    private final String description;
    private final int requiredPermissionLevel;
    private final String usage;
    
    /**
     * Create a new base command.
     * 
     * @param commandName the command name
     * @param description the command description
     * @param requiredPermissionLevel required permission level
     * @param usage the usage string
     */
    protected BaseCommand(String commandName, String description, int requiredPermissionLevel, String usage) {
        this.commandName = commandName;
        this.description = description;
        this.requiredPermissionLevel = requiredPermissionLevel;
        this.usage = usage;
        this.logger = new ProtocolLoggerImpl("Command:" + commandName);
    }
    
    /**
     * Create a new base command with custom logger.
     * 
     * @param commandName the command name
     * @param description the command description
     * @param requiredPermissionLevel required permission level
     * @param usage the usage string
     * @param logger the protocol logger
     */
    protected BaseCommand(String commandName, String description, int requiredPermissionLevel, String usage, ProtocolLogger logger) {
        this.commandName = commandName;
        this.description = description;
        this.requiredPermissionLevel = requiredPermissionLevel;
        this.usage = usage;
        this.logger = logger;
    }
    
    @Override
    public final int execute(CommandContext<CommandSourceStack> context) {
        String traceId = logger.generateTraceId();
        String sourceName = context.getSource().getTextName();
        long startTime = System.currentTimeMillis();
        
        try {
            // Log command execution start
            logger.logOperationStart("command_execute", "command", traceId,
                Map.of("command", commandName, "source", sourceName));
            
            // Validate arguments
            if (!validateArguments(context)) {
                String errorMsg = "Invalid arguments for command: " + commandName;
                logger.logWarning("command_execute", errorMsg, traceId,
                    Map.of("command", commandName, "source", sourceName));
                return handleError(context, new IllegalArgumentException(errorMsg));
            }
            
            // Execute command logic
            int result = executeCommand(context);
            long duration = System.currentTimeMillis() - startTime;
            
            // Log successful completion
            logger.logOperationComplete("command_execute", "command", traceId, duration, result > 0,
                Map.of("command", commandName, "result", result, "source", sourceName));
            
            return result;
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            
            // Log error
            logger.logError("command_execute", e, traceId,
                Map.of("command", commandName, "duration", duration, "source", sourceName));
            
            // Handle error
            return handleError(context, e);
        }
    }
    
    /**
     * Execute the actual command logic. Subclasses must implement this method.
     * 
     * @param context the command context
     * @return command result (typically 1 for success, 0 for failure)
     * @throws Exception if command execution fails
     */
    protected abstract int executeCommand(CommandContext<CommandSourceStack> context) throws Exception;
    
    @Override
    public boolean validateArguments(CommandContext<CommandSourceStack> context) {
        // Default implementation - subclasses can override for specific validation
        return true;
    }
    
    @Override
    public int handleError(CommandContext<CommandSourceStack> context, Exception error) {
        CommandSourceStack source = context.getSource();
        
        String errorMessage;
        if (error instanceof ProtocolException) {
            ProtocolException protocolError = (ProtocolException) error;
            errorMessage = String.format("§cCommand '%s' failed: %s (Error Code: %d)", 
                commandName, protocolError.getMessage(), protocolError.getErrorCode());
        } else if (error instanceof IllegalArgumentException) {
            errorMessage = String.format("§cInvalid arguments for command '%s': %s", 
                commandName, error.getMessage());
        } else {
            errorMessage = String.format("§cCommand '%s' failed: %s", 
                commandName, error.getMessage());
        }
        
        source.sendFailure(Component.literal(errorMessage));
        return 0; // Return failure code
    }
    
    @Override
    public String getCommandName() {
        return commandName;
    }
    
    @Override
    public String getDescription() {
        return description;
    }
    
    @Override
    public int getRequiredPermissionLevel() {
        return requiredPermissionLevel;
    }
    
    @Override
    public String getUsage() {
        return usage;
    }
    
    /**
     * Send success message to command source.
     * 
     * @param context the command context
     * @param message the success message
     */
    protected void sendSuccess(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal(message), false);
    }
    
    /**
     * Send failure message to command source.
     * 
     * @param context the command context
     * @param message the failure message
     */
    protected void sendFailure(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendFailure(Component.literal(message));
    }
    
    /**
     * Send formatted success message to command source.
     * 
     * @param context the command context
     * @param format the message format string
     * @param args the format arguments
     */
    protected void sendSuccessFormatted(CommandContext<CommandSourceStack> context, String format, Object... args) {
        String message = String.format(format, args);
        sendSuccess(context, message);
    }
    
    /**
     * Send formatted failure message to command source.
     * 
     * @param context the command context
     * @param format the message format string
     * @param args the format arguments
     */
    protected void sendFailureFormatted(CommandContext<CommandSourceStack> context, String format, Object... args) {
        String message = String.format(format, args);
        sendFailure(context, message);
    }
    
    /**
     * Get the protocol logger for this command.
     * 
     * @return the logger
     */
    protected ProtocolLogger getLogger() {
        return logger;
    }
    
    /**
     * Create a standardized help message for the command.
     * 
     * @return help message
     */
    protected String createHelpMessage() {
        return String.format("§6=== %s Command Help ===\n§bUsage: §f%s\n§bDescription: §f%s\n§bPermission Level: §f%d",
            commandName, usage, description, requiredPermissionLevel);
    }
}