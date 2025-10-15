package com.kneaf.core.performance.bridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Advanced native library loader with cross-platform support, dependency management,
 * and robust error handling for JNI and JNA libraries.
 */
public class NativeLibraryLoader {
  private static final Logger LOGGER = LoggerFactory.getLogger(NativeLibraryLoader.class);

  // OS and architecture detection
  private static final String OS_NAME = System.getProperty("os.name").toLowerCase();
  private static final String OS_ARCH = System.getProperty("os.arch").toLowerCase();
  private static final String JAVA_LIBRARY_PATH = System.getProperty("java.library.path", "");

  // Library loading state
  private final Set<String> loadedLibraries = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean initialized = new AtomicBoolean(false);
  private volatile Path tempDirectory;

  // Platform-specific library prefixes and suffixes
  private final String LIBRARY_PREFIX;
  private final String LIBRARY_SUFFIX;

  // Singleton instance
  private static final NativeLibraryLoader INSTANCE = new NativeLibraryLoader();

  private NativeLibraryLoader() {
    // Determine platform-specific library naming
    if (OS_NAME.contains("win")) {
      LIBRARY_PREFIX = "";
      LIBRARY_SUFFIX = ".dll";
    } else if (OS_NAME.contains("mac")) {
      LIBRARY_PREFIX = "lib";
      LIBRARY_SUFFIX = ".dylib";
    } else {
      // Linux and other Unix-like systems
      LIBRARY_PREFIX = "lib";
      LIBRARY_SUFFIX = ".so";
    }
  }

  /**
   * Load a native library with automatic platform detection and fallback strategies.
   *
   * @param libraryName The base name of the library (without prefix/suffix)
   * @return true if the library was loaded successfully, false otherwise
   */
  public boolean loadNativeLibrary(String libraryName) {
    Objects.requireNonNull(libraryName, "Library name cannot be null");
    
    if (libraryName.trim().isEmpty()) {
      LOGGER.error("Library name cannot be empty");
      return false;
    }

    // Check if already loaded
    if (loadedLibraries.contains(libraryName)) {
      LOGGER.debug("Library '{}' already loaded", libraryName);
      return true;
    }

    initializeIfNeeded();

    LOGGER.info("Attempting to load library: '{}'", libraryName);
    
    // Try multiple loading strategies in order with detailed logging
    return tryLoadStrategy(libraryName, this::tryLoadFromSystemPath) ||
           tryLoadStrategy(libraryName, this::tryLoadFromClasspath) ||
           tryLoadStrategy(libraryName, this::tryLoadFromCustomPaths) ||
           tryLoadStrategy(libraryName, this::tryExtractAndLoad);
  }

  /**
   * Load the default native library (backward compatibility method).
   *
   * @return true if the library was loaded successfully
   */
  public boolean loadDefaultLibraries() {
    // Try to load common library names
    String[] libraryNames = {"kneaf", "libkneaf", "kneafcore", "rustperf"};
    for (String libName : libraryNames) {
      if (loadNativeLibrary(libName)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Load the default native library (backward compatibility static method).
   *
   * @return true if the library was loaded successfully
   */
  public static boolean loadNativeLibrary() {
    return getInstance().loadDefaultLibraries();
  }

  /**
   * Initialize the loader if not already done.
   */
  private void initializeIfNeeded() {
    if (initialized.compareAndSet(false, true)) {
      try {
        createTempDirectory();
        logPlatformInfo();
      } catch (Exception e) {
        LOGGER.error("Failed to initialize NativeLibraryLoader", e);
        throw new IllegalStateException("Failed to initialize NativeLibraryLoader", e);
      }
    }
  }

  /**
   * Create a temporary directory for extracted libraries.
   */
  private void createTempDirectory() throws IOException {
    String tempDirName = "kneaf-native-libs-" + System.currentTimeMillis();
    tempDirectory = Files.createTempDirectory(tempDirName);

    // Add shutdown hook to cleanup temp directory
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        deleteDirectoryRecursively(tempDirectory);
      } catch (Exception e) {
        LOGGER.warn("Failed to cleanup temporary directory: {}", tempDirectory, e);
      }
    }));

