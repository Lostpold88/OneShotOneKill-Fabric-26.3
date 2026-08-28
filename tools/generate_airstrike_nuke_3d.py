"""Erzeugt das große Nuklearbomben-Modell des Luftangriffs.

    python tools/generate_airstrike_nuke_3d.py

Ausgabe:
  * textures/item/airstrike_nuke.png
  * models/item/airstrike_nuke.json
  * items/airstrike_nuke.json

Das Modell steht senkrecht: Spitze bei -Y, Leitwerk bei +Y. In der Welt wird es mit einer
Skalierung von 3,6 dargestellt und ist damit fast vier Blöcke lang. Das Skript gibt den Abstand
der Spitze von der Modellmitte aus; er muss als NUKE_NOSE_OFFSET nach AirstrikeSystem.java.
"""

from __future__ import annotations

import math
import pathlib

import modelkit
from modelkit import Atlas, Tile, centred_display, cube, dither, prism, shift

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/oneshotonekill"
MODELS_DIR = ASSETS / "models/item"
ITEMS_DIR = ASSETS / "items"
TEXTURES_DIR = ASSETS / "textures/item"
TEXTURE = "oneshotonekill:item/airstrike_nuke"

CENTRE = 8.0
WORLD_SCALE = 3.6
NOSE_Y = 0.2
TAIL_Y = 16.0


def _casing() -> Tile:
    """Olivgraue, matte Bombenhülle mit feinen Blechstößen."""
    tile = Tile()
    tile.fill_gradient((112, 119, 92), (58, 64, 50), 10)
    tile.streaks((2, 3, 8, 9, 14), 11)
    for y in (3, 11):
        tile.bands(range(y, y + 1), -22)
    return tile


def _panel() -> Tile:
    """Dunkle Wartungsbleche und Verbindungsringe."""
    tile = Tile()
    tile.fill_gradient((62, 66, 60), (25, 28, 27), 7)
    for x in range(0, modelkit.TILE, 5):
        for y in range(modelkit.TILE):
            tile.set(x, y, shift(tile.get(x, y), -18))
    return tile


