package com.kneaf.core.chunkstorage;

/**
 * Compatibility shim for tests expecting com.kneaf.core.chunkstorage.RustDatabaseAdapter.
 * Delegates behavior to com.kneaf.core.chunkstorage.database.RustDatabaseAdapter.
 */
public class RustDatabaseAdapter extends com.kneaf.core.chunkstorage.database.RustDatabaseAdapter {

    public RustDatabaseAdapter(String databaseType, boolean checksumEnabled) {
        super(databaseType, checksumEnabled);
    }

    public static boolean isNativeLibraryAvailable() {
        return com.kneaf.core.chunkstorage.database.RustDatabaseAdapter.isNativeLibraryAvailable();
    }

    @Override
    public com.kneaf.core.chunkstorage.DatabaseAdapter.DatabaseStats getStats() {
        Object raw = super.getStats();
        if (raw == null) return new DatabaseAdapter.DatabaseStats(0,0,0,0,0,false,0,0,0,0,0);
        if (raw instanceof com.kneaf.core.chunkstorage.DatabaseAdapter.DatabaseStats) {
            return (com.kneaf.core.chunkstorage.DatabaseAdapter.DatabaseStats) raw;
        }
        // Try to convert from a Map<String,Object> produced by the in-memory adapter
        if (raw instanceof java.util.Map) {
            java.util.Map<?,?> map = (java.util.Map<?,?>) raw;
            long totalChunks = toLong(map.get("chunkCount"), map.get("totalChunks"));
            long totalSize = toLong(map.get("totalSize"), map.get("totalSizeBytes"));
            long readLatency = toLong(map.get("readLatencyMs"));
            long writeLatency = toLong(map.get("writeLatencyMs"));
            long lastMaint = toLong(map.get("lastMaintenanceTime"), System.currentTimeMillis());
            boolean healthy = toBoolean(map.get("healthy"), map.get("isHealthy"), true);
            long swapTotal = toLong(map.get("swapOutCount"));
            long swapFailed = toLong(map.get("swapOutFailed"));
            long swapInLatency = toLong(map.get("swapInLatencyMs"));
            long swapOutLatency = toLong(map.get("swapOutLatencyMs"));
            long mmap = toLong(map.get("memoryMappedFilesActive"));
            return new DatabaseAdapter.DatabaseStats(totalChunks, totalSize, readLatency, writeLatency, lastMaint, healthy, swapTotal, swapFailed, swapInLatency, swapOutLatency, mmap);
        }
        // Fallback empty stats
        return new DatabaseAdapter.DatabaseStats(0,0,0,0,0,false,0,0,0,0,0);
    }

    private static long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number) return ((Number)o).longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return 0L; }
    }

    private static long toLong(Object a, Object b) {
        long v = toLong(a);
        if (v != 0) return v;
        if (b == null) return 0L;
        return toLong(b);
    }

    private static boolean toBoolean(Object a, Object b, boolean def) {
        if (a != null) return Boolean.parseBoolean(a.toString());
        if (b != null) return Boolean.parseBoolean(b.toString());
        return def;
    }
}
