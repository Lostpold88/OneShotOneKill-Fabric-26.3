package com.oneshotonekill.match;

import com.oneshotonekill.OneShotOneKill;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Kurze Ereignisse, die im Waffenspiel ein Kill-Fenster öffnen: ein abgefangener Treffer,
 * ein Teleport-Sprung, ein Zug am Grappling Hook. Die Systeme melden sie hier, das Waffenspiel
 * fragt sie bei der Kill-Wertung ab.
 */
public final class KillSignals {
   public static final KillSignals INSTANCE = new KillSignals();

   private final Map<UUID, Integer> shieldBlocks = new HashMap<>();
   private final Map<UUID, Integer> teleports = new HashMap<>();
   private final Map<UUID, Integer> grapples = new HashMap<>();
   private final Map<UUID, Integer> magnets = new HashMap<>();

   private KillSignals() {
   }

   private static int now() {
      MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
      return server == null ? 0 : server.getTickCount();
   }

   public void shieldBlocked(ServerPlayer player) {
      shieldBlocks.put(player.getUUID(), now());
   }

   public void teleported(ServerPlayer player) {
      teleports.put(player.getUUID(), now());
   }

   public void magnetActive(ServerPlayer player) {
      magnets.put(player.getUUID(), now());
   }

   public boolean magnetWithin(ServerPlayer player, int ticks) {
      return within(magnets, player, ticks);
   }

   public void grappling(ServerPlayer player) {
      grapples.put(player.getUUID(), now());
   }

   public boolean shieldBlockedWithin(ServerPlayer player, int ticks) {
      return within(shieldBlocks, player, ticks);
   }

   public boolean teleportedWithin(ServerPlayer player, int ticks) {
      return within(teleports, player, ticks);
   }

   public boolean grapplingWithin(ServerPlayer player, int ticks) {
      return within(grapples, player, ticks);
   }

   private static boolean within(Map<UUID, Integer> events, ServerPlayer player, int ticks) {
      Integer at = events.get(player.getUUID());
      return at != null && now() - at <= ticks;
   }

   public void clearFor(ServerPlayer player) {
      shieldBlocks.remove(player.getUUID());
      teleports.remove(player.getUUID());
      grapples.remove(player.getUUID());
      magnets.remove(player.getUUID());
   }

   public void reset() {
      shieldBlocks.clear();
      teleports.clear();
      grapples.clear();
      magnets.clear();
   }
}
