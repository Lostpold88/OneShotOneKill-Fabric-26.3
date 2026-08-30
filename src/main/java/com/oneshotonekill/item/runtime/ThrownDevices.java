package com.oneshotonekill.item.runtime;

import com.oneshotonekill.shared.Hologram;
import com.oneshotonekill.shared.Feedback;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.arena.ArenaWorlds;
import com.oneshotonekill.network.OsokPayloads.*;
import com.oneshotonekill.registry.ModItems;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Geworfene Geräte: Rauchbombe, Teleport-Granate und Singularität.
 * <p>
 * Die Flugbahn wird serverseitig simuliert statt über eine Wurf-Entity abgebildet. Das gibt volle
 * Kontrolle über Bahn, Aussehen und Einschlagprüfung, ohne für drei Geräte drei Entity-Typen zu
 * registrieren – und die Bahn kann pro Gerät eine eigene Partikelspur zeichnen.
 */
@SuppressWarnings({"resource", "SameParameterValue"})
public final class ThrownDevices {
   public static final ThrownDevices INSTANCE = new ThrownDevices();

   private static final double THROW_SPEED = 1.35;
   private static final double GRAVITY = 0.045;
   private static final double DRAG = 0.99;
   private static final int MAX_FLIGHT_TICKS = 100;

   private static final double TELEPORT_PUSH_RADIUS = 5.0;
   private static final double TELEPORT_PUSH = 1.15;
   /**
    * Der Riss, den der Sprung an beiden Enden hinterlässt.
    * <p>
    * Vorher war der Sprung ein Ton und eine Handvoll Portalpartikel – wer daneben stand,
    * bemerkte kaum, dass jemand verschwunden war. Jetzt steht an der alten Stelle ein Ring, der
    * in sich zusammenfällt, und an der neuen einer, der aufreißt; dazu Splitter, die aus dem
    * Riss fahren.
    */
   private static final int WARP_TICKS = 22;
   private static final double WARP_RING_DIAMETER = 3.6;
   private static final double WARP_RING_BAND = 0.3;
   private static final int WARP_SHARDS = 6;
   private static final double WARP_SHARD_LENGTH = 1.6;
   private static final double WARP_SHARD_GIRTH = 0.16;
   private static final double WARP_SHARD_REACH = 2.0;
   private static final float WARP_VIEW_RANGE = 4.0F;
   private static final float TELEPORT_FLIGHT_SCALE = 0.42F;
   private static final int WARP_HOT = 0xF2E8FF;
   private static final int WARP_DEEP = 0x7A3CFF;
   /** Höhe des Ringmodells und Querschnitt der Lanze bei Skalierung 1, in Blöcken. */
   private static final double WARP_RING_BAND_UNIT = 0.0978;
   private static final double WARP_SHARD_GIRTH_UNIT = 0.1188;
   /**
    * Die Rauchwand.
    * <p>
    * Sie ist jetzt das ganze Gerät. Vorher versetzte die Granate ihren Werfer an eine zufällige
    * Stelle der Arena und der Rauch war Beiwerk – damit war sie eine Fluchttaste mit
    * Nebelwirkung. Jetzt bleibt jeder, wo er ist, und der Nebel muss allein tragen: dichter,
    * länger, und wer darin steht, sieht nichts.
    */
   private static final int SMOKE_DURATION_TICKS = 220;
   /** Bis die Wand steht. Kurz genug, um sie noch als Deckung im Gefecht werfen zu können. */
   private static final int SMOKE_BUILD_TICKS = 22;
   /** Am Ende löst sie sich auf, statt zu verschwinden. */
   private static final int SMOKE_FADE_TICKS = 45;
   private static final double SMOKE_RADIUS = 4.8;
   private static final double SMOKE_HEIGHT = 4.0;
   /**
    * Wolkenballen, aus denen die Wand besteht.
    * <p>
    * Partikel allein reichen nicht: sie sind halb durchsichtig und verschwinden mit dem Abstand.
    * Erst Körper machen aus dem Nebel eine Wand, durch die wirklich niemand hindurchsieht –
    * dieselben Ballen wie beim Atompilz, nur grau und träge.
    */
   private static final int SMOKE_PUFFS = 12;
   private static final double SMOKE_PUFF_WIDTH = 4.4;
   private static final float SMOKE_VIEW_RANGE = 4.0F;
   /** Größe der Dose in der Welt; das Modell ist gut vier Fünftel eines Blocks hoch. */
   private static final float SMOKE_CANISTER_SCALE = 0.42F;
   /**
    * Wie lange die Blindheit nach dem letzten Auffrischen noch anhält.
    * <p>
    * Sie wird nur alle paar Ticks erneuert, läuft aber deutlich länger – so kostet das Blenden
    * kaum Pakete, und wer aus der Wand heraustritt, sieht nach einem knappen Augenblick wieder.
    */
   private static final int SMOKE_BLIND_TICKS = 30;
   private static final int SMOKE_BLIND_REFRESH_TICKS = 10;
   private static final int SMOKE_DARK = 0x3E4348;
   private static final int SMOKE_PALE = 0x9BA1A7;
   private static final int CANISTER_COLD = 0x3A2A22;
   private static final int CANISTER_HOT = 0xFF7A3C;
   private static final int SINGULARITY_DURATION_TICKS = 100;
   private static final int SINGULARITY_COLLAPSE_TICKS = 14;
   private static final double SINGULARITY_RADIUS = 10.0;
   private static final double SINGULARITY_PULL = 0.95;
   private static final double SINGULARITY_LIFT = 1.05;
   private static final float SINGULARITY_VIEW_RANGE = 6.0F;
   /** Abstand und Stärke der Bildverzerrung für den, den es zieht. */
   private static final int SINGULARITY_WARP_TICKS = 6;
   private static final float SINGULARITY_WARP_BASE = 0.35F;
   private static final float SINGULARITY_WARP_RANGE = 1.1F;
   private static final float SINGULARITY_FLIGHT_SCALE = 0.68F;
   private static final float SINGULARITY_FIELD_SCALE = 2.65F;
   private static final DustParticleOptions VOID_DUST = new DustParticleOptions(0x713CFF, 1.45F);
   private static final DustParticleOptions HOT_DUST = new DustParticleOptions(0xE7C9FF, 0.85F);

