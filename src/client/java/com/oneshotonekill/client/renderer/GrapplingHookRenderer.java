package com.oneshotonekill.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.client.state.ClientStates.GrapplePullState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.Util;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Das Seil des Grappling Hooks als Bestandteil desselben sichtbaren Schusses wie der Haken.
 * <p>
 * <p>Der Hakenpunkt kommt vom Server. Die Mündung wird dagegen nicht mehr aus Augenposition und
 * geschätzten Offsets gewonnen: Ein unsichtbarer {@link SpecialModelRenderer} sitzt als weitere
 * Ebene im Grappler-Item und liest die echte Modellmatrix am lokalen Rohrmund aus. Damit wirken
 * automatisch First-Person-Transformation, Hand-Bobbing, F5-Armhaltung und die Grappler-Neigung
 * auf denselben Punkt.</p>
 * <p>
 * <p>Das Kabel selbst bleibt im Weltpass. First-Person-Gegenstände werden nach der Welt mit
 * geleertem Tiefenpuffer gezeichnet; dort ein 38 Meter langes Seil zu zeichnen ließe es durch
 * Wände scheinen. Deshalb speichert der Spezial-Layer nur die unveränderliche Messung, und der
 * nächste Welt-Frame setzt sie relativ zur aktuellen Kamera beziehungsweise Spielerposition ein.</p>
 */
@SuppressWarnings({"NullableProblems", "resource"})
public final class GrapplingHookRenderer implements SpecialModelRenderer<GrapplingHookRenderer.CaptureArgument> {
   public static final GrapplingHookRenderer INSTANCE = new GrapplingHookRenderer();

   private static final RenderStateDataKey<List<RopeFrame>> FRAMES =
      RenderStateDataKey.create(() -> OneShotOneKill.MOD_ID + ":grappling_hook_ropes");

   /** Lokaler Rohrmund des generierten Modells: (8, 9, 0.7) von 16 Modelleinheiten. */
   private static final Vector3f MODEL_MUZZLE = new Vector3f(0.5F, 0.5625F, 0.04375F);
   private static final int SIDES = 8;
   private static final float RADIUS = 0.028F;
   private static final float SECTION_LENGTH = 0.58F;
   private static final double FIRST_PERSON_WORLD_DISTANCE = 1.05;
   private static final long CAPTURE_MAX_AGE_NANOS = 250_000_000L;
   /** Hintere Seilbuchse des 0,60 Blöcke langen Hakenmodells, vom Modellmittelpunkt aus. */
   private static final double HOOK_SOCKET_OFFSET = 0.285;
   private static final float[] ROPE_DARK = {0.035F, 0.040F, 0.045F};
   private static final float[] ROPE_MID = {0.095F, 0.105F, 0.115F};
   private static final float[] ROPE_LIGHT = {0.18F, 0.195F, 0.205F};
   private static final ConcurrentHashMap<UUID, CapturedMuzzle> CAPTURES = new ConcurrentHashMap<>();

   private GrapplingHookRenderer() {
   }

   public static void register() {
      LevelExtractionEvents.END_EXTRACTION.register(GrapplingHookRenderer::onExtract);
      LevelRenderEvents.COLLECT_SUBMITS.register(GrapplingHookRenderer::onSubmit);
   }

   public static void clear() {
      CAPTURES.clear();
   }

   /**
    * Baut die unveränderlichen Angaben für den Matrix-Capture-Layer des tatsächlich gehaltenen
    * Grapplers. Bei GUI-, Boden- und falscher Handlage gibt es bewusst keinen Layer.
    */
   public static @Nullable CaptureArgument captureArgument(LivingEntity holder, ItemDisplayContext context) {
      if (!isHeldContext(context) || !GrapplePullState.INSTANCE.isGrappleActive(holder.getUUID())) {
         return null;
      }

      boolean itemOnlyInOffhand = holder.getOffhandItem().is(com.oneshotonekill.registry.ModItems.GRAPPLING_HOOK)
         && !holder.getMainHandItem().is(com.oneshotonekill.registry.ModItems.GRAPPLING_HOOK);
      HumanoidArm itemArm = itemOnlyInOffhand ? holder.getMainArm().getOpposite() : holder.getMainArm();
      if (context.leftHand() != (itemArm == HumanoidArm.LEFT)) {
         return null;
      }

      Minecraft client = Minecraft.getInstance();
      float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
      var cameraState = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
      boolean firstPerson = holder == client.player && context.firstPerson();
      return new CaptureArgument(holder.getUUID(), firstPerson, cameraState.pos,
         holder.getPosition(partialTick), new Matrix4f(cameraState.viewRotationMatrix));
   }

