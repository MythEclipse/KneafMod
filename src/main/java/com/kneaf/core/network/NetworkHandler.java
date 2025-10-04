package com.kneaf.core.network;

import com.kneaf.core.KneafCore;
import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Minimal vanilla CustomPayload sender.
 *
 * <p>This class intentionally avoids any hard dependency on loader-specific networking libraries
 * (Forge/Fabric). It uses vanilla packet classes which are available to both client and server
 * sides in this environment. The client-side receiver still needs to be implemented to fully render
 * the overlay.
 */
public final class NetworkHandler {
  private static final Logger LOGGER = LogUtils.getLogger();

  // Channel used for performance overlay payloads
  // Use parse to avoid relying on constructor visibility across mappings
  public static final ResourceLocation PERFORMANCE_CHANNEL =
      ResourceLocation.parse(KneafCore.MODID + ":perf_overlay");

  private NetworkHandler() {}

  public static void register() {
    // Client-side registration is optional and must be implemented in a
    // Dist.CLIENT class when targeting the client. This method is a
    // no-op on the common side to avoid classloading client-only classes.
  }

  /**
   * Send a single text line to a player using a vanilla custom payload. The payload format is a
   * single UTF-8 string written via FriendlyByteBuf.writeUtf.
   */
  public static void sendPerformanceLineToPlayer(ServerPlayer player, String line) {
    // Prepare buffer
    FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
    buf.writeUtf(line);

    // Try to construct and send a ClientboundCustomPayloadPacket via reflection if available
    try {
      Class<?> pktClass =
          Class.forName("net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket");
      try {
        java.lang.reflect.Constructor<?> ctor =
            pktClass.getConstructor(ResourceLocation.class, FriendlyByteBuf.class);
        Object pkt = ctor.newInstance(PERFORMANCE_CHANNEL, buf);
        // Use player's connection send(Object) via reflection to avoid compile-time linkage
        var connection = player.connection;
        if (connection != null) {
          java.lang.reflect.Method m = connection.getClass().getMethod("send", Object.class);
          m.invoke(connection, pkt);
          return;
        }
      } catch (NoSuchMethodException nsme) {
        // Try alternate constructor signature (String, FriendlyByteBuf)
        try {
          java.lang.reflect.Constructor<?> ctor2 =
              pktClass.getConstructor(String.class, FriendlyByteBuf.class);
          Object pkt = ctor2.newInstance(PERFORMANCE_CHANNEL.toString(), buf);
          var connection = player.connection;
          if (connection != null) {
            java.lang.reflect.Method m = connection.getClass().getMethod("send", Object.class);
            m.invoke(connection, pkt);
            return;
          }
        } catch (Exception ex2) {
          LOGGER.debug(
              "Packet constructor mismatch for ClientboundCustomPayloadPacket: { }",
              ex2.getMessage());
        }
      }
    } catch (ClassNotFoundException cnfe) {
      // Packet class not available in this mapping; fall through to chat fallback
    } catch (Exception e) {
      LOGGER.debug(
          "Reflection send error when sending performance payload to { }: { }",
          player.getGameProfile().getName(),
          e.getMessage());
    }

    // Fallback: use chat/system message so the client receives the line even if custom payload
    // isn't available
    try {
      player.displayClientMessage(net.minecraft.network.chat.Component.literal(line), false);
    } catch (Exception ex) {
      LOGGER.debug(
          "Failed to fallback-send performance line to { }: { }",
          player.getGameProfile().getName(),
          ex.getMessage());
    }
  }

  /** Broadcast a performance line to all connected players on the server. */
  public static void broadcastPerformanceLine(MinecraftServer server, String line) {
    if (server == null) return;
    try {
      for (var level : server.getAllLevels()) {
        for (ServerPlayer player : level.players()) {
          sendPerformanceLineToPlayer(player, line);
        }
      }
    } catch (Exception e) {
      LOGGER.debug("Failed to broadcast performance payload to players: { }", e.getMessage());
    }
  }
}
