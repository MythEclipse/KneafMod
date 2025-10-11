package com.kneaf.core.performance;

import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
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
  private final ConcurrentHashMap<String, Function<Object, Object>> lambdaCache =
      new ConcurrentHashMap<>();

  // Method signature constants
  private static final MethodType BOOLEAN_METHOD = MethodType.methodType(boolean.class);
  private static final MethodType INT_METHOD = MethodType.methodType(int.class);
  private static final MethodType LONG_METHOD = MethodType.methodType(long.class);

  // Lookup for method handle creation
  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

  /** Pre-compiled method signatures for common operations */
  public enum MethodSignature {
    // Entity methods (core functionality)
    GET_ID("getId", LONG_METHOD),
    IS_ALIVE("isAlive", BOOLEAN_METHOD),
    GET_TYPE("getType", MethodType.methodType(String.class)),

    // Block methods (core functionality)
    GET_BLOCK_ID("getBlockId", INT_METHOD),
    IS_BLOCK_SOLID("isSolid", BOOLEAN_METHOD),

    // Generic methods
    TO_STRING("toString", MethodType.methodType(String.class));

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
      boolean entityAliveSuccess = compileMethod("com.kneaf.core.data.EntityData", MethodSignature.IS_ALIVE);
      boolean entityTypeSuccess = compileMethod("com.kneaf.core.data.EntityData", MethodSignature.GET_TYPE);

      // Pre-compile block methods
      boolean blockIdSuccess = compileMethod("com.kneaf.core.data.BlockEntityData", MethodSignature.GET_BLOCK_ID);
      boolean blockSolidSuccess = compileMethod("com.kneaf.core.data.BlockEntityData", MethodSignature.IS_BLOCK_SOLID);

      // Log pre-compilation results
      LOGGER.info("Pre-compilation results:");
      LOGGER.info("Entity methods: ID={}, Alive={}, Type={}", entityIdSuccess, entityAliveSuccess, entityTypeSuccess);
      LOGGER.info("Block methods: ID={}, Solid={}", blockIdSuccess, blockSolidSuccess);

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
      LOGGER.error("Error compiling method {}: {}", cacheKey, e.getMessage(), e);
      return false;
    } catch (Error e) {
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
      }

    } catch (Throwable e) {
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
      return (R) lambda.apply(target);
    }

    // Try method handle cache
    MethodHandle handle = methodHandleCache.get(cacheKey);
    if (handle != null) {
      return (R) handle.invoke(target);
    }

    // Fallback to reflection
    return invokeWithReflection(target, className, signature);
  }


  /** Fallback reflection method (slower but always works) */
  private <T, R> R invokeWithReflection(T target, String className, MethodSignature signature)
      throws Throwable {
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

}
