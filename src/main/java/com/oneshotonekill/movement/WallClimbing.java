package com.oneshotonekill.movement;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Shared contact checks against actual block collision shapes, never against other entities.
 */
@SuppressWarnings("resource")
public final class WallClimbing {
    public static final double UP_SPEED = 0.19;
    public static final double DOWN_SPEED = 0.14;
    public static final double SIDE_SPEED = 0.13;
    private static final double GRIP_REACH = 0.22;

    private WallClimbing() {
    }

    public static @Nullable Direction findWall(Player player, @Nullable Direction preferred) {
        if (preferred != null && hasContact(player, preferred)) return preferred;
        double yaw = Math.toRadians(player.getYRot());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        Direction result = null;
        double best = 0.35;
        for (Direction normal : Direction.Plane.HORIZONTAL) {
            double facing = -fx * normal.getStepX() - fz * normal.getStepZ();
            if (facing > best && hasContact(player, normal)) {
                best = facing;
                result = normal;
            }
        }
        return result;
    }

    public static boolean hasContact(Player player, Direction normal) {
        return hasContact(player, normal, player.getBoundingBox());
    }

    public static boolean hasContact(Player player, Direction normal, AABB box) {
        if (normal.getAxis() == Direction.Axis.Y) return false;
        double low = box.minY + 0.20, high = box.maxY - 0.20;
        AABB grip = switch (normal) {
            case EAST -> new AABB(box.minX - GRIP_REACH, low, box.minZ + 0.08, box.minX + 0.01, high, box.maxZ - 0.08);
            case WEST -> new AABB(box.maxX - 0.01, low, box.minZ + 0.08, box.maxX + GRIP_REACH, high, box.maxZ - 0.08);
            case SOUTH -> new AABB(box.minX + 0.08, low, box.minZ - GRIP_REACH, box.maxX - 0.08, high, box.minZ + 0.01);
            case NORTH -> new AABB(box.minX + 0.08, low, box.maxZ - 0.01, box.maxX - 0.08, high, box.maxZ + GRIP_REACH);
            default -> throw new IllegalArgumentException("Horizontal wall required");
        };
        return player.level().getBlockCollisions(player, grip).iterator().hasNext();
    }

    public static boolean hasCornerBlock(Player player, Direction wall, Direction adj) {
        if (wall.getAxis() == adj.getAxis()) return false;
        AABB box = player.getBoundingBox();
        double low = box.minY + 0.20, high = box.maxY - 0.20;
        double[] xr = wall.getAxis() == Direction.Axis.X ? cornerRange(wall, box.minX, box.maxX) : cornerRange(adj, box.minX, box.maxX);
        double[] zr = wall.getAxis() == Direction.Axis.Z ? cornerRange(wall, box.minZ, box.maxZ) : cornerRange(adj, box.minZ, box.maxZ);
        AABB corner = new AABB(xr[0], low, zr[0], xr[1], high, zr[1]);
        return player.level().getBlockCollisions(player, corner).iterator().hasNext();
    }

    private static double[] cornerRange(Direction dir, double min, double max) {
        return switch (dir) {
            case EAST, SOUTH -> new double[]{min - 0.70, min + 0.45};
            case WEST, NORTH -> new double[]{max - 0.45, max + 0.70};
            default -> new double[]{min, max};
        };
    }

    public static @Nullable Direction travelDirection(Direction normal, boolean left, boolean right) {
        if (left == right) return null;
        return right ? normal.getCounterClockWise() : normal.getClockWise();
    }

    public static @Nullable CornerTransition findCornerTransition(Player player, Direction currentWall, @Nullable Direction travelDir) {
        Direction cw = currentWall.getClockWise();
        Direction ccw = currentWall.getCounterClockWise();
        Direction first = travelDir == cw || travelDir == ccw ? travelDir : cw;
        Direction second = first == cw ? ccw : cw;
        CornerTransition result = checkCornerCandidate(player, currentWall, first);
        if (result != null) return result;
        return checkCornerCandidate(player, currentWall, second);
    }

    private static @Nullable CornerTransition checkCornerCandidate(Player player, Direction currentWall, Direction adj) {
        if (hasContact(player, adj.getOpposite())) {
            return new CornerTransition(adj.getOpposite(), Vec3.ZERO, 0, 0);
        }
        if (hasContact(player, adj)) {
            return new CornerTransition(adj, Vec3.ZERO, 0, 0);
        }
        AABB original = player.getBoundingBox();
        double[] alongCurrent = {0.58, 0.62, 0.54, 0.66, 0.50, 0.70};
        double[] alongAdj = {0.12, 0.14, 0.16, 0.10, 0.18, 0.08, 0.20, 0.06, 0.22};
        for (double dCurrent : alongCurrent) {
            for (double dAdj : alongAdj) {
                Vec3 wrap = new Vec3(
                        -currentWall.getStepX() * dCurrent + adj.getStepX() * dAdj,
                        0,
                        -currentWall.getStepZ() * dCurrent + adj.getStepZ() * dAdj
                );
                AABB targetBox = original.move(wrap);
                if (player.level().noCollision(player, targetBox) && hasContact(player, adj, targetBox)) {
                    return new CornerTransition(adj, wrap, dCurrent, dAdj);
                }
            }
        }
        return null;
    }

    public static @Nullable Direction findAdjacentWall(Player player, Direction currentWall) {
        Direction cw = currentWall.getClockWise();
        Direction ccw = currentWall.getCounterClockWise();
        if (hasContact(player, cw)) return cw;
        if (hasContact(player, ccw)) return ccw;
        if (hasContact(player, cw.getOpposite())) return cw.getOpposite();
        if (hasContact(player, ccw.getOpposite())) return ccw.getOpposite();
        return null;
    }

    public static boolean isAtAnyCorner(Player player, Direction currentWall) {
        Direction cw = currentWall.getClockWise();
        Direction ccw = currentWall.getCounterClockWise();
        return hasCornerBlock(player, currentWall, cw) || hasCornerBlock(player, currentWall, ccw);
    }

    public static Vec3 movement(Direction normal, boolean up, boolean down, boolean left, boolean right) {
        double vertical = up == down ? 0 : up ? UP_SPEED : -DOWN_SPEED;
        if (left == right) {
            return new Vec3(-normal.getStepX() * 0.04, vertical, -normal.getStepZ() * 0.04);
        }
        Direction rightDir = normal.getCounterClockWise();
        double sideways = (right ? 1.0 : -1.0) * SIDE_SPEED;
        return new Vec3(
                rightDir.getStepX() * sideways - normal.getStepX() * 0.04,
                vertical,
                rightDir.getStepZ() * sideways - normal.getStepZ() * 0.04
        );
    }

    /**
     * Normal components are transmitted as exact small integers, never as arbitrary movement vectors.
     */
    public static @Nullable Direction decodeNormal(Vec3 vector) {
        for (Direction normal : Direction.Plane.HORIZONTAL) {
            if (vector.x == normal.getStepX() && vector.y == 0 && vector.z == normal.getStepZ()) return normal;
        }
        return null;
    }

    public record CornerTransition(Direction newWall, Vec3 displacement, double dCurrent, double dAdj) {
    }
}
