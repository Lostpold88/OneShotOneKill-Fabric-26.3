package com.oneshotonekill.client.sound;

import com.oneshotonekill.client.effect.TimeDistortionEffects;
import com.oneshotonekill.client.mixin.SoundEngineAccessor;
import com.oneshotonekill.client.mixin.SoundManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance.Attenuation;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;

/**
 * Die eigene, ungedämpfte Tonspur über der per Mixin abgesenkten Welt.
 *
 * <p>Alles hier läuft je Bild und auf {@link Util#getMillis()}, nicht je Tick: Während der
 * Zeitlupe übernimmt der Client die Serverfrequenz von acht Ticks je Sekunde, ein Tickablauf
 * hätte also nur noch 125 ms Auflösung. Der beschleunigende Herzschlag verkürzt sich am Ende auf
 * 260 ms – bei Tickauflösung wäre das ein Schwanken von fast der Hälfte, und der Aktivierungsklang
 * käme bis zu einem Achtel Sekunde nach dem Bild.</p>
 */
public final class TimeDistortionSoundController {
   public static final TimeDistortionSoundController INSTANCE = new TimeDistortionSoundController();

   /** Ab dieser Änderung lohnt sich ein Nachzug an den laufenden Kanälen. */
   private static final float REFRESH_EPSILON = 0.003F;

   private ChronoHum hum;
   private boolean wasActive;
   private boolean wasVisible;
   private long nextPulseAt;
   private float appliedVolumeFactor = 1.0F;
   private float appliedPitchFactor = 1.0F;

   private TimeDistortionSoundController() {
   }

   /** Wird aus dem Renderdurchgang aufgerufen, damit echte Zeit und nicht der Spieltakt zählt. */
   public void frame(Minecraft client) {
      TimeDistortionEffects effects = TimeDistortionEffects.INSTANCE;
      boolean active = effects.isActive();
      boolean visible = effects.isVisible();
      long now = Util.getMillis();

      if (active && !wasActive) {
         hum = new ChronoHum();
         client.getSoundManager().queueTickingSound(hum);
         client.getSoundManager().play(SimpleSoundInstance.forUI(
            SoundEvents.RESPAWN_ANCHOR_CHARGE, 0.58F, 0.55F));
         nextPulseAt = now + 520L;
      } else if (!active && wasActive) {
         if (hum != null) {
            hum.fadeOut();
         }
         client.getSoundManager().play(SimpleSoundInstance.forUI(
            SoundEvents.RESPAWN_ANCHOR_DEPLETE.value(), 1.65F, 0.62F));
         client.getSoundManager().play(SimpleSoundInstance.forUI(
            SoundEvents.AMETHYST_BLOCK_RESONATE, 0.78F, 0.48F));
      }

      if (hum != null && hum.isStopped()) {
         hum = null;
      }

      if (visible || wasVisible) {
         applyToRunningSounds(client, effects);
      }

      if (active && now >= nextPulseAt) {
         float urgency = effects.modelUrgency();
         float pitch = 0.72F + urgency * 0.88F;
         client.getSoundManager().play(SimpleSoundInstance.forUI(
            SoundEvents.COMPARATOR_CLICK, pitch, 0.24F + urgency * 0.16F));
         if (urgency > 0.55F) {
            client.getSoundManager().play(SimpleSoundInstance.forUI(
               SoundEvents.NOTE_BLOCK_BELL.value(), 1.35F + urgency * 0.45F, 0.16F));
         }
         nextPulseAt = now + (long) (920.0F - urgency * 660.0F);
      }
      wasActive = active;
      wasVisible = visible;
   }

   /**
    * Zieht Lautstärke und Tonhöhe der bereits laufenden Klänge nach.
    *
    * <p>Ein Aufruf mit {@link SoundSource#MASTER} erfasst in {@code refreshCategoryVolume} jede
    * Instanz, unabhängig von ihrer Quelle – ein Durchlauf statt neun. Die Tonhöhe muss dagegen
    * selbst gesetzt werden; Vanilla frischt sie nur für tickende Klänge auf. Beides geschieht
    * erst, wenn sich der Faktor merklich bewegt hat, damit im Dauerzustand keine Aufträge an den
    * Tonthread laufen.</p>
    */
   private void applyToRunningSounds(Minecraft client, TimeDistortionEffects effects) {
      float volumeFactor = effects.soundFactor(SoundSource.AMBIENT);
      float pitchFactor = effects.soundPitchFactor(SoundSource.AMBIENT);
      SoundManager manager = client.getSoundManager();

      if (Math.abs(volumeFactor - appliedVolumeFactor) > REFRESH_EPSILON) {
         manager.refreshCategoryVolume(SoundSource.MASTER);
         appliedVolumeFactor = volumeFactor;
      }

      if (Math.abs(pitchFactor - appliedPitchFactor) > REFRESH_EPSILON) {
         SoundEngine engine = ((SoundManagerAccessor) manager).osok$soundEngine();
         SoundEngineAccessor accessor = (SoundEngineAccessor) engine;
         accessor.osok$instanceToChannel().forEach((instance, handle) -> {
            if (handle.isStopped()) {
               return;
            }
            float pitch = accessor.osok$calculatePitch(instance);
            handle.execute(channel -> channel.setPitch(pitch));
         });
         appliedPitchFactor = pitchFactor;
      }
   }

   public void stopAll() {
      if (hum != null) {
         hum.stopNow();
         hum = null;
      }
      wasActive = false;
      wasVisible = false;
      nextPulseAt = 0L;
      appliedVolumeFactor = 1.0F;
      appliedPitchFactor = 1.0F;
   }

   /**
    * Der Träger der Zeitlupe.
    *
    * <p>Lautstärke und Tonhöhe werden nicht mehr in {@code tick()} zwischengespeichert, sondern
    * bei jeder Abfrage aus der Echtzeit berechnet. So bleibt die 480-ms-Einblendung auch dann
    * stufenlos, wenn der Client währenddessen nur achtmal je Sekunde tickt.</p>
    */
   private static final class ChronoHum extends AbstractTickableSoundInstance {
      private static final long FADE_MILLIS = 600L;
      private long fadeStartedAt = Long.MIN_VALUE;

      private ChronoHum() {
         super(SoundEvents.BEACON_AMBIENT, SoundSource.MASTER, RandomSource.create());
         this.looping = true;
         this.delay = 0;
         this.attenuation = Attenuation.NONE;
         this.volume = 0.0F;
         this.pitch = 0.50F;
      }

      @Override
      public float getVolume() {
         if (this.fadeStartedAt != Long.MIN_VALUE) {
            float progress = Math.clamp(
               (Util.getMillis() - this.fadeStartedAt) / (float) FADE_MILLIS, 0.0F, 1.0F);
            return 0.20F * (1.0F - progress);
         }
         return 0.20F * TimeDistortionEffects.INSTANCE.modelActivePower();
      }

      @Override
      public float getPitch() {
         return 0.48F + TimeDistortionEffects.INSTANCE.modelUrgency() * 0.10F;
      }

      @Override
      public void tick() {
         if (this.fadeStartedAt != Long.MIN_VALUE
            && Util.getMillis() - this.fadeStartedAt >= FADE_MILLIS) {
            stop();
         }
      }

      private void fadeOut() {
         if (this.fadeStartedAt == Long.MIN_VALUE) {
            this.fadeStartedAt = Util.getMillis();
         }
      }

      private void stopNow() {
         stop();
      }
   }
}
