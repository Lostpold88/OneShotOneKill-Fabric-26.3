package com.oneshotonekill.client.mixin.renderer;

import com.oneshotonekill.client.renderer.PhaseGhost;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * An der Grenze der Phasen-Kugel gilt kein Verdeckungstest.
 * <p>
 * Vanilla lässt eine Fläche weg, wenn der Nachbar sie verdeckt. Für Kugelblöcke wäre der Nachbar
 * aber durchscheinend – ohne diese Weiche fehlten die Außenflächen der Kugel, und die Blöcke
 * dahinter hätten ein Loch dort, wo sie an die Kugel stoßen. Beide Block-Renderer, der von
 * Vanilla und der von Fabrics Indigo, fragen dieselbe statische Methode.
 */
@Mixin(Block.class)
public abstract class PhaseShouldRenderFaceMixin {
   @Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
   private static void osok$ghostBoundary(BlockState state, BlockState neighbour, Direction direction,
                                          CallbackInfoReturnable<Boolean> cir) {
      if (PhaseGhost.forcesFace(direction)) {
         cir.setReturnValue(true);
      }
   }
}
