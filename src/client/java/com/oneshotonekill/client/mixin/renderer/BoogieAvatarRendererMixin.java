package com.oneshotonekill.client.mixin.renderer;

import com.oneshotonekill.client.effect.BoogieBombClient;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides held weapons while dancing without modifying the inventory. */
@Mixin(AvatarRenderer.class)
public abstract class BoogieAvatarRendererMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("RETURN"))
    private void osok$emptyDancingHands(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
        if (BoogieBombClient.getWeight(entity.getUUID()) < 0.35F) return;
        state.leftHandItemStack = ItemStack.EMPTY;
        state.rightHandItemStack = ItemStack.EMPTY;
        state.leftHandItemState.clear();
        state.rightHandItemState.clear();
        state.leftArmPose = HumanoidModel.ArmPose.EMPTY;
        state.rightArmPose = HumanoidModel.ArmPose.EMPTY;
        state.attackTime = 0;
        state.isCrouching = false;
        state.isFallFlying = false;
        state.swimAmount = 0;
    }
}
