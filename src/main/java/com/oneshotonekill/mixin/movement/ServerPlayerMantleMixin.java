package com.oneshotonekill.mixin.movement;

import com.oneshotonekill.movement.ClimbingNetworking;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMantleMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void osok$validateMantle(CallbackInfo ci) {
        ClimbingNetworking.tick((ServerPlayer) (Object) this);
    }
}
