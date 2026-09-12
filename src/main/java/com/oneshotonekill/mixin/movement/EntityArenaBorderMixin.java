package com.oneshotonekill.mixin.movement;

import com.oneshotonekill.movement.ArenaBorderCollision;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Klinkt das physikalische Arena-Border-System direkt in Minecrafts native Kollisionsberechnung ein.
 */
@Mixin(Entity.class)
public abstract class EntityArenaBorderMixin {
    @Inject(method = "collide", at = @At("RETURN"), cancellable = true)
    private void osok$clampArenaBorderMovement(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        Vec3 candidate = cir.getReturnValue();
        if (candidate.x == 0.0 && candidate.y == 0.0 && candidate.z == 0.0) {
            return;
        }
        Entity self = (Entity) (Object) this;
        Vec3 clamped = ArenaBorderCollision.clampMovement(self, candidate);
        if (!clamped.equals(candidate)) {
            cir.setReturnValue(clamped);
        }
    }
}
