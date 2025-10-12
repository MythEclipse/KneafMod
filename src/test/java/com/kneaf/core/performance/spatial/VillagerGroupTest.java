package com.kneaf.core.performance.spatial;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kneaf.core.data.entity.VillagerData;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for VillagerGroup class focusing on the actual villager movement data implementation.
 */
public class VillagerGroupTest {

  private VillagerGroup testGroup;
  private List<Long> villagerIds;

  @BeforeEach
  void setUp() {
    villagerIds = Arrays.asList(1L, 2L, 3L, 4L, 5L);
    testGroup = new VillagerGroup(
        1L, 100.0f, 64.0f, 100.0f, villagerIds, "village", (byte) 4);
  }

  /** Test that needsUpdate() returns false when no movement has occurred. */
  @Test
  @DisplayName("Needs Update - No Movement")
  void testNeedsUpdateNoMovement() {
    // Mock the getVillagerData method to return static data (no movement)
    VillagerGroup testGroupWithMock = spy(testGroup);
    VillagerData staticVillager = createStaticVillagerData(1L, 100.0, 64.0, 100.0);
    
    doReturn(Optional.of(staticVillager)).when(testGroupWithMock).getVillagerData(anyLong());

    // First call should initialize cache
    boolean firstCheck = testGroupWithMock.needsUpdate(1.0f);
    
    // Second call should return false since no movement occurred
    boolean secondCheck = testGroupWithMock.needsUpdate(1.0f);
    
    assertFalse(firstCheck, "First check should return false with no movement");
    assertFalse(secondCheck, "Second check should also return false with no movement");
  }

  /** Test that needsUpdate() returns true when significant movement has occurred. */
  @Test
  @DisplayName("Needs Update - With Significant Movement")
  void testNeedsUpdateWithMovement() throws InterruptedException {
    // Create test group
    VillagerGroup testGroupWithMock = spy(testGroup);
    
    // First call with static data to initialize cache
    VillagerData staticVillager = createStaticVillagerData(1L, 100.0, 64.0, 100.0);
    doReturn(Optional.of(staticVillager)).when(testGroupWithMock).getVillagerData(1L);
    
    // Wait to ensure time has passed for movement check
    Thread.sleep(1000);
    
    // Second call with moved data
    VillagerData movedVillager = createStaticVillagerData(1L, 150.0, 64.0, 150.0); // Moved 50 units
    doReturn(Optional.of(movedVillager)).when(testGroupWithMock).getVillagerData(1L);
    
    // Should return true due to significant movement
    boolean needsUpdate = testGroupWithMock.needsUpdate(10.0f);
    
    assertTrue(needsUpdate, "Should return true when villager moved significantly");
  }

  /** Test that updateMovement() correctly updates movement tracking. */
  @Test
  @DisplayName("Update Movement Tracking")
  void testUpdateMovement() {
    // Verify initial state
    assertFalse(testGroup.needsUpdate(1.0f), "Initial state should not need update");
    assertEquals(0.0f, testGroup.getLastKnownMovement(), "Initial movement should be 0");

    // Update with movement data
    testGroup.updateMovement(1L, 15.0f);

    // Verify updated state
    assertTrue(testGroup.needsUpdate(1.0f), "Should need update after movement");
    assertEquals(15.0f, testGroup.getLastKnownMovement(), "Last known movement should be updated");
  }

  /** Test that calculateOptimalAiTickRate() works correctly with different densities. */
  @Test
  @DisplayName("Calculate Optimal AI Tick Rate")
  void testCalculateOptimalAiTickRate() {
    // Dense group (small radius, many villagers)
    VillagerGroup denseGroup = new VillagerGroup(
        2L, 200.0f, 64.0f, 200.0f, villagerIds, "village", (byte) 4, 1, 0, 5.0f);
    
    // Medium density group
    VillagerGroup mediumGroup = new VillagerGroup(
        3L, 300.0f, 64.0f, 300.0f, villagerIds, "village", (byte) 4, 1, 0, 10.0f);
    
    // Loose group
    VillagerGroup looseGroup = new VillagerGroup(
        4L, 400.0f, 64.0f, 400.0f, villagerIds, "village", (byte) 4, 1, 0, 20.0f);
    
    // Sparse group
    VillagerGroup sparseGroup = new VillagerGroup(
        5L, 500.0f, 64.0f, 500.0f, villagerIds, "village", (byte) 4, 1, 0, 30.0f);
    
    assertEquals(1, denseGroup.calculateOptimalAiTickRate(), "Dense group should get tick rate 1");
    assertEquals(2, mediumGroup.calculateOptimalAiTickRate(), "Medium group should get tick rate 2");
    assertEquals(4, looseGroup.calculateOptimalAiTickRate(), "Loose group should get tick rate 4");
    assertEquals(8, sparseGroup.calculateOptimalAiTickRate(), "Sparse group should get tick rate 8");
  }

  /** Helper method to create static villager data for testing. */
  private VillagerData createStaticVillagerData(long id, double x, double y, double z) {
    return new VillagerData(
        id,
        x,
        y,
        z,
        0.0,
        "farmer",
        1,
        true,
        false,
        false,
        0,
        100,
        1);
  }
}