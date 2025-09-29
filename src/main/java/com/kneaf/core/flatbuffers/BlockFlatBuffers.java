package com.kneaf.core.flatbuffers;

import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.Table;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * FlatBuffers serialization for BlockEntityData structures
 */
public class BlockFlatBuffers {
    
    private BlockFlatBuffers() {
        // Utility class
    }
    
    public static ByteBuffer serializeBlockInput(long tickCount, java.util.List<com.kneaf.core.data.BlockEntityData> blocks) {
        // Estimate buffer size based on input list to minimize reallocations
        // Base size + estimated size per block + string overhead
        int estimatedSize = 1024 +
                           blocks.size() * 40 +   // ~40 bytes per block (id, float, 3 ints, string ref)
                           blocks.size() * 16;    // string overhead estimate
        FlatBufferBuilder builder = new FlatBufferBuilder(estimatedSize);
        
        // Serialize blocks
        int[] blockOffsets = new int[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            com.kneaf.core.data.BlockEntityData block = blocks.get(i);
            int blockTypeOffset = builder.createString(block.blockType());
            blockOffsets[i] = createBlockEntityData(builder, block.id(), (float) block.distance(), blockTypeOffset,
                                                  block.x(), block.y(), block.z());
        }
        int blocksOffset = builder.createVectorOfTables(blockOffsets);
        
        // Create block config
        int configOffset = createBlockConfig(builder, 8, 16, 0.1f, 0.05f);
        
        // Create BlockInput
        startBlockInput(builder);
        addTickCount(builder, tickCount);
        addBlocks(builder, blocksOffset);
        addBlockConfig(builder, configOffset);
        int inputOffset = endBlockInput(builder);
        
        builder.finish(inputOffset);
        return builder.dataBuffer();
    }
    
    public static java.util.List<com.kneaf.core.data.BlockEntityData> deserializeBlockProcessResult(ByteBuffer buffer) {
        buffer.rewind();
        BlockProcessResult result = BlockProcessResult.getRootAsBlockProcessResult(buffer);
        
        java.util.List<com.kneaf.core.data.BlockEntityData> updatedBlocks = new java.util.ArrayList<>();
        for (int i = 0; i < result.updatedBlocksLength(); i++) {
            BlockProcessResult.BlockUpdate blockUpdate = result.updatedBlocks(i);
            com.kneaf.core.data.BlockEntityData blockData = new com.kneaf.core.data.BlockEntityData(
                blockUpdate.id(),
                blockUpdate.distance(),
                "", // blockType will be set from original
                blockUpdate.x(), blockUpdate.y(), blockUpdate.z()
            );
            updatedBlocks.add(blockData);
        }
        return updatedBlocks;
    }
    
    // BlockEntityData methods
    public static int createBlockEntityData(FlatBufferBuilder builder, long id, float distance, int blockType,
                                          int x, int y, int z) {
        startBlockEntityData(builder);
        addBlockId(builder, id);
        addDistance(builder, distance);
        addBlockType(builder, blockType);
        addBlockX(builder, x);
        addBlockY(builder, y);
        addBlockZ(builder, z);
        return endBlockEntityData(builder);
    }
    
    public static void startBlockEntityData(FlatBufferBuilder builder) { builder.startTable(5); }
    public static void addBlockId(FlatBufferBuilder builder, long id) { builder.addLong(0, id, 0); }
    public static void addDistance(FlatBufferBuilder builder, float distance) { builder.addFloat(1, distance, 0); }
    public static void addBlockType(FlatBufferBuilder builder, int blockType) { builder.addOffset(2, blockType, 0); }
    public static void addBlockX(FlatBufferBuilder builder, int x) { builder.addInt(3, x, 0); }
    public static void addBlockY(FlatBufferBuilder builder, int y) { builder.addInt(4, y, 0); }
    public static void addBlockZ(FlatBufferBuilder builder, int z) { builder.addInt(5, z, 0); }
    public static int endBlockEntityData(FlatBufferBuilder builder) { return builder.endTable(); }
    
