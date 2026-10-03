package com.oneshotonekill.client.hud;

import static com.oneshotonekill.client.hud.HudFx.argb;
import static com.oneshotonekill.client.hud.HudFx.easeOutBack;
import static com.oneshotonekill.client.hud.HudFx.easeOutCubic;
import static com.oneshotonekill.client.hud.HudFx.smooth;
import static com.oneshotonekill.client.hud.HudFx.twoDigits;

import com.oneshotonekill.client.effect.HeartbeatClock;
import com.oneshotonekill.client.screen.OsokWidgets;
import com.oneshotonekill.client.state.ClientStates.NukeState;
import com.oneshotonekill.nuke.NukeSequenceManager;
import com.oneshotonekill.nuke.NukeSequenceManager.NukePhase;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

/**
 * Der Countdown vor dem Einschlag: DEFCON 1 als Einsatzleitstand.
 * <p>
 * Alles hängt an zwei Uhren: der Zeit bis zum Einschlag und dem Herzschlag aus
 * {@link HeartbeatClock}, der mit der Tonspur {@code nuke_countdown_bed.ogg} läuft. Die rote Vignette atmet
 * im Sirenentakt (eine Sekunde, wie die Ansage) und schlägt zusätzlich auf jedem Herzschlag aus. Links
 * unten läuft ein Radar mit Ground Zero, der eigenen Position und dem anfliegenden Bomber, rechts unten
 * entschlüsselt sich die Telemetrie, oben steht die Uhr mit Hundertstelsekunden. In den letzten fünf
 * Sekunden schlägt jede Ziffer riesig in die Bildmitte, und kurz vor Null zieht ein weißer Schleier auf, der
 * nahtlos in den Blitz des Einschlags übergeht.
 */
@SuppressWarnings("DuplicatedCode")
public final class NukeCountdownLayer implements HudElement {
   private static final int BAR = 26;
   private static final int RED = 0xFF3C28;
   private static final int GREEN = 0x40FF70;
   /** Wie viele Blöcke der Radarradius abbildet. */
   private static final float RADAR_RANGE = 70.0F;
   private static final float COUNTDOWN_SECONDS = NukePhase.DETONATION.from() / 20.0F;

   @Override
   public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
      NukeState state = NukeState.INSTANCE;
      NukePhase phase = state.phase();
      if (phase != NukePhase.FREEZE && phase != NukePhase.COUNTDOWN) {
         return;
      }

      Minecraft client = Minecraft.getInstance();
      Font font = client.font;
      float partial = deltaTracker.getGameTimeDeltaPartialTick(false);
      float time = state.seconds(partial);
      float toImpact = state.secondsToImpactExact(partial);
      float progress = state.countdownProgress(partial);
      float beat = HeartbeatClock.punch(time);
      float strobe = strobe(time);

      int width = graphics.guiWidth();
      int height = graphics.guiHeight();
      float ui = HudFx.uiScale(width, height);
      int vw = (int) Math.ceil(width / ui);
      int vh = (int) Math.ceil(height / ui);

      drawAlertGrade(graphics, width, height, progress, beat, strobe);

      graphics.pose().pushMatrix();
      graphics.pose().scale(ui, ui);
      drawScanlines(graphics, vw, vh, time, progress);
      drawBars(graphics, font, vw, vh, time, strobe);
      drawRadar(graphics, font, client, state, partial, time, vw, vh);
      drawTelemetry(graphics, font, state, partial, toImpact, time, vw, vh);
      drawTimer(graphics, font, vw, toImpact, beat);
      if (toImpact <= 5.0F && toImpact > 0.0F) {
         drawFinalSlam(graphics, font, vw, vh, toImpact);
      }
      graphics.pose().popMatrix();

