"""Erzeugt Textur und Modell des Meisterdolchs, der letzten Stufe im Waffenspiel.

    python tools/models/generate_master_dagger_3d.py [--preview PFAD]

Ausgabe:
  * textures/item/master_dagger.png   – gemeinsamer Materialatlas
  * models/item/master_dagger.json    – der Dolch
  * items/master_dagger.json          – Item-Definition mit einer Farbebene für den Edelstein

**Eingebunden wird er ohne neues Item.** ``GunGameTier#masterDagger`` setzt die Komponente
``ITEM_MODEL`` auf ``oneshotonekill:master_dagger``. Das Basisitem bleibt ein goldenes Schwert,
damit Trefferprüfung und Aufräumen unverändert greifen.

**Die Klinge liegt wie bei Vanilla-Schwertern auf der Diagonale.** Das Modell zeigt von links
unten (Knauf) nach rechts oben (Spitze) und ist in Z dünn. Dadurch passen die Anzeigewerte
von ``item/handheld`` unverändert, und die Haltung in der Hand stimmt ohne eigene Rechnung.

Gebaut wird in einem eigenen Dolch-Raum: ``u`` läuft vom Knauf (0) zur Spitze (16), ``v`` quer
zur Klinge, ``w`` durch die Dicke. :func:`part` setzt einen Quader in diesem Raum auf die
Diagonale und dreht ihn um 45 Grad um Z. Jedes Teil trägt damit genau eine Drehung, das
Modellformat kennt nicht mehr.

**Der Edelstein ist die einzige einfärbbare Fläche** (``tintindex`` 0). Er ist fast weiß
gemalt, damit die Einfärbung den ganzen Farbraum behält; die Facetten bleiben als
Helligkeitsunterschied stehen und überstehen jede Multiplikation.

Mit ``--preview`` rendert das Skript vier Ansichten des Modells als PNG, ohne den Client zu
starten. Das ist eine grobe Vorschau aus Flächenfarben, keine Wiedergabe von Minecrafts
Beleuchtung.
"""

from __future__ import annotations

import argparse
import math
import pathlib

import modelkit
from modelkit import Atlas, Tile, cube, shift

# Das Projektverzeichnis (MOD). Die älteren Generatoren rechnen noch mit der alten Ablage
# direkt unter tools/ und landen beim Ausführen in tools/src; hier stimmt der Pfad.
ROOT = pathlib.Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets/oneshotonekill"
MODELS_DIR = ASSETS / "models/item"
ITEMS_DIR = ASSETS / "items"
TEXTURES_DIR = ASSETS / "textures/item"
TEXTURE = "oneshotonekill:item/master_dagger"

CENTRE = 8.0
COS45 = math.sqrt(0.5)

GEM_COLOUR = 0xD21F3C


# ---------------------------------------------------------------------------
# Materialien
# ---------------------------------------------------------------------------


def _gold() -> Tile:
    """Polierte Fläche: warmes Gold mit einem hellen Band, als hätte es Licht gefangen."""
    tile = Tile()
    tile.fill_gradient((255, 216, 96), (186, 132, 24), 6)
    tile.bands(range(3, 6), 26)
    return tile


def _gold_dark() -> Tile:
    """Schatten, Ringe und Krallen: dunkles, altes Gold."""
    tile = Tile()
    tile.fill_gradient((156, 108, 20), (96, 62, 8), 5)
    tile.streaks((2, 5, 9, 13), 12)
    return tile


def _blade() -> Tile:
    """Der Rücken der Klinge: fast Weißgold, längs gebürstet."""
    tile = Tile()
    tile.fill_gradient((255, 242, 176), (222, 178, 66), 6)
    tile.streaks((1, 2, 6, 7, 12, 13), 14)
    return tile


def _edge() -> Tile:
    """Die Schneiden: heller als der Rücken, damit die Klinge zur Kante hin aufblitzt."""
    tile = Tile()
    tile.fill_gradient((255, 250, 214), (240, 204, 108), 5)
    return tile


