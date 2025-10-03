package com.kneaf.core.performance;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.HashMap;

/**
 * Compatibility shim for tests and legacy callers. Delegates to
 * com.kneaf.core.performance.bridge.NativeBridge to keep the old package API.
 */
public final class NativeBridge {
    private NativeBridge() {}

    public static void initRustAllocator() { com.kneaf.core.performance.bridge.NativeBridge.initRustAllocator(); }

    public static long nativeCreateWorker(int concurrency) { return com.kneaf.core.performance.bridge.NativeBridge.nativeCreateWorker(concurrency); }
    public static void nativePushTask(long workerHandle, byte[] payload) { com.kneaf.core.performance.bridge.NativeBridge.nativePushTask(workerHandle, payload); }
    public static void nativePushTaskBuffer(long workerHandle, ByteBuffer payload) { com.kneaf.core.performance.bridge.NativeBridge.nativePushTaskBuffer(workerHandle, payload); }
    public static byte[] nativePollResult(long workerHandle) { return com.kneaf.core.performance.bridge.NativeBridge.nativePollResult(workerHandle); }
    public static ByteBuffer nativePollResultBuffer(long workerHandle) { return com.kneaf.core.performance.bridge.NativeBridge.nativePollResultBuffer(workerHandle); }
    public static void nativeDestroyWorker(long workerHandle) { com.kneaf.core.performance.bridge.NativeBridge.nativeDestroyWorker(workerHandle); }

    public static void nativePushBatch(long workerHandle, byte[][] payloads, int batchSize) { com.kneaf.core.performance.bridge.NativeBridge.nativePushBatch(workerHandle, payloads, batchSize); }
    public static void nativePushBatchBuffer(long workerHandle, ByteBuffer[] payloads, int batchSize) { com.kneaf.core.performance.bridge.NativeBridge.nativePushBatchBuffer(workerHandle, payloads, batchSize); }
    public static byte[][] nativePollBatchResults(long workerHandle, int maxResults) { return com.kneaf.core.performance.bridge.NativeBridge.nativePollBatchResults(workerHandle, maxResults); }
    public static ByteBuffer[] nativePollBatchResultsBuffer(long workerHandle, int maxResults) { return com.kneaf.core.performance.bridge.NativeBridge.nativePollBatchResultsBuffer(workerHandle, maxResults); }

    public static ByteBuffer nativeAllocateBuffer(int size) { return com.kneaf.core.performance.bridge.NativeBridge.nativeAllocateBuffer(size); }
    public static void nativeFreeBuffer(ByteBuffer buffer) { com.kneaf.core.performance.bridge.NativeBridge.nativeFreeBuffer(buffer); }
    public static long nativeGetBufferAddress(ByteBuffer buffer) { return com.kneaf.core.performance.bridge.NativeBridge.nativeGetBufferAddress(buffer); }

    public static long nativeGetWorkerQueueDepth(long workerHandle) { return com.kneaf.core.performance.bridge.NativeBridge.nativeGetWorkerQueueDepth(workerHandle); }
    public static double nativeGetWorkerAvgProcessingMs(long workerHandle) { return com.kneaf.core.performance.bridge.NativeBridge.nativeGetWorkerAvgProcessingMs(workerHandle); }
    public static long nativeGetWorkerMemoryUsage(long workerHandle) { return com.kneaf.core.performance.bridge.NativeBridge.nativeGetWorkerMemoryUsage(workerHandle); }

    // Convenience wrappers mirroring the enhanced API
    public static long createOptimizedWorker(int concurrency) { return com.kneaf.core.performance.bridge.NativeBridge.createOptimizedWorker(concurrency); }
    public static void destroyOptimizedWorker(long workerHandle) { com.kneaf.core.performance.bridge.NativeBridge.destroyOptimizedWorker(workerHandle); }
    public static void pushTaskOptimized(long workerHandle, byte[] payload) { com.kneaf.core.performance.bridge.NativeBridge.pushTaskOptimized(workerHandle, payload); }
    public static void pushBatchOptimized(long workerHandle, byte[][] payloads) { com.kneaf.core.performance.bridge.NativeBridge.pushBatchOptimized(workerHandle, payloads); }
    public static byte[] pollResultOptimized(long workerHandle) { return com.kneaf.core.performance.bridge.NativeBridge.pollResultOptimized(workerHandle); }

    public static java.util.Map<String, Object> getBatchStats() { return com.kneaf.core.performance.bridge.NativeBridge.getBatchStats(); }
    public static void cleanupBufferPool() { com.kneaf.core.performance.bridge.NativeBridge.cleanupBufferPool(); }
    public static int getOptimalBatchSize(int requestedSize) { return com.kneaf.core.performance.bridge.NativeBridge.getOptimalBatchSize(requestedSize); }
}
