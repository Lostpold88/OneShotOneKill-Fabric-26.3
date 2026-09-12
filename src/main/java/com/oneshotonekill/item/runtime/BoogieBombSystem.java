package com.oneshotonekill.item.runtime;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.ArenaWorlds;
import com.oneshotonekill.registry.ModItems;
import com.oneshotonekill.shared.Hologram;
import com.oneshotonekill.shared.SpecialItemRules;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/** Shared arena item: impact grenade, 15 seconds of disco, cancelled by damage.
 * Deadlines use real time so the music and dance also finish together during Slow Motion.
 * Each detonation applies once; walking into the old blast cannot restart the dance.
 */
@SuppressWarnings({"resource", "NullableProblems"})
public final class BoogieBombSystem {
    public static final BoogieBombSystem INSTANCE = new BoogieBombSystem();
    public static final int DURATION_MILLIS = 15_000;
    public static final double BLAST_RADIUS = 5.0;
    private static final int[] COLOURS = {0xEFFF38, 0xFF40CF, 0x38EAFF, 0x71FF42, 0xAB59FF, 0xFF9740};
    private final List<Flight> flights = new ArrayList<>();
    private final Map<UUID, Dance> dancers = new HashMap<>();

    private BoogieBombSystem() {}

    @SuppressWarnings({"resource", "AutoCloseableResource", "nullness", "NullableProblems", "all"})
    public record State(UUID playerId, int remainingMillis, int elapsedMillis) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(OneShotOneKill.INSTANCE.id("boogie_dance"));
        public static final StreamCodec<ByteBuf, State> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, State::playerId,
                ByteBufCodecs.VAR_INT, State::remainingMillis,
                ByteBufCodecs.VAR_INT, State::elapsedMillis, State::new);
        @Override public @NotNull Type<State> type() { return TYPE; }
    }

    @SuppressWarnings("all")
    public boolean throwBomb(ServerLevel level, ServerPlayer owner) {
        if (!SpecialItemRules.canUse(owner)) return false;
        Vec3 eye = owner.getEyePosition();
        Vec3 origin = eye.add(owner.getLookAngle().scale(0.3));
        // Do not spawn through a wall when the player's face touches it.
        BlockHitResult obstruction = level.clip(new ClipContext(eye, origin,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
        if (obstruction.getType() != HitResult.Type.MISS) origin = eye;
        Display.ItemDisplay model = Hologram.spawnEffect(level, origin, new ItemStack(ModItems.BOOGIE_BOMB), 2.0F);
        if (model != null) Hologram.setPose(model, new Vector3f(), new Quaternionf(), new Vector3f(0.42F), 0);
        flights.add(new Flight(owner.getUUID(), level, origin,
                owner.getLookAngle().scale(1.35).add(0, 0.12, 0), model));
        level.playSound(null, origin.x, origin.y, origin.z, SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.8F, 1.15F);
        return true;
    }

    @SuppressWarnings("all")
    public boolean isDancing(ServerPlayer player) {
        Dance dance = dancers.get(player.getUUID());
        return dance != null && dance.level == player.level() && remaining(dance) > 0;
    }

    @SuppressWarnings("all")
    public void tick(MinecraftServer server) {
        ArenaWorlds worlds = OneShotOneKill.INSTANCE.getArenas();
        if (worlds == null || com.oneshotonekill.match.MatchManager.INSTANCE.getCurrentMatchState()
                != com.oneshotonekill.match.MatchManager.MatchState.RUNNING) {
            reset(server);
            return;
        }
        Iterator<Flight> shots = flights.iterator();
        while (shots.hasNext()) {
            Flight shot = shots.next();
            if (shot.level != worlds.getActiveLevel()) {
                Hologram.remove(shot.model);
                shots.remove();
                continue;
            }
            Vec3 next = shot.position.add(shot.velocity);
            BlockHitResult block = shot.level.clip(new ClipContext(shot.position, next,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, CollisionContext.empty()));
            Vec3 impact = block.getType() == HitResult.Type.MISS ? next : block.getLocation();
            boolean collided = block.getType() != HitResult.Type.MISS;
            double nearest = shot.position.distanceToSqr(impact);
            // Swept collision prevents a fast grenade from passing through a player.
            AABB sweep = new AABB(shot.position, next).inflate(0.3);
            for (ServerPlayer target : shot.level.getEntitiesOfClass(ServerPlayer.class, sweep,
                    p -> !p.isSpectator() && p.isAlive() && (shot.ticks > 3 || !p.getUUID().equals(shot.owner)))) {
                AABB bounds = target.getBoundingBox().inflate(0.18);
                Vec3 hit = bounds.contains(shot.position) ? shot.position : bounds.clip(shot.position, next).orElse(null);
                if (hit != null && shot.position.distanceToSqr(hit) <= nearest) {
                    impact = hit;
                    nearest = shot.position.distanceToSqr(hit);
                    collided = true;
                }
            }
            shot.position = impact;
            shot.velocity = shot.velocity.scale(0.99).subtract(0, 0.045, 0);
            shot.ticks++;
            if (collided || shot.ticks >= 100) {
                Hologram.remove(shot.model);
                shots.remove();
                detonate(shot.level, shot.position);
            } else if (shot.model != null) {
                Hologram.move(shot.model, shot.position);
                Hologram.setPose(shot.model, new Vector3f(),
                        new Quaternionf().rotateX(shot.ticks * 0.34F).rotateZ(shot.ticks * 0.11F), new Vector3f(0.42F), 1);
                shot.level.sendParticles(new DustParticleOptions(COLOURS[shot.ticks % COLOURS.length], 0.55F),
                        impact.x, impact.y, impact.z, 1, 0.015, 0.015, 0.015, 0);
            }
        }
        Iterator<Map.Entry<UUID, Dance>> active = dancers.entrySet().iterator();
        while (active.hasNext()) {
            Map.Entry<UUID, Dance> entry = active.next();
            Dance dance = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || !player.isAlive() || player.isSpectator() || player.level() != dance.level
                    || dance.level != worlds.getActiveLevel() || remaining(dance) <= 0) {
                removeEffects(entry.getKey(), dance, server);
                active.remove();
                continue;
            }
            player.stopUsingItem();
            player.setSprinting(false);
            showDisco(player, dance);
            // Resynchronises late joiners and spectators without restarting audio/animation.
            if (server.getTickCount() % 20 == 0) broadcast(entry.getKey(), dance, server);
        }
    }

    private void detonate(ServerLevel level, Vec3 position) {
        level.playSound(null, position.x, position.y, position.z,
                SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 0.9F, 1.4F);
        for (int colour : COLOURS) {
            level.sendParticles(new DustParticleOptions(colour, 1.4F),
                    position.x, position.y + 0.2, position.z, 12, 0.9, 0.7, 0.9, 0.1);
        }
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class,
                new AABB(position, position).inflate(BLAST_RADIUS),
                p -> p.isAlive() && !p.isSpectator() && SpecialItemRules.activeArena(p) != null)) {
            Vec3 centre = player.position().add(0, player.getBbHeight() * 0.5, 0);
            if (centre.distanceToSqr(position) <= BLAST_RADIUS * BLAST_RADIUS) startDance(player);
        }
    }

    private void startDance(ServerPlayer player) {
        clearFor(player);
        Dance dance = new Dance(player.level(), System.nanoTime());
        dancers.put(player.getUUID(), dance);
        player.stopUsingItem();
        player.setSprinting(false);
        player.stopRiding();
        StatusAbilities.INSTANCE.clearOnDeath(player); // ends gliding; other earned buffs survive
        broadcast(player.getUUID(), dance, player.level().getServer());
        showDisco(player, dance);
    }

    private void showDisco(ServerPlayer player, Dance dance) {
        double seconds = elapsed(dance) / 1000.0;
        Vec3 top = player.position().add(0, player.getBbHeight() + 0.85, 0);
        if (player.tickCount % 4 == 0) {
            int col = COLOURS[(player.tickCount / 4) % COLOURS.length];
            dance.level.sendParticles(new DustParticleOptions(col, 1.2F),
                    top.x, top.y, top.z, 2, 0.25, 0.25, 0.25, 0);
        }
        if (player.tickCount % 7 == 0) {
            dance.level.sendParticles(ParticleTypes.NOTE, top.x, top.y - 0.3, top.z, 0,
                    ((int) (seconds * 5) % 24) / 24.0, 0, 0, 1);
        }
    }

    /** Also used for custom damage paths which deliberately bypass vanilla health loss. */
    public void clearFor(ServerPlayer player) {
        Dance dance = dancers.remove(player.getUUID());
        if (dance != null) removeEffects(player.getUUID(), dance, player.level().getServer());
    }

    public void syncJoiningPlayer(ServerPlayer joining) {
        dancers.forEach((id, dance) -> {
            if (dance.level == joining.level() && remaining(dance) > 0)
                ServerPlayNetworking.send(joining, new State(id, remaining(dance), elapsed(dance)));
        });
    }

    public void reset(MinecraftServer server) {
        flights.forEach(shot -> Hologram.remove(shot.model));
        flights.clear();
        dancers.forEach((id, dance) -> removeEffects(id, dance, server));
        dancers.clear();
    }

    private static int elapsed(Dance dance) {
        return Math.clamp((int) ((System.nanoTime() - dance.startNanos) / 1_000_000), 0, DURATION_MILLIS);
    }
    private static int remaining(Dance dance) { return DURATION_MILLIS - elapsed(dance); }

    private static void broadcast(UUID id, Dance dance, MinecraftServer server) {
        if (server == null) return;
        State packet = new State(id, remaining(dance), elapsed(dance));
        for (ServerPlayer viewer : server.getPlayerList().getPlayers())
            if (viewer.level() == dance.level) ServerPlayNetworking.send(viewer, packet);
    }

    private static void removeEffects(UUID id, Dance dance, MinecraftServer server) {
        if (server != null) {
            State packet = new State(id, 0, DURATION_MILLIS);
            for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
                if (viewer.level() == dance.level) ServerPlayNetworking.send(viewer, packet);
            }
        }
    }

    private static final class Flight {
        final UUID owner;
        final ServerLevel level;
        final Display.ItemDisplay model;
        Vec3 position;
        Vec3 velocity;
        int ticks;
        Flight(UUID owner, ServerLevel level, Vec3 position, Vec3 velocity, Display.ItemDisplay model) {
            this.owner = owner; this.level = level; this.position = position; this.velocity = velocity; this.model = model;
        }
    }

    private record Dance(ServerLevel level, long startNanos) {}
}
