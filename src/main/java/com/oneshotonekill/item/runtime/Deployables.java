package com.oneshotonekill.item.runtime;

import com.oneshotonekill.shared.Hologram;
import com.oneshotonekill.shared.Blast;
import com.oneshotonekill.shared.Feedback;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.arena.ArenaWorlds;
import com.oneshotonekill.event.KillFeed;
import com.oneshotonekill.event.CombatEvents.DamageListener;
import com.oneshotonekill.registry.ModDataComponents;
import com.oneshotonekill.registry.ModItems;
import com.oneshotonekill.network.OsokPayloads.DeployableMarkersPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Alles, was in der Arena abgestellt wird: Frost-Falle, C4-Ladung und Geschützturm.
 *
 * Keines davon verändert die Karte. Die Geräte sind reine Serverobjekte, die sich selbst mit
 * Partikeln zeichnen – so bleibt die Arena garantiert unberührt und muss nach dem Match nicht
 * zurückgesetzt werden.
 */
public final class Deployables {
   public static final Deployables INSTANCE = new Deployables();

   private static final int TRAP_FREEZE_TICKS = 140;
   /**
    * Ab dieser Abweichung vom Ankerpunkt wird ein Eingefrorener zurückgesetzt.
    *
    * Derselbe Wert wie im Countdown des Match-Starts: groß genug, dass die üblichen
    * Zehntelblöcke aus Restschwung und Netzwerklauf keine Korrektur auslösen, klein genug,
    * dass niemand aus dem Käfig herausspaziert.
    */
   private static final double FREEZE_DRIFT_TOLERANCE = 0.35;
   /**
    * Höhe der Falle über dem Boden – steht so in der Ausgabe von
    * {@code tools/generate_frost_trap_3d.py} und muss mit ihr übereinstimmen.
    */
   private static final double TRAP_LIFT = 0.0781;
   /**
    * Sichtweite der Falle in Vielfachen von 64 Blöcken.
    *
    * Bewusst kurz. Eine Haftmine soll man finden können, wenn man hinsieht, und nicht schon
    * beim Betreten des Raums bemerken.
    */
   private static final float TRAP_VIEW_RANGE = 0.6F;
   /** So lange bleibt die ausgelöste und nun für alle sichtbare Platte noch liegen. */
   private static final int TRAP_REVEAL_TICKS = 16;
   /** Matter Kern der Falle; bewusst dunkel und während der gesamten Liegezeit unverändert. */
   private static final int TRAP_CORE = 0x1B3A46;

   /** Der Eiskäfig um einen Eingefrorenen. */
   private static final int CAGE_SHARDS = 7;
   private static final int CAGE_GROW_TICKS = 6;
   private static final int CAGE_SHATTER_TICKS = 8;
   private static final double CAGE_RADIUS = 0.6;
   private static final double CAGE_TILT = 0.55;
   private static final double CAGE_LENGTH = 1.8;
   private static final double CAGE_WIDTH = 0.34;
   private static final double CAGE_RING_DIAMETER = 2.4;
   private static final double CAGE_RING_BAND = 0.18;
   /**
    * Breite des Kristallmodells bei Skalierung 1, in Blöcken; die Länge beträgt genau einen
    * Block. Beides steht in der Ausgabe von {@code tools/generate_frost_trap_3d.py}.
    */
   private static final double SHARD_WIDTH_UNIT = 0.4606;
   /** Höhe des Ringmodells bei Skalierung 1 – aus {@code tools/generate_blast_3d.py}. */
   private static final double RING_BAND_UNIT = 0.0978;
   private static final int ICE_BRIGHT = 0xEAF8FF;
   private static final int ICE_DEEP = 0x6FB6D8;
   /**
    * Kraterradius der C4 – und zugleich ihr tödlicher Radius.
    *
    * Beide aus derselben Zahl, mit Absicht: tödlich ist genau das Loch, das man hinterher
    * sieht, nichts darüber hinaus. Der Luftangriff hält es genauso.
    */
   private static final int C4_CRATER_RADIUS = 7;
   private static final double C4_BLAST_RADIUS = C4_CRATER_RADIUS;
   /** Ticks, bis der Krater wieder zuwächst. Kürzer als beim Luftangriff – C4 fällt öfter. */
   private static final int C4_RESTORE_DELAY_TICKS = 20 * 6;
   /**
    * Abstand von der Klebefläche zur Modellmitte.
    *
    * Steht so in der Ausgabe von {@code tools/generate_c4_3d.py} und muss mit ihr
    * übereinstimmen. Eine {@code Display} zeichnet ihr Modell um die eigene Position zentriert;
    * ohne diesen Versatz steckte die halbe Ladung in der Wand.
    */
   private static final double C4_LIFT = 0.2175;
   /** Reichweite, in der eine eigene Ladung wieder abgenommen werden kann. */
   private static final double C4_REACH = 4.5;
   /**
    * So weit darf der Blick an der Ladung vorbeigehen und sie trotzdem meinen.
    *
    * Knapp bemessen, und das mit Absicht: Seit der Zünder weg ist, greift das Abnehmen bei
    * jedem Rechtsklick – auch mit dem Bogen in der Zweithand. Eine großzügige Toleranz nähme
    * einem in der Nähe der eigenen Ladung sonst jeden Schuss ab.
    */
   private static final double C4_AIM_TOLERANCE = 0.42;
   /** Abstand der Blinkzeichen – kürzer, sobald der Besitzer eine weitere Ladung in der Hand hält. */
   private static final int C4_BLINK_IDLE = 26;
   private static final int C4_BLINK_ARMED = 8;
   private static final int C4_FLASH_TICKS = 5;
   /**
    * Glimmen zwischen den Blitzen und der Blitz selbst – beides rot, nichts dazwischen.
    *
    * Der Blitz geht über reines Rot hinaus: die Einfärbung wirkt als Faktor auf die fast weiße
    * Textur, und bei 255 im roten Kanal ist dort Schluss. Heller wird es nur, indem auch Grün
    * und Blau ein Stück mitgehen – so, wie eine wirklich helle Leuchtdiode in der Mitte
    * ausbleicht, ohne ihre Farbe zu verlieren.
    */
   private static final int C4_LED_DARK = 0x8C1409;
   private static final int C4_LED_FLASH = 0xFF4A34;
   private static final int TURRET_DURATION_TICKS = 400;
   private static final int TURRET_FIRE_INTERVAL_TICKS = 8;
   private static final double TURRET_RANGE = 14.0;
   private static final int TURRET_HITS_TO_KILL = 3;
   private static final int TURRET_HIT_MEMORY_TICKS = 160;
   private static final double TURRET_KNOCKBACK = 0.25;
   /** Aufbau: so lange fährt der Turm aus und schießt noch nicht. */
   private static final int TURRET_DEPLOY_TICKS = 24;
   /** Abschaltung: so lange sackt der Kopf ab, bevor der Turm zerspringt. */
   private static final int TURRET_DEATH_TICKS = 18;
   /** Treffer, die der Turm aushält, bevor er ausfällt. */
   private static final int TURRET_INTEGRITY = 4;
   /**
    * Höhe der beiden Displays über der Standfläche und Lage der Mündungen.
    *
    * Stehen so in der Ausgabe von {@code tools/generate_sentry_turret_3d.py} und müssen mit
    * ihr übereinstimmen: der Kopf schwenkt um seine Lagerung, und die Mündungsfeuer sollen
    * dort sitzen, wo die Läufe aufhören, nicht irgendwo davor.
    */
   private static final double TURRET_BASE_LIFT = 0.3063;
   private static final double TURRET_HEAD_LIFT = 0.8375;
   private static final double MUZZLE_FORWARD = 0.4625;
   private static final double MUZZLE_SIDE = 0.1187;
   private static final double MUZZLE_UP = 0.0250;
   /**
    * Schwenkgeschwindigkeit des Kopfes im Bogenmaß je Tick.
    *
    * Bewusst begrenzt. Vorher stand der Kopf im selben Tick auf jedem Ziel, egal woher es kam –
    * damit war der Turm von keiner Seite zu umgehen. Rund neun Grad je Tick sind schnell genug,
    * um niemanden entkommen zu lassen, und langsam genug, dass eine Flanke sich lohnt.
    */
   private static final float TURRET_TURN_PER_TICK = 0.16F;
   /** So genau muss der Kopf stehen, bevor der Turm feuert. */
   private static final float TURRET_AIM_TOLERANCE = 0.22F;
   /** Suchlauf ohne Ziel: Ausschlag und Geschwindigkeit des Schwenks. */
   private static final float TURRET_SCAN_ARC = 1.15F;
   private static final float TURRET_SCAN_SPEED = 0.035F;
   /** Farbe des Sensorauges je Lage. */
   /**
    * Das Sensorauge blinkt, statt eine Farbe zu halten.
    *
    * Eine feste Farbe sagt zwar, in welcher Lage der Turm ist, aber sie fällt nicht auf. Erst
    * der Takt macht daraus eine Warnung: langsames Atmen beim Suchen, ein Doppelschlag beim
    * Erfassen, hektisches Stroboskop beim Feuern.
    */
   private static final int LENS_SCAN = 0x54E070;
   private static final int LENS_SCAN_DIM = 0x0E3A1C;
   private static final int LENS_LOCK = 0xFFC040;
   private static final int LENS_LOCK_DIM = 0x4A2A00;
   private static final int LENS_FIRE = 0xFF4020;
   private static final int LENS_FIRE_HOT = 0xFFF0E0;
   private static final int LENS_DEAD = 0x2A2A2A;
   private static final int LENS_SCAN_CYCLE = 34;
   private static final int LENS_LOCK_CYCLE = 12;
   private static final int LENS_FIRE_CYCLE = 4;

