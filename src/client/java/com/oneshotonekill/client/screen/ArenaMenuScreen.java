package com.oneshotonekill.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.client.OsokClient;
import com.oneshotonekill.client.state.ClientStates.MatchStartState;
import com.oneshotonekill.client.state.ClientStates.MinimapState;
import com.oneshotonekill.client.state.ClientStates.NukeState;
import com.oneshotonekill.item.SpecialItem;
import com.oneshotonekill.item.box.SpecialItemManager;
import com.oneshotonekill.match.MatchManager.MatchState;
import com.oneshotonekill.match.MatchManager.MatchTargetMode;
import com.oneshotonekill.network.OsokPayloads.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.*;
import java.util.function.Consumer;

/**
 * Die moderne Cyber-Tactical Verwaltungsoberfläche für Arenen, Match-Ablauf, Match-Dauer und Itemgewichtungen.
 */
@SuppressWarnings({"ConstantValue", "NullableProblems", "SameParameterValue", "UnnecessaryLocalVariable"})
public final class ArenaMenuScreen extends Screen {
    private static final int MAX_CARD_WIDTH = 580;
    /** Schmaler wird die Karte nicht; darunter passen Reiter und Schaltflächen nicht mehr nebeneinander. */
    private static final int MIN_CARD_WIDTH = 420;
    private static final int TAB_CONTENT_HEIGHT = 270;
    private static final int MIN_TAB_CONTENT_HEIGHT = 100;
    private static final int CHROME_HEIGHT = 156;
    private static final int BUTTON_WIDTH = 76;
    private static final int CONTROL_BUTTON_WIDTH = 110;
    private static final int WEIGHT_BUTTON_WIDTH = 34;
    private static final int ARENA_ROW_HEIGHT = 44;
    private static final int SCROLLBAR_INSET = 9;
    private static final int EDGE_FADE = 10;
    private static final long ENTRANCE_MILLIS = 150L;

    /**
     * Jeder Reiter merkt sich seine eigene Scrollposition über das Schließen hinweg.
     */
    private static final Map<Tab, Float> SCROLL_MEMORY = new EnumMap<>(Tab.class);
    private static Tab lastSelectedTab = Tab.ARENAS;
    private static AdminItemScreen.ItemCategory weightFilterCategory = AdminItemScreen.ItemCategory.ALL;
    private final List<Hotspot> hotspots = new ArrayList<>();
    private final List<Slider> sliders = new ArrayList<>();
    private final OsokWidgets.ScrollMotion scroll = new OsokWidgets.ScrollMotion();
    private final long openedAt = Util.getMillis();
    private ArenaMenuStatePayload state;
    private Tab currentTab;
    private Slider activeSlider = null;
    private Integer localPreviewMinutes = null;
    private Integer localPreviewKills = null;
    private int cardWidth = MAX_CARD_WIDTH;
    private int cardLeft;
    private int cardTop;
    private int cardHeight;
    private int contentTop;
    private int contentHeight;
    private int contentLength;
    private long lastFrameMillis = Long.MIN_VALUE;
    private final OsokWidgets.TabIndicator tabIndicator = new OsokWidgets.TabIndicator();
    private long tabChangedAt = Long.MIN_VALUE;
    private double lastSliderValue = Double.NaN;

    public ArenaMenuScreen(ArenaMenuStatePayload state) {
        super(Component.translatable("gui.oneshotonekill.menu.title"));
        this.state = state;
        this.currentTab = lastSelectedTab;
        this.scroll.set(SCROLL_MEMORY.getOrDefault(currentTab, 0.0F));
    }

    private static String lastActiveArenaId = Arena.STANDARD.getId();

    public static String getLastActiveArenaId() {
        return lastActiveArenaId;
    }

    /**
     * Aktualisiert ein offenes Menü oder öffnet es, wenn der Server darum bittet.
     */
    public static void show(ArenaMenuStatePayload state, Minecraft client) {
        if (state == null) {
            return;
        }
        if (state.getActiveArenaId() != null) {
            lastActiveArenaId = state.getActiveArenaId();
        }
        MinimapState.INSTANCE.setMatchRunning("RUNNING".equalsIgnoreCase(state.getMatchState())
                && !MatchStartState.INSTANCE.isCountdownActive());
        if (client.gui.screen() instanceof ArenaMenuScreen screen) {
            screen.state = state;
            if (screen.activeSlider == null) {
                screen.localPreviewMinutes = null;
                screen.localPreviewKills = null;
            }
        } else if (state.open()) {
            client.gui.setScreen(new ArenaMenuScreen(state));
        }
    }

