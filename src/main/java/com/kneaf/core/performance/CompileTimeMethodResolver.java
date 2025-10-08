package com.kneaf.core.performance;

import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compile-time method resolution system to eliminate reflection overhead Uses MethodHandles and
 * LambdaMetafactory for zero-reflection method calls
 */
public final class CompileTimeMethodResolver {
  private static final CompileTimeMethodResolver INSTANCE = new CompileTimeMethodResolver();
  private static final Logger LOGGER = LoggerFactory.getLogger(CompileTimeMethodResolver.class);

  // Method handle cache for compiled method access
  private final ConcurrentHashMap<String, MethodHandle> methodHandleCache =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, CallSite> callSiteCache = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Function<Object, Object>> lambdaCache =
      new ConcurrentHashMap<>();

  // Performance metrics
  private final AtomicLong cacheHits = new AtomicLong(0);
  private final AtomicLong cacheMisses = new AtomicLong(0);
  private final AtomicLong reflectionFallbacks = new AtomicLong(0);
  private final AtomicLong compilationErrors = new AtomicLong(0);

  // Circuit breaker for method compilation
  private final AtomicLong compilationFailures = new AtomicLong(0);
  private static final int MAX_COMPILATION_RETRIES = 3;
  private static final int CIRCUIT_BREAKER_THRESHOLD = 5;
  private static final long COMPILATION_TIMEOUT_MS = 5000; // 5 second timeout

  // Method signature constants
  private static final MethodType VOID_METHOD = MethodType.methodType(void.class);
  private static final MethodType BOOLEAN_METHOD = MethodType.methodType(boolean.class);
  private static final MethodType INT_METHOD = MethodType.methodType(int.class);
  private static final MethodType LONG_METHOD = MethodType.methodType(long.class);
  private static final MethodType FLOAT_METHOD = MethodType.methodType(float.class);

  // Lookup for method handle creation
  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

  /** Pre-compiled method signatures for common operations */
  public enum MethodSignature {
    // Entity methods
    GET_ID("getId", LONG_METHOD),
    GET_POSITION("getPosition", MethodType.methodType(float[].class)),
    GET_BOUNDS("getBounds", MethodType.methodType(float[].class)),
    IS_ALIVE("isAlive", BOOLEAN_METHOD),
    GET_TYPE("getType", MethodType.methodType(String.class)),

    // Block methods
    GET_BLOCK_ID("getBlockId", INT_METHOD),
    GET_BLOCK_STATE("getBlockState", INT_METHOD),
    GET_BLOCK_METADATA("getBlockMetadata", INT_METHOD),
    IS_BLOCK_SOLID("isSolid", BOOLEAN_METHOD),
    GET_BLOCK_HARDNESS("getHardness", FLOAT_METHOD),

    // World methods
    GET_WORLD_TIME("getWorldTime", LONG_METHOD),
    GET_CHUNK_AT("getChunkAt", MethodType.methodType(Object.class, int.class, int.class)),
    GET_BLOCK_AT(
        "getBlockAt", MethodType.methodType(Object.class, int.class, int.class, int.class)),
    SET_BLOCK_AT("setBlockAt", VOID_METHOD, int.class, int.class, int.class, Object.class),

    // Player methods
    GET_PLAYER_NAME("getName", MethodType.methodType(String.class)),
    GET_PLAYER_UUID("getUniqueId", MethodType.methodType(String.class)),
    GET_PLAYER_POSITION("getPosition", MethodType.methodType(double[].class)),
    GET_PLAYER_HEALTH("getHealth", FLOAT_METHOD),
    SET_PLAYER_HEALTH("setHealth", VOID_METHOD, float.class),

    // Item methods
    GET_ITEM_ID("getItemId", INT_METHOD),
    GET_ITEM_COUNT("getCount", INT_METHOD),
    GET_ITEM_DAMAGE("getDamage", INT_METHOD),
    GET_ITEM_NBT("getNbt", MethodType.methodType(Object.class)),

