package com.oneshotonekill.client.hud;

import com.oneshotonekill.client.screen.OsokWidgets;
import com.oneshotonekill.client.state.ClientStates.GunGameHudState;
import com.oneshotonekill.match.GunGameTier;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Das HUD des Waffenspiels, oben links:
 * <ul>
 *   <li>Stufe, eigener Rang und Item-Icon der aktuellen Stufe mit Kill-Punkten,</li>
 *   <li>die Kill-Bedingung, grün solange das Fenster der Stufe offen ist,</li>
 *   <li>die nächste Stufe und der Abstand zum Führenden,</li>
 *   <li>beim Aufstieg ein Banner mit dem Namen der neuen Stufe.</li>
 * </ul>
 * Die Farbe richtet sich nach dem Akt der Stufe, nicht nach der Stufe selbst.
 */
public final class GunGameHudLayer implements HudElement {
   private static final int BADGE_WIDTH = 260;
   private static final int BADGE_HEIGHT = 76;
   private static final int COLOR_MET = 0xFF00E676;
   private static final int COLOR_UNMET = 0xFF64748B;

   @Override
   public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
      Minecraft client = Minecraft.getInstance();
      if (client.player == null || client.gui.screen() != null) {
         return;
      }

      GunGameHudState state = GunGameHudState.INSTANCE;
      if (!state.isActive()) {
         return;
      }

