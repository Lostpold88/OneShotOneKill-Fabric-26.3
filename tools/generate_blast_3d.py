"""Erzeugt Textur und Modelle für den Atompilz des Luftangriffs.

    python tools/generate_blast_3d.py

Ausgabe:
  * textures/item/blast.png      – gemeinsamer Materialatlas
  * models/item/blast_puff.json  – Wolkenballen (Feuerball, Stiel, Hut, Bodenwelle)
  * models/item/blast_ring.json  – Druckwelle als flacher Ring
  * models/item/blast_shard.json – herausgeschleuderter Brocken
  * items/blast_*.json           – Item-Definitionen mit Farbanschluss

**Die Modelle sind mit Absicht fast weiß.** Ihre Farbe kommt erst zur Laufzeit: die
Item-Definition trägt ``"tints": [{"type": "minecraft:dye"}]``, und das multipliziert die
Textur mit dem ``DyedItemColor`` des gezeigten Stapels. Ein einziges Modell reicht damit für
den weißglühenden Feuerball und den fast schwarzen Rauch am Ende – und die Farbe darf sich
während des Aufstiegs ändern, ohne dass ein zweites Modell nötig wäre. Wer die Textur
nachdunkelt, nimmt der Laufzeit genau diesen Spielraum.

**Maßstab: Skalierung 1 heißt ein Block.** Alle drei Modelle sind so gebaut, dass ihre
kennzeichnende Ausdehnung – beim Ballen und beim Brocken der Durchmesser, beim Ring der
Außendurchmesser des Kreises – genau 16 Modelleinheiten misst. In
``nuke/MushroomCloud.java`` ist der Skalierungswert dadurch unmittelbar die Größe in
Blöcken, ohne Umrechnungsfaktor, den irgendwann jemand vergisst.
"""

from __future__ import annotations

import math
import pathlib
import random

import modelkit
from modelkit import Atlas, Tile, centred_display, cube, dither, prism, shift

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src/main/resources/assets/oneshotonekill"
MODELS_DIR = ASSETS / "models/item"
ITEMS_DIR = ASSETS / "items"
TEXTURES_DIR = ASSETS / "textures/item"
TEXTURE = "oneshotonekill:item/blast"

CENTRE = 8.0

# Fester Startwert: die Modelle sollen bei jedem Lauf gleich herauskommen, sonst ist jeder
# Neubau ein unlesbares Diff.
SEED = 20260818


# ---------------------------------------------------------------------------
# Materialien
# ---------------------------------------------------------------------------


def _blotches(tile: Tile, rng: random.Random, count: int, radius: tuple[float, float],
              depth: tuple[int, int]) -> None:
    """Weiche dunkle Flecken – ohne sie wirkt eine Kugel wie poliertes Plastik."""
    for _ in range(count):
        centre_x = rng.uniform(0.0, modelkit.TILE)
        centre_y = rng.uniform(0.0, modelkit.TILE)
        spread = rng.uniform(*radius)
        strength = rng.randint(*depth)
        for y in range(modelkit.TILE):
            for x in range(modelkit.TILE):
                distance = math.hypot(x - centre_x, y - centre_y)
                if distance < spread:
                    falloff = 1.0 - distance / spread
                    tile.set(x, y, shift(tile.get(x, y), -round(strength * falloff * falloff)))


def _billow() -> Tile:
    """Wolkenhaut: nahezu weiß, damit die Einfärbung den ganzen Farbraum behält."""
    tile = Tile()
    tile.fill_gradient((252, 250, 246), (198, 195, 190), 9)
    _blotches(tile, random.Random(SEED), 9, (2.4, 5.2), (22, 52))
    return tile


def _crust() -> Tile:
    """Aufgeworfenes Erdreich: körnig, mit hellen Bruchkanten."""
    tile = Tile()
    tile.fill_gradient((228, 222, 212), (156, 150, 142), 14)
    _blotches(tile, random.Random(SEED + 1), 6, (1.6, 3.4), (34, 68))
    rng = random.Random(SEED + 2)
    for _ in range(20):
        x, y = rng.randrange(modelkit.TILE), rng.randrange(modelkit.TILE)
        tile.set(x, y, shift(tile.get(x, y), rng.choice((-46, 34))))
    return tile


def _wave() -> Tile:
    """Druckwelle: oben hell, nach unten auslaufend – der Ring soll oben aufblitzen."""
    tile = Tile()
    tile.fill_gradient((255, 253, 248), (176, 172, 166), 7)
    tile.bands(range(0, 3), 12)
    _blotches(tile, random.Random(SEED + 3), 5, (2.0, 4.4), (16, 34))
    return tile


ATLAS = Atlas({"billow": _billow, "crust": _crust, "wave": _wave}, columns=3)


# ---------------------------------------------------------------------------
# Der Wolkenballen
# ---------------------------------------------------------------------------