    @Override
    protected void init() {
        cardWidth = Math.clamp(width - 16, MIN_CARD_WIDTH, MAX_CARD_WIDTH);
        int[] b = OsokWidgets.CardLayout.compute(width, height, cardWidth, CHROME_HEIGHT, MIN_TAB_CONTENT_HEIGHT, TAB_CONTENT_HEIGHT, 84).bounds();
        cardLeft = b[0];
        cardTop = b[1];
        cardHeight = b[2];
        contentTop = b[3];
        contentHeight = b[4];

        scroll.clampNow(contentLength - contentHeight);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (OsokClient.isMenuKey(event)) {
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void removed() {
        SCROLL_MEMORY.put(currentTab, scroll.value());
        lastSelectedTab = currentTab;
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

    /**
     * Vergangene Echtzeit seit dem letzten Bild, gedeckelt gegen Sprünge nach einem Ruckler.
     */
    private float advanceClock() {
        long now = Util.getMillis();
        float delta = OsokWidgets.advanceClock(lastFrameMillis, now);
        lastFrameMillis = now;
        return delta;
    }

    /**
     * Federnder Auftritt von 0,95 auf 1,0 mit leichtem Überschwingen.
     */
    private float entranceScale() {
        return OsokWidgets.entranceScale(openedAt, ENTRANCE_MILLIS);
    }

    /**
     * Versatz des Inhalts direkt nach einem Reiterwechsel.
     */
    private int tabSlideOffset() {
        return OsokWidgets.tabSlideOffset(tabChangedAt, 180);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        renderMenuContent(graphics, mouseX, mouseY, advanceClock());
        super.extractRenderState(graphics, mouseX, mouseY, partial);
        updateCursor(graphics, mouseX, mouseY);
    }

    private void renderMenuContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        scroll.advance(delta, contentLength - contentHeight);
        OsokWidgets.startGlassFrame(graphics, width, height, cardLeft, cardTop, cardWidth, cardHeight, entranceScale());

        hotspots.clear();
        sliders.clear();

        drawHeader(graphics);
        drawTabs(graphics, mouseX, mouseY, delta);
        OsokWidgets.divider(graphics, cardLeft + 16, cardLeft + cardWidth - 16, contentTop - 6, OsokWidgets.COLOR_CARD_BORDER);

        drawCurrentTab(graphics, mouseX, mouseY);
        drawScrollbar(graphics, mouseX, mouseY);

        int footerY = contentTop + contentHeight + 16;
        OsokWidgets.divider(graphics, cardLeft + 16, cardLeft + cardWidth - 16, footerY - 8, OsokWidgets.COLOR_CARD_BORDER);
        drawFooter(graphics, mouseX, mouseY, footerY);

        OsokWidgets.endGlassFrame(graphics);
    }

    private void drawCurrentTab(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int contentLeft = cardLeft + 16;
        int contentRight = cardLeft + cardWidth - 16;
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
        OsokWidgets.drawSoftScrollEdges(graphics, contentLeft, contentRight, contentTop, contentBottom,
                EDGE_FADE, OsokWidgets.COLOR_CARD_BG, scroll.offset(), contentLength, contentHeight);
        graphics.disableScissor();
    }

    private void drawHeader(GuiGraphicsExtractor graphics) {
        graphics.text(font, "✦ OneShotOneKill", cardLeft + 16, cardTop + 14, OsokWidgets.COLOR_GOLD);
        graphics.text(font, Component.translatable("gui.oneshotonekill.menu.subtitle"), cardLeft + 16 + font.width("✦ OneShotOneKill ") + 4, cardTop + 14, OsokWidgets.COLOR_TEXT_MUTED);

        // Live-Match-Status Badge rechts oben
        drawMatchStateBadge(graphics, cardLeft + cardWidth - 16, cardTop + 12);
    }

    private void drawTabs(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int x = cardLeft + 16;
        int y = cardTop + 36;
        float activeX = x;
        float activeWidth = 0.0F;

        for (Tab tab : Tab.values()) {
            String label = tab.label();
            int tabWidth = font.width(label) + 16;
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
            // playSound: false, da selectTab bereits playTabSwitchSound abspielt
            hotspots.add(new Hotspot(x, y, tabWidth, 22, true, true, false, false, () -> selectTab(target)));
            x += tabWidth + 6;
        }

        // Der Unterstrich gleitet auf den gewählten Reiter, statt dorthin zu springen.
        tabIndicator.advance(activeX, activeWidth, delta);
        tabIndicator.draw(graphics, y + 20.0F, 2.0F, OsokWidgets.COLOR_GOLD);
    }

    private void drawArenasTab(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int left = cardLeft + 16;
        int right = cardLeft + cardWidth - 16;
        int y = contentTop + 4 - scroll.offset();

        if (!isMatchState(MatchState.STOPPED)) {
            OsokWidgets.alertBanner(graphics, font, left, y, right - left, 26,
                    new ItemStack(Items.BARRIER), Component.translatable("gui.oneshotonekill.menu.arena_locked_alert").getString(), OsokWidgets.COLOR_CRIMSON);
            y += 32;
        }

        for (Arena arena : Arena.values()) {
            drawArenaRow(graphics, arena, left, right, y, mouseX, mouseY);
            y += ARENA_ROW_HEIGHT + 6;
        }
        contentLength = y + scroll.offset() - contentTop;
    }

    private void drawArenaRow(GuiGraphicsExtractor graphics, Arena arena, int left, int right, int y, int mouseX, int mouseY) {
        boolean isActive = arena.getId().equals(state.getActiveArenaId());
        boolean isPlayerHere = arena.getId().equals(state.getPlayerArenaId());
        boolean isOpen = state.getOpenArenaIds().contains(arena.getId());
        boolean isResetting = arena.getId().equals(state.getResettingArenaId());
        boolean hovered = OsokWidgets.isOver(mouseX, mouseY, left, y, right - left, ARENA_ROW_HEIGHT)
                && mouseY >= contentTop && mouseY < contentTop + contentHeight;

        int rowBg = isActive ? 0xFF241F16 : (hovered ? 0xFF222B3D : 0xFF141924);
        int rowBorder = isActive ? OsokWidgets.COLOR_GOLD : (hovered ? OsokWidgets.COLOR_CARD_BORDER_HOVER : OsokWidgets.COLOR_CARD_BORDER);

        OsokWidgets.panel(graphics, left, y, right, y + ARENA_ROW_HEIGHT, rowBg, rowBorder);

        // Status-Streifen links
        int statusCol = stateColor(isActive, isOpen, isResetting);
        graphics.fill(left + 2, y + 2, left + 5, y + ARENA_ROW_HEIGHT - 2, statusCol);

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
                graphics.fill(px, y + 1, px + 1, y + ARENA_ROW_HEIGHT - 1, rgb | (alpha << 24));
            }
        }

        // Arena-Icon
        graphics.item(new ItemStack(itemFor(arena)), left + 10, y + 14);
        graphics.text(font, arena.getDisplayName(), left + 36, y + 8, OsokWidgets.COLOR_TEXT_WHITE);

        Component subtitle = switch (arena) {
            case STANDARD -> Component.translatable("gui.oneshotonekill.menu.arena_sub_standard");
            case DUSTPVP -> Component.translatable("gui.oneshotonekill.menu.arena_sub_dustpvp");
            case BO2 -> Component.translatable("gui.oneshotonekill.menu.arena_sub_bo2");
            case TILTED_TOWERS -> Component.translatable("gui.oneshotonekill.menu.arena_sub_tilted");
        };
        graphics.text(font, subtitle, left + 36, y + 25, OsokWidgets.COLOR_TEXT_FAINT);

        // Status-Tags
        int badgeX = left + 40 + font.width(arena.getDisplayName());
        if (isActive) {
            String activeText = Component.translatable("gui.oneshotonekill.menu.arena_active").getString();
            OsokWidgets.statusBadge(graphics, font, badgeX, y + 6, activeText, OsokWidgets.COLOR_GOLD, true);
            badgeX += font.width(activeText) + 22;
        }
        if (isPlayerHere) {
            OsokWidgets.statusBadge(graphics, font, badgeX, y + 6, Component.translatable("gui.oneshotonekill.menu.arena_here").getString(), OsokWidgets.COLOR_EMERALD, true);
        }

        boolean stopped = isMatchState(MatchState.STOPPED);
        int switchX = right - BUTTON_WIDTH * 2 - 8;
        int resetX = right - BUTTON_WIDTH - 2;
        boolean canSwitch = stopped && isOpen && !isActive && !isResetting;
        boolean canReset = stopped && isOpen && state.getResettingArenaId().isEmpty();

        int btnY = y + 12;
        boolean switchHover = OsokWidgets.isOver(mouseX, mouseY, switchX, btnY, BUTTON_WIDTH, 20);
        boolean resetHover = OsokWidgets.isOver(mouseX, mouseY, resetX, btnY, BUTTON_WIDTH, 20);

        String switchLabel = isActive
                ? Component.translatable("gui.oneshotonekill.menu.arena_active").getString()
                : Component.translatable("gui.oneshotonekill.menu.arena_select").getString();
        String resetLabel = isResetting
                ? Component.translatable("gui.oneshotonekill.menu.arena_loading").getString()
                : Component.translatable("gui.oneshotonekill.menu.arena_reset").getString();

        OsokWidgets.cyberButton(graphics, font, switchX, btnY, BUTTON_WIDTH, 20, switchLabel,
                canSwitch, switchHover, OsokWidgets.COLOR_GOLD);
        OsokWidgets.cyberButton(graphics, font, resetX, btnY, BUTTON_WIDTH, 20, resetLabel,
                canReset, resetHover, OsokWidgets.COLOR_CYAN);

        hotspots.add(new Hotspot(switchX, btnY, BUTTON_WIDTH, 20, canSwitch,
                stopped && isOpen && !isResetting, true, false,
                () -> {
                    ClientPlayNetworking.send(new SelectArenaPayload(arena.getId()));
                    OsokWidgets.playActionSound();
                }));
        hotspots.add(new Hotspot(resetX, btnY, BUTTON_WIDTH, 20, canReset,
                true, true, false,
                () -> {
                    ClientPlayNetworking.send(new ResetArenaPayload(arena.getId()));
                    OsokWidgets.playActionSound();
                }));
    }

