package com.oneshotonekill.client.mixin.renderer;

import com.oneshotonekill.client.effect.BoogieBombClient;
import com.oneshotonekill.client.effect.BoogieDanceAnimation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HumanoidModel.class)
public abstract class BoogieHumanoidModelMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("RETURN"))
    private void osok$discoPose(HumanoidRenderState state, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (!(state instanceof AvatarRenderState avatar) || client.level == null) return;
        Entity entity = client.level.getEntity(avatar.id);
        if (entity != null) {
            float weight = BoogieBombClient.getWeight(entity.getUUID());
            if (weight > 0.001F) {
                BoogieDanceAnimation.pose((HumanoidModel<?>) (Object) this,
                        BoogieBombClient.seconds(entity.getUUID()), weight, entity.getUUID());
            }
        }
    }
}
