package com.kneaf.core.model;

/**
 * Entity priority levels for CPU resource allocation
 */
public enum EntityPriority {
    CRITICAL(0), HIGH(1), MEDIUM(2), LOW(3);

    private final int level;

    EntityPriority(int level) {
        this.level = level;
    }

    public int getLevel() {
        return level;
    }
}
