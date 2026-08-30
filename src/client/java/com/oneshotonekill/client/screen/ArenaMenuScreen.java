package com.oneshotonekill.client.screen;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.client.OsokClient;
import com.oneshotonekill.client.state.ClientStates.*;
import com.oneshotonekill.client.state.ClientStates.NukeState;
import com.oneshotonekill.client.state.ClientStates.MatchStartState;
import com.oneshotonekill.item.SpecialItem;
import com.oneshotonekill.item.box.SpecialItemManager;
import com.oneshotonekill.network.OsokPayloads.*;
import com.oneshotonekill.match.MatchManager.MatchState;
import com.oneshotonekill.match.MatchManager.MatchTargetMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Die moderne Cyber-Tactical Verwaltungsoberfläche für Arenen, Match-Ablauf, Match-Dauer und Itemgewichtungen.
 */
@SuppressWarnings({"ConstantValue", "NullableProblems", "SameParameterValue", "UnnecessaryLocalVariable"})
public final class ArenaMenuScreen extends Screen {
   private static final int CARD_WIDTH = 580;
   private static final int TAB_CONTENT_HEIGHT = 270;
   private static final int MIN_TAB_CONTENT_HEIGHT = 100;
   private static final int CHROME_HEIGHT = 156;
   private static final int BUTTON_WIDTH = 76;
   private static final int CONTROL_BUTTON_WIDTH = 110;
   private static final int WEIGHT_BUTTON_WIDTH = 34;
   private static final int ROW_HEIGHT = 34;
   private static final int SCROLLBAR_INSET = 9;
   private static final int EDGE_FADE = 10;
   private static final long ENTRANCE_MILLIS = 150L;

   /** Jeder Reiter merkt sich seine eigene Scrollposition über das Schließen hinweg. */
   private static final Map<Tab, Float> SCROLL_MEMORY = new EnumMap<>(Tab.class);
   private static Tab lastSelectedTab = Tab.ARENAS;

   private ArenaMenuStatePayload state;
   private Tab currentTab;
   private final List<Hotspot> hotspots = new ArrayList<>();
   private final List<Slider> sliders = new ArrayList<>();
   private Slider activeSlider = null;
   private Integer localPreviewMinutes = null;
   private Integer localPreviewKills = null;

   private int cardLeft;
   private int cardTop;
   private int cardHeight;
   private int contentTop;
   private int contentHeight;
   private int contentLength;

   private final OsokWidgets.ScrollMotion scroll = new OsokWidgets.ScrollMotion();
   private final long openedAt = Util.getMillis();
   private long lastFrameMillis = Long.MIN_VALUE;
   private float indicatorX = Float.NaN;
   private float indicatorWidth;
   private long tabChangedAt = Long.MIN_VALUE;
   private double lastSliderValue = Double.NaN;

   public ArenaMenuScreen(ArenaMenuStatePayload state) {
      super(Component.literal("OneShotOneKill"));
      this.state = state;
      this.currentTab = lastSelectedTab;
      this.scroll.set(SCROLL_MEMORY.getOrDefault(currentTab, 0.0F));
   }

   /** Aktualisiert ein offenes Menü oder öffnet es, wenn der Server darum bittet. */
   public static void show(ArenaMenuStatePayload state, Minecraft client) {
      if (client.gui.screen() instanceof ArenaMenuScreen screen) {
         screen.state = state;
         if (screen.activeSlider == null) {
            screen.localPreviewMinutes = null;
            screen.localPreviewKills = null;
         }
      } else if (state.getOpen()) {
         client.gui.setScreen(new ArenaMenuScreen(state));
      }
   }

   @Override
   protected void init() {
      contentHeight = Math.clamp(height - CHROME_HEIGHT, MIN_TAB_CONTENT_HEIGHT, TAB_CONTENT_HEIGHT);
      cardHeight = contentHeight + CHROME_HEIGHT;
      cardLeft = Math.max(4, width / 2 - CARD_WIDTH / 2);
      cardTop = Math.max(4, height / 2 - cardHeight / 2);
      contentTop = cardTop + 84;
   }

   @Override
   public boolean isPauseScreen() {
      return false;
   }

   @Override
   public boolean keyPressed(KeyEvent keyEvent) {
      if (OsokClient.isMenuKey(keyEvent)) {
         onClose();
         return true;
      }
      return super.keyPressed(keyEvent);
   }

   @Override
   public void removed() {
      SCROLL_MEMORY.put(currentTab, scroll.value());
      super.removed();
   }

   // -- Zeichnen ------------------------------------------------------------

