package com.kneaf.core.performance.spatial;

import java.util.List;

/**
 * Represents a group of villagers for optimized processing.
 */
public class VillagerGroup {
    private final long groupId;
    private final float centerX;
    private final float centerY;
    private final float centerZ;
    private final List<Long> villagerIds;
    private final String groupType;
    private final byte aiTickRate;

    public VillagerGroup(long groupId, float centerX, float centerY, float centerZ, 
                           List<Long> villagerIds, String groupType, byte aiTickRate) {
        this.groupId = groupId;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.villagerIds = villagerIds;
        this.groupType = groupType;
        this.aiTickRate = aiTickRate;
    }

    public long getGroupId() {
        return groupId;
    }

    public float getCenterX() {
        return centerX;
    }

    public float getCenterY() {
        return centerY;
    }

    public float getCenterZ() {
        return centerZ;
    }

    public List<Long> getVillagerIds() {
        return villagerIds;
    }

    public String getGroupType() {
        return groupType;
    }

    public byte getAiTickRate() {
        return aiTickRate;
    }
}