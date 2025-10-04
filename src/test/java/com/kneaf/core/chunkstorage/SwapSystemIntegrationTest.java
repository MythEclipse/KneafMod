package com.kneaf.core.chunkstorage;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the swap system to verify the unified virtual memory implementation. This
 * test validates the integration between SwapManager and core components.
 */
public class SwapSystemIntegrationTest {

  @BeforeEach
  void setUp() {
    System.out.println("=== Setting up Swap System Integration Test ===");
  }

  @Test
  @DisplayName("Test SwapManager Creation and Basic Operations")
  void testSwapManagerCreation() {
    System.out.println("Testing SwapManager Creation and Basic Operations...");

    // Test 1: Create SwapManager configuration
    SwapManager.SwapConfig swapConfig = new SwapManager.SwapConfig();
    swapConfig.setEnabled(true);
    swapConfig.setMemoryCheckIntervalMs(1000);
    swapConfig.setMaxConcurrentSwaps(3);
    swapConfig.setSwapBatchSize(5);
    swapConfig.setEnableAutomaticSwapping(true);
    swapConfig.setCriticalMemoryThreshold(0.95);
    swapConfig.setHighMemoryThreshold(0.85);
    swapConfig.setElevatedMemoryThreshold(0.75);

    System.out.println("✓ Created SwapManager configuration: enabled=" + swapConfig.isEnabled());
    assertTrue(swapConfig.isEnabled(), "Swap should be enabled");
    assertEquals(3, swapConfig.getMaxConcurrentSwaps(), "Max concurrent swaps should be 3");
    assertEquals(5, swapConfig.getSwapBatchSize(), "Swap batch size should be 5");

    // Test 2: Create SwapManager
    SwapManager swapManager = new SwapManager(swapConfig);
    System.out.println("✓ Created SwapManager instance");
    assertNotNull(swapManager, "SwapManager should not be null");

    // Test 3: Test memory pressure detection
    SwapManager.MemoryUsageInfo memoryUsage = swapManager.getMemoryUsage();
    System.out.println("✓ Retrieved memory usage: " + memoryUsage);
    assertNotNull(memoryUsage, "Memory usage should not be null");
    assertTrue(memoryUsage.getHeapUsed() >= 0, "Heap used should be non-negative");
    assertTrue(memoryUsage.getHeapMax() > 0, "Heap max should be positive");

    SwapManager.MemoryPressureLevel pressureLevel = swapManager.getMemoryPressureLevel();
    System.out.println("✓ Current memory pressure level: " + pressureLevel);
    assertNotNull(pressureLevel, "Memory pressure level should not be null");

    // Test 4: Test swap statistics
    SwapManager.SwapStatistics swapStats = swapManager.getSwapStatistics();
    System.out.println("✓ Retrieved swap statistics: " + swapStats);
    assertNotNull(swapStats, "Swap statistics should not be null");
    assertEquals(0, swapStats.getTotalSwapOuts(), "Initial swap outs should be 0");
    assertEquals(0, swapStats.getTotalSwapIns(), "Initial swap ins should be 0");

    SwapManager.SwapManagerStats managerStats = swapManager.getStats();
    System.out.println("✓ Retrieved swap manager statistics: " + managerStats);
    assertNotNull(managerStats, "Swap manager statistics should not be null");
    assertTrue(managerStats.isEnabled(), "Swap manager should be enabled");

    // Test 5: Test memory pressure levels
    testMemoryPressureLevels(swapManager);

    // Test 6: Shutdown swap manager
    swapManager.shutdown();
    System.out.println("✓ Successfully shutdown SwapManager");

    System.out.println("\n🎉 SwapManager creation and basic operations tests passed!");
  }

