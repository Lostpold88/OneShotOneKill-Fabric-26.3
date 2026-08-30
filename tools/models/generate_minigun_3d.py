"""Erzeugt Textur, Modelle und Item-Definition der Minigun.

    python tools/generate_minigun_3d.py

Ausgabe:
  * textures/item/minigun.png        – Materialatlas aus acht 16x16-Kacheln
  * models/item/minigun_frame.json   – feststehender Teil (Gehäuse, Trommel, Griffe)
  * models/item/minigun_rotor.json   – drehendes Laufbündel
  * items/minigun.json               – setzt beide Teile zusammen, Rotor über eigenen Modelltyp

Warum zwei Modelle? Ein Item-Modell kann sich nicht selbst drehen. Die Item-Definition
setzt darum über ``minecraft:composite`` zwei Ebenen übereinander: das Gehäuse als
gewöhnliches Modell und das Laufbündel über den mod-eigenen Typ
``oneshotonekill:spinning_rotor``, der ihm zur Laufzeit eine Drehung um die Laufachse
mitgibt (siehe ``client/model/SpinningRotorModel.java``).

Das Skript rechnet am Ende aus, wo die Mündung in der Ersten-Person-Ansicht landet.
Diese Zahlen stehen in ``MinigunRuntime.Muzzle`` unter
``item/runtime/MinigunRuntime.java`` – wer hier die Geometrie oder die
Anzeige-Transformation ändert, muss sie dort nachziehen, sonst
kommen die Schüsse nicht mehr aus dem Lauf.
"""

from __future__ import annotations

import math
import pathlib

import modelkit
from modelkit import Atlas, Tile, apply, centred_display, cube, dither, octagon, rotation_matrix, shift

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/oneshotonekill"
MODELS_DIR = ASSETS / "models/item"
ITEMS_DIR = ASSETS / "items"
TEXTURES_DIR = ASSETS / "textures/item"
TEXTURE = "oneshotonekill:item/minigun"

# ---------------------------------------------------------------------------
# Geometrie – alle Maße in Modelleinheiten (16 = ein Block)
# ---------------------------------------------------------------------------

AXIS_X = 8.0            # Drehachse des Laufbündels
AXIS_Y = 8.0
BARREL_COUNT = 6
BARREL_RING_RADIUS = 2.6
BARREL_HALF_WIDTH = 0.62
BARREL_BACK_Z = 9.0
BARREL_FRONT_Z = 24.0
MUZZLE_FRONT_Z = 25.6   # Vorderkante der Mündungsringe
MUZZLE_HALF_WIDTH = 0.78

# Anzeige-Transformation der Ersten-Person-Ansicht. Aus diesen drei Werten
# ergibt sich die Mündungsposition, die der Server für Pfeile und Funken braucht.
FP_SCALE = 0.34
FP_TRANSLATION = (-4.2, 3.8, 0.0)
FP_ROTATION_Y = 180

# Vanilla setzt die Hand in der Ersten-Person-Ansicht auf diesen Punkt im Kameraraum
# (ItemInHandRenderer#applyItemArmTransform, rechte Hand, fertig ausgerüstet).
HAND_ANCHOR = (0.56, -0.52, -0.72)


# ---------------------------------------------------------------------------
# Materialkacheln
# ---------------------------------------------------------------------------


def _gunmetal() -> Tile:
    """Lackiertes Waffengehäuse: Plattenkanten und ein paar Nieten."""
    tile = Tile()
    tile.fill_gradient((72, 80, 92), (46, 52, 62), 10)
    for y in range(modelkit.TILE):  # senkrechte Plattenfuge
        tile.set(5, y, shift(tile.get(5, y), -22))
        tile.set(6, y, shift(tile.get(6, y), 12))
    for x in range(modelkit.TILE):  # waagerechte Plattenfuge
        tile.set(x, 11, shift(tile.get(x, 11), -20))
    for nx, ny in ((2, 3), (2, 14), (10, 3), (14, 8), (10, 14)):
        tile.set(nx, ny, (120, 130, 145))
        tile.set(nx, ny + 1, (34, 38, 46))
    return tile


def _dark_steel() -> Tile:
    """Motorblock und Streben: dunkel, mit umlaufenden Drehrillen."""
    tile = Tile()
    tile.fill_gradient((52, 56, 66), (26, 28, 36), 8)
    for y in range(0, modelkit.TILE, 3):
        for x in range(modelkit.TILE):
            tile.set(x, y, shift(tile.get(x, y), -14))
            tile.set(x, y + 1, shift(tile.get(x, y + 1), 9))
    return tile


