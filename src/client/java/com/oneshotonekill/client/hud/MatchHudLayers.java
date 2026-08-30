package com.oneshotonekill.client.hud;

import com.oneshotonekill.client.screen.OsokWidgets;
import com.oneshotonekill.client.state.ClientStates.*;
import static com.oneshotonekill.client.state.ClientStates.*;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

/**
 * Modernes AAA Cyber-Esports HUD-Intro für Match-Start & Countdown.
 */
@SuppressWarnings({"DuplicateBranchesInSwitch", "NullableProblems", "UnnecessaryLocalVariable"})
public final class MatchHudLayers {
   private MatchHudLayers() {}

   // =========================================================================
   // MatchCountdownLayer.java
   // =========================================================================
   /**
    * Spektakuläres Rundenstart-Intro:
    * - Filmische Letterbox-Balken mit Telemetrie-Headern.
    * - Gegenläufig rotierendes Multi-Ring Tech-HUD mit Grad-Markierungen.
    * - 3-Stufen Farbaufbau (Cyan -> Gold -> Crimson) mit synchronen Schockwellen & Audio-Pitch.
    * - Kinetischer Startschuss mit Flash, Speedlines, "MATCH START" Banner und Screenshake.
    */
   public static final class MatchCountdownLayer implements HudElement {
      /** Von Sekunde 3 bis 1: Cyan (Init), Gold (Armed), Crimson (Engage) */
      private static final int[] SECOND_COLORS = {
         OsokWidgets.COLOR_CRIMSON, // Sekunde 1 (Hot/Engage)
         OsokWidgets.COLOR_GOLD,    // Sekunde 2 (Armed)
         OsokWidgets.COLOR_CYAN     // Sekunde 3 (Init)
      };

      private static final String[] PHASE_SUBTITLES = {
         "PREPARE FOR ENGAGEMENT",
         "TARGETS ACQUIRED // WEAPONS READY",
         "INITIALIZING COMBAT PROTOCOL"
      };

      private static final float[] SECOND_PITCHES = { 1.4F, 1.05F, 0.85F };

      private static final int DIM = 0x44FFFFFF;
      private static final int SCRIM = 0x55000000;
      private static final int GHOST_ALPHA = 0x1A;

      private static final int OUTER_TECH_RADIUS = 76;
      private static final int PROGRESS_RING_RADIUS = 60;
      private static final int SWEEP_RADIUS = 46;
      private static final int RING_SEGMENTS = 64;
      private static final int SWEEP_LENGTH = 12;

      private static final int DIGIT_HEIGHT = 52;
      private static final int DIGIT_WIDTH = 30;
      private static final int DIGIT_THICKNESS = 6;
      private static final float PUNCH = 0.38F;

      private static final int BRACKET_ARM = 18;
      private static final int LETTERBOX_HEIGHT = 26;

      private static int lastBeepSecond = -1;

      private static final int[] SEGMENTS = {
         0b0111111, 0b0000110, 0b1011011, 0b1001111, 0b1100110,
         0b1101101, 0b1111101, 0b0000111, 0b1111111, 0b1101111,
      };

      @Override
      public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
         MatchStartState state = MatchStartState.INSTANCE;
         float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
         int centreX = graphics.guiWidth() / 2;
         int centreY = graphics.guiHeight() / 2;
         int width = graphics.guiWidth();
         int height = graphics.guiHeight();

         float go = state.getGoProgress(partialTick);
         if (go > 0.0F) {
            drawGoScreen(graphics, Minecraft.getInstance().font, centreX, centreY, width, height, go);
            lastBeepSecond = -1;
            return;
         }

         if (!state.isCountdownActive()) {
            lastBeepSecond = -1;
            return;
         }

         float remaining = state.getRemainingTicks(partialTick);
         int second = Math.clamp(Mth.ceil(remaining / 20.0F), 1, 3);
         float withinSecond = 1.0F - (remaining % 20.0F) / 20.0F;
         float overall = 1.0F - remaining / MatchStartState.COUNTDOWN_TICKS;
         int accent = SECOND_COLORS[second - 1];

