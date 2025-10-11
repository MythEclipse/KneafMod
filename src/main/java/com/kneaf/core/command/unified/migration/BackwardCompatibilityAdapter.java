package com.kneaf.core.command.unified.migration;

import com.kneaf.core.command.unified.CommandResult;
import com.kneaf.core.command.unified.ExecutableCommand;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import java.util.Map;
import java.util.Collections;

/**
 * Migration adapter for backward compatibility with existing command systems.
 * Bridges the new unified command system with legacy command implementations.
 */
public class BackwardCompatibilityAdapter {

    /**
     * Adapt a legacy CommandExecutor to the new ExecutableCommand interface.
     *
     * @param legacyExecutor the legacy command executor
     * @return the adapted executable command
     */
    public static ExecutableCommand adaptLegacyCommand(com.kneaf.core.protocol.core.CommandExecutor legacyExecutor) {
        return new LegacyCommandAdapter(legacyExecutor);
    }

    /**
     * Adapt a legacy command context to the new CommandContext.
     *
     * @param legacyContext the legacy command context
     * @return the adapted command context
     */
    public static com.kneaf.core.command.unified.CommandContext adaptLegacyContext(CommandContext<CommandSourceStack> legacyContext) {
        // For Brigadier CommandContext, we need to extract arguments differently
        // The arguments are stored in the context's nodes and can be accessed via getArgument method
        Map<String, Object> arguments = new java.util.HashMap<>();
        
        // Try to extract common arguments - this is a simplified approach
        // In a real implementation, you would need to know the specific argument names
        try {
            // Example: try to get some common arguments that might exist
            arguments.put("target", legacyContext.getSource()); // Fallback to source
        } catch (Exception e) {
            // If argument doesn't exist, just use empty map
        }

        return com.kneaf.core.command.unified.CommandContext.builder()
                .source(legacyContext.getSource())
                .arguments(arguments)
                .traceId(generateTraceId())
                .build();
    }

    /**
     * Adapt a new CommandResult to a legacy command return code.
     *
     * @param result the command result
     * @return the legacy return code (1 for success, 0 for failure)
     */
    public static int adaptResultToLegacyReturnCode(CommandResult result) {
        return result.isSuccess() ? 1 : 0;
    }

    /**
     * Generate a trace ID for logging.
     *
     * @return a unique trace ID
     */
    private static String generateTraceId() {
        return java.util.UUID.randomUUID().toString();
    }

    /**
     * Private adapter class that wraps a legacy CommandExecutor.
     */
    private static class LegacyCommandAdapter implements ExecutableCommand {

        private final com.kneaf.core.protocol.core.CommandExecutor legacyExecutor;

        public LegacyCommandAdapter(com.kneaf.core.protocol.core.CommandExecutor legacyExecutor) {
            this.legacyExecutor = legacyExecutor;
        }

        @Override
        public String getName() {
            return legacyExecutor.getCommandName();
        }

        @Override
        public String getDescription() {
            return legacyExecutor.getDescription();
        }

        @Override
        public int getRequiredPermissionLevel() {
            return legacyExecutor.getRequiredPermissionLevel();
        }

        @Override
        public String getUsage() {
            return legacyExecutor.getUsage();
        }

        @Override
        public CommandResult execute(com.kneaf.core.command.unified.CommandContext context) throws Exception {
            // Convert new context to legacy context
            CommandContext<CommandSourceStack> legacyContext = 
                    createLegacyContext(context);

            // Execute legacy command
            int legacyResult = legacyExecutor.execute(legacyContext);

            // Convert legacy result to new CommandResult
            return legacyResult > 0 
                    ? CommandResult.success(legacyResult, Collections.singletonList("Command executed successfully"))
                    : CommandResult.failure(legacyResult, Collections.singletonList("Command failed"));
        }

        @Override
        public boolean validateArguments(com.kneaf.core.command.unified.CommandContext context) {
            CommandContext<CommandSourceStack> legacyContext = 
                    createLegacyContext(context);
            return legacyExecutor.validateArguments(legacyContext);
        }

        @Override
        public CommandResult handleError(com.kneaf.core.command.unified.CommandContext context, Exception error) {
            return CommandResult.failure(
                0,
                Collections.singletonList("Legacy command failed: " + error.getMessage())
            );
        }

        /**
         * Create a legacy command context from the new context.
         *
         * @param context the new command context
         * @return the legacy command context
         */
        private CommandContext<CommandSourceStack> createLegacyContext(com.kneaf.core.command.unified.CommandContext context) {
            // This is a simplified adaptation - in a real implementation, you would need to
            // properly reconstruct the Brigadier command context with all the necessary nodes
            // For now, we'll just return a simple implementation that satisfies the interface
            throw new UnsupportedOperationException("Legacy context creation not implemented yet");
        }
    }
}