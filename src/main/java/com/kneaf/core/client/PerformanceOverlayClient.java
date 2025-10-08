package com.kneaf.core.client;

import com.kneaf.core.exceptions.utils.ExceptionSeverity;
import com.kneaf.core.exceptions.utils.ExceptionUtils;
import com.kneaf.core.network.NetworkHandler;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@OnlyIn(Dist.CLIENT)
public final class PerformanceOverlayClient {
  // Keep last 40 LINES
  private static final int MAX_LINES = 40;
  private static final Deque<String> LINES = new ArrayDeque<>(MAX_LINES);
  private static final Logger LOGGER = LoggerFactory.getLogger(PerformanceOverlayClient.class);

  // Error classification constants
  private static final String COMPONENT_NAME = "PerformanceOverlayClient";
  private static final String OPERATION_NETWORK_REGISTER = "NetworkRegistration";
  private static final String OPERATION_HUD_REGISTER = "HudRegistration";
  private static final String OPERATION_RENDER = "PerformanceRendering";
  private static final String OPERATION_PACKET_PROCESSING = "PacketProcessing";

  // Performance monitoring for error handling
  private static final long ERROR_HANDLING_THRESHOLD_NANOS = 1_000_000; // 1ms threshold
  private static long errorHandlingTimeNanos = 0;
  private static int errorCount = 0;

  private PerformanceOverlayClient() {}

  /**
   * Handles exceptions with proper logging, classification, and performance impact assessment.
   * Optimized for minimal performance overhead while maintaining comprehensive error tracking.
   *
   * @param exception The exception to handle
   * @param operation The operation that failed
   * @param context Additional context information
   * @param severity The severity level (if null, will be determined automatically)
   */
  private static void handleException(Throwable exception, String operation, String context, ExceptionSeverity severity) {
    if (exception == null) {
      return;
    }

    long startTime = System.nanoTime();
    
    try {
      // Determine severity if not provided
      if (severity == null) {
        severity = ExceptionUtils.determineSeverity(exception);
      }

      // Increment error counter for monitoring
      errorCount++;

      // Create context map for detailed logging (only for ERROR severity and above to minimize overhead)
      Map<String, Object> contextData = new HashMap<>();
      contextData.put("operation", operation);
      if (context != null) {
        contextData.put("context", context);
      }
      contextData.put("clientActive", Minecraft.getInstance() != null);
      contextData.put("thread", Thread.currentThread().getName());
      contextData.put("errorCount", errorCount);

      // Log the exception with appropriate severity
      String errorMessage = String.format("Performance overlay %s failed: %s", operation, exception.getMessage());
      
      // Only build detailed context for significant errors to minimize performance impact
      if (severity.isAtLeast(ExceptionSeverity.ERROR)) {
        com.kneaf.core.exceptions.utils.ExceptionContext.Builder contextBuilder =
            com.kneaf.core.exceptions.utils.ExceptionContext.builder()
                .operation(operation)
                .component(COMPONENT_NAME);
        
        // Add context data individually
        for (Map.Entry<String, Object> entry : contextData.entrySet()) {
            contextBuilder.addContext(entry.getKey(), entry.getValue());
        }
        
        ExceptionUtils.logExceptionWithContext(
            LOGGER,
            severity,
            errorMessage,
            exception,
            contextBuilder.build()
        );
      } else {
        // For lower severity, use simple logging to reduce overhead
        ExceptionUtils.logException(LOGGER, severity, errorMessage, exception);
      }

      // Performance impact assessment
      assessPerformanceImpact(exception, operation, severity);

      // Critical error handling - propagate or take corrective action
      if (severity == ExceptionSeverity.CRITICAL) {
        handleCriticalError(exception, operation);
      }
    } finally {
      // Track error handling performance
      long endTime = System.nanoTime();
      long handlingTime = endTime - startTime;
      errorHandlingTimeNanos += handlingTime;
      
      // Warn if error handling takes too long (indicates potential performance issue)
      if (handlingTime > ERROR_HANDLING_THRESHOLD_NANOS) {
        LOGGER.warn("Error handling took {} ms for operation {} (threshold: {} ms)",
            handlingTime / 1_000_000, operation, ERROR_HANDLING_THRESHOLD_NANOS / 1_000_000);
      }
    }
  }

  /**
   * Assesses the performance impact of an error and logs appropriate warnings.
   */
  private static void assessPerformanceImpact(Throwable exception, String operation, ExceptionSeverity severity) {
    if (severity.isAtLeast(ExceptionSeverity.ERROR)) {
      // High impact errors that could affect client performance
      LOGGER.warn("Performance impact detected in {}: {}. Severity: {}",
          operation, exception.getClass().getSimpleName(), severity.getName());
      
      // Add performance-specific context
      Map<String, Object> perfContext = new HashMap<>();
      perfContext.put("severity", severity.getName());
      perfContext.put("exceptionType", exception.getClass().getSimpleName());
      perfContext.put("operation", operation);
      perfContext.put("impact", "potential_performance_degradation");
      
      LOGGER.debug("Performance impact context: {}", perfContext);
    }
  }

