package com.kneaf.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comprehensive mock Minecraft types for testing without actual Minecraft dependencies
 * Provides complete isolation for unit testing core optimization logic
 */
public class MockMinecraftTypes {
    
    // Global entity ID counter for testing
    private static final AtomicInteger entityIdCounter = new AtomicInteger(1);
    
    /**
     * Mock Entity class with comprehensive Minecraft-like functionality
     */
    public static class Entity {
        private final int id;
        private MockLevel level;
        private MockVec3 position;
        private MockVec3 deltaMovement;
        private float yaw;
        private float pitch;
        private boolean removed;
        private final UUID uuid;
        private String entityType;
        private float health;
        private float maxHealth;
        private final Map<String, Object> data = new ConcurrentHashMap<>();
        
        public Entity(MockLevel level, MockVec3 position, String entityType) {
            this.id = entityIdCounter.getAndIncrement();
            this.level = level;
            this.position = position;
            this.deltaMovement = new MockVec3(0, 0, 0);
            this.uuid = UUID.randomUUID();
            this.entityType = entityType;
            this.health = 20.0f;
            this.maxHealth = 20.0f;
            this.yaw = 0.0f;
            this.pitch = 0.0f;
            this.removed = false;
        }
        
        // Constructor for compatibility with existing tests
        public Entity(int id, MockLevel level, MockVec3 deltaMovement) {
            this.id = id;
            this.level = level;
            this.position = new MockVec3(0, 0, 0);
            this.deltaMovement = deltaMovement;
            this.uuid = UUID.randomUUID();
            this.entityType = "test_entity";
            this.health = 20.0f;
            this.maxHealth = 20.0f;
            this.yaw = 0.0f;
            this.pitch = 0.0f;
            this.removed = false;
        }
        
        // Core entity methods
        public MockLevel level() { return level; }
        public int getId() { return id; }
        public UUID getUUID() { return uuid; }
        public String getType() { return entityType; }
        public boolean isRemoved() { return removed; }
        public void remove() { this.removed = true; }
        
        // Position and movement
        public MockVec3 position() { return position; }
        public MockVec3 getDeltaMovement() { return deltaMovement; }
        public void setPos(MockVec3 position) { this.position = position; }
        public void setDeltaMovement(MockVec3 delta) { this.deltaMovement = delta; }
        public float getYRot() { return yaw; }
        public float getXRot() { return pitch; }
        public void setYRot(float yaw) { this.yaw = yaw; }
        public void setXRot(float pitch) { this.pitch = pitch; }
        
        // Health system
        public float getHealth() { return health; }
        public float getMaxHealth() { return maxHealth; }
        public void setHealth(float health) { this.health = Math.max(0, Math.min(health, maxHealth)); }
        public void setMaxHealth(float maxHealth) { this.maxHealth = maxHealth; }
        public boolean isAlive() { return health > 0 && !removed; }
        
        // Data storage for custom properties
        public void setData(String key, Object value) { data.put(key, value); }
        public Object getData(String key) { return data.get(key); }
        public boolean hasData(String key) { return data.containsKey(key); }
        
        // Distance calculations
        public double distanceTo(Entity other) {
            return position.distanceTo(other.position());
        }
        
        public double distanceToSqr(Entity other) {
            return position.distanceToSqr(other.position());
        }
        
        // Block position
        public BlockPos blockPosition() {
            return new BlockPos((int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z));
        }
        
        // Chunk position
        public ChunkPos chunkPosition() {
            return new ChunkPos(blockPosition().getX() >> 4, blockPosition().getZ() >> 4);
        }
    }
    
    /**
     * Mock Player entity with additional player-specific functionality
     */
    public static class Player extends Entity {
        private String playerName;
        private int experienceLevel;
        private float experienceProgress;
        private final Set<String> permissions = new HashSet<>();
        private GameType gameType = GameType.SURVIVAL;
        