    LOGGER.debug("Created temporary directory for native libraries: {}", tempDirectory);
  }

  /**
   * Log platform and system information for debugging.
   */
  private void logPlatformInfo() {
    LOGGER.info("NativeLibraryLoader initialized:");
    LOGGER.info("  OS: {} ({})", OS_NAME, getOSFamily());
    LOGGER.info("  Architecture: {}", OS_ARCH);
    LOGGER.info("  Java Library Path: {}", JAVA_LIBRARY_PATH);
    LOGGER.info("  Library format: {}[name]{}", LIBRARY_PREFIX, LIBRARY_SUFFIX);
  }

  /**
   * Try to load library from system library path.
   */
  private boolean tryLoadFromSystemPath(String libraryName) {
    String fullLibraryName = LIBRARY_PREFIX + libraryName + LIBRARY_SUFFIX;
    LOGGER.debug("Trying system loadLibrary: '{}'", fullLibraryName);
    
    try {
      System.loadLibrary(fullLibraryName);
      loadedLibraries.add(libraryName);
      LOGGER.info("Successfully loaded library '{}' from system path", fullLibraryName);
      return true;
    } catch (UnsatisfiedLinkError e) {
      LOGGER.debug("Library '{}' not found in system path: {}", fullLibraryName, e.getMessage());
      return false;
    }
  }

  /**
   * Try to load library from classpath resources.
   */
  private boolean tryLoadFromClasspath(String libraryName) {
    String resourcePath = getResourcePath(libraryName);
    LOGGER.debug("Trying classpath resource path: {}", resourcePath);
    
    if (resourcePath == null) {
      LOGGER.debug("No valid resource path for library '{}'", libraryName);
      return false;
    }

    try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
      if (is == null) {
        LOGGER.debug("Library resource not found at path: {}", resourcePath);
        return false;
      }

      Path extractedLib = extractLibraryToTemp(is, libraryName);
      System.load(extractedLib.toAbsolutePath().toString());
      loadedLibraries.add(libraryName);
      LOGGER.info("Successfully loaded library '{}' from classpath resource: {}", libraryName, resourcePath);
      return true;
    } catch (Exception e) {
      LOGGER.debug("Failed to load library '{}' from classpath resource '{}': {}", libraryName, resourcePath, e.getMessage());
      return false;
    }
  }

  /**
   * Try to load library from custom search paths.
   */
  private boolean tryLoadFromCustomPaths(String libraryName) {
    List<String> searchPaths = getCustomSearchPaths();
    LOGGER.debug("Trying custom search paths for '{}': {}", libraryName, searchPaths);

    for (String searchPath : searchPaths) {
      try {
        Path libPath = Paths.get(searchPath, LIBRARY_PREFIX + libraryName + LIBRARY_SUFFIX);
        LOGGER.debug("Checking path: {}", libPath);
        if (Files.exists(libPath)) {
          LOGGER.debug("Found library at: {}", libPath);
          System.load(libPath.toAbsolutePath().toString());
          loadedLibraries.add(libraryName);
          LOGGER.info("Successfully loaded library '{}' from custom path: {}", libraryName, searchPath);
          return true;
        }
      } catch (UnsatisfiedLinkError e) {
        LOGGER.debug("Failed to load '{}' from {}: {}", libraryName, searchPath, e.getMessage());
      }
    }

    LOGGER.debug("Library '{}' not found in any custom search paths", libraryName);
    return false;
  }

  /**
   * Try to extract library from JAR and load it.
   */
  private boolean tryExtractAndLoad(String libraryName) {
    // Try different resource paths as fallback
    String osFamily = getOSFamily();
    String[] possiblePaths = {
        String.format("/native/%s/%s/%s%s%s", osFamily, OS_ARCH, LIBRARY_PREFIX, libraryName, LIBRARY_SUFFIX),
        String.format("/natives/%s/%s/%s%s%s", osFamily, OS_ARCH, LIBRARY_PREFIX, libraryName, LIBRARY_SUFFIX),
        String.format("/lib/%s/%s/%s%s%s", osFamily, OS_ARCH, LIBRARY_PREFIX, libraryName, LIBRARY_SUFFIX),
        String.format("/META-INF/natives/%s%s%s", LIBRARY_PREFIX, libraryName, LIBRARY_SUFFIX),
        String.format("/natives/%s%s%s", LIBRARY_PREFIX, libraryName, LIBRARY_SUFFIX),
        String.format("/%s%s%s", LIBRARY_PREFIX, libraryName, LIBRARY_SUFFIX)
    };

    for (String path : possiblePaths) {
      try (InputStream is = getClass().getResourceAsStream(path)) {
        if (is != null) {
          Path extractedLib = extractLibraryToTemp(is, libraryName);
          System.load(extractedLib.toAbsolutePath().toString());
          loadedLibraries.add(libraryName);
          LOGGER.info("Successfully extracted and loaded library '{}' from {}", libraryName, path);
          return true;
        }
      } catch (Exception e) {
        LOGGER.debug("Failed to extract and load '{}' from {}: {}", libraryName, path, e.getMessage());
      }
    }

    LOGGER.error("Failed to load library '{}' - all extraction strategies exhausted", libraryName);
    return false;
  }

  /**
   * Get the resource path for the current platform.
   */
  private String getResourcePath(String libraryName) {
    String osFamily = getOSFamily();
    // Try multiple resource paths to match the actual file structure
    String[] possiblePaths = {
        String.format("/natives/%s%s%s", LIBRARY_PREFIX, libraryName, LIBRARY_SUFFIX),
        String.format("/native/%s/%s/%s%s%s", osFamily, OS_ARCH, LIBRARY_PREFIX, libraryName, LIBRARY_SUFFIX),
        String.format("/natives/%s/%s/%s%s%s", osFamily, OS_ARCH, LIBRARY_PREFIX, libraryName, LIBRARY_SUFFIX),
        String.format("/%s%s%s", LIBRARY_PREFIX, libraryName, LIBRARY_SUFFIX)
    };
    
    for (String path : possiblePaths) {
      if (getClass().getResource(path) != null) {
        return path;
      }
    }
    return possiblePaths[0]; // Return first path as fallback
  }

  /**
   * Get the OS family name for resource paths.
   */
  public String getOSFamily() {
    if (OS_NAME.contains("win")) {
      return "windows";
    } else if (OS_NAME.contains("mac")) {
      return "macos";
    } else if (OS_NAME.contains("linux")) {
      return "linux";
    } else if (OS_NAME.contains("solaris") || OS_NAME.contains("sunos")) {
      return "solaris";
    } else {
      return "unknown";
    }
  }

  /**
   * Get custom search paths for native libraries.
   */
  private List<String> getCustomSearchPaths() {
    List<String> paths = new ArrayList<>();

    // Add current working directory
    paths.add(".");
    
    // Add common resource directories
    paths.add("src/main/resources/natives");
    paths.add("src/main/resources/lib");
    paths.add("resources/natives");
    paths.add("resources/lib");

    // Add common system library directories
    if (OS_NAME.contains("win")) {
      paths.add("C:\\Windows\\System32");
      paths.add("C:\\Windows\\SysWOW64");
      paths.add("C:\\Program Files\\Java\\jre\\bin");
    } else if (OS_NAME.contains("mac")) {
      paths.add("/usr/local/lib");
      paths.add("/opt/local/lib");
      paths.add("/Library/Java/JavaVirtualMachines");
    } else {
      paths.add("/usr/lib");
      paths.add("/usr/local/lib");
      paths.add("/lib");
      paths.add("/opt/java/lib");
    }

    // Add paths from environment variables
    String customPath = System.getenv("KNEAF_NATIVE_PATH");
    if (customPath != null) {
      paths.addAll(Arrays.asList(customPath.split(File.pathSeparator)));
    }

    return paths;
  }

  /**
   * Extract library from input stream to temporary file.
   */
  private Path extractLibraryToTemp(InputStream is, String libraryName) throws IOException {
    String tempFileName = LIBRARY_PREFIX + libraryName + "_" + System.nanoTime() + LIBRARY_SUFFIX;
    Path tempFile = tempDirectory.resolve(tempFileName);

    try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
      byte[] buffer = new byte[8192];
      int bytesRead;
      while ((bytesRead = is.read(buffer)) != -1) {
        fos.write(buffer, 0, bytesRead);
      }
    }

    // Make sure the file is executable (important for Unix-like systems)
    if (!OS_NAME.contains("win")) {
      makeExecutable(tempFile);
    }

    return tempFile;
  }

  /**
   * Make a file executable (Unix-only).
   */
  private void makeExecutable(Path file) {
    try {
      Process process = new ProcessBuilder("chmod", "+x", file.toString()).start();
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        LOGGER.warn("chmod command failed with exit code: {} for file: {}", exitCode, file);
      }
    } catch (Exception e) {
      LOGGER.warn("Failed to make library executable: {}", file, e);
    }
  }

  /**
   * Recursively delete a directory.
   */
  private void deleteDirectoryRecursively(Path directory) throws IOException {
    if (Files.exists(directory)) {
      Files.walk(directory)
          .sorted((a, b) -> b.compareTo(a)) // Reverse order for deletion
          .forEach(path -> {
            try {
              Files.delete(path);
            } catch (IOException e) {
              LOGGER.warn("Failed to delete: {}", path, e);
            }
          });
    }
  }

  /**
   * Helper method to execute load strategies with consistent logging.
   */
  private boolean tryLoadStrategy(String libraryName, LoadStrategy strategy) {
    try {
      return strategy.load(libraryName);
    } catch (Exception e) {
      LOGGER.error("Unexpected error loading library '{}' with strategy {}: {}", libraryName, strategy.getClass().getSimpleName(), e);
      return false;
    }
  }

  /**
   * Functional interface for library load strategies.
   */
  @FunctionalInterface
  private interface LoadStrategy {
    boolean load(String libraryName) throws Exception;
  }

  /**
   * Check if a library is already loaded.
   *
   * @param libraryName The library name to check
   * @return true if the library is loaded
   */
  public boolean isLibraryLoaded(String libraryName) {
    return loadedLibraries.contains(libraryName);
  }

  /**
   * Get the list of currently loaded libraries.
   *
   * @return Set of loaded library names
   */
  public Set<String> getLoadedLibraries() {
    return new HashSet<>(loadedLibraries);
  }

  /**
   * Force reload a library (useful for development).
   *
   * @param libraryName The library name to reload
   * @return true if reloaded successfully
   */
  public boolean reloadLibrary(String libraryName) {
    loadedLibraries.remove(libraryName);
    return loadNativeLibrary(libraryName);
  }

  /**
   * Get platform information for debugging.
   *
   * @return Platform information string
   */
  public String getPlatformInfo() {
    return String.format("OS: %s (%s), Arch: %s, Library format: %s[name]%s",
        OS_NAME, getOSFamily(), OS_ARCH, LIBRARY_PREFIX, LIBRARY_SUFFIX);
  }

  /**
   * Get the singleton instance of NativeLibraryLoader.
   *
   * @return The singleton instance
   */
  public static NativeLibraryLoader getInstance() {
    return INSTANCE;
  }
}