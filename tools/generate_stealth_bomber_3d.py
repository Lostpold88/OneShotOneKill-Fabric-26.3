"""Erzeugt Textur und Modelle für Tarnkappenbomber und seine Bomben.

    python tools/generate_stealth_bomber_3d.py

Ausgabe:
  * textures/item/stealth_bomber.png   – gemeinsamer Materialatlas für beide Modelle
  * models/item/stealth_bomber.json    – Nurflügler
  * models/item/bomber_bomb.json       – abgeworfene Bombe
  * items/stealth_bomber.json, items/bomber_bomb.json

**Beide Modelle zeigen mit der Nase nach -Z und sitzen mittig auf (8, 8, 8).** Das ist keine
Geschmacksfrage: In der Arena hängen sie an einer {@code Display.ItemDisplay}, und die zeichnet
mit dem Anzeigekontext {@code NONE}. Vanilla wendet dort keine der Anzeige-Transformationen an,
sondern nur die Verschiebung um (-0.5, -0.5, -0.5); die Mitte des Modells landet damit auf der
Entity-Position.

**Achtung bei der Ausrichtung:** völlig unverdreht bleibt die Geometrie trotzdem nicht.
{@code DisplayRenderer.ItemDisplayRenderer#submitInner} legt vor dem Zeichnen ein
{@code Axis.YP.rotation(PI)} auf den Stapel – im Ergebnis zeigt die Nase in der Welt also nach
+Z. Der Gierwinkel in {@code item/runtime/StealthBomberSystem.java} rechnet das mit ein; wer ihn
aus dem Modell allein herleitet, lässt den Bomber rückwärts fliegen.

Die Flügelhaut entsteht aus Spalten quer zur Flugrichtung, nicht aus Rippen längs dazu. Nur so
lassen sich Vorder- und Hinterkante unabhängig formen – und die Hinterkante braucht die
gezackte W-Form, an der man einen Nurflügler überhaupt erst erkennt.
"""

from __future__ import annotations

import math
import pathlib

import modelkit
from modelkit import Atlas, Tile, centred_display, cube, dither, octagon, shift

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/oneshotonekill"
MODELS_DIR = ASSETS / "models/item"
ITEMS_DIR = ASSETS / "items"
TEXTURES_DIR = ASSETS / "textures/item"
TEXTURE = "oneshotonekill:item/stealth_bomber"

# ---------------------------------------------------------------------------
# Geometrie des Nurflüglers
# ---------------------------------------------------------------------------

CENTRE_X = 8.0
CENTRE_Y = 8.0
NOSE_Z = 0.8
HALF_SPAN = 14.0
# Breite einer Spalte quer zur Flugrichtung. Schmaler heißt glattere Kanten und mehr Elemente.
COLUMN_WIDTH = 1.0
# Pfeilung der Vorderkante: so weit wandert sie je Einheit Spannweite nach hinten.
LEADING_EDGE_SWEEP = 0.62

# Hinterkante als Stützstellen (Abstand von der Mitte, z). Dazwischen wird linear
# interpoliert – daraus entsteht die gezackte W-Form des Vorbilds.
# Die Kerben bleiben flach. Beim Vorbild misst der Versatz einen Bruchteil der Flügeltiefe;
# tiefer gezogen sieht die Hinterkante nicht gezackt aus, sondern ausgefranst.
TRAILING_EDGE = (
    (0.0, 17.8),
    (3.2, 16.4),
    (6.4, 17.1),
    (9.6, 15.0),
    (11.8, 15.6),
    (14.0, 12.2),
)

# Halbe Dicke der Flügelhaut in der Mitte und an der Spitze.
ROOT_HALF_THICKNESS = 1.55
TIP_HALF_THICKNESS = 0.26
# Die Wölbung sitzt oben: unten ist das Profil flacher als oben.
CAMBER = 0.55


