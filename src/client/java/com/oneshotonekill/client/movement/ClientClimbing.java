package com.oneshotonekill.client.movement;

import com.oneshotonekill.client.state.ClientStates.*;
import com.oneshotonekill.movement.ClimbingNetworking;
import com.oneshotonekill.movement.MantleGeometry;
import com.oneshotonekill.movement.WallClimbing;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

@SuppressWarnings({"resource", "BooleanMethodIsAlwaysInverted"})
public final class ClientClimbing {
/** Mindestabstand zwischen zwei Kletteranfragen in Ticks. */
    private static final int REQUEST_COOLDOWN_TICKS = 2;
    /** So lange darf der Server eine vorab gestartete Kletterei unbestätigt lassen, bevor sie abgebrochen wird. */
    private static final int UNCONFIRMED_TIMEOUT_TICKS = 12;
    /** Über wie viele Ticks der Schwung aus dem Sprung in den Aufstieg einfließt. */
    private static final int ENTRY_BOOST_TICKS = 6;

    public static final ClientClimbing INSTANCE = new ClientClimbing();
    private final Map<Entity, Visual> visuals = new WeakHashMap<>();
    private LocalPlayer owner;
    private ClientLevel level;
    private boolean canGrab;
    private Vec3 target, start, expectedPosition;
    private double apexY;
    private Direction wall;
    private ClimbingNetworking.Request outgoing;
    private int sequence, nextRequestTick, lastHeartbeat, lastServerTick;
    private int pendingSince = -1;
    private int age, liftTicks, acrossTicks;
    private float cycle, previousCycle;
    private int cornerRollTicks, wallGrabTicks, mantleLandTicks;
    private float cornerRollAmount;
/** Vertikaler Schwung beim Einrasten (aus dem Absprung), der in den ersten Ticks zusätzlich nach oben trägt. */
    private float entryBoost;


    private ClientClimbing() {
    }