   public enum DeviceType {
      SMOKE,
      TELEPORT,
      SINGULARITY
   }

   private final List<Projectile> inFlight = new ArrayList<>();
   private final List<Field> fields = new ArrayList<>();
   private final List<Warp> warps = new ArrayList<>();

   private ThrownDevices() {
   }

   public boolean throwDevice(ServerLevel level, ServerPlayer thrower, DeviceType type) {
      Vec3 origin = thrower.getEyePosition().add(thrower.getLookAngle().scale(0.4));
      Vec3 velocity = thrower.getLookAngle().scale(THROW_SPEED).add(0.0, 0.12, 0.0);
      Display.ItemDisplay display = null;
      if (type == DeviceType.SINGULARITY) {
         display = Hologram.spawnEffect(level, origin, new ItemStack(ModItems.SINGULARITY),
            SINGULARITY_VIEW_RANGE);
         if (display != null) {
            Hologram.setPose(display, new Vector3f(), new Quaternionf(),
               uniformScale(SINGULARITY_FLIGHT_SCALE), 1);
         }
      } else if (type == DeviceType.TELEPORT) {
         // Auch sie fliegt jetzt sichtbar mit; vorher war unterwegs nur eine Partikelspur.
         display = Hologram.spawnEffect(level, origin, warpStack(WARP_DEEP), WARP_VIEW_RANGE);
         if (display != null) {
            Hologram.setPose(display, new Vector3f(), new Quaternionf(),
               uniformScale(TELEPORT_FLIGHT_SCALE), 1);
         }
      } else if (type == DeviceType.SMOKE) {
         // Die Dose fliegt sichtbar mit und bleibt nachher liegen. Natürlich beleuchtet, denn
         // ein Blechzylinder soll nicht im Dunkeln leuchten.
         display = Hologram.spawnNaturallyLit(level, origin, canisterStack(CANISTER_COLD), SMOKE_VIEW_RANGE);
         if (display != null) {
            Hologram.setPose(display, new Vector3f(), new Quaternionf(),
               uniformScale(SMOKE_CANISTER_SCALE), 1);
         }
      }
      inFlight.add(new Projectile(thrower.getUUID(), type, origin, velocity, 0, display));

      level.playSound(null, thrower.getX(), thrower.getY(), thrower.getZ(),
         SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.9F, type == DeviceType.SINGULARITY ? 0.6F : 1.3F);
      if (type == DeviceType.SINGULARITY) {
         level.playSound(null, thrower.getX(), thrower.getY(), thrower.getZ(),
            SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS, 0.75F, 0.55F);
      }
      return true;
   }

   public void tick(MinecraftServer server) {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      ServerLevel level = worlds == null ? null : worlds.getActiveLevel();
      if (level == null) {
         reset();
         return;
      }

      tickProjectiles(server, level);
      tickFields(server, level);
      tickWarps();
   }

   public void reset() {
      inFlight.forEach(Projectile::dismantle);
      fields.forEach(Field::dismantle);
      warps.forEach(Warp::dismantle);
      inFlight.clear();
      fields.clear();
      warps.clear();
   }

   // -- Flugbahn ------------------------------------------------------------

