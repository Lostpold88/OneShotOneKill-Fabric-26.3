package com.oneshotonekill.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.oneshotonekill.client.OsokClient;
import com.oneshotonekill.client.config.MinimapConfig;
import com.oneshotonekill.client.state.ClientStates.MinimapState;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.NullMarked;

/**
 * Interaktiver Editor zur Größenanpassung und freien Positionierung der Tilted-Towers-Minimap.
 * Wird mit Taste 'Z' geöffnet.
 */
@NullMarked
public final class MinimapConfigScreen extends Screen {
    private int radius;
    private float xRatio;
    private float yRatio;

    private boolean isDraggingMap = false;
    private int dragOffsetX = 0;
    private int dragOffsetY = 0;

    private boolean isDraggingRadiusSlider = false;

    public MinimapConfigScreen() {
        super(Component.translatable("gui.oneshotonekill.minimap.title"));
    }

    @Override
    protected void init() {
        super.init();
        this.radius = MinimapConfig.INSTANCE.getRadius();
        this.xRatio = MinimapConfig.INSTANCE.getXRatio();
        this.yRatio = MinimapConfig.INSTANCE.getYRatio();
    }

    private int getMapCenterX() {
        int minX = radius + 6;
        int maxX = Math.max(minX, width - radius - 6);
        int raw = Math.round(xRatio * width);
        return Math.clamp(raw, minX, maxX);
    }

    private int getMapCenterY() {
        int minY = radius + 6;
        int maxY = Math.max(minY, height - radius - 6);
        int raw = Math.round(yRatio * height);
        return Math.clamp(raw, minY, maxY);
    }

    private void updatePositionFromMouse(double mouseX, double mouseY) {
        int targetX = (int) Math.round(mouseX - dragOffsetX);
        int targetY = (int) Math.round(mouseY - dragOffsetY);

        // Magnetisches Einrasten (Snap-to-Edge)
        int margin = MinimapConfig.DEFAULT_MARGIN;
        if (Math.abs((targetX - radius) - margin) < 18) {
            targetX = radius + margin;
        } else if (Math.abs((width - targetX - radius) - margin) < 18) {
            targetX = width - radius - margin;
        }

        if (Math.abs((targetY - radius) - margin) < 18) {
            targetY = radius + margin;
        } else if (Math.abs((height - targetY - radius) - margin) < 18) {
            targetY = height - radius - margin;
        }

        int minX = radius + 6;
        int maxX = Math.max(minX, width - radius - 6);
        int minY = radius + 6;
        int maxY = Math.max(minY, height - radius - 6);

        targetX = Math.clamp(targetX, minX, maxX);
        targetY = Math.clamp(targetY, minY, maxY);

        this.xRatio = Math.clamp((float) targetX / (float) Math.max(1, width), 0.0f, 1.0f);
        this.yRatio = Math.clamp((float) targetY / (float) Math.max(1, height), 0.0f, 1.0f);
        MinimapConfig.INSTANCE.setRatios(this.xRatio, this.yRatio);
    }

    private void updateRadiusFromMouse(double mouseX, int sliderX, int sliderW) {
        double ratio = Math.clamp((mouseX - sliderX) / (double) Math.max(1, sliderW), 0.0, 1.0);
        this.radius = (int) Math.round(MinimapConfig.MIN_RADIUS + ratio * (MinimapConfig.MAX_RADIUS - MinimapConfig.MIN_RADIUS));
        MinimapConfig.INSTANCE.setRadius(this.radius);
    }

