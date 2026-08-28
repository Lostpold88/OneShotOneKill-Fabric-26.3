"""Erzeugt den kompakten 3D-Feldgenerator des Pfeilmagneten.

    python tools/generate_arrow_magnet_3d.py

Das Modell zeigt zwei deutlich verschieden gepolte Spulen, einen cyanfarbenen Energiekern,
einen geschlossenen Feldring und einen darüber abgelenkten Pfeil. So bleibt seine Funktion
sowohl in der Hand als auch im Inventar sofort lesbar.
"""

from __future__ import annotations

import pathlib

import modelkit
from modelkit import Atlas, Tile, centred_display, cube, prism, shift

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/oneshotonekill"
TEXTURE_PATH = ASSETS / "textures/item/arrow_magnet_3d.png"
TEXTURE = "oneshotonekill:item/arrow_magnet_3d"
MODEL_PATH = ASSETS / "models/item/arrow_magnet.json"
ITEM_PATH = ASSETS / "items/arrow_magnet.json"


def material(top: tuple[int, int, int], bottom: tuple[int, int, int], grain: int = 5) -> Tile:
    tile = Tile()
    tile.fill_gradient(top, bottom, grain)
    tile.streaks((2, 6, 10, 14), 7)
    return tile


def casing() -> Tile:
    tile = material((48, 58, 72), (18, 24, 34), 7)
    tile.bands(range(3, 16, 6), -11)
    return tile


def metal() -> Tile:
    return material((184, 207, 220), (72, 92, 108), 6)


def red_coil() -> Tile:
    tile = material((255, 82, 65), (112, 15, 24), 8)
    tile.bands(range(1, 16, 4), 22)
    return tile


def blue_coil() -> Tile:
    tile = material((76, 156, 255), (18, 44, 138), 8)
    tile.bands(range(1, 16, 4), 22)
    return tile


def energy() -> Tile:
    tile = material((221, 255, 255), (22, 182, 238), 3)
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            if (x - 7.5) ** 2 + (y - 7.5) ** 2 < 18:
                tile.set(x, y, shift(tile.get(x, y), 35))
    return tile


def wood() -> Tile:
    return material((184, 116, 55), (82, 42, 20), 5)


def feather() -> Tile:
    return material((248, 250, 246), (138, 158, 170), 3)


ATLAS = Atlas({
    "casing": casing,
    "metal": metal,
    "red": red_coil,
    "blue": blue_coil,
    "energy": energy,
    "wood": wood,
    "feather": feather,
}, columns=4)


def build() -> list[dict]:
    elements: list[dict] = []

    # Mehrlagiger Generator: dunkles Gehäuse, Metallkragen und heller Feldkern.
    elements += prism(ATLAS, 8.0, 8.0, 5.2, 5.3, 10.7, "casing", axis="z", sides=12)
    elements += prism(ATLAS, 8.0, 8.0, 4.15, 4.9, 11.1, "metal", axis="z", sides=12)
    elements += prism(ATLAS, 8.0, 8.0, 3.45, 4.65, 11.35, "casing", axis="z", sides=12)
    elements += prism(ATLAS, 8.0, 8.0, 2.25, 4.4, 11.6, "energy", axis="z", sides=12)

    # Rot/Blau machen die beiden Magnetpole auch bei kleiner UI-Skalierung eindeutig.
    elements += prism(ATLAS, 8.0, 8.0, 1.75, 1.35, 4.9, "red", axis="x", sides=8)
    elements += prism(ATLAS, 8.0, 8.0, 1.75, 11.1, 14.65, "blue", axis="x", sides=8)
    elements += prism(ATLAS, 8.0, 8.0, 2.05, 3.9, 5.15, "metal", axis="x", sides=8)
    elements += prism(ATLAS, 8.0, 8.0, 2.05, 10.85, 12.1, "metal", axis="x", sides=8)

    # Zwölf Segmente lesen sich als geschlossener, cyanfarbener Feldring statt als Raster.
    for index in range(12):
        rotation = {"origin": [8.0, 8.0, 8.0], "axis": "z", "angle": index * 30.0}
        elements.append(cube(ATLAS, (6.55, 12.75, 6.85), (9.45, 13.45, 9.15),
                             "energy", rotation=rotation))

    # Ein sichtbar vom Feld abgebogener Pfeil über dem Generator.
    elements.append(cube(ATLAS, (2.0, 13.7, 7.55), (12.6, 14.15, 8.0), "wood",
                         rotation={"origin": [8.0, 13.9, 8.0], "axis": "z", "angle": -12.0}))
    elements.append(cube(ATLAS, (11.8, 13.15, 7.0), (14.35, 14.7, 8.55), "metal",
                         rotation={"origin": [12.2, 13.9, 8.0], "axis": "z", "angle": -12.0}))
    elements.append(cube(ATLAS, (1.35, 12.9, 7.15), (3.5, 13.55, 8.4), "feather",
                         rotation={"origin": [2.5, 13.9, 8.0], "axis": "z", "angle": -12.0}))
    elements.append(cube(ATLAS, (1.35, 14.3, 7.15), (3.5, 14.95, 8.4), "feather",
                         rotation={"origin": [2.5, 13.9, 8.0], "axis": "z", "angle": -12.0}))
    return elements


def displays(elements: list[dict]) -> dict:
    hand = centred_display(elements, (12, -145, 8), 0.55, offset=(1.1, 3.6, 0.0))
    third = centred_display(elements, (12, -145, 8), 0.50, offset=(0.0, 2.5, 0.0))
    return {
        "firstperson_righthand": hand,
        "firstperson_lefthand": dict(hand),
        "thirdperson_righthand": third,
        "thirdperson_lefthand": dict(third),
        "gui": centred_display(elements, (25, -35, 0), 0.90, flat=True),
        "ground": centred_display(elements, (90, 0, 0), 0.56, offset=(0.0, 3.0, 0.0)),
        "fixed": centred_display(elements, (0, 180, 0), 0.92),
    }


def main() -> int:
    elements = build()
    ATLAS.save(TEXTURE_PATH)
    modelkit.write(MODEL_PATH, modelkit.model(TEXTURE, elements, displays(elements)))
    modelkit.write(ITEM_PATH, {
        "model": {"type": "minecraft:model", "model": "oneshotonekill:item/arrow_magnet"}
    })
    low, high = modelkit.bounds(elements)
    print(f"arrow_magnet: {len(elements)} Elemente, {low} .. {high}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
