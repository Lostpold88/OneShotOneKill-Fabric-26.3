package com.oneshotonekill.client.hud;

import com.oneshotonekill.client.effect.TimeDistortionEffects;
import com.oneshotonekill.client.screen.OsokWidgets;
import java.util.Locale;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Util;

/** HUD-Ebene des Zeitverzerrers: ein abbauender Ring mit der verbleibenden Echtzeit. */
public final class ChronoHudLayers {
   private ChronoHudLayers() {}

   /**
    * Die Restzeit stand bisher nur einmal beim Start in der Aktionsleiste – wer den Ruf verpasste,
    * wusste bis zum Rückschlag nicht, wie lange die Zeitlupe noch trägt.
    *
    * <p>Alles hier hängt an echter Zeit aus {@code TimeDistortionEffects}, nicht am Spieltakt:
    * Während der Zeitlupe läuft auch der Client nur mit acht Ticks je Sekunde, ein tickbasierter
    * Ring liefe sichtbar ruckelnd.</p>
    */
   public static final class TimeDistortionLayer implements HudElement {
      private static final int RADIUS = 19;
      private static final int SEGMENTS = 56;
      private static final int TOP_MARGIN = 26;
      private static final int TRACK = 0x33101828;

      @Override
      public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
         Minecraft client = Minecraft.getInstance();
         TimeDistortionEffects effects = TimeDistortionEffects.INSTANCE;
         if (client.player == null || !effects.isVisible()) {
            return;
         }

         float presence = effects.modelActivePower();
         if (presence <= 0.01F) {
            return;
         }

         boolean active = effects.isActive();
         float remaining = effects.remainingFraction();
         float urgency = effects.modelUrgency();
         float flash = effects.modelRestoreFlash();
         int accent = urgency < 0.5F
            ? blend(OsokWidgets.COLOR_CYAN, OsokWidgets.COLOR_GOLD, urgency * 2.0F)
            : blend(OsokWidgets.COLOR_GOLD, OsokWidgets.COLOR_CRIMSON, (urgency - 0.5F) * 2.0F);

         int centreX = graphics.guiWidth() / 2;
         int centreY = TOP_MARGIN + RADIUS;
         double seconds = Util.getNanos() / 1_000_000_000.0;

         // Laufband: eine Umdrehung je Sekunde, damit der Ring auch im Dauerzustand lebt.
         double sweep = seconds % 1.0;
         for (int i = 0; i < SEGMENTS; i++) {
            float slot = i / (float) SEGMENTS;
            double angle = slot * Math.PI * 2.0 - Math.PI * 0.5;
            int x = centreX + (int) Math.round(Math.cos(angle) * RADIUS);
            int y = centreY + (int) Math.round(Math.sin(angle) * RADIUS);

            boolean spent = active && slot > remaining;
            float sweepDistance = Math.abs(slot - (float) sweep);
            sweepDistance = Math.min(sweepDistance, 1.0F - sweepDistance);
            float glow = Math.max(0.0F, 1.0F - sweepDistance * 9.0F);

            int colour = spent ? TRACK : withAlpha(accent, (int) (150 + 105 * glow));
            int size = spent ? 1 : (glow > 0.45F ? 2 : 1);
            graphics.fill(x - size, y - size, x + size + 1, y + size + 1, fade(colour, presence));
         }

         // Vier Ecken, die mit der Dringlichkeit nach innen wandern.
         int inset = RADIUS + 7 - (int) (urgency * 4.0F);
         for (int corner = 0; corner < 4; corner++) {
            int signX = (corner & 1) == 0 ? -1 : 1;
            int signY = (corner & 2) == 0 ? -1 : 1;
            int x = centreX + signX * inset;
            int y = centreY + signY * inset;
            int bracket = fade(withAlpha(accent, 200), presence);
            graphics.fill(x - (signX > 0 ? 4 : 0), y, x + (signX > 0 ? 0 : 4), y + 1, bracket);
            graphics.fill(x, y - (signY > 0 ? 4 : 0), x + 1, y + (signY > 0 ? 0 : 4), bracket);
         }

         Font font = client.font;
         String value = active
            ? String.format(Locale.ROOT, "%.1f", effects.remainingSeconds())
            : "0.0";
         int textColour = flash > 0.0F
            ? blend(accent, 0xFFFFFFFF, flash)
            : accent;
         graphics.text(font, value, centreX - font.width(value) / 2, centreY - 7,
            fade(textColour, presence));
         String label = "ZEITBRUCH";
         graphics.text(font, label, centreX - font.width(label) / 2, centreY + 2,
            fade(withAlpha(OsokWidgets.COLOR_TEXT_MUTED, 210), presence));
      }

      private static int withAlpha(int colour, int alpha) {
         return (colour & 0x00FFFFFF) | (Math.clamp(alpha, 0, 255) << 24);
      }

      private static int fade(int colour, float amount) {
         int alpha = (int) ((colour >>> 24) * Math.clamp(amount, 0.0F, 1.0F));
         return (colour & 0x00FFFFFF) | (alpha << 24);
      }

      private static int blend(int from, int to, float amount) {
         float t = Math.clamp(amount, 0.0F, 1.0F);
         int alpha = lerp(from >>> 24, to >>> 24, t);
         int red = lerp(from >> 16 & 0xFF, to >> 16 & 0xFF, t);
         int green = lerp(from >> 8 & 0xFF, to >> 8 & 0xFF, t);
         int blue = lerp(from & 0xFF, to & 0xFF, t);
         return alpha << 24 | red << 16 | green << 8 | blue;
      }

      private static int lerp(int from, int to, float amount) {
         return from + (int) ((to - from) * amount);
      }
   }
}
