package com.oneshotonekill.client.mixin.movement;

import com.oneshotonekill.client.state.ClientStates.GlideState;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Clientseite von {@code PlayerGlidePoseMixin}: Der lokale Spieler berechnet seine Haltung selbst
 * und würde die des Servers sonst jeden Tick zurücksetzen. Dieselbe Regel auf beiden Seiten -
 * im Geschirr und in der Luft -, damit die Haltung nicht flackert.
 */
@Mixin(Player.class)
public abstract class ClientPlayerGlidePoseMixin {
   @Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
   private void osok$glidePose(CallbackInfo ci) {
      Player self = (Player) (Object) this;
      if (self.level().isClientSide() && !self.onGround()
         && GlideState.INSTANCE.activePlayers().contains(self.getUUID())) {
         self.setPose(Pose.FALL_FLYING);
         ci.cancel();
      }
   }
}
