package com.oneshotonekill.client.sound;

import com.oneshotonekill.client.effect.TimeDistortionEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance.Attenuation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;

/** Die eigene, ungedämpfte Tonspur über der per Mixin abgesenkten Welt. */
public final class TimeDistortionSoundController {
   public static final TimeDistortionSoundController INSTANCE = new TimeDistortionSoundController();

   private ChronoHum hum;
   private boolean wasActive;
   private boolean wasVisible;
   private long nextPulseAt;
   private long lastVolumeRefresh;

   private TimeDistortionSoundController() {
   }

   public void tick(Minecraft client) {
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
      if ((visible || wasVisible) && now - lastVolumeRefresh >= 50L) {
         for (SoundSource source : SoundSource.values()) {
            client.getSoundManager().refreshCategoryVolume(source);
         }
         lastVolumeRefresh = now;
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

   public void stopAll() {
      if (hum != null) {
         hum.stopNow();
         hum = null;
      }
      wasActive = false;
      wasVisible = false;
      nextPulseAt = 0L;
      lastVolumeRefresh = 0L;
   }

   private static final class ChronoHum extends AbstractTickableSoundInstance {
      private static final int FADE_TICKS = 12;
      private int fading = -1;

      private ChronoHum() {
         super(SoundEvents.BEACON_AMBIENT, SoundSource.MASTER, RandomSource.create());
         this.looping = true;
         this.delay = 0;
         this.attenuation = Attenuation.NONE;
         this.volume = 0.0F;
         this.pitch = 0.50F;
      }

      @Override
      public void tick() {
         if (fading >= 0) {
            if (++fading >= FADE_TICKS) {
               stop();
               return;
            }
            this.volume = 0.20F * (1.0F - fading / (float) FADE_TICKS);
            return;
         }
         TimeDistortionEffects effects = TimeDistortionEffects.INSTANCE;
         this.volume = 0.20F * effects.modelActivePower();
         this.pitch = 0.48F + effects.modelUrgency() * 0.10F;
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
