package com.oneshotonekill.client.hud;

import com.oneshotonekill.client.state.ClientStates.GrapplePullState;
import com.oneshotonekill.registry.ModItems;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.Locale;

import static com.oneshotonekill.client.hud.HudFx.*;

/**
 * Das Instrumenten-HUD des Enterhakens.
 * <p>
 * Aufbau, von außen nach innen:
 * <ul>
 *   <li><b>Kinetik-Schicht</b> (nur im Zug): Randglühen und radiale Speedlines, die mit Tempo und Nähe zum Anker
 *       länger, heller und wärmer werden.</li>
 *   <li><b>Anker-Marker</b>: Ein Rautenmarker folgt dem Haken beziehungsweise dem anvisierten Punkt in der Welt
 *       (aus Blickrichtung projiziert), mit Entfernung. Liegt der Anker außerhalb des Bildes, zeigt ein Pfeil am
 *       Bildrand die Richtung.</li>
 *   <li><b>Fadenkreuz</b>: Zielerfassung mit zuziehenden Klammern, kreisenden Bögen im Zug, Sonar-Ringen im Flug.</li>
 *   <li><b>Bogenanzeigen</b>: links die Entfernung, rechts Zugkraft und Tempo, in Farbverlauf von Cyan über Gelb
 *       nach Rot.</li>
 *   <li><b>Statuskopf</b> und <b>Druckzellen</b> (Ladungen) darunter.</li>
 * </ul>
 * Der Einrast-Moment (Anker sitzt) und das Lösen bekommen je einen eigenen Effekt: Druckwelle, Funken, Blitz.
 * <p>
 * Gezeichnet wird in einem Entwurfsraum von 480 x 270 Einheiten, auf den Bildschirm skaliert. Alle Animationen
 * hängen an der Wanduhr und am Tickzähler des Spielers; der Layer hält nur Zeitstempel, keine Partikelzustände.
 */
@SuppressWarnings("DuplicatedCode")
public final class GrapplingHookHudLayer implements HudElement {
   private static final double MAX_RANGE = 512.0;
   private static final double RELEASE_DISTANCE = 2.35;
   private static final float MAX_PULL_SPEED = 1.15F;
   private static final long LOCK_NANOS = 180_000_000L;
   private static final long ARM_NANOS = 200_000_000L;
   private static final long LATCH_NANOS = 750_000_000L;
   private static final long RELEASE_NANOS = 420_000_000L;
   private static final float TWO_PI = (float) (Math.PI * 2.0);

   private static final int CYAN = 0x2FE6FF;
   private static final int AMBER = 0xFFB020;
   private static final int RED = 0xFF3355;
   private static final int IDLE = 0x7C8CA3;
   private static final int WHITE = 0xFFFFFF;

   private boolean previousActive;
   private boolean previousPulling;
   private boolean previousLocked;
   private long armStartedAt = -1L;
   private long lockStartedAt = -1L;
   private long latchStartedAt = -1L;
   private long releaseStartedAt = -1L;
   private float lastPullSpeed;
   private float smoothTension;

   @Override
   public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
      Minecraft client = Minecraft.getInstance();
      LocalPlayer player = client.player;
      if (player == null || !client.options.getCameraType().isFirstPerson()) {
         reset();
         return;
      }
      boolean mainHand = player.getMainHandItem().is(ModItems.GRAPPLING_HOOK);
      if (!mainHand && !player.getOffhandItem().is(ModItems.GRAPPLING_HOOK)) {
         reset();
         return;
      }

      float partial = deltaTracker.getGameTimeDeltaPartialTick(false);
      int width = graphics.guiWidth();
      int height = graphics.guiHeight();
      float ui = HudFx.uiScale(width, height);
      float vw = width / ui;
      float vh = height / ui;
      float cx = vw / 2.0F;
      float cy = vh / 2.0F;