def _fuller() -> Tile:
    """Hohlkehle: dunkles Bernstein, das die Klinge in der Mitte teilt."""
    tile = Tile()
    tile.fill_gradient((126, 82, 14), (78, 48, 6), 5)
    return tile


def _grip() -> Tile:
    """Griff: schwarzes Leder mit diagonalem Golddraht."""
    tile = Tile()
    tile.fill_gradient((44, 38, 38), (22, 19, 20), 5)
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            if (x + y) % 5 == 0:
                tile.set(x, y, (196, 150, 40))
    return tile


def _gem() -> Tile:
    """Edelstein: fast weiß mit dunklen Facettenkanten, wird eingefärbt."""
    tile = Tile()
    tile.fill_gradient((250, 250, 252), (206, 206, 214), 4)
    centre = (modelkit.TILE - 1) / 2.0
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            # Diagonale Facetten: Kanten durch die Mitte und um sie herum.
            on_edge = abs(x - centre) == abs(y - centre) or abs(x - y) in (0, 8) or abs(x + y - 15) in (0, 8)
            if on_edge:
                tile.set(x, y, shift(tile.get(x, y), -70))
    # Ein heller Glanzpunkt oben links.
    for x, y in ((3, 3), (4, 3), (3, 4)):
        tile.set(x, y, (255, 255, 255))
    return tile


def _rune() -> Tile:
    """Gravur: dunkles Gold mit hellen Punkten, die wie Zeichen aussehen."""
    tile = Tile()
    tile.fill_gradient((120, 80, 14), (84, 54, 8), 4)
    for x, y in ((3, 4), (4, 4), (4, 5), (4, 6), (8, 3), (9, 3), (8, 4), (8, 5), (9, 5), (12, 8), (11, 9), (12, 10)):
        tile.set(x, y, (250, 214, 110))
    return tile


ATLAS = Atlas({
    "gold": _gold,
    "gold_dark": _gold_dark,
    "blade": _blade,
    "edge": _edge,
    "fuller": _fuller,
    "grip": _grip,
    "gem": _gem,
    "rune": _rune,
})


# ---------------------------------------------------------------------------
# Geometrie im Dolch-Raum
# ---------------------------------------------------------------------------


def part(
    u: tuple[float, float],
    v: tuple[float, float],
    w: tuple[float, float],
    material: str,
    face_materials: dict[str, str] | None = None,
    extra_angle: float = 0.0,
) -> dict:
    """Ein Quader im Dolch-Raum, auf die Diagonale gesetzt und um 45 Grad gedreht.

    ``u`` läuft entlang der Klinge, ``v`` quer dazu, ``w`` durch die Dicke. ``extra_angle``
    dreht das Teil zusätzlich um seine eigene Mitte, etwa für Parierflügel oder Rauten.
    """
    centre_u = (u[0] + u[1]) / 2.0
    centre_v = (v[0] + v[1]) / 2.0
    # Lokal liegt die Klinge entlang +Y. Die Drehung um -45 Grad um Z legt +Y auf die
    # Diagonale nach rechts oben und +X nach rechts unten.
    x = CENTRE + (centre_u - CENTRE) * COS45 + centre_v * COS45
    y = CENTRE + (centre_u - CENTRE) * COS45 - centre_v * COS45
    half_along = (u[1] - u[0]) / 2.0
    half_across = (v[1] - v[0]) / 2.0
    z0, z1 = CENTRE + w[0], CENTRE + w[1]
    return cube(
        ATLAS,
        (x - half_across, y - half_along, z0),
        (x + half_across, y + half_along, z1),
        material,
        face_materials,
        rotation={"origin": [round(x, 3), round(y, 3), round((z0 + z1) / 2.0, 3)], "axis": "z",
                  "angle": -45.0 + extra_angle},
    )


