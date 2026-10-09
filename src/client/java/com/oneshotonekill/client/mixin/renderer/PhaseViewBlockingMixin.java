package com.oneshotonekill.client.mixin.renderer;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.oneshotonekill.shared.PhaseFields;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Wer in der Phasen-Kugel durch einen Block geht, bekommt keine Blocktextur über den Bildschirm.
 * <p>
 * {@code getViewBlockingState} legt die Textur des Blocks vor die Kamera, sobald das Auge in einem
 * sichtsperrenden Block steckt – und das tut es hier, denn der Block ist ja noch da. Die Prüfung
 * wird für Blöcke übersprungen, die für den Spieler durchlässig sind.
 */
@Mixin(LevelExtractor.class)
public abstract class PhaseViewBlockingMixin {
   @WrapOperation(method = "getViewBlockingState", at = @At(value = "INVOKE",
      target = "Lnet/minecraft/world/level/block/state/BlockState;isViewBlocking(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/AABB;)Z"))
   private static boolean osok$phaseSeesThrough(BlockState state, BlockGetter level, BlockPos pos, AABB nearPlane,
                                                Operation<Boolean> original, LocalPlayer player, Frustum frustum) {
      return !PhaseFields.CLIENT.lets(player, pos) && original.call(state, level, pos, nearPlane);
   }
}
