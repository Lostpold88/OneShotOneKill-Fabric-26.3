package com.oneshotonekill.event;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.ArenaWorlds;
import com.oneshotonekill.equipment.EquipmentManager;
import com.oneshotonekill.item.runtime.ArmedShots;
import com.oneshotonekill.item.runtime.Deployables;
import com.oneshotonekill.item.runtime.StatusAbilities;
import com.oneshotonekill.item.runtime.StealthBomberSystem;
import com.oneshotonekill.item.runtime.SlowMotionSystem;
import com.oneshotonekill.match.MatchManager;
import com.oneshotonekill.match.MatchManager.MatchState;
import com.oneshotonekill.match.ScoreboardManager;
import com.oneshotonekill.arena.RandomTpSystem.RespawnSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.world.level.GameType;

@SuppressWarnings({"ConstantValue", "resource", "unused"})
public final class PlayerEvents {
   private PlayerEvents() {
   }

   public static void register() {
      ServerPlayerEvents.JOIN.register(PlayerEvents::onLogin);
      ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> onRespawn(newPlayer));
      ServerPlayerEvents.LEAVE.register(PlayerEvents::onLogout);
   }

   private static void onLogin(ServerPlayer player) {
      if (player.level().getServer() != null) {
         PlayerEvents.PresenceMessages.announceJoin(player.level().getServer(), player);
      }
      PlayerEvents.PlayerArenaPlacement.INSTANCE.onJoin(player);
      ScoreboardManager.INSTANCE.updateAllScoreboards();
   }

   private static void onRespawn(ServerPlayer player) {
      PlayerEvents.PlayerArenaPlacement.INSTANCE.onRespawn(player);
   }

   /** Beim Verlassen müssen alle laufenden Wirkungen abgeräumt werden, sonst hängen sie am Profil. */
   private static void onLogout(ServerPlayer player) {
      if (player.level().getServer() != null) {
         PlayerEvents.PresenceMessages.announceQuit(player.level().getServer(), player);
      }
      StatusAbilities.INSTANCE.clearFor(player);
      Deployables.INSTANCE.clearFor(player);
      com.oneshotonekill.item.runtime.BoogieBombSystem.INSTANCE.clearFor(player);
      ArmedShots.INSTANCE.clearFor(player);
      StealthBomberSystem.INSTANCE.clearFor(player);
      ScoreboardManager.INSTANCE.updateAllScoreboards();
   }

   /*
    * Der Name in der Tabellenliste kommt aus {@code ServerPlayerTabListMixin}.
    * <p>
    * Fabric API kennt kein Gegenstück zu NeoForges {@code TabListNameFormat}; die Zeile wird
    * deshalb wieder direkt an {@code ServerPlayer#getTabListDisplayName} gesetzt.
    */

   /** Setzt Spieler beim Beitritt und nach dem Respawn wieder in die aktive Arena. */
   public static final class PlayerArenaPlacement {
      public static final PlayerArenaPlacement INSTANCE = new PlayerArenaPlacement();
   
      private PlayerArenaPlacement() {
      }
   
      public void onJoin(ServerPlayer player) {
         ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
         if (worlds == null) {
            OneShotOneKill.INSTANCE.getLOGGER().warn("Beitritt: Arenaverwaltung läuft nicht, {} bleibt wo er ist.", player.getGameProfile().name());
            return;
         }
   
         player.getInventory().clearContent();
         player.setGameMode(GameType.SURVIVAL);
         worlds.placeOnJoin(player);
         if (MatchManager.INSTANCE.getCurrentMatchState() == MatchState.RUNNING) {
            // Die Lobby liegt in derselben Dimension, aber außerhalb der Kampfmaske. Ein
            // Spätbeitritt blieb bisher dort stehen und wirkte deshalb wie ein unverwundbarer
            // Matchspieler. Der zentrale Respawnweg setzt ihn an einen echten Kampfspawn und
            // vergibt zugleich das zum Spielmodus passende Loadout samt Waffenspiel-HUD.
            RespawnSystem.INSTANCE.respawnInstant(player, worlds.getActive(), player.position(), false);
         } else {
            EquipmentManager.INSTANCE.clearBaseEquipment(player);
         }
         SlowMotionSystem.INSTANCE.syncJoiningPlayer(player);
         com.oneshotonekill.item.runtime.BoogieBombSystem.INSTANCE.syncJoiningPlayer(player);
         MatchManager.INSTANCE.sendState(player, false);
      }
   
      public void onRespawn(ServerPlayer player) {
         ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
         if (worlds != null) {
            RespawnSystem.INSTANCE.respawnInstant(player, worlds.getActive(), player.position(), false);
         }
      }
   }

   
   
   /**
    * Die eigene Beitritts- und Abschiedsmeldung im Chat.
    * <p>
    * Vanilla schickt bereits „X joined the game“ – und zwar ohne abbrechbares Ereignis, direkt aus
    * {@code PlayerList#placeNewPlayer} beziehungsweise {@code ServerGamePacketListenerImpl}. Statt
    * dafür einen Mixin einzuführen, wird die Vanilla-Zeile auf dem Client verworfen: Dort bietet
    * Fabric API mit {@code ClientReceiveMessageEvents.ALLOW_GAME} ein reguläres Ereignis an –
    * siehe {@code client/ClientInputEvents#isSuppressed}. Das ist gefahrlos, weil Client und
    * Server dieselbe Mod-Fassung laden müssen.
    */
   public static final class PresenceMessages {
      private static final ChatFormatting BRACKET = ChatFormatting.DARK_GRAY;
      private static final ChatFormatting BRAND = ChatFormatting.GOLD;
      private static final ChatFormatting TEXT = ChatFormatting.GRAY;
      private static final ChatFormatting NAME = ChatFormatting.WHITE;
      private static final ChatFormatting JOINED = ChatFormatting.GREEN;
      private static final ChatFormatting LEFT = ChatFormatting.RED;
   
      private PresenceMessages() {
      }
   
      public static void announceJoin(MinecraftServer server, ServerPlayer player) {
         // Der Zähler steht bewusst auf beiden Meldungen: er beantwortet die Frage, die nach einem
         // Beitritt oder Abgang als nächstes kommt, ohne dass jemand die Tabellenliste öffnen muss.
         broadcast(server, line(player, "▸", JOINED, "betreten", onlineCount(server, player, true)));
      }
   
      public static void announceQuit(MinecraftServer server, ServerPlayer player) {
         broadcast(server, line(player, "◂", LEFT, "verlassen", onlineCount(server, player, false)));
      }
   
      /**
       * Baut die Zeile: {@code [OSOK] ▸ Name hat OneShotOneKill betreten · 3 online}
       */
      private static Component line(ServerPlayer player, String arrow, ChatFormatting accent, String verb, int online) {
         MutableComponent message = Component.empty()
            .append(Component.literal("[").withStyle(BRACKET))
            .append(Component.literal("OSOK").withStyle(BRAND, ChatFormatting.BOLD))
            .append(Component.literal("] ").withStyle(BRACKET))
            .append(Component.literal(arrow + " ").withStyle(accent, ChatFormatting.BOLD))
            .append(Component.literal(player.getGameProfile().name()).withStyle(NAME, ChatFormatting.BOLD))
            .append(Component.literal(" hat ").withStyle(TEXT))
            .append(Component.literal("OneShotOneKill").withStyle(BRAND))
            .append(Component.literal(" ").withStyle(TEXT))
            .append(Component.literal(verb).withStyle(accent, ChatFormatting.BOLD));
   
         return message
            .append(Component.literal(" · ").withStyle(BRACKET))
            .append(Component.literal(online + " online").withStyle(BRACKET));
      }
   
      /**
       * Beim Beitritt steht der Spieler schon in der Liste, beim Verlassen noch – gezählt wird
       * deshalb einmal so, wie es sich nach dem Ereignis darstellt.
       */
      private static int onlineCount(MinecraftServer server, ServerPlayer player, boolean joining) {
         int players = server.getPlayerList().getPlayerCount();
         boolean listed = server.getPlayerList().getPlayer(player.getUUID()) != null;
         if (joining) {
            return listed ? players : players + 1;
         }
         return listed ? players - 1 : players;
      }
   
      private static void broadcast(MinecraftServer server, Component message) {
         server.getPlayerList().broadcastSystemMessage(message, false);
      }
   }
}
