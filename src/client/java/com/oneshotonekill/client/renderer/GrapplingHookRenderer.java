package com.oneshotonekill.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.client.state.ClientStates.GrapplePullState;
import com.oneshotonekill.registry.ModItems;
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
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Das Seil des Grappling Hooks.
 * <p>
 * Die Mündung wird direkt aus der gerenderten Modellmatrix des tatsächlich gehaltenen Items
 * ausgelesen. Der Hakenpunkt wird bildgenau interpoliert und mit dem 3D-verdrillten Seil verbunden.
 */
@SuppressWarnings({"NullableProblems", "SameParameterValue", "resource", "unused"})
public final class GrapplingHookRenderer implements SpecialModelRenderer<GrapplingHookRenderer.CaptureArgument> {
    public static final GrapplingHookRenderer INSTANCE = new GrapplingHookRenderer();

    private static final RenderStateDataKey<List<RopeFrame>> FRAMES =
            RenderStateDataKey.create(() -> OneShotOneKill.MOD_ID + ":grappling_hook_ropes");

    /**
     * Lokaler Rohrmund des generierten Modells: (8, 9, 0.7) von 16 Modelleinheiten.
     */
    private static final Vector3f MODEL_MUZZLE = new Vector3f(0.5F, 0.5625F, 0.04375F);
    private static final int SIDES = 8;
    private static final float RADIUS = 0.028F;
    private static final float SECTION_LENGTH = 0.58F;
    private static final long CAPTURE_MAX_AGE_NANOS = 1_000_000_000L;
    /**
     * Hintere Seilbuchse des 0,60 Blöcke langen Hakenmodells, vom Modellmittelpunkt aus.
     */
    private static final double HOOK_SOCKET_OFFSET = 0.285;
    private static final float[] ROPE_DARK = {0.035F, 0.040F, 0.045F};
    private static final float[] ROPE_MID = {0.095F, 0.105F, 0.115F};
    private static final float[] ROPE_LIGHT = {0.18F, 0.195F, 0.205F};
    private static final float[] ROPE_ENERGY_CORE = {0.20F, 0.75F, 0.95F};
    private static final float[] ROPE_ENERGY_PULSE = {0.75F, 0.95F, 1.0F};
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
     * Ermittelt die exakte 3D-Weltposition der Waffenmündung aus dem letzten Capture
     * oder berechnet den zuverlässigen geometrischen Fallback.
     */
    public static Vec3 calculateMuzzle(LivingEntity player, float partialTick) {
        Minecraft client = Minecraft.getInstance();
        var cameraState = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        Vec3 camera = cameraState.pos;
        boolean localFirstPerson = player == client.player
                && client.options.getCameraType().isFirstPerson();
        CapturedMuzzle capture = CAPTURES.get(player.getUUID());

        if (capture != null && Util.getNanos() - capture.capturedAt <= CAPTURE_MAX_AGE_NANOS) {
            if (localFirstPerson && capture.firstPerson) {
                float handFov = cameraState.hudFov > 0.0F ? cameraState.hudFov : 70.0F;
                double worldTan = Math.abs(cameraState.projectionMatrix.m11()) > 0.01F
                        ? (1.0 / Math.abs(cameraState.projectionMatrix.m11()))
                        : Math.tan(Math.toRadians(client.options.fov().get().floatValue() * 0.5));
                double handTan = Math.tan(Math.toRadians(handFov * 0.5));
                double fovScale = handTan > 1.0E-4 ? (worldTan / handTan) : 1.0;

                Vector3f cameraRay = new Vector3f(
                        (float) (capture.offset.x * fovScale),
                        (float) (capture.offset.y * fovScale),
                        (float) capture.offset.z);

                double rayLen = cameraRay.length();
                Vec3 hookPos = GrapplePullState.INSTANCE.hookPosition(player.getUUID(), partialTick);
                if (hookPos != null) {
                    double distToHook = hookPos.distanceTo(camera);
                    double maxRayLen = Math.max(0.08, distToHook - 0.15);
                    if (rayLen > maxRayLen) {
                        cameraRay.mul((float) (maxRayLen / rayLen));
                    }
                }

                Matrix4f inverseView = new Matrix4f(cameraState.viewRotationMatrix).invert();
                inverseView.transformDirection(cameraRay);
                return camera.add(cameraRay.x, cameraRay.y, cameraRay.z);
            } else if (!localFirstPerson && !capture.firstPerson) {
                double x = Mth.lerp(partialTick, player.xo, player.getX());
                double y = Mth.lerp(partialTick, player.yo, player.getY());
                double z = Mth.lerp(partialTick, player.zo, player.getZ());
                return new Vec3(x, y, z).add(capture.offset);
            }
        }

        Vec3 look = player.getViewVector(partialTick).normalize();
        Vec3 up = player.getUpVector(partialTick).normalize();
        Vec3 weaponSide = player.getHandHoldingItemAngle(ModItems.GRAPPLING_HOOK);
        double forwardDist = 0.65;
        Vec3 hookPos = GrapplePullState.INSTANCE.hookPosition(player.getUUID(), partialTick);
        if (hookPos != null) {
            double dist = hookPos.distanceTo(player.getEyePosition(partialTick));
            if (dist < 0.85) {
                forwardDist = Math.max(0.1, dist - 0.2);
            }
        }
        return player.getEyePosition(partialTick)
                .add(look.scale(forwardDist))
                .add(weaponSide.scale(0.38))
                .add(up.scale(-0.25));
    }

