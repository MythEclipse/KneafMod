package com.kneaf.core.command.unified;

import java.util.List;
import java.util.Map;

/**
 * Base implementation for all commands in the unified system.
 * Provides common functionality and serves as a foundation for concrete command implementations.
 */
public abstract class BaseCommand implements ExecutableCommand {

    private final String name;
    private final String description;
    private final int requiredPermissionLevel;
    private final String usage;
    private final List<String> aliases;
    private final Map<String, String> metadata;
    protected final CommandLogger logger;

    /**
     * Create a new BaseCommand with standard configuration.
     *
     * @param name the command name
     * @param description the command description
     * @param requiredPermissionLevel required permission level
     * @param usage the usage string
     */
    protected BaseCommand(String name, String description, int requiredPermissionLevel, String usage) {
        this(name, description, requiredPermissionLevel, usage, List.of(), Map.of());
    }

    /**
     * Create a new BaseCommand with aliases and metadata.
     *
     * @param name the command name
     * @param description the command description
     * @param requiredPermissionLevel required permission level
     * @param usage the usage string
     * @param aliases command aliases
     * @param metadata additional command metadata
     */
    protected BaseCommand(String name, String description, int requiredPermissionLevel, String usage,
                         List<String> aliases, Map<String, String> metadata) {
        this.name = name;
        this.description = description;
        this.requiredPermissionLevel = requiredPermissionLevel;
        this.usage = usage;
        this.aliases = List.copyOf(aliases);
        this.metadata = Map.copyOf(metadata);
        this.logger = new CommandLogger();
    }

    @Override
    public final String getName() {
        return name;
    }

    @Override
    public final String getDescription() {
        return description;
    }

    @Override
    public final int getRequiredPermissionLevel() {
        return requiredPermissionLevel;
    }

    @Override
    public final String getUsage() {
        return usage;
    }

    @Override
    public final List<String> getAliases() {
        return aliases;
    }

    @Override
    public final Map<String, String> getMetadata() {
        return metadata;
    }

    /**
     * Get the command logger.
     *
     * @return the command logger
     */
    protected CommandLogger getLogger() {
        return logger;
    }

    /**
     * Send success message to command source.
     *
     * @param context the command context
     * @param message the success message
     */
    protected void sendSuccess(CommandContext context, String message) {
        // In a real implementation, this would send a message to the command source
        logger.logInfo(String.format("Sent success to %s: %s", getSourceIdentifier(context), message));
    }

    /**
     * Send failure message to command source.
     *
     * @param context the command context
     * @param message the failure message
     */
    protected void sendFailure(CommandContext context, String message) {
        // In a real implementation, this would send a message to the command source
        logger.logWarn(String.format("Sent failure to %s: %s", getSourceIdentifier(context), message));
    }

    /**
     * Get a human-readable identifier for the command source.
     *
     * @param context the command context
     * @return source identifier
     */
    private String getSourceIdentifier(CommandContext context) {
        return context.getPlayer()
                .map(player -> "player:" + player.getGameProfile().getName())
                .orElseGet(() -> "unknown:" + context.getSource().getTextName());
    }

    /**
     * Create a successful command result.
     *
     * @param messages result messages
     * @return successful command result
     */
    protected CommandResult successResult(List<String> messages) {
        return CommandResult.success(0, messages);
    }

    /**
     * Create a failed command result.
     *
     * @param returnCode failure return code
     * @param messages error messages
     * @return failed command result
     */
    protected CommandResult failureResult(int returnCode, List<String> messages) {
        return CommandResult.failure(returnCode, messages);
    }
}