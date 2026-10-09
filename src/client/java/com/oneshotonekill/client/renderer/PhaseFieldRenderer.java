package com.oneshotonekill.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.shared.PhaseFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;

/**
 * Die Phasen-Kugel als echtes Energiefeld statt als Partikelwolke.
 * <p>
 * Gebaut wie das Reflektor-Schild ({@link ReflectorShieldRenderer}): keine Entity, sondern
 * durchscheinende Geometrie, die in jedem Bild relativ zur Kamera gesetzt wird. Drei Lagen:
 * <ul>
 *   <li><b>Haut</b> – geschlossene Schale mit hellem Rand (Fresnel), einem Abtastband, das
 *       auf und ab läuft, und gegenläufigen Strömen.</li>
 *   <li><b>Gitter</b> – fünf Breiten- und sechzehn Längenkreise als schmale Bänder, über die
 *       Lichtimpulse wandern.</li>
 *   <li><b>Wellen</b> – eine Druckwelle am Boden beim Aufschlag, eine Welle, die die Kugel vom
 *       Boden aus hinaufläuft, und ein Ring auf der Haut, wo jemand hindurchtritt.</li>
 * </ul>
 * Zum Ende wird die Kugel rot und flackert; beim Verschwinden stürzt sie in sich zusammen und
 * blitzt auf. Alle Kugeln in der Welt werden gezeichnet, nicht nur die eigene – die Haut ist für
 * jeden sichtbar, nur Durchgang und Durchsicht gehören dem Werfer.
 */
@SuppressWarnings("resource")
public final class PhaseFieldRenderer {
   private static final RenderStateDataKey<List<Frame>> FRAMES =
      RenderStateDataKey.create(() -> OneShotOneKill.MOD_ID + ":phase_field_frames");

   private static final int LATITUDE_SEGMENTS = 24;
   private static final int LONGITUDE_SEGMENTS = 48;
   private static final int RING_SEGMENTS = 64;
   private static final int MERIDIAN_SEGMENTS = 32;
   private static final float[] RING_LATITUDES_DEGREES = {-60.0F, -30.0F, 0.0F, 30.0F, 60.0F};
   private static final int MERIDIANS = 16;
   private static final float RIBBON_HALF_WIDTH = 0.028F;
   private static final double MAX_DISTANCE_SQR = 96.0 * 96.0;
   /** Gesamtdeckkraft der Kugel; kleiner heißt durchsichtiger. */
   private static final float OPACITY = 0.6F;

   /** Aufbau in zehn Takten, Zusammensturz in zwölf. */
   private static final float GROW_PER_TICK = 0.1F;
   private static final int COLLAPSE_TICKS = 12;
   private static final int RIPPLE_LIFE = 22;
   private static final int IMPACT_RIPPLE_LIFE = 30;
   private static final int GROUND_WAVE_TICKS = 16;
   private static final int MAX_RIPPLES = 6;

   private static final Map<UUID, Visual> VISUALS = new HashMap<>();
   private static float time;

   /** Arbeitsfläche für vier Eckpunkte zu je sieben Werten; nur der Renderfaden zeichnet. */
   private static final float[] CORNERS = new float[28];

   private PhaseFieldRenderer() {
   }

   public static void register() {
      LevelExtractionEvents.END_EXTRACTION.register(PhaseFieldRenderer::onExtract);
      LevelRenderEvents.COLLECT_SUBMITS.register(PhaseFieldRenderer::onSubmit);
   }

   public static void clear() {
      VISUALS.clear();
      time = 0.0F;
   }

   // -- Zustand -------------------------------------------------------------

   private static final class Ripple {
      private final float dx;
      private final float dy;
      private final float dz;
      private final int life;
      private int age;

      private Ripple(Vec3 direction, int life) {
         Vec3 unit = direction.lengthSqr() < 1.0E-6 ? new Vec3(0.0, -1.0, 0.0) : direction.normalize();
         this.dx = (float) unit.x;
         this.dy = (float) unit.y;
         this.dz = (float) unit.z;
         this.life = life;
      }
   }