   private final List<FrostTrap> traps = new ArrayList<>();
   private final List<Charge> charges = new ArrayList<>();
   private final List<Turret> turrets = new ArrayList<>();
   private final Map<UUID, Frozen> frozen = new HashMap<>();
   private final Map<UUID, HitAccount> turretHits = new HashMap<>();

   private Deployables() {
   }

   // -- Platzieren ----------------------------------------------------------

   /**
    * Legt eine Frost-Falle flach auf den Boden.
    *
    * Sie soll übersehen werden können. Vorher stand eine hochkant gedrehte Bildtafel mit
    * Leuchtrand da, die durch Wände zu sehen war, und zog jeden Tick acht Schneeflocken im
    * Kreis – als Falle damit wertlos. Jetzt liegt dort eine flache dunkle Scheibe ohne
    * Leuchtrand, mit kurzer Sichtweite und natürlicher Umgebungsbeleuchtung.
    */
   public boolean placeFrostTrap(ServerLevel level, ServerPlayer owner, BlockPos pos) {
      Vec3 at = Vec3.atBottomCenterOf(pos).add(0.0, TRAP_LIFT, 0.0);
      float yaw = level.getRandom().nextFloat() * (float) (Math.PI * 2.0);
      FrostTrap trap = new FrostTrap(at, pos, owner.getUUID(), yaw);
      trap.ownerCanTrigger = !standsOnTrap(owner, trap);
      trap.display = Hologram.spawnOwnerVisible(level, at, trapStack(), TRAP_VIEW_RANGE, owner.getUUID());
      if (trap.display == null) {
         return false;
      }
      // Zufällig gedreht: was immer gleich ausgerichtet liegt, fällt genau dadurch auf.
      Hologram.setPose(trap.display, new Vector3f(),
         new Quaternionf().rotationY(trap.yaw),
         new Vector3f(1.0F, 1.0F, 1.0F), 0);
      traps.add(trap);
      StatusAbilities.Broadcaster.INSTANCE.refresh(owner);

      level.playSound(null, at.x, at.y, at.z, SoundEvents.POWDER_SNOW_PLACE, SoundSource.PLAYERS, 0.4F, 1.1F);
      Feedback.actionBar(owner, "§b❄ FROST-FALLE SCHARF §7· sie leuchtet nicht, merk dir die Stelle");
      return true;
   }

   private static ItemStack trapStack() {
      ItemStack stack = new ItemStack(ModItems.FROST_TRAP);
      stack.set(DataComponents.DYED_COLOR, new DyedItemColor(TRAP_CORE));
      return stack;
   }

   private static ItemStack shardStack(int colour) {
      ItemStack stack = new ItemStack(ModItems.FROST_SHARD);
      stack.set(DataComponents.DYED_COLOR, new DyedItemColor(colour));
      return stack;
   }

   private static ItemStack iceRingStack(int colour) {
      ItemStack stack = new ItemStack(ModItems.BLAST_RING);
      stack.set(DataComponents.DYED_COLOR, new DyedItemColor(colour));
      return stack;
   }

