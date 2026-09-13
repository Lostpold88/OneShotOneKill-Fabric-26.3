package com.oneshotonekill.client.hud;

import com.oneshotonekill.client.screen.OsokWidgets;
import com.oneshotonekill.client.state.ClientStates.*;
import static com.oneshotonekill.client.state.ClientStates.*;
import com.oneshotonekill.network.OsokPayloads.*;
import com.oneshotonekill.nuke.NukeSequenceManager.NukePhase;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

/**
 * Epische HUD-Ebenen für das OneShotOneKill Endgame (DEFCON-1 Countdown, Detonation & Championship Siegerehrung).
 */
@SuppressWarnings({"NullableProblems", "unused"})
public final class NukeHudLayers {
   private NukeHudLayers() {}

   // =========================================================================
   // NukeFlashLayer.java
   // =========================================================================
   /**
    * Thermonuklearer Doppelblitz (Double-Flash Phänomen), Thermal-Feuerwelle und Fallout-Schleier.
    */
   public static final class NukeFlashLayer implements HudElement {
      private static final int PEAK_TICKS = 8;
      private static final int THERMAL_TICKS = 34;
      private static final int FADE_TICKS = 55;
      private static final int FALLOUT_ALPHA = 75;

      private static boolean shakeTriggered = false;

      @Override
      public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
         NukeState state = NukeState.INSTANCE;
         if (!state.isRunning() || !state.hasDetonated()) {
            shakeTriggered = false;
            return;
         }

         int width = graphics.guiWidth();
         int height = graphics.guiHeight();
         float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
         float sinceBlast = state.currentTick() - NukePhase.DETONATION.from() + partialTick;

         // Einmaliger massiver Screen-Shake bei Einschlag
         if (!shakeTriggered) {
            CameraShakeState.INSTANCE.triggerDirect(1.15F, 48);
            shakeTriggered = true;
         }

         // 1. Initialer Weiß-Blau Peak (Tick 0 bis PEAK_TICKS)
         if (sinceBlast <= PEAK_TICKS) {
            float initialAlpha = 1.0F - (sinceBlast / (float) PEAK_TICKS) * 0.15F;
            graphics.fill(0, 0, width, height, ARGB.color((int) (initialAlpha * 255), 245, 250, 255));
         }
         // 2. Thermal Plasma Feuerblitz (Tick PEAK_TICKS bis THERMAL_TICKS)
         else if (sinceBlast <= PEAK_TICKS + THERMAL_TICKS) {
            float thermalProgress = (sinceBlast - PEAK_TICKS) / (float) THERMAL_TICKS;
            float thermalAlpha = (1.0F - thermalProgress) * (1.0F - thermalProgress);

            // Glühende Magma-Feuerwelle
            int r = Math.min(255, (int) (255 * (1.0F - thermalProgress * 0.3F)));
            int g = Math.max(40, (int) (140 * (1.0F - thermalProgress)));
            int b = Math.max(10, (int) (30 * (1.0F - thermalProgress)));
            graphics.fill(0, 0, width, height, ARGB.color((int) (thermalAlpha * 240), r, g, b));

            // Zarte Stoßwellen-Verzerrungsringe
            int shockRadius = Math.round(thermalProgress * (width / 2.0F + 100));
            int ringAlpha = (int) ((1.0F - thermalProgress) * 180);
            int cx = width / 2;
            int cy = height / 2;
            for (int seg = 0; seg < 48; seg++) {
               float ang = (float) (seg * (Math.PI * 2.0) / 48.0);
               int rx = cx + Math.round(Mth.cos(ang) * shockRadius);
               int ry = cy + Math.round(Mth.sin(ang) * shockRadius);
               graphics.fill(rx - 2, ry - 2, rx + 3, ry + 3, ARGB.color(ringAlpha, 255, 200, 50));
            }
         }
         // 3. Ausblenden
         else {
            float fadeProgress = Math.clamp((sinceBlast - PEAK_TICKS - THERMAL_TICKS) / (float) FADE_TICKS, 0.0F, 1.0F);
            float remaining = (1.0F - fadeProgress) * (1.0F - fadeProgress);
            if (remaining > 0.005F) {
               graphics.fill(0, 0, width, height, ARGB.color((int) (remaining * 180), 200, 80, 20));
            }
         }