    private static double ease(double t) {
        t = Math.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static boolean isMetal(SoundType st) {
        return st == SoundType.METAL || st == SoundType.COPPER || st == SoundType.IRON
                || st == SoundType.CHAIN || st == SoundType.ANVIL || st == SoundType.NETHERITE_BLOCK
                || st == SoundType.HEAVY_CORE || st == SoundType.VAULT || st == SoundType.COPPER_GRATE
                || st == SoundType.COPPER_BULB;
    }

    private static boolean isWood(SoundType st) {
        return st == SoundType.WOOD || st == SoundType.CHERRY_WOOD || st == SoundType.BAMBOO_WOOD
                || st == SoundType.NETHER_WOOD || st == SoundType.SCAFFOLDING;
    }

    private static boolean isGlass(SoundType st) {
        return st == SoundType.GLASS;
    }

    private boolean allowed(LocalPlayer player) {
        Minecraft client = Minecraft.getInstance();
        return MantleGeometry.eligibleBody(player) && client.gui.screen() == null
                && MinimapState.INSTANCE.isMatchRunning()
                && !MatchStartState.INSTANCE.isCountdownActive()
                && !MatchBannerState.INSTANCE.isMatchPaused()
                && !AbilityStatusState.INSTANCE.isFrozen()
                && !com.oneshotonekill.client.effect.BoogieBombClient.isLocalDancing()
                && AbilityStatusState.INSTANCE.getGlideTicks() <= 0
                && !GrapplePullState.INSTANCE.isGrappleActive(player.getUUID());
    }

    public void tick(LocalPlayer player) {
        if (owner != player || level != player.level()) {
            clear();
            owner = player;
            level = (ClientLevel) player.level();
        }
        visuals.entrySet().removeIf(entry -> entry.getKey().isRemoved()
                || player.tickCount - entry.getValue().refreshed > 30);
        if (cornerRollTicks > 0) cornerRollTicks--;
        if (wallGrabTicks > 0) wallGrabTicks--;
        if (mantleLandTicks > 0) mantleLandTicks--;
        canGrab = false;
        boolean jump = player.input.keyPresses.jump();
        if (!allowed(player)) {
            cancel();
        } else if (isActive()) {
            if (pendingSince >= 0 && player.tickCount - pendingSince > UNCONFIRMED_TIMEOUT_TICKS) cancel();
            else if ((wall != null && !jump) || player.tickCount - lastServerTick > 30) cancel();
            else if (wall != null && player.tickCount - lastHeartbeat >= 10) {
                outgoing = new ClimbingNetworking.Request(sequence, true, wall.get3DDataValue());
                lastHeartbeat = player.tickCount;
            }
        } else {
            Direction grabWall = WallClimbing.findWall(player, null);
            Vec3 ledge = grabWall == null ? MantleGeometry.findTarget(player) : null;
            canGrab = grabWall != null || ledge != null;
            // Die Leertaste genügt, und der Start passiert sofort: Der Server bestätigt erst im Nachhinein
            // (siehe handle). Früher wartete der Client auf seine Antwort, bevor sich irgendetwas bewegte.
            if (canGrab && jump && player.tickCount >= nextRequestTick
                    && ClientPlayNetworking.canSend(ClimbingNetworking.Request.TYPE)) {
                boolean started = grabWall != null ? beginWall(player, grabWall) : beginMantle(player, ledge);
                if (started) {
                    sequence++;
                    pendingSince = player.tickCount;
                    nextRequestTick = player.tickCount + REQUEST_COOLDOWN_TICKS;
                    outgoing = new ClimbingNetworking.Request(sequence, true,
                            grabWall != null ? grabWall.get3DDataValue() : -1);
                }
            }
        }
        if (outgoing != null && ClientPlayNetworking.canSend(ClimbingNetworking.Request.TYPE)) {
            ClientPlayNetworking.send(outgoing);
            outgoing = null;
        }
    }

    public void handle(ClimbingNetworking.Motion motion) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) return;
        Entity entity = client.level.getEntity(motion.entityId());
        if (entity == null) return;
        if (entity != client.player) {
            Visual old = visuals.get(entity);
            if (motion.mode() == ClimbingNetworking.STOP) {
                if (old != null && old.sequence == motion.requestId()) visuals.remove(entity);
            } else {
                int started = old != null && old.sequence == motion.requestId() && old.mode == motion.mode()
                        ? old.started : client.player.tickCount;
                visuals.put(entity, new Visual(started, client.player.tickCount, motion.requestId(), motion.mode()));
            }
            return;
        }
        if (owner != client.player || level != client.level || motion.requestId() != sequence) return;
        if (motion.mode() == ClimbingNetworking.STOP) {
            cancel();
            return;
        }
        if (pendingSince < 0 && !isActive()) return;
        if (!allowed(owner) || (motion.mode() == ClimbingNetworking.WALL && !owner.input.keyPresses.jump())) {
            cancel();
            return;
        }
        // Bestätigung einer vorab gestarteten Kletterei? Dann läuft sie bereits - nichts wird zurückgesetzt.
        boolean confirmation = pendingSince >= 0;
        Vec3 data = new Vec3(motion.x(), motion.y(), motion.z());
        if (motion.mode() == ClimbingNetworking.WALL) {
            Direction normal = WallClimbing.decodeNormal(data);
            if (normal == null || (!WallClimbing.hasContact(owner, normal)
                    && !WallClimbing.isAtAnyCorner(owner, normal)
                    && wall != normal
                    && (wall == null || WallClimbing.findAdjacentWall(owner, wall) != normal))) {
                cancel();
                return;
            }
            // Heartbeats frischen die Freigabe auf, ohne Bewegung oder Animation zurückzusetzen.
            if (wall == null) beginWall(owner, normal);
            wall = normal;
            target = null;
            owner.resetFallDistance();
        } else if (motion.mode() == ClimbingNetworking.MANTLE) {
            // Hat der Client seine Kante selbst schon gefunden und liegt der Server nah daran, bleibt es dabei.
            boolean agrees = confirmation && target != null && target.distanceToSqr(data) < 0.36;
            if (!agrees && !beginMantle(owner, data)) {
                cancel();
                return;
            }
        } else {
            cancel();
            return;
        }
        if (confirmation) lastHeartbeat = owner.tickCount;
        pendingSince = -1;
        lastServerTick = owner.tickCount;
        owner.setSprinting(false);
    }
