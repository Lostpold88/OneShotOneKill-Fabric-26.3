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

                // Raycast bis zu 32 Blöcke nach außen-unten, um Boden, Wände, Glas und alle Objekte zu treffen
                Vec3 rayEnd = rayStart.add(normDir.scale(32.0));
                BlockHitResult hit = client.level.clip(new ClipContext(
                        rayStart, rayEnd,
                        ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                        CollisionContext.empty()
                ));

                boolean hasHit = hit.getType() == HitResult.Type.BLOCK;
                Vec3 hitPos = hasHit ? hit.getLocation() : rayEnd;
                Vec3 normal = hasHit ? hit.getDirection().getUnitVec3() : new Vec3(-normDir.x, -normDir.y, -normDir.z);
                double hitDist = rayStart.distanceTo(hitPos);

                // Kegelradius skaliert sanft mit der Strahllänge
                float rBot = (float) Math.clamp(0.42F * weight * (hitDist / nominalDepth), 0.25F * weight, 1.35F * weight);
                float distFactor = (float) Math.clamp(nominalDepth / Math.max(1.0, hitDist), 0.35, 1.0);

                beams[i] = new BeamData(
                        (float) (hitPos.x - camPos.x),
                        (float) (hitPos.y - camPos.y),
                        (float) (hitPos.z - camPos.z),
                        (float) normal.x,
                        (float) normal.y,
                        (float) normal.z,
                        cos, sin, rBot, distFactor, hasHit
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

            // Basis am Auftreffpunkt (unten am Objekt):
            // Liegt exakt auf der getroffenen Fläche (Boden, Wand, Glas, Decke)
            float b1xBasis, b1yBasis, b1zBasis;
            float b2xBasis, b2yBasis, b2zBasis;

            if (beam.hasHit()) {
                float nx = beam.normX();
                float ny = beam.normY();
                float nz = beam.normZ();

                if (Math.abs(ny) < 0.9F) {
                    // Vertikale Wandfläche (oder schräg)
                    float hLen = (float) Math.sqrt(nz * nz + nx * nx);
                    if (hLen < 0.001F) {
                        b1xBasis = 1.0F;
                        b1yBasis = 0.0F;
                        b1zBasis = 0.0F;
                    } else {
                        b1xBasis = nz / hLen;
                        b1yBasis = 0.0F;
                        b1zBasis = -nx / hLen;
                    }
                    b2xBasis = ny * b1zBasis - nz * b1yBasis;
                    b2yBasis = nz * b1xBasis - nx * b1zBasis;
                    b2zBasis = nx * b1yBasis - ny * b1xBasis;
                } else {
                    // Horizontale Boden- oder Deckenfläche
                    b1xBasis = 1.0F;
                    b1yBasis = 0.0F;
                    b1zBasis = 0.0F;
                    b2xBasis = 0.0F;
                    b2yBasis = 0.0F;
                    b2zBasis = ny > 0 ? 1.0F : -1.0F;
                }
            } else {
                // Strahl ins Leere: senkrecht zur Strahlachse
                b1xBasis = ux;
                b1yBasis = 0.0F;
                b1zBasis = uz;
                b2xBasis = vx;
                b2yBasis = vy;
                b2zBasis = vz;
            }

            // Radien: Oben schlanker Hals an der Kugel, unten am Objekt aufgefächert
            float rTop = 0.04F * scale;
            float rBot = beam.rBot();

            // Transparenz: Sanfte, unaufdringliche atmosphärische Lichtstreuung
            float distFactor = beam.distFactor();
            float alphaTop = 0.16F * scale;
            float alphaBot = 0.05F * scale * distFactor;

            // Runder 12-eckiger Kegelstumpf direkt bis zur Objektoberfläche
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

                // Unten am getroffenen Objekt
                float b0x = groundX + (b1xBasis * ca0 + b2xBasis * sa0) * rBot;
                float b0y = groundY + (b1yBasis * ca0 + b2yBasis * sa0) * rBot;
                float b0z = groundZ + (b1zBasis * ca0 + b2zBasis * sa0) * rBot;

                float b1x = groundX + (b1xBasis * ca1 + b2xBasis * sa1) * rBot;
                float b1y = groundY + (b1yBasis * ca1 + b2yBasis * sa1) * rBot;
                float b1z = groundZ + (b1zBasis * ca1 + b2zBasis * sa1) * rBot;

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
                        groundX + (b1xBasis * ca1 + b2yBasis * sa1) * coreBot, groundY + (b1yBasis * ca1 + b2yBasis * sa1) * coreBot, groundZ + (b1zBasis * ca1 + b2zBasis * sa1) * coreBot, coreAlphaBot,
                        groundX + (b1xBasis * ca0 + b2yBasis * sa0) * coreBot, groundY + (b1yBasis * ca0 + b2yBasis * sa0) * coreBot, groundZ + (b1zBasis * ca0 + b2zBasis * sa0) * coreBot, coreAlphaBot,
                        r, g, b);
            }

            // Runder, sanft nach außen auslaufender Scheinwerfer-Lichtfleck direkt auf der getroffenen Fläche (Boden, Wand, Glas)
            if (beam.hasHit()) {
                renderSpotlightSurfacePool(pose, buffer,
                        groundX, groundY, groundZ,
                        beam.normX(), beam.normY(), beam.normZ(),
                        b1xBasis, b1yBasis, b1zBasis,
                        b2xBasis, b2yBasis, b2zBasis,
                        rBot * 1.35F, r, g, b, 0.22F * scale * distFactor);
            }
        }
    }

    /**
     * Runder, weich auslaufender Scheinwerfer-Lichtfleck direkt auf der getroffenen Objektoberfläche
     * (Boden, Wand, Glas, Decke).
     */
    private static void renderSpotlightSurfacePool(Matrix4fc pose, VertexConsumer buffer,
                                                   float cx, float cy, float cz,
                                                   float nx, float ny, float nz,
                                                   float t1x, float t1y, float t1z,
                                                   float t2x, float t2y, float t2z,
                                                   float radius,
                                                   float r, float g, float b, float maxAlpha) {
        if (maxAlpha <= 0.01F) return;
        int segments = 16;
        float rInner = 0.03F * radius;

        // Versatz um 0.015F entlang der Flächennormale, um Z-Fighting mit dem Block zu verhindern
        float ox = cx + nx * 0.015F;
        float oy = cy + ny * 0.015F;
        float oz = cz + nz * 0.015F;

        for (int i = 0; i < segments; i++) {
            double a0 = i * Math.PI * 2.0 / segments;
            double a1 = (i + 1) * Math.PI * 2.0 / segments;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);

            // Innenring nahe Zentrum
            float in0x = ox + (t1x * c0 + t2x * s0) * rInner;
            float in0y = oy + (t1y * c0 + t2y * s0) * rInner;
            float in0z = oz + (t1z * c0 + t2z * s0) * rInner;

            float in1x = ox + (t1x * c1 + t2x * s1) * rInner;
            float in1y = oy + (t1y * c1 + t2y * s1) * rInner;
            float in1z = oz + (t1z * c1 + t2z * s1) * rInner;

            // Außenrand
            float out0x = ox + (t1x * c0 + t2x * s0) * radius;
            float out0y = oy + (t1y * c0 + t2y * s0) * radius;
            float out0z = oz + (t1z * c0 + t2z * s0) * radius;

            float out1x = ox + (t1x * c1 + t2x * s1) * radius;
            float out1y = oy + (t1y * c1 + t2y * s1) * radius;
            float out1z = oz + (t1z * c1 + t2z * s1) * radius;

            // Quad von Innenring (hell) nach Außenrand (0.0 Alpha)
            quadBoth(pose, buffer,
                    in0x, in0y, in0z, maxAlpha,
                    in1x, in1y, in1z, maxAlpha,
                    out1x, out1y, out1z, 0.0F,
                    out0x, out0y, out0z, 0.0F,
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
                            float normX, float normY, float normZ,
                            float cos, float sin, float rBot,
                            float distFactor, boolean hasHit) {}

    private record DiscoFrame(float relBallX, float relBallY, float relBallZ,
                              float seconds, float weight,
                              BeamData[] beams) {}
}
