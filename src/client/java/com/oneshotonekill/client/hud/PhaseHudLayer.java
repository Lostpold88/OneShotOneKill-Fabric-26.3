package com.oneshotonekill.client.hud;

import com.oneshotonekill.shared.PhaseFields;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

/**
 * Bildschirmanzeige der Phasen-Granate für ihren Werfer, solange er in der Kugel steht.
 * <p>
 * Ein cyanfarbener Rand, der atmet, und ein schmaler Balken oben mit der Restzeit. In den letzten
 * drei Sekunden wird beides rot und flackert – dann ist es Zeit, die Wand zu verlassen.
 */
public final class PhaseHudLayer implements HudElement {
   private static final float DURATION_TICKS = 300.0F;
   private static final float WARNING_TICKS = 60.0F;
   private static final int BAR_WIDTH = 120;
   private static final int BAR_HEIGHT = 3;
   private static final int BAR_TOP = 14;

   private PhaseHudLayer() {
   }

   public static void register() {
      HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("oneshotonekill", "phase_screen_fx"),
         new PhaseHudLayer());
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
      Minecraft client = Minecraft.getInstance();
      if (client.player == null || client.level == null) {
         return;
      }
      PhaseFields.Zone zone = PhaseFields.CLIENT.find(client.player.getUUID());
      if (zone == null || client.player.getEyePosition().distanceToSqr(zone.centre()) > zone.radius() * zone.radius()) {
         return;
      }

      float left = zone.endsAt() - client.level.getGameTime() - deltaTracker.getGameTimeDeltaPartialTick(false);
      if (left <= 0.0F) {
         return;
      }
      boolean warning = left <= WARNING_TICKS;
      float time = client.level.getGameTime() + deltaTracker.getGameTimeDeltaPartialTick(false);
      float breath = 0.5F + 0.5F * (float) Math.sin(time * (warning ? 0.9F : 0.18F));
      if (warning && (int) (time / 2.0F) % 3 == 0) {
         breath *= 0.35F;
      }

      int r = warning ? 255 : 60;
      int g = warning ? 74 : 240;
      int b = warning ? 60 : 255;
      int width = graphics.guiWidth();
      int height = graphics.guiHeight();

      // Rand: vier Verläufe von außen nach innen, getrennt nach Breite und Höhe, damit er Ecken füllt.
      int alpha = (int) (34 + 40 * breath);
      int edge = Math.max(18, height / 7);
      int side = Math.max(18, width / 9);
      graphics.fillGradient(0, 0, width, edge, ARGB.color(alpha, r, g, b), ARGB.color(0, r, g, b));
      graphics.fillGradient(0, height - edge, width, height, ARGB.color(0, r, g, b), ARGB.color(alpha, r, g, b));
      for (int step = 0; step < side; step += 3) {
         int a = (int) (alpha * 0.8F * (1.0F - step / (float) side));
         graphics.fill(step, 0, step + 3, height, ARGB.color(a, r, g, b));
         graphics.fill(width - step - 3, 0, width - step, height, ARGB.color(a, r, g, b));
      }

      // Abtastlinie: eine helle Zeile wandert in sieben Sekunden einmal über das Bild.
      int scan = (int) ((time % 140.0F) / 140.0F * height);
      graphics.fill(0, scan, width, scan + 1, ARGB.color(26, r, g, b));

      // Restzeit.
      int barX = (width - BAR_WIDTH) / 2;
      int filled = Math.round(BAR_WIDTH * Math.min(1.0F, left / DURATION_TICKS));
      graphics.fill(barX - 1, BAR_TOP - 1, barX + BAR_WIDTH + 1, BAR_TOP + BAR_HEIGHT + 1, ARGB.color(120, 0, 0, 0));
      graphics.fill(barX, BAR_TOP, barX + filled, BAR_TOP + BAR_HEIGHT, ARGB.color(230, r, g, b));
   }
}
