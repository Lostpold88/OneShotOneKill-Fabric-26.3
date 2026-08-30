"""Erzeugt Item-Definitionen, Modelle und Sprachdateien für alle Spezial-Items.

    python tools/generate_item_assets.py

Die Item-JSONs und Modelle sind für alle Items strukturgleich – von Hand
gepflegt wären das 34 fast identische Dateien, bei denen ein Tippfehler erst im
Spiel als fehlende Textur auffällt. Die Anzeigenamen stehen hier an einer
Stelle für beide Sprachen.
"""

from __future__ import annotations

import json
import pathlib

ASSETS = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/assets/oneshotonekill"

# id -> (Griff-Stil, deutscher Name, englischer Name)
#   "handheld"  = wird wie ein Werkzeug in der Hand gehalten (Waffen, Stäbe)
#   "generated" = flaches Item (Wurfgeschosse, Platten, Ladungen)
ITEMS: dict[str, tuple[str, str, str]] = {
    "minigun":            ("custom_3d", "Minigun",               "Minigun"),
    "airstrike":          ("custom_3d", "Luftangriff",           "Air Strike"),
    # Reiner Modellträger für die fallende Nuklearbombe des Luftangriffs.
    "airstrike_nuke":     ("custom_3d", "Nuklearbombe",          "Nuclear Bomb"),
    "radar_pulse":        ("custom_3d", "Radar-Puls",            "Radar Pulse"),
    "explosive_shot":     ("custom_3d", "Explosiv-Schuss",       "Explosive Shot"),
    "reflector_shield":   ("custom_3d", "Reflektor-Schild",      "Reflector Shield"),
    # Reiner Modellträger für die besitzerexklusive Energiekugel.
    "reflector_barrier":  ("custom_3d", "Reflektor-Barriere",     "Reflector Barrier"),
    "smoke_bomb":         ("custom_3d", "Rauchgranate",          "Smoke Grenade"),
    "frost_trap":         ("custom_3d", "Frost-Falle",           "Frost Trap"),
    "frost_shard":        ("custom_3d", "Eiskristall",           "Frost Shard"),
    "teleport_grenade":   ("custom_3d", "Teleport-Granate",      "Teleport Grenade"),
    "invisibility_cloak": ("custom_3d", "Unsichtbarkeits-Mantel", "Invisibility Cloak"),
    "arrow_magnet":       ("custom_3d", "Pfeil-Magnetfeld",      "Arrow Magnet"),
    # Prozedurales 3D-Modell aus ChainLightningItemRenderer, getrennt vom Weltprojektil.
    "chain_lightning":    ("custom_3d", "Kettenblitz-Schuss",    "Chain Lightning Shot"),
    # Reiner Modellträger für den sichtbaren Blitz im Flug und zwischen Kettenzielen.
    "chain_lightning_bolt": ("custom_3d", "Kettenblitz",          "Chain Lightning Bolt"),
    "stealth_bomber":     ("custom_3d", "Tarnkappenbomber",      "Stealth Bomber"),
    # Nur Modelltraeger fuer die fallenden Ladungen – nie im Inventar eines Spielers.
    "bomber_bomb":        ("custom_3d", "Bomberladung",          "Bomber Charge"),
    "c4":                 ("custom_3d", "C4",                    "C4"),
    "c4_charge":          ("custom_3d", "C4-Ladung",             "C4 Charge"),
    "railgun":            ("custom_3d", "Railgun",               "Railgun"),
    "railgun_bolt":       ("custom_3d", "Railgun-Strahl",        "Railgun Bolt"),
    "singularity":        ("custom_3d", "Singularität",          "Singularity"),
    "slow_motion":       ("custom_3d", "Zeitverzerrer",         "Time Distorter"),
    "glider":             ("custom_3d", "Gleitflug",             "Glide Rig"),
    "sentry_turret":      ("custom_3d", "Geschützturm",          "Sentry Turret"),
    # Reiner Modellträger für den stillstehenden Unterbau des platzierten Turms.
    "sentry_base":        ("custom_3d", "Geschützturm-Unterbau", "Sentry Turret Base"),
    "sentry_head":        ("custom_3d", "Geschützturm-Kopf",     "Sentry Turret Head"),
    # Reine Anzeigehilfe: die schwebende Box um ein am Boden liegendes Spezial-Item.
    "item_box":           ("cube",      "Item-Box",              "Item Box"),
    # Bausteine des Atompilzes – siehe tools/generate_blast_3d.py.
    "blast_puff":         ("custom_3d", "Wolkenballen",          "Blast Billow"),
    "blast_ring":         ("custom_3d", "Druckwelle",            "Blast Wave"),
    "blast_shard":        ("custom_3d", "Erdbrocken",            "Blast Debris"),
}

