"""Erzeugt Textur und Modell der Teleport-Granate.

    python tools/generate_teleport_grenade_3d.py

Ausgabe:
  * textures/item/teleport_grenade.png   – Materialatlas
  * models/item/teleport_grenade.json    – die Ladung
  * items/teleport_grenade.json          – Item-Definition; die Energieteile sind einfärbbar

Vorher war sie eine flache Bildtafel. Jetzt ist sie ein Gerät: ein dunkler facettierter Kern,
zwei gegeneinander geneigte Kreiselringe darum, drei Halteklauen und ein Emitter obenauf.

**Nur Kern und Ringe tragen ``tintindex``.** Sie glimmen in der Hand, laden sich im Flug auf und
reißen beim Einschlag hell auf – Gehäuse und Klauen behalten dabei ihre Farbe. Siehe
``item/runtime/ThrownDevices.java``.

**Die Ringe sind aus je zwölf gedrehten Stücken gebaut.** Beliebige Drehwinkel erlaubt das
Modellformat erst seit 26.2; vorher wäre daraus ein Achteck geworden.
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
TEXTURE = "oneshotonekill:item/teleport_grenade"

CENTRE = 8.0
CORE_RADIUS = 3.1
RING_SEGMENTS = 12
RING_RADIUS = 5.4


# ---------------------------------------------------------------------------
# Materialien
# ---------------------------------------------------------------------------


def _shell() -> Tile:
    """Gehäuse: dunkles Violettgrau mit Plattenfugen."""
    tile = Tile()
    tile.fill_gradient((72, 62, 92), (42, 36, 56), 7)
    tile.bands(range(0, modelkit.TILE, 5), -20)
    return tile


def _claw() -> Tile:
    """Halteklauen: blankes, kaltes Metall."""
    tile = Tile()
    tile.fill_gradient((166, 168, 182), (104, 106, 122), 7)
    tile.streaks((3, 4, 11, 12), 20)
    return tile


def _dark() -> Tile:
    """Fugen und Kanten: fast schwarz."""
    tile = Tile()
    tile.fill_gradient((40, 36, 50), (20, 18, 28), 4)
    return tile


def _energy() -> Tile:
    """Energieflächen: fast weiß, damit die Einfärbung den ganzen Farbraum behält.

    Die Ringe im Muster bleiben als Helligkeitsunterschied stehen und überleben jede
    Multiplikation mit einer Farbe.
    """
    tile = Tile()
    tile.fill_gradient((248, 246, 252), (196, 192, 208), 4)
    centre = (modelkit.TILE - 1) / 2.0
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            distance = math.hypot(x - centre, y - centre)
            if int(distance) % 3 == 0:
                tile.set(x, y, shift(tile.get(x, y), -52))
    return tile


def _grid() -> Tile:
    """Emittergitter: dunkles Raster mit hellen Stegen."""
    tile = Tile()
    tile.fill_gradient((54, 48, 70), (30, 27, 40), 4)
    for y in range(0, modelkit.TILE, 3):
        for x in range(modelkit.TILE):
            tile.set(x, y, shift(tile.get(x, y), 40))
    for x in range(0, modelkit.TILE, 3):
        for y in range(modelkit.TILE):
            tile.set(x, y, shift(tile.get(x, y), 26))
    return tile


ATLAS = Atlas({
    "shell": _shell,
    "claw": _claw,
    "dark": _dark,
    "energy": _energy,
    "grid": _grid,
}, columns=3)


# ---------------------------------------------------------------------------
# Geometrie
# ---------------------------------------------------------------------------


def ring(radius: float, tilt: tuple[float, float, float], material: str,
         half_height: float = 0.42, half_depth: float = 0.52) -> list[dict]:
    """Ein Kreisel aus zwölf geraden Stücken, als Ganzes geneigt.

    Die Neigung sitzt in derselben Drehung wie die Verteilung im Kreis: die Euler-Form setzt
    erst X, dann Y, dann Z zusammen, und mit dem Ursprung auf der Modellmitte bleibt der Kreis
    dabei ein Kreis.
    """
    half_width = math.pi * radius / RING_SEGMENTS * 1.2
    elements: list[dict] = []
    for index in range(RING_SEGMENTS):
        elements.append(cube(
            ATLAS,
            (CENTRE - half_width, CENTRE - half_height, CENTRE + radius - half_depth),
            (CENTRE + half_width, CENTRE + half_height, CENTRE + radius + half_depth),
            material,
            rotation={
                "origin": [CENTRE, CENTRE, CENTRE],
                "x": tilt[0],
                "y": round(tilt[1] + index * 360.0 / RING_SEGMENTS, 4),
                "z": tilt[2],
            },
        ))
    return elements


def build() -> list[dict]:
    elements: list[dict] = []

    # Kern: eine Kugel aus fünf Scheiben, dazwischen ein Energieband.
    bands = 5
    for index in range(bands):
        low = -CORE_RADIUS + 2.0 * CORE_RADIUS * index / bands
        high = -CORE_RADIUS + 2.0 * CORE_RADIUS * (index + 1) / bands
        middle = (low + high) / 2.0
        radius = math.sqrt(max(0.3, CORE_RADIUS * CORE_RADIUS - middle * middle))
        material = "energy" if index == 2 else "shell"
        elements += prism(ATLAS, CENTRE, CENTRE, radius, CENTRE + low, CENTRE + high,
                          material, axis="y", sides=10)

    # Zwei gegeneinander geneigte Kreisel.
    elements += ring(RING_RADIUS, (26.0, 0.0, 0.0), "energy")
    elements += ring(RING_RADIUS - 0.9, (-32.0, 18.0, 0.0), "claw", half_height=0.34, half_depth=0.42)

    # Drei Halteklauen, die den Kern greifen.
    for index in range(3):
        rotation = {"origin": [CENTRE, CENTRE, CENTRE], "x": 0.0,
                    "y": round(index * 120.0, 3), "z": 0.0}
        elements.append(cube(
            ATLAS,
            (CENTRE - 0.75, CENTRE - 3.9, CENTRE + 1.9),
            (CENTRE + 0.75, CENTRE + 1.2, CENTRE + 3.3),
            "claw", {"north": "dark"}, rotation))

    # Emitter oben und Fuß unten.
    elements += prism(ATLAS, CENTRE, CENTRE, 1.5, CENTRE + CORE_RADIUS - 0.2, CENTRE + CORE_RADIUS + 1.5,
                      "shell", axis="y", sides=8, face_materials={"up": "grid"})
    elements += prism(ATLAS, CENTRE, CENTRE, 1.1, CENTRE + CORE_RADIUS + 1.5, CENTRE + CORE_RADIUS + 2.2,
                      "energy", axis="y", sides=8)
    elements += prism(ATLAS, CENTRE, CENTRE, 1.9, CENTRE - CORE_RADIUS - 1.2, CENTRE - CORE_RADIUS + 0.2,
                      "dark", axis="y", sides=8, face_materials={"down": "grid"})

    return elements


def mark_tint(elements: list[dict]) -> list[dict]:
    """Nur die Energieflächen werden eingefärbt."""
    wanted = tuple(ATLAS.uv("energy"))
    for element in elements:
        for face in element["faces"].values():
            if tuple(face["uv"]) == wanted:
                face["tintindex"] = 0
    return elements


def display_for(elements: list[dict]) -> dict:
    # Dieselbe Verschiebung nach oben wie bei den übrigen Handgeräten – Vanilla setzt gehaltene
    # Gegenstände rund drei Sechzehntel über den Handpunkt.
    hand = centred_display(elements, (10, -145, 8), 0.5, offset=(1.0, 3.1, 0.0))
    return {
        "firstperson_righthand": hand,
        "firstperson_lefthand": dict(hand),
        "thirdperson_righthand": centred_display(elements, (10, -145, 8), 0.48, offset=(0.0, 2.5, 0.0)),
        "thirdperson_lefthand": centred_display(elements, (10, -145, 8), 0.48, offset=(0.0, 2.5, 0.0)),
        "gui": centred_display(elements, (24, -150, 0), 0.94, flat=True),
        "ground": centred_display(elements, (0, 0, 0), 0.55, offset=(0.0, 3.0, 0.0)),
        "fixed": centred_display(elements, (0, 180, 0), 0.95),
        "head": centred_display(elements, (0, 180, 0), 1.1, offset=(0.0, 13.0, 0.0)),
    }


def main() -> int:
    texture_path = TEXTURES_DIR / "teleport_grenade.png"
    ATLAS.save(texture_path)
    print(f"Atlas: {texture_path.relative_to(ROOT)} "
          f"({texture_path.stat().st_size} B, {ATLAS.size[0]}x{ATLAS.size[1]}, {len(ATLAS.slots)} Materialien)")

    elements = mark_tint(build())
    modelkit.write(MODELS_DIR / "teleport_grenade.json",
                   modelkit.model(TEXTURE, elements, display_for(elements)))
    modelkit.write(ITEMS_DIR / "teleport_grenade.json", {
        "model": {
            "type": "minecraft:model",
            "model": "oneshotonekill:item/teleport_grenade",
            "tints": [{"type": "minecraft:dye", "default": modelkit.opaque(0x8A5CFF)}],
        },
    })

    low, high = modelkit.bounds(elements)
    tinted = sum(1 for element in elements for face in element["faces"].values() if "tintindex" in face)
    print(f"teleport_grenade: {len(elements)} Elemente, Ausdehnung "
          f"{[round(v, 2) for v in low]} .. {[round(v, 2) for v in high]}, {tinted} Flächen einfärbbar")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
