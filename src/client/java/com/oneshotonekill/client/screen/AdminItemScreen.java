package com.oneshotonekill.client.screen;

import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.oneshotonekill.client.OsokClient;
import com.oneshotonekill.item.SpecialItem;
import com.oneshotonekill.network.OsokPayloads.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.lwjgl.glfw.GLFW;

/**
 * Modernes Admin-Arsenal: Kategorisierte Schnellausgabe aller Spezialitems und Fähigkeiten.
 */
public final class AdminItemScreen extends Screen {
   private static final int CARD_WIDTH = 580;
   private static final int CONTENT_HEIGHT = 270;
   private static final int MIN_CONTENT_HEIGHT = 100;
   private static final int CHROME_HEIGHT = 156;
   private static final int ROW_HEIGHT = 36;
   private static final int BTN_WIDTH = 42;

   private static int rememberedScroll;
   private static ItemCategory lastSelectedCategory = ItemCategory.ALL;

   private final List<Hotspot> hotspots = new ArrayList<>();
   private ItemCategory currentCategory = lastSelectedCategory;
   private String searchQuery = "";
   private boolean searchFocused = false;

   private int cardLeft;
   private int cardTop;
   private int cardHeight;
   private int listTop;
   private int listHeight;
   private int contentLength;
   private int scroll = rememberedScroll;

   public enum ItemCategory {
      ALL("Alle", OsokWidgets.COLOR_GOLD),
      WEAPONS("Waffen", OsokWidgets.COLOR_CYAN),
      ABILITIES("Fähigkeiten", OsokWidgets.COLOR_EMERALD),
      DEPLOYABLES("Platzierbar", OsokWidgets.COLOR_AMBER),
      STREAKS("Killstreaks", OsokWidgets.COLOR_PURPLE);

      private final String label;
      private final int accent;

      ItemCategory(String label, int accent) {
         this.label = label;
         this.accent = accent;
      }

      public boolean matches(SpecialItem item) {
         if (this == ALL) return true;
         return getCategoryFor(item) == this;
      }
   }

   public AdminItemScreen() {
      super(Component.literal("Spezialitems-Arsenal"));
   }

   public static ItemCategory getCategoryFor(SpecialItem item) {
      return switch (item) {
         case MINIGUN, RAILGUN, EXPLOSIVE_SHOT, CHAIN_LIGHTNING -> ItemCategory.WEAPONS;
         case RADAR_PULSE, REFLECTOR_SHIELD, INVISIBILITY_CLOAK, ARROW_MAGNET, SINGULARITY, GLIDER, SLOW_MOTION -> ItemCategory.ABILITIES;
         case C4, FROST_TRAP, SENTRY_TURRET, SMOKE_BOMB, TELEPORT_GRENADE -> ItemCategory.DEPLOYABLES;
         case STEALTH_BOMBER, AIRSTRIKE -> ItemCategory.STREAKS;
      };
   }

   public static String getItemDescription(SpecialItem item) {
      return switch (item) {
         case RADAR_PULSE -> "Deckt alle Gegner in der Arena kurzzeitig auf.";
         case EXPLOSIVE_SHOT -> "Hochexplosive Munition mit Flächenschaden.";
         case REFLECTOR_SHIELD -> "Wirft ankommende Projektile und Pfeile direkt zurück.";
         case SMOKE_BOMB -> "Erzeugt eine dichte, sichtbehindernde Rauchwolke.";
         case FROST_TRAP -> "Friert Gegner bei Kontakt ein und verlangsamt sie.";
         case MINIGUN -> "Schwere Minigun mit rotierenden Läufen und extremer Kadenz.";
         case TELEPORT_GRENADE -> "Teleportiert den Werfer sofort zum Aufschlagpunkt.";
         case INVISIBILITY_CLOAK -> "Macht für begrenzte Zeit vollkommen unsichtbar.";
         case ARROW_MAGNET -> "Zieht feindliche Pfeile magnetisch an und fängt sie ab.";
         case CHAIN_LIGHTNING -> "Tödlicher Blitzstrahl, der auf nahe Feinde überspringt.";
         case STEALTH_BOMBER -> "Tarnkappenbomber-Luftschlag auf ein ausgewähltes Ziel.";
         case AIRSTRIKE -> "Taktisches Radar-Terminal für gezielte Bombenteppiche.";
         case C4 -> "Fernzündbare Sprengladung mit massiver Zerstörungskraft.";
         case RAILGUN -> "Präziser Hochgeschwindigkeits-Sofortstrahl durch Wände.";
         case SINGULARITY -> "Erzeugt ein Schwarzes Loch, das Feinde und Schüsse anzieht.";
         case GLIDER -> "Taktischer Hängegleiter für hohe Mobilität in der Luft.";
         case SENTRY_TURRET -> "Automatischer Geschützturm mit Zielerfassung.";
         case SLOW_MOTION -> "Verlangsamt den gesamten Zeitfluss für sieben Sekunden.";
      };
   }

