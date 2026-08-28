package com.oneshotonekill.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.oneshotonekill.client.effect.TimeDistortionEffects;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Dämpft die vorhandene Welttonspur, während der eigene Chrono-Hum darüber klar bleibt. */
@Mixin(SoundEngine.class)
public abstract class SoundEngineTimeDistortionMixin {
   @ModifyReturnValue(method = "calculateVolume(FLnet/minecraft/sounds/SoundSource;)F", at = @At("RETURN"))
   private float osok$duckWorldDuringTimeDistortion(float original, float volume, SoundSource source) {
      return original * TimeDistortionEffects.INSTANCE.soundFactor(source);
   }
}
