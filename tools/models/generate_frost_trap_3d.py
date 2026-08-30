"""Erzeugt Textur und Modelle der Frost-Falle und ihrer Eiskristalle.

    python tools/generate_frost_trap_3d.py

Ausgabe:
  * textures/item/frost_trap.png     – gemeinsamer Materialatlas
  * models/item/frost_trap.json      – die Mine, flach auf dem Boden
  * models/item/frost_shard.json     – ein Eiskristall für Einschlag und Eiskäfig
  * items/frost_trap.json, items/frost_shard.json

**Die Falle ist mit Absicht flach und dunkel.** Sie soll übersehen werden können: knapp einen
Sechstelblock hoch, mattes Blaugrau, kein Leuchtrand. Der Kern in der Mitte ist das einzige Teil
mit ``tintindex``; die Laufzeit färbt ihn einmal dunkel ein. Er pulsiert nicht, und das Display
benutzt die natürliche Umgebungsbeleuchtung.

**Der Kristall zeigt nach -Z und ist 16 Einheiten lang**, genau wie die Lanze der Railgun. In
der Welt zeigt er damit nach +Z ({@code DisplayRenderer.ItemDisplayRenderer#submitInner} legt
eine halbe Umdrehung auf den Stapel), und die Skalierung in Z ist unmittelbar seine Länge in
Blöcken. Dieselbe Bauweise, damit dieselbe Drehung passt.

**Die Höhe über dem Boden** gibt das Skript aus; sie gehört nach ``Deployables.java``.
"""

from __future__ import annotations

import math
import pathlib
import random

import modelkit
from modelkit import Atlas, Tile, centred_display, cube, dither, prism, shift

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/oneshotonekill"
MODELS_DIR = ASSETS / "models/item"
ITEMS_DIR = ASSETS / "items"
TEXTURES_DIR = ASSETS / "textures/item"
TEXTURE = "oneshotonekill:item/frost_trap"

SEED = 20260820
CENTRE = 8.0

# Die Mine wird mit dem Boden auf y = 0 gebaut und erst am Ende eingemittet.
PLATE_TOP = 1.0
COLLAR_TOP = 1.5
PAD_TOP = 2.1
CORE_TOP = 2.5


# ---------------------------------------------------------------------------
# Materialien
# ---------------------------------------------------------------------------


def _casing() -> Tile:
    """Grundplatte: mattes Blaugrau, das auf jedem Untergrund untergeht."""
    tile = Tile()
    tile.fill_gradient((72, 78, 86), (46, 51, 58), 6)
    tile.bands(range(0, modelkit.TILE, 6), -14)
    return tile


def _rim() -> Tile:
    """Kragen und Kanten: noch dunkler, damit die Falle keinen Umriss wirft."""
    tile = Tile()
    tile.fill_gradient((44, 48, 55), (26, 29, 34), 4)
    return tile


def _pad() -> Tile:
    """Trittplatte: feines Rautenmuster, aus zwei Metern nicht mehr zu erkennen."""
    tile = Tile()
    tile.fill_gradient((84, 91, 100), (58, 64, 72), 5)
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            if (x + y) % 4 == 0 or (x - y) % 4 == 0:
                tile.set(x, y, shift(tile.get(x, y), -20))
    return tile


def _core() -> Tile:
    """Kern: fast weiß, damit die Einfärbung den ganzen Farbraum behält."""
    tile = Tile()
    tile.fill_gradient((238, 244, 246), (196, 206, 212), 4)
    centre = (modelkit.TILE - 1) / 2.0
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            if math.hypot(x - centre, y - centre) < 4.5:
                tile.set(x, y, (255, 255, 255))
    return tile


def _rime() -> Tile:
    """Raureif am Rand der Platte – der einzige helle Fleck an der ganzen Falle."""
    tile = Tile()
    tile.fill_gradient((150, 168, 180), (104, 122, 136), 9)
    rng = random.Random(SEED)
    for _ in range(26):
        x, y = rng.randrange(modelkit.TILE), rng.randrange(modelkit.TILE)
        tile.set(x, y, shift(tile.get(x, y), rng.choice((-34, 46))))
    return tile


