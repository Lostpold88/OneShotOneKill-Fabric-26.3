package com.oneshotonekill.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.client.effect.BoogieBombClient;
import com.oneshotonekill.client.effect.BoogieDanceAnimation;
import com.oneshotonekill.registry.ModItems;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Clientseitiges High-FPS Rendering der 3D-Discokugel und authentischer Fortnite-Scheinwerferstrahlen.
 * <p>
 * Die 3D-Discokugel (ModItems.BOOGIE_DISCO_BALL) rotiert starr zentriert exakt über dem Kopf des Tänzers.
 * Die Scheinwerferstrahlen fächern sich als runde, volumetrische Lichtkegel mit weicher Transparenz
 * auf und projizieren sanft auslaufende, runde Lichtflecken auf den Boden.
 */
@SuppressWarnings({"resource", "DuplicatedCode"})
public final class BoogieDiscoRenderer {
    private static final RenderStateDataKey<List<DiscoFrame>> FRAMES =
            RenderStateDataKey.create(() -> OneShotOneKill.MOD_ID + ":boogie_disco_frames");

    private static final ItemStackRenderState DISCO_BALL_RENDER_STATE = new ItemStackRenderState();
    private static ItemStack discoBallStack;

    private static ItemStack getDiscoBallStack() {
        if (discoBallStack == null) {
            discoBallStack = new ItemStack(ModItems.BOOGIE_DISCO_BALL);
        }
        return discoBallStack;
    }

    /** Fluoreszierende Neon-Farbpalette nach Fortnite-Original. */
    private static final float[][] DISCO_COLORS = {
            {0.45F, 1.00F, 0.25F}, // Limettengrün
            {1.00F, 0.25F, 0.85F}, // Neon-Pink
            {0.18F, 0.90F, 1.00F}, // Cyan / Türkis
            {1.00F, 0.95F, 0.20F}, // Sonnengelb
            {0.70F, 0.35F, 1.00F}, // Neon-Lila
            {1.00F, 0.58F, 0.15F}, // Neon-Orange
            {0.25F, 1.00F, 0.60F}, // Frühlingsgrün
            {1.00F, 0.18F, 0.60F}  // Tiefes Magenta
    };

    private BoogieDiscoRenderer() {}

    public static void register() {
        LevelExtractionEvents.END_EXTRACTION.register(BoogieDiscoRenderer::onExtract);
        LevelRenderEvents.COLLECT_SUBMITS.register(BoogieDiscoRenderer::onSubmit);
    }

    public static void tick() {
        // Zustand wird direkt aus BoogieBombClient abgeleitet
    }

    public static void clear() {
        DISCO_BALL_RENDER_STATE.clear();
        discoBallStack = null;
    }

