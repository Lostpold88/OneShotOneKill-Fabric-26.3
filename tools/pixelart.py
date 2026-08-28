"""Kleine Zeichenbibliothek für Minecraft-Pixel-Art.

Gearbeitet wird auf einem logischen Pixelraster (Standard 64x64, also viermal
Vanilla-Auflösung). Alles ist bewusst pixelgenau: keine Antialiasing-Kanten,
keine Weichzeichner – nur gesetzte Pixel, damit die Texturen im Spiel scharf
bleiben und wie handgepixelt wirken.

Die Lichtquelle liegt einheitlich oben links.
"""

from __future__ import annotations

import math
from typing import Iterable, Sequence

from PIL import Image

RGBA = tuple[int, int, int, int]

TRANSPARENT: RGBA = (0, 0, 0, 0)


# --------------------------------------------------------------------------
# Farben
# --------------------------------------------------------------------------

def rgb(value: int, alpha: int = 255) -> RGBA:
    """0xRRGGBB -> RGBA."""
    return ((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF, alpha)


def mix(a: RGBA, b: RGBA, t: float) -> RGBA:
    t = max(0.0, min(1.0, t))
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(4))  # type: ignore[return-value]


def shade(color: RGBA, factor: float) -> RGBA:
    """factor < 1 verdunkelt, > 1 hellt auf. Alpha bleibt unangetastet."""
    return (
        max(0, min(255, round(color[0] * factor))),
        max(0, min(255, round(color[1] * factor))),
        max(0, min(255, round(color[2] * factor))),
        color[3],
    )


def ramp(base: int, steps: int = 5, low: float = 0.45, high: float = 1.35) -> list[RGBA]:
    """Erzeugt eine Tonwertrampe von dunkel nach hell rund um eine Grundfarbe."""
    root = rgb(base)
    out: list[RGBA] = []
    for index in range(steps):
        t = index / max(1, steps - 1)
        out.append(shade(root, low + (high - low) * t))
    return out


# --------------------------------------------------------------------------
# Leinwand
# --------------------------------------------------------------------------