def _warning() -> Tile:
    """Schwarz-gelbes Warnband, aus großer Entfernung noch lesbar."""
    tile = Tile()
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            yellow = ((x + y * 2) // 4) % 2 == 0
            colour = (222, 178, 38) if yellow else (24, 25, 21)
            tile.set(x, y, shift(colour, dither(x, y, 8)))
    return tile


def _nose() -> Tile:
    """Dunkle hitzefeste Kappe mit blanken Schrammen."""
    tile = Tile()
    tile.fill_gradient((66, 69, 66), (22, 24, 25), 9)
    for step in range(0, modelkit.TILE, 3):
        tile.set(step, (step * 5) % modelkit.TILE, (125, 128, 118))
    return tile


def _fin() -> Tile:
    """Schweres Leitwerk mit Rippen und heller Außenkante."""
    tile = Tile()
    tile.fill_gradient((76, 80, 72), (35, 38, 36), 8)
    tile.bands(range(2, modelkit.TILE, 4), -20)
    for y in range(modelkit.TILE):
        tile.set(0, y, (118, 121, 108))
        tile.set(modelkit.TILE - 1, y, (20, 22, 22))
    return tile


def _arming() -> Tile:
    """Rote mechanische Sicherung – Farbe, kein Leuchteffekt."""
    tile = Tile()
    tile.fill_gradient((176, 50, 32), (62, 19, 17), 8)
    centre = (modelkit.TILE - 1) / 2.0
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            if math.hypot(x - centre, y - centre) < 3.0:
                tile.set(x, y, shift((224, 78, 42), dither(x, y, 10)))
    return tile


ATLAS = Atlas({
    "casing": _casing,
    "panel": _panel,
    "warning": _warning,
    "nose": _nose,
    "fin": _fin,
    "arming": _arming,
}, columns=3)


# Viele kurze Scheiben geben dem Körper eine bauchige, schwere Silhouette statt der Form einer
# dünnen Rakete. (Unterkante, Oberkante, Radius, Material)
BODY = (
    (NOSE_Y, 0.8, 0.55, "nose"),
    (0.8, 1.5, 1.35, "nose"),
    (1.5, 2.4, 2.35, "casing"),
    (2.4, 3.5, 3.25, "casing"),
    (3.5, 5.0, 4.05, "casing"),
    (5.0, 8.2, 4.4, "casing"),
    (8.2, 9.2, 4.45, "warning"),
    (9.2, 10.8, 4.35, "casing"),
    (10.8, 11.4, 3.9, "panel"),
    (11.4, 12.4, 3.45, "casing"),
    (12.4, 13.4, 2.55, "casing"),
    (13.4, 15.4, 1.65, "panel"),
)


def build() -> list[dict]:
    elements: list[dict] = []
    for low, high, radius, material in BODY:
        elements += prism(ATLAS, CENTRE, CENTRE, radius, low, high, material, axis="y", sides=12)

    # Zwei durchgehende Platten ergeben vier große Leitflossen. Sie sind absichtlich breit:
    # aus zwanzig Blöcken Entfernung soll die Silhouette noch eindeutig eine Bombe sein.
    elements.append(cube(ATLAS, (CENTRE - 0.24, 11.7, 2.0), (CENTRE + 0.24, TAIL_Y, 14.0), "fin"))
    elements.append(cube(ATLAS, (2.0, 11.7, CENTRE - 0.24), (14.0, TAIL_Y, CENTRE + 0.24), "fin"))
    elements += prism(ATLAS, CENTRE, CENTRE, 1.8, 13.2, TAIL_Y, "panel", axis="y", sides=12)

    # Vier erhabene Sicherungskästen knapp unter dem Warnband brechen die glatte Hülle auf.
    for index in range(4):
        angle = index * math.pi / 2.0
        x = CENTRE + math.cos(angle) * 4.15
        z = CENTRE + math.sin(angle) * 4.15
        elements.append(cube(
            ATLAS,
            (x - 0.55, 7.25, z - 0.55),
            (x + 0.55, 8.15, z + 0.55),
            "arming",
            rotation={"origin": [CENTRE, 7.7, CENTRE], "axis": "y", "angle": index * 90.0},
        ))
    return elements


def display_for(elements: list[dict]) -> dict:
    hand = centred_display(elements, (0, -135, 28), 0.48, offset=(1.2, 1.2, 0.0))
    return {
        "firstperson_righthand": hand,
        "firstperson_lefthand": dict(hand),
        "thirdperson_righthand": centred_display(elements, (0, -130, 25), 0.46, offset=(0.0, 2.5, 0.0)),
        "thirdperson_lefthand": centred_display(elements, (0, -130, 25), 0.46, offset=(0.0, 2.5, 0.0)),
        "gui": centred_display(elements, (20, -145, 25), 0.9, flat=True),
        "ground": centred_display(elements, (0, 0, 0), 0.55, offset=(0.0, 3.0, 0.0)),
        "fixed": centred_display(elements, (0, 180, 0), 0.9),
        "head": centred_display(elements, (0, 180, 0), 1.0, offset=(0.0, 13.0, 0.0)),
    }


def main() -> int:
    texture_path = TEXTURES_DIR / "airstrike_nuke.png"
    ATLAS.save(texture_path)
    elements = build()
    modelkit.write(MODELS_DIR / "airstrike_nuke.json",
                   modelkit.model(TEXTURE, elements, display_for(elements)))
    modelkit.write(ITEMS_DIR / "airstrike_nuke.json", {
        "model": {"type": "minecraft:model", "model": "oneshotonekill:item/airstrike_nuke"},
    })

    low, high = modelkit.bounds(elements)
    nose_offset = (CENTRE - low[1]) / 16.0 * WORLD_SCALE
    tail_offset = (high[1] - CENTRE) / 16.0 * WORLD_SCALE
    print(f"Atlas: {texture_path.relative_to(ROOT)} ({texture_path.stat().st_size} B, "
          f"{ATLAS.size[0]}x{ATLAS.size[1]}, {len(ATLAS.slots)} Materialien)")
    print(f"airstrike_nuke: {len(elements)} Elemente, Ausdehnung "
          f"{[round(v, 2) for v in low]} .. {[round(v, 2) for v in high]}")
    print(f"Bei Skalierung {WORLD_SCALE:.1f}: Spitze {nose_offset:.4f} Blöcke unter, "
          f"Heck {tail_offset:.4f} Blöcke über der Displaymitte.")
    print("Diese Werte müssen mit NUKE_NOSE_OFFSET und NUKE_TAIL_OFFSET in AirstrikeSystem.java übereinstimmen.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