    /**
     * Baut die unveränderlichen Angaben für den Matrix-Capture-Layer des tatsächlich gehaltenen
     * Grapplers. Sobald der Grappler in der Hand liegt, wird die Mündung laufend erfasst.
     */
    public static @Nullable CaptureArgument captureArgument(LivingEntity holder, ItemDisplayContext context) {
        if (!isHeldContext(context)) {
            return null;
        }

        boolean itemOnlyInOffhand = holder.getOffhandItem().is(ModItems.GRAPPLING_HOOK)
                && !holder.getMainHandItem().is(ModItems.GRAPPLING_HOOK);
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

    /**
     * Liest den Rohrmund aus genau dem PoseStack, der unmittelbar danach das sichtbare Item zeichnet.
     */
    @Override
    public void submit(@Nullable CaptureArgument argument, PoseStack poseStack, SubmitNodeCollector collector,
                       int lightCoords, int overlayCoords, boolean hasFoil, int outlineColor) {
        if (argument == null) {
            return;
        }

        Vector3f renderedMuzzle = new Vector3f(MODEL_MUZZLE).mulPosition(poseStack.last().pose());
        long capturedAt = Util.getNanos();
        if (argument.firstPerson) {
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

    /**
     * Kopiert nur unveränderliche Koordinaten über die Grenze zum Renderthread.
     */
    private static void onExtract(LevelExtractionContext context) {
        float partialTick = context.deltaTracker().getGameTimeDeltaPartialTick(true);
        Vec3 camera = context.camera().position();
        List<RopeFrame> frames = new ArrayList<>();

        for (AbstractClientPlayer player : context.level().players()) {
            Vec3 hook = GrapplePullState.INSTANCE.hookPosition(player.getUUID(), partialTick);
            if (hook == null || player.isInvisible()
                    || (!player.getMainHandItem().is(ModItems.GRAPPLING_HOOK) && !player.getOffhandItem().is(ModItems.GRAPPLING_HOOK))) {
                continue;
            }

            Vec3 muzzle = calculateMuzzle(player, partialTick);
            Vec3 toHook = hook.subtract(muzzle);
            double toHookLen = toHook.length();
            double maxSocketOffset = Math.min(HOOK_SOCKET_OFFSET, toHookLen * 0.45);
            Direction hitDir = GrapplePullState.INSTANCE.hitDirection(player.getUUID());
            Vec3 socket;
            if (hitDir != null && GrapplePullState.INSTANCE.isPulling(player.getUUID())) {
                Vec3 normal = hitDir.getUnitVec3();
                socket = hook.add(normal.scale(maxSocketOffset));
            } else {
                socket = toHookLen > 0.02
                        ? hook.subtract(toHook.scale(maxSocketOffset / toHookLen))
                        : hook;
            }
            Vec3 delta = socket.subtract(muzzle);

            boolean pulling = GrapplePullState.INSTANCE.isPulling(player.getUUID());
            boolean retracting = GrapplePullState.INSTANCE.isRetracting(player.getUUID());
            float gameTime = player.tickCount + partialTick;

            frames.add(new RopeFrame(
                    (float) (muzzle.x - camera.x),
                    (float) (muzzle.y - camera.y),
                    (float) (muzzle.z - camera.z),
                    (float) delta.x, (float) delta.y, (float) delta.z,
                    pulling, retracting, gameTime));
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
            if (frame.dx * frame.dx + frame.dy * frame.dy + frame.dz * frame.dz >= 0.0025F) {
                poseStack.pushPose();
                poseStack.translate(frame.x, frame.y, frame.z);
                context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.debugQuads(),
                        (pose, buffer) -> renderRope(pose.pose(), buffer, frame));
                poseStack.popPose();
            }
        }
    }

    /**
     * Ein dynamisch durchhängendes oder straff pulsierendes achteckiges Kabel.
     */
    private static void renderRope(Matrix4fc pose, VertexConsumer buffer, RopeFrame frame) {
        Vec3 axis = new Vec3(frame.dx, frame.dy, frame.dz);
        double length = axis.length();
        if (length < 0.05) {
            return;
        }

        int sections = Math.clamp((int) Math.ceil(length / SECTION_LENGTH), 4, 64);
        double maxSag = frame.pulling ? 0.0 : (frame.retracting ? Math.min(1.3, length * 0.09) : Math.min(0.9, length * 0.065));

        for (int section = 0; section < sections; section++) {
            double from = section / (double) sections;
            double to = (section + 1) / (double) sections;
            double phaseFrom = from * length * 2.4;
            double phaseTo = to * length * 2.4;

            double sagFrom = 4.0 * from * (1.0 - from) * maxSag;
            double sagTo = 4.0 * to * (1.0 - to) * maxSag;
            Vec3 centerFrom = axis.scale(from).add(0.0, -sagFrom, 0.0);
            Vec3 centerTo = axis.scale(to).add(0.0, -sagTo, 0.0);

            Vec3 segment = centerTo.subtract(centerFrom);
            double segLength = segment.length();
            if (segLength < 1.0E-4) {
                continue;
            }
            Vec3 direction = segment.scale(1.0 / segLength);
            Vec3 reference = Math.abs(direction.y) < 0.92 ? new Vec3(0.0, 1.0, 0.0) : new Vec3(1.0, 0.0, 0.0);
            Vec3 side = direction.cross(reference).normalize().scale(RADIUS);
            Vec3 up = direction.cross(side).normalize().scale(RADIUS);

            float energyPulse = frame.pulling
                    ? (float) Math.sin(from * 28.0 - frame.gameTime * 1.8F)
                    : -1.0F;

            for (int face = 0; face < SIDES; face++) {
                double angle0 = face * Math.PI * 2.0 / SIDES;
                double angle1 = (face + 1) * Math.PI * 2.0 / SIDES;
                Vec3 a = centerFrom.add(radial(side, up, angle0 + phaseFrom));
                Vec3 b = centerTo.add(radial(side, up, angle0 + phaseTo));
                Vec3 c = centerTo.add(radial(side, up, angle1 + phaseTo));
                Vec3 d = centerFrom.add(radial(side, up, angle1 + phaseFrom));
                float[] colour;
                if (frame.pulling && (face % 4 == 0)) {
                    colour = energyPulse > 0.35F ? ROPE_ENERGY_PULSE : ROPE_ENERGY_CORE;
                } else {
                    colour = face % 4 == 0 ? ROPE_LIGHT : face % 2 == 0 ? ROPE_MID : ROPE_DARK;
                }
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

    /**
     * Startpunkt relativ zur Kamera und Delta bis zur Buchse samt Zug- und Statusdaten.
     */
    private record RopeFrame(float x, float y, float z, float dx, float dy, float dz,
                             boolean pulling, boolean retracting, float gameTime) {
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
