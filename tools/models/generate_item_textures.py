"""Erzeugt alle Item-Texturen der Mod als PNG.

Aufruf aus dem Projektwurzelverzeichnis:

    python tools/generate_item_textures.py

Gezeichnet wird auf einem 64x64-Raster – viermal Vanilla-Auflösung. Das ist
bewusst kein hochskaliertes 16x16: jeder Pixel ist gesetzt, die zusätzliche
Auflösung geht komplett in Details (Nieten, Kühlrippen, Displays, Verläufe per
Dithering). Die Dateien bleiben dabei bei wenigen Kilobyte.

Einheitlicher Stil für alle Items:
  * Lichtquelle oben links, harte Kontur außen herum
  * Tonwertrampen statt weicher Verläufe, Übergänge per Schachbrett-Dither
  * leuchtende Bauteile bekommen einen Lichtschein
"""

from __future__ import annotations

import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from pixelart import RGBA, Canvas, mix, ramp, rgb, shade  # noqa: E402

SIZE = 64
OUTPUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/assets/oneshotonekill/textures/item"

# Gemeinsame Materialpaletten -------------------------------------------------
GUNMETAL = ramp(0x4A525C, 6, 0.35, 1.30)
STEEL = ramp(0x9AA4B2, 6, 0.40, 1.25)
DARK_STEEL = ramp(0x2C323A, 5, 0.50, 1.45)
BRASS = ramp(0xC79A3A, 6, 0.42, 1.28)
RUBBER = ramp(0x23262B, 5, 0.55, 1.50)
WARN_RED = ramp(0xC8402C, 6, 0.40, 1.30)
ICE = ramp(0x8FD3E8, 6, 0.42, 1.25)
VOID = ramp(0x2A1B44, 6, 0.40, 1.40)
OLIVE = ramp(0x5C6440, 6, 0.42, 1.28)

OUTLINE = rgb(0x0B0D10)
GLASS_HI = rgb(0xFFFFFF, 150)


def finish(canvas: Canvas, outline_color=OUTLINE, diagonal: bool = False) -> Canvas:
    canvas.outline(outline_color, diagonal=diagonal)
    return canvas


# ---------------------------------------------------------------------------
# 1 – Minigun: Laufbündel, Trommelmagazin, Griff
# ---------------------------------------------------------------------------

def draw_minigun(c: Canvas) -> None:
    """Seitenansicht: Gehäuse links, fünf klar getrennte Läufe nach rechts, Trommel darunter."""
    hot = rgb(0xFFB43A)

    # Fünf Läufe. Jeder bekommt Glanzkante oben und Schattenkante unten, dazwischen eine
    # ganze Pixelzeile Fuge – ohne die verschmilzt das Bündel zu einem grauen Block.
    for index in range(5):
        top = 20 + index * 5
        c.rect(27, top, 59, top + 3, GUNMETAL[3])
        c.rect(27, top, 59, top, STEEL[2])
        c.rect(27, top + 3, 59, top + 3, DARK_STEEL[0])
        c.rect(56, top, 59, top + 3, DARK_STEEL[2])
        c.rect(58, top + 1, 59, top + 2, rgb(0x0A0C0F))

    # Laufhalterungen
    for x in (33, 46):
        c.rect(x, 17, x + 3, 45, STEEL[3])
        c.rect(x, 17, x, 45, STEEL[5])
        c.rect(x + 3, 17, x + 3, 45, DARK_STEEL[0])
        for y in (19, 43):
            c.px(x + 1, y, STEEL[5])

    # Gehäuse
    c.rect(8, 17, 27, 45, GUNMETAL[3])
    c.vgrad(8, 17, 27, 45, GUNMETAL[5], GUNMETAL[1])
    c.frame(8, 17, 27, 45, DARK_STEEL[0])
    for x in range(11, 26, 3):
        c.rect(x, 20, x, 28, DARK_STEEL[2])
        c.rect(x + 1, 20, x + 1, 28, GUNMETAL[5])
    c.rect(10, 34, 25, 36, shade(hot, 0.6))
    c.rect(10, 35, 25, 35, hot)
    c.glow(17, 35, 11, rgb(0xFF8A1E, 95))

    # Trommelmagazin, klar unterhalb des Gehäuses statt dahinter
    c.rect(12, 45, 18, 49, GUNMETAL[2])
    c.disc(14, 55, 7, BRASS[2])
    c.ring(14, 55, 7, BRASS[4], 2)
    c.ring(14, 55, 4, BRASS[0], 1)
    c.disc(14, 55, 2, DARK_STEEL[1])

    # Griff rechts daneben, damit sich Trommel und Griff nicht überlagern
    c.polygon([(24, 45), (33, 45), (30, 62), (21, 62)], RUBBER[2])
    c.polygon([(24, 45), (27, 45), (24, 62), (21, 62)], RUBBER[4])
    c.rect(24, 47, 30, 48, DARK_STEEL[3])

    c.sparkle(60, 32, rgb(0xFFE9B0, 200), 3)


# ---------------------------------------------------------------------------
# 2 – Air-Strike: Zielfunkgerät mit Kartenschirm
# ---------------------------------------------------------------------------

def draw_airstrike(c: Canvas) -> None:
    screen = rgb(0x1E2A20)
    scan = rgb(0x8FD36A)

    # Gehäuse
    c.rect(10, 8, 53, 56, OLIVE[2])
    c.vgrad(10, 8, 53, 56, OLIVE[4], OLIVE[1])
    c.frame(10, 8, 53, 56, DARK_STEEL[1])
    c.noise(11, 9, 52, 55, OLIVE[1], 11, 3)

    # Ecknieten
    for nx, ny in ((13, 11), (50, 11), (13, 53), (50, 53)):
        c.disc(nx, ny, 1.6, STEEL[4])
        c.px(nx, ny - 1, STEEL[5])

    # Bildschirm
    c.rect(15, 14, 48, 40, DARK_STEEL[0])
    c.rect(16, 15, 47, 39, screen)
    for y in range(16, 39, 3):
        c.rect(16, y, 47, y, shade(screen, 0.82))
    # Raster
    for x in range(16, 48, 8):
        c.rect(x, 15, x, 39, rgb(0x3E5C3A))
    for y in range(15, 40, 6):
        c.rect(16, y, 47, y, rgb(0x3E5C3A))
    # Sweep und Ziel
    c.line(31, 27, 46, 17, scan, 1)
    c.line(31, 27, 44, 20, shade(scan, 0.7), 1)
    c.ring(31, 27, 9, rgb(0x6FAE52), 1)
    c.ring(31, 27, 5, rgb(0x6FAE52), 1)
    c.rect(35, 22, 37, 24, rgb(0xE8583C))
    c.glow(36, 23, 5, rgb(0xE8583C, 110))
    c.rect(16, 15, 47, 16, GLASS_HI)

    # Bedienleiste
    c.rect(15, 43, 48, 53, DARK_STEEL[1])
    for index in range(4):
        x = 18 + index * 8
        c.rect(x, 45, x + 4, 49, STEEL[2] if index else WARN_RED[3])
        c.rect(x, 45, x + 4, 45, STEEL[4] if index else WARN_RED[5])
    c.rect(18, 51, 45, 52, GUNMETAL[1])

    # Antenne
    c.rect(44, 2, 46, 9, STEEL[3])
    c.rect(44, 2, 44, 9, STEEL[5])
    c.disc(45, 2, 2.2, WARN_RED[4])
    c.glow(45, 2, 5, rgb(0xE8583C, 100))


