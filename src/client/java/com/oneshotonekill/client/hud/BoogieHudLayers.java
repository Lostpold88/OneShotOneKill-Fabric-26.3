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
 * 1. Retro-Audio-Visualizer (Symmetrische Stereo-Equalizer-Bars mit LED-Segmenten am Bildschirmrand).
 * 2. Verstärkte beat-synchron pulsierende Neon-Vignette & Ambient-Beat-Flash auf Kickdrums.
 * 3. Diagonale Club-Laserstrahlen (Electric Cyan & Disco Magenta), die über den Bildschirm fegen.
 * 4. Funkelnde Diamant-Sterne und Musiknoten (♪, ♫, ♬).
 * 5. Vollständige Zeitverzerrer-Integration: Chrono-Farbverschiebung, chromatische Geisterbalken
 *    und temporale Interferenz-Ripples bei aktivem Zeitbruch.
 */
@SuppressWarnings("NullableProblems")
public final class BoogieHudLayers {
    private BoogieHudLayers() {}

    public static final class ScreenFxLayer implements HudElement {
        private static final int BARS_PER_CHANNEL = 14;
        private static final int BAR_WIDTH = 4;
        private static final int BAR_GAP = 2;
        private static final int TOTAL_EQ_WIDTH = BARS_PER_CHANNEL * (BAR_WIDTH + BAR_GAP) - BAR_GAP; // 82 px
        private static final int MAX_EQ_HEIGHT = 46;
        private static final int NUM_SEGMENTS = 15;

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
            float beat = sec * 2.0F; // 120 BPM Grundraster

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
            int steps = 8;
            for (int i = 0; i < steps; i++) {
                float f = (float) (steps - i) / steps;
                int stepAlpha = (int) (alphaEdge * f * f * 0.70F);
                int stepCol = ARGB.color(stepAlpha, r, g, b);
                int xL0 = i * (sideW / steps);
                int xL1 = (i + 1) * (sideW / steps);
                graphics.fill(xL0, topH, xL1, height - topH, stepCol);
                int xR0 = width - (i + 1) * (sideW / steps);
                int xR1 = width - i * (sideW / steps);
                graphics.fill(xR0, topH, xR1, height - topH, stepCol);
            }

            // =========================================================================
            // 3. Fegende diagonale Club-Laserstrahlen
            // =========================================================================
            renderClubLasers(graphics, width, height, sec, kick, weight, chrono);

            // =========================================================================
            // 4. Retro-Audio-Visualizer (Stereo-Equalizer-Bars am Bildschirmrand)
            // =========================================================================
            renderAudioVisualizer(graphics, client, width, height, beat, kick, weight, chrono, r, g, b);

            // =========================================================================
            // 5. Schwebende Disco-Glitzersterne & Musiknoten
            // =========================================================================
            renderSparklesAndNotes(graphics, client, width, height, sec, weight, chrono);

            // =========================================================================
            // 6. Temporale Interferenz-Ripples bei aktivem Zeitverzerrer
            // =========================================================================
            if (chrono > 0.001F) {
                renderChronoRipples(graphics, width, height, sec, weight, chrono);
            }
        }

