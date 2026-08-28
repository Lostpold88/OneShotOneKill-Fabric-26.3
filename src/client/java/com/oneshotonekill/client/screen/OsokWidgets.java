package com.oneshotonekill.client.screen;

import com.oneshotonekill.shared.OsokColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/**
 * Die zentralen Zeichenbausteine und das moderne UI-Designsystem der Mod.
 *
 * <p>Bietet sowohl abwärtskompatible Standardkomponenten als auch veredelte
 * Cyber-Tactical / Esports Dark-Theme UI-Bausteine mit Glassmorphismus,
 * dynamischen Glow-Kanten, Micro-Interaktionen und flüssigen Hover-Zuständen.</p>
 */
public final class OsokWidgets {
   // Farbpaletten-Konstanten für einheitliches Design
   public static final int COLOR_SCRIM = 0x77000000;
   public static final int COLOR_CARD_BG_TOP = 0xF6131722;
   public static final int COLOR_CARD_BG_BOTTOM = 0xFB0B0D14;
   public static final int COLOR_CARD_BORDER = 0xFF283042;
   public static final int COLOR_CARD_BORDER_HOVER = 0xFF455270;
   public static final int COLOR_LIGHT_EDGE = 0x22FFFFFF;

   // Die Akzentfarben stehen im gemeinsamen Source-Set: OsokEffects schickt sie als
   // Akzentfarbe einer Einblendung mit und erreicht diese Klasse hier nicht.
   public static final int COLOR_GOLD = OsokColors.GOLD;
   public static final int COLOR_CYAN = OsokColors.CYAN;
   public static final int COLOR_EMERALD = OsokColors.EMERALD;
   public static final int COLOR_CRIMSON = OsokColors.CRIMSON;
   public static final int COLOR_AMBER = OsokColors.AMBER;
   public static final int COLOR_PURPLE = OsokColors.PURPLE;

   public static final int COLOR_TEXT_WHITE = 0xFFF8FAFC;
   public static final int COLOR_TEXT_MUTED = 0xFF94A3B8;
   public static final int COLOR_TEXT_FAINT = 0xFF64748B;
   public static final int COLOR_TEXT_DISABLED = 0xFF475569;

   private OsokWidgets() {
   }

   // =========================================================================
   // 1. Abwärtskompatible Basis-Methoden (für unveränderte Screens)
   // =========================================================================

   /** Fläche mit Rahmen – das Gegenstück zu owos {@code Surface.flat().and(Surface.outline())}. */
   public static void panel(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int fill, int outline) {
      graphics.fill(x0, y0, x1, y1, fill);
      graphics.horizontalLine(x0, x1 - 1, y0, outline);
      graphics.horizontalLine(x0, x1 - 1, y1 - 1, outline);
      graphics.verticalLine(x0, y0, y1 - 1, outline);
      graphics.verticalLine(x1 - 1, y0, y1 - 1, outline);
   }

   public static void fillOnly(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int fill) {
      graphics.fill(x0, y0, x1, y1, fill);
   }

   public static void divider(GuiGraphicsExtractor graphics, int x0, int x1, int y, int color) {
      graphics.fill(x0, y, x1, y + 1, color);
   }

   /**
    * Schaltfläche im Vanilla-Stil der Mod.
    */
   public static void button(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height,
                             String label, boolean enabled, boolean hovered, int accent) {
      int background = enabled ? (hovered ? 0xFF34383A : 0xFF25282A) : 0xFF1B1D1F;
      int border = enabled ? (hovered ? 0xFFE4E0D5 : 0xFF5B6063) : 0xFF33383B;
      int text = enabled ? 0xFFE8EAF2 : 0xFF6A7078;

      graphics.fill(x, y, x + width, y + height, background);
      graphics.horizontalLine(x, x + width - 1, y, border);
      graphics.horizontalLine(x, x + width - 1, y + height - 1, border);
      graphics.verticalLine(x, y, y + height - 1, border);
      graphics.verticalLine(x + width - 1, y, y + height - 1, border);
      if (enabled) {
         graphics.fill(x + 2, y + 2, x + 4, y + height - 2, accent);
      }
      graphics.centeredText(font, Component.literal(label), x + width / 2, y + (height - 8) / 2, text);
   }