   private void tickProjectiles(MinecraftServer server, ServerLevel level) {
      Iterator<Projectile> iterator = inFlight.iterator();
      while (iterator.hasNext()) {
         Projectile shot = iterator.next();
         Vec3 next = shot.position.add(shot.velocity);

         BlockHitResult hit = level.clip(new ClipContext(
            shot.position, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
         boolean impact = hit.getType() != HitResult.Type.MISS;
         shot.position = impact ? hit.getLocation() : next;
         shot.velocity = shot.velocity.scale(DRAG).subtract(0.0, GRAVITY, 0.0);
         shot.ticks++;

         poseProjectile(shot);
         drawTrail(level, shot);

         if (impact || shot.ticks >= MAX_FLIGHT_TICKS) {
            iterator.remove();
            land(server, level, shot);
         }
      }
   }

   /** Das echte 3D-Modell überschlägt sich sichtbar entlang derselben serverseitigen Flugbahn. */
   private void poseProjectile(Projectile shot) {
      if (shot.display == null || shot.display.isRemoved()) {
         return;
      }
      Hologram.move(shot.display, shot.position);
      if (shot.type == DeviceType.TELEPORT) {
         // Die Kreisel laufen gegeneinander: einer um die Hochachse, der ganze Körper langsam quer.
         Quaternionf spin = new Quaternionf().rotateY(shot.ticks * 0.5F).rotateZ(shot.ticks * 0.14F);
         Hologram.setPose(shot.display, new Vector3f(), spin, uniformScale(TELEPORT_FLIGHT_SCALE), 1);
         return;
      }
      if (shot.type == DeviceType.SMOKE) {
         // Die Dose überschlägt sich um ihre Querachse, wie eine geworfene Granate.
         Quaternionf tumble = new Quaternionf().rotateY(shot.ticks * 0.09F).rotateX(shot.ticks * 0.42F);
         Hologram.setPose(shot.display, new Vector3f(), tumble, uniformScale(SMOKE_CANISTER_SCALE), 1);
         return;
      }
      float pulse = SINGULARITY_FLIGHT_SCALE * (0.96F + (float) Math.sin(shot.ticks * 0.7F) * 0.04F);
      Quaternionf rotation = new Quaternionf()
         .rotateY(shot.ticks * 0.36F)
         .rotateX(shot.ticks * 0.23F)
         .rotateZ(shot.ticks * 0.17F);
      Hologram.setPose(shot.display, new Vector3f(), rotation, uniformScale(pulse), 1);
   }

   private void drawTrail(ServerLevel level, Projectile shot) {
      switch (shot.type) {
         case SMOKE -> level.sendParticles(ParticleTypes.LARGE_SMOKE,
            shot.position.x, shot.position.y, shot.position.z, 3, 0.05, 0.05, 0.05, 0.01);
         case TELEPORT -> {
            level.sendParticles(ParticleTypes.PORTAL, shot.position.x, shot.position.y, shot.position.z,
               5, 0.1, 0.1, 0.1, 0.15);
            level.sendParticles(ParticleTypes.END_ROD, shot.position.x, shot.position.y, shot.position.z,
               1, 0.0, 0.0, 0.0, 0.0);
         }
         case SINGULARITY -> {
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, shot.position.x, shot.position.y, shot.position.z,
               10, 0.13, 0.13, 0.13, 0.25);
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, shot.position.x, shot.position.y, shot.position.z,
               3, 0.08, 0.08, 0.08, 0.02);
            level.sendParticles(VOID_DUST, shot.position.x, shot.position.y, shot.position.z,
               4, 0.16, 0.16, 0.16, 0.015);
         }
      }
   }

   // -- Einschlag -----------------------------------------------------------

   private void land(MinecraftServer server, ServerLevel level, Projectile shot) {
      ServerPlayer thrower = server.getPlayerList().getPlayer(shot.owner);
      switch (shot.type) {
         case SMOKE -> openSmoke(level, shot);
         case TELEPORT -> {
            shot.dismantle();
            if (thrower != null) {
               teleportAndPush(server, level, thrower, shot.position);
            } else {
               // Ohne Werfer bleibt nur der Riss an der Einschlagstelle.
               openWarp(level, shot.position.add(0.0, 0.1, 0.0), true);
            }
         }
         case SINGULARITY -> {
            Vec3 center = shot.position.add(0.0, SINGULARITY_LIFT, 0.0);
            if (shot.display != null) {
               Hologram.move(shot.display, center);
            }
            fields.add(new Field(DeviceType.SINGULARITY, center, SINGULARITY_DURATION_TICKS,
               shot.owner, shot.display));
            openSingularity(server, level, center, thrower);
         }
      }
   }

