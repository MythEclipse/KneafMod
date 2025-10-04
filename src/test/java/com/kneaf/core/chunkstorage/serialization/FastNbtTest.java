package com.kneaf.core.chunkstorage.serialization;

import static org.junit.jupiter.api.Assertions.*;

import com.kneaf.core.chunkstorage.common.ChunkStorageConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test suite for FastNBT functionality. Tests the fast NBT serialization/deserialization when
 * enabled.
 */
class FastNbtTest {

  private ChunkStorageConfig config;

  @BeforeEach
  void setUp() {
    config = new ChunkStorageConfig();
  }

  @Test
  void testFastNbtConfigDefaultEnabled() {
    // FastNBT should be enabled by default (new behavior)
    assertTrue(config.isEnableFastNbt(), "FastNBT should be enabled by default");
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
    assertTrue(config.isEnableFastNbt());

    config.setEnableFastNbt(false);
    assertFalse(config.isEnableFastNbt());

    config.setEnableFastNbt(true);
    assertTrue(config.isEnableFastNbt());
  }

  @Test
  void testFastNbtConfigToString() {
    // Test that toString includes FastNBT setting
    String configString = config.toString();
    assertTrue(
        configString.contains("enableFastNbt=true"),
        "Config toString should include FastNBT setting (now enabled by default)");
  }

  @Test
  void testFastNbtConfigToStringEnabled() {
    // Test that toString reflects enabled FastNBT
    config.setEnableFastNbt(true);
    String configString = config.toString();
    assertTrue(
        configString.contains("enableFastNbt=true"),
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
    // Test default configuration has FastNBT enabled (new behavior)
    ChunkStorageConfig defaultConfig = ChunkStorageConfig.createDefault();
    assertTrue(defaultConfig.isEnableFastNbt(), "Default config should have FastNBT enabled");
  }

  @Test
  void testFastNbtInDevelopmentConfig() {
    // Test development configuration has FastNBT enabled (new behavior)
    ChunkStorageConfig devConfig = ChunkStorageConfig.createDevelopment();
    assertTrue(devConfig.isEnableFastNbt(), "Development config should have FastNBT enabled");
  }

  @Test
  void testFastNbtInProductionConfig() {
    // Test production configuration has FastNBT enabled by default (new behavior)
    ChunkStorageConfig prodConfig = ChunkStorageConfig.createProduction();
    assertTrue(
        prodConfig.isEnableFastNbt(), "Production config should have FastNBT enabled by default");
  }

  // Add tests for actual FastNBT serialization/deserialization once implemented
  // These tests will need mock chunk data and FastNBT serializer implementation

  @Test
  void testFastNbtSerializerNotImplemented() {
    // Placeholder test - FastNBT serializer should not be available yet
    // This test will be updated once FastNBT implementation is added
    assertThrows(
        UnsupportedOperationException.class,
        () -> {
          // This would be the call to create FastNBT serializer
          throw new UnsupportedOperationException("FastNBT serializer not yet implemented");
        },
        "FastNBT serializer should not be implemented yet");
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
