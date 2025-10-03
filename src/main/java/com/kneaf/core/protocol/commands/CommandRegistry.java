package com.kneaf.core.protocol.commands;

import com.kneaf.core.protocol.core.CommandExecutor;
import com.kneaf.core.protocol.core.ProtocolConstants;
import com.kneaf.core.protocol.core.ProtocolException;
import com.kneaf.core.protocol.core.ProtocolLogger;
import com.kneaf.core.protocol.core.ProtocolUtils;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing command registration and execution with standardized patterns.
 */
public class CommandRegistry {
    
    private final Map<String, RegisteredCommand> commands = new ConcurrentHashMap<>();
    private final ProtocolLogger logger;
    private final String registryName;
    
    /**
     * Create a new command registry.
     * 
     * @param registryName the name of this registry
     * @param logger the protocol logger
     */
    public CommandRegistry(String registryName, ProtocolLogger logger) {
        this.registryName = registryName;
        this.logger = logger;
    }
    
    /**
     * Register a command with the registry.
     * 
     * @param command the command to register
     */
    public void registerCommand(CommandExecutor command) {
        String commandName = command.getCommandName();
        if (commands.containsKey(commandName)) {
            throw new IllegalArgumentException("Command '" + commandName + "' is already registered");
        }
        
        RegisteredCommand registeredCommand = new RegisteredCommand(command);
        commands.put(commandName, registeredCommand);
        
        String traceId = ProtocolUtils.generateTraceId();
        logger.logOperationStart("register_command", "registry", traceId, 
            Map.of("command", commandName, "registry", registryName));
    }
    
    /**
     * Register all commands with a command dispatcher.
     * 
     * @param dispatcher the command dispatcher
     */
    public void registerWithDispatcher(CommandDispatcher<CommandSourceStack> dispatcher) {
        String traceId = ProtocolUtils.generateTraceId();
        logger.logOperationStart("register_dispatcher", "registry", traceId,
            Map.of("registry", registryName, "command_count", commands.size()));
        
        try {
            for (RegisteredCommand registeredCommand : commands.values()) {
                CommandExecutor command = registeredCommand.getCommand();
                String commandName = command.getCommandName();
                
                LiteralArgumentBuilder<CommandSourceStack> builder = Commands.literal(commandName)
                    .requires(source -> source.hasPermission(command.getRequiredPermissionLevel()))
                    .executes(context -> executeCommand(command, context));
                
                dispatcher.register(builder);
                
                logger.logOperationComplete("register_command", "registry", traceId, 0, true,
                    Map.of("command", commandName));
            }
            
            logger.logOperationComplete("register_dispatcher", "registry", traceId, 0, true,
                Map.of("registered_commands", commands.size()));
        } catch (Exception e) {
            logger.logError("register_dispatcher", e, traceId,
                Map.of("registry", registryName));
            throw new RuntimeException("Failed to register commands with dispatcher", e);
        }
    }
    
    /**
     * Execute a command with standardized error handling and logging.
     * 
     * @param command the command to execute
     * @param context the command context
     * @return command result
     */
    private int executeCommand(CommandExecutor command, CommandContext<CommandSourceStack> context) {
        String traceId = ProtocolUtils.generateTraceId();
        String commandName = command.getCommandName();
        long startTime = System.currentTimeMillis();
        
        try {
            // Log operation start
            logger.logOperationStart("execute_command", "command", traceId,
                Map.of("command", commandName, "executor", registryName));
            
            // Validate arguments
            if (!command.validateArguments(context)) {
                String errorMsg = "Invalid arguments for command: " + commandName;
                logger.logWarning("execute_command", errorMsg, traceId,
                    Map.of("command", commandName));
                return command.handleError(context, new IllegalArgumentException(errorMsg));
            }
            
            // Execute command
            int result = command.execute(context);
            long duration = System.currentTimeMillis() - startTime;
            
            // Log operation completion
            logger.logOperationComplete("execute_command", "command", traceId, duration, result > 0,
                Map.of("command", commandName, "result", result));
            
            return result;
            
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            
            // Log error
            logger.logError("execute_command", e, traceId,
                Map.of("command", commandName, "duration", duration));
            
            // Handle error through command's error handler
            return command.handleError(context, e);
        }
    }
    
    /**
     * Get a registered command by name.
     * 
     * @param commandName the command name
     * @return the registered command or null if not found
     */
    public CommandExecutor getCommand(String commandName) {
        RegisteredCommand registered = commands.get(commandName);
        return registered != null ? registered.getCommand() : null;
    }
    
    /**
     * Check if a command is registered.
     * 
     * @param commandName the command name
     * @return true if registered
     */
    public boolean isCommandRegistered(String commandName) {
        return commands.containsKey(commandName);
    }
    
    /**
     * Get all registered command names.
     * 
     * @return array of command names
     */
    public String[] getRegisteredCommandNames() {
        return commands.keySet().toArray(new String[0]);
    }
    
    /**
     * Get command statistics.
     * 
     * @return map of command statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("registry_name", registryName);
        stats.put("total_commands", commands.size());
        
        Map<String, Object> commandStats = new HashMap<>();
        for (Map.Entry<String, RegisteredCommand> entry : commands.entrySet()) {
            commandStats.put(entry.getKey(), entry.getValue().getStatistics());
        }
        stats.put("commands", commandStats);
        
        return stats;
    }
    
    /**
     * Clear all registered commands.
     */
    public void clear() {
        String traceId = ProtocolUtils.generateTraceId();
        logger.logOperationStart("clear_registry", "registry", traceId,
            Map.of("registry", registryName, "commands_cleared", commands.size()));
        
        commands.clear();
        
        logger.logOperationComplete("clear_registry", "registry", traceId, 0, true,
            Map.of("registry", registryName));
    }
    
    /**
     * Inner class to track registered command information and statistics.
     */
    private static class RegisteredCommand {
        private final CommandExecutor command;
        private long executionCount = 0;
        private long totalExecutionTime = 0;
        private long lastExecutionTime = 0;
        private long successCount = 0;
        private long errorCount = 0;
        
        public RegisteredCommand(CommandExecutor command) {
            this.command = command;
        }
        
        public CommandExecutor getCommand() {
            return command;
        }
        
        public synchronized void recordExecution(long duration, boolean success) {
            executionCount++;
            totalExecutionTime += duration;
            lastExecutionTime = System.currentTimeMillis();
            if (success) {
                successCount++;
            } else {
                errorCount++;
            }
        }
        
        public synchronized Map<String, Object> getStatistics() {
            return Map.of(
                "execution_count", executionCount,
                "success_count", successCount,
                "error_count", errorCount,
                "total_execution_time", totalExecutionTime,
                "average_execution_time", executionCount > 0 ? totalExecutionTime / executionCount : 0,
                "last_execution_time", lastExecutionTime,
                "success_rate", executionCount > 0 ? (double) successCount / executionCount : 0.0
            );
        }
    }
}