# ---------------------------------------------------------------------------
# 3 – Radar-Puls: Sonarschale mit Ringen
# ---------------------------------------------------------------------------

def draw_radar_pulse(c: Canvas) -> None:
    lens = rgb(0x0E2418)
    beam = rgb(0x63E08A)

    c.disc(32, 32, 27, DARK_STEEL[1])
    c.disc(32, 32, 25, STEEL[2])
    c.ring(32, 32, 25, STEEL[4], 2)
    c.ring(32, 32, 21, DARK_STEEL[2], 2)
    c.disc(32, 32, 20, lens)

    # Konzentrische Impulsringe
    for radius, alpha in ((18, 230), (13, 180), (8, 130)):
        c.ring(32, 32, radius, (beam[0], beam[1], beam[2], alpha), 1)
    # Sweep-Keil
    c.polygon([(32, 32), (50, 20), (52, 26)], (beam[0], beam[1], beam[2], 90))
    c.line(32, 32, 50, 20, beam, 1)

    # Zielkreuz
    c.rect(31, 12, 32, 52, (beam[0], beam[1], beam[2], 70))
    c.rect(12, 31, 52, 32, (beam[0], beam[1], beam[2], 70))
    c.disc(32, 32, 3, beam)
    c.glow(32, 32, 14, rgb(0x3FBF6A, 70))

    # Gehäusenieten
    for angle_index in range(8):
        import math

        angle = angle_index * math.pi / 4
        nx = round(32 + math.cos(angle) * 23)
        ny = round(32 + math.sin(angle) * 23)
        c.disc(nx, ny, 1.6, STEEL[5])
        c.px(nx, ny, STEEL[1])
    c.sparkle(22, 22, GLASS_HI, 2)


# ---------------------------------------------------------------------------
# 4 – Explosiv-Schuss: Pfeil mit Sprengkopf
# ---------------------------------------------------------------------------

def draw_explosive_shot(c: Canvas) -> None:
    """Senkrechter Pfeil mit rotem Sprengkopf – aufrecht ist die Form am eindeutigsten."""
    wood = ramp(0x8A5A32, 5, 0.45, 1.25)
    fuse = rgb(0x4A3A28)

    # Schaft
    c.rect(29, 20, 34, 56, wood[2])
    c.rect(29, 20, 30, 56, wood[4])
    c.rect(33, 20, 34, 56, wood[0])

    # Befiederung: zwei geschlossene Fahnen statt einzelner Rippen
    c.polygon([(29, 40), (19, 47), (19, 57), (29, 54)], WARN_RED[3])
    c.polygon([(34, 40), (44, 47), (44, 57), (34, 54)], WARN_RED[2])
    c.line(29, 40, 19, 47, WARN_RED[5], 1)
    c.line(34, 40, 44, 47, WARN_RED[4], 1)
    # Feine Kerben in den Fahnen
    for index in range(1, 4):
        y = 42 + index * 4
        c.line(29, y, 20, y + 4, shade(WARN_RED[1], 0.85), 1)
        c.line(34, y, 43, y + 4, shade(WARN_RED[0], 0.85), 1)
    c.rect(29, 54, 34, 59, DARK_STEEL[2])
    c.rect(29, 54, 30, 59, DARK_STEEL[4])

    # Sprengkopf: klar roter Zylinder mit dunklen Bändern
    c.rect(20, 12, 43, 34, WARN_RED[2])
    c.hgrad(20, 12, 43, 34, WARN_RED[4], WARN_RED[1])
    c.frame(20, 12, 43, 34, DARK_STEEL[0])
    for y in (17, 29):
        c.rect(20, y, 43, y + 2, DARK_STEEL[2])
        c.rect(20, y, 43, y, DARK_STEEL[4])
    # Gefahrgutdreieck auf dem Mantel
    c.polygon([(31, 20), (38, 28), (24, 28)], rgb(0xF2C14A))
    c.polygon([(31, 23), (35, 27), (27, 27)], WARN_RED[0])
    c.rect(31, 24, 31, 25, rgb(0xF2C14A))

    # Pfeilspitze
    c.polygon([(31, 1), (44, 14), (19, 14)], STEEL[3])
    c.polygon([(31, 1), (38, 9), (31, 12), (24, 9)], STEEL[5])
    c.line(31, 1, 19, 14, STEEL[5], 1)

    # Brennende Zündschnur seitlich am Kopf
    c.line(43, 20, 51, 14, fuse, 2)
    c.line(51, 14, 55, 9, fuse, 2)
    c.disc(56, 7, 2.4, rgb(0xFFD86B))
    c.glow(56, 7, 9, rgb(0xFF9A2E, 150))
    c.sparkle(56, 7, rgb(0xFFF3C8, 235), 3)
    c.glow(31, 23, 16, rgb(0xFF5A2E, 55))


# ---------------------------------------------------------------------------
# 5 – Reflektor-Schild: Hexagonales Energiefeld
# ---------------------------------------------------------------------------

def hexagon(cx: float, cy: float, radius: float) -> list[tuple[int, int]]:
    """Sechseck mit Spitze oben – als konzentrische Ringe stapelbar."""
    import math

    return [
        (round(cx + math.sin(index * math.pi / 3) * radius),
         round(cy - math.cos(index * math.pi / 3) * radius))
        for index in range(6)
    ]


