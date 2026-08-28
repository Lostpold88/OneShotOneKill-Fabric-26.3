package com.oneshotonekill.client.mixin;

import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.oneshotonekill.client.effect.TimeDistortionEffects;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Legt den kurzen Zeitbruch auf die fertig gezeichnete Welt, aber noch vor HUD und Menüs.
 * Der vorhandene Fabric-Rendering-API fehlt ein Callback zwischen Welt-Postprocessing und GUI.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererTimeDistortionMixin {
   @Shadow @Final private CrossFrameResourcePool resourcePool;

   @Inject(method = "render", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
   private void osok$applyTimeDistortion(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
      Minecraft client = Minecraft.getInstance();
      Identifier effectId = TimeDistortionEffects.INSTANCE.currentPostEffect();
      if (client.level == null || effectId == null) {
         return;
      }
      PostChain chain = client.getShaderManager().getPostChain(effectId, LevelTargetBundle.MAIN_TARGETS);
      if (chain != null) {
         chain.process(((GameRenderer) (Object) this).mainRenderTarget(), this.resourcePool);
      }
   }
}
