package com.oneshotonekill.client.mixin.input;

import com.oneshotonekill.client.movement.ClientClimbing;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMantleMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void osok$sendMantleAfterMovement(CallbackInfo ci) {
        ClientClimbing.INSTANCE.tick((LocalPlayer) (Object) this);
    }
}
