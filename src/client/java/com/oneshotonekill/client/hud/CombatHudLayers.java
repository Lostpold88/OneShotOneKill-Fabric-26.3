package com.oneshotonekill.client.hud;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.oneshotonekill.client.config.MinimapConfig;
import com.oneshotonekill.client.screen.OsokWidgets;
import com.oneshotonekill.item.runtime.MinigunRuntime;
import com.oneshotonekill.network.OsokPayloads.DeployableMarkersPayload;
import com.oneshotonekill.registry.ModItems;
import com.oneshotonekill.shared.Hologram;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.BlockModelResolver;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.util.*;

import static com.oneshotonekill.client.state.ClientStates.*;
import static com.oneshotonekill.item.types.WeaponItems.MinigunItem;

/**
 * HUD-Layer Gruppe.
 */
@SuppressWarnings({"NullableProblems", "SameParameterValue", "resource", "unused"})
public final class CombatHudLayers {
    private CombatHudLayers() {
    }

    // =========================================================================
    // MinigunHudLayer.java
    // =========================================================================

    /**
     * Visier der Minigun: Apex-Gatling Visier mit 6 rotierenden Läufen,
     * zirkulärem 270°-Restzeitbogen, 10-Stufen Diamant-Hit-Tally, kinetischen Hitmarkern
     * und Doppel-Stoßring Kill-Banner.
     */
    public static final class MinigunHudLayer implements HudElement {
        private static final int BARRELS = 6;
        private static final float TWO_PI = (float) (Math.PI * 2.0);

        private static int accentColor(LocalPlayer player, boolean firing, boolean expiring, int remainingUseTicks, float spin) {
            if (expiring) {
                return OsokWidgets.COLOR_CRIMSON;
            }
            boolean critical = remainingUseTicks > 0 && remainingUseTicks <= MinigunRuntime.USE_DURATION_TICKS / 4;
            if (critical) {
                float pulse = 0.5f + 0.5f * Mth.sin(player.tickCount * 0.45f);
                return lerpColor(OsokWidgets.COLOR_GOLD, OsokWidgets.COLOR_CRIMSON, pulse);
            }
            if (firing) {
                return lerpColor(OsokWidgets.COLOR_CYAN, OsokWidgets.COLOR_GOLD, spin);
            }
            return OsokWidgets.COLOR_CYAN;
        }

        /**
         * 6 rotierende Läufe mit Orbital-Punkten und dynamischem Motion-Blur Schweif
         */
        private static void drawApexBarrelCluster(GuiGraphicsExtractor graphics, int centerX, int centerY,
                                                  float spinPhase, float spin, int accent) {
            float radius = 10.0f + spin * 3.5f;
            int barrelAlpha = (int) ((0.45f + spin * 0.55f) * 255);
            int barrelColor = withAlpha(accent, barrelAlpha);

            for (int i = 0; i < BARRELS; i++) {
                float angle = spinPhase + (i * TWO_PI / BARRELS);
                int bx = Math.round(centerX + Mth.cos(angle) * radius);
                int by = Math.round(centerY + Mth.sin(angle) * radius);

                // Lauf-Bohrung
                graphics.fill(bx - 1, by - 1, bx + 2, by + 2, barrelColor);
                graphics.fill(bx, by, bx + 1, by + 1, 0xFFFFFFFF);

                // Motion-Blur Schweif bei hoher Drehzahl
                if (spin > 0.35f) {
                    float trailAngle = angle - (0.28f * spin);
                    int tx = Math.round(centerX + Mth.cos(trailAngle) * radius);
                    int ty = Math.round(centerY + Mth.sin(trailAngle) * radius);
                    graphics.fill(tx, ty, tx + 1, ty + 1, withAlpha(accent, (int) (barrelAlpha * 0.45f)));
                }
            }

            // Zentraler Visierpunkt
            graphics.fill(centerX - 1, centerY - 1, centerX + 2, centerY + 2, withAlpha(accent, 180));
            graphics.fill(centerX, centerY, centerX + 1, centerY + 1, 0xFFFFFFFF);
        }

        /**
         * Taktische Zielklammern mit Streuwinkel-Expansion
         */
        private static void drawTargetBrackets(GuiGraphicsExtractor graphics, int centerX, int centerY,
                                               int accent, float spin, boolean firing) {
            int dist = Math.round(20.0f + (firing ? 14.0f * (1.0f - spin * 0.5f) : 6.0f));
            int arm = 5;
            int bracketColor = withAlpha(accent, 220);

            // 4 Ecken
            graphics.horizontalLine(centerX - dist - arm, centerX - dist, centerY - dist, bracketColor);
            graphics.verticalLine(centerX - dist, centerY - dist, centerY - dist + arm, bracketColor);

            graphics.horizontalLine(centerX + dist, centerX + dist + arm, centerY - dist, bracketColor);
            graphics.verticalLine(centerX + dist, centerY - dist, centerY - dist + arm, bracketColor);

            graphics.horizontalLine(centerX - dist - arm, centerX - dist, centerY + dist, bracketColor);
            graphics.verticalLine(centerX - dist, centerY + dist - arm, centerY + dist, bracketColor);

            graphics.horizontalLine(centerX + dist, centerX + dist + arm, centerY + dist, bracketColor);
            graphics.verticalLine(centerX + dist, centerY + dist - arm, centerY + dist, bracketColor);
        }

        /**
         * Zirkulärer 270°-Restzeit-Bogen um das Visier
         */
        private static void drawCircularTimeGauge(GuiGraphicsExtractor graphics, int centerX, int centerY,
                                                  int remainingTicks, int maxTicks, int accent) {
            if (remainingTicks <= 0) return;

            float pct = Mth.clamp((float) remainingTicks / maxTicks, 0.0f, 1.0f);
            int segments = 24;
            int activeSegments = Math.round(segments * pct);
            float gaugeRadius = 30.0f;

            // Bogen von 135° bis 405° (270° Sweep)
            float startRad = (float) (Math.PI * 0.75);
            float sweepRad = (float) (Math.PI * 1.5);

            for (int s = 0; s < segments; s++) {
                float angle = startRad + (s / (float) segments) * sweepRad;
                int px = Math.round(centerX + Mth.cos(angle) * gaugeRadius);
                int py = Math.round(centerY + Mth.sin(angle) * gaugeRadius);

                if (s < activeSegments) {
                    graphics.fill(px, py, px + 2, py + 2, accent);
                } else {
                    graphics.fill(px, py, px + 2, py + 2, 0x3300F0FF);
                }
            }
        }

        /**
         * 10-Stufen Diamant-Hit-Tally mit FATAL HIT Alarm
         */
        private static void drawHitTally(GuiGraphicsExtractor graphics, Font font, int centerX, int centerY, int consecutiveHits) {
            if (consecutiveHits <= 0) return;

            int maxHits = 10;
            int capped = Math.min(consecutiveHits, maxHits);
            int startX = centerX - (maxHits * 6) / 2;
            int y = centerY + 36;

            for (int i = 0; i < maxHits; i++) {
                int hx = startX + i * 6;
                if (i < capped) {
                    int hitCol = i == 9 ? OsokWidgets.COLOR_CRIMSON : (i >= 6 ? OsokWidgets.COLOR_GOLD : OsokWidgets.COLOR_CYAN);
                    // Diamantform (3x3)
                    graphics.fill(hx + 1, y, hx + 2, y + 3, hitCol);
                    graphics.fill(hx, y + 1, hx + 3, y + 2, hitCol);
                } else {
                    graphics.fill(hx + 1, y + 1, hx + 2, y + 2, 0x4400F0FF);
                }
            }

            if (consecutiveHits >= 9) {
                graphics.centeredText(font, Component.translatable("hud.oneshotonekill.minigun.fatal_ready").getString(), centerX, y + 7, OsokWidgets.COLOR_CRIMSON);
            }
        }

        /**
         * Präzise Cyber-Telemetrie unter dem Visier
         */
        private static void drawWeaponTelemetry(GuiGraphicsExtractor graphics, Font font, int centerX, int centerY,
                                                boolean warmingUp, boolean firing, boolean expiring, int remainingTicks, float spin, int accent) {
            int y = centerY + 50;

            if (expiring) {
                graphics.centeredText(font, Component.translatable("hud.oneshotonekill.minigun.depleted").getString(), centerX, y, OsokWidgets.COLOR_CRIMSON);
                return;
            }

            int rpm = Math.round(spin * 6000.0f);
            String rpmText = String.format("RPM: %d", rpm);
            graphics.centeredText(font, rpmText, centerX, y, accent);

            if (remainingTicks > 0) {
                float sec = remainingTicks / 20.0f;
                String timeText = String.format("RESTZEIT: %.1fs", sec);
                graphics.centeredText(font, timeText, centerX, y + 9, OsokWidgets.COLOR_TEXT_MUTED);
            }
        }

        /**
         * Kinetische Cyber-Hitmarker
         */
        private static void drawHitConfirmation(GuiGraphicsExtractor graphics, int centerX, int centerY, int effectTicks) {
            if (effectTicks <= 0) return;

            int alpha = Math.min(255, effectTicks * 45);
            int color = withAlpha(OsokWidgets.COLOR_CYAN, alpha);
            int dist = 6 + effectTicks;

            graphics.horizontalLine(centerX - dist - 4, centerX - dist, centerY - dist, color);
            graphics.verticalLine(centerX - dist, centerY - dist, centerY - dist + 4, color);

            graphics.horizontalLine(centerX + dist, centerX + dist + 4, centerY - dist, color);
            graphics.verticalLine(centerX + dist, centerY - dist, centerY - dist + 4, color);

            graphics.horizontalLine(centerX - dist - 4, centerX - dist, centerY + dist, color);
            graphics.verticalLine(centerX - dist, centerY + dist - 4, centerY + dist, color);

            graphics.horizontalLine(centerX + dist, centerX + dist + 4, centerY + dist, color);
            graphics.verticalLine(centerX + dist, centerY + dist - 4, centerY + dist, color);

            graphics.fill(centerX - 1, centerY - 1, centerX + 2, centerY + 2, withAlpha(0xFFFFFFFF, alpha));
        }

        /**
         * Doppel-Stoßring Kill-Banner
         */
        private static void drawKillConfirmation(GuiGraphicsExtractor graphics, Font font, int centerX, int centerY, int effectTicks) {
            if (effectTicks <= 0) return;

            int alpha = Math.min(255, effectTicks * 32);
            int ringRadius = (16 - effectTicks) * 3;
            int bannerColor = withAlpha(OsokWidgets.COLOR_CRIMSON, alpha);

            // Äußerer Schockring
            graphics.horizontalLine(centerX - ringRadius, centerX + ringRadius, centerY - ringRadius, bannerColor);
            graphics.horizontalLine(centerX - ringRadius, centerX + ringRadius, centerY + ringRadius, bannerColor);
            graphics.verticalLine(centerX - ringRadius, centerY - ringRadius, centerY + ringRadius, bannerColor);
            graphics.verticalLine(centerX + ringRadius, centerY - ringRadius, centerY + ringRadius, bannerColor);

            String killText = "✦ ZIEL AUSGESCHALTET ✦";
            graphics.centeredText(font, killText, centerX, centerY - 45, withAlpha(OsokWidgets.COLOR_GOLD, alpha));
        }

        public static int withAlpha(int color, int alpha) {
            return Mth.clamp(alpha, 0, 255) << 24 | (color & 0x00FFFFFF);
        }

        public static int lerpColor(int from, int to, float progress) {
            int alpha = Mth.lerpInt(progress, from >>> 24, to >>> 24);
            int red = Mth.lerpInt(progress, (from >> 16) & 0xFF, (to >> 16) & 0xFF);
            int green = Mth.lerpInt(progress, (from >> 8) & 0xFF, (to >> 8) & 0xFF);
            int blue = Mth.lerpInt(progress, from & 0xFF, to & 0xFF);
            return (alpha << 24) | (red << 16) | (green << 8) | blue;
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
            Minecraft client = Minecraft.getInstance();
            LocalPlayer player = client.player;
            if (player == null || !client.options.getCameraType().isFirstPerson()
                    || !player.getMainHandItem().is(ModItems.MINIGUN)) {
                return;
            }

            int centerX = (graphics.guiWidth() - 1) / 2;
            int centerY = (graphics.guiHeight() - 1) / 2;
            float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);

            MinigunHudState state = MinigunHudState.INSTANCE;
            int useTicks = player.isUsingItem() && player.getUseItem().is(ModItems.MINIGUN)
                    ? player.getTicksUsingItem()
                    : 0;
            boolean firing = useTicks >= MinigunItem.WARM_UP_TICKS;
            boolean warmingUp = useTicks > 0 && !firing;
            boolean expiring = state.isExpiring();
            int remainingUseTicks = state.getRemainingUseTicks();
            float spin = state.getSpin();
            float spinPhase = state.getSpinPhase(partialTick);
            int accent = accentColor(player, firing, expiring, remainingUseTicks, spin);

