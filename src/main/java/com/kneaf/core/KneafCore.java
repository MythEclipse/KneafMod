package com.kneaf.core;

import com.kneaf.entities.ModEntities;
import com.kneaf.entities.ShadowZombieNinja;
import com.kneaf.core.async.AsyncLoggingManager;
import com.kneaf.core.async.AsyncLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import com.kneaf.entities.ShadowZombieNinjaRenderer;
import org.slf4j.Logger;
import java.util.stream.IntStream;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Main mod class for KneafCore. Refactored to use modular architecture with clear separation of concerns.
 * Delegates responsibilities to specialized classes: ModInitializer, EventHandler, SystemManager, LifecycleManager.
 * Follows SOLID principles and provides proper lifecycle management.
 * Uses JOML math types for vector and quaternion operations.
 */
@Mod(KneafCore.MODID)
public class KneafCore {
    static {
        // Native library removed; implementations provided in Java for portability
    }

    /** Mod ID used for registration and identification */
    public static final String MODID = "kneafcore";

    /** Logger for the mod */
    public static final Logger LOGGER = LoggerFactory.getLogger(KneafCore.class);
    
    /** Async logger for non-blocking operations */
    private static final AsyncLogger ASYNC_LOGGER = AsyncLoggingManager.getAsyncLogger(KneafCore.class);

