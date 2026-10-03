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
/** So lange nach dem Start zählt die Leertaste als gehalten, bis das Eingabepaket des Clients angekommen ist. */
    private static final int JUMP_INPUT_GRACE_TICKS = 5;

/**
     * Größte Strecke (quadriert), die ein Spieler zwischen zwei Server-Ticks klettern darf, ohne dass die Sitzung
     * abgebrochen wird. Großzügig: Bei Ping-Schwankungen treffen mehrere Positionspakete gebündelt ein, und ein zu
     * strenger Wert bricht ehrliches Klettern ab - was der Spieler als Rücksetzer erlebt.
     */
    private static final double MAX_STEP_DISTANCE_SQR = 6.25;
    /** So lange nach dem Ende einer Sitzung gelten noch unterwegs befindliche Kletterpakete als Klettern. */
    private static final int STOP_GRACE_TICKS = 4;

/** Mindestabstand zwischen zwei neuen Kletteranfragen in Ticks (Schutz gegen Paketflut, kein Spielgefühl). */
    private static final int REQUEST_COOLDOWN_TICKS = 2;

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
/**
     * Ob die Leertaste für diese Sitzung als gehalten gilt: laut letztem Eingabepaket - oder in den ersten Ticks nach
     * dem Start, in denen das Eingabepaket dem Client-Tick noch hinterherhinkt.
     */
    private static boolean jumpHeld(ServerPlayer player, Session session) {
        return player.getLastClientInput().jump() || player.tickCount - session.startedAt <= JUMP_INPUT_GRACE_TICKS;
    }


    private static void handle(ServerPlayer player, Request request) {
        Session session = SESSIONS.computeIfAbsent(player, ignored -> new Session());
        if (!request.start()) {
            if (session.mode != STOP && session.requestId == request.requestId()) stop(player, session);
            return;
        }

        // Herzschlag der laufenden Sitzung: verlängert sie und führt die Wand nach.
        if (session.mode != STOP && session.requestId == request.requestId()) {
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
            return;
        }

        // Eine neue Anfrage löst eine ältere Sitzung ab, die der Client schon verlassen hat. Früher wurde sie still
        // verworfen - der Client wartete dann zwölf Ticks auf eine Antwort, die nie kam.
        // Zu schnell hintereinander: ausdrücklich ablehnen, damit der Client sofort neu ansetzen kann. Geprüft wird
        // vor dem Beenden der alten Sitzung - stop() setzt selbst eine Sperre, die sonst jede Ablösung blockierte.
        if (player.tickCount < session.nextRequestTick) {
            session.stoppedAt = player.tickCount;
            ServerPlayNetworking.send(player, new Motion(player.getId(), request.requestId(), STOP, 0, 0, 0));
            return;
        }
        if (session.mode != STOP) stop(player, session);
        session.nextRequestTick = player.tickCount + REQUEST_COOLDOWN_TICKS;

        net.minecraft.core.Direction wall = null;
        Vec3 target = null;
        // Die Leertaste genügt: Greifen an der Wand und Aufsteigen über eine Kante brauchen keine zweite Taste.
        // Ob sie wirklich gedrückt ist, lässt sich hier noch nicht prüfen: Der Client schickt die Anfrage im selben
        // Tick, in dem er die Taste erkennt, aber sein Eingabepaket folgt erst danach - der Server sähe "nicht
        // gedrückt" und lehnte jeden ersten Versuch ab. Die Anfrage gilt deshalb als Beleg; geprüft wird erst
        // nach der Schonfrist (siehe jumpHeld).
        if (allowed(player)) {
            net.minecraft.core.Direction preferred = (request.wallId() >= 0 && request.wallId() < net.minecraft.core.Direction.values().length)
                    ? net.minecraft.core.Direction.from3DDataValue(request.wallId()) : null;
            wall = WallClimbing.findWall(player, preferred);
            if (wall == null) target = MantleGeometry.findTarget(player);
        }
        if (wall == null && target == null) {
            session.stoppedAt = player.tickCount;
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
        session.startedAt = player.tickCount;
        session.expiresAt = player.tickCount + 30;
        session.cornerGraceTicks = 8;
        if (session.mode == MANTLE) {
            session.apexY = MantleGeometry.findApex(player, session.start, session.target);
        }
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
                || (session.mode == WALL && !jumpHeld(player, session))
                || position.distanceToSqr(session.lastPosition) > MAX_STEP_DISTANCE_SQR) {
            stop(player, session);
            return;
        }
        player.resetFallDistance();
        session.lastPosition = position;
        if (session.mode == WALL) {
            if (!jumpHeld(player, session)) {
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
            Vec3 landing = WallClimbing.wantsUp(player.getLastClientInput().forward(), player.getLastClientInput().backward())
                    ? MantleGeometry.findTarget(player, session.wall) : null;
            if (landing != null && landing.y - player.getY() <= 1.85) {
                session.mode = MANTLE;
                session.target = landing;
                session.start = position;
                session.apexY = MantleGeometry.findApex(player, session.start, session.target);
                session.expiresAt = player.tickCount + 30;
                sendMotion(player, session);
            } else if (player.tickCount - session.lastBroadcastTick >= 10) {
                sendMotion(player, session);
            }
        } else if (!MantleGeometry.hasSupport(player, session.target)
                || position.distanceToSqr(session.start) > 16
                || position.y > session.apexY + 0.50 || position.y < session.start.y - 0.75) {
            stop(player, session);
        }
    }

    public static boolean isClimbing(ServerPlayer player) {
        Session session = SESSIONS.get(player);
        if (session == null || session.mode == STOP || player.tickCount >= session.expiresAt || !allowed(player)) {
            return false;
        }
        if (session.mode == WALL) {
            return jumpHeld(player, session) && (WallClimbing.hasContact(player, session.wall)
                    || WallClimbing.isAtAnyCorner(player, session.wall)
                    || session.cornerGraceTicks > 0);
        }
        return session.mode == MANTLE && MantleGeometry.hasSupport(player, session.target);
    }
/**
     * Ob Positionspakete dieses Spielers gerade als Klettern auszuwerten sind: während einer Sitzung und für wenige
     * Ticks danach. Der Client hat die letzten Schritte schon gesendet, bevor der Abbruch bei ihm ankam; würden sie
     * als normale Bewegung geprüft, setzte der Server den Spieler zurück.
     */
    public static boolean usesClimbingMovement(ServerPlayer player) {
        if (isClimbing(player)) return true;
        Session session = SESSIONS.get(player);
        return session != null && session.mode == STOP && player.tickCount - session.stoppedAt <= STOP_GRACE_TICKS
                && allowed(player);
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
        session.apexY = 0;
        session.stoppedAt = player.tickCount;
        session.graceUntilTick = player.tickCount + 30;
        player.resetFallDistance();
        session.nextRequestTick = player.tickCount + REQUEST_COOLDOWN_TICKS;
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
        private int stoppedAt = Integer.MIN_VALUE / 2;
        private int startedAt = Integer.MIN_VALUE / 2;
        private Vec3 start, target, lastPosition;
        private double apexY;
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
