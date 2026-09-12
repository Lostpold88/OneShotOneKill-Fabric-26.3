"""Generate the Boogie-Bomb, overhead disco ball and translucent rotating light shafts.

Run from any directory: python MOD/tools/models/generate_boogie_bomb_3d.py
The atlas has eight animated frames. Mirror facets alternate lime, pink and cyan;
the safety lever, pin ring and gold collar remain metallic.
"""
from __future__ import annotations

import math
from pathlib import Path
from PIL import Image
import modelkit
from modelkit import Atlas, Tile, cube, prism, centred_display

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/main/resources/assets/oneshotonekill"
COLOURS = [(191, 255, 63), (255, 92, 234), (88, 241, 255), (255, 231, 72)]
MATERIALS = {}


def metal(top, bottom):
    def paint():
        tile = Tile()
        tile.fill_gradient(top, bottom, 4)
        tile.streaks((2, 3, 11, 12), 8)
        return tile
    return paint


MATERIALS.update({
    "mirror": metal((115, 149, 143), (19, 40, 43)),
    "dark": metal((43, 73, 86), (12, 26, 36)),
    "steel": metal((237, 246, 247), (97, 127, 141)),
    "gold": metal((255, 211, 65), (179, 98, 8)),
})
for index, colour in enumerate(COLOURS):
    MATERIALS[f"light{index}"] = metal((255, 255, 244), colour)
ATLAS = Atlas(MATERIALS, columns=4)


def sphere(radius=4.4, cy=6.8):
    elements = []
    # A solid faceted core beneath individual mirror tiles, with visible dark seams.
    for band in range(8):
        lo = -radius + 2 * radius * band / 8
        hi = -radius + 2 * radius * (band + 1) / 8
        r = math.sqrt(max(0.01, radius * radius - ((lo + hi) / 2) ** 2))
        elements += prism(ATLAS, 8, 8, r * 0.985, cy + lo, cy + hi, "dark", axis="y", sides=16)
    for row in range(9):
        latitude = -72 + row * 18
        width = max(0.25, math.cos(math.radians(latitude)) * radius * math.pi / 8 * 0.92)
        for column in range(16):
            # Narrow cuboids tangent to the sphere form actual 3-D mirror facets.
            material = f"light{(row + column) % 4}" if (row * 11 + column * 7) % 5 == 0 else "mirror"
            element = cube(ATLAS,
                (8 - width / 2, cy - 0.60, 8 + radius - 0.13),
                (8 + width / 2, cy + 0.60, 8 + radius + 0.03),
                material, rotation={"origin": [8, cy, 8], "x": latitude, "y": column * 22.5, "z": 0})
            if material.startswith("light"):
                element["light_emission"] = 15
                element["shade"] = False
            elements.append(element)
    return elements


def grenade():
    elements = sphere()
    # Silver tapered neck and characteristic thick gold retaining collar.
    elements += prism(ATLAS, 8, 8, 2.35, 10.6, 11.35, "gold", axis="y", sides=16)
    elements += prism(ATLAS, 8, 8, 1.62, 11.3, 12.0, "steel", axis="y", sides=12)
    elements += prism(ATLAS, 8, 8, 1.12, 12.0, 12.85, "steel", axis="y", sides=12)
    elements.append(cube(ATLAS, (6.7, 12.6, 6.8), (9.1, 13.65, 9.0), "dark"))
    # The spoon crosses the top and folds down one side.
    elements.append(cube(ATLAS, (5.15, 13.55, 6.9), (9.35, 14.1, 8.7), "dark"))
    elements.append(cube(ATLAS, (4.15, 11.5, 6.9), (5.35, 14.05, 8.7), "dark",
        rotation={"origin": [5.1, 13.6, 7.8], "x": 0, "y": 0, "z": -24}))
    elements.append(cube(ATLAS, (3.9, 5.1, 6.95), (4.5, 12.0, 8.65), "dark",
        rotation={"origin": [4.25, 11.5, 7.8], "x": 0, "y": 0, "z": -4}))
    # Pull ring in the front plane. Sixteen bars leave a true opening.
    for segment in range(16):
        angle = segment * 22.5
        elements.append(cube(ATLAS, (7.64, 10.65, 5.35), (8.36, 10.90, 5.70), "steel",
            rotation={"origin": [8, 12.15, 5.53], "x": 0, "y": 0, "z": angle}))
    elements += prism(ATLAS, 8, 13.05, 0.40, 5.25, 7.0, "steel", axis="z", sides=8)
    return elements


