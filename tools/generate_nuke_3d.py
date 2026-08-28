"""Erzeugt Textur und Modelle des Nuke-Abwurfs: Bomber und Bombe.

    python tools/generate_nuke_3d.py

Ausgabe:
  * textures/item/nuke.png          – gemeinsamer Materialatlas
  * models/item/nuke_bomber.json    – viermotoriger Bomber, quer zur Flugrichtung gebaut
  * models/item/nuke_bomb.json      – die Bombe, dick und mit Leitwerk
  * die zugehoerigen items/*.json

**Beide sind laengs der Y-Achse gebaut, mit der Nase nach unten.** Das ist kein Zufall, sondern
folgt aus dem Renderer: ``DisplayRenderer.ItemDisplayRenderer#submitInner`` legt vor dem
Zeichnen ein ``Axis.YP.rotation(PI)`` auf den Stapel und spiegelt damit X und Z – die Y-Achse
bleibt unberuehrt. Ein Modell, das seine Laengsachse in Y hat, laesst sich deshalb ohne
Vorzeichenfallen drehen: Die Bombe faellt einfach, und der Bomber bekommt nur seinen Gierwinkel.

**Nur Warnstreifen und Triebwerksglut tragen ``tintindex``.** Der Rest behaelt seine Farbe.
"""

from __future__ import annotations

import pathlib

import modelkit
from modelkit import Atlas, Tile, centred_display, cube, dither, prism, shift

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/oneshotonekill"
MODELS_DIR = ASSETS / "models/item"
ITEMS_DIR = ASSETS / "items"
TEXTURES_DIR = ASSETS / "textures/item"
TEXTURE = "oneshotonekill:item/nuke"

CENTRE = 8.0


# ---------------------------------------------------------------------------
# Materialien
# ---------------------------------------------------------------------------


def _shell() -> Tile:
    """Bombenhuelle: mattes Militaergruen mit Nietenreihen."""
    tile = Tile()
    tile.fill_gradient((104, 112, 84), (58, 64, 46), 8)
    tile.bands(range(2, modelkit.TILE, 5), -22)
    for y in range(1, modelkit.TILE, 6):
        for x in range(2, modelkit.TILE, 4):
            tile.set(x, y, shift(tile.get(x, y), 34))
    return tile


def _steel() -> Tile:
    """Leitwerk, Beschlaege, Gondelringe: helles Blech."""
    tile = Tile()
    tile.fill_gradient((168, 176, 186), (96, 104, 116), 8)
    tile.streaks((3, 4, 11, 12), 12)
    return tile


def _hull() -> Tile:
    """Rumpf und Flaechen des Bombers: dunkles Grauschiefer mit Plattenfugen."""
    tile = Tile()
    tile.fill_gradient((78, 84, 96), (42, 46, 55), 7)
    tile.bands(range(0, modelkit.TILE, 5), -20)
    return tile


def _dark() -> Tile:
    """Kanten, Fugen und Kanzelrahmen."""
    tile = Tile()
    tile.fill_gradient((36, 39, 46), (18, 20, 25), 4)
    return tile


