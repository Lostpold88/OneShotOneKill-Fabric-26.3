package com.oneshotonekill.mixin.movement;

import com.oneshotonekill.event.InteractionGates;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Both logical sides reject sprint activation while the player is dancing. */
@Mixin(LivingEntity.class)
public abstract class BoogieSprintMixin {
    @SuppressWarnings("ConstantValue")
    @Inject(method = "setSprinting(Z)V", at = @At("HEAD"), cancellable = true)
    private void osok$walkDuringDisco(boolean isSprinting, CallbackInfo ci) {
        if (isSprinting && (Object) this instanceof Player player && InteractionGates.isDancing(player)) ci.cancel();
    }
}
