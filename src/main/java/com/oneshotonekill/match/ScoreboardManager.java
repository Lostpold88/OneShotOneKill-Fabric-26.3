package com.oneshotonekill.match;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.match.MatchManager.MatchState;
import com.oneshotonekill.match.MatchManager.MatchTargetMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team.Visibility;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType;

@SuppressWarnings({"ConstantValue", "resource", "UnusedReturnValue"})
public final class ScoreboardManager {
   public static final ScoreboardManager INSTANCE = new ScoreboardManager();
   private static final String OBJECTIVE_NAME = "oneshot";
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
      Objective objective = scoreboard.getObjective(OBJECTIVE_NAME);
      if (objective != null) {
         scoreboard.removeObjective(objective);
      }
   }

   public void updateAllScoreboards() {
      MinecraftServer server = OneShotOneKill.INSTANCE.getServer();
      if (server == null) {
         return;
      }

      ServerScoreboard scoreboard = server.getScoreboard();
      MatchState state = MatchManager.INSTANCE.getCurrentMatchState();
      List<ServerPlayer> players = server.getPlayerList().getPlayers();
      if (state.isActive() && !MatchManager.Countdown.INSTANCE.isCountdownRunning() && !players.isEmpty()) {
         updateMatchScoreboard(scoreboard, state, players);
      } else {
         scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, null);
         clearNameTagTeam(scoreboard);
         if (state == MatchState.STOPPED) {
            Objective objective = scoreboard.getObjective(OBJECTIVE_NAME);
            if (objective != null) {
               scoreboard.removeObjective(objective);
            }
         }
      }
      // Tab-Liste asynchron am Server-Tick-Ende verschicken, um den Treffer-Frame nicht zu blockieren:
      server.execute(() -> {
         List<ServerPlayer> currentPlayers = server.getPlayerList().getPlayers();
         currentPlayers.forEach(this::updateTabList);
      });
   }

   private void updateMatchScoreboard(ServerScoreboard scoreboard, MatchState state, List<ServerPlayer> players) {
      boolean isGunGame = MatchManager.INSTANCE.getCurrentGameMode() == MatchManager.GameMode.GUN_GAME;
      List<ServerPlayer> ranking = new ArrayList<>(players);
      if (isGunGame) {
         ranking.sort(Comparator.comparingInt((ServerPlayer p) -> GunGameManager.INSTANCE.getPlayerTier(p.getUUID()))
            .thenComparingInt(p -> GunGameManager.INSTANCE.getPlayerTierKills(p.getUUID()))
            .thenComparingInt(p -> getKills(p.getUUID()))
            .reversed());
      } else {
         ranking.sort(Comparator.comparingInt((ServerPlayer player) -> getKills(player.getUUID())).reversed());
      }

      Component title;
      if (state == MatchState.PAUSED) {
         title = Component.literal("⏸ OSOK | PAUSIERT").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD);
      } else if (isGunGame) {
         title = Component.literal("🎯 OSOK | WAFFENSPIEL").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
      } else {
         title = Component.literal("🎯 OSOK | MATCH").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
      }

      Objective objective = scoreboard.getObjective(OBJECTIVE_NAME);
      if (objective == null) {
         objective = scoreboard.addObjective(OBJECTIVE_NAME, ObjectiveCriteria.DUMMY, title, RenderType.INTEGER, true, BlankFormat.INSTANCE);
      }
      objective.setDisplayName(title);
      scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, objective);

      List<Component> lines = buildLines(ranking);
      for (int index = 0; index < lines.size(); index++) {
         ScoreHolder holder = ScoreHolder.forNameOnly("osok_line_" + index);
         var score = scoreboard.getOrCreatePlayerScore(holder, objective);
         score.set(lines.size() - index);
         score.display(lines.get(index));
         score.numberFormatOverride(BlankFormat.INSTANCE);
      }
      for (int index = lines.size(); index < 16; index++) {
         scoreboard.resetSinglePlayerScore(ScoreHolder.forNameOnly("osok_line_" + index), objective);
      }
      syncNameTagTeam(scoreboard, players);
   }

   public Component getTabDisplayName(ServerPlayer player) {
      if (!MatchManager.INSTANCE.getCurrentMatchState().isActive()) {
         return Component.literal(player.getScoreboardName()).withStyle(ChatFormatting.WHITE);
      }
      UUID playerId = player.getUUID();
      boolean isGunGame = MatchManager.INSTANCE.getCurrentGameMode() == MatchManager.GameMode.GUN_GAME;

      if (isGunGame) {
         GunGameManager.Tier tier = GunGameManager.INSTANCE.getTierFor(playerId);
         int tierKills = GunGameManager.INSTANCE.getPlayerTierKills(playerId);
         return Component.literal(player.getScoreboardName()).withStyle(ChatFormatting.WHITE)
            .append(Component.literal(" | ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("Stufe " + tier.getTierIndex() + ": " + tier.getDisplayName()).withStyle(tier.getColor(), ChatFormatting.BOLD))
            .append(Component.literal(" (" + tierKills + "/" + tier.getRequiredKills() + ")").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(" | ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("K/D: " + getKDRatio(playerId)).withStyle(ChatFormatting.AQUA));
      }

      String bounty = isBountyTarget(playerId) ? "👑 " : "";
      return Component.literal(bounty).withStyle(ChatFormatting.YELLOW)
         .append(Component.literal(player.getScoreboardName()).withStyle(ChatFormatting.WHITE))
         .append(Component.literal(" | ").withStyle(ChatFormatting.GRAY))
         .append(Component.literal("K: " + getKills(playerId)).withStyle(ChatFormatting.GREEN))
         .append(Component.literal(" | ").withStyle(ChatFormatting.GRAY))
         .append(Component.literal("D: " + getDeaths(playerId)).withStyle(ChatFormatting.RED))
         .append(Component.literal(" | ").withStyle(ChatFormatting.GRAY))
         .append(Component.literal("K/D: " + getKDRatio(playerId)).withStyle(ChatFormatting.AQUA))
         .append(Component.literal(" | ").withStyle(ChatFormatting.GRAY))
         .append(Component.literal("⚡" + getStreak(playerId)).withStyle(ChatFormatting.YELLOW))
         .append(Component.literal(" (★" + getHighestStreak(playerId) + ")").withStyle(ChatFormatting.GOLD));
   }

   public void updateTabList(ServerPlayer player) {
      if (!MatchManager.INSTANCE.getCurrentMatchState().isActive()) {
         player.connection.send(new ClientboundTabListPacket(Component.empty(), Component.empty()));
      } else {
         boolean isGunGame = MatchManager.INSTANCE.getCurrentGameMode() == MatchManager.GameMode.GUN_GAME;
         Component header = isGunGame
            ? Component.literal("\n🎯 OSOK | WAFFENSPIEL\n").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
            : Component.literal("\n🎯 OSOK | MATCH STATS\n").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
         Component footer = isGunGame
            ? Component.literal("\nErreiche Stufe 13 & meistere den Meisterdolch!\n").withStyle(ChatFormatting.YELLOW)
            : Component.literal("\nScoreboard & Leaderboard\n").withStyle(ChatFormatting.GRAY);
         player.connection.send(new ClientboundTabListPacket(header, footer));
      }

      // NeoForges refreshTabListName() gibt es nicht; die Zeile wird direkt neu verschickt.
      // Den Namen selbst liefert ServerPlayerTabListMixin aus getTabDisplayName().
      if (player.level().getServer() != null) {
         player.level().getServer().getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, player));
      }
   }

   private List<Component> buildLines(List<ServerPlayer> ranking) {
      List<Component> lines = new ArrayList<>();
      Component separator = Component.literal("-------------------").withStyle(ChatFormatting.GRAY);
      lines.add(separator);

      boolean isGunGame = MatchManager.INSTANCE.getCurrentGameMode() == MatchManager.GameMode.GUN_GAME;
      MatchTargetMode targetMode = MatchManager.INSTANCE.getTargetMode();

      if (!isGunGame) {
         if (targetMode == MatchTargetMode.TIME_LIMIT) {
            int remainingSecs = Math.max(0, MatchManager.INSTANCE.getRemainingTicks() / 20);
            int minutes = remainingSecs / 60;
            int seconds = remainingSecs % 60;
            String timeStr = String.format("%02d:%02d", minutes, seconds);
            ChatFormatting timeColor = remainingSecs <= 30 ? ChatFormatting.RED : (remainingSecs <= 60 ? ChatFormatting.YELLOW : ChatFormatting.GREEN);
            lines.add(Component.literal("⏱ Zeit: ").withStyle(ChatFormatting.GRAY)
               .append(Component.literal(timeStr).withStyle(timeColor, ChatFormatting.BOLD)));
            lines.add(separator);
         } else if (targetMode == MatchTargetMode.KILL_LIMIT) {
            int targetKills = MatchManager.INSTANCE.getTargetValue();
            lines.add(Component.literal("🎯 Ziel: ").withStyle(ChatFormatting.GRAY)
               .append(Component.literal(targetKills + " Kills").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
            lines.add(separator);
         } else if (targetMode == MatchTargetMode.UNLIMITED) {
            int elapsedSecs = MatchManager.INSTANCE.getElapsedTicks() / 20;
            int minutes = elapsedSecs / 60;
            int seconds = elapsedSecs % 60;
            String timeStr = String.format("%02d:%02d", minutes, seconds);
            lines.add(Component.literal("⏱ Dauer: ").withStyle(ChatFormatting.GRAY)
               .append(Component.literal(timeStr).withStyle(ChatFormatting.WHITE)));
            lines.add(separator);
         }
      }

      if (isGunGame) {
         lines.add(Component.literal("🎯 STUFEN-RANG:").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
         for (int index = 0; index < Math.min(ranking.size(), 8); index++) {
            ServerPlayer player = ranking.get(index);
            UUID playerId = player.getUUID();
            GunGameManager.Tier tier = GunGameManager.INSTANCE.getTierFor(playerId);
            int tierKills = GunGameManager.INSTANCE.getPlayerTierKills(playerId);
            ChatFormatting rankColor = switch (index) {
               case 0 -> ChatFormatting.GOLD;
               case 1 -> ChatFormatting.GRAY;
               case 2 -> ChatFormatting.RED;
               default -> ChatFormatting.WHITE;
            };
            StringBuilder pips = new StringBuilder(" [");
            for (int k = 0; k < tier.getRequiredKills(); k++) {
               pips.append(k < tierKills ? "●" : "○");
            }
            pips.append("]");

            lines.add(Component.literal("#" + (index + 1) + " ").withStyle(rankColor)
               .append(Component.literal(player.getScoreboardName()).withStyle(ChatFormatting.WHITE))
               .append(Component.literal(" » ").withStyle(ChatFormatting.GRAY))
               .append(Component.literal("S" + tier.getTierIndex()).withStyle(tier.getColor(), ChatFormatting.BOLD))
               .append(Component.literal(pips.toString()).withStyle(ChatFormatting.DARK_GRAY)));
         }
      } else {
         lines.add(Component.literal("🏆 TOP RANKING:").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
         for (int index = 0; index < Math.min(ranking.size(), 10); index++) {
            ServerPlayer player = ranking.get(index);
            UUID playerId = player.getUUID();
            ChatFormatting rankColor = switch (index) {
               case 0 -> ChatFormatting.GOLD;
               case 1 -> ChatFormatting.GRAY;
               case 2 -> ChatFormatting.RED;
               default -> ChatFormatting.WHITE;
            };
            String bounty = isBountyTarget(playerId) ? "👑 " : "";
            lines.add(Component.literal("#" + (index + 1) + " ").withStyle(rankColor)
               .append(Component.literal(bounty).withStyle(ChatFormatting.YELLOW))
               .append(Component.literal(player.getScoreboardName()).withStyle(ChatFormatting.WHITE))
               .append(Component.literal(" » ").withStyle(ChatFormatting.GRAY))
               .append(Component.literal(getKills(playerId) + "K").withStyle(ChatFormatting.GREEN))
               .append(Component.literal(" | ").withStyle(ChatFormatting.GRAY))
               .append(Component.literal(getKDRatio(playerId)).withStyle(ChatFormatting.AQUA))
               .append(Component.literal(" | ").withStyle(ChatFormatting.GRAY))
               .append(Component.literal("⚡" + getStreak(playerId)).withStyle(ChatFormatting.YELLOW))
               .append(Component.literal(" (★" + getHighestStreak(playerId) + ")").withStyle(ChatFormatting.GOLD)));
         }
      }

      if (ranking.isEmpty()) {
         lines.add(Component.literal("Keine Spieler online").withStyle(ChatFormatting.GRAY));
      }
      lines.add(separator);
      return lines;
   }

   private void syncNameTagTeam(Scoreboard scoreboard, List<ServerPlayer> players) {
      PlayerTeam team = scoreboard.getPlayerTeam(NAMETAG_TEAM);
      if (team == null) {
         team = scoreboard.addPlayerTeam(NAMETAG_TEAM);
         team.setNameTagVisibility(Visibility.NEVER);
      }
      // Die Farbe des Umrisses eines leuchtenden Spielers kommt aus seinem Team. Alle stehen
      // ohnehin in diesem einen, und leuchten tut nur, wer vom Radar markiert wurde – also
      // reicht es, hier Rot zu setzen, statt Markierte in ein zweites Team umzuhängen. Ein
      // Spieler kann nur in einem Team sein, und das Umhängen brächte seinen Namen zurück.
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
      Component announcement = Component.literal("[👑 KOPFGELD] ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
         .append(Component.literal(name).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
         .append(Component.literal(" hat eine 5er Streak! Wer ihn tötet erhält 2 Bonus-Items!").withStyle(ChatFormatting.YELLOW));
      server.getPlayerList().broadcastSystemMessage(announcement, false);
      if (player != null) {
         player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.MASTER, 0.6F, 1.8F);
      }
   }

   public void resetStreak(UUID playerId) {
      streaks.put(playerId, 0);
      bountyTargets.remove(playerId);
   }

   /**
    * Nimmt einem Ziel das Kopfgeld ab und meldet, ob es eines hatte.
    * <p>
    * Die Ansage versprach zwei Bonus-Items, ausgezahlt wurden nie welche: die Menge diente
    * allein der Krone in der Tabelle. Wer den Tragäger erledigt, bekommt sie jetzt wirklich.
    */
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
