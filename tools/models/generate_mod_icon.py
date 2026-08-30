"""Erzeugt das Mod-Icon für fabric.mod.json.

    python tools/generate_mod_icon.py

Die Fabric-Spezifikation empfiehlt 128x128 – das bisherige Icon lag bei 1254x1254
und knapp 2,2 MB, die jeder Client beim Laden der Modliste mitzieht.
"""

from __future__ import annotations

import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from pixelart import Canvas, ramp, rgb  # noqa: E402

SIZE = 32
SCALE = 4  # -> 128x128
OUTPUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/assets/oneshotonekill/icon.png"

STEEL = ramp(0x9AA4B2, 6, 0.40, 1.25)
DARK = ramp(0x1B1F26, 5, 0.55, 1.45)
GOLD = ramp(0xE8A93A, 6, 0.42, 1.30)
RED = rgb(0xE04A2E)


def main() -> int:
    c = Canvas(SIZE)

    # Dunkle Scheibe als Hintergrund
    c.disc(16, 16, 15, DARK[1])
    c.ring(16, 16, 15, DARK[3], 2)
    c.ring(16, 16, 13, GOLD[2], 1)

    # Fadenkreuz-Ringe
    c.ring(16, 16, 10, STEEL[3], 1)
    c.ring(16, 16, 6, STEEL[2], 1)

    # Kreuzarme mit Lücke in der Mitte
    for x0, x1 in ((2, 8), (23, 29)):
        c.rect(x0, 15, x1, 16, GOLD[4])
    for y0, y1 in ((2, 8), (23, 29)):
        c.rect(15, y0, 16, y1, GOLD[4])

    # Einschussloch im Zentrum
    c.disc(16, 16, 3, RED)
    c.disc(16, 16, 1.5, rgb(0xFFE2D2))
    c.glow(16, 16, 8, (224, 74, 46, 90))

    # Ecknieten für den Gerätelook
    for nx, ny in ((7, 7), (24, 7), (7, 24), (24, 24)):
        c.disc(nx, ny, 1.4, STEEL[4])

    c.outline(rgb(0x08090C))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    c.save(OUTPUT, scale=SCALE)
    print(f"Icon: {OUTPUT}  ({OUTPUT.stat().st_size} B, {SIZE * SCALE}x{SIZE * SCALE})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
