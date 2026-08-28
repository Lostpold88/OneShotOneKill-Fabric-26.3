"""Bausteine für die 3D-Item-Modelle der Mod.

Minigun und Tarnkappenbomber brauchen dieselben Dinge: einen Materialatlas aus 16x16-Kacheln,
Quader, Achtkant-Zylinder und Anzeige-Transformationen, die sich selbst auf das Modell
einmitten. Die stehen hier einmal, statt in jedem Generator noch einmal.

Eine Lehre steckt fest in :func:`octagon`: Modellteile vereinigen sich, sie schneiden sich
nicht. Der übliche Trick „Quader plus derselbe um 45 Grad gedreht“ ergibt deshalb keinen
Zylinder, sondern einen achtzackigen Stern.
"""

from __future__ import annotations

import json
import math
import pathlib

from PIL import Image

TILE = 16
FACES = ("north", "east", "south", "west", "up", "down")

# 4x4-Bayer-Matrix: gibt den Kacheln eine feine, regelmäßige Körnung statt Rauschen.
BAYER = (
    (0, 8, 2, 10),
    (12, 4, 14, 6),
    (3, 11, 1, 9),
    (15, 7, 13, 5),
)

# Kurze Seite eines Achtecks im Verhältnis zum Innenradius.
OCTAGON_SHORT = math.tan(math.pi / 8)

# Damit die Stirnflächen eines Achtecks nicht exakt aufeinanderliegen und um die Tiefenprüfung
# streiten, sitzt jede Scheibe einen Hauch kürzer als die vorige.
DEPTH_STAGGER = 0.002

Colour = tuple[int, int, int]


# ---------------------------------------------------------------------------
# Farbe und Kacheln
# ---------------------------------------------------------------------------


def blend(a: Colour, b: Colour, t: float) -> Colour:
    return (
        round(a[0] + (b[0] - a[0]) * t),
        round(a[1] + (b[1] - a[1]) * t),
        round(a[2] + (b[2] - a[2]) * t),
    )


def opaque(rgb: int) -> int:
    """Ein Farbwert fuer ``tints``, dem die Deckkraft nicht fehlt.

    ``minecraft:dye`` liest seinen ``default`` ueber ``ExtraCodecs.RGB_COLOR_CODEC``, und das
    ist schlicht ``Codec.INT`` – der gelesene Wert geht ungefiltert als ARGB in den Tint. Ein
    Wert der Form ``0xRRGGBB`` hat damit die Deckkraft null: die getoenten Flaechen des Modells
    verschwinden, solange der Gegenstand noch keine eigene Farbe traegt. Vanilla faellt das
    nicht auf, weil dort ueberall Lederfarben mit gesetztem Alphabyte stehen.

    Zurueck kommt die vorzeichenbehaftete 32-Bit-Zahl, denn nur so schreibt sie ``json`` als
    einzelnen Zahlenwert und nicht als Wert jenseits des Int-Bereichs.
    """
    return (0xFF000000 | (rgb & 0xFFFFFF)) - 0x100000000


def shift(colour: Colour, amount: int) -> Colour:
    return (
        max(0, min(255, colour[0] + amount)),
        max(0, min(255, colour[1] + amount)),
        max(0, min(255, colour[2] + amount)),
    )


def dither(x: int, y: int, strength: int) -> int:
    return round((BAYER[y % 4][x % 4] / 15.0 - 0.5) * strength)


