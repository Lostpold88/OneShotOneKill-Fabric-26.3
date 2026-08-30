package com.oneshotonekill.client.mixin.effect;

import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Macht die Unschärfe hinter einem Bildschirm mehrfach anforderbar, statt hart abzustürzen.
 * <p>
 * <p>{@code GuiRenderState#blurBeforeThisStratum} wirft {@code IllegalStateException("Can only
 * blur once per frame")}, sobald in einem Bild ein zweites Mal unschärfegeblendet werden soll.
 * Genau das passiert, sobald ein eigener Bildschirm die Unschärfe anfordert und Vanilla in
 * derselben Bildfolge ebenfalls dazu kommt – etwa über {@code Screen#extractBlurredBackground}
 * eines darunterliegenden Bildschirms oder beim Wechsel zwischen zwei Menüs.</p>
 * <p>
 * <p>Fabric API bietet keinen Einstieg in den GUI-Renderstate, und ein Access Widener hilft
 * nicht: Der Wert ist bereits erreichbar, gebraucht wird ein anderes Verhalten. Der zweite
 * Aufruf wird deshalb still verworfen – die erste angeforderte Ebene bleibt gültig, und das
 * Ergebnis entspricht dem, was der Aufrufer erwartet hätte.</p>
 */
@Mixin(GuiRenderState.class)
public abstract class GuiRenderStateBlurMixin {
   @Shadow private int firstStratumAfterBlur;

   @Inject(method = "blurBeforeThisStratum", at = @At("HEAD"), cancellable = true)
   private void osok$allowRepeatedBlurRequest(CallbackInfo ci) {
      if (this.firstStratumAfterBlur != Integer.MAX_VALUE) {
         ci.cancel();
      }
   }
}
