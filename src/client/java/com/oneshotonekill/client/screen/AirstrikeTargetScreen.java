package com.oneshotonekill.client.screen;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.item.runtime.AirstrikeSystem;
import com.oneshotonekill.network.OsokPayloads.*;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Taktisches Orbital-Command Terminal (C2): Live-Geländescan, Polar-Radar mit Kompass,
 * holographischer Lock-On Reticle, 4-Kachel Telemetriedeck und Cyber-Buttons.
 */
public final class AirstrikeTargetScreen extends Screen {
   private static final Identifier RADAR_TEXTURE = OneShotOneKill.INSTANCE.id("dynamic/airstrike_radar");

   // Farbtokens & Designsystem
   private static final int OVERLAY = 0xD804070C;
   private static final int PANEL_BG = 0xF2080C14;
   private static final int PANEL_BORDER = 0xAA00F0FF;
   private static final int PANEL_INNER = 0x4400F0FF;
   private static final int HEADER_BG = 0xF20C121E;
   private static final int MAP_FRAME = 0xFF060910;
   private static final int MAP_BORDER = 0x8800F0FF;
   private static final int MAP_EMPTY = 0xFF080C12;
   private static final int GRID_COLOR = 0x2600F0FF;
   private static final int POLAR_RING_COLOR = 0x3300F0FF;
   private static final int SCANLINE = 0x16000000;
   private static final int BRACKET_GOLD = 0xFFFFD700;
   private static final int TARGET_CYAN = 0xFF00F0FF;
   private static final int TARGET_LOCKED = 0xFFFF3366;
   private static final int BLAST_FILL = 0x33FF2244;
   private static final int BLAST_LINE = 0xCCFF3366;
   private static final int BLAST_PREVIEW = 0x4400F0FF;
   private static final int SWEEP_RGB = 0x0000F0FF;
   private static final int PLAYER_EMERALD = 0xFF00FF9D;
   private static final int ENEMY_CRIMSON = 0xFFFF3366;
   private static final int DANGER_PULSE = 0xFFFF0033;
   private static final int TEXT_MUTED = 0xFF8B9BB4;
   private static final int TEXT_WHITE = 0xFFF0F6FC;

   private static final int SWEEP_TRAIL = 16;
   private static final float SWEEP_TRAIL_STEP = 0.045F;
   private static final float SWEEP_SPEED = 0.055F;
   private static final int SWEEP_MAX_ALPHA = 0x88;
   private static final int BLAST_CIRCLE_POINTS = 72;
   private static final int LOCK_ON_TICKS = 7;

   private final Arena arena;

   private double selectedX = Double.NaN;
   private double selectedZ = Double.NaN;
   private int selectedAtTick = -1;

   private double minX;
   private double maxX;
   private double minZ;
   private double maxZ;
   private int panelLeft;
   private int panelTop;
   private int panelWidth;
   private int panelHeight;
   private int mapLeft;
   private int mapTop;
   private int mapWidth;
   private int mapHeight;

   private List<Integer> terrainColors = List.of();
   private int terrainCells;
   private List<AirstrikeSystem.RadarPayload.RadarContact> contacts = List.of();
   private boolean hasScan;
   private DynamicTexture radarTexture;
   private int uploadedRadarRevision = -1;
   private int terrainRevision = -1;
   private CyberStrikeButton fireButton;

   public AirstrikeTargetScreen() {
      super(Component.literal("Orbital-Command Radar"));
      Arena found = Arena.getDefault();
      if (Minecraft.getInstance().level != null) {
         for (Arena candidate : Arena.values()) {
            if (candidate.getDimension().equals(Minecraft.getInstance().level.dimension())) {
               found = candidate;
               break;
            }
         }
      }
      arena = found;
   }