   private static final class Visual {
      private Vec3 centre = Vec3.ZERO;
      private float radius;
      private long remaining;
      private boolean alive = true;
      private float grow;
      private float previousGrow;
      private int collapse;
      private int age;
      private final List<Ripple> ripples = new ArrayList<>();
      private final Set<UUID> inside = new HashSet<>();
   }

   /** Ein Takt: Kugeln anlegen oder abbauen, Wellen altern lassen, Durchtritte erkennen. */
   public static void tick() {
      ClientLevel level = Minecraft.getInstance().level;
      if (level == null) {
         VISUALS.clear();
         return;
      }
      time += 1.0F;

      Set<UUID> present = new HashSet<>();
      for (PhaseFields.Zone zone : PhaseFields.CLIENT.all()) {
         present.add(zone.id());
         Visual visual = VISUALS.get(zone.id());
         if (visual == null || !visual.alive) {
            visual = new Visual();
            visual.centre = zone.centre();
            visual.radius = (float) zone.radius();
            VISUALS.put(zone.id(), visual);
            // Der Aufschlag läuft als erste Welle vom Boden aus die Kugel hinauf.
            visual.ripples.add(new Ripple(new Vec3(0.0, -1.0, 0.0), IMPACT_RIPPLE_LIFE));
            seedInside(level, visual);
         }
         visual.centre = zone.centre();
         visual.radius = (float) zone.radius();
         visual.remaining = zone.endsAt() - level.getGameTime();
      }

      Iterator<Map.Entry<UUID, Visual>> iterator = VISUALS.entrySet().iterator();
      while (iterator.hasNext()) {
         Map.Entry<UUID, Visual> entry = iterator.next();
         Visual visual = entry.getValue();
         visual.previousGrow = visual.grow;
         if (visual.alive && !present.contains(entry.getKey())) {
            visual.alive = false;
            visual.collapse = 0;
         }
         if (visual.alive) {
            visual.age++;
            visual.grow = Math.min(1.0F, visual.grow + GROW_PER_TICK);
            trackCrossings(level, visual);
         } else {
            visual.collapse++;
            visual.grow = Math.max(0.0F, 1.0F - visual.collapse / (float) COLLAPSE_TICKS);
            if (visual.collapse > COLLAPSE_TICKS) {
               iterator.remove();
               continue;
            }
         }
         visual.ripples.removeIf(ripple -> ++ripple.age > ripple.life);
      }
   }

   private static void seedInside(ClientLevel level, Visual visual) {
      visual.inside.clear();
      for (AbstractClientPlayer player : level.players()) {
         if (isInside(visual, player)) {
            visual.inside.add(player.getUUID());
         }
      }
   }

   private static boolean isInside(Visual visual, AbstractClientPlayer player) {
      return player.position().add(0.0, player.getBbHeight() * 0.5, 0.0).distanceToSqr(visual.centre)
         <= (double) visual.radius * visual.radius;
   }

   /** Wer die Haut durchquert, lässt dort einen Ring über die Kugel laufen – in beide Richtungen. */
   private static void trackCrossings(ClientLevel level, Visual visual) {
      for (AbstractClientPlayer player : level.players()) {
         if (player.isSpectator()) {
            continue;
         }
         boolean now = isInside(visual, player);
         if (now == visual.inside.contains(player.getUUID())) {
            continue;
         }
         if (now) {
            visual.inside.add(player.getUUID());
         } else {
            visual.inside.remove(player.getUUID());
         }
         if (visual.ripples.size() < MAX_RIPPLES) {
            visual.ripples.add(new Ripple(
               player.position().add(0.0, player.getBbHeight() * 0.5, 0.0).subtract(visual.centre), RIPPLE_LIFE));
         }
      }
   }

   // -- Bild ----------------------------------------------------------------