    // Generic methods
    TO_STRING("toString", MethodType.methodType(String.class)),
    HASH_CODE("hashCode", INT_METHOD),
    EQUALS("equals", BOOLEAN_METHOD, Object.class),
    GET_CLASS("getClass", MethodType.methodType(Class.class));

    private final String methodName;
    private final MethodType methodType;
    private final Class<?>[] parameterTypes;

    MethodSignature(String methodName, MethodType methodType, Class<?>... parameterTypes) {
      this.methodName = methodName;
      this.methodType = methodType;
      this.parameterTypes = parameterTypes;
    }

    public String getMethodName() {
      return methodName;
    }

    public MethodType getMethodType() {
      return methodType;
    }

    public Class<?>[] getParameterTypes() {
      return parameterTypes;
    }
  }

  /** Method call wrapper for type-safe invocations */
  public interface MethodCall<T, R> {
    R invoke(T target) throws Throwable;
  }

  /** Method call with parameters */
  public interface MethodCallWithParams<T, R> {
    R invoke(T target, Object... params) throws Throwable;
  }

  private CompileTimeMethodResolver() {
    // Pre-compile common methods during initialization
    precompileCommonMethods();
  }

  /** Get singleton instance */
  public static CompileTimeMethodResolver getInstance() {
    return INSTANCE;
  }

  /** Pre-compile commonly used methods to eliminate cold start overhead */
  private void precompileCommonMethods() {
    try {
      // Pre-compile entity methods
      boolean entityIdSuccess = compileMethod("com.kneaf.core.data.EntityData", MethodSignature.GET_ID);
      boolean entityPosSuccess = compileMethod("com.kneaf.core.data.EntityData", MethodSignature.GET_POSITION);
      boolean entityAliveSuccess = compileMethod("com.kneaf.core.data.EntityData", MethodSignature.IS_ALIVE);

      // Pre-compile block methods
      boolean blockIdSuccess = compileMethod("com.kneaf.core.data.BlockEntityData", MethodSignature.GET_BLOCK_ID);
      boolean blockStateSuccess = compileMethod("com.kneaf.core.data.BlockEntityData", MethodSignature.GET_BLOCK_STATE);
      boolean blockSolidSuccess = compileMethod("com.kneaf.core.data.BlockEntityData", MethodSignature.IS_BLOCK_SOLID);

      // Pre-compile player methods
      boolean playerNameSuccess = compileMethod("com.kneaf.core.data.PlayerData", MethodSignature.GET_PLAYER_NAME);
      boolean playerPosSuccess = compileMethod("com.kneaf.core.data.PlayerData", MethodSignature.GET_PLAYER_POSITION);
      boolean playerHealthSuccess = compileMethod("com.kneaf.core.data.PlayerData", MethodSignature.GET_PLAYER_HEALTH);

      // Pre-compile world methods
      boolean worldTimeSuccess = compileMethod("net.minecraft.world.level.Level", MethodSignature.GET_WORLD_TIME);
      boolean blockAtSuccess = compileMethod("net.minecraft.world.level.Level", MethodSignature.GET_BLOCK_AT);
      boolean setBlockSuccess = compileMethod("net.minecraft.world.level.Level", MethodSignature.SET_BLOCK_AT);

      // Log pre-compilation results
      LOGGER.info("Pre-compilation results:");
      LOGGER.info("Entity methods: ID={}, Position={}, Alive={}", entityIdSuccess, entityPosSuccess, entityAliveSuccess);
      LOGGER.info("Block methods: ID={}, State={}, Solid={}", blockIdSuccess, blockStateSuccess, blockSolidSuccess);
      LOGGER.info("Player methods: Name={}, Position={}, Health={}", playerNameSuccess, playerPosSuccess, playerHealthSuccess);
      LOGGER.info("World methods: Time={}, GetBlock={}, SetBlock={}", worldTimeSuccess, blockAtSuccess, setBlockSuccess);

    } catch (Exception e) {
      LOGGER.error("Error pre-compiling common methods: {}", e.getMessage(), e);
    }
  }