def _ice() -> Tile:
    """Kristallfläche: fast weiß mit Facettenkanten, damit die Splitter Struktur haben."""
    tile = Tile()
    tile.fill_gradient((250, 252, 254), (206, 220, 232), 5)
    for step in range(modelkit.TILE):
        tile.set(step, step, shift(tile.get(step, step), 30))
        tile.set(modelkit.TILE - 1 - step, step, shift(tile.get(modelkit.TILE - 1 - step, step), -38))
    for x in range(0, modelkit.TILE, 6):
        for y in range(modelkit.TILE):
            tile.set(x, y, shift(tile.get(x, y), -22))
    return tile


ATLAS = Atlas({
    "casing": _casing,
    "rim": _rim,
    "pad": _pad,
    "core": _core,
    "rime": _rime,
    "ice": _ice,
}, columns=3)


# ---------------------------------------------------------------------------
# Die Mine
# ---------------------------------------------------------------------------


def build_trap() -> list[dict]:
    """Grundplatte, Kragen, Trittplatte, Kern – vier flache Scheiben übereinander."""
    elements: list[dict] = []

    elements += prism(ATLAS, CENTRE, CENTRE, 5.0, 0.0, PLATE_TOP, "casing",
                      axis="y", sides=12, face_materials={"up": "pad", "down": "rim"})
    # Ein schmaler Reifring am Plattenrand. Er sitzt tiefer als die Platte, damit er nur von
    # der Seite zu sehen ist – von oben bleibt die Falle einfarbig.
    elements += prism(ATLAS, CENTRE, CENTRE, 5.15, 0.1, 0.55, "rime", axis="y", sides=12)

    elements += prism(ATLAS, CENTRE, CENTRE, 3.4, PLATE_TOP, COLLAR_TOP, "rim", axis="y", sides=12)
    elements += prism(ATLAS, CENTRE, CENTRE, 2.7, COLLAR_TOP, PAD_TOP, "pad",
                      axis="y", sides=12, face_materials={"up": "pad"})
    elements += prism(ATLAS, CENTRE, CENTRE, 1.15, PAD_TOP, CORE_TOP, "core", axis="y", sides=8)

    # Drei Düsen am Rand, aus denen der Frost fährt. Winzig, aber sie machen aus der Scheibe
    # ein Gerät.
    for index in range(3):
        rotation = {"origin": [CENTRE, 0.0, CENTRE], "axis": "y", "angle": round(index * 120.0, 3)}
        elements.append(cube(ATLAS, (CENTRE - 0.6, PLATE_TOP - 0.1, CENTRE + 3.3),
                             (CENTRE + 0.6, PLATE_TOP + 0.7, CENTRE + 4.7), "rim", {"up": "rime"}, rotation))

    return elements


# ---------------------------------------------------------------------------
# Der Kristall
# ---------------------------------------------------------------------------

SHARD_PROFILE = ((0.0, 0.0), (2.2, 0.7), (5.0, 1.3), (11.0, 1.55), (14.5, 1.1), (16.0, 0.55))


def build_shard() -> list[dict]:
    """Ein sechseckiger Kristall, vorn spitz und hinten stumpf abgesetzt.

    Sechs Seiten statt acht: Eis bricht kantig, und mit weniger Flächen bleibt der Splitter
    auch dann noch lesbar, wenn ein Dutzend davon gleichzeitig um einen Spieler steht.
    """
    elements: list[dict] = []
    for index in range(len(SHARD_PROFILE) - 1):
        start, radius = SHARD_PROFILE[index]
        end, next_radius = SHARD_PROFILE[index + 1]
        thickness = max(0.14, (radius + next_radius) / 2.0)
        elements += prism(ATLAS, CENTRE, CENTRE, thickness, start, end, "ice", axis="z", sides=6)

    # Zwei kleinere Kristalle seitlich am Fuß – ohne sie sieht der Splitter aus wie ein Zapfen.
    for side, angle in ((-1, 28.0), (1, -34.0)):
        elements += prism(ATLAS, CENTRE + side * 1.1, CENTRE, 0.62, 8.0, 13.4, "ice", axis="z", sides=6)
        elements[-1]["rotation"] = {"origin": [CENTRE + side * 1.1, CENTRE, 12.0], "axis": "y", "angle": angle}

    return elements


