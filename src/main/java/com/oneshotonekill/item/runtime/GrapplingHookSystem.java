package com.oneshotonekill.item.runtime;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.arena.ArenaWorlds;
import com.oneshotonekill.network.OsokPayloads.GrapplePullPayload;
import com.oneshotonekill.registry.ModItems;
import com.oneshotonekill.shared.Feedback;
import com.oneshotonekill.shared.Hologram;
import com.oneshotonekill.shared.SpecialItemRules;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Flug, Seil und Zugbewegung des Grappling Hooks.
 *
 * <p>Die Simulation ist vollständig serverautoritär. Der Haken ist kein Vanilla-Projektil,
 * sondern ein präzise pro Tick geraycasteter Punkt; dadurch kann er bei seinem hohen Tempo
 * keinen dünnen Block überspringen. Nur der tatsächlich fliegende beziehungsweise haftende
 * Saughaken ist ein Item-Display. Das Seil zeichnet jeder Client aus demselben synchronisierten
 * Hakenpunkt direkt an die interpolierte Waffenmündung; so kann keine zweite Entity der
 * Spielerbewegung hinterherhängen.</p>
 */
public final class GrapplingHookSystem {
   public static final GrapplingHookSystem INSTANCE = new GrapplingHookSystem();

   private static final double FIRE_SPEED = 3.20;
   private static final double MAX_RANGE = 38.0;
   private static final int MAX_FLIGHT_TICKS = 16;
   private static final int MAX_PULL_TICKS = 42;
   private static final double RELEASE_DISTANCE = 2.35;
   private static final double RETRACT_SPEED = 4.20;
   private static final double MAX_PULL_SPEED = 1.78;
   private static final double PULL_ACCELERATION = 0.72;
   private static final double RELEASE_LIFT = 0.42;
   /** Exakt der effektive Maßstab des geladenen Kopfes im Handmodell (0,5966). */
   private static final float HOOK_SCALE = 0.60F;
   private static final float VIEW_RANGE = 3.0F;
   /** Ein Tick: jedes Zwischenbild wird interpoliert, ohne dem schnellen Haken hinterherzuhängen. */
   private static final int VISUAL_INTERPOLATION_TICKS = 1;

   private final Map<UUID, Grapple> active = new LinkedHashMap<>();

   private GrapplingHookSystem() {
   }

   /** Schießt einen neuen Haken; ein noch laufender Schuss desselben Spielers wird sauber ersetzt. */
   public boolean fire(ServerLevel level, ServerPlayer player) {
      Grapple previous = active.remove(player.getUUID());
      if (previous != null) {
         previous.dismantle();
      }

      Vec3 look = player.getLookAngle().normalize();
      Vec3 origin = muzzlePosition(player, 1.0F);
      Display.ItemDisplay hook = Hologram.spawnEffect(level, origin,
         new ItemStack(ModItems.GRAPPLING_HOOK_HEAD), VIEW_RANGE);
      if (hook != null) {
         hook.setPosRotInterpolationDuration(VISUAL_INTERPOLATION_TICKS);
      }

      Grapple grapple = new Grapple(player.getUUID(), level, origin, look.scale(FIRE_SPEED),
         origin, look, hook);
      active.put(player.getUUID(), grapple);
      updateVisuals(grapple);
      syncGrappleState(grapple, true);

      level.playSound(null, player.getX(), player.getY(), player.getZ(),
         SoundEvents.CROSSBOW_SHOOT, SoundSource.PLAYERS, 0.85F, 1.55F);
      level.playSound(null, player.getX(), player.getY(), player.getZ(),
         SoundEvents.PISTON_EXTEND, SoundSource.PLAYERS, 0.45F, 1.75F);
      return true;
   }