        /** Zeichnet fegende Club-Laserstrahlen über den Bildschirm. */
        private static void renderClubLasers(GuiGraphicsExtractor graphics, int width, int height,
                                             float sec, float kick, float weight, float chrono) {
            float laserAlphaFactor = (0.55F + 0.45F * kick) * weight;

            // Laser 1: Cyan-Laser
            float xTop1 = width * (0.20F + 0.60F * (0.5F + 0.5F * (float) Math.sin(sec * 1.3F)));
            float xBot1 = width * (0.80F - 0.60F * (0.5F + 0.5F * (float) Math.cos(sec * 1.1F)));
            int colCyan = ARGB.color((int) (160 * laserAlphaFactor), 0, 240, 255);
            int colCyanBloom = ARGB.color((int) (35 * laserAlphaFactor), 0, 240, 255);
            drawLaserBeam(graphics, xTop1, xBot1, height, colCyan, colCyanBloom);

            // Laser 2: Magenta-Laser
            float xTop2 = width * (0.80F - 0.60F * (0.5F + 0.5F * (float) Math.sin(sec * 0.9F + 1.4F)));
            float xBot2 = width * (0.20F + 0.60F * (0.5F + 0.5F * (float) Math.cos(sec * 1.4F + 2.0F)));
            int colMag = ARGB.color((int) (160 * laserAlphaFactor), 255, 30, 180);
            int colMagBloom = ARGB.color((int) (35 * laserAlphaFactor), 255, 30, 180);
            drawLaserBeam(graphics, xTop2, xBot2, height, colMag, colMagBloom);

            // Chromatische Geisterstrahlen bei Zeitverzerrung
            if (chrono > 0.001F) {
                int ghostAlpha = (int) (55 * chrono * laserAlphaFactor);
                int colGhostRed = ARGB.color(ghostAlpha, 255, 20, 80);
                int colGhostCyan = ARGB.color(ghostAlpha, 0, 255, 255);
                drawLaserBeam(graphics, xTop1 - 4, xBot1 - 4, height, colGhostRed, 0);
                drawLaserBeam(graphics, xTop2 + 4, xBot2 + 4, height, colGhostCyan, 0);
            }
        }

        private static void drawLaserBeam(GuiGraphicsExtractor graphics, float xTop, float xBot,
                                          int height, int coreColor, int bloomColor) {
            int step = 3;
            for (int y = 0; y < height; y += step) {
                float t = (float) y / height;
                int cx = Math.round(xTop + (xBot - xTop) * t);
                if (bloomColor != 0) {
                    graphics.fill(cx - 4, y, cx + 5, y + step, bloomColor);
                }
                graphics.fill(cx - 1, y, cx + 2, y + step, coreColor);
            }
        }

        /** Zeichnet den symmetrischen 14-Band Retro Stereo-Equalizer an den Bildschirmrändern. */
        private static void renderAudioVisualizer(GuiGraphicsExtractor graphics, Minecraft client,
                                                  int width, int height, float beat,
                                                  float kick, float weight, float chrono,
                                                  int themeR, int themeG, int themeB) {
            int baseY = height - 12;
            int leftStartX = 12;
            int rightStartX = width - 12 - TOTAL_EQ_WIDTH;

            // 1. Dunkle Acrylglas-Trägerplatten mit Neon-Header-Linie
            int bgAlpha = (int) (135 * weight);
            int headerCol = ARGB.color((int) (200 * weight), themeR, themeG, themeB);

            // Linke Trägerplatte
            graphics.fill(leftStartX - 4, baseY - MAX_EQ_HEIGHT - 10, leftStartX + TOTAL_EQ_WIDTH + 4, baseY + 2,
                    ARGB.color(bgAlpha, 12, 10, 22));
            graphics.fill(leftStartX - 4, baseY - MAX_EQ_HEIGHT - 10, leftStartX + TOTAL_EQ_WIDTH + 4, baseY - MAX_EQ_HEIGHT - 9,
                    headerCol);

            // Rechte Trägerplatte
            graphics.fill(rightStartX - 4, baseY - MAX_EQ_HEIGHT - 10, rightStartX + TOTAL_EQ_WIDTH + 4, baseY + 2,
                    ARGB.color(bgAlpha, 12, 10, 22));
            graphics.fill(rightStartX - 4, baseY - MAX_EQ_HEIGHT - 10, rightStartX + TOTAL_EQ_WIDTH + 4, baseY - MAX_EQ_HEIGHT - 9,
                    headerCol);

            // 2. Channel-Status-Text (Retro Hi-Fi Style)
            int labelCol = ARGB.color((int) (190 * weight), themeR, themeG, themeB);
            String leftLabel = (chrono > 0.001F) ? "CH-L [CHRONO]" : "CH-L [128 BPM]";
            String rightLabel = (chrono > 0.001F) ? "[SLOW-MO] CH-R" : "[DISCO EQ] CH-R";
            graphics.text(client.font, leftLabel, leftStartX - 1, baseY - MAX_EQ_HEIGHT - 7, labelCol, false);
            graphics.text(client.font, rightLabel, rightStartX + TOTAL_EQ_WIDTH - client.font.width(rightLabel) + 1,
                    baseY - MAX_EQ_HEIGHT - 7, labelCol, false);

            // 3. Frequenz-Balken für linken und rechten Kanal berechnen und rendern
            for (int i = 0; i < BARS_PER_CHANNEL; i++) {
                // Symmetrische Frequenzverteilung: Sub-Bass am Rand (i = 0), Höhen zur Bildschirmmitte
                float freqL = calculateFrequencyMagnitude(i, beat, kick, false);
                float freqR = calculateFrequencyMagnitude(i, beat, kick, true);

                int barXL = leftStartX + i * (BAR_WIDTH + BAR_GAP);
                int barXR = rightStartX + (BARS_PER_CHANNEL - 1 - i) * (BAR_WIDTH + BAR_GAP);

                renderSingleEqBar(graphics, barXL, baseY, freqL, weight, chrono);
                renderSingleEqBar(graphics, barXR, baseY, freqR, weight, chrono);
            }
        }

