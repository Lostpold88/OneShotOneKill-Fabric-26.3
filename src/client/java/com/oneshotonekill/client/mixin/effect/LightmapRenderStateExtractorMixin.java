package com.oneshotonekill.client.mixin.effect;

import com.oneshotonekill.client.effect.BoogieBombClient;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Aktualisiert die Lightmap für getroffene Tänzer kontinuierlich (60/144+ FPS) und taucht
 * die Welt in pulsierende Neon-Disco-Farben synchron zum Beat der Musik.
 */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapRenderStateExtractorMixin {
    @Shadow
    private boolean needsUpdate;

    @Inject(method = "extract", at = @At("HEAD"))
    private void osok$forceBoogieLightmapUpdate(LightmapRenderState renderState, float partialTicks, CallbackInfo ci) {
        if (BoogieBombClient.isLocalDancing()) {
            this.needsUpdate = true;
        }
    }

    @Inject(method = "extract", at = @At("RETURN"))
    private void osok$applyBoogieDiscoLightmap(LightmapRenderState renderState, float partialTicks, CallbackInfo ci) {
        if (BoogieBombClient.isLocalDancing()) {
            renderState.needsUpdate = true;
            BoogieBombClient.applyDiscoLighting(renderState);
        }
    }
}
