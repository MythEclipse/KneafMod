package com.kneaf.core;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.File;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Parallel native library loader with dependency resolution and async loading capabilities.
 * Handles multiple library loading paths concurrently with fallback mechanisms.
 */
public final class ParallelLibraryLoader {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ParallelLibraryLoader INSTANCE = new ParallelLibraryLoader();
    
    // Thread pool for parallel library loading
    private final ExecutorService libraryLoader;
    private final ForkJoinPool dependencyResolver;
    private final ScheduledExecutorService maintenanceExecutor;
    
    // Library loading state tracking
    private final ConcurrentHashMap<String, LibraryLoadResult> loadedLibraries;
    private final ConcurrentHashMap<String, CompletableFuture<LibraryLoadResult>> loadingFutures;
    private final ConcurrentHashMap<String, Set<String>> libraryDependencies;
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicInteger activeLoaders = new AtomicInteger(0);
    
    // Configuration
    private static final int MAX_LOADER_THREADS = 4;
    private static final int DEPENDENCY_TIMEOUT_MS = 5000;
    private static final int LOAD_RETRY_ATTEMPTS = 3;
    private static final int RETRY_DELAY_MS = 1000;
    
    // Library loading paths
    private static final String[] RUST_LIBRARY_PATHS = {
        "natives/rustperf.dll",
        "rustperf.dll",
        "src/main/resources/natives/rustperf.dll",
        "build/resources/main/natives/rustperf.dll",
        "run/natives/rustperf.dll",
        "target/natives/rustperf.dll",
        "target/debug/rustperf.dll",
        "target/release/rustperf.dll"
    };
    
    private static final String[] SYSTEM_LIBRARY_PATHS = {
        "%USERPROFILE%/.minecraft/mods/natives/rustperf.dll",
        "%APPDATA%/.minecraft/mods/natives/rustperf.dll",
        "%USERPROFILE%/.minecraft/rustperf.dll",
        "%APPDATA%/.minecraft/rustperf.dll"
    };
    
