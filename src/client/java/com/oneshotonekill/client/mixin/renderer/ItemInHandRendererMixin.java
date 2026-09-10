package com.oneshotonekill.client.mixin.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.oneshotonekill.client.ClientInputEvents;
import com.oneshotonekill.client.movement.ClientClimbing;
import com.oneshotonekill.registry.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lässt die Minigun in der Hand rütteln: erst das anlaufende Getriebe, dann der Rückstoß.
 * <p>
 * <p>Fabric API kennt kein Gegenstück zu NeoForges {@code RenderHandEvent}; das Rendering-Modul
 * bietet Ereignisse für Weltebenen und HUD, nicht für die erste Person. Ein Access Widener hilft
 * nicht, weil kein Zugriff fehlt, sondern eine Stelle, an der sich die Matrix verschieben lässt.</p>
 * <p>
 * <p>Die Verschiebung bekommt eine eigene Ebene auf dem {@code PoseStack}, die am Ende der
 * Methode wieder abgeräumt wird. Der Vanilla-Rumpf von {@code submitArmWithItem} ist selbst
 * sauber geklammert und hat keinen vorzeitigen Ausstieg, ein {@code RETURN}-Einstieg trifft
 * also genau einmal.</p>
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    /**
     * Vanilla setzt bei jedem erfolgreichen Rechtsklick die Höhe der benutzten Hand auf null.
     * Für den Grappler sähe das wie eine Block-Platzieranimation aus und würde genau den Moment
     * verdecken, in dem der Pömpel das Rohr verlässt.
     */
    @Inject(method = "itemUsed", at = @At("HEAD"), cancellable = true)
    private void osok$keepGrapplingHookRaised(InteractionHand hand, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.getItemInHand(hand).is(ModItems.GRAPPLING_HOOK)) {
            ci.cancel();
        }
    }

    @Inject(method = "submitArmWithItem", at = @At("HEAD"))
    private void osok$pushShake(AbstractClientPlayer player, float frameInterp, float xRot, InteractionHand hand,
                                float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack,
                                SubmitNodeCollector submitNodeCollector, int lightCoords, CallbackInfo ci) {
        poseStack.pushPose();
        if (!player.isUsingItem() && attack <= 0) {
            float mantle = ClientClimbing.INSTANCE.pose(player, frameInterp);
            if (mantle > 0) {
                float stroke = ClientClimbing.INSTANCE.stroke(player, frameInterp);
                float sideShift = (hand == InteractionHand.MAIN_HAND ? 1.0F : -1.0F) * stroke * 0.035F;
                float vertShift = Math.abs(stroke) * 0.025F;
                poseStack.translate(sideShift, -0.28F * mantle + vertShift, -0.10F * mantle);
                poseStack.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(stroke * (hand == InteractionHand.MAIN_HAND ? 2.5F : -2.5F)));
            }
        }
        ClientInputEvents.applyMinigunHandShake(itemStack, frameInterp, poseStack);
        ClientInputEvents.applyTimeDistorterHandPose(itemStack, poseStack);
        ClientInputEvents.applyGrapplerHandShake(itemStack, frameInterp, poseStack);
    }

    @Inject(method = "submitArmWithItem", at = @At("RETURN"))
    private void osok$popShake(AbstractClientPlayer player, float frameInterp, float xRot, InteractionHand hand,
                               float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack,
                               SubmitNodeCollector submitNodeCollector, int lightCoords, CallbackInfo ci) {
        poseStack.popPose();
    }
}
