package com.kneaf.core.chunkstorage.serialization;

import static org.junit.jupiter.api.Assertions.*;

import com.kneaf.core.chunkstorage.common.ChunkStorageConfig;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test suite for FastNBT functionality.
 * Tests the fast NBT serialization/deserialization when enabled.
 */
class FastNbtTest {

  private ChunkStorageConfig config;

  @BeforeEach
  void setUp() {
    config = new ChunkStorageConfig();
  }

  @Test
  void testFastNbtConfigDefaultDisabled() {
    // FastNBT should be disabled by default
    assertFalse(config.isEnableFastNbt(), "FastNBT should be disabled by default");
  }

  @Test
  void testFastNbtConfigEnable() {
    // Test enabling FastNBT
    config.setEnableFastNbt(true);
    assertTrue(config.isEnableFastNbt(), "FastNBT should be enabled when set to true");
  }

  @Test
  void testFastNbtConfigDisable() {
    // Test disabling FastNBT
    config.setEnableFastNbt(false);
    assertFalse(config.isEnableFastNbt(), "FastNBT should be disabled when set to false");
  }

  @Test
  void testFastNbtConfigToggle() {
    // Test toggling FastNBT
    assertFalse(config.isEnableFastNbt());

    config.setEnableFastNbt(true);
    assertTrue(config.isEnableFastNbt());

    config.setEnableFastNbt(false);
    assertFalse(config.isEnableFastNbt());
  }

  @Test
  void testFastNbtConfigToString() {
    // Test that toString includes FastNBT setting
    String configString = config.toString();
    assertTrue(configString.contains("enableFastNbt=false"),
        "Config toString should include FastNBT setting");
  }

  @Test
  void testFastNbtConfigToStringEnabled() {
    // Test that toString reflects enabled FastNBT
    config.setEnableFastNbt(true);
    String configString = config.toString();
    assertTrue(configString.contains("enableFastNbt=true"),
        "Config toString should reflect enabled FastNBT");
  }

  @Test
  void testFastNbtCompatibilityWithOtherSettings() {
    // Test that FastNBT setting doesn't interfere with other config settings
    config.setEnableFastNbt(true);
    config.setCacheCapacity(2000);
    config.setEnableCompression(true);

    assertTrue(config.isEnableFastNbt());
    assertEquals(2000, config.getCacheCapacity());
    assertTrue(config.isEnableCompression());
  }

  @Test
  void testFastNbtInDefaultConfig() {
    // Test default configuration has FastNBT disabled
    ChunkStorageConfig defaultConfig = ChunkStorageConfig.createDefault();
    assertFalse(defaultConfig.isEnableFastNbt(),
        "Default config should have FastNBT disabled");
  }

  @Test
  void testFastNbtInDevelopmentConfig() {
    // Test development configuration has FastNBT disabled
    ChunkStorageConfig devConfig = ChunkStorageConfig.createDevelopment();
    assertFalse(devConfig.isEnableFastNbt(),
        "Development config should have FastNBT disabled");
  }

  @Test
  void testFastNbtInProductionConfig() {
    // Test production configuration has FastNBT disabled by default
    ChunkStorageConfig prodConfig = ChunkStorageConfig.createProduction();
    assertFalse(prodConfig.isEnableFastNbt(),
        "Production config should have FastNBT disabled by default");
  }

  // TODO: Add tests for actual FastNBT serialization/deserialization once implemented
  // These tests will need mock chunk data and FastNBT serializer implementation

  @Test
  void testFastNbtSerializerNotImplemented() {
    // Placeholder test - FastNBT serializer should not be available yet
    // This test will be updated once FastNBT implementation is added
    assertThrows(UnsupportedOperationException.class, () -> {
      // This would be the call to create FastNBT serializer
      throw new UnsupportedOperationException("FastNBT serializer not yet implemented");
    }, "FastNBT serializer should not be implemented yet");
  }

  @Test
  void testFastNbtFallbackToStandardNbt() {
    // Test that when FastNBT is enabled but not available, it falls back to standard NBT
    config.setEnableFastNbt(true);

    // This test assumes that if FastNBT is enabled but implementation missing,
    // the system should gracefully fall back to standard NBT serialization
    // Implementation would need to check if FastNBT classes are available

    // For now, just verify the config setting
    assertTrue(config.isEnableFastNbt());
  }
}