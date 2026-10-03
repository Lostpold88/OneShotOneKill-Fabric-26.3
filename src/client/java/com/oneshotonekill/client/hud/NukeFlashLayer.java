package com.oneshotonekill.client.hud;

import static com.oneshotonekill.client.hud.HudFx.argb;
import static com.oneshotonekill.client.hud.HudFx.clamp01;
import static com.oneshotonekill.client.hud.HudFx.easeOutCubic;
import static com.oneshotonekill.client.hud.HudFx.rand;
import static com.oneshotonekill.client.hud.HudFx.smooth;

import com.oneshotonekill.client.state.ClientStates.CameraShakeState;
import com.oneshotonekill.client.state.ClientStates.NukeState;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.phys.Vec3;

/**
 * Der Einschlag und alles danach.
 * <p>
 * Ablauf in Sekunden nach dem Einschlag: 0 bis 0,15 reines Weiß, dann sinkt es zu einem Glare, der an der
 * <em>echten Bildschirmposition von Ground Zero</em> sitzt (aus Blickrichtung und Sichtfeld projiziert; liegt
 * sie hinter dem Spieler, bleibt es bei der Bildmitte). Davon gehen God-Rays und Linsenreflexe aus, drei
 * Schockringe mit Farbsaum laufen nach außen, ein Feuerschleier und Hitzeflimmern ziehen über das Bild.
 * Danach bleiben Asche, Glut und der graue Fallout-Film stehen, bis das Match gestoppt wird. Ein weißes
 * Nachklingen der Ohren pulsiert langsam aus.
 * <p>
 * Asche und Glut laufen mit der Wanduhr statt mit dem Tickzähler: der Zähler bleibt am Ende der Sequenz
 * stehen, der Fallout aber soll weiter rieseln.
 */
@SuppressWarnings("DuplicatedCode")
public final class NukeFlashLayer implements HudElement {
   private static boolean shakeTriggered;
   private static boolean aftershockTriggered;

   @Override
   public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
      NukeState state = NukeState.INSTANCE;
      if (!state.isRunning() || !state.hasDetonated()) {
         shakeTriggered = false;
         aftershockTriggered = false;
         return;
      }

      Minecraft client = Minecraft.getInstance();
      int width = graphics.guiWidth();
      int height = graphics.guiHeight();
      float unit = height / 540.0F;
      float partial = deltaTracker.getGameTimeDeltaPartialTick(false);
      float since = state.secondsSinceBlast(partial);
      float ambient = Util.getMillis() / 1000.0F;

      if (!shakeTriggered) {
         CameraShakeState.INSTANCE.triggerDirect(1.15F, 48);
         shakeTriggered = true;
      }
      if (!aftershockTriggered && since > 1.8F) {
         CameraShakeState.INSTANCE.triggerDirect(0.45F, 70);
         aftershockTriggered = true;
      }

      float[] glare = project(client, state, partial, width, height);
      float gx = glare == null ? width / 2.0F : glare[0];
      float gy = glare == null ? height / 2.0F : glare[1];
      boolean visible = glare != null;

      // 1. Feuerschleier unter allem anderen
      if (since < 4.0F) {
         float fire = 1.0F - smooth((since - 0.2F) / 3.6F);
         graphics.fillGradient(0, 0, width, height, argb(0x8A1800, 0.55F * fire), argb(0xFF9A1E, 0.75F * fire));
      }

      // 2. Hitzeflimmern: horizontale Bänder, die zittern
      if (since < 7.0F) {
         float haze = 1.0F - smooth((since - 1.0F) / 6.0F);
         int bands = 30;
         for (int i = 0; i < bands; i++) {
            float y = i * height / (float) bands;
            float wobble = (float) Math.sin(ambient * 5.0F + i * 0.9F) * 6.0F * unit;
            float alpha = (0.045F + 0.03F * (float) Math.sin(ambient * 3.0F + i * 1.7F)) * haze;
            graphics.fill(Math.round(wobble), Math.round(y), width, Math.round(y + height / (float) bands * 0.55F),
               argb(0xFFD9A0, Math.max(0.0F, alpha)));
         }
      }

