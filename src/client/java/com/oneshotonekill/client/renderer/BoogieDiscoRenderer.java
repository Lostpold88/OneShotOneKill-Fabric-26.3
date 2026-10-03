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
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

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
/** Äußere, weit schwenkende Hauptstrahlen (Boden und Wände). */
    private static final int OUTER_BEAMS = 8;
    /** Steile, gegenläufig rotierende Innenstrahlen. */
    private static final int INNER_BEAMS = 5;
    /** Nach oben gerichtete Strahlen für Decke, Wände und Himmel. */
    private static final int SKY_BEAMS = 4;
    private static final int BEAM_COUNT = OUTER_BEAMS + INNER_BEAMS + SKY_BEAMS;
    /** Beat-Frequenz (120 BPM): Strobe, Bodenringe und Starburst pulsieren im Takt. */
    private static final float BEAT_HZ = 2.0F;
    private static final int STARBURST_RAYS = 18;


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

    /**
     * Prüft, ob ein Block spiegelnde bzw. teilreflektierende Eigenschaften besitzt
     * (Glasblöcke, Glasscheiben, Eisarten und polierte Gesteine).
     */
    private static boolean isReflectiveBlock(BlockState state) {
        var block = state.getBlock();
        if (block instanceof HalfTransparentBlock || block instanceof IronBarsBlock) {
            return true;
        }
        String name = block.getDescriptionId();
        return name.contains("polished");
    }

    private static void onExtract(LevelExtractionContext context) {Minecraft client = Minecraft.getInstance();
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

            float baseRadius = (2.6F + (float) Math.sin(seconds * 3.14F) * 0.35F) * weight;
            float rotSpeed = seconds * 2.2F;

            BeamData[] beams = new BeamData[BEAM_COUNT];
            double nominalDepth = Math.max(1.5, rayStartY - py);

            for (int i = 0; i < BEAM_COUNT; i++) {
                int tier = i < OUTER_BEAMS ? 0 : i < OUTER_BEAMS + INNER_BEAMS ? 1 : 2;
                float angle;
                float sweepRadius;
                double dirY;
                double reach = 32.0;
                if (tier == 0) {
                    // Äußere Hauptstrahlen: weit schwenkend, atmende Länge
                    angle = rotSpeed + i * (float) (Math.PI * 2.0 / OUTER_BEAMS);
                    sweepRadius = baseRadius * (0.88F + 0.24F * (float) Math.sin(seconds * 4.0F + i * 1.2F));
                    dirY = -nominalDepth * (1.0 + 0.10 * Math.sin(seconds * 2.7 + i * 0.9));
                } else if (tier == 1) {
                    // Innere Strahlen: steiler, schneller und gegenläufig
                    int local = i - OUTER_BEAMS;
                    angle = -seconds * 3.6F + local * (float) (Math.PI * 2.0 / INNER_BEAMS) + 0.52F;
                    sweepRadius = baseRadius * (0.80F + 0.20F * (float) Math.sin(seconds * 6.0F + local * 1.7F));
                    dirY = -nominalDepth;
                } else {
                    // Himmelsstrahlen: wandern über Decke, Wände und in die Nacht
                    int local = i - OUTER_BEAMS - INNER_BEAMS;
                    angle = seconds * 1.4F + local * (float) (Math.PI * 2.0 / SKY_BEAMS) + 0.4F;
                    sweepRadius = baseRadius * (0.95F + 0.30F * (float) Math.sin(seconds * 3.1F + local * 1.3F));
                    dirY = nominalDepth * (0.55 + 0.25 * Math.sin(seconds * 2.5 + local * 1.6));
                    reach = 20.0;
                }
                float cos = (float) Math.cos(angle);
                float sin = (float) Math.sin(angle);

                // Schräg nach außen gerichteter Richtungsvektor
                double dirX = cos * sweepRadius;
                double dirZ = sin * sweepRadius;
                double dirLen = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
                if (dirLen < 0.001) dirLen = 1.0;
                Vec3 normDir = new Vec3(dirX / dirLen, dirY / dirLen, dirZ / dirLen);

                // Raycast, um Boden, Wände, Decken, Glas und alle Objekte zu treffen
                Vec3 rayEnd = rayStart.add(normDir.scale(reach));
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

                // Lambertsches Kosinusgesetz & Einfallswinkel
                double dot = normDir.x * normal.x + normDir.y * normal.y + normDir.z * normal.z;
                float cosTheta = Math.abs((float) dot);
                float lambert = Math.clamp(0.25F + 0.75F * cosTheta, 0.25F, 1.0F);

                // Teilreflexion an Glas, Eis und polierten Flächen (Specular Bounce)
                BounceData bounce = null;
                if (hasHit) {
                    BlockState hitState = client.level.getBlockState(hit.getBlockPos());
                    if (isReflectiveBlock(hitState)) {
                        Vec3 reflectDir = normDir.subtract(normal.scale(2.0 * dot)).normalize();

                        Vec3 bounceStart = hitPos.add(normal.scale(0.02));
                        Vec3 bounceEnd = bounceStart.add(reflectDir.scale(14.0));
                        BlockHitResult bounceHit = client.level.clip(new ClipContext(
                                bounceStart, bounceEnd,
                                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                                CollisionContext.empty()
                        ));

                        boolean bHasHit = bounceHit.getType() == HitResult.Type.BLOCK;
                        Vec3 bHitPos = bHasHit ? bounceHit.getLocation() : bounceEnd;
                        Vec3 bNormal = bHasHit ? bounceHit.getDirection().getUnitVec3() : reflectDir.scale(-1.0);
                        double bDist = bounceStart.distanceTo(bHitPos);

                        float bRTop = rBot * 0.45F;
                        float bRBot = (float) Math.clamp(bRTop + 0.15F * weight * (bDist / 5.0), 0.15F * weight, 0.85F * weight);
                        float fresnel = Math.clamp(0.30F + 0.40F * (1.0F - cosTheta), 0.25F, 0.75F);

                        bounce = new BounceData(
                                (float) (bounceStart.x - camPos.x),
                                (float) (bounceStart.y - camPos.y),
                                (float) (bounceStart.z - camPos.z),
                                (float) (bHitPos.x - camPos.x),
                                (float) (bHitPos.y - camPos.y),
                                (float) (bHitPos.z - camPos.z),
                                (float) bNormal.x,
                                (float) bNormal.y,
                                (float) bNormal.z,
                                (float) reflectDir.x,
                                (float) reflectDir.y,
                                (float) reflectDir.z,
                                bRTop, bRBot,
                                fresnel,
                                bHasHit
                        );
                    }
                }

                beams[i] = new BeamData(
                        (float) (hitPos.x - camPos.x),
                        (float) (hitPos.y - camPos.y),
                        (float) (hitPos.z - camPos.z),
                        (float) normal.x,
                        (float) normal.y,
                        (float) normal.z,
                        (float) normDir.x,
                        (float) normDir.y,
                        (float) normDir.z,
                        cos, sin, rBot, distFactor, lambert, hasHit, tier, bounce
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
        }}

    private static void onSubmit(LevelRenderContext context) {LevelRenderState levelState = context.levelState();
        List<DiscoFrame> frames = levelState.getData(FRAMES);
        if (frames == null || frames.isEmpty()) return;

        PoseStack poseStack = context.poseStack();

        for (DiscoFrame frame : frames) {
            float scale = frame.weight;
            if (scale <= 0.001F) continue;
            float beat = beatPulse(frame.seconds);

            // 1. Authentisches 3D-Item-Modell der Discokugel (ModItems.BOOGIE_DISCO_BALL)
            // Starr direkt über dem Kopf zentriert, rotiert um die eigene Mittelachse und "atmet" im Beat
            if (!DISCO_BALL_RENDER_STATE.isEmpty()) {
                poseStack.pushPose();
                poseStack.translate(frame.relBallX, frame.relBallY, frame.relBallZ);
                poseStack.rotate(Axis.YP.rotation(frame.seconds * 2.2F));
                float ballScale = 1.65F * scale * (1.0F + 0.07F * beat);
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

            // 2. Leuchtaura, Starburst, volumetrische Scheinwerfer-Lichtstrahlen & Boden-Lichtkreise
            // debugQuads schreibt nicht in den Depth-Buffer und nutzt den regulären Translucent-Pass,
            // sodass Glas, Wasser und transluzente Blöcke auch aus der First-Person-Sicht dahinter sichtbar bleiben.
            context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.debugQuads(), (pose, buffer) -> {
                Matrix4fc m = pose.pose();
                float relX = frame.relBallX;
                float relY = frame.relBallY;
                float relZ = frame.relBallZ;

                renderBallHalo(m, buffer, relX, relY, relZ, frame.seconds, scale, beat);
                renderStarburst(m, buffer, relX, relY, relZ, frame.seconds, scale, beat);
                renderSpotlightBeams(m, buffer, relX, relY, relZ, frame.beams, scale, frame.seconds);
            });
        }}

    private static void renderSpotlightBeams(Matrix4fc pose, VertexConsumer buffer,
                                             float bx, float by, float bz,
                                             BeamData[] beams,
                                             float scale, float seconds) {
        float topY = by - 0.12F * scale;
        float beat = beatPulse(seconds);
        float phase = beatPhase(seconds);
        int beatIndex = (int) (seconds * BEAT_HZ);

        for (int i = 0; i < beams.length; i++) {
            BeamData beam = beams[i];
            float[] col = beamColor(i, seconds);
            float r = col[0], g = col[1], b = col[2];
            // Heißer, fast weißer Kern in der Strahlfarbe
            float cr = r + (1.0F - r) * 0.65F;
            float cg = g + (1.0F - g) * 0.65F;
            float cb = b + (1.0F - b) * 0.65F;

            // Strobe: jeder Strahl flackert leicht, jeder zweite Strahl blitzt auf dem Beat auf
            float flicker = 0.80F + 0.20F * (float) Math.sin(seconds * 9.0F + i * 2.1F);
            boolean accent = ((i + beatIndex) & 1) == 0;
            float intensity = flicker * (accent ? 1.0F + 0.7F * beat : 0.85F);
            if (beam.tier() == 2) intensity *= 0.8F;
            float k = intensity * scale;

            // Zielpunkt auf dem tatsächlichen Block
            float groundX = beam.relX();
            float groundY = beam.relY();
            float groundZ = beam.relZ();

            // Richtungsvektor vom Kugelursprung zum getroffenen Punkt
            float dx = groundX - bx;
            float dy = groundY - topY;
            float dz = groundZ - bz;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 0.01F) continue;
            float dirX = dx / len, dirY = dy / len, dirZ = dz / len;

            // Orthonormale Basis senkrecht zum Strahl (oben an der Kugel)
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
            Axes axes = new Axes(ux, uz, vx, vy, vz);

            // Exakte elliptische Halbachsen auf der getroffenen Fläche (Kegelschnitt)
            EllipticalBasis basis = computeEllipticalBasis(
                    dirX, dirY, dirZ,
                    beam.normX(), beam.normY(), beam.normZ(),
                    beam.rBot(), beam.hasHit()
            );

            // Lambert-Kosinusgesetz + Entfernung; freie Strahlen laufen in der Ferne aus
            float distFactor = beam.distFactor();
            float lambert = beam.lambert();
            float shade = distFactor * (0.4F + 0.6F * lambert) * (beam.hasHit() ? 1.0F : 0.15F);

            float mainBot = 0.07F * k * shade;

            // 1. Drei Schichten: weite Glow-Hülle, farbiger Hauptkegel, weißglühender Kern
            renderBeamShell(pose, buffer, bx, topY, bz, axes, 0.11F * scale,
                    groundX, groundY, groundZ, basis, 1.60F, 10,
                    Math.min(0.07F * k, 0.20F), 0.025F * k * shade, r, g, b);
            renderBeamShell(pose, buffer, bx, topY, bz, axes, 0.05F * scale,
                    groundX, groundY, groundZ, basis, 1.0F, 12,
                    Math.min(0.16F * k, 0.45F), mainBot, r, g, b);
            renderBeamShell(pose, buffer, bx, topY, bz, axes, 0.022F * scale,
                    groundX, groundY, groundZ, basis, 0.30F, 6,
                    Math.min(0.36F * k, 0.60F), 0.12F * k * shade, cr, cg, cb);

            // 2. Lichtfleck, Beat-Ringe und tanzende Spiegelpunkte auf der getroffenen Fläche
            if (beam.hasHit()) {
                float spotAlpha = 0.30F * k * distFactor * lambert;
                renderSpotlightSurfacePool(pose, buffer,
                        groundX, groundY, groundZ,
                        beam.normX(), beam.normY(), beam.normZ(),
                        basis,
                        1.35F,
                        r, g, b, spotAlpha);

                if (beam.tier() != 2) {
                    // Druckwelle: pro Beat läuft ein leuchtender Ring vom Lichtfleck nach außen
                    renderSurfaceRing(pose, buffer,
                            groundX, groundY, groundZ,
                            beam.normX(), beam.normY(), beam.normZ(),
                            basis, 0.25F + 1.35F * phase, 0.16F,
                            cr, cg, cb, 0.34F * k * (1.0F - phase) * distFactor * lambert);
                }

                if (beam.tier() == 0) {
                    // Spiegelpunkte der Discokugel: kleine helle Flecken kreisen um den Hauptfleck
                    for (int d = 0; d < 2; d++) {
                        float a = seconds * (1.6F + d * 0.7F) + i * 1.3F + d * 2.09F;
                        float dist = (0.55F + 0.35F * (float) Math.sin(seconds * 3.0F + d + i)) * 1.1F;
                        float ca = (float) Math.cos(a) * dist;
                        float sa = (float) Math.sin(a) * dist;
                        float[] dot = beamColor(i + d + 2, seconds);
                        renderSpotlightSurfacePool(pose, buffer,
                                groundX + basis.minorX() * ca + basis.majorX() * sa,
                                groundY + basis.minorY() * ca + basis.majorY() * sa,
                                groundZ + basis.minorZ() * ca + basis.majorZ() * sa,
                                beam.normX(), beam.normY(), beam.normZ(),
                                basis, 0.16F,
                                dot[0] + (1.0F - dot[0]) * 0.5F,
                                dot[1] + (1.0F - dot[1]) * 0.5F,
                                dot[2] + (1.0F - dot[2]) * 0.5F,
                                Math.min(0.55F * k * distFactor * lambert, 0.85F));
                    }
                }
            }

            // 3. Sekundärstrahl (Bounce) an Glas, Eis oder polierten Flächen
            BounceData bounce = beam.bounce();
            if (bounce != null) {
                float bStartX = bounce.startX();
                float bStartY = bounce.startY();
                float bStartZ = bounce.startZ();

                float bHitX = bounce.hitX();
                float bHitY = bounce.hitY();
                float bHitZ = bounce.hitZ();

                float bDirX = bounce.dirX();
                float bDirY = bounce.dirY();
                float bDirZ = bounce.dirZ();

                float bux = -bDirZ;
                float buz = bDirX;
                float buLen = (float) Math.sqrt(bux * bux + buz * buz);
                if (buLen < 0.001F) {
                    bux = 1.0F;
                    buz = 0.0F;
                } else {
                    bux /= buLen;
                    buz /= buLen;
                }
                float bvx = bDirY * buz;
                float bvy = bDirZ * bux - bDirX * buz;
                float bvz = -bDirY * bux;

                EllipticalBasis bounceBasis = computeEllipticalBasis(
                        bDirX, bDirY, bDirZ,
                        bounce.normX(), bounce.normY(), bounce.normZ(),
                        bounce.rBot(), bounce.hasHit()
                );

                float bRTop = bounce.rTop();
                float bAlphaTop = mainBot * bounce.alphaFactor() * 0.90F;
                float bAlphaBot = bAlphaTop * 0.35F;

                for (int seg = 0; seg < 8; seg++) {
                    double a0 = seg * Math.PI * 2.0 / 8;
                    double a1 = (seg + 1) * Math.PI * 2.0 / 8;
                    float ca0 = (float) Math.cos(a0), sa0 = (float) Math.sin(a0);
                    float ca1 = (float) Math.cos(a1), sa1 = (float) Math.sin(a1);

                    // Oben am Reflexionsursprung
                    float bt0x = bStartX + (bux * ca0 + bvx * sa0) * bRTop;
                    float bt0y = bStartY + bvy * sa0 * bRTop;
                    float bt0z = bStartZ + (buz * ca0 + bvz * sa0) * bRTop;

                    float bt1x = bStartX + (bux * ca1 + bvx * sa1) * bRTop;
                    float bt1y = bStartY + bvy * sa1 * bRTop;
                    float bt1z = bStartZ + (buz * ca1 + bvz * sa1) * bRTop;

                    // Unten am zweiten Auftreffpunkt
                    float bb0x = bHitX + bounceBasis.minorX() * ca0 + bounceBasis.majorX() * sa0;
                    float bb0y = bHitY + bounceBasis.minorY() * ca0 + bounceBasis.majorY() * sa0;
                    float bb0z = bHitZ + bounceBasis.minorZ() * ca0 + bounceBasis.majorZ() * sa0;

                    float bb1x = bHitX + bounceBasis.minorX() * ca1 + bounceBasis.majorX() * sa1;
                    float bb1y = bHitY + bounceBasis.minorY() * ca1 + bounceBasis.majorY() * sa1;
                    float bb1z = bHitZ + bounceBasis.minorZ() * ca1 + bounceBasis.majorZ() * sa1;

                    quadBoth(pose, buffer,
                            bt0x, bt0y, bt0z, bAlphaTop,
                            bt1x, bt1y, bt1z, bAlphaTop,
                            bb1x, bb1y, bb1z, bAlphaBot,
                            bb0x, bb0y, bb0z, bAlphaBot,
                            r, g, b);
                }

                if (bounce.hasHit()) {
                    renderSpotlightSurfacePool(pose, buffer,
                            bHitX, bHitY, bHitZ,
                            bounce.normX(), bounce.normY(), bounce.normZ(),
                            bounceBasis,
                            1.20F,
                            r, g, b, 0.20F * k * bounce.alphaFactor());
                }
            }
        }
    }