    private void saveAndClose() {
        MinimapConfig.INSTANCE.setRadius(this.radius);
        MinimapConfig.INSTANCE.setRatios(this.xRatio, this.yRatio);
        MinimapConfig.INSTANCE.save();
        onClose();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (OsokClient.isMinimapConfigKey(event) || event.key() == InputConstants.KEY_ESCAPE) {
            saveAndClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            int cx = getMapCenterX();
            int cy = getMapCenterY();
            double dist = Math.hypot(event.x() - cx, event.y() - cy);

            // 1. Klick auf die Minimap zum Verschieben
            if (dist <= radius + 10) {
                isDraggingMap = true;
                dragOffsetX = (int) Math.round(event.x() - cx);
                dragOffsetY = (int) Math.round(event.y() - cy);
                return true;
            }

            int panelW = Math.min(380, width - 24);
            int panelH = 112;
            int panelLeft = (width - panelW) / 2;
            int panelTop = height - panelH - 12;

            // 2. Klick auf den Radius-Schieberegler
            int sliderX = panelLeft + 14;
            int sliderY = panelTop + 36;
            int sliderW = panelW - 28;
            int sliderH = 16;
            if (OsokWidgets.isOver(event.x(), event.y(), sliderX - 4, sliderY - 6, sliderW + 8, sliderH + 10)) {
                isDraggingRadiusSlider = true;
                updateRadiusFromMouse(event.x(), sliderX, sliderW);
                OsokWidgets.playActionSound();
                return true;
            }

            // 3. Preset-Buttons
            int presetY = panelTop + 58;
            int buttonCount = 5;
            int gap = 5;
            int buttonW = (panelW - 28 - (buttonCount - 1) * gap) / buttonCount;
            int px = panelLeft + 14;

            // Oben Links
            if (OsokWidgets.isOver(event.x(), event.y(), px, presetY, buttonW, 18)) {
                MinimapConfig.INSTANCE.setPresetTopLeft(width, height);
                this.xRatio = MinimapConfig.INSTANCE.getXRatio();
                this.yRatio = MinimapConfig.INSTANCE.getYRatio();
                OsokWidgets.playActionSound();
                return true;
            }
            px += buttonW + gap;

            // Oben Rechts
            if (OsokWidgets.isOver(event.x(), event.y(), px, presetY, buttonW, 18)) {
                MinimapConfig.INSTANCE.setPresetTopRight(width, height);
                this.xRatio = MinimapConfig.INSTANCE.getXRatio();
                this.yRatio = MinimapConfig.INSTANCE.getYRatio();
                OsokWidgets.playActionSound();
                return true;
            }
            px += buttonW + gap;

            // Unten Links
            if (OsokWidgets.isOver(event.x(), event.y(), px, presetY, buttonW, 18)) {
                MinimapConfig.INSTANCE.setPresetBottomLeft(width, height);
                this.xRatio = MinimapConfig.INSTANCE.getXRatio();
                this.yRatio = MinimapConfig.INSTANCE.getYRatio();
                OsokWidgets.playActionSound();
                return true;
            }
            px += buttonW + gap;

            // Unten Rechts
            if (OsokWidgets.isOver(event.x(), event.y(), px, presetY, buttonW, 18)) {
                MinimapConfig.INSTANCE.setPresetBottomRight(width, height);
                this.xRatio = MinimapConfig.INSTANCE.getXRatio();
                this.yRatio = MinimapConfig.INSTANCE.getYRatio();
                OsokWidgets.playActionSound();
                return true;
            }
            px += buttonW + gap;

            // Reset
            if (OsokWidgets.isOver(event.x(), event.y(), px, presetY, buttonW, 18)) {
                MinimapConfig.INSTANCE.reset(width, height);
                this.radius = MinimapConfig.INSTANCE.getRadius();
                this.xRatio = MinimapConfig.INSTANCE.getXRatio();
                this.yRatio = MinimapConfig.INSTANCE.getYRatio();
                OsokWidgets.playActionSound();
                return true;
            }

            // 4. Speichern & Schließen Button
            int saveX = panelLeft + 14;
            int saveY = panelTop + 82;
            int saveW = panelW - 28;
            int saveH = 20;
            if (OsokWidgets.isOver(event.x(), event.y(), saveX, saveY, saveW, saveH)) {
                OsokWidgets.playActionSound();
                saveAndClose();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            isDraggingMap = false;
            isDraggingRadiusSlider = false;
            MinimapConfig.INSTANCE.save();
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            if (isDraggingMap) {
                updatePositionFromMouse(event.x(), event.y());
                return true;
            }
            if (isDraggingRadiusSlider) {
                int panelW = Math.min(380, width - 24);
                int panelLeft = (width - panelW) / 2;
                int sliderX = panelLeft + 14;
                int sliderW = panelW - 28;
                updateRadiusFromMouse(event.x(), sliderX, sliderW);
                return true;
            }
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        // Leichter abgedunkelter Hintergrund, damit das Spiel sichtbar bleibt
        graphics.fill(0, 0, width, height, 0x44080D14);

        int cx = getMapCenterX();
        int cy = getMapCenterY();
        MinimapState state = MinimapState.INSTANCE;

        // 1. Live Minimap-Vorschau an aktueller Position
        if (state.isRadarViewReady()) {
            int diameter = radius * 2;
            graphics.blit(RenderPipelines.GUI_TEXTURED, MinimapState.RADAR_VIEW_ID,
                    cx - radius, cy - radius, 0, 0, diameter, diameter,
                    MinimapState.RADAR_TEX_SIZE, MinimapState.RADAR_TEX_SIZE,
                    MinimapState.RADAR_TEX_SIZE, MinimapState.RADAR_TEX_SIZE);
        }

        // Taktische Gitter- & Kreisringe
        drawCircle(graphics, cx, cy, (int) (radius * 0.5), 0x3300F0FF);
        drawCircle(graphics, cx, cy, radius, 0xFF00F0FF);
        drawCircle(graphics, cx, cy, radius + 1, 0x6600F0FF);
        graphics.horizontalLine(cx - radius + 4, cx + radius - 4, cy, 0x2200F0FF);
        graphics.verticalLine(cx, cy - radius + 4, cy + radius - 4, 0x2200F0FF);

        // Spieler-Symbol
        drawPlayerMarker(graphics, cx, cy);

        // Interaktiver Auswahlrahmen & Fadenkreuz beim Überfahren mit der Maus
        double dist = Math.hypot(mouseX - cx, mouseY - cy);
        boolean mapHovered = dist <= radius + 8;
        if (mapHovered || isDraggingMap) {
            graphics.requestCursor(CursorTypes.RESIZE_ALL);
            drawCircle(graphics, cx, cy, radius + 3, 0xAA00FF9D);

            // Eck-Marker
            int r2 = radius + 6;
            graphics.horizontalLine(cx - r2, cx - r2 + 6, cy - r2, 0xFF00FF9D);
            graphics.verticalLine(cx - r2, cy - r2, cy - r2 + 6, 0xFF00FF9D);

            graphics.horizontalLine(cx + r2 - 6, cx + r2, cy - r2, 0xFF00FF9D);
            graphics.verticalLine(cx + r2, cy - r2, cy - r2 + 6, 0xFF00FF9D);

            graphics.horizontalLine(cx - r2, cx - r2 + 6, cy + r2, 0xFF00FF9D);
            graphics.verticalLine(cx - r2, cy + r2 - 6, cy + r2, 0xFF00FF9D);

            graphics.horizontalLine(cx + r2 - 6, cx + r2, cy + r2, 0xFF00FF9D);
            graphics.verticalLine(cx + r2, cy + r2 - 6, cy + r2, 0xFF00FF9D);
        }

        // 2. Control Panel unten mittig
        int panelW = Math.min(380, width - 24);
        int panelH = 112;
        int panelLeft = (width - panelW) / 2;
        int panelTop = height - panelH - 12;

        OsokWidgets.panel(graphics, panelLeft, panelTop, panelLeft + panelW, panelTop + panelH, 0xF40D111C, OsokWidgets.COLOR_CARD_BORDER);

        // Titel
        graphics.text(font, Component.translatable("gui.oneshotonekill.minimap.editor"), panelLeft + 14, panelTop + 10, OsokWidgets.COLOR_CYAN);
        graphics.text(font, Component.translatable("gui.oneshotonekill.minimap.drag_hint"), panelLeft + 120, panelTop + 10, OsokWidgets.COLOR_TEXT_FAINT);

        // Radius Slider
        int sliderX = panelLeft + 14;
        int sliderY = panelTop + 36;
        int sliderW = panelW - 28;
        int sliderH = 16;
        double sRatio = (radius - MinimapConfig.MIN_RADIUS) / (double) (MinimapConfig.MAX_RADIUS - MinimapConfig.MIN_RADIUS);
        String valText = (radius * 2) + " px (Radius " + radius + ")";
        boolean sliderHover = OsokWidgets.isOver(mouseX, mouseY, sliderX, sliderY, sliderW, sliderH);
        OsokWidgets.modernSlider(graphics, font, sliderX, sliderY, sliderW, sliderH,
                Component.translatable("gui.oneshotonekill.minimap.scale").getString(), valText, sRatio, true, sliderHover, isDraggingRadiusSlider, OsokWidgets.COLOR_CYAN);

        // Presets Buttons
        int presetY = panelTop + 58;
        int buttonCount = 5;
        int gap = 5;
        int buttonW = (panelW - 28 - (buttonCount - 1) * gap) / buttonCount;
        int px = panelLeft + 14;

        String[] labels = {
            Component.translatable("gui.oneshotonekill.minimap.top_left").getString(),
            Component.translatable("gui.oneshotonekill.minimap.top_right").getString(),
            Component.translatable("gui.oneshotonekill.minimap.bottom_left").getString(),
            Component.translatable("gui.oneshotonekill.minimap.bottom_right").getString(),
            Component.translatable("gui.oneshotonekill.minimap.reset").getString()
        };
        for (String lbl : labels) {
            boolean hov = OsokWidgets.isOver(mouseX, mouseY, px, presetY, buttonW, 18);
            OsokWidgets.cyberButton(graphics, font, px, presetY, buttonW, 18, lbl, true, hov, OsokWidgets.COLOR_CARD_BORDER);
            px += buttonW + gap;
        }

        // Speichern Button
        int saveX = panelLeft + 14;
        int saveY = panelTop + 82;
        int saveW = panelW - 28;
        int saveH = 20;
        boolean saveHover = OsokWidgets.isOver(mouseX, mouseY, saveX, saveY, saveW, saveH);
        String keyName = OsokClient.minimapConfigKeyName().getString();
        String saveText = Component.translatable("gui.oneshotonekill.minimap.save", keyName).getString();
        OsokWidgets.cyberButton(graphics, font, saveX, saveY, saveW, saveH,
                saveText, true, saveHover, OsokWidgets.COLOR_EMERALD);

        super.extractRenderState(graphics, mouseX, mouseY, partial);
    }

    private static void drawCircle(GuiGraphicsExtractor graphics, int cx, int cy, int radius, int color) {
        int points = 36;
        for (int i = 0; i < points; i++) {
            double a = i * (Math.PI * 2.0 / points);
            int px = cx + (int) Math.round(Math.cos(a) * radius);
            int py = cy + (int) Math.round(Math.sin(a) * radius);
            graphics.fill(px, py, px + 1, py + 1, color);
        }
    }

    private static void drawPlayerMarker(GuiGraphicsExtractor graphics, int cx, int cy) {
        graphics.fill(cx, cy - 4, cx + 1, cy - 3, 0xFFFFFFFF);
        graphics.fill(cx - 1, cy - 3, cx + 2, cy - 1, 0xFF00FF9D);
        graphics.fill(cx - 2, cy - 1, cx + 3, cy + 1, 0xFF00FF9D);
        graphics.fill(cx - 3, cy + 1, cx + 4, cy + 3, 0xFF00FF9D);
        graphics.fill(cx - 1, cy + 1, cx + 2, cy + 3, 0xFF060910);
    }
}
