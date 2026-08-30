"""Erzeugt Textur und Modelle des Geschützturms.

    python tools/generate_sentry_turret_3d.py

Ausgabe:
  * textures/item/sentry_turret.png     – gemeinsamer Materialatlas
  * models/item/sentry_turret.json      – vollständiger Turm für Hand und Inventar
  * models/item/sentry_base.json        – Unterbau: Beine, Plattform, Drehkranz
  * models/item/sentry_head.json        – Kopf: Panzerung, Doppellauf, Trommel, Auge
  * items/sentry_turret.json, items/sentry_base.json, items/sentry_head.json

**Die platzierte Version besteht aus zwei Teilmodellen.** Vorher drehte sich das ganze Gerät
zum Ziel, Beine inbegriffen – das sah aus, als rutschte es über den Boden. Jetzt steht der
Unterbau still und nur der Kopf schwenkt. Das benutzbare Item bekommt dagegen ein drittes,
vollständiges Modell, damit in Hand, Hotbar und Menü weder Kopf noch Waffen fehlen.

**Beide Modelle sind auf ihren Drehpunkt eingemittet.** Eine Display zeichnet ihr Modell um die
eigene Position zentriert; damit der Kopf um seine Lagerung schwenkt und nicht um seine
Bildmitte, wird er so verschoben, dass die Lagerung auf (8, 8, 8) liegt. Die Höhen, auf denen
die beiden Entities über dem Boden sitzen, gibt das Skript aus.

**Der Kopf zielt nach -Z.** In der Welt zeigt er damit nach +Z: {@code
DisplayRenderer.ItemDisplayRenderer#submitInner} legt vor dem Zeichnen ein
{@code Axis.YP.rotation(PI)} auf den Stapel. Der Gierwinkel in {@code Deployables} rechnet das
mit ein.
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
TEXTURE = "oneshotonekill:item/sentry_turret"

CENTRE = 8.0
# Beide Modelle werden zunächst mit dem Boden auf y = 0 gebaut und erst am Ende auf ihren
# Drehpunkt eingemittet. Das hält die Zahlen unten lesbar.
PLATFORM_TOP = 8.0
COLLAR_TOP = 9.8
PIVOT_Y = 13.4


# ---------------------------------------------------------------------------
# Materialien
# ---------------------------------------------------------------------------


def _armour() -> Tile:
    """Panzerung: dunkles Olivgrau mit Plattenfugen."""
    tile = Tile()
    tile.fill_gradient((96, 100, 88), (58, 62, 54), 8)
    tile.bands(range(0, modelkit.TILE, 6), -20)
    for x in (1, modelkit.TILE - 2):
        for y in range(modelkit.TILE):
            tile.set(x, y, shift(tile.get(x, y), -18))
    return tile


def _plate() -> Tile:
    """Deckplatten: heller, mit Riffelmuster – die Oberseite sieht man am längsten."""
    tile = Tile()
    tile.fill_gradient((132, 137, 124), (88, 93, 82), 9)
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            if (x + y) % 5 == 0:
                tile.set(x, y, shift(tile.get(x, y), 22))
    return tile


def _rim() -> Tile:
    """Kanten und Drehkranz: das Dunkelste am Turm."""
    tile = Tile()
    tile.fill_gradient((54, 57, 62), (30, 32, 36), 6)
    for y in range(1, modelkit.TILE, 4):
        for x in range(modelkit.TILE):
            tile.set(x, y, shift(tile.get(x, y), 26))
    return tile


def _barrel() -> Tile:
    """Läufe: gebürstetes Waffenmetall mit Längsstreifen."""
    tile = Tile()
    tile.fill_gradient((118, 122, 130), (66, 70, 78), 7)
    tile.streaks((1, 2, 7, 8, 13, 14), 18)
    return tile


def _muzzle() -> Tile:
    """Mündungsbremse: fast schwarz, innen angelaufen."""
    tile = Tile()
    tile.fill_gradient((46, 44, 42), (22, 21, 20), 5)
    centre = (modelkit.TILE - 1) / 2.0
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            if math.hypot(x - centre, y - centre) < 4.0:
                tile.set(x, y, shift((92, 58, 34), dither(x, y, 10)))
    return tile


def _drum() -> Tile:
    """Munitionstrommel: Messing mit Nietenreihe."""
    tile = Tile()
    tile.fill_gradient((168, 138, 74), (108, 88, 46), 9)
    for y in range(2, modelkit.TILE, 5):
        for x in range(1, modelkit.TILE, 4):
            tile.set(x, y, (206, 182, 120))
            tile.set(x + 1, y, (78, 62, 32))
    return tile


def _lens() -> Tile:
    """Sensorauge: fast weiß, damit die Einfärbung den ganzen Farbraum behält."""
    tile = Tile()
    tile.fill_gradient((214, 214, 210), (170, 170, 166), 5)
    centre = (modelkit.TILE - 1) / 2.0
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            distance = math.hypot(x - centre, y - centre)
            if distance < 5.5:
                tile.set(x, y, (255, 255, 253))
            elif distance < 6.8:
                tile.set(x, y, (206, 206, 202))
    return tile


def _hydraulic() -> Tile:
    """Kolbenstangen: blank, damit sich die Gelenke von der Panzerung absetzen."""
    tile = Tile()
    tile.fill_gradient((198, 202, 208), (136, 140, 148), 6)
    tile.streaks((3, 4, 11, 12), 20)
    return tile


def _warn() -> Tile:
    """Warnstreifen um die Plattform."""
    tile = Tile()
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            hot = ((x - y) // 4) % 2 == 0
            tile.set(x, y, shift((214, 172, 40) if hot else (30, 28, 24), dither(x, y, 8)))
    return tile


def _vent() -> Tile:
    """Kühlschlitze am Heck."""
    tile = Tile()
    tile.fill_gradient((70, 74, 68), (38, 41, 37), 5)
    for y in range(1, modelkit.TILE, 3):
        for x in range(2, modelkit.TILE - 2):
            tile.set(x, y, (18, 19, 18))
    return tile


ATLAS = Atlas({
    "armour": _armour,
    "plate": _plate,
    "rim": _rim,
    "barrel": _barrel,
    "muzzle": _muzzle,
    "drum": _drum,
    "lens": _lens,
    "hydraulic": _hydraulic,
    "warn": _warn,
    "vent": _vent,
})


# ---------------------------------------------------------------------------
# Unterbau
# ---------------------------------------------------------------------------

LEG_COUNT = 3
LEG_TILT = -25.0
LEG_HINGE_Y = 7.2
LEG_RADIUS = 3.65
LEG_HALF_WIDTH = 0.75


def leg_foot_y() -> float:
    """Wo ein senkrechtes Bein anfangen muss, damit sein Fuß nach der Neigung den Boden trifft.

    Von Hand wäre das Probieren: ein geneigtes Bein überbrückt weniger Höhe als ein
    senkrechtes, und weil die Drehung um die Turmachse läuft, hebt auch der Abstand zur Achse
    den Fuß an. Beides steckt in dieser Zeile – sie ist die nach y aufgelöste Drehformel
    ``y′ = y·cosθ − z·sinθ`` für das Ziel ``y′ = −LEG_HINGE_Y``.
    """
    tilt = math.radians(LEG_TILT)
    return LEG_HINGE_Y + (-LEG_HINGE_Y + LEG_RADIUS * math.sin(tilt)) / math.cos(tilt)


def build_base() -> list[dict]:
    """Dreibein, Plattform und Drehkranz – alles, was stehen bleibt.

    Die Beine stehen im Winkel von 120 Grad zueinander und sind zusätzlich nach außen
    geneigt. Beides in einem Element geht erst seit 26.2: die Euler-Form der Drehung setzt
    erst die Neigung um X und dann die Verteilung um Y zusammen. Gedreht wird um einen Punkt
    auf der Turmachse – nur dann verteilt der zweite Winkel die Beine gleichmäßig im Kreis.
    """
    elements: list[dict] = []
    foot_y = leg_foot_y()

    for index in range(LEG_COUNT):
        spin = index * 360.0 / LEG_COUNT
        rotation = {"origin": [CENTRE, LEG_HINGE_Y, CENTRE], "x": LEG_TILT, "y": spin, "z": 0.0}
        elements.append(cube(
            ATLAS,
            (CENTRE - LEG_HALF_WIDTH, foot_y + 0.9, CENTRE + LEG_RADIUS - 0.75),
            (CENTRE + LEG_HALF_WIDTH, LEG_HINGE_Y + 0.6, CENTRE + LEG_RADIUS + 0.75),
            "hydraulic",
            {"north": "rim", "south": "rim"},
            rotation,
        ))
        elements.append(cube(
            ATLAS,
            (CENTRE - 1.5, foot_y, CENTRE + LEG_RADIUS - 1.5),
            (CENTRE + 1.5, foot_y + 0.9, CENTRE + LEG_RADIUS + 1.5),
            "rim",
            {"up": "warn"},
            rotation,
        ))

    # Plattform als Zwölfeck – rund genug, dass man die Kanten nicht zählt.
    elements += prism(ATLAS, CENTRE, CENTRE, 5.0, PLATFORM_TOP - 2.0, PLATFORM_TOP,
                      "armour", axis="y", sides=12, face_materials={"up": "plate", "down": "rim"})
    # Warnband am Rand, einen Hauch breiter als die Plattform.
    elements += prism(ATLAS, CENTRE, CENTRE, 5.25, PLATFORM_TOP - 2.0, PLATFORM_TOP - 1.2,
                      "warn", axis="y", sides=12)
    # Drehkranz, auf dem der Kopf sitzt.
    elements += prism(ATLAS, CENTRE, CENTRE, 3.4, PLATFORM_TOP, COLLAR_TOP,
                      "rim", axis="y", sides=12, face_materials={"up": "hydraulic"})

    # Zwei Kästen auf der Plattform: Akku und Kühler.
    elements.append(cube(ATLAS, (CENTRE - 4.6, PLATFORM_TOP, CENTRE + 1.4),
                         (CENTRE - 2.6, PLATFORM_TOP + 1.9, CENTRE + 3.9), "armour", {"up": "vent"}))
    elements.append(cube(ATLAS, (CENTRE + 2.6, PLATFORM_TOP, CENTRE - 3.9),
                         (CENTRE + 4.6, PLATFORM_TOP + 1.4, CENTRE - 1.4), "armour", {"up": "plate"}))

    return elements


# ---------------------------------------------------------------------------
# Kopf
# ---------------------------------------------------------------------------

BARREL_TIP_Z = 0.6
BARREL_SIDE = 1.9


def build_head() -> list[dict]:
    """Panzergehäuse, Doppellauf, Munitionstrommel und Sensorauge."""
    elements: list[dict] = []

    # Gehäuse.
    elements.append(cube(ATLAS, (CENTRE - 3.6, PIVOT_Y - 2.6, 5.2), (CENTRE + 3.6, PIVOT_Y + 2.5, 12.4),
                         "armour", {"up": "plate", "south": "vent"}))
    # Wangenplatten, damit die Silhouette nicht rechteckig bleibt.
    for side in (-1, 1):
        left = CENTRE + min(side * 3.6, side * 4.4)
        right = CENTRE + max(side * 3.6, side * 4.4)
        elements.append(cube(ATLAS, (left, PIVOT_Y - 1.8, 6.0), (right, PIVOT_Y + 1.6, 11.4), "rim"))

    # Lagerung: zwei Zapfen zur Seite, an denen der Kopf hängt.
    for side in (-1, 1):
        left = CENTRE + min(side * 4.4, side * 5.2)
        right = CENTRE + max(side * 4.4, side * 5.2)
        # Achse x: corner_of legt a auf y und b auf z.
        elements += prism(ATLAS, PIVOT_Y, CENTRE + 0.8, 1.1, left, right, "hydraulic", axis="x", sides=8)

    # Doppellauf mit Mündungsbremse und Schutzblech darüber.
    for side in (-1, 1):
        centre_x = CENTRE + side * BARREL_SIDE
        # Achse z: corner_of legt a auf x und b auf y.
        elements += prism(ATLAS, centre_x, PIVOT_Y + 0.4, 0.8, BARREL_TIP_Z + 1.0, 6.2, "barrel", axis="z", sides=8)
        elements += prism(ATLAS, centre_x, PIVOT_Y + 0.4, 1.15, BARREL_TIP_Z, BARREL_TIP_Z + 1.0, "muzzle", axis="z", sides=8)
    elements.append(cube(ATLAS, (CENTRE - 3.0, PIVOT_Y + 1.5, 2.4), (CENTRE + 3.0, PIVOT_Y + 2.1, 5.6),
                         "plate", {"down": "rim"}))

    # Munitionstrommel rechts am Gehäuse.
    elements += prism(ATLAS, PIVOT_Y - 0.4, 8.8, 2.3, CENTRE + 3.4, CENTRE + 5.8, "drum", axis="x", sides=10)

    # Sensorauge vorn oben – das einzige einfärbbare Teil.
    elements.append(cube(ATLAS, (CENTRE - 1.8, PIVOT_Y + 2.1, 5.0), (CENTRE + 1.8, PIVOT_Y + 3.0, 6.6), "lens"))
    # Blende darüber, damit das Auge nicht wie aufgeklebt wirkt.
    elements.append(cube(ATLAS, (CENTRE - 2.2, PIVOT_Y + 3.0, 4.8), (CENTRE + 2.2, PIVOT_Y + 3.4, 7.0),
                         "rim", {"up": "plate"}))

    # Antenne am Heck.
    elements.append(cube(ATLAS, (CENTRE + 2.4, PIVOT_Y + 2.5, 11.2), (CENTRE + 2.9, PIVOT_Y + 6.2, 11.7),
                         "hydraulic",
                         rotation={"origin": [CENTRE + 2.65, PIVOT_Y + 2.5, 11.45], "axis": "x", "angle": 12.0}))

    return elements


def mark_lens(elements: list[dict]) -> list[dict]:
    """Nur das Sensorauge wird eingefärbt – der Rest behält seine Materialfarbe."""
    for element in elements:
        for face in element["faces"].values():
            if face["uv"] == ATLAS.uv("lens"):
                face["tintindex"] = 0
    return elements


# ---------------------------------------------------------------------------
# Einmitten und Ausgabe
# ---------------------------------------------------------------------------


def anchor_at(elements: list[dict], anchor_y: float) -> float:
    """Verschiebt das Modell so, dass ``anchor_y`` auf der Modellmitte liegt.

    Rückgabe ist die Höhe des Ankers über dem Boden in Blöcken – genau der Wert, um den die
    Display-Entity über der Standfläche sitzen muss.
    """
    shift_y = CENTRE - anchor_y
    for element in elements:
        element["from"][1] += shift_y
        element["to"][1] += shift_y
        rotation = element.get("rotation")
        if rotation is not None:
            rotation["origin"][1] += shift_y
    return anchor_y / 16.0


def display_for(elements: list[dict]) -> dict:
    # In Hand und Drittperson schaut die Waffenfront zum Betrachter. Die frühere Drehung um
    # -145 Grad zeigte vor allem Heck und Munitionstrommel. Das vollständige Modell braucht
    # außerdem etwas mehr Rand als der alleinige Unterbau, damit Beine, Antenne und Läufe nicht
    # am Bildschirm abgeschnitten werden.
    hand = centred_display(elements, (10, 35, 0), 0.49, offset=(0.9, 3.2, 0.0))
    return {
        "firstperson_righthand": hand,
        "firstperson_lefthand": dict(hand),
        "thirdperson_righthand": centred_display(elements, (18, 45, 0), 0.45, offset=(0.0, 2.5, 0.0)),
        "thirdperson_lefthand": centred_display(elements, (18, 45, 0), 0.45, offset=(0.0, 2.5, 0.0)),
        "gui": centred_display(elements, (26, -152, 0), 0.94, flat=True),
        "ground": centred_display(elements, (0, 0, 0), 0.55, offset=(0.0, 3.0, 0.0)),
        "fixed": centred_display(elements, (0, 180, 0), 0.95),
        "head": centred_display(elements, (0, 180, 0), 1.1, offset=(0.0, 13.0, 0.0)),
    }


def main() -> int:
    texture_path = TEXTURES_DIR / "sentry_turret.png"
    ATLAS.save(texture_path)
    print(f"Atlas: {texture_path.relative_to(ROOT)} "
          f"({texture_path.stat().st_size} B, {ATLAS.size[0]}x{ATLAS.size[1]}, {len(ATLAS.slots)} Materialien)")

    # Das benutzbare Item zeigt das vollständige Gerät. Für die platzierte Version bleiben
    # Unterbau und Kopf getrennte Modellträger, damit nur der Kopf zum Ziel schwenkt.
    complete = mark_lens(build_base() + build_head())
    base = build_base()
    head = mark_lens(build_head())

    # Der Unterbau wird auf seine halbe Höhe eingemittet, der Kopf auf seine Lagerung.
    base_lift = anchor_at(base, COLLAR_TOP / 2.0)
    head_lift = anchor_at(head, PIVOT_Y)

    for name, elements in (("sentry_turret", complete), ("sentry_base", base), ("sentry_head", head)):
        modelkit.write(MODELS_DIR / f"{name}.json", modelkit.model(TEXTURE, elements, display_for(elements)))
        modelkit.write(ITEMS_DIR / f"{name}.json", {
            "model": {
                "type": "minecraft:model",
                "model": f"oneshotonekill:item/{name}",
                "tints": [{"type": "minecraft:dye", "default": modelkit.opaque(0x64E67A)}],
            },
        })
        low, high = modelkit.bounds(elements)
        print(f"{name:14} {len(elements):3} Elemente, Ausdehnung "
              f"{[round(v, 2) for v in low]} .. {[round(v, 2) for v in high]}")

    print("\nDiese Werte gehören nach item/runtime/Deployables.java, sonst schwebt der Turm oder")
    print("der Kopf dreht um den falschen Punkt:")
    print(f"  TURRET_BASE_LIFT   = {base_lift:.4f}")
    print(f"  TURRET_HEAD_LIFT   = {head_lift:.4f}")
    print(f"  MUZZLE_FORWARD     = {(CENTRE - BARREL_TIP_Z) / 16.0:.4f}")
    print(f"  MUZZLE_SIDE        = {BARREL_SIDE / 16.0:.4f}")
    print(f"  MUZZLE_UP          = {0.4 / 16.0:.4f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
