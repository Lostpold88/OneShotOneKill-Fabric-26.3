package com.oneshotonekill.client.hud;

import com.oneshotonekill.client.screen.OsokWidgets;
import com.oneshotonekill.client.state.ClientStates.TabScoreboardState;
import com.oneshotonekill.network.OsokPayloads.MatchScoreboardPayload;
import com.oneshotonekill.network.OsokPayloads.MatchScoreboardPayload.PlayerEntry;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;

/**
 * Modernes CS:GO / Valorant-inspiriertes Tab-Listen-System.
 * <p>
 * Ersetzt das veraltete Vanilla-Scoreboard und die Text-Tabliste durch ein rahmenloses,
 * halbtransparentes Cyber-Esports-Panel mit Live-Statistiken, Köpfen, Pings und Runden-Telemetrie.
 * Alle Elemente blenden synchron mit exakt identischer Transparenzrate ein und aus.
 */
@SuppressWarnings("unused")
public final class TabScoreboardHudLayer {
   private static final int PANEL_WIDTH = 550;
   private static final int HEADER_HEIGHT = 38;
   private static final int COL_HEADER_HEIGHT = 16;
   private static final int ROW_HEIGHT = 18;
   private static final int FOOTER_HEIGHT = 28;
   private static final int MAX_VISIBLE_ROWS = 14;

   // Spalten-Abstände von der rechten Panel-Kante
   private static final int COL_PING_EDGE = 14;
   private static final int COL_STREAK_EDGE = 64;
   private static final int COL_KD_EDGE = 118;
   private static final int COL_DEATHS_EDGE = 166;
   private static final int COL_KILLS_EDGE = 208;
   private static final int COL_STATUS_LEFT = 240;

   private TabScoreboardHudLayer() {
   }