  /** Compile method for zero-reflection access - returns true if successful */
  public boolean compileMethod(String className, MethodSignature signature) {
    String cacheKey = className + "." + signature.getMethodName();

    try {
      Class<?> clazz = Class.forName(className);
      Method method = findMethod(clazz, signature);

      if (method == null) {
        LOGGER.warn("Method not found: {} in class {}", signature.getMethodName(), className);
        return false;
      }

      method.setAccessible(true);

      // Create method handle
      MethodHandle handle = LOOKUP.unreflect(method);

      // Cache method handle
      methodHandleCache.put(cacheKey, handle);

      // Create optimized lambda for common signatures
      if (signature.getParameterTypes().length == 0) {
        createOptimizedLambda(cacheKey, handle, signature);
      }

      return true;

    } catch (Exception e) {
      compilationErrors.incrementAndGet();
      LOGGER.error("Error compiling method {}: {}", cacheKey, e.getMessage(), e);
      return false;
    } catch (Error e) {
      compilationErrors.incrementAndGet();
      LOGGER.error("Critical error compiling method {}: {}", cacheKey, e.getMessage(), e);
      return false;
    }
  }

  /** Find method in class hierarchy */
  private Method findMethod(Class<?> clazz, MethodSignature signature) {
    Class<?> currentClass = clazz;

    while (currentClass != null) {
      try {
        if (signature.getParameterTypes().length == 0) {
          return currentClass.getDeclaredMethod(signature.getMethodName());
        } else {
          return currentClass.getDeclaredMethod(
              signature.getMethodName(), signature.getParameterTypes());
        }
      } catch (NoSuchMethodException e) {
        currentClass = currentClass.getSuperclass();
      }
    }

    // Try interfaces
    for (Class<?> iface : clazz.getInterfaces()) {
      try {
        if (signature.getParameterTypes().length == 0) {
          return iface.getDeclaredMethod(signature.getMethodName());
        } else {
          return iface.getDeclaredMethod(signature.getMethodName(), signature.getParameterTypes());
        }
      } catch (NoSuchMethodException e) {
        // Continue to next interface
      }
    }

    return null;
  }

  /** Create optimized lambda using MethodHandles and LambdaMetafactory */
  private void createOptimizedLambda(
      String cacheKey, MethodHandle handle, MethodSignature signature) {
    try {
      Class<?> returnType = signature.getMethodType().returnType();

      // Create functional interface implementation
      if (returnType == long.class) {
        CallSite callSite =
            LambdaMetafactory.metafactory(
                LOOKUP,
                "applyAsLong",
                MethodType.methodType(java.util.function.ToLongFunction.class),
                MethodType.methodType(long.class, Object.class),
                handle,
                handle.type());

        java.util.function.ToLongFunction<Object> lambda =
            (java.util.function.ToLongFunction<Object>) callSite.getTarget().invoke();

        lambdaCache.put(cacheKey, obj -> lambda.applyAsLong(obj));
        callSiteCache.put(cacheKey, callSite);

      } else if (returnType == int.class) {
        CallSite callSite =
            LambdaMetafactory.metafactory(
                LOOKUP,
                "applyAsInt",
                MethodType.methodType(java.util.function.ToIntFunction.class),
                MethodType.methodType(int.class, Object.class),
                handle,
                handle.type());

        java.util.function.ToIntFunction<Object> lambda =
            (java.util.function.ToIntFunction<Object>) callSite.getTarget().invoke();

        lambdaCache.put(cacheKey, obj -> (long) lambda.applyAsInt(obj));
        callSiteCache.put(cacheKey, callSite);

      } else if (returnType == boolean.class) {
        CallSite callSite =
            LambdaMetafactory.metafactory(
                LOOKUP,
                "test",
                MethodType.methodType(java.util.function.Predicate.class),
                MethodType.methodType(boolean.class, Object.class),
                handle,
                handle.type());

        java.util.function.Predicate<Object> lambda =
            (java.util.function.Predicate<Object>) callSite.getTarget().invoke();

        lambdaCache.put(cacheKey, obj -> lambda.test(obj));
        callSiteCache.put(cacheKey, callSite);

      } else if (returnType == float.class) {
        CallSite callSite =
            LambdaMetafactory.metafactory(
                LOOKUP,
                "apply",
                MethodType.methodType(java.util.function.ToDoubleFunction.class),
                MethodType.methodType(double.class, Object.class),
                handle,
                handle.type());

        java.util.function.ToDoubleFunction<Object> lambda =
            (java.util.function.ToDoubleFunction<Object>) callSite.getTarget().invoke();

        lambdaCache.put(cacheKey, obj -> (float) lambda.applyAsDouble(obj));
        callSiteCache.put(cacheKey, callSite);

      } else {
        // Generic object return type
        CallSite callSite =
            LambdaMetafactory.metafactory(
                LOOKUP,
                "apply",
                MethodType.methodType(Function.class),
                MethodType.methodType(Object.class, Object.class),
                handle,
                handle.type());

        Function<Object, Object> lambda = (Function<Object, Object>) callSite.getTarget().invoke();

        lambdaCache.put(cacheKey, lambda);
        callSiteCache.put(cacheKey, callSite);
      }

    } catch (Throwable e) {
      compilationErrors.incrementAndGet();
      LOGGER.error("Error creating optimized lambda for {}: {}", cacheKey, e.getMessage(), e);
    }
  }