  /**
   * Handles critical errors that could compromise system stability.
   */
  private static void handleCriticalError(Throwable exception, String operation) {
    LOGGER.error("CRITICAL ERROR in {}: {}. System stability may be compromised.",
        operation, exception.getMessage());
    
    // Log stack trace for critical errors
    LOGGER.error("Critical error stack trace: ", exception);
    
    // In a real-world scenario, you might:
    // 1. Send alerts to monitoring systems
    // 2. Trigger fallback mechanisms
    // 3. Request client restart
    // 4. Disable affected components
    
    // For now, we log and continue with degraded functionality
    LOGGER.warn("Continuing with degraded performance overlay functionality due to critical error");
  }

  /**
   * Gets performance statistics for error handling monitoring.
   */
  public static Map<String, Object> getErrorHandlingStats() {
    Map<String, Object> stats = new HashMap<>();
    stats.put("totalErrorCount", errorCount);
    stats.put("totalErrorHandlingTimeMs", errorHandlingTimeNanos / 1_000_000);
    stats.put("averageErrorHandlingTimeMs", errorCount > 0 ? (errorHandlingTimeNanos / errorCount) / 1_000_000 : 0);
    stats.put("thresholdExceededCount", errorHandlingTimeNanos > ERROR_HANDLING_THRESHOLD_NANOS ? 1 : 0);
    return stats;
  }

  /**
   * Resets error handling statistics for performance monitoring.
   */
  public static void resetErrorStats() {
    errorCount = 0;
    errorHandlingTimeNanos = 0;
    LOGGER.debug("Error handling statistics reset");
  }

  public static void registerClient(FMLClientSetupEvent event) {
    // Attempt to register a client-side packet listener for our channel.
    try {
      // Try Fabric's ClientPlayNetworking if present
      Class<?> clientPlayNetworking =
          Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking");
      Class<?> serverPlayPacketConsumer =
          Class.forName("net.fabricmc.fabric.api.networking.v1.PacketConsumer");
      java.lang.reflect.Method register =
          clientPlayNetworking.getMethod(
              "register", ResourceLocation.class, serverPlayPacketConsumer);
      // Build a PacketConsumer proxy via a dynamic proxy
      Object consumer =
          java.lang.reflect.Proxy.newProxyInstance(
              PerformanceOverlayClient.class.getClassLoader(),
              new Class[] {serverPlayPacketConsumer},
              (proxy, method, args) -> {
                // PacketConsumer#accept(client, buf) -> args[1] should be FriendlyByteBuf
                if (args != null && args.length > 1 && args[1] != null) {
                  try {
                    Object buf = args[1];
                    java.lang.reflect.Method readUtf =
                        buf.getClass().getMethod("readUtf", int.class);
                    String msg = (String) readUtf.invoke(buf, 32767);
                    onPerformanceLineReceived(msg);
                  } catch (Exception ex) {
                    handleException(ex, OPERATION_PACKET_PROCESSING, "Failed to read packet data", ExceptionSeverity.WARNING);
                  }
                }
                return null;
              });
      register.invoke(null, NetworkHandler.PERFORMANCE_CHANNEL, consumer);
      return;
    } catch (ClassNotFoundException ignored) {
      // Fabric API not present - this is expected, log at debug level
      LOGGER.debug("Fabric API not available for performance overlay network registration");
    } catch (NoSuchMethodException
        | IllegalAccessException
        | java.lang.reflect.InvocationTargetException e) {
      handleException(e, OPERATION_NETWORK_REGISTER, "Fabric network registration method error", ExceptionSeverity.INFO);
    }

    // Try to register a HUD render callback via Fabric's HudRenderCallback if available
    try {
      Class<?> hudCallbackClass =
          Class.forName("net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback");
      java.lang.reflect.Field eventField = hudCallbackClass.getField("EVENT");
      Object eventObj = eventField.get(null);
      java.lang.reflect.Method registerHud =
          eventObj.getClass().getMethod("register", hudCallbackClass);

      Object hudListener =
          java.lang.reflect.Proxy.newProxyInstance(
              PerformanceOverlayClient.class.getClassLoader(),
              new Class[] {hudCallbackClass},
              (proxy, method, args) -> {
                try {
                  // args[0] is MatrixStack (PoseStack) in most mappings, args[1] is tickDelta
                  float partial = 0f;
                  if (args != null && args.length > 1 && args[1] instanceof Float)
                    partial = (Float) args[1];
                  renderLinesUsingFont(args != null && args.length > 0 ? args[0] : null, partial);
                } catch (Throwable t) {
                  handleException(t, OPERATION_RENDER, "HUD rendering error during registration", ExceptionSeverity.WARNING);
                }
                return null;
              });

      registerHud.invoke(eventObj, hudListener);
      return;
    } catch (ClassNotFoundException cnf) {
      // HudRenderCallback not present; skip - log at debug level
      LOGGER.debug("Fabric HudRenderCallback not available for performance overlay");
    } catch (Throwable t) {
      handleException(t, OPERATION_HUD_REGISTER, "Critical error during HUD registration", ExceptionSeverity.ERROR);
    }

    try {
      // Try to access vanilla client packet listener registration:
      // Minecraft.getInstance().getConnection().addListener
      // The exact API differs across mappings; attempt to find a registerReceiver method on
      // net.minecraft.client.Minecraft
      net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
      Object conn = null;
      try {
        java.lang.reflect.Method getConnection = mc.getClass().getMethod("getConnection");
        conn = getConnection.invoke(mc);
      } catch (Exception ex) {
        handleException(ex, OPERATION_NETWORK_REGISTER, "Failed to get connection from Minecraft client", ExceptionSeverity.INFO);
      }
      if (conn != null) {
        // Try to find a method named 'addListener' or 'setListener' or 'register' that takes a
        // ResourceLocation and a BiConsumer
        // This is fragile; if not available we will fall back to chat fallback and expose LINES
        // buffer.
      }
    } catch (Throwable t) {
      handleException(t, OPERATION_NETWORK_REGISTER, "Critical error during vanilla network registration", ExceptionSeverity.WARNING);
    }

    // If we couldn't register a packet handler, we still accept performance LINES via chat
    // fallback.
  }

