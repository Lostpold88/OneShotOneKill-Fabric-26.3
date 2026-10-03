package com.oneshotonekill.client.hud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;

/**
 * Gemeinsame Zeichenbausteine der Inszenierungs-HUDs (Match-Start und Endgame).
 * <p>
 * Alles ist zustandslos: Animationen sind reine Funktionen der Zeit, Partikel kommen aus {@link #rand(int)}.
 * Dadurch kann nichts aus dem Takt geraten, wenn ein Paket spät kommt oder das Spiel pausiert.
 */
final class HudFx {
   private HudFx() {
   }

   // -- Farben und Kurven -----------------------------------------------------------------------

   static int argb(int rgb, float alpha) {
      return Mth.clamp(Math.round(alpha * 255.0F), 0, 255) << 24 | rgb & 0x00FFFFFF;
   }

   static int lighten(int rgb, float amount) {
      int r = rgb >> 16 & 0xFF;
      int g = rgb >> 8 & 0xFF;
      int b = rgb & 0xFF;
      r += Math.round((255 - r) * amount);
      g += Math.round((255 - g) * amount);
      b += Math.round((255 - b) * amount);
      return r << 16 | g << 8 | b;
   }

   static int mix(int from, int to, float share) {
      float t = Mth.clamp(share, 0.0F, 1.0F);
      int r = Math.round(Mth.lerp(t, from >> 16 & 0xFF, to >> 16 & 0xFF));
      int g = Math.round(Mth.lerp(t, from >> 8 & 0xFF, to >> 8 & 0xFF));
      int b = Math.round(Mth.lerp(t, from & 0xFF, to & 0xFF));
      return r << 16 | g << 8 | b;
   }

   static float clamp01(float t) {
      return Mth.clamp(t, 0.0F, 1.0F);
   }

   static float smooth(float t) {
      float x = clamp01(t);
      return x * x * (3.0F - 2.0F * x);
   }

   static float easeOutCubic(float t) {
      float x = 1.0F - clamp01(t);
      return 1.0F - x * x * x;
   }

   static float easeOutBack(float t) {
      float x = clamp01(t) - 1.0F;
      return 1.0F + 2.70158F * x * x * x + 1.70158F * x * x;
   }

   /** Deterministischer Pseudo-Zufall 0..1 aus einer Zahl: stabile Partikel ohne Zustand. */
   static float rand(int seed) {
      int h = seed * 0x9E3779B1;
      h ^= h >>> 15;
      h *= 0x85EBCA6B;
      h ^= h >>> 13;
      return (h & 0xFFFFFF) / (float) 0x1000000;
   }

   static String twoDigits(int value) {
      return value < 10 ? "0" + value : Integer.toString(value);
   }
/**
    * Skalierung für Layouts, die auf einer festen Entwurfsgröße von 480 x 270 Einheiten gebaut sind.
    * Ein HUD zeichnet dann in einem Koordinatenraum, der auf jeder Auflösung gleich aussieht.
    */
   static float uiScale(int width, int height) {
      return Math.max(0.75F, Math.min(width / 480.0F, height / 270.0F));
   }

   /** Text, dessen noch nicht aufgelöster Rest aus flackernden Zeichen besteht (Entschlüsselungs-Effekt). */
   static String scramble(String text, float resolved, int seed) {
      int shown = Mth.clamp((int) (text.length() * resolved), 0, text.length());
      if (shown >= text.length()) {
         return text;
      }
      String glyphs = "0123456789ABCDEF#%&";
      StringBuilder out = new StringBuilder(text.substring(0, shown));
      for (int i = shown; i < text.length(); i++) {
         out.append(text.charAt(i) == ' ' ? ' ' : glyphs.charAt((int) (rand(seed + i * 7) * glyphs.length())));
      }
      return out.toString();
   }


   // -- Formen ----------------------------------------------------------------------------------

   static void rect(GuiGraphicsExtractor graphics, float x0, float y0, float x1, float y1, int colour) {
      if ((colour >>> 24) == 0) {
         return;
      }
      int left = Math.round(x0);
      int top = Math.round(y0);
      graphics.fill(left, top, Math.max(Math.round(x1), left + 1), Math.max(Math.round(y1), top + 1), colour);
   }

   /** Linie als gedrehtes Rechteck. */
   static void line(GuiGraphicsExtractor graphics, float x0, float y0, float x1, float y1, float thickness, int colour) {
      if ((colour >>> 24) == 0) {
         return;
      }
      float dx = x1 - x0;
      float dy = y1 - y0;
      float length = (float) Math.sqrt(dx * dx + dy * dy);
      if (length < 0.5F) {
         return;
      }
      graphics.pose().pushMatrix();
      graphics.pose().translate(x0, y0);
      graphics.pose().rotate((float) Math.atan2(dy, dx));
      graphics.fill(0, -(int) Math.ceil(thickness / 2.0F), Math.round(length), (int) Math.ceil(thickness / 2.0F), colour);
      graphics.pose().popMatrix();
   }

   /** Ring aus Punkten. */
   static void ring(GuiGraphicsExtractor graphics, float centreX, float centreY, float radius,
                    int dots, int size, int colour) {
      if ((colour >>> 24) == 0 || radius <= 0.0F) {
         return;
      }
      for (int i = 0; i < dots; i++) {
         float angle = (float) (i * Math.PI * 2.0 / dots);
         int x = Math.round(centreX + Mth.cos(angle) * radius);
         int y = Math.round(centreY + Mth.sin(angle) * radius);
         graphics.fill(x - size / 2, y - size / 2, x + size / 2 + 1, y + size / 2 + 1, colour);
      }
   }

