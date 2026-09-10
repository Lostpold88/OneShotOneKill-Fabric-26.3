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
    public static final double MAX_RISE = 1.75;
    public static final double MAX_REACH = 0.90;
    public static final double CLEARANCE = 0.02;
    private static final double MIN_RISE = 0.50;

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

    /**
     * Includes the whole standing player and both parts of the L-shaped ascent.
     */
    public static boolean clearRoute(Player player, Vec3 from, Vec3 target) {
        if (!insideArena(from) || !insideArena(target)) return false;
        double rise = target.y - from.y;
        if (rise < -CLEARANCE || rise > MAX_RISE + 0.15
                || new Vec3(target.x - from.x, 0, target.z - from.z).lengthSqr() > 1.60) return false;
        Vec3 up = new Vec3(0, Math.max(0, rise), 0);
        Vec3 across = new Vec3(target.x - from.x, 0, target.z - from.z);
        AABB body = standingBox(player, from).deflate(1.0E-5);
        // The swept boxes also reject thin walls, trapdoors and low ceilings between endpoints.
        return player.level().noCollision(player, body.expandTowards(up))
                && player.level().noCollision(player, body.move(up).expandTowards(across))
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
        return findTarget(player, forward);
    }

    private static @Nullable Vec3 findTarget(Player player, Vec3 forward) {
        if (!eligibleBody(player)) return null;
        Vec3 feet = player.position();
        double inset = player.getBbWidth() * 0.5 + 0.08;
        // Different heights cover low cover, slabs/stairs and a ledge caught during a jump.
        for (double height : new double[]{0.35, 0.85, 1.35}) {
            Vec3 rayStart = feet.add(0, height, 0);
            BlockHitResult wall = clip(player, rayStart, rayStart.add(forward.scale(MAX_REACH)));
            if (wall.getType() != HitResult.Type.BLOCK || wall.isInside()
                    || wall.getDirection().getAxis() == Direction.Axis.Y) continue;
            Direction normal = wall.getDirection();
            Vec3 behindEdge = wall.getLocation().add(-normal.getStepX() * inset, 0, -normal.getStepZ() * inset);
            Vec3 top = new Vec3(behindEdge.x, feet.y + MAX_RISE, behindEdge.z);
            BlockHitResult landing = clip(player, top, new Vec3(top.x, feet.y + MIN_RISE - CLEARANCE, top.z));
            if (landing.getType() != HitResult.Type.BLOCK || landing.isInside()
                    || landing.getDirection() != Direction.UP) continue;
            Vec3 target = landing.getLocation().add(0, CLEARANCE, 0);
            if (target.y - feet.y < MIN_RISE || target.y - feet.y > MAX_RISE) continue;
            if (clearRoute(player, feet, target)) return target;
        }
        return null;
    }

    private static BlockHitResult clip(Player player, Vec3 from, Vec3 to) {
        return player.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, player));
    }

    public static @Nullable Vec3 findTarget(Player player, Direction wallNormal) {
        Vec3 forward = new Vec3(-wallNormal.getStepX(), 0, -wallNormal.getStepZ());
        return findTarget(player, forward);
    }
}
