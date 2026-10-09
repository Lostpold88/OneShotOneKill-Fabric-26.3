package com.oneshotonekill.item.runtime;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.ArenaWorlds;
import com.oneshotonekill.registry.ModItems;
import com.oneshotonekill.shared.Feedback;
import com.oneshotonekill.shared.Hologram;
import com.oneshotonekill.shared.PhaseFields;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Phasen-Granate: eine Kugel, in der der Werfer durch Blöcke geht und schießt.
 * <p>
 * Drei Bausteine tragen sie:
 * <ul>
 *   <li><b>Kollision</b> – {@link PhaseFields} und {@code PhaseCollisionMixin} nehmen dem Werfer
 *       (und seinen Geschossen und Wurfgeräten) die Kollision der Blöcke in der Kugel. Server und
 *       Client führen dieselbe Kugel, sonst gäbe es Rückschnellen.</li>
 *   <li><b>Durchsicht</b> – der Client zeichnet die Blöcke der Kugel halbtransparent
 *       ({@code PhaseSectionCompilerMixin}). Es bleiben dieselben Blöcke mit derselben Textur;
 *       Gegner sehen hindurch, kommen aber nicht hindurch.</li>
 *   <li><b>Ausstieg</b> – steckt der Werfer beim Ablauf in einem Block, kehrt er zur letzten
 *       freien Position zurück.</li>
 * </ul>
 * Dazu kommen die Effekte: Druckwelle beim Aufschlag, drei gegenläufige Ringe auf der Kugelhaut,
 * ein Summen, Wirbel beim Betreten und Verlassen, Funken, wo der Werfer im Gestein steckt, und
 * ein Zusammenstürzen der Haut zum Ende. Die Bildschirmanzeige liegt im Client
 * ({@code PhaseHudLayer}).
 */
@SuppressWarnings("resource")
public final class PhaseFieldSystem {
   public static final PhaseFieldSystem INSTANCE = new PhaseFieldSystem();

   private static final double THROW_SPEED = 1.35;
   private static final double GRAVITY = 0.045;
   private static final double DRAG = 0.99;
   private static final int MAX_FLIGHT_TICKS = 100;

   private static final double RADIUS = 5.0;
   /** 15 Sekunden. */
   private static final int DURATION_TICKS = 300;
   /** Die letzten drei Sekunden, in denen der Takt schneller wird und der Werfer die Kugel verlassen soll. */
   private static final int WARNING_TICKS = 60;
   /** Dauer der Druckwelle beim Aufschlag. */
   private static final int SHOCKWAVE_TICKS = 14;
   private static final float VIEW_RANGE = 4.0F;
   private static final float SCALE = 0.55F;
   private static final int LED_COLD = 0x0B4A58;
   private static final int LED_LIT = 0x3CF0FF;
   private static final int LED_WARN = 0xFF4A3C;
   private static final DustParticleOptions SHELL_DUST = new DustParticleOptions(0x3CF0FF, 0.9F);
   private static final DustParticleOptions SHELL_DUST_WARN = new DustParticleOptions(0xFF4A3C, 1.1F);
   private static final double GOLDEN_ANGLE = 2.399963;
   private static final int RING_POINTS = 22;
   private static final int IMPLODE_POINTS = 40;

