"""Erzeugt Textur und Modelle der Railgun und ihres Geschosses.

    python tools/generate_railgun_3d.py

Ausgabe:
  * textures/item/railgun.png       – gemeinsamer Materialatlas
  * models/item/railgun.json        – die Waffe
  * models/item/railgun_bolt.json   – die Lanze, die der Schuss in die Luft zieht
  * items/railgun.json, items/railgun_bolt.json

**Die Waffe hat zwei Farbebenen, das Geschoß eine.** Eine Item-Definition darf mehrere
``tints`` aufführen, und jede Fläche wählt über ihren ``tintindex`` eine davon:

* Ebene 0 (``minecraft:dye``) färbt die Kondensatorspulen. Sie glimmen im Ruhezustand und
  leuchten beim Laden immer greller.
* Ebene 1 (``minecraft:custom_model_data``) färbt die Anzeigen – Bildschirm und Melder. Sie
  blinken unabhängig davon ihr eigenes Muster.

Schiene, Gehäuse und Griff tragen gar keinen Tintindex und behalten ihre Materialfarbe. Beim
Geschoß trägt alles Ebene 0 – die ganze Lanze verblasst nach dem Schuss.

**Die Lanze zeigt nach -Z und ist genau 16 Einheiten lang.** In der Welt zeigt sie damit nach
+Z ({@code DisplayRenderer.ItemDisplayRenderer#submitInner} legt eine halbe Umdrehung auf den
Stapel), und weil eine ``Transformation`` erst skaliert und dann dreht, wird aus einem einzigen
Display der ganze Strahl: Skalierung in Z ist unmittelbar seine Länge in Blöcken.

**Die Mündung.** Das Skript gibt aus, wo die Schiene in der Ersten-Person-Ansicht endet –
dieselbe Rechnung wie bei der Minigun, siehe ``MinigunRuntime.Muzzle`` in
``item/runtime/MinigunRuntime.java``.
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
TEXTURE = "oneshotonekill:item/railgun"

CENTRE = 8.0
# Die Waffe liegt entlang Z: Mündung bei kleinem z, Schaft bei großem.
MUZZLE_Z = 0.4
RECEIVER_Z = 14.6
AXIS_Y = 8.6

# Vanilla setzt die Hand in der Ersten-Person-Ansicht auf diesen Punkt im Kameraraum
# (ItemInHandRenderer#applyItemArmTransform, rechte Hand, fertig ausgerüstet).
HAND_ANCHOR = (0.56, -0.52, -0.72)


# ---------------------------------------------------------------------------
# Materialien
# ---------------------------------------------------------------------------


def _shell() -> Tile:
    """Gehäuse: kaltes Blaugrau mit Plattenfugen."""
    tile = Tile()
    tile.fill_gradient((104, 112, 126), (62, 68, 80), 8)
    tile.bands(range(0, modelkit.TILE, 5), -22)
    return tile


def _rail() -> Tile:
    """Schiene: blanker Leiter, längs gebürstet."""
    tile = Tile()
    tile.fill_gradient((186, 192, 204), (118, 124, 138), 7)
    tile.streaks((1, 2, 6, 7, 12, 13), 22)
    return tile


def _dark() -> Tile:
    """Kanten, Griff und Schaft: fast schwarz."""
    tile = Tile()
    tile.fill_gradient((48, 51, 58), (24, 26, 31), 5)
    for y in range(2, modelkit.TILE, 4):
        for x in range(modelkit.TILE):
            tile.set(x, y, shift(tile.get(x, y), 20))
    return tile


def _coil() -> Tile:
    """Kondensatorspulen: fast weiß, damit die Einfärbung den ganzen Farbraum behält.

    Die Rillen bleiben trotzdem sichtbar – sie sind als Helligkeitsunterschied gemalt und
    überstehen jede Multiplikation mit einer Farbe.
    """
    tile = Tile()
    tile.fill_gradient((248, 248, 244), (190, 190, 186), 6)
    for y in range(0, modelkit.TILE, 3):
        for x in range(modelkit.TILE):
            tile.set(x, y, shift(tile.get(x, y), -44))
    return tile


def _vent() -> Tile:
    """Kühlschlitze über der Kammer."""
    tile = Tile()
    tile.fill_gradient((74, 80, 92), (36, 40, 48), 5)
    for y in range(1, modelkit.TILE, 3):
        for x in range(2, modelkit.TILE - 2):
            tile.set(x, y, (14, 15, 18))
    return tile


def _scope() -> Tile:
    """Zieloptik: dunkles Glas mit einem einzigen Lichtstreifen."""
    tile = Tile()
    tile.fill_gradient((28, 40, 58), (12, 17, 26), 5)
    for x in range(modelkit.TILE):
        y = 4 + (x // 6)
        tile.set(x, y, (132, 172, 214))
        tile.set(x, y + 1, (68, 92, 126))
    return tile


def _grip() -> Tile:
    """Griffschalen: geriffelter Kunststoff."""
    tile = Tile()
    tile.fill_gradient((58, 56, 52), (32, 31, 29), 4)
    for y in range(modelkit.TILE):
        for x in range(0, modelkit.TILE, 3):
            tile.set(x, y, shift(tile.get(x, y), 26))
    return tile


def _warn() -> Tile:
    """Warnband an der Kammer."""
    tile = Tile()
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            hot = ((x + y) // 4) % 2 == 0
            tile.set(x, y, shift((206, 168, 44) if hot else (26, 25, 21), dither(x, y, 8)))
    return tile


def _screen() -> Tile:
    """Anzeigefläche: fast weiß mit dunklen Rasterzeilen.

    Weiß, weil die Einfärbung als Faktor wirkt – nur so kann die Anzeige zwischen mattem
    Standby und grellem Alarm den ganzen Weg gehen. Die Zeilen bleiben als
    Helligkeitsunterschied stehen und überleben jede Multiplikation.
    """
    tile = Tile()
    tile.fill_gradient((246, 250, 250), (206, 216, 218), 4)
    for y in range(1, modelkit.TILE, 2):
        for x in range(modelkit.TILE):
            tile.set(x, y, shift(tile.get(x, y), -58))
    # Ein paar kürzere Zeilen, damit es nach Text aussieht und nicht nach Streifenmuster.
    for y, length in ((3, 9), (7, 13), (11, 6)):
        for x in range(length):
            tile.set(x + 1, y, shift(tile.get(x + 1, y), 44))
    return tile


def _plasma() -> Tile:
    """Der Strahl selbst: fast weiß mit einem helleren Kern in der Mitte."""
    tile = Tile()
    tile.fill_gradient((208, 208, 206), (208, 208, 206), 0)
    centre = (modelkit.TILE - 1) / 2.0
    for y in range(modelkit.TILE):
        for x in range(modelkit.TILE):
            # Nach innen heller: der Strahl soll einen Kern haben, keine gleichmäßige Röhre.
            glow = 1.0 - min(1.0, abs(y - centre) / centre)
            tile.set(x, y, shift((208, 208, 206), round(47 * glow * glow)))
    for x in range(0, modelkit.TILE, 5):
        tile.set(x, int(centre), (255, 255, 255))
    return tile


ATLAS = Atlas({
    "shell": _shell,
    "rail": _rail,
    "dark": _dark,
    "coil": _coil,
    "vent": _vent,
    "scope": _scope,
    "grip": _grip,
    "warn": _warn,
    "screen": _screen,
    "plasma": _plasma,
})


# ---------------------------------------------------------------------------
# Die Waffe
# ---------------------------------------------------------------------------

COIL_COUNT = 4


def build_railgun() -> list[dict]:
    """Zwei Schienen, vier Spulen dazwischen, Kammer und Schaft dahinter."""
    elements: list[dict] = []

    # Die beiden Leiterschienen. Zwischen ihnen läuft der Schuss.
    for side in (-1, 1):
        left = CENTRE + min(side * 1.05, side * 2.15)
        right = CENTRE + max(side * 1.05, side * 2.15)
        elements.append(cube(ATLAS, (left, AXIS_Y - 0.7, MUZZLE_Z), (right, AXIS_Y + 0.7, 11.0),
                             "rail", {"north": "dark"}))

    # Spulen um die Schienen – acht Ecken, damit sie rund wirken. Sie müssen deutlich weiter
    # reichen als die Schienen, sonst verschluckt der Umriss beides zu einem Klumpen.
    for index in range(COIL_COUNT):
        start = MUZZLE_Z + 1.4 + index * 2.6
        elements += prism(ATLAS, CENTRE, AXIS_Y, 3.2, start, start + 0.9, "coil", axis="z", sides=8)
    # Mündungsring, damit vorn eine Kante steht.
    elements += prism(ATLAS, CENTRE, AXIS_Y, 3.4, MUZZLE_Z + 0.5, MUZZLE_Z + 1.3, "dark", axis="z", sides=8)

    # Kammer: der dicke Teil hinter den Schienen.
    elements.append(cube(ATLAS, (CENTRE - 2.3, AXIS_Y - 2.0, 10.0), (CENTRE + 2.3, AXIS_Y + 2.0, RECEIVER_Z),
                         "shell", {"up": "vent", "north": "warn"}))
    # Energiezelle obenauf, ebenfalls einfärbbar.
    elements += prism(ATLAS, CENTRE, AXIS_Y + 2.9, 1.2, 10.6, 13.8, "coil", axis="z", sides=8)

    # Zieloptik über der Kammer.
    elements.append(cube(ATLAS, (CENTRE - 0.9, AXIS_Y + 2.0, 11.2), (CENTRE + 0.9, AXIS_Y + 2.6, 13.4), "dark"))
    elements += prism(ATLAS, CENTRE, AXIS_Y + 3.4, 1.0, 9.2, 13.0, "dark", axis="z", sides=8,
                      face_materials={"north": "scope", "south": "scope"})

    # Griff und Schaft.
    elements.append(cube(ATLAS, (CENTRE - 1.0, AXIS_Y - 5.4, 12.0), (CENTRE + 1.0, AXIS_Y - 1.6, 14.2),
                         "grip",
                         rotation={"origin": [CENTRE, AXIS_Y - 1.6, 13.1], "axis": "x", "angle": 14.0}))
    elements.append(cube(ATLAS, (CENTRE - 1.6, AXIS_Y - 1.2, RECEIVER_Z), (CENTRE + 1.6, AXIS_Y + 1.8, 17.6), "dark"))
    # Stützgriff vorn.
    elements.append(cube(ATLAS, (CENTRE - 0.8, AXIS_Y - 4.2, 5.0), (CENTRE + 0.8, AXIS_Y - 1.0, 6.6),
                         "grip",
                         rotation={"origin": [CENTRE, AXIS_Y - 1.0, 5.8], "axis": "x", "angle": -18.0}))

    # Anzeigen an beiden Flanken der Kammer. Beide Seiten, weil man die Waffe in der
    # Ersten-Person-Ansicht von links sieht, Zuschauer sie aber von rechts sehen.
    for side in (-1, 1):
        left = CENTRE + min(side * 2.3, side * 2.62)
        right = CENTRE + max(side * 2.3, side * 2.62)
        elements.append(cube(ATLAS, (left, AXIS_Y - 1.3, 10.7), (right, AXIS_Y + 1.1, 13.7), "screen"))

    # Drei Melder in einer Reihe auf dem Kammerdeckel.
    for index in range(3):
        x = CENTRE - 1.6 + index * 1.6
        elements.append(cube(ATLAS, (x, AXIS_Y + 2.0, 10.2), (x + 0.9, AXIS_Y + 2.24, 10.9), "screen"))

    # Leuchtstreifen an der Oberkante der Schienen.
    for side in (-1, 1):
        left = CENTRE + min(side * 1.1, side * 2.1)
        right = CENTRE + max(side * 1.1, side * 2.1)
        elements.append(cube(ATLAS, (left, AXIS_Y + 0.7, 6.4), (right, AXIS_Y + 0.86, 10.6), "screen"))

    # Mündungsblende zwischen den Schienen.
    elements.append(cube(ATLAS, (CENTRE - 2.4, AXIS_Y - 1.1, MUZZLE_Z), (CENTRE + 2.4, AXIS_Y + 1.1, MUZZLE_Z + 0.5),
                         "dark", {"north": "coil"}))

    return elements


# ---------------------------------------------------------------------------
# Die Lanze
# ---------------------------------------------------------------------------

BOLT_SEGMENTS = ((0.0, 0.55), (2.0, 0.9), (5.0, 1.0), (12.0, 0.85), (15.4, 0.35), (16.0, 0.0))


def build_bolt() -> list[dict]:
    """Ein schlanker Leuchtkörper, vorn spitz und hinten auslaufend.

    Er misst genau 16 Einheiten in Z. Dadurch ist der Skalierungswert in Z unmittelbar die
    Länge des Strahls in Blöcken – ein Umrechnungsfaktor, den irgendwann jemand vergisst,
    entsteht so gar nicht erst.
    """
    elements: list[dict] = []
    for index in range(len(BOLT_SEGMENTS) - 1):
        start, radius = BOLT_SEGMENTS[index]
        end, next_radius = BOLT_SEGMENTS[index + 1]
        thickness = max(0.12, (radius + next_radius) / 2.0)
        elements += prism(ATLAS, CENTRE, CENTRE, thickness, start, end, "plasma", axis="z", sides=8)
    return elements


def mark_tint(elements: list[dict], materials: tuple[str, ...], layer: int = 0) -> list[dict]:
    """Meldet die Flächen der genannten Materialien für eine Farbebene an.

    Flächen ohne ``tintindex`` lässt Vanilla ungefärbt; die Zahl wählt, welcher Eintrag aus
    ``tints`` gilt. Genau daran hängt, dass Spulen und Anzeigen sich unabhängig bewegen.
    """
    wanted = {tuple(ATLAS.uv(material)) for material in materials}
    for element in elements:
        for face in element["faces"].values():
            if tuple(face["uv"]) in wanted:
                face["tintindex"] = layer
    return elements


# ---------------------------------------------------------------------------
# Anzeige und Mündung
# ---------------------------------------------------------------------------

# **Y bleibt nahe null, nicht nahe 180.** Die Minigun dreht dort um eine halbe Umdrehung, weil
# ihre Mündung bei hohem z liegt. Hier liegt sie bei niedrigem z, und -Z ist im Anzeigeraum
# bereits die Richtung vom Auge weg: dieselbe halbe Umdrehung zeigte die Waffe auf den Träger,
# und man sähe in den Lauf statt hinaus.
HAND_ROTATION = (3, 9, 5)
HAND_FILL = 0.55

# Wo die Mündung in der Ersten-Person-Ansicht sitzen soll (vorwärts, rechts, unten, in Metern
# vom Auge aus). Die Werte sind an der Minigun abgelesen, die dort nachweislich richtig liegt.
# Die Handverschiebung wird daraus zurückgerechnet, statt sie zu suchen – nach jeder Änderung
# an Geometrie oder Drehung stimmt sie damit von selbst wieder.
MUZZLE_TARGET = (1.05, 0.30, 0.28)


def hand_offset(elements: list[dict]) -> tuple[float, float, float]:
    """Löst die Handverschiebung nach der gewünschten Mündungslage auf.

    Ein Modellpunkt p landet nach {@code ItemTransform#apply} bei ``T + R·(S·(p/16 - 0.5))``,
    davor sitzt die feste Handverschiebung des Renderers. Alle drei Achsen hängen linear an der
    Verschiebung, die Gleichung lässt sich also unmittelbar umstellen.
    """
    base = centred_display(elements, HAND_ROTATION, HAND_FILL)
    scale = base["scale"][0]
    matrix = modelkit.rotation_matrix(HAND_ROTATION)
    local = modelkit.apply(matrix, (CENTRE / 16.0 - 0.5, AXIS_Y / 16.0 - 0.5, MUZZLE_Z / 16.0 - 0.5))

    forward, right, down = MUZZLE_TARGET
    wanted = (
        (right - HAND_ANCHOR[0] - scale * local[0]) / 0.0625,
        (-down - HAND_ANCHOR[1] - scale * local[1]) / 0.0625,
        (-forward - HAND_ANCHOR[2] - scale * local[2]) / 0.0625,
    )
    # base["translation"] enthält schon die Einmittung; gesucht ist der Rest.
    return tuple(wanted[axis] - base["translation"][axis] for axis in range(3))


def display_for(elements: list[dict]) -> dict:
    hand = centred_display(elements, HAND_ROTATION, HAND_FILL, offset=hand_offset(elements))
    return {
        "firstperson_righthand": hand,
        "firstperson_lefthand": dict(hand),
        "thirdperson_righthand": centred_display(elements, (12, -155, 0), 0.52, offset=(0.0, 2.6, 0.0)),
        "thirdperson_lefthand": centred_display(elements, (12, -155, 0), 0.52, offset=(0.0, 2.6, 0.0)),
        "gui": centred_display(elements, (24, -148, 0), 0.96, flat=True),
        "ground": centred_display(elements, (0, 0, 0), 0.6, offset=(0.0, 3.0, 0.0)),
        "fixed": centred_display(elements, (0, 180, 0), 0.96),
        "head": centred_display(elements, (0, 180, 0), 1.1, offset=(0.0, 13.0, 0.0)),
    }


def bolt_display(elements: list[dict]) -> dict:
    hand = centred_display(elements, (0, -140, 20), 0.5, offset=(1.0, 1.4, 0.0))
    return {
        "firstperson_righthand": hand,
        "firstperson_lefthand": dict(hand),
        "thirdperson_righthand": centred_display(elements, (0, -140, 20), 0.48, offset=(0.0, 2.5, 0.0)),
        "thirdperson_lefthand": centred_display(elements, (0, -140, 20), 0.48, offset=(0.0, 2.5, 0.0)),
        "gui": centred_display(elements, (20, -150, 24), 0.9, flat=True),
        "ground": centred_display(elements, (90, 0, 0), 0.5, offset=(0.0, 3.0, 0.0)),
        "fixed": centred_display(elements, (0, 180, 0), 0.9),
        "head": centred_display(elements, (0, 180, 0), 1.0, offset=(0.0, 13.0, 0.0)),
    }


def muzzle_in_hand(elements: list[dict]) -> tuple[float, float, float]:
    """Wo die Mündung in der Ersten-Person-Ansicht wirklich sitzt – die Probe auf die Rechnung.

    Dieselbe Rechnung wie bei der Minigun: der Modellpunkt wird durch die
    Anzeige-Transformation und die Handverschiebung von ``ItemInHandRenderer`` geführt. Kommt
    hier nicht MUZZLE_TARGET heraus, stimmt etwas an der Herleitung nicht.
    """
    transform = centred_display(elements, HAND_ROTATION, HAND_FILL, offset=hand_offset(elements))
    matrix = modelkit.rotation_matrix(tuple(transform["rotation"]))
    scale = transform["scale"][0]
    local = modelkit.apply(matrix, (CENTRE / 16.0 - 0.5, AXIS_Y / 16.0 - 0.5, MUZZLE_Z / 16.0 - 0.5))

    right = transform["translation"][0] * 0.0625 + scale * local[0] + HAND_ANCHOR[0]
    up = transform["translation"][1] * 0.0625 + scale * local[1] + HAND_ANCHOR[1]
    forward = -(transform["translation"][2] * 0.0625 + scale * local[2] + HAND_ANCHOR[2])
    return forward, right, -up


def main() -> int:
    texture_path = TEXTURES_DIR / "railgun.png"
    ATLAS.save(texture_path)
    print(f"Atlas: {texture_path.relative_to(ROOT)} "
          f"({texture_path.stat().st_size} B, {ATLAS.size[0]}x{ATLAS.size[1]}, {len(ATLAS.slots)} Materialien)")

    weapon = mark_tint(mark_tint(build_railgun(), ("coil",), 0), ("screen",), 1)
    bolt = mark_tint(build_bolt(), ("plasma",), 0)

    weapon_tints = [
        {"type": "minecraft:dye", "default": modelkit.opaque(0x3AA0FF)},
        # Ebene 1 liest ihre Farbe aus der Farbliste von CUSTOM_MODEL_DATA; RailgunSystem setzt
        # sie Tick für Tick. Ohne den Eintrag zeigen die Anzeigen den Standardwert.
        {"type": "minecraft:custom_model_data", "index": 0, "default": 0x1FBFA8},
    ]
    for name, elements, display, tints in (
        ("railgun", weapon, display_for(weapon), weapon_tints),
        ("railgun_bolt", bolt, bolt_display(bolt), [{"type": "minecraft:dye", "default": modelkit.opaque(0x9FE8FF)}]),
    ):
        modelkit.write(MODELS_DIR / f"{name}.json", modelkit.model(TEXTURE, elements, display))
        modelkit.write(ITEMS_DIR / f"{name}.json", {
            "model": {
                "type": "minecraft:model",
                "model": f"oneshotonekill:item/{name}",
                "tints": tints,
            },
        })
        low, high = modelkit.bounds(elements)
        layers = [sum(1 for element in elements for face in element["faces"].values()
                      if face.get("tintindex") == layer) for layer in (0, 1)]
        print(f"{name:14} {len(elements):3} Elemente, Ausdehnung "
              f"{[round(v, 2) for v in low]} .. {[round(v, 2) for v in high]}, "
              f"Flächen je Farbebene {layers}")

    low, high = modelkit.bounds(bolt)
    print(f"\nDicke der Lanze bei Skalierung 1: {(high[0] - low[0]) / 16.0:.4f} Bl\u00f6cke \u2013 muss mit "
          f"BOLT_GIRTH_UNIT in\nitem/runtime/RailgunSystem.java \u00fcbereinstimmen, sonst ist der Strahl "
          f"ein Faden.")

    forward, right, down = muzzle_in_hand(weapon)
    print("\nMündung in der Ersten-Person-Ansicht (Kameraraum, Meter) – muss mit den Konstanten")
    print("in src/main/java/de/leopold/oneshotonekill/item/runtime/RailgunSystem.java übereinstimmen:")
    print(f"  MUZZLE_FORWARD = {forward:.4f}")
    print(f"  MUZZLE_RIGHT   = {right:.4f}")
    print(f"  MUZZLE_DOWN    = {down:.4f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
