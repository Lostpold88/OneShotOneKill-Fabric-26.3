package com.oneshotonekill.client.mixin.input;

import com.oneshotonekill.client.movement.ClientClimbing;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerMantleTravelMixin {
    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void osok$mantleTravel(Vec3 input, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (self instanceof LocalPlayer player && ClientClimbing.INSTANCE.travel(player)) ci.cancel();
    }
}