# Stufen der Klinge: (von, bis, halbe Breite). Jede Stufe ist schmaler als die vorige.
BLADE_STAGES = ((6.5, 10.4, 1.7), (10.4, 13.2, 1.35), (13.2, 15.0, 0.95))
POMMEL_END = 2.2
GRIP_END = 5.3
GUARD_END = 6.5


def build_dagger() -> list[dict]:
    """Klinge, Parierstange, Edelstein, Griff und Knauf."""
    elements: list[dict] = []

    # Klinge: je Stufe ein dünner Flügel mit Schneide und ein dickerer Rücken in der Mitte.
    # Zusammen ergibt das den linsenförmigen Querschnitt einer echten Klinge.
    for start, end, half in BLADE_STAGES:
        elements.append(part((start, end), (-half, half), (-0.30, 0.30), "edge"))
        elements.append(part((start, end), (-half * 0.42, half * 0.42), (-0.55, 0.55), "blade"))
    # Spitze: ein kleines Quadrat auf der Diagonale, das die letzte Stufe zuspitzt.
    elements.append(part((14.9, 15.9), (-0.55, 0.55), (-0.22, 0.22), "edge", extra_angle=45.0))

    # Hohlkehle: dunkle Streifen auf beiden Seiten des Rückens, über zwei Drittel der Länge.
    for side in (-1, 1):
        elements.append(part((8.2, 13.2), (-0.20, 0.20), (side * 0.55, side * 0.58), "fuller"))
    # Gravuren auf den Flügeln der ersten Stufe, vorn und hinten.
    for side in (-1, 1):
        for offset in (-1.0, 1.0):
            elements.append(part((8.4, 10.2), (offset - 0.35, offset + 0.35), (side * 0.30, side * 0.33), "rune"))

    # Parierstange: ein Mittelstück, zwei Arme und an deren Enden Krallen, die zur Klinge hin
    # hochgezogen sind. Alles überlappt, damit nichts lose im Raum hängt.
    elements.append(part((GRIP_END, GUARD_END), (-1.7, 1.7), (-1.0, 1.0), "gold"))
    for side in (-1, 1):
        arm = (side * 1.5, side * 3.8)
        elements.append(part((GRIP_END + 0.15, GUARD_END - 0.1), (min(arm), max(arm)), (-0.8, 0.8), "gold_dark"))
        claw = (side * 3.0, side * 3.9)
        elements.append(part((GRIP_END + 0.15, GUARD_END + 1.3), (min(claw), max(claw)), (-0.6, 0.6), "gold"))
        # Zweite, kürzere Kralle innen, damit die Parierstange gezackt wirkt.
        inner = (side * 2.2, side * 2.9)
        elements.append(part((GRIP_END + 0.15, GUARD_END + 0.6), (min(inner), max(inner)), (-0.5, 0.5), "gold"))
    # Unterseite der Parierstange: ein dunkler Streifen, der ihr Gewicht gibt.
    elements.append(part((GRIP_END - 0.2, GRIP_END + 0.15), (-2.2, 2.2), (-0.85, 0.85), "gold_dark"))

    # Edelstein vorn und hinten: eine Raute in der Mitte der Parierstange. Beide tragen die
    # Farbebene 0 und leuchten damit in derselben Farbe.
    for side in (-1, 1):
        elements.append(part((GRIP_END + 0.15, GRIP_END + 1.35), (-0.6, 0.6), (side * 0.95, side * 1.35), "gem",
                             extra_angle=45.0))

    # Griff: ein schmaler Quader mit drei Ringen.
    elements.append(part((POMMEL_END, GRIP_END), (-0.7, 0.7), (-0.7, 0.7), "grip"))
    for centre in (POMMEL_END + 0.25, (POMMEL_END + GRIP_END) / 2.0, GRIP_END - 0.25):
        elements.append(part((centre - 0.2, centre + 0.2), (-0.95, 0.95), (-0.95, 0.95), "gold_dark"))

    # Knauf: ein Ring mit Kugel am Ende.
    elements.append(part((0.9, POMMEL_END), (-1.25, 1.25), (-1.25, 1.25), "gold"))
    elements.append(part((1.2, 1.9), (-1.5, 1.5), (-0.8, 0.8), "gold_dark"))
    elements.append(part((0.15, 0.95), (-0.75, 0.75), (-0.75, 0.75), "gold_dark"))

    return mark_gem(elements)