   @Override
   protected void init() {
      minX = arena.getRegions().stream().mapToDouble(region -> region.getMinX()).min().orElse(0.0);
      maxX = arena.getRegions().stream().mapToDouble(region -> region.getMaxX()).max().orElse(1.0);
      minZ = arena.getRegions().stream().mapToDouble(region -> region.getMinZ()).min().orElse(0.0);
      maxZ = arena.getRegions().stream().mapToDouble(region -> region.getMaxZ()).max().orElse(1.0);
      double aspect = (maxX - minX) / Math.max(1.0, maxZ - minZ);
      int maxWidth = Math.min(380, width - 64);
      int maxHeight = Math.min(260, height - 210);
      mapWidth = Math.max(160, Math.min(maxWidth, (int) Math.round(maxHeight * aspect)));
      mapHeight = Math.max(160, Math.min(maxHeight, (int) Math.round(mapWidth / aspect)));

      // Mindestbreite 340px, damit Header, Telemetrie-Kacheln und Buttons immer perfekt passen
      int minPanelWidth = Math.min(width - 24, 340);
      panelWidth = Math.max(minPanelWidth, Math.min(width - 24, mapWidth + 48));
      panelHeight = mapHeight + 152;
      panelLeft = width / 2 - panelWidth / 2;
      panelTop = height / 2 - panelHeight / 2;

      // Karte mittig im Panel platzieren
      mapLeft = panelLeft + (panelWidth - mapWidth) / 2;
      mapTop = panelTop + 48;

      int buttonWidth = Math.min(240, panelWidth - 48);
      fireButton = new CyberStrikeButton(width / 2 - buttonWidth / 2, mapTop + mapHeight + 54, buttonWidth, 22,
         Component.literal("✦ ANGRIFF AUSLÖSEN ✦"), () -> hasTarget(), ignored -> confirm());
      fireButton.active = false;
      addRenderableWidget(fireButton);

      addRenderableWidget(new CyberAbortButton(width / 2 - buttonWidth / 2, mapTop + mapHeight + 80, buttonWidth, 18,
         Component.literal("✕ ABBRECHEN [RECHTSKLICK / ESC]"), ignored -> onClose()));

      ClientPlayNetworking.send(AirstrikeSystem.OpenRadarPayload.EMPTY);
   }