/**
     * Rastet an der Wand ein: Der Absprung-Schwung wird gemerkt, die Bewegung kurz stillgelegt, Griffgeräusch und
     * Kameraruck laufen an. Gerufen sowohl beim sofortigen Start als auch, wenn der Server eine Wand zuweist.
     */
    private boolean beginWall(LocalPlayer player, Direction normal) {
        wall = normal;
        target = null;
        age = 0;
        cycle = previousCycle = 0;
        expectedPosition = player.position();
        // Nur ein Teil des Absprung-Schwungs: Größere Sprünge pro Tick würden den Server-Check überholen, bevor
        // dessen Sitzung steht, und enden als Rücksetzer.
        entryBoost = (float) Math.clamp(player.getDeltaMovement().y * 0.5, 0.0, 0.18);
        player.setDeltaMovement(Vec3.ZERO);
        wallGrabTicks = 5;
        lastServerTick = player.tickCount;
        lastHeartbeat = player.tickCount;
        player.resetFallDistance();
        player.setSprinting(false);
        playWallGrabSound(player, normal);
        return true;
    }

    /**
     * Beginnt den Aufstieg über eine Kante zum Ziel {@code landing}.
     *
     * @return {@code false}, wenn der Weg dorthin nicht frei ist
     */
    private boolean beginMantle(LocalPlayer player, Vec3 landing) {
        if (!MantleGeometry.clearRoute(player, player.position(), landing)) return false;
        wall = null;
        start = player.position();
        target = landing;
        apexY = MantleGeometry.findApex(player, start, target);
        expectedPosition = start;
        age = 0;
        liftTicks = Math.max(3, (int) Math.ceil(Math.max(0.1, apexY - start.y) * 1.5 / 0.26));
        acrossTicks = Math.max(4, (int) Math.ceil(new Vec3(target.x - start.x, 0, target.z - start.z).length() * 1.5 / 0.22));
        player.setDeltaMovement(Vec3.ZERO);
        lastServerTick = player.tickCount;
        lastHeartbeat = player.tickCount;
        player.setSprinting(false);
        playMantleStartSound(player);
        return true;
    }


    /**
     * Player.travel HEAD. Only the local player gets custom motion; vanilla still handles collision and packets.
     */
    public boolean travel(LocalPlayer player) {
        if (owner != player || !isActive()) return false;
        if (!allowed(player) || (wall != null && !player.input.keyPresses.jump())
                || player.position().distanceToSqr(expectedPosition) > 1.0) {
            cancel();
            return false;
        }
        return wall != null ? climbWall(player) : mantle(player);
    }

    private boolean climbWall(LocalPlayer player) {
        Direction activeWall = wall;
        boolean rawLeft = player.input.keyPresses.left();
        boolean rawRight = player.input.keyPresses.right();
        boolean inverted = WallClimbing.isStrafeInverted(player.getYRot(), activeWall);
        boolean left = inverted ? rawRight : rawLeft;
        boolean right = inverted ? rawLeft : rawRight;

        Direction travelDir = WallClimbing.travelDirection(activeWall, left, right);
        if (!WallClimbing.hasContact(player, activeWall)) {
            WallClimbing.CornerTransition corner = WallClimbing.findCornerTransition(player, activeWall, travelDir);
            if (corner != null) {
                wall = corner.newWall();
                if (corner.displacement().lengthSqr() > 0) {
                    cornerRollAmount = right ? 3.2F : -3.2F;
                    cornerRollTicks = 10;
                    Vec3 out = new Vec3(corner.newWall().getStepX() * corner.dAdj(), 0, corner.newWall().getStepZ() * corner.dAdj());
                    Vec3 along = new Vec3(-activeWall.getStepX() * corner.dCurrent(), 0, -activeWall.getStepZ() * corner.dCurrent());
                    player.move(MoverType.SELF, out);
                    player.move(MoverType.SELF, along);
                    expectedPosition = player.position();
                    playCornerWrapSound(player, corner.newWall());
                    if (level != null) {
                        for (int i = 0; i < 5; i++) {
                            double px = player.getX() + (player.getRandom().nextDouble() - 0.5) * 0.4;
                            double py = player.getY() + 0.8 + (player.getRandom().nextDouble() - 0.5) * 0.4;
                            double pz = player.getZ() + (player.getRandom().nextDouble() - 0.5) * 0.4;
                            level.addParticle(net.minecraft.core.particles.ParticleTypes.POOF, px, py, pz, 0, 0.02, 0);
                        }
                    }
                }
                outgoing = new ClimbingNetworking.Request(sequence, true, wall.get3DDataValue());
                if (ClientPlayNetworking.canSend(ClimbingNetworking.Request.TYPE)) {
                    ClientPlayNetworking.send(outgoing);
                    outgoing = null;
                }
                player.resetFallDistance();
                player.setDeltaMovement(Vec3.ZERO);
                return true;
            } else {
                cancel();
                return false;
            }
        } else if (travelDir != null) {
            Direction inner = travelDir.getOpposite();
            if (inner.getAxis().isHorizontal() && inner != activeWall && WallClimbing.hasContact(player, inner)) {
                wall = inner;
                activeWall = inner;
                outgoing = new ClimbingNetworking.Request(sequence, true, wall.get3DDataValue());
                if (ClientPlayNetworking.canSend(ClimbingNetworking.Request.TYPE)) {
                    ClientPlayNetworking.send(outgoing);
                    outgoing = null;
                }
            }
        }
        age++;
        // Die Leertaste allein klettert hinauf; Rückwärts klettert hinunter; beide zusammen halten den Griff.
        boolean up = WallClimbing.wantsUp(player.input.keyPresses.forward(), player.input.keyPresses.backward());
        Vec3 delta = WallClimbing.movement(activeWall, up, player.input.keyPresses.backward(), left, right);
        Vec3 landing = delta.y > 0 ? MantleGeometry.findTarget(player, activeWall) : null;
        // Hold the grip at a reachable roof edge until the server confirms the transition.
        if (landing != null && landing.y - player.getY() <= 1.85) delta = new Vec3(delta.x, 0, delta.z);
        // Sanftes Anfahren statt Vollgas aus dem Stand, dazu trägt der Schwung des Absprungs in den ersten Ticks mit.
        double ramp = 0.55 + 0.45 * Math.min(1.0, age / 4.0);
        double vertical = delta.y * ramp;
        if (delta.y > 0 && entryBoost > 0 && age <= ENTRY_BOOST_TICKS) {
            vertical += entryBoost * (1.0 - (double) age / ENTRY_BOOST_TICKS);
        }
        delta = new Vec3(delta.x * ramp, vertical, delta.z * ramp);
        if (!MantleGeometry.insideArena(player.position().add(delta))) delta = Vec3.ZERO;
        Vec3 before = player.position();
        player.setSprinting(false);
        player.setDeltaMovement(delta);
        player.move(MoverType.SELF, delta);
        expectedPosition = player.position();
        // Descending or hanging on an actual wall has the same fall behavior as a ladder.
        player.resetFallDistance();
        player.setDeltaMovement(Vec3.ZERO);
        previousCycle = cycle;
        double distance = expectedPosition.distanceTo(before);
        cycle += (float) (distance * 3.8);
        if (distance > 0.025 && age % 8 == 0) {
            playClimbStepSound(player, activeWall);
        }
        return true;
    }

    private boolean mantle(LocalPlayer player) {
        if (!MantleGeometry.hasSupport(player, target)
                || !MantleGeometry.clearRoute(player, start, target, apexY)) {
            cancel();
            return false;
        }
        age++;
        Vec3 next;
        if (age <= liftTicks) {
            double liftProgress = ease((double) age / liftTicks);
            double currentY = start.y + (apexY - start.y) * liftProgress;
            next = new Vec3(start.x, currentY, start.z);
        } else {
            double horizProgress = ease((double) (age - liftTicks) / acrossTicks);
            double currentX = start.x + (target.x - start.x) * horizProgress;
            double currentZ = start.z + (target.z - start.z) * horizProgress;
            double currentY;
            if (apexY > target.y) {
                if (horizProgress <= 0.40) {
                    currentY = apexY;
                } else {
                    double dropProgress = ease((horizProgress - 0.40) / 0.60);
                    currentY = apexY + (target.y - apexY) * dropProgress;
                }
            } else {
                currentY = target.y;
            }
            next = new Vec3(currentX, currentY, currentZ);
        }
        Vec3 delta = next.subtract(player.position());
        if (delta.lengthSqr() > 0.25 || !player.level().noCollision(player,
                MantleGeometry.standingBox(player, player.position()).deflate(1.0E-5).expandTowards(delta))) {
            cancel();
            return false;
        }
        player.setDeltaMovement(delta);
        player.move(MoverType.SELF, delta);
        expectedPosition = player.position();
        if (expectedPosition.distanceToSqr(next) > 0.15) {
            cancel();
            return true;
        }
        player.resetFallDistance();
        player.setDeltaMovement(Vec3.ZERO);
        if (age == liftTicks && level != null) {
            for (int i = 0; i < 4; i++) {
                double px = player.getX() + (player.getRandom().nextDouble() - 0.5) * 0.5;
                double py = target.y + 0.05;
                double pz = player.getZ() + (player.getRandom().nextDouble() - 0.5) * 0.5;
                level.addParticle(net.minecraft.core.particles.ParticleTypes.POOF, px, py, pz, 0, 0.02, 0);
            }
        }
        if (age >= liftTicks + acrossTicks) {
            player.setPos(target.x, target.y, target.z);
            playMantleLandingSound(player);
            mantleLandTicks = 6;
            cancel();
        }
        return true;
    }

    public boolean hasLedge() {
        return canGrab && pendingSince < 0 && !isActive();
    }


    public boolean isActive() {
        return wall != null || target != null;
    }

    public boolean isWallClimbing() {
        return wall != null;
    }

    public boolean isWaiting() {
        return pendingSince >= 0;
    }

    public float pose(Entity entity, float partialTick) {
        if (owner == null || level != Minecraft.getInstance().level) return 0;
        if (entity == owner) {
            if (wall != null) return Math.min(1, (age + partialTick) / 3);
            if (target == null) return 0;
            float progress = (age + partialTick) / (liftTicks + acrossTicks);
            return Math.clamp((1 - progress) * 5, 0.0F, 1.0F);
        }
        Visual visual = visuals.get(entity);
        if (visual == null) return 0;
        float elapsed = owner.tickCount - visual.started + partialTick;
        return visual.mode == ClimbingNetworking.WALL ? Math.min(1, elapsed / 3)
                : Math.clamp((24 - elapsed) / 4, 0.0F, 1.0F);
    }

    public float stroke(Entity entity, float partialTick) {
        if (entity == owner)
            return wall == null ? 0 : (float) Math.sin(previousCycle + (cycle - previousCycle) * partialTick);
        Visual visual = visuals.get(entity);
        if (visual == null || visual.mode != ClimbingNetworking.WALL) return 0;
        return (float) Math.sin((owner.tickCount - visual.started + partialTick) * 0.65);
    }

    public float progress(float partialTick) {
        return target == null ? 0 : Math.min(1, (age + partialTick) / (liftTicks + acrossTicks));
    }

    public float cameraRoll(float partialTick) {
        float roll = 0;
        if (cornerRollTicks > 0) {
            float progress = 1.0F - (cornerRollTicks - partialTick) / 10.0F;
            if (progress >= 0 && progress <= 1) {
                roll += cornerRollAmount * (float) Math.sin(progress * Math.PI);
            }
        }
        if (target != null && liftTicks > 0 && age <= liftTicks) {
            float progress = Math.clamp((age + partialTick) / liftTicks, 0.0F, 1.0F);
            roll += 2.0F * (float) Math.sin(progress * Math.PI * 2.0);
        }
        return roll;
    }

    public float cameraPitch(float partialTick) {
        float pitch = 0;
        if (wallGrabTicks > 0) {
            float progress = (wallGrabTicks - partialTick) / 5.0F;
            if (progress >= 0 && progress <= 1) {
                pitch += -1.2F * (float) Math.sin(progress * Math.PI);
            }
        }
        if (target != null) {
            if (liftTicks > 0 && age <= liftTicks) {
                float progress = Math.clamp((age + partialTick) / liftTicks, 0.0F, 1.0F);
                pitch += -4.2F * (float) Math.sin(progress * Math.PI);
            } else if (acrossTicks > 0 && age > liftTicks) {
                float progress = Math.clamp((age - liftTicks + partialTick) / acrossTicks, 0.0F, 1.0F);
                pitch += 2.2F * (float) Math.sin(progress * Math.PI);
            }
        }
        if (mantleLandTicks > 0) {
            float progress = (mantleLandTicks - partialTick) / 6.0F;
            if (progress >= 0 && progress <= 1) {
                pitch += -3.2F * (float) Math.sin(progress * Math.PI);
            }
        }
        return pitch;
    }

    public float cornerTilt(Entity entity, float partialTick) {
        if (entity == owner && cornerRollTicks > 0) {
            return cameraRoll(partialTick);
        }
        return 0;
    }

    private void cancel() {
        if (isActive() || pendingSince >= 0) {
            outgoing = new ClimbingNetworking.Request(sequence, false, -1);
            nextRequestTick = owner == null ? 0 : owner.tickCount + REQUEST_COOLDOWN_TICKS;
        }
        if (owner != null) owner.resetFallDistance();
        entryBoost = 0.0F;
        wall = null;
        target = null;
        apexY = 0;
        pendingSince = -1;
        canGrab = false;
        cornerRollTicks = 0;
        cornerRollAmount = 0;
        wallGrabTicks = 0;
    }

    public void clear() {
        owner = null;
        level = null;
        wall = null;
        target = null;
        apexY = 0;
        canGrab = false;
        outgoing = null;
        pendingSince = -1;
        nextRequestTick = 0;
        cornerRollTicks = 0;
        cornerRollAmount = 0;
        wallGrabTicks = 0;
        mantleLandTicks = 0;
        visuals.clear();
    }

    private SoundType getWallSoundType(Player player, Direction normal) {
        if (normal == null || player == null) return SoundType.STONE;
        Level lvl = player.level();
        BlockPos handPos = BlockPos.containing(player.getX() - normal.getStepX() * 0.45, player.getY() + 1.2, player.getZ() - normal.getStepZ() * 0.45);
        BlockState state = lvl.getBlockState(handPos);
        if (!state.isAir()) return state.getSoundType();
        BlockPos feetPos = BlockPos.containing(player.getX() - normal.getStepX() * 0.45, player.getY() + 0.3, player.getZ() - normal.getStepZ() * 0.45);
        state = lvl.getBlockState(feetPos);
        if (!state.isAir()) return state.getSoundType();
        BlockPos centerPos = player.blockPosition().relative(normal.getOpposite());
        state = lvl.getBlockState(centerPos);
        if (!state.isAir()) return state.getSoundType();
        return SoundType.STONE;
    }

    private void playWallGrabSound(Player player, Direction normal) {
        SoundType st = getWallSoundType(player, normal);
        if (isMetal(st)) {
            player.playSound(SoundEvents.COPPER_HIT, 0.50F, 1.15F);
            player.playSound(SoundEvents.CHAIN_STEP, 0.35F, 1.10F);
            player.playSound(SoundEvents.ARMOR_EQUIP_GENERIC.value(), 0.50F, 1.25F);
        } else if (isWood(st)) {
            player.playSound(SoundEvents.CHERRY_WOOD_STEP, 0.50F, 1.10F);
            player.playSound(SoundEvents.WOOD_HIT, 0.40F, 1.20F);
            player.playSound(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.50F, 1.25F);
        } else if (isGlass(st)) {
            player.playSound(SoundEvents.GLASS_HIT, 0.25F, 0.85F);
            player.playSound(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.55F, 1.30F);
        } else {
            player.playSound(SoundEvents.DEEPSLATE_HIT, 0.50F, 1.20F);
            player.playSound(SoundEvents.TUFF_STEP, 0.45F, 1.10F);
            player.playSound(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.55F, 1.25F);
        }
    }

    private void playClimbStepSound(Player player, Direction normal) {
        SoundType st = getWallSoundType(player, normal);
        float pitchMod = 0.92F + player.getRandom().nextFloat() * 0.16F;
        if (isMetal(st)) {
            player.playSound(SoundEvents.COPPER_STEP, 0.38F, pitchMod);
            player.playSound(SoundEvents.CHAIN_STEP, 0.22F, pitchMod * 1.05F);
            player.playSound(SoundEvents.ARMOR_EQUIP_GENERIC.value(), 0.18F, 1.30F);
        } else if (isWood(st)) {
            player.playSound(SoundEvents.CHERRY_WOOD_STEP, 0.38F, pitchMod);
            player.playSound(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.18F, 1.30F);
        } else if (isGlass(st)) {
            player.playSound(SoundEvents.AMETHYST_BLOCK_HIT, 0.18F, pitchMod * 0.75F);
            player.playSound(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.22F, 1.35F);
        } else {
            player.playSound(SoundEvents.TUFF_STEP, 0.42F, pitchMod);
            player.playSound(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.20F, 1.35F);
        }
    }

    private void playCornerWrapSound(Player player, Direction newWall) {
        SoundType st = getWallSoundType(player, newWall);
        player.playSound(SoundEvents.ARMOR_EQUIP_ELYTRA.value(), 0.50F, 1.35F);
        if (isMetal(st)) {
            player.playSound(SoundEvents.COPPER_HIT, 0.35F, 1.25F);
            player.playSound(SoundEvents.CHAIN_STEP, 0.30F, 1.15F);
        } else if (isWood(st)) {
            player.playSound(SoundEvents.WOOD_HIT, 0.35F, 1.20F);
            player.playSound(SoundEvents.CHERRY_WOOD_STEP, 0.30F, 1.10F);
        } else {
            player.playSound(SoundEvents.CALCITE_STEP, 0.42F, 1.20F);
            player.playSound(SoundEvents.DEEPSLATE_HIT, 0.35F, 1.25F);
        }
    }

    private void playMantleStartSound(Player player) {
        player.playSound(SoundEvents.PLAYER_ATTACK_WEAK, 0.35F, 0.85F);
        player.playSound(SoundEvents.STONE_PLACE, 0.45F, 1.10F);
        player.playSound(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.45F, 1.20F);
    }

    private void playMantleLandingSound(Player player) {
        player.playSound(SoundEvents.STONE_STEP, 0.70F, 0.95F);
        player.playSound(SoundEvents.TUFF_STEP, 0.50F, 1.15F);
        player.playSound(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.45F, 1.10F);
    }

    private record Visual(int started, int refreshed, int sequence, int mode) {
    }
}
