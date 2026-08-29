package com.oneshotonekill.client.mixin.input;

import com.oneshotonekill.client.state.ClientStates.AbilityStatusState;
import com.oneshotonekill.client.state.ClientStates.MatchStartState;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sperrt Laufen und Springen während des Countdowns und im Eiskäfig.
 *
 * <p>Fabric API kennt kein Gegenstück zu NeoForges {@code MovementInputUpdateEvent}. Der Server
 * hält jeden zusätzlich auf seinem Startpunkt fest; nur damit allein ruckelte es aber sichtbar,
 * weil der Client losliefe und der Server zurückzöge. Die Eingabe hier zu verwerfen heißt, dass
 * es gar nicht erst zu einer Bewegung kommt, die zurückgenommen werden müsste.</p>
 *
 * <p>{@code KeyboardInput#tick} ist die Stelle, an der Vanilla die Tastenlage in Bewegung
 * übersetzt – die einzige Implementierung von {@code ClientInput}, die das tut. Am {@code TAIL}
 * statt am {@code HEAD}, damit das Ergebnis überschrieben wird und nicht die Eingabe.</p>
 *
 * <p>Die Klasse erbt von {@link ClientInput}, weil dort {@code keyPresses} und
 * {@code moveVector} deklariert sind – {@code KeyboardInput} selbst führt laut {@code javap}
 * kein einziges Feld. Über die Vererbung sind beide sichtbar, ohne sie zu schattieren.</p>
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
   @Inject(method = "tick", at = @At("TAIL"))
   private void osok$freezeMovement(CallbackInfo ci) {
      if (MatchStartState.INSTANCE.isCountdownActive() || AbilityStatusState.INSTANCE.isFrozen()) {
         this.keyPresses = Input.EMPTY;
         this.moveVector = Vec2.ZERO;
      }
   }
}