        private static float calculateFrequencyMagnitude(int band, float beat, float kick, boolean isRight) {
            float phase = isRight ? 0.40F : 0.0F;
            float val;
            if (band <= 1) {
                // Sub-Bass & 808 Kick
                val = 0.85F * kick + 0.15F * (float) Math.abs(Math.sin(beat * Math.PI * 2.0 + phase));
            } else if (band <= 3) {
                // Mid-Bass
                float kickP = (float) Math.pow(Math.max(0.0F, Math.sin(beat * Math.PI + phase)), 3.0);
                val = 0.70F * kickP + 0.30F * (float) Math.abs(Math.sin(beat * Math.PI + band * 0.4F));
            } else if (band <= 6) {
                // Snare & Low-Mids
                float snare = (float) Math.pow(Math.max(0.0F, Math.sin((beat + 0.5F) * Math.PI + phase)), 3.5);
                val = 0.55F * snare + 0.35F * (float) Math.abs(Math.sin(beat * Math.PI * 1.5F + band * 0.7F)) + 0.10F;
            } else if (band <= 9) {
                // Disco Synth & Melodic Chords
                val = 0.25F + 0.65F * (float) Math.abs(Math.sin(beat * Math.PI * 2.2F + band * 1.1F + phase));
            } else if (band <= 11) {
                // Hi-Hats & Percussion
                float hat = (float) Math.pow(Math.max(0.0F, Math.sin(beat * Math.PI * 4.0 + band * 0.8F + phase)), 2.0);
                val = 0.20F + 0.70F * hat;
            } else {
                // High Air & Sizzle
                val = 0.20F + 0.65F * (float) Math.abs(Math.sin(beat * Math.PI * 6.0 + band * 1.5F + phase));
            }
            return Math.clamp(val, 0.06F, 1.0F);
        }

