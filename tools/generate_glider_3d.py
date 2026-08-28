"""Erzeugt Textur und Modell des Gleitflugs.

    python tools/generate_glider_3d.py

Ausgabe:
  * textures/item/glider.png        – Materialatlas
  * models/item/glider.json         – der Gleiter mit ausgefahrenen Tragflaechen
  * items/glider.json

Ein Item-Modell wird meistens als 16x16-Bild in einer Leiste gesehen, und das entscheidet
alles. Zwei Vorgaenger sind daran gescheitert: erst zwei Drucktanks mit Warnband, dann ein
Ruecken-Geschirr mit angelegten Flaechen. Beide waren aus der Naehe schluessig und in der
Hotbar ein Klumpen, weil ihre Silhouette dieselbe war wie die jedes anderen Kastens.

Deshalb jetzt die Form, die man auf einen Blick erkennt: ein Gleiter mit **ausgefahrenen**
Tragflaechen ueber die volle Modellbreite, schlankem Rumpf, Leitwerk und zwei Gondeln. Die
Farben bleiben die der Tragflaechen im Flug (``client/renderer/GliderWingRenderer``) – dunkles
Blaugrau, stahlblaue Holme, cyanfarbene Kanten –, damit Geraet und Flug erkennbar dasselbe
Stueck Ausruestung sind.

Gestaffelt statt gedreht: Jedes Flaechenfeld ist ein eigener achsenparalleler Kasten mit
eigener Tiefe und Hoehe. Die Pfeilung entsteht aus der Staffelung, nicht aus einer Drehung –
das spart je Feld eine Drehmatrix und haelt die Ausdehnung des Modells vorhersagbar.

**Nur Vorderkanten, Rumpfstreifen und Duesenkehlen tragen ``tintindex``.** Alles andere behaelt
seine Farbe; die Laufzeit faehrt allein die Glut hoch und herunter – siehe
``item/runtime/DeviceLights.java``.
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
TEXTURE = "oneshotonekill:item/glider"

CENTRE = 8.0

# Rumpf: schlank und laenger als breit, damit die Flaechen ihn ueberragen.
BODY_HALF_WIDTH = 1.15
BODY_LOW = 6.9
BODY_HIGH = 9.5
BODY_NOSE = 2.4
BODY_TAIL = 12.9

# Tragflaechen: vier Felder je Seite bis an den Modellrand.
WING_PANELS = 4
WING_ROOT_X = 1.1
WING_TIP_X = 7.9
WING_ROOT_LEAD = 4.3
WING_TIP_LEAD = 8.6
WING_ROOT_CHORD = 5.0
WING_TIP_CHORD = 1.7
WING_ROOT_Y = 7.7
WING_TIP_RISE = 1.15
WING_ROOT_THICK = 0.85
WING_TIP_THICK = 0.4

# Leitwerk hinten auf dem Ruecken.
FIN_HALF_WIDTH = 0.32
FIN_HIGH = 13.1
FIN_FRONT = 10.4

# Gondeln unter den Flaechenwurzeln.
NOZZLE_OFFSET = 3.15
NOZZLE_RADIUS = 1.05
NOZZLE_FRONT = 7.6
NOZZLE_BACK = 12.6


# ---------------------------------------------------------------------------
# Materialien
# ---------------------------------------------------------------------------


def _steel() -> Tile:
    """Holme und Beschlaege: stahlblaues Blech mit feinen Zuegen."""
    tile = Tile()
    tile.fill_gradient((172, 190, 214), (112, 128, 152), 8)
    tile.streaks((2, 3, 9, 10), 12)
    tile.bands(range(4, modelkit.TILE, 7), -24)
    return tile


def _hull() -> Tile:
    """Gehaeuse: mittleres Blaugrau mit Plattenfugen.

    Heller als die Flaechen im Flug, und das mit Absicht: Ein Item wird meistens in einer
    schattigen Hotbar gesehen, und was dort dunkel gemeint ist, kommt schwarz an.
    """
    tile = Tile()
    tile.fill_gradient((78, 92, 118), (44, 53, 70), 7)
    tile.bands(range(0, modelkit.TILE, 5), -18)
    return tile


def _dark() -> Tile:
    """Kanten, Gurte und Halterungen: fast schwarz."""
    tile = Tile()
    tile.fill_gradient((52, 58, 72), (30, 34, 44), 4)
    return tile


def _vent() -> Tile:
    """Kuehlrippen auf dem Ruecken des Kernmoduls."""
    tile = Tile()
    tile.fill_gradient((88, 102, 128), (48, 57, 74), 6)
    for y in range(1, modelkit.TILE, 3):
        for x in range(modelkit.TILE):
            tile.set(x, y, shift(tile.get(x, y), -26 + dither(x, y, 6)))
    return tile


def _nozzle() -> Tile:
    """Duesenmantel: angelaufenes Metall, nach unten hin dunkler."""
    tile = Tile()
    tile.fill_gradient((132, 142, 158), (72, 79, 92), 7)
    tile.streaks((1, 2, 7, 8, 13, 14), 16)
    return tile


def _heat() -> Tile:
    """Leuchtstreifen, Vorderkanten und Duesenkehlen: fast weiss, damit die Einfaerbung traegt."""
    tile = Tile()
    tile.fill_gradient((250, 250, 246), (198, 200, 200), 5)
    centre = (modelkit.TILE - 1) / 2.0
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            # Nach innen heller: der Streifen soll einen Kern haben, keine gleichmaessige Flaeche.
            glow = 1.0 - min(1.0, abs(y - centre) / centre)
            tile.set(x, y, shift(tile.get(x, y), round(28 * glow * glow) - 14))
    for x in range(0, modelkit.TILE, 5):
        tile.set(x, int(centre), (255, 255, 255))
    return tile


ATLAS = Atlas({
    "steel": _steel,
    "hull": _hull,
    "dark": _dark,
    "vent": _vent,
    "nozzle": _nozzle,
    "heat": _heat,
}, columns=3)


# ---------------------------------------------------------------------------
# Das Geschirr
# ---------------------------------------------------------------------------


def build_rig() -> list[dict]:
    """Rumpf, zwei Tragflaechen, Leitwerk und zwei Gondeln."""
    elements: list[dict] = []
    elements += _body()
    elements += _fin()
    for side in (-1, 1):
        elements += _wing(side)
        elements += _nacelle(side)
    return elements


def _body() -> list[dict]:
    """Der Rumpf: vorn verjuengt, oben ein Leuchtstreifen ueber die ganze Laenge."""
    elements: list[dict] = []
    elements.append(cube(ATLAS, (CENTRE - BODY_HALF_WIDTH, BODY_LOW, BODY_NOSE + 1.6),
                         (CENTRE + BODY_HALF_WIDTH, BODY_HIGH, BODY_TAIL),
                         "hull", {"up": "vent"}))
    # Nase: ein schmaleres, flacheres Stueck davor – das macht aus dem Kasten ein Fluggeraet.
    elements.append(cube(ATLAS, (CENTRE - BODY_HALF_WIDTH + 0.45, BODY_LOW + 0.4, BODY_NOSE),
                         (CENTRE + BODY_HALF_WIDTH - 0.45, BODY_HIGH - 0.45, BODY_NOSE + 1.8), "steel"))
    # Leuchtstreifen auf dem Ruecken – die Verbindung zu den Flaechen im Flug.
    elements.append(cube(ATLAS, (CENTRE - 0.5, BODY_HIGH - 0.12, BODY_NOSE + 1.2),
                         (CENTRE + 0.5, BODY_HIGH + 0.16, BODY_TAIL - 0.6), "heat"))
    # Zwei Spanten fassen ihn ein.
    for depth in (BODY_NOSE + 3.4, BODY_TAIL - 2.6):
        elements.append(cube(ATLAS, (CENTRE - BODY_HALF_WIDTH - 0.16, BODY_LOW - 0.16, depth),
                             (CENTRE + BODY_HALF_WIDTH + 0.16, BODY_HIGH + 0.14, depth + 0.5), "dark"))
    return elements


def _fin() -> list[dict]:
    """Das Leitwerk: eine schmale Flosse mit leuchtender Vorderkante."""
    elements: list[dict] = []
    elements.append(cube(ATLAS, (CENTRE - FIN_HALF_WIDTH, BODY_HIGH - 0.3, FIN_FRONT + 0.9),
                         (CENTRE + FIN_HALF_WIDTH, FIN_HIGH, BODY_TAIL), "steel", {"north": "hull"}))
    elements.append(cube(ATLAS, (CENTRE - FIN_HALF_WIDTH - 0.1, BODY_HIGH + 0.4, FIN_FRONT + 0.55),
                         (CENTRE + FIN_HALF_WIDTH + 0.1, FIN_HIGH, FIN_FRONT + 1.05), "heat"))
    # Zwei kurze Hoehenruder – sie geben dem Heck Breite.
    for side in (-1, 1):
        elements.append(cube(ATLAS, (CENTRE + side * 0.3, BODY_HIGH - 0.1, BODY_TAIL - 2.1),
                             (CENTRE + side * 2.6, BODY_HIGH + 0.45, BODY_TAIL - 0.2), "steel"))
    return elements


def _wing(side: int) -> list[dict]:
    """Eine Tragflaeche aus vier gestaffelten Feldern, mit leuchtender Vorderkante.

    Jedes Feld ist ein eigener Kasten: nach aussen hin weiter hinten (Pfeilung), hoeher
    (V-Stellung), kuerzer (Verjuengung) und duenner. Zusammen ergibt das die Silhouette, die
    ein Gleiter braucht, ohne dass ein einziges Element gedreht werden muesste.
    """
    elements: list[dict] = []
    for index in range(WING_PANELS):
        inner_share = index / WING_PANELS
        outer_share = (index + 1) / WING_PANELS

        inner_x = CENTRE + side * _mix(WING_ROOT_X, WING_TIP_X, inner_share)
        outer_x = CENTRE + side * _mix(WING_ROOT_X, WING_TIP_X, outer_share)
        low_x, high_x = sorted((inner_x, outer_x))

        # Gemittelt ueber das Feld: Ein Kasten hat nur eine Tiefe und eine Hoehe.
        share = (inner_share + outer_share) / 2.0
        lead = _mix(WING_ROOT_LEAD, WING_TIP_LEAD, share)
        chord = _mix(WING_ROOT_CHORD, WING_TIP_CHORD, share)
        rise = WING_TIP_RISE * share * share
        thick = _mix(WING_ROOT_THICK, WING_TIP_THICK, share)
        low_y = WING_ROOT_Y + rise
        high_y = low_y + thick

        elements.append(cube(ATLAS, (low_x, low_y, lead), (high_x, high_y, lead + chord),
                             "steel", {"down": "hull"}))
        # Vorderkante: schmal, leuchtend, ueber die ganze Spannweite durchgehend.
        elements.append(cube(ATLAS, (low_x, low_y - 0.08, lead - 0.5), (high_x, high_y + 0.08, lead), "heat"))
        # Randbogen am aeusseren Feldende, nach oben gestellt.
        if index == WING_PANELS - 1:
            edge = high_x if side > 0 else low_x
            elements.append(cube(ATLAS, (edge - 0.3, low_y, lead), (edge + 0.3, high_y + 1.5, lead + chord * 0.7),
                                 "steel", {"north": "heat"}))
    return elements


def _nacelle(side: int) -> list[dict]:
    """Eine Achteckgondel unter der Flaechenwurzel, mit gluehender Kehle nach hinten."""
    elements: list[dict] = []
    centre_x = CENTRE + side * NOZZLE_OFFSET
    height = WING_ROOT_Y - 0.35
    # prism setzt fuer axis="z" den Querschnitt aus (x, y) zusammen.
    elements += prism(ATLAS, centre_x, height, NOZZLE_RADIUS, NOZZLE_FRONT, NOZZLE_BACK - 1.0,
                      "nozzle", axis="z", sides=8)
    elements += prism(ATLAS, centre_x, height, NOZZLE_RADIUS - 0.22, NOZZLE_BACK - 1.0, NOZZLE_BACK,
                      "heat", axis="z", sides=8)
    # Pylon zur Flaeche.
    elements.append(cube(ATLAS, (centre_x - 0.34, height, NOZZLE_FRONT + 1.2),
                         (centre_x + 0.34, WING_ROOT_Y + 0.6, NOZZLE_FRONT + 2.6), "dark"))
    return elements


def _mix(a: float, b: float, share: float) -> float:
    return a + (b - a) * share


def mark_tint(elements: list[dict]) -> list[dict]:
    """Nur die heissen Flaechen werden eingefaerbt."""
    wanted = tuple(ATLAS.uv("heat"))
    for element in elements:
        for face in element["faces"].values():
            if tuple(face["uv"]) == wanted:
                face["tintindex"] = 0
    return elements


def display_for(elements: list[dict], rotation: tuple[float, float, float], fill: float) -> dict:
    """Blickwinkel, unter denen die Spannweite zu sehen ist.

    Der Punkt ist die Drehung um die Hochachse. Die anderen Geraete der Mod stehen um etwa
    -145 Grad, weil sie laengs gehalten werden; ein Gleiter faellt dabei genau in die Achse
    seiner Spannweite und ist von vorn nur noch ein Strich. Er steht deshalb quer und leicht
    von oben – dieselbe Ansicht, in der man ein Flugzeugmodell in die Hand nimmt.
    """
    hand = centred_display(elements, rotation, fill, offset=(1.0, 5.5, 0.0))
    # Der Versatz nach oben ist bewusst gross. Vanilla deckelt jede Verschiebung bei 5
    # (``ItemTransform.Deserializer.MAX_TRANSLATION``), und da ``centred_display`` das Modell
    # vorher mittig rueckt, bleibt davon fast der ganze Weg fuer die Hoehe uebrig.
    return {
        "firstperson_righthand": hand,
        "firstperson_lefthand": dict(hand),
        "thirdperson_righthand": centred_display(elements, rotation, fill * 0.92, offset=(0.0, 4.5, 0.0)),
        "thirdperson_lefthand": centred_display(elements, rotation, fill * 0.92, offset=(0.0, 4.5, 0.0)),
        "gui": centred_display(elements, (34, -24, 0), 0.98, flat=True),
        "ground": centred_display(elements, (24, -30, 0), 0.6, offset=(0.0, 3.0, 0.0)),
        "fixed": centred_display(elements, (18, 0, 0), 0.98),
        "head": centred_display(elements, (0, 0, 0), 1.1, offset=(0.0, 13.0, 0.0)),
    }


def main() -> int:
    texture_path = TEXTURES_DIR / "glider.png"
    ATLAS.save(texture_path)
    print(f"Atlas: {texture_path.relative_to(ROOT)} "
          f"({texture_path.stat().st_size} B, {ATLAS.size[0]}x{ATLAS.size[1]}, {len(ATLAS.slots)} Materialien)")

    for name, builder, rotation, fill, default in (
        ("glider", build_rig, (26, -34, 4), 0.66, 0x2A6E9E),
    ):
        elements = mark_tint(builder())
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
        print(f"{name:14} {len(elements):3} Elemente, Ausdehnung "
              f"{[round(v, 2) for v in low]} .. {[round(v, 2) for v in high]}, {tinted} Flächen einfärbbar")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
