package com.oneshotonekill.mixin.movement;

import com.oneshotonekill.movement.ClimbingNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla fall-distance bookkeeping treats an approved wall grip like a ladder. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityClimbingMixin {
    @Inject(method = "onClimbable", at = @At("HEAD"), cancellable = true)
    private void osok$wallContact(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof ServerPlayer player && ClimbingNetworking.isClimbing(player)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "checkFallDamage", at = @At("HEAD"), cancellable = true)
    private void osok$climbingCheckFallDamage(double ya, boolean onGround, BlockState onState, BlockPos pos, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof ServerPlayer player && ClimbingNetworking.shouldNegateFallDamage(player)) {
            player.resetFallDistance();
            ci.cancel();
        }
    }

    @Inject(method = "causeFallDamage", at = @At("HEAD"), cancellable = true)
    private void osok$climbingCauseFallDamage(double fallDistance, float damageModifier, DamageSource damageSource, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof ServerPlayer player && ClimbingNetworking.shouldNegateFallDamage(player)) {
            player.resetFallDistance();
            cir.setReturnValue(false);
        }
    }
}