         // 4. Permanenter aschgrauer Fallout-Film
         float totalProgress = Math.clamp(sinceBlast / 40.0F, 0.0F, 1.0F);
         int falloutAlpha = Math.round(FALLOUT_ALPHA * totalProgress);
         if (falloutAlpha > 0) {
            graphics.fill(0, 0, width, height, ARGB.color(falloutAlpha, 98, 92, 85));
         }
      }
   }

   // =========================================================================
   // NukeCountdownLayer.java
   // =========================================================================
   /**
    * Taktischer DEFCON-1 Notfall-Broadcast Countdown mit nuklearem Reticle und Glitch-Effekten.
    */
   public static final class NukeCountdownLayer implements HudElement {
      private static final int URGENT_SECONDS = 4;
      private static final int HAZARD_HEIGHT = 24;
      private static final int HAZARD_STRIPE = 10;

      private static final float BASE_SCALE = 3.6F;
      private static final float URGENT_SCALE = 5.6F;

      private static final String[] STATUS_MESSAGES = {
         "ABWURF BESTÄTIGT // POSITION ERFASST",
         "KEINE DECKUNG MÖGLICH // ZONE VERLASSEN",
         "ALLE WAFFENSYSTEME GESPERRT",
         "EINSCHLAGSPUNKT: KARTENZENTRUM",
      };

      @Override
      public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
         NukeState state = NukeState.INSTANCE;
         NukePhase phase = state.phase();
         if (phase != NukePhase.FREEZE && phase != NukePhase.COUNTDOWN) {
            return;
         }

         Minecraft client = Minecraft.getInstance();
         Font font = client.font;
         float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
         float time = state.currentTick() + partialTick;
         int seconds = state.secondsToImpact();
         boolean urgent = seconds <= URGENT_SECONDS;

         float progress = Math.clamp(time / NukePhase.DETONATION.from(), 0.0F, 1.0F);
         float urgency = urgent ? Math.clamp((URGENT_SECONDS + 1 - seconds) / (float) URGENT_SECONDS, 0.0F, 1.0F) : 0.0F;

         float speed = urgent ? 10.0F : 4.0F;
         float pulse = 0.5F + 0.5F * (float) Math.sin(time * speed * Math.PI / 20.0);

         int width = graphics.guiWidth();
         int height = graphics.guiHeight();
         int centreX = width / 2;

         // 1. Hintergrund-Verdunklung & Rote Vignette
         drawVignette(graphics, width, height, progress, pulse);

         // 2. DEFCON-1 Warnbalken oben und unten
         drawDefconBars(graphics, font, width, height, urgency, time, seconds);

         // 3. Zentrales rotierendes Nuklear-Reticle
         int reticleY = height / 3 + 6;
         drawNuclearReticle(graphics, centreX, reticleY, progress, time, pulse, urgent);

         // 4. Scanlines & Kanten
         drawScanlines(graphics, width, height, time, progress);

         // 5. Glitch-Countdown-Ziffer
         drawGlitchCounter(graphics, font, width, reticleY, seconds, urgency, pulse, time);

         // 6. Statuszeile
         int statusIdx = urgent ? 1 : Math.floorMod(seconds, STATUS_MESSAGES.length);
         int statusY = reticleY + (urgent ? 46 : 38);
         int statusColor = urgent && pulse > 0.5F ? OsokWidgets.COLOR_CRIMSON : OsokWidgets.COLOR_TEXT_MUTED;
         graphics.centeredText(font, Component.literal(STATUS_MESSAGES[statusIdx]), centreX, statusY, statusColor);
      }

      /** Rote, pulsierende Rand-Vignette */
      private static void drawVignette(GuiGraphicsExtractor graphics, int width, int height, float progress, float pulse) {
         float strength = progress * progress * (0.75F + pulse * 0.25F);
         int scrimAlpha = Math.round(90 * strength);
         if (scrimAlpha > 0) {
            graphics.fill(0, 0, width, height, ARGB.color(scrimAlpha, 16, 4, 3));
         }

         for (int step = 0; step < 18; step++) {
            float falloff = 1.0F - step / 18.0F;
            int alpha = Math.round(52.0F * strength * falloff * falloff);
            if (alpha <= 0) continue;

            int colour = ARGB.color(alpha, 220, 20, 15);
            int inset = step * 4;
            graphics.fill(0, inset, width, inset + 4, colour);
            graphics.fill(0, height - inset - 4, width, height - inset, colour);
            graphics.fill(inset, 0, inset + 4, height, colour);
            graphics.fill(width - inset - 4, 0, width - inset, height, colour);
         }
      }

      /** DEFCON-1 Notfall-Broadcast Balken */
      private static void drawDefconBars(GuiGraphicsExtractor graphics, Font font, int width, int height,
                                         float urgency, float time, int seconds) {
         int barH = HAZARD_HEIGHT;
         int bgAlpha = 0xF20C0D14;

         // Oben
         graphics.fill(0, 0, width, barH, bgAlpha);
         graphics.horizontalLine(0, width - 1, barH - 1, OsokWidgets.COLOR_CRIMSON);

         // Unten
         graphics.fill(0, height - barH, width, height, bgAlpha);
         graphics.horizontalLine(0, width - 1, height - barH, OsokWidgets.COLOR_CRIMSON);

         // Animierte Schraffur an den Balkenrändern
         int shift = Math.floorMod((int) (time * 2.0F), HAZARD_STRIPE * 2);
         int stripeColor = ARGB.color(180, 255, 60, 40);
         for (int x = -HAZARD_STRIPE * 2 + shift; x < width; x += HAZARD_STRIPE * 2) {
            graphics.fill(x, barH - 3, x + HAZARD_STRIPE, barH - 1, stripeColor);
            graphics.fill(x, height - barH + 1, x + HAZARD_STRIPE, height - barH + 3, stripeColor);
         }

         // Header Telemetrie
         String headerText = "☢ DEFCON 1 // TACTICAL NUKE INBOUND // IMPACT IN " + seconds + "S ☢";
         graphics.centeredText(font, Component.literal(headerText), width / 2, 8, OsokWidgets.COLOR_GOLD);

         String footerText = "[ NOTFALL-PROTOKOLL AKTIV · EVAKUIERUNG UNMÖGLICH ]";
         graphics.centeredText(font, Component.literal(footerText), width / 2, height - barH + 8, 0xFFE2E8F0);
      }

      /** Rotierendes nukleares Ziel-Reticle im Zentrum */
      private static void drawNuclearReticle(GuiGraphicsExtractor graphics, int cx, int cy,
                                             float progress, float time, float pulse, boolean urgent) {
         int radius = urgent ? 72 : 62;
         float angleOffset = time * 0.05F;

         // Äußerer rotierender Tech-Ring
         for (int seg = 0; seg < 36; seg++) {
            if (seg % 9 == 0 || seg % 9 == 1) continue; // Schlitze
            float ang = (float) (seg * (Math.PI * 2.0) / 36.0 + angleOffset);
            int x = cx + Math.round(Mth.cos(ang) * radius);
            int y = cy + Math.round(Mth.sin(ang) * radius);
            graphics.fill(x - 1, y - 1, x + 2, y + 2, urgent ? OsokWidgets.COLOR_CRIMSON : 0xAAFF3344);
         }

         // 4 Gefahren-Ticks
         for (int i = 0; i < 4; i++) {
            float ang = (float) (i * Math.PI / 2.0 - angleOffset);
            int x0 = cx + Math.round(Mth.cos(ang) * (radius - 5));
            int y0 = cy + Math.round(Mth.sin(ang) * (radius - 5));
            int x1 = cx + Math.round(Mth.cos(ang) * (radius + 5));
            int y1 = cy + Math.round(Mth.sin(ang) * (radius + 5));
            graphics.fill(Math.min(x0, x1) - 1, Math.min(y0, y1) - 1, Math.max(x0, x1) + 2, Math.max(y0, y1) + 2, OsokWidgets.COLOR_GOLD);
         }

         // 4 Taktische Eck-Klammern
         int dist = Math.round(85 - 15 * progress);
         int brkCol = urgent ? OsokWidgets.COLOR_CRIMSON : OsokWidgets.COLOR_GOLD;
         for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
               int px = cx + sx * dist;
               int py = cy + sy * dist;
               graphics.fill(px - (sx > 0 ? 14 : 0), py, px + (sx > 0 ? 0 : 14), py + 2, brkCol);
               graphics.fill(px, py - (sy > 0 ? 14 : 0), px + 2, py + (sy > 0 ? 0 : 14), brkCol);
            }
         }
      }

      /** Ziffer mit dynamischer chromatischer Aberration (Glitch) */
      private static void drawGlitchCounter(GuiGraphicsExtractor graphics, Font font, int width, int cy,
                                            int seconds, float urgency, float pulse, float time) {
         String text = String.valueOf(seconds);
         float scale = (urgency > 0.0F ? URGENT_SCALE : BASE_SCALE) * (1.0F + pulse * (0.05F + urgency * 0.12F));
         int centreX = width / 2;

         float shake = urgency * 4.0F;
         float offsetX = (noise(time, 13) - 0.5F) * shake;
         float offsetY = (noise(time, 31) - 0.5F) * shake;
         float split = 1.4F + urgency * 4.5F;

         // Cyan / Rot Glitch-Kopien
         drawScaled(graphics, font, text, centreX + offsetX - split, cy + offsetY, scale, 0xFF00F0FF);
         drawScaled(graphics, font, text, centreX + offsetX + split, cy + offsetY, scale, 0xFFFF2233);
         drawScaled(graphics, font, text, centreX + offsetX, cy + offsetY, scale,
            pulse > 0.5F || urgency > 0.6F ? OsokWidgets.COLOR_GOLD : 0xFFFF3333);
      }

      private static void drawScaled(GuiGraphicsExtractor graphics, Font font, String text,
                                     float x, float y, float scale, int colour) {
         graphics.pose().pushMatrix();
         graphics.pose().translate(x, y - (font.lineHeight * scale) / 2.0F);
         graphics.pose().scale(scale, scale);
         graphics.centeredText(font, text, 0, 0, colour);
         graphics.pose().popMatrix();
      }

      private static void drawScanlines(GuiGraphicsExtractor graphics, int width, int height, float time, float progress) {
         int y = Math.floorMod((int) (time * 3.5F), height);
         graphics.fill(0, y, width, y + 2, ARGB.color(Math.round(45 * progress), 255, 60, 40));
      }

      private static float noise(float time, int salt) {
         float val = (float) Math.sin(time * 12.9898F + salt * 78.233F) * 43758.547F;
         return val - (float) Math.floor(val);
      }
   }

   // =========================================================================
   // NukeVictoryLayer.java
   // =========================================================================
   /**
    * Grandiose Cyber-Championship Abschlusstafel mit MVP-Hero-Showcase, Medaillen-Podium und Statistik-Kacheln.
    */
   public static final class NukeVictoryLayer implements HudElement {
      private static final int PANEL_WIDTH = 380;
      private static final int ROW_HEIGHT = 15;
      private static final int HEADER_HEIGHT = 44;
      private static final int SLIDE_TICKS = 14;
      private static final int LINE_DELAY = 3;

      private static final int COL_STREAK = 20;
      private static final int COL_RATIO = 72;
      private static final int COL_DEATHS = 124;
      private static final int COL_KILLS = 170;

      @Override
      public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
         NukeState state = NukeState.INSTANCE;
         if (!state.isRunning() || !state.showsVictoryBoard()) {
            return;
         }
         NukeVictoryPayload victory = state.victory();
         if (victory == null) {
            return;
         }

         Minecraft client = Minecraft.getInstance();
         Font font = client.font;
         float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
         float sincePhase = state.currentTick() - NukePhase.VICTORY.from() + partialTick;

         List<Line> stats = buildStats(victory);
         int rankRows = Math.min(6, victory.ranking().size());
         int panelHeight = HEADER_HEIGHT + (rankRows + 1) * ROW_HEIGHT + 14 + 40;

         int centreX = graphics.guiWidth() / 2;
         int targetY = Math.max(10, graphics.guiHeight() / 2 - panelHeight / 2 - 10);

         // Weiches Einfahren mit Dämpfung
         float slide = Math.clamp(sincePhase / SLIDE_TICKS, 0.0F, 1.0F);
         float eased = 1.0F - (1.0F - slide) * (1.0F - slide);
         int panelY = Math.round(targetY - (1.0F - eased) * (targetY + panelHeight));
         int left = centreX - PANEL_WIDTH / 2;
         int right = centreX + PANEL_WIDTH / 2;
         int bottom = panelY + panelHeight;

         // 1. Glassmorphic Championship Card Body
         graphics.fill(left + 2, bottom, right + 2, bottom + 3, 0x55000000);
         graphics.fill(right, panelY + 2, right + 3, bottom + 2, 0x55000000);
         graphics.fill(left, panelY, right, bottom, 0xF40A0D15);

         // 1px Top Highlight & Goldener Rand
         graphics.horizontalLine(left + 1, right - 2, panelY + 1, 0x44FFFFFF);
         graphics.horizontalLine(left, right - 1, panelY, OsokWidgets.COLOR_GOLD);
         graphics.horizontalLine(left, right - 1, bottom - 1, 0x88FFD700);
         graphics.verticalLine(left, panelY, bottom - 1, 0x88FFD700);
         graphics.verticalLine(right - 1, panelY, bottom - 1, 0x88FFD700);

         // 2. MVP Hero Showcase Header
         String headline = victory.isDraw()
            ? Component.translatable("hud.oneshotonekill.victory.draw").getString()
            : Component.translatable("hud.oneshotonekill.victory.header", victory.winner()).getString();
         graphics.pose().pushMatrix();
         graphics.pose().translate(centreX, panelY + 14.0F);
         graphics.pose().scale(1.3F, 1.3F);
         graphics.centeredText(font, headline, 0, 0, OsokWidgets.COLOR_GOLD);
         graphics.pose().popMatrix();

         graphics.centeredText(font, Component.literal(victory.reason()), centreX, panelY + 28, OsokWidgets.COLOR_TEXT_MUTED);

         // 3. Rangliste / Podium
         int y = panelY + HEADER_HEIGHT;
         int shown = 0;

         // Spalten-Header
         graphics.text(font, Component.translatable("hud.oneshotonekill.victory.col_player").getString(), left + 14, y, OsokWidgets.COLOR_TEXT_MUTED);
         right(graphics, font, Component.translatable("hud.oneshotonekill.victory.col_kills").getString(), right - COL_KILLS, y, OsokWidgets.COLOR_TEXT_MUTED);
         right(graphics, font, Component.translatable("hud.oneshotonekill.victory.col_deaths").getString(), right - COL_DEATHS, y, OsokWidgets.COLOR_TEXT_MUTED);
         right(graphics, font, Component.translatable("hud.oneshotonekill.victory.col_ratio").getString(), right - COL_RATIO, y, OsokWidgets.COLOR_TEXT_MUTED);
         right(graphics, font, Component.translatable("hud.oneshotonekill.victory.col_streak").getString(), right - COL_STREAK, y, OsokWidgets.COLOR_TEXT_MUTED);
         y += ROW_HEIGHT;
         graphics.fill(left + 14, y - 3, right - 14, y - 2, 0x33FFD700);

         for (int index = 0; index < rankRows; index++) {
            if (sincePhase < SLIDE_TICKS + shown * LINE_DELAY) {
               return;
            }
            shown++;
            NukeVictoryPayload.Row row = victory.ranking().get(index);

            int nameColor;
            String medalPrefix;
            if (index == 0) {
               medalPrefix = "🥇 ";
               nameColor = OsokWidgets.COLOR_GOLD;
               // Goldene Zeilen-Hinterlegung
               graphics.fill(left + 10, y - 2, right - 10, y + ROW_HEIGHT - 3, 0x22FFD700);
               graphics.fill(left + 10, y - 2, left + 13, y + ROW_HEIGHT - 3, OsokWidgets.COLOR_GOLD);
            } else if (index == 1) {
               medalPrefix = "🥈 ";
               nameColor = 0xFFE2E8F0;
            } else if (index == 2) {
               medalPrefix = "🥉 ";
               nameColor = 0xFFCD7F32;
            } else {
               medalPrefix = (index + 1) + ". ";
               nameColor = OsokWidgets.COLOR_TEXT_WHITE;
            }

            graphics.text(font, medalPrefix + row.name(), left + 14, y, nameColor);
            right(graphics, font, String.valueOf(row.kills()), right - COL_KILLS, y, OsokWidgets.COLOR_EMERALD);
            right(graphics, font, String.valueOf(row.deaths()), right - COL_DEATHS, y, OsokWidgets.COLOR_CRIMSON);
            right(graphics, font, row.ratio(), right - COL_RATIO, y, OsokWidgets.COLOR_TEXT_WHITE);
            right(graphics, font, "★" + row.bestStreak(), right - COL_STREAK, y, OsokWidgets.COLOR_GOLD);
            y += ROW_HEIGHT;
         }

         // 4. Match-Statistik-Kacheln unten (2x2 Grid)
         y += 6;
         graphics.fill(left + 14, y - 4, right - 14, y - 3, 0x33FFD700);
         drawStatsGrid(graphics, font, left + 14, right - 14, y, stats, sincePhase - SLIDE_TICKS - shown * LINE_DELAY);
      }

      private static void drawStatsGrid(GuiGraphicsExtractor graphics, Font font, int left, int right, int y,
                                        List<Line> stats, float phaseProgress) {
         if (phaseProgress < 0.0F) return;

         int totalWidth = right - left;
         int halfWidth = totalWidth / 2 - 4;
         int col2Left = left + totalWidth / 2 + 4;

         for (int i = 0; i < Math.min(4, stats.size()); i++) {
            Line line = stats.get(i);
            int rx = (i % 2 == 0) ? left : col2Left;
            int ry = y + (i / 2) * 14;

            graphics.text(font, line.label + ":", rx, ry, OsokWidgets.COLOR_TEXT_MUTED);
            graphics.text(font, line.value, rx + halfWidth - font.width(line.value), ry, line.colour);
         }
      }

      private static void right(GuiGraphicsExtractor graphics, Font font, String text, int edge, int y, int colour) {
         graphics.text(font, text, edge - font.width(text), y, colour);
      }

      private static List<Line> buildStats(NukeVictoryPayload victory) {
         List<Line> lines = new ArrayList<>();
         lines.add(new Line(Component.translatable("hud.oneshotonekill.victory.duration").getString(), duration(victory.matchSeconds()), OsokWidgets.COLOR_TEXT_WHITE));
         lines.add(new Line(Component.translatable("hud.oneshotonekill.victory.total_kills").getString(), String.valueOf(victory.totalKills()), OsokWidgets.COLOR_EMERALD));
         lines.add(new Line(Component.translatable("hud.oneshotonekill.victory.kpm").getString(), String.format(Locale.ROOT, "%.1f", victory.killsPerMinute()), OsokWidgets.COLOR_TEXT_WHITE));
         if (!victory.mvp().isEmpty()) {
            lines.add(new Line(Component.translatable("hud.oneshotonekill.victory.longest_streak").getString(), victory.mvp() + " (★" + victory.mvpStreak() + ")", OsokWidgets.COLOR_GOLD));
         } else {
            lines.add(new Line(Component.translatable("hud.oneshotonekill.victory.total_deaths").getString(), String.valueOf(victory.totalDeaths()), OsokWidgets.COLOR_CRIMSON));
         }
         return lines;
      }

      private static String duration(int seconds) {
         return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
      }

      private record Line(String label, String value, int colour) {
      }
   }
}

