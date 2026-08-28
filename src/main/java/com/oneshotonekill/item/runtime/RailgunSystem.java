package com.oneshotonekill.item.runtime;

import com.oneshotonekill.shared.Hologram;
import com.oneshotonekill.shared.Blast;
import com.oneshotonekill.shared.BlastEffect;
import com.oneshotonekill.shared.Feedback;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.arena.ArenaWorlds;
import com.oneshotonekill.event.KillFeed;
import com.oneshotonekill.event.CombatEvents.DamageListener;
import com.oneshotonekill.network.OsokPayloads.*;
import com.oneshotonekill.registry.ModItems;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Die Railgun: aufladen, loslassen, und was auf der Linie steht, ist weg.
 *
 * Vorher schoss sie ohne Vorlauf und zeichnete den Strahl aus einer Reihe Partikel. Beides ist
 * ersetzt:
 *
 * <ul>
 *   <li><b>Sie lädt.</b> Wer die Taste hält, sieht die Kondensatorspulen der Waffe von einem
 *       matten Blau bis zum Weißglühen hochlaufen und hört den Ton mitsteigen. Losgelassen
 *       wird erst bei voller Ladung etwas – wer zu früh loslässt, behält seine Waffe und
 *       fängt von vorn an. Der Schuss durchschlägt dafür jeden auf der Linie.</li>
 *   <li><b>Der Strahl ist ein Körper.</b> Eine einzige {@link Display.ItemDisplay} mit dem
 *       Lanzenmodell aus {@code tools/generate_railgun_3d.py}, in der Länge auf die
 *       Schussweite gestreckt. Das ist billiger als hundert Partikel und aus jeder Entfernung
 *       zu sehen.</li>
 * </ul>
 *
 * Block- und Spielertreffer werden getrennt gesucht und danach nach Entfernung verglichen –
 * eine Wand blockt den Schuss damit zuverlässig, statt dass ein Gegner dahinter noch getroffen
 * wird.
 */
public final class RailgunSystem {
   public static final RailgunSystem INSTANCE = new RailgunSystem();

   /**
    * Nach so vielen Ticks ist die Waffe voll geladen – und erst dann löst sie aus.
    *
    * Vorher genügte ein Bruchteil davon für einen schwächeren Schuss. Das nahm dem Aufladen
    * seinen Sinn: Wer schnell schoss, traf zwar kürzer, aber eben trotzdem, und die Waffe war
    * damit eine gewöhnliche Schnellfeuerwaffe mit Vorlauf. Jetzt gibt es nur den vollen Schuss.
    */
   public static final int CHARGE_TICKS = 34;

   /** Unendliche Reichweite über die gesamte Arena. */
   private static final double RANGE = 2048.0;
   /** Wie weit ein Schuss neben der Mitte eines Spielers noch trifft. */
   private static final double HIT_TOLERANCE = 0.35;

   /**
    * Mündung in der Ersten-Person-Ansicht, in Metern vom Auge aus.
    *
    * Keine geschätzten Werte: {@code tools/generate_railgun_3d.py} führt die Spitze der
    * Schiene durch die Anzeige-Transformation und die Handverschiebung des Renderers und gibt
    * das Ergebnis beim Erzeugen des Modells aus. Wer an Geometrie oder Handhaltung dreht, holt
    * die Zahlen von dort – sonst laufen Bild und Strahl auseinander.
    */
   private static final double MUZZLE_FORWARD = 1.0500;
   private static final double MUZZLE_RIGHT = 0.3000;
   private static final double MUZZLE_DOWN = 0.2799;

