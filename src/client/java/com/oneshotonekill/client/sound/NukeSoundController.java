package com.oneshotonekill.client.sound;

import static com.oneshotonekill.client.state.ClientStates.*;
import com.oneshotonekill.client.state.ClientStates.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * Die Tonspur des Matchendes.
 * <p>
 * <h2>Die Ansage</h2>
 * <p>
 * <p>{@code endgame/TacticalNukeIncoming.ogg} laeuft vom ersten Tick der Sequenz an durch – das
 * Original aus Call of Duty, und der ganze Ablauf ist darauf gelegt: Bei 12,031 Sekunden
 * schlaegt es in der Aufnahme ein, und {@code NukePhase.DETONATION} liegt auf Tick 241, also
 * bei 12,050 Sekunden. Naeher kommt man mit tickgebundener Steuerung nicht heran.</p>
 * <p>
 * <p>Sie liegt allerdings nicht hier, sondern auf der Serverseite: {@code NukeSequenceManager}
 * schickt sie im ersten Tick der Sequenz als Klangpaket, das an der Spieler-Entity haengt. Der
 * Umweg ueber den Client hatte ein Fenster von drei Ticks, in dem er sie starten durfte – und
 * wer es verpasste, weil ein Paket spaeter ankam, hoerte gar nichts. Ein Paket, das den Ton
 * mitbringt, kann man nicht verpassen.</p>
 * <p>
 * <h2>Der Nachhall</h2>
 * <p>
 * <p>Nach dem Einschlag bleibt ein tiefes Grollen stehen, bis die Sequenz endet. Das ist ein
 * gehaltener Vanilla-Ton eine Oktave tiefer und halb so laut – dafuer braucht es keine eigene
 * Datei, weil ein Grollen keine Melodie hat.</p>
 */
public final class NukeSoundController {
   public static final NukeSoundController INSTANCE = new NukeSoundController();

   /**
    * Der Nachlauf-Loop: Wind, tiefe Drone, Feuerknistern und fernes Grollen - ein nahtlos geschleifter,
    * synthetisierter Track (siehe {@code tools/audio/generate_nuke_audio.py}), der unter dem Einschlag
    * einblendet und bis zum Stopp des Matches stehen bleibt.
    */
   private static final SoundEvent AFTERMATH = com.oneshotonekill.registry.ModSounds.NUKE_FALLOUT;
   private static final float AFTERMATH_VOLUME = 0.85F;

   private NukeLoopSound aftermath;

   private NukeSoundController() {
   }

   /** Wird im Client-Takt aufgerufen und haelt die Tonspur am richtigen Punkt. */
   public void tick(Minecraft client) {
      if (aftermath != null && aftermath.isStopped()) {
         aftermath = null;
      }
      if (client.level == null) {
         stopAll();
         return;
      }

      NukeState state = NukeState.INSTANCE;
      if (!state.isRunning()) {
         if (aftermath != null) {
            stopAll();
         }
         return;
      }

      boolean wantAftermath = state.wantsAftermathDrone();
      if (wantAftermath && aftermath == null) {
         aftermath = new NukeLoopSound(AFTERMATH, AFTERMATH_VOLUME);
         client.getSoundManager().queueTickingSound(aftermath);
      } else if (!wantAftermath && aftermath != null) {
         aftermath.fadeOut();
      }
   }

   public void stopAll() {
      if (aftermath != null) {
         aftermath.stopNow();
         aftermath = null;
      }
   }

   /**
    * Ein Dauerklang ohne Abstandsdaempfung, der weich auslaeuft.
    * <p>
    * Das Auslaufen ist wichtig: Ein Grollen, das hart abbricht, klingt nach einem Fehler.
    */
   private static final class NukeLoopSound extends AbstractTickableSoundInstance {
      private static final int FADE_TICKS = 16;
      /** Der Loop blendet unter dem Nachhall des Einschlags langsam ein - nicht hart nach dem Knall. */
      private static final int FADE_IN_TICKS = 90;

      private final float peakVolume;
      private int fading = -1;
      private int age;

      private NukeLoopSound(SoundEvent sound, float peakVolume) {
         super(sound, SoundSource.MASTER, RandomSource.create());
         this.peakVolume = peakVolume;
         this.looping = true;
         this.delay = 0;
         this.attenuation = Attenuation.NONE;
         this.volume = 0.0F;
         this.pitch = 1.0F;
      }

      @Override
      public void tick() {
         float fadeIn = Math.min(1.0F, ++age / (float) FADE_IN_TICKS);
         if (fading < 0) {
            this.volume = peakVolume * fadeIn;
            return;
         }
         if (++fading >= FADE_TICKS) {
            stop();
            return;
         }
         this.volume = peakVolume * fadeIn * (1.0F - fading / (float) FADE_TICKS);
      }

      private void fadeOut() {
         if (fading < 0) {
            fading = 0;
         }
      }

      private void stopNow() {
         stop();
      }
   }
}