      // 3. Glare, God-Rays, Linsenreflexe
      float glareFade = (float) Math.exp(-since * 0.85F);
      if (since < 8.0F && glareFade > 0.01F) {
         int warm = HudFx.mix(0xFFFFFF, 0xFFA040, clamp01(since / 1.6F));
         HudFx.glow(graphics, gx, gy, height * (1.05F - 0.5F * clamp01(since / 3.0F)), warm, 0.95F * glareFade);
         HudFx.glow(graphics, gx, gy, height * 0.30F, 0xFFFFFF, 0.9F * glareFade);

         int rays = 28;
         for (int i = 0; i < rays; i++) {
            float angle = (float) (i * Math.PI * 2.0 / rays) + rand(i + 4000) * 0.22F + ambient * 0.03F;
            float length = height * (0.55F + 0.9F * rand(i + 4100));
            float thickness = 1.0F + 4.0F * rand(i + 4200);
            HudFx.line(graphics, gx, gy, gx + Mth.cos(angle) * length, gy + Mth.sin(angle) * length, thickness,
               argb(warm, 0.20F * glareFade * (0.4F + 0.6F * rand(i + 4300))));
         }

         if (visible) {
            float cx = width / 2.0F;
            float cy = height / 2.0F;
            for (int i = 1; i <= 4; i++) {
               float t = i / 4.0F * 1.6F - 0.3F;
               float fx = Mth.lerp(t, gx, cx - (gx - cx));
               float fy = Mth.lerp(t, gy, cy - (gy - cy));
               HudFx.glow(graphics, fx, fy, height * (0.04F + 0.025F * i), i % 2 == 0 ? 0x66CCFF : 0xFFAA55,
                  0.35F * glareFade);
            }
         }
      }

      // 4. Schockringe mit Farbsaum (rot, grün, blau leicht versetzt)
      for (int k = 0; k < 3; k++) {
         float t = since - k * 0.18F;
         if (t <= 0.0F || t > 2.2F) {
            continue;
         }
         float share = t / 2.2F;
         float radius = easeOutCubic(share) * Math.max(width, height) * (0.95F + 0.2F * k);
         float alpha = (1.0F - share) * 0.85F;
         int size = Math.max(2, Math.round((5.0F - 3.0F * share) * Math.max(1.0F, unit * 1.4F)));
         int dots = 200;
         HudFx.ring(graphics, gx, gy, radius * 1.012F, dots, size, argb(0xFF3030, alpha * 0.7F));
         HudFx.ring(graphics, gx, gy, radius, dots, size, argb(0xFFFFFF, alpha));
         HudFx.ring(graphics, gx, gy, radius * 0.988F, dots, size, argb(0x3070FF, alpha * 0.7F));
      }

      // 5. Weißer Blitz: zuerst reines Weiß, dann ein schneller Abfall
      float flash = since < 0.15F ? 1.0F : 1.0F - smooth((since - 0.15F) / 0.55F);
      if (flash > 0.004F) {
         graphics.fill(0, 0, width, height, argb(since < 0.08F ? 0xFFFFFF : 0xF2F8FF, flash));
      }

      // 6. Fallout: dunkle Ränder, grauer Film, rieselnde Asche, steigende Glut
      float falloutIn = clamp01(since / 2.0F);
      graphics.fill(0, 0, width, height, argb(0x625C55, 0.30F * falloutIn));
      HudFx.vignette(graphics, width, height, 0x000000, 0.55F * falloutIn);
      HudFx.vignette(graphics, width, height, 0xFF5A10, 0.22F * falloutIn * (0.8F + 0.2F * (float) Math.sin(ambient * 2.3F)));

      if (since > 0.6F) {
         drawAsh(graphics, width, height, unit, ambient, clamp01((since - 0.6F) / 2.0F));
         drawEmbers(graphics, width, height, unit, ambient, clamp01((since - 0.9F) / 2.0F));
      }

