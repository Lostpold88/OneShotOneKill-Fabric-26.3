package com.oneshotonekill.match;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.arena.ArenaWorlds;
import com.oneshotonekill.arena.ArenaWorlds.ResetOutcome;
import com.oneshotonekill.arena.RandomTpSystem;
import com.oneshotonekill.arena.RandomTpSystem.RespawnSystem;
import com.oneshotonekill.equipment.EquipmentManager;
import com.oneshotonekill.item.SpecialItem;
import com.oneshotonekill.item.box.SpecialItemManager;
import com.oneshotonekill.item.runtime.MinigunRuntime;
import com.oneshotonekill.network.OsokPayloads.*;
import com.oneshotonekill.nuke.NukeSequenceManager;
import com.oneshotonekill.shared.ArenaDemolition;
import com.oneshotonekill.shared.Feedback;
import com.oneshotonekill.shared.OsokEffects;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Der Ablauf eines Matches: starten, pausieren, stoppen, Arena wechseln, Match-Ziele verwalten und zurücksetzen.
 */
@SuppressWarnings({"BooleanMethodIsAlwaysInverted", "ConstantValue", "Convert2MethodRef", "RedundantTypeArguments", "resource", "UnnecessaryLocalVariable", "unused"})
public final class MatchManager {
   public static final MatchManager INSTANCE = new MatchManager();
   private static final int MAX_WEIGHT_ADJUSTMENT = 5;

   private static MatchState currentMatchState = MatchState.STOPPED;
   private static GameMode currentGameMode = GameMode.CLASSIC;
   private static MatchTargetMode targetMode = MatchTargetMode.TIME_LIMIT;
   private static int targetValue = 600; // 600 Sekunden (10 Min) bzw. 25 Kills
   private static int remainingTicks = 600 * 20;
   private static int elapsedTicks = 0;
   private static final Map<UUID, Map<Integer, ItemStack>> pausedSpecialItems = new LinkedHashMap<>();
   /**
    * Das Ergebnis steht fest, der Abschluss fehlt noch.
    * <p>
    * Gesetzt am Ende der Nuke-Sequenz, geräumt beim Starten und Stoppen. Solange dieses Merkmal
    * steht, ruht der Match-Timer und es erscheinen keine Item-Boxen mehr – die Runde ist
    * gelaufen, sie ist nur noch nicht abgeräumt.
    */
   private static boolean decided = false;

   private MatchManager() {
   }

   public MatchState getCurrentMatchState() {
      return currentMatchState;
   }

   public GameMode getCurrentGameMode() {
      return currentGameMode;
   }

   public MatchTargetMode getTargetMode() {
      return targetMode;
   }

   public int getTargetValue() {
      return targetValue;
   }

   public int getRemainingTicks() {
      return remainingTicks;
   }

   public int getElapsedTicks() {
      return elapsedTicks;
   }

   public void resetForServerSession() {
      MatchManager.Countdown.INSTANCE.cancelCountdown();
      currentMatchState = MatchState.STOPPED;
      decided = false;
      pausedSpecialItems.clear();
      remainingTicks = targetMode == MatchTargetMode.TIME_LIMIT ? targetValue * 20 : 0;
      elapsedTicks = 0;
      GunGameManager.INSTANCE.reset();
   }

   // -- Anweisungen aus den Menüs -------------------------------------------

   public void requestMenu(ServerPlayer player) {
      sendState(player, true);
   }

   public void setGameMode(ServerPlayer player, String modeName) {
      if (!settleBeforeArenaChange(player)) {
         player.sendSystemMessage(Component.translatable("chat.oneshotonekill.match.error_mode_only_stopped")
            .withStyle(ChatFormatting.YELLOW));
         return;
      }
      GameMode mode = GameMode.fromName(modeName);
      currentGameMode = mode;
      if (currentGameMode != GameMode.GUN_GAME) {
         GunGameManager.INSTANCE.clearStatuses(OneShotOneKill.INSTANCE.getServer());
      }
      if (currentGameMode == GameMode.GUN_GAME && targetMode == MatchTargetMode.KILL_LIMIT) {
         targetMode = MatchTargetMode.UNLIMITED;
         targetValue = 0;
         remainingTicks = 0;
      }
      broadcastState();
   }

