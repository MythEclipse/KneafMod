package com.kneaf.core.performance.bridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
  private static final Set<String> loadedLibraries = ConcurrentHashMap.newKeySet();
  private static final AtomicBoolean initialized = new AtomicBoolean(false);
  private static volatile Path tempDirectory;

  // Platform-specific library prefixes and suffixes
  private static final String LIBRARY_PREFIX;
  private static final String LIBRARY_SUFFIX;

  static {
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
  public static boolean loadNativeLibrary(String libraryName) {
    if (libraryName == null || libraryName.trim().isEmpty()) {
      LOGGER.error("Library name cannot be null or empty");
      return false;
    }

    // Check if already loaded
    if (loadedLibraries.contains(libraryName)) {
      LOGGER.debug("Library '{}' already loaded", libraryName);
      return true;
    }

    initializeIfNeeded();

    // Try multiple loading strategies in order
    return tryLoadFromSystemPath(libraryName) ||
           tryLoadFromClasspath(libraryName) ||
           tryLoadFromCustomPaths(libraryName) ||
           tryExtractAndLoad(libraryName);
  }

  /**
   * Load the default native library (backward compatibility method).
   *
   * @return true if the library was loaded successfully
   */
  public static boolean loadNativeLibrary() {
    // Try to load the main kneaf library
    return loadNativeLibrary("kneaf") ||
           loadNativeLibrary("libkneaf") ||
           loadNativeLibrary("kneafcore");
  }

  /**
   * Initialize the loader if not already done.
   */
  private static void initializeIfNeeded() {
    if (initialized.compareAndSet(false, true)) {
      try {
        createTempDirectory();
        logPlatformInfo();
      } catch (Exception e) {
        LOGGER.error("Failed to initialize NativeLibraryLoader", e);
      }
    }
  }

  /**
   * Create a temporary directory for extracted libraries.
   */
  private static void createTempDirectory() throws IOException {
    String tempDirName = "kneaf-native-libs-" + System.currentTimeMillis();
    tempDirectory = Files.createTempDirectory(tempDirName);

    // Add shutdown hook to cleanup temp directory
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        if (tempDirectory != null && Files.exists(tempDirectory)) {
          deleteDirectoryRecursively(tempDirectory);
        }
      } catch (Exception e) {
        LOGGER.warn("Failed to cleanup temporary directory: {}", tempDirectory, e);
      }
    }));

    LOGGER.debug("Created temporary directory for native libraries: {}", tempDirectory);
  }

  /**
   * Log platform and system information for debugging.
   */
  private static void logPlatformInfo() {
    LOGGER.info("NativeLibraryLoader initialized:");
    LOGGER.info("  OS: {} ({})", OS_NAME, getOSFamily());
    LOGGER.info("  Architecture: {}", OS_ARCH);
    LOGGER.info("  Java Library Path: {}", JAVA_LIBRARY_PATH);
    LOGGER.info("  Library prefix: '{}', suffix: '{}'", LIBRARY_PREFIX, LIBRARY_SUFFIX);
  }

  /**
   * Try to load library from system library path.
   */
  private static boolean tryLoadFromSystemPath(String libraryName) {
    try {
      String fullLibraryName = LIBRARY_PREFIX + libraryName + LIBRARY_SUFFIX;
      System.loadLibrary(fullLibraryName);
      loadedLibraries.add(libraryName);
      LOGGER.info("Successfully loaded library '{}' from system path", fullLibraryName);
      return true;
    } catch (UnsatisfiedLinkError e) {
      LOGGER.debug("Library '{}' not found in system path: {}", libraryName, e.getMessage());
      return false;
    }
  }

  /**
   * Try to load library from classpath resources.
   */
  private static boolean tryLoadFromClasspath(String libraryName) {
    String resourcePath = getResourcePath(libraryName);
    if (resourcePath == null) {
      return false;
    }

    try (InputStream is = NativeLibraryLoader.class.getResourceAsStream(resourcePath)) {
      if (is == null) {
        LOGGER.debug("Library resource not found: {}", resourcePath);
        return false;
      }

      Path extractedLib = extractLibraryToTemp(is, libraryName);
      System.load(extractedLib.toAbsolutePath().toString());
      loadedLibraries.add(libraryName);
      LOGGER.info("Successfully loaded library '{}' from classpath", libraryName);
      return true;
    } catch (Exception e) {
      LOGGER.debug("Failed to load library '{}' from classpath: {}", libraryName, e.getMessage());
      return false;
    }
  }

  /**
   * Try to load library from custom search paths.
   */
  private static boolean tryLoadFromCustomPaths(String libraryName) {
    List<String> searchPaths = getCustomSearchPaths();

    for (String searchPath : searchPaths) {
      try {
        Path libPath = Paths.get(searchPath, LIBRARY_PREFIX + libraryName + LIBRARY_SUFFIX);
        if (Files.exists(libPath)) {
          System.load(libPath.toAbsolutePath().toString());
          loadedLibraries.add(libraryName);
          LOGGER.info("Successfully loaded library '{}' from custom path: {}", libraryName, searchPath);
          return true;
        }
      } catch (UnsatisfiedLinkError e) {
        LOGGER.debug("Failed to load '{}' from {}: {}", libraryName, searchPath, e.getMessage());
      }
    }

    return false;
  }

  /**
   * Try to extract library from JAR and load it.
   */
  private static boolean tryExtractAndLoad(String libraryName) {
    // This is a fallback that tries different resource paths
    String[] possiblePaths = {
        "/native/" + getOSFamily() + "/" + OS_ARCH + "/" + LIBRARY_PREFIX + libraryName + LIBRARY_SUFFIX,
        "/natives/" + getOSFamily() + "/" + OS_ARCH + "/" + LIBRARY_PREFIX + libraryName + LIBRARY_SUFFIX,
        "/lib/" + getOSFamily() + "/" + OS_ARCH + "/" + LIBRARY_PREFIX + libraryName + LIBRARY_SUFFIX,
        "/META-INF/natives/" + LIBRARY_PREFIX + libraryName + LIBRARY_SUFFIX
    };

    for (String path : possiblePaths) {
      try (InputStream is = NativeLibraryLoader.class.getResourceAsStream(path)) {
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

    return false;
  }

  /**
   * Get the resource path for the current platform.
   */
  private static String getResourcePath(String libraryName) {
    String osFamily = getOSFamily();
    String resourcePath = String.format("/native/%s/%s/%s%s%s",
        osFamily, OS_ARCH, LIBRARY_PREFIX, libraryName, LIBRARY_SUFFIX);
    return resourcePath;
  }

  /**
   * Get the OS family name for resource paths.
   */
  private static String getOSFamily() {
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
  private static List<String> getCustomSearchPaths() {
    List<String> paths = new ArrayList<>();

    // Add current working directory
    paths.add(".");

    // Add common library directories
    if (OS_NAME.contains("win")) {
      paths.add("C:\\Windows\\System32");
      paths.add("C:\\Windows\\SysWOW64");
    } else if (OS_NAME.contains("mac")) {
      paths.add("/usr/local/lib");
      paths.add("/opt/local/lib");
    } else {
      paths.add("/usr/lib");
      paths.add("/usr/local/lib");
      paths.add("/lib");
    }

    // Add paths from environment variables
    String customPath = System.getenv("KNEAF_NATIVE_PATH");
    if (customPath != null) {
      for (String path : customPath.split(File.pathSeparator)) {
        paths.add(path);
      }
    }

    return paths;
  }

  /**
   * Extract library from input stream to temporary file.
   */
  private static Path extractLibraryToTemp(InputStream is, String libraryName) throws IOException {
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
      try {
        Process process = new ProcessBuilder("chmod", "+x", tempFile.toString()).start();
        process.waitFor();
      } catch (Exception e) {
        LOGGER.warn("Failed to make library executable: {}", tempFile, e);
      }
    }

    return tempFile;
  }

  /**
   * Recursively delete a directory.
   */
  private static void deleteDirectoryRecursively(Path directory) throws IOException {
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
   * Check if a library is already loaded.
   *
   * @param libraryName The library name to check
   * @return true if the library is loaded
   */
  public static boolean isLibraryLoaded(String libraryName) {
    return loadedLibraries.contains(libraryName);
  }

  /**
   * Get the list of currently loaded libraries.
   *
   * @return Set of loaded library names
   */
  public static Set<String> getLoadedLibraries() {
    return new HashSet<>(loadedLibraries);
  }

  /**
   * Force reload a library (useful for development).
   *
   * @param libraryName The library name to reload
   * @return true if reloaded successfully
   */
  public static boolean reloadLibrary(String libraryName) {
    loadedLibraries.remove(libraryName);
    return loadNativeLibrary(libraryName);
  }

  /**
   * Get platform information for debugging.
   *
   * @return Platform information string
   */
  public static String getPlatformInfo() {
    return String.format("OS: %s (%s), Arch: %s, Library format: %s[name]%s",
        OS_NAME, getOSFamily(), OS_ARCH, LIBRARY_PREFIX, LIBRARY_SUFFIX);
  }
}