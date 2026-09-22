package com.oneshotonekill.client.hud;

import com.oneshotonekill.client.effect.BoogieBombClient;
import com.oneshotonekill.client.effect.TimeDistortionEffects;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.ARGB;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * Intensives Disco-Screen-Overlay für die Boogie-Granate:
 * 1. Verstärkte beat-synchron pulsierende Neon-Vignette & Ambient-Beat-Flash auf Kickdrums.
 * 2. Funkelnde Diamant-Sterne und Musiknoten (♪, ♫, ♬).
 * 3. Vollständige Zeitverzerrer-Integration: Chrono-Farbverschiebung und temporale Interferenz-Ripples bei aktivem Zeitbruch.
 */
@SuppressWarnings("NullableProblems")
public final class BoogieHudLayers {
    private BoogieHudLayers() {}

    public static final class ScreenFxLayer implements HudElement {
        private static final float[][] SPARKLES = {
            {0.10F, 0.75F, 0.07F, 1.2F},
            {0.22F, 0.40F, 0.11F, 3.5F},
            {0.35F, 0.85F, 0.09F, 0.7F},
            {0.48F, 0.25F, 0.13F, 4.8F},
            {0.62F, 0.70F, 0.08F, 2.3F},
            {0.78F, 0.35F, 0.12F, 5.1F},
            {0.88F, 0.80F, 0.10F, 3.9F},
            {0.15F, 0.20F, 0.14F, 6.2F},
            {0.28F, 0.60F, 0.09F, 1.8F},
            {0.42F, 0.95F, 0.10F, 4.1F},
            {0.55F, 0.45F, 0.12F, 0.4F},
            {0.70F, 0.90F, 0.08F, 2.9F},
            {0.82F, 0.55F, 0.11F, 5.7F},
            {0.93F, 0.30F, 0.13F, 3.3F},
            {0.05F, 0.50F, 0.09F, 4.4F},
            {0.20F, 0.90F, 0.10F, 2.1F},
            {0.65F, 0.15F, 0.15F, 0.9F},
            {0.75F, 0.75F, 0.08F, 3.6F},
            {0.38F, 0.35F, 0.12F, 5.3F},
            {0.52F, 0.80F, 0.11F, 1.5F},
            {0.12F, 0.35F, 0.10F, 2.7F},
            {0.25F, 0.15F, 0.14F, 4.3F},
            {0.45F, 0.65F, 0.08F, 0.9F},
            {0.58F, 0.20F, 0.12F, 3.8F},
            {0.85F, 0.18F, 0.13F, 5.5F},
            {0.92F, 0.65F, 0.09F, 1.6F}
        };

        @Override
        public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null || !BoogieBombClient.isLocalDancing()) {
                return;
            }

            UUID id = client.player.getUUID();
            float weight = BoogieBombClient.getWeight(id);
            if (weight <= 0.001F) {
                return;
            }

            int width = graphics.guiWidth();
            int height = graphics.guiHeight();
            float sec = BoogieBombClient.seconds(id);
            float beat = sec * 2.0F;

            // Zeitverzerrer-Modulation
            float chrono = TimeDistortionEffects.INSTANCE.modelActivePower();

            Vector3f discoColor = BoogieBombClient.getDiscoColor(sec);
            float rF = discoColor.x;
            float gF = discoColor.y;
            float bF = discoColor.z;

            // Chrono-Farbverschiebung bei aktivem Zeitverzerrer
            if (chrono > 0.001F) {
                float chronoCycle = 0.5F + 0.5F * (float) Math.sin(sec * 2.4F);
                float chrR = 0.60F * (1.0F - chronoCycle) + 0.05F * chronoCycle;
                float chrG = 0.15F * (1.0F - chronoCycle) + 0.90F * chronoCycle;
                float chrB = 1.0F;
                rF = rF * (1.0F - chrono) + chrR * chrono;
                gF = gF * (1.0F - chrono) + chrG * chrono;
                bF = bF * (1.0F - chrono) + chrB * chrono;
            }

            int r = Math.clamp((int) (rF * 255.0F), 0, 255);
            int g = Math.clamp((int) (gF * 255.0F), 0, 255);
            int b = Math.clamp((int) (bF * 255.0F), 0, 255);

            float kick = (float) Math.pow(Math.max(0.0F, Math.sin(beat * Math.PI)), 4.0);
            float beatPulse = Math.max(0.0F, (float) Math.sin(beat * Math.PI * 2.0));

            // =========================================================================
            // 1. Ambient Beat Flash (Club-Stroboskop-Atmosphäre auf Kickdrums)
            // =========================================================================
            int flashAlpha = (int) ((10 + 26 * kick) * weight);
            if (flashAlpha > 0) {
                graphics.fill(0, 0, width, height, ARGB.color(flashAlpha, r, g, b));
            }

            // =========================================================================
            // 2. Verstärkte Beat-synchron pulsierende Neon-Vignette
            // =========================================================================
            int alphaEdge = (int) ((95 + 65 * beatPulse) * weight);
            int topH = Math.max(28, height / 4);
            graphics.fillGradient(0, 0, width, topH, ARGB.color(alphaEdge, r, g, b), ARGB.color(0, r, g, b));
            graphics.fillGradient(0, height - topH, width, height, ARGB.color(0, r, g, b), ARGB.color(alphaEdge, r, g, b));