   /** Gefüllte Kreisscheibe aus waagerechten Streifen (bei großen Radien mit Schrittweite). */
   static void disc(GuiGraphicsExtractor graphics, float centreX, float centreY, float radius, int colour) {
      if ((colour >>> 24) == 0 || radius < 1.0F) {
         return;
      }
      int step = Math.max(1, Math.round(radius / 40.0F));
      for (int dy = -Math.round(radius); dy < radius; dy += step) {
         float mid = dy + step * 0.5F;
         float half = (float) Math.sqrt(Math.max(0.0F, radius * radius - mid * mid));
         graphics.fill(Math.round(centreX - half), Math.round(centreY) + dy,
            Math.round(centreX + half), Math.round(centreY) + dy + step, colour);
      }
   }

   /** Weiche Leuchtkugel aus gestapelten, durchscheinenden Scheiben. */
   static void glow(GuiGraphicsExtractor graphics, float centreX, float centreY, float radius, int rgb, float alpha) {
      if (alpha <= 0.004F || radius < 2.0F) {
         return;
      }
      int layers = 7;
      for (int i = 0; i < layers; i++) {
         float share = (i + 1) / (float) layers;
         float layerAlpha = alpha / layers * 1.6F * (1.0F - share * 0.55F);
         disc(graphics, centreX, centreY, radius * share, argb(rgb, layerAlpha));
      }
   }

   /** Farbige Randverdunkelung aus weich auslaufenden Streifen. */
   static void vignette(GuiGraphicsExtractor graphics, int width, int height, int rgb, float strength) {
      if (strength <= 0.005F) {
         return;
      }
      int bands = 22;
      int stripW = Math.max(3, width / 48);
      int stripH = Math.max(3, height / 36);
      for (int i = 0; i < bands; i++) {
         float fade = 1.0F - i / (float) bands;
         int colour = argb(rgb, strength * fade * fade);
         graphics.fill(i * stripW, 0, (i + 1) * stripW, height, colour);
         graphics.fill(width - (i + 1) * stripW, 0, width - i * stripW, height, colour);
         graphics.fill(0, i * stripH, width, (i + 1) * stripH, colour);
         graphics.fill(0, height - (i + 1) * stripH, width, height - i * stripH, colour);
      }
   }

   /** Warnstreifen in einem waagerechten Band; {@code scroll} verschiebt sie. */
   static void hazardStripes(GuiGraphicsExtractor graphics, int width, int top, int bottom, int scroll, int rgb, float alpha) {
      graphics.enableScissor(0, top, width, bottom);
      int span = bottom - top;
      for (int x = -span * 2; x < width + span * 2; x += span * 2) {
         graphics.pose().pushMatrix();
         graphics.pose().translate(x + scroll % (span * 2), top);
         graphics.pose().rotate(-0.7F);
         graphics.fill(0, -span, span, span * 2, argb(rgb, alpha));
         graphics.pose().popMatrix();
      }
      graphics.disableScissor();
   }

   // -- Text ------------------------------------------------------------------------------------

   /** Zentrierter, skalierter Text mit optionaler dicker schwarzer Kontur. */
   static void bigText(GuiGraphicsExtractor graphics, Font font, String text, float centreX, float centreY,
                       float scale, int colour, boolean outline) {
      if ((colour >>> 24) == 0) {
         return;
      }
      graphics.pose().pushMatrix();
      graphics.pose().translate(centreX, centreY);
      graphics.pose().scale(scale, scale);
      int x = -font.width(text) / 2;
      int y = -9 / 2;
      if (outline) {
         int shadow = colour & 0xFF000000;
         for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
               if (dx != 0 || dy != 0) {
                  graphics.text(font, text, x + dx, y + dy, shadow, false);
               }
            }
         }
      }
      graphics.text(font, text, x, y, colour, false);
      graphics.pose().popMatrix();
   }

   /** Kleiner zentrierter Text mit Schatten, optional vergrößert. */
   static void smallText(GuiGraphicsExtractor graphics, Font font, String text, float centreX, float y,
                         float scale, int colour) {
      if ((colour >>> 24) == 0 || text.isEmpty()) {
         return;
      }
      graphics.pose().pushMatrix();
      graphics.pose().translate(centreX, y);
      graphics.pose().scale(scale, scale);
      graphics.text(font, text, -font.width(text) / 2, 0, colour, true);
      graphics.pose().popMatrix();
   }

   /** Linksbündiger Text mit Schatten, optional vergrößert. */
   static void leftText(GuiGraphicsExtractor graphics, Font font, String text, float x, float y,
                        float scale, int colour) {
      if ((colour >>> 24) == 0 || text.isEmpty()) {
         return;
      }
      graphics.pose().pushMatrix();
      graphics.pose().translate(x, y);
      graphics.pose().scale(scale, scale);
      graphics.text(font, text, 0, 0, colour, true);
      graphics.pose().popMatrix();
   }

   /** Rechtsbündiger Text mit Schatten, optional vergrößert. */
   static void rightText(GuiGraphicsExtractor graphics, Font font, String text, float rightEdge, float y,
                         float scale, int colour) {
      leftText(graphics, font, text, rightEdge - font.width(text) * scale, y, scale, colour);
   }
}
