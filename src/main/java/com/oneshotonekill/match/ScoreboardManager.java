package com.oneshotonekill.match;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.ArenaWorlds;
import com.oneshotonekill.match.MatchManager.MatchState;
import com.oneshotonekill.network.OsokPayloads.MatchScoreboardPayload;
import com.oneshotonekill.network.OsokPayloads.MatchScoreboardPayload.PlayerEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team.Visibility;

@SuppressWarnings({"resource", "UnusedReturnValue", "unused"})
public final class ScoreboardManager {
   public static final ScoreboardManager INSTANCE = new ScoreboardManager();
   private static final String NAMETAG_TEAM = "no_nametag";
   private static final int BOUNTY_STREAK = 5;
   private static final Map<UUID, Integer> kills = new LinkedHashMap<>();
   private static final Map<UUID, Integer> deaths = new LinkedHashMap<>();
   private static final Map<UUID, Integer> streaks = new LinkedHashMap<>();
   private static final Map<UUID, Integer> highestStreaks = new LinkedHashMap<>();
   private static final Set<UUID> bountyTargets = new LinkedHashSet<>();

   private ScoreboardManager() {
   }

   public void clearAllScoreboardsOnServerStart(MinecraftServer server) {
      clearStats();
      ServerScoreboard scoreboard = server.getScoreboard();
      scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, null);
      clearNameTagTeam(scoreboard);
      Objective legacy = scoreboard.getObjective("oneshot");
      if (legacy != null) {
         scoreboard.removeObjective(legacy);
      }
   }

   public void updateAllScoreboards() {
      MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
      if (server == null) {
         return;
      }

      ServerScoreboard scoreboard = server.getScoreboard();
      scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, null);
      Objective legacy = scoreboard.getObjective("oneshot");
      if (legacy != null) {
         scoreboard.removeObjective(legacy);
      }

      MatchState state = MatchManager.INSTANCE.getCurrentMatchState();
      List<ServerPlayer> players = server.getPlayerList().getPlayers();
      if (!state.isActive() || players.isEmpty()) {
         clearNameTagTeam(scoreboard);
         MatchScoreboardPayload empty = MatchScoreboardPayload.empty();
         for (ServerPlayer player : players) {
            ServerPlayNetworking.send(player, empty);
         }
         return;
      }

      syncNameTagTeam(scoreboard, players);

      int stateCode = 0;
      if (MatchManager.Countdown.INSTANCE.isCountdownRunning()) {
         stateCode = 1;
      } else if (state == MatchState.RUNNING) {
         stateCode = 2;
      } else if (state == MatchState.PAUSED) {
         stateCode = 3;
      }

      boolean isGunGame = MatchManager.INSTANCE.getCurrentGameMode() == MatchManager.GameMode.GUN_GAME;
      String gameModeStr = isGunGame ? "GUN_GAME" : "CLASSIC";
      String targetModeStr = MatchManager.INSTANCE.getTargetMode().name();
      int targetVal = MatchManager.INSTANCE.getTargetValue();
      int remaining = MatchManager.INSTANCE.getRemainingTicks();
      int elapsed = MatchManager.INSTANCE.getElapsedTicks();
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      String arenaName = worlds != null && worlds.getActive() != null ? worlds.getActive().getDisplayName() : "";

      List<PlayerEntry> entries = new ArrayList<>();
      for (ServerPlayer player : players) {
         UUID pid = player.getUUID();
         int k = getKills(pid);
         int d = getDeaths(pid);
         int s = getStreak(pid);
         int hs = getHighestStreak(pid);
         boolean bounty = isBountyTarget(pid);
         boolean alive = player.isAlive();
         int ping = player.connection.latency();

         int tier = 1;
         int tierKills = 0;
         int reqKills = 1;
         String tierName = "";
         String tierColor = "white";
         if (isGunGame) {
            GunGameManager.Tier t = GunGameManager.INSTANCE.getTierFor(pid);
            tier = t.getTierIndex();
            tierKills = GunGameManager.INSTANCE.getPlayerTierKills(pid);
            reqKills = t.getRequiredKills();
            tierName = t.getDisplayName();
            tierColor = t.getColor().name();
         }

         entries.add(new PlayerEntry(
            pid, player.getScoreboardName(), k, d, s, hs, bounty, alive, ping,
            tier, tierKills, reqKills, tierName, tierColor
         ));
      }

      MatchScoreboardPayload payload = new MatchScoreboardPayload(
         stateCode, gameModeStr, targetModeStr, targetVal, remaining, elapsed, arenaName, entries
      );

      for (ServerPlayer player : players) {
         ServerPlayNetworking.send(player, payload);
      }
   }

   private void syncNameTagTeam(Scoreboard scoreboard, List<ServerPlayer> players) {
      PlayerTeam team = scoreboard.getPlayerTeam(NAMETAG_TEAM);
      if (team == null) {
         team = scoreboard.addPlayerTeam(NAMETAG_TEAM);
         team.setNameTagVisibility(Visibility.NEVER);
      }
      team.setColor(java.util.Optional.of(net.minecraft.world.scores.TeamColor.RED));
      Set<String> onlineNames = players.stream().map(ServerPlayer::getScoreboardName).collect(java.util.stream.Collectors.toSet());
      for (String name : onlineNames) {
         if (!team.getPlayers().contains(name)) {
            scoreboard.addPlayerToTeam(name, team);
         }
      }
      for (String name : new ArrayList<>(team.getPlayers())) {
         if (!onlineNames.contains(name)) {
            scoreboard.removePlayerFromTeam(name, team);
         }
      }
   }

   private void clearNameTagTeam(Scoreboard scoreboard) {
      PlayerTeam team = scoreboard.getPlayerTeam(NAMETAG_TEAM);
      if (team != null) {
         scoreboard.removePlayerTeam(team);
      }
   }

   public int addKill(UUID playerId) {
      int value = getKills(playerId) + 1;
      kills.put(playerId, value);
      return value;
   }

   public int addDeath(UUID playerId) {
      int value = getDeaths(playerId) + 1;
      deaths.put(playerId, value);
      return value;
   }

   public int addStreak(UUID playerId) {
      int value = getStreak(playerId) + 1;
      streaks.put(playerId, value);
      highestStreaks.merge(playerId, value, Math::max);
      if (value == BOUNTY_STREAK) {
         announceBounty(playerId);
      }
      return value;
   }

   private void announceBounty(UUID playerId) {
      bountyTargets.add(playerId);
      MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
      if (server == null) {
         return;
      }
      ServerPlayer player = server.getPlayerList().getPlayer(playerId);
      String name = player == null ? "Ein Spieler" : player.getScoreboardName();
      Component announcement = Component.translatable("chat.oneshotonekill.bounty_announced", name)
         .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD);
      server.getPlayerList().broadcastSystemMessage(announcement, false);
      if (player != null) {
         player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.MASTER, 0.6F, 1.8F);
      }
   }

   public void resetStreak(UUID playerId) {
      streaks.put(playerId, 0);
      bountyTargets.remove(playerId);
   }

   public boolean claimBounty(UUID playerId) {
      return bountyTargets.remove(playerId);
   }

   public boolean isBountyTarget(UUID playerId) {
      return bountyTargets.contains(playerId);
   }

   public int getKills(UUID playerId) {
      return kills.getOrDefault(playerId, 0);
   }

   public int getDeaths(UUID playerId) {
      return deaths.getOrDefault(playerId, 0);
   }

   public int getStreak(UUID playerId) {
      return streaks.getOrDefault(playerId, 0);
   }

   public int getHighestStreak(UUID playerId) {
      return highestStreaks.getOrDefault(playerId, 0);
   }

   public String getKDRatio(UUID playerId) {
      return String.format(Locale.US, "%.1f", getKDRatioValue(playerId));
   }

   public double getKDRatioValue(UUID playerId) {
      int deathCount = getDeaths(playerId);
      return deathCount == 0 ? getKills(playerId) : (double) getKills(playerId) / deathCount;
   }

   public void resetAllStats() {
      clearStats();
      updateAllScoreboards();
   }

   private void clearStats() {
      kills.clear();
      deaths.clear();
      streaks.clear();
      highestStreaks.clear();
      bountyTargets.clear();
   }
}
