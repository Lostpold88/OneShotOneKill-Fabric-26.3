package com.oneshotonekill.client.mixin.effect;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.oneshotonekill.client.effect.TimeDistortionEffects;
import com.oneshotonekill.client.sound.TimeDistortionSoundController;
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
 * <p>
 * <p>Dieselbe Stelle ist zugleich der einzige verlässliche Takt in echter Zeit, den der Effekt
 * hat. Solange die Zeitlupe läuft, senkt Vanilla auch den Client-Takt auf acht Ticks je Sekunde
 * ({@code Minecraft#getTickTargetMillis} übernimmt die Serverfrequenz), weshalb der Tonablauf
 * hier je Bild und nicht je Tick fortgeschrieben wird.</p>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererTimeDistortionMixin {
    @Shadow
    @Final
    private CrossFrameResourcePool resourcePool;
    @Shadow
    @Final
    private RenderTarget mainRenderTarget;

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V"))
    private void osok$applyTimeDistortion(CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }

        TimeDistortionEffects effects = TimeDistortionEffects.INSTANCE;
        Identifier effectId = effects.currentPostEffect();
        TimeDistortionSoundController.INSTANCE.frame(client);
        if (effectId == null) {
            return;
        }

        try {
            effects.beginFrame();
            PostChain chain = client.getShaderManager().getPostChain(effectId, LevelTargetBundle.MAIN_TARGETS);
            if (chain != null) {
                RenderTarget target = this.mainRenderTarget;
                FrameGraphBuilder frameGraph = new FrameGraphBuilder();
                ResourceHandle<RenderTarget> handle = frameGraph.importExternal("main", target);
                chain.addToFrame(frameGraph, target.width, target.height, PostChain.TargetBundle.of(PostChain.MAIN_TARGET_ID, handle));
                frameGraph.execute(this.resourcePool);
            }
        } catch (Throwable t) {
            // Defensiver Fallback: Verhindert Client-Abstürze bei Drittmod-Inkompatibilitäten (z.B. Lunar/Iris-Shader-Pipelines)
        }
    }
}
