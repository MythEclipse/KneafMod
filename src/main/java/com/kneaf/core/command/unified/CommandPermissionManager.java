package com.kneaf.core.command.unified;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized permission management for command execution.
 * Manages permission checks and provides permission-related operations.
 */
public class CommandPermissionManager {

    private final Map<String, Set<Integer>> commandPermissions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> permissionGroups = new ConcurrentHashMap<>();

    /**
     * Register permissions for a command.
     *
     * @param commandName the command name
     * @param permissionLevels the required permission levels
     */
    public void registerCommandPermissions(String commandName, Set<Integer> permissionLevels) {
        commandPermissions.put(commandName, Set.copyOf(permissionLevels));
    }

    /**
     * Register a permission group.
     *
     * @param groupName the group name
     * @param permissions the permissions in the group
     */
    public void registerPermissionGroup(String groupName, Set<String> permissions) {
        permissionGroups.put(groupName, Set.copyOf(permissions));
    }

    /**
     * Check if a command context has permission to execute a command.
     *
     * @param context the command context
     * @param command the command to check
     * @return true if permission is granted
     */
    public boolean hasPermission(CommandContext context, ExecutableCommand command) {
        
        // Get the source's permission level (simplified - would need actual implementation)
        int sourceLevel = getSourcePermissionLevel(context);

        // Get the required permission level from the command
        int requiredLevel = command.getRequiredPermissionLevel();

        // Check if source has sufficient permission level
        return sourceLevel >= requiredLevel;
    }

    /**
     * Get the permission level of the command source.
     *
     * @param context the command context
     * @return the permission level
     */
    protected int getSourcePermissionLevel(CommandContext context) {
        // This is a simplified implementation - in a real system, you would extract
        // the actual permission level from the command source
        return 0; // Default to 0 (guest) if no permission level can be determined
    }

    /**
     * Check if a player has a specific permission.
     *
     * @param playerId the player ID
     * @param permission the permission to check
     * @return true if player has the permission
     */
    public boolean hasPermission(String playerId, String permission) {
        // Simplified implementation - would check player's assigned permissions
        return false;
    }

    /**
     * Get all permissions for a command.
     *
     * @param commandName the command name
     * @return set of permission levels
     */
    public Set<Integer> getCommandPermissions(String commandName) {
        return commandPermissions.getOrDefault(commandName, Set.of(0));
    }

    /**
     * Get all permission groups.
     *
     * @return map of permission groups
     */
    public Map<String, Set<String>> getPermissionGroups() {
        return Map.copyOf(permissionGroups);
    }

    /**
     * Get permission statistics.
     *
     * @return map containing permission statistics
     */
    public Map<String, Object> getStatistics() {
        return Map.of(
                "registered_commands", commandPermissions.size(),
                "registered_groups", permissionGroups.size(),
                "total_permissions", commandPermissions.values().stream()
                        .mapToInt(Set::size)
                        .sum()
        );
    }
}