   /**
    * Client-Spiegel einer Kugel; {@code radius <= 0} löscht sie.
    * <p>
    * Ohne dieses Paket wüsste der Client nicht, dass der Werfer durch Wände darf, und
    * die Bewegungsvorhersage risse ihn an jeder Wand zurück. {@code ticks} ist die Restdauer für
    * die Bildschirmanzeige.
    */
   public record Sync(UUID owner, double x, double y, double z, float radius, int ticks)
      implements CustomPacketPayload {
      public static final Type<Sync> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("phase_field"));
      public static final StreamCodec<ByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
         UUIDUtil.STREAM_CODEC, Sync::owner,
         ByteBufCodecs.DOUBLE, Sync::x,
         ByteBufCodecs.DOUBLE, Sync::y,
         ByteBufCodecs.DOUBLE, Sync::z,
         ByteBufCodecs.FLOAT, Sync::radius,
         ByteBufCodecs.VAR_INT, Sync::ticks,
         Sync::new);

      @Override
      public Type<Sync> type() {
         return TYPE;
      }
   }

   private final List<Flight> flights = new ArrayList<>();
   private final List<Field> fields = new ArrayList<>();

   private PhaseFieldSystem() {
   }

   public boolean throwGrenade(ServerLevel level, ServerPlayer thrower) {
      Vec3 origin = thrower.getEyePosition().add(thrower.getLookAngle().scale(0.4));
      Vec3 velocity = thrower.getLookAngle().scale(THROW_SPEED).add(0.0, 0.12, 0.0);
      Display.ItemDisplay display = Hologram.spawnEffect(level, origin, new ItemStack(ModItems.PHASE_GRENADE), VIEW_RANGE);
      if (display != null) {
         Hologram.setPose(display, new Vector3f(), new Quaternionf(), uniform(SCALE), 1);
      }
      flights.add(new Flight(thrower.getUUID(), origin, velocity, display));
      level.playSound(null, thrower.getX(), thrower.getY(), thrower.getZ(),
         SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.9F, 1.5F);
      return true;
   }

   public void tick(MinecraftServer server) {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      ServerLevel level = worlds == null ? null : worlds.getActiveLevel();
      if (level == null) {
         reset(server);
         return;
      }
      tickFlights(server, level);
      tickFields(server, level);
   }

   public void reset(MinecraftServer server) {
      flights.forEach(flight -> Hologram.remove(flight.display));
      flights.clear();
      for (Field field : List.copyOf(fields)) {
         end(server, field);
      }
      fields.clear();
      PhaseFields.SERVER.clear();
   }

   // -- Flug ----------------------------------------------------------------

   private void tickFlights(MinecraftServer server, ServerLevel level) {
      Iterator<Flight> iterator = flights.iterator();
      while (iterator.hasNext()) {
         Flight shot = iterator.next();
         Vec3 next = shot.position.add(shot.velocity);
         // Auch die Granate selbst fliegt durch eine laufende Kugel ihres Werfers.
         BlockHitResult hit = PhaseFields.clipThrown(level, shot.owner, shot.position, next, ClipContext.Fluid.NONE);
         boolean impact = hit.getType() != HitResult.Type.MISS;
         shot.position = impact ? hit.getLocation() : next;
         shot.velocity = shot.velocity.scale(DRAG).subtract(0.0, GRAVITY, 0.0);
         shot.ticks++;

         if (shot.display != null && !shot.display.isRemoved()) {
            Hologram.moveLeading(shot.display, shot.position, shot.velocity);
            Hologram.setPose(shot.display, new Vector3f(),
               new Quaternionf().rotateY(shot.ticks * 0.5F).rotateZ(shot.ticks * 0.21F), uniform(SCALE), 1);
            shot.led = Hologram.tint(shot.display, ModItems.PHASE_GRENADE, shot.led,
               shot.ticks % 4 < 2 ? LED_LIT : LED_COLD);
         }
         level.sendParticles(SHELL_DUST, shot.position.x, shot.position.y, shot.position.z,
            2, 0.12, 0.12, 0.12, 0.0);

         if (impact || shot.ticks >= MAX_FLIGHT_TICKS) {
            iterator.remove();
            land(server, level, shot);
         }
      }
   }

   private void land(MinecraftServer server, ServerLevel level, Flight shot) {
      ServerPlayer owner = server.getPlayerList().getPlayer(shot.owner);
      if (owner == null || !owner.isAlive()) {
         Hologram.remove(shot.display);
         return;
      }
      // Pro Werfer nur eine Kugel: die neue ersetzt die alte.
      for (Field old : List.copyOf(fields)) {
         if (old.owner.equals(shot.owner)) {
            end(server, old);
            fields.remove(old);
         }
      }

      Vec3 centre = shot.position.add(0.0, 0.4, 0.0);
      Field field = new Field(shot.owner, level, centre, shot.display, owner.position());
      for (ServerPlayer player : level.players()) {
         if (isInside(field, player)) {
            field.inside.add(player.getUUID());
         }
      }
      fields.add(field);
      if (shot.display != null) {
         Hologram.move(shot.display, shot.position.add(0.0, 0.15, 0.0));
      }

      PhaseFields.SERVER.put(new PhaseFields.Zone(shot.owner, centre, RADIUS, level.getGameTime() + DURATION_TICKS));
      broadcast(level, new Sync(shot.owner, centre.x, centre.y, centre.z, (float) RADIUS, DURATION_TICKS));

      // Aufschlag: Blitz, Funkenstoß nach allen Seiten und ein tiefer Ton, der in ein Summen übergeht.
      level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0.6F, 0.95F, 1.0F),
         centre.x, centre.y, centre.z, 1, 0, 0, 0, 0);
      level.sendParticles(ParticleTypes.ELECTRIC_SPARK, centre.x, centre.y, centre.z, 40, 0.3, 0.3, 0.3, 0.6);
      level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.3F, 1.6F);
      level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.5F, 0.5F);
      level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS, 0.9F, 1.7F);
   }

   // -- Kugel ---------------------------------------------------------------

   private void tickFields(MinecraftServer server, ServerLevel level) {
      for (Field field : List.copyOf(fields)) {
         ServerPlayer owner = server.getPlayerList().getPlayer(field.owner);
         field.ticksLeft--;
         if (field.ticksLeft <= 0 || owner == null || !owner.isAlive() || owner.level() != field.level) {
            end(server, field);
            fields.remove(field);
            continue;
         }

         // Solange der Werfer in freier Luft steht, ist das sein Rückzugspunkt.
         if (field.level.noCollision(null, owner.getBoundingBox())) {
            field.safe = owner.position();
         }

         boolean warning = field.ticksLeft <= WARNING_TICKS;
         int age = DURATION_TICKS - field.ticksLeft;
         if (age < SHOCKWAVE_TICKS) {
            drawShockwave(field, age);
         }
         drawShell(field, age, warning);
         hum(field, age, warning);
         trackCrossings(field);
         sparkInRock(field, owner, age);

         if (field.display != null && !field.display.isRemoved()) {
            float pulse = SCALE * (1.0F + 0.09F * (float) Math.sin(age * (warning ? 0.9 : 0.3)));
            Hologram.setPose(field.display, new Vector3f(),
               new Quaternionf().rotateY(age * (warning ? 0.35F : 0.12F)), uniform(pulse), 1);
            int period = warning ? 3 : 10;
            field.led = Hologram.tint(field.display, ModItems.PHASE_GRENADE, field.led,
               age % period < Math.max(1, period / 2) ? (warning ? LED_WARN : LED_LIT) : LED_COLD);
         }
         if (age % 10 == 0) {
            Feedback.actionBar(owner, Component.translatable(warning
               ? "actionbar.oneshotonekill.phase_ending" : "actionbar.oneshotonekill.phase_active",
               String.format("%.1f", field.ticksLeft / 20.0)));
         }
         if (warning && field.ticksLeft % 10 == 0) {
            field.level.playSound(null, owner.getX(), owner.getY(), owner.getZ(),
               SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.PLAYERS, 1.0F,
               0.8F + (WARNING_TICKS - field.ticksLeft) * 0.015F);
         }
      }
   }

   /** Ein Ring läuft vom Mittelpunkt in Bodenhöhe nach außen – das Zeichen, dass hier ab jetzt andere Regeln gelten. */
   private void drawShockwave(Field field, int age) {
      double reach = RADIUS * (age + 1) / SHOCKWAVE_TICKS;
      for (int index = 0; index < 24; index++) {
         double angle = index * Math.PI * 2.0 / 24.0;
         field.level.sendParticles(SHELL_DUST,
            field.centre.x + Math.cos(angle) * reach, field.centre.y - 0.3, field.centre.z + Math.sin(angle) * reach,
            1, 0.0, 0.0, 0.0, 0.0);
      }
   }

   /**
    * Die Haut: drei Ringe auf verschiedenen Breitengraden, die gegeneinander laufen. In der
    * Warnphase werden sie rot, flackern und Funken springen über die Kugel.
    */
   private void drawShell(Field field, int age, boolean warning) {
      if (age % 2 != 0 || warning && (age / 2) % 3 == 0) {
         return;
      }
      DustParticleOptions dust = warning ? SHELL_DUST_WARN : SHELL_DUST;
      drawRing(field, -0.6, age * 0.07, dust);
      drawRing(field, 0.0, -age * 0.05, dust);
      drawRing(field, 0.6, age * 0.09, dust);
      if (!warning) {
         return;
      }
      for (int spark = 0; spark < 2; spark++) {
         double height = field.level.getRandom().nextDouble() * 2.0 - 1.0;
         double ring = Math.sqrt(1.0 - height * height);
         double angle = field.level.getRandom().nextDouble() * Math.PI * 2.0 + age * GOLDEN_ANGLE;
         field.level.sendParticles(ParticleTypes.FLAME,
            field.centre.x + Math.cos(angle) * ring * RADIUS, field.centre.y + height * RADIUS,
            field.centre.z + Math.sin(angle) * ring * RADIUS, 1, 0.0, 0.0, 0.0, 0.0);
      }
   }

   private void drawRing(Field field, double heightShare, double phase, DustParticleOptions dust) {
      double y = field.centre.y + heightShare * RADIUS;
      double reach = Math.sqrt(1.0 - heightShare * heightShare) * RADIUS;
      for (int index = 0; index < RING_POINTS; index++) {
         double angle = phase + index * Math.PI * 2.0 / RING_POINTS;
         field.level.sendParticles(dust, field.centre.x + Math.cos(angle) * reach, y,
            field.centre.z + Math.sin(angle) * reach, 1, 0.0, 0.0, 0.0, 0.0);
      }
   }

   /** Dauerton der Kugel: ein tiefes Summen, dazu hin und wieder ein heller Kristallklang. */
   private void hum(Field field, int age, boolean warning) {
      if (age % 40 == 0) {
         field.level.playSound(null, field.centre.x, field.centre.y, field.centre.z,
            SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS, 0.8F, warning ? 1.9F : 1.5F);
      }
      if (age % 55 == 17) {
         field.level.playSound(null, field.centre.x, field.centre.y, field.centre.z,
            SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.9F, 0.8F + field.level.getRandom().nextFloat() * 0.8F);
      }
   }

   /** Wer die Kugelhaut durchquert, löst einen Wirbel und einen Ton aus – in beide Richtungen. */
   private void trackCrossings(Field field) {
      for (ServerPlayer player : field.level.players()) {
         if (player.isSpectator()) {
            continue;
         }
         boolean now = isInside(field, player);
         if (now == field.inside.contains(player.getUUID())) {
            continue;
         }
         if (now) {
            field.inside.add(player.getUUID());
         } else {
            field.inside.remove(player.getUUID());
         }
         field.level.sendParticles(ParticleTypes.REVERSE_PORTAL, player.getX(), player.getY() + 1.0, player.getZ(),
            36, 0.35, 0.7, 0.35, 0.25);
         field.level.sendParticles(SHELL_DUST, player.getX(), player.getY() + 1.0, player.getZ(),
            14, 0.4, 0.8, 0.4, 0.0);
         field.level.playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.1F, now ? 1.5F : 0.9F);
      }
   }

   /** Steckt der Werfer im Gestein, knistert es um ihn – so sehen es auch die anderen. */
   private void sparkInRock(Field field, ServerPlayer owner, int age) {
      if (age % 2 != 0 || !field.inside.contains(owner.getUUID())) {
         return;
      }
      boolean inBlock = !field.level.getBlockState(owner.blockPosition()).isAir()
         || !field.level.getBlockState(BlockPos.containing(owner.getEyePosition())).isAir();
      if (inBlock) {
         field.level.sendParticles(ParticleTypes.ELECTRIC_SPARK, owner.getX(), owner.getY() + 1.0, owner.getZ(),
            6, 0.3, 0.5, 0.3, 0.2);
         field.level.sendParticles(SHELL_DUST, owner.getX(), owner.getY() + 1.0, owner.getZ(),
            2, 0.3, 0.5, 0.3, 0.0);
      }
   }

   private static boolean isInside(Field field, ServerPlayer player) {
      return player.position().add(0.0, player.getBbHeight() * 0.5, 0.0).distanceToSqr(field.centre) <= RADIUS * RADIUS;
   }

   /** Die Haut stürzt in sich zusammen: Punkte fahren von der Oberfläche zur Mitte, dann ein Blitz. */
   private void implode(Field field) {
      for (int index = 0; index < IMPLODE_POINTS; index++) {
         double height = 1.0 - 2.0 * (index + 0.5) / IMPLODE_POINTS;
         double ring = Math.sqrt(1.0 - height * height);
         double angle = index * GOLDEN_ANGLE;
         double dx = Math.cos(angle) * ring;
         double dz = Math.sin(angle) * ring;
         field.level.sendParticles(ParticleTypes.REVERSE_PORTAL,
            field.centre.x + dx * RADIUS, field.centre.y + height * RADIUS, field.centre.z + dz * RADIUS,
            0, -dx, -height, -dz, 0.55);
      }
      field.level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 1.0F, 0.45F, 0.35F),
         field.centre.x, field.centre.y, field.centre.z, 1, 0, 0, 0, 0);
      field.level.playSound(null, field.centre.x, field.centre.y, field.centre.z,
         SoundEvents.RESPAWN_ANCHOR_DEPLETE, SoundSource.PLAYERS, 1.2F, 1.3F);
   }

   private void end(MinecraftServer server, Field field) {
      PhaseFields.SERVER.remove(field.owner);
      broadcast(field.level, new Sync(field.owner, field.centre.x, field.centre.y, field.centre.z, 0.0F, 0));
      Hologram.remove(field.display);
      implode(field);

      field.level.playSound(null, field.centre.x, field.centre.y, field.centre.z,
         SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.2F, 1.4F);

      ServerPlayer owner = server.getPlayerList().getPlayer(field.owner);
      if (owner != null && owner.isAlive() && owner.level() == field.level
         && !field.level.noCollision(null, owner.getBoundingBox())) {
         // Der Werfer steckt noch im Gestein: zurück an die letzte freie Stelle.
         field.level.sendParticles(ParticleTypes.REVERSE_PORTAL, owner.getX(), owner.getY() + 1.0, owner.getZ(),
            50, 0.4, 0.8, 0.4, 0.3);
         owner.teleportTo(field.level, field.safe.x, field.safe.y, field.safe.z,
            Set.of(), owner.getYRot(), owner.getXRot(), true);
         owner.setDeltaMovement(Vec3.ZERO);
         owner.resetFallDistance();
         field.level.sendParticles(ParticleTypes.REVERSE_PORTAL, field.safe.x, field.safe.y + 1.0, field.safe.z,
            50, 0.4, 0.8, 0.4, 0.3);
         field.level.playSound(null, field.safe.x, field.safe.y, field.safe.z,
            SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.2F);
      }
   }

   private static void broadcast(ServerLevel level, Sync payload) {
      for (ServerPlayer player : level.players()) {
         ServerPlayNetworking.send(player, payload);
      }
   }

   /** Wer später beitritt, bekommt die laufende Kugel nachgereicht. */
   public void syncJoiningPlayer(ServerPlayer joining) {
      for (Field field : fields) {
         if (field.level == joining.level()) {
            ServerPlayNetworking.send(joining, new Sync(field.owner, field.centre.x, field.centre.y,
               field.centre.z, (float) RADIUS, field.ticksLeft));
         }
      }
   }

   private static Vector3f uniform(float scale) {
      return new Vector3f(scale, scale, scale);
   }

   private static final class Flight {
      private final UUID owner;
      private Vec3 position;
      private Vec3 velocity;
      private int ticks;
      private int led = -1;
      private final Display.ItemDisplay display;

      private Flight(UUID owner, Vec3 position, Vec3 velocity, Display.ItemDisplay display) {
         this.owner = owner;
         this.position = position;
         this.velocity = velocity;
         this.display = display;
      }
   }

   private static final class Field {
      private final UUID owner;
      private final ServerLevel level;
      private final Vec3 centre;
      private final Display.ItemDisplay display;
      private final Set<UUID> inside = new HashSet<>();
      private Vec3 safe;
      private int ticksLeft = DURATION_TICKS;
      private int led = -1;

      private Field(UUID owner, ServerLevel level, Vec3 centre, Display.ItemDisplay display, Vec3 safe) {
         this.owner = owner;
         this.level = level;
         this.centre = centre;
         this.display = display;
         this.safe = safe;
      }
   }
}
