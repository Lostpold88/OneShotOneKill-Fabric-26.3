package com.oneshotonekill.mixin.movement;

import com.oneshotonekill.shared.PhaseFields;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Phasen-Granate: Blöcke in der Kugel haben für den Werfer keine Kollision.
 * <p>
 * Eine Stelle für alles: Bewegung, Serverprüfung der Bewegung und jeder Strahl mit
 * {@code ClipContext.Block.COLLIDER} und Entity-Kontext (Pfeile, Minigun, Railgun) fragen
 * hier nach der Form. Ein Kontext ohne Entity – wie ihn die Granaten selbst nutzen – bleibt
 * unberührt.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class PhaseCollisionMixin {
   @Inject(method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
      at = @At("HEAD"), cancellable = true)
   private void osok$phase(BlockGetter level, BlockPos pos, CollisionContext context,
                           CallbackInfoReturnable<VoxelShape> cir) {
      if (PhaseFields.letsThrown(pos)) {
         cir.setReturnValue(Shapes.empty());
         return;
      }
      if (context instanceof EntityCollisionContext entityContext) {
         Entity body = entityContext.getEntity();
         if (body != null && PhaseFields.of(body).lets(body, pos)) {
            cir.setReturnValue(Shapes.empty());
         }
      }
   }
}