    // BlockConfig methods
    public static int createBlockConfig(FlatBufferBuilder builder, int maxRedstonePower, int tickRadius, 
                                      float tickChance, float updateChance) {
        startBlockConfig(builder);
        addMaxRedstonePower(builder, maxRedstonePower);
        addTickRadius(builder, tickRadius);
        addTickChance(builder, tickChance);
        addUpdateChance(builder, updateChance);
        return endBlockConfig(builder);
    }
    
    public static void startBlockConfig(FlatBufferBuilder builder) { builder.startTable(4); }
    public static void addMaxRedstonePower(FlatBufferBuilder builder, int maxRedstonePower) { builder.addInt(0, maxRedstonePower, 0); }
    public static void addTickRadius(FlatBufferBuilder builder, int tickRadius) { builder.addInt(1, tickRadius, 0); }
    public static void addTickChance(FlatBufferBuilder builder, float tickChance) { builder.addFloat(2, tickChance, 0); }
    public static void addUpdateChance(FlatBufferBuilder builder, float updateChance) { builder.addFloat(3, updateChance, 0); }
    public static int endBlockConfig(FlatBufferBuilder builder) { return builder.endTable(); }
    
    // BlockInput methods
    public static void startBlockInput(FlatBufferBuilder builder) { builder.startTable(3); }
    public static void addTickCount(FlatBufferBuilder builder, long tickCount) { builder.addLong(0, tickCount, 0); }
    public static void addBlocks(FlatBufferBuilder builder, int blocks) { builder.addOffset(1, blocks, 0); }
    public static void addBlockConfig(FlatBufferBuilder builder, int blockConfig) { builder.addOffset(2, blockConfig, 0); }
    public static int endBlockInput(FlatBufferBuilder builder) { return builder.endTable(); }
    
    // BlockProcessResult methods
    public static class BlockProcessResult extends Table {
        public static BlockProcessResult getRootAsBlockProcessResult(ByteBuffer buffer) { return getRootAsBlockProcessResult(buffer, new BlockProcessResult()); }
        public static BlockProcessResult getRootAsBlockProcessResult(ByteBuffer buffer, BlockProcessResult obj) { buffer.order(ByteOrder.LITTLE_ENDIAN); return (obj.assign(buffer.getInt(buffer.position()) + buffer.position(), buffer)); }
        public void init(int index, ByteBuffer buffer) { __reset(index, buffer); }
        public BlockProcessResult assign(int index, ByteBuffer buffer) { init(index, buffer); return this; }
        
        public BlockUpdate updatedBlocks(int j) { return updatedBlocks(new BlockUpdate(), j); }
        public BlockUpdate updatedBlocks(BlockUpdate obj, int j) { int o = __offset(4); return o != 0 ? obj.assign(__indirect(__vector(o) + j * 4), bb) : null; }
        public int updatedBlocksLength() { int o = __offset(4); return o != 0 ? __vector_len(o) : 0; }
        
        public static class BlockUpdate extends Table {
            public BlockUpdate() {
                // Empty constructor for FlatBuffers
            }
            public BlockUpdate assign(int index, ByteBuffer buffer) { init(index, buffer); return this; }
            public void init(int index, ByteBuffer buffer) { __reset(index, buffer); }
            public long id() { int o = __offset(4); return o != 0 ? bb.getLong(o + bb_pos) : 0; }
            public float distance() { int o = __offset(6); return o != 0 ? bb.getFloat(o + bb_pos) : 0; }
            public int x() { int o = __offset(8); return o != 0 ? bb.getInt(o + bb_pos) : 0; }
            public int y() { int o = __offset(10); return o != 0 ? bb.getInt(o + bb_pos) : 0; }
            public int z() { int o = __offset(12); return o != 0 ? bb.getInt(o + bb_pos) : 0; }
        }
    }
}