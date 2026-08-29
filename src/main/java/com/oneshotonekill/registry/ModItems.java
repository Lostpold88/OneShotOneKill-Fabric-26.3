package com.oneshotonekill.registry;

import com.oneshotonekill.OneShotOneKill;
import static com.oneshotonekill.item.types.WeaponItems.*;
import static com.oneshotonekill.item.types.AbilityItems.*;
import static com.oneshotonekill.item.types.DeployableItems.*;
import java.util.function.Function;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

/**
 * Registriert die Items der Mod unmittelbar in {@link BuiltInRegistries#ITEM}.
 *
 * Fabric kennt keinen aufgeschobenen Registrierungslauf: Die Einträge entstehen beim Laden
 * dieser Klasse, und {@link #register()} zwingt genau diesen Zeitpunkt in den
 * {@code ModInitializer}. Weil die Felder danach fertige {@link Item}-Objekte sind, entfällt
 * der Umweg über einen Halter.
 *
 * Jedes Spezial-Item ist ein eigenes Item mit eigener Klasse und Textur – bewusst kein
 * umbenanntes Vanilla-Item.
 */
public final class ModItems {

   public static final Item MINIGUN = special("minigun", MinigunItem::new);
   public static final Item AIRSTRIKE = special("airstrike", AirstrikeItem::new);
   /** Große fallende Nuklearbombe des Luftangriffs – reiner Modellträger. */
   public static final Item AIRSTRIKE_NUKE = special("airstrike_nuke", Item::new);
   public static final Item RADAR_PULSE = special("radar_pulse", RadarPulseItem::new);
   public static final Item EXPLOSIVE_SHOT = special("explosive_shot", ExplosiveShotItem::new);
   public static final Item REFLECTOR_SHIELD = special("reflector_shield", ReflectorShieldItem::new);
   /** Besitzerexklusive Energiekugel des Reflektor-Schilds – reiner Modellträger. */
   public static final Item REFLECTOR_BARRIER = special("reflector_barrier", Item::new);
   public static final Item SMOKE_BOMB = special("smoke_bomb", SmokeBombItem::new);
   public static final Item FROST_TRAP = special("frost_trap", FrostTrapItem::new);
   /**
    * Der Eiskristall der Frost-Falle – Modellträger für Einschlag und Eiskäfig.
    *
    * Er ist wie die Lanze der Railgun gebaut: Spitze nach -Z, genau einen Block lang. Damit
    * passt dieselbe Drehung, und die Skalierung in Z ist unmittelbar seine Länge.
    */
   public static final Item FROST_SHARD = special("frost_shard", Item::new);
   public static final Item TELEPORT_GRENADE = special("teleport_grenade", TeleportGrenadeItem::new);
   public static final Item INVISIBILITY_CLOAK = special("invisibility_cloak", InvisibilityCloakItem::new);
   public static final Item ARROW_MAGNET = special("arrow_magnet", ArrowMagnetItem::new);
   public static final Item CHAIN_LIGHTNING = special("chain_lightning", ChainLightningItem::new);
   /** Sichtbarer Blitz des Kettenblitz-Schusses – reiner Modellträger. */
   public static final Item CHAIN_LIGHTNING_BOLT = special("chain_lightning_bolt", Item::new);
   public static final Item STEALTH_BOMBER = special("stealth_bomber", StealthBomberItem::new);
   public static final Item C4 = special("c4", C4Item::new);
   /**
    * Die klebende Ladung – reiner Modelltraeger.
    *
    * Der Gegenstand {@link #C4} zeigt seit dem Wegfall des eigenstaendigen Fernzuenders den
    * Zuendkasten, denn das ist es, was man in der Hand haelt. An der Wand klebt aber eine
    * Sprengladung, und die braucht ihr eigenes Modell. Beide tragen dieselbe einfaerbbare
    * Leuchtdiode.
    */
   public static final Item C4_CHARGE = special("c4_charge", Item::new);
   /**
    * Der Bomber und seine Ladung – reine Modelltraeger fuer das Matchende.
    *
    * Beide sind laengs der Y-Achse gebaut, mit der Nase nach unten; siehe
    * {@code tools/generate_nuke_3d.py}, warum das die drehsichere Bauweise ist.
    */
   public static final Item NUKE_BOMBER = special("nuke_bomber", Item::new);
   public static final Item NUKE_BOMB = special("nuke_bomb", Item::new);
   public static final Item RAILGUN = special("railgun", RailgunItem::new);
   /**
    * Die Lanze, die der Railgun-Schuss in die Luft zieht – nur ein Modellträger.
    *
    * Sie ist ganz einfärbbar: der Strahl kühlt von Weißglut nach Blau ab, während er verglüht.
    */
   public static final Item RAILGUN_BOLT = special("railgun_bolt", Item::new);
   public static final Item SINGULARITY = special("singularity", SingularityItem::new);
   public static final Item SLOW_MOTION = special("slow_motion", SlowMotionItem::new);
   public static final Item GLIDER = special("glider", GliderItem::new);
   public static final Item GRAPPLING_HOOK = special("grappling_hook", GrapplingHookItem::new);
   /** Ausgefahrener Saughaken des Grapplers – reiner Modellträger. */
   public static final Item GRAPPLING_HOOK_HEAD = special("grappling_hook_head", Item::new);
   /** Generierter Seilabschnitt als Modellressource; im Flug zeichnet der Client das Kabel bildgenau. */
   public static final Item GRAPPLING_HOOK_ROPE = special("grappling_hook_rope", Item::new);
   public static final Item SENTRY_TURRET = special("sentry_turret", SentryTurretItem::new);
   /** Stillstehender Unterbau des platzierten Turms – reiner Modellträger. */
   public static final Item SENTRY_BASE = special("sentry_base", Item::new);
   /**
    * Der schwenkbare Kopf des Geschützturms – wieder nur ein Modellträger.
    *
    * Unterbau und Kopf sind zwei Displays, damit der Turm beim Zielen nicht mit den Beinen
    * mitdreht. Sein Sensorauge ist über {@code minecraft:dye} einfärbbar und zeigt damit an,
    * ob der Turm sucht, erfasst hat oder feuert.
    */
   public static final Item SENTRY_HEAD = special("sentry_head", Item::new);
   /** Reine Anzeigehilfe für die schwebende Box am Boden – kein Spezial-Item. */
   public static final Item ITEM_BOX = special("item_box", Item::new);
   /**
    * Nur als Modellträger für die fallenden Ladungen des Bombers registriert.
    *
    * Eine {@code Display.ItemDisplay} braucht einen Gegenstand, dessen Modell sie zeigen kann.
    * Weil dieses Item in {@code SpecialItem} nicht auftaucht, kann es weder aus einer Item-Box
    * fallen noch als Killstreak-Belohnung vergeben werden.
    */
   public static final Item BOMBER_BOMB = special("bomber_bomb", Item::new);
   /**
    * Die drei Bausteine des Atompilzes – Wolkenballen, Druckwellenring und Erdbrocken.
    *
    * Auch sie sind reine Modellträger und tauchen in {@code SpecialItem} nicht auf. Ihre Farbe
    * steht nicht in der Textur: die Item-Definition färbt über {@code minecraft:dye} ein, das
    * Modell ist fast weiß, und {@code MushroomCloud} setzt den Farbwert je Teil und Tick. Ein
    * einziges Modell trägt so den weißglühenden Feuerball ebenso wie den schwarzen Rauch.
    */
   public static final Item BLAST_PUFF = special("blast_puff", Item::new);
   public static final Item BLAST_RING = special("blast_ring", Item::new);
   public static final Item BLAST_SHARD = special("blast_shard", Item::new);

   private ModItems() {
   }

   /** Löst das Laden dieser Klasse und damit die Registrierung aus. */
   public static void register() {
   }

   private static Item special(String name, Function<Item.Properties, ? extends Item> factory) {
      ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, OneShotOneKill.INSTANCE.id(name));
      Item item = factory.apply(new Item.Properties().setId(key).stacksTo(1).rarity(Rarity.EPIC));
      return Registry.register(BuiltInRegistries.ITEM, key, item);
   }
}
