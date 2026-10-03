package com.oneshotonekill.client.state;

import com.mojang.blaze3d.platform.NativeImage;
import com.oneshotonekill.arena.Arena;
import com.oneshotonekill.item.runtime.AirstrikeSystem;
import com.oneshotonekill.item.runtime.MinigunRuntime;
import com.oneshotonekill.network.OsokPayloads.*;
import com.oneshotonekill.nuke.NukeSequenceManager.NukePhase;
import com.oneshotonekill.registry.ModItems;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.MapColor.Brightness;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.*;

/**
 * Bündelt alle Client-seitigen Zustandshalter für HUDs, Effekte, Fähigkeiten und Animationen.
 */
@SuppressWarnings({"BooleanMethodIsAlwaysInverted", "unused"})
public final class ClientStates {
    private ClientStates() {
    }

    // =========================================================================
    // AbilityStatusState.java
    // =========================================================================

    /**
     * Der zuletzt gemeldete Stand der eigenen Spezial-Item-Wirkungen.
     * <p>
     * Der Server meldet nur bei Änderung; die Restzeiten laufen hier clientseitig weiter, damit die
     * Balken gleichmäßig leerlaufen statt in Halbsekundenschritten zu springen.
     */
    public static final class AbilityStatusState {
        public static final AbilityStatusState INSTANCE = new AbilityStatusState();

        private static boolean shield;
        private static int vanishTicks;
        private static int magnetTicks;
        private static int glideTicks;
        private static int frozenTicks;
        private static String armedShot = "";
        private static int charges;
        private static int traps;
        private static int turrets;

        private AbilityStatusState() {
        }

        public boolean hasShield() {
            return shield;
        }

        public int getVanishTicks() {
            return vanishTicks;
        }

        public int getMagnetTicks() {
            return magnetTicks;
        }

        public int getGlideTicks() {
            return glideTicks;
        }

        public int getFrozenTicks() {
            return frozenTicks;
        }

        public boolean isFrozen() {
            return frozenTicks > 0;
        }

        public String getArmedShot() {
            return armedShot;
        }

        public int getCharges() {
            return charges;
        }

        public int getTraps() {
            return traps;
        }

        public int getTurrets() {
            return turrets;
        }

        public boolean isEmpty() {
            return !shield && vanishTicks <= 0 && magnetTicks <= 0 && glideTicks <= 0 && frozenTicks <= 0
                    && armedShot.isEmpty() && charges <= 0 && traps <= 0 && turrets <= 0;
        }

        public void handle(AbilityStatusPayload payload) {
            shield = payload.shield();
            vanishTicks = payload.vanishTicks();
            magnetTicks = payload.magnetTicks();
            glideTicks = payload.glideTicks();
            frozenTicks = payload.frozenTicks();
            armedShot = payload.armedShot();
            charges = payload.charges();
            traps = payload.traps();
            turrets = payload.turrets();
        }

        public void tick() {
            if (vanishTicks > 0) {
                vanishTicks--;
            }
            if (magnetTicks > 0) {
                magnetTicks--;
            }
            if (glideTicks > 0) {
                glideTicks--;
            }
            if (frozenTicks > 0) {
                frozenTicks--;
            }
        }

        public void clear() {
            shield = false;
            vanishTicks = 0;
            magnetTicks = 0;
            glideTicks = 0;
            frozenTicks = 0;
            armedShot = "";
            charges = 0;
            traps = 0;
            turrets = 0;
        }
    }

    // =========================================================================
    // AirstrikeAlarmState.java
    // =========================================================================

    /**
     * Zählt den laufenden Bombenalarm für das HUD herunter und rechnet die Flugbahn mit.
     * Die Höhe wird lokal interpoliert, damit der Marker der Bombe folgt, ohne dass der
     * Server jede Position einzeln schicken muss.
     */
    public static final class AirstrikeAlarmState {
        public static final AirstrikeAlarmState INSTANCE = new AirstrikeAlarmState();

        /**
         * Wie lange die Einschlagmeldung nach der Detonation stehen bleibt.
         */
        private static final int AFTERMATH_TICKS = 45;

        private static double targetX;
        private static double targetZ;
        private static double launchY;
        private static double impactY;
        private static int warningTicks = 1;
        private static int remainingTicks;
        private static int aftermathTicks;

        private AirstrikeAlarmState() {
        }

        public boolean isActive() {
            return remainingTicks > 0 || aftermathTicks > 0;
        }

        public boolean isIncoming() {
            return remainingTicks > 0;
        }

        public int getRemainingTicks() {
            return remainingTicks;
        }

        public int getWarningTicks() {
            return warningTicks;
        }

        public double getTargetX() {
            return targetX;
        }

        public double getTargetZ() {
            return targetZ;
        }

        /**
         * Aktuelle Höhe der Bombe, zwischen den Ticks interpoliert; nach dem Einschlag die Kraterhöhe.
         */
        public double bombY(float partialTick) {
            if (!isIncoming()) {
                return impactY;
            }
            float elapsed = warningTicks - remainingTicks + partialTick;
            float progress = Mth.clamp(elapsed / warningTicks, 0.0F, 1.0F);
            return launchY + (impactY - launchY) * progress;
        }

        /**
         * Abstand des Spielers zum Zielpunkt, oder -1, wenn kein Alarm läuft.
         */
        public double distanceToTarget() {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || !isActive()) {
                return -1.0;
            }
            return Math.hypot(player.getX() - targetX, player.getZ() - targetZ);
        }

        public boolean isInBlastRadius() {
            double distance = distanceToTarget();
            return distance >= 0.0 && distance <= AirstrikeSystem.KILL_RADIUS;
        }

        public void handle(AirstrikeAlarmPayload payload) {
            targetX = payload.getTargetX();
            targetZ = payload.getTargetZ();
            launchY = payload.getLaunchY();
            impactY = payload.getImpactY();
            if (payload.getWarningTicks() <= 0) {
                // Einschlagmeldung: der Anflug endet sofort, die Anzeige hallt nach.
                remainingTicks = 0;
                aftermathTicks = AFTERMATH_TICKS;
                CameraShakeState.INSTANCE.trigger(targetX, impactY, targetZ, 48.0, 1.0F, 24);
                return;
            }
            warningTicks = payload.getWarningTicks();
            remainingTicks = warningTicks;
            aftermathTicks = 0;
        }

        public void tick() {
            if (remainingTicks > 0) {
                remainingTicks--;
                if (remainingTicks == 0) {
                    aftermathTicks = AFTERMATH_TICKS;
                    CameraShakeState.INSTANCE.trigger(targetX, impactY, targetZ, 48.0, 1.0F, 24);
                }
            } else if (aftermathTicks > 0) {
                aftermathTicks--;
            }
        }

