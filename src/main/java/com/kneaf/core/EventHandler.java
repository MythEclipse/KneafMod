package com.kneaf.core;

import com.kneaf.core.protocol.commands.PerformanceCommand;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

/**
 * Handles all event listeners for KneafCore mod.
 * Separated from KneafCore to reduce complexity and improve maintainability.
 */
public class EventHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    private final SystemManager systemManager;

    public EventHandler(SystemManager systemManager) {
        this.systemManager = systemManager;
    }

    /**
     * Register commands with the Minecraft command dispatcher.
     * This is the event handler for RegisterCommandsEvent.
     *
     * @param event The register commands event
     */
    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        LOGGER.info("Registering commands with dispatcher");
        
        try {
            // Use the unified command system to register all commands
            // In a real implementation, you would call systemManager.getCommandSystem().registerWithDispatcher(event.getDispatcher());
            // For backward compatibility, we'll keep the direct registration for now
            PerformanceCommand.register(event.getDispatcher());
            
            LOGGER.info("Commands registered successfully");
            
        } catch (Exception e) {
            LOGGER.error("Failed to register commands", e);
        }
    }

    /**
     * Handle server tick events for performance monitoring.
     * This is the event handler for ServerTickEvent.Post.
     *
     * @param event The server tick event
     */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (!systemManager.isPerformanceMonitoringEnabled() || !systemManager.isInitialized()) {
            return;
        }
        
        try {
            // Update TPS monitoring on each server tick
            var perfManager = systemManager.getPerformanceManager();
            if (perfManager != null) {
                perfManager.getTpsMonitor().updateTPS();
            }
            
        } catch (Exception e) {
            LOGGER.error("Error during server tick performance monitoring", e);
            // Continue running even if performance monitoring fails
        }
    }

    /**
     * Handle server starting event.
     * This is the event handler for ServerStartingEvent.
     *
     * @param event The server starting event
     */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Server starting - completing final initialization");
        
        try {
            // In a real implementation, you would perform server-specific initialization here
            // For example: systemManager.getPerformanceManager().enableServerSpecificMonitoring();
            
        } catch (Exception e) {
            LOGGER.error("Error during server start initialization", e);
        }
    }

    /**
     * Handle server stopping event for graceful shutdown.
     * This is the event handler for ServerStoppingEvent.
     *
     * @param event The server stopping event
     */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        LOGGER.info("Server stopping - initiating graceful shutdown");
        
        try {
            systemManager.shutdownGracefully();
            
        } catch (Exception e) {
            LOGGER.error("Error during graceful shutdown", e);
        }
    }
}