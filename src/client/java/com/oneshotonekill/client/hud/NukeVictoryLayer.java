package com.oneshotonekill.client.hud;

import static com.oneshotonekill.client.hud.HudFx.argb;
import static com.oneshotonekill.client.hud.HudFx.clamp01;
import static com.oneshotonekill.client.hud.HudFx.easeOutBack;
import static com.oneshotonekill.client.hud.HudFx.easeOutCubic;
import static com.oneshotonekill.client.hud.HudFx.rand;
import static com.oneshotonekill.client.hud.HudFx.smooth;

import com.oneshotonekill.client.screen.OsokWidgets;
import com.oneshotonekill.client.state.ClientStates.NukeState;
import com.oneshotonekill.network.OsokPayloads.NukeVictoryPayload;
import com.oneshotonekill.nuke.NukeSequenceManager.NukePhase;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

/**
 * Die Siegerehrung nach dem Einschlag.
 * <p>
 * Gezeichnet wird in einem festen Entwurfsraum von 480 x 270 Einheiten, der auf die Bildschirmgröße
 * skaliert wird - so sieht die Tafel auf jeder Auflösung gleich aus. Von oben nach unten: zwei Spotlights
 * und Konfetti im Hintergrund, die Schlagzeile "VICTORY" (Buchstabe für Buchstabe hereinfallend, mit
 * wanderndem Glanz), der Name des Siegers, dann die Rangliste mit Kill-Balken und hochzählenden Zahlen und
 * zuletzt vier Kacheln mit den Matchwerten.
 * <p>
 * Der Zähler der Sequenz bleibt nach ihrem Ende stehen. Alles, was weiterlaufen soll (Konfetti, Glanz,
 * Spotlights), läuft deshalb mit der Wanduhr.
 */
@SuppressWarnings("DuplicatedCode")
public final class NukeVictoryLayer implements HudElement {
   private static final int GOLD = 0xFFD700;
   private static final int[] CONFETTI = {0xFFD700, 0x00F0FF, 0xFF2244, 0xFFFFFF, 0x00E676, 0xBD00FF};
   private static final int PANEL_WIDTH = 360;
   private static final int ROW_HEIGHT = 14;
   private static final String HEADLINE_WIN = "VICTORY";
   private static final String HEADLINE_DRAW = "DRAW";

   @Override
   public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
      NukeState state = NukeState.INSTANCE;
      if (!state.isRunning() || !state.showsVictoryBoard()) {
         return;
      }
      NukeVictoryPayload victory = state.victory();
      if (victory == null) {
         return;
      }

      Minecraft client = Minecraft.getInstance();
      Font font = client.font;
      float partial = deltaTracker.getGameTimeDeltaPartialTick(false);
      float since = Math.max(0.0F, (state.currentTick() - NukePhase.VICTORY.from() + partial) / 20.0F);
      float ambient = Util.getMillis() / 1000.0F;

      int width = graphics.guiWidth();
      int height = graphics.guiHeight();
      float ui = HudFx.uiScale(width, height);
      int vw = (int) Math.ceil(width / ui);
      int vh = (int) Math.ceil(height / ui);

      graphics.fill(0, 0, width, height, argb(0x05060A, 0.58F * smooth(since / 0.8F)));
      HudFx.vignette(graphics, width, height, GOLD, 0.30F * smooth(since / 1.2F));

