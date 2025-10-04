package com.kneaf.core.client;

import com.kneaf.core.network.NetworkHandler;
import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@OnlyIn(Dist.CLIENT)
public final class PerformanceOverlayClient {
  // Keep last 40 LINES
  private static final int MAX_LINES = 40;
  private static final Deque<String> LINES = new ArrayDeque<>(MAX_LINES);

  private PerformanceOverlayClient() {}

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
                    // ignore
                  }
                }
                return null;
              });
      register.invoke(null, NetworkHandler.PERFORMANCE_CHANNEL, consumer);
      return;
    } catch (ClassNotFoundException ignored) {
      // Fabric API not present
    } catch (NoSuchMethodException
        | IllegalAccessException
        | java.lang.reflect.InvocationTargetException e) {
      // Fall through to next attempt
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
                  // swallow to avoid crashing the client
                }
                return null;
              });

      registerHud.invoke(eventObj, hudListener);
      return;
    } catch (ClassNotFoundException cnf) {
      // HudRenderCallback not present; skip
    } catch (Throwable t) {
      // ignore any other errors
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
        // no-op
      }
      if (conn != null) {
        // Try to find a method named 'addListener' or 'setListener' or 'register' that takes a
        // ResourceLocation and a BiConsumer
        // This is fragile; if not available we will fall back to chat fallback and expose LINES
        // buffer.
      }
    } catch (Throwable t) {
      // ignore
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
        } catch (Throwable ignored) {
          // best-effort only
        }
      }
    } catch (Throwable ignored) {
      // ignore
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
            // ignore individual line render errors
          }
          i++;
        }
      }
    } catch (Throwable t) {
      // Don't let rendering errors bubble up
    }
  }
}