        public Player(MockLevel level, MockVec3 position, String playerName) {
            super(level, position, "player");
            this.playerName = playerName;
            this.experienceLevel = 0;
            this.experienceProgress = 0.0f;
        }
        
        public String getName() { return playerName; }
        public void setName(String name) { this.playerName = name; }
        
        public int getExperienceLevel() { return experienceLevel; }
        public void setExperienceLevel(int level) { this.experienceLevel = level; }
        
        public float getExperienceProgress() { return experienceProgress; }
        public void setExperienceProgress(float progress) { this.experienceProgress = progress; }
        
        public boolean hasPermission(String permission) { return permissions.contains(permission); }
        public void addPermission(String permission) { permissions.add(permission); }
        public void removePermission(String permission) { permissions.remove(permission); }
        
        public GameType getGameType() { return gameType; }
        public void setGameType(GameType gameType) { this.gameType = gameType; }
        
        public boolean isCreative() { return gameType == GameType.CREATIVE; }
        public boolean isSurvival() { return gameType == GameType.SURVIVAL; }
        public boolean isAdventure() { return gameType == GameType.ADVENTURE; }
    }
    
    /**
     * Game types for player modes
     */
    public enum GameType {
        SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR
    }
    
    /**
     * Mock Level/World with comprehensive world simulation
     */
    public static class MockLevel {
        private final String levelName;
        private final boolean isClientSide;
        private final long seed;
        private final Map<ChunkPos, MockChunk> chunks = new ConcurrentHashMap<>();
        private final Map<BlockPos, MockBlockState> blocks = new ConcurrentHashMap<>();
        private final List<Entity> entities = new ArrayList<>();
        private final List<Player> players = new ArrayList<>();
        private Difficulty difficulty = Difficulty.NORMAL;
        private GameRules gameRules = new GameRules();
        private int tickCount = 0;
        
        public MockLevel(String levelName, boolean isClientSide, long seed) {
            this.levelName = levelName;
            this.isClientSide = isClientSide;
            this.seed = seed;
        }
        
        public MockLevel(boolean isClientSide) {
            this("mock_world", isClientSide, 12345L);
        }
        
        // Basic level properties
        public String getLevelName() { return levelName; }
        public boolean isClientSide() { return isClientSide; }
        public long getSeed() { return seed; }
        public int getTickCount() { return tickCount; }
        public void incrementTick() { tickCount++; }
        
        // Difficulty and game rules
        public Difficulty getDifficulty() { return difficulty; }
        public void setDifficulty(Difficulty difficulty) { this.difficulty = difficulty; }
        public GameRules getGameRules() { return gameRules; }
        
        // Entity management
        public List<Entity> getEntities() { return new ArrayList<>(entities); }
        public List<Player> getPlayers() { return new ArrayList<>(players); }
        
        public void addEntity(Entity entity) {
            entities.add(entity);
            if (entity instanceof Player) {
                players.add((Player) entity);
            }
        }
        
        public void removeEntity(Entity entity) {
            entities.remove(entity);
            if (entity instanceof Player) {
                players.remove(entity);
            }
        }
        
        public Entity getEntity(int id) {
            for (Entity entity : entities) {
                if (entity.getId() == id) {
                    return entity;
                }
            }
            return null;
        }
        
        public List<Entity> getEntitiesWithinAABB(AABB boundingBox) {
            List<Entity> result = new ArrayList<>();
            for (Entity entity : entities) {
                if (boundingBox.contains(entity.position())) {
                    result.add(entity);
                }
            }
            return result;
        }
        