      graphics.pose().pushMatrix();
      graphics.pose().scale(ui, ui);
      drawSpotlights(graphics, vw, vh, ambient, since);
      drawConfetti(graphics, vw, vh, ambient, since);
      int bottom = drawHeadline(graphics, font, vw, victory, since, ambient);
      int panelBottom = drawRanking(graphics, font, vw, bottom + 8, victory, since);
      drawTiles(graphics, font, vw, panelBottom + 8, victory, since);
      graphics.pose().popMatrix();
   }

   // ------------------------------------------------------------------
   // Hintergrund
   // ------------------------------------------------------------------

   private static void drawSpotlights(GuiGraphicsExtractor graphics, int vw, int vh, float ambient, float since) {
      float reveal = smooth((since - 0.2F) / 1.0F);
      for (int side = -1; side <= 1; side += 2) {
         float sway = (float) Math.sin(ambient * 0.5F + side) * 0.06F;
         graphics.pose().pushMatrix();
         graphics.pose().translate(vw / 2.0F + side * vw * 0.46F, -4);
         graphics.pose().rotate(-side * (0.38F + sway));
         graphics.fillGradient(-22, 0, 22, (int) (vh * 1.25F), argb(0xFFE9A0, 0.20F * reveal), argb(0xFFE9A0, 0.0F));
         graphics.fillGradient(-8, 0, 8, (int) (vh * 1.25F), argb(0xFFFFFF, 0.16F * reveal), argb(0xFFFFFF, 0.0F));
         graphics.pose().popMatrix();
      }
   }

   private static void drawConfetti(GuiGraphicsExtractor graphics, int vw, int vh, float ambient, float since) {
      float fade = smooth((since - 0.3F) / 1.2F);
      int pieces = 130;
      for (int i = 0; i < pieces; i++) {
         float speed = 22.0F + 60.0F * rand(i + 700);
         float y = (ambient * speed + rand(i + 701) * (vh + 30.0F)) % (vh + 30.0F) - 15.0F;
         float x = rand(i + 702) * vw + (float) Math.sin(ambient * (0.7F + rand(i + 703)) + i) * 14.0F;
         float flutter = Math.abs((float) Math.cos(ambient * (2.0F + 2.0F * rand(i + 704)) + i)) + 0.3F;
         float w = (2.0F + 3.0F * rand(i + 705)) * flutter;
         float h = 2.0F + 2.0F * rand(i + 706);
         HudFx.rect(graphics, x, y, x + w, y + h, argb(CONFETTI[i % CONFETTI.length], (0.55F + 0.4F * rand(i + 707)) * fade));
      }
   }

   // ------------------------------------------------------------------
   // Schlagzeile und Sieger
   // ------------------------------------------------------------------

   /** @return die untere Kante der Schlagzeilen-Gruppe */
   private static int drawHeadline(GuiGraphicsExtractor graphics, Font font, int vw, NukeVictoryPayload victory,
                                   float since, float ambient) {
      String headline = victory.isDraw() ? HEADLINE_DRAW : HEADLINE_WIN;
      float scale = 4.2F;
      int top = 12;
      float cy = top + 9 * scale / 2.0F;

      float total = 0.0F;
      for (int i = 0; i < headline.length(); i++) {
         total += font.width(String.valueOf(headline.charAt(i))) * scale + 2.0F;
      }
      HudFx.glow(graphics, vw / 2.0F, cy, 95.0F, GOLD, 0.30F * smooth((since - 0.2F) / 0.8F));

      float x = vw / 2.0F - total / 2.0F;
      float sweepX = ((ambient * 0.45F) % 1.7F - 0.35F) * total;
      for (int i = 0; i < headline.length(); i++) {
         String letter = String.valueOf(headline.charAt(i));
         float letterW = font.width(letter) * scale;
         float p = clamp01((since - (0.15F + i * 0.11F)) / 0.4F);
         float drop = (1.0F - easeOutBack(p)) * -50.0F;
         float shine = (float) Math.exp(-Math.pow((x + letterW / 2.0F - (vw / 2.0F - total / 2.0F) - sweepX) / (total * 0.12F), 2.0));
         int colour = HudFx.mix(HudFx.lighten(GOLD, 0.05F), 0xFFFFFF, shine * 0.85F);
         HudFx.bigText(graphics, font, letter, x + letterW / 2.0F, cy + drop, scale * (0.8F + 0.2F * p), argb(colour, p), true);
         x += letterW + 2.0F;
      }

      // Zierlinie mit Rauten
      float lineP = easeOutCubic((since - 1.0F) / 0.6F);
      int half = Math.round(110 * lineP);
      int lineY = top + (int) (9 * scale) + 5;
      if (half > 0) {
         graphics.fill(vw / 2 - half, lineY, vw / 2 + half, lineY + 1, argb(GOLD, 0.9F));
         HudFx.disc(graphics, vw / 2.0F - half - 3, lineY + 0.5F, 2.5F, argb(0xFFFFFF, 0.9F));
         HudFx.disc(graphics, vw / 2.0F + half + 3, lineY + 0.5F, 2.5F, argb(0xFFFFFF, 0.9F));
      }

      int y = lineY + 7;
      if (victory.isDraw()) {
         HudFx.smallText(graphics, font, "KEIN SIEGER  //  KEINE UEBERLEBENDEN", vw / 2.0F, y + 3, 1.3F,
            argb(0xE2E8F0, smooth((since - 1.1F) / 0.4F)));
         y += 16;
      } else {
         float p = smooth((since - 1.1F) / 0.4F);
         HudFx.smallText(graphics, font, "M A T C H   C H A M P I O N", vw / 2.0F, y, 0.9F, argb(GOLD, p));
         float resolved = clamp01((since - 1.2F) / 0.8F);
         String name = HudFx.scramble(victory.winner(), resolved, (int) (ambient * 24.0F));
         HudFx.bigText(graphics, font, name, vw / 2.0F, y + 20, 2.3F, argb(0xFFFFFF, p), true);
         y += 32;
      }
      HudFx.smallText(graphics, font, victory.reason(), vw / 2.0F, y, 0.9F,
         argb(0x94A3B8, smooth((since - 1.3F) / 0.4F)));
      return y + 10;
   }

   // ------------------------------------------------------------------
   // Rangliste
   // ------------------------------------------------------------------

   /** @return die untere Kante der Tafel */
   private static int drawRanking(GuiGraphicsExtractor graphics, Font font, int vw, int top,
                                  NukeVictoryPayload victory, float since) {
      List<NukeVictoryPayload.Row> rows = victory.ranking();
      int shown = Math.min(NukeVictoryPayload.MAX_ROWS, rows.size());
      int left = (vw - PANEL_WIDTH) / 2;
      int right = left + PANEL_WIDTH;
      int height = 15 + shown * ROW_HEIGHT + 4;

      float panelP = smooth((since - 1.2F) / 0.5F);
      graphics.fill(left, top, right, top + height, argb(0x07090F, 0.90F * panelP));
      graphics.outline(left, top, PANEL_WIDTH, height, argb(GOLD, 0.65F * panelP));
      graphics.fill(left + 1, top + 1, right - 1, top + 2, argb(0xFFFFFF, 0.12F * panelP));

      int muted = argb(0x94A3B8, panelP);
      HudFx.leftText(graphics, font, "#  SPIELER", left + 8, top + 4, 0.85F, muted);
      HudFx.rightText(graphics, font, "KILLS", left + 262, top + 4, 0.85F, muted);
      HudFx.rightText(graphics, font, "TODE", left + 296, top + 4, 0.85F, muted);
      HudFx.rightText(graphics, font, "K/D", left + 330, top + 4, 0.85F, muted);
      HudFx.rightText(graphics, font, "SERIE", left + 354, top + 4, 0.85F, muted);
      graphics.fill(left + 6, top + 13, right - 6, top + 14, argb(GOLD, 0.35F * panelP));

      int maxKills = 1;
      for (NukeVictoryPayload.Row row : rows) {
         maxKills = Math.max(maxKills, row.kills());
      }

      for (int i = 0; i < shown; i++) {
         NukeVictoryPayload.Row row = rows.get(i);
         float start = 1.5F + i * 0.2F;
         float p = clamp01((since - start) / 0.4F);
         if (p <= 0.0F) {
            continue;
         }
         float count = easeOutCubic((since - start - 0.25F) / 1.0F);
         float slide = (1.0F - easeOutCubic(p)) * (i % 2 == 0 ? -140.0F : 140.0F);
         int y = top + 16 + i * ROW_HEIGHT;

         graphics.pose().pushMatrix();
         graphics.pose().translate(slide, 0);
         if (i == 0) {
            graphics.fill(left + 3, y - 1, right - 3, y + ROW_HEIGHT - 2, argb(GOLD, 0.16F * p * (0.8F + 0.2F * (float) Math.sin(Util.getMillis() / 300.0))));
            graphics.fill(left + 3, y - 1, left + 5, y + ROW_HEIGHT - 2, argb(GOLD, p));
         } else if (i % 2 == 1) {
            graphics.fill(left + 3, y - 1, right - 3, y + ROW_HEIGHT - 2, argb(0xFFFFFF, 0.04F * p));
         }

         int chip = switch (i) {
            case 0 -> GOLD;
            case 1 -> 0xD7DEE8;
            case 2 -> 0xCD7F32;
            default -> 0x475569;
         };
         graphics.fill(left + 6, y, left + 16, y + 10, argb(chip, p));
         HudFx.smallText(graphics, font, Integer.toString(i + 1), left + 11, y + 1, 0.9F, argb(i < 3 ? 0x101418 : 0xE2E8F0, p));

         int nameColour = switch (i) {
            case 0 -> GOLD;
            case 1 -> 0xE2E8F0;
            case 2 -> 0xE8A865;
            default -> 0xF8FAFC;
         };
         HudFx.leftText(graphics, font, row.name(), left + 21, y + 1, 0.95F, argb(nameColour, p));

         int barW = Math.round(78 * (row.kills() / (float) maxKills) * count);
         graphics.fill(left + 150, y + 3, left + 228, y + 8, argb(0x1E293B, 0.8F * p));
         if (barW > 0) {
            graphics.fillGradient(left + 150, y + 3, left + 150 + barW, y + 8, argb(0x1FB86B, p), argb(0x00E676, p));
         }

         HudFx.rightText(graphics, font, Integer.toString(Math.round(row.kills() * count)), left + 262, y + 1, 0.95F,
            argb(OsokWidgets.COLOR_EMERALD, p));
         HudFx.rightText(graphics, font, Integer.toString(Math.round(row.deaths() * count)), left + 296, y + 1, 0.95F,
            argb(OsokWidgets.COLOR_CRIMSON, p));
         HudFx.rightText(graphics, font, row.ratio(), left + 330, y + 1, 0.95F, argb(0xF8FAFC, p));
         HudFx.rightText(graphics, font, "★" + Math.round(row.bestStreak() * count), left + 354, y + 1, 0.95F,
            argb(GOLD, p));
         graphics.pose().popMatrix();
      }
      return top + height;
   }

   // ------------------------------------------------------------------
   // Kacheln
   // ------------------------------------------------------------------

   private static void drawTiles(GuiGraphicsExtractor graphics, Font font, int vw, int top,
                                 NukeVictoryPayload victory, float since) {
      int gap = 6;
      int tileW = (PANEL_WIDTH - 3 * gap) / 4;
      int tileH = 34;
      int left = (vw - PANEL_WIDTH) / 2;
      int rows = Math.min(NukeVictoryPayload.MAX_ROWS, victory.ranking().size());
      float base = 1.5F + rows * 0.2F + 0.5F;

      String[] labels = {"DAUER", "KILLS GESAMT", "KILLS / MIN", victory.mvp().isEmpty() ? "TODE GESAMT" : "LAENGSTE SERIE"};
      for (int i = 0; i < 4; i++) {
         float start = base + i * 0.15F;
         float p = clamp01((since - start) / 0.35F);
         if (p <= 0.0F) {
            continue;
         }
         float count = easeOutCubic((since - start) / 1.1F);
         int x = left + i * (tileW + gap);
         int y = top + Math.round((1.0F - easeOutCubic(p)) * 14.0F);

         graphics.fill(x, y, x + tileW, y + tileH, argb(0x07090F, 0.88F * p));
         graphics.outline(x, y, tileW, tileH, argb(GOLD, 0.45F * p));
         graphics.fill(x, y, x + 2, y + tileH, argb(GOLD, p));
         HudFx.leftText(graphics, font, labels[i], x + 6, y + 4, 0.75F, argb(0x94A3B8, p));

         String value;
         int colour = 0xF8FAFC;
         float scale = 1.7F;
         switch (i) {
            case 0 -> {
               int seconds = Math.round(victory.matchSeconds() * count);
               value = seconds / 60 + ":" + HudFx.twoDigits(seconds % 60);
            }
            case 1 -> {
               value = Integer.toString(Math.round(victory.totalKills() * count));
               colour = OsokWidgets.COLOR_EMERALD;
            }
            case 2 -> value = String.format(Locale.ROOT, "%.1f", victory.killsPerMinute() * count);
            default -> {
               if (victory.mvp().isEmpty()) {
                  value = Integer.toString(Math.round(victory.totalDeaths() * count));
                  colour = OsokWidgets.COLOR_CRIMSON;
               } else {
                  value = "★" + Math.round(victory.mvpStreak() * count);
                  colour = GOLD;
               }
            }
         }
         HudFx.leftText(graphics, font, value, x + 6, y + 14, scale, argb(colour, p));
         if (i == 3 && !victory.mvp().isEmpty()) {
            HudFx.leftText(graphics, font, victory.mvp(), x + 6, y + tileH - 9, 0.75F, argb(0xCBD5E1, p));
         }
      }

      // Sandsack-Zeile: wer am häufigsten gestorben ist
      float footerP = smooth((since - base - 0.9F) / 0.5F);
      if (footerP > 0.0F && !victory.mostDeaths().isEmpty()) {
         HudFx.smallText(graphics, font, "SANDSACK: " + victory.mostDeaths() + "  (" + victory.mostDeathsCount() + " Tode)",
            vw / 2.0F, top + tileH + 6, 0.85F, argb(0x94A3B8, footerP));
      }
   }
}