    private void drawMatchControlTab(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int left = cardLeft + 16;
        int right = cardLeft + cardWidth - 16;
        int y = contentTop + 4 - scroll.offset();

        // Dashboard Info Card
        OsokWidgets.panel(graphics, left, y, right, y + 50, 0xFF141A27, OsokWidgets.COLOR_CARD_BORDER);

        graphics.text(font, Component.translatable("gui.oneshotonekill.menu.control_card_title"), left + 12, y + 10, OsokWidgets.COLOR_TEXT_WHITE);
        graphics.text(font, Component.translatable("gui.oneshotonekill.menu.control_card_desc"), left + 12, y + 28, OsokWidgets.COLOR_TEXT_FAINT);

        y += 62;

        boolean isPaused = isMatchState(MatchState.PAUSED);
        boolean isCountdown = MatchStartState.INSTANCE.isCountdownActive();
        boolean isNuke = NukeState.INSTANCE.isRunning();
        boolean canStart = isMatchState(MatchState.STOPPED) && !isNuke;
        boolean canPause = (isMatchState(MatchState.RUNNING) || isPaused) && !isNuke;
        boolean canRespawn = isMatchState(MatchState.RUNNING) && state.isOutsideArena() && !isCountdown && !isNuke;

        int x = left;
        controlButton(graphics, x, y, Component.translatable("gui.oneshotonekill.menu.btn_start").getString(), canStart, mouseX, mouseY, true, false, OsokWidgets.COLOR_EMERALD,
                () -> {
                    ClientPlayNetworking.send(StartMatchPayload.EMPTY);
                    onClose();
                });
        x += CONTROL_BUTTON_WIDTH + 8;
        controlButton(graphics, x, y, isPaused ? Component.translatable("gui.oneshotonekill.menu.btn_resume").getString() : Component.translatable("gui.oneshotonekill.menu.btn_pause").getString(),
                canPause, mouseX, mouseY, false, !isPaused, OsokWidgets.COLOR_AMBER,
                () -> {
                    ClientPlayNetworking.send(isPaused ? StartMatchPayload.EMPTY : PauseMatchPayload.EMPTY);
                    onClose();
                });
        x += CONTROL_BUTTON_WIDTH + 8;
        controlButton(graphics, x, y, Component.translatable("gui.oneshotonekill.menu.btn_stop").getString(), !isMatchState(MatchState.STOPPED), mouseX, mouseY, false, OsokWidgets.COLOR_CRIMSON,
                () -> {
                    ClientPlayNetworking.send(StopMatchPayload.EMPTY);
                    onClose();
                });
        x += CONTROL_BUTTON_WIDTH + 8;
        controlButton(graphics, x, y, Component.translatable("gui.oneshotonekill.menu.btn_respawn").getString(), canRespawn, mouseX, mouseY, true, OsokWidgets.COLOR_CYAN,
                () -> {
                    ClientPlayNetworking.send(RequestRespawnPayload.EMPTY);
                    onClose();
                });

        y += 32;
        boolean isAdmin = Minecraft.getInstance().player != null
                && OneShotOneKill.isAdmin(Minecraft.getInstance().player);
        String arrowLabel = isAdmin
                ? Component.translatable("gui.oneshotonekill.menu.btn_clear_arrows").getString()
                : Component.translatable("gui.oneshotonekill.menu.btn_clear_arrows_locked").getString();
        controlButton(graphics, left, y, arrowLabel, isAdmin, mouseX, mouseY, false, OsokWidgets.COLOR_CYAN,
                () -> {
                    ClientPlayNetworking.send(ClearArrowsPayload.EMPTY);
                    OsokWidgets.playActionSound();
                });

        y += 36;
        OsokWidgets.divider(graphics, left, right, y, OsokWidgets.COLOR_CARD_BORDER);
        y += 10;

        // Spielmodus-Auswahl
        graphics.text(font, Component.translatable("gui.oneshotonekill.menu.mode_select_title"), left, y, OsokWidgets.COLOR_GOLD);
        graphics.text(font, Component.translatable("gui.oneshotonekill.menu.mode_select_desc"), left, y + 14, OsokWidgets.COLOR_TEXT_FAINT);
        y += 30;

        boolean stopped = isMatchState(MatchState.STOPPED);
        String currentMode = state.getGameMode() != null ? state.getGameMode() : "CLASSIC";
        boolean isClassic = "CLASSIC".equalsIgnoreCase(currentMode);
        boolean isGunGame = "GUN_GAME".equalsIgnoreCase(currentMode);

        int modeCardW = (right - left - 12) / 2;
        drawModeSelectionCard(graphics, left, y, modeCardW, 46,
                Component.translatable("gui.oneshotonekill.menu.mode_classic_title").getString(),
                Component.translatable("gui.oneshotonekill.menu.mode_classic_desc").getString(),
                isClassic, stopped, mouseX, mouseY,
                () -> ClientPlayNetworking.send(new SetGameModePayload("CLASSIC")));
        drawModeSelectionCard(graphics, left + modeCardW + 12, y, modeCardW, 46,
                Component.translatable("gui.oneshotonekill.menu.mode_gungame_title").getString(),
                Component.translatable("gui.oneshotonekill.menu.mode_gungame_desc").getString(),
                isGunGame, stopped, mouseX, mouseY,
                () -> ClientPlayNetworking.send(new SetGameModePayload("GUN_GAME")));

        y += 56;

        if (isGunGame) {
            y = drawGunGameTierList(graphics, left, right, y);
        }

        contentLength = y + 10 + scroll.offset() - contentTop;
    }


