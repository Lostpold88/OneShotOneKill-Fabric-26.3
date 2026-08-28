package com.oneshotonekill.client.mixin;

import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Der {@code SoundManager} reicht seine Engine nicht heraus; für den Tonhöhen-Nachzug nötig. */
@Mixin(SoundManager.class)
public interface SoundManagerAccessor {
   @Accessor("soundEngine")
   SoundEngine osok$soundEngine();
}