def _steel() -> Tile:
    """Laufstahl: längs gebürstet, damit die Läufe Richtung zeigen."""
    tile = Tile()
    tile.fill_gradient((150, 160, 176), (96, 104, 120), 6)
    tile.streaks((0, 3, 4, 7, 11, 12, 15), 19)
    return tile


def _heated() -> Tile:
    """Angelaufener Stahl an der Mündung – Anlassfarben von Stroh nach Blau."""
    tile = Tile()
    stops = ((196, 172, 118), (188, 122, 54), (146, 78, 60), (78, 84, 128), (54, 60, 92))
    for y in range(modelkit.TILE):
        position = y / (modelkit.TILE - 1) * (len(stops) - 1)
        low = min(len(stops) - 2, int(position))
        row = modelkit.blend(stops[low], stops[low + 1], position - low)
        for x in range(modelkit.TILE):
            tile.set(x, y, shift(row, dither(x, y, 12)))
    return tile


def _brass() -> Tile:
    """Munitionstrommel und Gurtkanal: Messing mit Bandagen."""
    tile = Tile()
    tile.fill_gradient((214, 176, 84), (150, 116, 44), 10)
    for x in (1, 8, 14):
        for y in range(modelkit.TILE):
            tile.set(x, y, shift(tile.get(x, y), -34))
            tile.set(x + 1, y, shift(tile.get(x + 1, y), 22))
    return tile


def _rubber() -> Tile:
    """Griffe: gerändeltes Gummi, diagonales Muster."""
    tile = Tile()
    tile.fill_gradient((44, 44, 48), (22, 22, 26), 5)
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            if (x + y) % 4 == 0:
                tile.set(x, y, shift(tile.get(x, y), 26))
            elif (x - y) % 4 == 0:
                tile.set(x, y, shift(tile.get(x, y), -12))
    return tile


def _vents() -> Tile:
    """Kühlrippen: helle Lamelle über schwarzem Schlitz."""
    tile = Tile()
    for y in range(modelkit.TILE):
        phase = y % 3
        base = (128, 138, 152) if phase == 0 else (74, 80, 92) if phase == 1 else (14, 16, 20)
        for x in range(modelkit.TILE):
            tile.set(x, y, shift(base, dither(x, y, 8)))
    return tile


def _bore() -> Tile:
    """Laufmündung von vorn: schwarzes Loch mit angedeutetem Zug."""
    tile = Tile()
    centre = (modelkit.TILE - 1) / 2.0
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            distance = math.hypot(x - centre, y - centre) / centre
            if distance > 0.92:
                tile.set(x, y, shift((132, 142, 158), dither(x, y, 10)))
            elif distance > 0.72:
                tile.set(x, y, shift((70, 76, 88), dither(x, y, 8)))
            else:
                # Innen fast schwarz, ein Hauch Restlicht oben links.
                glint = 16 if (x - y) == -3 and distance < 0.6 else 0
                tile.set(x, y, shift((10, 11, 14), glint + dither(x, y, 4)))
    return tile


ATLAS = Atlas({
    "gunmetal": _gunmetal,
    "dark_steel": _dark_steel,
    "steel": _steel,
    "heated": _heated,
    "brass": _brass,
    "rubber": _rubber,
    "vents": _vents,
    "bore": _bore,
})


# ---------------------------------------------------------------------------
# Die beiden Modellhälften
# ---------------------------------------------------------------------------


def barrel_positions() -> list[tuple[float, float]]:
    positions = []
    for index in range(BARREL_COUNT):
        angle = index * (2 * math.pi / BARREL_COUNT)
        positions.append(
            (AXIS_X + BARREL_RING_RADIUS * math.cos(angle), AXIS_Y + BARREL_RING_RADIUS * math.sin(angle))
        )
    return positions


