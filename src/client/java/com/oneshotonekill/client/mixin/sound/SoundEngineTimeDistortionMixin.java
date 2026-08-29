package com.oneshotonekill.client.mixin.sound;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.oneshotonekill.client.effect.TimeDistortionEffects;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Dämpft und verlangsamt (Pitch-Drop) die vorhandene Welttonspur samt Minigun während des Zeitbruchs. */
@Mixin(SoundEngine.class)
public abstract class SoundEngineTimeDistortionMixin {
   @ModifyReturnValue(method = "calculateVolume(FLnet/minecraft/sounds/SoundSource;)F", at = @At("RETURN"))
   private float osok$duckWorldDuringTimeDistortion(float original, float volume, SoundSource source) {
      return original * TimeDistortionEffects.INSTANCE.soundFactor(source);
   }

   @ModifyReturnValue(method = "calculatePitch(Lnet/minecraft/client/resources/sounds/SoundInstance;)F", at = @At("RETURN"))
   private float osok$pitchDropDuringTimeDistortion(float original, SoundInstance instance) {
      return original * TimeDistortionEffects.INSTANCE.soundPitchFactor(instance.getSource());
   }
}
