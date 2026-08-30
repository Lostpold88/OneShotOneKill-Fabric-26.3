package com.oneshotonekill.client.screen;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.oneshotonekill.network.OsokPayloads.*;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Einsatzfreigabe des Tarnkappenbombers: eine Liste der Gegner in der Arena.
 * <p>
 * Vorher suchte sich der Bomber sein Ziel selbst – wer am nächsten an der Blickachse lag, bekam
 * ihn. Das war schnell, aber blind: hinter einer Wand oder im Rücken stehende Gegner waren
 * unerreichbar, und wen man wirklich getroffen hatte, sah man erst hinterher. Die Liste kommt
 * vom Server und nennt Entfernung, Richtung und laufende Killserie jedes Gegners, damit die
 * Wahl auf etwas beruht.
 */
@SuppressWarnings("ConstantValue")
public final class BomberTargetScreen extends Screen {
   private static final int OVERLAY = 0xB8000000;
   private static final int PANEL = 0xF0191B1D;
   private static final int PANEL_BORDER = 0xFF5B6063;
   private static final int HEADER = 0xFF242526;
   private static final int HEADER_ACCENT = 0xFF6B4A7F;
   private static final int TITLE = 0xFFE4E0D5;
   private static final int SUBTITLE = 0xFFB9A9C5;
   private static final int ROW = 0xFF25282A;
   private static final int ROW_HOVER = 0xFF34383A;
   private static final int ROW_BORDER = 0xFF3C4145;
   private static final int NAME = 0xFFE8EAF2;
   private static final int DETAIL = 0xFF9AA0A6;
   private static final int ACCENT = 0xFFA779C4;
   /** Der eigene Eintrag hebt sich ab – sonst ordert man sich das Bombardement versehentlich. */
   private static final int SELF_ACCENT = 0xFFC4553C;
   private static final int SELF_NAME = 0xFFE8A08D;
   private static final int STREAK_HOT = 0xFFE0813C;
   private static final int HINT = 0xFFB5B8B5;

   private static final int PANEL_WIDTH = 260;
   private static final int ROW_HEIGHT = 24;
   private static final int ROW_GAP = 3;
   private static final int HEADER_HEIGHT = 30;
   private static final int FOOTER_HEIGHT = 20;
   /** Ab dieser Killserie wird ein Gegner farblich als lohnendes Ziel hervorgehoben. */
   private static final int NOTEWORTHY_STREAK = 3;

   private final List<BomberTargetsPayload.Target> targets;

   private int panelLeft;
   private int panelTop;
   private int panelHeight;
   private int hovered = -1;
   private boolean launched;

   private BomberTargetScreen(List<BomberTargetsPayload.Target> targets) {
      super(Component.literal("Tarnkappenbomber"));
      this.targets = targets;
   }

   /** Wird aufgerufen, sobald die vom Server zusammengestellte Zielliste eintrifft. */
   public static void show(BomberTargetsPayload payload, Minecraft client) {
      if (payload.targets().isEmpty()) {
         return;
      }
      client.gui.setScreen(new BomberTargetScreen(payload.targets()));
   }

   @Override
   protected void init() {
      panelHeight = HEADER_HEIGHT + FOOTER_HEIGHT + targets.size() * (ROW_HEIGHT + ROW_GAP) + ROW_GAP;
      panelLeft = (width - PANEL_WIDTH) / 2;
      panelTop = (height - panelHeight) / 2;
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
      graphics.fill(0, 0, width, height, OVERLAY);

      OsokWidgets.panel(graphics, panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + panelHeight, PANEL, PANEL_BORDER);
      graphics.fill(panelLeft + 1, panelTop + 1, panelLeft + PANEL_WIDTH - 1, panelTop + HEADER_HEIGHT, HEADER);
      graphics.fill(panelLeft + 1, panelTop + HEADER_HEIGHT - 1, panelLeft + PANEL_WIDTH - 1, panelTop + HEADER_HEIGHT, HEADER_ACCENT);
      graphics.text(font, "🐉 TARNKAPPENBOMBER", panelLeft + 10, panelTop + 7, TITLE);
      graphics.text(font, "ZIEL WÄHLEN", panelLeft + 10, panelTop + 18, SUBTITLE);

      hovered = -1;
      for (int index = 0; index < targets.size(); index++) {
         renderRow(graphics, index, mouseX, mouseY);
      }

      graphics.centeredText(font, Component.literal("Klicken zum Anordnen · ESC bricht ab"),
         panelLeft + PANEL_WIDTH / 2, panelTop + panelHeight - 13, HINT);

      super.extractRenderState(graphics, mouseX, mouseY, partial);
      graphics.requestCursor(hovered >= 0 ? CursorTypes.POINTING_HAND : CursorTypes.ARROW);
   }