  // Called by network receiver when a line is received
  public static void onPerformanceLineReceived(String line) {
    if (line == null) return;
    synchronized (LINES) {
      if (LINES.size() >= MAX_LINES) LINES.removeFirst();
      LINES.addLast(line);
    }

    try {
      net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
      if (mc != null && mc.gui != null) {
        try {
          mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal(line));
        } catch (Throwable t) {
          handleException(t, OPERATION_PACKET_PROCESSING, "Failed to add chat message", ExceptionSeverity.INFO);
        }
      }
    } catch (Throwable t) {
      handleException(t, OPERATION_PACKET_PROCESSING, "Critical error in performance line processing", ExceptionSeverity.WARNING);
    }
  }

  // Render method to be called from a HUD render callback
  public static void renderOverlay(
      net.minecraft.client.gui.GuiGraphics graphics, float partialTicks) {
    Minecraft mc = Minecraft.getInstance();
    if (mc == null || mc.player == null) return;

    int x = 10;
    int y = 10;
    int lineHeight = mc.font.lineHeight + 2;
    synchronized (LINES) {
      int i = 0;
      for (String s : LINES) {
        int yy = y + i * lineHeight;
        graphics.drawString(mc.font, s, x, yy, 0xFFFFFF, false);
        i++;
      }
    }
  }

  // Reflection-based renderer that attempts to draw LINES using the client's font renderer.
  private static void renderLinesUsingFont(Object poseStack, float partialTicks) {
    try {
      Minecraft mc = Minecraft.getInstance();
      if (mc == null) return;

      Object font = mc.font; // common mapping
      if (font == null) return;

      int x = 10;
      int y = 10;
      int lineHeight = mc.font.lineHeight + 2;

      synchronized (LINES) {
        int i = 0;
        for (String s : LINES) {
          int yy = y + i * lineHeight;
          try {
            // Several mappings use draw or drawString signatures; try common ones
            try {
              java.lang.reflect.Method draw =
                  font.getClass()
                      .getMethod(
                          "draw",
                          net.minecraft.client.gui.GuiGraphics.class,
                          String.class,
                          int.class,
                          int.class,
                          int.class);
              // If GuiGraphics is not available here, this will throw; we ignore
              draw.invoke(font, null, s, x, yy, 0xFFFFFF);
            } catch (NoSuchMethodException ns) {
              try {
                java.lang.reflect.Method draw2 =
                    font.getClass()
                        .getMethod("draw", String.class, float.class, float.class, int.class);
                draw2.invoke(font, s, (float) x, (float) yy, 0xFFFFFF);
              } catch (NoSuchMethodException ns2) {
                // try older drawString
                java.lang.reflect.Method draw3 =
                    font.getClass()
                        .getMethod("drawString", String.class, int.class, int.class, int.class);
                draw3.invoke(font, s, x, yy, 0xFFFFFF);
              }
            }
          } catch (Throwable t) {
            handleException(t, OPERATION_RENDER, "Individual line render error", ExceptionSeverity.DEBUG);
          }
          i++;
        }
      }
    } catch (Throwable t) {
      handleException(t, OPERATION_RENDER, "Critical rendering error in performance overlay", ExceptionSeverity.ERROR);
    }
  }
}
