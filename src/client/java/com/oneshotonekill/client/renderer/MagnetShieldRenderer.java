package com.oneshotonekill.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.oneshotonekill.OneShotOneKill;
import static com.oneshotonekill.client.state.ClientStates.*;
import com.oneshotonekill.client.state.ClientStates.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.Mth;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import org.joml.Matrix4fc;

/**
 * Sichtbares Schutzfeld des Pfeilmagneten für alle Spieler.
 * <p>
 * <p>Die Kugel ist keine Entity. Jeder Client setzt sie unmittelbar an die interpolierte
 * Position des jeweiligen Spielers. Zwei weiche Energieschalen mit rot-blauen Magnetpolen und
 * gebogenen Feldlinien von einem Pol zum anderen ergeben ein deutlich magnetisches Feld.</p>
 */
@SuppressWarnings({"resource", "SuspiciousNameCombination"})
public final class MagnetShieldRenderer {
   private static final RenderStateDataKey<List<MagnetFrame>> FRAMES =
      RenderStateDataKey.create(() -> OneShotOneKill.MOD_ID + ":magnet_shield_frames");
   private static final int LATITUDE_SEGMENTS = 18;
   private static final int LONGITUDE_SEGMENTS = 36;
   /** Muss dem Kollisionsradius in StatusAbilities entsprechen. */
   private static final float RADIUS = 1.50F;
   private static final int FIELD_LINE_COUNT = 8;
   private static final int FIELD_LINE_SEGMENTS = 28;

   private static final Map<UUID, Float> intensities = new HashMap<>();
   private static final Map<UUID, Float> previousIntensities = new HashMap<>();
   private static float animationTicks;

   private MagnetShieldRenderer() {
   }

   /**
    * Hängt die Magnetfelder in den Renderdurchlauf ein.
    * <p>
    * {@code END_EXTRACTION} entspricht NeoForges {@code ExtractLevelRenderStateEvent} und
    * sammelt am Ende der Zustandserfassung die unveränderlichen Zahlen dieses Bildes ein;
    * {@code COLLECT_SUBMITS} entspricht {@code SubmitCustomGeometryEvent} und zeichnet daraus.
    */
   public static void register() {
      LevelExtractionEvents.END_EXTRACTION.register(MagnetShieldRenderer::onExtract);
      LevelRenderEvents.COLLECT_SUBMITS.register(MagnetShieldRenderer::onSubmit);
   }

   /** Baut neue Felder weich auf und lässt deaktivierte kurz ausklingen. */
   public static void tick() {
      Set<UUID> active = MagnetFieldState.INSTANCE.activePlayers();
      Set<UUID> known = new HashSet<>(intensities.keySet());
      known.addAll(active);
      for (UUID uuid : known) {
         float previous = intensities.getOrDefault(uuid, 0.0F);
         previousIntensities.put(uuid, previous);
         float current = active.contains(uuid)
            ? Math.min(1.0F, previous + 0.14F)
            : Math.max(0.0F, previous - 0.20F);
         if (current <= 0.0F && !active.contains(uuid)) {
            intensities.remove(uuid);
            previousIntensities.remove(uuid);
         } else {
            intensities.put(uuid, current);
         }
      }
      animationTicks += 1.0F;
   }

   public static void clear() {
      intensities.clear();
      previousIntensities.clear();
      animationTicks = 0.0F;
   }

   /** Erstellt pro sichtbarem Spieler nur kleine, unveränderliche Frame-Daten. */
   private static void onExtract(LevelExtractionContext context) {
      float partialTick = context.deltaTracker().getGameTimeDeltaPartialTick(true);
      Vec3 camera = context.camera().position();
      List<MagnetFrame> frames = new ArrayList<>();

      for (AbstractClientPlayer player : context.level().players()) {
         UUID uuid = player.getUUID();
         float current = intensities.getOrDefault(uuid, 0.0F);
         float previous = previousIntensities.getOrDefault(uuid, current);
         float visible = Mth.lerp(partialTick, previous, current);
         if (player.isInvisible() || visible <= 0.001F) {
            continue;
         }

         double x = Mth.lerp(partialTick, player.xo, player.getX());
         double y = Mth.lerp(partialTick, player.yo, player.getY()) + player.getBbHeight() * 0.5;
         double z = Mth.lerp(partialTick, player.zo, player.getZ());
         frames.add(new MagnetFrame((float) (x - camera.x), (float) (y - camera.y),
            (float) (z - camera.z), animationTicks + partialTick, visible));
      }
      context.levelState().setData(FRAMES, frames.isEmpty() ? null : List.copyOf(frames));
   }