  @Test
  @DisplayName("Test Memory Pressure Detection")
  void testMemoryPressureDetection() {
    System.out.println("Testing Memory Pressure Detection...");

    SwapManager.SwapConfig swapConfig = new SwapManager.SwapConfig();
    swapConfig.setEnabled(true);
    swapConfig.setCriticalMemoryThreshold(0.95);
    swapConfig.setHighMemoryThreshold(0.85);
    swapConfig.setElevatedMemoryThreshold(0.75);

    SwapManager swapManager = new SwapManager(swapConfig);

    // Test different memory pressure scenarios
    SwapManager.MemoryUsageInfo memoryUsage = swapManager.getMemoryUsage();
    SwapManager.MemoryPressureLevel pressureLevel = swapManager.getMemoryPressureLevel();

    System.out.println(
        "✓ Memory usage: " + memoryUsage.getHeapUsed() + " / " + memoryUsage.getHeapMax());
    System.out.println("✓ Memory pressure level: " + pressureLevel);

    // Verify pressure level is valid
    assertTrue(
        pressureLevel == SwapManager.MemoryPressureLevel.NORMAL
            || pressureLevel == SwapManager.MemoryPressureLevel.ELEVATED
            || pressureLevel == SwapManager.MemoryPressureLevel.HIGH
            || pressureLevel == SwapManager.MemoryPressureLevel.CRITICAL,
        "Pressure level should be one of the defined levels");

    swapManager.shutdown();
    System.out.println("\n🎉 Memory pressure detection tests passed!");
  }

  @Test
  @DisplayName("Test Swap Configuration Validation")
  void testSwapConfigurationValidation() {
    System.out.println("Testing Swap Configuration Validation...");

    // Test valid configuration
    SwapManager.SwapConfig validConfig = new SwapManager.SwapConfig();
    validConfig.setEnabled(true);
    validConfig.setMaxConcurrentSwaps(5);
    validConfig.setSwapBatchSize(10);
    validConfig.setCriticalMemoryThreshold(0.90);
    validConfig.setHighMemoryThreshold(0.80);
    validConfig.setElevatedMemoryThreshold(0.70);

    SwapManager swapManager = new SwapManager(validConfig);
    assertNotNull(swapManager, "SwapManager should be created with valid config");

    // Test threshold ordering
    SwapManager.SwapConfig invalidConfig = new SwapManager.SwapConfig();
    invalidConfig.setEnabled(true);
    invalidConfig.setCriticalMemoryThreshold(0.70); // Lower than high
    invalidConfig.setHighMemoryThreshold(0.80);
    invalidConfig.setElevatedMemoryThreshold(0.90); // Higher than others

    // This should still work - the SwapManager should handle threshold validation
    SwapManager invalidSwapManager = new SwapManager(invalidConfig);
    assertNotNull(invalidSwapManager, "SwapManager should handle threshold validation internally");

    swapManager.shutdown();
    invalidSwapManager.shutdown();

    System.out.println("\n🎉 Swap configuration validation tests passed!");
  }

  private void testMemoryPressureLevels(SwapManager swapManager) {
    System.out.println("✓ Testing memory pressure level detection:");

    SwapManager.MemoryUsageInfo memoryUsage = swapManager.getMemoryUsage();
    double usageRatio = memoryUsage.getUsagePercentage();

    System.out.println("  - Memory usage ratio: " + String.format("%.2f", usageRatio));

    SwapManager.MemoryPressureLevel pressureLevel = swapManager.getMemoryPressureLevel();
    System.out.println("  - Detected pressure level: " + pressureLevel);

    // Verify the pressure level makes sense based on usage ratio
    if (usageRatio >= 0.95) {
      assertEquals(
          SwapManager.MemoryPressureLevel.CRITICAL,
          pressureLevel,
          "Should be CRITICAL at >=95% usage");
    } else if (usageRatio >= 0.85) {
      assertEquals(
          SwapManager.MemoryPressureLevel.HIGH, pressureLevel, "Should be HIGH at >=85% usage");
    } else if (usageRatio >= 0.75) {
      assertEquals(
          SwapManager.MemoryPressureLevel.ELEVATED,
          pressureLevel,
          "Should be ELEVATED at >=75% usage");
    } else {
      assertEquals(
          SwapManager.MemoryPressureLevel.NORMAL, pressureLevel, "Should be NORMAL at <75% usage");
    }
  }
}