class Canvas:
    def __init__(self, size: int = 64) -> None:
        self.size = size
        self.pixels: list[list[RGBA]] = [[TRANSPARENT] * size for _ in range(size)]

    # -- Grundlagen ---------------------------------------------------------

    def inside(self, x: int, y: int) -> bool:
        return 0 <= x < self.size and 0 <= y < self.size

    def get(self, x: int, y: int) -> RGBA:
        return self.pixels[y][x] if self.inside(x, y) else TRANSPARENT

    def px(self, x: int, y: int, color: RGBA) -> None:
        if not self.inside(x, y) or color[3] == 0:
            return
        if color[3] == 255:
            self.pixels[y][x] = color
            return
        # Alpha über den vorhandenen Pixel legen.
        below = self.pixels[y][x]
        t = color[3] / 255.0
        if below[3] == 0:
            self.pixels[y][x] = color
        else:
            self.pixels[y][x] = (
                round(below[0] + (color[0] - below[0]) * t),
                round(below[1] + (color[1] - below[1]) * t),
                round(below[2] + (color[2] - below[2]) * t),
                max(below[3], color[3]),
            )

    def opaque(self, x: int, y: int) -> bool:
        return self.get(x, y)[3] > 0

    # -- Flächen ------------------------------------------------------------

    def clear_rect(self, x0: int, y0: int, x1: int, y1: int) -> None:
        """Radiert einen Bereich frei – `px` überschreibt mit Alpha 0 bewusst nicht."""
        for y in range(max(0, y0), min(self.size - 1, y1) + 1):
            for x in range(max(0, x0), min(self.size - 1, x1) + 1):
                self.pixels[y][x] = TRANSPARENT

    def clear_disc(self, cx: float, cy: float, radius: float) -> None:
        span = int(math.ceil(radius)) + 1
        for y in range(int(cy) - span, int(cy) + span + 1):
            for x in range(int(cx) - span, int(cx) + span + 1):
                if self.inside(x, y) and (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2 <= radius * radius:
                    self.pixels[y][x] = TRANSPARENT

    def rect(self, x0: int, y0: int, x1: int, y1: int, color: RGBA) -> None:
        for y in range(min(y0, y1), max(y0, y1) + 1):
            for x in range(min(x0, x1), max(x0, x1) + 1):
                self.px(x, y, color)

    def frame(self, x0: int, y0: int, x1: int, y1: int, color: RGBA) -> None:
        for x in range(x0, x1 + 1):
            self.px(x, y0, color)
            self.px(x, y1, color)
        for y in range(y0, y1 + 1):
            self.px(x0, y, color)
            self.px(x1, y, color)

    def vgrad(self, x0: int, y0: int, x1: int, y1: int, top: RGBA, bottom: RGBA) -> None:
        span = max(1, y1 - y0)
        for y in range(y0, y1 + 1):
            self.rect(x0, y, x1, y, mix(top, bottom, (y - y0) / span))

    def hgrad(self, x0: int, y0: int, x1: int, y1: int, left: RGBA, right: RGBA) -> None:
        span = max(1, x1 - x0)
        for x in range(x0, x1 + 1):
            self.rect(x, y0, x, y1, mix(left, right, (x - x0) / span))

    def disc(self, cx: float, cy: float, radius: float, color: RGBA) -> None:
        span = int(math.ceil(radius)) + 1
        for y in range(int(cy) - span, int(cy) + span + 1):
            for x in range(int(cx) - span, int(cx) + span + 1):
                if (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2 <= radius * radius:
                    self.px(x, y, color)

    def ring(self, cx: float, cy: float, radius: float, color: RGBA, thickness: float = 1.0) -> None:
        inner = max(0.0, radius - thickness)
        span = int(math.ceil(radius)) + 1
        for y in range(int(cy) - span, int(cy) + span + 1):
            for x in range(int(cx) - span, int(cx) + span + 1):
                d2 = (x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2
                if inner * inner <= d2 <= radius * radius:
                    self.px(x, y, color)

    def ellipse(self, cx: float, cy: float, rx: float, ry: float, color: RGBA) -> None:
        for y in range(int(cy - ry) - 1, int(cy + ry) + 2):
            for x in range(int(cx - rx) - 1, int(cx + rx) + 2):
                nx = (x + 0.5 - cx) / max(0.001, rx)
                ny = (y + 0.5 - cy) / max(0.001, ry)
                if nx * nx + ny * ny <= 1.0:
                    self.px(x, y, color)

    def line(self, x0: int, y0: int, x1: int, y1: int, color: RGBA, thickness: int = 1) -> None:
        """Bresenham; bei thickness > 1 wird ein Quadrat um jeden Punkt gesetzt."""
        dx = abs(x1 - x0)
        dy = -abs(y1 - y0)
        sx = 1 if x0 < x1 else -1
        sy = 1 if y0 < y1 else -1
        err = dx + dy
        reach = thickness // 2
        while True:
            if thickness <= 1:
                self.px(x0, y0, color)
            else:
                self.rect(x0 - reach, y0 - reach, x0 + reach, y0 + reach, color)
            if x0 == x1 and y0 == y1:
                break
            doubled = 2 * err
            if doubled >= dy:
                err += dy
                x0 += sx
            if doubled <= dx:
                err += dx
                y0 += sy

    def polygon(self, points: Sequence[tuple[int, int]], color: RGBA) -> None:
        """Scanline-Füllung eines geschlossenen Polygons."""
        if len(points) < 3:
            return
        top = min(p[1] for p in points)
        bottom = max(p[1] for p in points)
        for y in range(top, bottom + 1):
            crossings: list[float] = []
            for index in range(len(points)):
                ax, ay = points[index]
                bx, by = points[(index + 1) % len(points)]
                if ay == by:
                    continue
                if min(ay, by) <= y + 0.5 < max(ay, by):
                    crossings.append(ax + (y + 0.5 - ay) / (by - ay) * (bx - ax))
            crossings.sort()
            for pair in range(0, len(crossings) - 1, 2):
                for x in range(int(math.floor(crossings[pair])), int(math.ceil(crossings[pair + 1])) + 1):
                    self.px(x, y, color)

    # -- Stilmittel ---------------------------------------------------------

    def tint_horizontal(self, x0: int, y0: int, x1: int, y1: int, left: float, right: float) -> None:
        """
        Schattiert vorhandene Pixel abhängig von ihrer x-Position.

        Gedacht für Formen, die aus mehreren Teilstücken zusammengesetzt sind: Wird jedes Teil
        einzeln eingefärbt, entsteht an den Stoßkanten eine sichtbare Naht. Erst die Silhouette
        in einem Ton aufbauen und dann hierdurch schattieren – dann kann es keine Naht geben.
        """
        span = max(1, x1 - x0)
        for y in range(max(0, y0), min(self.size - 1, y1) + 1):
            for x in range(max(0, x0), min(self.size - 1, x1) + 1):
                if not self.opaque(x, y):
                    continue
                factor = left + (right - left) * ((x - x0) / span)
                self.pixels[y][x] = shade(self.pixels[y][x], factor)

    def dither(self, x0: int, y0: int, x1: int, y1: int, color: RGBA, parity: int = 0) -> None:
        """Schachbrett-Raster – ersetzt weiche Verläufe durch echtes Pixel-Shading."""
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                if (x + y) % 2 == parity and self.opaque(x, y):
                    self.px(x, y, color)

    def noise(self, x0: int, y0: int, x1: int, y1: int, color: RGBA, density: int = 7, seed: int = 1) -> None:
        """Deterministisches Streumuster für Materialkörnung."""
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                if not self.opaque(x, y):
                    continue
                if (x * 73856093 ^ y * 19349663 ^ seed * 83492791) % density == 0:
                    self.px(x, y, color)

    def outline(self, color: RGBA, diagonal: bool = False, darken: float = 0.34) -> None:
        """
        Legt eine Kontur um alle gesetzten Pixel.

        Die Farbe wird aus den angrenzenden Pixeln abgeleitet und abgedunkelt, statt überall
        dasselbe Schwarz zu setzen. Eine uniform schwarze Linie liest sich im Spiel wie ein
        aufgeklebter Rahmen; eine mitlaufende Schattenfarbe wirkt wie Volumen. `color` dient
        nur noch als Rückfall für Stellen ohne brauchbaren Nachbarn.
        """
        offsets = [(-1, 0), (1, 0), (0, -1), (0, 1)]
        if diagonal:
            offsets += [(-1, -1), (1, -1), (-1, 1), (1, 1)]

        additions: list[tuple[int, int, RGBA]] = []
        for y in range(self.size):
            for x in range(self.size):
                if self.opaque(x, y):
                    continue
                neighbours = [self.get(x + ox, y + oy) for ox, oy in offsets]
                lit = [n for n in neighbours if n[3] > 0]
                if not lit:
                    continue
                red = sum(n[0] for n in lit) // len(lit)
                green = sum(n[1] for n in lit) // len(lit)
                blue = sum(n[2] for n in lit) // len(lit)
                shaded = shade((red, green, blue, 255), darken)
                # Sehr dunkle Nachbarn würden eine unsichtbare Kontur ergeben – dort greift der Rückfall.
                if max(shaded[0], shaded[1], shaded[2]) < 18:
                    shaded = color
                additions.append((x, y, shaded))

        for x, y, tone in additions:
            self.pixels[y][x] = tone

    def clear_border(self, margin: int = 1) -> int:
        """
        Räumt den äußersten Ring frei und meldet, wie viele Pixel das gekostet hat.

        Lichtschein und Glanzpunkte laufen leicht über den Rand hinaus; dort kann keine Kontur
        mehr gezeichnet werden und das Item wirkt abgeschnitten. Ein kleiner Wert ist normal,
        ein großer heißt, dass echte Zeichnung am Rand klebt.
        """
        removed = 0
        for index in range(self.size):
            for offset in range(margin):
                for x, y in ((index, offset), (index, self.size - 1 - offset),
                             (offset, index), (self.size - 1 - offset, index)):
                    if self.opaque(x, y):
                        self.pixels[y][x] = TRANSPARENT
                        removed += 1
        return removed

    def touches_border(self, margin: int = 1) -> bool:
        """Prüft, ob Inhalt so weit außen liegt, dass die Kontur abgeschnitten würde."""
        for index in range(self.size):
            for offset in range(margin):
                if (self.opaque(index, offset) or self.opaque(index, self.size - 1 - offset)
                        or self.opaque(offset, index) or self.opaque(self.size - 1 - offset, index)):
                    return True
        return False

    def bevel(self, light: RGBA, dark: RGBA) -> None:
        """Hellt obere/linke Kanten auf und verdunkelt untere/rechte."""
        changes: list[tuple[int, int, RGBA]] = []
        for y in range(self.size):
            for x in range(self.size):
                if not self.opaque(x, y):
                    continue
                if not self.opaque(x, y - 1) or not self.opaque(x - 1, y):
                    changes.append((x, y, light))
                elif not self.opaque(x, y + 1) or not self.opaque(x + 1, y):
                    changes.append((x, y, dark))
        for x, y, color in changes:
            self.px(x, y, color)

    def glow(self, cx: float, cy: float, radius: float, color: RGBA, falloff: float = 2.0) -> None:
        """Weicher Lichtschein – setzt nur auf freie Pixel, überzeichnet also nichts."""
        span = int(math.ceil(radius)) + 1
        for y in range(int(cy) - span, int(cy) + span + 1):
            for x in range(int(cx) - span, int(cx) + span + 1):
                distance = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
                if distance > radius:
                    continue
                strength = (1.0 - distance / radius) ** falloff
                alpha = round(color[3] * strength)
                if alpha <= 4:
                    continue
                self.px(x, y, (color[0], color[1], color[2], alpha))

    def sparkle(self, x: int, y: int, color: RGBA, arm: int = 2) -> None:
        """Vier-Strahl-Glanzpunkt."""
        self.px(x, y, color)
        for step in range(1, arm + 1):
            faded = (color[0], color[1], color[2], max(40, color[3] - step * 60))
            self.px(x + step, y, faded)
            self.px(x - step, y, faded)
            self.px(x, y + step, faded)
            self.px(x, y - step, faded)

    # -- Ausgabe ------------------------------------------------------------

    def to_image(self, scale: int = 1) -> Image.Image:
        image = Image.new("RGBA", (self.size, self.size))
        image.putdata([self.pixels[y][x] for y in range(self.size) for x in range(self.size)])
        if scale > 1:
            image = image.resize((self.size * scale, self.size * scale), Image.NEAREST)
        return image

    def save(self, path, scale: int = 1) -> None:
        self.to_image(scale).save(path, "PNG", optimize=True)
