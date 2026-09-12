package com.oneshotonekill.movement;

import com.oneshotonekill.arena.Arena;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Shared, collision-shape based ledge detection. Never changes blocks or teleports players.
 */
@SuppressWarnings("resource")
public final class MantleGeometry {
    public static final double MAX_RISE = 2.80;
    public static final double MAX_REACH = 0.90;
    public static final double CLEARANCE = 0.02;
    public static final double MIN_RISE_GROUND = 1.80;
    public static final double MIN_RISE_CLIMB = 0.15;

    private MantleGeometry() {
    }

    public static boolean eligibleBody(Player player) {
        return Arena.TILTED_TOWERS.getDimension().equals(player.level().dimension())
                && insideArena(player.position())
                && player.isAlive() && !player.isSpectator() && !player.isCreative()
                && !player.getAbilities().flying && !player.isPassenger()
                && !player.isSleeping() && !player.isFallFlying() && !player.isSwimming()
                && !player.isInWater() && !player.isInLava() && !player.isShiftKeyDown()
                && player.getPose() == Pose.STANDING;
    }

    public static boolean insideArena(Vec3 position) {
        return Arena.TILTED_TOWERS.isInArena(position.x, position.y, position.z);
    }

    public static AABB standingBox(Player player, Vec3 feet) {
        return player.getDimensions(Pose.STANDING).makeBoundingBox(feet);
    }

    public static double findApex(Player player, Vec3 from, Vec3 target) {
        double minX = Math.min(from.x, target.x) - 0.35;
        double maxX = Math.max(from.x, target.x) + 0.35;
        double minZ = Math.min(from.z, target.z) - 0.35;
        double maxZ = Math.max(from.z, target.z) + 0.35;
        double minY = Math.min(from.y, target.y) - 0.10;
        double maxY = target.y + 1.80;
        AABB spanBox = new AABB(minX, minY, minZ, maxX, maxY, maxZ);

        double maxObstacleY = target.y;
        for (net.minecraft.world.phys.shapes.VoxelShape shape : player.level().getBlockCollisions(player, spanBox)) {
            for (AABB box : shape.toAabbs()) {
                if (box.maxX >= minX && box.minX <= maxX && box.maxZ >= minZ && box.minZ <= maxZ) {
                    if (box.maxY > maxObstacleY && box.maxY <= target.y + 1.60) {
                        maxObstacleY = box.maxY;
                    }
                }
            }
        }
        return Math.max(target.y, maxObstacleY + CLEARANCE);
    }

    /**
     * Includes the whole standing player and both parts of the vaulted ascent.
     */
    public static boolean clearRoute(Player player, Vec3 from, Vec3 target) {
        double apexY = findApex(player, from, target);
        return clearRoute(player, from, target, apexY);
    }

    public static boolean clearRoute(Player player, Vec3 from, Vec3 target, double apexY) {
        if (!insideArena(from) || !insideArena(target)) return false;
        double rise = target.y - from.y;
        double horizDistSqr = new Vec3(target.x - from.x, 0, target.z - from.z).lengthSqr();
        if (rise < -CLEARANCE || rise > MAX_RISE + 0.15 || horizDistSqr > 4.50) return false;
        if (apexY - target.y > 1.65 || apexY - from.y > MAX_RISE + 0.50) return false;

        double lift = Math.max(0, apexY - from.y);
        Vec3 up = new Vec3(0, lift, 0);
        Vec3 across = new Vec3(target.x - from.x, 0, target.z - from.z);
        AABB body = standingBox(player, from).deflate(1.0E-4);
        AABB destBox = standingBox(player, target).deflate(1.0E-4);

        return player.level().noCollision(player, body.expandTowards(up))
                && player.level().noCollision(player, body.move(up).expandTowards(across))
                && player.level().noCollision(player, destBox)
                && hasSupport(player, target);
    }

    public static boolean hasSupport(Player player, Vec3 target) {
        BlockHitResult support = clip(player, target.add(0, 0.04, 0), target.add(0, -0.08, 0));
        return support.getType() == HitResult.Type.BLOCK && support.getDirection() == Direction.UP
                && !support.isInside() && Math.abs(support.getLocation().y + CLEARANCE - target.y) < 0.04;
    }

    public static @Nullable Vec3 findTarget(Player player) {
        if (!eligibleBody(player)) return null;
        double yaw = Math.toRadians(player.getYRot());
        Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        return findTarget(player, forward, false);
    }

    public static @Nullable Vec3 findTarget(Player player, Direction wallNormal) {
        Vec3 forward = new Vec3(-wallNormal.getStepX(), 0, -wallNormal.getStepZ());
        return findTarget(player, forward, true);
    }

    private static @Nullable Vec3 findTarget(Player player, Vec3 forward, boolean isWallClimbing) {
        if (!eligibleBody(player)) return null;
        Vec3 feet = player.position();
        double minRise = isWallClimbing ? MIN_RISE_CLIMB : MIN_RISE_GROUND;

        double[] depths = {
            player.getBbWidth() * 0.5 + 0.08,
            player.getBbWidth() * 0.5 + 0.45,
            player.getBbWidth() * 0.5 + 0.95,
            player.getBbWidth() * 0.5 + 1.35
        };

        for (double height : new double[]{0.35, 0.85, 1.35}) {
            Vec3 rayStart = feet.add(0, height, 0);
            BlockHitResult wall = clip(player, rayStart, rayStart.add(forward.scale(MAX_REACH)));
            if (wall.getType() != HitResult.Type.BLOCK || wall.isInside()
                    || wall.getDirection().getAxis() == Direction.Axis.Y) continue;
            Direction normal = wall.getDirection();
            Vec3 stepDir = new Vec3(-normal.getStepX(), 0, -normal.getStepZ());

            for (double depth : depths) {
                Vec3 probe = wall.getLocation().add(stepDir.scale(depth));
                double topY = feet.y + (isWallClimbing ? 3.00 : MAX_RISE + 0.20);
                double bottomY = feet.y + (isWallClimbing ? -0.20 : minRise - CLEARANCE);

                BlockHitResult landing = clip(player, new Vec3(probe.x, topY, probe.z),
                        new Vec3(probe.x, bottomY, probe.z));
                if (landing.getType() != HitResult.Type.BLOCK || landing.isInside()
                        || landing.getDirection() != Direction.UP) continue;

                Vec3 target = landing.getLocation().add(0, CLEARANCE, 0);
                double rise = target.y - feet.y;
                if (rise < minRise || rise > MAX_RISE) continue;

                double apexY = findApex(player, feet, target);
                if (clearRoute(player, feet, target, apexY)) {
                    return target;
                }
            }
        }
        return null;
    }

    private static BlockHitResult clip(Player player, Vec3 from, Vec3 to) {
        return player.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
    }
}
