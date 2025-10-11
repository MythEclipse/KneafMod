package com.kneaf.core.command.unified;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ServiceLoader-based plugin management for command plugins.
 * Loads and manages plugin commands from the classpath.
 */
public class CommandPluginRegistry {

    private final Map<String, PluginCommand> pluginCommands = new ConcurrentHashMap<>();
    private final ServiceLoader<PluginCommand> serviceLoader;
    private final CommandRegistry commandRegistry;

    /**
     * Create a new CommandPluginRegistry.
     *
     * @param commandRegistry the command registry to register plugin commands with
     */
    public CommandPluginRegistry(CommandRegistry commandRegistry) {
        this.commandRegistry = commandRegistry;
        this.serviceLoader = ServiceLoader.load(PluginCommand.class);
        loadPlugins();
    }

    /**
     * Load all available plugin commands using ServiceLoader.
     */
    public void loadPlugins() {
        serviceLoader.reload();
        
        for (PluginCommand pluginCommand : serviceLoader) {
            if (pluginCommand.isEnabled()) {
                registerPluginCommand(pluginCommand);
            }
        }
    }

    /**
     * Register a plugin command.
     *
     * @param pluginCommand the plugin command to register
     */
    public void registerPluginCommand(PluginCommand pluginCommand) {
        String commandName = pluginCommand.getName();
        
        if (pluginCommands.containsKey(commandName)) {
            PluginCommand existing = pluginCommands.get(commandName);
            if (pluginCommand.getCommandPriority() < existing.getCommandPriority()) {
                // Replace with higher priority command
                unregisterPluginCommand(existing);
            } else {
                return; // Keep existing command with higher or equal priority
            }
        }

        pluginCommands.put(commandName, pluginCommand);
        commandRegistry.registerCommand(pluginCommand);
        
        // Use system logger instead of plugin logger
        System.out.println(String.format("Registered plugin command: %s (from %s v%s)",
                commandName, pluginCommand.getPluginId(), pluginCommand.getPluginVersion()));
    }

    /**
     * Unregister a plugin command.
     *
     * @param pluginCommand the plugin command to unregister
     */
    public void unregisterPluginCommand(PluginCommand pluginCommand) {
        String commandName = pluginCommand.getName();
        pluginCommands.remove(commandName);
        commandRegistry.unregisterCommand(commandName);
        
        // Use system logger instead of plugin logger
        System.out.println(String.format("Unregistered plugin command: %s (from %s v%s)",
                commandName, pluginCommand.getPluginId(), pluginCommand.getPluginVersion()));
    }

    /**
     * Get a plugin command by name.
     *
     * @param commandName the command name
     * @return the plugin command or null if not found
     */
    public PluginCommand getPluginCommand(String commandName) {
        return pluginCommands.get(commandName);
    }

    /**
     * Get all registered plugin commands.
     *
     * @return set of all registered plugin commands
     */
    public Set<PluginCommand> getPluginCommands() {
        return Set.copyOf(pluginCommands.values());
    }

    /**
     * Get all plugin commands from a specific plugin.
     *
     * @param pluginId the plugin ID
     * @return set of commands from the specified plugin
     */
    public Set<PluginCommand> getCommandsByPluginId(String pluginId) {
        return pluginCommands.values().stream()
                .filter(cmd -> cmd.getPluginId().equals(pluginId))
                .collect(Collectors.toSet());
    }

    /**
     * Get plugin command statistics.
     *
     * @return map containing plugin command statistics
     */
    public Map<String, Object> getStatistics() {
        Map<String, Long> statsByPlugin = pluginCommands.values().stream()
                .collect(Collectors.groupingBy(PluginCommand::getPluginId, Collectors.counting()));

        return Map.of(
                "total_plugin_commands", pluginCommands.size(),
                "commands_by_plugin", statsByPlugin,
                "plugin_ids", pluginCommands.values().stream()
                        .map(PluginCommand::getPluginId)
                        .distinct()
                        .collect(Collectors.toList())
        );
    }

    /**
     * Refresh plugin commands by reloading from ServiceLoader.
     */
    public void refreshPlugins() {
        // Unregister all current plugin commands
        pluginCommands.values().forEach(this::unregisterPluginCommand);
        
        // Load and register new plugins
        loadPlugins();
    }
}