   /**
    * Nur die Unschärfe, kein Vanilla-Hintergrund.
    * <p>
    * <p>Der Aufruf gehört hierher und nicht in {@code extractRenderState}: Die Unschärfe trennt
    * die bereits gezeichneten Ebenen von den folgenden, muss also feststehen, bevor der eigene
    * Inhalt beginnt. Vanillas Menühintergrund entfällt ersatzlos – über der unscharfen Welt
    * liegt allein der Schleier aus {@code OsokWidgets.COLOR_SCRIM}.</p>
    */
   @Override
   public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
      graphics.blurBeforeThisStratum();
      this.minecraft.gui.hud.extractDeferredSubtitles();
   }

   /** Vergangene Echtzeit seit dem letzten Bild, gedeckelt gegen Sprünge nach einem Ruckler. */
   private float advanceClock() {
      long now = Util.getMillis();
      float delta = lastFrameMillis == Long.MIN_VALUE
         ? 1.0F / 60.0F
         : Math.clamp((now - lastFrameMillis) / 1000.0F, 1.0F / 480.0F, 0.1F);
      lastFrameMillis = now;
      return delta;
   }

   /** Federnder Auftritt von 0,95 auf 1,0 mit leichtem Überschwingen. */
   private float entranceScale() {
      float progress = Math.clamp((Util.getMillis() - openedAt) / (float) ENTRANCE_MILLIS, 0.0F, 1.0F);
      if (progress >= 1.0F) {
         return 1.0F;
      }
      float back = progress - 1.0F;
      float eased = 1.0F + back * back * (2.0F * back + 1.0F);
      return 0.95F + 0.05F * eased;
   }

   /** Versatz des Inhalts direkt nach einem Reiterwechsel. */
   private int tabSlideOffset() {
      if (tabChangedAt == Long.MIN_VALUE) {
         return 0;
      }
      float progress = Math.clamp((Util.getMillis() - tabChangedAt) / 180.0F, 0.0F, 1.0F);
      float eased = 1.0F - (1.0F - progress) * (1.0F - progress);
      return Math.round((1.0F - eased) * 12.0F);
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
      float delta = advanceClock();
      scroll.advance(delta, contentLength - contentHeight);

      graphics.fill(0, 0, width, height, OsokWidgets.COLOR_SCRIM);

      // Auftritt: die Karte federt aus 95 % auf ihre volle Größe. Der Scissor-Rahmen wird von
      // GuiGraphicsExtractor mit derselben Matrix umgerechnet, der Inhalt bleibt also sauber
      // beschnitten. Nur Mauskoordinaten laufen ungewandelt weiter - für 150 ms belanglos.
      float entrance = entranceScale();
      float centerX = cardLeft + CARD_WIDTH / 2.0F;
      float centerY = cardTop + cardHeight / 2.0F;
      graphics.pose().pushMatrix();
      graphics.pose().translate(centerX, centerY);
      graphics.pose().scale(entrance, entrance);
      graphics.pose().translate(-centerX, -centerY);

      OsokWidgets.glassCard(graphics, cardLeft, cardTop, cardLeft + CARD_WIDTH, cardTop + cardHeight, false, 0);

      hotspots.clear();
      sliders.clear();

      drawHeader(graphics);
      drawTabs(graphics, mouseX, mouseY, delta);
      OsokWidgets.divider(graphics, cardLeft + 16, cardLeft + CARD_WIDTH - 16, contentTop - 6, OsokWidgets.COLOR_CARD_BORDER);

      // Inhalt liegt im Scissor-Bereich
      int contentLeft = cardLeft + 16;
      int contentRight = cardLeft + CARD_WIDTH - 16;
      int contentBottom = contentTop + contentHeight;
      graphics.enableScissor(contentLeft, contentTop, contentRight, contentBottom);
      graphics.pose().pushMatrix();
      graphics.pose().translate(tabSlideOffset(), 0.0F);
      switch (currentTab) {
         case ARENAS -> drawArenasTab(graphics, mouseX, mouseY);
         case MATCH_CONTROL -> drawMatchControlTab(graphics, mouseX, mouseY);
         case MATCH_TARGET -> drawMatchTargetTab(graphics, mouseX, mouseY);
         case ITEM_WEIGHTS -> drawItemWeightsTab(graphics, mouseX, mouseY);
      }
      graphics.pose().popMatrix();
      // Weiche Kanten noch innerhalb des Scissor-Bereichs, damit sie exakt darauf abschließen.
      OsokWidgets.drawSoftScrollEdges(graphics, contentLeft, contentRight, contentTop, contentBottom,
         EDGE_FADE, OsokWidgets.COLOR_CARD_BG, scroll.offset(), contentLength, contentHeight);
      graphics.disableScissor();
      drawScrollbar(graphics, mouseX, mouseY);

      int footerY = contentTop + contentHeight + 8;
      OsokWidgets.divider(graphics, cardLeft + 16, cardLeft + CARD_WIDTH - 16, footerY, OsokWidgets.COLOR_CARD_BORDER);
      drawFooter(graphics, mouseX, mouseY, footerY + 10);

      graphics.pose().popMatrix();

      super.extractRenderState(graphics, mouseX, mouseY, partial);
      updateCursor(graphics, mouseX, mouseY);
   }

   private void drawHeader(GuiGraphicsExtractor graphics) {
      graphics.text(font, "✦ OneShotOneKill", cardLeft + 16, cardTop + 14, OsokWidgets.COLOR_GOLD);
      graphics.text(font, "• Zentral-Verwaltung", cardLeft + 16 + font.width("✦ OneShotOneKill ") + 4, cardTop + 14, OsokWidgets.COLOR_TEXT_MUTED);

      // Live-Match-Status Badge rechts oben
      drawMatchStateBadge(graphics, cardLeft + CARD_WIDTH - 16, cardTop + 12);
   }

   private void drawTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
      int x = cardLeft + 16;
      int y = cardTop + 36;
      float activeX = x;
      float activeWidth = 0.0F;

      for (Tab tab : Tab.values()) {
         String label = tab.label;
         int tabWidth = font.width(label) + 18;
         boolean isActive = currentTab == tab;
         boolean hovered = OsokWidgets.isOver(mouseX, mouseY, x, y, tabWidth, 22);

         // Ohne festen Unterstrich: den übernimmt der gleitende Indikator weiter unten.
         OsokWidgets.tabHeader(graphics, font, x, y, tabWidth, 22, label, isActive, hovered,
            OsokWidgets.COLOR_GOLD, false);
         if (isActive) {
            activeX = x;
            activeWidth = tabWidth;
         }

         Tab target = tab;
         hotspots.add(new Hotspot(x, y, tabWidth, 22, true, false, () -> selectTab(target)));
         x += tabWidth + 6;
      }

      // Der Unterstrich gleitet auf den gewählten Reiter, statt dorthin zu springen.
      if (Float.isNaN(indicatorX)) {
         indicatorX = activeX;
         indicatorWidth = activeWidth;
      } else {
         float rate = 1.0F - (float) Math.exp(-delta * 24.0F);
         indicatorX += (activeX - indicatorX) * rate;
         indicatorWidth += (activeWidth - indicatorWidth) * rate;
      }
      OsokWidgets.floatingTabIndicator(graphics, indicatorX, y + 20.0F, indicatorWidth, 2.0F,
         OsokWidgets.COLOR_GOLD);
   }

   private void drawArenasTab(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
      int left = cardLeft + 16;
      int right = cardLeft + CARD_WIDTH - 16;
      int y = contentTop + 4 - scroll.offset();

      if (!isMatchState(MatchState.STOPPED)) {
         OsokWidgets.alertBanner(graphics, font, left, y, right - left, 26,
            new ItemStack(Items.BARRIER), "Arena-Wechsel gesperrt (Laufendes/pausiertes Match)", OsokWidgets.COLOR_CRIMSON);
         y += 32;
      }

      for (Arena arena : Arena.values()) {
         drawArenaRow(graphics, arena, left, right, y, mouseX, mouseY);
         y += ROW_HEIGHT + 6;
      }
      contentLength = y + scroll.offset() - contentTop;
   }

   private void drawArenaRow(GuiGraphicsExtractor graphics, Arena arena, int left, int right, int y, int mouseX, int mouseY) {
      boolean isActive = arena.getId().equals(state.getActiveArenaId());
      boolean isPlayerHere = arena.getId().equals(state.getPlayerArenaId());
      boolean isOpen = state.getOpenArenaIds().contains(arena.getId());
      boolean isResetting = arena.getId().equals(state.getResettingArenaId());
      boolean hovered = OsokWidgets.isOver(mouseX, mouseY, left, y, right - left, ROW_HEIGHT)
         && mouseY >= contentTop && mouseY < contentTop + contentHeight;

      int rowBg = isActive ? 0xFF241F16 : (hovered ? 0xFF222B3D : 0xFF141924);
      int rowBorder = isActive ? OsokWidgets.COLOR_GOLD : (hovered ? OsokWidgets.COLOR_CARD_BORDER_HOVER : OsokWidgets.COLOR_CARD_BORDER);

      graphics.fill(left, y, right, y + ROW_HEIGHT, rowBg);
      graphics.horizontalLine(left, right - 1, y, rowBorder);
      graphics.horizontalLine(left, right - 1, y + ROW_HEIGHT - 1, rowBorder);
      graphics.verticalLine(left, y, y + ROW_HEIGHT - 1, rowBorder);
      graphics.verticalLine(right - 1, y, y + ROW_HEIGHT - 1, rowBorder);

      // Status-Streifen links
      int statusCol = stateColor(isActive, isOpen, isResetting);
      graphics.fill(left + 2, y + 2, left + 5, y + ROW_HEIGHT - 2, statusCol);

      // Läuft ein Reset, wandert ein Lichtimpuls über die Zeile. Er liegt auf Echtzeit, weil der
      // Server während des Resets ohnehin beschäftigt ist und die Tickrate einbrechen kann.
      if (isResetting) {
         float phase = (Util.getMillis() % 1400L) / 1400.0F;
         int sweepX = left + Math.round(phase * (right - left));
         int rgb = OsokWidgets.COLOR_CYAN & 0x00FFFFFF;
         for (int offset = -14; offset <= 14; offset++) {
            int px = sweepX + offset;
            if (px < left + 1 || px >= right - 1) {
               continue;
            }
            int alpha = Math.round(72.0F * (1.0F - Math.abs(offset) / 14.0F));
            if (alpha <= 0) {
               continue;
            }
            graphics.fill(px, y + 1, px + 1, y + ROW_HEIGHT - 1, rgb | (alpha << 24));
         }
      }

      // Arena-Icon
      graphics.item(new ItemStack(itemFor(arena)), left + 10, y + 9);
      graphics.text(font, arena.getDisplayName(), left + 34, y + 9, OsokWidgets.COLOR_TEXT_WHITE);

      // Status-Tags
      int badgeX = left + 38 + font.width(arena.getDisplayName());
      if (isActive) {
         OsokWidgets.statusBadge(graphics, font, badgeX, y + 8, "Aktiv", OsokWidgets.COLOR_GOLD, true);
         badgeX += font.width("Aktiv") + 22;
      }
      if (isPlayerHere) {
         OsokWidgets.statusBadge(graphics, font, badgeX, y + 8, "Hier", OsokWidgets.COLOR_EMERALD, true);
      }

      boolean stopped = isMatchState(MatchState.STOPPED);
      int switchX = right - BUTTON_WIDTH * 2 - 8;
      int resetX = right - BUTTON_WIDTH - 2;
      boolean canSwitch = stopped && isOpen && !isActive && !isResetting;
      boolean canReset = stopped && isOpen && state.getResettingArenaId().isEmpty();

      boolean switchHover = OsokWidgets.isOver(mouseX, mouseY, switchX, y + 7, BUTTON_WIDTH, 20);
      boolean resetHover = OsokWidgets.isOver(mouseX, mouseY, resetX, y + 7, BUTTON_WIDTH, 20);

      OsokWidgets.cyberButton(graphics, font, switchX, y + 7, BUTTON_WIDTH, 20, isActive ? "Aktiv" : "Wählen",
         canSwitch, switchHover, OsokWidgets.COLOR_GOLD);
      OsokWidgets.cyberButton(graphics, font, resetX, y + 7, BUTTON_WIDTH, 20, isResetting ? "Lädt…" : "Reset",
         canReset, resetHover, OsokWidgets.COLOR_CYAN);

      hotspots.add(new Hotspot(switchX, y + 7, BUTTON_WIDTH, 20, canSwitch,
         stopped && isOpen && !isResetting, true,
         () -> ClientPlayNetworking.send(new SelectArenaPayload(arena.getId()))));
      hotspots.add(new Hotspot(resetX, y + 7, BUTTON_WIDTH, 20, canReset,
         () -> ClientPlayNetworking.send(new ResetArenaPayload(arena.getId()))));
   }

   private void drawMatchControlTab(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
      int left = cardLeft + 16;
      int right = cardLeft + CARD_WIDTH - 16;
      int y = contentTop + 4 - scroll.offset();

      // Dashboard Info Card
      graphics.fill(left, y, right, y + 50, 0xFF141A27);
      graphics.horizontalLine(left, right - 1, y, OsokWidgets.COLOR_CARD_BORDER);
      graphics.horizontalLine(left, right - 1, y + 49, OsokWidgets.COLOR_CARD_BORDER);
      graphics.verticalLine(left, y, y + 49, OsokWidgets.COLOR_CARD_BORDER);
      graphics.verticalLine(right - 1, y, y + 49, OsokWidgets.COLOR_CARD_BORDER);

      graphics.text(font, "🎮 PvP-Match Status & Steuerung", left + 12, y + 10, OsokWidgets.COLOR_TEXT_WHITE);
      graphics.text(font, "Startet, pausiert oder beendet das Minigame für alle Spieler in der Arena.", left + 12, y + 28, OsokWidgets.COLOR_TEXT_FAINT);

      y += 62;

      boolean isPaused = isMatchState(MatchState.PAUSED);
      boolean isCountdown = MatchStartState.INSTANCE.isCountdownActive();
      boolean isNuke = NukeState.INSTANCE.isRunning();
      boolean canStart = isMatchState(MatchState.STOPPED) && !isNuke;
      boolean canPause = (isMatchState(MatchState.RUNNING) || isPaused) && !isNuke;
      boolean canRespawn = isMatchState(MatchState.RUNNING) && state.isOutsideArena() && !isCountdown && !isNuke;

      int x = left;
      controlButton(graphics, x, y, "▶ Start", canStart, mouseX, mouseY, true, false, OsokWidgets.COLOR_EMERALD,
         () -> {
            ClientPlayNetworking.send(StartMatchPayload.EMPTY);
            onClose();
         });
      x += CONTROL_BUTTON_WIDTH + 8;
      controlButton(graphics, x, y, isPaused ? "▶ Fortsetzen" : "⏸ Pause",
         canPause, mouseX, mouseY, false, !isPaused, OsokWidgets.COLOR_AMBER,
         () -> {
            ClientPlayNetworking.send(isPaused ? StartMatchPayload.EMPTY : PauseMatchPayload.EMPTY);
            onClose();
         });
      x += CONTROL_BUTTON_WIDTH + 8;
      controlButton(graphics, x, y, "⏹ Stopp", !isMatchState(MatchState.STOPPED), mouseX, mouseY, false, OsokWidgets.COLOR_CRIMSON,
         () -> {
            ClientPlayNetworking.send(StopMatchPayload.EMPTY);
            onClose();
         });
      x += CONTROL_BUTTON_WIDTH + 8;
      controlButton(graphics, x, y, "🔄 Respawn", canRespawn, mouseX, mouseY, true, OsokWidgets.COLOR_CYAN,
         () -> {
            ClientPlayNetworking.send(RequestRespawnPayload.EMPTY);
            onClose();
         });

      y += 32;
      boolean isAdmin = Minecraft.getInstance().player != null
         && OneShotOneKill.isAdmin(Minecraft.getInstance().player);
      controlButton(graphics, left, y, "➶ Pfeile löschen", isAdmin, mouseX, mouseY, false, OsokWidgets.COLOR_CYAN,
         () -> ClientPlayNetworking.send(ClearArrowsPayload.EMPTY));

      y += 36;
      OsokWidgets.divider(graphics, left, right, y, OsokWidgets.COLOR_CARD_BORDER);
      y += 10;

      // Spielmodus-Auswahl
      graphics.text(font, "🕹 Spielmodus auswählen", left, y, OsokWidgets.COLOR_GOLD);
      graphics.text(font, "Legt das Spielprinzip für die Arena fest (nur vor Rundenstart änderbar):", left, y + 14, OsokWidgets.COLOR_TEXT_FAINT);
      y += 30;

      boolean stopped = isMatchState(MatchState.STOPPED);
      String currentMode = state.getGameMode() != null ? state.getGameMode() : "CLASSIC";
      boolean isClassic = "CLASSIC".equalsIgnoreCase(currentMode);
      boolean isGunGame = "GUN_GAME".equalsIgnoreCase(currentMode);

      int modeCardW = (right - left - 12) / 2;
      drawModeSelectionCard(graphics, left, y, modeCardW, 46, "🏆 Klassisch", "Standard Deathmatch & Kisten", isClassic, stopped, mouseX, mouseY,
         () -> ClientPlayNetworking.send(new SetGameModePayload("CLASSIC")));
      drawModeSelectionCard(graphics, left + modeCardW + 12, y, modeCardW, 46, "🎯 Waffenspiel", "13-Stufen Progression bis Meisterdolch", isGunGame, stopped, mouseX, mouseY,
         () -> ClientPlayNetworking.send(new SetGameModePayload("GUN_GAME")));

      y += 56;

      if (isGunGame) {
         // Gun Game Übersicht / Tier-Liste Info Box
         graphics.fill(left, y, right, y + 88, 0xDD101522);
         graphics.horizontalLine(left, right - 1, y, OsokWidgets.COLOR_GOLD);
         graphics.horizontalLine(left, right - 1, y + 87, OsokWidgets.COLOR_GOLD);
         graphics.verticalLine(left, y, y + 87, OsokWidgets.COLOR_GOLD);
         graphics.verticalLine(right - 1, y, y + 87, OsokWidgets.COLOR_GOLD);

         graphics.text(font, "🎯 WAFFENSPIEL REGELN & STUFEN (12 Tiers):", left + 10, y + 8, OsokWidgets.COLOR_GOLD);
         graphics.text(font, "• Leichte Waffen (T1-T2): 3 Kills nötig (Bogen, Dolch)", left + 10, y + 24, 0xFFE2E8F0);
         graphics.text(font, "• Spezialwaffen (T3-T7): 2 Kills nötig (Explosiv, Kettenblitz, Railgun, Minigun, Singularität + Bogen)", left + 10, y + 38, 0xFFE2E8F0);
         graphics.text(font, "• Schwere Waffen (T8-T11): 1 Kill nötig (C4, Frostfalle, Geschützturm, Stealth-Bomber)", left + 10, y + 52, 0xFFE2E8F0);
         graphics.text(font, "• T12: Zeitverzerrer + Dolch · FINALE (T13): 👑 Meisterdolch", left + 10, y + 68, OsokWidgets.COLOR_AMBER);
         y += 96;
      }

      contentLength = y + 10 + scroll.offset() - contentTop;
   }

   private void drawModeSelectionCard(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
                                      String title, String desc, boolean selected, boolean enabled,
                                      int mouseX, int mouseY, Runnable onSelect) {
      boolean hovered = OsokWidgets.isOver(mouseX, mouseY, x, y, w, h) && enabled;
      int bg = selected ? 0xFF241F16 : (hovered ? 0xFF1C2433 : 0xFF121722);
      int border = selected ? OsokWidgets.COLOR_GOLD : (hovered ? OsokWidgets.COLOR_CARD_BORDER_HOVER : OsokWidgets.COLOR_CARD_BORDER);

      graphics.fill(x, y, x + w, y + h, bg);
      graphics.horizontalLine(x, x + w - 1, y, border);
      graphics.horizontalLine(x, x + w - 1, y + h - 1, border);
      graphics.verticalLine(x, y, y + h - 1, border);
      graphics.verticalLine(x + w - 1, y, y + h - 1, border);

      if (selected) {
         graphics.fill(x + 2, y + 2, x + 5, y + h - 2, OsokWidgets.COLOR_GOLD);
      }

      graphics.text(font, title, x + 10, y + 8, selected ? OsokWidgets.COLOR_GOLD : OsokWidgets.COLOR_TEXT_WHITE);
      graphics.text(font, desc, x + 10, y + 24, OsokWidgets.COLOR_TEXT_FAINT);

      hotspots.add(new Hotspot(x, y, w, h, enabled && !selected, enabled, false, onSelect));
   }

   private void controlButton(GuiGraphicsExtractor graphics, int x, int y, String label, boolean enabled,
                              int mouseX, int mouseY, boolean closes, boolean playSound, int accent, Runnable action) {
      boolean hovered = OsokWidgets.isOver(mouseX, mouseY, x, y, CONTROL_BUTTON_WIDTH, 24);
      OsokWidgets.cyberButton(graphics, font, x, y, CONTROL_BUTTON_WIDTH, 24, label, enabled, hovered, accent);
      hotspots.add(new Hotspot(x, y, CONTROL_BUTTON_WIDTH, 24, enabled, enabled, true, playSound, () -> {
         action.run();
         if (closes) {
            onClose();
         }
      }));
   }

   private void controlButton(GuiGraphicsExtractor graphics, int x, int y, String label, boolean enabled,
                              int mouseX, int mouseY, boolean closes, int accent, Runnable action) {
      controlButton(graphics, x, y, label, enabled, mouseX, mouseY, closes, true, accent, action);
   }

   private void drawMatchTargetTab(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
      int left = cardLeft + 16;
      int right = cardLeft + CARD_WIDTH - 16;
      int y = contentTop + 4 - scroll.offset();
      boolean stopped = isMatchState(MatchState.STOPPED);
      boolean isGunGame = "GUN_GAME".equalsIgnoreCase(state.getGameMode());

      if (!stopped) {
         OsokWidgets.alertBanner(graphics, font, left, y, right - left, 26,
            new ItemStack(Items.BARRIER), "Ziel-Änderungen nur vor Match-Start möglich (Match läuft/pausiert)", OsokWidgets.COLOR_CRIMSON);
         y += 32;
      }

      if (isGunGame) {
         // Gun Game Modus Info Banner
         graphics.fill(left, y, right, y + 42, 0xFF241F16);
         graphics.horizontalLine(left, right - 1, y, OsokWidgets.COLOR_GOLD);
         graphics.horizontalLine(left, right - 1, y + 41, OsokWidgets.COLOR_GOLD);
         graphics.verticalLine(left, y, y + 41, OsokWidgets.COLOR_GOLD);
         graphics.verticalLine(right - 1, y, y + 41, OsokWidgets.COLOR_GOLD);

         graphics.item(new ItemStack(Items.GOLDEN_SWORD), left + 8, y + 13);
         graphics.text(font, "🎯 Waffenspiel-Modus aktiv: Ziel ist Stufe 13 (👑 Meisterdolch)", left + 32, y + 8, OsokWidgets.COLOR_GOLD);
         graphics.text(font, "Wähle 'Zeitbegrenzt' für eine maximale Rundendauer oder 'Bis Stufe 13' für Open-End.", left + 32, y + 24, OsokWidgets.COLOR_TEXT_FAINT);
         y += 50;
      }

      // Modus-Auswahl Panel
      graphics.fill(left, y, right, y + 56, 0xFF141A27);
      graphics.horizontalLine(left, right - 1, y, OsokWidgets.COLOR_CARD_BORDER);
      graphics.horizontalLine(left, right - 1, y + 55, OsokWidgets.COLOR_CARD_BORDER);
      graphics.verticalLine(left, y, y + 55, OsokWidgets.COLOR_CARD_BORDER);
      graphics.verticalLine(right - 1, y, y + 55, OsokWidgets.COLOR_CARD_BORDER);

      graphics.text(font, isGunGame ? "Waffenspiel-Rundenbegrenzung festlegen:" : "Match-Zielmodus vor Spielstart festlegen:", left + 10, y + 8, OsokWidgets.COLOR_TEXT_MUTED);

      MatchTargetMode currentMode = matchTargetMode();
      int buttonWidth = (right - left - 24) / 3;
      int modeX = left + 8;
      for (MatchTargetMode mode : MatchTargetMode.values()) {
         boolean isSelected = mode == currentMode;
         boolean isKillLimitInGunGame = isGunGame && mode == MatchTargetMode.KILL_LIMIT;
         boolean canClick = stopped && !isSelected && !isKillLimitInGunGame;
         boolean hovered = OsokWidgets.isOver(mouseX, mouseY, modeX, y + 24, buttonWidth, 22);

         String label = mode.getDisplayName();
         if (isGunGame) {
            label = switch (mode) {
               case TIME_LIMIT -> "⏱ Zeitbegrenzt";
               case KILL_LIMIT -> "🎯 Kill-Limit 🔒";
               case UNLIMITED -> "👑 Bis Stufe 13";
            };
         }

         int accentColor = isKillLimitInGunGame ? OsokWidgets.COLOR_TEXT_DISABLED : (isSelected ? OsokWidgets.COLOR_GOLD : OsokWidgets.COLOR_CARD_BORDER);
         OsokWidgets.cyberButton(graphics, font, modeX, y + 24, buttonWidth, 22, label,
            stopped && !isKillLimitInGunGame, hovered, accentColor);

         MatchTargetMode targetMode = mode;
         hotspots.add(new Hotspot(modeX, y + 24, buttonWidth, 22, canClick,
            stopped && !isKillLimitInGunGame, true, () -> {
            localPreviewMinutes = null;
            localPreviewKills = null;
            int defaultValue = switch (targetMode) {
               case TIME_LIMIT -> 600;
               case KILL_LIMIT -> 25;
               case UNLIMITED -> 0;
            };
            ClientPlayNetworking.send(new SetMatchTargetPayload(targetMode.name(), defaultValue));
         }));

         modeX += buttonWidth + 4;
      }
      y += 66;

      // Konfigurationsbereich je nach Modus
      switch (currentMode) {
         case TIME_LIMIT -> {
            int effectiveMins = localPreviewMinutes != null ? localPreviewMinutes : Math.clamp(state.getMatchTargetValue() / 60, 1, 60);
            String formattedTime = String.format("%02d:00 Minuten", effectiveMins);

            graphics.fill(left, y, right, y + 46, 0xFF141924);
            graphics.horizontalLine(left, right - 1, y, OsokWidgets.COLOR_CARD_BORDER);
            graphics.horizontalLine(left, right - 1, y + 45, OsokWidgets.COLOR_CARD_BORDER);
            graphics.verticalLine(left, y, y + 45, OsokWidgets.COLOR_CARD_BORDER);
            graphics.verticalLine(right - 1, y, y + 45, OsokWidgets.COLOR_CARD_BORDER);

            graphics.item(new ItemStack(Items.CLOCK), left + 10, y + 15);
            graphics.text(font, "Eingestellte Match-Dauer: " + formattedTime, left + 36, y + 11, OsokWidgets.COLOR_GOLD);
            graphics.text(font, isGunGame
               ? "Läuft die Zeit ab, gewinnt der Spieler auf der höchsten erreichten Waffenstufe."
               : "Das Match endet nach Ablauf der Zeit automatisch mit Siegerehrung.", left + 36, y + 27, OsokWidgets.COLOR_TEXT_FAINT);
            y += 56;

            // Schieberegler (Slider: 1 bis 60 Min)
            Slider timeSlider = new Slider(left, y + 14, right - left, 22, 1, 60, effectiveMins, 1.0, stopped, val -> {
               int newMins = val.intValue();
               localPreviewMinutes = newMins;
               int newSecs = newMins * 60;
               if (newSecs != state.getMatchTargetValue()) {
                  ClientPlayNetworking.send(new SetMatchTargetPayload(MatchTargetMode.TIME_LIMIT.name(), newSecs));
               }
            });
            drawSlider(graphics, timeSlider, "⏱ Dauer per Schieberegler wählen (1 bis 60 Min):", effectiveMins + " Min", mouseX, mouseY);
            y += 44;

            // Schnellauswahl Presets
            graphics.text(font, "Schnellauswahl (Voreinstellungen):", left, y + 4, OsokWidgets.COLOR_TEXT_MUTED);
            y += 18;
            int presetWidth = (right - left - 25) / 6;
            int[][] presets = new int[][] {{60, 1}, {300, 5}, {600, 10}, {900, 15}, {1800, 30}, {3600, 60}};
            int px = left;
            for (int[] preset : presets) {
               int val = preset[0];
               int mins = preset[1];
               String label = mins + " Min";
               boolean isCurrent = effectiveMins == mins;
               boolean hovered = OsokWidgets.isOver(mouseX, mouseY, px, y, presetWidth, 20);
               OsokWidgets.cyberButton(graphics, font, px, y, presetWidth, 20, label, stopped, hovered, isCurrent ? OsokWidgets.COLOR_GOLD : OsokWidgets.COLOR_CARD_BORDER);
               hotspots.add(new Hotspot(px, y, presetWidth, 20, stopped && !isCurrent, stopped, true, () -> {
                  localPreviewMinutes = mins;
                  ClientPlayNetworking.send(new SetMatchTargetPayload(MatchTargetMode.TIME_LIMIT.name(), val));
               }));
               px += presetWidth + 5;
            }
            y += 28;
         }
         case KILL_LIMIT -> {
            if (isGunGame) {
               graphics.fill(left, y, right, y + 54, 0xFF241F16);
               graphics.horizontalLine(left, right - 1, y, OsokWidgets.COLOR_AMBER);
               graphics.horizontalLine(left, right - 1, y + 53, OsokWidgets.COLOR_AMBER);
               graphics.verticalLine(left, y, y + 53, OsokWidgets.COLOR_AMBER);
               graphics.verticalLine(right - 1, y, y + 53, OsokWidgets.COLOR_AMBER);

               graphics.item(new ItemStack(Items.BARRIER), left + 10, y + 18);
               graphics.text(font, "⚠ Kill-Limit ist im Waffenspiel-Modus deaktiviert", left + 36, y + 12, OsokWidgets.COLOR_AMBER);
               graphics.text(font, "Der Sieg erfolgt automatisch durch das Beenden aller 13 Waffenstufen (Meisterdolch).", left + 36, y + 26, OsokWidgets.COLOR_TEXT_WHITE);
               graphics.text(font, "Wähle oben '⏱ Zeitbegrenzt' oder '👑 Bis Stufe 13'.", left + 36, y + 38, OsokWidgets.COLOR_TEXT_FAINT);
               y += 64;
            } else {
               int effectiveKills = localPreviewKills != null ? localPreviewKills : Math.clamp(state.getMatchTargetValue(), 1, 100);

               graphics.fill(left, y, right, y + 46, 0xFF141924);
               graphics.horizontalLine(left, right - 1, y, OsokWidgets.COLOR_CARD_BORDER);
               graphics.horizontalLine(left, right - 1, y + 45, OsokWidgets.COLOR_CARD_BORDER);
               graphics.verticalLine(left, y, y + 45, OsokWidgets.COLOR_CARD_BORDER);
               graphics.verticalLine(right - 1, y, y + 45, OsokWidgets.COLOR_CARD_BORDER);

               graphics.item(new ItemStack(Items.TARGET), left + 10, y + 15);
               graphics.text(font, "Eingestelltes Kill-Ziel: " + effectiveKills + " Kills", left + 36, y + 11, OsokWidgets.COLOR_GOLD);
               graphics.text(font, "Der erste Spieler, der das Kill-Ziel erreicht, gewinnt das Match sofort.", left + 36, y + 27, OsokWidgets.COLOR_TEXT_FAINT);
               y += 56;

               // Schieberegler (Slider: 1 bis 100 Kills)
               Slider killSlider = new Slider(left, y + 14, right - left, 22, 1, 100, effectiveKills, 1.0, stopped, val -> {
                  int newKills = val.intValue();
                  localPreviewKills = newKills;
                  if (newKills != state.getMatchTargetValue()) {
                     ClientPlayNetworking.send(new SetMatchTargetPayload(MatchTargetMode.KILL_LIMIT.name(), newKills));
                  }
               });
               drawSlider(graphics, killSlider, "🎯 Kill-Ziel per Schieberegler wählen (1 bis 100 Kills):", effectiveKills + " Kills", mouseX, mouseY);
               y += 44;

               // Schnellauswahl Presets
               graphics.text(font, "Schnellauswahl (Voreinstellungen):", left, y + 4, OsokWidgets.COLOR_TEXT_MUTED);
               y += 18;
               int presetWidth = (right - left - 25) / 6;
               int[] presets = new int[] {1, 10, 25, 50, 75, 100};
               int px = left;
               for (int val : presets) {
                  String label = val + (val == 1 ? " Kill" : " Kills");
                  boolean isCurrent = effectiveKills == val;
                  boolean hovered = OsokWidgets.isOver(mouseX, mouseY, px, y, presetWidth, 20);
                  OsokWidgets.cyberButton(graphics, font, px, y, presetWidth, 20, label, stopped, hovered, isCurrent ? OsokWidgets.COLOR_GOLD : OsokWidgets.COLOR_CARD_BORDER);
                  hotspots.add(new Hotspot(px, y, presetWidth, 20, stopped && !isCurrent, stopped, true, () -> {
                     localPreviewKills = val;
                     ClientPlayNetworking.send(new SetMatchTargetPayload(MatchTargetMode.KILL_LIMIT.name(), val));
                  }));
                  px += presetWidth + 5;
               }
               y += 28;
            }
         }
         case UNLIMITED -> {
            graphics.fill(left, y, right, y + (isGunGame ? 56 : 46), 0xFF141924);
            graphics.horizontalLine(left, right - 1, y, isGunGame ? OsokWidgets.COLOR_GOLD : OsokWidgets.COLOR_CARD_BORDER);
            graphics.horizontalLine(left, right - 1, y + (isGunGame ? 55 : 45), isGunGame ? OsokWidgets.COLOR_GOLD : OsokWidgets.COLOR_CARD_BORDER);
            graphics.verticalLine(left, y, y + (isGunGame ? 55 : 45), isGunGame ? OsokWidgets.COLOR_GOLD : OsokWidgets.COLOR_CARD_BORDER);
            graphics.verticalLine(right - 1, y, y + (isGunGame ? 55 : 45), isGunGame ? OsokWidgets.COLOR_GOLD : OsokWidgets.COLOR_CARD_BORDER);

            if (isGunGame) {
               graphics.fill(left + 2, y + 2, left + 5, y + 54, OsokWidgets.COLOR_GOLD);
               graphics.item(new ItemStack(Items.GOLDEN_SWORD), left + 10, y + 19);
               graphics.text(font, "👑 Waffenspiel-Ziel: Spielen bis Stufe 13 gemeistert ist", left + 36, y + 12, OsokWidgets.COLOR_GOLD);
               graphics.text(font, "Das Match läuft ohne Zeitdruck, bis ein Spieler Stufe 13 mit dem Meisterdolch beendet.", left + 36, y + 27, OsokWidgets.COLOR_TEXT_WHITE);
               graphics.text(font, "Dies ist die empfohlene Standardeinstellung für Waffenspiel-Matches.", left + 36, y + 41, OsokWidgets.COLOR_TEXT_FAINT);
               y += 66;
            } else {
               graphics.item(new ItemStack(Items.COMPASS), left + 10, y + 15);
               graphics.text(font, "Endlos-Modus aktiviert (Kein Zeit- oder Kill-Limit)", left + 36, y + 11, OsokWidgets.COLOR_GOLD);
               graphics.text(font, "Das Match läuft unbegrenzt, bis es in der Match-Steuerung manuell gestoppt wird.", left + 36, y + 27, OsokWidgets.COLOR_TEXT_FAINT);
               y += 56;
            }
         }
      }

      contentLength = y + scroll.offset() - contentTop;
   }

   private void drawSlider(GuiGraphicsExtractor graphics, Slider slider, String label, String displayValue, int mouseX, int mouseY) {
      double ratio = Math.clamp((slider.value - slider.min) / (slider.max - slider.min), 0.0, 1.0);
      boolean isHovered = OsokWidgets.isOver(mouseX, mouseY, slider.x, slider.y, slider.width, slider.height);
      boolean isHeld = activeSlider == slider;

      OsokWidgets.modernSlider(graphics, font, slider.x, slider.y, slider.width, slider.height,
         label, displayValue, ratio, slider.enabled, isHovered, isHeld, OsokWidgets.COLOR_GOLD);

      sliders.add(slider);
   }

   private void drawItemWeightsTab(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
      int left = cardLeft + 16;
      int right = cardLeft + CARD_WIDTH - 16;
      int y = contentTop + 4 - scroll.offset();

      // Item-Modus Card
      graphics.fill(left, y, right, y + 54, 0xFF141A27);
      graphics.horizontalLine(left, right - 1, y, OsokWidgets.COLOR_CARD_BORDER);
      graphics.horizontalLine(left, right - 1, y + 53, OsokWidgets.COLOR_CARD_BORDER);
      graphics.verticalLine(left, y, y + 53, OsokWidgets.COLOR_CARD_BORDER);
      graphics.verticalLine(right - 1, y, y + 53, OsokWidgets.COLOR_CARD_BORDER);

      graphics.text(font, "Item-Spawn-Modus:", left + 10, y + 8, OsokWidgets.COLOR_TEXT_MUTED);
      boolean modeHover = OsokWidgets.isOver(mouseX, mouseY, left + 10, y + 22, 220, 20);
      OsokWidgets.cyberButton(graphics, font, left + 10, y + 22, 220, 20, itemModeLabel(), true, modeHover, OsokWidgets.COLOR_GOLD);
      hotspots.add(new Hotspot(left + 10, y + 22, 220, 20, true,
         () -> ClientPlayNetworking.send(new SetItemModePayload(nextItemMode().name()))));

      graphics.text(font, itemModeDescription(), left + 238, y + 28, OsokWidgets.COLOR_TEXT_FAINT);
      y += 64;

      // Header Zeile für Gewichtungen
      graphics.text(font, "Spezialitems · Gewichtete Spawnchance", left, y + 6, OsokWidgets.COLOR_TEXT_WHITE);
      boolean resetHover = OsokWidgets.isOver(mouseX, mouseY, right - 110, y, 110, 20);
      OsokWidgets.cyberButton(graphics, font, right - 110, y, 110, 20, "Zurücksetzen", true, resetHover, OsokWidgets.COLOR_CRIMSON);
      hotspots.add(new Hotspot(right - 110, y, 110, 20, true,
         () -> ClientPlayNetworking.send(ResetSpecialItemWeightsPayload.EMPTY)));
      y += 28;

      for (SpecialItem item : SpecialItem.values()) {
         drawWeightRow(graphics, item, left, right, y, mouseX, mouseY);
         y += 32;
      }
      contentLength = y + scroll.offset() - contentTop;
   }

   private void drawWeightRow(GuiGraphicsExtractor graphics, SpecialItem item, int left, int right, int y, int mouseX, int mouseY) {
      boolean rowHover = OsokWidgets.isOver(mouseX, mouseY, left, y, right - left, 28)
         && mouseY >= contentTop && mouseY < contentTop + contentHeight;

      int rowBg = rowHover ? 0xFF222B3D : 0xFF141924;
      graphics.fill(left, y, right, y + 28, rowBg);
      graphics.horizontalLine(left, right - 1, y, OsokWidgets.COLOR_CARD_BORDER);
      graphics.horizontalLine(left, right - 1, y + 27, OsokWidgets.COLOR_CARD_BORDER);
      graphics.verticalLine(left, y, y + 27, OsokWidgets.COLOR_CARD_BORDER);
      graphics.verticalLine(right - 1, y, y + 27, OsokWidgets.COLOR_CARD_BORDER);

      graphics.item(new ItemStack(item.getIcon()), left + 6, y + 6);
      graphics.text(font, item.getDisplayName(), left + 30, y + 5, OsokWidgets.COLOR_TEXT_WHITE);

      double chance = spawnChanceFor(item);
      String chanceText = String.format(Locale.GERMANY, "Gewicht: %d · %.1f %%", weightFor(item), chance);
      graphics.text(font, chanceText, left + 30, y + 16, OsokWidgets.COLOR_TEXT_FAINT);

      int x = right - (WEIGHT_BUTTON_WIDTH + 4) * 4 - 4;
      for (int[] step : new int[][] {{-5}, {-1}, {1}, {5}}) {
         int adjustment = step[0];
         String label = adjustment < 0 ? "−" + Math.abs(adjustment) : "+" + adjustment;
         boolean btnHover = OsokWidgets.isOver(mouseX, mouseY, x, y + 4, WEIGHT_BUTTON_WIDTH, 20)
            && mouseY >= contentTop && mouseY < contentTop + contentHeight;

         int accent = adjustment < 0 ? OsokWidgets.COLOR_CRIMSON : OsokWidgets.COLOR_EMERALD;
         OsokWidgets.cyberButton(graphics, font, x, y + 4, WEIGHT_BUTTON_WIDTH, 20, label, true, btnHover, accent);

         hotspots.add(new Hotspot(x, y + 4, WEIGHT_BUTTON_WIDTH, 20, true,
            () -> ClientPlayNetworking.send(new AdjustSpecialItemWeightPayload(item.getId(), adjustment))));
         x += WEIGHT_BUTTON_WIDTH + 4;
      }
   }

   private void drawMatchStateBadge(GuiGraphicsExtractor graphics, int rightX, int y) {
      if (isMatchState(MatchState.RUNNING)) {
         String text = "Match LIVE";
         int width = font.width(text) + 18;
         OsokWidgets.statusBadge(graphics, font, rightX - width, y, text, OsokWidgets.COLOR_EMERALD, true);
      } else if (isMatchState(MatchState.PAUSED)) {
         String text = "Pausiert";
         int width = font.width(text) + 18;
         OsokWidgets.statusBadge(graphics, font, rightX - width, y, text, OsokWidgets.COLOR_AMBER, false);
      } else {
         String text = "Gestoppt";
         int width = font.width(text) + 18;
         OsokWidgets.statusBadge(graphics, font, rightX - width, y, text, OsokWidgets.COLOR_TEXT_DISABLED, false);
      }
   }

   private void drawFooter(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int y) {
      graphics.item(new ItemStack(Items.PAPER), cardLeft + 16, y - 4);
      graphics.text(font, Component.literal("Schnelltaste: ").append(OsokClient.menuKeyName()).append(" oder [ESC] zum Schließen"),
         cardLeft + 38, y, OsokWidgets.COLOR_TEXT_FAINT);

      int closeX = cardLeft + CARD_WIDTH - 16 - BUTTON_WIDTH;
      boolean closeHover = OsokWidgets.isOver(mouseX, mouseY, closeX, y - 6, BUTTON_WIDTH, 20);
      OsokWidgets.cyberButton(graphics, font, closeX, y - 6, BUTTON_WIDTH, 20, "Schließen", true, closeHover, OsokWidgets.COLOR_CARD_BORDER);
      hotspots.add(new Hotspot(closeX, y - 6, BUTTON_WIDTH, 20, true, false, this::onClose));
   }

   private void drawScrollbar(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
      int trackX = scrollbarTrackX();
      boolean hovered = mouseX >= trackX - 3 && mouseX <= trackX + 7
         && mouseY >= contentTop && mouseY < contentTop + contentHeight;
      OsokWidgets.scrollbar(graphics, trackX, contentTop, contentHeight, contentLength, contentHeight,
         scroll.offset(), OsokWidgets.COLOR_GOLD, hovered, scroll.isDragging());
   }

   private int scrollbarTrackX() {
      return cardLeft + CARD_WIDTH - SCROLLBAR_INSET;
   }

   // -- Eingabe -------------------------------------------------------------

   @Override
   public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      if (event.button() == 0) {
         // Der Scrollbalken hat Vorrang: Er liegt rechts außerhalb des Inhalts und würde sonst
         // von einem darunterliegenden Hotspot verdeckt.
         if (scroll.beginDrag(event.x(), event.y(), scrollbarTrackX(), contentTop,
            contentHeight, contentLength)) {
            return true;
         }

         // Sliders first (mit großzügigem Klickbereich)
         for (Slider slider : sliders) {
            if (slider.enabled && OsokWidgets.isOver(event.x(), event.y(), slider.x - 8, slider.y - 6, slider.width + 16, slider.height + 12)) {
               if (event.y() >= contentTop && event.y() < contentTop + contentHeight) {
                  activeSlider = slider;
                  double val = slider.getValueFromMouse(event.x());
                  slider.onValueChange.accept(val);
                  clickSound();
                  return true;
               }
            }
         }

         for (Hotspot hotspot : hotspots) {
            if (!hotspot.enabled || !OsokWidgets.isOver(event.x(), event.y(), hotspot.x, hotspot.y, hotspot.width, hotspot.height)) {
               continue;
            }
            if (hotspot.scrollable && (event.y() < contentTop || event.y() >= contentTop + contentHeight)) {
               continue;
            }
            if (hotspot.playSound) {
               clickSound();
            }
            hotspot.action.run();
            return true;
         }
      }
      return super.mouseClicked(event, doubleClick);
   }

   @Override
   public boolean mouseReleased(MouseButtonEvent event) {
      if (event.button() == 0) {
         activeSlider = null;
         lastSliderValue = Double.NaN;
         scroll.endDrag();
      }
      return super.mouseReleased(event);
   }

   @Override
   public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
      if (event.button() != 0) {
         return super.mouseDragged(event, deltaX, deltaY);
      }
      if (scroll.isDragging()) {
         scroll.drag(event.y(), contentTop, contentHeight, contentLength);
         return true;
      }
      if (activeSlider != null && activeSlider.enabled) {
         double val = activeSlider.getValueFromMouse(event.x());
         activeSlider.onValueChange.accept(val);
         // Feines Tickern nur bei einem echten Rastschritt, nicht bei jeder Mausbewegung.
         if (Double.isNaN(lastSliderValue) || Math.abs(val - lastSliderValue) > 1.0e-6) {
            double span = activeSlider.max - activeSlider.min;
            float ratio = span > 0.0 ? (float) ((val - activeSlider.min) / span) : 0.0F;
            tickSound(ratio);
            lastSliderValue = val;
         }
         return true;
      }
      return super.mouseDragged(event, deltaX, deltaY);
   }

   @Override
   public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
      int overflow = contentLength - contentHeight;
      if (overflow > 0) {
         scroll.scrollBy((float) -scrollY * 26.0F, overflow);
         return true;
      }
      return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
   }

   private void updateCursor(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
      graphics.requestCursor(CursorTypes.ARROW);

      if (activeSlider != null) {
         graphics.requestCursor(CursorTypes.RESIZE_EW);
         return;
      }

      if (scroll.isDragging()) {
         graphics.requestCursor(CursorTypes.RESIZE_NS);
         return;
      }
      int trackX = scrollbarTrackX();
      if (contentLength > contentHeight && mouseX >= trackX - 3 && mouseX <= trackX + 7
         && mouseY >= contentTop && mouseY < contentTop + contentHeight) {
         graphics.requestCursor(CursorTypes.POINTING_HAND);
         return;
      }

      for (int i = sliders.size() - 1; i >= 0; i--) {
         Slider slider = sliders.get(i);
         if (mouseY >= contentTop && mouseY < contentTop + contentHeight
            && OsokWidgets.isOver(mouseX, mouseY, slider.x - 8, slider.y - 6,
               slider.width + 16, slider.height + 12)) {
            graphics.requestCursor(slider.enabled ? CursorTypes.POINTING_HAND : CursorTypes.NOT_ALLOWED);
            return;
         }
      }

      for (int i = hotspots.size() - 1; i >= 0; i--) {
         Hotspot hotspot = hotspots.get(i);
         if (!OsokWidgets.isOver(mouseX, mouseY, hotspot.x, hotspot.y, hotspot.width, hotspot.height)) {
            continue;
         }
         if (hotspot.scrollable && (mouseY < contentTop || mouseY >= contentTop + contentHeight)) {
            continue;
         }
         graphics.requestCursor(hotspot.pointer ? CursorTypes.POINTING_HAND : CursorTypes.NOT_ALLOWED);
         return;
      }
   }

   private void selectTab(Tab tab) {
      if (currentTab == tab) {
         return;
      }
      SCROLL_MEMORY.put(currentTab, scroll.value());
      currentTab = tab;
      lastSelectedTab = tab;
      tabChangedAt = Util.getMillis();
      scroll.set(SCROLL_MEMORY.getOrDefault(tab, 0.0F));
   }

   private void clickSound() {
      Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
   }

   /** Leises Rastern beim Ziehen eines Reglers; die Tonhöhe folgt dem eingestellten Anteil. */
   private void tickSound(float ratio) {
      Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(
         SoundEvents.NOTE_BLOCK_HAT.value(), 1.35F + Math.clamp(ratio, 0.0F, 1.0F) * 0.55F, 0.16F));
   }

   // -- Zustand -------------------------------------------------------------

   private boolean isMatchState(MatchState expected) {
      return expected.name().equals(state.getMatchState());
   }

   private MatchTargetMode matchTargetMode() {
      return MatchTargetMode.fromName(state.getMatchTargetMode());
   }

   private int weightFor(SpecialItem item) {
      List<Integer> weights = state.getItemWeights();
      int index = item.ordinal();
      return index >= 0 && index < weights.size() ? weights.get(index) : SpecialItemManager.DEFAULT_WEIGHT;
   }

   private double spawnChanceFor(SpecialItem item) {
      int total = state.getItemWeights().stream().mapToInt(Integer::intValue).sum();
      return total <= 0 ? 0.0 : weightFor(item) * 100.0 / total;
   }

   private SpecialItem.Mode itemMode() {
      return Arrays.stream(SpecialItem.Mode.values())
         .filter(mode -> mode.name().equals(state.getItemMode()))
         .findFirst().orElse(SpecialItem.Mode.BOTH);
   }

   private SpecialItem.Mode nextItemMode() {
      return switch (itemMode()) {
         case STREAK -> SpecialItem.Mode.SPAWN;
         case SPAWN -> SpecialItem.Mode.BOTH;
         case BOTH -> SpecialItem.Mode.STREAK;
      };
   }

   private String itemModeLabel() {
      return switch (itemMode()) {
         case STREAK -> "🏆 Nur Killstreaks";
         case SPAWN -> "📦 Nur Boden-Spawns";
         case BOTH -> "✨ Killstreaks + Boden-Spawns";
      };
   }

   private String itemModeDescription() {
      return switch (itemMode()) {
         case STREAK -> "Bodenboxen sind deaktiviert.";
         case SPAWN -> "Items erscheinen ausschließlich alle 30 Sekunden auf dem Arena-Boden.";
         case BOTH -> "Bodenboxen erscheinen alle 30 Sekunden; künftige Killstreak-Belohnungen bleiben aktiv.";
      };
   }

   private int stateColor(boolean isActive, boolean isOpen, boolean isResetting) {
      if (isResetting) {
         return OsokWidgets.COLOR_CYAN;
      }
      if (!isOpen) {
         return OsokWidgets.COLOR_CRIMSON;
      }
      return isActive ? OsokWidgets.COLOR_GOLD : OsokWidgets.COLOR_TEXT_MUTED;
   }

   private Item itemFor(Arena arena) {
      return switch (arena) {
         case STANDARD -> Items.STONE_BRICKS;
         case DUSTPVP -> Items.SANDSTONE;
         case BO2 -> Items.BRICKS;
      };
   }

   private record Hotspot(int x, int y, int width, int height, boolean enabled, boolean pointer,
                          boolean scrollable, boolean playSound, Runnable action) {
      public Hotspot(int x, int y, int width, int height, boolean enabled, boolean pointer,
                     boolean scrollable, Runnable action) {
         this(x, y, width, height, enabled, pointer, scrollable, true, action);
      }

      public Hotspot(int x, int y, int width, int height, boolean enabled, boolean scrollable,
                     Runnable action) {
         this(x, y, width, height, enabled, enabled, scrollable, true, action);
      }

      public Hotspot(int x, int y, int width, int height, boolean enabled, Runnable action) {
         this(x, y, width, height, enabled, enabled, true, true, action);
      }
   }

   private record Slider(int x, int y, int width, int height, double min, double max, double value, double step, boolean enabled, Consumer<Double> onValueChange) {
      public double getValueFromMouse(double mouseX) {
         int thumbWidth = 12;
         double trackInnerWidth = Math.max(1.0, width - thumbWidth);
         double relativeX = mouseX - (x + thumbWidth / 2.0);
         double ratio = Math.clamp(relativeX / trackInnerWidth, 0.0, 1.0);
         double rawVal = min + ratio * (max - min);
         if (step > 0) {
            rawVal = Math.round((rawVal - min) / step) * step + min;
         }
         return Math.clamp(rawVal, min, max);
      }
   }

   public enum Tab {
      ARENAS("🗺 Arenen"),
      MATCH_CONTROL("🎮 Match-Steuerung"),
      MATCH_TARGET("⏱ Match-Dauer"),
      ITEM_WEIGHTS("🎲 Itemgewichtungen");

      private final String label;

      Tab(String label) {
         this.label = label;
      }
   }
}