   /** Liest den Rohrmund aus genau dem PoseStack, der unmittelbar danach das sichtbare Item zeichnet. */
   @Override
   public void submit(@Nullable CaptureArgument argument, PoseStack poseStack, SubmitNodeCollector collector,
                      int lightCoords, int overlayCoords, boolean hasFoil, int outlineColor) {
      if (argument == null) {
         return;
      }

      Vector3f renderedMuzzle = new Vector3f(MODEL_MUZZLE).mulPosition(poseStack.last().pose());
      long capturedAt = Util.getNanos();
      if (argument.firstPerson) {
         // Der Item-PoseStack beginnt mit der inversen Weltansicht. Zurück in Kameraachsen
         // bleibt deshalb nur die echte Bildschirmposition der Modellmündung übrig.
         Vector3f cameraLocal = argument.viewRotation.transformPosition(renderedMuzzle);
         CAPTURES.put(argument.player, CapturedMuzzle.firstPerson(cameraLocal, capturedAt));
      } else {
         Vec3 worldMuzzle = argument.cameraPosition.add(renderedMuzzle.x, renderedMuzzle.y, renderedMuzzle.z);
         CAPTURES.put(argument.player,
            CapturedMuzzle.thirdPerson(worldMuzzle.subtract(argument.playerPosition), capturedAt));
      }
   }

   @Override
   public void getExtents(Consumer<Vector3fc> output) {
      output.accept(new Vector3f(MODEL_MUZZLE));
   }

   @Override
   public @Nullable CaptureArgument extractArgument(ItemStack stack) {
      return null;
   }

   /** Kopiert nur unveränderliche Koordinaten über die Grenze zum Renderthread. */
   private static void onExtract(LevelExtractionContext context) {
      float partialTick = context.deltaTracker().getGameTimeDeltaPartialTick(true);
      Vec3 camera = context.camera().position();
      List<RopeFrame> frames = new ArrayList<>();

      for (AbstractClientPlayer player : context.level().players()) {
         Vec3 hook = GrapplePullState.INSTANCE.hookPosition(player.getUUID(), partialTick);
         if (hook == null || player.isInvisible()) {
            continue;
         }

         CapturedMuzzle capture = CAPTURES.get(player.getUUID());
         if (capture == null || Util.getNanos() - capture.capturedAt > CAPTURE_MAX_AGE_NANOS) {
            continue;
         }
         boolean localFirstPerson = player == Minecraft.getInstance().player
            && Minecraft.getInstance().options.getCameraType().isFirstPerson();
         if (capture.firstPerson != localFirstPerson) {
            continue;
         }

         Vec3 muzzle;
         if (localFirstPerson) {
            // Hand und Welt können verschiedene FOVs besitzen. Nur X/Y werden umgerechnet;
            // anschließend wird der Bildschirmstrahl durch die aktuelle Kamera zurück in die
            // Welt gedreht. So bleibt der Anschluss auch bei Sprint-FOV und Kamerarolle deckungsgleich.
            float worldFov = context.camera().getFov();
            float handFov = Minecraft.getInstance().gameRenderer.gameRenderState()
               .levelRenderState.cameraRenderState.hudFov;
            double fovScale = Math.tan(Math.toRadians(worldFov * 0.5))
               / Math.tan(Math.toRadians(handFov * 0.5));
            Vector3f cameraRay = new Vector3f(
               (float) (capture.offset.x * fovScale),
               (float) (capture.offset.y * fovScale),
               (float) capture.offset.z);
            Matrix4f inverseView = new Matrix4f(Minecraft.getInstance().gameRenderer.gameRenderState()
               .levelRenderState.cameraRenderState.viewRotationMatrix).invert();
            inverseView.transformDirection(cameraRay).normalize().mul((float) FIRST_PERSON_WORLD_DISTANCE);
            muzzle = camera.add(cameraRay.x, cameraRay.y, cameraRay.z);
         } else {
            double x = net.minecraft.util.Mth.lerp(partialTick, player.xo, player.getX());
            double y = net.minecraft.util.Mth.lerp(partialTick, player.yo, player.getY());
            double z = net.minecraft.util.Mth.lerp(partialTick, player.zo, player.getZ());
            muzzle = new Vec3(x, y, z).add(capture.offset);
         }
         Vec3 toHook = hook.subtract(muzzle);
         if (toHook.lengthSqr() < 0.0025) {
            continue;
         }
         // Der synchronisierte Punkt ist das Zentrum des Pömpelmodells. Das Kabel gehört an
         // dessen hintere Buchse, also auf der Spielerseite – nicht mitten in die rote Schüssel.
         Vec3 socket = hook.subtract(toHook.normalize().scale(HOOK_SOCKET_OFFSET));
         Vec3 delta = socket.subtract(muzzle);
         if (delta.lengthSqr() < 0.0025) {
            continue;
         }

         frames.add(new RopeFrame(
            (float) (muzzle.x - camera.x),
            (float) (muzzle.y - camera.y),
            (float) (muzzle.z - camera.z),
            (float) delta.x, (float) delta.y, (float) delta.z));
      }

      context.levelState().setData(FRAMES, frames.isEmpty() ? null : List.copyOf(frames));
   }

