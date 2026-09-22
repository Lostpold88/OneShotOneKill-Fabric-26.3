package com.oneshotonekill.item.runtime;

import com.oneshotonekill.registry.ModItems;
import com.oneshotonekill.network.OsokPayloads.*;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import com.oneshotonekill.item.types.WeaponItems.MinigunItem;


@SuppressWarnings({"ConstantValue", "resource", "unused"})
public final class MinigunRuntime {
   public static final MinigunRuntime INSTANCE = new MinigunRuntime();
   public static final int USE_DURATION_TICKS = 160;
   public static final int HISS_DURATION_TICKS = 30;
   /**
    * Treffer bis zur Eliminierung.
    * <p>
    * Die Minigun feuert zwei Schüsse je Tick; vier Treffer waren damit ein Wimpernschlag und
    * die Waffe eine bessere Railgun. Zehn zwingen dazu, das Ziel wirklich zu halten.
    */
   public static final int HITS_TO_ELIMINATE = 10;
   public static final int HIT_COMBO_TIMEOUT_TICKS = 25;

   private static final Map<UUID, ActiveMinigun> activeMiniguns = new LinkedHashMap<>();
   private static final Map<UUID, Integer> expiringMiniguns = new LinkedHashMap<>();
   private static final Map<UUID, HitProgress> hitProgress = new LinkedHashMap<>();
   /** Letzter gemeldeter Trefferstand je Schütze – nur für die Anzeige. */
   private static final Map<UUID, Integer> lastReportedHits = new LinkedHashMap<>();
   /** Schütze -> Entity-UUIDs seiner noch existierenden Minigun-Pfeile. */
   private static final Map<UUID, Set<UUID>> spawnedArrows = new LinkedHashMap<>();

   private MinigunRuntime() {
   }

   public boolean begin(ServerPlayer player) {
      int tick = player.level().getServer().getTickCount();
      UUID playerId = player.getUUID();
      if (expiringMiniguns.containsKey(playerId)) {
         return false;
      }

      ActiveMinigun active = activeMiniguns.get(playerId);
      if (active != null) {
         return tick < active.expiresAtTick();
      }

      activeMiniguns.put(playerId, new ActiveMinigun(tick, tick + USE_DURATION_TICKS));
      ServerPlayNetworking.send(player, new MinigunHudPayload("started"));
      return true;
   }

   public int elapsedUseTicks(ServerPlayer player) {
      ActiveMinigun active = activeMiniguns.get(player.getUUID());
      return active == null ? 0 : player.level().getServer().getTickCount() - active.startedAtTick();
   }

   public boolean canFire(ServerPlayer player) {
      ActiveMinigun active = activeMiniguns.get(player.getUUID());
      return active != null && player.level().getServer().getTickCount() < active.expiresAtTick();
   }

   public boolean recordHit(ServerPlayer attacker, ServerPlayer victim) {
      int tick = attacker.level().getServer().getTickCount();
      HitProgress previous = hitProgress.get(victim.getUUID());
      int hits = previous != null && Objects.equals(previous.attackerId(), attacker.getUUID()) && tick <= previous.expiresAtTick()
         ? previous.hits() + 1
         : 1;
      if (hits >= HITS_TO_ELIMINATE) {
         hitProgress.remove(victim.getUUID());
         return true;
      }

      hitProgress.put(victim.getUUID(), new HitProgress(attacker.getUUID(), hits, tick + HIT_COMBO_TIMEOUT_TICKS));
      lastReportedHits.put(attacker.getUUID(), hits);
      return false;
   }

   public void sendKillEffect(ServerPlayer player) {
      ServerPlayNetworking.send(player, new MinigunHudPayload("kill_confirmed"));
   }

   public void sendHitEffect(ServerPlayer player) {
      ServerPlayNetworking.send(player, new MinigunHudPayload("hit_confirmed",
         lastReportedHits.getOrDefault(player.getUUID(), 0)));
   }

   public void trackArrow(ServerPlayer shooter, AbstractArrow arrow) {
      spawnedArrows.computeIfAbsent(shooter.getUUID(), ignored -> new LinkedHashSet<>()).add(arrow.getUUID());
   }

   /** Entfernt serverweit sämtliche geladenen Pfeile; Rückgabewert für das Admin-Feedback. */
   public int clearAllArrows(MinecraftServer server) {
      List<AbstractArrow> arrows = new ArrayList<>();
      for (ServerLevel level : server.getAllLevels()) {
         for (Entity entity : level.getAllEntities()) {
            if (entity instanceof AbstractArrow arrow) {
               arrows.add(arrow);
            }
         }
      }
      arrows.forEach(Entity::discard);
      spawnedArrows.clear();
      return arrows.size();
   }

   public void tick(MinecraftServer server) {
      int currentTick = server.getTickCount();
      activeMiniguns.entrySet().removeIf(entry -> expireActiveMinigun(currentTick, server, entry));
      expiringMiniguns.entrySet().removeIf(entry -> removeExpiredMinigun(currentTick, server, entry));
      hitProgress.entrySet().removeIf(entry -> currentTick > entry.getValue().expiresAtTick());
   }

   public void reset() {
      activeMiniguns.clear();
      expiringMiniguns.clear();
      hitProgress.clear();
      lastReportedHits.clear();
      spawnedArrows.clear();
   }

