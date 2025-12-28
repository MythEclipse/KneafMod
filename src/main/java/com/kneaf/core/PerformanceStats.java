package com.kneaf.core;

/**
 * Centralized registry for real-time performance statistics.
 * Populated by various Mixins/Systems, read by DebugOverlayHandler (F3).
 */
public class PerformanceStats {

    // ChunkMapMixin
    public static volatile double chunkMapLoadRate;
    public static volatile double chunkMapPriorRate;
    public static volatile double chunkMapDelayRate;

    // ChunkGeneratorMixin
    public static volatile double chunkGenRate;
    public static volatile double chunkGenAvgMs;
    public static volatile double chunkGenParallelRate;
    public static volatile double chunkGenSkippedRate;

    // CompoundTagMixin (NBT)
    public static volatile double nbtTagRate;
    public static volatile double nbtAccessRate;
    public static volatile int nbtKeysInterned;
    public static volatile double nbtEmptySkipRate;

    // VoxelShapeCacheMixin
    public static volatile double voxelHitRate;
    public static volatile double voxelMissRate;
    public static volatile double voxelHitPercent;
    public static volatile double voxelFastPathRate;
    public static volatile int voxelCacheSize;

    // NoiseChunkGeneratorMixin
    public static volatile double noiseGenRate;
    public static volatile double noiseGenAvgMs;
    public static volatile double noiseGenDelayRate;

    // PlayerChunkLoaderMixin
    public static volatile double playerChunkMoveRate;
    public static volatile double playerChunkSkipRate;
    public static volatile double playerChunkSavedPercent;

    // ChunkProcessor
    public static volatile long chunkThroughput;
    public static volatile int chunkActiveThreads;

    // ServerLevelMixin
    public static volatile int entityCacheSize;
    // TPS is in TPSTracker

    // WalkNodeEvaluatorMixin
    public static volatile double walkPathQueries;
    public static volatile double walkCacheHits;
    public static volatile double walkAirSkips;

    // ChunkDataPacketMixin
    public static volatile double chunkPacketCreated;
    public static volatile double chunkPacketDuplicates;
    public static volatile double chunkPacketHot;
    public static volatile double chunkPacketEmpty;

    // PoiManagerMixin
    public static volatile double poiHits;
    public static volatile double poiMisses;
    public static volatile double poiSkipped;

    // EntityTrackerMixin
    public static volatile double trackerUpdates;
    public static volatile double trackerSkipped;

    // BiomeSourceMixin
    public static volatile double biomeCacheHits;
    public static volatile double biomeCacheMisses;

    // ScheduledTickMixin
    public static volatile double tickScheduled;
    public static volatile double tickSkipped;

    // WorldBorderMixin
    public static volatile double borderChecks;
    public static volatile double borderSkipped;

    // ConnectionMixin
    public static volatile double netPackets;
    public static volatile double netBatches;
    public static volatile double netCoalesced;

    // ItemEntityMixin
    public static volatile double itemMerges;
    public static volatile double itemSkipped;

    // BlockEntityMixin
    public static volatile double beChanges;
    public static volatile double beSkipped;

    // LightEngineMixin
    public static volatile double lightCacheHitPercent;
    public static volatile double lightSkipped;
    public static volatile double lightRustBatches;

    // BlockStatePropertyMapMixin
    public static volatile double blockStateCount;
    public static volatile double blockStateDedup;
    public static volatile double blockStateCached;

    // HeightmapMixin
    public static volatile double heightmapUpdates;
    public static volatile double heightmapCoalesced;
    public static volatile double heightmapCacheHitPercent;

    // RecipeManagerMixin
    public static volatile double recipeLookups;
    public static volatile double recipeHitPercent;
    public static volatile int recipeCacheSize;

    // ChunkStorageMixin
    public static volatile double storageReads;
    public static volatile double storageHitPercent;
    public static volatile double storageWritesSkipped;

    // StructureCacheMixin
    public static volatile double structureLookups;
    public static volatile double structureHitPercent;
    public static volatile int structureCached;
    public static volatile int structureEmptyCached;

    // FluidTickMixin
    public static volatile double fluidTicks;
    public static volatile double fluidSkippedPercent;
    public static volatile int fluidRustBatches;

    // ServerChunkCacheMixin
    public static volatile double chunkCacheAccesses;
    public static volatile double chunkCacheHitPercent;

    // BlockEntityBatchMixin
    public static volatile double beBatchTicks;
    public static volatile double beBatched;
    public static volatile double beBatchSkipped;

    // ThreadedAnvilMixin
    public static volatile double anvilSaved;
    public static volatile double anvilSkipped;
    public static volatile double anvilThrottled;

    // RegionFileStorageMixin
    public static volatile double regionReads;
    public static volatile double regionWrites;
    public static volatile double regionSkipped;

    // RandomTickMixin
    public static volatile double randomTickChunks;
    public static volatile double randomTickSkippedPercent;
    public static volatile double randomTicks;

    // EntityMixin
    public static volatile double entityPushOptimized;
    public static volatile double entityPushSkipped;
}