    private static void onExtract(LevelExtractionContext context) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.level != context.level()) {
            context.levelState().setData(FRAMES, null);
            return;
        }

        BoogieBombClient.checkCamera(client);
        List<DiscoFrame> frames = new ArrayList<>();
        float partialTick = context.deltaTracker().getGameTimeDeltaPartialTick(true);

        Vec3 camPos = context.camera().position();

        for (AbstractClientPlayer player : client.level.players()) {
            if (player.isSpectator() || player.isInvisible()) continue;
            UUID id = player.getUUID();
            float weight = BoogieBombClient.getWeight(id);
            if (weight <= 0.001F) continue;

            double px = Mth.lerp(partialTick, player.xo, player.getX());
            double py = Mth.lerp(partialTick, player.yo, player.getY());
            double pz = Mth.lerp(partialTick, player.zo, player.getZ());

            float seconds = BoogieBombClient.seconds(id);
            double hover = Math.sin(seconds * 5.0) * 0.04;
            double saltoLift = BoogieDanceAnimation.saltoBallOffset(seconds, id) * weight;
            double ballY = py + player.getBbHeight() + 0.85 + hover + saltoLift;

            float relBallX = (float) (px - camPos.x);
            float relBallY = (float) (ballY - camPos.y);
            float relBallZ = (float) (pz - camPos.z);

            double rayStartY = ballY - 0.12 * weight;
            Vec3 rayStart = new Vec3(px, rayStartY, pz);

            int beamCount = 8;
            float baseRadius = (2.6F + (float) Math.sin(seconds * 3.14F) * 0.35F) * weight;
            float rotSpeed = seconds * 2.2F;

            BeamData[] beams = new BeamData[beamCount];
            double nominalDepth = Math.max(1.5, rayStartY - py);

            for (int i = 0; i < beamCount; i++) {
                float angle = rotSpeed + i * (float) (Math.PI * 2.0 / beamCount);
                float cos = (float) Math.cos(angle);
                float sin = (float) Math.sin(angle);

                float sweepRadius = baseRadius * (0.88F + 0.24F * (float) Math.sin(seconds * 4.0F + i * 1.2F));

                // Schräg nach außen-unten gerichteter Richtungsvektor
                double dirX = cos * sweepRadius;
                double dirY = -nominalDepth;
                double dirZ = sin * sweepRadius;
                double dirLen = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
                if (dirLen < 0.001) dirLen = 1.0;
                Vec3 normDir = new Vec3(dirX / dirLen, dirY / dirLen, dirZ / dirLen);

                // Raycast bis zu 32 Blöcke nach unten/außen, um den tatsächlichen Bodenblock zu treffen
                Vec3 rayEnd = rayStart.add(normDir.scale(32.0));
                BlockHitResult hit = client.level.clip(new ClipContext(
                        rayStart, rayEnd,
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                        CollisionContext.empty()
                ));

                Vec3 hitPos = hit.getType() == HitResult.Type.BLOCK ? hit.getLocation() : rayEnd;
                double hitDist = rayStart.distanceTo(hitPos);

                // Kegelradius skaliert sanft mit der Strahllänge
                float rBot = (float) Math.clamp(0.42F * weight * (hitDist / nominalDepth), 0.25F * weight, 1.35F * weight);
                float distFactor = (float) Math.clamp(nominalDepth / Math.max(1.0, hitDist), 0.35, 1.0);

                beams[i] = new BeamData(
                        (float) (hitPos.x - camPos.x),
                        (float) (hitPos.y - camPos.y),
                        (float) (hitPos.z - camPos.z),
                        cos, sin, rBot, distFactor
                );
            }

            frames.add(new DiscoFrame(relBallX, relBallY, relBallZ, seconds, weight, beams));
        }

        if (!frames.isEmpty()) {
            client.getItemModelResolver().updateForTopItem(
                    DISCO_BALL_RENDER_STATE,
                    getDiscoBallStack(),
                    ItemDisplayContext.FIXED,
                    client.level,
                    null,
                    0
            );
            context.levelState().setData(FRAMES, frames);
        } else {
            context.levelState().setData(FRAMES, null);
        }
    }

    private static void onSubmit(LevelRenderContext context) {
        LevelRenderState levelState = context.levelState();
        List<DiscoFrame> frames = levelState.getData(FRAMES);
        if (frames == null || frames.isEmpty()) return;

        PoseStack poseStack = context.poseStack();

        for (DiscoFrame frame : frames) {
            float scale = frame.weight;
            if (scale <= 0.001F) continue;

            // 1. Authentisches 3D-Item-Modell der Discokugel (ModItems.BOOGIE_DISCO_BALL)
            // Starr direkt über dem Kopf zentriert, rotiert um die eigene Mittelachse
            if (!DISCO_BALL_RENDER_STATE.isEmpty()) {
                poseStack.pushPose();
                poseStack.translate(frame.relBallX, frame.relBallY, frame.relBallZ);
                poseStack.rotate(Axis.YP.rotation(frame.seconds * 2.2F));
                float ballScale = 1.65F * scale;
                poseStack.scale(ballScale, ballScale, ballScale);
                DISCO_BALL_RENDER_STATE.submit(
                        poseStack,
                        context.submitNodeCollector(),
                        Brightness.FULL_BRIGHT.pack(),
                        OverlayTexture.NO_OVERLAY,
                        0
                );
                poseStack.popPose();
            }

            // 2. Realistische, runde volumetrische Scheinwerfer-Lichtstrahlen & Boden-Lichtkreise
            // debugQuads schreibt nicht in den Depth-Buffer und nutzt den regulären Translucent-Pass,
            // sodass Glas, Wasser und transluzente Blöcke auch aus der First-Person-Sicht dahinter sichtbar bleiben.
            context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.debugQuads(), (pose, buffer) -> {
                Matrix4fc m = pose.pose();
                float relX = frame.relBallX;
                float relY = frame.relBallY;
                float relZ = frame.relBallZ;

                renderSpotlightBeams(m, buffer, relX, relY, relZ, frame.beams, scale);
            });
        }
    }

    /**
     * 8 runde, volumetrische Spotlight-Lichtkegel mit weicher Transparenz und runden Bodenflecken,
     * die per Raycast den tatsächlichen Bodenblock treffen (auch über Kanten hinweg).
     */
    private static void renderSpotlightBeams(Matrix4fc pose, VertexConsumer buffer,
                                             float bx, float by, float bz,
                                             BeamData[] beams,
                                             float scale) {
        float topY = by - 0.12F * scale;
        int radialSegments = 12;

        for (int i = 0; i < beams.length; i++) {
            BeamData beam = beams[i];
            float[] col = DISCO_COLORS[i % DISCO_COLORS.length];
            float r = col[0], g = col[1], b = col[2];

            // Zielpunkt auf dem tatsächlichen Bodenblock
            float groundX = beam.relX();
            float groundY = beam.relY();
            float groundZ = beam.relZ();

            // Richtungsvektor vom Kugelursprung zum Boden
            float dx = groundX - bx;
            float dy = groundY - topY;
            float dz = groundZ - bz;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 0.01F) continue;
            float dirX = dx / len, dirY = dy / len, dirZ = dz / len;

            // Orthonormale Basis senkrecht zum Strahl
            float ux = -dirZ;
            float uz = dirX;
            float uLen = (float) Math.sqrt(ux * ux + uz * uz);
            if (uLen < 0.001F) {
                ux = 1.0F;
                uz = 0.0F;
            } else {
                ux /= uLen;
                uz /= uLen;
            }
            float vx = dirY * uz;
            float vy = dirZ * ux - dirX * uz;
            float vz = -dirY * ux;

            // Radien: Oben schlanker Hals an der Kugel, unten weit aufgefächert
            float rTop = 0.04F * scale;
            float rBot = beam.rBot();

            // Transparenz: Sanfte, unaufdringliche atmosphärische Lichtstreuung
            float distFactor = beam.distFactor();
            float alphaTop = 0.16F * scale;
            float alphaBot = 0.05F * scale * distFactor;

            // Runder 12-eckiger Kegelstumpf
            for (int seg = 0; seg < radialSegments; seg++) {
                double a0 = seg * Math.PI * 2.0 / radialSegments;
                double a1 = (seg + 1) * Math.PI * 2.0 / radialSegments;
                float ca0 = (float) Math.cos(a0), sa0 = (float) Math.sin(a0);
                float ca1 = (float) Math.cos(a1), sa1 = (float) Math.sin(a1);

                // Oben
                float t0x = bx + (ux * ca0 + vx * sa0) * rTop;
                float t0y = topY + vy * sa0 * rTop;
                float t0z = bz + (uz * ca0 + vz * sa0) * rTop;

                float t1x = bx + (ux * ca1 + vx * sa1) * rTop;
                float t1y = topY + vy * sa1 * rTop;
                float t1z = bz + (uz * ca1 + vz * sa1) * rTop;

                // Unten am realen Boden
                float b0x = groundX + (ux * ca0 + vx * sa0) * rBot;
                float b0y = groundY + vy * sa0 * rBot;
                float b0z = groundZ + (uz * ca0 + vz * sa0) * rBot;

                float b1x = groundX + (ux * ca1 + vx * sa1) * rBot;
                float b1y = groundY + vy * sa1 * rBot;
                float b1z = groundZ + (uz * ca1 + vz * sa1) * rBot;

                quadBoth(pose, buffer,
                        t0x, t0y, t0z, alphaTop,
                        t1x, t1y, t1z, alphaTop,
                        b1x, b1y, b1z, alphaBot,
                        b0x, b0y, b0z, alphaBot,
                        r, g, b);
            }

            // Feiner, transparenter Kernstrahl in gleicher Farbe für sanften Helligkeitsverlauf
            float coreTop = 0.02F * scale;
            float coreBot = rBot * 0.40F;
            float coreAlphaTop = 0.10F * scale;
            float coreAlphaBot = 0.03F * scale * distFactor;
            for (int seg = 0; seg < 6; seg++) {
                double a0 = seg * Math.PI * 2.0 / 6;
                double a1 = (seg + 1) * Math.PI * 2.0 / 6;
                float ca0 = (float) Math.cos(a0), sa0 = (float) Math.sin(a0);
                float ca1 = (float) Math.cos(a1), sa1 = (float) Math.sin(a1);

                quadBoth(pose, buffer,
                        bx + (ux * ca0 + vx * sa0) * coreTop, topY + vy * sa0 * coreTop, bz + (uz * ca0 + vz * sa0) * coreTop, coreAlphaTop,
                        bx + (ux * ca1 + vx * sa1) * coreTop, topY + vy * sa1 * coreTop, bz + (uz * ca1 + vz * sa1) * coreTop, coreAlphaTop,
                        groundX + (ux * ca1 + vx * sa1) * coreBot, groundY + vy * sa1 * coreBot, groundZ + (uz * ca1 + vz * sa1) * coreBot, coreAlphaBot,
                        groundX + (ux * ca0 + vx * sa0) * coreBot, groundY + vy * sa0 * coreBot, groundZ + (uz * ca0 + vz * sa0) * coreBot, coreAlphaBot,
                        r, g, b);
            }

            // Runder, sanft nach außen auslaufender Scheinwerfer-Lichtfleck direkt auf dem Bodenblock
            renderSpotlightFloorPool(pose, buffer, groundX, groundY + 0.015F, groundZ,
                    beam.cos(), beam.sin(), rBot * 1.35F, r, g, b, 0.22F * scale * distFactor);
        }
    }

    /**
     * Runder, weich auslaufender Scheinwerfer-Lichtfleck auf dem Boden.
     * Radialer Helligkeitsgradient: Hell im Zentrum, weich auf 0.0 am Außenrand.
     */
    private static void renderSpotlightFloorPool(Matrix4fc pose, VertexConsumer buffer,
                                                 float cx, float y, float cz,
                                                 float dirX, float dirZ, float radius,
                                                 float r, float g, float b, float maxAlpha) {
        if (maxAlpha <= 0.01F) return;
        int segments = 16;
        float radAlong = radius * 1.22F;
        float radAcross = radius * 0.95F;

        // Tangentenvektor quer zur Strahlrichtung
        float tanX = -dirZ;

        float rInner = 0.03F * radius;

        for (int i = 0; i < segments; i++) {
            double a0 = i * Math.PI * 2.0 / segments;
            double a1 = (i + 1) * Math.PI * 2.0 / segments;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);

            // Innenring nahe Zentrum
            float in0x = cx + (dirX * c0 * rInner * 1.22F + tanX * s0 * rInner * 0.95F);
            float in0z = cz + (dirZ * c0 * rInner * 1.22F + dirX * s0 * rInner * 0.95F);

            float in1x = cx + (dirX * c1 * rInner * 1.22F + tanX * s1 * rInner * 0.95F);
            float in1z = cz + (dirZ * c1 * rInner * 1.22F + dirX * s1 * rInner * 0.95F);

            // Außenrand
            float out0x = cx + (dirX * c0 * radAlong + tanX * s0 * radAcross);
            float out0z = cz + (dirZ * c0 * radAlong + dirX * s0 * radAcross);

            float out1x = cx + (dirX * c1 * radAlong + tanX * s1 * radAcross);
            float out1z = cz + (dirZ * c1 * radAlong + dirX * s1 * radAcross);

            // Quad von Innenring (hell) nach Außenrand (0.0 Alpha)
            quadBoth(pose, buffer,
                    in0x, y, in0z, maxAlpha,
                    in1x, y, in1z, maxAlpha,
                    out1x, y, out1z, 0.0F,
                    out0x, y, out0z, 0.0F,
                    r, g, b);
        }
    }

    private static void quadBoth(Matrix4fc pose, VertexConsumer buffer,
                                 float x0, float y0, float z0, float a0,
                                 float x1, float y1, float z1, float a1,
                                 float x2, float y2, float z2, float a2,
                                 float x3, float y3, float z3, float a3,
                                 float r, float g, float b) {
        vertex(pose, buffer, x0, y0, z0, r, g, b, a0);
        vertex(pose, buffer, x1, y1, z1, r, g, b, a1);
        vertex(pose, buffer, x2, y2, z2, r, g, b, a2);
        vertex(pose, buffer, x3, y3, z3, r, g, b, a3);

        vertex(pose, buffer, x3, y3, z3, r, g, b, a3);
        vertex(pose, buffer, x2, y2, z2, r, g, b, a2);
        vertex(pose, buffer, x1, y1, z1, r, g, b, a1);
        vertex(pose, buffer, x0, y0, z0, r, g, b, a0);
    }

    private static void vertex(Matrix4fc pose, VertexConsumer buffer,
                               float x, float y, float z, float r, float g, float b, float a) {
        buffer.addVertex(pose, x, y, z).setColor(r, g, b, a);
    }

    private record BeamData(float relX, float relY, float relZ,
                            float cos, float sin, float rBot,
                            float distFactor) {}

    private record DiscoFrame(float relBallX, float relBallY, float relBallZ,
                              float seconds, float weight,
                              BeamData[] beams) {}
}
