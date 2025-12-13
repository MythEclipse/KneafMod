package com.kneaf.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import java.io.File;
import java.net.URL;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * OptimizationInjector - Rust-powered performance optimization for Minecraft
 * entities
 * 
 * MOD COMPATIBILITY POLICY:
 * This optimization system ONLY applies to:
 * - Vanilla Minecraft entities (net.minecraft.world.entity.*)
 * - Kneaf Mod custom entities (com.kneaf.entities.*)
 * 
 * Entities from other mods are NEVER touched to ensure full compatibility.
 * This whitelist approach prevents conflicts with other mods' custom entity
 * behaviors.
 */
@EventBusSubscriber(modid = KneafCore.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class OptimizationInjector {
    private static final String RUST_USAGE_POLICY = "CALCULATION_ONLY - NO_GAME_LOGIC - NO_AI - NO_STATE_MODIFICATION - NO_ENTITY_CONTROL";
    private static final Logger LOGGER = LoggerFactory.getLogger(OptimizationInjector.class);

    private static final PerformanceManager PERFORMANCE_MANAGER = PerformanceManager.getInstance();

    private static final AtomicInteger optimizationHits = new AtomicInteger(0);
    private static final AtomicInteger combinedOptimizationHits = new AtomicInteger(0);
    private static final AtomicInteger combinedOptimizationMisses = new AtomicInteger(0);
    private static final AtomicInteger optimizationMisses = new AtomicInteger(0);
    private static final AtomicLong totalEntitiesProcessed = new AtomicLong(0);

    // ========== MULTI-THREADED ENTITY PROCESSING ==========
    // Entity tick buffer for batch processing
    private static final ConcurrentLinkedQueue<EntityTickData> entityTickBuffer = new ConcurrentLinkedQueue<>();
    private static final int BATCH_SIZE = 64; // Process entities in batches of 64
    private static final int BATCH_TIMEOUT_MS = 50; // Process batch every 50ms
    private static final AtomicLong lastBatchProcessTime = new AtomicLong(System.currentTimeMillis());
    private static final AtomicInteger batchesProcessed = new AtomicInteger(0);
    private static final AtomicLong entitiesProcessedParallel = new AtomicLong(0);

    // Background executor for batch processing
    private static final ScheduledExecutorService batchExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "EntityBatchProcessor");
        t.setDaemon(true);
        return t;
    });

    // Entity tick data holder for buffering
    private static class EntityTickData {
        final Object entity;
        final double[] movementData;
        final long timestamp;

        EntityTickData(Object entity, double[] movementData) {
            this.entity = entity;
            this.movementData = movementData;
            this.timestamp = System.currentTimeMillis();
        }
    }

    // Initialize batch processor
    static {
        batchExecutor.scheduleAtFixedRate(() -> {
            try {
                processEntityBatchAsync();
            } catch (Exception e) {
                LOGGER.debug("Batch processing error: {}", e.getMessage());
            }
        }, BATCH_TIMEOUT_MS, BATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    // ========== ASYNC-STYLE ENTITY BLACKLIST SYSTEM ==========
    // Entities that failed async processing are blacklisted and tick synchronously
    private static final Set<UUID> blacklistedEntities = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger syncFallbackCount = new AtomicInteger(0);
    private static final AtomicInteger asyncSuccessCount = new AtomicInteger(0);

    // Entity types that MUST tick synchronously (from Async mod patterns)
    // These have critical state or collision detection requirements
    private static final Set<String> SYNC_ONLY_ENTITY_TYPES = Set.of(
            "minecraft:player", // Player state critical
            "minecraft:arrow", // Projectile collision
            "minecraft:spectral_arrow", // Projectile collision
            "minecraft:trident", // Projectile collision
            "minecraft:fireball", // Projectile collision
            "minecraft:small_fireball", // Projectile collision
            "minecraft:dragon_fireball", // Projectile collision
            "minecraft:wither_skull", // Projectile collision
            "minecraft:minecart", // Rail physics
            "minecraft:chest_minecart", // Rail physics
            "minecraft:hopper_minecart", // Rail physics
            "minecraft:tnt_minecart", // Rail physics + explosion
            "minecraft:falling_block", // Block placement critical
            "minecraft:tnt", // Explosion timing critical
            "minecraft:ender_dragon", // Boss fight mechanics
            "minecraft:wither" // Boss fight mechanics
    );

    // Pending async tasks for post-tick barrier
    private static final ConcurrentLinkedQueue<CompletableFuture<?>> pendingAsyncTasks = new ConcurrentLinkedQueue<>();

    // Cache to track which entity classes have been logged to prevent spam
    private static final Set<String> loggedEntityClasses = ConcurrentHashMap.newKeySet();

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
            "%APPDATA%/.minecraft/mods/natives/rustperf.dll",
            "D:/KneafMod/src/main/resources/natives/rustperf.dll",
            "D:/KneafMod/build/resources/main/natives/rustperf.dll",
            "D:/KneafMod/run/natives/rustperf.dll",
            "D:/KneafMod/target/natives/rustperf.dll"
    };
    private static boolean isNativeLibraryLoaded = false;
    private static final Object nativeLibraryLock = new Object();
    private static boolean isTestMode = false;

    // Native method declarations
    public static native double[] rustperf_batch_entity_physics(double[] velocities, int entityCount, double damping);

    // Track if we've already logged warnings for each native method to avoid spam
    private static boolean phantomShurikenWarningLogged = false;
    private static boolean quadShadowWarningLogged = false;
    private static boolean shadowKillDamageWarningLogged = false;
    private static boolean passiveStacksWarningLogged = false;

    private static String getOSErrorMessage(Throwable t) {
        String os = System.getProperty("os.name").toLowerCase();
        StringBuilder errorDetails = new StringBuilder();
        errorDetails.append(String.format("OS: %s (%s)", os, System.getProperty("os.arch")));
        errorDetails.append(String.format(", Architecture: %s", System.getProperty("os.arch")));
        errorDetails.append(String.format(", Java: %s (%s)", System.getProperty("java.version"),
                System.getProperty("java.vm.name")));

        String errorMsg = t.getMessage();
        if (errorMsg != null) {
            if (errorMsg.contains("Can't find dependent libraries"))
                errorDetails.append(", Possible cause: Missing DLL dependencies");
            else if (errorMsg.contains("Access is denied"))
                errorDetails.append(", Possible cause: File permissions issue");
            else if (errorMsg.contains("The specified module could not be found"))
                errorDetails.append(", Possible cause: Library not found in path");
            else if (errorMsg.contains("Bad image format"))
                errorDetails.append(", Possible cause: Wrong architecture (32-bit vs 64-bit)");
        }

        return errorDetails.toString();
    }

    static {
        if (!isTestMode) {
            // Use new RustNativeLoader instead of old loading logic
            isNativeLibraryLoaded = RustNativeLoader.loadLibrary();
            if (isNativeLibraryLoaded) {
                LOGGER.info("✅ OptimizationInjector: Native library loaded via RustNativeLoader");
            } else {
                LOGGER.error("❌ OptimizationInjector: Failed to load native library");
                enableSafeMode();
            }
        }
    }

    @Deprecated
    private static void loadNativeLibrary() {
        synchronized (nativeLibraryLock) {
            if (isNativeLibraryLoaded)
                return;

            try {
                String os = System.getProperty("os.name").toLowerCase();
                String arch = System.getProperty("os.arch").toLowerCase();
                String libExtension = os.contains("win") ? "dll" : os.contains("mac") ? "dylib" : "so";
                String libName = RUST_PERF_LIBRARY_NAME + "." + libExtension;
                String userDir = System.getProperty("user.dir");

                LOGGER.info("Starting native library search (OS: {}, Arch: {}, Java: {})", os, arch,
                        System.getProperty("java.version"));
                LOGGER.info("Working directory: {}", userDir);

                // First, try classpath locations (most reliable)
                String[] classpathPaths = {
                        "natives/" + libName,
                        libName,
                        "src/main/resources/natives/" + libName,
                        "build/resources/main/natives/" + libName,
                        "run/natives/" + libName,
                        "target/natives/" + libName,
                        "target/debug/" + libName,
                        "target/release/" + libName,
                        "rust/target/release/" + libName,
                        "rust/target/debug/" + libName
                };

                LOGGER.info("Attempting to load from classpath...");
                for (String path : classpathPaths) {
                    if (tryLoadFromClasspath(path)) {
                        LOGGER.info("✅ Successfully loaded from classpath: {}", path);
                        return;
                    }
                }

                // Try absolute paths with fallback to common development locations
                String sep = File.separator;
                String[] absolutePaths = new String[] {
                        userDir + sep + "src" + sep + "main" + sep + "resources" + sep + "natives" + sep + libName,
                        userDir + sep + "build" + sep + "resources" + sep + "main" + sep + "natives" + sep + libName,
                        userDir + sep + "run" + sep + "natives" + sep + libName,
                        userDir + sep + "target" + sep + "natives" + sep + libName,
                        userDir + sep + "target" + sep + "debug" + sep + libName,
                        userDir + sep + "target" + sep + "release" + sep + libName,
                        userDir + sep + libName,

                        // Rust build output directory (prioritize release, then debug)
                        userDir + sep + "rust" + sep + "target" + sep + "release" + sep + libName,
                        userDir + sep + "rust" + sep + "target" + sep + "debug" + sep + libName,

                        // Common Minecraft mod development paths
                        getUserProfilePath() + sep + ".minecraft" + sep + "mods" + sep + "natives" + sep + libName,
                        getUserProfilePath() + sep + ".minecraft" + sep + libName,
                        getAppDataPath() + sep + ".minecraft" + sep + "mods" + sep + "natives" + sep + libName,
                        getAppDataPath() + sep + ".minecraft" + sep + libName,

                        // Architecture-specific paths
                        userDir + sep + "target" + sep + arch + sep + "natives" + sep + libName,
                        userDir + sep + "target" + sep + arch + sep + libName
                };

                LOGGER.info("Attempting to load from absolute paths...");
                for (String absPath : absolutePaths) {
                    if (tryLoadFromAbsolutePath(absPath)) {
                        LOGGER.info("✅ Successfully loaded from absolute path: {}", absPath);
                        return;
                    }
                }

                // Try Java library path as last resort
                if (tryLoadFromJavaLibraryPath(libName)) {
                    LOGGER.info("✅ Successfully loaded from java.library.path");
                    return;
                }

                // Try to build the library if not found (development only)
                if (tryBuildRustLibrary()) {
                    LOGGER.info("✅ Successfully built and loaded Rust library");
                    return;
                }

                // If all else fails, log comprehensive error and enable safe mode
                logLibraryNotFoundError(classpathPaths, absolutePaths);
                isNativeLibraryLoaded = false;
                enableSafeMode();

            } catch (SecurityException e) {
                LOGGER.error("Security exception prevented native library loading: {}", e.getMessage(), e);
                isNativeLibraryLoaded = false;
                enableSafeMode();
            } catch (UnsatisfiedLinkError ule) {
                LOGGER.error("Unsatisfied link error in native library loading: {} ({})", ule.getMessage(),
                        getOSErrorMessage(ule), ule);
                isNativeLibraryLoaded = false;
                enableSafeMode();
            } catch (Throwable t) {
                LOGGER.error("Critical error in native library loading system: {} ({})", t.getMessage(),
                        getDetailedErrorMessage(t), t);
                isNativeLibraryLoaded = false;
                enableSafeMode();
            }
        }
    }

    private static boolean tryBuildRustLibrary() {
        if (ModeDetector.isTestMode() || !isDevelopmentEnvironment()) {
            LOGGER.debug("Skipping Rust auto-build (TestMode: {}, DevEnv: {})",
                    ModeDetector.isTestMode(), isDevelopmentEnvironment());
            return false;
        }

        LOGGER.info("🔨 Attempting to build Rust library automatically...");

        try {
            File rustDir = new File("rust");
            if (!rustDir.exists() || !rustDir.isDirectory()) {
                LOGGER.warn("Rust directory not found at: {}", rustDir.getAbsolutePath());
                return false;
            }

            // Check if cargo is available
            ProcessBuilder cargoCheck = new ProcessBuilder("cargo", "--version");
            try {
                Process checkProcess = cargoCheck.start();
                int checkCode = checkProcess.waitFor();
                if (checkCode != 0) {
                    LOGGER.warn("Cargo not found. Install Rust from https://rustup.rs/");
                    return false;
                }
            } catch (Exception e) {
                LOGGER.warn("Cargo not available: {}", e.getMessage());
                return false;
            }

            // Build the library
            LOGGER.info("Building Rust library in: {}", rustDir.getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder("cargo", "build", "--release");
            pb.directory(rustDir);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Read output
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOGGER.info("[Cargo] {}", line);
                }
            }

            int exitCode = process.waitFor();

            if (exitCode == 0) {
                LOGGER.info("✅ Rust library built successfully");

                // Try to load the newly built library
                String os = System.getProperty("os.name").toLowerCase();
                String libExtension = os.contains("win") ? "dll" : os.contains("mac") ? "dylib" : "so";
                String sep = File.separator;
                String libPath = rustDir.getAbsolutePath() + sep + "target" + sep + "release" + sep
                        + RUST_PERF_LIBRARY_NAME + "." + libExtension;

                LOGGER.info("Attempting to load built library from: {}", libPath);
                return tryLoadFromAbsolutePath(libPath);
            } else {
                LOGGER.error("❌ Failed to build Rust library (exit code: {})", exitCode);
                return false;
            }
        } catch (Exception e) {
            LOGGER.error("❌ Failed to build Rust library: {}", e.getMessage(), e);
            return false;
        }
    }

    private static boolean isDevelopmentEnvironment() {
        // Check for common development indicators
        File gradlew = new File("gradlew");
        File buildGradle = new File("build.gradle");
        File cargoToml = new File("rust/Cargo.toml");

        return gradlew.exists() && buildGradle.exists() && cargoToml.exists();
    }

    private static void enableSafeMode() {
        LOGGER.warn("🔧 Entering safe mode: Native optimizations disabled");
        LOGGER.warn("   - Performance will be reduced but game will remain functional");
        LOGGER.warn("   - To resolve: Build Rust library with 'cargo build --release' and place in natives/ directory");
        LOGGER.warn("   - Current search paths: {}", Arrays.toString(RUST_PERF_LIBRARY_PATHS));
    }

    private static boolean tryLoadFromClasspath(String path) {
        try {
            // Try to load from classpath (works for development and packaged JAR)
            URL resource = OptimizationInjector.class.getClassLoader().getResource(path);

            if (resource == null) {
                // Fallback to system classloader
                resource = ClassLoader.getSystemClassLoader().getResource(path);
            }

            if (resource != null) {
                String protocol = resource.getProtocol();

                // Handle jar:file: protocol (when library is inside JAR)
                if ("jar".equals(protocol)) {
                    return extractAndLoadFromJar(resource, path);
                }

                // Handle file: protocol (when library is in filesystem)
                if ("file".equals(protocol)) {
                    String filePath = resource.getPath();
                    // Remove leading slash on Windows (e.g., /D:/path becomes D:/path)
                    if (filePath.startsWith("/") && filePath.length() > 2 && filePath.charAt(2) == ':') {
                        filePath = filePath.substring(1);
                    }
                    System.load(filePath);
                    LOGGER.info("✅ SUCCESS: Loaded native library from classpath: {}", path);
                    isNativeLibraryLoaded = true;
                    return true;
                }
            } else {
                LOGGER.debug("ℹ️ Classpath resource not found: {}", path);
            }
        } catch (UnsatisfiedLinkError e) {
            LOGGER.debug("❌ Classpath load failed for {}: {}", path, e.getMessage());
        } catch (Exception e) {
            LOGGER.debug("❌ Classpath load error for {}: {}", path, e.getMessage());
        }
        return false;
    }

    /**
     * Extract native library from JAR and load it from temporary directory
     */
    private static boolean extractAndLoadFromJar(URL resource, String resourcePath) {
        try {
            // Create temp directory for native libraries with unique name to avoid
            // conflicts
            File tempDir = new File(System.getProperty("java.io.tmpdir"),
                    "kneaf-natives-" + System.currentTimeMillis());
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }

            // Extract library to temp directory
            String libName = new File(resourcePath).getName();
            File tempLib = new File(tempDir, libName);

            // Always extract to ensure we have the latest version
            LOGGER.info("Extracting native library from JAR: {} -> {}", resourcePath, tempLib.getAbsolutePath());

            try (java.io.InputStream in = resource.openStream();
                    java.io.FileOutputStream out = new java.io.FileOutputStream(tempLib)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

            LOGGER.info("Extracted native library to: {} (size: {} bytes)", tempLib.getAbsolutePath(),
                    tempLib.length());

            // Delete temp file on exit
            tempLib.deleteOnExit();
            tempDir.deleteOnExit();

            // Load the extracted library
            System.load(tempLib.getAbsolutePath());
            LOGGER.info("✅ SUCCESS: Loaded native library from JAR: {}", resourcePath);
            isNativeLibraryLoaded = true;
            return true;

        } catch (UnsatisfiedLinkError e) {
            LOGGER.error("❌ Failed to load extracted library: {} ({})", e.getMessage(), getOSErrorMessage(e));
            return false;
        } catch (Exception e) {
            LOGGER.warn("Failed to extract/load library from JAR: {}", e.getMessage());
            return false;
        }
    }

    private static boolean tryLoadFromAbsolutePath(String absPath) {
        java.io.File libFile = new java.io.File(absPath);
        if (libFile.exists()) {
            LOGGER.info("Found library file at: {} (size: {} bytes)", absPath, libFile.length());
            try {
                System.load(libFile.getAbsolutePath());
                LOGGER.info("✅ SUCCESS: Loaded native library from absolute path: {}", absPath);
                isNativeLibraryLoaded = true;
                return true;
            } catch (UnsatisfiedLinkError e) {
                LOGGER.error("❌ Absolute path load failed for {}: {} ({})", absPath, e.getMessage(),
                        getOSErrorMessage(e));
                LOGGER.error("   This usually means the DLL exists but has missing dependencies or wrong architecture");
            } catch (SecurityException e) {
                LOGGER.error("❌ Security restriction prevented loading from {}: {}", absPath, e.getMessage());
            }
        }
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
                    LOGGER.info("✅ SUCCESS: Loaded native library from java.library.path: {}",
                            libFile.getAbsolutePath());
                    isNativeLibraryLoaded = true;
                    return true;
                } catch (UnsatisfiedLinkError e) {
                    LOGGER.warn("❌ java.library.path load failed for {}: {} ({})", libFile.getAbsolutePath(),
                            e.getMessage(), getOSErrorMessage(e));
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
        return String.format("JavaLibraryPath=%s | Architecture=%s | JavaVersion=%s",
                System.getProperty("java.library.path"), System.getProperty("os.arch"),
                System.getProperty("java.version"));
    }

    public static synchronized void reloadNativeLibrary() {
        isNativeLibraryLoaded = false;
        loadNativeLibrary();
    }

    public static boolean isNativeLibraryLoaded() {
        return isNativeLibraryLoaded;
    }

    private OptimizationInjector() {
    }

    /**
     * MULTI-THREADED ENTITY TICK PROCESSING (Async mod pattern)
     * Entities are categorized into sync-only (players, projectiles) and async-safe.
     * Async-safe entities are buffered and processed in parallel batches.
     */
    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Pre event) {
        if (!PERFORMANCE_MANAGER.isEntityThrottlingEnabled())
            return;

        // Fast path: Skip expensive processing if native optimizations are not available
        if (!isNativeLibraryLoaded || !PERFORMANCE_MANAGER.isRustIntegrationEnabled()) {
            if (totalEntitiesProcessed.get() % 1000 == 0) {
                recordOptimizationMiss("Native library not loaded or integration disabled");
            }
            return;
        }

        try {
            Object entity = event.getEntity();
            if (entity == null) {
                return;
            }

            // Perform strict validation only when needed
            if (!ModeDetector.isTestMode() && !isValidMinecraftEntity(entity)) {
                return;
            }

            // ASYNC MOD PATTERN: Check if entity should tick synchronously
            // Players, projectiles, minecarts, and blacklisted entities skip async processing
            if (shouldTickSynchronously(entity)) {
                syncFallbackCount.incrementAndGet();
                // Let vanilla handle this entity - don't buffer it
                return;
            }

            double[] movementData = getEntityMovementData(entity);
            if (movementData == null || hasInvalidMovementValues(movementData[0], movementData[1], movementData[2])) {
                return;
            }

            // Buffer entity for async batch processing
            entityTickBuffer.add(new EntityTickData(entity, movementData));
            asyncSuccessCount.incrementAndGet();

            // Trigger batch processing if buffer is full
            if (entityTickBuffer.size() >= BATCH_SIZE) {
                processEntityBatchAsync();
            }

        } catch (Throwable t) {
            LOGGER.debug("Entity buffering failed: {}", t.getMessage());
        } finally {
            totalEntitiesProcessed.incrementAndGet();
        }
    }

    /**
     * Process buffered entities in parallel using EntityProcessingService
     * This method drains the buffer and submits entities for async processing.
     * Failed entities are blacklisted for sync fallback (Async mod pattern).
     */
    private static void processEntityBatchAsync() {
        if (entityTickBuffer.isEmpty())
            return;

        // Drain buffer up to BATCH_SIZE entities
        List<EntityTickData> batch = new ArrayList<>(BATCH_SIZE);
        EntityTickData data;
        while (batch.size() < BATCH_SIZE && (data = entityTickBuffer.poll()) != null) {
            batch.add(data);
        }

        if (batch.isEmpty())
            return;

        // Process batch in parallel using EntityProcessingService
        EntityProcessingService service = EntityProcessingService.getInstance();
        List<Object> entities = new ArrayList<>(batch.size());
        List<EntityProcessingService.EntityPhysicsData> physicsDataList = new ArrayList<>(batch.size());

        for (EntityTickData tickData : batch) {
            entities.add(tickData.entity);
            physicsDataList.add(new EntityProcessingService.EntityPhysicsData(
                    tickData.movementData[0],
                    tickData.movementData[1],
                    tickData.movementData[2]));
        }

        // Submit batch for parallel processing
        List<CompletableFuture<EntityProcessingService.EntityProcessingResult>> futures = service
                .processEntityBatch(entities, physicsDataList);

        // Handle results asynchronously (don't block game thread)
        CompletableFuture<Void> batchFuture = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    int processed = futures.size();
                    entitiesProcessedParallel.addAndGet(processed);
                    batchesProcessed.incrementAndGet();

                    // Apply results back to entities
                    for (int i = 0; i < futures.size(); i++) {
                        try {
                            EntityProcessingService.EntityProcessingResult result = futures.get(i).getNow(null);
                            if (result != null && result.success) {
                                EntityTickData tickData = batch.get(i);
                                applyOptimizedMovement(tickData.entity,
                                        new double[] { result.processedData.motionX,
                                                result.processedData.motionY,
                                                result.processedData.motionZ },
                                        tickData.movementData[1]);
                                recordOptimizationHit("Parallel batch processing");
                            }
                        } catch (Exception e) {
                            // ASYNC MOD PATTERN: Blacklist entity on failure
                            EntityTickData tickData = batch.get(i);
                            blacklistEntity(tickData.entity, "Async processing failed: " + e.getMessage());
                            LOGGER.debug("Failed to apply batch result: {}", e.getMessage());
                        }
                    }
                })
                .exceptionally(e -> {
                    // Batch-level failure: blacklist all entities in batch
                    for (EntityTickData tickData : batch) {
                        blacklistEntity(tickData.entity, "Batch processing failed: " + e.getMessage());
                    }
                    LOGGER.debug("Batch processing failed: {}", e.getMessage());
                    return null;
                });

        // Track pending task for post-tick barrier
        pendingAsyncTasks.add(batchFuture);
        lastBatchProcessTime.set(System.currentTimeMillis());
    }

    /**
     * Get parallel processing statistics including async/sync breakdown
     */
    public static String getParallelProcessingStats() {
        return String.format("ParallelStats{batches=%d, entitiesParallel=%d, asyncSuccess=%d, syncFallback=%d, blacklisted=%d, bufferSize=%d}",
                batchesProcessed.get(),
                entitiesProcessedParallel.get(),
                asyncSuccessCount.get(),
                syncFallbackCount.get(),
                blacklistedEntities.size(),
                entityTickBuffer.size());
    }
    
    /**
     * Check if entity should tick synchronously (Async mod pattern)
     * Returns true for entities that have critical state or collision requirements
     */
    private static boolean shouldTickSynchronously(Object entity) {
        // Check blacklist first (entities that failed async processing)
        UUID entityUUID = getEntityUUID(entity);
        if (entityUUID != null && blacklistedEntities.contains(entityUUID)) {
            return true;
        }
        
        // Check entity type against sync-only list
        String entityTypeId = getEntityTypeId(entity);
        if (entityTypeId != null && SYNC_ONLY_ENTITY_TYPES.contains(entityTypeId)) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Extract entity UUID via reflection
     */
    private static UUID getEntityUUID(Object entity) {
        try {
            java.lang.reflect.Method getUUID = entity.getClass().getMethod("getUUID");
            return (UUID) getUUID.invoke(entity);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Extract entity type ID via reflection (e.g., "minecraft:zombie")
     */
    private static String getEntityTypeId(Object entity) {
        try {
            java.lang.reflect.Method getType = entity.getClass().getMethod("getType");
            Object entityType = getType.invoke(entity);
            if (entityType != null) {
                // Try to get the registry name
                java.lang.reflect.Method toString = entityType.getClass().getMethod("toString");
                String typeStr = (String) toString.invoke(entityType);
                // Parse "entity minecraft.zombie" -> "minecraft:zombie"
                if (typeStr.contains(".")) {
                    typeStr = typeStr.replace("entity ", "").replace(".", ":");
                }
                return typeStr;
            }
        } catch (Exception e) {
            // Fallback to class name check
        }
        return null;
    }
    
    /**
     * Add entity to blacklist (called when async processing fails)
     */
    private static void blacklistEntity(Object entity, String reason) {
        UUID uuid = getEntityUUID(entity);
        if (uuid != null) {
            blacklistedEntities.add(uuid);
            syncFallbackCount.incrementAndGet();
            LOGGER.debug("Blacklisted entity {} for async processing: {}", uuid, reason);
        }
    }
    
    /**
     * Post-tick synchronization barrier (awaits all pending async tasks)
     * Called at end of server tick to ensure all entity processing completes
     */
    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        // Await all pending async entity tasks before next tick
        CompletableFuture<?> task;
        List<CompletableFuture<?>> tasks = new ArrayList<>();
        while ((task = pendingAsyncTasks.poll()) != null) {
            tasks.add(task);
        }
        
        if (!tasks.isEmpty()) {
            try {
                CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]))
                    .orTimeout(100, TimeUnit.MILLISECONDS) // 100ms max wait
                    .exceptionally(e -> {
                        LOGGER.debug("Post-tick barrier timeout: {}", e.getMessage());
                        return null;
                    })
                    .join();
            } catch (Exception e) {
                LOGGER.debug("Post-tick barrier error: {}", e.getMessage());
            }
        }
    }

    private static double[] getEntityMovementData(Object entity) {
        try {
            java.lang.reflect.Method getDeltaMovement = entity.getClass().getMethod("getDeltaMovement");
            Object vec3 = getDeltaMovement.invoke(entity);

            if (vec3 == null)
                return null;

            java.lang.reflect.Method getX = vec3.getClass().getMethod("x");
            java.lang.reflect.Method getY = vec3.getClass().getMethod("y");
            java.lang.reflect.Method getZ = vec3.getClass().getMethod("z");

            return new double[] {
                    ((Number) getX.invoke(vec3)).doubleValue(),
                    ((Number) getY.invoke(vec3)).doubleValue(),
                    ((Number) getZ.invoke(vec3)).doubleValue()
            };
        } catch (Exception e) {
            if (ModeDetector.isTestMode()) {
                return new double[] { 0.1, -0.2, 0.05 }; // Mock realistic movement
            }
            return null;
        }
    }

    private static boolean hasInvalidMovementValues(double x, double y, double z) {
        return Double.isNaN(x) || Double.isInfinite(x) ||
                Double.isNaN(y) || Double.isInfinite(y) ||
                Double.isNaN(z) || Double.isInfinite(z);
    }

    private static boolean isValidOptimizationResult(double[] result, double originalX, double originalY,
            double originalZ) {
        if (result == null || result.length != 3)
            return false;

        for (double val : result) {
            if (Double.isNaN(val) || Double.isInfinite(val))
                return false;
        }

        // More robust validation with reasonable thresholds
        final double HORIZONTAL_THRESHOLD = 10.0;
        final double VERTICAL_THRESHOLD = 15.0;

        boolean xDirectionReversed = (originalX > 0 && result[0] < 0) || (originalX < 0 && result[0] > 0);
        boolean zDirectionReversed = (originalZ > 0 && result[2] < 0) || (originalZ < 0 && result[2] > 0);

        double horizontalThreshold = xDirectionReversed || zDirectionReversed ? HORIZONTAL_THRESHOLD * 2
                : HORIZONTAL_THRESHOLD;

        return Math.abs(result[0]) <= Math.abs(originalX) * horizontalThreshold &&
                Math.abs(result[2]) <= Math.abs(originalZ) * horizontalThreshold &&
                Math.abs(result[1]) <= Math.abs(originalY) * VERTICAL_THRESHOLD;
    }

    private static void applyOptimizedMovement(Object entity, double[] resultData, double originalY) {
        try {
            // NO DAMPING - Pure vanilla physics passthrough
            applyEntityMovement(entity, resultData[0], resultData[1], resultData[2]);

        } catch (Exception e) {
            LOGGER.debug("Failed to apply optimized movement: {}", e.getMessage());
            // Don't spam logs for expected reflection failures
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Pre event) {
        if (PERFORMANCE_MANAGER.isEntityThrottlingEnabled()) {
            try {
                // Track entity count for metrics (result used for monitoring)
                @SuppressWarnings("unused")
                int entityCount = ModeDetector.isTestMode() ? 200 : getActualEntityCount(event);
                // Silent success - no logging, just metrics

                // Implement server-side throttling when native optimizations are unavailable
                if (!isNativeLibraryLoaded && totalEntitiesProcessed.get() % 1000 == 0) {
                    LOGGER.warn(
                            "Server tick lag detected - throttling entity processing (native optimizations unavailable)");
                }
            } catch (Throwable t) {
                recordOptimizationMiss("Server tick processing failed: " + t.getMessage());
            }
        }
    }

    /**
     * Throttle entity processing to prevent server overload when native
     * optimizations are unavailable
     */
    private static final AtomicInteger entityProcessingCounter = new AtomicInteger(0);
    private static final int ENTITY_PROCESSING_THROTTLE = 3; // Process 1 in every N entities (reduced from 5 for better
                                                             // performance)

    @SubscribeEvent
    public static void onEntityTickThrottle(EntityTickEvent.Pre event) {
        if (!PERFORMANCE_MANAGER.isEntityThrottlingEnabled() || isNativeLibraryLoaded) {
            return;
        }

        // Throttle entity processing when native optimizations are unavailable
        int count = entityProcessingCounter.incrementAndGet();

        if (count > ENTITY_PROCESSING_THROTTLE) {
            entityProcessingCounter.set(0);

            // Skip processing this entity to reduce server load
            event.setCanceled(true);
            LOGGER.trace("Throttled entity processing (native optimizations unavailable)");
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Pre event) {
        // Only process on server side to avoid client-side errors
        if (!event.getLevel().isClientSide() && PERFORMANCE_MANAGER.isEntityThrottlingEnabled()) {
            try {
                // Track entity count for metrics
                getActualEntityCount(event);
                // Silent success - no logging, just metrics
            } catch (Throwable t) {
                recordOptimizationMiss("Level tick processing failed: " + t.getMessage());
            }
        }
    }

    // Native methods now centralized in RustNativeLoader
    // These are kept for backward compatibility and delegate to RustNativeLoader

    static double[] rustperf_vector_multiply(double x, double y, double z, double scalar) {
        return RustNativeLoader.rustperf_vector_multiply(x, y, z, scalar);
    }

    static double[] rustperf_vector_add(double x1, double y1, double z1, double x2, double y2, double z2) {
        return RustNativeLoader.rustperf_vector_add(x1, y1, z1, x2, y2, z2);
    }

    static double[] rustperf_vector_damp(double x, double y, double z, double damping) {
        return RustNativeLoader.rustperf_vector_damp(x, y, z, damping);
    }

    // Hayabusa skill methods for ShadowZombieNinja
    static double[] rustperf_hayabusa_phantom_shuriken(double startX, double startY, double startZ, double targetX,
            double targetY, double targetZ, double speed) {
        return RustNativeLoader.rustperf_hayabusa_phantom_shuriken(startX, startY, startZ, targetX, targetY, targetZ,
                speed);
    }

    static double[][] rustperf_hayabusa_quad_shadow(double centerX, double centerY, double centerZ, double radius) {
        return RustNativeLoader.rustperf_hayabusa_quad_shadow(centerX, centerY, centerZ, radius);
    }

    static double rustperf_hayabusa_shadow_kill_damage(int passiveStacks, double baseDamage) {
        return RustNativeLoader.rustperf_hayabusa_shadow_kill_damage(passiveStacks, baseDamage);
    }

    static int rustperf_hayabusa_calculate_passive_stacks(int currentStacks, boolean successfulHit, int maxStacks) {
        return RustNativeLoader.rustperf_hayabusa_calculate_passive_stacks(currentStacks, successfulHit, maxStacks);
    }

    private static double[] java_vector_multiply(double x, double y, double z, double scalar) {
        return new double[] { x * scalar, y * scalar, z * scalar };
    }

    private static double[] java_vector_add(double x1, double y1, double z1, double x2, double y2, double z2) {
        return new double[] { x1 + x2, y1 + y2, z1 + z2 };
    }

    private static void recordOptimizationHit(String details) {
        optimizationHits.incrementAndGet();
        // Silent success - no logging for successful operations
    }

    static boolean isNaturalMovement(double[] result, double originalX, double originalY, double originalZ) {
        if (result == null || result.length != 3)
            return false;
        for (double val : result) {
            if (Double.isNaN(val) || Double.isInfinite(val))
                return false;
        }

        boolean xDirectionReversed = (originalX > 0 && result[0] < 0) || (originalX < 0 && result[0] > 0);
        boolean zDirectionReversed = (originalZ > 0 && result[2] < 0) || (originalZ < 0 && result[2] > 0);

        final double HORIZONTAL_THRESHOLD_NORMAL = 5.0;
        final double HORIZONTAL_THRESHOLD_REVERSED = 12.0;
        final double VERTICAL_THRESHOLD = 8.0;

        double horizontalThreshold = (xDirectionReversed || zDirectionReversed) ? HORIZONTAL_THRESHOLD_REVERSED
                : HORIZONTAL_THRESHOLD_NORMAL;

        if (Math.abs(result[0]) > Math.abs(originalX) * horizontalThreshold)
            return false;
        if (Math.abs(result[2]) > Math.abs(originalZ) * horizontalThreshold)
            return false;
        if (Math.abs(result[1]) > Math.abs(originalY) * VERTICAL_THRESHOLD)
            return false;

        return true;
    }

    private static void recordOptimizationMiss(String details) {
        optimizationMisses.incrementAndGet();
        // Only log as debug to reduce spam - these are normal when entities are
        // filtered
        LOGGER.debug("Optimization skipped: {}", details);
    }

    private static void recordCombinedOptimizationHit(String details) {
        combinedOptimizationHits.incrementAndGet();
        if (combinedOptimizationHits.get() % 100 == 0)
            logCombinedPerformanceStats();
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
        return String.format(
                "CombinedOptimizationMetrics{hits=%d, misses=%d, totalProcessed=%d, hitRate=%.2f%%, nativeLoaded=%b}",
                combinedOptimizationHits.get(), combinedOptimizationMisses.get(), totalEntitiesProcessed.get(), hitRate,
                isNativeLibraryLoaded);
    }

    private static void logPerformanceStats() {
        LOGGER.debug("Java optimization metrics: {}", getOptimizationMetrics());
    }

    public static String getOptimizationMetrics() {
        long totalOps = optimizationHits.get() + optimizationMisses.get();
        double hitRate = totalOps > 0 ? (double) optimizationHits.get() / totalOps * 100 : 0.0;
        return String.format(
                "OptimizationMetrics{hits=%d, misses=%d, totalProcessed=%d, hitRate=%.2f%%, nativeLoaded=%b}",
                optimizationHits.get(), optimizationMisses.get(), totalEntitiesProcessed.get(), hitRate,
                isNativeLibraryLoaded);
    }

    static void enableTestMode(boolean enabled) {
        LOGGER.warn("DEPRECATED: Use ModeDetector instead of direct test mode manipulation");
        isTestMode = enabled;
        if (enabled) {
            isNativeLibraryLoaded = false;
        } else {
            isNativeLibraryLoaded = false;
            loadNativeLibrary();
        }
    }

    /**
     * Get test metrics that respect current runtime mode
     * 
     * @return Optimization metrics appropriate for current mode
     */
    static OptimizationMetrics getTestMetrics() {
        return new OptimizationMetrics(optimizationHits.get(), optimizationMisses.get(), totalEntitiesProcessed.get(),
                isNativeLibraryLoaded && !ModeDetector.isTestMode());
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

        public int getNativeHits() {
            return hits;
        }

        public int getNativeMisses() {
            return misses;
        }
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

    /**
     * Hayabusa skill calculations using Rust optimization
     */
    public static double[] calculatePhantomShurikenTrajectory(double startX, double startY, double startZ,
            double targetX, double targetY, double targetZ, double speed) {
        if (!isNativeLibraryLoaded) {
            if (!phantomShurikenWarningLogged) {
                LOGGER.info("Phantom shuriken calculation using Java fallback (native library not loaded)");
                phantomShurikenWarningLogged = true;
            }
            return java_phantom_shuriken_trajectory(startX, startY, startZ, targetX, targetY, targetZ, speed);
        }

        try {
            double[] result = rustperf_hayabusa_phantom_shuriken(startX, startY, startZ, targetX, targetY, targetZ,
                    speed);
            if (result == null || result.length != 3) {
                if (!phantomShurikenWarningLogged) {
                    LOGGER.warn("Phantom shuriken Rust returned invalid result, using fallback");
                    phantomShurikenWarningLogged = true;
                }
                return java_phantom_shuriken_trajectory(startX, startY, startZ, targetX, targetY, targetZ, speed);
            }
            return result;
        } catch (UnsatisfiedLinkError e) {
            if (!phantomShurikenWarningLogged) {
                LOGGER.warn("Phantom shuriken native method not found, using fallback: {}", e.getMessage());
                phantomShurikenWarningLogged = true;
            }
            return java_phantom_shuriken_trajectory(startX, startY, startZ, targetX, targetY, targetZ, speed);
        } catch (Exception e) {
            if (!phantomShurikenWarningLogged) {
                LOGGER.warn("Phantom shuriken Rust calculation failed, using fallback: {}", e.getMessage());
                phantomShurikenWarningLogged = true;
            }
            return java_phantom_shuriken_trajectory(startX, startY, startZ, targetX, targetY, targetZ, speed);
        }
    }

    public static double[][] calculateQuadShadowPositions(double centerX, double centerY, double centerZ,
            double radius) {
        if (!isNativeLibraryLoaded) {
            if (!quadShadowWarningLogged) {
                LOGGER.info("Quad shadow calculation using Java fallback (native library not loaded)");
                quadShadowWarningLogged = true;
            }
            return java_quad_shadow_positions(centerX, centerY, centerZ, radius);
        }

        try {
            double[][] result = rustperf_hayabusa_quad_shadow(centerX, centerY, centerZ, radius);
            if (result == null || result.length != 4 || result[0].length != 3) {
                if (!quadShadowWarningLogged) {
                    LOGGER.warn("Quad shadow Rust returned invalid result, using fallback");
                    quadShadowWarningLogged = true;
                }
                return java_quad_shadow_positions(centerX, centerY, centerZ, radius);
            }
            return result;
        } catch (UnsatisfiedLinkError e) {
            if (!quadShadowWarningLogged) {
                LOGGER.warn("Quad shadow native method not found, using fallback: {}", e.getMessage());
                quadShadowWarningLogged = true;
            }
            return java_quad_shadow_positions(centerX, centerY, centerZ, radius);
        } catch (Exception e) {
            if (!quadShadowWarningLogged) {
                LOGGER.warn("Quad shadow Rust calculation failed, using fallback: {}", e.getMessage());
                quadShadowWarningLogged = true;
            }
            return java_quad_shadow_positions(centerX, centerY, centerZ, radius);
        }
    }

    public static double calculateShadowKillDamage(int passiveStacks, double baseDamage) {
        if (!isNativeLibraryLoaded) {
            if (!shadowKillDamageWarningLogged) {
                LOGGER.info("Shadow kill damage calculation using Java fallback (native library not loaded)");
                shadowKillDamageWarningLogged = true;
            }
            return java_shadow_kill_damage(passiveStacks, baseDamage);
        }

        try {
            double result = rustperf_hayabusa_shadow_kill_damage(passiveStacks, baseDamage);
            if (result <= 0 || Double.isNaN(result) || Double.isInfinite(result)) {
                if (!shadowKillDamageWarningLogged) {
                    LOGGER.warn("Shadow kill damage Rust returned invalid result, using fallback");
                    shadowKillDamageWarningLogged = true;
                }
                return java_shadow_kill_damage(passiveStacks, baseDamage);
            }
            return result;
        } catch (UnsatisfiedLinkError e) {
            if (!shadowKillDamageWarningLogged) {
                LOGGER.warn("Shadow kill damage native method not found, using fallback: {}", e.getMessage());
                shadowKillDamageWarningLogged = true;
            }
            return java_shadow_kill_damage(passiveStacks, baseDamage);
        } catch (Exception e) {
            if (!shadowKillDamageWarningLogged) {
                LOGGER.warn("Shadow kill damage Rust calculation failed, using fallback: {}", e.getMessage());
                shadowKillDamageWarningLogged = true;
            }
            return java_shadow_kill_damage(passiveStacks, baseDamage);
        }
    }

    public static int calculatePassiveStacks(int currentStacks, boolean successfulHit, int maxStacks) {
        if (!isNativeLibraryLoaded) {
            if (!passiveStacksWarningLogged) {
                LOGGER.info("Passive stacks calculation using Java fallback (native library not loaded)");
                passiveStacksWarningLogged = true;
            }
            return java_calculate_passive_stacks(currentStacks, successfulHit, maxStacks);
        }

        try {
            int result = rustperf_hayabusa_calculate_passive_stacks(currentStacks, successfulHit, maxStacks);
            if (result < 0 || result > maxStacks) {
                if (!passiveStacksWarningLogged) {
                    LOGGER.warn("Passive stacks Rust returned invalid result {}, using fallback", result);
                    passiveStacksWarningLogged = true;
                }
                return java_calculate_passive_stacks(currentStacks, successfulHit, maxStacks);
            }
            return result;
        } catch (UnsatisfiedLinkError e) {
            if (!passiveStacksWarningLogged) {
                LOGGER.warn("Passive stacks native method not found, using fallback: {}", e.getMessage());
                passiveStacksWarningLogged = true;
            }
            return java_calculate_passive_stacks(currentStacks, successfulHit, maxStacks);
        } catch (Exception e) {
            if (!passiveStacksWarningLogged) {
                LOGGER.warn("Passive stacks Rust calculation failed, using fallback: {}", e.getMessage());
                passiveStacksWarningLogged = true;
            }
            return java_calculate_passive_stacks(currentStacks, successfulHit, maxStacks);
        }
    }

    // Java fallback implementations for Hayabusa skills
    private static double[] java_phantom_shuriken_trajectory(double startX, double startY, double startZ,
            double targetX, double targetY, double targetZ, double speed) {
        double dx = targetX - startX;
        double dy = targetY - startY;
        double dz = targetZ - startZ;
        // Optimized: Use Rust vector length instead of Math.sqrt
        double distance = RustNativeLoader.vectorLength(dx, dy, dz);

        if (distance == 0)
            return new double[] { startX, startY, startZ };

        // Optimized: Use Rust vector normalization
        double[] normalized = RustNativeLoader.vectorNormalize(dx, dy, dz);

        // Return trajectory with speed applied
        return new double[] {
                startX + normalized[0] * speed * 0.1,
                startY + normalized[1] * speed * 0.1,
                startZ + normalized[2] * speed * 0.1
        };
    }

    private static double[][] java_quad_shadow_positions(double centerX, double centerY, double centerZ,
            double radius) {
        double[][] positions = new double[4][3];

        // Optimized: Use Rust trigonometric calculations for circular positions
        for (int i = 0; i < 4; i++) {
            double angle = (i * Math.PI) / 2.0;
            double[] circularPos = RustNativeLoader.calculateCircularPosition(centerX, centerZ, radius, angle);
            positions[i][0] = circularPos[0];
            positions[i][1] = centerY;
            positions[i][2] = circularPos[1];
        }

        return positions;
    }

    private static double java_shadow_kill_damage(int passiveStacks, double baseDamage) {
        // Hayabusa ultimate scaling: base damage + 30% per stack
        double multiplier = 1.0 + (passiveStacks * 0.30);
        return baseDamage * multiplier;
    }

    private static int java_calculate_passive_stacks(int currentStacks, boolean successfulHit, int maxStacks) {
        if (successfulHit) {
            return Math.min(currentStacks + 1, maxStacks);
        }
        return currentStacks;
    }

    /**
     * Perform strict Minecraft-specific entity validation
     * Only allows vanilla mobs and custom mobs from this mod for compatibility with
     * other mods
     * Items are excluded as they often have custom behaviors from other mods
     * 
     * @param entity Entity to validate
     * @return true if entity is valid for production processing
     */
    private static boolean isValidMinecraftEntity(Object entity) {
        if (entity == null)
            return false;

        try {
            // Check if entity is a valid Minecraft entity
            String entityClassName = entity.getClass().getName();

            // EXCLUDE ITEMS - they often have custom behaviors from mods
            if (entityClassName.contains(".item.") ||
                    entityClassName.contains("ItemEntity") ||
                    entityClassName.contains("ItemFrame") ||
                    entityClassName.contains("ItemStack")) {
                return false; // Never optimize items
            }

            // WHITELIST APPROACH FOR COMPATIBILITY:
            // Optimize vanilla Minecraft entities and our custom mod entities

            boolean isVanillaMinecraftEntity = entityClassName.startsWith("net.minecraft.world.entity.") ||
                    entityClassName.startsWith("net.minecraft.client.player.") ||
                    entityClassName.startsWith("net.minecraft.server.level.");

            boolean isKneafModEntity = entityClassName.startsWith("com.kneaf.entities.");

            // Only allow vanilla or our mod's entities
            if (!isVanillaMinecraftEntity && !isKneafModEntity) {
                // Silently skip other mod entities for compatibility
                return false;
            }

            // For vanilla entities, accept them directly without extra checks
            // The original hierarchy check was too restrictive and blocked valid entities
            return true;
        } catch (Exception e) {
            LOGGER.debug("Entity validation failed due to exception", e);
            return false;
        }
    }

    /**
     * Apply actual movement to Minecraft entity
     * 
     * @param entity Entity to update
     * @param x      X movement component
     * @param y      Y movement component
     * @param z      Z movement component
     */
    private static void applyEntityMovement(Object entity, double x, double y, double z) {
        try {
            // Use reflection to set delta movement on the entity
            java.lang.reflect.Method setDeltaMovement = entity.getClass().getMethod("setDeltaMovement",
                    double.class, double.class, double.class);
            setDeltaMovement.invoke(entity, x, y, z);

        } catch (Exception e) {
            LOGGER.error("Failed to apply entity movement: {}", e.getMessage(), e);
            recordOptimizationMiss("Failed to apply entity movement");
        }
    }

    /**
     * Get actual entity count from server tick event
     * 
     * @param event Server tick event
     * @return Actual number of entities
     */
    private static int getActualEntityCount(ServerTickEvent.Pre event) {
        try {
            // Try to get server from event context
            Object server = event.getServer();
            if (server != null) {
                // Use reflection to get all levels and count entities
                java.lang.reflect.Method getAllLevels = server.getClass().getMethod("getAllLevels");
                Object levels = getAllLevels.invoke(server);

                if (levels instanceof Iterable) {
                    int totalEntities = 0;
                    for (Object level : (Iterable<?>) levels) {
                        java.lang.reflect.Method getEntities = level.getClass().getMethod("getEntities");
                        Object entityAccess = getEntities.invoke(level);

                        java.lang.reflect.Method getAll = entityAccess.getClass().getMethod("getAll");
                        Object entities = getAll.invoke(entityAccess);

                        if (entities instanceof Iterable<?> iterableEntities) {
                            int count = 0;
                            for (@SuppressWarnings("unused")
                            Object ignored : iterableEntities)
                                count++;
                            totalEntities += count;
                        }
                    }
                    return totalEntities > 0 ? totalEntities : 500;
                }
            }
            return 500; // Fallback if server not available
        } catch (Exception e) {
            LOGGER.debug("Failed to get actual entity count: {}", e.getMessage());
            return 200; // Conservative fallback
        }
    }

    /**
     * Get actual entity count from level tick event
     * 
     * @param event Level tick event
     * @return Actual number of entities
     */
    private static int getActualEntityCount(LevelTickEvent.Pre event) {
        try {
            var level = event.getLevel();

            // Try to get actual entity count from level
            java.lang.reflect.Method getEntities = level.getClass().getMethod("getEntities");
            Object entityAccess = getEntities.invoke(level);

            java.lang.reflect.Method getAll = entityAccess.getClass().getMethod("getAll");
            Object entities = getAll.invoke(entityAccess);

            if (entities instanceof Iterable<?> iterableEntities) {
                int count = 0;
                for (@SuppressWarnings("unused")
                Object entity : iterableEntities) {
                    count++;
                }
                return count > 0 ? count : 100;
            }

            // Fallback to reasonable default
            return 300;
        } catch (Exception e) {
            LOGGER.debug("Failed to get actual entity count: {}", e.getMessage());
            return 100; // Conservative fallback
        }
    }
}