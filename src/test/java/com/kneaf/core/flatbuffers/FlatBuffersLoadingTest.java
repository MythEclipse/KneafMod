package com.kneaf.core.flatbuffers;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that FlatBuffers classes are properly loaded and accessible
 */
public class FlatBuffersLoadingTest {

    @Test
    public void testFlatBufferBuilderCanBeInstantiated() {
        // This test verifies that the FlatBufferBuilder class is available at runtime
        // If this test passes, it means the FlatBuffers dependency is properly configured
        assertDoesNotThrow(() -> {
            com.google.flatbuffers.FlatBufferBuilder builder = new com.google.flatbuffers.FlatBufferBuilder(1024);
            assertNotNull(builder);
            builder.finish(0); // Simple operation to verify functionality
        });
    }

    @Test
    public void testEntityFlatBuffersSerialization() {
        // Test that our EntityFlatBuffers class can use FlatBufferBuilder without issues
        assertDoesNotThrow(() -> {
            // This should not throw NoClassDefFoundError if FlatBuffers is properly loaded
            java.util.List<com.kneaf.core.data.EntityData> entities = new java.util.ArrayList<>();
            java.util.List<com.kneaf.core.data.PlayerData> players = new java.util.ArrayList<>();
            
            // This call uses FlatBufferBuilder internally
            java.nio.ByteBuffer buffer = EntityFlatBuffers.serializeEntityInput(1L, entities, players);
            assertNotNull(buffer);
        });
    }
}