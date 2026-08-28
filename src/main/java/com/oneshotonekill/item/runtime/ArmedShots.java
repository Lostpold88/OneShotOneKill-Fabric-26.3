package com.oneshotonekill.item.runtime;

import com.oneshotonekill.shared.Hologram;
import com.oneshotonekill.shared.Blast;
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Explosiv- und Kettenblitz-Schüsse samt ihrer echten 3D-Projektile und Einschlagseffekte. */
public final class ArmedShots {
   public static final ArmedShots INSTANCE = new ArmedShots();

   private static final double EXPLOSIVE_RADIUS = 7.0;
   private static final int EXPLOSIVE_CRATER_RADIUS = 4;
   private static final double EXPLOSIVE_CRATER_DEPTH = 1.0;
   private static final int EXPLOSIVE_RESTORE_TICKS = 20 * 6;

   /**
    * Die Flugbahn des eigenen Geschoßes.
    *
    * Sie ersetzt den Vanilla-Pfeil: dessen Werte werden übernommen, damit ein halb gezogener
    * Bogen weiterhin kürzer wirft als ein voll gezogener.
    */
   private static final double SHOT_GRAVITY = 0.05;
   private static final double SHOT_DRAG = 0.99;
   private static final int SHOT_MAX_TICKS = 200;
   /** Wie weit ein Geschoß neben der Mitte eines Spielers noch trifft. */
   private static final double SHOT_HIT_TOLERANCE = 0.3;

   private static final double CHAIN_RADIUS = 9.0;
   /** Erster Treffer plus bis zu zwei echte Sprünge. */
   private static final int CHAIN_MAX_TARGETS = 3;
   private static final int ARC_TICKS = 12;
   private static final int IMPACT_TICKS = 30;
   /** Ausdehnung des Blitzmodells entlang seiner Längsachse, aus dem Generator. */
   private static final double BOLT_LENGTH_UNIT = 1.0751;
   /** Hoehe des Ringbands bei Skalierung 1, aus generate_blast_3d.py. */
   private static final double RING_BAND_UNIT = 0.0978;
   private static final int LIGHTNING_GOLD = 0xFFD326;
   private static final int LIGHTNING_HOT = 0xFFF4A3;
   private static final int LIGHTNING_DEEP = 0xFF9D00;
   private static final DustParticleOptions GOLD_DUST = new DustParticleOptions(LIGHTNING_GOLD, 1.35F);
   private static final DustParticleOptions HOT_DUST = new DustParticleOptions(LIGHTNING_HOT, 0.9F);

   public enum ShotType {
      EXPLOSIVE,
      CHAIN_LIGHTNING
   }

   /** Noch nicht abgefeuerte Ladung je Spieler. */
   private final Map<UUID, ShotType> armed = new HashMap<>();
   /** Nach dem Abschuss gehört die Wirkung dem konkreten Pfeil, nicht mehr dem Spieler. */
   private final Map<UUID, FlyingShot> flying = new HashMap<>();
   private final List<LightningArc> arcs = new ArrayList<>();
   private final List<LightningImpact> impacts = new ArrayList<>();

   private ArmedShots() {
   }

