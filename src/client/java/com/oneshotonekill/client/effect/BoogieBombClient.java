package com.oneshotonekill.client.effect;

import com.oneshotonekill.client.hud.BoogieHudLayers;
import com.oneshotonekill.event.InteractionGates;
import com.oneshotonekill.item.runtime.BoogieBombSystem;
import com.oneshotonekill.registry.ModSounds;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Client-only dance state. The server owns the effect; this class owns its clock, camera and audio. */
public final class BoogieBombClient {
    private static final Map<UUID, Dance> DANCERS = new HashMap<>();
    private static @Nullable ClientLevel level;
    private static @Nullable CameraType previousCamera;

    private static final Vector3fc[] NEON_PALETTE = {
        new Vector3f(1.0F, 0.12F, 0.58F),  // Hot Pink
        new Vector3f(0.0F, 0.92F, 1.0F),   // Electric Cyan
        new Vector3f(0.68F, 0.18F, 1.0F),  // Ultraviolet
        new Vector3f(1.0F, 0.84F, 0.12F),  // Disco Gold
        new Vector3f(0.0F, 1.0F, 0.52F)    // Mint Lime
    };

    private BoogieBombClient() {}

    @SuppressWarnings("unused")
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(BoogieBombSystem.State.TYPE,
                (packet, context) -> handle(packet, context.client()));
        ClientTickEvents.START_CLIENT_TICK.register(BoogieBombClient::preTick);
        ClientTickEvents.END_CLIENT_TICK.register(BoogieBombClient::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((_, client) -> clear(client));
        InteractionGates.registerClientDanceGate(player -> isDancing(player.getUUID()));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("oneshotonekill", "boogie_screen_fx"),
                new BoogieHudLayers.ScreenFxLayer());
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("oneshotonekill", "boogie_dance"),
                (graphics, _) -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client.player == null || !isDancing(client.player.getUUID())) return;
                    Dance dance = DANCERS.get(client.player.getUUID());
                    int seconds = Math.max(1, (int) Math.ceil((dance.deadline - System.nanoTime()) / 1_000_000_000.0));
                    Component message = Component.translatable("hud.oneshotonekill.boogie", seconds);
                    int x = (graphics.guiWidth() - client.font.width(message)) / 2;
                    int y = graphics.guiHeight() - 76;
                    graphics.fill(x - 7, y - 4, x + client.font.width(message) + 7, y + 13, 0xAF171126);
                    graphics.text(client.font, message, x, y, 0xFFFF83E6, true);
                });
    }

    public static boolean isDancing(UUID id) {
        Dance dance = DANCERS.get(id);
        return dance != null && dance.stopTime == 0L && System.nanoTime() < dance.deadline;
    }

    public static boolean isLocalDancing() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && isDancing(client.player.getUUID());
    }

    /** Berechnet den kontinuierlichen Blend-Faktor (0.0 bis 1.0) mit weichem Ein- und Ausklingen. */
    public static float getWeight(UUID id) {
        Dance dance = DANCERS.get(id);
        if (dance == null) return 0.0F;
        long now = System.nanoTime();
        if (now >= dance.deadline) return 0.0F;
        if (dance.stopTime > 0L) {
            float elapsedStop = (now - dance.stopTime) / 1_000_000_000.0F;
            float fade = 1.0F - Math.clamp(elapsedStop / 0.35F, 0.0F, 1.0F);
            return fade * fade * (3.0F - 2.0F * fade);
        }
        float elapsedSec = (now - dance.start) / 1_000_000_000.0F;
        float remainingSec = (dance.deadline - now) / 1_000_000_000.0F;
        float blendIn = Math.clamp(elapsedSec / 0.40F, 0.0F, 1.0F);
        float blendOut = Math.clamp(remainingSec / 0.45F, 0.0F, 1.0F);
        float target = Math.min(blendIn, blendOut);
        return target * target * (3.0F - 2.0F * target);
    }


    /** Zeitverzerrungs-angepasste Sekunden für synchrone Choreografie in Echtzeit und Zeitlupe. */
    public static float seconds(UUID id) {
        Dance dance = DANCERS.get(id);
        if (dance == null) return 0.0F;
        dance.update(System.nanoTime());
        return dance.accumulatedSeconds;
    }

    public static Vector3f getDiscoColor(float seconds) {
        float beat = seconds * 2.0F;
        float phase = (beat * 0.5F) % NEON_PALETTE.length;
        if (phase < 0) phase += NEON_PALETTE.length;
        int idx1 = (int) phase;
        int idx2 = (idx1 + 1) % NEON_PALETTE.length;
        float t = phase - idx1;
        float smoothT = t * t * (3.0F - 2.0F * t);
        Vector3fc c1 = NEON_PALETTE[idx1];
        Vector3fc c2 = NEON_PALETTE[idx2];
        return new Vector3f(
            c1.x() + (c2.x() - c1.x()) * smoothT,
            c1.y() + (c2.y() - c1.y()) * smoothT,
            c1.z() + (c2.z() - c1.z()) * smoothT
        );
    }

    public static float cameraRoll() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !isDancing(client.player.getUUID())) return 0.0F;
        UUID id = client.player.getUUID();
        float weight = getWeight(id);
        if (weight <= 0.001F) return 0.0F;
        float sec = seconds(id);
        float saltoProg = BoogieDanceAnimation.saltoProgress(sec);
        float saltoFade = saltoProg >= 0.0F ? (1.0F - Math.clamp((float) Math.sin(saltoProg * Math.PI) * 1.35F, 0.0F, 1.0F)) : 1.0F;
        float spinProg = BoogieDanceAnimation.spinProgress(sec);
        float spinFade = spinProg >= 0.0F ? (1.0F - Math.clamp((float) Math.sin(spinProg * Math.PI) * 1.30F, 0.0F, 1.0F)) : 1.0F;
        float beat = sec * 2.0F;
        float swing = (float) Math.sin(beat * Math.PI);
        return swing * 1.8F * weight * saltoFade * spinFade;
    }

    public static float modifyCameraDistance(float cameraDistance) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !isDancing(client.player.getUUID())) return cameraDistance;
        UUID id = client.player.getUUID();
        float weight = getWeight(id);
        if (weight <= 0.001F) return cameraDistance;
        float sec = seconds(id);

        // 1. Initialer Bass-Drop Punch (die ersten 0.45s beim Treffer der Granate)
        float dropZoom = (sec < 0.45F) ? (float) Math.sin(Math.clamp(sec / 0.45F, 0.0F, 1.0F) * Math.PI) * 1.35F : 0.0F;

        // 2. Salto-Zoom während des Saltos
        float p = BoogieDanceAnimation.saltoProgress(sec);
        float saltoZoom = (p >= 0.0F && p <= 1.0F) ? (float) Math.sin(p * Math.PI) * 1.10F : 0.0F;

        // 3. Rhythmischer Woofer-Puls auf jedem Beat (Subwoofer-Pumpen)
        float beat = sec * 2.0F;
        float kick = (float) Math.pow(Math.max(0.0F, Math.sin(beat * Math.PI)), 4.0);
        float wooferZoom = kick * 0.25F * weight;

        return Math.max(1.4F, cameraDistance - (dropZoom + saltoZoom + wooferZoom));
    }

    public static float fovModifier() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !isDancing(client.player.getUUID())) return 0.0F;
        UUID id = client.player.getUUID();
        float weight = getWeight(id);
        if (weight <= 0.001F) return 0.0F;
        float sec = seconds(id);
        float beat = sec * 2.0F;

        // 1. Initialer Bass-Drop FOV-Kick beim Treffer
        float dropPunch = (sec < 0.45F) ? (float) Math.sin(Math.clamp(sec / 0.45F, 0.0F, 1.0F) * Math.PI) * 0.075F : 0.0F;

        // 2. Rhythmischer Woofer-Kickdrum-Puls
        float kick = (float) Math.pow(Math.max(0.0F, Math.sin(beat * Math.PI)), 4.0);
        float kickFov = kick * 0.026F;

        // 3. Kontinuierliches subtiles Atmen & Salto-Punch
        float pulse = (float) Math.sin(beat * Math.PI * 2.0) * 0.016F;
        float p = BoogieDanceAnimation.saltoProgress(sec);
        float saltoPunch = (p >= 0.0F && p <= 1.0F) ? (float) Math.sin(p * Math.PI) * 0.035F : 0.0F;

        return (dropPunch + kickFov + pulse + saltoPunch) * weight;
    }

    public static void applyDiscoLighting(LightmapRenderState renderState) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !isDancing(client.player.getUUID())) return;
        UUID id = client.player.getUUID();
        float weight = getWeight(id);
        if (weight <= 0.001F) return;

        float sec = seconds(id);
        float beat = sec * 2.0F;
        Vector3f discoColor = getDiscoColor(sec);

        float pulse = 0.35F + 0.15F * (float) Math.sin(beat * Math.PI * 2.0);
        float blendFactor = Math.clamp(pulse * weight, 0.0F, 0.70F);

        Vector3fc origBlock = renderState.blockLightTint;
        renderState.blockLightTint = new Vector3f(
            origBlock.x() * (1.0F - blendFactor) + discoColor.x * blendFactor,
            origBlock.y() * (1.0F - blendFactor) + discoColor.y * blendFactor,
            origBlock.z() * (1.0F - blendFactor) + discoColor.z * blendFactor
        );

        Vector3fc origAmbient = renderState.ambientColor;
        float ambientBlend = blendFactor * 0.40F;
        renderState.ambientColor = new Vector3f(
            origAmbient.x() * (1.0F - ambientBlend) + discoColor.x * ambientBlend,
            origAmbient.y() * (1.0F - ambientBlend) + discoColor.y * ambientBlend,
            origAmbient.z() * (1.0F - ambientBlend) + discoColor.z * ambientBlend
        );
    }

    private static void handle(BoogieBombSystem.State packet, Minecraft client) {
        if (level != client.level) {
            clear(client);
            level = client.level;
        }
        long now = System.nanoTime();
        if (packet.remainingMillis() <= 0) {
            Dance dance = DANCERS.get(packet.playerId());
            if (dance != null && dance.stopTime == 0L) {
                if (dance.deadline - now > 400_000_000L) {
                    dance.stopTime = now;
                } else {
                    dance.stopTime = now - 400_000_000L;
                }
                if (dance.sound != null) dance.sound.finish();
            }
        } else {
            Dance dance = DANCERS.get(packet.playerId());
            if (dance == null) {
                float initialSeconds = Math.clamp(packet.elapsedMillis(), 0, BoogieBombSystem.DURATION_MILLIS) / 1000.0F;
                dance = new Dance(now - (long) (initialSeconds * 1_000_000_000L), initialSeconds);
                DANCERS.put(packet.playerId(), dance);
            } else {
                dance.stopTime = 0L;
            }
            dance.deadline = now + Math.clamp(packet.remainingMillis(), 0, BoogieBombSystem.DURATION_MILLIS) * 1_000_000L;
        }
        updateCamera(client);
    }

    private static void tick(Minecraft client) {
        if (client.level == null || level != client.level) {
            clear(client);
            level = client.level;
            return;
        }
        long now = System.nanoTime();
        Iterator<Map.Entry<UUID, Dance>> iterator = DANCERS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Dance> entry = iterator.next();
            Dance dance = entry.getValue();
            if (now >= dance.deadline && dance.stopTime == 0L) {
                dance.stopTime = now - 400_000_000L;
                if (dance.sound != null) dance.sound.finish();
            }
            if (dance.stopTime > 0L && (now - dance.stopTime) >= 350_000_000L) {
                if (dance.sound != null) dance.sound.finish();
                iterator.remove();
                continue;
            }
            Entity dancer = client.level.getPlayerByUUID(entry.getKey());
            boolean isLocal = client.player != null && client.player.getUUID().equals(entry.getKey());
            // Wenn der lokale Spieler selbst tanzt, spielt seine eigene Musik direkt;
            // fremde Sounds anderer Tänzer werden gestoppt, um akustische Überlagerungen zu vermeiden.
            if (isLocalDancing() && !isLocal) {
                if (dance.sound != null) {
                    dance.sound.finish();
                    dance.sound = null;
                }
                continue;
            }
            if (dancer != null && dance.sound == null && dance.stopTime == 0L) {
                dance.sound = new DiscoSound(dancer, entry.getKey(), isLocal);
                client.getSoundManager().queueTickingSound(dance.sound);
            }
        }
        if (isLocalDancing() && client.player != null) {
            client.player.stopUsingItem();
            client.player.setSprinting(false);
            client.options.keySprint.setDown(false);
        }
        updateCamera(client);
    }

    private static void preTick(Minecraft client) {
        enforceThirdPerson(client);
    }

    public static void checkCamera(Minecraft client) {
        enforceThirdPerson(client);
    }

    private static void enforceThirdPerson(Minecraft client) {
        if (!isLocalDancing()) return;
        if (previousCamera == null) {
            previousCamera = client.options.getCameraType();
            if (previousCamera.isFirstPerson()) {
                previousCamera = CameraType.FIRST_PERSON;
            }
        }
        // F5-Klicks konsumieren: Der Spieler darf zwischen den beiden 3rd-Person-Ansichten wechseln,
        // aber unter keinen Umständen in die First-Person-Perspektive gelangen!
        while (client.options.keyTogglePerspective.consumeClick()) {
            CameraType current = client.options.getCameraType();
            client.options.setCameraType(current == CameraType.THIRD_PERSON_BACK ? CameraType.THIRD_PERSON_FRONT : CameraType.THIRD_PERSON_BACK);
        }
        if (client.options.getCameraType().isFirstPerson()) {
            client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }
    }

    private static void updateCamera(Minecraft client) {
        if (isLocalDancing()) {
            enforceThirdPerson(client);
        } else if (previousCamera != null) {
            client.options.setCameraType(previousCamera);
            previousCamera = null;
        }
    }

    private static void clear(Minecraft client) {
        DANCERS.values().forEach(dance -> { if (dance.sound != null) dance.sound.finish(); });
        DANCERS.clear();
        updateCamera(client);
        level = null;
    }

    private static final class Dance {
        final long start;
        long deadline;
        long stopTime = 0L;
        @Nullable DiscoSound sound;
        float accumulatedSeconds;
        long lastUpdateNanos;

        Dance(long start, float initialSeconds) {
            this.start = start;
            this.accumulatedSeconds = initialSeconds;
            this.lastUpdateNanos = System.nanoTime();
        }

        void update(long now) {
            if (lastUpdateNanos <= 0L) {
                lastUpdateNanos = now;
                return;
            }
            long deltaNanos = now - lastUpdateNanos;
            lastUpdateNanos = now;
            if (deltaNanos <= 0L) return;
            float dt = deltaNanos / 1_000_000_000.0F;
            float timeScale = TimeDistortionEffects.INSTANCE.soundPitchFactor(SoundSource.PLAYERS);
            accumulatedSeconds += dt * timeScale;
        }
    }

    private static final class DiscoSound extends AbstractTickableSoundInstance {
        /** Die letzten 4 Sekunden klingen in einem Decrescendo sanft ab. */
        private static final float FADE_OUT_MILLIS = 4_000.0F;
        private final Entity dancer;
        private final UUID id;
        private final boolean isLocalPlayer;
        private final float baseVolume;

        DiscoSound(Entity dancer, UUID id, boolean isLocalPlayer) {
            super(ModSounds.BOOGIE_BOMB, SoundSource.PLAYERS, RandomSource.create());
            this.dancer = dancer;
            this.id = id;
            this.isLocalPlayer = isLocalPlayer;
            this.looping = true;
            this.delay = 0;
            this.pitch = 1.0F;

            if (isLocalPlayer) {
                // Getroffener Spieler: normales direktes Audio ohne räumliche Dämpfung
                this.attenuation = Attenuation.NONE;
                this.relative = true;
                this.baseVolume = 0.85F;
                this.x = 0;
                this.y = 0;
                this.z = 0;
            } else {
                // Werfer & Außenstehende: dezentral vom getroffenen Spieler, aber auch auf Distanz gut hörbar
                this.attenuation = Attenuation.LINEAR;
                this.relative = false;
                this.baseVolume = 1.30F;
                this.x = dancer.getX();
                this.y = dancer.getY() + 1.2;
                this.z = dancer.getZ();
            }
            this.volume = this.baseVolume;
        }

        @Override
        public void tick() {
            if (dancer.isRemoved() || !isDancing(id)) {
                stop();
                return;
            }
            if (!isLocalPlayer) {
                this.x = dancer.getX();
                this.y = dancer.getY() + 1.2;
                this.z = dancer.getZ();
            }
            this.volume = calculateDynamicVolume();
        }

        @Override
        public float getVolume() {
            return calculateDynamicVolume();
        }

        private float calculateDynamicVolume() {
            Dance dance = DANCERS.get(id);
            if (dance == null) return 0.0F;
            long remainingNanos = dance.deadline - System.nanoTime();
            if (remainingNanos <= 0) return 0.0F;
            float remainingMillis = remainingNanos / 1_000_000.0F;
            float fade = remainingMillis < FADE_OUT_MILLIS
                    ? Math.clamp(remainingMillis / FADE_OUT_MILLIS, 0.0F, 1.0F)
                    : 1.0F;
            return this.baseVolume * fade;
        }

        void finish() {
            stop();
        }
    }
}
