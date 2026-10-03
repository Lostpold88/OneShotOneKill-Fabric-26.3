package com.oneshotonekill.client.hud;

import static com.oneshotonekill.client.hud.HudFx.argb;
import static com.oneshotonekill.client.hud.HudFx.clamp01;
import static com.oneshotonekill.client.hud.HudFx.easeOutCubic;
import static com.oneshotonekill.client.hud.HudFx.smooth;

import com.oneshotonekill.client.screen.OsokWidgets;
import com.oneshotonekill.client.state.ClientStates.TabScoreboardState;
import com.oneshotonekill.network.OsokPayloads.MatchScoreboardPayload;
import com.oneshotonekill.network.OsokPayloads.MatchScoreboardPayload.PlayerEntry;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

/**
 * Das Tab-Scoreboard: eine Glas-Tafel im Esports-Stil, die beim Halten von Tab einfährt.
 * <p>
 * Aufbau: Kopf mit Modus-Chip, Arena, großer Uhr (bei Kill-Limit ein Fortschrittsbalken zum Ziel des Führenden)
 * und Spielerzahl; darunter die Spalten und eine Zeile je Spieler mit Rang-Chip, Kopf, Name, Status, Kills als
 * dezentem Balkendiagramm hinter der Zeile, K/D, Serie mit Blitz und Ping als Signalbalken; unten eine
 * Leiste mit den eigenen Werten. Krone, Blitz, Statuspunkt und Signalbalken werden selbst gezeichnet - die
 * Emojis der Sprachdateien erscheinen in Minecrafts Schrift oft als Kästchen.
 * <p>
 * Gezeichnet wird in einem Entwurfsraum (siehe {@link HudFx#uiScale}), das Einfahren wird aus {@code progress}
 * berechnet; die Zeilen erscheinen leicht gestaffelt.
 */
@SuppressWarnings("unused")
public final class TabScoreboardHudLayer {
   private static final int PANEL_WIDTH = 560;
   private static final int HEADER_HEIGHT = 52;
   private static final int COL_HEADER_HEIGHT = 16;
   private static final int ROW_HEIGHT = 20;
   private static final int FOOTER_HEIGHT = 36;
   private static final int MAX_VISIBLE_ROWS = 14;

   // Spaltenanker, gemessen vom linken Panelrand
   private static final int X_RANK = 12;
   private static final int X_FACE = 36;
   private static final int X_NAME = 54;
   private static final int X_STATUS = 220;
   private static final int R_KILLS = PANEL_WIDTH - 210;
   private static final int R_DEATHS = PANEL_WIDTH - 170;
   private static final int R_KD = PANEL_WIDTH - 122;
   private static final int R_STREAK = PANEL_WIDTH - 72;
   private static final int R_PING_TEXT = PANEL_WIDTH - 30;

   private static final int GOLD = 0xFFD54A;
   private static final int SILVER = 0xD7DEE8;
   private static final int BRONZE = 0xCD7F32;
   private static final int GREEN = 0x34D399;
   private static final int RED = 0xF05252;
   private static final int MUTED = 0x94A3B8;
   private static final int WHITE = 0xF8FAFC;

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

      int width = graphics.guiWidth();
      int height = graphics.guiHeight();
      float ui = HudFx.uiScale(width, height);
      float vw = width / ui;
      float vh = height / ui;

      float open = clamp01(progress);
      float a = smooth(open);
      float slide = (1.0F - easeOutCubic(open)) * -18.0F;
      int left = Math.round((vw - PANEL_WIDTH) / 2.0F);
      int top = Math.round(Math.max(10.0F, (vh - panelHeight) / 2.0F) + slide);
      int right = left + PANEL_WIDTH;
      int bottom = top + panelHeight;
      float time = Util.getMillis() / 1000.0F;

      boolean gunGame = "GUN_GAME".equals(payload.gameMode());
      boolean paused = payload.matchState() == 3;
      boolean lobby = payload.matchState() == 0;
      int accent = paused ? 0xEAB308 : lobby ? OsokWidgets.COLOR_CYAN & 0xFFFFFF
         : gunGame ? GOLD : OsokWidgets.COLOR_CRIMSON & 0xFFFFFF;

      graphics.pose().pushMatrix();
      graphics.pose().scale(ui, ui);

