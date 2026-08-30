"""Erzeugt das Mod-Banner unter assets/oneshotonekill/banner.png.

    python tools/generate_mod_banner.py

Erzeugt ein hochauflösendes 512x128 Cyber-Tactical Banner mit Fadenkreuz-Symbolik,
ausgewogener Typografie und taktischen HUD-Details.
"""

from __future__ import annotations

import pathlib
import sys

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from pixelart import ramp, rgb  # noqa: E402

WIDTH = 512
HEIGHT = 128
OUTPUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/assets/oneshotonekill/banner.png"

STEEL = ramp(0x9AA4B2, 6, 0.40, 1.25)
DARK = ramp(0x11141A, 5, 0.55, 1.45)
GOLD = ramp(0xE8A93A, 6, 0.42, 1.30)
CYAN = ramp(0x00E5FF, 5, 0.50, 1.20)
RED = rgb(0xE04A2E)

# 5x7 Pixel Font Definition für Retro-Taktik-Schrift
FONT_5X7: dict[str, list[str]] = {
    'O': [" ### ", "#   #", "#   #", "#   #", "#   #", "#   #", " ### "],
    'N': ["#   #", "##  #", "# # #", "#  ##", "#   #", "#   #", "#   #"],
    'E': ["#####", "#    ", "#    ", "#### ", "#    ", "#    ", "#####"],
    'S': [" ####", "#    ", "#    ", " ### ", "    #", "    #", "#### "],
    'H': ["#   #", "#   #", "#   #", "#####", "#   #", "#   #", "#   #"],
    'T': ["#####", "  #  ", "  #  ", "  #  ", "  #  ", "  #  ", "  #  "],
    'K': ["#   #", "#  # ", "# #  ", "##   ", "# #  ", "#  # ", "#   #"],
    'I': ["#####", "  #  ", "  #  ", "  #  ", "  #  ", "  #  ", "#####"],
    'L': ["#    ", "#    ", "#    ", "#    ", "#    ", "#    ", "#####"],
    ' ': ["     ", "     ", "     ", "     ", "     ", "     ", "     "],
    '-': ["     ", "     ", "     ", " ### ", "     ", "     ", "     "],
    '1': ["  #  ", " ##  ", "  #  ", "  #  ", "  #  ", "  #  ", " ### "],
    'P': ["#### ", "#   #", "#   #", "#### ", "#    ", "#    ", "#    "],
    'V': ["#   #", "#   #", "#   #", "#   #", "#   #", " # # ", "  #  "],
    'A': [" ### ", "#   #", "#   #", "#####", "#   #", "#   #", "#   #"],
    'C': [" ####", "#    ", "#    ", "#    ", "#    ", "#    ", " ####"],
    'M': ["#   #", "## ##", "# # #", "#   #", "#   #", "#   #", "#   #"],
    'G': [" ####", "#    ", "#    ", "# ###", "#   #", "#   #", " ####"],
    'R': ["#### ", "#   #", "#   #", "#### ", "# #  ", "#  # ", "#   #"],
    'U': ["#   #", "#   #", "#   #", "#   #", "#   #", "#   #", " ### "],
    'D': ["#### ", "#   #", "#   #", "#   #", "#   #", "#   #", "#### "],
}


def draw_pixel_text(
    draw: ImageDraw.ImageDraw,
    text: str,
    x: int,
    y: int,
    scale: int = 4,
    spacing: int = 1,
    fill_color: tuple[int, int, int, int] = (232, 169, 58, 255),
    shadow_color: tuple[int, int, int, int] | None = (0, 0, 0, 180),
) -> int:
    cur_x = x
    for char in text.upper():
        glyph = FONT_5X7.get(char, FONT_5X7[' '])
        for row_idx, row in enumerate(glyph):
            for col_idx, pixel in enumerate(row):
                if pixel == '#':
                    px = cur_x + col_idx * scale
                    py = y + row_idx * scale
                    if shadow_color:
                        draw.rectangle([px + scale, py + scale, px + 2 * scale - 1, py + 2 * scale - 1], fill=shadow_color)
                    draw.rectangle([px, py, px + scale - 1, py + scale - 1], fill=fill_color)
        cur_x += (5 + spacing) * scale
    return cur_x


