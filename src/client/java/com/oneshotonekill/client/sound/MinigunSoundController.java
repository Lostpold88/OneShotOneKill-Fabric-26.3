package com.oneshotonekill.client.sound;

import static com.oneshotonekill.client.state.ClientStates.*;
import com.oneshotonekill.client.state.ClientStates.*;
import com.oneshotonekill.registry.ModItems;
import com.oneshotonekill.registry.ModSounds;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * Der Dauerlauf der Minigun aus {@code sounds/items/minigun.ogg} – für jeden, der ihn hören soll.
 * <p>
 * <p>Vorher lief er nur für den Schützen selbst. Gegner hörten von der Waffe gar nichts, und
 * ein zwischenzeitlich daruntergelegtes Pfeilgeräusch je Schuss war lauter als der Lauf selbst.
 * Jetzt bekommt jeder feuernde Spieler seinen eigenen Lauf: beim eigenen ohne Abstandsdämpfung
 * mitten im Kopf, bei fremden an ihrer Position im Raum.</p>
 * <p>
 * <p>Beide laufen mit derselben Grundlautstärke. Ein Zuschlag für fremde Waffen stand hier
 * einmal – der sollte über die Entfernung tragen, machte den fremden Lauf aus der Nähe aber
 * lauter als den eigenen, und das nimmt dem Ton seine wichtigste Aufgabe: zu verraten, wo der
 * Schütze steht. Jetzt zählt allein der Abstand, und aus null Metern klingt eine fremde Minigun
 * genauso wie die eigene.</p>
 * <p>
 * <p>Dass der Client von fremden Spielern überhaupt weiß, ob sie feuern, liegt an Vanilla:
 * {@code isUsingItem} und der gehaltene Gegenstand werden ohnehin synchronisiert. Es braucht
 * dafür also kein eigenes Paket.</p>
 */
@SuppressWarnings("Java8CollectionRemoveIf")
public final class MinigunSoundController {
    public static final MinigunSoundController INSTANCE = new MinigunSoundController();
    private final Map<UUID, MinigunLoopSound> loops = new HashMap<>();
    private MinigunLoopSound loop;

    private MinigunSoundController() { }

    /** Wird von OsokClient bei jedem Client-Tick aufgerufen. */
    public void tickClient(Minecraft client) {
        tick(client);
    }

    /** Beim Verlassen des Servers muss der laufende Klang sofort weg. */
    public void stopAll() {
        stop();
    }

    /** Die Waffe ist verbraucht – der Lauf trudelt aus, statt hart abzubrechen. */
    public void playExpiryHiss() {
        if (loop != null) loop.beginSpinDown();
    }

    private void tick(Minecraft client) {
        if (loop != null && loop.isStopped()) loop = null;
        if (client.level == null) {
            stop();
            return;
        }

        Iterator<Map.Entry<UUID, MinigunLoopSound>> spent = loops.entrySet().iterator();
        while (spent.hasNext()) {
            if (spent.next().getValue().isStopped()) spent.remove();
        }

        for (AbstractClientPlayer player : client.level.players()) {
            boolean self = player == client.player;
            // Nur beim eigenen Spieler zaehlt das Auslaufen: fuer fremde weiss der Client nicht,
            // ob deren Waffe gleich leer ist, und ein verstummender Lauf mitten im Feuer waere
            // irrefuehrender als einer, der eine Sekunde zu lang haelt.
            boolean firing = player.isUsingItem()
                && player.getUseItem().is(ModItems.MINIGUN)
                && !(self && MinigunHudState.INSTANCE.isExpiring());
            MinigunLoopSound running = loops.get(player.getUUID());
            if (!firing) {
                if (running != null) running.beginSpinDown();
                continue;
            }
            if (running == null) {
                running = new MinigunLoopSound(player, self);
                loops.put(player.getUUID(), running);
                client.getSoundManager().queueTickingSound(running);
                if (self) loop = running;
            } else {
                running.keepFiring();
            }
        }
    }

    private void stop() {
        loops.values().forEach(MinigunLoopSound::stopNow);
        loops.clear();
        loop = null;
    }

    /**
     * Der Lauf fährt hoch, hält, und läuft nach dem Loslassen aus: kurzer Nachdruck beim
     * Freigeben, danach fallen Tonhöhe und Lautstärke zusammen auf null.
     */
    private static final class MinigunLoopSound extends AbstractTickableSoundInstance {
        /** Grundlautstärke im Dauerfeuer – bewusst zurückhaltend. */
        private static final float FIRING_VOLUME = 0.32f;
        /** Kurzer Lautstärke-Überschwinger direkt beim Loslassen. */
        private static final float RELEASE_SWELL = 1.25f;
        private static final int SPIN_UP_TICKS = 10;
        private static final int RELEASE_SWELL_TICKS = 4;
        private static final int SPIN_DOWN_TICKS = 22;

        private final AbstractClientPlayer player;
        private int spinUpTicks;
        private int spinDownTicks = -1;

        private MinigunLoopSound(AbstractClientPlayer player, boolean self) {
            super(ModSounds.MINIGUN, SoundSource.PLAYERS, RandomSource.create());
            this.player = player;
            // Der eigene Lauf sitzt im Kopf, fremde stehen dort, wo ihr Schütze steht.
            this.attenuation = self ? Attenuation.NONE : Attenuation.LINEAR;
            this.relative = false;
            this.looping = true;
            this.delay = 0;
            this.volume = FIRING_VOLUME * 0.3f;
            this.pitch = 0.7f;
        }

        @Override
        public void tick() {
            if (player.isRemoved()) {
                stop();
                return;
            }
            this.x = player.getX();
            this.y = player.getEyeY();
            this.z = player.getZ();

            if (spinDownTicks < 0) {
                tickSpinUp();
            } else {
                tickSpinDown();
            }
        }

        private void tickSpinUp() {
            spinUpTicks = Math.min(SPIN_UP_TICKS, spinUpTicks + 1);
            float progress = spinUpTicks / (float) SPIN_UP_TICKS;
            this.volume = FIRING_VOLUME * (0.3f + 0.7f * progress);
            this.pitch = 0.7f + 0.3f * progress;
        }

        private void tickSpinDown() {
            spinDownTicks++;
            if (spinDownTicks >= SPIN_DOWN_TICKS) {
                stop();
                return;
            }

            if (spinDownTicks <= RELEASE_SWELL_TICKS) {
                // Kurzes Aufbäumen, bevor die Waffe wirklich ausläuft.
                float swell = spinDownTicks / (float) RELEASE_SWELL_TICKS;
                this.volume = FIRING_VOLUME * (1.0f + (RELEASE_SWELL - 1.0f) * swell);
                this.pitch = 1.0f + 0.05f * swell;
                return;
            }

            float progress = (spinDownTicks - RELEASE_SWELL_TICKS) / (float) (SPIN_DOWN_TICKS - RELEASE_SWELL_TICKS);
            float fade = (1.0f - progress) * (1.0f - progress);
            this.volume = FIRING_VOLUME * RELEASE_SWELL * fade;
            this.pitch = 1.05f - 0.55f * progress;
        }

        /** Erneutes Feuern innerhalb des Auslaufs holt den Lauf wieder auf Drehzahl. */
        private void keepFiring() {
            if (spinDownTicks >= 0) {
                spinUpTicks = Math.max(1, SPIN_UP_TICKS - spinDownTicks);
                spinDownTicks = -1;
            }
        }

        private void beginSpinDown() {
            if (spinDownTicks < 0) spinDownTicks = 0;
        }

        private void stopNow() { stop(); }
    }
}
