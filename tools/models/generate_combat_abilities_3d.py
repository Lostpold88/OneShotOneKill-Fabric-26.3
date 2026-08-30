"""Erzeugt die 3D-Modelle für Explosiv-Schuss, Kettenblitz und Reflektor-Schild.

    python tools/generate_combat_abilities_3d.py

Der Sprengkopf dient zugleich als Inventar-Item und sichtbares Projektil. Beim Kettenblitz
erzeugt dieses Skript den langen 3D-Weltblitz und die Definition fuer den prozeduralen
3D-Itemrenderer. Dessen freie Prismen bleiben in GUI, Hotbar und Hand sichtbar, ohne sich auf
gedrehte Blockmodell-Elemente zu verlassen.
``reflector_barrier`` ist dagegen nur der kugelförmige, besitzerexklusive Schild in der Welt.
Alle Modelle teilen sich einen Materialatlas; Blitz, Schildlinse und Barriere sind per
``minecraft:dye`` zur Laufzeit einfärbbar.
"""

from __future__ import annotations

import math
import pathlib

import modelkit
from modelkit import Atlas, Tile, centred_display, cube, dither, prism, shift

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/oneshotonekill"
MODELS = ASSETS / "models/item"
ITEMS = ASSETS / "items"
TEXTURES = ASSETS / "textures/item"
TEXTURE = "oneshotonekill:item/combat_abilities"
CENTRE = 8.0


def material(top: tuple[int, int, int], bottom: tuple[int, int, int], grain: int = 6) -> Tile:
    tile = Tile()
    tile.fill_gradient(top, bottom, grain)
    return tile


def red_tnt() -> Tile:
    tile = material((218, 65, 48), (126, 25, 22), 10)
    for y in range(2, modelkit.TILE, 5):
        tile.bands(range(y, min(y + 1, modelkit.TILE)), 25)
    return tile


