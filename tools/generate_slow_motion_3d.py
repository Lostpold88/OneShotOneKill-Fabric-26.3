"""Erzeugt das 3D-Modell des Zeitverzerrers.

    python tools/generate_slow_motion_3d.py

Das Gerät ist eine goldene Taschenuhr: rundes Metallgehäuse, großes helles Zifferblatt mit
zwölf Stundenmarken und drei Zeigern, zwei Chronographen, Aufzugskrone und ein echter
Uhrenbügel. Das vollständige Zifferblatt sitzt auf beiden Seiten, damit GUI, Handansicht und
Item-Box niemals wieder nur eine technische Rückseite zeigen.

Die beweglichen Teile werden als einzelne Teilmodelle ausgegeben. Das Clientmodell kann dadurch
beide Feldringe gegenläufig drehen, die acht Kondensatoren als Kaskade färben und Minuten- sowie
Sekundenzeiger unabhängig bewegen, ohne dafür den ItemStack auf dem Server zu verändern.
"""

from __future__ import annotations

import copy
import pathlib

import modelkit
from modelkit import Atlas, Tile, centred_display, cube, prism, shift

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/oneshotonekill"
TEXTURE_PATH = ASSETS / "textures/item/slow_motion_3d.png"
MODEL_DIR = ASSETS / "models/item"
ITEM_PATH = ASSETS / "items/slow_motion.json"
TEXTURE = "oneshotonekill:item/slow_motion_3d"
CENTRE = 8.0

BASE_MODEL = "slow_motion_base"
INNER_RING_MODELS = [f"slow_motion_inner_ring_{index}" for index in range(12)]
OUTER_RING_MODELS = [f"slow_motion_outer_ring_{index}" for index in range(12)]
MINUTE_HAND_MODEL = "slow_motion_minute_hand"
SECOND_HAND_MODEL = "slow_motion_second_hand"
CORE_MODEL = "slow_motion_core"
LENS_MODELS = [f"slow_motion_lens_{index}" for index in range(8)]


def gradient(top: tuple[int, int, int], bottom: tuple[int, int, int], grain: int = 5) -> Tile:
    tile = Tile()
    tile.fill_gradient(top, bottom, grain)
    tile.streaks((2, 7, 12), 8)
    return tile


def chassis() -> Tile:
    tile = gradient((255, 221, 122), (112, 60, 13), 6)
    tile.bands(range(2, modelkit.TILE, 4), 14)
    return tile


def silver() -> Tile:
    return gradient((224, 238, 248), (70, 84, 105), 4)


def face() -> Tile:
    tile = gradient((255, 252, 226), (202, 190, 154), 3)
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            if (x + y) % 6 == 0:
                tile.set(x, y, shift(tile.get(x, y), 5))
    return tile


