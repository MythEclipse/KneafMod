package com.kneaf.core.command.unified;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Centralized registry for managing all commands in the unified system.
 * Thread-safe and provides command lookup, registration, and management capabilities.
 */
public class CommandRegistry {

    private final Map<String, ExecutableCommand> commands = new ConcurrentHashMap<>();
    private final Map<String, List<ExecutableCommand>> commandGroups = new ConcurrentHashMap<>();

    /**
     * Register a command with the registry.
     *
     * @param command the command to register
     * @throws IllegalArgumentException if command with same name is already registered
     */
    public void registerCommand(ExecutableCommand command) {
        String commandName = command.getName();
        if (commands.containsKey(commandName)) {
            throw new IllegalArgumentException("Command '" + commandName + "' is already registered");
        }

        commands.put(commandName, command);
        
        // Register command in groups based on aliases
        for (String alias : command.getAliases()) {
            commandGroups.computeIfAbsent(alias, k -> new java.util.ArrayList<>())
                    .add(command);
        }
    }

    /**
     * Unregister a command from the registry.
     *
     * @param commandName the name of the command to unregister
     * @return true if command was found and unregistered, false otherwise
     */
    public boolean unregisterCommand(String commandName) {
        ExecutableCommand removed = commands.remove(commandName);
        if (removed != null) {
            // Remove from groups
            for (String alias : removed.getAliases()) {
                List<ExecutableCommand> group = commandGroups.get(alias);
                if (group != null) {
                    group.removeIf(cmd -> cmd.getName().equals(commandName));
                    if (group.isEmpty()) {
                        commandGroups.remove(alias);
                    }
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Get a command by name.
     *
     * @param commandName the command name
     * @return the command or null if not found
     */
    public ExecutableCommand getCommand(String commandName) {
        return commands.get(commandName);
    }

    /**
     * Get all commands in a group.
     *
     * @param groupName the group name
     * @return list of commands in the group
     */
    public List<ExecutableCommand> getCommandsInGroup(String groupName) {
        return commandGroups.getOrDefault(groupName, List.of());
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
     * @return list of all registered command names
     */
    public List<String> getRegisteredCommandNames() {
        return commands.keySet().stream().collect(Collectors.toList());
    }

    /**
     * Get all registered commands.
     *
     * @return list of all registered commands
     */
    public List<ExecutableCommand> getRegisteredCommands() {
        return commands.values().stream().collect(Collectors.toList());
    }

    /**
     * Clear all registered commands.
     */
    public void clear() {
        commands.clear();
        commandGroups.clear();
    }

    /**
     * Get command statistics.
     *
     * @return map containing registry statistics
     */
    public Map<String, Object> getStatistics() {
        return Map.of(
                "total_commands", commands.size(),
                "command_groups", commandGroups.size(),
                "registered_commands", getRegisteredCommandNames()
        );
    }
}