class Tile:
    """Eine 16x16-Materialkachel; gezeichnet wird in RGB, der Atlas setzt Alpha auf voll."""

    def __init__(self) -> None:
        self.pixels: list[list[Colour]] = [[(0, 0, 0) for _ in range(TILE)] for _ in range(TILE)]

    def get(self, x: int, y: int) -> Colour:
        return self.pixels[max(0, min(TILE - 1, y))][max(0, min(TILE - 1, x))]

    def set(self, x: int, y: int, colour: Colour) -> None:
        if 0 <= x < TILE and 0 <= y < TILE:
            self.pixels[y][x] = (
                max(0, min(255, colour[0])),
                max(0, min(255, colour[1])),
                max(0, min(255, colour[2])),
            )

    def fill_gradient(self, top: Colour, bottom: Colour, grain: int = 0) -> None:
        for y in range(TILE):
            row = blend(top, bottom, y / (TILE - 1))
            for x in range(TILE):
                self.set(x, y, shift(row, dither(x, y, grain)))

    def streaks(self, columns: tuple[int, ...], amount: int) -> None:
        """Längsstreifen – lässt zylindrische Teile gebürstet wirken."""
        for index, x in enumerate(columns):
            for y in range(TILE):
                self.set(x, y, shift(self.get(x, y), amount if index % 2 == 0 else -amount))

    def bands(self, rows: range, amount: int) -> None:
        for y in rows:
            for x in range(TILE):
                self.set(x, y, shift(self.get(x, y), amount))


