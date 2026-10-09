package com.oneshotonekill.item.runtime;

import com.oneshotonekill.OneShotOneKill;
import com.oneshotonekill.arena.ArenaWorlds;
import com.oneshotonekill.registry.ModItems;
import com.oneshotonekill.shared.Hologram;
import com.oneshotonekill.shared.SpecialItemRules;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ColorParticleOption;
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
            BlockHitResult block = com.oneshotonekill.shared.PhaseFields.clipThrown(
                    shot.level, shot.owner, shot.position, next, ClipContext.Fluid.ANY);
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
                Hologram.moveLeading(shot.model, shot.position, shot.velocity);
                Hologram.setPose(shot.model, new Vector3f(),
                        new Quaternionf().rotateX(shot.ticks * 0.34F).rotateZ(shot.ticks * 0.11F), new Vector3f(0.42F), 1);
                partyTrail(shot.level, impact, shot.velocity, shot.ticks);
            }
        }
        Iterator<Map.Entry<UUID, Dance>> active = dancers.entrySet().iterator();
        while (active.hasNext()) {
            Map.Entry<UUID, Dance> entry = active.next();
            Dance dance = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || !player.isAlive() || player.isSpectator() || player.level() != dance.level
                    || dance.level != worlds.getActiveLevel() || remaining(dance) <= 0) {
                if (player != null && dance.level == worlds.getActiveLevel()) {
                    boolean premature = remaining(dance) > 500;
                    triggerPartyCrasher(player, premature);
                }
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


/** Flugschweif der Bombe: zwei gegenläufige Konfetti-Spiralen, Funken, Noten und Feuerwerk. */
    private static void partyTrail(ServerLevel level, Vec3 pos, Vec3 velocity, int ticks) {
        Vec3 dir = velocity.lengthSqr() < 1.0E-4 ? new Vec3(0, 1, 0) : velocity.normalize();
        Vec3 side = dir.cross(new Vec3(0, 1, 0));
        side = side.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : side.normalize();
        Vec3 up = dir.cross(side);
        for (int arm = 0; arm < 2; arm++) {
            double angle = ticks * 0.9 + arm * Math.PI;
            Vec3 p = pos.add(side.scale(Math.cos(angle) * 0.28)).add(up.scale(Math.sin(angle) * 0.28));
            level.sendParticles(new DustParticleOptions(COLOURS[(ticks + arm * 3) % COLOURS.length], 0.8F),
                    p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
        level.sendParticles(ParticleTypes.END_ROD, pos.x, pos.y, pos.z, 1, 0.05, 0.05, 0.05, 0.01);
        if (ticks % 3 == 0) {
            level.sendParticles(ParticleTypes.NOTE, pos.x, pos.y + 0.2, pos.z, 0, (ticks % 24) / 24.0, 0, 0, 1);
        }
        if (ticks % 4 == 0) {
            level.sendParticles(ParticleTypes.FIREWORK, pos.x, pos.y, pos.z, 1, 0.1, 0.1, 0.1, 0.02);
        }
    }

    /** Waagerechter Ring aus bunten Staubpartikeln um {@code centre}, jede Farbe der Palette im Wechsel. */
    private static void spawnColourRing(ServerLevel level, Vec3 centre, double radius, double height,
                                        int points, float size) {
        for (int i = 0; i < points; i++) {
            double angle = i * Math.PI * 2.0 / points;
            level.sendParticles(new DustParticleOptions(COLOURS[i % COLOURS.length], size),
                    centre.x + Math.cos(angle) * radius, centre.y + height, centre.z + Math.sin(angle) * radius,
                    1, 0, 0, 0, 0);
        }
    }

private void detonate(ServerLevel level, Vec3 position) {
        level.playSound(null, position.x, position.y, position.z,
                SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 0.9F, 1.4F);
        level.playSound(null, position.x, position.y, position.z,
                SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.PLAYERS, 1.1F, 1.1F);
        level.playSound(null, position.x, position.y, position.z,
                SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.PLAYERS, 1.0F, 1.6F);
        level.playSound(null, position.x, position.y, position.z,
                SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8F, 1.9F);

        // Greller Blitz im Zentrum
        level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 1.0F, 0.95F, 0.45F),
                position.x, position.y + 0.3, position.z, 1, 0, 0, 0, 0);

        // Drei farbige Druckwellen-Ringe: weiter, flacher Bodenring bis hin zur kleinen Krone
        spawnColourRing(level, position, BLAST_RADIUS * 0.9, 0.15, 32, 1.7F);
        spawnColourRing(level, position, BLAST_RADIUS * 0.55, 0.9, 24, 1.5F);
        spawnColourRing(level, position, BLAST_RADIUS * 0.28, 1.6, 16, 1.3F);

        // Funkenregen in alle Richtungen (count 0 = Offset wird zur Geschwindigkeit)
        for (int i = 0; i < 28; i++) {
            double angle = i * Math.PI * 2.0 / 28;
            double lift = 0.12 + 0.28 * ((i * 7) % 5) / 4.0;
            level.sendParticles(ParticleTypes.END_ROD, position.x, position.y + 0.3, position.z,
                    0, Math.cos(angle), lift, Math.sin(angle), 0.32);
        }
        // Funkensäule nach oben
        for (int i = 0; i < 10; i++) {
            level.sendParticles(ParticleTypes.END_ROD, position.x, position.y + 0.2, position.z,
                    0, (i % 3 - 1) * 0.06, 1.0, (i % 2 - 0.5) * 0.12, 0.28 + 0.04 * i);
        }
        level.sendParticles(ParticleTypes.FIREWORK, position.x, position.y + 0.4, position.z, 50, 0.9, 0.7, 0.9, 0.12);
        level.sendParticles(ParticleTypes.NOTE, position.x, position.y + 0.8, position.z, 12, 1.2, 0.6, 1.2, 1.0);
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
        ServerLevel level = dance.level;
        Vec3 top = player.position().add(0, player.getBbHeight() + 0.85, 0);
        int tick = player.tickCount;
        if (tick % 4 == 0) {
            int col = COLOURS[(tick / 4) % COLOURS.length];
            level.sendParticles(new DustParticleOptions(col, 1.2F),
                    top.x, top.y, top.z, 2, 0.3, 0.3, 0.3, 0);
        }
        if (tick % 7 == 0) {
            level.sendParticles(ParticleTypes.NOTE, top.x, top.y - 0.3, top.z, 0,
                    ((int) (seconds * 5) % 24) / 24.0, 0, 0, 1);
        }
        // Zwei Konfetti-Spiralen winden sich um den Tänzer nach oben
        if (tick % 2 == 0) {
            double height = player.getBbHeight();
            double rise = (tick % 24) / 24.0 * height;
            for (int arm = 0; arm < 2; arm++) {
                double angle = tick * 0.45 + arm * Math.PI;
                level.sendParticles(new DustParticleOptions(COLOURS[(tick / 2 + arm * 3) % COLOURS.length], 0.9F),
                        player.getX() + Math.cos(angle) * 0.95, player.getY() + rise, player.getZ() + Math.sin(angle) * 0.95,
                        1, 0, 0, 0, 0);
            }
        }
        // Glitzerfunken rieseln von der Discokugel (count 0 = Offset wird zur Geschwindigkeit)
        if (tick % 10 == 5) {
            for (int i = 0; i < 2; i++) {
                double angle = tick * 0.37 + i * Math.PI;
                level.sendParticles(ParticleTypes.END_ROD, top.x, top.y, top.z,
                        0, Math.cos(angle), -0.35, Math.sin(angle), 0.12);
            }
        }
        // Beat (120 BPM): Feuerwerksfunken und farbiger Bodenring um die Füße
        if (tick % 10 == 0) {
            spawnColourRing(level, player.position(), 1.3, 0.1, 12, 1.0F);
        }
    }

    /** Also used for custom damage paths which deliberately bypass vanilla health loss. */
    public void clearFor(ServerPlayer player) {
        Dance dance = dancers.remove(player.getUUID());
        if (dance != null) {
            boolean premature = remaining(dance) > 500;
            triggerPartyCrasher(player, premature);
            removeEffects(player.getUUID(), dance, player.level().getServer());
        }
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
        dancers.forEach((id, dance) -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                triggerPartyCrasher(player, true);
            }
            removeEffects(id, dance, server);
        });
        dancers.clear();
    }

    private void triggerPartyCrasher(ServerPlayer player, boolean premature) {
        ServerLevel level = player.level();
        Vec3 pos = player.position().add(0, player.getBbHeight() * 0.65, 0);

        if (premature) {
            // Party crashed! Schallplatten-Scratch + Party-Knall + Glitzer
            level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ITEM_BREAK, SoundSource.PLAYERS, 1.25F, 1.65F);
            level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 1.0F, 1.45F);
            level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.PLAYERS, 0.9F, 1.2F);
        } else {
            // Reguläres Ende (15s Finale): Feierlicher Party-Finale-Burst
            level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 1.0F, 1.25F);
            level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.FIREWORK_ROCKET_TWINKLE, SoundSource.PLAYERS, 1.1F, 1.4F);
            level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.7F, 1.8F);
        }

        // Bunter Konfetti- und Partikel-Burst
        for (int colour : COLOURS) {
            level.sendParticles(new DustParticleOptions(colour, 1.4F),
                    pos.x, pos.y, pos.z, 6, 0.45, 0.45, 0.45, 0.22);
        }
        level.sendParticles(ParticleTypes.FIREWORK, pos.x, pos.y, pos.z, 20, 0.4, 0.4, 0.4, 0.25);
        level.sendParticles(ParticleTypes.NOTE, pos.x, pos.y + 0.2, pos.z, 10, 0.5, 0.3, 0.5, 1.0);
        level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 1.0F, 0.95F, 0.45F), pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
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