def _stealth() -> Tile:
    """Radarschluckende Haut: Anthrazit mit angedeuteten Facettenkanten."""
    tile = Tile()
    tile.fill_gradient((74, 78, 88), (50, 53, 62), 7)
    for step in range(modelkit.TILE):
        # Diagonale Plattenstöße – die Facetten des Originals in klein.
        tile.set(step, (step * 2) % modelkit.TILE, shift(tile.get(step, (step * 2) % modelkit.TILE), -14))
        tile.set(modelkit.TILE - 1 - step, step, shift(tile.get(modelkit.TILE - 1 - step, step), 9))
    return tile


def _panel() -> Tile:
    """Oberseite: eine Spur heller, damit sich Rumpf und Flügel absetzen."""
    tile = Tile()
    tile.fill_gradient((98, 103, 116), (66, 70, 82), 8)
    tile.bands(range(0, modelkit.TILE, 5), -13)
    return tile


def _edge() -> Tile:
    """Vorderkanten und Ränder: das Dunkelste am ganzen Flugzeug."""
    tile = Tile()
    tile.fill_gradient((46, 48, 56), (28, 30, 37), 5)
    return tile


def _canopy() -> Tile:
    """Kanzel: dunkles Glas mit einem einzigen Lichtstreifen."""
    tile = Tile()
    tile.fill_gradient((30, 42, 62), (14, 19, 30), 5)
    for x in range(modelkit.TILE):
        y = 3 + (x // 5)
        tile.set(x, y, (126, 158, 196))
        tile.set(x, y + 1, (72, 96, 130))
    return tile


def _intake() -> Tile:
    """Lufteinlass: schwarz, mit hellem Rand als Tiefenhinweis."""
    tile = Tile()
    tile.fill_gradient((20, 22, 26), (8, 9, 12), 3)
    for x in range(modelkit.TILE):
        tile.set(x, 0, (86, 92, 104))
        tile.set(x, modelkit.TILE - 1, (54, 58, 68))
    return tile


def _exhaust() -> Tile:
    """Schubdüse: von innen heiß, nach außen abkühlend."""
    tile = Tile()
    stops = ((250, 214, 150), (238, 140, 58), (168, 62, 32), (62, 40, 38), (24, 24, 28))
    for y in range(modelkit.TILE):
        position = y / (modelkit.TILE - 1) * (len(stops) - 1)
        low = min(len(stops) - 2, int(position))
        row = modelkit.blend(stops[low], stops[low + 1], position - low)
        for x in range(modelkit.TILE):
            tile.set(x, y, shift(row, dither(x, y, 10)))
    return tile


def _bay() -> Tile:
    """Bombenschacht von unten: Lukenkanten auf dunklem Grund."""
    tile = Tile()
    tile.fill_gradient((88, 93, 104), (58, 62, 72), 6)
    for y in range(modelkit.TILE):
        tile.set(1, y, (150, 158, 174))
        tile.set(modelkit.TILE - 2, y, (150, 158, 174))
    for x in range(2, modelkit.TILE - 2):
        tile.set(x, 7, (22, 24, 30))
        tile.set(x, 8, (124, 131, 146))
    return tile


def _underside() -> Tile:
    """Unterseite: heller als die Oberseite, mit einem Raster feiner Plattenfugen.

    Von unten sieht man den Bomber am längsten – eine einfarbige Fläche wirkt dort tot.
    """
    tile = Tile()
    tile.fill_gradient((118, 124, 138), (86, 91, 104), 7)
    for y in range(modelkit.TILE):
        tile.set(4, y, shift(tile.get(4, y), -34))
        tile.set(11, y, shift(tile.get(11, y), -34))
    for x in range(modelkit.TILE):
        tile.set(x, 5, shift(tile.get(x, 5), -30))
        tile.set(x, 12, shift(tile.get(x, 12), -30))
    for px, py in ((2, 2), (8, 9), (13, 3)):
        tile.set(px, py, (168, 176, 192))
    return tile


def _sensor() -> Tile:
    """Sensorkuppel: dunkles Glas mit einem harten Lichtpunkt."""
    tile = Tile()
    tile.fill_gradient((30, 34, 44), (12, 14, 20), 4)
    centre = (modelkit.TILE - 1) / 2.0
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            if math.hypot(x - centre, y - centre) < 5.5:
                tile.set(x, y, shift((22, 26, 36), dither(x, y, 8)))
    tile.set(5, 5, (150, 172, 200))
    tile.set(6, 5, (96, 116, 146))
    tile.set(5, 6, (96, 116, 146))
    return tile


def _nav_red() -> Tile:
    return _nav_light((236, 74, 58), (86, 20, 20))


def _nav_green() -> Tile:
    return _nav_light((92, 232, 118), (20, 78, 34))


def _nav_light(bright: tuple[int, int, int], dark: tuple[int, int, int]) -> Tile:
    """Positionslicht: heller Kern in dunkler Fassung."""
    tile = Tile()
    tile.fill_gradient(dark, (16, 17, 21), 4)
    centre = (modelkit.TILE - 1) / 2.0
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            distance = math.hypot(x - centre, y - centre)
            if distance < 3.0:
                tile.set(x, y, bright)
            elif distance < 5.0:
                tile.set(x, y, modelkit.blend(bright, dark, (distance - 3.0) / 2.0))
    return tile


def _casing() -> Tile:
    """Bombenkörper: mattes Olivgrau mit Längsnaht."""
    tile = Tile()
    tile.fill_gradient((104, 108, 92), (62, 66, 56), 8)
    tile.streaks((2, 3, 9, 10), 14)
    return tile


def _band() -> Tile:
    """Warnband: gelb-schwarz, das Gefahrenzeichen an der Nase."""
    tile = Tile()
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            hot = ((x + y) // 4) % 2 == 0
            tile.set(x, y, shift((214, 176, 46) if hot else (30, 28, 22), dither(x, y, 8)))
    return tile


def _tip() -> Tile:
    """Zünderspitze: dunkles Metall mit rotem Kern."""
    tile = Tile()
    tile.fill_gradient((70, 34, 30), (34, 18, 18), 6)
    centre = (modelkit.TILE - 1) / 2.0
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            if abs(x - centre) + abs(y - centre) < 4:
                tile.set(x, y, shift((206, 74, 44), dither(x, y, 12)))
    return tile


def _fin() -> Tile:
    """Leitwerk: dunkles Blech, quer gerippt."""
    tile = Tile()
    tile.fill_gradient((78, 82, 88), (44, 47, 53), 6)
    tile.bands(range(0, modelkit.TILE, 4), -18)
    return tile


ATLAS = Atlas({
    "stealth": _stealth,
    "panel": _panel,
    "edge": _edge,
    "canopy": _canopy,
    "intake": _intake,
    "exhaust": _exhaust,
    "bay": _bay,
    "underside": _underside,
    "sensor": _sensor,
    "nav_red": _nav_red,
    "nav_green": _nav_green,
    "casing": _casing,
    "band": _band,
    "tip": _tip,
    "fin": _fin,
})


# ---------------------------------------------------------------------------
# Der Bomber
# ---------------------------------------------------------------------------


def trailing_edge_z(distance: float) -> float:
    """Hinterkante an dieser Stelle der Spannweite, linear zwischen den Stützstellen."""
    for index in range(len(TRAILING_EDGE) - 1):
        inner_d, inner_z = TRAILING_EDGE[index]
        outer_d, outer_z = TRAILING_EDGE[index + 1]
        if distance <= outer_d:
            share = (distance - inner_d) / (outer_d - inner_d)
            return inner_z + (outer_z - inner_z) * share
    return TRAILING_EDGE[-1][1]


def half_thickness(distance: float) -> float:
    """Profildicke: in der Mitte am größten, zur Spitze hin auslaufend."""
    share = min(1.0, distance / HALF_SPAN)
    return TIP_HALF_THICKNESS + (ROOT_HALF_THICKNESS - TIP_HALF_THICKNESS) * (1.0 - share) ** 1.6


def build_bomber() -> list[dict]:
    """Nurflügler aus Spalten quer zur Flugrichtung.

    Jede Spalte trägt ihre eigene Vorderkante, Hinterkante und Dicke. Dadurch bekommt der
    Flügel ein echtes Profil – dick am Rumpf, dünn an der Spitze – und die Hinterkante kann
    der gezackten W-Form des Vorbilds folgen. Mit Rippen längs der Flugrichtung ginge beides
    nicht: dort ist jede Scheibe über die volle Spannweite gleich dick und gleich lang.
    """
    elements: list[dict] = []
    columns = int(HALF_SPAN / COLUMN_WIDTH)

    for index in range(columns):
        inner = index * COLUMN_WIDTH
        outer = inner + COLUMN_WIDTH
        middle = (inner + outer) / 2.0

        front = NOSE_Z + middle * LEADING_EDGE_SWEEP
        back = trailing_edge_z(middle)
        thickness = half_thickness(middle)
        top = CENTRE_Y + thickness
        bottom = CENTRE_Y - thickness * CAMBER

        # Die äußeren Spalten laufen als Kante aus, die inneren tragen die Bombenschachtluke.
        underside = "bay" if 1.0 < middle < 5.0 else "underside"
        skin = {"up": "panel", "north": "edge", "down": underside}

        for side in (-1, 1):
            left = CENTRE_X + (inner if side > 0 else -outer)
            right = CENTRE_X + (outer if side > 0 else -inner)
            elements.append(cube(ATLAS, (left, bottom, front), (right, top, back), "stealth", skin))

    # Kanzel: zwei Stufen, damit sie sich aus dem Rumpf hebt statt aufgeklebt zu wirken.
    elements.append(cube(ATLAS, (CENTRE_X - 2.0, CENTRE_Y + 1.4, 2.0), (CENTRE_X + 2.0, CENTRE_Y + 2.1, 6.4),
                         "stealth", {"up": "panel"}))
    elements.append(cube(ATLAS, (CENTRE_X - 1.4, CENTRE_Y + 2.1, 2.6), (CENTRE_X + 1.4, CENTRE_Y + 3.0, 5.4),
                         "canopy", {"down": "edge"}))

    # Triebwerksgondeln auf der Oberseite – beim Original liegen sie oben, damit der Rumpf
    # die heißen Düsen gegen Sicht von unten abschirmt.
    for side in (-1, 1):
        left = CENTRE_X + min(side * 2.6, side * 5.4)
        right = CENTRE_X + max(side * 2.6, side * 5.4)
        elements.append(cube(ATLAS, (left, CENTRE_Y + 1.45, 5.0), (right, CENTRE_Y + 2.35, 12.2),
                             "panel", {"north": "intake"}))
        elements.append(cube(ATLAS, (left + 0.4, CENTRE_Y + 1.5, 12.2), (right - 0.4, CENTRE_Y + 2.15, 13.2),
                             "edge", {"south": "exhaust"}))
        # Flache Auslassrinne dahinter: beim Vorbild wird das Abgas breitgezogen, damit es
        # schneller abkühlt. Sie bleibt schmal, sonst leuchtet der Rücken orange.
        elements.append(cube(ATLAS, (left + 1.0, CENTRE_Y + 1.42, 13.2), (right - 1.0, CENTRE_Y + 1.6, 14.9),
                             "exhaust"))

    elements += build_underside()
    return elements


def build_underside() -> list[dict]:
    """Die Seite, die man vom Boden aus sieht – und deshalb die wichtigste.

    Ein Bomber kreist über den Spielern; von unten war er bisher eine glatte dunkle Fläche.
    Luken, Sensoren, Fahrwerksklappen und Positionslichter geben ihm dort eine Silhouette.
    """
    elements: list[dict] = []
    floor = CENTRE_Y - half_thickness(0.0) * CAMBER

    # Zwei Bombenschachtluken mit einer Fuge dazwischen.
    for side in (-1, 1):
        left = CENTRE_X + min(side * 0.6, side * 3.9)
        right = CENTRE_X + max(side * 0.6, side * 3.9)
        elements.append(cube(ATLAS, (left, floor - 0.28, 5.2), (right, floor, 12.8),
                             "bay", {"down": "bay"}))
    elements.append(cube(ATLAS, (CENTRE_X - 0.3, floor - 0.34, 5.0), (CENTRE_X + 0.3, floor, 13.0), "edge"))

    # Fahrwerksklappen: eine vorn, zwei hinter dem Schacht.
    elements.append(cube(ATLAS, (CENTRE_X - 1.1, floor - 0.2, 2.6), (CENTRE_X + 1.1, floor, 4.6),
                         "underside", {"down": "bay"}))
    for side in (-1, 1):
        left = CENTRE_X + min(side * 4.6, side * 6.6)
        right = CENTRE_X + max(side * 4.6, side * 6.6)
        elements.append(cube(ATLAS, (left, floor - 0.2, 7.4), (right, floor, 10.4),
                             "underside", {"down": "bay"}))

    # Sensorkuppeln unter der Nase und an den Flanken.
    elements.append(cube(ATLAS, (CENTRE_X - 0.7, floor - 0.45, 1.6), (CENTRE_X + 0.7, floor, 2.5), "sensor"))
    for side in (-1, 1):
        left = CENTRE_X + min(side * 7.4, side * 8.6)
        right = CENTRE_X + max(side * 7.4, side * 8.6)
        under = CENTRE_Y - half_thickness(8.0) * CAMBER
        elements.append(cube(ATLAS, (left, under - 0.35, 7.0), (right, under, 8.2), "sensor"))

    # Positionslichter an den Flügelspitzen: links rot, rechts grün – von unten das
    # eindeutigste Zeichen dafür, dass da ein Flugzeug fliegt und keine Platte.
    tip_under = CENTRE_Y - half_thickness(13.5) * CAMBER
    tip_top = CENTRE_Y + half_thickness(13.5)
    for side, colour in ((-1, "nav_red"), (1, "nav_green")):
        left = CENTRE_X + min(side * 12.6, side * 13.9)
        right = CENTRE_X + max(side * 12.6, side * 13.9)
        front = NOSE_Z + 13.2 * LEADING_EDGE_SWEEP
        elements.append(cube(ATLAS, (left, tip_under - 0.25, front), (right, tip_top + 0.25, front + 1.1), colour))

    return elements


# ---------------------------------------------------------------------------
# Die Bombe
# ---------------------------------------------------------------------------

BOMB_NOSE_Z = 1.6
BOMB_TAIL_Z = 14.6
BOMB_RADIUS = 1.9


def build_bomb() -> list[dict]:
    elements: list[dict] = []

    # Zünderspitze und der Kegel dahinter.
    elements += octagon(ATLAS, CENTRE_X, CENTRE_Y, 0.55, BOMB_NOSE_Z, 2.4, "tip")
    elements += octagon(ATLAS, CENTRE_X, CENTRE_Y, 1.15, 2.4, 3.4, "casing")
    elements += octagon(ATLAS, CENTRE_X, CENTRE_Y, 1.6, 3.4, 4.4, "casing")

    # Körper mit dem Warnband vorn.
    elements += octagon(ATLAS, CENTRE_X, CENTRE_Y, BOMB_RADIUS, 4.4, 5.8, "band")
    elements += octagon(ATLAS, CENTRE_X, CENTRE_Y, BOMB_RADIUS, 5.8, 11.6, "casing")
    # Heckkonus.
    elements += octagon(ATLAS, CENTRE_X, CENTRE_Y, 1.45, 11.6, 12.8, "casing")

    # Vier Leitflossen im Kreuz, dazu der Ring, der sie hält.
    for horizontal in (True, False):
        half_long, half_short = 3.3, 0.22
        half_x = half_long if horizontal else half_short
        half_y = half_short if horizontal else half_long
        elements.append(cube(
            ATLAS,
            (CENTRE_X - half_x, CENTRE_Y - half_y, 11.0),
            (CENTRE_X + half_x, CENTRE_Y + half_y, BOMB_TAIL_Z),
            "fin",
        ))
    elements += octagon(ATLAS, CENTRE_X, CENTRE_Y, 3.0, 13.6, BOMB_TAIL_Z, "fin")

    return elements


# ---------------------------------------------------------------------------
# Anzeige
# ---------------------------------------------------------------------------


def bomber_display(elements: list[dict]) -> dict:
    # Der Nurflügler ist fast zwei Blöcke breit; in der Hand muss er entsprechend klein
    # und leicht angeschrägt liegen, sonst verdeckt er den halben Bildschirm.
    hand = centred_display(elements, (12, -140, 0), 0.62, offset=(1.5, 3.3, 0.0))
    return {
        "firstperson_righthand": hand,
        "firstperson_lefthand": dict(hand),
        "thirdperson_righthand": centred_display(elements, (18, -130, 0), 0.5, offset=(0.0, 2.5, 0.0)),
        "thirdperson_lefthand": centred_display(elements, (18, -130, 0), 0.5, offset=(0.0, 2.5, 0.0)),
        "gui": centred_display(elements, (28, -155, 0), 0.94, flat=True),
        "ground": centred_display(elements, (0, 0, 0), 0.6, offset=(0.0, 3.0, 0.0)),
        "fixed": centred_display(elements, (0, 180, 0), 0.96),
        "head": centred_display(elements, (0, 180, 0), 1.2, offset=(0.0, 13.0, 0.0)),
    }


def bomb_display(elements: list[dict]) -> dict:
    hand = centred_display(elements, (0, -125, 32), 0.55, offset=(1.0, 1.5, 0.0))
    return {
        "firstperson_righthand": hand,
        "firstperson_lefthand": dict(hand),
        "thirdperson_righthand": centred_display(elements, (0, -120, 30), 0.5, offset=(0.0, 2.5, 0.0)),
        "thirdperson_lefthand": centred_display(elements, (0, -120, 30), 0.5, offset=(0.0, 2.5, 0.0)),
        "gui": centred_display(elements, (22, -150, 24), 0.92, flat=True),
        "ground": centred_display(elements, (90, 0, 0), 0.55, offset=(0.0, 3.0, 0.0)),
        "fixed": centred_display(elements, (0, 180, 0), 0.9),
        "head": centred_display(elements, (0, 180, 0), 1.0, offset=(0.0, 13.0, 0.0)),
    }


def main() -> int:
    texture_path = TEXTURES_DIR / "stealth_bomber.png"
    ATLAS.save(texture_path)
    print(f"Atlas: {texture_path.relative_to(ROOT)} "
          f"({texture_path.stat().st_size} B, {ATLAS.size[0]}x{ATLAS.size[1]}, {len(ATLAS.slots)} Materialien)")

    for name, elements, display in (
        ("stealth_bomber", build_bomber(), None),
        ("bomber_bomb", build_bomb(), None),
    ):
        display = bomber_display(elements) if name == "stealth_bomber" else bomb_display(elements)
        modelkit.write(MODELS_DIR / f"{name}.json", modelkit.model(TEXTURE, elements, display))
        modelkit.write(ITEMS_DIR / f"{name}.json", {
            "model": {"type": "minecraft:model", "model": f"oneshotonekill:item/{name}"},
        })
        low, high = modelkit.bounds(elements)
        print(f"{name:16} {len(elements):3} Elemente, Ausdehnung {low} .. {high}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