class Atlas:
    """Ein Materialatlas: benannte 16x16-Kacheln, im Raster von links oben."""

    def __init__(self, painters: dict[str, callable], columns: int = 4) -> None:
        self.painters = painters
        self.columns = columns
        self.rows = (len(painters) + columns - 1) // columns
        self.slots = {name: (index % columns, index // columns) for index, name in enumerate(painters)}

    @property
    def size(self) -> tuple[int, int]:
        return self.columns * TILE, self.rows * TILE

    def uv(self, material: str) -> list[float]:
        """UV im 0..16-Raum, das Vanilla unabhängig von der Texturgröße erwartet."""
        column, row = self.slots[material]
        step_x = 16 / self.columns
        step_y = 16 / self.rows
        return [column * step_x, row * step_y, (column + 1) * step_x, (row + 1) * step_y]

    def save(self, path: pathlib.Path) -> None:
        width, height = self.size
        image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        for name, painter in self.painters.items():
            column, row = self.slots[name]
            tile = painter()
            for y in range(TILE):
                for x in range(TILE):
                    red, green, blue = tile.pixels[y][x]
                    image.putpixel((column * TILE + x, row * TILE + y), (red, green, blue, 255))
        path.parent.mkdir(parents=True, exist_ok=True)
        image.save(path)


# ---------------------------------------------------------------------------
# Geometrie
# ---------------------------------------------------------------------------


def cube(
    atlas: Atlas,
    start: tuple[float, float, float],
    end: tuple[float, float, float],
    material: str,
    face_materials: dict[str, str] | None = None,
    rotation: dict | None = None,
) -> dict:
    """Ein Quader; einzelne Flächen können ein abweichendes Material bekommen."""
    faces = {}
    for face in FACES:
        chosen = (face_materials or {}).get(face, material)
        faces[face] = {"uv": atlas.uv(chosen), "texture": "#atlas"}

    element: dict = {
        "from": [round(value, 3) for value in start],
        "to": [round(value, 3) for value in end],
        "shade": True,
        "faces": faces,
    }
    if rotation is not None:
        element["rotation"] = rotation
    return element


def prism(
    atlas: Atlas,
    centre_a: float,
    centre_b: float,
    radius: float,
    start: float,
    end: float,
    material: str,
    axis: str = "z",
    sides: int = 8,
    face_materials: dict[str, str] | None = None,
) -> list[dict]:
    """Regelmäßiges n-Eck als Zylinder entlang einer Achse, aus n/2 Balken.

    Der naheliegende Weg – ein Quader plus derselbe um 45 Grad gedreht – ergibt keinen
    Zylinder, sondern einen achtzackigen Stern: Modellteile vereinigen sich, sie schneiden
    sich nicht.

    Was stattdessen trägt: ein n-Eck (n gerade) ist genau die Vereinigung von n/2 Balken.
    Jeder reicht über den vollen Innendurchmesser und ist quer dazu nur ``r·tan(180°/n)``
    hoch – also gerade so hoch wie der mittlere Streifen des n-Ecks zwischen zwei
    gegenüberliegenden Kanten. Gedreht werden sie in Schritten von ``360°/n``.

    Beliebige Drehwinkel erlaubt das Modellformat erst seit 26.2
    (``CuboidModelElement.Deserializer`` liest ``angle`` als freies Float und kennt daneben
    die Euler-Form ``x``/``y``/``z``). Vorher waren nur Vielfache von 22,5 Grad zulässig und
    damit auch nur das Achteck.
    """
    if sides < 4 or sides % 2 != 0:
        raise ValueError(f"Seitenzahl muss gerade und mindestens 4 sein, nicht {sides}")

    across = radius * math.tan(math.pi / sides)
    pieces: list[dict] = []

    for index in range(sides // 2):
        # Jede Scheibe sitzt einen Hauch kürzer als die vorige, damit die Stirnflächen nicht
        # exakt aufeinanderliegen und um die Tiefenprüfung streiten.
        inset = index * DEPTH_STAGGER
        rotation = None if index == 0 else {
            "origin": origin_of(axis, centre_a, centre_b, start),
            "axis": axis,
            "angle": round(index * 360.0 / sides, 4),
        }
        pieces.append(cube(
            atlas,
            corner_of(axis, centre_a - radius, centre_b - across, start + inset),
            corner_of(axis, centre_a + radius, centre_b + across, end - inset),
            material,
            face_materials,
            rotation,
        ))

    return pieces


def octagon(
    atlas: Atlas,
    centre_a: float,
    centre_b: float,
    radius: float,
    start: float,
    end: float,
    material: str,
    axis: str = "z",
    face_materials: dict[str, str] | None = None,
) -> list[dict]:
    """Achteckiger Zylinder – der Sonderfall von :func:`prism` mit acht Seiten."""
    return prism(atlas, centre_a, centre_b, radius, start, end, material, axis, 8, face_materials)


def corner_of(axis: str, a: float, b: float, along: float) -> tuple[float, float, float]:
    """Setzt Querschnitts- und Achskoordinate wieder zu einem Punkt zusammen."""
    if axis == "x":
        return along, a, b
    if axis == "y":
        return b, along, a
    return a, b, along


def origin_of(axis: str, centre_a: float, centre_b: float, start: float) -> list[float]:
    return [round(value, 3) for value in corner_of(axis, centre_a, centre_b, start)]


def element_rotation(element: dict) -> list[list[float]] | None:
    """Drehmatrix eines Elements, so wie ``CuboidRotation`` sie berechnet."""
    rotation = element.get("rotation")
    if rotation is None:
        return None
    if "axis" in rotation:
        angle = math.radians(rotation["angle"])
        cos, sin = math.cos(angle), math.sin(angle)
        return {
            "x": [[1, 0, 0], [0, cos, -sin], [0, sin, cos]],
            "y": [[cos, 0, sin], [0, 1, 0], [-sin, 0, cos]],
            "z": [[cos, -sin, 0], [sin, cos, 0], [0, 0, 1]],
        }[rotation["axis"]]
    # Euler-Form: Vanilla setzt sie als rotationZYX zusammen, also erst X, dann Y, dann Z.
    return rotation_matrix((rotation.get("x", 0.0), rotation.get("y", 0.0), rotation.get("z", 0.0)))


def bounds(elements: list[dict]) -> tuple[list[float], list[float]]:
    """Ausdehnung der fertigen Geometrie – Drehungen eingerechnet.

    Die rohen ``from``/``to`` reichen dafür nicht: ein gedrehter Balken belegt einen anderen
    Raum als sein Quader vor der Drehung. Wer das übersieht, bekommt ein n-Eck, das in den
    Achsenrichtungen breiter ist, als seine Kästen es verraten – und damit eine
    Anzeige-Transformation, die das Modell zu groß und aus der Mitte gerückt zeichnet.
    """
    low = [float("inf")] * 3
    high = [float("-inf")] * 3
    for element in elements:
        matrix = element_rotation(element)
        origin = (element.get("rotation") or {}).get("origin", [0.0, 0.0, 0.0])
        for x in (element["from"][0], element["to"][0]):
            for y in (element["from"][1], element["to"][1]):
                for z in (element["from"][2], element["to"][2]):
                    corner = (x, y, z)
                    if matrix is not None:
                        shifted = tuple(corner[axis] - origin[axis] for axis in range(3))
                        corner = tuple(origin[axis] + value for axis, value in enumerate(apply(matrix, shifted)))
                    for axis in range(3):
                        low[axis] = min(low[axis], corner[axis])
                        high[axis] = max(high[axis], corner[axis])
    return low, high


# ---------------------------------------------------------------------------
# Anzeige-Transformationen
# ---------------------------------------------------------------------------


def rotation_matrix(degrees: tuple[float, float, float]) -> list[list[float]]:
    """Entspricht Quaternionf#rotationXYZ – erst um X, dann Y, dann Z."""
    x, y, z = (math.radians(value) for value in degrees)
    rx = [[1, 0, 0], [0, math.cos(x), -math.sin(x)], [0, math.sin(x), math.cos(x)]]
    ry = [[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]]
    rz = [[math.cos(z), -math.sin(z), 0], [math.sin(z), math.cos(z), 0], [0, 0, 1]]

    def multiply(a: list[list[float]], b: list[list[float]]) -> list[list[float]]:
        return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]

    return multiply(multiply(rx, ry), rz)


def apply(matrix: list[list[float]], vector: tuple[float, float, float]) -> tuple[float, float, float]:
    return tuple(sum(matrix[i][j] * vector[j] for j in range(3)) for i in range(3))  # type: ignore[return-value]


def centred_display(
    elements: list[dict],
    rotation: tuple[float, float, float],
    fill: float,
    offset: tuple[float, float, float] = (0.0, 0.0, 0.0),
    flat: bool = False,
) -> dict:
    """Rückt das Modell so, dass es nach der Drehung mittig sitzt und den Rahmen füllt.

    Von Hand wäre das nach jeder Geometrieänderung erneutes Raten; die Formel folgt
    ItemTransform#apply, das erst verschiebt, dann dreht, dann skaliert.

    ``flat`` gilt für das Inventarbild: dort zeichnet Vanilla ohne Perspektive, die Tiefe
    ändert also nichts an der Größe. Überall sonst muss sie mitzählen, sonst ragt ein langes
    Modell aus dem Rahmen heraus, in dem es steckt.
    """
    low, high = bounds(elements)
    matrix = rotation_matrix(rotation)

    corners = []
    for x in (low[0], high[0]):
        for y in (low[1], high[1]):
            for z in (low[2], high[2]):
                corners.append(apply(matrix, (x / 16 - 0.5, y / 16 - 0.5, z / 16 - 0.5)))

    axes = range(2) if flat else range(3)
    extent = max(
        max(corner[axis] for corner in corners) - min(corner[axis] for corner in corners) for axis in axes
    )
    scale = fill / extent

    centre = tuple(
        (max(corner[axis] for corner in corners) + min(corner[axis] for corner in corners)) / 2 for axis in range(3)
    )
    # translation wird von Vanilla mit 1/16 skaliert, also hier mal 16 zurück.
    translation = [round(-centre[axis] * scale * 16 + offset[axis], 3) for axis in range(3)]

    return {
        "rotation": [round(value, 2) for value in rotation],
        "translation": translation,
        "scale": [round(scale, 4)] * 3,
    }


# ---------------------------------------------------------------------------
# Ausgabe
# ---------------------------------------------------------------------------


def model(texture: str, elements: list[dict], display: dict) -> dict:
    return {
        # "side" statt "front": nur so schattiert Vanilla die Flächen unterschiedlich
        # und das Modell wirkt im Inventar räumlich.
        "gui_light": "side",
        "textures": {"atlas": texture, "particle": texture},
        "elements": elements,
        "display": display,
    }


def write(path: pathlib.Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
