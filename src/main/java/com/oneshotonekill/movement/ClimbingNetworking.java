package com.oneshotonekill.movement;

import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.item.runtime.GrapplingHookSystem;
import com.oneshotonekill.item.runtime.StatusAbilities;
import com.oneshotonekill.shared.SpecialItemRules;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

@SuppressWarnings("NullableProblems")
public final class ClimbingNetworking {
    public static final int STOP = 0, WALL = 1, MANTLE = 2;
    private static final Map<ServerPlayer, Session> SESSIONS = new WeakHashMap<>();

    private ClimbingNetworking() {
    }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(Request.TYPE, Request.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Motion.TYPE, Motion.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Request.TYPE,
                (payload, context) -> handle(context.player(), payload));
    }

    private static boolean allowed(ServerPlayer player) {
        return MantleGeometry.eligibleBody(player)
                && SpecialItemRules.activeArena(player) == Arena.TILTED_TOWERS
                && SpecialItemRules.canUse(player)
                && !StatusAbilities.INSTANCE.isGliding(player)
                && !GrapplingHookSystem.INSTANCE.isGrappleActive(player);
    }

    private static void handle(ServerPlayer player, Request request) {
        Session session = SESSIONS.computeIfAbsent(player, ignored -> new Session());
        if (!request.start()) {
            if (session.mode != STOP && session.requestId == request.requestId()) stop(player, session);
            return;
        }
        if (player.tickCount < session.nextRequestTick) return;
        session.nextRequestTick = player.tickCount + 6;
        if (session.mode != STOP) {
            if (session.requestId == request.requestId()) {
                session.expiresAt = player.tickCount + 30;
                if (session.mode == WALL && request.wallId() >= 0 && request.wallId() < net.minecraft.core.Direction.values().length) {
                    net.minecraft.core.Direction reqWall = net.minecraft.core.Direction.from3DDataValue(request.wallId());
                    if (reqWall.getAxis().isHorizontal() && (WallClimbing.hasContact(player, reqWall)
                            || WallClimbing.isAtAnyCorner(player, reqWall)
                            || WallClimbing.findAdjacentWall(player, session.wall) == reqWall
                            || session.cornerGraceTicks > 0)) {
                        if (session.wall != reqWall) {
                            session.wall = reqWall;
                            session.cornerGraceTicks = 8;
                            sendMotion(player, session);
                        }
                    }
                }
            }
            return;
        }
        net.minecraft.core.Direction wall = null;
        Vec3 target = null;
        if (allowed(player) && player.getLastClientInput().jump() && player.getLastClientInput().forward()) {
            net.minecraft.core.Direction preferred = (request.wallId() >= 0 && request.wallId() < net.minecraft.core.Direction.values().length)
                    ? net.minecraft.core.Direction.from3DDataValue(request.wallId()) : null;
            wall = WallClimbing.findWall(player, preferred);
            if (wall == null) target = MantleGeometry.findTarget(player);
        }
        if (wall == null && target == null) {
            ServerPlayNetworking.send(player, new Motion(player.getId(), request.requestId(), STOP, 0, 0, 0));
            return;
        }
        player.resetFallDistance();
        session.wall = wall;
        session.target = target;
        session.mode = wall != null ? WALL : MANTLE;
        session.start = player.position();
        session.lastPosition = player.position();
        session.requestId = request.requestId();
        session.expiresAt = player.tickCount + 30;
        session.cornerGraceTicks = 8;
        sendMotion(player, session);
    }

    /**
     * Server-player tick Mixin: contact and gameplay state are rechecked throughout the climb.
     */
    public static void tick(ServerPlayer player) {
        Session session = SESSIONS.get(player);
        if (session == null || session.mode == STOP) return;
        Vec3 position = player.position();
        if (player.tickCount >= session.expiresAt || !allowed(player)
                || (session.mode == WALL && !player.getLastClientInput().jump())
                || position.distanceToSqr(session.lastPosition) > 2.25) {
            stop(player, session);
            return;
        }
        player.resetFallDistance();
        session.lastPosition = position;
        if (session.mode == WALL) {
            if (!player.getLastClientInput().jump()) {
                stop(player, session);
                return;
            }
            if (WallClimbing.hasContact(player, session.wall)) {
                session.cornerGraceTicks = 8;
            } else {
                net.minecraft.core.Direction nextWall = WallClimbing.findAdjacentWall(player, session.wall);
                if (nextWall != null) {
                    session.wall = nextWall;
                    session.cornerGraceTicks = 8;
                    sendMotion(player, session);
                } else if (session.cornerGraceTicks > 0) {
                    session.cornerGraceTicks--;
                } else if (!WallClimbing.isAtAnyCorner(player, session.wall)) {
                    stop(player, session);
                    return;
                }
            }
            // A small lift onto the roof replaces wall climbing only once the edge is near the feet.
            Vec3 landing = player.getLastClientInput().forward()
                    ? MantleGeometry.findTarget(player, session.wall) : null;
            if (landing != null && landing.y - player.getY() <= 0.95) {
                session.mode = MANTLE;
                session.target = landing;
                session.start = position;
                session.expiresAt = player.tickCount + 30;
                sendMotion(player, session);
            } else if (player.tickCount - session.lastBroadcastTick >= 10) {
                sendMotion(player, session);
            }
        } else if (!MantleGeometry.hasSupport(player, session.target)
                || position.distanceToSqr(session.start) > 9
                || position.y > session.target.y + 0.25 || position.y < session.start.y - 0.75) {
            stop(player, session);
        }
    }

    public static boolean isClimbing(ServerPlayer player) {
        Session session = SESSIONS.get(player);
        if (session == null || session.mode == STOP || player.tickCount >= session.expiresAt || !allowed(player)) {
            return false;
        }
        if (session.mode == WALL) {
            return player.getLastClientInput().jump() && (WallClimbing.hasContact(player, session.wall)
                    || WallClimbing.isAtAnyCorner(player, session.wall)
                    || session.cornerGraceTicks > 0);
        }
        return session.mode == MANTLE && MantleGeometry.hasSupport(player, session.target);
    }

    public static boolean shouldNegateFallDamage(ServerPlayer player) {
        Session session = SESSIONS.get(player);
        if (session == null) return false;
        if (session.mode != STOP && isClimbing(player)) return true;
        return player.tickCount <= session.graceUntilTick;
    }

    private static void stop(ServerPlayer player, Session session) {
        session.mode = STOP;
        session.target = null;
        session.wall = null;
        session.graceUntilTick = player.tickCount + 30;
        player.resetFallDistance();
        session.nextRequestTick = player.tickCount + 6;
        broadcast(player, new Motion(player.getId(), session.requestId, STOP, 0, 0, 0));
    }

    private static void sendMotion(ServerPlayer player, Session session) {
        Vec3 data = session.mode == WALL
                ? new Vec3(session.wall.getStepX(), 0, session.wall.getStepZ()) : session.target;
        session.lastBroadcastTick = player.tickCount;
        broadcast(player, new Motion(player.getId(), session.requestId, session.mode, data.x, data.y, data.z));
    }

    private static void broadcast(ServerPlayer player, Motion motion) {
        ServerPlayNetworking.send(player, motion);
        for (ServerPlayer observer : PlayerLookup.tracking(player)) {
            if (observer != player) ServerPlayNetworking.send(observer, motion);
        }
    }

    private static final class Session {
        private int nextRequestTick, requestId, expiresAt, mode, lastBroadcastTick, graceUntilTick, cornerGraceTicks;
        private Vec3 start, target, lastPosition;
        private net.minecraft.core.Direction wall;
    }

    public record Request(int requestId, boolean start, int wallId) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(Identifier.fromNamespaceAndPath("oneshotonekill", "climb_request"));
        public static final StreamCodec<ByteBuf, Request> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Request::requestId,
                ByteBufCodecs.BOOL, Request::start,
                ByteBufCodecs.VAR_INT, Request::wallId, Request::new);

        @Override
        public Type<Request> type() {
            return TYPE;
        }
    }

    /**
     * Wall normal or server-chosen landing point, also delivered to observers for animation.
     */
    public record Motion(int entityId, int requestId, int mode, double x, double y, double z)
            implements CustomPacketPayload {
        public static final Type<Motion> TYPE = new Type<>(Identifier.fromNamespaceAndPath("oneshotonekill", "climb_motion"));
        public static final StreamCodec<ByteBuf, Motion> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Motion::entityId,
                ByteBufCodecs.VAR_INT, Motion::requestId,
                ByteBufCodecs.VAR_INT, Motion::mode,
                ByteBufCodecs.DOUBLE, Motion::x,
                ByteBufCodecs.DOUBLE, Motion::y,
                ByteBufCodecs.DOUBLE, Motion::z, Motion::new);

        @Override
        public Type<Motion> type() {
            return TYPE;
        }
    }
}
