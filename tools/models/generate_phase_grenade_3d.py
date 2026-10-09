"""Erzeugt Textur und Modell der Phasen-Granate.

    python tools/models/generate_phase_grenade_3d.py

Ausgabe:
  * textures/item/phase_grenade.png  – Materialatlas
  * models/item/phase_grenade.json   – die Kugel mit Leuchtdioden
  * items/phase_grenade.json         – Item-Definition; alle Leuchtdioden sind einfärbbar

Eine Kugel aus dunklem Panzerstahl, gefasst von vier Meridianbögen. Um den Äquator läuft ein
Leuchtband, darüber und darunter sitzt je ein Kranz aus acht Dioden, und in den Feldern
dazwischen liegen vier Linsen, durch die der Phasenkern schimmert. Oben der Zünder mit Ring,
unten die Emitterdüse.

**Nur Dioden und Leuchtband tragen ``tintindex``.** Die Laufzeit blinkt sie im Ruhemuster der
Handgeräte (``shared/DeviceLights``) und treibt sie beim Flug und auf dem Boden schneller. Die
Linsen bleiben bewusst ungefärbt: ein Kern, der nie die Farbe wechselt, hält das Modell auch
dann erkennbar, wenn das Band gerade dunkel ist.

Leuchtteile tragen ``light_emission`` – sie bleiben im Dunkeln sichtbar, der Panzer nicht.
"""

from __future__ import annotations

import math
import pathlib

import modelkit
from modelkit import Atlas, Tile, centred_display, cube, prism, shift

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
ASSETS = ROOT / "src/main/resources/assets/oneshotonekill"
MODELS_DIR = ASSETS / "models/item"
ITEMS_DIR = ASSETS / "items"
TEXTURES_DIR = ASSETS / "textures/item"
TEXTURE = "oneshotonekill:item/phase_grenade"

CENTRE = 8.0
RADIUS = 4.7
BANDS = 9
SIDES = 16
DIODES = 8
DIODE_LATITUDE = 38.0
COLD = 0x0B4A58


# ---------------------------------------------------------------------------
# Materialien
# ---------------------------------------------------------------------------


def _shell() -> Tile:
    """Panzer: blauschwarzer Stahl mit feinen Plattenfugen."""
    tile = Tile()
    tile.fill_gradient((62, 72, 88), (26, 32, 44), 7)
    for x in (0, 8):
        for y in range(modelkit.TILE):
            tile.set(x, y, shift(tile.get(x, y), -22))
    for x in range(modelkit.TILE):
        tile.set(x, 8, shift(tile.get(x, 8), -22))
    return tile


def _frame() -> Tile:
    """Meridianbögen: gebürstetes Hellmetall."""
    tile = Tile()
    tile.fill_gradient((196, 206, 218), (112, 124, 140), 7)
    tile.streaks((3, 4, 11, 12), 18)
    return tile


def _trim() -> Tile:
    """Fassungen um Band und Dioden: fast schwarz, damit das Licht daneben leuchtet."""
    tile = Tile()
    tile.fill_gradient((30, 36, 46), (12, 15, 22), 4)
    return tile


def _led() -> Tile:
    """Diode und Leuchtband: nahezu weiß, damit die Einfärbung den ganzen Farbraum behält."""
    tile = Tile()
    tile.fill_gradient((250, 252, 252), (212, 220, 222), 3)
    for y in range(3, modelkit.TILE, 5):
        for x in range(modelkit.TILE):
            tile.set(x, y, shift(tile.get(x, y), -34))
    return tile


def _lens() -> Tile:
    """Linse: der Phasenkern, heller Kern und dunkler werdender Rand."""
    tile = Tile()
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            distance = math.hypot(x - 7.5, y - 7.5) / 10.0
            tile.set(x, y, modelkit.blend((230, 255, 255), (20, 150, 190), min(1.0, distance)))
    return tile


def _steel() -> Tile:
    """Zünder und Ring: blanker Stahl."""
    tile = Tile()
    tile.fill_gradient((178, 184, 192), (118, 124, 132), 7)
    tile.streaks((4, 5, 11, 12), 20)
    return tile


ATLAS = Atlas({
    "shell": _shell,
    "frame": _frame,
    "trim": _trim,
    "led": _led,
    "lens": _lens,
    "steel": _steel,
}, columns=3)


# ---------------------------------------------------------------------------
# Geometrie
# ---------------------------------------------------------------------------


def _on_sphere(material: str, latitude: float, longitude: float, width: float, height: float,
               depth: float, reach: float = 0.0) -> dict:
    """Ein flacher Quader, der tangential auf der Kugel sitzt.

    ``latitude`` geht von -90 (unten) bis 90 (oben), ``longitude`` ist die Drehung um die
    Hochachse. Der Quader entsteht vor der Kugel auf der +Z-Seite und wird dann um den
    Mittelpunkt gedreht – wie die Spiegelfacetten der Boogie-Bombe.
    """
    return cube(
        ATLAS,
        (CENTRE - width / 2, CENTRE - height / 2, CENTRE + RADIUS - depth + reach),
        (CENTRE + width / 2, CENTRE + height / 2, CENTRE + RADIUS + reach),
        material,
        rotation={"origin": [CENTRE, CENTRE, CENTRE], "x": round(-latitude, 3),
                  "y": round(longitude, 3), "z": 0},
    )


def _glow(element: dict) -> dict:
    element["light_emission"] = 15
    return element