   public void setMatchTarget(ServerPlayer player, String modeName, int value) {
      if (currentMatchState != MatchState.STOPPED) {
         player.sendSystemMessage(Component.translatable("chat.oneshotonekill.match.error_target_only_stopped")
            .withStyle(ChatFormatting.YELLOW));
         return;
      }
      MatchTargetMode mode = MatchTargetMode.fromName(modeName);
      if (currentGameMode == GameMode.GUN_GAME && mode == MatchTargetMode.KILL_LIMIT) {
         player.sendSystemMessage(Component.translatable("chat.oneshotonekill.match.error_gungame_no_kill_limit")
            .withStyle(ChatFormatting.YELLOW));
         return;
      }
      int boundedValue = switch (mode) {
         case TIME_LIMIT -> Math.clamp(value, 60, 3600); // 1 min (60s) bis 60 min (3600s)
         case KILL_LIMIT -> Math.clamp(value, 1, 100);   // 1 bis 100 Kills
         case UNLIMITED -> 0;
      };
      targetMode = mode;
      targetValue = boundedValue;
      remainingTicks = targetMode == MatchTargetMode.TIME_LIMIT ? targetValue * 20 : 0;
      elapsedTicks = 0;
      broadcastState();
   }

   public void startMatch(ServerPlayer player) {
      if (currentMatchState == MatchState.RUNNING || NukeSequenceManager.INSTANCE.isRunning()) {
         return;
      }
      if (currentMatchState == MatchState.PAUSED) {
         resumeMatch();
         return;
      }
      currentMatchState = MatchState.RUNNING;
      decided = false;
      pausedSpecialItems.clear();
      remainingTicks = targetMode == MatchTargetMode.TIME_LIMIT ? targetValue * 20 : 0;
      elapsedTicks = 0;
      SpecialItemManager.INSTANCE.clearGroundItems();
      MatchManager.Countdown.INSTANCE.startCountdown();
      broadcastState();
   }

