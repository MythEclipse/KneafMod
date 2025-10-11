package com.kneaf.core.command.unified;

import java.util.List;
import java.util.Map;

/**
 * Core interface defining the contract for all commands in the unified system.
 * Establishes common functionality and contract for command execution.
 */
public interface UnifiedCommand {

    /**
     * Get the unique name of this command.
     *
     * @return command name
     */
    String getName();

    /**
     * Get the command description.
     *
     * @return command description
     */
    String getDescription();

    /**
     * Get the required permission level for this command.
     *
     * @return permission level (0-4 typically)
     */
    int getRequiredPermissionLevel();

    /**
     * Get the usage syntax for this command.
     *
     * @return usage string
     */
    String getUsage();

    /**
     * Get the aliases for this command.
     *
     * @return list of command aliases
     */
    default List<String> getAliases() {
        return List.of();
    }

    /**
     * Get the command metadata.
     *
     * @return map of metadata key-value pairs
     */
    default Map<String, String> getMetadata() {
        return Map.of();
    }
}