def _warn() -> Tile:
    """Warnband der Bombe: gelb-schwarz, schraeg."""
    tile = Tile()
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            hot = ((x + y) // 3) % 2 == 0
            tile.set(x, y, shift((214, 176, 44) if hot else (26, 24, 20), dither(x, y, 8)))
    return tile


def _glow() -> Tile:
    """Triebwerksglut und Kanzel: fast weiss, damit die Einfaerbung alles hergibt."""
    tile = Tile()
    tile.fill_gradient((250, 248, 242), (196, 200, 206), 5)
    centre = (modelkit.TILE - 1) / 2.0
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            glow = 1.0 - min(1.0, abs(y - centre) / centre)
            tile.set(x, y, shift(tile.get(x, y), round(30 * glow * glow) - 15))
    return tile


ATLAS = Atlas({
    "shell": _shell,
    "steel": _steel,
    "hull": _hull,
    "dark": _dark,
    "warn": _warn,
    "glow": _glow,
}, columns=3)


# ---------------------------------------------------------------------------
# Die Bombe
# ---------------------------------------------------------------------------

# Laengsprofil: (Hoehe, Radius). Die Nase liegt unten, das Leitwerk oben.
BOMB_PROFILE = (
    (0.6, 0.9),
    (1.4, 2.1),
    (2.6, 3.2),
    (4.2, 4.0),
    (8.4, 4.0),
    (10.4, 3.4),
    (11.8, 2.4),
    (12.8, 1.9),
)
BOMB_SIDES = 12
BOMB_FIN_TOP = 15.4
BOMB_FIN_SPAN = 4.6


def build_bomb() -> list[dict]:
    """Eine dicke Bombe: kugeliger Bauch, kurze Nase, vier Leitflaechen.

    Die Form ist die des historischen Vorbilds und nicht die einer Rakete – ein Zylinder mit
    Spitze sieht aus wie Munition, und eine Nuke soll nach etwas aussehen, das man nicht
    verschiesst, sondern abwirft.
    """
    elements: list[dict] = []

    for index in range(len(BOMB_PROFILE) - 1):
        low, radius = BOMB_PROFILE[index]
        high, next_radius = BOMB_PROFILE[index + 1]
        # Ein Abschnitt bekommt den groesseren der beiden Radien; die Stufen sind fein genug,
        # dass daraus eine Rundung wird und keine Treppe.
        girth = max(radius, next_radius)
        elements += prism(ATLAS, CENTRE, CENTRE, girth, low, high, "shell", axis="y", sides=BOMB_SIDES)

    # Warnband um den dicksten Teil.
    elements += prism(ATLAS, CENTRE, CENTRE, 4.12, 5.4, 6.6, "warn", axis="y", sides=BOMB_SIDES)
    # Zweiter, schmalerer Ring als Absetzung.
    elements += prism(ATLAS, CENTRE, CENTRE, 4.08, 7.4, 7.9, "steel", axis="y", sides=BOMB_SIDES)

    # Nasenkappe und Zuenderspitze.
    elements += prism(ATLAS, CENTRE, CENTRE, 1.5, 0.0, 0.7, "steel", axis="y", sides=8)
    elements.append(cube(ATLAS, (CENTRE - 0.35, -0.9, CENTRE - 0.35), (CENTRE + 0.35, 0.1, CENTRE + 0.35), "dark"))

    # Heckrohr, auf dem das Leitwerk sitzt.
    elements += prism(ATLAS, CENTRE, CENTRE, 1.6, 12.8, BOMB_FIN_TOP, "steel", axis="y", sides=8)

    # Vier Leitflaechen im Kreuz, jede ein flaches Blech.
    for axis_x in (True, False):
        for side in (-1, 1):
            near = CENTRE + side * 1.2
            far = CENTRE + side * BOMB_FIN_SPAN
            low_span, high_span = sorted((near, far))
            if axis_x:
                elements.append(cube(ATLAS, (low_span, 11.2, CENTRE - 0.28), (high_span, BOMB_FIN_TOP, CENTRE + 0.28),
                                     "steel", {"up": "dark"}))
            else:
                elements.append(cube(ATLAS, (CENTRE - 0.28, 11.2, low_span), (CENTRE + 0.28, BOMB_FIN_TOP, high_span),
                                     "steel", {"up": "dark"}))

    # Ein Ring, der die Leitflaechen aussen zusammenfasst – so sieht Leitwerk nach Leitwerk aus.
    for axis_x in (True, False):
        for side in (-1, 1):
            offset = side * (BOMB_FIN_SPAN - 0.3)
            if axis_x:
                elements.append(cube(ATLAS, (CENTRE + offset - 0.3, 13.4, CENTRE - BOMB_FIN_SPAN + 0.3),
                                     (CENTRE + offset + 0.3, 14.6, CENTRE + BOMB_FIN_SPAN - 0.3), "dark"))
            else:
                elements.append(cube(ATLAS, (CENTRE - BOMB_FIN_SPAN + 0.3, 13.4, CENTRE + offset - 0.3),
                                     (CENTRE + BOMB_FIN_SPAN - 0.3, 14.6, CENTRE + offset + 0.3), "dark"))
    return elements


# ---------------------------------------------------------------------------
# Der Bomber
# ---------------------------------------------------------------------------

BODY_HALF = 1.5
BODY_NOSE = 0.4
BODY_TAIL = 15.2
WING_PANELS = 4
WING_ROOT_X = 1.3
WING_TIP_X = 7.9
WING_ROOT_LEAD = 5.4
WING_TIP_LEAD = 9.2
WING_ROOT_CHORD = 5.4
WING_TIP_CHORD = 1.8
WING_Y = 7.4
WING_THICK = 0.9
ENGINE_OFFSETS = (2.9, 5.4)


def build_bomber() -> list[dict]:
    """Ein viermotoriger Bomber, gebaut wie die Bombe entlang der Y-Achse.

    Die Nase zeigt nach unten – im Flug wird das Modell einmal aufgerichtet und danach nur noch
    um die Hochachse gedreht. Der Umweg lohnt sich, weil damit dieselbe Drehlogik gilt wie fuer
    die Bombe und kein zweiter Satz Vorzeichen zu pflegen ist.
    """
    elements: list[dict] = []

    # Rumpf mit verjuengter Nase und Heck.
    elements.append(cube(ATLAS, (CENTRE - BODY_HALF, 2.2, CENTRE - BODY_HALF),
                         (CENTRE + BODY_HALF, BODY_TAIL - 1.4, CENTRE + BODY_HALF), "hull"))
    elements += prism(ATLAS, CENTRE, CENTRE, BODY_HALF - 0.35, BODY_NOSE, 2.2, "hull", axis="y", sides=8)
    elements += prism(ATLAS, CENTRE, CENTRE, BODY_HALF - 0.5, BODY_TAIL - 1.4, BODY_TAIL, "hull", axis="y", sides=8)
    # Kanzel als heller Streifen kurz hinter der Nase.
    elements.append(cube(ATLAS, (CENTRE - 0.9, 2.4, CENTRE - BODY_HALF - 0.14),
                         (CENTRE + 0.9, 3.9, CENTRE + BODY_HALF + 0.14), "glow"))

    # Tragflaechen, gestaffelt gepfeilt – dieselbe Bauweise wie beim Gleiter.
    for side in (-1, 1):
        for index in range(WING_PANELS):
            share = (index + 0.5) / WING_PANELS
            inner = CENTRE + side * _mix(WING_ROOT_X, WING_TIP_X, index / WING_PANELS)
            outer = CENTRE + side * _mix(WING_ROOT_X, WING_TIP_X, (index + 1) / WING_PANELS)
            low_x, high_x = sorted((inner, outer))
            lead = _mix(WING_ROOT_LEAD, WING_TIP_LEAD, share)
            chord = _mix(WING_ROOT_CHORD, WING_TIP_CHORD, share)
            thick = WING_THICK * (1.0 - share * 0.5)
            elements.append(cube(ATLAS, (low_x, WING_Y - thick / 2, lead), (high_x, WING_Y + thick / 2, lead + chord),
                                 "hull", {"down": "dark"}))

        # Vier Triebwerke: zwei je Seite, unter der Flaeche.
        for offset in ENGINE_OFFSETS:
            centre_x = CENTRE + side * offset
            elements += prism(ATLAS, centre_x, WING_Y - 1.3, 1.05, 5.2, 9.4, "steel", axis="z", sides=8)
            elements += prism(ATLAS, centre_x, WING_Y - 1.3, 0.8, 9.4, 10.4, "glow", axis="z", sides=8)
            elements.append(cube(ATLAS, (centre_x - 0.3, WING_Y - 1.0, 5.6), (centre_x + 0.3, WING_Y + 0.4, 6.8), "dark"))

    # Leitwerk: eine hohe Flosse und zwei Hoehenruder.
    elements.append(cube(ATLAS, (CENTRE - 0.34, WING_Y - 4.2, BODY_TAIL - 3.4),
                         (CENTRE + 0.34, WING_Y - 0.4, BODY_TAIL - 0.4), "hull", {"north": "dark"}))
    for side in (-1, 1):
        near = CENTRE + side * 0.4
        far = CENTRE + side * 3.4
        low_x, high_x = sorted((near, far))
        elements.append(cube(ATLAS, (low_x, WING_Y - 0.4, BODY_TAIL - 2.6), (high_x, WING_Y + 0.3, BODY_TAIL - 0.4),
                             "hull", {"down": "dark"}))
    return elements


def _mix(a: float, b: float, share: float) -> float:
    return a + (b - a) * share


# ---------------------------------------------------------------------------
# Ausgabe
# ---------------------------------------------------------------------------


def mark_tint(elements: list[dict], materials: tuple[str, ...]) -> list[dict]:
    wanted = {tuple(ATLAS.uv(name)) for name in materials}
    for element in elements:
        for face in element["faces"].values():
            if tuple(face["uv"]) in wanted:
                face["tintindex"] = 0
    return elements


def display_for(elements: list[dict], rotation: tuple[float, float, float], fill: float) -> dict:
    hand = centred_display(elements, rotation, fill, offset=(1.0, 2.8, 0.0))
    return {
        "firstperson_righthand": hand,
        "firstperson_lefthand": dict(hand),
        "thirdperson_righthand": centred_display(elements, rotation, fill * 0.92, offset=(0.0, 2.4, 0.0)),
        "thirdperson_lefthand": centred_display(elements, rotation, fill * 0.92, offset=(0.0, 2.4, 0.0)),
        "gui": centred_display(elements, (26, -28, 0), 0.96, flat=True),
        "ground": centred_display(elements, (0, 0, 0), 0.55, offset=(0.0, 3.0, 0.0)),
        # In der Welt haengt das Modell an einer Display-Entity und wird von der Laufzeit
        # gedreht; "fixed" muss deshalb ungedreht und ungestaucht bleiben.
        "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
        "head": centred_display(elements, (0, 0, 0), 1.1, offset=(0.0, 13.0, 0.0)),
    }


def main() -> int:
    texture_path = TEXTURES_DIR / "nuke.png"
    ATLAS.save(texture_path)
    print(f"Atlas: {texture_path.relative_to(ROOT)} "
          f"({texture_path.stat().st_size} B, {ATLAS.size[0]}x{ATLAS.size[1]}, {len(ATLAS.slots)} Materialien)")

    for name, builder, tints, rotation, fill, default in (
        ("nuke_bomb", build_bomb, ("warn",), (18, -34, 0), 0.62, 0xF2C94C),
        ("nuke_bomber", build_bomber, ("glow",), (18, -34, 0), 0.7, 0x8FD8F0),
    ):
        elements = mark_tint(builder(), tints)
        modelkit.write(MODELS_DIR / f"{name}.json",
                       modelkit.model(TEXTURE, elements, display_for(elements, rotation, fill)))
        modelkit.write(ITEMS_DIR / f"{name}.json", {
            "model": {
                "type": "minecraft:model",
                "model": f"oneshotonekill:item/{name}",
                "tints": [{"type": "minecraft:dye", "default": modelkit.opaque(default)}],
            },
        })
        low, high = modelkit.bounds(elements)
        tinted = sum(1 for element in elements for face in element["faces"].values() if "tintindex" in face)
        print(f"{name:12} {len(elements):3} Elemente, Ausdehnung "
              f"{[round(v, 2) for v in low]} .. {[round(v, 2) for v in high]}, {tinted} Flaechen einfaerbbar")
        print(f"{'':12} Laenge bei Skalierung 1: {(high[1] - low[1]) / 16.0:.4f} Bloecke")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
