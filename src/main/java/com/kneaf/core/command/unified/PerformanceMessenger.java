package com.kneaf.core.command.unified;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified network packet handling for command performance monitoring and optimization.
 * Manages network packet processing with performance considerations.
 */
public class PerformanceMessenger {

    private final NetworkMessageHandler messageHandler;
    private final Map<String, PacketStatistics> packetStats = new ConcurrentHashMap<>();
    private final int maxPacketQueueSize;
    private final long packetTimeoutMillis;

    /**
     * Create a new PerformanceMessenger with default settings.
     *
     * @param messageHandler the network message handler
     */
    public PerformanceMessenger(NetworkMessageHandler messageHandler) {
        this(messageHandler, 1000, 5000);
    }

    /**
     * Create a new PerformanceMessenger with custom settings.
     *
     * @param messageHandler the network message handler
     * @param maxPacketQueueSize maximum packet queue size
     * @param packetTimeoutMillis packet timeout in milliseconds
     */
    public PerformanceMessenger(NetworkMessageHandler messageHandler, int maxPacketQueueSize, long packetTimeoutMillis) {
        this.messageHandler = messageHandler;
        this.maxPacketQueueSize = maxPacketQueueSize;
        this.packetTimeoutMillis = packetTimeoutMillis;
    }

    /**
     * Send a command packet over the network.
     *
     * @param packet the command packet to send
     * @return the command result
     */
    public CommandResult sendCommandPacket(CommandPacket packet) {
        long startTime = System.currentTimeMillis();
        CommandResult result;

        try {
            // Validate packet
            if (!validatePacket(packet)) {
                return CommandResult.failure(
                    1,
                    List.of("Invalid command packet: " + packet.getCommandName())
                );
            }

            // Process packet through message handler
            NetworkMessageHandler.NetworkMessage message = createNetworkMessageFromPacket(packet);
            result = messageHandler.processMessage(message);

            // Update statistics
            updatePacketStatistics(packet.getCommandName(), startTime, result.isSuccess());

            return result;

        } catch (Exception e) {
            updatePacketStatistics(packet.getCommandName(), startTime, false);
            return CommandResult.failure(
                1,
                List.of("Failed to send command packet: " + e.getMessage())
            );
        }
    }

    /**
     * Receive and process a command packet from the network.
     *
     * @param packet the incoming command packet
     * @return the command result
     */
    public CommandResult receiveCommandPacket(CommandPacket packet) {
        return sendCommandPacket(packet); // For simplicity, same processing for send/receive
    }

    /**
     * Validate a command packet.
     *
     * @param packet the packet to validate
     * @return true if packet is valid
     */
    protected boolean validatePacket(CommandPacket packet) {
        return packet != null && packet.getCommandName() != null && !packet.getCommandName().isEmpty();
    }

    /**
     * Create a network message from a command packet.
     *
     * @param packet the command packet
     * @return the network message
     */
    protected NetworkMessageHandler.NetworkMessage createNetworkMessageFromPacket(CommandPacket packet) {
        Map<String, Object> data = Map.of(
                "command", packet.getCommandName(),
                "arguments", packet.getArguments(),
                "timestamp", packet.getTimestamp()
        );

        return new NetworkMessageHandler.NetworkMessage(
                "COMMAND_EXECUTE",
                data,
                packet.getTraceId()
        );
    }

    /**
     * Update packet statistics.
     *
     * @param commandName the command name
     * @param startTime the start time of packet processing
     * @param success whether processing was successful
     */
    private void updatePacketStatistics(String commandName, long startTime, boolean success) {
        long executionTime = System.currentTimeMillis() - startTime;
        packetStats.compute(commandName, (key, stats) -> {
            if (stats == null) {
                stats = new PacketStatistics();
            }
            stats.update(executionTime, success);
            return stats;
        });
    }

    /**
     * Get packet statistics for a command.
     *
     * @param commandName the command name
     * @return packet statistics or null if no statistics available
     */
    public PacketStatistics getPacketStatistics(String commandName) {
        return packetStats.get(commandName);
    }

    /**
     * Get all packet statistics.
     *
     * @return map of command name to packet statistics
     */
    public Map<String, PacketStatistics> getAllPacketStatistics() {
        return Map.copyOf(packetStats);
    }

    /**
     * Command packet representation for network transmission.
     */
    public static class CommandPacket {
        private final String commandName;
        private final Map<String, Object> arguments;
        private final long timestamp;
        private final String traceId;

        public CommandPacket(String commandName, Map<String, Object> arguments, String traceId) {
            this.commandName = commandName;
            this.arguments = Map.copyOf(arguments);
            this.timestamp = System.currentTimeMillis();
            this.traceId = traceId != null ? traceId : java.util.UUID.randomUUID().toString();
        }

        public String getCommandName() {
            return commandName;
        }

        public Map<String, Object> getArguments() {
            return arguments;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public String getTraceId() {
            return traceId;
        }
    }

    /**
     * Packet statistics for performance monitoring.
     */
    public static class PacketStatistics {
        private long totalPackets = 0;
        private long successfulPackets = 0;
        private long failedPackets = 0;
        private long totalProcessingTime = 0;
        private long minProcessingTime = Long.MAX_VALUE;
        private long maxProcessingTime = 0;

        /**
         * Update statistics with new packet data.
         *
         * @param processingTime processing time in milliseconds
         * @param success whether packet processing was successful
         */
        public synchronized void update(long processingTime, boolean success) {
            totalPackets++;
            totalProcessingTime += processingTime;

            if (processingTime < minProcessingTime) {
                minProcessingTime = processingTime;
            }
            if (processingTime > maxProcessingTime) {
                maxProcessingTime = processingTime;
            }

            if (success) {
                successfulPackets++;
            } else {
                failedPackets++;
            }
        }

        /**
         * Get total packets processed.
         *
         * @return total packets
         */
        public long getTotalPackets() {
            return totalPackets;
        }

        /**
         * Get successful packets.
         *
         * @return successful packets
         */
        public long getSuccessfulPackets() {
            return successfulPackets;
        }

        /**
         * Get failed packets.
         *
         * @return failed packets
         */
        public long getFailedPackets() {
            return failedPackets;
        }

        /**
         * Get total processing time.
         *
         * @return total processing time in milliseconds
         */
        public long getTotalProcessingTime() {
            return totalProcessingTime;
        }

        /**
         * Get average processing time.
         *
         * @return average processing time in milliseconds
         */
        public double getAverageProcessingTime() {
            return totalPackets > 0 ? (double) totalProcessingTime / totalPackets : 0;
        }

        /**
         * Get minimum processing time.
         *
         * @return minimum processing time in milliseconds
         */
        public long getMinProcessingTime() {
            return minProcessingTime;
        }

        /**
         * Get maximum processing time.
         *
         * @return maximum processing time in milliseconds
         */
        public long getMaxProcessingTime() {
            return maxProcessingTime;
        }

        /**
         * Get success rate.
         *
         * @return success rate (0-1)
         */
        public double getSuccessRate() {
            return totalPackets > 0 ? (double) successfulPackets / totalPackets : 0;
        }
    }
}