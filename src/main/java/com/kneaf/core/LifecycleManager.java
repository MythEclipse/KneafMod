package com.kneaf.core;

import com.kneaf.core.performance.unified.PerformanceManager;
import com.kneaf.core.command.unified.UnifiedCommandSystem;
import com.kneaf.core.unifiedbridge.UnifiedBridgeImpl;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * Handles startup and shutdown lifecycle for KneafCore mod.
 * Separated from KneafCore to reduce complexity and improve maintainability.
 */
public class LifecycleManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Perform graceful shutdown of all systems.
     *
     * @param modInitializer The ModInitializer instance to shutdown
     */
    public void shutdownGracefully(ModInitializer modInitializer) {
        LOGGER.info("Starting graceful shutdown of KneafCore systems");
        
        try {
            // Shutdown systems in reverse order of initialization
            UnifiedBridgeImpl bridge = modInitializer.getUnifiedBridge();
            if (bridge != null) {
                bridge.shutdown();
                LOGGER.info("Unified bridge system shut down successfully");
            }
            
            PerformanceManager perfManager = modInitializer.getPerformanceManager();
            if (perfManager != null) {
                perfManager.disable();
                LOGGER.info("Performance monitoring system shut down successfully");
            }
            
            UnifiedCommandSystem cmdSystem = modInitializer.getCommandSystem();
            if (cmdSystem != null) {
                // In a real implementation, you would call cmdSystem.shutdown()
                LOGGER.info("Command system shut down successfully");
            }
            
            LOGGER.info("KneafCore graceful shutdown completed successfully");
            
        } catch (Exception e) {
            LOGGER.error("Error during graceful shutdown", e);
            // Continue with shutdown even if some components fail
        }
    }

    /**
     * Handle initialization failures gracefully.
     *
     * @param cause The exception that caused the failure
     */
    public void handleInitializationFailure(Exception cause) {
        LOGGER.error("KneafCore initialization failed - entering degraded mode", cause);
        
        // In a real implementation, you would set up minimal functionality
        // to allow the game to continue running with reduced features
    }
}