package com.kneaf.core.command.unified;

/**
 * Interface for commands that are provided by plugins.
 * Extends the ExecutableCommand interface with plugin-specific capabilities.
 */
public interface PluginCommand extends ExecutableCommand {

    /**
     * Get the plugin identifier that provides this command.
     *
     * @return plugin identifier (e.g., "kneaf-performance", "kneaf-chunkstorage")
     */
    String getPluginId();

    /**
     * Get the plugin version that provides this command.
     *
     * @return plugin version
     */
    String getPluginVersion();

    /**
     * Get the command priority for registration.
     * Lower values indicate higher priority (will be registered first).
     *
     * @return command priority
     */
    default int getCommandPriority() {
        return 0; // Normal priority by default
    }

    /**
     * Check if this command is enabled.
     *
     * @return true if command is enabled
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * Get the plugin-specific metadata.
     *
     * @return map of plugin-specific metadata
     */
    default java.util.Map<String, String> getPluginMetadata() {
        return java.util.Map.of();
    }
}