   private static final float VIEW_RANGE = 32.0F;
   /** So lange steht der Strahl noch in der Luft, bevor er verglüht. */
   private static final int BEAM_TICKS = 16;
   /**
    * Dicke des Strahls in Blöcken, von der kleinsten bis zur vollen Ladung.
    *
    * In Blöcken und nicht als Skalierungswert: das Lanzenmodell ist im Querschnitt nur ein gutes
    * Zehntel eines Blocks breit, ein Skalierungswert um eins ergäbe also einen Faden. Umgerechnet
    * wird über {@link #BOLT_GIRTH_UNIT}.
    */
   private static final double BEAM_GIRTH_MIN = 0.30;
   private static final double BEAM_GIRTH_MAX = 0.95;
   /**
    * Querschnitt des Lanzenmodells bei Skalierung 1, in Blöcken.
    *
    * Steht so in der Ausgabe von {@code tools/generate_railgun_3d.py} und muss mit ihr
    * übereinstimmen.
    */
   private static final double BOLT_GIRTH_UNIT = 0.1188;
   /** Höhe des Ringmodells bei Skalierung 1 – aus {@code tools/generate_blast_3d.py}. */
   private static final double RING_BAND_UNIT = 0.0978;

   // --- Der Einschlag: Energie, kein Feuer ---
   private static final int FLASH_TICKS = 26;
   private static final double FLASH_RING_DIAMETER = 7.0;
   private static final double FLASH_RING_BAND = 0.35;
   private static final double FLASH_CORE_WIDTH = 2.4;
   private static final int FLASH_SHARDS = 7;
   private static final double FLASH_SHARD_LENGTH = 2.6;
   private static final double FLASH_SHARD_REACH = 3.4;
   private static final double FLASH_SHARD_GIRTH = 0.22;
   private static final int FLASH_HOT = 0xEAFDFF;
   private static final int FLASH_COOL = 0x3C74E8;
   private static final int FLASH_VIOLET = 0x9A5CFF;
   /** Rückstoß bei voller Ladung. Genug, um sich damit von einer Kante zu schubsen. */
   private static final double RECOIL = 0.62;

   /**
    * Die Anzeigen der Waffe – Bildschirme, Melder und Leuchtstreifen.
    *
    * Sie hängen an der zweiten Farbebene des Modells und laufen deshalb unabhängig von den
    * Spulen: im Ruhezustand ein türkises Doppelblinken mit langer Pause, beim Laden ein immer
    * schnelleres Bernstein, das zum Schluss rot durchschlägt.
    */
   private static final int PANEL_CYCLE = 44;
   private static final int PANEL_DIM = 0x0C4A45;
   private static final int PANEL_LIT = 0x63FFE6;
   private static final int PANEL_CHARGING = 0xFFB03A;
   private static final int PANEL_READY = 0xFF4632;

   /** Spulenfarbe von matt bis weißglühend, dazu die Farbe der Lanze. */
   private static final int COIL_COLD = 0x1E5A88;
   private static final int COIL_HOT = 0xF2FBFF;
   private static final int BOLT_HOT = 0xEAFBFF;
   private static final int BOLT_COLD = 0x2E7FC8;

   private static final Vec3 WORLD_UP = new Vec3(0.0, 1.0, 0.0);

   private static final int RING_SLOT = 0;
   private static final int CORE_SLOT = 1;

   private final List<Beam> beams = new ArrayList<>();
   private final List<Flash> flashes = new ArrayList<>();

   private RailgunSystem() {
   }

   // -- Laden ---------------------------------------------------------------

