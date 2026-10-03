package com.oneshotonekill.client.hud;

import com.oneshotonekill.client.screen.OsokWidgets;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import static com.oneshotonekill.client.state.ClientStates.*;

/**
 * Modernes AAA Cyber-Esports HUD-Intro für Match-Start & Countdown.
 */
@SuppressWarnings({"DuplicateBranchesInSwitch", "NullableProblems", "UnnecessaryLocalVariable"})
public final class MatchHudLayers {
   private MatchHudLayers() {}

   // =========================================================================
   // MatchCountdownLayer.java
   // =========================================================================

    // =========================================================================
   // MatchStartOverlayLayer.java
   // =========================================================================
   /**
    * Die Bildschirmeffekte beim Match-Start: Portalwirbel und Verzerrungswelle.
    */
   public static final class MatchStartOverlayLayer implements HudElement {
      private static final float PORTAL_FADE = 0.02F;
      private static final float CONFUSION_FADE = 0.025F;

      @Override
      public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
         Minecraft client = Minecraft.getInstance();
         MatchStartState state = MatchStartState.INSTANCE;

         float portal = state.getPortalIntensity();
         if (portal > 0.0F) {
            client.gui.hud.extractPortalOverlay(graphics, portal * 0.9F);
            state.setPortalIntensity(Math.max(0.0F, portal - PORTAL_FADE));
         }