   /** Kleines Etikett mit Rahmen, etwa „Du bist hier“ oder der Match-Zustand. */
   public static void badge(GuiGraphicsExtractor graphics, Font font, int x, int y, String text, int color) {
      int width = font.width(text) + 8;
      graphics.fill(x, y, x + width, y + 12, 0x66000000);
      graphics.horizontalLine(x, x + width - 1, y, color);
      graphics.horizontalLine(x, x + width - 1, y + 11, color);
      graphics.verticalLine(x, y, y + 11, color);
      graphics.verticalLine(x + width - 1, y, y + 11, color);
      graphics.text(font, text, x + 4, y + 2, color);
   }

   public static int badgeWidth(Font font, String text) {
      return font.width(text) + 8;
   }

   public static boolean isOver(double mouseX, double mouseY, int x, int y, int width, int height) {
      return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
   }

   public static final int COLOR_CARD_BG = 0xF4111520;

   // =========================================================================
   // 2. Modernes Cyber-Tactical UI-Designsystem
   // =========================================================================

   /**
    * Zeichnet eine edle Glassmorphic-Karte mit dezentem Schatten, innerer Glanzkante
    * und harmonischem, homogenem Hintergrund ohne störende Kanten.
    */
   public static void glassCard(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1,
                                boolean hovered, int accentGlow) {
      // Äußerer Randschatten (1px Versatz)
      graphics.fill(x0 + 1, y1, x1 + 1, y1 + 2, 0x40000000);
      graphics.fill(x1, y0 + 1, x1 + 2, y1 + 1, 0x40000000);

      // Homogene Kartenfläche
      graphics.fill(x0, y0, x1, y1, COLOR_CARD_BG);

      // Innere Glanzkante (Top-Highlight)
      graphics.horizontalLine(x0 + 1, x1 - 2, y0 + 1, COLOR_LIGHT_EDGE);
      graphics.verticalLine(x0 + 1, y0 + 1, y1 - 2, 0x12FFFFFF);

      // Außenrahmen mit Glühkante bei Hover
      int borderColor = hovered ? (accentGlow != 0 ? accentGlow : COLOR_CARD_BORDER_HOVER) : COLOR_CARD_BORDER;
      graphics.horizontalLine(x0, x1 - 1, y0, borderColor);
      graphics.horizontalLine(x0, x1 - 1, y1 - 1, borderColor);
      graphics.verticalLine(x0, y0, y1 - 1, borderColor);
      graphics.verticalLine(x1 - 1, y0, y1 - 1, borderColor);
   }

   /**
    * Veredelter Cyber-Button mit Kanten-Highlight, Akzentstreifen und Klick-Feedback.
    */
   public static void cyberButton(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height,
                                  String label, boolean enabled, boolean hovered, int accent) {
      int bg = enabled
         ? (hovered ? 0xFF2A3245 : 0xFF1B2130)
         : 0xFF12151F;
      int border = enabled
         ? (hovered ? accent : 0xFF38435C)
         : 0xFF202636;
      int textColor = enabled
         ? (hovered ? 0xFFFFFFFF : COLOR_TEXT_WHITE)
         : COLOR_TEXT_DISABLED;

      // Button-Körper
      graphics.fill(x, y, x + width, y + height, bg);

      // Inneres Top-Highlight für plastischen Look
      if (enabled) {
         graphics.horizontalLine(x + 1, x + width - 2, y + 1, hovered ? 0x44FFFFFF : 0x22FFFFFF);
      }

      // Rahmen
      graphics.horizontalLine(x, x + width - 1, y, border);
      graphics.horizontalLine(x, x + width - 1, y + height - 1, border);
      graphics.verticalLine(x, y, y + height - 1, border);
      graphics.verticalLine(x + width - 1, y, y + height - 1, border);

      // Akzent-Indikatorstreifen links
      if (enabled) {
         int stripWidth = hovered ? 4 : 3;
         graphics.fill(x + 2, y + 2, x + 2 + stripWidth, y + height - 2, accent);
      }

      // Zentrierter Text
      int textY = y + (height - 8) / 2;
      graphics.centeredText(font, Component.literal(label), x + width / 2, textY, textColor);
   }

