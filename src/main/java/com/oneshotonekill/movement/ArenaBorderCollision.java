package com.oneshotonekill.movement;

import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.match.MatchManager;
import com.oneshotonekill.match.MatchManager.MatchState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Natives physikalisches Begrenzungssystem für alle Arena-Karten.
 * <p>
 * Berechnet kontinuierliche Kollisionen mit den vermessenen Map-Polygonen, Decken- und Bodenhöhen
 * direkt innerhalb von {@code Entity#collide(Vec3)}.
 * <p>
 * Gilt ausschließlich für aktive Spieler während eines laufenden Matches.
 * Spieler in der Wartelobby oder außerhalb aktiver Matches bewegen sich uneingeschränkt.
 * Spieler, die gegen Begrenzungen prallen, erhalten einen spürbar kräftigen Pushback.
 * Spieler, die durch den Boden glitchen oder fallen, werden durch das Boden-Trampolin
 * sofort wieder nach oben in die Kampfzone katapultiert.
 */
@SuppressWarnings({"resource", "unused"})
public final class ArenaBorderCollision {
    /**
     * Sicherheitsabstand des Spielers zur Border in Blöcken (Spielerbreite: 0.6m -> Radius: 0.3m).
     * 0.25m gewährleistet flüssiges Passieren aller Standardtüren und Gänge bei robuster Randkollision.
     */
    private static final double COLLISION_RADIUS = 0.25;

    /**
     * Cooldown für akustisches und visuelles Feedback pro Spieler, um Ton-Spam beim Wandlaufen zu verhindern.
     */
    private static final Map<UUID, Long> LAST_FEEDBACK = new ConcurrentHashMap<>();

    /**
     * Clientseitiger Status-Prüfer für den Match-Zustand (wird von OsokClient initialisiert).
     */
    private static BooleanSupplier clientMatchRunningPredicate = () -> false;

    private ArenaBorderCollision() {
    }

    public static void setClientMatchRunningPredicate(BooleanSupplier predicate) {
        clientMatchRunningPredicate = predicate != null ? predicate : () -> false;
    }

    /**
     * Prüft, ob aktuell ein Match läuft (auf Client über MinimapState, auf Server über MatchManager).
     */
    public static boolean isMatchActive(Level level) {
        if (level.isClientSide()) {
            return clientMatchRunningPredicate.getAsBoolean();
        }
        return MatchManager.INSTANCE.getCurrentMatchState() == MatchState.RUNNING
                && !MatchManager.Countdown.INSTANCE.isCountdownRunning();
    }

    /**
     * Klemmt den Bewegungsvektor eines Spielers an den physischen Grenzen der Arena ein
     * und wendet dynamischen Pushback sowie Boden-Rettung an.
     */
    public static Vec3 clampMovement(Entity entity, Vec3 movement) {
        // Gilt nur für echte, nicht-spektierende Spieler (nicht für gedroppte Items)
        if (!(entity instanceof Player player) || player.isSpectator()) {
            return movement;
        }

        // Gilt ausschließlich, wenn ein Match aktiv läuft
        if (!isMatchActive(player.level())) {
            return movement;
        }

        Arena arena = Arena.byDimension(player.level().dimension());
        if (arena == null || arena.getRegions().isEmpty()) {
            return movement;
        }

        AABB box = player.getBoundingBox();
        double curX = box.getCenter().x;
        double curY = player.getY();
        double curZ = box.getCenter().z;

        double dx = movement.x;
        double dy = movement.y;
        double dz = movement.z;

        // 1. Boden-Kollision & Aufwärts-Katapult (Boden-Rettung / Trampolin)
        // Verhindert das Durchfallen durch den Boden und schleudert Spieler sofort wieder hoch
        double floorY = arena.getFloorY(curX, curY, curZ);
        if (box.minY + dy < floorY || box.minY < floorY) {
            BlockPos floorPos = BlockPos.containing(curX, Math.min(box.minY, box.minY + dy), curZ);
            if (player.level().getBlockState(floorPos).isAir()) {
                double upwardImpulse = Math.max(1.15, Math.abs(dy) * 1.10);
                dy = upwardImpulse;
                player.setDeltaMovement(player.getDeltaMovement().x, upwardImpulse, player.getDeltaMovement().z);
                player.resetFallDistance();
                player.fallDistance = 0.0f;
                if (!player.level().isClientSide()) {
                    player.syncVelocity = true;
                }
                triggerFeedback(player, curX, floorY, curZ, 0.0, 1.0, 0.0, Math.max(0.50, Math.abs(movement.y)));
            }
        }

        // 2. Decken-Kollision (Ceiling Cap: z.B. Y=35 in Tilted Towers, Y=69 in Standard)
        Double ceiling = arena.getCeilingY();
        if (ceiling != null && box.maxY <= ceiling && box.maxY + dy > ceiling) {
            BlockPos ceilPos = BlockPos.containing(curX, ceiling + 0.1, curZ);
            if (player.level().getBlockState(ceilPos).isAir()) {
                dy = Math.max(0.0, ceiling - box.maxY);
                if (dy < 1.0E-5) {
                    dy = -0.25;
                }
                player.setDeltaMovement(player.getDeltaMovement().x, -0.30, player.getDeltaMovement().z);
                if (!player.level().isClientSide()) {
                    player.syncVelocity = true;
                }
                triggerFeedback(player, curX, ceiling, curZ, 0.0, -1.0, 0.0, Math.abs(movement.y));
            }
        }

        // 3. Horizontale Polygon-Kollision mit kräftigem Pushback
        boolean currentlyInside = arena.isInArena(curX, curY, curZ);
        boolean collidedX = false;
        boolean collidedZ = false;

        if (currentlyInside) {
            // Prüfung X-Achse
            if (dx != 0.0) {
                double targetX = curX + dx;
                if (!isXAllowed(player.level(), arena, targetX, curY, curZ, dx)) {
                    double low = 0.0;
                    double high = dx;
                    for (int i = 0; i < 6; i++) {
                        double mid = (low + high) * 0.5;
                        if (isXAllowed(player.level(), arena, curX + mid, curY, curZ, dx)) {
                            low = mid;
                        } else {
                            high = mid;
                        }
                    }
                    dx = low;
                    collidedX = true;
                }
            }

            // Prüfung Z-Achse mit aktualisierter X-Koordinate
            double newX = curX + dx;
            if (dz != 0.0) {
                double targetZ = curZ + dz;
                if (!isZAllowed(player.level(), arena, newX, curY, targetZ, dz)) {
                    double low = 0.0;
                    double high = dz;
                    for (int i = 0; i < 6; i++) {
                        double mid = (low + high) * 0.5;
                        if (isZAllowed(player.level(), arena, newX, curY, curZ + mid, dz)) {
                            low = mid;
                        } else {
                            high = mid;
                        }
                    }
                    dz = low;
                    collidedZ = true;
                }
            }

            if (collidedX || collidedZ) {
                double[] normal = arena.getInwardNormal(curX + dx, curY, curZ + dz);
                double nx = normal[0];
                double nz = normal[1];
                double impactSpeed = Math.hypot(movement.x, movement.z);

                // Spürbar kräftigerer Pushback zurück in die Arena
                double pushStrength = Math.clamp(impactSpeed * 0.85 + 0.35, 0.45, 0.95);
                double pushX = nx * pushStrength;
                double pushZ = nz * pushStrength;

                player.setDeltaMovement(pushX, Math.max(0.15, player.getDeltaMovement().y), pushZ);
                if (!player.level().isClientSide()) {
                    player.syncVelocity = true;
                }

                triggerFeedback(player, curX + dx, box.getCenter().y, curZ + dz, nx, 0.0, nz, impactSpeed);
            }
        } else {
            // Notfall-Rückführung: Spieler befindet sich in Luft außerhalb der Border
            BlockPos curPos = BlockPos.containing(curX, curY + 0.5, curZ);
            if (player.level().getBlockState(curPos).isAir()) {
                double[] normal = arena.getInwardNormal(curX, curY, curZ);
                double nx = normal[0];
                double nz = normal[1];
                double pushX = nx * 0.65;
                double pushZ = nz * 0.65;
                dx = pushX;
                dz = pushZ;

                player.setDeltaMovement(pushX, Math.max(0.40, player.getDeltaMovement().y), pushZ);
                player.resetFallDistance();
                player.fallDistance = 0.0f;
                if (!player.level().isClientSide()) {
                    player.syncVelocity = true;
                }

                triggerFeedback(player, curX, box.getCenter().y, curZ, nx, 0.0, nz, 0.6);
            }
        }

        return new Vec3(dx, dy, dz);
    }

    private static boolean isXAllowed(Level level, Arena arena, double x, double y, double z, double dir) {
        double edgeX = x + (dir >= 0.0 ? COLLISION_RADIUS : -COLLISION_RADIUS);
        return isPointAllowed(level, arena, edgeX, y, z)
            && isPointAllowed(level, arena, edgeX, y, z - COLLISION_RADIUS)
            && isPointAllowed(level, arena, edgeX, y, z + COLLISION_RADIUS);
    }

    private static boolean isZAllowed(Level level, Arena arena, double x, double y, double z, double dir) {
        double edgeZ = z + (dir >= 0.0 ? COLLISION_RADIUS : -COLLISION_RADIUS);
        return isPointAllowed(level, arena, x, y, edgeZ)
            && isPointAllowed(level, arena, x - COLLISION_RADIUS, y, edgeZ)
            && isPointAllowed(level, arena, x + COLLISION_RADIUS, y, edgeZ);
    }

    private static boolean isPointAllowed(Level level, Arena arena, double x, double y, double z) {
        if (arena.isInArena(x, y, z)) {
            return true;
        }
        // Liegt der Punkt außerhalb der Arena:
        // Die Border darf nur bei Blöcken sein, die Luft sind, andernfalls absolut nicht!
        BlockPos feetPos = BlockPos.containing(x, y + 0.1, z);
        BlockPos bodyPos = BlockPos.containing(x, y + 1.0, z);
        boolean hasAir = level.getBlockState(feetPos).isAir() || level.getBlockState(bodyPos).isAir();
        return !hasAir;
    }

    /**
     * Löst visuelles (Partikel) und akustisches Feedback bei einer Border-Kollision aus.
     */
    public static void triggerFeedback(Entity entity, double x, double y, double z,
                                       double nx, double ny, double nz, double speed) {
        long now = System.currentTimeMillis();
        Long last = LAST_FEEDBACK.get(entity.getUUID());
        if (last != null && now - last < 200) {
            return;
        }
        LAST_FEEDBACK.put(entity.getUUID(), now);
        if (LAST_FEEDBACK.size() > 100) {
            LAST_FEEDBACK.entrySet().removeIf(e -> now - e.getValue() > 10000);
        }

        float vol = (float) Math.clamp(speed * 0.85, 0.40, 1.0);
        float pitch = 1.25F + (float) (Math.random() * 0.25);

        Level level = entity.level();
        if (level.isClientSide()) {
            level.playLocalSound(x, y, z, SoundEvents.SHIELD_BLOCK.value(), SoundSource.PLAYERS, vol, pitch, false);
            level.playLocalSound(x, y, z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, vol * 0.7F, pitch * 1.25F, false);
            int count = Math.clamp((int) (speed * 14), 6, 16);
            for (int i = 0; i < count; i++) {
                double ox = (Math.random() - 0.5) * 0.35 + nx * 0.08;
                double oy = (Math.random() - 0.5) * 0.35 + ny * 0.08;
                double oz = (Math.random() - 0.5) * 0.35 + nz * 0.08;
                level.addParticle(ParticleTypes.ELECTRIC_SPARK, x, y, z, ox, oy, oz);
            }
            if (speed > 0.40) {
                level.addParticle(ParticleTypes.SONIC_BOOM, x, y, z, 0.0, 0.0, 0.0);
            }
        } else if (level instanceof ServerLevel serverLevel) {
            ServerPlayer sp = (entity instanceof ServerPlayer p) ? p : null;
            serverLevel.playSound(sp, x, y, z, SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, vol, pitch);
            serverLevel.playSound(sp, x, y, z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, vol * 0.7F, pitch * 1.25F);
            int count = Math.clamp((int) (speed * 12), 5, 14);
            serverLevel.sendParticles(ParticleTypes.ELECTRIC_SPARK, x, y, z, count, 0.15, 0.15, 0.15, 0.14);
            serverLevel.sendParticles(ParticleTypes.WAX_OFF, x, y, z, 4, 0.08, 0.08, 0.08, 0.02);
        }
    }

    public record ProjectileBounce(Vec3 position, Vec3 velocity, boolean bounced) {}

    /**
     * Prüft und berechnet das Abprallen eines geworfenen Projektils an der Arena-Border.
     */
    public static ProjectileBounce handleBorderBounce(Level level, Arena arena, Vec3 pos, Vec3 vel, Vec3 next) {
        if (arena == null || arena.isInArena(next.x, next.y, next.z)) {
            return new ProjectileBounce(pos, vel, false);
        }
        BlockPos nextBlockPos = BlockPos.containing(next.x, next.y, next.z);
        if (!level.getBlockState(nextBlockPos).isAir()) {
            return new ProjectileBounce(pos, vel, false);
        }

        double floorY = arena.getFloorY(pos.x, pos.y, pos.z);
        Double ceiling = arena.getCeilingY();
        Vec3 newVel;
        Vec3 newPos;

        if (next.y < floorY) {
            newVel = new Vec3(vel.x * 0.7, Math.max(0.40, Math.abs(vel.y) * 0.8), vel.z * 0.7);
            newPos = new Vec3(pos.x, floorY + 0.15, pos.z);
        } else if (ceiling != null && next.y > ceiling) {
            newVel = new Vec3(vel.x * 0.7, -Math.abs(vel.y) * 0.8, vel.z * 0.7);
            newPos = new Vec3(pos.x, ceiling - 0.15, pos.z);
        } else {
            double[] normal = arena.getInwardNormal(pos.x, pos.y, pos.z);
            double nx = normal[0];
            double nz = normal[1];
            double dot = vel.x * nx + vel.z * nz;
            if (dot < 0) {
                double refX = vel.x - 2 * dot * nx;
                double refZ = vel.z - 2 * dot * nz;
                double speed = Math.max(0.65, Math.hypot(refX, refZ) * 0.85);
                double dirLen = Math.hypot(refX, refZ);
                if (dirLen > 1.0E-4) {
                    refX = (refX / dirLen) * speed;
                    refZ = (refZ / dirLen) * speed;
                } else {
                    refX = nx * 0.7;
                    refZ = nz * 0.7;
                }
                double upwardVel = Math.max(0.20, vel.y * 0.75 + 0.15);
                newVel = new Vec3(refX, upwardVel, refZ);
            } else {
                double upwardVel = Math.max(0.20, vel.y * 0.75 + 0.15);
                newVel = new Vec3(nx * 0.75, upwardVel, nz * 0.75);
            }
            newPos = pos.add(nx * 0.25, 0.05, nz * 0.25);
        }

        if (level.isClientSide()) {
            level.playLocalSound(pos.x, pos.y, pos.z, SoundEvents.SHIELD_BLOCK.value(), SoundSource.PLAYERS, 0.9F, 1.4F, false);
            level.playLocalSound(pos.x, pos.y, pos.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 0.75F, 1.5F, false);
            for (int i = 0; i < 14; i++) {
                level.addParticle(ParticleTypes.ELECTRIC_SPARK, pos.x, pos.y, pos.z, (Math.random() - 0.5) * 0.3, 0.15, (Math.random() - 0.5) * 0.3);
            }
        } else if (level instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.ELECTRIC_SPARK, pos.x, pos.y, pos.z, 14, 0.15, 0.15, 0.15, 0.18);
            sl.playSound(null, pos.x, pos.y, pos.z, SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 0.9F, 1.4F);
            sl.playSound(null, pos.x, pos.y, pos.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 0.75F, 1.5F);
        }

        return new ProjectileBounce(newPos, newVel, true);
    }
}
