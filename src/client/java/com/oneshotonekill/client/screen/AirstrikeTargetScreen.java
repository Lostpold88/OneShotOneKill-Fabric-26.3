package com.oneshotonekill.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.item.runtime.AirstrikeSystem;
import com.oneshotonekill.network.OsokPayloads.RequestAirstrikePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Kriegsraum-Terminal für den Luftangriff: große Taktikkarte mit Koordinatenlineal und Raster,
 * Radarsweep vom eigenen Standort, einrastendes Fadenkreuz mit Druckwellen und eine Datenleiste
 * mit Status, Daten und Feuerknopf. Bernstein auf Schwarz, Rot für alles, was tötet.
 */
@SuppressWarnings({"MathClampMigration", "NullableProblems", "SameParameterValue"})
public final class AirstrikeTargetScreen extends Screen {
   private static final Identifier RADAR_TEXTURE = OneShotOneKill.INSTANCE.id("dynamic/airstrike_radar");

   private static final int AMBER = 0xFFB02E;
   private static final int AMBER_LIGHT = 0xFFD27A;
   private static final int RED = 0xFF4B3E;
   private static final int GREEN = 0x5BE38A;
   private static final int MUTED = 0x8A8F98;
   private static final int WHITE = 0xFFFFFF;
   private static final int PANEL_BG = 0xF2101216;
   private static final int PANEL_BG_DARK = 0xF20A0C0F;
   private static final int MAP_EMPTY = 0xFF0B0D10;

   private static final int SWEEP_TRAIL = 18;
   private static final float SWEEP_TRAIL_STEP = 0.04F;
   private static final float SWEEP_SPEED = 0.05F;
   private static final int SWEEP_MAX_ALPHA = 0x70;
   private static final int RING_POINTS = 84;
   private static final int LOCK_ON_TICKS = 8;
   private static final int[] GRID_STEPS = {8, 16, 32, 64, 128, 256};

   private final Arena arena;

   private double selectedX = Double.NaN;
   private double selectedZ = Double.NaN;
   private int selectedAtTick = -1;

   private double minX;
   private double maxX;
   private double minZ;
   private double maxZ;
   private int mapLeft;
   private int mapTop;
   private int mapWidth;
   private int mapHeight;
   private int panelLeft;
   private int panelTop;
   private int panelWidth;
   private int panelHeight;

   private List<Integer> terrainColors = List.of();
   private int terrainCells;
   private List<AirstrikeSystem.RadarPayload.RadarContact> contacts = List.of();
   private boolean hasScan;
   private DynamicTexture radarTexture;
   private int uploadedRadarRevision = -1;
   private int terrainRevision = -1;
   private StrikeButton fireButton;

   public AirstrikeTargetScreen() {
      super(Component.translatable("gui.oneshotonekill.airstrike.title"));
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

      // Kompaktes, zentriertes Panel; bei hoher GUI-Skalierung schrumpft die Karte.
      int maxWidth = Math.min(380, width - 64);
      int maxHeight = Math.min(260, height - 210);
      mapWidth = Math.max(100, Math.min(maxWidth, (int) Math.round(maxHeight * aspect)));
      mapHeight = Math.max(100, Math.min(maxHeight, (int) Math.round(mapWidth / aspect)));

      panelWidth = Math.max(Math.min(width - 24, 340), Math.min(width - 24, mapWidth + 48));
      panelHeight = mapHeight + 156;
      panelLeft = width / 2 - panelWidth / 2;
      panelTop = height / 2 - panelHeight / 2;
      mapLeft = panelLeft + (panelWidth - mapWidth) / 2;
      mapTop = panelTop + 46;

      int deckLeft = panelLeft + 16;
      int deckWidth = panelWidth - 32;
      int buttonY = mapTop + mapHeight + 76;
      int fireWidth = deckWidth * 62 / 100;
      fireButton = new StrikeButton(deckLeft, buttonY, fireWidth, 24,
         Component.translatable("gui.oneshotonekill.airstrike.launch"), () -> hasTarget(), ignored -> confirm());
      fireButton.active = false;
      addRenderableWidget(fireButton);
      addRenderableWidget(new AbortButton(deckLeft + fireWidth + 6, buttonY, deckWidth - fireWidth - 6, 24,
         Component.translatable("gui.oneshotonekill.airstrike.cancel"), ignored -> onClose()));

      ClientPlayNetworking.send(AirstrikeSystem.OpenRadarPayload.EMPTY);
   }

