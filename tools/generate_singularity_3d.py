"""Erzeugt das 3D-Modell des werfbaren Singularitätskerns.

    python tools/generate_singularity_3d.py

Der Gegenstand ist kein flaches Schwarzes-Loch-Symbol mehr, sondern ein versiegelter
Gravitationskern: facettierter Ereignishorizont, drei verschiedenfarbige Energiebahnen,
Stabilisatorklammern und ein heller innerer Ring. Dasselbe Modell rotiert sichtbar im Flug
und wächst nach dem Einschlag zum Zentrum des Singularitätsfeldes.
"""

from __future__ import annotations

import pathlib

import modelkit
from modelkit import Atlas, Tile, centred_display, cube, prism, shift

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/oneshotonekill"
TEXTURE_PATH = ASSETS / "textures/item/singularity_3d.png"
TEXTURE = "oneshotonekill:item/singularity_3d"
MODEL_PATH = ASSETS / "models/item/singularity.json"
ITEM_PATH = ASSETS / "items/singularity.json"
CENTRE = 8.0


def gradient(top: tuple[int, int, int], bottom: tuple[int, int, int], grain: int = 5) -> Tile:
    tile = Tile()
    tile.fill_gradient(top, bottom, grain)
    tile.streaks((2, 6, 10, 14), 7)
    return tile


def event_horizon() -> Tile:
    tile = gradient((20, 13, 34), (1, 1, 5), 5)
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            if (x + y) % 7 == 0:
                tile.set(x, y, shift(tile.get(x, y), 10))
    return tile


def inner_void() -> Tile:
    tile = Tile()
    tile.fill_gradient((5, 4, 9), (0, 0, 1), 1)
    return tile


def violet() -> Tile:
    return gradient((244, 105, 255), (82, 15, 166), 5)


def cyan() -> Tile:
    return gradient((198, 255, 255), (18, 141, 236), 4)


def magenta() -> Tile:
    return gradient((255, 92, 184), (128, 12, 82), 5)


def metal() -> Tile:
    tile = gradient((132, 142, 166), (35, 40, 56), 7)
    tile.bands(range(2, 16, 5), 15)
    return tile


def hot_core() -> Tile:
    tile = gradient((255, 255, 255), (136, 87, 255), 3)
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            if (x - 7.5) ** 2 + (y - 7.5) ** 2 < 16:
                tile.set(x, y, (255, 255, 255))
    return tile


ATLAS = Atlas({
    "horizon": event_horizon,
    "void": inner_void,
    "violet": violet,
    "cyan": cyan,
    "magenta": magenta,
    "metal": metal,
    "core": hot_core,
}, columns=4)


def sphere_layers(material: str, scale: float = 1.0) -> list[dict]:
    """Zwölfseitige Scheiben ergeben eine runde, aber bewusst facettierte Gravitätskugel."""
    profile = (
        (4.55, 5.25, 1.35),
        (5.25, 6.15, 2.25),
        (6.15, 7.15, 2.85),
        (7.15, 8.85, 3.20),
        (8.85, 9.85, 2.85),
        (9.85, 10.75, 2.25),
        (10.75, 11.45, 1.35),
    )
    elements: list[dict] = []
    for low, high, radius in profile:
        elements += prism(ATLAS, CENTRE, CENTRE, radius * scale,
                          CENTRE + (low - CENTRE) * scale,
                          CENTRE + (high - CENTRE) * scale,
                          material, axis="y", sides=12)
    return elements


def orbital_ring(axis: str, material: str, radius: float, width: float,
                 thickness: float, phase: float = 0.0) -> list[dict]:
    elements: list[dict] = []
    for index in range(12):
        angle = phase + index * 30.0
        rotation = {"origin": [CENTRE, CENTRE, CENTRE], "axis": axis, "angle": angle}
        if axis == "y":
            start, end = (CENTRE - width, CENTRE - thickness, CENTRE + radius), (
                CENTRE + width, CENTRE + thickness, CENTRE + radius + 0.8)
        elif axis == "z":
            start, end = (CENTRE - width, CENTRE + radius, CENTRE - thickness), (
                CENTRE + width, CENTRE + radius + 0.8, CENTRE + thickness)
        else:
            start, end = (CENTRE - thickness, CENTRE + radius, CENTRE - width), (
                CENTRE + thickness, CENTRE + radius + 0.8, CENTRE + width)
        elements.append(cube(ATLAS, start, end, material, rotation=rotation))
    return elements


def build() -> list[dict]:
    elements = sphere_layers("horizon")
    # Tiefschwarzer Kern in der Vorder- und Rückseite verstärkt den Ereignishorizont.
    elements += prism(ATLAS, CENTRE, CENTRE, 1.65, 4.05, 4.5, "void", axis="z", sides=12)
    elements += prism(ATLAS, CENTRE, CENTRE, 1.65, 11.5, 11.95, "void", axis="z", sides=12)
    elements += prism(ATLAS, CENTRE, CENTRE, 0.72, 3.75, 4.02, "core", axis="z", sides=12)

    # Drei gegeneinander laufende Bahnen bilden den Stabilisator-Käfig.
    elements += orbital_ring("y", "violet", 4.55, 1.25, 0.28)
    elements += orbital_ring("z", "cyan", 4.85, 1.15, 0.24, 15.0)
    elements += orbital_ring("x", "magenta", 5.15, 1.05, 0.22, -15.0)

    # Vier massive Klammern lassen das Item wie ein gebautes Gerät statt eine reine Kugel wirken.
    for angle in range(0, 360, 90):
        elements.append(cube(ATLAS, (7.25, 12.3, 6.9), (8.75, 14.6, 9.1), "metal",
                             rotation={"origin": [8.0, 8.0, 8.0], "axis": "z", "angle": angle}))
        elements.append(cube(ATLAS, (7.55, 11.7, 7.2), (8.45, 12.65, 8.8), "core",
                             rotation={"origin": [8.0, 8.0, 8.0], "axis": "z", "angle": angle}))
    return elements


def displays(elements: list[dict]) -> dict:
    hand = centred_display(elements, (18, -140, 8), 0.56, offset=(1.1, 3.15, 0.0))
    third = centred_display(elements, (18, -140, 8), 0.50, offset=(0.0, 2.5, 0.0))
    return {
        "firstperson_righthand": hand,
        "firstperson_lefthand": dict(hand),
        "thirdperson_righthand": third,
        "thirdperson_lefthand": dict(third),
        "gui": centred_display(elements, (24, -35, -8), 0.92, flat=True),
        "ground": centred_display(elements, (20, 0, 0), 0.56, offset=(0.0, 3.0, 0.0)),
        "fixed": centred_display(elements, (0, 180, 0), 0.96),
    }


def main() -> int:
    elements = build()
    ATLAS.save(TEXTURE_PATH)
    modelkit.write(MODEL_PATH, modelkit.model(TEXTURE, elements, displays(elements)))
    modelkit.write(ITEM_PATH, {
        "model": {"type": "minecraft:model", "model": "oneshotonekill:item/singularity"}
    })
    low, high = modelkit.bounds(elements)
    print(f"singularity: {len(elements)} Elemente, "
          f"{[round(v, 2) for v in low]} .. {[round(v, 2) for v in high]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
