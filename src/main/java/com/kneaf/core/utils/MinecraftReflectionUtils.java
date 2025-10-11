package com.kneaf.core.utils;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Utility class untuk optimasi reflection dengan MethodHandles caching.
 * Mengganti penggunaan Class.forName() yang berulang dengan caching yang efisien.
 */
public final class MinecraftReflectionUtils {
    
    private static final ConcurrentMap<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, MethodHandle> METHOD_HANDLE_CACHE = new ConcurrentHashMap<>();
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    
    private MinecraftReflectionUtils() {
        // Utility class - prevent instantiation
    }
    
    /**
     * Mendapatkan class dengan caching untuk menghindari Class.forName() berulang.
     * 
     * @param className Nama kelas Minecraft yang ingin diakses
     * @return Class object atau null jika tidak ditemukan
     */
    public static Class<?> getCachedClass(String className) {
        return CLASS_CACHE.computeIfAbsent(className, key -> {
            try {
                return Class.forName(key);
            } catch (ClassNotFoundException e) {
                return null;
            }
        });
    }
    
    /**
     * Mendapatkan class dengan caching dan lazy loading.
     * 
     * @param className Nama kelas Minecraft yang ingin diakses
     * @param supplier Supplier untuk membuat class jika belum ada di cache
     * @return Class object atau null jika tidak ditemukan
     */
    public static Class<?> getCachedClass(String className, Supplier<Class<?>> supplier) {
        return CLASS_CACHE.computeIfAbsent(className, key -> {
            try {
                return supplier.get();
            } catch (Exception e) {
                return null;
            }
        });
    }
    
    /**
     * Mendapatkan MethodHandle dengan caching untuk method tertentu.
     * 
     * @param className Nama kelas yang berisi method
     * @param methodName Nama method yang ingin diakses
     * @param returnType Tipe return method
     * @param parameterTypes Tipe parameter method
     * @return MethodHandle atau null jika tidak ditemukan
     */
    public static MethodHandle getCachedMethodHandle(String className, String methodName, 
                                                    Class<?> returnType, Class<?>... parameterTypes) {
        String cacheKey = className + "." + methodName;
        
        return METHOD_HANDLE_CACHE.computeIfAbsent(cacheKey, key -> {
            try {
                Class<?> clazz = getCachedClass(className);
                if (clazz == null) {
                    return null;
                }
                
                return LOOKUP.findVirtual(clazz, methodName, 
                    MethodType.methodType(returnType, parameterTypes));
            } catch (Exception e) {
                return null;
            }
        });
    }
    
    /**
     * Mendapatkan MethodHandle dengan caching untuk static method.
     * 
     * @param className Nama kelas yang berisi static method
     * @param methodName Nama static method yang ingin diakses
     * @param returnType Tipe return method
     * @param parameterTypes Tipe parameter method
     * @return MethodHandle atau null jika tidak ditemukan
     */
    public static MethodHandle getCachedStaticMethodHandle(String className, String methodName,
                                                          Class<?> returnType, Class<?>... parameterTypes) {
        String cacheKey = className + ".static." + methodName;
        
        return METHOD_HANDLE_CACHE.computeIfAbsent(cacheKey, key -> {
            try {
                Class<?> clazz = getCachedClass(className);
                if (clazz == null) {
                    return null;
                }
                
                return LOOKUP.findStatic(clazz, methodName, 
                    MethodType.methodType(returnType, parameterTypes));
            } catch (Exception e) {
                return null;
            }
        });
    }
    
    /**
     * Memeriksa apakah suatu objek adalah instance dari kelas Minecraft tertentu.
     * 
     * @param obj Objek yang akan diperiksa
     * @param className Nama kelas Minecraft
     * @return true jika objek adalah instance dari kelas tersebut
     */
    public static boolean isInstanceOf(Object obj, String className) {
        Class<?> clazz = getCachedClass(className);
        return clazz != null && clazz.isInstance(obj);
    }
    
    /**
     * Memanggil method menggunakan MethodHandle dengan caching.
     * 
     * @param target Objek target yang akan memanggil method
     * @param className Nama kelas yang berisi method
     * @param methodName Nama method yang akan dipanggil
     * @param returnType Tipe return method
     * @param parameterTypes Tipe parameter method
     * @param args Argumen untuk method
     * @return Hasil pemanggilan method atau null jika gagal
     */
    public static Object invokeMethod(Object target, String className, String methodName,
                                     Class<?> returnType, Class<?>[] parameterTypes, Object... args) {
        MethodHandle handle = getCachedMethodHandle(className, methodName, returnType, parameterTypes);
        if (handle == null) {
            return null;
        }
        
        try {
            return handle.invokeWithArguments(args);
        } catch (Throwable e) {
            return null;
        }
    }
    
    /**
     * Memanggil static method menggunakan MethodHandle dengan caching.
     * 
     * @param className Nama kelas yang berisi static method
     * @param methodName Nama static method yang akan dipanggil
     * @param returnType Tipe return method
     * @param parameterTypes Tipe parameter method
     * @param args Argumen untuk method
     * @return Hasil pemanggilan method atau null jika gagal
     */
    public static Object invokeStaticMethod(String className, String methodName,
                                           Class<?> returnType, Class<?>[] parameterTypes, Object... args) {
        MethodHandle handle = getCachedStaticMethodHandle(className, methodName, returnType, parameterTypes);
        if (handle == null) {
            return null;
        }
        
        try {
            return handle.invokeWithArguments(args);
        } catch (Throwable e) {
            return null;
        }
    }
    