  /** Invoke method without reflection using pre-compiled method handles */
  public <T, R> R invokeMethod(T target, String className, MethodSignature signature)
      throws Throwable {
    String cacheKey = className + "." + signature.getMethodName();

    // Try lambda cache first (fastest)
    Function<Object, Object> lambda = lambdaCache.get(cacheKey);
    if (lambda != null) {
      cacheHits.incrementAndGet();
      return (R) lambda.apply(target);
    }

    // Try method handle cache
    MethodHandle handle = methodHandleCache.get(cacheKey);
    if (handle != null) {
      cacheHits.incrementAndGet();
      return (R) handle.invoke(target);
    }

    cacheMisses.incrementAndGet();

    // Fallback to reflection
    return invokeWithReflection(target, className, signature);
  }

  /** Invoke method with parameters */
  public <T, R> R invokeMethodWithParams(
      T target, String className, MethodSignature signature, Object... params) throws Throwable {
    String cacheKey = className + "." + signature.getMethodName();

    MethodHandle handle = methodHandleCache.get(cacheKey);
    if (handle != null) {
      cacheHits.incrementAndGet();
      return (R) handle.invoke(target, params);
    }

    cacheMisses.incrementAndGet();

    // Fallback to reflection
    return invokeWithReflectionAndParams(target, className, signature, params);
  }

  /** Fallback reflection method (slower but always works) */
  private <T, R> R invokeWithReflection(T target, String className, MethodSignature signature)
      throws Throwable {
    reflectionFallbacks.incrementAndGet();

    try {
      Class<?> clazz = Class.forName(className);
      Method method = findMethod(clazz, signature);

      if (method == null) {
        throw new NoSuchMethodException("Method not found: " + signature.getMethodName());
      }

      method.setAccessible(true);
      return (R) method.invoke(target);

    } catch (Exception e) {
      throw new RuntimeException(
          "Reflection fallback failed for " + className + "." + signature.getMethodName(), e);
    }
  }

  /** Fallback reflection method with parameters */
  private <T, R> R invokeWithReflectionAndParams(
      T target, String className, MethodSignature signature, Object[] params) throws Throwable {
    reflectionFallbacks.incrementAndGet();

    try {
      Class<?> clazz = Class.forName(className);
      Method method = findMethod(clazz, signature);

      if (method == null) {
        throw new NoSuchMethodException("Method not found: " + signature.getMethodName());
      }

      method.setAccessible(true);
      return (R) method.invoke(target, params);

    } catch (Exception e) {
      throw new RuntimeException(
          "Reflection fallback failed for " + className + "." + signature.getMethodName(), e);
    }
  }

