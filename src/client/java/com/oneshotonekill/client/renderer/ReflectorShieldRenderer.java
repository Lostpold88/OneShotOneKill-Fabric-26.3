package com.oneshotonekill.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.oneshotonekill.OneShotOneKill;
import static com.oneshotonekill.client.state.ClientStates.*;
import com.oneshotonekill.client.state.ClientStates.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
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
 * Das Reflektor-Energiefeld des lokalen Spielers.
 * <p>
 * <p>Es gibt dafür absichtlich keine Entity. Der Server meldet dem Besitzer nur den booleschen
 * Schildzustand; dieser Renderer setzt die Kugel in jedem Frame an die bereits interpolierte
 * Clientposition. Damit kann kein Positionspaket hinter der Spielerbewegung zurückbleiben.</p>
 * <p>
 * <p>Die Oberfläche ist eine geschlossene, additive Energiekugel und kein Drahtgitter. Zwei
 * leicht gegeneinander pulsierende Schalen, ein Fresnel-artig heller Rand und wandernde
 * Energieverdichtungen geben Volumen, ohne die Sicht wie eine massive Wand zu verdecken.</p>
 */
@SuppressWarnings("resource")
public final class ReflectorShieldRenderer {
   private static final RenderStateDataKey<ShieldFrame> FRAME =
      RenderStateDataKey.create(() -> OneShotOneKill.MOD_ID + ":reflector_shield_frame");
   private static final int LATITUDE_SEGMENTS = 18;
   private static final int LONGITUDE_SEGMENTS = 32;
   private static final float RADIUS = 1.28F;

   private static float intensity;
   private static float previousIntensity;
   private static float animationTicks;

   private ReflectorShieldRenderer() {
   }

   /**
    * Hängt das Reflektor-Energiefeld in den Renderdurchlauf ein.
    * <p>
    * {@code END_EXTRACTION} entspricht NeoForges {@code ExtractLevelRenderStateEvent} und
    * sammelt am Ende der Zustandserfassung die unveränderlichen Zahlen dieses Bildes ein;
    * {@code COLLECT_SUBMITS} entspricht {@code SubmitCustomGeometryEvent} und zeichnet daraus.
    */
   public static void register() {
      LevelExtractionEvents.END_EXTRACTION.register(ReflectorShieldRenderer::onExtract);
      LevelRenderEvents.COLLECT_SUBMITS.register(ReflectorShieldRenderer::onSubmit);
   }

   /** Weiches Aufbauen und kurzes Ausklingen beim Zerbrechen, weiterhin rein clientseitig. */
   public static void tick() {
      previousIntensity = intensity;
      if (AbilityStatusState.INSTANCE.hasShield() && AbilityStatusState.INSTANCE.getVanishTicks() <= 0) {
         intensity = Math.min(1.0F, intensity + 0.16F);
         animationTicks += 1.0F;
      } else {
         intensity = Math.max(0.0F, intensity - 0.24F);
      }
   }

   public static void clear() {
      intensity = 0.0F;
      previousIntensity = 0.0F;
      animationTicks = 0.0F;
   }

   /** Kopiert ausschließlich unveränderliche Frame-Daten in den neuen 26.2-Renderzustand. */
   private static void onExtract(LevelExtractionContext context) {
      float partialTick = context.deltaTracker().getGameTimeDeltaPartialTick(true);
      float visible = Mth.lerp(partialTick, previousIntensity, intensity);
      Minecraft minecraft = Minecraft.getInstance();
      LocalPlayer player = minecraft.player;
      if (player == null || player.isInvisible() || visible <= 0.001F || player.level() != context.level()) {
         context.levelState().setData(FRAME, null);
         return;
      }

      double x = Mth.lerp(partialTick, player.xo, player.getX());
      double y = Mth.lerp(partialTick, player.yo, player.getY()) + player.getBbHeight() * 0.5;
      double z = Mth.lerp(partialTick, player.zo, player.getZ());
      Vec3 camera = context.camera().position();
      context.levelState().setData(FRAME, new ShieldFrame(
         (float) (x - camera.x), (float) (y - camera.y), (float) (z - camera.z),
         animationTicks + partialTick, visible));
   }

   private static void onSubmit(LevelRenderContext context) {
      LevelRenderState levelState = context.levelState();
      ShieldFrame frame = levelState.getData(FRAME);
      if (frame == null) {
         return;
      }

      PoseStack poseStack = context.poseStack();
      poseStack.pushPose();
      poseStack.translate(frame.x, frame.y, frame.z);
      float pulse = RADIUS * (0.985F + (float) Math.sin(frame.time * 0.16F) * 0.015F);
      poseStack.scale(pulse, pulse, pulse);
      context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.debugQuads(),
         (pose, buffer) -> renderField(pose.pose(), buffer, frame));
      poseStack.popPose();
   }

   private static void renderField(Matrix4fc pose, VertexConsumer buffer, ShieldFrame frame) {
      Vec3 view = new Vec3(-frame.x, -frame.y, -frame.z);
      if (view.lengthSqr() < 1.0E-5) {
         view = new Vec3(0.0, 0.0, 1.0);
      } else {
         view = view.normalize();
      }
      // Innere Hauptschale und eine sehr dünne äußere Aura ergeben ein geschlossenes Feld.
      sphere(pose, buffer, frame.time, frame.intensity, view, 1.0F, 0.0F);
      sphere(pose, buffer, frame.time, frame.intensity * 0.62F, view, 1.035F, 1.9F);
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
            // Zweite Wicklung: Auch aus der Kugel heraus (Egoansicht) bleibt die Schale sichtbar.
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
      double rim = Math.pow(1.0 - facing, 2.4);
      double current = Math.sin(longitude * 3.0 + latitude * 5.0 - time * 0.22 + phase);
      double secondCurrent = Math.sin(longitude * -5.0 + latitude * 2.0 + time * 0.13 + phase);
      double energy = Math.pow(Math.max(0.0, current * 0.65 + secondCurrent * 0.35), 5.0);
      float wave = (float) (1.0 + Math.sin(longitude * 4.0 + latitude * 3.0 + time * 0.09 + phase) * 0.012);
      float alpha = (float) ((0.025 + rim * 0.17 + energy * 0.105) * strength);
      float red = (float) (0.08 + energy * 0.34);
      float green = (float) (0.58 + energy * 0.34);
      float blue = 1.0F;
      return new SurfaceVertex(nx * radius * wave, ny * radius * wave, nz * radius * wave,
         red, green, blue, alpha);
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

   private record ShieldFrame(float x, float y, float z, float time, float intensity) {
   }

   private record SurfaceVertex(float x, float y, float z, float red, float green, float blue, float alpha) {
   }
}
