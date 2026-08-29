package com.oneshotonekill.client.mixin.input;

import com.oneshotonekill.client.state.ClientStates.MatchStartState;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sperrt die Mausdrehung während des Countdowns.
 *
 * <p>Fabric API kennt kein Gegenstück zu NeoForges {@code CalculatePlayerTurnEvent}. Der
 * Abbruch ist hier gefahrlos: {@code MouseHandler} setzt {@code accumulatedDX} und
 * {@code accumulatedDY} unmittelbar nach dem Aufruf ohnehin zurück, aufgestaute Mausbewegung
 * kann also nicht nachträglich losbrechen.</p>
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
   @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
   private void osok$lockTurn(double mousea, CallbackInfo ci) {
      if (MatchStartState.INSTANCE.isCountdownActive()) {
         ci.cancel();
      }
   }
}