# Radius der Grundkugel. Die Beulen ragen darüber hinaus; zusammen ergibt das die vollen
# 16 Einheiten, siehe LOBE_REACH.
PUFF_RADIUS = 7.0
PUFF_BANDS = 5
PUFF_SIDES = 8
LOBE_COUNT = 7
# Wie weit die Mitte einer Beule von der Kugelmitte wegrückt, im Verhältnis zum Radius.
# Deutlich unter 0,7 verschwinden die Beulen in der Kugel und der Ballen bleibt eine Kugel.
LOBE_DISTANCE = 0.74
LOBE_HALF_SIZE = 0.36
# Eine gedrehte Beule reicht bis zu ihrer Raumdiagonale hinaus.
LOBE_REACH = LOBE_DISTANCE + LOBE_HALF_SIZE * math.sqrt(3.0)


def sphere(radius: float, bands: int, sides: int, material: str) -> list[dict]:
    """Kugel aus waagerechten Scheiben, jede ein n-Eck-Zylinder.

    Der Radius jeder Scheibe wird auf ihrer Mitte abgegriffen. An den Rändern sitzt die
    Kugel dadurch abwechselnd einen Hauch zu eng und zu weit – gemittelt stimmt sie, und
    eine Rauchwolke lebt ohnehin davon, nicht ganz rund zu sein.
    """
    elements: list[dict] = []
    for index in range(bands):
        low = -radius + 2.0 * radius * index / bands
        high = -radius + 2.0 * radius * (index + 1) / bands
        middle = (low + high) / 2.0
        band_radius = math.sqrt(max(0.25, radius * radius - middle * middle))
        # Achse y: corner_of legt a auf z und b auf x.
        elements += prism(ATLAS, CENTRE, CENTRE, band_radius,
                          CENTRE + low, CENTRE + high, material, axis="y", sides=sides)
    return elements


def build_puff() -> list[dict]:
    """Kugel plus ein paar herausquellende Beulen.

    Eine reine Kugel liest sich als Ball, nicht als Wolke. Die Beulen brechen die Silhouette
    auf; weil jede eigenwillig gedreht steht, wiederholt sich beim Stapeln kein Umriss.
    """
    elements = sphere(PUFF_RADIUS, PUFF_BANDS, PUFF_SIDES, "billow")
    rng = random.Random(SEED + 10)

    for index in range(LOBE_COUNT):
        # Gleichmäßig über die Kugel verteilt: Höhe linear, Winkel im goldenen Schnitt.
        height = 1.0 - 2.0 * (index + 0.5) / LOBE_COUNT
        ring = math.sqrt(max(0.0, 1.0 - height * height))
        angle = index * 2.399963
        direction = (math.cos(angle) * ring, height, math.sin(angle) * ring)

        half = PUFF_RADIUS * LOBE_HALF_SIZE * rng.uniform(0.72, 1.28)
        origin = [round(CENTRE + direction[axis] * PUFF_RADIUS * LOBE_DISTANCE, 3) for axis in range(3)]
        elements.append(cube(
            ATLAS,
            (origin[0] - half, origin[1] - half, origin[2] - half),
            (origin[0] + half, origin[1] + half, origin[2] + half),
            "billow",
            rotation={
                "origin": origin,
                "x": round(rng.uniform(0.0, 90.0), 2),
                "y": round(rng.uniform(0.0, 90.0), 2),
                "z": round(rng.uniform(0.0, 90.0), 2),
            },
        ))

    return elements


# ---------------------------------------------------------------------------
# Die Druckwelle
# ---------------------------------------------------------------------------

RING_SEGMENTS = 16
# Mittellinie bei 8 Einheiten: der Kreis misst damit 16 Einheiten im Durchmesser.
RING_RADIUS = 8.0
RING_HALF_HEIGHT = 0.9
RING_HALF_DEPTH = 1.2


def build_ring() -> list[dict]:
    """Ein Torus aus geraden Stücken, im Kreis gedreht.

    Bis 26.2 wäre das nicht gegangen: das Modellformat ließ nur Vielfache von 22,5 Grad zu,
    ein Ring hätte also höchstens acht Ecken gehabt. Jetzt sind beliebige Winkel erlaubt.
    """
    # Etwas breiter als die Bogenlänge, damit zwischen zwei Stücken keine Lücke klafft.
    half_width = math.pi * RING_RADIUS / RING_SEGMENTS * 1.15
    elements: list[dict] = []

    for index in range(RING_SEGMENTS):
        elements.append(cube(
            ATLAS,
            (CENTRE - half_width, CENTRE - RING_HALF_HEIGHT, CENTRE + RING_RADIUS - RING_HALF_DEPTH),
            (CENTRE + half_width, CENTRE + RING_HALF_HEIGHT, CENTRE + RING_RADIUS + RING_HALF_DEPTH),
            "wave",
            rotation={
                "origin": [CENTRE, CENTRE, CENTRE],
                "axis": "y",
                "angle": round(index * 360.0 / RING_SEGMENTS, 4),
            },
        ))

    return elements


# ---------------------------------------------------------------------------
# Der Brocken
# ---------------------------------------------------------------------------

SHARD_PIECES = 5