         float confusion = state.getConfusionIntensity();
         if (confusion > 0.0F) {
            client.gui.hud.extractConfusionOverlay(graphics, confusion * 0.5F);
            state.setConfusionIntensity(Math.max(0.0F, confusion - CONFUSION_FADE));
         }
      }
   }

   // =========================================================================
   // MatchBannerLayer.java
   // =========================================================================
   /**
    * Rendert animierte Cyber-Status-Banner (Pause, Resume, Stop, Map-Reset)
    * und die persistente Status-Leiste während eines pausierten Matches.
    */
   public static final class MatchBannerLayer implements HudElement {
      private static final int BANNER_WIDTH = 380;
      private static final int BANNER_HEIGHT = 48;
      private static final int BRACKET_SIZE = 10;

      @Override
      public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
         Minecraft client = Minecraft.getInstance();
         MatchBannerState state = MatchBannerState.INSTANCE;
         Font font = client.font;
         int screenWidth = graphics.guiWidth();
         int screenHeight = graphics.guiHeight();
         float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);

         // 1. Persistente HUD-Pausenleiste oben
         if (state.isMatchPaused() && client.gui.screen() == null) {
            String pauseChip = Component.translatable("hud.oneshotonekill.match.paused_chip").getString();
            int chipWidth = font.width(pauseChip) + 18;
            int chipX = screenWidth / 2 - chipWidth / 2;
            OsokWidgets.statusBadge(graphics, font, chipX, 8, pauseChip, OsokWidgets.COLOR_GOLD, true);
         }

         // 2. Animiertes Status-Banner
         if (!state.isBannerActive()) {
            return;
         }

         float progress = state.getProgress(partialTick);
         // Einblenden in den ersten 15%, Ausblenden in den letzten 15%
         float alphaFactor = Math.min(1.0F, Math.min((1.0F - progress) * 6.6F, progress * 6.6F));
         if (alphaFactor <= 0.01F) {
            return;
         }

         int centreX = screenWidth / 2;
         int bannerY = Math.max(14, (int) (screenHeight * 0.05F));
         int left = centreX - BANNER_WIDTH / 2;
         int right = left + BANNER_WIDTH;
         int bottom = bannerY + BANNER_HEIGHT;

         int accent = state.getAccentColor();
         int alphaInt = (int) (alphaFactor * 255);

         // Äußerer Randschatten
         graphics.fill(left + 2, bottom, right + 2, bottom + 3, (int) (alphaFactor * 60) << 24);
         graphics.fill(right, bannerY + 2, right + 3, bottom + 2, (int) (alphaFactor * 60) << 24);

         // Glassmorphic Body
         int bgAlpha = (int) (alphaFactor * 242);
         graphics.fill(left, bannerY, right, bottom, bgAlpha << 24 | 0x000A0D15);

         // Innere Glanzkante oben
         int lightAlpha = (int) (alphaFactor * 40);
         graphics.horizontalLine(left + 1, right - 2, bannerY + 1, lightAlpha << 24 | 0x00FFFFFF);

         // Leuchtender Rand
         int borderAlpha = (int) (alphaFactor * 180);
         int borderColor = (accent & 0x00FFFFFF) | (borderAlpha << 24);
         graphics.horizontalLine(left, right - 1, bannerY, borderColor);
         graphics.horizontalLine(left, right - 1, bottom - 1, borderColor);
         graphics.verticalLine(left, bannerY, bottom - 1, borderColor);
         graphics.verticalLine(right - 1, bannerY, bottom - 1, borderColor);

         // Akzent-Indikatorstreifen links & rechts
         int stripAlpha = (int) (alphaFactor * 230);
         int stripColor = (accent & 0x00FFFFFF) | (stripAlpha << 24);
         graphics.fill(left + 2, bannerY + 3, left + 6, bottom - 3, stripColor);
         graphics.fill(right - 6, bannerY + 3, right - 2, bottom - 3, stripColor);

         // Zarter Sweep-Lichtstreifen über das Banner
         float streakRatio = ((1.0F - progress) * 2.2F) % 1.0F;
         int streakX = left + (int) (streakRatio * (BANNER_WIDTH - 24));
         int streakAlpha = (int) (alphaFactor * 30);
         graphics.fill(streakX, bannerY + 1, streakX + 24, bottom - 1, (accent & 0x00FFFFFF) | (streakAlpha << 24));

         // 4 Taktische Eck-Klammern
         int bracketDist = 4;
         int brkAlpha = (int) (alphaFactor * 200);
         int brkColor = (accent & 0x00FFFFFF) | (brkAlpha << 24);

         // Oben Links
         graphics.fill(left - bracketDist - BRACKET_SIZE, bannerY - bracketDist, left - bracketDist, bannerY - bracketDist + 2, brkColor);
         graphics.fill(left - bracketDist, bannerY - bracketDist, left - bracketDist + 2, bannerY - bracketDist + BRACKET_SIZE, brkColor);
         // Oben Rechts
         graphics.fill(right + bracketDist, bannerY - bracketDist, right + bracketDist + BRACKET_SIZE, bannerY - bracketDist + 2, brkColor);
         graphics.fill(right + bracketDist - 2, bannerY - bracketDist, right + bracketDist, bannerY - bracketDist + BRACKET_SIZE, brkColor);
         // Unten Links
         graphics.fill(left - bracketDist - BRACKET_SIZE, bottom + bracketDist - 2, left - bracketDist, bottom + bracketDist, brkColor);
         graphics.fill(left - bracketDist, bottom + bracketDist - BRACKET_SIZE, left - bracketDist + 2, bottom + bracketDist, brkColor);
         // Unten Rechts
         graphics.fill(right + bracketDist, bottom + bracketDist - 2, right + bracketDist + BRACKET_SIZE, bottom + bracketDist, brkColor);
         graphics.fill(right + bracketDist - 2, bottom + bracketDist - BRACKET_SIZE, right + bracketDist, bottom + bracketDist, brkColor);

         // Titel & Subtitel
         int titleColor = (accent & 0x00FFFFFF) | (alphaInt << 24);
         int subColor = (OsokWidgets.COLOR_TEXT_WHITE & 0x00FFFFFF) | ((int) (alphaFactor * 220) << 24);

         graphics.centeredText(font, state.getTitleComponent(), centreX + 1, bannerY + 11, (int) (alphaFactor * 180) << 24);
         graphics.centeredText(font, state.getTitleComponent(), centreX, bannerY + 10, titleColor);

         if (!state.getSubtitle().isEmpty()) {
            graphics.centeredText(font, state.getSubtitleComponent(), centreX + 1, bannerY + 27, (int) (alphaFactor * 150) << 24);
            graphics.centeredText(font, state.getSubtitleComponent(), centreX, bannerY + 26, subColor);
         }
      }
   }

   // =========================================================================
   // GunGameHudLayer.java
   // =========================================================================
}