   /** Unveränderliche Daten eines Bildes; {@code ripples} sind Vierer aus Richtung und Fortschritt. */
   private record Frame(float x, float y, float z, float radius, float time, float strength, float warn,
                        float flash, float groundWave, float[] ripples) {
   }

   private static void onExtract(LevelExtractionContext context) {
      if (VISUALS.isEmpty()) {
         context.levelState().setData(FRAMES, null);
         return;
      }
      float partial = context.deltaTracker().getGameTimeDeltaPartialTick(true);
      Vec3 camera = context.camera().position();
      List<Frame> frames = new ArrayList<>(VISUALS.size());
      for (Visual visual : VISUALS.values()) {
         Vec3 offset = visual.centre.subtract(camera);
         if (offset.lengthSqr() > MAX_DISTANCE_SQR) {
            continue;
         }
         float grow = Mth.lerp(partial, visual.previousGrow, visual.grow);
         float scale = visual.alive ? easeOutBack(grow) : grow * grow;
         float strength = visual.alive ? Math.min(1.0F, grow * 1.6F) : grow;
         float warn = visual.alive && visual.remaining <= PhaseFields.WARNING_TICKS ? 1.0F : 0.0F;
         float flash = visual.alive ? 0.0F : 1.0F - (visual.collapse + partial) / COLLAPSE_TICKS;
         float groundWave = visual.alive && visual.age < GROUND_WAVE_TICKS
            ? (visual.age + partial) / GROUND_WAVE_TICKS : -1.0F;

         float[] ripples = new float[visual.ripples.size() * 4];
         int slot = 0;
         for (Ripple ripple : visual.ripples) {
            ripples[slot++] = ripple.dx;
            ripples[slot++] = ripple.dy;
            ripples[slot++] = ripple.dz;
            ripples[slot++] = Math.min(1.0F, (ripple.age + partial) / ripple.life);
         }
         frames.add(new Frame((float) offset.x, (float) offset.y, (float) offset.z, visual.radius * scale,
            time + partial, strength, warn, Math.max(0.0F, flash), groundWave, ripples));
      }
      context.levelState().setData(FRAMES, frames);
   }

   private static float easeOutBack(float t) {
      float shifted = t - 1.0F;
      return 1.0F + 2.70158F * shifted * shifted * shifted + 1.70158F * shifted * shifted;
   }