   /** Setzt die Spulen auf kalt und gibt den Anschlagton. */
   public void beginCharge(ServerLevel level, ServerPlayer shooter, ItemStack weapon) {
      setCoils(weapon, COIL_COLD);
      level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(),
         SoundEvents.CONDUIT_ACTIVATE, SoundSource.PLAYERS, 0.7F, 1.6F);
   }

   /**
    * Ein Tick Ladezeit: Spulen heller, Ton höher, Funken an der Mündung.
    *
    * Die Farbe wird auf dem gehaltenen Stapel selbst gesetzt. Das unterbricht das Laden nicht:
    * {@code LivingEntity#updatingUsingItem} vergleicht den Stapel über die Objektgleichheit,
    * und ein an Ort und Stelle geänderter Bestandteil lässt die Kennung unangetastet.
    */
   public void chargeTick(ServerLevel level, ServerPlayer shooter, ItemStack weapon) {
      int elapsed = shooter.getTicksUsingItem();
      double share = chargeShare(elapsed);
      setCoils(weapon, blend(COIL_COLD, COIL_HOT, share));

      Vec3 muzzle = muzzle(shooter);
      if (elapsed % 2 == 0) {
         level.sendParticles(ParticleTypes.ELECTRIC_SPARK, muzzle.x, muzzle.y, muzzle.z,
            (int) (1 + share * 4), 0.08, 0.08, 0.08, 0.03 + share * 0.09);
      }
      if (share >= 1.0 && elapsed % 6 == 0) {
         // Volle Ladung: der Lauf steht sichtbar unter Spannung.
         level.sendParticles(ParticleTypes.END_ROD, muzzle.x, muzzle.y, muzzle.z, 3, 0.1, 0.1, 0.1, 0.05);
      }

      // Die Anzeigen blinken mit dem Ladestand immer schneller und schlagen zum Schluss rot durch.
      int period = Math.max(2, (int) Math.round(10.0 - share * 7.0));
      boolean lit = Math.floorMod(level.getGameTime(), period) < Math.max(1, period / 2);
      setPanel(weapon, lit ? blend(PANEL_CHARGING, PANEL_READY, share) : PANEL_DIM);

      if (elapsed % 4 == 0) {
         level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(),
            SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS, 0.5F, 0.7F + (float) share * 1.2F);
      }
      if (elapsed == CHARGE_TICKS) {
         level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(),
            SoundEvents.NOTE_BLOCK_BELL, SoundSource.PLAYERS, 0.9F, 2.0F);
         Feedback.actionBar(shooter, "§b⚡ RAILGUN GELADEN");
      }
   }

   /** Bricht das Laden ab, ohne zu schießen – etwa beim Wechsel der Waffe. */
   public void cancelCharge(ItemStack weapon) {
      setCoils(weapon, COIL_COLD);
   }

   /**
    * Das Ruhemuster der Anzeigen: zweimal kurz, dann lange nichts.
    *
    * Läuft auch, wenn niemand lädt – eine Waffe, die erst beim Drücken lebendig wird, wirkt
    * wie ein Requisit. Gerätebedingt geht jede Farbänderung als Inventarpaket hinaus, deshalb
    * hat das Muster lange Ruhephasen und {@link #setPanel} vergleicht vorher.
    */
   public void idleBlink(ItemStack weapon, long gameTime) {
      int phase = (int) Math.floorMod(gameTime, PANEL_CYCLE);
      boolean lit = phase < 2 || (phase >= 5 && phase < 7);
      setPanel(weapon, lit ? PANEL_LIT : PANEL_DIM);
   }

   /** @return true, wenn wirklich geschossen wurde und das Item verbraucht werden darf. */
   public boolean release(ServerLevel level, ServerPlayer shooter, ItemStack weapon, int elapsed) {
      setCoils(weapon, COIL_COLD);
      if (elapsed < CHARGE_TICKS) {
         level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(),
            SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 0.6F, 1.8F);
         int missing = Math.max(1, (CHARGE_TICKS - elapsed + 19) / 20);
         Feedback.actionBar(shooter, "§7⚡ Zu früh losgelassen — noch " + missing + " s bis zur vollen Ladung");
         return false;
      }
      fire(level, shooter, 1.0);
      return true;
   }

   private static double chargeShare(int elapsed) {
      return Math.clamp(elapsed / (double) CHARGE_TICKS, 0.0, 1.0);
   }

   /**
    * Setzt die Farbe der zweiten Ebene über die Farbliste von {@code CUSTOM_MODEL_DATA}.
    *
    * Nur bei echter Änderung, denn jede geht als Inventarpaket zum Client. Dass die Waffe dabei
    * nicht bei jedem Blinken neu in die Hand genommen wird, hängt an
    * {@code RailgunItem#shouldCauseReequipAnimation} – Vanilla vergleicht dort die Stapel über
    * die Objektgleichheit und hält jede Veränderung für einen Waffenwechsel.
    */
   private static void setPanel(ItemStack weapon, int colour) {
      int stepped = colour & 0xF8F8F8;
      CustomModelData current = weapon.get(DataComponents.CUSTOM_MODEL_DATA);
      if (current != null && !current.colors().isEmpty() && current.colors().getFirst() == stepped) {
         return;
      }
      weapon.set(DataComponents.CUSTOM_MODEL_DATA,
         new CustomModelData(List.of(), List.of(), List.of(), List.of(stepped)));
   }

   private static void setCoils(ItemStack weapon, int colour) {
      int stepped = colour & 0xF8F8F8;
      DyedItemColor current = weapon.get(DataComponents.DYED_COLOR);
      if (current != null && current.rgb() == stepped) {
         return;
      }
      weapon.set(DataComponents.DYED_COLOR, new DyedItemColor(stepped));
   }

   // -- Schießen ------------------------------------------------------------

   /**
    * @param charge Stärke des Schusses, zwischen null und eins.
    *
    * Sie steht seit der Umstellung auf die volle Ladung immer auf eins. Der Wert bleibt
    * trotzdem ein Parameter, weil Reichweite, Strahldicke, Rückstoß und Einschlag alle daran
    * hängen – ein Schuss halber Stärke wäre damit eine Zeile Arbeit, kein Umbau.
    */
   private void fire(ServerLevel level, ServerPlayer shooter, double charge) {
      Vec3 origin = muzzle(shooter);
      Vec3 direction = shooter.getLookAngle().normalize();
      double range = RANGE * charge;

      BlockHitResult blockHit = level.clip(new ClipContext(
         shooter.getEyePosition(), shooter.getEyePosition().add(direction.scale(range)),
         ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
      Vec3 impact = blockHit.getType() == HitResult.Type.MISS
         ? origin.add(direction.scale(range))
         : blockHit.getLocation();

      // Die Waffe löst nur voll geladen aus, deshalb geht jeder Schuss durch alle auf der
      // Linie hindurch und endet erst an der Wand dahinter.
      List<ServerPlayer> victims = victimsAlong(level, shooter, origin, impact);

      shoot(level, shooter, origin, impact, charge);
      for (ServerPlayer victim : victims) {
         eliminate(shooter, victim);
      }

      if (victims.isEmpty()) {
         Feedback.actionBar(shooter, "§7⚡ RAILGUN — Fehlschuss");
      } else {
         Feedback.actionBar(shooter, "§b⚡ RAILGUN — " + victims.size()
            + (victims.size() == 1 ? " TREFFER" : " DURCHSCHLAGEN"));
      }
   }

   /**
    * Alle Spieler auf der Linie, nach Entfernung geordnet.
    *
    * Es wird gegen die aufgeblähte Trefferbox geschnitten statt gegen einen Punkt: eine
    * Railgun soll sich anfühlen wie ein Strich über den Bildschirm, nicht wie eine Nadel.
    */
   private List<ServerPlayer> victimsAlong(ServerLevel level, ServerPlayer shooter, Vec3 from, Vec3 to) {
      List<ServerPlayer> hit = new ArrayList<>();
      for (ServerPlayer candidate : level.getEntitiesOfClass(ServerPlayer.class,
            new AABB(from, to).inflate(2.0), player -> player.isAlive() && !player.equals(shooter))) {
         if (candidate.getBoundingBox().inflate(HIT_TOLERANCE).clip(from, to).isPresent()) {
            hit.add(candidate);
         }
      }
      hit.sort(Comparator.comparingDouble(player -> player.position().distanceToSqr(from)));
      return hit;
   }

   /** Der sichtbare und spürbare Teil: Lanze, Mündungsblitz, Einschlag, Rückstoß, Erschütterung. */
   private void shoot(ServerLevel level, ServerPlayer shooter, Vec3 origin, Vec3 impact, double charge) {
      spawnBeam(level, origin, impact, charge);

      level.playSound(null, origin.x, origin.y, origin.z, SoundEvents.LIGHTNING_BOLT_IMPACT,
         SoundSource.PLAYERS, 1.6F, 1.4F);
      level.playSound(null, origin.x, origin.y, origin.z, SoundEvents.GENERIC_EXPLODE,
         SoundSource.PLAYERS, 1.2F + (float) charge, 1.7F);
      level.playSound(null, origin.x, origin.y, origin.z, SoundEvents.BEACON_DEACTIVATE,
         SoundSource.PLAYERS, 1.4F, 0.5F);
      level.playSound(null, impact.x, impact.y, impact.z, SoundEvents.TRIDENT_RETURN,
         SoundSource.PLAYERS, 1.1F, 0.8F);

      level.sendParticles(ParticleTypes.END_ROD, origin.x, origin.y, origin.z, 0,
         (impact.x - origin.x), (impact.y - origin.y), (impact.z - origin.z), 0.4);
      // FLASH ist ein ParticleType<ColorParticleOption> und braucht seine Farbe mitgeliefert.
      level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0.78F, 0.95F, 1.0F),
         impact.x, impact.y, impact.z, 1, 0.0, 0.0, 0.0, 0.0);
      spawnFlash(level, impact, shooter.getLookAngle().normalize(), charge);

      // Rückstoß: mit voller Ladung schiebt es den Schützen spürbar zurück.
      Vec3 push = shooter.getLookAngle().normalize().scale(-RECOIL * charge);
      shooter.setDeltaMovement(shooter.getDeltaMovement().add(push.x, push.y * 0.4 + 0.08, push.z));
      shooter.hurtMarked = true;
      shakeFor(shooter, charge);
   }

   private void shakeFor(ServerPlayer shooter, double charge) {
      ServerPlayNetworking.send(shooter, new ExplosionShakePayload(
         shooter.getX(), shooter.getY(), shooter.getZ(), 6.0F, (float) (0.5 + charge * 0.9), 8));
   }

   private void eliminate(ServerPlayer shooter, ServerPlayer victim) {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      if (worlds == null) {
         return;
      }
      Arena arena = worlds.getActive();
      if (worlds.arenaOf(victim) == arena) {
         DamageListener.INSTANCE.eliminate(shooter, victim, arena, KillFeed.Cause.RAILGUN);
      }
   }

   // -- Der Strahl ----------------------------------------------------------

   /**
    * Hängt die Lanze zwischen Mündung und Einschlag auf.
    *
    * Ein einziges Display trägt den ganzen Strahl: das Modell ist genau 16 Einheiten lang, die
    * Skalierung in Z ist damit unmittelbar seine Länge in Blöcken. Die Entity sitzt auf der
    * Mitte der Strecke, weil eine {@code Display} ihr Modell um die eigene Position zentriert.
    *
    * Gedreht wird die Weltachse +Z auf die Schussrichtung. Dass das trotz der halben Umdrehung
    * des Renderers stimmt, liegt an der Bauweise des Modells: seine Spitze liegt bei z = 0 und
    * damit hinter der Mitte, und die Umdrehung dreht sie nach vorn.
    */
   private void spawnBeam(ServerLevel level, Vec3 origin, Vec3 impact, double charge) {
      Vec3 delta = impact.subtract(origin);
      double length = delta.length();
      if (length < 0.1) {
         return;
      }
      Vec3 middle = origin.add(delta.scale(0.5));
      Display.ItemDisplay display = Hologram.spawnEffect(level, middle, boltStack(BOLT_HOT), VIEW_RANGE);
      if (display == null) {
         return;
      }

      Vec3 direction = delta.scale(1.0 / length);
      Quaternionf rotation = new Quaternionf().rotationTo(0.0F, 0.0F, -1.0F,
         (float) direction.x, (float) direction.y, (float) direction.z);
      double girth = BEAM_GIRTH_MIN + (BEAM_GIRTH_MAX - BEAM_GIRTH_MIN) * charge;
      float width = (float) (girth / BOLT_GIRTH_UNIT);

      // Sofort in voller Größe und richtiger Richtung, mit Interpolationsdauer null. Ohne das
      // steht beim Erscheinen die Anfangspose – ungedreht und winzig – in den Daten, und der
      // Client schwenkt den Strahl über zwei Ticks sichtbar von der Seite in die Schussrichtung.
      Hologram.setPose(display, new Vector3f(), rotation,
         new Vector3f(width, width, (float) length), 0);
      beams.add(new Beam(display, rotation, length, width));
   }

   /**
    * Der Einschlag: ein Energiering, ein weißglühender Kern und ein paar Plasmasplitter.
    *
    * Bewusst kein {@link BlastEffect}. Der baut einen Feuerball mit Rauchballen und Erdbrocken –
    * richtig für eine Sprengladung, falsch für einen Strahl, der nichts verbrennt. Hier stößt
    * stattdessen Energie zurück: der Ring steht quer zur Schussrichtung, die Splitter fahren im
    * Kegel dagegen zurück, und alles kühlt von Weiß über Blau nach Violett aus.
    */
   private void spawnFlash(ServerLevel level, Vec3 impact, Vec3 direction, double charge) {
      Flash flash = new Flash(charge);

      // Der Ring liegt in der Ebene des Modells um dessen Hochachse; sie auf die
      // Schussrichtung zu drehen stellt ihn quer zum Strahl.
      flash.ring = Hologram.spawnEffect(level, impact, ringStack(FLASH_HOT), VIEW_RANGE);
      if (flash.ring != null) {
         flash.ringRotation = new Quaternionf().rotationTo(0.0F, 1.0F, 0.0F,
            (float) direction.x, (float) direction.y, (float) direction.z);
         // Auch hier gleich die richtige Lage, sonst dreht sich der Ring beim Aufgehen mit.
         Hologram.setPose(flash.ring, new Vector3f(), flash.ringRotation,
            new Vector3f(Hologram.HIDDEN_SCALE, Hologram.HIDDEN_SCALE, Hologram.HIDDEN_SCALE), 0);
      }
      flash.core = Hologram.spawnEffect(level, impact, puffStack(FLASH_HOT), VIEW_RANGE);

      // Die Splitter fahren im Kegel gegen die Schussrichtung zurück – wie Spritzer.
      Vec3 side = Math.abs(direction.y) > 0.9 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
      Vec3 right = direction.cross(side).normalize();
      Vec3 up = right.cross(direction).normalize();
      for (int index = 0; index < FLASH_SHARDS; index++) {
         double angle = index * Math.PI * 2.0 / FLASH_SHARDS + 0.3;
         Vec3 spray = right.scale(Math.cos(angle)).add(up.scale(Math.sin(angle)))
            .add(direction.scale(-0.55)).normalize();
         Display.ItemDisplay display = Hologram.spawnEffect(level, impact, boltStack(FLASH_HOT), VIEW_RANGE);
         if (display == null) {
            continue;
         }
         Quaternionf aim = new Quaternionf().rotationTo(0.0F, 0.0F, -1.0F,
            (float) spray.x, (float) spray.y, (float) spray.z);
         Hologram.setPose(display, new Vector3f(), aim,
            new Vector3f(Hologram.HIDDEN_SCALE, Hologram.HIDDEN_SCALE, Hologram.HIDDEN_SCALE), 0);
         flash.shards.add(display);
         flash.shardDirections.add(spray);
         flash.shardRotations.add(aim);
      }

      flashes.add(flash);

      level.sendParticles(ParticleTypes.END_ROD, impact.x, impact.y, impact.z,
         (int) (24 + charge * 40), 0.25, 0.25, 0.25, 0.55);
      level.sendParticles(ParticleTypes.ELECTRIC_SPARK, impact.x, impact.y, impact.z,
         (int) (24 + charge * 36), 0.3, 0.3, 0.3, 0.5);
      level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, impact.x, impact.y, impact.z,
         (int) (14 + charge * 22), 0.2, 0.2, 0.2, 0.32);
   }

   private void tickFlashes() {
      Iterator<Flash> iterator = flashes.iterator();
      while (iterator.hasNext()) {
         Flash flash = iterator.next();
         if (flash.age++ >= FLASH_TICKS) {
            flash.dismantle();
            iterator.remove();
            continue;
         }

         double spread = 1.0 - Math.pow(1.0 - flash.age / (double) FLASH_TICKS, 2.0);
         double left = 1.0 - flash.age / (double) FLASH_TICKS;
         int colour = flash.age < FLASH_TICKS / 3
            ? blend(FLASH_COOL, FLASH_HOT, left)
            : blend(FLASH_VIOLET, FLASH_COOL, left);

         if (flash.ring != null) {
            float across = (float) (FLASH_RING_DIAMETER * (0.4 + 0.6 * flash.charge) * spread);
            float band = (float) (FLASH_RING_BAND / RING_BAND_UNIT * left);
            Hologram.setPose(flash.ring, new Vector3f(), flash.ringRotation,
               new Vector3f(across, Math.max(Hologram.HIDDEN_SCALE, band), across), 2);
            recolour(flash.ring, flash, colour, RING_SLOT);
         }
         if (flash.core != null) {
            // Der Kern blitzt auf und ist gleich wieder weg; er markiert nur den Punkt.
            double grow = Math.min(1.0, flash.age / 3.0);
            float width = (float) Math.max(Hologram.HIDDEN_SCALE,
               FLASH_CORE_WIDTH * (0.5 + 0.5 * flash.charge) * grow * Math.max(0.0, 1.0 - flash.age / 9.0));
            Hologram.setPose(flash.core, new Vector3f(), new Quaternionf(),
               new Vector3f(width, width, width), 2);
            recolour(flash.core, flash, colour, CORE_SLOT);
         }
         for (int index = 0; index < flash.shards.size(); index++) {
            Vec3 direction = flash.shardDirections.get(index);
            double reach = FLASH_SHARD_REACH * flash.charge * spread;
            float girth = (float) Math.max(Hologram.HIDDEN_SCALE, FLASH_SHARD_GIRTH / BOLT_GIRTH_UNIT * left);
            Hologram.setPose(flash.shards.get(index),
               new Vector3f((float) (direction.x * reach), (float) (direction.y * reach), (float) (direction.z * reach)),
               flash.shardRotations.get(index),
               new Vector3f(girth, girth, (float) (FLASH_SHARD_LENGTH * left)), 2);
         }
         if (colour != flash.shardColour) {
            flash.shardColour = colour;
            for (Display.ItemDisplay shard : flash.shards) {
               shard.getSlot(0).set(boltStack(colour));
            }
         }
      }
   }

   /** Setzt die Farbe eines Einzelteils, aber nur wenn sie sich wirklich geändert hat. */
   private void recolour(Display.ItemDisplay display, Flash flash, int colour, int slot) {
      if (flash.colours[slot] == colour) {
         return;
      }
      flash.colours[slot] = colour;
      display.getSlot(0).set(slot == RING_SLOT ? ringStack(colour) : puffStack(colour));
   }

   public void tick() {
      tickFlashes();
      Iterator<Beam> iterator = beams.iterator();
      while (iterator.hasNext()) {
         Beam beam = iterator.next();
         if (beam.age++ >= BEAM_TICKS) {
            Hologram.remove(beam.display);
            iterator.remove();
            continue;
         }
         // Der Strahl verglüht: er wird dünner und kühlt von Weiß nach Blau ab. Die Länge
         // bleibt, sonst zöge er sich sichtbar in die Mündung zurück.
         double left = 1.0 - beam.age / (double) BEAM_TICKS;
         float width = (float) Math.max(Hologram.HIDDEN_SCALE, beam.width * left * left);
         Hologram.setPose(beam.display, new Vector3f(), beam.rotation,
            new Vector3f(width, width, (float) beam.length), 2);
         int colour = blend(BOLT_COLD, BOLT_HOT, left);
         if (colour != beam.colour) {
            beam.colour = colour;
            beam.display.getSlot(0).set(boltStack(colour));
         }
      }
   }

   public void reset() {
      for (Beam beam : beams) {
         Hologram.remove(beam.display);
      }
      beams.clear();
      for (Flash flash : flashes) {
         flash.dismantle();
      }
      flashes.clear();
   }

   private static ItemStack boltStack(int colour) {
      return tinted(ModItems.RAILGUN_BOLT, colour);
   }

   /** Der Energiering und der Kern leihen sich die Modelle des Atompilzes – dieselbe Geometrie. */
   private static ItemStack ringStack(int colour) {
      return tinted(ModItems.BLAST_RING, colour);
   }

   private static ItemStack puffStack(int colour) {
      return tinted(ModItems.BLAST_PUFF, colour);
   }

   private static ItemStack tinted(net.minecraft.world.item.Item item, int colour) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.DYED_COLOR, new DyedItemColor(colour));
      return stack;
   }

   // -- Hilfen --------------------------------------------------------------

   /**
    * Die Mündung in der Welt.
    *
    * In der Ersten-Person-Ansicht deckt sich dieser Punkt mit den gezeichneten Schienen, weil
    * die Hand im selben Sichtfeld wie die Welt gezeichnet wird. Für Zuschauer bleibt es eine
    * Näherung – sie sehen die Waffe an der Hand, nicht vor dem Auge.
    */
   private static Vec3 muzzle(ServerPlayer shooter) {
      Vec3 eye = shooter.getEyePosition();
      Vec3 look = shooter.getLookAngle().normalize();
      Vec3 right = look.cross(WORLD_UP);
      // Beim Blick senkrecht nach oben oder unten steht kein rechter Vektor mehr zur Verfügung.
      right = right.lengthSqr() < 1.0E-4 ? new Vec3(1.0, 0.0, 0.0) : right.normalize();
      Vec3 up = right.cross(look).normalize();
      return eye.add(look.scale(MUZZLE_FORWARD)).add(right.scale(MUZZLE_RIGHT)).add(up.scale(-MUZZLE_DOWN));
   }

   private static int blend(int from, int to, double share) {
      double amount = Math.clamp(share, 0.0, 1.0);
      int result = 0;
      for (int shift = 0; shift <= 16; shift += 8) {
         int a = (from >> shift) & 0xFF;
         int b = (to >> shift) & 0xFF;
         result |= (a + (int) Math.round((b - a) * amount)) << shift;
      }
      return result & 0xF8F8F8;
   }

   /**
    * Ein Einschlag: Ring, Kern und Splitter.
    *
    * Alle Teile hängen an derselben Stelle in der Welt und bewegen sich nur über ihre Matrix –
    * genauso wie beim Atompilz, und aus demselben Grund: der Client schiebt sie von sich aus
    * weiter, und aus dem Sichtfeld geschnitten wird eine Display ohne Ausmaße nie.
    */
   private static final class Flash {
      private final double charge;
      private final List<Display.ItemDisplay> shards = new ArrayList<>();
      private final List<Vec3> shardDirections = new ArrayList<>();
      private final List<Quaternionf> shardRotations = new ArrayList<>();
      private Display.ItemDisplay ring;
      private Display.ItemDisplay core;
      private Quaternionf ringRotation = new Quaternionf();
      private final int[] colours = {-1, -1};
      private int shardColour = -1;
      private int age;

      private Flash(double charge) {
         this.charge = charge;
      }

      private void dismantle() {
         Hologram.remove(ring);
         Hologram.remove(core);
         for (Display.ItemDisplay shard : shards) {
            Hologram.remove(shard);
         }
      }
   }

   /** Eine Lanze in der Luft. Länge und Richtung stehen fest, nur Dicke und Farbe laufen ab. */
   private static final class Beam {
      private final Display.ItemDisplay display;
      private final Quaternionf rotation;
      private final double length;
      private final double width;
      private int age;
      private int colour = -1;

      private Beam(Display.ItemDisplay display, Quaternionf rotation, double length, double width) {
         this.display = display;
         this.rotation = rotation;
         this.length = length;
         this.width = width;
      }
   }
}