      // Hintergrund abdunkeln, damit die Tafel vor dem Spielfeld steht
      graphics.fill(0, 0, Math.round(vw), Math.round(vh), argb(0x02050A, 0.34F * a));

      drawFrame(graphics, left, top, right, bottom, accent, a, time);
      drawHeader(graphics, font, left, top, right, payload, players, accent, gunGame, paused, lobby, a, time);
      drawColumnHeaders(graphics, font, left, right, top + HEADER_HEIGHT, gunGame, a);

      int rowY = top + HEADER_HEIGHT + COL_HEADER_HEIGHT;
      UUID localId = client.player != null ? client.player.getUUID() : null;
      int maxKills = 1;
      for (PlayerEntry entry : players) {
         maxKills = Math.max(maxKills, entry.kills());
      }
      for (int i = 0; i < rowCount; i++) {
         if (i >= players.size()) {
            HudFx.leftText(graphics, font, Component.translatable("hud.oneshotonekill.scoreboard.empty").getString(),
               left + 14, rowY + 6, 1.0F, argb(MUTED, a));
            break;
         }
         float rowAlpha = a * clamp01(open * 1.6F - i * 0.045F);
         if (rowAlpha <= 0.01F) {
            rowY += ROW_HEIGHT;
            continue;
         }
         PlayerEntry entry = players.get(i);
         boolean local = localId != null && localId.equals(entry.playerId());
         drawPlayerRow(graphics, font, client, left, right, rowY, i, entry, local, gunGame, maxKills, accent, rowAlpha, time);
         rowY += ROW_HEIGHT;
      }

