package com.oneshotonekill.client.mixin;

import com.oneshotonekill.client.ClientInputEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Legt den Fallout-Schleier nach dem Einschlag über die Welt.
 *
 * <p>Fabric API kennt weder ein Gegenstück zu NeoForges {@code ViewportEvent.RenderFog} noch zu
 * {@code ComputeFogColor}. Ein Access Widener genügt nicht: {@code FogData} ist bereits
 * öffentlich, aber ohne Einstieg gäbe es keinen Zeitpunkt, an dem die fertigen Werte noch
 * erreichbar wären.</p>
 *
 * <p>{@code setupFog} liefert am {@code RETURN} genau das eine Objekt, in dem Farbe und
 * Reichweite zusammenlaufen – damit deckt ein Einstieg beide NeoForge-Ereignisse ab.</p>
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
   @Inject(method = "setupFog", at = @At("RETURN"))
   private void osok$nukeFallout(Camera camera, int renderDistanceInChunks, DeltaTracker deltaTracker,
                                 float darkenWorldAmount, ClientLevel level,
                                 CallbackInfoReturnable<FogData> cir) {
      ClientInputEvents.applyNukeFog(cir.getReturnValue());
   }
}