    /**
     * Die Stufenliste des Waffenspiels, gruppiert nach den vier Akten, in zwei Spalten je Akt.
     *
     * @return die Zeile unter der Liste
     */
    private int drawGunGameTierList(GuiGraphicsExtractor graphics, int left, int right, int y) {
        com.oneshotonekill.match.GunGameTier[] tiers = com.oneshotonekill.match.GunGameTier.values();
        int[] perAct = new int[5];
        for (com.oneshotonekill.match.GunGameTier tier : tiers) {
            perAct[tier.act()]++;
        }
        int rowHeight = 20;
        int height = 30;
        for (int act = 1; act <= 4; act++) {
            height += 18 + ((perAct[act] + 1) / 2) * rowHeight + 4;
        }

        OsokWidgets.panel(graphics, left, y, right, y + height, 0xDD101522, OsokWidgets.COLOR_GOLD);
        graphics.text(font, Component.translatable("gui.oneshotonekill.menu.gungame_rules_title", tiers.length),
                left + 10, y + 8, OsokWidgets.COLOR_GOLD);

        int cursor = y + 26;
        int columnWidth = (right - left - 20) / 2;
        for (int act = 1; act <= 4; act++) {
            int color = com.oneshotonekill.client.hud.GunGameHudLayer.actColor(act);
            graphics.text(font, Component.translatable("gui.oneshotonekill.menu.gungame_act_" + act), left + 10, cursor, color);
            graphics.horizontalLine(left + 10, right - 11, cursor + 11, (color & 0x00FFFFFF) | 0x66000000);
            cursor += 18;
            int slot = 0;
            for (com.oneshotonekill.match.GunGameTier tier : tiers) {
                if (tier.act() != act) {
                    continue;
                }
                int x = left + 10 + (slot % 2) * columnWidth;
                int rowY = cursor + (slot / 2) * rowHeight;
                graphics.item(tier.iconStack(), x, rowY);
                String label = tier.index() + ". " + Component.translatable(tier.nameKey()).getString()
                        + " ×" + tier.requiredKills();
                graphics.text(font, font.plainSubstrByWidth(label, columnWidth - 26), x + 20, rowY + 4, 0xFFE2E8F0);
                slot++;
            }
            cursor += ((perAct[act] + 1) / 2) * rowHeight + 4;
        }
        return y + height + 8;
    }