def glass() -> Tile:
    tile = gradient((235, 255, 255), (132, 205, 220), 2)
    for index in range(modelkit.TILE):
        tile.set(index, max(0, 5 - index // 3), (220, 255, 255))
    return tile


def energy() -> Tile:
    tile = gradient((255, 255, 255), (202, 218, 232), 2)
    for y in range(4, 12):
        for x in range(4, 12):
            if (x + y) % 3:
                tile.set(x, y, (255, 255, 255))
    return tile


def violet() -> Tile:
    return gradient((211, 121, 255), (67, 19, 125), 5)


def brass() -> Tile:
    tile = gradient((255, 215, 105), (106, 62, 18), 6)
    tile.bands(range(1, modelkit.TILE, 4), 12)
    return tile


def ink() -> Tile:
    return gradient((45, 53, 68), (5, 7, 12), 3)


ATLAS = Atlas({
    "chassis": chassis,
    "silver": silver,
    "face": face,
    "glass": glass,
    "energy": energy,
    "violet": violet,
    "brass": brass,
    "ink": ink,
}, columns=4)


def ring_segments(radius: float, z_low: float, z_high: float, material: str,
                  count: int = 16, phase: float = 0.0) -> list[dict]:
    """Tangentiale Segmente ergeben einen freistehenden Ring um das Uhrwerk."""
    elements: list[dict] = []
    for index in range(count):
        angle = phase + index * 360.0 / count
        elements.append(cube(
            ATLAS,
            (CENTRE - 0.72, CENTRE + radius, z_low),
            (CENTRE + 0.72, CENTRE + radius + 0.52, z_high),
            material,
            rotation={"origin": [CENTRE, CENTRE, CENTRE], "axis": "z", "angle": angle},
        ))
    return elements


def chronograph_face() -> list[dict]:
    """Statischer Teil des Uhrengesichts mit zwei versenkten Hilfszifferblättern."""
    elements: list[dict] = []

    # Zwei versenkte Chronographen machen das Zifferblatt sofort als Uhr lesbar.
    for cx, cy, material in ((5.75, 7.05, "brass"), (10.25, 7.05, "silver")):
        elements += prism(ATLAS, cx, cy, 1.16, 3.72, 4.08, material, axis="z", sides=12)
        elements += prism(ATLAS, cx, cy, 0.82, 3.58, 3.76, "face", axis="z", sides=12)
        elements += prism(ATLAS, cx, cy, 0.22, 3.34, 3.72, "brass", axis="z", sides=8)

    # Stundenzeiger und Messingnabe bleiben fest; die beiden anderen Zeiger liegen in eigenen
    # Teilmodellen und werden zur Laufzeit bewegt.
    elements.append(cube(ATLAS, (7.66, 7.75, 3.46), (8.34, 10.55, 3.78), "brass",
                         rotation={"origin": [CENTRE, CENTRE, 3.62], "axis": "z", "angle": -47.0}))
    elements += prism(ATLAS, CENTRE, CENTRE, 0.62, 3.02, 3.72, "brass", axis="z", sides=10)
    return elements


def minute_hand() -> list[dict]:
    """Dunkler Minutenzeiger, der zur Laufzeit in deutlichen Schritten springt."""
    return [cube(ATLAS, (7.77, 7.6, 3.30), (8.23, 11.25, 3.54), "ink",
                 rotation={"origin": [CENTRE, CENTRE, 3.42], "axis": "z", "angle": 24.0})]


def second_hand() -> list[dict]:
    """Weißer Sekundenzeiger; das Clientmodell zeichnet zusätzlich zwei Zeit-Echos davon."""
    return [cube(ATLAS, (7.89, 5.0, 3.14), (8.11, 11.8, 3.34), "energy",
                 rotation={"origin": [CENTRE, CENTRE, 3.24], "axis": "z", "angle": 108.0})]


def temporal_core() -> list[dict]:
    """Die beiden Chronographenzeiger und die zentrale, pulsierende Energienabe."""
    elements: list[dict] = []
    for cx, cy in ((5.75, 7.05), (10.25, 7.05)):
        elements.append(cube(ATLAS, (cx - 0.13, cy - 0.1, 3.42),
                             (cx + 0.13, cy + 0.72, 3.68), "energy",
                             rotation={"origin": [cx, cy, 3.55], "axis": "z",
                                       "angle": -42.0 if cx < CENTRE else 58.0}))
    elements += prism(ATLAS, CENTRE, CENTRE, 0.28, 2.88, 3.04, "energy", axis="z", sides=8)
    return elements


def watch_bow() -> list[dict]:
    """Massiver Bügel oberhalb der Krone, wie bei einer echten Taschenuhr."""
    elements: list[dict] = []
    bow_centre_y = 15.7
    radius = 1.42
    for index in range(14):
        angle = index * 360.0 / 14
        elements.append(cube(
            ATLAS,
            (CENTRE - 0.42, bow_centre_y + radius, 6.85),
            (CENTRE + 0.42, bow_centre_y + radius + 0.44, 9.15),
            "silver",
            rotation={"origin": [CENTRE, bow_centre_y, CENTRE], "axis": "z", "angle": angle},
        ))
    return elements


def mirror_z(elements: list[dict]) -> list[dict]:
    """Spiegelt ein vollständiges Zifferblatt auf die zweite Seite der Taschenuhr."""
    mirrored = copy.deepcopy(elements)
    for element in mirrored:
        old_from = element["from"][2]
        old_to = element["to"][2]
        element["from"][2] = round(16.0 - old_to, 3)
        element["to"][2] = round(16.0 - old_from, 3)
        rotation = element.get("rotation")
        if rotation is not None:
            rotation["origin"][2] = round(16.0 - rotation["origin"][2], 3)
    return mirrored


def face_details() -> list[dict]:
    """Zwölf dunkle Indizes plus das vollständige Chronographen-Zeigerwerk."""
    details: list[dict] = []
    for index in range(12):
        details.append(cube(
            ATLAS,
            (7.64 if index % 3 == 0 else 7.79, 11.2, 3.62),
            (8.36 if index % 3 == 0 else 8.21, 12.7, 4.02),
            "brass" if index % 3 == 0 else "ink",
            rotation={"origin": [CENTRE, CENTRE, 4.2], "axis": "z", "angle": index * 30.0},
        ))
    details += chronograph_face()
    return details


def temporal_modules() -> list[dict]:
    """Statische Fassungen und violette Spulen der acht Zeitfeld-Kondensatoren."""
    modules: list[dict] = []
    for index in range(8):
        angle = 22.5 + index * 45.0
        rotation = {"origin": [CENTRE, CENTRE, 4.2], "axis": "z", "angle": angle}
        # Dunkle Fassung, violette Spule und eine hervorstehende, getönte Linse.
        modules.append(cube(ATLAS, (7.38, 12.55, 3.72), (8.62, 13.82, 5.02),
                            "ink", rotation=rotation))
        modules.append(cube(ATLAS, (7.55, 12.72, 3.42), (8.45, 13.6, 3.78),
                            "violet", rotation=rotation))
    return modules


def temporal_lenses() -> list[list[dict]]:
    """Acht getrennte Energielinsen, damit die Laufzeitfarbe um die Uhr wandern kann."""
    lenses: list[list[dict]] = []
    for index in range(8):
        angle = 22.5 + index * 45.0
        rotation = {"origin": [CENTRE, CENTRE, 4.2], "axis": "z", "angle": angle}
        lenses.append([cube(ATLAS, (7.7, 12.87, 3.12), (8.3, 13.45, 3.45),
                            "energy", rotation=rotation)])
    return lenses


def build() -> dict[str, list[dict]]:
    base: list[dict] = []

    # Dicker goldener Taschenuhrenkörper. Vorder- und Rückseite tragen dasselbe Zifferblatt.
    base += prism(ATLAS, CENTRE, CENTRE, 5.05, 5.1, 10.9, "chassis", axis="z", sides=16)
    front: list[dict] = []
    front += prism(ATLAS, CENTRE, CENTRE, 5.28, 4.72, 5.42, "brass", axis="z", sides=16)
    front += prism(ATLAS, CENTRE, CENTRE, 4.72, 4.34, 4.78, "face", axis="z", sides=16)
    front += prism(ATLAS, CENTRE, CENTRE, 4.28, 4.08, 4.38, "glass", axis="z", sides=16)
    front += face_details()
    front += temporal_modules()
    base += front
    base += mirror_z(front)

    # Münzrand-Riffelung statt des alten schwebenden Sci-Fi-Rings.
    base += ring_segments(5.12, 7.15, 8.85, "brass", 24, 7.5)

    # Aufzugskrone und Taschenuhrenbügel ersetzen die früheren seitlichen Geräte-Griffe.
    base += prism(ATLAS, CENTRE, CENTRE, 1.15, 12.65, 14.45, "brass", axis="y", sides=10)
    base += prism(ATLAS, CENTRE, CENTRE, 1.48, 14.35, 15.05, "silver", axis="y", sides=12)
    base += watch_bow()

    def double_sided(front_part: list[dict]) -> list[dict]:
        return front_part + mirror_z(front_part)

    lens_parts = [double_sided(lens) for lens in temporal_lenses()]
    parts = {
        "base": base,
        "minute_hand": double_sided(minute_hand()),
        "second_hand": double_sided(second_hand()),
        "core": double_sided(temporal_core()),
    }
    for index, segment in enumerate(ring_segments(4.62, 3.5, 3.78, "energy", 12, 15.0)):
        parts[f"inner_ring_{index}"] = double_sided([segment])
    for index, segment in enumerate(ring_segments(4.92, 3.12, 3.42, "energy", 12, 0.0)):
        parts[f"outer_ring_{index}"] = double_sided([segment])
    for index, lenses in enumerate(lens_parts):
        parts[f"lens_{index}"] = lenses
    return parts


def mark_energy_tint(elements: list[dict]) -> None:
    wanted = tuple(ATLAS.uv("energy"))
    for element in elements:
        is_energy = False
        for face_data in element["faces"].values():
            if tuple(face_data["uv"]) == wanted:
                face_data["tintindex"] = 0
                is_energy = True
        if is_energy:
            # Volle Modell-Lichtemission: Der helle Blinkzustand bleibt selbst nachts und
            # im Schatten strahlend weiß. Im dunklen Zustand ist die Tintfarbe fast schwarz,
            # sodass trotz Emission ein harter, gut sichtbarer Blinkkontrast entsteht.
            element["light_emission"] = 15
            element["shade"] = False


def displays(elements: list[dict]) -> dict:
    # In Ego-Ansicht kleiner und deutlich höher: Krone, Bügel und unterer Gehäuserand bleiben
    # vollständig im Bild, statt wie zuvor unten aus dem Sichtfeld zu laufen.
    hand = centred_display(elements, (8, 145, 2), 0.43, offset=(1.1, 5.0, 0.0))
    third = centred_display(elements, (8, 145, 2), 0.51, offset=(0.0, 2.4, 0.0))
    return {
        "firstperson_righthand": hand,
        "firstperson_lefthand": dict(hand),
        "thirdperson_righthand": third,
        "thirdperson_lefthand": dict(third),
        "gui": centred_display(elements, (12, 145, 0), 0.93, flat=True),
        "ground": centred_display(elements, (18, 0, 0), 0.56, offset=(0.0, 3.0, 0.0)),
        "fixed": centred_display(elements, (0, 180, 0), 0.92),
    }


def main() -> int:
    parts = build()
    dynamic_names = {"second_hand", "core"}
    dynamic_names.update(f"inner_ring_{index}" for index in range(12))
    dynamic_names.update(f"outer_ring_{index}" for index in range(12))
    dynamic_names.update(f"lens_{index}" for index in range(8))
    for name in dynamic_names:
        mark_energy_tint(parts[name])

    all_elements = [element for elements in parts.values() for element in elements]
    display = displays(all_elements)
    ATLAS.save(TEXTURE_PATH)
    # Vor der Segment-Kaskade waren beide Ringe je ein starres Teilmodell. Die Dateien würden
    # zwar nicht mehr referenziert, sollen aber auch nicht als tote Assets im Mod-JAR bleiben.
    for obsolete in ("slow_motion_inner_ring.json", "slow_motion_outer_ring.json"):
        (MODEL_DIR / obsolete).unlink(missing_ok=True)
    modelkit.write(MODEL_DIR / f"{BASE_MODEL}.json", modelkit.model(TEXTURE, parts["base"], display))
    for index, model_name in enumerate(INNER_RING_MODELS):
        modelkit.write(MODEL_DIR / f"{model_name}.json", modelkit.model(TEXTURE, parts[f"inner_ring_{index}"], display))
    for index, model_name in enumerate(OUTER_RING_MODELS):
        modelkit.write(MODEL_DIR / f"{model_name}.json", modelkit.model(TEXTURE, parts[f"outer_ring_{index}"], display))
    modelkit.write(MODEL_DIR / f"{MINUTE_HAND_MODEL}.json", modelkit.model(TEXTURE, parts["minute_hand"], display))
    modelkit.write(MODEL_DIR / f"{SECOND_HAND_MODEL}.json", modelkit.model(TEXTURE, parts["second_hand"], display))
    modelkit.write(MODEL_DIR / f"{CORE_MODEL}.json", modelkit.model(TEXTURE, parts["core"], display))
    for index, model_name in enumerate(LENS_MODELS):
        modelkit.write(MODEL_DIR / f"{model_name}.json", modelkit.model(TEXTURE, parts[f"lens_{index}"], display))
    modelkit.write(ITEM_PATH, {
        "model": {
            "type": "oneshotonekill:chrono_distorter",
            "base": f"oneshotonekill:item/{BASE_MODEL}",
            "inner_ring": [f"oneshotonekill:item/{name}" for name in INNER_RING_MODELS],
            "outer_ring": [f"oneshotonekill:item/{name}" for name in OUTER_RING_MODELS],
            "minute_hand": f"oneshotonekill:item/{MINUTE_HAND_MODEL}",
            "second_hand": f"oneshotonekill:item/{SECOND_HAND_MODEL}",
            "core": f"oneshotonekill:item/{CORE_MODEL}",
            "lenses": [f"oneshotonekill:item/{name}" for name in LENS_MODELS],
        }
    })
    low, high = modelkit.bounds(all_elements)
    tinted = sum(1 for element in all_elements for face_data in element["faces"].values()
                 if "tintindex" in face_data)
    print(f"slow_motion: {len(all_elements)} Elemente in {len(parts)} Teilmodellen, "
          f"{tinted} getönte Flächen, "
          f"{[round(value, 2) for value in low]} .. {[round(value, 2) for value in high]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
