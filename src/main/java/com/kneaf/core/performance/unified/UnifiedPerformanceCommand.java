package com.kneaf.core.performance.unified;

import com.kneaf.core.protocol.core.ProtocolConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Unified PerformanceCommand that consolidates all performance-related commands
 * using the subcommand pattern for maintainability and extensibility.
 */
public class UnifiedPerformanceCommand {
    private static final String COMMAND_NAME = "perf";
    private static final String DESCRIPTION = "Kneaf unified performance management commands";
    private static final String USAGE = "/kneaf perf <subcommand>";
    private static final int PERMISSION_LEVEL = ProtocolConstants.COMMAND_PERMISSION_LEVEL_MODERATOR;
    private static final Logger LOGGER = LogManager.getLogger(UnifiedPerformanceCommand.class);

    private final ToggleSubcommand toggleSubcommand;
    private final StatusSubcommand statusSubcommand;
    private final MetricsSubcommand metricsSubcommand;
    private final BroadcastSubcommand broadcastSubcommand;
    private final RotateLogSubcommand rotateLogSubcommand;
    private final HelpSubcommand helpSubcommand;

    /**
     * Create a new unified performance command.
     */
    public UnifiedPerformanceCommand() {
        PerformanceManagerImpl manager = PerformanceManagerImpl.getInstance();
        
        this.toggleSubcommand = new ToggleSubcommand(manager);
        this.statusSubcommand = new StatusSubcommand(manager);
        this.metricsSubcommand = new MetricsSubcommand(manager);
        this.broadcastSubcommand = new BroadcastSubcommand();
        this.rotateLogSubcommand = new RotateLogSubcommand(manager);
        this.helpSubcommand = new HelpSubcommand(this);
    }

    /**
     * Register the unified performance command and all subcommands.
     *
     * @param dispatcher the command dispatcher
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        UnifiedPerformanceCommand mainCommand = new UnifiedPerformanceCommand();
        
        LiteralArgumentBuilder<CommandSourceStack> builder = Commands.literal("kneaf")
                .then(Commands.literal(COMMAND_NAME)
                        .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                        .then(Commands.literal("toggle")
                                .executes(context -> mainCommand.toggleSubcommand.execute(context)))
                        .then(Commands.literal("status")
                                .executes(context -> mainCommand.statusSubcommand.execute(context)))
                        .then(Commands.literal("metrics")
                                .executes(context -> mainCommand.metricsSubcommand.execute(context)))
                        .then(Commands.literal("broadcast")
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(context -> mainCommand.broadcastSubcommand.execute(context))))
                        .then(Commands.literal("rotatelog")
                                .executes(context -> mainCommand.rotateLogSubcommand.execute(context)))
                        .then(Commands.literal("help")
                                .executes(context -> mainCommand.helpSubcommand.execute(context))));

        dispatcher.register(builder);
        LOGGER.info("Unified performance command registered");
    }

    /**
     * Base interface for all performance command subcommands.
     */
    public interface BaseSubcommand {
        /**
         * Execute the subcommand.
         *
         * @param context the command context
         * @return command result
         */
        int execute(CommandContext<CommandSourceStack> context);

        /**
         * Get the subcommand name.
         *
         * @return subcommand name
         */
        String getName();

        /**
         * Get the subcommand description.
         *
         * @return subcommand description
         */
        String getDescription();
    }

    /**
     * Abstract base class for subcommands with common functionality.
     */
    public abstract static class BaseSubcommandImpl implements BaseSubcommand {
        protected final int permissionLevel;

        public BaseSubcommandImpl(int permissionLevel) {
            this.permissionLevel = permissionLevel;
        }

        /**
         * Send a success message to the command source.
         *
         * @param context the command context
         * @param message the success message
         */
        protected void sendSuccess(CommandContext<CommandSourceStack> context, String message) {
            context.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(message), false);
        }