/** Position im aktuellen Beat, 0..1. */
    private static float beatPhase(float seconds) {
        return (seconds * BEAT_HZ) % 1.0F;
    }

    /** 1.0 genau auf dem Beat, fällt danach schnell ab. */
    private static float beatPulse(float seconds) {
        return (float) Math.exp(-beatPhase(seconds) * 4.5F);
    }

    /**
     * Neon-Farbe für Strahl {@code index}: Die Palette wandert im Takt durch alle Strahlen
     * und blendet weich zwischen zwei Nachbarfarben über (Farb-Chase).
     */
    private static float[] beamColor(int index, float seconds) {
        float shift = seconds * 1.5F;
        int step = (int) shift;
        float frac = shift - step;
        float t = frac * frac * (3.0F - 2.0F * frac);
        float[] from = DISCO_COLORS[(index + step) % DISCO_COLORS.length];
        float[] to = DISCO_COLORS[(index + step + 1) % DISCO_COLORS.length];
        return new float[]{
                from[0] + (to[0] - from[0]) * t,
                from[1] + (to[1] - from[1]) * t,
                from[2] + (to[2] - from[2]) * t
        };
    }
/**
     * Ein Kegelstumpf-Mantel von der Kugel (runder Hals) bis zur elliptischen Basis auf der getroffenen Fläche.
     * Mehrere Mäntel mit unterschiedlichem Radius und Alpha ergeben den volumetrischen Glow-Effekt.
     */
    private static void renderBeamShell(Matrix4fc pose, VertexConsumer buffer,
                                        float bx, float by, float bz, Axes ax, float rTop,
                                        float gx, float gy, float gz, EllipticalBasis basis, float baseScale,
                                        int segments, float alphaTop, float alphaBot,
                                        float r, float g, float b) {
        for (int seg = 0; seg < segments; seg++) {
            double a0 = seg * Math.PI * 2.0 / segments;
            double a1 = (seg + 1) * Math.PI * 2.0 / segments;
            float ca0 = (float) Math.cos(a0), sa0 = (float) Math.sin(a0);
            float ca1 = (float) Math.cos(a1), sa1 = (float) Math.sin(a1);

            quadBoth(pose, buffer,
                    bx + (ax.ux() * ca0 + ax.vx() * sa0) * rTop, by + ax.vy() * sa0 * rTop, bz + (ax.uz() * ca0 + ax.vz() * sa0) * rTop, alphaTop,
                    bx + (ax.ux() * ca1 + ax.vx() * sa1) * rTop, by + ax.vy() * sa1 * rTop, bz + (ax.uz() * ca1 + ax.vz() * sa1) * rTop, alphaTop,
                    gx + (basis.minorX() * ca1 + basis.majorX() * sa1) * baseScale, gy + (basis.minorY() * ca1 + basis.majorY() * sa1) * baseScale, gz + (basis.minorZ() * ca1 + basis.majorZ() * sa1) * baseScale, alphaBot,
                    gx + (basis.minorX() * ca0 + basis.majorX() * sa0) * baseScale, gy + (basis.minorY() * ca0 + basis.majorY() * sa0) * baseScale, gz + (basis.minorZ() * ca0 + basis.majorZ() * sa0) * baseScale, alphaBot,
                    r, g, b);
        }
    }

    /**
     * Leuchtender Ring auf der getroffenen Fläche (Beat-Druckwelle): Alpha ist auf der Ringmitte am höchsten
     * und läuft nach innen und außen weich aus.
     */
    private static void renderSurfaceRing(Matrix4fc pose, VertexConsumer buffer,
                                          float cx, float cy, float cz,
                                          float nx, float ny, float nz,
                                          EllipticalBasis basis, float radius, float halfWidth,
                                          float r, float g, float b, float maxAlpha) {
        if (maxAlpha <= 0.01F) return;
        int segments = 20;
        float ox = cx + nx * 0.02F;
        float oy = cy + ny * 0.02F;
        float oz = cz + nz * 0.02F;
        float inner = radius * (1.0F - halfWidth);
        float outer = radius * (1.0F + halfWidth);

        for (int i = 0; i < segments; i++) {
            double a0 = i * Math.PI * 2.0 / segments;
            double a1 = (i + 1) * Math.PI * 2.0 / segments;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);

            float e0x = basis.minorX() * c0 + basis.majorX() * s0;
            float e0y = basis.minorY() * c0 + basis.majorY() * s0;
            float e0z = basis.minorZ() * c0 + basis.majorZ() * s0;
            float e1x = basis.minorX() * c1 + basis.majorX() * s1;
            float e1y = basis.minorY() * c1 + basis.majorY() * s1;
            float e1z = basis.minorZ() * c1 + basis.majorZ() * s1;

            quadBoth(pose, buffer,
                    ox + e0x * inner, oy + e0y * inner, oz + e0z * inner, 0.0F,
                    ox + e1x * inner, oy + e1y * inner, oz + e1z * inner, 0.0F,
                    ox + e1x * radius, oy + e1y * radius, oz + e1z * radius, maxAlpha,
                    ox + e0x * radius, oy + e0y * radius, oz + e0z * radius, maxAlpha,
                    r, g, b);
            quadBoth(pose, buffer,
                    ox + e0x * radius, oy + e0y * radius, oz + e0z * radius, maxAlpha,
                    ox + e1x * radius, oy + e1y * radius, oz + e1z * radius, maxAlpha,
                    ox + e1x * outer, oy + e1y * outer, oz + e1z * outer, 0.0F,
                    ox + e0x * outer, oy + e0y * outer, oz + e0z * outer, 0.0F,
                    r, g, b);
        }
    }

    /** Durchscheinende Kugelschale mit gleichmäßigem Alpha. */
    private static void renderGlowSphere(Matrix4fc pose, VertexConsumer buffer,
                                         float cx, float cy, float cz, float radius,
                                         int slices, int stacks,
                                         float r, float g, float b, float alpha) {
        for (int j = 0; j < stacks; j++) {
            double p0 = Math.PI * j / stacks;
            double p1 = Math.PI * (j + 1) / stacks;
            float y0 = (float) Math.cos(p0) * radius, y1 = (float) Math.cos(p1) * radius;
            float rr0 = (float) Math.sin(p0) * radius, rr1 = (float) Math.sin(p1) * radius;
            for (int i = 0; i < slices; i++) {
                double t0 = i * Math.PI * 2.0 / slices;
                double t1 = (i + 1) * Math.PI * 2.0 / slices;
                float c0 = (float) Math.cos(t0), s0 = (float) Math.sin(t0);
                float c1 = (float) Math.cos(t1), s1 = (float) Math.sin(t1);
                quadBoth(pose, buffer,
                        cx + rr0 * c0, cy + y0, cz + rr0 * s0, alpha,
                        cx + rr0 * c1, cy + y0, cz + rr0 * s1, alpha,
                        cx + rr1 * c1, cy + y1, cz + rr1 * s1, alpha,
                        cx + rr1 * c0, cy + y1, cz + rr1 * s0, alpha,
                        r, g, b);
            }
        }
    }

    /** Pulsierende, farbwechselnde Leuchtaura um die Discokugel. */
    private static void renderBallHalo(Matrix4fc pose, VertexConsumer buffer,
                                       float cx, float cy, float cz,
                                       float seconds, float scale, float beat) {
        float[] inner = beamColor(0, seconds);
        float[] outer = beamColor(4, seconds);
        renderGlowSphere(pose, buffer, cx, cy, cz,
                0.50F * scale * (1.0F + 0.10F * beat), 12, 6,
                inner[0] + (1.0F - inner[0]) * 0.4F, inner[1] + (1.0F - inner[1]) * 0.4F, inner[2] + (1.0F - inner[2]) * 0.4F,
                0.10F * scale * (0.8F + 0.4F * beat));
        renderGlowSphere(pose, buffer, cx, cy, cz,
                0.95F * scale * (1.0F + 0.20F * beat), 12, 6,
                outer[0], outer[1], outer[2],
                0.035F * scale * (0.8F + 0.6F * beat));
    }

    /**
     * Glitzernder Starburst: Dünne Lichtnadeln strahlen in alle Richtungen von der Kugel ab,
     * rotieren langsam, funkeln in ihrer Länge und schießen auf dem Beat hinaus.
     */
    private static void renderStarburst(Matrix4fc pose, VertexConsumer buffer,
                                        float cx, float cy, float cz,
                                        float seconds, float scale, float beat) {
        for (int i = 0; i < STARBURST_RAYS; i++) {
            // Fibonacci-Kugel verteilt die Nadeln gleichmäßig, die Drehung um Y lässt sie kreisen
            float y = 1.0F - 2.0F * (i + 0.5F) / STARBURST_RAYS;
            float rad = (float) Math.sqrt(Math.max(0.0F, 1.0F - y * y));
            float phi = i * 2.39996F + seconds * 1.2F;
            float dx = rad * (float) Math.cos(phi);
            float dz = rad * (float) Math.sin(phi);

            // Nadeln nach unten bleiben kurz, damit sie den Tänzer unter der Kugel nicht überstrahlen
            float downward = y < -0.2F ? 0.40F : 1.0F;
            float length = scale * downward * (0.70F + 0.30F * (float) Math.sin(seconds * 7.0F + i * 1.9F)) * (1.0F + 0.40F * beat);
            float startDist = 0.30F * scale;
            float width = 0.04F * scale;

            // Zwei gekreuzte Flächen, damit die Nadel aus jedem Blickwinkel sichtbar ist
            float p1x = -dz, p1y = 0.0F, p1z = dx;
            float pLen = (float) Math.sqrt(p1x * p1x + p1z * p1z);
            if (pLen < 0.001F) {
                p1x = 1.0F;
                p1z = 0.0F;
            } else {
                p1x /= pLen;
                p1z /= pLen;
            }
            float p2x = y * p1z - dz * p1y;
            float p2y = dz * p1x - dx * p1z;
            float p2z = dx * p1y - y * p1x;

            float sx = cx + dx * startDist, sy = cy + y * startDist, sz = cz + dz * startDist;
            float tx = cx + dx * (startDist + length), ty = cy + y * (startDist + length), tz = cz + dz * (startDist + length);

            float[] col = beamColor(i + 3, seconds);
            float wr = col[0] + (1.0F - col[0]) * 0.5F;
            float wg = col[1] + (1.0F - col[1]) * 0.5F;
            float wb = col[2] + (1.0F - col[2]) * 0.5F;
            float alpha = Math.min(0.35F * scale * (0.7F + 0.5F * beat), 0.55F);

            quadBoth(pose, buffer,
                    sx - p1x * width, sy - p1y * width, sz - p1z * width, alpha,
                    sx + p1x * width, sy + p1y * width, sz + p1z * width, alpha,
                    tx, ty, tz, 0.0F,
                    tx, ty, tz, 0.0F,
                    wr, wg, wb);
            quadBoth(pose, buffer,
                    sx - p2x * width, sy - p2y * width, sz - p2z * width, alpha,
                    sx + p2x * width, sy + p2y * width, sz + p2z * width, alpha,
                    tx, ty, tz, 0.0F,
                    tx, ty, tz, 0.0F,
                    wr, wg, wb);
        }
    }

    private record Axes(float ux, float uz, float vx, float vy, float vz) {}





    /**
     * Berechnet die physikalisch exakte elliptische Halbachsenbasis (Minor und Major)
     * eines schräg auf eine Oberfläche treffenden Kegelstrahls (Kegelschnitt).
     */
    private static EllipticalBasis computeEllipticalBasis(
            float dirX, float dirY, float dirZ,
            float nx, float ny, float nz,
            float radius, boolean hasHit) {
        if (!hasHit) {
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
            return new EllipticalBasis(
                    ux * radius, 0.0F, uz * radius,
                    vx * radius, vy * radius, vz * radius
            );
        }

        // Kreuzprodukt d x n = Minor-Achse quer zur Einstrahlrichtung
        float cx = dirY * nz - dirZ * ny;
        float cy = dirZ * nx - dirX * nz;
        float cz = dirX * ny - dirY * nx;
        float cLen = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);

        float tMinorX, tMinorY, tMinorZ;
        if (cLen < 0.001F) {
            if (Math.abs(ny) < 0.9F) {
                float hLen = (float) Math.sqrt(nz * nz + nx * nx);
                tMinorX = nz / hLen;
                tMinorY = 0.0F;
                tMinorZ = -nx / hLen;
            } else {
                tMinorX = 1.0F;
                tMinorY = 0.0F;
                tMinorZ = 0.0F;
            }
        } else {
            tMinorX = cx / cLen;
            tMinorY = cy / cLen;
            tMinorZ = cz / cLen;
        }

        // Major-Achse = n x tMinor (liegt in der Oberfläche genau in Einstrahlrichtung)
        float tMajorX = ny * tMinorZ - nz * tMinorY;
        float tMajorY = nz * tMinorX - nx * tMinorZ;
        float tMajorZ = nx * tMinorY - ny * tMinorX;

        // Kosinus des Einfallswinkels zur Flächennormale
        float cosTheta = Math.abs(dirX * nx + dirY * ny + dirZ * nz);
        float clampedCos = Math.clamp(cosTheta, 0.20F, 1.0F);

        // Elliptische Halbachsen: Minor bleibt unverändert radius, Major wird um 1 / cos(theta) gestreckt (max 5x)
        float rMajor = radius / clampedCos;

        return new EllipticalBasis(
                tMinorX * radius, tMinorY * radius, tMinorZ * radius,
                tMajorX * rMajor, tMajorY * rMajor, tMajorZ * rMajor
        );
    }

    /**
     * Runder bis elliptischer, weich auslaufender Scheinwerfer-Lichtfleck direkt auf der getroffenen Objektoberfläche.
     */
    private static void renderSpotlightSurfacePool(Matrix4fc pose, VertexConsumer buffer,
                                                   float cx, float cy, float cz,
                                                   float nx, float ny, float nz,
                                                   EllipticalBasis basis,
                                                   float radiusMultiplier,
                                                   float r, float g, float b, float maxAlpha) {
        if (maxAlpha <= 0.01F) return;
        int segments = 16;

        // Versatz um 0.015F entlang der Flächennormale, um Z-Fighting mit dem Block zu verhindern
        float ox = cx + nx * 0.015F;
        float oy = cy + ny * 0.015F;
        float oz = cz + nz * 0.015F;

        float uX = basis.minorX() * radiusMultiplier;
        float uY = basis.minorY() * radiusMultiplier;
        float uZ = basis.minorZ() * radiusMultiplier;

        float vX = basis.majorX() * radiusMultiplier;
        float vY = basis.majorY() * radiusMultiplier;
        float vZ = basis.majorZ() * radiusMultiplier;

        for (int i = 0; i < segments; i++) {
            double a0 = i * Math.PI * 2.0 / segments;
            double a1 = (i + 1) * Math.PI * 2.0 / segments;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);

            // Innenring nahe Zentrum (3% Ausdehnung)
            float in0x = ox + (uX * c0 + vX * s0) * 0.03F;
            float in0y = oy + (uY * c0 + vY * s0) * 0.03F;
            float in0z = oz + (uZ * c0 + vZ * s0) * 0.03F;

            float in1x = ox + (uX * c1 + vX * s1) * 0.03F;
            float in1y = oy + (uY * c1 + vY * s1) * 0.03F;
            float in1z = oz + (uZ * c1 + vZ * s1) * 0.03F;

            // Außenrand der Ellipse
            float out0x = ox + (uX * c0 + vX * s0);
            float out0y = oy + (uY * c0 + vY * s0);
            float out0z = oz + (uZ * c0 + vZ * s0);

            float out1x = ox + (uX * c1 + vX * s1);
            float out1y = oy + (uY * c1 + vY * s1);
            float out1z = oz + (uZ * c1 + vZ * s1);

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
        // Alle Koordinaten sind kamerarelativ: Geometrie nahe der Kamera blendet aus,
        // damit Strahlen weder den Bildschirm überdecken noch den Tänzer verdecken.
        float dist = (float) Math.sqrt(x * x + y * y + z * z);
        float near = Math.clamp((dist - 0.8F) / 2.7F, 0.0F, 1.0F);
        buffer.addVertex(pose, x, y, z).setColor(r, g, b, a * near * near * (3.0F - 2.0F * near));
    }

    private record EllipticalBasis(
            float minorX, float minorY, float minorZ,
            float majorX, float majorY, float majorZ
    ) {}

    private record BounceData(
            float startX, float startY, float startZ,
            float hitX, float hitY, float hitZ,
            float normX, float normY, float normZ,
            float dirX, float dirY, float dirZ,
            float rTop, float rBot,
            float alphaFactor,
            boolean hasHit
    ) {}

    private record BeamData(
            float relX, float relY, float relZ,
            float normX, float normY, float normZ,
            float dirX, float dirY, float dirZ,
            float cos, float sin, float rBot,
            float distFactor, float lambert,
            boolean hasHit,
            int tier,
            @Nullable BounceData bounce
    ) {}

    private record DiscoFrame(float relBallX, float relBallY, float relBallZ,
                              float seconds, float weight,
                              BeamData[] beams) {}
}
