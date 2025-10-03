package com.kneaf.core.data.core;

/**
 * Constants used across data classes for validation and processing.
 */
public final class DataConstants {
    
    private DataConstants() {
        // Private constructor to prevent instantiation
    }
    
    // Validation constants
    public static final double MIN_COORDINATE = -30_000_000.0;
    public static final double MAX_COORDINATE = 30_000_000.0;
    public static final double MIN_DISTANCE = 0.0;
    public static final double MAX_DISTANCE = 1_000_000.0;
    public static final int MIN_CHUNK_COORDINATE = -1_875_000;
    public static final int MAX_CHUNK_COORDINATE = 1_875_000;
    public static final int MIN_ITEM_COUNT = 1;
    public static final int MAX_ITEM_COUNT = 64;
    public static final int MIN_ITEM_AGE = 0;
    public static final int MAX_ITEM_AGE = 6000; // 5 minutes in ticks
    
    // Entity type constants
    public static final String ENTITY_TYPE_PLAYER = "player";
    public static final String ENTITY_TYPE_VILLAGER = "villager";
    public static final String ENTITY_TYPE_MOB = "mob";
    public static final String ENTITY_TYPE_ITEM = "item";
    public static final String ENTITY_TYPE_BLOCK = "block";
    
    // Villager profession constants
    public static final String PROFESSION_NONE = "none";
    public static final String PROFESSION_FARMER = "farmer";
    public static final String PROFESSION_LIBRARIAN = "librarian";
    public static final String PROFESSION_CLERIC = "cleric";
    public static final String PROFESSION_ARMORER = "armorer";
    public static final String PROFESSION_WEAPONSMITH = "weaponsmith";
    public static final String PROFESSION_TOOLSMITH = "toolsmith";
    
    // Default values
    public static final double DEFAULT_DISTANCE = 0.0;
    public static final String DEFAULT_PROFESSION = PROFESSION_NONE;
    public static final int DEFAULT_LEVEL = 1;
    public static final boolean DEFAULT_BOOLEAN = false;
    public static final long DEFAULT_LAST_PATHFIND_TICK = 0L;
    public static final int DEFAULT_PATHFIND_FREQUENCY = 20;
    public static final int DEFAULT_AI_COMPLEXITY = 1;
}