        /**
         * Send a formatted success message to the command source.
         *
         * @param context the command context
         * @param format the format string
         * @param args the format arguments
         */
        protected void sendSuccessFormatted(CommandContext<CommandSourceStack> context, String format, Object... args) {
            sendSuccess(context, String.format(format, args));
        }

        /**
         * Send a failure message to the command source.
         *
         * @param context the command context
         * @param message the failure message
         */
        protected void sendFailure(CommandContext<CommandSourceStack> context, String message) {
            context.getSource().sendFailure(net.minecraft.network.chat.Component.literal(message));
        }

        /**
         * Send a formatted failure message to the command source.
         *
         * @param context the command context
         * @param format the format string
         * @param args the format arguments
         */
        protected void sendFailureFormatted(CommandContext<CommandSourceStack> context, String format, Object... args) {
            sendFailure(context, String.format(format, args));
        }
    }

    /**
     * Toggle subcommand implementation.
     */
    public static class ToggleSubcommand extends BaseSubcommandImpl {
        private final PerformanceManagerImpl manager;

        public ToggleSubcommand(PerformanceManagerImpl manager) {
            super(PERMISSION_LEVEL);
            this.manager = manager;
        }

        @Override
        public int execute(CommandContext<CommandSourceStack> context) {
            try {
                boolean newVal = !manager.isEnabled();
                manager.setEnabled(newVal);
                sendSuccessFormatted(context, "Kneaf performance manager enabled=%s", newVal);
                return 1;
            } catch (Exception e) {
                sendFailureFormatted(context, "Failed to toggle performance manager: %s", e.getMessage());
                return 0;
            }
        }

        @Override
        public String getName() {
            return "toggle";
        }

        @Override
        public String getDescription() {
            return "Toggle performance monitoring on/off";
        }
    }

    /**
     * Status subcommand implementation.
     */
    public static class StatusSubcommand extends BaseSubcommandImpl {
        private final PerformanceManagerImpl manager;

        public StatusSubcommand(PerformanceManagerImpl manager) {
            super(PERMISSION_LEVEL);
            this.manager = manager;
        }

        @Override
        public int execute(CommandContext<CommandSourceStack> context) {
            try {
                double tps = manager.getCurrentTPS();
                double usedMemory = manager.getUsedMemoryPercentage();
                String cpuStats = "N/A"; // Replace with actual CPU stats in real implementation
                
                String msg = String.format(
                        "Kneaf Performance - enabled=%s TPS=%.2f CPU=%s MEM=%.2f%%",
                        manager.isEnabled(), tps, cpuStats, usedMemory);
                
                sendSuccess(context, msg);
                return 1;
            } catch (Exception e) {
                sendFailureFormatted(context, "Failed to get performance status: %s", e.getMessage());
                return 0;
            }
        }

        @Override
        public String getName() {
            return "status";
        }

        @Override
        public String getDescription() {
            return "Show current performance status";
        }
    }

    /**
     * Metrics subcommand implementation.
     */
    public static class MetricsSubcommand extends BaseSubcommandImpl {
        private final PerformanceManagerImpl manager;

        public MetricsSubcommand(PerformanceManagerImpl manager) {
            super(PERMISSION_LEVEL);
            this.manager = manager;
        }

        @Override
        public int execute(CommandContext<CommandSourceStack> context) {
            try {
                StringBuilder msg = new StringBuilder("Performance Metrics:\n");
                
                msg.append(String.format("  TPS: %.2f\n", manager.getCurrentTPS()));
                msg.append(String.format("  Memory: %.2f%% used\n", manager.getUsedMemoryPercentage()));
                msg.append(String.format("  CPU: N/A%%\n", "")); // Replace with actual CPU stats in real implementation
                
                sendSuccess(context, msg.toString());
                return 1;
            } catch (Exception e) {
                sendFailureFormatted(context, "Failed to get performance metrics: %s", e.getMessage());
                return 0;
            }
        }

        @Override
        public String getName() {
            return "metrics";
        }

        @Override
        public String getDescription() {
            return "Show detailed performance metrics";
        }
    }

    /**
     * Broadcast subcommand implementation.
     */
    public static class BroadcastSubcommand extends BaseSubcommandImpl {
        public BroadcastSubcommand() {
            super(PERMISSION_LEVEL);
        }

        @Override
        public int execute(CommandContext<CommandSourceStack> context) {
            try {
                String message = StringArgumentType.getString(context, "message");
                
                sendSuccessFormatted(context, "Broadcasted performance message: %s", message);
                return 1;
            } catch (Exception e) {
                sendFailureFormatted(context, "Failed to broadcast message: %s", e.getMessage());
                return 0;
            }
        }

        @Override
        public String getName() {
            return "broadcast";
        }

        @Override
        public String getDescription() {
            return "Send a test performance message to all players";
        }
    }

    /**
     * Rotate log subcommand implementation.
     */
    public static class RotateLogSubcommand extends BaseSubcommandImpl {
        private final PerformanceManagerImpl manager;

        public RotateLogSubcommand(PerformanceManagerImpl manager) {
            super(PERMISSION_LEVEL);
            this.manager = manager;
        }

        @Override
        public int execute(CommandContext<CommandSourceStack> context) {
            try {
                manager.getPerformanceManager().rotateLog();
                sendSuccess(context, "Kneaf performance log rotated.");
                return 1;
            } catch (Exception e) {
                sendFailureFormatted(context, "Failed to rotate performance log: %s", e.getMessage());
                return 0;
            }
        }

        @Override
        public String getName() {
            return "rotatelog";
        }

        @Override
        public String getDescription() {
            return "Rotate the performance log file";
        }
    }

    /**
     * Help subcommand implementation.
     */
    public static class HelpSubcommand extends BaseSubcommandImpl {
        private final UnifiedPerformanceCommand parentCommand;

        public HelpSubcommand(UnifiedPerformanceCommand parentCommand) {
            super(PERMISSION_LEVEL);
            this.parentCommand = parentCommand;
        }

        @Override
        public int execute(CommandContext<CommandSourceStack> context) {
            try {
                StringBuilder helpMessage = new StringBuilder()
                        .append("§bKneaf Performance Command Help\n")
                        .append("§bUsage: /kneaf perf <subcommand>\n")
                        .append("§bAvailable Subcommands:\n");

                helpMessage.append(String.format("§b  toggle §7- %s\n", toggleSubcommand().getDescription()))
                        .append(String.format("§b  status §7- %s\n", statusSubcommand().getDescription()))
                        .append(String.format("§b  metrics §7- %s\n", metricsSubcommand().getDescription()))
                        .append(String.format("§b  broadcast §7- %s\n", broadcastSubcommand().getDescription()))
                        .append(String.format("§b  rotatelog §7- %s\n", rotateLogSubcommand().getDescription()))
                        .append(String.format("§b  help §7- %s\n", getDescription()));

                sendSuccess(context, helpMessage.toString());
                return 1;
            } catch (Exception e) {
                sendFailureFormatted(context, "Failed to show help: %s", e.getMessage());
                return 0;
            }
        }

        private ToggleSubcommand toggleSubcommand() {
            return new ToggleSubcommand(PerformanceManagerImpl.getInstance());
        }

        private StatusSubcommand statusSubcommand() {
            return new StatusSubcommand(PerformanceManagerImpl.getInstance());
        }

        private MetricsSubcommand metricsSubcommand() {
            return new MetricsSubcommand(PerformanceManagerImpl.getInstance());
        }

        private BroadcastSubcommand broadcastSubcommand() {
            return new BroadcastSubcommand();
        }

        private RotateLogSubcommand rotateLogSubcommand() {
            return new RotateLogSubcommand(PerformanceManagerImpl.getInstance());
        }

        @Override
        public String getName() {
            return "help";
        }

        @Override
        public String getDescription() {
            return "Show performance command help";
        }
    }
}