   /**
    * Taktischer Reiter / Tab mit aktiver Akzent-Unterstrichleiste und weichem Hover.
    */
   public static void tabHeader(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height,
                                String label, boolean active, boolean hovered, int accent) {
      int bg = active
         ? 0xDD1F273A
         : (hovered ? 0x99181F2E : 0x55111520);
      int textColor = active
         ? accent
         : (hovered ? COLOR_TEXT_WHITE : COLOR_TEXT_MUTED);

      graphics.fill(x, y, x + width, y + height, bg);

      // Rand oben & seitlich
      int border = active ? 0xFF425072 : (hovered ? 0xFF303A52 : 0xFF1E2536);
      graphics.horizontalLine(x, x + width - 1, y, border);
      graphics.verticalLine(x, y, y + height - 1, border);
      graphics.verticalLine(x + width - 1, y, y + height - 1, border);

      // Aktive Glühleiste unten
      if (active) {
         graphics.fill(x, y + height - 2, x + width, y + height, accent);
         graphics.fill(x + 1, y + height - 3, x + width - 1, y + height - 2, (accent & 0x00FFFFFF) | 0x88000000);
      } else {
         graphics.horizontalLine(x, x + width - 1, y + height - 1, border);
      }

      int textY = y + (height - 8) / 2;
      graphics.centeredText(font, Component.literal(label), x + width / 2, textY, textColor);
   }

   /**
    * Status-Badge / Chip mit Statuspunkt und synchron pulsierender, leuchtender Outline.
    */
   public static void statusBadge(GuiGraphicsExtractor graphics, Font font, int x, int y,
                                  String text, int color, boolean pulse) {
      int textWidth = font.width(text);
      int width = textWidth + 18;
      int height = 14;

      long time = System.currentTimeMillis();
      float wave = pulse
         ? (Mth.sin((float) (time % 1600L) / 1600.0F * (float) (Math.PI * 2.0)) + 1.0F) * 0.5F
         : 1.0F;

      // Abgerundete Pill-Form (mit 1px Eck-Ausschnitten)
      graphics.fill(x + 1, y, x + width - 1, y + height, 0xE80C0F17);
      graphics.fill(x, y + 1, x + width, y + height - 1, 0xE80C0F17);

      // Deutlich hellerer Rahmen, der synchron mit dem Punkt pulsiert
      int borderAlpha = pulse ? (int) (120 + wave * 135) : 210;
      int borderColor = (color & 0x00FFFFFF) | (borderAlpha << 24);

      graphics.horizontalLine(x + 1, x + width - 2, y, borderColor);
      graphics.horizontalLine(x + 1, x + width - 2, y + height - 1, borderColor);
      graphics.verticalLine(x, y + 1, y + height - 2, borderColor);
      graphics.verticalLine(x + width - 1, y + 1, y + height - 2, borderColor);

      // Subtiler innerer Glühschein bei Puls
      if (pulse) {
         int glowAlpha = (int) (15 + wave * 35);
         graphics.horizontalLine(x + 2, x + width - 3, y + 1, (color & 0x00FFFFFF) | (glowAlpha << 24));
      }

      // Status-Punkt (synchron pulsierend)
      int dotAlpha = pulse ? (int) (140 + wave * 115) : 255;
      int dotColor = (color & 0x00FFFFFF) | (dotAlpha << 24);
      graphics.fill(x + 4, y + 5, x + 8, y + 9, dotColor);

      // Text
      graphics.text(font, text, x + 12, y + 3, COLOR_TEXT_WHITE);
   }

