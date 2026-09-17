package com.oneshotonekill.client.screen;

import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.client.OsokClient;
import com.oneshotonekill.item.SpecialItem;
import com.oneshotonekill.network.OsokPayloads.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.mojang.blaze3d.platform.InputConstants;

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
   private final OsokWidgets.TabIndicator tabIndicator = new OsokWidgets.TabIndicator();
   private long categoryChangedAt = Long.MIN_VALUE;

   private int cardLeft;
   private int cardTop;
   private int cardHeight;
   private int listTop;
   private int listHeight;
   private int contentLength;

   public enum ItemCategory {
      ALL("gui.oneshotonekill.admin.cat_all", OsokWidgets.COLOR_GOLD),
      FAVORITES("gui.oneshotonekill.admin.cat_favorites", OsokWidgets.COLOR_GOLD),
      WEAPONS("gui.oneshotonekill.admin.cat_weapons", OsokWidgets.COLOR_CYAN),
      ABILITIES("gui.oneshotonekill.admin.cat_abilities", OsokWidgets.COLOR_EMERALD),
      DEPLOYABLES("gui.oneshotonekill.admin.cat_deployables", OsokWidgets.COLOR_AMBER),
      STREAKS("gui.oneshotonekill.admin.cat_streaks", OsokWidgets.COLOR_PURPLE);

      public final String translationKey;
      public final int accent;

      ItemCategory(String translationKey, int accent) {
         this.translationKey = translationKey;
         this.accent = accent;
      }

      public String label() {
         return Component.translatable(translationKey).getString();
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
      super(Component.translatable("gui.oneshotonekill.admin.title"));
      this.scroll.set(rememberedScroll);
   }

   public static ItemCategory getCategoryFor(SpecialItem item) {
      if (item == null) return ItemCategory.DEPLOYABLES;
      return switch (item) {
         case MINIGUN, RAILGUN, EXPLOSIVE_SHOT, CHAIN_LIGHTNING -> ItemCategory.WEAPONS;
         case RADAR_PULSE, REFLECTOR_SHIELD, INVISIBILITY_CLOAK, ARROW_MAGNET, SINGULARITY, GLIDER, SLOW_MOTION, GRAPPLING_HOOK -> ItemCategory.ABILITIES;
         case C4, FROST_TRAP, SENTRY_TURRET, SMOKE_BOMB, TELEPORT_GRENADE, BOOGIE_BOMB -> ItemCategory.DEPLOYABLES;
         case STEALTH_BOMBER, AIRSTRIKE -> ItemCategory.STREAKS;
      };
   }

   public static String getItemDescription(SpecialItem item) {
      if (item == null) return "";
      return Component.translatable("desc.oneshotonekill." + item.getId()).getString();
   }

   @Override
   protected void init() {
      OsokWidgets.CardLayout layout = OsokWidgets.CardLayout.compute(width, height, CARD_WIDTH, CHROME_HEIGHT, MIN_CONTENT_HEIGHT, CONTENT_HEIGHT, 84);
      listHeight = layout.contentHeight();
      cardHeight = layout.cardHeight();
      cardLeft = layout.cardLeft();
      cardTop = layout.cardTop();
      listTop = layout.contentTop();

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
         String localizedName = item.getNameComponent().getString().toLowerCase(Locale.ROOT);
         if (!query.isEmpty() && !localizedName.contains(query) && !item.getDisplayName().toLowerCase(Locale.ROOT).contains(query)) {
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
         if (event.key() == InputConstants.KEY_BACKSPACE) {
            if (!searchQuery.isEmpty()) {
               searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
               scroll.set(0.0F);
               updateContentLength();
            }
            return true;
         } else if (event.key() == InputConstants.KEY_ESCAPE || event.key() == InputConstants.KEY_RETURN) {
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
      float delta = OsokWidgets.advanceClock(lastFrameMillis, now);
      lastFrameMillis = now;
      return delta;
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
      float delta = advanceClock();
      scroll.advance(delta, contentLength - listHeight);

      OsokWidgets.startGlassFrame(graphics, width, height, cardLeft, cardTop, CARD_WIDTH, cardHeight, entranceScale());
      hotspots.clear();

      graphics.text(font, "✦ OneShotOneKill", cardLeft + 16, cardTop + 14, OsokWidgets.COLOR_GOLD);
      graphics.text(font, Component.translatable("gui.oneshotonekill.admin.subtitle"), cardLeft + 16 + font.width("✦ OneShotOneKill ") + 4, cardTop + 14, OsokWidgets.COLOR_TEXT_MUTED);

      List<SpecialItem> filteredItems = getFilteredItems();
      String countText = filteredItems.size() == 1
         ? Component.translatable("gui.oneshotonekill.admin.item_count", filteredItems.size()).getString()
         : Component.translatable("gui.oneshotonekill.admin.items_count", filteredItems.size()).getString();
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
      graphics.text(font, Component.translatable("gui.oneshotonekill.admin.hint", keyName),
         cardLeft + 16, footerY + 12, OsokWidgets.COLOR_TEXT_FAINT);

      // Batch-Aktionen: [📦 Alle +1] & [Schließen]
      int closeWidth = 78;
      int closeX = cardLeft + CARD_WIDTH - 16 - closeWidth;
      boolean closeHover = OsokWidgets.isOver(mouseX, mouseY, closeX, footerY + 6, closeWidth, 20);
      OsokWidgets.cyberButton(graphics, font, closeX, footerY + 6, closeWidth, 20, Component.translatable("gui.oneshotonekill.admin.close").getString(), true, closeHover, OsokWidgets.COLOR_CARD_BORDER);
      hotspots.add(new Hotspot(closeX, footerY + 6, closeWidth, 20, false, () -> {
         OsokWidgets.playUiClickSound();
         this.onClose();
      }));

      int giveAllWidth = 84;
      int giveAllX = closeX - giveAllWidth - 6;
      boolean giveAllHover = OsokWidgets.isOver(mouseX, mouseY, giveAllX, footerY + 6, giveAllWidth, 20);
      boolean hasItems = !filteredItems.isEmpty();
      OsokWidgets.cyberButton(graphics, font, giveAllX, footerY + 6, giveAllWidth, 20, Component.translatable("gui.oneshotonekill.admin.give_all").getString(), hasItems, giveAllHover, OsokWidgets.COLOR_EMERALD);
      hotspots.add(new Hotspot(giveAllX, footerY + 6, giveAllWidth, 20, false, () -> {
         boolean isTilted = isTiltedActive();
         for (SpecialItem item : filteredItems) {
            if (isTilted && item == SpecialItem.GRAPPLING_HOOK) {
               continue;
            }
            ClientPlayNetworking.send(new GiveSpecialItemPayload(item.getId()));
         }
         OsokWidgets.playItemGiveSound();
      }));

      OsokWidgets.endGlassFrame(graphics);

      if (hoveredItemForTooltip != null) {
         ItemCategory cat = getCategoryFor(hoveredItemForTooltip);
         boolean isFav = FAVORITES.contains(hoveredItemForTooltip);
         List<Component> tooltip = buildTooltip(hoveredItemForTooltip, cat, isFav);
         graphics.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
      }

      super.extractRenderState(graphics, mouseX, mouseY, partial);
      updateCursor(graphics, mouseX, mouseY);
   }

   private boolean isTiltedActive() {
      return (minecraft.level != null && Arena.TILTED_TOWERS.getDimension().equals(minecraft.level.dimension()))
         || Arena.TILTED_TOWERS.getId().equalsIgnoreCase(ArenaMenuScreen.getLastActiveArenaId());
   }

   private List<Component> buildTooltip(SpecialItem item, ItemCategory cat, boolean isFav) {
      boolean isTilted = isTiltedActive();
      Component itemName = item.getNameComponent();
      if (isTilted && item == SpecialItem.GRAPPLING_HOOK) {
         return List.of(
            Component.literal((isFav ? "⭐ " : "")).append(itemName).withColor(cat.accent),
            Component.translatable("gui.oneshotonekill.admin.tilted_grappler_status").withColor(OsokWidgets.COLOR_AMBER),
            Component.empty(),
            Component.translatable("gui.oneshotonekill.admin.tilted_grappler_desc").withColor(OsokWidgets.COLOR_TEXT_WHITE),
            Component.empty(),
            Component.translatable("gui.oneshotonekill.admin.tilted_grappler_locked").withColor(OsokWidgets.COLOR_CRIMSON)
         );
      }
      return List.of(
         Component.literal((isFav ? "⭐ " : "")).append(itemName).withColor(cat.accent),
         Component.translatable("gui.oneshotonekill.admin.category_label", cat.label()).withColor(OsokWidgets.COLOR_TEXT_MUTED),
         Component.empty(),
         Component.literal(getItemDescription(item)).withColor(OsokWidgets.COLOR_TEXT_WHITE),
         Component.empty(),
         Component.translatable("gui.oneshotonekill.admin.tooltip_hint").withColor(OsokWidgets.COLOR_TEXT_FAINT)
      );
   }

   private static String trimText(Font font, String text, int available) {
      if (font.width(text) <= available) {
         return text;
      }
      String shortened = text;
      while (shortened.length() > 1 && font.width(shortened + "…") > available) {
         shortened = shortened.substring(0, shortened.length() - 1);
      }
      return shortened + "…";
   }

   private float entranceScale() {
      return OsokWidgets.entranceScale(openedAt, ENTRANCE_MILLIS);
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
         Component emptyMsg = currentCategory == ItemCategory.FAVORITES
            ? Component.translatable("gui.oneshotonekill.admin.empty_favorites")
            : Component.translatable("gui.oneshotonekill.admin.empty_search", searchQuery);
         graphics.centeredText(font, emptyMsg, (left + right) / 2, listTop + listHeight / 2 - 4, OsokWidgets.COLOR_TEXT_FAINT);
      } else {
         for (SpecialItem item : filteredItems) {
            boolean rowHovered = OsokWidgets.isOver(mouseX, mouseY, left, y, right - left, ROW_HEIGHT)
               && mouseY >= listTop && mouseY < listBottom;

            ItemCategory cat = getCategoryFor(item);
            boolean isFav = FAVORITES.contains(item);
            int rowBg = rowHovered ? 0xFF222B3D : 0xFF141924;
            int rowBorder = rowHovered ? cat.accent : OsokWidgets.COLOR_CARD_BORDER;

            OsokWidgets.panel(graphics, left, y, right, y + ROW_HEIGHT, rowBg, rowBorder);

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

            int btnY = y + 8;
            int btn16X = right - BTN_WIDTH - 8;
            int btn1X = btn16X - BTN_WIDTH - 6;
            boolean isTilted = isTiltedActive();
            int actionLeft = (isTilted && item == SpecialItem.GRAPPLING_HOOK)
               ? (right - (font.width(Component.translatable("gui.oneshotonekill.admin.tilted_badge")) + 18) - 8)
               : btn1X;
            int maxDescWidth = Math.max(20, actionLeft - (left + 48) - 8);

            Component itemName = item.getNameComponent();
            graphics.text(font, itemName, left + 48, y + 8, OsokWidgets.COLOR_TEXT_WHITE);
            int nameWidth = font.width(itemName);
            if (isTilted && item == SpecialItem.GRAPPLING_HOOK) {
               graphics.text(font, Component.translatable("gui.oneshotonekill.admin.tilted_sub"), left + 52 + nameWidth, y + 8, OsokWidgets.COLOR_AMBER);
               graphics.text(font, trimText(font, Component.translatable("gui.oneshotonekill.admin.tilted_desc").getString(), maxDescWidth), left + 48, y + 20, OsokWidgets.COLOR_TEXT_FAINT);
            } else {
               graphics.text(font, "• " + cat.label(), left + 52 + nameWidth, y + 8, cat.accent);
               graphics.text(font, trimText(font, getItemDescription(item), maxDescWidth), left + 48, y + 20, OsokWidgets.COLOR_TEXT_FAINT);
            }

            if (isTilted && item == SpecialItem.GRAPPLING_HOOK) {
               String badge = Component.translatable("gui.oneshotonekill.admin.tilted_badge").getString();
               int badgeWidth = font.width(badge) + 18;
               int badgeX = right - badgeWidth - 8;
               OsokWidgets.statusBadge(graphics, font, badgeX, btnY + 3, badge, OsokWidgets.COLOR_AMBER, false);
            } else {
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
            }

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
      return OsokWidgets.tabSlideOffset(categoryChangedAt, 180);
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
            : cat.label() + " (" + count + ")";
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

      tabIndicator.advance(activeX, activeWidth, delta);
      tabIndicator.draw(graphics, y + 18.0F, 2.0F, currentCategory.accent);

      int searchWidth = 130;
      int searchX = cardLeft + CARD_WIDTH - 16 - searchWidth;
      boolean searchHover = OsokWidgets.isOver(mouseX, mouseY, searchX, y, searchWidth, 20);
      OsokWidgets.searchInput(graphics, font, searchX, y, searchWidth, 20, searchQuery, Component.translatable("gui.oneshotonekill.admin.search").getString(), searchFocused, searchHover);

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
      if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
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
      if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && scroll.isDragging()) {
         scroll.drag(event.y(), listTop, listHeight, contentLength);
         return true;
      }
      return super.mouseDragged(event, deltaX, deltaY);
   }

   @Override
   public boolean mouseReleased(MouseButtonEvent event) {
      if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
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