def display(elements):
    return {
        "gui": centred_display(elements, (18, -35, -12), 0.95, flat=True),
        "firstperson_righthand": centred_display(elements, (0, -35, 8), 0.53, offset=(0.8, 2.8, 0)),
        "firstperson_lefthand": centred_display(elements, (0, 35, -8), 0.53, offset=(-0.8, 2.8, 0)),
        "thirdperson_righthand": centred_display(elements, (0, -35, 8), 0.45, offset=(0, 2.6, 0)),
        "thirdperson_lefthand": centred_display(elements, (0, 35, -8), 0.45, offset=(0, 2.6, 0)),
        "ground": centred_display(elements, (0, 0, 0), 0.45, offset=(0, 2.5, 0)),
        "fixed": centred_display(elements, (0, 0, 0), 1),
    }


def write_model(name, elements, texture, transforms=None, tinted=False):
    payload = modelkit.model(texture, elements, transforms or {})
    modelkit.write(ASSETS / f"models/item/{name}.json", payload)
    definition = {"type": "minecraft:model", "model": f"oneshotonekill:item/{name}"}
    if tinted:
        definition["tints"] = [{"type": "minecraft:dye", "default": modelkit.opaque(0xFFFFFF)}]
    modelkit.write(ASSETS / f"items/{name}.json", {"model": definition})


def animated_atlas():
    path = ASSETS / "textures/item/boogie_bomb.png"
    ATLAS.save(path)
    base = Image.open(path).convert("RGBA")
    animation = Image.new("RGBA", (base.width, base.height * 8))
    for frame in range(8):
        tile = base.copy()
        for light in range(4):
            column, row = ATLAS.slots[f"light{light}"]
            colour = COLOURS[(light + frame // 2) % len(COLOURS)]
            for y in range(16):
                for x in range(16):
                    strength = 0.66 + 0.34 * math.sin((frame / 8 + light / 4) * math.tau) ** 2
                    edge = 0.6 if x in (0, 15) or y in (0, 15) else 1.0
                    highlight = 45 if 3 <= x <= 5 and 2 <= y <= 12 else 0
                    rgb = tuple(min(255, round(channel * strength * edge) + highlight) for channel in colour)
                    tile.putpixel((column * 16 + x, row * 16 + y), (*rgb, 255))
        animation.paste(tile, (0, frame * base.height))
    animation.save(path)
    modelkit.write(path.with_suffix(".png.mcmeta"),
        {"animation": {"frametime": 2, "interpolate": True, "width": base.width, "height": base.height}})


def light_shafts():
    # Crossed thin translucent ribbons, one block long along Y. World transforms
    # stretch them from the overhead sphere down to four rotating floor spots.
    image = Image.new("RGBA", (16, 32))
    for y in range(32):
        for x in range(16):
            edge = max(0, 1 - abs((x - 7.5) / 7.5))
            alpha = round(90 * edge * (0.25 + 0.75 * y / 31))
            image.putpixel((x, y), (255, 255, 255, alpha))
    image.save(ASSETS / "textures/item/boogie_beam.png")
    faces = lambda: {side: {"uv": [0, 0, 16, 16], "texture": "#atlas", "tintindex": 0}
                     for side in modelkit.FACES}
    elements = [
        {"from": [6.5, 0, 8], "to": [9.5, 16, 8], "faces": faces(), "shade": False, "light_emission": 15},
        {"from": [8, 0, 6.5], "to": [8, 16, 9.5], "faces": faces(), "shade": False, "light_emission": 15},
    ]
    write_model("boogie_beam", elements, "oneshotonekill:item/boogie_beam", tinted=True)


def main():
    animated_atlas()
    bomb = grenade()
    ball = sphere(4.0, 8.0)
    write_model("boogie_bomb", bomb, "oneshotonekill:item/boogie_bomb", display(bomb))
    write_model("boogie_disco_ball", ball, "oneshotonekill:item/boogie_bomb")
    light_shafts()
    print(f"Boogie-Bomb: {len(bomb)} elements; disco ball: {len(ball)} elements; 8 animated atlas frames")
    print(f"Assets: {ASSETS}")


if __name__ == "__main__":
    main()
