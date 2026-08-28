"""Erzeugt Textur und Modell der Rauchgranate.

    python tools/generate_smoke_bomb_3d.py

Ausgabe:
  * textures/item/smoke_bomb.png   – Materialatlas
  * models/item/smoke_bomb.json    – die Dose
  * items/smoke_bomb.json          – Item-Definition; der Glutstreifen ist einfärbbar

Vorher war die Granate eine flache Bildtafel. Jetzt ist sie eine Dose mit Rippen, Farbband,
Auslassöffnungen, Zündkopf, Hebel und Abzugsring.

**Nur der Glutstreifen trägt ``tintindex``.** Solange die Dose fliegt, bleibt er dunkel; auf dem
Boden glüht er, während sie ausbrennt. Blech und Bänder behalten dabei ihre Farbe – siehe
``item/runtime/ThrownDevices.java``.

**Die Dose steht auf der Hochachse.** Sie liegt später auf dem Boden und bläst nach oben ab; die
Laufzeit dreht sie dafür einmal quer und lässt sie dort liegen.
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
TEXTURE = "oneshotonekill:item/smoke_bomb"

CENTRE = 8.0
BODY_LOW = 1.4
BODY_HIGH = 11.2
BODY_RADIUS = 2.9
CAP_RADIUS = 2.75
HEAD_HIGH = 13.4


# ---------------------------------------------------------------------------
# Materialien
# ---------------------------------------------------------------------------


def _body() -> Tile:
    """Blech der Dose: mattes Olivgrün mit Längsschliff."""
    tile = Tile()
    tile.fill_gradient((94, 102, 76), (58, 65, 46), 8)
    tile.streaks((1, 2, 7, 8, 13, 14), 12)
    return tile


def _rib() -> Tile:
    """Sicken um den Körper: dunkler, damit sie sich als Kante absetzen."""
    tile = Tile()
    tile.fill_gradient((66, 72, 54), (38, 43, 32), 5)
    for y in range(0, modelkit.TILE, 4):
        for x in range(modelkit.TILE):
            tile.set(x, y, shift(tile.get(x, y), 26))
    return tile


def _cap() -> Tile:
    """Deckel und Boden: dunkles, blankes Metall."""
    tile = Tile()
    tile.fill_gradient((92, 96, 102), (52, 56, 62), 6)
    for x, y in ((3, 3), (12, 3), (3, 12), (12, 12)):
        tile.set(x, y, (140, 146, 154))
    return tile


def _band() -> Tile:
    """Farbband: die Kennung für die Rauchfarbe, hier hellgrau."""
    tile = Tile()
    tile.fill_gradient((214, 218, 222), (168, 174, 180), 6)
    for y in range(modelkit.TILE):
        for x in range(0, modelkit.TILE, 6):
            tile.set(x, y, shift(tile.get(x, y), -46))
    return tile


def _glow() -> Tile:
    """Glutstreifen: fast weiß, damit die Einfärbung den ganzen Farbraum behält."""
    tile = Tile()
    tile.fill_gradient((246, 248, 246), (206, 210, 208), 4)
    for y in range(2, modelkit.TILE, 5):
        for x in range(modelkit.TILE):
            tile.set(x, y, shift(tile.get(x, y), -50))
    return tile


def _steel() -> Tile:
    """Hebel und Ring: blanker Stahl."""
    tile = Tile()
    tile.fill_gradient((178, 184, 192), (118, 124, 132), 7)
    tile.streaks((4, 5, 11, 12), 20)
    return tile


ATLAS = Atlas({
    "body": _body,
    "rib": _rib,
    "cap": _cap,
    "band": _band,
    "glow": _glow,
    "steel": _steel,
}, columns=3)


# ---------------------------------------------------------------------------
# Geometrie
# ---------------------------------------------------------------------------

RING_SEGMENTS = 8
RING_RADIUS = 1.9


def build() -> list[dict]:
    elements: list[dict] = []

    # Körper und Deckel.
    elements += prism(ATLAS, CENTRE, CENTRE, BODY_RADIUS, BODY_LOW, BODY_HIGH, "body", axis="y", sides=12)
    elements += prism(ATLAS, CENTRE, CENTRE, CAP_RADIUS, 0.8, BODY_LOW, "cap", axis="y", sides=12)
    elements += prism(ATLAS, CENTRE, CENTRE, CAP_RADIUS, BODY_HIGH, BODY_HIGH + 1.1, "cap",
                      axis="y", sides=12, face_materials={"up": "cap"})

    # Drei Sicken und das Farbband dazwischen.
    for height in (3.2, 8.6):
        elements += prism(ATLAS, CENTRE, CENTRE, BODY_RADIUS + 0.22, height, height + 0.55, "rib",
                          axis="y", sides=12)
    elements += prism(ATLAS, CENTRE, CENTRE, BODY_RADIUS + 0.1, 5.2, 6.8, "band", axis="y", sides=12)

    # Der Glutstreifen sitzt tief am Körper – auf dem Boden liegend zeigt er nach außen.
    elements += prism(ATLAS, CENTRE, CENTRE, BODY_RADIUS + 0.14, 1.9, 2.7, "glow", axis="y", sides=12)

    # Vier Auslassöffnungen im Deckel.
    for index in range(4):
        rotation = {"origin": [CENTRE, BODY_HIGH, CENTRE], "axis": "y", "angle": round(index * 90.0 + 45.0, 3)}
        elements.append(cube(ATLAS, (CENTRE - 0.5, BODY_HIGH + 0.2, CENTRE + 1.1),
                             (CENTRE + 0.5, BODY_HIGH + 1.3, CENTRE + 2.1), "cap", {"up": "rib"}, rotation))

    # Zündkopf.
    elements += prism(ATLAS, CENTRE, CENTRE, 1.15, BODY_HIGH + 1.1, HEAD_HIGH, "steel", axis="y", sides=8)

    # Hebel: eine Blechlasche, die am Kopf ansetzt und schräg am Körper herunterläuft.
    elements.append(cube(
        ATLAS,
        (CENTRE - 0.7, 5.4, CENTRE + BODY_RADIUS - 0.1),
        (CENTRE + 0.7, HEAD_HIGH - 0.4, CENTRE + BODY_RADIUS + 0.5),
        "steel",
        rotation={"origin": [CENTRE, HEAD_HIGH - 0.4, CENTRE + BODY_RADIUS], "axis": "x", "angle": -7.0},
    ))

    # Abzugsring, flach über dem Kopf. Acht gedrehte Stücke ergeben einen runden Ring – seit
    # 26.2 sind dafür beliebige Winkel erlaubt.
    half_width = math.pi * RING_RADIUS / RING_SEGMENTS * 1.2
    for index in range(RING_SEGMENTS):
        elements.append(cube(
            ATLAS,
            (CENTRE - half_width, HEAD_HIGH - 0.25, CENTRE + RING_RADIUS - 0.3),
            (CENTRE + half_width, HEAD_HIGH + 0.25, CENTRE + RING_RADIUS + 0.3),
            "steel",
            rotation={"origin": [CENTRE, HEAD_HIGH, CENTRE], "axis": "y",
                      "angle": round(index * 360.0 / RING_SEGMENTS, 4)},
        ))

    return elements


def mark_tint(elements: list[dict]) -> list[dict]:
    """Nur der Glutstreifen wird eingefärbt; alles andere behält seine Materialfarbe."""
    wanted = tuple(ATLAS.uv("glow"))
    for element in elements:
        for face in element["faces"].values():
            if tuple(face["uv"]) == wanted:
                face["tintindex"] = 0
    return elements


def display_for(elements: list[dict]) -> dict:
    # Die Verschiebung nach oben ist dieselbe wie bei den übrigen Handgeräten der Mod: Vanilla
    # setzt gehaltene Gegenstände rund drei Sechzehntel über den Handpunkt, und wer hier kleiner
    # rechnet, lässt die Waffe am unteren Bildrand hängen.
    hand = centred_display(elements, (10, -145, 8), 0.54, offset=(1.0, 3.1, 0.0))
    return {
        "firstperson_righthand": hand,
        "firstperson_lefthand": dict(hand),
        "thirdperson_righthand": centred_display(elements, (10, -145, 8), 0.5, offset=(0.0, 2.5, 0.0)),
        "thirdperson_lefthand": centred_display(elements, (10, -145, 8), 0.5, offset=(0.0, 2.5, 0.0)),
        "gui": centred_display(elements, (26, -148, 0), 0.94, flat=True),
        "ground": centred_display(elements, (0, 0, 0), 0.55, offset=(0.0, 3.0, 0.0)),
        "fixed": centred_display(elements, (0, 180, 0), 0.95),
        "head": centred_display(elements, (0, 180, 0), 1.1, offset=(0.0, 13.0, 0.0)),
    }


def main() -> int:
    texture_path = TEXTURES_DIR / "smoke_bomb.png"
    ATLAS.save(texture_path)
    print(f"Atlas: {texture_path.relative_to(ROOT)} "
          f"({texture_path.stat().st_size} B, {ATLAS.size[0]}x{ATLAS.size[1]}, {len(ATLAS.slots)} Materialien)")

    elements = mark_tint(build())
    modelkit.write(MODELS_DIR / "smoke_bomb.json", modelkit.model(TEXTURE, elements, display_for(elements)))
    modelkit.write(ITEMS_DIR / "smoke_bomb.json", {
        "model": {
            "type": "minecraft:model",
            "model": "oneshotonekill:item/smoke_bomb",
            # Ohne Farbe im Stapel bleibt der Streifen dunkel – so liegt die Granate im Inventar.
            "tints": [{"type": "minecraft:dye", "default": modelkit.opaque(0x3A2A22)}],
        },
    })

    low, high = modelkit.bounds(elements)
    tinted = sum(1 for element in elements for face in element["faces"].values() if "tintindex" in face)
    print(f"smoke_bomb: {len(elements)} Elemente, Ausdehnung "
          f"{[round(v, 2) for v in low]} .. {[round(v, 2) for v in high]}, {tinted} Flächen einfärbbar")
    print(f"Höhe der Dose: {(high[1] - low[1]) / 16.0:.4f} Blöcke bei Skalierung 1, "
          f"Durchmesser {(high[0] - low[0]) / 16.0:.4f}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