def main() -> int:
    img = Image.new("RGBA", (WIDTH, HEIGHT), (14, 17, 23, 255))
    draw = ImageDraw.Draw(img)

    # Subtiler Hintergrund-Farbverlauf
    for y in range(HEIGHT):
        factor = 1.0 - (y / HEIGHT) * 0.40
        base_col = (int(18 * factor), int(22 * factor), int(30 * factor), 255)
        draw.line([(0, y), (WIDTH, y)], fill=base_col)

    # Taktisches Cyber-Gitter im Hintergrund
    grid_color = (255, 255, 255, 7)
    for x in range(0, WIDTH, 16):
        draw.line([(x, 0), (x, HEIGHT)], fill=grid_color)
    for y in range(0, HEIGHT, 16):
        draw.line([(0, y), (WIDTH, y)], fill=grid_color)

    # Äußere Gold- und Stahl-Bordüren
    draw.rectangle([0, 0, WIDTH - 1, HEIGHT - 1], outline=(232, 169, 58, 220), width=2)
    draw.rectangle([4, 4, WIDTH - 5, HEIGHT - 5], outline=(154, 164, 178, 50), width=1)

    # Diagonale Taktik-Ecken (Cyber-Schnitt)
    corner_gold = (232, 169, 58, 255)
    for cx, cy in [(0, 0), (WIDTH, 0), (0, HEIGHT), (WIDTH, HEIGHT)]:
        dx = -16 if cx > 0 else 16
        dy = -16 if cy > 0 else 16
        draw.line([(cx, cy + dy), (cx + dx, cy)], fill=corner_gold, width=2)

    # Linke Seite: Großes leuchtendes Fadenkreuz-Icon (Center at x=64, y=64)
    ix, iy = 64, 64
    for r in range(46, 10, -4):
        alpha = int(35 * (1.0 - r / 46))
        draw.ellipse([ix - r, iy - r, ix + r, iy + r], fill=(232, 169, 58, alpha))

    draw.ellipse([ix - 34, iy - 34, ix + 34, iy + 34], outline=(232, 169, 58, 230), width=3)
    draw.ellipse([ix - 22, iy - 22, ix + 22, iy + 22], outline=(154, 164, 178, 180), width=2)
    draw.ellipse([ix - 10, iy - 10, ix + 10, iy + 10], outline=(0, 229, 255, 200), width=2)

    for x0, x1 in ((ix - 42, ix - 14), (ix + 14, ix + 42)):
        draw.line([(x0, iy), (x1, iy)], fill=(232, 169, 58, 255), width=3)
    for y0, y1 in ((iy - 42, iy - 14), (iy + 14, iy + 42)):
        draw.line([(ix, y0), (ix, y1)], fill=(232, 169, 58, 255), width=3)

    draw.ellipse([ix - 4, iy - 4, ix + 4, iy + 4], fill=(224, 74, 46, 255))
    draw.ellipse([ix - 2, iy - 2, ix + 2, iy + 2], fill=(255, 226, 210, 255))

    # Mittlere Taktik-Linie (Trenner)
    draw.line([(124, 20), (124, HEIGHT - 20)], fill=(232, 169, 58, 140), width=2)
    draw.line([(128, 28), (128, HEIGHT - 28)], fill=(0, 229, 255, 70), width=1)

    # Typografie / Logo im Hauptfeld
    # Zeile 1: "ONESHOT ONEKILL"
    draw_pixel_text(draw, "ONESHOT ONEKILL", x=142, y=22, scale=4, spacing=1, fill_color=(232, 169, 58, 255), shadow_color=(0, 0, 0, 220))

    # Zeile 2: Subtitle "TACTICAL 1-HIT PVP"
    draw_pixel_text(draw, "TACTICAL 1-HIT PVP", x=142, y=62, scale=2, spacing=1, fill_color=(0, 229, 255, 230), shadow_color=(0, 0, 0, 180))

    # Zeile 3: Taktische Features Bar
    draw_pixel_text(draw, "GUN GAME - 17 ITEMS - ARENA ROLLBACK", x=142, y=86, scale=1, spacing=1, fill_color=(154, 164, 178, 220), shadow_color=(0, 0, 0, 150))

    # Status-Dioden & Deko rechts unten
    draw.rectangle([142, 106, 480, 108], fill=(232, 169, 58, 80))
    for i, col in enumerate([(0, 229, 255, 255), (232, 169, 58, 255), (224, 74, 46, 255), (0, 255, 128, 255)]):
        draw.rectangle([142 + i * 16, 104, 150 + i * 16, 110], fill=col)

    # Rechtes Taktik-Zielkreuz klein bei x=476, y=64
    rx, ry = 476, 64
    draw.ellipse([rx - 16, ry - 16, rx + 16, ry + 16], outline=(232, 169, 58, 140), width=1)
    draw.line([(rx - 22, ry), (rx + 22, ry)], fill=(0, 229, 255, 160), width=1)
    draw.line([(rx, ry - 22), (rx, ry + 22)], fill=(0, 229, 255, 160), width=1)
    draw.ellipse([rx - 3, ry - 3, rx + 3, ry + 3], fill=(232, 169, 58, 220))

    # Ecknieten / Markierungen
    for nx in (10, WIDTH - 10):
        for ny in (10, HEIGHT - 10):
            draw.rectangle([nx - 2, ny - 2, nx + 2, ny + 2], fill=(232, 169, 58, 240))

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUTPUT, "PNG")
    print(f"Banner: {OUTPUT} ({OUTPUT.stat().st_size} B, {WIDTH}x{HEIGHT})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
