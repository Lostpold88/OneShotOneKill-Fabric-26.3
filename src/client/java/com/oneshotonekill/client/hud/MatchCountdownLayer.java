package com.oneshotonekill.client.hud;

import static com.oneshotonekill.client.state.ClientStates.MatchStartState;

import com.oneshotonekill.client.screen.OsokWidgets;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

/**
 * Die Kino-Sequenz vor jedem Match.
 * <p>
 * Ablauf (siehe {@link MatchStartState}):
 * <ol>
 *   <li><b>Titelkarte</b>: "ONE SHOT" und "ONE KILL" stürmen von beiden Seiten herein, rasten mit RGB-Split ein,
 *       ein Lichtstreifen fegt darüber, darunter tippt sich die Arena-Zeile.</li>
 *   <li><b>3 - 2 - 1</b>: Riesenziffer mit Slam, Echo-Ringen und Druckwelle, Speedlines und Funken auf jedem Beat,
 *       ein Segment-Timer füllt sich Sekunde für Sekunde.</li>
 *   <li><b>Startschuss</b>: weißer Blitz, Schockwellen, Konfetti und ein Titel, der in Scheiben einrastet.</li>
 * </ol>
 * Alle Partikel sind reine Funktionen der Zeit: Es gibt keinen Zustand, der aus dem Takt geraten könnte.
 */
@SuppressWarnings({"UnnecessaryLocalVariable", "DuplicatedCode"})
public final class MatchCountdownLayer implements HudElement {
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

   /** Konfetti- und Funkenfarben des Startschusses. */
   private static final int[] CONFETTI = {
      0xFFD700, 0x00F0FF, 0xFF2244, 0xFFFFFF, 0x00E676, 0xBD00FF
   };

   /** Zeitpunkte (Ticks seit Sequenzbeginn), an denen Funken-Salven abgehen. */
   private static final int[] SPARK_BEATS = {
      MatchStartState.HIT_ONE_SHOT, MatchStartState.HIT_ONE_KILL,
      MatchStartState.INTRO_TICKS, MatchStartState.INTRO_TICKS + 20, MatchStartState.INTRO_TICKS + 40
   };

   private static final int TITLE_EXIT_START = 36;

   @Override
   public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
      MatchStartState state = MatchStartState.INSTANCE;
      Minecraft client = Minecraft.getInstance();
      Font font = client.font;
      float partialTick = client.isPaused() ? 0.0F : deltaTracker.getGameTimeDeltaPartialTick(false);
      int width = graphics.guiWidth();
      int height = graphics.guiHeight();
      int centreX = width / 2;
      int centreY = height / 2;

      float go = state.getGoProgress(partialTick);
      if (go > 0.0F) {
         drawGoScreen(graphics, font, width, height, state.goElapsedTicks(partialTick));
         return;
      }
      if (!state.isCountdownActive()) {
         return;
      }

      float elapsed = state.elapsedTicks(partialTick);
      float remaining = state.getRemainingTicks(partialTick);
      int second = state.countdownSecond(partialTick);
      float secondProgress = state.secondProgress(partialTick);
      float punch = state.beatPunch(partialTick);
      int accent = second == 0 ? OsokWidgets.COLOR_CYAN : SECOND_COLORS[second - 1];

      drawBackdrop(graphics, width, height, elapsed, punch, accent);
      drawSpeedLines(graphics, centreX, centreY, width, height, elapsed, punch, accent);
      drawSparks(graphics, centreX, centreY, height, elapsed, accent);

      if (elapsed < TITLE_EXIT_START + 12) {
         drawTitleCard(graphics, font, width, height, elapsed, state);
      }
      if (second > 0) {
         drawCountdownDigit(graphics, font, width, height, second, secondProgress, elapsed, punch, accent);
      }

