package com.oneshotonekill.mixin.movement;

import com.oneshotonekill.shared.PhaseFields;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Wer in der Phasen-Kugel im Block steht, erstickt nicht darin.
 * <p>
 * {@code Entity#isInWall} fragt die Kollisionsform ohne Entity-Kontext ab und kommt deshalb an
 * {@code PhaseCollisionMixin} vorbei.
 */
@Mixin(Entity.class)
public abstract class PhaseInWallMixin {
   @Inject(method = "isInWall", at = @At("HEAD"), cancellable = true)
   private void osok$phaseNoSuffocation(CallbackInfoReturnable<Boolean> cir) {
      Entity self = (Entity) (Object) this;
      if (PhaseFields.of(self).lets(self, BlockPos.containing(self.getEyePosition()))) {
         cir.setReturnValue(false);
      }
   }
}