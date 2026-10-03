package com.oneshotonekill.client.hud;

import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.client.config.MinimapConfig;
import com.oneshotonekill.client.state.ClientStates.GrapplePullState;
import com.oneshotonekill.client.state.ClientStates.MatchStartState;
import com.oneshotonekill.client.state.ClientStates.MinimapState;
import com.oneshotonekill.client.state.ClientStates.NukeState;
import com.oneshotonekill.registry.ModItems;
import com.oneshotonekill.shared.Hologram;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import static com.oneshotonekill.client.hud.HudFx.argb;

/**
 * Das taktische Radar auf Tilted Towers.
 * <p>
 * Die rotierende Geländekarte samt Relief-Schattierung liefert {@link MinimapState}; dieser Layer zeichnet
 * alles darum herum: einen mitdrehenden Rahmen mit Gradskala und rotem Nordzeiger, Kompassbuchstaben, einen
 * Sichtkegel in Blickrichtung, einen langsamen Radar-Sweep und Ping-Ringe, Gegnerkontakte als Rauten mit
 * Höhenpfeilen (sie verblassen mit dem Alter und kleben als Randpfeile am Rand, wenn sie außerhalb liegen),
 * Item-Kisten, die Grappler-Leine zum Anker sowie darunter Höhe und Kontaktzahl.
 * <p>
 * Der Maßstab ist ein Block je GUI-Pixel; Größe und Position stammen aus {@link MinimapConfig}, alle Marker
 * skalieren mit dem Radius.
 */
@SuppressWarnings("DuplicatedCode")
public final class TiltedMinimapLayer implements HudElement {
   /** So lange (Sekunden seit Start der Nuke-Sequenz) flackert die Minimap noch, bevor sie ganz verschwindet. */
   private static final float SIGNAL_LOSS_SECONDS = 1.1F;
   private static final double SCALE = 1.0;

   private static final int CYAN = 0x00F0FF;
   private static final int PLAYER = 0x00FF9D;
   private static final int ENEMY = 0xFF2244;
   private static final int ENEMY_BELOW = 0xFF7733;
   private static final int GOLD = 0xFFC64B;
   private static final int WHITE = 0xFFFFFF;
   private static final int NORTH_RED = 0xFF3344;

   @Override
   public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
      Minecraft client = Minecraft.getInstance();
      LocalPlayer player = client.player;
      if (player == null || client.level == null) {
         return;
      }
      if (!Arena.TILTED_TOWERS.getDimension().equals(client.level.dimension())) {
         return;
      }

      MinimapState state = MinimapState.INSTANCE;
      if (!state.isMatchRunning()
         || MatchStartState.INSTANCE.isCountdownActive()
         || !Arena.TILTED_TOWERS.isInArenaColumn(player.getX(), player.getZ())) {
         return;
      }