      // Der weiße Schleier gehört in Bildschirmkoordinaten: er soll jede Ecke decken.
      if (toImpact < 0.4F) {
         graphics.fill(0, 0, width, height, argb(0xFFFFFF, smooth((0.4F - toImpact) / 0.4F) * 0.9F));
      }
   }

   /** Sirenentakt: eine Sekunde, mit der Spitze kurz vor dem Sekundenwechsel - wie im Original-Alarm. */
   private static float strobe(float time) {
      float phase = time % 1.0F;
      float s = 0.5F + 0.5F * (float) Math.cos((phase - 0.8F) * Math.PI * 2.0);
      return s * s;
   }

   // ------------------------------------------------------------------
   // Bildgrading
   // ------------------------------------------------------------------

   private static void drawAlertGrade(GuiGraphicsExtractor graphics, int width, int height,
                                      float progress, float beat, float strobe) {
      graphics.fill(0, 0, width, height, argb(0x180404, progress * 0.30F));
      HudFx.vignette(graphics, width, height, 0xFF1A10,
         (0.18F + 0.45F * progress) * (0.50F + 0.50F * strobe) + 0.30F * beat * progress);
   }

   private static void drawScanlines(GuiGraphicsExtractor graphics, int vw, int vh, float time, float progress) {
      int scan = argb(RED, 0.03F + 0.03F * progress);
      for (int y = 0; y < vh; y += 3) {
         graphics.fill(0, y, vw, y + 1, scan);
      }
      int sweep = Math.floorMod((int) (time * 90.0F), vh);
      graphics.fill(0, sweep, vw, sweep + 2, argb(RED, 0.10F + 0.14F * progress));
   }

   // ------------------------------------------------------------------
   // Balken oben und unten
   // ------------------------------------------------------------------

   private static void drawBars(GuiGraphicsExtractor graphics, Font font, int vw, int vh, float time, float strobe) {
      graphics.fill(0, 0, vw, BAR, 0xF2090306);
      graphics.fill(0, vh - BAR, vw, vh, 0xF2090306);
      int edge = argb(0xFF2A2A, 0.55F + 0.45F * strobe);
      graphics.fill(0, BAR - 1, vw, BAR, edge);
      graphics.fill(0, vh - BAR, vw, vh - BAR + 1, edge);

      int scroll = (int) (time * 40.0F);
      HudFx.hazardStripes(graphics, vw, BAR - 6, BAR - 1, scroll, RED, 0.55F);
      HudFx.hazardStripes(graphics, vw, vh - BAR + 1, vh - BAR + 6, -scroll, RED, 0.55F);

      boolean flash = ((int) (time * 4.0F) & 1) == 0;
      HudFx.smallText(graphics, font, "☢ DEFCON 1  //  TACTICAL NUKE INBOUND ☢", vw / 2.0F, 8,
         1.0F, flash ? OsokWidgets.COLOR_GOLD : OsokWidgets.COLOR_CRIMSON);
      HudFx.leftText(graphics, font, "OSOK // STRATCOM", 10, 8, 1.0F, 0xFFCBD5E1);
      HudFx.rightText(graphics, font, "AUTHORISIERUNG: BESTAETIGT", vw - 10, 8, 1.0F, 0xFFCBD5E1);

      HudFx.smallText(graphics, font, "[ NOTFALL-PROTOKOLL AKTIV · EVAKUIERUNG UNMOEGLICH ]", vw / 2.0F, vh - BAR + 9,
         1.0F, 0xFFE2E8F0);
      HudFx.leftText(graphics, font, "SIRENE: AKTIV", 10, vh - BAR + 9, 1.0F, argb(RED, 0.6F + 0.4F * strobe));
      HudFx.rightText(graphics, font, "SPERRE: WAFFEN / ITEMS / BAU", vw - 10, vh - BAR + 9, 1.0F, 0xFF94A3B8);
   }

   // ------------------------------------------------------------------
   // Radar
   // ------------------------------------------------------------------

   private static void drawRadar(GuiGraphicsExtractor graphics, Font font, Minecraft client, NukeState state,
                                 float partial, float time, int vw, int vh) {
      int radius = 46;
      int cx = 14 + radius + 8;
      int cy = vh - BAR - radius - 20;

      graphics.fill(cx - radius - 8, cy - radius - 17, cx + radius + 8, cy + radius + 17, argb(0x040806, 0.80F));
      graphics.outline(cx - radius - 8, cy - radius - 17, radius * 2 + 16, radius * 2 + 34, argb(RED, 0.75F));
      HudFx.disc(graphics, cx, cy, radius, argb(0x0B1A12, 0.85F));
      HudFx.ring(graphics, cx, cy, radius, 90, 1, argb(GREEN, 0.60F));
      HudFx.ring(graphics, cx, cy, radius * 2.0F / 3.0F, 60, 1, argb(GREEN, 0.28F));
      HudFx.ring(graphics, cx, cy, radius / 3.0F, 36, 1, argb(GREEN, 0.28F));
      graphics.fill(cx - radius, cy, cx + radius, cy + 1, argb(GREEN, 0.20F));
      graphics.fill(cx, cy - radius, cx + 1, cy + radius, argb(GREEN, 0.20F));

      // Sweep mit Leuchtschweif
      float angle = time * 2.6F;
      for (int i = 0; i < 22; i++) {
         float a = angle - i * 0.045F;
         HudFx.line(graphics, cx, cy, cx + Mth.cos(a) * radius, cy + Mth.sin(a) * radius, i == 0 ? 2 : 1,
            argb(GREEN, (1.0F - i / 22.0F) * 0.5F));
      }

      // Ground Zero pulsiert im Sekundentakt
      float frac = time % 1.0F;
      HudFx.ring(graphics, cx, cy, frac * radius * 0.9F, 40, 2, argb(0xFF3030, 1.0F - frac));
      graphics.fill(cx - 2, cy - 2, cx + 3, cy + 3, argb(0xFF4040, 1.0F));

      // Eigene Position
      LocalPlayer player = client.player;
      float distance = 0.0F;
      if (player != null) {
         double dx = player.getX() - state.centreX();
         double dz = player.getZ() - state.centreZ();
         distance = (float) Math.sqrt(dx * dx + dz * dz);
         float scale = radius / RADAR_RANGE;
         float px = (float) dx * scale;
         float pz = (float) dz * scale;
         float len = (float) Math.sqrt(px * px + pz * pz);
         if (len > radius - 3) {
            px *= (radius - 3) / len;
            pz *= (radius - 3) / len;
         }
         graphics.fill(Math.round(cx + px) - 1, Math.round(cy + pz) - 1, Math.round(cx + px) + 2, Math.round(cy + pz) + 2,
            argb(0x00F0FF, 1.0F));
         HudFx.ring(graphics, cx + px, cy + pz, 3.0F + 2.0F * ((time * 2.0F) % 1.0F), 12, 1, argb(0x00F0FF, 0.7F));
      }

      // Bomber im Anflug und die Bombe im Fall
      float tick = state.currentTick() + partial;
      if (tick >= NukeSequenceManager.BOMBER_ENTER_TICK && tick < NukePhase.DETONATION.from()) {
         float bx = (tick - NukeSequenceManager.BOMB_RELEASE_TICK) * (float) NukeSequenceManager.BOMBER_SPEED;
         float blipX = cx + bx / RADAR_RANGE * radius;
         if (Math.abs(bx) < RADAR_RANGE && ((int) (time * 6.0F) & 1) == 0) {
            HudFx.line(graphics, blipX - 3, cy, blipX, cy - 3, 2, argb(0xFFB800, 1.0F));
            HudFx.line(graphics, blipX, cy - 3, blipX + 3, cy, 2, argb(0xFFB800, 1.0F));
            HudFx.line(graphics, blipX + 3, cy, blipX, cy + 3, 2, argb(0xFFB800, 1.0F));
            HudFx.line(graphics, blipX, cy + 3, blipX - 3, cy, 2, argb(0xFFB800, 1.0F));
         }
      }

      HudFx.leftText(graphics, font, "RADAR // SEKTOR", cx - radius - 5, cy - radius - 13, 0.9F, argb(GREEN, 0.9F));
      String gz = "GZ-ABSTAND: " + Math.round(distance) + " M";
      HudFx.leftText(graphics, font, gz, cx - radius - 5, cy + radius + 6, 0.9F,
         distance < 30.0F ? argb(0xFF5050, 0.6F + 0.4F * ((int) (time * 4.0F) & 1)) : 0xFFE2E8F0);
   }

   // ------------------------------------------------------------------
   // Telemetrie
   // ------------------------------------------------------------------

   private static void drawTelemetry(GuiGraphicsExtractor graphics, Font font, NukeState state, float partial,
                                     float toImpact, float time, int vw, int vh) {
      float tick = state.currentTick() + partial;
      boolean released = tick >= NukeSequenceManager.BOMB_RELEASE_TICK;
      boolean inbound = tick >= NukeSequenceManager.BOMBER_ENTER_TICK;
      float fall = Mth.clamp((tick - NukeSequenceManager.BOMB_RELEASE_TICK)
         / (float) (NukePhase.DETONATION.from() - NukeSequenceManager.BOMB_RELEASE_TICK), 0.0F, 1.0F);

      String carrier = released ? "ABWURF" : inbound ? "ANFLUG" : "STANDBY";
      String altitude = !inbound ? "--" : released ? Math.round(30.0F * (1.0F - fall * fall)) + " M" : "30 M";
      String[][] lines = {
         {"SPRENGKOPF", "SCHARF"},
         {"SPRENGKRAFT", "20.0 MT"},
         {"TRAEGER", carrier},
         {"HOEHE", altitude},
         {"DETONATION", String.format(java.util.Locale.ROOT, "T-%05.2f", toImpact)},
         {"SCHUTZRAUM", "KEINER"},
      };

      int panelW = 132;
      int lineH = 11;
      int px = vw - 14 - panelW;
      int py = vh - BAR - lines.length * lineH - 22;
      graphics.fill(px - 6, py - 14, px + panelW + 6, py + lines.length * lineH + 6, argb(0x040806, 0.80F));
      graphics.outline(px - 6, py - 14, panelW + 12, lines.length * lineH + 20, argb(RED, 0.75F));
      HudFx.leftText(graphics, font, "TELEMETRIE", px, py - 10, 0.9F, argb(RED, 0.95F));

      for (int i = 0; i < lines.length; i++) {
         float resolved = Mth.clamp((time - 0.3F - i * 0.35F) / 0.5F, 0.0F, 1.0F);
         int y = py + i * lineH;
         HudFx.leftText(graphics, font, lines[i][0], px, y, 0.9F, 0xFF94A3B8);
         int colour = i == 5 && ((int) (time * 3.0F) & 1) == 0 ? OsokWidgets.COLOR_CRIMSON : 0xFFF1F5F9;
         HudFx.rightText(graphics, font, HudFx.scramble(lines[i][1], resolved, (int) (time * 24.0F) + i * 31),
            px + panelW, y, 0.9F, resolved >= 1.0F ? colour : argb(GREEN, 0.85F));
      }
   }

   // ------------------------------------------------------------------
   // Uhr
   // ------------------------------------------------------------------

   private static void drawTimer(GuiGraphicsExtractor graphics, Font font, int vw, float toImpact, float beat) {
      int cx = vw / 2;
      int top = BAR + 8;
      HudFx.smallText(graphics, font, "EINSCHLAG IN", cx, top, 1.1F, argb(0xCBD5E1, 0.9F));

      int centis = Math.max(0, (int) (toImpact * 100.0F));
      String text = twoDigits(centis / 100) + "." + twoDigits(centis % 100);
      float urgency = 1.0F - Mth.clamp(toImpact / 6.0F, 0.0F, 1.0F);
      int colour = toImpact > 6.0F ? OsokWidgets.COLOR_GOLD : OsokWidgets.COLOR_CRIMSON;
      float flicker = toImpact < 3.0F ? (((int) (Util.getMillis() / 60L) & 1) == 0 ? 1.0F : 0.78F) : 1.0F;
      float scale = 3.0F + 0.25F * beat + urgency * 0.3F;
      float split = 1.5F + urgency * 3.0F;
      float y = top + 28;

      HudFx.bigText(graphics, font, text, cx - split, y, scale, argb(0x00F0FF, 0.7F * flicker), false);
      HudFx.bigText(graphics, font, text, cx + split, y, scale, argb(0xFF2233, 0.7F * flicker), false);
      HudFx.bigText(graphics, font, text, cx, y, scale, argb(colour, flicker), true);

      // Segmentierter Restzeit-Balken
      int blocks = 40;
      int filled = (int) Math.ceil(blocks * toImpact / COUNTDOWN_SECONDS);
      int barW = 5;
      int gap = 2;
      int left = cx - (blocks * (barW + gap)) / 2;
      int barY = (int) (y + 4.5F * scale) + 6;
      for (int i = 0; i < blocks; i++) {
         int x = left + i * (barW + gap);
         graphics.fill(x, barY, x + barW, barY + 4, i < filled ? argb(colour, 0.95F) : argb(0x334155, 0.55F));
      }
   }

   // ------------------------------------------------------------------
   // Letzte fünf Sekunden
   // ------------------------------------------------------------------

   private static void drawFinalSlam(GuiGraphicsExtractor graphics, Font font, int vw, int vh, float toImpact) {
      int second = (int) Math.ceil(toImpact);
      float fraction = toImpact - (float) Math.floor(toImpact);
      float progress = fraction == 0.0F ? 0.0F : 1.0F - fraction;
      float cx = vw / 2.0F;
      float cy = vh / 2.0F + 14;

      // roter Blitz auf jedem Sekundenwechsel
      graphics.fill(0, 0, vw, vh, argb(0xFF1010, 0.16F * (1.0F - progress)));

      float appear = easeOutBack(progress * 4.0F);
      float scale = 7.0F * (1.0F + 1.2F * (1.0F - Math.min(1.0F, appear)));
      float alpha = Mth.clamp(progress * 8.0F, 0.0F, 1.0F) * (1.0F - 0.5F * smooth((progress - 0.8F) / 0.2F)) * 0.88F;
      String digit = Integer.toString(second);

      if (progress < 0.55F) {
         float t = progress / 0.55F;
         HudFx.ring(graphics, cx, cy, Mth.lerp(easeOutCubic(t), 30.0F, vh * 0.62F), 90, 3, argb(0xFF3030, (1.0F - t) * 0.9F));
         HudFx.ring(graphics, cx, cy, Mth.lerp(easeOutCubic(t), 20.0F, vh * 0.48F), 70, 2, argb(0xFFFFFF, (1.0F - t) * 0.6F));
      }
      for (int k = 3; k >= 1; k--) {
         HudFx.bigText(graphics, font, digit, cx, cy, scale * (1.0F + 0.2F * k * (0.4F + progress)),
            argb(0xFF2030, (0.18F / k * (1.0F - progress) + 0.02F) * alpha), false);
      }
      float split = (1.0F - progress) * 7.0F;
      HudFx.bigText(graphics, font, digit, cx - split, cy, scale, argb(0x00F0FF, 0.55F * alpha), false);
      HudFx.bigText(graphics, font, digit, cx + split, cy, scale, argb(0xFF2233, 0.55F * alpha), false);
      HudFx.bigText(graphics, font, digit, cx, cy, scale, argb(HudFx.lighten(0xFF3030, 0.25F), alpha), true);
   }
}
