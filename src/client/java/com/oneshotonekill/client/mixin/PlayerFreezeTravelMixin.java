package com.oneshotonekill.client.mixin;

import com.oneshotonekill.client.state.ClientStates.AbilityStatusState;
import com.oneshotonekill.client.state.ClientStates.MatchStartState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Verhindert Schwerkraft, Absacken und physikalische Bewegung während des Einfrierens und Countdowns.
 *
 * <p>{@link KeyboardInputMixin} schaltet die Tastendrücke (WASD, Springen) ab, aber die
 * Physikberechnung in {@code Player#travel} wendete auf dem Client weiterhin die Schwerkraft an.
 * Dadurch sackte der Spieler in der Luft ab, und der Server musste ihn per Teleport zurückziehen —
 * die Ursache für das Ruckeln (Rubberbanding) bei Frost-Fallen in der Luft.</p>
 *
 * <p>Durch das Abbrechen von {@code travel} am {@code HEAD} und das Nullen von {@code deltaMovement}
 * bleibt der Spieler im Raum exakt an seiner 3D-Position stehen, ohne dass Schwerkraft oder
 * Rutschen greifen. Umschauen bleibt uneingeschränkt möglich.</p>
 */
@Mixin(Player.class)
public abstract class PlayerFreezeTravelMixin {
   @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
   private void osok$freezeTravel(Vec3 input, CallbackInfo ci) {
      if (AbilityStatusState.INSTANCE.isFrozen() || MatchStartState.INSTANCE.isCountdownActive()) {
         Player self = (Player) (Object) this;
         self.setDeltaMovement(Vec3.ZERO);
         ci.cancel();
      }
   }
}
