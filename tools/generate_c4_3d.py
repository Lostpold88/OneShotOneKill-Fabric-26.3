"""Erzeugt Textur und Modell der C4-Haftladung.

    python tools/generate_c4_3d.py

Ausgabe:
  * textures/item/c4_charge.png   – Materialatlas
  * models/item/c4_charge.json    – die Ladung als Körper
  * items/c4_charge.json          – Item-Definition; die Leuchtdiode ist einfärbbar

Vorher war die Ladung eine flache Bildtafel: in der Hand ging das durch, in der Arena stand
sie als Pappaufsteller im Raum. Jetzt ist sie ein Riegel Sprengmasse mit Klebebändern,
Eckklammern, einem Zündkasten samt Antenne und zwei Kabeln dazwischen.

**Nur die Leuchtdiode trägt ``tintindex``.** Alle anderen Flächen bleiben von der Einfärbung
unberührt, und ``DyedItemColor`` steuert damit ausschließlich die Diode – die Ladung kann
blinken, ohne dass die Sprengmasse die Farbe wechselt. Siehe
``item/runtime/Deployables.java``.

**Die Unterseite liegt unter der Modellmitte.** Eine ``Display.ItemDisplay`` zeichnet das
Modell um ihre Position zentriert; wer die Entity auf die Oberfläche setzt, versenkt die halbe
Ladung darin. Das Skript gibt den nötigen Abstand aus, er gehört nach ``C4_LIFT``.
"""

from __future__ import annotations

import math
import pathlib
import random

import modelkit
from modelkit import Atlas, Tile, centred_display, cube, dither, shift

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/oneshotonekill"
MODELS_DIR = ASSETS / "models/item"
ITEMS_DIR = ASSETS / "items"
TEXTURES_DIR = ASSETS / "textures/item"
TEXTURE = "oneshotonekill:item/c4_charge"

SEED = 20260819
CENTRE = 8.0

# Der Riegel Sprengmasse.
BLOCK_LOW = (2.0, 4.6, 4.4)
BLOCK_HIGH = (14.0, 9.4, 11.6)
# Zündkasten obenauf.
BOX_LOW = (5.6, 9.4, 6.6)
BOX_HIGH = (10.4, 12.2, 9.4)


# ---------------------------------------------------------------------------
# Materialien
# ---------------------------------------------------------------------------


def _putty() -> Tile:
    """Sprengmasse in Folie: heller Ton mit Knitterfalten."""
    tile = Tile()
    tile.fill_gradient((206, 190, 148), (156, 142, 106), 10)
    rng = random.Random(SEED)
    for _ in range(5):
        y = rng.randrange(modelkit.TILE)
        for x in range(modelkit.TILE):
            drift = y + round(math.sin(x * 0.7) * 1.2)
            tile.set(x, drift, shift(tile.get(x, drift), rng.choice((-26, 22))))
    for _ in range(30):
        x, y = rng.randrange(modelkit.TILE), rng.randrange(modelkit.TILE)
        tile.set(x, y, shift(tile.get(x, y), rng.randint(-14, 14)))
    return tile