      float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);

      // Endgame: Die Funkverbindung zur Karte reißt ab - kurzer Signalverlust, danach bleibt sie bis zum
      // Match-Stopp weg. Der Server meldet beim Stopp den leeren Nuke-Zustand, dann kehrt sie zurück.
      NukeState nuke = NukeState.INSTANCE;
      float lostSeconds = nuke.isRunning() ? nuke.seconds(partialTick) : -1.0F;
      if (lostSeconds >= SIGNAL_LOSS_SECONDS) {
         return;
      }

      MinimapConfig config = MinimapConfig.INSTANCE;
      int radius = config.getRadius();
      int cx = config.getCenterX(graphics.guiWidth(), radius);
      int cy = config.getCenterY(graphics.guiHeight(), radius);
      float k = Mth.clamp(radius / 56.0F, 0.8F, 1.6F);
      float time = Util.getMillis() / 1000.0F;

      double playerX = Mth.lerp(partialTick, player.xo, player.getX());
      double playerZ = Mth.lerp(partialTick, player.zo, player.getZ());
      float yaw = player.getViewYRot(partialTick);

      // 1. Schlagschatten hinter der Karte
      HudFx.disc(graphics, cx, cy + 1, radius + 5, argb(0x000000, 0.28F));
      HudFx.disc(graphics, cx, cy + 1, radius + 3, argb(0x000000, 0.35F));

      // 2. Rotierende Geländekarte
      state.updateRadarView(playerX, playerZ, yaw);
      if (state.isRadarViewReady()) {
         int diameter = radius * 2;
         graphics.blit(RenderPipelines.GUI_TEXTURED, MinimapState.RADAR_VIEW_ID,
            cx - radius, cy - radius, 0, 0, diameter, diameter,
            MinimapState.RADAR_TEX_SIZE, MinimapState.RADAR_TEX_SIZE,
            MinimapState.RADAR_TEX_SIZE, MinimapState.RADAR_TEX_SIZE);
      } else {
         HudFx.disc(graphics, cx, cy, radius, argb(0x0B121A, 0.95F));
      }

      // 3. Randverdunkelung gibt der Scheibe Tiefe, der Cyan-Schimmer das Radar-Gefühl
      innerVignette(graphics, cx, cy, radius);

      // 4. Entfernungsringe, Fadenkreuz
      HudFx.ring(graphics, cx, cy, radius * 0.5F, 64, 1, argb(CYAN, 0.22F));
      HudFx.ring(graphics, cx, cy, radius * 0.75F, 80, 1, argb(CYAN, 0.10F));
      graphics.fill(cx - radius + 6, cy, cx + radius - 6, cy + 1, argb(CYAN, 0.10F));
      graphics.fill(cx, cy - radius + 6, cx + 1, cy + radius - 6, argb(CYAN, 0.10F));

      // 5. Sichtkegel, Sweep, Ping
      drawViewCone(graphics, cx, cy, radius, k);
      drawSweep(graphics, cx, cy, radius, time);
      float ping = (time / 2.4F) % 1.0F;
      HudFx.ring(graphics, cx, cy, ping * radius, 72, 1, argb(CYAN, (1.0F - ping) * 0.30F));

      // 6. Welt: Kisten, Grappler-Leine, Gegner
      drawWorldObjects(graphics, client, player, cx, cy, radius, partialTick, k, time);
      int contacts = drawContacts(graphics, player, state, cx, cy, radius, partialTick, k, time);

      // 7. Rahmen, Kompass, eigener Pfeil
      drawBezel(graphics, cx, cy, radius, yaw);
      drawCompass(graphics, client.font, cx, cy, radius, yaw, k);
      drawPlayerMarker(graphics, cx, cy, k);

      // 8. Fußzeile: Höhe und Kontakte
      drawReadout(graphics, client.font, cx, cy, radius, (int) Math.floor(player.getY()), contacts);

      // 9. Signalverlust beim Start des Endgames
      if (lostSeconds >= 0.0F) {
         drawSignalLoss(graphics, client.font, cx, cy, radius, lostSeconds / SIGNAL_LOSS_SECONDS);
      }
   }

   // ------------------------------------------------------------------
   // Karte
   // ------------------------------------------------------------------

   private static void innerVignette(GuiGraphicsExtractor graphics, int cx, int cy, int radius) {
      for (int i = 0; i < 9; i++) {
         float falloff = 1.0F - i / 9.0F;
         HudFx.ring(graphics, cx, cy, radius - i, Math.max(48, (radius - i) * 3), 2, argb(0x02060C, 0.42F * falloff * falloff));
      }
      HudFx.ring(graphics, cx, cy, radius - 1, Math.max(48, radius * 3), 2, argb(CYAN, 0.10F));
   }

   /** Der Sichtkegel in Blickrichtung (nach oben): dünne Strahlen, die nach außen ausblenden. */
   private static void drawViewCone(GuiGraphicsExtractor graphics, int cx, int cy, int radius, float k) {
      int rays = 15;
      float half = (float) Math.toRadians(42.0);
      float reach = radius * 0.92F;
      for (int i = 0; i < rays; i++) {
         float t = (i / (float) (rays - 1)) * 2.0F - 1.0F;
         float angle = (float) (-Math.PI / 2.0) + t * half;
         float dx = Mth.cos(angle);
         float dy = Mth.sin(angle);
         float edge = 1.0F - Math.abs(t) * 0.45F;
         for (int part = 0; part < 3; part++) {
            float r0 = 6.0F * k + reach * part / 3.0F;
            float r1 = 6.0F * k + reach * (part + 1) / 3.0F;
            HudFx.line(graphics, cx + dx * r0, cy + dy * r0, cx + dx * r1, cy + dy * r1, 3.0F,
               argb(PLAYER, (0.075F - 0.022F * part) * edge));
         }
      }
      for (int side = -1; side <= 1; side += 2) {
         float a = (float) (-Math.PI / 2.0) + side * half;
         HudFx.line(graphics, cx + Mth.cos(a) * 6.0F * k, cy + Mth.sin(a) * 6.0F * k,
            cx + Mth.cos(a) * reach, cy + Mth.sin(a) * reach, 1.0F, argb(PLAYER, 0.22F));
      }
   }

   /** Rotierender Radar-Strahl mit Leuchtschweif. */
   private static void drawSweep(GuiGraphicsExtractor graphics, int cx, int cy, int radius, float time) {
      float angle = time * 1.9F;
      for (int i = 0; i < 16; i++) {
         float a = angle - i * 0.05F;
         HudFx.line(graphics, cx, cy, cx + Mth.cos(a) * (radius - 2), cy + Mth.sin(a) * (radius - 2),
            i == 0 ? 1.6F : 1.0F, argb(CYAN, (1.0F - i / 16.0F) * 0.20F));
      }
   }

   // ------------------------------------------------------------------
   // Rahmen und Kompass
   // ------------------------------------------------------------------

   /** Rahmen mit Gradskala, die mit der Karte mitdreht, und rotem Nordzeiger. */
   private static void drawBezel(GuiGraphicsExtractor graphics, int cx, int cy, int radius, float yaw) {
      float yawRad = (float) Math.toRadians(yaw);
      // Doppelter Ring: kräftig innen, zart außen
      circle(graphics, cx, cy, radius + 1.0F, 2.0F, argb(CYAN, 0.85F));
      circle(graphics, cx, cy, radius + 3.5F, 1.0F, argb(CYAN, 0.28F));

      // Skala alle 15 Grad, alle 45 Grad länger; N=π-yaw, im Uhrzeigersinn weiter
      for (int i = 0; i < 24; i++) {
         float angle = (float) Math.PI - yawRad + i * (float) (Math.PI / 12.0);
         boolean major = i % 6 == 0;
         boolean medium = i % 3 == 0;
         float inner = radius + 1.0F;
         float outer = radius + (major ? 6.0F : medium ? 4.5F : 3.0F);
         float sx = (float) Math.sin(angle);
         float sy = -(float) Math.cos(angle);
         HudFx.line(graphics, cx + sx * inner, cy + sy * inner, cx + sx * outer, cy + sy * outer,
            major ? 2.0F : 1.0F, argb(i == 0 ? NORTH_RED : CYAN, major ? 0.95F : 0.5F));
      }

      // Roter Nordzeiger außen am Rand
      float north = (float) Math.PI - yawRad;
      float nx = (float) Math.sin(north);
      float ny = -(float) Math.cos(north);
      float baseR = radius + 8.0F;
      float tipR = radius + 2.0F;
      float px = -ny;
      float py = nx;
      HudFx.line(graphics, cx + nx * baseR + px * 3.0F, cy + ny * baseR + py * 3.0F, cx + nx * tipR, cy + ny * tipR, 1.6F, argb(NORTH_RED, 0.95F));
      HudFx.line(graphics, cx + nx * baseR - px * 3.0F, cy + ny * baseR - py * 3.0F, cx + nx * tipR, cy + ny * tipR, 1.6F, argb(NORTH_RED, 0.95F));
      HudFx.line(graphics, cx + nx * baseR - px * 3.0F, cy + ny * baseR - py * 3.0F, cx + nx * baseR + px * 3.0F, cy + ny * baseR + py * 3.0F, 1.6F, argb(NORTH_RED, 0.95F));
   }

   private static void drawCompass(GuiGraphicsExtractor graphics, Font font, int cx, int cy, int radius, float yaw, float k) {
      float yawRad = (float) Math.toRadians(yaw);
      int dist = radius - Math.round(9.0F * k);
      cardinal(graphics, font, cx, cy, dist, (float) Math.PI - yawRad, "N", NORTH_RED);
      cardinal(graphics, font, cx, cy, dist, (float) (1.5 * Math.PI) - yawRad, "O", 0x9ED8F0);
      cardinal(graphics, font, cx, cy, dist, -yawRad, "S", 0x9ED8F0);
      cardinal(graphics, font, cx, cy, dist, (float) (0.5 * Math.PI) - yawRad, "W", 0x9ED8F0);
   }

   private static void cardinal(GuiGraphicsExtractor graphics, Font font, int cx, int cy, int dist, float angle,
                                String label, int colour) {
      float x = cx + dist * (float) Math.sin(angle);
      float y = cy - dist * (float) Math.cos(angle);
      HudFx.disc(graphics, x, y, 5.0F, argb(0x02060C, 0.55F));
      HudFx.smallText(graphics, font, label, x, y - 3.5F, 0.85F, argb(colour, 1.0F));
   }

   // ------------------------------------------------------------------
   // Inhalt
   // ------------------------------------------------------------------

   private static float[] project(double dx, double dz, double cos, double sin, int cx, int cy) {
      return new float[]{(float) (cx + (-dx * cos - dz * sin) * SCALE), (float) (cy + (dx * sin - dz * cos) * SCALE)};
   }

   private static void drawWorldObjects(GuiGraphicsExtractor graphics, Minecraft client, LocalPlayer player, int cx, int cy,
                                        int radius, float partialTick, float k, float time) {
      double px = Mth.lerp(partialTick, player.xo, player.getX());
      double pz = Mth.lerp(partialTick, player.zo, player.getZ());
      double yawRad = Math.toRadians(player.getViewYRot(partialTick));
      double cos = Math.cos(yawRad);
      double sin = Math.sin(yawRad);

      // Grappler-Leine: von der Mitte zum Anker, am Rand gekappt
      Vec3 hook = GrapplePullState.INSTANCE.hookPosition(player.getUUID(), partialTick);
      if (hook != null) {
         float[] s = project(hook.x - px, hook.z - pz, cos, sin, cx, cy);
         float dx = s[0] - cx;
         float dy = s[1] - cy;
         float dist = (float) Math.sqrt(dx * dx + dy * dy);
         float max = radius - 4.0F;
         boolean inside = dist <= max;
         float ex = inside ? s[0] : cx + dx / dist * max;
         float ey = inside ? s[1] : cy + dy / dist * max;
         HudFx.line(graphics, cx, cy, ex, ey, 1.0F, argb(CYAN, 0.45F));
         if (inside) {
            HudFx.line(graphics, ex - 3 * k, ey, ex + 3 * k, ey, 1.6F, argb(CYAN, 1.0F));
            HudFx.line(graphics, ex, ey - 3 * k, ex, ey + 3 * k, 1.6F, argb(CYAN, 1.0F));
            HudFx.ring(graphics, ex, ey, 4.5F * k, 14, 1, argb(CYAN, 0.7F));
         }
      }

      if (client.level == null) {
         return;
      }
      for (Entity entity : client.level.entitiesForRendering()) {
         if (entity instanceof Display.ItemDisplay box && Hologram.item(box).is(ModItems.ITEM_BOX)) {
            Vec3 pos = box.position();
            float[] s = project(pos.x - px, pos.z - pz, cos, sin, cx, cy);
            float dx = s[0] - cx;
            float dy = s[1] - cy;
            if (dx * dx + dy * dy > (radius - 5) * (radius - 5)) {
               continue;
            }
            float pulse = 0.5F + 0.5F * (float) Math.sin(time * 4.0F + pos.x);
            diamond(graphics, s[0], s[1], 3.2F * k, 1.6F, argb(GOLD, 1.0F));
            graphics.fill(Math.round(s[0]), Math.round(s[1]), Math.round(s[0]) + 1, Math.round(s[1]) + 1, argb(WHITE, 1.0F));
            HudFx.ring(graphics, s[0], s[1], (4.0F + 3.0F * pulse) * k, 14, 1, argb(GOLD, 0.45F * (1.0F - pulse)));
         }
      }
   }

   /** @return Anzahl der Kontakte */
   private static int drawContacts(GuiGraphicsExtractor graphics, LocalPlayer player, MinimapState state, int cx, int cy,
                                   int radius, float partialTick, float k, float time) {
      double px = Mth.lerp(partialTick, player.xo, player.getX());
      double pz = Mth.lerp(partialTick, player.zo, player.getZ());
      double yawRad = Math.toRadians(player.getViewYRot(partialTick));
      double cos = Math.cos(yawRad);
      double sin = Math.sin(yawRad);
      int count = 0;

      for (MinimapState.EnemyContact contact : state.getContacts().values()) {
         count++;
         Vec3 target = contact.pos();
         double dy = contact.dy();
         float[] s = project(target.x - px, target.z - pz, cos, sin, cx, cy);
         float dx = s[0] - cx;
         float dyScreen = s[1] - cy;
         float dist = (float) Math.sqrt(dx * dx + dyScreen * dyScreen);
         int colour = dy < -4.5 ? ENEMY_BELOW : ENEMY;

         // Kontakte verblassen kurz vor dem Ablauf
         float remaining = contact.expiryTick() - (player.tickCount + partialTick);
         float fade = Mth.clamp(remaining / 14.0F, 0.30F, 1.0F);

         float maxR = radius - 5.0F;
         if (dist > maxR) {
            // außerhalb: Pfeil am Rand, der nach außen zeigt
            float ux = dx / dist;
            float uy = dyScreen / dist;
            float ex = cx + ux * maxR;
            float ey = cy + uy * maxR;
            float tipX = ex + ux * 3.5F * k;
            float tipY = ey + uy * 3.5F * k;
            float sideX = -uy * 3.0F * k;
            float sideY = ux * 3.0F * k;
            HudFx.line(graphics, ex + sideX, ey + sideY, tipX, tipY, 1.8F, argb(colour, 0.9F * fade));
            HudFx.line(graphics, ex - sideX, ey - sideY, tipX, tipY, 1.8F, argb(colour, 0.9F * fade));
            continue;
         }

         if (contact.shooting()) {
            float ripple = ((time * 1.6F) % 1.0F);
            HudFx.ring(graphics, s[0], s[1], (4.0F + 9.0F * ripple) * k, 24, 1, argb(ENEMY, (1.0F - ripple) * 0.85F));
            HudFx.disc(graphics, s[0], s[1], 5.0F * k, argb(ENEMY, 0.18F));
         }
         diamond(graphics, s[0], s[1], 3.4F * k, 1.7F, argb(colour, fade));
         graphics.fill(Math.round(s[0]), Math.round(s[1]), Math.round(s[0]) + 1, Math.round(s[1]) + 1, argb(WHITE, fade));

         // Höhenpfeil über oder unter der Raute
         if (dy > 4.5) {
            chevron(graphics, s[0], s[1] - 7.0F * k, -1, k, argb(colour, fade));
         } else if (dy < -4.5) {
            chevron(graphics, s[0], s[1] + 7.0F * k, 1, k, argb(colour, fade));
         }
      }
      return count;
   }

   /** Kleiner Pfeil nach oben ({@code direction = -1}) oder unten ({@code 1}). */
   private static void chevron(GuiGraphicsExtractor graphics, float x, float y, int direction, float k, int colour) {
      float w = 2.8F * k;
      float h = 2.4F * k * direction;
      HudFx.line(graphics, x - w, y - h, x, y + h, 1.5F, colour);
      HudFx.line(graphics, x + w, y - h, x, y + h, 1.5F, colour);
   }

   private static void diamond(GuiGraphicsExtractor graphics, float x, float y, float size, float thickness, int colour) {
      HudFx.line(graphics, x, y - size, x + size, y, thickness, colour);
      HudFx.line(graphics, x + size, y, x, y + size, thickness, colour);
      HudFx.line(graphics, x, y + size, x - size, y, thickness, colour);
      HudFx.line(graphics, x - size, y, x, y - size, thickness, colour);
   }

   /** Der eigene Pfeil: leuchtende Spitze nach oben, dunkler Kern. */
   private static void drawPlayerMarker(GuiGraphicsExtractor graphics, int cx, int cy, float k) {
      HudFx.disc(graphics, cx, cy, 6.0F * k, argb(PLAYER, 0.18F));
      float tip = -6.5F * k;
      float wing = 4.2F * k;
      float back = 4.2F * k;
      float notch = 1.6F * k;
      int c = argb(PLAYER, 1.0F);
      HudFx.line(graphics, cx, cy + tip, cx - wing, cy + back, 2.0F, c);
      HudFx.line(graphics, cx, cy + tip, cx + wing, cy + back, 2.0F, c);
      HudFx.line(graphics, cx - wing, cy + back, cx, cy + notch, 2.0F, c);
      HudFx.line(graphics, cx + wing, cy + back, cx, cy + notch, 2.0F, c);
      HudFx.disc(graphics, cx, cy + 0.5F, 1.4F * k, argb(0x060910, 1.0F));
      graphics.fill(cx, Math.round(cy + tip) - 1, cx + 1, Math.round(cy + tip) + 1, argb(WHITE, 1.0F));
   }

   private static void drawReadout(GuiGraphicsExtractor graphics, Font font, int cx, int cy, int radius, int height, int contacts) {
      int y = cy + radius + 9;
      String text = "Y " + height + "   " + contacts + (contacts == 1 ? " CONTACT" : " CONTACTS");
      int w = Math.round(font.width(text) * 0.8F) + 12;
      graphics.fill(cx - w / 2, y - 2, cx + w / 2, y + 9, argb(0x02060C, 0.72F));
      graphics.fill(cx - w / 2, y - 2, cx + w / 2, y - 1, argb(CYAN, 0.55F));
      HudFx.smallText(graphics, font, text, cx, y, 0.8F, argb(contacts > 0 ? ENEMY : 0x9ED8F0, 1.0F));
   }

   /** Glatter Kreis aus kurzen Linien. */
   private static void circle(GuiGraphicsExtractor graphics, float cx, float cy, float radius, float thickness, int colour) {
      int segments = Math.max(48, Math.round(radius * 1.6F));
      float px = cx + radius;
      float py = cy;
      for (int i = 1; i <= segments; i++) {
         float a = (float) (i * Math.PI * 2.0 / segments);
         float x = cx + Mth.cos(a) * radius;
         float y = cy + Mth.sin(a) * radius;
         HudFx.line(graphics, px, py, x, y, thickness, colour);
         px = x;
         py = y;
      }
   }

   // ------------------------------------------------------------------
   // Signalverlust
   // ------------------------------------------------------------------

   /**
    * Die Karte stirbt: Zeilenversatz wie bei einem defekten Bildsignal, Rauschbalken, dann wird die Scheibe
    * schwarz und "KEIN SIGNAL" blinkt, bevor sie verschwindet.
    */
   private static void drawSignalLoss(GuiGraphicsExtractor graphics, Font font, int cx, int cy, int radius, float progress) {
      float k = Math.min(1.0F, progress);
      int frame = (int) (Util.getMillis() / 45L);
      int top = cy - radius;
      for (int i = 0; i < 16; i++) {
         float row = HudFx.rand(frame * 31 + i * 7);
         int y = top + Math.round(row * radius * 2);
         int h = 1 + Math.round(HudFx.rand(frame * 17 + i) * 4.0F);
         int shift = Math.round((HudFx.rand(frame * 13 + i * 3) - 0.5F) * 22.0F * k);
         int colour = HudFx.rand(frame + i * 5) > 0.5F ? 0xFFFFFF : 0x0A0F14;
         graphics.fill(cx - radius + shift, y, cx + radius + shift, y + h, argb(colour, 0.35F + 0.4F * k));
      }
      HudFx.disc(graphics, cx, cy, radius, argb(0x05080A, k * k * 0.95F));
      if (k > 0.4F && ((frame / 4) & 1) == 0) {
         HudFx.smallText(graphics, font, "KEIN SIGNAL", cx, cy - 4, 0.9F, argb(0xFF3C28, 0.95F));
      }
   }
}
