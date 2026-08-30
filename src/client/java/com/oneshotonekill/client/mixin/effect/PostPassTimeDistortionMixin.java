package com.oneshotonekill.client.mixin.effect;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.oneshotonekill.client.effect.TimeDistortionEffects;
import java.util.Map;
import net.minecraft.client.renderer.PostPass;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Speist pro gerendertem Frame neue Zeitbruchwerte in Vanillas ansonsten statische Post-UBOs.
 * Andere Post-Pässe besitzen keine Gruppe namens {@code TemporalConfig} und bleiben unberührt.
 */
@Mixin(PostPass.class)
public abstract class PostPassTimeDistortionMixin {
   @Shadow @Final private Map<String, GpuBuffer> customUniforms;
   @Unique private GpuBuffer osok$dynamicTemporalUniform;

   @Inject(method = "addToFrame", at = @At("HEAD"))
   private void osok$animateTemporalUniforms(FrameGraphBuilder frame,
                                             Map<?, ?> targets,
                                             GpuBufferSlice shaderOrthoMatrix,
                                             CallbackInfo ci) {
      GpuBuffer original = this.customUniforms.get("TemporalConfig");
      if (original == null) {
         return;
      }
      if (this.osok$dynamicTemporalUniform == null) {
         this.osok$dynamicTemporalUniform = RenderSystem.getDevice().createBuffer(
            () -> "OneShotOneKill dynamic temporal uniforms",
            GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
            original.size());
         this.customUniforms.put("TemporalConfig", this.osok$dynamicTemporalUniform);
         original.close();
      }
      TimeDistortionEffects.INSTANCE.writePostUniforms(this.osok$dynamicTemporalUniform);
   }
}