   /**
    * Veredelter Schieberegler mit gefülltem Verlaufs-Track, Glüh-Schiebeknopf und Wertanzeige.
    */
   public static void modernSlider(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height,
                                   String label, String valueText, double ratio, boolean enabled,
                                   boolean hovered, boolean active, int accent) {
      // Beschriftung & Wert oben
      graphics.text(font, label, x, y - 11, enabled ? COLOR_TEXT_MUTED : COLOR_TEXT_DISABLED);
      graphics.text(font, valueText, x + width - font.width(valueText), y - 11, enabled ? accent : COLOR_TEXT_DISABLED);

      // Track-Abmessungen
      int trackHeight = 6;
      int trackY = y + (height - trackHeight) / 2;

      // Track-Hintergrund
      graphics.fill(x, trackY, x + width, trackY + trackHeight, 0xFF141926);
      int trackBorder = enabled ? 0xFF2A354C : 0xFF1C2230;
      graphics.horizontalLine(x, x + width - 1, trackY, trackBorder);
      graphics.horizontalLine(x, x + width - 1, trackY + trackHeight - 1, trackBorder);
      graphics.verticalLine(x, trackY, trackY + trackHeight - 1, trackBorder);
      graphics.verticalLine(x + width - 1, trackY, trackY + trackHeight - 1, trackBorder);

      // Fortschritts-Füllung
      int fillWidth = (int) (ratio * width);
      if (fillWidth > 0 && enabled) {
         graphics.fill(x + 1, trackY + 1, x + fillWidth, trackY + trackHeight - 1, (accent & 0x00FFFFFF) | 0x99000000);
         graphics.horizontalLine(x + 1, x + fillWidth - 1, trackY + 1, (accent & 0x00FFFFFF) | 0xDD000000);
      }

      // Schiebeknopf / Thumb
      if (enabled) {
         int thumbWidth = 12;
         int thumbHeight = 16;
         int thumbX = x + Math.max(0, Math.min(width - thumbWidth, (int) (ratio * (width - thumbWidth))));
         int thumbY = y + (height - thumbHeight) / 2;

         int thumbBg = active ? 0xFFFFFFFF : (hovered ? 0xFFE2E8F0 : 0xFF94A3B8);
         int thumbBorder = active ? accent : (hovered ? accent : 0xFF475569);

         graphics.fill(thumbX, thumbY, thumbX + thumbWidth, thumbY + thumbHeight, thumbBg);
         graphics.horizontalLine(thumbX, thumbX + thumbWidth - 1, thumbY, thumbBorder);
         graphics.horizontalLine(thumbX, thumbX + thumbWidth - 1, thumbY + thumbHeight - 1, thumbBorder);
         graphics.verticalLine(thumbX, thumbY, thumbY + thumbHeight - 1, thumbBorder);
         graphics.verticalLine(thumbX + thumbWidth - 1, thumbY, thumbY + thumbHeight - 1, thumbBorder);

         // Mittelrille auf dem Thumb
         graphics.fill(thumbX + thumbWidth / 2 - 1, thumbY + 3, thumbX + thumbWidth / 2 + 1, thumbY + thumbHeight - 3, 0x44000000);
      }
   }

