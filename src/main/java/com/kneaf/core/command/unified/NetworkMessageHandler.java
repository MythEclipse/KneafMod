package com.kneaf.core.command.unified;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consolidated network message processing for command-related network traffic.
 * Handles incoming command messages and routes them to the appropriate command system components.
 */
public class NetworkMessageHandler {

    private final UnifiedCommandSystem commandSystem;
    private final Map<String, MessageHandler> messageHandlers = new ConcurrentHashMap<>();

    /**
     * Create a new NetworkMessageHandler.
     *
     * @param commandSystem the unified command system
     */
    public NetworkMessageHandler(UnifiedCommandSystem commandSystem) {
        this.commandSystem = commandSystem;
        initializeDefaultHandlers();
    }

    /**
     * Initialize default message handlers.
     */
    private void initializeDefaultHandlers() {
        registerMessageHandler("COMMAND_EXECUTE", this::handleCommandExecute);
        registerMessageHandler("COMMAND_REGISTER", this::handleCommandRegister);
        registerMessageHandler("COMMAND_UNREGISTER", this::handleCommandUnregister);
        registerMessageHandler("COMMAND_STATUS", this::handleCommandStatus);
    }

    /**
     * Register a custom message handler.
     *
     * @param messageType the message type to handle
     * @param handler the handler function
     */
    public void registerMessageHandler(String messageType, MessageHandler handler) {
        messageHandlers.put(messageType, handler);
    }

    /**
     * Process an incoming network message.
     *
     * @param message the incoming message
     * @return the processing result
     */
    public CommandResult processMessage(NetworkMessage message) {
        String messageType = message.getType();
        MessageHandler handler = messageHandlers.get(messageType);
        
        if (handler == null) {
            return CommandResult.failure(
                1,
                List.of("No handler registered for message type: " + messageType)
            );
        }

        try {
            return handler.handle(message);
        } catch (Exception e) {
            return CommandResult.failure(
                1,
                List.of("Failed to process message: " + e.getMessage())
            );
        }
    }

    /**
     * Handle command execution messages.
     *
     * @param message the command execution message
     * @return the command result
     * @throws Exception if command execution fails
     */
    private CommandResult handleCommandExecute(NetworkMessage message) throws Exception {
        String commandName = message.getData().get("command").toString();
        Map<String, Object> arguments = (Map<String, Object>) message.getData().get("arguments");
        
        // Create command context from message data
        // For now, create a simple context without source
        CommandContext context = CommandContext.builder()
                .arguments(arguments)
                .traceId(message.getTraceId())
                .build();

        return commandSystem.executeCommand(commandName, context);
    }

    /**
     * Handle command registration messages.
     *
     * @param message the command registration message
     * @return the command result
     */
    private CommandResult handleCommandRegister(NetworkMessage message) {
        // In a real implementation, this would deserialize the command from the message
        // For this example, we'll just log the registration attempt
        String commandName = message.getData().get("commandName").toString();
        commandSystem.getCommandLogger().logInfo("Received command registration request for: " + commandName);
        
        return CommandResult.success(
            0,
            List.of("Command registration requested: " + commandName)
        );
    }

    /**
     * Handle command unregistration messages.
     *
     * @param message the command unregistration message
     * @return the command result
     */
    private CommandResult handleCommandUnregister(NetworkMessage message) {
        String commandName = message.getData().get("commandName").toString();
        boolean result = commandSystem.unregisterCommand(commandName);
        
        if (result) {
            return CommandResult.success(
                0,
                List.of("Command unregistered: " + commandName)
            );
        } else {
            return CommandResult.failure(
                1,
                List.of("Command not found: " + commandName)
            );
        }
    }

    /**
     * Handle command status messages.
     *
     * @param message the command status message
     * @return the command result
     */
    private CommandResult handleCommandStatus(NetworkMessage message) {
        String commandName = message.getData().get("commandName").toString();
        ExecutableCommand command = commandSystem.getCommandRegistry().getCommand(commandName);
        
        if (command == null) {
            return CommandResult.failure(
                1,
                List.of("Command not found: " + commandName)
            );
        }

        return CommandResult.success(
            0,
            List.of(
                "Command Status: " + commandName,
                "Description: " + command.getDescription(),
                "Permission Level: " + command.getRequiredPermissionLevel(),
                "Usage: " + command.getUsage()
            )
        );
    }

    /**
     * Extract command source from network message.
     *
     * @param message the network message
     * @return the command source stack
     */
    private Object extractCommandSourceFromMessage(NetworkMessage message) {
        // In a real implementation, this would extract and reconstruct the CommandSourceStack
        // from the network message data
        return new Object(); // Placeholder
    }

    /**
     * Functional interface for message handlers.
     */
    @FunctionalInterface
    public interface MessageHandler {
        CommandResult handle(NetworkMessage message) throws Exception;
    }

    /**
     * Simple network message representation.
     */
    public static class NetworkMessage {
        private final String type;
        private final Map<String, Object> data;
        private final String traceId;

        public NetworkMessage(String type, Map<String, Object> data, String traceId) {
            this.type = type;
            this.data = Map.copyOf(data);
            this.traceId = traceId;
        }

        public String getType() {
            return type;
        }

        public Map<String, Object> getData() {
            return data;
        }

        public String getTraceId() {
            return traceId;
        }
    }
}