def build_frame() -> list[dict]:
    """Alles, was stillsteht: Gehäuse, Motor, Munitionstrommel, Griffe, Tragebügel."""
    elements: list[dict] = []

    # Gehäuse mit dem Rotorantrieb. Die Vorderseite trägt Kühlrippen, weil dort der
    # Rotor austritt und die Kante sonst nackt wirkt.
    elements.append(cube(ATLAS, (4.5, 4.5, -1.0), (11.5, 11.5, 8.0), "gunmetal", {"south": "vents"}))
    elements.append(cube(ATLAS, (5.4, 11.5, 0.4), (10.6, 12.4, 7.6), "vents"))
    elements.append(cube(ATLAS, (4.9, 4.0, 0.4), (11.1, 4.5, 7.6), "dark_steel"))

    # Verschlussdeckel hinten und der Übergang zu den Griffen.
    elements.append(cube(ATLAS, (5.0, 5.0, -3.2), (11.0, 11.0, -1.0), "dark_steel"))
    elements.append(cube(ATLAS, (6.0, 6.0, -4.4), (10.0, 10.0, -3.2), "gunmetal"))

    # Motorwulst links, Gurtzuführung rechts.
    elements.append(cube(ATLAS, (2.9, 5.4, 0.6), (4.5, 10.6, 6.6), "dark_steel"))
    elements.append(cube(ATLAS, (2.6, 10.6, 1.2), (4.5, 11.3, 6.0), "vents"))
    elements.append(cube(ATLAS, (2.4, 7.4, 2.4), (2.9, 8.8, 4.4), "heated"))  # Statusleuchte
    elements.append(cube(ATLAS, (11.5, 5.6, 1.0), (13.1, 9.8, 6.2), "brass"))
    elements.append(cube(ATLAS, (13.1, 6.4, 2.0), (13.6, 9.0, 5.2), "dark_steel"))

    # Munitionstrommel: liegender Achtkant quer zur Laufrichtung.
    elements += octagon(ATLAS, 2.6, 3.7, 2.1, 3.6, 12.4, "brass", axis="x")
    # Deckel links und rechts, damit die Trommel Enden hat.
    elements.append(cube(ATLAS, (3.2, 1.6, 2.7), (3.6, 3.6, 4.7), "dark_steel"))
    elements.append(cube(ATLAS, (12.4, 1.6, 2.7), (12.8, 3.6, 4.7), "dark_steel"))
    # Gurtkanal von der Trommel ins Gehäuse.
    elements.append(cube(ATLAS, (7.0, 4.1, 2.2), (9.0, 5.2, 5.0), "brass"))

    # Spatengriffe hinten: zwei Holme, Querbügel, Abzug.
    elements.append(cube(ATLAS, (4.4, 5.0, -6.0), (5.8, 9.6, -4.6), "rubber"))
    elements.append(cube(ATLAS, (10.2, 5.0, -6.0), (11.6, 9.6, -4.6), "rubber"))
    elements.append(cube(ATLAS, (4.4, 8.6, -6.0), (11.6, 9.6, -4.6), "dark_steel"))
    elements.append(cube(ATLAS, (5.2, 6.6, -4.6), (10.8, 9.6, -4.0), "gunmetal"))
    elements.append(cube(ATLAS, (7.4, 6.2, -5.2), (8.6, 7.2, -4.2), "heated"))  # Abzug

    # Tragebügel über dem Gehäuse.
    elements.append(cube(ATLAS, (7.2, 12.4, 5.4), (8.8, 14.0, 6.6), "dark_steel"))
    elements.append(cube(ATLAS, (7.2, 12.4, 0.6), (8.8, 14.0, 1.8), "dark_steel"))
    elements.append(cube(ATLAS, (6.9, 14.0, 0.3), (9.1, 14.9, 6.9), "rubber"))

    return elements


def build_rotor() -> list[dict]:
    """Alles, was sich dreht: Nabe, Welle, sechs Läufe, Mündungsringe."""
    elements: list[dict] = []

    # Rotorscheibe hinten, aus der die Läufe austreten. Ihr Außenmaß bleibt knapp unter dem
    # Gehäuse, sonst stünden die Ecken beim Drehen seitlich heraus.
    elements += octagon(ATLAS, AXIS_X, AXIS_Y, 3.2, 7.4, BARREL_BACK_Z, "dark_steel")
    # Kleine Nabe auf halber Länge. Eine durchgehende Scheibe wäre einfacher, verschlösse
    # aber die Lücken zwischen den Läufen – von vorn sähe die Waffe dann wie eine Platte aus.
    elements += octagon(ATLAS, AXIS_X, AXIS_Y, 1.5, 15.9, 17.5, "gunmetal")

    # Zentrale Welle – zwischen den Läufen sichtbar.
    elements.append(cube(ATLAS, (AXIS_X - 0.9, AXIS_Y - 0.9, BARREL_BACK_Z),
                         (AXIS_X + 0.9, AXIS_Y + 0.9, BARREL_FRONT_Z), "dark_steel"))

    for centre_x, centre_y in barrel_positions():
        # Der Lauf selbst, achteckig und längs gebürstet.
        elements += octagon(ATLAS, centre_x, centre_y, BARREL_HALF_WIDTH, BARREL_BACK_Z, BARREL_FRONT_Z, "steel")
        # Schelle, die den Lauf an der Nabe hält.
        elements.append(cube(ATLAS, (centre_x - 0.86, centre_y - 0.86, 16.0),
                             (centre_x + 0.86, centre_y + 0.86, 17.4), "gunmetal"))
        # Mündungsring mit der Bohrung auf der Stirnfläche. Ein einzelner Quader genügt:
        # er ist kurz, und seine Kanten lesen sich als Mündungsbremse.
        elements.append(cube(
            ATLAS,
            (centre_x - MUZZLE_HALF_WIDTH, centre_y - MUZZLE_HALF_WIDTH, BARREL_FRONT_Z),
            (centre_x + MUZZLE_HALF_WIDTH, centre_y + MUZZLE_HALF_WIDTH, MUZZLE_FRONT_Z),
            "heated",
            {"south": "bore"},
        ))

    return elements