   private static void onSubmit(LevelRenderContext context) {
      List<Frame> frames = context.levelState().getData(FRAMES);
      if (frames == null || frames.isEmpty()) {
         return;
      }
      for (Frame frame : frames) {
         if (frame.radius < 0.05F || frame.strength <= 0.001F) {
            continue;
         }
         PoseStack poseStack = context.poseStack();
         poseStack.pushPose();
         poseStack.translate(frame.x, frame.y, frame.z);
         context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.debugQuads(),
            (pose, buffer) -> renderField(pose.pose(), buffer, frame));
         poseStack.popPose();
      }
   }

   private static void renderField(Matrix4fc pose, VertexConsumer buffer, Frame frame) {
      Vec3 view = new Vec3(-frame.x, -frame.y, -frame.z);
      view = view.lengthSqr() < 1.0E-5 ? new Vec3(0.0, 0.0, 1.0) : view.normalize();
      // Die äußere Aura ist eine zweite, dünnere Haut: so hat der Rand Tiefe.
      shell(pose, buffer, frame, frame.radius, 1.0F, 0.0F, view);
      shell(pose, buffer, frame, frame.radius * 1.03F, 0.45F, 2.1F, view);
      lattice(pose, buffer, frame);
      groundWave(pose, buffer, frame);
   }

   // -- Haut ----------------------------------------------------------------

   /** Farbe nach Zustand: Cyan, zum Ende Rot; {@code energy} schiebt Richtung Weiß. */
   private static void colour(Frame frame, float energy, float[] out, int slot) {
      float r = Mth.lerp(energy, 0.10F, 0.78F);
      float g = Mth.lerp(energy, 0.80F, 1.00F);
      float b = Mth.lerp(energy, 1.00F, 1.00F);
      if (frame.warn > 0.0F) {
         r = Mth.lerp(energy, 1.00F, 1.00F);
         g = Mth.lerp(energy, 0.20F, 0.72F);
         b = Mth.lerp(energy, 0.14F, 0.50F);
      }
      if (frame.flash > 0.0F) {
         r = Mth.lerp(frame.flash, r, 1.0F);
         g = Mth.lerp(frame.flash, g, 0.95F);
         b = Mth.lerp(frame.flash, b, 0.90F);
      }
      out[slot] = r;
      out[slot + 1] = g;
      out[slot + 2] = b;
   }

   private static float flicker(Frame frame) {
      return frame.warn > 0.0F && (int) (frame.time / 2.0F) % 3 == 0 ? 0.35F : 1.0F;
   }

   private static void shell(Matrix4fc pose, VertexConsumer buffer, Frame frame, float radius, float weight,
                             float phase, Vec3 view) {
      float flicker = flicker(frame);
      for (int latitude = 0; latitude < LATITUDE_SEGMENTS; latitude++) {
         double lat0 = -Math.PI * 0.5 + latitude * Math.PI / LATITUDE_SEGMENTS;
         double lat1 = -Math.PI * 0.5 + (latitude + 1) * Math.PI / LATITUDE_SEGMENTS;
         for (int longitude = 0; longitude < LONGITUDE_SEGMENTS; longitude++) {
            double lon0 = longitude * Math.PI * 2.0 / LONGITUDE_SEGMENTS;
            double lon1 = (longitude + 1) * Math.PI * 2.0 / LONGITUDE_SEGMENTS;
            shellVertex(frame, radius, weight, phase, view, flicker, lat0, lon0, 0);
            shellVertex(frame, radius, weight, phase, view, flicker, lat1, lon0, 7);
            shellVertex(frame, radius, weight, phase, view, flicker, lat1, lon1, 14);
            shellVertex(frame, radius, weight, phase, view, flicker, lat0, lon1, 21);
            emitQuad(pose, buffer);
         }
      }
   }

   private static void shellVertex(Frame frame, float radius, float weight, float phase, Vec3 view, float flicker,
                                   double latitude, double longitude, int slot) {
      double cosLat = Math.cos(latitude);
      float nx = (float) (cosLat * Math.cos(longitude));
      float ny = (float) Math.sin(latitude);
      float nz = (float) (cosLat * Math.sin(longitude));
      double facing = Math.abs(nx * view.x + ny * view.y + nz * view.z);

      double rim = Math.pow(1.0 - facing, 2.2);
      double current = Math.pow(Math.max(0.0, Math.sin(longitude * 3.0 + latitude * 5.0 - frame.time * 0.22 + phase)), 5.0);
      double counter = Math.pow(Math.max(0.0, Math.sin(longitude * -5.0 + latitude * 2.0 + frame.time * 0.13 + phase)), 6.0);
      double scanLatitude = Math.sin(frame.time * 0.05 + phase) * 1.25;
      double scan = Math.exp(-Math.pow(latitude - scanLatitude, 2.0) / 0.012);

      double ripple = 0.0;
      float[] ripples = frame.ripples;
      for (int index = 0; index < ripples.length; index += 4) {
         double dot = Mth.clamp(nx * ripples[index] + ny * ripples[index + 1] + nz * ripples[index + 2], -1.0, 1.0);
         double ring = ripples[index + 3] * Math.PI;
         double distance = Math.acos(dot) - ring;
         ripple += Math.exp(-distance * distance / 0.02) * (1.0 - ripples[index + 3]) * 0.6;
      }

      double energy = Math.min(1.0, current * 0.5 + counter * 0.35 + scan * 0.9 + ripple * 1.4 + frame.flash);
      double alpha = (0.035 + rim * 0.17 + current * 0.06 + counter * 0.04 + scan * 0.2 + ripple + frame.flash * 0.5)
         * frame.strength * weight * flicker;
      float wave = (float) (1.0 + Math.sin(longitude * 4.0 + latitude * 3.0 + frame.time * 0.09 + phase) * 0.008);

      CORNERS[slot] = nx * radius * wave;
      CORNERS[slot + 1] = ny * radius * wave;
      CORNERS[slot + 2] = nz * radius * wave;
      colour(frame, (float) energy, CORNERS, slot + 3);
      CORNERS[slot + 6] = (float) Mth.clamp(alpha * OPACITY, 0.0, 0.85);
   }

   // -- Gitter --------------------------------------------------------------

   private static void lattice(Matrix4fc pose, VertexConsumer buffer, Frame frame) {
      float flicker = flicker(frame);
      float radius = frame.radius * 1.004F;
      for (int ring = 0; ring < RING_LATITUDES_DEGREES.length; ring++) {
         double latitude = Math.toRadians(RING_LATITUDES_DEGREES[ring]);
         double cosLat = Math.cos(latitude);
         double sinLat = Math.sin(latitude);
         for (int segment = 0; segment < RING_SEGMENTS; segment++) {
            double lon0 = segment * Math.PI * 2.0 / RING_SEGMENTS;
            double lon1 = (segment + 1) * Math.PI * 2.0 / RING_SEGMENTS;
            ribbonCorner(frame, radius, cosLat, sinLat, lon0, true, -1.0F, ring * 1.3F + (float) lon0, flicker, 0);
            ribbonCorner(frame, radius, cosLat, sinLat, lon0, true, 1.0F, ring * 1.3F + (float) lon0, flicker, 7);
            ribbonCorner(frame, radius, cosLat, sinLat, lon1, true, 1.0F, ring * 1.3F + (float) lon1, flicker, 14);
            ribbonCorner(frame, radius, cosLat, sinLat, lon1, true, -1.0F, ring * 1.3F + (float) lon1, flicker, 21);
            emitQuad(pose, buffer);
         }
      }
      for (int meridian = 0; meridian < MERIDIANS; meridian++) {
         double longitude = meridian * Math.PI * 2.0 / MERIDIANS;
         for (int segment = 0; segment < MERIDIAN_SEGMENTS; segment++) {
            double lat0 = -Math.PI * 0.5 + segment * Math.PI / MERIDIAN_SEGMENTS;
            double lat1 = -Math.PI * 0.5 + (segment + 1) * Math.PI / MERIDIAN_SEGMENTS;
            float s0 = (float) (lat0 + Math.PI * 0.5) + meridian * 0.7F;
            float s1 = (float) (lat1 + Math.PI * 0.5) + meridian * 0.7F;
            ribbonCorner(frame, radius, Math.cos(lat0), Math.sin(lat0), longitude, false, -1.0F, s0, flicker, 0);
            ribbonCorner(frame, radius, Math.cos(lat0), Math.sin(lat0), longitude, false, 1.0F, s0, flicker, 7);
            ribbonCorner(frame, radius, Math.cos(lat1), Math.sin(lat1), longitude, false, 1.0F, s1, flicker, 14);
            ribbonCorner(frame, radius, Math.cos(lat1), Math.sin(lat1), longitude, false, -1.0F, s1, flicker, 21);
            emitQuad(pose, buffer);
         }
      }
   }

   /**
    * Eine Ecke eines Gitterbandes. Breiten ringen laufen waagerecht und sind senkrecht breit, Längen
    * kreise laufen senkrecht und sind waagerecht breit; die Lichtimpulse wandern mit {@code travel}.
    */
   private static void ribbonCorner(Frame frame, float radius, double cosLat, double sinLat, double longitude,
                                    boolean horizontalRing, float side, float travel, float flicker, int slot) {
      double cosLon = Math.cos(longitude);
      double sinLon = Math.sin(longitude);
      float x = (float) (cosLat * cosLon * radius);
      float y = (float) (sinLat * radius);
      float z = (float) (cosLat * sinLon * radius);
      float half = RIBBON_HALF_WIDTH * side;
      if (horizontalRing) {
         x += (float) (-sinLat * cosLon) * half;
         y += (float) cosLat * half;
         z += (float) (-sinLat * sinLon) * half;
      } else {
         x += (float) -sinLon * half;
         z += (float) cosLon * half;
      }
      double pulse = Math.pow(Math.max(0.0, Math.sin(travel * 5.0 - frame.time * 0.2)), 3.0);
      double alpha = (0.2 + pulse * 0.55 + frame.flash * 0.4) * frame.strength * flicker;
      CORNERS[slot] = x;
      CORNERS[slot + 1] = y;
      CORNERS[slot + 2] = z;
      colour(frame, (float) pulse, CORNERS, slot + 3);
      CORNERS[slot + 6] = (float) Mth.clamp(alpha * OPACITY, 0.0, 0.9);
   }

   // -- Bodenwelle ----------------------------------------------------------

   /** Beim Aufschlag läuft ein breiter Ring flach über den Boden nach außen und verblasst. */
   private static void groundWave(Matrix4fc pose, VertexConsumer buffer, Frame frame) {
      if (frame.groundWave < 0.0F) {
         return;
      }
      float eased = 1.0F - (1.0F - frame.groundWave) * (1.0F - frame.groundWave);
      float fade = 1.0F - frame.groundWave;
      float y = -0.36F;
      for (int band = 0; band < 2; band++) {
         float reach = frame.radius * (band == 0 ? 1.0F : 0.72F) * eased * 1.15F;
         float half = band == 0 ? 0.32F : 0.18F;
         float weight = band == 0 ? 1.0F : 0.55F;
         for (int segment = 0; segment < RING_SEGMENTS; segment++) {
            double a0 = segment * Math.PI * 2.0 / RING_SEGMENTS;
            double a1 = (segment + 1) * Math.PI * 2.0 / RING_SEGMENTS;
            groundCorner(frame, a0, reach - half, y, fade * weight, 0, true);
            groundCorner(frame, a0, reach + half, y, fade * weight, 7, false);
            groundCorner(frame, a1, reach + half, y, fade * weight, 14, false);
            groundCorner(frame, a1, reach - half, y, fade * weight, 21, true);
            emitQuad(pose, buffer);
         }
      }
   }

   private static void groundCorner(Frame frame, double angle, float distance, float y, float fade, int slot,
                                    boolean inner) {
      CORNERS[slot] = (float) (Math.cos(angle) * distance);
      CORNERS[slot + 1] = y;
      CORNERS[slot + 2] = (float) (Math.sin(angle) * distance);
      colour(frame, inner ? 0.2F : 1.0F, CORNERS, slot + 3);
      // Außen hell, innen durchsichtig: der Ring hat eine scharfe Vorderkante und einen weichen Schweif.
      CORNERS[slot + 6] = Math.min(0.8F, (inner ? 0.05F : 0.55F) * fade * frame.strength * OPACITY);
   }

   // -- Ausgabe -------------------------------------------------------------

   /** Schreibt die vier Ecken aus {@link #CORNERS} in beiden Umlaufrichtungen, damit keine Seite fehlt. */
   private static void emitQuad(Matrix4fc pose, VertexConsumer buffer) {
      for (int corner = 0; corner < 4; corner++) {
         put(pose, buffer, corner * 7);
      }
      for (int corner = 3; corner >= 0; corner--) {
         put(pose, buffer, corner * 7);
      }
   }

   private static void put(Matrix4fc pose, VertexConsumer buffer, int slot) {
      buffer.addVertex(pose, CORNERS[slot], CORNERS[slot + 1], CORNERS[slot + 2])
         .setColor(CORNERS[slot + 3], CORNERS[slot + 4], CORNERS[slot + 5], CORNERS[slot + 6]);
   }
}