   /**
    * Die Dose bleibt liegen und fängt an zu brennen; um sie herum baut sich die Wand auf.
    * <p>
    * Die Ballen hängen als eigene Entities an festen Stellen im Kreis und wachsen von dort aus
    * zusammen. Sie an der Wolkenmitte aufzuhängen und nur zu verschieben ginge auch, köstete
    * aber dieselbe Zahl Entities und nähme jedem Ballen seine eigene Sichtweitenprüfung.
    */
   private void openSmoke(ServerLevel level, Projectile shot) {
      Vec3 centre = shot.position.add(0.0, 0.2, 0.0);
      Field field = new Field(DeviceType.SMOKE, centre, SMOKE_DURATION_TICKS, shot.owner, shot.display);
      if (shot.display != null) {
         // Auf die Seite gelegt: eine Granate steht nach dem Wurf nicht ordentlich aufrecht.
         Hologram.move(shot.display, shot.position);
         Hologram.setPose(shot.display, new Vector3f(),
            new Quaternionf().rotateX((float) (Math.PI / 2.0)).rotateZ(level.getRandom().nextFloat() * 6.28F),
            uniformScale(SMOKE_CANISTER_SCALE), 2);
      }

      for (int index = 0; index < SMOKE_PUFFS; index++) {
         // Gleichmäßig über die Halbkugel verteilt: Höhe linear, Winkel im goldenen Schnitt.
         double height = index / (double) SMOKE_PUFFS;
         double angle = index * 2.399963;
         double reach = SMOKE_RADIUS * (0.35 + 0.65 * Math.sqrt(1.0 - height * 0.75));
         Vec3 at = centre.add(Math.cos(angle) * reach, SMOKE_HEIGHT * height * 0.85, Math.sin(angle) * reach);
         Display.ItemDisplay puff = Hologram.spawnNaturallyLit(level, at, puffStack(SMOKE_DARK), SMOKE_VIEW_RANGE);
         if (puff == null) {
            continue;
         }
         Hologram.setPose(puff, new Vector3f(),
            new Quaternionf().rotateY(level.getRandom().nextFloat() * 6.28F),
            uniformScale(Hologram.HIDDEN_SCALE), 0);
         field.puffs.add(puff);
         field.puffBulk.add(0.75 + level.getRandom().nextDouble() * 0.5);
      }
      fields.add(field);

      level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 1.5F, 0.65F);
      level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.LAVA_EXTINGUISH, SoundSource.PLAYERS, 1.2F, 0.5F);
      level.sendParticles(ParticleTypes.LARGE_SMOKE, centre.x, centre.y, centre.z, 60, 0.6, 0.3, 0.6, 0.09);
   }

   /**
    * Sucht am Einschlag einen Platz, an dem ein Spieler wirklich stehen kann.
    * <p>
    * Geprüft werden der Punkt selbst und die beiden darüber – eine Granate, die an einer Wand
    * abprallt, liegt gern einen halben Block im Boden.
    */
   private static Vec3 freeSpotNear(ServerLevel level, Vec3 target) {
      for (int step = 0; step <= 2; step++) {
         Vec3 candidate = target.add(0.0, 0.2 + step, 0.0);
         BlockPos feet = BlockPos.containing(candidate);
         if (level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir()) {
            return candidate;
         }
      }
      return null;
   }

   /**
    * Ein Riss: ein Ring und, an der Ankunftsseite, Splitter, die daraus hervorfahren.
    * <p>
    * Beide Enden benutzen dasselbe Bild in verschiedener Richtung – an der Abflugseite fällt
    * der Ring zusammen, an der Ankunftsseite reißt er auf. Das macht auf einen Blick klar, wo
    * jemand verschwunden und wo er aufgetaucht ist.
    */
   private void openWarp(ServerLevel level, Vec3 at, boolean arrival) {
      Warp warp = new Warp(arrival);
      warp.ring = Hologram.spawnEffect(level, at, warpRingStack(WARP_HOT), WARP_VIEW_RANGE);
      if (warp.ring != null) {
         Hologram.setPose(warp.ring, new Vector3f(), new Quaternionf(),
            uniformScale(Hologram.HIDDEN_SCALE), 0);
      }

      if (arrival) {
         for (int index = 0; index < WARP_SHARDS; index++) {
            double angle = index * Math.PI * 2.0 / WARP_SHARDS;
            Vec3 direction = new Vec3(Math.cos(angle) * 0.75, 0.66, Math.sin(angle) * 0.75).normalize();
            Display.ItemDisplay shard = Hologram.spawnEffect(level, at, warpShardStack(WARP_HOT), WARP_VIEW_RANGE);
            if (shard == null) {
               continue;
            }
            Quaternionf aim = new Quaternionf().rotationTo(0.0F, 0.0F, -1.0F,
               (float) direction.x, (float) direction.y, (float) direction.z);
            Hologram.setPose(shard, new Vector3f(), aim, uniformScale(Hologram.HIDDEN_SCALE), 0);
            warp.shards.add(shard);
            warp.shardAims.add(aim);
            warp.shardDirections.add(direction);
         }
      }
      warps.add(warp);

      level.sendParticles(ParticleTypes.REVERSE_PORTAL, at.x, at.y, at.z, 45, 0.4, 0.6, 0.4, 0.35);
      level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 18, 0.25, 0.4, 0.25, 0.22);
   }

   private void tickWarps() {
      Iterator<Warp> iterator = warps.iterator();
      while (iterator.hasNext()) {
         Warp warp = iterator.next();
         if (warp.age++ >= WARP_TICKS) {
            warp.dismantle();
            iterator.remove();
            continue;
         }

         double share = warp.age / (double) WARP_TICKS;
         // Ankommen heißt aufreißen, Abfliegen heißt zusammenfallen – dieselbe Kurve, umgekehrt.
         double open = warp.arrival ? 1.0 - (1.0 - share) * (1.0 - share) : 1.0 - share;
         double fade = 1.0 - share;

         if (warp.ring != null) {
            float across = (float) Math.max(Hologram.HIDDEN_SCALE, WARP_RING_DIAMETER * open);
            float band = (float) Math.max(Hologram.HIDDEN_SCALE, WARP_RING_BAND / WARP_RING_BAND_UNIT * fade);
            Hologram.setPose(warp.ring, new Vector3f(), new Quaternionf(),
               new Vector3f(across, band, across), 2);
         }
         for (int index = 0; index < warp.shards.size(); index++) {
            Vec3 direction = warp.shardDirections.get(index);
            double reach = WARP_SHARD_REACH * open;
            float girth = (float) Math.max(Hologram.HIDDEN_SCALE, WARP_SHARD_GIRTH / WARP_SHARD_GIRTH_UNIT * fade);
            Hologram.setPose(warp.shards.get(index),
               new Vector3f((float) (direction.x * reach), (float) (direction.y * reach), (float) (direction.z * reach)),
               warp.shardAims.get(index),
               new Vector3f(girth, girth, (float) Math.max(Hologram.HIDDEN_SCALE, WARP_SHARD_LENGTH * fade)), 2);
         }

         int colour = blendColour(WARP_DEEP, WARP_HOT, fade);
         if (colour != warp.tone) {
            warp.tone = colour;
            if (warp.ring != null) {
               Hologram.setItem(warp.ring, warpRingStack(colour));
            }
            for (Display.ItemDisplay shard : warp.shards) {
               Hologram.setItem(shard, warpShardStack(colour));
            }
         }
      }
   }

   private static ItemStack warpStack(int colour) {
      ItemStack stack = new ItemStack(ModItems.TELEPORT_GRENADE);
      stack.set(DataComponents.DYED_COLOR, new DyedItemColor(colour));
      return stack;
   }

   private static ItemStack warpRingStack(int colour) {
      ItemStack stack = new ItemStack(ModItems.BLAST_RING);
      stack.set(DataComponents.DYED_COLOR, new DyedItemColor(colour));
      return stack;
   }

   private static ItemStack warpShardStack(int colour) {
      ItemStack stack = new ItemStack(ModItems.RAILGUN_BOLT);
      stack.set(DataComponents.DYED_COLOR, new DyedItemColor(colour));
      return stack;
   }

   private static ItemStack canisterStack(int glow) {
      ItemStack stack = new ItemStack(ModItems.SMOKE_BOMB);
      stack.set(DataComponents.DYED_COLOR, new DyedItemColor(glow));
      return stack;
   }

   private static ItemStack puffStack(int colour) {
      ItemStack stack = new ItemStack(ModItems.BLAST_PUFF);
      stack.set(DataComponents.DYED_COLOR, new DyedItemColor(colour));
      return stack;
   }

   /**
    * Setzt den Werfer an den Einschlag und stößt alle anderen im Umkreis weg.
    * <p>
    * Der Ankunftspunkt wird geprüft, statt blind übernommen: die Granate kann an einer Decke
    * oder in einer Nische liegen bleiben, und ein Sprung mitten in einen Block setzte den Werfer
    * fest. Findet sich nichts Freies, bleibt er stehen, wo er ist – lieber kein Sprung als ein
    * Sprung in die Wand.
    */
   private void teleportAndPush(MinecraftServer server, ServerLevel level, ServerPlayer thrower, Vec3 target) {
      Vec3 landing = freeSpotNear(level, target);
      if (landing == null) {
         Feedback.actionBar(thrower, "§5✦ TELEPORT — kein Platz am Einschlag");
         level.playSound(null, target.x, target.y, target.z, SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 0.8F, 1.6F);
         return;
      }

      Vec3 origin = thrower.position();
      openWarp(level, origin.add(0.0, 1.0, 0.0), false);
      thrower.teleportTo(level, landing.x, landing.y, landing.z, Set.of(), thrower.getYRot(), thrower.getXRot(), false);
      thrower.fallDistance = 0.0;
      openWarp(level, landing.add(0.0, 1.0, 0.0), true);
      shake(server, level, landing, 12.0F, 0.7F, 8);

      // Zwei kurze Anschläge, einer je Ende. ELYTRA_FLYING lief hier vorher mit: das ist ein
      // Dauerklang, der sekundenlang weiterrauscht, während der Sprung längst vorbei ist.
      level.playSound(null, origin.x, origin.y, origin.z, SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.4F);
      level.playSound(null, target.x, target.y, target.z, SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 1.1F, 1.1F);
      level.playSound(null, target.x, target.y, target.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.9F, 1.6F);
      level.sendParticles(ParticleTypes.PORTAL, target.x, target.y + 1.0, target.z, 90, 0.6, 1.0, 0.6, 0.4);

      for (ServerPlayer other : server.getPlayerList().getPlayers()) {
         if (other.equals(thrower)) {
            continue;
         }
         Vec3 away = other.position().subtract(target);
         if (away.lengthSqr() > TELEPORT_PUSH_RADIUS * TELEPORT_PUSH_RADIUS || away.lengthSqr() < 0.0001) {
            continue;
         }
         Vec3 push = away.normalize().scale(TELEPORT_PUSH).add(0.0, 0.45, 0.0);
         other.setDeltaMovement(other.getDeltaMovement().add(push));
         other.hurtMarked = true;
      }
      Feedback.actionBar(thrower, "§d🌀 TELEPORT-GRANATE");
   }

   // -- Wirkungsfelder ------------------------------------------------------

   private void tickFields(MinecraftServer server, ServerLevel level) {
      Iterator<Field> iterator = fields.iterator();
      while (iterator.hasNext()) {
         Field field = iterator.next();
         field.ticksLeft--;
         if (field.ticksLeft <= 0) {
            if (field.type == DeviceType.SINGULARITY) {
               collapseSingularity(server, level, field);
            }
            field.dismantle();
            iterator.remove();
            continue;
         }

         if (field.type == DeviceType.SMOKE) {
            tickSmoke(server, level, field);
         } else {
            poseSingularity(field);
            pullSingularity(server, level, field);
         }
      }
   }

   /** Mehrstufiges Öffnen: Druckstoß, Partikelhülle, tiefe Klangschicht und Kameraruck. */
   private void openSingularity(MinecraftServer server, ServerLevel level, Vec3 center, ServerPlayer thrower) {
      // Der Einschlag noch einmal beim Werfer selbst. Er steht meist weit genug weg, dass der
      // Klang am Einschlagort ihn gar nicht mehr erreicht – und ausgerechnet der, der geworfen
      // hat, bekäme von seiner eigenen Singularität nichts mit.
      if (thrower != null) {
         level.playSound(null, thrower.getX(), thrower.getY(), thrower.getZ(),
            SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 1.1F, 0.5F);
         level.playSound(null, thrower.getX(), thrower.getY(), thrower.getZ(),
            SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS, 0.7F, 0.6F);
      }
      level.playSound(null, center.x, center.y, center.z,
         SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 1.55F, 0.42F);
      level.playSound(null, center.x, center.y, center.z,
         SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS, 0.9F, 0.55F);
      level.sendParticles(ParticleTypes.EXPLOSION, center.x, center.y, center.z,
         4, 0.2, 0.2, 0.2, 0.0);
      level.sendParticles(ParticleTypes.REVERSE_PORTAL, center.x, center.y, center.z,
         120, 1.6, 1.2, 1.6, 0.65);
      level.sendParticles(ParticleTypes.WITCH, center.x, center.y, center.z,
         65, 1.4, 0.75, 1.4, 0.16);
      level.sendParticles(HOT_DUST, center.x, center.y, center.z,
         55, 1.7, 1.1, 1.7, 0.08);
      shake(server, level, center, 30.0F, 1.15F, 14);
   }

   /** Der Kern wächst aus dem Wurfgerät und dreht seine drei Modellbahnen gegeneinander. */
   private void poseSingularity(Field field) {
      if (field.display == null || field.display.isRemoved()) {
         return;
      }
      int age = SINGULARITY_DURATION_TICKS - field.ticksLeft;
      float growth = Math.min(1.0F, age / 12.0F);
      float collapse = field.ticksLeft < SINGULARITY_COLLAPSE_TICKS
         ? Math.max(0.12F, field.ticksLeft / (float) SINGULARITY_COLLAPSE_TICKS)
         : 1.0F;
      float pulse = 0.965F + (float) Math.sin(age * 0.42F) * 0.035F;
      float scale = SINGULARITY_FIELD_SCALE * growth * collapse * pulse;
      Quaternionf rotation = new Quaternionf()
         .rotateY(age * 0.115F)
         .rotateX((float) Math.sin(age * 0.055F) * 0.28F)
         .rotateZ(age * -0.047F);
      Hologram.setPose(field.display, new Vector3f(), rotation, uniformScale(Math.max(0.08F, scale)), 2);
   }

   /**
    * Ein Tick Rauchwand: Ballen setzen, Dose glühen lassen, Partikel darüberlegen, blenden.
    * <p>
    * Die Wolke ist damit dreierlei zugleich – Körper für die Deckung, Partikel für die Unruhe
    * und ein Blindheitseffekt für den, der wirklich darin steht. Keines der drei allein reicht:
    * Körper flimmern nicht, Partikel decken nicht, und ein Effekt ohne Bild wäre Willkür.
    */
   private void tickSmoke(MinecraftServer server, ServerLevel level, Field field) {
      int age = SMOKE_DURATION_TICKS - field.ticksLeft;
      double build = Math.clamp(age / (double) SMOKE_BUILD_TICKS, 0.0, 1.0);
      double fade = field.ticksLeft < SMOKE_FADE_TICKS ? field.ticksLeft / (double) SMOKE_FADE_TICKS : 1.0;
      double share = build * fade;

      poseSmoke(field, age, share);
      glowCanister(field, age, fade);

      // Partikel nur als Beiwerk über den Ballen; die Dichte tragen die Körper.
      for (int index = 0; index < 8; index++) {
         double angle = level.getRandom().nextDouble() * Math.PI * 2.0;
         double radius = level.getRandom().nextDouble() * SMOKE_RADIUS * share;
         level.sendParticles(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE,
            field.center.x + Math.cos(angle) * radius,
            field.center.y + level.getRandom().nextDouble() * SMOKE_HEIGHT * share,
            field.center.z + Math.sin(angle) * radius,
            1, 0.0, 0.01, 0.0, 0.0);
      }
      // Der Strahl aus der Dose selbst – daran sieht man, woher die Wand kommt.
      if (fade >= 1.0) {
         level.sendParticles(ParticleTypes.LARGE_SMOKE,
            field.center.x, field.center.y, field.center.z, 4, 0.12, 0.06, 0.12, 0.055);
      }

      if (age % SMOKE_BLIND_REFRESH_TICKS == 0 && build >= 0.35) {
         blindInside(server, field);
      }
   }

   /** Die Ballen wachsen an ihrem Platz zusammen, drehen sich langsam und heben leicht ab. */
   private void poseSmoke(Field field, int age, double share) {
      for (int index = 0; index < field.puffs.size(); index++) {
         Display.ItemDisplay puff = field.puffs.get(index);
         if (puff.isRemoved()) {
            continue;
         }
         double bulk = field.puffBulk.get(index);
         float width = (float) Math.max(Hologram.HIDDEN_SCALE, SMOKE_PUFF_WIDTH * bulk * share);
         float lift = (float) (Math.sin(age * 0.035 + index) * 0.22 + share * 0.35);
         Hologram.setPose(puff, new Vector3f(0.0F, lift, 0.0F),
            new Quaternionf().rotateY((float) (age * 0.012 + index)).rotateX((float) (index * 0.7)),
            uniformScale(width), 3);
      }

      int colour = mixGrey(0.5 + 0.5 * Math.sin(age * 0.04));
      if (colour != field.tone) {
         field.tone = colour;
         for (Display.ItemDisplay puff : field.puffs) {
            Hologram.setItem(puff, puffStack(colour));
         }
      }
   }

   /** Die Dose glüht, solange sie brennt, und erlischt zum Schluss. */
   private void glowCanister(Field field, int age, double fade) {
      if (field.display == null || field.display.isRemoved()) {
         return;
      }
      double heat = fade * (0.65 + 0.35 * Math.sin(age * 0.3));
      int colour = blendColour(CANISTER_COLD, CANISTER_HOT, heat);
      if (colour == field.canisterGlow) {
         return;
      }
      field.canisterGlow = colour;
      Hologram.setItem(field.display, canisterStack(colour));
   }

   /**
    * Blendet jeden, dessen Augen in der Wand stecken – auch den Werfer.
    * <p>
    * Keine Ausnahme für ihn: eine Rauchwand, durch die nur einer hindurchsieht, ist keine
    * Deckung mehr, sondern ein Zielfernrohr. Wer sie wirft, wirft sie, um Sicht zu nehmen, nicht
    * um selbst welche zu haben.
    */
   private void blindInside(MinecraftServer server, Field field) {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      Arena arena = worlds == null ? null : worlds.getActive();
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         if (worlds != null && worlds.arenaOf(player) != arena) {
            continue;
         }
         Vec3 eye = player.getEyePosition();
         double deltaX = eye.x - field.center.x;
         double deltaZ = eye.z - field.center.z;
         double deltaY = eye.y - field.center.y;
         if (deltaX * deltaX + deltaZ * deltaZ > SMOKE_RADIUS * SMOKE_RADIUS
            || deltaY < -1.5 || deltaY > SMOKE_HEIGHT) {
            continue;
         }
         // Unsichtbar und ohne Symbol: der Rauch auf dem Bildschirm ist die Begründung genug.
         player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, SMOKE_BLIND_TICKS, 0, true, false, false));
      }
   }

   private static int mixGrey(double share) {
      return blendColour(SMOKE_DARK, SMOKE_PALE, share);
   }

   private static int blendColour(int from, int to, double share) {
      double amount = Math.clamp(share, 0.0, 1.0);
      int result = 0;
      for (int shift = 0; shift <= 16; shift += 8) {
         int low = (from >> shift) & 0xFF;
         int high = (to >> shift) & 0xFF;
         result |= (low + (int) Math.round((high - low) * amount)) << shift;
      }
      return result & 0xF8F8F8;
   }

   /**
    * Der Sog zieht Gegner zum Zentrum, richtet aber keinen Schaden an – die Singularität ist ein
    * Aufbau-Item. Wer währenddessen eliminiert wird, bleibt für den Rest der Laufzeit ausgenommen,
    * sonst würde ein Respawn in Reichweite sofort wieder eingesogen.
    */
   private void pullSingularity(MinecraftServer server, ServerLevel level, Field field) {
      int age = SINGULARITY_DURATION_TICKS - field.ticksLeft;
      double phase = age * 0.28;
      double opening = Math.min(1.0, age / 12.0);
      double closing = field.ticksLeft < SINGULARITY_COLLAPSE_TICKS
         ? field.ticksLeft / (double) SINGULARITY_COLLAPSE_TICKS : 1.0;
      double visualRadius = SINGULARITY_RADIUS * opening * (0.70 + closing * 0.30);

      // Sechs gekrümmte Spiralarme ziehen sichtbar bis in den Ereignishorizont.
      for (int arm = 0; arm < 6; arm++) {
         double base = phase + arm * Math.PI * 2.0 / 6.0;
         for (int step = 0; step < 9; step++) {
            double t = step / 8.0;
            double radius = visualRadius * Math.pow(1.0 - t, 0.78);
            double angle = base + t * 4.6;
            double y = field.center.y + Math.sin(angle * 1.7 + phase) * (0.45 + radius * 0.035);
            double x = field.center.x + Math.cos(angle) * radius;
            double z = field.center.z + Math.sin(angle) * radius;
            level.sendParticles((step + arm) % 3 == 0 ? ParticleTypes.WITCH : ParticleTypes.REVERSE_PORTAL,
               x, y, z, 1, 0.025, 0.025, 0.025, 0.0);
            if (step == 3 || step == 7) {
               level.sendParticles(arm % 2 == 0 ? VOID_DUST : HOT_DUST,
                  x, y, z, 1, 0.015, 0.015, 0.015, 0.0);
            }
         }
      }

      // Zwei senkrechte Linsenringe und ein dichter Kern machen das Feld dreidimensional.
      if ((age & 1) == 0) {
         for (int point = 0; point < 18; point++) {
            double angle = point * Math.PI * 2.0 / 18.0 + phase * 0.45;
            double radius = 2.5 + Math.sin(age * 0.17) * 0.25;
            level.sendParticles(point % 2 == 0 ? HOT_DUST : VOID_DUST,
               field.center.x + Math.cos(angle) * radius,
               field.center.y + Math.sin(angle) * radius,
               field.center.z + Math.sin(angle * 2.0) * 0.32,
               1, 0.01, 0.01, 0.01, 0.0);
         }
      }
      level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, field.center.x, field.center.y, field.center.z,
         5, 0.3, 0.3, 0.3, 0.015);
      level.sendParticles(ParticleTypes.SCULK_SOUL, field.center.x, field.center.y, field.center.z,
         3, 0.55, 0.55, 0.55, 0.025);

      if (age % 20 == 0) {
         float pitch = 0.52F + Math.min(0.28F, age / (float) SINGULARITY_DURATION_TICKS * 0.28F);
         level.playSound(null, field.center.x, field.center.y, field.center.z,
            SoundEvents.WARDEN_HEARTBEAT, SoundSource.PLAYERS, 1.15F, pitch);
         level.playSound(null, field.center.x, field.center.y, field.center.z,
            SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS, 0.65F, 0.45F + pitch * 0.35F);
      }

      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      Arena arena = worlds == null ? null : worlds.getActive();
      if (arena == null) {
         return;
      }

      for (ServerPlayer victim : server.getPlayerList().getPlayers()) {
         if (victim.getUUID().equals(field.owner) || field.excluded.contains(victim.getUUID())) {
            continue;
         }
         if (worlds.arenaOf(victim) != arena || !arena.isInArena(victim.getX(), victim.getY(), victim.getZ())) {
            continue;
         }
         Vec3 toCenter = field.center.subtract(victim.position());
         double distance = toCenter.length();
         if (distance > SINGULARITY_RADIUS || distance < 0.4) {
            continue;
         }
         double strength = SINGULARITY_PULL * (0.45 + 0.55 * (1.0 - distance / SINGULARITY_RADIUS));
         Vec3 horizontal = new Vec3(toCenter.x, 0.0, toCenter.z);
         Vec3 orbit = horizontal.lengthSqr() < 1.0E-5
            ? Vec3.ZERO : new Vec3(-horizontal.z, 0.0, horizontal.x).normalize().scale(0.035);
         double collapseBoost = field.ticksLeft < SINGULARITY_COLLAPSE_TICKS ? 1.35 : 1.0;
         Vec3 pull = toCenter.normalize().scale(strength * 0.19 * collapseBoost).add(orbit);
         victim.setDeltaMovement(victim.getDeltaMovement().add(pull));
         victim.hurtMarked = true;

         // Wer gezogen wird, spürt es auch: ein kurzer, dauernd erneuerter Ruck verzerrt ihm
         // das Bild, und zwar umso stärker, je näher er dem Kern kommt.
         if (age % SINGULARITY_WARP_TICKS == 0) {
            float closeness = (float) (1.0 - distance / SINGULARITY_RADIUS);
            ServerPlayNetworking.send(victim, new ExplosionShakePayload(
               victim.getX(), victim.getY(), victim.getZ(), 4.0F,
               SINGULARITY_WARP_BASE + closeness * SINGULARITY_WARP_RANGE, SINGULARITY_WARP_TICKS + 2));
         }
      }
   }

   /** Der Ereignishorizont fällt auf einen Punkt zusammen und entlädt seine sichtbare Energie. */
   private void collapseSingularity(MinecraftServer server, ServerLevel level, Field field) {
      level.playSound(null, field.center.x, field.center.y, field.center.z,
         SoundEvents.RESPAWN_ANCHOR_DEPLETE, SoundSource.PLAYERS, 1.45F, 0.48F);
      level.playSound(null, field.center.x, field.center.y, field.center.z,
         SoundEvents.WARDEN_SONIC_BOOM, SoundSource.PLAYERS, 1.25F, 1.35F);
      level.sendParticles(ParticleTypes.EXPLOSION_EMITTER,
         field.center.x, field.center.y, field.center.z, 2, 0.25, 0.25, 0.25, 0.0);
      level.sendParticles(ParticleTypes.REVERSE_PORTAL,
         field.center.x, field.center.y, field.center.z, 180, 2.8, 2.2, 2.8, 0.95);
      level.sendParticles(ParticleTypes.WITCH,
         field.center.x, field.center.y, field.center.z, 110, 2.4, 1.7, 2.4, 0.35);
      level.sendParticles(HOT_DUST,
         field.center.x, field.center.y, field.center.z, 90, 2.5, 2.0, 2.5, 0.22);
      shake(server, level, field.center, 36.0F, 1.75F, 18);
   }

   private static void shake(MinecraftServer server, ServerLevel level, Vec3 center,
                             float range, float intensity, int ticks) {
      ExplosionShakePayload payload = new ExplosionShakePayload(
         center.x, center.y, center.z, range, intensity, ticks);
      for (ServerPlayer listener : server.getPlayerList().getPlayers()) {
         if (listener.level() == level && listener.position().distanceToSqr(center) <= range * range) {
            ServerPlayNetworking.send(listener, payload);
         }
      }
   }

   private static Vector3f uniformScale(float scale) {
      return new Vector3f(scale, scale, scale);
   }

   /** Ein eliminierter Spieler wird aus allen laufenden Sogfeldern ausgetragen. */
   public void excludeFromFields(ServerPlayer player) {
      for (Field field : fields) {
         field.excluded.add(player.getUUID());
      }
   }

   private static final class Projectile {
      private final UUID owner;
      private final DeviceType type;
      private Vec3 position;
      private Vec3 velocity;
      private int ticks;
      private final Display.ItemDisplay display;

      private Projectile(UUID owner, DeviceType type, Vec3 position, Vec3 velocity, int ticks,
                         Display.ItemDisplay display) {
         this.owner = owner;
         this.type = type;
         this.position = position;
         this.velocity = velocity;
         this.ticks = ticks;
         this.display = display;
      }

      private void dismantle() {
         Hologram.remove(display);
      }
   }

   /** Ein Riss an einem Ende des Sprungs. */
   private static final class Warp {
      private final boolean arrival;
      private final List<Display.ItemDisplay> shards = new ArrayList<>();
      private final List<Quaternionf> shardAims = new ArrayList<>();
      private final List<Vec3> shardDirections = new ArrayList<>();
      private Display.ItemDisplay ring;
      private int tone = -1;
      private int age;

      private Warp(boolean arrival) {
         this.arrival = arrival;
      }

      private void dismantle() {
         Hologram.remove(ring);
         for (Display.ItemDisplay shard : shards) {
            Hologram.remove(shard);
         }
      }
   }

   private static final class Field {
      private final DeviceType type;
      private final Vec3 center;
      private final UUID owner;
      private final Set<UUID> excluded = new HashSet<>();
      private final Display.ItemDisplay display;
      /** Die Körper der Rauchwand und ihre Größenstreuung. */
      private final List<Display.ItemDisplay> puffs = new ArrayList<>();
      private final List<Double> puffBulk = new ArrayList<>();
      private int tone = -1;
      private int canisterGlow = -1;
      private int ticksLeft;

      private Field(DeviceType type, Vec3 center, int ticksLeft, UUID owner, Display.ItemDisplay display) {
         this.type = type;
         this.center = center;
         this.ticksLeft = ticksLeft;
         this.owner = owner;
         this.display = display;
      }

      private void dismantle() {
         Hologram.remove(display);
         for (Display.ItemDisplay puff : puffs) {
            Hologram.remove(puff);
         }
      }
   }
}