            drawApexBarrelCluster(graphics, centerX, centerY, spinPhase, spin, accent);
            drawTargetBrackets(graphics, centerX, centerY, accent, spin, firing);
            drawCircularTimeGauge(graphics, centerX, centerY, remainingUseTicks, MinigunRuntime.USE_DURATION_TICKS, accent);
            drawHitTally(graphics, client.font, centerX, centerY, state.getHitsOnTarget());
            drawWeaponTelemetry(graphics, client.font, centerX, centerY, warmingUp, firing, expiring, remainingUseTicks, spin, accent);
            drawHitConfirmation(graphics, centerX, centerY, state.getHitEffectTicks());
            drawKillConfirmation(graphics, client.font, centerX, centerY, state.getKillEffectTicks());
        }
    }

    // =========================================================================
    // RailgunHudLayer.java
    // =========================================================================

    /**
     * Visier und Ladeanzeige der Railgun: Magnetischer Kompressions-Kollimator,
     * Tesla-Lichtbögen, Mil-Dot Präzisions-Reticle und 4-Zellen Hochvolt-Kondensator.
     */
    public static final class RailgunHudLayer implements HudElement {
        /**
         * Sci-Fi Scharfschützen-Reticle mit Mil-Dots und Entfernungs-Winkeln
         */
        private static void drawIdleReticle(GuiGraphicsExtractor graphics, int cx, int cy, float time) {
            // Glühender 1px Präzisionspunkt
            graphics.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0x6600F0FF);
            graphics.fill(cx, cy, cx + 1, cy + 1, 0xFFFFFFFF);

            // 4 feine Fadenkreuz-Linien mit Mil-Dots
            int gap = 5;
            int len = 8;
            graphics.horizontalLine(cx - gap - len, cx - gap, cy, OsokWidgets.COLOR_CYAN);
            graphics.horizontalLine(cx + gap, cx + gap + len, cy, OsokWidgets.COLOR_CYAN);
            graphics.verticalLine(cx, cy - gap - len, cy - gap, OsokWidgets.COLOR_CYAN);
            graphics.verticalLine(cx, cy + gap, cy + gap + len, OsokWidgets.COLOR_CYAN);

            // 4 Mil-Dots
            int dotDist = 18;
            graphics.fill(cx - dotDist, cy, cx - dotDist + 1, cy + 1, OsokWidgets.COLOR_CYAN);
            graphics.fill(cx + dotDist, cy, cx + dotDist + 1, cy + 1, OsokWidgets.COLOR_CYAN);
            graphics.fill(cx, cy - dotDist, cx + 1, cy - dotDist + 1, OsokWidgets.COLOR_CYAN);
            graphics.fill(cx, cy + dotDist, cx + 1, cy + dotDist + 1, OsokWidgets.COLOR_CYAN);

            // Äußere Hightech-Winkel
            int bDist = 24;
            int bArm = 5;
            graphics.horizontalLine(cx - bDist - bArm, cx - bDist, cy - bDist, 0x8800F0FF);
            graphics.verticalLine(cx - bDist, cy - bDist, cy - bDist + bArm, 0x8800F0FF);

            graphics.horizontalLine(cx + bDist, cx + bDist + bArm, cy - bDist, 0x8800F0FF);
            graphics.verticalLine(cx + bDist, cy - bDist, cy - bDist + bArm, 0x8800F0FF);

            graphics.horizontalLine(cx - bDist - bArm, cx - bDist, cy + bDist, 0x8800F0FF);
            graphics.verticalLine(cx - bDist, cy + bDist - bArm, cy + bDist, 0x8800F0FF);

            graphics.horizontalLine(cx + bDist, cx + bDist + bArm, cy + bDist, 0x8800F0FF);
            graphics.verticalLine(cx + bDist, cy + bDist - bArm, cy + bDist, 0x8800F0FF);
        }

        /**
         * Magnetischer Kompressions-Kollimator mit Tesla-Lichtbögen
         */
        private static void drawChargingReticle(GuiGraphicsExtractor graphics, LocalPlayer player, int cx, int cy, float progress, float partialTick) {
            float time = player.tickCount + partialTick;
            float pulse = 0.5f + 0.5f * Mth.sin(time * 0.75f);
            int coreColor = progress >= 1.0f ? MinigunHudLayer.lerpColor(OsokWidgets.COLOR_CYAN, 0xFFFFFFFF, pulse) : OsokWidgets.COLOR_CYAN;

            // 1. Kernpunkt
            graphics.fill(cx - 1, cy - 1, cx + 2, cy + 2, coreColor);

            // 2. 4 Magnetische Kompressions-Spulen (ziehen sich von 26px auf 8px zusammen)
            int coilDist = Math.round(26.0f - 18.0f * progress);
            int coilArm = 6;
            int accent = progress >= 1.0f ? MinigunHudLayer.lerpColor(OsokWidgets.COLOR_CYAN, 0xFFFFFFFF, pulse) : OsokWidgets.COLOR_CYAN;

            graphics.horizontalLine(cx - coilDist - coilArm, cx - coilDist, cy - coilDist, accent);
            graphics.verticalLine(cx - coilDist, cy - coilDist, cy - coilDist + coilArm, accent);

            graphics.horizontalLine(cx + coilDist, cx + coilDist + coilArm, cy - coilDist, accent);
            graphics.verticalLine(cx + coilDist, cy - coilDist, cy - coilDist + coilArm, accent);

            graphics.horizontalLine(cx - coilDist - coilArm, cx - coilDist, cy + coilDist, accent);
            graphics.verticalLine(cx - coilDist, cy + coilDist - coilArm, cy + coilDist, accent);

            graphics.horizontalLine(cx + coilDist, cx + coilDist + coilArm, cy + coilDist, accent);
            graphics.verticalLine(cx + coilDist, cy + coilDist - coilArm, cy + coilDist, accent);

            // 3. Tesla-Lichtbögen zwischen den Spulen bei höherer Ladung
            if (progress > 0.3f) {
                float sparkStrength = (progress - 0.3f) / 0.7f;
                int sparkCount = Math.round(sparkStrength * 4);
                for (int s = 0; s < sparkCount; s++) {
                    float sparkAngle = (float) (s * (Math.PI / 2.0) + (Math.sin(time * 3.0f + s) * 0.4f));
                    int sx = cx + Math.round(Mth.cos(sparkAngle) * (coilDist + 2));
                    int sy = cy + Math.round(Mth.sin(sparkAngle) * (coilDist + 2));
                    graphics.fill(sx, sy, sx + 1, sy + 1, 0xFFE0FFFF);
                }
            }

            // 4. Bei voller Ladung: 4-Punkt Lock-On Matrix
            if (progress >= 1.0f) {
                int lockDist = 12;
                graphics.fill(cx - lockDist, cy - lockDist, cx - lockDist + 2, cy - lockDist + 2, 0xFFFFFFFF);
                graphics.fill(cx + lockDist - 1, cy - lockDist, cx + lockDist + 1, cy - lockDist + 2, 0xFFFFFFFF);
                graphics.fill(cx - lockDist, cy + lockDist - 1, cx - lockDist + 2, cy + lockDist + 1, 0xFFFFFFFF);
                graphics.fill(cx + lockDist - 1, cy + lockDist - 1, cx + lockDist + 1, cy + lockDist + 1, 0xFFFFFFFF);
            }
        }

        /**
         * 4-Zellen Hochvolt-Kondensator Ladebalken
         */
        private static void drawChargeBar(GuiGraphicsExtractor graphics, Font font, LocalPlayer player, int centerX, int y, float progress, float partialTick) {
            int width = 120;
            int height = 6;
            int left = centerX - width / 2;
            int right = left + width;

            // Glassmorphic Capacitor Deck
            graphics.fill(left - 3, y - 3, right + 3, y + height + 3, 0xF20A0D15);
            graphics.fill(left - 2, y - 2, right + 2, y + height + 2, 0x4400F0FF);
            graphics.fill(left - 1, y - 1, right + 1, y + height + 1, 0xCC050A10);

            // 4 Zellen-Aufteilung (25%, 50%, 75%, 100%)
            int cells = 4;
            int cellGap = 2;
            int totalGaps = (cells - 1) * cellGap;
            int cellWidth = (width - totalGaps) / cells;

            for (int c = 0; c < cells; c++) {
                int cx = left + c * (cellWidth + cellGap);
                float cellStart = c / (float) cells;
                float cellEnd = (c + 1) / (float) cells;
                float cellProgress = Mth.clamp((progress - cellStart) / (cellEnd - cellStart), 0.0f, 1.0f);

                // Zelle Hintergrund
                graphics.fill(cx, y, cx + cellWidth, y + height, 0x3300F0FF);

                if (cellProgress > 0.0f) {
                    int filledW = Math.round(cellWidth * cellProgress);
                    int color;
                    if (progress >= 1.0f) {
                        float pulse = 0.5f + 0.5f * Mth.sin((player.tickCount + partialTick) * 0.7f);
                        color = MinigunHudLayer.lerpColor(OsokWidgets.COLOR_CYAN, 0xFFFFFFFF, pulse);
                    } else {
                        color = MinigunHudLayer.lerpColor(0xFF0066FF, OsokWidgets.COLOR_CYAN, cellProgress);
                    }
                    graphics.fill(cx, y, cx + filledW, y + height, color);
                }
            }

            // Telemetrie-Text
            if (progress >= 1.0f) {
                float pulse = 0.5f + 0.5f * Mth.sin((player.tickCount + partialTick) * 0.7f);
                int textColor = MinigunHudLayer.lerpColor(OsokWidgets.COLOR_CYAN, 0xFFFFFFFF, pulse);
                graphics.centeredText(font, "⚡ 1.21 GW // DISCHARGE READY ⚡", centerX, y + height + 6, textColor);
            } else {
                int pct = (int) (progress * 100);
                graphics.centeredText(font, "⚡ CAPACITOR: " + pct + "%", centerX, y + height + 6, OsokWidgets.COLOR_TEXT_MUTED);
            }
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
            Minecraft client = Minecraft.getInstance();
            LocalPlayer player = client.player;
            if (player == null || !client.options.getCameraType().isFirstPerson()) {
                return;
            }

            boolean holdingRailgun = player.getMainHandItem().is(ModItems.RAILGUN)
                    || player.getOffhandItem().is(ModItems.RAILGUN);
            if (!holdingRailgun) {
                return;
            }

            int centerX = (graphics.guiWidth() - 1) / 2;
            int centerY = (graphics.guiHeight() - 1) / 2;
            float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);

            boolean isCharging = player.isUsingItem() && player.getUseItem().is(ModItems.RAILGUN);
            if (!isCharging) {
                drawIdleReticle(graphics, centerX, centerY, player.tickCount + partialTick);
                return;
            }

            float progress = Math.min(1.0f, (player.getTicksUsingItem() + partialTick) / (float) com.oneshotonekill.item.runtime.RailgunSystem.CHARGE_TICKS);

            drawChargingReticle(graphics, player, centerX, centerY, progress, partialTick);
            drawChargeBar(graphics, client.font, player, centerX, centerY + 30, progress, partialTick);
        }
    }

    // =========================================================================
    // GrapplingHookHudLayer.java
    // =========================================================================

    // =========================================================================
    // AirstrikeAlarmLayer.java
    // =========================================================================

    /**
     * Moderner DEFCON-1 Bombenalarm mit Notfall-Banner, 3D Tracking-Projektionsmarker
     * und weicher Gefahren-Vignette.
     */
    public static final class AirstrikeAlarmLayer implements HudElement {
        private static final int ALARM_RED = 0xFFFF3366;
        private static final int ALARM_RED_DIM = 0xAA881122;
        private static final int CORE = 0xFFFFFFFF;
        private static final int SAFE_CYAN = 0xFF00F0FF;
        private static final int BACKDROP = 0xF00A0E16;

        private static final int VIGNETTE_DEPTH = 32;
        private static final int VIGNETTE_STEPS = 8;
        private static final int MARKER_MARGIN = 46;
        private static final int EDGE_MARGIN = 26;
        private static final double ON_SCREEN_LIMIT = 78.0;

        /**
         * Weich pulsierende Notfall-Vignette am Bildschirmrand
         */
        private static void drawVignette(GuiGraphicsExtractor graphics, float intensity, boolean endangered) {
            int width = graphics.guiWidth();
            int height = graphics.guiHeight();
            int rgb = ALARM_RED & 0x00FFFFFF;
            float strength = intensity * (endangered ? 1.0f : 0.5f);
            for (int step = 0; step < VIGNETTE_STEPS; step++) {
                int depth = VIGNETTE_DEPTH * (step + 1) / VIGNETTE_STEPS;
                int alpha = (int) (strength * 0x38 * (1.0f - step / (float) VIGNETTE_STEPS));
                if (alpha <= 2) {
                    continue;
                }
                int color = alpha << 24 | rgb;
                graphics.fill(0, 0, width, depth, color);
                graphics.fill(0, height - depth, width, height, color);
                graphics.fill(0, depth, depth, height - depth, color);
                graphics.fill(width - depth, depth, width, height - depth, color);
            }
        }

        /**
         * Glassmorphic DEFCON Banner mit Gefahrenstreifen oben
         */
        private static void drawBanner(GuiGraphicsExtractor graphics, Font font, AirstrikeAlarmState alarm,
                                       int accent, boolean endangered, boolean blinkOn, float time) {
            int centerX = graphics.guiWidth() / 2;
            int top = Math.max(12, graphics.guiHeight() / 7);

            int panelWidth = 240;
            int panelHeight = alarm.isIncoming() ? 38 : 22;
            int left = centerX - panelWidth / 2;
            int right = left + panelWidth;

            // Glassmorphic Deck
            graphics.fill(left, top, right, top + panelHeight, BACKDROP);
            graphics.outline(left, top, panelWidth, panelHeight, accent);
            graphics.horizontalLine(left + 2, right - 2, top + 1, endangered ? ALARM_RED : OsokWidgets.COLOR_GOLD);

            // Hazard Schraffur-Ticks
            int stripeShift = Math.floorMod((int) (time * 2.0f), 12);
            for (int s = -stripeShift; s < panelWidth; s += 12) {
                int sx = left + s;
                if (sx >= left + 2 && sx + 3 <= right - 2) {
                    graphics.fill(sx, top + 2, sx + 3, top + 4, endangered ? ALARM_RED : OsokWidgets.COLOR_GOLD);
                }
            }

            String headline = alarm.isIncoming()
                    ? (endangered ? Component.translatable("hud.oneshotonekill.airstrike.danger").getString() : Component.translatable("hud.oneshotonekill.airstrike.incoming").getString())
                    : Component.translatable("hud.oneshotonekill.airstrike.impact").getString();
            graphics.centeredText(font, headline, centerX, top + 7, blinkOn ? ALARM_RED : 0xFFFFFFFF);

            if (!alarm.isIncoming()) {
                return;
            }

            int distance = (int) Math.round(alarm.distanceToTarget());
            String detail = endangered
                    ? Component.translatable("hud.oneshotonekill.airstrike.in_radius", distance).getString()
                    : Component.translatable("hud.oneshotonekill.airstrike.impact_distance", distance).getString();
            graphics.centeredText(font, detail, centerX, top + 18, endangered ? ALARM_RED : SAFE_CYAN);
            drawCountdownBar(graphics, centerX, top + 29, alarm, accent);
        }

        private static void drawCountdownBar(GuiGraphicsExtractor graphics, int centerX, int y, AirstrikeAlarmState alarm, int accent) {
            int barWidth = 180;
            int left = centerX - barWidth / 2;
            float progress = Mth.clamp(alarm.getRemainingTicks() / (float) alarm.getWarningTicks(), 0.0f, 1.0f);

            graphics.fill(left, y, left + barWidth, y + 4, 0x9905080E);
            graphics.fill(left, y, left + Math.round(barWidth * progress), y + 4, accent);
            graphics.outline(left - 1, y - 1, barWidth + 2, 6, 0x6600F0FF);
        }

        /**
         * 3D Tracking-Projektionsmarker für anfliegende Gefechtsköpfe
         */
        private static void drawBombMarker(GuiGraphicsExtractor graphics, Minecraft client, Font font,
                                           AirstrikeAlarmState alarm, LocalPlayer player, int accent, boolean blinkOn, float partialTick) {
            net.minecraft.world.phys.Vec3 eye = player.getEyePosition(partialTick);
            double deltaX = alarm.getTargetX() - eye.x;
            double deltaY = alarm.bombY(partialTick) - eye.y;
            double deltaZ = alarm.getTargetZ() - eye.z;
            double horizontal = Math.hypot(deltaX, deltaZ);
            if (horizontal < 0.01 && Math.abs(deltaY) < 0.01) {
                return;
            }

            double targetYaw = Math.toDegrees(Math.atan2(-deltaX, deltaZ));
            double targetPitch = -Math.toDegrees(Math.atan2(deltaY, horizontal));
            double yawOffset = Mth.wrapDegrees(targetYaw - player.getViewYRot(partialTick));
            double pitchOffset = targetPitch - player.getViewXRot(partialTick);

            int width = graphics.guiWidth();
            int height = graphics.guiHeight();
            int centerX = width / 2;
            int centerY = height / 2;
            int x = centerX;
            int y = centerY;
            boolean onScreen = false;

            if (Math.abs(yawOffset) < ON_SCREEN_LIMIT && Math.abs(pitchOffset) < ON_SCREEN_LIMIT) {
                double halfVertical = Math.tan(Math.toRadians(client.options.fov().get() / 2.0));
                double halfHorizontal = halfVertical * width / (double) height;
                x = centerX + (int) Math.round(Math.tan(Math.toRadians(yawOffset)) / halfHorizontal * centerX);
                y = centerY + (int) Math.round(Math.tan(Math.toRadians(pitchOffset)) / halfVertical * centerY);
                onScreen = x >= EDGE_MARGIN && x <= width - EDGE_MARGIN && y >= EDGE_MARGIN && y <= height - EDGE_MARGIN;
            }

            if (!onScreen) {
                int radius = Math.max(20, Math.min(centerX, centerY) - MARKER_MARGIN);
                double bearing = Math.toRadians(yawOffset);
                x = centerX + (int) Math.round(Math.sin(bearing) * radius);
                y = centerY - (int) Math.round(Math.cos(bearing) * radius);
            }

            drawMarkerIcon(graphics, x, y, accent, blinkOn, onScreen);
            if (alarm.isIncoming()) {
                String altitude = Math.max(0, (int) Math.round(alarm.bombY(partialTick) - player.getY())) + "m ↓";
                graphics.centeredText(font, altitude, x, y + 14, blinkOn ? ALARM_RED : 0xFFFFFFFF);
            }
        }

        private static void drawMarkerIcon(GuiGraphicsExtractor graphics, int x, int y, int accent, boolean blinkOn, boolean onScreen) {
            int outer = blinkOn ? 10 : 8;
            graphics.outline(x - outer, y - outer, outer * 2 + 1, outer * 2 + 1, accent);
            graphics.outline(x - outer + 1, y - outer + 1, outer * 2 - 1, outer * 2 - 1, 0xAA000000);

            if (blinkOn) {
                graphics.fill(x - 4, y - 4, x + 5, y + 5, ALARM_RED);
                graphics.fill(x - 1, y - 1, x + 2, y + 2, CORE);
            } else {
                graphics.outline(x - 4, y - 4, 9, 9, accent);
            }

            int reach = outer + (blinkOn ? 6 : 4);
            graphics.horizontalLine(x - reach, x - outer - 1, y, accent);
            graphics.horizontalLine(x + outer + 1, x + reach, y, accent);
            graphics.verticalLine(x, y - reach, y - outer - 1, accent);
            graphics.verticalLine(x, y + outer + 1, y + reach, accent);

            if (!onScreen) {
                graphics.outline(x - outer - 3, y - outer - 3, (outer + 3) * 2 + 1, (outer + 3) * 2 + 1, 0x5500F0FF);
            }
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
            AirstrikeAlarmState alarm = AirstrikeAlarmState.INSTANCE;
            Minecraft client = Minecraft.getInstance();
            LocalPlayer player = client.player;
            if (!alarm.isActive() || player == null) {
                return;
            }

            float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
            boolean endangered = alarm.isInBlastRadius();

            float urgency = alarm.isIncoming()
                    ? 1.0f - Mth.clamp(alarm.getRemainingTicks() / (float) alarm.getWarningTicks(), 0.0f, 1.0f)
                    : 1.0f;
            int blinkPeriod = urgency > 0.55f ? 2 : 4;
            boolean blinkOn = player.tickCount / blinkPeriod % 2 == 0;
            int accent = blinkOn ? ALARM_RED : ALARM_RED_DIM;

            drawVignette(graphics, blinkOn ? 1.0f : 0.4f, endangered);
            drawBanner(graphics, client.font, alarm, accent, endangered, blinkOn, player.tickCount + partialTick);
            drawBombMarker(graphics, client, client.font, alarm, player, accent, blinkOn, partialTick);
        }
    }

    // =========================================================================
    // ItemBoxLayer.java
    // =========================================================================

    /**
     * Marker fuer die Spezialitem-Boxen am Arenaboden.
     * <p>
     * Auf Fabric war das ein Mixin auf {@code Gui}, weil es dort keinen Hook fuer eine eigene
     * HUD-Ebene gibt. Fabric API kennt {@link HudElement} – die Zeichenroutine ist unveraendert, nur
     * angemeldet wird sie jetzt regulaer ueber {@code HudElementRegistry}.
     */
    public static final class ItemBoxLayer implements HudElement {

        private static final int GOLD = 0xFFFFC64B;
        private static final int GOLD_SOFT = 0xFFB08A34;
        private static final int LABEL = 0xFFE8E2D2;

        /**
         * Weiter entfernte Boxen auszublenden hält das Bild ruhig.
         */
        private static final double MAX_DISTANCE = 90.0;
        private static final int EDGE_MARGIN = 20;

        private static void draw(GuiGraphicsExtractor graphics, Font font, LocalPlayer player, Vec3 at,
                                 float partialTick, float beat) {
            OsokWidgets.WorldMarker.Projection marker = OsokWidgets.WorldMarker.project(graphics, player, at, partialTick, EDGE_MARGIN);
            if (!marker.onScreen()) {
                // Kein Radar-Marker am Bildschirmrand: Nur anzeigen, wenn man wirklich zur Box schaut
                return;
            }

            int size = 5;
            int alpha = (int) (0x88 + 0x77 * beat);
            int color = alpha << 24 | GOLD & 0x00FFFFFF;

            // Raute statt Kasten – sie hebt sich vom rechteckigen Vanilla-HUD ab.
            for (int offset = -size; offset <= size; offset++) {
                int span = size - Math.abs(offset);
                graphics.horizontalLine(marker.x() - span, marker.x() + span, marker.y() + offset,
                        Math.abs(offset) == size || span == 0 ? color : GOLD_SOFT & 0x00FFFFFF | alpha / 3 << 24);
            }
            graphics.fill(marker.x() - 1, marker.y() - 1, marker.x() + 2, marker.y() + 2, 0xFFFFFFFF);

            String label = Math.round(marker.distance()) + " m";
            graphics.centeredText(font, label, marker.x(), marker.y() + size + 3, LABEL);
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
            Minecraft client = Minecraft.getInstance();
            LocalPlayer player = client.player;
            if (player == null || client.level == null) {
                return;
            }

            float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
            float beat = 0.5f + 0.5f * Mth.sin((player.tickCount + partialTick) * 0.22f);

            for (Entity entity : client.level.entitiesForRendering()) {
                if (!(entity instanceof Display.ItemDisplay box) || !Hologram.item(box).is(ModItems.ITEM_BOX)) {
                    continue;
                }
                Vec3 at = box.position().add(0.0, 0.55, 0.0);
                if (at.distanceToSqr(player.position()) > MAX_DISTANCE * MAX_DISTANCE) {
                    continue;
                }
                draw(graphics, client.font, player, at, partialTick, beat);
            }
        }
    }

    // =========================================================================
    // DeployableMarkerLayer.java
    // =========================================================================

    /**
     * Peilung auf die eigenen abgestellten Geräte: C4-Ladungen, Geschütztürme und Frost-Fallen.
     * <p>
     * Die drei haben gemeinsam, dass man sie irgendwo hinstellt und danach wiederfinden muss. Die
     * Frost-Falle sagt es beim Aufstellen sogar ausdrücklich – „merk dir die Stelle“ –, weil sie
     * für alle unsichtbar ist; eine C4 klebt schnell an einer Wand, die von vorne wie jede andere
     * aussieht. Diese Ebene nimmt einem das Merken ab, ohne die Geräte für Gegner zu verraten:
     * gezeichnet wird nur, was der Server als Eigentum des Empfängers meldet.
     * <p>
     * Projiziert wird über {@link OsokWidgets.WorldMarker} – derselbe Baustein, den auch der
     * Marker der Item-Boxen benutzt, damit beide Anzeigen an derselben Stelle sitzen.
     * <p>
     * <p>Gezeichnet wird ausschließlich, was wirklich im Bild liegt. Es gibt bewusst keinen
     * Randanzeiger und keine Peilung um das Fadenkreuz: Wer wegsieht, soll sein HUD frei haben.
     * <p>
     * <p>Und wer sein Gerät ohnehin sieht, braucht kein Zeichen darauf. Steht nichts zwischen
     * Kamera und Gerät, blendet die Plakette aus – siehe {@link Sightlines}. Übrig bleibt genau
     * der Fall, für den die Peilung gedacht ist: Das Gerät liegt hinter einer Wand, unter einem
     * Dach oder um die Ecke.
     */
    public static final class DeployableMarkerLayer implements HudElement {
        private static final int C4_COLOR = 0xFFFF8A46;
        private static final int TURRET_COLOR = 0xFFFFC64B;
        private static final int TRAP_COLOR = 0xFF6FE9FF;
        /**
         * Das Zeichen selbst ist fast weiß – Farbe trägt der Rand, Form und Kontrast das Zeichen.
         */
        private static final int GLYPH = 0xFFF4F8FF;
        private static final int LABEL = 0xFFE6ECF4;
        /**
         * Dunkler Grund hinter jeder Form; ohne ihn verschwindet ein Zeichen auf heller Karte.
         */
        private static final int SHADOW = 0xFF05070A;
        private static final int PLATE = 0xC2060910;
        /**
         * Anteil, mit dem die Gerätefarbe für die Plakettenfüllung ins Dunkle gezogen wird.
         */
        private static final float BODY_DARKEN = 0.76F;

        /**
         * Weiter entfernte Geräte auszublenden hält das Bild ruhig.
         */
        private static final double MAX_DISTANCE = 120.0;
        /**
         * Bis hierher bleibt ein Zeichen in voller Größe; danach tritt es zurück.
         */
        private static final double NEAR_DISTANCE = 12.0;
        private static final float MIN_FADE = 0.45F;

        /**
         * Halbe Höhe der Plakette, nah und fern.
         */
        private static final int SIZE_NEAR = 9;
        private static final int SIZE_FAR = 6;

        /**
         * Rand des Bildes, in dem ein Zeichen ausblendet.
         * <p>
         * Ohne diesen Streifen springt ein Zeichen an der Kante hart an und aus, sobald man den Kopf
         * bewegt. Einen Randanzeiger gibt es bewusst nicht: Was nicht im Bild liegt, wird gar nicht
         * gezeichnet – wer nicht hinschaut, soll sein HUD frei haben.
         */
        private static final int EDGE_MARGIN = 8;
        private static final int EDGE_FADE_BAND = 20;

        /**
         * Wie hoch die Plakette über dem Gerät schwebt, damit sie nicht im Boden steckt.
         */
        private static final double BADGE_LIFT = 0.75;

        private static final Sightlines SIGHTLINES = new Sightlines();

        private static void draw(
                GuiGraphicsExtractor graphics, Font font, LocalPlayer player,
                DeployableMarkersPayload.Marker marker, Vec3 device, float partialTick, float pulse
        ) {
            // Freie Sicht auf das Gerät heißt: kein Zeichen. Übergänge laufen weich, damit an einer
            // Mauerkante nichts flackert.
            float obstructed = SIGHTLINES.obstruction(device, partialTick);
            if (obstructed <= 0.01F) {
                return;
            }

            OsokWidgets.WorldMarker.Projection projection =
                    OsokWidgets.WorldMarker.project(graphics, player, device.add(0.0, BADGE_LIFT, 0.0), partialTick, EDGE_MARGIN);
            if (!projection.onScreen()) {
                return;
            }

            int x = projection.x();
            int y = projection.y();

            // Nähe bringt ein Zeichen nach vorne: es wird größer und deckender. Zum Bildrand hin
            // blendet es weich aus, damit beim Umsehen nichts aufpoppt.
            float proximity = 1.0F - (float) Mth.clamp(
                    (projection.distance() - NEAR_DISTANCE) / (MAX_DISTANCE - NEAR_DISTANCE), 0.0, 1.0);
            float presence = Mth.lerp(proximity, MIN_FADE, 1.0F) * edgeFade(graphics, x, y) * obstructed;
            if (presence <= 0.02F) {
                return;
            }
            int alpha = (int) (255.0F * presence);
            if (alpha <= 4) {
                return;
            }

            int accent = color(marker.kind());
            int size = Math.round(Mth.lerp(proximity, SIZE_FAR, SIZE_NEAR));

            // Ein scharfes Gerät bekommt einen pulsierenden Kranz statt eines flackernden Zeichens:
            // die Plakette bleibt ruhig lesbar, die Warnung liegt außen herum.
            if (marker.alerted()) {
                drawAlertRing(graphics, x, y, size + 4, MinigunHudLayer.withAlpha(accent, (int) (alpha * (0.25F + 0.6F * pulse))));
            }

            drawBadge(graphics, x, y, size, accent, alpha);
            drawGlyph(graphics, marker.kind(), x, y, MinigunHudLayer.withAlpha(GLYPH, alpha), size);
            drawStem(graphics, x, y + size, MinigunHudLayer.withAlpha(accent, alpha), MinigunHudLayer.withAlpha(SHADOW, alpha * 3 / 5));
            drawDistancePlate(graphics, font, x, y + size + 6, projection.distance(), alpha);
        }

        /**
         * Weiches Ausblenden zum Bildrand hin.
         * <p>
         * Gerechnet wird über den kleinsten Abstand zu einer der vier Kanten; innerhalb des
         * Ausblendstreifens läuft die Deckkraft linear auf null.
         */
        private static float edgeFade(GuiGraphicsExtractor graphics, int x, int y) {
            int toEdge = Math.min(
                    Math.min(x - EDGE_MARGIN, graphics.guiWidth() - EDGE_MARGIN - x),
                    Math.min(y - EDGE_MARGIN, graphics.guiHeight() - EDGE_MARGIN - y));
            return Mth.clamp(toEdge / (float) EDGE_FADE_BAND, 0.0F, 1.0F);
        }

        /**
         * Die Plakette: Schlagschatten, farbiger Rand, dunkle Füllung.
         * <p>
         * Ein gefülltes Achteck mit hellem Zeichen darauf liest sich auf jedem Untergrund sofort –
         * anders als reine Strichzeichnung, die vor heller Karte verschwindet. Die Füllung ist die
         * Gerätefarbe, weit ins Dunkle gezogen: Sie bleibt als Farbton erkennbar, ohne dem weißen
         * Zeichen den Kontrast zu nehmen.
         */
        private static void drawBadge(GuiGraphicsExtractor graphics, int x, int y, int size, int accent, int alpha) {
            octagon(graphics, x + 1, y + 1, size, MinigunHudLayer.withAlpha(SHADOW, alpha * 3 / 5));
            octagon(graphics, x, y, size, MinigunHudLayer.withAlpha(accent, alpha));
            octagon(graphics, x, y, size - 1,
                    MinigunHudLayer.withAlpha(MinigunHudLayer.lerpColor(accent, SHADOW, BODY_DARKEN), alpha));
        }

        /**
         * Gefülltes Achteck als Zeilenfüllung – eine Fläche je Bildzeile.
         * <p>
         * Die abgeschrägten Ecken nehmen der Form das Klobige eines Rechtecks, ohne die
         * Rundungsartefakte, die ein echter Kreis auf diesem Raster zeigt.
         */
        private static void octagon(GuiGraphicsExtractor graphics, int cx, int cy, int size, int color) {
            if (size <= 0 || (color >>> 24) == 0) {
                return;
            }
            int straight = size - Math.max(1, size / 2);
            for (int dy = -size; dy <= size; dy++) {
                int half = size - Math.max(0, Math.abs(dy) - straight);
                graphics.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
            }
        }

        /**
         * Ein Kranz aus acht Punkten um die Plakette – die Warnung für scharfe Geräte.
         */
        private static void drawAlertRing(GuiGraphicsExtractor graphics, int x, int y, int radius, int color) {
            if ((color >>> 24) == 0) {
                return;
            }
            for (int angle = 0; angle < 360; angle += 45) {
                double rad = Math.toRadians(angle);
                int px = x + (int) Math.round(Math.cos(rad) * radius);
                int py = y + (int) Math.round(Math.sin(rad) * radius);
                graphics.fill(px - 1, py - 1, px + 1, py + 1, color);
            }
        }

        /**
         * Der kurze Stiel unter der Plakette: er macht aus dem Aufkleber eine Nadel im Raum.
         */
        private static void drawStem(GuiGraphicsExtractor graphics, int x, int bottom, int color, int shadow) {
            graphics.fill(x, bottom + 1, x + 2, bottom + 5, shadow);
            graphics.fill(x - 1, bottom, x + 1, bottom + 4, color);
        }

        /**
         * Das Zeichen in der Mitte; bei kleiner Plakette entfällt das Beiwerk.
         */
        private static void drawGlyph(
                GuiGraphicsExtractor graphics, DeployableMarkersPayload.Kind kind, int x, int y, int tint, int size
        ) {
            boolean detailed = size >= 8;
            switch (kind) {
                case C4 -> glyphCharge(graphics, x, y, tint, detailed);
                case TURRET -> glyphTurret(graphics, x, y, tint, detailed);
                case FROST_TRAP -> glyphFrost(graphics, x, y, tint, detailed);
            }
        }

        /**
         * C4: der Sprengstoffriegel, darüber die Antenne des Zünders.
         */
        private static void glyphCharge(GuiGraphicsExtractor graphics, int x, int y, int tint, boolean detailed) {
            graphics.fill(x - 4, y, x + 5, y + 5, tint);
            if (detailed) {
                graphics.fill(x - 1, y - 3, x + 1, y, tint);
                graphics.fill(x - 2, y - 5, x + 2, y - 3, tint);
            }
        }

        /**
         * Geschützturm: Kopf auf Sockel, der Lauf zur Seite.
         */
        private static void glyphTurret(GuiGraphicsExtractor graphics, int x, int y, int tint, boolean detailed) {
            graphics.fill(x - 4, y + 3, x + 5, y + 5, tint);
            graphics.fill(x - 3, y - 2, x + 2, y + 3, tint);
            if (detailed) {
                graphics.fill(x + 2, y - 1, x + 6, y + 1, tint);
            }
        }

        /**
         * Frost-Falle: der sechsstrahlige Eisstern.
         */
        private static void glyphFrost(GuiGraphicsExtractor graphics, int x, int y, int tint, boolean detailed) {
            graphics.fill(x, y - 5, x + 1, y + 6, tint);
            int reach = detailed ? 4 : 3;
            for (int step = 1; step <= reach; step++) {
                graphics.fill(x - step, y - step, x - step + 1, y - step + 1, tint);
                graphics.fill(x + step, y - step, x + step + 1, y - step + 1, tint);
                graphics.fill(x - step, y + step, x - step + 1, y + step + 1, tint);
                graphics.fill(x + step, y + step, x + step + 1, y + step + 1, tint);
            }
            if (detailed) {
                graphics.fill(x - 2, y - 4, x + 3, y - 3, tint);
                graphics.fill(x - 2, y + 4, x + 3, y + 5, tint);
            }
        }

        /**
         * Die Entfernung auf einem eigenen dunklen Feld – lesbar auch über heller Karte.
         */
        private static void drawDistancePlate(
                GuiGraphicsExtractor graphics, Font font, int centerX, int top, double distance, int alpha
        ) {
            String label = Math.round(distance) + " m";
            int half = font.width(label) / 2;
            graphics.fill(centerX - half - 3, top - 1, centerX + half + 3, top + font.lineHeight,
                    MinigunHudLayer.withAlpha(PLATE, alpha * 4 / 5));
            graphics.centeredText(font, label, centerX, top, MinigunHudLayer.withAlpha(LABEL, alpha));
        }

        private static int color(DeployableMarkersPayload.Kind kind) {
            return switch (kind) {
                case C4 -> C4_COLOR;
                case TURRET -> TURRET_COLOR;
                case FROST_TRAP -> TRAP_COLOR;
            };
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
            List<DeployableMarkersPayload.Marker> markers = DeployableMarkerState.INSTANCE.markers();
            if (markers.isEmpty()) {
                return;
            }

            Minecraft client = Minecraft.getInstance();
            LocalPlayer player = client.player;
            if (player == null || client.level == null) {
                return;
            }

            float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
            // Ein gemeinsamer Takt für alle Zeichen: so atmen sie im Gleichschritt, statt zu flirren.
            float pulse = 0.5F + 0.5F * Mth.sin((player.tickCount + partialTick) * 0.32F);

            SIGHTLINES.update(client.level, player, markers);

            for (DeployableMarkersPayload.Marker marker : markers) {
                Vec3 device = new Vec3(marker.x(), marker.y(), marker.z());
                if (device.distanceToSqr(player.position()) > MAX_DISTANCE * MAX_DISTANCE) {
                    continue;
                }
                draw(graphics, client.font, player, marker, device, partialTick, pulse);
            }
        }

        /**
         * Merkt je Gerät, ob etwas zwischen ihm und der Kamera steht.
         * <p>
         * <p>Geprüft wird wie in {@code LivingEntity#hasLineOfSight}: ein Blockstrahl über
         * {@code Level#clip}, und nur ein {@code HitResult.Type.MISS} gilt als freie Sicht.
         * Anders als dort läuft der Strahl gegen {@link ClipContext.Block#VISUAL} statt gegen die
         * Kollisionsform – gefragt ist hier buchstäblich, ob man das Gerät sieht, und durch eine
         * Glasscheibe sieht man hindurch.
         * <p>
         * <p>Gemessen wird von der echten Kameraposition, nicht vom Kopf des Spielers: In der
         * Verfolgeransicht steht die Kamera woanders, und maßgeblich ist, was auf dem Bildschirm
         * ankommt.
         * <p>
         * <p>Ein Blockstrahl ist zu teuer, um ihn in jedem Bild zu ziehen; er läuft einmal je Tick.
         * Zwischen zwei Ticks wandert das Ergebnis weich weiter, sonst würde ein Zeichen an jeder
         * Mauerkante flackern, sobald man sich seitlich bewegt.
         */
        private static final class Sightlines {
            /**
             * Anteil je Tick, um den sich der Übergang seinem Ziel nähert – gut ein Drittel einer Sekunde.
             */
            private static final float STEP = 0.16F;
            /**
             * Dieselbe Obergrenze, die auch Vanilla für Sichtlinien zieht.
             */
            private static final double MAX_RAY = 128.0;

            private final Map<BlockPos, Fade> fades = new HashMap<>();
            private final Set<BlockPos> present = new HashSet<>();
            private int lastTick = Integer.MIN_VALUE;

            private static boolean isObstructed(ClientLevel level, LocalPlayer player, Vec3 eye, Vec3 device) {
                if (eye.distanceToSqr(device) > MAX_RAY * MAX_RAY) {
                    return true;
                }
                return level.clip(new ClipContext(eye, device, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player))
                        .getType() != HitResult.Type.MISS;
            }

            void update(ClientLevel level, LocalPlayer player, List<DeployableMarkersPayload.Marker> markers) {
                if (player.tickCount == lastTick) {
                    return;
                }
                lastTick = player.tickCount;

                Vec3 eye = Minecraft.getInstance().gameRenderer.mainCamera().position();
                present.clear();
                for (DeployableMarkersPayload.Marker marker : markers) {
                    Vec3 device = new Vec3(marker.x(), marker.y(), marker.z());
                    BlockPos key = BlockPos.containing(device);
                    present.add(key);

                    boolean blocked = isObstructed(level, player, eye, device);
                    Fade fade = fades.get(key);
                    if (fade == null) {
                        // Neu aufgetaucht: sofort im Zielzustand, damit nichts sichtbar einschwingt.
                        fades.put(key, new Fade(blocked ? 1.0F : 0.0F));
                        continue;
                    }
                    fade.advance(blocked ? STEP : -STEP);
                }
                fades.keySet().retainAll(present);
            }

            /**
             * 0 = freie Sicht, kein Zeichen. 1 = verdeckt, volles Zeichen.
             */
            float obstruction(Vec3 device, float partialTick) {
                Fade fade = fades.get(BlockPos.containing(device));
                // Noch nicht geprüft: lieber zeigen als verschlucken.
                return fade == null ? 1.0F : fade.value(partialTick);
            }

            private static final class Fade {
                private float previous;
                private float current;

                private Fade(float start) {
                    this.previous = start;
                    this.current = start;
                }

                private void advance(float delta) {
                    previous = current;
                    current = Mth.clamp(current + delta, 0.0F, 1.0F);
                }

                private float value(float partialTick) {
                    return Mth.lerp(partialTick, previous, current);
                }
            }
        }
    }

    // =========================================================================
    // AbilityStatusLayer.java
    // =========================================================================

    /**
     * Die Liste der laufenden Spezial-Item-Wirkungen.
     * <p>
     * Auf Fabric war das ein Mixin auf {@code Gui}, weil es dort keinen Hook fuer eine eigene
     * HUD-Ebene gibt. Fabric API kennt {@link HudElement} – die Zeichenroutine ist unveraendert, nur
     * angemeldet wird sie jetzt regulaer ueber {@code HudElementRegistry}.
     */
    public static final class AbilityStatusLayer implements HudElement {

        private static final int PANEL = 0xA00C1018;
        private static final int PANEL_EDGE = 0xFF2C3040;
        private static final int LABEL = 0xFFE8EAF2;

        private static final int SHIELD = 0xFF4FD8E8;
        private static final int VANISH = 0xFFB0B6C8;
        private static final int MAGNET = 0xFF5AA8E0;
        private static final int GLIDE = 0xFF9FD8F0;
        private static final int FROZEN = 0xFF79DFFF;
        private static final int EXPLOSIVE_ARMED = 0xFFFF654F;
        private static final int LIGHTNING_ARMED = 0xFFFFD33A;
        private static final int CHARGE = 0xFFFF6A4A;
        private static final int TRAP = 0xFF6FE9FF;
        private static final int TURRET = 0xFFFFC64B;

        private static final int MARGIN = 6;
        private static final int ROW_HEIGHT = 13;
        private static final int PANEL_WIDTH = 148;
        /**
         * Volle Laufzeiten, damit die Balken den richtigen Anteil zeigen.
         */
        private static final int VANISH_TOTAL = 300;
        private static final int MAGNET_TOTAL = 300;
        private static final int GLIDE_TOTAL = 160;
        private static final int FROZEN_TOTAL = 140;

        private static Row timed(String name, int remaining, int total, int color) {
            int tenths = (remaining + 1) / 2;
            return new Row(name, tenths / 10 + "." + tenths % 10 + " s",
                    Mth.clamp(remaining / (float) total, 0.0f, 1.0f), color);
        }

        private static void drawRow(GuiGraphicsExtractor graphics, Font font, Row row, int x, int y) {
            String value = row.value;
            int valueX = x + PANEL_WIDTH - 10 - font.width(value);
            // Der Name wird auf den freien Platz gekürzt, statt unter dem Wert durchzulaufen.
            // „Explosiv-Schuss“ und „SCHARF“ überlagerten sich sonst zu einem unlesbaren Klumpen –
            // und der nächste lange Name hätte es wieder getan.
            graphics.text(font, trim(font, row.name, valueX - x - 4), x, y, row.color);
            graphics.text(font, value, valueX, y, LABEL);

            // Schmaler Restzeitbalken unter der Zeile; bei dauerhaften Zuständen bleibt er voll.
            int barWidth = PANEL_WIDTH - 12;
            int filled = Math.round(barWidth * row.progress);
            graphics.fill(x, y + 9, x + barWidth, y + 10, row.color & 0x00FFFFFF | 0x40000000);
            if (filled > 0) {
                graphics.fill(x, y + 9, x + filled, y + 10, row.color);
            }
        }

        /**
         * Kürzt einen Text auf die verfügbare Breite und hängt Auslassungspunkte an.
         */
        private static String trim(Font font, String text, int available) {
            if (font.width(text) <= available) {
                return text;
            }
            String shortened = text;
            while (shortened.length() > 1 && font.width(shortened + "…") > available) {
                shortened = shortened.substring(0, shortened.length() - 1);
            }
            return shortened + "…";
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
            Minecraft client = Minecraft.getInstance();
            AbilityStatusState status = AbilityStatusState.INSTANCE;
            if (client.player == null || status.isEmpty()) {
                return;
            }

            List<Row> rows = new ArrayList<>();
            if (status.hasShield()) {
                rows.add(new Row("🛡 Schild", "bereit", 1.0f, SHIELD));
            }
            if (!status.getArmedShot().isEmpty()) {
                boolean explosive = status.getArmedShot().equals("explosive");
                rows.add(new Row(explosive ? "💣 Explosiv-Schuss" : "⚡ Kettenblitz",
                        "SCHARF", 1.0f, explosive ? EXPLOSIVE_ARMED : LIGHTNING_ARMED));
            }
            if (status.getVanishTicks() > 0) {
                rows.add(timed("👻 Tarnung", status.getVanishTicks(), VANISH_TOTAL, VANISH));
            }
            if (status.getMagnetTicks() > 0) {
                rows.add(timed("🧲 Magnetfeld", status.getMagnetTicks(), MAGNET_TOTAL, MAGNET));
            }
            if (status.getGlideTicks() > 0) {
                rows.add(timed("🦅 Gleitflug", status.getGlideTicks(), GLIDE_TOTAL, GLIDE));
            }
            if (status.getFrozenTicks() > 0) {
                rows.add(timed("❄ Eingefroren", status.getFrozenTicks(), FROZEN_TOTAL, FROZEN));
            }
            if (status.getCharges() > 0) {
                rows.add(new Row("💥 C4", status.getCharges() + "×", 1.0f, CHARGE));
            }
            if (status.getTraps() > 0) {
                rows.add(new Row("❄ Frost-Falle", status.getTraps() + "×", 1.0f, TRAP));
            }
            if (status.getTurrets() > 0) {
                rows.add(new Row("🤖 Geschützturm", status.getTurrets() + "×", 1.0f, TURRET));
            }
            if (rows.isEmpty()) {
                return;
            }

            // Links mittig – dort steht bei Vanilla nichts, und der Blick bleibt in Bildmitte.
            int left = MARGIN;
            int top = graphics.guiHeight() / 2 - rows.size() * ROW_HEIGHT / 2;
            int height = rows.size() * ROW_HEIGHT + 4;
            graphics.fill(left, top - 2, left + PANEL_WIDTH, top + height - 2, PANEL);
            graphics.fill(left, top - 2, left + 1, top + height - 2, PANEL_EDGE);

            for (int index = 0; index < rows.size(); index++) {
                drawRow(graphics, client.font, rows.get(index), left + 4, top + index * ROW_HEIGHT);
            }
        }

        private record Row(String name, String value, float progress, int color) {
        }
    }

    // =========================================================================
    // BomberCameraLayer.java
    // =========================================================================

    /**
     * Taktisches Bild-in-Bild (PiP) Drohnen-/Aufklärungs-HUD für den Tarnkappenbomber.
     * <p>
     * Zeichnet einen militärischen FLIR-Recon-Monitor oben rechts auf den Bildschirm,
     * der das anvisierte Ziel live in 3D aus der Bomber-Perspektive von oben filmt,
     * inklusive Zielklammern, Telemetrie, Scanlines und EMP-Glitch bei Detonationen.
     */
    public static final class BomberCameraLayer implements HudElement {
        private static final int FRAME_WIDTH = 216;
        private static final int FRAME_HEIGHT = 156;
        private static final int MARGIN_RIGHT = 14;
        private static final int MARGIN_TOP = 14;

        /**
         * Rand des Videobildes im Monitor; oben und unten liegen Kopf- und Fußzeile.
         */
        private static final int VIEW_INSET_X = 3;
        private static final int VIEW_INSET_Y = 17;

        private static final int BG_COLOR = 0xEE120104;
        private static final int BORDER_RED = 0xFFFF1E2B;
        private static final int BORDER_DIM = 0x66FF1E2B;
        private static final int TEXT_RED = 0xFFFF3344;
        private static final int TEXT_WHITE = 0xFFFFF2F4;
        private static final int ALERT_RED = 0xFFFF1824;
        private static final int ALERT_RED_DIM = 0x77880008;
        private static final int ALERT_ORANGE = 0xFFFF7700;

        /**
         * Nadir-Blick aus dem Flugzeugbauch, fast senkrecht nach unten in Flugrichtung.
         */
        private static final float NADIR_PITCH = 85.0F;
        /**
         * Weitwinkel-Aufklärungsansicht: Kantenlänge eines Blocks in GUI-Pixeln.
         */
        private static final float VIEW_SCALE = 8.5F;
        /**
         * Halbe Kantenlänge des Geländeausschnitts unter dem Bomber (33x33 Säulen).
         */
        private static final int SCAN_RADIUS = 16;
        private static final int SCAN_ABOVE = 8;
        private static final int SCAN_BELOW = 14;
        /**
         * Wie tief eine sichtbare Geländekante höchstens aufgefüllt wird.
         */
        private static final int MAX_CLIFF_DEPTH = 3;
        /**
         * Ab dieser waagerechten Entfernung liegt eine Entity außerhalb des Ausschnitts.
         */
        private static final float ENTITY_CULL_RADIUS = 22.0F;
        private static final float TARGET_MODEL_SCALE = 1.3F;
        private static final float BOMB_MODEL_SCALE = 3.5F;
        private static final int MAX_FIREBALL_RADIUS = 14;

        /**
         * Restlicht des Aufklärungsbildes. Das Bild bleibt so hell lesbar wie bisher, aber
         * emittierende Blöcke behalten über {@link LightCoordsUtil#getLightCoords} ihre echte
         * Eigenhelligkeit, statt dass wie zuvor die Rohhelligkeit in beide Lichtkanäle geschrieben
         * und damit jeder Block gleich überstrahlt wird.
         */
        private static final int RECON_AMBIENT_LIGHT = LightCoordsUtil.pack(10, 15);

        /**
         * Geländeabtastung, höchstens einmal je Tick statt einmal je Bild – siehe {@link TerrainScan}.
         */
        private static final TerrainScan TERRAIN = new TerrainScan();
        /**
         * Kameraprojektion des laufenden Bildes; nur vom Render-Thread benutzt.
         */
        private static final PipProjector PROJECTOR = new PipProjector();
        /**
         * Fallende Bomben des laufenden Bildes; nur vom Render-Thread benutzt.
         */
        private static final List<BombView> BOMBS = new ArrayList<>();

        /**
         * Alle fallenden Bomber-Bomben mit ihrer interpolierten Position einsammeln.
         */
        private static void collectBombs(ClientLevel level, float partialTick) {
            BOMBS.clear();
            for (Entity entity : level.entitiesForRendering()) {
                if (!(entity instanceof Display.ItemDisplay itemDisplay)
                        || !Hologram.item(itemDisplay).is(ModItems.BOMBER_BOMB)) {
                    continue;
                }
                BOMBS.add(new BombView(
                        itemDisplay,
                        Mth.lerp(partialTick, itemDisplay.xo, itemDisplay.getX()),
                        Mth.lerp(partialTick, itemDisplay.yo, itemDisplay.getY()),
                        Mth.lerp(partialTick, itemDisplay.zo, itemDisplay.getZ())
                ));
            }
        }

        /**
         * Zeichnet den Monitor-Hintergrund, Scanlines und Hi-Tech-Eckklammern im aggressiven Rot-Design.
         */
        private static void drawTacticalFrame(
                GuiGraphicsExtractor graphics, Font font, Minecraft client, int x, int y
        ) {
            int right = x + FRAME_WIDTH;
            int bottom = y + FRAME_HEIGHT;

            // Haupt-Hintergrund (dunkles, bedrohliches Tiefrot-Glas)
            graphics.fill(x, y, right, bottom, BG_COLOR);

            // Äußerer feiner Rahmen
            graphics.fill(x, y, right, y + 1, BORDER_DIM);
            graphics.fill(x, bottom - 1, right, bottom, BORDER_DIM);
            graphics.fill(x, y, x + 1, bottom, BORDER_DIM);
            graphics.fill(right - 1, y, right, bottom, BORDER_DIM);

            // Taktische Eck-Klammern in Signalrot
            int arm = 8;
            // Oben Links
            graphics.fill(x - 1, y - 1, x + arm, y + 1, BORDER_RED);
            graphics.fill(x - 1, y - 1, x + 1, y + arm, BORDER_RED);
            // Oben Rechts
            graphics.fill(right - arm, y - 1, right + 1, y + 1, BORDER_RED);
            graphics.fill(right - 1, y - 1, right + 1, y + arm, BORDER_RED);
            // Unten Links
            graphics.fill(x - 1, bottom - 1, x + arm, bottom + 1, BORDER_RED);
            graphics.fill(x - 1, bottom - arm, x + 1, bottom + 1, BORDER_RED);
            // Unten Rechts
            graphics.fill(right - arm, bottom - 1, right + 1, bottom + 1, BORDER_RED);
            graphics.fill(right - 1, bottom - arm, right + 1, bottom + 1, BORDER_RED);

            // Kopfzeile: REC-Punkt & Titel
            graphics.fill(x, y, right, y + 15, 0xCC1A0205);
            graphics.fill(x, y + 15, right, y + 16, BORDER_DIM);

            boolean blink = client.player != null && (client.player.tickCount / 8 % 2 == 0);
            int recColor = blink ? ALERT_RED : ALERT_RED_DIM;
            // Blinkender roter REC-Punkt
            graphics.fill(x + 6, y + 5, x + 10, y + 9, recColor);

            graphics.text(font, "REC ⏺ B-2 SPECTRE", x + 14, y + 3, TEXT_RED);
            String flirLabel = "FLIR · 144FPS";
            graphics.text(font, flirLabel, right - font.width(flirLabel) - 6, y + 3, MinigunHudLayer.withAlpha(TEXT_RED, 0xDD));

            // Scanlines (subtile rote Video-Linien)
            for (int lineY = y + VIEW_INSET_Y; lineY < bottom - VIEW_INSET_Y; lineY += 3) {
                graphics.fill(x + 1, lineY, right - 1, lineY + 1, 0x14FF0033);
            }
        }

        /**
         * Baut die 3D-Szene der am Bomberbauch montierten Kamera und reicht sie als PiP-Zustand weiter.
         */
        private static void submitPipScene(
                GuiGraphicsExtractor graphics, BomberCameraState state, ClientLevel level,
                @Nullable Player target, int viewX0, int viewY0, int viewX1, int viewY1, float partialTick
        ) {
            if (target == null) {
                return;
            }

            double camX = PROJECTOR.camX;
            double camY = PROJECTOR.camY;
            double camZ = PROJECTOR.camZ;

            // Gelände: ganzzahliger Ursprung, damit der zwischengespeicherte Ausschnitt unabhängig
            // von der Bruchteilposition der Kamera bleibt. Der Versatz zur Kamera wandert als
            // einzelne Verschiebung in den PoseStack statt in jeden einzelnen Blockeintrag.
            int originX = Mth.floor(camX);
            int originY = Mth.floor(state.getTargetY());
            int originZ = Mth.floor(camZ);
            List<BlockEntry> terrain = TERRAIN.get(level, originX, originY, originZ);
            Vector3f terrainOffset = new Vector3f(
                    (float) (originX - camX), (float) (originY - camY), (float) (originZ - camZ));

            // 3D-Explosionsfeuer als Krater-Licht in der PiP-Welt platzieren
            List<BlockEntry> effects = new ArrayList<>();
            BlockState fire = Blocks.FIRE.defaultBlockState();
            int blastLight = LightCoordsUtil.pack(15, 15);
            for (BomberCameraState.DetonationFX det : state.getActiveDetonations()) {
                if ((det.ageTicks + partialTick) / det.maxAgeTicks >= 0.9F) {
                    continue;
                }
                float dx = (float) (det.x - camX);
                float dy = (float) (det.y - camY);
                float dz = (float) (det.z - camZ);
                effects.add(new BlockEntry(dx, dy + 0.4F, dz, fire, blastLight));
                effects.add(new BlockEntry(dx + 1.0F, dy + 0.1F, dz, fire, blastLight));
                effects.add(new BlockEntry(dx - 1.0F, dy + 0.1F, dz, fire, blastLight));
                effects.add(new BlockEntry(dx, dy + 0.1F, dz + 1.0F, fire, blastLight));
                effects.add(new BlockEntry(dx, dy + 0.1F, dz - 1.0F, fire, blastLight));
            }

            EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
            List<EntityRenderEntry> entityEntries = new ArrayList<>();

            // 1. Ziel-Spielermodell (relativ zum fliegenden Bomber)
            float targetRelX = (float) (Mth.lerp(partialTick, target.xo, target.getX()) - camX);
            float targetRelY = (float) (Mth.lerp(partialTick, target.yo, target.getY()) - camY);
            float targetRelZ = (float) (Mth.lerp(partialTick, target.zo, target.getZ()) - camZ);
            if (Math.abs(targetRelX) <= ENTITY_CULL_RADIUS && Math.abs(targetRelZ) <= ENTITY_CULL_RADIUS) {
                EntityRenderer<? super LivingEntity, ?> targetRenderer = dispatcher.getRenderer(target);
                EntityRenderState targetRenderState = targetRenderer.createRenderState(target, partialTick);
                targetRenderState.shadowPieces.clear();
                targetRenderState.outlineColor = 0xFFFF1E2B;

                if (targetRenderState instanceof LivingEntityRenderState livingRenderState) {
                    // Nur die Größenskalierung auf ein einheitliches Aufklärungsmaß normieren.
                    // Körper-, Kopf- und Blickwinkel setzt LivingEntityRenderer#extractRenderState
                    // bereits interpoliert und mit der richtigen Kopf-zu-Körper-Differenz.
                    livingRenderState.boundingBoxWidth = livingRenderState.boundingBoxWidth / livingRenderState.scale;
                    livingRenderState.boundingBoxHeight = livingRenderState.boundingBoxHeight / livingRenderState.scale;
                    livingRenderState.scale = 1.0F;
                }
                entityEntries.add(new EntityRenderEntry(
                        targetRenderState, targetRelX, targetRelY, targetRelZ, TARGET_MODEL_SCALE));
            }

            // 2. Herabfallende 3D-Bomben stark vergrößert rendern
            for (BombView bomb : BOMBS) {
                float relX = (float) (bomb.x() - camX);
                float relY = (float) (bomb.y() - camY);
                float relZ = (float) (bomb.z() - camZ);
                if (Math.abs(relX) > ENTITY_CULL_RADIUS || Math.abs(relZ) > ENTITY_CULL_RADIUS
                        || relY < -24.0F || relY > 5.0F) {
                    continue;
                }
                EntityRenderer<? super Display.ItemDisplay, ?> bombRenderer = dispatcher.getRenderer(bomb.display());
                EntityRenderState bombState = bombRenderer.createRenderState(bomb.display(), partialTick);
                bombState.shadowPieces.clear();
                entityEntries.add(new EntityRenderEntry(bombState, relX, relY, relZ, BOMB_MODEL_SCALE));
            }

            float pitchRad = (float) -Math.toRadians(NADIR_PITCH);
            float yawRad = (float) Math.toRadians(PROJECTOR.yawDeg);
            Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI).rotateX(pitchRad).rotateY(yawRad);
            Quaternionf cameraAngle = new Quaternionf().rotateX(pitchRad).rotateY(yawRad);

            // Vanilla nimmt einen Bild-im-Bild-Zustand direkt am GuiRenderState entgegen;
            // NeoForges submitPictureInPictureRenderState war nur eine Abkürzung dorthin.
            graphics.guiRenderState.addPicturesInPictureState(new BomberCameraPipRenderState(
                    entityEntries,
                    terrain,
                    terrainOffset,
                    effects,
                    rotation,
                    cameraAngle,
                    viewX0,
                    viewY0,
                    viewX1,
                    viewY1,
                    VIEW_SCALE,
                    graphics.scissorStack.peek()
            ));
        }

        /**
         * Zeichnet taktische Telemetrie, Elimination-Meldung, Bomben-Spuren und Explosionen im Gefahren-Look.
         */
        private static void drawTacticalOverlay(
                GuiGraphicsExtractor graphics, Font font, BomberCameraState state,
                int x, int y, int viewX0, int viewY0, int viewX1, int viewY1, float partialTick
        ) {
            int right = x + FRAME_WIDTH;
            int bottom = y + FRAME_HEIGHT;
            int centerX = x + FRAME_WIDTH / 2;

            // Alles Folgende gehört ins Videobild. Die Zeichenroutinen dürfen über den Rand
            // hinauslaufen; abgeschnitten wird über den Scissor-Bereich, statt in jeder Schleife
            // jeden einzelnen Punkt gegen den Rahmen zu prüfen. Ringe und Feuerbälle brechen
            // dadurch am Monitorrand sauber ab, statt stückweise zu verschwinden.
            graphics.enableScissor(viewX0, viewY0, viewX1, viewY1);

            // 1. Taktischer Ziel-Lock & Namenspeilung auf dem Zielspieler
            PROJECTOR.project(state.getTargetX(), state.getTargetY() + 0.9, state.getTargetZ());
            int tx = PROJECTOR.screenX;
            int ty = PROJECTOR.screenY;
            if (tx >= viewX0 + 8 && tx < viewX1 - 8 && ty >= viewY0 + 8 && ty < viewY1 - 8) {
                int bsize = 9;
                int bcolor = 0xEEFF1E2B;
                // 4 Eck-Klammern um das Ziel
                graphics.fill(tx - bsize, ty - bsize, tx - bsize + 4, ty - bsize + 1, bcolor);
                graphics.fill(tx - bsize, ty - bsize, tx - bsize + 1, ty - bsize + 4, bcolor);
                graphics.fill(tx + bsize - 4, ty - bsize, tx + bsize, ty - bsize + 1, bcolor);
                graphics.fill(tx + bsize - 1, ty - bsize, tx + bsize, ty - bsize + 4, bcolor);
                graphics.fill(tx - bsize, ty + bsize - 1, tx - bsize + 4, ty + bsize, bcolor);
                graphics.fill(tx - bsize, ty + bsize - 4, tx - bsize + 1, ty + bsize, bcolor);
                graphics.fill(tx + bsize - 4, ty + bsize - 1, tx + bsize, ty + bsize, bcolor);
                graphics.fill(tx + bsize - 1, ty + bsize - 4, tx + bsize, ty + bsize, bcolor);

                // Ziel-Marker Tag
                graphics.centeredText(font, "▼ " + state.getTargetName(), tx, ty - bsize - 10, 0xFFFF3344);
            }

            // 2. Bombenabwurf-Spuren & Ziel-Impakt-Markierungen
            for (BombView bomb : BOMBS) {
                PROJECTOR.project(bomb.x(), bomb.y(), bomb.z());
                int bScreenX = PROJECTOR.screenX;
                int bScreenY = PROJECTOR.screenY;

                // Glühende Rauch-/Feuerspur über der fallenden Bombe
                for (int step = 1; step <= 5; step++) {
                    int trailY = bScreenY - step * 3;
                    int trailX = bScreenX + (step % 2 == 0 ? 1 : -1);
                    int alpha = Math.max(0, 220 - step * 40);
                    int trailColor = (alpha << 24) | (step <= 2 ? 0xFF6600 : 0xAA3300);
                    graphics.fill(trailX - 1, trailY - 1, trailX + 2, trailY + 2, trailColor);
                }

                // Taktischer Zielkreis am Boden unter der Bombe
                PROJECTOR.project(bomb.x(), state.getTargetY(), bomb.z());
                drawShockwaveRing(graphics, PROJECTOR.screenX, PROJECTOR.screenY, 7, 0xAAFF1E2B);
                graphics.fill(PROJECTOR.screenX - 1, PROJECTOR.screenY - 1,
                        PROJECTOR.screenX + 2, PROJECTOR.screenY + 2, 0xFFFF1E2B);
            }

            // 3. Aktive Explosionen & Druckwellen beim Einschlag rendern
            for (BomberCameraState.DetonationFX det : state.getActiveDetonations()) {
                float progress = (det.ageTicks + partialTick) / det.maxAgeTicks;
                if (progress >= 1.0F) {
                    continue;
                }

                PROJECTOR.project(det.x, det.y, det.z);
                int ex = PROJECTOR.screenX;
                int ey = PROJECTOR.screenY;
                if (ex < viewX0 - 40 || ex >= viewX1 + 40 || ey < viewY0 - 40 || ey >= viewY1 + 40) {
                    continue;
                }

                // Expandierende Stoßwelle
                int shockRadius = (int) (6.0F + progress * 36.0F);
                int shockAlpha = (int) ((1.0F - progress) * 255.0F);
                drawShockwaveRing(graphics, ex, ey, shockRadius, (shockAlpha << 24) | 0xFF3300);

                // Zweiter äußerer Druckwellen-Ring
                if (shockRadius > 10) {
                    drawShockwaveRing(graphics, ex, ey, shockRadius - 6, ((shockAlpha * 2 / 3) << 24) | 0xFF8800);
                }

                // Feuerball im Zentrum
                drawExplosionFireball(graphics, ex, ey, progress);
            }

            graphics.disableScissor();

            // Subtile, kurze Eliminierungs-Meldung (dezent oben im Monitor)
            if (state.isTargetEliminated()) {
                graphics.fill(x + 10, y + 18, right - 10, y + 32, 0xDD990011);
                graphics.fill(x + 10, y + 31, right - 10, y + 32, 0xFFFF1E2B);
                graphics.centeredText(font, Component.translatable("hud.oneshotonekill.bomber.target_eliminated").getString(), centerX, y + 20, 0xFFFFFFFF);
            }

            // Fußzeile mit sauber getrennten Spalten
            graphics.fill(x, bottom - 16, right, bottom, 0xCC1A0205);
            graphics.fill(x, bottom - 16, right, bottom - 15, BORDER_DIM);

            // 1. Spalte: Ziel-Name (Links)
            String targetName = state.getTargetName();
            if (targetName.length() > 10) {
                targetName = targetName.substring(0, 9) + "…";
            }
            graphics.text(font, "TGT: " + targetName, x + 6, bottom - 12, TEXT_WHITE);

            // 2. Spalte: Höhe (Mittig zentriert)
            graphics.centeredText(font, "ALT: " + Math.round(state.altitude()) + "m", centerX, bottom - 12, TEXT_RED);

            // 3. Spalte: Kurs / Heading (Rechtsbündig)
            int hdg = (int) Math.round(Math.toDegrees(state.getHeading()));
            if (hdg < 0) {
                hdg += 360;
            }
            String hdgText = "HDG: " + hdg + "°";
            graphics.text(font, hdgText, right - font.width(hdgText) - 6, bottom - 12, ALERT_ORANGE);

            // Bombenabwurf-Meldung bei Klinken einer Bombe
            if (state.getBombDropFlashTicks() > 0 && !state.isTargetEliminated()) {
                graphics.fill(x + 2, y + 18, right - 2, y + 30, 0xEEFF1E2B);
                graphics.centeredText(font, Component.translatable("hud.oneshotonekill.bomber.bombs_dropped").getString(), centerX, y + 20, 0xFFFFFFFF);
            }
        }

        /**
         * Zeichnet einen expandierenden Druckwellen-Ring einer Explosion.
         */
        private static void drawShockwaveRing(GuiGraphicsExtractor graphics, int cx, int cy, int radius, int color) {
            if (radius <= 0 || (color >>> 24) == 0) {
                return;
            }
            for (int angleDeg = 0; angleDeg < 360; angleDeg += 15) {
                double rad = Math.toRadians(angleDeg);
                int px = cx + (int) Math.round(Math.cos(rad) * radius);
                int py = cy + (int) Math.round(Math.sin(rad) * radius);
                graphics.fill(px - 1, py - 1, px + 2, py + 2, color);
            }
        }

        /**
         * Zeichnet einen blendenden Feuerball mit weißem Kern und radialen Funken.
         */
        private static void drawExplosionFireball(GuiGraphicsExtractor graphics, int cx, int cy, float progress) {
            int alpha = (int) ((1.0F - progress) * 255.0F);
            int coreRadius = Math.max(1, (int) ((1.0F - progress) * MAX_FIREBALL_RADIUS));

            // Initialer weiß-gelber Detonations-Blitz
            if (progress < 0.2F) {
                fillDisc(graphics, cx, cy, (int) ((1.0F - progress / 0.2F) * 16.0F), 0xEEFFFFFF);
            }
            // Äußerer Explosions-Feuerball
            fillDisc(graphics, cx, cy, coreRadius, ((alpha * 3 / 4) << 24) | 0xFF3300);
            // Weiß-heißer Zentrum-Blitz
            fillDisc(graphics, cx, cy, Math.max(1, coreRadius / 2), (alpha << 24) | 0xFFFFAA);

            // Radiale Funken/Trümmerteile
            int sparkDist = (int) (progress * 26.0F);
            int sparkColor = ((int) ((1.0F - progress) * 240.0F) << 24) | 0xFF8800;
            for (int angle = 0; angle < 360; angle += 30) {
                double rad = Math.toRadians(angle);
                int sx = cx + (int) Math.round(Math.cos(rad) * sparkDist);
                int sy = cy + (int) Math.round(Math.sin(rad) * sparkDist);
                graphics.fill(sx - 1, sy - 1, sx + 2, sy + 2, sparkColor);
            }
        }

        /**
         * Gefüllte Kreisscheibe als Zeilenfüllung: eine Fläche je Bildzeile statt einer je Pixel.
         * <p>
         * Die getroffene Pixelmenge ist dieselbe wie bei der früheren Punktprüfung
         * {@code dx*dx + dy*dy <= r*r}, denn das größte zulässige {@code dx} einer Zeile ist genau
         * {@code floor(sqrt(r*r - dy*dy))}. Jedes {@code fill} legt intern eine eigene Matrix und
         * einen eigenen Renderstate an – ein Feuerball mit Radius 16 kostete so rund 800 davon je
         * Bild, jetzt sind es 33.
         */
        private static void fillDisc(GuiGraphicsExtractor graphics, int cx, int cy, int radius, int color) {
            if (radius < 0 || (color >>> 24) == 0) {
                return;
            }
            for (int dy = -radius; dy <= radius; dy++) {
                int dx = (int) Math.sqrt((double) radius * radius - (double) dy * dy);
                graphics.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
            }
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
            BomberCameraState state = BomberCameraState.INSTANCE;
            if (!state.isActive()) {
                return;
            }

            Minecraft client = Minecraft.getInstance();
            ClientLevel level = client.level;
            if (level == null) {
                return;
            }

            float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
            float transition = state.getTransition(partialTick);
            if (transition <= 0.001F) {
                return;
            }

            // Weiches Hineingleiten von rechts (Slide-In / Slide-Out)
            int finalX = graphics.guiWidth() - FRAME_WIDTH - MARGIN_RIGHT;
            int startX = graphics.guiWidth() + 10;
            int x = (int) Mth.lerp(transition, (float) startX, (float) finalX);
            int y = MARGIN_TOP;

            int viewX0 = x + VIEW_INSET_X;
            int viewY0 = y + VIEW_INSET_Y;
            int viewX1 = x + FRAME_WIDTH - VIEW_INSET_X;
            int viewY1 = y + FRAME_HEIGHT - VIEW_INSET_Y;

            // Kamera einmal je Bild aufbauen – 3D-Szene und Overlay teilen sich dieselbe Projektion.
            PROJECTOR.setup(
                    state.getInterpolatedBomberX(partialTick),
                    state.getInterpolatedBomberY(partialTick),
                    state.getInterpolatedBomberZ(partialTick),
                    NADIR_PITCH,
                    state.getInterpolatedHeadingDegrees(partialTick),
                    viewX0, viewY0, viewX1, viewY1, VIEW_SCALE
            );

            // Fallende Bomben einmal je Bild einsammeln, statt die Entity-Liste zweimal zu durchlaufen.
            collectBombs(level, partialTick);

            Player target = state.getTargetId() != null ? level.getPlayerByUUID(state.getTargetId()) : null;

            drawTacticalFrame(graphics, client.font, client, x, y);
            submitPipScene(graphics, state, level, target, viewX0, viewY0, viewX1, viewY1, partialTick);
            drawTacticalOverlay(graphics, client.font, state, x, y, viewX0, viewY0, viewX1, viewY1, partialTick);

            BOMBS.clear();
        }

        /**
         * Eine fallende Bombe mit bereits interpolierter Position.
         */
        private record BombView(Display.ItemDisplay display, double x, double y, double z) {
        }

        /**
         * Orthografische Projektion der Bauchkamera auf Monitorpixel.
         * <p>
         * Die PiP-Textur wird von {@code PictureInPictureRenderer#prepare} orthografisch aufgebaut,
         * deshalb genügt hier dieselbe Drehung plus ein linearer Maßstab. Drehung und Ursprung
         * entstehen einmal je Bild; früher legte jeder projizierte Punkt ein eigenes
         * {@code Quaternionf} und ein eigenes {@code Vector3f} an.
         */
        private static final class PipProjector {
            private final Quaternionf rotation = new Quaternionf();
            private final Vector3f scratch = new Vector3f();

            private double camX;
            private double camY;
            private double camZ;
            private float yawDeg;
            private int centerX;
            private int centerY;
            private float scale;

            private int screenX;
            private int screenY;

            void setup(
                    double camX, double camY, double camZ, float pitchDeg, float yawDeg,
                    int viewX0, int viewY0, int viewX1, int viewY1, float scale
            ) {
                this.camX = camX;
                this.camY = camY;
                this.camZ = camZ;
                this.yawDeg = yawDeg;
                this.centerX = (viewX0 + viewX1) / 2;
                this.centerY = (viewY0 + viewY1) / 2;
                this.scale = scale;
                this.rotation.identity()
                        .rotateZ((float) Math.PI)
                        .rotateX((float) -Math.toRadians(pitchDeg))
                        .rotateY((float) Math.toRadians(yawDeg));
            }

            /**
             * Projiziert Weltkoordinaten; das Ergebnis steht danach in {@link #screenX}/{@link #screenY}.
             */
            void project(double worldX, double worldY, double worldZ) {
                this.scratch.set((float) (worldX - this.camX), (float) (worldY - this.camY), (float) (worldZ - this.camZ));
                this.rotation.transform(this.scratch);
                this.screenX = Math.round(this.centerX + this.scratch.x * this.scale);
                this.screenY = Math.round(this.centerY + this.scratch.y * this.scale);
            }
        }

        /**
         * Zwischenspeicher für den Geländeausschnitt unter dem Bomber.
         * <p>
         * <p>Der Ausschnitt wurde vorher in jedem Bild neu abgetastet: 33x33 Säulen mal bis zu 23
         * Y-Ebenen, also bis zu rund 25.000 {@code getBlockState}-Abfragen je Bild. Bei 144 FPS
         * waren das Millionen je Sekunde, obwohl sich die Welt nur zwanzigmal je Sekunde ändert.
         * Der Scan läuft jetzt höchstens einmal je Tick und Ursprungsblock; frisch gerissene Krater
         * erscheinen dadurch unverändert im nächsten Tick.
         * <p>
         * <p>Statt jeder Säule pauschal drei Schichten mitzugeben, liefert
         * {@link Heightmap.Types#WORLD_SURFACE} – clientseitig gepflegt, siehe
         * {@link Heightmap.Usage#CLIENT} – direkt die Oberkante. Nach unten wird nur so weit
         * aufgefüllt, wie eine Nachbarsäule tiefer liegt: aus der Nadir-Perspektive ist alles
         * darunter ohnehin verdeckt. Auf ebenem Boden bleibt so eine Schicht statt drei übrig, und
         * Säulen ohne Blöcke – bei BO2 rund ein Drittel der umschließenden Box – fallen ganz weg.
         * <p>
         * <p>Die zurückgegebene Liste gehört dem Zwischenspeicher und wird beim nächsten Aufbau
         * wiederverwendet. Das ist zulässig, weil {@code GuiRenderer} alle PiP-Zustände noch im
         * selben Bild zeichnet und {@link BomberCameraPipRenderState} identitätsbasiert vergleicht,
         * die Liste eines vergangenen Bildes also nirgends mehr gelesen wird.
         */
        private static final class TerrainScan {
            private static final int SIZE = SCAN_RADIUS * 2 + 1;
            private static final int EMPTY = Integer.MIN_VALUE;

            private final List<BlockEntry> blocks = new ArrayList<>();
            private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            private final int[] columnTop = new int[SIZE * SIZE];

            private @Nullable ClientLevel level;
            private long builtAtGameTime = Long.MIN_VALUE;
            private int originX = EMPTY;
            private int originY = EMPTY;
            private int originZ = EMPTY;

            private @Nullable ChunkAccess cachedChunk;
            private int cachedChunkX;
            private int cachedChunkZ;
            private boolean cachedChunkValid;

            /**
             * Blöcke relativ zum ganzzahligen Ursprung; baut nur neu auf, wenn Tick oder Ursprung wechseln.
             */
            List<BlockEntry> get(ClientLevel level, int originX, int originY, int originZ) {
                long gameTime = level.getGameTime();
                if (this.level == level && this.builtAtGameTime == gameTime
                        && this.originX == originX && this.originY == originY && this.originZ == originZ) {
                    return this.blocks;
                }
                this.level = level;
                this.builtAtGameTime = gameTime;
                this.originX = originX;
                this.originY = originY;
                this.originZ = originZ;
                rebuild(level, originX, originY, originZ);
                return this.blocks;
            }

            private void rebuild(ClientLevel level, int originX, int originY, int originZ) {
                this.blocks.clear();
                this.cachedChunkValid = false;

                int maxY = originY + SCAN_ABOVE;
                int minY = originY - SCAN_BELOW;

                // 1. Oberkante jeder Säule bestimmen. Die Heightmap liefert die Obergrenze, darunter
                //    muss nur noch bis zum ersten nicht-leeren Block gesucht werden.
                for (int ix = 0; ix < SIZE; ix++) {
                    int worldX = originX - SCAN_RADIUS + ix;
                    for (int iz = 0; iz < SIZE; iz++) {
                        int worldZ = originZ - SCAN_RADIUS + iz;
                        ChunkAccess chunk = chunkAt(level, worldX, worldZ);
                        if (chunk == null) {
                            this.columnTop[ix * SIZE + iz] = EMPTY;
                            continue;
                        }
                        int surface = Math.min(chunk.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ), maxY);
                        this.columnTop[ix * SIZE + iz] = findSurface(chunk, worldX, worldZ, surface, minY);
                    }
                }

                // 2. Sichtbare Schichten ausgeben: die Oberkante und so viel Kantenfüllung, wie die
                //    tiefste Nachbarsäule verlangt.
                for (int ix = 0; ix < SIZE; ix++) {
                    int worldX = originX - SCAN_RADIUS + ix;
                    for (int iz = 0; iz < SIZE; iz++) {
                        int top = this.columnTop[ix * SIZE + iz];
                        if (top == EMPTY) {
                            continue;
                        }
                        int worldZ = originZ - SCAN_RADIUS + iz;
                        int lowestNeighbour = lowestNeighbour(ix, iz, top);

                        int bottom = lowestNeighbour == EMPTY ? minY : lowestNeighbour + 1;
                        bottom = Math.max(bottom, top - MAX_CLIFF_DEPTH + 1);
                        bottom = Math.max(bottom, minY);
                        bottom = Math.min(bottom, top);

                        emitColumn(level, worldX, worldZ, top, bottom, originX, originY, originZ);
                    }
                }
            }

            private int lowestNeighbour(int ix, int iz, int top) {
                int lowest = top;
                if (ix > 0) {
                    lowest = Math.min(lowest, this.columnTop[(ix - 1) * SIZE + iz]);
                }
                if (ix < SIZE - 1) {
                    lowest = Math.min(lowest, this.columnTop[(ix + 1) * SIZE + iz]);
                }
                if (iz > 0) {
                    lowest = Math.min(lowest, this.columnTop[ix * SIZE + iz - 1]);
                }
                if (iz < SIZE - 1) {
                    lowest = Math.min(lowest, this.columnTop[ix * SIZE + iz + 1]);
                }
                return lowest;
            }

            /**
             * Erste nicht-leere Ebene einer Säule, von {@code startY} abwärts.
             */
            private int findSurface(ChunkAccess chunk, int worldX, int worldZ, int startY, int minY) {
                for (int y = startY; y >= minY; y--) {
                    this.cursor.set(worldX, y, worldZ);
                    if (!chunk.getBlockState(this.cursor).isAir()) {
                        return y;
                    }
                }
                return EMPTY;
            }

            private void emitColumn(
                    ClientLevel level, int worldX, int worldZ, int top, int bottom,
                    int originX, int originY, int originZ
            ) {
                ChunkAccess chunk = chunkAt(level, worldX, worldZ);
                if (chunk == null) {
                    return;
                }
                for (int y = top; y >= bottom; y--) {
                    this.cursor.set(worldX, y, worldZ);
                    BlockState blockState = chunk.getBlockState(this.cursor);
                    if (blockState.isAir()) {
                        continue;
                    }
                    int light = LightCoordsUtil.max(
                            LightCoordsUtil.getLightCoords(
                                    LightCoordsUtil.BrightnessGetter.DEFAULT, level, blockState, this.cursor),
                            RECON_AMBIENT_LIGHT);
                    this.blocks.add(new BlockEntry(
                            worldX - originX, y - originY, worldZ - originZ, blockState, light));
                }
            }

            /**
             * Chunk-Suche einmal je Chunk statt einmal je Block.
             */
            private @Nullable ChunkAccess chunkAt(ClientLevel level, int worldX, int worldZ) {
                int chunkX = SectionPos.blockToSectionCoord(worldX);
                int chunkZ = SectionPos.blockToSectionCoord(worldZ);
                if (!this.cachedChunkValid || chunkX != this.cachedChunkX || chunkZ != this.cachedChunkZ) {
                    this.cachedChunk = level.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
                    this.cachedChunkX = chunkX;
                    this.cachedChunkZ = chunkZ;
                    this.cachedChunkValid = true;
                }
                return this.cachedChunk;
            }
        }
    }

    // =========================================================================
    // PiP 3D World & Terrain Subsystem
    // =========================================================================

    /**
     * Ein Block der PiP-Szene, verschoben gegen den Bezugspunkt seiner Liste.
     */
    public record BlockEntry(float relX, float relY, float relZ, BlockState state, int light) {
    }

    public record EntityRenderEntry(EntityRenderState state, float relX, float relY, float relZ, float scale) {
    }

    /**
     * Zustand der Bomber-Bauchkamera für einen Bildaufbau.
     * <p>
     * <p>{@code terrain} liegt relativ zu einem ganzzahligen Weltursprung und wird über
     * {@code terrainOffset} an die Kamera gerückt; {@code effects} und {@code entities} liegen
     * bereits relativ zur Kamera.
     * <p>
     * <p><b>{@code equals}/{@code hashCode} sind bewusst identitätsbasiert.</b> Die von
     * {@code record} erzeugte komponentenweise Fassung liefe über sämtliche Gelände- und
     * Entity-Einträge – ein teurer Vergleich für ein Ergebnis, das nie {@code true} sein kann:
     * Die Kamera liefert in jedem Bild eine neue Position und frische
     * {@link EntityRenderState}-Objekte. {@code GuiRenderer} sucht den passenden Renderer
     * ohnehin über {@code getClass()} und nicht über den Zustand selbst.
     */
    public record BomberCameraPipRenderState(
            List<EntityRenderEntry> entities,
            List<BlockEntry> terrain,
            Vector3fc terrainOffset,
            List<BlockEntry> effects,
            Quaternionfc rotation,
            @Nullable Quaternionfc overrideCameraAngle,
            int x0,
            int y0,
            int x1,
            int y1,
            float scale,
            @Nullable ScreenRectangle scissorArea,
            @Nullable ScreenRectangle bounds
    ) implements PictureInPictureRenderState {
        public BomberCameraPipRenderState(
                List<EntityRenderEntry> entities,
                List<BlockEntry> terrain,
                Vector3fc terrainOffset,
                List<BlockEntry> effects,
                Quaternionfc rotation,
                @Nullable Quaternionfc overrideCameraAngle,
                int x0,
                int y0,
                int x1,
                int y1,
                float scale,
                @Nullable ScreenRectangle scissorArea
        ) {
            this(
                    entities,
                    terrain,
                    terrainOffset,
                    effects,
                    rotation,
                    overrideCameraAngle,
                    x0,
                    y0,
                    x1,
                    y1,
                    scale,
                    scissorArea,
                    PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissorArea)
            );
        }

        @Override
        public boolean equals(Object other) {
            return this == other;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    public static final class BomberCameraPipRenderer extends PictureInPictureRenderer<BomberCameraPipRenderState> {
        private final EntityRenderDispatcher entityRenderDispatcher;
        private final BlockModelResolver blockModelResolver;
        private final BlockModelRenderState blockModelRenderState = new BlockModelRenderState();
        private final CameraRenderState cameraRenderState = new CameraRenderState();

        public BomberCameraPipRenderer(EntityRenderDispatcher entityRenderDispatcher, BlockModelResolver blockModelResolver) {
            this.entityRenderDispatcher = entityRenderDispatcher;
            this.blockModelResolver = blockModelResolver;
        }

        @Override
        public Class<BomberCameraPipRenderState> getRenderStateClass() {
            return BomberCameraPipRenderState.class;
        }

        @Override
        protected void renderToTexture(BomberCameraPipRenderState state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector) {
            Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
            poseStack.rotate(state.rotation());

            // 1. Gelände: der Versatz zwischen Scan-Ursprung und Kamera gilt für den ganzen Block
            //    und wandert einmal in den PoseStack statt in jeden Eintrag.
            Vector3fc terrainOffset = state.terrainOffset();
            poseStack.pushPose();
            poseStack.translate(terrainOffset.x(), terrainOffset.y(), terrainOffset.z());
            submitBlocks(state.terrain(), poseStack, submitNodeCollector);
            poseStack.popPose();

            // 2. Explosionsfeuer, bereits relativ zur Kamera
            submitBlocks(state.effects(), poseStack, submitNodeCollector);

            // 3. Ziel-Spieler und alle herabfallenden Bomben in 3D rendern
            // Der CameraRenderState wird über Bilder hinweg wiederverwendet; ohne den else-Zweig
            // bliebe beim Ausbleiben eines Winkels die Ausrichtung des vorherigen Bildes stehen.
            Quaternionfc overriddenCameraAngle = state.overrideCameraAngle();
            if (overriddenCameraAngle != null) {
                this.cameraRenderState.orientation = overriddenCameraAngle.conjugate(new Quaternionf()).rotateY((float) Math.PI);
            } else {
                this.cameraRenderState.orientation = new Quaternionf();
            }

            for (EntityRenderEntry entry : state.entities()) {
                poseStack.pushPose();
                poseStack.translate(entry.relX(), entry.relY(), entry.relZ());
                if (entry.scale() != 1.0F) {
                    poseStack.scale(entry.scale(), entry.scale(), entry.scale());
                }
                this.entityRenderDispatcher.submit(entry.state(), this.cameraRenderState, 0.0, 0.0, 0.0, poseStack, submitNodeCollector);
                poseStack.popPose();
            }
        }

        private void submitBlocks(List<BlockEntry> entries, PoseStack poseStack, SubmitNodeCollector submitNodeCollector) {
            for (BlockEntry block : entries) {
                this.blockModelResolver.update(this.blockModelRenderState, block.state(), DisplayRenderer.BLOCK_DISPLAY_CONTEXT);
                // Blöcke ohne sichtbares Modell gar nicht erst einreihen: BlockModelRenderState#submit
                // legt bei jedem Aufruf eine Kopie der Modellteil-Liste an.
                if (this.blockModelRenderState.isEmpty()) {
                    continue;
                }
                poseStack.pushPose();
                poseStack.translate(block.relX(), block.relY(), block.relZ());
                this.blockModelRenderState.submit(poseStack, submitNodeCollector, block.light(), OverlayTexture.NO_OVERLAY, 0);
                poseStack.popPose();
            }
        }

        @Override
        protected float getTranslateY(int height, int guiScale) {
            return height / 2.0F;
        }

        @Override
        protected String getTextureLabel() {
            return "bomber_camera";
        }
    }

    // =========================================================================
    // TiltedMinimapLayer.java (Taktisches 2D-Radar oben links auf Tilted Towers)
    // =========================================================================

    public static final class TiltedMinimapLayer implements HudElement {
/** So lange (Sekunden seit Start der Nuke-Sequenz) flackert die Minimap noch, bevor sie ganz verschwindet. */
        private static final float SIGNAL_LOSS_SECONDS = 1.1F;

        private static final double SCALE = 1.0;

        private static final int BORDER_CYAN = 0xFF00F0FF;
        private static final int BORDER_DIM = 0x5500F0FF;
        private static final int GRID_COLOR = 0x2200F0FF;
        private static final int RING_COLOR = 0x3300F0FF;
        private static final int PLAYER_COLOR = 0xFF00FF9D;
        private static final int ENEMY_COLOR = 0xFFFF2244;
        private static final int ENEMY_BELOW = 0xFFFF6633;
        private static final int BOX_COLOR = 0xFFFFC64B;

        private static void drawCircle(GuiGraphicsExtractor graphics, int cx, int cy, int radius, int color) {
            int points = 36;
            for (int i = 0; i < points; i++) {
                double a = i * (Math.PI * 2.0 / points);
                int px = cx + (int) Math.round(Math.cos(a) * radius);
                int py = cy + (int) Math.round(Math.sin(a) * radius);
                graphics.fill(px, py, px + 1, py + 1, color);
            }
        }

        private static void drawCompass(GuiGraphicsExtractor graphics, Font font, int cx, int cy, int radius, float yaw) {
            double yawRad = Math.toRadians(yaw);
            int textDist = radius - 8;
            drawCardinal(graphics, font, cx, cy, textDist, Math.PI - yawRad, "N", 0xFFFF3344);
            drawCardinal(graphics, font, cx, cy, textDist, 1.5 * Math.PI - yawRad, "O", 0xFF88CCEE);
            drawCardinal(graphics, font, cx, cy, textDist, -yawRad, "S", 0xFF88CCEE);
            drawCardinal(graphics, font, cx, cy, textDist, 0.5 * Math.PI - yawRad, "W", 0xFF88CCEE);
        }

        private static void drawCardinal(GuiGraphicsExtractor graphics, Font font, int cx, int cy, int dist, double angle, String label, int color) {
            int x = cx + (int) Math.round(dist * Math.sin(angle));
            int y = cy - (int) Math.round(dist * Math.cos(angle));
            graphics.centeredText(font, label, x, y - 4, color);
        }

        private static void drawBezel(GuiGraphicsExtractor graphics, int cx, int cy, int radius) {
            drawCircle(graphics, cx, cy, radius, BORDER_CYAN);
            drawCircle(graphics, cx, cy, radius + 1, BORDER_DIM);

            for (int i = 0; i < 12; i++) {
                double angle = i * (Math.PI / 6.0);
                int x1 = cx + (int) Math.round(radius * Math.sin(angle));
                int y1 = cy - (int) Math.round(radius * Math.cos(angle));
                int x2 = cx + (int) Math.round((radius + 2) * Math.sin(angle));
                int y2 = cy - (int) Math.round((radius + 2) * Math.cos(angle));
                graphics.fill(x1, y1, x2 + 1, y2 + 1, BORDER_CYAN);
            }
        }

        private static void drawPlayerMarker(GuiGraphicsExtractor graphics, int cx, int cy) {
            graphics.fill(cx, cy - 4, cx + 1, cy - 3, 0xFFFFFFFF);
            graphics.fill(cx - 1, cy - 3, cx + 2, cy - 1, PLAYER_COLOR);
            graphics.fill(cx - 2, cy - 1, cx + 3, cy + 1, PLAYER_COLOR);
            graphics.fill(cx - 3, cy + 1, cx + 4, cy + 3, PLAYER_COLOR);
            graphics.fill(cx - 1, cy + 1, cx + 2, cy + 3, 0xFF060910);
        }

        private static void drawContacts(GuiGraphicsExtractor graphics, Minecraft client, LocalPlayer player,
                                         MinimapState state, int cx, int cy, int radius, float partialTick) {
            double px = Mth.lerp(partialTick, player.xo, player.getX());
            double pz = Mth.lerp(partialTick, player.zo, player.getZ());
            double yawRad = Math.toRadians(player.getViewYRot(partialTick));
            double cos = Math.cos(yawRad);
            double sin = Math.sin(yawRad);

            for (MinimapState.EnemyContact contact : state.getContacts().values()) {
                Vec3 targetPos = contact.pos();
                double dx = targetPos.x - px;
                double dz = targetPos.z - pz;
                double dy = contact.dy();

                double sx = cx + (-dx * cos - dz * sin) * SCALE;
                double sy = cy + (dx * sin - dz * cos) * SCALE;

                double distSq = (sx - cx) * (sx - cx) + (sy - cy) * (sy - cy);
                int maxR = radius - 5;
                if (distSq > maxR * maxR) {
                    double len = Math.sqrt(distSq);
                    sx = cx + (sx - cx) / len * maxR;
                    sy = cy + (sy - cy) / len * maxR;
                }

                int ix = (int) Math.round(sx);
                int iy = (int) Math.round(sy);

                if (contact.shooting()) {
                    int ripple = (int) ((player.tickCount % 10) * 0.7);
                    drawCircle(graphics, ix, iy, 3 + ripple, 0x88FF2244);
                }

                if (dy > 4.5) {
                    drawUpChevron(graphics, ix, iy, ENEMY_COLOR);
                } else if (dy < -4.5) {
                    drawDownChevron(graphics, ix, iy, ENEMY_BELOW);
                } else {
                    drawContactDot(graphics, ix, iy, ENEMY_COLOR);
                }
            }
        }

        private static void drawUpChevron(GuiGraphicsExtractor graphics, int x, int y, int color) {
            graphics.fill(x, y - 3, x + 1, y - 2, 0xFFFFFFFF);
            graphics.fill(x - 1, y - 2, x + 2, y - 1, color);
            graphics.fill(x - 2, y - 1, x + 3, y, color);
            graphics.fill(x - 3, y, x + 4, y + 2, color);
        }

        private static void drawDownChevron(GuiGraphicsExtractor graphics, int x, int y, int color) {
            graphics.fill(x - 3, y - 1, x + 4, y + 1, color);
            graphics.fill(x - 2, y + 1, x + 3, y + 2, color);
            graphics.fill(x - 1, y + 2, x + 2, y + 3, color);
            graphics.fill(x, y + 3, x + 1, y + 4, 0xFFFFFFFF);
        }

        private static void drawContactDot(GuiGraphicsExtractor graphics, int x, int y, int color) {
            graphics.fill(x - 2, y - 1, x + 3, y + 2, color);
            graphics.fill(x - 1, y - 2, x + 2, y - 1, color);
            graphics.fill(x, y, x + 1, y + 1, 0xFFFFFFFF);
        }

        private static void drawWorldObjects(GuiGraphicsExtractor graphics, Minecraft client, LocalPlayer player,
                                             int cx, int cy, int radius, float partialTick) {
            double px = Mth.lerp(partialTick, player.xo, player.getX());
            double pz = Mth.lerp(partialTick, player.zo, player.getZ());
            double yawRad = Math.toRadians(player.getViewYRot(partialTick));
            double cos = Math.cos(yawRad);
            double sin = Math.sin(yawRad);

            if (client.level != null) {
                for (Entity entity : client.level.entitiesForRendering()) {
                    if (entity instanceof Display.ItemDisplay box && Hologram.item(box).is(ModItems.ITEM_BOX)) {
                        Vec3 pos = box.position();
                        double dx = pos.x - px;
                        double dz = pos.z - pz;
                        double sx = cx + (-dx * cos - dz * sin) * SCALE;
                        double sy = cy + (dx * sin - dz * cos) * SCALE;
                        double distSq = (sx - cx) * (sx - cx) + (sy - cy) * (sy - cy);
                        if (distSq <= (radius - 4) * (radius - 4)) {
                            int bx = (int) Math.round(sx);
                            int by = (int) Math.round(sy);
                            graphics.fill(bx, by - 2, bx + 1, by + 3, BOX_COLOR);
                            graphics.fill(bx - 2, by, bx + 3, by + 1, BOX_COLOR);
                            graphics.fill(bx, by, bx + 1, by + 1, 0xFFFFFFFF);
                        }
                    }
                }
            }

            Vec3 hookPos = GrapplePullState.INSTANCE.hookPosition(player.getUUID(), partialTick);
            if (hookPos != null) {
                double dx = hookPos.x - px;
                double dz = hookPos.z - pz;
                double sx = cx + (-dx * cos - dz * sin) * SCALE;
                double sy = cy + (dx * sin - dz * cos) * SCALE;
                double distSq = (sx - cx) * (sx - cx) + (sy - cy) * (sy - cy);
                int hx = (int) Math.round(sx);
                int hy = (int) Math.round(sy);
                if (distSq <= (radius - 2) * (radius - 2)) {
                    graphics.fill(hx - 2, hy, hx + 3, hy + 1, 0xFF00F0FF);
                    graphics.fill(hx, hy - 2, hx + 1, hy + 3, 0xFF00F0FF);
                }
            }
        }

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
            Minecraft client = Minecraft.getInstance();
            LocalPlayer player = client.player;
            if (player == null || client.level == null) {
                return;
            }
            if (!com.oneshotonekill.arena.Arena.TILTED_TOWERS.getDimension().equals(client.level.dimension())) {
                return;
            }

            MinimapState state = MinimapState.INSTANCE;
            if (!state.isMatchRunning()
                    || MatchStartState.INSTANCE.isCountdownActive()
                    || !com.oneshotonekill.arena.Arena.TILTED_TOWERS.isInArenaColumn(player.getX(), player.getZ())) {
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

            double playerWorldX = Mth.lerp(partialTick, player.xo, player.getX());
            double playerWorldZ = Mth.lerp(partialTick, player.zo, player.getZ());
            float yaw = player.getViewYRot(partialTick);

            // 1. Rotierende, kreisförmige Geländekarte aktualisieren & zeichnen
            state.updateRadarView(playerWorldX, playerWorldZ, yaw);
            if (state.isRadarViewReady()) {
                int diameter = radius * 2;
                graphics.blit(RenderPipelines.GUI_TEXTURED, MinimapState.RADAR_VIEW_ID,
                        cx - radius, cy - radius, 0, 0, diameter, diameter,
                        MinimapState.RADAR_TEX_SIZE, MinimapState.RADAR_TEX_SIZE,
                        MinimapState.RADAR_TEX_SIZE, MinimapState.RADAR_TEX_SIZE);
            }

            // 2. Polar-Gitter & Distanzringe
            drawCircle(graphics, cx, cy, (int) (radius * 0.5), RING_COLOR);
            drawCircle(graphics, cx, cy, radius, BORDER_DIM);
            graphics.horizontalLine(cx - radius + 4, cx + radius - 4, cy, GRID_COLOR);
            graphics.verticalLine(cx, cy - radius + 4, cy + radius - 4, GRID_COLOR);

            // 3. Rotierende Kompass-Markierungen (N, O, S, W)
            drawCompass(graphics, client.font, cx, cy, radius, yaw);

            // 4. Radar-Rahmen & Ticks
            drawBezel(graphics, cx, cy, radius);

            // 5. Kisten, Grappler-Anker
            drawWorldObjects(graphics, client, player, cx, cy, radius, partialTick);

            // 6. Gegner-Kontakte (mit Vertikalitäts-Höhenpfeilen ▲ / ▼ / ●)
            drawContacts(graphics, client, player, state, cx, cy, radius, partialTick);

            // 7. Eigener Spielerpfeil (Mitte)
            drawPlayerMarker(graphics, cx, cy);

            // 8. Signalverlust beim Start des Endgames
            if (lostSeconds >= 0.0F) {
                drawSignalLoss(graphics, client.font, cx, cy, radius, lostSeconds / SIGNAL_LOSS_SECONDS);
            }
        }

        /**
         * Die Karte stirbt: Zeilenversatz wie bei einem defekten Bildsignal, Rauschbalken, dann wird die Scheibe
         * schwarz und "KEIN SIGNAL" blinkt, bevor sie verschwindet.
         */
        private static void drawSignalLoss(GuiGraphicsExtractor graphics, Font font, int cx, int cy, int radius, float progress) {
            float k = Math.min(1.0F, progress);
            int frame = (int) (net.minecraft.util.Util.getMillis() / 45L);
            int top = cy - radius;
            for (int i = 0; i < 16; i++) {
                float row = HudFx.rand(frame * 31 + i * 7);
                int y = top + Math.round(row * radius * 2);
                int h = 1 + Math.round(HudFx.rand(frame * 17 + i) * 4.0F);
                int shift = Math.round((HudFx.rand(frame * 13 + i * 3) - 0.5F) * 22.0F * k);
                int colour = HudFx.rand(frame + i * 5) > 0.5F ? 0xFFFFFF : 0x0A0F14;
                graphics.fill(cx - radius + shift, y, cx + radius + shift, y + h, HudFx.argb(colour, 0.35F + 0.4F * k));
            }
            HudFx.disc(graphics, cx, cy, radius, HudFx.argb(0x05080A, k * k * 0.95F));
            if (k > 0.4F && ((frame / 4) & 1) == 0) {
                HudFx.smallText(graphics, font, "KEIN SIGNAL", cx, cy - 4, 0.9F, HudFx.argb(0xFF3C28, 0.95F));
            }
        }
    }
}