   public void tick(MinecraftServer server) {
      Iterator<Grapple> iterator = active.values().iterator();
      while (iterator.hasNext()) {
         Grapple grapple = iterator.next();
         ServerPlayer owner = server.getPlayerList().getPlayer(grapple.owner);
         if (!isUsable(grapple, owner)) {
            grapple.dismantle();
            iterator.remove();
            continue;
         }

         boolean finished = switch (grapple.phase) {
            case FLYING -> tickFlying(grapple, owner);
            case PULLING -> tickPulling(grapple, owner);
            case RETRACTING -> tickRetracting(grapple, owner);
         };
         if (finished) {
            grapple.dismantle();
            iterator.remove();
         } else {
            updateVisuals(grapple);
            syncGrappleState(grapple, true);
         }
      }
   }

   private static boolean isUsable(Grapple grapple, ServerPlayer owner) {
      return owner != null && owner.isAlive() && !owner.isSpectator()
         && owner.level() == grapple.level && SpecialItemRules.canUse(owner);
   }

   private boolean tickFlying(Grapple grapple, ServerPlayer owner) {
      Vec3 next = grapple.position.add(grapple.velocity);
      BlockHitResult hit = grapple.level.clip(new ClipContext(
         grapple.position, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
         CollisionContext.empty()));

      if (hit.getType() != HitResult.Type.MISS) {
         latch(grapple, owner, hit);
         return false;
      }

      grapple.position = next;
      grapple.ticks++;
      double travelled = grapple.position.distanceTo(grapple.launchOrigin);
      if (travelled >= MAX_RANGE || grapple.ticks >= MAX_FLIGHT_TICKS) {
         beginRetracting(grapple, owner, true);
      } else if ((grapple.ticks & 1) == 0) {
         grapple.level.sendParticles(ParticleTypes.CRIT,
            grapple.position.x, grapple.position.y, grapple.position.z,
            2, 0.025, 0.025, 0.025, 0.01);
      }
      return false;
   }

   private void latch(Grapple grapple, ServerPlayer owner, BlockHitResult hit) {
      ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
      Arena arena = worlds == null ? null : worlds.getActive();
      Vec3 surface = hit.getLocation();
      // Der getroffene Wandblock darf knapp außerhalb des vermessenen Polygons liegen. Entscheidend
      // ist die Stelle unmittelbar vor dem Einschlag – also die Seite, von der der Spieler kommt.
      Vec3 approach = surface.subtract(grapple.aimDirection.scale(0.10));
      if (arena == null || !arena.isInArena(approach.x, approach.y, approach.z)) {
         grapple.position = surface;
         beginRetracting(grapple, owner, true);
         return;
      }

      Vec3 normal = hit.getDirection().getUnitVec3();
      grapple.position = surface.add(normal.scale(0.075));
      grapple.anchor = grapple.position;
      grapple.anchorBlock = hit.getBlockPos().immutable();
      grapple.phase = Phase.PULLING;
      grapple.pullTicks = 0;

      double metres = owner.getEyePosition().distanceTo(grapple.anchor);
      Feedback.actionBar(owner, "§b⛓ EINGEHAKT §7· " + Math.round(metres) + " m");
      grapple.level.playSound(null, surface.x, surface.y, surface.z,
         SoundEvents.CHAIN_PLACE, SoundSource.PLAYERS, 1.0F, 1.35F);
      grapple.level.playSound(null, surface.x, surface.y, surface.z,
         SoundEvents.TRIPWIRE_CLICK_ON, SoundSource.PLAYERS, 0.8F, 0.75F);
      grapple.level.sendParticles(ParticleTypes.WAX_OFF, surface.x, surface.y, surface.z,
         8, 0.10, 0.10, 0.10, 0.025);
   }