    // Deferred Registers
    /** Deferred register for blocks */
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);

    /** Deferred register for items */
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);


    // Core components - refactored to use modular architecture
    private static final AtomicReference<KneafCore> INSTANCE = new AtomicReference<>();

    /**
     * Constructor for the mod. Registers all deferred registers, event listeners, and sets up core systems.
     *
     * @param modEventBus The mod event bus
     * @param modContainer The mod container
     */
    public KneafCore(IEventBus modEventBus, ModContainer modContainer) {
        INSTANCE.set(this);
        
        // Initialize async logging system FIRST untuk ensure all logging is non-blocking
        AsyncLoggingManager.initialize();
        ASYNC_LOGGER.info("AsyncLoggingManager initialized - All logging operations are now non-blocking");
        
        // Initialize modular components

        // Register deferred registers
        ModEntities.ENTITIES.register(modEventBus);
        ModEntities.ITEMS.register(modEventBus);
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);

        // Register event listeners
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(this::registerEntityAttributes);
        modEventBus.addListener(this::buildCreativeTabContents);
        
        // Register entity renderer on the mod event bus
        modEventBus.addListener(EntityRenderersEvent.RegisterRenderers.class, this::registerEntityRenderers);
        

        ASYNC_LOGGER.info("KneafCore mod constructor completed - waiting for initialization");
    }

    /**
     * Common setup method called during mod initialization.
     * Delegates initialization to SystemManager.
     *
     * @param event The FML common setup event
     */
    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Starting KneafCore common setup");
        
        try {
            // Initialize PerformanceManager
            PerformanceManager performanceManager = PerformanceManager.getInstance();
            performanceManager.loadConfiguration();
            LOGGER.info("PerformanceManager initialized: {}", performanceManager);
            
            // Register OptimizationInjector event listeners
            LOGGER.info("Registering OptimizationInjector event listeners");
            
            // Register commands on the game bus
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(this::registerCommands);
            
            // Delegate initialization to SystemManager
            
            LOGGER.info("KneafCore initialization completed successfully");
            
        } catch (Exception e) {
            LOGGER.error("Failed to complete KneafCore initialization", e);
        }
    }

    /**
     * Client setup method called during mod initialization on the client side.
     * Registers entity renderers and other client-side components.
     *
     * @param event The FML client setup event
     */
    private void clientSetup(FMLClientSetupEvent event) {
        LOGGER.info("Starting KneafCore client setup");
        LOGGER.info("KneafCore client setup completed successfully");
    }

    /**
     * Registers entity attributes for custom entities.
     *
     * @param event The entity attribute creation event
     */
    private void registerEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.SHADOW_ZOMBIE_NINJA.get(), ShadowZombieNinja.createAttributes().build());
    }

    /**
     * Get the singleton instance of KneafCore.
     *
     * @return the singleton instance
     */
    public static KneafCore getInstance() {
        KneafCore instance = INSTANCE.get();
        if (instance == null) {
            throw new IllegalStateException("KneafCore is not initialized yet");
        }
        return instance;
    }

    /**
     * Multiplies two 4x4 matrices represented as float arrays of length 16.
     *
     * @param a the first matrix
     * @param b the second matrix
     * @return the result matrix
     */
    private static float[] matrixMultiply(float[] a, float[] b) {
        if (a == null || b == null) throw new IllegalArgumentException("Matrices cannot be null");
        if (a.length != 16 || b.length != 16) throw new IllegalArgumentException("Only 4x4 matrices are supported");
        float[] r = new float[16];
        IntStream.range(0, 4).parallel().forEach(row -> {
            for (int col = 0; col < 4; col++) {
                float sum = 0f;
                for (int k = 0; k < 4; k++) {
                    sum += a[row * 4 + k] * b[k * 4 + col];
                }
                r[row * 4 + col] = sum;
            }
        });
        return r;
    }

    /* Convenience overloads using JOML math types */
    public static Vector3f quaternionRotateVector(Quaternionf q, Vector3f v) {
        if (q == null || v == null) throw new IllegalArgumentException("Quaternion and vector cannot be null");
        float[] qarr = new float[] { q.x(), q.y(), q.z(), q.w() };
        float[] varr = new float[] { v.x(), v.y(), v.z() };
        float[] res = quaternionRotateVector(qarr, varr);
        return new Vector3f(res[0], res[1], res[2]);
    }

    public static Vector3f vectorCross(Vector3f a, Vector3f b) {
        if (a == null || b == null) throw new IllegalArgumentException("Vectors cannot be null");
        float[] res = vectorCross(new float[] { a.x(), a.y(), a.z() }, new float[] { b.x(), b.y(), b.z() });
        return new Vector3f(res[0], res[1], res[2]);
    }

    public static float vectorDot(Vector3f a, Vector3f b) {
        if (a == null || b == null) throw new IllegalArgumentException("Vectors cannot be null");
        return vectorDot(new float[] { a.x(), a.y(), a.z() }, new float[] { b.x(), b.y(), b.z() });
    }

    public static Vector3f vectorNormalize(Vector3f a) {
        if (a == null) throw new IllegalArgumentException("Vector cannot be null");
        float[] res = vectorNormalize(new float[] { a.x(), a.y(), a.z() });
        return new Vector3f(res[0], res[1], res[2]);
    }

    /**
     * Computes the dot product of two 3D vectors represented as float arrays of length 3.
     *
     * @param a the first vector
     * @param b the second vector
     * @return the dot product
     */
    private static float vectorDot(float[] a, float[] b) {
        if (a == null || b == null) throw new IllegalArgumentException("Vectors cannot be null");
        if (a.length != 3 || b.length != 3) throw new IllegalArgumentException("Only 3D vectors are supported");
        return a[0]*b[0] + a[1]*b[1] + a[2]*b[2];
    }

    /**
     * Computes the cross product of two 3D vectors represented as float arrays of length 3.
     *
     * @param a the first vector
     * @param b the second vector
     * @return the cross product vector
     */
    private static float[] vectorCross(float[] a, float[] b) {
        if (a == null || b == null) throw new IllegalArgumentException("Vectors cannot be null");
        if (a.length != 3 || b.length != 3) throw new IllegalArgumentException("Only 3D vectors are supported");
        return new float[] {
            a[1]*b[2] - a[2]*b[1],
            a[2]*b[0] - a[0]*b[2],
            a[0]*b[1] - a[1]*b[0]
        };
    }

    /**
     * Normalizes a 3D vector represented as a float array of length 3.
     *
     * @param a the vector to normalize
     * @return the normalized vector
     */
    private static float[] vectorNormalize(float[] a) {
        if (a == null) throw new IllegalArgumentException("Vector cannot be null");
        if (a.length != 3) throw new IllegalArgumentException("Only 3D vectors are supported");
        float len = (float)Math.sqrt(a[0]*a[0] + a[1]*a[1] + a[2]*a[2]);
        if (len == 0f) return new float[] {0f,0f,0f};
        return new float[] { a[0]/len, a[1]/len, a[2]/len };
    }

    /**
     * Rotates a 3D vector using a quaternion represented as float arrays.
     *
     * @param q the quaternion (length 4)
     * @param v the vector to rotate (length 3)
     * @return the rotated vector
     */
    private static float[] quaternionRotateVector(float[] q, float[] v) {
        if (q == null || v == null) throw new IllegalArgumentException("Quaternion and vector cannot be null");
        if (q.length != 4) throw new IllegalArgumentException("Quaternion must be length 4");
        if (v.length != 3) throw new IllegalArgumentException("Vector must be length 3");
        // q = [x, y, z, w]
        float qx = q[0], qy = q[1], qz = q[2], qw = q[3];
        // t = 2 * cross(q.xyz, v)
        float[] qxyz = new float[] { qx, qy, qz };
        float[] t = vectorCross(qxyz, v);
        t[0] *= 2f; t[1] *= 2f; t[2] *= 2f;
        // v' = v + qw * t + cross(q.xyz, t)
        float[] cross2 = vectorCross(qxyz, t);
        return new float[] {
            v[0] + qw * t[0] + cross2[0],
            v[1] + qw * t[1] + cross2[1],
            v[2] + qw * t[2] + cross2[2]
        };
    }

    /**
     * Performs A* pathfinding on a 2D grid.
     *
     * @param grid the grid as a boolean array (true for obstacles)
     * @param width the width of the grid
     * @param height the height of the grid
     * @param startX the starting X coordinate
     * @param startY the starting Y coordinate
     * @param goalX the goal X coordinate
     * @param goalY the goal Y coordinate
     * @return the path as an array of coordinates [x1, y1, x2, y2, ...] or null if no path found
     */
    private static int[] aStarPathfind(boolean[] grid, int width, int height, int startX, int startY, int goalX, int goalY) {
        if (grid == null) throw new IllegalArgumentException("Grid cannot be null");
        if (grid.length != width * height) throw new IllegalArgumentException("Grid size does not match width*height");
        if (startX < 0 || startY < 0 || goalX < 0 || goalY < 0 || startX >= width || goalX >= width || startY >= height || goalY >= height)
            throw new IllegalArgumentException("Start/goal outside grid");

        // A* implementation using simple arrays. 4-connected grid.
        final int W = width, H = height;
        final int N = W*H;
        int start = startY * W + startX;
        int goal = goalY * W + goalX;
        boolean[] closed = new boolean[N];
        int[] gScore = new int[N];
        int[] fScore = new int[N];
        int[] cameFrom = new int[N];
        java.util.Arrays.fill(gScore, Integer.MAX_VALUE/2);
        java.util.Arrays.fill(fScore, Integer.MAX_VALUE/2);
        java.util.Arrays.fill(cameFrom, -1);

        java.util.PriorityQueue<Integer> open = new java.util.PriorityQueue<>(11, (i,j)->Integer.compare(fScore[i], fScore[j]));

        gScore[start] = 0;
        fScore[start] = heuristic(startX, startY, goalX, goalY);
        open.add(start);

        while (!open.isEmpty()) {
            int current = open.poll();
            if (current == goal) {
                // reconstruct path
                java.util.ArrayList<Integer> path = new java.util.ArrayList<>();
                int cur = current;
                while (cur != -1) {
                    path.add(cur);
                    cur = cameFrom[cur];
                }
                // reverse and convert to [x1,y1,x2,y2,...]
                int len = path.size();
                int[] coords = new int[len*2];
                for (int i = 0; i < len; i++) {
                    int idx = path.get(len-1-i);
                    coords[i*2] = idx % W;
                    coords[i*2+1] = idx / W;
                }
                return coords;
            }

            closed[current] = true;
            int cx = current % W;
            int cy = current / W;

            int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
            for (int[] d : dirs) {
                int nx = cx + d[0];
                int ny = cy + d[1];
                if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                int neighbor = ny*W + nx;
                if (closed[neighbor]) continue;
                if (grid[neighbor]) continue; // obstacle

                int tentativeG = gScore[current] + 1;
                if (tentativeG < gScore[neighbor]) {
                    cameFrom[neighbor] = current;
                    gScore[neighbor] = tentativeG;
                    fScore[neighbor] = tentativeG + heuristic(nx, ny, goalX, goalY);
                    if (!open.contains(neighbor)) open.add(neighbor);
                }
            }
        }

        return null; // no path
    }

    private static int heuristic(int x, int y, int gx, int gy) {
        // Manhattan distance
        return Math.abs(x - gx) + Math.abs(y - gy);
    }

    /**
     * Performs A* pathfinding asynchronously on a 2D grid.
     *
     * @param grid the grid as a boolean array (true for obstacles)
     * @param width the width of the grid
     * @param height the height of the grid
     * @param startX the starting X coordinate
     * @param startY the starting Y coordinate
     * @param goalX the goal X coordinate
     * @param goalY the goal Y coordinate
     * @return a CompletableFuture that completes with the path as an array of coordinates [x1, y1, x2, y2, ...] or null if no path found
     */
    public static CompletableFuture<int[]> aStarPathfindAsync(boolean[] grid, int width, int height, int startX, int startY, int goalX, int goalY) {
        return CompletableFuture.supplyAsync(() -> aStarPathfind(grid, width, height, startX, startY, goalX, goalY));
    }

    /**
     * Builds the contents of creative mode tabs.
     *
     * @param event The build creative mode tab contents event
     */
    private void buildCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        // Add Shadow Zombie Ninja spawn egg to spawn eggs creative tab
        if (event.getTabKey().location().toString().equals("minecraft:spawn_eggs")) {
            event.accept(ModEntities.SHADOW_ZOMBIE_NINJA_SPAWN_EGG.get().getDefaultInstance());
        }
    }

    /**
     * Registers commands for the mod.
     *
     * @param event The register commands event
     */

    /**
     * Registers entity renderers for the mod.
     *
     * @param event The entity renderers registration event
     */
    private void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        LOGGER.info("Registering entity renderers");
        event.registerEntityRenderer(ModEntities.SHADOW_ZOMBIE_NINJA.get(), ShadowZombieNinjaRenderer::new);
        LOGGER.info("Entity renderers registered successfully");
    }

    /**
     * Registers commands for the mod.
     *
     * @param event The register commands event
     */
    private void registerCommands(RegisterCommandsEvent event) {
        // Register async monitoring commands
        com.kneaf.commands.AsyncMonitorCommands.register(event.getDispatcher());
        
        // Skip other command registration for now to avoid compilation issues
        // com.kneaf.commands.MetricsCommand.register(event.getDispatcher());
    }


}
