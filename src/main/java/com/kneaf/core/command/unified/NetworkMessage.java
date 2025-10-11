package com.kneaf.core.command.unified;

import java.util.Map;

/**
 * Represents a network message for command communication.
 */
public class NetworkMessage {
    private final String messageType;
    private final Map<String, Object> data;
    private final long timestamp;

    public NetworkMessage(String messageType, Map<String, Object> data) {
        this.messageType = messageType;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public NetworkMessage(String messageType, Map<String, Object> data, long timestamp) {
        this.messageType = messageType;
        this.data = data;
        this.timestamp = timestamp;
    }

    public String getMessageType() {
        return messageType;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public long getTimestamp() {
        return timestamp;
    }
}