   @Override
   public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      if (event.button() == 1) {
         onClose();
         return true;
      }
      if (event.button() == 0 && isOverMap(event.x(), event.y())) {
         double x = worldXAt(event.x());
         double z = worldZAt(event.y());
         if (arena.isInArenaColumn(x, z)) {
            selectedX = x;
            selectedZ = z;
            selectedAtTick = clientTick();
            fireButton.active = true;
         }
         return true;
      }
      return super.mouseClicked(event, doubleClick);
   }

   @Override
   public boolean isPauseScreen() {
      return false;
   }

   @Override
   public void onClose() {
      ClientPlayNetworking.send(AirstrikeSystem.CloseRadarPayload.EMPTY);
      super.onClose();
   }

   @Override
   public void removed() {
      if (radarTexture != null) {
         Minecraft.getInstance().getTextureManager().release(RADAR_TEXTURE);
      }
      radarTexture = null;
      uploadedRadarRevision = -1;
      super.removed();
   }

   public void updateRadar(AirstrikeSystem.RadarPayload payload) {
      if (!arena.getId().equals(payload.arenaId()) || payload.cells() < 1) {
         return;
      }

      List<Integer> colors = payload.colors();
      if (!colors.isEmpty()) {
         if (colors.size() != payload.cells() * payload.cells()) {
            return;
         }
         terrainColors = colors;
         terrainCells = payload.cells();
         terrainRevision = payload.revision();
         hasScan = true;
         uploadRadarTexture();
      }

      contacts = payload.contacts();
      minX = payload.minX();
      maxX = payload.maxX();
      minZ = payload.minZ();
      maxZ = payload.maxZ();
   }

   @Override
   public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
      graphics.requestCursor(cursorFor(mouseX, mouseY));
      graphics.fill(0, 0, width, height, OVERLAY);
      drawGlassPanel(graphics, panelLeft, panelTop, panelWidth, panelHeight);
      drawHeader(graphics);

      graphics.fill(mapLeft - 3, mapTop - 3, mapLeft + mapWidth + 3, mapTop + mapHeight + 3, MAP_FRAME);
      drawTerrain(graphics);
      drawPolarGrid(graphics);
      drawSweep(graphics, partial);
      drawScanlines(graphics);
      drawBlastPreview(graphics, mouseX, mouseY);
      drawTarget(graphics, partial);
      drawContacts(graphics);
      drawPlayerMarker(graphics);
      drawMapFrameAndCompass(graphics);

      drawHint(graphics, mouseX, mouseY);
      drawTelemetryDeck(graphics);
      super.extractRenderState(graphics, mouseX, mouseY, partial);
   }

   private void confirm() {
      if (hasTarget()) {
         ClientPlayNetworking.send(new RequestAirstrikePayload(selectedX, selectedZ));
         onClose();
      }
   }

   /** Header mit Satelliten-Icon, Telemetrie und blinkender DEFCON-Statuslampe */
   private void drawHeader(GuiGraphicsExtractor graphics) {
      graphics.fill(panelLeft + 3, panelTop + 3, panelLeft + panelWidth - 3, panelTop + 38, HEADER_BG);
      graphics.fill(panelLeft + 3, panelTop + 36, panelLeft + panelWidth - 3, panelTop + 38, 0x5500F0FF);

      // Goldener Tech-Winkel links oben
      graphics.fill(panelLeft + 4, panelTop + 4, panelLeft + 12, panelTop + 6, BRACKET_GOLD);
      graphics.fill(panelLeft + 4, panelTop + 4, panelLeft + 6, panelTop + 12, BRACKET_GOLD);

      // Scharf gezeichnetes 2D-Vektor Satelliten-Icon
      int titleTextWidth = font.width("ORBITAL STRIKE TERMINAL // SAT-UPLINK v4.2");
      int satX = width / 2 - titleTextWidth / 2 - 14;
      int satY = panelTop + 13;
      drawVectorSatelliteIcon(graphics, satX, satY);

      graphics.centeredText(font, Component.literal("ORBITAL STRIKE TERMINAL // SAT-UPLINK v4.2"), width / 2 + 4, panelTop + 10, OsokWidgets.COLOR_CYAN);
      graphics.centeredText(font, Component.literal("SEKTOR: " + arena.getDisplayName().toUpperCase() + "  ·  GPS LOCK: ACTIVE"), width / 2, panelTop + 23, TEXT_MUTED);

      // Status-Beacon rechts
      boolean armed = hasTarget();
      int pulse = armed ? 255 : (int) (140 + 90 * Mth.sin(clientTick() * 0.35F));
      int lampColor = Mth.clamp(pulse, 0, 255) << 24 | (armed ? TARGET_LOCKED : OsokWidgets.COLOR_CYAN) & 0x00FFFFFF;
      int lampX = panelLeft + panelWidth - 20;
      graphics.fill(lampX - 1, panelTop + 13, lampX + 8, panelTop + 22, 0x44000000);
      graphics.fill(lampX, panelTop + 14, lampX + 7, panelTop + 21, lampColor);
      graphics.outline(lampX - 2, panelTop + 12, 11, 11, armed ? TARGET_LOCKED : 0x8800F0FF);
   }

   /** Zeichnet ein scharfes 10x8 Vektor-Satelliten-Icon */
   private static void drawVectorSatelliteIcon(GuiGraphicsExtractor graphics, int x, int y) {
      // Satellitenkörper (Center Core)
      graphics.fill(x + 3, y + 1, x + 7, y + 6, OsokWidgets.COLOR_GOLD);
      graphics.fill(x + 4, y + 2, x + 6, y + 5, 0xFFFFFFFF);

      // Solarsegel Links
      graphics.fill(x, y, x + 2, y + 7, OsokWidgets.COLOR_CYAN);
      graphics.horizontalLine(x + 1, x + 3, y + 3, 0xFFFFFFFF);

      // Solarsegel Rechts
      graphics.fill(x + 8, y, x + 10, y + 7, OsokWidgets.COLOR_CYAN);
      graphics.horizontalLine(x + 6, x + 9, y + 3, 0xFFFFFFFF);

      // Antennenspitze
      graphics.fill(x + 5, y - 2, x + 6, y + 1, 0xFF00F0FF);
   }

   private void drawTerrain(GuiGraphicsExtractor graphics) {
      if (!hasScan) {
         graphics.fill(mapLeft, mapTop, mapLeft + mapWidth, mapTop + mapHeight, MAP_EMPTY);
         return;
      }
      uploadRadarTexture();
      graphics.blit(RenderPipelines.GUI_TEXTURED, RADAR_TEXTURE, mapLeft, mapTop, 0, 0, mapWidth, mapHeight,
         terrainCells, terrainCells, terrainCells, terrainCells);
   }

   private void uploadRadarTexture() {
      if (!hasScan || uploadedRadarRevision == terrainRevision) {
         return;
      }
      if (radarTexture == null || radarTexture.getPixels().getWidth() != terrainCells) {
         if (radarTexture != null) {
            Minecraft.getInstance().getTextureManager().release(RADAR_TEXTURE);
         }
         radarTexture = new DynamicTexture("OneShotOneKill Luftangriff-Radar", terrainCells, terrainCells, true);
         Minecraft.getInstance().getTextureManager().register(RADAR_TEXTURE, radarTexture);
      }
      NativeImage pixels = radarTexture.getPixels();
      for (int row = 0; row < terrainCells; row++) {
         for (int column = 0; column < terrainCells; column++) {
            int color = terrainColors.get(row * terrainCells + column);
            pixels.setPixel(column, row, color == AirstrikeSystem.RADAR_VOID_COLOR ? 0x00080D14 : color);
         }
      }
      radarTexture.upload();
      uploadedRadarRevision = terrainRevision;
   }

   /** Polar-Koordinaten Gitter mit konzentrischen Distanzringen */
   private void drawPolarGrid(GuiGraphicsExtractor graphics) {
      int cx = mapLeft + mapWidth / 2;
      int cy = mapTop + mapHeight / 2;

      // Achsenkreuze
      graphics.verticalLine(cx, mapTop, mapTop + mapHeight, GRID_COLOR);
      graphics.horizontalLine(mapLeft, mapLeft + mapWidth, cy, GRID_COLOR);

      // Konzentrische Distanzringe (25%, 50%, 75%, 100%)
      int maxRadius = Math.min(mapWidth, mapHeight) / 2;
      for (int r = 1; r <= 3; r++) {
         int radius = maxRadius * r / 4;
         drawCircleOutline(graphics, cx, cy, radius, POLAR_RING_COLOR);
      }
   }

   private void drawCircleOutline(GuiGraphicsExtractor graphics, int cx, int cy, int radius, int color) {
      int points = 36;
      for (int i = 0; i < points; i++) {
         double a = i * (Math.PI * 2.0 / points);
         int px = cx + (int) Math.round(Math.cos(a) * radius);
         int py = cy + (int) Math.round(Math.sin(a) * radius);
         if (px >= mapLeft && px < mapLeft + mapWidth && py >= mapTop && py < mapTop + mapHeight) {
            graphics.fill(px, py, px + 1, py + 1, color);
         }
      }
   }

   /** Dual-Phosphor Radarsweep mit feinem Partikelschweif */
   private void drawSweep(GuiGraphicsExtractor graphics, float partial) {
      if (!hasScan) {
         return;
      }
      float angle = (clientTick() + partial) * SWEEP_SPEED;
      int reach = (int) Math.ceil(Math.hypot(mapWidth, mapHeight) / 2.0);

      graphics.enableScissor(mapLeft, mapTop, mapLeft + mapWidth, mapTop + mapHeight);
      graphics.pose().pushMatrix();
      graphics.pose().translate(mapLeft + mapWidth / 2.0F, mapTop + mapHeight / 2.0F);

      for (int trail = SWEEP_TRAIL - 1; trail >= 0; trail--) {
         int alpha = SWEEP_MAX_ALPHA * (SWEEP_TRAIL - trail) / SWEEP_TRAIL;
         graphics.pose().pushMatrix();
         graphics.pose().rotate(angle - trail * SWEEP_TRAIL_STEP);
         graphics.fill(0, -1, reach, 0, alpha << 24 | SWEEP_RGB);
         graphics.pose().popMatrix();
      }
      graphics.pose().popMatrix();
      graphics.disableScissor();
   }

   private void drawScanlines(GuiGraphicsExtractor graphics) {
      for (int y = mapTop + 1; y < mapTop + mapHeight; y += 3) {
         graphics.fill(mapLeft, y, mapLeft + mapWidth, y + 1, SCANLINE);
      }
   }

   /** Kartenrahmen mit Kompass-Markierungen (N, O, S, W) und goldenen Tech-Ecken */
   private void drawMapFrameAndCompass(GuiGraphicsExtractor graphics) {
      graphics.outline(mapLeft, mapTop, mapWidth, mapHeight, MAP_BORDER);

      // Goldene Tech-Eckklammern
      int arm = 10;
      int right = mapLeft + mapWidth - 1;
      int bottom = mapTop + mapHeight - 1;

      graphics.horizontalLine(mapLeft - 2, mapLeft + arm, mapTop - 2, BRACKET_GOLD);
      graphics.verticalLine(mapLeft - 2, mapTop - 2, mapTop + arm, BRACKET_GOLD);

      graphics.horizontalLine(right - arm, right + 2, mapTop - 2, BRACKET_GOLD);
      graphics.verticalLine(right + 2, mapTop - 2, mapTop + arm, BRACKET_GOLD);

      graphics.horizontalLine(mapLeft - 2, mapLeft + arm, bottom + 2, BRACKET_GOLD);
      graphics.verticalLine(mapLeft - 2, bottom - arm, bottom + 2, BRACKET_GOLD);

      graphics.horizontalLine(right - arm, right + 2, bottom + 2, BRACKET_GOLD);
      graphics.verticalLine(right + 2, bottom - arm, bottom + 2, BRACKET_GOLD);

      // Kompass-Markierungen
      int midX = mapLeft + mapWidth / 2;
      int midZ = mapTop + mapHeight / 2;
      drawMicroLabel(graphics, "N", midX, mapTop + 4, OsokWidgets.COLOR_GOLD);
      drawMicroLabel(graphics, "S", midX, bottom - 8, OsokWidgets.COLOR_CYAN);
      drawMicroLabel(graphics, "W", mapLeft + 6, midZ - 3, OsokWidgets.COLOR_CYAN);
      drawMicroLabel(graphics, "E", right - 6, midZ - 3, OsokWidgets.COLOR_CYAN);
   }

   private void drawBlastPreview(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
      if (!isOverMap(mouseX, mouseY)) {
         return;
      }
      double x = worldXAt(mouseX);
      double z = worldZAt(mouseY);
      if (!arena.isInArenaColumn(x, z)) {
         return;
      }
      drawBlastZone(graphics, x, z, BLAST_PREVIEW, false);
      graphics.outline(mouseX - 4, mouseY - 4, 9, 9, TARGET_CYAN);
   }

   /** Holographisches Zielkreuz mit Einrast-Animation und Danger-Zone */
   private void drawTarget(GuiGraphicsExtractor graphics, float partial) {
      if (!hasTarget()) {
         return;
      }
      int x = toMapX(selectedX);
      int z = toMapZ(selectedZ);

      // Wirkungszone mit rotem Füllkreis und gestricheltem Gefahrenrand
      drawBlastZone(graphics, selectedX, selectedZ, BLAST_LINE, true);

      // Peillinie vom Spieler zum Ziel
      LocalPlayer player = Minecraft.getInstance().player;
      if (player != null && arena.isInArenaColumn(player.getX(), player.getZ())) {
         int px = toMapX(player.getX());
         int pz = toMapZ(player.getZ());
         drawDottedLine(graphics, px, pz, x, z, 0x6600F0FF);
      }

      // Einrast-Klammern
      float lockProgress = Mth.clamp((clientTick() + partial - selectedAtTick) / LOCK_ON_TICKS, 0.0F, 1.0F);
      int lockSize = 6 + Math.round(20 * (1.0F - lockProgress));
      int lockAlpha = (int) ((0.5F + 0.5F * lockProgress) * 255);
      int lockColor = lockAlpha << 24 | TARGET_LOCKED & 0x00FFFFFF;

      graphics.outline(x - lockSize, z - lockSize, lockSize * 2 + 1, lockSize * 2 + 1, lockColor);
      graphics.outline(x - 5, z - 5, 11, 11, TARGET_LOCKED);
      graphics.horizontalLine(x - 10, x + 10, z, TARGET_LOCKED);
      graphics.verticalLine(x, z - 10, z + 10, TARGET_LOCKED);
      graphics.fill(x, z, x + 1, z + 1, 0xFFFFFFFF);
   }

   private void drawBlastZone(GuiGraphicsExtractor graphics, double worldX, double worldZ, int color, boolean fill) {
      double radiusX = AirstrikeSystem.KILL_RADIUS / (maxX - minX) * mapWidth;
      double radiusZ = AirstrikeSystem.KILL_RADIUS / (maxZ - minZ) * mapHeight;
      int centerX = toMapX(worldX);
      int centerZ = toMapZ(worldZ);

      if (fill) {
         for (int step = 0; step < BLAST_CIRCLE_POINTS; step++) {
            double angle = step * (Math.PI * 2.0 / BLAST_CIRCLE_POINTS);
            int x = centerX + (int) Math.round(Math.cos(angle) * (radiusX * 0.95));
            int y = centerZ + (int) Math.round(Math.sin(angle) * (radiusZ * 0.95));
            if (x >= mapLeft && x < mapLeft + mapWidth && y >= mapTop && y < mapTop + mapHeight) {
               graphics.fill(x, y, x + 1, y + 1, BLAST_FILL);
            }
         }
      }

      for (int step = 0; step < BLAST_CIRCLE_POINTS; step++) {
         double angle = step * (Math.PI * 2.0 / BLAST_CIRCLE_POINTS);
         int x = centerX + (int) Math.round(Math.cos(angle) * radiusX);
         int y = centerZ + (int) Math.round(Math.sin(angle) * radiusZ);
         if (x >= mapLeft && x < mapLeft + mapWidth && y >= mapTop && y < mapTop + mapHeight) {
            graphics.fill(x, y, x + 1, y + 1, color);
         }
      }
   }

   private void drawDottedLine(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color) {
      int dx = Math.abs(x1 - x0);
      int dy = Math.abs(y1 - y0);
      int steps = Math.max(dx, dy);
      if (steps <= 0) return;

      for (int i = 0; i <= steps; i += 4) {
         int x = x0 + (x1 - x0) * i / steps;
         int y = y0 + (y1 - y0) * i / steps;
         if (x >= mapLeft && x < mapLeft + mapWidth && y >= mapTop && y < mapTop + mapHeight) {
            graphics.fill(x, y, x + 1, y + 1, color);
         }
      }
   }

   private void drawPlayerMarker(GuiGraphicsExtractor graphics) {
      LocalPlayer player = Minecraft.getInstance().player;
      if (player == null || !arena.isInArenaColumn(player.getX(), player.getZ())) {
         return;
      }
      boolean endangered = isInBlast(player.getX(), player.getZ());
      int color = endangered ? DANGER_PULSE : PLAYER_EMERALD;
      int x = toMapX(player.getX());
      int z = toMapZ(player.getZ());

      drawSonarPulse(graphics, x, z, color & 0xFFFFFF, player.tickCount);
      // Diamantform (3x3)
      graphics.fill(x, z - 2, x + 1, z + 3, color);
      graphics.fill(x - 2, z, x + 3, z + 1, color);
      graphics.fill(x, z, x + 1, z + 1, 0xFFFFFFFF);

      drawMicroLabel(graphics, "DU", x, z + 6, endangered ? DANGER_PULSE : PLAYER_EMERALD);
   }

   private void drawContacts(GuiGraphicsExtractor graphics) {
      if (!hasScan) {
         return;
      }
      LocalPlayer player = Minecraft.getInstance().player;
      int tick = player == null ? 0 : player.tickCount;
      for (AirstrikeSystem.RadarPayload.RadarContact contact : contacts) {
         boolean doomed = isInBlast(contact.x(), contact.z());
         int color = doomed ? DANGER_PULSE : ENEMY_CRIMSON;
         int x = toMapX(contact.x());
         int z = toMapZ(contact.z());

         drawSonarPulse(graphics, x, z, color & 0xFFFFFF, tick + Math.floorMod(contact.name().hashCode(), 20));
         graphics.fill(x, z - 2, x + 1, z + 3, color);
         graphics.fill(x - 2, z, x + 3, z + 1, color);
         graphics.fill(x, z, x + 1, z + 1, 0xFFFFFFFF);

         String tag = doomed ? "☠ " + contact.name() : contact.name();
         drawMicroLabel(graphics, tag, x, z + 6, doomed ? DANGER_PULSE : TEXT_MUTED);
      }
   }

   private void drawHint(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
      String hint;
      int color;
      if (!hasScan) {
         hint = "SAT-LINK SCAN WIRD INITIALISIERT ...";
         color = TEXT_MUTED;
      } else if (!hasTarget()) {
         hint = isOverMap(mouseX, mouseY)
            ? String.format("ZIELPEILUNG: [ X: %d / Z: %d ]", (int) worldXAt(mouseX), (int) worldZAt(mouseY))
            : "KLICKE AUF DAS GELÄNDE, UM EINEN EINSCHLAG ZU PROGRAMMIEREN";
         color = OsokWidgets.COLOR_CYAN;
      } else {
         hint = "✦ ZIEL AUFGESCHALTET · FREIGABE ERTEILT ✦";
         color = OsokWidgets.COLOR_GOLD;
      }
      graphics.centeredText(font, Component.literal(hint), width / 2, mapTop + mapHeight + 7, color);
   }

   /** 4-Kachel Telemetrie-Deck am unteren Bildschirmrand */
   private void drawTelemetryDeck(GuiGraphicsExtractor graphics) {
      int y = mapTop + mapHeight + 20;
      int tileHeight = 28;
      int tileCount = 4;
      int gap = 4;
      int totalGaps = (tileCount - 1) * gap;
      int deckLeft = panelLeft + 24;
      int deckWidth = panelWidth - 48;
      int tileWidth = (deckWidth - totalGaps) / tileCount;

      LocalPlayer player = Minecraft.getInstance().player;
      boolean selfInBlast = hasTarget() && player != null && isInBlast(player.getX(), player.getZ());

      String targetVal = hasTarget() ? String.format("%d / %d", (int) selectedX, (int) selectedZ) : "---";
      String distVal = hasTarget() && player != null
         ? Math.round(Math.hypot(selectedX - player.getX(), selectedZ - player.getZ())) + "m"
         : "---";
      String radiusVal = "24m";
      String statusVal = !hasTarget() ? "STANDBY" : (selfInBlast ? "GEFAHR!" : "LOCK");
      int statusCol = !hasTarget() ? TEXT_MUTED : (selfInBlast ? DANGER_PULSE : OsokWidgets.COLOR_EMERALD);

      drawTelemetryTile(graphics, deckLeft, y, tileWidth, tileHeight, "ZIEL", targetVal, hasTarget() ? OsokWidgets.COLOR_CYAN : TEXT_MUTED);
      drawTelemetryTile(graphics, deckLeft + (tileWidth + gap), y, tileWidth, tileHeight, "DISTANZ", distVal, hasTarget() ? OsokWidgets.COLOR_CYAN : TEXT_MUTED);
      drawTelemetryTile(graphics, deckLeft + 2 * (tileWidth + gap), y, tileWidth, tileHeight, "RADIUS", radiusVal, OsokWidgets.COLOR_GOLD);
      drawTelemetryTile(graphics, deckLeft + 3 * (tileWidth + gap), y, tileWidth, tileHeight, "STATUS", statusVal, statusCol);
   }

   private void drawTelemetryTile(GuiGraphicsExtractor graphics, int x, int y, int w, int h, String title, String val, int valColor) {
      graphics.fill(x, y, x + w, y + h, 0xCC070B12);
      graphics.outline(x, y, w, h, 0x5500F0FF);
      graphics.horizontalLine(x + 2, x + w - 2, y + 1, 0x3300F0FF);

      drawMicroLabel(graphics, title, x + w / 2, y + 4, TEXT_MUTED);
      graphics.centeredText(font, Component.literal(val), x + w / 2, y + 15, valColor);
   }

   private void drawMicroLabel(GuiGraphicsExtractor graphics, String text, int x, int y, int color) {
      graphics.pose().pushMatrix();
      graphics.pose().translate(x, y);
      graphics.pose().scale(0.7F, 0.7F);
      graphics.centeredText(font, Component.literal(text), 0, 0, color);
      graphics.pose().popMatrix();
   }

   private boolean hasTarget() {
      return !Double.isNaN(selectedX);
   }

   private boolean isInBlast(double worldX, double worldZ) {
      return hasTarget()
         && Math.hypot(worldX - selectedX, worldZ - selectedZ) <= AirstrikeSystem.KILL_RADIUS;
   }

   private boolean isOverMap(double x, double y) {
      return x >= mapLeft && x < mapLeft + mapWidth && y >= mapTop && y < mapTop + mapHeight;
   }

   private CursorType cursorFor(double mouseX, double mouseY) {
      if (!isOverMap(mouseX, mouseY)) {
         return CursorTypes.ARROW;
      }
      return arena.isInArenaColumn(worldXAt(mouseX), worldZAt(mouseY))
         ? CursorTypes.CROSSHAIR
         : CursorTypes.NOT_ALLOWED;
   }

   private double worldXAt(double screenX) {
      return minX + (screenX - mapLeft) / mapWidth * (maxX - minX);
   }

   private double worldZAt(double screenY) {
      return minZ + (screenY - mapTop) / mapHeight * (maxZ - minZ);
   }

   private int toMapX(double x) {
      return mapLeft + (int) ((x - minX) / (maxX - minX) * mapWidth);
   }

   private int toMapZ(double z) {
      return mapTop + (int) ((z - minZ) / (maxZ - minZ) * mapHeight);
   }

   private static int clientTick() {
      LocalPlayer player = Minecraft.getInstance().player;
      return player == null ? 0 : player.tickCount;
   }

   private static void drawGlassPanel(GuiGraphicsExtractor graphics, int x, int y, int panelWidth, int panelHeight) {
      graphics.fill(x - 3, y - 3, x + panelWidth + 3, y + panelHeight + 3, 0xD0000000);
      graphics.fill(x, y, x + panelWidth, y + panelHeight, PANEL_BG);
      graphics.outline(x, y, panelWidth, panelHeight, PANEL_BORDER);
      graphics.outline(x + 2, y + 2, panelWidth - 4, panelHeight - 4, PANEL_INNER);

      // 4 Goldene Tech-Ecken
      int corner = 8;
      graphics.fill(x, y, x + corner, y + 2, BRACKET_GOLD);
      graphics.fill(x, y, x + 2, y + corner, BRACKET_GOLD);

      graphics.fill(x + panelWidth - corner, y, x + panelWidth, y + 2, BRACKET_GOLD);
      graphics.fill(x + panelWidth - 2, y, x + panelWidth, y + corner, BRACKET_GOLD);

      graphics.fill(x, y + panelHeight - 2, x + corner, y + panelHeight, BRACKET_GOLD);
      graphics.fill(x, y + panelHeight - corner, x + 2, y + panelHeight, BRACKET_GOLD);

      graphics.fill(x + panelWidth - corner, y + panelHeight - 2, x + panelWidth, y + panelHeight, BRACKET_GOLD);
      graphics.fill(x + panelWidth - 2, y + panelHeight - corner, x + panelWidth, y + panelHeight, BRACKET_GOLD);
   }

   private static void drawSonarPulse(GuiGraphicsExtractor graphics, int x, int y, int rgb, int tick) {
      int phase = Math.floorMod(tick, 24);
      int radius = 4 + phase / 3;
      int alpha = Math.max(0, 0x88 - phase * 5);
      graphics.outline(x - radius, y - radius, radius * 2 + 1, radius * 2 + 1, alpha << 24 | rgb);
   }

   /** Spektakulärer Cyber-Strike Startknopf mit Gefahren-Schraffur und Pulse-Glow */
   private static final class CyberStrikeButton extends AbstractButton {
      private final Consumer<CyberStrikeButton> onPress;
      private final java.util.function.BooleanSupplier isArmed;

      private CyberStrikeButton(int x, int y, int width, int height, Component label,
                               java.util.function.BooleanSupplier isArmed, Consumer<CyberStrikeButton> onPress) {
         super(x, y, width, height, label);
         this.isArmed = isArmed;
         this.onPress = onPress;
      }

      @Override
      public void onPress(InputWithModifiers input) {
         onPress.accept(this);
      }

      @Override
      protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
         boolean armed = isArmed.getAsBoolean();
         boolean highlighted = isHoveredOrFocused();
         int x = getX();
         int y = getY();
         int w = getWidth();
         int h = getHeight();

         if (armed) {
            float pulse = 0.5f + 0.5f * Mth.sin(clientTick() * 0.35f);
            int bgColor = highlighted ? 0xEE990022 : 0xDD770018;
            int borderColor = highlighted ? 0xFFFFFFFF : 0xFFFF3366;

            graphics.fill(x, y, x + w, y + h, bgColor);
            graphics.outline(x, y, w, h, borderColor);

            // Gefahrenstreifen an den Seiten
            for (int s = 0; s < 12; s += 3) {
               graphics.fill(x + 2 + s, y + 2, x + 3 + s, y + h - 2, OsokWidgets.COLOR_GOLD);
               graphics.fill(x + w - 14 + s, y + 2, x + w - 13 + s, y + h - 2, OsokWidgets.COLOR_GOLD);
            }
         } else {
            graphics.fill(x, y, x + w, y + h, highlighted ? 0xDD121824 : 0xAA0A0E18);
            graphics.outline(x, y, w, h, highlighted ? 0xAA00F0FF : 0x4400F0FF);
         }

         extractDefaultLabel(graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
         if (isHovered()) {
            graphics.requestCursor(active ? CursorTypes.POINTING_HAND : CursorTypes.NOT_ALLOWED);
         }
      }

      @Override
      public void updateWidgetNarration(NarrationElementOutput output) {
         defaultButtonNarrationText(output);
      }
   }

   /** Eleganter Glassmorphism-Abbruchknopf */
   private static final class CyberAbortButton extends AbstractButton {
      private final Consumer<CyberAbortButton> onPress;

      private CyberAbortButton(int x, int y, int width, int height, Component label, Consumer<CyberAbortButton> onPress) {
         super(x, y, width, height, label);
         this.onPress = onPress;
      }

      @Override
      public void onPress(InputWithModifiers input) {
         onPress.accept(this);
      }

      @Override
      protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
         boolean highlighted = isHoveredOrFocused();
         int x = getX();
         int y = getY();
         int w = getWidth();
         int h = getHeight();

         graphics.fill(x, y, x + w, y + h, highlighted ? 0x99182230 : 0x660B1018);
         graphics.outline(x, y, w, h, highlighted ? 0x8800F0FF : 0x3300F0FF);

         extractDefaultLabel(graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
         if (isHovered()) {
            graphics.requestCursor(CursorTypes.POINTING_HAND);
         }
      }

      @Override
      public void updateWidgetNarration(NarrationElementOutput output) {
         defaultButtonNarrationText(output);
      }
   }
}
