package com.oneshotonekill.shared;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.arena.ArenaWorlds;
import com.oneshotonekill.item.runtime.Deployables;
import com.oneshotonekill.match.MatchManager.MatchState;
import com.oneshotonekill.match.MatchManager;
import net.minecraft.server.level.ServerPlayer;

/** Gemeinsame Einsatzbedingung aller Spezial-Items: laufendes Match, in der aktiven Arena. */
public final class SpecialItemRules {
   private SpecialItemRules() {
   }

   public static boolean canUse(ServerPlayer player) {
      return activeArena(player) != null && !Deployables.INSTANCE.isFrozen(player)
         && !com.oneshotonekill.item.runtime.BoogieBombSystem.INSTANCE.isDancing(player);
   }

   /**
    * Wie {@link #canUse}, sagt dem Spieler aber, warum nichts passiert.
    */
   public static boolean canUseOrExplain(ServerPlayer player) {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      if (worlds == null) {
         Feedback.actionBar(player, "§c✖ Arenaverwaltung läuft nicht");
         return false;
      }
      if (MatchManager.INSTANCE.getCurrentMatchState() != MatchState.RUNNING) {
         Feedback.actionBar(player, "§e✖ Spezial-Items wirken nur im laufenden Match");
         return false;
      }
      if (MatchManager.Countdown.INSTANCE.isCountdownRunning()) {
         Feedback.actionBar(player, "§e✖ Während des Countdowns gesperrt");
         return false;
      }
      if (com.oneshotonekill.item.runtime.BoogieBombSystem.INSTANCE.isDancing(player)) {
         Feedback.actionBar(player, "§d♫ BOOGIE! §7· keine Waffen oder Items");
         return false;
      }
      if (Deployables.INSTANCE.isFrozen(player)) {
         Feedback.actionBar(player, "§b❄ Eingefroren — keine Items einsetzbar");
         return false;
      }

      Arena arena = worlds.getActive();
      if (worlds.arenaOf(player) != arena || !arena.isInArena(player.getX(), player.getY(), player.getZ())) {
         Feedback.actionBar(player, "§e✖ Nur innerhalb der Arena einsetzbar");
         return false;
      }
      return true;
   }

   /** Die aktive Arena, wenn der Spieler gerade darin kämpfen darf – sonst {@code null}. */
   public static Arena activeArena(ServerPlayer player) {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      if (worlds == null
         || MatchManager.INSTANCE.getCurrentMatchState() != MatchState.RUNNING
         || MatchManager.Countdown.INSTANCE.isCountdownRunning()) {
         return null;
      }

      Arena arena = worlds.getActive();
      if (worlds.arenaOf(player) != arena || !arena.isInArena(player.getX(), player.getY(), player.getZ())) {
         return null;
      }
      return arena;
   }
}