# Waffen sollen in der Ersten-Person-Ansicht schräg und weiter vorn liegen.
HANDHELD_DISPLAY = {
    "firstperson_righthand": {
        "rotation": [0, 180, 12],
        "translation": [1.8, 1.1, 0.55],
        "scale": [0.42, 0.42, 0.42],
    },
    "firstperson_lefthand": {
        "rotation": [0, 0, -12],
        "translation": [-1.8, 1.1, 0.55],
        "scale": [0.42, 0.42, 0.42],
    },
}


def write(path: pathlib.Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def main() -> int:
    for item_id, (style, _, _) in ITEMS.items():
        # Items mit 3D-Modell bringen Modell *und* Item-Definition selbst mit, weil beides
        # zusammen erzeugt wird. Siehe tools/generate_minigun_3d.py und
        # tools/generate_stealth_bomber_3d.py.
        if style == "custom_3d":
            continue

        write(ASSETS / "items" / f"{item_id}.json", {
            "model": {"type": "minecraft:model", "model": f"oneshotonekill:item/{item_id}"},
        })

        if style == "cube":
            model: dict = {
                "textures": {
                    "all": f"oneshotonekill:item/{item_id}",
                    "particle": f"oneshotonekill:item/{item_id}",
                },
                "elements": [
                    {
                        "from": [0, 0, 0],
                        "to": [16, 16, 16],
                        "shade": True,
                        "faces": {
                            "north": {"uv": [0, 0, 16, 16], "texture": "#all"},
                            "east":  {"uv": [0, 0, 16, 16], "texture": "#all"},
                            "south": {"uv": [0, 0, 16, 16], "texture": "#all"},
                            "west":  {"uv": [0, 0, 16, 16], "texture": "#all"},
                            "up":    {"uv": [0, 0, 16, 16], "texture": "#all"},
                            "down":  {"uv": [0, 0, 16, 16], "texture": "#all"},
                        },
                    }
                ],
                "display": {
                    "fixed": {
                        "rotation": [0, 0, 0],
                        "translation": [0, 0, 0],
                        "scale": [1, 1, 1],
                    },
                    "ground": {
                        "rotation": [0, 0, 0],
                        "translation": [0, 3, 0],
                        "scale": [0.5, 0.5, 0.5],
                    },
                    "gui": {
                        "rotation": [30, 225, 0],
                        "translation": [0, 0, 0],
                        "scale": [0.625, 0.625, 0.625],
                    },
                    "firstperson_righthand": {
                        "rotation": [0, 45, 0],
                        "translation": [0, 2, 0],
                        "scale": [0.4, 0.4, 0.4],
                    },
                    "thirdperson_righthand": {
                        "rotation": [75, 45, 0],
                        "translation": [0, 2.5, 0],
                        "scale": [0.375, 0.375, 0.375],
                    },
                },
            }
        else:
            model = {
                "parent": f"minecraft:item/{style}",
                "textures": {"layer0": f"oneshotonekill:item/{item_id}"},
            }
            if style == "handheld":
                model["display"] = HANDHELD_DISPLAY
        write(ASSETS / "models/item" / f"{item_id}.json", model)

    for lang, index in (("de_de", 1), ("en_us", 2)):
        path = ASSETS / "lang" / f"{lang}.json"
        existing = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
        # Nur die Item-Namen werden verwaltet; Tasten- und Oberflächentexte bleiben unangetastet.
        for item_id, names in ITEMS.items():
            existing[f"item.oneshotonekill.{item_id}"] = names[index]
        write(path, dict(sorted(existing.items())))

    print(f"{len(ITEMS)} Items: je eine Item-Definition und ein Modell, dazu 2 Sprachdateien.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