    private void drawModeSelectionCard(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
                                       String title, String desc, boolean selected, boolean enabled,
                                       int mouseX, int mouseY, Runnable onSelect) {
        boolean hovered = OsokWidgets.isOver(mouseX, mouseY, x, y, w, h) && enabled;
        int bg = selected ? 0xFF241F16 : (hovered ? 0xFF1C2433 : 0xFF121722);
        int border = selected ? OsokWidgets.COLOR_GOLD : (hovered ? OsokWidgets.COLOR_CARD_BORDER_HOVER : OsokWidgets.COLOR_CARD_BORDER);

        OsokWidgets.panel(graphics, x, y, x + w, y + h, bg, border);

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
        int right = cardLeft + cardWidth - 16;
        int y = contentTop + 4 - scroll.offset();
        boolean stopped = isMatchState(MatchState.STOPPED);
        boolean isGunGame = "GUN_GAME".equalsIgnoreCase(state.getGameMode());

        if (!stopped) {
            OsokWidgets.alertBanner(graphics, font, left, y, right - left, 26,
                    new ItemStack(Items.BARRIER), Component.translatable("gui.oneshotonekill.menu.target_locked_alert").getString(), OsokWidgets.COLOR_CRIMSON);
            y += 32;
        }

        if (isGunGame) {
            // Gun Game Modus Info Banner
            OsokWidgets.panel(graphics, left, y, right, y + 42, 0xFF241F16, OsokWidgets.COLOR_GOLD);

            graphics.item(com.oneshotonekill.match.GunGameTier.MASTER.iconStack(), left + 8, y + 13);
            graphics.text(font, Component.translatable("gui.oneshotonekill.menu.target_gungame_banner_title"), left + 32, y + 8, OsokWidgets.COLOR_GOLD);
            graphics.text(font, Component.translatable("gui.oneshotonekill.menu.target_gungame_banner_desc"), left + 32, y + 24, OsokWidgets.COLOR_TEXT_FAINT);
            y += 50;
        }

        // Modus-Auswahl Panel
        OsokWidgets.panel(graphics, left, y, right, y + 56, 0xFF141A27, OsokWidgets.COLOR_CARD_BORDER);

        graphics.text(font, isGunGame ? Component.translatable("gui.oneshotonekill.menu.target_section_title_gungame") : Component.translatable("gui.oneshotonekill.menu.target_section_title"), left + 10, y + 8, OsokWidgets.COLOR_TEXT_MUTED);

        MatchTargetMode currentMode = matchTargetMode();
        int buttonWidth = (right - left - 24) / 3;
        int modeX = left + 8;
        for (MatchTargetMode mode : MatchTargetMode.values()) {
            boolean isSelected = mode == currentMode;
            boolean isKillLimitInGunGame = isGunGame && mode == MatchTargetMode.KILL_LIMIT;
            boolean canClick = stopped && !isSelected && !isKillLimitInGunGame;
            boolean hovered = OsokWidgets.isOver(mouseX, mouseY, modeX, y + 24, buttonWidth, 22);

            int accentColor = isKillLimitInGunGame ? OsokWidgets.COLOR_TEXT_DISABLED : (isSelected ? OsokWidgets.COLOR_GOLD : OsokWidgets.COLOR_CARD_BORDER);
            OsokWidgets.cyberButton(graphics, font, modeX, y + 24, buttonWidth, 22,
                    getTargetModeLabel(mode, isGunGame),
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
                String formattedTime = Component.translatable("gui.oneshotonekill.menu.target_time_minutes", effectiveMins).getString();

                OsokWidgets.panel(graphics, left, y, right, y + 46, 0xFF141924, OsokWidgets.COLOR_CARD_BORDER);

                graphics.item(new ItemStack(Items.CLOCK), left + 10, y + 15);
                graphics.text(font, Component.translatable("gui.oneshotonekill.menu.target_time_card_title", formattedTime), left + 36, y + 11, OsokWidgets.COLOR_GOLD);
                graphics.text(font, isGunGame
                        ? Component.translatable("gui.oneshotonekill.menu.target_time_card_desc_gungame")
                        : Component.translatable("gui.oneshotonekill.menu.target_time_card_desc"), left + 36, y + 27, OsokWidgets.COLOR_TEXT_FAINT);
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
                drawSlider(graphics, timeSlider, Component.translatable("gui.oneshotonekill.menu.target_time_slider").getString(), Component.translatable("gui.oneshotonekill.menu.target_time_min_short", effectiveMins).getString(), mouseX, mouseY);
                y += 44;

                // Schnellauswahl Presets
                graphics.text(font, Component.translatable("gui.oneshotonekill.menu.target_presets_title"), left, y + 4, OsokWidgets.COLOR_TEXT_MUTED);
                y += 18;
                int presetWidth = (right - left - 25) / 6;
                int[][] presets = new int[][]{{60, 1}, {300, 5}, {600, 10}, {900, 15}, {1800, 30}, {3600, 60}};
                int px = left;
                for (int[] preset : presets) {
                    int val = preset[0];
                    int mins = preset[1];
                    String label = Component.translatable("gui.oneshotonekill.menu.target_time_min_short", mins).getString();
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
                    OsokWidgets.panel(graphics, left, y, right, y + 54, 0xFF241F16, OsokWidgets.COLOR_AMBER);

                    graphics.item(new ItemStack(Items.BARRIER), left + 10, y + 18);
                    graphics.text(font, Component.translatable("gui.oneshotonekill.menu.target_kills_gungame_alert_title"), left + 36, y + 12, OsokWidgets.COLOR_AMBER);
                    graphics.text(font, Component.translatable("gui.oneshotonekill.menu.target_kills_gungame_alert_desc1"), left + 36, y + 26, OsokWidgets.COLOR_TEXT_WHITE);
                    graphics.text(font, Component.translatable("gui.oneshotonekill.menu.target_kills_gungame_alert_desc2"), left + 36, y + 38, OsokWidgets.COLOR_TEXT_FAINT);
                    y += 64;
                } else {
                    int effectiveKills = localPreviewKills != null ? localPreviewKills : Math.clamp(state.getMatchTargetValue(), 1, 100);

                    OsokWidgets.panel(graphics, left, y, right, y + 46, 0xFF141924, OsokWidgets.COLOR_CARD_BORDER);

                    graphics.item(new ItemStack(Items.TARGET), left + 10, y + 15);
                    graphics.text(font, Component.translatable("gui.oneshotonekill.menu.target_kills_card_title", effectiveKills), left + 36, y + 11, OsokWidgets.COLOR_GOLD);
                    graphics.text(font, Component.translatable("gui.oneshotonekill.menu.target_kills_card_desc"), left + 36, y + 27, OsokWidgets.COLOR_TEXT_FAINT);
                    y += 56;

                    // Schieberegler (Slider: 1 bis 100 Kills)
                    Slider killSlider = new Slider(left, y + 14, right - left, 22, 1, 100, effectiveKills, 1.0, stopped, val -> {
                        int newKills = val.intValue();
                        localPreviewKills = newKills;
                        if (newKills != state.getMatchTargetValue()) {
                            ClientPlayNetworking.send(new SetMatchTargetPayload(MatchTargetMode.KILL_LIMIT.name(), newKills));
                        }
                    });
                    drawSlider(graphics, killSlider, Component.translatable("gui.oneshotonekill.menu.target_kills_slider").getString(), Component.translatable("gui.oneshotonekill.menu.target_kills_count", effectiveKills).getString(), mouseX, mouseY);
                    y += 44;

                    // Schnellauswahl Presets
                    graphics.text(font, Component.translatable("gui.oneshotonekill.menu.target_presets_title"), left, y + 4, OsokWidgets.COLOR_TEXT_MUTED);
                    y += 18;
                    int presetWidth = (right - left - 25) / 6;
                    int[] presets = new int[]{1, 10, 25, 50, 75, 100};
                    int px = left;
                    for (int val : presets) {
                        String label = val == 1
                                ? Component.translatable("gui.oneshotonekill.menu.target_kills_one", val).getString()
                                : Component.translatable("gui.oneshotonekill.menu.target_kills_count", val).getString();
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
                OsokWidgets.panel(graphics, left, y, right, y + (isGunGame ? 56 : 46), 0xFF141924,
                        isGunGame ? OsokWidgets.COLOR_GOLD : OsokWidgets.COLOR_CARD_BORDER);

                if (isGunGame) {
                    graphics.fill(left + 2, y + 2, left + 5, y + 54, OsokWidgets.COLOR_GOLD);
                    graphics.item(com.oneshotonekill.match.GunGameTier.MASTER.iconStack(), left + 10, y + 19);
                    graphics.text(font, Component.translatable("gui.oneshotonekill.menu.target_unlimited_gungame_title"), left + 36, y + 12, OsokWidgets.COLOR_GOLD);
                    graphics.text(font, Component.translatable("gui.oneshotonekill.menu.target_unlimited_gungame_desc1"), left + 36, y + 27, OsokWidgets.COLOR_TEXT_WHITE);
                    graphics.text(font, Component.translatable("gui.oneshotonekill.menu.target_unlimited_gungame_desc2"), left + 36, y + 41, OsokWidgets.COLOR_TEXT_FAINT);
                    y += 66;
                } else {
                    graphics.item(new ItemStack(Items.COMPASS), left + 10, y + 15);
                    graphics.text(font, Component.translatable("gui.oneshotonekill.menu.target_unlimited_title"), left + 36, y + 11, OsokWidgets.COLOR_GOLD);
                    graphics.text(font, Component.translatable("gui.oneshotonekill.menu.target_unlimited_desc"), left + 36, y + 27, OsokWidgets.COLOR_TEXT_FAINT);
                    y += 56;
                }
            }
        }

        contentLength = y + scroll.offset() - contentTop;
    }

    private static String getTargetModeLabel(MatchTargetMode mode, boolean isGunGame) {
        if (isGunGame) {
            return switch (mode) {
                case TIME_LIMIT -> Component.translatable("gui.oneshotonekill.menu.target_time_btn_gungame").getString();
                case KILL_LIMIT -> Component.translatable("gui.oneshotonekill.menu.target_kills_btn_gungame").getString();
                case UNLIMITED -> Component.translatable("gui.oneshotonekill.menu.target_none_btn_gungame").getString();
            };
        }
        return switch (mode) {
            case TIME_LIMIT -> Component.translatable("gui.oneshotonekill.menu.target_time_btn").getString();
            case KILL_LIMIT -> Component.translatable("gui.oneshotonekill.menu.target_kills_btn").getString();
            case UNLIMITED -> Component.translatable("gui.oneshotonekill.menu.target_none_btn").getString();
        };
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
        int right = cardLeft + cardWidth - 16;
        int y = contentTop + 4 - scroll.offset();

        // Item-Modus Card
        OsokWidgets.panel(graphics, left, y, right, y + 54, 0xFF141A27, OsokWidgets.COLOR_CARD_BORDER);

        graphics.text(font, Component.translatable("gui.oneshotonekill.menu.weights_mode_title"), left + 10, y + 8, OsokWidgets.COLOR_TEXT_MUTED);
        boolean modeHover = OsokWidgets.isOver(mouseX, mouseY, left + 10, y + 22, 220, 20);
        OsokWidgets.cyberButton(graphics, font, left + 10, y + 22, 220, 20, itemModeLabel(), true, modeHover, OsokWidgets.COLOR_GOLD);
        hotspots.add(new Hotspot(left + 10, y + 22, 220, 20, true,
                () -> {
                    ClientPlayNetworking.send(new SetItemModePayload(nextItemMode().name()));
                    OsokWidgets.playActionSound();
                }));

        graphics.text(font, itemModeDescription(), left + 238, y + 28, OsokWidgets.COLOR_TEXT_FAINT);
        y += 62;

        // Gestapelte Verteilungsleiste aller Spezialitems
        List<OsokWidgets.DistributionSegment> segments = new ArrayList<>();
        for (SpecialItem item : SpecialItem.values()) {
            double chance = spawnChanceFor(item);
            if (chance > 0) {
                AdminItemScreen.ItemCategory cat = AdminItemScreen.getCategoryFor(item);
                segments.add(new OsokWidgets.DistributionSegment(item.getNameComponent().getString(), chance, cat.accent));
            }
        }

        graphics.text(font, Component.translatable("gui.oneshotonekill.menu.weights_distribution_title"), left, y, OsokWidgets.COLOR_TEXT_MUTED);
        y += 12;

        OsokWidgets.DistributionSegment hoveredSeg = OsokWidgets.drawStackedDistributionBar(
                graphics, font, left, y, right - left, 14, segments, mouseX, mouseY);
        y += 20;

        // Floating Tooltip für das überfahrene Segment
        if (hoveredSeg != null && mouseY >= contentTop && mouseY < contentTop + contentHeight) {
            String tip = String.format(Locale.ROOT, "%s: %.1f %%", hoveredSeg.label(), hoveredSeg.percentage());
            int tipW = font.width(tip) + 12;
            int tipX = Math.clamp(mouseX - tipW / 2, left, right - tipW);
            int tipY = y - 36;
            graphics.fill(tipX, tipY, tipX + tipW, tipY + 14, 0xF00B0E16);
            graphics.horizontalLine(tipX, tipX + tipW - 1, tipY, hoveredSeg.color());
            graphics.horizontalLine(tipX, tipX + tipW - 1, tipY + 13, hoveredSeg.color());
            graphics.verticalLine(tipX, tipY, tipY + 13, hoveredSeg.color());
            graphics.verticalLine(tipX + tipW - 1, tipY, tipY + 13, hoveredSeg.color());
            graphics.text(font, tip, tipX + 6, tipY + 3, OsokWidgets.COLOR_TEXT_WHITE);
        }

        // Filter-Reiter & Reset-Button
        int filterX = left;
        for (AdminItemScreen.ItemCategory cat : AdminItemScreen.ItemCategory.values()) {
            if (cat == AdminItemScreen.ItemCategory.FAVORITES) continue;
            int catWidth = font.width(cat.label()) + 12;
            boolean isSelected = weightFilterCategory == cat;
            boolean hov = OsokWidgets.isOver(mouseX, mouseY, filterX, y, catWidth, 20);
            OsokWidgets.tabHeader(graphics, font, filterX, y, catWidth, 20, cat.label(), isSelected, hov, cat.accent, true);
            AdminItemScreen.ItemCategory targetCat = cat;
            hotspots.add(new Hotspot(filterX, y, catWidth, 20, true, true, true, false, () -> {
                weightFilterCategory = targetCat;
                OsokWidgets.playTabSwitchSound();
            }));
            filterX += catWidth + 4;
        }

        boolean resetHover = OsokWidgets.isOver(mouseX, mouseY, right - 100, y, 100, 20);
        OsokWidgets.cyberButton(graphics, font, right - 100, y, 100, 20, Component.translatable("gui.oneshotonekill.menu.weights_btn_reset").getString(), true, resetHover, OsokWidgets.COLOR_CRIMSON);
        hotspots.add(new Hotspot(right - 100, y, 100, 20, true, true, true, false,
                () -> {
                    ClientPlayNetworking.send(ResetSpecialItemWeightsPayload.EMPTY);
                    OsokWidgets.playClearSound();
                }));
        y += 28;

        for (SpecialItem item : SpecialItem.values()) {
            if (!weightFilterCategory.matches(item, Set.of())) {
                continue;
            }
            drawWeightRow(graphics, item, left, right, y, mouseX, mouseY);
            y += 32;
        }
        contentLength = y + scroll.offset() - contentTop;
    }

    private void drawWeightRow(GuiGraphicsExtractor graphics, SpecialItem item, int left, int right, int y, int mouseX, int mouseY) {
        boolean rowHover = OsokWidgets.isOver(mouseX, mouseY, left, y, right - left, 28)
                && mouseY >= contentTop && mouseY < contentTop + contentHeight;

        AdminItemScreen.ItemCategory cat = AdminItemScreen.getCategoryFor(item);
        int rowBg = rowHover ? 0xFF222B3D : 0xFF141924;
        int rowBorder = rowHover ? cat.accent : OsokWidgets.COLOR_CARD_BORDER;

        OsokWidgets.panel(graphics, left, y, right, y + 28, rowBg, rowBorder);

        boolean isTilted = state != null && Arena.TILTED_TOWERS.getId().equalsIgnoreCase(state.getActiveArenaId());
        boolean isTiltedHook = isTilted && item == SpecialItem.GRAPPLING_HOOK;

        graphics.fill(left + 2, y + 2, left + 5, y + 26, isTiltedHook ? OsokWidgets.COLOR_AMBER : cat.accent);
        graphics.item(new ItemStack(item.getIcon()), left + 8, y + 6);
        graphics.text(font, item.getNameComponent(), left + 32, y + 5, OsokWidgets.COLOR_TEXT_WHITE);

        if (isTiltedHook) {
            String lockedText = Component.translatable("gui.oneshotonekill.menu.weights_tilted_grappler").getString();
            graphics.text(font, lockedText, left + 32, y + 16, OsokWidgets.COLOR_TEXT_FAINT);

            String badgeText = Component.translatable("gui.oneshotonekill.menu.weights_start_loadout").getString();
            int badgeWidth = font.width(badgeText) + 18;
            int badgeX = right - badgeWidth - 4;
            OsokWidgets.statusBadge(graphics, font, badgeX, y + 4, badgeText, OsokWidgets.COLOR_AMBER, false);
        } else {
            double chance = spawnChanceFor(item);
            String chanceText = Component.translatable("gui.oneshotonekill.menu.weights_row_format", weightFor(item), String.format(Locale.ROOT, "%.1f", chance)).getString();
            graphics.text(font, chanceText, left + 32, y + 16, OsokWidgets.COLOR_TEXT_FAINT);

            int x = right - (WEIGHT_BUTTON_WIDTH + 4) * 4 - 4;
            for (int[] step : new int[][]{{-5}, {-1}, {1}, {5}}) {
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
    }

    private void drawMatchStateBadge(GuiGraphicsExtractor graphics, int rightX, int y) {
        if (isMatchState(MatchState.RUNNING)) {
            String text = Component.translatable("gui.oneshotonekill.menu.state_running").getString();
            int width = font.width(text) + 18;
            OsokWidgets.statusBadge(graphics, font, rightX - width, y, text, OsokWidgets.COLOR_EMERALD, true);
        } else if (isMatchState(MatchState.PAUSED)) {
            String text = Component.translatable("gui.oneshotonekill.menu.state_paused").getString();
            int width = font.width(text) + 18;
            OsokWidgets.statusBadge(graphics, font, rightX - width, y, text, OsokWidgets.COLOR_AMBER, false);
        } else {
            String text = Component.translatable("gui.oneshotonekill.menu.state_stopped").getString();
            int width = font.width(text) + 18;
            OsokWidgets.statusBadge(graphics, font, rightX - width, y, text, OsokWidgets.COLOR_TEXT_DISABLED, false);
        }
    }

    private void drawFooter(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int y) {
        graphics.item(new ItemStack(Items.PAPER), cardLeft + 16, y - 4);
        graphics.text(font, Component.translatable("gui.oneshotonekill.menu.hotkey_hint", OsokClient.menuKeyName()),
                cardLeft + 38, y, OsokWidgets.COLOR_TEXT_FAINT);

        int closeX = cardLeft + cardWidth - 16 - BUTTON_WIDTH;
        boolean closeHover = OsokWidgets.isOver(mouseX, mouseY, closeX, y - 6, BUTTON_WIDTH, 20);
        OsokWidgets.cyberButton(graphics, font, closeX, y - 6, BUTTON_WIDTH, 20, Component.translatable("gui.oneshotonekill.menu.footer_close").getString(), true, closeHover, OsokWidgets.COLOR_CARD_BORDER);
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
        return cardLeft + cardWidth - SCROLLBAR_INSET;
    }

    // -- Eingabe -------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
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
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            activeSlider = null;
            lastSliderValue = Double.NaN;
            scroll.endDrag();
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) {
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
            if (hotspot.matches(mouseX, mouseY, contentTop, contentHeight)) {
                graphics.requestCursor(hotspot.pointer ? CursorTypes.POINTING_HAND : CursorTypes.NOT_ALLOWED);
                return;
            }
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
        OsokWidgets.playTabSwitchSound();
    }

    private void clickSound() {
        OsokWidgets.playUiClickSound();
    }

    /**
     * Leises Rastern beim Ziehen eines Reglers; die Tonhöhe folgt dem eingestellten Anteil.
     */
    private void tickSound(float ratio) {
        OsokWidgets.playSliderTickSound(ratio);
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
        boolean isTilted = state != null && Arena.TILTED_TOWERS.getId().equalsIgnoreCase(state.getActiveArenaId());
        if (isTilted && item == SpecialItem.GRAPPLING_HOOK) {
            return 0.0;
        }
        int total = 0;
        for (SpecialItem i : SpecialItem.values()) {
            if (isTilted && i == SpecialItem.GRAPPLING_HOOK) {
                continue;
            }
            total += weightFor(i);
        }
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
            case STREAK -> Component.translatable("gui.oneshotonekill.menu.weights_mode_streak").getString();
            case SPAWN -> Component.translatable("gui.oneshotonekill.menu.weights_mode_spawn").getString();
            case BOTH -> Component.translatable("gui.oneshotonekill.menu.weights_mode_both").getString();
        };
    }

    private Component itemModeDescription() {
        return switch (itemMode()) {
            case STREAK -> Component.translatable("gui.oneshotonekill.menu.weights_desc_streak");
            case SPAWN -> Component.translatable("gui.oneshotonekill.menu.weights_desc_spawn");
            case BOTH -> Component.translatable("gui.oneshotonekill.menu.weights_desc_both");
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
            case TILTED_TOWERS -> Items.CLOCK;
        };
    }

    public enum Tab {
        ARENAS("gui.oneshotonekill.menu.tab_arenas"),
        MATCH_CONTROL("gui.oneshotonekill.menu.tab_match"),
        MATCH_TARGET("gui.oneshotonekill.menu.tab_target"),
        ITEM_WEIGHTS("gui.oneshotonekill.menu.tab_weights");

        private final String translationKey;

        Tab(String translationKey) {
            this.translationKey = translationKey;
        }

        public String label() {
            return Component.translatable(translationKey).getString();
        }
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

        public boolean matches(int mouseX, int mouseY, int contentTop, int contentHeight) {
            if (!OsokWidgets.isOver(mouseX, mouseY, x, y, width, height)) {
                return false;
            }
            return !scrollable || (mouseY >= contentTop && mouseY < contentTop + contentHeight);
        }
    }

    private record Slider(int x, int y, int width, int height, double min, double max, double value, double step,
                          boolean enabled, Consumer<Double> onValueChange) {
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
}