      // 7. Ohrensausen: ein weißes Pulsieren am Rand, das über Sekunden verebbt
      float ringing = (float) Math.exp(-since * 0.35F) * (0.65F + 0.35F * (float) Math.sin(since * 6.0F));
      HudFx.vignette(graphics, width, height, 0xFFFFFF, 0.28F * ringing * smooth((since - 0.4F) / 0.5F));
   }

   /** Asche, die schräg im Wind fällt - dichter nahe dem Boden, langsame und schnelle Flocken gemischt. */
   private static void drawAsh(GuiGraphicsExtractor graphics, int width, int height, float unit, float time, float fade) {
      int flakes = 140;
      for (int i = 0; i < flakes; i++) {
         float speed = (18.0F + 52.0F * rand(i + 100)) * unit;
         float y = (time * speed + rand(i + 101) * height) % (height + 20.0F) - 10.0F;
         float sway = (float) Math.sin(time * (0.8F + 1.6F * rand(i + 102)) + rand(i + 103) * 6.28F) * 14.0F * unit;
         float x = (rand(i + 104) * (width + 80.0F) + time * 10.0F * unit) % (width + 80.0F) - 40.0F + sway;
         float size = (1.0F + 2.2F * rand(i + 105)) * Math.max(1.0F, unit);
         float shade = rand(i + 106);
         int colour = HudFx.mix(0x5A544E, 0xC9C2B8, shade);
         HudFx.rect(graphics, x, y, x + size, y + size, argb(colour, (0.30F + 0.45F * rand(i + 107)) * fade));
      }
   }

   /** Glühende Funken, die vom unteren Rand aufsteigen und dabei flackern. */
   private static void drawEmbers(GuiGraphicsExtractor graphics, int width, int height, float unit, float time, float fade) {
      int embers = 70;
      for (int i = 0; i < embers; i++) {
         float speed = (22.0F + 60.0F * rand(i + 200)) * unit;
         float travel = (time * speed + rand(i + 201) * height) % (height * 0.9F);
         float y = height - travel;
         float x = rand(i + 202) * width + (float) Math.sin(time * (1.0F + rand(i + 203)) + i) * 16.0F * unit;
         float life = 1.0F - travel / (height * 0.9F);
         float flicker = 0.6F + 0.4F * (float) Math.sin(time * 14.0F + i * 2.3F);
         float size = (1.0F + 1.8F * rand(i + 204)) * Math.max(1.0F, unit);
         int colour = HudFx.mix(0xFF4A10, 0xFFD060, rand(i + 205));
         HudFx.rect(graphics, x, y, x + size, y + size, argb(colour, life * life * flicker * 0.9F * fade));
      }
   }

   /**
    * Projiziert Ground Zero in Bildschirmkoordinaten.
    * <p>
    * Aus Augenposition, Blickwinkeln und senkrechtem Sichtfeld wird die Richtung zum Pilz in die
    * Kamerabasis (vorne, rechts, oben) zerlegt und perspektivisch geteilt. Minecrafts Sichtfeld-Option ist
    * das vertikale; das waagerechte folgt aus dem Seitenverhältnis.
    *
    * @return {x, y} in Pixeln oder {@code null}, wenn der Punkt hinter dem Spieler liegt.
    */
   private static float[] project(Minecraft client, NukeState state, float partial, int width, int height) {
      LocalPlayer player = client.player;
      if (player == null) {
         return null;
      }
      Vec3 eye = player.getEyePosition(partial);
      double dx = state.centreX() - eye.x;
      double dy = state.centreY() + 8.0 - eye.y;
      double dz = state.centreZ() - eye.z;

      double yaw = Math.toRadians(player.getViewYRot(partial));
      double pitch = Math.toRadians(player.getViewXRot(partial));
      double fx = -Math.sin(yaw) * Math.cos(pitch);
      double fy = -Math.sin(pitch);
      double fz = Math.cos(yaw) * Math.cos(pitch);
      double rx = -Math.cos(yaw);
      double rz = -Math.sin(yaw);
      double ux = -rz * fy;
      double uy = rz * fx - rx * fz;
      double uz = rx * fy;

      double depth = dx * fx + dy * fy + dz * fz;
      if (depth < 0.5) {
         return null;
      }
      double tanHalf = Math.tan(Math.toRadians(client.options.fov().get()) / 2.0);
      double ndcX = (dx * rx + dz * rz) / depth / (tanHalf * width / (double) height);
      double ndcY = (dx * ux + dy * uy + dz * uz) / depth / tanHalf;
      return new float[]{(float) (width / 2.0 + ndcX * width / 2.0), (float) (height / 2.0 - ndcY * height / 2.0)};
   }
}