   @Override
   protected void init() {
      listHeight = Math.max(MIN_CONTENT_HEIGHT, Math.min(CONTENT_HEIGHT, height - CHROME_HEIGHT));
      cardHeight = listHeight + CHROME_HEIGHT;
      cardLeft = Math.max(4, width / 2 - CARD_WIDTH / 2);
      cardTop = Math.max(4, height / 2 - cardHeight / 2);
      listTop = cardTop + 84;

      updateContentLength();
      int overflow = contentLength - listHeight;
      scroll = overflow > 0 ? Math.max(0, Math.min(overflow, scroll)) : 0;
   }

   private void updateContentLength() {
      List<SpecialItem> filtered = getFilteredItems();
      contentLength = filtered.size() * (ROW_HEIGHT + 4);
   }

   private List<SpecialItem> getFilteredItems() {
      List<SpecialItem> list = new ArrayList<>();
      String query = searchQuery.trim().toLowerCase(Locale.ROOT);
      for (SpecialItem item : SpecialItem.values()) {
         if (!currentCategory.matches(item)) {
            continue;
         }
         if (!query.isEmpty() && !item.getDisplayName().toLowerCase(Locale.ROOT).contains(query)) {
            continue;
         }
         list.add(item);
      }
      return list;
   }

   @Override
   public boolean isPauseScreen() {
      return false;
   }

