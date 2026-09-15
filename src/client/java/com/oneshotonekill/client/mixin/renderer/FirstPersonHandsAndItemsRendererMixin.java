package com.oneshotonekill.client.mixin.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.oneshotonekill.client.ClientInputEvents;
import com.oneshotonekill.client.movement.ClientClimbing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lässt die Minigun in der Hand rütteln: erst das anlaufende Getriebe, dann der Rückstoß.
 * Verwaltet außerdem Kletter- und Zeitverzerrungs-Animationen in der ersten Person.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonHandsAndItemsRendererMixin {
    @Inject(method = "submitArmWithItem", at = @At("HEAD"))
    private void osok$pushShake(PlayerRenderState playerRenderState,
                                FirstPersonHandsAndItemsRenderState handsAndItemsRenderState,
                                float frameInterp, float xRot, InteractionHand hand,
                                float attack, ItemStack itemStack, float inverseArmHeight,
                                PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                                int lightCoords, CallbackInfo ci) {
        poseStack.pushPose();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && !player.isUsingItem() && attack <= 0) {
            float mantle = ClientClimbing.INSTANCE.pose(player, frameInterp);
            if (mantle > 0) {
                float stroke = ClientClimbing.INSTANCE.stroke(player, frameInterp);
                float sideShift = (hand == InteractionHand.MAIN_HAND ? 1.0F : -1.0F) * stroke * 0.035F;
                float vertShift = Math.abs(stroke) * 0.025F;
                poseStack.translate(sideShift, -0.28F * mantle + vertShift, -0.10F * mantle);
                poseStack.rotate(com.mojang.math.Axis.ZP.rotationDegrees(stroke * (hand == InteractionHand.MAIN_HAND ? 2.5F : -2.5F)));
            }
        }
        ClientInputEvents.applyMinigunHandShake(itemStack, frameInterp, poseStack);
        ClientInputEvents.applyTimeDistorterHandPose(itemStack, poseStack);
        ClientInputEvents.applyGrapplerHandShake(itemStack, frameInterp, poseStack);
    }

    @Inject(method = "submitArmWithItem", at = @At("RETURN"))
    private void osok$popShake(PlayerRenderState playerRenderState,
                               FirstPersonHandsAndItemsRenderState handsAndItemsRenderState,
                               float frameInterp, float xRot, InteractionHand hand,
                               float attack, ItemStack itemStack, float inverseArmHeight,
                               PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                               int lightCoords, CallbackInfo ci) {
        poseStack.popPose();
    }
}
