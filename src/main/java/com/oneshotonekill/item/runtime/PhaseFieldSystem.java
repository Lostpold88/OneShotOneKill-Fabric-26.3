package com.oneshotonekill.item.runtime;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.ArenaWorlds;
import com.oneshotonekill.registry.ModItems;
import com.oneshotonekill.shared.DeviceLights;
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
import net.minecraft.core.UUIDUtil;
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
 *   <li><b>Durchsicht</b> – der Client des Werfers zeichnet die Blöcke der Kugel halbtransparent
 *       ({@code PhaseGhost}). Es bleiben dieselben Blöcke mit derselben Textur. Durchgang, Durchsicht
 *       und Bildschirmanzeige gelten nur für den Werfer; die Haut der Kugel sieht jeder.</li>
 *   <li><b>Ausstieg</b> – steckt der Werfer beim Ablauf in einem Block, kehrt er zur letzten
 *       freien Position zurück.</li>
 * </ul>
 * Sichtbar ist die Kugel über {@code PhaseFieldRenderer} (Haut, Gitter, Wellen), die Anzeige des
 * Werfers über {@code PhaseHudLayer}. Der Server steuert nur Töne und die Granate selbst – Partikel
 * gibt es hier bewusst keine.
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
   private static final int DURATION_TICKS = PhaseFields.DURATION_TICKS;
   /** Die letzten drei Sekunden, in denen der Takt schneller wird und der Werfer die Kugel verlassen soll. */
   private static final int WARNING_TICKS = PhaseFields.WARNING_TICKS;
   private static final float VIEW_RANGE = 4.0F;
   private static final float SCALE = 0.55F;
   private static final int LED_COLD = 0x0B4A58;
   private static final int LED_LIT = 0x3CF0FF;
   private static final int LED_WARN = 0xFF4A3C;

   /**
    * Client-Spiegel einer Kugel; {@code radius <= 0} löscht sie. Geht nur an den Werfer.
    * <p>
    * Ohne dieses Paket wüsste sein Client nicht, dass er durch Wände darf, und
    * die Bewegungsvorhersage risse ihn an jeder Wand zurück. {@code ticks} ist die Restdauer für
    * die Bildschirmanzeige.
    */
   public record Sync(UUID id, UUID owner, double x, double y, double z, float radius, int ticks)
      implements CustomPacketPayload {
      public static final Type<Sync> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("phase_field"));
      public static final StreamCodec<ByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
         UUIDUtil.STREAM_CODEC, Sync::id,
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
      // Die Dioden leuchten von Anfang an und werden im Flug nicht mehr umgefärbt: jede Farbänderung
      // schickt den Gegenstand neu, der Client baut dann das Modell mit seinen 200 Teilen neu auf, und
      // die Bahn ruckelt. Alle anderen Wurfgeräte tragen im Flug einen unveränderlichen Gegenstand.
      ItemStack flying = new ItemStack(ModItems.PHASE_GRENADE);
      DeviceLights.set(flying, LED_LIT);
      Display.ItemDisplay display = Hologram.spawnEffect(level, origin, flying, VIEW_RANGE);
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
         }

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

      // Beliebig viele Kugeln gleichzeitig, auch mehrere desselben Werfers: jede hat ihre eigene Kennung.
      PhaseFields.SERVER.put(new PhaseFields.Zone(field.id, shot.owner, centre, RADIUS,
         level.getGameTime() + DURATION_TICKS));
      broadcast(level, new Sync(field.id, shot.owner, centre.x, centre.y, centre.z, (float) RADIUS, DURATION_TICKS));

      // Den sichtbaren Aufschlag – Druckwelle, aufsteigende Welle, Aufbau der Haut – zeichnet der Client.
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
         hum(field, age, warning);
         trackCrossings(field);

         if (field.display != null && !field.display.isRemoved()) {
            float pulse = SCALE * (1.0F + 0.09F * (float) Math.sin(age * (warning ? 0.9 : 0.3)));
            Hologram.setPose(field.display, new Vector3f(),
               new Quaternionf().rotateY(age * (warning ? 0.35F : 0.12F)), uniform(pulse), 1);
            int period = warning ? 8 : 20;
            field.led = Hologram.tint(field.display, ModItems.PHASE_GRENADE, field.led,
               age % period < Math.max(1, period / 2) ? (warning ? LED_WARN : LED_LIT) : LED_COLD);
         }
         // Mehrere Kugeln desselben Werfers schreiben nicht um die Anzeige: nur die längste meldet sich.
         if (age % 10 == 0 && isLongest(field)) {
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

   private boolean isLongest(Field field) {
      for (Field other : fields) {
         if (other != field && other.owner.equals(field.owner) && other.ticksLeft > field.ticksLeft) {
            return false;
         }
      }
      return true;
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

   /** Wer die Kugelhaut durchquert, löst einen Ton aus – in beide Richtungen. Den Ring dazu malt der Client. */
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
         field.level.playSound(null, player.getX(), player.getY(), player.getZ(),
            SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.1F, now ? 1.5F : 0.9F);
      }
   }

   private static boolean isInside(Field field, ServerPlayer player) {
      return player.position().add(0.0, player.getBbHeight() * 0.5, 0.0).distanceToSqr(field.centre) <= RADIUS * RADIUS;
   }

   private void end(MinecraftServer server, Field field) {
      PhaseFields.SERVER.remove(field.id);
      broadcast(field.level, new Sync(field.id, field.owner, field.centre.x, field.centre.y, field.centre.z, 0.0F, 0));
      Hologram.remove(field.display);

      field.level.playSound(null, field.centre.x, field.centre.y, field.centre.z,
         SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.2F, 1.4F);

      ServerPlayer owner = server.getPlayerList().getPlayer(field.owner);
      if (owner != null && owner.isAlive() && owner.level() == field.level
         && !field.level.noCollision(owner, owner.getBoundingBox())) {
         // Mit dem Werfer als Kontext: steht er noch in einer anderen seiner Kugeln, ist er dort sicher.
         // Der Werfer steckt noch im Gestein: zurück an die letzte freie Stelle.
         owner.teleportTo(field.level, field.safe.x, field.safe.y, field.safe.z,
            Set.of(), owner.getYRot(), owner.getXRot(), true);
         owner.setDeltaMovement(Vec3.ZERO);
         owner.resetFallDistance();
         field.level.playSound(null, field.safe.x, field.safe.y, field.safe.z,
            SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.2F);
      }
   }

   /**
    * Die Kugel geht an alle: jeder sieht die Haut. Durchgang und Durchsicht bleiben dem Werfer –
    * der Client vergleicht den Besitzer mit dem lokalen Spieler.
    */
   private static void broadcast(ServerLevel level, Sync payload) {
      for (ServerPlayer player : level.players()) {
         ServerPlayNetworking.send(player, payload);
      }
   }

   /** Wer später beitritt, bekommt die laufenden Kugeln nachgereicht. */
   public void syncJoiningPlayer(ServerPlayer joining) {
      for (Field field : fields) {
         if (field.level == joining.level()) {
            ServerPlayNetworking.send(joining, new Sync(field.id, field.owner, field.centre.x, field.centre.y,
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
      private final Display.ItemDisplay display;

      private Flight(UUID owner, Vec3 position, Vec3 velocity, Display.ItemDisplay display) {
         this.owner = owner;
         this.position = position;
         this.velocity = velocity;
         this.display = display;
      }
   }

   private static final class Field {
      private final UUID id = UUID.randomUUID();
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