      drawLetterbox(graphics, font, width, height, elapsed, second, accent, state, remaining);
      drawCornerBrackets(graphics, width, height, elapsed, punch, accent);
      drawSegmentTimer(graphics, width, height, elapsed);
   }

   // ------------------------------------------------------------------
   // Hintergrund, Speedlines, Funken
   // ------------------------------------------------------------------

   /** Verdunkelung, pulsierende Farb-Vignette und holografische Scanlines. */
   private static void drawBackdrop(GuiGraphicsExtractor graphics, int width, int height,
                                    float elapsed, float punch, int accent) {
      float dark = smooth(elapsed / 12.0F) * 0.40F;
      graphics.fill(0, 0, width, height, argb(0x050810, dark));

      vignette(graphics, width, height, accent, (0.16F + 0.40F * punch) * smooth(elapsed / 8.0F));

      int scan = argb(accent, 0.035F);
      for (int y = 0; y < height; y += 4) {
         graphics.fill(0, y, width, y + 1, scan);
      }
      long now = Util.getMillis();
      float sweep = (now % 1800L) / 1800.0F;
      int sweepY = (int) (sweep * height);
      graphics.fill(0, sweepY - 1, width, sweepY + 2, argb(accent, 0.10F + 0.07F * (float) Math.sin(sweep * Math.PI)));
   }

   /** Farbige Randverdunkelung aus weich auslaufenden Streifen. */
   private static void vignette(GuiGraphicsExtractor graphics, int width, int height, int rgb, float strength) {
      if (strength <= 0.005F) {
         return;
      }
      int bands = 22;
      int stripW = Math.max(3, width / 48);
      int stripH = Math.max(3, height / 36);
      for (int i = 0; i < bands; i++) {
         float fade = 1.0F - i / (float) bands;
         int colour = argb(rgb, strength * fade * fade);
         graphics.fill(i * stripW, 0, (i + 1) * stripW, height, colour);
         graphics.fill(width - (i + 1) * stripW, 0, width - i * stripW, height, colour);
         graphics.fill(0, i * stripH, width, (i + 1) * stripH, colour);
         graphics.fill(0, height - (i + 1) * stripH, width, height - i * stripH, colour);
      }
   }

   /** Radiale Speedlines, die auf jedem Beat aufblitzen und danach einschlafen. */
   private static void drawSpeedLines(GuiGraphicsExtractor graphics, int centreX, int centreY,
                                      int width, int height, float elapsed, float punch, int accent) {
      float strength = (0.12F + 0.88F * punch) * smooth(elapsed / 6.0F);
      if (strength <= 0.02F) {
         return;
      }
      float reach = Math.min(width, height);
      int rays = 44;
      graphics.pose().pushMatrix();
      graphics.pose().translate(centreX, centreY);
      for (int i = 0; i < rays; i++) {
         float angle = (float) (i * Math.PI * 2.0 / rays) + rand(i) * 0.12F;
         float start = reach * (0.20F + 0.12F * rand(i + 99)) + 40.0F * (1.0F - punch);
         float length = reach * (0.14F + 0.55F * rand(i + 7)) * strength;
         int thickness = rand(i + 31) > 0.7F ? 2 : 1;
         graphics.pose().pushMatrix();
         graphics.pose().rotate(angle);
         graphics.fill(Math.round(start), -thickness, Math.round(start + length), thickness,
            argb(accent, 0.30F * strength));
         graphics.fill(Math.round(start), 0, Math.round(start + length * 0.55F), 1,
            argb(0xFFFFFF, 0.55F * strength));
         graphics.pose().popMatrix();
      }
      graphics.pose().popMatrix();
   }

   /** Funken-Salven auf jedem Beat – rein aus der Zeit berechnet, ohne Zustand. */
   private static void drawSparks(GuiGraphicsExtractor graphics, int centreX, int centreY,
                                  int height, float elapsed, int accent) {
      float unit = height / 540.0F;
      for (int beat : SPARK_BEATS) {
         float t = elapsed - beat;
         if (t < 0.0F || t > 26.0F) {
            continue;
         }
         for (int i = 0; i < 34; i++) {
            int seed = beat * 131 + i * 17;
            float life = 14.0F + rand(seed + 2) * 12.0F;
            if (t > life) {
               continue;
            }
            float angle = rand(seed) * (float) (Math.PI * 2.0);
            float speed = 4.0F + rand(seed + 1) * 10.0F;
            float dirX = (float) Math.cos(angle);
            float dirY = (float) Math.sin(angle);
            float size = 2.0F + rand(seed + 3) * 2.0F;
            float alpha = 1.0F - t / life;
            int colour = rand(seed + 4) > 0.5F ? 0xFFFFFF : accent;

            float travel = speed * t * (1.0F - t / (life * 2.3F));
            float x = centreX + dirX * travel * unit;
            float y = centreY + dirY * travel * unit + 0.09F * t * t * unit;
            rect(graphics, x, y, x + size, y + size, argb(colour, alpha));

            // kurzer Schweif an der Position von vor 1,6 Ticks
            float back = Math.max(0.0F, t - 1.6F);
            float trail = speed * back * (1.0F - back / (life * 2.3F));
            float tx = centreX + dirX * trail * unit;
            float ty = centreY + dirY * trail * unit + 0.09F * back * back * unit;
            rect(graphics, tx, ty, tx + size * 0.6F, ty + size * 0.6F, argb(colour, alpha * 0.45F));
         }
      }
   }

   // ------------------------------------------------------------------
   // Titelkarte "ONE SHOT // ONE KILL"
   // ------------------------------------------------------------------

   private static void drawTitleCard(GuiGraphicsExtractor graphics, Font font, int width, int height,
                                     float elapsed, MatchStartState state) {
      float exit = smooth((elapsed - TITLE_EXIT_START) / 10.0F);
      float alpha = 1.0F - exit;
      if (alpha <= 0.01F) {
         return;
      }
      int centreX = width / 2;
      float scale = Mth.clamp(height / 85.0F, 2.0F, 9.0F);
      float lineHeight = 9.0F * scale;
      float middle = height * 0.42F - exit * height * 0.10F;

      // Zwei Zeilen stürmen von links und rechts herein und rasten auf dem Beat ein.
      float slide1 = easeOutCubic(elapsed / MatchStartState.HIT_ONE_SHOT);
      float slide2 = easeOutCubic((elapsed - MatchStartState.HIT_ONE_SHOT)
         / (MatchStartState.HIT_ONE_KILL - MatchStartState.HIT_ONE_SHOT));
      float x1 = centreX - (1.0F - slide1) * width * 0.75F;
      float x2 = centreX + (1.0F - slide2) * width * 0.75F;
      float y1 = middle - lineHeight * 0.55F;
      float y2 = middle + lineHeight * 0.55F;

      float glitch1 = elapsed < MatchStartState.HIT_ONE_SHOT
         ? 0.0F : (float) Math.exp(-(elapsed - MatchStartState.HIT_ONE_SHOT) / 3.0F);
      float glitch2 = elapsed < MatchStartState.HIT_ONE_KILL
         ? 0.0F : (float) Math.exp(-(elapsed - MatchStartState.HIT_ONE_KILL) / 3.0F);

      if (elapsed > 0.5F) {
         titleLine(graphics, font, "ONE SHOT", x1, y1, scale, 0xFFFFFF, slide1, glitch1, -1.0F, alpha);
      }
      if (elapsed > MatchStartState.HIT_ONE_SHOT + 0.5F) {
         titleLine(graphics, font, "ONE KILL", x2, y2, scale, 0xFF3355, slide2, glitch2, 1.0F, alpha);
      }

      // Lichtstreifen, der einmal quer über beide Zeilen fegt
      float sweep = (elapsed - 18.0F) / 16.0F;
      if (sweep > 0.0F && sweep < 1.0F) {
         int band = (int) (lineHeight * 2.0F);
         graphics.enableScissor(0, Math.round(middle - lineHeight * 1.25F), width, Math.round(middle + lineHeight * 1.25F));
         graphics.pose().pushMatrix();
         graphics.pose().translate(Mth.lerp(sweep, -width * 0.2F, width * 1.2F), middle);
         graphics.pose().rotate(-0.35F);
         graphics.fill(-14, -band, 14, band, argb(0xFFFFFF, 0.30F * alpha * (1.0F - Math.abs(sweep - 0.5F) * 1.2F)));
         graphics.fill(-4, -band, 4, band, argb(0xFFFFFF, 0.45F * alpha));
         graphics.pose().popMatrix();
         graphics.disableScissor();
      }

      // Unterstreichung wächst aus der Mitte
      float lineGrow = easeOutCubic((elapsed - 18.0F) / 10.0F);
      int underlineY = Math.round(middle + lineHeight * 1.15F);
      int half = Math.round(width * 0.28F * lineGrow);
      if (half > 0) {
         graphics.fill(centreX - half, underlineY, centreX + half, underlineY + 3, argb(OsokWidgets.COLOR_CYAN, alpha));
         graphics.fill(centreX - half - 18, underlineY + 1, centreX - half - 6, underlineY + 2, argb(0xFFFFFF, alpha * 0.8F));
         graphics.fill(centreX + half + 6, underlineY + 1, centreX + half + 18, underlineY + 2, argb(0xFFFFFF, alpha * 0.8F));
      }

      // Kopfzeile blinkt leicht, Untertitel wird getippt
      if (elapsed > 4.0F) {
         float blink = 0.7F + 0.3F * (float) Math.sin(elapsed * 0.9F);
         smallText(graphics, font, "- INITIALIZING COMBAT PROTOCOL -", centreX, Math.round(middle - lineHeight * 1.45F),
            Math.max(1.0F, scale / 4.5F), argb(0x94A3B8, alpha * blink));
      }
      if (elapsed > 22.0F) {
         String full = "// ARENA: " + state.getMapName().toUpperCase() + "  //  MODE: " + state.getGameModeName().toUpperCase();
         int typed = Math.min(full.length(), (int) ((elapsed - 22.0F) * 2.4F));
         boolean cursor = typed < full.length() && ((int) (elapsed / 2.0F) & 1) == 0;
         smallText(graphics, font, full.substring(0, typed) + (cursor ? "_" : ""), centreX, underlineY + 12,
            Math.max(1.0F, scale / 3.4F), argb(0xE2E8F0, alpha));
      }
   }

   /** Eine Titelzeile mit Bewegungsunschärfe, RGB-Split und dicker Kontur. */
   private static void titleLine(GuiGraphicsExtractor graphics, Font font, String text, float x, float y,
                                 float scale, int colour, float slide, float glitch, float direction, float alpha) {
      // Nachbilder entgegen der Flugrichtung, solange die Zeile noch fliegt
      if (slide < 0.98F) {
         for (int k = 3; k >= 1; k--) {
            bigText(graphics, font, text, x - direction * k * (1.0F - slide) * 46.0F, y, scale,
               argb(colour, alpha * 0.16F), false);
         }
      }
      float split = glitch * scale * 1.6F;
      if (split > 0.4F) {
         bigText(graphics, font, text, x - split, y, scale, argb(OsokWidgets.COLOR_CYAN, alpha * 0.65F * glitch), false);
         bigText(graphics, font, text, x + split, y, scale, argb(OsokWidgets.COLOR_CRIMSON, alpha * 0.65F * glitch), false);
      }
      // Beim Einrasten kurz größer
      bigText(graphics, font, text, x, y, scale * (1.0F + 0.10F * glitch), argb(colour, alpha), true);
   }

   // ------------------------------------------------------------------
   // Countdown-Ziffer
   // ------------------------------------------------------------------

   /** Riesige Ziffer mit Slam, Echo-Ringen, RGB-Split und Wackeln. */
   private static void drawCountdownDigit(GuiGraphicsExtractor graphics, Font font, int width, int height,
                                          int second, float secondProgress, float elapsed, float punch, int accent) {
      int centreX = width / 2;
      int centreY = height / 2;
      float base = Mth.clamp(height / 66.0F, 3.0F, 13.0F);
      float appear = easeOutBack(secondProgress * 5.0F);
      float scale = base * (1.0F + 1.6F * (1.0F - Math.min(1.0F, appear)));
      float alpha = Mth.clamp(secondProgress * 10.0F, 0.0F, 1.0F)
         * (1.0F - 0.55F * smooth((secondProgress - 0.85F) / 0.15F));
      String digit = Integer.toString(second);

      // Druckwelle: Punktring, der vom Einschlag nach außen läuft
      if (secondProgress < 0.55F) {
         float t = secondProgress / 0.55F;
         drawRing(graphics, centreX, centreY, Mth.lerp(easeOutCubic(t), base * 4.0F, Math.min(width, height) * 0.50F),
            72, 3, argb(accent, (1.0F - t) * 0.9F));
         drawRing(graphics, centreX, centreY, Mth.lerp(easeOutCubic(t), base * 3.0F, Math.min(width, height) * 0.40F),
            56, 2, argb(0xFFFFFF, (1.0F - t) * 0.6F));
      }

      float shake = punch * 3.0F;
      float dx = (float) Math.sin(elapsed * 13.0F) * shake;
      float dy = (float) Math.cos(elapsed * 17.0F) * shake;

      // Echos hinter der Ziffer
      for (int k = 3; k >= 1; k--) {
         bigText(graphics, font, digit, centreX + dx, centreY + dy, scale * (1.0F + 0.20F * k * (0.4F + secondProgress)),
            argb(accent, 0.20F / k * (1.0F - secondProgress) * alpha + 0.02F), false);
      }
      float split = punch * 7.0F;
      if (split > 0.5F) {
         bigText(graphics, font, digit, centreX + dx - split, centreY + dy, scale,
            argb(OsokWidgets.COLOR_CYAN, 0.6F * punch * alpha), false);
         bigText(graphics, font, digit, centreX + dx + split, centreY + dy, scale,
            argb(OsokWidgets.COLOR_CRIMSON, 0.6F * punch * alpha), false);
      }
      bigText(graphics, font, digit, centreX + dx, centreY + dy, scale, argb(lighten(accent, 0.35F), alpha), true);

      // Beschriftung unter der Ziffer, getippt
      String sub = PHASE_SUBTITLES[Math.min(PHASE_SUBTITLES.length - 1, second - 1)];
      int typed = Math.min(sub.length(), (int) (secondProgress * 60.0F));
      smallText(graphics, font, sub.substring(0, typed), centreX, centreY + Math.round(base * 5.4F),
         Math.max(1.0F, base / 8.0F), argb(0xF1F5F9, Mth.clamp(secondProgress * 6.0F, 0.0F, 1.0F)));
   }

   // ------------------------------------------------------------------
   // Rahmen: Letterbox, Klammern, Segment-Timer
   // ------------------------------------------------------------------

   private static float barMax(int height) {
      return Mth.clamp(height * 0.075F, 15.0F, 44.0F);
   }

   /** Filmische Balken oben und unten, mit laufenden Warnstreifen und Telemetrie. */
   private static void drawLetterbox(GuiGraphicsExtractor graphics, Font font, int width, int height,
                                     float elapsed, int second, int accent, MatchStartState state, float remaining) {
      float barMax = barMax(height);
      int barH = Math.round(barMax * easeOutCubic(elapsed / 10.0F));
      if (barH <= 0) {
         return;
      }
      graphics.fill(0, 0, width, barH, 0xF2060810);
      graphics.fill(0, height - barH, width, height, 0xF2060810);
      graphics.fill(0, barH - 1, width, barH, argb(accent, 0.75F));
      graphics.fill(0, height - barH, width, height - barH + 1, argb(accent, 0.75F));

      // Warnstreifen laufen gegenläufig an der Innenkante entlang
      int scroll = (int) (Util.getMillis() / 35L % 16L);
      warningStripes(graphics, width, barH - 6, barH - 1, scroll, accent);
      warningStripes(graphics, width, height - barH + 1, height - barH + 6, 16 - scroll, accent);

      if (barH < barMax - 2.0F) {
         return;
      }
      int textTop = Math.max(2, (barH - 9) / 2 - 2);
      graphics.text(font, "OSOK // PROTOCOL // ARENA: " + state.getMapName().toUpperCase(), 14, textTop, 0xFFCBD5E1);

      String status = switch (second) {
         case 0 -> "STANDBY";
         case 3 -> "INIT";
         case 2 -> "ARMED";
         default -> "LOCK-ON";
      };
      String right = "SYS.STATUS: " + status + " // " + state.getGameModeName().toUpperCase();
      graphics.text(font, right, width - 14 - font.width(right), textTop, argb(accent, 1.0F));

      int bottomText = height - barH + textTop;
      String bottomMsg = Component.translatable("hud.oneshotonekill.match.movement_locked").getString();
      graphics.centeredText(font, Component.literal(bottomMsg), width / 2, bottomText, 0xFFE2E8F0);

      int hundredths = Math.max(0, Math.round(remaining * 5.0F));
      String timer = "T-" + twoDigits(hundredths / 100) + "." + twoDigits(hundredths % 100);
      graphics.text(font, timer, width - 14 - font.width(timer), bottomText, argb(accent, 1.0F));
      String phase = switch (second) {
         case 0 -> "BRIEFING";
         case 3 -> Component.translatable("hud.oneshotonekill.match.init").getString();
         case 2 -> Component.translatable("hud.oneshotonekill.match.armed").getString();
         default -> Component.translatable("hud.oneshotonekill.match.lock_on").getString();
      };
      graphics.text(font, phase, 14, bottomText, argb(accent, 1.0F));
   }

   private static void warningStripes(GuiGraphicsExtractor graphics, int width, int top, int bottom,
                                      int scroll, int accent) {
      graphics.enableScissor(0, top, width, bottom);
      for (int x = -16; x < width + 16; x += 16) {
         graphics.pose().pushMatrix();
         graphics.pose().translate(x + scroll, top);
         graphics.pose().rotate(-0.7F);
         graphics.fill(0, -2, 5, 12, argb(accent, 0.38F));
         graphics.pose().popMatrix();
      }
      graphics.disableScissor();
   }

   /** Vier Zielfokus-Klammern, die sich bis zum Start langsam zusammenziehen. */
   private static void drawCornerBrackets(GuiGraphicsExtractor graphics, int width, int height,
                                          float elapsed, float punch, int accent) {
      float progress = smooth(elapsed / MatchStartState.COUNTDOWN_TICKS);
      int bar = Math.round(barMax(height));
      int margin = Math.round(Mth.lerp(progress, 64.0F, 20.0F) - punch * 8.0F);
      int arm = Math.round(26.0F + 18.0F * punch);
      int colour = argb(accent, 0.9F * smooth(elapsed / 8.0F));
      int left = margin;
      int right = width - margin;
      int top = bar + margin;
      int bottom = height - bar - margin;
      // oben links, oben rechts, unten links, unten rechts
      graphics.fill(left, top, left + arm, top + 2, colour);
      graphics.fill(left, top, left + 2, top + arm, colour);
      graphics.fill(right - arm, top, right, top + 2, colour);
      graphics.fill(right - 2, top, right, top + arm, colour);
      graphics.fill(left, bottom - 2, left + arm, bottom, colour);
      graphics.fill(left, bottom - arm, left + 2, bottom, colour);
      graphics.fill(right - arm, bottom - 2, right, bottom, colour);
      graphics.fill(right - 2, bottom - arm, right, bottom, colour);
   }

   /** Drei Balken über dem unteren Rand, die sich Sekunde für Sekunde füllen. */
   private static void drawSegmentTimer(GuiGraphicsExtractor graphics, int width, int height, float elapsed) {
      float visible = smooth((elapsed - (MatchStartState.INTRO_TICKS - 8)) / 8.0F);
      if (visible <= 0.01F) {
         return;
      }
      int segW = Math.max(24, width / 12);
      int gap = 8;
      int total = segW * 3 + gap * 2;
      int left = width / 2 - total / 2;
      int y = height - Math.round(barMax(height)) - 18;
      for (int i = 0; i < 3; i++) {
         int x0 = left + i * (segW + gap);
         float fill = Mth.clamp((elapsed - (MatchStartState.INTRO_TICKS + i * 20)) / 20.0F, 0.0F, 1.0F);
         int segColour = SECOND_COLORS[2 - i];
         int filled = Math.round(segW * fill);
         graphics.fill(x0, y, x0 + segW, y + 5, argb(0x1E293B, 0.85F * visible));
         graphics.fill(x0, y, x0 + filled, y + 5, argb(segColour, visible));
         if (fill > 0.0F && fill < 1.0F) {
            graphics.fill(x0 + filled - 3, y - 2, x0 + filled + 1, y + 7, argb(0xFFFFFF, visible));
         }
      }
   }

   // ------------------------------------------------------------------
   // Startschuss
   // ------------------------------------------------------------------

   /** Flash, Schockwellen, Speedlines, Konfetti und ein in Scheiben einrastender Startschuss-Titel. */
   private static void drawGoScreen(GuiGraphicsExtractor graphics, Font font, int width, int height, float goElapsed) {
      int centreX = width / 2;
      int centreY = height / 2;
      float life = goElapsed / MatchStartState.GO_TICKS;
      float unit = height / 540.0F;
      float fadeOut = 1.0F - smooth((goElapsed - (MatchStartState.GO_TICKS - 10.0F)) / 10.0F);

      // 1. Weißer Blitz und goldene Vignette
      graphics.fill(0, 0, width, height, argb(0xFFFFFF, Math.max(0.0F, 1.0F - goElapsed / 7.0F) * 0.92F));
      vignette(graphics, width, height, 0xFFB800, 0.55F * (1.0F - life));

      // 2. Speedlines schießen nach außen
      float lineAlpha = Math.max(0.0F, 1.0F - goElapsed / 24.0F);
      if (lineAlpha > 0.0F) {
         graphics.pose().pushMatrix();
         graphics.pose().translate(centreX, centreY);
         for (int i = 0; i < 56; i++) {
            float angle = (float) (i * Math.PI * 2.0 / 56.0) + rand(i + 500) * 0.08F;
            float start = (30.0F + goElapsed * (14.0F + 20.0F * rand(i + 501))) * unit;
            float length = (50.0F + 120.0F * rand(i + 502)) * unit;
            graphics.pose().pushMatrix();
            graphics.pose().rotate(angle);
            graphics.fill(Math.round(start), -1, Math.round(start + length), 1, argb(0xFFD700, lineAlpha));
            graphics.fill(Math.round(start + length * 0.4F), 0, Math.round(start + length), 1, argb(0xFFFFFF, lineAlpha));
            graphics.pose().popMatrix();
         }
         graphics.pose().popMatrix();
      }

      // 3. Drei Schockwellen-Ringe
      for (int k = 0; k < 3; k++) {
         float t = goElapsed - k * 3.0F;
         if (t <= 0.0F) {
            continue;
         }
         float radius = t * 24.0F * unit * (1.0F + 0.25F * k);
         float alpha = Math.max(0.0F, 1.0F - t / 22.0F);
         drawRing(graphics, centreX, centreY, radius, 90, 3 - k / 2, argb(k == 1 ? 0x00F0FF : 0xFFD700, alpha));
      }

      // 4. Konfetti-Regen
      for (int i = 0; i < 120; i++) {
         int seed = 7000 + i * 13;
         float lifeSpan = 20.0F + rand(seed + 2) * 18.0F;
         if (goElapsed > lifeSpan) {
            continue;
         }
         float angle = (float) (-Math.PI * (0.05 + 0.9 * rand(seed)));
         float speed = 6.0F + rand(seed + 1) * 17.0F;
         float travel = speed * goElapsed * (1.0F - goElapsed / (lifeSpan * 2.4F));
         float x = centreX + (float) Math.cos(angle) * travel * unit * 1.4F;
         float y = centreY + (float) Math.sin(angle) * travel * unit + 0.28F * goElapsed * goElapsed * unit;
         float flutter = Math.abs((float) Math.cos(goElapsed * 0.6F + i)) + 0.25F;
         float sizeW = (3.0F + rand(seed + 3) * 4.0F) * flutter;
         float sizeH = 2.0F + rand(seed + 4) * 3.0F;
         float alpha = 1.0F - (goElapsed / lifeSpan) * (goElapsed / lifeSpan);
         rect(graphics, x, y, x + sizeW, y + sizeH, argb(CONFETTI[i % CONFETTI.length], alpha));
      }

      // 5. Titel: rastet in horizontalen Scheiben von links und rechts ein
      String title = Component.translatable("hud.oneshotonekill.match.start").getString();
      float scale = Mth.clamp(height / 70.0F, 2.5F, 10.0F);
      float titleY = centreY - height * 0.04F;
      int slices = 8;
      float sliceH = 9.0F * scale / slices;
      float top = titleY - 4.5F * scale;
      for (int i = 0; i < slices; i++) {
         float u = Mth.clamp((goElapsed - 1.0F - i * 0.9F) / 9.0F, 0.0F, 1.0F);
         if (u <= 0.0F) {
            continue;
         }
         float offset = (1.0F - easeOutCubic(u)) * (i % 2 == 0 ? -1.0F : 1.0F) * width * 0.6F;
         float y0 = top + i * sliceH;
         graphics.enableScissor(0, Math.round(y0), width, Math.round(y0 + sliceH) + 1);
         bigText(graphics, font, title, centreX + offset, titleY, scale,
            argb(0xFFD700, Math.min(1.0F, u * 3.0F) * fadeOut), true);
         graphics.disableScissor();
      }
      // Glanzstreifen läuft einmal über den eingerasteten Titel
      float shine = (goElapsed - 12.0F) / 10.0F;
      if (shine > 0.0F && shine < 1.0F) {
         int band = (int) (9.0F * scale);
         graphics.enableScissor(0, Math.round(top), width, Math.round(top + 9.0F * scale));
         graphics.pose().pushMatrix();
         graphics.pose().translate(Mth.lerp(shine, centreX - width * 0.4F, centreX + width * 0.4F), titleY);
         graphics.pose().rotate(-0.35F);
         graphics.fill(-10, -band, 10, band, argb(0xFFFFFF, 0.55F * fadeOut));
         graphics.pose().popMatrix();
         graphics.disableScissor();
      }

      // 6. Untertitel
      float subAlpha = Mth.clamp((goElapsed - 8.0F) / 5.0F, 0.0F, 1.0F) * fadeOut;
      if (subAlpha > 0.0F) {
         String fire = Component.translatable("hud.oneshotonekill.match.fire_at_will").getString();
         smallText(graphics, font, fire, centreX, Math.round(titleY + 5.6F * scale),
            Math.max(1.5F, scale / 3.0F), argb(0xFFFFFF, subAlpha));
         smallText(graphics, font, "ONE SHOT  //  ONE KILL", centreX, Math.round(titleY - 6.3F * scale),
            Math.max(1.0F, scale / 4.5F), argb(0x00F0FF, subAlpha));
      }

      // 7. Letterbox fährt wieder aus
      int barH = Math.round(barMax(height) * (1.0F - easeOutCubic(goElapsed / 10.0F)));
      if (barH > 0) {
         graphics.fill(0, 0, width, barH, 0xF2060810);
         graphics.fill(0, height - barH, width, height, 0xF2060810);
      }
   }

   // ------------------------------------------------------------------
   // Zeichenhelfer
   // ------------------------------------------------------------------

   /** Zentrierter, skalierter Text mit optionaler dicker schwarzer Kontur. */
   private static void bigText(GuiGraphicsExtractor graphics, Font font, String text, float centreX, float centreY,
                               float scale, int colour, boolean outline) {
      if ((colour >>> 24) == 0) {
         return;
      }
      graphics.pose().pushMatrix();
      graphics.pose().translate(centreX, centreY);
      graphics.pose().scale(scale, scale);
      int x = -font.width(text) / 2;
      int y = -9 / 2;
      if (outline) {
         int shadow = colour & 0xFF000000;
         for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
               if (dx != 0 || dy != 0) {
                  graphics.text(font, text, x + dx, y + dy, shadow, false);
               }
            }
         }
      }
      graphics.text(font, text, x, y, colour, false);
      graphics.pose().popMatrix();
   }

   /** Kleiner zentrierter Text mit Schatten, optional vergrößert. */
   private static void smallText(GuiGraphicsExtractor graphics, Font font, String text, int centreX, int y,
                                 float scale, int colour) {
      if ((colour >>> 24) == 0 || text.isEmpty()) {
         return;
      }
      graphics.pose().pushMatrix();
      graphics.pose().translate(centreX, y);
      graphics.pose().scale(scale, scale);
      graphics.text(font, text, -font.width(text) / 2, 0, colour, true);
      graphics.pose().popMatrix();
   }

   /** Ring aus Punkten. */
   private static void drawRing(GuiGraphicsExtractor graphics, int centreX, int centreY, float radius,
                                int dots, int size, int colour) {
      if ((colour >>> 24) == 0 || radius <= 0.0F) {
         return;
      }
      for (int i = 0; i < dots; i++) {
         float angle = (float) (i * Math.PI * 2.0 / dots);
         int x = centreX + Math.round(Mth.cos(angle) * radius);
         int y = centreY + Math.round(Mth.sin(angle) * radius);
         graphics.fill(x - size / 2, y - size / 2, x + size / 2 + 1, y + size / 2 + 1, colour);
      }
   }

   private static void rect(GuiGraphicsExtractor graphics, float x0, float y0, float x1, float y1, int colour) {
      if ((colour >>> 24) == 0) {
         return;
      }
      int left = Math.round(x0);
      int top = Math.round(y0);
      graphics.fill(left, top, Math.max(Math.round(x1), left + 1), Math.max(Math.round(y1), top + 1), colour);
   }

   private static String twoDigits(int value) {
      return value < 10 ? "0" + value : Integer.toString(value);
   }

   private static int argb(int rgb, float alpha) {
      return Mth.clamp(Math.round(alpha * 255.0F), 0, 255) << 24 | rgb & 0x00FFFFFF;
   }

   private static int lighten(int rgb, float amount) {
      int r = rgb >> 16 & 0xFF;
      int g = rgb >> 8 & 0xFF;
      int b = rgb & 0xFF;
      r += Math.round((255 - r) * amount);
      g += Math.round((255 - g) * amount);
      b += Math.round((255 - b) * amount);
      return r << 16 | g << 8 | b;
   }

   private static float smooth(float t) {
      float x = Mth.clamp(t, 0.0F, 1.0F);
      return x * x * (3.0F - 2.0F * x);
   }

   private static float easeOutCubic(float t) {
      float x = 1.0F - Mth.clamp(t, 0.0F, 1.0F);
      return 1.0F - x * x * x;
   }

   private static float easeOutBack(float t) {
      float x = Mth.clamp(t, 0.0F, 1.0F) - 1.0F;
      return 1.0F + 2.70158F * x * x * x + 1.70158F * x * x;
   }

   /** Deterministischer Pseudo-Zufall 0..1 aus einer Zahl: stabile Partikel ohne Zustand. */
   private static float rand(int seed) {
      int h = seed * 0x9E3779B1;
      h ^= h >>> 15;
      h *= 0x85EBCA6B;
      h ^= h >>> 13;
      return (h & 0xFFFFFF) / (float) 0x1000000;
   }
}
