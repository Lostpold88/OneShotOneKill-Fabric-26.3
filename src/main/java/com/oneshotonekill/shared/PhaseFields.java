package com.oneshotonekill.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.Vec3;

/**
 * Die Kugeln der Phasen-Granate: wo ein Spieler durch Blöcke gehen und schießen darf.
 * <p>
 * Dieselbe Klasse läuft auf beiden Seiten, aber mit getrennten Tabellen: der Server führt
 * {@link #SERVER}, der Client spiegelt sie nach {@code PhaseFieldPayload} in {@link #CLIENT}.
 * Getrennt, weil im Einzelspieler beide in einer JVM leben – eine gemeinsame Tabelle würde
 * Beitritt und Ablauf doppelt verbuchen.
 * <p>
 * Abgefragt wird aus den Kollisions-Mixins ({@code PhaseCollisionMixin}), also im heißesten Pfad
 * des Spiels. Die Liste ist deshalb ein unveränderlicher Schnappschuss und bleibt fast immer leer.
 */
public final class PhaseFields {
   public static final PhaseFields SERVER = new PhaseFields();
   public static final PhaseFields CLIENT = new PhaseFields();

   /** {@code endsAt} ist die Spielzeit des Ablaufs in Ticks der jeweiligen Seite; nur die Anzeige liest sie. */
   public record Zone(UUID owner, Vec3 centre, double radius, long endsAt) {
      public boolean contains(BlockPos pos) {
         double dx = pos.getX() + 0.5 - centre.x;
         double dy = pos.getY() + 0.5 - centre.y;
         double dz = pos.getZ() + 0.5 - centre.z;
         return dx * dx + dy * dy + dz * dz <= radius * radius;
      }
   }

   private volatile List<Zone> zones = List.of();
   /** Wessen Wurfgerät gerade geprüft wird; nur auf dem Serverfaden und nur für die Dauer eines Strahls gesetzt. */
   private static final ThreadLocal<UUID> THROWN_BY = new ThreadLocal<>();

   private PhaseFields() {
   }

   /** Die Tabelle, die zu dieser Welt gehört. */
   public static PhaseFields of(Entity entity) {
      return entity.level().isClientSide() ? CLIENT : SERVER;
   }

   /** Setzt die Kugel eines Besitzers; eine neue ersetzt seine alte. */
   public synchronized void put(Zone zone) {
      List<Zone> next = new ArrayList<>(zones);
      next.removeIf(old -> old.owner.equals(zone.owner));
      next.add(zone);
      zones = List.copyOf(next);
   }

   public synchronized void remove(UUID owner) {
      List<Zone> next = new ArrayList<>(zones);
      if (next.removeIf(old -> old.owner.equals(owner))) {
         zones = List.copyOf(next);
      }
   }

   public synchronized void clear() {
      zones = List.of();
   }

   /**
    * Darf dieser Körper den Block an {@code pos} ignorieren?
    * <p>
    * Spieler selbst und ihre Geschosse (Pfeile, Tridents) zählen für ihren Besitzer – deshalb
    * geht ein Pfeil des Werfers durch die Wand, einer des Gegners nicht. Nur Spieler behalten
    * den Boden unter den Füßen, und auch sie nur, solange sie nicht schleichen.
    */
   public boolean lets(Entity body, BlockPos pos) {
      List<Zone> snapshot = zones;
      if (snapshot.isEmpty() || body == null) {
         return false;
      }
      UUID who = body instanceof Player player ? player.getUUID()
         : body instanceof Projectile projectile && projectile.getOwner() instanceof Player owner
            ? owner.getUUID() : null;
      if (who == null) {
         return false;
      }
      // Der Boden unter den Füßen bleibt fest, sonst fiele der Werfer durch die untere Kugelhälfte.
      // Beim Schleichen gibt er nach: so sinkt man durch die Blöcke wieder nach unten.
      if (body instanceof Player player && !player.isShiftKeyDown() && pos.getY() + 1 <= body.getY() + 1.0E-3) {
         return false;
      }
      for (Zone zone : snapshot) {
         if (zone.owner.equals(who) && zone.contains(pos)) {
            return true;
         }
      }
      return false;
   }

   /**
    * Strahl eines geworfenen Geräts, das keine Entity ist (Rauch, Teleport, Singularität, Boogie, Phase).
    * <p>
    * Ein Kontext ohne Entity sähe {@code PhaseCollisionMixin} nicht; deshalb merkt sich der Faden den
    * Werfer, und der Mixin fragt {@link #letsThrown}. Anders als beim Spieler bleibt hier jeder Block
    * der Kugel offen – ein Wurf nach unten soll durch den Boden gehen, wenn er in der Kugel liegt.
    */
   public static BlockHitResult clipThrown(Level level, UUID owner, Vec3 from, Vec3 to, ClipContext.Fluid fluid) {
      THROWN_BY.set(owner);
      try {
         return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, fluid, CollisionContext.empty()));
      } finally {
         THROWN_BY.remove();
      }
   }

   public static boolean letsThrown(BlockPos pos) {
      if (SERVER.zones.isEmpty()) {
         return false;
      }
      UUID owner = THROWN_BY.get();
      if (owner == null) {
         return false;
      }
      for (Zone zone : SERVER.zones) {
         if (zone.owner.equals(owner) && zone.contains(pos)) {
            return true;
         }
      }
      return false;
   }

   /** Die Kugel dieses Besitzers, falls er eine hat. */
   public Zone find(UUID owner) {
      for (Zone zone : zones) {
         if (zone.owner.equals(owner)) {
            return zone;
         }
      }
      return null;
   }

   /** Liegt der Block in irgendeiner Kugel? Für die Darstellung, die für alle gleich ist. */
   public boolean covers(BlockPos pos) {
      for (Zone zone : zones) {
         if (zone.contains(pos)) {
            return true;
         }
      }
      return false;
   }

   public boolean isActive(UUID owner) {
      for (Zone zone : zones) {
         if (zone.owner.equals(owner)) {
            return true;
         }
      }
      return false;
   }
}
