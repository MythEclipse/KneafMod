package com.kneaf.core.flatbuffers.mob;

import com.kneaf.core.data.entity.MobData;

import java.util.List;

/**
 * Helper class for mob input data.
 */
public class MobInput {
    public final long tickCount;
    public final List<MobData> mobs;
    
    public MobInput(long tickCount, List<MobData> mobs) {
        this.tickCount = tickCount;
        this.mobs = mobs;
    }
}