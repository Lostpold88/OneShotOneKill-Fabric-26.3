package com.oneshotonekill.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.oneshotonekill.client.ClientInputEvents;
import net.minecraft.client.player.AbstractClientPlayer;
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
 *
 * <p>Fabric API kennt kein Gegenstück zu NeoForges {@code RenderHandEvent}; das Rendering-Modul
 * bietet Ereignisse für Weltebenen und HUD, nicht für die erste Person. Ein Access Widener hilft
 * nicht, weil kein Zugriff fehlt, sondern eine Stelle, an der sich die Matrix verschieben lässt.</p>
 *
 * <p>Die Verschiebung bekommt eine eigene Ebene auf dem {@code PoseStack}, die am Ende der
 * Methode wieder abgeräumt wird. Der Vanilla-Rumpf von {@code submitArmWithItem} ist selbst
 * sauber geklammert und hat keinen vorzeitigen Ausstieg, ein {@code RETURN}-Einstieg trifft
 * also genau einmal.</p>
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
   @Inject(method = "submitArmWithItem", at = @At("HEAD"))
   private void osok$pushShake(AbstractClientPlayer player, float frameInterp, float xRot, InteractionHand hand,
                               float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack,
                               SubmitNodeCollector submitNodeCollector, int lightCoords, CallbackInfo ci) {
      poseStack.pushPose();
      ClientInputEvents.applyMinigunHandShake(itemStack, frameInterp, poseStack);
   }

   @Inject(method = "submitArmWithItem", at = @At("RETURN"))
   private void osok$popShake(AbstractClientPlayer player, float frameInterp, float xRot, InteractionHand hand,
                              float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack,
                              SubmitNodeCollector submitNodeCollector, int lightCoords, CallbackInfo ci) {
      poseStack.popPose();
   }
}
