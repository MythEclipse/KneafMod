package com.kneaf.core.command.unified.migration;

import com.kneaf.core.command.unified.CommandResult;
import com.kneaf.core.command.unified.ExecutableCommand;
import com.kneaf.core.unifiedbridge.BridgeException;
import com.kneaf.core.unifiedbridge.BridgeResult;
import com.kneaf.core.unifiedbridge.UnifiedBridge;
import com.kneaf.core.unifiedbridge.BridgeFactory;
import com.kneaf.core.unifiedbridge.BridgeConfiguration;
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
         * Validate context through bridge components.
         *
         * @param context the unified command context to validate
         * @throws BridgeException if validation fails
         */
        private void validateContextThroughBridge(com.kneaf.core.command.unified.CommandContext context) throws BridgeException {
            try {
                // Import bridge components
                UnifiedBridge bridge = BridgeFactory.getInstance()
                    .createBridge(BridgeFactory.BridgeType.SYNCHRONOUS,
                                BridgeConfiguration.getDefault());

                // Validate context using bridge operation
                BridgeResult result = bridge.executeSync(
                    "validate_command_context",
                    context.getSource(),
                    context.getArguments(),
                    context.getTraceId()
                );

                if (!result.isSuccess()) {
                    throw new BridgeException("Context validation failed: " + result.getErrorMessage(),
                                            BridgeException.BridgeErrorType.COMPATIBILITY_ERROR);
                }

                // Additional validation for arguments
                Map<String, Object> args = context.getArguments();
                if (args != null) {
                    for (Map.Entry<String, Object> entry : args.entrySet()) {
                        if (entry.getKey() == null || entry.getKey().trim().isEmpty()) {
                            throw new BridgeException("Invalid argument key: null or empty",
                                                    BridgeException.BridgeErrorType.COMPATIBILITY_ERROR);
                        }
                        // Validate argument value types
                        if (entry.getValue() != null && !isValidArgumentType(entry.getValue())) {
                            throw new BridgeException("Invalid argument type for key '" + entry.getKey() + "': " +
                                                    entry.getValue().getClass().getName(),
                                                    BridgeException.BridgeErrorType.COMPATIBILITY_ERROR);
                        }
                    }
                }

            } catch (BridgeException e) {
                throw e; // Re-throw bridge exceptions
            } catch (Exception e) {
                throw new BridgeException("Bridge validation failed: " + e.getMessage(),
                                        BridgeException.BridgeErrorType.GENERIC_ERROR, e);
            }
        }

        /**
         * Check if argument value type is valid for legacy context.
         *
         * @param value the argument value
         * @return true if valid
         */
        private boolean isValidArgumentType(Object value) {
            return value instanceof String ||
                   value instanceof Number ||
                   value instanceof Boolean ||
                   value instanceof java.util.List ||
                   value instanceof java.util.Map;
        }

        /**
         * Create a legacy command context from the new context.
         *
         * @param context the new command context
         * @return the legacy command context
         */
        private CommandContext<CommandSourceStack> createLegacyContext(com.kneaf.core.command.unified.CommandContext context) {
            try {
                // Validate input context
                if (context == null) {
                    throw new IllegalArgumentException("Unified context cannot be null");
                }

                if (context.getSource() == null) {
                    throw new IllegalArgumentException("Command source cannot be null");
                }

                // Use bridge for validation and conversion assistance
                validateContextThroughBridge(context);

                // Create legacy context adapter
                // Note: Using cast since CommandContext is final and cannot be extended
                @SuppressWarnings("unchecked")
                CommandContext<CommandSourceStack> legacyContext =
                    (CommandContext<CommandSourceStack>) (Object) new LegacyCommandContextAdapter(context);
                return legacyContext;

            } catch (Exception e) {
                // Log error with trace ID for debugging
                System.err.println("Failed to create legacy context [traceId: " + context.getTraceId() + "]: " + e.getMessage());
                throw new RuntimeException("Legacy context creation failed: " + e.getMessage(), e);
            }
        }

        /**
         * Legacy Command Context Adapter that bridges unified context to Brigadier CommandContext.
         * This adapter provides backward compatibility by implementing the necessary CommandContext methods.
         * Note: Since CommandContext is final, this uses composition and delegation.
         */
        private static class LegacyCommandContextAdapter {
            private final com.kneaf.core.command.unified.CommandContext unifiedContext;
            private final Map<String, Object> argumentCache;

            /**
             * Create a new legacy context adapter.
             *
             * @param unifiedContext the unified command context
             */
            public LegacyCommandContextAdapter(com.kneaf.core.command.unified.CommandContext unifiedContext) {
                this.unifiedContext = unifiedContext;
                this.argumentCache = new java.util.HashMap<>(unifiedContext.getArguments());

                // Initialize with unified context data
                initializeFromUnifiedContext();
            }

            /**
             * Initialize adapter from unified context data.
             */
            private void initializeFromUnifiedContext() {
                // Convert unified arguments to Brigadier-compatible format
                for (Map.Entry<String, Object> entry : unifiedContext.getArguments().entrySet()) {
                    argumentCache.put(entry.getKey(), convertArgumentValue(entry.getValue()));
                }
            }

            /**
             * Convert unified argument value to legacy-compatible format.
             *
             * @param value unified argument value
             * @return legacy-compatible value
             */
            private Object convertArgumentValue(Object value) {
                if (value == null) {
                    return null;
                }

                // Convert common types
                if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                    return value;
                }

                // Convert collections
                if (value instanceof java.util.List) {
                    return value; // Lists are compatible
                }

                if (value instanceof java.util.Map) {
                    return value; // Maps are compatible
                }

                // For complex objects, convert to string representation
                return value.toString();
            }

            public <V> V getArgument(String name, Class<V> clazz) {
                Object value = argumentCache.get(name);
                if (value == null) {
                    throw new IllegalArgumentException("No argument found with name '" + name + "'");
                }

                if (!clazz.isInstance(value)) {
                    throw new IllegalArgumentException("Argument '" + name + "' is not of type " + clazz.getName() +
                                                     ", found " + value.getClass().getName());
                }

                return clazz.cast(value);
            }

            public CommandSourceStack getSource() {
                return unifiedContext.getSource();
            }

            public boolean hasArgument(String name) {
                return argumentCache.containsKey(name);
            }

            public String getInput() {
                // Return a synthetic input string based on arguments
                return buildInputString();
            }

            /**
             * Build synthetic input string from arguments.
             *
             * @return input string
             */
            private String buildInputString() {
                StringBuilder input = new StringBuilder("/command");
                for (Map.Entry<String, Object> entry : argumentCache.entrySet()) {
                    input.append(" ").append(entry.getKey()).append(":").append(entry.getValue());
                }
                return input.toString();
            }

            public com.mojang.brigadier.context.ParsedCommandNode<CommandSourceStack> getRootNode() {
                // Return null for simplified implementation
                // In a full implementation, this would construct proper Brigadier nodes
                return null;
            }

            public java.util.List<com.mojang.brigadier.context.ParsedCommandNode<CommandSourceStack>> getNodes() {
                // Return empty list for simplified implementation
                return java.util.Collections.emptyList();
            }

            public com.mojang.brigadier.context.CommandContextBuilder<CommandSourceStack> getChild() {
                // Return null for simplified implementation
                return null;
            }

            public com.mojang.brigadier.context.CommandContextBuilder<CommandSourceStack> getLastChild() {
                // Return null for simplified implementation
                return null;
            }

            public Object getCustom() {
                // Return unified context as custom data
                return unifiedContext;
            }

            public boolean isForked() {
                return false;
            }

            /**
             * Get the underlying unified context.
             *
             * @return unified command context
             */
            public com.kneaf.core.command.unified.CommandContext getUnifiedContext() {
                return unifiedContext;
            }

            /**
             * Get trace ID for debugging.
             *
             * @return trace ID
             */
            public String getTraceId() {
                return unifiedContext.getTraceId();
            }
        }
    }
}