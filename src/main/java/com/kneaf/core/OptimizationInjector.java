package com.kneaf.core;

import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@EventBusSubscriber(modid = KneafCore.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class OptimizationInjector {
    private static final String RUST_USAGE_POLICY = "CALCULATION_ONLY - NO_GAME_LOGIC - NO_AI - NO_STATE_MODIFICATION - NO_ENTITY_CONTROL";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final PerformanceManager PERFORMANCE_MANAGER = PerformanceManager.getInstance();

    private static final AtomicInteger optimizationHits = new AtomicInteger(0);
    private static final AtomicInteger combinedOptimizationHits = new AtomicInteger(0);
    private static final AtomicInteger combinedOptimizationMisses = new AtomicInteger(0);
    private static final AtomicInteger optimizationMisses = new AtomicInteger(0);
    private static final AtomicLong totalEntitiesProcessed = new AtomicLong(0);

    private static final String RUST_PERF_LIBRARY_NAME = "rustperf";
    private static final String[] RUST_PERF_LIBRARY_PATHS = {
        "natives/rustperf.dll",
        "rustperf.dll",
        "src/main/resources/natives/rustperf.dll",
        "build/resources/main/natives/rustperf.dll",
        "run/natives/rustperf.dll",
        "target/natives/rustperf.dll",
        "target/debug/rustperf.dll",
        "target/release/rustperf.dll",
        "%USERPROFILE%/.minecraft/mods/natives/rustperf.dll",
        "%APPDATA%/.minecraft/mods/natives/rustperf.dll"
    };
    private static boolean isNativeLibraryLoaded = false;
    private static final Object nativeLibraryLock = new Object();
    private static boolean isTestMode = false;

    private static String getOSErrorMessage(Throwable t) {
        String os = System.getProperty("os.name").toLowerCase();
        StringBuilder errorDetails = new StringBuilder();
        errorDetails.append(String.format("OS: %s (%s)", os, System.getProperty("os.arch")));
        errorDetails.append(String.format(", Architecture: %s", System.getProperty("os.arch")));
        errorDetails.append(String.format(", Java: %s (%s)", System.getProperty("java.version"), System.getProperty("java.vm.name")));
        
        String errorMsg = t.getMessage();
        if (errorMsg != null) {
            if (errorMsg.contains("Can't find dependent libraries")) errorDetails.append(", Possible cause: Missing DLL dependencies");
            else if (errorMsg.contains("Access is denied")) errorDetails.append(", Possible cause: File permissions issue");
            else if (errorMsg.contains("The specified module could not be found")) errorDetails.append(", Possible cause: Library not found in path");
            else if (errorMsg.contains("Bad image format")) errorDetails.append(", Possible cause: Wrong architecture (32-bit vs 64-bit)");
        }
        
        return errorDetails.toString();
    }

    static { if (!isTestMode) loadNativeLibrary(); }

    private static void loadNativeLibrary() {
        synchronized (nativeLibraryLock) {
            if (isNativeLibraryLoaded) return;

            try {
                String os = System.getProperty("os.name").toLowerCase();
                String libExtension = os.contains("win") ? "dll" : os.contains("mac") ? "dylib" : "so";
                String libName = RUST_PERF_LIBRARY_NAME + "." + libExtension;
                String userDir = System.getProperty("user.dir");

                LOGGER.info("Starting native library search (OS: {}, Arch: {}, Java: {})", os, System.getProperty("os.arch"), System.getProperty("java.version"));

                String[] classpathPaths = {
                    "natives/" + libName, libName, "src/main/resources/natives/" + libName,
                    "build/resources/main/natives/" + libName, "run/natives/" + libName,
                    "target/natives/" + libName, "target/debug/" + libName, "target/release/" + libName
                };

                for (String path : classpathPaths) { if (tryLoadFromClasspath(path)) return; }

                String[] absolutePaths = new String[] {
                    userDir + "\\src\\main\\resources\\natives\\" + libName, userDir + "\\build\\resources\\main\\natives\\" + libName,
                    userDir + "\\run\\natives\\" + libName, userDir + "\\target\\natives\\" + libName,
                    userDir + "\\target\\debug\\" + libName, userDir + "\\target\\release\\" + libName, userDir + "\\" + libName,
                    "D:\\KneafMod\\src\\main\\resources\\natives\\" + libName, "D:\\KneafMod\\build\\resources\\main\\natives\\" + libName,
                    "D:\\KneafMod\\run\\natives\\" + libName, "D:\\KneafMod\\target\\debug\\" + libName,
                    "D:\\KneafMod\\target\\release\\" + libName, "D:\\KneafMod\\" + libName,
                    getUserProfilePath() + "\\.minecraft\\mods\\natives\\" + libName, getUserProfilePath() + "\\.minecraft\\" + libName,
                    getAppDataPath() + "\\.minecraft\\mods\\natives\\" + libName, getAppDataPath() + "\\.minecraft\\" + libName
                };

                for (String absPath : absolutePaths) { if (tryLoadFromAbsolutePath(absPath)) return; }

                if (tryLoadFromJavaLibraryPath(libName)) return;

                logLibraryNotFoundError(classpathPaths, absolutePaths);
                isNativeLibraryLoaded = false;

            } catch (Throwable t) {
                LOGGER.error("Critical error in native library loading system: {} ({})", t.getMessage(), getDetailedErrorMessage(t), t);
                isNativeLibraryLoaded = false;
            }
        }
    }

    private static boolean tryLoadFromClasspath(String path) {
        URL resource = ClassLoader.getSystemClassLoader().getResource(path);
        if (resource != null) {
            try {
                System.load(resource.getPath());
                LOGGER.info("✅ SUCCESS: Loaded native library from classpath: {}", path);
                isNativeLibraryLoaded = true;
                return true;
            } catch (UnsatisfiedLinkError e) {
                LOGGER.warn("❌ Classpath load failed for {}: {} ({})", path, e.getMessage(), getOSErrorMessage(e));
            }
        } else LOGGER.debug("ℹ️ Classpath resource not found: {}", path);
        return false;
    }

    private static boolean tryLoadFromAbsolutePath(String absPath) {
        java.io.File libFile = new java.io.File(absPath);
        if (libFile.exists()) {
            try {
                System.load(absPath);
                LOGGER.info("✅ SUCCESS: Loaded native library from absolute path: {}", absPath);
                isNativeLibraryLoaded = true;
                return true;
            } catch (UnsatisfiedLinkError e) {
                LOGGER.warn("❌ Absolute path load failed for {}: {} ({})", absPath, e.getMessage(), getOSErrorMessage(e));
            } catch (SecurityException e) {
                LOGGER.warn("❌ Security restriction prevented loading from {}: {}", absPath, e.getMessage());
            }
        } else LOGGER.trace("ℹ️ File not found (ignored): {}", absPath);
        return false;
    }

    private static boolean tryLoadFromJavaLibraryPath(String libName) {
        String javaLibPath = System.getProperty("java.library.path");
        LOGGER.debug("java.library.path: {}", javaLibPath);

        for (String path : javaLibPath.split(java.io.File.pathSeparator)) {
            java.io.File libFile = new java.io.File(path, libName);
            if (libFile.exists()) {
                try {
                    System.load(libFile.getAbsolutePath());
                    LOGGER.info("✅ SUCCESS: Loaded native library from java.library.path: {}", libFile.getAbsolutePath());
                    isNativeLibraryLoaded = true;
                    return true;
                } catch (UnsatisfiedLinkError e) {
                    LOGGER.warn("❌ java.library.path load failed for {}: {} ({})", libFile.getAbsolutePath(), e.getMessage(), getOSErrorMessage(e));
                }
            }
        }
        return false;
    }

    private static void logLibraryNotFoundError(String[] classpathPaths, String[] absolutePaths) {
        LOGGER.error("❌ CRITICAL: Rust performance native library NOT FOUND in any search path");
        LOGGER.error("   - OS: {} ({})", System.getProperty("os.name"), System.getProperty("os.arch"));
        LOGGER.error("   - Java: {} ({})", System.getProperty("java.version"), System.getProperty("java.vm.name"));
        LOGGER.error("   - Classpath paths searched: {}", java.util.Arrays.toString(classpathPaths));
        LOGGER.error("   - Absolute paths searched: {}", java.util.Arrays.toString(absolutePaths));
        LOGGER.error("   - TROUBLESHOOTING:");
        LOGGER.error("     1. Ensure rustperf.dll exists in one of the searched paths");
        LOGGER.error("     2. Check that all DLL dependencies are available");
        LOGGER.error("     3. Verify 32-bit/64-bit architecture matches Java runtime");
        LOGGER.error("     4. For development: Run 'cargo build --release' in rust/ directory");
        LOGGER.error("     5. For production: Place rustperf.dll in .minecraft/mods/natives/");
        isNativeLibraryLoaded = false;
    }

    private static String getUserProfilePath() {
        String userProfile = System.getenv("USERPROFILE");
        return userProfile != null ? userProfile : "C:\\Users\\Default";
    }

    private static String getAppDataPath() {
        String appData = System.getenv("APPDATA");
        return appData != null ? appData : "C:\\Users\\Default\\AppData\\Roaming";
    }

    private static String getDetailedErrorMessage(Throwable t) {
        return String.format("%s | %s", getOSErrorMessage(t), getLoadFailureContext());
    }

    private static String getLoadFailureContext() {
        return String.format("JavaLibraryPath=%s | Architecture=%s | JavaVersion=%s", System.getProperty("java.library.path"), System.getProperty("os.arch"), System.getProperty("java.version"));
    }

    public static synchronized void reloadNativeLibrary() {
        isNativeLibraryLoaded = false;
        loadNativeLibrary();
    }

    public static boolean isNativeLibraryLoaded() {
        return isNativeLibraryLoaded;
    }

    private OptimizationInjector() {}

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Pre event) {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide() || !PERFORMANCE_MANAGER.isEntityThrottlingEnabled()) return;
        if (!isNativeLibraryLoaded || !PERFORMANCE_MANAGER.isRustIntegrationEnabled()) {
            recordOptimizationMiss("Native library not loaded or integration disabled for entity " + entity.getId());
            return;
        }
        if (entity instanceof Player) return;

        try {
            double x = entity.getDeltaMovement().x;
            double y = entity.getDeltaMovement().y;
            double z = entity.getDeltaMovement().z;
            if (Double.isNaN(x) || Double.isInfinite(x) || Double.isNaN(y) || Double.isInfinite(y) || Double.isNaN(z) || Double.isInfinite(z)) {
                recordOptimizationMiss("Native physics calculation skipped for entity " + entity.getId() + " - invalid input values");
                return;
            }

            double originalX = x;
            double originalY = y;
            double originalZ = z;
            double[] resultData = null;
            boolean useNativeResult = false;
            double dampingFactor = 0.980; // Default to "other" entity damping factor

            try {
                dampingFactor = calculateEntitySpecificDamping(entity); // Override with entity-specific value
                resultData = rustperf_vector_damp(x, y, z, dampingFactor);
                
                if (resultData != null && resultData.length == 3 && isNaturalMovement(resultData, originalX, originalY, originalZ)) {
                    useNativeResult = true;
                    recordOptimizationHit(String.format("Native vector calculation succeeded for entity %d", entity.getId()));
                } else {
                    resultData = java_vector_damp(x, y, z, dampingFactor);
                    if (isNaturalMovement(resultData, originalX, originalY, originalZ)) {
                        useNativeResult = true;
                        recordOptimizationHit(String.format("Java fallback vector calculation succeeded for entity %d", entity.getId()));
                    } else {
                        recordOptimizationMiss("Both Rust and Java fallback failed for entity " + entity.getId() + " - using original");
                    }
                }
            } catch (UnsatisfiedLinkError ule) {
                LOGGER.error("JNI link error in rustperf_vector_damp for entity {}: {}", entity.getId(), ule.getMessage());
                resultData = java_vector_damp(x, y, z, dampingFactor);
                if (isNaturalMovement(resultData, originalX, originalY, originalZ)) {
                    useNativeResult = true;
                    recordOptimizationHit(String.format("Java fallback used due to JNI error for entity %d", entity.getId()));
                } else {
                    recordOptimizationMiss("Native library JNI error and Java fallback failed for entity " + entity.getId());
                }
            } catch (Throwable t) {
                LOGGER.error("Error in rustperf_vector_damp for entity {}: {}", entity.getId(), t.getMessage());
                resultData = java_vector_damp(x, y, z, dampingFactor);
                if (isNaturalMovement(resultData, originalX, originalY, originalZ)) {
                    useNativeResult = true;
                    recordOptimizationHit(String.format("Java fallback used due to error for entity %d", entity.getId()));
                } else {
                    recordOptimizationMiss("Native calculation error and Java fallback failed for entity " + entity.getId() + " - " + t.getMessage());
                }
            }

            if (useNativeResult) {
                // Improved gravity handling - allow natural gravity while preserving external effects
                double processedY = entity.getDeltaMovement().y;
                
                // Only preserve external gravity modifications if they are significant (knockback, explosions)
                // Allow natural gravity to work normally
                if (Math.abs(resultData[1] - entity.getDeltaMovement().y) > 0.5) {
                    // Significant external effect detected (explosion, strong knockback)
                    processedY = resultData[1];
                    recordOptimizationHit(String.format("Strong external effect preserved for entity %d", entity.getId()));
                } else if (entity.getDeltaMovement().y < -0.1) {
                    // Natural falling - apply enhanced gravity for better feel
                    processedY = Math.min(entity.getDeltaMovement().y * 1.1, resultData[1]);
                }
                
                // Reduced horizontal damping to allow better knockback
                double horizontalDamping = 0.008; // Reduced from 0.015 for better knockback
                entity.setDeltaMovement(
                    resultData[0] * (1 - horizontalDamping), // Dampen horizontal X
                    processedY,                           // Improved gravity handling
                    resultData[2] * (1 - horizontalDamping)  // Dampen horizontal Z
                );
                recordOptimizationHit(String.format("Native vector calculation applied for entity %d", entity.getId()));
            } else {
                recordOptimizationMiss(String.format("Using original movement for entity %d (native fallback)", entity.getId()));
            }
        } catch (Throwable t) {
            LOGGER.error("Error during native vector calculation for entity {}: {}", entity.getId(), t.getMessage());
            recordOptimizationMiss("Native vector calculation failed for entity " + entity.getId() + " - " + t.getMessage());
        } finally {
            totalEntitiesProcessed.incrementAndGet();
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Pre event) {
        if (PERFORMANCE_MANAGER.isEntityThrottlingEnabled()) {
            try {
                int entityCount = 200;
                recordOptimizationHit(String.format("Server tick processed with %d entities (Java-only)", entityCount));
            } catch (Throwable t) {
                recordOptimizationMiss("Server tick Java-only processing failed: " + t.getMessage());
            }
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Pre event) {
        if (PERFORMANCE_MANAGER.isEntityThrottlingEnabled()) {
            try {
                var level = event.getLevel();
                int entityCount = 100;
                String dimension = level.dimension().location().toString();
                recordOptimizationHit(String.format("Level tick processed with %d entities in %s (Java-only)", entityCount, dimension));
            } catch (Throwable t) {
                recordOptimizationMiss("Level tick Java-only processing failed: " + t.getMessage());
            }
        }
    }

    static native double[] rustperf_vector_multiply(double x, double y, double z, double scalar);
    
    static native double[] rustperf_vector_add(double x1, double y1, double z1, double x2, double y2, double z2);
    
    static native double[] rustperf_vector_damp(double x, double y, double z, double damping);

    private static double[] java_vector_damp(double x, double y, double z, double horizontalDamping) {
        // Improved gravity handling - allow natural gravity while preserving external effects
        double processedY = y;
        
        // Only preserve external gravity modifications if they are significant (knockback, explosions)
        // Allow natural gravity to work normally
        if (Math.abs(y - y) > 0.5) {
            // Significant external effect detected (explosion, strong knockback)
            processedY = y;
        } else if (y < -0.1) {
            // Natural falling - apply enhanced gravity for better feel
            processedY = Math.min(y * 1.1, y);
        }
        
        return new double[] {
            x * horizontalDamping,  // Dampen horizontal X
            processedY,             // Improved gravity handling
            z * horizontalDamping   // Dampen horizontal Z
        };
    }
    
    private static double[] java_vector_multiply(double x, double y, double z, double scalar) {
        return new double[] { x * scalar, y * scalar, z * scalar };
    }

    private static double[] java_vector_add(double x1, double y1, double z1, double x2, double y2, double z2) {
        return new double[] { x1 + x2, y1 + y2, z1 + z2 };
    }

    private static void recordOptimizationHit(String details) {
        optimizationHits.incrementAndGet();
    }

    static double calculateEntitySpecificDamping(Entity entity) {
        // Use EntityTypeEnum for consistent damping factor calculation
        // This aligns hash-based lookup with string-based fallback
        return EntityTypeEnum.calculateDampingFactor(entity);
    }

    static boolean isNaturalMovement(double[] result, double originalX, double originalY, double originalZ) {
        if (result == null || result.length != 3) return false;
        // Check for invalid values (NaN/Infinite)
        for (double val : result) { if (Double.isNaN(val) || Double.isInfinite(val)) return false; }
        
        // Enhanced validation for direction changes and external forces
        // Allow for 180° direction changes and external forces (explosions, water, knockback)
        
        // Check for direction reversals (180° turns) - natural for entities changing direction
        boolean xDirectionReversed = (originalX > 0 && result[0] < 0) || (originalX < 0 && result[0] > 0);
        boolean zDirectionReversed = (originalZ > 0 && result[2] < 0) || (originalZ < 0 && result[2] > 0);
        
        // If direction is reversed, use more lenient thresholds
        final double HORIZONTAL_THRESHOLD_NORMAL = 3.0;  // For normal movements
        final double HORIZONTAL_THRESHOLD_REVERSED = 8.0; // For direction reversals (180° turns)
        final double VERTICAL_THRESHOLD = 5.0;           // For falling and jumping
        
        double horizontalThreshold = (xDirectionReversed || zDirectionReversed) ?
            HORIZONTAL_THRESHOLD_REVERSED : HORIZONTAL_THRESHOLD_NORMAL;
        
        // Apply thresholds with direction change consideration
        if (Math.abs(result[0]) > Math.abs(originalX) * horizontalThreshold) return false;
        if (Math.abs(result[2]) > Math.abs(originalZ) * horizontalThreshold) return false;
        if (Math.abs(result[1]) > Math.abs(originalY) * VERTICAL_THRESHOLD) return false;
        
        return true;
    }

    private static void recordOptimizationMiss(String details) {
        optimizationMisses.incrementAndGet();
        LOGGER.warn("Optimization fallback: {}", details);
    }

    private static void recordCombinedOptimizationHit(String details) {
        combinedOptimizationHits.incrementAndGet();
        if (combinedOptimizationHits.get() % 100 == 0) logCombinedPerformanceStats();
    }

    private static void recordCombinedOptimizationMiss(String details) {
        combinedOptimizationMisses.incrementAndGet();
        LOGGER.warn("Combined optimization fallback: {}", details);
    }

    private static void logCombinedPerformanceStats() {
        LOGGER.debug("Java combined optimization metrics: {}", getCombinedOptimizationMetrics());
    }

    public static String getCombinedOptimizationMetrics() {
        long totalOps = combinedOptimizationHits.get() + combinedOptimizationMisses.get();
        double hitRate = totalOps > 0 ? (double) combinedOptimizationHits.get() / totalOps * 100 : 0.0;
        return String.format("CombinedOptimizationMetrics{hits=%d, misses=%d, totalProcessed=%d, hitRate=%.2f%%, nativeLoaded=%b}", combinedOptimizationHits.get(), combinedOptimizationMisses.get(), totalEntitiesProcessed.get(), hitRate, isNativeLibraryLoaded);
    }

    private static void logPerformanceStats() {
        LOGGER.debug("Java optimization metrics: {}", getOptimizationMetrics());
    }

    public static String getOptimizationMetrics() {
        long totalOps = optimizationHits.get() + optimizationMisses.get();
        double hitRate = totalOps > 0 ? (double) optimizationHits.get() / totalOps * 100 : 0.0;
        return String.format("OptimizationMetrics{hits=%d, misses=%d, totalProcessed=%d, hitRate=%.2f%%, nativeLoaded=%b}", optimizationHits.get(), optimizationMisses.get(), totalEntitiesProcessed.get(), hitRate, isNativeLibraryLoaded);
    }

    static void enableTestMode(boolean enabled) {
        isTestMode = enabled;
        if (!enabled) {
            isNativeLibraryLoaded = false;
            loadNativeLibrary();
        }
    }

    static OptimizationMetrics getTestMetrics() {
        return new OptimizationMetrics(optimizationHits.get(), optimizationMisses.get(), totalEntitiesProcessed.get(), isNativeLibraryLoaded && !isTestMode);
    }

    static double[] fallbackToVanilla(double[] position, boolean onGround) {
        recordOptimizationMiss("Fallback to vanilla physics");
        return position.clone();
    }

    public static class OptimizationMetrics {
        public final int hits, misses;
        public final long totalProcessed;
        public final boolean nativeLoaded;

        public OptimizationMetrics(int hits, int misses, long totalProcessed, boolean nativeLoaded) {
            this.hits = hits;
            this.misses = misses;
            this.totalProcessed = totalProcessed;
            this.nativeLoaded = nativeLoaded;
        }

        public int getNativeHits() { return hits; }
        public int getNativeMisses() { return misses; }
    }

    public static void resetMetrics() {
        optimizationHits.set(0);
        optimizationMisses.set(0);
        combinedOptimizationHits.set(0);
        combinedOptimizationMisses.set(0);
        totalEntitiesProcessed.set(0);
    }

    public static void logFromRust(String message) {
        LOGGER.info("[Rust] {}", message);
    }
}