      // Raycast auf die anvisierte Fläche
      Vec3 eye = player.getEyePosition(partial);
      Vec3 look = player.getViewVector(partial);
      BlockHitResult hit = player.level().clip(new ClipContext(eye, eye.add(look.scale(MAX_RANGE)),
         ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
      boolean hitSolid = hit.getType() != HitResult.Type.MISS;
      Vec3 aimPoint = hitSolid ? hit.getLocation() : null;
      double aimDistance = hitSolid ? eye.distanceTo(aimPoint) : MAX_RANGE;
      boolean inRange = hitSolid;

      GrapplePullState pulls = GrapplePullState.INSTANCE;
      boolean active = pulls.isGrappleActive(player.getUUID());
      boolean pulling = pulls.isPulling(player.getUUID());
      boolean retracting = pulls.isRetracting(player.getUUID());
      Vec3 hook = pulls.hookPosition(player.getUUID(), partial);
      double distance = active && hook != null ? eye.distanceTo(hook) : aimDistance;
      float pullSpeed = (float) player.getDeltaMovement().length();

      long now = System.nanoTime();
      float kineticBlend = updateTimers(now, active, pulling, inRange, distance, pullSpeed);

      ItemStack item = mainHand ? player.getMainHandItem() : player.getOffhandItem();
      boolean infinite = item.has(DataComponents.UNBREAKABLE);
      int maxCharges = item.getMaxDamage();
      int charges = infinite ? maxCharges : Math.max(0, maxCharges - item.getDamageValue());

      float time = player.tickCount + partial;
      float speedShare = clamp01(pullSpeed / MAX_PULL_SPEED);
      float proximity = clamp01((float) ((8.0 - distance) / (8.0 - RELEASE_DISTANCE)));
      float tensionTarget = pulling
         ? clamp01(speedShare * 0.72F + proximity * 0.28F)
         : active ? clamp01((float) (distance / MAX_RANGE) * 0.55F + 0.08F) : 0.0F;
      smoothTension += (tensionTarget - smoothTension) * 0.22F;
      int heat = heat(smoothTension);

      graphics.pose().pushMatrix();
      graphics.pose().scale(ui, ui);

      drawKinetics(graphics, vw, vh, cx, cy, time, lastPullSpeed > pullSpeed && !active ? lastPullSpeed / MAX_PULL_SPEED : speedShare,
         proximity, kineticBlend, heat, pulling);

      // Anker-Marker: der Haken selbst, im Zielmodus der anvisierte Punkt (nur wenn er nicht unter dem Fadenkreuz liegt)
      Vec3 markerPoint = active && hook != null ? hook : null;
      if (markerPoint != null) {
         drawAnchorMarker(graphics, client.font, client, markerPoint, partial, width, height, ui, vw, vh, distance, pulling, time, heat);
      }

      drawReticle(graphics, cx, cy, time, now, active, pulling, retracting, inRange, proximity, heat);
      drawGauges(graphics, client.font, cx, cy, distance, active || inRange, smoothTension, pullSpeed * 20.0F, active, heat, time);
      drawHeader(graphics, client.font, cx, cy - 52.0F, active, pulling, retracting, inRange, heat, time);
      drawCharges(graphics, client.font, cx, cy + 50.0F, charges, maxCharges, infinite, time);
      drawBursts(graphics, cx, cy, vw, vh, now, heat);

      graphics.pose().popMatrix();

      previousActive = active;
      previousPulling = pulling;
      previousLocked = inRange;
   }

   // ------------------------------------------------------------------
   // Zeitstempel
   // ------------------------------------------------------------------

   private float updateTimers(long now, boolean active, boolean pulling, boolean inRange, double distance, float speed) {
      if (active && !previousActive) {
         armStartedAt = now;
      }
      if (pulling && !previousPulling) {
         latchStartedAt = now;
      }
      if (inRange && !previousLocked) {
         lockStartedAt = now;
      }
      if (!active && previousActive) {
         releaseStartedAt = now;
      }
      if (active) {
         releaseStartedAt = -1L;
         lastPullSpeed = speed;
         return easeOutCubic(progress(now, armStartedAt, ARM_NANOS));
      }
      if (releaseStartedAt >= 0L) {
         float blend = 1.0F - progress(now, releaseStartedAt, RELEASE_NANOS);
         if (blend > 0.0F) {
            return blend;
         }
         releaseStartedAt = -1L;
      }
      return 0.0F;
   }

   private void reset() {
      previousActive = false;
      previousPulling = false;
      previousLocked = false;
      armStartedAt = -1L;
      lockStartedAt = -1L;
      latchStartedAt = -1L;
      releaseStartedAt = -1L;
      lastPullSpeed = 0.0F;
      smoothTension = 0.0F;
   }

   private static float progress(long now, long startedAt, long duration) {
      return startedAt < 0L ? 1.0F : clamp01((float) (now - startedAt) / duration);
   }

   /** Cyan bei geringer Spannung, über Gelb nach Rot bei voller Last. */
   private static int heat(float tension) {
      return tension < 0.5F
         ? HudFx.mix(CYAN, AMBER, tension * 2.0F)
         : HudFx.mix(AMBER, RED, (tension - 0.5F) * 2.0F);
   }

   // ------------------------------------------------------------------
   // Kinetik-Schicht
   // ------------------------------------------------------------------

   private void drawKinetics(GuiGraphicsExtractor graphics, float vw, float vh, float cx, float cy, float time,
                             float speed, float proximity, float blend, int colour, boolean pulling) {
      if (blend <= 0.01F) {
         return;
      }
      float intensity = blend * (0.30F + speed * 0.70F);
      if (pulling || speed > 0.05F) {
         HudFx.vignette(graphics, Math.round(vw), Math.round(vh), colour, (0.04F + 0.30F * speed) * blend);
      }

      float inner = Math.min(vw, vh) * 0.17F;
      float outer = Math.min(vw, vh) * 0.62F;
      int streaks = 44;
      for (int i = 0; i < streaks; i++) {
         float angle = i * TWO_PI / streaks + Mth.sin(time * 0.012F + i * 1.7F) * 0.03F;
         float travel = fraction(time * (0.035F + speed * 0.11F) + rand(i + 31) * 1.7F);
         float head = outer - travel * (outer - inner);
         float length = 6.0F + speed * 34.0F + proximity * 10.0F;
         float visibility = Mth.sin(travel * (float) Math.PI);
         float base = (0.10F + visibility * 0.55F) * intensity;
         float cos = Mth.cos(angle);
         float sin = Mth.sin(angle);
         // drei Abschnitte, zum Kopf hin heller und dicker - ein Verlauf ohne Verlaufsfunktion
         for (int part = 0; part < 3; part++) {
            float r0 = head + length * (1.0F - part / 3.0F);
            float r1 = head + length * (1.0F - (part + 1) / 3.0F);
            float alpha = base * (0.25F + 0.375F * part);
            HudFx.line(graphics, cx + cos * r0, cy + sin * r0, cx + cos * r1, cy + sin * r1,
               part == 2 ? 1.6F : 1.0F, argb(part == 2 ? HudFx.lighten(colour, 0.45F) : colour, alpha));
         }
      }

      // Fahrtwind an den Seiten
      if (speed > 0.35F) {
         for (int i = 0; i < 16; i++) {
            float y = rand(i + 701) * vh;
            float length = 8.0F + 36.0F * speed * (0.4F + rand(i + 702));
            float x = fraction(time * (0.05F + 0.12F * speed) * (0.5F + rand(i + 703)) + rand(i + 704)) * vw * 0.22F;
            float alpha = 0.32F * speed * blend;
            HudFx.line(graphics, x, y, x + length, y, 1.0F, argb(WHITE, alpha));
            HudFx.line(graphics, vw - x, y, vw - x - length, y, 1.0F, argb(WHITE, alpha));
         }
      }
   }

   // ------------------------------------------------------------------
   // Anker-Marker
   // ------------------------------------------------------------------

   private void drawAnchorMarker(GuiGraphicsExtractor graphics, Font font, Minecraft client, Vec3 point, float partial,
                                 int width, int height, float ui, float vw, float vh, double distance,
                                 boolean pulling, float time, int colour) {
      HudFx.Projection projection = HudFx.project(client, point, partial, width, height);
      if (projection == null) {
         return;
      }
      String label = distance < 100.0 ? String.format(Locale.ROOT, "%.1f M", distance) : Math.round(distance) + " M";
      float margin = 20.0F;

      if (projection.inFront()) {
         float x = projection.x() / ui;
         float y = projection.y() / ui;
         if (x >= margin && x <= vw - margin && y >= margin && y <= vh - margin) {
            drawDiamond(graphics, x, y, 6.0F + (pulling ? 0.0F : 1.5F * (0.5F + 0.5F * Mth.sin(time * 0.5F))), time, colour);
            HudFx.ring(graphics, x, y, 11.0F + 3.0F * fraction(time * 0.06F), 18, 1, argb(colour, 0.55F * (1.0F - fraction(time * 0.06F))));
            HudFx.smallText(graphics, font, label, x, y + 12.0F, 0.85F, argb(WHITE, 0.95F));
            return;
         }
      }

      // Außerhalb des Bildes: Pfeil am Rand, der zum Anker zeigt
      float dx = projection.right();
      float dy = -projection.up();
      float length = (float) Math.sqrt(dx * dx + dy * dy);
      if (length < 1.0E-4F) {
         dx = 0.0F;
         dy = 1.0F;
         length = 1.0F;
      }
      dx /= length;
      dy /= length;
      float halfW = vw / 2.0F - margin;
      float halfH = vh / 2.0F - margin;
      float scale = Math.min(Math.abs(dx) < 1.0E-4F ? Float.MAX_VALUE : halfW / Math.abs(dx),
         Math.abs(dy) < 1.0E-4F ? Float.MAX_VALUE : halfH / Math.abs(dy));
      float ax = vw / 2.0F + dx * scale;
      float ay = vh / 2.0F + dy * scale;
      float pulse = 0.65F + 0.35F * Mth.sin(time * 0.5F);
      float angle = (float) Math.atan2(dy, dx);
      for (int wing = -1; wing <= 1; wing += 2) {
         float a = angle + (float) Math.PI + wing * 0.55F;
         HudFx.line(graphics, ax, ay, ax + Mth.cos(a) * 9.0F, ay + Mth.sin(a) * 9.0F, 2.0F, argb(colour, pulse));
      }
      HudFx.line(graphics, ax - dx * 5.0F, ay - dy * 5.0F, ax, ay, 1.0F, argb(colour, pulse * 0.6F));
      float tx = Mth.clamp(ax - dx * 18.0F, 30.0F, vw - 30.0F);
      float ty = Mth.clamp(ay - dy * 14.0F - 3.0F, 12.0F, vh - 12.0F);
      HudFx.smallText(graphics, font, label, tx, ty, 0.8F, argb(WHITE, 0.9F));
   }

   private static void drawDiamond(GuiGraphicsExtractor graphics, float x, float y, float size, float time, int colour) {
      float wobble = Mth.sin(time * 0.1F) * 0.06F;
      for (int i = 0; i < 4; i++) {
         float a0 = i * TWO_PI / 4.0F + wobble;
         float a1 = (i + 1) * TWO_PI / 4.0F + wobble;
         HudFx.line(graphics, x + Mth.cos(a0) * size, y + Mth.sin(a0) * size,
            x + Mth.cos(a1) * size, y + Mth.sin(a1) * size, 2.0F, argb(colour, 0.95F));
      }
      graphics.fill(Math.round(x) - 1, Math.round(y) - 1, Math.round(x) + 2, Math.round(y) + 2, argb(WHITE, 1.0F));
   }

   // ------------------------------------------------------------------
   // Fadenkreuz
   // ------------------------------------------------------------------

   private void drawReticle(GuiGraphicsExtractor graphics, float cx, float cy, float time, long now,
                            boolean active, boolean pulling, boolean retracting, boolean inRange,
                            float proximity, int heat) {
      float lock = inRange && !active ? easeOutCubic(progress(now, lockStartedAt, LOCK_NANOS)) : 0.0F;
      int base = active ? heat : inRange ? CYAN : IDLE;
      float alpha = active || inRange ? 0.95F : 0.55F;

      if (pulling) {
         // Anker sitzt: drei Bögen kreisen gegen die Zeit und ziehen sich mit der Nähe zusammen
         float radius = 15.0F - proximity * 5.0F + 1.2F * (0.5F + 0.5F * Mth.sin(time * 0.6F));
         arc(graphics, cx, cy, radius, -time * 0.05F, -time * 0.05F + 1.6F, 10, 2.0F, argb(base, alpha));
         arc(graphics, cx, cy, radius, -time * 0.05F + 2.1F, -time * 0.05F + 3.7F, 10, 2.0F, argb(base, alpha));
         arc(graphics, cx, cy, radius, -time * 0.05F + 4.2F, -time * 0.05F + 5.8F, 10, 2.0F, argb(base, alpha));
         arc(graphics, cx, cy, 8.0F, time * 0.09F, time * 0.09F + 2.2F, 8, 1.2F, argb(WHITE, 0.75F));
         bracket(graphics, cx, cy, 8.0F + proximity * 2.0F, 3.0F, argb(HudFx.lighten(base, 0.3F), 0.9F));
      } else if (retracting) {
         float phase = fraction(time * 0.09F);
         arc(graphics, cx, cy, 26.0F - phase * 16.0F, -time * 0.06F, -time * 0.06F + 4.4F, 22, 1.6F,
            argb(AMBER, (1.0F - phase * 0.5F) * 0.85F));
         bracket(graphics, cx, cy, 11.0F, 3.5F, argb(AMBER, 0.8F));
      } else if (active) {
         // Haken fliegt: Sonar-Ringe laufen nach außen, die Klammern stehen als Raute
         for (int ring = 0; ring < 2; ring++) {
            float phase = fraction(time * 0.07F + ring * 0.5F);
            arc(graphics, cx, cy, 8.0F + phase * 24.0F, time * 0.03F, time * 0.03F + TWO_PI * 0.92F, 28, 1.2F,
               argb(base, (1.0F - phase) * 0.8F));
         }
         drawDiamond(graphics, cx, cy, 6.5F, time, base);
      } else {
         // Bereit: Klammern ziehen sich beim Erfassen eines Ziels zu
         float distance = Mth.lerp(lock, 15.0F, 10.0F) + (inRange ? 0.8F * Mth.sin(time * 0.3F) : 0.0F);
         bracket(graphics, cx, cy, distance, 4.0F + 2.0F * lock, argb(base, alpha));
         if (inRange) {
            arc(graphics, cx, cy, 7.5F, time * 0.045F, time * 0.045F + TWO_PI * 0.78F, 22, 1.0F, argb(base, 0.55F));
         }
         if (lock > 0.0F && lock < 1.0F) {
            HudFx.ring(graphics, cx, cy, 10.0F + 12.0F * (1.0F - lock), 24, 1, argb(WHITE, (1.0F - lock) * 0.8F));
         }
      }

      int core = active || inRange ? WHITE : IDLE;
      graphics.fill(Math.round(cx) - 1, Math.round(cy) - 1, Math.round(cx) + 1, Math.round(cy) + 1, argb(core, 0.95F));
   }

   /** Vier Eckklammern um den Mittelpunkt. */
   private static void bracket(GuiGraphicsExtractor graphics, float cx, float cy, float distance, float length, int colour) {
      for (int sx = -1; sx <= 1; sx += 2) {
         for (int sy = -1; sy <= 1; sy += 2) {
            float x = cx + sx * distance;
            float y = cy + sy * distance;
            HudFx.line(graphics, x, y, x - sx * length, y, 1.4F, colour);
            HudFx.line(graphics, x, y, x, y - sy * length, 1.4F, colour);
         }
      }
   }

   // ------------------------------------------------------------------
   // Bogenanzeigen
   // ------------------------------------------------------------------

   private void drawGauges(GuiGraphicsExtractor graphics, Font font, float cx, float cy, double distance, boolean live,
                           float tension, float speedMs, boolean active, int heat, float time) {
      float radius = 38.0F;
      float a0 = 150.0F * Mth.DEG_TO_RAD;
      float sweep = 60.0F * Mth.DEG_TO_RAD;
      int track = argb(IDLE, 0.28F);

      // links: Entfernung (Wurzelskala - nah wird fein aufgelöst), von unten nach oben
      float distShare = live ? (float) Math.sqrt(clamp01((float) (distance / MAX_RANGE))) : 0.0F;
      arc(graphics, cx, cy, radius, a0, a0 + sweep, 24, 2.0F, track);
      gaugeTicks(graphics, cx, cy, radius, a0, sweep, true, argb(IDLE, 0.7F));
      if (live) {
         gaugeFill(graphics, cx, cy, radius, a0, sweep, distShare, true, active ? heat : CYAN);
      }

      // rechts: Zugkraft
      float b0 = 30.0F * Mth.DEG_TO_RAD;
      arc(graphics, cx, cy, radius, b0, b0 - sweep, 24, 2.0F, track);
      gaugeTicks(graphics, cx, cy, radius, b0, -sweep, false, argb(IDLE, 0.7F));
      if (active) {
         gaugeFill(graphics, cx, cy, radius, b0, -sweep, tension, false, heat);
      }

      int muted = argb(0x9AA8BD, 0.9F);
      String distText = !live ? "--.-" : distance < 100.0 ? String.format(Locale.ROOT, "%.1f", distance) : Long.toString(Math.round(distance));
      HudFx.rightText(graphics, font, "ENTF.", cx - radius - 7.0F, cy - 15.0F, 0.7F, muted);
      HudFx.rightText(graphics, font, distText, cx - radius - 7.0F, cy - 6.0F, 1.35F, argb(live ? WHITE : IDLE, 0.97F));
      HudFx.rightText(graphics, font, "METER", cx - radius - 7.0F, cy + 8.0F, 0.7F, muted);

      String speedText = active ? String.format(Locale.ROOT, "%.1f", speedMs) : "--.-";
      HudFx.leftText(graphics, font, "TEMPO", cx + radius + 7.0F, cy - 15.0F, 0.7F, muted);
      HudFx.leftText(graphics, font, speedText, cx + radius + 7.0F, cy - 6.0F, 1.35F, argb(active ? WHITE : IDLE, 0.97F));
      HudFx.leftText(graphics, font, active ? "M/S  " + Math.round(tension * 100.0F) + "%" : "M/S", cx + radius + 7.0F, cy + 8.0F, 0.7F,
         active ? argb(heat, 0.95F) : muted);
   }

   private static void gaugeTicks(GuiGraphicsExtractor graphics, float cx, float cy, float radius, float start, float sweep,
                                  boolean outward, int colour) {
      for (int i = 0; i <= 10; i++) {
         float a = start + sweep * i / 10.0F;
         float len = i % 5 == 0 ? 5.0F : 2.5F;
         float inner = outward ? radius + 2.0F : radius - 2.0F - len + 2.0F;
         float outer = outward ? radius + 2.0F + len : radius - 2.0F;
         HudFx.line(graphics, cx + Mth.cos(a) * inner, cy + Mth.sin(a) * inner,
            cx + Mth.cos(a) * outer, cy + Mth.sin(a) * outer, 1.0F, colour);
      }
   }

   /** Gefüllter Bogen mit Farbverlauf von Cyan bis zur Zielfarbe und hellem Endpunkt. */
   private static void gaugeFill(GuiGraphicsExtractor graphics, float cx, float cy, float radius, float start, float sweep,
                                 float share, boolean left, int endColour) {
      int segments = 24;
      int filled = Math.max(1, Math.round(segments * clamp01(share)));
      float previous = start;
      for (int i = 1; i <= filled; i++) {
         float a = start + sweep * clamp01(share) * i / filled;
         float f = i / (float) segments;
         int colour = HudFx.mix(CYAN, endColour, f * 1.4F);
         HudFx.line(graphics, cx + Mth.cos(previous) * radius, cy + Mth.sin(previous) * radius,
            cx + Mth.cos(a) * radius, cy + Mth.sin(a) * radius, 3.0F, argb(colour, 0.95F));
         previous = a;
      }
      float outer = radius + 5.5F;
      float inner = radius - 5.5F;
      HudFx.line(graphics, cx + Mth.cos(previous) * inner, cy + Mth.sin(previous) * inner,
         cx + Mth.cos(previous) * outer, cy + Mth.sin(previous) * outer, 1.6F, argb(WHITE, 0.95F));
   }

   // ------------------------------------------------------------------
   // Statuskopf und Ladungen
   // ------------------------------------------------------------------

   private void drawHeader(GuiGraphicsExtractor graphics, Font font, float cx, float y, boolean active, boolean pulling,
                           boolean retracting, boolean inRange, int heat, float time) {
      String text;
      int colour;
      if (pulling) {
         text = "VERANKERT";
         colour = heat;
      } else if (retracting) {
         text = "SEILRUECKLAUF";
         colour = AMBER;
      } else if (active) {
         text = "HAKEN FLIEGT";
         colour = CYAN;
      } else if (inRange) {
         text = "ZIEL ERFASST";
         colour = CYAN;
      } else {
         text = "KEIN ZIEL";
         colour = IDLE;
      }
      boolean live = active || inRange;
      float width = font.width(text) * 0.9F;
      float alpha = live ? 0.95F : 0.6F;
      graphics.fill(Math.round(cx - width / 2.0F - 12.0F), Math.round(y - 3.0F), Math.round(cx + width / 2.0F + 12.0F),
         Math.round(y + 10.0F), argb(0x05080C, 0.55F));
      graphics.fill(Math.round(cx - width / 2.0F - 12.0F), Math.round(y + 9.0F), Math.round(cx + width / 2.0F + 12.0F),
         Math.round(y + 10.0F), argb(colour, alpha * 0.8F));
      HudFx.smallText(graphics, font, text, cx, y, 0.9F, argb(colour, alpha));

      // laufende Chevrons rechts und links des Textes
      float run = fraction(time * (pulling ? 0.10F : 0.05F));
      for (int side = -1; side <= 1; side += 2) {
         for (int i = 0; i < 3; i++) {
            float step = i + run;
            float x = cx + side * (width / 2.0F + 5.0F + step * 3.5F);
            float a = (1.0F - step / 3.0F) * alpha * (live ? 1.0F : 0.4F);
            HudFx.line(graphics, x, y, x + side * 2.0F, y + 3.0F, 1.0F, argb(colour, a));
            HudFx.line(graphics, x + side * 2.0F, y + 3.0F, x, y + 6.0F, 1.0F, argb(colour, a));
         }
      }
   }

   private void drawCharges(GuiGraphicsExtractor graphics, Font font, float cx, float y, int charges, int maxCharges,
                            boolean infinite, float time) {
      if (maxCharges <= 0) {
         return;
      }
      float cell = 6.0F;
      float gap = 3.0F;
      float total = maxCharges * cell + (maxCharges - 1) * gap;
      float start = cx - total / 2.0F;
      float pulse = 0.5F + 0.5F * Mth.sin(time * 0.35F);
      boolean low = !infinite && charges <= 3;
      int fillColour = infinite ? CYAN : charges == 0 ? RED : low ? AMBER : CYAN;

      for (int i = 0; i < maxCharges; i++) {
         float x = start + i * (cell + gap);
         boolean filled = infinite || i < charges;
         if (filled) {
            float glow = low ? 0.5F + 0.5F * pulse : 0.35F;
            HudFx.line(graphics, x, y + 3.0F, x + cell - 1.0F, y - 1.0F, 5.0F, argb(fillColour, 0.20F * glow));
            HudFx.line(graphics, x, y + 3.0F, x + cell - 1.0F, y - 1.0F, 3.0F, argb(fillColour, 0.95F));
            HudFx.line(graphics, x + 1.0F, y + 2.0F, x + cell - 2.0F, y - 0.5F, 1.0F, argb(WHITE, 0.55F));
         } else {
            HudFx.line(graphics, x, y + 3.0F, x + cell - 1.0F, y - 1.0F, 3.0F, argb(IDLE, 0.22F));
         }
      }

      String label = infinite ? "DRUCK  ∞" : String.format(Locale.ROOT, "DRUCK  %02d/%02d", charges, maxCharges);
      HudFx.smallText(graphics, font, label, cx, y + 7.0F, 0.8F,
         argb(infinite ? CYAN : charges == 0 ? RED : low ? HudFx.mix(AMBER, WHITE, pulse * 0.5F) : 0xCBD5E1, 0.95F));
   }

   // ------------------------------------------------------------------
   // Einrasten und Lösen
   // ------------------------------------------------------------------

   private void drawBursts(GuiGraphicsExtractor graphics, float cx, float cy, float vw, float vh, long now, int heat) {
      float latch = progress(now, latchStartedAt, LATCH_NANOS);
      if (latchStartedAt >= 0L && latch < 1.0F) {
         float eased = easeOutCubic(latch);
         graphics.fill(0, 0, Math.round(vw), Math.round(vh), argb(WHITE, 0.10F * (1.0F - latch) * (1.0F - latch)));
         HudFx.ring(graphics, cx, cy, Mth.lerp(eased, 12.0F, vh * 0.34F), 96, 2, argb(HudFx.lighten(heat, 0.4F), (1.0F - latch) * 0.9F));
         HudFx.ring(graphics, cx, cy, Mth.lerp(eased, 8.0F, vh * 0.22F), 72, 2, argb(WHITE, (1.0F - latch) * 0.7F));
         for (int i = 0; i < 14; i++) {
            float angle = i * TWO_PI / 14.0F + rand(i + 900) * 0.4F;
            float dist = eased * (26.0F + 52.0F * rand(i + 901));
            float x = cx + Mth.cos(angle) * (10.0F + dist);
            float y = cy + Mth.sin(angle) * (10.0F + dist);
            HudFx.line(graphics, x, y, x - Mth.cos(angle) * 6.0F * (1.0F - latch), y - Mth.sin(angle) * 6.0F * (1.0F - latch),
               1.6F, argb(rand(i + 902) > 0.5F ? WHITE : heat, 1.0F - latch));
         }
      }

      if (releaseStartedAt >= 0L) {
         float release = progress(now, releaseStartedAt, RELEASE_NANOS);
         if (release < 1.0F) {
            HudFx.ring(graphics, cx, cy, Mth.lerp(release, 34.0F, 7.0F), 40, 2, argb(AMBER, (1.0F - release) * 0.8F));
         }
      }
   }

   // ------------------------------------------------------------------
   // Werkzeug
   // ------------------------------------------------------------------

   private static void arc(GuiGraphicsExtractor graphics, float cx, float cy, float radius, float start, float end,
                           int segments, float thickness, int colour) {
      if ((colour >>> 24) == 0) {
         return;
      }
      float previousX = cx + Mth.cos(start) * radius;
      float previousY = cy + Mth.sin(start) * radius;
      for (int i = 1; i <= segments; i++) {
         float angle = start + (end - start) * i / segments;
         float x = cx + Mth.cos(angle) * radius;
         float y = cy + Mth.sin(angle) * radius;
         HudFx.line(graphics, previousX, previousY, x, y, thickness, colour);
         previousX = x;
         previousY = y;
      }
   }

   private static float fraction(float value) {
      return value - (float) Math.floor(value);
   }
}