        private static void renderSingleEqBar(GuiGraphicsExtractor graphics, int x, int baseY,
                                              float magnitude, float weight, float chrono) {
            int litSegments = Math.round(magnitude * NUM_SEGMENTS);
            litSegments = Math.clamp(litSegments, 1, NUM_SEGMENTS);

            // Chromatische Geisterbalken bei aktiver Zeitverzerrung
            if (chrono > 0.001F) {
                int ghostAlpha = (int) (40 * chrono * weight);
                int colViolet = ARGB.color(ghostAlpha, 190, 40, 255);
                int colCyan = ARGB.color(ghostAlpha, 0, 240, 255);
                int ghostH = litSegments * 3;
                graphics.fill(x - 3, baseY - ghostH, x - 3 + BAR_WIDTH, baseY, colViolet);
                graphics.fill(x + 3, baseY - ghostH, x + 3 + BAR_WIDTH, baseY, colCyan);
            }

            // Einzelne LED-Segmente zeichnen
            for (int seg = 0; seg < litSegments; seg++) {
                int segBottom = baseY - seg * 3;
                int segTop = segBottom - 2;

                int segAlpha = (int) (235 * weight);
                int color;
                if (chrono > 0.001F) {
                    // Chrono-Palette (Deep Violet -> Indigo -> Electric Cyan)
                    if (seg < 8) {
                        color = ARGB.color(segAlpha, 0, 230, 255);       // Electric Cyan
                    } else if (seg < 12) {
                        color = ARGB.color(segAlpha, 140, 70, 255);      // Indigo
                    } else {
                        color = ARGB.color(segAlpha, 225, 40, 255);      // Temporal Violet
                    }
                } else {
                    // Klassische Retro-HiFi-Palette (Mint-Grün -> Amber-Gold -> Disco-Pink)
                    if (seg < 9) {
                        color = ARGB.color(segAlpha, 15, 255, 130);      // Mint-Grün
                    } else if (seg < 12) {
                        color = ARGB.color(segAlpha, 255, 210, 0);       // Amber-Gold
                    } else {
                        color = ARGB.color(segAlpha, 255, 20, 130);      // Hot-Pink / Peak
                    }
                }
                graphics.fill(x, segTop, x + BAR_WIDTH, segBottom, color);
            }

            // Peak-Hold Cap (Schwebende weiße Spitzenanzeige)
            int peakSeg = Math.min(NUM_SEGMENTS - 1, litSegments);
            int peakBottom = baseY - peakSeg * 3;
            int peakTop = peakBottom - 2;
            int capColor = (chrono > 0.001F)
                    ? ARGB.color((int) (250 * weight), 160, 240, 255)
                    : ARGB.color((int) (250 * weight), 255, 255, 255);
            graphics.fill(x, peakTop, x + BAR_WIDTH, peakBottom, capColor);
        }

        /** Zeichnet schwebende Glitzersterne und Musiknoten. */
        private static void renderSparklesAndNotes(GuiGraphicsExtractor graphics, Minecraft client,
                                                   int width, int height, float sec, float weight, float chrono) {
            for (int i = 0; i < SPARKLES.length; i++) {
                float[] sp = SPARKLES[i];
                float yFrac = (sp[1] - sec * sp[2]) % 1.0F;
                if (yFrac < 0.0F) yFrac += 1.0F;

                float xFrac = sp[0] + (float) Math.sin(sec * 1.8F + sp[3]) * 0.025F;
                int px = (int) (xFrac * width);
                int py = (int) (yFrac * height);

                float tw = 0.5F + 0.5F * (float) Math.sin(sec * 7.5F + sp[3]);
                int starAlpha = (int) (tw * 240.0F * weight);
                if (starAlpha <= 15) continue;

                int colorIdx = ((int) sp[3]) % 4;
                int starColor;
                if (chrono > 0.001F) {
                    starColor = switch (colorIdx) {
                        case 0 -> ARGB.color(starAlpha, 0, 245, 255);    // Chrono Cyan
                        case 1 -> ARGB.color(starAlpha, 180, 70, 255);   // Temporal Violet
                        case 2 -> ARGB.color(starAlpha, 255, 90, 220);   // Neon Magenta
                        default -> ARGB.color(starAlpha, 255, 255, 255); // White Spark
                    };
                } else {
                    starColor = switch (colorIdx) {
                        case 0 -> ARGB.color(starAlpha, 255, 225, 90);   // Disco-Gold
                        case 1 -> ARGB.color(starAlpha, 80, 240, 255);   // Electric-Cyan
                        case 2 -> ARGB.color(starAlpha, 255, 100, 220);  // Hot-Pink
                        default -> ARGB.color(starAlpha, 70, 255, 150);  // Mint-Green
                    };
                }

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
    }
}
