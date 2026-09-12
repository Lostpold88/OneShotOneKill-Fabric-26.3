package com.oneshotonekill.mixin.match;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin zur vollständigen Deaktivierung von Erfolgen / Advancements.
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {

	@Inject(method = "award", at = @At("HEAD"), cancellable = true)
	private void onAward(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
		cir.setReturnValue(false);
	}
}
