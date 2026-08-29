package com.oneshotonekill.mixin.effect;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Verhindert das Erzeugen von Staub-/Boden-Partikeln beim Sprinten/Rennen,
 * wenn die Entity (z. B. Spieler mit Unsichtbarkeits-Mantel) unsichtbar ist.
 */
@Mixin(Entity.class)
public abstract class EntitySprintParticleMixin {

   @Shadow
   public abstract boolean isInvisible();

   @Inject(method = "canSpawnSprintParticle", at = @At("HEAD"), cancellable = true)
   private void osok$cancelCanSpawnSprintParticle(CallbackInfoReturnable<Boolean> cir) {
      if (this.isInvisible()) {
         cir.setReturnValue(false);
      }
   }

   @Inject(method = "spawnSprintParticle", at = @At("HEAD"), cancellable = true)
   private void osok$cancelSpawnSprintParticle(CallbackInfo ci) {
      if (this.isInvisible()) {
         ci.cancel();
      }
   }
}