         // Audio-Beep bei Sekundenwechsel
         if (second != lastBeepSecond) {
            lastBeepSecond = second;
            Minecraft.getInstance().getSoundManager().play(
               SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, SECOND_PITCHES[second - 1])
            );
         }

         // 1. Hintergrund-Verdunklung & Filmische Letterbox-Balken
         graphics.fill(0, 0, width, height, SCRIM);
         drawLetterbox(graphics, Minecraft.getInstance().font, width, height, overall, accent);

         // 2. Weicher Hintergrundschein (Radial Glow)
         drawRadialGlow(graphics, centreX, centreY, accent);

         // 3. Taktisches Tech-HUD (Gegenläufige Ringe, Sweep & Klammern)
         drawTechRings(graphics, centreX, centreY, remaining, overall, withinSecond, accent);

         // 4. Stoßwellen-Puls bei jedem Sekundenwechsel
         drawShockwave(graphics, centreX, centreY, withinSecond, accent);

         // 5. 7-Segment Ziffer mit Punch & Ghosting
         drawDigit(graphics, centreX, centreY, second, withinSecond, accent);

         // 6. Taktische Beschriftungen
         drawLabels(graphics, Minecraft.getInstance().font, centreX, centreY, second, accent);
      }

      /** Filmische Widescreen-Letterbox Balken oben und unten mit Telemetrie */
      private static void drawLetterbox(GuiGraphicsExtractor graphics, Font font, int width, int height, float overall, int accent) {
         float introProgress = Math.min(1.0F, overall * 4.0F);
         int barH = Math.round(LETTERBOX_HEIGHT * introProgress);
         if (barH <= 0) return;

         // Oberer Balken
         graphics.fill(0, 0, width, barH, 0xF2080A10);
         graphics.horizontalLine(0, width - 1, barH - 1, (accent & 0x00FFFFFF) | 0x99000000);
         graphics.fill(0, barH, width, barH + 1, 0x40000000);

         // Unterer Balken
         graphics.fill(0, height - barH, width, height, 0xF2080A10);
         graphics.horizontalLine(0, width - 1, height - barH, (accent & 0x00FFFFFF) | 0x99000000);
         graphics.fill(0, height - barH - 1, width, height - barH, 0x40000000);

         // Telemetrie im oberen Balken
         if (barH >= LETTERBOX_HEIGHT - 4) {
            graphics.text(font, "⚡ PROTOCOL: OSOK // ARENA COMBAT", 14, 9, 0xFF94A3B8);
            String rightTag = "SYS.STATUS: ARMED";
            graphics.text(font, rightTag, width - 14 - font.width(rightTag), 9, accent);

            // Text im unteren Balken
            String bottomMsg = "[ BEWEGUNG GESPERRT · WAFFEN-SCHARFMACHUNG ]";
            graphics.centeredText(font, Component.literal(bottomMsg), width / 2, height - barH + 9, 0xFFE2E8F0);
         }
      }

      /** Weicher radialer Leuchtschein hinter dem HUD */
      private static void drawRadialGlow(GuiGraphicsExtractor graphics, int centreX, int centreY, int accent) {
         for (int step = 4; step >= 1; step--) {
            int radius = 24 + step * 14;
            int colour = withAlpha(accent, 8);
            for (int offset = -radius; offset <= radius; offset += 3) {
               int halfWidth = (int) Math.sqrt((double) radius * radius - (double) offset * offset);
               graphics.fill(centreX - halfWidth, centreY + offset, centreX + halfWidth, centreY + offset + 3, colour);
            }
         }
      }

      /** Gegenläufig rotierende Ringe, Grad-Ticks und Zielfokus-Klammern */
      private static void drawTechRings(GuiGraphicsExtractor graphics, int centreX, int centreY,
                                        float remainingTicks, float overall, float withinSecond, int accent) {
         // A. Äußerer Tech-Kompass-Ring (rotiert gegenläufig)
         float techAngleOffset = -remainingTicks * 0.04F;
         for (int segment = 0; segment < 48; segment++) {
            // 4 Lücken für Tech-Optik
            if (segment % 12 == 0 || segment % 12 == 1) continue;

            float angle = (float) (segment * (Math.PI * 2.0) / 48.0 + techAngleOffset);
            int x = centreX + Math.round(Mth.cos(angle) * OUTER_TECH_RADIUS);
            int y = centreY + Math.round(Mth.sin(angle) * OUTER_TECH_RADIUS);
            graphics.fill(x - 1, y - 1, x + 2, y + 2, (accent & 0x00FFFFFF) | 0x77000000);
         }

         // 4 Haupt-Grad-Ticks auf dem Außenring
         for (int i = 0; i < 4; i++) {
            float angle = (float) (i * Math.PI / 2.0 + techAngleOffset);
            int x0 = centreX + Math.round(Mth.cos(angle) * (OUTER_TECH_RADIUS - 4));
            int y0 = centreY + Math.round(Mth.sin(angle) * (OUTER_TECH_RADIUS - 4));
            int x1 = centreX + Math.round(Mth.cos(angle) * (OUTER_TECH_RADIUS + 4));
            int y1 = centreY + Math.round(Mth.sin(angle) * (OUTER_TECH_RADIUS + 4));
            graphics.fill(Math.min(x0, x1) - 1, Math.min(y0, y1) - 1, Math.max(x0, x1) + 2, Math.max(y0, y1) + 2, accent);
         }

         // B. Mittlerer 3-Sekunden-Fortschrittsring
         int usedSegments = Math.round(RING_SEGMENTS * Mth.clamp(overall, 0.0F, 1.0F));
         for (int segment = 0; segment < RING_SEGMENTS; segment++) {
            float angle = (float) (segment * (Math.PI * 2.0) / RING_SEGMENTS - Math.PI / 2.0);
            int x = centreX + Math.round(Mth.cos(angle) * PROGRESS_RING_RADIUS);
            int y = centreY + Math.round(Mth.sin(angle) * PROGRESS_RING_RADIUS);
            boolean spent = segment < usedSegments;
            int size = spent ? 1 : 2;
            graphics.fill(x - size / 2, y - size / 2, x + size / 2 + 1, y + size / 2 + 1, spent ? DIM : accent);
         }

         // C. Innerer Ladering (läuft je Sekunde einmal herum mit Leuchtschweif)
         for (int trail = 0; trail < SWEEP_LENGTH; trail++) {
            float angle = (float) ((withinSecond - trail * 0.015F) * Math.PI * 2.0 - Math.PI / 2.0);
            int x = centreX + Math.round(Mth.cos(angle) * SWEEP_RADIUS);
            int y = centreY + Math.round(Mth.sin(angle) * SWEEP_RADIUS);
            int alpha = Math.max(0, 255 - trail * 20);
            graphics.fill(x - 1, y - 1, x + 2, y + 2, withAlpha(accent, alpha));
         }

         // D. 4 Taktische Fokus-Klammern
         int distance = Math.round(100 - 22 * Mth.clamp(overall, 0.0F, 1.0F));
         int bracketColor = withAlpha(accent, 0xDD);
         for (int side = -1; side <= 1; side += 2) {
            for (int vertical = -1; vertical <= 1; vertical += 2) {
               int x = centreX + side * distance;
               int y = centreY + vertical * distance;
               graphics.fill(x - (side > 0 ? BRACKET_ARM : 0), y, x + (side > 0 ? 0 : BRACKET_ARM), y + 2, bracketColor);
               graphics.fill(x, y - (vertical > 0 ? BRACKET_ARM : 0), x + 2, y + (vertical > 0 ? 0 : BRACKET_ARM), bracketColor);
            }
         }
      }

      /** Stoßwellen-Puls bei jedem Sekundenwechsel */
      private static void drawShockwave(GuiGraphicsExtractor graphics, int centreX, int centreY, float withinSecond, int accent) {
         if (withinSecond > 0.35F) return;

         float shockProgress = withinSecond / 0.35F; // 0.0 bis 1.0
         int radius = Math.round(30 + shockProgress * 130);
         int alpha = (int) ((1.0F - shockProgress) * 220);
         int shockColor = withAlpha(accent, alpha);

         for (int seg = 0; seg < 40; seg++) {
            float angle = (float) (seg * (Math.PI * 2.0) / 40.0);
            int x = centreX + Math.round(Mth.cos(angle) * radius);
            int y = centreY + Math.round(Mth.sin(angle) * radius);
            graphics.fill(x - 1, y - 1, x + 2, y + 2, shockColor);
         }
      }

      /** Ziffer als Segmentanzeige mit dynamischem Punch */
      private static void drawDigit(GuiGraphicsExtractor graphics, int centreX, int centreY,
                                    int digit, float withinSecond, int accent) {
         float punch = 1.0F + PUNCH * Math.max(0.0F, 1.0F - withinSecond * 3.2F);
         int height = Math.round(DIGIT_HEIGHT * punch);
         int width = Math.round(DIGIT_WIDTH * punch);
         int thickness = Math.max(3, Math.round(DIGIT_THICKNESS * punch));

         int left = centreX - width / 2;
         int top = centreY - height / 2;
         int right = left + width;
         int bottom = top + height;
         int middle = centreY;
         int half = thickness / 2;
         int mask = SEGMENTS[Mth.clamp(digit, 0, 9)];

         for (int segment = 0; segment < 7; segment++) {
            boolean lit = (mask & (1 << segment)) != 0;
            int colour = lit ? accent : withAlpha(accent, GHOST_ALPHA);
            switch (segment) {
               case 0 -> graphics.fill(left + thickness, top, right - thickness, top + thickness, colour);
               case 1 -> graphics.fill(right - thickness, top + thickness, right, middle - half, colour);
               case 2 -> graphics.fill(right - thickness, middle + half, right, bottom - thickness, colour);
               case 3 -> graphics.fill(left + thickness, bottom - thickness, right - thickness, bottom, colour);
               case 4 -> graphics.fill(left, middle + half, left + thickness, bottom - thickness, colour);
               case 5 -> graphics.fill(left, top + thickness, left + thickness, middle - half, colour);
               default -> graphics.fill(left + thickness, middle - half, right - thickness, middle + half, colour);
            }
         }
      }

      private static void drawLabels(GuiGraphicsExtractor graphics, Font font, int centreX, int centreY, int second, int accent) {
         String title = "MATCH COUNTDOWN";
         graphics.centeredText(font, Component.literal(title), centreX, centreY - OUTER_TECH_RADIUS - 16, accent);

         String sub = PHASE_SUBTITLES[Math.min(PHASE_SUBTITLES.length - 1, second - 1)];
         graphics.centeredText(font, Component.literal(sub), centreX, centreY + OUTER_TECH_RADIUS + 14, 0xFFF1F5F9);
      }

      /** Kinetischer Startschuss mit Flash, Speedlines und großem "MATCH START" Banner */
      private static void drawGoScreen(GuiGraphicsExtractor graphics, Font font, int centreX, int centreY,
                                       int width, int height, float goProgress) {
         // 1. Weiß-Goldener Flash
         int flashAlpha = (int) (goProgress * 210);
         graphics.fill(0, 0, width, height, flashAlpha << 24 | 0x00FFFFFF);

         // 2. Kinetische Speedlines
         int lineAlpha = (int) (goProgress * 240);
         int lineCol = lineAlpha << 24 | 0x00FFD700;
         float expand = (1.0F - goProgress);

         for (int i = 0; i < 16; i++) {
            float angle = (float) (i * Math.PI / 8.0);
            int innerDist = Math.round(50 + expand * 160);
            int outerDist = Math.round(innerDist + 40 + goProgress * 60);

            int x0 = centreX + Math.round(Mth.cos(angle) * innerDist);
            int y0 = centreY + Math.round(Mth.sin(angle) * innerDist);
            int x1 = centreX + Math.round(Mth.cos(angle) * outerDist);
            int y1 = centreY + Math.round(Mth.sin(angle) * outerDist);

            graphics.fill(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1) + 2, Math.max(y0, y1) + 2, lineCol);
         }

         // 3. Expandierender Druckwellen-Ring
         int shockRadius = Math.round(30 + expand * 240);
         for (int seg = 0; seg < RING_SEGMENTS; seg++) {
            float angle = (float) (seg * (Math.PI * 2.0) / RING_SEGMENTS);
            int x = centreX + Math.round(Mth.cos(angle) * shockRadius);
            int y = centreY + Math.round(Mth.sin(angle) * shockRadius);
            graphics.fill(x - 1, y - 1, x + 2, y + 2, lineAlpha << 24 | 0x0000F0FF);
         }

         // 4. Zentrales "MATCH START" Banner
         int bannerAlpha = Math.min(255, (int) (goProgress * 280));
         int goldText = bannerAlpha << 24 | 0x00FFD700;
         int whiteText = bannerAlpha << 24 | 0x00FFFFFF;

         // Doppelter Schatten für plastischen Look
         graphics.centeredText(font, Component.literal("⚔ MATCH START ⚔"), centreX + 1, centreY - 11, 0xDD000000);
         graphics.centeredText(font, Component.literal("⚔ MATCH START ⚔"), centreX, centreY - 12, goldText);

         graphics.centeredText(font, Component.literal("FEUER FREI!"), centreX + 1, centreY + 4, 0xDD000000);
         graphics.centeredText(font, Component.literal("FEUER FREI!"), centreX, centreY + 3, whiteText);
      }

      private static int withAlpha(int colour, int alpha) {
         return Mth.clamp(alpha, 0, 255) << 24 | colour & 0x00FFFFFF;
      }
   }

   // =========================================================================
   // MatchStartOverlayLayer.java
   // =========================================================================
   /**
    * Die Bildschirmeffekte beim Match-Start: Portalwirbel und Verzerrungswelle.
    */
   public static final class MatchStartOverlayLayer implements HudElement {
      private static final float PORTAL_FADE = 0.02F;
      private static final float CONFUSION_FADE = 0.025F;

      @Override
      public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
         Minecraft client = Minecraft.getInstance();
         MatchStartState state = MatchStartState.INSTANCE;

         float portal = state.getPortalIntensity();
         if (portal > 0.0F) {
            client.gui.hud.extractPortalOverlay(graphics, portal * 0.9F);
            state.setPortalIntensity(Math.max(0.0F, portal - PORTAL_FADE));
         }

         float confusion = state.getConfusionIntensity();
         if (confusion > 0.0F) {
            client.gui.hud.extractConfusionOverlay(graphics, confusion * 0.5F);
            state.setConfusionIntensity(Math.max(0.0F, confusion - CONFUSION_FADE));
         }
      }
   }

   // =========================================================================
   // MatchBannerLayer.java
   // =========================================================================
   /**
    * Rendert animierte Cyber-Status-Banner (Pause, Resume, Stop, Map-Reset)
    * und die persistente Status-Leiste während eines pausierten Matches.
    */
   public static final class MatchBannerLayer implements HudElement {
      private static final int BANNER_WIDTH = 380;
      private static final int BANNER_HEIGHT = 48;
      private static final int BRACKET_SIZE = 10;

      @Override
      public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
         Minecraft client = Minecraft.getInstance();
         MatchBannerState state = MatchBannerState.INSTANCE;
         Font font = client.font;
         int screenWidth = graphics.guiWidth();
         int screenHeight = graphics.guiHeight();
         float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);

         // 1. Persistente HUD-Pausenleiste oben
         if (state.isMatchPaused() && client.gui.screen() == null) {
            String pauseChip = "⏸ MATCH PAUSIERT // WAFFEN INAKTIV";
            int chipWidth = font.width(pauseChip) + 18;
            int chipX = screenWidth / 2 - chipWidth / 2;
            OsokWidgets.statusBadge(graphics, font, chipX, 8, pauseChip, OsokWidgets.COLOR_GOLD, true);
         }

         // 2. Animiertes Status-Banner
         if (!state.isBannerActive()) {
            return;
         }

         float progress = state.getProgress(partialTick);
         // Einblenden in den ersten 15%, Ausblenden in den letzten 15%
         float alphaFactor = Math.min(1.0F, Math.min((1.0F - progress) * 6.6F, progress * 6.6F));
         if (alphaFactor <= 0.01F) {
            return;
         }

         int centreX = screenWidth / 2;
         int bannerY = Math.max(14, (int) (screenHeight * 0.05F));
         int left = centreX - BANNER_WIDTH / 2;
         int right = left + BANNER_WIDTH;
         int bottom = bannerY + BANNER_HEIGHT;

         int accent = state.getAccentColor();
         int alphaInt = (int) (alphaFactor * 255);

         // Äußerer Randschatten
         graphics.fill(left + 2, bottom, right + 2, bottom + 3, (int) (alphaFactor * 60) << 24);
         graphics.fill(right, bannerY + 2, right + 3, bottom + 2, (int) (alphaFactor * 60) << 24);

         // Glassmorphic Body
         int bgAlpha = (int) (alphaFactor * 242);
         graphics.fill(left, bannerY, right, bottom, bgAlpha << 24 | 0x000A0D15);

         // Innere Glanzkante oben
         int lightAlpha = (int) (alphaFactor * 40);
         graphics.horizontalLine(left + 1, right - 2, bannerY + 1, lightAlpha << 24 | 0x00FFFFFF);

         // Leuchtender Rand
         int borderAlpha = (int) (alphaFactor * 180);
         int borderColor = (accent & 0x00FFFFFF) | (borderAlpha << 24);
         graphics.horizontalLine(left, right - 1, bannerY, borderColor);
         graphics.horizontalLine(left, right - 1, bottom - 1, borderColor);
         graphics.verticalLine(left, bannerY, bottom - 1, borderColor);
         graphics.verticalLine(right - 1, bannerY, bottom - 1, borderColor);

         // Akzent-Indikatorstreifen links & rechts
         int stripAlpha = (int) (alphaFactor * 230);
         int stripColor = (accent & 0x00FFFFFF) | (stripAlpha << 24);
         graphics.fill(left + 2, bannerY + 3, left + 6, bottom - 3, stripColor);
         graphics.fill(right - 6, bannerY + 3, right - 2, bottom - 3, stripColor);

         // Zarter Sweep-Lichtstreifen über das Banner
         float streakRatio = ((1.0F - progress) * 2.2F) % 1.0F;
         int streakX = left + (int) (streakRatio * (BANNER_WIDTH - 24));
         int streakAlpha = (int) (alphaFactor * 30);
         graphics.fill(streakX, bannerY + 1, streakX + 24, bottom - 1, (accent & 0x00FFFFFF) | (streakAlpha << 24));

         // 4 Taktische Eck-Klammern
         int bracketDist = 4;
         int brkAlpha = (int) (alphaFactor * 200);
         int brkColor = (accent & 0x00FFFFFF) | (brkAlpha << 24);

         // Oben Links
         graphics.fill(left - bracketDist - BRACKET_SIZE, bannerY - bracketDist, left - bracketDist, bannerY - bracketDist + 2, brkColor);
         graphics.fill(left - bracketDist, bannerY - bracketDist, left - bracketDist + 2, bannerY - bracketDist + BRACKET_SIZE, brkColor);
         // Oben Rechts
         graphics.fill(right + bracketDist, bannerY - bracketDist, right + bracketDist + BRACKET_SIZE, bannerY - bracketDist + 2, brkColor);
         graphics.fill(right + bracketDist - 2, bannerY - bracketDist, right + bracketDist, bannerY - bracketDist + BRACKET_SIZE, brkColor);
         // Unten Links
         graphics.fill(left - bracketDist - BRACKET_SIZE, bottom + bracketDist - 2, left - bracketDist, bottom + bracketDist, brkColor);
         graphics.fill(left - bracketDist, bottom + bracketDist - BRACKET_SIZE, left - bracketDist + 2, bottom + bracketDist, brkColor);
         // Unten Rechts
         graphics.fill(right + bracketDist, bottom + bracketDist - 2, right + bracketDist + BRACKET_SIZE, bottom + bracketDist, brkColor);
         graphics.fill(right + bracketDist - 2, bottom + bracketDist - BRACKET_SIZE, right + bracketDist, bottom + bracketDist, brkColor);

         // Titel & Subtitel
         int titleColor = (accent & 0x00FFFFFF) | (alphaInt << 24);
         int subColor = (OsokWidgets.COLOR_TEXT_WHITE & 0x00FFFFFF) | ((int) (alphaFactor * 220) << 24);

         graphics.centeredText(font, Component.literal(state.getTitle()), centreX + 1, bannerY + 11, (int) (alphaFactor * 180) << 24);
         graphics.centeredText(font, Component.literal(state.getTitle()), centreX, bannerY + 10, titleColor);

         if (!state.getSubtitle().isEmpty()) {
            graphics.centeredText(font, Component.literal(state.getSubtitle()), centreX + 1, bannerY + 27, (int) (alphaFactor * 150) << 24);
            graphics.centeredText(font, Component.literal(state.getSubtitle()), centreX, bannerY + 26, subColor);
         }
      }
   }

   // =========================================================================
   // GunGameHudLayer.java
   // =========================================================================
   /**
    * Rendert das Cyber-HUD für das Waffenspiel:
    * - Permanentes Waffen-/Stufen-Abzeichen (oben links) mit Kills, Pips und Next-Weapon-Indikator.
    * - Kinetisches Level-Up Banner mit Schockwellen & Lichtstreifen beim Aufstieg.
    */
   public static final class GunGameHudLayer implements HudElement {
      private static final int BADGE_WIDTH = 190;
      private static final int BADGE_HEIGHT = 38;

      @Override
      public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
         Minecraft client = Minecraft.getInstance();
         if (client.player == null || client.gui.screen() != null) {
            return;
         }

         GunGameHudState state = GunGameHudState.INSTANCE;
         if (!state.isActive()) {
            return;
         }

         Font font = client.font;
         int screenWidth = graphics.guiWidth();
         int screenHeight = graphics.guiHeight();
         float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);

         int accent = getTierColor(state.getCurrentTier());

         // 1. Permanentes Cyber-Stufen-Badge oben links
         int badgeX = 14;
         int badgeY = 14;

         // Glassmorphic Card
         graphics.fill(badgeX, badgeY, badgeX + BADGE_WIDTH, badgeY + BADGE_HEIGHT, 0xDD080A10);
         // Border
         graphics.horizontalLine(badgeX, badgeX + BADGE_WIDTH - 1, badgeY, (accent & 0x00FFFFFF) | 0x99000000);
         graphics.horizontalLine(badgeX, badgeX + BADGE_WIDTH - 1, badgeY + BADGE_HEIGHT - 1, (accent & 0x00FFFFFF) | 0x44000000);
         graphics.verticalLine(badgeX, badgeY, badgeY + BADGE_HEIGHT - 1, (accent & 0x00FFFFFF) | 0x99000000);
         graphics.verticalLine(badgeX + BADGE_WIDTH - 1, badgeY, badgeY + BADGE_HEIGHT - 1, (accent & 0x00FFFFFF) | 0x44000000);
         // Akzent-Streifen links
         graphics.fill(badgeX + 2, badgeY + 2, badgeX + 5, badgeY + BADGE_HEIGHT - 2, accent);

         // Stufe & Name
         String tierTitle = "⚡ STUFE " + state.getCurrentTier() + "/" + state.getTotalTiers();
         graphics.text(font, tierTitle, badgeX + 10, badgeY + 6, accent);
         graphics.text(font, state.getTierName(), badgeX + 10, badgeY + 17, 0xFFFFFFFF);

         // Kill Dots & Progress
         int dotsX = badgeX + BADGE_WIDTH - 10;
         int currentKills = state.getTierKills();
         int requiredKills = state.getRequiredKills();
         for (int i = requiredKills - 1; i >= 0; i--) {
            int dotCol = i < currentKills ? 0xFF00E676 : 0xFF475569;
            graphics.fill(dotsX - 8, badgeY + 8, dotsX, badgeY + 16, dotCol);
            dotsX -= 12;
         }

         // Fortschrittsbalken unten am Badge
         float progress = Math.min(1.0F, (float) currentKills / (float) requiredKills);
         int barWidth = Math.round((BADGE_WIDTH - 10) * progress);
         if (barWidth > 0) {
            graphics.fill(badgeX + 6, badgeY + BADGE_HEIGHT - 3, badgeX + 6 + barWidth, badgeY + BADGE_HEIGHT - 1, accent);
         }

         // 2. Kinetisches Level-Up Banner beim Stufenaufstieg
         float levelUpProgress = state.getLevelUpProgress(partialTick);
         if (levelUpProgress > 0.0F) {
            drawLevelUpBanner(graphics, font, screenWidth, screenHeight, state, levelUpProgress, accent);
         }
      }

      private static void drawLevelUpBanner(GuiGraphicsExtractor graphics, Font font, int screenWidth, int screenHeight,
                                            GunGameHudState state, float progress, int accent) {
         float alphaFactor = Math.min(1.0F, Math.min((1.0F - progress) * 5.0F, progress * 4.0F));
         if (alphaFactor <= 0.01F) return;

         int centreX = screenWidth / 2;
         int bannerY = (int) (screenHeight * 0.18F);
         int bannerW = 340;
         int bannerH = 44;
         int left = centreX - bannerW / 2;
         int right = left + bannerW;
         int bottom = bannerY + bannerH;

         int alphaInt = (int) (alphaFactor * 255);
         int bgAlpha = (int) (alphaFactor * 230);
         graphics.fill(left, bannerY, right, bottom, bgAlpha << 24 | 0x000A0D15);

         int borderColor = (accent & 0x00FFFFFF) | ((int) (alphaFactor * 200) << 24);
         graphics.horizontalLine(left, right - 1, bannerY, borderColor);
         graphics.horizontalLine(left, right - 1, bottom - 1, borderColor);
         graphics.verticalLine(left, bannerY, bottom - 1, borderColor);
         graphics.verticalLine(right - 1, bannerY, bottom - 1, borderColor);

         // Taktische Akzentstreifen
         graphics.fill(left + 2, bannerY + 2, left + 5, bottom - 2, (accent & 0x00FFFFFF) | (alphaInt << 24));
         graphics.fill(right - 5, bannerY + 2, right - 2, bottom - 2, (accent & 0x00FFFFFF) | (alphaInt << 24));

         String title = "▲ AUFGESTIEGEN: STUFE " + state.getCurrentTier() + " ▲";
         String subtitle = "Waffe freigeschaltet: " + state.getTierName();

         graphics.centeredText(font, Component.literal(title), centreX + 1, bannerY + 9, (int) (alphaFactor * 160) << 24);
         graphics.centeredText(font, Component.literal(title), centreX, bannerY + 8, (accent & 0x00FFFFFF) | (alphaInt << 24));

         graphics.centeredText(font, Component.literal(subtitle), centreX + 1, bannerY + 24, (int) (alphaFactor * 140) << 24);
         graphics.centeredText(font, Component.literal(subtitle), centreX, bannerY + 23, (OsokWidgets.COLOR_TEXT_WHITE & 0x00FFFFFF) | (alphaInt << 24));
      }

      private static int getTierColor(int tier) {
         return switch (tier) {
            case 1 -> OsokWidgets.COLOR_GOLD;
            case 2 -> OsokWidgets.COLOR_CRIMSON;
            case 3 -> OsokWidgets.COLOR_GOLD;
            case 4 -> OsokWidgets.COLOR_CYAN;
            case 5 -> 0xFF2979FF;
            case 6 -> OsokWidgets.COLOR_GOLD;
            case 7 -> 0xFFBD00FF;
            case 8 -> OsokWidgets.COLOR_GOLD;
            case 9 -> OsokWidgets.COLOR_CYAN;
            case 10 -> 0xFF00E676;
            case 11 -> 0xFF00BCD4;
            case 12 -> 0xFF9B5CFF;
            case 13 -> OsokWidgets.COLOR_GOLD;
            default -> OsokWidgets.COLOR_CYAN;
         };
      }
   }
}