   private void renderRow(GuiGraphicsExtractor graphics, int index, int mouseX, int mouseY) {
      BomberTargetsPayload.Target target = targets.get(index);
      int x = panelLeft + 6;
      int y = panelTop + HEADER_HEIGHT + ROW_GAP + index * (ROW_HEIGHT + ROW_GAP);
      int rowWidth = PANEL_WIDTH - 12;
      boolean over = OsokWidgets.isOver(mouseX, mouseY, x, y, rowWidth, ROW_HEIGHT);
      boolean self = isSelf(target);
      if (over) {
         hovered = index;
      }

      OsokWidgets.panel(graphics, x, y, x + rowWidth, y + ROW_HEIGHT, over ? ROW_HOVER : ROW, ROW_BORDER);
      graphics.fill(x + 2, y + 2, x + 4, y + ROW_HEIGHT - 2, self ? SELF_ACCENT : ACCENT);
      graphics.text(font, target.name(), x + 9, y + 4, self ? SELF_NAME : NAME);

      String detail = self ? "du selbst" : distanceLabel(target) + " · " + compass(target);
      graphics.text(font, detail, x + 9, y + 14, DETAIL);

      if (target.killstreak() > 0) {
         String streak = "SERIE " + target.killstreak();
         graphics.text(font, streak, x + rowWidth - 8 - font.width(streak), y + 9,
            target.killstreak() >= NOTEWORTHY_STREAK ? STREAK_HOT : DETAIL);
      }
   }

   private boolean isSelf(BomberTargetsPayload.Target target) {
      LocalPlayer player = minecraft == null ? null : minecraft.player;
      return player != null && player.getUUID().equals(target.id());
   }

   private String distanceLabel(BomberTargetsPayload.Target target) {
      LocalPlayer player = minecraft == null ? null : minecraft.player;
      if (player == null) {
         return "—";
      }
      return Math.round(Math.hypot(target.x() - player.getX(), target.z() - player.getZ())) + " m";
   }

   /**
    * Richtung zum Ziel, bezogen auf die eigene Blickrichtung.
    * <p>
    * Ein Winkel in Grad wäre genauer, aber im Gefecht nicht lesbar; „vorne rechts“ genügt, um
    * einen Eintrag der Liste einem Gegner im Blickfeld zuzuordnen.
    * <p>
    * Minecraft misst den Gierwinkel von Süden aus und steigend nach Westen – die Richtung zu
    * einem Punkt ist deshalb {@code atan2(-dx, dz)}, und ein positiver Unterschied zur eigenen
    * Blickrichtung liegt rechts von einem.
    */
   private String compass(BomberTargetsPayload.Target target) {
      LocalPlayer player = minecraft == null ? null : minecraft.player;
      if (player == null) {
         return "";
      }
      double towards = Math.toDegrees(Math.atan2(player.getX() - target.x(), target.z() - player.getZ()));
      double relative = Mth.wrapDegrees(towards - player.getYRot());
      double side = Math.abs(relative);

      if (side < 22.5) {
         return "voraus";
      }
      if (side >= 157.5) {
         return "im Rücken";
      }
      String hand = relative > 0 ? "rechts" : "links";
      if (side < 67.5) {
         return "vorne " + hand;
      }
      return side < 112.5 ? hand : "hinten " + hand;
   }

   @Override
   public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      if (event.button() == 0 && hovered >= 0 && hovered < targets.size()) {
         // Nur einmal auslösen: ein zweiter Klick vor dem Schließen wäre ein zweiter Bomber
         // für ein Item, das der Server nur einmal einzieht.
         if (!launched) {
            launched = true;
            ClientPlayNetworking.send(new SelectBomberTargetPayload(targets.get(hovered).id()));
         }
         onClose();
         return true;
      }
      return super.mouseClicked(event, doubleClick);
   }

   @Override
   public void onClose() {
      if (minecraft != null) {
         minecraft.gui.setScreen(null);
      }
   }

   @Override
   public boolean isPauseScreen() {
      return false;
   }
}