  /** Batch invoke methods on multiple targets */
  public <T, R> Map<T, R> batchInvokeMethod(
      Iterable<T> targets, String className, MethodSignature signature) {
    Map<T, R> results = new HashMap<>();
    String cacheKey = className + "." + signature.getMethodName();

    // Get cached method handle for batch processing
    Function<Object, Object> lambda = lambdaCache.get(cacheKey);
    MethodHandle handle = methodHandleCache.get(cacheKey);

    if (lambda != null) {
      // Use optimized lambda
      for (T target : targets) {
        try {
          R result = (R) lambda.apply(target);
          results.put(target, result);
        } catch (Exception e) {
          LOGGER.warn("Batch invocation failed for target: {}", e.getMessage(), e);
        }
      }
    } else if (handle != null) {
      // Use method handle
      for (T target : targets) {
        try {
          R result = (R) handle.invoke(target);
          results.put(target, result);
        } catch (Throwable e) {
          LOGGER.warn("Batch invocation failed for target: {}", e.getMessage(), e);
        }
      }
    } else {
      // Fallback to reflection for each target
      for (T target : targets) {
        try {
          R result = invokeWithReflection(target, className, signature);
          results.put(target, result);
        } catch (Throwable e) {
          LOGGER.warn("Batch reflection invocation failed for target: {}", e.getMessage(), e);
        }
      }
    }

    return results;
  }

  /** Create dynamic method caller for repeated invocations */
  public <T> MethodCall<T, Object> createMethodCaller(String className, MethodSignature signature) {
    String cacheKey = className + "." + signature.getMethodName();

    // Check circuit breaker
    if (compilationFailures.get() >= CIRCUIT_BREAKER_THRESHOLD) {
      System.err.println("Circuit breaker activated: Too many compilation failures (" + compilationFailures.get() + ")");
      throw new RuntimeException("Circuit breaker activated: Method compilation disabled due to repeated failures");
    }

    Function<Object, Object> lambda = lambdaCache.get(cacheKey);
    MethodHandle handle = methodHandleCache.get(cacheKey);

    if (lambda != null) {
      return target -> lambda.apply(target);
    } else if (handle != null) {
      return target -> {
        try {
          return handle.invoke(target);
        } catch (Throwable e) {
          throw new RuntimeException("Method invocation failed", e);
        }
      };
    } else {
      // Attempt compilation with retry, timeout, and circuit breaker
      int attempts = 0;
      boolean compiled = false;
      long startTime = System.currentTimeMillis();

      while (attempts < MAX_COMPILATION_RETRIES && !compiled) {
        attempts++;
        
        // Check timeout before attempting compilation
        if (System.currentTimeMillis() - startTime > COMPILATION_TIMEOUT_MS) {
          LOGGER.error("Method compilation timeout for {} after {} attempts", cacheKey, attempts);
          break;
        }

        // Exponential backoff with jitter
        if (attempts > 1) {
          long backoffMs = (long) (Math.pow(2, attempts - 1) * 100 + Math.random() * 100);
          try {
            Thread.sleep(backoffMs);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Compilation retry interrupted for {}", cacheKey);
            break;
          }
        }

        try {
          boolean compilationSuccess = compileMethod(className, signature);

          // Check if compilation succeeded by verifying cache presence
          if (compilationSuccess && (lambdaCache.containsKey(cacheKey) || methodHandleCache.containsKey(cacheKey))) {
            compiled = true;
            LOGGER.info("Method compilation successful for {} (attempt {})", cacheKey, attempts);
          } else {
            compilationErrors.incrementAndGet();
            LOGGER.warn("Method compilation failed for {}: compilation returned {}, cache contains: lambda={}, handle={} (attempt {})",
                cacheKey, compilationSuccess, lambdaCache.containsKey(cacheKey), methodHandleCache.containsKey(cacheKey), attempts);
          }

        } catch (Exception e) {
          compilationErrors.incrementAndGet();
          compilationFailures.incrementAndGet();
          LOGGER.error("Method compilation attempt {} failed for {}: {}", attempts, cacheKey, e.getMessage(), e);
        } catch (Error e) {
          compilationErrors.incrementAndGet();
          compilationFailures.incrementAndGet();
          LOGGER.error("Method compilation attempt {} failed with critical error for {}: {}", attempts, cacheKey, e.getMessage(), e);
          
          // Break early for certain unrecoverable errors
          if (e instanceof NoClassDefFoundError || e instanceof LinkageError) {
            LOGGER.error("Breaking retry loop due to unrecoverable error: {}", e.getClass().getSimpleName());
            break;
          }
        }
      }

      if (!compiled) {
        compilationFailures.incrementAndGet();
        throw new RuntimeException("Failed to compile method after " + attempts + " attempts: " + cacheKey);
      }

      // Return the compiled method caller using cached values
      Function<Object, Object> finalLambda = lambdaCache.get(cacheKey);
      MethodHandle finalHandle = methodHandleCache.get(cacheKey);

      if (finalLambda != null) {
        return target -> finalLambda.apply(target);
      } else if (finalHandle != null) {
        return target -> {
          try {
            return finalHandle.invoke(target);
          } catch (Throwable e) {
            throw new RuntimeException("Method invocation failed", e);
          }
        };
      } else {
        // This should not happen if compiled is true
        throw new RuntimeException("Unexpected error: method compiled but not cached: " + cacheKey);
      }
    }
  }