      Font font = client.font;
      int screenWidth = graphics.guiWidth();
      int screenHeight = graphics.guiHeight();
      float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);

      GunGameTier tier = GunGameTier.byIndex(state.getCurrentTier());
      boolean lastTier = state.getCurrentTier() >= state.getTotalTiers();
      GunGameTier next = lastTier ? null : GunGameTier.byIndex(state.getCurrentTier() + 1);
      int accent = actColor(tier.act());

      int badgeX = 14;
      int badgeY = 14;
      int badgeRight = badgeX + BADGE_WIDTH;

      graphics.fill(badgeX, badgeY, badgeRight, badgeY + BADGE_HEIGHT, 0xDD080A10);
      graphics.horizontalLine(badgeX, badgeRight - 1, badgeY, (accent & 0x00FFFFFF) | 0x99000000);
      graphics.horizontalLine(badgeX, badgeRight - 1, badgeY + BADGE_HEIGHT - 1, (accent & 0x00FFFFFF) | 0x44000000);
      graphics.verticalLine(badgeX, badgeY, badgeY + BADGE_HEIGHT - 1, (accent & 0x00FFFFFF) | 0x99000000);
      graphics.verticalLine(badgeRight - 1, badgeY, badgeY + BADGE_HEIGHT - 1, (accent & 0x00FFFFFF) | 0x44000000);
      graphics.fill(badgeX + 2, badgeY + 2, badgeX + 5, badgeY + BADGE_HEIGHT - 2, accent);

      // Zeile 1: Stufe und eigener Rang
      String tierTitle = Component.translatable("hud.oneshotonekill.match.tier", state.getCurrentTier()).getString()
         + "/" + state.getTotalTiers();
      graphics.text(font, tierTitle, badgeX + 10, badgeY + 6, accent);
      String rankText = "#" + state.getRank() + "/" + state.getPlayerCount();
      graphics.text(font, rankText, badgeRight - 10 - font.width(rankText), badgeY + 6, 0xFFCBD5E1);

      // Zeile 2: Item-Icon, Name, Kill-Punkte
      graphics.item(tier.iconStack(), badgeX + 10, badgeY + 17);
      int currentKills = state.getTierKills();
      int requiredKills = state.getRequiredKills();
      int dotsX = badgeRight - 10;
      for (int i = requiredKills - 1; i >= 0; i--) {
         int dotColor = i < currentKills ? COLOR_MET : 0xFF475569;
         graphics.fill(dotsX - 8, badgeY + 22, dotsX, badgeY + 30, dotColor);
         dotsX -= 12;
      }
      String name = Component.translatable(tier.nameKey()).getString();
      graphics.text(font, font.plainSubstrByWidth(name, dotsX - (badgeX + 34)), badgeX + 34, badgeY + 22, 0xFFFFFFFF);

      // Zeile 3: Kill-Bedingung, grün solange das Fenster offen ist
      boolean met = state.isConditionMet();
      graphics.fill(badgeX + 10, badgeY + 41, badgeX + 16, badgeY + 47, met ? COLOR_MET : COLOR_UNMET);
      String condition = Component.translatable(tier.conditionKey()).getString();
      graphics.text(font, font.plainSubstrByWidth(condition, BADGE_WIDTH - 36), badgeX + 22, badgeY + 40,
         met ? 0xFFE2E8F0 : 0xFF94A3B8);

      // Zeile 4: nächste Stufe und Abstand zum Führenden
      if (next != null) {
         String nextText = Component.translatable("hud.oneshotonekill.match.next_tier",
            Component.translatable(next.nameKey()).getString()).getString();
         graphics.text(font, font.plainSubstrByWidth(nextText, BADGE_WIDTH - 110), badgeX + 10, badgeY + 54, 0xFF64748B);
      } else {
         graphics.text(font, Component.translatable("hud.oneshotonekill.match.final_tier"), badgeX + 10, badgeY + 54,
            OsokWidgets.COLOR_GOLD);
      }
      if (state.getRank() > 1) {
         String gap = Component.translatable("hud.oneshotonekill.match.leader_tier", state.getLeaderTier()).getString();
         graphics.text(font, gap, badgeRight - 10 - font.width(gap), badgeY + 54, 0xFFFB7185);
      } else if (state.getPlayerCount() > 1) {
         String lead = Component.translatable("hud.oneshotonekill.match.you_lead").getString();
         graphics.text(font, lead, badgeRight - 10 - font.width(lead), badgeY + 54, COLOR_MET);
      }

      // Fortschrittsbalken unten am Badge
      float progress = Math.min(1.0F, (float) currentKills / (float) Math.max(1, requiredKills));
      int barWidth = Math.round((BADGE_WIDTH - 10) * progress);
      if (barWidth > 0) {
         graphics.fill(badgeX + 6, badgeY + BADGE_HEIGHT - 3, badgeX + 6 + barWidth, badgeY + BADGE_HEIGHT - 1, accent);
      }

      float levelUpProgress = state.getLevelUpProgress(partialTick);
      if (levelUpProgress > 0.0F) {
         drawLevelUpBanner(graphics, font, screenWidth, screenHeight, state, tier, levelUpProgress, accent);
      }
   }

   private static void drawLevelUpBanner(GuiGraphicsExtractor graphics, Font font, int screenWidth, int screenHeight,
                                         GunGameHudState state, GunGameTier tier, float progress, int accent) {
      float alphaFactor = Math.min(1.0F, Math.min((1.0F - progress) * 5.0F, progress * 4.0F));
      if (alphaFactor <= 0.01F) {
         return;
      }

      int centreX = screenWidth / 2;
      int bannerY = (int) (screenHeight * 0.18F);
      int bannerW = 340;
      int bannerH = 44;
      int left = centreX - bannerW / 2;
      int right = left + bannerW;
      int bottom = bannerY + bannerH;

      int alphaInt = (int) (alphaFactor * 255);
      int bgAlpha = (int) (alphaFactor * 230);
      graphics.fill(left, bannerY, right, bottom, bgAlpha << 24 | 0x000A0D15);

      int borderColor = (accent & 0x00FFFFFF) | ((int) (alphaFactor * 200) << 24);
      graphics.horizontalLine(left, right - 1, bannerY, borderColor);
      graphics.horizontalLine(left, right - 1, bottom - 1, borderColor);
      graphics.verticalLine(left, bannerY, bottom - 1, borderColor);
      graphics.verticalLine(right - 1, bannerY, bottom - 1, borderColor);

      graphics.fill(left + 2, bannerY + 2, left + 5, bottom - 2, (accent & 0x00FFFFFF) | (alphaInt << 24));
      graphics.fill(right - 5, bannerY + 2, right - 2, bottom - 2, (accent & 0x00FFFFFF) | (alphaInt << 24));

      String title = Component.translatable("hud.oneshotonekill.match.tier_banner_title", state.getCurrentTier()).getString();
      String subtitle = Component.translatable("hud.oneshotonekill.match.tier_banner_sub",
         Component.translatable(tier.nameKey()).getString()).getString();

      graphics.centeredText(font, Component.literal(title), centreX + 1, bannerY + 9, (int) (alphaFactor * 160) << 24);
      graphics.centeredText(font, Component.literal(title), centreX, bannerY + 8, (accent & 0x00FFFFFF) | (alphaInt << 24));

      graphics.centeredText(font, Component.literal(subtitle), centreX + 1, bannerY + 24, (int) (alphaFactor * 140) << 24);
      graphics.centeredText(font, Component.literal(subtitle), centreX, bannerY + 23,
         (OsokWidgets.COLOR_TEXT_WHITE & 0x00FFFFFF) | (alphaInt << 24));
   }

   /** Eine Farbe je Akt statt je Stufe, damit 23 Stufen lesbar bleiben. */
   public static int actColor(int act) {
      return switch (act) {
         case 1 -> 0xFF4F86FF;
         case 2 -> 0xFFF0A32B;
         case 3 -> 0xFFB565FF;
         default -> 0xFFEE5A5F;
      };
   }
}
