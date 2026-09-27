package com.oneshotonekill.mixin.movement;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.oneshotonekill.movement.ArenaBorderCollision;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Klinkt das physikalische Arena-Border-System direkt in Minecrafts native Kollisionsberechnung ein.
 */
@Mixin(Entity.class)
public abstract class EntityArenaBorderMixin {
    @ModifyReturnValue(method = "collide", at = @At("RETURN"))
    private Vec3 osok$clampArenaBorderMovement(Vec3 original, Vec3 movement) {
        if (original.x == 0.0 && original.y == 0.0 && original.z == 0.0) {
            return original;
        }
        return ArenaBorderCollision.clampMovement((Entity) (Object) this, original);
    }
}