   private static void onSubmit(LevelRenderContext context) {
      LevelRenderState levelState = context.levelState();
      List<MagnetFrame> frames = levelState.getData(FRAMES);
      if (frames == null) {
         return;
      }

      PoseStack poseStack = context.poseStack();
      for (MagnetFrame frame : frames) {
         poseStack.pushPose();
         poseStack.translate(frame.x, frame.y, frame.z);
         float pulse = RADIUS * (0.985F + (float) Math.sin(frame.time * 0.20F) * 0.015F);
         poseStack.scale(pulse, pulse, pulse);
         context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.debugQuads(),
            (pose, buffer) -> renderField(pose.pose(), buffer, frame));
         poseStack.popPose();
      }
   }

   private static void renderField(Matrix4fc pose, VertexConsumer buffer, MagnetFrame frame) {
      Vec3 view = new Vec3(-frame.x, -frame.y, -frame.z);
      view = view.lengthSqr() < 1.0E-5 ? new Vec3(0.0, 0.0, 1.0) : view.normalize();
      sphere(pose, buffer, frame.time, frame.intensity, view, 1.0F, 0.0F);
      sphere(pose, buffer, frame.time, frame.intensity * 0.52F, view, 1.025F, 2.1F);
      magneticFieldLines(pose, buffer, frame.time, frame.intensity, view);
   }

   private static void sphere(Matrix4fc pose, VertexConsumer buffer, float time, float strength,
                              Vec3 view, float radius, float phase) {
      for (int latitude = 0; latitude < LATITUDE_SEGMENTS; latitude++) {
         double lat0 = -Math.PI * 0.5 + latitude * Math.PI / LATITUDE_SEGMENTS;
         double lat1 = -Math.PI * 0.5 + (latitude + 1) * Math.PI / LATITUDE_SEGMENTS;
         for (int longitude = 0; longitude < LONGITUDE_SEGMENTS; longitude++) {
            double lon0 = longitude * Math.PI * 2.0 / LONGITUDE_SEGMENTS;
            double lon1 = (longitude + 1) * Math.PI * 2.0 / LONGITUDE_SEGMENTS;
            SurfaceVertex a = surface(lat0, lon0, radius, time, phase, strength, view);
            SurfaceVertex b = surface(lat1, lon0, radius, time, phase, strength, view);
            SurfaceVertex c = surface(lat1, lon1, radius, time, phase, strength, view);
            SurfaceVertex d = surface(lat0, lon1, radius, time, phase, strength, view);
            quad(pose, buffer, a, b, c, d);
            quad(pose, buffer, d, c, b, a);
         }
      }
   }

   private static SurfaceVertex surface(double latitude, double longitude, float radius, float time,
                                        float phase, float strength, Vec3 view) {
      double cosLat = Math.cos(latitude);
      float nx = (float) (cosLat * Math.cos(longitude));
      float ny = (float) Math.sin(latitude);
      float nz = (float) (cosLat * Math.sin(longitude));

      double facing = Math.abs(nx * view.x + ny * view.y + nz * view.z);
      double rim = Math.pow(1.0 - facing, 2.25);
      double poleAngle = time * 0.012 + phase * 0.08;
      double pole = nx * Math.cos(poleAngle) + nz * Math.sin(poleAngle);
      double polarity = (pole + 1.0) * 0.5;
      double poleCap = Math.pow(Math.abs(pole), 8.0);
      double currentA = Math.sin(longitude * 4.0 + latitude * 7.0 - time * 0.20 + phase);
      double currentB = Math.sin(longitude * -3.0 + latitude * 5.0 + time * 0.13 - phase);
      double streams = Math.pow(Math.max(0.0, currentA * 0.6 + currentB * 0.4), 7.0);
      double magneticEquator = Math.pow(1.0 - Math.abs(pole), 7.0);
      float wave = (float) (1.0 + (streams + poleCap * 0.45) * 0.016);
      float alpha = (float) ((0.018 + rim * 0.13 + streams * 0.07
         + magneticEquator * 0.035 + poleCap * 0.10) * strength);
      float red = (float) (0.08 + polarity * 0.84 + poleCap * polarity * 0.08);
      float green = (float) (0.12 + magneticEquator * 0.40 + streams * 0.34);
      float blue = (float) (0.10 + (1.0 - polarity) * 0.88
         + poleCap * (1.0 - polarity) * 0.08);
      return new SurfaceVertex(nx * radius * wave, ny * radius * wave, nz * radius * wave,
         red, green, blue, alpha);
   }

   /** Acht geschwungene Dipol-Linien: vom roten Nordpol außen herum zum blauen Südpol. */
   private static void magneticFieldLines(Matrix4fc pose, VertexConsumer buffer, float time,
                                          float strength, Vec3 view) {
      double axisAngle = time * 0.012;
      double axisCos = Math.cos(axisAngle);
      double axisSin = Math.sin(axisAngle);
      for (int line = 0; line < FIELD_LINE_COUNT; line++) {
         double orbit = line * Math.PI * 2.0 / FIELD_LINE_COUNT + time * 0.004;
         for (int segment = 0; segment < FIELD_LINE_SEGMENTS; segment++) {
            double t0 = segment * Math.PI / FIELD_LINE_SEGMENTS;
            double t1 = (segment + 1) * Math.PI / FIELD_LINE_SEGMENTS;
            Vec3 a = fieldPoint(t0, orbit, axisCos, axisSin);
            Vec3 b = fieldPoint(t1, orbit, axisCos, axisSin);
            double progress = (segment + 0.5) / FIELD_LINE_SEGMENTS;
            double packet = Math.pow(Math.max(0.0,
               Math.sin(progress * Math.PI * 6.0 - time * 0.16 + line * 0.9)), 6.0);
            float polarity = (float) ((Math.cos((t0 + t1) * 0.5) + 1.0) * 0.5);
            float red = 0.12F + polarity * 0.86F;
            float green = (float) (0.30 + packet * 0.62);
            float blue = 0.16F + (1.0F - polarity) * 0.84F;
            float alpha = (float) ((0.13 + packet * 0.20) * strength);
            ribbon(pose, buffer, a, b, view, (float) (0.016 + packet * 0.018),
               red, green, blue, alpha);
         }
      }
   }

   private static Vec3 fieldPoint(double t, double orbit, double axisCos, double axisSin) {
      double axial = Math.cos(t) * 1.015;
      double bow = Math.sin(t) * (1.03 + Math.sin(t) * 0.30);
      double y = Math.cos(orbit) * bow;
      double localZ = Math.sin(orbit) * bow;
      return new Vec3(axial * axisCos + localZ * axisSin, y,
         -axial * axisSin + localZ * axisCos);
   }

   /** Kameraseitiges Band zwischen zwei Punkten, damit eine Feldlinie aus jedem Winkel lesbar ist. */
   private static void ribbon(Matrix4fc pose, VertexConsumer buffer, Vec3 from, Vec3 to, Vec3 view,
                              float width, float red, float green, float blue, float alpha) {
      Vec3 direction = to.subtract(from);
      Vec3 side = direction.cross(view);
      if (side.lengthSqr() < 1.0E-6) {
         side = direction.cross(from.add(to));
      }
      if (side.lengthSqr() < 1.0E-6) {
         side = new Vec3(0.0, width, 0.0);
      } else {
         side = side.normalize().scale(width);
      }
      colouredVertex(pose, buffer, from.add(side), red, green, blue, alpha);
      colouredVertex(pose, buffer, to.add(side), red, green, blue, alpha);
      colouredVertex(pose, buffer, to.subtract(side), red, green, blue, alpha);
      colouredVertex(pose, buffer, from.subtract(side), red, green, blue, alpha);
   }

   private static void colouredVertex(Matrix4fc pose, VertexConsumer buffer, Vec3 point,
                                      float red, float green, float blue, float alpha) {
      buffer.addVertex(pose, (float) point.x, (float) point.y, (float) point.z)
         .setColor(red, green, blue, alpha);
   }

   private static void quad(Matrix4fc pose, VertexConsumer buffer, SurfaceVertex a, SurfaceVertex b,
                            SurfaceVertex c, SurfaceVertex d) {
      vertex(pose, buffer, a);
      vertex(pose, buffer, b);
      vertex(pose, buffer, c);
      vertex(pose, buffer, d);
   }

   private static void vertex(Matrix4fc pose, VertexConsumer buffer, SurfaceVertex vertex) {
      buffer.addVertex(pose, vertex.x, vertex.y, vertex.z)
         .setColor(vertex.red, vertex.green, vertex.blue, vertex.alpha);
   }

   private record MagnetFrame(float x, float y, float z, float time, float intensity) {
   }

   private record SurfaceVertex(float x, float y, float z, float red, float green, float blue, float alpha) {
   }
}