   public static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, float progress) {
      Minecraft client = Minecraft.getInstance();
      if (progress <= 0.01F || client.gui.screen() != null) {
         return;
      }

      TabScoreboardState state = TabScoreboardState.INSTANCE;
      MatchScoreboardPayload payload = state.getPayload();
      Font font = client.font;

      List<PlayerEntry> players = state.getSortedEntries();
      int rowCount = Math.clamp(players.size(), 1, MAX_VISIBLE_ROWS);
      int panelHeight = HEADER_HEIGHT + COL_HEADER_HEIGHT + rowCount * ROW_HEIGHT + FOOTER_HEIGHT;

      int screenWidth = graphics.guiWidth();
      int screenHeight = graphics.guiHeight();
      int left = (screenWidth - PANEL_WIDTH) / 2;
      int top = Math.max(12, (screenHeight - panelHeight) / 2);
      int right = left + PANEL_WIDTH;
      int bottom = top + panelHeight;

      // Sanfte Skalierung und Ausblendung bei Fade-In / Fade-Out
      float alphaRatio = Math.clamp(progress, 0.0F, 1.0F);
      int bgAlpha = Math.round(235 * alphaRatio);
      int shadowAlpha = Math.round(110 * alphaRatio);

      boolean isGunGame = "GUN_GAME".equals(payload.gameMode());
      boolean isPaused = payload.matchState() == 3;
      boolean isLobby = payload.matchState() == 0;
      int accent = isPaused ? 0xFFEAB308 : (isLobby ? OsokWidgets.COLOR_CYAN : (isGunGame ? OsokWidgets.COLOR_GOLD : OsokWidgets.COLOR_CRIMSON));

      // 1. Schatten & Frosted-Glass-Kartenkörper
      graphics.fill(left + 2, bottom, right + 2, bottom + 4, shadowAlpha << 24);
      graphics.fill(right, top + 2, right + 4, bottom + 4, shadowAlpha << 24);
      graphics.fill(left, top, right, bottom, ARGB.color(bgAlpha, 10, 14, 24));

      // 2. Akzent-Bordüren (Neon-Top-Line & Umrandung)
      graphics.fill(left, top, right, top + 2, withAlpha(accent, alphaRatio));
      graphics.horizontalLine(left, right - 1, bottom - 1, withAlpha(0xB4465064, alphaRatio));
      graphics.verticalLine(left, top + 2, bottom - 1, withAlpha(0xB4465064, alphaRatio));
      graphics.verticalLine(right - 1, top + 2, bottom - 1, withAlpha(0xB4465064, alphaRatio));

      // 3. Header-Bereich
      drawHeader(graphics, font, left, top, right, payload, accent, isGunGame, isPaused, isLobby, players.size(), alphaRatio);

      // 4. Spalten-Überschriften
      int colY = top + HEADER_HEIGHT;
      drawColumnHeaders(graphics, font, left, right, colY, isGunGame, alphaRatio);

      // 5. Spieler-Zeilen
      int rowY = colY + COL_HEADER_HEIGHT;
      UUID localId = client.player != null ? client.player.getUUID() : null;

      for (int i = 0; i < rowCount; i++) {
         if (i >= players.size()) {
            graphics.text(font, Component.translatable("hud.oneshotonekill.scoreboard.empty").getString(), left + 14, rowY + 4, withAlpha(OsokWidgets.COLOR_TEXT_MUTED, alphaRatio));
            break;
         }

         PlayerEntry entry = players.get(i);
         boolean isLocal = localId != null && localId.equals(entry.playerId());
         drawPlayerRow(graphics, font, left, right, rowY, i, entry, isLocal, isGunGame, client, alphaRatio);
         rowY += ROW_HEIGHT;
      }

      // 6. Footer (Persönliche Match-Performance & Steuerung)
      drawFooter(graphics, font, left, right, bottom - FOOTER_HEIGHT, state, localId, alphaRatio);
   }

   private static void drawHeader(GuiGraphicsExtractor graphics, Font font, int left, int top, int right,
                                  MatchScoreboardPayload payload, int accent, boolean isGunGame, boolean isPaused, boolean isLobby,
                                  int playerCount, float alphaRatio) {
      // Modus-Badge & Arena-Name (links)
      String modeBadge = isPaused
         ? Component.translatable("hud.oneshotonekill.scoreboard.paused").getString()
         : (isLobby
            ? Component.translatable("hud.oneshotonekill.scoreboard.lobby").getString()
            : (isGunGame
               ? Component.translatable("hud.oneshotonekill.scoreboard.gungame").getString()
               : Component.translatable("hud.oneshotonekill.scoreboard.deathmatch").getString()));
      graphics.text(font, modeBadge, left + 14, top + 10, withAlpha(accent, alphaRatio));

      String arenaTag = payload.arenaName().isEmpty()
         ? (isLobby ? Component.translatable("hud.oneshotonekill.scoreboard.main_lobby").getString() : Component.translatable("hud.oneshotonekill.scoreboard.default_arena").getString())
         : Component.translatable("hud.oneshotonekill.scoreboard.arena_tag", payload.arenaName().toUpperCase()).getString();
      graphics.text(font, arenaTag, left + 14, top + 22, withAlpha(OsokWidgets.COLOR_TEXT_MUTED, alphaRatio));

      // Zentraler Timer / Zielwert
      int centerX = (left + right) / 2;
      String centerTitle;
      int centerColor = OsokWidgets.COLOR_TEXT_WHITE;

      if (isLobby) {
         centerTitle = "⚡ ONESHOTONEKILL ⚡";
         centerColor = OsokWidgets.COLOR_CYAN;
      } else if ("TIME_LIMIT".equals(payload.targetMode())) {
         int secs = Math.max(0, payload.remainingTicks() / 20);
         centerTitle = Component.translatable("hud.oneshotonekill.scoreboard.time_remaining", secs / 60, secs % 60).getString();
         if (secs <= 30) {
            centerColor = OsokWidgets.COLOR_CRIMSON;
         } else if (secs <= 60) {
            centerColor = OsokWidgets.COLOR_GOLD;
         }
      } else if ("KILL_LIMIT".equals(payload.targetMode())) {
         centerTitle = Component.translatable("hud.oneshotonekill.scoreboard.kill_target", payload.targetValue()).getString();
         centerColor = OsokWidgets.COLOR_CYAN;
      } else {
         int secs = payload.elapsedTicks() / 20;
         centerTitle = Component.translatable("hud.oneshotonekill.scoreboard.time_elapsed", secs / 60, secs % 60).getString();
      }

      graphics.centeredText(font, Component.literal(centerTitle), centerX, top + 14, withAlpha(centerColor, alphaRatio));

      // Spieleranzahl (rechts)
      String countTag = Component.translatable("hud.oneshotonekill.scoreboard.player_count", playerCount).getString();
      right(graphics, font, countTag, right - 14, top + 14, withAlpha(OsokWidgets.COLOR_TEXT_WHITE, alphaRatio));

      // Header-Trennlinie
      graphics.horizontalLine(left + 10, right - 11, top + HEADER_HEIGHT - 2, withAlpha(0x22FFFFFF, alphaRatio));
   }

   private static void drawColumnHeaders(GuiGraphicsExtractor graphics, Font font, int left, int right, int y,
                                         boolean isGunGame, float alphaRatio) {
      graphics.fill(left + 6, y, right - 6, y + COL_HEADER_HEIGHT - 2, withAlpha(0x14FFFFFF, alphaRatio));

      graphics.text(font, "#", left + 14, y + 3, withAlpha(OsokWidgets.COLOR_TEXT_MUTED, alphaRatio));
      graphics.text(font, Component.translatable("hud.oneshotonekill.scoreboard.col_player").getString(), left + 44, y + 3, withAlpha(OsokWidgets.COLOR_TEXT_MUTED, alphaRatio));
      graphics.text(font, isGunGame ? Component.translatable("hud.oneshotonekill.scoreboard.col_tier_progress").getString() : Component.translatable("hud.oneshotonekill.scoreboard.col_status").getString(), left + COL_STATUS_LEFT, y + 3, withAlpha(OsokWidgets.COLOR_TEXT_MUTED, alphaRatio));

      right(graphics, font, "K", right - COL_KILLS_EDGE, y + 3, withAlpha(OsokWidgets.COLOR_TEXT_MUTED, alphaRatio));
      right(graphics, font, "D", right - COL_DEATHS_EDGE, y + 3, withAlpha(OsokWidgets.COLOR_TEXT_MUTED, alphaRatio));
      right(graphics, font, "K/D", right - COL_KD_EDGE, y + 3, withAlpha(OsokWidgets.COLOR_TEXT_MUTED, alphaRatio));
      right(graphics, font, Component.translatable("hud.oneshotonekill.scoreboard.col_streak").getString(), right - COL_STREAK_EDGE, y + 3, withAlpha(OsokWidgets.COLOR_TEXT_MUTED, alphaRatio));
      right(graphics, font, Component.translatable("hud.oneshotonekill.scoreboard.col_ping").getString(), right - COL_PING_EDGE, y + 3, withAlpha(OsokWidgets.COLOR_TEXT_MUTED, alphaRatio));

      graphics.horizontalLine(left + 6, right - 7, y + COL_HEADER_HEIGHT - 2, withAlpha(0x30FFFFFF, alphaRatio));
   }

   private static void drawPlayerRow(GuiGraphicsExtractor graphics, Font font, int left, int right, int y,
                                    int rankIndex, PlayerEntry entry, boolean isLocal, boolean isGunGame,
                                    Minecraft client, float alphaRatio) {
      int rowBg = isLocal ? 0x2200E5FF : ((rankIndex % 2 == 0) ? 0x0CFFFFFF : 0x04FFFFFF);
      graphics.fill(left + 6, y, right - 6, y + ROW_HEIGHT - 1, withAlpha(rowBg, alphaRatio));

      if (isLocal) {
         graphics.fill(left + 6, y, left + 9, y + ROW_HEIGHT - 1, withAlpha(OsokWidgets.COLOR_CYAN, alphaRatio));
         graphics.fill(right - 9, y, right - 6, y + ROW_HEIGHT - 1, withAlpha(OsokWidgets.COLOR_CYAN, alphaRatio));
      } else if (entry.isBounty()) {
         graphics.fill(left + 6, y, left + 9, y + ROW_HEIGHT - 1, withAlpha(OsokWidgets.COLOR_GOLD, alphaRatio));
      }

      // Rang-Farbe & Text
      int rankColor = switch (rankIndex) {
         case 0 -> OsokWidgets.COLOR_GOLD;
         case 1 -> 0xFFE2E8F0;
         case 2 -> 0xFFCD7F32;
         default -> OsokWidgets.COLOR_TEXT_MUTED;
      };
      graphics.text(font, String.valueOf(rankIndex + 1), left + 14, y + 5, withAlpha(rankColor, alphaRatio));

      // Spielerkopf (10x10)
      int headX = left + 28;
      int headY = y + 4;
      PlayerInfo info = client.getConnection() != null ? client.getConnection().getPlayerInfo(entry.playerId()) : null;
      if (info != null) {
         PlayerFaceExtractor.extractRenderState(graphics, info.getSkin(), headX, headY, 10, withAlpha(0xFFFFFFFF, alphaRatio));
      } else {
         graphics.fill(headX, headY, headX + 10, headY + 10, withAlpha(0x44FFFFFF, alphaRatio));
      }

      // Spielername & Kopfgeld-Krone
      int nameX = headX + 14;
      int nameColor = isLocal ? OsokWidgets.COLOR_CYAN : (entry.isAlive() ? OsokWidgets.COLOR_TEXT_WHITE : 0xFF71717A);
      String displayName = (entry.isBounty() ? "👑 " : "") + entry.name() + (isLocal ? Component.translatable("hud.oneshotonekill.scoreboard.you").getString() : "");
      graphics.text(font, font.plainSubstrByWidth(displayName, 150), nameX, y + 5, withAlpha(nameColor, alphaRatio));

      // Status / Waffenspiel-Fortschritt
      if (isGunGame) {
         StringBuilder pips = new StringBuilder(" [");
         for (int k = 0; k < entry.requiredKills(); k++) {
            pips.append(k < entry.tierKills() ? "●" : "○");
         }
         pips.append("]");
         String tierStr = "S" + entry.tier() + " " + entry.tierName();
         graphics.text(font, tierStr, left + COL_STATUS_LEFT, y + 5, withAlpha(OsokWidgets.COLOR_GOLD, alphaRatio));
         graphics.text(font, pips.toString(), left + COL_STATUS_LEFT + font.width(tierStr), y + 5, withAlpha(OsokWidgets.COLOR_TEXT_MUTED, alphaRatio));
      } else {
         String statusText = entry.isAlive()
            ? Component.translatable("hud.oneshotonekill.scoreboard.alive").getString()
            : Component.translatable("hud.oneshotonekill.scoreboard.dead").getString();
         int statusColor = entry.isAlive() ? 0xFF38BDF8 : OsokWidgets.COLOR_CRIMSON;
         graphics.text(font, statusText, left + COL_STATUS_LEFT, y + 5, withAlpha(statusColor, alphaRatio));
      }

      // Kills & Tode
      right(graphics, font, String.valueOf(entry.kills()), right - COL_KILLS_EDGE, y + 5, withAlpha(OsokWidgets.COLOR_EMERALD, alphaRatio));
      right(graphics, font, String.valueOf(entry.deaths()), right - COL_DEATHS_EDGE, y + 5, withAlpha(0xFFE05252, alphaRatio));

      // K/D
      int kdColor = entry.kdRatio() >= 2.0 ? OsokWidgets.COLOR_GOLD : (entry.kdRatio() >= 1.0 ? OsokWidgets.COLOR_EMERALD : OsokWidgets.COLOR_TEXT_MUTED);
      right(graphics, font, entry.kdRatioFormatted(), right - COL_KD_EDGE, y + 5, withAlpha(kdColor, alphaRatio));

      // Streak
      String streakStr = "⚡" + entry.streak() + (entry.highestStreak() > 0 ? " (" + entry.highestStreak() + ")" : "");
      int streakColor = entry.isBounty() ? OsokWidgets.COLOR_GOLD : (entry.streak() > 0 ? 0xFFFDE047 : OsokWidgets.COLOR_TEXT_MUTED);
      right(graphics, font, streakStr, right - COL_STREAK_EDGE, y + 5, withAlpha(streakColor, alphaRatio));

      // Ping
      int pingColor = entry.ping() < 50 ? OsokWidgets.COLOR_EMERALD : (entry.ping() < 120 ? OsokWidgets.COLOR_GOLD : OsokWidgets.COLOR_CRIMSON);
      right(graphics, font, entry.ping() + "ms", right - COL_PING_EDGE, y + 5, withAlpha(pingColor, alphaRatio));
   }

   private static void drawFooter(GuiGraphicsExtractor graphics, Font font, int left, int right, int y,
                                  TabScoreboardState state, UUID localId, float alphaRatio) {
      graphics.horizontalLine(left + 10, right - 11, y, withAlpha(0x22FFFFFF, alphaRatio));

      int rank = localId != null ? state.getLocalPlayerRank(localId) : -1;
      PlayerEntry local = localId != null ? state.getLocalPlayerEntry(localId) : null;

      if (local != null) {
         String summary = Component.translatable("hud.oneshotonekill.scoreboard.footer_stats",
            rank, local.kills(), local.deaths(), local.kdRatioFormatted(), local.streak()).getString();
         if (local.isBounty()) {
            summary += Component.translatable("hud.oneshotonekill.scoreboard.bounty_active").getString();
         }
         graphics.text(font, summary, left + 14, y + 10, withAlpha(OsokWidgets.COLOR_CYAN, alphaRatio));
      } else {
         graphics.text(font, Component.translatable("hud.oneshotonekill.scoreboard.footer_default").getString(), left + 14, y + 10, withAlpha(OsokWidgets.COLOR_TEXT_MUTED, alphaRatio));
      }

      String hint = Component.translatable("hud.oneshotonekill.scoreboard.hold_hint").getString();
      right(graphics, font, hint, right - 14, y + 10, withAlpha(0x8894A3B8, alphaRatio));
   }

   private static void right(GuiGraphicsExtractor graphics, Font font, String text, int edge, int y, int color) {
      graphics.text(font, text, edge - font.width(text), y, color);
   }

   private static int withAlpha(int argb, float alphaRatio) {
      int a = (argb >>> 24);
      int newA = Math.clamp(Math.round(a * alphaRatio), 0, 255);
      return (argb & 0x00FFFFFF) | (newA << 24);
   }
}