def warning() -> Tile:
    tile = Tile()
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            hot = ((x + y) // 3) % 2 == 0
            tile.set(x, y, shift((238, 190, 42) if hot else (30, 28, 23), dither(x, y, 8)))
    return tile


def energy() -> Tile:
    tile = Tile()
    tile.fill_gradient((255, 255, 255), (178, 190, 198), 4)
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            distance = abs(x - 7.5) + abs(y - 7.5)
            tile.set(x, y, shift(tile.get(x, y), round(max(0, 7 - distance) * 5)))
    return tile


ATLAS = Atlas({
    "tnt": red_tnt,
    "warning": warning,
    "dark": lambda: material((52, 57, 64), (20, 23, 28)),
    "metal": lambda: material((190, 199, 211), (84, 91, 104), 8),
    "copper": lambda: material((202, 126, 58), (100, 49, 25), 7),
    "energy": energy,
    "glass": lambda: material((222, 242, 248), (122, 158, 174), 3),
})


def mark(elements: list[dict], materials: tuple[str, ...]) -> list[dict]:
    uvs = [ATLAS.uv(name) for name in materials]
    for element in elements:
        for face in element["faces"].values():
            if face["uv"] in uvs:
                face["tintindex"] = 0
    return elements


def beam(start: tuple[float, float, float], end: tuple[float, float, float], width: float,
         material_name: str) -> dict:
    """Ein Balken zwischen zwei Punkten, als entlang Z gebauter und dann gedrehter Quader."""
    delta = tuple(end[i] - start[i] for i in range(3))
    length = math.sqrt(sum(value * value for value in delta))
    middle = tuple((start[i] + end[i]) * 0.5 for i in range(3))
    horizontal = math.hypot(delta[0], delta[2])
    pitch = -math.degrees(math.atan2(delta[1], horizontal))
    yaw = math.degrees(math.atan2(delta[0], delta[2]))
    return cube(
        ATLAS,
        (middle[0] - width, middle[1] - width, middle[2] - length * 0.5),
        (middle[0] + width, middle[1] + width, middle[2] + length * 0.5),
        material_name,
        rotation={"origin": [round(v, 3) for v in middle], "x": round(pitch, 3), "y": round(yaw, 3), "z": 0.0},
    )


def explosive_round() -> list[dict]:
    elements: list[dict] = []
    # Spitze bei kleinem Z: ItemDisplays drehen das Modell beim Zeichnen um 180 Grad,
    # in der Welt fliegt die Rakete dadurch in +Z-Richtung.
    elements += prism(ATLAS, CENTRE, CENTRE, 0.75, 0.2, 1.0, "metal", sides=12)
    elements += prism(ATLAS, CENTRE, CENTRE, 1.6, 1.0, 2.1, "warning", sides=12)
    elements += prism(ATLAS, CENTRE, CENTRE, 2.7, 2.1, 4.0, "tnt", sides=12)
    elements += prism(ATLAS, CENTRE, CENTRE, 3.0, 4.0, 12.8, "tnt", sides=12)
    elements += prism(ATLAS, CENTRE, CENTRE, 3.15, 6.5, 8.4, "warning", sides=12)
    elements += prism(ATLAS, CENTRE, CENTRE, 2.65, 12.8, 14.8, "dark", sides=12)
    elements += prism(ATLAS, CENTRE, CENTRE, 1.55, 14.8, 16.0, "copper", sides=12)
    # Vier Leitflächen machen aus dem roten Zylinder klar ein Geschoss.
    for angle in (0.0, 90.0, 180.0, 270.0):
        elements.append(cube(ATLAS, (7.55, 7.55, 11.8), (8.45, 12.9, 15.8), "metal",
                             rotation={"origin": [8.0, 8.0, 13.8], "axis": "z", "angle": angle}))
    return elements


def lightning_bolt() -> list[dict]:
    points = [
        (8.0, 8.0, 0.0), (6.4, 9.0, 2.0), (9.6, 7.0, 4.0), (6.8, 6.2, 6.3),
        (9.4, 9.8, 8.4), (7.0, 8.8, 10.6), (9.5, 6.8, 12.8), (8.0, 8.0, 16.0),
    ]
    elements: list[dict] = []
    for start, end in zip(points, points[1:]):
        elements.append(beam(start, end, 0.72, "energy"))
        elements.append(beam(start, end, 0.28, "glass"))
    # Kurze Seitenäste geben dem Geschoss auch seitlich eine Silhouette.
    for index in (2, 4, 5):
        origin = points[index]
        side = -1 if index % 2 else 1
        end = (origin[0] + side * 2.3, origin[1] + 1.1, origin[2] + 1.4)
        elements.append(beam(origin, end, 0.42, "energy"))
    return mark(elements, ("energy", "glass"))


def shield_emitter() -> list[dict]:
    elements: list[dict] = []
    # Ein technischer Buckler: Scheibe in XY, Vorderseite bei kleinem Z.
    elements += prism(ATLAS, CENTRE, CENTRE, 5.2, 6.8, 10.0, "dark", axis="z", sides=12)
    elements += prism(ATLAS, CENTRE, CENTRE, 4.35, 6.35, 10.45, "metal", axis="z", sides=12)
    elements += prism(ATLAS, CENTRE, CENTRE, 3.25, 5.8, 6.4, "energy", axis="z", sides=12)
    elements += prism(ATLAS, CENTRE, CENTRE, 1.15, 5.25, 5.85, "glass", axis="z", sides=12)
    # Vier radiale Halteklammern auf der Linse.
    for angle in (0.0, 90.0, 180.0, 270.0):
        elements.append(cube(ATLAS, (7.45, 8.8, 5.45), (8.55, 12.6, 6.55), "copper",
                             rotation={"origin": [8.0, 8.0, 6.0], "axis": "z", "angle": angle}))
    # Griffbügel auf der Rückseite.
    elements.append(cube(ATLAS, (5.8, 6.9, 10.0), (6.8, 9.1, 13.5), "dark"))
    elements.append(cube(ATLAS, (9.2, 6.9, 10.0), (10.2, 9.1, 13.5), "dark"))
    elements.append(cube(ATLAS, (6.3, 7.2, 12.6), (9.7, 8.8, 14.0), "metal"))
    return mark(elements, ("energy", "glass"))


def ring(axis: str, radius: float, width: float, material_name: str, phase: float = 0.0) -> list[dict]:
    points: list[tuple[float, float, float]] = []
    segments = 20
    for index in range(segments):
        angle = phase + index * math.tau / segments
        a, b = math.cos(angle) * radius, math.sin(angle) * radius
        if axis == "x":
            points.append((CENTRE, CENTRE + a, CENTRE + b))
        elif axis == "y":
            points.append((CENTRE + a, CENTRE, CENTRE + b))
        else:
            points.append((CENTRE + a, CENTRE + b, CENTRE))
    return [beam(points[i], points[(i + 1) % segments], width, material_name) for i in range(segments)]


def reflector_barrier() -> list[dict]:
    """Dichtes sphärisches Energiegitter aus Breitenkreisen und Meridianen."""
    radius = 7.25
    heights = (-radius, -5.4, -3.0, 0.0, 3.0, 5.4, radius)
    circles: list[list[tuple[float, float, float]]] = []
    segments = 16
    elements: list[dict] = []
    for band, height in enumerate(heights):
        across = math.sqrt(max(0.0, radius * radius - height * height))
        points = []
        for index in range(segments):
            angle = index * math.tau / segments + (band % 2) * math.pi / segments
            points.append((CENTRE + math.cos(angle) * across, CENTRE + height,
                           CENTRE + math.sin(angle) * across))
        circles.append(points)
        if across > 0.1:
            width = 0.20 if height == 0.0 else 0.13
            for index in range(segments):
                elements.append(beam(points[index], points[(index + 1) % segments], width, "energy"))

    # Zwölf Meridiane verbinden die versetzten Ringe zu facettierten Schutzfeldern.
    for longitude in range(0, segments, 1):
        for band in range(len(circles) - 1):
            elements.append(beam(circles[band][longitude], circles[band + 1][longitude], 0.105, "glass"))

    # Pole und Äquator-Knoten tragen die stärkeren Feldlinien.
    for point in ((8, 15.25, 8), (8, 0.75, 8), (15.25, 8, 8), (0.75, 8, 8), (8, 8, 15.25), (8, 8, 0.75)):
        elements += prism(ATLAS, point[0], point[1], 0.38, point[2] - 0.38, point[2] + 0.38,
                          "energy", axis="z", sides=8)
    return mark(elements, ("energy", "glass"))


def display(elements: list[dict], projectile: bool = False, bolt_icon: bool = False) -> dict:
    if bolt_icon:
        # Einfache Frontansichten statt automatisch berechneter Schraegansichten: So bleibt der
        # Blitz in der 16px-Hotbar sichtbar und verdeckt in der Hand nicht den halben Bildschirm.
        hand = centred_display(elements, (0, 180, 12), 0.38, offset=(1.0, 1.6, 0.0))
        return {
            "firstperson_righthand": hand,
            "firstperson_lefthand": dict(hand),
            "thirdperson_righthand": centred_display(elements, (0, 180, 12), 0.42, offset=(0, 2.5, 0)),
            "thirdperson_lefthand": centred_display(elements, (0, 180, 12), 0.42, offset=(0, 2.5, 0)),
            "gui": centred_display(elements, (0, 0, 0), 0.78, flat=True),
            "ground": centred_display(elements, (90, 0, 0), 0.58, offset=(0, 3, 0)),
            "fixed": centred_display(elements, (0, 180, 0), 0.94),
        }
    hand = centred_display(elements, (10, -145, 8), 0.54, offset=(1.0, 3.1, 0.0))
    return {
        "firstperson_righthand": hand,
        "firstperson_lefthand": dict(hand),
        "thirdperson_righthand": centred_display(elements, (10, -145, 8), 0.50, offset=(0, 2.5, 0)),
        "thirdperson_lefthand": centred_display(elements, (10, -145, 8), 0.50, offset=(0, 2.5, 0)),
        "gui": centred_display(elements, (25, -145, 12), 0.92, flat=True),
        "ground": centred_display(elements, (90 if projectile else 0, 0, 0), 0.52, offset=(0, 3, 0)),
        "fixed": centred_display(elements, (0, 180, 0), 0.94),
    }


def main() -> int:
    texture = TEXTURES / "combat_abilities.png"
    ATLAS.save(texture)

    payloads = (
        ("explosive_shot", explosive_round(), True, False, None),
        ("chain_lightning_bolt", lightning_bolt(), True, False, 0xFFD83D),
        ("reflector_shield", shield_emitter(), False, False, 0x63E6FF),
        ("reflector_barrier", reflector_barrier(), False, False, 0x62DCFF),
    )
    for name, elements, projectile, bolt_icon, tint in payloads:
        model_display = display(elements, projectile, bolt_icon)
        if name == "explosive_shot":
            # Der Sprengkopf zeigte in der Hand mit dem Heck zur Blickrichtung. Nur Hand- und
            # Drittpersonansicht werden um 180 Grad gewendet; FIXED bleibt für das fliegende
            # Projektil unverändert.
            held = centred_display(elements, (10, 35, 8), 0.54, offset=(1.0, 3.1, 0.0))
            third = centred_display(elements, (10, 35, 8), 0.50, offset=(0.0, 2.5, 0.0))
            model_display["firstperson_righthand"] = held
            model_display["firstperson_lefthand"] = dict(held)
            model_display["thirdperson_righthand"] = third
            model_display["thirdperson_lefthand"] = dict(third)
        modelkit.write(MODELS / f"{name}.json", modelkit.model(TEXTURE, elements, model_display))
        model_payload: dict = {"type": "minecraft:model", "model": f"oneshotonekill:item/{name}"}
        if tint is not None:
            model_payload["tints"] = [{"type": "minecraft:dye", "default": modelkit.opaque(tint)}]
        modelkit.write(ITEMS / f"{name}.json", {"model": model_payload})
        low, high = modelkit.bounds(elements)
        print(f"{name:20} {len(elements):3} Elemente, "
              f"{[round(v, 2) for v in low]} .. {[round(v, 2) for v in high]}")

    # Das benutzbare Item wird von ChainLightningItemRenderer gezeichnet. Das Basismodell
    # liefert ausschließlich die kontextspezifischen Transformationen; seine Geometrie kommt
    # als frei extrudierte Prismen aus Java und nicht aus Cuboid-Elementen.
    chain_display = {
        "firstperson_righthand": {
            "rotation": [0, 180, 14], "translation": [1.0, 3.2, 0.0], "scale": [0.50, 0.50, 0.50],
        },
        "firstperson_lefthand": {
            "rotation": [0, 0, -14], "translation": [-1.0, 3.2, 0.0], "scale": [0.50, 0.50, 0.50],
        },
        "thirdperson_righthand": {
            "rotation": [0, 180, 12], "translation": [0.0, 2.2, 0.0], "scale": [0.46, 0.46, 0.46],
        },
        "thirdperson_lefthand": {
            "rotation": [0, 0, -12], "translation": [0.0, 2.2, 0.0], "scale": [0.46, 0.46, 0.46],
        },
        "gui": {
            "rotation": [16, -28, -8], "translation": [0.0, 0.0, 0.0], "scale": [0.86, 0.86, 0.86],
        },
        "ground": {
            "rotation": [0, 0, 0], "translation": [0.0, 2.4, 0.0], "scale": [0.58, 0.58, 0.58],
        },
        "fixed": {
            "rotation": [0, 180, 0], "translation": [0.0, 0.0, 0.0], "scale": [0.92, 0.92, 0.92],
        },
    }
    modelkit.write(MODELS / "chain_lightning.json", {
        "gui_light": "front",
        "ambientocclusion": False,
        "textures": {"particle": "oneshotonekill:item/chain_lightning"},
        "display": chain_display,
    })
    modelkit.write(ITEMS / "chain_lightning.json", {
        "model": {
            "type": "minecraft:special",
            "base": "oneshotonekill:item/chain_lightning",
            "model": {"type": "oneshotonekill:chain_lightning"},
        },
    })
    print("chain_lightning       prozedurales 3D-Spezialmodell")
    print(f"Atlas: {texture.relative_to(ROOT)} ({ATLAS.size[0]}x{ATLAS.size[1]})")
    low, high = modelkit.bounds(payloads[1][1])
    print(f"BOLT_LENGTH_UNIT = {(high[2] - low[2]) / 16.0:.4f}  "
          "(muss mit ArmedShots.java übereinstimmen)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