   /**
    * Taktisches Such- / Eingabefeld mit Fokusrahmen, Icon und Platzhalter-Text.
    */
   public static void searchInput(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height,
                                  String query, String placeholder, boolean focused, boolean hovered) {
      int bg = focused ? 0xFF1B2334 : (hovered ? 0xFF161C2A : 0xFF10141E);
      int border = focused ? COLOR_CYAN : (hovered ? COLOR_CARD_BORDER_HOVER : COLOR_CARD_BORDER);

      graphics.fill(x, y, x + width, y + height, bg);
      graphics.horizontalLine(x, x + width - 1, y, border);
      graphics.horizontalLine(x, x + width - 1, y + height - 1, border);
      graphics.verticalLine(x, y, y + height - 1, border);
      graphics.verticalLine(x + width - 1, y, y + height - 1, border);

      // Such-Symbol "🔍"
      graphics.text(font, "🔍", x + 6, y + (height - 8) / 2, focused ? COLOR_CYAN : COLOR_TEXT_MUTED);

      // Eingegebener Text oder Platzhalter
      if (query.isEmpty()) {
         graphics.text(font, placeholder, x + 20, y + (height - 8) / 2, COLOR_TEXT_FAINT);
      } else {
         graphics.text(font, query, x + 20, y + (height - 8) / 2, COLOR_TEXT_WHITE);
         // Cursor
         if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            int cursorX = x + 20 + font.width(query) + 1;
            graphics.fill(cursorX, y + 4, cursorX + 1, y + height - 4, COLOR_CYAN);
         }
      }
   }

   /**
    * Zeichnet eine elegante Cyber-Scrollbar dezent am rechten Rand der Card.
    * Liegt standardmäßig außerhalb des Inhaltsbereichs, um Bedienelemente nicht zu überlagern.
    */
   public static void scrollbar(GuiGraphicsExtractor graphics, int trackX, int trackY, int trackHeight,
                                int contentLength, int visibleHeight, int scroll, int thumbColor) {
      int overflow = contentLength - visibleHeight;
      if (overflow <= 0) {
         return;
      }
      int barHeight = Math.max(22, visibleHeight * visibleHeight / Math.max(1, contentLength));
      int barY = trackY + (visibleHeight - barHeight) * scroll / overflow;

      // Track-Hintergrund (halbtransparent dunkel mit zarter Kante)
      graphics.fill(trackX, trackY, trackX + 4, trackY + trackHeight, 0x440B0E14);
      graphics.horizontalLine(trackX, trackX + 3, trackY, 0x22FFFFFF);
      graphics.horizontalLine(trackX, trackX + 3, trackY + trackHeight - 1, 0x22000000);

      // Thumb (Schieber)
      graphics.fill(trackX, barY, trackX + 4, barY + barHeight, thumbColor);
      graphics.fill(trackX + 1, barY + 1, trackX + 3, barY + barHeight - 1, (thumbColor & 0x00FFFFFF) | 0xDD000000);
   }

   // =========================================================================
   // 3. 3D-Welt-Projektionsmarker (für Item-Boxen und HUD-Markierungen)
   // =========================================================================

   public static final class WorldMarker {
      /**
       * Grenze im normierten Bildraum, jenseits derer nicht mehr gezeichnet wird.
       *
       * Nahe der Bildkante wachsen die projizierten Werte sehr schnell; die Schranke hält sie in
       * einem Bereich, in dem die Umrechnung auf Pixel nicht überläuft. Ein Punkt jenseits von
       * ±1 liegt ohnehin außerhalb des Bildes.
       */
      private static final double OFF_SCREEN_LIMIT = 2.0;

      private static final Projection OFF_SCREEN = new Projection(0, 0, false, 0.0);

      private WorldMarker() {
      }

      /** Bildschirmposition eines Weltpunkts; {@code onScreen} sagt, ob er wirklich im Bild liegt. */
      public record Projection(int x, int y, boolean onScreen, double distance) {
      }

      /**
       * Projiziert einen Weltpunkt auf das HUD.
       *
       * <p>Gerechnet wird über Vanilla selbst: {@code GameRenderer} erfüllt
       * {@code TrackedWaypoint.Projector} und benutzt die echte Kameramatrix. Eine eigene Rechnung
       * aus Sichtwinkeln und dem Options-Sichtfeld läge daneben, sobald das Sichtfeld verändert
       * ist (Sprint, gespannter Bogen, Effekte) oder die Kamera in der Verfolgeransicht steht.
       *
       * <p><b>Ob der Punkt vor der Kamera liegt, wird geometrisch entschieden und nicht an der
       * projizierten Tiefe.</b> Vanilla prüft in {@code TrackedWaypoint.Vec3iWaypoint} auf
       * {@code z > 1.0}, aber {@code Projection#getMatrix} baut die Matrix mit vertauschtem
       * Near und Far – die Tiefe läuft also andersherum, und der Test greift hier nicht. Ein
       * Punkt genau hinter der Kamera kommt aus der perspektivischen Division bei x≈0/y≈0 heraus
       * und säße damit scheinbar mitten im Bild. Das Skalarprodukt mit der Blickrichtung ist von
       * dieser Frage unabhängig und entscheidet sie eindeutig.
       */
      public static Projection project(GuiGraphicsExtractor graphics, LocalPlayer player, Vec3 target,
                                       float partialTick, int edgeMargin) {
         Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
         Vec3 cameraPos = camera.position();
         Vector3fc forward = camera.forwardVector();
         double aheadOfCamera = (target.x - cameraPos.x) * forward.x()
            + (target.y - cameraPos.y) * forward.y()
            + (target.z - cameraPos.z) * forward.z();
         if (aheadOfCamera <= 0.0) {
            return OFF_SCREEN;
         }

         Vec3 onScreen = Minecraft.getInstance().gameRenderer.projectPointToScreen(target);
         if (Math.abs(onScreen.x) > OFF_SCREEN_LIMIT || Math.abs(onScreen.y) > OFF_SCREEN_LIMIT) {
            return OFF_SCREEN;
         }

         int width = graphics.guiWidth();
         int height = graphics.guiHeight();
         int centerX = width / 2;
         int centerY = height / 2;
         int x = centerX + (int) Math.round(onScreen.x * centerX);
         // Der Bildraum zeigt nach oben, das HUD nach unten.
         int y = centerY - (int) Math.round(onScreen.y * centerY);
         if (x < edgeMargin || x > width - edgeMargin || y < edgeMargin || y > height - edgeMargin) {
            return OFF_SCREEN;
         }

         return new Projection(x, y, true, target.distanceTo(player.getEyePosition(partialTick)));
      }
   }
}
