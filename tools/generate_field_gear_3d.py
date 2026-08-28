"""Erzeugt Textur und Modelle der Feldgeräte: Luftangriff, Peilsender und Tarnmantel.

    python tools/generate_field_gear_3d.py

Ausgabe:
  * textures/item/field_gear.png        – gemeinsamer Materialatlas
  * models/item/airstrike.json          – Zielmarkierer mit Klappantenne und Schirm
  * models/item/c4.json                 – Zuendkasten: die C4 in der Hand
  * models/item/radar_pulse.json        – Peilsender mit Schüssel und Suchbalken
  * models/item/invisibility_cloak.json – gerollter Tarnmantel mit Feldgeber
  * die zugehörigen items/*.json

Alle Geräte teilen sich einen Atlas, weil sie aus demselben Material bestehen: Gehäuse,
Gummikanten, Tastenfeld, Antenne. Ein zweiter Satz Kacheln wäre dieselbe Farbe unter neuem
Namen.

**Nur die Anzeigen tragen ``tintindex``.** Schirm, Melder und Leuchtring blinken zur Laufzeit
über ``DyedItemColor``; Gehäuse und Antenne behalten ihre Farbe. Getrieben wird das von
``item/runtime/DeviceLights.java``.

**Beide liegen auf derselben Höhe in der Hand** wie die übrigen Handgeräte der Mod: Vanilla
setzt gehaltene Gegenstände rund drei Sechzehntel über den Handpunkt, und wer kleiner rechnet,
lässt sie am unteren Bildrand hängen.
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
TEXTURE = "oneshotonekill:item/field_gear"

CENTRE = 8.0


# ---------------------------------------------------------------------------
# Materialien
# ---------------------------------------------------------------------------


def _case() -> Tile:
    """Gehäuse: mattes Olivgrau mit Plattenfugen."""
    tile = Tile()
    tile.fill_gradient((92, 96, 84), (56, 60, 52), 7)
    tile.bands(range(0, modelkit.TILE, 5), -20)
    return tile


def _rubber() -> Tile:
    """Gummikanten: fast schwarz, grob genarbt."""
    tile = Tile()
    tile.fill_gradient((46, 46, 44), (24, 24, 23), 4)
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            if (x * 3 + y * 5) % 7 == 0:
                tile.set(x, y, shift(tile.get(x, y), 22))
    return tile


def _keys() -> Tile:
    """Tastenfeld: dunkle Platte mit hellen Tastenköpfen."""
    tile = Tile()
    tile.fill_gradient((38, 40, 44), (22, 24, 27), 4)
    for row in range(3):
        for column in range(3):
            x, y = 3 + column * 4, 3 + row * 4
            for dx in range(3):
                for dy in range(3):
                    tile.set(x + dx, y + dy, (104, 108, 116) if (row + column) % 2 == 0 else (76, 80, 88))
    return tile


def _screen() -> Tile:
    """Anzeigefläche: fast weiß mit Rasterzeilen, damit die Einfärbung alles hergibt."""
    tile = Tile()
    tile.fill_gradient((246, 250, 250), (204, 214, 216), 4)
    for y in range(1, modelkit.TILE, 2):
        for x in range(modelkit.TILE):
            tile.set(x, y, shift(tile.get(x, y), -58))
    for y, length in ((3, 10), (7, 13), (11, 7)):
        for x in range(length):
            tile.set(x + 1, y, shift(tile.get(x + 1, y), 44))
    return tile


def _steel() -> Tile:
    """Antenne, Bügel und Schlüssel: blanker Stahl."""
    tile = Tile()
    tile.fill_gradient((176, 182, 190), (114, 120, 128), 7)
    tile.streaks((3, 4, 11, 12), 20)
    return tile


def _warn() -> Tile:
    """Warnband um den Drücker."""
    tile = Tile()
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            hot = ((x + y) // 4) % 2 == 0
            tile.set(x, y, shift((208, 168, 40) if hot else (28, 26, 22), dither(x, y, 8)))
    return tile


def _cloth() -> Tile:
    """Manteltuch: dunkles Grau mit sichtbarem Faltenwurf."""
    tile = Tile()
    tile.fill_gradient((86, 88, 100), (48, 50, 60), 8)
    for x in range(0, modelkit.TILE, 3):
        for y in range(modelkit.TILE):
            tile.set(x, y, shift(tile.get(x, y), -26))
            tile.set(min(modelkit.TILE - 1, x + 1), y, shift(tile.get(min(modelkit.TILE - 1, x + 1), y), 18))
    return tile


def _weave() -> Tile:
    """Innenfutter: feiner gewebt und eine Spur heller als das Tuch."""
    tile = Tile()
    tile.fill_gradient((112, 116, 130), (72, 76, 88), 6)
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            if (x + y) % 2 == 0:
                tile.set(x, y, shift(tile.get(x, y), 14))
    return tile


ATLAS = Atlas({
    "case": _case,
    "rubber": _rubber,
    "keys": _keys,
    "screen": _screen,
    "steel": _steel,
    "warn": _warn,
    "cloth": _cloth,
    "weave": _weave,
}, columns=3)


# ---------------------------------------------------------------------------
# Der Zielmarkierer
# ---------------------------------------------------------------------------


def build_airstrike() -> list[dict]:
    """Ein Funkgerät mit aufgeklapptem Schirm, Tastenfeld und Peitschenantenne."""
    elements: list[dict] = []

    # Gehäuse mit Gummikanten oben und unten.
    elements.append(cube(ATLAS, (CENTRE - 2.6, 1.6, CENTRE - 1.6), (CENTRE + 2.6, 10.4, CENTRE + 1.6),
                         "case", {"north": "keys"}))
    for low, high in ((1.2, 2.0), (10.0, 10.8)):
        elements.append(cube(ATLAS, (CENTRE - 2.8, low, CENTRE - 1.8), (CENTRE + 2.8, high, CENTRE + 1.8), "rubber"))

    # Tastenfeld auf der Vorderseite, leicht erhaben.
    elements.append(cube(ATLAS, (CENTRE - 1.9, 2.6, CENTRE - 1.9), (CENTRE + 1.9, 6.2, CENTRE - 1.55), "keys"))

    # Der Schirm klappt aus dem Gehäuse nach hinten weg – das Kennzeichen des Geräts.
    elements.append(cube(
        ATLAS,
        (CENTRE - 2.5, 10.4, CENTRE - 0.5),
        (CENTRE + 2.5, 16.2, CENTRE + 0.1),
        "case", {"north": "screen"},
        rotation={"origin": [CENTRE, 10.4, CENTRE], "axis": "x", "angle": -22.0},
    ))
    # Melderreihe unter dem Schirm.
    for index in range(3):
        x = CENTRE - 1.7 + index * 1.6
        elements.append(cube(ATLAS, (x, 8.4, CENTRE - 1.75), (x + 0.9, 9.3, CENTRE - 1.5), "screen"))

    # Peitschenantenne. Sie steht senkrecht: ein Sechskantprisma besteht aus drei gegeneinander
    # gedrehten Balken, und eine zusätzliche Neigung bekäme nur einer von ihnen – der Rest
    # bliebe stehen und das Prisma fiele auseinander. Ein Element trägt genau eine Drehung.
    elements += prism(ATLAS, CENTRE + 1.9, CENTRE + 0.9, 0.3, 10.4, 19.4, "steel", axis="y", sides=6)
    elements += prism(ATLAS, CENTRE + 1.9, CENTRE + 0.9, 0.52, 10.2, 11.4, "rubber", axis="y", sides=6)

    # Lautsprechergitter und Tragebügel an den Flanken.
    elements.append(cube(ATLAS, (CENTRE - 2.75, 6.6, CENTRE - 1.2), (CENTRE - 2.55, 9.4, CENTRE + 1.2), "keys"))
    elements.append(cube(ATLAS, (CENTRE - 3.3, 4.2, CENTRE - 0.4), (CENTRE - 2.6, 8.6, CENTRE + 0.4), "steel"))
    elements.append(cube(ATLAS, (CENTRE + 2.6, 4.2, CENTRE - 0.4), (CENTRE + 3.3, 8.6, CENTRE + 0.4), "steel"))

    # Ein zweiter, kleiner Schirm auf der Rückseite – die Peilanzeige.
    elements.append(cube(ATLAS, (CENTRE - 1.6, 6.8, CENTRE + 1.6), (CENTRE + 1.6, 9.6, CENTRE + 1.85), "screen"))

    return elements


# ---------------------------------------------------------------------------
# Der Zuendkasten – die C4 in der Hand
# ---------------------------------------------------------------------------


def build_detonator() -> list[dict]:
    """Ein flacher Zuendkasten mit Schluesselschalter, Leuchtring und grossem Druecker.

    Das ist die Form, die der Spieler in der Hand haelt. Die Haftladung selbst sieht ganz
    anders aus und entsteht in ``tools/generate_c4_3d.py`` als ``c4_charge`` – der Gegenstand
    ist seit dem Wegfall des eigenstaendigen Fernzuenders beides zugleich, und in der Hand
    gewinnt der Zuender: Man traegt das Geraet und klebt die Ladung damit an die Wand.
    """
    elements: list[dict] = []

    # Kasten, liegend.
    elements.append(cube(ATLAS, (CENTRE - 3.4, 4.0, CENTRE - 2.4), (CENTRE + 3.4, 8.6, CENTRE + 2.4),
                         "case", {"up": "keys"}))
    for low, high in ((3.6, 4.4), (8.2, 9.0)):
        elements.append(cube(ATLAS, (CENTRE - 3.6, low, CENTRE - 2.6), (CENTRE + 3.6, high, CENTRE + 2.6), "rubber"))

    # Statusband an der Vorderkante – die eigentliche Anzeige.
    elements.append(cube(ATLAS, (CENTRE - 2.6, 5.2, CENTRE - 2.75), (CENTRE + 2.6, 6.6, CENTRE - 2.45), "screen"))

    # Warnring und Drücker obenauf.
    elements += prism(ATLAS, CENTRE, CENTRE, 2.1, 8.6, 9.2, "warn", axis="y", sides=12)
    elements += prism(ATLAS, CENTRE, CENTRE, 1.6, 9.2, 10.6, "screen", axis="y", sides=12)

    # Schlüsselschalter neben dem Drücker.
    elements += prism(ATLAS, CENTRE - 2.4, CENTRE + 1.3, 0.7, 8.6, 9.4, "steel", axis="y", sides=8)
    elements.append(cube(ATLAS, (CENTRE - 2.7, 9.4, CENTRE + 1.15), (CENTRE - 2.1, 11.2, CENTRE + 1.45), "steel",
                         rotation={"origin": [CENTRE - 2.4, 9.4, CENTRE + 1.3], "axis": "z", "angle": 24.0}))

    # Kurze Stummelantenne hinten.
    elements += prism(ATLAS, CENTRE + 2.6, CENTRE - 1.6, 0.34, 8.6, 14.4, "steel", axis="y", sides=6)

    return elements


def build_radar() -> list[dict]:
    """Peilsender: Sockel mit Schirm, darüber eine Schüssel mit Suchbalken."""
    elements: list[dict] = []

    # Sockel.
    elements.append(cube(ATLAS, (CENTRE - 2.4, 1.4, CENTRE - 2.0), (CENTRE + 2.4, 6.2, CENTRE + 2.0),
                         "case", {"north": "keys"}))
    elements.append(cube(ATLAS, (CENTRE - 2.6, 1.0, CENTRE - 2.2), (CENTRE + 2.6, 1.9, CENTRE + 2.2), "rubber"))
    elements.append(cube(ATLAS, (CENTRE - 1.8, 2.4, CENTRE - 2.15), (CENTRE + 1.8, 5.2, CENTRE - 1.9), "screen"))

    # Mast.
    elements += prism(ATLAS, CENTRE, CENTRE, 0.6, 6.2, 8.6, "steel", axis="y", sides=8)

    # Schüssel: drei Ringe, nach oben weiter – von der Seite ein Parabolschnitt.
    for index, (low, high, radius) in enumerate(((8.6, 9.4, 2.2), (9.4, 10.2, 3.4), (10.2, 10.8, 4.4))):
        material = "keys" if index == 0 else "case"
        elements += prism(ATLAS, CENTRE, CENTRE, radius, low, high, material,
                          axis="y", sides=12, face_materials={"up": "screen" if index == 2 else material})

    # Suchbalken quer über der Schüssel und der Erreger in der Mitte.
    elements.append(cube(ATLAS, (CENTRE - 4.6, 10.8, CENTRE - 0.35), (CENTRE + 4.6, 11.3, CENTRE + 0.35),
                         "steel", {"up": "screen"}))
    elements += prism(ATLAS, CENTRE, CENTRE, 0.75, 10.8, 13.2, "steel", axis="y", sides=8)
    elements += prism(ATLAS, CENTRE, CENTRE, 1.05, 13.2, 14.0, "screen", axis="y", sides=8)

    return elements


def build_cloak() -> list[dict]:
    """Tarnmantel: ein gerollter Ballen Tuch mit Spange, Riemen und Feldgeber.

    Ein Mantel als Gegenstand ist heikel – ausgebreitet ist er eine Fläche, und Flächen sehen in
    der Hand nach Pappe aus. Gerollt hat er einen Körper, und die Spange sagt trotzdem, dass es
    ein Kleidungsstück ist.
    """
    elements: list[dict] = []

    # Der Ballen liegt quer, damit man das Rollende sieht.
    elements += prism(ATLAS, CENTRE, CENTRE, 3.4, CENTRE - 4.8, CENTRE + 4.8, "cloth",
                      axis="x", sides=12, face_materials={"east": "weave", "west": "weave"})
    # Ein überstehender Zipfel, der die Rolle als Tuch ausweist.
    elements.append(cube(ATLAS, (CENTRE + 1.4, CENTRE - 4.4, CENTRE - 2.6),
                         (CENTRE + 4.4, CENTRE - 2.4, CENTRE + 2.6), "cloth", {"down": "weave"},
                         rotation={"origin": [CENTRE + 1.4, CENTRE - 2.4, CENTRE], "axis": "z", "angle": -18.0}))

    # Zwei Riemen um die Rolle.
    for offset in (-2.4, 2.4):
        elements += prism(ATLAS, CENTRE, CENTRE, 3.55, CENTRE + offset - 0.4, CENTRE + offset + 0.4,
                          "rubber", axis="x", sides=12)

    # Spange und Feldgeber obenauf.
    elements.append(cube(ATLAS, (CENTRE - 1.6, CENTRE + 3.2, CENTRE - 1.2),
                         (CENTRE + 1.6, CENTRE + 4.2, CENTRE + 1.2), "steel"))
    elements += prism(ATLAS, CENTRE, CENTRE, 1.0, CENTRE + 4.2, CENTRE + 5.2, "screen", axis="y", sides=8)

    return elements


def mark_tint(elements: list[dict]) -> list[dict]:
    """Nur die Anzeigeflächen werden eingefärbt."""
    wanted = tuple(ATLAS.uv("screen"))
    for element in elements:
        for face in element["faces"].values():
            if tuple(face["uv"]) == wanted:
                face["tintindex"] = 0
    return elements


# Höhe der Hand-Verschiebung, in Sechzehnteln. Sie wird nach der Einmittung *gesetzt*, nicht
# addiert: centred_display rückt das Modell zuerst auf seinen Schwerpunkt, und bei einem hohen
# Gerät wie dem Funkgerät frisst dieser Schritt einen guten Teil der Verschiebung wieder auf –
# genau deshalb hingen die beiden tiefer in der Hand als die übrigen Geräte.
HAND_HEIGHT = 3.4


def display_for(elements: list[dict], rotation: tuple[float, float, float], fill: float) -> dict:
    hand = centred_display(elements, rotation, fill, offset=(1.0, 0.0, 0.0))
    hand["translation"][1] = HAND_HEIGHT
    return {
        "firstperson_righthand": hand,
        "firstperson_lefthand": dict(hand),
        "thirdperson_righthand": third_person(elements, rotation, fill * 0.92),
        "thirdperson_lefthand": third_person(elements, rotation, fill * 0.92),
        "gui": centred_display(elements, (24, -150, 0), 0.94, flat=True),
        "ground": centred_display(elements, (0, 0, 0), 0.55, offset=(0.0, 3.0, 0.0)),
        "fixed": centred_display(elements, (0, 180, 0), 0.95),
        "head": centred_display(elements, (0, 180, 0), 1.1, offset=(0.0, 13.0, 0.0)),
    }


def third_person(elements: list[dict], rotation: tuple[float, float, float], fill: float) -> dict:
    view = centred_display(elements, rotation, fill)
    view["translation"][1] = 2.8
    return view


def main() -> int:
    texture_path = TEXTURES_DIR / "field_gear.png"
    ATLAS.save(texture_path)
    print(f"Atlas: {texture_path.relative_to(ROOT)} "
          f"({texture_path.stat().st_size} B, {ATLAS.size[0]}x{ATLAS.size[1]}, {len(ATLAS.slots)} Materialien)")

    for name, builder, rotation, fill, default in (
        ("airstrike", build_airstrike, (8, -148, 6), 0.52, 0x35D66B),
        ("c4", build_detonator, (14, -142, 8), 0.52, 0xFF3B2A),
        ("radar_pulse", build_radar, (10, -146, 4), 0.52, 0x3FE0FF),
        ("invisibility_cloak", build_cloak, (14, -140, 8), 0.54, 0xB07CFF),
    ):
        elements = mark_tint(builder())
        modelkit.write(MODELS_DIR / f"{name}.json",
                       modelkit.model(TEXTURE, elements, display_for(elements, rotation, fill)))
        if name == "c4":
            modelkit.write(ITEMS_DIR / "c4.json", {
                "model": {
                    "type": "minecraft:condition",
                    "property": "oneshotonekill:has_placed_c4",
                    "on_true": {
                        "type": "minecraft:model",
                        "model": "oneshotonekill:item/c4",
                        "tints": [{"type": "minecraft:dye", "default": modelkit.opaque(0xFF3B2A)}],
                    },
                    "on_false": {
                        "type": "minecraft:model",
                        "model": "oneshotonekill:item/c4_charge",
                        "tints": [{"type": "minecraft:dye", "default": modelkit.opaque(0xFF4A32)}],
                    },
                },
            })
        else:
            modelkit.write(ITEMS_DIR / f"{name}.json", {
                "model": {
                    "type": "minecraft:model",
                    "model": f"oneshotonekill:item/{name}",
                    "tints": [{"type": "minecraft:dye", "default": modelkit.opaque(default)}],
                },
            })
        low, high = modelkit.bounds(elements)
        tinted = sum(1 for element in elements for face in element["faces"].values() if "tintindex" in face)
        print(f"{name:18} {len(elements):3} Elemente, Ausdehnung "
              f"{[round(v, 2) for v in low]} .. {[round(v, 2) for v in high]}, {tinted} Flächen einfärbbar")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
