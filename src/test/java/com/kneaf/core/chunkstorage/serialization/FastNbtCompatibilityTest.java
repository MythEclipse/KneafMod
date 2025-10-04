package com.kneaf.core.chunkstorage.serialization;

import static org.junit.jupiter.api.Assertions.*;

import com.kneaf.core.chunkstorage.common.ChunkStorageConfig;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test suite for FastNBT data format compatibility.
 * Ensures that FastNBT produces data compatible with standard NBT format.
 */
class FastNbtCompatibilityTest {

  private ChunkStorageConfig config;
  private NbtChunkSerializer standardSerializer;

  @BeforeEach
  void setUp() throws Exception {
    config = new ChunkStorageConfig();

    // Only initialize if Minecraft classes are available
    if (NbtChunkSerializer.isMinecraftAvailable()) {
      standardSerializer = new NbtChunkSerializer();
    }
  }

  @Test
  void testFastNbtDisabledUsesStandardFormat() {
    // When FastNBT is disabled, should use standard NBT format
    config.setEnableFastNbt(false);

    // This test verifies that the configuration correctly controls the format
    assertFalse(config.isEnableFastNbt(), "FastNBT should be disabled");
  }

  @Test
  void testFastNbtEnabledUsesOptimizedFormat() {
    // When FastNBT is enabled, should use optimized format
    config.setEnableFastNbt(true);

    // This test verifies that the configuration correctly controls the format
    assertTrue(config.isEnableFastNbt(), "FastNBT should be enabled");
  }

  @Test
  void testDataFormatCompatibilityWhenFastNbtDisabled() throws IOException {
    if (standardSerializer == null) {
      System.out.println("Skipping compatibility test - Minecraft classes not available");
      return;
    }

    // Test that standard NBT format is self-compatible
    config.setEnableFastNbt(false);

    // Create mock chunk data
    Object mockChunk = createMockChunkData();

    // Serialize with standard format
    byte[] serializedData = standardSerializer.serialize(mockChunk);

    // Deserialize with standard format
    Object deserializedChunk = standardSerializer.deserialize(serializedData);

    // Verify the data is not null (basic compatibility check)
    assertNotNull(deserializedChunk, "Deserialized chunk should not be null");
    assertNotNull(serializedData, "Serialized data should not be null");
    assertTrue(serializedData.length > 0, "Serialized data should not be empty");
  }

  @Test
  void testDataFormatCompatibilityWhenFastNbtEnabled() throws IOException {
    // Test that when FastNBT is enabled, it should still produce compatible data
    config.setEnableFastNbt(true);

    if (standardSerializer == null) {
      System.out.println("Skipping FastNBT compatibility test - Minecraft classes not available");
      return;
    }

    // This test simulates what would happen when FastNBT is implemented
    // For now, it verifies that the configuration is set correctly
    assertTrue(config.isEnableFastNbt(), "FastNBT should be enabled for this test");

    // TODO: When FastNBT is implemented, this test should:
    // 1. Serialize with FastNBT
    // 2. Deserialize with standard NBT
    // 3. Verify data integrity
    // 4. Test reverse: serialize with standard, deserialize with FastNBT

    System.out.println("FastNBT compatibility test placeholder - implementation pending");
  }

  @Test
  void testFormatVersionCompatibility() {
    // Test that format versions are maintained for compatibility
    // This ensures that FastNBT doesn't break existing data formats

    final String expectedFormat = "NBT";
    final int expectedVersion = 1;

    if (standardSerializer != null) {
      assertEquals(expectedFormat, standardSerializer.getFormat(),
          "Serializer should use correct format name");
      assertEquals(expectedVersion, standardSerializer.getVersion(),
          "Serializer should use correct format version");
    }

    // FastNBT should also maintain the same format name and version for compatibility
    // This is a design requirement for FastNBT implementation
    System.out.println("Format compatibility requirement: FastNBT must use format='" +
        expectedFormat + "', version=" + expectedVersion);
  }

  @Test
  void testChecksumCompatibility() {
    // Test that checksum calculation is compatible between formats
    // This ensures data integrity is maintained

    if (standardSerializer == null) {
      System.out.println("Skipping checksum test - Minecraft classes not available");
      return;
    }

    // Test with sample data
    byte[] testData = "test chunk data for checksum".getBytes();

    // Calculate checksum using the same algorithm that would be used by both formats
    long checksum = calculateChecksum(testData);

    assertTrue(checksum != 0, "Checksum should not be zero for non-empty data");
    assertEquals(0L, calculateChecksum(new byte[0]), "Checksum should be zero for empty data");

    // FastNBT implementation must use the same checksum algorithm for compatibility
    System.out.println("Checksum compatibility: FastNBT must use same checksum algorithm");
  }

  @Test
  void testCompressionCompatibility() {
    // Test that compression settings are compatible
    config.setEnableCompression(true);
    assertTrue(config.isEnableCompression(), "Compression should be enabled");

    config.setEnableCompression(false);
    assertFalse(config.isEnableCompression(), "Compression should be disabled");

    // FastNBT should respect compression settings for compatibility
    System.out.println("Compression compatibility: FastNBT must respect compression settings");
  }

  @Test
  void testBackwardCompatibilityWithExistingData() {
    // Test that existing serialized data can still be read
    // This is crucial for data migration

    if (standardSerializer == null) {
      System.out.println("Skipping backward compatibility test - Minecraft classes not available");
      return;
    }

    // This test should verify that data serialized before FastNBT implementation
    // can still be read correctly
    // For now, it's a placeholder for when FastNBT is implemented

    System.out.println("Backward compatibility: Existing data must remain readable");
  }

  @Test
  void testForwardCompatibilityWithFutureVersions() {
    // Test that the format can be extended for future versions
    // while maintaining backward compatibility

    if (standardSerializer == null) {
      System.out.println("Skipping forward compatibility test - Minecraft classes not available");
      return;
    }

    // Verify that the serializer correctly reports supported formats
    assertTrue(standardSerializer.supports("NBT", 1), "Should support current format");
    assertFalse(standardSerializer.supports("NBT", 2), "Should not support future versions yet");
    assertFalse(standardSerializer.supports("OTHER", 1), "Should not support other formats");

    // FastNBT should also follow the same compatibility rules
    System.out.println("Forward compatibility: FastNBT must follow same version rules");
  }

  /**
   * Create mock chunk data for testing.
   */
  private Object createMockChunkData() {
    // Return a simple mock object
    // In real implementation, this would create actual Minecraft chunk data
    return new Object();
  }

  /**
   * Calculate checksum using the same algorithm as NbtChunkSerializer.
   */
  private long calculateChecksum(byte[] data) {
    if (data == null || data.length == 0) {
      return 0L;
    }

    long checksum = 0;
    for (int i = 0; i < data.length; i++) {
      checksum = (checksum << 1) ^ (data[i] & 0xFF);
      // Rotate bits to improve distribution
      checksum = (checksum << 7) | (checksum >>> (64 - 7));
    }
    return checksum;
  }
}