    private ParallelLibraryLoader() {
        this.libraryLoader = new ThreadPoolExecutor(
            1,
            MAX_LOADER_THREADS,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(MAX_LOADER_THREADS * 2),
            new ThreadFactory() {
                private final AtomicInteger threadNumber = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "LibraryLoader-" + threadNumber.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        this.dependencyResolver = new ForkJoinPool(MAX_LOADER_THREADS);
        this.maintenanceExecutor = Executors.newSingleThreadScheduledExecutor();
        this.loadedLibraries = new ConcurrentHashMap<>();
        this.loadingFutures = new ConcurrentHashMap<>();
        this.libraryDependencies = new ConcurrentHashMap<>();
        
        initializeLibraryDependencies();
        startMaintenanceTask();
        
        LOGGER.info("ParallelLibraryLoader initialized with {} max threads", MAX_LOADER_THREADS);
    }
    
    public static ParallelLibraryLoader getInstance() {
        return INSTANCE;
    }
    
    /**
     * Initialize library dependency graph
     */
    private void initializeLibraryDependencies() {
        // Define library dependencies
        libraryDependencies.put("rustperf", new HashSet<>());
        // Add more dependencies as needed
    }
    
    /**
     * Load native library asynchronously with parallel path resolution
     */
    public CompletableFuture<LibraryLoadResult> loadLibraryAsync(String libraryName) {
        if (isLibraryLoaded(libraryName)) {
            return CompletableFuture.completedFuture(loadedLibraries.get(libraryName));
        }
        
        // Check if already loading
        CompletableFuture<LibraryLoadResult> existingFuture = loadingFutures.get(libraryName);
        if (existingFuture != null) {
            return existingFuture;
        }
        
        // Create new loading future
        CompletableFuture<LibraryLoadResult> future = new CompletableFuture<>();
        loadingFutures.put(libraryName, future);
        
        // Submit to thread pool for parallel loading
        libraryLoader.submit(() -> performParallelLibraryLoad(libraryName, future));
        
        return future;
    }
    
    /**
     * Perform parallel library loading across multiple paths
     */
    private void performParallelLibraryLoad(String libraryName, CompletableFuture<LibraryLoadResult> future) {
        activeLoaders.incrementAndGet();
        long startTime = System.currentTimeMillis();
        
        try {
            // Determine OS-specific library extension
            String os = System.getProperty("os.name").toLowerCase();
            String libExtension = os.contains("win") ? "dll" : os.contains("mac") ? "dylib" : "so";
            String libFileName = libraryName + "." + libExtension;
            
            LOGGER.info("Starting parallel library load for {} (OS: {}, Arch: {})", 
                libraryName, os, System.getProperty("os.arch"));
            
            // Create parallel loading tasks for different path categories
            List<CompletableFuture<LibraryPathResult>> pathFutures = new ArrayList<>();
            
            // Classpath paths
            for (String path : RUST_LIBRARY_PATHS) {
                String fullPath = path.replace("rustperf.dll", libFileName);
                pathFutures.add(loadFromPathAsync(fullPath, "classpath"));
            }
            
            // System paths with environment variable expansion
            for (String path : SYSTEM_LIBRARY_PATHS) {
                String expandedPath = expandEnvironmentVariables(path).replace("rustperf.dll", libFileName);
                pathFutures.add(loadFromPathAsync(expandedPath, "system"));
            }
            
            // Java library path
            pathFutures.add(loadFromJavaLibraryPathAsync(libFileName));
            
            // Wait for first successful load
            CompletableFuture<LibraryPathResult> firstSuccess = CompletableFuture.anyOf(
                pathFutures.toArray(new CompletableFuture[0])
            ).thenApply(result -> (LibraryPathResult) result);
            
            try {
                LibraryPathResult pathResult = firstSuccess.get(DEPENDENCY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                
                if (pathResult.success) {
                    LibraryLoadResult loadResult = new LibraryLoadResult(
                        libraryName, true, pathResult.path, pathResult.loadTimeMs, null
                    );
                    loadedLibraries.put(libraryName, loadResult);
                    future.complete(loadResult);
                    
                    LOGGER.info("✅ Successfully loaded {} from {} in {}ms", 
                        libraryName, pathResult.path, pathResult.loadTimeMs);
                } else {
                    LibraryLoadResult failureResult = new LibraryLoadResult(
                        libraryName, false, null, System.currentTimeMillis() - startTime, 
                        "All loading paths failed"
                    );
                    future.complete(failureResult);
                    
                    LOGGER.error("❌ Failed to load {} - all paths exhausted", libraryName);
                }
                
            } catch (TimeoutException e) {
                future.completeExceptionally(new RuntimeException("Library loading timed out"));
                LOGGER.error("⏰ Library loading timeout for {}", libraryName);
            }
            
        } catch (Exception e) {
            future.completeExceptionally(e);
            LOGGER.error("💥 Critical error loading library {}: {}", libraryName, e.getMessage(), e);
        } finally {
            activeLoaders.decrementAndGet();
            loadingFutures.remove(libraryName);
        }
    }
    
    /**
     * Load library from specific path asynchronously
     */
    private CompletableFuture<LibraryPathResult> loadFromPathAsync(String path, String pathType) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            
            try {
                File libFile = new File(path);
                if (!libFile.exists()) {
                    return new LibraryPathResult(path, false, 0, "File not found");
                }
                
                // Attempt to load library with retries
                for (int attempt = 1; attempt <= LOAD_RETRY_ATTEMPTS; attempt++) {
                    try {
                        System.load(path);
                        long loadTime = System.currentTimeMillis() - startTime;
                        
                        LOGGER.debug("✅ Loaded {} from {} (attempt {}) in {}ms", 
                            libFile.getName(), pathType, attempt, loadTime);
                        
                        return new LibraryPathResult(path, true, loadTime, null);
                        
                    } catch (UnsatisfiedLinkError e) {
                        if (attempt < LOAD_RETRY_ATTEMPTS) {
                            LOGGER.warn("⚠️ Retry {} for {} from {}: {}", 
                                attempt, libFile.getName(), pathType, e.getMessage());
                            Thread.sleep(RETRY_DELAY_MS);
                        } else {
                            return new LibraryPathResult(path, false, 
                                System.currentTimeMillis() - startTime, e.getMessage());
                        }
                    } catch (SecurityException e) {
                        return new LibraryPathResult(path, false, 
                            System.currentTimeMillis() - startTime, "Security restriction: " + e.getMessage());
                    }
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new LibraryPathResult(path, false, 
                    System.currentTimeMillis() - startTime, "Interrupted");
            } catch (Exception e) {
                return new LibraryPathResult(path, false, 
                    System.currentTimeMillis() - startTime, e.getMessage());
            }
            
            return new LibraryPathResult(path, false, 
                System.currentTimeMillis() - startTime, "Unknown error");
        }, libraryLoader);
    }
    
    /**
     * Load library from java.library.path
     */
    private CompletableFuture<LibraryPathResult> loadFromJavaLibraryPathAsync(String libName) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            String javaLibPath = System.getProperty("java.library.path");
            
            String[] paths = javaLibPath.split(File.pathSeparator);
            for (String path : paths) {
                File libFile = new File(path, libName);
                if (libFile.exists()) {
                    try {
                        System.load(libFile.getAbsolutePath());
                        long loadTime = System.currentTimeMillis() - startTime;
                        
                        LOGGER.debug("✅ Loaded {} from java.library.path: {} in {}ms", 
                            libName, libFile.getAbsolutePath(), loadTime);
                        
                        return new LibraryPathResult(libFile.getAbsolutePath(), true, loadTime, null);
                        
                    } catch (UnsatisfiedLinkError e) {
                        return new LibraryPathResult(libFile.getAbsolutePath(), false, 
                            System.currentTimeMillis() - startTime, e.getMessage());
                    } catch (SecurityException e) {
                        return new LibraryPathResult(libFile.getAbsolutePath(), false, 
                            System.currentTimeMillis() - startTime, "Security: " + e.getMessage());
                    }
                }
            }
            
            return new LibraryPathResult(null, false, 
                System.currentTimeMillis() - startTime, "Not found in java.library.path");
        }, libraryLoader);
    }
    
    /**
     * Load multiple libraries with dependency resolution
     */
    public CompletableFuture<Map<String, LibraryLoadResult>> loadLibrariesWithDependencies(
            List<String> libraryNames) {
        
        return CompletableFuture.supplyAsync(() -> {
            Map<String, LibraryLoadResult> results = new ConcurrentHashMap<>();
            Set<String> loadedLibs = ConcurrentHashMap.newKeySet();
            
            // Sort libraries by dependency order
            List<String> orderedLibs = resolveDependencyOrder(libraryNames);
            
            // Load libraries in dependency order
            for (String libName : orderedLibs) {
                try {
                    LibraryLoadResult result = loadLibraryAsync(libName).get(
                        DEPENDENCY_TIMEOUT_MS, TimeUnit.MILLISECONDS
                    );
                    results.put(libName, result);
                    
                    if (result.success) {
                        loadedLibs.add(libName);
                    } else {
                        LOGGER.warn("Failed to load dependency {}: {}", libName, result.errorMessage);
                    }
                    
                } catch (Exception e) {
                    results.put(libName, new LibraryLoadResult(
                        libName, false, null, 0, "Dependency loading failed: " + e.getMessage()
                    ));
                }
            }
            
            return results;
        }, dependencyResolver);
    }
    
    /**
     * Resolve library loading order based on dependencies
     */
    private List<String> resolveDependencyOrder(List<String> libraryNames) {
        // Simple topological sort for dependency resolution
        List<String> ordered = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        
        for (String libName : libraryNames) {
            if (!visited.contains(libName)) {
                resolveDependenciesRecursive(libName, ordered, visited, visiting);
            }
        }
        
        return ordered;
    }
    
    /**
     * Recursive dependency resolution
     */
    private void resolveDependenciesRecursive(String libName, List<String> ordered, 
                                            Set<String> visited, Set<String> visiting) {
        if (visiting.contains(libName)) {
            throw new IllegalStateException("Circular dependency detected for: " + libName);
        }
        
        if (visited.contains(libName)) {
            return;
        }
        
        visiting.add(libName);
        
        // Add dependencies first
        Set<String> dependencies = libraryDependencies.get(libName);
        if (dependencies != null) {
            for (String dependency : dependencies) {
                resolveDependenciesRecursive(dependency, ordered, visited, visiting);
            }
        }
        
        visiting.remove(libName);
        visited.add(libName);
        ordered.add(libName);
    }
    
    /**
     * Check if library is loaded
     */
    public boolean isLibraryLoaded(String libraryName) {
        return loadedLibraries.containsKey(libraryName) && 
               loadedLibraries.get(libraryName).success;
    }
    
    /**
     * Get loaded library information
     */
    public LibraryLoadResult getLibraryInfo(String libraryName) {
        return loadedLibraries.get(libraryName);
    }
    
    /**
     * Get all loaded libraries
     */
    public Map<String, LibraryLoadResult> getLoadedLibraries() {
        return new HashMap<>(loadedLibraries);
    }
    
    /**
     * Start maintenance task for cleanup and monitoring
     */
    private void startMaintenanceTask() {
        maintenanceExecutor.scheduleAtFixedRate(() -> {
            try {
                // Clean up completed futures
                loadingFutures.entrySet().removeIf(entry -> entry.getValue().isDone());
                
                // Log performance metrics
                if (loadedLibraries.size() % 10 == 0) {
                    logPerformanceMetrics();
                }
            } catch (Exception e) {
                LOGGER.error("Error in maintenance task: {}", e.getMessage(), e);
            }
        }, 60, 60, TimeUnit.SECONDS);
    }
    
    /**
     * Log performance metrics
     */
    private void logPerformanceMetrics() {
        LOGGER.info("ParallelLibraryLoader Metrics - Loaded: {}, Loading: {}, Active: {}", 
            loadedLibraries.size(), 
            loadingFutures.size(), 
            activeLoaders.get()
        );
    }
    
    /**
     * Expand environment variables in paths
     */
    private String expandEnvironmentVariables(String path) {
        String result = path;
        
        Map<String, String> envVars = new HashMap<>();
        envVars.put("%USERPROFILE%", System.getProperty("user.home"));
        envVars.put("%APPDATA%", System.getenv("APPDATA"));
        envVars.put("%PROGRAMFILES%", System.getenv("PROGRAMFILES"));
        
        for (Map.Entry<String, String> entry : envVars.entrySet()) {
            if (entry.getValue() != null) {
                result = result.replace(entry.getKey(), entry.getValue());
            }
        }
        
        return result;
    }
    
    /**
     * Shutdown loader gracefully
     */
    public void shutdown() {
        libraryLoader.shutdown();
        dependencyResolver.shutdown();
        maintenanceExecutor.shutdown();
        
        try {
            if (!libraryLoader.awaitTermination(5, TimeUnit.SECONDS)) {
                libraryLoader.shutdownNow();
            }
            if (!dependencyResolver.awaitTermination(5, TimeUnit.SECONDS)) {
                dependencyResolver.shutdownNow();
            }
            if (!maintenanceExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                maintenanceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            libraryLoader.shutdownNow();
            dependencyResolver.shutdownNow();
            maintenanceExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        LOGGER.info("ParallelLibraryLoader shutdown completed");
    }
    
    /**
     * Library load result
     */
    public static class LibraryLoadResult {
        public final String libraryName;
        public final boolean success;
        public final String loadedPath;
        public final long loadTimeMs;
        public final String errorMessage;
        
        public LibraryLoadResult(String libraryName, boolean success, String loadedPath, 
                               long loadTimeMs, String errorMessage) {
            this.libraryName = libraryName;
            this.success = success;
            this.loadedPath = loadedPath;
            this.loadTimeMs = loadTimeMs;
            this.errorMessage = errorMessage;
        }
    }
    
    /**
     * Library path result
     */
    private static class LibraryPathResult {
        public final String path;
        public final boolean success;
        public final long loadTimeMs;
        public final String errorMessage;
        
        public LibraryPathResult(String path, boolean success, long loadTimeMs, String errorMessage) {
            this.path = path;
            this.success = success;
            this.loadTimeMs = loadTimeMs;
            this.errorMessage = errorMessage;
        }
    }
}