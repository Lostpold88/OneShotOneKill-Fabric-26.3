package com.oneshotonekill.client.screen;

import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.oneshotonekill.client.OsokClient;
import com.oneshotonekill.item.SpecialItem;
import com.oneshotonekill.network.OsokPayloads.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.lwjgl.glfw.GLFW;

/**
 * Modernes Admin-Arsenal: Kategorisierte Schnellausgabe aller Spezialitems und Fähigkeiten
 * mit Favoriten-System (⭐), Batch-Aktionen und Live-Suche.
 */
@SuppressWarnings("NullableProblems")
public final class AdminItemScreen extends Screen {
   private static final int CARD_WIDTH = 580;
   private static final int CONTENT_HEIGHT = 270;
   private static final int MIN_CONTENT_HEIGHT = 100;
   private static final int CHROME_HEIGHT = 156;
   private static final int ROW_HEIGHT = 36;
   private static final int BTN_WIDTH = 42;
   private static final int SCROLLBAR_INSET = 9;
   private static final int EDGE_FADE = 10;
   private static final long ENTRANCE_MILLIS = 150L;

   private static float rememberedScroll;
   private static ItemCategory lastSelectedCategory = ItemCategory.ALL;
   private static final Set<SpecialItem> FAVORITES = EnumSet.noneOf(SpecialItem.class);

   private final List<Hotspot> hotspots = new ArrayList<>();
   private final OsokWidgets.ScrollMotion scroll = new OsokWidgets.ScrollMotion();
   private final long openedAt = Util.getMillis();
   private ItemCategory currentCategory = lastSelectedCategory;
   private String searchQuery = "";
   private boolean searchFocused = false;

   private long lastFrameMillis = Long.MIN_VALUE;
   private float indicatorX = Float.NaN;
   private float indicatorWidth;
   private long categoryChangedAt = Long.MIN_VALUE;

   private int cardLeft;
   private int cardTop;
   private int cardHeight;
   private int listTop;
   private int listHeight;
   private int contentLength;

   public enum ItemCategory {
      ALL("Alle", OsokWidgets.COLOR_GOLD),
      FAVORITES("⭐ Favoriten", OsokWidgets.COLOR_GOLD),
      WEAPONS("Waffen", OsokWidgets.COLOR_CYAN),
      ABILITIES("Fähigkeiten", OsokWidgets.COLOR_EMERALD),
      DEPLOYABLES("Platzierbar", OsokWidgets.COLOR_AMBER),
      STREAKS("Killstreaks", OsokWidgets.COLOR_PURPLE);

      public final String label;
      public final int accent;

      ItemCategory(String label, int accent) {
         this.label = label;
         this.accent = accent;
      }

      public boolean matches(SpecialItem item, Set<SpecialItem> favorites) {
         if (this == ALL) return true;
         if (this == FAVORITES) return favorites.contains(item);
         return getCategoryFor(item) == this;
      }

      public int count(Set<SpecialItem> favorites) {
         int count = 0;
         for (SpecialItem item : SpecialItem.values()) {
            if (matches(item, favorites)) {
               count++;
            }
         }
         return count;
      }
   }

   public AdminItemScreen() {
      super(Component.literal("Spezialitems-Arsenal"));
      this.scroll.set(rememberedScroll);
   }

   public static ItemCategory getCategoryFor(SpecialItem item) {
      return switch (item) {
         case MINIGUN, RAILGUN, EXPLOSIVE_SHOT, CHAIN_LIGHTNING -> ItemCategory.WEAPONS;
         case RADAR_PULSE, REFLECTOR_SHIELD, INVISIBILITY_CLOAK, ARROW_MAGNET, SINGULARITY, GLIDER, SLOW_MOTION, GRAPPLING_HOOK -> ItemCategory.ABILITIES;
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
         case GRAPPLING_HOOK -> "Zehn Schüsse: Hakt sich an Flächen ein und zieht dich dorthin.";
      };
   }

   @Override
   protected void init() {
      listHeight = Math.clamp(height - CHROME_HEIGHT, MIN_CONTENT_HEIGHT, CONTENT_HEIGHT);
      cardHeight = listHeight + CHROME_HEIGHT;
      cardLeft = Math.max(4, width / 2 - CARD_WIDTH / 2);
      cardTop = Math.max(4, height / 2 - cardHeight / 2);
      listTop = cardTop + 84;

      updateContentLength();
      scroll.clampNow(contentLength - listHeight);
   }

   private void updateContentLength() {
      contentLength = getFilteredItems().size() * (ROW_HEIGHT + 4);
   }

