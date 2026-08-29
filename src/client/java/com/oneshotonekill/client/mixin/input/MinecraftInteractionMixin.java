package com.oneshotonekill.client.mixin.input;

import com.oneshotonekill.client.state.ClientStates.AbilityStatusState;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Unterdrückt Angriff, Benutzen und Pick-Block schon vor Handanimation und Netzwerkpaket.
 *
 * <p>Fabric API kennt kein Gegenstück zu NeoForges {@code InputEvent.InteractionKeyMappingTriggered}.
 * {@code AttackEntityCallback} und Verwandte greifen erst, wenn der Client die Interaktion bereits
 * angestoßen und die Hand geschwungen hat – der eingefrorene Spieler sähe also weiter seine
 * Schlagbewegung, während der Server sie verwirft. Ein Access Widener hilft nicht: Die drei
 * Methoden sind privat, aber es fehlt nicht der Zugriff, sondern die Abbruchmöglichkeit.</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftInteractionMixin {
   @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
   private void osok$blockAttack(CallbackInfoReturnable<Boolean> cir) {
      if (AbilityStatusState.INSTANCE.isFrozen()) {
         cir.setReturnValue(false);
      }
   }

   @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
   private void osok$blockContinueAttack(boolean down, CallbackInfo ci) {
      if (AbilityStatusState.INSTANCE.isFrozen()) {
         ci.cancel();
      }
   }

   @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
   private void osok$blockUseItem(CallbackInfo ci) {
      if (AbilityStatusState.INSTANCE.isFrozen()) {
         ci.cancel();
      }
   }

   @Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true)
   private void osok$blockPick(CallbackInfo ci) {
      if (AbilityStatusState.INSTANCE.isFrozen()) {
         ci.cancel();
      }
   }
}