   private boolean tickPulling(Grapple grapple, ServerPlayer owner) {
      BlockState anchorState = grapple.anchorBlock == null
         ? null : grapple.level.getBlockState(grapple.anchorBlock);
      if (grapple.anchor == null || anchorState == null || anchorState.isAir() || anchorState.canBeReplaced()) {
         beginRetracting(grapple, owner, false);
         return false;
      }

      Vec3 toAnchor = grapple.anchor.subtract(owner.getEyePosition());
      double distance = toAnchor.length();
      if (distance <= RELEASE_DISTANCE) {
         launchPastAnchor(owner);
         grapple.level.playSound(null, owner.getX(), owner.getY(), owner.getZ(),
            SoundEvents.TRIPWIRE_CLICK_ON, SoundSource.PLAYERS, 0.55F, 1.65F);
         return true;
      }
      if (++grapple.pullTicks > MAX_PULL_TICKS) {
         beginRetracting(grapple, owner, false);
         return false;
      }

      Vec3 direction = toAnchor.scale(1.0 / distance);
      Vec3 current = owner.getDeltaMovement();
      double targetSpeed = Math.min(MAX_PULL_SPEED, 1.12 + distance * 0.025);
      Vec3 desired = direction.scale(targetSpeed).add(0.0, 0.055, 0.0);
      Vec3 movement = current.scale(0.58).add(desired.scale(PULL_ACCELERATION));
      if (movement.lengthSqr() > MAX_PULL_SPEED * MAX_PULL_SPEED) {
         movement = movement.normalize().scale(MAX_PULL_SPEED);
      }
      if (owner.onGround() && direction.y > 0.08) {
         movement = new Vec3(movement.x, Math.max(movement.y, 0.48), movement.z);
      }

      owner.setDeltaMovement(movement);
      owner.hurtMarked = true;
      owner.resetFallDistance();
      owner.connection.resetFlyingTicks();

      if ((grapple.pullTicks & 3) == 0) {
         Vec3 ropePoint = owner.getRopeHoldPosition(1.0F).lerp(grapple.anchor, 0.55);
         grapple.level.sendParticles(ParticleTypes.WAX_ON,
            ropePoint.x, ropePoint.y, ropePoint.z, 1, 0.02, 0.02, 0.02, 0.0);
      }
      return false;
   }

   /** Gibt am Anker noch einen kleinen Aufwärtsimpuls – sonst klebt man stumpf an der Wand. */
   private static void launchPastAnchor(ServerPlayer owner) {
      Vec3 movement = owner.getDeltaMovement();
      owner.setDeltaMovement(movement.x, Math.max(movement.y, RELEASE_LIFT), movement.z);
      owner.hurtMarked = true;
      owner.resetFallDistance();
   }

   private void beginRetracting(Grapple grapple, ServerPlayer owner, boolean missed) {
      grapple.phase = Phase.RETRACTING;
      grapple.anchor = null;
      grapple.anchorBlock = null;
      Vec3 returnDirection = muzzlePosition(owner, 1.0F).subtract(grapple.position);
      if (returnDirection.lengthSqr() > 1.0E-6) {
         grapple.aimDirection = returnDirection.normalize();
      }
      if (missed) {
         Feedback.actionBar(owner, "§7⛓ Kein Halt");
         grapple.level.playSound(null, owner.getX(), owner.getY(), owner.getZ(),
            SoundEvents.FISHING_BOBBER_THROW, SoundSource.PLAYERS, 0.35F, 0.55F);
      }
   }

   private boolean tickRetracting(Grapple grapple, ServerPlayer owner) {
      Vec3 hand = muzzlePosition(owner, 1.0F);
      Vec3 back = hand.subtract(grapple.position);
      double distance = back.length();
      if (distance <= RETRACT_SPEED) {
         return true;
      }
      grapple.velocity = back.scale(RETRACT_SPEED / distance);
      grapple.position = grapple.position.add(grapple.velocity);
      grapple.aimDirection = grapple.velocity.normalize();
      return false;
   }