   private List<SpecialItem> getFilteredItems() {
      List<SpecialItem> list = new ArrayList<>();
      String query = searchQuery.trim().toLowerCase(Locale.ROOT);
      for (SpecialItem item : SpecialItem.values()) {
         if (!currentCategory.matches(item, FAVORITES)) {
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
   public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
      graphics.blurBeforeThisStratum();
      this.minecraft.gui.hud.extractDeferredSubtitles();
   }

   @Override
   public boolean keyPressed(KeyEvent event) {
      if (searchFocused) {
         if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
            if (!searchQuery.isEmpty()) {
               searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
               scroll.set(0.0F);
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
         scroll.set(0.0F);
         updateContentLength();
         return true;
      }
      return super.charTyped(event);
   }

   @Override
   public void removed() {
      rememberedScroll = scroll.value();
      lastSelectedCategory = currentCategory;
      super.removed();
   }

   private float advanceClock() {
      long now = Util.getMillis();
      float delta = lastFrameMillis == Long.MIN_VALUE
         ? 1.0F / 60.0F
         : Math.clamp((now - lastFrameMillis) / 1000.0F, 1.0F / 480.0F, 0.1F);
      lastFrameMillis = now;
      return delta;
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
      float delta = advanceClock();
      scroll.advance(delta, contentLength - listHeight);

      graphics.fill(0, 0, width, height, OsokWidgets.COLOR_SCRIM);

      float entrance = entranceScale();
      float centerX = cardLeft + CARD_WIDTH / 2.0F;
      float centerY = cardTop + cardHeight / 2.0F;
      graphics.pose().pushMatrix();
      graphics.pose().translate(centerX, centerY);
      graphics.pose().scale(entrance, entrance);
      graphics.pose().translate(-centerX, -centerY);

      OsokWidgets.glassCard(graphics, cardLeft, cardTop, cardLeft + CARD_WIDTH, cardTop + cardHeight, false, 0);

      hotspots.clear();

      graphics.text(font, "✦ OneShotOneKill", cardLeft + 16, cardTop + 14, OsokWidgets.COLOR_GOLD);
      graphics.text(font, "• Admin-Spezialitem-Arsenal", cardLeft + 16 + font.width("✦ OneShotOneKill ") + 4, cardTop + 14, OsokWidgets.COLOR_TEXT_MUTED);

      List<SpecialItem> filteredItems = getFilteredItems();
      String countText = filteredItems.size() + (filteredItems.size() == 1 ? " Item" : " Items");
      OsokWidgets.statusBadge(graphics, font, cardLeft + CARD_WIDTH - 16 - (font.width(countText) + 18), cardTop + 12, countText, currentCategory.accent, false);

      drawCategoryTabsAndSearch(graphics, mouseX, mouseY, delta);
      OsokWidgets.divider(graphics, cardLeft + 16, cardLeft + CARD_WIDTH - 16, listTop - 6, OsokWidgets.COLOR_CARD_BORDER);

      SpecialItem hoveredItemForTooltip = drawItemList(graphics, mouseX, mouseY, filteredItems);

      int trackX = cardLeft + CARD_WIDTH - SCROLLBAR_INSET;
      boolean barHovered = mouseX >= trackX - 3 && mouseX <= trackX + 7
         && mouseY >= listTop && mouseY < listTop + listHeight;
      OsokWidgets.scrollbar(graphics, trackX, listTop, listHeight, contentLength, listHeight,
         scroll.offset(), OsokWidgets.COLOR_GOLD, barHovered, scroll.isDragging());

      int footerY = listTop + listHeight + 8;
      OsokWidgets.divider(graphics, cardLeft + 16, cardLeft + CARD_WIDTH - 16, footerY, OsokWidgets.COLOR_CARD_BORDER);

      String keyName = OsokClient.adminMenuKeyName().getString();
      graphics.text(font, "Schnelltaste [" + keyName + "] oder [ESC] schließt · ⭐ = Favorit · [+1] / [+16] = Items",
         cardLeft + 16, footerY + 12, OsokWidgets.COLOR_TEXT_FAINT);

      // Batch-Aktionen: [📦 Alle +1] & [Schließen]
      int closeWidth = 78;
      int closeX = cardLeft + CARD_WIDTH - 16 - closeWidth;
      boolean closeHover = OsokWidgets.isOver(mouseX, mouseY, closeX, footerY + 6, closeWidth, 20);
      OsokWidgets.cyberButton(graphics, font, closeX, footerY + 6, closeWidth, 20, "Schließen", true, closeHover, OsokWidgets.COLOR_CARD_BORDER);
      hotspots.add(new Hotspot(closeX, footerY + 6, closeWidth, 20, false, () -> {
         OsokWidgets.playUiClickSound();
         this.onClose();
      }));

      int giveAllWidth = 84;
      int giveAllX = closeX - giveAllWidth - 6;
      boolean giveAllHover = OsokWidgets.isOver(mouseX, mouseY, giveAllX, footerY + 6, giveAllWidth, 20);
      boolean hasItems = !filteredItems.isEmpty();
      OsokWidgets.cyberButton(graphics, font, giveAllX, footerY + 6, giveAllWidth, 20, "📦 Alle +1", hasItems, giveAllHover, OsokWidgets.COLOR_EMERALD);
      hotspots.add(new Hotspot(giveAllX, footerY + 6, giveAllWidth, 20, false, () -> {
         for (SpecialItem item : filteredItems) {
            ClientPlayNetworking.send(new GiveSpecialItemPayload(item.getId()));
         }
         OsokWidgets.playItemGiveSound();
      }));

      graphics.pose().popMatrix();

      if (hoveredItemForTooltip != null) {
         ItemCategory cat = getCategoryFor(hoveredItemForTooltip);
         boolean isFav = FAVORITES.contains(hoveredItemForTooltip);
         List<Component> tooltip = List.of(
            Component.literal((isFav ? "⭐ " : "") + hoveredItemForTooltip.getDisplayName()).withColor(cat.accent),
            Component.literal("Kategorie: " + cat.label).withColor(OsokWidgets.COLOR_TEXT_MUTED),
            Component.empty(),
            Component.literal(getItemDescription(hoveredItemForTooltip)).withColor(OsokWidgets.COLOR_TEXT_WHITE),
            Component.empty(),
            Component.literal("💡 [★] = Favorit umschalten · [+1] = 1 Stück · [+16] = 16 Stück").withColor(OsokWidgets.COLOR_TEXT_FAINT)
         );
         graphics.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
      }

      super.extractRenderState(graphics, mouseX, mouseY, partial);
      updateCursor(graphics, mouseX, mouseY);
   }

   private float entranceScale() {
      float progress = Math.clamp((Util.getMillis() - openedAt) / (float) ENTRANCE_MILLIS, 0.0F, 1.0F);
      if (progress >= 1.0F) {
         return 1.0F;
      }
      float back = progress - 1.0F;
      float eased = 1.0F + back * back * (2.0F * back + 1.0F);
      return 0.95F + 0.05F * eased;
   }

   private SpecialItem drawItemList(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                    List<SpecialItem> filteredItems) {
      int left = cardLeft + 16;
      int right = cardLeft + CARD_WIDTH - 16;
      int listBottom = listTop + listHeight;

      graphics.enableScissor(left, listTop, right, listBottom);

      int slide = tabSlideOffset();
      graphics.pose().pushMatrix();
      graphics.pose().translate(slide, 0.0F);

      int y = listTop - scroll.offset();
      SpecialItem hoveredItemForTooltip = null;

      if (filteredItems.isEmpty()) {
         String emptyMsg = currentCategory == ItemCategory.FAVORITES
            ? "Noch keine Favoriten markiert (Klicke auf ★ bei einem Item)"
            : "Keine Items gefunden für '" + searchQuery + "'";
         graphics.centeredText(font, Component.literal(emptyMsg), (left + right) / 2, listTop + listHeight / 2 - 4, OsokWidgets.COLOR_TEXT_FAINT);
      } else {
         for (SpecialItem item : filteredItems) {
            boolean rowHovered = OsokWidgets.isOver(mouseX, mouseY, left, y, right - left, ROW_HEIGHT)
               && mouseY >= listTop && mouseY < listBottom;

            ItemCategory cat = getCategoryFor(item);
            boolean isFav = FAVORITES.contains(item);
            int rowBg = rowHovered ? 0xFF222B3D : 0xFF141924;
            int rowBorder = rowHovered ? cat.accent : OsokWidgets.COLOR_CARD_BORDER;

            graphics.fill(left, y, right, y + ROW_HEIGHT, rowBg);
            graphics.horizontalLine(left, right - 1, y, rowBorder);
            graphics.horizontalLine(left, right - 1, y + ROW_HEIGHT - 1, rowBorder);
            graphics.verticalLine(left, y, y + ROW_HEIGHT - 1, rowBorder);
            graphics.verticalLine(right - 1, y, y + ROW_HEIGHT - 1, rowBorder);

            // Akzent-Kante links (Gold wenn Favorit)
            graphics.fill(left + 2, y + 2, left + 5, y + ROW_HEIGHT - 2, isFav ? OsokWidgets.COLOR_GOLD : cat.accent);

            // Favoriten-Stern
            int starX = left + 9;
            int starY = y + 13;
            boolean starHovered = OsokWidgets.isOver(mouseX, mouseY, starX - 2, starY - 2, 14, 14)
               && mouseY >= listTop && mouseY < listBottom;
            int starColor = isFav ? OsokWidgets.COLOR_GOLD : (starHovered ? OsokWidgets.COLOR_TEXT_WHITE : OsokWidgets.COLOR_TEXT_FAINT);
            graphics.text(font, isFav ? "★" : "☆", starX, starY, starColor);
            hotspots.add(new Hotspot(starX - 2, starY - 2, 14, 14, true, () -> {
               if (FAVORITES.contains(item)) {
                  FAVORITES.remove(item);
               } else {
                  FAVORITES.add(item);
               }
               OsokWidgets.playActionSound();
               updateContentLength();
            }));

            // Item-Icon
            ItemStack stack = item.createStack();
            graphics.item(stack, left + 24, y + 10);

            boolean iconHovered = OsokWidgets.isOver(mouseX, mouseY, left + 22, y + 8, 20, 20)
               && mouseY >= listTop && mouseY < listBottom;
            if (iconHovered) {
               hoveredItemForTooltip = item;
            }

            graphics.text(font, item.getDisplayName(), left + 48, y + 8, OsokWidgets.COLOR_TEXT_WHITE);
            int nameWidth = font.width(item.getDisplayName());
            graphics.text(font, "• " + cat.label, left + 52 + nameWidth, y + 8, cat.accent);
            graphics.text(font, getItemDescription(item), left + 48, y + 20, OsokWidgets.COLOR_TEXT_FAINT);

            int btnY = y + 8;
            int btn16X = right - BTN_WIDTH - 8;
            int btn1X = btn16X - BTN_WIDTH - 6;

            boolean btn1Hover = OsokWidgets.isOver(mouseX, mouseY, btn1X, btnY, BTN_WIDTH, 20)
               && mouseY >= listTop && mouseY < listBottom;
            boolean btn16Hover = OsokWidgets.isOver(mouseX, mouseY, btn16X, btnY, BTN_WIDTH, 20)
               && mouseY >= listTop && mouseY < listBottom;

            OsokWidgets.cyberButton(graphics, font, btn1X, btnY, BTN_WIDTH, 20, "+1", true, btn1Hover, OsokWidgets.COLOR_GOLD);
            OsokWidgets.cyberButton(graphics, font, btn16X, btnY, BTN_WIDTH, 20, "+16", true, btn16Hover, cat.accent);

            hotspots.add(new Hotspot(btn1X, btnY, BTN_WIDTH, 20, true, () -> {
               ClientPlayNetworking.send(new GiveSpecialItemPayload(item.getId()));
               OsokWidgets.playItemGiveSound();
            }));
            hotspots.add(new Hotspot(btn16X, btnY, BTN_WIDTH, 20, true, () -> {
               for (int i = 0; i < 16; i++) {
                  ClientPlayNetworking.send(new GiveSpecialItemPayload(item.getId()));
               }
               OsokWidgets.playItemGiveSound();
            }));

            y += ROW_HEIGHT + 4;
         }
      }
      contentLength = y + scroll.offset() - listTop;

      graphics.pose().popMatrix();
      OsokWidgets.drawSoftScrollEdges(graphics, left, right, listTop, listBottom, EDGE_FADE,
         OsokWidgets.COLOR_CARD_BG, scroll.offset(), contentLength, listBottom - listTop);
      graphics.disableScissor();
      return hoveredItemForTooltip;
   }

   private int tabSlideOffset() {
      if (categoryChangedAt == Long.MIN_VALUE) {
         return 0;
      }
      float progress = Math.clamp((Util.getMillis() - categoryChangedAt) / 180.0F, 0.0F, 1.0F);
      float eased = 1.0F - (1.0F - progress) * (1.0F - progress);
      return Math.round((1.0F - eased) * 12.0F);
   }

   private void drawCategoryTabsAndSearch(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      int x = cardLeft + 16;
      int y = cardTop + 36;

      float activeX = x;
      float activeWidth = 0.0F;

      for (ItemCategory cat : ItemCategory.values()) {
         int count = cat.count(FAVORITES);
         String tabTitle = cat == ItemCategory.FAVORITES
            ? (count > 0 ? "⭐ " + count : "⭐")
            : cat.label + " (" + count + ")";
         int tabWidth = font.width(tabTitle) + 12;
         boolean isActive = currentCategory == cat;
         boolean hovered = OsokWidgets.isOver(mouseX, mouseY, x, y, tabWidth, 20);

         OsokWidgets.tabHeader(graphics, font, x, y, tabWidth, 20, tabTitle, isActive, hovered, cat.accent, false);
         if (isActive) {
            activeX = x;
            activeWidth = tabWidth;
         }
         int tabX = x;
         hotspots.add(new Hotspot(tabX, y, tabWidth, 20, false, () -> selectCategory(cat)));
         x += tabWidth + 3;
      }

      if (Float.isNaN(indicatorX)) {
         indicatorX = activeX;
         indicatorWidth = activeWidth;
      } else {
         float rate = 1.0F - (float) Math.exp(-delta * 24.0F);
         indicatorX += (activeX - indicatorX) * rate;
         indicatorWidth += (activeWidth - indicatorWidth) * rate;
      }
      OsokWidgets.floatingTabIndicator(graphics, indicatorX, y + 18.0F, indicatorWidth, 2.0F,
         currentCategory.accent);

      int searchWidth = 130;
      int searchX = cardLeft + CARD_WIDTH - 16 - searchWidth;
      boolean searchHover = OsokWidgets.isOver(mouseX, mouseY, searchX, y, searchWidth, 20);
      OsokWidgets.searchInput(graphics, font, searchX, y, searchWidth, 20, searchQuery, "Suchen…", searchFocused, searchHover);

      hotspots.add(new Hotspot(searchX, y, searchWidth, 20, false, CursorTypes.IBEAM,
         () -> searchFocused = true));
   }

   private void selectCategory(ItemCategory category) {
      if (currentCategory == category) {
         return;
      }
      currentCategory = category;
      categoryChangedAt = Util.getMillis();
      scroll.set(0.0F);
      updateContentLength();
      OsokWidgets.playTabSwitchSound();
   }

   @Override
   public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      if (event.button() == 0) {
         if (scroll.beginDrag(event.x(), event.y(), cardLeft + CARD_WIDTH - SCROLLBAR_INSET,
            listTop, listHeight, contentLength)) {
            return true;
         }

         int searchWidth = 130;
         int searchX = cardLeft + CARD_WIDTH - 16 - searchWidth;
         int searchY = cardTop + 36;
         if (!searchQuery.isEmpty() && OsokWidgets.isSearchClearHovered(event.x(), event.y(), searchX, searchY, searchWidth, 20)) {
            searchQuery = "";
            scroll.set(0.0F);
            updateContentLength();
            OsokWidgets.playClearSound();
            return true;
         }

         boolean hitSomething = false;
         for (Hotspot hotspot : hotspots) {
            if (OsokWidgets.isOver(event.x(), event.y(), hotspot.x, hotspot.y, hotspot.width, hotspot.height)) {
               if (hotspot.scrollable && (event.y() < listTop || event.y() >= listTop + listHeight)) {
                  continue;
               }
               hotspot.action.run();
               hitSomething = true;
               break;
            }
         }
         if (!hitSomething) {
            searchFocused = false;
         }
         return true;
      }
      return super.mouseClicked(event, doubleClick);
   }

   @Override
   public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
      if (event.button() == 0 && scroll.isDragging()) {
         scroll.drag(event.y(), listTop, listHeight, contentLength);
         return true;
      }
      return super.mouseDragged(event, deltaX, deltaY);
   }

   @Override
   public boolean mouseReleased(MouseButtonEvent event) {
      if (event.button() == 0) {
         scroll.endDrag();
      }
      return super.mouseReleased(event);
   }

   @Override
   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      int overflow = contentLength - listHeight;
      if (overflow > 0) {
         scroll.scrollBy((float) -scrollY * 26.0F, overflow);
         return true;
      }
      return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
   }

   private void updateCursor(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
      graphics.requestCursor(CursorTypes.ARROW);

      if (scroll.isDragging()) {
         graphics.requestCursor(CursorTypes.RESIZE_NS);
         return;
      }
      int trackX = cardLeft + CARD_WIDTH - SCROLLBAR_INSET;
      if (contentLength > listHeight && mouseX >= trackX - 3 && mouseX <= trackX + 7
         && mouseY >= listTop && mouseY < listTop + listHeight) {
         graphics.requestCursor(CursorTypes.POINTING_HAND);
         return;
      }

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
