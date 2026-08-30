"""Erzeugt Grappling Hook, ausgefahrenen Saughaken und Seil.

    python tools/generate_grappling_hook_3d.py

Ausgabe:
  * textures/item/grappling_hook.png
  * models/item/grappling_hook.json
  * models/item/grappling_hook_head.json
  * models/item/grappling_hook_rope.json
  * items/grappling_hook*.json

Die Waffe ist ein offener Druckluft-Grappler aus blankem Rahmen, Federrohr, großer Seiltrommel
und einem bereits aufgesteckten roten Pömpel. Der fliegende Kopf ist derselbe Pömpel als eigenes
Modell. Er zeigt nach ``-Z``; dieselbe Konvention nutzt die Railgun-Lanze, sodass
``GrapplingHookSystem`` ihn mit einer einzigen Quaternion auf die Flugrichtung legen kann.

Der generierte Seilabschnitt bleibt als Modellressource erhalten. Im Einsatz zeichnet der Client
das Kabel jedoch bildgenau zwischen sichtbarer Waffenmündung und Hakenbuchse; ein bewegtes
Display-Entity könnte der Spielerhand nur mit Servertakt folgen und würde in F5 hinterherhängen.
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
TEXTURE = "oneshotonekill:item/grappling_hook"

CENTRE = 8.0
AXIS_Y = 9.0


def _gunmetal() -> Tile:
    tile = Tile()
    tile.fill_gradient((105, 116, 126), (48, 57, 65), 8)
    tile.bands(range(0, modelkit.TILE, 5), -24)
    return tile


def _steel() -> Tile:
    tile = Tile()
    tile.fill_gradient((222, 228, 230), (125, 137, 146), 8)
    tile.streaks((1, 2, 6, 10, 11), 20)
    return tile


def _metal() -> Tile:
    tile = Tile()
    tile.fill_gradient((194, 204, 210), (104, 118, 128), 8)
    tile.streaks((1, 5, 6, 12), 20)
    return tile


def _dark() -> Tile:
    tile = Tile()
    tile.fill_gradient((45, 51, 58), (17, 21, 26), 5)
    for y in range(1, modelkit.TILE, 3):
        for x in range(modelkit.TILE):
            tile.set(x, y, shift(tile.get(x, y), 17))
    return tile


def _rubber() -> Tile:
    tile = Tile()
    tile.fill_gradient((244, 78, 55), (160, 29, 24), 7)
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            tile.set(x, y, shift(tile.get(x, y), dither(x, y, 9)))
    return tile


def _rubber_dark() -> Tile:
    """Innenseite des Pömpels: tiefes Rot, das die Schüssel optisch aushöhlt."""
    tile = Tile()
    tile.fill_gradient((121, 23, 24), (52, 9, 13), 6)
    centre = (modelkit.TILE - 1) / 2.0
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            distance = ((x - centre) ** 2 + (y - centre) ** 2) ** 0.5
            if distance > 5.6:
                tile.set(x, y, shift(tile.get(x, y), 23))
    return tile


def _rope() -> Tile:
    tile = Tile()
    tile.fill_gradient((66, 72, 76), (25, 29, 32), 4)
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            if (x + y * 2) % 5 in (0, 1):
                tile.set(x, y, shift(tile.get(x, y), 31))
    return tile


def _gauge() -> Tile:
    tile = Tile()
    tile.fill_gradient((218, 244, 240), (112, 166, 166), 5)
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            if x in (2, 7, 12) or y in (2, 12):
                tile.set(x, y, shift(tile.get(x, y), -72))
    for step in range(4, 12):
        tile.set(step, 11 - (step - 4) // 3, (242, 82, 42))
    return tile


ATLAS = Atlas({
    "gunmetal": _gunmetal,
    "steel": _steel,
    "metal": _metal,
    "dark": _dark,
    "rubber": _rubber,
    "rubber_dark": _rubber_dark,
    "rope": _rope,
    "gauge": _gauge,
}, columns=4)


def cup_rim(front_z: float, centre_y: float = CENTRE, radius: float = 4.55,
            segments: int = 14) -> list[dict]:
    """Offener Rand der Pömpelschüssel aus tangentialen Gummisegmenten."""
    elements: list[dict] = []
    half_tangent = 3.141592653589793 * radius / segments * 0.67
    half_radial = 0.62
    for index in range(segments):
        elements.append(cube(
            ATLAS,
            (CENTRE - half_tangent, centre_y + radius - half_radial, front_z),
            (CENTRE + half_tangent, centre_y + radius + half_radial, front_z + 1.15),
            "rubber",
            rotation={
                "origin": [CENTRE, centre_y, front_z + 0.575],
                "axis": "z",
                "angle": round(index * 360.0 / segments, 4),
            },
        ))
    return elements


def plunger(front_z: float, centre_y: float = CENTRE) -> list[dict]:
    """Großer roter Pömpel; ``front_z`` ist die offene Stirnseite."""
    elements: list[dict] = []
    # Zurückgesetzte dunkle Scheibe plus echter offener Rand: von vorn liest sich das als
    # tiefe Gummischüssel statt als flacher roter Zylinder.
    elements += prism(ATLAS, CENTRE, centre_y, 3.62, front_z + 0.34, front_z + 0.52,
                      "rubber_dark", axis="z", sides=14)
    elements += cup_rim(front_z, centre_y)

    # Drei immer schmalere Bänder bilden die gewölbte Rückseite.
    elements += prism(ATLAS, CENTRE, centre_y, 4.05, front_z + 1.00, front_z + 2.00,
                      "rubber", axis="z", sides=14)
    elements += prism(ATLAS, CENTRE, centre_y, 3.35, front_z + 1.90, front_z + 3.05,
                      "rubber", axis="z", sides=12)
    elements += prism(ATLAS, CENTRE, centre_y, 2.35, front_z + 2.95, front_z + 4.05,
                      "rubber", axis="z", sides=10)
    elements += prism(ATLAS, CENTRE, centre_y, 1.12, front_z + 3.90, front_z + 5.80,
                      "dark", axis="z", sides=8)
    elements += prism(ATLAS, CENTRE, centre_y, 1.48, front_z + 5.25, front_z + 6.15,
                      "metal", axis="z", sides=8)
    return elements


def build_gun() -> list[dict]:
    elements: list[dict] = []

    # Offenes Abschussrohr und langes Federpaket – keine geschlossene Sci-Fi-Box mehr.
    elements += prism(ATLAS, CENTRE, AXIS_Y, 1.72, 0.7, 12.9, "dark", axis="z", sides=10)
    elements += prism(ATLAS, CENTRE, AXIS_Y, 1.28, 0.9, 12.7, "steel", axis="z", sides=10)
    for z in (2.0, 3.55, 5.10, 6.65, 8.20, 9.75, 11.30):
        elements += prism(ATLAS, CENTRE, AXIS_Y, 2.05, z, z + 0.42, "metal", axis="z", sides=10)

    # Untere Schiene und zwei schräge Rahmenstreben halten das Rohr sichtbar frei.
    elements.append(cube(ATLAS, (5.1, 6.45, 4.8), (10.9, 7.85, 15.0), "gunmetal",
                         {"up": "steel", "north": "dark"}))
    for side in (-1, 1):
        x1 = CENTRE + min(side * 2.0, side * 3.0)
        x2 = CENTRE + max(side * 2.0, side * 3.0)
        elements.append(cube(ATLAS, (x1, 7.3, 7.4), (x2, 10.6, 8.35), "steel",
                             rotation={"origin": [CENTRE + side * 2.5, 7.5, 8.0],
                                       "axis": "x", "angle": -28.0}))
        elements.append(cube(ATLAS, (x1, 7.0, 12.0), (x2, 10.8, 12.9), "gunmetal",
                             rotation={"origin": [CENTRE + side * 2.5, 7.2, 12.4],
                                       "axis": "x", "angle": 24.0}))

    # Große seitliche Seiltrommel: dunkle Wangen, helle Schrauben und sichtbare Wicklung.
    elements += prism(ATLAS, 10.2, 11.8, 3.75, 10.2, 11.0, "gunmetal", axis="x", sides=12)
    elements += prism(ATLAS, 10.2, 11.8, 3.25, 10.95, 13.75, "rope", axis="x", sides=12)
    elements += prism(ATLAS, 10.2, 11.8, 3.80, 13.7, 14.55, "dark", axis="x", sides=12)
    elements += prism(ATLAS, 10.2, 11.8, 0.72, 9.85, 14.9, "steel", axis="x", sides=10)
    # Sechs Bolzen auf der äußeren Trommelwange.
    for index in range(6):
        angle = index * 60.0
        elements.append(cube(
            ATLAS, (14.52, 9.82, 11.42), (14.78, 10.58, 12.18), "metal",
            rotation={"origin": [14.65, 10.2, 11.8], "axis": "x", "angle": angle},
        ))

    # Pistolengriff und Abzugsbügel.
    grip_rotation = {"origin": [8.0, 7.0, 13.1], "axis": "x", "angle": 12.0}
    elements.append(cube(ATLAS, (6.35, 1.0, 11.4), (9.65, 7.2, 14.9), "dark", rotation=grip_rotation))
    # Griffmulden als helle Querrippen.
    for y in (2.0, 3.2, 4.4, 5.6):
        elements.append(cube(ATLAS, (6.18, y, 12.0), (9.82, y + 0.34, 14.55), "gunmetal",
                             rotation=grip_rotation))
    elements.append(cube(ATLAS, (5.9, 5.8, 8.7), (10.1, 7.2, 12.8), "steel"))
    elements.append(cube(ATLAS, (7.25, 4.65, 9.2), (8.75, 6.5, 10.35), "dark",
                         rotation={"origin": [8.0, 6.5, 9.8], "axis": "x", "angle": -18.0}))

    # Kleines Manometer an der dem Spieler zugewandten Rahmenseite.
    elements += prism(ATLAS, 8.0, 8.5, 1.28, 10.85, 11.45, "dark", axis="x", sides=10)
    elements += prism(ATLAS, 8.0, 8.5, 0.99, 11.43, 11.61, "gauge", axis="x", sides=10)

    # Seilführung von der Trommel zur Rohrmitte.
    elements += prism(ATLAS, CENTRE, AXIS_Y, 0.56, 1.0, 6.5, "rope", axis="z", sides=8)
    elements.append(cube(ATLAS, (6.8, 11.7, 5.3), (9.2, 12.7, 7.7), "gunmetal"))
    return elements


def build_loaded_head() -> list[dict]:
    """Der geladene Pömpel als eigene Ebene, die während des ganzen Schusses ausgeblendet wird."""
    return plunger(-5.15, AXIS_Y)


def build_head() -> list[dict]:
    # Derselbe Pömpel wie an der geladenen Waffe, dahinter nur Kupplung und Seilbuchse.
    elements = plunger(0.0)
    elements += prism(ATLAS, CENTRE, CENTRE, 1.38, 5.75, 10.4, "steel", axis="z", sides=10)
    elements += prism(ATLAS, CENTRE, CENTRE, 1.82, 9.8, 11.7, "dark", axis="z", sides=10)
    elements += prism(ATLAS, CENTRE, CENTRE, 1.40, 11.5, 13.2, "metal", axis="z", sides=8)
    elements += prism(ATLAS, CENTRE, CENTRE, 0.62, 12.9, 16.0, "rope", axis="z", sides=8)
    return elements


def build_rope() -> list[dict]:
    # Exakt ein Block lang; zwei ineinanderliegende Prismen lassen die Litze auch in der Ferne
    # rund und kontrastreich erscheinen.
    elements = prism(ATLAS, CENTRE, CENTRE, 0.62, 0.0, 16.0, "rope", axis="z", sides=8)
    elements += prism(ATLAS, CENTRE, CENTRE, 0.22, 0.01, 15.99, "metal", axis="z", sides=6)
    return elements


def gun_display(elements: list[dict]) -> dict:
    # Deutlich zur Bildschirmmitte: Positive X-Verschiebung schob die rechte Hand bisher noch
    # weiter an den Rand. Null Grad um Y legt die Rohr-/Schussachse in die Blicktiefe; schon die
    # frühere 35-Grad-Schrägstellung ließ die lange Waffe auf dem Bildschirm wieder quer wirken.
    right = centred_display(elements, (3, 0, 3), 0.80, offset=(-4.0, 1.8, 0.0))
    left = centred_display(elements, (3, 0, -3), 0.80, offset=(4.0, 1.8, 0.0))
    return {
        "firstperson_righthand": right,
        "firstperson_lefthand": left,
        "thirdperson_righthand": centred_display(elements, (0, 0, 0), 0.80, offset=(0.0, 2.0, 0.0)),
        "thirdperson_lefthand": centred_display(elements, (0, 0, 0), 0.80, offset=(0.0, 2.0, 0.0)),
        "gui": centred_display(elements, (22, -145, 0), 0.92, flat=True),
        "ground": centred_display(elements, (0, 25, 90), 0.56, offset=(0.0, 3.2, 0.0)),
        "fixed": centred_display(elements, (0, 180, 0), 0.86),
        "head": centred_display(elements, (0, 180, 0), 1.0, offset=(0.0, 12.0, 0.0)),
    }


def carrier_display(elements: list[dict]) -> dict:
    fixed = centred_display(elements, (0, 0, 0), 1.0)
    return {
        "firstperson_righthand": fixed,
        "firstperson_lefthand": fixed,
        "thirdperson_righthand": fixed,
        "thirdperson_lefthand": fixed,
        "gui": centred_display(elements, (20, -35, 0), 0.86, flat=True),
        "ground": fixed,
        "fixed": fixed,
        "head": fixed,
    }


def item_definition(model_name: str) -> dict:
    return {"model": {"type": "minecraft:model", "model": f"oneshotonekill:item/{model_name}"}}


def grappling_hook_definition() -> dict:
    return {
        "model": {
            "type": "oneshotonekill:grappling_hook",
            "frame": "oneshotonekill:item/grappling_hook",
            "loaded_head": "oneshotonekill:item/grappling_hook_loaded_head",
        }
    }


def main() -> int:
    texture_path = TEXTURES_DIR / "grappling_hook.png"
    ATLAS.save(texture_path)
    print(f"Atlas: {texture_path.relative_to(ROOT)} "
          f"({texture_path.stat().st_size} B, {ATLAS.size[0]}x{ATLAS.size[1]}, {len(ATLAS.slots)} Materialien)")

    gun = build_gun()
    loaded_head = build_loaded_head()
    # Beide Renderlagen brauchen exakt dieselbe Transformation. Würde jede Ebene anhand ihrer
    # eigenen Grenzen zentriert, läge der Pömpel trotz korrekter Modellkoordinaten neben dem Rohr.
    shared_gun_display = gun_display(gun + loaded_head)
    payloads = {
        "grappling_hook": (gun, lambda _elements: shared_gun_display),
        "grappling_hook_loaded_head": (loaded_head, lambda _elements: shared_gun_display),
        "grappling_hook_head": (build_head(), carrier_display),
        "grappling_hook_rope": (build_rope(), carrier_display),
    }
    for name, (elements, display_factory) in payloads.items():
        modelkit.write(MODELS_DIR / f"{name}.json",
                       modelkit.model(TEXTURE, elements, display_factory(elements)))
        # Das geladene Vorderteil ist kein registriertes Item, sondern nur die bewegliche
        # Render-Ebene der Waffe. Für die Waffe selbst verbindet das Clientmodell beide Teile.
        if name == "grappling_hook":
            modelkit.write(ITEMS_DIR / f"{name}.json", grappling_hook_definition())
        elif name != "grappling_hook_loaded_head":
            modelkit.write(ITEMS_DIR / f"{name}.json", item_definition(name))
        low, high = modelkit.bounds(elements)
        print(f"{name}: {len(elements)} Elemente, Ausdehnung "
              f"{[round(value, 2) for value in low]} .. {[round(value, 2) for value in high]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
