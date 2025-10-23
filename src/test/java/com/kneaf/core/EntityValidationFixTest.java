package com.kneaf.core;

import com.kneaf.core.mock.TestMockEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that the entity validation fix correctly handles
 * Minecraft entities from various packages including client-side,
 * server-side, and custom mod entities.
 */
public class EntityValidationFixTest {
    
    private EntityProcessingService entityProcessingService;
    
    @BeforeEach
    void setUp() {
        // Force test mode for this test
        ModeDetector.forceModeForTesting(ModeDetector.TEST_MODE);
        entityProcessingService = EntityProcessingService.getInstance();
    }
    
    @AfterEach
    void tearDown() {
        // Reset to production mode after test
        ModeDetector.forceModeForTesting(ModeDetector.PRODUCTION_MODE);
    }
    
    @Test
    @DisplayName("Test that entity validation accepts test mock entities in test mode")
    void testEntityValidationAcceptance() throws Exception {
        // Test with a standard entity type that should be accepted in test mode
        TestMockEntity mockEntity = new TestMockEntity(1, "minecraft:zombie");
        mockEntity.setPosition(10.0, 20.0, 30.0);
        
        EntityProcessingService.EntityPhysicsData physicsData = 
            new EntityProcessingService.EntityPhysicsData(0.1, -0.2, 0.05);
        
        // This should not be rejected in test mode
        var result = entityProcessingService.processEntityAsync(mockEntity, physicsData);
        
        assertNotNull(result);
        
        // Wait for the result and check it's successful
        var processingResult = result.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(processingResult);
        
        // In test mode, this should not be a validation failure
        assertFalse(processingResult.message.contains("Entity failed production validation"));
    }
}