def mark_gem(elements: list[dict]) -> list[dict]:
    """Meldet die Flächen des Edelsteins für die Farbebene 0 an."""
    wanted = tuple(ATLAS.uv("gem"))
    for element in elements:
        for face in element["faces"].values():
            if tuple(face["uv"]) == wanted:
                face["tintindex"] = 0
    return elements


# ---------------------------------------------------------------------------
# Anzeige
# ---------------------------------------------------------------------------


def display() -> dict:
    """Die Anzeigewerte von ``item/handheld`` für Schwerter.

    Das Modell liegt wie das Vanilla-Schwert auf der Diagonale, deshalb gelten dieselben
    Werte. Der Dolch ist kürzer als ein Schwert und wirkt dadurch von selbst wie ein Dolch.
    """
    return {
        "thirdperson_righthand": {"rotation": [0, -90, 55], "translation": [0, 4, 0.5], "scale": [0.85, 0.85, 0.85]},
        "thirdperson_lefthand": {"rotation": [0, 90, -55], "translation": [0, 4, 0.5], "scale": [0.85, 0.85, 0.85]},
        "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
        "firstperson_lefthand": {"rotation": [0, 90, -25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
        "gui": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1.0, 1.0, 1.0]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
        "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1.0, 1.0, 1.0]},
        "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1.0, 1.0, 1.0]},
    }


# ---------------------------------------------------------------------------
# Vorschau
# ---------------------------------------------------------------------------


def _average(material: str) -> tuple[int, int, int]:
    pixels = [pixel for row in dict(ATLAS.painters)[material]().pixels for pixel in row]
    return tuple(round(sum(pixel[channel] for pixel in pixels) / len(pixels)) for channel in range(3))  # type: ignore[return-value]


def _tint(colour: tuple[int, int, int], rgb: int) -> tuple[int, int, int]:
    return tuple(round(colour[i] * ((rgb >> shift_bits) & 0xFF) / 255.0) for i, shift_bits in enumerate((16, 8, 0)))  # type: ignore[return-value]


def _faces(element: dict) -> list[tuple[list[tuple[float, float, float]], tuple[float, float, float], str]]:
    """Die sechs Flächen eines Elements als Vierecke samt Normale, Drehung eingerechnet."""
    (x0, y0, z0), (x1, y1, z1) = element["from"], element["to"]
    matrix = modelkit.element_rotation(element)
    origin = (element.get("rotation") or {}).get("origin", [0.0, 0.0, 0.0])

    def turn(point: tuple[float, float, float]) -> tuple[float, float, float]:
        if matrix is None:
            return point
        local = tuple(point[axis] - origin[axis] for axis in range(3))
        return tuple(origin[axis] + value for axis, value in enumerate(modelkit.apply(matrix, local)))  # type: ignore[return-value]

    quads = {
        "north": ([(x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0)], (0, 0, -1)),
        "south": ([(x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)], (0, 0, 1)),
        "west": ([(x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0)], (-1, 0, 0)),
        "east": ([(x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1)], (1, 0, 0)),
        "up": ([(x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0)], (0, 1, 0)),
        "down": ([(x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1)], (0, -1, 0)),
    }
    result = []
    for name, (corners, normal) in quads.items():
        turned_normal = normal if matrix is None else modelkit.apply(matrix, normal)
        result.append(([turn(corner) for corner in corners], turned_normal, name))
    return result


def render_preview(elements: list[dict], path: pathlib.Path) -> None:
    """Vier Ansichten als Bild: vorn, hinten, schräg von oben und von der Seite."""
    from PIL import Image, ImageDraw

    views = (
        ("vorn", (0.0, 0.0, 0.0)),
        ("hinten", (0.0, 180.0, 0.0)),
        ("schräg", (-25.0, 35.0, 0.0)),
        ("seite", (0.0, 90.0, 0.0)),
    )
    size = 360
    scale = size / 22.0
    sheet = Image.new("RGB", (size * len(views), size), (24, 28, 36))
    draw = ImageDraw.Draw(sheet)
    averages = {name: _average(name) for name in ATLAS.slots}
    light = (-0.35, 0.8, 0.5)

    for index, (label, angles) in enumerate(views):
        view = modelkit.rotation_matrix(angles)
        drawn = []
        for element in elements:
            for corners, normal, face_name in _faces(element):
                material_uv = element["faces"][face_name]["uv"]
                material = next(name for name, _ in ATLAS.slots.items() if ATLAS.uv(name) == material_uv)
                base = averages[material]
                if element["faces"][face_name].get("tintindex") == 0:
                    base = _tint(base, GEM_COLOUR)
                centred = [tuple(value - CENTRE for value in corner) for corner in corners]
                projected = [modelkit.apply(view, corner) for corner in centred]
                view_normal = modelkit.apply(view, normal)
                if view_normal[2] <= 0.0:
                    continue
                shade = 0.55 + 0.45 * max(0.0, sum(a * b for a, b in zip(view_normal, light)) / 1.0)
                depth = sum(point[2] for point in projected) / 4.0
                drawn.append((depth, projected, tuple(min(255, round(channel * shade)) for channel in base)))
        for _, projected, colour in sorted(drawn, key=lambda item: item[0]):
            points = [(index * size + size / 2 + point[0] * scale, size / 2 - point[1] * scale) for point in projected]
            draw.polygon(points, fill=colour, outline=(10, 10, 12))
        draw.text((index * size + 8, 6), label, fill=(210, 214, 222))

    path.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(path)


# ---------------------------------------------------------------------------
# Ausgabe
# ---------------------------------------------------------------------------


def main() -> int:
    parser = argparse.ArgumentParser(description="Erzeugt Textur und Modell des Meisterdolchs.")
    parser.add_argument("--preview", type=pathlib.Path, default=None,
                        help="Pfad für ein Vorschaubild mit vier Ansichten (wird nicht in die Mod geschrieben)")
    args = parser.parse_args()

    texture_path = TEXTURES_DIR / "master_dagger.png"
    ATLAS.save(texture_path)
    print(f"Atlas: {texture_path.relative_to(ROOT)} "
          f"({texture_path.stat().st_size} B, {ATLAS.size[0]}x{ATLAS.size[1]}, {len(ATLAS.slots)} Materialien)")

    elements = build_dagger()
    modelkit.write(MODELS_DIR / "master_dagger.json", modelkit.model(TEXTURE, elements, display()))
    modelkit.write(ITEMS_DIR / "master_dagger.json", {
        "model": {
            "type": "minecraft:model",
            "model": "oneshotonekill:item/master_dagger",
            "tints": [{"type": "minecraft:dye", "default": modelkit.opaque(GEM_COLOUR)}],
        },
    })

    low, high = modelkit.bounds(elements)
    gem_faces = sum(1 for element in elements for face in element["faces"].values() if face.get("tintindex") == 0)
    print(f"master_dagger  {len(elements):3} Elemente, Ausdehnung "
          f"{[round(v, 2) for v in low]} .. {[round(v, 2) for v in high]}, Flächen mit Farbebene 0: {gem_faces}")
    outside = [axis for axis in range(3) if low[axis] < -0.001 or high[axis] > 16.001]
    if outside:
        print(f"WARNUNG: Das Modell ragt in Achse(n) {outside} über den 16er-Raum hinaus.")

    if args.preview is not None:
        render_preview(elements, args.preview)
        print(f"Vorschau: {args.preview}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