   @Override
   public boolean keyPressed(KeyEvent event) {
      if (searchFocused) {
         if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
            if (!searchQuery.isEmpty()) {
               searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
               scroll = 0;
               updateContentLength();
            }
            return true;
         } else if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_ENTER) {
            searchFocused = false;
            return true;
         }
      }

      if (OsokClient.isAdminMenuKey(event)) {
         onClose();
         return true;
      }
      return super.keyPressed(event);
   }

   @Override
   public boolean charTyped(CharacterEvent event) {
      if (searchFocused && event.isAllowedChatCharacter()) {
         searchQuery += event.codepointAsString();
         scroll = 0;
         updateContentLength();
         return true;
      }
      return super.charTyped(event);
   }

   @Override
   public void removed() {
      rememberedScroll = scroll;
      lastSelectedCategory = currentCategory;
      super.removed();
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
      graphics.fill(0, 0, width, height, OsokWidgets.COLOR_SCRIM);
      OsokWidgets.glassCard(graphics, cardLeft, cardTop, cardLeft + CARD_WIDTH, cardTop + cardHeight, false, 0);

      hotspots.clear();

      // Header mit Brand-Logo
      graphics.text(font, "✦ OneShotOneKill", cardLeft + 16, cardTop + 14, OsokWidgets.COLOR_GOLD);
      graphics.text(font, "• Admin-Spezialitem-Arsenal", cardLeft + 16 + font.width("✦ OneShotOneKill ") + 4, cardTop + 14, OsokWidgets.COLOR_TEXT_MUTED);

      // Status Badge: Anzahl gefilterter Items
      List<SpecialItem> filteredItems = getFilteredItems();
      String countText = filteredItems.size() + (filteredItems.size() == 1 ? " Item" : " Items");
      OsokWidgets.statusBadge(graphics, font, cardLeft + CARD_WIDTH - 16 - (font.width(countText) + 18), cardTop + 12, countText, currentCategory.accent, false);

      // Kategorie-Tabs & Suchleiste (Zeile 2)
      drawCategoryTabsAndSearch(graphics, mouseX, mouseY);
      OsokWidgets.divider(graphics, cardLeft + 16, cardLeft + CARD_WIDTH - 16, listTop - 6, OsokWidgets.COLOR_CARD_BORDER);

      // Item-Liste im Scissor-Bereich
      int left = cardLeft + 16;
      int right = cardLeft + CARD_WIDTH - 16;

      graphics.enableScissor(left, listTop, right, listTop + listHeight);
      int y = listTop - scroll;

      SpecialItem hoveredItemForTooltip = null;

      if (filteredItems.isEmpty()) {
         graphics.centeredText(font, Component.literal("Keine Items gefunden für '" + searchQuery + "'"), (left + right) / 2, listTop + listHeight / 2 - 4, OsokWidgets.COLOR_TEXT_FAINT);
      } else {
         for (SpecialItem item : filteredItems) {
            boolean rowHovered = OsokWidgets.isOver(mouseX, mouseY, left, y, right - left, ROW_HEIGHT)
               && mouseY >= listTop && mouseY < listTop + listHeight;

            ItemCategory cat = getCategoryFor(item);
            int rowBg = rowHovered ? 0xFF222B3D : 0xFF141924;
            int rowBorder = rowHovered ? cat.accent : OsokWidgets.COLOR_CARD_BORDER;

            // Kartenkörper
            graphics.fill(left, y, right, y + ROW_HEIGHT, rowBg);
            graphics.horizontalLine(left, right - 1, y, rowBorder);
            graphics.horizontalLine(left, right - 1, y + ROW_HEIGHT - 1, rowBorder);
            graphics.verticalLine(left, y, y + ROW_HEIGHT - 1, rowBorder);
            graphics.verticalLine(right - 1, y, y + ROW_HEIGHT - 1, rowBorder);

            // Akzentleiste links
            graphics.fill(left + 2, y + 2, left + 5, y + ROW_HEIGHT - 2, cat.accent);

            // 3D Item Icon
            ItemStack stack = item.createStack();
            graphics.item(stack, left + 10, y + 10);

            // Prüfen auf Icon-Hover für Tooltip
            boolean iconHovered = OsokWidgets.isOver(mouseX, mouseY, left + 8, y + 8, 20, 20)
               && mouseY >= listTop && mouseY < listTop + listHeight;
            if (iconHovered) {
               hoveredItemForTooltip = item;
            }

            // Name & Kategorie-Tag
            graphics.text(font, item.getDisplayName(), left + 34, y + 8, OsokWidgets.COLOR_TEXT_WHITE);
            int nameWidth = font.width(item.getDisplayName());
            graphics.text(font, "• " + cat.label, left + 38 + nameWidth, y + 8, cat.accent);

            // Subtext (Kurzbeschreibung)
            graphics.text(font, getItemDescription(item), left + 34, y + 20, OsokWidgets.COLOR_TEXT_FAINT);

            // Knöpfe: "+1" und "+16"
            int btnY = y + 8;
            int btn16X = right - BTN_WIDTH - 8;
            int btn1X = btn16X - BTN_WIDTH - 6;

            boolean btn1Hover = OsokWidgets.isOver(mouseX, mouseY, btn1X, btnY, BTN_WIDTH, 20)
               && mouseY >= listTop && mouseY < listTop + listHeight;
            boolean btn16Hover = OsokWidgets.isOver(mouseX, mouseY, btn16X, btnY, BTN_WIDTH, 20)
               && mouseY >= listTop && mouseY < listTop + listHeight;

            OsokWidgets.cyberButton(graphics, font, btn1X, btnY, BTN_WIDTH, 20, "+1", true, btn1Hover, OsokWidgets.COLOR_GOLD);
            OsokWidgets.cyberButton(graphics, font, btn16X, btnY, BTN_WIDTH, 20, "+16", true, btn16Hover, cat.accent);

            hotspots.add(new Hotspot(btn1X, btnY, BTN_WIDTH, 20, true,
               () -> ClientPlayNetworking.send(new GiveSpecialItemPayload(item.getId()))));
            hotspots.add(new Hotspot(btn16X, btnY, BTN_WIDTH, 20, true, () -> {
               for (int i = 0; i < 16; i++) {
                  ClientPlayNetworking.send(new GiveSpecialItemPayload(item.getId()));
               }
            }));

            y += ROW_HEIGHT + 4;
         }
      }
      contentLength = y + scroll - listTop;
      graphics.disableScissor();

      drawScrollbar(graphics, right);

      // Footer
      int footerY = listTop + listHeight + 8;
      OsokWidgets.divider(graphics, cardLeft + 16, cardLeft + CARD_WIDTH - 16, footerY, OsokWidgets.COLOR_CARD_BORDER);

      String keyName = OsokClient.adminMenuKeyName().getString();
      graphics.text(font, "Schnelltaste [" + keyName + "] oder [ESC] schließt · Klick auf [+1] / [+16] gibt Items", cardLeft + 16, footerY + 12, OsokWidgets.COLOR_TEXT_FAINT);

      int closeWidth = 84;
      int closeX = cardLeft + CARD_WIDTH - 16 - closeWidth;
      boolean closeHover = OsokWidgets.isOver(mouseX, mouseY, closeX, footerY + 6, closeWidth, 20);
      OsokWidgets.cyberButton(graphics, font, closeX, footerY + 6, closeWidth, 20, "Schließen", true, closeHover, OsokWidgets.COLOR_CARD_BORDER);
      hotspots.add(new Hotspot(closeX, footerY + 6, closeWidth, 20, false, this::onClose));

      // Tooltip ganz oben zeichnen falls Icon gehovert
      if (hoveredItemForTooltip != null) {
         ItemCategory cat = getCategoryFor(hoveredItemForTooltip);
         List<Component> tooltip = List.of(
            Component.literal(hoveredItemForTooltip.getDisplayName()).withColor(cat.accent),
            Component.literal("Kategorie: " + cat.label).withColor(OsokWidgets.COLOR_TEXT_MUTED),
            Component.empty(),
            Component.literal(getItemDescription(hoveredItemForTooltip)).withColor(OsokWidgets.COLOR_TEXT_WHITE),
            Component.empty(),
            Component.literal("💡 [+1] = 1 Stück · [+16] = 16 Stück").withColor(OsokWidgets.COLOR_TEXT_FAINT)
         );
         graphics.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
      }

      super.extractRenderState(graphics, mouseX, mouseY, partial);
      updateCursor(graphics, mouseX, mouseY);
   }

   private void drawCategoryTabsAndSearch(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
      int x = cardLeft + 16;
      int y = cardTop + 36;

      // Kategorie-Tabs
      for (ItemCategory cat : ItemCategory.values()) {
         int tabWidth = font.width(cat.label) + 14;
         boolean isActive = currentCategory == cat;
         boolean hovered = OsokWidgets.isOver(mouseX, mouseY, x, y, tabWidth, 20);

         OsokWidgets.tabHeader(graphics, font, x, y, tabWidth, 20, cat.label, isActive, hovered, cat.accent);
         hotspots.add(new Hotspot(x, y, tabWidth, 20, false, () -> {
            currentCategory = cat;
            scroll = 0;
            updateContentLength();
         }));
         x += tabWidth + 4;
      }

      // Suchfeld rechts
      int searchWidth = 140;
      int searchX = cardLeft + CARD_WIDTH - 16 - searchWidth;
      boolean searchHover = OsokWidgets.isOver(mouseX, mouseY, searchX, y, searchWidth, 20);
      OsokWidgets.searchInput(graphics, font, searchX, y, searchWidth, 20, searchQuery, "Suchen…", searchFocused, searchHover);

      hotspots.add(new Hotspot(searchX, y, searchWidth, 20, false, CursorTypes.IBEAM,
         () -> searchFocused = true));
   }

   private void drawScrollbar(GuiGraphicsExtractor graphics, int right) {
      int trackX = cardLeft + CARD_WIDTH - 9;
      OsokWidgets.scrollbar(graphics, trackX, listTop, listHeight, contentLength, listHeight, scroll, OsokWidgets.COLOR_GOLD);
   }

   @Override
   public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      if (event.button() == 0) {
         boolean hitSearch = false;
         for (Hotspot hotspot : hotspots) {
            if (OsokWidgets.isOver(event.x(), event.y(), hotspot.x, hotspot.y, hotspot.width, hotspot.height)) {
               if (hotspot.scrollable && (event.y() < listTop || event.y() >= listTop + listHeight)) {
                  continue;
               }
               Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
               hotspot.action.run();
               hitSearch = true;
               break;
            }
         }
         if (!hitSearch) {
            searchFocused = false;
         }
         return true;
      }
      return super.mouseClicked(event, doubleClick);
   }

   @Override
   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      int overflow = contentLength - listHeight;
      if (overflow > 0) {
         scroll = Math.max(0, Math.min(overflow, scroll - (int) (scrollY * 18)));
         return true;
      }
      return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
   }

   private void updateCursor(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
      graphics.requestCursor(CursorTypes.ARROW);
      for (int i = hotspots.size() - 1; i >= 0; i--) {
         Hotspot hotspot = hotspots.get(i);
         if (!OsokWidgets.isOver(mouseX, mouseY, hotspot.x, hotspot.y, hotspot.width, hotspot.height)) {
            continue;
         }
         if (hotspot.scrollable && (mouseY < listTop || mouseY >= listTop + listHeight)) {
            continue;
         }
         graphics.requestCursor(hotspot.cursor);
         return;
      }
   }

   private record Hotspot(int x, int y, int width, int height, boolean scrollable, CursorType cursor,
                          Runnable action) {
      private Hotspot(int x, int y, int width, int height, boolean scrollable, Runnable action) {
         this(x, y, width, height, scrollable, CursorTypes.POINTING_HAND, action);
      }
   }
}