def build_shard() -> list[dict]:
    """Ein kantiger Klumpen aus wenigen, gegeneinander verdrehten Kästen."""
    rng = random.Random(SEED + 20)
    elements: list[dict] = []

    for _ in range(SHARD_PIECES):
        half = [rng.uniform(2.0, 4.2) for _ in range(3)]
        origin = [round(CENTRE + rng.uniform(-1.8, 1.8), 3) for _ in range(3)]
        elements.append(cube(
            ATLAS,
            (origin[0] - half[0], origin[1] - half[1], origin[2] - half[2]),
            (origin[0] + half[0], origin[1] + half[1], origin[2] + half[2]),
            "crust",
            rotation={
                "origin": origin,
                "x": round(rng.uniform(0.0, 360.0), 2),
                "y": round(rng.uniform(0.0, 360.0), 2),
                "z": round(rng.uniform(0.0, 360.0), 2),
            },
        ))

    return elements


# ---------------------------------------------------------------------------
# Maßstab und Ausgabe
# ---------------------------------------------------------------------------


def mark_tintable(elements: list[dict]) -> list[dict]:
    """Meldet jede Fläche für die Einfärbung an.

    Ohne ``tintindex`` bleibt eine Fläche ungefärbt – Vanilla setzt den Wert auf -1 und lässt
    den Farbeintrag der Item-Definition dann schlicht liegen. Der ganze Farbverlauf des
    Atompilzes hinge daran, ohne dass irgendetwas fehlschlüge: die Modelle kämen einfach
    kreideweiß heraus.
    """
    for element in elements:
        for face in element["faces"].values():
            face["tintindex"] = 0
    return elements


def fit_to_block(elements: list[dict]) -> list[dict]:
    """Rückt und streckt die Geometrie so, dass sie genau die 16 Einheiten um (8,8,8) füllt.

    Damit stimmt die Zusage aus dem Kopfkommentar: Skalierung 1 ist ein Block. Von Hand
    passende Radien zu suchen ginge auch – bis zur nächsten Änderung an einer Beule.
    """
    low, high = modelkit.bounds(elements)
    extent = max(high[axis] - low[axis] for axis in range(3))
    factor = 16.0 / extent
    centre = [(high[axis] + low[axis]) / 2.0 for axis in range(3)]

    def move(point: list[float]) -> list[float]:
        return [round(CENTRE + (point[axis] - centre[axis]) * factor, 4) for axis in range(3)]

    for element in elements:
        element["from"] = move(element["from"])
        element["to"] = move(element["to"])
        rotation = element.get("rotation")
        if rotation is not None:
            rotation["origin"] = move(rotation["origin"])

    return elements


def display_for(elements: list[dict]) -> dict:
    """Diese Modelle hängen nur an Effekt-Entities; die Handhaltungen sind reine Vorsorge."""
    hand = centred_display(elements, (0, 0, 0), 0.45, offset=(1.0, 1.5, 0.0))
    return {
        "firstperson_righthand": hand,
        "firstperson_lefthand": dict(hand),
        "thirdperson_righthand": centred_display(elements, (0, 0, 0), 0.45, offset=(0.0, 2.5, 0.0)),
        "thirdperson_lefthand": centred_display(elements, (0, 0, 0), 0.45, offset=(0.0, 2.5, 0.0)),
        "gui": centred_display(elements, (25, 45, 0), 0.9, flat=True),
        "ground": centred_display(elements, (0, 0, 0), 0.5, offset=(0.0, 3.0, 0.0)),
        "fixed": centred_display(elements, (0, 0, 0), 0.95),
        "head": centred_display(elements, (0, 0, 0), 1.0, offset=(0.0, 13.0, 0.0)),
    }


def main() -> int:
    texture_path = TEXTURES_DIR / "blast.png"
    ATLAS.save(texture_path)
    print(f"Atlas: {texture_path.relative_to(ROOT)} "
          f"({texture_path.stat().st_size} B, {ATLAS.size[0]}x{ATLAS.size[1]}, {len(ATLAS.slots)} Materialien)")

    for name, builder in (
        ("blast_puff", build_puff),
        ("blast_ring", build_ring),
        ("blast_shard", build_shard),
    ):
        elements = mark_tintable(fit_to_block(builder()))
        modelkit.write(MODELS_DIR / f"{name}.json", modelkit.model(TEXTURE, elements, display_for(elements)))
        modelkit.write(ITEMS_DIR / f"{name}.json", {
            "model": {
                "type": "minecraft:model",
                "model": f"oneshotonekill:item/{name}",
                # Ohne Farbe im Stapel bleibt das Modell weiß – so, wie die Textur gemalt ist.
                "tints": [{"type": "minecraft:dye", "default": modelkit.opaque(0xFFFFFF)}],
            },
        })
        low, high = modelkit.bounds(elements)
        print(f"{name:12} {len(elements):3} Elemente, Ausdehnung "
              f"{[round(v, 2) for v in low]} .. {[round(v, 2) for v in high]}")
        if name == "blast_ring":
            print(f"{'':12} Bandhöhe bei Skalierung 1: {(high[1] - low[1]) / 16.0:.4f} Blöcke – muss mit "
                  f"WAVE_BAND_UNIT in nuke/MushroomCloud.java übereinstimmen, sonst wird die "
                  f"Druckwelle zur Röhre.")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