   public void pauseMatch(ServerPlayer player) {
      if (currentMatchState != MatchState.RUNNING || NukeSequenceManager.INSTANCE.isRunning()) {
         return;
      }
      MatchManager.Countdown.INSTANCE.cancelCountdown();
      currentMatchState = MatchState.PAUSED;
      OneShotOneKill.clearAbilities(OneShotOneKill.INSTANCE.getServer());
      pausedSpecialItems.clear();

      forEachOnlinePlayer(online -> {
         // Spezialitems vor dem Pausieren slot-getreu merken und aus dem Inventar räumen
         Map<Integer, ItemStack> playerItems = new LinkedHashMap<>();
         Inventory inventory = online.getInventory();
         for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && SpecialItem.fromStack(stack) != null) {
               playerItems.put(slot, stack.copy());
               inventory.setItem(slot, ItemStack.EMPTY);
            }
         }
         if (!playerItems.isEmpty()) {
            pausedSpecialItems.put(online.getUUID(), playerItems);
         }

         EquipmentManager.INSTANCE.clearBaseEquipment(online);
         ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
         if (worlds != null) {
            worlds.sendToActiveLobby(online);
         }
         OsokEffects.INSTANCE.playPauseMatchEffect(online);
      });
      ScoreboardManager.INSTANCE.updateAllScoreboards();
      broadcastState();
   }

   public void stopMatch(ServerPlayer player) {
      if (currentMatchState == MatchState.STOPPED) {
         return;
      }
      MatchManager.Countdown.INSTANCE.cancelCountdown();
      pausedSpecialItems.clear();
      SpecialItemManager.INSTANCE.clearGroundItems();
      OneShotOneKill.clearAbilities(OneShotOneKill.INSTANCE.getServer());
      // Erst hier endet, was die Nuke hinterlassen hat: Die Zuschauer duerfen wieder spielen,
      // und die Krater wachsen zu. Waehrend der Sequenz und danach bleibt beides stehen –
      // die zerstoerte Karte ist das Ergebnis des Matches und soll zu sehen sein.
      NukeSequenceManager.INSTANCE.finish(OneShotOneKill.INSTANCE.getServer());

      /*
       * Der Rückbau der Karte.
       *
       * Hier stand einmal eine Fallunterscheidung: Nach einer Nuke sollte die Arena aus ihrem
       * Archiv neu ausgepackt werden, weil eine ausradierte Karte zu groß für den Rückbau
       * Block für Block schien. Das hat nicht funktioniert – der Server hält die Chunks der
       * Dimension im Speicher und schreibt sie über die frisch ausgepackten Dateien, sodass
       * jeder Schritt Erfolg meldete und die Karte trotzdem zerstört blieb.
       *
       * Jetzt hebt {@code ArenaDemolition#obliterate} den alten Zustand auf wie jeder andere
       * Einschlag auch, und hier wird er auf einen Schlag zurückgesetzt. Das geht durch
       * dieselbe Welt, in der zerstört wurde, und hat deshalb kein Zwischenlager, das
       * danebengehen könnte.
       */
      ArenaWorlds stopWorlds = OneShotOneKill.INSTANCE.getArenas();
      if (stopWorlds != null && stopWorlds.levelOf(stopWorlds.getActive()) != null) {
         ArenaDemolition.INSTANCE.restoreArena(stopWorlds.levelOf(stopWorlds.getActive()), stopWorlds.getActive());
      } else {
         ArenaDemolition.INSTANCE.restoreEverythingNow();
      }

      currentMatchState = MatchState.STOPPED;
      decided = false;
      remainingTicks = targetMode == MatchTargetMode.TIME_LIMIT ? targetValue * 20 : 0;
      elapsedTicks = 0;
      GunGameManager.INSTANCE.clearStatuses(OneShotOneKill.INSTANCE.getServer());
      GunGameManager.INSTANCE.reset();
      forEachOnlinePlayer(online -> {
         EquipmentManager.INSTANCE.clearBaseEquipment(online);
         ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
         if (worlds != null) {
            worlds.sendToActiveLobby(online);
         }
         OsokEffects.INSTANCE.playStopMatchEffect(online);
      });
      ScoreboardManager.INSTANCE.resetAllStats();
      broadcastState();
   }

   private void resumeMatch() {
      currentMatchState = MatchState.RUNNING;
      MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      Arena arena = worlds == null ? null : worlds.getActive();
      if (server != null) {
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (arena != null) {
               Vec3 target = RandomTpSystem.INSTANCE.getRandomArenaLocation(arena, server);
               ServerLevel level = server.getLevel(arena.getDimension());
               player.teleportTo(level == null ? player.level() : level, target.x, target.y, target.z,
                  Set.of(), player.getYRot(), player.getXRot(), false);
               player.fallDistance = 0.0;
            }
            if (currentGameMode == GameMode.GUN_GAME) {
               GunGameManager.INSTANCE.giveTierEquipment(player);
               GunGameManager.INSTANCE.syncStatus(player, false);
            } else {
               EquipmentManager.INSTANCE.giveOneShotEquipment(player);
            }

            // Gemerkte Spezialitems genau in die ursprünglichen Slots zurückgeben
            Map<Integer, ItemStack> savedItems = pausedSpecialItems.remove(player.getUUID());
            if (savedItems != null) {
               Inventory inventory = player.getInventory();
               for (Map.Entry<Integer, ItemStack> entry : savedItems.entrySet()) {
                  int slot = entry.getKey();
                  ItemStack stack = entry.getValue();
                  if (slot >= 0 && slot < inventory.getContainerSize()) {
                     inventory.setItem(slot, stack);
                  }
               }
               player.inventoryMenu.broadcastChanges();
            }

            OsokEffects.INSTANCE.playResumeMatchEffect(player);
         }
      }
      pausedSpecialItems.clear();
      ScoreboardManager.INSTANCE.updateAllScoreboards();
      broadcastState();
   }

   public void tick(MinecraftServer server) {
      // Waehrend der Nuke-Sequenz steht die Uhr. Die Punkte sind eingefroren, und ein Timer,
      // der waehrend des Countdowns weiterliefe, koennte das Match ein zweites Mal beenden.
      if (currentMatchState != MatchState.RUNNING || MatchManager.Countdown.INSTANCE.isCountdownRunning()
         || NukeSequenceManager.INSTANCE.isRunning() || decided) {
         return;
      }

      ensureCombatReady(server);

      if (currentGameMode == GameMode.GUN_GAME) {
         GunGameManager.INSTANCE.tick(server);
      }

      elapsedTicks++;
      if (targetMode == MatchTargetMode.TIME_LIMIT) {
         remainingTicks--;
         int remainingSecs = remainingTicks / 20;

         // Tick-Sound in den letzten 5 Sekunden
         if (remainingTicks > 0 && remainingTicks % 20 == 0 && remainingSecs <= 5) {
            forEachOnlinePlayer(player ->
               player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                  SoundEvents.NOTE_BLOCK_BELL, SoundSource.PLAYERS, 0.8F, 1.2F + (5 - remainingSecs) * 0.15F));
         }

         if (remainingTicks % 20 == 0) {
            ScoreboardManager.INSTANCE.updateAllScoreboards();
         }

         if (remainingTicks <= 0) {
            endMatchWithWinner(server, Component.translatable("chat.oneshotonekill.time_over").getString());
         }
      } else if (targetMode == MatchTargetMode.UNLIMITED) {
         if (elapsedTicks % 20 == 0) {
            ScoreboardManager.INSTANCE.updateAllScoreboards();
         }
      }
   }

   public void checkKillLimit(ServerPlayer killer, int currentKills) {
      if (currentMatchState == MatchState.RUNNING && targetMode == MatchTargetMode.KILL_LIMIT && currentGameMode != GameMode.GUN_GAME) {
         if (currentKills >= targetValue) {
            MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
            if (server != null) {
               endMatchWithWinner(server, Component.translatable("chat.oneshotonekill.kill_limit_reached").getString());
            }
         }
      }
   }

   /**
    * Das Match endet – aber nicht sofort.
    * <p>
    * <p>Frueher stand hier ein Titel, ein Klang und ein sofortiger Stopp. Das war korrekt und
    * vollkommen unspektakulaer: Der Bildschirm sprang um, und die Runde war vorbei. Jetzt
    * uebernimmt {@link NukeSequenceManager} die naechsten zweiundzwanzig Sekunden – Countdown,
    * Einschlag, Nachlauf, Abschlusstafel – und ruft danach {@link #stopMatch} selbst auf.</p>
    * <p>
    * <p>Die Meldung im Chat bleibt hier, denn sie gehoert zum Match und nicht zur Inszenierung:
    * Wer im Moment des Endes gerade wegsieht, soll sie im Verlauf nachlesen koennen.</p>
    */
   public void endMatchWithWinner(MinecraftServer server, String reason) {
      if (currentMatchState == MatchState.STOPPED || decided || NukeSequenceManager.INSTANCE.isRunning()) {
         return;
      }

      List<ServerPlayer> players = server.getPlayerList().getPlayers();
      ServerPlayer winner;
      if (currentGameMode == GameMode.GUN_GAME) {
         winner = players.stream()
            .max(Comparator.<ServerPlayer>comparingInt(p -> GunGameManager.INSTANCE.getPlayerTier(p.getUUID()))
               .thenComparingInt(p -> GunGameManager.INSTANCE.getPlayerTierKills(p.getUUID()))
               .thenComparingInt(p -> ScoreboardManager.INSTANCE.getKills(p.getUUID())))
            .orElse(null);
      } else {
         winner = players.stream()
            .max(Comparator.comparingInt(p -> ScoreboardManager.INSTANCE.getKills(p.getUUID())))
            .orElse(null);
      }

      int topKills = winner != null ? ScoreboardManager.INSTANCE.getKills(winner.getUUID()) : 0;
      int topTier = (winner != null && currentGameMode == GameMode.GUN_GAME) ? GunGameManager.INSTANCE.getPlayerTier(winner.getUUID()) : 0;
      boolean isDraw = winner == null || (currentGameMode == GameMode.GUN_GAME ? topTier == 1 && topKills == 0 : topKills == 0);

      Component title = isDraw
         ? Component.translatable("chat.oneshotonekill.match_draw").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
         : Component.translatable("chat.oneshotonekill.match_winner", winner.getScoreboardName()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);

      Component subtitle;
      if (currentGameMode == GameMode.GUN_GAME && winner != null && !isDraw) {
         subtitle = Component.literal("S" + topTier + " (" + topKills + " Kills) · " + reason).withStyle(ChatFormatting.YELLOW);
      } else {
         subtitle = Component.literal((isDraw ? "" : topKills + " Kills · ") + reason).withStyle(ChatFormatting.YELLOW);
      }

      Component chatMsg;
      if (currentGameMode == GameMode.GUN_GAME && winner != null && !isDraw) {
         chatMsg = Component.literal("[OSOK] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
            .append(Component.translatable("chat.oneshotonekill.match_winner", winner.getScoreboardName()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
      } else {
         chatMsg = Component.literal("[OSOK] ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
            .append(title);
      }

      server.getPlayerList().broadcastSystemMessage(chatMsg, false);

      if (currentGameMode == GameMode.GUN_GAME) {
         GunGameManager.INSTANCE.clearStatuses(server);
      }

      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      ServerLevel level = worlds == null ? null : worlds.getActiveLevel();
      if (level == null) {
         // Ohne Arena gibt es nichts zu sprengen – dann endet das Match wie frueher, sofort.
         for (ServerPlayer player : players) {
            player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 80, 20));
            player.connection.send(new ClientboundSetTitleTextPacket(title));
            player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
         }
         stopMatch(null);
         return;
      }

      NukeSequenceManager.INSTANCE.triggerSequence(level, isDraw ? null : winner, Component.literal(reason));
   }

   public void markDecided() {
      if (currentMatchState != MatchState.RUNNING) {
         return;
      }
      MatchManager.Countdown.INSTANCE.cancelCountdown();
      SpecialItemManager.INSTANCE.clearGroundItems();
      decided = true;
      broadcastState();
   }

   public boolean isDecided() {
      return decided;
   }

   /**
    * Bringt eine entschiedene Runde zu Ende, bevor an der Arena gedreht wird.
    *
    * @return ob jetzt an der Arena gearbeitet werden darf
    */
   private boolean settleBeforeArenaChange(ServerPlayer player) {
      if (decided) {
         stopMatch(player);
      }
      return currentMatchState == MatchState.STOPPED;
   }

   public void selectArena(ServerPlayer player, String arenaId) {
      withArena(player, arenaId, (arena, worlds) -> {
         if (!settleBeforeArenaChange(player)) {
            player.sendSystemMessage(Component.translatable("chat.oneshotonekill.match.error_arena_only_stopped")
               .withStyle(ChatFormatting.YELLOW));
            return;
         }
         if (arena == worlds.getActive()) {
            sendState(player, false);
            return;
         }
         if (worlds.switchTo(arena) == null) {
            player.sendSystemMessage(Component.translatable("chat.oneshotonekill.match.error_arena_not_loaded", arena.getDisplayName())
               .withStyle(ChatFormatting.RED));
            return;
         }

         forEachOnlinePlayer(online -> EquipmentManager.INSTANCE.clearBaseEquipment(online));
         SpecialItemManager.INSTANCE.clearGroundItems();
         OneShotOneKill.clearAbilities(OneShotOneKill.INSTANCE.getServer());
         forEachOnlinePlayer(online -> OsokEffects.INSTANCE.playMapSwitchEffect(online, arena.getDisplayName()));
         broadcastState();
      });
   }

   public void resetArena(ServerPlayer player, String arenaId) {
      withArena(player, arenaId, (arena, worlds) -> {
         if (!settleBeforeArenaChange(player)) {
            player.sendSystemMessage(Component.translatable("chat.oneshotonekill.match.error_reset_only_stopped")
               .withStyle(ChatFormatting.YELLOW));
            return;
         }

         ResetOutcome outcome = worlds.requestReset(arena);
         switch (outcome) {
            case STARTED -> {
               forEachOnlinePlayer(online -> {
                  EquipmentManager.INSTANCE.clearBaseEquipment(online);
                  OsokEffects.INSTANCE.playMapResetEffect(online, arena.getDisplayName());
               });
               broadcastState();
            }
            case BUSY -> player.sendSystemMessage(Component.translatable("chat.oneshotonekill.match.error_reset_busy")
               .withStyle(ChatFormatting.YELLOW));
            case NOT_OPEN -> player.sendSystemMessage(Component.translatable("chat.oneshotonekill.match.error_not_open", arena.getDisplayName())
               .withStyle(ChatFormatting.RED));
         }
      });
   }

   public void requestRespawn(ServerPlayer player) {
      if (currentMatchState != MatchState.RUNNING || MatchManager.Countdown.INSTANCE.isCountdownRunning() || NukeSequenceManager.INSTANCE.isRunning()) {
         return;
      }
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      if (worlds == null) {
         return;
      }

      Arena arena = worlds.getActive();
      if (!arena.isInArena(player.getX(), player.getY(), player.getZ())) {
         RespawnSystem.INSTANCE.respawnInstant(player, arena, player.position(), false);
         sendState(player, false);
      }
   }

   /** Räumt liegen gebliebene Nuke-/Admin-Flags auf, solange tatsächlich gekämpft wird. */
   private void ensureCombatReady(MinecraftServer server) {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      Arena arena = worlds == null ? null : worlds.getActive();
      if (arena == null) {
         return;
      }
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         if (worlds.arenaOf(player) != arena) {
            continue;
         }
         player.setPermanentlyInvulnerable(false);
         if (player.gameMode() != GameType.SURVIVAL) {
            player.setGameMode(GameType.SURVIVAL);
         }
         // Player#hurtServer prüft dieses zweite Flag getrennt von isInvulnerableTo. Es lässt
         // sich deshalb nicht über ServerPlayerInvulnerabilityMixin öffnen und muss hier
         // ebenfalls auf den Kampfzustand zurückgesetzt werden.
         if (player.getAbilities().invulnerable) {
            player.getAbilities().invulnerable = false;
            player.onUpdateAbilities();
         }
      }
   }

   /** Admin-Aktion aus der Zentral-GUI: entfernt alle geladenen Pfeile in allen Dimensionen. */
   public void clearAllArrows(ServerPlayer player) {
      if (!OneShotOneKill.isAdmin(player)) {
         OneShotOneKill.INSTANCE.getLOGGER().warn("{} hat versucht, serverweit Pfeile zu entfernen.",
            player.getGameProfile().name());
         return;
      }
      MinecraftServer server = player.level().getServer();
      if (server == null) {
         return;
      }
      int removed = MinigunRuntime.INSTANCE.clearAllArrows(server);
      Feedback.actionBar(player, Component.translatable("actionbar.oneshotonekill.arrows_cleared", removed));
   }

   public void adjustWeight(ServerPlayer player, AdjustSpecialItemWeightPayload payload) {
      SpecialItem item = SpecialItem.fromId(payload.getItemId());
      if (item == null) {
         OneShotOneKill.INSTANCE.getLOGGER().warn("Unbekanntes Spezialitem-Gewicht '{}' von {} abgewiesen.",
            payload.getItemId(), player.getGameProfile().name());
         return;
      }
      int adjustment = Math.clamp(payload.getAdjustment(), -MAX_WEIGHT_ADJUSTMENT, MAX_WEIGHT_ADJUSTMENT);
      if (adjustment != 0) {
         SpecialItemManager.INSTANCE.setWeight(item, SpecialItemManager.INSTANCE.weightOf(item) + adjustment);
         broadcastState();
      }
   }

   public void setItemMode(ServerPlayer player, String modeId) {
      SpecialItem.Mode mode = Arrays.stream(SpecialItem.Mode.values())
         .filter(candidate -> candidate.name().equals(modeId)).findFirst().orElse(null);
      if (mode == null) {
         OneShotOneKill.INSTANCE.getLOGGER().warn("Unbekannter Item-Modus '{}' von {} abgewiesen.",
            modeId, player.getGameProfile().name());
         return;
      }
      SpecialItemManager.INSTANCE.setItemMode(mode);
      broadcastState();
   }

   public void resetWeights(ServerPlayer player) {
      SpecialItemManager.INSTANCE.resetWeights();
      broadcastState();
   }

   public void giveSpecialItem(ServerPlayer player, String itemId) {
      if (!OneShotOneKill.isAdmin(player)) {
         OneShotOneKill.INSTANCE.getLOGGER().warn("{} hat versucht, ein Spezialitem aus der privaten GUI anzufordern.",
            player.getGameProfile().name());
         return;
      }

      SpecialItem item = SpecialItem.fromId(itemId);
      if (item == null) {
         OneShotOneKill.INSTANCE.getLOGGER().warn("Unbekanntes Spezialitem '{}' von {} abgewiesen.",
            itemId, player.getGameProfile().name());
         return;
      }

      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      Arena activeArena = worlds != null ? worlds.getActive() : null;
      Arena playerArena = worlds != null ? worlds.arenaOf(player) : null;
      if ((playerArena == Arena.TILTED_TOWERS || activeArena == Arena.TILTED_TOWERS)
         && item == SpecialItem.GRAPPLING_HOOK) {
         Feedback.actionBar(player, Component.translatable("actionbar.oneshotonekill.tilted_grappler_fixed"));
         return;
      }

      if (!player.getInventory().add(item.createStack())) {
         Feedback.actionBar(player, Component.translatable("actionbar.oneshotonekill.no_slot_for_item"));
      }
   }

   // -- Zustandsmeldung -----------------------------------------------------

   public void sendState(ServerPlayer player, boolean open) {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      if (worlds == null) {
         return;
      }

      Arena activeArena = worlds.getActive();
      Arena playerArena = worlds.arenaOf(player);
      String playerArenaId = playerArena == null ? "" : playerArena.getId();
      var openArenaIds = Arrays.stream(Arena.values()).filter(worlds::isOpen).map(Arena::getId).toList();
      String resettingArenaId = Arrays.stream(Arena.values()).filter(worlds::isResetting).map(Arena::getId).findFirst().orElse("");
      var itemWeights = Arrays.stream(SpecialItem.values()).map(SpecialItemManager.INSTANCE::weightOf).toList();
      boolean outsideArena = !activeArena.isInArena(player.getX(), player.getY(), player.getZ());

      ServerPlayNetworking.send(player, new ArenaMenuStatePayload(open, activeArena.getId(), playerArenaId,
         openArenaIds, resettingArenaId, currentMatchState.name(), outsideArena,
         SpecialItemManager.INSTANCE.getItemMode().name(), itemWeights,
         targetMode.name(), targetValue, remainingTicks, currentGameMode.name()));
   }

   public void broadcastState() {
      forEachOnlinePlayer(player -> sendState(player, false));
   }

   // -- Anweisungen ausführen -----------------------------------------------

   private void withArena(ServerPlayer player, String arenaId, ArenaAction action) {
      Arena arena = Arena.byId(arenaId);
      if (arena == null) {
         OneShotOneKill.INSTANCE.getLOGGER().warn("Unbekannte Arena '{}' von {}", arenaId, player.getGameProfile().name());
         return;
      }
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      if (worlds != null) {
         action.accept(arena, worlds);
      }
   }

   private void forEachOnlinePlayer(Consumer<ServerPlayer> action) {
      MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
      if (server != null) {
         server.getPlayerList().getPlayers().forEach(action);
      }
   }

   @FunctionalInterface
   private interface ArenaAction {
      void accept(Arena arena, ArenaWorlds worlds);
   }

   public enum GameMode {
      CLASSIC("🏆 Klassisch", "Standard Deathmatch mit Spezial-Items und Killstreaks."),
      GUN_GAME("🎯 Waffenspiel", "13-Stufen Progression. Wer Stufe 13 meistert gewinnt!");

      private final String displayName;
      private final String description;

      GameMode(String displayName, String description) {
         this.displayName = displayName;
         this.description = description;
      }

      public String getDisplayName() {
         return displayName;
      }

      public String getDescription() {
         return description;
      }

      public static GameMode fromName(String name) {
         for (GameMode mode : values()) {
            if (mode.name().equalsIgnoreCase(name)) {
               return mode;
            }
         }
         return CLASSIC;
      }
   }

   public enum MatchState {
      STOPPED,
      RUNNING,
      PAUSED;

      public final boolean isActive() {
         return this != STOPPED;
      }
   }

   public enum MatchTargetMode {
      UNLIMITED("♾ Unbegrenzt", "Match läuft ohne Limit bis zum manuellen Stopp."),
      TIME_LIMIT("⏱ Zeitlimit", "Match endet nach Ablauf der vorgegebenen Zeit."),
      KILL_LIMIT("🎯 Kill-Ziel", "Der erste Spieler mit der Ziel-Killanzahl gewinnt.");

      private final String displayName;
      private final String description;

      MatchTargetMode(String displayName, String description) {
         this.displayName = displayName;
         this.description = description;
      }

      public String getDisplayName() {
         return displayName;
      }

      public String getDescription() {
         return description;
      }

      public static MatchTargetMode fromName(String name) {
         for (MatchTargetMode mode : values()) {
            if (mode.name().equalsIgnoreCase(name)) {
               return mode;
            }
         }
         return TIME_LIMIT;
      }
   }

   public static final class Countdown {
      public static final Countdown INSTANCE = new Countdown();
      private static final int COUNTDOWN_TICKS = 60;
      private static final int COUNTDOWN_CANCELLED = -1;
      /** Ab dieser Abweichung vom Startpunkt wird zurückgesetzt. */
      private static final double DRIFT_TOLERANCE = 0.35;
      private static final Set<UUID> frozenPlayers = new LinkedHashSet<>();
      private static final Map<UUID, Vec3> frozenPositions = new LinkedHashMap<>();
      private static final Map<UUID, Float> frozenYaws = new LinkedHashMap<>();
      private static int remainingTicks = COUNTDOWN_CANCELLED;

      private Countdown() {
      }

      public void startCountdown() {
         MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
         if (server == null) {
            return;
         }

         frozenPlayers.clear();
         frozenPositions.clear();
         frozenYaws.clear();
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID playerId = player.getUUID();
            frozenPlayers.add(playerId);
            frozenPositions.put(playerId, player.position());
            frozenYaws.put(playerId, player.getYRot());
         }
         remainingTicks = COUNTDOWN_TICKS;
         broadcastCountdown();
         playCountdownBeat(3);
      }

      public void cancelCountdown() {
         if (remainingTicks < 0) {
            return;
         }

         remainingTicks = COUNTDOWN_CANCELLED;
         frozenPlayers.clear();
         frozenPositions.clear();
         frozenYaws.clear();
         MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
         if (server != null) {
            server.getPlayerList().getPlayers().forEach(player -> ServerPlayNetworking.send(player, MatchCountdownPayload.cancelled()));
         }
      }

      public void tick() {
         if (remainingTicks < 0) {
            return;
         }

         holdFrozenPlayers();
         remainingTicks--;
         switch (remainingTicks) {
            case 0 -> finishCountdownAndStartMatch();
            case 20 -> {
               broadcastCountdown();
               playCountdownBeat(1);
            }
            case 40 -> {
               broadcastCountdown();
               playCountdownBeat(2);
            }
            default -> {
            }
         }
      }

      public boolean isFrozen(ServerPlayer player) {
         return frozenPlayers.contains(player.getUUID());
      }

      public Vec3 getFrozenPosition(ServerPlayer player) {
         return isFrozen(player) ? frozenPositions.get(player.getUUID()) : null;
      }

      public Float getFrozenYaw(ServerPlayer player) {
         return isFrozen(player) ? frozenYaws.get(player.getUUID()) : null;
      }

      public boolean isCountdownRunning() {
         return remainingTicks >= 0;
      }

      /**
       * Hält jeden Eingefrorenen auf seinem Startpunkt.
       */
      private void holdFrozenPlayers() {
         MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
         if (server == null) {
            return;
         }

         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Vec3 anchor = getFrozenPosition(player);
            if (anchor == null) {
               continue;
            }
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            if (player.position().distanceToSqr(anchor) > DRIFT_TOLERANCE * DRIFT_TOLERANCE) {
               Float yaw = getFrozenYaw(player);
               player.teleportTo(player.level(), anchor.x, anchor.y, anchor.z, Set.<Relative>of(),
                  yaw == null ? player.getYRot() : yaw, player.getXRot(), false);
            }
         }
      }

      private void broadcastCountdown() {
         MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
         ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
         if (server == null) {
            return;
         }
         String arenaName = worlds != null ? worlds.getActive().getDisplayName() : "Standard";
         String modeName = currentGameMode == GameMode.GUN_GAME ? "Waffenspiel" : "Klassisch";
         MatchCountdownPayload payload = new MatchCountdownPayload(remainingTicks, false, arenaName, modeName);
         server.getPlayerList().getPlayers().forEach(player -> ServerPlayNetworking.send(player, payload));
      }

      /**
       * Mehrschichtiges Cyber-Audio-Design und Partikeleffekte für jede Sekunde.
       */
      private void playCountdownBeat(int seconds) {
         MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
         if (server == null) {
            return;
         }

         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ServerLevel level = player.level();
            Vec3 pos = player.position();
            switch (seconds) {
               case 3 -> {
                  // Sub-Bass & Initialisierung (Cyan)
                  OsokEffects.INSTANCE.playOwnSound(player, SoundEvents.WARDEN_HEARTBEAT, 1.0F, 0.65F);
                  OsokEffects.INSTANCE.playOwnSound(player, SoundEvents.BEACON_ACTIVATE, 0.7F, 0.75F);
                  OsokEffects.INSTANCE.playOwnSound(player, SoundEvents.NOTE_BLOCK_PLING.value(), 1.0F, 0.6F);
                  level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pos.x, pos.y + 0.1, pos.z, 20, 0.6, 0.1, 0.6, 0.05);
               }
               case 2 -> {
                  // Energie-Aufladung & Schild (Gold)
                  OsokEffects.INSTANCE.playOwnSound(player, SoundEvents.RESPAWN_ANCHOR_CHARGE, 0.85F, 1.2F);
                  OsokEffects.INSTANCE.playOwnSound(player, SoundEvents.WARDEN_HEARTBEAT, 1.0F, 1.05F);
                  OsokEffects.INSTANCE.playOwnSound(player, SoundEvents.NOTE_BLOCK_PLING.value(), 1.0F, 1.1F);
                  level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pos.x, pos.y + 0.2, pos.z, 30, 0.8, 0.2, 0.8, 0.08);
                  level.sendParticles(ParticleTypes.GLOW, pos.x, pos.y + 0.5, pos.z, 15, 0.5, 0.4, 0.5, 0.02);
               }
               default -> {
                  // Lock-On / Alarm (Crimson)
                  OsokEffects.INSTANCE.playOwnSound(player, SoundEvents.ARROW_HIT_PLAYER, 1.0F, 1.7F);
                  OsokEffects.INSTANCE.playOwnSound(player, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.0F, 1.6F);
                  OsokEffects.INSTANCE.playOwnSound(player, SoundEvents.WARDEN_HEARTBEAT, 1.0F, 1.4F);
                  OsokEffects.INSTANCE.playOwnSound(player, SoundEvents.NOTE_BLOCK_PLING.value(), 1.0F, 1.7F);
                  level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pos.x, pos.y + 0.3, pos.z, 45, 1.0, 0.3, 1.0, 0.12);
                  level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, pos.x, pos.y + 0.2, pos.z, 20, 0.6, 0.3, 0.6, 0.04);
               }
            }
         }
      }

      private void finishCountdownAndStartMatch() {
         remainingTicks = COUNTDOWN_CANCELLED;
         frozenPlayers.clear();
         frozenPositions.clear();
         frozenYaws.clear();
         MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
         ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
         if (server == null || worlds == null) {
            return;
         }

         Arena arena = worlds.getActive();
         String arenaName = arena.getDisplayName();
         String modeName = currentGameMode == GameMode.GUN_GAME ? "Waffenspiel" : "Klassisch";
         if (currentGameMode == GameMode.GUN_GAME) {
            GunGameManager.INSTANCE.startMatch(server);
         }
         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Vec3 target = RandomTpSystem.INSTANCE.getRandomArenaLocation(arena, server);
            ServerLevel level = server.getLevel(arena.getDimension());
            player.teleportTo(level == null ? player.level() : level, target.x, target.y, target.z, Set.of(), player.getYRot(), player.getXRot(), false);
            player.fallDistance = 0.0;
            if (currentGameMode == GameMode.GUN_GAME) {
               GunGameManager.INSTANCE.giveTierEquipment(player);
               GunGameManager.INSTANCE.syncStatus(player, false);
            } else {
               EquipmentManager.INSTANCE.giveOneShotEquipment(player);
            }
            OsokEffects.INSTANCE.playStartMatchEffect(player);
            ServerPlayNetworking.send(player, MatchCountdownPayload.go(arenaName, modeName));
         }
         ScoreboardManager.INSTANCE.updateAllScoreboards();
         MatchManager.INSTANCE.broadcastState();
      }
   }
}