   /**
    * Klebt eine Ladung auf die angeklickte Fläche.
    *
    * Anders als Falle und Turm steht sie nicht auf dem Boden – sie haftet, an Wänden und unter
    * Decken genauso. Dafür wird die Oberseite des Modells auf die Flächennormale gedreht und die
    * Entity um die halbe Modellhöhe nach außen gerückt.
    *
    * Einen Leuchtrand bekommt sie bewusst nicht. Die anderen Geräte tragen einen, der durch
    * Wände zu sehen ist; bei einer versteckten Ladung nähme das genau den Sinn.
    */
   public boolean placeC4(ServerLevel level, ServerPlayer owner, Vec3 surface, Direction face) {
      Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
      Vec3 at = surface.add(normal.scale(C4_LIFT));
      Charge charge = new Charge(at, owner.getUUID(), face, orientation(face, owner.getYRot()));
      charge.display = Hologram.spawnEffect(level, at, ledStack(C4_LED_DARK), 4.0F);
      if (charge.display == null) {
         return false;
      }
      Hologram.setPose(charge.display, new Vector3f(), charge.facing, new Vector3f(1.0F, 1.0F, 1.0F), 0);
      charge.led = C4_LED_DARK;
      charges.add(charge);
      StatusAbilities.Broadcaster.INSTANCE.refresh(owner);

      level.playSound(null, at.x, at.y, at.z, SoundEvents.NETHERITE_BLOCK_PLACE, SoundSource.PLAYERS, 0.9F, 1.3F);
      level.playSound(null, at.x, at.y, at.z, SoundEvents.COMPARATOR_CLICK, SoundSource.PLAYERS, 0.8F, 1.6F);
      level.sendParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y, at.z, 10, 0.15, 0.15, 0.15, 0.05);
      Component msg = Component.literal("§6💥 C4 HAFTET — " + ownedCharges(owner.getUUID()) + " scharf §7· Zünder oder [")
         .append(Component.keybind("key.oneshotonekill.detonate_c4").withStyle(ChatFormatting.YELLOW))
         .append(Component.literal("§7] zündet · Klick auf Ladung nimmt sie ab"));
      Feedback.actionBar(owner, msg);
      return true;
   }

   /**
    * Der Krater, den diese Ladung reißt – abhängig davon, worauf sie klebt.
    *
    * Die Kugel wird gegen die Fläche versetzt, an der die Ladung hängt: auf dem Boden nach
    * unten, unter einer Decke nach oben, an einer Wand gar nicht. Ohne diese Fallunterscheidung
    * spränge eine Ladung an der Wand den Boden mehrere Blöcke unter sich weg und ließe die Wand
    * selbst fast stehen.
    */
   private static Blast.Crater craterOf(Charge charge) {
      double depth = charge.face.getStepY() * C4_CRATER_RADIUS * Blast.CRATER_DEPTH_SHARE;
      return new Blast.Crater(C4_CRATER_RADIUS, depth, C4_RESTORE_DELAY_TICKS);
   }

   /**
    * Ausrichtung der Ladung auf einer Fläche.
    *
    * Die Modelloberseite zeigt von der Fläche weg. Auf Boden und Decke dreht sie sich zusätzlich
    * so, dass der Zündkasten den ansieht, der sie gesetzt hat. Der halbe Umlauf im Winkel ist
    * kein Versehen: {@code DisplayRenderer.ItemDisplayRenderer#submitInner} legt vor dem Zeichnen
    * ein {@code Axis.YP.rotation(PI)} auf den Stapel, das Modell steht in der Welt also um
    * 180 Grad verdreht zu seiner Geometrie.
    *
    * An einer Wand bleibt der Rollwinkel dem überlassen, was {@code rotationTo} liefert. Ein
    * eigener Wert brächte dort nichts: die zweite Diode sitzt auf dem Deckel und zeigt damit
    * ohnehin von der Wand weg.
    */
   private static Quaternionf orientation(Direction face, float placerYaw) {
      Quaternionf rotation = new Quaternionf().rotationTo(0.0F, 1.0F, 0.0F,
         face.getStepX(), face.getStepY(), face.getStepZ());
      if (face.getAxis().isVertical()) {
         rotation.rotateY((float) Math.toRadians(180.0F - placerYaw));
      }
      return rotation;
   }

   /** Eine Ladung mit der angegebenen Farbe für die Leuchtdiode. */
   private static ItemStack ledStack(int colour) {
      ItemStack stack = new ItemStack(ModItems.C4_CHARGE);
      stack.set(DataComponents.DYED_COLOR, new DyedItemColor(colour));
      return stack;
   }

   /**
    * Stellt einen Geschützturm auf.
    *
    * Er besteht aus zwei Displays: einem Unterbau, der steht, und einem Kopf, der schwenkt.
    * Vorher war es eines, und dann drehte sich das ganze Gerät samt Beinen zum Ziel – es sah
    * aus, als rutschte es über den Boden.
    */
   public boolean placeSentryTurret(ServerLevel level, ServerPlayer owner, BlockPos pos) {
      Vec3 at = Vec3.atBottomCenterOf(pos);
      Turret turret = new Turret(at, owner.getUUID(), TURRET_DURATION_TICKS);
      // Er blickt zunächst dorthin, wohin der Aufsteller schaut – die Richtung, aus der Ärger
      // erwartet wird. Der halbe Umlauf gleicht die Drehung des Renderers aus.
      turret.home = (float) Math.toRadians(180.0F - owner.getYRot());
      turret.yaw = turret.home;

      turret.baseDisplay = Hologram.spawnEffect(level, at.add(0.0, TURRET_BASE_LIFT, 0.0),
         new ItemStack(ModItems.SENTRY_BASE), 4.0F);
      turret.headDisplay = Hologram.spawnEffect(level, at.add(0.0, TURRET_HEAD_LIFT, 0.0),
         lensStack(LENS_SCAN), 4.0F);
      if (turret.baseDisplay == null || turret.headDisplay == null) {
         Hologram.remove(turret.baseDisplay);
         Hologram.remove(turret.headDisplay);
         return false;
      }
      turret.lens = LENS_SCAN;
      turrets.add(turret);
      StatusAbilities.Broadcaster.INSTANCE.refresh(owner);

      level.playSound(null, at.x, at.y, at.z, SoundEvents.IRON_DOOR_OPEN, SoundSource.PLAYERS, 1.0F, 0.7F);
      level.playSound(null, at.x, at.y, at.z, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.8F, 1.5F);
      Feedback.actionBar(owner, "§6🤖 GESCHÜTZTURM FÄHRT AUS — 20 s");
      return true;
   }

   /** Der Kopf mit der angegebenen Farbe für das Sensorauge. */
   private static ItemStack lensStack(int colour) {
      ItemStack stack = new ItemStack(ModItems.SENTRY_HEAD);
      stack.set(DataComponents.DYED_COLOR, new DyedItemColor(colour));
      return stack;
   }

   /** Zündet alle Ladungen des Spielers gleichzeitig. */
   public boolean detonateAll(ServerPlayer owner) {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      ServerLevel level = worlds == null ? null : worlds.getActiveLevel();
      if (level == null) {
         return false;
      }

      List<Charge> own = charges.stream().filter(charge -> charge.owner.equals(owner.getUUID())).toList();
      if (own.isEmpty()) {
         Feedback.actionBar(owner, "§7💥 Keine Ladung scharf");
         return false;
      }

      charges.removeAll(own);
      for (Charge charge : own) {
         Hologram.remove(charge.display);
         Blast.detonate(level, owner, charge.position, C4_BLAST_RADIUS, KillFeed.Cause.C4, craterOf(charge));
      }
      // Der Zünder wird beim Zünden verbraucht
      consumeArmedDetonators(owner);
      StatusAbilities.Broadcaster.INSTANCE.refresh(owner);
      Feedback.actionBar(owner, "§6💥 " + own.size() + " LADUNG(EN) GEZÜNDET");
      return true;
   }

   // -- Tick ----------------------------------------------------------------

   public void tick(MinecraftServer server) {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      ServerLevel level = worlds == null ? null : worlds.getActiveLevel();
      if (level == null) {
         return;
      }

      tickTraps(server, level);
      tickCharges(server, level);
      tickTurrets(server, level);
      tickFrozen(server);
      int now = server.getTickCount();
      turretHits.entrySet().removeIf(entry -> now - entry.getValue().lastHitTick > TURRET_HIT_MEMORY_TICKS);
   }

   private void tickTraps(MinecraftServer server, ServerLevel level) {
      List<FrostTrap> snapshot = new ArrayList<>(traps);
      for (FrostTrap trap : snapshot) {
         if (!traps.contains(trap)) {
            continue;
         }
         if (trap.revealTicks > 0) {
            if (--trap.revealTicks <= 0) {
               Hologram.remove(trap.display);
               traps.remove(trap);
               ServerPlayer owner = server.getPlayerList().getPlayer(trap.owner);
               if (owner != null) {
                  StatusAbilities.Broadcaster.INSTANCE.refresh(owner);
               }
            }
            continue;
         }

         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() != level || frozen.containsKey(player.getUUID())) {
               continue;
            }
            if (player.getUUID().equals(trap.owner) && !trap.ownerCanTrigger) {
               // Wird die Platte direkt unter den eigenen Füßen gesetzt, geht sie nicht im
               // nächsten Tick los: erst verlassen, dann wirklich wieder hineintreten.
               if (!standsOnTrap(player, trap)) {
                  trap.ownerCanTrigger = true;
               }
               continue;
            }
            if (!standsOnTrap(player, trap)) {
               continue;
            }
            revealTrap(level, trap);
            freeze(level, player, trap.position);
            break;
         }
      }
   }

   /** Nur die Blockposition der Füße zählt; eine überlappende Hitbox vom Nachbarblock nicht. */
   private static boolean standsOnTrap(ServerPlayer player, FrostTrap trap) {
      return player.blockPosition().equals(trap.triggerBlock);
   }

   /** Ersetzt das besitzerexklusive Display durch eine gewöhnliche, für alle verfolgte Entity. */
   private void revealTrap(ServerLevel level, FrostTrap trap) {
      Hologram.remove(trap.display);
      trap.display = Hologram.spawnNaturallyLit(level, trap.position, trapStack(), TRAP_VIEW_RANGE);
      if (trap.display != null) {
         Hologram.setPose(trap.display, new Vector3f(), new Quaternionf().rotationY(trap.yaw),
            new Vector3f(1.0F, 1.0F, 1.0F), 0);
      }
      trap.revealTicks = TRAP_REVEAL_TICKS;
   }

   /**
    * Friert einen Spieler ein und baut den Eiskäfig um ihn.
    *
    * Der Käfig ist der eigentliche Effekt: sieben Kristalle fahren im Kreis aus dem Boden und
    * neigen sich über ihn, dazu ein Ring aus Rauhreif zu seinen Füßen. Eine Wolke
    * Schneeflocken täte es nicht – die ist nach einer Sekunde weg, und der Getroffene steht
    * die restlichen sechs unbegründet still.
    */
   private void freeze(ServerLevel level, ServerPlayer player, Vec3 trapAt) {
      Vec3 anchor = player.position();
      Frozen state = new Frozen(anchor, TRAP_FREEZE_TICKS);

      state.ring = Hologram.spawnEffect(level, anchor.add(0.0, 0.08, 0.0), iceRingStack(ICE_BRIGHT), 2.0F);
      if (state.ring != null) {
         Hologram.setPose(state.ring, new Vector3f(), new Quaternionf(),
            new Vector3f(Hologram.HIDDEN_SCALE, Hologram.HIDDEN_SCALE, Hologram.HIDDEN_SCALE), 0);
      }

      for (int index = 0; index < CAGE_SHARDS; index++) {
         double angle = index * Math.PI * 2.0 / CAGE_SHARDS + level.getRandom().nextDouble() * 0.2;
         // Nach außen geneigt statt senkrecht: senkrechte Stangen sehen aus wie ein Zaun,
         // geneigte wie etwas, das den Getroffenen einschließt.
         Vec3 direction = new Vec3(Math.cos(angle) * CAGE_TILT, 1.0, Math.sin(angle) * CAGE_TILT).normalize();
         Display.ItemDisplay display = Hologram.spawnEffect(level,
            anchor.add(Math.cos(angle) * CAGE_RADIUS, 0.0, Math.sin(angle) * CAGE_RADIUS),
            shardStack(ICE_BRIGHT), 2.0F);
         if (display == null) {
            continue;
         }
         Quaternionf aim = new Quaternionf().rotationTo(0.0F, 0.0F, -1.0F,
            (float) direction.x, (float) direction.y, (float) direction.z);
         Hologram.setPose(display, new Vector3f(), aim,
            new Vector3f(Hologram.HIDDEN_SCALE, Hologram.HIDDEN_SCALE, Hologram.HIDDEN_SCALE), 0);
         state.shards.add(display);
         state.shardAims.add(aim);
         state.shardDirections.add(direction);
      }

      frozen.put(player.getUUID(), state);
      // Angefangene Dauerbenutzungen (Bogen, Minigun, Railgun) sofort und ohne Schuss beenden.
      player.stopUsingItem();
      // Wer in die Falle läuft, läuft schnell. Der Restschwung muss einmal ausdrücklich zum
      // Client, sonst rutscht der noch ein Stück weiter und wird dafür zurückgeholt.
      player.setDeltaMovement(Vec3.ZERO);
      player.hurtMarked = true;
      player.fallDistance = 0.0;
      StatusAbilities.Broadcaster.INSTANCE.refresh(player);

      level.playSound(null, anchor.x, anchor.y, anchor.z, SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.1F, 0.55F);
      level.playSound(null, anchor.x, anchor.y, anchor.z, SoundEvents.POWDER_SNOW_BREAK, SoundSource.PLAYERS, 1.3F, 0.6F);
      level.playSound(null, anchor.x, anchor.y, anchor.z, SoundEvents.PLAYER_HURT_FREEZE, SoundSource.PLAYERS, 0.9F, 1.0F);
      level.sendParticles(ParticleTypes.SNOWFLAKE, anchor.x, anchor.y + 0.9, anchor.z, 90, 0.6, 0.9, 0.6, 0.14);
      level.sendParticles(ParticleTypes.ITEM_SNOWBALL, trapAt.x, trapAt.y + 0.2, trapAt.z, 40, 0.35, 0.1, 0.35, 0.22);
      Feedback.actionBar(player, "§b❄ EINGEFROREN");
   }

   /**
    * Setzt den Käfig für diesen Tick: ausfahren, stehen, zerspringen.
    *
    * Die Kristalle wachsen in der Länge, nicht in der Größe – sie sollen aus dem Boden fahren
    * und nicht aufgeblasen wirken. Am Ende schrumpfen sie schnell zusammen, dazu der Bruchton.
    */
   private void poseCage(Frozen state) {
      double grow = Math.clamp(state.age / (double) CAGE_GROW_TICKS, 0.0, 1.0);
      double shatter = state.ticksLeft <= CAGE_SHATTER_TICKS
         ? state.ticksLeft / (double) CAGE_SHATTER_TICKS
         : 1.0;
      double share = grow * shatter;
      int colour = blendIce(state.age);

      for (int index = 0; index < state.shards.size(); index++) {
         Vec3 direction = state.shardDirections.get(index);
         double length = CAGE_LENGTH * share;
         float width = (float) Math.max(Hologram.HIDDEN_SCALE, CAGE_WIDTH / SHARD_WIDTH_UNIT * shatter);
         // Der Kristall ist auf seiner Mitte eingemittet, also mit der halben Länge nachrücken.
         Vector3f offset = new Vector3f(
            (float) (direction.x * length * 0.5), (float) (direction.y * length * 0.5), (float) (direction.z * length * 0.5));
         Hologram.setPose(state.shards.get(index), offset, state.shardAims.get(index),
            new Vector3f(width, width, (float) Math.max(Hologram.HIDDEN_SCALE, length)), 2);
      }

      if (state.ring != null) {
         float across = (float) (CAGE_RING_DIAMETER * share);
         float band = (float) (CAGE_RING_BAND / RING_BAND_UNIT * shatter);
         Hologram.setPose(state.ring, new Vector3f(), new Quaternionf(),
            new Vector3f(Math.max(Hologram.HIDDEN_SCALE, across), Math.max(Hologram.HIDDEN_SCALE, band),
               Math.max(Hologram.HIDDEN_SCALE, across)), 2);
      }

      if (colour != state.colour) {
         state.colour = colour;
         for (Display.ItemDisplay shard : state.shards) {
            shard.getSlot(0).set(shardStack(colour));
         }
         if (state.ring != null) {
            state.ring.getSlot(0).set(iceRingStack(colour));
         }
      }
   }

   /** Das Eis flimmert leicht, statt gleichmäßig zu stehen. */
   private static int blendIce(int age) {
      double wave = 0.5 + 0.5 * Math.sin(age * 0.22);
      int result = 0;
      for (int shift = 0; shift <= 16; shift += 8) {
         int deep = (ICE_DEEP >> shift) & 0xFF;
         int bright = (ICE_BRIGHT >> shift) & 0xFF;
         result |= (deep + (int) Math.round((bright - deep) * wave)) << shift;
      }
      return result & 0xF8F8F8;
   }

   /**
    * Hält eingefrorene Spieler wirklich fest.
    *
    * Eine reine Verlangsamung reicht nicht – ein bereits begonnener Sprung trüge den Getroffenen
    * weiterhin mehrere Blöcke weit. Deshalb wird die Position zurückgesetzt und die Geschwindigkeit
    * genullt. Umsehen bleibt erlaubt, sonst fühlt es sich wie ein Verbindungsabbruch an.
    */
   /**
    * Hält einen Eingefrorenen auf seinem Ankerpunkt fest.
    *
    * <p>Entscheidend ist, <em>wann</em> zurückgesetzt wird. Vorher lief hier in jedem Tick ein
    * {@code teleportTo}, und weil das immer ein Positionspaket schickt, waren das über die sieben
    * Sekunden rund einhundertvierzig erzwungene Korrekturen — der Grund für das Ruckeln beim
    * Gegenlaufen. Die Bewegung wird stattdessen schon auf dem Client unterdrückt
    * ({@code KeyboardInputMixin}); hier bleibt nur die Auffanglinie, und die greift erst, wenn
    * der Spieler wirklich weggerutscht ist.</p>
    *
    * <p>Genau so hält es der Countdown beim Match-Start in {@code MatchManager.Countdown}.
    * {@code hurtMarked} setzt dabei nur die Korrektur, nicht jeder Tick: Es schickt die
    * genullte Geschwindigkeit zum Client und räumt dessen Restschwung mit aus.</p>
    */
   private static void holdAtAnchor(ServerPlayer player, Vec3 anchor) {
      player.setDeltaMovement(Vec3.ZERO);
      player.fallDistance = 0.0;
      if (player.position().distanceToSqr(anchor) > FREEZE_DRIFT_TOLERANCE * FREEZE_DRIFT_TOLERANCE) {
         player.hurtMarked = true;
         player.teleportTo(player.level(), anchor.x, anchor.y, anchor.z,
            Set.of(), player.getYRot(), player.getXRot(), false);
      }
   }

   private void tickFrozen(MinecraftServer server) {
      Iterator<Map.Entry<UUID, Frozen>> iterator = frozen.entrySet().iterator();
      while (iterator.hasNext()) {
         Map.Entry<UUID, Frozen> entry = iterator.next();
         ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
         if (player == null) {
            entry.getValue().dismantle();
            iterator.remove();
            continue;
         }

         Frozen state = entry.getValue();
         state.age++;
         state.ticksLeft--;
         if (state.ticksLeft <= 0) {
            iterator.remove();
            state.dismantle();
            StatusAbilities.Broadcaster.INSTANCE.refresh(player);
            player.level().playSound(null, state.anchor.x, state.anchor.y, state.anchor.z,
               SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.0F, 1.3F);
            ((ServerLevel) player.level()).sendParticles(ParticleTypes.SNOWFLAKE,
               state.anchor.x, state.anchor.y + 0.9, state.anchor.z, 50, 0.5, 0.8, 0.5, 0.18);
            Feedback.actionBar(player, "§b❄ Aufgetaut");
            continue;
         }
         poseCage(state);

         if (player.isUsingItem()) {
            player.stopUsingItem();
         }
         holdAtAnchor(player, state.anchor);
         // Sparsam: der Käfig trägt das Bild, die Partikel legen nur das Knistern darüber.
         if (state.ticksLeft % 10 == 0) {
            player.level().sendParticles(ParticleTypes.SNOWFLAKE,
               player.getX(), player.getY() + 1.0, player.getZ(), 4, 0.4, 0.7, 0.4, 0.01);
         }
         if (state.ticksLeft % 24 == 0) {
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
               SoundEvents.POWDER_SNOW_BREAK, SoundSource.PLAYERS, 0.35F, 1.7F);
         }
      }
   }

   /**
    * Die scharfe Ladung blinkt, funkt und piept.
    *
    * Gefärbt wird dabei nur die Leuchtdiode: das Modell trägt {@code tintindex} allein auf ihren
    * Flächen, die Sprengmasse bleibt unberührt. Der Farbwert steckt im Gegenstand und geht als
    * eigenes Paket hinaus, deshalb wird er nur bei echter Änderung gesetzt.
    *
    * Hält der Besitzer eine weitere Ladung in der Hand – und damit den Zünder –, blinkt sie
    * dreimal so schnell: für ihn eine Bestätigung, für jeden anderen die letzte Warnung.
    *
    * Ton gibt sie keinen von sich. Eine piepende Ladung wäre um jede Ecke zu hören und damit
    * genau das Gegenteil einer versteckten.
    */
   private void tickCharges(MinecraftServer server, ServerLevel level) {
      for (Charge charge : charges) {
         charge.blink++;
         ServerPlayer owner = server.getPlayerList().getPlayer(charge.owner);
         int period = owner != null && holdsC4(owner) ? C4_BLINK_ARMED : C4_BLINK_IDLE;
         setLed(charge, charge.blink % period < C4_FLASH_TICKS ? C4_LED_FLASH : C4_LED_DARK);

         if (charge.blink % period == 0) {
            // Bewusst lautlos: eine Ladung, die piept, verrät sich hinter jeder Ecke.
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
               charge.position.x, charge.position.y + 0.2, charge.position.z, 2, 0.08, 0.05, 0.08, 0.01);
         }
      }
   }

   private void setLed(Charge charge, int colour) {
      if (charge.display == null || charge.led == colour) {
         return;
      }
      charge.led = colour;
      charge.display.getSlot(0).set(ledStack(colour));
   }

   /** Ob der Besitzer den Zünder – also die Ladung selbst – gerade in der Hand hält. */
   private boolean holdsC4(ServerPlayer owner) {
      return owner.getMainHandItem().is(ModItems.C4)
         || owner.getOffhandItem().is(ModItems.C4);
   }

   /** Ob der Spieler gerade eine seiner platzierten Ladungen anvisiert. */
   public boolean isAimingAtCharge(ServerPlayer owner) {
      Vec3 eye = owner.getEyePosition();
      Vec3 look = owner.getLookAngle();
      for (Charge charge : charges) {
         if (!charge.owner.equals(owner.getUUID())) {
            continue;
         }
         Vec3 toCharge = charge.position.subtract(eye);
         double along = toCharge.dot(look);
         if (along >= 0.0 && along <= C4_REACH
            && toCharge.subtract(look.scale(along)).length() <= C4_AIM_TOLERANCE) {
            return true;
         }
      }
      return false;
   }

   /**
    * Nimmt die angesehene eigene Ladung wieder von der Wand.
    *
    * Gesucht wird entlang der Blickachse: wer zwei Ladungen nebeneinander geklebt hat, soll die
    * abnehmen, die er ansieht, und nicht die, die zufällig näher liegt.
    *
    * Zurückgegeben wird nichts – der Gegenstand wurde beim Kleben gar nicht erst verbraucht.
    * Er verschwindet erst beim Zünden, siehe {@link #detonateAll(ServerPlayer)}.
    *
    * Meldet {@code false} ohne jede Rückmeldung, wenn der Blick auf keine eigene Ladung zeigt.
    * Das ist wichtig: Aufgerufen wird bei jedem Rechtsklick, und die allermeisten davon meinen
    * etwas ganz anderes.
    */
   public boolean recoverAimedCharge(ServerPlayer owner) {
      Vec3 eye = owner.getEyePosition();
      Vec3 look = owner.getLookAngle();
      Charge best = null;
      double bestDistance = Double.MAX_VALUE;

      for (Charge charge : charges) {
         if (!charge.owner.equals(owner.getUUID())) {
            continue;
         }
         Vec3 toCharge = charge.position.subtract(eye);
         double along = toCharge.dot(look);
         if (along < 0.0 || along > C4_REACH
            || toCharge.subtract(look.scale(along)).length() > C4_AIM_TOLERANCE) {
            continue;
         }
         if (along < bestDistance) {
            bestDistance = along;
            best = charge;
         }
      }

      if (best == null) {
         return false;
      }

      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      ServerLevel level = worlds == null ? null : worlds.getActiveLevel();
      if (level == null) {
         return false;
      }
      charges.remove(best);
      Hologram.remove(best.display);

      long remaining = ownedCharges(owner.getUUID());
      if (remaining <= 0) {
         // Keine Ladungen mehr scharf -> Der Zünder wird wieder zum unplatzierten C4
         disarmOneDetonator(owner);
      } else {
         // Es sind noch weitere Ladungen scharf -> Zünder bleibt scharf, abgenommenes C4 wird als unplatziertes Item zurückgegeben
         ItemStack recoveredC4 = new ItemStack(ModItems.C4);
         if (!owner.getInventory().add(recoveredC4)) {
            owner.drop(recoveredC4, false);
         }
         owner.containerMenu.broadcastChanges();
      }

      StatusAbilities.Broadcaster.INSTANCE.refresh(owner);
      level.playSound(null, best.position.x, best.position.y, best.position.z,
         SoundEvents.ITEM_FRAME_REMOVE_ITEM, SoundSource.PLAYERS, 0.9F, 1.2F);
      level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
         best.position.x, best.position.y, best.position.z, 12, 0.2, 0.2, 0.2, 0.06);
      Feedback.actionBar(owner, "§a💥 C4 ABGENOMMEN — " + remaining + " noch scharf");
      return true;
   }

   private void tickTurrets(MinecraftServer server, ServerLevel level) {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      Arena arena = worlds == null ? null : worlds.getActive();
      List<Turret> snapshot = new ArrayList<>(turrets);
      for (Turret turret : snapshot) {
         if (!turrets.contains(turret)) {
            continue;
         }

         if (turret.dying > 0) {
            if (--turret.dying <= 0) {
               shutDownFinished(level, turret);
               turrets.remove(turret);
               continue;
            }
            poseTurret(turret);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, turret.pivot().x, turret.pivot().y, turret.pivot().z,
               3, 0.25, 0.25, 0.25, 0.02);
            continue;
         }

         if (arena == null) {
            Hologram.remove(turret.baseDisplay);
            Hologram.remove(turret.headDisplay);
            turrets.remove(turret);
            continue;
         }

         checkArrowHits(level, turret);
         if (turret.dying > 0) {
            continue;
         }

         if (--turret.ticksLeft <= 0) {
            shutDown(level, turret);
            continue;
         }

         if (turret.deploy < TURRET_DEPLOY_TICKS) {
            deploy(level, turret);
         } else {
            aim(server, level, arena, turret);
            if (turret.aimed && turret.ticksLeft % TURRET_FIRE_INTERVAL_TICKS == 0) {
               fireTurret(server, level, arena, turret);
            }
         }
         poseTurret(turret);
      }
   }

   /** Der Turm fährt aus: er wächst auf volle Größe und dreht einmal durch. */
   private void deploy(ServerLevel level, Turret turret) {
      turret.deploy++;
      turret.yaw = turret.home + (float) (Math.PI * 2.0) * (1.0F - turret.deploy / (float) TURRET_DEPLOY_TICKS);
      turret.pitch = -0.5F * (1.0F - turret.deploy / (float) TURRET_DEPLOY_TICKS);
      turret.lens = turret.deploy % 6 < 3 ? LENS_LOCK : LENS_DEAD;
      if (turret.deploy % 6 == 0) {
         level.playSound(null, turret.position.x, turret.position.y, turret.position.z,
            SoundEvents.COMPARATOR_CLICK, SoundSource.PLAYERS, 0.5F, 0.8F);
      }
      if (turret.deploy >= TURRET_DEPLOY_TICKS) {
         turret.lens = LENS_SCAN;
         level.playSound(null, turret.position.x, turret.position.y, turret.position.z,
            SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.9F, 1.2F);
      }
   }

   /**
    * Richtet den Kopf aus – auf ein Ziel oder im Suchlauf.
    *
    * Der Turm hält sein Ziel fest, solange es gültig bleibt. Ohne das verteilt er seine Treffer
    * auf alle Gegner in Reichweite und kommt bei niemandem auf die drei nötigen Treffer.
    */
   private void aim(MinecraftServer server, ServerLevel level, Arena arena, Turret turret) {
      Vec3 pivot = turret.pivot();
      ServerPlayer target = turret.target == null ? null : server.getPlayerList().getPlayer(turret.target);
      if (target == null || !isValidTarget(level, arena, turret, pivot, target)) {
         ServerPlayer found = findTarget(server, level, arena, turret, pivot);
         if (found != null && turret.target == null) {
            // Anpeilen ist hörbar: zwei Schichten, damit es über das Gefecht kommt.
            level.playSound(null, pivot.x, pivot.y, pivot.z, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.9F, 2.0F);
            level.playSound(null, pivot.x, pivot.y, pivot.z, SoundEvents.NOTE_BLOCK_BELL, SoundSource.PLAYERS, 0.7F, 1.9F);
            Feedback.actionBar(found, "§c🤖 TURM HAT DICH ERFASST");
         }
         turret.target = found == null ? null : found.getUUID();
         target = found;
      }

      // Spieler haben Vorrang. Erst wenn keiner in Reichweite steht, nimmt der Turm einen
      // fremden Turm aufs Korn – sonst duellierten sich zwei Türme in Sichtweite miteinander,
      // während die Gegner in aller Ruhe daran vorbeiliefen.
      if (target != null) {
         turret.targetTurret = null;
      } else {
         if (turret.targetTurret != null && !isValidTurretTarget(level, turret, pivot, turret.targetTurret)) {
            turret.targetTurret = null;
         }
         if (turret.targetTurret == null) {
            turret.targetTurret = findTurretTarget(level, turret, pivot);
            if (turret.targetTurret != null) {
               level.playSound(null, pivot.x, pivot.y, pivot.z, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.9F, 2.0F);
               ServerPlayer victim = server.getPlayerList().getPlayer(turret.targetTurret.owner);
               if (victim != null) {
                  Feedback.actionBar(victim, "§c🤖 DEIN TURM WIRD VON EINEM TURM ANGEGRIFFEN");
               }
            }
         }
      }

      Vec3 aimAt = target != null
         ? target.position().add(0.0, 1.1, 0.0)
         : (turret.targetTurret == null ? null : turret.targetTurret.pivot());
      if (aimAt == null) {
         // Suchlauf: ein langsamer Schwenk um die Blickrichtung beim Aufstellen.
         turret.scan += TURRET_SCAN_SPEED;
         turret.yaw = approachAngle(turret.yaw, turret.home + TURRET_SCAN_ARC * (float) Math.sin(turret.scan),
            TURRET_TURN_PER_TICK * 0.5F);
         turret.pitch = approach(turret.pitch, 0.0F, TURRET_TURN_PER_TICK * 0.5F);
         // Langsames Atmen: der Turm sucht, aber niemand ist gemeint.
         int scanPhase = Math.floorMod(turret.ticksLeft, LENS_SCAN_CYCLE);
         turret.lens = scanPhase < 5 ? LENS_SCAN : LENS_SCAN_DIM;
         if (scanPhase == 0) {
            level.playSound(null, pivot.x, pivot.y, pivot.z, SoundEvents.COMPARATOR_CLICK,
               SoundSource.PLAYERS, 0.35F, 1.4F);
         }
         turret.aimed = false;
         return;
      }

      Vec3 delta = aimAt.subtract(pivot);
      float wantYaw = (float) Math.atan2(delta.x, delta.z);
      float wantPitch = (float) -Math.asin(Math.clamp(delta.normalize().y, -1.0, 1.0));
      turret.yaw = approachAngle(turret.yaw, wantYaw, TURRET_TURN_PER_TICK);
      turret.pitch = approach(turret.pitch, wantPitch, TURRET_TURN_PER_TICK);

      turret.aimed = Math.abs(wrapAngle(wantYaw - turret.yaw)) < TURRET_AIM_TOLERANCE
         && Math.abs(wantPitch - turret.pitch) < TURRET_AIM_TOLERANCE;
      if (turret.aimed) {
         // Beim Feuern schlägt das Auge zwischen Rot und Weißglut um.
         turret.lens = Math.floorMod(turret.ticksLeft, LENS_FIRE_CYCLE) < LENS_FIRE_CYCLE / 2
            ? LENS_FIRE_HOT : LENS_FIRE;
      } else {
         // Erfasst, aber noch nicht drauf: Doppelschlag in Bernstein.
         int lockPhase = Math.floorMod(turret.ticksLeft, LENS_LOCK_CYCLE);
         turret.lens = lockPhase < 3 || (lockPhase >= 5 && lockPhase < 8) ? LENS_LOCK : LENS_LOCK_DIM;
      }
   }

   /** Setzt Unterbau und Kopf. Der Unterbau steht; nur der Kopf trägt Gier- und Neigungswinkel. */
   private void poseTurret(Turret turret) {
      float scale = turret.dying > 0
         ? turret.dying / (float) TURRET_DEATH_TICKS
         : Math.min(1.0F, 0.25F + 0.75F * turret.deploy / (float) TURRET_DEPLOY_TICKS);
      // Im Ausfall sackt der Kopf nach vorn ab, statt einfach zu verschwinden.
      float pitch = turret.dying > 0
         ? turret.pitch + 1.1F * (1.0F - turret.dying / (float) TURRET_DEATH_TICKS)
         : turret.pitch;

      // Der Unterbau steht still. Seine Pose geht nur hinaus, wenn sich die Größe ändert –
      // sonst schickte ein Turm zwanzig Sekunden lang jede Runde ein Paket für ein Bild,
      // das sich nicht bewegt.
      if (scale != turret.baseScale) {
         turret.baseScale = scale;
         Hologram.setPose(turret.baseDisplay, new Vector3f(), new Quaternionf(),
            new Vector3f(scale, scale, scale), 2);
      }
      Hologram.setPose(turret.headDisplay, new Vector3f(),
         new Quaternionf().rotationY(turret.yaw).rotateX(pitch),
         new Vector3f(scale, scale, scale), 2);
      setLens(turret, turret.dying > 0 ? LENS_DEAD : turret.lens);
   }

   private void setLens(Turret turret, int colour) {
      if (turret.headDisplay == null || turret.lensShown == colour) {
         return;
      }
      turret.lensShown = colour;
      turret.headDisplay.getSlot(0).set(lensStack(colour));
   }

   /**
    * Pfeile, die den Turm treffen, beschädigen ihn.
    *
    * Über den Schadensweg ginge das nicht: der Turm ist keine Entity mit Trefferbox, sondern
    * zwei Displays ganz ohne Kollision – ein Pfeil flöge hindurch, ohne dass irgendetwas
    * feuerte. Deshalb wird hier die Strecke geprüft, die der Pfeil in diesem Tick zurückgelegt
    * hat. Eine reine Umkreisprüfung reichte nicht: ein Pfeil legt mehrere Blöcke je Tick zurück
    * und übersprünge den Turm zwischen zwei Prüfungen.
    */
   private void checkArrowHits(ServerLevel level, Turret turret) {
      AABB box = turret.hitbox();
      for (AbstractArrow arrow : level.getEntitiesOfClass(AbstractArrow.class, box.inflate(4.0))) {
         if (!(arrow.getOwner() instanceof ServerPlayer shooter) || shooter.getUUID().equals(turret.owner)) {
            continue;
         }
         Vec3 to = arrow.position();
         if (box.clip(to.subtract(arrow.getDeltaMovement()), to).isEmpty()) {
            continue;
         }
         arrow.discard();
         damageTurret(level, turret, shooter);
         if (turret.dying > 0) {
            return;
         }
      }
   }

   /**
    * Ein Treffer auf den Turm.
    *
    * @param credit Wem der Treffer gutgeschrieben wird – der Schütze oder der Besitzer des
    *               feuernden Turms. Darf fehlen: Ein Turm schießt weiter, auch wenn der, der
    *               ihn aufgestellt hat, längst offline ist.
    */
   private void damageTurret(ServerLevel level, Turret turret, ServerPlayer credit) {
      Vec3 pivot = turret.pivot();
      level.playSound(null, pivot.x, pivot.y, pivot.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.6F, 1.8F);
      level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pivot.x, pivot.y, pivot.z, 14, 0.3, 0.3, 0.3, 0.14);

      if (--turret.integrity <= 0) {
         if (credit != null) {
            Feedback.actionBar(credit, "§a🤖 TURM ZERSTÖRT");
         }
         shutDown(level, turret);
         return;
      }
      if (credit != null) {
         Feedback.actionBar(credit, "§e🤖 TURM " + (TURRET_INTEGRITY - turret.integrity)
            + "/" + TURRET_INTEGRITY);
      }
      ServerPlayer owner = level.getServer().getPlayerList().getPlayer(turret.owner);
      if (owner != null) {
         Feedback.actionBar(owner, "§c🤖 DEIN TURM WIRD BESCHOSSEN");
      }
   }

   /** Leitet den Ausfall ein: der Kopf sackt ab, dann zerspringt der Turm. */
   private void shutDown(ServerLevel level, Turret turret) {
      if (turret.dying > 0) {
         return;
      }
      turret.dying = TURRET_DEATH_TICKS;
      turret.target = null;
      turret.aimed = false;
      Vec3 pivot = turret.pivot();
      level.playSound(null, pivot.x, pivot.y, pivot.z, SoundEvents.IRON_DOOR_CLOSE, SoundSource.PLAYERS, 1.0F, 0.6F);
   }

   /**
    * Der Turm fällt aus und wird abgebaut – ohne Explosion.
    *
    * Er hatte einen Feuerball, und der war irreführend: nichts an ihm detoniert, niemand nimmt
    * Schaden, und wer den Knall hörte, suchte nach einer Sprengladung. Jetzt raucht die
    * Elektrik ab, mehr nicht.
    */
   private void shutDownFinished(ServerLevel level, Turret turret) {
      Vec3 pivot = turret.pivot();
      Hologram.remove(turret.baseDisplay);
      Hologram.remove(turret.headDisplay);
      level.playSound(null, pivot.x, pivot.y, pivot.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 0.8F, 0.7F);
      level.sendParticles(ParticleTypes.ELECTRIC_SPARK, pivot.x, pivot.y, pivot.z, 18, 0.25, 0.2, 0.25, 0.12);
      level.sendParticles(ParticleTypes.SMOKE, pivot.x, pivot.y, pivot.z, 14, 0.2, 0.2, 0.2, 0.02);
      MinecraftServer server = level.getServer();
      if (server != null) {
         ServerPlayer owner = server.getPlayerList().getPlayer(turret.owner);
         if (owner != null) {
            StatusAbilities.Broadcaster.INSTANCE.refresh(owner);
         }
      }
   }

   /** Dreht einen Winkel höchstens um {@code step} auf sein Ziel zu, über den Umlauf hinweg. */
   private static float approachAngle(float current, float wanted, float step) {
      return current + Math.clamp(wrapAngle(wanted - current), -step, step);
   }

   private static float approach(float current, float wanted, float step) {
      return current + Math.clamp(wanted - current, -step, step);
   }

   /** Bringt eine Winkeldifferenz auf den kurzen Weg zwischen -Pi und Pi. */
   private static float wrapAngle(float angle) {
      float wrapped = angle % (float) (Math.PI * 2.0);
      if (wrapped > (float) Math.PI) {
         wrapped -= (float) (Math.PI * 2.0);
      } else if (wrapped < -(float) Math.PI) {
         wrapped += (float) (Math.PI * 2.0);
      }
      return wrapped;
   }

   private void fireTurret(MinecraftServer server, ServerLevel level, Arena arena, Turret turret) {
      ServerPlayer target = turret.target == null ? null : server.getPlayerList().getPlayer(turret.target);
      if (target == null) {
         if (turret.targetTurret != null) {
            fireAtTurret(server, level, turret, turret.targetTurret);
         }
         return;
      }

      Vec3 aim = target.position().add(0.0, 1.1, 0.0);
      shotEffects(level, turret, aim);

      HitAccount account = turretHits.computeIfAbsent(target.getUUID(), ignored -> new HitAccount());
      account.hits++;
      account.lastHitTick = server.getTickCount();

      Vec3 push = aim.subtract(turret.pivot()).normalize().scale(TURRET_KNOCKBACK);
      target.setDeltaMovement(target.getDeltaMovement().add(push.x, 0.1, push.z));
      target.hurtMarked = true;

      if (account.hits >= TURRET_HITS_TO_KILL) {
         turretHits.remove(target.getUUID());
         ServerPlayer owner = server.getPlayerList().getPlayer(turret.owner);
         if (owner != null) {
            DamageListener.INSTANCE.eliminate(owner, target, arena, KillFeed.Cause.SENTRY_TURRET);
         }
      } else {
         Feedback.actionBar(target, "§c🤖 TURMTREFFER " + account.hits + "/" + TURRET_HITS_TO_KILL);
      }
   }

   /**
    * Ein Schuss auf einen fremden Turm.
    *
    * Ein Treffer kostet dieselbe Widerstandskraft wie ein Pfeil. Vier Schüsse im Takt von acht
    * Ticks heißt: Wer zuerst ausgerichtet ist, gewinnt das Duell – und das ist der Reiz daran,
    * einen Turm gegen einen anderen zu stellen.
    */
   private void fireAtTurret(MinecraftServer server, ServerLevel level, Turret turret, Turret victim) {
      shotEffects(level, turret, victim.pivot());
      damageTurret(level, victim, server.getPlayerList().getPlayer(turret.owner));
      if (victim.dying > 0) {
         turret.targetTurret = null;
      }
   }

   /** Mündungsfeuer, Leuchtspur und Ton – für jeden Schuss gleich, egal worauf. */
   private void shotEffects(ServerLevel level, Turret turret, Vec3 aim) {
      // Abwechselnd links und rechts – das Mündungsfeuer wandert sichtbar hin und her.
      turret.barrel = -turret.barrel;
      Vec3 muzzle = turret.muzzle(turret.barrel);
      drawTracer(level, muzzle, aim);
      level.sendParticles(ParticleTypes.FLAME, muzzle.x, muzzle.y, muzzle.z, 6, 0.05, 0.05, 0.05, 0.02);
      level.sendParticles(ParticleTypes.SMOKE, muzzle.x, muzzle.y, muzzle.z, 3, 0.04, 0.04, 0.04, 0.01);
      // Drei Schichten je Schuss: der Anschlag, der Nachhall und das Klacken der Mechanik.
      level.playSound(null, muzzle.x, muzzle.y, muzzle.z, SoundEvents.CROSSBOW_SHOOT, SoundSource.PLAYERS, 0.9F, 1.5F);
      level.playSound(null, muzzle.x, muzzle.y, muzzle.z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 0.35F, 1.9F);
      if (turret.barrel > 0) {
         level.playSound(null, muzzle.x, muzzle.y, muzzle.z, SoundEvents.COMPARATOR_CLICK, SoundSource.PLAYERS, 0.5F, 0.7F);
      }
   }

   /** Der nächste fremde Turm in Reichweite und in Sicht. */
   private Turret findTurretTarget(ServerLevel level, Turret turret, Vec3 pivot) {
      Turret best = null;
      double bestDistance = Double.MAX_VALUE;
      for (Turret candidate : turrets) {
         if (!isValidTurretTarget(level, turret, pivot, candidate)) {
            continue;
         }
         double distance = candidate.pivot().distanceToSqr(pivot);
         if (distance < bestDistance) {
            bestDistance = distance;
            best = candidate;
         }
      }
      return best;
   }

   /**
    * Ob dieser Turm auf jenen schießen darf.
    *
    * Die Prüfung gegen die Liste der lebenden Türme steht mit Absicht mit vorn: Ein Verweis auf
    * einen längst abgeräumten Turm sähe sonst weiter gültig aus, und der Turm feuerte auf eine
    * Stelle, an der nichts mehr steht.
    */
   private boolean isValidTurretTarget(ServerLevel level, Turret turret, Vec3 pivot, Turret candidate) {
      if (candidate == turret || candidate.owner.equals(turret.owner)
         || candidate.dying > 0 || !turrets.contains(candidate)) {
         return false;
      }
      Vec3 aim = candidate.pivot();
      if (aim.distanceToSqr(pivot) > TURRET_RANGE * TURRET_RANGE) {
         return false;
      }
      return level.clip(new ClipContext(pivot, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()))
         .getType() == HitResult.Type.MISS;
   }

   private ServerPlayer findTarget(MinecraftServer server, ServerLevel level, Arena arena, Turret turret, Vec3 muzzle) {
      ServerPlayer best = null;
      double bestDistance = Double.MAX_VALUE;
      for (ServerPlayer candidate : server.getPlayerList().getPlayers()) {
         if (!isValidTarget(level, arena, turret, muzzle, candidate)) {
            continue;
         }
         double distance = candidate.position().distanceToSqr(muzzle);
         if (distance < bestDistance) {
            bestDistance = distance;
            best = candidate;
         }
      }
      return best;
   }

   /** Unsichtbare Spieler bleiben ausgenommen – sonst wäre der Tarnmantel gegen Türme wertlos. */
   private boolean isValidTarget(ServerLevel level, Arena arena, Turret turret, Vec3 muzzle, ServerPlayer candidate) {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      if (candidate.getUUID().equals(turret.owner) || !candidate.isAlive() || worlds == null) {
         return false;
      }
      if (worlds.arenaOf(candidate) != arena || StatusAbilities.INSTANCE.isVanished(candidate)) {
         return false;
      }
      Vec3 aim = candidate.position().add(0.0, 1.1, 0.0);
      if (aim.distanceToSqr(muzzle) > TURRET_RANGE * TURRET_RANGE) {
         return false;
      }
      return level.clip(new ClipContext(muzzle, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()))
         .getType() == HitResult.Type.MISS;
   }

   private void drawTracer(ServerLevel level, Vec3 from, Vec3 to) {
      Vec3 delta = to.subtract(from);
      int steps = Math.max(4, (int) (delta.length() * 2));
      for (int step = 0; step <= steps; step++) {
         Vec3 point = from.add(delta.scale(step / (double) steps));
         level.sendParticles(ParticleTypes.CRIT, point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0);
      }
   }

   // -- Abrechnung der Ladungen ---------------------------------------------

   /**
    * Ob noch eine Ladung gesetzt werden darf.
    *
    * Eine Ladung je Gegenstand: Wer zwei C4 im Inventar hat, klebt zwei an die Wand. Der
    * Gegenstand bleibt beim Kleben liegen, weil er zugleich der Zünder ist – ohne diese
    * Abrechnung ließe sich mit einem einzigen C4 die halbe Karte zupflastern.
    */
   public boolean canPlaceMore(ServerPlayer owner) {
      return ownedCharges(owner.getUUID()) < countC4(owner);
   }

   /** Verbraucht den Zünder beim Detonieren aller Ladungen. */
   private static void consumeArmedDetonators(ServerPlayer owner) {
      for (int slot = 0; slot < owner.getInventory().getContainerSize(); slot++) {
         ItemStack stack = owner.getInventory().getItem(slot);
         if (stack.is(ModItems.C4) && stack.has(ModDataComponents.C4_ARMED)) {
            stack.shrink(1);
         }
      }
      owner.containerMenu.broadcastChanges();
   }

   /** Entschärft genau einen Zünder und verwandelt ihn zurück in ein unplatziertes C4. */
   private static void disarmOneDetonator(ServerPlayer owner) {
      ItemStack main = owner.getMainHandItem();
      if (main.is(ModItems.C4) && main.has(ModDataComponents.C4_ARMED)) {
         main.remove(ModDataComponents.C4_ARMED);
         owner.containerMenu.broadcastChanges();
         return;
      }
      ItemStack off = owner.getOffhandItem();
      if (off.is(ModItems.C4) && off.has(ModDataComponents.C4_ARMED)) {
         off.remove(ModDataComponents.C4_ARMED);
         owner.containerMenu.broadcastChanges();
         return;
      }
      for (int slot = 0; slot < owner.getInventory().getContainerSize(); slot++) {
         ItemStack stack = owner.getInventory().getItem(slot);
         if (stack.is(ModItems.C4) && stack.has(ModDataComponents.C4_ARMED)) {
            stack.remove(ModDataComponents.C4_ARMED);
            owner.containerMenu.broadcastChanges();
            return;
         }
      }
   }

   /** Entschärft alle Zünder des Spielers bei Match-Ende oder Reset. */
   private static void disarmAllDetonators(ServerPlayer owner) {
      for (int slot = 0; slot < owner.getInventory().getContainerSize(); slot++) {
         ItemStack stack = owner.getInventory().getItem(slot);
         if (stack.is(ModItems.C4) && stack.has(ModDataComponents.C4_ARMED)) {
            stack.remove(ModDataComponents.C4_ARMED);
         }
      }
      owner.containerMenu.broadcastChanges();
   }

   private static int countC4(ServerPlayer owner) {
      int found = 0;
      for (int slot = 0; slot < owner.getInventory().getContainerSize(); slot++) {
         ItemStack stack = owner.getInventory().getItem(slot);
         if (stack.is(ModItems.C4)) {
            found += stack.getCount();
         }
      }
      return found;
   }

   /** Anzahl der scharfen Ladungen eines Spielers – fürs HUD. */
   public int chargeCount(ServerPlayer owner) {
      return (int) ownedCharges(owner.getUUID());
   }

   /** Anzahl der platzierten Frost-Fallen eines Spielers – fürs HUD. */
   public int trapCount(ServerPlayer owner) {
      UUID id = owner.getUUID();
      return (int) traps.stream().filter(trap -> trap.owner.equals(id) && trap.revealTicks <= 0).count();
   }

   /** Anzahl der aktiven Geschütztürme eines Spielers – fürs HUD. */
   public int turretCount(ServerPlayer owner) {
      UUID id = owner.getUUID();
      return (int) turrets.stream().filter(turret -> turret.owner.equals(id)).count();
   }

   /**
    * Zerstört alle platzierten Geräte (Frost-Fallen, Geschütztürme, C4-Ladungen) im Explosionsradius
    * und aktualisiert sofort das Status-HUD der Besitzer.
    */
   public void destroyInRadius(ServerLevel level, Vec3 center, double radius) {
      double rSqr = radius * radius;
      Set<UUID> affectedOwners = new java.util.HashSet<>();

      // 1. Frost-Fallen
      List<FrostTrap> destroyedTraps = new ArrayList<>();
      for (FrostTrap trap : traps) {
         if (trap.position.distanceToSqr(center) <= rSqr) {
            destroyedTraps.add(trap);
         }
      }
      for (FrostTrap trap : destroyedTraps) {
         Hologram.remove(trap.display);
         traps.remove(trap);
         affectedOwners.add(trap.owner);
         level.playSound(null, trap.position.x, trap.position.y, trap.position.z,
            SoundEvents.POWDER_SNOW_BREAK, SoundSource.PLAYERS, 1.0F, 0.8F);
         level.sendParticles(ParticleTypes.SNOWFLAKE, trap.position.x, trap.position.y + 0.2, trap.position.z,
            25, 0.3, 0.2, 0.3, 0.1);
      }

      // 2. Geschütztürme
      List<Turret> destroyedTurrets = new ArrayList<>();
      for (Turret turret : turrets) {
         if (turret.position.distanceToSqr(center) <= rSqr || turret.pivot().distanceToSqr(center) <= rSqr) {
            destroyedTurrets.add(turret);
         }
      }
      for (Turret turret : destroyedTurrets) {
         shutDownFinished(level, turret);
         turrets.remove(turret);
         affectedOwners.add(turret.owner);
      }

      // 3. C4-Ladungen
      List<Charge> destroyedCharges = new ArrayList<>();
      for (Charge charge : charges) {
         if (charge.position.distanceToSqr(center) <= rSqr) {
            destroyedCharges.add(charge);
         }
      }
      for (Charge charge : destroyedCharges) {
         Hologram.remove(charge.display);
         charges.remove(charge);
         affectedOwners.add(charge.owner);
      }

      // Besitzer benachrichtigen & HUD sofort synchronisieren
      MinecraftServer server = level.getServer();
      if (server != null) {
         for (UUID ownerId : affectedOwners) {
            ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
            if (owner != null) {
               if (!destroyedCharges.isEmpty()) {
                  consumeArmedDetonators(owner);
               }
               StatusAbilities.Broadcaster.INSTANCE.refresh(owner);
            }
         }
      }
   }

   public boolean isFrozen(ServerPlayer player) {
      return frozen.containsKey(player.getUUID());
   }

   public int remainingFreezeTicks(ServerPlayer player) {
      Frozen state = frozen.get(player.getUUID());
      return state == null ? 0 : state.ticksLeft;
   }

   private long ownedCharges(UUID owner) {
      return charges.stream().filter(charge -> charge.owner.equals(owner)).count();
   }

   /**
    * Alles, was dieser Spieler abgestellt hat – für die Peilung auf seinem HUD.
    *
    * Nur die eigenen Geräte: Eine Frost-Falle ist für Gegner absichtlich unsichtbar, und wer
    * fremde C4 auf dem Schirm hätte, müsste sie nicht mehr suchen.
    *
    * Gemeldet wird die Stelle, an der das Gerät wirklich steht – beim Turm sein Kopf, also das,
    * was man von ihm sieht. Wie hoch das Zeichen darüber schwebt, entscheidet das HUD; nur so
    * kann es auch prüfen, ob freie Sicht auf das Gerät selbst besteht.
    */
   public List<DeployableMarkersPayload.Marker> markersFor(ServerPlayer owner) {
      UUID id = owner.getUUID();
      List<DeployableMarkersPayload.Marker> markers = new ArrayList<>();
      boolean detonatorReady = holdsC4(owner);
      for (Charge charge : charges) {
         if (charge.owner.equals(id)) {
            markers.add(new DeployableMarkersPayload.Marker(
               DeployableMarkersPayload.Kind.C4,
               charge.position.x, charge.position.y, charge.position.z,
               detonatorReady));
         }
      }
      for (Turret turret : turrets) {
         if (turret.owner.equals(id)) {
            markers.add(new DeployableMarkersPayload.Marker(
               DeployableMarkersPayload.Kind.TURRET,
               turret.position.x, turret.position.y + TURRET_HEAD_LIFT, turret.position.z,
               turret.target != null || turret.targetTurret != null));
         }
      }
      for (FrostTrap trap : traps) {
         if (trap.owner.equals(id)) {
            markers.add(new DeployableMarkersPayload.Marker(
               DeployableMarkersPayload.Kind.FROST_TRAP,
               trap.position.x, trap.position.y, trap.position.z,
               trap.revealTicks > 0));
         }
      }
      return markers;
   }

   // -- Aufräumen -----------------------------------------------------------

   /**
    * Was ein Tod beendet: nur das Eingefrorensein.
    *
    * Fallen, Ladungen und Türme bleiben stehen. Sie sind aufgestellt worden und gehören zur
    * Karte, nicht zum Leben ihres Aufstellers – ein Turm, der mit seinem Besitzer verschwindet,
    * lohnt sich nie.
    */
   public void clearOnDeath(ServerPlayer player) {
      Frozen thawed = frozen.remove(player.getUUID());
      if (thawed != null) {
         thawed.dismantle();
         StatusAbilities.Broadcaster.INSTANCE.refresh(player);
      }
      turretHits.remove(player.getUUID());
   }

   /** Der volle Abbau – bei Verbindungsabbruch, Matchende und Arena-Reset. */
   public void clearFor(ServerPlayer player) {
      Frozen thawed = frozen.remove(player.getUUID());
      if (thawed != null) {
         thawed.dismantle();
         StatusAbilities.Broadcaster.INSTANCE.refresh(player);
      }
      traps.removeIf(trap -> {
         if (!trap.owner.equals(player.getUUID())) {
            return false;
         }
         Hologram.remove(trap.display);
         return true;
      });
      turretHits.remove(player.getUUID());
      turrets.removeIf(turret -> {
         if (!turret.owner.equals(player.getUUID())) {
            return false;
         }
         Hologram.remove(turret.baseDisplay);
         Hologram.remove(turret.headDisplay);
         return true;
      });
      charges.removeIf(charge -> {
         if (!charge.owner.equals(player.getUUID())) {
            return false;
         }
         Hologram.remove(charge.display);
         return true;
      });
      disarmAllDetonators(player);
   }

   public void reset() {
      traps.forEach(trap -> Hologram.remove(trap.display));
      charges.forEach(charge -> Hologram.remove(charge.display));
      turrets.forEach(turret -> {
         Hologram.remove(turret.baseDisplay);
         Hologram.remove(turret.headDisplay);
      });
      frozen.values().forEach(Frozen::dismantle);
      traps.clear();
      charges.clear();
      turrets.clear();
      frozen.clear();
      turretHits.clear();
   }

   private static final class FrostTrap {
      private final Vec3 position;
      private final BlockPos triggerBlock;
      private final UUID owner;
      private final float yaw;
      private Display.ItemDisplay display;
      private int revealTicks;
      private boolean ownerCanTrigger;

      private FrostTrap(Vec3 position, BlockPos triggerBlock, UUID owner, float yaw) {
         this.position = position;
         this.triggerBlock = triggerBlock;
         this.owner = owner;
         this.yaw = yaw;
      }
   }

   private static final class Charge {
      /** Mitte der Ladung – zugleich die Position ihrer Display-Entity. */
      private final Vec3 position;
      private final UUID owner;
      /** Fläche, an der die Ladung klebt – sie bestimmt Ausrichtung und Kraterlage. */
      private final Direction face;
      private final Quaternionf facing;
      private Display.ItemDisplay display;
      private int blink;
      private int led;

      private Charge(Vec3 position, UUID owner, Direction face, Quaternionf facing) {
         this.position = position;
         this.owner = owner;
         this.face = face;
         this.facing = facing;
      }
   }

   private static final class Turret {
      /** Standfläche des Turms; beide Displays sitzen um feste Beträge darüber. */
      private final Vec3 position;
      private final UUID owner;
      private Display.ItemDisplay baseDisplay;
      private Display.ItemDisplay headDisplay;
      private int ticksLeft;
      private int deploy;
      private int dying;
      private int integrity = TURRET_INTEGRITY;
      private UUID target;
      /**
       * Ein anvisierter fremder Turm.
       *
       * Als Verweis und nicht als Kennung: Türme haben keine, und eine einzuführen hieße, sie
       * überall mitzuschleppen. Ob der Verweis noch gilt, sagt die Liste der lebenden Türme –
       * bei einer Handvoll Einträgen ist das billiger als jede Buchführung.
       */
      private Turret targetTurret;
      /** Blickrichtung beim Aufstellen – Mitte des Suchlaufs. */
      private float home;
      private float yaw;
      private float pitch;
      private float scan;
      private boolean aimed;
      private int barrel = 1;
      private int lens = LENS_SCAN;
      private int lensShown = -1;
      private float baseScale = -1.0F;

      private Turret(Vec3 position, UUID owner, int ticksLeft) {
         this.position = position;
         this.owner = owner;
         this.ticksLeft = ticksLeft;
      }

      /** Lagerung des Kopfes – der Punkt, um den er schwenkt. */
      private Vec3 pivot() {
         return position.add(0.0, TURRET_HEAD_LIFT, 0.0);
      }

      /**
       * Mündung eines der beiden Läufe, aus der Ausrichtung des Kopfes gerechnet.
       *
       * Ein fester Abstand über der Lagerung täte es nicht: der Kopf schwenkt, und die Läufe
       * schwenken mit. Wer das Mündungsfeuer an einen festen Punkt setzt, sieht es beim
       * Seitwärtsschießen neben der Waffe hängen.
       */
      private Vec3 muzzle(int side) {
         double cosYaw = Math.cos(yaw);
         double sinYaw = Math.sin(yaw);
         double cosPitch = Math.cos(pitch);
         Vec3 forward = new Vec3(sinYaw * cosPitch, -Math.sin(pitch), cosYaw * cosPitch);
         Vec3 right = new Vec3(cosYaw, 0.0, -sinYaw);
         return pivot()
            .add(forward.scale(MUZZLE_FORWARD))
            .add(right.scale(side * MUZZLE_SIDE))
            .add(0.0, MUZZLE_UP, 0.0);
      }

      /** Trefferbox für Pfeilbeschuss – grob der Umriss von Unterbau und Kopf. */
      private AABB hitbox() {
         return new AABB(position.x - 0.5, position.y, position.z - 0.5,
            position.x + 0.5, position.y + 1.2, position.z + 0.5);
      }
   }

   private static final class Frozen {
      private final Vec3 anchor;
      private final List<Display.ItemDisplay> shards = new ArrayList<>();
      private final List<Quaternionf> shardAims = new ArrayList<>();
      private final List<Vec3> shardDirections = new ArrayList<>();
      private Display.ItemDisplay ring;
      private int ticksLeft;
      private int age;
      private int colour = -1;

      private Frozen(Vec3 anchor, int ticksLeft) {
         this.anchor = anchor;
         this.ticksLeft = ticksLeft;
      }

      private void dismantle() {
         Hologram.remove(ring);
         for (Display.ItemDisplay shard : shards) {
            Hologram.remove(shard);
         }
      }
   }

   private static final class HitAccount {
      private int hits;
      private int lastHitTick;
   }
}