   @Override
   public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
      if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
         onClose();
         return true;
      }
      if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && isOverMap(event.x(), event.y())) {
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

   /** Enter löst den Angriff aus, sobald ein Ziel gewählt ist. */
   @Override
   public boolean keyPressed(KeyEvent event) {
      if (event.isConfirmation() && hasTarget()) {
         confirm();
         return true;
      }
      return super.keyPressed(event);
   }

   /** Nur die Unschärfe; der dunkle Schleier folgt in {@link #extractRenderState}. */
   @Override
   public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
      graphics.blurBeforeThisStratum();
      this.minecraft.gui.hud.extractDeferredSubtitles();
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
      graphics.fillGradient(0, 0, width, height, 0xCC050608, 0xDD020304);

      drawPanel(graphics);
      drawTitle(graphics);
      graphics.fill(mapLeft - 2, mapTop - 2, mapLeft + mapWidth + 2, mapTop + mapHeight + 2, 0xFF050607);
      drawTerrain(graphics);
      drawGrid(graphics);
      drawSweep(graphics, partial);
      drawScanlines(graphics);

      graphics.enableScissor(mapLeft, mapTop, mapLeft + mapWidth, mapTop + mapHeight);
      drawBlastPreview(graphics, mouseX, mouseY);
      drawTarget(graphics, partial);
      drawContacts(graphics);
      drawPlayerMarker(graphics);
      graphics.disableScissor();

      drawMapFrame(graphics);
      drawHint(graphics, mouseX, mouseY);
      drawDeck(graphics);
      super.extractRenderState(graphics, mouseX, mouseY, partial);
   }

   private void confirm() {
      if (hasTarget()) {
         ClientPlayNetworking.send(new RequestAirstrikePayload(selectedX, selectedZ));
         onClose();
      }
   }

   // ---------------------------------------------------------------- Panel & Titel

   private void drawPanel(GuiGraphicsExtractor graphics) {
      boolean armed = hasTarget();
      int edge = armed ? RED : AMBER;
      graphics.fill(panelLeft - 3, panelTop - 3, panelLeft + panelWidth + 3, panelTop + panelHeight + 3, 0xB0000000);
      graphics.fillGradient(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, PANEL_BG, PANEL_BG_DARK);
      float beat = armed ? 0.5F + 0.5F * Mth.sin(clientTick() * 0.3F) : 0.0F;
      graphics.outline(panelLeft, panelTop, panelWidth, panelHeight, alpha(armed ? 120 + Math.round(120 * beat) : 0x88, edge));
      graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + 2, alpha(0xFF, edge));
   }

   private void drawTitle(GuiGraphicsExtractor graphics) {
      int left = panelLeft + 16;
      int y = panelTop + 9;
      boolean blink = (clientTick() / 10) % 2 == 0;
      graphics.fill(left, y + 1, left + 5, y + 6, alpha(blink ? 0xFF : 0x55, hasTarget() ? RED : AMBER));

      Component terminal = Component.translatable("gui.oneshotonekill.airstrike.terminal");
      graphics.pose().pushMatrix();
      graphics.pose().translate(left + 10, y);
      float fit = Math.min(1.0F, (panelWidth - 42) / (float) Math.max(1, font.width(terminal)));
      graphics.pose().scale(fit, fit);
      graphics.text(font, terminal, 0, 0, alpha(0xFF, AMBER));
      graphics.pose().popMatrix();

      drawMicro(graphics, Component.translatable("gui.oneshotonekill.airstrike.sector", arena.getDisplayName().toUpperCase()).getString(),
         left + 10, y + 12, alpha(0xFF, MUTED), -1);
      graphics.fill(left, panelTop + 30, panelLeft + panelWidth - 16, panelTop + 31, alpha(0x55, AMBER));
   }

   // ---------------------------------------------------------------- Karte

   private void drawTerrain(GuiGraphicsExtractor graphics) {
      if (!hasScan) {
         graphics.fill(mapLeft, mapTop, mapLeft + mapWidth, mapTop + mapHeight, MAP_EMPTY);
         return;
      }
      uploadRadarTexture();
      graphics.blit(RenderPipelines.GUI_TEXTURED, RADAR_TEXTURE, mapLeft, mapTop, 0, 0, mapWidth, mapHeight,
         terrainCells, terrainCells, terrainCells, terrainCells);
      // Leicht abdunkeln, damit Marker und Raster auf hellem Gelände lesbar bleiben.
      graphics.fill(mapLeft, mapTop, mapLeft + mapWidth, mapTop + mapHeight, 0x40000000);
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

   /** Rasterlinien in runden Blockabständen; der Abstand passt sich der Kartengröße an. */
   private int gridStep() {
      double pixelsPerBlock = mapWidth / Math.max(1.0, maxX - minX);
      for (int step : GRID_STEPS) {
         if (step * pixelsPerBlock >= 38) {
            return step;
         }
      }
      return GRID_STEPS[GRID_STEPS.length - 1];
   }

   private void drawGrid(GuiGraphicsExtractor graphics) {
      int step = gridStep();
      int line = alpha(0x22, AMBER);
      for (double wx = Math.ceil(minX / step) * step; wx < maxX; wx += step) {
         int px = toMapX(wx);
         graphics.verticalLine(px, mapTop, mapTop + mapHeight - 1, line);
      }
      for (double wz = Math.ceil(minZ / step) * step; wz < maxZ; wz += step) {
         int pz = toMapZ(wz);
         graphics.horizontalLine(mapLeft, mapLeft + mapWidth - 1, pz, line);
      }
   }

   /** Radarsweep, der vom eigenen Standort ausgeht. */
   private void drawSweep(GuiGraphicsExtractor graphics, float partial) {
      float angle = (clientTick() + partial) * SWEEP_SPEED;
      int reach = (int) Math.ceil(Math.hypot(mapWidth, mapHeight));
      LocalPlayer player = Minecraft.getInstance().player;
      float originX = mapLeft + mapWidth / 2.0F;
      float originZ = mapTop + mapHeight / 2.0F;
      if (player != null && arena.isInArenaColumn(player.getX(), player.getZ())) {
         originX = toMapX(player.getX()) + 0.5F;
         originZ = toMapZ(player.getZ()) + 0.5F;
      }

      graphics.enableScissor(mapLeft, mapTop, mapLeft + mapWidth, mapTop + mapHeight);
      graphics.pose().pushMatrix();
      graphics.pose().translate(originX, originZ);
      for (int trail = SWEEP_TRAIL - 1; trail >= 0; trail--) {
         int a = SWEEP_MAX_ALPHA * (SWEEP_TRAIL - trail) / SWEEP_TRAIL;
         graphics.pose().pushMatrix();
         graphics.pose().rotate(angle - trail * SWEEP_TRAIL_STEP);
         graphics.fill(0, -1, reach, 0, alpha(a, AMBER));
         graphics.pose().popMatrix();
      }
      graphics.pose().popMatrix();
      graphics.disableScissor();
   }

   private void drawScanlines(GuiGraphicsExtractor graphics) {
      for (int y = mapTop + 1; y < mapTop + mapHeight; y += 3) {
         graphics.fill(mapLeft, y, mapLeft + mapWidth, y + 1, 0x14000000);
      }
   }

   /** Rahmen, Eckwinkel und Koordinatenlineal mit Skalenstrichen. */
   private void drawMapFrame(GuiGraphicsExtractor graphics) {
      int right = mapLeft + mapWidth - 1;
      int bottom = mapTop + mapHeight - 1;
      graphics.outline(mapLeft, mapTop, mapWidth, mapHeight, alpha(0x88, AMBER));

      int arm = 12;
      int c = alpha(0xFF, AMBER);
      graphics.horizontalLine(mapLeft - 2, mapLeft + arm, mapTop - 2, c);
      graphics.verticalLine(mapLeft - 2, mapTop - 2, mapTop + arm, c);
      graphics.horizontalLine(right - arm, right + 2, mapTop - 2, c);
      graphics.verticalLine(right + 2, mapTop - 2, mapTop + arm, c);
      graphics.horizontalLine(mapLeft - 2, mapLeft + arm, bottom + 2, c);
      graphics.verticalLine(mapLeft - 2, bottom - arm, bottom + 2, c);
      graphics.horizontalLine(right - arm, right + 2, bottom + 2, c);
      graphics.verticalLine(right + 2, bottom - arm, bottom + 2, c);

      int step = gridStep();
      int tick = alpha(0xAA, AMBER);
      int label = alpha(0xFF, MUTED);
      for (double wx = Math.ceil(minX / step) * step; wx < maxX; wx += step) {
         int px = toMapX(wx);
         graphics.verticalLine(px, mapTop - 4, mapTop - 1, tick);
         drawMicro(graphics, String.valueOf((int) wx), px, mapTop - 12, label, 0);
      }
      for (double wz = Math.ceil(minZ / step) * step; wz < maxZ; wz += step) {
         int pz = toMapZ(wz);
         graphics.horizontalLine(mapLeft - 4, mapLeft - 1, pz, tick);
         drawMicro(graphics, String.valueOf((int) wz), mapLeft - 6, pz - 3, label, 1);
      }
      drawMicro(graphics, "N", mapLeft + mapWidth / 2, mapTop + 4, alpha(0xFF, AMBER_LIGHT), 0);
   }

   // ---------------------------------------------------------------- Zielen

   private void drawBlastPreview(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
      if (!isOverMap(mouseX, mouseY)) {
         return;
      }
      double x = worldXAt(mouseX);
      double z = worldZAt(mouseY);
      if (!arena.isInArenaColumn(x, z)) {
         return;
      }
      int line = alpha(0x30, AMBER_LIGHT);
      graphics.horizontalLine(mapLeft, mapLeft + mapWidth - 1, mouseY, line);
      graphics.verticalLine(mouseX, mapTop, mapTop + mapHeight - 1, line);
      drawBlastZone(graphics, x, z, false);
      drawBrackets(graphics, mouseX, mouseY, 5, 3, alpha(0xFF, AMBER_LIGHT));
      drawTag(graphics, "X " + (int) x + "  Z " + (int) z, mouseX + 36, mouseY + 8, alpha(0xFF, AMBER_LIGHT));
   }

   /** Einrastendes Fadenkreuz: Klammern ziehen sich zusammen, dann laufen Druckwellen vom Ziel aus. */
   private void drawTarget(GuiGraphicsExtractor graphics, float partial) {
      if (!hasTarget()) {
         return;
      }
      int x = toMapX(selectedX);
      int z = toMapZ(selectedZ);
      drawBlastZone(graphics, selectedX, selectedZ, true);

      LocalPlayer player = Minecraft.getInstance().player;
      if (player != null && arena.isInArenaColumn(player.getX(), player.getZ())) {
         drawDashedLine(graphics, toMapX(player.getX()), toMapZ(player.getZ()), x, z, alpha(0xAA, AMBER_LIGHT));
      }

      float lock = Mth.clamp((clientTick() + partial - selectedAtTick) / LOCK_ON_TICKS, 0.0F, 1.0F);
      float ease = 1.0F - (1.0F - lock) * (1.0F - lock) * (1.0F - lock);
      int half = 8 + Math.round(24 * (1.0F - ease));
      int red = alpha(Math.round(120 + 135 * ease), RED);
      drawBrackets(graphics, x, z, half, 6, red);

      // Fadenkreuz mit Lücke in der Mitte.
      int c = alpha(0xFF, RED);
      graphics.horizontalLine(x - 12, x - 3, z, c);
      graphics.horizontalLine(x + 3, x + 12, z, c);
      graphics.verticalLine(x, z - 12, z - 3, c);
      graphics.verticalLine(x, z + 3, z + 12, c);
      graphics.fill(x, z, x + 1, z + 1, alpha(0xFF, WHITE));

      drawTag(graphics, "X " + (int) selectedX + "  Z " + (int) selectedZ, x,
         z - (int) Math.round(AirstrikeSystem.KILL_RADIUS / (maxZ - minZ) * mapHeight) - 16, alpha(0xFF, RED));
   }

   /** Wirkungskreis: scharf als pulsierende rote Fläche mit Druckwellen und kreisenden Strichen, sonst als Vorschau. */
   private void drawBlastZone(GuiGraphicsExtractor graphics, double worldX, double worldZ, boolean armed) {
      double radiusX = AirstrikeSystem.KILL_RADIUS / (maxX - minX) * mapWidth;
      double radiusZ = AirstrikeSystem.KILL_RADIUS / (maxZ - minZ) * mapHeight;
      int centerX = toMapX(worldX);
      int centerZ = toMapZ(worldZ);

      if (!armed) {
         drawBlastDisc(graphics, centerX, centerZ, radiusX, radiusZ, AMBER_LIGHT, 0x14);
         drawEllipse(graphics, centerX, centerZ, radiusX, radiusZ, alpha(0x99, AMBER_LIGHT), 0.0, false);
         return;
      }

      float pulse = 0.5F + 0.5F * Mth.sin(clientTick() * 0.25F);
      drawBlastDisc(graphics, centerX, centerZ, radiusX, radiusZ, RED, 0x26 + Math.round(0x20 * pulse));
      for (int wave = 0; wave < 3; wave++) {
         float t = ((clientTick() + wave * 7) % 21) / 21.0F;
         drawEllipse(graphics, centerX, centerZ, radiusX * t, radiusZ * t, alpha(Math.round(0xB0 * (1.0F - t)), RED), 0.0, false);
      }
      drawEllipse(graphics, centerX, centerZ, radiusX, radiusZ, alpha(0xFF, RED), 0.0, false);
      drawEllipse(graphics, centerX, centerZ, radiusX + 4, radiusZ + 4, alpha(0xCC, AMBER), clientTick() * 0.06, true);
   }

   /** Gefüllte Ellipse zeilenweise, auf die Karte beschnitten. */
   private void drawBlastDisc(GuiGraphicsExtractor graphics, int centerX, int centerZ, double radiusX, double radiusZ,
                              int rgb, int alpha) {
      int reach = (int) Math.ceil(radiusZ);
      for (int dz = -reach; dz <= reach; dz++) {
         int row = centerZ + dz;
         double t = radiusZ <= 0.0 ? 2.0 : dz / radiusZ;
         if (row < mapTop || row >= mapTop + mapHeight || Math.abs(t) > 1.0) {
            continue;
         }
         int half = (int) Math.round(radiusX * Math.sqrt(1.0 - t * t));
         int left = Math.max(mapLeft, centerX - half);
         int right = Math.min(mapLeft + mapWidth, centerX + half + 1);
         if (right > left) {
            graphics.fill(left, row, right, row + 1, alpha(alpha, rgb));
         }
      }
   }

   /** Ellipse aus Einzelpunkten; gestrichelt dreht sie sich mit {@code spin}. */
   private void drawEllipse(GuiGraphicsExtractor graphics, int cx, int cy, double rx, double ry, int color, double spin,
                            boolean dashed) {
      for (int i = 0; i < RING_POINTS; i++) {
         if (dashed && (i / 4) % 2 == 1) {
            continue;
         }
         double a = spin + i * (Math.PI * 2.0 / RING_POINTS);
         int px = cx + (int) Math.round(Math.cos(a) * rx);
         int py = cy + (int) Math.round(Math.sin(a) * ry);
         if (isInsideMap(px, py)) {
            graphics.fill(px, py, px + 1, py + 1, color);
         }
      }
   }

   /** Vier Eckklammern um einen Punkt. */
   private static void drawBrackets(GuiGraphicsExtractor graphics, int cx, int cy, int half, int arm, int color) {
      graphics.horizontalLine(cx - half, cx - half + arm, cy - half, color);
      graphics.verticalLine(cx - half, cy - half, cy - half + arm, color);
      graphics.horizontalLine(cx + half - arm, cx + half, cy - half, color);
      graphics.verticalLine(cx + half, cy - half, cy - half + arm, color);
      graphics.horizontalLine(cx - half, cx - half + arm, cy + half, color);
      graphics.verticalLine(cx - half, cy + half - arm, cy + half, color);
      graphics.horizontalLine(cx + half - arm, cx + half, cy + half, color);
      graphics.verticalLine(cx + half, cy + half - arm, cy + half, color);
   }

   /** Kleines Etikett auf dunklem Grund, in die Karte geklemmt. */
   private void drawTag(GuiGraphicsExtractor graphics, String text, int centerX, int y, int color) {
      int w = (int) (font.width(text) * 0.7F) + 8;
      int h = 11;
      int x = Mth.clamp(centerX - w / 2, mapLeft + 2, Math.max(mapLeft + 2, mapLeft + mapWidth - w - 2));
      int yy = Mth.clamp(y, mapTop + 2, Math.max(mapTop + 2, mapTop + mapHeight - h - 2));
      graphics.fill(x, yy, x + w, yy + h, 0xDD07080A);
      graphics.outline(x, yy, w, h, (color & 0x00FFFFFF) | 0x99000000);
      drawMicro(graphics, text, x + w / 2, yy + 3, color, 0);
   }

   private void drawDashedLine(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int color) {
      int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
      if (steps <= 0) {
         return;
      }
      int shift = clientTick() / 2 % 8;
      for (int i = 0; i <= steps; i++) {
         if ((i + shift) % 8 >= 4) {
            continue;
         }
         int x = x0 + (x1 - x0) * i / steps;
         int y = y0 + (y1 - y0) * i / steps;
         graphics.fill(x, y, x + 1, y + 1, color);
      }
   }

   // ---------------------------------------------------------------- Marker

   private void drawPlayerMarker(GuiGraphicsExtractor graphics) {
      LocalPlayer player = Minecraft.getInstance().player;
      if (player == null || !arena.isInArenaColumn(player.getX(), player.getZ())) {
         return;
      }
      boolean endangered = isInBlast(player.getX(), player.getZ());
      int rgb = endangered ? RED : GREEN;
      int color = alpha(0xFF, rgb);
      int x = toMapX(player.getX());
      int z = toMapZ(player.getZ());

      drawSonarPulse(graphics, x, z, rgb, player.tickCount);
      graphics.fill(x - 2, z - 2, x + 3, z + 3, color);
      graphics.fill(x - 1, z - 1, x + 2, z + 2, 0xFF050607);
      graphics.fill(x, z, x + 1, z + 1, color);
      // Blickrichtung (Yaw 0 schaut nach +Z).
      double yaw = Math.toRadians(player.getYRot());
      for (int d = 4; d <= 9; d++) {
         int hx = x + (int) Math.round(-Math.sin(yaw) * d);
         int hz = z + (int) Math.round(Math.cos(yaw) * d);
         graphics.fill(hx, hz, hx + 1, hz + 1, color);
      }
      drawMicro(graphics, Component.translatable("gui.oneshotonekill.airstrike.you").getString(), x, z + 7, color, 0);
   }

   private void drawContacts(GuiGraphicsExtractor graphics) {
      if (!hasScan) {
         return;
      }
      LocalPlayer player = Minecraft.getInstance().player;
      int tick = player == null ? 0 : player.tickCount;
      for (AirstrikeSystem.RadarPayload.RadarContact contact : contacts) {
         boolean doomed = isInBlast(contact.x(), contact.z());
         int rgb = doomed ? RED : AMBER;
         int color = alpha(0xFF, rgb);
         int x = toMapX(contact.x());
         int z = toMapZ(contact.z());

         drawSonarPulse(graphics, x, z, rgb, tick + Math.floorMod(contact.name().hashCode(), 20));
         graphics.outline(x - 2, z - 2, 5, 5, color);
         graphics.fill(x, z, x + 1, z + 1, color);
         if (doomed && (tick / 4) % 2 == 0) {
            graphics.fill(x - 3, z - 3, x + 4, z + 4, alpha(0x40, RED));
         }
         drawMicro(graphics, doomed ? "☠ " + contact.name() : contact.name(), x, z + 6, doomed ? color : alpha(0xDD, AMBER_LIGHT), 0);
      }
   }

   private static void drawSonarPulse(GuiGraphicsExtractor graphics, int x, int y, int rgb, int tick) {
      int phase = Math.floorMod(tick, 24);
      int radius = 4 + phase / 3;
      graphics.outline(x - radius, y - radius, radius * 2 + 1, radius * 2 + 1, alpha(Math.max(0, 0x80 - phase * 5), rgb));
   }

   private void drawHint(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
      Component hint;
      int rgb;
      if (!hasScan) {
         hint = Component.translatable("gui.oneshotonekill.airstrike.scan_init");
         rgb = MUTED;
      } else if (!hasTarget()) {
         hint = isOverMap(mouseX, mouseY)
            ? Component.translatable("gui.oneshotonekill.airstrike.target_coords", (int) worldXAt(mouseX), (int) worldZAt(mouseY))
            : Component.translatable("gui.oneshotonekill.airstrike.click_hint");
         rgb = AMBER;
      } else {
         hint = Component.translatable("gui.oneshotonekill.airstrike.locked_hint");
         rgb = RED;
      }
      drawFittedText(graphics, hint, mapLeft + mapWidth / 2, mapTop + mapHeight + 8, mapWidth, alpha(0xFF, rgb));
   }

   // ---------------------------------------------------------------- Datenleiste

   /** Statusbalken und vier Datenzellen unter der Karte. */
   private void drawDeck(GuiGraphicsExtractor graphics) {
      boolean armed = hasTarget();
      LocalPlayer player = Minecraft.getInstance().player;
      boolean selfInBlast = armed && player != null && isInBlast(player.getX(), player.getZ());
      int enemiesInBlast = 0;
      if (armed) {
         for (AirstrikeSystem.RadarPayload.RadarContact contact : contacts) {
            if (isInBlast(contact.x(), contact.z())) {
               enemiesInBlast++;
            }
         }
      }

      String statusText;
      int statusRgb;
      if (!armed) {
         statusText = Component.translatable("gui.oneshotonekill.airstrike.status_standby").getString();
         statusRgb = MUTED;
      } else if (selfInBlast) {
         statusText = Component.translatable("gui.oneshotonekill.airstrike.status_danger").getString();
         statusRgb = RED;
      } else if (enemiesInBlast > 0) {
         statusText = Component.translatable("gui.oneshotonekill.airstrike.in_blast", enemiesInBlast).getString();
         statusRgb = AMBER;
      } else {
         statusText = Component.translatable("gui.oneshotonekill.airstrike.status_lock").getString();
         statusRgb = GREEN;
      }

      int left = panelLeft + 16;
      int w = panelWidth - 32;
      int y = mapTop + mapHeight + 20;
      int boxAlpha = selfInBlast ? 0x30 + Math.round(0x30 * (0.5F + 0.5F * Mth.sin(clientTick() * 0.5F))) : 0x26;
      graphics.fill(left, y, left + w, y + 20, alpha(boxAlpha, statusRgb));
      graphics.outline(left, y, w, 20, alpha(0xAA, statusRgb));
      graphics.fill(left, y, left + 3, y + 20, alpha(0xFF, statusRgb));
      drawMicro(graphics, Component.translatable("gui.oneshotonekill.airstrike.tile_status").getString(), left + 9, y + 7, alpha(0xFF, MUTED), -1);
      drawFittedText(graphics, Component.literal(statusText), left + w / 2 + 14, y + 6, w - 90, alpha(0xFF, statusRgb));

      String targetVal = armed ? (int) selectedX + " / " + (int) selectedZ : "---";
      String distVal = armed && player != null
         ? Math.round(Math.hypot(selectedX - player.getX(), selectedZ - player.getZ())) + "m"
         : "---";
      int gap = 4;
      int cell = (w - 3 * gap) / 4;
      int cy = y + 26;
      int valueRgb = armed ? AMBER_LIGHT : MUTED;
      drawCell(graphics, left, cy, cell, Component.translatable("gui.oneshotonekill.airstrike.tile_target").getString(), targetVal, valueRgb);
      drawCell(graphics, left + (cell + gap), cy, cell, Component.translatable("gui.oneshotonekill.airstrike.tile_distance").getString(), distVal, valueRgb);
      drawCell(graphics, left + 2 * (cell + gap), cy, cell, Component.translatable("gui.oneshotonekill.airstrike.tile_radius").getString(),
         (int) AirstrikeSystem.KILL_RADIUS + "m", AMBER);
      drawCell(graphics, left + 3 * (cell + gap), cy, cell, Component.translatable("gui.oneshotonekill.airstrike.tile_eta").getString(),
         String.format("%.1fs", AirstrikeSystem.WARNING_TICKS / 20.0), armed ? RED : MUTED);
   }

   private void drawCell(GuiGraphicsExtractor graphics, int x, int y, int w, String title, String value, int rgb) {
      graphics.fillGradient(x, y, x + w, y + 24, 0xDD14171C, 0xDD0B0D10);
      graphics.outline(x, y, w, 24, alpha(0x44, AMBER));
      graphics.fill(x + 1, y + 1, x + w - 1, y + 2, alpha(0xAA, rgb));
      drawMicro(graphics, title, x + w / 2, y + 5, alpha(0xFF, MUTED), 0);
      drawFittedText(graphics, Component.literal(value), x + w / 2, y + 13, w - 6, alpha(0xFF, rgb));
   }

   // ---------------------------------------------------------------- Text

   /** Verkleinerter Text; {@code align}: -1 links, 0 Mitte, 1 rechts vom Punkt. */
   private void drawMicro(GuiGraphicsExtractor graphics, String text, int x, int y, int color, int align) {
      int w = font.width(text);
      graphics.pose().pushMatrix();
      graphics.pose().translate(x, y);
      graphics.pose().scale(0.7F, 0.7F);
      graphics.text(font, text, align < 0 ? 0 : align == 0 ? -w / 2 : -w, 0, color);
      graphics.pose().popMatrix();
   }

   /** Text, der bei Platzmangel verkleinert statt abgeschnitten wird. */
   private void drawFittedText(GuiGraphicsExtractor graphics, Component text, int centerX, int y, int maxWidth, int color) {
      int textWidth = font.width(text);
      if (textWidth <= maxWidth) {
         graphics.centeredText(font, text, centerX, y, color);
         return;
      }
      float scale = maxWidth / (float) textWidth;
      graphics.pose().pushMatrix();
      graphics.pose().translate(centerX, y + 4.0F * (1.0F - scale));
      graphics.pose().scale(scale, scale);
      graphics.centeredText(font, text, 0, 0, color);
      graphics.pose().popMatrix();
   }

   // ---------------------------------------------------------------- Hilfen

   private static int alpha(int alpha, int rgb) {
      return Mth.clamp(alpha, 0, 255) << 24 | rgb & 0x00FFFFFF;
   }

   private boolean hasTarget() {
      return !Double.isNaN(selectedX);
   }

   private boolean isInBlast(double worldX, double worldZ) {
      return hasTarget() && Math.hypot(worldX - selectedX, worldZ - selectedZ) <= AirstrikeSystem.KILL_RADIUS;
   }

   private boolean isInsideMap(int x, int y) {
      return x >= mapLeft && x < mapLeft + mapWidth && y >= mapTop && y < mapTop + mapHeight;
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

   // ---------------------------------------------------------------- Knöpfe

   /** Feuerknopf: gesperrt flach und grau, scharf rot mit wandernden Warnstreifen und pulsierendem Rand. */
   private static final class StrikeButton extends AbstractButton {
      private final Consumer<StrikeButton> onPress;
      private final BooleanSupplier isArmed;

      private StrikeButton(int x, int y, int width, int height, Component label, BooleanSupplier isArmed,
                           Consumer<StrikeButton> onPress) {
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
            float pulse = 0.5F + 0.5F * Mth.sin(clientTick() * 0.35F);
            graphics.outline(x - 1, y - 1, w + 2, h + 2, alpha(50 + Math.round(120 * pulse), RED));
            graphics.fillGradient(x, y, x + w, y + h, highlighted ? 0xF0C4261B : 0xF0A31D14, highlighted ? 0xF0801510 : 0xF0650F0B);
            graphics.outline(x, y, w, h, highlighted ? alpha(0xFF, WHITE) : alpha(0xFF, RED));

            // Schräge Warnstreifen links und rechts, die langsam wandern.
            int shift = (int) (Util.getMillis() / 80L % 8L);
            for (int row = 2; row < h - 2; row++) {
               for (int s = -((row + shift) % 8); s < 14; s += 8) {
                  int from = Math.max(0, s);
                  int to = Math.min(14, s + 4);
                  if (to > from) {
                     graphics.fill(x + 2 + from, y + row, x + 2 + to, y + row + 1, alpha(0xFF, AMBER));
                     graphics.fill(x + w - 16 + from, y + row, x + w - 16 + to, y + row + 1, alpha(0xFF, AMBER));
                  }
               }
            }
         } else {
            graphics.fill(x, y, x + w, y + h, highlighted ? 0xCC15181D : 0xAA0D0F12);
            graphics.outline(x, y, w, h, alpha(highlighted ? 0x77 : 0x44, MUTED));
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

   /** Schlichter Abbruchknopf. */
   private static final class AbortButton extends AbstractButton {
      private final Consumer<AbortButton> onPress;

      private AbortButton(int x, int y, int width, int height, Component label, Consumer<AbortButton> onPress) {
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
         graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), highlighted ? 0x99221C10 : 0x660D0F12);
         graphics.outline(getX(), getY(), getWidth(), getHeight(), alpha(highlighted ? 0xAA : 0x55, AMBER));

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