        public void clear() {
            remainingTicks = 0;
            aftermathTicks = 0;
        }
    }

    // =========================================================================
    // CameraShakeState.java
    // =========================================================================

    /**
     * Verwaltet ein dynamisches Kamera-Wackeln (Screen Shake) auf dem Client,
     * z. B. bei nahegelegenen Explosionen oder Luftangriffen.
     */
    public static final class CameraShakeState {
        public static final CameraShakeState INSTANCE = new CameraShakeState();

        private static final float MAX_SHAKE_YAW = 2.8F;
        private static final float MAX_SHAKE_PITCH = 3.5F;
        private static final float MAX_SHAKE_ROLL = 4.2F;

        private int totalTicks;
        private int remainingTicks;
        private float intensity;

        private CameraShakeState() {
        }

        public boolean isShaking() {
            return remainingTicks > 0 && intensity > 0.001F;
        }

        /**
         * Löst ein Kamera-Wackeln relativ zu einer Explosionsposition aus.
         * <p>
         *
         * @param explosionX    X-Koordinate der Explosion
         * @param explosionY    Y-Koordinate der Explosion
         * @param explosionZ    Z-Koordinate der Explosion
         * @param maxDistance   Maximaler Radius in Blöcken, in dem die Erschütterung spürbar ist
         * @param baseIntensity Basis-Stärke (z. B. 1.0F)
         * @param durationTicks Dauer der Erschütterung in Ticks
         */
        public void trigger(double explosionX, double explosionY, double explosionZ, double maxDistance, float baseIntensity, int durationTicks) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) {
                return;
            }

            double distance = player.position().distanceTo(new Vec3(explosionX, explosionY, explosionZ));
            if (distance > maxDistance) {
                return;
            }

            // Quadratisch abfallende Intensität mit zunehmender Distanz
            float distanceFactor = (float) Math.max(0.0, 1.0 - (distance / maxDistance));
            float newIntensity = (float) Math.pow(distanceFactor, 1.5) * baseIntensity;

            if (newIntensity > this.intensity || remainingTicks <= 0) {
                this.intensity = newIntensity;
                this.totalTicks = Math.max(1, durationTicks);
                this.remainingTicks = this.totalTicks;
            }
        }

        /**
         * Direkter Wackler (z. B. beim Match-Startschuss oder Flash).
         */
        public void triggerDirect(float baseIntensity, int durationTicks) {
            if (baseIntensity > this.intensity || remainingTicks <= 0) {
                this.intensity = baseIntensity;
                this.totalTicks = Math.max(1, durationTicks);
                this.remainingTicks = this.totalTicks;
            }
        }

        public void tick() {
            if (remainingTicks > 0) {
                remainingTicks--;
                if (remainingTicks == 0) {
                    intensity = 0.0F;
                }
            }
        }

        public void clear() {
            remainingTicks = 0;
            totalTicks = 0;
            intensity = 0.0F;
        }

        private float getProgress(float partialTick) {
            if (remainingTicks <= 0 || totalTicks <= 0) {
                return 0.0F;
            }
            float current = remainingTicks - partialTick;
            return Mth.clamp(current / (float) totalTicks, 0.0F, 1.0F);
        }

        public float getYawOffset(float partialTick) {
            float progress = getProgress(partialTick);
            if (progress <= 0.0F) {
                return 0.0F;
            }
            float time = (totalTicks - remainingTicks + partialTick);
            float decay = progress * progress;
            return (float) (Math.sin(time * 1.9) * Math.cos(time * 0.9) * MAX_SHAKE_YAW * intensity * decay);
        }

        public float getPitchOffset(float partialTick) {
            float progress = getProgress(partialTick);
            if (progress <= 0.0F) {
                return 0.0F;
            }
            float time = (totalTicks - remainingTicks + partialTick);
            float decay = progress * progress;
            return (float) (Math.cos(time * 2.5) * MAX_SHAKE_PITCH * intensity * decay);
        }

        public float getRollOffset(float partialTick) {
            float progress = getProgress(partialTick);
            if (progress <= 0.0F) {
                return 0.0F;
            }
            float time = (totalTicks - remainingTicks + partialTick);
            float decay = progress * progress;
            return (float) (Math.sin(time * 2.1) * MAX_SHAKE_ROLL * intensity * decay);
        }
    }

    // =========================================================================
    // GlideState.java
    // =========================================================================

    /**
     * Clientkopie der Spieler, die gerade im Gleitflug sind.
     */
    public static final class GlideState {
        public static final GlideState INSTANCE = new GlideState();

        private Set<UUID> activePlayers = Set.of();

        private GlideState() {
        }

        public Set<UUID> activePlayers() {
            return activePlayers;
        }

        public void handle(GlidingPlayersPayload payload) {
            activePlayers = Set.copyOf(payload.players());
        }

        public void clear() {
            activePlayers = Set.of();
        }
    }

    // =========================================================================
    // GrapplePullState.java
    // =========================================================================

    /**
     * Clientkopie des einen Grappler-Hakens pro Spieler.
     * <p>
     * <p>Seil und leeres Handmodell hängen am gesamten Grapple-Zustand. Nur die Neigung von
     * Körper und Kamera wird mit der eigentlichen Zugphase weich ein- und ausgeblendet.</p>
     */
    public static final class GrapplePullState {
        public static final GrapplePullState INSTANCE = new GrapplePullState();

        private static final float ENTER_STEP = 0.20F;
        private static final float EXIT_STEP = 0.28F;
        private static final float AIM_ENTER_STEP = 0.28F;
        private static final float AIM_EXIT_STEP = 0.35F;
        private final Map<UUID, Pull> pulls = new HashMap<>();
        private float recoil;
        private float previousRecoil;

        private GrapplePullState() {
        }

        public void triggerRecoil() {
            this.recoil = 1.0F;
            this.previousRecoil = 1.0F;
        }

        public float recoil(float partialTick) {
            return Mth.lerp(partialTick, previousRecoil, recoil);
        }

        public void handle(GrapplePullPayload payload) {
            Pull pull = pulls.get(payload.player());
            if (payload.active()) {
                Vec3 hook = new Vec3(payload.hookX(), payload.hookY(), payload.hookZ());
                LocalPlayer localPlayer = Minecraft.getInstance().player;
                if (localPlayer != null && payload.player().equals(localPlayer.getUUID())) {
                    if (pull == null || !pull.grappleActive) {
                        triggerRecoil();
                    }
                }
                if (pull == null) {
                    pulls.put(payload.player(), new Pull(hook, payload.pulling(), payload.retracting(), payload.normalDir()));
                } else {
                    boolean freshlyLatched = payload.pulling() && !pull.pulling;
                    pull.previousHook = (freshlyLatched || !pull.grappleActive) ? hook : pull.hook;
                    pull.hook = hook;
                    pull.grappleActive = true;
                    pull.pulling = payload.pulling();
                    pull.retracting = payload.retracting();
                    pull.normalDir = payload.normalDir();
                }
            } else if (pull != null) {
                pull.grappleActive = false;
                pull.pulling = false;
                pull.retracting = false;
            }
        }

        public void tick() {
            previousRecoil = recoil;
            if (recoil > 0.0F) {
                recoil = Math.max(0.0F, recoil - 0.20F);
            }
            LocalPlayer localPlayer = Minecraft.getInstance().player;
            if (localPlayer != null && isPulling(localPlayer.getUUID())) {
                if (localPlayer.isUsingItem() && localPlayer.getUseItem().is(Items.BOW)) {
                    localPlayer.stopUsingItem();
                }
            }
            var iterator = pulls.entrySet().iterator();
            while (iterator.hasNext()) {
                Pull pull = iterator.next().getValue();
                pull.previousBlend = pull.blend;
                pull.previousAimBlend = pull.aimBlend;
                float target = pull.pulling ? 1.0F : 0.0F;
                float step = pull.pulling ? ENTER_STEP : EXIT_STEP;
                pull.blend += Math.clamp(target - pull.blend, -step, step);
                float aimTarget = pull.grappleActive ? 1.0F : 0.0F;
                float aimStep = pull.grappleActive ? AIM_ENTER_STEP : AIM_EXIT_STEP;
                pull.aimBlend += Math.clamp(aimTarget - pull.aimBlend, -aimStep, aimStep);
                if (!pull.grappleActive && pull.blend <= 0.001F && pull.aimBlend <= 0.001F) {
                    iterator.remove();
                }
            }
        }

        /**
         * Solange dies wahr ist, befindet sich der einzige Pömpel außerhalb der Handwaffe.
         */
        public boolean isGrappleActive(UUID player) {
            Pull pull = pulls.get(player);
            return pull != null && pull.grappleActive;
        }

        public boolean isPulling(UUID player) {
            Pull pull = pulls.get(player);
            return pull != null && pull.grappleActive && pull.pulling;
        }

        public boolean isRetracting(UUID player) {
            Pull pull = pulls.get(player);
            return pull != null && pull.grappleActive && pull.retracting;
        }

        public @Nullable Direction hitDirection(UUID player) {
            Pull pull = pulls.get(player);
            if (pull == null || pull.normalDir < 0 || pull.normalDir >= 6) {
                return null;
            }
            return Direction.from3DDataValue(pull.normalDir);
        }

        /**
         * Pro Bild interpolierter Endpunkt für das Seil; {@code null} nach vollständigem Einzug.
         */
        public @Nullable Vec3 hookPosition(UUID player, float partialTick) {
            Pull pull = pulls.get(player);
            if (pull == null || !pull.grappleActive) {
                return null;
            }
            return pull.previousHook.lerp(pull.hook, Math.clamp(partialTick, 0.0F, 1.0F));
        }

        /**
         * Winkel zur Ankerposition an der interpolierten Augenposition der Figur.
         */
        public @Nullable RenderPose pose(LivingEntity entity, float partialTick) {
            return pose(entity, partialTick, false);
        }

        /**
         * Blickrichtung zum fliegenden Haken, weich über dessen vollständige Lebenszeit eingeblendet.
         * Anders als {@link #pose(LivingEntity, float)} beginnt diese Haltung bereits beim Abschuss.
         */
        public @Nullable RenderPose aimPose(LivingEntity entity, float partialTick) {
            return pose(entity, partialTick, true);
        }

        private @Nullable RenderPose pose(LivingEntity entity, float partialTick, boolean aiming) {
            Pull pull = pulls.get(entity.getUUID());
            if (pull == null) {
                return null;
            }
            float blend = aiming
                    ? Mth.lerp(partialTick, pull.previousAimBlend, pull.aimBlend)
                    : Mth.lerp(partialTick, pull.previousBlend, pull.blend);
            if (blend <= 0.001F) {
                return null;
            }

            Vec3 anchor = hookPosition(entity.getUUID(), partialTick);
            if (anchor == null) {
                return null;
            }
            Vec3 delta = anchor.subtract(entity.getEyePosition(partialTick));
            double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
            if (delta.lengthSqr() < 1.0E-6) {
                return null;
            }
            float yaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
            float elevation = (float) Math.toDegrees(Math.atan2(delta.y, horizontal));
            return new RenderPose(yaw, elevation, blend);
        }

        private @Nullable RenderPose firstPersonPose(float partialTick) {
            Minecraft client = Minecraft.getInstance();
            LocalPlayer player = client.player;
            if (player == null || !client.options.getCameraType().isFirstPerson()) {
                return null;
            }
            return pose(player, partialTick);
        }

        /**
         * Die Egoansicht neigt sich sanft mit der vertikalen Zugneigung nach oben/unten (ohne seitliches Rollen).
         */
        public float cameraPitch(float partialTick) {
            RenderPose pose = firstPersonPose(partialTick);
            if (pose == null) {
                return 0.0F;
            }
            Minecraft client = Minecraft.getInstance();
            LocalPlayer player = client.player;
            if (player == null) return 0.0F;
            float targetPitch = -pose.elevation;
            float currentPitch = player.getXRot(partialTick);
            float deltaPitch = Mth.wrapDegrees(targetPitch - currentPitch);
            return deltaPitch * pose.blend * 0.35F;
        }

        /**
         * Die Egoansicht rollt sich sanft bei seitlichen Grappler-Schwüngen in die Kurve.
         */
        public float cameraRoll(float partialTick) {
            RenderPose pose = firstPersonPose(partialTick);
            if (pose == null) {
                return 0.0F;
            }
            Minecraft client = Minecraft.getInstance();
            LocalPlayer player = client.player;
            if (player == null) return 0.0F;
            float deltaYaw = Mth.wrapDegrees(pose.yaw - player.getYRot(partialTick));
            return Math.clamp(deltaYaw * 0.045F, -2.5F, 2.5F) * pose.blend;
        }

        public void clear() {
            pulls.clear();
            recoil = 0.0F;
            previousRecoil = 0.0F;
        }

        public record RenderPose(float yaw, float elevation, float blend) {
        }

        private static final class Pull {
            private Vec3 previousHook;
            private Vec3 hook;
            private boolean grappleActive = true;
            private boolean pulling;
            private boolean retracting;
            private byte normalDir;
            private float previousBlend;
            private float blend;
            private float previousAimBlend;
            private float aimBlend;

            private Pull(Vec3 hook, boolean pulling, boolean retracting, byte normalDir) {
                this.previousHook = hook;
                this.hook = hook;
                this.pulling = pulling;
                this.retracting = retracting;
                this.normalDir = normalDir;
            }
        }
    }

    // =========================================================================
    // MagnetFieldState.java
    // =========================================================================

    /**
     * Clientkopie der weltweit sichtbaren, aktiven Pfeilmagnet-Felder.
     */
    public static final class MagnetFieldState {
        public static final MagnetFieldState INSTANCE = new MagnetFieldState();

        private Set<UUID> activePlayers = Set.of();

        private MagnetFieldState() {
        }

        public Set<UUID> activePlayers() {
            return activePlayers;
        }

        public void handle(MagnetFieldsPayload payload) {
            activePlayers = Set.copyOf(payload.players());
        }

        public void clear() {
            activePlayers = Set.of();
        }
    }

    // =========================================================================
    // DeployableMarkerState.java
    // =========================================================================

    /**
     * Die eigenen abgestellten Geräte für die HUD-Peilung.
     * <p>
     * Reiner Empfangsspeicher: Der Server schickt die Liste bei jeder Änderung und im groben
     * Raster des {@code Broadcaster}. Eine leere Liste löscht die Peilung, ein eigenes Aufräumen
     * beim Verlassen der Arena braucht es deshalb nicht.
     */
    public static final class DeployableMarkerState {
        public static final DeployableMarkerState INSTANCE = new DeployableMarkerState();

        private List<DeployableMarkersPayload.Marker> markers = List.of();

        private DeployableMarkerState() {
        }

        public List<DeployableMarkersPayload.Marker> markers() {
            return markers;
        }

        public void handle(DeployableMarkersPayload payload) {
            markers = List.copyOf(payload.markers());
        }

        public void clear() {
            markers = List.of();
        }
    }

    // =========================================================================
    // MatchStartState.java
    // =========================================================================

    /**
     * Die Kamera- und Bildeffekte rund um den Match-Start.
     * <p>
     * Der Countdown läuft hier lokal weiter: der Server meldet den Stand in Ticks, herunterzählen und
     * zwischen den Ticks interpolieren macht der Client. Nur so lässt sich die Anzeige flüssig
     * animieren, statt einmal je Sekunde umzuspringen.
     * <p>
     * Die Sequenz ist eine kleine Kino-Szene: erst eine Titelkarte mit Kamerafahrt (Kran-Orbit um den
     * Spieler, Dolly-Zoom, schiefer Horizont), dann 3-2-1 mit je einem Kamera-Punch und zuletzt der
     * Startschuss.
     */
    public static final class MatchStartState {
        public static final MatchStartState INSTANCE = new MatchStartState();

        /**
         * Gesamtlänge: Titelkarte plus Countdown. Der Server zählt mit derselben Länge
         * ({@code MatchManager.Countdown}).
         */
        public static final int COUNTDOWN_TICKS = 100;
        /**
         * Länge der Titelkarte vor dem eigentlichen 3-2-1.
         */
        public static final int INTRO_TICKS = 40;
        /**
         * So lange hallt der Startschuss auf dem Bildschirm nach.
         */
        public static final int GO_TICKS = 36;

        /**
         * Zeitpunkte (in Ticks seit Sequenzbeginn), an denen die Titelkarte "einschlägt".
         */
        public static final int HIT_ONE_SHOT = 8;
        public static final int HIT_ONE_KILL = 16;

        private static float fovBoost;
        private static float portalIntensity;
        private static float confusionIntensity;
        private static int remainingTicks = -1;
        private static int goTicks;
        private static String mapName = "Standard";
        private static String gameModeName = "CLASSIC";

        private MatchStartState() {
        }

        public float getFovBoost() {
            return fovBoost;
        }

        public void setFovBoost(float value) {
            fovBoost = value;
        }

        public float getPortalIntensity() {
            return portalIntensity;
        }

        public void setPortalIntensity(float value) {
            portalIntensity = value;
        }

        public float getConfusionIntensity() {
            return confusionIntensity;
        }

        public void setConfusionIntensity(float value) {
            confusionIntensity = value;
        }

        public String getMapName() {
            return mapName;
        }

        public String getGameModeName() {
            // Der Server schickt die Modus-Kennung, übersetzt wird hier in der Sprache des Spielers.
            return net.minecraft.network.chat.Component.translatable("GUN_GAME".equals(gameModeName)
                    ? "hud.oneshotonekill.mode.gungame" : "hud.oneshotonekill.mode.classic").getString();
        }

        public boolean isCountdownActive() {
            return remainingTicks > 0;
        }

        /**
         * Restzeit in Ticks, zwischen zwei Ticks interpoliert – die Grundlage jeder Animation.
         */
        public float getRemainingTicks(float partialTick) {
            return Math.max(0.0F, remainingTicks - partialTick);
        }

        /**
         * Vergangene Zeit der Sequenz in Ticks (0 bis {@link #COUNTDOWN_TICKS}), interpoliert.
         */
        public float elapsedTicks(float partialTick) {
            return Math.clamp(COUNTDOWN_TICKS - getRemainingTicks(partialTick), 0.0F, (float) COUNTDOWN_TICKS);
        }

        /**
         * Fortschritt der Titelkarte von 0 bis 1.
         */
        public float introProgress(float partialTick) {
            return Math.clamp(elapsedTicks(partialTick) / INTRO_TICKS, 0.0F, 1.0F);
        }

        /**
         * Aktuelle Ziffer 3, 2, 1 – oder 0, solange noch die Titelkarte läuft.
         */
        public int countdownSecond(float partialTick) {
            float remaining = getRemainingTicks(partialTick);
            if (remaining > COUNTDOWN_TICKS - INTRO_TICKS) {
                return 0;
            }
            return Math.clamp(Mth.ceil(remaining / 20.0F), 1, 3);
        }

        /**
         * Fortschritt innerhalb der aktuellen Ziffer von 0 (gerade eingeschlagen) bis 1.
         */
        public float secondProgress(float partialTick) {
            float remaining = getRemainingTicks(partialTick);
            if (remaining > COUNTDOWN_TICKS - INTRO_TICKS) {
                return 0.0F;
            }
            return Math.clamp(1.0F - (remaining % 20.0F) / 20.0F, 0.0F, 1.0F);
        }

        /**
         * Schlag-Impuls: 1,0 genau auf einem Beat, danach rasch abklingend. Beats sind die beiden
         * Titel-Einschläge und der Beginn jeder Ziffer.
         */
        public float beatPunch(float partialTick) {
            float elapsed = elapsedTicks(partialTick);
            float punch = Math.max(hit(elapsed, HIT_ONE_SHOT, 3.0F), hit(elapsed, HIT_ONE_KILL, 3.0F));
            for (int beat = 0; beat < 3; beat++) {
                punch = Math.max(punch, hit(elapsed, INTRO_TICKS + beat * 20, 3.5F));
            }
            return punch;
        }

        private static float hit(float elapsed, float at, float decay) {
            return elapsed < at ? 0.0F : (float) Math.exp(-(elapsed - at) / decay);
        }

        /**
         * Restlicher Nachhall des Startschusses von 1 (gerade eben) bis 0.
         */
        public float getGoProgress(float partialTick) {
            return goTicks <= 0 ? 0.0F : Math.max(0.0F, (goTicks - partialTick) / GO_TICKS);
        }

        /**
         * Ticks seit dem Startschuss, interpoliert.
         */
        public float goElapsedTicks(float partialTick) {
            return (1.0F - getGoProgress(partialTick)) * GO_TICKS;
        }

        /**
         * Kamerafahrt: weich von weit weg (Kran-Perspektive) bis nah vors Gesicht, dazu ein kurzer
         * Punch nach vorn auf jedem Beat.
         */
        public float getCameraDistance(float partialTick) {
            if (!isCountdownActive()) {
                return 4.0F;
            }
            float t = smoother(elapsedTicks(partialTick) / COUNTDOWN_TICKS);
            float distance = Mth.lerp(t, 7.5F, 1.55F);
            distance -= 0.45F * beatPunch(partialTick);
            float breathing = (float) Math.sin((Util.getMillis() % 2400L) / 2400.0 * Math.PI * 2.0) * 0.03F;
            return Math.max(1.0F, distance + breathing);
        }

        /**
         * Gierwinkel-Versatz der Kamera um den Spieler: startet hinter ihm und schwingt nach vorn.
         */
        public float cameraOrbitYaw(float partialTick) {
            float t = smoother(elapsedTicks(partialTick) / (COUNTDOWN_TICKS - 8.0F));
            float sway = (float) Math.sin(elapsedTicks(partialTick) * 0.11F) * 2.2F * (1.0F - t);
            return 165.0F * (1.0F - t) + sway;
        }

        /**
         * Absoluter Kamera-Nickwinkel: hoch über dem Spieler und auf Augenhöhe herunter.
         */
        public float cameraPitch(float partialTick) {
            float t = smoother(elapsedTicks(partialTick) / COUNTDOWN_TICKS);
            return Mth.lerp(t, 28.0F, 4.0F) - 1.6F * beatPunch(partialTick);
        }

        /**
         * Schiefer Horizont (Dutch Angle), der sich einpendelt, mit einem Kick auf jedem Beat.
         */
        public float cameraRoll(float partialTick) {
            float elapsed = elapsedTicks(partialTick);
            float tilt = -9.0F * (1.0F - smoother(elapsed / 70.0F));
            float direction = ((int) (elapsed / 20.0F) & 1) == 0 ? 1.0F : -1.0F;
            return tilt + direction * 2.6F * beatPunch(partialTick);
        }

        /**
         * Dolly-Zoom: Je näher die Kamera rückt, desto weiter öffnet sich das Sichtfeld.
         */
        public float cinematicFov() {
            float elapsed = COUNTDOWN_TICKS - remainingTicks;
            float t = smoother(elapsed / COUNTDOWN_TICKS);
            return Mth.lerp(t, -0.16F, 0.10F) + 0.07F * beatPunch(0.0F);
        }

        private static float smoother(float t) {
            float x = Math.clamp(t, 0.0F, 1.0F);
            return x * x * x * (x * (x * 6.0F - 15.0F) + 10.0F);
        }

        private static void ui(net.minecraft.sounds.SoundEvent sound, float pitch, float volume) {
            Minecraft client = Minecraft.getInstance();
            if (!client.isPaused()) {
                client.getSoundManager().play(
                        net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(sound, pitch, volume));
            }
        }

        private static void playSequenceSounds(int elapsed) {
            // Start der Kamerafahrt: Luftzug und Aufheulen
            if (elapsed == 1) {
                ui(net.minecraft.sounds.SoundEvents.ENDER_DRAGON_FLAP, 0.6F, 0.9F);
                ui(net.minecraft.sounds.SoundEvents.FIREWORK_ROCKET_LAUNCH, 0.7F, 0.8F);
            }
            // Titel-Einschläge
            if (elapsed == HIT_ONE_SHOT) {
                ui(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BASEDRUM.value(), 0.5F, 1.0F);
                ui(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 0.7F, 0.6F);
            }
            if (elapsed == HIT_ONE_KILL) {
                ui(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BASEDRUM.value(), 0.62F, 1.0F);
                ui(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 0.94F, 0.6F);
            }
            // Riser: steigende Töne bis zur ersten Ziffer
            if (elapsed > 22 && elapsed < INTRO_TICKS && elapsed % 3 == 0) {
                float rise = (elapsed - 22) / (float) (INTRO_TICKS - 22);
                ui(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 0.6F + rise * 1.3F, 0.3F);
            }
            // Ziffern: knackiger Klick; die Wucht kommt aus dem Server-Ton
            if (elapsed == INTRO_TICKS) {
                ui(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 0.85F, 1.0F);
            }
            if (elapsed == INTRO_TICKS + 20) {
                ui(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.05F, 1.0F);
            }
            if (elapsed == INTRO_TICKS + 40) {
                ui(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.4F, 1.0F);
            }
        }

        public void handle(MatchCountdownPayload payload) {
            Minecraft client = Minecraft.getInstance();
            if (payload.getRemainingTicks() < 0) {
                clear();
                return;
            }

            if (!payload.getArenaName().isEmpty()) {
                mapName = payload.getArenaName();
            }
            if (!payload.getGameMode().isEmpty()) {
                gameModeName = payload.getGameMode();
            }

            if (payload.isGo()) {
                // Start: zurück in die Ich-Perspektive, dazu ein druckvoller Kinetik- & Sichtfeldstoß.
                client.options.setCameraType(CameraType.FIRST_PERSON);
                fovBoost = 0.65F;
                portalIntensity = 0.85F;
                confusionIntensity = 0.50F;
                remainingTicks = -1;
                goTicks = GO_TICKS;
                CameraShakeState.INSTANCE.triggerDirect(0.55F, 18);
                MinimapState.INSTANCE.setMatchRunning(true);
                MinimapState.INSTANCE.markDirty();
                ui(net.minecraft.sounds.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.0F);
                ui(net.minecraft.sounds.SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 1.0F, 0.9F);
            } else {
                // Countdown: Kamera von vorn, damit man sich selbst im Startfeld stehen sieht.
                client.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                fovBoost = 0.0F;
                remainingTicks = payload.getRemainingTicks();
                goTicks = 0;
                MinimapState.INSTANCE.setMatchRunning(false);
            }
        }

        public void tick() {
            if (remainingTicks > 0) {
                remainingTicks--;
                playSequenceSounds(COUNTDOWN_TICKS - remainingTicks);
                if (remainingTicks == 0 && goTicks <= 0) {
                    Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
                }
            }
            if (goTicks > 0) {
                goTicks--;
                if (goTicks == 0) {
                    Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
                }
            }
        }

        /**
         * Beim Verlassen des Servers muss die Kamera zurück, sonst bleibt sie in der Aussenansicht.
         */
        public void clear() {
            remainingTicks = -1;
            goTicks = 0;
            fovBoost = 0.0F;
            portalIntensity = 0.0F;
            confusionIntensity = 0.0F;
            mapName = "Standard";
            gameModeName = "CLASSIC";
            Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
            MinimapState.INSTANCE.setMatchRunning(false);
        }
    }

    // =========================================================================
    // MinigunHudState.java
    // =========================================================================

    /**
     * Hält die vom Server gemeldeten Minigun-Zustände für das HUD und zählt sie herunter.
     * <p>
     * Die Drehzahl selbst steht nicht mehr hier: sie gilt für jeden sichtbaren Spieler und liegt
     * darum in {@link MinigunSpinState}. Damit zeigt das HUD genau die Drehzahl an, mit der sich
     * auch das Modell in der Hand dreht.
     */
    public static final class MinigunHudState {
        public static final MinigunHudState INSTANCE = new MinigunHudState();

        private static final int KILL_EFFECT_DURATION_TICKS = 30;
        private static final int HIT_EFFECT_DURATION_TICKS = 6;
        /**
         * Standzeit der Trefferanzeige.
         * <p>
         * Der Servertreffer selbst leuchtet nur sechs Ticks – zu kurz, um mitzuzählen. Die Anzeige
         * bleibt deshalb länger stehen und verfällt in derselben Zeitspanne wie die Trefferserie auf
         * dem Server, damit sie keinen Vorsprung verspricht, den es nicht mehr gibt.
         */
        private static final int TALLY_DURATION_TICKS = MinigunRuntime.HIT_COMBO_TIMEOUT_TICKS;
        private static int remainingUseTicks;
        private static int expiringTicks;
        private static int killEffectTicks;
        private static int hitEffectTicks;
        /**
         * Wie oft das zuletzt beschossene Ziel schon getroffen wurde.
         */
        private static int hitsOnTarget;
        private static int tallyTicks;

        private MinigunHudState() {
        }

        public int getRemainingUseTicks() {
            return remainingUseTicks;
        }

        public int getExpiringTicks() {
            return expiringTicks;
        }

        public int getHitsOnTarget() {
            return hitsOnTarget;
        }

        public int getTallyTicks() {
            return tallyTicks;
        }

        public int getKillEffectTicks() {
            return killEffectTicks;
        }

        public int getHitEffectTicks() {
            return hitEffectTicks;
        }

        public boolean isExpiring() {
            return expiringTicks > 0;
        }

        /**
         * Aktuelle Drehzahl von 0 (steht) bis 1 (volle Feuerrate).
         */
        public float getSpin() {
            LocalPlayer player = Minecraft.getInstance().player;
            return player == null ? 0.0F : MinigunSpinState.INSTANCE.getSpin(player);
        }

        /**
         * Drehwinkel der Laufgruppe, zwischen den Ticks interpoliert.
         */
        public float getSpinPhase(float partialTick) {
            LocalPlayer player = Minecraft.getInstance().player;
            return player == null ? 0.0F : MinigunSpinState.INSTANCE.getPhase(player, partialTick);
        }

        public void handle(MinigunHudPayload payload) {
            switch (payload.getEvent()) {
                case MinigunHudPayload.STARTED -> {
                    remainingUseTicks = MinigunRuntime.USE_DURATION_TICKS;
                    expiringTicks = 0;
                    hitsOnTarget = 0;
                }
                case MinigunHudPayload.EXPIRING -> {
                    remainingUseTicks = 0;
                    expiringTicks = MinigunRuntime.HISS_DURATION_TICKS;
                }
                case MinigunHudPayload.KILL_CONFIRMED -> {
                    hitEffectTicks = HIT_EFFECT_DURATION_TICKS;
                    killEffectTicks = KILL_EFFECT_DURATION_TICKS;
                    hitsOnTarget = 0;
                    tallyTicks = 0;
                }
                case MinigunHudPayload.HIT_CONFIRMED -> {
                    hitEffectTicks = HIT_EFFECT_DURATION_TICKS;
                    hitsOnTarget = payload.getValue();
                    tallyTicks = TALLY_DURATION_TICKS;
                }
                default -> {
                }
            }
        }

        public void tick() {
            if (tallyTicks > 0 && --tallyTicks == 0) {
                hitsOnTarget = 0;
            }
            if (remainingUseTicks > 0) {
                remainingUseTicks--;
                if (remainingUseTicks == 0 && expiringTicks == 0) {
                    expiringTicks = MinigunRuntime.HISS_DURATION_TICKS;
                }
            }

            if (expiringTicks > 0) {
                expiringTicks--;
            }
            if (killEffectTicks > 0) {
                killEffectTicks--;
            }
            if (hitEffectTicks > 0) {
                hitEffectTicks--;
            }
        }

        public void clear() {
            remainingUseTicks = 0;
            expiringTicks = 0;
            killEffectTicks = 0;
            hitEffectTicks = 0;
        }
    }

    // =========================================================================
    // MinigunSpinState.java
    // =========================================================================

    /**
     * Drehzahl und Drehwinkel der Laufgruppe – für jeden sichtbaren Spieler, nicht nur den eigenen.
     * <p>
     * Das Modell wird für jedes Bild neu aufgebaut und könnte den Winkel nicht selbst mitzählen;
     * hier läuft er im Takt der Ticks mit und wird beim Zeichnen dazwischen interpoliert.
     * <p>
     * Beim Loslassen fällt die Drehzahl weich ab, statt abzuschneiden: die Läufe trudeln aus wie
     * der Klang, der dasselbe tut. Solange gefeuert wird, gilt die gemeinsame Kurve aus
     * {@link com.oneshotonekill.item.runtime.MinigunRuntime.Spin}, damit sich das Bündel genauso schnell dreht, wie der Server rechnet.
     */
    public static final class MinigunSpinState {
        public static final MinigunSpinState INSTANCE = new MinigunSpinState();

        /**
         * Drehzahlverlust je Tick nach dem Loslassen.
         */
        private static final float SPIN_DECAY = 0.035F;
        private static final float TWO_PI = (float) (Math.PI * 2.0);

        private static final Map<Integer, Rotor> rotors = new HashMap<>();

        private MinigunSpinState() {
        }

        private static void advance(Rotor rotor, Player player) {
            boolean firing = player.isUsingItem() && player.getUseItem().is(ModItems.MINIGUN);
            rotor.spin = firing
                    ? MinigunRuntime.Spin.speed(player.getTicksUsingItem())
                    : Math.max(0.0F, rotor.spin - SPIN_DECAY);

            rotor.previousPhase = rotor.phase;
            rotor.phase += rotor.spin * MinigunRuntime.Spin.MAX_RADIANS_PER_TICK;
            if (rotor.phase > TWO_PI) {
                // Beide Winkel zusammen zurücksetzen, damit die Interpolation den Sprung nicht mitmacht.
                rotor.phase -= TWO_PI;
                rotor.previousPhase -= TWO_PI;
            }
        }

        /**
         * Drehzahl von 0 bis 1; das HUD zeigt sie an.
         */
        public float getSpin(LivingEntity holder) {
            Rotor rotor = rotors.get(holder.getId());
            return rotor == null ? 0.0F : rotor.spin;
        }

        /**
         * Drehwinkel im Bogenmaß, zwischen zwei Ticks interpoliert.
         */
        public float getPhase(LivingEntity holder, float partialTick) {
            Rotor rotor = rotors.get(holder.getId());
            return rotor == null ? 0.0F : rotor.previousPhase + (rotor.phase - rotor.previousPhase) * partialTick;
        }

        public void tick(Minecraft client) {
            if (client.level == null) {
                rotors.clear();
                return;
            }

            for (Player player : client.level.players()) {
                advance(rotors.computeIfAbsent(player.getId(), id -> new Rotor()), player);
            }
            // Wer nicht mehr in Sicht ist, braucht auch keinen Rotor mehr.
            rotors.keySet().removeIf(id -> client.level.getEntity(id) == null);
        }

        public void clear() {
            rotors.clear();
        }

        private static final class Rotor {
            private float spin;
            private float phase;
            private float previousPhase;
        }
    }

    // =========================================================================
    // NukeState.java
    // =========================================================================

    /**
     * Clientkopie der Nuke-Sequenz.
     * <p>
     * <p>Der Server schickt seinen Tickzähler nur einmal je Sekunde; dazwischen zählt diese Klasse
     * selbst weiter. Das ist der einzige Weg zu einem Countdown, der flüssig läuft – zwanzig Pakete
     * je Sekunde für eine Zahl zu verschicken, die man auch addieren kann, wäre Verschwendung, und
     * ein Countdown, der nur im Sekundentakt aktualisiert, kann weder blinken noch weich ausblenden.
     * Der nächste Abgleich vom Server holt einen abgedrifteten Zähler jedes Mal wieder ein.</p>
     * <p>
     * <p>Der Zwischenbildanteil wird mitgeführt, weil Blitz und Ausblenden schneller ablaufen als
     * ein Tick: Ohne ihn zuckte das weiße Bild in zwanzig Stufen, statt zu verlaufen.</p>
     */
    public static final class NukeState {
        public static final NukeState INSTANCE = new NukeState();

        private int tick = -1;
        private double x;
        private double y;
        private double z;
        private @Nullable NukeVictoryPayload victory;

        private NukeState() {
        }

        public void handle(NukeStatePayload payload) {
            if (payload.tick() < 0) {
                clear();
                return;
            }
            this.tick = payload.tick();
            this.x = payload.x();
            this.y = payload.y();
            this.z = payload.z();
        }

        public void handle(NukeVictoryPayload payload) {
            this.victory = payload;
        }

        /**
         * Wird im Client-Takt aufgerufen und schiebt den Zähler zwischen zwei Abgleichen weiter.
         * <p>
         * Am Ende der Sequenz bleibt er auf {@link NukePhase#TOTAL_TICKS} stehen. Das ist kein
         * Überlauf, sondern der Zustand „Sequenz vorbei, Fallout steht noch": Nebel, Bildschirmfilm
         * und Abschlusstafel hängen daran und bleiben, bis der Server das Match wirklich stoppt und
         * einen leeren Zustand schickt.
         */
        public void tick() {
            if (tick >= 0 && tick < NukePhase.TOTAL_TICKS) {
                tick++;
            }
        }

        public void clear() {
            tick = -1;
            victory = null;
        }

        public boolean isRunning() {
            return tick >= 0;
        }

        public int currentTick() {
            return tick;
        }

        public @Nullable NukePhase phase() {
            return NukePhase.at(tick);
        }

        /**
         * Der Fortschritt innerhalb des laufenden Abschnitts, samt Zwischenbild.
         */
        public float phaseShare(float partialTick) {
            NukePhase phase = phase();
            if (phase == null) {
                return 0.0F;
            }
            int span = phase.to() - phase.from();
            if (span <= 1) {
                return 0.0F;
            }
            return Math.clamp((tick - phase.from() + partialTick) / (float) span, 0.0F, 1.0F);
        }

        public int secondsToImpact() {
            return NukePhase.secondsToImpact(tick);
        }

        /**
         * Ob der Einschlag schon war – daran hängen Blitz, Nebel und Ton des Nachlaufs.
         */
        public boolean hasDetonated() {
            return tick >= NukePhase.DETONATION.from();
        }

        /**
         * Ob die Sequenz durch ist und nur noch der Fallout steht.
         * <p>
         * Ab hier läuft kein Abschnitt mehr, aber der Zustand bleibt: Der Server schickt erst beim
         * Stoppen des Matches den leeren Zustand nach.
         */
        public boolean isFalloutOnly() {
            return tick >= NukePhase.TOTAL_TICKS;
        }

        /**
         * Die Abschlusstafel steht ab ihrem Abschnitt und bleibt danach stehen.
         */
        public boolean showsVictoryBoard() {
            return tick >= NukePhase.VICTORY.from();
        }

        /**
         * Der Nachhall/Wind-Sound läuft nach dem Einschlag durchgehend weiter, bis das Match per GUI gestoppt wird.
         */
        public boolean wantsAftermathDrone() {
            return hasDetonated();
        }
/**
         * Sekunden seit Sequenzbeginn, samt Zwischenbild.
         */
        public float seconds(float partialTick) {
            return (Math.max(tick, 0) + partialTick) / 20.0F;
        }

        /**
         * Sekunden seit dem Einschlag; vor dem Einschlag null.
         */
        public float secondsSinceBlast(float partialTick) {
            return hasDetonated() ? (tick - NukePhase.DETONATION.from() + partialTick) / 20.0F : 0.0F;
        }

        /**
         * Restzeit bis zum Einschlag mit Nachkommastellen; nach dem Einschlag null.
         */
        public float secondsToImpactExact(float partialTick) {
            return Math.max(0.0F, (NukePhase.DETONATION.from() - tick - partialTick) / 20.0F);
        }

        /**
         * Anteil des Countdowns von 0 bis 1.
         */
        public float countdownProgress(float partialTick) {
            return Math.clamp(seconds(partialTick) / (NukePhase.DETONATION.from() / 20.0F), 0.0F, 1.0F);
        }

        /**
         * Schiefe des Horizonts: Vor dem Einschlag wächst ein nervöses Pendeln samt Herzschlag-Kick,
         * danach schlägt die Druckwelle die Kamera kurz aus der Waage.
         */
        public float cameraRoll(float partialTick) {
            if (!isRunning()) {
                return 0.0F;
            }
            if (hasDetonated()) {
                float since = secondsSinceBlast(partialTick);
                return (float) (Math.sin(since * 11.0F) * Math.exp(-since * 1.6F) * 7.0);
            }
            float t = seconds(partialTick);
            float intensity = countdownProgress(partialTick);
            intensity *= intensity;
            float beat = com.oneshotonekill.client.effect.HeartbeatClock.punch(t);
            float side = (com.oneshotonekill.client.effect.HeartbeatClock.beatsSoFar(t) & 1) == 0 ? 1.0F : -1.0F;
            return (float) Math.sin(t * 0.9F) * 1.6F * intensity + beat * side * 1.2F * intensity;
        }

        /**
         * Sichtfeld-Versatz: Vor dem Einschlag zieht sich der Tunnelblick zu, im Rhythmus des Herzschlags
         * pumpt er leicht. Der Einschlag selbst reißt das Bild kurz auf.
         */
        public float fovOffset(float partialTick) {
            if (!isRunning()) {
                return 0.0F;
            }
            if (hasDetonated()) {
                return (float) (0.45 * Math.exp(-secondsSinceBlast(partialTick) * 3.2F));
            }
            float p = countdownProgress(partialTick);
            float beat = com.oneshotonekill.client.effect.HeartbeatClock.punch(seconds(partialTick));
            return -0.11F * p * p + 0.035F * beat * p;
        }


        public double centreX() {
            return x;
        }

        public double centreY() {
            return y;
        }

        public double centreZ() {
            return z;
        }

        public @Nullable NukeVictoryPayload victory() {
            return victory;
        }
    }

    // =========================================================================
    // BomberCameraState.java
    // =========================================================================

    /**
     * Hält die Live-Kameradaten des Tarnkappenbombers für das PiP-Aufklärungs-HUD auf dem Client.
     */
    public static final class BomberCameraState {
        public static final BomberCameraState INSTANCE = new BomberCameraState();
        private final List<DetonationFX> activeDetonations = new ArrayList<>();
        private boolean active;
        private UUID targetId;
        private String targetName = "";
        private double bomberX, bomberY, bomberZ;
        private double prevBomberX, prevBomberY, prevBomberZ;
        private double targetX, targetY, targetZ;
        private float heading;
        private float prevHeading;
        private int remainingTicks;
        private int totalTicks;
        private int bombDropFlashTicks;
        private int impactGlitchTicks;
        private int eliminatedTicks;
        private float transitionProgress;
        private float previousTransitionProgress;

        private BomberCameraState() {
        }

        public boolean isActive() {
            return active || transitionProgress > 0.001F;
        }

        public boolean isFeedOnline() {
            return active;
        }

        public boolean isTargetEliminated() {
            return eliminatedTicks > 0;
        }

        public UUID getTargetId() {
            return targetId;
        }

        public String getTargetName() {
            return targetName != null ? targetName : "";
        }

        public double getBomberX() {
            return bomberX;
        }

        public double getBomberY() {
            return bomberY;
        }

        public double getBomberZ() {
            return bomberZ;
        }

        public double getInterpolatedBomberX(float pt) {
            return prevBomberX + (bomberX - prevBomberX) * pt;
        }

        public double getInterpolatedBomberY(float pt) {
            return prevBomberY + (bomberY - prevBomberY) * pt;
        }

        public double getInterpolatedBomberZ(float pt) {
            return prevBomberZ + (bomberZ - prevBomberZ) * pt;
        }

        public float getInterpolatedHeadingDegrees(float pt) {
            return Mth.rotLerp(pt, (float) Math.toDegrees(prevHeading), (float) Math.toDegrees(heading));
        }

        public double getTargetX() {
            return targetX;
        }

        public double getTargetY() {
            return targetY;
        }

        public double getTargetZ() {
            return targetZ;
        }

        public float getHeading() {
            return heading;
        }

        public int getRemainingTicks() {
            return remainingTicks;
        }

        public int getTotalTicks() {
            return totalTicks;
        }

        public int getBombDropFlashTicks() {
            return bombDropFlashTicks;
        }

        public int getImpactGlitchTicks() {
            return impactGlitchTicks;
        }

        public float getTransition(float partialTick) {
            return Mth.clamp(previousTransitionProgress + (transitionProgress - previousTransitionProgress) * partialTick, 0.0F, 1.0F);
        }

        public double distanceToTarget() {
            return Math.sqrt(Math.pow(bomberX - targetX, 2) + Math.pow(bomberZ - targetZ, 2));
        }

        public double altitude() {
            return Math.max(0.0, bomberY - targetY);
        }

        public List<DetonationFX> getActiveDetonations() {
            return activeDetonations;
        }

        public void addDetonation(double x, double y, double z) {
            activeDetonations.add(new DetonationFX(x, y, z));
        }

        public void handle(BomberCameraPayload payload) {
            if (!payload.active()) {
                this.active = false;
                return;
            }
            if (!this.active || (this.bomberX == 0.0 && this.bomberY == 0.0 && this.bomberZ == 0.0)) {
                this.prevBomberX = payload.bomberX();
                this.prevBomberY = payload.bomberY();
                this.prevBomberZ = payload.bomberZ();
                this.prevHeading = payload.heading();
            }
            this.active = true;
            this.targetId = payload.targetId();
            this.targetName = payload.targetName();
            this.bomberX = payload.bomberX();
            this.bomberY = payload.bomberY();
            this.bomberZ = payload.bomberZ();
            this.targetX = payload.targetX();
            this.targetY = payload.targetY();
            this.targetZ = payload.targetZ();
            this.heading = payload.heading();
            this.remainingTicks = payload.remainingTicks();
            this.totalTicks = payload.totalTicks();
            if (payload.bombDropped()) {
                this.bombDropFlashTicks = 8;
            }
            if (payload.impactGlitch()) {
                this.impactGlitchTicks = 14;
                double bx = payload.blastX() != 0.0 ? payload.blastX() : payload.targetX();
                double by = payload.blastY() != 0.0 ? payload.blastY() : payload.targetY();
                double bz = payload.blastZ() != 0.0 ? payload.blastZ() : payload.targetZ();
                addDetonation(bx, by, bz);
            }
            if (payload.targetEliminated() && this.eliminatedTicks <= 0) {
                this.eliminatedTicks = 35;
            }
        }

        public void tick() {
            previousTransitionProgress = transitionProgress;
            prevBomberX = bomberX;
            prevBomberY = bomberY;
            prevBomberZ = bomberZ;
            prevHeading = heading;
            if (active) {
                if (transitionProgress < 1.0F) {
                    transitionProgress = Math.min(1.0F, transitionProgress + 0.15F);
                }
            } else {
                if (transitionProgress > 0.0F) {
                    transitionProgress = Math.max(0.0F, transitionProgress - 0.18F);
                    if (transitionProgress == 0.0F) {
                        clearData();
                    }
                }
            }

            if (bombDropFlashTicks > 0) {
                bombDropFlashTicks--;
            }
            if (impactGlitchTicks > 0) {
                impactGlitchTicks--;
            }
            if (eliminatedTicks > 0) {
                eliminatedTicks--;
            }

            activeDetonations.removeIf(det -> {
                det.ageTicks++;
                return det.ageTicks >= det.maxAgeTicks;
            });
        }

        private void clearData() {
            targetId = null;
            targetName = "";
            bomberX = bomberY = bomberZ = 0.0;
            prevBomberX = prevBomberY = prevBomberZ = 0.0;
            heading = 0.0F;
            prevHeading = 0.0F;
            remainingTicks = 0;
            totalTicks = 0;
            bombDropFlashTicks = 0;
            impactGlitchTicks = 0;
            eliminatedTicks = 0;
            activeDetonations.clear();
        }

        public void clear() {
            active = false;
            transitionProgress = 0.0F;
            previousTransitionProgress = 0.0F;
            clearData();
        }

        public static final class DetonationFX {
            public final double x, y, z;
            public final int maxAgeTicks;
            public int ageTicks;

            public DetonationFX(double x, double y, double z) {
                this.x = x;
                this.y = y;
                this.z = z;
                this.ageTicks = 0;
                this.maxAgeTicks = 22;
            }
        }
    }

    // =========================================================================
    // MatchBannerState.java
    // =========================================================================

    /**
     * Verwaltet das aktive Cyber-Status-Banner für Match-Zustandswechsel (Pause, Resume, Stop, Map-Reset)
     * sowie die persistente HUD-Pausenleiste.
     */
    public static final class MatchBannerState {
        public static final MatchBannerState INSTANCE = new MatchBannerState();

        private String eventType = "";
        private String title = "";
        private String subtitle = "";
        private int totalTicks = 0;
        private int remainingTicks = 0;
        private int accentColor = 0;
        private boolean matchPaused = false;

        private Component titleComponent = Component.empty();
        private Component subtitleComponent = Component.empty();

        private MatchBannerState() {
        }

        public boolean isBannerActive() {
            return remainingTicks > 0;
        }

        public boolean isMatchPaused() {
            return matchPaused;
        }

        public String getEventType() {
            return eventType;
        }

        public String getTitle() {
            return title;
        }

        public String getSubtitle() {
            return subtitle;
        }

        public Component getTitleComponent() {
            return titleComponent;
        }

        public Component getSubtitleComponent() {
            return subtitleComponent;
        }

        public int getAccentColor() {
            return accentColor;
        }

        public float getProgress(float partialTick) {
            if (remainingTicks <= 0 || totalTicks <= 0) return 0.0F;
            return Math.max(0.0F, (remainingTicks - partialTick) / (float) totalTicks);
        }

        public void handle(MatchNotificationPayload payload) {
            this.eventType = payload.getEvent();
            this.totalTicks = Math.max(1, payload.getDurationTicks());
            this.remainingTicks = this.totalTicks;
            this.accentColor = payload.getAccentColor();

            if ("RESUME".equalsIgnoreCase(payload.getEvent())) {
                this.titleComponent = Component.translatable("notification.oneshotonekill.resume.title");
                this.subtitleComponent = Component.translatable("notification.oneshotonekill.resume.subtitle");
                this.matchPaused = false;
                MinimapState.INSTANCE.setMatchRunning(true);
            } else if ("PAUSE".equalsIgnoreCase(payload.getEvent())) {
                this.titleComponent = Component.translatable("notification.oneshotonekill.pause.title");
                this.subtitleComponent = Component.translatable("notification.oneshotonekill.pause.subtitle");
                this.matchPaused = true;
                MinimapState.INSTANCE.setMatchRunning(false);
            } else if ("STOP".equalsIgnoreCase(payload.getEvent())) {
                this.titleComponent = Component.translatable("notification.oneshotonekill.stop.title");
                this.subtitleComponent = Component.translatable("notification.oneshotonekill.stop.subtitle");
                this.matchPaused = false;
                MinimapState.INSTANCE.setMatchRunning(false);
            } else if ("ARENA_SWITCH".equalsIgnoreCase(payload.getEvent())) {
                this.titleComponent = Component.translatable("notification.oneshotonekill.arena_switch.title");
                this.subtitleComponent = Component.translatable("notification.oneshotonekill.arena_switch.subtitle", payload.getSubtitle());
            } else if ("ARENA_RESET".equalsIgnoreCase(payload.getEvent())) {
                this.titleComponent = Component.translatable("notification.oneshotonekill.arena_reset.title");
                this.subtitleComponent = Component.translatable("notification.oneshotonekill.arena_reset.subtitle", payload.getSubtitle());
            } else if ("FROST_TRAP".equalsIgnoreCase(payload.getEvent())) {
                this.titleComponent = Component.translatable("notification.oneshotonekill.frost_trap.title");
                this.subtitleComponent = Component.translatable("notification.oneshotonekill.frost_trap.subtitle", payload.getSubtitle());
            } else {
                this.titleComponent = Component.literal(payload.getTitle());
                this.subtitleComponent = Component.literal(payload.getSubtitle());
            }
            this.title = this.titleComponent.getString();
            this.subtitle = this.subtitleComponent.getString();
        }

        public void tick() {
            if (remainingTicks > 0) {
                remainingTicks--;
            }
        }

        public void clear() {
            remainingTicks = 0;
            totalTicks = 0;
            matchPaused = false;
            eventType = "";
            title = "";
            subtitle = "";
        }
    }

    // =========================================================================
    // GunGameHudState.java
    // =========================================================================

    public static final class GunGameHudState {
        public static final GunGameHudState INSTANCE = new GunGameHudState();
        private static final int LEVEL_UP_DURATION = 50;
        private int currentTier = 1;
        private int totalTiers = 1;
        private int tierKills = 0;
        private int requiredKills = 1;
        private boolean conditionMet = true;
        private int rank = 1;
        private int playerCount = 1;
        private int leaderTier = 1;
        private boolean active = false;
        private int levelUpEffectTicks = 0;

        private GunGameHudState() {
        }

        public boolean isActive() {
            return active;
        }

        public int getCurrentTier() {
            return currentTier;
        }

        public int getTotalTiers() {
            return totalTiers;
        }

        public int getTierKills() {
            return tierKills;
        }

        public int getRequiredKills() {
            return requiredKills;
        }

        /**
         * Ob das Fenster der aktuellen Stufe gerade offen ist, der nächste Kill also zählen würde.
         */
        public boolean isConditionMet() {
            return conditionMet;
        }

        public int getRank() {
            return rank;
        }

        public int getPlayerCount() {
            return playerCount;
        }

        public int getLeaderTier() {
            return leaderTier;
        }

        public int getLevelUpEffectTicks() {
            return levelUpEffectTicks;
        }

        public float getLevelUpProgress(float partialTick) {
            if (levelUpEffectTicks <= 0) return 0.0F;
            return Math.max(0.0F, (levelUpEffectTicks - partialTick) / (float) LEVEL_UP_DURATION);
        }

        public void handle(GunGameStatusPayload payload) {
            if (!payload.active()) {
                clear();
                return;
            }
            this.active = true;
            this.currentTier = payload.currentTier();
            this.totalTiers = payload.totalTiers();
            this.tierKills = payload.tierKills();
            this.requiredKills = payload.requiredKills();
            this.conditionMet = payload.conditionMet();
            this.rank = payload.rank();
            this.playerCount = payload.playerCount();
            this.leaderTier = payload.leaderTier();
            if (payload.isLevelUp()) {
                this.levelUpEffectTicks = LEVEL_UP_DURATION;
            }
        }

        public void tick() {
            if (levelUpEffectTicks > 0) {
                levelUpEffectTicks--;
            }
        }

        public void clear() {
            active = false;
            currentTier = 1;
            totalTiers = 1;
            tierKills = 0;
            requiredKills = 1;
            conditionMet = true;
            rank = 1;
            playerCount = 1;
            leaderTier = 1;
            levelUpEffectTicks = 0;
        }
    }

    // =========================================================================
    // MinimapState.java (Tilted Towers 2D Radar)
    // =========================================================================

    public static final class MinimapState {
        public static final MinimapState INSTANCE = new MinimapState();
        public static final Identifier RADAR_VIEW_ID = Identifier.fromNamespaceAndPath("oneshotonekill", "dynamic/tilted_radar_view");

        public static final int MAP_WIDTH = 170;
        public static final int MAP_HEIGHT = 160;
        public static final double MIN_X = -135.0;
        public static final double MAX_X = 35.0;
        public static final double MIN_Z = 165.0;
        public static final double MAX_Z = 325.0;

        public static final int RADAR_RADIUS = 56;
        public static final int RADAR_TEX_RADIUS = 112;
        public static final int RADAR_TEX_SIZE = RADAR_TEX_RADIUS * 2;

        private final int[] arenaColors = new int[MAP_WIDTH * MAP_HEIGHT];
        private final short[] arenaHeights = new short[MAP_WIDTH * MAP_HEIGHT];
        private final Map<UUID, EnemyContact> contacts = new HashMap<>();
        private DynamicTexture radarViewTexture;
        private NativeImage radarImage;
        private boolean textureUploaded = false;
        private boolean radarViewReady = false;
        private boolean radarViewDirty = true;
        private boolean matchRunning = false;
        private double lastPx = Double.NaN;
        private double lastPz = Double.NaN;
        private float lastYaw = Float.NaN;
        private int scanColumn = 0;
        private int scanPasses = 0;
        private boolean scanComplete = false;
        private String currentDimension = "";

        private MinimapState() {
        }

        public boolean isMatchRunning() {
            return matchRunning;
        }

        public void setMatchRunning(boolean matchRunning) {
            this.matchRunning = matchRunning;
        }

        /**
         * Echtes Vollfarben-Rendering mit dynamischem 3D-Relief-Schattierungseffekt (Hillshading).
         * Sonne steht im Nordwesten; Wolkenkratzer und Kanten werfen plastische Schatten.
         */
        private static int shadeRelief(int rawRgb, int y, int hNorth, int hWest) {
            if (rawRgb == 0) {
                return 0xFF141920; // Solider, deckender Asphalt-/Schattengrund
            }
            int r = (rawRgb >> 16) & 0xFF;
            int g = (rawRgb >> 8) & 0xFF;
            int b = rawRgb & 0xFF;

            // 1. Höhenschattierung: Tiefere Straßen und Unterführungen dunkler, hohe Türme heller
            double altFactor = 0.80 + 0.28 * Math.clamp((y - 4) / 42.0, 0.0, 1.0);

            // 2. Relief-Schattenwurf (Licht von Nord-Westen)
            int diffN = y - hNorth;
            int diffW = y - hWest;
            double slope = (diffN + diffW) * 0.08;
            double sunFactor = 1.0 + Math.clamp(slope, -0.32, 0.32);

            double total = altFactor * sunFactor;

            int tr = (int) Math.clamp(r * total, 0, 255);
            int tg = (int) Math.clamp(g * total, 0, 255);
            int tb = (int) Math.clamp(b * total, 0, 255);

            // 0xFF = 255 Alpha (100% solide Deckkraft - kein Durchscheinen der Spielwelt)
            return 0xFF000000 | (tr << 16) | (tg << 8) | tb;
        }

        public boolean isRadarViewReady() {
            return radarViewReady && radarViewTexture != null;
        }

        public Map<UUID, EnemyContact> getContacts() {
            return contacts;
        }

        public void onPlayerShot(UUID player) {
            EnemyContact current = contacts.get(player);
            if (current != null) {
                contacts.put(player, new EnemyContact(current.pos(), current.dy(), true, current.expiryTick() + 40));
            }
        }

        public void updateRadarView(double px, double pz, float yaw) {
            Minecraft client = Minecraft.getInstance();
            if (radarViewTexture == null) {
                radarViewTexture = new DynamicTexture("Tilted Radar View", RADAR_TEX_SIZE, RADAR_TEX_SIZE, true);
                radarImage = radarViewTexture.getPixels();
                client.getTextureManager().register(RADAR_VIEW_ID, radarViewTexture);
                radarViewDirty = true;
            }

            double dPx = px - lastPx;
            double dPz = pz - lastPz;
            float dYaw = Math.abs(yaw - lastYaw);
            if (!radarViewDirty && Math.abs(dPx) < 0.03 && Math.abs(dPz) < 0.03 && dYaw < 0.12f) {
                return;
            }

            lastPx = px;
            lastPz = pz;
            lastYaw = yaw;
            radarViewDirty = false;

            double yawRad = Math.toRadians(yaw);
            double cos = Math.cos(yawRad);
            double sin = Math.sin(yawRad);
            int rSq = RADAR_TEX_RADIUS * RADAR_TEX_RADIUS;
            int innerSq = (RADAR_TEX_RADIUS - 3) * (RADAR_TEX_RADIUS - 3);

            for (int dy = -RADAR_TEX_RADIUS; dy < RADAR_TEX_RADIUS; dy++) {
                int py = dy + RADAR_TEX_RADIUS;
                for (int dx = -RADAR_TEX_RADIUS; dx < RADAR_TEX_RADIUS; dx++) {
                    int pxOffset = dx + RADAR_TEX_RADIUS;
                    int d2 = dx * dx + dy * dy;
                    if (d2 > rSq) {
                        radarImage.setPixel(pxOffset, py, 0x00000000);
                        continue;
                    }

                    // 2x Supersampling: 1 GUI-Pixel = 2 Textur-Pixel
                    double screenDx = dx * 0.5;
                    double screenDy = dy * 0.5;

                    // World-Koordinaten aus lokalem Bildschirm-Offset
                    double deltaX = -screenDx * cos + screenDy * sin;
                    double deltaZ = -screenDx * sin - screenDy * cos;
                    int worldX = (int) Math.floor(px + deltaX);
                    int worldZ = (int) Math.floor(pz + deltaZ);

                    int mapX = (int) Math.floor(worldX - MIN_X);
                    int mapZ = (int) Math.floor(worldZ - MIN_Z);

                    int color = 0xFF141920;
                    if (mapX >= 0 && mapX < MAP_WIDTH && mapZ >= 0 && mapZ < MAP_HEIGHT) {
                        int idx = mapZ * MAP_WIDTH + mapX;
                        int rawRgb = arenaColors[idx];
                        int h = arenaHeights[idx];
                        int hNorth = (mapZ > 0) ? arenaHeights[(mapZ - 1) * MAP_WIDTH + mapX] : h;
                        int hWest = (mapX > 0) ? arenaHeights[mapZ * MAP_WIDTH + (mapX - 1)] : h;
                        color = shadeRelief(rawRgb, h, hNorth, hWest);
                    }

                    // Weiches 3-Pixel Antialiasing am kreisförmigen Rand
                    if (d2 > innerSq) {
                        float edgeFade = 1.0f - (float) (Math.sqrt(d2) - (RADAR_TEX_RADIUS - 3)) / 3.0f;
                        edgeFade = Math.clamp(edgeFade, 0.0f, 1.0f);
                        int alpha = (int) (255 * edgeFade);
                        color = (alpha << 24) | (color & 0x00FFFFFF);
                    }

                    radarImage.setPixel(pxOffset, py, color);
                }
            }

            radarViewTexture.upload();
            radarViewReady = true;
        }

        public void tick(Minecraft client) {
            if (client.level == null || client.player == null) {
                clear();
                return;
            }

            boolean isTilted = Arena.TILTED_TOWERS.getDimension().equals(client.level.dimension());
            if (!isTilted) {
                if (!currentDimension.isEmpty()) {
                    clear();
                }
                return;
            }

            String dim = Arena.TILTED_TOWERS.getId();
            if (!dim.equals(currentDimension)) {
                clear();
                currentDimension = dim;
            }

            // Inkrementeller Geländescan (schonend über mehrere Ticks verteilt)
            if (!scanComplete) {
                int columnsPerTick = 18;
                int end = Math.min(MAP_WIDTH, scanColumn + columnsPerTick);
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                ClientLevel level = client.level;
                boolean anyChunkMissing = false;

                for (int cx = scanColumn; cx < end; cx++) {
                    int worldX = (int) Math.floor(MIN_X + cx);
                    for (int cz = 0; cz < MAP_HEIGHT; cz++) {
                        int worldZ = (int) Math.floor(MIN_Z + cz);
                        int rawRgb = 0;
                        short height = 0;
                        if (level.hasChunk(worldX >> 4, worldZ >> 4)) {
                            int upper = 85;
                            int lower = 0;
                            for (int y = upper; y >= lower; y--) {
                                pos.set(worldX, y, worldZ);
                                BlockState state = level.getBlockState(pos);

                                // 1. Unsichtbare Hilfs- und Gameplay-Blöcke ignorieren (durchschauen auf echte Dächer)
                                if (state.isAir() || state.is(Blocks.BARRIER) || state.is(Blocks.LIGHT)
                                        || state.is(Blocks.STRUCTURE_VOID) || state.is(Blocks.TRIPWIRE)
                                        || state.is(Blocks.TRIPWIRE_HOOK)) {
                                    continue;
                                }

                                // 2. Glas & Dachfenster: Echte architektonische Glasfarbe statt unsichtbarem Durchscheinen
                                if (state.is(Blocks.GLASS) || state.is(Blocks.GLASS_PANE) || state.is(Blocks.TINTED_GLASS)) {
                                    height = (short) y;
                                    rawRgb = 0x688599; // Modernes, dezentes Stahlblau-Glas
                                    break;
                                }

                                // 3. Gitter / Geländer auf Dächern
                                if (state.is(Blocks.IRON_BARS)) {
                                    height = (short) y;
                                    rawRgb = 0x484E54; // Dunkles Konstruktions-Metall
                                    break;
                                }

                                // 4. Reguläre Farbblöcke
                                MapColor mapColor = state.getMapColor(level, pos);
                                if (mapColor != MapColor.NONE) {
                                    height = (short) y;
                                    rawRgb = mapColor.calculateARGBColor(Brightness.NORMAL) & 0xFFFFFF;
                                    break;
                                }
                            }
                        } else {
                            anyChunkMissing = true;
                        }
                        int idx = cz * MAP_WIDTH + cx;
                        arenaColors[idx] = rawRgb;
                        arenaHeights[idx] = height;
                    }
                }
                radarViewDirty = true;
                scanColumn = end;
                if (scanColumn == MAP_WIDTH) {
                    if (anyChunkMissing && scanPasses < 5) {
                        scanPasses++;
                        scanColumn = 0;
                    } else {
                        scanComplete = true;
                        textureUploaded = true;
                    }
                }
            }

            // Abgelaufene Kontakte entfernen
            int currentTick = client.player.tickCount;
            contacts.entrySet().removeIf(e -> e.getValue().expiryTick() < currentTick);

            LocalPlayer player = client.player;
            Vec3 eye = player.getEyePosition();
            Vec3 look = player.getViewVector(1.0F);

            // Taktische Ortung: Rennen, Springen, Schießen, Grappler, Sichtlinie
            for (Player other : client.level.players()) {
                if (other == player || !other.isAlive() || other.isSpectator() || other.isInvisible()) {
                    continue;
                }

                boolean sprinting = other.isSprinting();
                boolean jumping = !other.onGround() && !other.isInWater() && Math.abs(other.getDeltaMovement().y) > 0.06;
                boolean usingWeapon = other.isUsingItem() && (other.getUseItem().is(Items.BOW) || other.getUseItem().is(ModItems.MINIGUN));
                boolean grappling = GrapplePullState.INSTANCE.isGrappleActive(other.getUUID());

                Vec3 toOther = other.getEyePosition().subtract(eye);
                double dist = toOther.length();
                boolean onScreen = false;
                if (dist > 0.1 && dist < 128.0) {
                    double dot = look.dot(toOther.scale(1.0 / dist));
                    if (dot > 0.40 && player.hasLineOfSight(other)) {
                        onScreen = true;
                    }
                }

                if (sprinting || jumping || usingWeapon || grappling || onScreen) {
                    double dy = other.getY() - player.getY();
                    contacts.put(other.getUUID(), new EnemyContact(other.position(), dy, usingWeapon || grappling, currentTick + 40));
                }
            }
        }

        public void markDirty() {
            scanColumn = 0;
            scanPasses = 0;
            scanComplete = false;
            radarViewDirty = true;
        }

        public void clear() {
            Minecraft client = Minecraft.getInstance();
            if (radarViewTexture != null) {
                client.getTextureManager().release(RADAR_VIEW_ID);
                radarViewTexture = null;
                radarImage = null;
            }
            Arrays.fill(arenaColors, 0);
            Arrays.fill(arenaHeights, (short) 0);
            textureUploaded = false;
            radarViewReady = false;
            radarViewDirty = true;
            matchRunning = false;
            lastPx = Double.NaN;
            lastPz = Double.NaN;
            lastYaw = Float.NaN;
            scanColumn = 0;
            scanPasses = 0;
            scanComplete = false;
            currentDimension = "";
            contacts.clear();
        }

        public record EnemyContact(Vec3 pos, double dy, boolean shooting, int expiryTick) {
        }
    }

    // =========================================================================
    // TabScoreboardState.java (CS:GO Tactical Match Scoreboard)
    // =========================================================================
    public static final class TabScoreboardState {
        public static final TabScoreboardState INSTANCE = new TabScoreboardState();

        private MatchScoreboardPayload currentPayload = MatchScoreboardPayload.empty();
        private float openProgress = 0.0F;
        private float prevOpenProgress = 0.0F;

        private TabScoreboardState() {
        }

        public void onPayload(MatchScoreboardPayload payload) {
            this.currentPayload = payload;
        }

        public MatchScoreboardPayload getPayload() {
            return currentPayload;
        }

        public boolean isActiveMatch() {
            return currentPayload.matchState() != 0;
        }

        public void tick(boolean tabDown) {
            prevOpenProgress = openProgress;
            if (tabDown) {
                openProgress = Math.min(1.0F, openProgress + 0.45F);
            } else {
                openProgress = Math.max(0.0F, openProgress - 0.50F);
            }
        }

        public float getTabOpenProgress(float partialTick) {
            return Mth.lerp(partialTick, prevOpenProgress, openProgress);
        }

        public List<MatchScoreboardPayload.PlayerEntry> getSortedEntries() {
            List<MatchScoreboardPayload.PlayerEntry> list = new ArrayList<>(currentPayload.players());
            if ("GUN_GAME".equals(currentPayload.gameMode())) {
                list.sort(Comparator.comparingInt(MatchScoreboardPayload.PlayerEntry::tier)
                    .thenComparingInt(MatchScoreboardPayload.PlayerEntry::tierKills)
                    .thenComparingInt(MatchScoreboardPayload.PlayerEntry::kills)
                    .reversed());
            } else {
                list.sort(Comparator.comparingInt(MatchScoreboardPayload.PlayerEntry::kills)
                    .thenComparingDouble(MatchScoreboardPayload.PlayerEntry::kdRatio)
                    .reversed());
            }
            return list;
        }

        public MatchScoreboardPayload.@Nullable PlayerEntry getLocalPlayerEntry(UUID localPlayerId) {
            for (MatchScoreboardPayload.PlayerEntry entry : currentPayload.players()) {
                if (entry.playerId().equals(localPlayerId)) {
                    return entry;
                }
            }
            return null;
        }

        public int getLocalPlayerRank(UUID localPlayerId) {
            List<MatchScoreboardPayload.PlayerEntry> sorted = getSortedEntries();
            for (int i = 0; i < sorted.size(); i++) {
                if (sorted.get(i).playerId().equals(localPlayerId)) {
                    return i + 1;
                }
            }
            return -1;
        }

        public void reset() {
            currentPayload = MatchScoreboardPayload.empty();
            openProgress = 0.0F;
            prevOpenProgress = 0.0F;
        }
    }
}