        // Block and chunk management
        public MockBlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, new MockBlockState("air"));
        }
        
        public void setBlockState(BlockPos pos, MockBlockState state) {
            blocks.put(pos, state);
        }
        
        public MockChunk getChunk(ChunkPos pos) {
            return chunks.get(pos);
        }
        
        public void setChunk(ChunkPos pos, MockChunk chunk) {
            chunks.put(pos, chunk);
        }
        
        // Random access for world generation
        public Random getRandom() {
            return new Random(seed + tickCount);
        }
        
        // Time and weather (simplified)
        public long getDayTime() {
            return tickCount % 24000; // Minecraft day cycle
        }
        
        public boolean isDay() {
            return getDayTime() < 13000;
        }
        
        public boolean isNight() {
            return getDayTime() >= 13000;
        }
    }
    
    /**
     * Difficulty levels
     */
    public enum Difficulty {
        PEACEFUL, EASY, NORMAL, HARD
    }
    
    /**
     * Game rules for world behavior
     */
    public static class GameRules {
        private final Map<String, Boolean> rules = new HashMap<>();
        
        public GameRules() {
            rules.put("keepInventory", false);
            rules.put("doMobSpawning", true);
            rules.put("doDaylightCycle", true);
            rules.put("doWeatherCycle", true);
            rules.put("doImmediateRespawn", false);
        }
        
        public boolean getBoolean(String rule) {
            return rules.getOrDefault(rule, false);
        }
        
        public void setBoolean(String rule, boolean value) {
            rules.put(rule, value);
        }
    }
    
    /**
     * Enhanced MockVec3 with comprehensive vector operations
     */
    public static class MockVec3 {
        public final double x;
        public final double y;
        public final double z;
        
        public MockVec3(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
        
        // Factory methods
        public static MockVec3 of(double x, double y, double z) {
            return new MockVec3(x, y, z);
        }
        
        public static MockVec3 ZERO = new MockVec3(0, 0, 0);
        public static MockVec3 ONE = new MockVec3(1, 1, 1);
        
        // Basic vector operations
        public MockVec3 add(MockVec3 other) {
            return new MockVec3(x + other.x, y + other.y, z + other.z);
        }
        
        public MockVec3 subtract(MockVec3 other) {
            return new MockVec3(x - other.x, y - other.y, z - other.z);
        }
        
        public MockVec3 multiply(double scalar) {
            return new MockVec3(x * scalar, y * scalar, z * scalar);
        }
        
        public MockVec3 divide(double scalar) {
            return new MockVec3(x / scalar, y / scalar, z / scalar);
        }
        
        public double dot(MockVec3 other) {
            return x * other.x + y * other.y + z * other.z;
        }
        
        public MockVec3 cross(MockVec3 other) {
            return new MockVec3(
                y * other.z - z * other.y,
                z * other.x - x * other.z,
                x * other.y - y * other.x
            );
        }
        
        public double length() {
            return Math.sqrt(x * x + y * y + z * z);
        }
        
        public double lengthSqr() {
            return x * x + y * y + z * z;
        }
        
        public MockVec3 normalize() {
            double len = length();
            return len > 0 ? divide(len) : ZERO;
        }
        
        public double distanceTo(MockVec3 other) {
            return subtract(other).length();
        }
        
        public double distanceToSqr(MockVec3 other) {
            return subtract(other).lengthSqr();
        }
        
        public boolean isClose(MockVec3 other, double epsilon) {
            return distanceTo(other) < epsilon;
        }
        
        // Direction vectors
        public MockVec3 getXAxis() { return new MockVec3(1, 0, 0); }
        public MockVec3 getYAxis() { return new MockVec3(0, 1, 0); }
        public MockVec3 getZAxis() { return new MockVec3(0, 0, 1); }
        
        // Rotation and transformation
        public MockVec3 rotateAroundX(double angle) {
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            return new MockVec3(x, y * cos - z * sin, y * sin + z * cos);
        }
        
        public MockVec3 rotateAroundY(double angle) {
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            return new MockVec3(x * cos + z * sin, y, -x * sin + z * cos);
        }
        
        public MockVec3 rotateAroundZ(double angle) {
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            return new MockVec3(x * cos - y * sin, x * sin + y * cos, z);
        }
        
        // Conversion
        public BlockPos toBlockPos() {
            return new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        }
        
        @Override
        public String toString() {
            return String.format("Vec3(%.2f, %.2f, %.2f)", x, y, z);
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof MockVec3)) return false;
            MockVec3 other = (MockVec3) obj;
            return Double.compare(x, other.x) == 0 &&
                   Double.compare(y, other.y) == 0 &&
                   Double.compare(z, other.z) == 0;
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(x, y, z);
        }
    }
    
    /**
     * Block position for discrete world coordinates
     */
    public static class BlockPos {
        private final int x, y, z;
        
        public BlockPos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
        
        public int getX() { return x; }
        public int getY() { return y; }
        public int getZ() { return z; }
        
        public BlockPos offset(int dx, int dy, int dz) {
            return new BlockPos(x + dx, y + dy, z + dz);
        }
        
        public BlockPos north() { return offset(0, 0, -1); }
        public BlockPos south() { return offset(0, 0, 1); }
        public BlockPos east() { return offset(1, 0, 0); }
        public BlockPos west() { return offset(-1, 0, 0); }
        public BlockPos up() { return offset(0, 1, 0); }
        public BlockPos down() { return offset(0, -1, 0); }
        
        public MockVec3 toVec3() {
            return new MockVec3(x, y, z);
        }
        
        public MockVec3 toVec3Center() {
            return new MockVec3(x + 0.5, y + 0.5, z + 0.5);
        }
        
        public double distSqr(BlockPos other) {
            int dx = x - other.x;
            int dy = y - other.y;
            int dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof BlockPos)) return false;
            BlockPos other = (BlockPos) obj;
            return x == other.x && y == other.y && z == other.z;
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(x, y, z);
        }
        
        @Override
        public String toString() {
            return String.format("BlockPos(%d, %d, %d)", x, y, z);
        }
    }
    
    /**
     * Chunk position for chunk-based world management
     */
    public static class ChunkPos {
        private final int x, z;
        
        public ChunkPos(int x, int z) {
            this.x = x;
            this.z = z;
        }
        
        public int getX() { return x; }
        public int getZ() { return z; }
        
        public BlockPos getWorldPosition() {
            return new BlockPos(x << 4, 0, z << 4);
        }
        
        public int getRegionX() { return x >> 5; }
        public int getRegionZ() { return z >> 5; }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof ChunkPos)) return false;
            ChunkPos other = (ChunkPos) obj;
            return x == other.x && z == other.z;
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(x, z);
        }
    }
    
    /**
     * Bounding box for collision detection and area queries
     */
    public static class AABB {
        public final double minX, minY, minZ;
        public final double maxX, maxY, maxZ;
        
        public AABB(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
        
        public static AABB of(BlockPos pos) {
            return new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        }
        
        public static AABB ofSize(MockVec3 center, double size) {
            return new AABB(
                center.x - size/2, center.y - size/2, center.z - size/2,
                center.x + size/2, center.y + size/2, center.z + size/2
            );
        }
        
        public boolean contains(MockVec3 point) {
            return point.x >= minX && point.x <= maxX &&
                   point.y >= minY && point.y <= maxY &&
                   point.z >= minZ && point.z <= maxZ;
        }
        
        public boolean intersects(AABB other) {
            return maxX >= other.minX && minX <= other.maxX &&
                   maxY >= other.minY && minY <= other.maxY &&
                   maxZ >= other.minZ && minZ <= other.maxZ;
        }
        
        public AABB inflate(double amount) {
            return new AABB(minX - amount, minY - amount, minZ - amount,
                           maxX + amount, maxY + amount, maxZ + amount);
        }
        
        public double getVolume() {
            return (maxX - minX) * (maxY - minY) * (maxZ - minZ);
        }
    }
    
    /**
     * Mock block state for world simulation
     */
    public static class MockBlockState {
        private final String blockType;
        private final Map<String, String> properties;
        
        public MockBlockState(String blockType) {
            this.blockType = blockType;
            this.properties = new HashMap<>();
        }
        
        public MockBlockState(String blockType, Map<String, String> properties) {
            this.blockType = blockType;
            this.properties = new HashMap<>(properties);
        }
        
        public String getBlockType() { return blockType; }
        public Map<String, String> getProperties() { return new HashMap<>(properties); }
        
        public boolean isAir() { return blockType.equals("air"); }
        public boolean isSolid() { return !isAir() && !blockType.contains("water") && !blockType.contains("lava"); }
        
        @Override
        public String toString() {
            return "BlockState{" + blockType + ", properties=" + properties + "}";
        }
    }
    
    /**
     * Mock chunk for world generation and management
     */
    public static class MockChunk {
        private final ChunkPos position;
        private final MockLevel level;
        private final MockBlockState[][][] blocks = new MockBlockState[16][256][16];
        
        public MockChunk(ChunkPos position, MockLevel level) {
            this.position = position;
            this.level = level;
            // Initialize with air blocks
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 256; y++) {
                    for (int z = 0; z < 16; z++) {
                        blocks[x][y][z] = new MockBlockState("air");
                    }
                }
            }
        }
        
        public ChunkPos getPos() { return position; }
        public MockLevel getLevel() { return level; }
        
        public MockBlockState getBlockState(int x, int y, int z) {
            if (x < 0 || x >= 16 || y < 0 || y >= 256 || z < 0 || z >= 16) {
                return new MockBlockState("air");
            }
            return blocks[x][y][z];
        }
        
        public void setBlockState(int x, int y, int z, MockBlockState state) {
            if (x < 0 || x >= 16 || y < 0 || y >= 256 || z < 0 || z >= 16) {
                return;
            }
            blocks[x][y][z] = state;
        }
        
        public BlockPos getWorldPosition(int x, int y, int z) {
            return new BlockPos(position.getX() * 16 + x, y, position.getZ() * 16 + z);
        }
    }
    
    /**
     * Utility methods for creating test scenarios
     */
    public static class TestUtils {
        
        public static MockLevel createTestLevel() {
            return new MockLevel("test_world", false, 12345L);
        }
        
        public static Player createTestPlayer(MockLevel level, String name) {
            return new Player(level, new MockVec3(0, 64, 0), name);
        }
        
        public static Entity createTestEntity(MockLevel level, String type, MockVec3 position) {
            return new Entity(level, position, type);
        }
        
        public static MockChunk createTestChunk(MockLevel level, int chunkX, int chunkZ) {
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            MockChunk chunk = new MockChunk(pos, level);
            
            // Generate some basic terrain
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int worldX = chunkX * 16 + x;
                    int worldZ = chunkZ * 16 + z;
                    int height = 64 + (int)(Math.sin(worldX * 0.1) * Math.cos(worldZ * 0.1) * 10);
                    
                    for (int y = 0; y < height; y++) {
                        if (y < height - 4) {
                            chunk.setBlockState(x, y, z, new MockBlockState("stone"));
                        } else if (y < height - 1) {
                            chunk.setBlockState(x, y, z, new MockBlockState("dirt"));
                        } else {
                            chunk.setBlockState(x, y, z, new MockBlockState("grass_block"));
                        }
                    }
                }
            }
            
            return chunk;
        }
        
        public static List<Entity> createEntityCluster(MockLevel level, String type, MockVec3 center, int count, double spread) {
            List<Entity> entities = new ArrayList<>();
            Random random = new Random();
            
            for (int i = 0; i < count; i++) {
                double angle = random.nextDouble() * 2 * Math.PI;
                double distance = random.nextDouble() * spread;
                double height = random.nextDouble() * 2 - 1;
                
                MockVec3 position = new MockVec3(
                    center.x + Math.cos(angle) * distance,
                    center.y + height,
                    center.z + Math.sin(angle) * distance
                );
                
                entities.add(createTestEntity(level, type, position));
            }
            
            return entities;
        }
    }
}