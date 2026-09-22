package com.oneshotonekill.client.mixin.effect;

import com.oneshotonekill.client.effect.BoogieBombClient;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Aktualisiert die Lightmap für getroffene Tänzer kontinuierlich (60/144+ FPS) und taucht
 * die Welt in pulsierende Neon-Disco-Farben synchron zum Beat der Musik.
 * Nach Beendigung des Tanzes (auch bei /kill oder vorzeitigem Tod) wird die Lightmap
 * für mehrere Frames forciert zurückgesetzt, damit kein eingefrorener Farbfilter auf der GPU verbleibt.
 */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapRenderStateExtractorMixin {
    @Shadow
    private boolean needsUpdate;

    @Unique
    private int osok$cleanupFrames;

    @Inject(method = "extract", at = @At("HEAD"))
    private void osok$forceBoogieLightmapUpdate(LightmapRenderState renderState, float partialTicks, CallbackInfo ci) {
        if (BoogieBombClient.isLocalDancing() || this.osok$cleanupFrames > 0) {
            this.needsUpdate = true;
        }
    }

    @Inject(method = "extract", at = @At("RETURN"))
    private void osok$applyBoogieDiscoLightmap(LightmapRenderState renderState, float partialTicks, CallbackInfo ci) {
        if (BoogieBombClient.isLocalDancing()) {
            renderState.needsUpdate = true;
            BoogieBombClient.applyDiscoLighting(renderState);
            this.osok$cleanupFrames = 3;
        } else if (this.osok$cleanupFrames > 0) {
            // Tanz wurde beendet (z. B. via /kill oder Ablauf):
            // Forciert für 3 Frames eine saubere Vanilla-Lightmap-Aktualisierung auf der GPU ohne Disco-Tint
            renderState.needsUpdate = true;
            this.needsUpdate = true;
            this.osok$cleanupFrames--;
        }
    }
}