   private static void onSubmit(LevelRenderContext context) {
      LevelRenderState levelState = context.levelState();
      List<RopeFrame> frames = levelState.getData(FRAMES);
      if (frames == null) {
         return;
      }

      PoseStack poseStack = context.poseStack();
      for (RopeFrame frame : frames) {
         poseStack.pushPose();
         poseStack.translate(frame.x, frame.y, frame.z);
         context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.debugQuads(),
            (pose, buffer) -> renderRope(pose.pose(), buffer, frame));
         poseStack.popPose();
      }
   }

   /** Ein leicht verdrilltes achteckiges Kabel statt einer flachen, aus manchen Winkeln unsichtbaren Linie. */
   private static void renderRope(Matrix4fc pose, VertexConsumer buffer, RopeFrame frame) {
      Vec3 axis = new Vec3(frame.dx, frame.dy, frame.dz);
      double length = axis.length();
      if (length < 0.05) {
         return;
      }

      Vec3 direction = axis.scale(1.0 / length);
      Vec3 reference = Math.abs(direction.y) < 0.92 ? new Vec3(0.0, 1.0, 0.0) : new Vec3(1.0, 0.0, 0.0);
      Vec3 side = direction.cross(reference).normalize().scale(RADIUS);
      Vec3 up = direction.cross(side).normalize().scale(RADIUS);
      int sections = Math.clamp((int) Math.ceil(length / SECTION_LENGTH), 1, 64);

      for (int section = 0; section < sections; section++) {
         double from = section / (double) sections;
         double to = (section + 1) / (double) sections;
         // Die geringe Drehung je Abschnitt lässt die acht Längsflächen wie ein geflochtenes
         // schwarzes Seil lesen, ohne eine lange Textur über dutzende Blöcke zu strecken.
         double phaseFrom = from * length * 2.4;
         double phaseTo = to * length * 2.4;
         Vec3 centerFrom = axis.scale(from);
         Vec3 centerTo = axis.scale(to);

         for (int face = 0; face < SIDES; face++) {
            double angle0 = face * Math.PI * 2.0 / SIDES;
            double angle1 = (face + 1) * Math.PI * 2.0 / SIDES;
            Vec3 a = centerFrom.add(radial(side, up, angle0 + phaseFrom));
            Vec3 b = centerTo.add(radial(side, up, angle0 + phaseTo));
            Vec3 c = centerTo.add(radial(side, up, angle1 + phaseTo));
            Vec3 d = centerFrom.add(radial(side, up, angle1 + phaseFrom));
            float[] colour = face % 4 == 0 ? ROPE_LIGHT : face % 2 == 0 ? ROPE_MID : ROPE_DARK;
            quadBoth(pose, buffer, a, b, c, d, colour);
         }
      }
   }

   private static Vec3 radial(Vec3 side, Vec3 up, double angle) {
      return side.scale(Math.cos(angle)).add(up.scale(Math.sin(angle)));
   }

   private static void quadBoth(Matrix4fc pose, VertexConsumer buffer,
                                Vec3 a, Vec3 b, Vec3 c, Vec3 d, float[] colour) {
      quad(pose, buffer, a, b, c, d, colour);
      quad(pose, buffer, d, c, b, a, colour);
   }

   private static void quad(Matrix4fc pose, VertexConsumer buffer,
                            Vec3 a, Vec3 b, Vec3 c, Vec3 d, float[] colour) {
      vertex(pose, buffer, a, colour);
      vertex(pose, buffer, b, colour);
      vertex(pose, buffer, c, colour);
      vertex(pose, buffer, d, colour);
   }

   private static void vertex(Matrix4fc pose, VertexConsumer buffer, Vec3 point, float[] colour) {
      buffer.addVertex(pose, (float) point.x, (float) point.y, (float) point.z)
         .setColor(colour[0], colour[1], colour[2], 1.0F);
   }

   /** Startpunkt relativ zur Kamera und Delta bis zum Haken. */
   private record RopeFrame(float x, float y, float z, float dx, float dy, float dz) {
   }

   public record CaptureArgument(UUID player, boolean firstPerson, Vec3 cameraPosition,
                                 Vec3 playerPosition, Matrix4f viewRotation) {
   }

   private record CapturedMuzzle(boolean firstPerson, Vec3 offset, long capturedAt) {
      private static CapturedMuzzle firstPerson(Vector3f cameraLocal, long capturedAt) {
         return new CapturedMuzzle(true,
            new Vec3(cameraLocal.x, cameraLocal.y, cameraLocal.z), capturedAt);
      }

      private static CapturedMuzzle thirdPerson(Vec3 playerOffset, long capturedAt) {
         return new CapturedMuzzle(false, playerOffset, capturedAt);
      }
   }

   private static boolean isHeldContext(ItemDisplayContext context) {
      return context.firstPerson() || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
         || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
   }
}
