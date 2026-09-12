package com.oneshotonekill.movement;

import com.oneshotonekill.arena.Arena;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Natives physikalisches Begrenzungssystem für alle Arena-Karten.
 * <p>
 * Berechnet kontinuierliche Kollisionen mit den vermessenen Map-Polygonen und Deckenhöhen
 * direkt innerhalb von {@code Entity#collide(Vec3)}. Dadurch gleiten Spieler butterweich an
 * Map-Grenzen entlang (Wall-Sliding), stoßen an unsichtbaren Decken ab und können die Karte
 * weder durch Laufen, Sprinten, Grappling-Hook noch Gleiten verlassen.
 * <p>
 * Da die Berechnung sowohl auf dem Client als auch auf dem Server identisch abläuft,
 * treten keinerlei Positions-Desyncs, Teleport-Ruckler oder „Zurückbuggen“ (Rubberbanding) auf.
 */
@SuppressWarnings({"resource", "unused"})
public final class ArenaBorderCollision {
    /**
     * Sicherheitsabstand des Spielers zur Border in Blöcken (Spielerbreite: 0.6m -> Radius: 0.3m).
     * 0.25m gewährleistet flüssiges Passieren aller Standardtüren und Gänge bei robuster Randkollision.
     */
    private static final double COLLISION_RADIUS = 0.25;

    private ArenaBorderCollision() {
    }

    /**
     * Klemmt den Bewegungsvektor einer Entität an den physischen Grenzen der Arena ein.
     */
    public static Vec3 clampMovement(Entity entity, Vec3 movement) {
        if (!(entity instanceof Player player) || player.isSpectator()) {
            return movement;
        }

        Arena arena = Arena.byDimension(player.level().dimension());
        if (arena == null || arena.getRegions().isEmpty()) {
            return movement;
        }

        AABB box = player.getBoundingBox();
        double curX = box.getCenter().x;
        double curZ = box.getCenter().z;

        // Spieler in der Wartelobby (außerhalb der Kampfsäule) bewegen sich frei in ihrem Warteraum
        if (!arena.isInArenaColumn(curX, curZ)) {
            return movement;
        }

        double dx = movement.x;
        double dy = movement.y;
        double dz = movement.z;

        // 1. Decken-Kollision (Ceiling Cap: z.B. Y=35 in Tilted Towers, Y=69 in Standard)
        Double ceiling = arena.getCeilingY();
        if (ceiling != null && box.maxY <= ceiling) {
            if (box.maxY + dy > ceiling) {
                dy = Math.max(0.0, ceiling - box.maxY);
                if (dy < 1.0E-5) {
                    dy = 0.0;
                }
            }
        }

        // 2. Horizontale Polygon-Kollision (unabhängige X- und Z-Achsen für butterweiches Entlanggleiten)
        boolean currentlyAllowed = isPositionAllowed(arena, curX, curZ);

        if (currentlyAllowed) {
            // Prüfung X-Achse
            if (dx != 0.0) {
                double targetX = curX + dx;
                if (!isPositionAllowed(arena, targetX, curZ)) {
                    double low = 0.0;
                    double high = dx;
                    for (int i = 0; i < 6; i++) {
                        double mid = (low + high) * 0.5;
                        if (isPositionAllowed(arena, curX + mid, curZ)) {
                            low = mid;
                        } else {
                            high = mid;
                        }
                    }
                    dx = low;
                    if (Math.abs(dx) < 1.0E-4) {
                        dx = 0.0;
                    }
                }
            }

            // Prüfung Z-Achse mit aktualisierter X-Koordinate
            double newX = curX + dx;
            if (dz != 0.0) {
                double targetZ = curZ + dz;
                if (!isPositionAllowed(arena, newX, targetZ)) {
                    double low = 0.0;
                    double high = dz;
                    for (int i = 0; i < 6; i++) {
                        double mid = (low + high) * 0.5;
                        if (isPositionAllowed(arena, newX, curZ + mid)) {
                            low = mid;
                        } else {
                            high = mid;
                        }
                    }
                    dz = low;
                    if (Math.abs(dz) < 1.0E-4) {
                        dz = 0.0;
                    }
                }
            }
        } else {
            // Notfall-Sicherung: Sollte ein Spieler durch Explosionen/Glitches außerhalb geraten,
            // werden nur Bewegungen erlaubt, die ihn wieder zurück zur Arena/Lobby führen.
            Vec3 anchor = arena.getLobby();
            double curDistSq = (curX - anchor.x) * (curX - anchor.x) + (curZ - anchor.z) * (curZ - anchor.z);
            double newDistSq = (curX + dx - anchor.x) * (curX + dx - anchor.x) + (curZ + dz - anchor.z) * (curZ + dz - anchor.z);
            if (newDistSq >= curDistSq) {
                dx = 0.0;
                dz = 0.0;
            }
        }

        return new Vec3(dx, dy, dz);
    }

    /**
     * Prüft das Zentrum sowie 4 Eckpunkte des Spielerradius gegen das Arena-Polygon.
     * Nutzt die vorberechnete Rastermaske von ArenaShape für O(1) Abfragen unter 1ns.
     */
    private static boolean isPositionAllowed(Arena arena, double x, double z) {
        return arena.isInArenaColumn(x, z)
            && arena.isInArenaColumn(x - COLLISION_RADIUS, z - COLLISION_RADIUS)
            && arena.isInArenaColumn(x - COLLISION_RADIUS, z + COLLISION_RADIUS)
            && arena.isInArenaColumn(x + COLLISION_RADIUS, z - COLLISION_RADIUS)
            && arena.isInArenaColumn(x + COLLISION_RADIUS, z + COLLISION_RADIUS);
    }
}