def mark_tint(elements: list[dict], materials: tuple[str, ...]) -> list[dict]:
    """Meldet die Flächen der genannten Materialien für die Einfärbung an."""
    wanted = {tuple(ATLAS.uv(material)) for material in materials}
    for element in elements:
        for face in element["faces"].values():
            if tuple(face["uv"]) in wanted:
                face["tintindex"] = 0
    return elements


# ---------------------------------------------------------------------------
# Einmitten und Ausgabe
# ---------------------------------------------------------------------------


def anchor_at(elements: list[dict], anchor_y: float) -> float:
    """Verschiebt das Modell so, dass ``anchor_y`` auf der Modellmitte liegt."""
    shift_y = CENTRE - anchor_y
    for element in elements:
        element["from"][1] += shift_y
        element["to"][1] += shift_y
        rotation = element.get("rotation")
        if rotation is not None:
            rotation["origin"][1] += shift_y
    return anchor_y / 16.0


def display_for(elements: list[dict], rotation: tuple[float, float, float]) -> dict:
    hand = centred_display(elements, rotation, 0.5, offset=(1.2, 3.1, 0.0))
    return {
        "firstperson_righthand": hand,
        "firstperson_lefthand": dict(hand),
        "thirdperson_righthand": centred_display(elements, rotation, 0.48, offset=(0.0, 2.5, 0.0)),
        "thirdperson_lefthand": centred_display(elements, rotation, 0.48, offset=(0.0, 2.5, 0.0)),
        "gui": centred_display(elements, (28, -145, 0), 0.94, flat=True),
        "ground": centred_display(elements, (0, 0, 0), 0.55, offset=(0.0, 3.0, 0.0)),
        "fixed": centred_display(elements, (0, 180, 0), 0.95),
        "head": centred_display(elements, (0, 180, 0), 1.1, offset=(0.0, 13.0, 0.0)),
    }


def main() -> int:
    texture_path = TEXTURES_DIR / "frost_trap.png"
    ATLAS.save(texture_path)
    print(f"Atlas: {texture_path.relative_to(ROOT)} "
          f"({texture_path.stat().st_size} B, {ATLAS.size[0]}x{ATLAS.size[1]}, {len(ATLAS.slots)} Materialien)")

    trap = mark_tint(build_trap(), ("core",))
    shard = mark_tint(build_shard(), ("ice",))
    lift = anchor_at(trap, CORE_TOP / 2.0)

    for name, elements, rotation in (("frost_trap", trap, (22, -150, 0)), ("frost_shard", shard, (0, -140, 24))):
        modelkit.write(MODELS_DIR / f"{name}.json",
                       modelkit.model(TEXTURE, elements, display_for(elements, rotation)))
        modelkit.write(ITEMS_DIR / f"{name}.json", {
            "model": {
                "type": "minecraft:model",
                "model": f"oneshotonekill:item/{name}",
                "tints": [{"type": "minecraft:dye", "default": modelkit.opaque(0x9FD8EE)}],
            },
        })
        low, high = modelkit.bounds(elements)
        tinted = sum(1 for element in elements for face in element["faces"].values() if "tintindex" in face)
        print(f"{name:12} {len(elements):3} Elemente, Ausdehnung "
              f"{[round(v, 2) for v in low]} .. {[round(v, 2) for v in high]}, {tinted} Flächen einfärbbar")

    print(f"\nDie Falle sitzt {lift:.4f} Blöcke über dem Boden – muss mit TRAP_LIFT in")
    print("item/runtime/Deployables.java übereinstimmen, sonst steckt sie darin oder schwebt.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