def draw_reflector_shield(c: Canvas) -> None:
    """Konzentrisch von außen nach innen gefüllt – so kann keine Lage über die Silhouette treten."""
    field = rgb(0x4FD8E8)
    deep = rgb(0x123A46)

    # Jede Lage überzeichnet die vorherige vollständig; ohne aufgesetzte Rahmenlinien.
    c.polygon(hexagon(32, 32, 30), DARK_STEEL[0])
    c.polygon(hexagon(32, 32, 28), STEEL[4])
    c.polygon(hexagon(32, 32, 25), STEEL[1])
    c.polygon(hexagon(32, 32, 23), deep)
    c.polygon(hexagon(32, 32, 21), rgb(0x11313C))

    # Wabenmuster nur im Innenfeld
    for row in range(16, 46, 6):
        for col in range(14, 50, 8):
            offset = 4 if (row // 6) % 2 else 0
            c.frame(col + offset, row, col + offset + 5, row + 4, (field[0], field[1], field[2], 45))

    # Energiekante als eigene, schmalere Lage – bleibt damit innen
    inner = hexagon(32, 32, 21)
    for index in range(len(inner)):
        ax, ay = inner[index]
        bx, by = inner[(index + 1) % len(inner)]
        c.line(ax, ay, bx, by, field, 1)

    # Nieten auf dem Metallrahmen
    for nx, ny in hexagon(32, 32, 26):
        c.disc(nx, ny, 1.6, STEEL[5])
        c.px(nx, ny, STEEL[1])

    # Kern
    for dx, dy in ((0, -13), (11, -6), (11, 6), (0, 13), (-11, 6), (-11, -6)):
        c.line(32, 32, 32 + dx, 32 + dy, (field[0], field[1], field[2], 110), 1)
    c.disc(32, 32, 8, deep)
    c.ring(32, 32, 8, field, 2)
    c.disc(32, 32, 4, rgb(0xBFF4FA))
    c.disc(32, 32, 2, rgb(0xFFFFFF))
    c.glow(32, 32, 18, rgb(0x4FD8E8, 65))
    c.sparkle(24, 20, GLASS_HI, 2)


# ---------------------------------------------------------------------------
# 6 – Rauchbombe: Kanister mit Abzugsring
# ---------------------------------------------------------------------------

def draw_smoke_bomb(c: Canvas) -> None:
    body = ramp(0x6B7078, 6, 0.42, 1.28)
    smoke = rgb(0xCBD2D8)

    # Rauchschwaden hinter dem Körper
    for cx, cy, r, a in ((16, 14, 7, 90), (24, 8, 6, 70), (46, 12, 6, 80), (52, 20, 5, 60)):
        c.disc(cx, cy, r, (smoke[0], smoke[1], smoke[2], a))

    # Zylinder
    c.rect(21, 20, 43, 57, body[2])
    c.hgrad(21, 20, 43, 57, body[4], body[1])
    c.ellipse(32, 20, 11, 4, body[5])
    c.ellipse(32, 57, 11, 4, body[1])
    c.frame(21, 22, 43, 55, DARK_STEEL[2])
    c.dither(22, 40, 42, 56, body[1], parity=1)

    # Beschriftungsbänder
    c.rect(21, 30, 43, 34, WARN_RED[3])
    c.rect(21, 30, 43, 30, WARN_RED[5])
    c.rect(21, 44, 43, 46, rgb(0xE8D9A8))
    for x in range(23, 42, 4):
        c.rect(x, 31, x + 1, 33, WARN_RED[1])

    # Kopfstück
    c.rect(25, 12, 39, 21, STEEL[2])
    c.rect(25, 12, 39, 13, STEEL[5])
    c.frame(25, 12, 39, 21, DARK_STEEL[1])
    for x in range(27, 39, 3):
        c.rect(x, 14, x, 20, DARK_STEEL[2])

    # Abzugsring
    c.ring(45, 8, 7, BRASS[4], 2)
    c.ring(45, 8, 7, BRASS[2], 1)
    c.line(39, 12, 36, 15, BRASS[3], 2)
    c.sparkle(27, 24, GLASS_HI, 2)


# ---------------------------------------------------------------------------
# 7 – Frost-Trap: Platte mit Eisdornen
# ---------------------------------------------------------------------------

def draw_frost_trap(c: Canvas) -> None:
    plate = ramp(0x59626E, 6, 0.42, 1.30)

    # Grundplatte in Aufsicht
    c.ellipse(32, 40, 27, 15, plate[1])
    c.ellipse(32, 37, 27, 15, plate[3])
    c.ellipse(32, 36, 23, 12, plate[4])
    c.ellipse(32, 36, 18, 9, plate[2])
    c.dither(10, 30, 54, 46, plate[1], parity=0)

    # Zähne am Rand
    import math

    for index in range(12):
        angle = index * math.pi / 6
        px = 32 + math.cos(angle) * 24
        py = 36 + math.sin(angle) * 13
        tip_x = 32 + math.cos(angle) * 30
        tip_y = 36 + math.sin(angle) * 17
        c.polygon(
            [(round(px - 2), round(py)), (round(px + 2), round(py)), (round(tip_x), round(tip_y))],
            ICE[4],
        )
        c.line(round(px), round(py), round(tip_x), round(tip_y), ICE[5], 1)

    # Eisfläche
    c.ellipse(32, 35, 16, 8, (ICE[3][0], ICE[3][1], ICE[3][2], 210))
    c.ellipse(30, 33, 9, 4, (ICE[5][0], ICE[5][1], ICE[5][2], 190))

    # Frostkristall in der Mitte
    for dx, dy in ((0, -11), (9, -6), (9, 6), (0, 11), (-9, 6), (-9, -6)):
        c.line(32, 35, 32 + dx, 35 + dy, ICE[5], 1)
        c.line(32 + dx // 2, 35 + dy // 2, 32 + dx // 2 + (dy // 4), 35 + dy // 2 - (dx // 4), ICE[4], 1)
    c.disc(32, 35, 3, rgb(0xE8FAFF))
    c.glow(32, 35, 16, rgb(0x7FD8F0, 75))
    c.sparkle(20, 28, rgb(0xFFFFFF, 220), 2)
    c.sparkle(45, 42, rgb(0xDFF6FF, 180), 2)


# ---------------------------------------------------------------------------
# 8 – Teleport-Granate
# ---------------------------------------------------------------------------

def draw_teleport_grenade(c: Canvas) -> None:
    shell = ramp(0x3E3358, 6, 0.42, 1.32)
    warp = rgb(0xB56BE8)

    c.disc(32, 38, 21, shell[2])
    c.ellipse(28, 32, 15, 13, shell[4])
    c.ring(32, 38, 21, DARK_STEEL[1], 2)
    c.dither(14, 40, 52, 58, shell[1], parity=1)

    # Segmentrillen
    import math

    for index in range(6):
        angle = index * math.pi / 6
        c.line(
            round(32 - math.cos(angle) * 20), round(38 - math.sin(angle) * 20),
            round(32 + math.cos(angle) * 20), round(38 + math.sin(angle) * 20),
            shell[0], 1,
        )

    # Ender-Strudel
    for arm in range(3):
        base = arm * 2.09
        points = []
        for step in range(9):
            t = step / 8.0
            angle = base + t * 3.1
            radius = 3 + t * 13
            points.append((round(32 + math.cos(angle) * radius), round(38 + math.sin(angle) * radius)))
        for index in range(len(points) - 1):
            fade = 240 - index * 22
            c.line(*points[index], *points[index + 1], (warp[0], warp[1], warp[2], fade), 2)

    c.disc(32, 38, 5, rgb(0x1A0F2E))
    c.disc(32, 38, 3, rgb(0xE0B6FF))
    c.glow(32, 38, 18, rgb(0x9B4FE0, 85))

    # Zünder mit Bügel
    c.rect(27, 12, 37, 20, STEEL[3])
    c.rect(27, 12, 37, 13, STEEL[5])
    c.frame(27, 12, 37, 20, DARK_STEEL[1])
    c.ring(44, 10, 6, BRASS[4], 2)
    c.line(38, 14, 40, 12, BRASS[3], 2)
    c.sparkle(22, 26, GLASS_HI, 2)


# ---------------------------------------------------------------------------
# 9 – Unsichtbarkeits-Mantel
# ---------------------------------------------------------------------------

def draw_invisibility_cloak(c: Canvas) -> None:
    cloth = ramp(0x515A73, 6, 0.38, 1.32)

    # Schulterpartie und Umhang, nach unten hin durchsichtig werdend
    for y in range(26, 60):
        t = (y - 26) / 33.0
        fade = max(0, round(255 - t * 235))
        width = round(13 + t * 15)
        tone = mix(cloth[3], cloth[1], t)
        c.rect(32 - width, y, 32 + width, y, (tone[0], tone[1], tone[2], fade))
        # Innenschatten unter der Kapuze
        if y < 44:
            inner = max(0, round(200 - t * 220))
            c.rect(32 - width // 3, y, 32 + width // 3, y, (18, 20, 30, inner))

    # Kapuze als Spitzbogen
    c.polygon([(32, 3), (48, 22), (44, 34), (20, 34), (16, 22)], cloth[2])
    c.polygon([(32, 3), (42, 20), (32, 26), (22, 20)], cloth[4])
    c.polygon([(32, 8), (44, 24), (40, 33), (24, 33), (20, 24)], cloth[1])

    # Gesichtsöffnung – die leere Dunkelheit macht das Item lesbar
    c.ellipse(32, 25, 9, 9, rgb(0x080A11))
    c.ellipse(32, 23, 7, 6, rgb(0x0E1220))
    # Zwei schwach glimmende Augen
    c.disc(28, 24, 1.6, rgb(0x9FD8F0, 220))
    c.disc(36, 24, 1.6, rgb(0x9FD8F0, 220))
    c.glow(32, 24, 10, rgb(0x5AA8E0, 55))

    # Kapuzenkante und Falten
    for index in range(len(((32, 3), (48, 22), (44, 34), (20, 34), (16, 22))) - 1):
        pass
    c.line(32, 3, 48, 22, cloth[5], 1)
    c.line(32, 3, 16, 22, cloth[5], 1)
    for x, drop in ((21, 36), (26, 38), (38, 38), (43, 36)):
        for y in range(drop, 56):
            fade = max(0, 190 - (y - drop) * 9)
            c.px(x + (y - drop) // 7, y, (cloth[0][0], cloth[0][1], cloth[0][2], fade))

    # Kragenschließe
    c.rect(26, 33, 38, 37, cloth[1])
    c.rect(26, 33, 38, 33, cloth[4])
    c.disc(32, 35, 4, BRASS[3])
    c.disc(32, 35, 2, rgb(0xF0E0A8))
    c.glow(32, 35, 9, rgb(0xC79A3A, 70))

    # Auflösende Stofffetzen – die Tarnung greift
    for x, y in ((17, 42), (47, 44), (23, 52), (41, 50), (32, 56)):
        c.disc(x, y, 2.2, (200, 220, 255, 60))
        c.sparkle(x, y, (255, 255, 255, 130), 2)


# ---------------------------------------------------------------------------
# 10 – Pfeil-Magnetfeld
# ---------------------------------------------------------------------------

def draw_arrow_magnet(c: Canvas) -> None:
    red = ramp(0xE34558, 5, 0.45, 1.28)
    blue = ramp(0x347DFF, 5, 0.45, 1.30)
    field = rgb(0x83E8FF)

    # Kompakter Feldgenerator statt eines gewöhnlichen Hufeisenmagneten.
    c.glow(32, 34, 27, rgb(0x557CFF, 62))
    for radius, alpha in ((27, 85), (23, 120), (18, 155)):
        c.ring(32, 34, radius, (100, 210, 255, alpha), 1)

    housing = [(32, 12), (49, 22), (53, 40), (43, 54), (21, 54), (11, 40), (15, 22)]
    c.polygon(housing, DARK_STEEL[1])
    c.polygon([(32, 15), (46, 24), (49, 39), (40, 50), (24, 50), (15, 39), (18, 24)], GUNMETAL[3])
    c.frame(18, 26, 46, 44, STEEL[1])

    # Gegenpole mit leuchtender Feldlinse in der Mitte.
    c.rect(18, 27, 27, 43, red[2])
    c.rect(18, 27, 27, 29, red[4])
    c.rect(37, 27, 46, 43, blue[2])
    c.rect(37, 27, 46, 29, blue[4])
    for x in (21, 24, 39, 42):
        c.rect(x, 30, x + 1, 41, STEEL[4])
    c.disc(32, 35, 9, rgb(0x17375C))
    c.ring(32, 35, 8, field, 2)
    c.disc(32, 35, 3, rgb(0xE9FCFF))
    c.glow(32, 35, 12, rgb(0x6FE8FF, 110))

    # Ein Pfeil wird oberhalb des Generators sichtbar aus seiner Flugbahn gedrückt.
    wood = ramp(0x9A6234, 5, 0.45, 1.25)
    c.line(6, 11, 23, 16, wood[3], 3)
    c.line(23, 16, 38, 10, wood[3], 3)
    c.line(7, 10, 38, 10, wood[4], 1)
    c.polygon([(38, 5), (48, 9), (39, 15)], STEEL[4])
    c.polygon([(39, 8), (44, 9), (39, 12)], STEEL[5])
    c.line(22, 17, 27, 22, field, 2)
    c.sparkle(27, 22, rgb(0xE9FCFF), 2)


# ---------------------------------------------------------------------------
# 11 – Kettenblitz-Schuss
# ---------------------------------------------------------------------------

def draw_chain_lightning(c: Canvas) -> None:
    gold = ramp(0xFFD126, 6, 0.42, 1.28)
    hot = rgb(0xFFF7B0)
    white = rgb(0xFFFFFF)

    # Ein einziges grosses, klassisches Blitzsymbol statt Tesla-Stab oder Blocktreppe. Der
    # Lichtschein liegt hinter der Form und bleibt deshalb auch bei 16px als Goldrand erhalten.
    c.glow(32, 31, 27, rgb(0xFFC400, 82))
    c.glow(32, 31, 17, rgb(0xFFF06A, 105))
    silhouette = [(40, 3), (18, 29), (29, 29), (11, 60),
                  (43, 34), (32, 34), (55, 8)]
    c.polygon(silhouette, gold[2])

    # Linke Lichtkante und rechte Schattenkante verleihen der flachen GUI-Grafik Tiefe.
    c.polygon([(40, 5), (23, 28), (30, 28), (16, 53),
               (35, 33), (30, 33), (49, 10)], gold[5])
    c.polygon([(55, 8), (43, 34), (36, 34), (16, 56),
               (43, 32), (33, 32)], gold[0])

    # Weissglühender Energiekern folgt der Zickzackbewegung, ohne die Goldfläche zu überdecken.
    c.line(41, 9, 25, 28, hot, 3)
    c.line(25, 28, 34, 31, hot, 3)
    c.line(34, 31, 19, 51, hot, 3)
    c.line(41, 10, 30, 26, white, 1)

    # Zwei kurze Abzweigungen machen aus dem Symbol sichtbar einen Kettenblitz.
    c.line(27, 22, 17, 17, gold[4], 3)
    c.line(17, 17, 8, 21, gold[4], 3)
    c.line(38, 35, 49, 41, gold[3], 3)
    c.line(49, 41, 58, 38, gold[3], 3)
    c.sparkle(8, 21, hot, 2)
    c.sparkle(58, 38, hot, 2)


# ---------------------------------------------------------------------------
# 12 – Tarnkappenbomber
# ---------------------------------------------------------------------------

def draw_stealth_bomber(c: Canvas) -> None:
    hull = ramp(0x2A2F3C, 6, 0.45, 1.40)
    thrust = rgb(0x8FD8FF)

    # Nurflügler in Aufsicht
    c.polygon([(32, 4), (60, 44), (46, 44), (32, 32), (18, 44), (4, 44)], hull[2])
    c.polygon([(32, 4), (46, 24), (32, 24)], hull[4])
    c.polygon([(32, 4), (18, 24), (32, 24)], hull[3])
    c.polygon([(32, 32), (46, 44), (18, 44)], hull[1])

    # Panelfugen
    c.line(32, 4, 32, 32, hull[0], 1)
    c.line(32, 12, 14, 42, hull[1], 1)
    c.line(32, 12, 50, 42, hull[1], 1)
    c.line(32, 22, 22, 43, hull[1], 1)
    c.line(32, 22, 42, 43, hull[1], 1)
    c.dither(12, 34, 52, 43, hull[0], parity=1)

    # Cockpit
    c.polygon([(32, 10), (36, 20), (28, 20)], rgb(0x1A2C3A))
    c.polygon([(32, 12), (34, 18), (30, 18)], rgb(0x6FA8C8))

    # Triebwerke
    for x in (24, 40):
        c.rect(x - 3, 38, x + 3, 45, hull[0])
        c.rect(x - 3, 43, x + 3, 45, thrust)
        c.glow(x, 47, 8, (thrust[0], thrust[1], thrust[2], 110))
        for length in range(3):
            alpha = 150 - length * 45
            c.rect(x - 2 + length, 46 + length * 4, x + 2 - length, 49 + length * 4,
                   (thrust[0], thrust[1], thrust[2], alpha))

    # Abgeworfene Bombe
    c.ellipse(32, 52, 3, 5, GUNMETAL[3])
    c.polygon([(29, 56), (35, 56), (32, 60)], GUNMETAL[1])
    c.px(31, 49, STEEL[5])


# ---------------------------------------------------------------------------
# 13 – C4-Ladung
# ---------------------------------------------------------------------------

# 3x5-Ziffernsatz für das C4-Display; je Zeile ein Bitmuster von links nach rechts.
DIGITS = {
    "0": ("111", "101", "101", "101", "111"),
    "1": ("010", "110", "010", "010", "111"),
    "2": ("111", "001", "111", "100", "111"),
    "3": ("111", "001", "111", "001", "111"),
    "4": ("101", "101", "111", "001", "001"),
    "5": ("111", "100", "111", "001", "111"),
    "6": ("111", "100", "111", "101", "111"),
    "7": ("111", "001", "001", "001", "001"),
    "8": ("111", "101", "111", "101", "111"),
    "9": ("111", "101", "111", "001", "111"),
}


def draw_digit(c: Canvas, glyph: str, x: int, y: int, scale: int, on: RGBA, off: RGBA) -> int:
    """Zeichnet eine Ziffer und gibt ihre Breite zurück – so bleiben die Abstände gleichmäßig."""
    rows = DIGITS[glyph]
    for row, bits in enumerate(rows):
        for column, bit in enumerate(bits):
            color = on if bit == "1" else off
            c.rect(x + column * scale, y + row * scale,
                   x + column * scale + scale - 1, y + row * scale + scale - 1, color)
    return 3 * scale


def draw_c4(c: Canvas) -> None:
    clay = ramp(0xC9B98A, 6, 0.45, 1.20)
    wire_red = rgb(0xD03A2C)
    wire_blue = rgb(0x2E6FC0)
    lit = rgb(0x4CE07A)
    dim = rgb(0x15361F)

    # Sprengmasse – der Riegel trägt das Bild, der Zünder sitzt nur obenauf
    c.rect(5, 20, 58, 58, clay[2])
    c.vgrad(5, 20, 58, 58, clay[4], clay[1])
    c.frame(5, 20, 58, 58, shade(clay[0], 0.65))
    c.noise(6, 21, 57, 57, clay[1], 11, 5)
    c.noise(6, 21, 57, 57, clay[5], 17, 9)
    # Folienfalten und Klebeband
    c.line(5, 29, 58, 27, clay[5], 1)
    c.line(5, 51, 58, 53, clay[0], 1)
    c.rect(22, 20, 27, 58, clay[3])
    c.rect(22, 20, 22, 58, clay[5])
    c.rect(27, 20, 27, 58, clay[0])

    # Warnband quer über den Riegel
    c.rect(5, 34, 58, 41, WARN_RED[2])
    for x in range(4, 58, 7):
        c.polygon([(x, 41), (x + 4, 34), (x + 8, 34), (x + 4, 41)], rgb(0xF0D24A))
    c.rect(5, 34, 58, 34, WARN_RED[5])
    c.rect(5, 41, 58, 41, shade(WARN_RED[0], 0.8))

    # Zünderblock, kompakt oben aufgesetzt
    c.rect(20, 6, 44, 21, DARK_STEEL[2])
    c.rect(20, 6, 44, 7, DARK_STEEL[4])
    c.frame(20, 6, 44, 21, DARK_STEEL[0])
    for nx in (22, 42):
        c.px(nx, 8, STEEL[4])
        c.px(nx, 19, STEEL[2])

    # Display: Ziffern über die Zeichensatztabelle, damit die Abstände gleich bleiben
    c.rect(23, 9, 41, 18, rgb(0x081409))
    c.rect(24, 10, 40, 17, rgb(0x0E2413))
    scale = 2
    text = "30"
    gap = 3
    width = len(text) * 3 * scale + (len(text) - 1) * gap
    cursor = 32 - width // 2 + 1
    for glyph in text:
        cursor += draw_digit(c, glyph, cursor, 11, scale, lit, dim) + gap
    c.glow(32, 14, 10, rgb(0x3CC468, 55))

    # Kabel vom Zünder in die Masse
    c.line(21, 21, 14, 27, wire_red, 2)
    c.line(14, 27, 12, 32, wire_red, 2)
    c.line(43, 21, 50, 27, wire_blue, 2)
    c.line(50, 27, 52, 32, wire_blue, 2)
    c.disc(12, 32, 2, STEEL[4])
    c.disc(52, 32, 2, STEEL[4])


# ---------------------------------------------------------------------------
# 14 – Railgun
# ---------------------------------------------------------------------------

def draw_railgun(c: Canvas) -> None:
    """Waagerecht statt diagonal – die Silhouette einer Waffe liest sich so deutlich klarer."""
    coil = rgb(0x5AE0FF)

    # Schulterstütze
    c.polygon([(2, 32), (16, 29), (16, 43), (2, 46)], GUNMETAL[2])
    c.polygon([(2, 32), (16, 29), (16, 33), (2, 36)], GUNMETAL[4])
    c.rect(2, 32, 3, 46, RUBBER[3])

    # Griff und Abzugsbügel
    c.polygon([(18, 41), (26, 41), (23, 59), (15, 59)], RUBBER[2])
    c.polygon([(18, 41), (21, 41), (18, 59), (15, 59)], RUBBER[4])
    c.line(26, 42, 30, 48, DARK_STEEL[2], 2)

    # Gehäuse
    c.rect(16, 27, 32, 43, GUNMETAL[3])
    c.vgrad(16, 27, 32, 43, GUNMETAL[5], GUNMETAL[1])
    c.frame(16, 27, 32, 43, DARK_STEEL[0])
    c.rect(19, 31, 29, 36, DARK_STEEL[1])
    c.rect(20, 32, 28, 35, coil)
    c.glow(24, 33, 9, (coil[0], coil[1], coil[2], 80))

    # Lauf mit Schienenpaar: zwei dunkle Schienen, dazwischen die leuchtende Spur
    c.rect(32, 28, 60, 31, GUNMETAL[4])
    c.rect(32, 28, 60, 28, STEEL[3])
    c.rect(32, 38, 60, 41, GUNMETAL[2])
    c.rect(32, 41, 60, 41, DARK_STEEL[1])
    c.rect(32, 32, 60, 37, DARK_STEEL[0])
    c.rect(32, 34, 60, 35, coil)
    c.glow(46, 34, 14, (coil[0], coil[1], coil[2], 70))

    # Spulen entlang des Laufs
    for x in (36, 44, 52):
        c.rect(x, 25, x + 4, 44, STEEL[2])
        c.rect(x, 25, x + 4, 26, STEEL[5])
        c.frame(x, 25, x + 4, 44, DARK_STEEL[1])
        c.rect(x + 1, 33, x + 3, 36, coil)
        c.glow(x + 2, 34, 6, (coil[0], coil[1], coil[2], 90))

    # Mündung
    c.rect(58, 24, 62, 45, DARK_STEEL[2])
    c.rect(58, 24, 62, 25, DARK_STEEL[4])
    c.disc(60, 34, 3, rgb(0x0A1A22))
    c.disc(60, 34, 1.6, rgb(0xCFF6FF))
    c.glow(61, 34, 11, (coil[0], coil[1], coil[2], 120))

    # Zielfernrohr auf dem Gehäuse
    c.rect(18, 17, 36, 24, DARK_STEEL[2])
    c.rect(18, 17, 36, 18, DARK_STEEL[4])
    c.frame(18, 17, 36, 24, DARK_STEEL[0])
    c.rect(21, 24, 23, 27, DARK_STEEL[1])
    c.rect(31, 24, 33, 27, DARK_STEEL[1])
    c.disc(35, 20, 2.4, rgb(0x7FD8F0))
    c.sparkle(35, 20, rgb(0xFFFFFF, 200), 2)


# ---------------------------------------------------------------------------
# 15 – Singularität
# ---------------------------------------------------------------------------

def draw_singularity(c: Canvas) -> None:
    import math

    hot = rgb(0xFFD08A)
    violet = rgb(0x8A4FE0)

    # Akkretionsscheibe: verzerrte Ellipse
    for index in range(26, 8, -1):
        t = (index - 8) / 18.0
        color = mix(violet, hot, 1.0 - t)
        alpha = round(60 + 160 * (1.0 - abs(t - 0.55) * 2))
        c.ellipse(32, 34, index, max(2.0, index * 0.34), (color[0], color[1], color[2], max(30, alpha)))

    # Gebogene Materiearme
    for arm in range(4):
        base = arm * (math.pi / 2)
        for step in range(16):
            t = step / 15.0
            angle = base + t * 2.4
            radius = 8 + t * 20
            x = round(32 + math.cos(angle) * radius)
            y = round(34 + math.sin(angle) * radius * 0.42)
            color = mix(hot, violet, t)
            c.disc(x, y, 1.6 - t, (color[0], color[1], color[2], round(230 - t * 140)))

    # Lichtbogen oberhalb, gravitationsverzerrt
    c.ring(32, 34, 22, (hot[0], hot[1], hot[2], 70), 1)
    for step in range(-20, 21):
        y = round(34 - math.sqrt(max(0.0, 22 * 22 - step * step)) * 0.62)
        c.px(32 + step, y, (255, 236, 200, 120))

    # Ereignishorizont
    c.disc(32, 34, 10, (0, 0, 0, 255))
    c.ring(32, 34, 10, (hot[0], hot[1], hot[2], 220), 1)
    c.ring(32, 34, 11, (violet[0], violet[1], violet[2], 120), 1)
    c.glow(32, 34, 28, (violet[0], violet[1], violet[2], 60))
    c.sparkle(48, 22, rgb(0xFFFFFF, 190), 2)


# ---------------------------------------------------------------------------
# 16 – Gleitflug
# ---------------------------------------------------------------------------

def draw_glider(c: Canvas) -> None:
    membrane = ramp(0x4E6E8A, 6, 0.42, 1.30)
    edge = rgb(0x9FD8F0)

    for side in (-1, 1):
        cx = 32
        # Flügelfläche
        tip = cx + side * 30
        c.polygon(
            [(cx, 10), (tip, 24), (tip - side * 4, 40), (cx + side * 8, 52), (cx, 30)],
            membrane[2],
        )
        c.polygon([(cx, 10), (cx + side * 16, 22), (cx + side * 6, 30)], membrane[4])
        # Streben
        for index in range(1, 4):
            end_x = cx + side * (10 + index * 7)
            end_y = 26 + index * 7
            c.line(cx, 14, end_x, end_y, membrane[0], 1)
        # Vorderkante
        c.line(cx, 10, tip, 24, edge, 2)
        c.line(tip, 24, cx + side * 8, 52, membrane[1], 1)
        c.dither(min(cx, tip), 30, max(cx, tip), 50, membrane[1], parity=1)

    # Rückenmodul
    c.rect(27, 8, 37, 34, DARK_STEEL[2])
    c.rect(27, 8, 37, 9, DARK_STEEL[4])
    c.frame(27, 8, 37, 34, DARK_STEEL[0])
    for y in range(12, 32, 5):
        c.rect(29, y, 35, y + 1, STEEL[2])
    c.disc(32, 20, 4, edge)
    c.disc(32, 20, 2, rgb(0xE8FBFF))
    c.glow(32, 20, 12, (edge[0], edge[1], edge[2], 85))

    # Schubfahnen
    for side in (-1, 1):
        for index in range(3):
            alpha = 140 - index * 40
            x = 32 + side * (6 + index * 3)
            c.rect(x - 1, 52 + index * 3, x + 1, 56 + index * 3, (edge[0], edge[1], edge[2], alpha))


# ---------------------------------------------------------------------------
# 17 – Geschützturm
# ---------------------------------------------------------------------------

def draw_sentry_turret(c: Canvas) -> None:
    housing = ramp(0x59606B, 6, 0.42, 1.28)
    eye = rgb(0xFF4A32)

    # Stativbeine
    for dx in (-1, 1):
        c.line(32, 44, 32 + dx * 16, 61, DARK_STEEL[2], 3)
        c.line(32, 44, 32 + dx * 16, 61, STEEL[1], 1)
        c.rect(32 + dx * 16 - 3, 59, 32 + dx * 16 + 3, 61, DARK_STEEL[1])
    c.line(32, 44, 32, 61, DARK_STEEL[2], 3)
    c.rect(29, 59, 35, 61, DARK_STEEL[1])

    # Drehsockel
    c.ellipse(32, 44, 14, 6, housing[1])
    c.ellipse(32, 42, 14, 6, housing[3])
    c.ring(32, 42, 14, housing[5], 1)
    c.dither(18, 42, 46, 48, housing[0], parity=1)

    # Turmkopf
    c.rect(18, 20, 46, 40, housing[2])
    c.vgrad(18, 20, 46, 40, housing[4], housing[1])
    c.frame(18, 20, 46, 40, DARK_STEEL[1])
    c.rect(18, 20, 46, 22, housing[5])
    # Panzerplatten
    c.rect(20, 24, 26, 36, housing[3])
    c.rect(38, 24, 44, 36, housing[3])
    c.frame(20, 24, 26, 36, housing[0])
    c.frame(38, 24, 44, 36, housing[0])
    for nx, ny in ((21, 25), (25, 25), (21, 35), (25, 35), (39, 25), (43, 25), (39, 35), (43, 35)):
        c.px(nx, ny, STEEL[5])

    # Sensorauge
    c.rect(28, 26, 36, 34, DARK_STEEL[0])
    c.disc(32, 30, 4, eye)
    c.disc(32, 30, 2, rgb(0xFFD6C8))
    c.glow(32, 30, 12, (eye[0], eye[1], eye[2], 95))

    # Lauf
    c.rect(46, 27, 62, 33, GUNMETAL[3])
    c.rect(46, 27, 62, 28, GUNMETAL[5])
    c.rect(46, 32, 62, 33, GUNMETAL[0])
    for x in range(49, 61, 4):
        c.rect(x, 26, x + 1, 34, GUNMETAL[1])
    c.rect(60, 26, 62, 34, DARK_STEEL[2])
    c.disc(61, 30, 2, rgb(0x120E0C))

    # Munitionskasten
    c.rect(12, 30, 20, 40, OLIVE[2])
    c.rect(12, 30, 20, 31, OLIVE[4])
    c.frame(12, 30, 20, 40, DARK_STEEL[1])
    c.rect(13, 33, 19, 35, BRASS[3])



def draw_item_box(c: Canvas) -> None:
    """Ikonische 3D-Mario-Kart-Box-Textur: Vollständig deckender Goldener Kristallblock mit zentriertem Fragezeichen."""
    gold_dark = rgb(0x8A5200)
    gold_mid = rgb(0xD48A00)
    gold_bright = rgb(0xFFBE1A)
    gold_highlight = rgb(0xFFEE70)
    gold_edge = rgb(0xFFFAAA)
    border_dark = rgb(0x381E00)

    # 1. Äußerer Rahmen (0..63) vollflächig deckend ohne transparente Kanten
    c.rect(0, 0, 63, 63, border_dark)
    c.rect(1, 1, 62, 62, gold_mid)

    # Rahmen-Bevel: Lichtkanten oben/links, Schatten unten/rechts
    c.rect(1, 1, 62, 3, gold_highlight)
    c.rect(1, 1, 3, 62, gold_highlight)
    c.rect(60, 2, 62, 62, gold_dark)
    c.rect(2, 60, 62, 62, gold_dark)

    # Innerer Falz / Kontur
    c.rect(4, 4, 59, 59, border_dark)

    # 2. Kristalliner Innenbereich (5..58) mit Farbverlauf / Facetten
    for y in range(5, 59):
        t_y = (y - 5) / 53.0
        for x in range(5, 59):
            t_x = (x - 5) / 53.0
            # Diagonaler Farbverlauf von oben-links nach unten-rechts
            diag = (t_x * 0.45 + t_y * 0.55)
            col = mix(gold_bright, gold_mid, diag)
            # Dezente subtile Rauten-Struktur / Facetten
            if abs((x - 31.5) + (y - 31.5)) < 1.0 or abs((x - 31.5) - (y - 31.5)) < 1.0:
                col = mix(col, gold_highlight, 0.25)
            c.px(x, y, col)

    # Ecknieten / Kristallecken
    for nx, ny in ((3, 3), (60, 3), (3, 60), (60, 60)):
        c.rect(nx - 1, ny - 1, nx + 1, ny + 1, border_dark)
        c.px(nx, ny, gold_edge)

    # 3. Ikonisches Mario-Kart-Fragezeichen '?'
    mark_shadow = rgb(0x2E1600)
    mark_white = rgb(0xFFFFFF)
    mark_soft = rgb(0xFFF2D6)

    # Zeichne zuerst den Schatten (um 2 Pixel nach rechts-unten versetzt)
    def draw_qmark(ox: int, oy: int, col: RGBA, col_body: RGBA) -> None:
        # Oberer Bogen
        c.rect(ox + 25, oy + 15, ox + 38, oy + 19, col)
        c.rect(ox + 21, oy + 18, ox + 26, oy + 27, col)
        c.rect(ox + 37, oy + 18, ox + 42, oy + 29, col)

        # Mittlerer Schwung nach innen
        c.rect(ox + 31, oy + 28, ox + 38, oy + 33, col)

        # Vertikaler Mittelsteg
        c.rect(ox + 29, oy + 33, ox + 34, oy + 40, col)

        # Punkt unten
        c.rect(ox + 29, oy + 45, ox + 34, oy + 50, col)

        if col != col_body:
            # Füllung mit feinem Glanz (oberer Bereich heller)
            c.rect(ox + 26, oy + 16, ox + 37, oy + 18, col_body)
            c.rect(ox + 22, oy + 19, ox + 25, oy + 26, col_body)
            c.rect(ox + 38, oy + 19, ox + 41, oy + 28, col_body)
            c.rect(ox + 32, oy + 29, ox + 37, oy + 32, col_body)
            c.rect(ox + 30, oy + 34, ox + 33, oy + 39, col_body)
            c.rect(ox + 30, oy + 46, ox + 33, oy + 49, col_body)

    # Schatten
    draw_qmark(2, 2, mark_shadow, mark_shadow)
    draw_qmark(1, 2, mark_shadow, mark_shadow)
    draw_qmark(2, 1, mark_shadow, mark_shadow)

    # Weißer Hauptkörper mit Soft-Highlight
    draw_qmark(0, 0, mark_white, mark_soft)

    # 4. Dezente Glanzakzente (Highlight-Spiegelung)
    c.sparkle(10, 10, rgb(0xFFFFFF, 230), 3)
    c.sparkle(52, 52, rgb(0xFFFAAA, 160), 2)


ITEMS = {
    "minigun": draw_minigun,
    "item_box": draw_item_box,
    "airstrike": draw_airstrike,
    "radar_pulse": draw_radar_pulse,
    "explosive_shot": draw_explosive_shot,
    "reflector_shield": draw_reflector_shield,
    "smoke_bomb": draw_smoke_bomb,
    "frost_trap": draw_frost_trap,
    "teleport_grenade": draw_teleport_grenade,
    "invisibility_cloak": draw_invisibility_cloak,
    "arrow_magnet": draw_arrow_magnet,
    "chain_lightning": draw_chain_lightning,
    "stealth_bomber": draw_stealth_bomber,
    "c4": draw_c4,
    "railgun": draw_railgun,
    "singularity": draw_singularity,
    "glider": draw_glider,
    "sentry_turret": draw_sentry_turret,
}


# Diese Items haben ein 3D-Modell mit eigenem Materialatlas; ihre Textur entsteht in
# den jeweiligen tools/generate_*_3d.py-Skripten.
MODELLED_IN_3D = {
    "minigun", "stealth_bomber", "c4", "c4_charge", "sentry_turret", "railgun", "frost_trap",
    "explosive_shot", "reflector_shield", "smoke_bomb", "teleport_grenade",
    "airstrike", "glider", "radar_pulse", "invisibility_cloak",
}


def main() -> int:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    clipped: list[str] = []
    for name, draw in ITEMS.items():
        if name in MODELLED_IN_3D:
            continue
        canvas = Canvas(SIZE)
        draw(canvas)
        if name != "item_box":
            # Der äußerste Ring muss bei 2D-Items frei bleiben, sonst fehlt dort die Kontur.
            removed = canvas.clear_border(1)
            if removed > 24:
                clipped.append(f"{name} ({removed} px)")
            finish(canvas)
        path = OUTPUT / f"{name}.png"
        canvas.save(path)
        print(f"{name:22} -> {path.relative_to(OUTPUT.parents[5])}  ({path.stat().st_size} B)")

    print(f"\n{len(ITEMS) - len(MODELLED_IN_3D & ITEMS.keys())} Texturen erzeugt ({SIZE}x{SIZE}).")
    if clipped:
        print("WARNUNG – Inhalt berührt den Texturrand und wirkt abgeschnitten: " + ", ".join(clipped))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