def build() -> list[dict]:
    elements: list[dict] = []

    # Kugelkörper aus übereinandergestapelten Scheiben.
    for band in range(BANDS):
        low = -RADIUS + 2 * RADIUS * band / BANDS
        high = -RADIUS + 2 * RADIUS * (band + 1) / BANDS
        radius = math.sqrt(max(0.05, RADIUS * RADIUS - ((low + high) / 2) ** 2))
        elements += prism(ATLAS, CENTRE, CENTRE, radius, CENTRE + low, CENTRE + high, "shell",
                          axis="y", sides=SIDES)

    # Leuchtband am Äquator, beidseitig gefasst.
    elements += prism(ATLAS, CENTRE, CENTRE, RADIUS + 0.28, CENTRE - 1.15, CENTRE - 0.55, "trim",
                      axis="y", sides=SIDES)
    elements += prism(ATLAS, CENTRE, CENTRE, RADIUS + 0.28, CENTRE + 0.55, CENTRE + 1.15, "trim",
                      axis="y", sides=SIDES)
    elements += [_glow(element) for element in prism(
        ATLAS, CENTRE, CENTRE, RADIUS + 0.2, CENTRE - 0.55, CENTRE + 0.55, "led", axis="y", sides=SIDES)]

    # Zwei Kränze aus je acht Dioden, versetzt zueinander.
    for latitude, offset in ((DIODE_LATITUDE, 0.0), (-DIODE_LATITUDE, 180.0 / DIODES)):
        for index in range(DIODES):
            longitude = index * 360.0 / DIODES + offset
            elements.append(_on_sphere("trim", latitude, longitude, 1.5, 1.5, 0.5, 0.18))
            elements.append(_glow(_on_sphere("led", latitude, longitude, 0.9, 0.9, 0.5, 0.42)))

    # Vier Linsen in den Feldern zwischen Band und Dioden, auf Höhe des Äquators versetzt.
    for index in range(4):
        longitude = index * 90.0 + 45.0
        elements.append(_on_sphere("trim", 12.0, longitude, 2.4, 2.4, 0.5, 0.1))
        elements.append(_glow(_on_sphere("lens", 12.0, longitude, 1.7, 1.7, 0.5, 0.3)))

    # Vier Meridianbögen als Kette kurzer, tangentialer Stücke.
    for longitude in (0.0, 90.0, 180.0, 270.0):
        latitude = -66.0
        while latitude <= 66.0:
            if abs(latitude) > 8.0:
                elements.append(_on_sphere("frame", latitude, longitude, 0.8, 2.3, 0.5, 0.05))
            latitude += 12.0

    # Zünder oben: Sockel, Kopf und Ring.
    elements += prism(ATLAS, CENTRE, CENTRE, 1.7, CENTRE + RADIUS - 0.5, CENTRE + RADIUS + 0.55, "trim",
                      axis="y", sides=8)
    elements += prism(ATLAS, CENTRE, CENTRE, 1.05, CENTRE + RADIUS + 0.55, CENTRE + RADIUS + 1.6, "steel",
                      axis="y", sides=8)
    ring_radius = 1.9
    half_width = math.pi * ring_radius / 8 * 1.2
    ring_height = CENTRE + RADIUS + 1.6
    for index in range(8):
        elements.append(cube(
            ATLAS,
            (CENTRE - half_width, ring_height - 0.25, CENTRE + ring_radius - 0.3),
            (CENTRE + half_width, ring_height + 0.25, CENTRE + ring_radius + 0.3),
            "steel",
            rotation={"origin": [CENTRE, ring_height, CENTRE], "axis": "y",
                      "angle": round(index * 45.0, 4)},
        ))

    # Emitterdüse unten, mit glühendem Kern.
    elements += prism(ATLAS, CENTRE, CENTRE, 1.6, CENTRE - RADIUS - 0.5, CENTRE - RADIUS + 0.5, "trim",
                      axis="y", sides=8)
    elements += [_glow(element) for element in prism(
        ATLAS, CENTRE, CENTRE, 0.85, CENTRE - RADIUS - 0.9, CENTRE - RADIUS - 0.4, "led", axis="y", sides=8)]

    return elements


def mark_tint(elements: list[dict]) -> list[dict]:
    """Nur Dioden und Leuchtband werden eingefärbt; Linsen und Panzer behalten ihre Farbe."""
    wanted = tuple(ATLAS.uv("led"))
    for element in elements:
        for face in element["faces"].values():
            if tuple(face["uv"]) == wanted:
                face["tintindex"] = 0
    return elements


def display_for(elements: list[dict]) -> dict:
    hand = centred_display(elements, (10, -145, 8), 0.46, offset=(1.0, 3.1, 0.0))
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
    texture_path = TEXTURES_DIR / "phase_grenade.png"
    ATLAS.save(texture_path)
    print(f"Atlas: {texture_path.relative_to(ROOT)} "
          f"({texture_path.stat().st_size} B, {ATLAS.size[0]}x{ATLAS.size[1]}, {len(ATLAS.slots)} Materialien)")

    elements = mark_tint(build())
    modelkit.write(MODELS_DIR / "phase_grenade.json", modelkit.model(TEXTURE, elements, display_for(elements)))
    modelkit.write(ITEMS_DIR / "phase_grenade.json", {
        "model": {
            "type": "minecraft:model",
            "model": "oneshotonekill:item/phase_grenade",
            # Ohne Farbe im Stapel bleiben die Dioden dunkel – so liegt die Granate im Inventar.
            "tints": [{"type": "minecraft:dye", "default": modelkit.opaque(COLD)}],
        },
    })

    low, high = modelkit.bounds(elements)
    tinted = sum(1 for element in elements for face in element["faces"].values() if "tintindex" in face)
    print(f"phase_grenade: {len(elements)} Elemente, Ausdehnung "
          f"{[round(v, 2) for v in low]} .. {[round(v, 2) for v in high]}, {tinted} Flächen einfärbbar")
    assert all(0.0 <= v <= 16.0 for v in low + high), "Modell ragt aus dem 16er-Raster"
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