            int sideW = Math.max(24, width / 5);
            int stepSize = Math.max(1, sideW / 128);
            for (int x = 0; x < sideW; x += stepSize) {
                int xNext = Math.min(x + stepSize, sideW);
                float f = 1.0F - (float) (x + xNext) / (2.0F * sideW);
                int stepAlpha = (int) (alphaEdge * f * f * 0.70F);
                if (stepAlpha <= 0) {
                    continue;
                }
                int stepCol = ARGB.color(stepAlpha, r, g, b);
                graphics.fill(x, 0, xNext, height, stepCol);
                graphics.fill(width - xNext, 0, width - x, height, stepCol);
            }

            // =========================================================================
            // 3. Schwebende Disco-Glitzersterne & Musiknoten
            // =========================================================================
            renderSparklesAndNotes(graphics, client, width, height, sec, weight, chrono);

            // =========================================================================
            // 4. Temporale Interferenz-Ripples bei aktivem Zeitverzerrer
            // =========================================================================
            if (chrono > 0.001F) {
                renderChronoRipples(graphics, width, height, sec, weight, chrono);
            }
        }

        /** Zeichnet schwebende Glitzersterne und Musiknoten. */
        private static void renderSparklesAndNotes(GuiGraphicsExtractor graphics, Minecraft client,
                                                   int width, int height, float sec, float weight, float chrono) {
            for (int i = 0; i < SPARKLES.length; i++) {
                renderSingleSparkle(graphics, client, width, height, sec, weight, chrono, i, SPARKLES[i]);
            }
        }

        private static void renderSingleSparkle(GuiGraphicsExtractor graphics, Minecraft client,
                                                int width, int height, float sec, float weight, float chrono,
                                                int i, float[] sp) {
            float yFrac = (sp[1] - sec * sp[2]) % 1.0F;
            if (yFrac < 0.0F) yFrac += 1.0F;

            float xFrac = sp[0] + (float) Math.sin(sec * 1.8F + sp[3]) * 0.025F;
            int px = (int) (xFrac * width);
            int py = (int) (yFrac * height);

            float tw = 0.5F + 0.5F * (float) Math.sin(sec * 7.5F + sp[3]);
            int starAlpha = (int) (tw * 240.0F * weight);
            if (starAlpha <= 15) return;

            int starColor = getStarColor(((int) sp[3]) % 4, starAlpha, chrono);

            // Musiknoten für ausgewählte Partikel, Diamantsterne für alle übrigen
            if (i == 4 || i == 11 || i == 16 || i == 22) {
                String note = (i == 11) ? "♫" : (i == 22 ? "♬" : "♪");
                graphics.text(client.font, note, px, py, starColor, true);
            } else {
                graphics.fill(px - 1, py, px + 2, py + 1, starColor);
                graphics.fill(px, py - 1, px + 1, py + 2, starColor);
                if (tw > 0.72F) {
                    int coreAlpha = starAlpha / 2;
                    graphics.fill(px - 2, py, px + 3, py + 1, ARGB.color(coreAlpha, 255, 255, 255));
                    graphics.fill(px, py - 2, px + 1, py + 3, ARGB.color(coreAlpha, 255, 255, 255));
                }
            }
        }

        /** Zeichnet temporale Interferenz-Ripples bei aktivem Zeitverzerrer. */
        private static void renderChronoRipples(GuiGraphicsExtractor graphics, int width, int height,
                                                float sec, float weight, float chrono) {
            int ripY1 = (int) ((sec * 85.0F) % height);
            int ripY2 = (ripY1 + height / 2) % height;
            int ripAlpha1 = (int) (38 * chrono * weight);
            int ripAlpha2 = (int) (32 * chrono * weight);
            graphics.fill(0, ripY1, width, ripY1 + 2, ARGB.color(ripAlpha1, 0, 240, 255));
            graphics.fill(0, ripY2, width, ripY2 + 2, ARGB.color(ripAlpha2, 210, 50, 255));
        }

        private static int getStarColor(int colorIdx, int starAlpha, float chrono) {
            if (chrono > 0.001F) {
                return switch (colorIdx) {
                    case 0 -> ARGB.color(starAlpha, 0, 245, 255);    // Chrono Cyan
                    case 1 -> ARGB.color(starAlpha, 180, 70, 255);   // Temporal Violet
                    case 2 -> ARGB.color(starAlpha, 255, 90, 220);   // Neon Magenta
                    default -> ARGB.color(starAlpha, 255, 255, 255); // White Spark
                };
            }
            return switch (colorIdx) {
                case 0 -> ARGB.color(starAlpha, 255, 225, 90);   // Disco-Gold
                case 1 -> ARGB.color(starAlpha, 80, 240, 255);   // Electric-Cyan
                case 2 -> ARGB.color(starAlpha, 255, 100, 220);  // Hot-Pink
                default -> ARGB.color(starAlpha, 70, 255, 150);  // Mint-Green
            };
        }
    }
}