def display_block(elements: list[dict]) -> dict:
    first_person = {
        "rotation": [0, FP_ROTATION_Y, 0],
        "translation": list(FP_TRANSLATION),
        "scale": [FP_SCALE, FP_SCALE, FP_SCALE],
    }
    third_person = {
        "rotation": [-8, 180, 0],
        "translation": [0.0, 3.4, -1.0],
        "scale": [0.3, 0.3, 0.3],
    }

    return {
        "firstperson_righthand": first_person,
        "firstperson_lefthand": dict(first_person),
        "thirdperson_righthand": third_person,
        "thirdperson_lefthand": dict(third_person),
        "gui": centred_display(elements, (18, -145, 0), 0.92, flat=True),
        "ground": centred_display(elements, (0, 0, 0), 0.55, offset=(0.0, 3.0, 0.0)),
        "fixed": centred_display(elements, (0, 180, 0), 0.95),
        "head": centred_display(elements, (0, 180, 0), 1.1, offset=(0.0, 13.0, 0.0)),
    }


def first_person_muzzle() -> tuple[float, float, float, float]:
    """Mündungsmitte im Kameraraum (rechts, hoch, vorwärts) plus Laufkreisradius.

    Ein Modellpunkt p läuft durch ItemTransform#apply und landet bei
    ``T + R * (S * (p/16 - 0.5))``; davor sitzt die Handverschiebung von Vanilla.
    """
    matrix = rotation_matrix((0.0, float(FP_ROTATION_Y), 0.0))
    local = apply(matrix, (0.0, 0.0, MUZZLE_FRONT_Z / 16 - 0.5))

    right = FP_TRANSLATION[0] * 0.0625 + FP_SCALE * local[0] + HAND_ANCHOR[0]
    up = FP_TRANSLATION[1] * 0.0625 + FP_SCALE * local[1] + HAND_ANCHOR[1]
    forward = -(FP_TRANSLATION[2] * 0.0625 + FP_SCALE * local[2] + HAND_ANCHOR[2])
    radius = BARREL_RING_RADIUS / 16 * FP_SCALE
    return right, up, forward, radius


def main() -> int:
    texture_path = TEXTURES_DIR / "minigun.png"
    ATLAS.save(texture_path)
    print(f"Atlas: {texture_path.relative_to(ROOT)} "
          f"({texture_path.stat().st_size} B, {ATLAS.size[0]}x{ATLAS.size[1]})")

    frame = build_frame()
    rotor = build_rotor()
    display = display_block(frame + rotor)

    modelkit.write(MODELS_DIR / "minigun_frame.json", modelkit.model(TEXTURE, frame, display))
    modelkit.write(MODELS_DIR / "minigun_rotor.json", modelkit.model(TEXTURE, rotor, display))
    modelkit.write(ITEMS_DIR / "minigun.json", {
        "model": {
            "type": "minecraft:composite",
            "models": [
                {"type": "minecraft:model", "model": "oneshotonekill:item/minigun_frame"},
                {
                    "type": "oneshotonekill:spinning_rotor",
                    "model": "oneshotonekill:item/minigun_rotor",
                    "pivot_x": AXIS_X,
                    "pivot_y": AXIS_Y,
                },
            ],
        }
    })

    print(f"Gehäuse: {len(frame)} Elemente -> {(MODELS_DIR / 'minigun_frame.json').relative_to(ROOT)}")
    print(f"Rotor:   {len(rotor)} Elemente -> {(MODELS_DIR / 'minigun_rotor.json').relative_to(ROOT)}")
    print(f"Item:    {(ITEMS_DIR / 'minigun.json').relative_to(ROOT)}")

    right, up, forward, radius = first_person_muzzle()
    print(
        "\nMündung in der Ersten-Person-Ansicht (Kameraraum, Meter) – muss mit den\n"
        "Konstanten in MinigunRuntime.Muzzle unter\n"
        "src/main/java/de/leopold/oneshotonekill/item/runtime/MinigunRuntime.java\n"
        "übereinstimmen, sonst kommen die Schüsse nicht aus dem Lauf:\n"
        f"  FORWARD       = {forward:.4f}\n"
        f"  RIGHT         = {right:.4f}\n"
        f"  DOWN          = {-up:.4f}\n"
        f"  BARREL_RADIUS = {radius:.4f}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
