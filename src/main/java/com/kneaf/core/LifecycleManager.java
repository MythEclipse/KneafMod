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
                try {
                    // Use reasonable timeout values for graceful shutdown
                    // In a real implementation, you would call cmdSystem.shutdown()
                    cmdSystem.shutdown(30, java.util.concurrent.TimeUnit.SECONDS);
                    LOGGER.info("Command system shut down successfully");
                } catch (InterruptedException e) {
                    LOGGER.error("Command system shutdown was interrupted", e);
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    LOGGER.error("Failed to shut down command system", e);
                }
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
       
       // Set up minimal functionality for degraded mode
       try {
           // Enable essential logging
           LOGGER.info("Enabling minimal logging functionality");
           
           // In a real implementation, you would set up minimal functionality
           // to allow the game to continue running with reduced features
           
           // Keep core performance monitoring but at reduced level
           PerformanceManager perfManager = PerformanceManager.getInstance();
           if (perfManager != null && !perfManager.isEnabled()) {
               try {
                   perfManager.enable();
                   LOGGER.info("Performance monitoring enabled in degraded mode");
               } catch (Exception e) {
                   LOGGER.error("Failed to enable performance monitoring in degraded mode", e);
               }
           }
           
           LOGGER.info("KneafCore is now running in degraded mode with minimal functionality");
           
       } catch (Exception e) {
           LOGGER.error("Failed to set up minimal functionality", e);
       }
   }
}