   public boolean arm(ServerPlayer player, ShotType type) {
      armed.put(player.getUUID(), type);
      if (type == ShotType.EXPLOSIVE) {
         player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.TNT_PRIMED, SoundSource.PLAYERS, 1.0F, 0.82F);
         player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.PLAYERS, 0.8F, 0.65F);
      } else {
         player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.TRIDENT_THUNDER, SoundSource.PLAYERS, 0.8F, 1.55F);
         player.level().sendParticles(GOLD_DUST,
            player.getX(), player.getY() + 1.0, player.getZ(), 30, 0.35, 0.55, 0.35, 0.12);
         player.level().sendParticles(HOT_DUST,
            player.getX(), player.getY() + 1.0, player.getZ(), 12, 0.25, 0.45, 0.25, 0.06);
      }
      Feedback.actionBar(player, type == ShotType.EXPLOSIVE
         ? "§c💣 NÄCHSTER SCHUSS: TNT-SPRENGKOPF"
         : "§e⚡ NÄCHSTER SCHUSS: KETTENBLITZ");
      StatusAbilities.Broadcaster.INSTANCE.refresh(player);
      return true;
   }

   public boolean isArmed(ServerPlayer player) {
      return armed.containsKey(player.getUUID());
   }

   public String armedLabel(ServerPlayer player) {
      ShotType type = armed.get(player.getUUID());
      if (type == null) {
         return "";
      }
      return type == ShotType.EXPLOSIVE ? "explosive" : "chain_lightning";
   }

   /**
    * Nimmt einem scharf gemachten Bogenschuss den Pfeil ab und fliegt selbst weiter.
    *
    * Der Pfeil wird nicht versteckt, sondern gar nicht erst in die Welt gelassen – der Aufrufer
    * bricht sein Beitrittsereignis ab. Das ist der einzige Weg, der ohne Mixin auskommt:
    * {@code arrow.setInvisible(true)} bleibt wirkungslos, weil {@code ArrowRenderer#submit} die
    * Unsichtbarkeit gar nicht abfragt und das Modell bedingungslos einreicht. Genau deshalb flog
    * bisher neben dem Geschoß immer noch ein gewöhnlicher Pfeil her.
    *
    * Geschwindigkeit und Ort kommen vom Pfeil, damit ein halb gezogener Bogen weiterhin kürzer
    * wirft. Minigun-Projektile tragen deren Item als Waffe und können die Ladung nicht stehlen.
    *
    * @return true, wenn die Ladung übernommen wurde und der Pfeil verschwinden soll.
    */
   public boolean takeOverArrow(ServerLevel level, AbstractArrow arrow) {
      if (!(arrow.getOwner() instanceof ServerPlayer shooter)) {
         return false;
      }
      ItemStack weapon = arrow.getWeaponItem();
      if (weapon == null || !weapon.is(Items.BOW)) {
         return false;
      }
      ShotType type = armed.remove(shooter.getUUID());
      if (type == null) {
         return false;
      }

      ItemStack shown = type == ShotType.EXPLOSIVE
         ? new ItemStack(ModItems.EXPLOSIVE_SHOT)
         : lightningStack(LIGHTNING_GOLD);
      Display.ItemDisplay display = Hologram.spawnEffect(level, arrow.position(), shown, 3.0F);
      flying.put(UUID.randomUUID(),
         new FlyingShot(shooter.getUUID(), type, arrow.position(), arrow.getDeltaMovement(), display));
      StatusAbilities.Broadcaster.INSTANCE.refresh(shooter);

      if (type == ShotType.EXPLOSIVE) {
         level.playSound(null, arrow.getX(), arrow.getY(), arrow.getZ(),
            SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 1.0F, 0.72F);
      } else {
         level.playSound(null, arrow.getX(), arrow.getY(), arrow.getZ(),
            SoundEvents.TRIDENT_THROW, SoundSource.PLAYERS, 1.0F, 1.55F);
      }
      return true;
   }

   /** Wertet den Treffer des Geschoßes aus und räumt dessen 3D-Modell ab. */
   private void land(ServerLevel level, MinecraftServer server, FlyingShot shot, Vec3 impact, ServerPlayer directHit) {
      shot.dismantle();
      ServerPlayer shooter = server.getPlayerList().getPlayer(shot.shooter);
      if (shooter == null) {
         return;
      }

      if (shot.type == ShotType.EXPLOSIVE) {
         Blast.detonate(level, shooter, impact, EXPLOSIVE_RADIUS, KillFeed.Cause.EXPLOSIVE_SHOT,
            new Blast.Crater(EXPLOSIVE_CRATER_RADIUS, EXPLOSIVE_CRATER_DEPTH, EXPLOSIVE_RESTORE_TICKS));
      } else {
         strikeChain(level, shooter, impact, directHit);
      }
   }

   /** Bewegt die Display-Projektile und hält die kurzen Blitzbögen am Einschlag lebendig. */
   public void tick(MinecraftServer server) {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      ServerLevel level = worlds == null ? null : worlds.getActiveLevel();
      // Über eine Abschrift laufen, nicht über die Liste selbst.
      //
      // Der Einschlag wird seit dem Wegfall des Vanilla-Pfeils hier drin ausgewertet, und er
      // kann den Schützen selbst eliminieren – die Sprengwirkung nimmt den Auslöser
      // ausdrücklich mit. Das räumt über clearFor seine eigenen Geschoße aus derselben Liste,
      // während der Iterator darauf steht, und genau daran ist der Server abgestürzt.
      List<Map.Entry<UUID, FlyingShot>> pass = new ArrayList<>(flying.entrySet());
      List<UUID> spent = new ArrayList<>();
      for (Map.Entry<UUID, FlyingShot> entry : pass) {
         FlyingShot shot = entry.getValue();
         if (level == null || ++shot.age > SHOT_MAX_TICKS) {
            shot.dismantle();
            spent.add(entry.getKey());
            continue;
         }
         if (advance(level, server, shot)) {
            spent.add(entry.getKey());
         }
      }
      spent.forEach(flying::remove);

      Iterator<LightningArc> arcIterator = arcs.iterator();
      while (arcIterator.hasNext()) {
         LightningArc arc = arcIterator.next();
         if (arc.display == null || arc.display.isRemoved() || ++arc.age > ARC_TICKS) {
            arc.dismantle();
            arcIterator.remove();
            continue;
         }
         poseArc(arc);
      }

      Iterator<LightningImpact> impactIterator = impacts.iterator();
      while (impactIterator.hasNext()) {
         LightningImpact impact = impactIterator.next();
         if (++impact.age > IMPACT_TICKS) {
            impact.dismantle();
            impactIterator.remove();
            continue;
         }
         poseImpact(impact);
         energizeImpact(impact);
      }
   }

   /**
    * Ein Tick Flug: bewegen, auf Wand und Spieler prüfen, zeichnen.
    *
    * Geprüft wird die Strecke dieses Ticks, nicht der Zielpunkt: ein Bogenschuss legt mehrere
    * Blöcke je Tick zurück und spränge sonst über dünne Wände und schmale Gegner hinweg.
    *
    * @return true, wenn das Geschoß aufgeschlagen ist und aus der Liste soll.
    */
   private boolean advance(ServerLevel level, MinecraftServer server, FlyingShot shot) {
      Vec3 from = shot.position;
      Vec3 to = from.add(shot.velocity);

      ServerPlayer struck = null;
      double nearest = Double.MAX_VALUE;
      for (ServerPlayer candidate : level.getEntitiesOfClass(ServerPlayer.class,
            new AABB(from, to).inflate(2.0), player -> player.isAlive() && !player.getUUID().equals(shot.shooter))) {
         Vec3 entry = candidate.getBoundingBox().inflate(SHOT_HIT_TOLERANCE).clip(from, to).orElse(null);
         if (entry != null && entry.distanceToSqr(from) < nearest) {
            nearest = entry.distanceToSqr(from);
            struck = candidate;
         }
      }

      BlockHitResult wall = level.clip(new ClipContext(from, to,
         ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
      boolean hitsWall = wall.getType() != HitResult.Type.MISS;
      // Wer zuerst auf der Strecke liegt, gilt: eine Wand vor dem Gegner blockt den Schuss.
      if (hitsWall && struck != null && wall.getLocation().distanceToSqr(from) < nearest) {
         struck = null;
      }

      if (struck != null) {
         land(level, server, shot, struck.position().add(0.0, 1.0, 0.0), struck);
         return true;
      }
      if (hitsWall) {
         land(level, server, shot, wall.getLocation(), null);
         return true;
      }

      shot.position = to;
      shot.velocity = shot.velocity.scale(SHOT_DRAG).subtract(0.0, SHOT_GRAVITY, 0.0);
      poseProjectile(level, shot);
      return false;
   }

   private void poseProjectile(ServerLevel level, FlyingShot shot) {
      Vec3 motion = shot.velocity;
      if (motion.lengthSqr() < 1.0E-5) {
         return;
      }
      Vec3 direction = motion.normalize();
      Vec3 position = shot.position;
      if (shot.display != null) {
         Hologram.move(shot.display, position);
         Quaternionf aim = new Quaternionf().rotationTo(0.0F, 0.0F, 1.0F,
            (float) direction.x, (float) direction.y, (float) direction.z);
         if (shot.type == ShotType.EXPLOSIVE) {
            aim.rotateZ(shot.age * 0.24F);
            Hologram.setPose(shot.display, new Vector3f(), aim, new Vector3f(0.95F, 0.95F, 0.95F), 1);
         } else {
            float pulse = 0.78F + (float) Math.sin(shot.age * 1.7) * 0.1F;
            Hologram.setPose(shot.display, new Vector3f(), aim, new Vector3f(pulse, pulse, 1.35F), 1);
            if ((shot.age & 1) == 0) {
               shot.display.getSlot(0).set(lightningStack((shot.age & 2) == 0 ? LIGHTNING_HOT : LIGHTNING_GOLD));
            }
         }
      }

      Vec3 tail = position.subtract(direction.scale(0.55));
      if (shot.type == ShotType.EXPLOSIVE) {
         level.sendParticles(ParticleTypes.SMALL_FLAME, tail.x, tail.y, tail.z, 3, 0.05, 0.05, 0.05, 0.015);
         level.sendParticles(ParticleTypes.SMOKE, tail.x, tail.y, tail.z, 2, 0.07, 0.07, 0.07, 0.01);
      } else {
         level.sendParticles(GOLD_DUST, position.x, position.y, position.z,
            7, 0.18, 0.18, 0.18, 0.08);
         level.sendParticles(HOT_DUST, tail.x, tail.y, tail.z, 2, 0.04, 0.04, 0.04, 0.01);
         if (shot.age % 7 == 0) {
            level.playSound(null, position.x, position.y, position.z,
               SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS, 0.35F, 1.9F);
         }
      }
   }

   /** Wählt nach jedem Treffer den nächsten Gegner vom letzten Kettenpunkt aus. */
   private void strikeChain(ServerLevel level, ServerPlayer shooter, Vec3 impact, ServerPlayer directHit) {
      MinecraftServer server = level.getServer();
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      if (server == null || worlds == null) {
         return;
      }

      Arena arena = worlds.getActive();
      List<ServerPlayer> candidates = new ArrayList<>();
      for (ServerPlayer candidate : server.getPlayerList().getPlayers()) {
         if (!candidate.equals(shooter) && candidate.isAlive() && !candidate.isSpectator()
            && worlds.arenaOf(candidate) == arena) {
            candidates.add(candidate);
         }
      }

      Vec3 previous = impact;
      ServerPlayer forced = directHit != null && candidates.contains(directHit) ? directHit : null;
      int hits = 0;
      while (hits < CHAIN_MAX_TARGETS) {
         ServerPlayer victim;
         if (forced != null) {
            victim = forced;
            forced = null;
         } else {
            Vec3 origin = previous;
            victim = candidates.stream()
               .filter(candidate -> candidate.position().add(0.0, 1.0, 0.0).distanceToSqr(origin)
                  <= CHAIN_RADIUS * CHAIN_RADIUS)
               .min(Comparator.comparingDouble(candidate ->
                  candidate.position().add(0.0, 1.0, 0.0).distanceToSqr(origin)))
               .orElse(null);
         }
         if (victim == null) {
            break;
         }
         candidates.remove(victim);

         Vec3 target = victim.position().add(0.0, 1.0, 0.0);
         spawnArc(level, previous, target);
         lightningBurst(level, target, hits);
         previous = target;
         hits++;
         DamageListener.INSTANCE.eliminate(shooter, victim, arena, KillFeed.Cause.CHAIN_LIGHTNING);
      }

      if (hits == 0) {
         spawnArc(level, impact.add(0.0, 3.5, 0.0), impact);
         lightningBurst(level, impact, 0);
      }
      level.playSound(null, impact.x, impact.y, impact.z,
         SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 1.0F, 1.35F);
   }

   private void spawnArc(ServerLevel level, Vec3 from, Vec3 to) {
      Vec3 delta = to.subtract(from);
      double length = delta.length();
      if (length < 0.1) {
         return;
      }
      Vec3 middle = from.add(to).scale(0.5);
      Display.ItemDisplay display = Hologram.spawnEffect(level, middle, lightningStack(LIGHTNING_HOT), 5.0F);
      LightningArc arc = new LightningArc(from, to, display);
      arcs.add(arc);
      poseArc(arc);

      int steps = Math.max(5, (int) Math.ceil(length * 2.0));
      for (int step = 0; step <= steps; step++) {
         double share = step / (double) steps;
         Vec3 point = from.add(delta.scale(share));
         level.sendParticles(GOLD_DUST, point.x, point.y, point.z,
            3, 0.08, 0.08, 0.08, 0.04);
         level.sendParticles(HOT_DUST, point.x, point.y, point.z,
            1, 0.035, 0.035, 0.035, 0.01);
      }
   }

   private void poseArc(LightningArc arc) {
      if (arc.display == null) {
         return;
      }
      Vec3 delta = arc.to.subtract(arc.from);
      double length = delta.length();
      Vec3 direction = delta.scale(1.0 / length);
      Quaternionf aim = new Quaternionf().rotationTo(0.0F, 0.0F, 1.0F,
         (float) direction.x, (float) direction.y, (float) direction.z);
      float fade = 1.0F - arc.age / (float) ARC_TICKS;
      float width = Math.max(Hologram.HIDDEN_SCALE, (0.62F + (float) Math.sin(arc.age * 2.4) * 0.12F) * fade);
      Hologram.setPose(arc.display, new Vector3f(), aim,
         new Vector3f(width, width, (float) (length / BOLT_LENGTH_UNIT)), 1);
      arc.display.getSlot(0).set(lightningStack((arc.age & 1) == 0 ? LIGHTNING_HOT : LIGHTNING_GOLD));
   }

   private void lightningBurst(ServerLevel level, Vec3 target, int jump) {
      spawnImpact(level, target);
      level.playSound(null, target.x, target.y, target.z,
         SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.PLAYERS, 1.7F, 1.18F + jump * 0.1F);
      level.playSound(null, target.x, target.y, target.z,
         SoundEvents.TRIDENT_THUNDER, SoundSource.PLAYERS, 1.15F, 1.55F + jump * 0.08F);
      level.playSound(null, target.x, target.y, target.z,
         SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.PLAYERS, 0.85F, 1.65F);
      level.sendParticles(GOLD_DUST, target.x, target.y, target.z,
         150, 0.85, 1.15, 0.85, 0.55);
      level.sendParticles(HOT_DUST, target.x, target.y, target.z,
         80, 0.55, 0.9, 0.55, 0.34);
      level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 1.0F, 0.82F, 0.12F),
         target.x, target.y, target.z, 4, 0.12, 0.12, 0.12, 0.0);

      // Zwoelf sichtbare Bodenentladungen machen den Treffer auch aus der Entfernung lesbar.
      for (int ray = 0; ray < 12; ray++) {
         double angle = ray * Math.PI * 2.0 / 12.0 + jump * 0.19;
         for (int step = 1; step <= 8; step++) {
            double reach = step * 0.43;
            Vec3 spark = target.add(Math.cos(angle) * reach, 0.08 + (step & 1) * 0.08,
               Math.sin(angle) * reach);
            level.sendParticles(step % 3 == 0 ? HOT_DUST : GOLD_DUST,
               spark.x, spark.y, spark.z, 2, 0.035, 0.035, 0.035, 0.015);
         }
      }

      if (jump == 0) {
         ExplosionShakePayload shake = new ExplosionShakePayload(target.x, target.y, target.z,
            22.0F, 1.0F, 9);
         for (ServerPlayer listener : level.getServer().getPlayerList().getPlayers()) {
            if (listener.level() == level && listener.position().distanceToSqr(target) <= 22.0 * 22.0) {
               ServerPlayNetworking.send(listener, shake);
            }
         }
      }
   }

   /** Baut einen mehrstufigen Einschlag aus Blitzsaeule, Auslaeufern, Doppelring und Kern. */
   private void spawnImpact(ServerLevel level, Vec3 target) {
      double skyY = Math.max(target.y + 1.0, level.getMaxY() - 2.0);
      LightningImpact impact = new LightningImpact(level, target, skyY);
      impact.ring = Hologram.spawnEffect(level, target.add(0.0, 0.08, 0.0),
         tinted(ModItems.BLAST_RING, LIGHTNING_GOLD), 6.0F);
      impact.echoRing = Hologram.spawnEffect(level, target.add(0.0, 0.13, 0.0),
         tinted(ModItems.BLAST_RING, LIGHTNING_HOT), 6.0F);
      impact.core = Hologram.spawnEffect(level, target.add(0.0, 0.3, 0.0),
         tinted(ModItems.BLAST_PUFF, LIGHTNING_HOT), 6.0F);

      // Eine einzige durchgehende, gezackte 3D-Saeule reicht vom Treffer bis knapp unter die
      // Baugrenze. Weil nur ein Display gestreckt wird, bleibt das auch bei 200+ Bloecken Hoehe
      // erheblich guenstiger als eine Partikelsaeule aus Hunderten Einzelpunkten.
      addImpactBolt(level, impact, target.add(0.0, 0.15, 0.0),
         new Vec3(target.x, skyY, target.z), 2.55F, 0);
      // Vier volle Nebenstränge fächern sich erst nach oben auf. So ist die Entladung am
      // Einschlag kompakt, wird zum Himmel hin aber mehrere Blöcke breit statt zu einer Linie.
      for (int strand = 0; strand < 4; strand++) {
         double angle = strand * Math.PI * 0.5 + 0.35;
         double spread = 3.8;
         Vec3 skyEnd = new Vec3(target.x + Math.cos(angle) * spread, skyY,
            target.z + Math.sin(angle) * spread);
         addImpactBolt(level, impact, target.add(0.0, 0.25, 0.0), skyEnd, 1.35F, strand & 1);
      }
      for (int index = 0; index < 10; index++) {
         double angle = index * Math.PI * 2.0 / 10.0 + 0.17;
         double reach = 3.3 + (index % 3) * 0.45;
         Vec3 end = target.add(Math.cos(angle) * reach, 0.28 + (index % 2) * 0.42,
            Math.sin(angle) * reach);
         addImpactBolt(level, impact, target.add(0.0, 0.24, 0.0), end, 0.58F, index % 3);
      }
      // Gegabelte Entladungen oberhalb des Kerns lassen die Saeule wie einen echten Einschlag
      // aus dem Himmel wirken statt wie einen geraden Lichtstab.
      Vec3 crown = target.add(0.0, 6.7, 0.0);
      for (int index = 0; index < 4; index++) {
         double angle = index * Math.PI * 0.5 + 0.4;
         Vec3 end = crown.add(Math.cos(angle) * 2.0, 2.1 + (index & 1) * 0.7,
            Math.sin(angle) * 2.0);
         addImpactBolt(level, impact, crown, end, 0.48F, index % 2);
      }
      impacts.add(impact);
      poseImpact(impact);
      energizeImpact(impact);
   }

   private void addImpactBolt(ServerLevel level, LightningImpact impact, Vec3 from, Vec3 to,
                              float width, int delay) {
      Vec3 delta = to.subtract(from);
      double length = delta.length();
      if (length < 0.1) {
         return;
      }
      Vec3 middle = from.add(to).scale(0.5);
      Display.ItemDisplay display = Hologram.spawnEffect(level, middle, lightningStack(LIGHTNING_HOT), 6.0F);
      if (display == null) {
         return;
      }
      Vec3 direction = delta.scale(1.0 / length);
      Quaternionf rotation = new Quaternionf().rotationTo(0.0F, 0.0F, 1.0F,
         (float) direction.x, (float) direction.y, (float) direction.z);
      Hologram.setPose(display, new Vector3f(), rotation,
         new Vector3f(Hologram.HIDDEN_SCALE, Hologram.HIDDEN_SCALE, Hologram.HIDDEN_SCALE), 0);
      impact.bolts.add(new ImpactBolt(display, rotation, length, width, delay));
   }

   private void poseImpact(LightningImpact impact) {
      double progress = impact.age / (double) IMPACT_TICKS;
      double eased = 1.0 - Math.pow(1.0 - progress, 3.0);
      double left = Math.max(0.0, 1.0 - progress);
      int colour = impact.age < 5 ? LIGHTNING_HOT
         : (impact.age & 2) == 0 ? LIGHTNING_GOLD : LIGHTNING_DEEP;

      if (impact.ring != null) {
         float across = (float) (8.5 * eased);
         float band = (float) Math.max(Hologram.HIDDEN_SCALE, 0.34 / RING_BAND_UNIT * left);
         Hologram.setPose(impact.ring, new Vector3f(), new Quaternionf(),
            new Vector3f(across, band, across), 1);
         impact.ring.getSlot(0).set(tinted(ModItems.BLAST_RING, colour));
      }
      if (impact.echoRing != null) {
         double delayed = Math.clamp((impact.age - 3.0) / (IMPACT_TICKS - 3.0), 0.0, 1.0);
         float across = (float) (6.3 * (1.0 - Math.pow(1.0 - delayed, 2.0)));
         float band = (float) Math.max(Hologram.HIDDEN_SCALE, 0.22 / RING_BAND_UNIT * (1.0 - delayed));
         Hologram.setPose(impact.echoRing, new Vector3f(), new Quaternionf(),
            new Vector3f(Math.max(Hologram.HIDDEN_SCALE, across), band,
               Math.max(Hologram.HIDDEN_SCALE, across)), 1);
      }
      if (impact.core != null) {
         double flash = Math.min(1.0, impact.age / 2.0) * Math.max(0.0, 1.0 - progress * 1.35);
         float size = (float) Math.max(Hologram.HIDDEN_SCALE, 2.8 * flash);
         Hologram.setPose(impact.core, new Vector3f(), new Quaternionf(), new Vector3f(size, size, size), 1);
         impact.core.getSlot(0).set(tinted(ModItems.BLAST_PUFF, colour));
      }

      for (ImpactBolt bolt : impact.bolts) {
         double life = Math.clamp((impact.age - bolt.delay) / (double) (IMPACT_TICKS - bolt.delay), 0.0, 1.0);
         double appear = Math.min(1.0, Math.max(0.0, impact.age - bolt.delay) / 2.0);
         double flicker = 0.78 + Math.sin((impact.age + bolt.delay) * 2.7) * 0.22;
         // Die Himmels-Saeule (Breite > 1) bleibt fast die gesamte Laufzeit dominant stehen;
         // die Bodenarme zucken dagegen schnell aus und vergluehen.
         double fade = bolt.width > 1.0F ? Math.pow(1.0 - life, 0.32) : 1.0 - life;
         float width = (float) Math.max(Hologram.HIDDEN_SCALE,
            bolt.width * appear * fade * flicker);
         Hologram.setPose(bolt.display, new Vector3f(), bolt.rotation,
            new Vector3f(width, width, (float) (bolt.length / BOLT_LENGTH_UNIT * appear)), 1);
         bolt.display.getSlot(0).set(lightningStack(colour));
      }
   }

   /** Gelbe Ladungsteilchen kriechen waehrend des gesamten Einschlags an Saeule und Boden. */
   private void energizeImpact(LightningImpact impact) {
      if (impact.age > IMPACT_TICKS - 3) {
         return;
      }
      double height = Math.max(1.0, impact.skyY - impact.center.y);
      for (int index = 0; index < 14; index++) {
         double share = (index / 14.0 + impact.age * 0.071) % 1.0;
         double angle = index * 2.399 + impact.age * 0.53;
         // Der Partikelstrom folgt derselben Trichterform wie die neuen Nebenstränge: unten
         // gebündelt, oben breit und wild elektrisch aufgeladen.
         double radius = 0.22 + share * 3.15 + (index % 4) * 0.11;
         Vec3 spark = impact.center.add(Math.cos(angle) * radius, height * share,
            Math.sin(angle) * radius);
         impact.level.sendParticles(index % 4 == 0 ? HOT_DUST : GOLD_DUST,
            spark.x, spark.y, spark.z, 2, 0.045, 0.12, 0.045, 0.025);
      }

      // Pulsierende Kriechstroeme rund um den Fuss der Saeule.
      for (int index = 0; index < 10; index++) {
         double angle = index * Math.PI * 0.2 + impact.age * 0.31;
         double radius = 0.5 + (impact.age % 8) * 0.42;
         Vec3 spark = impact.center.add(Math.cos(angle) * radius, 0.08,
            Math.sin(angle) * radius);
         impact.level.sendParticles((index & 2) == 0 ? GOLD_DUST : HOT_DUST,
            spark.x, spark.y, spark.z, 2, 0.05, 0.04, 0.05, 0.04);
      }
   }

   private static ItemStack lightningStack(int colour) {
      return tinted(ModItems.CHAIN_LIGHTNING_BOLT, colour);
   }

   private static ItemStack tinted(net.minecraft.world.item.Item item, int colour) {
      ItemStack stack = new ItemStack(item);
      stack.set(DataComponents.DYED_COLOR, new DyedItemColor(colour));
      return stack;
   }

   public void clearFor(ServerPlayer player) {
      armed.remove(player.getUUID());
      flying.values().removeIf(shot -> {
         if (!shot.shooter.equals(player.getUUID())) {
            return false;
         }
         shot.dismantle();
         return true;
      });
   }

   public void reset() {
      armed.clear();
      flying.values().forEach(FlyingShot::dismantle);
      flying.clear();
      arcs.forEach(LightningArc::dismantle);
      arcs.clear();
      impacts.forEach(LightningImpact::dismantle);
      impacts.clear();
   }

   /** Ein Geschoß in der Luft. Ort und Geschwindigkeit führt es selbst – es gibt keinen Pfeil mehr. */
   private static final class FlyingShot {
      private final UUID shooter;
      private final ShotType type;
      private final Display.ItemDisplay display;
      private Vec3 position;
      private Vec3 velocity;
      private int age;

      private FlyingShot(UUID shooter, ShotType type, Vec3 position, Vec3 velocity, Display.ItemDisplay display) {
         this.shooter = shooter;
         this.type = type;
         this.position = position;
         this.velocity = velocity;
         this.display = display;
      }

      private void dismantle() {
         Hologram.remove(display);
      }
   }

   private static final class LightningArc {
      private final Vec3 from;
      private final Vec3 to;
      private final Display.ItemDisplay display;
      private int age;

      private LightningArc(Vec3 from, Vec3 to, Display.ItemDisplay display) {
         this.from = from;
         this.to = to;
         this.display = display;
      }

      private void dismantle() {
         Hologram.remove(display);
      }
   }

   private static final class LightningImpact {
      private final ServerLevel level;
      private final Vec3 center;
      private final double skyY;
      private final List<ImpactBolt> bolts = new ArrayList<>();
      private Display.ItemDisplay ring;
      private Display.ItemDisplay echoRing;
      private Display.ItemDisplay core;
      private int age;

      private LightningImpact(ServerLevel level, Vec3 center, double skyY) {
         this.level = level;
         this.center = center;
         this.skyY = skyY;
      }

      private void dismantle() {
         Hologram.remove(ring);
         Hologram.remove(echoRing);
         Hologram.remove(core);
         for (ImpactBolt bolt : bolts) {
            Hologram.remove(bolt.display);
         }
      }
   }

   private record ImpactBolt(Display.ItemDisplay display, Quaternionf rotation, double length,
                             float width, int delay) {
   }
}
