package com.oneshotonekill.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Unterdrückt das Zeichnen unsichtbarer Spielerfiguren vollständig.
 *
 * <p>Ersetzt NeoForges {@code RenderPlayerEvent.Pre}; Fabric API hat dazu kein Gegenstück.
 * Vanilla zeichnet einen unsichtbaren Spieler nicht als Körper, wohl aber weiter dessen
 * Ausrüstung und gehaltene Gegenstände – ein Tarnmantel liefe damit als schwebender Bogen
 * durch die Arena.</p>
 *
 * <p>Der Einstieg sitzt auf {@code LivingEntityRenderer#submit}, weil {@code AvatarRenderer}
 * diese Methode nicht selbst überschreibt; die Abfrage auf {@link AvatarRenderState} grenzt ihn
 * wieder auf Spielerfiguren ein.</p>
 *
 * <p>Die Signatur steht ausgeschrieben, weil {@code javap} neben der eigentlichen Methode noch
 * eine Brücke {@code submit(EntityRenderState, …)} zeigt: Ohne den Deskriptor träfe der
 * Einstieg beide, und das Bild liefe zweimal durch dieselbe Prüfung.</p>
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
   @Inject(
      method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
      at = @At("HEAD"),
      cancellable = true)
   private void osok$hideInvisibleAvatar(LivingEntityRenderState state, PoseStack poseStack,
                                         SubmitNodeCollector submitNodeCollector, CameraRenderState camera,
                                         CallbackInfo ci) {
      if (state instanceof AvatarRenderState && state.isInvisible) {
         ci.cancel();
      }
   }
}
