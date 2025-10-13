package com.kneaf.core.performance.bridge;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/** Utility class for loading native libraries with fallback mechanisms */
public class NativeLibraryLoader {

  private static final String LIBRARY_NAME = "rustperf";
  private static volatile boolean libraryLoaded = false;
  private static volatile boolean initializationAttempted = false;

  /** Attempt to load the native library with multiple fallback strategies */
  public static synchronized boolean loadNativeLibrary() {
    if (initializationAttempted) {
      return libraryLoaded;
    }

    initializationAttempted = true;

    List<String> errors = new ArrayList<>();

    // Strategy 0: Try loading from build resources first (highest priority)
    try {
      System.out.println("NativeLibraryLoader: Attempting to load from build resources first");
      Path buildResourcesPath = Paths.get("build", "resources", "main", "natives", "rustperf.dll");
      if (Files.exists(buildResourcesPath)) {
        System.out.println("NativeLibraryLoader: Found library in build resources: " + buildResourcesPath.toAbsolutePath());
        System.load(buildResourcesPath.toAbsolutePath().toString());
        libraryLoaded = true;
        System.out.println("NativeLibraryLoader: Successfully loaded from build resources");
        return true;
      } else {
        System.out.println("NativeLibraryLoader: Build resources library not found at: " + buildResourcesPath.toAbsolutePath());
      }
    } catch (Exception e) {
      errors.add("Build resources loading failed: " + e.getMessage());
      System.out.println("NativeLibraryLoader: Build resources loading failed: " + e.getMessage());
    }

    // Strategy 1: Try System.loadLibrary (requires library in java.library.path)
    try {
      System.out.println("NativeLibraryLoader: Attempting System.loadLibrary(\"" + LIBRARY_NAME + "\")");
      System.loadLibrary(LIBRARY_NAME);
      libraryLoaded = true;
      System.out.println("NativeLibraryLoader: Successfully loaded via System.loadLibrary");
      return true;
    } catch (UnsatisfiedLinkError e) {
      errors.add("System.loadLibrary failed: " + e.getMessage());
      System.out.println("NativeLibraryLoader: System.loadLibrary failed: " + e.getMessage());
    } catch (Exception e) {
      errors.add("System.loadLibrary exception: " + e.getMessage());
      System.out.println("NativeLibraryLoader: System.loadLibrary exception: " + e.getMessage());
    }

    // Strategy 2: Try loading from JAR resources
    try {
      System.out.println("NativeLibraryLoader: Attempting to load from JAR resources");
      if (loadFromResources()) {
        libraryLoaded = true;
        System.out.println("NativeLibraryLoader: Successfully loaded from JAR resources");
        return true;
      }
    } catch (Exception e) {
      errors.add("Resource loading failed: " + e.getMessage());
      System.out.println("NativeLibraryLoader: Resource loading failed: " + e.getMessage());
    }

    // Strategy 3: Try loading from build directory
    try {
      System.out.println("NativeLibraryLoader: Attempting to load from build directory");
      if (loadFromBuildDirectory()) {
        libraryLoaded = true;
        System.out.println("NativeLibraryLoader: Successfully loaded from build directory");
        return true;
      }
    } catch (Exception e) {
      errors.add("Build directory loading failed: " + e.getMessage());
      System.out.println("NativeLibraryLoader: Build directory loading failed: " + e.getMessage());
    }

    // Strategy 4: Try loading with platform-specific names
    try {
      System.out.println("NativeLibraryLoader: Attempting platform-specific loading");
      if (loadWithPlatformNames()) {
        libraryLoaded = true;
        System.out.println("NativeLibraryLoader: Successfully loaded with platform-specific names");
        return true;
      }
    } catch (Exception e) {
      errors.add("Platform-specific loading failed: " + e.getMessage());
      System.out.println("NativeLibraryLoader: Platform-specific loading failed: " + e.getMessage());
    }

    // Log all attempts
    System.err.println(
        "Failed to load native library '" + LIBRARY_NAME + "'. Attempted strategies:");
    for (String error : errors) {
      System.err.println("  - " + error);
    }

    return false;
  }