   private void removeOneMinigun(ServerPlayer player) {
      for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
         if (player.getInventory().getItem(slot).is(ModItems.MINIGUN)) {
            player.getInventory().setItem(slot, ItemStack.EMPTY);
            player.containerMenu.broadcastChanges();
            return;
         }
      }
   }

   private static boolean expireActiveMinigun(int currentTick, MinecraftServer server, Map.Entry<UUID, ActiveMinigun> entry) {
      if (currentTick < entry.getValue().expiresAtTick()) {
         return false;
      }

      INSTANCE.discardMinigunArrows(server, entry.getKey());

      ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
      if (player != null) {
         player.stopUsingItem();
         expiringMiniguns.put(entry.getKey(), currentTick + HISS_DURATION_TICKS);
         ServerPlayNetworking.send(player, new MinigunHudPayload("expiring"));
      }
      return true;
   }

   private void discardMinigunArrows(MinecraftServer server, UUID shooterId) {
      Set<UUID> arrowIds = spawnedArrows.remove(shooterId);
      if (arrowIds == null || arrowIds.isEmpty()) {
         return;
      }
      for (ServerLevel level : server.getAllLevels()) {
         for (UUID arrowId : arrowIds) {
            Entity entity = level.getEntity(arrowId);
            if (entity instanceof AbstractArrow arrow) {
               arrow.discard();
            }
         }
      }
   }

   private static boolean removeExpiredMinigun(int currentTick, MinecraftServer server, Map.Entry<UUID, Integer> entry) {
      if (currentTick < entry.getValue()) {
         return false;
      }

      ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
      if (player != null) {
         INSTANCE.removeOneMinigun(player);
      }
      return true;
   }

   private record ActiveMinigun(int startedAtTick, int expiresAtTick) {
   }

   private record HitProgress(UUID attackerId, int hits, int expiresAtTick) {
   }

   // --- Muzzle Vector & Raycast Calculation ---
   public static final class Muzzle {
      private static final double FORWARD = 1.0940;
      private static final double RIGHT = 0.2975;
      private static final double DOWN = 0.2825;
      private static final double BARREL_RADIUS = 0.0553;
      private static final double AIM_RANGE = 80.0;
      private static final double WALL_CLEARANCE = 0.15;

      private Muzzle() {
      }

      public record Shot(Vec3 origin, Vec3 direction) {
      }

      public static Shot resolve(ServerLevel level, ServerPlayer player) {
         ViewBasis basis = ViewBasis.of(player);
         Vec3 muzzle = barrelInFiringPosition(basis.eye(), basis.look(), basis.right(), basis.up(), player.getTicksUsingItem());
         muzzle = keepInsideWorld(level, player, basis.eye(), muzzle);
         return new Shot(muzzle, towardsCrosshair(level, player, basis.eye(), basis.look(), muzzle));
      }

      private static Vec3 barrelInFiringPosition(Vec3 eye, Vec3 look, Vec3 right, Vec3 up, int useTicks) {
         float angle = Spin.angle(useTicks);
         return eye
            .add(look.scale(FORWARD))
            .add(right.scale(RIGHT - BARREL_RADIUS * Math.cos(angle)))
            .add(up.scale(BARREL_RADIUS * Math.sin(angle) - DOWN));
      }

      private static Vec3 keepInsideWorld(ServerLevel level, ServerPlayer player, Vec3 eye, Vec3 muzzle) {
         HitResult blocked = level.clip(new ClipContext(eye, muzzle,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
         if (blocked.getType() == HitResult.Type.MISS) {
            return muzzle;
         }
         Vec3 offset = muzzle.subtract(eye);
         double free = Math.max(0.0, blocked.getLocation().distanceTo(eye) - WALL_CLEARANCE);
         return offset.lengthSqr() < 1.0E-8 ? muzzle : eye.add(offset.normalize().scale(free));
      }

      private static Vec3 towardsCrosshair(ServerLevel level, ServerPlayer player, Vec3 eye, Vec3 look, Vec3 muzzle) {
         HitResult aim = net.minecraft.world.entity.projectile.ProjectileUtil.getHitResultOnViewVector(
            player, entity -> !entity.isSpectator() && entity.isPickable(), AIM_RANGE);
         Vec3 target = (aim == null || aim.getType() == HitResult.Type.MISS) ? eye.add(look.scale(AIM_RANGE)) : aim.getLocation();
         return target.distanceToSqr(muzzle) < 0.01
            ? look
            : target.subtract(muzzle).normalize();
      }
   }

   // --- Spin Mathematics ---
   public static final class Spin {
      public static final float MAX_RADIANS_PER_TICK = 0.62F;

      private Spin() {
      }

      public static float speed(float useTicks) {
         if (useTicks <= 0.0F) {
            return 0.0F;
         }
         return Math.min(1.0F, useTicks / MinigunItem.WARM_UP_TICKS);
      }

      public static float angle(float useTicks) {
         if (useTicks <= 0.0F) {
            return 0.0F;
         }
         float warmUp = MinigunItem.WARM_UP_TICKS;
         float turns = useTicks <= warmUp
            ? useTicks * useTicks / (2.0F * warmUp)
            : warmUp / 2.0F + (useTicks - warmUp);
         return turns * MAX_RADIANS_PER_TICK;
      }
   }
}