   private static void updateVisuals(Grapple grapple) {
      // Beim Spawn sofort in der richtigen Lage erscheinen; erst die folgenden Serverupdates
      // werden über einen Tick geglättet. So gibt es weder den ursprünglichen 20-Hz-Sprung noch
      // ein sichtbares Aufwachsen aus der winzigen versteckten Startmatrix.
      // Beim Abprall wechselt die Richtung fast um 180 Grad. Eine Quaternion-Interpolation
      // würde den Kopf für genau diesen Tick quer durch die Zwischenlage drehen. Im Rückflug
      // wird die Ausrichtung daher sofort gesetzt; nur die Entityposition bleibt geglättet.
      int interpolationTicks = grapple.visualsInitialized && grapple.phase != Phase.RETRACTING
         ? VISUAL_INTERPOLATION_TICKS : 0;
      if (grapple.hook != null && Hologram.isLive(grapple.hook)) {
         Hologram.move(grapple.hook, grapple.position);
         Vec3 aim = grapple.aimDirection;
         Quaternionf rotation = new Quaternionf().rotationTo(0.0F, 0.0F, -1.0F,
            (float) aim.x, (float) aim.y, (float) aim.z);
         Hologram.setPose(grapple.hook, new Vector3f(), rotation,
            new Vector3f(HOOK_SCALE, HOOK_SCALE, HOOK_SCALE), interpolationTicks);
      }
      grapple.visualsInitialized = true;
   }

   /**
    * Serverseitiger Start- und Rückkehrpunkt nahe der sichtbaren Waffenmündung.
    *
    * <p>{@link Player#getHandHoldingItemAngle} liefert bereits die Seite des Arms, der das Item
    * tatsächlich hält. Dieser Vektor darf nicht noch einmal negiert werden: Bei einer rechten
    * Hand würde der Pömpel sonst links vom Spieler erscheinen. Die bildgenaue Seilbuchse liest
    * der Client zusätzlich direkt aus der gerenderten Modellmatrix.</p>
    */
   public static Vec3 muzzlePosition(Player player, float partialTick) {
      Vec3 look = player.getViewVector(partialTick).normalize();
      Vec3 up = player.getUpVector(partialTick).normalize();
      Vec3 weaponSide = player.getHandHoldingItemAngle(ModItems.GRAPPLING_HOOK);
      return player.getEyePosition(partialTick)
         .add(look.scale(0.89))
         .add(weaponSide.scale(0.55))
         .add(up.scale(-0.34));
   }

   /**
    * Meldet den einen echten Hakenpunkt. Der Client interpoliert diese 20-Hz-Stützstellen pro
    * Bild und verbindet sie mit seiner ebenso interpolierten Waffenmündung.
    */
   private static void syncGrappleState(Grapple grapple, boolean active) {
      GrapplePullPayload payload = active
         ? new GrapplePullPayload(grapple.owner, true, grapple.phase == Phase.PULLING,
            grapple.position.x, grapple.position.y, grapple.position.z)
         : GrapplePullPayload.inactive(grapple.owner);
      for (ServerPlayer listener : grapple.level.players()) {
         ServerPlayNetworking.send(listener, payload);
      }
   }

   public void reset() {
      active.values().forEach(Grapple::dismantle);
      active.clear();
   }

   private enum Phase {
      FLYING,
      PULLING,
      RETRACTING
   }

   private static final class Grapple {
      private final UUID owner;
      private final ServerLevel level;
      private final Vec3 launchOrigin;
      private final Display.ItemDisplay hook;
      private Vec3 position;
      private Vec3 velocity;
      private Vec3 aimDirection;
      private Vec3 anchor;
      private BlockPos anchorBlock;
      private Phase phase = Phase.FLYING;
      private int ticks;
      private int pullTicks;
      private boolean visualsInitialized;

      private Grapple(UUID owner, ServerLevel level, Vec3 position, Vec3 velocity,
                      Vec3 launchOrigin, Vec3 aimDirection, Display.ItemDisplay hook) {
         this.owner = owner;
         this.level = level;
         this.position = position;
         this.velocity = velocity;
         this.launchOrigin = launchOrigin;
         this.aimDirection = aimDirection;
         this.hook = hook;
      }

      private void dismantle() {
         syncGrappleState(this, false);
         Hologram.remove(hook);
      }
   }
}
