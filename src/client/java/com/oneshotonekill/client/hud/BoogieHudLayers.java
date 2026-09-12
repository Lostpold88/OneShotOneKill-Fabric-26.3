package com.oneshotonekill.client.hud;

import com.oneshotonekill.client.effect.BoogieBombClient;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.ARGB;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * Screen-Overlay für die Boogie-Granate:
 * 1. Weiche beat-synchron pulsierende Neon-Vignette an den Bildschirmrändern.
 * 2. Zarte schwebende Disco-Glitzersterne und Musiknoten.
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
            {0.52F, 0.80F, 0.11F, 1.5F}
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

            Vector3f discoColor = BoogieBombClient.getDiscoColor(sec);
            int r = Math.clamp((int) (discoColor.x * 255.0F), 0, 255);
            int g = Math.clamp((int) (discoColor.y * 255.0F), 0, 255);
            int b = Math.clamp((int) (discoColor.z * 255.0F), 0, 255);

            // =========================================================================
            // 1. Atmende Rand-Vignette im Disco-Farbverlauf
            // =========================================================================
            float beatPulse = Math.max(0.0F, (float) Math.sin(beat * Math.PI * 2.0));
            int alphaEdge = (int) ((45 + 35 * beatPulse) * weight);

            int topH = Math.max(18, height / 5);
            graphics.fillGradient(0, 0, width, topH, ARGB.color(alphaEdge, r, g, b), ARGB.color(0, r, g, b));
            graphics.fillGradient(0, height - topH, width, height, ARGB.color(0, r, g, b), ARGB.color(alphaEdge, r, g, b));

            int sideW = Math.max(18, width / 6);
            int steps = 6;
            for (int i = 0; i < steps; i++) {
                float f = (float) (steps - i) / steps;
                int stepAlpha = (int) (alphaEdge * f * f * 0.65F);
                int stepCol = ARGB.color(stepAlpha, r, g, b);
                int xL0 = i * (sideW / steps);
                int xL1 = (i + 1) * (sideW / steps);
                graphics.fill(xL0, topH, xL1, height - topH, stepCol);
                int xR0 = width - (i + 1) * (sideW / steps);
                int xR1 = width - i * (sideW / steps);
                graphics.fill(xR0, topH, xR1, height - topH, stepCol);
            }

            // =========================================================================
            // 2. Schwebende Disco-Glitzersterne & Musiknoten
            // =========================================================================
            for (int i = 0; i < SPARKLES.length; i++) {
                float[] sp = SPARKLES[i];
                float yFrac = (sp[1] - sec * sp[2]) % 1.0F;
                if (yFrac < 0.0F) yFrac += 1.0F;

                float xFrac = sp[0] + (float) Math.sin(sec * 1.8F + sp[3]) * 0.025F;
                int px = (int) (xFrac * width);
                int py = (int) (yFrac * height);

                float tw = 0.5F + 0.5F * (float) Math.sin(sec * 7.5F + sp[3]);
                int starAlpha = (int) (tw * 230.0F * weight);
                if (starAlpha <= 15) continue;

                int colorIdx = ((int) sp[3]) % 3;
                int starColor = switch (colorIdx) {
                    case 0 -> ARGB.color(starAlpha, 255, 225, 90);   // Disco-Gold
                    case 1 -> ARGB.color(starAlpha, 80, 240, 255);   // Electric-Cyan
                    default -> ARGB.color(starAlpha, 255, 100, 220); // Hot-Pink
                };

                // Noten für 3 Partikel, Diamantsterne für alle übrigen
                if (i == 4 || i == 11 || i == 16) {
                    String note = (i == 11) ? "♫" : "♪";
                    graphics.text(client.font, note, px, py, starColor, true);
                } else {
                    graphics.fill(px - 1, py, px + 2, py + 1, starColor);
                    graphics.fill(px, py - 1, px + 1, py + 2, starColor);
                    if (tw > 0.82F) {
                        int coreAlpha = starAlpha / 2;
                        graphics.fill(px - 2, py, px + 3, py + 1, ARGB.color(coreAlpha, 255, 255, 255));
                        graphics.fill(px, py - 2, px + 1, py + 3, ARGB.color(coreAlpha, 255, 255, 255));
                    }
                }
            }
        }
    }
}