      drawFooter(graphics, font, left, right, bottom - FOOTER_HEIGHT, state, localId, accent, a);
      graphics.pose().popMatrix();
   }

   // ------------------------------------------------------------------
   // Rahmen
   // ------------------------------------------------------------------

   private static void drawFrame(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom,
                                 int accent, float a, float time) {
      // weicher Schlagschatten
      for (int i = 1; i <= 5; i++) {
         graphics.fill(left - i, top - i + 3, right + i, bottom + i + 3, argb(0x000000, 0.07F * a * (6 - i) / 5.0F));
      }
      graphics.fillGradient(left, top, right, bottom, argb(0x0E1626, 0.95F * a), argb(0x070B14, 0.97F * a));
      graphics.outline(left, top, right - left, bottom - top, argb(0x2A3A55, a));

      // Akzentleiste oben mit wanderndem Glanz
      graphics.fill(left, top, right, top + 2, argb(accent, a));
      float sweep = ((time * 0.30F) % 1.5F - 0.25F) * (right - left);
      graphics.enableScissor(left, top, right, top + 2);
      graphics.fillGradient(Math.round(left + sweep), top, Math.round(left + sweep + 120), top + 2,
         argb(0xFFFFFF, 0.0F), argb(0xFFFFFF, 0.9F * a));
      graphics.disableScissor();

      // Eckklammern
      int arm = 14;
      int bracket = argb(accent, 0.85F * a);
      graphics.fill(left - 3, top - 3, left - 3 + arm, top - 2, bracket);
      graphics.fill(left - 3, top - 3, left - 2, top - 3 + arm, bracket);
      graphics.fill(right + 3 - arm, top - 3, right + 3, top - 2, bracket);
      graphics.fill(right + 2, top - 3, right + 3, top - 3 + arm, bracket);
      graphics.fill(left - 3, bottom + 2, left - 3 + arm, bottom + 3, bracket);
      graphics.fill(left - 3, bottom + 3 - arm, left - 2, bottom + 3, bracket);
      graphics.fill(right + 3 - arm, bottom + 2, right + 3, bottom + 3, bracket);
      graphics.fill(right + 2, bottom + 3 - arm, right + 3, bottom + 3, bracket);
   }

   // ------------------------------------------------------------------
   // Kopf
   // ------------------------------------------------------------------

   private static void drawHeader(GuiGraphicsExtractor graphics, Font font, int left, int top, int right,
                                  MatchScoreboardPayload payload, List<PlayerEntry> players, int accent,
                                  boolean gunGame, boolean paused, boolean lobby, float a, float time) {
      // Modus-Chip
      String mode = paused ? "PAUSED" : lobby ? "LOBBY // WARMUP" : gunGame ? "GUN GAME" : "DEATHMATCH";
      float modeScale = 1.0F;
      int chipW = Math.round(font.width(mode) * modeScale) + 16;
      graphics.fill(left + 12, top + 10, left + 12 + chipW, top + 24, argb(accent, 0.18F * a));
      graphics.outline(left + 12, top + 10, chipW, 14, argb(accent, 0.75F * a));
      graphics.fill(left + 12, top + 10, left + 14, top + 24, argb(accent, a));
      HudFx.leftText(graphics, font, mode, left + 20, top + 13, modeScale, argb(HudFx.lighten(accent, 0.25F), a));

      String arena = payload.arenaName().isEmpty()
         ? (lobby ? Component.translatable("hud.oneshotonekill.scoreboard.main_lobby").getString()
            : Component.translatable("hud.oneshotonekill.scoreboard.default_arena").getString())
         : payload.arenaName().toUpperCase(Locale.ROOT);
      HudFx.leftText(graphics, font, arena, left + 14, top + 30, 0.95F, argb(MUTED, a));

      // Uhr in der Mitte
      int centerX = (left + right) / 2;
      String clock;
      String label;
      int clockColor = WHITE;
      float barFill = -1.0F;
      if (lobby) {
         clock = "READY";
         label = "WAITING FOR MATCH";
         clockColor = OsokWidgets.COLOR_CYAN & 0xFFFFFF;
      } else if ("TIME_LIMIT".equals(payload.targetMode())) {
         int seconds = Math.max(0, payload.remainingTicks() / 20);
         clock = HudFx.twoDigits(seconds / 60) + ":" + HudFx.twoDigits(seconds % 60);
         label = "TIME REMAINING";
         if (seconds <= 30) {
            clockColor = RED;
            if (((int) (time * 2.0F) & 1) == 0) {
               clockColor = HudFx.lighten(RED, 0.3F);
            }
         } else if (seconds <= 60) {
            clockColor = GOLD;
         }
      } else if ("KILL_LIMIT".equals(payload.targetMode())) {
         int lead = 0;
         for (PlayerEntry entry : players) {
            lead = Math.max(lead, entry.kills());
         }
         clock = lead + " / " + payload.targetValue();
         label = "FIRST TO " + payload.targetValue() + " KILLS";
         clockColor = OsokWidgets.COLOR_CYAN & 0xFFFFFF;
         barFill = payload.targetValue() <= 0 ? 0.0F : clamp01(lead / (float) payload.targetValue());
      } else {
         int seconds = payload.elapsedTicks() / 20;
         clock = HudFx.twoDigits(seconds / 60) + ":" + HudFx.twoDigits(seconds % 60);
         label = "MATCH TIME";
      }
      HudFx.bigText(graphics, font, clock, centerX, top + 17, 2.0F, argb(clockColor, a), true);
      HudFx.smallText(graphics, font, label, centerX, top + 33, 0.8F, argb(MUTED, a));
      if (barFill >= 0.0F) {
         int barW = 120;
         graphics.fill(centerX - barW / 2, top + 43, centerX + barW / 2, top + 46, argb(0x1E293B, a));
         graphics.fill(centerX - barW / 2, top + 43, centerX - barW / 2 + Math.round(barW * barFill), top + 46, argb(clockColor, a));
      }

      // Spielerzahl und Lebende rechts
      int alive = 0;
      for (PlayerEntry entry : players) {
         if (entry.isAlive()) {
            alive++;
         }
      }
      HudFx.rightText(graphics, font, players.size() + " PLAYERS", right - 14, top + 13, 1.0F, argb(WHITE, a));
      statusDot(graphics, right - 14 - 6, top + 33, GREEN, a);
      HudFx.rightText(graphics, font, alive + " ALIVE", right - 26, top + 29, 0.9F, argb(GREEN, a));

      graphics.fillGradient(left + 10, top + HEADER_HEIGHT - 3, right - 10, top + HEADER_HEIGHT - 2,
         argb(accent, 0.5F * a), argb(accent, 0.05F * a));
   }

   private static void drawColumnHeaders(GuiGraphicsExtractor graphics, Font font, int left, int right, int y,
                                         boolean gunGame, float a) {
      int muted = argb(MUTED, a);
      HudFx.leftText(graphics, font, "#", left + X_RANK + 3, y + 3, 0.85F, muted);
      HudFx.leftText(graphics, font, Component.translatable("hud.oneshotonekill.scoreboard.col_player").getString(),
         left + X_NAME, y + 3, 0.85F, muted);
      HudFx.leftText(graphics, font, gunGame
            ? Component.translatable("hud.oneshotonekill.scoreboard.col_tier_progress").getString()
            : Component.translatable("hud.oneshotonekill.scoreboard.col_status").getString(),
         left + X_STATUS, y + 3, 0.85F, muted);
      HudFx.rightText(graphics, font, "K", left + R_KILLS, y + 3, 0.85F, muted);
      HudFx.rightText(graphics, font, "D", left + R_DEATHS, y + 3, 0.85F, muted);
      HudFx.rightText(graphics, font, "K/D", left + R_KD, y + 3, 0.85F, muted);
      HudFx.rightText(graphics, font, Component.translatable("hud.oneshotonekill.scoreboard.col_streak").getString(),
         left + R_STREAK, y + 3, 0.85F, muted);
      HudFx.rightText(graphics, font, Component.translatable("hud.oneshotonekill.scoreboard.col_ping").getString(),
         right - 12, y + 3, 0.85F, muted);
      graphics.fill(left + 8, y + COL_HEADER_HEIGHT - 2, right - 8, y + COL_HEADER_HEIGHT - 1, argb(0xFFFFFF, 0.10F * a));
   }

   // ------------------------------------------------------------------
   // Zeilen
   // ------------------------------------------------------------------

   private static void drawPlayerRow(GuiGraphicsExtractor graphics, Font font, Minecraft client, int left, int right,
                                     int y, int rank, PlayerEntry entry, boolean local, boolean gunGame, int maxKills,
                                     int accent, float a, float time) {
      int rowLeft = left + 6;
      int rowRight = right - 6;
      boolean dead = !entry.isAlive();
      float dim = dead ? 0.55F : 1.0F;

      // Zeilenhintergrund: Streifenmuster plus Balkendiagramm der Kills
      graphics.fill(rowLeft, y, rowRight, y + ROW_HEIGHT - 1, argb(0xFFFFFF, (rank % 2 == 0 ? 0.045F : 0.02F) * a));
      int barWidth = Math.round((rowRight - rowLeft) * (entry.kills() / (float) maxKills));
      if (barWidth > 0) {
         int barColour = local ? OsokWidgets.COLOR_CYAN & 0xFFFFFF : rank == 0 ? GOLD : accent;
         graphics.fillGradient(rowLeft, y + ROW_HEIGHT - 3, rowLeft + barWidth, y + ROW_HEIGHT - 2,
            argb(barColour, 0.55F * a), argb(barColour, 0.05F * a));
         graphics.fill(rowLeft, y, rowLeft + barWidth, y + ROW_HEIGHT - 1, argb(barColour, 0.045F * a));
      }
      if (local) {
         float pulse = 0.85F + 0.15F * (float) Math.sin(time * 3.0F);
         graphics.fill(rowLeft, y, rowRight, y + ROW_HEIGHT - 1, argb(0x00E5FF, 0.10F * a * pulse));
         graphics.fill(rowLeft, y, rowLeft + 2, y + ROW_HEIGHT - 1, argb(0x00E5FF, a));
         graphics.fill(rowRight - 2, y, rowRight, y + ROW_HEIGHT - 1, argb(0x00E5FF, a));
      } else if (entry.isBounty()) {
         graphics.fill(rowLeft, y, rowLeft + 2, y + ROW_HEIGHT - 1, argb(GOLD, a));
      }

      // Rang-Chip
      int chip = switch (rank) {
         case 0 -> GOLD;
         case 1 -> SILVER;
         case 2 -> BRONZE;
         default -> 0x334155;
      };
      int chipX = left + X_RANK;
      graphics.fill(chipX, y + 3, chipX + 16, y + 15, argb(chip, a * (rank < 3 ? 1.0F : 0.7F)));
      HudFx.smallText(graphics, font, Integer.toString(rank + 1), chipX + 8, y + 5, 0.9F,
         argb(rank < 3 ? 0x0B1018 : WHITE, a));

      // Spielerkopf mit Rahmen
      int faceX = left + X_FACE;
      int faceY = y + 3;
      int faceFrame = local ? OsokWidgets.COLOR_CYAN & 0xFFFFFF : entry.isBounty() ? GOLD : 0x475569;
      graphics.fill(faceX - 1, faceY - 1, faceX + 13, faceY + 13, argb(faceFrame, a * dim));
      PlayerInfo info = client.getConnection() != null ? client.getConnection().getPlayerInfo(entry.playerId()) : null;
      if (info != null) {
         PlayerFaceExtractor.extractRenderState(graphics, info.getSkin(), faceX, faceY, 12, argb(0xFFFFFF, a * dim));
      } else {
         graphics.fill(faceX, faceY, faceX + 12, faceY + 12, argb(0x64748B, a * dim));
      }

      // Name, Krone bei Kopfgeld
      int nameX = left + X_NAME;
      if (entry.isBounty()) {
         crown(graphics, nameX, y + 5, a);
         nameX += 12;
      }
      int nameColor = local ? OsokWidgets.COLOR_CYAN & 0xFFFFFF : dead ? 0x71717A : WHITE;
      String name = entry.name() + (local ? Component.translatable("hud.oneshotonekill.scoreboard.you").getString() : "");
      HudFx.leftText(graphics, font, font.plainSubstrByWidth(name, 150 - (nameX - left - X_NAME)), nameX, y + 6, 1.0F,
         argb(nameColor, a));

      // Status oder Waffenspiel-Fortschritt
      int statusX = left + X_STATUS;
      if (gunGame) {
         String tier = font.plainSubstrByWidth("S" + entry.tier() + " " + entry.tierName(), 92);
         HudFx.leftText(graphics, font, tier, statusX, y + 6, 0.95F, argb(GOLD, a * dim));
         int pipX = statusX + Math.round(font.width(tier) * 0.95F) + 6;
         for (int k = 0; k < entry.requiredKills() && k < 8; k++) {
            boolean filled = k < entry.tierKills();
            graphics.fill(pipX + k * 6, y + 7, pipX + k * 6 + 4, y + 11, argb(filled ? GOLD : 0x475569, a * (filled ? 1.0F : 0.7F)));
         }
      } else {
         int statusColour = dead ? RED : GREEN;
         statusDot(graphics, statusX + 3, y + 9, statusColour, a * (dead ? 0.8F : 1.0F));
         HudFx.leftText(graphics, font, dead ? "DEAD" : Component.translatable("hud.oneshotonekill.scoreboard.alive").getString(),
            statusX + 11, y + 6, 0.9F, argb(statusColour, a * (dead ? 0.85F : 1.0F)));
      }

      // Zahlen
      HudFx.rightText(graphics, font, Integer.toString(entry.kills()), left + R_KILLS, y + 6, 1.0F, argb(GREEN, a * dim));
      HudFx.rightText(graphics, font, Integer.toString(entry.deaths()), left + R_DEATHS, y + 6, 1.0F, argb(RED, a * dim));
      int kdColor = entry.kdRatio() >= 2.0 ? GOLD : entry.kdRatio() >= 1.0 ? GREEN : MUTED;
      HudFx.rightText(graphics, font, entry.kdRatioFormatted(), left + R_KD, y + 6, 1.0F, argb(kdColor, a * dim));

      // Serie mit Blitz
      int streakColor = entry.isBounty() ? GOLD : entry.streak() > 0 ? 0xFDE047 : 0x64748B;
      String streak = entry.streak() + (entry.highestStreak() > 0 ? " (" + entry.highestStreak() + ")" : "");
      HudFx.rightText(graphics, font, streak, left + R_STREAK, y + 6, 0.95F, argb(streakColor, a * dim));
      bolt(graphics, left + R_STREAK - Math.round(font.width(streak) * 0.95F) - 8, y + 5, argb(streakColor, a * dim));

      // Ping als Signalbalken
      int ping = entry.ping();
      int bars = ping < 40 ? 4 : ping < 80 ? 3 : ping < 140 ? 2 : 1;
      int pingColor = ping < 60 ? GREEN : ping < 130 ? GOLD : RED;
      HudFx.rightText(graphics, font, ping + "ms", left + R_PING_TEXT, y + 6, 0.85F, argb(pingColor, a));
      for (int bar = 0; bar < 4; bar++) {
         int h = 2 + bar * 2;
         graphics.fill(right - 24 + bar * 3, y + 15 - h, right - 22 + bar * 3, y + 15,
            argb(bar < bars ? pingColor : 0x334155, a * (bar < bars ? 1.0F : 0.7F)));
      }
   }

   private static void statusDot(GuiGraphicsExtractor graphics, int cx, int cy, int colour, float a) {
      HudFx.disc(graphics, cx, cy, 2.6F, argb(colour, 0.30F * a));
      HudFx.disc(graphics, cx, cy, 1.6F, argb(colour, a));
   }

   /** Kleine Krone aus Linien: Sockel und drei Zacken. */
   private static void crown(GuiGraphicsExtractor graphics, int x, int y, float a) {
      int colour = argb(GOLD, a);
      graphics.fill(x, y + 6, x + 9, y + 8, colour);
      HudFx.line(graphics, x, y + 6, x + 1, y + 1, 1.4F, colour);
      HudFx.line(graphics, x + 1, y + 1, x + 4.5F, y + 5, 1.4F, colour);
      HudFx.line(graphics, x + 4.5F, y + 5, x + 8, y + 1, 1.4F, colour);
      HudFx.line(graphics, x + 8, y + 1, x + 9, y + 6, 1.4F, colour);
   }

   /** Kleiner Blitz aus drei Strichen. */
   private static void bolt(GuiGraphicsExtractor graphics, int x, int y, int colour) {
      HudFx.line(graphics, x + 4, y, x, y + 5, 1.3F, colour);
      HudFx.line(graphics, x, y + 5, x + 4, y + 5, 1.3F, colour);
      HudFx.line(graphics, x + 4, y + 5, x + 1, y + 10, 1.3F, colour);
   }

   // ------------------------------------------------------------------
   // Fuß
   // ------------------------------------------------------------------

   private static void drawFooter(GuiGraphicsExtractor graphics, Font font, int left, int right, int y,
                                  TabScoreboardState state, UUID localId, int accent, float a) {
      graphics.fillGradient(left + 10, y, right - 10, y + 1, argb(accent, 0.05F * a), argb(accent, 0.5F * a));

      int rank = localId != null ? state.getLocalPlayerRank(localId) : -1;
      PlayerEntry local = localId != null ? state.getLocalPlayerEntry(localId) : null;

      if (local != null) {
         int x = left + 14;
         x = chip(graphics, font, x, y + 8, "RANK", "#" + rank, rank == 1 ? GOLD : WHITE, a);
         x = chip(graphics, font, x, y + 8, "KILLS", Integer.toString(local.kills()), GREEN, a);
         x = chip(graphics, font, x, y + 8, "DEATHS", Integer.toString(local.deaths()), RED, a);
         x = chip(graphics, font, x, y + 8, "K/D", local.kdRatioFormatted(), local.kdRatio() >= 1.0 ? GREEN : MUTED, a);
         x = chip(graphics, font, x, y + 8, "STREAK", Integer.toString(local.streak()), local.streak() > 0 ? 0xFDE047 : MUTED, a);
         if (local.isBounty()) {
            crown(graphics, x + 2, y + 12, a);
            HudFx.leftText(graphics, font, "BOUNTY", x + 15, y + 13, 0.9F, argb(GOLD, a));
         }
      } else {
         HudFx.leftText(graphics, font, Component.translatable("hud.oneshotonekill.scoreboard.footer_default").getString(),
            left + 14, y + 13, 1.0F, argb(MUTED, a));
      }
      HudFx.rightText(graphics, font, Component.translatable("hud.oneshotonekill.scoreboard.hold_hint").getString(),
         right - 14, y + 13, 0.9F, argb(0x64748B, a));
   }

   /** Eine beschriftete Wertkachel; liefert die x-Position für die nächste. */
   private static int chip(GuiGraphicsExtractor graphics, Font font, int x, int y, String label, String value,
                           int valueColour, float a) {
      float labelScale = 0.7F;
      float valueScale = 1.1F;
      int width = Math.max(Math.round(font.width(label) * labelScale), Math.round(font.width(value) * valueScale)) + 14;
      graphics.fill(x, y, x + width, y + 22, argb(0xFFFFFF, 0.05F * a));
      graphics.fill(x, y, x + 2, y + 22, argb(valueColour, a));
      HudFx.leftText(graphics, font, label, x + 7, y + 3, labelScale, argb(MUTED, a));
      HudFx.leftText(graphics, font, value, x + 7, y + 11, valueScale, argb(valueColour, a));
      return x + width + 6;
   }
}