def _tape() -> Tile:
    """Klebeband: schwarzgelb, das Zeichen für „nicht anfassen“."""
    tile = Tile()
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            hot = ((x + y) // 3) % 2 == 0
            tile.set(x, y, shift((216, 176, 44) if hot else (28, 26, 22), dither(x, y, 9)))
    return tile


def _casing() -> Tile:
    """Zündkasten: mattes dunkles Gehäuse mit Schraubenköpfen."""
    tile = Tile()
    tile.fill_gradient((74, 78, 84), (40, 43, 48), 7)
    for x, y in ((2, 2), (13, 2), (2, 13), (13, 13)):
        tile.set(x, y, (118, 124, 132))
        tile.set(x, y + 1 if y < 8 else y - 1, (26, 28, 32))
    return tile


def _panel() -> Tile:
    """Tastenfeld auf dem Deckel."""
    tile = Tile()
    tile.fill_gradient((34, 36, 40), (20, 21, 25), 4)
    for row in range(3):
        for column in range(3):
            x, y = 3 + column * 4, 3 + row * 4
            for dx in range(3):
                for dy in range(3):
                    tile.set(x + dx, y + dy, (92, 96, 104) if (row + column) % 2 == 0 else (64, 68, 74))
    return tile


def _led() -> Tile:
    """Leuchtdiode: fast weiß, damit die Einfärbung den ganzen Farbraum behält."""
    tile = Tile()
    tile.fill_gradient((236, 236, 232), (188, 188, 184), 5)
    centre = (modelkit.TILE - 1) / 2.0
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            if abs(y - centre) < 4.5:
                tile.set(x, y, (255, 255, 252))
    for x in range(0, modelkit.TILE, 4):
        tile.set(x, 7, (210, 210, 206))
        tile.set(x, 8, (210, 210, 206))
    return tile


def _metal() -> Tile:
    """Antenne und Klammern: gebürsteter Stahl."""
    tile = Tile()
    tile.fill_gradient((150, 155, 162), (86, 90, 98), 8)
    tile.streaks((2, 3, 8, 9, 13), 16)
    return tile


def _wire_red() -> Tile:
    return _wire((198, 58, 44), (96, 24, 20))


def _wire_blue() -> Tile:
    return _wire((52, 106, 190), (20, 44, 88))


def _wire(bright: tuple[int, int, int], dark: tuple[int, int, int]) -> Tile:
    """Kabelmantel: rund wirkend durch einen Lichtstreifen in der Mitte."""
    tile = Tile()
    tile.fill_gradient(dark, bright, 5)
    for x in range(modelkit.TILE):
        tile.set(x, 6, shift(bright, 46))
        tile.set(x, 7, shift(bright, 22))
    return tile


ATLAS = Atlas({
    "putty": _putty,
    "tape": _tape,
    "casing": _casing,
    "panel": _panel,
    "led": _led,
    "metal": _metal,
    "wire_red": _wire_red,
    "wire_blue": _wire_blue,
})


# ---------------------------------------------------------------------------
# Geometrie
# ---------------------------------------------------------------------------


def build() -> list[dict]:
    elements: list[dict] = []

    # Der Riegel selbst. Die Unterseite bleibt dunkel: dort klebt er an der Wand.
    elements.append(cube(ATLAS, BLOCK_LOW, BLOCK_HIGH, "putty", {"down": "casing"}))

    # Zwei Klebebänder quer darum herum, einen Hauch über der Oberfläche.
    for start in (4.2, 9.8):
        elements.append(cube(
            ATLAS,
            (start, BLOCK_LOW[1] - 0.06, BLOCK_LOW[2] - 0.06),
            (start + 1.7, BLOCK_HIGH[1] + 0.06, BLOCK_HIGH[2] + 0.06),
            "tape",
        ))

    # Eckklammern – ohne sie sieht der Riegel aus wie ein Stück Butter.
    for x in (BLOCK_LOW[0], BLOCK_HIGH[0] - 1.0):
        for z in (BLOCK_LOW[2], BLOCK_HIGH[2] - 1.0):
            elements.append(cube(
                ATLAS,
                (x - 0.08, BLOCK_LOW[1] - 0.08, z - 0.08),
                (x + 1.08, BLOCK_LOW[1] + 1.2, z + 1.08),
                "metal",
            ))

    # Zündkasten mit Tastenfeld.
    elements.append(cube(ATLAS, BOX_LOW, BOX_HIGH, "casing"))
    elements.append(cube(
        ATLAS,
        (BOX_LOW[0] + 0.7, BOX_HIGH[1], BOX_LOW[2] + 0.5),
        (BOX_HIGH[0] - 0.7, BOX_HIGH[1] + 0.28, BOX_HIGH[2] - 0.5),
        "panel",
    ))

    # Die Leuchtdiode sitzt vorn am Kasten und ragt ein Stück heraus, damit sie auch von
    # schräg unten zu sehen ist – aus dieser Richtung schaut man eine liegende Ladung an.
    elements.append(cube(
        ATLAS,
        (BOX_LOW[0] + 0.9, BOX_LOW[1] + 0.8, BOX_LOW[2] - 0.35),
        (BOX_HIGH[0] - 0.9, BOX_LOW[1] + 1.9, BOX_LOW[2] + 0.05),
        "led",
    ))

    # Zweite Diode oben auf dem Deckel. An einer Wand oder unter einer Decke zeigt die
    # vordere ins Leere – diese hier schaut immer von der Fläche weg.
    elements.append(cube(
        ATLAS,
        (BOX_LOW[0] + 1.5, BOX_HIGH[1] + 0.28, BOX_LOW[2] + 0.9),
        (BOX_HIGH[0] - 1.5, BOX_HIGH[1] + 0.62, BOX_HIGH[2] - 0.9),
        "led",
    ))

    # Antenne, leicht nach hinten geneigt.
    elements.append(cube(
        ATLAS,
        (9.5, BOX_HIGH[1] - 0.4, 8.6),
        (10.1, BOX_HIGH[1] + 3.4, 9.2),
        "metal",
        rotation={"origin": [9.8, BOX_HIGH[1], 8.9], "axis": "x", "angle": -14.0},
    ))

    # Zwei Kabel vom Kasten in die Masse, gegenläufig geneigt.
    for material, x, angle in (("wire_red", 4.9, 34.0), ("wire_blue", 10.5, -34.0)):
        elements.append(cube(
            ATLAS,
            (x, BLOCK_HIGH[1] - 0.6, 7.6),
            (x + 0.62, BOX_HIGH[1] - 0.6, 8.2),
            material,
            rotation={"origin": [x + 0.31, BLOCK_HIGH[1], 7.9], "axis": "z", "angle": angle},
        ))

    return elements


def mark_led(elements: list[dict]) -> list[dict]:
    """Meldet nur die Diode für die Einfärbung an.

    ``tintindex`` fehlt sonst überall, und Vanilla lässt den Farbeintrag der Item-Definition
    dann liegen. Genau das ist hier gewollt: gefärbt wird die Diode, nicht die Ladung.
    """
    for element in elements:
        for face in element["faces"].values():
            if face["uv"] == ATLAS.uv("led"):
                face["tintindex"] = 0
    return elements


def display_for(elements: list[dict]) -> dict:
    hand = centred_display(elements, (14, -140, 0), 0.52, offset=(1.4, 4.05, 0.0))
    return {
        "firstperson_righthand": hand,
        "firstperson_lefthand": dict(hand),
        "thirdperson_righthand": centred_display(elements, (20, -130, 0), 0.5, offset=(0.0, 2.5, 0.0)),
        "thirdperson_lefthand": centred_display(elements, (20, -130, 0), 0.5, offset=(0.0, 2.5, 0.0)),
        "gui": centred_display(elements, (28, -150, 0), 0.94, flat=True),
        "ground": centred_display(elements, (0, 0, 0), 0.55, offset=(0.0, 3.0, 0.0)),
        "fixed": centred_display(elements, (0, 180, 0), 0.95),
        "head": centred_display(elements, (0, 180, 0), 1.1, offset=(0.0, 13.0, 0.0)),
    }


def main() -> int:
    texture_path = TEXTURES_DIR / "c4_charge.png"
    ATLAS.save(texture_path)
    print(f"Atlas: {texture_path.relative_to(ROOT)} "
          f"({texture_path.stat().st_size} B, {ATLAS.size[0]}x{ATLAS.size[1]}, {len(ATLAS.slots)} Materialien)")

    elements = mark_led(build())
    modelkit.write(MODELS_DIR / "c4_charge.json", modelkit.model(TEXTURE, elements, display_for(elements)))
    modelkit.write(ITEMS_DIR / "c4_charge.json", {
        "model": {
            "type": "minecraft:model",
            "model": "oneshotonekill:item/c4_charge",
            # Ohne Farbe im Stapel leuchtet die Diode rot – so sieht sie auch im Inventar aus.
            "tints": [{"type": "minecraft:dye", "default": modelkit.opaque(0xFF4A32)}],
        },
    })

    low, high = modelkit.bounds(elements)
    tinted = sum(1 for element in elements for face in element["faces"].values() if "tintindex" in face)
    print(f"c4: {len(elements)} Elemente, Ausdehnung "
          f"{[round(v, 2) for v in low]} .. {[round(v, 2) for v in high]}, {tinted} Flächen einfärbbar")
    print(f"    Unterseite liegt {(CENTRE - low[1]) / 16.0:.4f} Blöcke unter der Modellmitte – muss mit "
          f"C4_LIFT in item/runtime/Deployables.java übereinstimmen, sonst steckt die Ladung im Boden.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
