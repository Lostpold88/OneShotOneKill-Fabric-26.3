package com.oneshotonekill.mixin.movement;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.oneshotonekill.shared.PhaseFields;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Ein Pfeil, der in einem Phasen-Block ankommt, bleibt dort nicht stecken.
 * <p>
 * {@code AbstractArrow#tick} prüft zu Beginn jedes Ticks, ob die Spitze in einer Kollisionsform
 * liegt, und fragt sie ohne Entity-Kontext ab – {@code PhaseCollisionMixin} sieht diese Abfrage
 * nicht. Ohne diese Weiche flöge der Pfeil zwar durch die Wand, steckte aber im nächsten Tick
 * mitten im Block.
 */
@Mixin(AbstractArrow.class)
public abstract class PhaseArrowMixin {
   @WrapOperation(method = "tick", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/block/state/BlockState;getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
   private VoxelShape osok$phaseThrough(BlockState state, BlockGetter level, BlockPos pos,
                                        Operation<VoxelShape> original) {
      AbstractArrow self = (AbstractArrow) (Object) this;
      return PhaseFields.of(self).lets(self, pos) ? Shapes.empty() : original.call(state, level, pos);
   }
}