  /** Get performance statistics */
  public String getPerformanceStats() {
    long total = cacheHits.get() + cacheMisses.get();
    double hitRate = total > 0 ? (double) cacheHits.get() / total * 100.0 : 0.0;

    return String.format(
        "CompileTimeMethodResolver Stats:\n"
            + "Cache Hits: %d\n"
            + "Cache Misses: %d\n"
            + "Hit Rate: %.2f%%\n"
            + "Reflection Fallbacks: %d\n"
            + "Compilation Errors: %d\n"
            + "Compilation Failures: %d\n"
            + "Cached Methods: %d\n"
            + "Cached Lambdas: %d\n",
        cacheHits.get(),
        cacheMisses.get(),
        hitRate,
        reflectionFallbacks.get(),
        compilationErrors.get(),
        compilationFailures.get(),
        methodHandleCache.size(),
        lambdaCache.size());
  }

  /** Clear all caches */
  public void clearCaches() {
    methodHandleCache.clear();
    callSiteCache.clear();
    lambdaCache.clear();

    cacheHits.set(0);
    cacheMisses.set(0);
    reflectionFallbacks.set(0);
    compilationErrors.set(0);
    compilationFailures.set(0);
  }

  /** Benchmark method invocation performance */
  public <T> void benchmarkMethod(
      T target, String className, MethodSignature signature,  int iterations) {
    LOGGER.info("Benchmarking method: {}.{}", className, signature.getMethodName());

    // Warm up
    for (int i = 0; i < 1000; i++) {
      try {
        invokeMethod(target, className, signature);
      } catch (Throwable e) {
        // Ignore warm-up errors
      }
    }

    // Benchmark compiled method
    long startTime = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      try {
        invokeMethod(target, className, signature);
      } catch (Throwable e) {
        LOGGER.warn("Benchmark invocation failed: {}", e.getMessage(), e);
      }
    }
    long compiledTime = System.nanoTime() - startTime;

    // Benchmark reflection
    startTime = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      try {
        invokeWithReflection(target, className, signature);
      } catch (Throwable e) {
        LOGGER.warn("Reflection benchmark failed: {}", e.getMessage(), e);
      }
    }
    long reflectionTime = System.nanoTime() - startTime;

    LOGGER.info("Results for {} invocations:", iterations);
    LOGGER.info("Compiled method time: {} ms", compiledTime / 1_000_000);
    LOGGER.info("Reflection time: {} ms", reflectionTime / 1_000_000);
    LOGGER.info("Speedup: {}x", String.format("%.2f", (double) reflectionTime / compiledTime));
  }
}