    /**
     * Mendapatkan constructor dengan caching.
     * 
     * @param className Nama kelas yang berisi constructor
     * @param parameterTypes Tipe parameter constructor
     * @return MethodHandle untuk constructor atau null jika tidak ditemukan
     */
    public static MethodHandle getCachedConstructor(String className, Class<?>... parameterTypes) {
        String cacheKey = className + ".constructor";
        
        return METHOD_HANDLE_CACHE.computeIfAbsent(cacheKey, key -> {
            try {
                Class<?> clazz = getCachedClass(className);
                if (clazz == null) {
                    return null;
                }
                
                return LOOKUP.findConstructor(clazz, MethodType.methodType(void.class, parameterTypes));
            } catch (Exception e) {
                return null;
            }
        });
    }
    
    /**
     * Membuat instance baru menggunakan constructor dengan caching.
     * 
     * @param className Nama kelas yang akan diinstantiasi
     * @param parameterTypes Tipe parameter constructor
     * @param args Argumen untuk constructor
     * @return Instance baru atau null jika gagal
     */
    public static Object newInstance(String className, Class<?>[] parameterTypes, Object... args) {
        MethodHandle constructor = getCachedConstructor(className, parameterTypes);
        if (constructor == null) {
            return null;
        }
        
        try {
            return constructor.invokeWithArguments(args);
        } catch (Throwable e) {
            return null;
        }
    }
    
    /**
     * Membersihkan cache untuk kelas tertentu.
     * 
     * @param className Nama kelas yang akan dihapus dari cache
     */
    public static void clearClassCache(String className) {
        CLASS_CACHE.remove(className);
        // Hapus juga semua MethodHandle yang terkait dengan kelas ini
        METHOD_HANDLE_CACHE.keySet().removeIf(key -> key.startsWith(className + "."));
    }
    
    /**
     * Lazy loading untuk komponen reflection yang berat.
     * Menggunakan Supplier untuk menunda inisialisasi sampai benar-benar diperlukan.
     *
     * @param className Nama kelas yang akan diload secara lazy
     * @param supplier Supplier untuk membuat instance kelas
     * @return Instance kelas atau null jika gagal
     */
    public static <T> T lazyLoadClass(String className, Supplier<T> supplier) {
        // Cek apakah kelas sudah ada di cache
        Class<?> clazz = getCachedClass(className);
        if (clazz != null) {
            try {
                T instance = (T) clazz.getDeclaredConstructor().newInstance();
                return instance;
            } catch (Exception e) {
                // Fallback ke supplier jika instantiasi gagal
                return supplier.get();
            }
        }
        
        // Jika kelas tidak tersedia, gunakan supplier
        return supplier.get();
    }
    
    /**
     * Lazy loading untuk MethodHandle dengan fallback ke reflection tradisional.
     *
     * @param className Nama kelas yang berisi method
     * @param methodName Nama method yang akan diload secara lazy
     * @param returnType Tipe return method
     * @param parameterTypes Tipe parameter method
     * @param fallbackSupplier Supplier fallback jika MethodHandle tidak tersedia
     * @return MethodHandle atau hasil fallback supplier
     */
    public static Object lazyLoadMethodHandle(String className, String methodName,
                                             Class<?> returnType, Class<?>[] parameterTypes,
                                             Supplier<Object> fallbackSupplier) {
        MethodHandle handle = getCachedMethodHandle(className, methodName, returnType, parameterTypes);
        if (handle != null) {
            return handle;
        }
        
        // Fallback ke reflection tradisional jika MethodHandle tidak tersedia
        return fallbackSupplier.get();
    }
    
    /**
     * Pre-load kelas-kelas Minecraft yang umum digunakan untuk optimasi startup.
     */
    public static void preloadCommonMinecraftClasses() {
        String[] commonClasses = {
            "net.minecraft.world.level.chunk.LevelChunk",
            "net.minecraft.world.level.ChunkPos",
            "net.minecraft.server.level.ServerLevel",
            "net.minecraft.world.level.dimension.Dimension",
            "net.minecraft.nbt.CompoundTag",
            "net.minecraft.nbt.NbtIo",
            "net.minecraft.core.BlockPos",
            "net.minecraft.world.level.block.state.BlockState",
            "net.minecraft.world.level.block.entity.BlockEntity"
        };
        
        for (String className : commonClasses) {
            getCachedClass(className);
        }
    }
    
    /**
     * Memeriksa apakah kelas-kelas Minecraft penting sudah tersedia.
     *
     * @return true jika semua kelas penting tersedia
     */
    public static boolean areEssentialClassesAvailable() {
        String[] essentialClasses = {
            "net.minecraft.world.level.chunk.LevelChunk",
            "net.minecraft.world.level.ChunkPos",
            "net.minecraft.server.level.ServerLevel"
        };
        
        for (String className : essentialClasses) {
            if (!isClassAvailable(className)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Membersihkan seluruh cache.
     */
    public static void clearAllCache() {
        CLASS_CACHE.clear();
        METHOD_HANDLE_CACHE.clear();
    }
    
    /**
     * Memeriksa apakah kelas tertentu tersedia di classpath.
     * 
     * @param className Nama kelas yang akan diperiksa
     * @return true jika kelas tersedia
     */
    public static boolean isClassAvailable(String className) {
        return getCachedClass(className) != null;
    }
    
    /**
     * Mendapatkan statistik cache.
     * 
     * @return String berisi statistik cache
     */
    public static String getCacheStats() {
        return String.format("Class cache: %d entries, MethodHandle cache: %d entries", 
                           CLASS_CACHE.size(), METHOD_HANDLE_CACHE.size());
    }
}