  /** Load native library from JAR resources */
  private static boolean loadFromResources() throws IOException {
    String libName = getPlatformLibraryName();
    String resourcePath = "natives/" + libName;

    try (InputStream in =
        NativeLibraryLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IOException("Native library not found in resources: " + resourcePath);
      }

      // Create temp directory
      Path tempDir = Files.createTempDirectory("kneafcore-natives");
      Path tempFile = tempDir.resolve(libName);

      // Copy library to temp location
      Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);

      // Load the library
      System.load(tempFile.toAbsolutePath().toString());

      // Schedule cleanup
      tempFile.toFile().deleteOnExit();
      tempDir.toFile().deleteOnExit();

      return true;
    }
  }

  /** Load native library from build directory */
  private static boolean loadFromBuildDirectory() throws IOException {
    String libName = getPlatformLibraryName();
    Path buildPath = Paths.get("build", "generated", "resources", "natives", libName);

    if (Files.exists(buildPath)) {
      System.load(buildPath.toAbsolutePath().toString());
      return true;
    }

    // Try alternative paths inside the project where the native build may output
    Path[] alternativePaths = {
      Paths.get("build", "generated", "resources", "natives", "rustperf.dll"),
      Paths.get("build", "generated", "resources", "natives", "librustperf.so"),
      Paths.get("build", "generated", "resources", "natives", "librustperf.dylib"),
      // Also check Rust target folders (common for local development builds)
      Paths.get("rust", "target", "release", "rustperf.dll"),
      Paths.get("rust", "target", "release", "librustperf.so"),
      Paths.get("rust", "target", "release", "librustperf.dylib"),
      Paths.get("rust", "target", "debug", "rustperf.dll"),
      Paths.get("rust", "target", "debug", "librustperf.so"),
      Paths.get("rust", "target", "debug", "librustperf.dylib")
    };

    for (Path path : alternativePaths) {
      if (Files.exists(path)) {
        System.out.println("NativeLibraryLoader: Found library at: " + path.toAbsolutePath());
        System.load(path.toAbsolutePath().toString());
        return true;
      } else {
        System.out.println("NativeLibraryLoader: Library not found at: " + path.toAbsolutePath());
      }
    }

    throw new IOException("Native library not found in build directory: " + buildPath);
  }

  /** Load with platform-specific library names */
  private static boolean loadWithPlatformNames() {
    String osName = System.getProperty("os.name").toLowerCase();
    String[] possibleNames;

    if (osName.contains("windows")) {
      possibleNames = new String[] {"rustperf.dll", "librustperf.dll"};
    } else if (osName.contains("mac")) {
      possibleNames = new String[] {"librustperf.dylib", "librustperf.jnilib"};
    } else {
      possibleNames = new String[] {"librustperf.so", "rustperf.so"};
    }

    for (String name : possibleNames) {
      try {
        System.loadLibrary(name.replace(".dll", "").replace(".so", "").replace(".dylib", ""));
        return true;
      } catch (UnsatisfiedLinkError e) {
        // Try next name
      }
    }

    return false;
  }

  /** Get platform-specific library name */
  private static String getPlatformLibraryName() {
    String osName = System.getProperty("os.name").toLowerCase();

    if (osName.contains("windows")) {
      return "rustperf.dll";
    } else if (osName.contains("mac")) {
      return "librustperf.dylib";
    } else {
      return "librustperf.so";
    }
  }

  /** Check if native library is loaded */
  public static boolean isLibraryLoaded() {
    return libraryLoaded;
  }

  /** Get library loading status with details */
  public static String getLoadingStatus() {
    if (libraryLoaded) {
      return "Native library '" + LIBRARY_NAME + "' successfully loaded";
    } else if (initializationAttempted) {
      return "Native library '" + LIBRARY_NAME + "' failed to load (initialization attempted)";
    } else {
      return "Native library '" + LIBRARY_NAME + "' not loaded (initialization not attempted)";
    }
  }
}
